package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * Advisor UI: in-panel Risk Level / Re-check-interval controls (Flipping
 * Copilot-style segmented buttons), a prominent "Recommended Flip" card
 * that cycles through the best buy candidates (mirroring the site's
 * flip-finder card), the rest of the suggestions (adjust / sell) as a
 * stack of cards with Skip / Block / Favorite, and an editable
 * never-recommend list of chips at the bottom.
 */
public class AdvisorPanel extends PluginPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	private static final Color TEAL = new Color(0x26, 0xA6, 0x9A);
	private static final Color ADJUST = new Color(0xFF, 0x9F, 0x43);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	private static final int ICON_SIZE = 32;

	public interface Actions
	{
		void skip(int itemId);
		void block(String itemName);
		void unblock(String itemName);
		void toggleFavorite(int itemId, String name);
		void setAdjustInterval(PocketGeTrackerConfig.AdjustInterval v);
		void setRiskLevel(PocketGeTrackerConfig.RiskLevel v);
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JLabel status = new JLabel("Advisor off", SwingConstants.LEFT);
	private final JPanel recommendedWrap = new JPanel(new BorderLayout());
	private final JPanel cards = new JPanel();
	private final JPanel blockChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
	private final Map<PocketGeTrackerConfig.AdjustInterval, JButton> intervalButtons = new EnumMap<>(PocketGeTrackerConfig.AdjustInterval.class);
	private final Map<PocketGeTrackerConfig.RiskLevel, JButton> riskButtons = new EnumMap<>(PocketGeTrackerConfig.RiskLevel.class);

	private List<Advisor.Suggestion> currentBuys = List.of();
	private Map<Integer, AnalystRating.Grade> currentRatings = Map.of();
	private int recommendedIndex = 0;
	private Set<Integer> favoriteIds = Set.of();

	public AdvisorPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel();
		north.setLayout(new BoxLayout(north, BoxLayout.Y_AXIS));
		north.setOpaque(false);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setAlignmentX(0f);
		status.setFont(status.getFont().deriveFont(10.5f));
		north.add(status);
		north.add(Box.createVerticalStrut(8));
		north.add(controlRow("Re-check every", intervalRow()));
		north.add(Box.createVerticalStrut(4));
		north.add(controlRow("Risk level", riskRow()));
		add(north, BorderLayout.NORTH);

		recommendedWrap.setOpaque(false);
		recommendedWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setOpaque(false);
		cards.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel south = new JPanel(new BorderLayout(0, 4));
		south.setOpaque(false);
		JLabel blkTitle = new JLabel("Never recommend");
		blkTitle.setForeground(GOLD);
		blkTitle.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
		south.add(blkTitle, BorderLayout.NORTH);
		blockChips.setOpaque(false);
		south.add(blockChips, BorderLayout.CENTER);

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		center.add(recommendedWrap);
		center.add(cards);
		center.add(south);
		add(center, BorderLayout.CENTER);
	}

	private JPanel controlRow(String label, JPanel buttonRow)
	{
		JPanel wrap = new JPanel(new BorderLayout(0, 3));
		wrap.setOpaque(false);
		JLabel lbl = new JLabel(label);
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lbl.setFont(lbl.getFont().deriveFont(10f));
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
			b.addActionListener(e -> actions.setAdjustInterval(v));
			intervalButtons.put(v, b);
			row.add(b);
		}
		return row;
	}

	private JPanel riskRow()
	{
		JPanel row = new JPanel(new GridLayout(1, 0, 3, 0));
		row.setOpaque(false);
		for (PocketGeTrackerConfig.RiskLevel v : PocketGeTrackerConfig.RiskLevel.values())
		{
			JButton b = segmentButton(riskLabel(v));
			b.addActionListener(e -> actions.setRiskLevel(v));
			riskButtons.put(v, b);
			row.add(b);
		}
		return row;
	}

	private static String riskLabel(PocketGeTrackerConfig.RiskLevel r)
	{
		switch (r)
		{
			case LOW: return "Low";
			case HIGH: return "High";
			default: return "Med";
		}
	}

	private JButton segmentButton(String label)
	{
		JButton b = new JButton(label);
		b.setFocusPainted(false);
		b.setFont(b.getFont().deriveFont(10f));
		b.setMargin(new Insets(3, 4, 3, 4));
		b.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return b;
	}

	private void highlightControls(PocketGeTrackerConfig.AdjustInterval currentInterval, PocketGeTrackerConfig.RiskLevel currentRisk)
	{
		for (Map.Entry<PocketGeTrackerConfig.AdjustInterval, JButton> e : intervalButtons.entrySet())
		{
			setActive(e.getValue(), e.getKey() == currentInterval);
		}
		for (Map.Entry<PocketGeTrackerConfig.RiskLevel, JButton> e : riskButtons.entrySet())
		{
			setActive(e.getValue(), e.getKey() == currentRisk);
		}
	}

	private void setActive(JButton b, boolean active)
	{
		b.setBackground(active ? GOLD : ColorScheme.DARKER_GRAY_COLOR);
		b.setForeground(active ? Color.BLACK : ColorScheme.LIGHT_GRAY_COLOR);
	}

	public void setStatus(String s)
	{
		status.setText(s);
	}

	/** Rebuild everything. Call on the EDT.
	 *  {@code ratings} is itemId -> Analyst Rating grade; missing entries
	 *  just render without a badge. {@code favoriteIds} decides whether a
	 *  card's star renders filled or hollow. {@code currentInterval} /
	 *  {@code currentRisk} highlight the active segmented button. */
	public void update(List<Advisor.Suggestion> suggestions, List<String> blocked,
		Map<Integer, AnalystRating.Grade> ratings, Set<Integer> favoriteIds,
		PocketGeTrackerConfig.AdjustInterval currentInterval, PocketGeTrackerConfig.RiskLevel currentRisk)
	{
		this.favoriteIds = favoriteIds != null ? favoriteIds : Set.of();
		this.currentRatings = ratings != null ? ratings : Map.of();
		highlightControls(currentInterval, currentRisk);

		List<Advisor.Suggestion> buys = new ArrayList<>();
		List<Advisor.Suggestion> others = new ArrayList<>();
		if (suggestions != null)
		{
			for (Advisor.Suggestion s : suggestions)
			{
				(s.type == Advisor.Suggestion.Type.BUY ? buys : others).add(s);
			}
		}
		currentBuys = buys;
		if (recommendedIndex >= buys.size())
		{
			recommendedIndex = 0;
		}
		renderRecommended();

		cards.removeAll();
		for (Advisor.Suggestion s : others)
		{
			cards.add(card(s, currentRatings.get(s.itemId)));
			cards.add(Box.createVerticalStrut(6));
		}
		if (others.isEmpty() && buys.isEmpty())
		{
			JLabel none = new JLabel("<html><center>No suggestions right now.<br>Prices refresh on your chosen interval.</center></html>", SwingConstants.CENTER);
			none.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			none.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));
			cards.add(none);
		}

		blockChips.removeAll();
		if (blocked == null || blocked.isEmpty())
		{
			JLabel empty = new JLabel("Nothing blocked");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			blockChips.add(empty);
		}
		else
		{
			for (String name : blocked)
			{
				blockChips.add(chip(name));
			}
		}
		revalidate();
		repaint();
	}

	/** The single, prominent "best buy right now" card -- Copilot's headline
	 *  suggestion, matching the site's flip-finder treatment. A Next arrow
	 *  cycles through the rest of the buy candidates when there's more than
	 *  one. */
	private void renderRecommended()
	{
		recommendedWrap.removeAll();
		if (currentBuys.isEmpty())
		{
			JLabel empty = new JLabel("<html><center>No buy recommended right now.</center></html>", SwingConstants.CENTER);
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(empty.getFont().deriveFont(11f));
			recommendedWrap.add(empty, BorderLayout.CENTER);
			recommendedWrap.revalidate();
			recommendedWrap.repaint();
			return;
		}

		Advisor.Suggestion s = currentBuys.get(recommendedIndex);
		AnalystRating.Grade rating = currentRatings.get(s.itemId);

		JPanel p = new JPanel(new BorderLayout(8, 0));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, GOLD),
			BorderFactory.createEmptyBorder(8, 8, 8, 8)));

		JLabel titleLabel = new JLabel("⚡ RECOMMENDED FLIP");
		titleLabel.setForeground(GOLD);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 9.5f));

		JLabel icon = iconLabel(s.itemId);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		JPanel headRow = new JPanel(new BorderLayout(6, 0));
		headRow.setOpaque(false);
		JLabel name = new JLabel(s.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
		headRow.add(name, BorderLayout.WEST);
		if (rating != null)
		{
			headRow.add(ratingBadge(rating), BorderLayout.EAST);
		}
		text.add(titleLabel);
		text.add(headRow);

		JLabel priceLine = new JLabel(QuantityFormatter.quantityToStackSize(s.price) + " gp"
			+ (s.expectedProfit > 0 ? "   +" + QuantityFormatter.quantityToStackSize(s.expectedProfit) + " gp" : ""));
		priceLine.setForeground(s.expectedProfit > 0 ? POSITIVE : ColorScheme.LIGHT_GRAY_COLOR);
		priceLine.setFont(priceLine.getFont().deriveFont(Font.BOLD, 12f));
		text.add(priceLine);

		JLabel why = new JLabel("<html><body style='width:130px'>" + s.reason + "</html>");
		why.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		why.setFont(why.getFont().deriveFont(10f));
		text.add(why);

		JPanel left = new JPanel(new BorderLayout(8, 0));
		left.setOpaque(false);
		left.add(icon, BorderLayout.WEST);
		left.add(text, BorderLayout.CENTER);
		p.add(left, BorderLayout.CENTER);

		JPanel btns = new JPanel();
		btns.setLayout(new BoxLayout(btns, BoxLayout.Y_AXIS));
		btns.setOpaque(false);
		boolean fav = favoriteIds.contains(s.itemId);
		btns.add(smallBtn(fav ? "★" : "☆", fav ? "Remove " + s.name + " from favorites" : "Add " + s.name + " to favorites",
			e -> actions.toggleFavorite(s.itemId, s.name)));
		if (currentBuys.size() > 1)
		{
			btns.add(Box.createVerticalStrut(4));
			btns.add(smallBtn("↻ Next", "Show the next best buy", e ->
			{
				recommendedIndex = (recommendedIndex + 1) % currentBuys.size();
				renderRecommended();
			}));
		}
		p.add(btns, BorderLayout.EAST);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, s.name);
		recommendedWrap.add(p, BorderLayout.CENTER);
		recommendedWrap.revalidate();
		recommendedWrap.repaint();
	}

	private JPanel card(Advisor.Suggestion s, AnalystRating.Grade rating)
	{
		JPanel p = new JPanel(new BorderLayout(6, 2));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent(s.type)),
			BorderFactory.createEmptyBorder(6, 8, 6, 6)));
		p.setMaximumSize(new Dimension(Integer.MAX_VALUE, 110));

		JLabel icon = iconLabel(s.itemId);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);

		JPanel headRow = new JPanel(new BorderLayout(6, 0));
		headRow.setOpaque(false);
		JLabel head = new JLabel(verb(s.type) + " " + s.name);
		head.setForeground(Color.WHITE);
		head.setFont(head.getFont().deriveFont(Font.BOLD));
		headRow.add(head, BorderLayout.WEST);
		if (rating != null)
		{
			headRow.add(ratingBadge(rating), BorderLayout.EAST);
		}
		text.add(headRow);

		JLabel line = new JLabel(QuantityFormatter.quantityToStackSize(s.price) + " gp × "
			+ QuantityFormatter.quantityToStackSize(s.quantity)
			+ (s.expectedProfit > 0 ? "   +" + QuantityFormatter.quantityToStackSize(s.expectedProfit) + " gp" : ""));
		line.setForeground(s.expectedProfit > 0 ? POSITIVE : ColorScheme.LIGHT_GRAY_COLOR);

		JLabel why = new JLabel("<html><body style='width:130px'>" + s.reason + "</html>");
		why.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		why.setFont(why.getFont().deriveFont(10f));

		text.add(line);
		text.add(why);

		JPanel left = new JPanel(new BorderLayout(6, 0));
		left.setOpaque(false);
		left.add(icon, BorderLayout.WEST);
		left.add(text, BorderLayout.CENTER);
		p.add(left, BorderLayout.CENTER);

		JPanel btns = new JPanel();
		btns.setLayout(new BoxLayout(btns, BoxLayout.Y_AXIS));
		btns.setOpaque(false);
		boolean fav = favoriteIds.contains(s.itemId);
		btns.add(smallBtn(fav ? "★" : "☆", fav ? "Remove " + s.name + " from favorites" : "Add " + s.name + " to favorites",
			e -> actions.toggleFavorite(s.itemId, s.name)));
		btns.add(Box.createVerticalStrut(4));
		btns.add(smallBtn("Skip", "Hide this for the session", e -> actions.skip(s.itemId)));
		btns.add(Box.createVerticalStrut(4));
		btns.add(smallBtn("Block", "Never recommend " + s.name + " again", e -> actions.block(s.name)));
		p.add(btns, BorderLayout.EAST);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, s.name);
		return p;
	}

	/** A small square item-sprite icon, loaded async like every other
	 *  RuneLite panel that shows item art. */
	private JLabel iconLabel(int itemId)
	{
		JLabel label = new JLabel();
		label.setPreferredSize(new Dimension(ICON_SIZE, ICON_SIZE));
		label.setMinimumSize(new Dimension(ICON_SIZE, ICON_SIZE));
		label.setHorizontalAlignment(SwingConstants.CENTER);
		if (itemManager != null && itemId > 0)
		{
			AsyncBufferedImage img = itemManager.getImage(itemId);
			img.addTo(label);
		}
		return label;
	}

	/** Click-to-open-chart + a hover tint, shared by every clickable row in
	 *  this panel. */
	private void wireOpenChart(JPanel row, Color normalBg, String itemName)
	{
		row.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		row.setToolTipText("Open the live " + itemName + " chart on PocketGE");
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(itemName));
			}

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
		b.setFont(b.getFont().deriveFont(10f));
		b.setMargin(new Insets(2, 6, 2, 6));
		b.addActionListener(a);
		return b;
	}

	private JPanel chip(String name)
	{
		JPanel c = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 1));
		c.setBackground(ColorScheme.MEDIUM_GRAY_COLOR);
		c.setBorder(BorderFactory.createEmptyBorder(1, 6, 1, 4));
		JLabel n = new JLabel(name);
		n.setForeground(Color.WHITE);
		n.setFont(n.getFont().deriveFont(11f));
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

	/** Small colored chip mirroring the website's Analyst Rating gauge label
	 *  (Strong Buy -> Strong Sell), so a suggestion here reads the same way
	 *  it would on pocketge.com. */
	private JLabel ratingBadge(AnalystRating.Grade rating)
	{
		JLabel badge = new JLabel(rating.label.text);
		badge.setOpaque(true);
		badge.setForeground(Color.BLACK);
		badge.setBackground(ratingColor(rating.label));
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 9.5f));
		badge.setBorder(BorderFactory.createEmptyBorder(2, 6, 2, 6));
		return badge;
	}

	private static Color ratingColor(AnalystRating.Label label)
	{
		switch (label)
		{
			case STRONG_BUY: return GOLD;
			case BUY: return new Color(0xE8, 0xD9, 0xA8);
			case SELL: return new Color(0xB8, 0xE0, 0xDA);
			case STRONG_SELL: return TEAL;
			default: return ColorScheme.LIGHT_GRAY_COLOR;
		}
	}

	private static Color accent(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return GOLD;
			case SELL: return TEAL;
			default: return ADJUST;
		}
	}

	private static String verb(Advisor.Suggestion.Type t)
	{
		switch (t)
		{
			case BUY: return "Buy";
			case SELL: return "Sell";
			case ADJUST_BUY: return "Adjust bid:";
			case ADJUST_SELL: return "Adjust ask:";
			default: return "";
		}
	}

	private static String urlEncode(String s)
	{
		try
		{
			return URLEncoder.encode(s, StandardCharsets.UTF_8.name()).replace("+", "%20");
		}
		catch (UnsupportedEncodingException e)
		{
			return s.replace(" ", "%20");
		}
	}
}
