package net.runelite.client.ui;

import java.awt.Font;

/** Stub of RuneLite's FontManager. Uses plain AWT fonts — no bundled TTFs. */
public class FontManager
{
	private static final Font RUNESCAPE_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 16);
	private static final Font RUNESCAPE_SMALL_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 14);
	private static final Font RUNESCAPE_BOLD_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 16);
	private static final Font DEFAULT_FONT = new Font(Font.SANS_SERIF, Font.PLAIN, 12);
	private static final Font DEFAULT_BOLD_FONT = new Font(Font.SANS_SERIF, Font.BOLD, 12);

	public static Font getRunescapeFont()
	{
		return RUNESCAPE_FONT;
	}

	public static Font getRunescapeSmallFont()
	{
		return RUNESCAPE_SMALL_FONT;
	}

	public static Font getRunescapeBoldFont()
	{
		return RUNESCAPE_BOLD_FONT;
	}

	public static Font getDefaultFont()
	{
		return DEFAULT_FONT;
	}

	public static Font getDefaultBoldFont()
	{
		return DEFAULT_BOLD_FONT;
	}
}
