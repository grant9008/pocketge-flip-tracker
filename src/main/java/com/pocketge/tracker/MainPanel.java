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
		void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v);
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
		/** See GeSlotsPanel.Actions.setSlotAdviceSkipped. */
		void setSlotAdviceSkipped(int slot, boolean skipped);
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
			@Override public void unblock(String itemName) { actions.unblock(itemName); }
			@Override public void toggleFavorite(int itemId, String name) { actions.toggleFavorite(itemId, name); }
			@Override public void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v) { actions.setAdjustInterval(v); }
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
		});
		advisorPanel.setBorder(BorderFactory.createEmptyBorder());

		favoritesPanel = new FavoritesPanel(itemManager, new FavoritesPanel.Actions()
		{
			@Override public void remove(int itemId) { actions.removeFavorite(itemId); }
			@Override public void reorder(int itemId, int delta) { actions.reorderFavorite(itemId, delta); }
			@Override public void reorderTo(int itemId, int newIndex) { actions.reorderFavoriteTo(itemId, newIndex); }
			@Override public void selectItem(FavoritesPanel.Row r) { advisorPanel.setSelectedItem(r); } // local UI state, no plugin round-trip needed
			@Override public void selectList(String listId) { actions.selectFavoriteList(listId); }
			@Override public void createList(String name) { actions.createFavoriteList(name); }
			@Override public void renameList(String listId, String name) { actions.renameFavoriteList(listId, name); }
			@Override public void recolorList(String listId, String color) { actions.recolorFavoriteList(listId, color); }
			@Override public void deleteList(String listId) { actions.deleteFavoriteList(listId); }
			@Override public void searchItems(String query, java.util.function.Consumer<List<FavoritesPanel.SearchResult>> callback) { actions.searchItems(query, callback); }
			@Override public void addFavorite(int itemId, String name) { actions.addFavorite(itemId, name); }
			@Override public void setSlotAdviceSkipped(int slot, boolean skipped) { actions.setSlotAdviceSkipped(slot, skipped); }
		});

		historyPanel = new HistoryPanel();

		finderPanel = new FinderPanel(itemManager, new FinderPanel.Actions()
		{
			@Override public void addFavorite(int itemId, String name) { actions.addFavorite(itemId, name); }
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
		scrollContent.add(historyPanel);
		scrollContent.add(sectionDivider());
		scrollContent.add(statsHeader);
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
		JPanel wrap = new JPanel(new BorderLayout());
		wrap.setOpaque(false);
		wrap.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
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
		link.setFont(link.getFont().deriveFont(12f));
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://pocketge.com/");
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
		/* Share sits here rather than in the card's control row: it is the
		   least-pressed action and it was taking width from the most-pressed
		   ones. It shares whatever card is on screen, so one button covers
		   every card. */
		wrap.add(advisorPanel.shareButton());
		/* Pause is here for a different reason: it is a mode rather than an
		   action on the current item, and the card it used to sit on is the
		   very thing it stops from changing. */
		wrap.add(advisorPanel.pauseButton());
		wrap.add(toolButton("🌐", "Open pocketge.com", e -> LinkBrowser.browse("https://pocketge.com/")));
		wrap.add(redditButton());
		return wrap;
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
		favoritesPanel.setWebsiteLinked(linked);
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

	public void updateStats(FlipStats.Stats stats, PortfolioValuer.Result portfolio)
	{
		statsHeader.update(stats, portfolio);
	}

	public void updateSuggestions(List<Advisor.Suggestion> suggestions,
		Map<Integer, AnalystRating.Grade> ratings, java.util.Set<Integer> favoriteIds, AdvisorPanel.Settings settings)
	{
		advisorPanel.update(suggestions, ratings, favoriteIds, settings);
	}

	/** Swaps the advisor boxes for a "log in to the game" message — before
	 *  login there's no bank, inventory or offers, so they'd all sit empty. */
	public void setLoggedIn(boolean loggedIn)
	{
		advisorPanel.setLoggedIn(loggedIn);
	}

	/** The single ranked recommendation stream — sells out of your
	 *  bank/inventory and buys sized to your liquid cash, already merged. */
	public void updateRecommendations(List<AdvisorPanel.Rec> recs)
	{
		advisorPanel.setRecommendations(recs);
	}

	public void updateFavorites(List<FavoritesPanel.Row> rows)
	{
		favoritesPanel.update(rows);
		// The inspection card holds a Row captured at click time; hand it the
		// rebuilt list so it re-reads the same item's current numbers.
		advisorPanel.refreshSelectedFrom(rows);
	}

	public void updateFavoriteLists(List<FavoritesPanel.ListMeta> lists, String activeListId)
	{
		favoritesPanel.updateLists(lists, activeListId);
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
