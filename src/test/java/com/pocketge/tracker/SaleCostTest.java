package com.pocketge.tracker;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * A sale is charged against the stack you bought last — among what was
 * bought before the offer was listed — never against older buys the plugin
 * did not see leave.
 *
 * Reported: 13,418 Aether catalysts bought at 277 and sold at 280 (275
 * after tax) booked -1.12M, charged at 359 each against 24,500 left over
 * from an earlier stack sold where the plugin did not see it. The real
 * result was -26,836.
 */
public class SaleCostTest
{
	private static final int AETHER = 30771;
	private static final int ITEM = 561;
	private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR;
	private static final long NOW = 1_000 * DAY;

	private static void bought(FlipTracker t, int slot, int item, long price, int qty, long at)
	{
		t.onOffer(at - 1, slot, item, "x", true, 0, 0L, price, qty, false);
		t.onOffer(at, slot, item, "x", true, qty, qty * price, price, qty, false);
		t.onOffer(at + 1, slot, 0, "", false, 0, 0L, 0, 0, true); // collected
	}

	private static FlipTracker userCase()
	{
		final FlipTracker t = new FlipTracker();
		bought(t, 0, AETHER, 359, 24_500, NOW - 5 * DAY); // sold later where the plugin did not see
		bought(t, 1, AETHER, 277, 13_418, NOW - 2 * HOUR);
		t.onOffer(NOW - 30 * MIN, 2, AETHER, "Aether catalyst", false, 0, 0L, 280, 13_418, false);
		return t;
	}

	private static Flip last(FlipTracker t)
	{
		final List<Flip> f = t.getFlips();
		return f.get(f.size() - 1);
	}

	@Test
	public void theUsersSaleIsChargedAtWhatTheyPaidForThisStack()
	{
		final FlipTracker t = userCase();
		t.onOffer(NOW, 2, AETHER, "Aether catalyst", false, 13_418, 13_418L * 280, 280, 13_418, false);
		final Flip f = last(t);
		Assert.assertEquals(13_418L * 277, f.buySpent);
		Assert.assertEquals(67_090L, f.tax);
		Assert.assertEquals(-26_836L, f.profit);
		Assert.assertEquals("held two hours, not five days", 2 * HOUR, f.holdMillis());
		Assert.assertArrayEquals("the leftovers are untouched", new long[]{24_500, 24_500L * 359},
			t.getOpenBuyTotal(AETHER));
	}

	/** The same sale, filled while logged out and booked at the next login. */
	@Test
	public void soAcrossARestartAndALoginReplay()
	{
		final FlipTracker before = userCase();
		final FlipTracker t = new FlipTracker();
		t.restore(before.snapshot());
		t.onOffer(NOW + 8 * HOUR, 2, AETHER, "Aether catalyst", false, 13_418, 13_418L * 280, 280, 13_418, false);
		Assert.assertEquals(-26_836L, last(t).profit);
	}

