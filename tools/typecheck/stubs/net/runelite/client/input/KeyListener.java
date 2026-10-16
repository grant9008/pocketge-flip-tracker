package net.runelite.client.input;

/**
 * Compile-only stub of net.runelite.client.input.KeyListener: the AWT
 * interface plus one default method, which RuneLite consults to decide
 * whether the listener runs while the login screen is up.
 */
public interface KeyListener extends java.awt.event.KeyListener
{
	default boolean isEnabledOnLoginScreen()
	{
		return false;
	}
}
