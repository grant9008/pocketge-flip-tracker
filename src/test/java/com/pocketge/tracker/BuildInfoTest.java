package com.pocketge.tracker;

import java.io.FileInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Test;

/** The version the plugin reports is the one the hub shows. */
public class BuildInfoTest
{
	@Test
	public void theVersionMatchesTheHubsAndGradles() throws Exception
	{
		final Properties p = new Properties();
		try (FileInputStream in = new FileInputStream("runelite-plugin.properties"))
		{
			p.load(in);
		}
		Assert.assertEquals("runelite-plugin.properties", p.getProperty("version"), BuildInfo.VERSION);
		final Matcher m = Pattern.compile("(?m)^version = '([^']+)'")
			.matcher(new String(Files.readAllBytes(Paths.get("build.gradle")), StandardCharsets.UTF_8));
		Assert.assertTrue("build.gradle has a version line", m.find());
		Assert.assertEquals("build.gradle", m.group(1), BuildInfo.VERSION);
	}

	/** Three plain numbers, the last moved on per update — what
	 *  tools/dev/bump-version.sh counts up. */
	@Test
	public void theVersionIsThreeNumbers()
	{
		Assert.assertTrue(BuildInfo.VERSION, BuildInfo.VERSION.matches("\\d+\\.\\d+\\.\\d+"));
	}

	/** Installed from the hub the class is in a jar: the stamp is that
	 *  entry's time, read from the jar file itself. */
	@Test
	public void aJarBuildReadsItsEntrysTime() throws Exception
	{
		final java.io.File jar = java.io.File.createTempFile("pocketge build", ".jar");
		jar.deleteOnExit();
		final long when = 1_790_000_000_000L;
		try (java.util.jar.JarOutputStream out = new java.util.jar.JarOutputStream(new java.io.FileOutputStream(jar)))
		{
			final java.util.jar.JarEntry e = new java.util.jar.JarEntry("com/pocketge/tracker/BuildInfo.class");
			e.setTime(when);
			out.putNextEntry(e);
			out.write(new byte[]{1, 2, 3});
			out.closeEntry();
		}
		final java.net.URL url = new java.net.URL("jar:" + jar.toURI() + "!/com/pocketge/tracker/BuildInfo.class");
		Assert.assertEquals("a space in the path is fine too", when / 2000, BuildInfo.jarEntryTime(url) / 2000);
		Assert.assertEquals("no such entry", 0, BuildInfo.jarEntryTime(
			new java.net.URL("jar:" + jar.toURI() + "!/nothing.class")));
		Assert.assertEquals("not a jar", 0, BuildInfo.jarEntryTime(jar.toURI().toURL()));
	}

	/**
	 * The plugin hub's scan refuses URL.openConnection, and HTTP that does
	 * not go through RuneLite's OkHttpClient ("URL::openConnection is not
	 * allowed, @Inject and use RuneLite's OkHttpClient instead" on the 0.7.2
	 * PR). Caught here rather than by a reviewer.
	 */
	@Test
	public void nothingOpensItsOwnConnection() throws Exception
	{
		final java.util.List<String> found = new java.util.ArrayList<>();
		try (java.util.stream.Stream<java.nio.file.Path> files = Files.walk(Paths.get("src/main/java")))
		{
			for (java.nio.file.Path f : (Iterable<java.nio.file.Path>) files.filter(x -> x.toString().endsWith(".java"))::iterator)
			{
				final String code = new String(Files.readAllBytes(f), StandardCharsets.UTF_8)
					.replaceAll("(?s)/\\*.*?\\*/", "").replaceAll("//[^\\n]*", "");
				if (code.matches("(?s).*\\.openConnection\\s*\\(.*") || code.contains("HttpURLConnection")
					|| code.contains("URLConnection"))
				{
					found.add(f.getFileName().toString());
				}
			}
		}
		Assert.assertEquals("connections that bypass OkHttp", java.util.List.of(), found);
	}

	/** Run from a classes directory, as the tests are: the stamp is there,
	 *  and in UTC like the report's own times. */
	@Test
	public void aDevBuildSaysWhenItWasBuilt()
	{
		Assert.assertTrue(BuildInfo.label(), BuildInfo.label().startsWith(BuildInfo.VERSION + ", built "));
		Assert.assertTrue(BuildInfo.label(), BuildInfo.label().endsWith(" UTC"));
	}
}
