package com.pocketge.tracker;

import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JComponent;
import javax.swing.JPanel;
import javax.swing.border.CompoundBorder;
import javax.swing.border.MatteBorder;
import org.junit.Assert;
import org.junit.Test;

/**
 * "Every time i cook a shark, it flashes the colors on the side bar": each
 * inventory change rebuilt the watchlist, and a rebuilt row was painted bare
 * until its glow timer's first tick. Unchanged rows are now left alone, and
 * a rebuilt row wears its glow from its first frame.
 */
public class WatchlistFlickerTest
{
	private static FavoritesPanel panel()
	{
		final FavoritesPanel.Actions actions = (FavoritesPanel.Actions) Proxy.newProxyInstance(
			FavoritesPanel.Actions.class.getClassLoader(), new Class<?>[]{FavoritesPanel.Actions.class},
			(p, m, a) ->
			{
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? (Object) false : r.isPrimitive() ? (Object) 0 : null;
			});
		final FavoritesPanel panel = new FavoritesPanel(null, actions);
		panel.setLoggedIn(true);
		return panel;
	}

	private static List<FavoritesPanel.Row> rows()
	{
		final List<FavoritesPanel.Row> out = new ArrayList<>();
		final int[] ids = {1607, 555, 1603};
		final String[] names = {"Sapphire", "Water rune", "Ruby"};
		for (int i = 0; i < ids.length; i++)
		{
			final FavoritesPanel.Row r = new FavoritesPanel.Row();
			r.id = ids[i];
			r.name = names[i];
			r.price = 100 + i;
			r.tier = i == 0 ? PriceExtremes.Tier.NONE : PriceExtremes.Tier.LOW_5D;
			r.changePct = i == 0 ? -17 : 0; // the sapphire's spike
			out.add(r);
		}
		return out;
	}

	private static JPanel rowsPanel(FavoritesPanel p) throws Exception
	{
		final Field f = FavoritesPanel.class.getDeclaredField("rows");
		f.setAccessible(true);
		return (JPanel) f.get(p);
	}

	@Test
	public void anUnchangedListIsLeftAlone() throws Exception
	{
		final FavoritesPanel p = panel();
		p.update(rows());
		final java.awt.Component[] before = rowsPanel(p).getComponents();
		p.update(rows()); // a shark cooked: the same rows, as new objects
		Assert.assertArrayEquals("the same row components, glows untouched", before, rowsPanel(p).getComponents());

		final List<FavoritesPanel.Row> moved = rows();
		moved.get(1).price = 999;
		p.update(moved);
		Assert.assertNotSame("a real change rebuilds", before[1], rowsPanel(p).getComponents()[1]);
		p.stopPulseTimers();
	}

	@Test
	public void aRebuiltRowGlowsFromItsFirstFrame() throws Exception
	{
		final FavoritesPanel p = panel();
		p.update(rows());
		final java.awt.Component[] built = rowsPanel(p).getComponents();
		Assert.assertFalse("the spike row's outline is on at once, not the bare padding",
			((JComponent) built[0]).getBorder() instanceof javax.swing.border.EmptyBorder);
		for (int i = 1; i < 3; i++)
		{
			final javax.swing.border.Border b = ((JComponent) built[i]).getBorder();
			Assert.assertTrue("the 5-day glow's accent is on at once, row " + i,
				b instanceof CompoundBorder && ((CompoundBorder) b).getOutsideBorder() instanceof MatteBorder);
		}
		Assert.assertTrue(FavoritesPanel.sameRows(rows(), rows()));
		final List<FavoritesPanel.Row> held = rows();
		held.get(2).heldQty = 5;
		Assert.assertFalse("what you hold counts too: the card reads it", FavoritesPanel.sameRows(rows(), held));
		p.stopPulseTimers();
	}

	/** A rebuild straight after typing in the item search keeps the search:
	 *  it stops the row glows, not the search's 200ms debounce. */
	@Test
	public void aRebuildDoesNotCancelATypedSearch() throws Exception
	{
		final java.util.concurrent.atomic.AtomicInteger searches = new java.util.concurrent.atomic.AtomicInteger();
		final FavoritesPanel.Actions actions = (FavoritesPanel.Actions) Proxy.newProxyInstance(
			FavoritesPanel.Actions.class.getClassLoader(), new Class<?>[]{FavoritesPanel.Actions.class},
			(px, m, a) ->
			{
				if ("searchItems".equals(m.getName()))
				{
					searches.incrementAndGet();
				}
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? (Object) false : r.isPrimitive() ? (Object) 0 : null;
			});
		final FavoritesPanel p = new FavoritesPanel(null, actions);
		p.setLoggedIn(true);
		p.update(rows());
		final Field f = FavoritesPanel.class.getDeclaredField("searchField");
		f.setAccessible(true);
		final javax.swing.JTextField field = (javax.swing.JTextField) f.get(p);
		javax.swing.SwingUtilities.invokeAndWait(() -> field.setText("shark"));
		final List<FavoritesPanel.Row> changed = rows();
		changed.get(0).heldQty = 1; // a shark cooked: a real change, so a real rebuild
		javax.swing.SwingUtilities.invokeAndWait(() -> p.update(changed));
		Thread.sleep(600);
		Assert.assertTrue("the typed search still ran", searches.get() >= 1);
		p.stopPulseTimers();
	}

	/** Muted, no row glows — the badges stay, the spike's still in its
	 *  colours — and the row menu offers to unmute. */
	@Test
	public void mutedRowsKeepTheirBadgesButNotTheirBorders() throws Exception
	{
		final FavoritesPanel p = panel();
		p.setGlowEnabled(false);
		p.update(rows());
		for (java.awt.Component c : rowsPanel(p).getComponents())
		{
			Assert.assertTrue("no glow border", ((JComponent) c).getBorder() instanceof javax.swing.border.EmptyBorder);
			Assert.assertNull("not marked as an outlined row", ((JComponent) c).getClientProperty(FavoritesPanel.SPIKE_ROW));
		}
		final javax.swing.JLabel spike = findLabel(rowsPanel(p).getComponent(0), "%");
		Assert.assertNotNull("the spike's % badge is still there", spike);
		Assert.assertNotEquals("in its colours, not Swing's grey", new javax.swing.JLabel().getForeground(), spike.getForeground());
		Assert.assertEquals("Unmute glow borders", ((javax.swing.JMenuItem) p.rowMenu(rows().get(0)).getComponent(3)).getText());
		p.setGlowEnabled(true);
		Assert.assertEquals("Mute glow borders", ((javax.swing.JMenuItem) p.rowMenu(rows().get(0)).getComponent(3)).getText());
		Assert.assertFalse(((JComponent) rowsPanel(p).getComponent(0)).getBorder() instanceof javax.swing.border.EmptyBorder);
		p.stopPulseTimers();
	}

	private static javax.swing.JLabel findLabel(java.awt.Component c, String containing)
	{
		if (c instanceof javax.swing.JLabel && ((javax.swing.JLabel) c).getText() != null
			&& ((javax.swing.JLabel) c).getText().contains(containing))
		{
			return (javax.swing.JLabel) c;
		}
		if (c instanceof java.awt.Container)
		{
			for (java.awt.Component k : ((java.awt.Container) c).getComponents())
			{
				final javax.swing.JLabel l = findLabel(k, containing);
				if (l != null)
				{
					return l;
				}
			}
		}
		return null;
	}
}
