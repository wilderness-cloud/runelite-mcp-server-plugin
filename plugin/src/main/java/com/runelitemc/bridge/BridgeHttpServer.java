package com.runelitemc.bridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.runelitemc.bridge.mcp.McpServer;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.Reader;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;

/**
 * Read-only JSON server bound to 127.0.0.1. Route suppliers may block briefly
 * while hopping to the client thread, so the executor is a small fixed pool.
 */
@Slf4j
public class BridgeHttpServer
{
	public interface Responder
	{
		JsonElement respond() throws Exception;
	}

	public static final String MCP_PATH = "/mcp";
	private static final int MAX_MCP_BODY_BYTES = 1024 * 1024;

	private final HttpServer server;
	private final ExecutorService executor;
	private final Gson gson = new GsonBuilder().disableHtmlEscaping().create();
	/**
	 * JSON-RPC replies only. An error the request gave no usable id for still has
	 * to carry {@code "id": null} — the member is required, and a client that
	 * validates the envelope rejects a message missing it, which lands back on
	 * the caller as an unexplained parse failure. The default Gson drops nulls,
	 * hence the second instance; no tool payload contains one, so nothing else
	 * changes shape.
	 */
	private final Gson rpcGson = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();
	private final Map<String, Responder> routes;
	private final McpServer mcp;

	private BridgeHttpServer(HttpServer server, ExecutorService executor, Map<String, Responder> routes, McpServer mcp)
	{
		this.server = server;
		this.executor = executor;
		this.routes = routes;
		this.mcp = mcp;
	}

	public static BridgeHttpServer start(int port, Map<String, Responder> routes, McpServer mcp) throws IOException
	{
		HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
		ExecutorService executor = Executors.newFixedThreadPool(4);
		BridgeHttpServer bridge = new BridgeHttpServer(server, executor, routes, mcp);
		server.setExecutor(executor);
		server.createContext("/", bridge::handle);
		server.start();
		return bridge;
	}

	/** The bound port; differs from the configured one only when it was 0 (tests). */
	public int port()
	{
		return server.getAddress().getPort();
	}

	public void stop()
	{
		server.stop(0);
		executor.shutdownNow();
	}

	private void handle(HttpExchange exchange) throws IOException
	{
		try
		{
			if (!isLocalHost(exchange))
			{
				respond(exchange, 403, error("forbidden: non-local Host header"));
				return;
			}

			String method = exchange.getRequestMethod();
			String path = exchange.getRequestURI().getPath();
			if (path == null)
			{
				path = "/";
			}

			if (MCP_PATH.equals(path))
			{
				handleMcp(exchange, method);
				return;
			}

			if (!"GET".equals(method) && !"HEAD".equals(method))
			{
				respond(exchange, 405, error("read-only server: GET only (MCP is served at " + MCP_PATH + ")"));
				return;
			}

			if (path.equals("/"))
			{
				JsonObject index = new JsonObject();
				index.addProperty("service", "gielinor-companion");
				index.addProperty("mcp", MCP_PATH);
				index.add("endpoints", gson.toJsonTree(routes.keySet()));
				respond(exchange, 200, index);
				return;
			}

			Responder responder = routes.get(path);
			if (responder == null)
			{
				respond(exchange, 404, error("unknown endpoint: " + path));
				return;
			}

			respond(exchange, 200, responder.respond());
		}
		catch (Exception e)
		{
			log.warn("Error handling request", e);
			respond(exchange, 500, error(String.valueOf(e)));
		}
		finally
		{
			exchange.close();
		}
	}

