package net.runelite.api.events;

import net.runelite.api.GrandExchangeOffer;

/**
 * Stub of RuneLite's GrandExchangeOfferChanged event.
 *
 * <p>Upstream this is a Lombok {@code @Data} class over the fields
 * {@code offer} and {@code slot}.</p>
 */
/* Stores its values, like the real Lombok @Data class, so tests can fire it. */
public class GrandExchangeOfferChanged
{
	private GrandExchangeOffer offer;
	private int slot;

	public GrandExchangeOffer getOffer()
	{
		return offer;
	}

	public int getSlot()
	{
		return slot;
	}

	public void setOffer(GrandExchangeOffer offer)
	{
		this.offer = offer;
	}

	public void setSlot(int slot)
	{
		this.slot = slot;
	}
}
