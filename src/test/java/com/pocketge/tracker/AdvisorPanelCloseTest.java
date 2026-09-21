package com.pocketge.tracker;

import java.awt.Component;
import java.awt.Container;
import java.lang.reflect.Field;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JButton;
import org.junit.Assert;
import org.junit.Test;

/** The ✕ on the inspection card must hand the box back, and the next refresh must not undo it. */
public class AdvisorPanelCloseTest
{
	private final List<String> calls = new ArrayList<>();

	private AdvisorPanel panel()
	{
		AdvisorPanel.Actions actions = (AdvisorPanel.Actions) Proxy.newProxyInstance(
			AdvisorPanel.Actions.class.getClassLoader(), new Class<?>[]{AdvisorPanel.Actions.class},
			(proxy, method, args) ->
			{
				calls.add(method.getName() + (args == null ? "()" : "(" + java.util.Arrays.toString(args) + ")"));
				Class<?> t = method.getReturnType();
				if (t == boolean.class)
				{
					return false;
				}
				if (t == int.class || t == long.class || t == double.class || t == float.class)
				{
					return 0;
				}
				return null;
			});
		return new AdvisorPanel(null, actions);
	}

	private static JButton find(Component c, String text)
	{
		if (c instanceof JButton && text.equals(((JButton) c).getText()))
		{
			return (JButton) c;
		}
		if (c instanceof Container)
		{
			for (Component child : ((Container) c).getComponents())
			{
				JButton b = find(child, text);
				if (b != null)
				{
					return b;
				}
			}
		}
		return null;
	}

	private static Object field(Object o, String name) throws Exception
	{
		Field f = o.getClass().getDeclaredField(name);
		f.setAccessible(true);
		return f.get(o);
	}

	@Test
	public void theCloseButtonDismissesTheCardAndTellsThePlugin() throws Exception
	{
		AdvisorPanel panel = panel();
		FavoritesPanel.Row row = new FavoritesPanel.Row();
		row.id = 555;
		row.name = "Water rune";
		panel.setSelectedItem(row);
		Assert.assertEquals(row, field(panel, "selectedFavorite"));
		JButton close = find(panel, "✕");
		Assert.assertNotNull("the card has its ✕", close);
		calls.clear();
		close.doClick();
		Assert.assertNull("the card is dismissed", field(panel, "selectedFavorite"));
		Assert.assertTrue("the plugin is told: " + calls, calls.contains("onSelectedItemChanged([null])"));
		Assert.assertNull("no ✕ left on the box", find(panel, "✕"));

		// The plugin's next refresh still carries a row for the item; that must not bring it back.
		List<FavoritesPanel.Row> rows = new ArrayList<>();
		rows.add(row);
		panel.refreshSelectedFrom(rows);
		Assert.assertNull("still dismissed after a refresh", field(panel, "selectedFavorite"));
	}
}