	/** Buying and selling one item in two slots: what the buy fills after
	 *  the sell was listed is in the buy's box, not in the sell. */
	@Test
	public void aBuyFilledAfterTheSellWasListedIsNotWhatItSold()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(NOW - 61 * MIN, 0, ITEM, "x", true, 0, 0L, 100, 3_000, false);
		t.onOffer(NOW - 60 * MIN, 0, ITEM, "x", true, 1_000, 100_000L, 100, 3_000, false);
		t.onOffer(NOW - 50 * MIN, 1, ITEM, "x", false, 0, 0L, 110, 1_000, false);       // listed
		t.onOffer(NOW - 40 * MIN, 0, ITEM, "x", true, 1_500, 150_000L, 100, 3_000, false); // after
		bought(t, 2, ITEM, 102, 500, NOW - 20 * MIN);                                       // after
		t.onOffer(NOW, 1, ITEM, "x", false, 1_000, 110_000L, 110, 1_000, false);
		final Flip f = last(t);
		Assert.assertEquals(100_000L, f.buySpent);
		Assert.assertEquals(60 * MIN, f.holdMillis());
	}

	/** ...and the bound is kept across a restart. */
	@Test
	public void theListingBoundSurvivesARestart()
	{
		final FlipTracker a = new FlipTracker();
		bought(a, 0, ITEM, 100, 1_000, NOW - 2 * HOUR);
		a.onOffer(NOW - HOUR, 1, ITEM, "x", false, 0, 0L, 110, 1_000, false);
		final FlipTracker t = new FlipTracker();
		t.restore(a.snapshot());
		bought(t, 0, ITEM, 130, 1_000, NOW - 30 * MIN); // after the listing, after the restart
		t.onOffer(NOW, 1, ITEM, "x", false, 1_000, 110_000L, 110, 1_000, false);
		Assert.assertEquals(100_000L, last(t).buySpent);
	}

	/** A save from before lots were numbered: everything on record may be in
	 *  a listed offer, and nothing bought after the restore can be. */
	@Test
	public void aSaveWithoutNumbersStillMatchesNewestFirst()
	{
		final FlipTracker.State s = new FlipTracker.State();
		s.flips = new java.util.ArrayList<>();
		s.openBuys = new java.util.HashMap<>();
		s.openBuys.put(ITEM, List.of(new long[]{1_000, 359_000L, NOW - 5 * DAY}, new long[]{1_000, 277_000L, NOW - HOUR}));
		s.slotsByAccount = new java.util.HashMap<>();
		final java.util.Map<Integer, long[]> rows = new java.util.HashMap<>();
		rows.put(2, new long[]{ITEM, 0, 0, 0, 280, 1_000, 7, 0, 0, 0, 0, 0}); // a listed sell, no bound
		s.slotsByAccount.put(0L, rows);
		s.slotsFormat = FlipTracker.SLOTS_FORMAT;
		s.nextOfferToken = 8;
		final FlipTracker t = new FlipTracker();
		t.restore(s);
		bought(t, 0, ITEM, 500, 1_000, NOW - MIN);
		t.onOffer(NOW, 2, ITEM, "x", false, 1_000, 280_000L, 280, 1_000, false);
		Assert.assertEquals(277_000L, last(t).buySpent);
	}

	/** A long-held stack keeps its own cost when the same item is flipped
	 *  on top of it: the flip is the 120s, held half an hour. */
	@Test
	public void aStackHeldForWeeksKeepsItsCostUnderAFlipOfTheSameItem()
	{
		final FlipTracker t = new FlipTracker();
		bought(t, 0, ITEM, 100, 5_000, NOW - 21 * DAY);
		bought(t, 1, ITEM, 120, 1_000, NOW - 30 * MIN);
		t.onOffer(NOW - 20 * MIN, 2, ITEM, "x", false, 0, 0L, 125, 1_000, false);
		t.onOffer(NOW, 2, ITEM, "x", false, 1_000, 125_000L, 125, 1_000, false);
		Assert.assertEquals(3_000L, last(t).profit);
		Assert.assertEquals(30 * MIN, last(t).holdMillis());
		Assert.assertArrayEquals(new long[]{5_000, 500_000L}, t.getOpenBuyTotal(ITEM));
	}

	/** Selling more than was bought since reaches the older lots, oldest of
	 *  them last, and the clock is the oldest it reached. */
	@Test
	public void aBiggerSaleReachesTheOlderLots()
	{
		final FlipTracker t = new FlipTracker();
		bought(t, 0, ITEM, 100, 1_000, NOW - 3 * HOUR);
		bought(t, 1, ITEM, 120, 1_000, NOW - HOUR);
		t.onOffer(NOW - 30 * MIN, 2, ITEM, "x", false, 0, 0L, 125, 1_500, false);
		t.onOffer(NOW, 2, ITEM, "x", false, 1_500, 187_500L, 125, 1_500, false);
		Assert.assertEquals(120_000L + 50_000L, last(t).buySpent);
		Assert.assertEquals(3 * HOUR, last(t).holdMillis());
		Assert.assertArrayEquals(new long[]{500, 50_000L}, t.getOpenBuyTotal(ITEM));
	}

	/** Lot numbers survive a save, so a forget prompt read before it still
	 *  drops exactly what it showed. */
	@Test
	public void lotNumbersSurviveASave()
	{
		final FlipTracker a = new FlipTracker();
		bought(a, 0, ITEM, 100, 1_000, NOW - HOUR);
		final long[] shown = a.getOpenBuyRecord(ITEM);
		final FlipTracker t = new FlipTracker();
		t.restore(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(a.snapshot()), FlipTracker.State.class));
		bought(t, 0, ITEM, 120, 1_000, NOW);
		Assert.assertArrayEquals(new long[]{1_000, 100_000L}, t.forgetOpenBuys(ITEM, shown[2]));
		Assert.assertArrayEquals(new long[]{1_000, 120_000L}, t.getOpenBuyTotal(ITEM));
	}
}
