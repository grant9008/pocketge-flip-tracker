package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class PortfolioValuerTest
{
	private static Advisor.Quote quote(long low, long high)
	{
		Advisor.Quote q = new Advisor.Quote();
		q.low = low;
		q.high = high;
		return q;
	}

	@Test
	public void cashAndItemsSumCorrectly()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1601, quote(100, 110));

		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 50); // 50 * 100 = 5,000

		PortfolioValuer.Result r = PortfolioValuer.value(1_000, holdings, new HashMap<>(), new ArrayList<>(), quotes);
		Assert.assertEquals(1_000L, r.cash);
		Assert.assertEquals(5_000L, r.itemsValue);
		Assert.assertEquals(0L, r.offersValue);
		Assert.assertEquals(6_000L, r.total);
	}

	@Test
	public void unknownItemIsSkippedNotGuessed()
	{
		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(999, 10); // no quote for this id
		PortfolioValuer.Result r = PortfolioValuer.value(0, holdings, new HashMap<>(), new ArrayList<>(), new HashMap<>());
		Assert.assertEquals(0L, r.itemsValue);
		Assert.assertEquals(0L, r.total);
	}

	@Test
	public void openBuyOfferCountsEscrowPlusFilledPortion()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1601, quote(100, 110));

		Advisor.OfferView buy = new Advisor.OfferView();
		buy.itemId = 1601;
		buy.buy = true;
		buy.price = 90;
		buy.totalQuantity = 100;
		buy.quantitySold = 40;
		buy.active = true;

		List<Advisor.OfferView> offers = new ArrayList<>();
		offers.add(buy);

		PortfolioValuer.Result r = PortfolioValuer.value(0, new HashMap<>(), new HashMap<>(), offers, quotes);
		// unfilled 60 * 90 (escrow) + filled 40 * 100 (current market) = 5,400 + 4,000
		Assert.assertEquals(9_400L, r.offersValue);
	}

	@Test
	public void openSellOfferCountsUnsoldPlusProceedsAfterTax()
	{
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(1601, quote(100, 110));

		Advisor.OfferView sell = new Advisor.OfferView();
		sell.itemId = 1601;
		sell.buy = false;
		sell.price = 120;
		sell.totalQuantity = 50;
		sell.quantitySold = 30;
		sell.active = true;

		List<Advisor.OfferView> offers = new ArrayList<>();
		offers.add(sell);

		PortfolioValuer.Result r = PortfolioValuer.value(0, new HashMap<>(), new HashMap<>(), offers, quotes);
		long tax = FlipTracker.taxPerItem(120, 1601) * 30;
		long expected = 20L * 100 /* unsold at market */ + (30L * 120 - tax) /* sold proceeds after tax */;
		Assert.assertEquals(expected, r.offersValue);
	}

	@Test
	public void inactiveOffersAreIgnored()
	{
		Advisor.OfferView inactive = new Advisor.OfferView();
		inactive.itemId = 1601;
		inactive.buy = true;
		inactive.price = 100;
		inactive.totalQuantity = 10;
		inactive.quantitySold = 10;
		inactive.active = false; // BOUGHT/CANCELLED — already collected or gone

		List<Advisor.OfferView> offers = new ArrayList<>();
		offers.add(inactive);

		PortfolioValuer.Result r = PortfolioValuer.value(0, new HashMap<>(), new HashMap<>(), offers, new HashMap<>());
		Assert.assertEquals(0L, r.offersValue);
	}

	/* ── heldPosition: the watchlist "profit available" tag ───────────────
	   Every test here exists because the alternative is a green number in
	   the player's sidebar telling them to go sell something, on a claim
	   that isn't true. */

	@Test
	public void heldPosition_holdingNothingReportsNothing()
	{
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(1601, 0, quote(100, 110), new long[]{5, 400});
		Assert.assertEquals(0, p.heldQty);
		Assert.assertEquals(0L, p.sellValue);
		Assert.assertFalse(p.hasCostBasis);
	}

	@Test
	public void heldPosition_noQuoteMeansNoClaim()
	{
		Assert.assertEquals(0L, PortfolioValuer.heldPosition(1601, 50, null, null).sellValue);
		Assert.assertEquals(0L, PortfolioValuer.heldPosition(1601, 50, quote(100, 0), null).sellValue);
	}

	@Test
	public void heldPosition_untrackedStackIsValuedButNeverCalledProfit()
	{
		// A stack owned before the plugin ever ran: worth something, but we
		// have no idea what it cost, so hasCostBasis must stay false and the
		// UI must not print "profit".
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(1601, 100, quote(1_000, 2_000), null);
		Assert.assertEquals(100, p.heldQty);
		// tax on 2000 = 2000/50 = 40; net 1960 each
		Assert.assertEquals(196_000L, p.sellValue);
		Assert.assertFalse(p.hasCostBasis);
		Assert.assertEquals(0L, p.heldProfit);
		Assert.assertEquals(0, p.pricedQty);
	}

	@Test
	public void heldPosition_trackedStackReportsGainAfterTax()
	{
		// Bought 100 at 1,500 each (150,000 spent), now bid 2,000.
		// net = 2000 - 40 = 1960 -> 196,000 proceeds - 150,000 = 46,000
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			1601, 100, quote(1_900, 2_000), new long[]{100, 150_000});
		Assert.assertTrue(p.hasCostBasis);
		Assert.assertEquals(100, p.pricedQty);
		Assert.assertEquals(196_000L, p.sellValue);
		Assert.assertEquals(46_000L, p.heldProfit);
	}

	@Test
	public void heldPosition_profitCoversOnlyTheUnitsStillHeld()
	{
		// Bought 1,000 and spent 150,000; used 400, so only 600 are left.
		// Claiming profit on all 1,000 would report gain on runes that are
		// gone. Cost of the 600 remaining = 150,000 * 0.6 = 90,000.
		// net = 2000 - 40 = 1960 -> 600 * 1960 = 1,176,000 - 90,000
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			1601, 600, quote(1_900, 2_000), new long[]{1_000, 150_000});
		Assert.assertEquals(600, p.heldQty);
		Assert.assertEquals(600, p.pricedQty);
		Assert.assertEquals(1_176_000L, p.sellValue);
		Assert.assertEquals(1_086_000L, p.heldProfit);
	}

	@Test
	public void heldPosition_profitCoversOnlyTheUnitsWeWatchedYouBuy()
	{
		// Held 500 but only ever saw 100 bought (400 pre-dated the plugin).
		// sellValue covers all 500; profit covers 100. Understating is the
		// only safe direction.
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			1601, 500, quote(1_900, 2_000), new long[]{100, 150_000});
		Assert.assertEquals(500, p.heldQty);
		Assert.assertEquals(100, p.pricedQty);
		Assert.assertEquals(980_000L, p.sellValue);        // 500 * 1960
		Assert.assertEquals(46_000L, p.heldProfit);        // 100 * 1960 - 150,000
	}

	@Test
	public void heldPosition_underwaterPositionReportsNegativeRatherThanZero()
	{
		// Bought 100 at 3,000 (300,000), now only bid 2,000. The panel
		// chooses not to SHOW this, but the number itself must be honest —
		// clamping here would hide a real loss from the inspection card too.
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			1601, 100, quote(1_900, 2_000), new long[]{100, 300_000});
		Assert.assertTrue(p.hasCostBasis);
		Assert.assertEquals(-104_000L, p.heldProfit);
	}

	@Test
	public void heldPosition_roundingNeverFlattersTheProfit()
	{
		// 3 of 7 units held, 100 gp spent on the 7. An average-cost-per-item
		// of 100/7 = 14 (integer truncation) would price the 3 at 42 and
		// claim 3 gp more profit than really exists. Ceil of 100*3/7 = 43.
		// Price is under the 50 gp tax floor, so net == high == 20.
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(
			1601, 3, quote(19, 20), new long[]{7, 100});
		Assert.assertEquals(3, p.pricedQty);
		Assert.assertEquals(60L, p.sellValue);
		Assert.assertEquals(17L, p.heldProfit); // 60 - 43, NOT 60 - 42
	}

	@Test
	public void heldPosition_zeroOrGarbageTrackedLotIsTreatedAsUntracked()
	{
		Assert.assertFalse(PortfolioValuer.heldPosition(1601, 50, quote(100, 200), new long[]{0, 0}).hasCostBasis);
		Assert.assertFalse(PortfolioValuer.heldPosition(1601, 50, quote(100, 200), new long[]{5, 0}).hasCostBasis);
		Assert.assertFalse(PortfolioValuer.heldPosition(1601, 50, quote(100, 200), new long[]{9}).hasCostBasis);
	}

	@Test
	public void heldPosition_taxExemptItemKeepsTheWholePrice()
	{
		// Chisel (1755) is on the tax-exempt list; net must equal the bid.
		PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(1755, 10, quote(900, 1_000), null);
		Assert.assertEquals(10_000L, p.sellValue);
	}

	/* ── cash vs stock ────────────────────────────────────────────────────
	   Platinum tokens are how anyone flipping at size holds their gp. Counted
	   as an item they inflate "stock" and vanish from liquid cash, so the
	   advisor sizes buys against a fraction of the real bankroll. */

	@Test
	public void cashValue_knowsBothDenominations()
	{
		Assert.assertEquals(5_000L, PortfolioValuer.cashValue(PortfolioValuer.COINS_ID, 5_000));
		Assert.assertEquals(500_000_000L,
			PortfolioValuer.cashValue(PortfolioValuer.PLATINUM_TOKEN_ID, 500_000));
		Assert.assertEquals("an ordinary item is not cash", 0L, PortfolioValuer.cashValue(1601, 10));
		Assert.assertEquals(0L, PortfolioValuer.cashValue(PortfolioValuer.COINS_ID, 0));
		Assert.assertEquals(0L, PortfolioValuer.cashValue(PortfolioValuer.COINS_ID, -5));
	}

	@Test
	public void isCash_coversCoinsAndPlatinumOnly()
	{
		Assert.assertTrue(PortfolioValuer.isCash(PortfolioValuer.COINS_ID));
		Assert.assertTrue(PortfolioValuer.isCash(PortfolioValuer.PLATINUM_TOKEN_ID));
		Assert.assertFalse(PortfolioValuer.isCash(1601));
	}

	@Test
	public void cashLeftInHoldingsIsNeverCountedTwice()
	{
		// The plugin strips cash from holdings, but if a stray stack ever got
		// through it must not be added on top of the `cash` argument — and
		// platinum especially must not be valued from a market quote.
		Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(PortfolioValuer.PLATINUM_TOKEN_ID, quote(1_050, 1_100)); // market != face
		quotes.put(PortfolioValuer.COINS_ID, quote(1, 1));
		quotes.put(1601, quote(100, 110));

		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(PortfolioValuer.PLATINUM_TOKEN_ID, 1_000);
		holdings.put(PortfolioValuer.COINS_ID, 50_000);
		holdings.put(1601, 50); // 50 * 100 = 5,000 of genuine stock

		PortfolioValuer.Result r = PortfolioValuer.value(
			1_050_000L, holdings, new HashMap<>(), new ArrayList<>(), quotes);
		Assert.assertEquals("only the real item counts as stock", 5_000L, r.itemsValue);
		Assert.assertEquals(1_050_000L, r.cash);
		Assert.assertEquals(1_055_000L, r.total);
	}
}
