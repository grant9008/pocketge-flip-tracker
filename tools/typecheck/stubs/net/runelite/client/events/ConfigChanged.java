package net.runelite.client.events;

import java.util.Objects;

/**
 * Stub of RuneLite's ConfigChanged event. Upstream is a Lombok {@code @Data}
 * class with five mutable fields; the generated no-arg constructor, getters
 * and setters are written out here so a harness can build one and fire it at
 * the plugin's subscriber.
 */
public class ConfigChanged
{
	private String group;
	private String profile;
	private String key;
	private String oldValue;
	private String newValue;

	public String getGroup()
	{
		return group;
	}

	public String getProfile()
	{
		return profile;
	}

	public String getKey()
	{
		return key;
	}

	public String getOldValue()
	{
		return oldValue;
	}

	public String getNewValue()
	{
		return newValue;
	}

	public void setGroup(String group)
	{
		this.group = group;
	}

	public void setProfile(String profile)
	{
		this.profile = profile;
	}

	public void setKey(String key)
	{
		this.key = key;
	}

	public void setOldValue(String oldValue)
	{
		this.oldValue = oldValue;
	}

	public void setNewValue(String newValue)
	{
		this.newValue = newValue;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof ConfigChanged))
		{
			return false;
		}
		final ConfigChanged other = (ConfigChanged) o;
		return Objects.equals(group, other.group)
			&& Objects.equals(profile, other.profile)
			&& Objects.equals(key, other.key)
			&& Objects.equals(oldValue, other.oldValue)
			&& Objects.equals(newValue, other.newValue);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(group, profile, key, oldValue, newValue);
	}

	@Override
	public String toString()
	{
		return "ConfigChanged(group=" + group + ", profile=" + profile + ", key=" + key
			+ ", oldValue=" + oldValue + ", newValue=" + newValue + ")";
	}
}
