package com.pocketge.tracker;

import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import javax.swing.JLabel;
import javax.swing.JPopupMenu;
import org.junit.Assert;
import org.junit.Test;

/**
 * "make it so you can select the color of these lists": the switcher that
 * lists them carries the palette as dots, for the list it is showing.
 */
public class ListColourTest
{
	private static double luminance(Color c)
	{
		final double[] ch = {c.getRed() / 255.0, c.getGreen() / 255.0, c.getBlue() / 255.0};
		for (int i = 0; i < 3; i++)
		{
			ch[i] = ch[i] <= 0.03928 ? ch[i] / 12.92 : Math.pow((ch[i] + 0.055) / 1.055, 2.4);
		}
		return 0.2126 * ch[0] + 0.7152 * ch[1] + 0.0722 * ch[2];
	}

	@Test
	public void everyColourHasANameAndReadsOnThePanel()
	{
		Assert.assertEquals(FavoriteLists.PALETTE.length, FavoriteLists.PALETTE_NAMES.length);
		Assert.assertEquals("distinct", FavoriteLists.PALETTE.length,
			new HashSet<>(java.util.Arrays.asList(FavoriteLists.PALETTE)).size());
		final double bg = luminance(Brand.BG_PANEL);
		for (int i = 0; i < FavoriteLists.PALETTE.length; i++)
		{
			final double fg = luminance(Color.decode(FavoriteLists.PALETTE[i]));
			/* The list's name is written in its colour. Purple, one of the
			   original seven, is the floor at about 3.7:1. */
			Assert.assertTrue(FavoriteLists.PALETTE_NAMES[i] + " too dark",
				(fg + 0.05) / (bg + 0.05) >= 3.5);
		}
	}

	@Test
	public void theOriginalSevenAreWhereTheyWere()
	{
		/* New lists are dealt from the front of the palette, and the first
		   list ever made is PALETTE[0]: reordering would change what a fresh
		   install looks like. */
		final String[] before = {"#E5C158", "#26A69A", "#EF5350", "#42A5F5", "#AB47BC", "#66BB6A", "#FF9F43"};
		for (int i = 0; i < before.length; i++)
		{
			Assert.assertEquals(before[i], FavoriteLists.PALETTE[i]);
		}
	}

