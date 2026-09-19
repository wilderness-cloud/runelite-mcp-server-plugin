package com.runelitemc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Session-scoped capture of collection log pages the player has actually
 * viewed. The game only materializes per-item obtained state for the page on
 * screen, so coverage grows as the player browses their log.
 */
public class CollectionLogCapture
{
	public static class CapturedItem
	{
		public final int id;
		public final int qty;
		public final boolean obtained;

		public CapturedItem(int id, int qty, boolean obtained)
		{
			this.id = id;
			this.qty = qty;
			this.obtained = obtained;
		}
	}

	private final Map<String, Map<Integer, CapturedItem>> pages = new ConcurrentHashMap<>();

	public void record(String page, List<CapturedItem> items)
	{
		Map<Integer, CapturedItem> map = new ConcurrentHashMap<>();
		for (CapturedItem item : items)
		{
			map.put(item.id, item);
		}
		pages.put(page, map);
	}

	public CapturedItem lookup(String page, int itemId)
	{
		Map<Integer, CapturedItem> map = pages.get(page);
		return map == null ? null : map.get(itemId);
	}

	public boolean isViewed(String page)
	{
		Map<Integer, CapturedItem> map = pages.get(page);
		return map != null && !map.isEmpty();
	}

	public int pageCount()
	{
		return pages.size();
	}

	public void clear()
	{
		pages.clear();
	}

	public JsonObject summaryJson()
	{
		JsonObject o = new JsonObject();
		o.addProperty("pagesCapturedThisSession", pages.size());
		if (!pages.isEmpty())
		{
			JsonObject perPage = new JsonObject();
			pages.forEach((page, items) ->
			{
				long obtained = items.values().stream().filter(i -> i.obtained).count();
				JsonObject p = new JsonObject();
				p.addProperty("obtained", obtained);
				p.addProperty("items", items.size());
				perPage.add(page, p);
			});
			o.add("pages", perPage);
		}
		o.addProperty("note", "Per-item obtained state is only sent for pages viewed this session; browse the in-game log to capture more. Full item catalog is always available.");
		return o;
	}

	public JsonArray pageJson(String page)
	{
		Map<Integer, CapturedItem> map = pages.get(page);
		JsonArray arr = new JsonArray();
		if (map == null)
		{
			return arr;
		}
		map.values().stream()
			.sorted((a, b) -> Boolean.compare(a.obtained, b.obtained))
			.forEach(item ->
			{
				JsonObject it = new JsonObject();
				it.addProperty("id", item.id);
				it.addProperty("obtained", item.obtained);
				it.addProperty("qty", item.qty);
				arr.add(it);
			});
		return arr;
	}
}
