package net.runelite.api.events;

/**
 * Stub of RuneLite's ScriptPostFired event.
 *
 * <p>Upstream this is a Lombok {@code @Value} class (hence final, with a final
 * {@code scriptId} field, an all-args constructor and a getter only).</p>
 */
public final class ScriptPostFired
{
	private final int scriptId;

	public ScriptPostFired(int scriptId)
	{
		this.scriptId = scriptId;
	}

	public int getScriptId()
	{
		throw new UnsupportedOperationException();
	}
}
