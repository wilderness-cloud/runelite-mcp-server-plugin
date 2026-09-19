package com.runelitemc.bridge.mcp;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
import java.io.StringWriter;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import lombok.extern.slf4j.Slf4j;

/**
 * Minimal MCP server: JSON-RPC 2.0 dispatch over whatever transport hands it a
 * parsed message. Hand-rolled on purpose — the official MCP Java SDK requires
 * JDK 17 while the client is Java 11, and shading anything into a sideloaded
 * plugin invites the child-first-classloader ClassCastException trap.
 *
 * Stateless: no session id is issued, so a client survives the plugin being
 * toggled off and on (or the whole game client restarting) by re-initializing,
 * with no stale-session 404s in between.
 */
@Slf4j
public class McpServer
{
	public static final String LATEST_PROTOCOL = "2025-06-18";
	private static final Set<String> SUPPORTED_PROTOCOLS = new HashSet<>(
		Arrays.asList("2025-06-18", "2025-03-26", "2024-11-05"));

	private static final int PARSE_ERROR = -32700;
	private static final int INVALID_REQUEST = -32600;
	private static final int METHOD_NOT_FOUND = -32601;
	private static final int INTERNAL_ERROR = -32603;

	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
	private final Map<String, McpTool> tools = new LinkedHashMap<>();
	private final String name;
	private final String version;
	private final String instructions;

	public McpServer(String name, String version, String instructions)
	{
		this.name = name;
		this.version = version;
		this.instructions = instructions;
	}

	public McpServer register(McpTool tool)
	{
		tools.put(tool.name(), tool);
		return this;
	}

	/**
	 * Dispatches one parsed JSON-RPC message (or a batch array). Returns null
	 * when nothing should be sent back, i.e. the message was purely
	 * notifications, which the transport answers with 202 Accepted.
	 */
	public JsonElement handle(JsonElement message)
	{
		if (message == null || message.isJsonNull())
		{
			return errorResponse(null, PARSE_ERROR, "empty request body");
		}

		if (message.isJsonArray())
		{
			// Batching was dropped in 2025-06-18, but older clients still send it.
			if (message.getAsJsonArray().size() == 0)
			{
				return errorResponse(null, INVALID_REQUEST, "empty batch: no JSON-RPC requests to answer");
			}

			JsonArray responses = new JsonArray();
			for (JsonElement entry : message.getAsJsonArray())
			{
				JsonElement response = handleOne(entry);
				if (response != null)
				{
					responses.add(response);
				}
			}
			return responses.size() == 0 ? null : responses;
		}

		return handleOne(message);
	}

	private JsonElement handleOne(JsonElement message)
	{
		if (!message.isJsonObject())
		{
			return errorResponse(null, INVALID_REQUEST, "request must be a JSON-RPC object");
		}

		JsonObject request = message.getAsJsonObject();
		JsonElement id = request.get("id");
		boolean isNotification = id == null || id.isJsonNull();
		String method = request.has("method") && request.get("method").isJsonPrimitive()
			? request.get("method").getAsString()
			: null;

		if (method == null)
		{
			// Not a JSON-RPC message at all — someone POSTed a bare tool call like
			// {"tool":"get_bank"}. Answering matters more than it looks: the old
			// path treated this as a notification and replied 202 with no body, so
			// the caller's parser choked on zero bytes and reported an unrelated
			// "Parse error" instead of the mistake it actually made.
			return errorResponse(id, INVALID_REQUEST, "missing \"method\": " + envelopeHint());
		}

		if (isNotification)
		{
			// Genuine notifications get no reply, as the spec requires.
			if (method.startsWith("notifications/") || "initialized".equals(method))
			{
				return null;
			}
			// A request that forgot its id would otherwise wait forever for an
			// answer the spec forbids sending.
			return errorResponse(null, INVALID_REQUEST,
				"request \"" + method + "\" is missing an \"id\" (only notifications/* may omit it): " + envelopeHint());
		}

		JsonObject params = request.has("params") && request.get("params").isJsonObject()
			? request.getAsJsonObject("params")
			: new JsonObject();

		try
		{
			switch (method)
			{
				case "initialize":
					return result(id, initialize(params));
				case "ping":
					return result(id, new JsonObject());
				case "tools/list":
					return result(id, listTools());
				case "tools/call":
					return result(id, callTool(params));
				case "resources/list":
					return result(id, emptyList("resources"));
				case "prompts/list":
					return result(id, emptyList("prompts"));
				default:
					return errorResponse(id, METHOD_NOT_FOUND, "unknown method: " + method);
			}
		}
		catch (Exception e)
		{
			log.warn("MCP method {} failed", method, e);
			return errorResponse(id, INTERNAL_ERROR, String.valueOf(e));
		}
	}

