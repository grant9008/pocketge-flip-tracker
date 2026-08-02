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
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.ImageUtil;

/**
 * Copilot-style bank/inventory highlighting: draws a colored border + our
 * gold-scimitar mark (the same icon on the RuneLite toolbar button, not a
 * generic dot) around any item slot the advisor currently has a live
 * suggestion for (bank, inventory, or equipment — RuneLite calls
 * {@link #renderItemOverlay} for every visible item container widget, so
 * one overlay covers all of them). Gold border = buy candidate, teal = sell
 * candidate you're already holding, matching the rest of the panel's
 * color language; the corner mark itself is always the PocketGE icon so
 * "this is what we're pointing you at" reads as our brand at a glance.
 */
@Singleton
public class BankHighlightOverlay extends WidgetItemOverlay
{
	private static final Color BUY_COLOR = new Color(0xE5, 0xC1, 0x58);
	private static final Color SELL_COLOR = new Color(0x26, 0xA6, 0x9A);
	private static final int MARK_SIZE = 14;

	private volatile Map<Integer, Advisor.Suggestion> suggestionsByItem = Map.of();
	private final BufferedImage markIcon;

	@Inject
	private BankHighlightOverlay()
	{
		showOnInventory();
		showOnBank();
		showOnEquipment();
		markIcon = ImageUtil.resizeImage(ImageUtil.loadImageResource(getClass(), "icon.png"), MARK_SIZE, MARK_SIZE);
	}

	/** Called from the plugin whenever suggestions are recomputed. Keyed by
	 *  item id so a lookup per rendered slot is O(1). */
	public void setSuggestions(Map<Integer, Advisor.Suggestion> byItem)
	{
		this.suggestionsByItem = byItem != null ? byItem : Map.of();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		Advisor.Suggestion s = suggestionsByItem.get(itemId);
		if (s == null)
		{
			return;
		}
		Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		Color color = s.type == Advisor.Suggestion.Type.SELL ? SELL_COLOR : BUY_COLOR;

		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		graphics.setColor(color);
		graphics.setStroke(new BasicStroke(1.5f));
		graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);

		// The PocketGE mark in the bottom-right corner — the same "here's what
		// we're pointing you at" affordance as a generic dot, but branded.
		// Bottom (not top) so it doesn't collide with RuneLite's own item
		// quantity label, which sits top-left of every bank slot.
		int mx = bounds.x + bounds.width - MARK_SIZE;
		int my = bounds.y + bounds.height - MARK_SIZE;
		graphics.drawImage(markIcon, mx, my, null);
	}
}
