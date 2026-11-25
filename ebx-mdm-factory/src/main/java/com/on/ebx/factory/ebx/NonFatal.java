package com.on.ebx.factory.ebx;

import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;

/**
 * Lot F1b (2026-10-19): what a phase that must never fail its caller does with a {@link Throwable}. Live, the simulation of the feedback phase died with an {@code ExceptionInInitializerError}
 * (Calcite generated code refusing a date literal) - an {@link Error}, which a {@code catch (RuntimeException)} lets through - and took the whole proposal with it. Everything except the
 * errors of the virtual machine itself ({@link VirtualMachineError}: out of memory, stack overflow, ...) and {@link ThreadDeath} is recorded and the phase goes on. Pure.
 */
final class NonFatal
{
	private NonFatal()
	{
	}

	/** Rethrows the errors that must never be swallowed; returns normally for anything else. */
	static void rethrowIfFatal(final Throwable pThrowable)
	{
		if (pThrowable instanceof VirtualMachineError)
		{
			throw (VirtualMachineError) pThrowable;
		}
		if (pThrowable instanceof ThreadDeath)
		{
			throw (ThreadDeath) pThrowable;
		}
	}

	/** {@link ErrorText#of} cut to {@code pMaxLength}: the message, or the class name with the cause's (an {@code ExceptionInInitializerError} has none of its own). */
	static String reason(final Throwable pThrowable, final int pMaxLength)
	{
		return TextLimits.truncate(ErrorText.of(pThrowable), pMaxLength);
	}
}
