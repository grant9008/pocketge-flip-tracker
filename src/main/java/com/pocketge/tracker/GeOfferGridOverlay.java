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
import net.runelite.api.Point;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.ui.overlay.Overlay;
import net.runelite.client.ui.overlay.OverlayLayer;
import net.runelite.client.ui.overlay.OverlayPosition;
import net.runelite.client.ui.overlay.tooltip.Tooltip;
import net.runelite.client.ui.overlay.tooltip.TooltipManager;
import net.runelite.client.util.QuantityFormatter;

/**
 * What the plugin knows about your 8 Grand Exchange slots, drawn onto the
 * slots themselves: a coloured border (green while the offer is still
 * competitively priced, red once it has drifted and wants repricing — the
 * same call the sidebar's ADJUST_BUY/ADJUST_SELL suggestions make), and, on
 * hover, what the offer actually makes you, measured against what the plugin
 * watched you pay.
 *
 * There is deliberately NO progress bar here. The game already prints the
 * quantity in the box, and a bar drawn over Jagex's own interface is decor
 * competing with the numbers it duplicates. Progress belongs on the sidebar
 * strip, where the slot is a 32px sprite with nothing else to say — see
 * GeSlotsPanel.
 *
 * Empty slots are left alone. A slot you have told the plugin to stop
 * advising on keeps its tooltip but loses the red border — see
 * {@link SlotView#adviceSkipped}.
 */
@Singleton
public class GeOfferGridOverlay extends Overlay
{
	private static final Color OK_COLOR = new Color(0x1F, 0xB8, 0x5C);
	private static final Color ADJUST_COLOR = new Color(0xEF, 0x53, 0x50);
	/** Muted border for a slot you have opted out of advice on. Still drawn,
	 *  so the slot does not look unmonitored — just not shouting. */
	private static final Color MUTED_COLOR = new Color(0x8A, 0x82, 0x74);
	private static final int[] SLOT_WIDGETS = {
		InterfaceID.GeOffers.INDEX_0, InterfaceID.GeOffers.INDEX_1, InterfaceID.GeOffers.INDEX_2,
		InterfaceID.GeOffers.INDEX_3, InterfaceID.GeOffers.INDEX_4, InterfaceID.GeOffers.INDEX_5,
		InterfaceID.GeOffers.INDEX_6, InterfaceID.GeOffers.INDEX_7,
	};

	/** One slot, as the overlay needs it. Built on the client thread in
	 *  PocketGeTrackerPlugin and published as one immutable map, so a render
	 *  can never catch half of an update. */
	public static class SlotView
	{
		public String itemName;
		public boolean buy;
		public int filled;
		public int total;
		/** True when the advisor wants this offer repriced. */
		public boolean needsAdjust;
		/** True when you have right-clicked the slot and told the plugin you
		 *  are pricing this one yourself. Suppresses the red border. */
		public boolean adviceSkipped;
		/** Projected profit over the WHOLE offer, after tax, or null when
		 *  there is no cost basis to measure it against. Never guessed: a
		 *  stack with no tracked purchase gets null, not zero. */
		public Long projectedProfit;
		/** Profit on what has actually filled so far, same rules. */
		public Long filledProfit;
	}

	private final Client client;
	private final TooltipManager tooltipManager;
	/** slot index (0-7) -> what to draw. A slot absent from the map draws
	 *  nothing: empty, or the advisor has nothing fresh enough to say. */
	private volatile Map<Integer, SlotView> slots = Collections.emptyMap();

	@Inject
	private GeOfferGridOverlay(Client client, TooltipManager tooltipManager)
	{
		this.client = client;
		this.tooltipManager = tooltipManager;
		setPosition(OverlayPosition.DYNAMIC);
		setLayer(OverlayLayer.ABOVE_WIDGETS);
	}

	/** Called from the plugin whenever suggestions are recomputed. */
	public void setSlots(Map<Integer, SlotView> bySlot)
	{
		this.slots = bySlot != null ? bySlot : Collections.emptyMap();
	}

	@Override
	public java.awt.Dimension render(Graphics2D graphics)
	{
		final Map<Integer, SlotView> current = slots;
		if (current.isEmpty())
		{
			return null;
		}
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		final Point mouse = client.getMouseCanvasPosition();
		for (Map.Entry<Integer, SlotView> e : current.entrySet())
		{
			final int slot = e.getKey();
			final SlotView v = e.getValue();
			if (slot < 0 || slot >= SLOT_WIDGETS.length || v == null)
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

			graphics.setColor(v.adviceSkipped ? MUTED_COLOR : v.needsAdjust ? ADJUST_COLOR : OK_COLOR);
			graphics.setStroke(new BasicStroke(2f));
			graphics.drawRect(bounds.x + 1, bounds.y + 1, bounds.width - 3, bounds.height - 3);

			if (mouse != null && bounds.contains(mouse.getX(), mouse.getY()))
			{
				tooltipManager.add(new Tooltip(tooltipText(v)));
			}
		}
		return null;
	}

	/** What Flipping Copilot puts here, in the plugin's own words: what the
	 *  offer is, how far along it is, and what it makes. */
	private static String tooltipText(SlotView v)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append(v.buy ? "Buying" : "Selling");
		if (v.itemName != null && !v.itemName.isEmpty())
		{
			sb.append(": ").append(v.itemName);
		}
		sb.append("</br>").append(QuantityFormatter.quantityToStackSize(v.filled))
			.append(" / ").append(QuantityFormatter.quantityToStackSize(v.total));

		if (v.projectedProfit != null)
		{
			sb.append("</br>").append(v.buy ? "Profit if it flips: " : "Profit: ")
				.append(money(v.projectedProfit));
			/* Only worth a second line once the two genuinely differ — on an
			   untouched or a completed offer they are the same number, and
			   printing it twice just makes the tooltip taller. */
			if (v.filledProfit != null && !v.filledProfit.equals(v.projectedProfit))
			{
				sb.append("</br><col=8a8274>So far: </col>").append(money(v.filledProfit));
			}
		}
		else if (!v.buy)
		{
			/* Never a zero here. The plugin not having watched you buy
			   something is not the same as that thing having cost nothing,
			   and "Profit: 5.3M" measured from a cost of zero is how a stack
			   you have held for a year claims a win it never made. */
			sb.append("</br>No purchase tracked, so there is no profit to measure.");
		}
		if (v.adviceSkipped)
		{
			sb.append("</br>Price advice off for this offer.");
		}
		return sb.toString();
	}

	/** Green for a gain, red for a loss — the same pair the sidebar uses, so
	 *  an underwater offer is obvious without reading the minus sign. */
	private static String money(long v)
	{
		return "<col=" + (v >= 0 ? "1fb85c" : "ef5350") + ">"
			+ (v >= 0 ? "+" : "") + QuantityFormatter.quantityToStackSize(v) + " gp</col>";
	}
}
