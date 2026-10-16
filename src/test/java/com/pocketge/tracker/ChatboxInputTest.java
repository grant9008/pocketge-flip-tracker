package com.pocketge.tracker;

import java.awt.event.KeyEvent;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.swing.JPanel;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

/**
 * The plugin writes to the chatbox input only when the player presses E.
 *
 * RuneLite rejects plugins that "programmatically insert text into the
 * user's chatbox input for any reason ... The only way text can get into the
 * chatbox is if the user is pressing keys themselves." 0.7.0 filled the GE
 * price box the moment it opened and typed prices, quantities and item names
 * on clicks; the hub description said "only from a click", which was not
 * true either. These hold the line in both directions: one write, behind the
 * key, and the key does nothing unless the chip naming it is on screen.
 */
public class ChatboxInputTest
{
	private static final String SRC = "src/main/java/com/pocketge/tracker/";

	private static String code(String cls) throws Exception
	{
		return Files.readString(Path.of(SRC + cls + ".java"))
			.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)//.*$", " ");
	}

	/** Exactly one write to the chatbox input in the whole plugin, and it is
	 *  the one the key listener calls. */
	@Test
	public void oneWriteAndItIsTheKeys() throws Exception
	{
		int writes = 0;
		for (String cls : new String[]{"PocketGeTrackerPlugin", "GeOfferPriceOverlay", "AdvisorPanel", "MainPanel"})
		{
			final Matcher m = Pattern.compile("MESLAYERINPUT").matcher(code(cls));
			while (m.find())
			{
				writes++;
			}
		}
		Assert.assertEquals("one write to MESLAYERINPUT", 1, writes);
		final String plugin = code("PocketGeTrackerPlugin");
		final int fill = plugin.indexOf("private void fillGePrompt(");
		final int write = plugin.indexOf("MESLAYERINPUT");
		final int next = plugin.indexOf("\n\tprivate ", fill + 1);
		Assert.assertTrue("the write is inside fillGePrompt", fill > 0 && write > fill && write < next);
		Assert.assertTrue("and fillGePrompt is called from the key listener",
			plugin.substring(plugin.indexOf("fillKeyListener"), plugin.indexOf("fillKeyListener") + 3000)
				.contains("fillGePrompt("));
	}

	/** Nothing fills the price box when it opens. */
	@Test
	public void nothingHappensWhenThePromptOpens() throws Exception
	{
		Assert.assertFalse(code("PocketGeTrackerPlugin").contains("CHAT_PROMPT_INIT"));
	}

	// -- the key itself ----------------------------------------------------

	private PocketGeTrackerPlugin plugin;
	private GeOfferPriceOverlay overlay;
	private java.awt.event.KeyListener keys;

	@Before
	public void wire() throws Exception
	{
		plugin = new PocketGeTrackerPlugin();
		final Constructor<GeOfferPriceOverlay> ctor = GeOfferPriceOverlay.class
			.getDeclaredConstructor(net.runelite.api.Client.class, net.runelite.client.game.ItemManager.class);
		ctor.setAccessible(true);
		overlay = ctor.newInstance(null, null);
		final Field o = PocketGeTrackerPlugin.class.getDeclaredField("gePriceOverlay");
		o.setAccessible(true);
		o.set(plugin, overlay);
		final Field k = PocketGeTrackerPlugin.class.getDeclaredField("fillKeyListener");
		k.setAccessible(true);
		keys = (java.awt.event.KeyListener) k.get(plugin);
	}

	private void arm(long value) throws Exception
	{
		final Field a = GeOfferPriceOverlay.class.getDeclaredField("armed");
		a.setAccessible(true);
		a.set(overlay, value > 0 ? new GeOfferPriceOverlay.Armed(false, value) : null);
	}

	private static KeyEvent press(int code, int modifiers)
	{
		return new KeyEvent(new JPanel(), KeyEvent.KEY_PRESSED, 0L, modifiers, code, KeyEvent.CHAR_UNDEFINED);
	}

	private static KeyEvent typed(char c)
	{
		return new KeyEvent(new JPanel(), KeyEvent.KEY_TYPED, 0L, 0, KeyEvent.VK_UNDEFINED, c);
	}

	/** With no chip on screen, E is just E. */
	@Test
	public void anUnarmedEPassesThrough() throws Exception
	{
		arm(0);
		final KeyEvent e = press(KeyEvent.VK_E, 0);
		keys.keyPressed(e);
		Assert.assertFalse(e.isConsumed());
		final KeyEvent t = typed('e');
		keys.keyTyped(t);
		Assert.assertFalse(t.isConsumed());
	}

	/** Ctrl/Alt/Meta+E belong to whatever else is bound to them. */
	@Test
	public void aModifiedEPassesThrough() throws Exception
	{
		arm(1_120);
		for (int mod : new int[]{KeyEvent.CTRL_DOWN_MASK, KeyEvent.ALT_DOWN_MASK, KeyEvent.META_DOWN_MASK})
		{
			final KeyEvent e = press(KeyEvent.VK_E, mod);
			keys.keyPressed(e);
			Assert.assertFalse("modifier " + mod, e.isConsumed());
		}
	}

	/** Other keys are never touched, armed or not — the digits above all,
	 *  since they are how a player types their own price. */
	@Test
	public void otherKeysPassThrough() throws Exception
	{
		arm(1_120);
		for (int code : new int[]{KeyEvent.VK_1, KeyEvent.VK_K, KeyEvent.VK_ENTER, KeyEvent.VK_BACK_SPACE})
		{
			final KeyEvent e = press(code, 0);
			keys.keyPressed(e);
			Assert.assertFalse("key " + code, e.isConsumed());
		}
	}

	/** Shift+E too: the chips promise E "with any modifier" passes. */
	@Test
	public void shiftEPassesThrough() throws Exception
	{
		arm(1_120);
		final KeyEvent e = press(KeyEvent.VK_E, KeyEvent.SHIFT_DOWN_MASK);
		keys.keyPressed(e);
		Assert.assertFalse(e.isConsumed());
	}

	// -- which box is open --------------------------------------------------

	/** A chatbox showing {@code title} as the question and {@code typed} as
	 *  the input line (the game draws the input with a trailing "*"). */
	private static net.runelite.api.Client chatbox(String title, String typed)
	{
		final java.util.Map<Integer, String> text = new java.util.HashMap<>();
		text.put(net.runelite.api.gameval.InterfaceID.Chatbox.MES_TEXT, title);
		text.put(net.runelite.api.gameval.InterfaceID.Chatbox.MES_TEXT2, typed + "*");
		return (net.runelite.api.Client) java.lang.reflect.Proxy.newProxyInstance(
			net.runelite.api.Client.class.getClassLoader(), new Class<?>[]{net.runelite.api.Client.class},
			(proxy, method, args) ->
			{
				if ("getWidget".equals(method.getName()) && args != null && args.length == 1
					&& args[0] instanceof Integer && text.containsKey(args[0]))
				{
					final String t = text.get(args[0]);
					return java.lang.reflect.Proxy.newProxyInstance(
						net.runelite.api.widgets.Widget.class.getClassLoader(),
						new Class<?>[]{net.runelite.api.widgets.Widget.class},
						(p2, m2, a2) ->
						{
							if ("getText".equals(m2.getName()))
							{
								return t;
							}
							final Class<?> r = m2.getReturnType();
							if (r == boolean.class)
							{
								return false;
							}
							return r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
						});
				}
				final Class<?> r = method.getReturnType();
				if (r == boolean.class)
				{
					return false;
				}
				return r.isPrimitive() ? (r == long.class ? (Object) 0L : (Object) 0) : null;
			});
	}

	@Test
	public void theExchangesOwnBoxesAreRecognised()
	{
		Assert.assertEquals(GeOfferPriceOverlay.NumberBox.PRICE,
			GeOfferPriceOverlay.openNumberBox(chatbox("Set a price for each item:", "")));
		Assert.assertEquals(GeOfferPriceOverlay.NumberBox.QUANTITY,
			GeOfferPriceOverlay.openNumberBox(chatbox("How many do you wish to buy?", "12")));
	}

	/**
	 * A private message is never mistaken for the price box — the bug this
	 * test is named for: typing "what price?" to a friend with the offer
	 * screen open armed E, and the next E replaced the message with a number.
	 */
	@Test
	public void aPrivateMessageIsNeverAPriceBox()
	{
		Assert.assertEquals(GeOfferPriceOverlay.NumberBox.NONE,
			GeOfferPriceOverlay.openNumberBox(chatbox("Enter message to send to Bob:", "what price do you want")));
		Assert.assertEquals(GeOfferPriceOverlay.NumberBox.NONE,
			GeOfferPriceOverlay.openNumberBox(chatbox("Enter message to send to Bob:", "how many have you got")));
		Assert.assertEquals("a friend's name is not the question either", GeOfferPriceOverlay.NumberBox.NONE,
			GeOfferPriceOverlay.openNumberBox(chatbox("Enter message to send to Iceprice:", "")));
	}

	/** Armed, the typed 'e' is swallowed so it does not land in the box. */
	@Test
	public void anArmedTypedEIsSwallowed() throws Exception
	{
		arm(1_120);
		final KeyEvent t = typed('e');
		keys.keyTyped(t);
		Assert.assertTrue(t.isConsumed());
	}
}
