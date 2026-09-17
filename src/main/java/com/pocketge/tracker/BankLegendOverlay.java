package com.pocketge.tracker;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.ArrayList;
import java.util.List;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * What the coloured rings round your bank slots mean, said in the bank.
 *
 * The marks have always carried a hover tooltip, and the note next to it in
 * BankHighlightOverlay argued that a legend you have to remember is a legend
 * that has already failed. That is still true of a legend somewhere else —
 * but it turned out to be an argument against the tooltip too, because a
 * tooltip is only read by someone who already suspects there is something to
 * read. Reported as "what do the green squares mean?" by the person who asked
 * for the gold one.
 *
 * So the key is drawn where the marks are, and it explains only what is
 * actually on screen: a row appears when that colour is in use and not
 * otherwise. With nothing marked there is nothing to explain, and the bank is
 * left alone.
 */
@Singleton
public class BankLegendOverlay extends Overlay
{
	/**
	 * The bank interface's root container.
	 *
	 * Built from the group id rather than a named component constant, because
	 * there is no bank constant to name: the offline stubs carry only
	 * InterfaceID.BANKMAIN, and inventing a component id that the real client
	 * might number differently is exactly the kind of guess that compiles
	 * cleanly and fails in the game. The packing is the convention visible in
	 * the stubs themselves — GeOffers.UNIVERSE is 0x01d1_0000, which is group
	 * 0x01d1 with child 0 — so child 0 of the bank group is its root.
	 *
	 * If that is wrong, getWidget returns null and the legend simply does not
	 * draw. Nothing else depends on it.
	 */
	private static final int BANK_ROOT = InterfaceID.BANKMAIN << 16;

	private static final Color BG = new Color(0x12, 0x12, 0x12, 0xD0);
	private static final Color RIM = new Color(0x00, 0x00, 0x00, 0x90);
	private static final Color TEXT = new Color(0xC8, 0xC8, 0xC8);

	private static final int SWATCH = 9;
	private static final int PAD = 5;
	private static final int ROW_H = 13;
	/** Clear of the bank's own bottom button row. */
	private static final int MARGIN = 6;

	@Inject
	private Client client;

	@Inject
	private BankHighlightOverlay marks;

	@Inject
	private BankLegendOverlay()
	{
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	@Override
	public Dimension render(Graphics2D g)
	{
		if (!marks.isEnabled())
		{
			return null;
		}
		final Widget bank = client.getWidget(BANK_ROOT);
		if (bank == null || bank.isHidden())
		{
			return null;
		}
		final Rectangle b = bank.getBounds();
		if (b == null || b.width <= 0 || b.height <= 0)
		{
			return null;
		}

		/* Only what is on screen. A key listing a colour the bank is not
		   currently wearing is a quiz, not a key. */
		final List<String[]> rows = new ArrayList<>();
		if (marks.hasRecommended())
		{
			rows.add(new String[]{"gold", "On your panel now"});
		}
		if (marks.hasSellable())
		{
			rows.add(new String[]{"green", "Worth selling"});
		}
		if (rows.isEmpty())
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g.setFont(g.getFont().deriveFont(11f));
		final FontMetrics fm = g.getFontMetrics();

		int textW = 0;
		for (String[] r : rows)
		{
			textW = Math.max(textW, fm.stringWidth(r[1]));
		}
		final int w = PAD + SWATCH + 5 + textW + PAD;
		final int h = PAD + rows.size() * ROW_H + PAD - 2;
		final int x = b.x + MARGIN;
		final int y = b.y + b.height - h - MARGIN;

		g.setColor(BG);
		g.fillRect(x, y, w, h);
		g.setColor(RIM);
		g.drawRect(x, y, w, h);

		int ry = y + PAD;
		for (String[] r : rows)
		{
			final boolean gold = "gold".equals(r[0]);
			/* The swatch is the mark itself, at swatch size — drawn by the
			   same method that draws it on a slot, so the key cannot drift
			   out of step with the thing it is describing. */
			BankHighlightOverlay.drawRing(g, new Rectangle(x + PAD, ry, SWATCH, SWATCH), gold);
			g.setColor(TEXT);
			g.drawString(r[1], x + PAD + SWATCH + 5, ry + SWATCH - 1);
			ry += ROW_H;
		}
		return null;
	}
}
