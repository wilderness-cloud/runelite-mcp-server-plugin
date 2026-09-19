package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.List;
import net.runelite.api.Client;
import net.runelite.api.gameval.DBTableID;

/**
 * Boss killcounts straight from the client's own hiscores-bosses table: each
 * row names a boss and the varp that stores its KC. Exact values, no hiscores
 * lookup lag; only meaningful while logged in.
 */
public class BossKcProvider
{
	private final Client client;

	public BossKcProvider(Client client)
	{
		this.client = client;
	}

	public JsonObject bossKc()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		JsonArray bosses = new JsonArray();

		List<Integer> rows = client.getDBTableRows(DBTableID.HiscoresBossesInfo.ID);
		if (rows != null)
		{
			for (int row : rows)
			{
				try
				{
					Object[] nameField = client.getDBTableField(row, DBTableID.HiscoresBossesInfo.COL_BOSSNAME, 0);
					if (nameField == null || nameField.length == 0 || !(nameField[0] instanceof String))
					{
						continue;
					}
					JsonObject boss = new JsonObject();
					boss.addProperty("name", (String) nameField[0]);

					Object[] varpField = client.getDBTableField(row, DBTableID.HiscoresBossesInfo.COL_BOSSVARP, 0);
					if (varpField != null && varpField.length > 0 && varpField[0] instanceof Integer)
					{
						int varpId = (Integer) varpField[0];
						boss.addProperty("varpId", varpId);
						boss.addProperty("kills", client.getVarpValue(varpId));
					}
					bosses.add(boss);
				}
				catch (Exception ignored)
				{
					// unreadable row; skip it rather than fail the whole endpoint
				}
			}
		}

		o.add("bosses", bosses);
		o.addProperty("trackedBosses", bosses.size());
		return o;
	}
}
