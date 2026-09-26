package com.runelitemc.bridge.providers;

import java.util.HashSet;
import java.util.Set;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

/**
 * The farming tables are generated from RuneLite's source rather than written
 * by hand (scripts/gen-farming-data.mjs), so what needs guarding is not the
 * arithmetic of any one patch but that the generator produced something whole
 * and that the prediction port still agrees with the table it reads.
 *
 * The spot checks below are read straight off RuneLite's PatchImplementation:
 * if a regenerate silently drops or shifts a range, one of them stops matching.
 */
public class FarmingDataTest
{
	private static final FarmingData DATA = FarmingData.load();

	@Test
	public void theGeneratedTablesAreWhole()
	{
		assertTrue("no produce", DATA.produce.size() > 80);
		assertTrue("no implementations", DATA.implementations.size() > 20);
		assertTrue("no regions", DATA.regions.size() > 30);
		assertNotNull("no bird house table", DATA.birdhouses);
		assertEquals("bird house spaces", 4, DATA.birdhouses.spaces.size());
		assertEquals("bird house cycle", 50 * 60, DATA.birdhouses.durationSeconds);
		assertTrue("the source tag is not recorded", DATA.source != null && DATA.source.contains("runelite"));
	}

	/** load() validates cross-references; this states what that means. */
	@Test
	public void everyReferenceResolves()
	{
		for (FarmingData.Implementation impl : DATA.implementations.values())
		{
			assertTrue("implementation with no ranges", !impl.ranges.isEmpty());
			for (FarmingData.Range range : impl.ranges)
			{
				assertTrue(range.produce, DATA.produce.containsKey(range.produce));
				assertTrue(range.lo + ">" + range.hi, range.lo <= range.hi);
			}
		}
		for (FarmingData.Region region : DATA.regions)
		{
			for (FarmingData.Patch patch : region.patches)
			{
				assertTrue(patch.implementation, DATA.implementations.containsKey(patch.implementation));
			}
		}
	}

	/** Two patches sharing a config key would silently shadow each other. */
	@Test
	public void configKeysAreUnique()
	{
		Set<String> seen = new HashSet<>();
		for (FarmingData.Region region : DATA.regions)
		{
			for (FarmingData.Patch patch : region.patches)
			{
				String key = FarmingData.configKey(region, patch);
				assertTrue("duplicate config key " + key + " in " + region.name, seen.add(key));
			}
		}
	}

	@Test
	public void herbVarbitValuesDecodeAsRuneLiteDoes()
	{
		assertDecodes("HERB", 0, "Weeds", "GROWING", 3);
		assertDecodes("HERB", 4, "Guam", "GROWING", 0);
		assertDecodes("HERB", 7, "Guam", "GROWING", 3);
		assertDecodes("HERB", 10, "Guam", "HARVESTABLE", 0);
		assertDecodes("HERB", 11, "Marrentill", "GROWING", 0);
	}

	@Test
	public void otherImplementationsDecodeToo()
	{
		assertDecodes("MUSHROOM", 16, "Mushroom", "DISEASED", 1);
		assertDecodes("MUSHROOM", 21, "Mushroom", "DEAD", 1);
		assertDecodes("TREE", 0, "Weeds", "GROWING", 3);
		assertDecodes("HESPORI", 4, "Hespori", "GROWING", 0);
	}

	/** A value past every range must decode to nothing, not to the last match. */
	@Test
	public void anUncoveredValueDecodesToNull()
	{
		assertNull(FarmingProvider.rangeFor(DATA.implementations.get("HERB"), 100_000));
	}

	@Test
	public void theCatherbyHerbPatchIsWhereItShouldBe()
	{
		FarmingData.Region catherby = DATA.regions.stream()
			.filter(r -> r.name.equals("Catherby") && r.regionId == 11062)
			.findFirst()
			.orElseThrow(() -> new AssertionError("no Catherby region 11062"));

		FarmingData.Patch herb = catherby.patches.stream()
			.filter(p -> p.implementation.equals("HERB"))
			.findFirst()
			.orElseThrow(() -> new AssertionError("Catherby has no herb patch"));

		assertEquals("11062." + herb.varbit, FarmingData.configKey(catherby, herb));
	}

	@Test
	public void observationsParseAndMalformedOnesDoNot()
	{
		assertEquals(4, FarmingProvider.parseObservation("4:1600000000")[0]);
		assertEquals(1600000000L, FarmingProvider.parseObservation("4:1600000000")[1]);
		assertNull(FarmingProvider.parseObservation(null));
		assertNull(FarmingProvider.parseObservation(""));
		assertNull(FarmingProvider.parseObservation("4"));
		assertNull(FarmingProvider.parseObservation("four:1600000000"));
		// A zero timestamp is RuneLite's "never observed", not the epoch
		assertNull(FarmingProvider.parseObservation("4:0"));
	}

	/**
	 * Growth ticks are wall-clock: a 5-minute crop ticks on every 5-minute
	 * boundary, and the learned offset shifts those boundaries backwards.
	 */
	@Test
	public void growthTicksLandOnWallClockBoundaries()
	{
		long noon = 1600000200L; // a multiple of 300
		assertEquals(noon, FarmingProvider.tickTime(null, null, 5, 0, noon));
		assertEquals(noon, FarmingProvider.tickTime(null, null, 5, 0, noon + 299));
		assertEquals(noon + 300, FarmingProvider.tickTime(null, null, 5, 1, noon));
		assertEquals(noon + 1200, FarmingProvider.tickTime(null, null, 5, 4, noon));

		// Offsets are stored positive but mean "ticks land this many minutes early"
		assertEquals(noon - 120, FarmingProvider.tickTime(40, 2, 5, 0, noon));
		// ... and are ignored when the offset was learned at a coarser precision
		// than the tick being asked about
		assertEquals(FarmingProvider.tickTime(null, null, 20, 0, noon),
			FarmingProvider.tickTime(5, 2, 20, 0, noon));
	}

	private static void assertDecodes(String impl, int value, String produce, String state, int stage)
	{
		FarmingData.Range range = FarmingProvider.rangeFor(DATA.implementations.get(impl), value);
		assertNotNull(impl + " " + value + " decoded to nothing", range);
		String where = impl + " " + value;
		assertEquals(where, produce, DATA.produce.get(range.produce).name);
		assertEquals(where, state, range.state);
		assertEquals(where, stage, range.a + range.b * value);
	}
}
