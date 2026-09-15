package net.runelite.api.widgets;

/** Component-id packing helpers. */
public class WidgetUtil
{
	public static int componentToInterface(int c)
	{
		return c >>> 16;
	}

	public static int componentToId(int c)
	{
		return c & 0xFFFF;
	}

	public static int packComponentId(int interfaceId, int childId)
	{
		return (interfaceId << 16) | childId;
	}
}
