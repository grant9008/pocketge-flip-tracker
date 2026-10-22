package net.runelite.client.config;

import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;

/**
 * Compile-only stub of RuneLite's Keybind: the same public surface and the
 * same matching rules (runelite master 330e650), without guava or lombok.
 */
public class Keybind
{
	private static final int KEYBOARD_MODIFIER_MASK = InputEvent.CTRL_DOWN_MASK | InputEvent.ALT_DOWN_MASK
		| InputEvent.SHIFT_DOWN_MASK | InputEvent.META_DOWN_MASK;

	public static final Keybind NOT_SET = new Keybind(KeyEvent.VK_UNDEFINED, 0);

	private final int keyCode;
	private final int modifiers;

	public Keybind(int keyCode, int modifiers)
	{
		modifiers &= KEYBOARD_MODIFIER_MASK;
		final Integer mf = getModifierForKeyCode(keyCode);
		if (mf != null)
		{
			keyCode = KeyEvent.VK_UNDEFINED;
		}
		this.keyCode = keyCode;
		this.modifiers = modifiers;
	}

	public Keybind(KeyEvent e)
	{
		this(e.getExtendedKeyCode(), e.getModifiersEx());
	}

	public int getKeyCode()
	{
		return keyCode;
	}

	public int getModifiers()
	{
		return modifiers;
	}

	public boolean matches(KeyEvent e)
	{
		if (NOT_SET.equals(this))
		{
			return false;
		}
		int keyCode = e.getExtendedKeyCode();
		int modifiers = e.getModifiersEx() & KEYBOARD_MODIFIER_MASK;
		final Integer mf = getModifierForKeyCode(keyCode);
		if (mf != null)
		{
			modifiers |= mf;
			keyCode = KeyEvent.VK_UNDEFINED;
		}
		if (e.getID() == KeyEvent.KEY_RELEASED)
		{
			if (keyCode != KeyEvent.VK_UNDEFINED)
			{
				return this.keyCode == keyCode;
			}
			else if (mf != null)
			{
				return this.keyCode == keyCode && (this.modifiers & modifiers) == this.modifiers && ((mf & this.modifiers) == mf);
			}
		}
		return this.keyCode == keyCode && this.modifiers == modifiers;
	}

	public static Integer getModifierForKeyCode(int keyCode)
	{
		switch (keyCode)
		{
			case KeyEvent.VK_CONTROL:
				return InputEvent.CTRL_DOWN_MASK;
			case KeyEvent.VK_ALT:
				return InputEvent.ALT_DOWN_MASK;
			case KeyEvent.VK_SHIFT:
				return InputEvent.SHIFT_DOWN_MASK;
			case KeyEvent.VK_META:
				return InputEvent.META_DOWN_MASK;
			default:
				return null;
		}
	}

	@Override
	public boolean equals(Object o)
	{
		return o instanceof Keybind && ((Keybind) o).keyCode == keyCode && ((Keybind) o).modifiers == modifiers;
	}

	@Override
	public int hashCode()
	{
		return keyCode * 31 + modifiers;
	}

	@Override
	public String toString()
	{
		if (keyCode == KeyEvent.VK_UNDEFINED && modifiers == 0)
		{
			return "Not set";
		}
		final String key = keyCode == KeyEvent.VK_UNDEFINED ? "" : KeyEvent.getKeyText(keyCode);
		final String mod = modifiers != 0 ? InputEvent.getModifiersExText(modifiers) : "";
		if (mod.isEmpty() && key.isEmpty())
		{
			return "Not set";
		}
		if (!mod.isEmpty() && !key.isEmpty())
		{
			return mod + "+" + key;
		}
		return mod.isEmpty() ? key : mod;
	}
}
