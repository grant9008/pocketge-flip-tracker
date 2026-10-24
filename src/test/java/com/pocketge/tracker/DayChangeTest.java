package com.pocketge.tracker;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.junit.Assert;
import org.junit.Test;

/**
 * "plugin showing -19% on day sunfire splinters. but website is showing +3%
 * green 1day. which is true?" The website's. The watchlist badge measured the
 * live mid against the Wiki's /24h average, which is the last COMPLETE day's:
 * after a fall it still holds the day before. It now measures what the
 * website's 1D button does: the live mid against the mid 24 hours ago.
 */
public class DayChangeTest
{
	private static final long NOW = 1_800_000_000L;

	private static JsonObject hour(long ts, long low, long high)
	{
		final JsonObject o = new JsonObject();
		o.addProperty("timestamp", ts);
		if (high > 0)
		{
			o.addProperty("avgHighPrice", high);
		}
		if (low > 0)
		{
			o.addProperty("avgLowPrice", low);
		}
		return o;
	}

	/** Sunfire splinters: 330 two days ago, a fall to the 250s by a day
	 *  ago, 256/266 now. The day's change is about +3%, not -19%. */
	@Test
	public void theDaysChangeIsAgainstADayAgoNotYesterdaysAverage()
	{
		final JsonArray data = new JsonArray();
		for (int h = 48; h > 24; h--)
		{
			data.add(hour(NOW - h * 3600L, 325, 335)); // the day before: what /24h averaged
		}
		data.add(hour(NOW - 24 * 3600L + 600, 248, 258)); // the first hour of the day
		for (int h = 23; h >= 1; h--)
		{
			data.add(hour(NOW - h * 3600L, 240, 270));
		}
		final PriceExtremes ex = MarketClient.extremesFrom(data, NOW);
		Assert.assertEquals(253.0, ex.midDayBefore(NOW), 0.001);
		final double pct = ex.dayChangePct(266, 256, NOW);
		Assert.assertEquals((261.0 - 253.0) / 253.0 * 100, pct, 0.001);
		Assert.assertTrue("+3%, as the website says", pct > 3 && pct < 3.3);
	}

	/** A one-sided hour counts at the side there is, as app.js's pctChange
	 *  takes it; an hour with neither is skipped. */
	@Test
	public void theFirstPricedHourOfTheDay()
	{
		final JsonArray data = new JsonArray();
		data.add(hour(NOW - 23 * 3600L, 0, 0));
		data.add(hour(NOW - 22 * 3600L, 0, 300));
		data.add(hour(NOW - 21 * 3600L, 280, 290));
		Assert.assertEquals(300.0, MarketClient.extremesFrom(data, NOW).midDayBefore(NOW), 0.001);
	}

	/** Nothing a day ago, or no live price: no figure, not a made-up one. */
	@Test
	public void unknownIsNotAFigure()
	{
		final PriceExtremes none = MarketClient.extremesFrom(new JsonArray(), NOW);
		Assert.assertEquals(0.0, none.midDayBefore(NOW), 0.0);
		Assert.assertTrue(Double.isNaN(none.dayChangePct(266, 256, NOW)));
		Assert.assertTrue(Double.isNaN(new PriceExtremes().dayChangePct(266, 256, NOW)));
		final JsonArray data = new JsonArray();
		data.add(hour(NOW - 20 * 3600L, 248, 258));
		final PriceExtremes ex = MarketClient.extremesFrom(data, NOW);
		Assert.assertTrue(Double.isNaN(ex.dayChangePct(0, 0, NOW)));
		Assert.assertEquals("one live side is the mid", (266 - 253) / 253.0 * 100, ex.dayChangePct(266, 0, NOW), 0.001);
		Assert.assertEquals("and the other", (240 - 253) / 253.0 * 100, ex.dayChangePct(0, 240, NOW), 0.001);
	}

