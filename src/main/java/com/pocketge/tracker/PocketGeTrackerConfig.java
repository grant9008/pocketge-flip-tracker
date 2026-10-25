package com.pocketge.tracker;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;

@ConfigGroup(PocketGeTrackerConfig.GROUP)
public interface PocketGeTrackerConfig extends Config
{
	String GROUP = "pocketgetracker";

	/*
	 * Grouped, and with the plugin's stored data hidden.
	 *
	 * This menu used to show the favourite lists as raw JSON, the legacy
	 * favourites as id:name pairs and the active list as an id, between the
	 * real settings: "this wrench menu is unusable". Those three are hidden
	 * now — they are data the panel manages — and every setting here is also
	 * in the panel's own gear menu, which is where people actually look.
	 */
	@ConfigSection(
		name = "Advisor",
		description = "Live-price suggestions, the profit floor, alerts and the never-recommend list.",
		position = 0
	)
	String ADVISOR = "advisorSection";

	@ConfigSection(
		name = "Display",
		description = "Colours, the card and watchlist details, and the bank marks.",
		position = 1
	)
	String DISPLAY = "displaySection";

	@ConfigSection(
		name = "Website link",
		description = "The optional link to pocketge.com open in your browser.",
		position = 2
	)
	String WEBSITE = "websiteSection";

