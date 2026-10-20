package com.pocketge.tracker;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JMenuItem;
import javax.swing.JPopupMenu;
import org.junit.Assert;
import org.junit.Test;

/**
 * A watchlist row's right-click offers the chart both ways, as the chart
 * button does: "lemme right click open pocket ge chart here in favorites
 * also".
 */
public class WatchlistMenuTest
{
	@Test
	public void theRowMenuCanSendTheItemToTheOpenTab()
	{
		final List<String> calls = new ArrayList<>();
		final FavoritesPanel.Actions actions = (FavoritesPanel.Actions) Proxy.newProxyInstance(
			FavoritesPanel.Actions.class.getClassLoader(), new Class<?>[]{FavoritesPanel.Actions.class},
			(p, m, a) ->
			{
				calls.add(m.getName() + (a != null && a.length > 0 ? " " + a[0] : ""));
				final Class<?> r = m.getReturnType();
				return r == boolean.class ? (Object) false : r.isPrimitive() ? (Object) 0 : null;
			});
		final FavoritesPanel panel = new FavoritesPanel(null, actions);
		final FavoritesPanel.Row row = new FavoritesPanel.Row();
		row.id = 1621;
		row.name = "Uncut emerald";
		final JPopupMenu menu = panel.rowMenu(row);

		Assert.assertEquals("Open PocketGE chart", ((JMenuItem) menu.getComponent(0)).getText());
		final JMenuItem send = (JMenuItem) menu.getComponent(1);
		Assert.assertEquals(AdvisorPanel.SEND_TO_TAB, send.getText());
		calls.clear();
		send.doClick();
		Assert.assertEquals(List.of("sendChartToOpenTab Uncut emerald"), calls);
	}
}
