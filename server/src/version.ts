import { readFileSync } from "node:fs";
import { join } from "node:path";

/**
 * The package version, read at startup rather than retyped into each of the
 * three places that report it (MCP serverInfo over stdio, over HTTP, and the
 * outbound User-Agent). The release workflow bumps package.json from the merged
 * PR's semver label, so this is the only copy that has to move.
 *
 * Resolves to server/package.json from either src/ (tsx) or dist/ (built).
 */
function readVersion(): string {
	try {
		const pkg = JSON.parse(readFileSync(join(import.meta.dirname, "..", "package.json"), "utf8")) as {
			version?: string;
		};
		return pkg.version ?? "0.0.0-dev";
	} catch {
		return "0.0.0-dev";
	}
}

export const VERSION = readVersion();

export const SERVER_NAME = "runelite-mcp-companion";
