package net.runelite.api.events;

import net.runelite.api.ChatMessageType;

/**
 * Stub of RuneLite's ChatMessage event.
 *
 * <p>Upstream this is a Lombok {@code @Data @AllArgsConstructor @NoArgsConstructor}
 * class; the accessors below are the ones Lombok generates.</p>
 */
public class ChatMessage
{
	private ChatMessageType type;
	private String name;
	private String message;
	private String sender;
	private int timestamp;

	public ChatMessage()
	{
	}

	public ChatMessageType getType()
	{
		throw new UnsupportedOperationException();
	}

	public String getName()
	{
		throw new UnsupportedOperationException();
	}

	public String getMessage()
	{
		throw new UnsupportedOperationException();
	}

	public String getSender()
	{
		throw new UnsupportedOperationException();
	}

	public int getTimestamp()
	{
		throw new UnsupportedOperationException();
	}

	public void setType(ChatMessageType type)
	{
		throw new UnsupportedOperationException();
	}

	public void setName(String name)
	{
		throw new UnsupportedOperationException();
	}

	public void setMessage(String message)
	{
		throw new UnsupportedOperationException();
	}

	public void setSender(String sender)
	{
		throw new UnsupportedOperationException();
	}

	public void setTimestamp(int timestamp)
	{
		throw new UnsupportedOperationException();
	}
}