	private JsonObject initialize(JsonObject params)
	{
		String requested = params.has("protocolVersion") && params.get("protocolVersion").isJsonPrimitive()
			? params.get("protocolVersion").getAsString()
			: null;

		JsonObject toolsCap = new JsonObject();
		toolsCap.addProperty("listChanged", false);
		JsonObject capabilities = new JsonObject();
		capabilities.add("tools", toolsCap);

		JsonObject serverInfo = new JsonObject();
		serverInfo.addProperty("name", name);
		serverInfo.addProperty("version", version);

		JsonObject o = new JsonObject();
		// Echo back a version we both know; otherwise offer ours.
		o.addProperty("protocolVersion",
			requested != null && SUPPORTED_PROTOCOLS.contains(requested) ? requested : LATEST_PROTOCOL);
		o.add("capabilities", capabilities);
		o.add("serverInfo", serverInfo);
		if (instructions != null)
		{
			o.addProperty("instructions", instructions);
		}
		return o;
	}

	private JsonObject listTools()
	{
		JsonArray list = new JsonArray();
		for (McpTool tool : tools.values())
		{
			list.add(tool.describe());
		}
		JsonObject o = new JsonObject();
		o.add("tools", list);
		return o;
	}

	private JsonObject emptyList(String key)
	{
		JsonObject o = new JsonObject();
		o.add(key, new JsonArray());
		return o;
	}

	private JsonObject callTool(JsonObject params)
	{
		String toolName = params.has("name") && params.get("name").isJsonPrimitive()
			? params.get("name").getAsString()
			: null;
		McpTool tool = toolName == null ? null : tools.get(toolName);
		if (tool == null)
		{
			// Name the alternatives: a model that guessed a tool name can fix
			// itself on the next turn instead of guessing again.
			return toolResult("unknown tool: " + toolName
				+ ". Available tools: " + String.join(", ", tools.keySet())
				+ ". Skills, quests, diaries, slayer, boss killcounts, inventory and equipment all come from game_state"
				+ " (use its sections argument to narrow the response).", true);
		}

		JsonObject args = params.has("arguments") && params.get("arguments").isJsonObject()
			? params.getAsJsonObject("arguments")
			: new JsonObject();

		try
		{
			JsonElement value = tool.handler().call(args);
			// Both halves: structuredContent for clients that parse it, the same
			// JSON as text for those that don't. The spec asks for the text copy.
			return toolResult(pretty(value), false,
				value != null && value.isJsonObject() ? value.getAsJsonObject() : null);
		}
		catch (Exception e)
		{
			// Tool failures are results with isError, not JSON-RPC errors: the
			// model is meant to see them and react (log in, open the bank, ...).
			log.debug("MCP tool {} failed", toolName, e);
			Throwable cause = e.getCause() != null ? e.getCause() : e;
			return toolResult(toolName + " failed: " + cause.getMessage(), true);
		}
	}

	/**
	 * What a well-formed call looks like, with the real tool names in it. A model
	 * or a hand-rolled client that guessed the envelope can fix itself from this
	 * rather than guess again.
	 */
	private String envelopeHint()
	{
		String example = tools.isEmpty() ? "<tool>" : tools.keySet().iterator().next();
		return "expected JSON-RPC 2.0, e.g. {\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"" + example + "\",\"arguments\":{}}}."
			+ " Available tools: " + String.join(", ", tools.keySet()) + ".";
	}

	private JsonObject toolResult(String body, boolean isError)
	{
		return toolResult(body, isError, null);
	}

	private JsonObject toolResult(String body, boolean isError, JsonObject structured)
	{
		JsonObject entry = new JsonObject();
		entry.addProperty("type", "text");
		entry.addProperty("text", body);

		JsonArray content = new JsonArray();
		content.add(entry);

		JsonObject o = new JsonObject();
		o.add("content", content);
		if (structured != null)
		{
			o.add("structuredContent", structured);
		}
		if (isError)
		{
			o.addProperty("isError", true);
		}
		return o;
	}

	/** One-space indent: readable for the model without burning tokens on padding. */
	private String pretty(JsonElement value)
	{
		if (value == null || value.isJsonNull())
		{
			return "null";
		}
		try
		{
			StringWriter out = new StringWriter();
			JsonWriter writer = new JsonWriter(out);
			writer.setIndent(" ");
			gson.toJson(value, writer);
			return out.toString();
		}
		catch (Exception e)
		{
			return gson.toJson(value);
		}
	}

	private JsonObject result(JsonElement id, JsonObject payload)
	{
		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id);
		o.add("result", payload);
		return o;
	}

	public JsonObject errorResponse(JsonElement id, int code, String message)
	{
		JsonObject error = new JsonObject();
		error.addProperty("code", code);
		error.addProperty("message", message);

		JsonObject o = new JsonObject();
		o.addProperty("jsonrpc", "2.0");
		o.add("id", id);
		o.add("error", error);
		return o;
	}
}
