package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * A sell that has started selling: its hover says what it has made so far,
 * not only what the part still listed would add — "seee how i made 295k
 * emeralds. it should atleast say 295k of x amount total 85k left maybe?"
 */
public class BookedSoFarTest
{
	private static final int UNCUT_EMERALD = 1621;

	private static String words(String markup)
	{
		return markup.replaceAll("<[^>]*>", "").replaceAll("\\s+", " ").trim();
	}

	/** 7,950 bought at 317, listed at 372, 6,162 sold: 48 gp a unit after
	 *  the 7 gp tax, booked against the offer that sold them. */
	@Test
	public void anOfferKnowsWhatItsSalesBooked()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		final long now = System.currentTimeMillis();
		t.onOffer(now, 0, UNCUT_EMERALD, "Uncut emerald", true, 0, 0L, false);
		t.onOffer(now + 1_000, 0, UNCUT_EMERALD, "Uncut emerald", true, 7_950, 7_950L * 317, false);
		t.onOffer(now + 2_000, 1, UNCUT_EMERALD, "Uncut emerald", false, 0, 0L, false);
		t.onOffer(now + 3_000, 1, UNCUT_EMERALD, "Uncut emerald", false, 4_000, 4_000L * 372, false);
		t.onOffer(now + 4_000, 1, UNCUT_EMERALD, "Uncut emerald", false, 6_162, 6_162L * 372, false);

		final long[] booked = t.bookedBy(1);
		Assert.assertNotNull(booked);
		Assert.assertEquals("both fills, one trade", 6_162, booked[0]);
		Assert.assertEquals(6_162L * 48, booked[1]);
		Assert.assertNull("the buy booked nothing", t.bookedBy(0));
		Assert.assertNull("an empty slot booked nothing", t.bookedBy(5));

