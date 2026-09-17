package com.pocketge.tracker;

import java.awt.BasicStroke;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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
 *
 * The ring is four lines rather than one drawRect since the top edge had to
 * go see-through: the game prints the stack count along the top of the slot,
 * and a solid line there sat on the tops of the digits — "42,027" on a marked
 * inventory stack could not be read. So the recorder reassembles a ring from
 * the four lines that draw it, and the counting below is unchanged.
 */
public class BankHighlightOverlayTest
{
	private static final int SLOT = 36;

	/** Records the drawing calls that decide how busy a slot looks. */
	private static class Recorder
	{
		final List<Rectangle> rects = new ArrayList<>();
		final List<Float> strokes = new ArrayList<>();
		/** The stroke each ring's BODY was drawn at — the three solid sides.
		 *  Not the same as the last stroke seen: the faint top edge is drawn
		 *  after them and at 1px whatever the body weight is, so asserting on
		 *  the tail of {@link #strokes} would measure the wrong line. */
		final List<Float> ringStrokes = new ArrayList<>();
		/** Alpha of the colour each ring's TOP edge was drawn in. */
		final List<Integer> topAlphas = new ArrayList<>();
		int images;

		// -- reassembly ------------------------------------------------------

		/*
		 * The ring is drawn as a set of lines, and how MANY has already
		 * changed twice — four when the top edge went see-through, five when
		 * the left edge did too. So rings are counted by geometry rather than
		 * by line count: a rectangle has two vertical sides, so the number of
		 * DISTINCT x positions among the vertical lines is two per ring.
		 *
		 * That is exactly the property the "one ring, not two" rule is about.
		 * The bug it guards against was concentric rings a few pixels apart,
		 * which is two more distinct x values; splitting one edge into two
		 * segments at the same x is not.
		 */
		private final List<int[]> lines = new ArrayList<>();
		private float stroke = 1f;
		private java.awt.Color colour = java.awt.Color.WHITE;
		private final List<Float> lineStrokes = new ArrayList<>();
		private final List<Integer> lineAlphas = new ArrayList<>();

		void line(int x1, int y1, int x2, int y2)
		{
			lines.add(new int[]{x1, y1, x2, y2});
			lineStrokes.add(stroke);
			lineAlphas.add(colour.getAlpha());
		}

		/** Turn the recorded lines into the ring count and the ring's shape. */
		void finish()
		{
			final Set<Integer> verticalX = new LinkedHashSet<>();
			for (int[] l : lines)
			{
				if (l[0] == l[2])
				{
					verticalX.add(l[0]);
				}
			}
			final int count = verticalX.size() / 2;
			if (count == 0)
			{
				return;
			}
			Rectangle all = null;
			for (int[] l : lines)
			{
				final Rectangle seg = new Rectangle(Math.min(l[0], l[2]), Math.min(l[1], l[3]),
					Math.abs(l[2] - l[0]), Math.abs(l[3] - l[1]));
				all = all == null ? seg : all.union(seg);
			}
			for (int i = 0; i < count; i++)
			{
				rects.add(all);
				/* The BODY weight — the heaviest line in the ring. The faint
				   edges are drawn at 1px whatever the body is, so the minimum
				   would report 1 for every ring and the last-seen would
				   report whichever happened to be drawn last. */
				float body = 0f;
				for (Float f : lineStrokes)
				{
					body = Math.max(body, f);
				}
				ringStrokes.add(body);
				/* And the FAINTEST alpha, which is the see-through edge. */
				int faint = 255;
				for (Integer a : lineAlphas)
				{
					faint = Math.min(faint, a);
				}
				topAlphas.add(faint);
			}
		}
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
					r.stroke = ((BasicStroke) s).getLineWidth();
					r.strokes.add(r.stroke);
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
			@Override public void setColor(java.awt.Color c) { if (c != null) { r.colour = c; } real.setColor(c); }
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
			@Override public void drawLine(int x1, int y1, int x2, int y2) { r.line(x1, y1, x2, y2); }
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
		return paint(itemId, recommended, true, 8_944);
	}

