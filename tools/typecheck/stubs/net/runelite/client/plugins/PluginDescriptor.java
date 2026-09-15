package net.runelite.client.plugins;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Stub of RuneLite's @PluginDescriptor. Element names, types and defaults are copied
 * verbatim from upstream, so a descriptor that compiles here compiles against the jar.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@Documented
public @interface PluginDescriptor
{
	String name();

	/**
	 * Internal name used in the config.
	 */
	String configName() default "";

	/**
	 * A short, one-line summary of the plugin.
	 */
	String description() default "";

	/**
	 * A list of plugin keywords, used (together with the name) when searching for plugins.
	 */
	String[] tags() default {};

	/**
	 * A list of plugin names that are mutually exclusive with this plugin.
	 */
	String[] conflicts() default {};

	/**
	 * If this plugin should be defaulted to on.
	 */
	boolean enabledByDefault() default true;

	/**
	 * Whether or not plugin is hidden from configuration panel
	 */
	boolean hidden() default false;

	boolean developerPlugin() default false;

	boolean loadInSafeMode() default true;
}
