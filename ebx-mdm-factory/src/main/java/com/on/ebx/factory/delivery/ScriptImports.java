package com.on.ebx.factory.delivery;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The EBX Script libraries a delivered model needs, by name, closed over their own imports. Measured
 * 2026-09-22 on the target instance: a DMA model whose script constraints import {@code Fasttrack.lib.phone}
 * fails its staging import with "Library 'Fasttrack.lib.phone' is not published or does not exist"; a library
 * travels as a {@code ScriptSource} staging element (id {@code definition.library.<name>}), and EBX computes no
 * library-to-library dependency for it (the archive's {@code _package.json} lists only "Dataset:Script
 * sources"), so the closure is this class's job: {@code Fasttrack.lib.phone} imports
 * {@code Fasttrack.lib.validator.regex}, which must travel too. Pure; {@code ebx.DeliveryDomainReader} reads
 * the sources from the technical dataset {@code ebx-script-source-2/ebx-script-source} ({@code /root/definition},
 * fields {@code idePath}, {@code source}).
 */
public final class ScriptImports
{
	private ScriptImports()
	{
	}

	/** {@code import Fasttrack.lib.validator.regex as rx;} - a {@code uses core.string as str;} line is a built-in module, never a library. */
	private static final Pattern IMPORT = Pattern.compile("(?m)^\\s*import\\s+([A-Za-z_][\\w.]*)\\s+as\\s+[A-Za-z_]\\w*\\s*;");

	/** The library names one source imports, in order of appearance, each once. */
	public static List<String> importsOf(final String pSource)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (pSource != null)
		{
			final Matcher matcher = ScriptImports.IMPORT.matcher(pSource);
			while (matcher.find())
			{
				out.add(matcher.group(1));
			}
		}
		return new java.util.ArrayList<String>(out);
	}

	/** {@code Fasttrack.lib.phone} → IDE path {@code library/Fasttrack/lib/phone}. */
	public static String idePath(final String pLibraryName)
	{
		return "library/" + pLibraryName.replace('.', '/');
	}

	/** {@code Fasttrack.lib.phone} → staging element id {@code definition.library.Fasttrack.lib.phone} (read live on the source instance). */
	public static String stagingElementId(final String pLibraryName)
	{
		return "definition.library." + pLibraryName;
	}

	public static final class Closure
	{
		/** Every library needed, roots first then their imports, each once, in a stable order. */
		public final List<String> libraries;
		/** Names whose source could not be read (not found, or not readable): the delivery is refused on them. */
		public final List<String> missing;

		Closure(final List<String> pLibraries, final List<String> pMissing)
		{
			this.libraries = java.util.Collections.unmodifiableList(pLibraries);
			this.missing = java.util.Collections.unmodifiableList(pMissing);
		}
	}

	/**
	 * Closes the root library names over their imports. {@code pSourceOf} answers a library's source text by
	 * name, or null when it does not exist. A cycle ends naturally (each name is visited once).
	 */
	public static Closure closure(final List<String> pRoots, final Function<String, String> pSourceOf)
	{
		final Set<String> seen = new LinkedHashSet<String>();
		final List<String> missing = new java.util.ArrayList<String>();
		final Deque<String> queue = new ArrayDeque<String>(pRoots);
		while (!queue.isEmpty())
		{
			final String name = queue.removeFirst();
			if (name == null || name.trim().isEmpty() || !seen.add(name.trim()))
			{
				continue;
			}
			final String source = pSourceOf.apply(name.trim());
			if (source == null)
			{
				missing.add(name.trim());
				continue;
			}
			for (final String imported : ScriptImports.importsOf(source))
			{
				if (!seen.contains(imported))
				{
					queue.addLast(imported);
				}
			}
		}
		return new Closure(new java.util.ArrayList<String>(seen), missing);
	}
}
