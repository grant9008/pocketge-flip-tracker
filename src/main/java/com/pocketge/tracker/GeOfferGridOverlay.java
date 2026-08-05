package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.util.Collections;
import java.util.Map;
import javax.inject.Inject;
import javax.inject.Singleton;
import net.runelite.api.Client;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;

/**
 * Draws a colored border directly on each of the 8 Grand Exchange offer
 * boxes — green if the offer is actively BUYING/SELLING at a price the
 * advisor considers still competitive, red if it's drifted off the market
 * and needs repricing (same "adjust" call the sidebar's ADJUST_BUY/
 * ADJUST_SELL suggestions already make — see PocketGeTrackerPlugin
 * .recomputeAdvice). Matches the at-a-glance green/red convention other
 * flip tools (FlipSmart etc.) already use on this exact screen. Empty and
 * fully bought/sold slots are left untouched — nothing to adjust there.
 */
@Singleton
public class GeOfferGridOverlay extends Overlay
{
	private static final Color OK_COLOR = new Color(0x1F, 0xB8, 0x5C);
	private static final Color ADJUST_COLOR = new Color(0xEF, 0x53, 0x50);
	private static final int[] SLOT_WIDGETS = {
		InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
	};

	private final Client client;
	/** slot index (0-7) -> true (priced fine, green) / false (needs
	 *  adjusting, red). A slot simply absent from the map draws nothing —
	 *  empty, fully filled, or the advisor doesn't have fresh enough quotes
	 *  to judge it yet. */
	private volatile Map<Integer, Boolean> slotStatus = Collections.emptyMap();

	@Inject
	private GeOfferGridOverlay(Client client)
	{
		this.client = client;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Called from the plugin whenever suggestions are recomputed. */
	public void setSlotStatus(Map<Integer, Boolean> bySlot)
	{
		this.slotStatus = bySlot != null ? bySlot : Collections.emptyMap();
	}

	@Override
	public java.awt.Dimension render(Graphics2D graphics)
	{
		if (slotStatus.isEmpty())
		{
			return null;
		}
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		for (Map.Entry<Integer, Boolean> e : slotStatus.entrySet())
		{
			final int slot = e.getKey();
			if (slot < 0 || slot >= SLOT_WIDGETS.length)
			{
				continue;
			}
			final Widget w = client.getWidget(SLOT_WIDGETS[slot]);
			if (w == null || w.isHidden())
			{
				continue;
			}
			final Rectangle bounds = w.getBounds();
			if (bounds == null || bounds.isEmpty())
			{
				continue;
			}
			graphics.setColor(e.getValue() ? OK_COLOR : ADJUST_COLOR);
			graphics.setStroke(new BasicStroke(2f));
			graphics.drawRect(bounds.x + 1, bounds.y + 1, bounds.width - 3, bounds.height - 3);
		}
		return null;
	}
}
