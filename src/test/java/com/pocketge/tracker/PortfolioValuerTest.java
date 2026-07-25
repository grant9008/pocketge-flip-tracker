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
}
