/**
 * Manual MCP handshake check: spawns the built server over stdio, runs
 * initialize -> tools/list -> one tools/call, prints the transcript.
 * Usage: npx tsx test/handshake.ts
 */
import { spawn } from "node:child_process";

const proc = spawn(process.execPath, ["dist/index.js"], { stdio: ["pipe", "pipe", "inherit"] });

let buf = "";
const pending = new Map<number, (value: unknown) => void>();

proc.stdout!.on("data", (chunk: Buffer) => {
	buf += chunk.toString();
	let idx: number;
	while ((idx = buf.indexOf("\n")) >= 0) {
		const line = buf.slice(0, idx).trim();
		buf = buf.slice(idx + 1);
		if (!line) continue;
		try {
			const msg = JSON.parse(line);
			if (msg.id !== undefined && pending.has(msg.id)) {
				pending.get(msg.id)!(msg);
				pending.delete(msg.id);
			}
		} catch {
			// non-JSON line on stdout (should not happen; server logs go to stderr)
		}
	}
});

function request(id: number, method: string, params: unknown): Promise<any> {
	return new Promise((resolve) => {
		pending.set(id, resolve);
		proc.stdin!.write(`${JSON.stringify({ jsonrpc: "2.0", id, method, params })}\n`);
	});
}

const init = await request(1, "initialize", {
	protocolVersion: "2025-06-18",
	capabilities: {},
	clientInfo: { name: "handshake-test", version: "0.0.0" },
});
console.log("== initialize ==");
console.log(JSON.stringify(init.result.serverInfo), "tools cap:", JSON.stringify(init.result.capabilities?.tools));

proc.stdin!.write(`${JSON.stringify({ jsonrpc: "2.0", method: "notifications/initialized" })}\n`);

const tools = await request(2, "tools/list", {});
console.log("== tools/list ==");
console.log(tools.result.tools.map((t: { name: string }) => t.name).join(", "));

const call = await request(3, "tools/call", {
	name: "ge_price",
	arguments: { nameOrId: "abyssal whip" },
});
console.log("== tools/call ge_price ==");
console.log(call.result?.content?.[0]?.text?.slice(0, 400) ?? JSON.stringify(call));

const state = await request(4, "tools/call", { name: "game_state", arguments: {} });
console.log("== tools/call game_state (expected offline error) ==");
console.log(state.result?.content?.[0]?.text?.slice(0, 160) ?? JSON.stringify(state));

proc.kill();
process.exit(0);