	private static void collect(Container root, List<JLabel> out)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && ((JLabel) c).getIcon() instanceof FavoritesPanel.Swatch)
			{
				out.add((JLabel) c);
			}
			if (c instanceof Container)
			{
				collect((Container) c, out);
			}
		}
	}

	private static FavoritesPanel panel(List<String> calls)
	{
		final FavoritesPanel.Actions actions = (FavoritesPanel.Actions) Proxy.newProxyInstance(
			FavoritesPanel.Actions.class.getClassLoader(), new Class<?>[]{FavoritesPanel.Actions.class},
			(p, m, a) ->
			{
				if (m.getName().equals("recolorList") || m.getName().equals("selectList")
					|| m.getName().equals("deleteList"))
				{
					calls.add(m.getName() + ":" + java.util.Arrays.toString(a));
				}
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? (Object) false : r.isPrimitive() ? (Object) 0 : null;
			});
		return new FavoritesPanel(null, actions);
	}

	private static FavoritesPanel.ListMeta list(String id, String name, String colour)
	{
		final FavoritesPanel.ListMeta l = new FavoritesPanel.ListMeta();
		l.id = id;
		l.name = name;
		l.color = colour;
		return l;
	}

	private static void release(Component c, int button, int x)
	{
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_PRESSED, 0,
			button == MouseEvent.BUTTON1 ? java.awt.event.InputEvent.BUTTON1_DOWN_MASK : java.awt.event.InputEvent.BUTTON3_DOWN_MASK,
			x, 4, 1, button == MouseEvent.BUTTON3, button));
		c.dispatchEvent(new MouseEvent(c, MouseEvent.MOUSE_RELEASED, 0, 0, x, 4, 1, false, button));
	}

	/** "make it so you can do it from here": in the switcher a left-click
	 *  still switches lists, and a right-click does not — it is the list's
	 *  own menu. */
	@Test
	public void aRightClickInTheSwitcherIsTheListsMenuNotASwitch()
	{
		final List<String> calls = new ArrayList<>();
		final FavoritesPanel panel = panel(calls);
		final FavoritesPanel.ListMeta members = list("m", "Members", FavoriteLists.PALETTE[1]);
		panel.updateLists(List.of(list("f", "Favorites", FavoriteLists.PALETTE[0]), members), "f");
		final JPopupMenu switcher = new JPopupMenu();
		final FavoritesPanel.ListItem item = panel.new ListItem(members, false, switcher, new javax.swing.JButton());
		item.setSize(item.getPreferredSize());

		release(item, MouseEvent.BUTTON3, 4);
		Assert.assertTrue("a right-click switches nothing: " + calls, calls.isEmpty());

		release(item, MouseEvent.BUTTON1, 4);
		Assert.assertEquals(List.of("selectList:[m]"), calls);
		Assert.assertTrue(item.getToolTipText().contains("right-click"));
	}

	@Test
	public void theListsMenuRenamesRecoloursAndDeletes()
	{
		final List<String> calls = new ArrayList<>();
		final FavoritesPanel panel = panel(calls);
		final FavoritesPanel.ListMeta members = list("m", "Members", FavoriteLists.PALETTE[1]);
		panel.updateLists(List.of(list("f", "Favorites", FavoriteLists.PALETTE[0]), members), "f");
		final JPopupMenu menu = panel.listMenu(members, new javax.swing.JButton());
		final List<String> labels = new ArrayList<>();
		javax.swing.JMenu colour = null;
		javax.swing.JMenuItem delete = null;
		for (Component c : menu.getComponents())
		{
			if (c instanceof javax.swing.JMenuItem)
			{
				final javax.swing.JMenuItem mi = (javax.swing.JMenuItem) c;
				labels.add(mi.getText());
				if (mi instanceof javax.swing.JMenu)
				{
					colour = (javax.swing.JMenu) mi;
				}
				else if (mi.getText().startsWith("Delete"))
				{
					delete = mi;
				}
			}
		}
		Assert.assertEquals(List.of("Rename list…", "Colour", "Delete list…"), labels);
		Assert.assertEquals(FavoriteLists.PALETTE.length, colour.getItemCount());
		colour.getItem(7).doClick();
		Assert.assertEquals(List.of("recolorList:[m, " + FavoriteLists.PALETTE[7] + "]"), calls);
		Assert.assertTrue("two lists, so either can go", delete.isEnabled());

		panel.updateLists(List.of(members), "m");
		javax.swing.JMenuItem only = null;
		for (Component c : panel.listMenu(members, new javax.swing.JButton()).getComponents())
		{
			if (c instanceof javax.swing.JMenuItem && ((javax.swing.JMenuItem) c).getText().startsWith("Delete"))
			{
				only = (javax.swing.JMenuItem) c;
			}
		}
		Assert.assertFalse("the last list stays", only.isEnabled());
	}

	@Test
	public void aDotRecoloursTheListShownAndMarksItsOwn()
	{
		final List<String> calls = new ArrayList<>();
		final FavoritesPanel.Actions actions = (FavoritesPanel.Actions) Proxy.newProxyInstance(
			FavoritesPanel.Actions.class.getClassLoader(), new Class<?>[]{FavoritesPanel.Actions.class},
			(p, m, a) ->
			{
				if (m.getName().equals("recolorList"))
				{
					calls.add(a[0] + "=" + a[1]);
				}
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? (Object) false : r.isPrimitive() ? (Object) 0 : null;
			});
		final FavoritesPanel panel = new FavoritesPanel(null, actions);
		final FavoritesPanel.ListMeta members = new FavoritesPanel.ListMeta();
		members.id = "m";
		members.name = "Members";
		members.color = FavoriteLists.PALETTE[1];

		final JPopupMenu menu = new JPopupMenu();
		final Container row = panel.colourRow(members, menu);
		final List<JLabel> dots = new ArrayList<>();
		collect(row, dots);
		Assert.assertEquals(FavoriteLists.PALETTE.length, dots.size());
		for (int i = 0; i < dots.size(); i++)
		{
			Assert.assertEquals("only the list's own colour is ringed", i == 1,
				((FavoritesPanel.Swatch) dots.get(i).getIcon()).current());
			Assert.assertEquals(FavoriteLists.PALETTE_NAMES[i], dots.get(i).getToolTipText());
		}

		final JLabel pink = dots.get(7);
		pink.setSize(pink.getPreferredSize());
		for (MouseListener l : pink.getMouseListeners())
		{
			l.mouseReleased(new MouseEvent(pink, MouseEvent.MOUSE_RELEASED, 0, 0, 4, 4, 1, false, MouseEvent.BUTTON1));
		}
		Assert.assertEquals(List.of("m=" + FavoriteLists.PALETTE[7]), calls);

		/* A release that drifted off the dot is not a choice. */
		calls.clear();
		for (MouseListener l : pink.getMouseListeners())
		{
			l.mouseReleased(new MouseEvent(pink, MouseEvent.MOUSE_RELEASED, 0, 0, 400, 4, 1, false, MouseEvent.BUTTON1));
		}
		Assert.assertTrue(calls.isEmpty());
	}
}
