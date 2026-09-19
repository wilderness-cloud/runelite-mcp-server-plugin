import { existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from "node:fs";
import { dirname, join } from "node:path";

const CACHE_DIR = join(import.meta.dirname, "..", ".cache");

/**
 * Tiny disk-backed JSON cache. Stale/failed reads fall through to the fetcher;
 * failed writes are non-fatal.
 */
export async function cachedJson<T>(key: string, ttlMs: number, fetcher: () => Promise<T>): Promise<T> {
	const file = join(CACHE_DIR, `${key}.json`);
	try {
		if (existsSync(file) && Date.now() - statSync(file).mtimeMs < ttlMs) {
			return JSON.parse(readFileSync(file, "utf8")) as T;
		}
	} catch {
		// unreadable cache entry; refetch
	}
	const value = await fetcher();
	try {
		mkdirSync(dirname(file), { recursive: true });
		writeFileSync(file, JSON.stringify(value));
	} catch {
		// non-fatal
	}
	return value;
}
