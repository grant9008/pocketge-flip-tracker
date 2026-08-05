package com.pocketge.tracker;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.FlowLayout;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JPanel;
import net.runelite.client.ui.ColorScheme;

/**
 * 8 small squares, one per Grand Exchange offer slot — a glance-only status
 * strip so you can tell whether anything needs attention without tabbing
 * out of whatever you're doing (skilling, a boss trip) to actually open the
 * GE. Same green/red meaning as GeOfferGridOverlay draws directly on the GE
 * interface itself; this is just that same signal, always visible in the
 * sidebar instead of only when the GE window happens to be open.
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
	}

	private static final Color OK_COLOR = new Color(0x1F, 0xB8, 0x5C);
	private static final Color ADJUST_COLOR = new Color(0xEF, 0x53, 0x50);
	private static final Color COLLECT_COLOR = new Color(0xE5, 0xC1, 0x58);
	private static final Color EMPTY_COLOR = ColorScheme.DARKER_GRAY_COLOR.brighter();
	private static final int SQUARE = 16;

	private final JLabel[] squares = new JLabel[8];

	public GeSlotsPanel()
	{
		setLayout(new FlowLayout(FlowLayout.LEFT, 3, 2));
		setOpaque(false);
		setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));
		setToolTipText("Your 8 Grand Exchange offer slots — green: priced fine, "
			+ "red: needs a new price, gold: ready to collect, gray: empty");
		for (int i = 0; i < squares.length; i++)
		{
			final JLabel sq = new JLabel();
			sq.setOpaque(true);
			sq.setPreferredSize(new Dimension(SQUARE, SQUARE));
			sq.setBackground(EMPTY_COLOR);
			sq.setBorder(BorderFactory.createLineBorder(ColorScheme.MEDIUM_GRAY_COLOR, 1));
			sq.setCursor(Cursor.getPredefinedCursor(Cursor.DEFAULT_CURSOR));
			squares[i] = sq;
			add(sq);
		}
	}

	/** Call on the EDT. slots.length is always 8 (or fewer at the tail if a
	 *  caller passes a short list — missing entries just render empty). */
	public void update(SlotInfo[] slots)
	{
		for (int i = 0; i < squares.length; i++)
		{
			final SlotInfo info = (slots != null && i < slots.length) ? slots[i] : null;
			final SlotState state = info != null ? info.state : SlotState.EMPTY;
			final JLabel sq = squares[i];
			switch (state)
			{
				case ACTIVE_OK:
					sq.setBackground(OK_COLOR);
					break;
				case ACTIVE_ADJUST:
					sq.setBackground(ADJUST_COLOR);
					break;
				case READY_COLLECT:
					sq.setBackground(COLLECT_COLOR);
					break;
				case EMPTY:
				default:
					sq.setBackground(EMPTY_COLOR);
					break;
			}
			final String label = "Slot " + (i + 1) + ": " + slotStateLabel(state)
				+ (info != null && info.itemName != null ? " — " + info.itemName : "");
			sq.setToolTipText(label);
		}
	}

	private static String slotStateLabel(SlotState state)
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
