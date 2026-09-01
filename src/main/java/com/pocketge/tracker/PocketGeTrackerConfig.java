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
		keyName = "advisor",
		name = "Flip advisor (needs live prices)",
		description = "Suggest buys sized to your cash, sells for stacks you already hold, and 'adjust your offer' " +
			"nudges when your price drifts off the market. This is the ONLY feature that fetches live prices from the " +
			"OSRS Wiki price API — everything else is fully offline. Turn it off to stop all outbound requests.",
		position = 1
	)
	default boolean advisor()
	{
		/* On by default. Every recommendation in the sidebar — what to sell
		   out of your bank, how to deploy your cash across your GE slots —
		   is computed by the advisor cycle, so with this off the panel has
		   nothing to show and reads as broken rather than as switched off.
		   The tradeoff is that the plugin talks to the OSRS Wiki price API
		   as soon as it's enabled; that's what the description says, and
		   the toggle is still one click away in the gear popup. */
		return true;
	}

	@ConfigItem(keyName = "advisor", name = "", description = "")
	void setAdvisor(boolean on);

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
		name = "Favorites (legacy)",
		description = "The old single Favorites list (id:name pairs) — superseded by Favorite lists below, which it's " +
			"migrated into once on first load. Not written to anymore; not meant for hand-editing.",
		position = 5
	)
	default String favorites()
	{
		return "";
	}

	@ConfigItem(keyName = "favorites", name = "", description = "")
	void setFavorites(String names);

	@ConfigItem(
		keyName = "favoriteLists",
		name = "Favorite lists",
		description = "Your favorites organized into named, color-flagged lists, like TradingView watchlists (JSON — " +
			"not meant for hand-editing). Manage lists from the star icon in the Favorites section of the panel.",
		position = 6
	)
	default String favoriteLists()
	{
		return "";
	}

	@ConfigItem(keyName = "favoriteLists", name = "", description = "")
	void setFavoriteLists(String json);

	@ConfigItem(
		keyName = "activeFavoriteList",
		name = "Active favorites list",
		description = "Which favorites list the star button on suggestions and flips adds to.",
		position = 7
	)
	default String activeFavoriteList()
	{
		return "";
	}

	@ConfigItem(keyName = "activeFavoriteList", name = "", description = "")
	void setActiveFavoriteList(String listId);

	@ConfigItem(
		keyName = "localBridge",
		name = "Local website bridge",
		description = "Serve your session's flips on 127.0.0.1 so pocketge.com open in YOUR browser can display them. " +
			"Local-only: nothing ever leaves this machine, and it is OFF by default.",
		position = 8
	)
	default boolean localBridge()
	{
		return false;
	}

	@ConfigItem(keyName = "localBridge", name = "", description = "")
	void setLocalBridge(boolean on);

	/* Off by default as of 0.5.0. It drew three different colours plus a
	   corner mark on your bank slots, none of them labelled anywhere, so the
	   honest report from actually using it was "a bunch of colors" that
	   didn't mean anything. Everything it was trying to say is now said in
	   words: the watchlist shows a profit tag on stacks you hold, and the
	   recommendation card names the item outright. Kept, rather than
	   deleted, because the highlight is genuinely useful once you know the
	   code — it just shouldn't be the first thing a new user meets. */
	@ConfigItem(
		keyName = "bankHighlights",
		name = "Colour bank slots the advisor is pointing at",
		description = "Draws a border on bank/inventory slots with a live suggestion: gold to buy more, "
			+ "green to sell, dashed teal for items you told it to hold. Off by default — the sidebar "
			+ "says the same things in words.",
		position = 9
	)
	default boolean bankHighlights()
	{
		return false;
	}

	@ConfigItem(keyName = "bankHighlights", name = "", description = "")
	void setBankHighlights(boolean on);

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
		position = 9
	)
	default int bridgePort()
	{
		return 8477;
	}

	@ConfigItem(keyName = "bridgePort", name = "", description = "")
	void setBridgePort(int port);

	@ConfigItem(
		keyName = "maxFlips",
		name = "Flips to keep",
		description = "How many completed flips to show in the panel.",
		position = 10
	)
	@Range(min = 5, max = 200)
	default int maxFlips()
	{
		return 50;
	}

	@ConfigItem(keyName = "maxFlips", name = "", description = "")
	void setMaxFlips(int n);
}
