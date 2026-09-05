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

	/* Copilot calls its equivalent "Min. predicted profit". The floor is real
	   here in one way theirs isn't obliged to be: leaving it on Auto keeps the
	   old behaviour exactly, INCLUDING the relax-and-retry that would rather
	   show a thin flip than an empty panel. Set an explicit floor and that
	   retry is bounded by it — a 3k idea is not a helpful fallback for someone
	   who just said "nothing under 500k". See Advisor.advise. */
	@ConfigItem(
		keyName = "minProfit",
		name = "Min. profit per suggestion",
		description = "Ignore buy ideas whose whole-limit profit lands under this, after tax. Auto keeps the " +
			"plugin's own low floor and always shows you something; anything else is a hard floor, so with " +
			"a big number and a small bank the suggestions can legitimately run dry.",
		position = 3
	)
	default MinProfit minProfit()
	{
		return MinProfit.AUTO;
	}

	@ConfigItem(keyName = "minProfit", name = "", description = "")
	void setMinProfit(MinProfit v);

	enum MinProfit
	{
		AUTO("Auto", 0),
		K20("20K", 20_000),
		K50("50K", 50_000),
		K100("100K", 100_000),
		K200("200K", 200_000),
		K500("500K", 500_000),
		M1("1M", 1_000_000);

		private final String label;
		private final long gp;
		MinProfit(String l, long g) { this.label = l; this.gp = g; }
		/** The floor in gp, or 0 for "let the advisor decide". */
		public long gp() { return gp; }
		@Override public String toString() { return label; }
	}

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

	/* On by default, because the badges are how the watchlist says anything
	   at all about an item you are not currently looking at. Off is for the
	   case they were asked for: a long list where several rows are always
	   near some edge, so the pulsing reads as decoration rather than signal.
	   This kills the animation too, not just the chip — a silent badge is
	   still half the distraction. */
	@ConfigItem(
		keyName = "showBadges",
		name = "Watchlist badges",
		description = "The ▲/▼ range chips, the big-swing percentage, and the pulsing row borders in your " +
			"watchlist. Turn off for a plain list; prices and everything else stay exactly as they are.",
		position = 13
	)
	default boolean showBadges()
	{
		return true;
	}

	@ConfigItem(keyName = "showBadges", name = "", description = "")
	void setShowBadges(boolean on);

	/* Deliberately NOT called "dump alerts", which is what Flipping Copilot
	   names its version of this. Theirs is fed by real trades their own users
	   report to their server, so it can genuinely say "someone dumped a big
	   stack". This plugin has one price source, the OSRS Wiki's public
	   aggregates, and can only see the shadow of that: the price moving. A
	   name promising trade flow we cannot observe would be a lie, and would
	   have you reading it as volume when it is a percentage. */
	@ConfigItem(
		keyName = "priceAlerts",
		name = "Alert on big price moves",
		description = "Notify when something on your watchlist swings this far from its own 24-hour typical " +
			"price — a dip worth buying, or a spike worth selling into. Measures price only: it cannot see " +
			"who traded, so a quiet drift far enough is the same alert as a dump. One alert per item per hour.",
		position = 14
	)
	default PriceAlert priceAlerts()
	{
		return PriceAlert.OFF;
	}

	@ConfigItem(keyName = "priceAlerts", name = "", description = "")
	void setPriceAlerts(PriceAlert v);

	enum PriceAlert
	{
		OFF("Off", 0),
		P10("10%+", 10),
		P15("15%+", 15),
		P20("20%+", 20),
		P30("30%+", 30);

		private final String label;
		private final int pct;
		PriceAlert(String l, int p) { this.label = l; this.pct = p; }
		/** Move size that fires, in percent, or 0 when off. */
		public int pct() { return pct; }
		@Override public String toString() { return label; }
	}

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
