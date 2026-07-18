package com.pocketge.tracker;

/**
 * A completed (or partially completed) flip: sell fills matched FIFO
 * against earlier buy fills of the same item. Profit is AFTER GE tax.
 */
public class Flip
{
	public final long closedAt;    // epoch millis of the closing sell fill
	public final int itemId;
	public final String itemName;
	public final int quantity;
	public final long buySpent;    // total gp paid for the matched buys
	public final long sellGross;   // total gp received before tax
	public final long tax;         // GE tax on the sale
	public final long profit;      // sellGross - tax - buySpent

	public Flip(long closedAt, int itemId, String itemName, int quantity, long buySpent, long sellGross, long tax)
	{
		this.closedAt = closedAt;
		this.itemId = itemId;
		this.itemName = itemName;
		this.quantity = quantity;
		this.buySpent = buySpent;
		this.sellGross = sellGross;
		this.tax = tax;
		this.profit = sellGross - tax - buySpent;
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
