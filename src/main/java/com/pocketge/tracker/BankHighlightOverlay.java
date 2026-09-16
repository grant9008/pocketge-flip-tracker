package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.ItemComposition;
import net.runelite.api.Point;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.ImageUtil;
import net.runelite.client.util.QuantityFormatter;

/**
 * Marks the stacks in your bank that are worth SELLING right now — one
 * colour, one meaning.
 *
 * It used to draw three: gold for "buy more of this", green for "sell
 * this", and dashed teal for "you told me to ignore this". None of them
 * was labelled anywhere, and the honest report from actually using it was
 * that it was a bunch of colours that didn't mean anything. Two of the
 * three earned that:
 *
 *  - Gold answered a question you weren't asking. You are looking at what
 *    you already own; telling you to go buy more of it belongs in Find
 *    Opportunities, not on a bank slot.
 *  - Teal only ever appeared on items you had personally muted, so it told
 *    you something you already knew.
 *
 * Green answers a question nothing else can: walk into a bank of three
 * hundred stacks, and which of them should you be selling today? That is
 * worth a colour. So it is the only one left, it is on by default, and
 * hovering a marked slot says why in words — a legend you have to remember
 * is a legend that has already failed.
 *
 * Covers bank, inventory and equipment: RuneLite calls
 * {@link #renderItemOverlay} for every visible item container widget.
 */
@Singleton
public class BankHighlightOverlay extends WidgetItemOverlay
{
	/** The same green every other "this is money" signal in the plugin uses
	 *  — GeSlotsPanel, the offer grid, the watchlist profit tag. */
	private static final Color SELL_COLOR = new Color(0x1F, 0xB8, 0x5C);
	/** Brand gold, for the ONE stack the sidebar is talking about right now.
	 *  Green means "worth selling"; gold means "this is the card". Without
	 *  the second colour, walking into a bank with nine marked stacks tells
	 *  you nine things and points at none of them.
	 *
	 *  It is also the only slot that gets the PocketGE mark. Marking all of
	 *  them defeated the point of marking one, and cost every sellable stack
	 *  a 14px icon over its sprite. */
	private static final Color RECOMMENDED_COLOR = new Color(0xE5, 0xC1, 0x58);
	private static final int MARK_SIZE = 14;
	/**
	 * How opaque the TOP edge of the ring is, out of 255.
	 *
	 * The game prints the stack count in the top-left of the slot, in a small
	 * font whose glyphs start at the very first row of pixels — so a solid
	 * 2px gold line laid along the top of the slot sits on the tops of the
	 * digits. That is exactly where a digit's identity lives: shave three
	 * pixels off the top of "42,027" and the 4 could be a 1 and the 7 could
	 * be a 2. The report was a marked stack whose quantity could not be read.
	 *
	 * The other three sides stay solid. Only this one has anything behind it
	 * worth reading, and at this weight the box still closes — the two top
	 * corners are where the eye finishes the rectangle, and the left and
	 * right edges draw those at full strength.
	 */
	static final int TOP_EDGE_ALPHA = 110;

	/**
	 * The ring, drawn INSIDE the slot, with a see-through top edge.
	 *
	 * Static and free of the client so it can be rendered and measured on its
	 * own — see the SlotMark harness, which reads the pixels back over a
	 * mock quantity label rather than trusting that this looks right.
	 */
	static void drawRing(Graphics2D g, Rectangle bounds, boolean isRecommended)
	{
		final Color base = isRecommended ? RECOMMENDED_COLOR : SELL_COLOR;
		final int w = isRecommended ? 2 : 1;
		final int inset = isRecommended ? 1 : 0;
		final int x0 = bounds.x + inset;
		final int y0 = bounds.y + inset;
		final int x1 = bounds.x + bounds.width - 1 - inset;
		final int y1 = bounds.y + bounds.height - 1 - inset;

		g.setStroke(new BasicStroke(w));
		g.setColor(base);
		g.drawLine(x0, y0, x0, y1); // left
		g.drawLine(x1, y0, x1, y1); // right
		g.drawLine(x0, y1, x1, y1); // bottom

		/* Last, and thinner: it is drawn OVER the left and right edges at
		   their corners, so at full width it would blunt them, and a soft
		   line is what lets the digits through. */
		g.setStroke(new BasicStroke(1f));
		g.setColor(new Color(base.getRed(), base.getGreen(), base.getBlue(), TOP_EDGE_ALPHA));
		g.drawLine(x0, y0, x1, y0);
	}

