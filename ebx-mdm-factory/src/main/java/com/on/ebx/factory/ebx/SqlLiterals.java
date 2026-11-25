package com.on.ebx.factory.ebx;

import java.util.regex.Pattern;

/**
 * Lot F1b (2026-10-19): the EBX SQL literal of a key value, from the Java type of the field. The coverage read of a simulation embedded every value as a quoted string
 * ({@code c."birthDate" IN ('Tue Jan 20 00:00:00 CET 2026')}): wrong for any field that is not text - live, the date key of a plan made Calcite's generated code fail with
 * "Invalid DATE value". The value is the field's XS string ({@code SchemaNode.formatToXsString}: {@code 2026-01-20}, {@code 2026-01-20T10:30:00.000}, {@code 12.50}, {@code true}), never
 * {@code Date.toString}. Pure.
 * <ul>
 * <li>a date field ({@code java.util.Date}, XS string {@code yyyy-MM-dd}) -&gt; {@code DATE 'yyyy-MM-dd'};</li>
 * <li>a dateTime field (XS string with a {@code T}) -&gt; {@code TIMESTAMP 'yyyy-MM-dd HH:mm:ss'} (the fraction and the zone are dropped);</li>
 * <li>a time field (XS string {@code HH:mm:ss}) -&gt; {@code TIME 'HH:mm:ss'};</li>
 * <li>Integer, Long, Short, Byte, BigInteger, BigDecimal, Double, Float -&gt; the bare number;</li>
 * <li>Boolean -&gt; {@code TRUE} / {@code FALSE};</li>
 * <li>anything else, and any value that does not read as its type -&gt; the quoted string, a quote doubled.</li>
 * </ul>
 */
final class SqlLiterals
{
	private static final Pattern DATE = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})(Z|[+-]\\d{2}:?\\d{2})?");
	private static final Pattern DATE_TIME = Pattern.compile("(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}:\\d{2})(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?");
	private static final Pattern TIME = Pattern.compile("(\\d{2}:\\d{2}:\\d{2})(\\.\\d+)?(Z|[+-]\\d{2}:?\\d{2})?");
	private static final Pattern NUMBER = Pattern.compile("-?\\d+(\\.\\d+)?");

	private SqlLiterals()
	{
	}

	/**
	 * @param pJavaClass {@code SchemaNode.getOccurrencesJavaClass()} of the field; null when unknown (the value is then a string)
	 * @param pXsValue the value as the field's XS string; null gives {@code NULL}
	 */
	static String literal(final Class<?> pJavaClass, final String pXsValue)
	{
		if (pXsValue == null)
		{
			return "NULL";
		}
		final String value = pXsValue.trim();
		if (pJavaClass != null)
		{
			if (java.util.Date.class.isAssignableFrom(pJavaClass))
			{
				final java.util.regex.Matcher dateTime = DATE_TIME.matcher(value);
				if (dateTime.matches())
				{
					return "TIMESTAMP '" + dateTime.group(1) + " " + dateTime.group(2) + "'";
				}
				final java.util.regex.Matcher date = DATE.matcher(value);
				if (date.matches())
				{
					return "DATE '" + date.group(1) + "'";
				}
				final java.util.regex.Matcher time = TIME.matcher(value);
				if (time.matches())
				{
					return "TIME '" + time.group(1) + "'";
				}
			}
			else if (Number.class.isAssignableFrom(pJavaClass) || pJavaClass.isPrimitive() && pJavaClass != boolean.class && pJavaClass != char.class)
			{
				if (NUMBER.matcher(value).matches())
				{
					return value;
				}
			}
			else if (pJavaClass == Boolean.class || pJavaClass == boolean.class)
			{
				if ("true".equalsIgnoreCase(value))
				{
					return "TRUE";
				}
				if ("false".equalsIgnoreCase(value))
				{
					return "FALSE";
				}
			}
		}
		return MatchPlanSimulationRunner.sqlStringLiteral(pXsValue);
	}
}
