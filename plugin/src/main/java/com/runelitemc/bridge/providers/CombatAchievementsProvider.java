package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.StructComposition;
import net.runelite.api.Varbits;
import net.runelite.api.gameval.VarPlayerID;

/**
 * Combat achievements: task lists live in tier enums whose entries are structs
 * (param 1306 = task id, 1308 = name); completion is a bitfield across 21
 * varps, bit (id % 32) of varp (id / 32). All readable without opening any
 * interface.
 */
public class CombatAchievementsProvider
{
	private static final int[] TIER_ENUM_IDS = {3981, 3982, 3983, 3984, 3985, 3986};
	private static final String[] TIER_NAMES = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
	private static final int[] TIER_COMPLETE_VARBITS = {
		Varbits.COMBAT_ACHIEVEMENT_TIER_EASY,
		Varbits.COMBAT_ACHIEVEMENT_TIER_MEDIUM,
		Varbits.COMBAT_ACHIEVEMENT_TIER_HARD,
		Varbits.COMBAT_ACHIEVEMENT_TIER_ELITE,
		Varbits.COMBAT_ACHIEVEMENT_TIER_MASTER,
		Varbits.COMBAT_ACHIEVEMENT_TIER_GRANDMASTER,
	};
	private static final int[] COMPLETED_VARPS = {
		VarPlayerID.CA_TASK_COMPLETED_0, VarPlayerID.CA_TASK_COMPLETED_1, VarPlayerID.CA_TASK_COMPLETED_2,
		VarPlayerID.CA_TASK_COMPLETED_3, VarPlayerID.CA_TASK_COMPLETED_4, VarPlayerID.CA_TASK_COMPLETED_5,
		VarPlayerID.CA_TASK_COMPLETED_6, VarPlayerID.CA_TASK_COMPLETED_7, VarPlayerID.CA_TASK_COMPLETED_8,
		VarPlayerID.CA_TASK_COMPLETED_9, VarPlayerID.CA_TASK_COMPLETED_10, VarPlayerID.CA_TASK_COMPLETED_11,
		VarPlayerID.CA_TASK_COMPLETED_12, VarPlayerID.CA_TASK_COMPLETED_13, VarPlayerID.CA_TASK_COMPLETED_14,
		VarPlayerID.CA_TASK_COMPLETED_15, VarPlayerID.CA_TASK_COMPLETED_16, VarPlayerID.CA_TASK_COMPLETED_17,
		VarPlayerID.CA_TASK_COMPLETED_18, VarPlayerID.CA_TASK_COMPLETED_19, VarPlayerID.CA_TASK_COMPLETED_20,
	};
	private static final int TASK_ID_PARAM = 1306;
	private static final int TASK_NAME_PARAM = 1308;

	private final Client client;

	public CombatAchievementsProvider(Client client)
	{
		this.client = client;
	}

	public JsonObject combatAchievements()
	{
		return combatAchievements(null, null, null);
	}

