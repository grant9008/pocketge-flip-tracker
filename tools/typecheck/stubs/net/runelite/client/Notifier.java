package net.runelite.client;

import java.awt.Graphics2D;
import java.awt.TrayIcon;
import java.util.ArrayList;
import java.util.List;

/**
 * Stub of RuneLite's Notifier. Upstream is a @Singleton class whose public surface is
 * notify(String), notify(String, TrayIcon.MessageType), notify(Notification, String) and
 * processFlash(Graphics2D). The Notification overload is left out here so the stub tree
 * does not have to carry net.runelite.client.config.Notification and its whole
 * constructor; leaving a method out can only make the local compile stricter than CI.
 *
 * Rather than pop anything, this keeps what it was told, so a harness can assert on the
 * alert text a plugin decided to send.
 */
public class Notifier
{
	private final List<String> messages = new ArrayList<>();

	public void notify(String message)
	{
		messages.add(message);
	}

	public void notify(String message, TrayIcon.MessageType type)
	{
		messages.add(message);
	}

	public void processFlash(final Graphics2D graphics)
	{
	}

	/** Stub-only accessor — not part of the real Notifier. */
	public List<String> getMessages()
	{
		return messages;
	}
}
