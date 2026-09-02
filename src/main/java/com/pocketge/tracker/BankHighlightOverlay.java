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
	private static final int MARK_SIZE = 14;

	private volatile Map<Integer, Advisor.Suggestion> suggestionsByItem = Map.of();
	/** Mirrors PocketGeTrackerConfig.bankHighlights. Checked per slot rather
	 *  than by adding/removing the overlay, so a toggle can never race a
	 *  half-drawn frame. */
	private volatile boolean enabled = true;
	private final BufferedImage markIcon;

	@Inject
	private ItemManager itemManager;

	@Inject
	private Client client;

	@Inject
	private TooltipManager tooltipManager;

	@Inject
	private BankHighlightOverlay()
	{
		showOnInventory();
		showOnBank();
		showOnEquipment();
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

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		if (!enabled)
		{
			return;
		}
		final Advisor.Suggestion s = suggestionsByItem.get(itemId);
		if (s == null || s.type != Advisor.Suggestion.Type.SELL)
		{
			return;
		}
		final Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null || !isMerchantStack(itemId, widgetItem))
		{
			return;
		}
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(SELL_COLOR);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);

		// The PocketGE mark bottom-right — bottom, not top, so it doesn't
		// collide with RuneLite's own quantity label in the slot's top-left.
		graphics.drawImage(markIcon, bounds.x + bounds.width - MARK_SIZE, bounds.y + bounds.height - MARK_SIZE, null);

		/* Say what the border means, on the slot itself. A colour you have
		   to look up somewhere else is a colour that gets ignored, which is
		   exactly what happened to the three this replaced. */
		final Point mouse = client.getMouseCanvasPosition();
		if (mouse != null && bounds.contains(mouse.getX(), mouse.getY()))
		{
			tooltipManager.add(new Tooltip(tooltipText(s)));
		}
	}

	/** Deliberately leads with the money rather than the verb — "sell this"
	 *  is already obvious from the border; what you actually want to know
	 *  standing in your bank is whether this stack is worth the click. */
	private static String tooltipText(Advisor.Suggestion s)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("</col>Worth selling: <col=1fb85c>")
			.append(QuantityFormatter.quantityToStackSize(s.expectedProfit))
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
