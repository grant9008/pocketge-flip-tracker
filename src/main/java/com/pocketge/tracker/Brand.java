package com.pocketge.tracker;

import java.awt.Color;

/**
 * pocketge.com's own surface colours, so the sidebar and the website are one
 * product rather than two that share a name.
 *
 * <h2>Why</h2>
 * The recommendation card has been the website's {@code --bg-panel} since it
 * was built, and everything around it was RuneLite's stock neutral greys. That
 * reads exactly as what it is — a warm brown panel sitting in a grey box — and
 * was spotted from the screenshot alone: "im not crazy right, that top section
 * is brown? i like it alot, matches the website. can we apply that look to the
 * rest of the sidebar of our plugin".
 *
 * So the greys go, and the values below are lifted from {@code app.css}
 * verbatim. Each names the custom property it came from. Changing one here
 * without changing it there is the drift this class exists to make visible —
 * BrandTest pins every value.
 *
 * <h2>The ramp</h2>
 * Three surfaces, darkest at the back, exactly as the site stacks them:
 *
 * <pre>
 *   BG_BASE   0C0B09   the sidebar itself — what a card floats on
 *   BG_PANEL  1B1815   cards, boxes, popups
 *   BG_INPUT  282320   buttons, headers, fields — things you click or type in
 * </pre>
 *
 * A border sits above whichever surface it is drawn on: {@link #BORDER_MAIN}
 * for a panel's own outline, {@link #BORDER_LIGHT} for a divider that has to
 * be seen across one.
 *
 * <h2>What is deliberately NOT here</h2>
 * The buy and sell hues. Those are the player's, chosen in settings, and they
 * arrive through {@link PocketGeTrackerConfig.ColourTheme} — the site's
 * {@code --buy-color} and {@code --sell-color} are only that theme's defaults.
 * Freezing them here would put a second, unthemed copy of the two colours that
 * carry the plugin's only real meaning into the codebase, which is the exact
 * bug the bank marks and the watchlist badges have each been fixed for once.
 */
final class Brand
{
	/** {@code --bg-base}. The page, and the sidebar: the deepest surface,
	 *  what everything else is laid on. */
	static final Color BG_BASE = new Color(0x0C, 0x0B, 0x09);

	/** {@code --bg-chart}. Behind a plotted series — and the one step between
	 *  base and panel, for anything that should recede without going black. */
	static final Color BG_CHART = new Color(0x13, 0x11, 0x10);

	/** {@code --bg-panel}. Cards, boxes, popups. The colour that was already
	 *  right, and the reason the rest of this file exists. */
	static final Color BG_PANEL = new Color(0x1B, 0x18, 0x15);

	/** {@code --bg-input}. Buttons, headers, text fields: things you click or
	 *  type into, raised off the panel behind them. */
	static final Color BG_INPUT = new Color(0x28, 0x23, 0x20);

	/** {@code --border-main}. A panel's own outline — present, not loud. */
	static final Color BORDER_MAIN = new Color(0x2B, 0x26, 0x21);

	/** {@code --border-light}. A divider that has to carry across a surface,
	 *  and the outline of anything interactive. */
	static final Color BORDER_LIGHT = new Color(0x3C, 0x35, 0x2B);

	/** {@code --text-main}. Item names and figures. Identical to
	 *  AdvisorPanel.TEXT_MAIN and TipStyle.SUBJECT, which both predate this
	 *  class and both took it from the same place. */
	static final Color TEXT_MAIN = new Color(0xD9, 0xD3, 0xC7);

	/**
	 * {@code --text-structural}. The small-caps labels over a figure —
	 * QUANTITY, VALUE, PAID @.
	 *
	 * Warm, where RuneLite's LIGHT_GRAY_COLOR is neutral. It is a label on a
	 * panel, so it is read at a glance or not at all, and the site sets it
	 * two steps brighter than {@code --text-muted} for exactly that reason.
	 */
	static final Color TEXT_STRUCTURAL = new Color(0x94, 0x8A, 0x78);

	/**
	 * {@code --text-muted}. Secondary prose on a panel.
	 *
	 * Note this is 8A8274 — the colour TipStyle bans. Both are right: on the
	 * site's {@code --bg-panel} it is comfortably legible, and the thing that
	 * failed was putting it on the GAME's tooltip background, which is a
	 * different and much closer substrate. A colour is only readable against
	 * something.
	 */
	static final Color TEXT_MUTED = new Color(0x8A, 0x82, 0x74);

	/** {@code --accent-bright}. The site's gold accent. */
	static final Color ACCENT_BRIGHT = new Color(0xE5, 0xC1, 0x58);

	/**
	 * Dresses a Swing control that would otherwise arrive in RuneLite's stock
	 * grey — a text field, a combo box, a plain button.
	 *
	 * These are the components nothing in the plugin had ever painted, because
	 * they look fine until the surface behind them stops being grey too. The
	 * search box over the watchlist and the Session/Reset pair over the stats
	 * were the last two neutral rectangles in a warm sidebar.
	 *
	 * Deliberately NOT a UIManager default. Setting one would reach every
	 * Swing component in the client, including other plugins' panels, which is
	 * not this plugin's to change.
	 *
	 * @param c    the control
	 * @param text its foreground, or null to leave the existing one — a
	 *             placeholder and a typed query are different colours, and
	 *             this must not flatten them
	 */
	static void control(javax.swing.JComponent c, Color text)
	{
		c.setBackground(BG_INPUT);
		if (text != null)
		{
			c.setForeground(text);
		}
		c.setBorder(javax.swing.BorderFactory.createCompoundBorder(
			javax.swing.BorderFactory.createLineBorder(BORDER_LIGHT, 1),
			javax.swing.BorderFactory.createEmptyBorder(2, 5, 2, 5)));
		if (c instanceof javax.swing.text.JTextComponent)
		{
			/* Or the caret is the LAF's near-black on a near-black field. */
			((javax.swing.text.JTextComponent) c).setCaretColor(TEXT_MAIN);
		}
		if (c instanceof javax.swing.AbstractButton)
		{
			final javax.swing.AbstractButton b = (javax.swing.AbstractButton) c;
			b.setFocusPainted(false);
			/* A JButton ignores setBackground under several look-and-feels
			   unless it is told to fill its own content area. */
			b.setOpaque(true);
			b.setContentAreaFilled(true);
		}
	}

	private Brand()
	{
	}
}
