package net.runelite.client.chat;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * Stub of RuneLite's ChatMessageManager. Only the two members the plugin can
 * reach are kept: queue() parks a message and process() drains it. The real
 * class writes the drained message into the client's chat buffer, which there
 * is nothing to write to here.
 *
 * <p>Upstream's constructor is {@code @Inject private}; the stub leaves the
 * default public one so a harness can build one directly.
 */
public class ChatMessageManager
{
	private final Queue<QueuedMessage> queuedMessages = new ConcurrentLinkedQueue<>();

	public void queue(QueuedMessage message)
	{
		queuedMessages.add(message);
	}

	public void process()
	{
		queuedMessages.clear();
	}
}
