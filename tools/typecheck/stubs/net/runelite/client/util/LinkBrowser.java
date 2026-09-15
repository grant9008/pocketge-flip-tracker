package net.runelite.client.util;

/** Stub of RuneLite's LinkBrowser — records instead of opening a browser. */
public class LinkBrowser
{
	public static String lastBrowsed;
	public static String lastCopied;

	public static boolean browse(final String url)
	{
		lastBrowsed = url;
		return true;
	}

	public static boolean open(final String directory)
	{
		lastBrowsed = directory;
		return true;
	}

	public static void openLocalFile(final java.io.File file)
	{
		lastBrowsed = file.getAbsolutePath();
	}
}
