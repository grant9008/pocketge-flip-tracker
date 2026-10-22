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
import net.runelite.client.ui.FontManager;
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
	/* One copy of each, in GeSlotsPanel: the strip and this grid describe
	   the same slots, and two hand-written copies of a colour drift. */
	private static final Color OK_COLOR = GeSlotsPanel.OK_COLOR;
	private static final Color COLLECT_COLOR = GeSlotsPanel.COLLECT_COLOR;
	/** Brand gold, matching the ring the bank overlay puts on a recommended
	 *  stack — one colour across the whole plugin for "this is the thing the
	 *  panel is talking about, click here". */
	/*
	 * White, for a buy and for a sell alike.
	 *
	 * This was brand gold, which read as "buy" next to a card whose buy
	 * colour is gold — and there was no sell equivalent at all, so a sell
	 * card pointed at nothing. Reported as "buy advice gets a yellow buy
	 * over buy slot in ge, but the sell advice isnt doing the opposite".
	 *
	 * The ring is not classifying the trade; the button underneath it already
	 * says Buy or Sell in the game's own words. It is saying "act here", and
	 * white is what the rest of the plugin uses for that — LEAD_RIM on the
	 * price box you are about to type into, and the bank square for the stack
	 * the sidebar is talking about. Same value as both.
	 */
	private static final Color ACT_HERE_COLOR = new Color(0xF2, 0xF2, 0xF2);
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
		/** Done — bought, sold or cancelled — with something to collect. */
		public boolean ready;
		/** True when you have right-clicked the slot and told the plugin you
		 *  are pricing this one yourself. Suppresses the red border. */
		public boolean adviceSkipped;
		/** Projected profit after tax, or null when there is no cost basis
		 *  to measure it against. Never guessed: a stack with no tracked
		 *  purchase gets null, not zero. A buy: the whole offer, if it flips.
		 *  A sell: the part still listed — what has sold is booked already,
		 *  in {@link #bookedProfit}. */
		public Long projectedProfit;
		/** A sell: what its sales so far have booked, from the flips they
		 *  closed; null before the first. */
		public Long bookedProfit;
		/** How many units {@link #bookedProfit} is over. Fewer than have
		 *  sold when some sold where the plugin could not book them. */
		public long bookedUnits;
		/** What the offer is listed at, and what it should be listed at.
		 *  Both 0 when there is nothing to say. The red border used to be the
		 *  entire message, and a red box around an offer reads as "cancel
		 *  this" long before it reads as "edit this". */
		public long offerPrice;
		public long targetPrice;
		/** True when repricing would fill you into a loser, so the honest
		 *  advice is to take a different flip rather than chase this one. */
		public boolean noMargin;
		/** A buy that fills at a loss: targetPrice is a LOWER bid, and
		 *  breakEven the most that can be bid for +0. */
		public boolean losing;
		public long breakEven;
		/**
		 * SELL only: what the whole offer fetches after tax, at the price this
		 * tooltip is talking about — the target when there is one, the listed
		 * price otherwise.
		 *
		 * Not a profit, and never painted as one. It needs no cost basis,
		 * which is the point: a stack held since before the plugin watched you
		 * buy it has no profit to show, and "Cost unknown, so no profit to
		 * show." was the whole money line on those — a sentence that answers
		 * the question by declining it. Asked for directly: "it should still
		 * say how much this trade is going to make me, in addition to asking
		 * me to adjust the porce".
		 */
		public Long grossValue;
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

	/** The item the card is proposing you trade, or null. Volatile: written
	 *  on the Swing EDT when the card changes, read here every frame. */
	private volatile Integer actPromptItemId;
	/** …and which side, so the ring lands on Sell rather than Buy. */
	private volatile boolean actPromptSell;

	/** Tell the overlay what the panel is proposing, so it can point at where
	 *  that trade starts — an empty slot's Buy or Sell button. Null for no
	 *  card, which is what stops the ring appearing when there is nothing to
	 *  click. */
	public void setActionPrompt(Integer itemId, boolean sell)
	{
		this.actPromptItemId = itemId;
		this.actPromptSell = sell;
	}

	/** "+2,000 gp" rising off a slot as a batch of its sell goes through. */
	private final ProfitPops pops = new ProfitPops();

	/** A batch of {@code slot}'s sell just booked {@code profit}. The plugin
	 *  calls this for live sales only — see ProfitPops. */
	public void pop(int slot, long profit, long now)
	{
		pops.add(slot, profit, now);
	}

	@Override
	public java.awt.Dimension render(Graphics2D graphics)
	{
		final Map<Integer, SlotView> current = slots;
		graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		drawActionPrompt(graphics, current);
		drawSlots(graphics, current);
		/* Last, so the figure is over the borders rather than under them. */
		drawPops(graphics);
		return null;
	}

	private void drawPops(Graphics2D graphics)
	{
		final long now = System.currentTimeMillis();
		final java.util.List<ProfitPops.Pop> live = pops.live(now);
		for (ProfitPops.Pop p : live)
		{
			if (p.slot < 0 || p.slot >= SLOT_WIDGETS.length)
			{
				continue;
			}
			/* Only over the slot screen. On the offer screen, or with the
			   Exchange shut, it runs its course unseen — this is for watching
			   the slots, not a notification to catch up on later. */
			final Widget w = client.getWidget(SLOT_WIDGETS[p.slot]);
			if (w == null || w.isHidden())
			{
				continue;
			}
			final Rectangle bounds = w.getBounds();
			if (bounds == null || bounds.isEmpty())
			{
				continue;
			}
			ProfitPops.draw(graphics, bounds, p, now, FontManager.getRunescapeBoldFont());
		}
	}

	private void drawSlots(Graphics2D graphics, Map<Integer, SlotView> current)
	{
		if (current.isEmpty())
		{
			return;
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

			/* A border only when there is something to say: white, "act
			   here", when it needs a new price, green when it is done — see
			   GeSlotsPanel.box, which the sidebar squares share.
			   An offer that is working gets none — the game's own box already
			   shows it working, and a ring round every busy slot would be
			   eight borders saying "nothing to see". The hover still works on
			   all of them. */
			final Color border = v.ready ? COLLECT_COLOR
				: v.adviceSkipped || !v.needsAdjust ? null
				: GeSlotsPanel.REPRICE_COLOR;
			if (border != null)
			{
				graphics.setColor(border);
				graphics.setStroke(new BasicStroke(2f));
				graphics.drawRect(bounds.x + 1, bounds.y + 1, bounds.width - 3, bounds.height - 3);
			}

			/* One tooltip, ever. Adjacent slots can report overlapping bounds,
			   and the loop happily added a tooltip for each — two long blocks
			   of text drawn over each other and over the slots, which is how
			   they became unreadable. First match wins and the rest are
			   skipped. */
			if (!tipShown && mouse != null && bounds.contains(mouse.getX(), mouse.getY()))
			{
				tipShown = true;
				tooltipManager.add(TipStyle.tooltip(tooltipText(v)));
			}
		}
	}

	/**
	 * Ring the Buy or Sell button of a free slot, matching what the card is
	 * proposing.
	 *
	 * The Exchange screen is eight identical boxes with two identical little
	 * arrows each, and the panel telling you to trade something does not say
	 * where that starts. This does — for either side, since a sell begins in
	 * a free slot exactly as a buy does.
	 *
	 * The button has no named widget id — InterfaceID.GeOffers stops at
	 * INDEX_0..7, the slot containers — so it is found by asking each child
	 * what its right-click action says. That is sturdier than a hardcoded
	 * child index, which would silently ring the wrong arrow the first time
	 * Jagex reorders the interface. If no child admits to being Buy, the whole
	 * slot is ringed instead: still points at the right box, which is most of
	 * the value, and never points at the wrong thing.
	 */
	private void drawActionPrompt(Graphics2D graphics, Map<Integer, SlotView> active)
	{
		if (actPromptItemId == null)
		{
			return;
		}
		final net.runelite.api.GrandExchangeOffer[] offers = client.getGrandExchangeOffers();
		if (offers == null)
		{
			return;
		}
		/* Only the slots this world lets you open. A free world has three;
		   the other five are drawn, report EMPTY, and cannot be used — and
		   the ring used to land on the first of them. */
		final int usable = Math.min(SLOT_WIDGETS.length,
			Math.min(offers.length, PocketGeTrackerPlugin.usableGeSlots(client)));
		for (int slot = 0; slot < usable; slot++)
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
			final Rectangle target = actionButtonBounds(w, actPromptSell ? "sell" : "buy");
			if (target == null || target.isEmpty())
			{
				continue;
			}
			graphics.setColor(ACT_HERE_COLOR);
			graphics.setStroke(new BasicStroke(2f));
			graphics.drawRect(target.x - 1, target.y - 1, target.width + 1, target.height + 1);
			return; // the FIRST free slot only — one ring, one place to click
		}
	}

	/** The Buy or Sell control inside a slot, or the slot itself when it
	 *  cannot be identified. See {@link #drawActionPrompt}. */
	private static Rectangle actionButtonBounds(Widget slot, String action)
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
				for (String a : child.getActions())
				{
					if (a != null && a.toLowerCase().contains(action))
					{
						return child.getBounds();
					}
				}
			}
		}
		return slot.getBounds();
	}

	/**
	 * Three lines at most: what it is, what to do, what it makes.
	 *
	 * This ran to six — item, "Buying N of M", projected profit, profit so
	 * far, a diagnosis and an instruction — every line true and the whole
	 * thing no easier to read than the border it was explaining. The report
	 * was simply "such a long hover tooltip"; what was wanted was the border's
	 * colour plus a couple of words.
	 *
	 * So the progress line is gone, for the reason the class comment already
	 * gives about the progress bar: the game prints the quantity in the box,
	 * and repeating it is decor competing with the numbers it duplicates.
	 * "So far" is gone too — it is the same figure as the projection until an
	 * offer is part-filled, and when it differs it is a detail, not a verdict.
	 *
	 * What is left is a VERB. "Off the market" named a problem and left you
	 * to find the number; "Lower your ask to 788 gp" is the whole thing.
	 */
	/**
	 * The hover's money line: "Profit if it flips" on a buy; on a sell that
	 * has started selling, what it has made so far and what is still to come
	 * — "295k of x amount total 85k left". One line, so this hover keeps to
	 * its three. Null when there is nothing honest to say. The sidebar square
	 * says the same: GeSlotsPanel.moneyLine.
	 */
	static String moneyLine(boolean buy, Long projected, Long booked, long bookedUnits, long sold)
	{
		if (!buy && booked != null)
		{
			/* Booked on every unit sold, or only on some: sold with the
			   plugin not watching, or with no buy on record. Then the figure
			   says what it covers, and there is no "in all" to give. */
			final boolean whole = bookedUnits >= sold;
			final StringBuilder sb = new StringBuilder()
				.append(TipStyle.muted("Made ")).append(TipStyle.money(booked))
				.append(TipStyle.muted(whole ? " so far" : " on " + String.format("%,d", bookedUnits)
					+ " of " + String.format("%,d", sold) + " sold"));
			if (projected != null)
			{
				sb.append(TipStyle.muted(", ")).append(TipStyle.money(projected))
					.append(TipStyle.muted(" to come" + (whole ? " (" + signedShort(booked + projected) + " in all)" : "")));
			}
			return sb.toString();
		}
		if (projected == null)
		{
			return null;
		}
		return TipStyle.muted(buy ? "Profit if it flips: " : "Profit: ") + TipStyle.money(projected);
	}

	private static String signedShort(long v)
	{
		return (v >= 0 ? "+" : "-") + QuantityFormatter.quantityToStackSize(Math.abs(v));
	}

	private static String tooltipText(SlotView v)
	{
		final StringBuilder sb = new StringBuilder();
		if (v.itemName != null && !v.itemName.isEmpty())
		{
			/* Parchment, the same colour the sidebar names items in — not
				   the near-gold e5c158 this used to be, which matched no
				   other colour in the plugin. See TipStyle. */
				sb.append(TipStyle.subject(v.itemName)).append(TipStyle.BREAK);
		}
		sb.append(stateLine(v));
		/* The money, and only when there is a real cost behind it. */
		final String money = moneyLine(v.buy, v.projectedProfit, v.bookedProfit, v.bookedUnits, v.filled);
		if (money != null)
		{
			sb.append(TipStyle.BREAK).append(money);
		}
		else if (!v.buy && v.grossValue != null && v.grossValue > 0)
		{
			/*
			 * Proceeds, because profit cannot be had.
			 *
			 * Never a zero profit here. The plugin not having watched you buy
			 * something is not the same as that thing having cost nothing, and
			 * "Profit: 5.3M" measured from a cost of zero is how a stack you
			 * have held for a year claims a win it never made. But the line
			 * this replaces — "Cost unknown, so no profit to show." — answered
			 * the question by declining it, on exactly the offers where the
			 * number you actually want is the simpler one: what lands in your
			 * pocket.
			 *
			 * Parchment, not the profit green. The sidebar spent three
			 * attempts learning that a figure in the profit colour reads as
			 * profit however it is worded, and the bank tooltip carries the
			 * same rule for the same figure.
			 *
			 * It reads as "at that price" because the line above it is the one
			 * naming the price — see grossValue, which is computed at whatever
			 * price this tooltip is recommending.
			 */
			sb.append(TipStyle.BREAK)
				.append(TipStyle.muted("Sells for "))
				.append(TipStyle.figure(QuantityFormatter.quantityToStackSize(v.grossValue) + " gp"))
				.append(TipStyle.muted(" after tax"));
		}
		else if (!v.buy)
		{
			sb.append(TipStyle.BREAK).append(TipStyle.muted("Cost unknown, so no profit to show."));
		}
		return sb.toString();
	}

	/**
	 * The one line that says what state this slot is in, in the fewest words
	 * that are still an instruction.
	 *
	 * It says "re-list", never "modify": the Exchange has no way to edit a
	 * live offer's price, so the real sequence is abort, collect, place
	 * again, and naming a button the game does not have is worse than saying
	 * nothing. The direction is spelled out rather than left to the colour —
	 * a buy that is mispriced is ALWAYS too low and a sell ALWAYS too high,
	 * so "raise" and "lower" are never ambiguous.
	 */
	private static String stateLine(SlotView v)
	{
		if (v.ready)
		{
			return TipStyle.state(COLLECT_COLOR, "Done \u2014 ready to collect.");
		}
		if (v.adviceSkipped)
		{
			return TipStyle.muted("You are pricing this one.");
		}
		if (!v.needsAdjust)
		{
			/* Green has always meant "not red", which is true and says
			   nothing. Reported as: I ignored the suggested price, typed my
			   own, and the box stayed green — was it listening? It was; the
			   difference was inside the drift threshold. So the green state
			   says so out loud rather than leaving it to be inferred. */
			/* OK_COLOR, the colour of the border this sentence is
				   explaining — not a hex literal that happened to match it. */
			return TipStyle.state(OK_COLOR, "Priced fine \u2014 leave it.");
		}
		if (v.losing && v.targetPrice > 0)
		{
			/* Fills, but at a loss: the fix is a LOWER bid, and the most that
			   can be bid for +0 is worth saying when the suggestion is lower
			   still. */
			final StringBuilder sb = new StringBuilder();
			sb.append(TipStyle.state(GeSlotsPanel.REPRICE_COLOR, "Loses at this bid \u2014 lower it to "))
				.append(TipStyle.figure(String.format("%,d", v.targetPrice) + " gp"));
			if (v.breakEven > v.targetPrice)
			{
				sb.append(" ").append(TipStyle.muted("(" + String.format("%,d", v.breakEven) + " at most for +0)"));
			}
			return sb.toString();
		}
		if (v.noMargin)
		{
			return TipStyle.state(GeSlotsPanel.REPRICE_COLOR, "No margin left \u2014 take a new flip.");
		}
		if (v.targetPrice > 0)
		{
			/* Exact, not abbreviated like the profit line: this is a number
			   you are about to type into the game. */
			final StringBuilder sb = new StringBuilder();
			sb.append(TipStyle.state(GeSlotsPanel.REPRICE_COLOR, GeSlotsPanel.adjustVerb(v.buy)))
				.append(TipStyle.figure(String.format("%,d", v.targetPrice) + " gp"));
			if (v.buy)
			{
				sb.append(TipStyle.state(GeSlotsPanel.REPRICE_COLOR, GeSlotsPanel.adjustTail(true)));
			}
			if (v.offerPrice > 0)
			{
				sb.append(" ").append(TipStyle.muted(
					"(yours: " + String.format("%,d", v.offerPrice) + ")"));
			}
			return sb.toString();
		}
		return TipStyle.state(GeSlotsPanel.REPRICE_COLOR, "Priced off the market \u2014 re-list.");
	}

}
