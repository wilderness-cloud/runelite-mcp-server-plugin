import { VERSION } from "./version.js";

/**
 * prices.runescape.wiki blocks generic Java/curl agents and asks callers to
 * identify themselves, so this carries the project URL: whoever runs the wiki
 * needs a way to reach the author of a misbehaving client, and with this code in
 * strangers' hands that cannot be a personal contact.
 */
export const USER_AGENT =
	`runelite-mcp-companion-server/${VERSION} (+https://github.com/wilderness-cloud/runelite-mcp-server-plugin)`;

export async function fetchJson<T>(url: string, timeoutMs = 15000): Promise<T> {
	const res = await fetch(url, {
		headers: { "User-Agent": USER_AGENT, Accept: "application/json" },
		signal: AbortSignal.timeout(timeoutMs),
	});
	if (!res.ok) {
		throw new Error(`HTTP ${res.status} for ${url}`);
	}
	return (await res.json()) as T;
}

export async function fetchText(url: string, timeoutMs = 15000): Promise<string> {
	const res = await fetch(url, {
		headers: { "User-Agent": USER_AGENT, Accept: "text/plain" },
		signal: AbortSignal.timeout(timeoutMs),
	});
	if (!res.ok) {
		throw new Error(`HTTP ${res.status} for ${url}`);
	}
	return await res.text();
}
