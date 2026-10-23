package com.pocketge.tracker;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.util.ArrayList;
import java.util.List;
import java.util.function.IntPredicate;
import net.runelite.client.util.QuantityFormatter;

/**
 * "+2,000 gp" floating up off a Grand Exchange slot when a batch of its sell
 * goes through — or "-1,250 gp" in red when it lost.
 *
 * Asked for after another plugin's: "if lets say rune darts sold. it would
 * show +200gp or +2000gp or +20kgp profit. for whatever batch just sold / how
 * much profit over that square. it was very slot machine/ brain satisfying.
 * makes you want ot keep this creen open to see the money tick in", and "it
 * also showed - red gp for when you lost money on somthing".
 *
 * The figure is what the batch BOOKED: the flips its units closed, after tax,
 * against what the plugin watched you pay — the same number the flip list
 * gets. A sale with no buy on record to close makes no pop: its takings
 * dressed as profit would be the one number here that lies.
 *
 * A sale nobody saw waits for its square: "even if i missed it live, the
 * next time i load into this ge screen, it should have said +64kgp over rune
 * darts in that square". What has not been shown yet is kept on the offer's
 * own saved record — FlipTracker.unseenBy — and the overlay starts a figure
 * here the first time the square is on screen with something unshown. This
 * class is only the figures on screen and how they move.
 *
 * Holds no RuneLite types beyond the formatter, so the timing and the drawing
 * are tested without a client.
 */
final class ProfitPops
{
	/** How long one is on screen, from the pop to gone. */
	static final long LIFE_MS = 3_000;
	/** A batch this soon after the slot's last figure started is the same
	 *  sale to the eye: added to its figure, which pops again. Two game ticks —
	 *  fills on back-to-back ticks land about 600 ms apart, give or take, and
	 *  a window of exactly one tick split them at random. */
	static final long MERGE_MS = 1_200;
	/** How far it rises over its life. */
	static final int RISE_PX = 22;
	/** Fully opaque until this far through, then fades out. */
	private static final float FADE_FROM = 0.55f;
	/** Where it starts, down the slot: on the game's progress bar. */
	private static final float START_AT = 0.74f;
	private static final int[][] OUTLINE = {{-1, 0}, {1, 0}, {0, -1}, {0, 1}, {1, 1}};
	/** One per slot, so eight; a guard, past which the oldest goes. */
	static final int MAX = 16;
	/** Brighter than TipStyle.GOOD and .BAD, which are drawn on a tooltip's
	 *  near-black: these sit on the Exchange's brown, over sprites and text,
	 *  with only a one-pixel outline to hold them off it. */
	static final Color GAIN = new Color(0x3D, 0xE0, 0x76);
	static final Color LOSS = new Color(0xFF, 0x5A, 0x55);
	static final Color EVEN = TipStyle.MUTED;

	/** One figure on screen: which slot, when it popped, and how much. */
	static final class Pop
	{
		final int slot;
		final long at;
		final long profit;

		Pop(int slot, long at, long profit)
		{
			this.slot = slot;
			this.at = at;
			this.profit = profit;
		}
	}

	/* Started and read on the client thread, by the overlay. */
	private final List<Pop> pops = new ArrayList<>();

	/**
	 * {@code slot} has {@code profit} to show, now.
	 *
	 * One figure per slot at a time. Each rises only a line's height over its
	 * life, so two on one slot a second or so apart were drawn over each
	 * other into a smear. A batch within a moment of the slot's last figure
	 * adds to it and pops it again — the number ticking up; a later one takes
	 * the slot over with its own.
	 */
	synchronized void start(int slot, long profit, long now)
	{
		prune(now);
		long made = profit;
		for (int i = pops.size() - 1; i >= 0; i--)
		{
			final Pop p = pops.get(i);
			if (p.slot == slot)
			{
				if (now - p.at < MERGE_MS)
				{
					made += p.profit;
				}
				pops.remove(i);
			}
		}
		pops.add(new Pop(slot, now, made));
		while (pops.size() > MAX)
		{
			pops.remove(0);
		}
	}

