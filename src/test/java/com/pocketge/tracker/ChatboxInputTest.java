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

	private static final Pattern WRITE = Pattern.compile("setVarcStrValue\\(\\s*(?:[\\w.]*\\.)?VarClientID\\.MESLAYERINPUT");

	/** Exactly one write to the chatbox input in the whole plugin, and it is
	 *  the one the key listener calls. Reading it (is the search still
	 *  empty?) is not a write. */
	@Test
	public void oneWriteAndItIsTheKeys() throws Exception
	{
		int writes = 0;
		int setters = 0;
		for (String cls : new String[]{"PocketGeTrackerPlugin", "GeOfferPriceOverlay", "AdvisorPanel", "MainPanel"})
		{
			final Matcher m = WRITE.matcher(code(cls));
			while (m.find())
			{
				writes++;
			}
			final Matcher any = Pattern.compile("setVarcStrValue\\(").matcher(code(cls));
			while (any.find())
			{
				setters++;
			}
		}
		Assert.assertEquals("one write to MESLAYERINPUT", 1, writes);
		Assert.assertEquals("and no other chatbox string written", 1, setters);
		final String plugin = code("PocketGeTrackerPlugin");
		final int fill = plugin.indexOf("private void fillGePrompt(");
		final Matcher w = WRITE.matcher(plugin);
		Assert.assertTrue(w.find());
		final int write = w.start();
		final int next = plugin.indexOf("\n\tprivate ", fill + 1);
		Assert.assertTrue("the write is inside fillGePrompt", fill > 0 && write > fill && write < next);
		final String listener = plugin.substring(plugin.indexOf("fillKeyListener = "), plugin.indexOf("fillKeyListener = ") + 3000);
		Assert.assertTrue("and fillGePrompt is called from the key listener", listener.contains("fillGePrompt("));
	}

	/** The search pick is the game's: the plugin never runs a script or
	 *  types anything there. It fills the search's own result row, which
	 *  the game picks on the player's click or Enter, and the fill key is
	 *  handed on to the game as Enter. */
	@Test
	public void theSearchPickIsTheGamesOwn() throws Exception
	{
		final String plugin = code("PocketGeTrackerPlugin");
		Assert.assertFalse("no select script run by the plugin", plugin.contains("runScript(MESLAYER_MODE14_SELECT"));
		final int fill = plugin.indexOf("private void fillSearchRow(");
		final int next = plugin.indexOf("\n\tprivate ", fill + 1);
		final String body = plugin.substring(fill, next);
		Assert.assertTrue(body.contains("setOnOpListener(MESLAYER_MODE14_SELECT"));
		Assert.assertTrue(body.contains("setOnKeyListener(MESLAYER_MODE14_SELECT"));
		Assert.assertFalse("types nothing", body.contains("setVarcStrValue"));
		final String listener = plugin.substring(plugin.indexOf("fillKeyListener = "), plugin.indexOf("fillKeyListener = ") + 6000);
		Assert.assertTrue("the key goes on as Enter", listener.contains("e.setKeyCode(java.awt.event.KeyEvent.VK_ENTER)"));
	}

	/** With the row filled and nothing typed, E reaches the game as Enter
	 *  and its 'e' never lands; a letter typed first makes it a letter. */
	@Test
	public void eGoesOnAsEnterInAnUntouchedSearch() throws Exception
	{
		final Field c = PocketGeTrackerPlugin.class.getDeclaredField("client");
		c.setAccessible(true);
		c.set(plugin, chatbox("What would you like to buy?", ""));
		final Field row = PocketGeTrackerPlugin.class.getDeclaredField("searchRowItem");
		row.setAccessible(true);
		row.set(plugin, 1737);
		final Field a = GeOfferPriceOverlay.class.getDeclaredField("armed");
		a.setAccessible(true);
		final java.lang.reflect.Method item = GeOfferPriceOverlay.Armed.class.getDeclaredMethod("item", int.class);
		item.setAccessible(true);
		a.set(overlay, item.invoke(null, 1737));

		final KeyEvent e = real(KeyEvent.KEY_PRESSED, KeyEvent.VK_E, 'e');
		keys.keyPressed(e);
		Assert.assertFalse("handed on, not taken", e.isConsumed());
		Assert.assertEquals(KeyEvent.VK_ENTER, e.getKeyCode());
		final KeyEvent t = typed('e');
		keys.keyTyped(t);
		Assert.assertTrue("its 'e' never lands", t.isConsumed());
		final KeyEvent r = real(KeyEvent.KEY_RELEASED, KeyEvent.VK_E, 'e');
		keys.keyReleased(r);
		Assert.assertEquals("and lets go as Enter", KeyEvent.VK_ENTER, r.getKeyCode());
		Assert.assertEquals("the row is used up", 0, row.get(plugin));
		final KeyEvent next = real(KeyEvent.KEY_PRESSED, KeyEvent.VK_A, 'a');
		keys.keyPressed(next);
		Assert.assertTrue("typing straight on after a pick does not reach the chat", next.isConsumed());

		/* A letter first: E is a letter of the search. */
		final Field until = keys.getClass().getDeclaredField("afterPickUntil");
		until.setAccessible(true);
		until.set(keys, 0L);
		row.set(plugin, 1737);
		final KeyEvent b = real(KeyEvent.KEY_PRESSED, KeyEvent.VK_B, 'b');
		keys.keyPressed(b);
		Assert.assertFalse(b.isConsumed());
		Assert.assertEquals("typing gives the row back to the search", 0, row.get(plugin));
		final KeyEvent e2 = real(KeyEvent.KEY_PRESSED, KeyEvent.VK_E, 'e');
		keys.keyPressed(e2);
		Assert.assertEquals(KeyEvent.VK_E, e2.getKeyCode());
		Assert.assertFalse(e2.isConsumed());
	}

	/** Armed over a price box, the real E is taken, typed char and all. */
	@Test
	public void anArmedEIsTaken() throws Exception
	{
		final Field c = PocketGeTrackerPlugin.class.getDeclaredField("clientThread");
		c.setAccessible(true);
		c.set(plugin, null); // fillGePrompt's hop to the client thread is not under test
		arm(1_120);
		final KeyEvent e = real(KeyEvent.KEY_PRESSED, KeyEvent.VK_E, 'e');
		try
		{
			keys.keyPressed(e);
		}
		catch (NullPointerException expected)
		{
			// no client thread here: the press got as far as filling
		}
		Assert.assertTrue(e.isConsumed());
		final KeyEvent t = typed('e');
		keys.keyTyped(t);
		Assert.assertTrue(t.isConsumed());
	}

	/** Enter, Escape and Backspace are the boxes' own: as the fill key they
	 *  count as no key. */
	@Test
	public void theBoxesOwnKeysAreRefused()
	{
		Assert.assertTrue(PocketGeTrackerPlugin.usedByThePrompts(new net.runelite.client.config.Keybind(KeyEvent.VK_ENTER, 0)));
		Assert.assertTrue(PocketGeTrackerPlugin.usedByThePrompts(new net.runelite.client.config.Keybind(KeyEvent.VK_ESCAPE, 0)));
		Assert.assertTrue(PocketGeTrackerPlugin.usedByThePrompts(new net.runelite.client.config.Keybind(KeyEvent.VK_BACK_SPACE, 0)));
		Assert.assertFalse(PocketGeTrackerPlugin.usedByThePrompts(new net.runelite.client.config.Keybind(KeyEvent.VK_E, 0)));
		Assert.assertFalse("with a modifier it is not the box's",
			PocketGeTrackerPlugin.usedByThePrompts(new net.runelite.client.config.Keybind(KeyEvent.VK_ENTER, KeyEvent.CTRL_DOWN_MASK)));
	}

	/** The key is the setting's: another key works the same, and E is then
	 *  just E. */
	@Test
	public void theKeyIsTheSettings() throws Exception
	{
		final Field f = PocketGeTrackerPlugin.class.getDeclaredField("fillKey");
		f.setAccessible(true);
		f.set(plugin, new net.runelite.client.config.Keybind(KeyEvent.VK_F, 0));
		arm(0);
		final KeyEvent e = press(KeyEvent.VK_E, 0);
		keys.keyPressed(e);
		Assert.assertFalse(e.isConsumed());
		final KeyEvent t = typed('e');
		keys.keyTyped(t);
		Assert.assertFalse(t.isConsumed());
		f.set(plugin, net.runelite.client.config.Keybind.NOT_SET);
		arm(1_120);
		final KeyEvent unset = press(KeyEvent.VK_E, 0);
		keys.keyPressed(unset);
		Assert.assertFalse("no key set: nothing is taken", unset.isConsumed());
		Assert.assertFalse(PocketGeTrackerPlugin.typedCharOf(net.runelite.client.config.Keybind.NOT_SET, typed('e')));
		Assert.assertTrue(PocketGeTrackerPlugin.typedCharOf(new net.runelite.client.config.Keybind(KeyEvent.VK_F, 0), typed('f')));
		Assert.assertFalse("a key with a modifier has no typed character of its own",
			PocketGeTrackerPlugin.typedCharOf(new net.runelite.client.config.Keybind(KeyEvent.VK_F, KeyEvent.CTRL_DOWN_MASK), typed('f')));
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

	/** A key event as AWT delivers it, with its extended key code set —
	 *  which RuneLite's Keybind matches on, and a synthetic event lacks. */
	private static KeyEvent real(int id, int code, char c) throws Exception
	{
		final KeyEvent e = new KeyEvent(new JPanel(), id, 0L, 0, code, c);
		final Field f = sun.misc.Unsafe.class.getDeclaredField("theUnsafe");
		f.setAccessible(true);
		final sun.misc.Unsafe unsafe = (sun.misc.Unsafe) f.get(null);
		unsafe.putLong(e, unsafe.objectFieldOffset(KeyEvent.class.getDeclaredField("extendedKeyCode")), code);
		return e;
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
