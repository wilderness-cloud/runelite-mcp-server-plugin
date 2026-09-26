package com.runelitemc.bridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntFunction;
import net.runelite.api.GrandExchangeOffer;
import net.runelite.api.GrandExchangeOfferState;

/**
 * Live Grand Exchange offers, plus as much of the four-hour buy limit as a
 * client can honestly know.
 *
 * Jagex expose the per-item buy limit nowhere, and the amount an account has
 * already bought in the current window nowhere at all — not in the API, not in
 * a varbit. What the client does see is its own offers changing, so this
 * watches them: each time a buy offer's filled quantity rises, the difference
 * is banked as a purchase at that moment, and purchases inside the last four
 * hours are totalled per item.
 *
 * That makes the total a FLOOR, not the truth, and the tool description says
 * so. Three things it cannot see:
 *
 * - purchases made before this plugin started, or while it was off;
 * - an offer that was already part-filled the first time it was observed (the
 *   fill happened at an unknown time, so it is taken as a baseline and not
 *   counted rather than dated wrongly);
 * - purchases on another client or another device.
 *
 * The window is per item and starts at that item's first purchase, which is how
 * the game's limit works — not a fixed wall-clock bucket.
 */
public class GrandExchangeTracker
{
	static final long WINDOW_MS = 4 * 60 * 60 * 1000L;
	private static final int SLOTS = 8;

	private static final class Purchase
	{
		private final int itemId;
		private final int quantity;
		private final long atMs;

		private Purchase(int itemId, int quantity, long atMs)
		{
			this.itemId = itemId;
			this.quantity = quantity;
			this.atMs = atMs;
		}
	}

	/** Item id of the buy offer occupying each slot, 0 when the slot holds no buy. */
	private final int[] slotItem = new int[SLOTS];
	/** Filled quantity already attributed for that slot. */
	private final int[] slotCounted = new int[SLOTS];
	private final Deque<Purchase> ledger = new ArrayDeque<>();
	private volatile long trackingSinceMs = System.currentTimeMillis();

	/**
	 * Folds one offer update into the ledger. Called on the client thread from
	 * the GrandExchangeOfferChanged subscriber.
	 */
	public synchronized void observe(int slot, GrandExchangeOffer offer)
	{
		if (slot < 0 || slot >= SLOTS)
		{
			return;
		}
		if (offer == null || !isBuy(offer.getState()))
		{
			slotItem[slot] = 0;
			slotCounted[slot] = 0;
			return;
		}

		int itemId = offer.getItemId();
		int filled = offer.getQuantitySold();

		if (slotItem[slot] != itemId)
		{
			// First sight of this offer. Whatever is already filled happened at a
			// time we cannot know, so it becomes the baseline rather than a dated
			// purchase.
			slotItem[slot] = itemId;
			slotCounted[slot] = filled;
			return;
		}

		int delta = filled - slotCounted[slot];
		if (delta > 0)
		{
			ledger.addLast(new Purchase(itemId, delta, System.currentTimeMillis()));
		}
		slotCounted[slot] = filled;
	}

	private static boolean isBuy(GrandExchangeOfferState state)
	{
		return state == GrandExchangeOfferState.BUYING
			|| state == GrandExchangeOfferState.BOUGHT
			|| state == GrandExchangeOfferState.CANCELLED_BUY;
	}

	/** Forgets everything: a different account's offers must never be served. */
	public synchronized void clear()
	{
		ledger.clear();
		for (int i = 0; i < SLOTS; i++)
		{
			slotItem[i] = 0;
			slotCounted[i] = 0;
		}
		trackingSinceMs = System.currentTimeMillis();
	}

	public JsonObject grandExchange(GrandExchangeOffer[] offers, IntFunction<String> itemName)
	{
		JsonObject out = new JsonObject();
		long now = System.currentTimeMillis();
		out.addProperty("capturedAt", Instant.ofEpochMilli(now).toString());

		JsonArray slots = new JsonArray();
		int active = 0;
		if (offers != null)
		{
			for (int slot = 0; slot < offers.length; slot++)
			{
				GrandExchangeOffer offer = offers[slot];
				if (offer == null || offer.getState() == null
					|| offer.getState() == GrandExchangeOfferState.EMPTY)
				{
					continue;
				}
				active++;
				JsonObject o = new JsonObject();
				o.addProperty("geSlot", slot);
				o.addProperty("state", offer.getState().name());
				o.addProperty("buying", isBuy(offer.getState()));
				o.addProperty("itemId", offer.getItemId());
				String name = itemName.apply(offer.getItemId());
				if (name != null && !name.isEmpty())
				{
					o.addProperty("item", name);
				}
				o.addProperty("pricePerItem", offer.getPrice());
				o.addProperty("totalQuantity", offer.getTotalQuantity());
				o.addProperty("quantityFilled", offer.getQuantitySold());
				o.addProperty("coinsExchanged", offer.getSpent());
				slots.add(o);
			}
		}
		out.add("offers", slots);
		out.addProperty("activeOffers", active);
		out.add("buyLimitUsage", buyLimitUsage(now, itemName));
		return out;
	}

	private synchronized JsonObject buyLimitUsage(long now, IntFunction<String> itemName)
	{
		while (!ledger.isEmpty() && now - ledger.peekFirst().atMs >= WINDOW_MS)
		{
			ledger.removeFirst();
		}

		Map<Integer, int[]> totals = new LinkedHashMap<>();
		Map<Integer, Long> firstAt = new LinkedHashMap<>();
		for (Purchase p : ledger)
		{
			totals.computeIfAbsent(p.itemId, k -> new int[1])[0] += p.quantity;
			firstAt.putIfAbsent(p.itemId, p.atMs);
		}

		JsonObject out = new JsonObject();
		out.addProperty("windowSeconds", WINDOW_MS / 1000L);
		out.addProperty("trackingSince", Instant.ofEpochMilli(trackingSinceMs).toString());
		out.addProperty("isFloor", true);
		out.addProperty("note", "Counted from offers this plugin watched fill, so it is a lower bound: buys made "
			+ "before it started, while it was off, on another device, or already filled when an offer was first "
			+ "seen are not in it. Pair with the per-item limit from item_info to estimate what is left.");

		JsonArray items = new JsonArray();
		for (Map.Entry<Integer, int[]> e : totals.entrySet())
		{
			JsonObject item = new JsonObject();
			item.addProperty("itemId", e.getKey());
			String name = itemName.apply(e.getKey());
			if (name != null && !name.isEmpty())
			{
				item.addProperty("item", name);
			}
			item.addProperty("boughtInWindow", e.getValue()[0]);
			long start = firstAt.get(e.getKey());
			item.addProperty("windowStartedAt", Instant.ofEpochMilli(start).toString());
			item.addProperty("windowResetsAt", Instant.ofEpochMilli(start + WINDOW_MS).toString());
			item.addProperty("windowResetsInSeconds", Math.max(0, (start + WINDOW_MS - now) / 1000L));
			items.add(item);
		}
		out.add("items", items);
		return out;
	}
}
