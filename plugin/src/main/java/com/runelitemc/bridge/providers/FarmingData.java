package com.runelitemc.bridge.providers;

import com.google.gson.Gson;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * The farming tables lifted out of RuneLite's own Time Tracking plugin.
 *
 * RuneLite knows every patch in the game and how each patch's varbit encodes
 * produce, crop state and growth stage — but FarmingWorld, FarmingPatch,
 * PatchImplementation and Produce are all package-private, so no plugin outside
 * that package can name them, and reflection is off the table (see README). The
 * data itself is ordinary, so it is extracted from RuneLite's source into
 * /farming/farming-data.json by scripts/gen-farming-data.mjs and read back here.
 *
 * Regenerate that file when RuneLite adds patches or produce; the generator is
 * pinned to a RuneLite tag and records it in {@link #source}.
 *
 * Derived from RuneLite (BSD-2-Clause) — see NOTICE.
 */
public final class FarmingData
{
	private static final String RESOURCE = "/farming/farming-data.json";

	public String source;
	public String generatedAt;
	/** Tab constant name -> user-visible name ("HERB" -> "Herb Patches"). */
	public Map<String, String> tabs;
	public Map<String, Produce> produce;
	public Map<String, Implementation> implementations;
	public List<Region> regions;
	public BirdHouses birdhouses;

	public static final class Produce
	{
		public String name;
		/** Value of FARMGUILD_CONTRACT_TYPE that asks for this crop; -1 when it is never contracted. */
		public int contractVarbitValue;
		/** Minutes per growth tick. */
		public int tickrate;
		/** Growth states, typically tick count + 1. */
		public int stages;
		/** Minutes per regrow tick, or 0 when the crop does not regrow. */
		public int regrowTickrate;
		/** Harvest states ("lives"). */
		public int harvestStages;
	}

	public static final class Implementation
	{
		public String tab;
		public String contractName;
		public boolean healthCheckRequired;
		public List<Range> ranges;
	}

	/**
	 * One branch of RuneLite's forVarbitValue: varbit values in [lo, hi] mean
	 * this produce in this crop state, at stage {@code a + b * value}.
	 */
	public static final class Range
	{
		public int lo;
		public int hi;
		public String produce;
		public String state;
		public int a;
		public int b;
	}

	public static final class Region
	{
		public String name;
		public int regionId;
		public boolean definite;
		public List<Patch> patches;
	}

	public static final class Patch
	{
		/** Disambiguator within a region ("North"), empty when the region has one of this type. */
		public String name;
		public int varbit;
		public String implementation;
	}

	public static final class BirdHouses
	{
		public int durationSeconds;
		/** Indexed by (varp - 1) / 3. */
		public List<String> types;
		public List<Space> spaces;
	}

	public static final class Space
	{
		public String name;
		public int varp;
	}

	public static FarmingData load()
	{
		try (InputStream in = FarmingData.class.getResourceAsStream(RESOURCE))
		{
			if (in == null)
			{
				throw new IllegalStateException("missing resource " + RESOURCE
					+ " (run: node scripts/gen-farming-data.mjs)");
			}
			try (Reader reader = new InputStreamReader(in, StandardCharsets.UTF_8))
			{
				FarmingData data = new Gson().fromJson(reader, FarmingData.class);
				data.validate();
				return data;
			}
		}
		catch (IOException e)
		{
			throw new IllegalStateException("could not read " + RESOURCE, e);
		}
	}

	/**
	 * A generated file that parsed but does not hang together would fail one
	 * patch at a time at request time; fail at startup instead.
	 */
	void validate()
	{
		if (produce == null || implementations == null || regions == null || birdhouses == null)
		{
			throw new IllegalStateException(RESOURCE + " is missing a top-level table");
		}
		for (Map.Entry<String, Implementation> e : implementations.entrySet())
		{
			for (Range range : e.getValue().ranges)
			{
				if (!produce.containsKey(range.produce))
				{
					throw new IllegalStateException(RESOURCE + ": implementation " + e.getKey()
						+ " references unknown produce " + range.produce);
				}
			}
		}
		for (Region region : regions)
		{
			for (Patch patch : region.patches)
			{
				if (!implementations.containsKey(patch.implementation))
				{
					throw new IllegalStateException(RESOURCE + ": region " + region.name
						+ " references unknown implementation " + patch.implementation);
				}
			}
		}
	}

	/** The config key RuneLite stores a patch's observation under. */
	public static String configKey(Region region, Patch patch)
	{
		return region.regionId + "." + patch.varbit;
	}
}
