package net.runelite.client.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stub of RuneLite's @ConfigItem. */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface ConfigItem
{
	String keyName();

	String name();

	String description();

	int position() default 0;

	boolean hidden() default false;

	String unhide() default "";

	String unhideValue() default "";

	String hide() default "";

	String hideValue() default "";

	boolean warning() default false;

	boolean secret() default false;

	String section() default "";

	boolean disabled() default false;
}
