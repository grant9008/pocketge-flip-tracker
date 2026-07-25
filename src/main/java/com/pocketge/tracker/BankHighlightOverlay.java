package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.widgets.WidgetItem;
import net.runelite.client.ui.overlay.WidgetItemOverlay;
import net.runelite.client.util.QuantityFormatter;

/**
 * Copilot-style bank/inventory highlighting: draws a colored border + a
 * small profit badge around any item slot the advisor currently has a
 * live suggestion for (bank, inventory, or equipment — RuneLite calls
 * {@link #renderItemOverlay} for every visible item container widget, so
 * one overlay covers all of them). Gold = buy candidate, teal = sell
 * candidate you're already holding, matching the rest of the panel's
 * color language.
 */
@Singleton
public class BankHighlightOverlay extends WidgetItemOverlay
{
	private static final Color BUY_COLOR = new Color(0xE5, 0xC1, 0x58);
	private static final Color SELL_COLOR = new Color(0x26, 0xA6, 0x9A);

	private volatile Map<Integer, Advisor.Suggestion> suggestionsByItem = Map.of();

	@Inject
	private BankHighlightOverlay()
	{
		showOnInventory();
		showOnBank();
		showOnEquipment();
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

		// Small profit badge in the top-right corner (Copilot's coin-icon
		// affordance, done as a filled dot since we don't ship a sprite).
		int r = 6;
		int cx = bounds.x + bounds.width - r;
		int cy = bounds.y;
		graphics.fillOval(cx, cy, r * 2, r * 2);
		graphics.setColor(Color.BLACK);
		graphics.drawOval(cx, cy, r * 2, r * 2);

		if (s.expectedProfit != 0)
		{
			String label = (s.expectedProfit > 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(s.expectedProfit);
			graphics.setColor(Color.WHITE);
			graphics.setFont(graphics.getFont().deriveFont(java.awt.Font.BOLD, 10f));
			graphics.drawString(label, bounds.x, bounds.y + bounds.height + 11);
		}
	}
}
