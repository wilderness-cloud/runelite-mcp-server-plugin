import { cachedJson } from "./cache.js";
import { fetchText } from "./http.js";

/**
 * What is left of the wiki here is deliberately small.
 *
 * This server reads the player's own account; wiki content is the client app's
 * job. The page-reading tools that lived here (wiki_page / wiki_table /
 * wiki_infobox, and the wiki half of boss_info) were removed for that reason.
 *
 * Two things remain because they are not wiki *browsing*: a URL builder, so an
 * answer can point at the page it came from, and a raw fetch used by
 * questdata.ts to read Module:Questreq/data — a machine-readable table, not an
 * article.
 */
const DAY = 24 * 3600_000;

export function wikiUrl(title: string): string {
	// "/" is a path separator on the wiki too — Vorkath/Strategies is a real page,
	// and percent-encoding the slash turns the link into a 404.
	const path = title
		.replaceAll(" ", "_")
		.split("/")
		.map(encodeURIComponent)
		.join("/");
	return `https://oldschool.runescape.wiki/w/${path}`;
}

/** Raw wikitext of a page (works for Module: and main namespace). Cached 24h. */
export async function rawWikitext(title: string): Promise<string> {
	const key = `wiki-${title.toLowerCase().replace(/[^a-z0-9]+/g, "_")}`;
	return cachedJson(key, DAY, async () => fetchText(`${wikiUrl(title)}?action=raw`));
}
