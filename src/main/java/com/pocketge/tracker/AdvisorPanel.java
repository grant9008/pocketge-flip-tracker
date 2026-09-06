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
import javax.swing.JLabel;
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
	private static final int CONTROL_GAP = 3;
	private static final int NEXT_BTN_W = 62;
	private static final Icon CHART_ICON = buildChartIcon(1.45f);
	private static final Icon SHARE_ICON = buildShareIcon();
	private static final Icon NEXT_ICON = buildNextIcon();
	private static final Icon PAUSE_ICON = buildPauseIcon();
	private static final Icon HOLD_ICON = buildHoldIcon();
	private static final Icon BLOCK_ICON = buildBlockIcon();
	private static final Icon STAR_FILLED_ICON = buildStarIcon(true);
	private static final Icon STAR_HOLLOW_ICON = buildStarIcon(false);

	public interface Actions
	{
		void skip(int itemId);
		void block(String itemName);
		void unblock(String itemName);
		void toggleFavorite(int itemId, String name);
		void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v);
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
		/** Re-run the advisor now. Fired when Next walks off the end of the
		 *  list, so "no more ideas" turns into fresh ones instead of the same
		 *  ring of suggestions going round again. */
		void refreshSuggestions();
	}

	/** Everything the gear-icon popup shows/edits, bundled so update()
	 *  doesn't grow another loose parameter every time a new setting moves
	 *  in here. Plain public fields, matching this codebase's other small
	 *  data-holder classes (Advisor.Quote, Advisor.ItemMeta, ...). */
	public static class Settings
	{
		public boolean advisorOn;
		public PocketGeTrackerConfig.AdjustInterval interval = PocketGeTrackerConfig.AdjustInterval.M5;
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
		public String note;         // optional one-liner (why this, or what capped it)
		/** gp this ties up. Buys only; 0 on a sell, which frees capital
		 *  rather than consuming it. */
		public long capital;
	}

	private List<Advisor.Suggestion> currentSuggestions = List.of();
	private Map<Integer, AnalystRating.Grade> currentRatings = Map.of();
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
		gearBtn.setToolTipText("Settings: advisor, re-check interval, never-recommend list, website bridge, flip history size");
		gearBtn.setOpaque(true);
		gearBtn.setContentAreaFilled(true);
		gearBtn.setBorderPainted(true);
		gearBtn.setFocusPainted(false);
		gearBtn.setFont(gearBtn.getFont().deriveFont(Font.BOLD, 13f));
		gearBtn.setMargin(new Insets(2, 6, 2, 6));
		gearBtn.setPreferredSize(new Dimension(26, 22));
		gearBtn.setMaximumSize(new Dimension(26, 22));
		gearBtn.setBackground(GOLD);
		gearBtn.setForeground(Color.BLACK);
		gearBtn.setBorder(BorderFactory.createLineBorder(GOLD.darker(), 1));
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

	private void showSettingsPopup()
	{
		JPopupMenu popup = new JPopupMenu();
		popup.addPopupMenuListener(new javax.swing.event.PopupMenuListener()
		{
			@Override
			public void popupMenuWillBecomeVisible(javax.swing.event.PopupMenuEvent e)
			{
			}

			@Override
			public void popupMenuWillBecomeInvisible(javax.swing.event.PopupMenuEvent e)
			{
				settingsClosedAt = System.currentTimeMillis();
			}

			@Override
			public void popupMenuCanceled(javax.swing.event.PopupMenuEvent e)
			{
				settingsClosedAt = System.currentTimeMillis();
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

		popup.add(content);
		popup.show(gearBtn, 0, gearBtn.getHeight() + 4);
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

	/**
	 * Share, for the pinned top bar: posts whatever card is on screen right
	 * now — the flip being recommended, the watchlist item you clicked, or
	 * the offer you have open.
	 *
	 * Reads the card at CLICK time rather than capturing one at build time,
	 * which is the whole reason it can live outside the card: the top bar is
	 * built once and the card underneath it changes constantly.
	 */
	public JButton shareButton()
	{
		final JButton b = new JButton(SHARE_ICON);
		b.setToolTipText("Copy an image of the card below (for Reddit/Discord)");
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 6, 2, 6));
		b.setPreferredSize(new Dimension(30, 22));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e ->
		{
			final Card c = shownCard;
			if (c == null)
			{
				/* Nothing on the card worth posting (logged out, or still
				   looking). Say so rather than copying a blank image. */
				b.setToolTipText("Nothing to share yet");
				return;
			}
			copyImageToClipboard(buildShareImage(c.itemId, c.name, c));
			final Color original = b.getBackground();
			b.setBackground(POSITIVE);
			b.setToolTipText("Copied — paste it into Reddit or Discord");
			final javax.swing.Timer revert = new javax.swing.Timer(1200, ev ->
			{
				b.setBackground(original);
				b.setToolTipText("Copy an image of the card below (for Reddit/Discord)");
			});
			revert.setRepeats(false);
			revert.start();
		});
		return b;
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
			? "Suggestions paused — click to resume updating"
			: "Pause suggestions — keep the current one on screen while you work");
		pauseBtn.setBackground(paused ? GOLD : pauseIdleBackground);
		pauseBtn.setForeground(paused ? Color.BLACK : pauseIdleForeground);
	}

	/** Rebuild everything. Call on the EDT.
	 *  {@code ratings} is itemId -> Analyst Rating grade; missing entries
	 *  just render without a badge. {@code favoriteIds} decides whether a
	 *  card's star renders filled or hollow. {@code settings} is stashed for
	 *  the next time the gear-icon popup opens — that's where every field on
	 *  it (advisor on/off, interval, blocklist, bridge, flip count)
	 *  gets edited. */
	public void update(List<Advisor.Suggestion> suggestions,
		Map<Integer, AnalystRating.Grade> ratings, Set<Integer> favoriteIds, Settings settings)
	{
		this.favoriteIds = favoriteIds != null ? favoriteIds : Set.of();
		this.currentRatings = ratings != null ? ratings : Map.of();
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
		this.selectedFavorite = r;
		this.selectedFavoriteId = r != null ? r.id : -1;
		if (r != null && geContextItemId != null)
		{
			this.geContextDismissedFor = geContextItemId;
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
		// Next comes first here for the same reason it does on a flip: it is
		// the way out of this card and back into the stream.
		if (!recommendations.isEmpty())
		{
			addControl(controls, nextButton());
		}
		/* Share has moved to the pinned top bar. It was the least-pressed
		   control sitting in the most-pressed row, taking width from Next,
		   hold and block on a 225px sidebar — and it never needed to be
		   per-card, because there is only ever one card on screen. */
		final boolean fav = favoriteIds.contains(r.id);
		addControl(controls, bigIconBtn(fav ? STAR_FILLED_ICON : STAR_HOLLOW_ICON,
			fav ? "Remove " + r.name + " from favorites" : "Add " + r.name + " to favorites",
			e -> actions.toggleFavorite(r.id, r.name)));
		addControl(controls, bigIconBtn(BLOCK_ICON, "Never recommend " + r.name + " again",
			e -> actions.block(r.name)));
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
		JLabel title = new JLabel("Log in to the game");
		title.setForeground(GOLD);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 14f));
		title.setAlignmentX(0f);
		p.add(title);
		JLabel sub = new JLabel("to start getting flips");
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(sub.getFont().deriveFont(12f));
		sub.setAlignmentX(0f);
		sub.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		p.add(sub);
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
		if (itemId == null || !itemId.equals(geContextDismissedFor))
		{
			/* A different item, or the screen closed: whatever you dismissed
			   is no longer what is on screen, so the takeover is earned
			   again. Without this, pressing Next once would suppress the
			   offer card for every subsequent offer too. */
			this.geContextDismissedFor = null;
		}
		this.geContextItemId = itemId;
		this.geContextName = name != null ? name : "";
		this.geContextIsBuy = isBuy;
		this.geContextPrice = price;
		renderRecommendation();
	}

	/** The offer-screen takeover: what to type, for the item actually on
	 *  screen. Same shell as a recommendation so the box doesn't visibly
	 *  change shape when it switches over. */
	private JPanel geContextBody()
	{
		final int itemId = geContextItemId;
		final String name = geContextName;
		final boolean isBuy = geContextIsBuy;

		Card c = new Card();
		c.accent = isBuy ? GOLD : SELL_COLOR;
		c.itemId = itemId;
		c.name = name;
		c.actionText = (isBuy ? "Buy at " : "Sell at ")
			+ QuantityFormatter.quantityToStackSize(geContextPrice) + " gp each";
		c.subText = "also written on the offer screen";

		JPanel controls = controlsRow();
		/* Next is here for the misclick: you opened an offer screen for the
		   wrong item and want the suggestion stream back without having to
		   close the screen first. Before this the takeover was a one-way
		   door for as long as the screen stayed up. */
		addControl(controls, nextButton());
		final boolean fav = favoriteIds.contains(itemId);
		addControl(controls, bigIconBtn(fav ? STAR_FILLED_ICON : STAR_HOLLOW_ICON,
			fav ? "Remove " + name + " from favorites" : "Add " + name + " to favorites",
			e -> actions.toggleFavorite(itemId, name)));
		addControl(controls, bigIconBtn(BLOCK_ICON, "Never recommend " + name + " again",
			e -> actions.block(name)));
		c.controls = controls;
		c.footnote = "Offer screen open";
		shownCard = c;
		return buildCard(c);
	}

	/** Matches the site's text-overflow ellipsis on the collapsed flip
	 *  card — long names get cut with an ellipsis; the full name is still
	 *  reachable via the row's tooltip. */
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

		header.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e) { onToggle.run(); }
		});

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
		final JPanel body;
		final boolean offerOwnsBox = geContextItemId != null
			&& !geContextItemId.equals(geContextDismissedFor);
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
		final String title = offerOwnsBox ? "YOUR OFFER"
			: selectedFavorite != null ? "WATCHING" : "RECOMMENDED FLIP";
		recommendationWrap.add(collapsibleSection(title, null, recommendationOpen,
			() -> { recommendationOpen = !recommendationOpen; renderRecommendation(); }, body), BorderLayout.NORTH);
		recommendationWrap.revalidate();
		recommendationWrap.repaint();
	}

	/** One idea, Copilot-shaped: what to do, at what price, what it makes,
	 *  its Analyst Rating, and the controls that matter — move on, hold it,
	 *  or stop being told about this item. */
	private JPanel recommendationBody(Rec r)
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
		c.actionLabel = r.sell ? "Target sell " : "Target buy ";
		/* Thousands separators, not the abbreviating formatter. These are
		   the two numbers you are about to type into the offer screen, and
		   "8.9K @ 729" is not a thing you can type. */
		c.actionText = String.format("%,d", r.quantity)
			+ " @ " + String.format("%,d", r.unitPrice) + " gp ea";
		c.actionColor = r.sell ? SELL_COLOR : BUY_COLOR;
		/* What it cost is the other half of the decision on a held stack, so
		   it stays. The "-14 gp/item margin at today's spread" line that used
		   to appear instead on an untracked stack is gone: a NEGATIVE margin
		   under a sell suggestion reads as "this is a bad idea" when the
		   actual message is "you already own it, sell it anyway". */
		c.subText = r.sell && r.unitCost > 0
			? "bought at " + QuantityFormatter.quantityToStackSize(r.unitCost) + " gp ea"
			: null;
		c.profitValue = r.profit;
		/* Three different claims, three different words, so none can be
		   mistaken for another: a buy projects "profit", a sell with a known
		   cost measures "P&L", and a sell without one can only report what
		   the sale brings in. That last one is green now \u2014 money arriving
		   IS good news \u2014 but it is never called profit, because the plugin
		   has no idea what the stack cost you. */
		c.profitSuffix = untracked ? "gp sale value" : r.sell ? "gp P&L" : "gp profit";
		c.profitSigned = !untracked;
		c.profitTooltip = untracked
			? "<html>What this stack fetches after the 2% tax.<br>The plugin never watched you buy it, so it cannot tell you "
				+ "your profit \u2014 only what selling brings in."
			: r.sell
				? "Profit after the 2% GE tax, measured against what the plugin watched you pay."
				: "Projected profit after the 2% GE tax.";
		c.capital = r.capital;
		c.tooltip = r.note;

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
		addControl(controls, nextButton());
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
			e -> actions.block(r.name)));
		c.controls = controls;

		/* No "3 of 20". The count was never something to act on, it cost a
		   whole line, and it framed the list as finite when Next now just
		   fetches more once it runs out. */
		c.footnote = paused ? "Paused" : null;
		c.footnoteWarn = paused;
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
	private JButton nextButton()
	{
		final JButton next = bigIconBtn(NEXT_ICON,
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
		// Wider than the rest: this is the control you press most, and at
		// icon-size it was the hardest one to hit.
		next.setText("Next");
		next.setForeground(TEXT_MAIN);
		next.setFont(next.getFont().deriveFont(Font.BOLD, 12f));
		next.setHorizontalTextPosition(SwingConstants.LEFT);
		next.setIconTextGap(4);
		sizeExactly(next, NEXT_BTN_W, CONTROL_H);
		return next;
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
		/** gp the position ties up, 0 to hide. */
		long capital;
		/** Buttons along the bottom. Null for none. */
		JPanel controls;
		JButton close;
		/** Small grey line at the very bottom — "3 of 12", "From your
		 *  watchlist". */
		String footnote;
		/** Colours the footnote as a warning instead of grey ("Paused"). */
		boolean footnoteWarn;
		String tooltip;
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
	private JPanel buildCard(Card c)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setAlignmentX(0f);
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, c.accent),
			BorderFactory.createEmptyBorder(9, CARD_PAD_L, 9, CARD_PAD_R)));

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
		row1.add(nameLabel, BorderLayout.CENTER);
		if (c.close != null)
		{
			JPanel eastWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
			eastWrap.setOpaque(false);
			eastWrap.add(c.close);
			row1.add(eastWrap, BorderLayout.EAST);
		}
		p.add(row1);

		if (c.actionText != null)
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
			p.add(leftStrut(4));
			JLabel profitLabel = new JLabel(moneyLine(c));
			profitLabel.setForeground(c.profitColor != null ? c.profitColor
				: c.profitValue >= 0 ? POSITIVE : NEGATIVE);
			profitLabel.setFont(profitLabel.getFont().deriveFont(Font.BOLD, 15f));
			profitLabel.setAlignmentX(0f);
			if (c.profitTooltip != null)
			{
				profitLabel.setToolTipText(c.profitTooltip);
			}
			p.add(profitLabel);
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
			final String tip = String.format("%,d", c.capital)
				+ " gp. What this position ties up — the plan is sized against your liquid "
				+ "cash and free GE slots, so it never suggests more than you can place.";
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
		if (c.controls != null)
		{
			p.add(leftStrut(8));
			c.controls.setAlignmentX(0f);
			p.add(c.controls);
		}

		if (c.footnote != null)
		{
			p.add(leftStrut(6));
			JLabel foot = new JLabel(c.footnote);
			foot.setForeground(c.footnoteWarn ? ADJUST : ColorScheme.LIGHT_GRAY_COLOR);
			foot.setFont(foot.getFont().deriveFont(c.footnoteWarn ? Font.BOLD : Font.PLAIN, 10f));
			foot.setAlignmentX(0f);
			p.add(foot);
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
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.addTo(label);
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
	private JButton chartButton(String itemName)
	{
		JButton b = new JButton(CHART_ICON);
		b.setToolTipText("Open the live " + itemName + " chart on PocketGE");
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 4, 2, 4));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e -> actions.openChart(itemName));
		return b;
	}

	private static final int SHARE_CARD_W = 640, SHARE_CARD_H = 300;

	private BufferedImage buildShareImage(int itemId, String itemName, Card c)
	{
		/* Reads the very Card the panel drew, so the image cannot word or
		   colour its headline differently from the card it claims to be. */
		final String actionText = c.actionText;
		final Color actionColor = c.actionColor;
		final BufferedImage img = new BufferedImage(SHARE_CARD_W, SHARE_CARD_H, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(OBSIDIAN_BG);
		g.fillRect(0, 0, SHARE_CARD_W, SHARE_CARD_H);
		g.setColor(GOLD);
		g.fillRect(0, 0, 6, SHARE_CARD_H);

		// Brand.
		g.setColor(GOLD);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 22f));
		g.drawString("PocketGE", 32, 44);
		g.setColor(new Color(0x8A, 0x82, 0x74));
		g.setFont(g.getFont().deriveFont(12f));
		g.drawString("Live OSRS Grand Exchange tracker", 32, 62);

		// Item icon + name.
		if (itemManager != null && itemId > 0)
		{
			try
			{
				final AsyncBufferedImage icon = itemManager.getImage(itemId);
				g.drawImage(icon, 32, 84, 48, 48, null);
			}
			catch (Exception ignore) { /* icon not ready yet — card still works without it */ }
		}
		g.setColor(TEXT_MAIN);
		g.setFont(g.getFont().deriveFont(Font.BOLD, 24f));
		g.drawString(itemName, 92, 108);

		int y = 160;
		if (actionText != null)
		{
			/* Same colour the card itself gave this line — the share image is
			   meant to be the card, and a screenshot that recolours its own
			   headline is worse than no image. Brand gold is still the bar
			   and the wordmark above. */
			g.setColor(actionColor != null ? actionColor : GOLD);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
			g.drawString(actionText, 32, y);
			y += 40;
		}
		if (c.profitValue != null)
		{
			g.setColor(c.profitColor != null ? c.profitColor : c.profitValue >= 0 ? POSITIVE : NEGATIVE);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
			g.drawString(moneyLine(c), 32, y);
			y += 40;
		}
		/* No Analyst Rating. It measures a different question from the card
		   above it and kept answering it out loud: the website could say
		   "ANALYST RATING: Sell" while the plugin said "RECOMMENDED FLIP:
		   Buy" for the same item at the same moment, and no amount of being
		   technically about different things saves a new player from reading
		   that as a contradiction. It is off the cards and off the shared
		   image; the website keeps the deeper analysis. */
		if (c.subText != null)
		{
			g.setColor(new Color(0x8A, 0x82, 0x74));
			g.setFont(g.getFont().deriveFont(14f));
			g.drawString(c.subText, 32, y);
		}

		// Footer watermark.
		g.setColor(new Color(0x2B, 0x26, 0x21));
		g.drawLine(32, SHARE_CARD_H - 40, SHARE_CARD_W - 32, SHARE_CARD_H - 40);
		g.setColor(new Color(0x8A, 0x82, 0x74));
		g.setFont(g.getFont().deriveFont(12f));
		g.drawString("pocketge.com — free, no login", 32, SHARE_CARD_H - 18);
		g.dispose();
		return img;
	}

	private static void copyImageToClipboard(BufferedImage img)
	{
		final Clipboard clipboard = Toolkit.getDefaultToolkit().getSystemClipboard();
		clipboard.setContents(new Transferable()
		{
			@Override
			public DataFlavor[] getTransferDataFlavors() { return new DataFlavor[]{DataFlavor.imageFlavor}; }

			@Override
			public boolean isDataFlavorSupported(DataFlavor flavor) { return DataFlavor.imageFlavor.equals(flavor); }

			@Override
			public Object getTransferData(DataFlavor flavor) throws UnsupportedFlavorException
			{
				if (!DataFlavor.imageFlavor.equals(flavor))
				{
					throw new UnsupportedFlavorException(flavor);
				}
				return img;
			}
		}, null);
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
	private static Icon buildNextIcon()
	{
		final int w = 7, h = 10;
		final BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(1.7f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		g.drawPolyline(new int[]{1, 5, 1}, new int[]{1, h / 2, h - 1}, 3);
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

	private static Icon buildShareIcon()
	{
		final int size = 13;
		final BufferedImage img = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setColor(GOLD);
		g.setStroke(new BasicStroke(1.3f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
		final int topX = 10, topY = 2, midX = 3, midY = 6, botX = 10, botY = 10;
		g.drawLine(midX, midY, topX, topY);
		g.drawLine(midX, midY, botX, botY);
		final int r = 2;
		g.fillOval(topX - r, topY - r, r * 2, r * 2);
		g.fillOval(midX - r, midY - r, r * 2, r * 2);
		g.fillOval(botX - r, botY - r, r * 2, r * 2);
		g.dispose();
		return new ImageIcon(img);
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


	private static Color accent(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return GOLD;
			case SELL: return SELL_COLOR;
			default: return ADJUST;
		}
	}

}
