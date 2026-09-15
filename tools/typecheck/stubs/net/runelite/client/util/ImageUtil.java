package net.runelite.client.util;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;

/**
 * Stub of RuneLite's ImageUtil. Resource loading returns a blank image rather
 * than reading the plugin's classpath, so nothing depends on packaged sprites.
 */
public class ImageUtil
{
	public static BufferedImage loadImageResource(Class<?> c, String path)
	{
		final BufferedImage img = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = img.createGraphics();
		g.setColor(new Color(0, 0, 0, 0));
		g.fillRect(0, 0, 16, 16);
		g.dispose();
		return img;
	}

	public static BufferedImage resizeImage(BufferedImage image, int newWidth, int newHeight)
	{
		return resizeImage(image, newWidth, newHeight, false);
	}

	public static BufferedImage resizeImage(BufferedImage image, int newWidth, int newHeight, boolean preserveAspect)
	{
		final BufferedImage out = new BufferedImage(Math.max(1, newWidth), Math.max(1, newHeight),
			BufferedImage.TYPE_INT_ARGB);
		final Graphics2D g = out.createGraphics();
		g.drawImage(image, 0, 0, out.getWidth(), out.getHeight(), null);
		g.dispose();
		return out;
	}

	public static BufferedImage resizeCanvas(BufferedImage image, int newWidth, int newHeight)
	{
		return resizeImage(image, newWidth, newHeight);
	}

	public static BufferedImage luminanceOffset(BufferedImage image, int offset)
	{
		return image;
	}

	public static BufferedImage alphaOffset(BufferedImage image, float percentage)
	{
		return image;
	}

	public static BufferedImage grayscaleImage(BufferedImage image)
	{
		return image;
	}

	public static BufferedImage fillImage(BufferedImage image, Color color)
	{
		return image;
	}

	public static BufferedImage recolorImage(BufferedImage image, Color color)
	{
		return image;
	}
}
