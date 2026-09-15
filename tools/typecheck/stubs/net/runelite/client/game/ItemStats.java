package net.runelite.client.game;

/** Stub of RuneLite's ItemStats. */
public class ItemStats
{
	private final boolean canBeNoted;
	private final boolean equipable;
	private final int weight;
	private final int geLimit;

	public ItemStats(boolean canBeNoted, boolean equipable, int weight, int geLimit)
	{
		this.canBeNoted = canBeNoted;
		this.equipable = equipable;
		this.weight = weight;
		this.geLimit = geLimit;
	}

	public boolean isCanBeNoted()
	{
		return canBeNoted;
	}

	public boolean isEquipable()
	{
		return equipable;
	}

	public int getWeight()
	{
		return weight;
	}

	public int getGeLimit()
	{
		return geLimit;
	}
}
