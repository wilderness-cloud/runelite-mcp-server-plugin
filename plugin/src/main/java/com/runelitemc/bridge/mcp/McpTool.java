package com.runelitemc.bridge.mcp;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;

/**
 * One MCP tool: the metadata advertised by {@code tools/list} plus the handler
 * {@code tools/call} runs. Handlers execute on an HTTP worker thread and may
 * block briefly — the game-state ones hop to the client thread internally.
 */
public final class McpTool
{
	public interface Handler
	{
		JsonElement call(JsonObject args) throws Exception;
	}

	private final String name;
	private final String description;
	private final JsonObject inputSchema;
	private final JsonObject outputSchema;
	private final Handler handler;

	public McpTool(String name, String description, JsonObject inputSchema, Handler handler)
	{
		this(name, description, inputSchema, null, handler);
	}

	public McpTool(String name, String description, JsonObject inputSchema, JsonObject outputSchema, Handler handler)
	{
		this.name = name;
		this.description = description;
		this.inputSchema = inputSchema;
		this.outputSchema = outputSchema;
		this.handler = handler;
	}

	public String name()
	{
		return name;
	}

	public Handler handler()
	{
		return handler;
	}

	public JsonObject describe()
	{
		JsonObject o = new JsonObject();
		o.addProperty("name", name);
		o.addProperty("description", description);
		o.add("inputSchema", inputSchema);
		if (outputSchema != null)
		{
			// Declared alongside the structuredContent every call returns, so a
			// client can validate the payload against the contract it was given.
			o.add("outputSchema", outputSchema);
		}
		return o;
	}

	/** Schema for a tool that takes no arguments. */
	public static JsonObject noArgs()
	{
		return schema(new JsonObject());
	}

	/** Object schema wrapper; {@code required} names must exist in properties. */
	public static JsonObject schema(JsonObject properties, String... required)
	{
		JsonObject o = new JsonObject();
		o.addProperty("type", "object");
		o.add("properties", properties);
		if (required.length > 0)
		{
			JsonArray req = new JsonArray();
			for (String name : required)
			{
				req.add(name);
			}
			o.add("required", req);
		}
		return o;
	}

	/** An array-of-enum property, e.g. the game_state section filter. */
	public static JsonObject enumArray(String description, List<String> values)
	{
		JsonArray allowed = new JsonArray();
		for (String value : values)
		{
			allowed.add(value);
		}

		JsonObject items = new JsonObject();
		items.addProperty("type", "string");
		items.add("enum", allowed);

		JsonObject o = new JsonObject();
		o.addProperty("type", "array");
		o.add("items", items);
		o.addProperty("description", description);
		return o;
	}
}
