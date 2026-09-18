package com.pocketge.tracker;

/**
 * How big the stack you are holding is against the number of units buyers
 * actually took off this item in the last 24 hours — "your 10,787 is 2.3 days
 * of demand".
 *
 * This is what a sell card says in the slot where a buy card shows its flip
 * score. It is deliberately NOT a score.
 *
 * <h2>Why not a score</h2>
 * Four candidate 0-100 exit scores were specified and picked apart before this
 * one line survived, and the three that lost are worth recording because each
 * failed in a way that is easy to walk back into:
 *
 * <ul>
 *   <li>A <b>price-level</b> score — "you are near the top of the range, so
 *       this is a good exit" — reduces to a one-sided drawdown penalty. Its
 *       largest term measures which way the price moved today, which is a
 *       forecast wearing a measurement's clothes. See {@link RangePosition},
 *       which refuses the same thing for the same reason.</li>
 *   <li>A <b>liquidation-cost</b> score that mixed stack size with the GE buy
 *       limit scored one twisted bow at 88 of 100 — "you are a rounding error
 *       against the flow" — because a single unit is always under any limit.
 *       It also imported Jagex's buy-limit table, which is an anti-RWT policy
 *       dial, as a quarter of a liquidity measurement.</li>
 *   <li>An <b>opportunity-cost</b> score — "this gold could be earning more in
 *       the buy ideas on this list" — comes out inversely proportional to
 *       stack size, so it whispers at small liquid junk and goes quiet on the
 *       huge dead position. It fought the panel's own sort order on screen.</li>
 * </ul>
 *
 * What was left after all three was the measurement underneath the best of
 * them, with no number wrapped around it. A 0-100 here would have to be
 * calibrated against something, and there is nothing honest to calibrate it
 * against: "is this a good exit" depends on what you want the gold for, which
 * the plugin does not know. "Your stack is 2.3 days of demand" depends on
 * nothing but two counts.
 *
 * <h2>What it deliberately does not use</h2>
 * <b>Cost basis.</b> No parameter here is what you paid, so a stack bought
 * through the plugin and a stack that came out of a drop go down identical
 * code. Every P&amp;L-shaped signal goes blank on exactly the holdings that most
 * need an exit read — the ones predating the plugin — and an exit measurement
 * that is contaminated by cost basis is a sunk-cost machine anyway: it would
 * argue for holding a loser and dumping a winner.
 *
 * <b>The live spread.</b> {@code Advisor.freshForSell} gates a sell candidate
 * on the BID leg only, over a two-hour window, so {@code q.low} on a sell may
 * be hours stale or zero. Anything computed from {@code q.high - q.low} would
 * score a fresh ask against an arbitrarily old bid. Both volumes here come out
 * of the same /24h response instead, so they are always consistent with each
 * other.
 *
 * <b>One side of the book.</b> The obvious denominator is the insta-buy volume
 * alone, since that is the side that takes your offer. It is also the side
 * that collapses in a bleed — holders stop waiting and hit the bid while
 * buyers stop lifting the ask — so a one-sided denominator quietly turns this
 * into a directional signal, spiking exactly when the price is falling. The
 * average of the two sides is used instead, which is the same figure
 * {@link FinderEngine} sums for its own volume floor.
 *
 * Pure and dependency-free, like {@link RangePosition} and
 * {@link PriceExtremes}, so the arithmetic is unit-testable without a client
 * or a network.
 */
public final class Clearance
{
	/**
	 * Below this many prints across both sides in 24 hours, this says nothing
	 * at all.
	 *
	 * Without it the measurement is at its most confident exactly where it is
	 * least supported: a blue partyhat with three held against eight insta-buys
	 * in a day is arithmetically "0.75 days of demand", which reads as a
	 * liquid position and is nonsense off a sample of eight. Silence on a rare
	 * is correct; a number off a handful of trades is not.
	 *
	 * Deliberately the same constant and the same both-sides sum as
	 * FinderEngine.LOW_VOL_THRESHOLD, which already refuses to rate an item on
	 * this much evidence.
	 */
	public static final long MIN_PRINTS = 100_000;

