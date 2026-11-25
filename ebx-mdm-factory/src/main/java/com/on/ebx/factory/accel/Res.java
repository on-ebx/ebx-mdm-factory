package com.on.ebx.factory.accel;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** Class-path reading and JSON helpers of the catalogue (package-private, pure). */
final class Res
{
	static final String ROOT = "com/on/ebx/factory/accel/";
	static final ObjectMapper MAPPER = new ObjectMapper();

	private Res()
	{
	}

	/** The text of a resource under {@code accel/}; fails with the path when absent. */
	static String text(final String pRelativePath)
	{
		final String full = Res.ROOT + pRelativePath;
		InputStream in = Res.class.getClassLoader().getResourceAsStream(full);
		if (in == null)
		{
			throw new IllegalStateException("Accelerator resource not found on the class path: " + full);
		}
		try
		{
			final ByteArrayOutputStream out = new ByteArrayOutputStream();
			final byte[] buffer = new byte[8192];
			int n;
			while ((n = in.read(buffer)) > 0)
			{
				out.write(buffer, 0, n);
			}
			String s = new String(out.toByteArray(), StandardCharsets.UTF_8);
			if (s.length() > 0 && s.charAt(0) == '﻿')
			{
				s = s.substring(1);
			}
			return s;
		}
		catch (final IOException e)
		{
			throw new IllegalStateException("Cannot read accelerator resource " + full + ": " + e.getMessage(), e);
		}
		finally
		{
			try
			{
				in.close();
			}
			catch (final IOException ignored)
			{
				// nothing to do
			}
		}
	}

	static boolean exists(final String pRelativePath)
	{
		return Res.class.getClassLoader().getResource(Res.ROOT + pRelativePath) != null;
	}

	static JsonNode json(final String pRelativePath)
	{
		final String text = Res.text(pRelativePath);
		try
		{
			return Res.MAPPER.readTree(text);
		}
		catch (final IOException e)
		{
			throw new IllegalStateException("Malformed JSON in accelerator file " + Res.ROOT + pRelativePath + ": " + e.getMessage(), e);
		}
	}

	static String str(final JsonNode pNode, final String pField)
	{
		final JsonNode v = pNode.get(pField);
		return v == null || v.isNull() ? null : v.asText();
	}

	static String required(final JsonNode pNode, final String pField, final String pWhere)
	{
		final String v = Res.str(pNode, pField);
		if (v == null || v.trim().isEmpty())
		{
			throw new IllegalStateException("Accelerator file " + pWhere + ": missing required field '" + pField + "'");
		}
		return v;
	}

	static List<String> strings(final JsonNode pNode)
	{
		final List<String> out = new ArrayList<String>();
		if (pNode != null && pNode.isArray())
		{
			for (final JsonNode item : pNode)
			{
				out.add(item.asText());
			}
		}
		return out;
	}

	/** {"en-US": "...", "fr-FR": "..."} to a map; an absent node gives an empty map. */
	static Map<String, String> localized(final JsonNode pNode)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pNode != null && pNode.isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> it = pNode.fields();
			while (it.hasNext())
			{
				final Map.Entry<String, JsonNode> e = it.next();
				out.put(e.getKey(), e.getValue().asText());
			}
		}
		return out;
	}

	/** Exact tag, then same language, then en-US, then the first entry, else "". */
	static String pick(final Map<String, String> pMap, final Locale pLocale)
	{
		if (pMap.isEmpty())
		{
			return "";
		}
		final Locale locale = pLocale == null ? Locale.US : pLocale;
		final String exact = pMap.get(locale.toLanguageTag());
		if (exact != null)
		{
			return exact;
		}
		for (final Map.Entry<String, String> e : pMap.entrySet())
		{
			if (e.getKey().toLowerCase(Locale.ROOT).startsWith(locale.getLanguage().toLowerCase(Locale.ROOT) + "-")
					|| e.getKey().equalsIgnoreCase(locale.getLanguage()))
			{
				return e.getValue();
			}
		}
		final String en = pMap.get("en-US");
		return en != null ? en : pMap.values().iterator().next();
	}
}
