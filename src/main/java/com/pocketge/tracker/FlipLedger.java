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

	FlipLedger(File file, Gson gson)
	{
		this.file = file;
		this.gson = gson;
	}

	File file()
	{
		return file;
	}

	/**
	 * Append one closed flip.
	 *
	 * Returns false rather than throwing: a ledger that cannot be written is
	 * a degraded history page, and it must never be able to take down the
	 * booking of the flip itself, which is what the sidebar and the config
	 * state depend on.
	 */
	synchronized boolean append(Flip flip)
	{
		if (flip == null)
		{
			return false;
		}
		try
		{
			final File dir = file.getParentFile();
			if (dir != null && !dir.isDirectory() && !dir.mkdirs())
			{
				return false;
			}
			/* One line, one flush, one close. The newline is written as part
			   of the same call so a line can never be appended without its
			   terminator — that is what makes a torn write cost one flip
			   instead of gluing two together into an unparseable one. */
			try (Writer w = new BufferedWriter(new OutputStreamWriter(
				Files.newOutputStream(file.toPath(), StandardOpenOption.CREATE, StandardOpenOption.APPEND),
				StandardCharsets.UTF_8)))
			{
				w.write(gson.toJson(flip));
				w.write('\n');
			}
			return true;
		}
		catch (IOException | RuntimeException e)
		{
			return false;
		}
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
	synchronized List<Flip> readAll()
	{
		final List<Flip> out = new ArrayList<>();
		if (!file.isFile())
		{
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
		return out;
	}
}
