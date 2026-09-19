package com.runelitemc.bridge;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.runelitemc.bridge.mcp.McpServer;
import com.runelitemc.bridge.mcp.McpTool;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

/**
 * The transport half: an MCP client POSTs JSON-RPC to /mcp and the read-only
 * GET endpoints keep working beside it. Guards the regression that started all
 * this — a POST to the plugin coming back "405 read-only server: GET only".
 */
public class BridgeHttpServerTest
{
	private final Gson gson = new Gson();
	private BridgeHttpServer server;
	private String base;

	@Before
	public void setUp() throws IOException
	{
		Map<String, BridgeHttpServer.Responder> routes = new HashMap<>();
		routes.put("/health", () ->
		{
			JsonObject o = new JsonObject();
			o.addProperty("status", "ok");
			return o;
		});

		McpServer mcp = new McpServer("gielinor-companion", "0.1.0", null)
			.register(new McpTool("ping_state", "test tool", McpTool.noArgs(), args ->
			{
				JsonObject o = new JsonObject();
				o.addProperty("loggedIn", true);
				return o;
			}));

		server = BridgeHttpServer.start(0, routes, mcp);
		base = "http://127.0.0.1:" + server.port();
	}

	@After
	public void tearDown()
	{
		server.stop();
	}

	private static final class Response
	{
		private int status;
		private String body;
	}

