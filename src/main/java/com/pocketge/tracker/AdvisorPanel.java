package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.LinkBrowser;
import net.runelite.client.util.QuantityFormatter;

/**
 * Advisor UI: a compact status/settings header (Risk Level, Re-check
 * interval and the never-recommend list live behind a gear-icon popup —
 * matching the website's "tuck the knobs away, lead with the numbers"
 * philosophy), a single-row "Recommended Flip" mirroring the site's
 * collapsed flip-finder card, and the rest of the suggestions (adjust /
 * sell) as a stack of cards with Skip / Block / Favorite.
 */
public class AdvisorPanel extends PluginPanel
{
	private static final Color POSITIVE = new Color(0x1F, 0xB8, 0x5C);
	private static final Color GOLD = new Color(0xE5, 0xC1, 0x58);
	private static final Color TEAL = new Color(0x26, 0xA6, 0x9A);
	private static final Color ADJUST = new Color(0xFF, 0x9F, 0x43);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	private static final int ICON_SIZE = 32;
	private static final int MINI_ICON_SIZE = 22;

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
	private final JButton gearBtn = new JButton("⚙");
	private final JPanel recommendedWrap = new JPanel(new BorderLayout());
	private final JPanel cards = new JPanel();

	private List<Advisor.Suggestion> currentBuys = List.of();
	private Map<Integer, AnalystRating.Grade> currentRatings = Map.of();
	private int recommendedIndex = 0;
	private Set<Integer> favoriteIds = Set.of();
	private List<String> currentBlocked = List.of();
	private PocketGeTrackerConfig.AdjustInterval currentInterval = PocketGeTrackerConfig.AdjustInterval.M5;
	private PocketGeTrackerConfig.RiskLevel currentRisk = PocketGeTrackerConfig.RiskLevel.MED;

