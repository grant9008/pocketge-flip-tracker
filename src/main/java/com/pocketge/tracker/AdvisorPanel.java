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
	private static final Icon SHARE_ICON = buildShareIcon();

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
	/** The status strip, hidden whenever the status text is empty. */
	private JPanel statusBar;
	/** Top box — always shows something: whatever Favorites row was last
	 *  clicked, or (by default / once cleared) a static preview of the #1
	 *  suggestion. Does not cycle — that's the bottom box's job. */
	private final JPanel inspectionWrap = new JPanel(new BorderLayout());
	private final JPanel geContextWrap = new JPanel(new BorderLayout());
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
		public long profit;
		public boolean hasTrackedCost = true;
		public String note;         // optional one-liner (why this, or what capped it)
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
		gearBtn.addActionListener(e -> showSettingsPopup());
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

		inspectionWrap.setOpaque(false);
		inspectionWrap.setBorder(BorderFactory.createEmptyBorder(2, 0, 0, 0));

		geContextWrap.setOpaque(false);

		recommendationWrap.setOpaque(false);
		recommendationWrap.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		center.add(inspectionWrap);
		center.add(geContextWrap);
		// Order matches the website sidebar exactly: Potential Profit,
		// Analyst Rating, Recommended Flip, in that order, all above
		// Favorites (see MainPanel's scrollContent ordering).
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
		status.setText(s != null ? s : "");
		statusBar.setVisible(s != null && !s.isEmpty());
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

		// Kept for the bank/GE overlays and the inspection card's rating
		// lookup. The sidebar itself no longer renders this ranked list
		// directly: it splits into "sell what you hold" and "deploy your
		// cash", which are the two decisions actually being made.
		currentSuggestions = suggestions != null ? new ArrayList<>(suggestions) : new ArrayList<>();
		renderInspection();
		renderRecommendation();

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
		this.selectedFavoriteId = r != null ? r.id : -1;
		renderInspection();
	}

	/** The top box: whatever Favorites row is being inspected — nothing more.
	 *  It used to default to previewing the #1 suggestion when nothing was
	 *  selected, but that just showed the exact same card as the box below
	 *  it (same item, same everything) — a real "1 too many" bug, not a
	 *  design choice. A lightweight prompt instead keeps the box always
	 *  present (never collapses to zero height) without duplicating the
	 *  suggestion box's content. */
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
				renderInspection();
				return;
			}
		}
		// Unfavorited while being inspected — drop back to the prompt rather
		// than keeping a card for something no longer on the list.
		selectedFavorite = null;
		selectedFavoriteId = -1;
		renderInspection();
	}

	private void renderInspection()
	{
		inspectionWrap.removeAll();
		// Only takes up room when there's something to show: logged out (the
		// message explaining why everything else is empty) or a favorite
		// actually clicked. The old permanent "click a favorite" prompt was
		// a reserved empty rectangle the rest of the time.
		inspectionWrap.setVisible(!loggedIn || selectedFavorite != null);
		if (!loggedIn)
		{
			inspectionWrap.add(loginPrompt(), BorderLayout.NORTH);
		}
		else if (selectedFavorite != null)
		{
			inspectionWrap.add(renderSelectedCard(), BorderLayout.NORTH);
		}
		inspectionWrap.revalidate();
		inspectionWrap.repaint();
	}

	private JPanel renderSelectedCard()
	{
		final FavoritesPanel.Row r = selectedFavorite;
		// The favorites row already pulses its border for this (see
		// FavoritesPanel.wirePulse) but that glow doesn't carry over to the
		// inspection card once you click in — say it in words here too,
		// same as the website's own ▲ 5D / ▼ 5D badge, rather than relying
		// on remembering which row was glowing before you clicked it.
		final String extremeBadge = r.atHigh5d ? "▲ 5D HIGH" : r.atLow5d ? "▼ 5D LOW" : null;
		final String priceText = r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : null;
		JButton close = smallBtn("✕", "Stop inspecting — show the top suggestion again", e -> setSelectedItem(null));

		final long edge = (r.targetBuy > 0 && r.targetSell > 0)
			? r.targetSell - r.targetBuy - FlipTracker.taxPerItem(r.targetSell, r.id) : 0;

		/* A spread narrower than the 2% tax makes potentialProfit (edge x
		   the 4h limit) a large NEGATIVE number, and this card used to
		   headline it — a Diamond necklace with a 9 gp spread and a 39 gp
		   tax rendered as a flat "-666K gp profit", which reads like the
		   item lost you money rather than "there's no margin here today".
		   Only ever show a profit figure when there IS one; when there
		   isn't, say that in the action line instead. */
		Long profitValue = null;
		String profitSuffix = "gp profit";
		if (edge > 0)
		{
			if (r.potentialProfit > 0)
			{
				profitValue = r.potentialProfit;
				profitSuffix = "gp profit at the 4h limit";
			}
			else
			{
				profitValue = edge;
				profitSuffix = "gp/ea if you bought now";
			}
		}

		final String marginNote = edge > 0 ? null : "No margin after tax right now";
		String actionText = extremeBadge != null && priceText != null ? extremeBadge + "   ·   " + priceText
			: extremeBadge != null ? extremeBadge : priceText;
		if (marginNote != null)
		{
			actionText = actionText != null ? actionText + "   ·   " + marginNote : marginNote;
		}
		return buildCompactCard(GOLD, true, r.id, r.name, actionText, profitValue, profitSuffix, r.rating, close);
	}

	/** Same card shell as everything else so the sidebar reads as "waiting"
	 *  rather than "empty" before login. */
	private JPanel loginPrompt()
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
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
		JLabel sub = new JLabel("to start receiving recommendations");
		sub.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		sub.setFont(sub.getFont().deriveFont(12f));
		sub.setAlignmentX(0f);
		sub.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		p.add(sub);
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
		geContextWrap.add(p, BorderLayout.NORTH);
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

	/** The full gauge — mirroring the website's own Analyst Rating module —
	 *  for the top inspection card only. A bare "49" badge meant nothing
	 *  without the label/scale next to it; this is the same info the
	 *  compact badge already carried, just actually legible: an eyebrow +
	 *  "?" explainer, the grade in words, a 5-band Strong Sell → Strong Buy
	 *  bar with a marker at the exact score, and the number underneath. */
	private JPanel buildRatingGauge(AnalystRating.Grade rating)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setOpaque(false);
		p.setAlignmentX(0f);

		JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		header.setOpaque(false);
		header.setAlignmentX(0f);
		JLabel eyebrow = new JLabel("ANALYST RATING");
		eyebrow.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		eyebrow.setFont(eyebrow.getFont().deriveFont(Font.BOLD, 10f));
		header.add(eyebrow);
		JLabel help = new JLabel("?");
		help.setToolTipText("Condenses live price vs. today's 24h typical into one call, Strong Sell to Strong Buy. Advisory only — Target Buy/Sell are the actual trade.");
		help.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		help.setFont(help.getFont().deriveFont(Font.BOLD, 9f));
		help.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
		help.setOpaque(true);
		help.setBackground(OBSIDIAN_BG);
		help.setHorizontalAlignment(SwingConstants.CENTER);
		help.setPreferredSize(new Dimension(13, 13));
		header.add(help);
		p.add(header);

		JLabel labelText = new JLabel(rating.label.text);
		labelText.setForeground(ratingColor(rating.label));
		labelText.setFont(labelText.getFont().deriveFont(Font.BOLD, 15f));
		labelText.setAlignmentX(0f);
		labelText.setBorder(BorderFactory.createEmptyBorder(1, 0, 4, 0));
		p.add(labelText);

		p.add(ratingGaugeBar(rating.score));

		JLabel scoreText = new JLabel("Score " + rating.score + "/100");
		scoreText.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		scoreText.setFont(scoreText.getFont().deriveFont(10f));
		scoreText.setAlignmentX(0f);
		scoreText.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
		p.add(scoreText);

		return p;
	}

	/** 5 bands (matching AnalystRating.labelFor's exact 0/20/40/60/80
	 *  breakpoints) with a marker triangle at the precise score — same
	 *  Strong Sell-to-Strong Buy scale the website's needle gauge shows,
	 *  just drawn directly rather than as a CSS gradient. */
	private JPanel ratingGaugeBar(int score)
	{
		final AnalystRating.Label[] bands = {
			AnalystRating.Label.STRONG_SELL, AnalystRating.Label.SELL, AnalystRating.Label.HOLD,
			AnalystRating.Label.BUY, AnalystRating.Label.STRONG_BUY
		};
		JPanel bar = new JPanel()
		{
			@Override
			protected void paintComponent(Graphics g)
			{
				super.paintComponent(g);
				final Graphics2D g2 = (Graphics2D) g;
				g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
				final int w = getWidth(), h = 6;
				final int segW = w / bands.length;
				for (int i = 0; i < bands.length; i++)
				{
					g2.setColor(ratingColor(bands[i]));
					g2.fillRoundRect(i * segW, 0, segW - 1, h, 2, 2);
				}
				final int markerX = Math.max(2, Math.min(w - 2, Math.round(score / 100f * w)));
				g2.setColor(Color.WHITE);
				final int[] xs = { markerX - 4, markerX + 4, markerX };
				final int[] ys = { h + 6, h + 6, h + 1 };
				g2.fillPolygon(xs, ys, 3);
			}
		};
		bar.setOpaque(false);
		bar.setAlignmentX(0f);
		bar.setPreferredSize(new Dimension(160, 12));
		bar.setMaximumSize(new Dimension(Short.MAX_VALUE, 12));
		return bar;
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
		   with the content floating in it. Same fix BankStatsPanel already
		   carries for the same reason. */
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
			wrap.add(body);
		}
		return wrap;
	}

	/** A short placeholder for a section with nothing to show yet, in the
	 *  same bordered card shell as a real one. */
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
		renderInspection();
		renderRecommendation();
	}

	/** Toolbar Share: snapshot whatever idea is currently on screen — the
	 *  inspected favorite if there is one, else the top suggestion. */
	public void shareCurrentIdea()
	{
		final FavoritesPanel.Row r = selectedFavorite;
		if (r != null)
		{
			final Long profit = r.potentialProfit != 0 ? r.potentialProfit : null;
			copyImageToClipboard(buildShareImage(r.id, r.name,
				r.price > 0 ? QuantityFormatter.quantityToStackSize(r.price) + " gp" : null,
				profit, "gp profit", r.rating));
			return;
		}
		if (!currentSuggestions.isEmpty())
		{
			final Advisor.Suggestion s = currentSuggestions.get(0);
			copyImageToClipboard(buildShareImage(s.itemId, s.name,
				verb(s.type).replace(":", "") + " " + QuantityFormatter.quantityToStackSize(s.quantity)
					+ " for " + QuantityFormatter.quantityToStackSize(s.price) + " gp",
				s.expectedProfit != 0 ? s.expectedProfit : null, "gp profit",
				currentRatings.get(s.itemId)));
		}
	}

	/** Called whenever the plugin recomputes the recommendation stream —
	 *  sells out of your bank/inventory and buys sized to your liquid cash,
	 *  already ranked. Keeps your place in the list across refreshes where it
	 *  can, so a background tick doesn't yank the card you were reading. */
	public void setRecommendations(List<Rec> recs)
	{
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
		recommendationWrap.setVisible(loggedIn);
		if (recIndex >= recommendations.size())
		{
			recIndex = 0;
		}
		final JPanel body = recommendations.isEmpty()
			? emptyMiniBody(settings.advisorOn
				? "Checking your cash and stacks against live prices\u2026"
				: "Turn the Advisor on (\u2699 above) to get recommendations.")
			: recommendationBody(recommendations.get(recIndex));
		recommendationWrap.add(collapsibleSection("RECOMMENDED FLIP", null, recommendationOpen,
			() -> { recommendationOpen = !recommendationOpen; renderRecommendation(); }, body), BorderLayout.NORTH);
		recommendationWrap.revalidate();
		recommendationWrap.repaint();
	}

	/** One idea, Copilot-shaped: what to do, at what price, what it makes,
	 *  and the three controls that matter — fill it, move on, or stop being
	 *  told about this item. */
	private JPanel recommendationBody(Rec r)
	{
		JPanel p = new JPanel();
		p.setLayout(new BoxLayout(p, BoxLayout.Y_AXIS));
		p.setBackground(OBSIDIAN_BG);
		p.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(0, 2, 0, 0, r.sell ? TEAL : GOLD),
			BorderFactory.createEmptyBorder(9, 12, 9, 10)));

		JPanel head = new JPanel(new BorderLayout(6, 0));
		head.setOpaque(false);
		head.setAlignmentX(0f);
		head.add(iconLabel(r.itemId, 26), BorderLayout.WEST);

		JPanel text = new JPanel();
		text.setLayout(new BoxLayout(text, BoxLayout.Y_AXIS));
		text.setOpaque(false);
		JLabel line1 = new JLabel((r.sell ? "Sell " : "Buy ")
			+ QuantityFormatter.quantityToStackSize(r.quantity) + " \u00D7 " + truncateName(r.name));
		line1.setToolTipText(r.name);
		line1.setForeground(TEXT_MAIN);
		line1.setFont(line1.getFont().deriveFont(Font.BOLD, 13.5f));
		line1.setAlignmentX(0f);
		text.add(line1);
		// On a held stack the pair of prices IS the decision — what it fetches
		// against what it cost. Omitted entirely when nothing was tracked
		// rather than inventing a basis.
		JLabel line2 = new JLabel("at " + QuantityFormatter.quantityToStackSize(r.unitPrice) + " gp ea"
			+ (r.sell && r.unitCost > 0 ? "  \u00B7  bought " + QuantityFormatter.quantityToStackSize(r.unitCost) : ""));
		line2.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
		line2.setFont(line2.getFont().deriveFont(11f));
		line2.setAlignmentX(0f);
		text.add(line2);
		head.add(text, BorderLayout.CENTER);
		p.add(head);

		p.add(Box.createVerticalStrut(5));
		// A sell with no tracked purchase reports the stack's sale value, not a
		// gain — calling that profit is how a long-held stack claims a fake win.
		JLabel profit = new JLabel((r.profit >= 0 ? "+" : "")
			+ QuantityFormatter.quantityToStackSize(r.profit) + " gp "
			+ (r.sell && !r.hasTrackedCost ? "value (no purchase tracked)" : "profit"));
		profit.setForeground(r.profit >= 0 ? POSITIVE : NEGATIVE);
		profit.setFont(profit.getFont().deriveFont(Font.BOLD, 15f));
		profit.setAlignmentX(0f);
		p.add(profit);

		p.add(Box.createVerticalStrut(7));
		JPanel controls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		controls.setOpaque(false);
		controls.setAlignmentX(0f);
		controls.add(smallBtn("\u29C9", "Fill this quantity and price into the GE box if it's open", e ->
		{
			actions.fillGeQuantity(r.quantity);
			actions.fillGePrice(r.unitPrice);
		}));
		if (recommendations.size() > 1)
		{
			controls.add(smallBtn("Next \u203A", "Show the next recommendation", e ->
			{
				recIndex = (recIndex + 1) % recommendations.size();
				renderRecommendation();
			}));
		}
		controls.add(smallBtn("Hold", "Stop recommending " + r.name + " until you unblock it",
			e -> actions.block(r.name)));
		p.add(controls);

		if (recommendations.size() > 1)
		{
			JLabel pos = new JLabel((recIndex + 1) + " of " + recommendations.size());
			pos.setForeground(ColorScheme.LIGHT_GRAY_COLOR);
			pos.setFont(pos.getFont().deriveFont(10f));
			pos.setAlignmentX(0f);
			pos.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));
			p.add(pos);
		}
		if (r.note != null)
		{
			p.setToolTipText(r.note);
		}
		return p;
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
		// Only the top inspection card gets a share button (large is only
		// ever true there) — matches the website's own share card, same
		// name/price/profit/rating spread, but as a clipboard image instead
		// of a canvas, since Swing has no canvas-to-clipboard-image
		// equivalent in the browser sense — Java's own image Transferable
		// does the same job.
		if (large)
		{
			eastWrap.add(shareButton(itemId, itemName, actionText, profitValue, profitSuffix, rating));
		}
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

		// The full gauge (label + bar, not just a bare score) only fits
		// comfortably on the large inspection card — everywhere else it's
		// still the compact badge in row3, same as before.
		if (profitValue != null || (rating != null && !large) || trailingBtns.length > 0)
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
			if (rating != null && !large)
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

		if (large && rating != null)
		{
			p.add(Box.createVerticalStrut(6));
			p.add(buildRatingGauge(rating));
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

	/** A hover tint on the whole card, nothing more — clicking anywhere on
	 *  the card used to also open the browser, which meant tapping the
	 *  profit/rating text just to read it (or, now that this card has a
	 *  Share and a favorite-toggle button too, missing one of those by a
	 *  few pixels) fired off a browser tab you didn't ask for. Opening the
	 *  chart is chartButton's job specifically now, not the card's. */
	private void wireOpenChart(JPanel row, Color normalBg, String itemName)
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

	/** Builds a clean, data-full card image for this item — icon, name,
	 *  price/action line, profit, Analyst Rating — and copies it straight to
	 *  the system clipboard so it can be pasted directly into a Reddit/
	 *  Discord post, mirroring the website's own Share button (which
	 *  renders the same information to a canvas and does the same "copy an
	 *  image" flow). Only on the top inspection card — the one box worth
	 *  turning into a shareable snapshot. */
	private JButton shareButton(int itemId, String itemName, String actionText,
		Long profitValue, String profitSuffix, AnalystRating.Grade rating)
	{
		JButton b = new JButton(SHARE_ICON);
		b.setToolTipText("Copy a shareable image of this card (for Reddit/Discord)");
		b.setFocusPainted(false);
		b.setMargin(new Insets(2, 4, 2, 4));
		b.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		b.addActionListener(e ->
		{
			final BufferedImage img = buildShareImage(itemId, itemName, actionText, profitValue, profitSuffix, rating);
			copyImageToClipboard(img);
			final Icon original = b.getIcon();
			b.setText("Copied!");
			b.setIcon(null);
			javax.swing.Timer revert = new javax.swing.Timer(1500, ev -> { b.setText(""); b.setIcon(original); });
			revert.setRepeats(false);
			revert.start();
		});
		return b;
	}

	private static final int SHARE_CARD_W = 640, SHARE_CARD_H = 300;

	private BufferedImage buildShareImage(int itemId, String itemName, String actionText,
		Long profitValue, String profitSuffix, AnalystRating.Grade rating)
	{
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
			g.setColor(GOLD);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
			g.drawString(actionText, 32, y);
			y += 40;
		}
		if (profitValue != null)
		{
			g.setColor(profitValue >= 0 ? POSITIVE : NEGATIVE);
			g.setFont(g.getFont().deriveFont(Font.BOLD, 20f));
			g.drawString((profitValue >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(profitValue) + " " + profitSuffix, 32, y);
			y += 40;
		}
		if (rating != null)
		{
			g.setColor(new Color(0x8A, 0x82, 0x74));
			g.setFont(g.getFont().deriveFont(13f));
			g.drawString("Analyst Rating", 32, y);
			g.setColor(ratingColor(rating.label));
			g.setFont(g.getFont().deriveFont(Font.BOLD, 18f));
			g.drawString(rating.score + " — " + rating.label.text, 32, y + 24);
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
