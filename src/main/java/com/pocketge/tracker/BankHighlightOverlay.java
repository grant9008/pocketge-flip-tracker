package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
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
 * one overlay covers all of them). Yellow/gold border = buy candidate
 * (good time to pick up more), green = sell candidate you're already
 * holding, matching the rest of the panel's color language (the same
 * green GeSlotsPanel/GeOfferGridOverlay use for "priced fine"); the
 * corner mark itself is always the PocketGE icon so
 * "this is what we're pointing you at" reads as our brand at a glance.
 * Held items (the sidebar's "Hold" button / right-click "Never recommend")
 * get a distinct muted marker instead — the opposite message, "we're
 * deliberately NOT pointing you at this."
 */
@Singleton
public class BankHighlightOverlay extends WidgetItemOverlay
{
	private static final Color BUY_COLOR = new Color(0xE5, 0xC1, 0x58);
	private static final Color SELL_COLOR = new Color(0x1F, 0xB8, 0x5C);
	private static final Color HELD_COLOR = new Color(0x8A, 0x82, 0x74);
	private static final int MARK_SIZE = 14;

	private volatile Map<Integer, Advisor.Suggestion> suggestionsByItem = Map.of();
	private volatile Set<Integer> heldItemIds = Collections.emptySet();
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

	/** Item ids currently on the never-recommend list (the sidebar's "Hold"
	 *  button, or right-click "Never recommend") — same resolved id set
	 *  Advisor.advise() itself excludes suggestions for, see
	 *  PocketGeTrackerPlugin.blockedIds(). */
	public void setHeldItems(Set<Integer> ids)
	{
		this.heldItemIds = ids != null ? ids : Collections.emptySet();
	}

	@Override
	public void renderItemOverlay(Graphics2D graphics, int itemId, WidgetItem widgetItem)
	{
		final Rectangle bounds = widgetItem.getCanvasBounds();
		if (bounds == null)
		{
			return;
		}
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		if (heldItemIds.contains(itemId))
		{
			graphics.setColor(HELD_COLOR);
			graphics.setStroke(new BasicStroke(1.5f, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 1f, new float[]{4f, 3f}, 0f));
			graphics.drawRect(bounds.x, bounds.y, bounds.width - 1, bounds.height - 1);
			drawHeldMark(graphics, bounds.x + bounds.width - MARK_SIZE, bounds.y + bounds.height - MARK_SIZE);
			return;
		}

		Advisor.Suggestion s = suggestionsByItem.get(itemId);
		if (s == null)
		{
			return;
		}
		Color color = s.type == Advisor.Suggestion.Type.SELL ? SELL_COLOR : BUY_COLOR;

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

	/** A small pause-bars glyph (⏸, drawn rather than an emoji glyph — font
	 *  fallback for pause/hold symbols is inconsistent across the JREs
	 *  RuneLite runs on) marking a held item's corner, mirroring where the
	 *  PocketGE mark sits on an active suggestion. */
	private void drawHeldMark(Graphics2D graphics, int x, int y)
	{
		graphics.setColor(HELD_COLOR);
		final int barW = 3, barH = 10, gap = 3;
		final int totalW = barW * 2 + gap;
		final int ox = x + (MARK_SIZE - totalW) / 2;
		final int oy = y + (MARK_SIZE - barH) / 2;
		graphics.fillRect(ox, oy, barW, barH);
		graphics.fillRect(ox + barW + gap, oy, barW, barH);
	}
}
