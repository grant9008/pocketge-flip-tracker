package com.pocketge.tracker;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.List;
import javax.swing.JLabel;
import org.junit.Assert;
import org.junit.Test;

/**
 * The one-time card for someone with flips to see and the bridge off:
 * "unlinked people should be maybe encouraged wih a 1 time popup message
 * suggesting to link".
 */
public class BridgeNudgeTest
{
	private final List<String> calls = new ArrayList<>();

	private MainPanel panel()
	{
		final MainPanel.Actions actions = (MainPanel.Actions) Proxy.newProxyInstance(
			MainPanel.Actions.class.getClassLoader(), new Class<?>[]{MainPanel.Actions.class},
			(proxy, method, args) ->
			{
				calls.add(method.getName());
				final Class<?> t = method.getReturnType();
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
		return new MainPanel(null, actions);
	}

	private static JLabel label(Container root, String text)
	{
		for (Component c : root.getComponents())
		{
			if (c instanceof JLabel && text.equals(((JLabel) c).getText()))
			{
				return (JLabel) c;
			}
			if (c instanceof Container)
			{
				final JLabel in = label((Container) c, text);
				if (in != null)
				{
					return in;
				}
			}
		}
		return null;
	}

	private static void click(JLabel l)
	{
		l.dispatchEvent(new MouseEvent(l, MouseEvent.MOUSE_CLICKED, 0L, 0, 2, 2, 1, false, MouseEvent.BUTTON1));
	}

	/** Hidden until the plugin says so, and gone at once on either answer. */
	@Test
	public void itShowsOnceAndEitherAnswerEndsIt()
	{
		final MainPanel p = panel();
		final JLabel link = label(p, "Link it ↗");
		final JLabel later = label(p, "Not now");
		Assert.assertNotNull(link);
		Assert.assertNotNull(later);
		final Component card = link.getParent().getParent();
		Assert.assertFalse("not until asked for", card.isVisible());

		p.setBridgeNudge(true);
		Assert.assertTrue(card.isVisible());
		click(later);
		Assert.assertFalse(card.isVisible());
		Assert.assertTrue(calls.contains("dismissBridgeNudge"));

		p.setBridgeNudge(true);
		click(link);
		Assert.assertFalse(card.isVisible());
		Assert.assertTrue(calls.contains("linkWebsite"));
	}
}
