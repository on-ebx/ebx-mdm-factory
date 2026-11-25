package com.on.ebx.factory.agent;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The allow-list of an agent ({@code AgentDefinition.allowedTools}, agentic framework lot 2026-10-30). Pure. An entry is one of:
 * <ul>
 * <li>{@code factory:<action>} - an action of the Factory itself ({@link #SUSPECT_DECIDE}, {@link #RECORD_CORRECT});</li>
 * <li>{@code <mcp server>/<tool>} or {@code <mcp server>/*} - a tool of an MCP server of the instance;</li>
 * <li>{@code connectors:<flow>} or {@code connectors:*} - a Connectors flow (mapping).</li>
 * </ul>
 * Matching is exact apart from the case of the server or flow family and a trailing {@code *} that stands for a whole server. An empty list allows nothing:
 * an agent whose definition lists no tool can only abstain or be refused, never act.
 */
public final class AgentTools
{
	/** Decide a Match and Merge suspect pair (accept = merge, reject = not the same). */
	public static final String SUSPECT_DECIDE = "factory:suspect.decide";
	/** Write a corrected value into one field of one record. */
	public static final String RECORD_CORRECT = "factory:record.correct";

	private final List<String> entries;

	private AgentTools(final List<String> pEntries)
	{
		this.entries = pEntries;
	}

	/** The allow-list of these stored values (blank and duplicate entries dropped, each trimmed). */
	public static AgentTools of(final Collection<?> pValues)
	{
		final List<String> out = new ArrayList<String>();
		if (pValues != null)
		{
			for (final Object value : pValues)
			{
				final String entry = AgentTools.normalise(value == null ? null : value.toString());
				if (entry != null && !out.contains(entry))
				{
					out.add(entry);
				}
			}
		}
		return new AgentTools(Collections.unmodifiableList(out));
	}

	/** The entries, normalised. */
	public List<String> entries()
	{
		return this.entries;
	}

	/** Whether {@code pTool} (in the same notation) may be used. */
	public boolean allows(final String pTool)
	{
		final String tool = AgentTools.normalise(pTool);
		if (tool == null || tool.endsWith("*"))
		{
			return false; // a decision names one tool, never a pattern
		}
		for (final String entry : this.entries)
		{
			if (entry.equals(tool))
			{
				return true;
			}
			if (entry.endsWith("/*") && tool.startsWith(entry.substring(0, entry.length() - 1)) && tool.indexOf('/') == entry.length() - 2)
			{
				return true;
			}
			if (entry.equals("connectors:*") && tool.startsWith("connectors:"))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Pure: one entry as compared. The family before the first {@code :} or {@code /} is lower-cased (server names and the {@code factory}/{@code connectors}
	 * prefixes are case-insensitive); the rest is kept as written. Null for a blank value or one with no family.
	 */
	static String normalise(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String value = pValue.trim();
		if (value.isEmpty())
		{
			return null;
		}
		int cut = -1;
		for (int i = 0; i < value.length(); i++)
		{
			final char c = value.charAt(i);
			if (c == ':' || c == '/')
			{
				cut = i;
				break;
			}
		}
		if (cut <= 0 || cut == value.length() - 1)
		{
			return null;
		}
		return value.substring(0, cut).toLowerCase(Locale.ROOT) + value.substring(cut);
	}
}
