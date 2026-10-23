package com.pocketge.tracker;

import com.google.gson.Gson;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * A sale is charged only to units that could be in it: this character's
 * buys, made before the offer was listed, and not waiting in a buy's
 * collection box at that moment. Newest first among those.
 */
public class SaleProvenanceTest
{
	private static final int AETHER = 30771, ITEM = 561;
	private static final long MIN = 60_000L, HOUR = 60 * MIN, DAY = 24 * HOUR, NOW = 1_000 * DAY;
	private static final long MAIN = 111L, ALT = 222L;
	private static final long TRUTH = 3_757_040L - 67_090L - 13_418L * 277; // -26,836

	private static void placeBuy(FlipTracker t, int slot, int item, long price, int qty, long at)
	{
		t.onOffer(at, slot, item, "x", true, 0, 0L, price, qty, false);
	}

	private static void fillBuy(FlipTracker t, int slot, int item, long price, int qty, int total, long at)
	{
		t.onOffer(at, slot, item, "x", true, qty, qty * price, price, total, false);
	}

	private static void collect(FlipTracker t, int slot, long at)
	{
		t.onOffer(at, slot, 0, "", false, 0, 0L, 0, 0, true);
	}

	private static void bought(FlipTracker t, int slot, int item, long price, int qty, long at)
	{
		placeBuy(t, slot, item, price, qty, at - 1);
		fillBuy(t, slot, item, price, qty, qty, at);
		collect(t, slot, at + 1);
	}

	private static void list(FlipTracker t, int slot, int item, long price, int qty, long at)
	{
		t.onOffer(at, slot, item, "x", false, 0, 0L, price, qty, false);
	}

	private static void sold(FlipTracker t, int slot, int item, long price, int qty, int total, long at)
	{
		t.onOffer(at, slot, item, "x", false, qty, (long) qty * price, price, total, false);
	}

	private static Flip last(FlipTracker t)
	{
		final List<Flip> f = t.getFlips();
		return f.isEmpty() ? null : f.get(f.size() - 1);
	}

