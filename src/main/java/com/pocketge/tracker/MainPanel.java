package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.LinkBrowser;

/**
 * The single unified sidebar panel: stats header (profit / ROI / hourly
 * rate / portfolio value + time-range dropdown), flip advisor suggestions
 * with Analyst Rating badges, a Favorites watchlist, and paginated flip
 * history — one tab instead of the old Tracker + Advisor split, matching
 * how Flipping Copilot keeps everything in one place.
 */
public class MainPanel extends PluginPanel
{
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);

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
		void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v);
		void setRiskLevel(PocketGeTrackerConfig.RiskLevel v);
		void setAdvisorEnabled(boolean on);
		void setLocalBridge(boolean on);
		void setBridgePort(int port);
		void setMaxFlips(int n);
		void fillGePrice(long price);
	}

	private final StatsHeaderPanel statsHeader;
	private final AdvisorPanel advisorPanel;
	private final FavoritesPanel favoritesPanel;
	private final HistoryPanel historyPanel;

	public MainPanel(ItemManager itemManager, Actions actions)
	{
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
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
			@Override public void setRiskLevel(PocketGeTrackerConfig.RiskLevel v) { actions.setRiskLevel(v); }
			@Override public void setAdvisorEnabled(boolean on) { actions.setAdvisorEnabled(on); }
			@Override public void setLocalBridge(boolean on) { actions.setLocalBridge(on); }
			@Override public void setBridgePort(int port) { actions.setBridgePort(port); }
			@Override public void setMaxFlips(int n) { actions.setMaxFlips(n); }
			@Override public void fillGePrice(long price) { actions.fillGePrice(price); }
		});
		advisorPanel.setBorder(BorderFactory.createEmptyBorder());

		favoritesPanel = new FavoritesPanel(itemManager, new FavoritesPanel.Actions()
		{
			@Override public void remove(int itemId) { actions.removeFavorite(itemId); }
			@Override public void reorder(int itemId, int delta) { actions.reorderFavorite(itemId, delta); }
		});

		historyPanel = new HistoryPanel(itemManager, new HistoryPanel.Actions()
		{
			@Override public void toggleFavorite(int itemId, String name) { actions.toggleFavorite(itemId, name); }
		});

		JPanel scrollContent = new JPanel();
		scrollContent.setLayout(new BoxLayout(scrollContent, BoxLayout.Y_AXIS));
		scrollContent.setOpaque(false);
		scrollContent.add(advisorPanel);
		scrollContent.add(sectionDivider());
		scrollContent.add(favoritesPanel);
		scrollContent.add(sectionDivider());
		scrollContent.add(moreSuggestionsSection(advisorPanel.getSuggestionCards()));
		scrollContent.add(sectionDivider());
		scrollContent.add(historyPanel);
		scrollContent.add(sectionDivider());
		scrollContent.add(statsHeader);
		scrollContent.add(Box.createVerticalStrut(6));
		scrollContent.add(openSiteLink());

		JScrollPane scroll = new JScrollPane(scrollContent);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.getVerticalScrollBar().setUnitIncrement(16);
		add(scroll, BorderLayout.CENTER);
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

	/** Wraps the advisor's "everything else" suggestion cards with a title,
	 *  same treatment as Favorites/History, so it doesn't read as an orphan
	 *  list once it's no longer directly under the Recommended Flip card. */
	private JPanel moreSuggestionsSection(JPanel cards)
	{
		JPanel wrap = new JPanel(new BorderLayout(0, 6));
		wrap.setOpaque(false);
		JLabel title = new JLabel("More Suggestions");
		title.setForeground(GOLD);
		wrap.add(title, BorderLayout.NORTH);
		wrap.add(cards, BorderLayout.CENTER);
		return wrap;
	}

	private JLabel openSiteLink()
	{
		JLabel link = new JLabel("Open PocketGE ↗", SwingConstants.CENTER);
		link.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		link.setFont(link.getFont().deriveFont(11f));
		link.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
		link.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		link.setAlignmentX(0.5f);
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

	public void setAdvisorStatus(String s)
	{
		advisorPanel.setStatus(s);
	}

	/** Whatever item is currently in an open GE offer screen, if any — null
	 *  itemId clears it. Takes priority over the advisor's own top pick in
	 *  the Recommended Flip slot since it's what the player is doing right
	 *  now. */
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

	public void updateFavorites(List<FavoritesPanel.Row> rows)
	{
		favoritesPanel.update(rows);
	}

	/** Stops the Favorites panel's 5-day-extreme glow Timers — call on
	 *  plugin shutDown() so they don't keep ticking after the panel is gone. */
	public void stopFavoritesGlow()
	{
		favoritesPanel.stopPulseTimers();
	}

	public void updateHistory(List<Flip> flips, java.util.Set<Integer> favoriteIds)
	{
		historyPanel.update(flips, favoriteIds);
	}
}
