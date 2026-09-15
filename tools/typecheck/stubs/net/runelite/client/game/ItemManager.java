package net.runelite.client.game;

import java.util.Collections;
import java.util.List;
import net.runelite.client.util.AsyncBufferedImage;

/**
 * Stub of RuneLite's ItemManager. Every lookup returns null / empty — the
 * panels already null-guard, so a null ItemManager and a stub one both work.
 */
public class ItemManager
{
	/* A real (blank) image, not null. The live client always returns one for
	   a tradeable id, and panels that call addTo() on the result are entitled
	   to assume that — returning null here made the stub, not the panel, the
	   thing under test. */
	/* Real item sprites, when a directory of them is pointed at with
	   -Dpocketge.sprites=<dir> holding <itemId>.png files. Off unless that
	   property is set, so every existing harness class keeps the blank icon
	   it was written against and only the screenshot renderer pays for this. */
	private static final String SPRITE_DIR = System.getProperty("pocketge.sprites");
	private static final java.util.Map<Integer, AsyncBufferedImage> SPRITES = new java.util.HashMap<>();

	public AsyncBufferedImage getImage(int itemId)
	{
		if (SPRITE_DIR == null)
		{
			return new AsyncBufferedImage();
		}
		synchronized (SPRITES)
		{
			return SPRITES.computeIfAbsent(itemId, ItemManager::loadSprite);
		}
	}

	/** Load <id>.png, trim its transparent border, and fit it into the 36x32
	 *  box the panels lay out for an item icon. The source renders are around
	 *  1200px square and mostly padding, so trimming first is what stops every
	 *  item arriving as a stamp in the middle of an empty cell. */
	private static AsyncBufferedImage loadSprite(int itemId)
	{
		final AsyncBufferedImage out = new AsyncBufferedImage();
		try
		{
			final java.io.File f = new java.io.File(SPRITE_DIR, itemId + ".png");
			if (!f.isFile())
			{
				return out;
			}
			final java.awt.image.BufferedImage src = javax.imageio.ImageIO.read(f);
			if (src == null)
			{
				return out;
			}
			int minX = src.getWidth(), minY = src.getHeight(), maxX = -1, maxY = -1;
			for (int y = 0; y < src.getHeight(); y++)
			{
				for (int x = 0; x < src.getWidth(); x++)
				{
					if (((src.getRGB(x, y) >>> 24) & 0xFF) > 8)
					{
						if (x < minX) { minX = x; }
						if (y < minY) { minY = y; }
						if (x > maxX) { maxX = x; }
						if (y > maxY) { maxY = y; }
					}
				}
			}
			final java.awt.image.BufferedImage trimmed = maxX < minX || maxY < minY
				? src : src.getSubimage(minX, minY, maxX - minX + 1, maxY - minY + 1);
			final double scale = Math.min(36.0 / trimmed.getWidth(), 32.0 / trimmed.getHeight());
			final int w = Math.max(1, (int) Math.round(trimmed.getWidth() * scale));
			final int h = Math.max(1, (int) Math.round(trimmed.getHeight() * scale));
			final java.awt.Graphics2D g = out.createGraphics();
			g.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION,
				java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
			g.drawImage(trimmed, (36 - w) / 2, (32 - h) / 2, w, h, null);
			g.dispose();
		}
		catch (Exception ignore)
		{
			// a missing or unreadable sprite is a blank icon, never a crash
		}
		return out;
	}

	public AsyncBufferedImage getImage(int itemId, int quantity, boolean stackable)
	{
		return getImage(itemId);
	}

	public int canonicalize(int itemId)
	{
		return itemId;
	}

	public ItemStats getItemStats(int itemId)
	{
		return null;
	}

	public ItemStats getItemStats(int itemId, boolean allowNote)
	{
		return null;
	}

	public net.runelite.api.ItemComposition getItemComposition(int itemId)
	{
		return null;
	}

	/**
	 * runelite-client/.../game/ItemManager.java:436 —
	 *   public List&lt;ItemPrice&gt; search(String itemName)
	 * where the ItemPrice imported at :65 is net.runelite.http.api.item.ItemPrice,
	 * NOT a net.runelite.client.game one. This stub used to say the latter, which
	 * silently made the local build disagree with the real one about the element
	 * type of a list the plugin iterates.
	 */
	public List<net.runelite.http.api.item.ItemPrice> search(String itemName)
	{
		return Collections.emptyList();
	}
}
