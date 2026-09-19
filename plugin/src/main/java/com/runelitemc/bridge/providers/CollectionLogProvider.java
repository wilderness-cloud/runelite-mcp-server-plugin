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
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		o.addProperty("uniqueObtained", client.getVarpValue(UNIQUE_OBTAINED_VARP));
		o.addProperty("uniqueTotal", client.getVarpValue(UNIQUE_TOTAL_VARP));
		o.add("capture", capture.summaryJson());
		o.add("catalog", catalog());
		return o;
	}

	private JsonObject catalog()
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

				JsonObject pageJson = new JsonObject();
				JsonArray itemsJson = new JsonArray();
				int obtained = 0;

				EnumComposition itemsEnum = client.getEnum(pageStruct.getIntValue(PAGE_ITEMS_ENUM_PARAM));
				if (itemsEnum != null && itemsEnum.getIntVals() != null)
				{
					for (int itemId : itemsEnum.getIntVals())
					{
						JsonObject itemJson = new JsonObject();
						itemJson.addProperty("id", itemId);
						String name = itemName(itemId);
						if (name != null && !name.isEmpty())
						{
							itemJson.addProperty("name", name);
						}
						CollectionLogCapture.CapturedItem seen = capture.lookup(pageName, itemId);
						if (seen != null)
						{
							itemJson.addProperty("obtained", seen.obtained);
							if (seen.obtained)
							{
								itemJson.addProperty("qty", seen.qty);
								obtained++;
							}
						}
						itemsJson.add(itemJson);
					}
				}

				pageJson.addProperty("itemCount", itemsJson.size());
				if (capture.isViewed(pageName))
				{
					pageJson.addProperty("viewedThisSession", true);
					pageJson.addProperty("obtainedInViewedPage", obtained);
				}
				pageJson.add("items", itemsJson);
				pagesJson.add(pageName, pageJson);
			}
			tabsJson.add(tabName, pagesJson);
		}

		return tabsJson;
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
