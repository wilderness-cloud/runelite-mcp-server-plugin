import { cachedJson } from "./cache.js";
import { fetchJson, fetchText } from "./http.js";

const API = "https://oldschool.runescape.wiki/api.php";
const DAY = 24 * 3600_000;

export function wikiUrl(title: string): string {
	return `https://oldschool.runescape.wiki/w/${encodeURIComponent(title.replaceAll(" ", "_"))}`;
}

/** Raw wikitext of a page (works for Module: and main namespace). Cached 24h. */
export async function rawWikitext(title: string): Promise<string> {
	const key = `wiki-${title.toLowerCase().replace(/[^a-z0-9]+/g, "_")}`;
	return cachedJson(key, DAY, async () =>
		fetchText(`${wikiUrl(title)}?action=raw`),
	);
}

/** OpenSearch title suggestions. Cached 12h. */
export async function searchTitles(query: string, limit = 10): Promise<string[]> {
	const key = `opensearch-${query.toLowerCase().replace(/[^a-z0-9]+/g, "_")}-${limit}`;
	return cachedJson(key, 12 * 3600_000, async () => {
		const url = `${API}?action=opensearch&format=json&limit=${limit}&namespace=0&search=${encodeURIComponent(query)}`;
		const result = await fetchJson<[string, string[], string[], string[]]>(url);
		return result[1] ?? [];
	});
}

export function truncate(text: string, max: number): string {
	if (text.length <= max) {
		return text;
	}
	return `${text.slice(0, max)}\n...[truncated ${text.length - max} chars]`;
}
