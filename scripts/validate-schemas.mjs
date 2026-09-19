/**
 * Checks the plugin against its own contract: calls every tool the running
 * client advertises and validates each result against the outputSchema that
 * same tools/list response declared. Catches a provider and its schema drifting
 * apart, which unit tests cannot see.
 *
 *   node scripts/validate-schemas.mjs [http://127.0.0.1:8765/mcp]
 *
 * Needs RuneLite running with the plugin enabled, and ajv (from server/).
 */
import { createRequire } from "node:module";
import { fileURLToPath } from "node:url";
import { dirname, join } from "node:path";

const here = dirname(fileURLToPath(import.meta.url));
const require = createRequire(join(here, "..", "server", "node_modules", "placeholder.js"));

let Ajv, addFormats;
try {
	Ajv = require("ajv/dist/2020.js");
	addFormats = require("ajv-formats");
} catch {
	console.error("ajv not found. Run `npm install` in server/ (it is the only thing that pulls ajv in).");
	process.exit(2);
}

const url = process.argv[2] ?? "http://127.0.0.1:8765/mcp";

async function rpc(method, params, id) {
	const res = await fetch(url, {
		method: "POST",
		headers: { "Content-Type": "application/json" },
		body: JSON.stringify({ jsonrpc: "2.0", id, method, params }),
	});
	if (!res.ok) {
		throw new Error(`${method}: HTTP ${res.status} ${await res.text()}`);
	}
	const body = await res.json();
	if (body.error) {
		throw new Error(`${method}: ${JSON.stringify(body.error)}`);
	}
	return body.result;
}

const ajv = new Ajv({ allErrors: true, strict: true });
addFormats(ajv);

await rpc("initialize", {
	protocolVersion: "2025-06-18",
	capabilities: {},
	clientInfo: { name: "validate-schemas", version: "0.1.0" },
}, 1);

const { tools } = await rpc("tools/list", {}, 2);
let failures = 0;
let validated = 0;
let id = 3;

for (const tool of tools) {
	if (!tool.outputSchema) {
		console.log(`skip ${tool.name}: declares no outputSchema`);
		continue;
	}

	const result = await rpc("tools/call", { name: tool.name, arguments: {} }, id++);
	if (result.isError) {
		console.log(`skip ${tool.name}: ${result.content?.[0]?.text?.slice(0, 120)}`);
		continue;
	}

	// structuredContent is the typed half; the text block carries the same JSON.
	const payload = result.structuredContent ?? JSON.parse(result.content[0].text);
	const validate = ajv.compile(tool.outputSchema);
	if (validate(payload)) {
		validated++;
		console.log(`ok   ${tool.name}`);
	} else {
		failures++;
		console.log(`FAIL ${tool.name}`);
		for (const error of validate.errors.slice(0, 10)) {
			console.log(`       ${error.instancePath || "/"} ${error.message}`);
		}
	}
}

if (failures) {
	console.log(`\n${failures} tool(s) do not match their schema`);
	process.exit(1);
}
if (validated === 0) {
	// Nothing checked is not a pass: usually the client is running an older jar.
	console.log("\nno tool was validated — is RuneLite running the current plugin build?");
	process.exit(2);
}
console.log(`\nevery tool matches its schema (${validated} checked)`);
