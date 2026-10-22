package com.pocketge.tracker;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The portfolio's value over time, for pocketge.com's chart: "id like to see
 * my overal progress of bank value going up and down on a chart".
 */
public class PortfolioHistoryTest
{
	private static final long T0 = 1_790_000_000_000L;
	private static final long MIN = 60_000L;
	private static final long HOUR = 60 * MIN;
	private static final long DAY = 24 * HOUR;

	@Rule
	public TemporaryFolder tmp = new TemporaryFolder();

	/** One sample per 15 minutes, however often the panel refreshes. */
	@Test
	public void aSampleEveryFifteenMinutes() throws Exception
	{
		final PortfolioHistory h = new PortfolioHistory(tmp.getRoot());
		Assert.assertTrue(h.record(42L, T0, 148_000_000L));
		Assert.assertFalse("a refresh, not a sample", h.record(42L, T0 + 5 * MIN, 149_000_000L));
		Assert.assertTrue(h.record(42L, T0 + 15 * MIN, 150_000_000L));
		Assert.assertFalse("nothing worth nothing", h.record(42L, T0 + HOUR, 0L));
		final List<long[]> s = h.series(42L, T0 + HOUR);
		Assert.assertEquals(2, s.size());
		Assert.assertEquals(148_000_000L, s.get(0)[1]);
		Assert.assertEquals(150_000_000L, s.get(1)[1]);
	}

	/** Each character has its own, and it survives a restart. */
	@Test
	public void perCharacterAndOnDisk() throws Exception
	{
		final PortfolioHistory h = new PortfolioHistory(tmp.getRoot());
		h.record(42L, T0, 100L);
		h.record(-7L, T0, 900L);
		final PortfolioHistory again = new PortfolioHistory(tmp.getRoot());
		Assert.assertEquals(100L, again.series(42L, T0).get(0)[1]);
		Assert.assertEquals(900L, again.series(-7L, T0).get(0)[1]);
		Assert.assertTrue(again.series(1L, T0).isEmpty());
	}

	/** A torn last line — a client killed mid-write — costs that one line. */
	@Test
	public void aTornLineIsSkipped() throws Exception
	{
		final PortfolioHistory h = new PortfolioHistory(tmp.getRoot());
		final File f = h.fileFor(42L);
		Files.write(f.toPath(), (T0 + ",100\n" + (T0 + HOUR) + ",200\n" + (T0 + 2 * HOUR) + ",3")
			.replace(",3", ",").getBytes(StandardCharsets.UTF_8));
		Assert.assertEquals(2, new PortfolioHistory(tmp.getRoot()).series(42L, T0 + 3 * HOUR).size());
		Assert.assertNull(PortfolioHistory.parse("garbage"));
		Assert.assertNull(PortfolioHistory.parse("-5,10"));
	}

	/** A sample from the future — a clock put back — does not stop the
	 *  next one; and a torn last line never merges into the next sample. */
	@Test
	public void theFutureAndATornLineDoNotStopRecording() throws Exception
	{
		final PortfolioHistory h = new PortfolioHistory(tmp.getRoot());
		Assert.assertTrue(h.record(42L, T0 + 7 * DAY, 100L));
		Assert.assertTrue("after a restart, with the clock back", new PortfolioHistory(tmp.getRoot()).record(42L, T0, 200L));

		Files.write(h.fileFor(7L).toPath(), (T0 + ",100\n1").getBytes(StandardCharsets.UTF_8));
		final PortfolioHistory again = new PortfolioHistory(tmp.getRoot());
		Assert.assertTrue(again.record(7L, T0 + HOUR, 150L));
		final List<long[]> s = new PortfolioHistory(tmp.getRoot()).series(7L, T0 + 2 * HOUR);
		Assert.assertEquals("the torn line stays one stray line, not a sample in the year 2500", 2, s.size());
		Assert.assertEquals(T0 + HOUR, s.get(1)[0]);
	}

	/** Who the chart shows before anyone logs in: the last one sampled. */
	@Test
	public void theLatestCharacterIsTheLastWritten() throws Exception
	{
		final PortfolioHistory h = new PortfolioHistory(tmp.getRoot());
		Assert.assertNull(h.latestAccount());
		h.record(42L, T0, 100L);
		h.fileFor(42L).setLastModified(T0);
		h.record(-7L, T0, 100L);
		h.fileFor(-7L).setLastModified(T0 + HOUR);
		Assert.assertEquals(Long.valueOf(-7L), h.latestAccount());
	}

	/** Everything for 30 days, then hourly, then daily — and the file
	 *  shrinks to match, so years of play stay small. */
	@Test
	public void olderStretchesAreThinned() throws Exception
	{
		final List<long[]> pts = new ArrayList<>();
		final long now = T0 + 400 * DAY;
		for (long t = T0; t <= now; t += 15 * MIN)
		{
			pts.add(new long[]{t, t / MIN});
		}
		final List<long[]> thin = PortfolioHistory.thin(pts, now);
		long full = 0;
		long hourly = 0;
		long daily = 0;
		for (int i = 0; i < thin.size(); i++)
		{
			final long age = now - thin.get(i)[0];
			if (i > 0)
			{
				Assert.assertTrue("oldest first", thin.get(i)[0] > thin.get(i - 1)[0]);
			}
			if (age <= PortfolioHistory.FULL_FOR_MS)
			{
				full++;
			}
			else if (age <= PortfolioHistory.HOURLY_FOR_MS)
			{
				hourly++;
			}
			else
			{
				daily++;
			}
		}
		Assert.assertEquals("every sample of the last 30 days", 30 * 24 * 4 + 1, full);
		Assert.assertEquals("one an hour back to a year", 335 * 24, hourly, 2);
		Assert.assertEquals("one a day before that", 35, daily, 2);
		Assert.assertEquals("the newest is never dropped", now, thin.get(thin.size() - 1)[0]);

		final PortfolioHistory h = new PortfolioHistory(tmp.getRoot());
		final StringBuilder sb = new StringBuilder();
		for (long[] p : pts)
		{
			sb.append(p[0]).append(',').append(p[1]).append('\n');
		}
		Files.write(h.fileFor(9L).toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
		Assert.assertEquals(thin.size(), h.series(9L, now).size());
		Assert.assertEquals("rewritten thin", thin.size(), Files.readAllLines(h.fileFor(9L).toPath()).size());
	}
}
