package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.Player;
import net.runelite.api.Skill;
import net.runelite.api.VarPlayer;
import net.runelite.api.WorldType;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.vars.AccountType;

public class PlayerStateProvider
{
	private final Client client;

	public PlayerStateProvider(Client client)
	{
		this.client = client;
	}

	public JsonObject state()
	{
		JsonObject o = new JsonObject();
		o.addProperty("capturedAt", Instant.now().toString());

		Player player = client.getLocalPlayer();
		o.addProperty("loggedIn", player != null);

		String name = player != null ? player.getName() : null;
		if (name == null || name.isEmpty())
		{
			name = client.getUsername();
		}
		if (name != null && !name.isEmpty())
		{
			o.addProperty("username", name);
		}

		if (player != null)
		{
			o.addProperty("combatLevel", player.getCombatLevel());
			WorldPoint loc = player.getWorldLocation();
			if (loc != null)
			{
				JsonObject l = new JsonObject();
				l.addProperty("x", loc.getX());
				l.addProperty("y", loc.getY());
				l.addProperty("plane", loc.getPlane());
				l.addProperty("regionId", loc.getRegionID());
				o.add("location", l);
			}
		}

		o.addProperty("world", client.getWorld());

		Set<WorldType> worldTypes = client.getWorldType();
		if (worldTypes != null)
		{
			JsonArray types = new JsonArray();
			for (WorldType t : worldTypes)
			{
				types.add(t.name());
			}
			o.add("worldTypes", types);
			o.addProperty("members", worldTypes.contains(WorldType.MEMBERS));
		}

		AccountType accountType = client.getAccountType();
		if (accountType != null)
		{
			o.addProperty("accountType", accountType.name());
		}

		JsonObject skills = new JsonObject();
		for (Skill skill : Skill.values())
		{
			if ("Overall".equalsIgnoreCase(skill.getName()))
			{
				continue;
			}
			JsonObject s = new JsonObject();
			s.addProperty("level", client.getRealSkillLevel(skill));
			s.addProperty("boosted", client.getBoostedSkillLevel(skill));
			s.addProperty("xp", client.getSkillExperience(skill));
			skills.add(skill.getName(), s);
		}
		o.add("skills", skills);

		JsonObject overall = new JsonObject();
		overall.addProperty("totalLevel", client.getTotalLevel());
		overall.addProperty("xp", client.getOverallExperience());
		o.add("overall", overall);

		o.addProperty("hitpoints", client.getBoostedSkillLevel(Skill.HITPOINTS));
		o.addProperty("prayerPoints", client.getBoostedSkillLevel(Skill.PRAYER));
		o.addProperty("runEnergy", client.getEnergy() / 100.0);
		o.addProperty("weightTenthsKg", client.getWeight());
		o.addProperty("kudos", client.getVarbitValue(VarbitID.VM_KUDOS));
		o.addProperty("questPoints", client.getVarpValue(VarPlayer.QUEST_POINTS));

		return o;
	}
}