		/* Collected, and the slot takes a new sell: that offer has sold
		   nothing yet, whatever the last one made. */
		t.onOffer(now + 5_000, 1, 0, "", false, 0, 0L, true);
		t.onOffer(now + 6_000, 1, UNCUT_EMERALD, "Uncut emerald", false, 0, 0L, false);
		Assert.assertNull(t.bookedBy(1));
	}

	@Test
	public void theHoverSaysMadeSoFarAndWhatIsLeft()
	{
		final String box = words(GeOfferGridOverlay.moneyLine(false, 85_800L, 295_776L, 6_162, 6_162));
		Assert.assertTrue(box, box.startsWith("Made +295"));
		Assert.assertTrue(box, box.contains("so far, +85"));
		Assert.assertTrue(box, box.contains("to come (+381"));
		Assert.assertTrue(box, box.endsWith("in all)"));

		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.buy = false;
		s.projectedProfit = 85_800L;
		s.bookedProfit = 295_776L;
		s.bookedUnits = 6_162;
		s.quantityFilled = 6_162;
		final String square = words(GeSlotsPanel.moneyLine(s));
		Assert.assertEquals("the square says what the box says", box, square);
	}

	/**
	 * Sold partly where the plugin could not book it — on mobile, between
	 * logins — the figure says what it covers and claims no total: 4,000
	 * sold unseen and 162 since read "Made +7.7K so far ... +189K in all"
	 * for an offer that had made about +200K.
	 */
	@Test
	public void aFigureBookedOnSomeOfTheSalesSaysSo()
	{
		final String box = words(GeOfferGridOverlay.moneyLine(false, 181_824L, 7_776L, 162, 4_162));
		Assert.assertTrue(box, box.contains("on 162 of 4,162 sold"));
		Assert.assertFalse(box, box.contains("in all"));
		Assert.assertFalse(box, box.contains("so far"));

		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.projectedProfit = 181_824L;
		s.bookedProfit = 7_776L;
		s.bookedUnits = 162;
		s.quantityFilled = 4_162;
		Assert.assertEquals("the square says what the box says", box, words(GeSlotsPanel.moneyLine(s)));
	}

	/** Booked on the offer as it sells, so other slots' trading — the
	 *  flips list keeps only the newest 500 — cannot take it away, and it
	 *  survives a restart. */
	@Test
	public void theTotalSurvivesOtherTradingAndARestart()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		final long now = System.currentTimeMillis();
		t.onOffer(now, 0, UNCUT_EMERALD, "Uncut emerald", true, 0, 0L, false);
		t.onOffer(now + 1, 0, UNCUT_EMERALD, "Uncut emerald", true, 7_950, 7_950L * 317, false);
		t.onOffer(now + 2, 1, UNCUT_EMERALD, "Uncut emerald", false, 0, 0L, false);
		t.onOffer(now + 3, 1, UNCUT_EMERALD, "Uncut emerald", false, 6_000, 6_000L * 372, false);
		/* 600 flips of something else, in another slot. */
		t.onOffer(now + 10, 2, 565, "Blood rune", true, 0, 0L, false);
		t.onOffer(now + 11, 2, 565, "Blood rune", true, 600, 600L * 290, false);
		t.onOffer(now + 12, 2, 565, "Blood rune", true, 600, 600L * 290, true);
		t.onOffer(now + 13, 3, 565, "Blood rune", false, 0, 0L, false);
		for (int i = 1; i <= 600; i++)
		{
			t.onOffer(now + 100 + i, 3, 565, "Blood rune", false, i, i * 310L, false);
		}
		Assert.assertArrayEquals(new long[]{6_000, 6_000L * 48}, t.bookedBy(1));

		final FlipTracker after = new FlipTracker();
		after.restore(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(t.snapshot()),
			FlipTracker.State.class));
		after.setAccountHash(42L);
		Assert.assertArrayEquals("through a restart", new long[]{6_000, 6_000L * 48}, after.bookedBy(1));
	}

	/** A sell already part-sold under a build that counted its total off
	 *  the flips list: after the upgrade, its total is the same. */
	@Test
	public void aSaveFromBeforeTheTotalIsSeededFromItsFlips()
	{
		final FlipTracker t = new FlipTracker();
		t.setAccountHash(42L);
		final long now = System.currentTimeMillis();
		t.onOffer(now, 0, UNCUT_EMERALD, "Uncut emerald", true, 0, 0L, false);
		t.onOffer(now + 1, 0, UNCUT_EMERALD, "Uncut emerald", true, 7_950, 7_950L * 317, false);
		t.onOffer(now + 2, 1, UNCUT_EMERALD, "Uncut emerald", false, 0, 0L, false);
		t.onOffer(now + 3, 1, UNCUT_EMERALD, "Uncut emerald", false, 6_162, 6_162L * 372, false);
		final FlipTracker.State saved = t.snapshot();
		/* As the earlier build wrote them: eight fields to a slot row. */
		for (java.util.Map<Integer, long[]> rows : saved.slotsByAccount.values())
		{
			rows.replaceAll((slot, row) -> java.util.Arrays.copyOf(row, 8));
		}
		final FlipTracker after = new FlipTracker();
		after.restore(new com.google.gson.Gson().fromJson(new com.google.gson.Gson().toJson(saved), FlipTracker.State.class));
		after.setAccountHash(42L);
		Assert.assertArrayEquals(new long[]{6_162, 6_162L * 48}, after.bookedBy(1));
		Assert.assertNull("the buy slot booked nothing", after.bookedBy(0));
	}

	/** Nothing sold yet, and a buy: the one line, as before. */
	@Test
	public void beforeTheFirstSaleItIsTheOneLine()
	{
		Assert.assertTrue(words(GeOfferGridOverlay.moneyLine(false, 85_800L, null, 0, 0)).startsWith("Profit: +"));
		Assert.assertTrue(words(GeOfferGridOverlay.moneyLine(true, 60_000L, null, 0, 0)).startsWith("Profit if it flips: +"));
		Assert.assertNull(GeOfferGridOverlay.moneyLine(false, null, null, 0, 0));

		final GeSlotsPanel.SlotInfo buy = new GeSlotsPanel.SlotInfo();
		buy.buy = true;
		buy.projectedProfit = 60_000L;
		Assert.assertEquals(words(GeOfferGridOverlay.moneyLine(true, 60_000L, null, 0, 0)), words(GeSlotsPanel.moneyLine(buy)));
	}

	/** A loss reads as one, in both places. */
	@Test
	public void aLossIsSigned()
	{
		final String box = words(GeOfferGridOverlay.moneyLine(false, -20_000L, 5_000L, 100, 100));
		Assert.assertTrue(box, box.contains(", -20"));
		Assert.assertTrue(box, box.contains("to come (-15"));
	}
}
