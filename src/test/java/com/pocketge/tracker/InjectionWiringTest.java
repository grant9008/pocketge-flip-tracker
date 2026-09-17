package com.pocketge.tracker;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import javax.inject.Inject;
import org.junit.Assert;
import org.junit.Test;

/**
 * Everything Guice has to build, it can actually build.
 *
 * This exists because of a bug that took the whole plugin off the sidebar and
 * that nothing else here could have caught. A constant was added to
 * BankHighlightOverlay directly above its constructor:
 *
 *     &#64;Inject
 *     private static final int GE_INVENTORY_GROUP = 467;    // &lt;- new
 *     private BankHighlightOverlay() { ... }
 *
 * The annotation belonged to the constructor. The new field silently took it,
 * and both halves of that are fatal: Guice refuses an &#64;Inject on a final
 * field, and with the constructor no longer annotated it cannot build a class
 * whose only constructor is private. The injector dies at startup with a
 * CreationException, RuneLite drops the plugin, and the panel is simply gone.
 *
 * It compiles. It type-checks. Every unit test passes. Java is perfectly happy
 * to put an annotation on the wrong declaration, and the offline type-check
 * cannot run Guice at all — the one place the mistake shows up is a real
 * client, which is the slowest and most expensive place to find anything.
 *
 * So the rules Guice enforces at startup are enforced here instead, walking
 * the same graph Guice walks: start at the plugin, follow every &#64;Inject
 * into our own package, and check what it finds.
 */
public class InjectionWiringTest
{
	private static final String PKG = "com.pocketge.tracker.";

	/**
	 * The classes Guice is actually asked to construct — the transitive
	 * closure of our own types reachable through injection points, which is
	 * exactly the set whose wiring can break startup. A class nothing injects
	 * is not Guice's problem and is not checked.
	 */
	private static Set<Class<?>> injected()
	{
		final Set<Class<?>> seen = new LinkedHashSet<>();
		final Deque<Class<?>> queue = new ArrayDeque<>();
		queue.add(PocketGeTrackerPlugin.class);
		while (!queue.isEmpty())
		{
			final Class<?> c = queue.poll();
			if (c.isInterface() || c.isPrimitive() || !seen.add(c))
			{
				continue;
			}
			for (Field f : c.getDeclaredFields())
			{
				if (f.isAnnotationPresent(Inject.class) && ours(f.getType()))
				{
					queue.add(f.getType());
				}
			}
			for (Constructor<?> k : c.getDeclaredConstructors())
			{
				if (!k.isAnnotationPresent(Inject.class))
				{
					continue;
				}
				for (Class<?> p : k.getParameterTypes())
				{
					if (ours(p))
					{
						queue.add(p);
					}
				}
			}
		}
		return seen;
	}

	private static boolean ours(Class<?> c)
	{
		return c.getName().startsWith(PKG);
	}

	/**
	 * Guice: "Injected field cannot be final", and a static field is only
	 * injected on an explicit requestStaticInjection, which nothing here
	 * does. Either way the annotation is not doing what its author meant.
	 *
	 * This is the half of the bug that fires loudest — a CreationException
	 * naming the field — and the half that is hardest to read as a typo,
	 * because the field it names is not the declaration anyone touched.
	 */
	@Test
	public void noInjectedFieldIsStaticOrFinal()
	{
		final List<String> bad = new ArrayList<>();
		for (Class<?> c : injected())
		{
			for (Field f : c.getDeclaredFields())
			{
				if (!f.isAnnotationPresent(Inject.class))
				{
					continue;
				}
				if (Modifier.isStatic(f.getModifiers()))
				{
					bad.add(c.getSimpleName() + "." + f.getName() + " is static"
						+ " — did it steal the @Inject from the declaration below it?");
				}
				if (Modifier.isFinal(f.getModifiers()))
				{
					bad.add(c.getSimpleName() + "." + f.getName() + " is final"
						+ " — Guice refuses this and the injector dies at startup");
				}
			}
		}
		Assert.assertEquals(String.join("\n", bad), List.of(), bad);
	}

	/**
	 * And the quiet half: a class Guice must build needs either one &#64;Inject
	 * constructor or a zero-argument constructor it is allowed to call. Lose
	 * the annotation off a private constructor and there is nothing left —
	 * which is how the overlay stopped being constructible without a single
	 * line of its own code changing.
	 */
	@Test
	public void everyInjectedClassIsConstructible()
	{
		final List<String> bad = new ArrayList<>();
		for (Class<?> c : injected())
		{
			final Constructor<?>[] ctors = c.getDeclaredConstructors();
			int annotated = 0;
			boolean usableNoArg = false;
			for (Constructor<?> k : ctors)
			{
				if (k.isAnnotationPresent(Inject.class))
				{
					annotated++;
				}
				if (k.getParameterCount() == 0 && !Modifier.isPrivate(k.getModifiers()))
				{
					usableNoArg = true;
				}
			}
			if (annotated > 1)
			{
				bad.add(c.getSimpleName() + " has " + annotated + " @Inject constructors"
					+ " — Guice allows exactly one");
			}
			else if (annotated == 0 && !usableNoArg)
			{
				bad.add(c.getSimpleName() + " has no @Inject constructor and no callable"
					+ " zero-arg one — Guice cannot build it");
			}
		}
		Assert.assertEquals(String.join("\n", bad), List.of(), bad);
	}

	/**
	 * The walk has to actually reach things, or the two tests above pass by
	 * checking nothing — which is the failure mode of every test that scans
	 * for problems rather than asserting a value.
	 */
	@Test
	public void theWalkReachesTheOverlays()
	{
		final Set<Class<?>> seen = injected();
		Assert.assertTrue("the overlay that broke is in the set",
			seen.contains(BankHighlightOverlay.class));
		Assert.assertTrue("and the other two overlays",
			seen.contains(GeOfferGridOverlay.class) && seen.contains(GeOfferPriceOverlay.class));
		Assert.assertTrue("and the market client", seen.contains(MarketClient.class));
		Assert.assertTrue("and the bank legend, which is injected into nothing else",
			seen.contains(BankLegendOverlay.class));
	}

	/**
	 * The stub's @Inject must be RUNTIME or none of the above reads anything.
	 * It was CLASS-retention once, which is silent: the scans find no
	 * annotations, report no problems, and pass.
	 */
	@Test
	public void theAnnotationIsVisibleToReflection()
	{
		int found = 0;
		for (Field f : PocketGeTrackerPlugin.class.getDeclaredFields())
		{
			if (f.isAnnotationPresent(Inject.class))
			{
				found++;
			}
		}
		Assert.assertTrue("@Inject is not visible at runtime — these tests are inert", found > 5);
	}
}
