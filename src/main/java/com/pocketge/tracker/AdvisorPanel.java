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
import javax.swing.JMenuItem;
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
 * Advisor UI: a compact status/settings header (re-check interval and the
 * never-recommend list live behind a gear-icon popup — matching the
 * website's "tuck the knobs away, lead with the numbers" philosophy) and a
 * single-row "Top Suggestion" card mirroring the site's collapsed
 * flip-finder card — cycling through every suggestion Advisor.advise()
 * returns (adjust nudges, bank/inventory sells, and buys, in that same
 * ranked order) one at a time via its own Next arrow, instead of splitting
 * "our pick" and "everything else" into two separate cards competing for
 * the same attention.
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
		public List<String> blocked = List.of();
		public boolean bridgeOn;
		public int bridgePort = 8477;
		public int maxFlips = 50;
	}

	private final ItemManager itemManager;
	private final Actions actions;
	private final JLabel status = new JLabel("Advisor off", SwingConstants.LEFT);
	private final JButton gearBtn = new JButton("⚙");
	/** The single, always-visible top card — shows whichever Favorites row
	 *  was last clicked, or (once cleared / by default) the top-ranked
	 *  suggestion, so the slot never just disappears. */
	private final JPanel topCardWrap = new JPanel(new BorderLayout());
	private final JPanel geContextWrap = new JPanel(new BorderLayout());

	private List<Advisor.Suggestion> currentSuggestions = List.of();
	private Map<Integer, AnalystRating.Grade> currentRatings = Map.of();
	private int suggestionIndex = 0;
	private Set<Integer> favoriteIds = Set.of();
	private Settings settings = new Settings();
	/** Whatever item is currently in an open GE offer screen — its own
	 *  "BUYING NOW"/"SELLING NOW" section, separate from (and above) the
	 *  Top Suggestion card rather than replacing it, since the two answer
	 *  different questions ("what am I doing right now" vs "what's our
	 *  pick"). Null itemId means nothing open. */
	private Integer geContextItemId = null;
	private String geContextName = "";
	private boolean geContextIsBuy = true;
	private long geContextPrice = 0;
	/** Whichever Favorites row was last clicked — takes over the top card
	 *  until another row is clicked or dismissed with its own close button,
	 *  at which point the top card reverts to showing the top suggestion.
	 *  Independent of geContext (the GE offer screen) and of the Favorites
	 *  list itself, which never changes when this is set. */
	private FavoritesPanel.Row selectedFavorite = null;

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
		gearBtn.addActionListener(e -> showSettingsPopup());
		north.add(gearBtn, BorderLayout.WEST);

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setFont(status.getFont().deriveFont(11.5f));
		north.add(status, BorderLayout.CENTER);
		add(north, BorderLayout.NORTH);

		topCardWrap.setOpaque(false);
		topCardWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		geContextWrap.setOpaque(false);

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		center.add(topCardWrap);
		center.add(geContextWrap);
		add(center, BorderLayout.CENTER);
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
		status.setText(s);
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

		// Advisor.advise() already returns these ranked (adjust nudges, then
		// bank/inventory sells, then buys) — cycle through that single order
		// rather than splitting "our pick" from "everything else" into two
		// separate cards.
		currentSuggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
		if (suggestionIndex >= currentSuggestions.size())
		{
			suggestionIndex = 0;
		}
		renderTopCard();

		revalidate();
		repaint();
	}

	/** Called when a Favorites row is clicked. Takes over the top card until
	 *  another row is clicked or dismissed with its own close button, same
	 *  relationship the website's ticker header has to its own flip-finder
	 *  card. Pass null to dismiss (reverts the top card to the top
	 *  suggestion). */
	public void setSelectedItem(FavoritesPanel.Row r)
	{
		this.selectedFavorite = r;
		renderTopCard();
	}

	/** Decides what the always-visible top card shows: whatever Favorites
	 *  row is currently being inspected, or — the default, and what it
	 *  reverts to once that's cleared — the top-ranked suggestion. One slot
	 *  serving both jobs instead of a card that disappears when nothing's
	 *  selected. */
	private void renderTopCard()
	{
		topCardWrap.removeAll();
		if (selectedFavorite != null)
		{
			renderSelectedCard();
		}
		else
		{
			renderSuggestionCard();
		}
		topCardWrap.revalidate();
		topCardWrap.repaint();
	}

	private void renderSelectedCard()
	{
		final FavoritesPanel.Row r = selectedFavorite;

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, GOLD),
			BorderFactory.createEmptyBorder(7, 9, 7, 7)));

		// Ticker row: icon, name, price+change, and close — all on one line,
		// matching how compact the site's own ticker row is.
		JPanel ticker = new JPanel(new BorderLayout(6, 0));
		ticker.setOpaque(false);
		ticker.setAlignmentX(0f);
		ticker.add(iconLabel(r.id, MINI_ICON_SIZE), BorderLayout.WEST);
		JPanel tickerMid = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
		tickerMid.setOpaque(false);
		JLabel name = new JLabel(truncateName(r.name));
		name.setToolTipText(r.name);
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
		tickerMid.add(name);
		tickerMid.add(chartHintIcon());
		JLabel price = new JLabel(r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : "—");
		price.setForeground(GOLD);
		price.setFont(price.getFont().deriveFont(Font.BOLD, 12.5f));
		tickerMid.add(price);
		if (r.changePct != 0)
		{
			JLabel chg = new JLabel(String.format("%s%.1f%%", r.changePct >= 0 ? "+" : "", r.changePct));
			chg.setForeground(r.changePct >= 0 ? POSITIVE : NEGATIVE);
			chg.setFont(chg.getFont().deriveFont(11.5f));
			tickerMid.add(chg);
		}
		ticker.add(tickerMid, BorderLayout.CENTER);
		JPanel tickerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		tickerBtns.setOpaque(false);
		tickerBtns.add(smallBtn("✕", "Stop inspecting — show the top suggestion again", e -> setSelectedItem(null)));
		ticker.add(tickerBtns, BorderLayout.EAST);
		p.add(ticker);

		if (r.limit > 0 && r.potentialProfit != 0)
		{
			p.add(Box.createVerticalStrut(4));
			JPanel profitRow = new JPanel(new BorderLayout());
			profitRow.setOpaque(false);
			profitRow.setAlignmentX(0f);
			JPanel profitLeft = new JPanel();
			profitLeft.setLayout(new BoxLayout(profitLeft, BoxLayout.Y_AXIS));
			profitLeft.setOpaque(false);
			JLabel profitLbl = new JLabel("POTENTIAL PROFIT");
			profitLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			profitLbl.setFont(profitLbl.getFont().deriveFont(Font.BOLD, 9.5f));
			profitLbl.setAlignmentX(0f);
			profitLeft.add(profitLbl);
			JLabel limitLbl = new JLabel(QuantityFormatter.quantityToStackSize(r.limit) + " units @ 4h limit");
			limitLbl.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			limitLbl.setFont(limitLbl.getFont().deriveFont(10f));
			limitLbl.setAlignmentX(0f);
			profitLeft.add(limitLbl);
			profitRow.add(profitLeft, BorderLayout.WEST);
			JLabel profitVal = new JLabel((r.potentialProfit >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(r.potentialProfit) + " gp");
			profitVal.setForeground(r.potentialProfit >= 0 ? POSITIVE : NEGATIVE);
			profitVal.setFont(profitVal.getFont().deriveFont(Font.BOLD, 14f));
			profitRow.add(profitVal, BorderLayout.EAST);
			p.add(profitRow);
		}

		if (r.rating != null)
		{
			p.add(Box.createVerticalStrut(4));
			JPanel ratingRow = new JPanel(new BorderLayout(6, 0));
			ratingRow.setOpaque(false);
			ratingRow.setAlignmentX(0f);
			JLabel ratingVal = new JLabel(r.rating.label.text);
			ratingVal.setForeground(ratingColor(r.rating.label));
			ratingVal.setFont(ratingVal.getFont().deriveFont(Font.BOLD, 12.5f));
			ratingRow.add(ratingVal, BorderLayout.WEST);
			ratingRow.add(ratingBar(r.rating.score), BorderLayout.CENTER);
			p.add(ratingRow);
		}

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, r.name);
		topCardWrap.add(p, BorderLayout.CENTER);
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
	 *  screen. Renders into its own section (geContextWrap), separate from
	 *  and below the top card — the two answer different questions and both
	 *  stay visible together rather than one replacing the other. */
	public void setGeContext(Integer itemId, String name, boolean isBuy, long price)
	{
		this.geContextItemId = itemId;
		this.geContextName = name != null ? name : "";
		this.geContextIsBuy = isBuy;
		this.geContextPrice = price;
		renderGeContext();
	}

	private void renderGeContext()
	{
		geContextWrap.removeAll();
		if (geContextItemId != null)
		{
			renderGeContextCard();
		}
		geContextWrap.revalidate();
		geContextWrap.repaint();
	}

	/** A price for whatever item the player actually has the GE offer
	 *  screen open on right now — same card shell as the top card, just
	 *  labeled and colored to read as "here's your price", not "here's our
	 *  pick". The fill button behaves exactly like the suggestion row's —
	 *  same live-fill-or-copy action. */
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
			BorderFactory.createEmptyBorder(7, 9, 7, 7)));

		JPanel kicker = new JPanel(new BorderLayout(4, 0));
		kicker.setOpaque(false);
		JLabel titleLabel = new JLabel("🛒 " + (isBuy ? "BUYING NOW" : "SELLING NOW"));
		titleLabel.setForeground(isBuy ? GOLD : TEAL);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 10.5f));
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

		JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		nameRow.setOpaque(false);
		JLabel nameLabel = new JLabel(truncateName(name));
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 13f));
		nameRow.add(nameLabel);
		nameRow.add(chartHintIcon());
		row.add(nameRow, BorderLayout.CENTER);

		JLabel priceLabel = new JLabel(QuantityFormatter.quantityToStackSize(price) + " gp");
		priceLabel.setForeground(isBuy ? GOLD : TEAL);
		priceLabel.setFont(priceLabel.getFont().deriveFont(Font.BOLD, 12f));
		row.add(priceLabel, BorderLayout.EAST);
		p.add(row);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, name);
		p.setToolTipText((isBuy ? "Live wiki insta-sell price" : "Live wiki insta-buy price") + " for " + name
			+ " — click ⧉ to fill it into the open GE offer.");
		geContextWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 10, 0));
		geContextWrap.add(p, BorderLayout.CENTER);
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
	 *  Rating gauge. */
	private JLabel ratingScoreLabel(AnalystRating.Grade rating)
	{
		JLabel badge = new JLabel(String.valueOf(rating.score));
		badge.setOpaque(true);
		badge.setForeground(Color.BLACK);
		badge.setBackground(ratingColor(rating.label));
		badge.setFont(badge.getFont().deriveFont(Font.BOLD, 11f));
		badge.setBorder(BorderFactory.createEmptyBorder(1, 5, 1, 5));
		return badge;
	}

	/** A small, non-interactive "you can click this card to open its chart"
	 *  hint — mirrors Flipping Copilot's own graph icon next to item names.
	 *  The whole card is already clickable via wireOpenChart; this just
	 *  makes that discoverable instead of relying on hover alone. */
	private JLabel chartHintIcon()
	{
		JLabel icon = new JLabel("📈");
		icon.setFont(icon.getFont().deriveFont(10f));
		icon.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		return icon;
	}

	/** The single, ranked "what to do next" row — mirrors the site's
	 *  COLLAPSED flip-finder card (icon · name · score · stat on one line)
	 *  rather than a big multi-line card, so it reads at a glance and the
	 *  reason/full price breakdown live in the tooltip instead of eating
	 *  vertical space. Cycles through every suggestion Advisor.advise()
	 *  returned — adjust nudges, bank/inventory sells, and buys, in that
	 *  same ranked order — via its own Next arrow, one at a time, instead of
	 *  splitting "our pick" from "everything else" into two cards. This is
	 *  the top card's default content — renderSelectedCard() takes over
	 *  instead whenever a Favorites row is being inspected. */
	private void renderSuggestionCard()
	{
		if (currentSuggestions.isEmpty())
		{
			JLabel empty = new JLabel("<html><center>No suggestions right now.</center></html>", SwingConstants.CENTER);
			empty.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			empty.setFont(empty.getFont().deriveFont(12f));
			topCardWrap.add(empty, BorderLayout.CENTER);
			return;
		}

		Advisor.Suggestion s = currentSuggestions.get(suggestionIndex);
		AnalystRating.Grade rating = currentRatings.get(s.itemId);
		Color accent = accent(s.type);
		boolean isBuy = s.type == Advisor.Suggestion.Type.BUY;

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(ColorScheme.DARKER_GRAY_COLOR);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 3, 0, 0, accent),
			BorderFactory.createEmptyBorder(7, 9, 7, 7)));

		JPanel kicker = new JPanel(new BorderLayout(4, 0));
		kicker.setOpaque(false);
		JLabel titleLabel = new JLabel(isBuy ? "⚡ RECOMMENDED FLIP"
			: "📦 " + verb(s.type).replace(":", "").toUpperCase() + " SUGGESTION");
		titleLabel.setForeground(accent);
		titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 10.5f));
		kicker.add(titleLabel, BorderLayout.WEST);

		JPanel kickerBtns = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		kickerBtns.setOpaque(false);
		kickerBtns.add(copyPriceBtn(s.price));
		boolean fav = favoriteIds.contains(s.itemId);
		kickerBtns.add(smallBtn(fav ? "★" : "☆", fav ? "Remove " + s.name + " from favorites" : "Add " + s.name + " to favorites",
			e -> actions.toggleFavorite(s.itemId, s.name)));
		if (currentSuggestions.size() > 1)
		{
			kickerBtns.add(smallBtn("↻", "Show the next suggestion", e ->
			{
				suggestionIndex = (suggestionIndex + 1) % currentSuggestions.size();
				renderTopCard();
			}));
		}
		kicker.add(kickerBtns, BorderLayout.EAST);
		p.add(kicker);
		p.add(Box.createVerticalStrut(3));

		JPanel row = new JPanel(new BorderLayout(6, 0));
		row.setOpaque(false);
		row.add(iconLabel(s.itemId, MINI_ICON_SIZE), BorderLayout.WEST);

		JPanel nameRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 5, 0));
		nameRow.setOpaque(false);
		JLabel name = new JLabel(truncateName(s.name));
		name.setForeground(Color.WHITE);
		name.setFont(name.getFont().deriveFont(Font.BOLD, 13f));
		nameRow.add(name);
		nameRow.add(chartHintIcon());
		row.add(nameRow, BorderLayout.CENTER);

		JPanel stats = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
		stats.setOpaque(false);
		if (rating != null)
		{
			stats.add(ratingScoreLabel(rating));
		}
		if (isBuy)
		{
			long perEa = s.quantity > 0 ? s.expectedProfit / s.quantity : s.expectedProfit;
			JLabel edge = new JLabel(perEa > 0 ? "+" + QuantityFormatter.quantityToStackSize(perEa) + "/ea" : "—");
			edge.setForeground(perEa > 0 ? POSITIVE : ColorScheme.LIGHT_GRAY_COLOR);
			edge.setFont(edge.getFont().deriveFont(Font.BOLD, 12f));
			stats.add(edge);
		}
		else
		{
			JLabel price = new JLabel(QuantityFormatter.quantityToStackSize(s.price) + " gp");
			price.setForeground(accent);
			price.setFont(price.getFont().deriveFont(Font.BOLD, 12f));
			stats.add(price);
		}
		row.add(stats, BorderLayout.EAST);
		p.add(row);

		wireOpenChart(p, ColorScheme.DARKER_GRAY_COLOR, s.name);
		wireSuggestionContextMenu(p, s);
		p.setToolTipText(verb(s.type) + " " + QuantityFormatter.quantityToStackSize(s.quantity) + " " + s.name + " at "
			+ QuantityFormatter.quantityToStackSize(s.price) + " gp — " + s.reason
			+ " (right-click for skip/never-recommend)");
		topCardWrap.add(p, BorderLayout.CENTER);
	}

	/** Skip/Block used to be permanent buttons on this card; they're right-
	 *  click now so the compact row doesn't need to make room for them. */
	private void wireSuggestionContextMenu(JPanel row, Advisor.Suggestion s)
	{
		row.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mousePressed(MouseEvent e) { maybeShow(e); }

			@Override
			public void mouseReleased(MouseEvent e) { maybeShow(e); }

			private void maybeShow(MouseEvent e)
			{
				if (!e.isPopupTrigger())
				{
					return;
				}
				JPopupMenu menu = new JPopupMenu();
				JMenuItem skip = new JMenuItem("Skip for this session");
				skip.addActionListener(a -> actions.skip(s.itemId));
				JMenuItem block = new JMenuItem("Never recommend " + s.name);
				block.addActionListener(a -> actions.block(s.name));
				menu.add(skip);
				menu.add(block);
				menu.show(row, e.getX(), e.getY());
			}
		});
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
		b.setFont(b.getFont().deriveFont(11f));
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
