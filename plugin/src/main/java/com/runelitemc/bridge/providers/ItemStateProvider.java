package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.runelitemc.bridge.RuntimeState;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.Item;
import net.runelite.api.ItemComposition;
import net.runelite.api.ItemContainer;
import net.runelite.api.gameval.InventoryID;

public class ItemStateProvider
{
	/** Worn-container index to slot name (HEAD, CAPE, ...), for gear loadouts. */
	private static final String[] EQUIPMENT_SLOTS = equipmentSlotNames();

	private final Client client;
	private final RuntimeState runtimeState;

	public ItemStateProvider(Client client, RuntimeState runtimeState)
	{
		this.client = client;
		this.runtimeState = runtimeState;
	}

	public JsonObject inventory()
	{
		return liveContainer(InventoryID.INV, "inventory", false);
	}

	public JsonObject equipment()
	{
		return liveContainer(InventoryID.WORN, "equipment", true);
	}

	private static String[] equipmentSlotNames()
	{
		int max = 0;
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			max = Math.max(max, slot.getSlotIdx());
		}

		String[] names = new String[max + 1];
		for (EquipmentInventorySlot slot : EquipmentInventorySlot.values())
		{
			names[slot.getSlotIdx()] = slot.name();
		}
		return names;
	}

	public JsonObject bank()
	{
		JsonObject o = new JsonObject();
		o.addProperty("container", "bank");

		Item[] items = runtimeState.getBankItems();
		long capturedAtMs = runtimeState.getBankCapturedAtMs();
		if (items == null || capturedAtMs <= 0)
		{
			o.addProperty("available", false);
			o.addProperty("note", "Bank contents are only sent by the server while the bank is open; open your bank once this session to capture a snapshot");
			return o;
		}

		o.addProperty("available", true);
		o.addProperty("capturedAtMs", capturedAtMs);
		o.addProperty("capturedAt", Instant.ofEpochMilli(capturedAtMs).toString());
		o.addProperty("ageSeconds", (System.currentTimeMillis() - capturedAtMs) / 1000);

		JsonArray arr = new JsonArray();
		int placeholders = 0;
		int lastOccupied = -1;
		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item == null || item.getId() <= 0)
			{
				continue;
			}
			// The slot index is the whole basis for tab membership, so it is
			// recorded per item rather than inferred from array position —
			// array position stops matching the moment anything is skipped.
			JsonObject it = bankItem(item, i);
			if (item.getQuantity() <= 0)
			{
				// A placeholder: the slot is reserved, nothing is stored in it.
				it.addProperty("placeholder", true);
				placeholders++;
			}
			arr.add(it);
			lastOccupied = i;
		}

		o.add("items", arr);
		// Unchanged meaning: every entry emitted, placeholders included.
		o.addProperty("uniqueItems", arr.size());
		o.addProperty("placeholderCount", placeholders);
		o.add("tabs", bankTabs(runtimeState.getBankTabCounts(), lastOccupied + 1));
		o.addProperty("currentTab", runtimeState.getBankCurrentTab());
		o.addProperty("leavePlaceholders", runtimeState.isBankLeavePlaceholders());
		return o;
	}

	/**
	 * Bank tabs as index ranges over bankSlot rather than a field on every item: the bank is
	 * one flat array in display order, tabs 1-9 occupy consecutive runs from
	 * index 0, and whatever follows them is the main tab. Ten small entries
	 * describe the whole layout, where a per-item tab would add ~45% to a
	 * 500-item payload for the same information.
	 */
	private JsonArray bankTabs(int[] counts, int occupiedSlots)
	{
		JsonArray tabs = new JsonArray();
		int start = 0;
		if (counts != null)
		{
			for (int i = 0; i < counts.length; i++)
			{
				if (counts[i] <= 0)
				{
					continue;
				}
				JsonObject tab = new JsonObject();
				tab.addProperty("tab", i + 1);
				tab.addProperty("start", start);
				tab.addProperty("count", counts[i]);
				tabs.add(tab);
				start += counts[i];
			}
		}

		JsonObject main = new JsonObject();
		main.addProperty("tab", 0);
		main.addProperty("start", start);
		main.addProperty("count", Math.max(0, occupiedSlots - start));
		main.addProperty("main", true);
		tabs.add(main);
		return tabs;
	}

	/** Which tab a bank slot falls in; 0 is the main tab. */
	private static int tabForSlot(int[] counts, int slot)
	{
		if (counts == null || slot < 0)
		{
			return 0;
		}
		int start = 0;
		for (int i = 0; i < counts.length; i++)
		{
			if (counts[i] <= 0)
			{
				continue;
			}
			if (slot < start + counts[i])
			{
				return i + 1;
			}
			start += counts[i];
		}
		return 0;
	}

	private JsonObject bankItem(Item item, int slot)
	{
		JsonObject it = new JsonObject();
		it.addProperty("id", item.getId());
		it.addProperty("qty", item.getQuantity());
		// Deliberately not "slot": that name is the worn-equipment enum in the
		// shared item schema, and an integer in it fails validation for every
		// consumer of game_state, whose bank section is this same object.
		it.addProperty("bankSlot", slot);
		String name = itemName(item.getId());
		if (name != null && !name.isEmpty())
		{
			it.addProperty("name", name);
		}
		return it;
	}

	/**
	 * Answers "do I have X, and where is it?" without the caller pulling every
	 * container and matching client-side — the bank alone runs to hundreds of
	 * items. A numeric query matches an item id exactly; anything else is a
	 * case-insensitive substring match on the item name.
	 */
	public JsonObject findItem(String query, java.util.Set<String> containers)
	{
		JsonObject o = new JsonObject();
		o.addProperty("query", query == null ? "" : query);
		o.addProperty("capturedAt", Instant.now().toString());

		JsonArray searched = new JsonArray();
		for (String c : containers)
		{
			searched.add(c);
		}
		o.add("containersSearched", searched);

		JsonArray matches = new JsonArray();
		if (query == null || query.trim().isEmpty())
		{
			o.add("matches", matches);
			o.addProperty("totalMatches", 0);
			o.addProperty("note", "Empty query: pass an item name substring or a numeric item id.");
			return o;
		}

		String needle = query.trim().toLowerCase(java.util.Locale.ROOT);
		Integer wantedId = null;
		try
		{
			wantedId = Integer.valueOf(needle);
		}
		catch (NumberFormatException ignored)
		{
			// Not an id; fall through to a name match.
		}

		if (containers.contains("bank"))
		{
			Item[] bank = runtimeState.getBankItems();
			o.addProperty("bankAvailable", bank != null && runtimeState.getBankCapturedAtMs() > 0);
			if (bank != null)
			{
				int[] counts = runtimeState.getBankTabCounts();
				for (int i = 0; i < bank.length; i++)
				{
					Item item = bank[i];
					if (item == null || item.getId() <= 0 || !matches(item.getId(), needle, wantedId))
					{
						continue;
					}
					JsonObject m = bankItem(item, i);
					m.addProperty("container", "bank");
					m.addProperty("tab", tabForSlot(counts, i));
					if (item.getQuantity() <= 0)
					{
						m.addProperty("placeholder", true);
					}
					matches.add(m);
				}
			}
		}

		if (containers.contains("inventory"))
		{
			collectMatches(matches, InventoryID.INV, "inventory", false, needle, wantedId);
		}
		if (containers.contains("equipment"))
		{
			collectMatches(matches, InventoryID.WORN, "equipment", true, needle, wantedId);
		}

		o.add("matches", matches);
		o.addProperty("totalMatches", matches.size());
		return o;
	}

	private void collectMatches(JsonArray out, int containerId, String containerName,
		boolean withSlots, String needle, Integer wantedId)
	{
		ItemContainer container = client.getItemContainer(containerId);
		if (container == null)
		{
			return;
		}
		Item[] items = container.getItems();
		if (items == null)
		{
			return;
		}
		for (int i = 0; i < items.length; i++)
		{
			Item item = items[i];
			if (item == null || item.getId() <= 0 || !matches(item.getId(), needle, wantedId))
			{
				continue;
			}
			JsonObject m = new JsonObject();
			m.addProperty("container", containerName);
			m.addProperty("id", item.getId());
			m.addProperty("qty", item.getQuantity());
			String name = itemName(item.getId());
			if (name != null && !name.isEmpty())
			{
				m.addProperty("name", name);
			}
			if (withSlots)
			{
				String slot = slotName(i);
				if (slot != null)
				{
					m.addProperty("equipmentSlot", slot);
				}
			}
			else
			{
				m.addProperty("inventorySlot", i);
			}
			out.add(m);
		}
	}

	private boolean matches(int id, String needle, Integer wantedId)
	{
		if (wantedId != null)
		{
			return id == wantedId.intValue();
		}
		String name = itemName(id);
		return name != null && name.toLowerCase(java.util.Locale.ROOT).contains(needle);
	}

	private JsonObject liveContainer(int containerId, String containerName, boolean withSlots)
	{
		JsonObject o = new JsonObject();
		o.addProperty("container", containerName);
		o.addProperty("capturedAt", Instant.now().toString());

		ItemContainer container = client.getItemContainer(containerId);
		o.addProperty("available", container != null);
		if (container == null)
		{
			return o;
		}

		Item[] items = container.getItems();
		if (items == null)
		{
			o.add("items", new JsonArray());
			return o;
		}

		JsonArray arr = new JsonArray();
		for (int i = 0; i < items.length; i++)
		{
			// In the worn container the index *is* the equipment slot.
			addItem(arr, items[i], withSlots ? slotName(i) : null);
		}
		o.add("items", arr);
		return o;
	}

	private static String slotName(int index)
	{
		return index >= 0 && index < EQUIPMENT_SLOTS.length ? EQUIPMENT_SLOTS[index] : null;
	}

	private void addItem(JsonArray arr, Item item)
	{
		addItem(arr, item, null);
	}

	private void addItem(JsonArray arr, Item item, String slot)
	{
		if (item == null || item.getId() <= 0)
		{
			return;
		}
		JsonObject it = new JsonObject();
		it.addProperty("id", item.getId());
		it.addProperty("qty", item.getQuantity());
		if (slot != null)
		{
			it.addProperty("slot", slot);
		}
		String name = itemName(item.getId());
		if (name != null && !name.isEmpty())
		{
			it.addProperty("name", name);
		}
		arr.add(it);
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
