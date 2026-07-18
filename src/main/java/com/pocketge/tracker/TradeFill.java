package com.pocketge.tracker;

/**
 * One partial or complete fill of a GE offer: "N items actually traded at
 * this moment, for this much total gp". Fills are the ground truth the
 * flip matcher consumes — offers can fill in many increments over hours.
 */
public class TradeFill
{
	public final long time;        // epoch millis
	public final int itemId;
	public final String itemName;
	public final boolean buy;      // true = bought, false = sold
	public final int quantity;     // items in THIS fill (delta, not cumulative)
	public final long spent;       // gp moved in THIS fill (delta, not cumulative)

	public TradeFill(long time, int itemId, String itemName, boolean buy, int quantity, long spent)
	{
		this.time = time;
		this.itemId = itemId;
		this.itemName = itemName;
		this.buy = buy;
		this.quantity = quantity;
		this.spent = spent;
	}

	public long unitPrice()
	{
		return quantity > 0 ? Math.round((double) spent / quantity) : 0;
	}
}
