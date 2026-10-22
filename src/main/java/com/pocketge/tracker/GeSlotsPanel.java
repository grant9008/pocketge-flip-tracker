package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JMenuItem;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import net.runelite.client.game.ItemManager;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Your 8 Grand Exchange slots, laid out 4 x 2 like the clerk's own screen,
 * drawn as the item actually in each one with a fill bar underneath showing
 * how much of that offer has completed.
 *
 * It used to be 8 flat colour swatches. Colour alone told you a slot was
 * busy but not WHAT was in it or how close it was to done — which is the
 * whole reason you'd glance at the GE while doing something else. An empty
 * slot is still just an outline, so "how many slots are free" stays
 * readable at a glance too.
 */
public class GeSlotsPanel extends JPanel
{
	public enum SlotState
	{
		/** Nothing in this slot. */
		EMPTY,
		/** Buying or selling, and the advisor still considers the price
		 *  competitive. */
		ACTIVE_OK,
		/** Buying or selling, but the price has drifted off the market —
		 *  matches the sidebar's own ADJUST_BUY/ADJUST_SELL suggestions. */
		ACTIVE_ADJUST,
		/** Buying, and filling, but every unit loses money against the price
		 *  it sells back at after tax — Advisor.losingBuy. */
		ACTIVE_LOSING,
		/** Fully bought/sold (or cancelled with something to collect) —
		 *  nothing left to do but collect it. */
		READY_COLLECT
	}

	public static class SlotInfo
	{
		public SlotState state = SlotState.EMPTY;
		public String itemName;
		public int itemId;
		/** Units filled so far and the offer's total, for the progress bar.
		 *  Both 0 on an empty slot. */
		public int quantityFilled;
		public int quantityTotal;
		public boolean buy;
		/** True when you have right-clicked this slot and told the plugin you
		 *  are pricing this one yourself. The slot keeps its sprite and its
		 *  fill bar, and it is never boxed for a new price — as on the
		 *  in-game GE box. */
		public boolean adviceSkipped;
		/** What this offer is listed at, and what the plugin thinks it should
		 *  be listed at. Both 0 when there is nothing to say.
		 *
		 *  A red slot used to say only "needs a new price", which is half an
		 *  instruction: it names a problem and not the fix, and the obvious
		 *  reading of a red box is "cancel this". Somebody did, repriced at
		 *  the same number, and was rightly annoyed. The tooltip now names
		 *  the number to move to. */
		public long offerPrice;
		public long targetPrice;
		/** True when the flip no longer clears the tax at the new price —
		 *  i.e. repricing would fill you into a loser. Then the honest advice
		 *  is not "reprice", it is "take a different flip". */
		public boolean noMargin;
		/** What the whole offer makes: for a buy, if it flips at the price
		 *  the card would sell at; for a sell, against what you paid. Null
		 *  when there is nothing honest to say — a sell the plugin never saw
		 *  you buy. Same figure as the in-game box's tooltip. */
		public Long projectedProfit;
		/** A sell: what it has booked so far — see
		 *  GeOfferGridOverlay.SlotView.bookedProfit. */
		public Long bookedProfit;
		/** What the offer is listed at — bid or ask — on any slot holding an
		 *  offer, 0 on an empty one. */
		public long listedPrice;
		/** ACTIVE_LOSING: the most that can be bid for +0. */
		public long breakEven;
		/** What is waiting in this slot's collection box: gp, and units of the
		 *  offer's item (bought, or handed back unsold). -1 when the plugin has
		 *  not seen the box, or saw something in it that is not this offer's. */
		public long collectCoins = -1;
		public int collectItems = -1;
		/** Units filled since you last emptied the box; -1 when unknown. */
		public int sinceCollect = -1;
	}

	/**
	 * "To collect: 461,616 gp — 12 sold since you last collected", or null.
	 *
	 * Asked for on the hover: "add how much money i have able to collect /
	 * how many additional units have sold if any since last collect". The
	 * fill count alone is the whole offer's history; this is the part that
	 * is sitting there for you now.
	 */
	/**
	 * The words around an adjust's price, shared with the in-game box so the
	 * two say the same thing. A buy the market has moved past has two
	 * answers, and the new price may not be worth paying: "it should be
	 * yellow and say adjust price or reconsider trade ... i may not even
	 * want to buy at that price". In the same line — these stay one short
	 * sentence (SlotWordingTest).
	 */
	static String adjustVerb(boolean buy)
	{
		return buy ? "Raise to " : "Lower your ask to ";
	}