	/**
	 * Under a full day of demand, this says nothing.
	 *
	 * The first cut printed a line on every sell it could measure, which
	 * meant almost all of them read "under an hour of demand" — true,
	 * unhelpful, and different on adjacent cards for no reason a player could
	 * see. Reported as "get rid of this inconsistent hours of demand text its
	 * meaningless", and that is the right call: the whole point of the line is
	 * that your stack is big enough for size to be a factor in getting out.
	 * A stack that clears inside a day is not a stack you have to think about,
	 * so there is nothing to say about it.
	 *
	 * The floor also removes the hours unit entirely, so the line has exactly
	 * one form — "N days of demand" — rather than four that a reader has to
	 * tell apart.
	 */
	public static final double MIN_DAYS = 1.0;

	/** Units you are holding. */
	public final long qty;
	/** Units a day, averaged across both sides of the book. */
	public final long flowPerDay;
	/** qty / flowPerDay. Uncapped — see {@link #label()}. */
	public final double days;

	private Clearance(long qty, long flowPerDay, double days)
	{
		this.qty = qty;
		this.flowPerDay = flowPerDay;
		this.days = days;
	}

	/**
	 * @param qty         units held — the stack this card is about to list
	 * @param buySideVol  AnalystRating.Average.highPriceVolume, 24h
	 * @param sellSideVol AnalystRating.Average.lowPriceVolume, 24h
	 * @return null when it cannot be measured or is not supported by enough
	 *         prints, in which case the card shows nothing rather than a
	 *         number it cannot stand behind
	 */
	public static Clearance of(long qty, long buySideVol, long sellSideVol)
	{
		if (qty <= 0 || buySideVol < 0 || sellSideVol < 0)
		{
			return null;
		}
		final long prints = buySideVol + sellSideVol;
		if (prints < MIN_PRINTS)
		{
			return null;
		}
		final long flow = prints / 2;
		if (flow <= 0)
		{
			return null;
		}
		final double days = qty / (double) flow;
		if (days < MIN_DAYS)
		{
			return null;
		}
		return new Clearance(qty, flow, days);
	}

	/**
	 * The line the card prints.
	 *
	 * "of demand", not "to clear". The difference is not pedantry: "2.3 days
	 * to clear" says the stack WILL be gone in 2.3 days, which is a forecast
	 * this cannot support — it assumes you are the only seller listing, and
	 * two players each holding the same 2.3 days are between them 4.6 days of
	 * flow and one of them is not getting filled. "2.3 days of demand" states
	 * the ratio and claims nothing about the future.
	 *
	 * Uncapped at the top on purpose. A stack worth 41 days of demand prints
	 * "41 days", not "10+ days": the whole value of the line on a dead
	 * position is that the number keeps going. Floored at the bottom instead,
	 * where the readings were meaningless — see MIN_DAYS.
	 */
	public String label()
	{
		/* One unit, always. See MIN_DAYS — anything smaller than a day does
		   not get a line at all, so there is no hours form to tell apart. */
		return days >= 10
			? String.format("%,.0f days of demand", days)
			: String.format("%.1f days of demand", days);
	}

	/**
	 * The working, as plain lines. The panel wraps these in its own tooltip
	 * HTML; kept as text here so a test can read them.
	 *
	 * Line two is the assumption, not a footnote, because it is the single
	 * thing most likely to make the number wrong for you.
	 */
	public String[] tooltipLines()
	{
		return new String[]{
			String.format("Your %,d against %,d a day.", qty, flowPerDay),
			"Units buyers took off this item in the last 24 hours, averaged "
				+ "across both sides of the book.",
			"Assumes you are the only seller listing.",
			"Nothing here is what you paid, or where the price goes next.",
		};
	}
}
