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

	void updateBank(ItemContainer container)
	{
		if (container == null)
		{
			return;
		}
		Item[] items = container.getItems();
		bankItems = items == null ? new Item[0] : items.clone();
		bankCapturedAtMs = System.currentTimeMillis();
	}

	void clearBank()
	{
		bankItems = null;
		bankCapturedAtMs = 0;
	}
}
