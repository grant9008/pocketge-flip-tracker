package org.slf4j;
public final class LoggerFactory
{
	public static Logger getLogger(Class<?> c)
	{
		return new Logger()
		{
			public void debug(String m, Object... a) { }
			public void warn(String m, Object... a) { }
			public void info(String m, Object... a) { }
		};
	}
}
