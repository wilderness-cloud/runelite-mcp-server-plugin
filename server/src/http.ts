export const USER_AGENT =
	"runelite-mcp-companion/0.1.0 (personal local OSRS assistant; prices/wiki/WOM friendly client)";

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
