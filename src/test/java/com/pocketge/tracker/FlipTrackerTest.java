package com.pocketge.tracker;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

public class FlipTrackerTest
{
	@Test
	public void firstSnapshotIsBaselineNotFill()
	{
		FlipTracker t = new FlipTracker();
		// login replay: an offer already half-filled must NOT count as a fill
		TradeFill f = t.onOffer(1L, 0, 1601, "Diamond", true, 500, 1_000_000L, false);
		Assert.assertNull(f);
		Assert.assertTrue(t.getFills().isEmpty());
	}

	@Test
	public void witnessedGrowthBecomesFill()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, false);          // fresh offer placed
		TradeFill f = t.onOffer(2L, 0, 1601, "Diamond", true, 100, 180_000L, false);
		Assert.assertNotNull(f);
		Assert.assertEquals(100, f.quantity);
		Assert.assertEquals(180_000L, f.spent);
		Assert.assertEquals(1800L, f.unitPrice());
	}

	@Test
	public void fifoFlipProfitAfterTax()
	{
		FlipTracker t = new FlipTracker();
		// buy 100 @ 1,800
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(2L, 0, 1601, "Diamond", true, 100, 180_000L, false);
		t.onOffer(3L, 0, 1601, "Diamond", true, 100, 180_000L, true);   // collected
		// sell 100 @ 2,000
		t.onOffer(4L, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(5L, 1, 1601, "Diamond", false, 100, 200_000L, false);

		List<Flip> flips = t.getFlips();
		Assert.assertEquals(1, flips.size());
		Flip flip = flips.get(0);
		Assert.assertEquals(100, flip.quantity);
		Assert.assertEquals(180_000L, flip.buySpent);
		Assert.assertEquals(200_000L, flip.sellGross);
		// tax: floor(2000/50) = 40 gp/item -> 4,000 total
		Assert.assertEquals(4_000L, flip.tax);
		Assert.assertEquals(16_000L, flip.profit);
		Assert.assertEquals(16_000L, t.getSessionProfit());
	}

	@Test
	public void partialSellMatchesPartialLot()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(2L, 0, 1601, "Diamond", true, 100, 180_000L, false);
		t.onOffer(3L, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(4L, 1, 1601, "Diamond", false, 40, 80_000L, false);   // sell 40 @ 2,000

		Flip flip = t.getFlips().get(0);
		Assert.assertEquals(40, flip.quantity);
		Assert.assertEquals(72_000L, flip.buySpent);                     // 40% of the lot
		Assert.assertEquals(80_000L, flip.sellGross);
	}

	@Test
	public void lowValueAndExemptItemsPayNoTax()
	{
		Assert.assertEquals(0L, FlipTracker.taxPerItem(49, 1601));       // under 50 gp
		Assert.assertEquals(0L, FlipTracker.taxPerItem(5_000_000, 13190)); // bond exempt
		Assert.assertEquals(1L, FlipTracker.taxPerItem(50, 1601));
		Assert.assertEquals(5_000_000L, FlipTracker.taxPerItem(2_000_000_000L, 1601)); // cap
	}

	@Test
	public void sellWithoutSeenBuyProducesNoFlip()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(2L, 0, 1601, "Diamond", false, 10, 20_000L, false);
		Assert.assertTrue(t.getFlips().isEmpty());
		Assert.assertEquals(1, t.getFills().size());                      // fill still recorded
	}

	/**
	 * The ledger's whole reason for existing: it is told about a flip when the
	 * flip is booked, so it keeps the ones the 500-flip in-memory window later
	 * drops. If the sink only ever saw what getFlips() still holds, a lifetime
	 * history would be capped at 500 all over again.
	 */
	@Test
	public void theFlipSinkSeesEveryFlipIncludingOnesTheWindowEvicts()
	{
		FlipTracker t = new FlipTracker();
		List<Flip> sunk = new java.util.ArrayList<>();
		t.setFlipSink(sunk::add);

		long time = 1L;
		for (int i = 0; i < 600; i++)
		{
			// buy 1 @ 1,000, then sell 1 @ 2,000 — one flip per iteration
			t.onOffer(time++, 0, 1601, "Diamond", true, 0, 0L, false);
			t.onOffer(time++, 0, 1601, "Diamond", true, 1, 1_000L, false);
			t.onOffer(time++, 0, 1601, "Diamond", true, 1, 1_000L, true);
			t.onOffer(time++, 1, 1601, "Diamond", false, 0, 0L, false);
			t.onOffer(time++, 1, 1601, "Diamond", false, 1, 2_000L, false);
			t.onOffer(time++, 1, 1601, "Diamond", false, 1, 2_000L, true);
		}

		Assert.assertEquals("the in-memory window is still capped", 500, t.getFlips().size());
		Assert.assertEquals("but the sink saw all of them", 600, sunk.size());
	}

	/** A ledger that cannot be written must not stop a flip being booked. */
	@Test
	public void aThrowingFlipSinkDoesNotBreakBooking()
	{
		FlipTracker t = new FlipTracker();
		t.setFlipSink(f -> { throw new RuntimeException("disk full"); });

		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(2L, 0, 1601, "Diamond", true, 100, 180_000L, false);
		t.onOffer(3L, 0, 1601, "Diamond", true, 100, 180_000L, true);
		t.onOffer(4L, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(5L, 1, 1601, "Diamond", false, 100, 200_000L, false);
		t.onOffer(6L, 1, 1601, "Diamond", false, 100, 200_000L, true);

		Assert.assertEquals(1, t.getFlips().size());
		Assert.assertTrue("profit still counted", t.getLifetimeProfit() > 0);
	}

	/**
	 * Hold time: how long the gold was actually tied up.
	 *
	 * Slot time is the scarce resource — three slots on a free world — so
	 * "+91K" is a different trade at eleven minutes than at three days, and
	 * until now a Flip carried only the moment it CLOSED.
	 */
	@Test
	public void aFlipRecordsHowLongTheGoldWasTiedUp()
	{
		FlipTracker t = new FlipTracker();
		long bought = 1_000_000L;
		long sold = bought + 29 * 60_000L;   // 29 minutes later

		t.onOffer(bought - 1, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(bought, 0, 1601, "Diamond", true, 100, 180_000L, false);
		t.onOffer(bought + 1, 0, 1601, "Diamond", true, 100, 180_000L, true);
		t.onOffer(sold - 1, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(sold, 1, 1601, "Diamond", false, 100, 200_000L, false);

		Flip f = t.getFlips().get(0);
		Assert.assertEquals(bought, f.openedAt);
		Assert.assertEquals(sold, f.closedAt);
		Assert.assertEquals(29 * 60_000L, f.holdMillis());
	}

	/** FIFO decides which lots a sell closes, and the clock has to follow the
	 *  same lots the cost does — otherwise "held 40 minutes at 20.1M" would be
	 *  describing two different sets of units. */
	@Test
	public void holdTimeFollowsTheSameLotsTheCostDoes()
	{
		FlipTracker t = new FlipTracker();
		long old = 1_000_000L;
		long recent = old + 60 * 60_000L;    // an hour after the first buy

		// two buy lots, an hour apart, 100 each
		t.onOffer(old - 1, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(old, 0, 1601, "Diamond", true, 100, 100_000L, false);
		t.onOffer(old + 1, 0, 1601, "Diamond", true, 100, 100_000L, true);
		t.onOffer(recent - 1, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(recent, 0, 1601, "Diamond", true, 100, 120_000L, false);
		t.onOffer(recent + 1, 0, 1601, "Diamond", true, 100, 120_000L, true);

		// sell 100 — FIFO closes the OLDER lot
		long sold = recent + 10 * 60_000L;
		t.onOffer(sold - 1, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(sold, 1, 1601, "Diamond", false, 100, 200_000L, false);

		Flip f = t.getFlips().get(0);
		Assert.assertEquals("cost came from the older lot", 100_000L, f.buySpent);
		Assert.assertEquals("so the clock must too", old, f.openedAt);
	}

	/** A stack the tracker never watched being bought closes no flip at all,
	 *  so there is nothing to mis-time. */
	@Test
	public void aSellWithNoTrackedBuyBooksNoFlip()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(2L, 1, 1601, "Diamond", false, 100, 200_000L, false);
		Assert.assertTrue(t.getFlips().isEmpty());
	}

	/** The migration case. A save written before lots carried a time restores
	 *  as [qty, spent]; those flips must report an UNKNOWN hold, never a zero
	 *  one, because zero reads as "filled instantly" — the most flattering
	 *  possible lie about a trade. */
	@Test
	public void aLotRestoredFromAnOlderSaveReportsAnUnknownHoldNotAZeroOne()
	{
		FlipTracker t = new FlipTracker();
		FlipTracker.State s = new FlipTracker.State();
		s.lifetimeProfit = 0;
		s.flips = new java.util.ArrayList<>();
		s.openBuys = new java.util.HashMap<>();
		// length 2: the old shape, with no fill time
		s.openBuys.put(1601, List.of(new long[]{100, 180_000L}));
		t.restore(s);

		t.onOffer(9_000_000L, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(9_000_001L, 1, 1601, "Diamond", false, 100, 200_000L, false);

		Flip f = t.getFlips().get(0);
		Assert.assertEquals("the flip still books, with its real cost", 180_000L, f.buySpent);
		Assert.assertEquals("but the buy time is unknown", 0L, f.openedAt);
		Assert.assertEquals("and that must read as nothing, not as instant", -1L, f.holdMillis());
		Assert.assertEquals("gp/hr cannot be computed from it either", 0L, f.profitPerHour());
	}

	/** A save written now carries the time, and a round trip keeps it. */
	@Test
	public void anOpenLotKeepsItsBuyTimeAcrossASaveAndReload()
	{
		FlipTracker a = new FlipTracker();
		long bought = 5_000_000L;
		a.onOffer(bought - 1, 0, 1601, "Diamond", true, 0, 0L, false);
		a.onOffer(bought, 0, 1601, "Diamond", true, 100, 180_000L, false);

		FlipTracker b = new FlipTracker();
		b.restore(a.snapshot());

		long sold = bought + 45 * 60_000L;
		b.onOffer(sold - 1, 1, 1601, "Diamond", false, 0, 0L, false);
		b.onOffer(sold, 1, 1601, "Diamond", false, 100, 200_000L, false);

		Flip f = b.getFlips().get(0);
		Assert.assertEquals(bought, f.openedAt);
		Assert.assertEquals(45 * 60_000L, f.holdMillis());
	}

	/** Selling half a lot must not disturb when the other half was bought. */
	@Test
	public void partiallyConsumingALotLeavesItsBuyTimeAlone()
	{
		FlipTracker t = new FlipTracker();
		long bought = 2_000_000L;
		t.onOffer(bought - 1, 0, 1601, "Diamond", true, 0, 0L, false);
		t.onOffer(bought, 0, 1601, "Diamond", true, 200, 360_000L, false);
		t.onOffer(bought + 1, 0, 1601, "Diamond", true, 200, 360_000L, true);

		long firstSell = bought + 10 * 60_000L;
		t.onOffer(firstSell - 1, 1, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(firstSell, 1, 1601, "Diamond", false, 100, 200_000L, false);
		t.onOffer(firstSell + 1, 1, 1601, "Diamond", false, 100, 200_000L, true);

		long secondSell = bought + 90 * 60_000L;
		t.onOffer(secondSell - 1, 2, 1601, "Diamond", false, 0, 0L, false);
		t.onOffer(secondSell, 2, 1601, "Diamond", false, 100, 210_000L, false);

		List<Flip> flips = t.getFlips();
		Assert.assertEquals(2, flips.size());
		Assert.assertEquals(bought, flips.get(0).openedAt);
		Assert.assertEquals("the remainder was still bought at the same moment",
			bought, flips.get(1).openedAt);
		Assert.assertEquals(10 * 60_000L, flips.get(0).holdMillis());
		Assert.assertEquals(90 * 60_000L, flips.get(1).holdMillis());
	}

	/** gp per hour of slot time — the number that says whether an item is
	 *  worth a slot, rather than merely profitable. */
	@Test
	public void profitPerHourDividesByTheHoldTime()
	{
		// +20,000 profit held exactly two hours -> 10,000 gp/hr.
		// openedAt must be non-zero: zero is reserved for "not known".
		Flip f = new Flip(1_000L, 1_000L + 7_200_000L, 1601, "Diamond", 100, 180_000L, 204_082L, 4_082L);
		Assert.assertEquals(7_200_000L, f.holdMillis());
		Assert.assertEquals(20_000L, f.profit);
		Assert.assertEquals(10_000L, f.profitPerHour());
	}

	/** A flip that closed in under a minute would divide into a nonsense
	 *  hourly rate, so it declines to. */
	@Test
	public void profitPerHourRefusesToExtrapolateFromSeconds()
	{
		Flip f = new Flip(1_000L, 6_000L, 1601, "Diamond", 1, 100L, 200L, 4L);
		Assert.assertTrue(f.holdMillis() > 0);
		Assert.assertEquals(0L, f.profitPerHour());
	}

	/** Restarts the client: snapshot out of one tracker, into a fresh one,
	 *  exactly as the plugin does across a session. */
	private static FlipTracker restart(FlipTracker t)
	{
		FlipTracker next = new FlipTracker();
		next.restore(t.snapshot());
		return next;
	}

	/**
	 * The whole point of persisting slot baselines: offers keep filling while
	 * you are logged out, and the fills that happen then used to be thrown
	 * away. A slot the tracker remembers is a slot it can measure against.
	 */
	@Test
	public void fillsThatHappenWhileLoggedOutAreCounted()
	{
		FlipTracker t = new FlipTracker();
		t.setAccountHash(4242L);
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, 1_800L, 1_000, false);   // placed
		t.onOffer(2L, 0, 1601, "Diamond", true, 100, 180_000L, 1_800L, 1_000, false);

		// ... log out, close the client, come back tomorrow: the same offer
		// has filled another 400.
		FlipTracker t2 = restart(t);
		t2.setAccountHash(4242L);
		TradeFill f = t2.onOffer(99L, 0, 1601, "Diamond", true, 500, 900_000L, 1_800L, 1_000, false);

		Assert.assertNotNull("the overnight fill has to count", f);
		Assert.assertEquals(400, f.quantity);
		Assert.assertEquals(720_000L, f.spent);
		Assert.assertEquals(500L, t2.getOpenBuyTotals().get(1601)[0]);
		Assert.assertEquals(900_000L, t2.getOpenBuyTotals().get(1601)[1]);
	}

	/**
	 * That lot's cost is exact but its clock is not: nobody watched it fill.
	 * A flip closed against it reports an unknown hold rather than one
	 * measured from the moment the client happened to reopen.
	 */
	@Test
	public void anOfflineFillHasNoKnownBuyTime()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, 1_800L, 100, false);
		FlipTracker t2 = restart(t);
		t2.onOffer(1_000L, 0, 1601, "Diamond", true, 100, 180_000L, 1_800L, 100, false);
		t2.onOffer(2_000L, 0, 1601, "Diamond", true, 100, 180_000L, 1_800L, 100, true);

		t2.onOffer(3_000L, 1, 1601, "Diamond", false, 0, 0L, 2_000L, 100, false);
		t2.onOffer(4_000L + 7_200_000L, 1, 1601, "Diamond", false, 100, 200_000L, 2_000L, 100, false);

		List<Flip> flips = t2.getFlips();
		Assert.assertEquals(1, flips.size());
		Assert.assertEquals("cost is known exactly", 180_000L, flips.get(0).buySpent);
		Assert.assertEquals("when it was bought is not", -1L, flips.get(0).holdMillis());
	}

	/** A live fill still carries its real time — the unknown above is for
	 *  offline growth only, not for every restored slot forever. */
	@Test
	public void theSlotGoesBackToLiveTimingAfterOneEvent()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, 1_800L, 200, false);
		FlipTracker t2 = restart(t);
		t2.onOffer(1_000L, 0, 1601, "Diamond", true, 100, 180_000L, 1_800L, 200, false); // offline
		t2.onOffer(5_000L, 0, 1601, "Diamond", true, 200, 360_000L, 1_800L, 200, false); // live
		t2.onOffer(6_000L, 0, 1601, "Diamond", true, 200, 360_000L, 1_800L, 200, true);

		t2.onOffer(7_000L, 1, 1601, "Diamond", false, 0, 0L, 2_000L, 200, false);
		// Sell only the second lot's worth, so FIFO reaches the live one after
		// the offline one is consumed.
		t2.onOffer(8_000L, 1, 1601, "Diamond", false, 100, 200_000L, 2_000L, 200, false);
		t2.onOffer(5_000L + 3_600_000L, 1, 1601, "Diamond", false, 200, 400_000L, 2_000L, 200, false);

		List<Flip> flips = t2.getFlips();
		Assert.assertEquals(2, flips.size());
		Assert.assertEquals("offline lot", -1L, flips.get(0).holdMillis());
		Assert.assertEquals("live lot", 3_600_000L, flips.get(1).holdMillis());
	}

	/**
	 * A slot holding a DIFFERENT offer than the one remembered must baseline,
	 * not book. Same item and direction is not enough — you can abort a buy
	 * and place another for the same thing at a different price, and measuring
	 * the new one against the old one's numbers invents a fill.
	 */
	@Test
	public void aReplacedOfferIsNotTheRememberedOne()
	{
		FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, 1_800L, 1_000, false);
		t.onOffer(2L, 0, 1601, "Diamond", true, 300, 540_000L, 1_800L, 1_000, false);

		// Same item, same direction, but re-listed 100 gp higher.
		FlipTracker t2 = restart(t);
		TradeFill f = t2.onOffer(99L, 0, 1601, "Diamond", true, 400, 760_000L, 1_900L, 1_000, false);
		Assert.assertNull("different price, so a different offer", f);

		// And a different size is equally a different offer.
		FlipTracker t3 = restart(t);
		Assert.assertNull(t3.onOffer(99L, 0, 1601, "Diamond", true, 400, 720_000L, 1_800L, 5_000, false));

		// A slot that went BACKWARDS was emptied and refilled unseen.
		FlipTracker t4 = restart(t);
		Assert.assertNull(t4.onOffer(99L, 0, 1601, "Diamond", true, 50, 90_000L, 1_800L, 1_000, false));
	}

	/**
	 * Slot 3 is a different offer on every character, and this state file is
	 * shared by all of them, so baselines do not survive a change of account.
	 */
	@Test
	public void baselinesDoNotCrossAccounts()
	{
		FlipTracker t = new FlipTracker();
		t.setAccountHash(1111L);
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, 1_800L, 1_000, false);
		t.onOffer(2L, 0, 1601, "Diamond", true, 300, 540_000L, 1_800L, 1_000, false);

		FlipTracker t2 = restart(t);
		t2.setAccountHash(2222L);
		Assert.assertNull("another character's slot 3 is not this one's",
			t2.onOffer(99L, 0, 1601, "Diamond", true, 700, 1_260_000L, 1_800L, 1_000, false));

		// Same character, and it still works.
		FlipTracker t3 = restart(t);
		t3.setAccountHash(1111L);
		Assert.assertNotNull(t3.onOffer(99L, 0, 1601, "Diamond", true, 700, 1_260_000L, 1_800L, 1_000, false));
	}

	/**
	 * Placing and collecting an offer produce no fill, but they DO move a
	 * baseline — and an unsaved baseline is re-taken from scratch next start,
	 * which is the bug this all exists to fix.
	 */
	@Test
	public void baselineOnlyChangesStillAskToBeSaved()
	{
		FlipTracker t = new FlipTracker();
		t.takeSlotsDirty();
		t.onOffer(1L, 0, 1601, "Diamond", true, 0, 0L, 1_800L, 100, false);
		Assert.assertTrue("placing an offer moved a baseline", t.takeSlotsDirty());
		Assert.assertFalse("and the flag clears when read", t.takeSlotsDirty());

		t.onOffer(2L, 0, 1601, "Diamond", true, 100, 180_000L, 1_800L, 100, true);
		Assert.assertTrue("collecting it moved a baseline too", t.takeSlotsDirty());
	}

	/** Saves written before slot baselines existed restore as they always
	 *  did: no slots, so the next login baselines and books nothing. */
	@Test
	public void anOlderSaveRestoresWithoutSlots()
	{
		FlipTracker t = new FlipTracker();
		FlipTracker.State s = new FlipTracker.State();
		s.lifetimeProfit = 5_000L;
		s.slots = null;
		s.openBuys = null;
		s.flips = null;
		t.restore(s);
		Assert.assertEquals(5_000L, t.getLifetimeProfit());
		Assert.assertNull(t.onOffer(1L, 0, 1601, "Diamond", true, 900, 1_620_000L, 1_800L, 1_000, false));
	}
}