	static String adjustTail(boolean buy)
	{
		return buy ? ", or cancel" : "";
	}

	static String collectLine(SlotInfo s)
	{
		if (s == null || (s.collectCoins <= 0 && s.collectItems <= 0))
		{
			return null;
		}
		final StringBuilder sb = new StringBuilder("To collect: ");
		boolean any = false;
		if (s.collectCoins > 0)
		{
			sb.append(String.format("%,d", s.collectCoins)).append(" gp");
			any = true;
		}
		if (s.collectItems > 0)
		{
			sb.append(any ? " + " : "").append(String.format("%,d", s.collectItems))
				.append(s.buy ? " bought" : " unsold");
		}
		if (s.sinceCollect > 0)
		{
			sb.append(" \u2014 ").append(String.format("%,d", s.sinceCollect))
				.append(s.buy ? " bought" : " sold").append(" since you last collected");
		}
		return sb.toString();
	}

	/** What this strip can ask the plugin to do. */
	public interface Actions
	{
		/** Turn "leave this offer's price alone" on or off for one slot. */
		void setSlotAdviceSkipped(int slot, boolean skipped);
		/** Show the item in this slot in the inspection card above — the same
		 *  thing a watchlist row or a finder row does when clicked. The eight
		 *  squares name the items you have the most riding on right now, and
		 *  until this they were the one place in the panel where clicking an
		 *  item did nothing at all. */
		void inspectItem(int itemId, String name);
	}

	/*
	 * Package-private, and the canonical copies: the in-game offer grid and
	 * the settings legend paint or explain these same colours, and a legend
	 * that drifts from what is on screen is worse than no legend.
	 *
	 * THREE meanings, in the game's own language. The Exchange's progress
	 * bar is orange while an offer works, green when it completes and red
	 * when it is aborted — and these squares used green for "priced fine"
	 * and red for "reprice", so a working offer read as finished and one
	 * wanting a new price read as cancelled: "green makes me think it means
	 * is done / red makes me think its done/canceled. + all the colors on
	 * side bar is too additional". That became grey while it works, yellow
	 * when it needs you, green when it is done.
	 *
	 * And then the boxes went: "i dont like the white boxes honestly ...
	 * boxes only appear for change? the yellow box conflicts meaning with
	 * the theme". A grey box round every busy slot was eight borders saying
	 * nothing, and the yellow one sat round SELLS in a theme whose yellow
	 * means buy. So a working slot is now just its picture and its bar, the
	 * bar filling in your buy or sell colour; a slot that wants a new price
	 * is boxed in that same colour; a done one is boxed in the game's green,
	 * which no theme uses. The in-game boxes already worked this way.
	 */
	/** "Priced fine" on a hover: quiet on purpose — most slots, most of the
	 *  time, are this. Never a box. */
	static final Color OK_COLOR = new Color(0x9C, 0x95, 0x8A);
	/** Done, waiting at the Exchange — the game's own green. */
	static final Color COLLECT_COLOR = new Color(0x1F, 0xB8, 0x5C);
	/** A slot you are pricing yourself. The plugin has nothing to say about
	 *  it, so no box — the hover still says whose it is. */
	static final Color MUTED_COLOR = OK_COLOR;
	/* The theme's buy and sell colours, pushed by the plugin with the rest
	   of the theme; High contrast, the default, until then. */
	private static volatile Color buyColour = PocketGeTrackerConfig.ColourTheme.CONTRAST.buy();
	private static volatile Color sellColour = PocketGeTrackerConfig.ColourTheme.CONTRAST.sell();
	/* Package-private, like OK_COLOR and the rest: the settings legend
	   draws a swatch for this border and must read it off the class that
	   paints it. It was the one legend row carrying a retyped colour, and
	   it drifted the moment the slot grid moved to the brand palette. */
	static final Color EMPTY_BORDER = Brand.BORDER_LIGHT;
	private static final Color TRACK = new Color(0x2B, 0x26, 0x21);
	/* 4 across, 2 down — the same arrangement the Grand Exchange clerk's own
	   interface uses. As a single row of 8 in a 225px sidebar each cell got
	   24px, narrower than the 36x32 item sprite it had to draw, so every slot
	   was a squashed thumbnail you couldn't identify without the tooltip.
	   Measured at 4 columns: 49x37 per cell, enough for the sprite at full
	   size — and, more to the point, a slot in the top-left of this panel is
	   the slot in the top-left of the GE window, so you can map one to the
	   other without counting. */
	private static final int COLS = 4;
	private static final int ROWS = 2;
	private static final int CELL = 32;
	private static final int BAR_H = 3;
	/* Both trimmed: the strip sat in more air than a row of eight 32px
	   squares needs, and the search box under it had its own strut on top
	   of this pad. Two pixels between the rows still separates them; three
	   under the strip is enough to keep the bottom bars off the box. */
	private static final int VGAP = 2;
	private static final int BOTTOM_PAD = 3;

