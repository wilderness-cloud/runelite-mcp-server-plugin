package com.runelitemc.bridge.providers;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.runelite.api.Client;
import net.runelite.api.WorldType;
import net.runelite.client.config.ConfigManager;

/**
 * Farming patches and bird houses, answered from RuneLite's own Time Tracking
 * observations.
 *
 * Patch state is not readable from varbits unless the player is standing in the
 * region, so this is the one provider that does not read the live client: the
 * Time Tracking plugin writes what it last saw of each patch into the RuneScape
 * profile config (group "timetracking", key "&lt;regionId&gt;.&lt;varbit&gt;",
 * value "&lt;varbitValue&gt;:&lt;unixSeconds&gt;"), and growth from there is
 * arithmetic. The same store holds bird houses under "birdhouse.&lt;varp&gt;".
 *
 * Two consequences a caller has to respect:
 *
 * - Time Tracking must be enabled (it is by default). With it off nothing is
 *   ever written and every patch reads as unobserved.
 * - A patch is only as fresh as the last visit. observedAt says when; a patch
 *   the player has never visited on this account is omitted rather than guessed
 *   at. Growth is predicted forward from the observation, which is exact —
 *   farming ticks are wall-clock — but a patch harvested or cleared by some
 *   other means will read stale until the player next sees it.
 *
 * The prediction is a port of FarmingTracker.predictPatch, including the farm
 * tick offset the plugin learns by watching a patch advance.
 */
public class FarmingProvider
{
	private static final String GROUP = "timetracking";
	private static final String AUTOWEED = "autoweed";
	private static final String FARM_TICK_OFFSET = "farmTickOffset";
	private static final String FARM_TICK_OFFSET_PRECISION = "farmTickOffsetPrecision";
	/** Autoweed.ON.ordinal() in RuneLite's enum (UNOWNED, OFF, ON). */
	private static final String AUTOWEED_ON = "2";
	private static final String WEEDS = "WEEDS";

	/** Produce that means "nothing planted here" rather than a crop. */
	private static final Set<String> NOT_A_CROP = new HashSet<>(Arrays.asList(WEEDS, "SCARECROW"));

	private final Client client;
	private final ConfigManager configManager;
	private final FarmingData data;

	public FarmingProvider(Client client, ConfigManager configManager, FarmingData data)
	{
		this.client = client;
		this.configManager = configManager;
		this.data = data;
	}

	/**
	 * @param tabs         restrict to these Time Tracking tabs (HERB, TREE, ...); all when null/empty
	 * @param states       restrict to these crop states (HARVESTABLE, GROWING, DISEASED, DEAD, EMPTY, FILLING)
	 * @param includeBare  include patches with nothing planted; off by default, since a
	 *                     checklist wants the patches that need something
	 */
	public JsonObject farming(Set<String> tabs, Set<String> states, boolean includeBare)
	{
		JsonObject out = new JsonObject();
		out.addProperty("capturedAt", Instant.now().toString());

		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return unavailable(out, "No RuneScape profile is active — log in once so RuneLite knows which "
				+ "account's patches to read.");
		}

		Context ctx = new Context(profile);
		Set<String> wantTabs = upper(tabs);
		Set<String> wantStates = upper(states);

		List<JsonObject> patches = new ArrayList<>();
		Map<String, Integer> counts = new LinkedHashMap<>();
		int observed = 0;

		for (FarmingData.Region region : data.regions)
		{
			for (FarmingData.Patch patch : region.patches)
			{
				FarmingData.Implementation impl = data.implementations.get(patch.implementation);
				JsonObject row = predict(ctx, region, patch, impl);
				if (row == null)
				{
					continue;
				}
				observed++;

				String state = row.get("state").getAsString();
				counts.merge(state.toLowerCase(Locale.ROOT), 1, Integer::sum);

				if (!wantTabs.isEmpty() && !wantTabs.contains(impl.tab))
				{
					continue;
				}
				if (!wantStates.isEmpty() && !wantStates.contains(state))
				{
					continue;
				}
				if (!includeBare && row.get("bare").getAsBoolean())
				{
					continue;
				}
				patches.add(row);
			}
		}

