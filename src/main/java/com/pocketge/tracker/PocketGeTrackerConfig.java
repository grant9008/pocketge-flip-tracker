package com.pocketge.tracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.Range;

@ConfigGroup(PocketGeTrackerConfig.GROUP)
public interface PocketGeTrackerConfig extends Config
{
	String GROUP = "pocketgetracker";

	enum RiskLevel
	{
		LOW(1_000_000),   // only very liquid items
		MED(250_000),
		HIGH(30_000);     // include thinner markets

		private final long minVolume;
		RiskLevel(long v) { this.minVolume = v; }
		public long minVolume() { return minVolume; }
	}

	@ConfigItem(
		keyName = "advisor",
		name = "Flip advisor (needs live prices)",
		description = "Suggest buys sized to your cash, sells for stacks you already hold, and 'adjust your offer' " +
			"nudges when your price drifts off the market. This is the ONLY feature that fetches live prices from the " +
			"OSRS Wiki price API — everything else is fully offline. OFF by default.",
		position = 1
	)
	default boolean advisor()
	{
		return false;
	}

	@ConfigItem(
		keyName = "adjustInterval",
		name = "Re-check every",
		description = "How often the advisor refreshes prices and re-thinks its suggestions.",
		position = 2
	)
	default AdjustInterval adjustInterval()
	{
		return AdjustInterval.M5;
	}

	@ConfigItem(keyName = "adjustInterval", name = "", description = "")
	void setAdjustInterval(AdjustInterval v);

	@ConfigItem(
		keyName = "riskLevel",
		name = "Risk level",
		description = "Low = only very liquid items; High = include thinner, higher-margin markets.",
		position = 3
	)
	default RiskLevel riskLevel()
	{
		return RiskLevel.MED;
	}

	@ConfigItem(keyName = "riskLevel", name = "", description = "")
	void setRiskLevel(RiskLevel v);

	@ConfigItem(
		keyName = "blocklist",
		name = "Never-recommend list",
		description = "Comma-separated item names the advisor will never suggest. Edit here, or click the block " +
			"icon on any suggestion card. Example: Cannonball, Nature rune",
		position = 4
	)
	default String blocklist()
	{
		return "";
	}

	@ConfigItem(keyName = "blocklist", name = "", description = "")
	void setBlocklist(String names);

	@ConfigItem(
		keyName = "favorites",
		name = "Favorites",
		description = "Items watched in the Favorites section — a local watchlist, independent of any account. " +
			"Click the star on any suggestion or flip-history row to add one; this box just shows what's saved " +
			"(id:name pairs — not meant for hand-editing).",
		position = 5
	)
	default String favorites()
	{
		return "";
	}

	@ConfigItem(keyName = "favorites", name = "", description = "")
	void setFavorites(String names);

	@ConfigItem(
		keyName = "localBridge",
		name = "Local website bridge",
		description = "Serve your session's flips on 127.0.0.1 so pocketge.com open in YOUR browser can display them. " +
			"Local-only: nothing ever leaves this machine, and it is OFF by default.",
		position = 6
	)
	default boolean localBridge()
	{
		return false;
	}

	enum AdjustInterval
	{
		M5("5m", 300),
		M30("30m", 1800),
		H2("2h", 7200),
		H8("8h", 28800);

		private final String label;
		private final int seconds;
		AdjustInterval(String l, int s) { this.label = l; this.seconds = s; }
		public int seconds() { return seconds; }
		@Override public String toString() { return label; }
	}

	@Range(min = 1024, max = 65535)
	@ConfigItem(
		keyName = "bridgePort",
		name = "Bridge port",
		description = "Port the local bridge listens on (127.0.0.1 only).",
		position = 7
	)
	default int bridgePort()
	{
		return 8477;
	}

	@ConfigItem(
		keyName = "maxFlips",
		name = "Flips to keep",
		description = "How many completed flips to show in the panel.",
		position = 8
	)
	@Range(min = 5, max = 200)
	default int maxFlips()
	{
		return 50;
	}
}