	@ConfigItem(
		keyName = "advisor",
		name = "Flip advisor (needs live prices)",
		description = "Suggest buys sized to your cash, sells for stacks you already hold, and 'adjust your offer' " +
			"nudges when your price drifts off the market. This is the ONLY feature that fetches live prices from the " +
			"OSRS Wiki price API — everything else is fully offline. Turn it off to stop all outbound requests.",
		position = 1,
		section = ADVISOR
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
		position = 2,
		section = ADVISOR
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
		description = "Ignore buy ideas whose profit lands under this, after tax — the ideas and the planned " +
			"buys alike. Auto is the plugin's own floor of 2,000 gp. Anything higher is a hard floor, so with " +
			"a big number and a small bank the suggestions can legitimately run dry.",
		position = 3,
		section = ADVISOR
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

	/**
	 * Which two colours mean "buy" and "sell".
	 *
	 * The same four pairs pocketge.com offers, with the same hex values, so
	 * somebody running both does not have to learn two colour schemes — the
	 * plugin's default IS the site's default, down to the digit.
	 *
	 * This is a direction axis and nothing else. It does not touch the green
	 * and red that mean "this offer is fine" and "this offer is stranded", or
	 * the gold that means "the plugin is pointing here": those are states, a
	 * different question, and they keep their own colours whatever is chosen
	 * here. See the colour key in the settings popup.
	 *
	 * Every pair survives red-green colour blindness, which is the one real
	 * constraint — roughly one man in twelve cannot separate the red/green
	 * pairing that trading software reaches for by reflex.
	 */
	@ConfigItem(
		keyName = "colourTheme",
		name = "Buy / sell colours",
		description = "Which two colours mean buy and sell on the cards, and the outline on bank stacks worth " +
			"selling. Matches pocketge.com's own picker. Does not change the green and red that mean an offer " +
			"is fine or stranded, or the white that marks the one to act on.",
		position = 5,
		section = DISPLAY
	)
	default ColourTheme colourTheme()
	{
		return ColourTheme.CONTRAST; // the default since 0.7.2, as on pocketge.com
	}

	@ConfigItem(keyName = "colourTheme", name = "", description = "")
	void setColourTheme(ColourTheme v);

	/** Whether a Terminal choice saved before High contrast became the
	 *  default has been moved over — see PocketGeTrackerPlugin.moveToNewDefaultTheme. */
	@ConfigItem(keyName = "themeDefaultMoved", name = "", description = "", hidden = true)
	default boolean themeDefaultMoved()
	{
		return false;
	}

	@ConfigItem(keyName = "themeDefaultMoved", name = "", description = "")
	void setThemeDefaultMoved(boolean moved);

	@ConfigItem(
		keyName = "showFlipScore",
		name = "Score line on cards",
		description = "The line under the item name. On a buy idea, the 0–100 score and verdict " +
			"(Thin, Solid, Strong, Prime) that pocketge.com shows. On a sell, how many days of " +
			"the item's own demand your stack comes to. Off hides the row; the ranking " +
			"underneath is unchanged.",
		position = 6,
		section = DISPLAY
	)
	default boolean showFlipScore()
	{
		return true;
	}

	@ConfigItem(keyName = "showFlipScore", name = "", description = "")
	void setShowFlipScore(boolean on);

	/**
	 * Kept in step with pocketge.com's THEMES table by hand, in the same
	 * order and with the same values. Copying it is the point: the two are
	 * the same product, and a "Cobalt" that is one blue here and another
	 * there would be worse than not offering it.
	 */
	enum ColourTheme
	{
		TERMINAL("Terminal", 0xE5B842, 0x26A9AB),
		CONTRAST("High contrast", 0xFFC107, 0x29B6F6),
		NEON("Neon", 0xFF44B0, 0x22E0FF),
		SUNSET("Sunset", 0xFF8A3D, 0xB388FF),
		COBALT("Cobalt", 0xFF9F1C, 0x3D8BFF),
		SOLAR("Solar", 0xFFE14D, 0x7B8CFF),
		ORCHID("Orchid", 0xFFB2E6, 0x7C6BFF),
		/**
		 * The one pair that is not two hues.
		 *
		 * Buy and sell separate by lightness alone, which leaves green and
		 * red the only colours on the panel carrying meaning — useful if you
		 * want the prices to stop competing with the profit figure for
		 * attention, and the only option here legible to someone with no
		 * colour vision at all.
		 *
		 * The slate is lighter than a pure lightness split wants, because the
		 * site paints this colour solid behind near-black text in its badges
		 * and a darker one fell under AA contrast there. Carried across
		 * unchanged rather than re-tuned for this panel: the value is shared,
		 * and two products disagreeing about what "Mono" is would be worse
		 * than one of them being a shade off ideal.
		 */
		MONO("Mono", 0xF2EADB, 0x7A8794);

		private final String label;
		private final int buy;
		private final int sell;

		ColourTheme(String l, int buy, int sell)
		{
			this.label = l;
			this.buy = buy;
			this.sell = sell;
		}

		public java.awt.Color buy() { return new java.awt.Color(buy); }

		public java.awt.Color sell() { return new java.awt.Color(sell); }

		@Override public String toString() { return label; }
	}

	@ConfigItem(
		keyName = "blocklist",
		name = "Never-recommend list",
		description = "Comma-separated item names the advisor will never suggest. Edit here, or click the block " +
			"icon on any suggestion card. Example: Cannonball, Nature rune",
		position = 4,
		section = ADVISOR
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
		position = 5,
		hidden = true
	)
	default String favorites()
	{
		return "";
	}


	@ConfigItem(
		keyName = "favoriteLists",
		name = "Favorite lists",
		description = "Your favorites organized into named, color-flagged lists, like TradingView watchlists (JSON — " +
			"not meant for hand-editing). Manage lists from the star icon in the Favorites section of the panel.",
		position = 6,
		hidden = true
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
		position = 7,
		hidden = true
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
		description = "Lets pocketge.com, open in a browser on this computer, show your flips, favorites and bank "
			+ "summary, and edit your favorites. Served on 127.0.0.1 only, never to the network. Other websites "
			+ "open in your browser are refused. Programs already running on this computer are not stopped by "
			+ "it (they could read RuneLite's own files anyway). Off by default.",
		position = 8,
		section = WEBSITE
	)
	default boolean localBridge()
	{
		return false;
	}

	@ConfigItem(keyName = "localBridge", name = "", description = "")
	void setLocalBridge(boolean on);

	/** Whether the one-time "see this on pocketge.com" card has been
	 *  answered, either way — see MainPanel.setBridgeNudge. */
	@ConfigItem(keyName = "bridgeNudgeDone", name = "", description = "", hidden = true)
	default boolean bridgeNudgeDone()
	{
		return false;
	}

	@ConfigItem(keyName = "bridgeNudgeDone", name = "", description = "")
	void setBridgeNudgeDone(boolean done);

	/* One colour, one meaning, and on by default — see BankHighlightOverlay
	   for why the other two were removed rather than kept behind this. */
	@ConfigItem(
		keyName = "bankHighlights",
		name = "Mark bank stacks worth selling",
		description = "Outlines stacks in your bank and inventory that are worth selling right now — priced to sell, "
			+ "and worth enough after tax to bother. Hover a marked slot to see what it's worth.",
		position = 9,
		section = DISPLAY
	)
	default boolean bankHighlights()
	{
		return true;
	}

	@ConfigItem(keyName = "bankHighlights", name = "", description = "")
	void setBankHighlights(boolean on);

	/* "i just bought these, hovering over them should show how much profit
	   available or losing" — every stack the plugin watched you buy, not
	   only the ones marked worth selling. */
	@ConfigItem(
		keyName = "holdingTips",
		name = "Price and profit on hover over your stacks",
		description = "Hover a stack in your bank or inventory to see what it sells for now. On one the plugin watched "
			+ "you buy, also what you paid and the profit available or the loss on it.",
		position = 16,
		section = DISPLAY
	)
	default boolean holdingTips()
	{
		return true;
	}

	@ConfigItem(keyName = "holdingTips", name = "", description = "")
	void setHoldingTips(boolean on);

	/* "thoughts on hover over also showing high alch profit ea alch?" and
	   "putting high alch ea profit on the flip trade idea cards too" — see
	   HighAlch. */
	@ConfigItem(
		keyName = "alchProfit",
		name = "High alch profit",
		description = "When a high alchemy cast pays, on the hover over a stack and on sell cards: what one makes, its "
			+ "alch value less a nature rune and what one cost you (or its price, with no buy on record), and when "
			+ "alching beats selling. Nothing is shown when it does not pay.",
		position = 16,
		section = DISPLAY
	)
	default boolean alchProfit()
	{
		return true;
	}

	@ConfigItem(keyName = "alchProfit", name = "", description = "")
	void setAlchProfit(boolean on);

	/* "if lets say rune darts sold. it would show +200gp or +2000gp or
	   +20kgp profit ... over that square ... makes you want ot keep this
	   creen open to see the money tick in" — see ProfitPops. */
	@ConfigItem(
		keyName = "profitPops",
		name = "Profit pops on GE slots",
		description = "When a sell goes through, what it made floats up off its slot on the Grand Exchange screen: "
			+ "+2,000 gp, or -1,250 gp in red on a loss. Something never bought, like sharks you cooked, pops the "
			+ "coins it brought in, in gold. Sold while you were away or the Exchange was shut? It pops the next "
			+ "time you open the Exchange.",
		position = 17,
		section = DISPLAY
	)
	default boolean profitPops()
	{
		return true;
	}

	@ConfigItem(keyName = "profitPops", name = "", description = "")
	void setProfitPops(boolean on);

	/*
	 * On by default, with a switch. The key ("Your current suggestion / Also
	 * worth selling") once sat on the bank's own tool row and then on the
	 * bank note under it — "maybe hide that in settings ... it covers the
	 * bank note". It was briefly off by default; with its placement fixed it
	 * came back on ("have it on by default, the new location is fine"), and
	 * the switch stays for anyone whose layout it still gets in the way of.
	 */
	@ConfigItem(
		keyName = "bankLegend",
		name = "Show a key beside the bank",
		description = "Draws a small box next to the open bank saying what the marks mean (\"Your current "
			+ "suggestion\", \"Also worth selling\"). Turn off if it covers something — hovering a marked slot "
			+ "says the same thing.",
		position = 9,
		section = DISPLAY
	)
	default boolean bankLegend()
	{
		return true;
	}

	@ConfigItem(keyName = "bankLegend", name = "", description = "")
	void setBankLegend(boolean on);


	/*
	 * Off by default now, and the handoff has its own right-click item.
	 *
	 * On, a left click had to decide whether a linked tab would really
	 * receive the request, and when it guessed wrong the click did nothing
	 * at all — no tab moved, no browser opened. A control whose ordinary
	 * outcome is "nothing visible happens" is worse than one that is merely
	 * less clever, and it was reported as broken three times.
	 *
	 * So the plain click always opens a tab, which cannot silently fail, and
	 * "send it to the tab I already have open" is the explicit right-click.
	 * This switch only moves that behaviour back onto the left click for
	 * people who want it there.
	 */
	@ConfigItem(
		keyName = "reuseBrowserTab",
		name = "Chart clicks reuse your open tab",
		description = "OFF (default): clicking a chart opens a new browser tab, every time. Right-click for "
			+ "\"Send to my open PocketGE tab\" when you want the page you already have open to change instead. "
			+ "ON: a plain click goes to that tab, and if the tab does not confirm it within a couple of "
			+ "seconds (closed, asleep, or no tab open) it opens in a new one. Note "
			+ "the tab changes in the BACKGROUND either way — browsers do not let a page raise its own window — "
			+ "so you still have to alt-tab to it.",
		position = 10,
		section = WEBSITE
	)
	default boolean reuseBrowserTab()
	{
		return false;
	}

	@ConfigItem(keyName = "reuseBrowserTab", name = "", description = "")
	void setReuseBrowserTab(boolean on);

	/* "every time i list a new item in the ge, if i already have a pocket ge
	   window open ... can it automatically change the window from sapphire
	   necklaces to ruby necklaces when i switch to my next ge listing". Only
	   ever moves a tab that is already open and linked; never opens one. */
	@ConfigItem(
		keyName = "followGeOffer",
		name = "Open tab follows the GE offer screen",
		description = "When a PocketGE tab is already open and linked, it switches to whatever item you open "
			+ "an offer for in the Grand Exchange. Never opens a new tab.",
		position = 17,
		section = WEBSITE
	)
	default boolean followGeOffer()
	{
		return true;
	}

	@ConfigItem(keyName = "followGeOffer", name = "", description = "")
	void setFollowGeOffer(boolean on);

	/* The pocketge.com chart over the chat while an offer is open: the
	   item's prices, volume and targets at a glance, where the price is
	   being decided. */
	@ConfigItem(
		keyName = "priceChart",
		name = "Price chart over the chat",
		description = "While a Grand Exchange offer is open, draws the item's pocketge.com price chart over "
			+ "the chat: prices, volume by price, the target and your offer. Click its range buttons to look "
			+ "further back; drag its top edge to make it taller, double-click the edge to reset.",
		position = 18,
		section = DISPLAY
	)
	default boolean priceChart()
	{
		return true;
	}

	@ConfigItem(keyName = "priceChart", name = "", description = "")
	void setPriceChart(boolean on);

	/* The one key the plugin answers on the Exchange's own screens: it
	   puts the suggestion shown on a chip into the box or search that is
	   open. Taken only while that chip is on screen. */
	@ConfigItem(
		keyName = "fillKey",
		name = "Fill key",
		description = "While a PocketGE chip is showing on the Grand Exchange, this key puts its suggestion in: "
			+ "the price or quantity into the open box, or, on \"What would you like to buy?\" before you have typed "
			+ "anything, picks the suggested item. Any other time the key does what it always does. "
			+ "Enter, Escape and Backspace can't be used: the boxes need them.",
		position = 19,
		section = DISPLAY
	)
	default Keybind fillKey()
	{
		return new Keybind(java.awt.event.KeyEvent.VK_E, 0);
	}

	/* The chart's own controls, remembered between offers. Set from the
	   chart itself, so not listed. */
	@ConfigItem(keyName = "chartRange", name = "Chart range", description = "", hidden = true)
	default String chartRange()
	{
		return "D1";
	}

	@ConfigItem(keyName = "chartRange", name = "", description = "")
	void setChartRange(String range);

	/** The chart's height in px, set by dragging its top edge; 0 is the
	 *  chat's own height. */
	@ConfigItem(keyName = "chartHeight", name = "Chart height", description = "", hidden = true)
	default int chartHeight()
	{
		return 0;
	}

	@ConfigItem(keyName = "chartHeight", name = "", description = "")
	void setChartHeight(int px);

	@ConfigItem(keyName = "chartVolumeProfile", name = "Chart volume profile", description = "", hidden = true)
	default boolean chartVolumeProfile()
	{
		return true;
	}

	@ConfigItem(keyName = "chartVolumeProfile", name = "", description = "")
	void setChartVolumeProfile(boolean on);


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
		position = 11,
		section = WEBSITE
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
		position = 13,
		section = DISPLAY
	)
	default boolean showBadges()
	{
		return true;
	}

