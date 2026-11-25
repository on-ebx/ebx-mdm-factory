package com.on.ebx.factory.deducedscan;

import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Audit F-17: the values a language model puts into a deduced scan's operation inputs reach the external system before anyone confirms them
 * ({@code connectors_call} on the connection, read only). The operation and the input NAMES were already checked ({@link OperationCatalog#firstViolation}); this
 * checks each VALUE, before the first call, with rules that never need the model's good faith: a bounded length, no control character, no URL, scheme or host
 * unless the input is itself a URL parameter (and then only {@code http(s)} without user information), and a number where the connector declares the input numeric.
 * Pure: no EBX import, unit-tested directly.
 * <p>
 * A listing input is a page size, a cursor, a date, a wildcard or a code: none of those looks like a host name, so a value that does is refused rather than guessed
 * at (the one false refusal this rule can make is a value such as {@code file.csv}; the model is told why and can answer again).
 */
public final class InputValueRules
{
	/** Longest value a deduced scan sends. A listing input is a token, not a document. */
	public static final int MAX_LENGTH = 200;

	private static final Pattern NUMBER = Pattern.compile("[+-]?\\d{1,18}(\\.\\d{1,18})?");
	private static final Pattern SCHEME = Pattern.compile("(?i)^[a-z][a-z0-9+.-]{1,15}:(//|\\\\\\\\).*");
	private static final Pattern KNOWN_SCHEME = Pattern.compile("(?i)^(https?|ftps?|sftp|file|ldaps?|jar|data|javascript|gopher|dict|ws|wss|jdbc|smb|nfs|tftp|telnet|ssh):.*");
	private static final Pattern HOST = Pattern.compile("(?i)^(?:[a-z0-9_-]+\\.)+[a-z]{2,}(?::\\d{1,5})?(?:[/?#].*)?$");
	private static final Pattern IPV4 = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}(?::\\d{1,5})?(?:[/?#].*)?$");
	private static final Pattern LOCALHOST = Pattern.compile("(?i)^localhost(?::\\d{1,5})?(?:[/?#].*)?$");
	private static final Pattern IPV6 = Pattern.compile("^\\[[0-9a-fA-F:.]+\\](?::\\d{1,5})?(?:[/?#].*)?$");

	private InputValueRules()
	{
	}

	/**
	 * Why {@code pValue} may not be sent as input {@code pName}, or null when it may. A null or blank value is never a violation here (a required input left blank is
	 * {@link OperationCatalog#missingRequiredInputs}'s business).
	 *
	 * @param pDeclaredType the type the connector declares for the input ({@code integer}, {@code number}, {@code url}...), or null when it does not say
	 */
	public static String firstViolation(final String pName, final String pValue, final String pDeclaredType)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return null;
		}
		if (pValue.length() > InputValueRules.MAX_LENGTH)
		{
			return "the value is " + pValue.length() + " characters long; at most " + InputValueRules.MAX_LENGTH + " are sent.";
		}
		for (int i = 0; i < pValue.length(); i++)
		{
			if (Character.isISOControl(pValue.charAt(i)))
			{
				return "the value contains a control character (line break, tab...).";
			}
		}
		final String value = pValue.trim();
		if (InputValueRules.isNumeric(pDeclaredType))
		{
			return InputValueRules.NUMBER.matcher(value).matches() ? null : "the connector declares this input as a number, and the value is not one.";
		}
		if (InputValueRules.isUrlInput(pName, pDeclaredType))
		{
			return InputValueRules.urlViolation(value);
		}
		if (value.startsWith("//") || value.startsWith("\\\\") || InputValueRules.SCHEME.matcher(value).matches() || InputValueRules.KNOWN_SCHEME.matcher(value).matches())
		{
			return "the value is a URL or carries a scheme (http:, file:...), and this input is not a URL parameter.";
		}
		if (InputValueRules.HOST.matcher(value).matches() || InputValueRules.IPV4.matcher(value).matches() || InputValueRules.LOCALHOST.matcher(value).matches()
			|| InputValueRules.IPV6.matcher(value).matches())
		{
			return "the value is a host name or an address, and this input is not a URL parameter.";
		}
		return null;
	}

	/** Whether the connector declares the input numeric. */
	static boolean isNumeric(final String pDeclaredType)
	{
		if (pDeclaredType == null)
		{
			return false;
		}
		final String type = pDeclaredType.trim().toLowerCase(Locale.ROOT);
		return type.equals("integer") || type.equals("int") || type.equals("long") || type.equals("number") || type.equals("decimal") || type.equals("double")
			|| type.equals("float") || type.equals("numeric") || type.equals("short") || type.equals("bigdecimal") || type.equals("biginteger");
	}

	/** Whether the input is itself a URL: declared so, or named so. */
	static boolean isUrlInput(final String pName, final String pDeclaredType)
	{
		if (pDeclaredType != null)
		{
			final String type = pDeclaredType.trim().toLowerCase(Locale.ROOT);
			if (type.equals("url") || type.equals("uri") || type.equals("href"))
			{
				return true;
			}
		}
		if (pName == null)
		{
			return false;
		}
		final String name = pName.toLowerCase(Locale.ROOT);
		return name.endsWith("url") || name.endsWith("uri") || name.endsWith("href") || name.equals("link") || name.equals("endpoint") || name.equals("webhook");
	}

	private static String urlViolation(final String pValue)
	{
		final String lower = pValue.toLowerCase(Locale.ROOT);
		if (!lower.startsWith("http://") && !lower.startsWith("https://"))
		{
			return "a URL input takes an http:// or https:// address only.";
		}
		final String afterScheme = pValue.substring(pValue.indexOf("//") + 2);
		final int end = indexOfAny(afterScheme, "/?#");
		final String authority = end < 0 ? afterScheme : afterScheme.substring(0, end);
		if (authority.isEmpty() || authority.indexOf('@') >= 0 || authority.indexOf('\\') >= 0)
		{
			return "a URL input takes an address with a host and no user information.";
		}
		return null;
	}

	private static int indexOfAny(final String pText, final String pChars)
	{
		for (int i = 0; i < pText.length(); i++)
		{
			if (pChars.indexOf(pText.charAt(i)) >= 0)
			{
				return i;
			}
		}
		return -1;
	}
}
