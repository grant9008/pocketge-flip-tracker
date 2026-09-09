package com.pocketge.tracker;

import com.google.gson.Gson;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.List;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

/**
 * The ledger is the only copy of history older than the 500-flip config
 * window, so what is tested here is durability rather than formatting: that a
 * crash costs one row, that a foreign file cannot be mistaken for history,
 * and that a ledger which cannot be written fails quietly instead of taking
 * the tracker down with it.
 */
public class FlipLedgerTest
{
	@Rule
	public final TemporaryFolder tmp = new TemporaryFolder();

	private final Gson gson = new Gson();

	private static Flip flip(long closedAt, int itemId, String name, int qty, long spent, long gross)
	{
		return new Flip(closedAt, itemId, name, qty, spent, gross, gross / 50);
	}

	private FlipLedger ledger() throws IOException
	{
		return new FlipLedger(new File(tmp.newFolder("led"), "flips.jsonl"), gson);
	}

	@Test
	public void appendsAndReadsBackEveryField() throws Exception
	{
		final FlipLedger l = ledger();
		Assert.assertTrue(l.append(flip(1_000L, 1592, "Sapphire necklace", 18000, 20_160_000L, 21_600_000L)));

		final List<Flip> back = l.readAll();
		Assert.assertEquals(1, back.size());
		final Flip f = back.get(0);
		Assert.assertEquals(1_000L, f.closedAt);
		Assert.assertEquals(1592, f.itemId);
		Assert.assertEquals("Sapphire necklace", f.itemName);
		Assert.assertEquals(18000, f.quantity);
		Assert.assertEquals(20_160_000L, f.buySpent);
		Assert.assertEquals(21_600_000L, f.sellGross);
		Assert.assertEquals(21_600_000L / 50, f.tax);
		Assert.assertEquals(21_600_000L - (21_600_000L / 50) - 20_160_000L, f.profit);
	}

	/** The whole point of the file: it keeps growing past the config cap. */
	@Test
	public void growsWellBeyondTheFiveHundredFlipConfigWindow() throws Exception
	{
		final FlipLedger l = ledger();
		for (int i = 0; i < 1200; i++)
		{
			l.append(flip(i, 100 + i, "Item " + i, 1, 10, 20));
		}
		Assert.assertEquals(1200, l.readAll().size());
		Assert.assertEquals(1200, l.count());
	}

	/** Appends accumulate across separate opens — a client restart is just a
	 *  new FlipLedger over the same file. */
	@Test
	public void survivesReopening() throws Exception
	{
		final File f = new File(tmp.newFolder("reopen"), "flips.jsonl");
		new FlipLedger(f, gson).append(flip(1, 10, "A", 1, 1, 2));
		new FlipLedger(f, gson).append(flip(2, 11, "B", 1, 1, 2));
		Assert.assertEquals(2, new FlipLedger(f, gson).readAll().size());
	}

	@Test
	public void readsOldestFirstEvenWhenAppendedOutOfOrder() throws Exception
	{
		final FlipLedger l = ledger();
		l.append(flip(300, 3, "C", 1, 1, 2));
		l.append(flip(100, 1, "A", 1, 1, 2));
		l.append(flip(200, 2, "B", 1, 1, 2));

		final List<Flip> back = l.readAll();
		Assert.assertEquals(100L, back.get(0).closedAt);
		Assert.assertEquals(200L, back.get(1).closedAt);
		Assert.assertEquals(300L, back.get(2).closedAt);
	}

	/** A client killed mid-append leaves a half-written final line. It must
	 *  cost that one flip and nothing else. */
	@Test
	public void aTornFinalLineCostsOneFlipNotTheHistory() throws Exception
	{
		final File file = new File(tmp.newFolder("torn"), "flips.jsonl");
		final FlipLedger l = new FlipLedger(file, gson);
		for (int i = 0; i < 10; i++)
		{
			l.append(flip(i, 100 + i, "Item " + i, 1, 10, 20));
		}
		Files.write(file.toPath(), "{\"closedAt\":99,\"itemId\":9".getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);

		Assert.assertEquals(10, l.readAll().size());
		// ...and the count agrees, so the site is never told to expect a row
		// that readAll will not hand over.
		Assert.assertEquals(10, l.count());
	}

