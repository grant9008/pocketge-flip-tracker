package com.pocketge.tracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PocketGeTrackerConfig.GROUP)
public interface PocketGeTrackerConfig extends Config
{
	String GROUP = "pocketgetracker";

	@ConfigItem(
		keyName = "localBridge",
		name = "Local website bridge",
		description = "Serve your session's flips on 127.0.0.1 so pocketge.com open in YOUR browser can display them. " +
			"Local-only: nothing ever leaves this machine, and it is OFF by default.",
		position = 1
	)
	default boolean localBridge()
	{
		return false;
	}

	@Range(min = 1024, max = 65535)
	@ConfigItem(
		keyName = "bridgePort",
		name = "Bridge port",
		description = "Port the local bridge listens on (127.0.0.1 only).",
		position = 2
	)
	default int bridgePort()
	{
		return 8477;
	}

	@ConfigItem(
		keyName = "maxFlips",
		name = "Flips to keep",
		description = "How many completed flips to show in the panel.",
		position = 3
	)
	@Range(min = 5, max = 200)
	default int maxFlips()
	{
		return 50;
	}
}
