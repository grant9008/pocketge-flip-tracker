package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
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
import javax.swing.Icon;
import javax.swing.ImageIcon;
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
	private static final Color TEAL = new Color(0x26, 0xA6, 0x9A);
	private static final Color ADJUST = new Color(0xFF, 0x9F, 0x43);
	private static final Color HOVER_BG = new Color(0x3A, 0x33, 0x28);
	// pocketge.com's own "Gilded & Obsidian" palette (--bg-panel / --text-main
	// in index.html) — warmer than RuneLite's neutral ColorScheme grays, so
	// these cards read as PocketGE's own rather than generic plugin chrome.
	private static final Color OBSIDIAN_BG = new Color(0x1B, 0x18, 0x15);
	private static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);
	private static final int ICON_SIZE = 32;
	private static final int MINI_ICON_SIZE = 22;
	private static final Icon CHART_ICON = buildChartIcon(1f);
	private static final Icon CHART_ICON_LARGE = buildChartIcon(1.45f);

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
	/** Top box — always shows something: whatever Favorites row was last
	 *  clicked, or (by default / once cleared) a static preview of the #1
	 *  suggestion. Does not cycle — that's the bottom box's job. */
	private final JPanel inspectionWrap = new JPanel(new BorderLayout());
	private final JPanel geContextWrap = new JPanel(new BorderLayout());
	/** Bottom box — always shows the current pick from Advisor.advise()'s
	 *  full ranked list (adjust nudges, then bank/inventory sells, then
	 *  buys) and cycles through the rest via its own Next arrow. */
	private final JPanel suggestionWrap = new JPanel(new BorderLayout());

	private List<Advisor.Suggestion> currentSuggestions = List.of();
	private Map<Integer, AnalystRating.Grade> currentRatings = Map.of();
	private int suggestionIndex = 0;
	private Set<Integer> favoriteIds = Set.of();
	private Settings settings = new Settings();
	/** Whatever item is currently in an open GE offer screen — its own
	 *  "BUYING NOW"/"SELLING NOW" section, separate from (and below) the
	 *  inspection card, since the two answer different questions ("what am
	 *  I doing right now" vs "what's our pick"). Null itemId means nothing
	 *  open. */
	private Integer geContextItemId = null;
	private String geContextName = "";
	private boolean geContextIsBuy = true;
	private long geContextPrice = 0;
	/** Whichever Favorites row was last clicked — takes over the
	 *  inspection card until another row is clicked or dismissed with its
	 *  own close button, at which point it reverts to previewing the #1
	 *  suggestion. Independent of geContext (the GE offer screen) and of
	 *  the Favorites list itself, which never changes when this is set. */
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
		// Lives at the bottom of the whole sidebar now (see MainPanel, next
		// to the "Open PocketGE" link) instead of up here — not added to
		// `north` itself, just built and kept as a field so MainPanel can
		// place the actual button wherever it wants.

		status.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		status.setFont(status.getFont().deriveFont(11.5f));
		north.add(status, BorderLayout.CENTER);
		add(north, BorderLayout.NORTH);

		inspectionWrap.setOpaque(false);
		inspectionWrap.setBorder(BorderFactory.createEmptyBorder(10, 0, 0, 0));

		geContextWrap.setOpaque(false);

		suggestionWrap.setOpaque(false);
		suggestionWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		center.add(inspectionWrap);
		center.add(geContextWrap);
		center.add(suggestionWrap);
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

	/** The gear/settings button itself, so MainPanel can place it at the
	 *  bottom of the sidebar (next to the website link) instead of here. */
	public JButton settingsButton()
	{
		return gearBtn;
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
		// bank/inventory sells, then buys) — the bottom box cycles through
		// that single order rather than splitting "our pick" from
		// "everything else" into two competing cards.
		currentSuggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
		if (suggestionIndex >= currentSuggestions.size())
		{
			suggestionIndex = 0;
		}
		renderInspection();
		renderSuggestion();

		revalidate();
		repaint();
	}

	/** Called when a Favorites row is clicked. Takes over the inspection
	 *  card until another row is clicked or dismissed with its own close
	 *  button, same relationship the website's ticker header has to its own
	 *  flip-finder card. Pass null to dismiss (reverts to the "click a
	 *  favorite to inspect it" prompt). */
	public void setSelectedItem(FavoritesPanel.Row r)
	{
		this.selectedFavorite = r;
		renderInspection();
	}

	/** The top box: whatever Favorites row is being inspected — nothing more.
	 *  It used to default to previewing the #1 suggestion when nothing was
	 *  selected, but that just showed the exact same card as the box below
	 *  it (same item, same everything) — a real "1 too many" bug, not a
	 *  design choice. A lightweight prompt instead keeps the box always
	 *  present (never collapses to zero height) without duplicating the
	 *  suggestion box's content. */
	private void renderInspection()
	{
		inspectionWrap.removeAll();
		inspectionWrap.add(selectedFavorite != null ? renderSelectedCard() : renderInspectionPrompt(), BorderLayout.CENTER);
		inspectionWrap.revalidate();
		inspectionWrap.repaint();
	}

	private JPanel renderSelectedCard()
	{
		final FavoritesPanel.Row r = selectedFavorite;
		String actionText = r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : null;
		JButton close = smallBtn("✕", "Stop inspecting — show the top suggestion again", e -> setSelectedItem(null));

		// potentialProfit needs a valid GE buy limit to be nonzero (it's
		// edge * limit) — items not currently held can still be missing
		// that lookup or just have it come back 0, which used to leave this
		// whole row blank even though we already have real buy/sell prices.
		// Fall back to the same per-unit edge a BUY suggestion would show.
		Long profitValue = null;
		String profitSuffix = "gp profit";
		if (r.potentialProfit != 0)
		{
			profitValue = r.potentialProfit;
		}
		else if (r.targetBuy > 0 && r.targetSell > 0)
		{
			profitValue = r.targetSell - r.targetBuy - FlipTracker.taxPerItem(r.targetSell, r.id);
			profitSuffix = "gp/ea if you bought now";
		}
		return buildCompactCard(GOLD, true, r.id, r.name, actionText, profitValue, profitSuffix, r.rating, close);
	}

	/** Sized to match renderSelectedCard()'s "large" card so the inspection
	 *  box never visibly changes size depending on what it's showing. */
	private JPanel renderInspectionPrompt()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(11, 14, 11, 11)));
		JLabel label = new JLabel("<html>Click a favorite below to inspect it here.</html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(label.getFont().deriveFont(13.5f));
		p.add(label, BorderLayout.CENTER);
		return p;
	}

	/** Same shell as renderInspectionPrompt() — a bare, unboxed "No
	 *  suggestions right now." used to be the entire empty state, which read
	 *  as broken rather than "nothing to show yet". Always present now, and
	 *  actually tells you what to do about it instead of just reporting
	 *  nothing. */
	private JPanel renderSuggestionPrompt()
	{
		JPanel p = new JPanel(new BorderLayout());
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, ColorScheme.MEDIUM_GRAY_COLOR),
			BorderFactory.createEmptyBorder(11, 14, 11, 11)));
		String text = !settings.advisorOn
			? "Turn on the Advisor (⚙ above) to get live buy/sell suggestions based on your gp and holdings."
			: "No suggestions right now — checking your cash (bank + inventory) and held stacks against live prices. Suggestions appear here once something clears the bar.";
		JLabel label = new JLabel("<html>" + text + "</html>");
		label.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		label.setFont(label.getFont().deriveFont(13f));
		p.add(label, BorderLayout.CENTER);
		return p;
	}

	/** Called whenever the plugin detects (or clears) an open GE offer
	 *  screen. Renders into its own section (geContextWrap), separate from
	 *  and below the inspection card — the two answer different questions
	 *  and both stay visible together rather than one replacing the other. */
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
	 *  screen open on right now — same card shell as the other boxes, just
	 *  labeled and colored to read as "here's your price", not "here's our
	 *  pick". The fill button behaves exactly like the suggestion row's —
	 *  same live-fill-or-copy action. */
	private void renderGeContextCard()
	{
		final int itemId = geContextItemId;
		final String name = geContextName;
		final boolean isBuy = geContextIsBuy;
		final long price = geContextPrice;

		String actionText = (isBuy ? "Buying for " : "Selling for ") + QuantityFormatter.quantityToStackSize(price) + " gp";
		JButton fillBtn = smallBtn("⧉", "Fill " + QuantityFormatter.quantityToStackSize(price)
			+ " gp into the GE price box if it's open, or copy it to paste in", e -> actions.fillGePrice(price));
		boolean fav = favoriteIds.contains(itemId);
		JButton favBtn = smallBtn(fav ? "★" : "☆", fav ? "Remove " + name + " from favorites" : "Add " + name + " to favorites",
			e -> actions.toggleFavorite(itemId, name));
		JPanel p = buildCompactCard(isBuy ? GOLD : TEAL, false, itemId, name, actionText, null, null, null, null, fillBtn, favBtn);
		p.setToolTipText((isBuy ? "Live wiki insta-sell price" : "Live wiki insta-buy price") + " for " + name
			+ " — click ⧉ to fill it into the open GE offer.");
		geContextWrap.setBorder(BorderFactory.createEmptyBorder(0, 0, 8, 0));
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
		// The number on its own means nothing without context — same
		// complaint would apply to the website's gauge if it had no label
		// either. Spell it out on hover: what it is, what this particular
		// score means, and the 0-100 scale it lives on.
		badge.setToolTipText("<html>Analyst Rating: <b>" + rating.score + "/100 — " + rating.label.text + "</b><br>"
			+ "How this item's live price compares to its 24h typical (0 = Strong Sell, 100 = Strong Buy).</html>");
		return badge;
	}

	/** The bottom box: whatever Advisor.advise() ranks highest, cycling
	 *  through the rest — adjust nudges, bank/inventory sells, and buys, in
	 *  that same ranked order — via its own Next arrow, one at a time. */
	private void renderSuggestion()
	{
		suggestionWrap.removeAll();
		if (currentSuggestions.isEmpty())
		{
			suggestionWrap.add(renderSuggestionPrompt(), BorderLayout.CENTER);
			suggestionWrap.revalidate();
			suggestionWrap.repaint();
			return;
		}

		Advisor.Suggestion s = currentSuggestions.get(suggestionIndex);
		AnalystRating.Grade rating = currentRatings.get(s.itemId);
		Color accent = accent(s.type);
		String actionText = verb(s.type).replace(":", "") + " " + QuantityFormatter.quantityToStackSize(s.quantity)
			+ " for " + QuantityFormatter.quantityToStackSize(s.price) + " gp";

		JButton fillBtn = smallBtn("⧉", "Fill this suggestion's quantity/price into the GE box if it's open, or copy the price to paste in", e ->
		{
			actions.fillGeQuantity(s.quantity);
			actions.fillGePrice(s.price);
		});
		boolean fav = favoriteIds.contains(s.itemId);
		JButton favBtn = smallBtn(fav ? "★" : "☆", fav ? "Remove " + s.name + " from favorites" : "Add " + s.name + " to favorites",
			e -> actions.toggleFavorite(s.itemId, s.name));
		List<JButton> trailing = new ArrayList<>();
		trailing.add(fillBtn);
		trailing.add(favBtn);
		// SELL suggestions specifically get a visible "Hold" button — right-
		// click "Never recommend" already does the exact same thing, but it's
		// invisible unless you already know to look for it, and "I own this
		// and don't want to keep being told to sell it" is common enough to
		// deserve its own button rather than living only in a context menu.
		if (s.type == Advisor.Suggestion.Type.SELL)
		{
			trailing.add(smallBtn("Hold", "Stop suggesting you sell " + s.name + " — never recommend it again",
				e -> actions.block(s.name)));
		}
		if (currentSuggestions.size() > 1)
		{
			trailing.add(smallBtn("↻", "Show the next suggestion", e ->
			{
				suggestionIndex = (suggestionIndex + 1) % currentSuggestions.size();
				renderSuggestion();
			}));
		}

		Long profitValue = s.expectedProfit != 0 ? s.expectedProfit : null;
		JPanel p = buildCompactCard(accent, false, s.itemId, s.name, actionText, profitValue, "gp profit",
			rating, null, trailing.toArray(new JButton[0]));
		wireSuggestionContextMenu(p, s);
		p.setToolTipText(verb(s.type) + " " + QuantityFormatter.quantityToStackSize(s.quantity) + " " + s.name + " at "
			+ QuantityFormatter.quantityToStackSize(s.price) + " gp — " + s.reason
			+ " (right-click for skip/never-recommend)");
		suggestionWrap.add(p, BorderLayout.CENTER);
		suggestionWrap.revalidate();
		suggestionWrap.repaint();
	}

	/** Shared shell for every card in this panel — colored left accent
	 *  (pocketge.com's own obsidian background behind it, not RuneLite's
	 *  neutral gray), an icon+name headline row (with an optional close
	 *  button), an optional action line (the "Buy N for X gp" / "958 gp"
	 *  part), and an optional row for profit / rating / action buttons.
	 *  Splitting name and action onto their own lines — rather than one
	 *  combined "Buy N Item for X gp" string — is deliberate: a single
	 *  JLabel doesn't wrap, so a longer item name (Helm of neitiznot, say)
	 *  combined with the price used to push the price itself past the
	 *  card's edge, clipped and invisible. Each line now only ever needs to
	 *  fit ONE piece of information. {@code large} scales the icon/fonts/
	 *  padding up a notch — used only for the top inspection card, so the
	 *  one box the player is meant to look at first/most reads as more
	 *  prominent than the (more numerous, more disposable) suggestion/
	 *  GE-context cards beneath it. */
	private JPanel buildCompactCard(Color accent, boolean large, int itemId, String itemName, String actionText,
		Long profitValue, String profitSuffix, AnalystRating.Grade rating, JButton closeBtn, JButton... trailingBtns)
	{
		final int iconSize = large ? 30 : MINI_ICON_SIZE;
		final float nameSize = large ? 16f : 13f;
		final float actionSize = large ? 14f : 12.5f;
		final float profitSize = large ? 14f : 12f;
		final Insets padding = large ? new Insets(9, 12, 9, 11) : new Insets(7, 10, 7, 7);

		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, accent),
			BorderFactory.createEmptyBorder(padding.top, padding.left, padding.bottom, padding.right)));

		JPanel row1 = new JPanel(new BorderLayout(6, 0));
		row1.setOpaque(false);
		row1.add(iconLabel(itemId, iconSize), BorderLayout.WEST);
		// Just the name in CENTER — a lone JLabel truncates safely via
		// truncateName() when it's the squeezed slot. The chart button used
		// to share this FlowLayout with the name; that worked fine until a
		// close button (EAST, always gets its full preferred width) showed
		// up too on the large inspection card, squeezing CENTER enough that
		// the chart button rendered partially clipped — only the tail end of
		// its icon visible, cut off by nameRow's own narrowed bounds. Moving
		// it into the same always-full-width EAST slot as the close button
		// fixes that outright rather than fighting FlowLayout for room.
		JLabel nameLabel = new JLabel(truncateName(itemName));
		nameLabel.setToolTipText(itemName);
		nameLabel.setForeground(TEXT_MAIN);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, nameSize));
		row1.add(nameLabel, BorderLayout.CENTER);
		JPanel eastWrap = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
		eastWrap.setOpaque(false);
		eastWrap.add(chartButton(itemName, large));
		if (closeBtn != null)
		{
			eastWrap.add(closeBtn);
		}
		row1.add(eastWrap, BorderLayout.EAST);
		p.add(row1);

		if (actionText != null)
		{
			p.add(Box.createVerticalStrut(2));
			JLabel actionLabel = new JLabel(actionText);
			actionLabel.setForeground(accent);
			actionLabel.setFont(actionLabel.getFont().deriveFont(Font.BOLD, actionSize));
			actionLabel.setAlignmentX(0f);
			p.add(actionLabel);
		}

		if (profitValue != null || rating != null || trailingBtns.length > 0)
		{
			p.add(Box.createVerticalStrut(3));
			JPanel row3 = new JPanel(new BorderLayout(6, 0));
			row3.setOpaque(false);
			if (profitValue != null)
			{
				JLabel profitLabel = new JLabel((profitValue >= 0 ? "+" : "")
					+ QuantityFormatter.quantityToStackSize(profitValue) + " " + profitSuffix);
				profitLabel.setForeground(profitValue >= 0 ? POSITIVE : NEGATIVE);
				profitLabel.setFont(profitLabel.getFont().deriveFont(Font.BOLD, profitSize));
				row3.add(profitLabel, BorderLayout.WEST);
			}
			JPanel row3Right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
			row3Right.setOpaque(false);
			if (rating != null)
			{
				row3Right.add(ratingScoreLabel(rating));
			}
			for (JButton b : trailingBtns)
			{
				row3Right.add(b);
			}
			row3.add(row3Right, BorderLayout.EAST);
			p.add(row3);
		}

		wireOpenChart(p, OBSIDIAN_BG, itemName);
		return p;
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

	/** A real button (not just a hint) to open this card's item on the live
	 *  chart, mirroring Flipping Copilot's own graph icon next to item
	 *  names — gold and sized up a notch from the rest of the card's chrome
	 *  so it reads as an obvious, clickable affordance rather than a subtle
	 *  decoration. The whole card is already clickable via wireOpenChart;
	 *  this gives the same action its own explicit, more discoverable
	 *  target too. Drawn with Java2D rather than an emoji glyph — emoji
	 *  font fallback support is inconsistent across the JREs RuneLite runs
	 *  on, so a relied-on affordance icon needs to render the same
	 *  everywhere. */
	private JButton chartButton(String itemName, boolean large)
	{
		JButton b = new JButton(large ? CHART_ICON_LARGE : CHART_ICON);
		b.setToolTipText("Open the live " + itemName + " chart on PocketGE");
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 4, 2, 4));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e -> LinkBrowser.browse("https://pocketge.com/?q=" + urlEncode(itemName)));
		return b;
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
