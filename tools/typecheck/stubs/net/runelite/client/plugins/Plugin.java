package net.runelite.client.plugins;

/**
 * Stub of RuneLite's Plugin base class.
 *
 * Upstream this is {@code public abstract class Plugin implements com.google.inject.Module},
 * carrying a {@code protected Injector injector}, {@code configure(Binder)} and
 * {@code getInjector()}. Those three members are the only ones dropped here: keeping them
 * would drag in Guice's Module/Binder/Injector, which no plugin source touches. Dropping
 * them can only make the local compile STRICTER than CI, never looser, which is the safe
 * direction for a stub.
 *
 * Everything that remains matches upstream exactly — in particular startUp()/shutDown() are
 * protected and declare {@code throws Exception}, and hashCode()/equals() are final.
 */
public abstract class Plugin
{
	@Override
	public final int hashCode()
	{
		return super.hashCode();
	}

	@Override
	public final boolean equals(Object obj)
	{
		return super.equals(obj);
	}

	protected void startUp() throws Exception
	{
	}

	protected void shutDown() throws Exception
	{
	}

	public void resetConfiguration()
	{
	}

	public String getName()
	{
		final PluginDescriptor descriptor = getClass().getAnnotation(PluginDescriptor.class);
		return descriptor == null ? null : descriptor.name();
	}
}
