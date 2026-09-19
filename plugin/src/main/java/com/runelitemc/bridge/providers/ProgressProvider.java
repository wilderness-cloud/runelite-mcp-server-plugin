package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.gameval.VarbitID;

public class ProgressProvider
{
	private final Client client;

	public ProgressProvider(Client client)
	{
		this.client = client;
	}

	/**
	 * Per-quest completion state for every quest in the Quest enum. Each lookup
	 * runs a small client script, so this must only be called on the client thread.
	 */
	public JsonObject quests()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());

		JsonObject questMap = new JsonObject();
		int finished = 0;
		int inProgress = 0;
		int notStarted = 0;

		for (Quest quest : Quest.values())
		{
			String name = quest.getName();
			if (name == null || name.isEmpty())
			{
				continue;
			}
			QuestState state;
			try
			{
				state = quest.getState(client);
			}
			catch (Exception e)
			{
				continue;
			}
			if (state == null)
			{
				continue;
			}
			questMap.addProperty(name, state.name());
			switch (state)
			{
				case FINISHED:
					finished++;
					break;
				case IN_PROGRESS:
					inProgress++;
					break;
				default:
					notStarted++;
					break;
			}
		}

		o.add("quests", questMap);
		JsonObject summary = new JsonObject();
		summary.addProperty("finished", finished);
		summary.addProperty("inProgress", inProgress);
		summary.addProperty("notStarted", notStarted);
		o.add("summary", summary);
		return o;
	}

	/**
	 * Achievement diaries: completion, progress and reward state per tier.
	 *
	 * Three varbit families per tier, using the game's own names:
	 *   *_DIARY_<TIER>_COMPLETE  the tier is finished
	 *   *_<TIER>_COUNT           how many of its tasks are done so far
	 *   *_<TIER>_REWARD          the reward has been claimed from the taskmaster
	 *
	 * The task total per tier is not stored client-side, so `tasksComplete` is
	 * a bare count; pair it with wiki data to render "7 of 10".
	 */
	public JsonObject diaries()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		JsonObject regions = new JsonObject();

		addRegion(regions, "Ardougne", VarbitID.STARTED_ARDOUGNE_DIARY,
			tier(VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_EASY_COUNT, VarbitID.ARDOUGNE_EASY_REWARD),
			tier(VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE, VarbitID.ARDOUGNE_MED_COUNT, VarbitID.ARDOUGNE_MEDIUM_REWARD),
			tier(VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_HARD_COUNT, VarbitID.ARDOUGNE_HARD_REWARD),
			tier(VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE, VarbitID.ARDOUGNE_ELITE_COUNT, VarbitID.ARDOUGNE_ELITE_REWARD));
		addRegion(regions, "Desert", VarbitID.STARTED_DESERT_DIARY,
			tier(VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_EASY_COUNT, VarbitID.DESERT_EASY_REWARD),
			tier(VarbitID.DESERT_DIARY_MEDIUM_COMPLETE, VarbitID.DESERT_MED_COUNT, VarbitID.DESERT_MEDIUM_REWARD),
			tier(VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_HARD_COUNT, VarbitID.DESERT_HARD_REWARD),
			tier(VarbitID.DESERT_DIARY_ELITE_COMPLETE, VarbitID.DESERT_ELITE_COUNT, VarbitID.DESERT_ELITE_REWARD));
		addRegion(regions, "Falador", VarbitID.STARTED_FALADOR_DIARY,
			tier(VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_EASY_COUNT, VarbitID.FALADOR_EASY_REWARD),
			tier(VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE, VarbitID.FALADOR_MED_COUNT, VarbitID.FALADOR_MEDIUM_REWARD),
			tier(VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_HARD_COUNT, VarbitID.FALADOR_HARD_REWARD),
			tier(VarbitID.FALADOR_DIARY_ELITE_COMPLETE, VarbitID.FALADOR_ELITE_COUNT, VarbitID.FALADOR_ELITE_REWARD));
		addRegion(regions, "Fremennik", VarbitID.STARTED_FREMENNIK_DIARY,
			tier(VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_EASY_COUNT, VarbitID.FREMENNIK_EASY_REWARD),
			tier(VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE, VarbitID.FREMENNIK_MED_COUNT, VarbitID.FREMENNIK_MEDIUM_REWARD),
			tier(VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_HARD_COUNT, VarbitID.FREMENNIK_HARD_REWARD),
			tier(VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE, VarbitID.FREMENNIK_ELITE_COUNT, VarbitID.FREMENNIK_ELITE_REWARD));
		addRegion(regions, "Kandarin", VarbitID.STARTED_KANDARIN_DIARY,
			tier(VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_EASY_COUNT, VarbitID.KANDARIN_EASY_REWARD),
			tier(VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE, VarbitID.KANDARIN_MED_COUNT, VarbitID.KANDARIN_MEDIUM_REWARD),
			tier(VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_HARD_COUNT, VarbitID.KANDARIN_HARD_REWARD),
			tier(VarbitID.KANDARIN_DIARY_ELITE_COMPLETE, VarbitID.KANDARIN_ELITE_COUNT, VarbitID.KANDARIN_ELITE_REWARD));
		// Karamja predates the standard diary system: its easy/medium/hard tiers
		// live on the old Tai Bwo Wannai ("ATJUN") varbits, and only elite uses
		// the modern naming.
		addRegion(regions, "Karamja", VarbitID.ATJUN_STARTED,
			legacyTier(VarbitID.ATJUN_EASY_DONE, VarbitID.KARAMJA_EASY_COUNT, VarbitID.ATJUN_EASY_REWARD),
			legacyTier(VarbitID.ATJUN_MED_DONE, VarbitID.KARAMJA_MED_COUNT, VarbitID.ATJUN_MED_REWARD),
			legacyTier(VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_HARD_COUNT, VarbitID.ATJUN_HARD_REWARD),
			tier(VarbitID.KARAMJA_DIARY_ELITE_COMPLETE, VarbitID.KARAMJA_ELITE_COUNT, VarbitID.KARAMJA_ELITE_REWARD));
		addRegion(regions, "Kourend & Kebos", VarbitID.STARTED_KOUREND_DIARY,
			tier(VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_EASY_COUNT, VarbitID.KOUREND_EASY_REWARD),
			tier(VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE, VarbitID.KOUREND_MED_COUNT, VarbitID.KOUREND_MEDIUM_REWARD),
			tier(VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_HARD_COUNT, VarbitID.KOUREND_HARD_REWARD),
			tier(VarbitID.KOUREND_DIARY_ELITE_COMPLETE, VarbitID.KOUREND_ELITE_COUNT, VarbitID.KOUREND_ELITE_REWARD));
		addRegion(regions, "Lumbridge & Draynor", VarbitID.STARTED_LUMBRIDGE_DIARY,
			tier(VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_EASY_COUNT, VarbitID.LUMBRIDGE_EASY_REWARD),
			tier(VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE, VarbitID.LUMBRIDGE_MED_COUNT, VarbitID.LUMBRIDGE_MEDIUM_REWARD),
			tier(VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_HARD_COUNT, VarbitID.LUMBRIDGE_HARD_REWARD),
			tier(VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE, VarbitID.LUMBRIDGE_ELITE_COUNT, VarbitID.LUMBRIDGE_ELITE_REWARD));
		addRegion(regions, "Morytania", VarbitID.STARTED_MORYTANIA_DIARY,
			tier(VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_EASY_COUNT, VarbitID.MORYTANIA_EASY_REWARD),
			tier(VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE, VarbitID.MORYTANIA_MED_COUNT, VarbitID.MORYTANIA_MEDIUM_REWARD),
			tier(VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_HARD_COUNT, VarbitID.MORYTANIA_HARD_REWARD),
			tier(VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE, VarbitID.MORYTANIA_ELITE_COUNT, VarbitID.MORYTANIA_ELITE_REWARD));
		addRegion(regions, "Varrock", VarbitID.STARTED_VARROCK_DIARY,
			tier(VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_EASY_COUNT, VarbitID.VARROCK_EASY_REWARD),
			tier(VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE, VarbitID.VARROCK_MED_COUNT, VarbitID.VARROCK_MEDIUM_REWARD),
			tier(VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_HARD_COUNT, VarbitID.VARROCK_HARD_REWARD),
			tier(VarbitID.VARROCK_DIARY_ELITE_COMPLETE, VarbitID.VARROCK_ELITE_COUNT, VarbitID.VARROCK_ELITE_REWARD));
		addRegion(regions, "Western Provinces", VarbitID.STARTED_WESTERN_DIARY,
			tier(VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_EASY_COUNT, VarbitID.WESTERN_EASY_REWARD),
			tier(VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE, VarbitID.WESTERN_MED_COUNT, VarbitID.WESTERN_MEDIUM_REWARD),
			tier(VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_HARD_COUNT, VarbitID.WESTERN_HARD_REWARD),
			tier(VarbitID.WESTERN_DIARY_ELITE_COMPLETE, VarbitID.WESTERN_ELITE_COUNT, VarbitID.WESTERN_ELITE_REWARD));
		addRegion(regions, "Wilderness", VarbitID.STARTED_WILDERNESS_DIARY,
			tier(VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_EASY_COUNT, VarbitID.WILDERNESS_EASY_REWARD),
			tier(VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE, VarbitID.WILDERNESS_MED_COUNT, VarbitID.WILDERNESS_MEDIUM_REWARD),
			tier(VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_HARD_COUNT, VarbitID.WILDERNESS_HARD_REWARD),
			tier(VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE, VarbitID.WILDERNESS_ELITE_COUNT, VarbitID.WILDERNESS_ELITE_REWARD));

		o.add("regions", regions);
		o.add("summary", diarySummary(regions));
		return o;
	}

	private int[] tier(int completeVarbit, int countVarbit, int rewardVarbit)
	{
		return new int[]{completeVarbit, countVarbit, rewardVarbit, 1};
	}

	/** Karamja easy-hard: the legacy ATJUN varbits count 1 as in progress. */
	private int[] legacyTier(int completeVarbit, int countVarbit, int rewardVarbit)
	{
		return new int[]{completeVarbit, countVarbit, rewardVarbit, 2};
	}

	private void addRegion(JsonObject regions, String region, int startedVarbit,
		int[] easy, int[] medium, int[] hard, int[] elite)
	{
		JsonObject r = new JsonObject();
		r.addProperty("started", client.getVarbitValue(startedVarbit) > 0);
		r.add("easy", tierState(easy));
		r.add("medium", tierState(medium));
		r.add("hard", tierState(hard));
		r.add("elite", tierState(elite));
		regions.add(region, r);
	}

	private JsonObject tierState(int[] varbits)
	{
		int value = client.getVarbitValue(varbits[0]);
		boolean complete = diaryComplete(value, varbits[3]);

		JsonObject t = new JsonObject();
		t.addProperty("raw", value);
		t.addProperty("complete", complete);
		t.addProperty("state", complete ? "COMPLETE" : "INCOMPLETE");
		t.addProperty("tasksComplete", client.getVarbitValue(varbits[1]));
		t.addProperty("rewardClaimed", client.getVarbitValue(varbits[2]) > 0);
		return t;
	}

	private JsonObject diarySummary(JsonObject regions)
	{
		int tiersComplete = 0;
		int tasksComplete = 0;
		for (String region : regions.keySet())
		{
			JsonObject r = regions.getAsJsonObject(region);
			for (String tier : new String[]{"easy", "medium", "hard", "elite"})
			{
				JsonObject t = r.getAsJsonObject(tier);
				if (t.get("complete").getAsBoolean())
				{
					tiersComplete++;
				}
				tasksComplete += t.get("tasksComplete").getAsInt();
			}
		}

		JsonObject o = new JsonObject();
		o.addProperty("tiersComplete", tiersComplete);
		o.addProperty("tiersTotal", regions.size() * 4);
		o.addProperty("tasksComplete", tasksComplete);
		return o;
	}

	/**
	 * Completion threshold, verified against a live account rather than assumed.
	 *
	 * Standard diaries finish at 1: every tier reading 1 had its reward claimed
	 * (impossible before finishing) and a full task count, while every 0 tier was
	 * partial — e.g. Kandarin medium at 13 tasks, unclaimed. RuneLite's own
	 * DailyTasksPlugin gates dailies on the same {@code == 1}.
	 *
	 * Karamja's legacy ATJUN varbits finish at 2: its medium read 1 with 8 tasks
	 * done and hard read 1 with 3, so there 1 only means in progress.
	 *
	 * "Started" and per-task progress live in their own varbits (see diaries()).
	 */
	static boolean diaryComplete(int raw, int completeAt)
	{
		return raw >= completeAt;
	}
}
