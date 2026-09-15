package net.runelite.api;

/** Stub of RuneLite's GrandExchangeOffer. */
public interface GrandExchangeOffer
{
	int getQuantitySold();

	int getItemId();

	int getTotalQuantity();

	int getPrice();

	int getSpent();

	GrandExchangeOfferState getState();
}
