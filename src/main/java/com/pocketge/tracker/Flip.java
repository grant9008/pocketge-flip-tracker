package com.pocketge.tracker;

/**
 * A completed (or partially completed) flip: sell fills matched FIFO
 * against earlier buy fills of the same item. Profit is AFTER GE tax.
 */
public class Flip
{
	/**
	 * When the capital in this flip was committed — the fill time of the
	 * OLDEST buy lot it closed — or 0 when that is not known.
	 *
	 * Oldest rather than an average, because FIFO already decided which lots
	 * this flip consumed and {@link #buySpent} is computed from those same
	 * lots. Reading the cost from one set of lots and the clock from another
	 * would let the two disagree; this way "held 40 minutes at 20.1M" refers
	 * throughout to the same units.
	 *
	 * 0 means genuinely unknown, never "instant": a lot bought before the
	 * plugin recorded buy times, or a stack it never watched you buy at all.
	 * Callers must show that as nothing rather than as a zero duration — see
	 * {@link #holdMillis()}, which refuses to return one.
	 */
	/**
	 * Which Grand Exchange offer this came out of, or 0 when that is not
	 * recorded — every flip booked before offers were tokenised, and every
	 * ledger line written then.
	 *
	 * The Exchange fills one sell offer in as many chunks as it finds buyers
	 * for, so one trade of 8,218 adamantite bars arrives as five separate
	 * fills and books five flips. They are five real, correctly-priced flips;
	 * the tax and the cost basis on each are exact. But they are ONE thing
	 * you did, and a history that lists them as five — "2 x Adamantite bar,
	 * +22 gp" among them — reports activity instead of trades.
	 *
	 * This is the seam. Rows sharing a token are the same offer, so the panel
	 * can show one line and the stats can count one flip, without the money
	 * path having to defer anything: matching still happens per fill, exactly
	 * when it did before, which is what keeps a crash mid-offer from losing or
	 * duplicating gold.
	 */
	public final long offerId;
	public final long openedAt;
	public final long closedAt;    // epoch millis of the closing sell fill
	public final int itemId;
	public final String itemName;
	public final int quantity;
	public final long buySpent;    // total gp paid for the matched buys
	public final long sellGross;   // total gp received before tax
	public final long tax;         // GE tax on the sale
	public final long profit;      // sellGross - tax - buySpent

	public Flip(long offerId, long openedAt, long closedAt, int itemId, String itemName, int quantity,
		long buySpent, long sellGross, long tax)
	{
		this.offerId = offerId;
		this.openedAt = openedAt;
		this.closedAt = closedAt;
		this.itemId = itemId;
		this.itemName = itemName;
		this.quantity = quantity;
		this.buySpent = buySpent;
		this.sellGross = sellGross;
		this.tax = tax;
		this.profit = sellGross - tax - buySpent;
	}

	/** A flip from no identified offer — nothing groups with it. */
	public Flip(long openedAt, long closedAt, int itemId, String itemName, int quantity,
		long buySpent, long sellGross, long tax)
	{
		this(0L, openedAt, closedAt, itemId, itemName, quantity, buySpent, sellGross, tax);
	}

	/** A flip whose buy time is not known — every flip booked before the
	 *  tracker started recording one, and every flip read back out of a
	 *  ledger line written then. */
	public Flip(long closedAt, int itemId, String itemName, int quantity, long buySpent, long sellGross, long tax)
	{
		this(0L, 0L, closedAt, itemId, itemName, quantity, buySpent, sellGross, tax);
	}

	/**
	 * The key rows group by: the offer when there is one, and otherwise the
	 * row's own identity so an untokenised flip stands alone rather than
	 * collapsing into every other untokenised flip under a shared 0.
	 */
	public Object groupKey()
	{
		return offerId != 0 ? (Object) offerId : (Object) this;
	}

	/**
	 * The fills of one offer, as the single trade they were.
	 *
	 * Money adds up: quantity, cost, gross and tax are sums, so the merged
	 * row's profit is exactly the sum of its parts' — no rounding is
	 * introduced, because profit is derived from the summed components rather
	 * than from summing already-derived profits.
	 *
	 * Time does not add up. closedAt is the last fill, which is when the trade
	 * finished. openedAt is the EARLIEST buy behind any part — when the gold
	 * actually went out — unless any part's is unknown, in which case the
	 * whole thing is unknown. Reporting the earliest of the parts we happen to
	 * know would quietly understate a hold that began before the tracker was
	 * watching, and a hold time is the one figure here that must never flatter.
	 */
	public static Flip merge(java.util.List<Flip> parts)
	{
		if (parts == null || parts.isEmpty())
		{
			throw new IllegalArgumentException("nothing to merge");
		}
		final Flip first = parts.get(0);
		if (parts.size() == 1)
		{
			return first;
		}
		int quantity = 0;
		long buySpent = 0, sellGross = 0, tax = 0, closedAt = 0;
		long openedAt = Long.MAX_VALUE;
		boolean openUnknown = false;
		for (Flip f : parts)
		{
			quantity += f.quantity;
			buySpent += f.buySpent;
			sellGross += f.sellGross;
			tax += f.tax;
			closedAt = Math.max(closedAt, f.closedAt);
			if (f.openedAt <= 0)
			{
				openUnknown = true;
			}
			else
			{
				openedAt = Math.min(openedAt, f.openedAt);
			}
		}
		return new Flip(first.offerId, openUnknown ? 0L : openedAt, closedAt,
			first.itemId, first.itemName, quantity, buySpent, sellGross, tax);
	}

