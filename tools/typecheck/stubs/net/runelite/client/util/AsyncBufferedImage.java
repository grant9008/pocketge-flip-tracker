package net.runelite.client.util;

import java.awt.image.BufferedImage;
import javax.swing.JLabel;

/**
 * Stub of RuneLite's AsyncBufferedImage — a BufferedImage that can be handed
 * to a JLabel and re-pushed when the real sprite loads. Headlessly there is
 * never a real sprite, so addTo() just sets the (blank) icon.
 */
public class AsyncBufferedImage extends BufferedImage
{
	public AsyncBufferedImage(int width, int height, int imageType)
	{
		super(Math.max(1, width), Math.max(1, height), imageType);
	}

	public AsyncBufferedImage()
	{
		this(36, 32, BufferedImage.TYPE_INT_ARGB);
	}

	public void onLoaded(Runnable r)
	{
		// no-op: nothing loads asynchronously in the harness
	}

	public void addTo(JLabel label)
	{
		label.setIcon(new javax.swing.ImageIcon(this));
	}

	public void changed()
	{
	}
}
