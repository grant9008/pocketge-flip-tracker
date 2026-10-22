package com.pocketge.tracker;

import java.awt.Component;
import java.awt.Container;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import javax.swing.JLabel;
import org.junit.Assert;
import org.junit.Test;

/**
 * "may as well add a small chevron here like above for flip ideas incase
 * people want to collapse this": the eight squares fold to one line, and
 * that line still says which slots want you.
 */
public class GeSlotsFoldTest
{
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
				final JLabel found = label((Container) c, text);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static void click(Component c)
	{
		for (MouseListener l : c.getMouseListeners())
		{
			l.mouseClicked(new MouseEvent(c, MouseEvent.MOUSE_CLICKED, 0, 0, 1, 1, 1, false, MouseEvent.BUTTON1));
		}
	}

	private static GeSlotsPanel.SlotInfo slot(GeSlotsPanel.SlotState state, boolean skipped)
	{
		final GeSlotsPanel.SlotInfo s = new GeSlotsPanel.SlotInfo();
		s.state = state;
		s.adviceSkipped = skipped;
		return s;
	}

	@Test
	public void theChevronFoldsTheGridToOneLineAndBack()
	{
		final GeSlotsPanel p = new GeSlotsPanel(null, null);
		final int open = p.getPreferredSize().height;
		Assert.assertNull("folded line not shown while open", label(p, "GE slots hidden"));

		click(label(p, "▾"));
		Assert.assertNotNull(label(p, "GE slots hidden"));
		final int folded = p.getPreferredSize().height;
		Assert.assertTrue("one line, not a grid: " + folded + " vs " + open, folded > 0 && folded < open / 2);
		Assert.assertEquals("never a wide preferred width", 0, p.getPreferredSize().width);
		Assert.assertEquals(folded, p.getMaximumSize().height);

		click(label(p, "GE slots hidden"));
		Assert.assertEquals(open, p.getPreferredSize().height);
		Assert.assertNull(label(p, "GE slots hidden"));
	}

	@Test
	public void theFoldedLineStillSaysWhatWantsYou()
	{
		final GeSlotsPanel p = new GeSlotsPanel(null, null);
		click(label(p, "▾"));
		p.update(new GeSlotsPanel.SlotInfo[]{
			slot(GeSlotsPanel.SlotState.READY_COLLECT, false),
			slot(GeSlotsPanel.SlotState.READY_COLLECT, true),
			slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, false),
			slot(GeSlotsPanel.SlotState.ACTIVE_LOSING, false),
			slot(GeSlotsPanel.SlotState.ACTIVE_ADJUST, true),
			slot(GeSlotsPanel.SlotState.ACTIVE_OK, false),
			null,
		});
		final JLabel ready = label(p, "2 ready");
		final JLabel reprice = label(p, "2 to reprice");
		Assert.assertNotNull("collectable even if you priced it yourself", ready);
		Assert.assertNotNull("a slot you price yourself is not counted", reprice);
		Assert.assertTrue(ready.isVisible() && reprice.isVisible());

		p.update(new GeSlotsPanel.SlotInfo[]{slot(GeSlotsPanel.SlotState.ACTIVE_OK, false)});
		Assert.assertFalse("nothing to say, nothing shown", label(p, "0 ready").isVisible());
		Assert.assertFalse(label(p, "0 to reprice").isVisible());
		Assert.assertEquals(0, GeSlotsPanel.readyCount(null));
		Assert.assertEquals(0, GeSlotsPanel.repriceCount(null));
	}
}
