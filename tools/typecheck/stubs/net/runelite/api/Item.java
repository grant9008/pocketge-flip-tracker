package net.runelite.api;

/** Stub of RuneLite's Item (upstream is a Lombok {@code @Value} class). */
public final class Item
{
	private final int id;
	private final int quantity;

	public Item(int id, int quantity)
	{
		this.id = id;
		this.quantity = quantity;
	}

	public int getId()
	{
		return id;
	}

	public int getQuantity()
	{
		return quantity;
	}
}