	private volatile Map<Integer, Advisor.Suggestion> suggestionsByItem = Map.of();
	/** Mirrors PocketGeTrackerConfig.bankHighlights. Checked per slot rather
	 *  than by adding/removing the overlay, so a toggle can never race a
	 *  half-drawn frame. */
	private volatile boolean enabled = true;
	/** See {@link #setRecommended}. Volatile: written on the Swing EDT when
	 *  the card changes, read on the client thread every frame. */
	private volatile Integer recommendedItemId;
	private final BufferedImage markIcon;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Client client;

	@Inject
	private TooltipManager tooltipManager;

	@Inject
	/**
	 * The Grand Exchange's own inventory panel.
	 *
	 * showOnInventory() covers the normal inventory tab and the bank's copy
	 * of it, and not this — the Exchange draws its own inventory beside the
	 * offer screen, as a separate interface. So the mark was on your stack
	 * everywhere except the one screen you go to in order to sell it, which
	 * is where it is for. Reported with a card reading "Sell 18,608 Diamond
	 * from your inventory" over an unmarked stack, with the Exchange open.
	 *
	 * A bare group id because the offline stub carries no constant for it;
	 * 467 is RuneLite's long-standing GRAND_EXCHANGE_INVENTORY group, the
	 * one its own GE plugin uses. Registration is additive — this overlay
	 * already calls three of these in a row and all three work — so it joins
	 * the others rather than replacing them.
	 */
	private static final int GE_INVENTORY_GROUP = 467;

