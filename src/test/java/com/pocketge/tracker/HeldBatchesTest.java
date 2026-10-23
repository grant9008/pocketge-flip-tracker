package com.pocketge.tracker;

import java.util.HashMap;
import java.util.Map;
import net.runelite.client.util.QuantityFormatter;
import org.junit.Assert;
import org.junit.Test;

/**
 * "it cant remember 2 costs paid?" / "yes add that to the hover": the
 * batches behind a stack, as the hover lists them.
 */
public class HeldBatchesTest
{
	private static final int ITEM = 4151;
	private long now = 1_000_000L;

	/** One buy offer of {@code qty} at {@code price}, filled and collected. */
	private void buy(FlipTracker t, int qty, int price)
	{
		t.onOffer(now++, 0, ITEM, "Item", true, 0, 0, price, qty, false);
		t.onOffer(now++, 0, ITEM, "Item", true, qty, (long) qty * price, price, qty, false);
		t.onOffer(now++, 0, 0, "", false, 0, 0, 0, 0, true);
	}

	private static Map<Integer, Long> holding(long n)
	{
		final Map<Integer, Long> m = new HashMap<>();
		m.put(ITEM, n);
		return m;
	}

	private FlipTracker threeBatches()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		buy(t, 10_000, 349);
		buy(t, 10_000, 333);
		buy(t, 10_000, 329);
		return t;
	}

	@Test
	public void itRemembersEachBatchItWasBoughtIn()
	{
		final FlipTracker t = threeBatches();
		Assert.assertArrayEquals("oldest first, as bought",
			new long[]{10_000, 3_490_000, 10_000, 3_330_000, 10_000, 3_290_000},
			t.heldBatches(holding(30_000)).get(ITEM));
	}

	/** Selling 5K uses the newest batch first, and the rest is what is
	 *  listed: 10K @ 349, 10K @ 333, 5K @ 329. */
	@Test
	public void aSaleTakesFromTheNewest()
	{
		final FlipTracker t = threeBatches();
		t.onOffer(now++, 1, ITEM, "Item", false, 0, 0, 340, 5_000, false);
		t.onOffer(now++, 1, ITEM, "Item", false, 5_000, 5_000L * 340, 340, 5_000, false);
		Assert.assertArrayEquals(new long[]{10_000, 3_490_000, 10_000, 3_330_000, 5_000, 1_645_000},
			t.heldBatches(holding(25_000)).get(ITEM));
	}

	/** And it is the same stack the hover's "Paid" is worked out from. */
	@Test
	public void theBatchesAddUpToWhatThePaidLineSays()
	{
		final FlipTracker t = threeBatches();
		for (long held : new long[]{30_000, 25_000, 12_000})
		{
			final long[] b = t.heldBatches(holding(held)).get(ITEM);
			long q = 0, s = 0;
			for (int i = 0; i < b.length; i += 2)
			{
				q += b[i];
				s += b[i + 1];
			}
			final long[] paid = t.getHeldCost(holding(held)).get(ITEM);
			Assert.assertEquals("units at " + held, paid[0], q);
			Assert.assertEquals("gp at " + held, paid[1], s);
		}
	}

	/** One buy offer filled in several parts at different prices is one
	 *  batch, at its average. */
	@Test
	public void anOfferFilledInPartsIsOneBatch()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		t.onOffer(now++, 0, ITEM, "Item", true, 0, 0, 350, 9_000, false);
		t.onOffer(now++, 0, ITEM, "Item", true, 3_000, 3_000L * 345, 350, 9_000, false);
		t.onOffer(now++, 0, ITEM, "Item", true, 6_000, 3_000L * 345 + 3_000L * 348, 350, 9_000, false);
		t.onOffer(now++, 0, ITEM, "Item", true, 9_000, 3_000L * 345 + 3_000L * 348 + 3_000L * 350, 350, 9_000, false);
		/* Uncollected, the units are in the box and not in the stack you
		   hold; collected, they are. */
		Assert.assertNull(t.heldBatches(holding(9_000)).get(ITEM));
		t.onOffer(now++, 0, 0, "", false, 0, 0, 0, 0, true);
		Assert.assertArrayEquals(new long[]{9_000, 3_000L * (345 + 348 + 350)}, t.heldBatches(holding(9_000)).get(ITEM));
	}

	/** Older buys beyond what you hold (sold where the plugin did not see)
	 *  are not batches of the stack you hold. */
	@Test
	public void onlyTheBatchesOfWhatYouHold()
	{
		final FlipTracker t = threeBatches();
		Assert.assertArrayEquals(new long[]{10_000, 3_330_000, 10_000, 3_290_000}, t.heldBatches(holding(20_000)).get(ITEM));
		Assert.assertArrayEquals("part of a batch", new long[]{2_000, 2_000L * 329},
			t.heldBatches(holding(2_000)).get(ITEM));
	}

	@Test
	public void theHoverListsThemWhenThereIsMoreThanOne()
	{
		final String three = BankHighlightOverlay.batchesText(
			new long[]{10_000, 3_490_000, 10_000, 3_330_000, 10_000, 3_290_000});
		final String k = QuantityFormatter.quantityToStackSize(10_000);
		Assert.assertTrue(three, three.contains("Bought in batches, newest sells first:"));
		Assert.assertTrue(three, three.contains(k + " @ 349 · " + k + " @ 333 · " + k + " @ 329"));
		Assert.assertEquals("one batch: the Paid line says it", "",
			BankHighlightOverlay.batchesText(new long[]{10_000, 3_490_000}));
		Assert.assertEquals("", BankHighlightOverlay.batchesText(null));

		final String five = BankHighlightOverlay.batchesText(
			new long[]{100, 10_000, 100, 11_000, 100, 12_000, 100, 13_000, 100, 14_000});
		Assert.assertTrue(five, five.contains("+2 older · 100 @ 120 · 100 @ 130 · 100 @ 140"));
	}
}