	/**
	 * MCP Streamable HTTP, the request/response half of it: clients POST a
	 * JSON-RPC message and get the reply in the body. No SSE stream is offered
	 * (nothing here is server-initiated), so GET answers 405 as the spec allows,
	 * and no session id is issued, so there is no session to go stale.
	 *
	 * Deliberately no CORS headers: with the Host check below they would be the
	 * one thing letting any web page you visit read your account.
	 */
	private void handleMcp(HttpExchange exchange, String method) throws IOException
	{
		if (!"POST".equals(method))
		{
			exchange.getResponseHeaders().set("Allow", "POST");
			respond(exchange, 405, error("MCP endpoint accepts POST (no SSE stream is offered on GET)"));
			return;
		}

		JsonElement request;
		try (Reader reader = new InputStreamReader(new BoundedInputStream(exchange.getRequestBody(), MAX_MCP_BODY_BYTES), StandardCharsets.UTF_8))
		{
			request = gson.fromJson(reader, JsonElement.class);
		}
		catch (Exception e)
		{
			respondRpc(exchange, 400, mcp.errorResponse(null, -32700, "invalid JSON: " + e.getMessage()));
			return;
		}

		if (request == null || request.isJsonNull())
		{
			// An empty POST body is as unparseable as a broken one; answer it the
			// same way rather than with a 200 that says otherwise.
			respondRpc(exchange, 400, mcp.errorResponse(null, -32700, "empty request body"));
			return;
		}

		JsonElement response = mcp.handle(request);
		if (response == null)
		{
			// Notifications only: accepted, nothing to say back.
			exchange.sendResponseHeaders(202, -1);
			return;
		}
		respondRpc(exchange, 200, response);
	}

	/** Caps how much of a request body we will parse. */
	private static final class BoundedInputStream extends FilterInputStream
	{
		private final long limit;
		private long read;

		private BoundedInputStream(InputStream in, long limit)
		{
			super(in);
			this.limit = limit;
		}

		@Override
		public int read() throws IOException
		{
			int b = super.read();
			if (b >= 0 && ++read > limit)
			{
				throw new IOException("request body exceeds " + limit + " bytes");
			}
			return b;
		}

		@Override
		public int read(byte[] buf, int off, int len) throws IOException
		{
			int n = super.read(buf, off, len);
			if (n > 0)
			{
				read += n;
				if (read > limit)
				{
					throw new IOException("request body exceeds " + limit + " bytes");
				}
			}
			return n;
		}
	}

	/**
	 * Reject non-local Host headers (DNS rebinding hardening); the socket only
	 * listens on 127.0.0.1 anyway. The machine's own interface addresses are
	 * also accepted so requests forwarded from a WSL-side agent (via the WSL
	 * vEthernet adapter or a portproxy) pass.
	 */
	private boolean isLocalHost(HttpExchange exchange)
	{
		String host = exchange.getRequestHeaders().getFirst("Host");
		if (host == null)
		{
			return false;
		}
		String h = host.toLowerCase(Locale.ROOT);
		int colon = h.lastIndexOf(':');
		if (colon >= 0)
		{
			h = h.substring(0, colon);
		}
		if (h.startsWith("[") && h.endsWith("]"))
		{
			h = h.substring(1, h.length() - 1);
		}
		if (h.equals("127.0.0.1") || h.equals("localhost") || h.equals("::1"))
		{
			return true;
		}
		return localAddresses().contains(h);
	}

	private volatile Set<String> cachedLocalAddresses = Set.of();
	private volatile long localAddressesCheckedAt;

	private Set<String> localAddresses()
	{
		if (System.currentTimeMillis() - localAddressesCheckedAt < 30_000)
		{
			return cachedLocalAddresses;
		}
		Set<String> found = new HashSet<>();
		try
		{
			for (NetworkInterface nic : Collections.list(NetworkInterface.getNetworkInterfaces()))
			{
				for (InetAddress addr : Collections.list(nic.getInetAddresses()))
				{
					found.add(addr.getHostAddress().split("%")[0]);
				}
			}
		}
		catch (SocketException ignored)
		{
			// fall back to whatever was cached
		}
		cachedLocalAddresses = found;
		localAddressesCheckedAt = System.currentTimeMillis();
		return found;
	}

	private JsonObject error(String message)
	{
		JsonObject o = new JsonObject();
		o.addProperty("error", message);
		return o;
	}

	/** A JSON-RPC message, written so a null id survives to the wire. */
	private void respondRpc(HttpExchange exchange, int code, JsonElement body) throws IOException
	{
		write(exchange, code, rpcGson.toJson(body));
	}

	private void respond(HttpExchange exchange, int code, JsonElement body) throws IOException
	{
		write(exchange, code, gson.toJson(body));
	}

	private void write(HttpExchange exchange, int code, String json) throws IOException
	{
		byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
		exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
		exchange.getResponseHeaders().set("Cache-Control", "no-store");
		exchange.sendResponseHeaders(code, bytes.length);
		if (!"HEAD".equals(exchange.getRequestMethod()))
		{
			try (OutputStream out = exchange.getResponseBody())
			{
				out.write(bytes);
			}
		}
	}
}