		JsonObject birdHouses = birdHouses(ctx);
		if (observed == 0 && !birdHouses.get("available").getAsBoolean())
		{
			return unavailable(out, "No farming observations stored for this account. RuneLite's Time Tracking "
				+ "plugin records patches as you pass them — check it is enabled, then visit a patch once.");
		}

		// Ready first, then whatever finishes soonest: that is the order a caller
		// reads out as a to-do list.
		patches.sort((a, b) ->
		{
			boolean ra = a.get("ready").getAsBoolean();
			boolean rb = b.get("ready").getAsBoolean();
			if (ra != rb)
			{
				return ra ? -1 : 1;
			}
			long ta = a.has("readyInSeconds") ? a.get("readyInSeconds").getAsLong() : Long.MAX_VALUE;
			long tb = b.has("readyInSeconds") ? b.get("readyInSeconds").getAsLong() : Long.MAX_VALUE;
			return Long.compare(ta, tb);
		});

		JsonObject summary = new JsonObject();
		summary.addProperty("observedPatches", observed);
		for (Map.Entry<String, Integer> e : counts.entrySet())
		{
			summary.addProperty(e.getKey(), e.getValue());
		}

		out.addProperty("available", true);
		out.add("summary", summary);
		JsonArray array = new JsonArray();
		patches.forEach(array::add);
		out.add("patches", array);
		out.add("birdHouses", birdHouses);
		out.addProperty("dataSource", data.source);
		return out;
	}

	/** Bird houses on their own, for a caller that only wants the 50-minute loop. */
	public JsonObject birdHouses()
	{
		JsonObject out = new JsonObject();
		out.addProperty("capturedAt", Instant.now().toString());

		String profile = configManager.getRSProfileKey();
		if (profile == null)
		{
			return unavailable(out, "No RuneScape profile is active — log in once first.");
		}
		for (Map.Entry<String, com.google.gson.JsonElement> e : birdHouses(new Context(profile)).entrySet())
		{
			out.add(e.getKey(), e.getValue());
		}
		return out;
	}

	private static JsonObject unavailable(JsonObject out, String note)
	{
		out.addProperty("available", false);
		out.addProperty("note", note);
		return out;
	}

	/**
	 * Everything read once per request: the profile key, the autoweed setting and
	 * the learned farm tick offset. Reading these per patch would be 300+ config
	 * lookups for one answer.
	 */
	private final class Context
	{
		private final String profile;
		private final boolean autoweed;
		private final Integer offsetPrecisionMins;
		private final Integer offsetTimeMins;
		private final boolean leagues;
		private final long now = Instant.now().getEpochSecond();

		Context(String profile)
		{
			this.profile = profile;
			this.autoweed = AUTOWEED_ON.equals(configManager.getConfiguration(GROUP, profile, AUTOWEED));
			this.offsetPrecisionMins = configManager.getConfiguration(GROUP, profile, FARM_TICK_OFFSET_PRECISION, int.class);
			this.offsetTimeMins = configManager.getConfiguration(GROUP, profile, FARM_TICK_OFFSET, int.class);

			EnumSet<WorldType> worldTypes = client.getWorldType();
			this.leagues = worldTypes != null
				&& worldTypes.contains(WorldType.SEASONAL)
				&& !worldTypes.contains(WorldType.DEADMAN);
		}

		String get(String key)
		{
			return configManager.getConfiguration(GROUP, profile, key);
		}

		long tickTime(int tickRate, int ticks, long requestedTime)
		{
			return FarmingProvider.tickTime(offsetPrecisionMins, offsetTimeMins, tickRate, ticks, requestedTime);
		}
	}

	/** Port of FarmingTracker.predictPatch; null when this patch was never observed. */
	private JsonObject predict(Context ctx, FarmingData.Region region, FarmingData.Patch patch,
		FarmingData.Implementation impl)
	{
		long[] stored = parseObservation(ctx.get(FarmingData.configKey(region, patch)));
		if (stored == null)
		{
			return null;
		}
		int value = (int) stored[0];
		long observedAt = stored[1];

		JsonObject row = patchRow(region, patch, impl, observedAt, ctx.now);

		FarmingData.Range range = rangeFor(impl, value);
		if (range == null)
		{
			// A varbit value RuneLite's tables do not cover: the game gained
			// produce since the pinned tag. Say so rather than guess.
			row.addProperty("state", "UNKNOWN");
			row.addProperty("bare", false);
			row.addProperty("ready", false);
			row.addProperty("varbitValue", value);
			row.addProperty("note", "varbit value not covered by the generated tables; regenerate with "
				+ "scripts/gen-farming-data.mjs against a newer RuneLite tag");
			return row;
		}

		FarmingData.Produce produce = data.produce.get(range.produce);
		String cropState = range.state;
		boolean harvest = "HARVESTABLE".equals(cropState);
		int stage = range.a + range.b * value;
		int stages = harvest || "FILLING".equals(cropState) ? produce.harvestStages : produce.stages;
		int tickrate = harvest
			? produce.regrowTickrate
			: "GROWING".equals(cropState) ? produce.tickrate : 0;

		// Farming ticks on leagues worlds are 1 minute instead of 5
		if (ctx.leagues)
		{
			tickrate = tickrate / 5;
		}
		if (ctx.autoweed && WEEDS.equals(range.produce))
		{
			stage = 0;
			stages = 1;
			tickrate = 0;
		}

		long doneEstimate = 0;
		if (tickrate > 0)
		{
			long tickNow = ctx.tickTime(tickrate, 0, ctx.now);
			long tickThen = ctx.tickTime(tickrate, 0, observedAt);
			int delta = (int) ((tickNow - tickThen) / (tickrate * 60L));

			doneEstimate = ctx.tickTime(tickrate, stages - 1 - stage, tickThen);

			stage += delta;
			if (stage >= stages)
			{
				stage = stages - 1;
			}
		}

		boolean crop = !NOT_A_CROP.contains(range.produce);
		boolean bare = !crop || "EMPTY".equals(cropState) || "FILLING".equals(cropState);
		boolean ready = crop && (harvest
			|| ("GROWING".equals(cropState) && (tickrate == 0 || doneEstimate <= ctx.now)));

		row.addProperty("produce", produce.name);
		row.addProperty("state", cropState);
		row.addProperty("stage", stage);
		row.addProperty("stages", stages);
		row.addProperty("bare", bare);
		row.addProperty("ready", ready);
		if (doneEstimate > 0 && !ready)
		{
			row.addProperty("readyAt", Instant.ofEpochSecond(doneEstimate).toString());
			row.addProperty("readyInSeconds", doneEstimate - ctx.now);
		}
		else if (doneEstimate > 0)
		{
			// Harvestable and regrowing: the estimate is the next regrow tick,
			// not a wait — the crop can be picked now.
			row.addProperty("regrowsAt", Instant.ofEpochSecond(doneEstimate).toString());
		}
		if (impl.healthCheckRequired && "GROWING".equals(cropState) && stage >= stages - 1)
		{
			row.addProperty("needsHealthCheck", true);
		}
		return row;
	}

	private static JsonObject patchRow(FarmingData.Region region, FarmingData.Patch patch,
		FarmingData.Implementation impl, long observedAt, long now)
	{
		JsonObject row = new JsonObject();
		row.addProperty("region", region.name);
		if (patch.name != null && !patch.name.isEmpty())
		{
			row.addProperty("patch", patch.name);
		}
		row.addProperty("type", patch.implementation);
		row.addProperty("patchGroup", impl.tab);
		row.addProperty("observedAt", Instant.ofEpochSecond(observedAt).toString());
		row.addProperty("observedSecondsAgo", now - observedAt);
		return row;
	}

	static FarmingData.Range rangeFor(FarmingData.Implementation impl, int value)
	{
		for (FarmingData.Range range : impl.ranges)
		{
			if (value >= range.lo && value <= range.hi)
			{
				return range;
			}
		}
		return null;
	}

	/** "&lt;varbitValue&gt;:&lt;unixSeconds&gt;" to {value, seconds}, or null when absent or malformed. */
	static long[] parseObservation(String stored)
	{
		if (stored == null)
		{
			return null;
		}
		String[] parts = stored.split(":");
		if (parts.length != 2)
		{
			return null;
		}
		try
		{
			long value = Long.parseLong(parts[0].trim());
			long seconds = Long.parseLong(parts[1].trim());
			return seconds > 0 ? new long[]{value, seconds} : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/**
	 * Port of FarmingTracker.getTickTime: when the {@code ticks}-th growth tick
	 * after {@code requestedTime} lands, allowing for the offset the Time
	 * Tracking plugin learns by watching a patch advance. Offsets are stored
	 * positive but are negative.
	 */
	static long tickTime(Integer offsetPrecisionMins, Integer offsetTimeMins, int tickRate, int ticks,
		long requestedTime)
	{
		long offset = 0L;
		if (offsetPrecisionMins != null && offsetTimeMins != null
			&& (offsetPrecisionMins >= tickRate || offsetPrecisionMins >= 40))
		{
			offset = (offsetTimeMins % tickRate) * 60L;
		}
		long unixNow = requestedTime + offset;
		long timeOfCurrentTick = unixNow - (unixNow % (tickRate * 60L));
		return timeOfCurrentTick + ((long) ticks * tickRate * 60L) - offset;
	}

	private JsonObject birdHouses(Context ctx)
	{
		JsonObject out = new JsonObject();
		JsonArray houses = new JsonArray();
		int filling = 0;
		int ready = 0;
		int needsSeeding = 0;
		long soonest = Long.MAX_VALUE;
		int typeCount = data.birdhouses.types.size();

		for (FarmingData.Space space : data.birdhouses.spaces)
		{
			long[] stored = parseObservation(ctx.get("birdhouse." + space.varp));
			if (stored == null)
			{
				continue;
			}
			int value = (int) stored[0];
			long observedAt = stored[1];

			JsonObject house = new JsonObject();
			house.addProperty("space", space.name);
			house.addProperty("observedAt", Instant.ofEpochSecond(observedAt).toString());
			house.addProperty("observedSecondsAgo", ctx.now - observedAt);

			int index = (value - 1) / 3;
			if (value > 0 && index < typeCount)
			{
				house.addProperty("type", data.birdhouses.types.get(index));
			}

			String state;
			if (value < 0 || value > typeCount * 3)
			{
				state = "UNKNOWN";
			}
			else if (value == 0)
			{
				state = "EMPTY";
				needsSeeding++;
			}
			else if (value % 3 == 0)
			{
				state = "SEEDED";
				long doneAt = observedAt + data.birdhouses.durationSeconds;
				if (doneAt <= ctx.now)
				{
					house.addProperty("ready", true);
					ready++;
				}
				else
				{
					house.addProperty("ready", false);
					house.addProperty("readyAt", Instant.ofEpochSecond(doneAt).toString());
					house.addProperty("readyInSeconds", doneAt - ctx.now);
					soonest = Math.min(soonest, doneAt);
					filling++;
				}
			}
			else
			{
				// Built but not seeded: it will never fill on its own
				state = "BUILT";
				needsSeeding++;
			}
			house.addProperty("state", state);
			houses.add(house);
		}

		if (houses.size() == 0)
		{
			out.addProperty("available", false);
			out.addProperty("note", "No bird house observations stored — pass the Fossil Island houses once "
				+ "with Time Tracking enabled.");
			return out;
		}

		out.addProperty("available", true);
		JsonObject summary = new JsonObject();
		summary.addProperty("readyToDismantle", ready);
		summary.addProperty("filling", filling);
		summary.addProperty("needsSeeding", needsSeeding);
		if (soonest != Long.MAX_VALUE)
		{
			summary.addProperty("nextReadyAt", Instant.ofEpochSecond(soonest).toString());
			summary.addProperty("nextReadyInSeconds", soonest - ctx.now);
		}
		out.add("summary", summary);
		out.add("houses", houses);
		return out;
	}

	private static Set<String> upper(Collection<String> in)
	{
		Set<String> out = new LinkedHashSet<>();
		if (in != null)
		{
			for (String s : in)
			{
				if (s != null && !s.trim().isEmpty())
				{
					out.add(s.trim().toUpperCase(Locale.ROOT));
				}
			}
		}
		return out;
	}
}
