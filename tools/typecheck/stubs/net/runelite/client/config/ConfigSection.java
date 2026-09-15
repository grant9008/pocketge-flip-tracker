package net.runelite.client.config;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/** Stub of RuneLite's @ConfigSection. */
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.FIELD, ElementType.METHOD})
public @interface ConfigSection
{
	String name() default "";

	String description() default "";

	int position() default 0;

	boolean closedByDefault() default false;
}