	@ConfigItem(keyName = "showBadges", name = "", description = "")
	void setShowBadges(boolean on);

	/* The badges without the borders: the arrows and percentages stay, in
	   their colours, and the rows stop glowing ("add right click mute
	   border ... just leaves the glow arrow/%"). Also on a watchlist row's
	   right-click menu. */
	@ConfigItem(
		keyName = "rowGlow",
		name = "Watchlist glow borders",
		description = "The glowing and pulsing borders on watchlist rows at a 5-day edge or in a big swing. "
			+ "Off keeps the ▲/▼ chips and the swing percentage, in colour, without the borders.",
		position = 13,
		section = DISPLAY
	)
	default boolean rowGlow()
	{
		return true;
	}

	@ConfigItem(keyName = "rowGlow", name = "", description = "")
	void setRowGlow(boolean on);


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
		description = "Notify when something on your watchlist swings this far on the day, against its price " +
			"24 hours ago — a dip worth buying, or a spike worth selling into. Measures price only: it cannot see " +
			"who traded, so a quiet drift far enough is the same alert as a dump. At most one alert per item " +
			"an hour while the move lasts; it can alert again once the price has come back, or after you log in again.",
		position = 14,
		section = ADVISOR
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

	/* Not a nag: blocking is the one action in the panel that is silent,
	   permanent and easy to hit by accident — the block icon sits next to
	   Next in the same row, and nothing on screen changes when it lands, so
	   the item simply stops appearing forever. Hence a confirm, and hence a
	   way to switch it off for people who block deliberately and often. */
	@ConfigItem(
		keyName = "confirmBlock",
		name = "Ask before blocking an item",
		description = "Show a confirmation before adding an item to the never-recommend list. Turn off if you "
			+ "block items often and would rather it just happen.",
		position = 15,
		section = ADVISOR
	)
	default boolean confirmBlock()
	{
		return true;
	}

	@ConfigItem(keyName = "confirmBlock", name = "", description = "")
	void setConfirmBlock(boolean on);

	/* "why sometimes flip rating and sometimes not" — the round prices the
	   first few flip ideas from their price history; the rest are priced
	   ahead of being paged to. See PocketGeTrackerPlugin.noteCardsAhead. */
	@ConfigItem(
		keyName = "priceNextCard",
		name = "Price the next cards ahead",
		description = "While a suggestion is up, fetch the price history of the next two if the plugin has none, so "
			+ "they are priced and rated the way pocketge.com does it by the time you page to them. At most two "
			+ "requests each time the card changes, usually one. Off, cards past the first few show the last "
			+ "trade prices and no rating.",
		position = 16,
		section = ADVISOR
	)
	default boolean priceNextCard()
	{
		return true;
	}

}
