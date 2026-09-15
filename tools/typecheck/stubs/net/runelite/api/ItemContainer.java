package net.runelite.api;

/** Stub of RuneLite's ItemContainer. Upstream it extends Node, which is not stubbed. */
public interface ItemContainer
{
	int getId();

	Item[] getItems();

	Item getItem(int slot);

	boolean contains(int itemId);

	int count(int itemId);

	int size();

	int count();

	int find(int itemId);
}
