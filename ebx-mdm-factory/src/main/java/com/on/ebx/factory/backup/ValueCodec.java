package com.on.ebx.factory.backup;

import java.math.BigDecimal;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Field values of an archive row, as JSON and back (audit F-30). The Java type a field takes at restore comes from the schema (the node's occurrence
 * class), so the JSON stays plain: text, boolean, number (a decimal as its exact text, never a double), a date as the local wall time
 * {@code yyyy-MM-dd'T'HH:mm:ss.SSS}, a multi-valued field as an array. A foreign key is its formatted primary key, a text. Pure.
 */
public final class ValueCodec
{
	private ValueCodec()
	{
	}

	private static SimpleDateFormat dates()
	{
		return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS");
	}

	/** The text a value is written as in JSON, or null for a null; a number is written as its plain decimal text. */
	public static Object toJson(final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pValue instanceof Date)
		{
			return ValueCodec.dates().format((Date) pValue);
		}
		if (pValue instanceof BigDecimal)
		{
			return ((BigDecimal) pValue).toPlainString();
		}
		if (pValue instanceof Number)
		{
			return String.valueOf(pValue);
		}
		if (pValue instanceof Boolean)
		{
			return pValue;
		}
		return String.valueOf(pValue);
	}

	/**
	 * The value a JSON node stands for as {@code pJavaClass} (String, Integer, BigDecimal, Boolean, Date, or another number class); null for a JSON null or a
	 * missing node. A value that cannot be read as that class throws {@link IllegalArgumentException} naming it.
	 */
	public static Object fromJson(final JsonNode pNode, final Class<?> pJavaClass)
	{
		if (pNode == null || pNode.isNull() || pNode.isMissingNode())
		{
			return null;
		}
		final String text = pNode.isTextual() ? pNode.asText() : pNode.toString();
		try
		{
			if (Boolean.class.equals(pJavaClass))
			{
				return Boolean.valueOf(pNode.isBoolean() ? pNode.asBoolean() : "true".equalsIgnoreCase(text));
			}
			if (Integer.class.equals(pJavaClass))
			{
				return Integer.valueOf(new BigDecimal(text).intValueExact());
			}
			if (Long.class.equals(pJavaClass))
			{
				return Long.valueOf(new BigDecimal(text).longValueExact());
			}
			if (BigDecimal.class.equals(pJavaClass))
			{
				return new BigDecimal(text);
			}
			if (Date.class.isAssignableFrom(pJavaClass))
			{
				return ValueCodec.dates().parse(text);
			}
		}
		catch (final NumberFormatException | ArithmeticException | ParseException ex)
		{
			throw new IllegalArgumentException("value '" + text + "' is not a valid " + pJavaClass.getSimpleName());
		}
		return text;
	}

	/** The values of an array node (a multi-valued field), each read as {@code pJavaClass}; a non-array node is one value, null/missing is an empty list. */
	public static List<Object> listFromJson(final JsonNode pNode, final Class<?> pJavaClass)
	{
		final List<Object> out = new ArrayList<Object>();
		if (pNode == null || pNode.isNull() || pNode.isMissingNode())
		{
			return out;
		}
		if (pNode.isArray())
		{
			for (final JsonNode item : pNode)
			{
				out.add(ValueCodec.fromJson(item, pJavaClass));
			}
		}
		else
		{
			out.add(ValueCodec.fromJson(pNode, pJavaClass));
		}
		return out;
	}
}
