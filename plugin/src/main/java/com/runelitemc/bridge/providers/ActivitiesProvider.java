package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;

/**
 * The progress that is neither a skill, a quest, a diary nor a killcount:
 * Great Kourend favour, minigame reward-point balances and the farming guild
 * contract.
 *
 * These are plain varbit and varp reads — the game has always known them, they
 * just had nowhere to land in the snapshot, so a caller had to ask the player
 * or guess. Each is cheap, so they are served together.
 *
 * Miniquest progress is NOT here: every miniquest is in the Quest enum and so
 * already appears in game_state's quests section with its own completion state.
 * What that section cannot say is which *steps* of a multi-part miniquest like
 * Barbarian Training are done; those sub-steps have no stable named varbits in
 * the API, so they are left alone rather than guessed at.
 */
public class ActivitiesProvider
{
	/** Kourend houses and the varbit each stores favour in. */
	private static final class House
	{
		private final String name;
		private final int varbit;

		private House(String name, int varbit)
		{
			this.name = name;
			this.varbit = varbit;
		}
	}

	private static final House[] FAVOUR_HOUSES = {
		new House("Arceuus", VarbitID.ARCQUEST_FAVOUR),
		new House("Hosidius", VarbitID.HOSIDIUSQUEST_FAVOUR),
		new House("Lovakengj", VarbitID.LOVAQUEST_FAVOUR),
		new House("Piscarilius", VarbitID.PISCQUEST_FAVOUR),
		new House("Shayzien", VarbitID.SHAYZIENQUEST_FAVOUR),
	};

	/** Favour is stored in tenths of a percent: 1000 is 100%. */
	private static final int FAVOUR_FULL = 1000;

	private final Client client;
	private final FarmingData farmingData;

	public ActivitiesProvider(Client client, FarmingData farmingData)
	{
		this.client = client;
		this.farmingData = farmingData;
	}

	public JsonObject activities()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());
		o.add("kourendFavour", kourendFavour());
		o.add("rewardPoints", rewardPoints());
		o.add("farmingContract", farmingContract());
		return o;
	}

	/**
	 * Favour with the five Great Kourend houses. Gates a long list of content
	 * (Woodcutting Guild at 75% Hosidius, blast mining at 100% Lovakengj, the
	 * Arceuus spellbook, Shayzien armour), which is exactly the sort of thing a
	 * plan trips over when it has to be asked about instead of read.
	 */
	private JsonObject kourendFavour()
	{
		JsonObject out = new JsonObject();
		JsonArray houses = new JsonArray();
		int atFull = 0;

		for (House house : FAVOUR_HOUSES)
		{
			int raw = client.getVarbitValue(house.varbit);
			JsonObject entry = new JsonObject();
			entry.addProperty("house", house.name);
			entry.addProperty("percent", raw / 10.0);
			entry.addProperty("rawTenths", raw);
			boolean full = raw >= FAVOUR_FULL;
			entry.addProperty("full", full);
			if (full)
			{
				atFull++;
			}
			houses.add(entry);
		}

		out.add("houses", houses);
		out.addProperty("housesAtFullFavour", atFull);
		return out;
	}

	/**
	 * Minigame currencies a plan needs to know the balance of. Slayer points are
	 * deliberately absent: they are already in the slayer section, and one number
	 * in two places is one number that can disagree with itself.
	 */
	private JsonArray rewardPoints()
	{
		JsonArray out = new JsonArray();
		out.add(points("Tithe Farm", client.getVarbitValue(VarbitID.HOSIDIUS_TITHE_REWARDPOINTS),
			"Spent on farmer's outfit pieces, grape seeds and bologa's blessing."));
		out.add(points("Nightmare Zone", client.getVarpValue(VarPlayerID.NZONE_REWARDPOINTS),
			"Spent on imbues, herb boxes and potions at the NMZ reward shop."));
		out.add(points("Soul Wars", client.getVarpValue(VarPlayerID.SOUL_WARS_ZEAL_TOKENS),
			"Zeal tokens, spent on imbues and Soul Wars rewards."));
		out.add(points("Giants' Foundry", client.getVarpValue(VarPlayerID.GIANTS_FOUNDRY_REWARD_SHOP_POINTS),
			"Spent at Kovac's reward shop (smithing outfit, double ammo mould)."));

		// Only meaningful mid-run, but cheap and it tells a caller whether the
		// player is in the middle of something.
		int titheRun = client.getVarbitValue(VarbitID.HOSIDIUS_TITHE_SCORE);
		if (titheRun > 0)
		{
			out.add(points("Tithe Farm (current run)", titheRun, "Fruit handed in during the run in progress."));
		}
		return out;
	}

	private static JsonObject points(String activity, int value, String spentOn)
	{
		JsonObject o = new JsonObject();
		o.addProperty("activity", activity);
		o.addProperty("points", value);
		o.addProperty("spentOn", spentOn);
		return o;
	}

	/**
	 * The Farming Guild contract, decoded through the same produce table
	 * farming_state uses so the two answers agree on crop names.
	 * FARMGUILD_CONTRACT_COMPLETE above zero means there is no contract running.
	 */
	private JsonObject farmingContract()
	{
		JsonObject out = new JsonObject();
		out.addProperty("contractsCompleted", client.getVarbitValue(VarbitID.FARMGUILD_CONTRACT_COUNT));

		if (client.getVarbitValue(VarbitID.FARMGUILD_CONTRACT_COMPLETE) > 0)
		{
			out.addProperty("active", false);
			out.addProperty("note", "No contract in progress — ask Guildmaster Jane for one.");
			return out;
		}

		int type = client.getVarbitValue(VarbitID.FARMGUILD_CONTRACT_TYPE);
		if (type == 0)
		{
			out.addProperty("active", false);
			out.addProperty("note", "No contract in progress — ask Guildmaster Jane for one.");
			return out;
		}

		out.addProperty("active", true);
		out.addProperty("difficultyLevel", client.getVarbitValue(VarbitID.FARMGUILD_CONTRACT_LEVEL));

		String produce = produceForContract(type);
		if (produce != null)
		{
			out.addProperty("produce", produce);
		}
		else
		{
			out.addProperty("contractVarbitValue", type);
			out.addProperty("note", "Contract crop not in the generated produce table; regenerate with "
				+ "scripts/gen-farming-data.mjs against a newer RuneLite tag.");
		}
		return out;
	}

	private String produceForContract(int contractVarbitValue)
	{
		for (Map.Entry<String, FarmingData.Produce> e : farmingData.produce.entrySet())
		{
			if (e.getValue().contractVarbitValue == contractVarbitValue)
			{
				return e.getValue().name;
			}
		}
		return null;
	}
}
