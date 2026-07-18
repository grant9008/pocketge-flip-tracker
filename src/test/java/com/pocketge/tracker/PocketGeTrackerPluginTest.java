package com.pocketge.tracker;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/** Dev entrypoint: runs a full RuneLite client with the plugin loaded.
 *  `./gradlew test` runs the real unit tests; this is for manual testing. */
public class PocketGeTrackerPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(PocketGeTrackerPlugin.class);
		RuneLite.main(args);
	}
}
