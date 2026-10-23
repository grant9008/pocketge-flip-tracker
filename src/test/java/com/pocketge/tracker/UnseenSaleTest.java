package com.pocketge.tracker;

import com.google.gson.Gson;
import org.junit.Assert;
import org.junit.Test;

/**
 * What a slot's sell has booked and not yet shown, kept on the slot's saved
 * record: "even if i missed it live, the next time i load into this ge
 * screen, it should have said +64kgp over rune darts in that square".
 */
public class UnseenSaleTest
{
	private static final int DART = 811;
	private static final long MAIN = 42L;
	private static final long ALT = 43L;

	/** Bought 7,132 at 190, then listed in slot 2 at 200: 196 after tax,
	 *  6 each in all. */
	private static FlipTracker withASell(long account)
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(account);
		t.onOffer(1_000, 0, DART, "Rune dart", true, 0, 0, 190, 7_132, false);
		t.onOffer(2_000, 0, DART, "Rune dart", true, 7_132, 7_132L * 190, 190, 7_132, false);
		t.onOffer(3_000, 0, 0, "", false, 0, 0, 0, 0, true);
		t.onOffer(4_000, 2, DART, "Rune dart", false, 0, 0, 200, 7_132, false);
		return t;
	}

	private static void sell(FlipTracker t, long when, int sold)
	{
		t.onOffer(when, 2, DART, "Rune dart", false, sold, sold * 200L, 200, 7_132, false);
	}

	private static FlipTracker restarted(FlipTracker t, long account)
	{
		final Gson gson = new Gson();
		final FlipTracker after = new FlipTracker();
		after.restore(gson.fromJson(gson.toJson(t.snapshot()), FlipTracker.State.class));
		after.setAccountHash(account);
		return after;
	}

	@Test
	public void whatSoldWaitsUntilShownThenOnlyWhatIsNew()
	{
		final FlipTracker t = withASell(MAIN);
		Assert.assertNull("nothing sold yet", t.unseenBy(2, DART));
		sell(t, 5_000, 3_000);
		sell(t, 6_000, 7_132);
		Assert.assertArrayEquals("the whole sale, every batch", new long[]{7_132, 7_132L * 6}, t.unseenBy(2, DART));
		t.markShown(2);
		Assert.assertNull("shown", t.unseenBy(2, DART));
		Assert.assertTrue("and to be saved", t.takeSlotsDirty());
	}

	@Test
	public void onlyForTheSellOfThatItem()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 7_132);
		Assert.assertNull("another item", t.unseenBy(2, 565));
		Assert.assertNull("an empty slot", t.unseenBy(5, DART));
		Assert.assertNull("the buy slot's record, emptied", t.unseenBy(0, DART));
	}

	/** Sold with the Exchange shut, then RuneLite restarted — overnight, or
	 *  its weekly update — before the Exchange was opened. */
	@Test
	public void survivesARestart()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 7_132);
		final FlipTracker after = restarted(t, MAIN);
		Assert.assertArrayEquals(new long[]{7_132, 7_132L * 6}, after.unseenBy(2, DART));
		after.markShown(2);
		Assert.assertNull("and once shown, not again after the next", restarted(after, MAIN).unseenBy(2, DART));
	}

	/** Collected on mobile and the same item relisted in the slot on other
	 *  terms: a new offer, with nothing of its own to show. The old figure
	 *  used to land on it. */
	@Test
	public void aRelistOnOtherTermsStartsAtNothing()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 7_132);
		final FlipTracker after = restarted(t, MAIN);
		after.markSlotsUnwatched();
		after.onOffer(9_000, 2, DART, "Rune dart", false, 0, 0, 205, 5_000, false);
		Assert.assertNull(after.unseenBy(2, DART));
	}

	/** Switched off: every character's waiting sale counts as shown. */
	@Test
	public void markAllShownCoversEveryCharacter()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 3_000);
		t.setAccountHash(ALT);
		t.onOffer(6_000, 0, DART, "Rune dart", true, 0, 0, 190, 100, false);
		t.onOffer(6_100, 0, DART, "Rune dart", true, 100, 100L * 190, 190, 100, false);
		t.onOffer(6_200, 0, 0, "", false, 0, 0, 0, 0, true);
		t.onOffer(6_300, 4, DART, "Rune dart", false, 0, 0, 200, 100, false);
		t.onOffer(6_400, 4, DART, "Rune dart", false, 100, 100L * 200, 200, 100, false);
		Assert.assertNotNull(t.unseenBy(4, DART));
		t.takeSlotsDirty();
		t.markAllShown();
		Assert.assertTrue(t.takeSlotsDirty());
		Assert.assertNull(t.unseenBy(4, DART));
		t.setAccountHash(MAIN);
		Assert.assertNull(t.unseenBy(2, DART));
	}

	/** One character's figures are not another's. */
	@Test
	public void belongsToItsCharacter()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 7_132);
		t.setAccountHash(ALT);
		Assert.assertNull(t.unseenBy(2, DART));
		t.setAccountHash(MAIN);
		Assert.assertNotNull(t.unseenBy(2, DART));
	}

	/** A save from before figures existed: what it booked counts as shown,
	 *  so an upgrade does not pop every sale in progress. */
	@Test
	public void anUpgradePopsNothingOld()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 3_000);
		final FlipTracker.State saved = t.snapshot();
		for (java.util.Map<Integer, long[]> rows : saved.slotsByAccount.values())
		{
			rows.replaceAll((slot, row) -> java.util.Arrays.copyOf(row, 10));
		}
		final Gson gson = new Gson();
		final FlipTracker after = new FlipTracker();
		after.restore(gson.fromJson(gson.toJson(saved), FlipTracker.State.class));
		after.setAccountHash(MAIN);
		Assert.assertNotNull("booked kept", after.bookedBy(2));
		Assert.assertNull(after.unseenBy(2, DART));
		sell(after, 9_000, 4_000);
		Assert.assertArrayEquals("what sells after the upgrade does show", new long[]{1_000, 6_000}, after.unseenBy(2, DART));
	}

	/** Older still: rows with no booked totals, seeded from the flips. */
	@Test
	public void anOlderSaveSeededFromFlipsPopsNothingOld()
	{
		final FlipTracker t = withASell(MAIN);
		sell(t, 5_000, 3_000);
		final FlipTracker.State saved = t.snapshot();
		for (java.util.Map<Integer, long[]> rows : saved.slotsByAccount.values())
		{
			rows.replaceAll((slot, row) -> java.util.Arrays.copyOf(row, 8));
		}
		final Gson gson = new Gson();
		final FlipTracker after = new FlipTracker();
		after.restore(gson.fromJson(gson.toJson(saved), FlipTracker.State.class));
		after.setAccountHash(MAIN);
		Assert.assertArrayEquals(new long[]{3_000, 18_000}, after.bookedBy(2));
		Assert.assertNull(after.unseenBy(2, DART));
	}
}
