package com.on.ebx.factory.landing;

/**
 * The sentence to keep or show for an exception (audit F-23): its message, or - when it has none - its class name. {@code
 * String.valueOf(ex.getMessage())} turns a {@code NullPointerException} into the text "null", and an empty message into nothing:
 * a step or a screen that stores that tells the operator nothing and the developer less. A cause with a message is named too, since a
 * wrapper without one is usually the interesting case. Pure: no EBX class.
 */
public final class ErrorText
{
	private ErrorText()
	{
	}

	public static String of(final Throwable pThrowable)
	{
		if (pThrowable == null)
		{
			return "unknown error";
		}
		final String message = pThrowable.getMessage();
		if (message != null && !message.trim().isEmpty())
		{
			return message;
		}
		final Throwable cause = pThrowable.getCause();
		final String causeMessage = cause == null || cause == pThrowable ? null : cause.getMessage();
		final String name = pThrowable.getClass().getName();
		return causeMessage == null || causeMessage.trim().isEmpty() ? name : name + " (caused by " + cause.getClass().getName() + ": " + causeMessage + ")";
	}
}
