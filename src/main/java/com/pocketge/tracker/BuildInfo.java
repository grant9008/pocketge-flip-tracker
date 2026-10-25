package com.pocketge.tracker;

import java.io.File;
import java.net.URI;
import java.net.URL;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;

/**
 * Which build is running, for the report and the bottom of the gear menu.
 *
 * Every build said 0.7.1 for weeks while the code under it changed daily,
 * so a screenshot could not say whether it came from the hub's build or a
 * newer one off main — and the two priced the same stack 375K apart. The
 * version alone cannot tell two dev builds apart, so the time the classes
 * were compiled goes with it.
 */
final class BuildInfo
{
	/** Keep in step with runelite-plugin.properties (the one the hub shows)
	 *  and build.gradle; BuildInfoTest holds all three together. One patch
	 *  on per update, with tools/dev/bump-version.sh, so the footer says
	 *  which update this is — "like personal space has version 1.9.37 way
	 *  easier for me to confirm updates / latest version im on". */
	static final String VERSION = "0.7.39";

	private static final long NOT_BEFORE = 1_577_836_800_000L; // 2020-01-01: a zeroed or reproducible (1980) stamp

	private static volatile String built;

	private BuildInfo()
	{
	}

	/** When this build's classes were written, as "2026-10-20 at 14:03 UTC",
	 *  or "" when the packaging did not keep a time. UTC, like the report's
	 *  "made" line, so two players' screenshots compare. */
	static String built()
	{
		String b = built;
		if (b == null)
		{
			final long at = builtAt();
			b = at > NOT_BEFORE
				? DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm 'UTC'").format(Instant.ofEpochMilli(at).atZone(ZoneOffset.UTC))
				: "";
			built = b;
		}
		return b;
	}

	/**
	 * From a jar (the hub's build): the class ENTRY's own time. Not the jar
	 * file's — RuneLite touches every installed hub jar on each start, so
	 * that would read as "built when you launched". A packager that builds
	 * reproducibly zeroes entry times (1980), and then this reads as no
	 * time: such builds are told apart by version alone.
	 *
	 * From a classes directory (a dev build): the newest class file in the
	 * package. Gradle compiles incrementally, so BuildInfo.class alone keeps
	 * its old time when only other files changed.
	 */
	/**
	 * The time on the jar entry a {@code jar:file:…!/entry} URL names, or 0.
	 *
	 * Read by opening the jar as a file, not through the URL's connection:
	 * the plugin hub's scan refuses a URL connection anywhere in a plugin and
	 * asks for RuneLite's OkHttpClient instead — which has nothing to do with
	 * reading a file off the disk, so the file is opened directly. Our own
	 * JarFile, closed here, so the class loader's is never touched.
	 */
	static long jarEntryTime(URL url) throws Exception
	{
		if (url == null || !"jar".equals(url.getProtocol()))
		{
			return 0;
		}
		final String path = url.getPath(); // file:/.../plugin.jar!/com/.../BuildInfo.class
		final int bang = path.indexOf("!/");
		if (bang <= 0)
		{
			return 0;
		}
		try (JarFile jar = new JarFile(new File(URI.create(path.substring(0, bang)))))
		{
			final ZipEntry e = jar.getEntry(path.substring(bang + 2));
			return e != null ? e.getTime() : 0;
		}
	}

	private static long builtAt()
	{
		try
		{
			final URL url = BuildInfo.class.getResource("BuildInfo.class");
			if (url == null)
			{
				return 0;
			}
			if ("file".equals(url.getProtocol()))
			{
				final File[] classes = new File(url.toURI()).getParentFile().listFiles((d, n) -> n.endsWith(".class"));
				long newest = 0;
				for (File f : classes != null ? classes : new File[0])
				{
					newest = Math.max(newest, f.lastModified());
				}
				return newest;
			}
			return jarEntryTime(url);
		}
		catch (Exception | LinkageError ignore)
		{
			// no stamp is a fine answer
		}
		return 0;
	}

	/** "0.7.2, built 2026-10-20 at 14:03 UTC", or just the version. */
	static String label()
	{
		final String b = built();
		return b.isEmpty() ? VERSION : VERSION + ", built " + b;
	}
}
