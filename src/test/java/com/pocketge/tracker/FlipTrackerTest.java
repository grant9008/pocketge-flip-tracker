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
}
