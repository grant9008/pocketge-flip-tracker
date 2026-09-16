package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Advisor UI: a compact status/settings header (re-check interval and the
 * never-recommend list live behind a gear-icon popup — matching the
 * website's "tuck the knobs away, lead with the numbers" philosophy), a top
 * "inspection" card that shows whatever Favorites row was last clicked (a
 * lightweight prompt otherwise — it deliberately does NOT default to
 * previewing the #1 suggestion, since that would just duplicate the box
 * below it), and a second "Top Suggestion" card that cycles through every
 * suggestion Advisor.advise() returns — matching Flipping Copilot's own
 * minimal "Buy N Item for X gp / +profit" style rather than a busy
 * multi-line card.
 */
public class AdvisorPanel extends PluginPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	/* pocketge.com's --buy-color and --sell-color, to the byte. The plugin
	   had drifted to #26A69A for sell, which is a DIFFERENT teal from the
	   site's #26A9AB \u2014 close enough to look like a rendering artifact and
	   not close enough to be one. Same target price in two places should be
	   the same colour in two places. */
	/* The DEFAULT pair. Which two colours are actually in use is a setting —
	   see buyColor()/sellColor() and PocketGeTrackerConfig.ColourTheme — and
	   these are ColourTheme.TERMINAL, which is also pocketge.com's default,
	   to the digit.

	   Kept as constants for the one place that cannot ask: buildHoldIcon() is
	   a static Icon built once at class load. It is a pause glyph rather than
	   a direction, so it does not follow the theme. */
	private static final Color BUY_COLOR = new Color(0xE5, 0xB8, 0x42);
	private static final Color SELL_COLOR = new Color(0x26, 0xA9, 0xAB);
	/* The website's .hl-badge.high5d / .low5d, and the same two constants
	   FavoritesPanel uses. Direction is carried by COLOUR here, not just by
	   the ▲/▼ glyph: green is "at the top of its range, sell zone", gold is
	   "at the bottom, buy zone", everywhere in the plugin and on the site.
	   These have to be their own constants rather than reusing the card's
	   accent — accent means buy-vs-sell for a suggestion card (GOLD/SELL_COLOR),
	   which is a different axis, and borrowing it painted a 5-DAY HIGH in
	   the low tier's gold. */
	private static final Color HIGH5D = new Color(0x00, 0xFF, 0x7A);
	private static final Color LOW5D = new Color(0xFF, 0xB3, 0x00);
	private static final Color ADJUST = new Color(0xFF, 0x9F, 0x43);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	// pocketge.com's own "Gilded & Obsidian" palette (--bg-panel / --text-main
	// in index.html) — warmer than RuneLite's neutral ColorScheme grays, so
	// these cards read as PocketGE's own rather than generic plugin chrome.
	private static final Color OBSIDIAN_BG = new Color(0x1B, 0x18, 0x15);
	private static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);
	/** Item sprite size on a card. One size — there is one card. */
	private static final int CARD_ICON = 30;
	/* The controls row has to survive its worst case — Next + Pause + Hold +
	   Block on a SELL suggestion — inside a 225px RuneLite sidebar. Budget:
	   225 - 20 (this panel's own border) - 2 (card accent) - 21 (card
	   padding) = 182px usable, against 62 + 3 x (3 + 32) = 167. The old
	   FlowLayout version wanted 199 and quietly wrapped the last button onto
	   a second row that was then clipped away — which is why Block kept
	   vanishing on sells. */
	private static final int CARD_PAD_L = 12;
	private static final int CARD_PAD_R = 9;
	private static final int CONTROL_W = 32;
	private static final int CONTROL_H = 27;
	/* Sized so the widest row fits the 194px a card has inside a 225px
	   sidebar: three plain controls, two pager squares, four gaps —
	   3 × 32 + 2 × 27 + 4 × 2 = 158. */
	private static final int CONTROL_GAP = 2;
	/**
	 * pocketge.com's .fc-page, ported exactly.
	 *
	 * The site's flip card ended up with a pair of small gold-tinted chevron
	 * squares for paging, and it is the best that card has looked — so these
	 * are its literal values rather than an impression of them: #C9A64D for
	 * the glyph and the border, the same hue at 12% behind it, 26% and a
	 * brighter glyph under the cursor, 6px corners, a 24px target.
	 */
	private static final Color PAGER_FG = new Color(0xC9, 0xA6, 0x4D);
	private static final Color PAGER_FG_HOVER = new Color(0xFF, 0xD9, 0x8A);
	private static final int PAGER_FILL_ALPHA = 31;        // .12
	private static final int PAGER_FILL_ALPHA_HOVER = 66;  // .26
	private static final int PAGER_RIM_ALPHA = 115;        // .45
	private static final int PAGER_RADIUS = 6;
	private static final Icon CHART_ICON = buildChartIcon(1.45f);
	/* No NEXT_ICON/BACK_ICON constants: a pager square needs its chevron in
	   two colours, so each button builds its own pair — see pagerButton. */
	private static final Icon PAUSE_ICON = buildPauseIcon();
	private static final Icon HOLD_ICON = buildHoldIcon();
	private static final Icon BLOCK_ICON = buildBlockIcon();
	private static final Icon STAR_FILLED_ICON = buildStarIcon(true);
	private static final Icon STAR_HOLLOW_ICON = buildStarIcon(false);

	public interface Actions
	{
		void skip(int itemId);
		void block(String itemName);
		/** Persist the "don't ask again" tick from the block confirmation. */
		void setConfirmBlock(boolean on);
		void unblock(String itemName);
		void toggleFavorite(int itemId, String name);
		void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v);
		/** The floor under a buy idea's whole-limit profit. Lived only in
		 *  RuneLite's own plugin settings, which is not where anyone looks
		 *  for it — this popup is. */
		void setMinProfit(PocketGeTrackerConfig.MinProfit v);
		/** Which pair means buy and sell. See PocketGeTrackerConfig.ColourTheme. */
		void setColourTheme(PocketGeTrackerConfig.ColourTheme v);
		/** Whether the flip score row is drawn on buy cards. */
		void setShowFlipScore(boolean on);
		void setAdvisorEnabled(boolean on);
		void setLocalBridge(boolean on);
		void setBridgePort(int port);
		void setMaxFlips(int n);
		void fillGePrice(long price);
		void fillGeQuantity(long qty);
		/** Opens the item on PocketGE. Goes through the plugin rather than
		 *  straight to LinkBrowser so one place decides between navigating a
		 *  PocketGE tab you already have open and launching a browser — see
		 *  PocketGeTrackerPlugin.openPocketGeSearch. */
		void openChart(String itemName);
		/** Same item, but always a NEW browser tab — the right-click escape
		 *  hatch from openChart's reuse-the-open-tab behaviour, for when you
		 *  want two items on screen at once. */
		void openChartInNewTab(String itemName);
		/** Re-run the advisor now. Fired when Next walks off the end of the
		 *  list, so "no more ideas" turns into fresh ones instead of the same
		 *  ring of suggestions going round again. */
		void refreshSuggestions();
		/** Which watchlist item the inspection card is showing, or null when
		 *  it closes. The plugin needs to know because the card prices through
		 *  TradeEngine, and that costs a per-item price series — so exactly
		 *  one item can have one, and this says which. */
		void onSelectedItemChanged(Integer itemId);

		/**
		 * Which recommendation the card is showing, or null for none — so the
		 * in-game overlays can point at the very thing the sidebar is talking
		 * about, rather than at everything that merely qualifies.
		 *
		 * Distinct from {@link #onSelectedItemChanged}, which is the WATCHLIST
		 * inspection card and exists to budget a price series. This one is the
		 * recommendation, it costs nothing, and it changes whenever Next or
		 * Back is pressed.
		 */
		void onRecommendationShown(Integer itemId, String name, boolean sell);
	}

	/** Everything the gear-icon popup shows/edits, bundled so update()
	 *  doesn't grow another loose parameter every time a new setting moves
	 *  in here. Plain public fields, matching this codebase's other small
	 *  data-holder classes (Advisor.Quote, Advisor.ItemMeta, ...). */
	public static class Settings
	{
		public boolean advisorOn;
		public PocketGeTrackerConfig.AdjustInterval interval = PocketGeTrackerConfig.AdjustInterval.M5;
		public PocketGeTrackerConfig.MinProfit minProfit = PocketGeTrackerConfig.MinProfit.AUTO;
		public List<String> blocked = List.of();
		public boolean bridgeOn;
		public int bridgePort = 8477;
		/** Seconds since a PocketGE page last polled the bridge, -1 for never.
		 *  Shown in the popup because "is a tab actually linked" was
		 *  previously invisible — and it is the difference between chart
		 *  clicks reusing your tab and silently opening a new one, with no
		 *  way to tell which state you were in. */
		public long bridgeClientAgeSec = -1;
		public int maxFlips = 50;
		/** Whether the block button asks first — see confirmBlock(). */
		public boolean confirmBlock = true;
		/** Which pair means buy and sell — see AdvisorPanel.buyColor(). */
		public PocketGeTrackerConfig.ColourTheme theme = PocketGeTrackerConfig.ColourTheme.TERMINAL;
		/** Whether buy cards carry the site's flip score row. */
		public boolean showFlipScore = true;
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JLabel status = new JLabel("Advisor off", SwingConstants.LEFT);
	private final JButton gearBtn = new JButton("⚙");
	/** Lives in the pinned top bar, built once — see pauseButton(). */
	private final JButton pauseBtn = new JButton();
	/** The look-and-feel's own colours, kept so the paused tint can be undone
	 *  rather than guessed at. Captured when the button is built. */
	private Color pauseIdleBackground;
	private Color pauseIdleForeground;
	/** The status strip, hidden whenever the status text is empty. */
	private JPanel statusBar;
	/** Three stacked sections describing Advisor.advise()'s current #1 pick
	 *  (adjust nudges, then bank/inventory sells, then buys — same ranked
	 *  list as before, still cycling via its own Next control), split to
	 *  match the website sidebar's own Potential Profit / Analyst Rating /
	 *  Recommended Flip stack instead of one combined card. All three
	 *  always describe the SAME item — cycling to the next suggestion
	 *  re-renders all three together. */
	/** The single recommendation box. One idea at a time, paged with Next —
	 *  selling a stack you hold and buying with idle gp are the same
	 *  question ("what's the best use of a slot right now"), so they share
	 *  one ranked stream rather than sitting in competing boxes you have to
	 *  read separately. Cash sizing still happens, it just isn't a concept
	 *  the player has to look at. */
	private final JPanel recommendationWrap = new JPanel(new BorderLayout());
	private boolean recommendationOpen = true;
	private List<Rec> recommendations = List.of();
	private int recIndex = 0;
	/**
	 * The cards you have already paged past, most recent last, so Back can
	 * return to one.
	 *
	 * It remembers ITEMS, not positions, and that is the whole difficulty.
	 * The list is rebuilt every advisor cycle and re-ranked as prices move,
	 * so index 3 a minute from now is a different flip from index 3 today —
	 * a Back that simply decremented recIndex would take you to whatever had
	 * drifted into that slot, which is worse than not offering Back at all.
	 *
	 * So each entry is {itemId, sell} and Back looks that pair up in the
	 * CURRENT list. Anything that has since been filled, blocked, skipped or
	 * dropped out of the ranking is silently passed over — which is what
	 * "if it's still available" has to mean here.
	 *
	 * Bounded because it is a browsing trail, not an audit log.
	 */
	private final java.util.ArrayDeque<int[]> recTrail = new java.util.ArrayDeque<>();
	private static final int MAX_REC_TRAIL = 32;
	/** While paused, incoming refreshes are ignored so the card you're
	 *  reading can't change under you mid-trade. */
	private boolean paused = false;

	/** One recommendation, already resolved for display — the panel does no
	 *  pricing of its own. Sells carry a buy price when the plugin tracked
	 *  the purchase; buys never do. */
	public static class Rec
	{
		public boolean sell;
		public int itemId;
		public String name;
		public int quantity;
		public long unitPrice;      // what to list/bid at, per item
		public long unitCost;       // sells only: average price paid, 0 if untracked
		/** Buys: projected gain. Tracked sells: measured P&amp;L against what
		 *  you paid. UNTRACKED sells: the stack's proceeds after tax, which
		 *  is emphatically not a gain — see {@link #hasTrackedCost}. */
		public long profit;
		/** Today's after-tax spread on ONE unit, 0 when unknown. The only
		 *  honest "what is this worth" figure for a stack whose cost the
		 *  plugin never saw, since it needs no purchase price at all. */
		public long unitMargin;
		public boolean hasTrackedCost = true;
		/** Sells: how many of {@link #quantity} the plugin never watched you
		 *  buy, and what those fetch after tax. {@link #profit} says nothing
		 *  about them, so the card has to. Both 0 on a fully tracked stack
		 *  and on every buy. */
		public long untrackedQty;
		public long untrackedValue;
		/** SELL only: age in seconds of the bid this is priced off, or 0 when
		 *  it is current. See Advisor.Suggestion.quoteAgeSec — a thin,
		 *  expensive item can be priced off a print an hour old, and a price
		 *  shown without its age is a price presented as current. */
		public long quoteAgeSec;
		/** Buys only: the sell price the projected profit assumes, 0 when
		 *  unknown. A buy card names the price to bid at and then a gp figure
		 *  that only makes sense at some OTHER price — this is that price, so
		 *  the number can be checked rather than taken on faith. Never set on
		 *  a sell: there the action line already IS the sell price, and
		 *  printing it twice was the note on the watching card. */
		public long exitPrice;
		/** Buys only: where the exit price sits in this item's own 30-day
		 *  range, e.g. "93% of 30d range · +26% in 30d". Null when the item is
		 *  mid-range, has no usable range, or no range was fetched — see
		 *  RangePosition for why silence is the default. */
		public String rangeNote;
		public String note;         // optional one-liner (why this, or what capped it)
		/** gp this ties up. Buys only; 0 on a sell, which frees capital
		 *  rather than consuming it. */
		public long capital;
		/** Buys the engine has priced: how good this flip looks right now,
		 *  on the site's own scale. Null on a sell and on a buy the engine
		 *  has not yet seen a series for — no score is not a low score. */
		public TradeEngine.FlipScore score;
	}

	private List<Advisor.Suggestion> currentSuggestions = List.of();
	private Set<Integer> favoriteIds = Set.of();
	private Settings settings = new Settings();
	/** Whatever item is currently in an open GE offer screen — its own
	 *  "BUYING NOW"/"SELLING NOW" section, separate from (and below) the
	 *  inspection card, since the two answer different questions ("what am
	 *  I doing right now" vs "what's our pick"). Null itemId means nothing
	 *  open. */
	private Integer geContextItemId = null;
	/** The offer-screen item whose takeover you have pressed Next past.
	 *
	 *  Opening an offer screen hands this box to that item, which is right
	 *  almost always — but not when you opened it by misclicking, and then
	 *  there was no way back to the suggestion stream without closing the
	 *  screen. Keyed on the ITEM, not a plain boolean, so opening a screen
	 *  for something else still takes over as normal; cleared outright when
	 *  the screen closes, since setGeContext(null) then re-arms it. */
	private Integer geContextDismissedFor = null;
	/** The card currently drawn in the recommendation box, so the top bar's
	 *  Share knows what to post.
	 *
	 *  Share used to be a per-card button, which was one per card too many:
	 *  there is only ever ONE card on screen, so "share the card" needs no
	 *  card-specific state, just the last one rendered. Null while the box is
	 *  showing a login prompt or "looking for flips" — nothing to post. */
	private Card shownCard;
	private String geContextName = "";
	private boolean geContextIsBuy = true;
	private long geContextPrice = 0;
	/** The offer as a whole card's worth of figures — paid, ask, profit,
	 *  quantity — so the takeover draws through the same cardFor as the
	 *  ranked stream. Null when nothing is open. */
	private Rec geContextRec;
	/** Whichever Favorites row was last clicked — takes over the
	 *  inspection card until another row is clicked or dismissed with its
	 *  own close button, at which point it reverts to previewing the #1
	 *  suggestion. Independent of geContext (the GE offer screen) and of
	 *  the Favorites list itself, which never changes when this is set. */
	private FavoritesPanel.Row selectedFavorite = null;
	/** Tracked separately from the Row itself so the card can be re-pointed
	 *  at fresh data — see {@link #refreshSelectedFrom}. */
	private int selectedFavoriteId = -1;
	/** Assume logged in until told otherwise, so a missed state event can
	 *  never wedge the panel on the login message while the game is live. */
	private boolean loggedIn = true;

	public AdvisorPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel(new BorderLayout(6, 0));
		north.setOpaque(false);

		/* A plain JButton.setBackground() is silently ignored by a lot of
		   Swing look-and-feels unless the button is ALSO told to actually
		   paint its content area — without these three calls this button
		   rendered as RuneLite's default (dark, easy to miss) button chrome
		   no matter what color was set here, which is almost certainly why
		   this kept reading as "invisible" despite being gold in the source.
		   Small and icon-only (Copilot puts its own gear in a corner, not a
		   full-width bar) — the fix was never needing the extra width, just
		   these three flags. */
		gearBtn.setText("⚙");
		/* Names the thing, then says what is in it. It listed five nouns and
		   never the word that tells you what pressing it does. */
		gearBtn.setToolTipText("<html><b>Settings</b><br>Advisor on/off, how often it re-checks, minimum profit,"
			+ "<br>blocked items, the website link, and a colour key.</html>");
		gearBtn.setOpaque(true);
		gearBtn.setContentAreaFilled(true);
		gearBtn.setBorderPainted(true);
		gearBtn.setFocusPainted(false);
		gearBtn.setFont(gearBtn.getFont().deriveFont(Font.BOLD, 13f));
		gearBtn.setMargin(new Insets(2, 6, 2, 6));
		gearBtn.setPreferredSize(new Dimension(26, 22));
		gearBtn.setMaximumSize(new Dimension(26, 22));
		/* Captured BEFORE anything is painted on top, so closing the menu can
		   put the look-and-feel's own colours back rather than a guess at
		   them — same approach as the pause button's idle pair. */
		gearIdleBackground = gearBtn.getBackground();
		gearIdleForeground = gearBtn.getForeground();
		setGearOpen(false);
		gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		/* Toggle, not just open. Swing dismisses an open JPopupMenu on the
		   mouse PRESS anywhere outside it, and the gear is outside it \u2014 so by
		   the time the button's action fires the popup is already gone, the
		   action opens a fresh one, and the menu looks like it cannot be
		   closed by the control that opened it.
		   isVisible() cannot see this: it is false by then. The dismissal
		   timestamp can, because that press hid the popup microseconds
		   earlier. Anything within the window is the click that closed it, so
		   it opens nothing. */
		gearBtn.addActionListener(e ->
		{
			if (System.currentTimeMillis() - settingsClosedAt < POPUP_REOPEN_GUARD_MS)
			{
				return;
			}
			showSettingsPopup();
		});
		// Lives at the bottom of the whole sidebar now (see MainPanel, next
		// to the "Open PocketGE" link) instead of up here — not added to
		// `north` itself, just built and kept as a field so MainPanel can
		// place the actual button wherever it wants.

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setFont(status.getFont().deriveFont(11.5f));
		north.add(status, BorderLayout.CENTER);
		// Hidden while it has nothing to say. An empty JLabel still claims a
		// line's height plus this panel's gap, and the status is blank in
		// normal running — that was a strip of dead space under the toolbar.
		north.setVisible(false);
		statusBar = north;
		add(north, BorderLayout.NORTH);

		recommendationWrap.setOpaque(false);
		recommendationWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		/* One box, not two. A watchlist click used to open a SECOND card
		   underneath the recommendation, so the panel showed two items at
		   once and you had to work out which number belonged to which — and
		   the two cards weren't even the same shape. There is one card now
		   and whatever you last pointed at owns it. */
		center.add(recommendationWrap);
		/* NORTH, not CENTER: BorderLayout.CENTER stretches its child to fill
		   the panel, which hands the BoxLayout above spare height to spread
		   across its children — the empty-space bug. NORTH gives it exactly
		   its preferred height instead, so nothing stretches. */
		JPanel centerHolder = new JPanel(new BorderLayout());
		centerHolder.setOpaque(false);
		centerHolder.add(center, BorderLayout.NORTH);
		add(centerHolder, BorderLayout.CENTER);
	}

	/** Builds a fresh popup on every open so it always reflects the latest
	 *  state stashed by update() — cheaper than keeping a live popup synced
	 *  while it's closed, and the popup is thrown away on dismiss anyway.
	 *  Everything that otherwise lives only in RuneLite's own plugin config
	 *  screen (the wrench icon, several clicks away) is here too, so routine
	 *  tweaks never require leaving this panel. */
	/** When the settings popup last closed. See the gear's action listener:
	 *  this is what lets a second click on the gear close the menu instead of
	 *  reopening it. */
	private long settingsClosedAt;
	/** Long enough to cover the gap between Swing hiding the popup and the
	 *  button firing (both inside one click), short enough that a deliberate
	 *  second click is never swallowed. */
	private static final long POPUP_REOPEN_GUARD_MS = 250;
	/** The look-and-feel's own colours for the gear, kept so closing the
	 *  settings menu can restore them exactly. See setGearOpen. */
	private Color gearIdleBackground;
	private Color gearIdleForeground;

	/**
	 * Gold only while the settings menu is actually open.
	 *
	 * It used to be gold always, which made the one button that opens a menu
	 * you rarely need the brightest object in the sidebar — brighter than the
	 * profit figure, and sitting first in a strip of five otherwise plain
	 * buttons, so the eye went to it every time the panel redrew. A
	 * permanently highlighted control is not highlighting anything.
	 *
	 * Lit while open, it is doing the job highlighting is for: saying which
	 * thing on screen this menu belongs to.
	 */
	private void setGearOpen(boolean open)
	{
		gearBtn.setBackground(open ? GOLD : gearIdleBackground);
		gearBtn.setForeground(open ? Color.BLACK : gearIdleForeground);
		/* A 1px line either way, only the colour changes. Restoring the
		   look-and-feel's own border for the idle state was the obvious
		   thing and it was wrong: that border carries several pixels of
		   inset, which inside a 26px button left the gear glyph no room and
		   Swing drew "…" in its place. */
		gearBtn.setBorder(BorderFactory.createLineBorder(open ? GOLD.darker() : ColorScheme.MEDIUM_GRAY_COLOR, 1));
	}

	private void showSettingsPopup()
	{
		JPopupMenu popup = new JPopupMenu();
		popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
			{
				setGearOpen(true);
			}

			@Override
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e)
			{
				settingsClosedAt = System.currentTimeMillis();
				setGearOpen(false);
			}

			@Override
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e)
			{
				settingsClosedAt = System.currentTimeMillis();
				setGearOpen(false);
			}
		});
		popup.setBackground(ColorScheme.DARK_GRAY_COLOR);
		popup.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);

		JCheckBox advisorBox = checkbox("Flip advisor (needs live prices)", settings.advisorOn);
		advisorBox.addActionListener(e -> actions.setAdvisorEnabled(advisorBox.isSelected()));
		advisorBox.setAlignmentX(0f);
		content.add(advisorBox);
		content.add(Box.createVerticalStrut(8));

		content.add(controlRow("Re-check every", intervalRow()));
		content.add(Box.createVerticalStrut(8));

		content.add(controlRow("Min. profit per suggestion", minProfitRow()));
		content.add(Box.createVerticalStrut(8));

		content.add(controlRow("Buy / sell colours", colourThemeRow()));
		content.add(Box.createVerticalStrut(8));

		final JCheckBox scoreBox = checkbox("Flip score on cards", settings.showFlipScore);
		scoreBox.setToolTipText(tip("The 0–100 score and verdict on buy ideas",
			"Off hides the row. The ranking underneath is unchanged."));
		scoreBox.addActionListener(e -> actions.setShowFlipScore(scoreBox.isSelected()));
		scoreBox.setAlignmentX(0f);
		content.add(scoreBox);
		content.add(Box.createVerticalStrut(8));

		JLabel blkTitle = new JLabel("Never recommend");
		blkTitle.setForeground(GOLD);
		blkTitle.setAlignmentX(0f);
		blkTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		content.add(blkTitle);

		JPanel blockChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		blockChips.setOpaque(false);
		if (settings.blocked.isEmpty())
		{
			JLabel empty = new JLabel("Nothing blocked");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			blockChips.add(empty);
		}
		else
		{
			for (String name : settings.blocked)
			{
				blockChips.add(chip(name));
			}
		}
		content.add(blockChips);
		content.add(sectionDivider());

		JLabel siteTitle = new JLabel("Website link");
		siteTitle.setForeground(GOLD);
		siteTitle.setAlignmentX(0f);
		siteTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		content.add(siteTitle);

		JCheckBox bridgeBox = checkbox("Local website bridge", settings.bridgeOn);
		bridgeBox.setAlignmentX(0f);
		content.add(bridgeBox);
		bridgeBox.addActionListener(e -> actions.setLocalBridge(bridgeBox.isSelected()));
		content.add(Box.createVerticalStrut(4));
		content.add(controlRow("Bridge port", stepperRow(settings.bridgePort, 1024, 65535, 1, actions::setBridgePort)));
		if (settings.bridgeOn)
		{
			final boolean linked = settings.bridgeClientAgeSec >= 0 && settings.bridgeClientAgeSec <= 40;
			JLabel link = new JLabel(linked
				? "\u25CF  Website tab linked (" + settings.bridgeClientAgeSec + "s ago)"
				: "\u25CB  No website tab linked");
			link.setForeground(linked ? POSITIVE : ColorScheme.LIGHT_GRAY_COLOR);
			link.setFont(link.getFont().deriveFont(11f));
			link.setBorder(BorderFactory.createEmptyBorder(2, 4, 4, 4));
			link.setToolTipText(linked
				? "A PocketGE page is polling the bridge, so chart clicks will open in that tab."
				: "<html>Nothing is polling the bridge, so chart clicks will open a new page.<br>"
					+ "Open pocketge.com, then Bank \u2192 Connect. If it still says this,<br>"
					+ "hard-reload the site (Ctrl+Shift+R) \u2014 a cached copy of the page<br>"
					+ "predates bridge support.</html>");
			content.add(link);
		}
		content.add(Box.createVerticalStrut(8));

		content.add(controlRow("Flips to keep", stepperRow(settings.maxFlips, 5, 200, 5, actions::setMaxFlips)));
		content.add(sectionDivider());

		/*
		 * What the colours mean.
		 *
		 * The plugin paints outlines in the bank, round inventory stacks, on
		 * the Exchange's own offer boxes and on the eight squares in the
		 * sidebar — and every one of them was explained only in a tooltip you
		 * had to know to hover. "I still don't know what the colours mean" is
		 * a fair thing to say about a tool that colours four different
		 * surfaces and writes the key nowhere you would look for it.
		 *
		 * Settings is where it goes: it is reference material you read once,
		 * not a control, and the gear is the one button that is always there
		 * regardless of what the panel is currently showing.
		 */
		JLabel keyTitle = new JLabel("What the colours mean");
		keyTitle.setForeground(GOLD);
		keyTitle.setAlignmentX(0f);
		keyTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		content.add(keyTitle);
		/* Read off the classes that actually paint them, never retyped — see
		   GeSlotsPanel's note on why those constants are package-private. */
		content.add(legendRow(GeSlotsPanel.OK_COLOR, "Priced to fill",
			"An offer the market can still reach — leave it alone. In the bank it means a stack worth selling."));
		content.add(legendRow(GeSlotsPanel.ADJUST_COLOR, "Priced off the market",
			"Nobody is going to fill this at your price. Hover the offer for the price to re-list at."));
		content.add(legendRow(GeSlotsPanel.COLLECT_COLOR, "Do this next",
			"Where the plugin is pointing: the stack to sell, the Buy button to press, or an offer that is done and waiting to be collected."));
		content.add(legendRow(ColorScheme.MEDIUM_GRAY_COLOR, "Left alone",
			"An empty slot, or one you right-clicked to say you are pricing it yourself."));
		content.add(Box.createVerticalStrut(4));
		content.add(legendRow(FavoritesPanel.HIGH5D, "▲ 5D  at a 5-day high",
			"On a watchlist row: the price is in the top of its own 5-day range."));
		content.add(legendRow(FavoritesPanel.LOW5D, "▼ 5D  at a 5-day low",
			"On a watchlist row: the price is in the bottom of its own 5-day range."));

		popup.add(content);
		popup.show(gearBtn, 0, gearBtn.getHeight() + 4);
	}

	/**
	 * One line of the colour key: a swatch painted in the real colour, the
	 * thing it means, and the detail on hover.
	 *
	 * The swatch is a filled square with a darker rim rather than a bare
	 * block, because three of these colours appear in game as OUTLINES round
	 * a stack or an offer box, and a solid chip is a poor likeness of a ring.
	 * The rim is what makes it read as "the colour of an edge".
	 */
	private JPanel legendRow(Color colour, String meaning, String detail)
	{
		final JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.setAlignmentX(0f);
		row.setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
		row.setToolTipText("<html><body style='width:210px'>" + detail + "</body></html>");

		final JPanel swatch = new JPanel();
		swatch.setPreferredSize(new Dimension(11, 11));
		swatch.setMinimumSize(new Dimension(11, 11));
		swatch.setMaximumSize(new Dimension(11, 11));
		swatch.setBackground(colour);
		swatch.setBorder(BorderFactory.createLineBorder(colour.darker(), 1));
		/* BorderLayout stretches WEST to the row's full height, which on a
		   12px line turns the square into a bar. Wrap it so it keeps its
		   shape. */
		final JPanel hold = new JPanel(new java.awt.FlowLayout(java.awt.FlowLayout.LEFT, 0, 2));
		hold.setOpaque(false);
		hold.add(swatch);
		row.add(hold, BorderLayout.WEST);

		final JLabel text = new JLabel(meaning);
		text.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		text.setFont(text.getFont().deriveFont(Font.PLAIN, 11f));
		row.add(text, BorderLayout.CENTER);
		/* Rows must not stretch vertically when BoxLayout has spare height —
		   otherwise the last one absorbs it all and the key looks ragged. */
		row.setMaximumSize(new Dimension(Short.MAX_VALUE, row.getPreferredSize().height + 4));
		return row;
	}

	private JPanel sectionDivider()
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setAlignmentX(0f);
		wrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
		JPanel line = new JPanel();
		line.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		line.setPreferredSize(new Dimension(0, 1));
		wrap.add(line, BorderLayout.CENTER);
		return wrap;
	}

	private JCheckBox checkbox(String label, boolean selected)
	{
		JCheckBox box = new JCheckBox(label, selected);
		box.setOpaque(false);
		box.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		box.setFont(box.getFont().deriveFont(12f));
		box.setFocusPainted(false);
		return box;
	}

	/** A -/value/+ stepper built from the same JButton/JLabel this popup
	 *  already uses elsewhere — a JSpinner's text field is a known rough
	 *  edge inside a JPopupMenu (focus handling can close the popup out
	 *  from under a click), so this sidesteps that entirely. */
	private JPanel stepperRow(int value, int min, int max, int step, java.util.function.IntConsumer onChange)
	{
		JPanel row = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		row.setOpaque(false);
		JLabel valueLabel = new JLabel(String.valueOf(value));
		valueLabel.setForeground(Color.WHITE);
		valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 12f));
		valueLabel.setPreferredSize(new Dimension(50, 20));
		valueLabel.setHorizontalAlignment(SwingConstants.CENTER);
		final int[] current = { value };
		JButton minus = smallBtn("−", "Decrease", e ->
		{
			current[0] = Math.max(min, current[0] - step);
			valueLabel.setText(String.valueOf(current[0]));
			onChange.accept(current[0]);
		});
		JButton plus = smallBtn("+", "Increase", e ->
		{
			current[0] = Math.min(max, current[0] + step);
			valueLabel.setText(String.valueOf(current[0]));
			onChange.accept(current[0]);
		});
		row.add(minus);
		row.add(valueLabel);
		row.add(plus);
		return row;
	}

	private JPanel controlRow(String label, JPanel buttonRow)
	{
		JPanel wrap = new JPanel(new BorderLayout(0, 3));
		wrap.setOpaque(false);
		wrap.setAlignmentX(0f);
		JLabel lbl = new JLabel(label);
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lbl.setFont(lbl.getFont().deriveFont(11f));
		wrap.add(lbl, BorderLayout.NORTH);
		wrap.add(buttonRow, BorderLayout.CENTER);
		return wrap;
	}

	private JPanel intervalRow()
	{
		JPanel row = new JPanel(new GridLayout(1, 0, 3, 0));
		row.setOpaque(false);
		for (PocketGeTrackerConfig.AdjustInterval v : PocketGeTrackerConfig.AdjustInterval.values())
		{
			JButton b = segmentButton(v.toString());
			setActive(b, v == settings.interval);
			b.addActionListener(e -> actions.setAdjustInterval(v));
			row.add(b);
		}
		return row;
	}

	/**
	 * The minimum-profit floor.
	 *
	 * A dropdown rather than the segmented row "Re-check every" uses: seven
	 * options at that button size want about 263px and the popup has roughly
	 * 220, so they would either wrap or squeeze the labels past reading.
	 */
	/**
	 * The buy/sell colour picker, with each option drawn in its own two
	 * colours rather than merely named.
	 *
	 * "Neon" and "Sunset" say nothing about what you are choosing, and this is
	 * a choice about how something LOOKS — so the swatch is the control and
	 * the name is the caption, exactly as pocketge.com's own picker does it.
	 */
	private JPanel colourThemeRow()
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		final JComboBox<PocketGeTrackerConfig.ColourTheme> box =
			new JComboBox<>(PocketGeTrackerConfig.ColourTheme.values());
		box.setSelectedItem(settings.theme);
		box.setFont(box.getFont().deriveFont(11f));
		box.setToolTipText("<html><b>Which two colours mean buy and sell</b>"
			+ "<br>The same four pairs pocketge.com offers, with the same values,"
			+ "<br>so running both does not mean learning two schemes."
			+ "<br>Every pair stays readable with red-green colour blindness."
			+ "<br>The green, red and gold that mean an offer is fine, stranded"
			+ "<br>or worth acting on are states, not directions, and do not change.</html>");
		box.setRenderer(new javax.swing.DefaultListCellRenderer()
		{
			@Override
			public java.awt.Component getListCellRendererComponent(javax.swing.JList<?> list, Object value,
				int index, boolean selected, boolean focused)
			{
				super.getListCellRendererComponent(list, value, index, selected, focused);
				if (value instanceof PocketGeTrackerConfig.ColourTheme)
				{
					final PocketGeTrackerConfig.ColourTheme t = (PocketGeTrackerConfig.ColourTheme) value;
					setIcon(swatch(t.buy(), t.sell()));
					setText(t.toString());
				}
				return this;
			}
		});
		box.addActionListener(e ->
		{
			final Object v = box.getSelectedItem();
			if (v instanceof PocketGeTrackerConfig.ColourTheme)
			{
				actions.setColourTheme((PocketGeTrackerConfig.ColourTheme) v);
			}
		});
		row.add(box, BorderLayout.CENTER);
		return row;
	}

	/** Two blocks of colour, buy then sell — the pair as it will appear. */
	private static Icon swatch(Color buy, Color sell)
	{
		final BufferedImage img = new BufferedImage(22, 10, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(buy);
		g.fillRect(0, 0, 10, 10);
		g.setColor(sell);
		g.fillRect(12, 0, 10, 10);
		g.dispose();
		return new ImageIcon(img);
	}

	private JPanel minProfitRow()
	{
		final JPanel row = new JPanel(new BorderLayout());
		row.setOpaque(false);
		final JComboBox<PocketGeTrackerConfig.MinProfit> box =
			new JComboBox<>(PocketGeTrackerConfig.MinProfit.values());
		box.setSelectedItem(settings.minProfit);
		box.setFont(box.getFont().deriveFont(11f));
		box.setToolTipText("<html>Buy ideas whose whole-limit profit lands under this, after tax, are not offered."
			+ "<br>Auto keeps the plugin's own low floor and always shows you something."
			+ "<br>Anything else is a hard floor \u2014 with a big number and a small bank"
			+ "<br>the suggestions can legitimately run dry, and that empty panel is the answer.");
		box.addActionListener(e ->
		{
			final Object v = box.getSelectedItem();
			if (v instanceof PocketGeTrackerConfig.MinProfit)
			{
				actions.setMinProfit((PocketGeTrackerConfig.MinProfit) v);
			}
		});
		row.add(box, BorderLayout.CENTER);
		return row;
	}

	private JButton segmentButton(String label)
	{
		JButton b = new JButton(label);
		b.setFocusPainted(false);
		b.setFont(b.getFont().deriveFont(11f));
		b.setMargin(new Insets(3, 4, 3, 4));
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return b;
	}

	private void setActive(JButton b, boolean active)
	{
		b.setBackground(active ? GOLD : ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(active ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
	}

	public void setStatus(String s)
	{
		status.setText(s != null ? s : "");
		statusBar.setVisible(s != null && !s.isEmpty());
	}

	/** The gear/settings button itself, so MainPanel can place it at the
	 *  bottom of the sidebar (next to the website link) instead of here. */
	public JButton settingsButton()
	{
		return gearBtn;
	}

	/**
	 * Pause, for the pinned top bar.
	 *
	 * It moved out of the card's control row for the same reason Share did,
	 * with one addition: pausing is a mode, not an action on this card. The
	 * card it was attached to is exactly the thing that disappears when the
	 * advisor moves on, so the control for "stop moving on" travelled with
	 * the content it was meant to hold still. Up here it is in one fixed
	 * place whatever is on screen.
	 *
	 * Because the top bar is built once and never rebuilt, the button has to
	 * carry its own state — hence the field. It goes gold while paused, the
	 * same "this is on" language the gear uses, so the mode is legible from
	 * the strip without reading the card's footnote.
	 */
	public JButton pauseButton()
	{
		if (pauseBtn.getActionListeners().length > 0)
		{
			/* Already built. Returning it wired twice would toggle the mode
			   back to where it started on every click, which reads as a dead
			   button rather than as a double-fire. */
			return pauseBtn;
		}
		pauseBtn.setIcon(PAUSE_ICON);
		pauseBtn.setOpaque(true);
		pauseBtn.setContentAreaFilled(true);
		pauseBtn.setFocusPainted(false);
		pauseBtn.setMargin(new Insets(2, 6, 2, 6));
		pauseBtn.setPreferredSize(new Dimension(30, 22));
		pauseBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		pauseIdleBackground = pauseBtn.getBackground();
		pauseIdleForeground = pauseBtn.getForeground();
		syncPauseButton();
		pauseBtn.addActionListener(e ->
		{
			paused = !paused;
			syncPauseButton();
			renderRecommendation();
		});
		return pauseBtn;
	}

	/** Keep the top-bar button telling the truth about the mode it toggles. */
	private void syncPauseButton()
	{
		pauseBtn.setToolTipText(paused
			? "<html><b>Paused</b><br>The card is being held on screen. Click to let it"
				+ "<br>start moving with the market again.</html>"
			: "<html><b>Pause the suggestions</b><br>Holds the current card on screen so it does not change"
				+ "<br>under you while you place the offer.</html>");
		pauseBtn.setBackground(paused ? GOLD : pauseIdleBackground);
		pauseBtn.setForeground(paused ? Color.BLACK : pauseIdleForeground);
	}

	/** Rebuild everything. Call on the EDT.
	 *  {@code favoriteIds} decides whether a
	 *  card's star renders filled or hollow. {@code settings} is stashed for
	 *  the next time the gear-icon popup opens — that's where every field on
	 *  it (advisor on/off, interval, blocklist, bridge, flip count)
	 *  gets edited. */
	public void update(List<Advisor.Suggestion> suggestions, Set<Integer> favoriteIds, Settings settings)
	{
		this.favoriteIds = favoriteIds != null ? favoriteIds : Set.of();
		/* The card floor is measured from the tallest shape a card can take,
		   and the score row is part of that shape — so a change to whether it
		   is drawn has to send the floor back for re-measuring. See cardFloor. */
		if (settings != null && this.settings != null && settings.showFlipScore != this.settings.showFlipScore)
		{
			cardFloor = -1;
		}
		this.settings = settings != null ? settings : this.settings;

		// Kept for the bank/GE overlays and the inspection card's rating
		// lookup. The sidebar itself no longer renders this ranked list
		// directly: it splits into "sell what you hold" and "deploy your
		// cash", which are the two decisions actually being made.
		currentSuggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
		renderRecommendation();

		revalidate();
		repaint();
	}

	/** Called when a Favorites row is clicked. The clicked item TAKES OVER
	 *  the one recommendation box until another row is clicked, Next is
	 *  pressed, or it's dismissed with the card's own close button. Pass null
	 *  to dismiss.
	 *
	 *  This BEATS an open GE offer screen. Both want the same box, but only
	 *  one of them is something you just did: opening an offer screen is
	 *  passive context, clicking a row is a request. While the offer card
	 *  outranked it, clicking Diamond necklace in the watchlist with an
	 *  Emerald necklace offer open simply did nothing, with no hint as to
	 *  why. Reuses the offer card's own Next dismissal, so opening a screen
	 *  for a different item still takes over as normal. */
	public void setSelectedItem(FavoritesPanel.Row r)
	{
		final int previous = this.selectedFavoriteId;
		this.selectedFavorite = r;
		this.selectedFavoriteId = r != null ? r.id : -1;
		if (r != null && geContextItemId != null)
		{
			this.geContextDismissedFor = geContextItemId;
		}
		/* Every route in and out of the inspection card comes through here —
		   a watchlist click, the ✕, and refreshSelectedFrom giving up on an
		   unfavorited item — so this is the one place that can tell the plugin
		   which item now needs a price series. */
		if (previous != this.selectedFavoriteId)
		{
			actions.onSelectedItemChanged(r != null ? r.id : null);
		}
		renderRecommendation();
	}

	/**
	 * Re-point the inspection card at the freshest Row for the same item.
	 *
	 * The Row a click hands us is a snapshot: FavoritesPanel.update()
	 * rebuilds every row object on each refresh, and the click closure
	 * captured whichever one existed at the time. Holding that original
	 * meant the card kept rendering what was known when you clicked — so a
	 * row clicked before the first price fetch landed (or before its rating
	 * was computed) stayed blank forever, showing nothing but the item name
	 * no matter how many refreshes went by. Look the item up again by id
	 * instead, every time the list is rebuilt.
	 */
	public void refreshSelectedFrom(List<FavoritesPanel.Row> rows)
	{
		if (selectedFavoriteId < 0 || rows == null)
		{
			return;
		}
		for (FavoritesPanel.Row r : rows)
		{
			if (r.id == selectedFavoriteId)
			{
				selectedFavorite = r;
				renderRecommendation();
				return;
			}
		}
		// Unfavorited while being inspected — hand the box back to the
		// recommendation stream rather than keeping a card for something no
		// longer on the list.
		selectedFavorite = null;
		selectedFavoriteId = -1;
		/* Unfavorited while being inspected still ends the inspection, so the
		   series fetched for it is no longer wanted. */
		actions.onSelectedItemChanged(null);
		renderRecommendation();
	}

	/** The watchlist takeover: the item you clicked, in the same card as
	 *  everything else, with Next still on it so one press puts you back in
	 *  the flip stream. */
	private JPanel favoriteBody()
	{
		final FavoritesPanel.Row r = selectedFavorite;
		// The favorites row already pulses its border for this (see
		// FavoritesPanel.wirePulse) but that glow doesn't carry over once you
		// click in — say it in words here too, same as the website's own
		// ▲ 5D / ▼ 5D badge, rather than relying on remembering which row was
		// glowing before you clicked it.
		final String extremeBadge = extremeBadgeText(r.tier);
		final String priceText = r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : null;

		final long edge = (r.targetBuy > 0 && r.targetSell > 0)
			? r.targetSell - r.targetBuy - FlipTracker.taxPerItem(r.targetSell, r.id) : 0;

		Card c = new Card();
		c.accent = GOLD;
		c.itemId = r.id;
		c.name = r.name;
		/* The live price is dropped whenever the target pair is shown below,
		   because the pair already contains it: a Mithril bar card read
		   "951 gp" and then "buy 951 \u2192 sell 979" \u2014 the same number twice,
		   costing a headline to repeat what the next line says better. */
		final String headPrice = edge > 0 ? null : priceText;
		c.actionText = extremeBadge != null && headPrice != null ? extremeBadge + "   \u00B7   " + headPrice
			: extremeBadge != null ? extremeBadge : headPrice;
		/* A HIGH is green and a LOW is gold, at every tier — the same pairing
		   the watchlist rows and the website use. This line used to take the
		   card's gold accent whatever it said, so a high rendered in the low
		   tier's colour and contradicted its own arrow. */
		c.actionColor = r.tier.isHigh() ? HIGH5D : r.tier.isLow() ? LOW5D : null;

		/* A spread narrower than the 2% tax makes potentialProfit (edge x
		   the 4h limit) a large NEGATIVE number, and this card used to
		   headline it — a Diamond necklace with a 9 gp spread and a 39 gp
		   tax rendered as a flat "-666K gp profit", which reads like the
		   item lost you money rather than "there's no margin here today".
		   Only ever show a profit figure when there IS one; when there
		   isn't, say that instead. */
		if (edge > 0)
		{
			c.subText = "buy " + QuantityFormatter.quantityToStackSize(r.targetBuy)
				+ " → sell " + QuantityFormatter.quantityToStackSize(r.targetSell);
			/* Suffixes stay SHORT. "gp profit at the 4h limit" wanted 247px on
			   a line that gets 211 — the number survived and the qualifier got
			   cut, which is the wrong half to lose. The full sentence is on
			   the tooltip. */
			if (r.potentialProfit > 0)
			{
				c.profitValue = r.potentialProfit;
				c.profitSuffix = "gp / 4h limit";
				c.profitTooltip = "Profit after the 2% GE tax if you buy and sell a full 4-hour buy limit ("
					+ (r.limit > 0 ? QuantityFormatter.quantityToStackSize(r.limit) : "the limit") + ").";
			}
			else
			{
				c.profitValue = edge;
				c.profitSuffix = "gp / item";
				c.profitTooltip = "Margin per item after the 2% GE tax, at these targets.";
			}
		}
		else
		{
			c.subText = "No margin after tax right now";
		}

		/* Same question as on a flip card: can I afford this? It was missing
		   here, so clicking a watchlist row gave you a target pair and a
		   profit with no idea what it would tie up. */
		if (edge > 0 && r.targetBuy > 0 && r.limit > 0)
		{
			c.capital = (long) r.targetBuy * r.limit;
		}
		c.close = smallBtn("✕", "Stop watching — back to the recommended flip",
			e -> setSelectedItem(null));

		JPanel controls = controlsRow();
		/* Share has moved to the pinned top bar. It was the least-pressed
		   control sitting in the most-pressed row, taking width from Next,
		   hold and block on a 225px sidebar — and it never needed to be
		   per-card, because there is only ever one card on screen. */
		final boolean fav = favoriteIds.contains(r.id);
		addControl(controls, bigIconBtn(fav ? STAR_FILLED_ICON : STAR_HOLLOW_ICON,
			fav ? "Remove " + r.name + " from favorites" : "Add " + r.name + " to favorites",
			e -> actions.toggleFavorite(r.id, r.name)));
		addControl(controls, bigIconBtn(BLOCK_ICON, "Never recommend " + r.name + " again",
			e -> { if (confirmBlock(r.name)) { actions.block(r.name); } }));
		// The pager rides the right edge, as it does on the website.
		if (!recommendations.isEmpty())
		{
			addSpacer(controls);
			if (canGoBack())
			{
				addControl(controls, backButton());
			}
			addControl(controls, nextButton());
		}
		c.controls = controls;
		/* No "From your watchlist" footnote: you got here by clicking your
		   watchlist, so it only ever told you something you had just done. */
		shownCard = c;
		return buildCard(c);
	}

	/** Same card shell as everything else so the sidebar reads as "waiting"
	 *  rather than "empty" before login. */
	private JPanel loginPrompt()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setAlignmentX(0f);
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, GOLD),
			BorderFactory.createEmptyBorder(11, 12, 11, 10)));
		// Two plain labels rather than one <html> one. An HTML JLabel here
		// rendered its box but no visible text in the client, and a message
		// whose whole job is explaining an empty panel cannot itself be the
		// thing that fails to draw.
		/* The ONE logged-out message in the sidebar. It used to be a heading
		   plus a subtitle here, and then the watchlist, the finder and the
		   history each said their own version underneath — four blocks
		   explaining the same single fact, which made a logged-out panel look
		   broken rather than idle. One line, and everything below it simply
		   goes quiet. */
		JLabel title = new JLabel("Log in to the game to track flips");
		title.setForeground(GOLD);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 12f));
		title.setAlignmentX(0f);
		p.add(title);
		return p;
	}

	/** Called whenever the plugin detects (or clears) an open GE offer
	 *  screen.
	 *
	 *  This TAKES OVER the recommendation box rather than adding a card
	 *  below it. Once you've opened an offer screen for an item, the price
	 *  for THAT item is the only thing you need — a separate "here's our
	 *  pick" card underneath was competing for attention at exactly the
	 *  wrong moment, and the two boxes said such similar things that it
	 *  wasn't obvious which number belonged to the screen you were on. */
	public void setGeContext(Integer itemId, String name, boolean isBuy, long price)
	{
		Rec r = null;
		if (itemId != null)
		{
			r = new Rec();
			r.itemId = itemId;
			r.name = name;
			r.sell = !isBuy;
			r.unitPrice = price;
		}
		setGeContext(r);
	}

	/** The offer on screen, as the figures a card is built from; null when
	 *  the screen closed. */
	public void setGeContext(Rec r)
	{
		final Integer itemId = r != null ? r.itemId : null;
		if (itemId == null || !itemId.equals(geContextDismissedFor))
		{
			/* A different item, or the screen closed: whatever you dismissed
			   is no longer what is on screen, so the takeover is earned
			   again. Without this, pressing Next once would suppress the
			   offer card for every subsequent offer too. */
			this.geContextDismissedFor = null;
		}
		this.geContextRec = r;
		this.geContextItemId = itemId;
		this.geContextName = r != null && r.name != null ? r.name : "";
		this.geContextIsBuy = r == null || !r.sell;
		this.geContextPrice = r != null ? r.unitPrice : 0;
		renderRecommendation();
	}

	/** The offer-screen takeover: what to type, for the item actually on
	 *  screen. Same shell as a recommendation so the box doesn't visibly
	 *  change shape when it switches over. */
	private JPanel geContextBody()
	{
		final int itemId = geContextItemId;
		final String name = geContextName;
		final Rec r = geContextRec;

		/* The same card as a recommendation, for the trade you are actually
		   about to place. This was three lines — "Sell at 779 gp each / also
		   written on the offer screen / Offer screen open" — in small grey
		   over an empty box, at the one moment you are committing gold. Now
		   it says what you paid, what to ask, what that makes and how many,
		   from the same figures and in the same places as every other card,
		   so nothing has to be re-learned on the offer screen. */
		final Card c = cardFor(r);
		/* The side's colour on the accent, unlike the ranked cards' constant
		   gold: this box has been taken over by the screen you have open,
		   and the stripe is what says so. */
		c.accent = r.sell ? sellColor() : buyColor();
		c.provenance = "offer screen open";
		if (c.footnote == null)
		{
			c.footnote = "Written onto the offer screen";
			c.footnoteWarn = false;
		}

		JPanel controls = controlsRow();
		/* Next is here for the misclick: you opened an offer screen for the
		   wrong item and want the suggestion stream back without having to
		   close the screen first. Before this the takeover was a one-way
		   door for as long as the screen stayed up. No Back beside it: this
		   card is not a place in the stream, so there is nothing to go back
		   from. */
		final boolean fav = favoriteIds.contains(itemId);
		addControl(controls, bigIconBtn(fav ? STAR_FILLED_ICON : STAR_HOLLOW_ICON,
			fav ? "Remove " + name + " from favorites" : "Add " + name + " to favorites",
			e -> actions.toggleFavorite(itemId, name)));
		addControl(controls, bigIconBtn(BLOCK_ICON, "Never recommend " + name + " again",
			e -> { if (confirmBlock(name)) { actions.block(name); } }));
		addSpacer(controls);
		addControl(controls, nextButton());
		c.controls = controls;
		shownCard = c;
		return buildCard(c);
	}

	/** Matches the site's text-overflow ellipsis on the collapsed flip
	 *  card — long names get cut with an ellipsis; the full name is still
	 *  reachable via the row's tooltip. */
	/** A duration in seconds as the coarsest unit that still says something:
	 *  "18m", "2h", "1d". Only ever used for a price age, where the reader
	 *  wants to know the order of magnitude and never the seconds. */
	static String agoText(long seconds)
	{
		final long mins = Math.max(1, seconds / 60);
		if (mins < 60)
		{
			return mins + "m";
		}
		final long hours = mins / 60;
		return hours < 24 ? hours + "h" : (hours / 24) + "d";
	}

	private static String truncateName(String name)
	{
		// 12, not 16. Measured in a 225px sidebar: "Sell 3 x Bandos chestpla..."
		// wants 209px and gets 178, so Swing ellipsizes it a second time and
		// the quantity prefix is what pays for it. Cut the name first instead;
		// the full one is always on the row's tooltip.
		return truncateName(name, 12);
	}

	private static String truncateName(String name, int max)
	{
		return name.length() > max ? name.substring(0, max - 1) + "…" : name;
	}


	/** Shared shell for the three top-of-panel sections — a small caps
	 *  title, an optional trailing control (only Recommended Flip uses this,
	 *  for its Next button — matches the website's own header-row Next
	 *  placement), and a chevron that toggles the body's visibility. Rebuilt
	 *  on every render() call like everything else in this panel, so the
	 *  toggle just flips the tracked boolean and re-renders. */
	private JPanel collapsibleSection(String title, JButton extra, boolean open, Runnable onToggle, JPanel body)
	{
		/* Clamped to its own preferred height on purpose. These sections
		   live in a BoxLayout.Y_AXIS column, and a JPanel reports an
		   unbounded maximum height, so BoxLayout hands every one of them a
		   share of whatever viewport height is left over and stretches them
		   — which is what turned this whole area into mostly empty space
		   with the content floating in it. */
		JPanel wrap = new JPanel()
		{
			@Override
			public java.awt.Dimension getMaximumSize()
			{
				return new java.awt.Dimension(Short.MAX_VALUE, getPreferredSize().height);
			}
		};
		wrap.setLayout(new BoxLayout(wrap, BoxLayout.Y_AXIS));
		wrap.setOpaque(false);
		wrap.setAlignmentX(0f);

		JPanel header = new JPanel(new BorderLayout(4, 0));
		header.setOpaque(false);
		/* A JPanel defaults to alignmentX 0.50, but `body` reports 0.00 —
		   Container.getAlignmentX() delegates to BoxLayout.getLayoutAlignmentX(),
		   which derives it from ITS children, and those are all left-aligned.
		   BoxLayout resolves a mixed column by summing max-ascent and
		   max-descent, so the column claims 343px for a 265px card and offsets
		   the 0.00 child by alignment x allocated-width. Measured: the body
		   landed at x=7451 in a 225px sidebar — every number painted far off
		   the right edge while the 0.50-aligned header stayed at x=0. That is
		   exactly "the header is there but the card is blank". */
		header.setAlignmentX(0f);
		header.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		header.setBorder(BorderFactory.createEmptyBorder(0, 2, 5, 2));
		JLabel titleLabel = new JLabel(title);
		titleLabel.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 10.5f));
		header.add(titleLabel, BorderLayout.WEST);

		JPanel headerRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		headerRight.setOpaque(false);
		if (extra != null)
		{
			headerRight.add(extra);
		}
		JLabel chevron = new JLabel(open ? "▾" : "▸");
		chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		chevron.setFont(chevron.getFont().deriveFont(10f));
		headerRight.add(chevron);
		header.add(headerRight, BorderLayout.EAST);

		/*
		 * On the children as well as the row, because Swing does not bubble.
		 *
		 * A mouse event goes to the deepest component under the pointer and
		 * stops there; a JLabel with no listener simply swallows it. So the
		 * header row was clickable everywhere EXCEPT on the two things that
		 * look like the control — its title and its chevron. That was a dead
		 * spot nobody noticed while the rows carried wide titles and you
		 * could hit the gap beside them. It stopped being survivable when the
		 * recommendation's title came off: the row was then empty apart from
		 * a chevron that did nothing, so a collapsed box could not be
		 * reopened by clicking the only mark on it.
		 */
		final MouseAdapter toggleOnClick = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e) { onToggle.run(); }
		};
		header.addMouseListener(toggleOnClick);
		titleLabel.addMouseListener(toggleOnClick);
		chevron.addMouseListener(toggleOnClick);
		/* `extra` is deliberately left out: it is a button with its own
		   action, and it sits in this row rather than belonging to it. */

		wrap.add(header);
		if (open)
		{
			body.setAlignmentX(0f);
			wrap.add(body);
		}
		return wrap;
	}

	/** A short placeholder for a section with nothing to show yet, in the
	 *  same bordered card shell as a real one. */
	/** Box.createVerticalStrut returns a Filler aligned 0.5, which puts a
	 *  centre-aligned child back into a left-aligned column and revives the
	 *  offset bug. Always use this instead. */
	private static java.awt.Component leftStrut(int h)
	{
		final Box.Filler f = (Box.Filler) Box.createVerticalStrut(h);
		f.setAlignmentX(0f);
		return f;
	}

	private JPanel emptyMiniBody(String text)
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(9, 12, 9, 10)));
		// Plain, not <html>. Wrapped HTML labels in this column rendered
		// their box but no visible text in the client, and an empty-state
		// message that itself fails to draw is worse than no box at all —
		// so these strings are kept short enough not to need wrapping.
		JLabel label = new JLabel(text);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(label.getFont().deriveFont(12f));
		p.add(label, BorderLayout.CENTER);
		return p;
	}

	/** Before you're logged in there's no bank, no inventory and no offers,
	 *  so every box would sit empty and read as broken — which is exactly
	 *  how it looked. Say what's actually going on instead. */
	public void setLoggedIn(boolean loggedIn)
	{
		if (this.loggedIn == loggedIn)
		{
			return;
		}
		this.loggedIn = loggedIn;
		renderRecommendation();
	}

	/** Called whenever the plugin recomputes the recommendation stream —
	 *  sells out of your bank/inventory and buys sized to your liquid cash,
	 *  already ranked. Keeps your place in the list across refreshes where it
	 *  can, so a background tick doesn't yank the card you were reading. */
	public void setRecommendations(List<Rec> recs)
	{
		if (paused)
		{
			return; // holding the current card on screen deliberately
		}
		final Rec showing = (recIndex >= 0 && recIndex < recommendations.size())
			? recommendations.get(recIndex) : null;
		this.recommendations = recs != null ? recs : List.<Rec>of();
		recIndex = 0;
		if (showing != null)
		{
			for (int i = 0; i < recommendations.size(); i++)
			{
				if (recommendations.get(i).itemId == showing.itemId
					&& recommendations.get(i).sell == showing.sell)
				{
					recIndex = i;
					break;
				}
			}
		}
		renderRecommendation();
	}

	private void renderRecommendation()
	{
		recommendationWrap.removeAll();
		recommendationWrap.setVisible(true);
		if (recIndex >= recommendations.size())
		{
			recIndex = 0;
		}
		/* Logged out is a full stop — no bank, no offers, nothing to rank —
		   so it replaces the box outright, header and all, rather than
		   sitting under a "RECOMMENDED FLIP" title that can't be true yet. */
		if (!loggedIn)
		{
			shownCard = null;
			recommendationWrap.add(loginPrompt(), BorderLayout.NORTH);
			recommendationWrap.revalidate();
			recommendationWrap.repaint();
			return;
		}
		/* Three things can own this box, in this order: the offer screen you
		   have open right now (that price is the only one that matters while
		   it's up), the watchlist item you deliberately clicked, then the
		   ranked stream. Each one TAKES OVER — none of them adds a second
		   card underneath, which is what the panel used to do. */
		final Runnable toggle = () -> { recommendationOpen = !recommendationOpen; renderRecommendation(); };
		/* Handed to whichever of the four builders below runs, without
		   threading a parameter through all four \u2014 none of them otherwise
		   cares which section it is inside. Set for the duration of the build
		   and cleared after; this is the EDT, and buildCard consumes it once.
		   See Card.collapse. */
		cardCollapse = recommendationOpen ? collapseChevron(toggle) : null;
		final JPanel body;
		final boolean offerOwnsBox = geContextItemId != null
			&& !geContextItemId.equals(geContextDismissedFor);
		try
		{
			if (offerOwnsBox)
			{
				body = geContextBody();
			}
			else if (selectedFavorite != null)
			{
				body = favoriteBody();
			}
			else
			{
				if (recommendations.isEmpty())
				{
					shownCard = null;
				}
				body = recommendations.isEmpty()
					? emptyMiniBody(settings.advisorOn
						? "Looking for flips\u2026"
						: "Advisor is off (\u2699 above).")
					: recommendationBody(recommendations.get(recIndex));
			}
		}
		finally
		{
			cardCollapse = null;
		}
		/* Tell the overlays what the card ended up showing. Announced here
		   rather than from Next/Back, because the card also changes when a
		   refresh re-ranks the list under you, and this is the one place that
		   every one of those routes passes through.
		   Deduped: this method runs on every rebuild, and an unchanged
		   recommendation is not news. */
		announceShownRecommendation(offerOwnsBox || selectedFavorite != null
			? null : (recommendations.isEmpty() ? null : recommendations.get(recIndex)));
		/*
		 * No title, in any state.
		 *
		 * "RECOMMENDED FLIP" and "SELL FROM YOUR BANK" went first: both named
		 * the box rather than saying anything about the idea in it, and this
		 * box is the recommendation area, which is not in doubt when you are
		 * looking at it.
		 *
		 * YOUR OFFER and WATCHING were kept back on the argument that they
		 * mark the box being taken over by something else. They do — but each
		 * one spends a whole row of a 225px sidebar saying what its own card
		 * says anyway: the watching card is the only one with a dismiss ✕ on
		 * it, and the offer card carries "also written on the offer screen"
		 * and an "Offer screen open" footnote. A caption that repeats the
		 * thing underneath it is not orientation, it is a line of pixels.
		 *
		 * The collapse chevron keeps the row, which is why this is still a
		 * titled section and not a bare panel.
		 */
		if (recommendationOpen)
		{
			/* No header row at all. With the titles gone it held nothing but
			   a chevron, and 27px of empty strip above the one thing on the
			   panel you actually read is not a price worth paying for a 10px
			   glyph. The chevron went onto the card itself — see Card.collapse
			   and the EAST slot in buildCard.
			   Collapsed it comes back, because then there is no card to put it
			   on and it is the only way left to reopen the box. */
			body.setAlignmentX(0f);
			recommendationWrap.add(body, BorderLayout.NORTH);
		}
		else
		{
			recommendationWrap.add(collapsibleSection("", null, false, toggle, body), BorderLayout.NORTH);
		}
		recommendationWrap.revalidate();
		recommendationWrap.repaint();
	}

	/**
	 * The card for one trade, minus its buttons: name, verb, score, the
	 * boxed pair, profit, the labelled figures, and whichever footnote the
	 * numbers earn. Shared by the ranked stream and the offer-screen
	 * takeover, so the item you are about to price on the Exchange is drawn
	 * exactly the way the one the plugin proposed was.
	 */
	private Card cardFor(Rec r)
	{
		final boolean untracked = r.sell && !r.hasTrackedCost;

		Card c = new Card();
		/* Brand gold, always. The accent used to flip to teal on every sell,
		   which is most cards, so the box read as permanently teal and the
		   colour stopped carrying the buy/sell distinction it was there for.
		   That distinction is on the action line now, in the site's own
		   colours, where it sits next to the numbers it describes. */
		c.accent = GOLD;
		c.itemId = r.itemId;
		c.name = r.name;
		/* "Target sell" / "Target buy" in white, then the numbers in the
		   site's sell/buy colour \u2014 the same wording and the same hues the
		   website's own target row uses, so the two read as one product. */
		/* Name, then the verb under it, then the numbers — each in its own
		   place. This card has been a sentence ("Buy 13,000 / Tin ore / for
		   15 gp ea") and a spec sheet ("Target buy / 13,000 @ 15 gp ea")
		   before now, and the sentence lost its own quantity: written in the
		   buy colour above the name it read as a label, and the report came
		   back as "include the quantity", about a card that had it in bold on
		   line one. So: the name leads, as it does on the site; a one-word
		   verb says which way; the two prices are the boxed pair; and the
		   quantity is a labelled figure beside capital, where a number you
		   are about to type is looked for. */
		c.actionLabel = null;
		c.actionText = null;
		c.actionLead = null;
		c.actionTrail = null;
		c.verb = r.sell ? "Sell" : "Buy";
		c.actionColor = r.sell ? sellColor() : buyColor();
		c.pair = new Card.PricePair();
		if (r.sell)
		{
			/* What you paid on the left, in the buy colour, because it IS the
			   buy half of this flip — just one that already happened. A dash
			   when the plugin never saw it. */
			c.pair.buyLabel = "PAID @";
			c.pair.buy = r.unitCost;
			c.pair.buyTip = r.unitCost > 0
				? tip("You paid " + String.format("%,d", r.unitCost) + " gp each", "Average over the units tracked.")
				: tip("Cost unknown", "The plugin never watched you buy this stack.");
			c.pair.sellLabel = "SELL @";
			c.pair.sell = r.unitPrice;
			c.pair.sellTip = tip("Ask " + String.format("%,d", r.unitPrice) + " gp each",
				"Filled in on the offer screen for you.");
		}
		else
		{
			c.pair.buyLabel = "BUY @";
			c.pair.buy = r.unitPrice;
			c.pair.buyTip = tip("Bid " + String.format("%,d", r.unitPrice) + " gp each",
				"Filled in on the offer screen for you.");
			c.pair.sellLabel = "SELL @";
			c.pair.sell = r.exitPrice;
			c.pair.sellTip = r.exitPrice > 0
				? tip("Sell back at " + String.format("%,d", r.exitPrice) + " gp",
					"What the profit below assumes, after tax.")
				: tip("No exit price yet", null);
		}
		/* Buys the engine has scored, when the row is wanted. Sells are not
		   scored: the site rates ideas it is proposing, and a stack you
		   already hold is not one. */
		c.score = r.sell || !settings.showFlipScore ? null : r.score;
		/* The figures you type, labelled. Capital is HERE now rather than in
		   the stacked block further down, so the two sit side by side as the
		   pair they are — how many, and what that costs. */
		c.stats = new ArrayList<>();
		c.stats.add(new Card.Stat("QUANTITY", String.format("%,d", r.quantity),
			tip(String.format("%,d", r.quantity) + (r.sell ? " to sell" : " to buy"),
				r.sell ? "The stack in your bank." : "Filled in on the offer screen for you.")));
		if (!r.sell && r.capital > 0)
		{
			c.stats.add(new Card.Stat("CAPITAL", String.format("%,d", r.capital) + " gp",
				tip(String.format("%,d", r.capital) + " gp tied up",
					"Sized to the cash you have free and the slots you have spare.")));
		}
		c.provenance = r.sell ? "from your bank" : null;
		/* What it cost is the other half of the decision on a held stack, so
		   it stays. The "-14 gp/item margin at today's spread" line that used
		   to appear instead on an untracked stack is gone: a NEGATIVE margin
		   under a sell suggestion reads as "this is a bad idea" when the
		   actual message is "you already own it, sell it anyway". */
		/* The quantity goes in when only part of the stack is tracked, because
		   then this line is also the scope of the P&L below it: "bought at 517"
		   next to "sell 18,000" invites you to read the P&L as covering all
		   18,000, and it doesn't. */
		/* Exact, with separators, like every other per-unit price on the card.
		   quantityToStackSize prints 1141 beside a "for 1,144 gp ea" that has
		   a comma, and above ten thousand it abbreviates — so a cost basis of
		   12,500 rendered "12.5K" directly under an exact asking price. Two
		   prices on adjacent lines in two different notations invite the
		   reader to compare them, which is the one thing they must be able to
		   do at a glance. */
		/* The plain "bought at 950 gp ea" line is gone — the PAID @ box above
		   is that number. The partly-tracked form survives, because there the
		   line is doing a second job the box cannot: saying HOW MANY of the
		   stack the price applies to, which is also the scope of the P&L. */
		c.subText = r.sell && r.unitCost > 0 && r.untrackedQty > 0
			? "bought " + String.format("%,d", r.quantity - r.untrackedQty) + " at "
				+ String.format("%,d", r.unitCost) + " gp ea"
			: null;
		c.profitValue = r.profit;
		/* Three different claims, three different words, so none can be
		   mistaken for another: a buy projects "profit", a sell with a known
		   cost measures "P&L", and a sell without one can only report what
		   the sale brings in. That last one is green now \u2014 money arriving
		   IS good news \u2014 but it is never called profit, because the plugin
		   has no idea what the stack cost you. */
		/* "profit", not "P&L", on a tracked sell. The distinction P&L was
		   drawn for — measured versus projected — is not something the
		   abbreviation actually conveys, and it is jargon in a card whose
		   whole job is to read as plain instruction. Two words do the work:
		   "profit" when a cost is known, "sale value" when it is not. */
		c.profitSuffix = untracked ? "gp sale value" : "gp profit";
		c.profitSigned = !untracked;
		/* Sale value is printed in plain text, not profit green. It used to be
		   green on the reasoning that money arriving is good news, and that
		   was wrong in practice: the figure sits in the slot where every other
		   card shows a gain, at the same size and the same colour, so "1.08M
		   gp sale value" on a stack carrying about 80K of actual upside read
		   as 1.08M of upside no matter what the words next to it said. The
		   label alone was never going to outrank the colour. */
		c.profitColor = untracked ? TEXT_MAIN : null;
		c.profitTooltip = untracked
			? tip("Proceeds, not profit", "What the stack fetches after tax. The plugin never saw what you paid.")
			: r.untrackedQty > 0
				? tip("Covers " + String.format("%,d", r.quantity - r.untrackedQty) + " units",
					"The other " + String.format("%,d", r.untrackedQty) + " cost an unknown amount.")
				: r.sell
					? tip("Profit after the 2% tax", "Measured against what you paid.")
					: tip("Profit after the 2% tax", "Projected, if it fills at both prices.");
		/* Buys only. On a sell the action line above already IS the sell
		   price, and printing the same number twice on one card is the note
		   that came back about the watching card. */
		c.exitPrice = r.sell ? 0 : r.exitPrice;
		c.capital = 0; // in the stats row — see above
		/* Into the footnote slot, which is free on a buy card and already
		   renders at 10f grey. footnoteWarn stays FALSE deliberately: warn
		   paints it bold orange, and an orange caution under a green profit
		   figure is the plugin arguing with its own recommendation on the same
		   card — which is exactly what got Analyst Rating removed. This is a
		   measurement, so it is styled like one.

		   Paused wins the slot when paused: that is a state of the whole
		   panel, and it outranks a fact about one item. */
		if (r.rangeNote != null)
		{
			c.footnote = r.rangeNote;
			c.footnoteWarn = false;
		}
		/* The rest of the stack, on a sell the plugin only partly watched.
		   This is proceeds and the figure above it is a gain, so they get
		   separate lines and separate words; the alternative was adding them,
		   and adding them is what made a card offering to sell 18,000
		   necklaces at a loss announce "+6.81M gp P&L".

		   Sells never carry a range note, so this is not competing for the
		   slot — see rangeNote, which is buys only. */
		if (r.sell && r.hasTrackedCost && r.profit < 0)
		{
			/*
			 * Wins the slot over the untracked remainder below, because a card
			 * telling you to sell at a loss has one thing worth saying and
			 * that is not an accounting footnote.
			 *
			 * footnoteWarn TRUE here, unlike everywhere else. The rule that
			 * kept it off was that bold orange under a GREEN profit is the
			 * plugin arguing with its own recommendation — but the figure
			 * above this is red, so the caution agrees with it rather than
			 * contradicting it.
			 *
			 * It says what selling now means and what the button does. It does
			 * NOT say the price will recover, because the plugin has no idea
			 * whether it will, and "hold, it'll come back" is the single most
			 * expensive thing a trading tool can tell someone.
			 */
			c.footnote = "At a loss — Hold to keep it";
			c.footnoteWarn = true;
		}
		else if (r.sell && r.untrackedQty > 0)
		{
			c.footnote = "+" + QuantityFormatter.quantityToStackSize(r.untrackedValue)
				+ " gp from " + String.format("%,d", r.untrackedQty) + " at unknown cost";
			c.footnoteWarn = false;
		}
		else if (untracked)
		{
			/* Worth saying twice. "sale value" is already in the suffix, and
			   it still got read as a gain — a big number in the profit slot
			   is a strong claim and two quiet words next to it are a weak
			   correction. */
			c.footnote = "Cost unknown — proceeds, not profit";
			c.footnoteWarn = false;
		}
		else if (r.sell && r.quoteAgeSec > 0)
		{
			/*
			 * How old the price is, and last in the chain on purpose.
			 *
			 * A sell is allowed to price off a bid up to two hours old,
			 * because the alternative is never being able to talk about a
			 * Twisted bow at all — it trades a few dozen times a day, so its
			 * bid is routinely half an hour stale. The cost of that window is
			 * that the number on the card may not be current, and a price
			 * shown without its age is a price presented as current.
			 *
			 * It takes this slot only when nothing else wants it. Every
			 * footnote above is a stronger claim about the same trade — that
			 * it loses money, that part of it is unpriced, that none of it is
			 * — and none should be displaced by a timestamp.
			 */
			c.footnote = "Last traded " + agoText(r.quoteAgeSec) + " ago";
			c.footnoteWarn = false;
		}
		c.tooltip = r.note;
		return c;
	}

	/** One idea, Copilot-shaped: what to do, at what price, what it makes,
	 *  and the controls that matter — move on, hold it, or stop being told
	 *  about this item. */
	private JPanel recommendationBody(Rec r)
	{
		final Card c = cardFor(r);

		/* Big icon buttons rather than the cramped text ones this had. The
		   fill button is gone from here entirely: the price is written onto
		   the GE offer screen itself now (see GeOfferPriceOverlay) and
		   auto-filled when the prompt opens, so a sidebar button for it was a
		   worse version of something already happening. No chart button
		   either — clicking the item opens the chart, which costs no width,
		   and this row has none to spare. */
		JPanel controls = controlsRow();
		/* Always, not just when there are two or more. Next asks for a fresh
		   batch once it walks off the end, so on a one-suggestion list it is
		   the button that GETS you more \u2014 exactly when hiding it left you
		   with no way forward at all. */
		/* Pause is in the pinned top bar now — see pauseButton(). It was the
		   odd one out here: Next, Hold and Block all act on THIS item, while
		   pause acts on the advisor. */
		// Hold is "I'm keeping this one for now" — a session skip, so it
		// comes back next login. Block is the permanent one. Only sells can
		// be held: you can't hold something you don't own.
		if (r.sell)
		{
			addControl(controls, bigIconBtn(HOLD_ICON, "Hold your " + r.name + " — skip it for this session",
				e -> actions.skip(r.itemId)));
		}
		addControl(controls, bigIconBtn(BLOCK_ICON, "Never recommend " + r.name + " again",
			e -> { if (confirmBlock(r.name)) { actions.block(r.name); } }));
		/* The pager last and hard right, the way the website's card carries
		   it: everything left of the gap acts on THIS item, the two chevrons
		   move you off it. Next is drawn even on a one-suggestion list,
		   because it is what asks for a fresh batch once it walks off the
		   end -- exactly when hiding it would leave no way forward at all. */
		addSpacer(controls);
		if (canGoBack())
		{
			addControl(controls, backButton());
		}
		addControl(controls, nextButton());
		c.controls = controls;

		/* No "3 of 20". The count was never something to act on, it cost a
		   whole line, and it framed the list as finite when Next now just
		   fetches more once it runs out. */
		/* Paused outranks the range note — it is a state of the whole panel,
		   and a card that looks live while the stream is frozen is worse than
		   one missing a footnote. Note this reads c.footnote rather than
		   clearing it: the range line is set above, and assigning null here
		   unconditionally would have silently thrown it away. */
		if (paused)
		{
			c.footnote = "Paused";
			c.footnoteWarn = true;
		}
		/* Was `c.footnoteWarn = paused;` unconditionally, which quietly reset
		   the flag every card that had set one. Harmless while nothing but
		   Paused ever used it; it swallowed the at-a-loss caution the moment
		   something did. */
		shownCard = c;
		return buildCard(c);
	}

	/** The controls strip along the bottom of a card.
	 *
	 *  BoxLayout, not FlowLayout: FlowLayout pads BOTH ends with its hgap and
	 *  silently wraps to a second row when it runs out of width — and because
	 *  the panel's preferred height is still measured for one row, that
	 *  second row is simply clipped away. Next + Pause + Hold + Block did not
	 *  fit a 225px sidebar with FlowLayout's padding, so on any SELL
	 *  suggestion the Block button was being cut off. It fits without. */
	private static JPanel controlsRow()
	{
		JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(0f);
		return row;
	}

	/** Adds a control, with a gap before it if it isn't the first. The
	 *  explicit alignmentY keeps every child on one baseline — a default
	 *  Box.Filler and a JButton disagree otherwise, and an X_AXIS BoxLayout
	 *  resolves that disagreement by making the row taller. */
	/**
	 * Pushes whatever is added next to the right-hand end of the row.
	 *
	 * The website's card puts its pager on the far edge of the bottom row,
	 * clear of everything that acts on the item — which is most of what
	 * makes two chevrons read as ONE pager rather than as two more buttons
	 * in a line of five. Copied here for the same reason.
	 */
	private static void addSpacer(JPanel row)
	{
		final Box.Filler glue = (Box.Filler) Box.createHorizontalGlue();
		glue.setAlignmentY(0.5f);
		row.add(glue);
	}

	private static void addControl(JPanel row, JButton b)
	{
		if (row.getComponentCount() > 0)
		{
			final Box.Filler gap = (Box.Filler) Box.createHorizontalStrut(CONTROL_GAP);
			gap.setAlignmentY(0.5f);
			gap.setAlignmentX(0f); // irrelevant on an X_AXIS row, but keeps the
			                       // whole panel's alignment audit clean
			row.add(gap);
		}
		b.setAlignmentY(0.5f);
		row.add(b);
	}

	/** Paging. On the watchlist takeover it ALSO drops the inspection: the
	 *  item you clicked isn't part of the ranked stream, so "next" there can
	 *  only sensibly mean "back to the flips, one further along". */
	/** Push whatever is on screen onto the browsing trail. */
	private void rememberCurrentRec()
	{
		if (recIndex < 0 || recIndex >= recommendations.size())
		{
			return;
		}
		final Rec cur = recommendations.get(recIndex);
		final int[] top = recTrail.peekLast();
		if (top != null && top[0] == cur.itemId && top[1] == (cur.sell ? 1 : 0))
		{
			return; // already the last thing we left; don't stack duplicates
		}
		recTrail.addLast(new int[]{cur.itemId, cur.sell ? 1 : 0});
		while (recTrail.size() > MAX_REC_TRAIL)
		{
			recTrail.removeFirst();
		}
	}

	/** Where {@code entry} sits in the list as it stands now, or -1 if that
	 *  flip is no longer being recommended. */
	private int indexOfRec(int[] entry)
	{
		for (int i = 0; i < recommendations.size(); i++)
		{
			final Rec r = recommendations.get(i);
			if (r.itemId == entry[0] && (r.sell ? 1 : 0) == entry[1])
			{
				return i;
			}
		}
		return -1;
	}

	/** True when at least one card on the trail is still on offer — i.e. when
	 *  Back would actually do something. */
	private boolean canGoBack()
	{
		for (int[] entry : recTrail)
		{
			if (indexOfRec(entry) >= 0)
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Back to the last card you paged past that is still being recommended.
	 *
	 * Entries that have gone stale are discarded as they are popped rather
	 * than skipped over and kept: if a flip has dropped out of the ranking,
	 * pressing Back twice should not keep offering to look for it.
	 */
	private JButton backButton()
	{
		final JButton back = pagerButton(true, "Back to the previous suggestion", e ->
		{
			while (!recTrail.isEmpty())
			{
				final int[] entry = recTrail.removeLast();
				final int at = indexOfRec(entry);
				if (at >= 0)
				{
					/* Leaving the offer-screen takeover and the favourite
					   selection the same way Next does — otherwise Back would
					   change the card underneath an overlay still showing the
					   item you were on. */
					selectedFavorite = null;
					selectedFavoriteId = -1;
					recIndex = at;
					renderRecommendation();
					return;
				}
			}
			renderRecommendation(); // trail emptied out; redraw without the button
		});
		return back;
	}

	private JButton nextButton()
	{
		final JButton next = pagerButton(false,
			geContextItemId != null || selectedFavorite != null
				? "Back to flips — show the next suggestion"
				: "Next suggestion", e ->
		{
			if (selectedFavorite != null)
			{
				selectedFavorite = null;
				selectedFavoriteId = -1;
			}
			/* Step out of the offer-screen takeover too, for the item that is
			   on screen right now. One button, one meaning wherever it
			   appears: "stop showing me this, show me the next flip." */
			if (geContextItemId != null)
			{
				geContextDismissedFor = geContextItemId;
			}
			if (!recommendations.isEmpty())
			{
				/* Remember what you are stepping off, so Back can return to
				   it. Pushed here rather than in renderRecommendation because
				   only a deliberate Next is "browsing" — a refresh landing a
				   new card under you is not something you asked to leave. */
				rememberCurrentRec();
				/* Walking off the end asks for a new batch rather than
				   quietly starting the same ring over. The wrap to 0 stays as
				   the fallback: the refresh is asynchronous, so there has to
				   be something to show on the very next paint, and if it
				   returns the same list this is exactly the old behaviour. */
				final int nextIndex = recIndex + 1;
				if (nextIndex >= recommendations.size())
				{
					actions.refreshSuggestions();
				}
				recIndex = nextIndex % recommendations.size();
			}
			renderRecommendation();
		});
		/* No word on it any more.
		   It carried "Next" on the argument that the control you press most
		   should be the easiest to hit. The website's card answered that
		   differently and better: a PAIR of chevron squares reads as a pager
		   at a glance, where one worded button and one bare arrow read as two
		   unrelated controls. The pair is also narrower than the single
		   worded button was, which is where the room for the rest of the row
		   came from. */
		return next;
	}

	/**
	 * Dresses a control as one of the site's pager squares: a rounded
	 * gold-tinted tile that brightens under the cursor.
	 *
	 * Painted rather than bordered because Swing's border is square and the
	 * corner radius is most of what makes these read as the site's. The fill
	 * is translucent, so the card's own hover tint still shows through it
	 * rather than the button punching a flat hole in the card.
	 */
	private static JButton asPager(JButton b)
	{
		b.setContentAreaFilled(false);
		b.setBorderPainted(false);
		b.setOpaque(false);
		b.setFocusPainted(false);
		b.setText(null);
		b.setRolloverEnabled(true);
		sizeExactly(b, CONTROL_H, CONTROL_H);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/** A pager square carrying {@code glyph}, which is swapped for its
	 *  brighter self while the cursor is on it. */
	private JButton pagerButton(boolean back, String tip, java.awt.event.ActionListener a)
	{
		final JButton b = new JButton(buildChevron(back, PAGER_FG))
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				final boolean hot = getModel().isRollover() || getModel().isPressed();
				g2.setColor(new Color(PAGER_FG.getRed(), PAGER_FG.getGreen(), PAGER_FG.getBlue(),
					hot ? PAGER_FILL_ALPHA_HOVER : PAGER_FILL_ALPHA));
				g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, PAGER_RADIUS, PAGER_RADIUS);
				g2.setColor(hot ? PAGER_FG
					: new Color(PAGER_FG.getRed(), PAGER_FG.getGreen(), PAGER_FG.getBlue(), PAGER_RIM_ALPHA));
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, PAGER_RADIUS, PAGER_RADIUS);
				g2.dispose();
				super.paintComponent(g);
			}
		};
		b.setRolloverIcon(buildChevron(back, PAGER_FG_HOVER));
		b.setPressedIcon(buildChevron(back, PAGER_FG_HOVER));
		b.setToolTipText(tip);
		b.addActionListener(a);
		return asPager(b);
	}

	/** Everything one card can show.
	 *
	 *  A spec object rather than a ten-argument method, because there is now
	 *  exactly ONE card shape in this panel — the recommended flip, the
	 *  watchlist item you clicked, and the offer screen you have open all
	 *  render through it, and the only difference between them is which
	 *  fields are filled in. Two differently-shaped cards stacked on top of
	 *  each other was the thing that made this panel hard to read. */
	/** The card's money line. Shared by the panel and the share image so a
	 *  pasted card cannot word its own headline differently. */
	private static String moneyLine(Card c)
	{
		final String sign = c.profitSigned && c.profitValue >= 0 ? "+" : "";
		return sign + QuantityFormatter.quantityToStackSize(c.profitValue) + " " + c.profitSuffix;
	}

	/** The watchlist row's badge, said in words for the card headline. Longer
	 *  than the row's own version because there is room here, and because a
	 *  bare ▲ next to a price would read as a price arrow rather than a
	 *  range one. */
	private static String extremeBadgeText(PriceExtremes.Tier tier)
	{
		switch (tier)
		{
			case HIGH_5D:
				return "▲ 5D HIGH";
			case LOW_5D:
				return "▼ 5D LOW";
			case HIGH_1D:
				return "▲ DAY HIGH";
			case LOW_1D:
				return "▼ DAY LOW";
			default:
				return null;
		}
	}

	private static class Card
	{
		Color accent = GOLD;
		int itemId;
		String name = "";
		/** A white lead-in printed before {@link #actionText} and NOT taking
		 *  its colour — "Target sell", "Target buy". The label says which
		 *  side you are on; the coloured half is the numbers. Null for a
		 *  headline that is all one colour. */
		String actionLabel;
		/** The headline under the name — "8,917 @ 729 gp ea",
		 *  "▲ 5D HIGH · 958 gp". */
		String actionText;
		/** Colour for {@link #actionText}. Null means "use the accent", which
		 *  is right for a buy/sell instruction (the accent already encodes
		 *  that direction) and wrong for a range badge, where green/gold mean
		 *  high/low instead. */
		Color actionColor;
		/**
		 * Recommendation cards only: the two coloured lines that SANDWICH the
		 * item name — "Sell 17,303" above it, "for 984 gp ea" below — so the
		 * card reads as one instruction top to bottom instead of a name
		 * followed by a spec sheet.
		 *
		 * Null everywhere else, which leaves those cards on the single
		 * {@link #actionText} line above the name. {@link #actionText} stays
		 * populated either way: the share image renders from it, and it is
		 * what keeps the picture's headline identical to the card's.
		 */
		String actionLead;
		String actionTrail;
		/** Where the goods are, under the instruction — "from your bank" on
		 *  a sell. Null on a buy, which is not from anywhere yet. */
		String provenance;
		/** Muted second line: what it cost, the target pair, no-margin. */
		String subText;
		Long profitValue;
		String profitSuffix = "gp profit";
		String profitTooltip;
		/** Colour for the profit line. Null means the usual green-for-plus,
		 *  red-for-minus. Set it when the number is not a gain at all and
		 *  must not be read as one. */
		Color profitColor;
		/** Whether to print a leading "+". A gain is signed; a sum of money
		 *  that simply arrives is not. */
		boolean profitSigned = true;
		/** The sell price the profit assumes, 0 to hide. Rendered beside the
		 *  money line, small and grey: it is the number that makes the green
		 *  one true, not a second headline. */
		long exitPrice;
		/** gp the position ties up, 0 to hide. */
		long capital;
		/** Buttons along the bottom. Null for none. */
		JPanel controls;
		JButton close;
		/** The enclosing section's collapse chevron, rendered in this card's
		 *  own top-right rather than on a header row of its own. Null for
		 *  cards that are not the whole of a collapsible section. */
		JLabel collapse;
		/** Small grey line at the very bottom — "3 of 12", "From your
		 *  watchlist". */
		String footnote;
		/** Colours the footnote as a warning instead of grey ("Paused"). */
		boolean footnoteWarn;
		String tooltip;
		/** The site card's score row — verdict, number and meter — under
		 *  the instruction. Null for cards that are not scored buys. */
		TradeEngine.FlipScore score;
		/** Recommendation cards: the instruction word under the name — "Buy"
		 *  or "Sell" — in the side's colour, with {@link #provenance} after
		 *  it in grey when there is one. The quantity that used to ride in
		 *  this line is a labelled figure in {@link #stats} now. */
		String verb;
		/** Small labelled figures along the bottom — QUANTITY, CAPITAL — in
		 *  the site card's stat-cell shape. Null for none. */
		List<Stat> stats;

		static final class Stat
		{
			final String label;
			final String value;
			final String tip;

			Stat(String label, String value, String tip)
			{
				this.label = label;
				this.value = value;
				this.tip = tip;
			}
		}
		/** The two prices, boxed side by side the way the site's card draws
		 *  its Buy @ / Sell @. Replaces {@link #actionTrail} and the "Sell
		 *  at" row when set: the same two numbers, in one shape. */
		PricePair pair;

		static final class PricePair
		{
			String buyLabel;
			/** 0 means unknown, rendered as a dash: a sell of a stack the
			 *  plugin never watched you buy has no price to put here. */
			long buy;
			String buyTip;
			String sellLabel;
			long sell;
			String sellTip;
		}
	}

	/** Shared shell for every card in this panel — colored left accent
	 *  (pocketge.com's own obsidian background behind it, not RuneLite's
	 *  neutral gray), an icon+name headline row, then each optional line in
	 *  turn. Splitting name, action and price onto their own lines — rather
	 *  than one combined "Buy N Item for X gp" string — is deliberate: a
	 *  single JLabel doesn't wrap, so a longer item name (Helm of neitiznot,
	 *  say) combined with the price used to push the price itself past the
	 *  card's edge, clipped and invisible. Each line now only ever needs to
	 *  fit ONE piece of information. */
	/**
	 * The height every recommendation card is held to, so paging with Next
	 * does not move the card's own buttons or shunt the whole panel below it.
	 *
	 * Measured rather than chosen: the four card shapes came out at 331, 347,
	 * 347 and 351 pixels, because a buy carries an exit price and a capital
	 * block where a sell carries a provenance line, a cost line and a
	 * footnote — nearly the same height by coincidence, never exactly. An
	 * untracked sell has no cost line at all and is a row shorter again.
	 *
	 * Held to the tallest of them, with the slack going to a glue above the
	 * buttons. Cards that genuinely need more still grow; nothing is
	 * truncated. The cost is a few pixels of dark background on the shortest
	 * card, which is a better trade than a control that moves under the
	 * cursor between presses.
	 *
	 * Not a pixel constant any more. 236 was measured in the headless
	 * harness, whose JRE default font is a size or two larger than the font
	 * RuneLite's look-and-feel actually draws with — so in the client every
	 * card came out shorter than the number and the floor left a band of
	 * nothing above the buttons on all of them. A height tuned in one font
	 * cannot be right in another.
	 *
	 * So the floor is MEASURED, once, in whatever font is live: the first
	 * card built lays out a probe in the tallest shape a card can take
	 * (scored buy, two stats, a footnote, buttons) and takes its preferred
	 * height. Same fonts, same rows, same insets — by construction the
	 * tallest real card lands on the floor exactly and the rest sit within
	 * one row of it. Reset when the score row is switched off, since that
	 * changes the tallest shape.
	 */
	private int cardFloor = -1;
	/** True while the probe card is being built, so its own preferred
	 *  height is not clamped to a floor that does not exist yet. */
	private boolean probingFloor;

	private int cardFloor()
	{
		if (cardFloor < 0 && !probingFloor)
		{
			probingFloor = true;
			try
			{
				cardFloor = buildCard(tallestShape()).getPreferredSize().height;
			}
			finally
			{
				probingFloor = false;
			}
		}
		return Math.max(0, cardFloor);
	}

	/** Every optional row present at once. Values are placeholders; only the
	 *  rows and their fonts matter, and a "1" is as tall as "20,160,000". */
	private Card tallestShape()
	{
		final Card c = new Card();
		c.name = "Probe";
		c.verb = "Buy";
		c.actionColor = buyColor();
		c.score = settings == null || settings.showFlipScore
			? TradeEngine.FlipScore.of(0.02, 1_000_000, false) : null;
		c.pair = new Card.PricePair();
		c.pair.buyLabel = "BUY @";
		c.pair.buy = 1;
		c.pair.sellLabel = "SELL @";
		c.pair.sell = 1;
		c.profitValue = 1L;
		c.stats = List.of(new Card.Stat("QUANTITY", "1", null), new Card.Stat("CAPITAL", "1 gp", null));
		c.footnote = "probe";
		final JPanel controls = controlsRow();
		addControl(controls, nextButton());
		c.controls = controls;
		return c;
	}

	/** See the assignment in renderRecommendation: live only while the
	 *  recommendation body is being built, consumed once by buildCard. */
	private JLabel cardCollapse;

	/** The section chevron as it appears on the card: same glyph, colour and
	 *  size the header row used, so moving it changed where it is and nothing
	 *  about what it looks like. */
	private JLabel collapseChevron(Runnable onToggle)
	{
		final JLabel chevron = new JLabel("\u25BE");
		chevron.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		chevron.setFont(chevron.getFont().deriveFont(10f));
		chevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chevron.setToolTipText("Hide the recommendation");
		chevron.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e) { onToggle.run(); }
		});
		return chevron;
	}

	private JPanel buildCard(Card c)
	{
		JPanel p = new JPanel()
		{
			@Override
			public java.awt.Dimension getPreferredSize()
			{
				final java.awt.Dimension d = super.getPreferredSize();
				return new java.awt.Dimension(d.width, Math.max(d.height, probingFloor ? 0 : cardFloor()));
			}
		};
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setAlignmentX(0f);
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, c.accent),
			BorderFactory.createEmptyBorder(9, CARD_PAD_L, 9, CARD_PAD_R)));

		if (c.collapse == null && cardCollapse != null)
		{
			c.collapse = cardCollapse;
			cardCollapse = null;
		}
		JPanel row1 = new JPanel(new BorderLayout(6, 0));
		row1.setOpaque(false);
		/* Every child of this BoxLayout must share one alignmentX. A JPanel
		   defaults to 0.5 (CENTER) while the labels below are set to 0f
		   (LEFT); BoxLayout resolves a mixed column by widening it to fit
		   both interpretations and offsetting the members, which inside a
		   fixed 225px PluginPanel pushed the left-aligned parts — the price
		   line and the whole rating gauge — outside the visible width. They
		   still contributed height, so the card rendered as a tall box with
		   nothing in it but the item name. */
		row1.setAlignmentX(0f);
		final String itemName = c.name != null ? c.name : "";
		final JLabel icon = iconLabel(c.itemId, CARD_ICON);
		wireOpenChartOnClick(icon, itemName);
		row1.add(icon, BorderLayout.WEST);
		// Just the name in CENTER — a lone JLabel truncates safely via
		// truncateName() when it's the squeezed slot. The chart button used
		// to share this slot with the name; that worked until a close button
		// (EAST, always gets its full preferred width) showed up too, which
		// squeezed CENTER enough that the chart button rendered clipped.
		// Moving it into the same always-full-width EAST slot fixes that
		// outright rather than fighting the layout for room.
		/* EAST always gets its full preferred width, so every button here is
		   width taken straight off the name. Measured in a 225px sidebar: one
		   button leaves the name 132px, two leave it ~103.
		   The chart button has now followed share down to the controls row,
		   which is where every other verb on this card already lives. That
		   gives a card with no close button the whole row for its name, so
		   "Diamond necklace" stops arriving as "Diamond n…". Clicking the
		   icon or the name still opens the chart, so nothing moved out of
		   reach — only the button did.
		   The budget is characters that actually FIT, not a round number:
		   Swing ellipsizes a second time on its own if the label overflows,
		   which is how "Bandos chestplate" once rendered as "Bandos ches"
		   with even the ellipsis cut off. */
		JLabel nameLabel = new JLabel(truncateName(itemName, c.close != null ? 14 : 22));
		nameLabel.setToolTipText(itemName + " — click to open its chart");
		nameLabel.setForeground(TEXT_MAIN);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 15f));
		wireOpenChartOnClick(nameLabel, itemName);
		if (c.actionLead != null || c.verb != null)
		{
			final JPanel stack = new JPanel();
			stack.setLayout(new BoxLayout(stack, BoxLayout.Y_AXIS));
			stack.setOpaque(false);
			final Color lineFg = c.actionColor != null ? c.actionColor : c.accent;
			if (c.actionLead != null)
			{
				stack.add(actionLine(c.actionLead, lineFg));
			}
			nameLabel.setAlignmentX(0f);
			stack.add(nameLabel);
			if (c.actionTrail != null)
			{
				stack.add(actionLine(c.actionTrail, lineFg));
			}
			if (c.verb != null)
			{
				/* Name first, verb under it — the site card's order. This was
				   the other way round, as a sentence: "Buy 13,000 / Tin ore".
				   The trouble with a sentence is that the number inside it is
				   read as part of the phrase and not as a figure, and the one
				   figure you have to type into the offer screen was going
				   unseen on a card that had it in bold on the first line. The
				   verb stays, small and coloured; the quantity moved down to
				   the stats, labelled, where a number is looked for. */
				final JPanel verbRow = new JPanel();
				verbRow.setLayout(new BoxLayout(verbRow, BoxLayout.X_AXIS));
				verbRow.setOpaque(false);
				verbRow.setAlignmentX(0f);
				final JLabel verb = new JLabel(c.verb);
				verb.setForeground(lineFg);
				verb.setFont(verb.getFont().deriveFont(Font.BOLD, 11f));
				verb.setAlignmentY(0.5f);
				verbRow.add(verb);
				if (c.provenance != null)
				{
					final JLabel from = new JLabel(" · " + c.provenance);
					from.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
					from.setFont(from.getFont().deriveFont(10f));
					from.setAlignmentY(0.5f);
					verbRow.add(from);
				}
				stack.add(holdHeight(verbRow));
			}
			else if (c.provenance != null)
			{
				/* What the "SELL FROM YOUR BANK" header used to say, now on the
				   card with the rest of the idea. Small and grey: it is context
				   for the instruction above, not part of it. */
				final JLabel from = new JLabel(c.provenance);
				from.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
				from.setFont(from.getFont().deriveFont(10f));
				from.setAlignmentX(0f);
				stack.add(from);
			}
			row1.add(stack, BorderLayout.CENTER);
		}
		else
		{
			row1.add(nameLabel, BorderLayout.CENTER);
		}
		if (c.close != null || c.collapse != null)
		{
			JPanel eastWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			eastWrap.setOpaque(false);
			if (c.close != null)
			{
				eastWrap.add(c.close);
			}
			if (c.collapse != null)
			{
				/* The section's collapse handle, on the card instead of on a
				   header row above it. That row held nothing but this chevron
				   once the titles came off, and an empty strip 27px tall over
				   the one thing you actually read is the sidebar spending its
				   scarcest resource on a 10px glyph. */
				eastWrap.add(c.collapse);
			}
			row1.add(eastWrap, BorderLayout.EAST);
		}
		/* Held to its own height. A BorderLayout panel reports no maximum,
		   and BoxLayout hands a column's spare height to every child that
		   will take it — so on a card shorter than MIN_CARD_HEIGHT the slack
		   was being split between this row and the glue above the buttons,
		   and the gap under the item name grew and shrank from one card to
		   the next. Measured: 19px under "Leather", 13px under "Uncut
		   diamond". All of it goes to the glue now, where it was meant to. */
		p.add(holdHeight(row1));

		if (c.score != null)
		{
			p.add(leftStrut(6));
			p.add(scoreRow(c.score));
		}
		if (c.pair != null)
		{
			p.add(leftStrut(8));
			p.add(pairRow(c.pair));
		}

		/* Skipped when the sandwich above already said it — actionText stays
		   set for the share image, which renders from it. */
		if (c.actionText != null && c.actionLead == null)
		{
			p.add(leftStrut(3));
			final Color actionFg = c.actionColor != null ? c.actionColor : c.accent;
			if (c.actionLabel != null)
			{
				/* Stacked, not side by side. Measured: "Target sell" plus a
				   four-digit quantity and price comes to 252px against the
				   204 a card has, and no readable font size rescues it \u2014 a
				   large price ("100,000 @ 2,147,483 gp") is still over at
				   12pt. So the white label takes its own line and the numbers
				   get the full width beneath it, which also puts the figure
				   you are about to type on a line of its own. */
				final JLabel lead = new JLabel(c.actionLabel.trim());
				lead.setForeground(Color.WHITE);
				lead.setFont(lead.getFont().deriveFont(Font.BOLD, 11f));
				lead.setAlignmentX(0f);
				p.add(lead);
				final JLabel rest = new JLabel(c.actionText);
				rest.setForeground(actionFg);
				rest.setFont(rest.getFont().deriveFont(Font.BOLD, 14f));
				rest.setAlignmentX(0f);
				p.add(rest);
			}
			else
			{
				JLabel actionLabel = new JLabel(c.actionText);
				actionLabel.setForeground(actionFg);
				actionLabel.setFont(actionLabel.getFont().deriveFont(Font.BOLD, 14f));
				actionLabel.setAlignmentX(0f);
				p.add(actionLabel);
			}
		}

		if (c.subText != null)
		{
			p.add(leftStrut(2));
			JLabel sub = new JLabel(c.subText);
			sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			sub.setFont(sub.getFont().deriveFont(11f));
			sub.setAlignmentX(0f);
			p.add(sub);
		}

		if (c.profitValue != null)
		{
			/* Order: what you pay, then what you sell at, then what that leaves.
			   The profit used to sit between the buy price and the sell price,
			   which put the conclusion in the middle of its own working — the
			   green number is only true at the price on the line BELOW it. Read
			   top to bottom it is now the arithmetic in the order you would say
			   it out loud. */
			/* Not when the boxed pair above already carries the exit — the
			   SELL @ box is this row, in the site's shape. */
			if (c.exitPrice > 0 && c.pair == null)
			{
				/* Readable, and in the SELL colour.
				   This was 11f grey — small enough to read as a footnote when
				   it is half the trade: the gold line above says what you pay,
				   this says what you have to get back out at, and the green
				   number between them is only true if you do. The card already
				   uses gold for the buy side and teal for the sell side on its
				   action line, so the same teal here lets the pair be read
				   without reading the words. */
				/* Its OWN row, not tacked onto the money line.
				   Measured: at a readable 13f, "+296M gp profit" plus
				   "@ 1,250,000 gp" comes to 253px against the card's 211px, so
				   on an expensive item the price would be clipped — and a
				   clipped exit price is worse than a small one. On its own row
				   it has the whole width, so it stays legible no matter how
				   big the number gets.

				   Labelled "Sell at", matching the card's existing
				   white-word/coloured-number idiom, and in the same teal the
				   action line uses for a sell — so gold above is what you pay
				   and teal below is what you have to get back out at. */
				final JPanel exit = new JPanel();
				exit.setLayout(new BoxLayout(exit, BoxLayout.X_AXIS));
				exit.setOpaque(false);
				exit.setAlignmentX(0f);
				final JLabel sellWord = new JLabel("Sell at ");
				sellWord.setForeground(Color.WHITE);
				sellWord.setFont(sellWord.getFont().deriveFont(Font.BOLD, 11f));
				sellWord.setAlignmentY(0.5f);
				final JLabel at = new JLabel(String.format("%,d", c.exitPrice) + " gp");
				at.setForeground(sellColor());
				at.setFont(at.getFont().deriveFont(Font.BOLD, 14f));
				at.setAlignmentY(0.5f);
				final String tip = "The sell price this profit assumes — today's insta-buy. Bid "
					+ String.format("%,d", c.exitPrice) + " gp back out and the green number above is what you keep after the 2% tax.";
				sellWord.setToolTipText(tip);
				at.setToolTipText(tip);
				exit.add(sellWord);
				exit.add(at);
				exit.add(Box.createHorizontalGlue());
				p.add(holdHeight(exit));
			}
			p.add(leftStrut(4));
			/* The money and the price that produces it, side by side but not
			   the same weight: a buy card names the price to BID at, then a
			   green number that is only true at some other price. Without the
			   exit there is nothing on the card to check the profit against.

			   A row rather than one string, because the two want different
			   sizes — 15f bold for the money, 11f grey for the price — and
			   because a long price then costs the layout a wrap rather than
			   widening the sidebar. */
			final JPanel money = new JPanel();
			money.setLayout(new BoxLayout(money, BoxLayout.X_AXIS));
			money.setOpaque(false);
			money.setAlignmentX(0f);
			JLabel profitLabel = new JLabel(moneyLine(c));
			profitLabel.setForeground(c.profitColor != null ? c.profitColor
				: c.profitValue >= 0 ? POSITIVE : NEGATIVE);
			/* The biggest thing on the card, by a clear margin.
			   It was 15f, the same size as the item name and a point over the
			   price boxes, so the one number the whole card exists to produce
			   was competing on equal terms with the label above it. At 19f it
			   wins outright and the card has an obvious place for the eye to
			   land. Checked against the widest figure the panel can print —
			   "+296M gp profit" — inside a 204px card. */
			profitLabel.setFont(profitLabel.getFont().deriveFont(Font.BOLD, 19f));
			profitLabel.setAlignmentY(0.5f);
			if (c.profitTooltip != null)
			{
				profitLabel.setToolTipText(c.profitTooltip);
			}
			money.add(profitLabel);
			money.add(Box.createHorizontalGlue());
			p.add(holdHeight(money));
		}

		if (c.stats != null && !c.stats.isEmpty())
		{
			/* The site card's stat cells: a 9pt label over a bold figure, as
			   many across as there are figures. QUANTITY and CAPITAL for a
			   buy, QUANTITY alone for a sell. Exact with separators, never
			   abbreviated \u2014 1.77M and 177M are one dot apart at this size and
			   these are the two numbers you type. */
			p.add(leftStrut(6));
			p.add(statsRow(c.stats));
		}

		if (c.capital > 0)
		{
			/* "1.77M gp capital" in small grey was unreadable at a glance \u2014
			   1.77M and 177M are one misread apart, and that is a hundredfold
			   error in the only number that says whether you can afford this.
			   Named, brightened, and the exact figure on the tooltip so the
			   abbreviation never has to be trusted on its own. */
			/* Stacked, for the same reason the action line is: side by side,
			   the label plus an exact figure came to exactly the 204px a card
			   has, leaving nothing for a bigger number — and capital can run
			   to billions. Label above, figure below, the same shape as the
			   target line so the two read as a pair.

			   Exact, with separators, because the abbreviation was the whole
			   problem: 1.77M and 177M differ by a decimal point at 11px, and
			   reading that wrong by a factor of a hundred is the difference
			   between affording a flip and not. */
			p.add(leftStrut(4));
			final String tip = tip(String.format("%,d", c.capital) + " gp tied up",
				"Sized to the cash you have free and the slots you have spare.");
			final JLabel capName = new JLabel("Capital needed");
			capName.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			capName.setFont(capName.getFont().deriveFont(11f));
			capName.setAlignmentX(0f);
			capName.setToolTipText(tip);
			p.add(capName);
			final JLabel capValue = new JLabel(String.format("%,d", c.capital) + " gp");
			capValue.setForeground(TEXT_MAIN);
			capValue.setFont(capValue.getFont().deriveFont(Font.BOLD, 13f));
			capValue.setAlignmentX(0f);
			capValue.setToolTipText(tip);
			p.add(capValue);
		}

		/* The chart button leads the controls row on every card, so it sits
		   in the same place each time and costs the title nothing. Done here
		   rather than in each card builder so none of them can forget it. */
		if (c.controls != null)
		{
			final JPanel withChart = controlsRow();
			addControl(withChart, chartButton(itemName));
			for (java.awt.Component existing : c.controls.getComponents())
			{
				withChart.add(existing);
			}
			c.controls = withChart;
		}
		/*
		 * Footnote ABOVE the buttons, and the buttons last on every card.
		 *
		 * This was the other way round, which meant the two kinds of small
		 * print sat on opposite sides of the button row: "Capital needed"
		 * above it on a buy, "At a loss — Hold to keep it" below it on a
		 * sell. Measured, that put Next 27px further down on a buy card than
		 * on a sell — so paging through ideas moved the button out from under
		 * the cursor, on a control whose entire purpose is being pressed
		 * repeatedly.
		 *
		 * All the reading matter above, all the verbs below.
		 */
		if (c.footnote != null)
		{
			p.add(leftStrut(6));
			JLabel foot = new JLabel(c.footnote);
			foot.setForeground(c.footnoteWarn ? ADJUST : ColorScheme.LIGHT_GRAY_COLOR);
			foot.setFont(foot.getFont().deriveFont(c.footnoteWarn ? Font.BOLD : Font.PLAIN, 10f));
			foot.setAlignmentX(0f);
			p.add(foot);
		}

		if (c.controls != null)
		{
			/* Takes up whatever height the card has over its minimum, so the
			   buttons sit on the bottom edge rather than floating under
			   whichever rows this particular card happened to need. */
			p.add(Box.createVerticalGlue());
			p.add(leftStrut(8));
			c.controls.setAlignmentX(0f);
			/* The row is held to the buttons' own height. The horizontal
			   struts between them report an unbounded maximum HEIGHT, which
			   made the whole row a second glue: it took a share of the card's
			   slack and centred the buttons in it, so Next sat 13px lower on
			   a full card than on a sparse one — with every card the same
			   height. Measured: 233 to 246. */
			p.add(holdHeight(c.controls));
		}

		if (c.tooltip != null)
		{
			p.setToolTipText(c.tooltip);
		}

		wireHover(p, OBSIDIAN_BG);
		return p;
	}

	private JLabel iconLabel(int itemId, int size)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(size, size));
		label.setMinimumSize(new Dimension(size, size));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null && itemId > 0)
		{
			/* Null-checked. ItemManager.getImage builds from the client's
			   item sprites, which are not loaded on the login screen — and
			   these panels render there. An NPE here does not just lose one
			   icon: it is thrown inside a row builder called from update(),
			   so it aborts the whole rebuild mid-list and takes the rest of
			   the panel with it. Same failure the getItemStats guard nearby
			   exists for. A missing icon is a blank square; a missing panel
			   reads as the plugin being broken. */
			final AsyncBufferedImage img = itemManager.getImage(itemId);
			if (img != null)
			{
				img.addTo(label);
			}
		}
		return label;
	}

	/** A hover tint on the whole card, nothing more — clicking anywhere on
	 *  the card used to also open the browser, which meant tapping the
	 *  profit/rating text just to read it (or, now that this card has a
	 *  Share and a favorite-toggle button too, missing one of those by a
	 *  few pixels) fired off a browser tab you didn't ask for. Opening the
	 *  chart is chartButton's job specifically now, not the card's. */
	/** Clicking the item itself opens its chart — the same affordance the
	 *  favorites rows have, so the gesture is consistent wherever an item
	 *  name appears. Scoped to the name and the sprite rather than the whole
	 *  card: the controls row sits a few pixels away, and a card-wide click
	 *  target put "open a browser tab" one slip away from every button. */
	private void wireOpenChartOnClick(java.awt.Component c, String itemName)
	{
		c.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		c.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (e.getButton() == MouseEvent.BUTTON1)
				{
					actions.openChart(itemName);
				}
			}
		});
	}

	private static void wireHover(JPanel row, Color normalBg)
	{
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseEntered(MouseEvent e)
			{
				row.setBackground(HOVER_BG);
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				row.setBackground(normalBg);
			}
		});
	}

	private JButton smallBtn(String label, String tip, java.awt.event.ActionListener a)
	{
		JButton b = new JButton(label);
		b.setToolTipText(tip);
		b.setFocusPainted(false);
		b.setFont(b.getFont().deriveFont(11f));
		b.setMargin(new Insets(2, 6, 2, 6));
		b.addActionListener(a);
		return b;
	}

	/** The ONLY way this card opens the live chart — mirroring Flipping
	 *  Copilot's own graph icon next to item names — gold and sized up a
	 *  notch from the rest of the card's chrome so it reads as an obvious,
	 *  clickable affordance rather than a subtle decoration. Clicking
	 *  elsewhere on the card no longer does this (see wireOpenChart) — with
	 *  a Share and a favorite-toggle button also living on this card now, a
	 *  whole-card click target meant any of those was one stray pixel away
	 *  from opening a browser tab instead. Drawn with Java2D rather than an
	 *  emoji glyph — emoji font fallback support is inconsistent across the
	 *  JREs RuneLite runs on, so a relied-on affordance icon needs to
	 *  render the same everywhere. */
	/**
	 * Left-click hands the item to a PocketGE tab you already have open;
	 * right-click forces a new browser tab instead.
	 *
	 * Right-click rather than a second button because the controls row has
	 * no width to give — Next, Hold and Block already fought over it, and
	 * Pause had to leave for the top bar. A second chart icon would cost a
	 * visible, frequently-pressed control to serve the rarer case.
	 *
	 * Rare, but not unwanted: the handoff deliberately reuses the tab you
	 * were looking at, so comparing two items means losing the first. This
	 * is the escape hatch, and the tooltip says it exists since a right-click
	 * on a toolbar button is not something anyone discovers by accident.
	 */
	/**
	 * Ask before blocking, unless told not to.
	 *
	 * Blocking is the only action on the card that is silent, permanent and
	 * easy to hit by accident: the icon sits in the same row as Next, nothing
	 * on screen changes when it lands, and the item just never appears again.
	 * Someone who fat-fingers it has no way to know what happened, let alone
	 * which item it was — so the dialog names the item, and says where to undo
	 * it. That last part is the half that matters: a confirmation that only
	 * says "are you sure" leaves you no better off if you were wrong.
	 *
	 * The tick is a real preference, not a session flag, so it survives a
	 * restart for people who block deliberately and often.
	 *
	 * @return true if the block should go ahead
	 */
	private boolean confirmBlock(String itemName)
	{
		if (!settings.confirmBlock)
		{
			return true;
		}
		final JPanel body = new JPanel();
		body.setLayout(new BoxLayout(body, BoxLayout.Y_AXIS));
		final JLabel ask = new JLabel("<html>Never recommend <b>" + itemName + "</b> again?</html>");
		ask.setAlignmentX(0f);
		body.add(ask);
		body.add(Box.createVerticalStrut(8));
		final JLabel how = new JLabel("<html><span style='color:#8a8274'>To unblock it later, open "
			+ "settings (\u2699) and edit the never-recommend list.</span></html>");
		how.setAlignmentX(0f);
		body.add(how);
		body.add(Box.createVerticalStrut(10));
		final javax.swing.JCheckBox dontAsk = new javax.swing.JCheckBox("Don't ask me again");
		dontAsk.setAlignmentX(0f);
		dontAsk.setOpaque(false);
		body.add(dontAsk);

		final int choice = javax.swing.JOptionPane.showConfirmDialog(this, body, "Block item",
			javax.swing.JOptionPane.OK_CANCEL_OPTION, javax.swing.JOptionPane.QUESTION_MESSAGE);
		if (choice != javax.swing.JOptionPane.OK_OPTION)
		{
			/* Cancelled. The tick is deliberately NOT saved here — someone who
			   ticks it and then backs out has not agreed to anything, and
			   silently disabling the guard on the way out of a cancelled
			   dialog is how you lose the next item by accident. */
			return false;
		}
		if (dontAsk.isSelected())
		{
			settings.confirmBlock = false;
			actions.setConfirmBlock(false);
		}
		return true;
	}

	private JButton chartButton(String itemName)
	{
		JButton b = new JButton(CHART_ICON);
		b.setToolTipText(tip("Chart on pocketge.com", "Right-click for a new browser tab."));
		b.setMargin(new Insets(2, 4, 2, 4));
		b.addActionListener(e -> actions.openChart(itemName));
		b.setComponentPopupMenu(chartPopup(itemName));
		/* Dressed like the buttons beside it. It was the one control on the
		   row left on the look-and-feel's default face — a different fill, a
		   different border, no fixed size — so it read as something that had
		   wandered in from another panel. */
		return styleAsControl(b);
	}

	/** Last value handed to {@link Actions#onRecommendationShown}, so an
	 *  unchanged card does not re-announce itself on every rebuild. */
	private Integer announcedRecItemId;
	private boolean announcedRecSell;

	private void announceShownRecommendation(Rec r)
	{
		final Integer id = r != null ? r.itemId : null;
		final boolean sell = r != null && r.sell;
		if (java.util.Objects.equals(id, announcedRecItemId) && sell == announcedRecSell)
		{
			return;
		}
		announcedRecItemId = id;
		announcedRecSell = sell;
		actions.onRecommendationShown(id, r != null ? r.name : null, sell);
	}

	/** One of the two coloured instruction lines either side of the item name.
	 *  13f rather than the 14f the single action line used: three stacked
	 *  lines have to fit the sprite's height beside them. */
	/**
	 * The width every card tooltip wraps to.
	 *
	 * Swing lays a plain-string tooltip out on ONE line however long it is,
	 * which is how "The sell price this profit assumes — 19 gp. Ask that back
	 * out and the green number below is what you keep after the 2% tax."
	 * became a 700px ribbon lying across the middle of the screen. A width
	 * turns it into a block; the shape below turns it into something you can
	 * read at a glance.
	 */
	private static final int TIP_W = 185;

	/**
	 * One shape for every tooltip on a card: a bold first line that names the
	 * number or the thing, then at most one short sentence under it.
	 *
	 * Having a shape is most of the point. These were written one at a time
	 * and read like it — some led with the value, some with an explanation,
	 * some ran to three clauses — so hovering two of them in a row felt like
	 * reading two different products. Title, then the reason, then nothing.
	 */
	static String tip(String title, String body)
	{
		final StringBuilder b = new StringBuilder("<html><body style='width:")
			.append(TIP_W).append("px'><b>").append(title).append("</b>");
		if (body != null && !body.isEmpty())
		{
			b.append("<br>").append(body);
		}
		return b.append("</body></html>").toString();
	}

	private static JLabel actionLine(String text, Color fg)
	{
		final JLabel l = new JLabel(text);
		l.setForeground(fg);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 13f));
		l.setAlignmentX(0f);
		return l;
	}

	/**
	 * The site card's score, laid flat for a 225px sidebar: the verdict on
	 * the left, the number on the right, both in the band's colour, and the
	 * meter running between them. On the site the number stacks over the
	 * word with the meter beneath, in a 58px column beside the name; here
	 * that column would come straight out of the item name, which is
	 * already the thing this card truncates first — and a meter on a row of
	 * its own cost 8px that every card then had to be held to.
	 */
	private JPanel scoreRow(TradeEngine.FlipScore s)
	{
		final Color fg = new Color(s.band.color);
		final String tip = scoreTip(s);
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(0f);
		final JLabel word = new JLabel(s.band.word);
		word.setForeground(fg);
		word.setFont(word.getFont().deriveFont(Font.BOLD, 11f));
		word.setToolTipText(tip);
		word.setAlignmentY(0.5f);
		final JLabel num = new JLabel(String.valueOf(s.total));
		num.setForeground(fg);
		num.setFont(num.getFont().deriveFont(Font.BOLD, 15f));
		num.setToolTipText(tip);
		num.setAlignmentY(0.5f);
		final JComponent meter = scoreMeter(s);
		meter.setAlignmentY(0.5f);
		row.add(word);
		row.add(Box.createHorizontalStrut(8));
		row.add(meter);
		row.add(Box.createHorizontalStrut(8));
		row.add(num);
		return holdHeight(row);
	}

	/**
	 * Pins a row to its own preferred height, so it cannot take a share of
	 * the card's slack.
	 *
	 * The card is a vertical BoxLayout held to MIN_CARD_HEIGHT with a glue
	 * above the buttons that is meant to take every spare pixel. BoxLayout
	 * hands spare height to EVERY child whose maximum exceeds its preferred,
	 * in proportion — and that is more of them than it looks: a BorderLayout
	 * panel reports no maximum at all, a GridLayout panel likewise, and a
	 * horizontal BoxLayout row inherits the tallest maximum among its
	 * children, which for a horizontal strut or glue is unbounded. So the
	 * name row, the price pair, the money line and the button row were all
	 * quietly stretching, and Next landed somewhere different on every card
	 * of the same height.
	 */
	private static <T extends JComponent> T holdHeight(T row)
	{
		row.setMaximumSize(new Dimension(Integer.MAX_VALUE, row.getPreferredSize().height));
		return row;
	}

	private static final int METER_H = 3;

	/** The 3px bar under the score: a faint track, filled to the score in
	 *  the band's colour. Same proportions as the site's .fc-meter. */
	private JComponent scoreMeter(TradeEngine.FlipScore s)
	{
		final Color fg = new Color(s.band.color);
		final JComponent bar = new JComponent()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				final int w = getWidth();
				final int h = getHeight();
				g2.setColor(new Color(255, 255, 255, 26));
				g2.fillRoundRect(0, 0, w, h, h, h);
				g2.setColor(fg);
				g2.fillRoundRect(0, 0, Math.round(w * s.total / 100f), h, h, h);
				g2.dispose();
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(0f);
		bar.setPreferredSize(new Dimension(10, METER_H));
		bar.setMinimumSize(new Dimension(10, METER_H));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, METER_H));
		bar.setToolTipText(scoreTip(s));
		return bar;
	}

	/**
	 * The score, showing its working.
	 *
	 * A definition cannot answer "why is this one 96 and that one 70", so
	 * this prints the live arithmetic instead. What it does NOT do any more
	 * is explain each term in a sentence on the same line: that ran the
	 * widest row out past seventy characters ("Net edge 26.7% +45 / 45 —
	 * maxed: anything past a 3% edge scores the same") and left the three
	 * numbers that matter unaligned in the middle of prose. They are a
	 * column now, right-aligned, with the caps stated once underneath.
	 */
	static String scoreTip(TradeEngine.FlipScore s)
	{
		final String hex = String.format("#%06X", s.band.color);
		final String edgePct = String.format(s.edgePct < 0.1 ? "%.2f" : "%.1f", s.edgePct * 100);
		final StringBuilder b = new StringBuilder("<html>");
		b.append("<font color='").append(hex).append("'><b>").append(s.band.word)
			.append(" — ").append(s.total).append(" of 100</b></font><br>");
		b.append("How good this flip looks right now, not the item.");
		b.append("<table cellpadding=0 cellspacing=0>");
		scoreTipRow(b, "Engine-cleared", "+" + Math.round(s.base), "");
		scoreTipRow(b, "Net edge " + edgePct + "%", "+" + Math.round(s.edge),
			s.edgeMaxed ? "of 45 &nbsp;maxed" : "of 45");
		scoreTipRow(b, "Liquidity " + QuantityFormatter.quantityToStackSize(s.vol) + "/day",
			"+" + Math.round(s.liq), s.liqMaxed ? "of 35 &nbsp;maxed" : "of 35");
		if (s.lowConf)
		{
			scoreTipRow(b, "Thin tape", "−" + Math.round(s.penalty), "one side is quiet");
		}
		b.append("</table>");
		for (int i = 0; i < TradeEngine.FlipScore.BANDS.length; i++)
		{
			final TradeEngine.FlipScore.Band band = TradeEngine.FlipScore.BANDS[i];
			if (i > 0)
			{
				b.append(" &nbsp;");
			}
			final String label = band.word.replace(" Flip", "") + " " + (band.min > 0 ? "" + band.min : "&lt;55");
			b.append(band == s.band ? "<b>" + label + "</b>" : label);
		}
		b.append("<br>Edge maxes at 3%, liquidity at 10M a day.");
		return b.toString();
	}

	/** One row of the score table: name, the points right-aligned in their
	 *  own column so they can be read down, then what they are out of. */
	private static void scoreTipRow(StringBuilder b, String name, String points, String outOf)
	{
		b.append("<tr><td>").append(name).append("&nbsp;&nbsp;&nbsp;</td>")
			.append("<td align=right><b>").append(points).append("</b>&nbsp;&nbsp;</td>")
			.append("<td>").append(outOf).append("</td></tr>");
	}

	/**
	 * The stat cells side by side, each at its own width.
	 *
	 * Not a GridLayout. Equal columns gave QUANTITY and CAPITAL 98px apiece,
	 * and "20,160,000 gp" does not fit 98px at 13pt bold — so the one figure
	 * this card refuses to abbreviate was being ellipsised by the layout
	 * into "20,160,000…", which is the same misread with an extra step. Each
	 * cell takes what it needs; the gap is fixed; spare width goes to the
	 * right, where nothing is.
	 */
	private JPanel statsRow(List<Card.Stat> stats)
	{
		final JPanel row = new JPanel();
		row.setLayout(new BoxLayout(row, BoxLayout.X_AXIS));
		row.setOpaque(false);
		row.setAlignmentX(0f);
		for (int i = 0; i < stats.size(); i++)
		{
			if (i > 0)
			{
				row.add(Box.createHorizontalStrut(16));
			}
			final JPanel cell = statCell(stats.get(i));
			cell.setAlignmentY(0f);
			row.add(cell);
		}
		row.add(Box.createHorizontalGlue());
		return holdHeight(row);
	}

	private JPanel statCell(Card.Stat s)
	{
		final JPanel cell = new JPanel();
		cell.setLayout(new BoxLayout(cell, BoxLayout.Y_AXIS));
		cell.setOpaque(false);
		final JLabel label = new JLabel(s.label);
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(label.getFont().deriveFont(Font.BOLD, 9f));
		label.setAlignmentX(0f);
		final JLabel value = new JLabel(s.value);
		value.setForeground(TEXT_MAIN);
		value.setFont(value.getFont().deriveFont(Font.BOLD, 13f));
		value.setAlignmentX(0f);
		if (s.tip != null)
		{
			cell.setToolTipText(s.tip);
			label.setToolTipText(s.tip);
			value.setToolTipText(s.tip);
		}
		cell.add(label);
		cell.add(value);
		return cell;
	}

	/** The site card's Buy @ / Sell @ row: two boxes, each tinted its side's
	 *  colour at the site's own opacities (10% fill, 30% border), the label
	 *  in the colour and the number in plain text. */
	private JPanel pairRow(Card.PricePair pp)
	{
		final JPanel row = new JPanel(new GridLayout(1, 2, 8, 0));
		row.setOpaque(false);
		row.setAlignmentX(0f);
		row.add(priceBox(pp.buyLabel, pp.buy, buyColor(), pp.buyTip));
		row.add(priceBox(pp.sellLabel, pp.sell, sellColor(), pp.sellTip));
		return holdHeight(row);
	}

	private JPanel priceBox(String label, long value, Color tint, String tip)
	{
		final JPanel box = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				final Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				/* Translucent rather than pre-blended against the card's
				   background, so the tint still reads right when the card
				   takes its hover colour. */
				g2.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 26));
				g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
				g2.setColor(new Color(tint.getRed(), tint.getGreen(), tint.getBlue(), 77));
				g2.drawRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);
				g2.dispose();
			}
		};
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setOpaque(false);
		box.setBorder(BorderFactory.createEmptyBorder(5, 6, 5, 6));
		box.setToolTipText(tip);
		final JLabel l = new JLabel(label);
		l.setForeground(tint);
		l.setFont(l.getFont().deriveFont(Font.BOLD, 10f));
		l.setAlignmentX(0.5f);
		l.setToolTipText(tip);
		final JLabel v = new JLabel(value > 0 ? String.format("%,d", value) : "—");
		v.setForeground(value > 0 ? TEXT_MAIN : ColorScheme.LIGHT_GRAY_COLOR);
		v.setFont(v.getFont().deriveFont(Font.BOLD, 14f));
		v.setAlignmentX(0.5f);
		v.setToolTipText(tip);
		box.add(l);
		box.add(v);
		return box;
	}

	/** The right-click menu on a chart button. Same shape as the GE slot's
	 *  own popup (see GeSlotsPanel), so the two behave alike. */
	private JPopupMenu chartPopup(String itemName)
	{
		final JPopupMenu menu = new JPopupMenu();
		final JMenuItem newTab = new JMenuItem("Open in a new browser tab");
		newTab.setToolTipText("Ignore any PocketGE tab already open and launch a fresh one — "
			+ "for comparing two items side by side.");
		newTab.addActionListener(e -> actions.openChartInNewTab(itemName));
		menu.add(newTab);
		return menu;
	}

	private static Icon buildChartIcon(float scale)
	{
		final int w = Math.round(13 * scale);
		final int h = Math.round(11 * scale);
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(1.6f * scale, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		int[] xs = scalePoints(new int[]{0, 4, 7, 11}, scale);
		int[] ys = scalePoints(new int[]{9, 5, 7, 1}, scale);
		g.drawPolyline(xs, ys, 4);
		g.drawLine(xs[3], ys[3], Math.round(8 * scale), ys[3]); // arrowhead
		g.drawLine(xs[3], ys[3], xs[3], Math.round(4 * scale));
		g.dispose();
		return new ImageIcon(img);
	}

	/** Three connected nodes — the standard "share" glyph (same shape as the
	 *  website's Share button icon), drawn rather than an emoji for the same
	 *  cross-JRE-font-fallback reason as the chart icon. */
	/** A right chevron, drawn. The U+203A glyph it replaces rendered as a
	 *  stray comma in the client — the same cross-JRE font-fallback problem
	 *  the chart and share icons are drawn to avoid. */
	/** The Next chevron, mirrored. Drawn rather than flipped at paint time so
	 *  the two are pixel-identical apart from direction. */
	/**
	 * One chevron, pointing either way, in whatever colour the state wants.
	 *
	 * Drawn rather than set as text for the reason the whole icon set is
	 * drawn: the ‹ and › the website uses are U+2039/U+203A, and U+203A
	 * already rendered as a stray comma in this client once. A glyph is a
	 * request that the JRE find a font containing it; a polyline is not.
	 *
	 * 9x13 rather than the 7x10 these were, because the button around them
	 * grew to match the site's 24px target and a chevron that small inside
	 * it read as a speck.
	 */
	private static Icon buildChevron(boolean back, Color fg)
	{
		final int w = 9, h = 13;
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(fg);
		g.setStroke(new BasicStroke(1.9f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		final int[] xs = back ? new int[]{6, 2, 6} : new int[]{2, 6, 2};
		g.drawPolyline(xs, new int[]{1, h / 2, h - 2}, 3);
		g.dispose();
		return new ImageIcon(img);
	}

	private static Icon buildStarIcon(boolean filled)
	{
		final int size = 13;
		final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final int[] xs = new int[10];
		final int[] ys = new int[10];
		final double cx = size / 2.0, cy = size / 2.0;
		for (int i = 0; i < 10; i++)
		{
			final double r = (i % 2 == 0) ? size / 2.0 - 0.5 : size / 4.6;
			final double a = -Math.PI / 2 + i * Math.PI / 5;
			xs[i] = (int) Math.round(cx + r * Math.cos(a));
			ys[i] = (int) Math.round(cy + r * Math.sin(a));
		}
		g.setColor(GOLD);
		if (filled)
		{
			g.fillPolygon(xs, ys, 10);
		}
		else
		{
			g.setStroke(new BasicStroke(1.2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
			g.drawPolygon(xs, ys, 10);
		}
		g.dispose();
		return new ImageIcon(img);
	}

	private static Icon buildPauseIcon()
	{
		final int w = 11, h = 11;
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(TEXT_MAIN);
		g.fillRect(1, 0, 3, h);
		g.fillRect(7, 0, 3, h);
		g.dispose();
		return new ImageIcon(img);
	}

	/** An open hand-ish "keep this" mark: a filled square in a bracket. Kept
	 *  deliberately unlike the block glyph so the two aren't confused at a
	 *  glance — one is temporary, the other permanent. */
	private static Icon buildHoldIcon()
	{
		final int size = 12;
		final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(SELL_COLOR);
		g.setStroke(new BasicStroke(1.6f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawLine(2, 1, 2, size - 2);
		g.drawLine(size - 3, 1, size - 3, size - 2);
		g.fillRect(4, 4, size - 8, size - 8);
		g.dispose();
		return new ImageIcon(img);
	}

	/** Circle-slash — the same "never again" mark other flip tools use. */
	private static Icon buildBlockIcon()
	{
		final int size = 12;
		final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(NEGATIVE);
		g.setStroke(new BasicStroke(1.6f));
		g.drawOval(1, 1, size - 3, size - 3);
		g.drawLine(3, size - 4, size - 4, 3);
		g.dispose();
		return new ImageIcon(img);
	}

	/** Bigger and squarer than smallBtn, with a painted background so the
	 *  icon reads as a real control — the old text buttons were too cramped
	 *  to identify at a glance in a 225px column. */
	private JButton bigIconBtn(Icon icon, String tip, java.awt.event.ActionListener a)
	{
		final JButton b = new JButton(icon);
		b.setToolTipText(tip);
		b.addActionListener(a);
		return styleAsControl(b);
	}

	/** The controls-row look, applied to buttons built elsewhere (the share
	 *  button, which needs its own click behaviour) as well as to
	 *  {@link #bigIconBtn}'s. */
	private static JButton styleAsControl(JButton b)
	{
		b.setFocusPainted(false);
		b.setOpaque(true);
		b.setContentAreaFilled(true);
		b.setBorderPainted(true);
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
		sizeExactly(b, CONTROL_W, CONTROL_H);
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		return b;
	}

	/** Pins a button to one exact size in all three dimensions Swing asks
	 *  about. setPreferredSize alone is not enough inside a BoxLayout row:
	 *  JComponent.getMaximumSize falls through to the UI's own computed
	 *  preferred size when no maximum was set, so a button that "is" 34px
	 *  wide will still be stretched by the layout — and the controls row has
	 *  no spare width to give away. */
	private static void sizeExactly(JButton b, int w, int h)
	{
		final Dimension d = new Dimension(w, h);
		b.setPreferredSize(d);
		b.setMinimumSize(d);
		b.setMaximumSize(d);
	}

	private static int[] scalePoints(int[] points, float scale)
	{
		int[] out = new int[points.length];
		for (int i = 0; i < points.length; i++)
		{
			out[i] = Math.round(points[i] * scale);
		}
		return out;
	}

	private JPanel chip(String name)
	{
		JPanel c = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
		c.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		c.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 4));
		JLabel n = new JLabel(name);
		n.setForeground(Color.WHITE);
		n.setFont(n.getFont().deriveFont(12f));
		JButton x = new JButton("×");
		x.setToolTipText("Remove " + name + " from the never-recommend list");
		x.setFocusPainted(false);
		x.setMargin(new Insets(0, 4, 0, 4));
		x.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		x.addActionListener(e -> actions.unblock(name));
		c.add(n);
		c.add(x);
		return c;
	}


	private Color accent(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return buyColor();
			case SELL: return sellColor();
			/* An adjust is neither: it is a state of an offer already out
			   there, so it keeps its own orange whatever the pair is. */
			default: return ADJUST;
		}
	}

	/**
	 * The two colours that mean buy and sell right now.
	 *
	 * Read per paint rather than cached, because the setting can change under
	 * a card that is already on screen and the panel is rebuilt far more often
	 * than anyone can click a menu. Falls back to the default pair when no
	 * settings have arrived yet, which is every paint before the first
	 * refresh.
	 *
	 * The buy side used to be GOLD in two of these places and BUY_COLOR in the
	 * third — two near-identical golds, which nobody could see but which meant
	 * there was no single "buy colour" to change. Now there is.
	 */
	private Color buyColor()
	{
		return settings != null && settings.theme != null ? settings.theme.buy() : BUY_COLOR;
	}

	private Color sellColor()
	{
		return settings != null && settings.theme != null ? settings.theme.sell() : SELL_COLOR;
	}

}
