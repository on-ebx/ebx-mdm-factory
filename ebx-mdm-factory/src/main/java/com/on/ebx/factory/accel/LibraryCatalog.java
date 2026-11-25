package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

/** The published script libraries a pack may wrap ({@code libraries.json}). */
public final class LibraryCatalog
{
	/** One library: its name, function signatures and what it covers. */
	public static final class Library
	{
		private final String name;
		private final List<String> functions;
		private final List<String> covers;

		Library(final JsonNode pNode, final String pWhere)
		{
			this.name = Res.required(pNode, "name", pWhere);
			this.functions = Collections.unmodifiableList(Res.strings(pNode.get("functions")));
			this.covers = Collections.unmodifiableList(Res.strings(pNode.get("covers")));
			if (this.functions.isEmpty())
			{
				throw new IllegalStateException("Accelerator file " + pWhere + ": library " + this.name + " declares no function");
			}
		}

		public String name()
		{
			return this.name;
		}

		/** Signatures as written, e.g. {@code isValid(s: string): boolean}. */
		public List<String> functions()
		{
			return this.functions;
		}

		public List<String> covers()
		{
			return this.covers;
		}

		/** Function names without their signature. */
		public List<String> functionNames()
		{
			final List<String> out = new ArrayList<String>();
			for (final String f : this.functions)
			{
				out.add(LibraryCatalog.bareName(f));
			}
			return out;
		}
	}

	private final Map<String, Library> byName;
	private final Set<String> qualified;

	LibraryCatalog(final JsonNode pRoot, final String pWhere)
	{
		final Map<String, Library> m = new LinkedHashMap<String, Library>();
		final Set<String> q = new LinkedHashSet<String>();
		for (final JsonNode item : pRoot.path("libraries"))
		{
			final Library lib = new Library(item, pWhere);
			if (m.put(lib.name(), lib) != null)
			{
				throw new IllegalStateException("Accelerator file " + pWhere + ": library " + lib.name() + " declared twice");
			}
			for (final String f : lib.functionNames())
			{
				q.add(lib.name() + "." + f);
			}
		}
		if (m.isEmpty())
		{
			throw new IllegalStateException("Accelerator file " + pWhere + ": no library declared");
		}
		this.byName = Collections.unmodifiableMap(m);
		this.qualified = Collections.unmodifiableSet(q);
	}

	static String bareName(final String pSignature)
	{
		final int paren = pSignature.indexOf('(');
		return (paren < 0 ? pSignature : pSignature.substring(0, paren)).trim();
	}

	/** True when the fully qualified function ({@code Fasttrack.lib.phone.isValid}, signature tolerated) is published here. */
	public boolean knows(final String pFunction)
	{
		return pFunction != null && this.qualified.contains(LibraryCatalog.bareName(pFunction));
	}

	/** Null when absent. */
	public Library library(final String pName)
	{
		return this.byName.get(pName);
	}

	public List<Library> all()
	{
		return Collections.unmodifiableList(new ArrayList<Library>(this.byName.values()));
	}

	/** One line per library: name, signatures, what it covers. No JSON. */
	public String promptText()
	{
		final StringBuilder sb = new StringBuilder();
		for (final Library lib : this.byName.values())
		{
			sb.append(lib.name()).append(": ").append(join(lib.functions(), "; "));
			if (!lib.covers().isEmpty())
			{
				sb.append(" | covers ").append(join(lib.covers(), "; "));
			}
			sb.append('\n');
		}
		return sb.toString();
	}

	static String join(final List<String> pItems, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pItems.size(); i++)
		{
			sb.append(i > 0 ? pSeparator : "").append(pItems.get(i));
		}
		return sb.toString();
	}
}