	/** Flips as trades: consecutive fills of one offer folded into one row,
	 *  input order preserved. */
	public static java.util.List<Flip> byTrade(java.util.List<Flip> flips)
	{
		final java.util.Map<Object, java.util.List<Flip>> groups = new java.util.LinkedHashMap<>();
		for (Flip f : flips)
		{
			groups.computeIfAbsent(f.groupKey(), k -> new java.util.ArrayList<>()).add(f);
		}
		final java.util.List<Flip> out = new java.util.ArrayList<>(groups.size());
		for (java.util.List<Flip> g : groups.values())
		{
			out.add(merge(g));
		}
		return byTerms(out);
	}

	/**
	 * How long two fills of one offer may be apart and still be that offer.
	 *
	 * The website's flip-group.js uses the same fifteen minutes for the same
	 * job, and the two surfaces read the same ledger — so they have to agree
	 * or the same afternoon is six trades on one screen and nineteen on the
	 * other, which is exactly what was reported.
	 */
	static final long GROUP_WINDOW_MS = 15 * 60 * 1000L;

	/**
	 * The fallback grouping, for rows that carry no offer token.
	 *
	 * {@link #offerId} was added on 15 Sep 2026. Every flip booked before
	 * that is in the ledger with a zero, and {@link #groupKey()} deliberately
	 * keeps those apart rather than collapsing all of them together — which
	 * is right, and also means a ledger older than the feature does not group
	 * at all. Reported from a real one: nineteen rows for six trades, with the
	 * website beside it saying six, because the website never had a token to
	 * rely on and matched on the trade's own terms instead.
	 *
	 * So the same terms are used here, for untokenised rows only: same item,
	 * identical unit price on both sides, and closed within
	 * {@link #GROUP_WINDOW_MS} of the group so far. The price test is what
	 * keeps genuinely separate trades apart — two flips of the same item at
	 * the same pair of prices, hours apart, were two decisions and stay two
	 * rows.
	 *
	 * A row that HAS a token is never touched here. The token is exact; this
	 * is an inference, and an inference must not overrule a fact.
	 */
	private static java.util.List<Flip> byTerms(java.util.List<Flip> rows)
	{
		final java.util.List<Flip> out = new java.util.ArrayList<>(rows.size());
		/* Index into `out` of the open group for a set of terms. */
		final java.util.Map<String, Integer> open = new java.util.HashMap<>();
		for (Flip f : rows)
		{
			if (f.offerId != 0 || f.quantity <= 0)
			{
				out.add(f);
				continue;
			}
			final String key = f.itemId
				+ "|" + Math.round(f.buySpent / (double) f.quantity)
				+ "|" + Math.round(f.sellGross / (double) f.quantity);
			final Integer at = open.get(key);
			if (at != null)
			{
				final Flip g = out.get(at);
				if (f.closedAt - g.closedAt <= GROUP_WINDOW_MS)
				{
					out.set(at, merge(java.util.List.of(g, f)));
					continue;
				}
			}
			open.put(key, out.size());
			out.add(f);
		}
		return out;
	}

	/**
	 * How long the gold was tied up, in millis, or -1 when that cannot be
	 * said.
	 *
	 * -1 rather than 0 on purpose. Slot time is the scarce resource — three
	 * slots on a free world — so "+296K" means something quite different at
	 * eleven minutes than at three days, and a flip with no recorded buy time
	 * reporting "0" would claim the most flattering of those. It has to read
	 * as a blank.
	 */
	public long holdMillis()
	{
		if (openedAt <= 0 || closedAt <= 0 || closedAt < openedAt)
		{
			return -1;
		}
		return closedAt - openedAt;
	}

	/** Profit per hour of slot time, or 0 when the hold time is unknown or
	 *  too short to divide by. The number that says whether an item is worth
	 *  occupying a slot with, as opposed to merely profitable. */
	public long profitPerHour()
	{
		final long held = holdMillis();
		if (held < 60_000L)
		{
			return 0;
		}
		return Math.round(profit * 3_600_000.0 / held);
	}

	public long avgBuy()
	{
		return quantity > 0 ? Math.round((double) buySpent / quantity) : 0;
	}

	public long avgSell()
	{
		return quantity > 0 ? Math.round((double) sellGross / quantity) : 0;
	}
}
