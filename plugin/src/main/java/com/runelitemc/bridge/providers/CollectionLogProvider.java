package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelitemc.bridge.CollectionLogCapture;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.ItemComposition;
import net.runelite.api.StructComposition;

/**
 * Collection log data. Aggregate counts come from varps (always available);
 * the full tab/page/item catalog is walked from cache structs/enums; per-item
 * obtained state is merged from the session capture of viewed pages.
 * Structure mirrors the cs2 scripts proc_collection_draw_list/draw_log.
 */
public class CollectionLogProvider
{
	private static final int[] TAB_STRUCT_IDS = {471, 472, 473, 474, 475}; // Bosses, Raids, Clues, Minigames, Other
	private static final int TAB_NAME_PARAM = 682;
	private static final int TAB_ENUM_PARAM = 683;
	private static final int PAGE_NAME_PARAM = 689;
	private static final int PAGE_ITEMS_ENUM_PARAM = 690;
	private static final int UNIQUE_OBTAINED_VARP = 2943;
	private static final int UNIQUE_TOTAL_VARP = 2944;

	private final Client client;
	private final CollectionLogCapture capture;

	public CollectionLogProvider(Client client, CollectionLogCapture capture)
	{
		this.client = client;
		this.capture = capture;
	}

	public JsonObject summary()
	{
		JsonObject o = new JsonObject();
		o.addProperty("uniqueObtained", client.getVarpValue(UNIQUE_OBTAINED_VARP));
		o.addProperty("uniqueTotal", client.getVarpValue(UNIQUE_TOTAL_VARP));
		o.add("capture", capture.summaryJson());
		return o;
	}

	public JsonObject collectionLog()
	{
		return collectionLog(null, null, null);
	}

	/**
	 * Filtered catalog. The full catalog is ~1,900 items across every page and
	 * runs to a quarter of a megabyte, which is the wrong way to answer "is
	 * this one item logged?" — narrow with page or search instead.
	 *
	 * @param page     exact or partial page name (e.g. "Vorkath"), or null
	 * @param search   case-insensitive substring of an item name, or null
	 * @param obtained TRUE for obtained only, FALSE for missing only, null for
	 *                 both. Only meaningful for pages viewed in game this
	 *                 session; elsewhere per-item state is unknown and the item
	 *                 is treated as not matching either filter.
	 */
	public JsonObject collectionLog(String page, String search, Boolean obtained)
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		o.addProperty("uniqueObtained", client.getVarpValue(UNIQUE_OBTAINED_VARP));
		o.addProperty("uniqueTotal", client.getVarpValue(UNIQUE_TOTAL_VARP));
		o.add("capture", capture.summaryJson());
		o.add("catalog", catalog(page, search, obtained));
		if (page != null || search != null || obtained != null)
		{
			JsonObject applied = new JsonObject();
			if (page != null)
			{
				applied.addProperty("page", page);
			}
			if (search != null)
			{
				applied.addProperty("search", search);
			}
			if (obtained != null)
			{
				applied.addProperty("obtained", obtained.booleanValue());
			}
			o.add("filter", applied);
		}
		return o;
	}

	private JsonObject catalog(String pageFilter, String search, Boolean obtainedFilter)
	{
		JsonObject tabsJson = new JsonObject();

		for (int tabStructId : TAB_STRUCT_IDS)
		{
			StructComposition tabStruct = client.getStructComposition(tabStructId);
			if (tabStruct == null)
			{
				continue;
			}
			String tabName = tabStruct.getStringValue(TAB_NAME_PARAM);
			if (tabName == null || tabName.isEmpty())
			{
				continue;
			}

			EnumComposition tabEnum = client.getEnum(tabStruct.getIntValue(TAB_ENUM_PARAM));
			if (tabEnum == null || tabEnum.getIntVals() == null)
			{
				continue;
			}

			JsonObject pagesJson = new JsonObject();
			for (int pageStructId : tabEnum.getIntVals())
			{
				StructComposition pageStruct = client.getStructComposition(pageStructId);
				if (pageStruct == null)
				{
					continue;
				}
				String pageName = pageStruct.getStringValue(PAGE_NAME_PARAM);
				if (pageName == null || pageName.isEmpty())
				{
					continue;
				}
				if (pageFilter != null && !matchesText(pageName, pageFilter))
				{
					continue;
				}

				JsonObject pageJson = new JsonObject();
				JsonArray itemsJson = new JsonArray();
				int obtained = 0;
				int pageItemCount = 0;

				EnumComposition itemsEnum = client.getEnum(pageStruct.getIntValue(PAGE_ITEMS_ENUM_PARAM));
				if (itemsEnum != null && itemsEnum.getIntVals() != null)
				{
					for (int itemId : itemsEnum.getIntVals())
					{
						pageItemCount++;
						String name = itemName(itemId);
						CollectionLogCapture.CapturedItem seen = capture.lookup(pageName, itemId);
						if (seen != null && seen.obtained)
						{
							obtained++;
						}

						if (search != null && (name == null || !matchesText(name, search)))
						{
							continue;
						}
						if (obtainedFilter != null
							&& (seen == null || seen.obtained != obtainedFilter.booleanValue()))
						{
							continue;
						}

						JsonObject itemJson = new JsonObject();
						itemJson.addProperty("id", itemId);
						if (name != null && !name.isEmpty())
						{
							itemJson.addProperty("name", name);
						}
						if (seen != null)
						{
							itemJson.addProperty("obtained", seen.obtained);
							if (seen.obtained)
							{
								itemJson.addProperty("qty", seen.qty);
							}
						}
						itemsJson.add(itemJson);
					}
				}

				// itemCount is the page's true size; itemsReturned reflects a filter.
				pageJson.addProperty("itemCount", pageItemCount);
				if (itemsJson.size() != pageItemCount)
				{
					pageJson.addProperty("itemsReturned", itemsJson.size());
				}
				if (capture.isViewed(pageName))
				{
					pageJson.addProperty("viewedThisSession", true);
					pageJson.addProperty("obtainedInViewedPage", obtained);
				}
				pageJson.add("items", itemsJson);
				if (itemsJson.size() == 0 && (search != null || obtainedFilter != null))
				{
					continue;
				}
				pagesJson.add(pageName, pageJson);
			}
			if (pagesJson.size() == 0 && (pageFilter != null || search != null || obtainedFilter != null))
			{
				continue;
			}
			tabsJson.add(tabName, pagesJson);
		}

		return tabsJson;
	}

	private static boolean matchesText(String value, String needle)
	{
		return value.toLowerCase(java.util.Locale.ROOT)
			.contains(needle.toLowerCase(java.util.Locale.ROOT));
	}

	private String itemName(int id)
	{
		try
		{
			ItemComposition comp = client.getItemDefinition(id);
			return comp == null ? null : comp.getName();
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
