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
		final String[] left = file.getParentFile().list((d, n) -> n.endsWith(".tmp"));
		Assert.assertEquals("no copy left beside it", 0, left == null ? 0 : left.length);
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
		Assert.assertEquals("the game's own shorthand", 1_200, HistoryPanel.parsePaid("1.2k"));
		Assert.assertEquals(45_000_000, HistoryPanel.parsePaid("45m"));
		Assert.assertEquals(2_100_000_000L, HistoryPanel.parsePaid("2.1b"));
		Assert.assertEquals(277, HistoryPanel.parsePaid("277GP"));
		Assert.assertEquals("past what a GE price can be", 0, HistoryPanel.parsePaid("3b"));
		Assert.assertEquals(0, HistoryPanel.parsePaid("0"));
		Assert.assertEquals(0, HistoryPanel.parsePaid("1.2.3"));
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
			final java.util.concurrent.atomic.AtomicLong revision =
				(java.util.concurrent.atomic.AtomicLong) get(plugin, "historyRevision");
			final long revisionBefore = revision.get();

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
			Assert.assertEquals("the website is told to read it again", revisionBefore + 1, revision.get());

			/* Again at the same price: nothing left to change. */
			plugin.correctPaid(trade, 277);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);
			Assert.assertEquals(revisionBefore + 1, revision.get());
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
			final long before = ((java.util.concurrent.atomic.AtomicLong) get(plugin, "historyRevision")).get();
			plugin.correctPaid(new Flip(0, 1, 5, CATALYST, "Aether catalyst", 10, 3_590, 2_800, 50), 277);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);
			Assert.assertEquals(before, ((java.util.concurrent.atomic.AtomicLong) get(plugin, "historyRevision")).get());
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	/**
	 * A sale still selling, corrected between two of its fills, with the
	 * second fill's ledger write queued behind the correction: it lands
	 * corrected, the totals move once, and the slot's "Made" moves with it.
	 */
	@Test
	public void aFillQueuedBehindTheCorrectionIsWrittenCorrected() throws Exception
	{
		final File file = new File(Files.createTempDirectory("pge-correct").toFile(), "flips.jsonl");
		final ScheduledThreadPoolExecutor ex = new ScheduledThreadPoolExecutor(1);
		try
		{
			final PocketGeTrackerPlugin plugin = new PocketGeTrackerPlugin();
			final Gson gson = new Gson();
			set(plugin, "gson", gson);
			set(plugin, "executor", ex);
			final FlipTracker tracker = (FlipTracker) get(plugin, "tracker");
			tracker.setAccountHash(42L);
			Files.write(file.toPath(), (gson.toJson(new Flip(7, 1, 2, 1603, "Ruby", 100, 180_000, 200_000, 4_000)) + "\n")
				.getBytes(StandardCharsets.UTF_8));
			plugin.openLedger(file);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);

			/* Bought at 359 as far as the record knows; selling 1,000 at 280. */
			tracker.onOffer(1, 0, CATALYST, "Aether catalyst", true, 0, 0, 359, 1_000, false);
			tracker.onOffer(2, 0, CATALYST, "Aether catalyst", true, 1_000, 359_000, 359, 1_000, false);
			tracker.onOffer(3, 0, 0, "", false, 0, 0, 0, 0, true);
			tracker.onOffer(4, 1, CATALYST, "Aether catalyst", false, 0, 0, 280, 1_000, false);
			tracker.onOffer(5, 1, CATALYST, "Aether catalyst", false, 600, 600L * 280, 280, 1_000, false);
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);
			final Flip first = tracker.getFlips().get(tracker.getFlips().size() - 1);
			tracker.markShown(1); // the first 600's figure has popped
			final long lifetimeBefore = tracker.snapshot().lifetimeProfit;

			final java.util.concurrent.CountDownLatch hold = new java.util.concurrent.CountDownLatch(1);
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
			plugin.correctPaid(first, 277);
			tracker.onOffer(6, 1, CATALYST, "Aether catalyst", false, 1_000, 1_000L * 280, 280, 1_000, false);
			hold.countDown();
			ex.submit(() -> { }).get(10, TimeUnit.SECONDS);

			long onDisk = 0;
			int rows = 0;
			for (Flip f : new FlipLedger(file, gson).readAll())
			{
				if (f.itemId == CATALYST)
				{
					Assert.assertEquals("every fill at 277", f.quantity * 277L, f.buySpent);
					onDisk += f.profit;
					rows++;
				}
			}
			Assert.assertEquals(2, rows);
			final long truth = 1_000L * (280 - 5 - 277);
			Assert.assertEquals(truth, onDisk);
			Assert.assertEquals("lifetime moved once, to the truth", lifetimeBefore - first.profit + truth,
				tracker.snapshot().lifetimeProfit);
			Assert.assertArrayEquals("the slot's Made moved with it", new long[]{1_000, truth}, tracker.bookedBy(1));
			Assert.assertArrayEquals("the 400 not yet shown pop at their corrected figure, the correction itself not at all",
				new long[]{400, 400L * (280 - 5 - 277), FlipTracker.UNSEEN_PROFIT}, tracker.unseenBy(1, CATALYST));
		}
		finally
		{
			ex.shutdownNow();
		}
	}

	/** Another client's ledger writing the same file while a rewrite runs:
	 *  every row it appended is still there afterwards. */
	@Test
	public void aRewriteNeverLosesAnotherWritersRow() throws Exception
	{
		final File file = new File(Files.createTempDirectory("pge-correct").toFile(), "flips.jsonl");
		final Gson gson = new Gson();
		final FlipLedger a = new FlipLedger(file, gson);
		final FlipLedger b = new FlipLedger(file, gson);
		Assert.assertTrue(a.append(new Flip(9, 1, 5, CATALYST, "Aether catalyst", 10, 3_590, 2_800, 50)));
		final Thread writer = new Thread(() ->
		{
			for (int i = 0; i < 200; i++)
			{
				b.append(new Flip(1000 + i, 1, 10 + i, 1603, "Ruby", 1, 1_800, 2_000, 40));
			}
		});
		writer.start();
		for (int i = 0; i < 50; i++)
		{
			final long cost = 270 + (i % 2) * 7;
			a.rewrite(f -> f.offerId == 9 ? new Flip(9, 1, 5, CATALYST, "Aether catalyst", 10, 10 * cost, 2_800, 50) : f);
		}
		writer.join();
		final List<Flip> all = a.readAll();
		Assert.assertEquals("every appended row, and the corrected one", 201, all.size());
	}

	/** Right-clicking a row opens its menu and nothing else; a left click
	 *  still opens the chart. */
	@Test
	public void aRightClickDoesNotAlsoOpenTheChart()
	{
		final List<String> charts = new java.util.ArrayList<>();
		final HistoryPanel panel = new HistoryPanel(new HistoryPanel.Actions()
		{
			@Override
			public void openChart(String itemName)
			{
				charts.add(itemName);
			}
		});
		panel.update(List.of(new Flip(9, 1, System.currentTimeMillis(), CATALYST, "Aether catalyst", 10, 3_590, 2_800, 50)),
			FlipStats.Range.ALL);
		final javax.swing.JLabel name = find(panel, "Aether catalyst");
		Assert.assertNotNull(name);
		name.dispatchEvent(new java.awt.event.MouseEvent(name, java.awt.event.MouseEvent.MOUSE_CLICKED,
			System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON3_DOWN_MASK, 5, 5, 1, false,
			java.awt.event.MouseEvent.BUTTON3));
		Assert.assertTrue("right click: no chart", charts.isEmpty());
		name.dispatchEvent(new java.awt.event.MouseEvent(name, java.awt.event.MouseEvent.MOUSE_CLICKED,
			System.currentTimeMillis(), java.awt.event.InputEvent.BUTTON1_DOWN_MASK, 5, 5, 1, false,
			java.awt.event.MouseEvent.BUTTON1));
		Assert.assertEquals("left click: the chart", List.of("Aether catalyst"), charts);
	}

	private static javax.swing.JLabel find(java.awt.Container root, String text)
	{
		for (java.awt.Component c : root.getComponents())
		{
			if (c instanceof javax.swing.JLabel && text.equals(((javax.swing.JLabel) c).getText()))
			{
				return (javax.swing.JLabel) c;
			}
			if (c instanceof java.awt.Container)
			{
				final javax.swing.JLabel f = find((java.awt.Container) c, text);
				if (f != null)
				{
					return f;
				}
			}
		}
		return null;
	}
}