	/**
	 * Filtered task list. Tier summaries are always returned in full — they are
	 * six small objects and they are what makes a filtered response
	 * interpretable ("12 of 60 Hard done" alongside the 48 you asked for).
	 *
	 * @param tier      tier name, or null for every tier
	 * @param completed TRUE for done, FALSE for remaining, null for both
	 * @param search    case-insensitive substring of the task name, or null
	 */
	public JsonObject combatAchievements(String tier, Boolean completedFilter, String search)
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());

		JsonArray tiers = new JsonArray();
		int totalTasks = 0;
		int totalCompleted = 0;

		for (int i = 0; i < TIER_ENUM_IDS.length; i++)
		{
			if (tier != null && !TIER_NAMES[i].equalsIgnoreCase(tier))
			{
				continue;
			}

			JsonArray tasks = new JsonArray();
			int completedCount = 0;
			int tierTaskCount = 0;

			EnumComposition tierEnum = client.getEnum(TIER_ENUM_IDS[i]);
			if (tierEnum != null && tierEnum.getIntVals() != null)
			{
				for (int structId : tierEnum.getIntVals())
				{
					StructComposition struct = client.getStructComposition(structId);
					if (struct == null)
					{
						continue;
					}
					String name = struct.getStringValue(TASK_NAME_PARAM);
					if (name == null || name.isEmpty())
					{
						continue;
					}
					int id = struct.getIntValue(TASK_ID_PARAM);
					boolean completed = isCompleted(id);
					tierTaskCount++;
					if (completed)
					{
						completedCount++;
					}
					if (!taskMatches(name, completed, completedFilter, search))
					{
						continue;
					}
					JsonObject task = new JsonObject();
					task.addProperty("id", id);
					task.addProperty("name", name);
					task.addProperty("completed", completed);
					tasks.add(task);
				}
			}

			JsonObject tierJson = new JsonObject();
			tierJson.addProperty("tier", TIER_NAMES[i]);
			tierJson.addProperty("tierCompleted", client.getVarbitValue(TIER_COMPLETE_VARBITS[i]) >= 2);
			tierJson.addProperty("tasksCompleted", completedCount);
			tierJson.addProperty("taskCount", tierTaskCount);
			if (tasks.size() != tierTaskCount)
			{
				// Say so explicitly: a filtered list beside an unfiltered count
				// is otherwise easy to misread as missing data.
				tierJson.addProperty("tasksReturned", tasks.size());
			}
			tierJson.add("tasks", tasks);
			tiers.add(tierJson);

			totalTasks += tierTaskCount;
			totalCompleted += completedCount;
		}

		o.add("tiers", tiers);
		o.addProperty("totalTasks", totalTasks);
		o.addProperty("totalCompleted", totalCompleted);
		if (tier != null || completedFilter != null || search != null)
		{
			JsonObject applied = new JsonObject();
			if (tier != null)
			{
				applied.addProperty("tier", tier);
			}
			if (completedFilter != null)
			{
				applied.addProperty("completed", completedFilter.booleanValue());
			}
			if (search != null)
			{
				applied.addProperty("search", search);
			}
			o.add("filter", applied);
		}
		return o;
	}

	private static boolean taskMatches(String name, boolean completed, Boolean completedFilter, String search)
	{
		if (completedFilter != null && completedFilter.booleanValue() != completed)
		{
			return false;
		}
		return search == null
			|| name.toLowerCase(java.util.Locale.ROOT).contains(search.toLowerCase(java.util.Locale.ROOT));
	}

	public JsonObject summary()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		JsonArray tiers = new JsonArray();
		int totalTasks = 0;
		int totalCompleted = 0;

		for (int i = 0; i < TIER_ENUM_IDS.length; i++)
		{
			int completedCount = 0;
			int taskCount = 0;

			EnumComposition tierEnum = client.getEnum(TIER_ENUM_IDS[i]);
			if (tierEnum != null && tierEnum.getIntVals() != null)
			{
				for (int structId : tierEnum.getIntVals())
				{
					StructComposition struct = client.getStructComposition(structId);
					if (struct == null || struct.getStringValue(TASK_NAME_PARAM) == null)
					{
						continue;
					}
					taskCount++;
					if (isCompleted(struct.getIntValue(TASK_ID_PARAM)))
					{
						completedCount++;
					}
				}
			}

			JsonObject tier = new JsonObject();
			tier.addProperty("tier", TIER_NAMES[i]);
			tier.addProperty("tierCompleted", client.getVarbitValue(TIER_COMPLETE_VARBITS[i]) >= 2);
			tier.addProperty("tasksCompleted", completedCount);
			tier.addProperty("taskCount", taskCount);
			tiers.add(tier);

			totalTasks += taskCount;
			totalCompleted += completedCount;
		}

		o.add("tiers", tiers);
		o.addProperty("totalTasks", totalTasks);
		o.addProperty("totalCompleted", totalCompleted);
		o.addProperty("note", "Per-task detail via /combat-achievements");
		return o;
	}

	private boolean isCompleted(int taskId)
	{
		if (taskId < 0)
		{
			return false;
		}
		int varpIndex = taskId / 32;
		if (varpIndex >= COMPLETED_VARPS.length)
		{
			return false;
		}
		return (client.getVarpValue(COMPLETED_VARPS[varpIndex]) & (1 << (taskId % 32))) != 0;
	}
}
