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
		Assert.assertEquals(253.0, ex.mid1dAgo, 0.001);
		final double pct = ex.dayChangePct(266, 256);
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
		Assert.assertEquals(300.0, MarketClient.extremesFrom(data, NOW).mid1dAgo, 0.001);
	}

	/** Nothing a day ago, or no live price: no figure, not a made-up one. */
	@Test
	public void unknownIsNotAFigure()
	{
		final PriceExtremes none = MarketClient.extremesFrom(new JsonArray(), NOW);
		Assert.assertEquals(0.0, none.mid1dAgo, 0.0);
		Assert.assertTrue(Double.isNaN(none.dayChangePct(266, 256)));
		final PriceExtremes ex = new PriceExtremes();
		ex.mid1dAgo = 253;
		Assert.assertTrue(Double.isNaN(ex.dayChangePct(0, 0)));
		Assert.assertEquals("one live side is the mid", (266 - 253) / 253.0 * 100, ex.dayChangePct(266, 0), 0.001);
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
		final String before = src.substring(Math.max(0, at - 1200), at);
		Assert.assertTrue(before, before.contains("dayChangePct(q.high, q.low, System.currentTimeMillis() / 1000L)"));
		/* The /24h average only for a favourite the hourly series never
		   reaches, and said as such. */
		final int avg = before.indexOf("avgHighPrice");
		Assert.assertTrue(avg > 0);
		Assert.assertTrue(before.substring(0, avg), before.substring(0, avg).contains("!dayExtremesTracked.contains(f.id)"));
		Assert.assertTrue(before.contains("row.changeVsAverage = true;"));
	}

	/** An anchor not refreshed for two hours — logged out, the per-item
	 *  fetches stop — gives no figure rather than a "day ago" that is a day
	 *  and a quarter ago. */
	@Test
	public void aStaleAnchorGivesNoFigure()
	{
		final PriceExtremes ex = new PriceExtremes();
		ex.mid1dAgo = 253;
		ex.fetchedAt = NOW;
		Assert.assertFalse(Double.isNaN(ex.dayChangePct(266, 256, NOW + PriceExtremes.ANCHOR_MAX_AGE_SEC)));
		Assert.assertTrue(Double.isNaN(ex.dayChangePct(266, 256, NOW + PriceExtremes.ANCHOR_MAX_AGE_SEC + 1)));
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
		Assert.assertEquals(253.0, MarketClient.extremesFrom(data, NOW).mid1dAgo, 0.001);
	}
}
