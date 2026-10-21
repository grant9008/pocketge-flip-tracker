package com.pocketge.tracker;

import java.util.List;
import org.junit.Assert;
import org.junit.Test;

/** The flip list's rolling window and its "Show all". */
public class RangeListTest
{
	private static final long HOUR = 3_600_000L;

	private static Flip flip(long closedAt)
	{
		return new Flip(closedAt, closedAt - 60_000L, closedAt, 1621, "Uncut emerald", 100, 31_700, 33_400, 700);
	}

	/** The list is redrawn when its oldest flip ages out of a rolling range. */
	@Test
	public void aRollingRangeKnowsWhenItsOldestFlipLeaves()
	{
		final List<Flip> in = List.of(flip(10 * HOUR), flip(10 * HOUR + 600_000L));
		Assert.assertEquals(11 * HOUR + 1, FlipStats.nextExpiry(in, FlipStats.Range.H1));
		Assert.assertEquals("session does not roll", 0, FlipStats.nextExpiry(in, FlipStats.Range.SESSION));
		Assert.assertEquals("nor does all time", 0, FlipStats.nextExpiry(in, FlipStats.Range.ALL));
		Assert.assertEquals("nothing listed, nothing to expire", 0, FlipStats.nextExpiry(List.of(), FlipStats.Range.H1));
	}

	/** The ledger never knows less than the tracker: a flip whose write
	 *  failed, or one booked on another computer, is still counted. */
	@Test
	public void theLedgerIsTopUpWithTheTrackersRecentFlips()
	{
		final Flip a = flip(10 * HOUR);
		final Flip b = flip(11 * HOUR);
		final Flip c = flip(12 * HOUR);
		final List<Flip> merged = PocketGeTrackerPlugin.mergeFlips(List.of(a, c), List.of(b, c));
		Assert.assertEquals(List.of(a, b, c), merged);
		Assert.assertEquals("nothing missing, nothing added", 2,
			PocketGeTrackerPlugin.mergeFlips(List.of(a, c), List.of(c)).size());
	}

	/** Two clients from one saved state mint the same offer tokens; their
	 *  flips of different items stay separate trades. */
	@Test
	public void aSharedTokenNeverJoinsTwoItems()
	{
		final Flip a = new Flip(2, HOUR, 2 * HOUR, 560, "Death rune", 100, 1_000, 2_000, 40);
		final Flip b = new Flip(2, HOUR, 2 * HOUR + 1, 2, "Steel cannonball", 100, 1_000, 2_000, 40);
		Assert.assertEquals(2, Flip.byTrade(List.of(a, b)).size());
	}

	/** A ledger read that failed partway is not taken as the whole file. */
	@Test
	public void aPartialLedgerReadIsNotTakenAsWhole() throws Exception
	{
		final java.io.File dir = java.nio.file.Files.createTempDirectory("pge-ledger").toFile();
		final FlipLedger ledger = new FlipLedger(new java.io.File(dir, "flips.jsonl"), new com.google.gson.Gson());
		Assert.assertNotNull("no file yet is a whole read of nothing", ledger.readAllOrNull());
		Assert.assertTrue(ledger.append(flip(HOUR)));
		Assert.assertEquals(1, ledger.readAllOrNull().size());
	}

	@Test
	public void theLatestFiveThenTheRestOnRequest()
	{
		Assert.assertEquals(5, HistoryPanel.shown(30, false));
		Assert.assertEquals("Show all 30", HistoryPanel.moreText(30, false));
		Assert.assertEquals(30, HistoryPanel.shown(30, true));
		Assert.assertEquals("Show fewer", HistoryPanel.moreText(30, true));
		Assert.assertEquals("nothing to toggle", "", HistoryPanel.moreText(4, false));
		Assert.assertEquals(HistoryPanel.SHOW_ALL_CAP, HistoryPanel.shown(5_000, true));
	}
}
