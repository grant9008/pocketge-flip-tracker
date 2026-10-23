package com.pocketge.tracker;

import com.google.gson.Gson;
import java.io.File;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.junit.Assert;
import org.junit.Test;

/**
 * "its remembering an old buy price. it thinks i paid 359 each for these, i
 * only paid 280 each. i lost money but i didnt lose that much money" — the
 * sale already booked, and the way to put it right: "Correct what I paid…"
 * on its row.
 */
public class CorrectPaidTest
{
	private static final int CATALYST = 30771;

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

	@Test
	public void theLedgerRewritesOnlyTheRowsItIsTold() throws Exception
	{
		final File file = new File(Files.createTempDirectory("pge-correct").toFile(), "flips.jsonl");
		final Gson gson = new Gson();
		final String other = gson.toJson(new Flip(7, 1, 2, 1603, "Ruby", 100, 180_000, 200_000, 4_000));
		final String wrong = gson.toJson(new Flip(9, 1, 5, CATALYST, "Aether catalyst", 13_418,
			4_817_062, 3_757_040, 67_090));
		Files.write(file.toPath(), (other + "\n" + "not a flip at all\n" + wrong + "\n")
			.getBytes(StandardCharsets.UTF_8));
		final FlipLedger ledger = new FlipLedger(file, gson);

		final int changed = ledger.rewrite(f -> f.offerId == 9
			? new Flip(f.offerId, f.openedAt, f.closedAt, f.itemId, f.itemName, f.quantity, f.quantity * 277L,
				f.sellGross, f.tax)
			: f);
		Assert.assertEquals(1, changed);
		final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
		Assert.assertEquals("the other row, byte for byte", other, lines.get(0));
		Assert.assertEquals("a line it cannot read, kept", "not a flip at all", lines.get(1));
		final Flip fixed = gson.fromJson(lines.get(2), Flip.class);
		Assert.assertEquals(13_418L * 277, fixed.buySpent);
		Assert.assertEquals(-26_836, fixed.profit);
		Assert.assertFalse("no copy left beside it", new File(file.getPath() + ".tmp").exists());
		Assert.assertEquals("nothing to change, nothing written", 0, ledger.rewrite(f -> f));
	}

	@Test
	public void whatYouTypeIsReadAsANumber()
	{
		Assert.assertEquals(277, HistoryPanel.parsePaid("277"));
		Assert.assertEquals(277, HistoryPanel.parsePaid(" 277 gp "));
		Assert.assertEquals(1_250, HistoryPanel.parsePaid("1,250"));
		Assert.assertEquals(0, HistoryPanel.parsePaid("abc"));
		Assert.assertEquals(0, HistoryPanel.parsePaid(""));
		Assert.assertEquals(0, HistoryPanel.parsePaid(null));
		Assert.assertEquals(0, HistoryPanel.parsePaid("-5"));
	}

	/**
	 * The whole thing: the sale as the old rule booked it, -1.12M, put right
	 * at 277 each — in the file, the cached list, the saved state and the
	 * totals — and another trade left alone.
	 */
	@Test
	public void aBookedSaleIsRepricedEverywhereItIsKept() throws Exception
	{
		final File file = new File(Files.createTempDirectory("pge-correct").toFile(), "flips.jsonl");
		final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
		try
		{
			final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
			set(plugin, "gson", new Gson());
			set(plugin, "executor", ex);
			final FlipTracker tracker = (FlipTracker) get(plugin, "tracker");
			final Gson gson = new Gson();
			final Flip ruby = new Flip(7, 1, 2, 1603, "Ruby", 100, 180_000, 200_000, 4_000);
			/* Booked in two fills of one offer, as a sale often is. */
			final Flip partA = new Flip(9, 1, 5, CATALYST, "Aether catalyst", 6_000, 6_000L * 359, 6_000L * 280, 6_000L * 5);
			final Flip partB = new Flip(9, 1, 6, CATALYST, "Aether catalyst", 7_418, 7_418L * 359, 7_418L * 280, 7_418L * 5);
			Files.write(file.toPath(), (gson.toJson(ruby) + "\n" + gson.toJson(partA) + "\n" + gson.toJson(partB) + "\n")
				.getBytes(StandardCharsets.UTF_8));
			plugin.openLedger(file);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);

			final Flip trade = Flip.merge(List.of(partA, partB));
			Assert.assertEquals(13_418L * (280 - 5 - 359), trade.profit);
			final long lifetimeBefore = tracker.snapshot().lifetimeProfit;

			plugin.correctPaid(trade, 277);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);

			final List<Flip> onDisk = new FlipLedger(file, gson).readAll();
			Assert.assertEquals(3, onDisk.size());
			long catalyst = 0;
			for (Flip f : onDisk)
			{
				if (f.itemId == CATALYST)
				{
					Assert.assertEquals(f.quantity * 277L, f.buySpent);
					catalyst += f.profit;
				}
				else
				{
					Assert.assertEquals("the other trade untouched", ruby.profit, f.profit);
				}
			}
			Assert.assertEquals("the true result", 13_418L * (280 - 5 - 277), catalyst);
			@SuppressWarnings("unchecked")
			final List<Flip> cached = (List<Flip>) get(plugin, "ledgerFlips");
			Assert.assertEquals(catalyst, cached.stream().filter(f -> f.itemId == CATALYST).mapToLong(f -> f.profit).sum());
			Assert.assertEquals("lifetime moves by what the correction changed",
				lifetimeBefore + 13_418L * (359 - 277), tracker.snapshot().lifetimeProfit);
			final java.util.concurrent.atomic.AtomicInteger revision =
				(java.util.concurrent.atomic.AtomicInteger) get(plugin, "historyRevision");
			Assert.assertEquals("the website is told to read it again", 1, revision.get());

			/* Again at the same price: nothing left to change. */
			plugin.correctPaid(trade, 277);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);
			Assert.assertEquals(1, revision.get());
			Assert.assertEquals(lifetimeBefore + 13_418L * (359 - 277), tracker.snapshot().lifetimeProfit);
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	/** A row with no offer token names its fills only by inference: not
	 *  offered, and refused if asked. */
	@Test
	public void aRowWithoutAnOfferTokenIsLeftAlone() throws Exception
	{
		final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
		final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
		try
		{
			set(plugin, "executor", ex);
			plugin.correctPaid(new Flip(0, 1, 5, CATALYST, "Aether catalyst", 10, 3_590, 2_800, 50), 277);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);
			Assert.assertEquals(0, ((java.util.concurrent.atomic.AtomicInteger) get(plugin, "historyRevision")).get());
		}
		finally
		{
			ex.shutdownNow();
		}
	}
}
