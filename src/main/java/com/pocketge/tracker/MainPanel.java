package com.pocketge.tracker;

import java.awt.AWTEvent;
import java.awt.BorderLayout;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Toolkit;
import java.awt.event.AWTEventListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseWheelEvent;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The single unified sidebar panel: stats header (profit / ROI / hourly
 * rate / portfolio value + time-range dropdown), flip advisor suggestions
 * with Analyst Rating badges, a Favorites watchlist, and a link out to full
 * flip history on the website — one tab instead of the old Tracker +
 * Advisor split, matching how Flipping Copilot keeps everything in one
 * place.
 */
public class MainPanel extends PluginPanel
{
	/** Everything the panel can trigger, aggregated so the plugin only
	 *  implements one interface instead of three. */
	public interface Actions
	{
		void onRangeChanged(FlipStats.Range range);
		void onResetSession();
		void skip(int itemId);
		void block(String itemName);
		/** See AdvisorPanel.Actions.setConfirmBlock. */
		void setConfirmBlock(boolean on);
		void unblock(String itemName);
		void toggleFavorite(int itemId, String name);
		void removeFavorite(int itemId);
		void reorderFavorite(int itemId, int delta);
		void reorderFavoriteTo(int itemId, int newIndex);
		void selectFavoriteList(String listId);
		void createFavoriteList(String name);
		void renameFavoriteList(String listId, String name);
		void recolorFavoriteList(String listId, String color);
		void deleteFavoriteList(String listId);
		void searchItems(String query, java.util.function.Consumer<List<FavoritesPanel.SearchResult>> callback);
		void addFavorite(int itemId, String name);
		/** See FinderPanel.Actions.addFavoriteToList. */
		void addFavoriteToList(String listId, int itemId, String name);
		/** See FinderPanel.Actions.inspectItem. */
		void inspectItem(int itemId, String name);
		void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v);
		/** See AdvisorPanel.Actions.setMinProfit. */
		void setMinProfit(PocketGeTrackerConfig.MinProfit v);
		void setAdvisorEnabled(boolean on);
		void setLocalBridge(boolean on);
		void setBridgePort(int port);
		void setMaxFlips(int n);
		void fillGePrice(long price);
		void fillGeQuantity(long qty);
		/** See AdvisorPanel.Actions.openChart. */
		void openChart(String itemName);
		/** See AdvisorPanel.Actions.openChartInNewTab. */
		void openChartInNewTab(String itemName);
		/** See AdvisorPanel.Actions.refreshSuggestions. */
		void refreshSuggestions();
		/** See AdvisorPanel.Actions.onSelectedItemChanged. */
		void onSelectedItemChanged(Integer itemId);
		/** See AdvisorPanel.Actions.onRecommendationShown. */
		void onRecommendationShown(Integer itemId, String name, boolean sell);
		/** See GeSlotsPanel.Actions.setSlotAdviceSkipped. */
		void setSlotAdviceSkipped(int slot, boolean skipped);
		/** See FavoritesPanel.Actions.setWatchlistRows. */
		void setWatchlistRows(int rows);
	}

	private final StatsHeaderPanel statsHeader;
	private final AdvisorPanel advisorPanel;
	private final FavoritesPanel favoritesPanel;
	private final HistoryPanel historyPanel;
	private final FinderPanel finderPanel;
	private final JScrollPane scroll;
	/** Wheel events only land on the deepest component under the cursor and
	 *  don't reliably bubble up through everything nested in here (rows,
	 *  buttons, labels) to reach the JScrollPane's own listener — so instead
	 *  of hoping every descendant forwards them, this catches wheel events
	 *  anywhere over the panel and scrolls the one JScrollPane directly. */
	private final AWTEventListener wheelForwarder;
	/** Held because topBar() is an instance method and the link button has to
	 *  reach setLocalBridge; the constructor's parameter is out of scope by
	 *  then. */
	private final Actions actions;
	/** The link button and the background it wears when nothing is polling.
	 *  Built once with the top bar, so it carries its own state — same reason
	 *  as AdvisorPanel's pause button. */
	private JButton linkBtn;
	private java.awt.Color linkIdleBackground;
	private boolean websiteLinked;
	/** The site's own .rl-dot.on green, matching FavoritesPanel's LINKED
	 *  badge so the two say "connected" in the same colour. */
	private static final java.awt.Color LINKED_GREEN = new java.awt.Color(0x1F, 0xB8, 0x5C);

	public MainPanel(ItemManager itemManager, Actions actions)
	{
		/* Do NOT let PluginPanel wrap this panel in a scroll pane of its own.
		 *
		 * Its no-arg constructor puts `this` at BorderLayout.NORTH of a holder
		 * inside a JScrollPane it owns. NORTH hands a child its full PREFERRED
		 * height, so this panel was never height-constrained — which meant the
		 * scroll pane built below, sized to its view's preferred height, had a
		 * scroll range of exactly zero. RuneLite's outer bar was doing all the
		 * scrolling; ours was inert scenery.
		 *
		 * That is what broke the mouse wheel. forwardWheelEvent() moves OUR
		 * bar and consumes the event, so anywhere over the panel the wheel
		 * nudged a bar with nowhere to go and swallowed the event before the
		 * outer pane could act on it. Over RuneLite's own scrollbar the source
		 * is not a descendant of this panel, the forwarder bailed out, and
		 * native scrolling worked — which is exactly the "only works directly
		 * on the bar" symptom.
		 *
		 * It also un-pinned the top bar. Being inside the outer pane, the whole
		 * panel scrolled as one block, so the strip that is supposed to stay
		 * put scrolled away with the content.
		 *
		 * Unwrapped, BorderLayout gives NORTH to the top bar and CENTER to our
		 * scroll pane, which now gets a bounded height, a real range, and a
		 * genuinely fixed strip above it. Safe to pass false: PluginPanel only
		 * touches its scrollPane field inside the wrap branch, and the border,
		 * layout and background it would have set are all set below anyway. */
		super(false);
		this.actions = actions;
		setLayout(new BorderLayout());
		// Asymmetric on purpose: the favorites list has gotten long enough
		// that the vertical scrollbar is now on-screen most of the time, and
		// it eats into the same width this border used to assume was fully
		// available — content that fit fine with no scrollbar was getting
		// squeezed tighter on the right than the left the moment one
		// appeared. Trim right down to leave it room, and left down a touch
		// too so the whole panel reads as shifted left rather than just
		// lopsided.
		setBorder(BorderFactory.createEmptyBorder(10, 6, 10, 2));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		statsHeader = new StatsHeaderPanel(new StatsHeaderPanel.Actions()
		{
			@Override public void onRangeChanged(FlipStats.Range range) { actions.onRangeChanged(range); }
			@Override public void onResetSession() { actions.onResetSession(); }
		});

		advisorPanel = new AdvisorPanel(itemManager, new AdvisorPanel.Actions()
		{
			@Override public void skip(int itemId) { actions.skip(itemId); }
			@Override public void block(String itemName) { actions.block(itemName); }
			@Override public void setConfirmBlock(boolean on) { actions.setConfirmBlock(on); }
			@Override public void unblock(String itemName) { actions.unblock(itemName); }
			@Override public void toggleFavorite(int itemId, String name) { actions.toggleFavorite(itemId, name); }
			@Override public void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v) { actions.setAdjustInterval(v); }
			@Override public void setMinProfit(PocketGeTrackerConfig.MinProfit v) { actions.setMinProfit(v); }
			@Override public void setAdvisorEnabled(boolean on) { actions.setAdvisorEnabled(on); }
			@Override public void setLocalBridge(boolean on) { actions.setLocalBridge(on); }
			@Override public void setBridgePort(int port) { actions.setBridgePort(port); }
			@Override public void setMaxFlips(int n) { actions.setMaxFlips(n); }
			@Override public void fillGePrice(long price) { actions.fillGePrice(price); }
			@Override public void fillGeQuantity(long qty) { actions.fillGeQuantity(qty); }
			@Override public void openChart(String itemName) { actions.openChart(itemName); }
			@Override public void openChartInNewTab(String itemName) { actions.openChartInNewTab(itemName); }
			@Override public void refreshSuggestions() { actions.refreshSuggestions(); }
			@Override public void onSelectedItemChanged(Integer itemId) { actions.onSelectedItemChanged(itemId); }
			@Override public void onRecommendationShown(Integer itemId, String name, boolean sell) { actions.onRecommendationShown(itemId, name, sell); }
		});
		advisorPanel.setBorder(BorderFactory.createEmptyBorder());

		favoritesPanel = new FavoritesPanel(itemManager, new FavoritesPanel.Actions()
		{
			@Override public void remove(int itemId) { actions.removeFavorite(itemId); }
			@Override public void reorder(int itemId, int delta) { actions.reorderFavorite(itemId, delta); }
			@Override public void reorderTo(int itemId, int newIndex) { actions.reorderFavoriteTo(itemId, newIndex); }
			@Override public void selectItem(FavoritesPanel.Row r) { advisorPanel.setSelectedItem(r); } // local UI state, no plugin round-trip needed
			@Override public void openChart(String itemName) { actions.openChart(itemName); }
			@Override public void selectList(String listId) { actions.selectFavoriteList(listId); }
			@Override public void createList(String name) { actions.createFavoriteList(name); }
			@Override public void renameList(String listId, String name) { actions.renameFavoriteList(listId, name); }
			@Override public void recolorList(String listId, String color) { actions.recolorFavoriteList(listId, color); }
			@Override public void deleteList(String listId) { actions.deleteFavoriteList(listId); }
			@Override public void searchItems(String query, java.util.function.Consumer<List<FavoritesPanel.SearchResult>> callback) { actions.searchItems(query, callback); }
			@Override public void addFavorite(int itemId, String name) { actions.addFavorite(itemId, name); }
			@Override public void setSlotAdviceSkipped(int slot, boolean skipped) { actions.setSlotAdviceSkipped(slot, skipped); }
			@Override public void inspectItem(int itemId, String name) { actions.inspectItem(itemId, name); }
			@Override public void setWatchlistRows(int n) { actions.setWatchlistRows(n); }
		});

		historyPanel = new HistoryPanel(actions::openChart);

		finderPanel = new FinderPanel(itemManager, new FinderPanel.Actions()
		{
			@Override public void addFavorite(int itemId, String name) { actions.addFavorite(itemId, name); }
			@Override public void addFavoriteToList(String listId, int itemId, String name) { actions.addFavoriteToList(listId, itemId, name); }
			@Override public void inspectItem(int itemId, String name) { actions.inspectItem(itemId, name); }
			@Override public void openChart(String itemName) { actions.openChart(itemName); }
		});

		add(topBar(), BorderLayout.NORTH);

		JPanel scrollContent = new JPanel();
		scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
		scrollContent.setOpaque(false);
		scrollContent.add(advisorPanel);
		scrollContent.add(sectionDivider());
		scrollContent.add(favoritesPanel);
		scrollContent.add(finderPanel);
		scrollContent.add(sectionDivider());
		/* Stats, then the flips that make them up — the totals first and the
		   working underneath, which is the order you read them in. The other
		   way round, the recent-flip rows sat between the watchlist and the
		   profit figure they belong to, so the one number the panel is about
		   arrived after a list of its own components. */
		scrollContent.add(statsHeader);
		/* Smaller than a section break, because these two belong together —
		   see sectionDivider(int, int). HistoryPanel carries 8px of its own
		   top padding, so nothing is needed underneath. */
		scrollContent.add(sectionDivider(8, 0));
		scrollContent.add(historyPanel);
		scrollContent.add(Box.createVerticalStrut(6));
		scrollContent.add(bottomBar());

		/* Same alignmentX trap as inside AdvisorPanel, one level up: panels
		   whose layout is a BoxLayout report a DERIVED alignment (0.00 here,
		   from their left-aligned children) while plain JPanels report 0.50.
		   Mixed, BoxLayout offsets the odd ones out — measured, it threw the
		   bank line to x=16335, which is the blank band that was showing above
		   the first section. State one alignment for every child. */
		for (java.awt.Component c : scrollContent.getComponents())
		{
			if (c instanceof javax.swing.JComponent)
			{
				((javax.swing.JComponent) c).setAlignmentX(java.awt.Component.LEFT_ALIGNMENT);
			}
		}

		/* North-anchored for the same reason AdvisorPanel's own column is:
		   a JScrollPane stretches its view to the viewport height when the
		   content is shorter, and scrollContent's BoxLayout then spreads
		   that spare height across every section instead of leaving it at
		   the bottom. */
		/* Track the viewport's width instead of reporting the content's own
		   preferred width. A JViewport sizes a non-Scrollable view to
		   max(viewport, preferred), so any single child that wants more than
		   the ~225px sidebar silently widens the whole column and pushes
		   everything else off the right edge. Clamping here means an
		   over-wide label ellipsizes in place instead. */
		JPanel scrollHolder = new JPanel(new BorderLayout())
		{
			@Override
			public java.awt.Dimension getPreferredSize()
			{
				final java.awt.Dimension d = super.getPreferredSize();
				final java.awt.Container parent = getParent();
				return new java.awt.Dimension(parent != null && parent.getWidth() > 0 ? parent.getWidth() : d.width, d.height);
			}
		};
		scrollHolder.setOpaque(false);
		scrollHolder.add(scrollContent, BorderLayout.NORTH);
		scroll = new JScrollPane(scrollHolder);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);

		wheelForwarder = this::forwardWheelEvent;
		Toolkit.getDefaultToolkit().addAWTEventListener(wheelForwarder, AWTEvent.MOUSE_WHEEL_EVENT_MASK);
	}

	/** Redirects any mouse-wheel event landing somewhere inside this panel to
	 *  the sidebar's own scrollbar, regardless of which child component the
	 *  cursor happens to be over. Leaves events over the scrollbar itself
	 *  (and anything outside this panel entirely, e.g. other plugin panels)
	 *  untouched. */
	private void forwardWheelEvent(AWTEvent event)
	{
		if (!(event instanceof MouseWheelEvent) || !(event.getSource() instanceof Component))
		{
			return;
		}
		final MouseWheelEvent wheel = (MouseWheelEvent) event;
		final Component source = (Component) event.getSource();
		final JScrollBar bar = scroll.getVerticalScrollBar();
		if (!SwingUtilities.isDescendingFrom(source, this) || SwingUtilities.isDescendingFrom(source, bar))
		{
			return;
		}
		/* Never consume an event this bar cannot act on. When the content fits,
		   or if the panel ever ends up inside someone else's scroll pane again,
		   swallowing the wheel here would break scrolling rather than provide
		   it — which is precisely the bug super(false) above just fixed, and
		   this is the cheap guard that keeps it from coming back silently. */
		if (bar.getVisibleAmount() >= bar.getMaximum() - bar.getMinimum())
		{
			return;
		}
		bar.setValue(bar.getValue() + wheel.getUnitsToScroll() * bar.getUnitIncrement());
		wheel.consume();
	}

	/** Call on plugin shutDown() so this global listener doesn't leak past
	 *  the panel's lifetime. */
	public void dispose()
	{
		Toolkit.getDefaultToolkit().removeAWTEventListener(wheelForwarder);
	}

	private JPanel sectionDivider()
	{
		return sectionDivider(6, 6);
	}

	/**
	 * The same hairline with its own breathing room, for a break that is
	 * real but smaller than the ones between whole sections.
	 *
	 * Stats and the flip list are one idea in two halves — the totals, then
	 * the trades they are made of — so they are not separated by a full
	 * section break. They were separated by nothing at all, which ran
	 * "Session time 0:02:59" straight into "12 flips recorded" and made the
	 * header of the second look like one more stat row of the first.
	 */
	private JPanel sectionDivider(int above, int below)
	{
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(above, 0, below, 0));
		JPanel line = new JPanel();
		line.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		line.setPreferredSize(new java.awt.Dimension(0, 1));
		wrap.add(line, BorderLayout.CENTER);
		return wrap;
	}

	private JLabel openSiteLink()
	{
		JLabel link = new JLabel("Open PocketGE ↗", SwingConstants.CENTER);
		link.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		/* 11f, matching "Flip history" — both are ways out of the panel, and
		   at the stat rows' 12f they read as content rather than as exits. */
		link.setFont(link.getFont().deriveFont(11f));
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse(PocketGeLinks.home("bottom_link"));
			}
		});
		return link;
	}

	/** A fixed icon strip pinned above the scroll area — settings, share,
	 *  the site, and the flipping subreddits. Outside the JScrollPane on
	 *  purpose: these are always-available actions, and having them scroll
	 *  away with the content (or sit at the very bottom, as the gear used
	 *  to) meant reaching for them was a scroll every time. */
	private JPanel topBar()
	{
		JPanel wrap = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 2));
		wrap.add(advisorPanel.settingsButton());
		/* Pause is here for a different reason: it is a mode rather than an
		   action on the current item, and the card it used to sit on is the
		   very thing it stops from changing. */
		wrap.add(advisorPanel.pauseButton());
		/* Your own ledger, one press from anywhere in the panel. It had a
		   single way in — a small "Flip history ↗" link at the end of the
		   recent-flips list, which you only meet if you have already scrolled
		   past everything else. The page is the best thing the website does
		   with the plugin's data and almost nobody was finding it. */
		wrap.add(toolButton("🧾", "Your full flip history on pocketge.com",
			e -> LinkBrowser.browse(PocketGeLinks.flips("toolbar"))));
		wrap.add(linkButton());
		wrap.add(toolButton("🌐", "Open pocketge.com", e -> LinkBrowser.browse(PocketGeLinks.home("toolbar"))));
		wrap.add(redditButton());
		return wrap;
	}

	/**
	 * One press to connect the website to this plugin.
	 *
	 * Everything the site can show about YOUR trades — the flip history page,
	 * the live panel, your watchlists — needs the local bridge on, and the
	 * only switch for it was buried in the gear popup under a heading about
	 * ports. So the page people were sent to could not read anything, said so
	 * politely, and that was the end of it.
	 *
	 * This turns the bridge on and opens the site in one go. It goes green
	 * once a tab is actually polling, which is the only honest confirmation:
	 * the setting being on says the plugin is listening, not that anything is
	 * listening back.
	 *
	 * Deliberately not called "link your account". There is no account, the
	 * site says so in as many words on the page this opens, and borrowing the
	 * vocabulary of one would undercut the thing that makes it worth using.
	 */
	private JButton linkButton()
	{
		linkBtn = toolButton("🔗", "", e ->
		{
			actions.setLocalBridge(true);
			LinkBrowser.browse(PocketGeLinks.home("link_button"));
		});
		linkIdleBackground = linkBtn.getBackground();
		syncLinkButton();
		return linkBtn;
	}

	private void syncLinkButton()
	{
		if (linkBtn == null)
		{
			return;
		}
		linkBtn.setBackground(websiteLinked ? LINKED_GREEN : linkIdleBackground);
		linkBtn.setToolTipText(websiteLinked
			? "<html>Linked — a pocketge.com tab on this computer is reading the plugin."
				+ "<br>Click to open the site.</html>"
			: "<html>Link pocketge.com to this plugin.<br>Switches on the local bridge (127.0.0.1 only,"
				+ "<br>nothing leaves this machine) and opens the site.</html>");
	}

	/** Two subreddits, one button — a popup rather than two more icons,
	 *  since the strip is competing for a narrow sidebar's width. */
	private JButton redditButton()
	{
		final JButton b = toolButton("r/", "OSRS flipping subreddits", null);
		b.addActionListener(e ->
		{
			JPopupMenu menu = new JPopupMenu();
			JMenuItem bets = new JMenuItem("r/GrandExchangeBets");
			bets.addActionListener(a -> LinkBrowser.browse("https://www.reddit.com/r/GrandExchangeBets/"));
			menu.add(bets);
			JMenuItem flip = new JMenuItem("r/osrsflipping");
			flip.addActionListener(a -> LinkBrowser.browse("https://www.reddit.com/r/osrsflipping/"));
			menu.add(flip);
			menu.show(b, 0, b.getHeight() + 2);
		});
		return b;
	}

	private JButton toolButton(String label, String tip, java.awt.event.ActionListener a)
	{
		JButton b = new JButton(label);
		b.setToolTipText(tip);
		b.setFocusPainted(false);
		b.setFont(b.getFont().deriveFont(Font.BOLD, 12f));
		b.setMargin(new java.awt.Insets(2, 6, 2, 6));
		b.setPreferredSize(new java.awt.Dimension(30, 22));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		if (a != null)
		{
			b.addActionListener(a);
		}
		return b;
	}

	/** Very bottom of the sidebar: just the website link now that the gear
	 *  and the rest of the shortcuts live in the pinned top bar. */
	private JPanel bottomBar()
	{
		JPanel wrap = new JPanel(new BorderLayout(6, 0));
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		wrap.add(openSiteLink(), BorderLayout.CENTER);
		return wrap;
	}

	public void setAdvisorStatus(String s)
	{
		advisorPanel.setStatus(s);
	}

	/** The 8-square GE offer-slot status strip, above the Favorites search
	 *  box. See GeSlotsPanel for what each color means. */
	public void updateGeSlots(GeSlotsPanel.SlotInfo[] slots)
	{
		favoritesPanel.updateGeSlots(slots);
	}

	/** Mirrors the website's own "LINKED" chip onto the Favorites header, so
	 *  the link is visible from the side you are actually looking at while
	 *  you play. Same signal the settings popup reports. */
	public void setWebsiteLinked(boolean linked)
	{
		websiteLinked = linked;
		syncLinkButton();
		favoritesPanel.setWebsiteLinked(linked);
	}

	/** See FavoritesPanel.setWatchlistRows — the remembered height of the
	 *  watchlist, restored at startup. */
	public void setWatchlistRows(int rows)
	{
		favoritesPanel.setWatchlistRows(rows);
	}

	/** See FavoritesPanel.setBadgesEnabled — the watchlist chips and glow. */
	public void setBadgesEnabled(boolean enabled)
	{
		favoritesPanel.setBadgesEnabled(enabled);
	}

	/** The plugin-side Find Opportunities section — see FinderEngine for
	 *  what each list is and why Reliable 14D Margins isn't among them. */
	public void updateFinder(List<FinderPanel.Row> highVol, List<FinderPanel.Row> lowVol, List<FinderPanel.Row> losers,
		List<FinderPanel.Row> at5dHigh, List<FinderPanel.Row> at5dLow)
	{
		finderPanel.update(highVol, lowVol, losers, at5dHigh, at5dLow);
	}

	/** Whatever item is currently in an open GE offer screen, if any — null
	 *  itemId clears it. Shown above the advisor's own Top Suggestion card
	 *  since it's what the player is doing right now. */
	public void setGeContext(Integer itemId, String name, boolean isBuy, long price)
	{
		advisorPanel.setGeContext(itemId, name, isBuy, price);
	}

	public void setSelectedRangeQuietly(FlipStats.Range range)
	{
		statsHeader.setSelectedRangeQuietly(range);
	}

	public void updateStats(FlipStats.Stats stats, PortfolioValuer.Result portfolio, long sessionStartMillis)
	{
		statsHeader.update(stats, portfolio, sessionStartMillis);
	}

	public void updateSuggestions(List<Advisor.Suggestion> suggestions,
		java.util.Set<Integer> favoriteIds, AdvisorPanel.Settings settings)
	{
		advisorPanel.update(suggestions, favoriteIds, settings);
	}

	/** Swaps the advisor boxes for a "log in to the game" message — before
	 *  login there's no bank, inventory or offers, so they'd all sit empty. */
	public void setLoggedIn(boolean loggedIn)
	{
		advisorPanel.setLoggedIn(loggedIn);
		favoritesPanel.setLoggedIn(loggedIn);
		statsHeader.setLoggedIn(loggedIn);
		finderPanel.setLoggedIn(loggedIn);
		historyPanel.setLoggedIn(loggedIn);
	}

	/** The single ranked recommendation stream — sells out of your
	 *  bank/inventory and buys sized to your liquid cash, already merged. */
	public void updateRecommendations(List<AdvisorPanel.Rec> recs)
	{
		advisorPanel.setRecommendations(recs);
	}

	/**
	 * @param rows       what the watchlist shows — favourites only
	 * @param selectable the wider set the inspection card may be pointed at,
	 *                   which also covers a Find Opportunities pick that is
	 *                   not a favourite
	 */
	public void updateFavorites(List<FavoritesPanel.Row> rows, List<FavoritesPanel.Row> selectable)
	{
		favoritesPanel.update(rows);
		// The inspection card holds a Row captured at click time; hand it the
		// rebuilt list so it re-reads the same item's current numbers.
		advisorPanel.refreshSelectedFrom(selectable != null ? selectable : rows);
	}

	/** Show an arbitrary item in the inspection card — the finder's rows are
	 *  not favourites, so they have no Row until the plugin builds one. */
	public void showInspected(FavoritesPanel.Row row)
	{
		advisorPanel.setSelectedItem(row);
	}

	public void updateFavoriteLists(List<FavoritesPanel.ListMeta> lists, String activeListId)
	{
		favoritesPanel.updateLists(lists, activeListId);
		finderPanel.updateLists(lists, activeListId);
	}

	/** Stops the Favorites panel's 5-day-extreme glow Timers — call on
	 *  plugin shutDown() so they don't keep ticking after the panel is gone. */
	public void stopFavoritesGlow()
	{
		favoritesPanel.stopPulseTimers();
	}

	public void updateHistory(List<Flip> flips)
	{
		historyPanel.update(flips);
	}
}
