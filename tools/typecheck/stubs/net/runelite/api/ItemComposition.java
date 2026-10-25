package net.runelite.api;

/** Stub of RuneLite's ItemComposition. */
public interface ItemComposition
{
	int getId();

	String getName();

	boolean isStackable();

	boolean isTradeable();

	int getNote();

	int getLinkedNoteId();

	int getPrice();

	/** runelite-api/.../ItemComposition.java — `int getHaPrice();`, the
	 *  high alchemy value. */
	int getHaPrice();

	/** runelite-api/.../ItemComposition.java:127 — `boolean isMembers();` */
	boolean isMembers();
}
