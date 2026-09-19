package com.runelitemc.bridge.providers;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import org.junit.Test;

public class ProgressProviderTest
{
	/**
	 * Regression: the threshold was once {@code >= 2} for every diary, which
	 * reported all eleven finished standard tiers as incomplete.
	 */
	@Test
	public void standardDiaryTierIsCompleteAtOne()
	{
		assertFalse(ProgressProvider.diaryComplete(0, 1));
		assertTrue(ProgressProvider.diaryComplete(1, 1));
	}

	/**
	 * Regression: a global {@code >= 1} then over-reported Karamja medium (1, with
	 * 8 tasks done) and hard (1, with 3) as finished. On the legacy ATJUN varbits
	 * 1 is in progress and 2 is complete.
	 */
	@Test
	public void karamjaLegacyTierNeedsTwo()
	{
		assertFalse(ProgressProvider.diaryComplete(1, 2));
		assertTrue(ProgressProvider.diaryComplete(2, 2));
	}
}
