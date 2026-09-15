package net.runelite.client.config;

/** Stub of RuneLite's Config marker interface. */
public interface Config
{
	default String getGroupName()
	{
		final ConfigGroup group = getClass().getAnnotation(ConfigGroup.class);
		return group == null ? null : group.value();
	}
}
