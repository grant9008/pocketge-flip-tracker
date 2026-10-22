package com.pocketge.tracker;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What a character's portfolio was worth, over time, on disk: "id like to
 * see my overal progress of bank value going up and down on a chart
 * somewehre". pocketge.com draws it, over the local bridge.
 *
 * One file per character beside flips.jsonl, one "time,value" line per
 * sample, appended — for the reasons FlipLedger gives: a config value is the
 * wrong home for something that only grows, and an append is the write a
 * crash cannot hurt. A torn last line is skipped on read.
 *
 * A sample at most every {@link #EVERY_MS} while you play. The panel works
 * the value out far more often than that, and a point for every refresh
 * would be a chart of the refresh timer. Older stretches are thinned as the
 * file is read — every point for the last 30 days, the last of each hour
 * before that, the last of each day before a year — and the file rewritten
 * when that drops anything, so a year of play stays a few thousand lines.
 *
 * Plain File and long[] {time, value}, no RuneLite types, so it can be
 * tested.
 */
final class PortfolioHistory
{
	static final long EVERY_MS = 15 * 60_000L;
	private static final long HOUR = 3_600_000L;
	private static final long DAY = 24 * HOUR;
	/** Every sample is kept this long. */
	static final long FULL_FOR_MS = 30 * DAY;
	/** Then one an hour until this age; one a day after it. */
	static final long HOURLY_FOR_MS = 365 * DAY;
	/** A line longer than this is a corrupted file, not a sample. */
	private static final int MAX_LINE = 64;

	private final File dir;
	/** Character -> its samples, oldest first; read from disk on first use. */
	private final Map<Long, List<long[]>> loaded = new HashMap<>();

	PortfolioHistory(File dir)
	{
		this.dir = dir;
	}

	/**
	 * Record {@code value} for {@code account} at {@code now}, unless its
	 * last sample is under {@link #EVERY_MS} old or the value is not a real
	 * one. Returns whether it was recorded. Never throws: a history that
	 * cannot be written is a gap in a chart, nothing worse.
	 */
	synchronized boolean record(long account, long now, long value)
	{
		if (value <= 0)
		{
			return false;
		}
		final List<long[]> pts = load(account, now);
		/* A last sample from the future — a clock put back — is no reason
		   to wait: "under 15 minutes ago" would hold until the clock caught
		   up with it. */
		if (!pts.isEmpty())
		{
			final long last = pts.get(pts.size() - 1)[0];
			if (last <= now && now - last < EVERY_MS)
			{
				return false;
			}
		}
		pts.add(new long[]{now, value});
		try
		{
			Files.createDirectories(dir.toPath());
			final File f = fileFor(account);
			/* A torn last line would run into this one and read back as a
			   single sample centuries ahead; start a fresh line under it. */
			final boolean torn = endsTorn(f);
			try (Writer w = Files.newBufferedWriter(f.toPath(), StandardCharsets.UTF_8,
				StandardOpenOption.CREATE, StandardOpenOption.APPEND))
			{
				w.write((torn ? "\n" : "") + now + "," + value + "\n");
			}
		}
		catch (IOException | RuntimeException e)
		{
			// kept in memory for this session; the file just misses it
		}
		return true;
	}

	/** The character's samples, oldest first, thinned: a copy, {time, value}
	 *  per entry. Empty for a character with none. */
	synchronized List<long[]> series(long account, long now)
	{
		final List<long[]> out = new ArrayList<>();
		for (long[] p : thin(load(account, now), now))
		{
			out.add(p.clone());
		}
		return out;
	}

	/**
	 * Every sample younger than {@link #FULL_FOR_MS}; the last of each hour
	 * up to {@link #HOURLY_FOR_MS}; the last of each day beyond it. Input
	 * oldest first, and so is the output.
	 */
	static List<long[]> thin(List<long[]> pts, long now)
	{
		final List<long[]> out = new ArrayList<>();
		long[] held = null;
		long heldBucket = 0;
		for (long[] p : pts)
		{
			final long age = now - p[0];
			final boolean keep = age <= FULL_FOR_MS;
			/* Odd for an hour, even for a day, so the two never collide. */
			final long bucket = age > HOURLY_FOR_MS ? Math.floorDiv(p[0], DAY) * 2 : Math.floorDiv(p[0], HOUR) * 2 + 1;
			if (held != null && (keep || bucket != heldBucket))
			{
				out.add(held);
				held = null;
			}
			if (keep)
			{
				out.add(p);
			}
			else
			{
				held = p;
				heldBucket = bucket;
			}
		}
		if (held != null)
		{
			out.add(held);
		}
		return out;
	}

	File fileFor(long account)
	{
		return new File(dir, "portfolio-" + Long.toHexString(account) + ".csv");
	}

	/** The character whose file was written last, or null for none — who
	 *  the chart shows before anyone has logged in this session. */
	synchronized Long latestAccount()
	{
		final File[] files = dir.listFiles((d, name) -> name.startsWith("portfolio-") && name.endsWith(".csv"));
		Long best = null;
		long bestAt = Long.MIN_VALUE;
		for (File f : files != null ? files : new File[0])
		{
			final String hex = f.getName().substring("portfolio-".length(), f.getName().length() - ".csv".length());
			try
			{
				final long account = Long.parseUnsignedLong(hex, 16);
				if (f.lastModified() > bestAt)
				{
					bestAt = f.lastModified();
					best = account;
				}
			}
			catch (NumberFormatException e)
			{
				// not one of ours
			}
		}
		return best;
	}

	/** Whether the file is non-empty and does not end in a newline. */
	private static boolean endsTorn(File f) throws IOException
	{
		if (!f.isFile() || f.length() == 0)
		{
			return false;
		}
		try (java.io.RandomAccessFile raf = new java.io.RandomAccessFile(f, "r"))
		{
			raf.seek(raf.length() - 1);
			return raf.read() != '\n';
		}
	}

	private List<long[]> load(long account, long now)
	{
		final List<long[]> have = loaded.get(account);
		if (have != null)
		{
			return have;
		}
		final List<long[]> raw = new ArrayList<>();
		final File f = fileFor(account);
		if (f.isFile())
		{
			try (BufferedReader r = Files.newBufferedReader(f.toPath(), StandardCharsets.UTF_8))
			{
				String line;
				while ((line = r.readLine()) != null)
				{
					final long[] p = parse(line);
					if (p != null)
					{
						raw.add(p);
					}
				}
			}
			catch (IOException | RuntimeException e)
			{
				// what was read is kept; the rest is a gap
			}
		}
		raw.sort((a, b) -> Long.compare(a[0], b[0]));
		/* Nothing more than a day ahead of the clock: two torn lines run
		   together read as one sample centuries out, and kept it would sit
		   at the end of every chart. */
		final int read = raw.size();
		raw.removeIf(p -> p[0] > now + DAY);
		final List<long[]> pts = thin(raw, now);
		if (pts.size() < read)
		{
			rewrite(f, pts);
		}
		loaded.put(account, pts);
		return pts;
	}

	/** {time, value} from one line, or null for anything that is not one —
	 *  a torn last line, a blank, a hand edit. */
	static long[] parse(String line)
	{
		if (line == null || line.length() > MAX_LINE)
		{
			return null;
		}
		final int comma = line.indexOf(',');
		if (comma <= 0)
		{
			return null;
		}
		try
		{
			final long t = Long.parseLong(line.substring(0, comma).trim());
			final long v = Long.parseLong(line.substring(comma + 1).trim());
			return t > 0 && v > 0 ? new long[]{t, v} : null;
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Replace the file with {@code pts}, via a temporary file so a crash
	 *  mid-write leaves the old one whole. */
	private static void rewrite(File f, List<long[]> pts)
	{
		final File tmp = new File(f.getPath() + ".tmp");
		try
		{
			final StringBuilder sb = new StringBuilder();
			for (long[] p : pts)
			{
				sb.append(p[0]).append(',').append(p[1]).append('\n');
			}
			Files.write(tmp.toPath(), sb.toString().getBytes(StandardCharsets.UTF_8));
			Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
		}
		catch (IOException | RuntimeException e)
		{
			// the long file still reads; thinning again next time
		}
	}
}
