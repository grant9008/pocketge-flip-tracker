package com.pocketge.tracker;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import org.junit.Assert;
import org.junit.Test;

public class AdvisorTest
{
	private static final long NOW = 1_000_000L;

	private static Map<Integer, Advisor.Quote> quotes(long high, long low)
	{
		Advisor.Quote q = new Advisor.Quote();
		q.high = high;
		q.low = low;
		q.highTime = NOW;
		q.lowTime = NOW;
		Map<Integer, Advisor.Quote> m = new HashMap<>();
		m.put(1601, q);
		return m;
	}

	private static Map<Integer, Advisor.ItemMeta> meta()
	{
		Advisor.ItemMeta m = new Advisor.ItemMeta();
		m.id = 1601;
		m.name = "Diamond";
		m.limit = 100;
		m.dailyVolume = 1_000_000L;
		Map<Integer, Advisor.ItemMeta> out = new HashMap<>();
		out.put(1601, m);
		return out;
	}

	private Advisor.Suggestion sellSuggestion(Map<Integer, long[]> costBasis)
	{
		Map<Integer, Integer> holdings = new HashMap<>();
		holdings.put(1601, 100);
		List<Advisor.Suggestion> out = Advisor.advise(NOW, quotes(2000, 1900), meta(), 0, holdings, new ArrayList<>(),
			new HashSet<>(), new HashSet<>(), 0, 0.01, 4, costBasis);
		return out.stream().filter(s -> s.type == Advisor.Suggestion.Type.SELL).findFirst().orElse(null);
	}

	@Test
	public void sellShowsRealProfitWhenFullyTracked()
	{
		Map<Integer, long[]> costBasis = new HashMap<>();
		costBasis.put(1601, new long[]{100, 150_000L}); // bought 100 @ 1,500 each

		Advisor.Suggestion sell = sellSuggestion(costBasis);
		Assert.assertNotNull(sell);
		long expectedProfit = (2000 - 40) * 100L - 150_000L; // (price - tax) * qty - cost
		Assert.assertEquals(expectedProfit, sell.expectedProfit);
		Assert.assertTrue(sell.reason.contains("profit vs your tracked buy price"));
	}

	@Test
	public void sellSplitsTrackedAndUntrackedPortions()
	{
		Map<Integer, long[]> costBasis = new HashMap<>();
		costBasis.put(1601, new long[]{60, 90_000L}); // only 60 of the 100 held are tracked

		Advisor.Suggestion sell = sellSuggestion(costBasis);
		long trackedProfit = (2000 - 40) * 60L - 90_000L;
		long untrackedValue = (2000 - 40) * 40L;
		Assert.assertEquals(trackedProfit + untrackedValue, sell.expectedProfit);
		Assert.assertTrue(sell.reason.contains("untracked units"));
	}

	@Test
	public void sellFallsBackToValueLanguageWithNoCostBasis()
	{
		Advisor.Suggestion sell = sellSuggestion(null);
		Assert.assertNotNull(sell);
		Assert.assertEquals((2000 - 40) * 100L, sell.expectedProfit);
		Assert.assertTrue(sell.reason.contains("worth ~"));
	}
}
