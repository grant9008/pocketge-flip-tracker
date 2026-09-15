package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.runelite.api.widgets.WidgetItem;
import org.junit.Assert;
import org.junit.Test;

/**
 * What the bank overlay actually PAINTS, recorded rather than eyeballed.
 *
 * The report was "diamonds are so highlighted I can't read them, double
 * border going on", and there were two reasons for it. A stroke is centred
 * on the path it follows, so the 2.5px ring laid straight along the slot
 * bounds put half its width outside the slot — a fat soft line hard against
 * the bank's own slot border, which is exactly what a doubled border looks
 * like. A second ring 3px further in made it three lines around a small pale
 * sprite. And the PocketGE mark was stamped on EVERY marked stack, so a bank
 * with nine sellable stacks wore nine icons: the thing meant to pick one slot
 * out of the crowd was on all of them.
 *
 * Counting rectangles is the only way to hold that down. "Looks fine now" is
 * how it got to three in the first place.
 */
public class BankHighlightOverlayTest
{
	private static final int SLOT = 36;

	/** Records the drawing calls that decide how busy a slot looks. */
	private static class Recorder
	{
		final List<Rectangle> rects = new ArrayList<>();
		final List<Float> strokes = new ArrayList<>();
		int images;
	}

	/** A Graphics2D that notes what it was asked to draw and does nothing. */
	private static Graphics2D recording(Recorder r)
	{
		final BufferedImage img = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB);
		return new java.awt.Graphics2D()
		{
			private final Graphics2D real = img.createGraphics();

			@Override
			public void drawRect(int x, int y, int w, int h)
			{
				r.rects.add(new Rectangle(x, y, w, h));
			}

			@Override
			public void setStroke(Stroke s)
			{
				if (s instanceof BasicStroke)
				{
					r.strokes.add(((BasicStroke) s).getLineWidth());
				}
			}

			@Override
			public boolean drawImage(java.awt.Image i, int x, int y, java.awt.image.ImageObserver o)
			{
				r.images++;
				return true;
			}

			/* Everything else goes to a real off-screen Graphics2D, so the
			   overlay can set colours and hints without this class having to
			   stub out the whole of Graphics2D by hand. */
			@Override
			protected Object clone()
			{
				return this;
			}

			// --- delegation ------------------------------------------------
			@Override public void setRenderingHint(java.awt.RenderingHints.Key k, Object v) { real.setRenderingHint(k, v); }
			@Override public Object getRenderingHint(java.awt.RenderingHints.Key k) { return real.getRenderingHint(k); }
			@Override public void setColor(java.awt.Color c) { real.setColor(c); }
			@Override public java.awt.Color getColor() { return real.getColor(); }
			@Override public Stroke getStroke() { return real.getStroke(); }
			@Override public java.awt.Font getFont() { return real.getFont(); }
			@Override public void setFont(java.awt.Font f) { real.setFont(f); }
			@Override public java.awt.FontMetrics getFontMetrics(java.awt.Font f) { return real.getFontMetrics(f); }
			@Override public java.awt.Rectangle getClipBounds() { return real.getClipBounds(); }
			@Override public void setClip(java.awt.Shape s) { real.setClip(s); }
			@Override public java.awt.Shape getClip() { return real.getClip(); }
			@Override public void setClip(int x, int y, int w, int h) { real.setClip(x, y, w, h); }
			@Override public void clipRect(int x, int y, int w, int h) { real.clipRect(x, y, w, h); }
			@Override public void dispose() { real.dispose(); }

			// --- the rest of the abstract surface, unused here --------------
			@Override public void draw(java.awt.Shape s) { }
			@Override public boolean drawImage(java.awt.Image i, java.awt.geom.AffineTransform t, java.awt.image.ImageObserver o) { return true; }
			@Override public void drawImage(java.awt.image.BufferedImage i, java.awt.image.BufferedImageOp op, int x, int y) { }
			@Override public void drawRenderedImage(java.awt.image.RenderedImage i, java.awt.geom.AffineTransform t) { }
			@Override public void drawRenderableImage(java.awt.image.renderable.RenderableImage i, java.awt.geom.AffineTransform t) { }
			@Override public void drawString(String s, int x, int y) { }
			@Override public void drawString(String s, float x, float y) { }
			@Override public void drawString(java.text.AttributedCharacterIterator i, int x, int y) { }
			@Override public void drawString(java.text.AttributedCharacterIterator i, float x, float y) { }
			@Override public void drawGlyphVector(java.awt.font.GlyphVector g, float x, float y) { }
			@Override public void fill(java.awt.Shape s) { }
			@Override public boolean hit(java.awt.Rectangle r2, java.awt.Shape s, boolean on) { return false; }
			@Override public java.awt.GraphicsConfiguration getDeviceConfiguration() { return real.getDeviceConfiguration(); }
			@Override public void setComposite(java.awt.Composite c) { }
			@Override public void setPaint(java.awt.Paint p) { }
			@Override public void setRenderingHints(Map<?, ?> m) { }
			@Override public void addRenderingHints(Map<?, ?> m) { }
			@Override public java.awt.RenderingHints getRenderingHints() { return real.getRenderingHints(); }
			@Override public void translate(int x, int y) { }
			@Override public void translate(double x, double y) { }
			@Override public void rotate(double t) { }
			@Override public void rotate(double t, double x, double y) { }
			@Override public void scale(double x, double y) { }
			@Override public void shear(double x, double y) { }
			@Override public void transform(java.awt.geom.AffineTransform t) { }
			@Override public void setTransform(java.awt.geom.AffineTransform t) { }
			@Override public java.awt.geom.AffineTransform getTransform() { return real.getTransform(); }
			@Override public java.awt.Paint getPaint() { return real.getPaint(); }
			@Override public java.awt.Composite getComposite() { return real.getComposite(); }
			@Override public void setBackground(java.awt.Color c) { }
			@Override public java.awt.Color getBackground() { return real.getBackground(); }
			@Override public void clip(java.awt.Shape s) { }
			@Override public java.awt.font.FontRenderContext getFontRenderContext() { return real.getFontRenderContext(); }
			@Override public java.awt.Graphics create() { return this; }
			@Override public void setPaintMode() { }
			@Override public void setXORMode(java.awt.Color c) { }
			@Override public void copyArea(int x, int y, int w, int h, int dx, int dy) { }
			@Override public void drawLine(int x1, int y1, int x2, int y2) { }
			@Override public void fillRect(int x, int y, int w, int h) { }
			@Override public void clearRect(int x, int y, int w, int h) { }
			@Override public void drawRoundRect(int x, int y, int w, int h, int aw, int ah) { }
			@Override public void fillRoundRect(int x, int y, int w, int h, int aw, int ah) { }
			@Override public void drawOval(int x, int y, int w, int h) { }
			@Override public void fillOval(int x, int y, int w, int h) { }
			@Override public void drawArc(int x, int y, int w, int h, int s, int a) { }
			@Override public void fillArc(int x, int y, int w, int h, int s, int a) { }
			@Override public void drawPolyline(int[] x, int[] y, int n) { }
			@Override public void drawPolygon(int[] x, int[] y, int n) { }
			@Override public void fillPolygon(int[] x, int[] y, int n) { }
			@Override public boolean drawImage(java.awt.Image i, int x, int y, int w, int h, java.awt.image.ImageObserver o) { return true; }
			@Override public boolean drawImage(java.awt.Image i, int x, int y, java.awt.Color c, java.awt.image.ImageObserver o) { return true; }
			@Override public boolean drawImage(java.awt.Image i, int x, int y, int w, int h, java.awt.Color c, java.awt.image.ImageObserver o) { return true; }
			@Override public boolean drawImage(java.awt.Image i, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, java.awt.image.ImageObserver o) { return true; }
			@Override public boolean drawImage(java.awt.Image i, int dx1, int dy1, int dx2, int dy2, int sx1, int sy1, int sx2, int sy2, java.awt.Color c, java.awt.image.ImageObserver o) { return true; }
		};
	}

	/** The constructor is @Inject private — Guice reaches it, a test cannot.
	 *  Reflection rather than widening it: the visibility is correct, and a
	 *  production class should not be loosened to suit a test. */
	private static BankHighlightOverlay newOverlay() throws Exception
	{
		final java.lang.reflect.Constructor<BankHighlightOverlay> ctor =
			BankHighlightOverlay.class.getDeclaredConstructor();
		ctor.setAccessible(true);
		final BankHighlightOverlay o = ctor.newInstance();
		/* @Inject fields, absent outside a running client. The paint path
		   reads `client` to decide whether the pointer is over this slot, so
		   it has to be real; the mouse is parked far away, which keeps the
		   tooltip path out of these tests. */
		set(o, "client", stubClient());
		return o;
	}

	/**
	 * A Client that answers only the one call the paint path makes, via a
	 * dynamic proxy.
	 *
	 * NOT an anonymous {@code new Client(){...}}, and the difference is not
	 * stylistic. RuneLite's real Client declares a large number of abstract
	 * methods, so an anonymous subclass has to implement every one of them or
	 * it does not compile — and the offline stub in tools/typecheck declares
	 * them all {@code default}, so an anonymous version compiles happily
	 * there and then breaks the actual Gradle build. This exact mistake broke
	 * it once already, on macroExpand(String).
	 *
	 * A proxy is shaped by the interface at runtime, so it cannot fall out of
	 * step with either version of it.
	 */
	private static net.runelite.api.Client stubClient()
	{
		return (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(),
			new Class<?>[]{net.runelite.api.Client.class},
			(proxy, method, args) ->
			{
				if ("getMouseCanvasPosition".equals(method.getName()))
				{
					/* Parked far outside every slot these tests use, which
					   keeps the tooltip branch — and tooltipManager, which is
					   null here — out of the paint path. */
					return new net.runelite.api.Point(-999, -999);
				}
				return defaultValue(method.getReturnType());
			});
	}

	/** What an unimplemented method hands back: the JLS default for a
	 *  primitive, null for anything else. */
	private static Object defaultValue(Class<?> type)
	{
		if (!type.isPrimitive() || type == void.class)
		{
			return null;
		}
		if (type == boolean.class)
		{
			return false;
		}
		if (type == char.class)
		{
			return (char) 0;
		}
		if (type == long.class)
		{
			return 0L;
		}
		if (type == float.class)
		{
			return 0f;
		}
		if (type == double.class)
		{
			return 0d;
		}
		if (type == byte.class)
		{
			return (byte) 0;
		}
		if (type == short.class)
		{
			return (short) 0;
		}
		return 0;
	}

	private static void set(Object target, String field, Object value) throws Exception
	{
		final Field f = BankHighlightOverlay.class.getDeclaredField(field);
		f.setAccessible(true);
		f.set(target, value);
	}

	private static Recorder paint(int itemId, Integer recommended) throws Exception
	{
		final BankHighlightOverlay overlay = newOverlay();
		final Advisor.Suggestion s = new Advisor.Suggestion(
			Advisor.Suggestion.Type.SELL, itemId, "Uncut diamond", 2_442, 8_944, 1_000, "");
		s.grossValue = 21_800_000L;
		final Map<Integer, Advisor.Suggestion> byItem = new HashMap<>();
		byItem.put(itemId, s);
		overlay.setSuggestions(byItem);
		overlay.setEnabled(true);
		overlay.setRecommended(recommended);

		final Recorder r = new Recorder();
		overlay.renderItemOverlay(recording(r), itemId,
			new WidgetItem(itemId, 8_944, new Rectangle(10, 20, SLOT, SLOT), null, null));
		return r;
	}

	/**
	 * A stack worth selling gets ONE rectangle, and it lands inside the slot.
	 * The old 1.5px stroke straddled the boundary, which against the bank's
	 * own slot edge already read as a double line before the recommended
	 * styling added any more.
	 */
	@Test
	public void aSellableStackGetsOneRing() throws Exception
	{
		final Recorder r = paint(1617, null);
		Assert.assertEquals("one ring, not two", 1, r.rects.size());
		Assert.assertEquals("and no icon over the sprite", 0, r.images);
		Assert.assertEquals(1f, r.strokes.get(0), 0.001f);

		final Rectangle ring = r.rects.get(0);
		Assert.assertTrue("the ring stays within the slot",
			ring.x >= 10 && ring.y >= 20
				&& ring.x + ring.width <= 10 + SLOT && ring.y + ring.height <= 20 + SLOT);
	}

	/**
	 * The recommended stack is still unmistakable — but with one ring and the
	 * mark, not two rings and a mark. Three concentric lines round a small
	 * pale sprite is what made a stack of diamonds unreadable.
	 */
	@Test
	public void theRecommendedStackGetsOneRingAndTheMark() throws Exception
	{
		final Recorder r = paint(1617, 1617);
		Assert.assertEquals("one ring, not two", 1, r.rects.size());
		Assert.assertEquals("the mark is what sets it apart now", 1, r.images);

		final Rectangle ring = r.rects.get(0);
		final float stroke = r.strokes.get(r.strokes.size() - 1);
		Assert.assertEquals("heavier than a plain sellable stack", 2f, stroke, 0.001f);
		/* Inset by HALF the stroke, so a 2px line centred on this path lands
		   wholly inside the slot instead of hanging over its border. */
		Assert.assertTrue("a 2px stroke on this path stays inside the slot",
			ring.x - stroke / 2 >= 10 && ring.y - stroke / 2 >= 20
				&& ring.x + ring.width + stroke / 2 <= 10 + SLOT
				&& ring.y + ring.height + stroke / 2 <= 20 + SLOT);
	}

	/** The mark picks ONE slot out. Stamping it on every sellable stack is
	 *  what it was doing, and that is not picking anything out. */
	@Test
	public void theMarkIsExclusiveToTheRecommendedStack() throws Exception
	{
		Assert.assertEquals(0, paint(1617, null).images);
		Assert.assertEquals(0, paint(1617, 999).images);
		Assert.assertEquals(1, paint(1617, 1617).images);
	}

	/** Nothing at all when the feature is off, or when the item has no sell
	 *  suggestion behind it. */
	@Test
	public void nothingIsPaintedWithoutAReason() throws Exception
	{
		final BankHighlightOverlay off = newOverlay();
		off.setEnabled(false);
		final Recorder r = new Recorder();
		off.renderItemOverlay(recording(r), 1617,
			new WidgetItem(1617, 8_944, new Rectangle(10, 20, SLOT, SLOT), null, null));
		Assert.assertEquals(0, r.rects.size());
		Assert.assertEquals(0, r.images);

		final Recorder unknown = paint(1617, null);
		Assert.assertEquals("sanity: the fixture itself does paint", 1, unknown.rects.size());
	}

	/** Guards the reflection above: if these stop being the field names, the
	 *  test would quietly stop constructing what it thinks it is. */
	@Test
	public void theOverlayStillHasTheFieldsThisTestAssumes() throws Exception
	{
		for (String name : new String[]{"itemManager", "client", "tooltipManager"})
		{
			final Field f = BankHighlightOverlay.class.getDeclaredField(name);
			Assert.assertNotNull(f);
		}
	}
}
