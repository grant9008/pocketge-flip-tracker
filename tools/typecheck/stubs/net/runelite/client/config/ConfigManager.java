package net.runelite.client.config;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Stub of RuneLite's ConfigManager.
 *
 * <p>The string get/set pairs are backed by an in-memory map so a harness can
 * round-trip the plugin's saved state (the real manager persists the same
 * group.key pairs to a profile file). getConfig() returns null: the real one
 * hands back a dynamic proxy over the config interface, which is not something
 * a compile-only stub can reproduce, and the plugin's own provider method is
 * only called by Guice.
 */
public class ConfigManager
{
	private final Map<String, String> values = new ConcurrentHashMap<>();

	private static String wholeKey(String groupName, String profile, String key)
	{
		return profile == null
			? groupName + "." + key
			: groupName + "." + profile + "." + key;
	}

	public <T extends Config> T getConfig(Class<T> clazz)
	{
		return null;
	}

	public List<String> getConfigurationKeys(String prefix)
	{
		final List<String> keys = new ArrayList<>();
		for (String k : values.keySet())
		{
			if (k.startsWith(prefix))
			{
				keys.add(k);
			}
		}
		return keys;
	}

	public String getConfiguration(String groupName, String key)
	{
		return values.get(wholeKey(groupName, null, key));
	}

	public String getConfiguration(String groupName, String profile, String key)
	{
		return values.get(wholeKey(groupName, profile, key));
	}

	public <T> T getConfiguration(String groupName, String key, Type clazz)
	{
		return null;
	}

	public <T> T getConfiguration(String groupName, String profile, String key, Type type)
	{
		return null;
	}

	public String getRSProfileConfiguration(String groupName, String key)
	{
		return null;
	}

	public void setConfiguration(String groupName, String profile, String key, String value)
	{
		values.put(wholeKey(groupName, profile, key), value);
	}

	public void setConfiguration(String groupName, String key, String value)
	{
		values.put(wholeKey(groupName, null, key), value);
	}

	public <T> void setConfiguration(String groupName, String profile, String key, T value)
	{
		setConfiguration(groupName, profile, key, String.valueOf(value));
	}

	public <T> void setConfiguration(String groupName, String key, T value)
	{
		setConfiguration(groupName, key, String.valueOf(value));
	}

	public void unsetConfiguration(String groupName, String profile, String key)
	{
		values.remove(wholeKey(groupName, profile, key));
	}

	public void unsetConfiguration(String groupName, String key)
	{
		values.remove(wholeKey(groupName, null, key));
	}
}