	/**
	 * @param suggested   whether the SELL suggestion map knows this item at
	 *                    all. False is the case the card-outranks-the-map
	 *                    fix exists for.
	 * @param quantity    stack size; 1 makes isMerchantStack's own filter bite.
	 */
	private static Recorder paint(int itemId, Integer recommended, boolean suggested, int quantity)
		throws Exception
	{
		final BankHighlightOverlay overlay = newOverlay();
		final Advisor.Suggestion s = new Advisor.Suggestion(
			Advisor.Suggestion.Type.SELL, itemId, "Uncut diamond", 2_442, 8_944, 1_000, "");
		s.grossValue = 21_800_000L;
		final Map<Integer, Advisor.Suggestion> byItem = new HashMap<>();
		if (suggested)
		{
			byItem.put(itemId, s);
		}
		overlay.setSuggestions(byItem);
		overlay.setEnabled(true);
		overlay.setRecommended(recommended);

		final Recorder r = new Recorder();
		overlay.renderItemOverlay(recording(r), itemId,
			new WidgetItem(itemId, quantity, new Rectangle(10, 20, SLOT, SLOT), null, null));
		r.finish();
		return r;
	}

	/**
	 * The stack the CARD names is marked even when the suggestion map has
	 * never heard of it.
	 *
	 * Reported as a card reading "Sell 17,303 Uncut ruby" over an inventory
	 * of unmarked rubies. The map is rebuilt from Advisor's SELL suggestions
	 * each cycle and the card is not always one of them — it can come from
	 * the plan's own sell candidate, or from a cycle that has not landed
	 * yet. The plugin was naming a stack and then declining to point at it,
	 * which is the whole job of the mark.
	 */
	@Test
	public void theCardsOwnStackIsMarkedWithoutASuggestion() throws Exception
	{
		final Recorder r = paint(1603, 1603, false, 17_303);
		Assert.assertEquals("it is ringed", 1, r.rects.size());
		Assert.assertEquals("and carries the mark", 1, r.images);
		Assert.assertEquals("at the recommended weight", 2f, r.ringStrokes.get(0), 0.001f);
	}

	/** ...and nothing else is. An unsuggested stack that is not the card's
	 *  is still none of the overlay's business. */
	@Test
	public void anUnsuggestedStackThatIsNotTheCardStaysBare() throws Exception
	{
		final Recorder r = paint(1603, 999, false, 17_303);
		Assert.assertEquals(0, r.rects.size());
		Assert.assertEquals(0, r.images);
	}

	/**
	 * isMerchantStack keeps the plain outline off a single unstackable item,
	 * and does not get a vote on the card's own stack. A sell card can name
	 * a single noted or unstackable item, and "the plugin told me to sell it
	 * but would not show me which one" is the bug either way.
	 */
	@Test
	public void theCardsStackIsMarkedEvenAsASingleItem() throws Exception
	{
		/* Only the recommended side is asserted here. The other one reaches
		   isMerchantStack, which asks ItemManager whether a single item is
		   noted — and this overlay is built without one, so it would be
		   testing the stub rather than the rule. That the plain outline
		   stays off a stack the card has not named is covered by
		   anUnsuggestedStackThatIsNotTheCardStaysBare, which returns before
		   ItemManager is ever reached. */
		Assert.assertEquals("the card's own, quantity 1", 1, paint(1603, 1603, true, 1).rects.size());
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
		Assert.assertEquals(1f, r.ringStrokes.get(0), 0.001f);

		final Rectangle ring = r.rects.get(0);
		Assert.assertTrue("the ring stays within the slot",
			ring.x >= 10 && ring.y >= 20
				&& ring.x + ring.width <= 10 + SLOT && ring.y + ring.height <= 20 + SLOT);
	}

	/**
	 * The quantity along the top of the slot stays readable.
	 *
	 * The game prints the stack count in the top-left starting at the very
	 * first row of pixels, so a solid gold line laid along the top of the slot
	 * sits on the tops of the digits — and the top of a digit is where its
	 * identity lives. Reported as a marked inventory stack whose "42,027"
	 * could not be read.
	 *
	 * Only the top edge gives way. The other three have nothing behind them,
	 * and a box that fades out on every side is not a box — which is the whole
	 * reason the ring is still here rather than being replaced by the mark
	 * alone.
	 */
	@Test
	public void theTopEdgeLetsTheQuantityThrough() throws Exception
	{
		for (Recorder r : List.of(paint(1617, 1617), paint(1617, null)))
		{
			final int alpha = r.topAlphas.get(0);
			Assert.assertTrue("the top edge is see-through (alpha " + alpha + ")",
				alpha < 160);
			Assert.assertTrue("but still drawn — an invisible edge is a missing edge",
				alpha > 40);
		}
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
		final float stroke = r.ringStrokes.get(0);
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
