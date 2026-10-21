package com.pocketge.tracker;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * The flip ledger gets each flip once. Three writers — the startup rescue,
 * a flip's own append, and the retry of a failed one — each used to decide
 * for itself what was already in the file, and between them wrote flips
 * twice; a duplicate on disk is counted twice in All time for good.
 */
public class LedgerWriteTest
{
	private static void set(Object o, String field, Object v) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(o, v);
	}

	private static Object get(Object o, String field) throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField(field);
		f.setAccessible(true);
		return f.get(o);
	}

	/** Buy 100 Ruby at 1,800, sell at 2,000 in slot 1: one flip. */
	private static void flipRuby(FlipTracker t)
	{
		t.onOffer(1L, 0, 1603, "Ruby", true, 0, 0L, 1_800, 100, false);
		t.onOffer(2L, 0, 1603, "Ruby", true, 100, 180_000L, 1_800, 100, false);
		t.onOffer(3L, 1, 1603, "Ruby", false, 0, 0L, 2_000, 100, false);
		t.onOffer(4L, 1, 1603, "Ruby", false, 100, 200_000L, 2_000, 100, false);
	}

	private static long rubyRows(File file) throws Exception
	{
		return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8).stream()
			.filter(l -> l.contains("\"itemName\":\"Ruby\"")).count();
	}

	/**
	 * A flip booked while the startup read is still waiting: the read's
	 * rescue writes it, and its own queued append must then skip it.
	 */
	@Test
	public void aFlipBookedDuringTheStartupReadIsWrittenOnce() throws Exception
	{
		final File dir = Files.createTempDirectory("pge-ledger").toFile();
		final File file = new File(dir, "flips.jsonl");
		final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
		try
		{
			final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
			set(plugin, "gson", new Gson());
			set(plugin, "executor", ex);
			/* An earlier flip, so the file is not empty and is not seeded. */
			Files.write(file.toPath(), (new Gson().toJson(new Flip(9, 1, 2, 1601, "Diamond", 1, 1, 2, 0)) + "\n")
				.getBytes(StandardCharsets.UTF_8));
			final CountDownLatch hold = new CountDownLatch(1);
			ex.execute(() ->
			{
				try
				{
					hold.await();
				}
				catch (InterruptedException ignore)
				{
				}
			});
			plugin.openLedger(file);
			flipRuby((FlipTracker) get(plugin, "tracker")); // the sink queues its append behind the read
			hold.countDown();
			ex.shutdown();
			Assert.assertTrue(ex.awaitTermination(10, TimeUnit.SECONDS));
			Assert.assertEquals("one Ruby row on disk", 1, rubyRows(file));
			@SuppressWarnings("unchecked")
			final List<Flip> cached = (List<Flip>) get(plugin, "ledgerFlips");
			Assert.assertEquals("and once in memory", 1, cached.stream().filter(f -> f.itemId == 1603).count());
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	/** A crash mid-write leaves a line with no newline; the next record
	 *  starts a line of its own rather than being glued onto it. */
	@Test
	public void anAppendAfterATornLineStartsItsOwn() throws Exception
	{
		final File file = new File(Files.createTempDirectory("pge-ledger").toFile(), "flips.jsonl");
		Files.write(file.toPath(), "{\"offerId\":2,\"itemId\":16".getBytes(StandardCharsets.UTF_8));
		final FlipLedger ledger = new FlipLedger(file, new Gson());
		Assert.assertTrue(ledger.append(new Flip(4, 7, 10, 1603, "Ruby", 100, 180_000, 200_000, 4_000)));
		final List<Flip> read = ledger.readAll();
		Assert.assertEquals(1, read.size());
		Assert.assertEquals(1603, read.get(0).itemId);
	}
}