	/* The grid's own height. The panel's open height is this plus the
	   chevron's row and the pad, all counted (see getPreferredSize): left
	   out, GridLayout quietly takes them out of the cells and each sprite
	   gets squeezed a few pixels short of its 32px height. */
	private static final int GRID_H = ROWS * (CELL + BAR_H + 2) + (ROWS - 1) * VGAP;

	private final ItemManager itemManager;
	private final Actions actions;
	private final Cell[] cells = new Cell[COLS * ROWS];
	/** The grid with its fold chevron above it, and the one line that
	 *  replaces both when folded. Only one is ever added. */
	private final JPanel openView = new JPanel(new BorderLayout());
	/** The chevron's row over the grid. */
	private final JPanel topRow = new JPanel(new BorderLayout());
	private final JPanel foldedRow = new JPanel(new BorderLayout(4, 0));
	private final JLabel readyNote = new JLabel();
	private final JLabel repriceNote = new JLabel();
	/**
	 * Whether the grid is showing.
	 *
	 * "may as well add a small chevron here like above for flip ideas incase
	 * people want to collapse this". For this session only, like the flip
	 * ideas box and the watchlist: neither of those is remembered either, and
	 * one fold that outlived a restart while its neighbours did not would be
	 * the odd one out.
	 */
	private boolean open = true;
	private SlotInfo[] last;

