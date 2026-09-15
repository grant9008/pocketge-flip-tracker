package net.runelite.client.chat;

import java.awt.Color;

/**
 * Stub of RuneLite's ChatMessageBuilder.
 *
 * <p>The real class delegates to ColorUtil/Text; those are not part of this
 * stub set, so the same two transformations are inlined here — the built
 * string still comes out byte-identical to the live client's, which is what
 * a harness asserting on a queued chat line actually cares about.
 */
public class ChatMessageBuilder
{
	private final StringBuilder builder = new StringBuilder();

	public ChatMessageBuilder append(final ChatColorType type)
	{
		builder.append("<col").append(type.name()).append('>');
		return this;
	}

	public ChatMessageBuilder append(final Color color, final String message)
	{
		// ColorUtil.wrapWithColorTag: "<col=rrggbb>" + str + "</col>"
		builder.append("<col=")
			.append(String.format("%06x", color.getRGB() & 0xFFFFFF))
			.append('>')
			.append(message)
			.append("</col>");
		return this;
	}

	public ChatMessageBuilder append(final String message)
	{
		// Text.escapeJagex
		builder.append(message == null ? null
			: message.replace("<", "<lt>").replace(">", "<gt>"));
		return this;
	}

	public ChatMessageBuilder img(int imageId)
	{
		builder.append("<img=").append(imageId).append('>');
		return this;
	}

	public String build()
	{
		return builder.toString();
	}
}