	private BankHighlightOverlay()
	{
		showOnInventory();
		showOnBank();
		showOnEquipment();
		showOnInterfaces(GE_INVENTORY_GROUP);
		markIcon = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "icon.png"), MARK_SIZE, MARK_SIZE);
	}

	/** Called from the plugin whenever suggestions are recomputed. Keyed by
	 *  item id so a lookup per rendered slot is O(1). May contain BUY
	 *  entries; this overlay ignores them (see the class comment). */
	public void setSuggestions(Map<Integer, Advisor.Suggestion> byItem)
	{
		this.suggestionsByItem = byItem != null ? byItem : Map.of();
	}

	public void setEnabled(boolean enabled)
	{
		this.enabled = enabled;
	}

	/** The item the recommendation card is showing, or null. Marked apart
	 *  from the rest so there is one thing to click, not a field of them. */
	public void setRecommended(Integer itemId)
	{
		this.recommendedItemId = itemId;
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!enabled)
		{
			return;
		}
		final Integer rec = recommendedItemId;
		final boolean isRecommended = rec != null && rec == itemId;
		final Advisor.Suggestion s = suggestionsByItem.get(itemId);
		/*
		 * The card's own item is marked whatever the suggestion map says.
		 *
		 * Both gates below used to apply to it too, and that is why a card
		 * reading "Sell 17,303 Uncut ruby" could leave every ruby in the
		 * inventory unmarked. The map is rebuilt from Advisor's SELL
		 * suggestions each cycle, and the card is not always one of those —
		 * it can come from the plan's own sell candidate, from a stack whose
		 * suggestion has aged out, or from a cycle that has not landed yet.
		 * So the plugin was telling you to sell something and then declining
		 * to point at it, which is the one job the mark has.
		 *
		 * isMerchantStack is skipped for the same reason. It exists to keep
		 * the plain outline off single unstackable items in a bank full of
		 * them — a sensible filter for "everything worth selling", and not a
		 * second opinion the card needs. The card already decided.
		 */
		if (!isRecommended && (s == null || s.type != Advisor.Suggestion.Type.SELL))
		{
			return;
		}
		final Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null || (!isRecommended && !isMerchantStack(itemId, widgetItem)))
		{
			return;
		}

		/*
		 * ONE ring, drawn INSIDE the slot.
		 *
		 * A stroke is centred on the path it follows, so 2.5px laid straight
		 * along `bounds` put half of itself outside the slot — and a fat soft
		 * line sitting right against the bank's own slot border reads as two
		 * borders, not one. Adding a second ring 3px further in made it three.
		 * On a stack of uncut diamonds, which are small, pale and numerous,
		 * the result was a slot you could not read the contents of.
		 *
		 * Inset by half the stroke so the ring lands wholly within the slot
		 * and the bank's own edge stays the only line on the boundary.
		 */
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		drawRing(graphics, bounds, isRecommended);
		if (isRecommended)
		{
			/* The mark is what separates "the one on your panel" from the rest
			   now that the second ring is gone — and it is EXCLUSIVE to it.
			   It used to be stamped on every marked stack, so a bank with nine
			   sellable stacks wore nine icons over nine sprites; the thing
			   meant to pick one slot out was on all of them. Bottom-right, away
			   from the game's own quantity label in the top-left. */
			graphics.drawImage(markIcon, bounds.x + bounds.width - MARK_SIZE,
				bounds.y + bounds.height - MARK_SIZE, null);
		}

		/* Say what the border means, on the slot itself. A colour you have
		   to look up somewhere else is a colour that gets ignored, which is
		   exactly what happened to the three this replaced. */
		final Point mouse = client.getMouseCanvasPosition();
		if (mouse != null && bounds.contains(mouse.getX(), mouse.getY()))
		{
			/* s can be null on the recommended stack now — see the gate above,
			   where the card outranks the suggestion map. The headline still
			   stands on its own; the money line simply has nothing to add. */
			tooltipManager.add(new Tooltip(isRecommended
				? "</col><col=e5c158>This is the flip on your panel</col>"
					+ (s != null ? "</br>" + tooltipText(s) : "")
				: tooltipText(s)));
		}
	}

	/** Deliberately leads with the money rather than the verb — "sell this"
	 *  is already obvious from the border; what you actually want to know
	 *  standing in your bank is whether this stack is worth the click. */
	private static String tooltipText(Advisor.Suggestion s)
	{
		final StringBuilder sb = new StringBuilder();
		/* grossValue, not expectedProfit. "Worth selling: X" is a claim about
		   what the stack fetches, and on a tracked stack expectedProfit is a
		   gain instead — which reads as a catastrophic undervaluation when
		   the position is barely up, and as a negative number when it is
		   down. What the sale brings in needs no knowledge of what you paid,
		   so it is the one figure that is right in every case. */
		sb.append("</col>Worth selling: <col=1fb85c>")
			.append(QuantityFormatter.quantityToStackSize(s.grossValue))
			.append(" gp</col> after tax");
		if (s.price > 0)
		{
			sb.append("</br><col=8a8274>at ")
				.append(QuantityFormatter.quantityToStackSize(s.price))
				.append(" gp each</col>");
		}
		return sb.toString();
	}

	/** A lone unstacked individual item (quantity 1, not in noted form)
	 *  isn't something you'd bulk-flip — it's whatever single piece of gear
	 *  or loot happens to be sitting there. Without this, an unstackable
	 *  item filling a dozen inventory slots (one unit each) drew the exact
	 *  same border on every one of those slots, which reads as a dozen
	 *  separate suggestions rather than the one real one. */
	private boolean isMerchantStack(int itemId, WidgetItem widgetItem)
	{
		if (widgetItem.getQuantity() > 1)
		{
			return true;
		}
		final ItemComposition comp = itemManager.getItemComposition(itemId);
		return comp != null && comp.getNote() != -1;
	}
}
