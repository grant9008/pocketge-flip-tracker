package net.runelite.client.chat;

import java.util.Objects;
import net.runelite.api.ChatMessageType;

/**
 * Stub of RuneLite's QueuedMessage. Upstream is a Lombok {@code @Data @Builder}
 * class; the builder, getters and setters Lombok would generate are written
 * out by hand here so the shape at the call site is identical:
 * {@code QueuedMessage.builder().type(..).runeLiteFormattedMessage(..).build()}.
 *
 * <p>{@code type} is {@code @NonNull} upstream — build() rejects a null the
 * same way the generated constructor would.
 */
public class QueuedMessage
{
	private final ChatMessageType type;
	private final String value;
	private String name;
	private String sender;
	private String runeLiteFormattedMessage;
	private int timestamp;

	QueuedMessage(ChatMessageType type, String value, String name, String sender,
		String runeLiteFormattedMessage, int timestamp)
	{
		if (type == null)
		{
			throw new NullPointerException("type is marked non-null but is null");
		}
		this.type = type;
		this.value = value;
		this.name = name;
		this.sender = sender;
		this.runeLiteFormattedMessage = runeLiteFormattedMessage;
		this.timestamp = timestamp;
	}

	public static QueuedMessageBuilder builder()
	{
		return new QueuedMessageBuilder();
	}

	public ChatMessageType getType()
	{
		return type;
	}

	public String getValue()
	{
		return value;
	}

	public String getName()
	{
		return name;
	}

	public String getSender()
	{
		return sender;
	}

	public String getRuneLiteFormattedMessage()
	{
		return runeLiteFormattedMessage;
	}

	public int getTimestamp()
	{
		return timestamp;
	}

	public void setName(String name)
	{
		this.name = name;
	}

	public void setSender(String sender)
	{
		this.sender = sender;
	}

	public void setRuneLiteFormattedMessage(String runeLiteFormattedMessage)
	{
		this.runeLiteFormattedMessage = runeLiteFormattedMessage;
	}

	public void setTimestamp(int timestamp)
	{
		this.timestamp = timestamp;
	}

	@Override
	public boolean equals(Object o)
	{
		if (this == o)
		{
			return true;
		}
		if (!(o instanceof QueuedMessage))
		{
			return false;
		}
		final QueuedMessage other = (QueuedMessage) o;
		return timestamp == other.timestamp
			&& Objects.equals(type, other.type)
			&& Objects.equals(value, other.value)
			&& Objects.equals(name, other.name)
			&& Objects.equals(sender, other.sender)
			&& Objects.equals(runeLiteFormattedMessage, other.runeLiteFormattedMessage);
	}

	@Override
	public int hashCode()
	{
		return Objects.hash(type, value, name, sender, runeLiteFormattedMessage, timestamp);
	}

	@Override
	public String toString()
	{
		return "QueuedMessage(type=" + type + ", value=" + value + ", name=" + name
			+ ", sender=" + sender + ", runeLiteFormattedMessage=" + runeLiteFormattedMessage
			+ ", timestamp=" + timestamp + ")";
	}

	public static class QueuedMessageBuilder
	{
		private ChatMessageType type;
		private String value;
		private String name;
		private String sender;
		private String runeLiteFormattedMessage;
		private int timestamp;

		QueuedMessageBuilder()
		{
		}

		public QueuedMessageBuilder type(ChatMessageType type)
		{
			this.type = type;
			return this;
		}

		public QueuedMessageBuilder value(String value)
		{
			this.value = value;
			return this;
		}

		public QueuedMessageBuilder name(String name)
		{
			this.name = name;
			return this;
		}

		public QueuedMessageBuilder sender(String sender)
		{
			this.sender = sender;
			return this;
		}

		public QueuedMessageBuilder runeLiteFormattedMessage(String runeLiteFormattedMessage)
		{
			this.runeLiteFormattedMessage = runeLiteFormattedMessage;
			return this;
		}

		public QueuedMessageBuilder timestamp(int timestamp)
		{
			this.timestamp = timestamp;
			return this;
		}

		public QueuedMessage build()
		{
			return new QueuedMessage(type, value, name, sender, runeLiteFormattedMessage, timestamp);
		}

		@Override
		public String toString()
		{
			return "QueuedMessage.QueuedMessageBuilder(type=" + type + ", value=" + value
				+ ", name=" + name + ", sender=" + sender
				+ ", runeLiteFormattedMessage=" + runeLiteFormattedMessage
				+ ", timestamp=" + timestamp + ")";
		}
	}
}
