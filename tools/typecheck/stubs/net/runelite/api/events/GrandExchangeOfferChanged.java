package net.runelite.api.events;

import net.runelite.api.GrandExchangeOffer;

/**
 * Stub of RuneLite's GrandExchangeOfferChanged event.
 *
 * <p>Upstream this is a Lombok {@code @Data} class over the fields
 * {@code offer} and {@code slot}.</p>
 */
public class GrandExchangeOfferChanged
{
	private GrandExchangeOffer offer;
	private int slot;

	public GrandExchangeOffer getOffer()
	{
		throw new UnsupportedOperationException();
	}

	public int getSlot()
	{
		throw new UnsupportedOperationException();
	}

	public void setOffer(GrandExchangeOffer offer)
	{
		throw new UnsupportedOperationException();
	}

	public void setSlot(int slot)
	{
		throw new UnsupportedOperationException();
	}
}