	/** Holds 13,418@277; a 25,000@359 buy finishes into its box; the 13,418
	 *  are listed. FIFO got this right; plain newest-first booked -1.12M. */
	private static FlipTracker boxedAtListing(boolean boxSeen)
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		bought(t, 0, AETHER, 277, 13_418, NOW - 5 * HOUR);
		placeBuy(t, 1, AETHER, 359, 25_000, NOW - 3 * HOUR);
		fillBuy(t, 1, AETHER, 359, 25_000, 25_000, NOW - 2 * HOUR);
		if (boxSeen)
		{
			t.noteBox(1, AETHER, 25_000);
		}
		list(t, 2, AETHER, 280, 13_418, NOW - HOUR);
		return t;
	}

	@Test
	public void unitsWaitingInABuysBoxAreNotWhatWasListed()
	{
		for (boolean seen : new boolean[]{true, false})
		{
			final FlipTracker t = boxedAtListing(seen);
			sold(t, 2, AETHER, 280, 13_418, 13_418, NOW);
			Assert.assertEquals("box seen " + seen, TRUTH, last(t).profit);
			Assert.assertArrayEquals(new long[]{25_000, 25_000L * 359}, t.getOpenBuyTotal(AETHER));
		}
	}

	/** Collecting the buy after the listing does not put its units in it. */
	@Test
	public void collectingTheBuyAfterTheListingChangesNothing()
	{
		final FlipTracker t = boxedAtListing(true);
		collect(t, 1, NOW - 30 * MIN);
		sold(t, 2, AETHER, 280, 13_418, 13_418, NOW);
		Assert.assertEquals(TRUTH, last(t).profit);
	}

	/** A running buy with part of it in the box: only the boxed part is out. */
	@Test
	public void aRunningBuysBoxedPartIsOutItsCollectedPartIsIn()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		bought(t, 0, AETHER, 359, 24_500, NOW - 5 * DAY); // left unseen
		placeBuy(t, 1, AETHER, 277, 25_000, NOW - 3 * HOUR);
		fillBuy(t, 1, AETHER, 277, 13_418, 25_000, NOW - 2 * HOUR);
		t.noteBox(1, AETHER, 13_418);
		t.noteBox(1, AETHER, 0); // collected; the buy runs on
		list(t, 2, AETHER, 280, 13_418, NOW - HOUR);
		sold(t, 2, AETHER, 280, 13_418, 13_418, NOW);
		Assert.assertEquals(TRUTH, last(t).profit);
	}

	/** Login replay: a buy that finished offline into its box is read before
	 *  a sell listed and sold on mobile from the older stack. */
	@Test
	public void aReplayedFinishedBuyStillInItsBoxIsNotTheSale()
	{
		final FlipTracker a = new FlipTracker();
		a.setAccountHash(MAIN);
		bought(a, 0, AETHER, 277, 13_418, NOW - 10 * HOUR);
		placeBuy(a, 1, AETHER, 359, 25_000, NOW - 9 * HOUR);
		final FlipTracker t = new FlipTracker();
		t.restore(a.snapshot());
		t.setAccountHash(MAIN);
		fillBuy(t, 1, AETHER, 359, 25_000, 25_000, NOW);
		sold(t, 3, AETHER, 280, 13_418, 13_418, NOW);
		Assert.assertEquals(TRUTH, last(t).profit);
	}

	/** ...but a finished buy remembered from before the logout proves
	 *  nothing until its slot is seen again: collected on mobile and sold
	 *  there, its EMPTY waits out the login clear while the sale arrives. */
	@Test
	public void aBuyRememberedFromBeforeTheGapIsNotAssumedStillBoxed()
	{
		final FlipTracker a = new FlipTracker();
		a.setAccountHash(MAIN);
		bought(a, 0, AETHER, 359, 24_500, NOW - 5 * DAY); // left unseen
		placeBuy(a, 1, AETHER, 277, 25_000, NOW - 9 * HOUR);
		fillBuy(a, 1, AETHER, 277, 25_000, 25_000, NOW - 8 * HOUR);
		final FlipTracker t = new FlipTracker();
		t.restore(a.snapshot());
		t.setAccountHash(MAIN);
		sold(t, 4, AETHER, 300, 25_000, 25_000, NOW); // never-seen sell, in the replay
		collect(t, 1, NOW + 1);                        // the sweep, after the settle
		Assert.assertEquals(25_000L * 277, last(t).buySpent);
	}

	@Test
	public void aCharactersSaleIsChargedToItsOwnBuysInEitherOrder()
	{
		for (boolean altFirst : new boolean[]{true, false})
		{
			final FlipTracker t = new FlipTracker();
			t.setAccountHash(altFirst ? ALT : MAIN);
			bought(t, 0, AETHER, altFirst ? 277 : 359, altFirst ? 13_418 : 25_000, NOW - 5 * HOUR);
			t.setAccountHash(altFirst ? MAIN : ALT);
			bought(t, 0, AETHER, altFirst ? 359 : 277, altFirst ? 25_000 : 13_418, NOW - 3 * HOUR);
			t.setAccountHash(ALT);
			list(t, 1, AETHER, 280, 13_418, NOW - HOUR);
			sold(t, 1, AETHER, 280, 13_418, 13_418, NOW);
			Assert.assertEquals("alt first " + altFirst, TRUTH, last(t).profit);
			t.setAccountHash(MAIN);
			Assert.assertArrayEquals("main's stack untouched", new long[]{25_000, 25_000L * 359}, t.getOpenBuyTotal(AETHER));
		}
	}

	/** Bought on one character, sold on another: no cost on record for the
	 *  seller, so nothing is booked — unknown, never another's price. */
	@Test
	public void anotherCharactersBuysAreNeverCharged()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(ALT);
		bought(t, 0, ITEM, 100, 1_000, NOW - 2 * HOUR);
		t.setAccountHash(MAIN);
		list(t, 1, ITEM, 110, 1_000, NOW - HOUR);
		sold(t, 1, ITEM, 110, 1_000, 1_000, NOW);
		Assert.assertNull(last(t));
	}

	/** Lots from a save that did not record whose they were: any character. */
	@Test
	public void unattributedLotsFromAnOlderSaveServeAnyCharacter()
	{
		final FlipTracker.State s = new FlipTracker.State();
		s.flips = new java.util.ArrayList<>();
		s.openBuys = new HashMap<>();
		s.openBuys.put(ITEM, List.of(new long[]{1_000, 100_000L, NOW - DAY})); // 0.7.1 shape
		s.slotsFormat = FlipTracker.SLOTS_FORMAT;
		final FlipTracker t = new FlipTracker();
		t.restore(s);
		t.setAccountHash(ALT);
		list(t, 1, ITEM, 110, 1_000, NOW - HOUR);
		sold(t, 1, ITEM, 110, 1_000, 1_000, NOW);
		Assert.assertEquals(100_000L, last(t).buySpent);
	}

	/** What the slot's hover projects is what the sale then books. */
	@Test
	public void thePreviewIsTheBooking()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		bought(t, 0, ITEM, 100, 5_000, NOW - 21 * DAY);
		bought(t, 1, ITEM, 120, 1_000, NOW - 30 * MIN);
		list(t, 2, ITEM, 125, 1_000, NOW - 20 * MIN);
		final long[] p = t.previewSale(2);
		sold(t, 2, ITEM, 125, 1_000, 1_000, NOW);
		Assert.assertArrayEquals(new long[]{1_000, 120_000L}, p);
		Assert.assertEquals(p[1], last(t).buySpent);
		final FlipTracker u = boxedAtListing(true);
		final long[] q = u.previewSale(2);
		sold(u, 2, AETHER, 280, 13_418, 13_418, NOW);
		Assert.assertEquals(q[1], last(u).buySpent);
	}

	/** The card prices the stack you can list, not the box beside it. */
	@Test
	public void paidAtLeavesOutWhatIsInABox()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		bought(t, 0, AETHER, 277, 13_418, NOW - 5 * HOUR);
		placeBuy(t, 1, AETHER, 359, 25_000, NOW - 3 * HOUR);
		fillBuy(t, 1, AETHER, 359, 25_000, 25_000, NOW - 2 * HOUR);
		t.noteBox(1, AETHER, 25_000);
		final Map<Integer, Long> held = new HashMap<>();
		held.put(AETHER, 13_418L + 25_000L); // heldForCost counts the box
		Assert.assertArrayEquals(new long[]{13_418, 13_418L * 277, 0, 0}, t.getHeldCost(held).get(AETHER));
	}

	/** Owner, source and the listing's box snapshot survive a save. */
	@Test
	public void provenanceSurvivesASave()
	{
		final FlipTracker a = boxedAtListing(true);
		a.setAccountHash(ALT);
		bought(a, 0, AETHER, 300, 1_000, NOW - 90 * MIN);
		a.setAccountHash(MAIN);
		final Gson g = new Gson();
		final FlipTracker t = new FlipTracker();
		t.restore(g.fromJson(g.toJson(a.snapshot()), FlipTracker.State.class));
		t.setAccountHash(MAIN);
		collect(t, 1, NOW - 10 * MIN); // box state is not saved; the snapshot is
		sold(t, 2, AETHER, 280, 13_418, 13_418, NOW);
		Assert.assertEquals(TRUTH, last(t).profit);
	}

	/** A hold that reaches a lot with no fill time is unknown, as in merge —
	 *  even when that lot is the newer one: a receipt first seen today may
	 *  have been bought weeks ago. */
	@Test
	public void aHoldReachingAnUntimedLotIsUnknown()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		bought(t, 0, ITEM, 120, 1_000, NOW - HOUR);
		t.onOffer(NOW - 50 * MIN, 1, ITEM, "x", true, 1_000, 100_000L, 100, 1_000, false); // first sighting: untimed
		collect(t, 1, NOW - 50 * MIN + 1);
		list(t, 2, ITEM, 125, 2_000, NOW - 30 * MIN);
		sold(t, 2, ITEM, 125, 2_000, 2_000, NOW);
		Assert.assertEquals(-1L, last(t).holdMillis());
	}
}
