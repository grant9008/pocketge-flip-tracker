package com.pocketge.tracker;

import com.google.gson.Gson;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Every flip you have ever closed, on disk, forever.
 *
 * The 500-flip cap this exists to lift was never about 500 being enough. Flip
 * history was kept in a RuneLite CONFIG VALUE, which is one string, rewritten
 * whole on every save and synced to your RuneLite account — a place where an
 * unbounded ledger is not merely wasteful but actively hostile to the config
 * service. The cap was the honest response to that storage, so the fix is to
 * change the storage rather than raise the number.
 *
 * Hence a file, and hence this shape:
 *
 * ONE JSON OBJECT PER LINE, APPEND ONLY. A flip is a fact about something that
 * already happened, so nothing here ever needs rewriting — and an append is
 * both cheap (no rewrite of a growing file) and the only write that survives
 * a client killed mid-flush with the rest of the history intact. The worst a
 * crash can leave is a torn final line, which {@link #readAll()} drops.
 *
 * NOT the running state. The config blob keeps doing exactly what it did:
 * lifetime P/L, the open buy lots, and the recent window the sidebar renders.
 * This is purely additive — a second, durable copy of the closed flips — so a
 * user who never opens the history page is unaffected, and one who downgrades
 * loses only the long tail rather than their tracker.
 *
 * Deliberately plain: a File and a Gson, no RuneLite types, so the durability
 * rules above can be tested rather than asserted.
 */
class FlipLedger
{
	/** Skip any line longer than this rather than buffering it. A Flip
	 *  serializes to a couple hundred bytes; something a thousand times that
	 *  is a corrupted file, not a trade. */
	private static final int MAX_LINE_CHARS = 64 * 1024;

	private final File file;
	private final Gson gson;

	/**
	 * Writes, from any client on this computer, one at a time.
	 *
	 * Every RuneLite client shares this file, and a rewrite reads the whole
	 * of it and moves a new copy over it — so a row another client appended
	 * in between would be lost for good, and that client, having counted it
	 * written, would never write it again. A lock on a file beside the
	 * ledger excludes the other processes; this monitor, the other ledgers
	 * in this one (a FileLock is per process, and a second request from the
	 * same process throws rather than waits).
	 */
	private static final Object WRITERS = new Object();

	private interface Write<T>
	{
		T run() throws IOException;
	}

	private <T> T locked(Write<T> body) throws IOException
	{
		synchronized (WRITERS)
		{
			final File dir = file.getParentFile();
			if (dir != null && !dir.isDirectory() && !dir.mkdirs())
			{
				throw new IOException("no directory for the ledger");
			}
			try (java.nio.channels.FileChannel ch = java.nio.channels.FileChannel.open(
				new File(file.getPath() + ".lock").toPath(), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
				java.nio.channels.FileLock ignored = ch.lock())
			{
				return body.run();
			}
		}
	}

	FlipLedger(File file, Gson gson)
	{
		this.file = file;
		this.gson = gson;
	}


	/**
	 * Append one closed flip.
	 *
	 * Returns false rather than throwing: a ledger that cannot be written is
	 * a degraded history page, and it must never be able to take down the
	 * booking of the flip itself, which is what the sidebar and the config
	 * state depend on.
	 */
	/** Whether the file is non-empty and its last byte is not a newline. */
	private boolean endsTorn() throws IOException
	{
		if (!file.isFile() || file.length() == 0)
		{
			return false;
		}
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(file, "r"))
		{
			raf.seek(raf.length() - 1);
			return raf.read() != '\n';
		}
	}

	synchronized boolean append(Flip flip)
	{
		if (flip == null)
		{
			return false;
		}
		try
		{
			return locked(() -> appendLocked(flip));
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
	}

	private boolean appendLocked(Flip flip) throws IOException
	{
		/* A file that does not end in a newline ends in a torn line — a
		   crash mid-write. Finish that line first, or this record is glued
		   onto it and both are lost to the reader. */
		final boolean torn = endsTorn();
		/* One line, one flush, one close. The newline is written as part of
		   the same call so a line can never be appended without its
		   terminator — that is what makes a torn write cost one flip instead
		   of gluing two together into an unparseable one. */
		try (Writer w = new BufferedWriter(new OutputStreamWriter(
			Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND),
			StandardCharsets.UTF_8)))
		{
			if (torn)
			{
				w.write('\n');
			}
			w.write(gson.toJson(flip));
			w.write('\n');
		}
		return true;
	}

	/** Append many, reporting how many landed. Used to seed the ledger from
	 *  the config blob on the first run after upgrading. */
	synchronized int appendAll(List<Flip> flips)
	{
		if (flips == null || flips.isEmpty())
		{
			return 0;
		}
		int n = 0;
		for (Flip f : flips)
		{
			if (append(f))
			{
				n++;
			}
		}
		return n;
	}

	/**
	 * Rewrite the rows {@code fix} changes, keeping every other line exactly
	 * as it is — the one exception to append-only, for a flip booked wrong:
	 * "it thinks i paid 359 each for these, i only paid 280 each". Written
	 * to a copy beside the file and moved over it, so a crash leaves either
	 * the old ledger or the new one, never half of each. A line that does
	 * not parse is carried over untouched; a torn last line gets its newline.
	 * Returns how many rows changed, or -1 when the file could not be
	 * rewritten (and is then as it was).
	 */
	synchronized int rewrite(java.util.function.UnaryOperator<Flip> fix)
	{
		if (!file.isFile())
		{
			return 0;
		}
		try
		{
			return locked(() -> rewriteLocked(fix));
		}
		catch (IOException | RuntimeException e)
		{
			return -1;
		}
	}

	private int rewriteLocked(java.util.function.UnaryOperator<Flip> fix) throws IOException
	{
		final File dir = file.getAbsoluteFile().getParentFile();
		final File tmp = Files.createTempFile(dir.toPath(), file.getName() + ".", ".tmp").toFile();
		int changed = 0;
		try
		{
			final List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
			try (Writer w = new BufferedWriter(new OutputStreamWriter(Files.newOutputStream(tmp.toPath(),
				StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE),
				StandardCharsets.UTF_8)))
			{
				for (String line : lines)
				{
					String out = line;
					if (!line.isEmpty() && line.length() <= MAX_LINE_CHARS)
					{
						try
						{
							final Flip f = gson.fromJson(line, Flip.class);
							final Flip r = f != null ? fix.apply(f) : null;
							if (r != null && r != f)
							{
								out = gson.toJson(r);
								changed++;
							}
						}
						catch (RuntimeException unparseable)
						{
							// carried over as it is
						}
					}
					w.write(out);
					w.write('\n');
				}
			}
			if (changed == 0)
			{
				Files.deleteIfExists(tmp.toPath());
				return 0;
			}
			try
			{
				Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING,
					java.nio.file.StandardCopyOption.ATOMIC_MOVE);
			}
			catch (java.nio.file.AtomicMoveNotSupportedException e)
			{
				Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
			}
			return changed;
		}
		catch (IOException | RuntimeException e)
		{
			try
			{
				Files.deleteIfExists(tmp.toPath());
			}
			catch (IOException ignore)
			{
				// the ledger itself was not touched
			}
			return -1;
		}
	}

	/** True when there is nothing on disk yet — the seed condition, and the
	 *  only thing that decides whether the migration runs. */
	synchronized boolean isEmpty()
	{
		return !file.isFile() || file.length() == 0L;
	}

	/**
	 * How many rows, without parsing any of them.
	 *
	 * Counting bytes rather than deserializing matters at startup: this runs
	 * before the client has finished loading, on a file that is allowed to
	 * grow for years, and the answer is only ever used to tell the website
	 * whether its copy is stale. Parsing tens of thousands of flips to
	 * produce one integer would be a real stall for no gain.
	 */
	synchronized int count()
	{
		if (!file.isFile())
		{
			return 0;
		}
		int lines = 0;
		try (java.io.InputStream in = new java.io.BufferedInputStream(Files.newInputStream(file.toPath())))
		{
			final byte[] buf = new byte[8192];
			int read;
			int last = -1;
			while ((read = in.read(buf)) > 0)
			{
				for (int i = 0; i < read; i++)
				{
					if (buf[i] == '\n')
					{
						lines++;
					}
					last = buf[i];
				}
			}
			/* A final line with no terminator is a torn write, and readAll
			   drops it — so it must not be counted here either, or the site
			   would forever see one more row than it can fetch. */
			if (last != -1 && last != '\n')
			{
				// unterminated tail: not a row
				return lines;
			}
		}
		catch (IOException | RuntimeException e)
		{
			return lines;
		}
		return lines;
	}

	/**
	 * The whole ledger, oldest first.
	 *
	 * Sorted by close time rather than trusted in file order: appends are
	 * handed off the client thread, so two flips booked in the same instant
	 * can land in either order. The page draws a time series from this, and a
	 * cumulative line that steps backwards is a bug you can see.
	 *
	 * Unparseable lines are skipped, not fatal. A ledger with one torn line
	 * from a crash is still a year of history, and refusing to read it would
	 * be the only way that crash could actually cost anything.
	 */
	/** As readAll, but null when the read did not reach the end of the
	 *  file — for callers that must not take a partial read as the whole. */
	synchronized List<Flip> readAllOrNull()
	{
		final List<Flip> out = readAll();
		return lastReadComplete ? out : null;
	}

	/** Whether the most recent readAll got to the end of the file. */
	private boolean lastReadComplete;

	synchronized List<Flip> readAll()
	{
		lastReadComplete = false;
		final List<Flip> out = new ArrayList<>();
		if (!file.isFile())
		{
			lastReadComplete = true; // nothing written yet is a whole read of nothing
			return out;
		}
		try (BufferedReader r = new BufferedReader(new InputStreamReader(
			Files.newInputStream(file.toPath()), StandardCharsets.UTF_8)))
		{
			String line;
			while ((line = r.readLine()) != null)
			{
				if (line.isEmpty() || line.length() > MAX_LINE_CHARS)
				{
					continue;
				}
				final String t = line.trim();
				if (t.isEmpty() || t.charAt(0) != '{')
				{
					continue;
				}
				try
				{
					final Flip f = gson.fromJson(t, Flip.class);
					/* itemId 0 means the object parsed but was not a Flip —
					   Gson is happy to hand back a blank one for any JSON
					   object at all, so the shape has to be checked here. */
					if (f != null && f.itemId > 0)
					{
						out.add(f);
					}
				}
				catch (RuntimeException ignore)
				{
					// torn or foreign line — keep reading
				}
			}
		}
		catch (IOException | RuntimeException e)
		{
			return out; // whatever was read before the failure is still real
		}
		out.sort(Comparator.comparingLong(f -> f.closedAt));
		lastReadComplete = true;
		return out;
	}
}