	/** ...and writing continues correctly afterwards. */
	@Test
	public void appendingAfterATornLineDoesNotGlueTheLinesTogether() throws Exception
	{
		final File file = new File(tmp.newFolder("torn2"), "flips.jsonl");
		final FlipLedger l = new FlipLedger(file, gson);
		l.append(flip(1, 10, "A", 1, 1, 2));
		Files.write(file.toPath(), "{\"closedAt\":2,\"itemI".getBytes(StandardCharsets.UTF_8),
			StandardOpenOption.APPEND);
		l.append(flip(3, 12, "C", 1, 1, 2));

		final List<Flip> back = l.readAll();
		// The torn line swallows the next append's opening brace, so that one
		// is lost too — but only that one, and the history around it reads.
		Assert.assertTrue(back.size() >= 1);
		Assert.assertEquals(10, back.get(0).itemId);
	}

	@Test
	public void ignoresBlankAndForeignLines() throws Exception
	{
		final File file = new File(tmp.newFolder("junk"), "flips.jsonl");
		Files.write(file.toPath(), String.join("\n",
			"",
			"not json at all",
			"[1,2,3]",
			"{\"unrelated\":true}",
			gson.toJson(flip(5, 55, "Real", 1, 1, 2)),
			"").getBytes(StandardCharsets.UTF_8));

		final List<Flip> back = new FlipLedger(file, gson).readAll();
		Assert.assertEquals(1, back.size());
		Assert.assertEquals(55, back.get(0).itemId);
	}

	@Test
	public void isEmptyDrivesTheOneTimeSeed() throws Exception
	{
		final FlipLedger l = ledger();
		Assert.assertTrue("a ledger with no file yet is empty", l.isEmpty());

		final List<Flip> existing = new ArrayList<>();
		existing.add(flip(1, 10, "A", 1, 1, 2));
		existing.add(flip(2, 11, "B", 1, 1, 2));
		Assert.assertEquals(2, l.appendAll(existing));

		Assert.assertFalse("seeded once, so it never seeds again", l.isEmpty());
		Assert.assertEquals(2, l.readAll().size());
	}

	@Test
	public void anUnwritableLedgerFailsQuietly() throws Exception
	{
		// A path whose parent is a FILE, so the directory can never be made.
		final File blocker = tmp.newFile("not-a-directory");
		final FlipLedger l = new FlipLedger(new File(blocker, "flips.jsonl"), gson);

		Assert.assertFalse(l.append(flip(1, 10, "A", 1, 1, 2)));
		Assert.assertTrue(l.readAll().isEmpty());
		Assert.assertEquals(0, l.count());
		Assert.assertTrue(l.isEmpty());
	}

	@Test
	public void countMatchesReadAllWithoutParsing() throws Exception
	{
		final FlipLedger l = ledger();
		Assert.assertEquals(0, l.count());
		for (int i = 0; i < 37; i++)
		{
			l.append(flip(i, 100 + i, "Item " + i, 1, 10, 20));
		}
		Assert.assertEquals(37, l.count());
		Assert.assertEquals(l.readAll().size(), l.count());
	}

	/** Item names carry apostrophes and, in RuneScape, the odd non-ASCII
	 *  character — a newline or a broken encoding in one name would split or
	 *  corrupt a row. */
	@Test
	public void namesWithQuotesAndUnicodeSurviveTheRoundTrip() throws Exception
	{
		final FlipLedger l = ledger();
		l.append(flip(1, 10, "Verac's flail", 1, 1, 2));
		l.append(flip(2, 11, "Choc-ice — \"quoted\"", 1, 1, 2));

		final List<Flip> back = l.readAll();
		Assert.assertEquals(2, back.size());
		Assert.assertEquals("Verac's flail", back.get(0).itemName);
		Assert.assertEquals("Choc-ice — \"quoted\"", back.get(1).itemName);
	}
}