	/** The watchlist row, which drives the spike badge and the price alert,
	 *  takes the day's change — not the /24h average. Read from the source:
	 *  the row is built on a running client. */
	@Test
	public void theWatchlistRowTakesTheDaysChange() throws Exception
	{
		final String src = java.nio.file.Files.readString(
			java.nio.file.Path.of("src/main/java/com/pocketge/tracker/PocketGeTrackerPlugin.java"))
			.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
		final int at = src.indexOf("row.changePct =");
		Assert.assertTrue(at > 0);
		Assert.assertEquals("set in one place", at, src.lastIndexOf("row.changePct ="));
		final String before = src.substring(Math.max(0, at - 1400), at);
		/* By place in the list: the day's change for the first
		   MAX_EXTREME_FAVORITES, asked for when missing — never the /24h
		   figure in its place — and the /24h average, said as such, for the
		   rest. */
		final int reach = before.indexOf("if (dayReach.contains(f.id))");
		final int avg = before.indexOf("avgHighPrice");
		Assert.assertTrue(before, reach > 0 && avg > reach);
		final String inReach = before.substring(reach, avg);
		Assert.assertTrue(inReach, inReach.contains("day.dayChangePct(q.high, q.low, nowSec)"));
		Assert.assertTrue(inReach, inReach.contains("missingDay.add(f.id);"));
		Assert.assertTrue(inReach, inReach.endsWith("else if (q.high > 0 && avg != null && avg."));
		Assert.assertTrue(before.substring(avg), before.substring(avg).contains("row.changeVsAverage = true;"));
		Assert.assertTrue(src.contains("final Set<Integer> dayReach = dayChangeFavourites(activeList);"));
		Assert.assertTrue(src.contains("final Set<Integer> favIds = dayChangeFavourites(activeFavoriteList(loadFavoriteLists()));"));
		Assert.assertTrue("the missing are asked for", src.contains("requestDayExtremes(missingDay);"));
		/* Alerts for the watchlist only: the inspected item rides along. */
		final String after = src.substring(at, at + 200);
		Assert.assertTrue(after, after.matches("(?s)row\\.changePct = change;\\s*if \\(onList\\)\\s*\\{\\s*maybeAlertOnMove\\(row\\);.*"));
	}

	/** The first MAX_EXTREME_FAVORITES of the list, in its order, once each. */
	@Test
	public void theReachIsTheFirstOfTheList()
	{
		final FavoriteLists.FavoriteList list = new FavoriteLists.FavoriteList();
		list.items = new java.util.ArrayList<>();
		for (int i = 0; i < PocketGeTrackerPlugin.MAX_EXTREME_FAVORITES + 5; i++)
		{
			list.items.add(new Favorites.Fav(1000 + i, "item " + i));
		}
		list.items.add(1, new Favorites.Fav(1000, "a duplicate"));
		final java.util.Set<Integer> reach = PocketGeTrackerPlugin.dayChangeFavourites(list);
		Assert.assertEquals(PocketGeTrackerPlugin.MAX_EXTREME_FAVORITES, reach.size());
		Assert.assertEquals(Integer.valueOf(1000), reach.iterator().next());
		Assert.assertTrue(reach.contains(1000 + PocketGeTrackerPlugin.MAX_EXTREME_FAVORITES - 1));
		Assert.assertFalse(reach.contains(1000 + PocketGeTrackerPlugin.MAX_EXTREME_FAVORITES));
		Assert.assertTrue(PocketGeTrackerPlugin.dayChangeFavourites(null).isEmpty());
	}

	/** "A day ago" moves with the clock, not with the fetch. The day's
	 *  hours are kept, so six hours after the fetch the anchor is the first
	 *  priced hour from 18 hours before it — what a fresh fetch would give —
	 *  until the hours held no longer reach back a day: then no figure. */
	@Test
	public void aDayAgoMovesWithTheClock()
	{
		final JsonArray data = new JsonArray();
		data.add(hour(NOW - 30 * 3600L, 100, 110)); // older than a day: not kept
		for (int h = 24; h >= 1; h--)
		{
			data.add(hour(NOW - h * 3600L, 200 + h * 10, 210 + h * 10));
		}
		data.add(hour(NOW - 17 * 3600L - 1800, 0, 0)); // nothing traded: skipped
		final PriceExtremes ex = MarketClient.extremesFrom(data, NOW);
		Assert.assertEquals("at the fetch", 445.0, ex.midDayBefore(NOW), 0.001);
		Assert.assertEquals("six hours on", 385.0, ex.midDayBefore(NOW + 6 * 3600L), 0.001);
		Assert.assertEquals("between hours: the next one", 375.0, ex.midDayBefore(NOW + 6 * 3600L + 1), 0.001);
		Assert.assertEquals((255.0 - 385.0) / 385.0 * 100, ex.dayChangePct(260, 250, NOW + 6 * 3600L), 0.001);
		Assert.assertEquals("the last hour held", 215.0, ex.midDayBefore(NOW + 23 * 3600L), 0.001);
		Assert.assertEquals("past it", 0.0, ex.midDayBefore(NOW + 23 * 3600L + 1), 0.0);
		Assert.assertTrue(Double.isNaN(ex.dayChangePct(260, 250, NOW + 24 * 3600L)));
		Assert.assertEquals("stamped when built from a fetch", NOW,
			MarketClient.extremesFrom(new JsonArray(), NOW).fetchedAt);
	}

	/** Out of order, as nothing promises they arrive sorted: still the
	 *  earliest hour of the day. */
	@Test
	public void theEarliestHourWhateverTheOrder()
	{
		final JsonArray data = new JsonArray();
		data.add(hour(NOW - 2 * 3600L, 400, 410));
		data.add(hour(NOW - 20 * 3600L, 248, 258));
		data.add(hour(NOW - 30 * 3600L, 100, 110)); // older than a day: not in it
		Assert.assertEquals(253.0, MarketClient.extremesFrom(data, NOW).midDayBefore(NOW), 0.001);
	}
}
