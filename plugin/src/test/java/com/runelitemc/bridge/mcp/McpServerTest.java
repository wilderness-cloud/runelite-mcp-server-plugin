package com.runelitemc.bridge.mcp;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Before;
import org.junit.Test;

/**
 * Covers the JSON-RPC surface the Gielinor app drives: handshake, discovery,
 * calls, and the failure modes that used to show up as a dead handshake.
 */
public class McpServerTest
{
	private final Gson gson = new Gson();
	private McpServer mcp;

	@Before
	public void setUp()
	{
		mcp = new McpServer("gielinor-companion", "0.1.0", "test instructions")
			.register(new McpTool("echo", "Echoes its argument", McpTool.noArgs(), args ->
			{
				JsonObject o = new JsonObject();
				o.addProperty("got", args.has("value") ? args.get("value").getAsString() : "");
				return o;
			}))
			.register(new McpTool("boom", "Always fails", McpTool.noArgs(), args ->
			{
				throw new IllegalStateException("not logged in");
			}));
	}

	private JsonObject send(String json)
	{
		JsonElement response = mcp.handle(gson.fromJson(json, JsonElement.class));
		return response == null ? null : response.getAsJsonObject();
	}

	private static String textOf(JsonObject response)
	{
		return response.getAsJsonObject("result")
			.getAsJsonArray("content").get(0).getAsJsonObject()
			.get("text").getAsString();
	}

	@Test
	public void initializeEchoesAKnownProtocolAndAdvertisesTools()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-03-26\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"gielinor\",\"version\":\"1\"}}}");

		JsonObject result = res.getAsJsonObject("result");
		assertEquals("2.0", res.get("jsonrpc").getAsString());
		assertEquals(1, res.get("id").getAsInt());
		assertEquals("2025-03-26", result.get("protocolVersion").getAsString());
		assertEquals("gielinor-companion", result.getAsJsonObject("serverInfo").get("name").getAsString());
		assertTrue(result.getAsJsonObject("capabilities").has("tools"));
	}

	@Test
	public void initializeFallsBackToLatestOnAnUnknownProtocol()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"1999-01-01\"}}");

		assertEquals(McpServer.LATEST_PROTOCOL,
			res.getAsJsonObject("result").get("protocolVersion").getAsString());
	}

	@Test
	public void initializedNotificationGetsNoResponse()
	{
		assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
	}

	@Test
	public void toolsListReturnsRegisteredToolsWithSchemas()
	{
		JsonArray tools = send("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}")
			.getAsJsonObject("result").getAsJsonArray("tools");

		assertEquals(2, tools.size());
		JsonObject first = tools.get(0).getAsJsonObject();
		assertEquals("echo", first.get("name").getAsString());
		assertEquals("object", first.getAsJsonObject("inputSchema").get("type").getAsString());
	}

	@Test
	public void toolsCallReturnsTextContent()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"echo\",\"arguments\":{\"value\":\"hi\"}}}");

		assertTrue(textOf(res).contains("\"got\": \"hi\""));
		assertTrue(!res.getAsJsonObject("result").has("isError"));
		// Clients that read structuredContent get the same document, parsed.
		assertEquals("hi", res.getAsJsonObject("result")
			.getAsJsonObject("structuredContent").get("got").getAsString());
	}

	@Test
	public void toolFailureIsAResultNotAProtocolError()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"boom\",\"arguments\":{}}}");

		assertTrue(res.has("result"));
		assertTrue(res.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(textOf(res).contains("not logged in"));
	}

	@Test
	public void unknownToolIsAToolErrorNotADeadCall()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"nope\"}}");

		assertTrue(res.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(textOf(res).contains("unknown tool"));
		// The guessing model needs to see what it could have called instead.
		assertTrue(textOf(res).contains("echo, boom"));
	}

	@Test
	public void unknownMethodIsMethodNotFound()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":6,\"method\":\"resources/subscribe\"}");
		assertEquals(-32601, res.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void batchesAnswerOnlyTheRequests()
	{
		JsonElement res = mcp.handle(gson.fromJson(
			"[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
				+ "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"ping\"}]", JsonElement.class));

		JsonArray responses = res.getAsJsonArray();
		assertEquals(1, responses.size());
		assertEquals(7, responses.get(0).getAsJsonObject().get("id").getAsInt());
	}

	/**
	 * A body with no "method" is not a notification, it is a broken request —
	 * answering it with silence made the caller report a parse failure on the
	 * empty body instead of the malformed envelope it actually sent.
	 */
	@Test
	public void aBareToolCallIsAnInvalidRequestNotSilence()
	{
		JsonObject res = send("{\"tool\":\"get_bank\"}");

		assertEquals(-32600, res.getAsJsonObject("error").get("code").getAsInt());
		assertTrue(res.get("id").isJsonNull());

		String message = res.getAsJsonObject("error").get("message").getAsString();
		assertTrue(message, message.contains("method"));
		assertTrue(message, message.contains("tools/call"));
		assertTrue(message, message.contains("echo, boom"));
	}

	@Test
	public void aMalformedRequestKeepsItsIdSoTheClientCanMatchIt()
	{
		JsonObject res = send("{\"id\":9,\"tool\":\"get_bank\"}");

		assertEquals(9, res.get("id").getAsInt());
		assertEquals(-32600, res.getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void aRequestMethodWithoutAnIdIsRejectedRatherThanDropped()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"echo\"}}");

		assertEquals(-32600, res.getAsJsonObject("error").get("code").getAsInt());
		assertTrue(res.getAsJsonObject("error").get("message").getAsString().contains("id"));
	}

	/** Real notifications keep their silence; that is the half the spec insists on. */
	@Test
	public void everyNotificationShapeStaysSilent()
	{
		assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/cancelled\",\"params\":{}}"));
		assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}"));
		// Pre-spec clients that send the bare name; tolerated for the same reason.
		assertNull(send("{\"jsonrpc\":\"2.0\",\"method\":\"initialized\"}"));
		// An explicit null id is the notification form too.
		assertNull(send("{\"jsonrpc\":\"2.0\",\"id\":null,\"method\":\"notifications/initialized\"}"));
	}

	@Test
	public void emptyBatchIsAnInvalidRequest()
	{
		JsonElement res = mcp.handle(gson.fromJson("[]", JsonElement.class));
		assertEquals(-32600, res.getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
	}

	@Test
	public void aBatchOfBrokenEntriesAnswersEveryOne()
	{
		JsonArray responses = mcp.handle(gson.fromJson(
			"[{\"tool\":\"get_bank\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"},"
				+ "{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"ping\"}]", JsonElement.class)).getAsJsonArray();

		// The notification stays silent; the broken entry and the ping both answer.
		assertEquals(2, responses.size());
		assertEquals(-32600, responses.get(0).getAsJsonObject().getAsJsonObject("error").get("code").getAsInt());
		assertEquals(8, responses.get(1).getAsJsonObject().get("id").getAsInt());
	}

	/** The name the caller reached for, sent properly: a usable error, not a dead call. */
	@Test
	public void theCorrectedCallExplainsTheToolNameInstead()
	{
		JsonObject res = send("{\"jsonrpc\":\"2.0\",\"id\":10,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"get_bank\",\"arguments\":{}}}");

		assertTrue(res.getAsJsonObject("result").get("isError").getAsBoolean());
		assertTrue(textOf(res).contains("unknown tool: get_bank"));
		assertTrue(textOf(res).contains("echo, boom"));
	}
}
