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
		final String box = words(GeOfferGridOverlay.moneyLine(false, 85_800L, 295_776L));
		Assert.assertTrue(box, box.startsWith("Made +295"));
		Assert.assertTrue(box, box.contains("so far, +85"));
		Assert.assertTrue(box, box.contains("to come (+381"));
		Assert.assertTrue(box, box.endsWith("in all)"));

		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.buy = false;
		s.projectedProfit = 85_800L;
		s.bookedProfit = 295_776L;
		final String square = words(GeSlotsPanel.moneyLine(s));
		Assert.assertEquals("the square says what the box says", box, square);
	}

	/** Nothing sold yet, and a buy: the one line, as before. */
	@Test
	public void beforeTheFirstSaleItIsTheOneLine()
	{
		Assert.assertTrue(words(GeOfferGridOverlay.moneyLine(false, 85_800L, null)).startsWith("Profit: +"));
		Assert.assertTrue(words(GeOfferGridOverlay.moneyLine(true, 60_000L, null)).startsWith("Profit if it flips: +"));
		Assert.assertNull(GeOfferGridOverlay.moneyLine(false, null, null));

		final GeSlotsPanel.SlotInfo buy = new GeSlotsPanel.SlotInfo();
		buy.buy = true;
		buy.projectedProfit = 60_000L;
		Assert.assertEquals(words(GeOfferGridOverlay.moneyLine(true, 60_000L, null)), words(GeSlotsPanel.moneyLine(buy)));
	}

	/** A loss reads as one, in both places. */
	@Test
	public void aLossIsSigned()
	{
		final String box = words(GeOfferGridOverlay.moneyLine(false, -20_000L, 5_000L));
		Assert.assertTrue(box, box.contains(", -20"));
		Assert.assertTrue(box, box.contains("to come (-15"));
	}
}
