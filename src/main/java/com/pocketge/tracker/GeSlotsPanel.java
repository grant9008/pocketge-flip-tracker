package com.pocketge.tracker;

import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.SwingConstants;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.ColorScheme;
import net.runelite.client.util.AsyncBufferedImage;
import net.runelite.client.util.QuantityFormatter;

/**
 * Your 8 Grand Exchange slots, drawn as the item actually in each one with
 * a fill bar underneath showing how much of that offer has completed.
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
	}

	private static final Color OK_COLOR = new Color(0x1F, 0xB8, 0x5C);
	private static final Color ADJUST_COLOR = new Color(0xEF, 0x53, 0x50);
	private static final Color COLLECT_COLOR = new Color(0xE5, 0xC1, 0x58);
	private static final Color EMPTY_BORDER = ColorScheme.MEDIUM_GRAY_COLOR;
	private static final Color TRACK = new Color(0x2B, 0x26, 0x21);
	private static final int CELL = 24;
	private static final int BAR_H = 3;

	private final ItemManager itemManager;
	private final Cell[] cells = new Cell[8];

	public GeSlotsPanel(ItemManager itemManager)
	{
		this.itemManager = itemManager;
		setLayout(new GridLayout(1, cells.length, 2, 0));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		setPreferredSize(new Dimension(Short.MAX_VALUE, CELL + BAR_H + 2));
		setMaximumSize(new Dimension(Short.MAX_VALUE, CELL + BAR_H + 2));
		setToolTipText("Your 8 Grand Exchange offer slots — the bar under each shows how much of that offer has filled");
		for (int i = 0; i < cells.length; i++)
		{
			cells[i] = new Cell();
			add(cells[i]);
		}
	}

	/** Call on the EDT. Fewer than 8 entries just leaves the tail empty. */
	public void update(SlotInfo[] slots)
	{
		for (int i = 0; i < cells.length; i++)
		{
			cells[i].set((slots != null && i < slots.length) ? slots[i] : null);
		}
		revalidate();
		repaint();
	}

	/** One slot: the item's sprite with a progress bar drawn beneath it.
	 *  The bar is painted rather than a JProgressBar so it can be 3px tall
	 *  and take the slot's own status colour without fighting the look and
	 *  feel. */
	private class Cell extends JPanel
	{
		private final JLabel icon = new JLabel();
		private SlotInfo info;

		Cell()
		{
			setLayout(new BorderLayout());
			setOpaque(false);
			icon.setHorizontalAlignment(SwingConstants.CENTER);
			icon.setPreferredSize(new Dimension(CELL, CELL));
			add(icon, BorderLayout.CENTER);
		}

		void set(SlotInfo next)
		{
			this.info = next;
			icon.setIcon(null);
			if (next != null && next.itemId > 0 && itemManager != null)
			{
				final AsyncBufferedImage img = itemManager.getImage(next.itemId);
				img.addTo(icon);
			}
			setToolTipText(describe(next));
			repaint();
		}

		private String describe(SlotInfo s)
		{
			if (s == null || s.state == SlotState.EMPTY)
			{
				return "Empty slot";
			}
			final String action = s.buy ? "Buying" : "Selling";
			final String progress = s.quantityTotal > 0
				? " — " + QuantityFormatter.quantityToStackSize(s.quantityFilled) + " of "
					+ QuantityFormatter.quantityToStackSize(s.quantityTotal)
				: "";
			return action + " " + (s.itemName != null ? s.itemName : "item") + progress
				+ " (" + label(s.state) + ")";
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

			// Outline: colourless when empty so free slots read as gaps.
			g2.setColor(state == SlotState.EMPTY ? EMPTY_BORDER : accent(state));
			g2.drawRect(0, 0, w - 1, h - BAR_H - 2);

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
				g2.setColor(accent(state));
				g2.fillRect(0, barY, (int) Math.round(w * pct), BAR_H);
			}
		}
	}

	private static Color accent(SlotState state)
	{
		switch (state)
		{
			case ACTIVE_OK: return OK_COLOR;
			case ACTIVE_ADJUST: return ADJUST_COLOR;
			case READY_COLLECT: return COLLECT_COLOR;
			case EMPTY:
			default: return EMPTY_BORDER;
		}
	}

	private static String label(SlotState state)
	{
		switch (state)
		{
			case ACTIVE_OK: return "priced fine";
			case ACTIVE_ADJUST: return "needs a new price";
			case READY_COLLECT: return "ready to collect";
			case EMPTY:
			default: return "empty";
		}
	}
}
