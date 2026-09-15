package net.runelite.client;

import java.io.File;

/**
 * Stub of RuneLite's entry-point class, reduced to the public directory constants —
 * the only part of it a plugin ever reads. Values and types are the upstream ones, so
 * RUNELITE_DIR really does resolve to ~/.runelite here too.
 *
 * The launcher plumbing upstream (main, setInjector, USER_AGENT, the Injector field)
 * is omitted rather than faked, since nothing in the plugin references it.
 */
public class RuneLite
{
	public static final File RUNELITE_DIR = new File(System.getProperty("user.home"), ".runelite");
	public static final File CACHE_DIR = new File(RUNELITE_DIR, "cache");
	public static final File PLUGINS_DIR = new File(RUNELITE_DIR, "plugins");
	public static final File SCREENSHOT_DIR = new File(RUNELITE_DIR, "screenshots");
	public static final File LOGS_DIR = new File(RUNELITE_DIR, "logs");
	public static final File DEFAULT_SESSION_FILE = new File(RUNELITE_DIR, "session");
	public static final File NOTIFICATIONS_DIR = new File(RuneLite.RUNELITE_DIR, "notifications");
	public static final File FONTS_DIR = new File(RuneLite.RUNELITE_DIR, "fonts");
}
