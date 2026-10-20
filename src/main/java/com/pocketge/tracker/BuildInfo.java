package com.pocketge.tracker;

import java.net.URL;
import java.net.URLConnection;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

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
	 *  and build.gradle; BuildInfoTest holds all three together. */
	static final String VERSION = "0.7.2";

	private static final long NOT_BEFORE = 1_577_836_800_000L; // 2020-01-01: a zeroed or "reproducible" stamp

	private static volatile String built;

	private BuildInfo()
	{
	}

	/** When this class file was written — compile time for a dev build, the
	 *  jar entry's time for the hub's — as "2026-10-20 at 14:03", local time, or
	 *  "" when the packaging did not keep one. */
	static String built()
	{
		String b = built;
		if (b == null)
		{
			b = "";
			try
			{
				final URL url = BuildInfo.class.getResource("BuildInfo.class");
				if (url != null)
				{
					final URLConnection c = url.openConnection();
					c.setUseCaches(false);
					final long at = c.getLastModified();
					if (at > NOT_BEFORE)
					{
						b = DateTimeFormatter.ofPattern("yyyy-MM-dd 'at' HH:mm")
							.format(Instant.ofEpochMilli(at).atZone(ZoneId.systemDefault()));
					}
				}
			}
			catch (Exception | LinkageError ignore)
			{
				// no stamp is a fine answer
			}
			built = b;
		}
		return b;
	}

	/** "0.7.2, built 2026-10-20 at 14:03", or just the version. */
	static String label()
	{
		final String b = built();
		return b.isEmpty() ? VERSION : VERSION + ", built " + b;
	}
}
