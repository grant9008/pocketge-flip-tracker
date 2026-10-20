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

	@Test
	public void theLabelStartsWithTheVersion()
	{
		Assert.assertTrue(BuildInfo.label(), BuildInfo.label().startsWith(BuildInfo.VERSION));
	}
}
