package com.runelitemc.bridge;

import com.google.gson.JsonObject;
import com.runelitemc.bridge.providers.BossKcProvider;
import com.runelitemc.bridge.providers.CombatAchievementsProvider;
import com.runelitemc.bridge.providers.CollectionLogProvider;
import com.runelitemc.bridge.providers.ItemStateProvider;
import com.runelitemc.bridge.providers.PlayerStateProvider;
import com.runelitemc.bridge.providers.ProgressProvider;
import com.runelitemc.bridge.providers.SlayerProvider;
import java.time.Instant;

/**
 * Builds the unified account snapshot. Must be called on the client thread.
 * Heavy catalogs (combat achievement tasks, collection log items) are
 * summarized here; fetch their dedicated endpoints for full detail.
 */
public class SnapshotService
{
	private final PlayerStateProvider playerState;
	private final ItemStateProvider itemState;
	private final ProgressProvider progress;
	private final CombatAchievementsProvider combatAchievements;
	private final SlayerProvider slayer;
	private final BossKcProvider bossKc;
	private final CollectionLogProvider collectionLog;

	public SnapshotService(PlayerStateProvider playerState, ItemStateProvider itemState,
		ProgressProvider progress, CombatAchievementsProvider combatAchievements,
		SlayerProvider slayer, BossKcProvider bossKc, CollectionLogProvider collectionLog)
	{
		this.playerState = playerState;
		this.itemState = itemState;
		this.progress = progress;
		this.combatAchievements = combatAchievements;
		this.slayer = slayer;
		this.bossKc = bossKc;
		this.collectionLog = collectionLog;
	}

	public JsonObject snapshot()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		o.add("state", playerState.state());
		o.add("quests", progress.quests());
		o.add("diaries", progress.diaries());
		o.add("combatAchievements", combatAchievements.summary());
		o.add("slayer", slayer.slayer());
		o.add("bossKc", bossKc.bossKc());
		o.add("inventory", itemState.inventory());
		o.add("equipment", itemState.equipment());
		o.add("bank", itemState.bank());
		o.add("collectionLog", collectionLog.summary());
		return o;
	}
}
