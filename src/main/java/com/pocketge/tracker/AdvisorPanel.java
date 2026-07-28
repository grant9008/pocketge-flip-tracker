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
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
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
	private static final Color NEGATIVE = new Color(0xEF, 0x53, 0x50);
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
		void setAdvisorEnabled(boolean on);
		void setLocalBridge(boolean on);
		void setBridgePort(int port);
		void setMaxFlips(int n);
		void fillGePrice(long price);
	}

	/** Everything the gear-icon popup shows/edits, bundled so update()
	 *  doesn't grow another loose parameter every time a new setting moves
	 *  in here. Plain public fields, matching this codebase's other small
	 *  data-holder classes (Advisor.Quote, Advisor.ItemMeta, ...). */
	public static class Settings
	{
		public boolean advisorOn;
		public PocketGeTrackerConfig.AdjustInterval interval = PocketGeTrackerConfig.AdjustInterval.M5;
		public PocketGeTrackerConfig.RiskLevel risk = PocketGeTrackerConfig.RiskLevel.MED;
		public List<String> blocked = List.of();
		public boolean bridgeOn;
		public int bridgePort = 8477;
		public int maxFlips = 50;
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JLabel status = new JLabel("Advisor off", SwingConstants.LEFT);
	private final JButton gearBtn = new JButton("⚙");
	private final JPanel selectedWrap = new JPanel(new BorderLayout());
	private final JPanel recommendedWrap = new JPanel(new BorderLayout());
	private final JPanel cards = new JPanel();

	private List<Advisor.Suggestion> currentBuys = List.of();
	private Map<Integer, AnalystRating.Grade> currentRatings = Map.of();
	private int recommendedIndex = 0;
	private Set<Integer> favoriteIds = Set.of();
	private Settings settings = new Settings();
	/** Whatever item is currently in an open GE offer screen — takes over the
	 *  Recommended Flip slot from the advisor's own top pick while set, since
	 *  it's directly actionable right now. Null itemId means nothing open. */
	private Integer geContextItemId = null;
	private String geContextName = "";
	private boolean geContextIsBuy = true;
	private long geContextPrice = 0;
	/** Whichever Favorites row was last clicked — shown above Recommended
	 *  Flip until another row is clicked or dismissed with its own close
	 *  button. Independent of geContext (the GE offer screen) and of the
	 *  Favorites list itself, which never changes when this is set. */
	private FavoritesPanel.Row selectedFavorite = null;

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

		/* A plain JButton.setBackground() is silently ignored by a lot of
		   Swing look-and-feels unless the button is ALSO told to actually
		   paint its content area — without these three calls this button
		   rendered as RuneLite's default (dark, easy to miss) button chrome
		   no matter what color was set here, which is almost certainly why
		   this kept reading as "invisible" despite being gold in the source.
		   Full-width with its own label (not just a glyph) so there's no
		   ambiguity even if a future L&F quirk mutes the color again. */
		gearBtn.setText("⚙ SETTINGS");
		gearBtn.setToolTipText("Settings: advisor, re-check interval, risk level, never-recommend list, website bridge, flip history size");
		gearBtn.setOpaque(true);
		gearBtn.setContentAreaFilled(true);
		gearBtn.setBorderPainted(true);
		gearBtn.setFocusPainted(false);
		gearBtn.setFont(gearBtn.getFont().deriveFont(Font.BOLD, 12f));
		gearBtn.setAlignmentX(0f);
		gearBtn.setMaximumSize(new Dimension(Integer.MAX_VALUE, 26));
		gearBtn.setBackground(GOLD);
		gearBtn.setForeground(Color.BLACK);
		gearBtn.setBorder(BorderFactory.createLineBorder(GOLD.darker(), 1));
		gearBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		gearBtn.addActionListener(e -> showSettingsPopup());
		north.add(gearBtn);
		north.add(Box.createVerticalStrut(6));

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setFont(status.getFont().deriveFont(10.5f));
		status.setAlignmentX(0f);
		north.add(status);
		add(north, BorderLayout.NORTH);

		recommendedWrap.setOpaque(false);
		recommendedWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		selectedWrap.setOpaque(false);
		selectedWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));

		cards.setLayout(new BoxLayout(cards, BoxLayout.Y_AXIS));
		cards.setOpaque(false);
		cards.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		center.add(selectedWrap);
		center.add(recommendedWrap);
		add(center, BorderLayout.CENTER);
		// cards (the rest of the suggestions beyond the one Recommended Flip)
		// is NOT added here — MainPanel places it further down the scroll
		// column via getSuggestionCards(), so Favorites sits directly under
		// Recommended Flip, matching the website's layout.
	}

	/** The rest of the advisor's suggestions (adjust/sell nudges, other buy
	 *  candidates) beyond the single Recommended Flip — a separate component
	 *  so MainPanel can place it after Favorites/History instead of it
	 *  always trailing the recommended card. */
	public JPanel getSuggestionCards()
	{
		return cards;
	}

	/** Builds a fresh popup on every open so it always reflects the latest
	 *  state stashed by update() — cheaper than keeping a live popup synced
	 *  while it's closed, and the popup is thrown away on dismiss anyway.
	 *  Everything that otherwise lives only in RuneLite's own plugin config
	 *  screen (the wrench icon, several clicks away) is here too, so routine
	 *  tweaks never require leaving this panel. */
	private void showSettingsPopup()
	{
		JPopupMenu popup = new JPopupMenu();
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
		box.setFont(box.getFont().deriveFont(11f));
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
		valueLabel.setFont(valueLabel.getFont().deriveFont(Font.BOLD, 11f));
		valueLabel.setPreferredSize(new Dimension(46, 18));
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
			setActive(b, v == settings.interval);
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
			setActive(b, v == settings.risk);
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
	 *  card's star renders filled or hollow. {@code settings} is stashed for
	 *  the next time the gear-icon popup opens — that's where every field on
	 *  it (advisor on/off, interval, risk, blocklist, bridge, flip count)
	 *  gets edited. */
	public void update(List<Advisor.Suggestion> suggestions,
		Map<Integer, AnalystRating.Grade> ratings, Set<Integer> favoriteIds, Settings settings)
	{
		this.favoriteIds = favoriteIds != null ? favoriteIds : Set.of();
		this.currentRatings = ratings != null ? ratings : Map.of();
		this.settings = settings != null ? settings : this.settings;

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

	/** Called when a Favorites row is clicked. Renders above Recommended
	 *  Flip — the Favorites list itself never changes, this is a separate,
	 *  persistent "currently viewing" slot, same relationship the website's
	 *  ticker header has to its own flip-finder card. Pass null to dismiss. */
	public void setSelectedItem(FavoritesPanel.Row r)
	{
		this.selectedFavorite = r;
		renderSelected();
	}

	private void renderSelected()
	{
		selectedWrap.removeAll();
		final FavoritesPanel.Row r = selectedFavorite;
		if (r == null)
		{
			selectedWrap.revalidate();
			selectedWrap.repaint();
			return;
		}

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		JPanel closeRow = new JPanel(new BorderLayout());
		closeRow.setOpaque(false);
		JLabel viewingLbl = new JLabel("VIEWING");
		viewingLbl.setForeground(GOLD);
		viewingLbl.setFont(viewingLbl.getFont().deriveFont(Font.BOLD, 9.5f));
		closeRow.add(viewingLbl, BorderLayout.WEST);
		JButton close = smallBtn("✕", "Close", e -> setSelectedItem(null));
		closeRow.add(close, BorderLayout.EAST);
		p.add(closeRow);
		p.add(Box.createVerticalStrut(4));

		JPanel head = new JPanel(new BorderLayout(8, 0));
		head.setOpaque(false);
		head.setAlignmentX(0f);
		head.add(iconLabel(r.id, ICON_SIZE), BorderLayout.WEST);
		JPanel headText = new JPanel();
		headText.setLayout(new BoxLayout(headText, BoxLayout.Y_AXIS));
		headText.setOpaque(false);
		JLabel name = new JLabel(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
		name.setAlignmentX(0f);
		headText.add(name);
		JPanel priceRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		priceRow.setOpaque(false);
		priceRow.setAlignmentX(0f);
		JLabel price = new JLabel(r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : "—");
		price.setForeground(GOLD);
		price.setFont(price.getFont().deriveFont(Font.BOLD, 13f));
		priceRow.add(price);
		if (r.changePct != 0)
		{
			JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
			chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
			chg.setFont(chg.getFont().deriveFont(11f));
			priceRow.add(chg);
		}
		headText.add(priceRow);
		head.add(headText, BorderLayout.CENTER);
		p.add(head);
		p.add(Box.createVerticalStrut(8));

		JPanel targets = new JPanel(new GridLayout(1, 2, 6, 0));
		targets.setOpaque(false);
		targets.setAlignmentX(0f);
		targets.add(targetBox("TARGET BUY", r.targetBuy, GOLD));
		targets.add(targetBox("TARGET SELL", r.targetSell, TEAL));
		p.add(targets);
		p.add(Box.createVerticalStrut(6));

		if (r.limit > 0 && r.potentialProfit != 0)
		{
			JPanel profitRow = new JPanel(new BorderLayout());
			profitRow.setOpaque(false);
			profitRow.setAlignmentX(0f);
			profitRow.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 2));
			JLabel profitLbl = new JLabel("POTENTIAL PROFIT");
			profitLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			profitLbl.setFont(profitLbl.getFont().deriveFont(Font.BOLD, 9f));
			profitRow.add(profitLbl, BorderLayout.WEST);
			JLabel profitVal = new JLabel((r.potentialProfit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(r.potentialProfit) + " gp");
			profitVal.setForeground(r.potentialProfit >= 0 ? POSITIVE : NEGATIVE);
			profitVal.setFont(profitVal.getFont().deriveFont(Font.BOLD, 12f));
			profitRow.add(profitVal, BorderLayout.EAST);
			p.add(profitRow);
			JLabel limitLbl = new JLabel(QuantityFormatter.quantityToStackSize(r.limit) + " units @ 4h limit");
			limitLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			limitLbl.setFont(limitLbl.getFont().deriveFont(9.5f));
			limitLbl.setAlignmentX(0f);
			limitLbl.setBorder(BorderFactory.createEmptyBorder(0, 2, 6, 0));
			p.add(limitLbl);
		}

		if (r.rating != null)
		{
			JPanel ratingWrap = new JPanel();
			ratingWrap.setLayout(new BoxLayout(ratingWrap, BoxLayout.Y_AXIS));
			ratingWrap.setOpaque(false);
			ratingWrap.setAlignmentX(0f);
			ratingWrap.setBorder(BorderFactory.createEmptyBorder(2, 2, 6, 2));
			JLabel ratingLbl = new JLabel("ANALYST RATING");
			ratingLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			ratingLbl.setFont(ratingLbl.getFont().deriveFont(Font.BOLD, 9f));
			ratingLbl.setAlignmentX(0f);
			ratingWrap.add(ratingLbl);
			JLabel ratingVal = new JLabel(r.rating.label.text + " · " + r.rating.score);
			ratingVal.setForeground(ratingColor(r.rating.label));
			ratingVal.setFont(ratingVal.getFont().deriveFont(Font.BOLD, 13f));
			ratingVal.setAlignmentX(0f);
			ratingWrap.add(ratingVal);
			ratingWrap.add(ratingBar(r.rating.score));
			p.add(ratingWrap);
		}

		JButton openChart = new JButton("Open full chart ↗");
		openChart.setAlignmentX(0f);
		openChart.setFocusPainted(false);
		openChart.setBackground(GOLD);
		openChart.setForeground(Color.BLACK);
		openChart.setFont(openChart.getFont().deriveFont(Font.BOLD, 11f));
		openChart.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		openChart.addActionListener(e -> LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(r.name)));
		p.add(openChart);

		selectedWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		selectedWrap.add(p, BorderLayout.CENTER);
		selectedWrap.revalidate();
		selectedWrap.repaint();
	}

	private JPanel targetBox(String label, long price, Color accent)
	{
		JPanel box = new JPanel();
		box.setLayout(new BoxLayout(box, BoxLayout.Y_AXIS));
		box.setBackground(ColorScheme.DARK_GRAY_COLOR);
		box.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createLineBorder(accent, 1),
			BorderFactory.createEmptyBorder(5, 7, 5, 7)));
		JLabel lbl = new JLabel(label);
		lbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		lbl.setFont(lbl.getFont().deriveFont(Font.BOLD, 8.5f));
		lbl.setAlignmentX(0f);
		box.add(lbl);
		JLabel val = new JLabel(price > 0 ? QuantityFormatter.quantityToStackSize(price) : "—");
		val.setForeground(Color.WHITE);
		val.setFont(val.getFont().deriveFont(Font.BOLD, 13f));
		val.setAlignmentX(0f);
		box.add(val);
		return box;
	}

	/** A simple 5-segment strip (Strong Sell..Strong Buy) with the current
	 *  score's segment lit — a lightweight stand-in for the website's
	 *  gradient gauge bar. */
	private JPanel ratingBar(int score)
	{
		JPanel bar = new JPanel(new GridLayout(1, 5, 2, 0));
		bar.setOpaque(false);
		bar.setAlignmentX(0f);
		bar.setPreferredSize(new Dimension(0, 5));
		bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 5));
		Color[] segColors = { TEAL, TEAL.brighter(), ColorScheme.MEDIUM_GRAY_COLOR, GOLD.darker(), GOLD };
		int lit = Math.min(4, score / 20);
		for (int i = 0; i < 5; i++)
		{
			JPanel seg = new JPanel();
			seg.setBackground(i == lit ? segColors[i] : ColorScheme.DARKER_GRAY_COLOR);
			bar.add(seg);
		}
		return bar;
	}

	/** Called whenever the plugin detects (or clears) an open GE offer
	 *  screen. While set, this takes over the Recommended Flip slot — the
	 *  player is actively doing something right now, which is more useful
	 *  than our own algorithm's general best pick. */
	public void setGeContext(Integer itemId, String name, boolean isBuy, long price)
	{
		this.geContextItemId = itemId;
		this.geContextName = name != null ? name : "";
		this.geContextIsBuy = isBuy;
		this.geContextPrice = price;
		renderRecommended();
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
		if (geContextItemId != null)
		{
			renderGeContextCard();
			return;
		}
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

	/** A price for whatever item the player actually has the GE offer
	 *  screen open on right now — same card shell as the normal Recommended
	 *  Flip row, just labeled and colored to read as "here's your price",
	 *  not "here's our pick". The fill button behaves exactly like the
	 *  normal recommended row's — same live-fill-or-copy action. */
	private void renderGeContextCard()
	{
		final int itemId = geContextItemId;
		final String name = geContextName;
		final boolean isBuy = geContextIsBuy;
		final long price = geContextPrice;

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, isBuy ? GOLD : TEAL),
			BorderFactory.createEmptyBorder(6, 8, 6, 6)));

		JPanel kicker = new JPanel(new BorderLayout(4, 0));
		kicker.setOpaque(false);
		JLabel titleLabel = new JLabel("🛒 " + (isBuy ? "BUYING NOW" : "SELLING NOW"));
		titleLabel.setForeground(isBuy ? GOLD : TEAL);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 9.5f));
		kicker.add(titleLabel, BorderLayout.WEST);

		JPanel kickerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		kickerBtns.setOpaque(false);
		kickerBtns.add(copyPriceBtn(price));
		boolean fav = favoriteIds.contains(itemId);
		kickerBtns.add(smallBtn(fav ? "★" : "☆", fav ? "Remove " + name + " from favorites" : "Add " + name + " to favorites",
			e -> actions.toggleFavorite(itemId, name)));
		kicker.add(kickerBtns, BorderLayout.EAST);
		p.add(kicker);
		p.add(Box.createVerticalStrut(3));

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(iconLabel(itemId, MINI_ICON_SIZE), BorderLayout.WEST);

		JLabel nameLabel = new JLabel(truncateName(name));
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 12f));
		row.add(nameLabel, BorderLayout.CENTER);

		JLabel priceLabel = new JLabel(QuantityFormatter.quantityToStackSize(price) + " gp");
		priceLabel.setForeground(isBuy ? GOLD : TEAL);
		priceLabel.setFont(priceLabel.getFont().deriveFont(Font.BOLD, 11f));
		row.add(priceLabel, BorderLayout.EAST);
		p.add(row);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, name);
		p.setToolTipText((isBuy ? "Live wiki insta-sell price" : "Live wiki insta-buy price") + " for " + name
			+ " — click ⧉ to fill it into the open GE offer.");
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

	/** The "one click to autofill" Copilot affordance: if the GE offer
	 *  screen has its price prompt open, the plugin writes the number
	 *  straight into it; either way it also copies to the clipboard, so a
	 *  paste always works as the fallback. The live-fill half only ever
	 *  fires when the plugin has confirmed (from the actual on-screen chat
	 *  prompt text) that it's looking at the price entry, not a guess. */
	private JButton copyPriceBtn(long price)
	{
		JButton b = smallBtn("⧉", "Fill " + QuantityFormatter.quantityToStackSize(price) + " gp into the GE price box if it's open, or copy it to paste in",
			e -> actions.fillGePrice(price));
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
