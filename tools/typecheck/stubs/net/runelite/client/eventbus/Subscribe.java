package net.runelite.client.eventbus;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stub of RuneLite's @Subscribe. Marks a method as an event subscriber.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Subscribe
{
	/**
	 * Priority relative to other event subscribers. Higher priorities run first.
	 */
	float priority() default 0;
}
