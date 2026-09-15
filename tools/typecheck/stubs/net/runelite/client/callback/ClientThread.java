package net.runelite.client.callback;

import java.util.function.BooleanSupplier;

/**
 * Stub of RuneLite's ClientThread. Both the Runnable and the BooleanSupplier overloads of
 * invoke/invokeLater are kept, because which one a lambda binds to is exactly the kind of
 * thing that differs between a stub with only one of them and the real jar.
 *
 * There is no game thread here, so work runs inline on the caller's thread — a task that
 * asks to be retried (returns false) is simply retried until it returns true would risk
 * spinning forever, so it is run once and dropped.
 */
public class ClientThread
{
	public void invoke(Runnable r)
	{
		r.run();
	}

	public void invoke(BooleanSupplier r)
	{
		r.getAsBoolean();
	}

	public void invokeLater(Runnable r)
	{
		r.run();
	}

	public void invokeLater(BooleanSupplier r)
	{
		r.getAsBoolean();
	}

	public void invokeAtTickEnd(Runnable r)
	{
		r.run();
	}
}
