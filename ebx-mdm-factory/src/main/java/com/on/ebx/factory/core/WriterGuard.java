package com.on.ebx.factory.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * The pure half of the guard every writer into a technical dataset of ON EBX or of an add-on goes through (audit F-06).
 * Three writers put rows into datasets the Factory does not own, with field paths and Java types read off bytecode or off
 * one instance: the DMA's toolbar rows ({@code ebx.DmaToolbarWriter}), the publication owner ({@code ebx.PublicationOwnerRepair}) and the perspective
 * ({@code ebx.PerspectiveInstaller}). (A fourth, the Match and Merge relation fields, left with lot W13: the add-on writes them itself now.) None of them used to look at what the target looks like before writing; after an
 * upgrade that renamed a field they failed halfway, or wrote a shape the product then ignored.
 * <p>
 * Before writing, a writer lists what it is about to touch as {@link Expectation}s (absolute schema path + {@link Kind});
 * {@link #check} compares them with what {@link Shape} answers for the real schema, and a mismatch is a {@link Verdict}
 * that names the writer, every path and what was found - the writer refuses, it never writes a partial shape. Each writer
 * has a switch ({@link Writer#property()}, default on) so that an administrator can turn it off after an upgrade until the
 * module is checked against the new version, and one global switch ({@link #GLOBAL_PROPERTY}) turns the SHAPE CHECK off
 * (the writers then behave exactly as before this guard) in case the check itself is wrong on an instance.
 * <p>
 * No EBX type here: {@code core.WriterShapes} reads the schema, this class only decides. Unit-tested without a server.
 */
public final class WriterGuard
{
	/** {@code -Debx.factory.writerGuard=false}: no shape check at all (the writers run as they did before the guard). Default true. */
	public static final String GLOBAL_PROPERTY = "ebx.factory.writerGuard";

	private static final String SWITCH_PREFIX = "ebx.factory.writer.";

	/** The writers, each with the id that names its switch. */
	public enum Writer
	{
		/** {@code ebx.DmaToolbarWriter}: the master model's toolbar rows in {@code ebx-dma}. */
		DMA_TOOLBARS("dmaToolbars", "master toolbars in the data model assistant"),
		/** {@code ebx.PublicationOwnerRepair}: {@code originalDataModel} of the DMA publications. */
		PUBLICATION_OWNER("publicationOwner", "publication owner repair"),
		/** {@code ebx.PerspectiveInstaller}: the packaged perspective in {@code ebx-manager}. Also off with the older {@code -Debx.factory.perspective=false}. */
		PERSPECTIVE("perspective", "MDM Factory perspective");

		private final String id;
		private final String label;

		Writer(final String pId, final String pLabel)
		{
			this.id = pId;
			this.label = pLabel;
		}

		public String id()
		{
			return this.id;
		}

		/** What an administrator reads in a message: the writer in words. */
		public String label()
		{
			return this.label;
		}

		/** The JVM property that turns this writer off: {@code ebx.factory.writer.<id>=false}. */
		public String property()
		{
			return WriterGuard.SWITCH_PREFIX + this.id;
		}
	}

	/** What a schema node is, as far as a writer cares. */
	public enum Kind
	{
		/** A table node. */
		TABLE,
		/** A single-valued group (complex, at most one occurrence). */
		GROUP,
		/** A repeatable node (more than one occurrence): a list of values or of groups. */
		LIST,
		/** A foreign key (a node with a table-reference facet). */
		FOREIGN_KEY,
		STRING,
		INT,
		DECIMAL,
		BOOLEAN,
		LOCALE,
		/** Another single terminal value (a date, a binary...). As an EXPECTATION: any terminal or group, whatever its type (the writer only needs the path to exist). */
		OTHER;

		/** Whether a node of kind {@code pActual} satisfies an expectation of this kind. {@code pActual == null} (no such node) never does. */
		boolean accepts(final Kind pActual)
		{
			if (pActual == null)
			{
				return false;
			}
			return this == Kind.OTHER || this == pActual;
		}
	}

	/** One path a writer touches: absolute schema path (e.g. {@code /root/toolbarNode/toolbar/name}) and what the writer treats it as. */
	public static final class Expectation
	{
		public final String path;
		public final Kind kind;

		public Expectation(final String pPath, final Kind pKind)
		{
			this.path = pPath;
			this.kind = pKind;
		}

		public static Expectation of(final String pPath, final Kind pKind)
		{
			return new Expectation(pPath, pKind);
		}
	}

	/** What the schema answers: the {@link Kind} of the node at an absolute path, or null when there is none. */
	public interface Shape
	{
		Kind kindOf(String pAbsolutePath);
	}

	/** One difference between what a writer expects and what the schema has. */
	public static final class Problem
	{
		public final String path;
		public final Kind expected;
		/** Null: the path does not exist. */
		public final Kind actual;

		Problem(final String pPath, final Kind pExpected, final Kind pActual)
		{
			this.path = pPath;
			this.expected = pExpected;
			this.actual = pActual;
		}

		public boolean missing()
		{
			return this.actual == null;
		}
	}

	/** The outcome of one check. */
	public static final class Verdict
	{
		public final Writer writer;
		public final List<Problem> problems;

		Verdict(final Writer pWriter, final List<Problem> pProblems)
		{
			this.writer = pWriter;
			this.problems = Collections.unmodifiableList(pProblems);
		}

		public boolean ok()
		{
			return this.problems.isEmpty();
		}

		/**
		 * The refusal, for an administrator: the writer, each path and what was found (at most {@link #MAX_LISTED} of them, then a count),
		 * the version read at start, the way out.
		 */
		public String message(final Locale pLocale, final String pVersions)
		{
			final StringBuilder list = new StringBuilder();
			int listed = 0;
			for (final Problem problem : this.problems)
			{
				if (listed == WriterGuard.MAX_LISTED)
				{
					list.append(ModuleMessages.get(pLocale, "writerguard.problem.more", Integer.valueOf(this.problems.size() - listed)));
					break;
				}
				if (listed > 0)
				{
					list.append("; ");
				}
				list.append(problem.missing() ? ModuleMessages.get(pLocale, "writerguard.problem.missing", problem.path)
					: ModuleMessages.get(pLocale, "writerguard.problem.type", problem.path, problem.expected.name(), problem.actual.name()));
				listed++;
			}
			return ModuleMessages.get(pLocale, "writerguard.refused", this.writer.id(), this.writer.label(), list.toString(), pVersions, this.writer.property());
		}
	}

	/** Paths named in one refusal; the rest are counted. */
	static final int MAX_LISTED = 6;

	private WriterGuard()
	{
	}

	/** Whether {@code pWriter} may run: its switch is not {@code false}. Reads the JVM properties. */
	public static boolean enabled(final Writer pWriter)
	{
		return WriterGuard.enabled(pWriter, new Function<String, String>()
		{
			@Override
			public String apply(final String pName)
			{
				return System.getProperty(pName);
			}
		});
	}

	/** {@link #enabled(Writer)} with the property lookup given (tests). */
	static boolean enabled(final Writer pWriter, final Function<String, String> pProperties)
	{
		if (WriterGuard.isFalse(pProperties.apply(pWriter.property())))
		{
			return false;
		}
		// the switch the perspective installation had before this guard
		return !(pWriter == Writer.PERSPECTIVE && WriterGuard.isFalse(pProperties.apply("ebx.factory.perspective")));
	}

	/** Whether the shape check runs (the global property is not {@code false}). */
	public static boolean shapeCheckOn()
	{
		return !WriterGuard.isFalse(System.getProperty(WriterGuard.GLOBAL_PROPERTY));
	}

	private static boolean isFalse(final String pValue)
	{
		return pValue != null && "false".equalsIgnoreCase(pValue.trim());
	}

	/** The message of a writer that is switched off. */
	public static String disabledMessage(final Writer pWriter, final Locale pLocale)
	{
		return ModuleMessages.get(pLocale, "writerguard.disabled", pWriter.id(), pWriter.label(), pWriter.property());
	}

	/** Compares what {@code pWriter} expects with what {@code pShape} has. Every difference is listed, in the order of {@code pExpected}. */
	public static Verdict check(final Writer pWriter, final Shape pShape, final List<Expectation> pExpected)
	{
		final List<Problem> problems = new ArrayList<Problem>();
		for (final Expectation expectation : pExpected)
		{
			final Kind actual = pShape.kindOf(expectation.path);
			if (!expectation.kind.accepts(actual))
			{
				problems.add(new Problem(expectation.path, expectation.kind, actual));
			}
		}
		return new Verdict(pWriter, problems);
	}
}
