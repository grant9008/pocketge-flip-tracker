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
	 * How far down the slot the stack count reaches, in pixels.
	 *
	 * The label is not only ON the top edge — it starts at the slot's left
	 * edge too, so the ring's left side ran through the first digit. Fading
	 * the whole left edge would have been the wrong trade (a box that gives
	 * way on every side is not a box), so only the top of it does: this is
	 * the band the digits occupy, and below it the edge is full weight.
	 */
	static final int QTY_BAND_H = 10;

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
		final Color faint = new Color(base.getRed(), base.getGreen(), base.getBlue(), TOP_EDGE_ALPHA);
		final int w = isRecommended ? 2 : 1;
		final int inset = isRecommended ? 1 : 0;
		final int x0 = bounds.x + inset;
		final int y0 = bounds.y + inset;
		final int x1 = bounds.x + bounds.width - 1 - inset;
		final int y1 = bounds.y + bounds.height - 1 - inset;
		/* Never more than a third of the slot, however tall the label is
		   reckoned to be — past that the left edge stops being an edge. */
		final int band = Math.min(QTY_BAND_H, bounds.height / 3);

		/* Right and bottom are solid, whole. Nothing is drawn behind them. */
		g.setStroke(new BasicStroke(w));
		g.setColor(base);
		g.drawLine(x1, y0, x1, y1); // right
		g.drawLine(x0, y1, x1, y1); // bottom
		/* The left edge only gives way where the label actually is, which is
		   its top corner. Below the band it is a full-weight edge like any
		   other, so three and a half of the four sides still carry the box. */
		g.drawLine(x0, y0 + band, x0, y1);

		g.setStroke(new BasicStroke(1f));
		g.setColor(faint);
		g.drawLine(x0, y0, x0, y0 + band);
		/* The top edge last: it crosses the left and right edges at their
		   corners, and at full weight it would blunt them. */
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

	/**
	 * Guice builds this overlay. The annotation has to sit on the constructor
	 * itself — it is private, so nothing else can — and a constant declared
	 * between the two silently steals it, which is how the whole plugin
	 * vanished from the sidebar once already. See InjectionWiringTest.
	 */
	@Inject
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

	/* Read by BankLegendOverlay, so the key and the marks come from one
	   source. A legend that disagrees with what is on the slots is worse
	   than no legend at all. */

	boolean isEnabled()
	{
		return enabled;
	}

	/** Whether a gold mark is on screen — the one stack the card names. */
	boolean hasRecommended()
	{
		return recommendedItemId != null;
	}

	/** Whether any green ring is on screen. Mirrors the SELL-only gate in
	 *  renderItemOverlay: the map may also carry BUY entries, which this
	 *  overlay does not draw. */
	boolean hasSellable()
	{
		for (Advisor.Suggestion s : suggestionsByItem.values())
		{
			if (s != null && s.type == Advisor.Suggestion.Type.SELL)
			{
				return true;
			}
		}
		return false;
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
			tooltipManager.add(new Tooltip(tooltipText(s, isRecommended)));
		}
	}

	/**
	 * The hover text for a marked stack.
	 *
	 * Four lines, in the same order and the same colours whichever mark you
	 * are hovering, because the two used to be laid out differently — the
	 * recommended stack led with a header and the other trailed one, so the
	 * same facts appeared in different places depending on which square you
	 * were over.
	 *
	 *   1  which mark this is, in the legend's own words
	 *   2  what the stack fetches, leading, in profit green
	 *   3  how that number is arrived at — count times unit price
	 *   4  why this moment, when there is a real answer
	 *
	 * Line 2 leads with the FIGURE rather than "Worth selling: 29.6M". The
	 * verb is already on the square you are hovering, and burying the one
	 * number you came for behind two words is what made these hard to read
	 * at a glance.
	 *
	 * Line 3 is new. The tooltip claimed "29.6M gp" and "1,635 gp each" and
	 * left out the count between them, so the one figure that matters could
	 * not be checked against anything.
	 *
	 * RuneLite tooltips are game-markup text, not Swing: the colours are
	 * &lt;col&gt; tags and the breaks are &lt;/br&gt;. A properly drawn panel
	 * would mean a Tooltip(LayoutableRenderableEntity) and a PanelComponent,
	 * neither of which this repo can compile against — see tools/typecheck,
	 * where the stubs are hand-written and have shipped a broken client once
	 * already. Not worth that risk for a border.
	 */
	private static String tooltipText(Advisor.Suggestion s, boolean isRecommended)
	{
		final StringBuilder sb = new StringBuilder();
		/* The legend's wording, verbatim. The bank draws a legend naming
		   these two marks; a tooltip that called them something else would
		   make the player match up two vocabularies for one colour. */
		sb.append("</col><col=").append(isRecommended ? "e5b842" : "a5a5a5").append(">")
			.append(isRecommended ? "Your current suggestion" : "Also worth selling")
			.append("</col>");
		if (s == null)
		{
			/* s can be null on the recommended stack — see the gate above,
			   where the card outranks the suggestion map. The header still
			   stands on its own; there is simply nothing to price. */
			return sb.toString();
		}
		/* grossValue, not expectedProfit. "Worth selling: X" is a claim about
		   what the stack fetches, and on a tracked stack expectedProfit is a
		   gain instead — which reads as a catastrophic undervaluation when
		   the position is barely up, and as a negative number when it is
		   down. What the sale brings in needs no knowledge of what you paid,
		   so it is the one figure that is right in every case. */
		final long worth = worth(s);
		if (worth > 0)
		{
			sb.append("</br><col=1fb85c>")
				.append(QuantityFormatter.quantityToStackSize(worth))
				.append(" gp</col><col=a5a5a5> after tax</col>");
		}
		if (s.price > 0)
		{
			/* a5a5a5, not the 8a8274 this used to be. That brown was two
			   shades off the tooltip's own background and effectively
			   invisible — it is the line in the screenshot that came back as
			   hard to read. */
			sb.append("</br><col=a5a5a5>");
			if (s.quantity > 0)
			{
				sb.append(String.format("%,d", s.quantity)).append(" at ");
			}
			sb.append(String.format("%,d", s.price)).append(" gp each</col>");
		}
		/* And why THIS moment. The mark said a stack was worth selling and
		   what it would fetch, and never why now rather than any other time —
		   asked as "why is this good to sell right now, is it up 30% since
		   you purchased or what". Null whenever there is no real answer. */
		if (s.whyNow != null && !s.whyNow.isEmpty())
		{
			sb.append("</br><col=26a9ab>").append(s.whyNow).append("</col>");
		}
		return sb.toString();
	}

	/**
	 * What the stack fetches, with a floor under it.
	 *
	 * grossValue is the right field and is set on every path that builds a
	 * sell — except that one of them did not, and a stack of 26,000 emeralds
	 * hovered "Worth selling: 0 gp after tax". That is fixed upstream, but a
	 * zero here is ALWAYS wrong and always visible, so it is worth not being
	 * able to print one: price times quantity is the same arithmetic the
	 * missing field would have carried.
	 */
	private static long worth(Advisor.Suggestion s)
	{
		if (s.grossValue > 0)
		{
			return s.grossValue;
		}
		if (s.price > 0 && s.quantity > 0)
		{
			final long net = s.price - FlipTracker.taxPerItem(s.price, s.itemId);
			return Math.max(0, net * s.quantity);
		}
		return 0;
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
