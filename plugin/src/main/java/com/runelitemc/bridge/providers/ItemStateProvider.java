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
		for (Item item : items)
		{
			addItem(arr, item);
		}
		o.add("items", arr);
		o.addProperty("uniqueItems", arr.size());
		return o;
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
