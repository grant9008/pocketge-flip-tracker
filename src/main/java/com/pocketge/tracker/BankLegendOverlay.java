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

	/* Opaque enough to work over the 3D scene. Inside the bank it sat on
	   dark parchment; below it, what is behind it is grass, sand or a stone
	   floor, and 0xD0 over bright ground was unreadable. */
	private static final Color BG = new Color(0x12, 0x12, 0x12, 0xE8);
	private static final Color RIM = new Color(0x00, 0x00, 0x00, 0x90);
	private static final Color TEXT = new Color(0xE6, 0xE6, 0xE6);

	private static final int SWATCH = 18;
	private static final int PAD = 12;
	private static final int SWATCH_GAP = 11;
	/** Bank's bottom edge to the legend's top edge. */
	private static final int GAP_BELOW = 6;
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
		final boolean hasGold = marks.hasRecommended();
		if (hasGold)
		{
			/* Says what it IS, not where it happens to be shown. "On your
			   panel now" describes the sidebar; the thing you want to know
			   standing at a bank is that this stack is the one being
			   suggested. */
			rows.add(new String[]{"gold", "Your current suggestion"});
		}
		if (marks.hasSellable())
		{
			/* "Also" only when there is something for it to be also TO. With
			   no gold mark on screen these green ones are not the runners-up,
			   they are the whole list. */
			rows.add(new String[]{"green", hasGold ? "Also worth selling" : "Worth selling"});
		}
		if (rows.isEmpty())
		{
			return null;
		}

		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		/* 15f bold: the readable band this plugin already settled on for text
		   drawn on the game canvas, after 11pt was reported as mush on the
		   offer-screen chip. Inside the bank this could lean on the interface
		   around it; out here it cannot. */
		g.setFont(g.getFont().deriveFont(java.awt.Font.BOLD, 17f));
		final FontMetrics fm = g.getFontMetrics();

		int textW = 0;
		for (String[] r : rows)
		{
			textW = Math.max(textW, fm.stringWidth(r[1]));
		}
		/* Row height from the font, not a constant: a size change must not
		   start clipping descenders silently. */
		final int rowH = Math.max(SWATCH, fm.getHeight()) + 5;
		final int w = PAD + SWATCH + SWATCH_GAP + textW + PAD;
		final int h = PAD * 2 + rows.size() * rowH;

		/*
		 * Below the bank, not inside it.
		 *
		 * It was bottom-left INSIDE the bank window, which put a key on top
		 * of the slots it is a key for. Underneath is the empty band of scene
		 * you get in resizable mode, where it covers nothing.
		 *
		 * Three placements, in order, so it can never leave the canvas: under
		 * the bank, above it, or — when neither fits, which is most of fixed
		 * mode where the bank fills the viewport — back inside at the old
		 * spot. Somewhere readable beats nowhere.
		 */
		int x = b.x;
		int y = b.y + b.height + GAP_BELOW;

		final int cw = client.getCanvasWidth();
		final int ch = client.getCanvasHeight();
		/* The floor is the chat area when there is one: in fixed mode the
		   space "below the bank" IS the chat strip, and a key printed over
		   the chat log is not an improvement on one printed over the bank. */
		int floor = ch > 0 ? ch : y + h;
		final Widget chat = client.getWidget(InterfaceID.Chatbox.CHATAREA);
		final Rectangle cb = chat != null && !chat.isHidden() ? chat.getBounds() : null;
		if (cb != null && !cb.isEmpty() && cb.y > b.y)
		{
			floor = Math.min(floor, cb.y - 2);
		}
		/*
		 * Above the bank if there is room; otherwise it STAYS below it.
		 *
		 * The third placement used to be back inside the bank, bottom-left,
		 * and that is where it landed in the ordinary case — a bank that
		 * starts near the top of the screen has no room above it, and a chat
		 * box open underneath leaves no room below. So the key sat on the
		 * bank's own tool row: "make this box i circled always apear more
		 * down. it covers the bank tools".
		 *
		 * The rule is now that it never covers the bank. When the only space
		 * left is the strip the chat is using, it takes that: the chat is
		 * text you read and can scroll, the tool row is buttons you click,
		 * and a box over a button is worse than a box over a sentence. The
		 * canvas clamp below still keeps it on screen — if a layout really
		 * has nowhere at all, somewhere readable beats nowhere, but that is
		 * now the last resort rather than the common case.
		 */
		if (y + h > floor && b.y - GAP_BELOW - h >= 0)
		{
			y = b.y - GAP_BELOW - h;
		}
		/* Guarded on > 0: the offline stub reports 0 for both, and an
		   unguarded clamp would pin the box to the corner in every test and
		   on any frame before the canvas size is known. */
		if (cw > 0)
		{
			x = Math.max(0, Math.min(x, cw - w));
		}
		if (ch > 0)
		{
			y = Math.max(0, Math.min(y, ch - h));
		}

		g.setColor(BG);
		g.fillRect(x, y, w, h);
		g.setColor(RIM);
		/* w-1/h-1: fillRect covers x..x+w-1, so a rim at w/h sat a pixel
		   outside the fill on two sides. */
		g.drawRect(x, y, w - 1, h - 1);

		int ry = y + PAD;
		for (String[] r : rows)
		{
			final boolean gold = "gold".equals(r[0]);
			/* The swatch is the mark itself, at swatch size — drawn by the
			   same method that draws it on a slot, so the key cannot drift
			   out of step with the thing it is describing. */
			final int sy = ry + (rowH - 5 - SWATCH) / 2;
			BankHighlightOverlay.drawRing(g, new Rectangle(x + PAD, sy, SWATCH, SWATCH), gold);
			g.setColor(TEXT);
			/* Centred on the row rather than pinned to the swatch's bottom,
			   so swatch and text stay aligned if either size changes. */
			g.drawString(r[1], x + PAD + SWATCH + SWATCH_GAP,
				ry + (rowH - 5 + fm.getAscent() - fm.getDescent()) / 2);
			ry += rowH;
		}
		return null;
	}
}
