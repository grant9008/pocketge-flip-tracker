package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.Assert;
import org.junit.Test;

/**
 * Every sell card the sidebar offers gets its price series fetched — not just
 * the first one.
 *
 * The plugin used to remember a single item id: whichever SELL Advisor.advise()
 * ranked top. But the sidebar does not show one sell, it shows the whole ranked
 * stack and lets you page through it — so card 1 was priced by TradeEngine and
 * cards 2..n were priced off the last insta-buy print, which is the
 * "quote the actively-traded price and call it a target" mistake the GE-context
 * path already had to be talked out of. Two cards in the same list, built by
 * two different methods, with nothing on either saying which one you got.
 *
 * Two things hold this closed. {@link #everySellCardOnTheBoardIsCovered} says
 * the selection is by rank and covers a board's worth rather than one item, and
 * {@link #aSeriesMovesTheAskOffTheRawPrint} says the coverage is worth having by
 * showing the two paths actually disagree.
 */
public class SellSeriesCoverageTest
{
	private static final long NOW = 1_700_000_000L;

	@Test
	public void everySellCardOnTheBoardIsCovered()
	{
		final List<AdvisorPanel.Rec> sells = new ArrayList<>();
		for (int i = 0; i < 20; i++)
		{
			final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
			r.sell = true;
			r.itemId = 1000 + i;
			sells.add(r);
		}

		final Set<Integer> ids = PocketGeTrackerPlugin.sellSeriesIds(sells);

		Assert.assertEquals("a board's worth, not one item",
			PocketGeTrackerPlugin.SELL_SERIES_CAP, ids.size());
		Assert.assertTrue("the top card is still in there", ids.contains(1000));
		Assert.assertTrue("and so is the one three pages down — this is the regression",
			ids.contains(1002));
		Assert.assertFalse("but not the twentieth: every id is an HTTP call per cycle",
			ids.contains(1019));
	}

	@Test
	public void aShortListIsCoveredWhole()
	{
		final List<AdvisorPanel.Rec> sells = new ArrayList<>();
		final AdvisorPanel.Rec r = new AdvisorPanel.Rec();
		r.sell = true;
		r.itemId = 1656;
		sells.add(r);

		Assert.assertEquals(new HashSet<>(java.util.Collections.singletonList(1656)),
			PocketGeTrackerPlugin.sellSeriesIds(sells));
	}

	@Test
	public void noSellsAsksForNothing()
	{
		Assert.assertTrue(PocketGeTrackerPlugin.sellSeriesIds(new ArrayList<>()).isEmpty());
	}

	/**
	 * The point of covering them: with a series the ask comes from the engine,
	 * without one it is the raw bid. If these ever coincide the test above is
	 * guarding nothing, so this asserts they differ.
	 */
	@Test
	public void aSeriesMovesTheAskOffTheRawPrint()
	{
		final Map<Integer, Advisor.Quote> quotes = new HashMap<>();
		final Advisor.Quote q = new Advisor.Quote();
		/* The bid sits below the series' own range, so the engine's target
		   clears it and sellTarget's never-under-the-bid clamp does not
		   simply hand back rawHigh — without that this test would pass on a
		   plugin that had no engine at all. */
		q.high = 1990;
		q.low = 1900;
		q.highTime = NOW;
		q.lowTime = NOW;
		quotes.put(1601, q);

		final Map<Integer, Advisor.ItemMeta> meta = new HashMap<>();
		final Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = 1601;
		m.name = "Diamond";
		m.limit = 10_000;
		m.dailyVolume = 500_000;
		meta.put(1601, m);

		final Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 1_000);

		final Advisor.Suggestion raw = Advisor.sellCandidates(NOW, quotes, meta, holdings,
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), null, new HashMap<>()).get(0);
		Assert.assertEquals("uncovered: the last insta-buy print, verbatim", 1990, raw.price);

		final Map<Integer, TradeEngine.Series> seriesByItem = new HashMap<>();
		seriesByItem.put(1601, AdvisorTest.syntheticSeries(NOW, 200, 1900, 2000, 11));
		final Advisor.Suggestion priced = Advisor.sellCandidates(NOW, quotes, meta, holdings,
			new ArrayList<>(), new HashSet<>(), new HashSet<>(), null, seriesByItem).get(0);

		Assert.assertNotEquals("covered: the engine's own target, as the website shows it",
			raw.price, priced.price);
		Assert.assertTrue("and never under the standing bid", priced.price >= q.high);
	}
}
