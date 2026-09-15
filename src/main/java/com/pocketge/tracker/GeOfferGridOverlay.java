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
	/** Brand gold, matching the ring the bank overlay puts on a recommended
	 *  stack — one colour across the whole plugin for "this is the thing the
	 *  panel is talking about, click here". */
	private static final Color BUY_PROMPT_COLOR = new Color(0xE5, 0xC1, 0x58);
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
		/** What the offer is listed at, and what it should be listed at.
		 *  Both 0 when there is nothing to say. The red border used to be the
		 *  entire message, and a red box around an offer reads as "cancel
		 *  this" long before it reads as "edit this". */
		public long offerPrice;
		public long targetPrice;
		/** True when repricing would fill you into a loser, so the honest
		 *  advice is to take a different flip rather than chase this one. */
		public boolean noMargin;
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

	/** The item the card is proposing you BUY, or null. Volatile: written on
	 *  the Swing EDT when the card changes, read here every frame. */
	private volatile Integer buyPromptItemId;

	/** Tell the overlay the panel is currently proposing a buy, so it can
	 *  point at where the buy starts — an empty slot's Buy button. Null for
	 *  a sell card or no card, which is what stops the ring appearing when
	 *  there is nothing to click. */
	public void setBuyPrompt(Integer itemId)
	{
		this.buyPromptItemId = itemId;
	}

	@Override
	public java.awt.Dimension render(Graphics2D graphics)
	{
		final Map<Integer, SlotView> current = slots;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		drawBuyPrompt(graphics, current);
		if (current.isEmpty())
		{
			return null;
		}
		final Point mouse = client.getMouseCanvasPosition();
		boolean tipShown = false;
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

			/* One tooltip, ever. Adjacent slots can report overlapping bounds,
			   and the loop happily added a tooltip for each — two long blocks
			   of text drawn over each other and over the slots, which is how
			   they became unreadable. First match wins and the rest are
			   skipped. */
			if (!tipShown && mouse != null && bounds.contains(mouse.getX(), mouse.getY()))
			{
				tipShown = true;
				tooltipManager.add(new Tooltip(tooltipText(v)));
			}
		}
		return null;
	}

	/**
	 * Ring the Buy button of a free slot when the card is proposing a buy.
	 *
	 * The Exchange screen is eight identical boxes with two identical little
	 * arrows each, and the panel telling you to buy something does not say
	 * where buying starts. This does.
	 *
	 * The button has no named widget id — InterfaceID.GeOffers stops at
	 * INDEX_0..7, the slot containers — so it is found by asking each child
	 * what its right-click action says. That is sturdier than a hardcoded
	 * child index, which would silently ring the wrong arrow the first time
	 * Jagex reorders the interface. If no child admits to being Buy, the whole
	 * slot is ringed instead: still points at the right box, which is most of
	 * the value, and never points at the wrong thing.
	 */
	private void drawBuyPrompt(Graphics2D graphics, Map<Integer, SlotView> active)
	{
		if (buyPromptItemId == null)
		{
			return;
		}
		final net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		for (int slot = 0; slot < SLOT_WIDGETS.length && slot < offers.length; slot++)
		{
			final net.runelite.api.GrandExchangeOffer o = offers[slot];
			/* Free means free: empty in the client AND not something the
			   plugin is already tracking an offer in. */
			if (o == null || o.getState() != net.runelite.api.GrandExchangeOfferState.EMPTY
				|| active.containsKey(slot))
			{
				continue;
			}
			final Widget w = client.getWidget(SLOT_WIDGETS[slot]);
			if (w == null || w.isHidden())
			{
				continue;
			}
			final Rectangle target = buyButtonBounds(w);
			if (target == null || target.isEmpty())
			{
				continue;
			}
			graphics.setColor(BUY_PROMPT_COLOR);
			graphics.setStroke(new BasicStroke(2f));
			graphics.drawRect(target.x - 1, target.y - 1, target.width + 1, target.height + 1);
			return; // the FIRST free slot only — one ring, one place to click
		}
	}

	/** The Buy control inside a slot, or the slot itself when it cannot be
	 *  identified. See {@link #drawBuyPrompt}. */
	private static Rectangle buyButtonBounds(Widget slot)
	{
		final Widget[][] families = {
			slot.getDynamicChildren(), slot.getStaticChildren(), slot.getNestedChildren()};
		for (Widget[] family : families)
		{
			if (family == null)
			{
				continue;
			}
			for (Widget child : family)
			{
				if (child == null || child.isHidden() || child.getActions() == null)
				{
					continue;
				}
				for (String action : child.getActions())
				{
					if (action != null && action.toLowerCase().contains("buy"))
					{
						return child.getBounds();
					}
				}
			}
		}
		return slot.getBounds();
	}

	/** What Flipping Copilot puts here, in the plugin's own words: what the
	 *  offer is, how far along it is, and what it makes. */
	private static String tooltipText(SlotView v)
	{
		/* Fewer lines, and a heading that looks like one. This ran to seven
		   flat lines of the same colour — every one of them true, and the
		   whole no easier to read than the border it was explaining. The verb
		   and the progress now share the first line, so the item name gets one
		   to itself in the plugin's gold. */
		final StringBuilder sb = new StringBuilder();
		if (v.itemName != null && !v.itemName.isEmpty())
		{
			sb.append("<col=e5c158>").append(v.itemName).append("</col></br>");
		}
		sb.append("<col=8a8274>").append(v.buy ? "Buying " : "Selling ").append("</col>")
			.append(QuantityFormatter.quantityToStackSize(v.filled))
			.append("<col=8a8274> of </col>")
			.append(QuantityFormatter.quantityToStackSize(v.total));

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
		else if (v.needsAdjust)
		{
			/* Why the box went red, and what to do about it. Without this the
			   border was the whole message, and "red" is not an instruction:
			   it named a problem and left you to find the number yourself.
			   Somebody duly aborted an offer, re-placed it at the same price,
			   and asked what they had aborted for.

			   Exact prices, not the abbreviated form the profit lines use:
			   this is a number you are about to type into the game.

			   It says "re-list", not "modify": the Exchange has no way to
			   edit a live offer's price, so the actual sequence is abort,
			   collect, place again. Saying "modify" would be an instruction
			   for a button the game does not have. */
			if (v.noMargin)
			{
				sb.append("</br><col=ef5350>No margin left at the price this would take to fill.</col>")
					.append("</br>Take a new recommendation rather than repricing.");
			}
			else if (v.targetPrice > 0)
			{
				/* The instruction alone. "Priced off the market." above a
				   re-list price was a diagnosis in front of its own cure —
				   the next line says the same thing and says what to do. */
				sb.append("</br><col=ef5350>Off the market \u2014 re-list at </col><col=e5c158>")
					.append(String.format("%,d", v.targetPrice)).append(" gp</col>");
				if (v.offerPrice > 0)
				{
					sb.append(" <col=8a8274>(yours: ").append(String.format("%,d", v.offerPrice))
						.append(" gp)</col>");
				}
				sb.append("</br><col=8a8274>Aborting keeps whatever already filled.</col>");
			}
			else
			{
				sb.append("</br><col=ef5350>Priced off the market.</col>");
			}
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