	private Response post(String path, String json) throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
		conn.setRequestMethod("POST");
		conn.setDoOutput(true);
		conn.setRequestProperty("Content-Type", "application/json");
		try (OutputStream out = conn.getOutputStream())
		{
			out.write(json.getBytes(StandardCharsets.UTF_8));
		}
		return read(conn);
	}

	private Response get(String path) throws IOException
	{
		HttpURLConnection conn = (HttpURLConnection) new URL(base + path).openConnection();
		conn.setRequestMethod("GET");
		return read(conn);
	}

	private Response read(HttpURLConnection conn) throws IOException
	{
		Response response = new Response();
		response.status = conn.getResponseCode();
		InputStream in = response.status >= 400 ? conn.getErrorStream() : conn.getInputStream();
		if (in == null)
		{
			response.body = "";
			return response;
		}
		ByteArrayOutputStream buf = new ByteArrayOutputStream();
		byte[] chunk = new byte[4096];
		int n;
		while ((n = in.read(chunk)) > 0)
		{
			buf.write(chunk, 0, n);
		}
		response.body = new String(buf.toByteArray(), StandardCharsets.UTF_8);
		return response;
	}

	@Test
	public void mcpHandshakeOverHttp() throws IOException
	{
		Response res = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\","
			+ "\"params\":{\"protocolVersion\":\"2025-06-18\",\"capabilities\":{},"
			+ "\"clientInfo\":{\"name\":\"gielinor\",\"version\":\"1\"}}}");

		assertEquals(200, res.status);
		JsonObject body = gson.fromJson(res.body, JsonObject.class);
		assertEquals("gielinor-companion",
			body.getAsJsonObject("result").getAsJsonObject("serverInfo").get("name").getAsString());
	}

	@Test
	public void notificationsAreAccepted() throws IOException
	{
		Response res = post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
		assertEquals(202, res.status);
		assertEquals("", res.body);
	}

	@Test
	public void toolsCallOverHttp() throws IOException
	{
		Response res = post("/mcp", "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"ping_state\",\"arguments\":{}}}");

		assertEquals(200, res.status);
		assertTrue(res.body.contains("loggedIn"));
	}

	@Test
	public void malformedJsonIsAParseError() throws IOException
	{
		Response res = post("/mcp", "{not json");
		assertEquals(400, res.status);
		assertEquals(-32700, gson.fromJson(res.body, JsonObject.class)
			.getAsJsonObject("error").get("code").getAsInt());
		assertTrue(res.body, res.body.contains("\"id\":null"));
	}

	@Test
	public void getOnMcpSaysPostInstead() throws IOException
	{
		Response res = get("/mcp");
		assertEquals(405, res.status);
		assertTrue(res.body.contains("POST"));
	}

	@Test
	public void restEndpointsStillServeGet() throws IOException
	{
		assertEquals(200, get("/health").status);
		assertTrue(get("/health").body.contains("\"ok\""));
	}

	@Test
	public void indexAdvertisesTheMcpEndpoint() throws IOException
	{
		Response res = get("/");
		assertEquals(200, res.status);
		assertTrue(res.body.contains("\"mcp\":\"/mcp\""));
	}

	@Test
	public void postToAReadOnlyEndpointStillRefuses() throws IOException
	{
		Response res = post("/health", "{}");
		assertEquals(405, res.status);
		assertTrue(res.body.contains("/mcp"));
	}

	/**
	 * The bug this guards: a body that is not JSON-RPC at all — {"tool":"get_bank"},
	 * a bare tool call — has no "id", so it used to be mistaken for a notification
	 * and answered 202 with zero bytes. The client then failed parsing an empty
	 * body and reported "Parse error", which says nothing about the real mistake.
	 */
	@Test
	public void bareToolCallGetsAJsonRpcErrorNotAnEmptyBody() throws IOException
	{
		Response res = post("/mcp", "{\"tool\":\"get_bank\"}");

		assertEquals(200, res.status);
		JsonObject body = gson.fromJson(res.body, JsonObject.class);
		assertEquals(-32600, body.getAsJsonObject("error").get("code").getAsInt());

		// "id": null is required on an error whose request had no usable id, and
		// it has to survive serialization: a client that validates the envelope
		// rejects a message without the member, parse failure all over again.
		assertTrue(res.body, res.body.contains("\"id\":null"));
		assertTrue(body.has("id") && body.get("id").isJsonNull());

		String message = body.getAsJsonObject("error").get("message").getAsString();
		assertTrue(message, message.contains("method"));
		// It has to name the way out: the envelope and the tools that exist.
		assertTrue(message, message.contains("tools/call"));
		assertTrue(message, message.contains("ping_state"));
	}

	@Test
	public void requestWithoutAnIdIsAnsweredRatherThanDropped() throws IOException
	{
		Response res = post("/mcp", "{\"jsonrpc\":\"2.0\",\"method\":\"tools/call\","
			+ "\"params\":{\"name\":\"ping_state\",\"arguments\":{}}}");

		assertEquals(200, res.status);
		String message = gson.fromJson(res.body, JsonObject.class)
			.getAsJsonObject("error").get("message").getAsString();
		assertTrue(message, message.contains("id"));
	}

	@Test
	public void emptyBodyIsAParseError() throws IOException
	{
		Response res = post("/mcp", "");
		assertEquals(400, res.status);
		assertEquals(-32700, gson.fromJson(res.body, JsonObject.class)
			.getAsJsonObject("error").get("code").getAsInt());
	}

	/** Every POST leaves either a JSON body or a bodiless 202 — never a 200 with nothing in it. */
	@Test
	public void noPostEverAnswersWithAnUnparseableBody() throws IOException
	{
		String[] bodies = {
			"{\"tool\":\"get_bank\"}",
			"{}",
			"[]",
			"\"hello\"",
			"{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"get_bank\"}}",
			"{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
		};

		for (String body : bodies)
		{
			Response res = post("/mcp", body);
			if (res.status == 202)
			{
				assertEquals(body, "", res.body);
				continue;
			}
			assertTrue(body + " -> " + res.status, res.body.length() > 0);
			// Parses, and is a complete JSON-RPC message rather than an arbitrary
			// blob: jsonrpc, an id member (null when the request gave none), and
			// exactly one of result/error.
			JsonObject parsed = gson.fromJson(res.body, JsonObject.class);
			assertEquals(body, "2.0", parsed.get("jsonrpc").getAsString());
			assertTrue(body, parsed.has("id"));
			assertTrue(body, parsed.has("result") ^ parsed.has("error"));
		}
	}
}
