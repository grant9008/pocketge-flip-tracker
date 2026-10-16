package com.pocketge.tracker;

import org.junit.Assert;
import org.junit.Test;

/**
 * How long the plugin stays away from the wiki after being told to.
 *
 * Before this a refusal changed nothing: the next cycle asked again on
 * schedule and every per-item loop carried on through its list, each request
 * refused in turn. The OSRS Wiki has started naming tools whose traffic it
 * has to absorb, and a client that ignores "too many requests" is the kind it
 * names.
 */
public class MarketBackoffTest
{
	private static final long MIN = 60_000;

	/** A minute first, doubling with each refusal in a row. */
	@Test
	public void doublesFromAMinute()
	{
		Assert.assertEquals(MIN, MarketClient.backoffMs(0, null, 0));
		Assert.assertEquals(2 * MIN, MarketClient.backoffMs(1, null, 0));
		Assert.assertEquals(4 * MIN, MarketClient.backoffMs(2, null, 0));
		Assert.assertEquals(16 * MIN, MarketClient.backoffMs(4, null, 0));
	}

	/** And stops at half an hour, however long the refusals go on. */
	@Test
	public void capsAtHalfAnHour()
	{
		Assert.assertEquals(30 * MIN, MarketClient.backoffMs(5, null, 0));
		Assert.assertEquals(30 * MIN, MarketClient.backoffMs(40, null, 0));
		Assert.assertEquals("no overflow from a huge streak",
			30 * MIN, MarketClient.backoffMs(Integer.MAX_VALUE, null, 0));
	}

	/** The server's own number wins when it gives one in seconds. */
	@Test
	public void honoursRetryAfter()
	{
		Assert.assertEquals(120_000, MarketClient.backoffMs(0, "120", 0));
		Assert.assertEquals("even on a long streak", 5_000, MarketClient.backoffMs(9, " 5 ", 0));
	}

	/** Within reason: a Retry-After of a day is held to an hour. */
	@Test
	public void capsRetryAfterAtAnHour()
	{
		Assert.assertEquals(60 * MIN, MarketClient.backoffMs(0, "86400", 0));
	}

	/** An HTTP-date, a zero or garbage falls back to the doubling rather
	 *  than to no wait at all. */
	@Test
	public void unreadableRetryAfterStillWaits()
	{
		Assert.assertEquals(MIN, MarketClient.backoffMs(0, "Wed, 21 Oct 2026 07:28:00 GMT", 0));
		Assert.assertEquals(MIN, MarketClient.backoffMs(0, "0", 0));
		Assert.assertEquals(2 * MIN, MarketClient.backoffMs(1, "soon", 0));
	}

	/** Up to a fifth more at random, so clients refused together do not all
	 *  come back on the same second. Never less than the base. */
	@Test
	public void spreadsClientsOut()
	{
		Assert.assertEquals(MIN, MarketClient.backoffMs(0, null, 0));
		Assert.assertEquals(MIN + MIN / 10, MarketClient.backoffMs(0, null, 0.5));
		Assert.assertTrue(MarketClient.backoffMs(0, null, 0.999) < MIN + MIN / 5 + 1);
		Assert.assertEquals("a bad random number cannot shorten the wait",
			MIN, MarketClient.backoffMs(0, null, -3));
	}
}
