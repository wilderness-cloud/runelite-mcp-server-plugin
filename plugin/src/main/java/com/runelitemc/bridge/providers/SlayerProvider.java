package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * Task/points/unlock state. Name resolution mirrors the core slayer plugin:
 * varps point into the SlayerTask/SlayerArea DB tables. Reward unlocks are
 * emitted as raw bitfield varps; there is no first-party decode table anymore.
 */
public class SlayerProvider
{
	private static final int BOSS_TASK_ID = 98; // from [proc,helper_slayer_current_assignment]

	private final Client client;

	public SlayerProvider(Client client)
	{
		this.client = client;
	}

	public JsonObject slayer()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());

		int remaining = client.getVarpValue(VarPlayerID.SLAYER_COUNT);
		int targetId = client.getVarpValue(VarPlayerID.SLAYER_TARGET);
		o.addProperty("taskRemaining", remaining);

		if (remaining > 0 && targetId > 0)
		{
			o.addProperty("taskId", targetId);
			String task = taskName(targetId);
			if (task != null)
			{
				o.addProperty("task", task);
			}
			o.addProperty("initialAmount", client.getVarpValue(VarPlayerID.SLAYER_COUNT_ORIGINAL));

			int areaId = client.getVarpValue(VarPlayerID.SLAYER_AREA);
			if (areaId > 0)
			{
				o.addProperty("areaId", areaId);
				String area = areaName(areaId);
				if (area != null)
				{
					o.addProperty("area", area);
				}
			}
		}

		o.addProperty("points", client.getVarbitValue(VarbitID.SLAYER_POINTS));
		o.addProperty("tasksCompleted1", client.getVarpValue(VarPlayerID.SLAYER_TASKS_COMPLETED_1));
		o.addProperty("tasksCompleted2", client.getVarpValue(VarPlayerID.SLAYER_TASKS_COMPLETED_2));

		JsonObject unlocks = new JsonObject();
		unlocks.addProperty("rewardsUnlocks", client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS));
		unlocks.addProperty("rewardsUnlocks1", client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS1));
		unlocks.addProperty("blockedTasks", client.getVarpValue(VarPlayerID.SLAYER_REWARDS_BLOCKED));
		o.add("unlockBitFields", unlocks);
		o.add("unlocks", decodedUnlocks());

		return o;
	}

	/**
	 * Decoded reward unlocks from the SlayerUnlock DB table: each row names an
	 * unlock and its bit index. Bits 0-31 live in SLAYER_REWARDS_UNLOCKS and
	 * 32-63 in SLAYER_REWARDS_UNLOCKS1 (same packing pattern the game uses for
	 * combat achievement tasks).
	 */
	private JsonArray decodedUnlocks()
	{
		JsonArray out = new JsonArray();
		List<Integer> rows = client.getDBTableRows(DBTableID.SlayerUnlock.ID);
		if (rows == null)
		{
			return out;
		}

		long varpLow = 0;
		long varpHigh = 0;
		try
		{
			varpLow = Integer.toUnsignedLong(client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS));
			varpHigh = Integer.toUnsignedLong(client.getVarpValue(VarPlayerID.SLAYER_REWARDS_UNLOCKS1));
		}
		catch (Exception ignored)
		{
			// leave bitfields at 0; names still listed as unknown state
		}

		for (int row : rows)
		{
			try
			{
				Object[] bitField = client.getDBTableField(row, DBTableID.SlayerUnlock.COL_BIT, 0);
				if (bitField == null || bitField.length == 0 || !(bitField[0] instanceof Integer))
				{
					continue;
				}
				int bit = (Integer) bitField[0];
				if (bit < 0 || bit >= 64)
				{
					continue;
				}

				Object[] nameField = client.getDBTableField(row, DBTableID.SlayerUnlock.COL_NAME, 0);
				if (nameField == null || nameField.length == 0 || !(nameField[0] instanceof String))
				{
					continue;
				}
				String name = (String) nameField[0];
				if (name.isEmpty())
				{
					continue;
				}

				long mask = 1L << (bit % 32);
				boolean unlocked = bit < 32 ? (varpLow & mask) != 0 : (varpHigh & mask) != 0;

				JsonObject unlock = new JsonObject();
				unlock.addProperty("name", name);
				unlock.addProperty("bit", bit);
				unlock.addProperty("unlocked", unlocked);

				Object[] costField = client.getDBTableField(row, DBTableID.SlayerUnlock.COL_COST, 0);
				if (costField != null && costField.length > 0 && costField[0] instanceof Integer)
				{
					unlock.addProperty("cost", (Integer) costField[0]);
				}
				out.add(unlock);
			}
			catch (Exception ignored)
			{
				// unreadable row; skip it
			}
		}
		return out;
	}

	private String taskName(int taskId)
	{
		try
		{
			int taskRow;
			if (taskId == BOSS_TASK_ID)
			{
				int bossId = client.getVarbitValue(VarbitID.SLAYER_TARGET_BOSSID);
				List<Integer> bossRows = client.getDBRowsByValue(
					DBTableID.SlayerTaskSublist.ID,
					DBTableID.SlayerTaskSublist.COL_TASK_SUBTABLE_ID,
					0,
					bossId);
				if (bossRows == null || bossRows.isEmpty())
				{
					return null;
				}
				Object[] field = client.getDBTableField(bossRows.get(0), DBTableID.SlayerTaskSublist.COL_TASK, 0);
				if (field == null || field.length == 0 || !(field[0] instanceof Integer))
				{
					return null;
				}
				taskRow = (Integer) field[0];
			}
			else
			{
				List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerTask.ID, DBTableID.SlayerTask.COL_ID, 0, taskId);
				if (rows == null || rows.isEmpty())
				{
					return null;
				}
				taskRow = rows.get(0);
			}
			Object[] name = client.getDBTableField(taskRow, DBTableID.SlayerTask.COL_NAME_UPPERCASE, 0);
			return name != null && name.length > 0 && name[0] instanceof String ? (String) name[0] : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}

	private String areaName(int areaId)
	{
		try
		{
			List<Integer> rows = client.getDBRowsByValue(DBTableID.SlayerArea.ID, DBTableID.SlayerArea.COL_AREA_ID, 0, areaId);
			if (rows == null || rows.isEmpty())
			{
				return null;
			}
			Object[] name = client.getDBTableField(rows.get(0), DBTableID.SlayerArea.COL_AREA_NAME_IN_HELPER, 0);
			return name != null && name.length > 0 && name[0] instanceof String ? (String) name[0] : null;
		}
		catch (Exception e)
		{
			return null;
		}
	}
}