	public GeSlotsPanel(ItemManager itemManager, Actions actions)
	{
		this.itemManager = itemManager;
		this.actions = actions;
		setLayout(new BorderLayout());
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, 0, BOTTOM_PAD, 0));
		setToolTipText("Your 8 Grand Exchange offer slots — the bar under each shows how much of that offer has filled");

		final JPanel grid = new JPanel(new GridLayout(ROWS, COLS, 3, VGAP));
		grid.setOpaque(false);
		for (int i = 0; i < cells.length; i++)
		{
			cells[i] = new Cell(i);
			grid.add(cells[i]);
		}
		/* The chevron on a row of its own above the grid, at its right end.
		   It sat in a narrow column beside the grid and took that width off
		   all four columns ("this chevron pushed everything left"). The row
		   costs no height overall: it stands where the divider line between
		   the card and the slots was (see MainPanel). */
		final JLabel hide = foldChevron("▾", "Hide your GE slots");
		hide.setBorder(BorderFactory.createEmptyBorder(0, 6, 1, 2));
		topRow.setOpaque(false);
		topRow.add(hide, BorderLayout.EAST);
		openView.setOpaque(false);
		openView.add(topRow, BorderLayout.NORTH);
		openView.add(grid, BorderLayout.CENTER);

		/* Folded, the same line the flip ideas leave behind ("Recommendations
		   hidden"), plus what the squares were there to tell you: a slot
		   waiting to be collected, or one wanting a new price, should not
		   vanish because the grid did. */
		foldedRow.setOpaque(false);
		foldedRow.setBorder(BorderFactory.createEmptyBorder(0, 2, 2, 2));
		foldedRow.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		foldedRow.setToolTipText("Show your GE slots");
		final JLabel title = new JLabel("GE slots hidden");
		title.setForeground(Brand.TEXT_STRUCTURAL);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 10.5f));
		foldedRow.add(title, BorderLayout.WEST);
		final JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
		right.setOpaque(false);
		readyNote.setForeground(COLLECT_COLOR);
		readyNote.setFont(readyNote.getFont().deriveFont(Font.PLAIN, 10f));
		/* Neutral, not a side's colour: the slots it counts can be buys
		   and sells at once. */
		repriceNote.setForeground(Brand.TEXT_MAIN);
		repriceNote.setFont(repriceNote.getFont().deriveFont(Font.PLAIN, 10f));
		right.add(readyNote);
		right.add(repriceNote);
		right.add(foldChevron("▸", "Show your GE slots"));
		foldedRow.add(right, BorderLayout.EAST);
		/* On every child as well, because Swing does not bubble: a label with
		   no listener swallows the click — see collapsibleSection. */
		final MouseAdapter unfold = new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e) { toggle(); }
		};
		foldedRow.addMouseListener(unfold);
		title.addMouseListener(unfold);
		right.addMouseListener(unfold);
		readyNote.addMouseListener(unfold);
		repriceNote.addMouseListener(unfold);

		add(openView, BorderLayout.CENTER);
		paintNotes();
	}

	/** The chevron as the flip-ideas card draws it: same glyph, size and
	 *  colour, so the two read as the same control. */
	private JLabel foldChevron(String glyph, String tip)
	{
		final JLabel chevron = new JLabel(glyph);
		chevron.setForeground(Brand.TEXT_STRUCTURAL);
		chevron.setFont(chevron.getFont().deriveFont(10f));
		chevron.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		chevron.setToolTipText(tip);
		chevron.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e) { toggle(); }
		});
		return chevron;
	}

	private void toggle()
	{
		open = !open;
		removeAll();
		add(open ? openView : foldedRow, BorderLayout.CENTER);
		revalidate();
		repaint();
	}

	/*
	 * Preferred width 0, NOT Short.MAX_VALUE. A preferred width of 32767
	 * propagates up through FavoritesPanel into the scroll view, and a
	 * JViewport sizes a non-Scrollable view to max(viewport, preferred) — so
	 * one bad preferred width made the entire sidebar column 32767px wide.
	 * Only the MAXIMUM should be unbounded.
	 */
	@Override
	public Dimension getPreferredSize()
	{
		final int h = open
			? topRow.getPreferredSize().height + GRID_H + BOTTOM_PAD
			: super.getPreferredSize().height;
		return new Dimension(0, h);
	}

	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Short.MAX_VALUE, getPreferredSize().height);
	}

	/** Call on the EDT. Fewer than 8 entries just leaves the tail empty. */
	public void update(SlotInfo[] slots)
	{
		last = slots;
		for (int i = 0; i < cells.length; i++)
		{
			cells[i].set((slots != null && i < slots.length) ? slots[i] : null);
		}
		paintNotes();
		revalidate();
		repaint();
	}

	private void paintNotes()
	{
		final int ready = readyCount(last);
		final int reprice = repriceCount(last);
		readyNote.setText(ready + " ready");
		readyNote.setVisible(ready > 0);
		repriceNote.setText(reprice + " to reprice");
		repriceNote.setVisible(reprice > 0);
	}

	/** Slots done and waiting to be collected: the green squares. */
	static int readyCount(SlotInfo[] slots)
	{
		int n = 0;
		for (SlotInfo s : slots != null ? slots : new SlotInfo[0])
		{
			if (s != null && s.state == SlotState.READY_COLLECT)
			{
				n++;
			}
		}
		return n;
	}

	/** Slots wanting a new price: the boxed ones, bar the green. Not one you
	 *  are pricing yourself, which has no box for the same reason — see
	 *  box(SlotInfo). */
	static int repriceCount(SlotInfo[] slots)
	{
		int n = 0;
		for (SlotInfo s : slots != null ? slots : new SlotInfo[0])
		{
			if (s != null && !s.adviceSkipped
				&& (s.state == SlotState.ACTIVE_ADJUST || s.state == SlotState.ACTIVE_LOSING))
			{
				n++;
			}
		}
		return n;
	}

	/** One slot: the item's sprite with a progress bar drawn beneath it.
	 *  The bar is painted rather than a JProgressBar so it can be 3px tall
	 *  and take the slot's own status colour without fighting the look and
	 *  feel. */
	/**
	 * How far along an offer is, as a percentage string.
	 *
	 * Two decimals below 1%, whole numbers above it. A 4-hour limit is often
	 * five figures, so the first fills of a big offer are a fraction of a
	 * percent — 138 of 18,000 is 0.77%, and as an integer that floored to a
	 * flat "0%", which says "nothing has happened" about an offer that has
	 * genuinely started. The two ends of the range need different precision.
	 *
	 * Nothing filled still reads "0", because 0.00% for an offer that has not
	 * moved is false precision. And anything short of complete stops at 99:
	 * 35,999 of 36,000 rounds to 100% and reads as finished when it is not,
	 * which is the one misreading that sends you to the GE for nothing.
	 *
	 * Static and package-private so the boundaries can be tested — they are
	 * where every version of this has gone wrong.
	 */
	static String percentText(int filled, int total)
	{
		if (total <= 0 || filled <= 0)
		{
			return "0";
		}
		if (filled >= total)
		{
			return "100";
		}
		final double pct = 100.0 * filled / total;
		if (pct >= 1.0)
		{
			return String.valueOf(Math.min(99, (int) pct));
		}
		/* Floors rather than rounds, but never below 0.01: a single item out
		   of a huge limit is a real fill and must not report as zero. */
		final double floored = Math.floor(pct * 100) / 100.0;
		return String.format("%.2f", Math.max(0.01, floored));
	}

	private class Cell extends JPanel
	{
		private final JLabel icon = new JLabel();
		private final int slot;
		private SlotInfo info;

		Cell(int slot)
		{
			this.slot = slot;
			setLayout(new BorderLayout());
			setOpaque(false);
			// Keeps the sprite clear of the progress bar painted along the
			// bottom edge — BorderLayout.CENTER otherwise centres the icon in
			// the cell's FULL height and a 32px sprite runs into the bar.
			setBorder(BorderFactory.createEmptyBorder(0, 0, BAR_H + 2, 0));
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			icon.setPreferredSize(new Dimension(CELL, CELL));
			add(icon, BorderLayout.CENTER);
			/* Built fresh on each right-click rather than once up front,
			   because the entry has to read the slot's CURRENT state \u2014 the
			   same cell is "stop advising" one minute and "resume" the next,
			   and a menu built at construction time would be stale by then.
			   Popup triggers differ per platform (press on Linux, release on
			   Windows), so both are checked. */
			final MouseAdapter menu = new MouseAdapter()
			{
				@Override public void mousePressed(MouseEvent e) { maybeShow(e); }
				@Override public void mouseReleased(MouseEvent e) { maybeShow(e); }

				/* Left-click inspects, matching the watchlist and the finder.
				   The popup trigger is checked first because on Linux a
				   right-click arrives as a press with isPopupTrigger set, and
				   SwingUtilities.isRightMouseButton alone would let a
				   context-menu click also swap the card underneath it. */
				@Override
				public void mouseClicked(MouseEvent e)
				{
					if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e) || !inspectable())
					{
						return;
					}
					actions.inspectItem(info.itemId, info.itemName);
				}

				private void maybeShow(MouseEvent e)
				{
					if (!e.isPopupTrigger() || actions == null
						|| info == null || info.state == SlotState.EMPTY)
					{
						return;
					}
					final boolean skipped = info.adviceSkipped;
					final JPopupMenu popup = new JPopupMenu();
					final JMenuItem toggle = new JMenuItem(skipped
						? "Resume price advice for this offer"
						: "Ignore advice \u2014 I'm pricing this one myself");
					toggle.setToolTipText(skipped
						? "Go back to being told when this offer's price has drifted off the market"
						: "Stop being told to reprice this offer. For when you are deliberately "
							+ "asking more than the market pays \u2014 an overnight sell, say \u2014 or "
							+ "bidding under it and content to wait.");
					toggle.addActionListener(a -> actions.setSlotAdviceSkipped(slot, !skipped));
					popup.add(toggle);
					popup.show(Cell.this, e.getX(), e.getY());
				}
			};
			addMouseListener(menu);
			icon.addMouseListener(menu);
		}

		/** Whether clicking this cell has anything to show. The name matters
		 *  as much as the id: the inspection card is rebuilt from a Row keyed
		 *  by name, so a nameless slot would open a card that never prices. */
		private boolean inspectable()
		{
			return actions != null && info != null && info.state != SlotState.EMPTY
				&& info.itemId > 0 && info.itemName != null && !info.itemName.isEmpty();
		}

		void set(SlotInfo next)
		{
			this.info = next;
			icon.setIcon(null);
			if (next != null && next.itemId > 0 && itemManager != null)
			{
				/* Same null guard as the other panels: no item sprites on the
				   login screen, and an NPE inside this loop would drop the
				   remaining slots rather than one icon. */
				final AsyncBufferedImage img = itemManager.getImage(next.itemId);
				if (img != null)
				{
					img.addTo(icon);
				}
			}
			final Cursor cursor = Cursor.getPredefinedCursor(
				inspectable() ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR);
			setCursor(cursor);
			icon.setCursor(cursor);
			final String tip = describe(next);
			setToolTipText(tip);
			/*
			 * The sprite needs it too, and that is not belt and braces.
			 *
			 * ToolTipManager attaches itself per component, to each one that
			 * has had setToolTipText called on it — it does NOT walk up to a
			 * parent for a component that has none. This label fills the
			 * cell's CENTER, so it is what the pointer is actually over
			 * essentially always, and the cell's own tooltip could only ever
			 * appear in the two-pixel margin around the edge. Which is why
			 * hovering an offer to see how far it had filled appeared to do
			 * nothing at all: the text was there, on a component nobody could
			 * reach.
			 */
			icon.setToolTipText(tip);
			repaint();
		}

		/**
		 * What this square is, what to do about it, and how far along it is —
		 * one short line each.
		 *
		 * This was a single run-on sentence: "Selling Ruby — 4,000 of 12,328
		 * (32%) (needs a new price). The market moved to 788 gp; yours is at
		 * 791 gp. Re-list at 788 gp — aborting keeps whatever already filled.
		 * Right-click to stop being told to reprice it. Click to inspect it."
		 * Every clause true, and nobody reads a paragraph off a 32px square.
		 *
		 * Same wording as the in-game box (GeOfferGridOverlay.stateLine), on
		 * purpose: these two describe the same eight slots, and two surfaces
		 * that disagree about what to call a state are worse than either.
		 */
		private String describe(SlotInfo s)
		{
			if (s == null || s.state == SlotState.EMPTY)
			{
				return "Empty slot";
			}
			final StringBuilder sb = new StringBuilder("<html>");
			sb.append(s.itemName != null ? s.itemName : "item");
			sb.append("<br>").append(stateLine(s));
			/* What it is listed at, every time. The game shows it on the GE
			   box; the sidebar square, which you can hover from anywhere,
			   did not. */
			if (s.listedPrice > 0)
			{
				sb.append("<br>").append(s.buy ? "Bidding " : "Asking ")
					.append(String.format("%,d", s.listedPrice)).append(" gp each");
			}
			if (s.quantityTotal > 0)
			{
				/* The percentage is the whole reason to hover: the raw pair
				   needs dividing in your head, which is exactly the work the
				   bar underneath exists to save.

				   Exact counts, NOT the abbreviated form used elsewhere.
				   35,999 of 36,000 abbreviates to "36K of 36K", which next to
				   a truthful "(99%)" reads as a contradiction and makes the
				   reader distrust both numbers. */
				sb.append("<br>").append(s.buy ? "Bought " : "Sold ")
					.append(String.format("%,d", Math.max(0, s.quantityFilled)))
					.append(" of ").append(String.format("%,d", s.quantityTotal))
					.append(" (").append(percentText(s.quantityFilled, s.quantityTotal)).append("%)");
				/* "Abort keeps it" rides with the number it is about, and
				   only when there is something to keep.
				
				   The long tooltip said "aborting keeps whatever already
				   filled" on every red slot. As boilerplate it was part of
				   what made the thing a paragraph — but the question it
				   answers is real and it is about money: being told to
				   re-list looks like being told to throw away a part-filled
				   offer, and someone who believes that will sit on a
				   mispriced one rather than risk it. */
				if ((s.state == SlotState.ACTIVE_ADJUST || s.state == SlotState.ACTIVE_LOSING)
					&& !s.adviceSkipped && s.quantityFilled > 0)
				{
					sb.append(" \u2014 abort keeps it");
				}
			}
			final String collect = collectLine(s);
			if (collect != null)
			{
				sb.append("<br>").append(collect);
			}
			/* The money line the in-game box carries, in the same words. */
			final String money = moneyLine(s);
			if (money != null)
			{
				sb.append("<br>").append(money);
			}
			/*
			 * The gestures, on one line, last.
			 *
			 * These were two whole sentences — "Right-click to stop being
			 * told to reprice it. Click to inspect it." — repeated on all
			 * eight squares, and a good part of what made this a paragraph.
			 * But they are the only place either gesture is discoverable:
			 * nothing on a 32px square suggests it is clickable, and an
			 * affordance nobody finds may as well not exist.
			 *
			 * So: one short line, both gestures, and only the ones that
			 * actually do something on THIS slot. The right-click is left off
			 * a slot already opted out, because the state line above it
			 * already says how to undo that.
			 */
			final List<String> gestures = new ArrayList<>();
			if (inspectable())
			{
				gestures.add("click: inspect");
			}
			if (!s.adviceSkipped
				&& (s.state == SlotState.ACTIVE_OK || s.state == SlotState.ACTIVE_ADJUST
					|| s.state == SlotState.ACTIVE_LOSING))
			{
				gestures.add("right-click: price it yourself");
			}
			if (!gestures.isEmpty())
			{
				sb.append("<br>").append(String.join("  \u00b7  ", gestures));
			}
			return sb.append("</html>").toString();
		}

		/**
		 * The instruction, not the diagnosis. "Needs a new price" on its own
		 * leaves you to go and work out WHICH price, and somebody duly
		 * cancelled an offer, re-placed it at the same number, and asked what
		 * they had cancelled for.
		 *
		 * It says re-list rather than modify, because the Exchange cannot
		 * edit a live offer's price — abort and place again is the only
		 * sequence there is.
		 */
		private String stateLine(SlotInfo s)
		{
			/* Collect first: a skip flag can outlive its offer into
			   ready-to-collect, and it must not hide the one state that wants
			   a click — the same rule box() applies to the colour. */
			if (s.state == SlotState.READY_COLLECT)
			{
				return "Ready to collect.";
			}
			if (s.adviceSkipped)
			{
				return "You are pricing this one \u2014 right-click to undo.";
			}
			if (s.state == SlotState.ACTIVE_LOSING)
			{
				final StringBuilder sb = new StringBuilder("Loses at this bid \u2014 lower it to ")
					.append(String.format("%,d", s.targetPrice)).append(" gp");
				if (s.breakEven > s.targetPrice)
				{
					sb.append(" (").append(String.format("%,d", s.breakEven)).append(" at most for +0)");
				}
				return sb.toString();
			}
			if (s.state == SlotState.ACTIVE_ADJUST)
			{
				if (s.noMargin)
				{
					return "No margin left \u2014 take a new flip.";
				}
				if (s.targetPrice > 0)
				{
					final StringBuilder sb = new StringBuilder(adjustVerb(s.buy))
						.append(String.format("%,d", s.targetPrice)).append(" gp").append(adjustTail(s.buy));
					if (s.offerPrice > 0)
					{
						sb.append(" (yours: ").append(String.format("%,d", s.offerPrice)).append(")");
					}
					return sb.toString();
				}
				return "Priced off the market \u2014 re-list.";
			}
			/* Green has only ever meant "not red", which is true and says
			   nothing — see GeOfferGridOverlay.stateLine for the report. */
			return "Priced fine \u2014 leave it.";
		}


		@Override
		protected void paintComponent(Graphics g)
		{
			super.paintComponent(g);
			final Graphics2D g2 = (Graphics2D) g;
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			final int w = getWidth();
			final int h = getHeight();
			final SlotState state = info != null ? info.state : SlotState.EMPTY;

			/* Every square keeps the faint frame an empty one has, so the
			   grid still reads as eight slots; a box goes over it only when
			   the slot needs you. */
			g2.setColor(EMPTY_BORDER);
			g2.drawRect(0, 0, w - 1, h - BAR_H - 2);
			final Color box = box(info);
			if (box != null)
			{
				g2.setColor(box);
				g2.drawRect(0, 0, w - 1, h - BAR_H - 2);
			}

			final int barY = h - BAR_H;
			g2.setColor(TRACK);
			g2.fillRect(0, barY, w, BAR_H);
			if (state != SlotState.EMPTY && info != null)
			{
				// A collected-ready offer is done by definition, whatever the
				// counter says — otherwise a cancelled part-fill would show a
				// half bar next to a "ready to collect" outline.
				final double pct = state == SlotState.READY_COLLECT ? 1.0
					: (info.quantityTotal > 0 ? Math.min(1.0, info.quantityFilled / (double) info.quantityTotal) : 0.0);
				/* Buying or selling, in the theme's colours — "if you are
				   selling somthing ... the theme sell bar color. if you are
				   buyingt somthing ... buy color". Both fill as the offer
				   does, so a full bar always means done. */
				g2.setColor(sideColour(info.buy));
				g2.fillRect(0, barY, (int) Math.round(w * pct), BAR_H);
			}
		}
	}

	/**
	 * The hover's money line, as HTML, in the words GeOfferGridOverlay.
	 * moneyLine uses on the in-game box — "Profit if it flips" on a buy, and
	 * on a sell that has started selling, what it has made so far and what
	 * is still to come. Null when there is nothing honest to say.
	 */
	static String moneyLine(SlotInfo s)
	{
		if (s == null)
		{
			return null;
		}
		if (!s.buy && s.bookedProfit != null)
		{
			final StringBuilder sb = new StringBuilder("Made ").append(gp(s.bookedProfit)).append(" so far");
			if (s.projectedProfit != null)
			{
				final long all = s.bookedProfit + s.projectedProfit;
				sb.append(", ").append(gp(s.projectedProfit))
					.append(" to come (").append(all >= 0 ? "+" : "-")
					.append(net.runelite.client.util.QuantityFormatter.quantityToStackSize(Math.abs(all)))
					.append(" in all)");
			}
			return sb.toString();
		}
		if (s.projectedProfit == null)
		{
			return null;
		}
		return (s.buy ? "Profit if it flips: " : "Profit: ") + gp(s.projectedProfit);
	}

	/** A signed gp figure in the profit or loss colour. */
	private static String gp(long p)
	{
		final Color c = p >= 0 ? TipStyle.GOOD : TipStyle.BAD;
		return String.format("<font color='#%06x'>", c.getRGB() & 0xFFFFFF)
			+ (p >= 0 ? "+" : "-")
			+ net.runelite.client.util.QuantityFormatter.quantityToStackSize(Math.abs(p))
			+ " gp</font>";
	}

	/** Hand the slots the theme's buy and sell colours. Any thread; the
	 *  next paint picks them up. */
	static void setTheme(Color buy, Color sell)
	{
		if (buy != null)
		{
			buyColour = buy;
		}
		if (sell != null)
		{
			sellColour = sell;
		}
	}

	/** A buy's colour or a sell's, from the theme: the bar always, and the
	 *  box when the slot wants a new price. */
	static Color sideColour(boolean buy)
	{
		return buy ? buyColour : sellColour;
	}

	/**
	 * The box round a slot, or null for none: only when it needs you.
	 *
	 * Takes the whole SlotInfo because "you are pricing this one yourself"
	 * is not a state — the offer is still perfectly active — but it does
	 * take the box away, as it does on the in-game one.
	 */
	static Color box(SlotInfo s)
	{
		final SlotState state = s != null ? s.state : SlotState.EMPTY;
		/* Collect before the skip flag: the flag outlives its offer into
		   ready-to-collect, and hiding a collectable slot would hide the one
		   state that wants a click. The in-game overlay never meets this
		   case, because buildSlotViews drops inactive offers before it gets
		   there. */
		if (state == SlotState.READY_COLLECT)
		{
			return COLLECT_COLOR;
		}
		if (s == null || s.adviceSkipped)
		{
			return null;
		}
		/* Off the market, or filling at a loss: both mean "move this price",
		   and the hover says which and to what. */
		return state == SlotState.ACTIVE_ADJUST || state == SlotState.ACTIVE_LOSING ? sideColour(s.buy) : null;
	}

	private static String label(SlotState state)
	{
		switch (state)
		{
			case ACTIVE_OK: return "priced fine";
			case ACTIVE_ADJUST: return "needs a new price";
			case ACTIVE_LOSING: return "losing at this price";
			case READY_COLLECT: return "ready to collect";
			case EMPTY:
			default: return "empty";
		}
	}
}
