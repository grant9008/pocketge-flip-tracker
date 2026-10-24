package com.pocketge.tracker;

import java.util.HashMap;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

/**
 * One stack, one price. "should say +117k gp on that hover": 6,193 Dragon
 * darts read 994 and "+124K" on the hover beside an inspect card at 993 and
 * +117K, Rune darts -110K against -132K, Mithril dart tips 101 against 100,
 * the ice sack 304 against 303. The hovers, the bank marks and the sell
 * cards raised the website's Target Sell to the live insta-buy; the inspect
 * card did not. Asked which: the website's price, everywhere.
 */
public class OneSellPriceTest
{
	private static final long NOW = 1_700_000_000L;
	private static final int ITEM = 1601;

	private static Advisor.Quote quote(long low, long high)
	{
		final Advisor.Quote q = new Advisor.Quote();
		q.low = low;
		q.high = high;
		q.lowTime = NOW;
		q.highTime = NOW;
		return q;
	}

	/** This series has the engine ask a gp under a 2000 insta-buy. */
	private static TradeEngine.Series underTheBid()
	{
		return AdvisorTest.syntheticSeries(NOW, 200, 1900, 2000, 11);
	}

	@Test
	public void theWebsitesTargetSellAsItStands()
	{
		final Advisor.Quote q = quote(1900, 2000);
		final long website = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, underTheBid(), ITEM).sell;
		Assert.assertTrue("the case in hand: the website asks under the bid", website < q.high);
		Assert.assertEquals(website, Advisor.heldSellPrice(ITEM, q, underTheBid()));
		Assert.assertEquals("no series: the last insta-buy", 2000, Advisor.heldSellPrice(ITEM, q, null));
	}

	/** The hover, the sell card, the offer screen and the inspect card's
	 *  ask all name the same number for the same stack. */
	@Test
	public void everySurfaceNamesTheSameNumber()
	{
		final Advisor.Quote q = quote(1900, 2000);
		final TradeEngine.Series s = underTheBid();
		final long website = Advisor.heldSellPrice(ITEM, q, s);

		final Map<Integer, TradeEngine.Series> series = new HashMap<>();
		series.put(ITEM, s);
		Assert.assertEquals("the stack hover", website, PocketGeTrackerPlugin.sellPriceFor(ITEM, q, series));

		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(ITEM, q);
		final Map<Integer, Advisor.ItemMeta> meta = new HashMap<>();
		final Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = ITEM;
		m.name = "Diamond";
		m.limit = 10_000;
		m.dailyVolume = 500_000;
		meta.put(ITEM, m);
		final Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(ITEM, 1_000);
		final Advisor.Suggestion card = Advisor.sellCandidates(NOW, quotes, meta, holdings,
			new java.util.ArrayList<>(), new java.util.HashSet<>(), new java.util.HashSet<>(), null, series).get(0);
		Assert.assertEquals("the sell card and the bank mark", website, card.price);

		final TradeEngine.Result engine = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, s, ITEM);
		Assert.assertEquals("the offer screen", website, PocketGeTrackerPlugin.offerScreenPrice(q, engine, false));
		/* The inspect card asks the watchlist row's targetSell, which is
		   engine.sell for a viable engine: the same expression. */
		Assert.assertTrue(engine.viable);
		Assert.assertEquals("the inspect card", website, engine.sell);
	}

	/** A flat day with no margin: the website's box still shows the
	 *  engine's honest sell, and so does every surface here. */
	private static TradeEngine.Series flat(double low, double high)
	{
		final int n = 200;
		final TradeEngine.Series s = new TradeEngine.Series();
		s.labels = new long[n];
		s.low = new double[n];
		s.high = new double[n];
		s.lowVol = new double[n];
		s.highVol = new double[n];
		for (int i = 0; i < n; i++)
		{
			s.labels[i] = NOW - (n - 1 - i) * 300L;
			s.low[i] = low;
			s.high[i] = high;
			s.lowVol[i] = 50;
			s.highVol[i] = 50;
		}
		return s;
	}

	@Test
	public void noMarginIsStillTheWebsitesSell()
	{
		final Advisor.Quote q = quote(1001, 995);
		final TradeEngine.Series s = flat(1000, 1010);
		final TradeEngine.Result r = TradeEngine.viewTargets(q.low, q.high, q.lowTime, q.highTime, s, ITEM);
		Assert.assertFalse("the case in hand: no margin", r.viable);
		Assert.assertNotEquals("and an engine sell that is not the insta-buy", q.high, r.sell);
		Assert.assertEquals(r.sell, Advisor.heldSellPrice(ITEM, q, s));
		Assert.assertEquals("the offer screen", r.sell, PocketGeTrackerPlugin.offerScreenPrice(q, r, false));
		Assert.assertEquals("a no-margin buy keeps the live print", q.low, PocketGeTrackerPlugin.offerScreenPrice(q, r, true));
	}

	/** The header's Unrealized and the portfolio total mark a stack where
	 *  its card does. */
	@Test
	public void theTotalsMarkAStackWhereItsCardDoes()
	{
		/* An insta-buy far enough under the website's price that the two
		   differ after tax too — 1999 and 2000 both net 1,960. */
		final Advisor.Quote q = quote(1900, 1990);
		final TradeEngine.Series s = underTheBid();
		final long website = Advisor.heldSellPrice(ITEM, q, s);
		final long net = website - FlipTracker.taxPerItem(website, ITEM);
		Assert.assertNotEquals(1990 - FlipTracker.taxPerItem(1990, ITEM), net);
		Assert.assertEquals(net, PortfolioValuer.netExit(q, ITEM, s));
		Assert.assertEquals("no series: the insta-buy", 1990 - FlipTracker.taxPerItem(1990, ITEM),
			PortfolioValuer.netExit(q, ITEM, null));

		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		quotes.put(ITEM, q);
		final Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(ITEM, 1_000);
		final Map<Integer, TradeEngine.Series> series = new HashMap<>();
		series.put(ITEM, s);
		Assert.assertEquals(1_000L * net,
			PortfolioValuer.value(0, holdings, null, null, quotes, series).itemsValue);
		final PortfolioValuer.HeldPosition p = PortfolioValuer.heldPosition(ITEM, 1_000, q, new long[]{1_000, 1_000L * 1_800}, s);
		Assert.assertEquals("the inspect card's gain", 1_000L * net - 1_000L * 1_800, p.heldProfit);
	}

	/** A stack marked because its rating says sell is priced where its card
	 *  and hover are, and says its gain. Every mark but the advisor's one
	 *  best sell came from here at the insta-buy with no gain. */
	@Test
	public void aRatingMarkIsPricedLikeTheStackAndSaysItsGain() throws Exception
	{
		final Advisor.Quote q = quote(1900, 2000);
		final AnalystRating.Grade sell = new AnalystRating.Grade();
		sell.label = AnalystRating.Label.SELL;
		final long website = Advisor.heldSellPrice(ITEM, q, underTheBid());
		final long[] paid = {6_193, 6_193L * 1_800, website};
		final Advisor.Suggestion s = PocketGeTrackerPlugin.ratingSell(ITEM, "Dragon dart", 6_193, q, sell, paid, website);
		Assert.assertNotNull(s);
		Assert.assertEquals(website, s.price);
		Assert.assertTrue(s.hasTrackedCost);
		Assert.assertEquals(BankHighlightOverlay.holdingProfit(ITEM, 6_193, 6_193, 6_193L * 1_800, website), s.expectedProfit);
		final java.lang.reflect.Method tip = BankHighlightOverlay.class
			.getDeclaredMethod("tooltipText", Advisor.Suggestion.class, boolean.class);
		tip.setAccessible(true);
		final String t = (String) tip.invoke(null, s, true);
		Assert.assertTrue(t, t.contains("gp profit available"));
		Assert.assertTrue(t, t.contains("at " + String.format("%,d", website) + " gp each"));

		final Advisor.Suggestion untracked = PocketGeTrackerPlugin.ratingSell(ITEM, "Dragon dart", 6_193, q, sell, null, website);
		Assert.assertFalse("no buy on record: no gain claimed", untracked.hasTrackedCost);
		Assert.assertEquals(website, untracked.price);
	}

	/** A crossed book — the last insta-buy under the last insta-sell — is
	 *  priced at the insta-buy, as everywhere else; the inspect card took
	 *  the insta-sell. */
	@Test
	public void aCrossedBookIsTheInstaBuyEverywhere() throws Exception
	{
		final Advisor.Quote q = quote(102, 100);
		Assert.assertEquals(100, Advisor.heldSellPrice(ITEM, q, null));
		final java.lang.reflect.Method fill = PocketGeTrackerPlugin.class.getDeclaredMethod("fillHeldPosition",
			FavoritesPanel.Row.class, Advisor.Quote.class, Map.class, Map.class, TradeEngine.Series.class);
		fill.setAccessible(true);
		final FavoritesPanel.Row row = new FavoritesPanel.Row();
		row.id = ITEM;
		row.price = q.low;
		row.heldSell = Advisor.heldSellPrice(ITEM, q, null);
		final Map<Integer, Integer> bank = new HashMap<>();
		bank.put(ITEM, 25_000);
		final Map<Integer, long[]> lots = new HashMap<>();
		lots.put(ITEM, new long[]{25_000, 25_000L * 90});
		fill.invoke(null, row, q, bank, lots, null);
		final long net = 100 - FlipTracker.taxPerItem(100, ITEM);
		Assert.assertEquals(25_000L * net, row.sellValue);
		Assert.assertEquals(25_000L * net - 25_000L * 90, row.heldProfit);
	}

	/** A sell that had a series keeps it while it is listed, a few past the
	 *  cap, so it does not drop out, lose its price, climb back and flip
	 *  between two prices every round. */
	@Test
	public void aListedSellKeepsTheSeriesItHad()
	{
		final java.util.List<AdvisorPanel.Rec> recs = new java.util.ArrayList<>();
		for (int i = 1; i <= 15; i++)
		{
			final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
			r.itemId = i;
			recs.add(r);
		}
		final java.util.Set<Integer> first = PocketGeTrackerPlugin.sellSeriesIds(recs, java.util.Set.of());
		Assert.assertEquals(PocketGeTrackerPlugin.SELL_SERIES_CAP, first.size());
		/* Item 8 fell to ninth on its series' lower price. */
		final java.util.List<AdvisorPanel.Rec> next = new java.util.ArrayList<>(recs);
		java.util.Collections.swap(next, 7, 8);
		final java.util.Set<Integer> second = PocketGeTrackerPlugin.sellSeriesIds(next, first);
		Assert.assertTrue("kept", second.contains(8));
		Assert.assertTrue("and the new eighth has one", second.contains(9));
		Assert.assertEquals(PocketGeTrackerPlugin.SELL_SERIES_CAP + 1, second.size());

		final java.util.Set<Integer> had = new java.util.HashSet<>();
		for (int i = 1; i <= 15; i++)
		{
			had.add(i);
		}
		Assert.assertEquals("bounded", PocketGeTrackerPlugin.SELL_SERIES_CAP + PocketGeTrackerPlugin.SELL_SERIES_STICKY,
			PocketGeTrackerPlugin.sellSeriesIds(recs, had).size());
		final java.util.List<AdvisorPanel.Rec> gone = new java.util.ArrayList<>(recs.subList(0, 5));
		Assert.assertFalse("one no longer listed lets it go", PocketGeTrackerPlugin.sellSeriesIds(gone, had).contains(12));
	}

	/** The adjust nudge on a live sell keeps its clamp: an ask at what
	 *  buyers are paying is never told to come down to the website's
	 *  lower target. */
	@Test
	public void theAdjustNudgeNeverSaysToUndercutBuyers()
	{
		final Advisor.Quote q = quote(1900, 2000);
		final long website = Advisor.heldSellPrice(ITEM, q, underTheBid());
		Assert.assertTrue(website < 2000);
		Assert.assertEquals(2000, TradeEngine.sellTarget(website, q.high));
	}
}
