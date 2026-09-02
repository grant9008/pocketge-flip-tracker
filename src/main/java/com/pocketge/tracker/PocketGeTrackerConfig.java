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

	/* One colour, one meaning, and on by default — see BankHighlightOverlay
	   for why the other two were removed rather than kept behind this. */
	@ConfigItem(
		keyName = "bankHighlights",
		name = "Mark bank stacks worth selling",
		description = "Outlines stacks in your bank and inventory that are worth selling right now — priced to sell, "
			+ "and worth enough after tax to bother. Hover a marked slot to see what it's worth.",
		position = 9
	)
	default boolean bankHighlights()
	{
		return true;
	}

	@ConfigItem(keyName = "bankHighlights", name = "", description = "")
	void setBankHighlights(boolean on);

	/* Only has any effect while the local bridge is on AND a PocketGE page
	   is actually polling it, so leaving this on costs nothing when it
	   can't apply — with the bridge off (the default) chart clicks open a
	   browser exactly as before. */
	@ConfigItem(
		keyName = "reuseBrowserTab",
		name = "Send charts to an open PocketGE tab",
		description = "When pocketge.com is already open and linked to the plugin, chart clicks navigate THAT tab "
			+ "instead of asking the system to open a link — which, depending on your browser, can hijack whatever "
			+ "tab you were on. Falls back to opening a page normally when no tab is linked.",
		position = 10
	)
	default boolean reuseBrowserTab()
	{
		return true;
	}

	@ConfigItem(keyName = "reuseBrowserTab", name = "", description = "")
	void setReuseBrowserTab(boolean on);

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
		position = 11
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
		position = 12
	)
	@Range(min = 5, max = 200)
	default int maxFlips()
	{
		return 50;
	}

	@ConfigItem(keyName = "maxFlips", name = "", description = "")
	void setMaxFlips(int n);
}
