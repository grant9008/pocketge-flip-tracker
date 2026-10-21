package com.pocketge.tracker;

import java.awt.Color;
import java.awt.Insets;
import javax.swing.BoxLayout;
import javax.swing.JPanel;
import org.junit.Assert;
import org.junit.Test;

/**
 * Watchlist rows outlined for a price spike: stacked, they drew their
 * touching edges twice — "these bars when stacked get double thick
 * borders". The lower row leaves its top edge to the one above.
 */
public class SpikeBorderTest
{
	private static JPanel spikeRow()
	{
		final JPanel p = new JPanel();
		p.putClientProperty(FavoritesPanel.SPIKE_ROW, Boolean.TRUE);
		return p;
	}

	@Test
	public void aRowUnderAnOutlinedRowSharesItsEdge()
	{
		final JPanel rows = new JPanel();
		rows.setLayout(new BoxLayout(rows, BoxLayout.Y_AXIS));
		final JPanel plain = new JPanel();
		final JPanel first = spikeRow();
		final JPanel second = spikeRow();
		rows.add(plain);
		rows.add(first);
		rows.add(second);
		Assert.assertFalse("under a plain row: its own top edge", FavoritesPanel.outlinedAbove(rows, first));
		Assert.assertTrue("under an outlined row: shared", FavoritesPanel.outlinedAbove(rows, second));
		Assert.assertFalse("the first row has nothing above", FavoritesPanel.outlinedAbove(rows, plain));
		first.setVisible(false);
		Assert.assertFalse("a hidden row above draws nothing to share", FavoritesPanel.outlinedAbove(rows, second));
	}

	/** One line between them, 2px like every other edge, and no row changes height. */
	@Test
	public void theSharedEdgeIsOneLineAndTheRowKeepsItsHeight()
	{
		final JPanel probe = new JPanel();
		final Insets own = FavoritesPanel.spikeBorder(Color.RED, false).getBorderInsets(probe);
		final Insets shared = FavoritesPanel.spikeBorder(Color.RED, true).getBorderInsets(probe);
		Assert.assertEquals("same height either way", own.top + own.bottom, shared.top + shared.bottom);
		Assert.assertEquals(own.left, shared.left);
		Assert.assertEquals(own.right, shared.right);
		final javax.swing.border.CompoundBorder b = (javax.swing.border.CompoundBorder) FavoritesPanel.spikeBorder(Color.RED, true);
		Assert.assertEquals("no top line of its own", 0, b.getOutsideBorder().getBorderInsets(probe).top);
		Assert.assertEquals("and its bottom line still there", 2, b.getOutsideBorder().getBorderInsets(probe).bottom);
	}
}