	public AdvisorPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout(0, 8));
		setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
		setBackground(ColorScheme.DARK_GRAY_COLOR);

		JPanel north = new JPanel(new BorderLayout(6, 0));
		north.setOpaque(false);
		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setFont(status.getFont().deriveFont(10.5f));
		north.add(status, BorderLayout.CENTER);

		gearBtn.setToolTipText("Advisor settings: re-check interval, risk level, never-recommend list");
		gearBtn.setFocusPainted(false);
		gearBtn.setMargin(new Insets(2, 6, 2, 6));
		gearBtn.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		gearBtn.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		gearBtn.addActionListener(e -> showSettingsPopup());
		north.add(gearBtn, BorderLayout.EAST);
		add(north, BorderLayout.NORTH);

		recommendedWrap.setOpaque(false);
		recommendedWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setOpaque(false);
		cards.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		center.add(recommendedWrap);
		center.add(cards);
		add(center, BorderLayout.CENTER);
	}

	/** Builds a fresh popup on every open so it always reflects the latest
	 *  state stashed by update() — cheaper than keeping a live popup synced
	 *  while it's closed, and the popup is thrown away on dismiss anyway. */
	private void showSettingsPopup()
	{
		JPopupMenu popup = new JPopupMenu();
		popup.setBackground(ColorScheme.DARK_GRAY_COLOR);
		popup.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

		JPanel content = new JPanel();
		content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
		content.setOpaque(false);
		content.add(controlRow("Re-check every", intervalRow()));
		content.add(Box.createVerticalStrut(6));
		content.add(controlRow("Risk level", riskRow()));
		content.add(Box.createVerticalStrut(8));

		JLabel blkTitle = new JLabel("Never recommend");
		blkTitle.setForeground(GOLD);
		blkTitle.setAlignmentX(0f);
		blkTitle.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));
		content.add(blkTitle);

		JPanel blockChips = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
		blockChips.setOpaque(false);
		if (currentBlocked.isEmpty())
		{
			JLabel empty = new JLabel("Nothing blocked");
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			blockChips.add(empty);
		}
		else
		{
			for (String name : currentBlocked)
			{
				blockChips.add(chip(name));
			}
		}
		content.add(blockChips);

		popup.add(content);
		popup.show(gearBtn, gearBtn.getWidth() - 260, gearBtn.getHeight() + 4);
	}

	private JPanel controlRow(String label, JPanel buttonRow)
	{
		JPanel wrap = new JPanel(new BorderLayout(0, 3));
		wrap.setOpaque(false);
		wrap.setAlignmentX(0f);
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
			setActive(b, v == currentInterval);
			b.addActionListener(e -> actions.setAdjustInterval(v));
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
			setActive(b, v == currentRisk);
			b.addActionListener(e -> actions.setRiskLevel(v));
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
	 *  card's star renders filled or hollow. {@code interval} / {@code risk}
	 *  are stashed for the next time the gear-icon settings popup opens; the
	 *  never-recommend list moved into that same popup. */
	public void update(List<Advisor.Suggestion> suggestions, List<String> blocked,
		Map<Integer, AnalystRating.Grade> ratings, Set<Integer> favoriteIds,
		PocketGeTrackerConfig.AdjustInterval interval, PocketGeTrackerConfig.RiskLevel risk)
	{
		this.favoriteIds = favoriteIds != null ? favoriteIds : Set.of();
		this.currentRatings = ratings != null ? ratings : Map.of();
		this.currentBlocked = blocked != null ? blocked : List.of();
		this.currentInterval = interval != null ? interval : this.currentInterval;
		this.currentRisk = risk != null ? risk : this.currentRisk;

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
		revalidate();
		repaint();
	}

	/** The single, prominent "best buy right now" row — mirrors the site's
	 *  COLLAPSED flip-finder card (icon · name · score · +edge/ea on one
	 *  line) rather than a big multi-line card, so it reads at a glance and
	 *  the reason/full price breakdown live in the tooltip instead of eating
	 *  vertical space. A Next arrow cycles through the rest of the buy
	 *  candidates when there's more than one. */
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
		long perEa = s.quantity > 0 ? s.expectedProfit / s.quantity : s.expectedProfit;

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, GOLD),
			BorderFactory.createEmptyBorder(6, 8, 6, 6)));

		JPanel kicker = new JPanel(new BorderLayout(4, 0));
		kicker.setOpaque(false);
		JLabel titleLabel = new JLabel("⚡ RECOMMENDED FLIP");
		titleLabel.setForeground(GOLD);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 9.5f));
		kicker.add(titleLabel, BorderLayout.WEST);

		JPanel kickerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		kickerBtns.setOpaque(false);
		kickerBtns.add(copyPriceBtn(s.price));
		boolean fav = favoriteIds.contains(s.itemId);
		kickerBtns.add(smallBtn(fav ? "★" : "☆", fav ? "Remove " + s.name + " from favorites" : "Add " + s.name + " to favorites",
			e -> actions.toggleFavorite(s.itemId, s.name)));
		if (currentBuys.size() > 1)
		{
			kickerBtns.add(smallBtn("↻", "Show the next best buy", e ->
			{
				recommendedIndex = (recommendedIndex + 1) % currentBuys.size();
				renderRecommended();
			}));
		}
		kicker.add(kickerBtns, BorderLayout.EAST);
		p.add(kicker);
		p.add(Box.createVerticalStrut(3));

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(iconLabel(s.itemId, MINI_ICON_SIZE), BorderLayout.WEST);

		JLabel name = new JLabel(truncateName(s.name));
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 12f));
		row.add(name, BorderLayout.CENTER);

		JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		stats.setOpaque(false);
		if (rating != null)
		{
			stats.add(ratingScoreLabel(rating));
		}
		JLabel edge = new JLabel(perEa > 0 ? "+" + QuantityFormatter.quantityToStackSize(perEa) + "/ea" : "—");
		edge.setForeground(perEa > 0 ? POSITIVE : ColorScheme.LIGHT_GRAY_COLOR);
		edge.setFont(edge.getFont().deriveFont(Font.BOLD, 11f));
		stats.add(edge);
		row.add(stats, BorderLayout.EAST);
		p.add(row);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, s.name);
		// wireOpenChart sets a generic "open chart" tooltip; override it with
		// the full buy/qty/price/reason breakdown that used to live in the
		// card body, now that the row itself is compact.
		p.setToolTipText("Buy " + QuantityFormatter.quantityToStackSize(s.quantity) + " " + s.name + " at "
			+ QuantityFormatter.quantityToStackSize(s.price) + " gp — " + s.reason);
		recommendedWrap.add(p, BorderLayout.CENTER);
		recommendedWrap.revalidate();
		recommendedWrap.repaint();
	}

	/** Matches the site's text-overflow ellipsis on the collapsed flip
	 *  card — long names get cut with an ellipsis; the full name is still
	 *  reachable via the row's tooltip. */
	private static String truncateName(String name)
	{
		final int max = 16;
		return name.length() > max ? name.substring(0, max - 1) + "…" : name;
	}

	/** Compact numeric score chip (0-100), colored like the site's Analyst
	 *  Rating gauge — the mini-row equivalent of {@link #ratingBadge}. */
	private JLabel ratingScoreLabel(AnalystRating.Grade rating)
	{
		JLabel badge = new JLabel(String.valueOf(rating.score));
		badge.setOpaque(true);
		badge.setForeground(Color.BLACK);
		badge.setBackground(ratingColor(rating.label));
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 10f));
		badge.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
		return badge;
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
		btns.add(copyPriceBtn(s.price));
		btns.add(Box.createVerticalStrut(4));
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
		return iconLabel(itemId, ICON_SIZE);
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

	/** Copies the raw number to the clipboard so it can be pasted straight
	 *  into the GE offer's price box — the "one click to autofill" Copilot
	 *  affordance, done via the clipboard rather than writing into the game
	 *  widget directly. Directly poking the offer's price field would need
	 *  us to be certain which item that screen is currently showing (wrong
	 *  guess = wrong price landing on a real trade with real gp on the
	 *  line), which isn't something we can verify offline — copy+paste gets
	 *  the same speed win with the player's own paste as the safety check. */
	private JButton copyPriceBtn(long price)
	{
		String text = String.valueOf(price);
		JButton b = smallBtn("⧉", "Copy " + QuantityFormatter.quantityToStackSize(price) + " gp — paste into the GE price box",
			e -> Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text), null));
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
