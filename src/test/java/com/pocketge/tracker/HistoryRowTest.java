package com.pocketge.tracker;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import org.junit.Assert;
import org.junit.Test;

/**
 * A recent-flip row: "can this area be clearer to read", and the click that
 * its tooltip promises.
 */
public class HistoryRowTest
{
	private static List<Component> allOf(Container root)
	{
		final List<Component> out = new ArrayList<>();
		for (Component c : root.getComponents())
		{
			out.add(c);
			if (c instanceof Container)
			{
				out.addAll(allOf((Container) c));
			}
		}
		return out;
	}

	private static JLabel label(HistoryPanel p, String text)
	{
		for (Component c : allOf(p))
		{
			if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
			{
				return (JLabel) c;
			}
		}
		throw new AssertionError("no label reading \"" + text + "\"");
	}

	/**
	 * A click on the item's NAME opens its chart. The name carries a
	 * tooltip, so it has a mouse listener of its own, and Swing hands the
	 * click to the deepest component that listens — the label, not the row.
	 * With the handler on the row alone, only the gap between the name and
	 * the figure did anything.
	 */
	@Test
	public void clickingTheTextOpensTheChart()
	{
		final List<String> opened = new ArrayList<>();
		final HistoryPanel p = new HistoryPanel(opened::add);
		final List<Flip> flips = new ArrayList<>();
		flips.add(new Flip(1_000L, 565, "Blood rune", 21_000, 21_000L * 290, 21_000L * 308, 21_000L * 6));
		p.update(flips, FlipStats.Range.ALL);

		for (String text : new String[]{"Blood rune", "21K × ", "+252K gp"})
		{
			final JLabel l = label(p, text);
			l.dispatchEvent(new MouseEvent(l, MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
		}
		Assert.assertEquals(List.of("Blood rune", "Blood rune", "Blood rune"), opened);
	}

	/** The count is its own, quieter label, so the name is what reads
	 *  first; and the list is the stats rows' size, not a size smaller. */
	@Test
	public void theNameStandsApartFromTheCount()
	{
		final HistoryPanel p = new HistoryPanel(n -> { });
		final List<Flip> flips = new ArrayList<>();
		flips.add(new Flip(1_000L, 565, "Blood rune", 21_000, 21_000L * 290, 21_000L * 308, 21_000L * 6));
		p.update(flips, FlipStats.Range.ALL);

		final JLabel name = label(p, "Blood rune");
		final JLabel count = label(p, "21K × ");
		Assert.assertNotEquals(name.getForeground(), count.getForeground());
		Assert.assertEquals(12f, name.getFont().getSize2D(), 0.01f);
		Assert.assertEquals(12f, label(p, "+252K gp").getFont().getSize2D(), 0.01f);
		Assert.assertEquals(12f, label(p, "1 flip recorded").getFont().getSize2D(), 0.01f);
	}
}
