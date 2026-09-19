package com.runelitemc.bridge;

import lombok.AccessLevel;
import lombok.Getter;
import lombok.Setter;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;

/**
 * Thread-safe snapshot of volatile runtime info, updated by event subscribers
 * on the client thread and read by HTTP worker threads.
 */
@Getter
@Setter(AccessLevel.PACKAGE)
public class RuntimeState
{
	private volatile long lastTickMs;
	private volatile String gameState = "UNKNOWN";
	private volatile String username;
	private volatile Item[] bankItems;
	private volatile long bankCapturedAtMs;
	/**
	 * Item counts for bank tabs 1-9, captured with the items they describe.
	 * Tabs are positional — tab 1 starts at container index 0 and each tab
	 * follows the previous one — so counts are only meaningful paired with the
	 * exact item array they were read alongside.
	 */
	private volatile int[] bankTabCounts;
	private volatile int bankCurrentTab;
	private volatile boolean bankLeavePlaceholders;

	/**
	 * Captures the bank. Tab metadata is passed in rather than read here: the
	 * varbits must be sampled on the client thread at the same moment as the
	 * items, or the tab ranges can describe a layout that no longer matches.
	 */
	void updateBank(ItemContainer container, int[] tabCounts, int currentTab, boolean leavePlaceholders)
	{
		if (container == null)
		{
			return;
		}
		Item[] items = container.getItems();
		bankItems = items == null ? new Item[0] : items.clone();
		bankTabCounts = tabCounts == null ? new int[0] : tabCounts.clone();
		bankCurrentTab = currentTab;
		bankLeavePlaceholders = leavePlaceholders;
		bankCapturedAtMs = System.currentTimeMillis();
	}

	void clearBank()
	{
		bankItems = null;
		bankTabCounts = null;
		bankCurrentTab = 0;
		bankLeavePlaceholders = false;
		bankCapturedAtMs = 0;
	}
}
