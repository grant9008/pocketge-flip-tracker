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
	public final long openedAt;
	public final long closedAt;    // epoch millis of the closing sell fill
	public final int itemId;
	public final String itemName;
	public final int quantity;
	public final long buySpent;    // total gp paid for the matched buys
	public final long sellGross;   // total gp received before tax
	public final long tax;         // GE tax on the sale
	public final long profit;      // sellGross - tax - buySpent

	public Flip(long openedAt, long closedAt, int itemId, String itemName, int quantity,
		long buySpent, long sellGross, long tax)
	{
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

	/** A flip whose buy time is not known — every flip booked before the
	 *  tracker started recording one, and every flip read back out of a
	 *  ledger line written then. */
	public Flip(long closedAt, int itemId, String itemName, int quantity, long buySpent, long sellGross, long tax)
	{
		this(0L, closedAt, itemId, itemName, quantity, buySpent, sellGross, tax);
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
