/**
 * Streamable HTTP transport, for MCP clients that can only be pointed at a URL.
 *
 * Note the two ports are different things: the RuneLite plugin (8765) serves
 * read-only game-state JSON over GET and is *not* an MCP endpoint — an MCP
 * client aimed at it gets `405 {"error":"read-only server: GET only"}` on its
 * first POST and gives up. This module serves the real MCP endpoint, which
 * proxies the plugin, at http://127.0.0.1:<port>/mcp.
 */
import { randomUUID } from "node:crypto";
import { createServer, type IncomingMessage, type ServerResponse } from "node:http";
import { McpServer } from "@modelcontextprotocol/sdk/server/mcp.js";
import { StreamableHTTPServerTransport } from "@modelcontextprotocol/sdk/server/streamableHttp.js";
import { isInitializeRequest } from "@modelcontextprotocol/sdk/types.js";
import { registerTools } from "./tools.js";

export const DEFAULT_HTTP_PORT = 8766;
const MCP_PATH = "/mcp";
const MAX_BODY_BYTES = 4 * 1024 * 1024;

type Session = { server: McpServer; transport: StreamableHTTPServerTransport };

const sessions = new Map<string, Session>();

function send(res: ServerResponse, status: number, body: unknown): void {
	const payload = JSON.stringify(body);
	res.writeHead(status, {
		"Content-Type": "application/json; charset=utf-8",
		"Cache-Control": "no-store",
	});
	res.end(payload);
}

function rpcError(res: ServerResponse, status: number, message: string): void {
	send(res, status, { jsonrpc: "2.0", error: { code: -32000, message }, id: null });
}

/**
 * Same DNS-rebinding hardening the plugin applies: the socket only listens on
 * loopback, but a hostile page could still resolve a name to 127.0.0.1. The
 * port is ignored so a client that omits or rewrites it still works.
 */
function isLocalHost(req: IncomingMessage): boolean {
	const host = req.headers.host;
	if (!host) {
		return true; // HTTP/1.0 clients omit it; the bind address already limits reach
	}
	const name = host.startsWith("[") ? host.slice(0, host.indexOf("]") + 1) : host.split(":")[0];
	return name === "127.0.0.1" || name === "localhost" || name === "[::1]" || name === "::1";
}

async function readBody(req: IncomingMessage): Promise<unknown> {
	const chunks: Buffer[] = [];
	let size = 0;
	for await (const chunk of req) {
		const buf = chunk as Buffer;
		size += buf.length;
		if (size > MAX_BODY_BYTES) {
			throw new Error("request body too large");
		}
		chunks.push(buf);
	}
	if (size === 0) {
		return undefined;
	}
	return JSON.parse(Buffer.concat(chunks).toString("utf8"));
}

async function newSession(): Promise<StreamableHTTPServerTransport> {
	const server = new McpServer({ name: "osrs-companion", version: "0.1.0" });
	registerTools(server);
	const transport = new StreamableHTTPServerTransport({
		sessionIdGenerator: () => randomUUID(),
		// Plain JSON replies instead of SSE: simpler for clients that only
		// implement the request/response half of the transport.
		enableJsonResponse: true,
		onsessioninitialized: (id) => {
			sessions.set(id, { server, transport });
			console.error(`[mcp-http] session ${id} initialized`);
		},
		onsessionclosed: (id) => {
			sessions.delete(id);
			console.error(`[mcp-http] session ${id} closed`);
		},
	});
	transport.onclose = () => {
		if (transport.sessionId) {
			sessions.delete(transport.sessionId);
		}
	};
	await server.connect(transport);
	return transport;
}

async function handle(req: IncomingMessage, res: ServerResponse): Promise<void> {
	if (!isLocalHost(req)) {
		send(res, 403, { error: "forbidden: non-local Host header" });
		return;
	}

	const path = new URL(req.url ?? "/", "http://127.0.0.1").pathname;
	const method = req.method ?? "GET";

	if (path === "/health" || path === "/") {
		send(res, 200, {
			service: "osrs-companion-mcp",
			transport: "streamable-http",
			endpoint: MCP_PATH,
			sessions: sessions.size,
		});
		return;
	}

	if (path !== MCP_PATH) {
		send(res, 404, { error: `unknown endpoint: ${path} (MCP is served at ${MCP_PATH})` });
		return;
	}

	// The spec requires clients to accept both media types on POST and the SDK
	// enforces it with a 406; several clients send only application/json, so
	// widen it rather than fail the handshake over a header.
	const accept = req.headers.accept ?? "";
	if (!accept.includes("text/event-stream") || !accept.includes("application/json")) {
		req.headers.accept = "application/json, text/event-stream";
	}

	const sessionId = req.headers["mcp-session-id"];
	const known = typeof sessionId === "string" ? sessions.get(sessionId) : undefined;

	if (method === "POST") {
		let body: unknown;
		try {
			body = await readBody(req);
		} catch (e) {
			rpcError(res, 400, `invalid JSON body: ${e instanceof Error ? e.message : String(e)}`);
			return;
		}

		if (known) {
			await known.transport.handleRequest(req, res, body);
			return;
		}
		if (isInitializeRequest(body)) {
			const transport = await newSession();
			await transport.handleRequest(req, res, body);
			return;
		}
		// Stale session (the app outlived this process): tell the client to
		// re-initialize rather than leaving it hanging.
		rpcError(res, 404, "unknown or expired MCP session — send initialize first");
		return;
	}

	if (method === "GET" || method === "DELETE") {
		if (!known) {
			rpcError(res, 404, "unknown or expired MCP session — send initialize first");
			return;
		}
		await known.transport.handleRequest(req, res);
		return;
	}

	res.writeHead(405, { Allow: "GET, POST, DELETE" }).end();
}

export async function startHttpServer(port: number, host = "127.0.0.1"): Promise<void> {
	const server = createServer((req, res) => {
		handle(req, res).catch((e) => {
			console.error("[mcp-http] request failed", e);
			if (!res.headersSent) {
				rpcError(res, 500, String(e));
			} else {
				res.end();
			}
		});
	});

	await new Promise<void>((resolve, reject) => {
		server.once("error", reject);
		server.listen(port, host, () => {
			server.off("error", reject);
			resolve();
		});
	});
	console.error(`[mcp-http] MCP endpoint listening on http://${host}:${port}${MCP_PATH}`);
}