	/** The figures to draw at {@code now}, oldest first: those whose slot
	 *  is on screen. One whose square went away runs out unseen — it was
	 *  seen when it started. */
	synchronized List<Pop> running(long now, IntPredicate onScreen)
	{
		prune(now);
		final List<Pop> out = new ArrayList<>();
		for (Pop p : pops)
		{
			if (onScreen.test(p.slot))
			{
				out.add(p);
			}
		}
		return out;
	}

	/** Every figure off: the setting switched off. */
	synchronized void clear()
	{
		pops.clear();
	}

	private void prune(long now)
	{
		/* Gone once their life is over — and any dated ahead of now, which a
		   clock set back would otherwise leave hanging in the air for as long
		   as it was set back by. */
		pops.removeIf(p -> now - p.at >= LIFE_MS || p.at > now);
	}

	/** "+2,000 gp", "+20K gp", "-12K gp": the game's own stack-size
	 *  abbreviations, exact under 10,000 — with the comma there written
	 *  here, not left to whichever formatter is on the classpath. */
	static String text(long profit)
	{
		final long size = profit == Long.MIN_VALUE ? Long.MAX_VALUE : Math.abs(profit);
		return (profit < 0 ? "-" : "+")
			+ (size < 10_000 ? String.format(java.util.Locale.ENGLISH, "%,d", size) : QuantityFormatter.quantityToStackSize(size))
			+ " gp";
	}

	static Color colour(long profit)
	{
		return profit > 0 ? GAIN : profit < 0 ? LOSS : EVEN;
	}

	/** How far up it has risen: quick off the slot, slowing as it goes. */
	static int rise(long age)
	{
		final float t = clamp01((float) age / LIFE_MS);
		return Math.round(RISE_PX * (1f - (1f - t) * (1f - t)));
	}

	/** Opaque, then fading over the back part of its life. */
	static float alpha(long age)
	{
		if (age < 0 || age >= LIFE_MS)
		{
			return 0f;
		}
		final float t = (float) age / LIFE_MS;
		return t <= FADE_FROM ? 1f : clamp01(1f - (t - FADE_FROM) / (1f - FADE_FROM));
	}

	/** The pop: in small, a touch past full size, then settling — the bit
	 *  that makes it read as an arrival rather than a label. */
	static float scale(long age)
	{
		if (age < 100)
		{
			return 0.6f + 0.55f * clamp01(age / 100f);
		}
		if (age < 220)
		{
			return 1.15f - 0.15f * ((age - 100) / 120f);
		}
		return 1f;
	}

	/** Draws {@code p} over the slot at {@code slot}, centred, risen and
	 *  faded for its age. Nothing once it has run its course. */
	static void draw(Graphics2D g, Rectangle slot, Pop p, long now, Font font)
	{
		final long age = now - p.at;
		final float a = alpha(age);
		if (a <= 0f)
		{
			return;
		}
		final String s = text(p.profit);
		final Graphics2D g2 = (Graphics2D) g.create();
		try
		{
			g2.setFont(font.deriveFont(font.getSize2D() * scale(age)));
			final FontMetrics fm = g2.getFontMetrics();
			final int x = slot.x + (slot.width - fm.stringWidth(s)) / 2;
			/* Off the slot's progress bar — the plainest, darkest strip in the
			   box, where it reads cleanly the moment it lands — and up over the
			   item. From the middle of the box it landed on the item's name,
			   and the two smeared into each other. */
			final int y = slot.y + Math.round(slot.height * START_AT) + fm.getAscent() / 2 - rise(age);
			g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
			/* Outlined, the way the game outlines its own floating text: a
			   drop shadow alone left the strokes running into the sprites. */
			g2.setColor(Color.BLACK);
			for (int[] d : OUTLINE)
			{
				g2.drawString(s, x + d[0], y + d[1]);
			}
			g2.setColor(colour(p.profit));
			g2.drawString(s, x, y);
		}
		finally
		{
			g2.dispose();
		}
	}

	private static float clamp01(float v)
	{
		return v < 0f ? 0f : v > 1f ? 1f : v;
	}
}
