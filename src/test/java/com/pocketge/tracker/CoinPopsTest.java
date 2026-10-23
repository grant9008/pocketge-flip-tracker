package com.pocketge.tracker;

import com.google.gson.Gson;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.image.BufferedImage;
import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/**
 * "i saw sharks sell but no green numkbers came up" / "oo you right i just
 * cooked these" / "yes add the gold coins pop": a sale with no buy on record
 * pops the coins it brought in, in gold. Never as profit.
 */
public class CoinPopsTest
{
	private static final int SHARK = 385;
	private static final long MAIN = 42L;

	private static long each(long price)
	{
		return price - FlipTracker.taxPerItem(price, SHARK);
	}

	/** Sharks listed in slot 3 at 900 that nothing on record paid for. */
	private static FlipTracker cooked()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		t.onOffer(1_000, 3, SHARK, "Shark", false, 0, 0, 900, 2_000, false);
		return t;
	}

	private static void sold(FlipTracker t, long when, int units)
	{
		t.onOffer(when, 3, SHARK, "Shark", false, units, units * 900L, 900, 2_000, false);
	}

	private static FlipTracker restarted(FlipTracker t)
	{
		final Gson gson = new Gson();
		final FlipTracker after = new FlipTracker();
		after.restore(gson.fromJson(gson.toJson(t.snapshot()), FlipTracker.State.class));
		after.setAccountHash(MAIN);
		return after;
	}

	@Test
	public void cookedSharksWaitAsCoinsAfterTax()
	{
		final FlipTracker t = cooked();
		Assert.assertNull("nothing sold yet", t.unseenBy(3, SHARK));
		Assert.assertFalse(t.waiting(3));
		sold(t, 2_000, 500);
		Assert.assertTrue("taxed", each(900) < 900);
		Assert.assertArrayEquals(new long[]{500, 500 * each(900), FlipTracker.UNSEEN_COINS}, t.unseenBy(3, SHARK));
		Assert.assertTrue(t.waiting(3));
		Assert.assertNull("not profit: nothing booked", t.bookedBy(3));
		Assert.assertTrue("and no flip", t.getFlips().isEmpty());

		sold(t, 3_000, 800);
		Assert.assertArrayEquals("every batch, until shown", new long[]{800, 800 * each(900), FlipTracker.UNSEEN_COINS},
			t.unseenBy(3, SHARK));
		t.markShown(3);
		Assert.assertNull(t.unseenBy(3, SHARK));
		Assert.assertFalse(t.waiting(3));

		sold(t, 4_000, 1_000);
		Assert.assertArrayEquals("then only what is new", new long[]{200, 200 * each(900), FlipTracker.UNSEEN_COINS},
			t.unseenBy(3, SHARK));
		Assert.assertNull("only over that item's sell", t.unseenBy(3, 386));
	}

	/** 300 bought, 200 cooked, all 500 sold in one go: one figure, the
	 *  profit — and the coins go with it rather than popping after. */
	@Test
	public void profitKeepsTheSquareWhenBothWait()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		t.onOffer(1_000, 0, SHARK, "Shark", true, 0, 0, 800, 300, false);
		t.onOffer(2_000, 0, SHARK, "Shark", true, 300, 300 * 800L, 800, 300, false);
		t.onOffer(3_000, 0, 0, "", false, 0, 0, 0, 0, true);
		t.onOffer(4_000, 3, SHARK, "Shark", false, 0, 0, 900, 500, false);
		t.onOffer(5_000, 3, SHARK, "Shark", false, 500, 500 * 900L, 900, 500, false);
		Assert.assertArrayEquals(new long[]{300, 300 * (each(900) - 800), FlipTracker.UNSEEN_PROFIT}, t.unseenBy(3, SHARK));
		final long[] row = t.snapshot().slotsByAccount.get(MAIN).get(3);
		Assert.assertArrayEquals("the 200 cooked are on the record all the same",
			new long[]{200, 200 * each(900), 0, 0}, java.util.Arrays.copyOfRange(row, row.length - 4, row.length));
		t.markShown(3);
		Assert.assertNull("the coins counted as shown with it", t.unseenBy(3, SHARK));
		final long[] shown = t.snapshot().slotsByAccount.get(MAIN).get(3);
		Assert.assertArrayEquals(new long[]{200, 200 * each(900), 200, 200 * each(900)},
			java.util.Arrays.copyOfRange(shown, shown.length - 4, shown.length));
	}

	/** The bought ones sell first, the cooked after: profit, then coins. */
	@Test
	public void aLaterSaleWithNothingLeftOnRecordPopsCoins()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		t.onOffer(1_000, 0, SHARK, "Shark", true, 0, 0, 800, 300, false);
		t.onOffer(2_000, 0, SHARK, "Shark", true, 300, 300 * 800L, 800, 300, false);
		t.onOffer(3_000, 0, 0, "", false, 0, 0, 0, 0, true);
		t.onOffer(4_000, 3, SHARK, "Shark", false, 0, 0, 900, 500, false);
		t.onOffer(5_000, 3, SHARK, "Shark", false, 300, 300 * 900L, 900, 500, false);
		Assert.assertEquals(FlipTracker.UNSEEN_PROFIT, t.unseenBy(3, SHARK)[2]);
		t.markShown(3);
		t.onOffer(6_000, 3, SHARK, "Shark", false, 500, 500 * 900L, 900, 500, false);
		Assert.assertArrayEquals(new long[]{200, 200 * each(900), FlipTracker.UNSEEN_COINS}, t.unseenBy(3, SHARK));
	}

	/** Sold with the Exchange shut, then RuneLite restarted. */
	@Test
	public void survivesARestart()
	{
		final FlipTracker t = cooked();
		sold(t, 2_000, 500);
		final FlipTracker after = restarted(t);
		Assert.assertArrayEquals(new long[]{500, 500 * each(900), FlipTracker.UNSEEN_COINS}, after.unseenBy(3, SHARK));
		after.markShown(3);
		Assert.assertNull("shown, and not again after the next", restarted(after).unseenBy(3, SHARK));
	}

	/** Saved behind the pairs of units that were in a box at listing — and
	 *  both read back where they were written. */
	@Test
	public void savedAfterTheBoxedPairs()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(MAIN);
		/* 100 bought and still in slot 0's box when the sell is listed: the
		   sell cannot be those, so it is charged nothing. */
		t.onOffer(1_000, 0, SHARK, "Shark", true, 0, 0, 800, 100, false);
		t.onOffer(2_000, 0, SHARK, "Shark", true, 100, 100 * 800L, 800, 100, false);
		t.noteBox(0, SHARK, 100);
		t.onOffer(3_000, 3, SHARK, "Shark", false, 0, 0, 900, 2_000, false);
		sold(t, 4_000, 50);
		final long[] row = t.snapshot().slotsByAccount.get(MAIN).get(3);
		Assert.assertEquals("one boxed pair", 1, row[13]);
		Assert.assertEquals("then the coins", 14 + 2 + 4, row.length);

		final FlipTracker after = restarted(t);
		Assert.assertArrayEquals(new long[]{50, 50 * each(900), FlipTracker.UNSEEN_COINS}, after.unseenBy(3, SHARK));
		after.markShown(3);
		/* The pair still keeps the boxed lot out once collected: more sold
		   is coins again, not profit against it. */
		after.onOffer(5_000, 0, 0, "", false, 0, 0, 0, 0, true);
		after.onOffer(6_000, 3, SHARK, "Shark", false, 150, 150 * 900L, 900, 2_000, false);
		Assert.assertNull(after.bookedBy(3));
		Assert.assertArrayEquals(new long[]{100, 100 * each(900), FlipTracker.UNSEEN_COINS}, after.unseenBy(3, SHARK));
	}

	/** A save from before the coins: none of what it sold pops. */
	@Test
	public void anUpgradePopsNothingOld()
	{
		final FlipTracker t = cooked();
		sold(t, 2_000, 500);
		final FlipTracker.State saved = t.snapshot();
		saved.slotsByAccount.get(MAIN).replaceAll((slot, row) -> java.util.Arrays.copyOf(row, 14));
		final Gson gson = new Gson();
		final FlipTracker after = new FlipTracker();
		after.restore(gson.fromJson(gson.toJson(saved), FlipTracker.State.class));
		after.setAccountHash(MAIN);
		Assert.assertNull(after.unseenBy(3, SHARK));
		sold(after, 3_000, 700);
		Assert.assertArrayEquals("what sells after it does", new long[]{200, 200 * each(900), FlipTracker.UNSEEN_COINS},
			after.unseenBy(3, SHARK));
	}

	/** Collected on mobile and relisted on other terms: a new offer, with
	 *  only its own coins to show. */
	@Test
	public void aRelistStartsAtNothing()
	{
		final FlipTracker t = cooked();
		sold(t, 2_000, 500);
		final FlipTracker after = restarted(t);
		after.markSlotsUnwatched();
		after.onOffer(9_000, 3, SHARK, "Shark", false, 0, 0, 950, 1_000, false);
		Assert.assertNull(after.unseenBy(3, SHARK));
		after.onOffer(9_500, 3, SHARK, "Shark", false, 100, 95_000, 950, 1_000, false);
		Assert.assertArrayEquals(new long[]{100, 100 * each(950), FlipTracker.UNSEEN_COINS}, after.unseenBy(3, SHARK));
	}

	@Test
	public void markAllShownTakesTheCoinsToo()
	{
		final FlipTracker t = cooked();
		sold(t, 2_000, 500);
		t.setAccountHash(7L);
		t.markAllShown();
		t.setAccountHash(MAIN);
		Assert.assertNull(t.unseenBy(3, SHARK));
	}

	/* ── The figure ─────────────────────────────────────────────────── */

	@Test
	public void goldWithNoSign()
	{
		Assert.assertEquals("1,234 gp", ProfitPops.coinsText(1_234));
		Assert.assertEquals("20K gp", ProfitPops.coinsText(20_000));
		Assert.assertEquals("441,000 is not 441K-with-a-plus", "441K gp", ProfitPops.coinsText(441_000));
		Assert.assertEquals("cut as the sidebar's profit is", "24K", ProfitPops.shortText(new ProfitPops.Pop(0, 0, 24_100, true)));
		Assert.assertEquals("2.4K", ProfitPops.shortText(new ProfitPops.Pop(0, 0, 2_450, true)));
		Assert.assertEquals("200", ProfitPops.shortText(new ProfitPops.Pop(0, 0, 200, true)));
		Assert.assertEquals("+200", ProfitPops.shortText(new ProfitPops.Pop(0, 0, 200)));
		Assert.assertEquals("1,234 gp", ProfitPops.text(new ProfitPops.Pop(0, 0, 1_234, true)));
		Assert.assertEquals("+1,234 gp", ProfitPops.text(new ProfitPops.Pop(0, 0, 1_234)));
		Assert.assertEquals(ProfitPops.COINS, ProfitPops.colour(new ProfitPops.Pop(0, 0, 1_234, true)));
		Assert.assertEquals(ProfitPops.GAIN, ProfitPops.colour(new ProfitPops.Pop(0, 0, 1_234)));
	}

	@Test
	public void coinsAddToCoinsButNeverToProfit()
	{
		ProfitPops pops = new ProfitPops();
		pops.start(1, 1_000, true, 10_000);
		pops.start(1, 500, true, 10_600);
		List<ProfitPops.Pop> live = pops.running(10_700, s -> true);
		Assert.assertEquals(1, live.size());
		Assert.assertEquals(1_500, live.get(0).profit);
		Assert.assertTrue(live.get(0).coins);

		pops = new ProfitPops();
		pops.start(1, 2_000, false, 10_000);
		pops.start(1, 500, true, 10_600);
		live = pops.running(10_700, s -> true);
		Assert.assertEquals("profit keeps the square", 1, live.size());
		Assert.assertEquals(2_000, live.get(0).profit);
		Assert.assertFalse(live.get(0).coins);
		Assert.assertEquals("and is not restarted by the coins", 10_000, live.get(0).at);

		pops = new ProfitPops();
		pops.start(1, 500, true, 10_000);
		pops.start(1, 2_000, false, 10_600);
		live = pops.running(10_700, s -> true);
		Assert.assertEquals(1, live.size());
		Assert.assertEquals("profit takes it over, not added to", 2_000, live.get(0).profit);
		Assert.assertFalse(live.get(0).coins);

		pops = new ProfitPops();
		pops.start(1, 2_000, false, 10_000);
		pops.start(1, 500, true, 12_000);
		live = pops.running(12_100, s -> true);
		Assert.assertEquals("a later sale takes the square over", 1, live.size());
		Assert.assertTrue(live.get(0).coins);
	}

	@Test
	public void paintedInGold()
	{
		final Rectangle slot = new Rectangle(120, 40, 115, 110);
		final BufferedImage img = new BufferedImage(360, 200, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		ProfitPops.draw(g, slot, new ProfitPops.Pop(1, 0, 441_000, true), 400,
			new java.awt.Font(java.awt.Font.SANS_SERIF, java.awt.Font.BOLD, 16));
		g.dispose();
		Assert.assertTrue(count(img, ProfitPops.COINS) > 0);
		Assert.assertEquals(0, count(img, ProfitPops.GAIN));
	}

	static int count(BufferedImage img, Color c)
	{
		int n = 0;
		for (int y = 0; y < img.getHeight(); y++)
		{
			for (int x = 0; x < img.getWidth(); x++)
			{
				final int rgb = img.getRGB(x, y);
				if ((rgb >>> 24) > 200 && Math.abs(((rgb >> 16) & 0xFF) - c.getRed()) < 12
					&& Math.abs(((rgb >> 8) & 0xFF) - c.getGreen()) < 12 && Math.abs((rgb & 0xFF) - c.getBlue()) < 12)
				{
					n++;
				}
			}
		}
		return n;
	}
}
