package com.pocketge.tracker;

import net.runelite.api.GameState;
import net.runelite.api.GrandExchangeOfferState;
import org.junit.Assert;
import org.junit.Test;

/**
 * What the tracker does with one real offer's life: fills, a price change,
 * a logout and a hop.
 *
 * Written while chasing a sell card that read "PAID @ 1,025" for Mithril bars
 * bought at 876. The tracker was not at fault — that was the card's old
 * formula, since fixed (SellCardPaidTest) — but tracing every path an offer
 * can take found a real way to count a purchase twice, and these hold both.
 */
public class GeReplayTest
{
	private static final int MITHRIL_BAR = 2359;

	private static long[] totals(FlipTracker t)
	{
		return t.getOpenBuyTotals().get(MITHRIL_BAR);
	}

	/** First fills and later fills of one offer are one cost basis — the
	 *  first 173 are inside the 3,738, never on top of them. */
	@Test
	public void anOffersFillsAreOneCostBasisThroughAPriceChange()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 173, 173L * 876, 876, 10_000, false);
		t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		// The price change: the rest re-listed at 871, nothing filled yet.
		t.onOffer(4L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 871, 6_262, false);
		Assert.assertArrayEquals(new long[]{3_738, 3_738L * 876}, totals(t));
	}

	@Test
	public void abortCollectRelistKeepsTheLots()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		Assert.assertNull("the abort itself books nothing",
			t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false));
		t.onOffer(4L, 0, 0, "", false, 0, 0L, 0, 0, true); // collected
		t.onOffer(5L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 871, 6_262, false);
		Assert.assertArrayEquals(new long[]{3_738, 3_738L * 876}, totals(t));
	}

	/**
	 * Why the plugin must not forward the login clear. If it reaches the
	 * tracker, the record goes, and the replayed offer books its whole
	 * receipt again: 3,738 bars become 7,476.
	 */
	@Test
	public void forwardingTheLoginClearWouldDoubleCount()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		t.onOffer(3L, 0, 0, "", false, 0, 0L, 0, 0, true); // the clear, if forwarded
		t.onOffer(4L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		Assert.assertArrayEquals("this is the bug isLoginClear stops",
			new long[]{7_476, 7_476L * 876}, totals(t));
	}

	/** Not forwarded, the replay is the same offer and books nothing. */
	@Test
	public void theReplayAloneBooksNothing()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		Assert.assertNull(t.onOffer(4L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false));
		Assert.assertArrayEquals(new long[]{3_738, 3_738L * 876}, totals(t));
	}

	@Test
	public void theLoginClearIsRecognised()
	{
		for (GameState g : new GameState[]{GameState.LOGIN_SCREEN, GameState.LOGGING_IN,
			GameState.HOPPING, GameState.CONNECTION_LOST, GameState.LOADING})
		{
			Assert.assertTrue(g.toString(), PocketGeTrackerPlugin.isLoginClear(GrandExchangeOfferState.EMPTY, g));
		}
		Assert.assertFalse("collecting in game is real",
			PocketGeTrackerPlugin.isLoginClear(GrandExchangeOfferState.EMPTY, GameState.LOGGED_IN));
		Assert.assertFalse("only EMPTY is ever ignored",
			PocketGeTrackerPlugin.isLoginClear(GrandExchangeOfferState.BUYING, GameState.LOGGING_IN));
		Assert.assertFalse(
			PocketGeTrackerPlugin.isLoginClear(GrandExchangeOfferState.CANCELLED_BUY, GameState.HOPPING));
	}

	/** A buy delta above its own bid was measured against another offer's
	 *  baseline; it books nothing rather than a lot above anything paid. */
	@Test
	public void aBuyDeltaAboveTheBidIsNotBooked()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 100, 87_600L, 876, 10_000, false);
		Assert.assertNull("50 more at 3,232 each against an 876 bid",
			t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 150, 87_600L + 50L * 3_232, 876, 10_000, false));
		Assert.assertArrayEquals(new long[]{100, 87_600L}, totals(t));
		Assert.assertNotNull("an ordinary fill at the bid still books",
			t.onOffer(4L, 0, MITHRIL_BAR, "Mithril bar", true, 200, 87_600L + 50L * 3_232 + 50L * 876, 876, 10_000, false));
		Assert.assertArrayEquals(new long[]{150, 87_600L + 50L * 876}, totals(t));
	}

	/**
	 * The same offer collected and placed again while the player was logged
	 * out (on mobile). The slot looks like the old offer grown, but the
	 * growth costs more than its bid allows — so it is a new offer, and its
	 * whole receipt is booked. This used to book nothing and lose the buy.
	 */
	@Test
	public void anIdenticalOfferPlacedAgainIsBookedFromZero()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		// filled cheap: 100 for 500 each
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 100, 50_000L, 876, 10_000, false);
		t.markSlotsUnwatched(); // logged out
		// back in: the replacement, 150 at the full 876
		final TradeFill f = t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 150, 150L * 876, 876, 10_000, false);
		Assert.assertNotNull(f);
		Assert.assertEquals(150, f.quantity);
		Assert.assertArrayEquals(new long[]{250, 50_000L + 150L * 876}, totals(t));
		// and the replacement's next fill is measured against itself
		Assert.assertEquals(10, t.onOffer(4L, 0, MITHRIL_BAR, "Mithril bar", true, 160, 160L * 876, 876, 10_000, false).quantity);
	}

	/** The same over-bid growth, watched live, is a misread and books
	 *  nothing: a slot cannot change offers in view without an EMPTY. */
	@Test
	public void watchedLiveAnOverBidGrowthBooksNothing()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 100, 50_000L, 876, 10_000, false);
		Assert.assertNull(t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 150, 150L * 876, 876, 10_000, false));
		Assert.assertArrayEquals(new long[]{100, 50_000L}, totals(t));
	}

	/**
	 * Two characters with the same offer in the same slot, one of them with
	 * a negative account hash. Each must keep its own baseline: shared, the
	 * slot flipped between their offers on every switch, and the over-bid
	 * rule booked the main's receipt again each time it came back —
	 * 3,000 bars became 6,000, 9,000, 12,000.
	 */
	@Test
	public void switchingToANegativeHashCharacterBooksNothingTwice()
	{
		final FlipTracker t = new FlipTracker();
		final long main = 123_456_789L;
		final long alt = -987_654_321L;
		t.setAccountHash(main);
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_000, 3_000L * 876, 876, 10_000, false);
		for (int round = 0; round < 3; round++)
		{
			t.markSlotsUnwatched();
			t.setAccountHash(alt);
			t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 2_500, 2_000_000L, 876, 10_000, false);
			t.markSlotsUnwatched();
			t.setAccountHash(main);
			t.onOffer(4L, 0, MITHRIL_BAR, "Mithril bar", true, 3_000, 3_000L * 876, 876, 10_000, false);
		}
		final long[] lots = totals(t);
		Assert.assertEquals("the main's 3,000, once; the alt's first sighting is its own", 3_000L + 2_500L, lots[0]);
	}

	/** Forgetting drops one item's lots and nothing else; the slot keeps its
	 *  baseline, so the offer's next fill still books. */
	@Test
	public void forgettingDropsTheItemsLotsOnly()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		t.onOffer(3L, 1, 1601, "Diamond", true, 0, 0L, 2_000, 10, false);
		t.onOffer(4L, 1, 1601, "Diamond", true, 10, 20_000L, 2_000, 10, false);

		final long[] record = t.getOpenBuyRecord(MITHRIL_BAR);
		Assert.assertArrayEquals(new long[]{3_738, 3_738L * 876}, t.forgetOpenBuys(MITHRIL_BAR, record[2]));
		Assert.assertNull(totals(t));
		Assert.assertNull(t.getOpenBuyTotal(MITHRIL_BAR));
		Assert.assertArrayEquals(new long[]{10, 20_000L}, t.getOpenBuyTotal(1601));
		Assert.assertNull("nothing left to forget", t.forgetOpenBuys(MITHRIL_BAR, record[2]));

		t.onOffer(5L, 0, MITHRIL_BAR, "Mithril bar", true, 3_800, 3_800L * 876, 876, 10_000, false);
		Assert.assertArrayEquals(new long[]{62, 62L * 876}, totals(t));
	}

	/** A fill booked while the forget prompt was open is not what the
	 *  player was shown, and survives the forget. */
	@Test
	public void forgettingKeepsLotsBookedAfterThePrompt()
	{
		final FlipTracker t = new FlipTracker();
		t.onOffer(1L, 0, MITHRIL_BAR, "Mithril bar", true, 0, 0L, 876, 10_000, false);
		t.onOffer(2L, 0, MITHRIL_BAR, "Mithril bar", true, 3_738, 3_738L * 876, 876, 10_000, false);
		final long[] shown = t.getOpenBuyRecord(MITHRIL_BAR);
		t.onOffer(3L, 0, MITHRIL_BAR, "Mithril bar", true, 4_238, 3_738L * 876 + 500L * 870, 876, 10_000, false);
		Assert.assertArrayEquals(new long[]{3_738, 3_738L * 876}, t.forgetOpenBuys(MITHRIL_BAR, shown[2]));
		Assert.assertArrayEquals(new long[]{500, 500L * 870}, totals(t));
	}
}
