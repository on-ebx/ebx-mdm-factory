package com.on.ebx.factory.core;

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.core.WriterGuard.Verdict;
import com.on.ebx.factory.core.WriterGuard.Writer;
import com.orchestranetworks.schema.SchemaNode;

/**
 * Audit F-06: the decision of the writer guard ({@link WriterGuard}) and its EBX half ({@link WriterShapes}) - kinds read off a schema node, a refusal that
 * names writer / path / version / switch, one switch per writer, the global switch, and a guard that itself fails never blocking a writer. JUnit-free: run the main.
 */
public final class WriterGuardTest
{
	private static final String VERSIONS = "ON EBX 6.3.0.4-RC2 (build 6.3.0-rc2); add-ons ebx-addons.jar (1 bytes, 2026-09-24); module 1.0";

	public static void main(final String[] pArgs)
	{
		WriterGuardTest.switches();
		WriterGuardTest.kindsAreReadOffTheSchemaNode();
		WriterGuardTest.checkAcceptsWhatMatches();
		WriterGuardTest.checkNamesEveryDifference();
		WriterGuardTest.refusalNamesWriterPathVersionAndSwitch();
		WriterGuardTest.refusalIsFrenchWhenAsked();
		WriterGuardTest.refusalFromTheSchema();
		WriterGuardTest.aSwitchedOffWriterRefuses();
		WriterGuardTest.theGlobalSwitchSkipsTheShapeCheckOnly();
		WriterGuardTest.aGuardThatFailsNeverBlocksTheWriter();
		System.out.println("WriterGuardTest OK");
	}

	private static void switches()
	{
		final Function<String, String> none = new Function<String, String>()
		{
			@Override
			public String apply(final String pName)
			{
				return null;
			}
		};
		for (final Writer writer : Writer.values())
		{
			WriterGuardTest.check("default is ON for " + writer, WriterGuard.enabled(writer, none));
			WriterGuardTest.check("the property of " + writer + " is ebx.factory.writer.<id>", writer.property().equals("ebx.factory.writer." + writer.id()));
			WriterGuardTest.check(writer + " off with false (any case, blanks)", !WriterGuard.enabled(writer, WriterGuardTest.props(writer.property(), " False ")));
			WriterGuardTest.check(writer + " stays on with another value", WriterGuard.enabled(writer, WriterGuardTest.props(writer.property(), "true")));
			WriterGuardTest.check(writer + ": the switch of another writer does not touch it", WriterGuard.enabled(writer,
				WriterGuardTest.props(writer == Writer.DMA_TOOLBARS ? Writer.PUBLICATION_OWNER.property() : Writer.DMA_TOOLBARS.property(), "false")));
		}
		WriterGuardTest.check("the older perspective switch still turns the perspective off", !WriterGuard.enabled(Writer.PERSPECTIVE, WriterGuardTest.props("ebx.factory.perspective", "false")));
		WriterGuardTest.check("the older perspective switch touches no other writer", WriterGuard.enabled(Writer.DMA_TOOLBARS, WriterGuardTest.props("ebx.factory.perspective", "false")));
		WriterGuardTest.check("three writers, three distinct ids", Writer.values().length == 3
			&& Writer.DMA_TOOLBARS.id().equals("dmaToolbars") && Writer.PUBLICATION_OWNER.id().equals("publicationOwner") && Writer.PERSPECTIVE.id().equals("perspective"));
	}

	private static Function<String, String> props(final String pName, final String pValue)
	{
		return new Function<String, String>()
		{
			@Override
			public String apply(final String pAsked)
			{
				return pName.equals(pAsked) ? pValue : null;
			}
		};
	}

	private static void kindsAreReadOffTheSchemaNode()
	{
		for (final Kind kind : Kind.values())
		{
			if (kind == Kind.OTHER)
			{
				continue; // an expectation only; the node stand-in answers a date for it
			}
			WriterGuardTest.check("a " + kind + " node reads back as " + kind, WriterShapes.kindOf(FakeSchemaTest.node(kind)) == kind);
		}
		WriterGuardTest.check("a date reads as OTHER", WriterShapes.kindOf(FakeSchemaTest.node(Kind.OTHER)) == Kind.OTHER);
		WriterGuardTest.check("no node reads as null", WriterShapes.kindOf(null) == null);
		WriterGuardTest.check("the integer family", WriterShapes.kindOfClass(Short.class) == Kind.INT && WriterShapes.kindOfClass(Long.class) == Kind.INT
			&& WriterShapes.kindOfClass(java.math.BigInteger.class) == Kind.INT);
	}

	private static void checkAcceptsWhatMatches()
	{
		final List<Expectation> expected = Arrays.asList(Expectation.of("/a/t", Kind.TABLE), Expectation.of("/a/t/s", Kind.STRING), Expectation.of("/a/t/any", Kind.OTHER),
			Expectation.of("/a/t/l", Kind.LIST));
		final Map<String, SchemaNode> nodes = FakeSchemaTest.map();
		nodes.put("/a/t", FakeSchemaTest.node(Kind.TABLE));
		nodes.put("/a/t/s", FakeSchemaTest.node(Kind.STRING));
		nodes.put("/a/t/any", FakeSchemaTest.node(Kind.FOREIGN_KEY)); // OTHER accepts any existing node
		nodes.put("/a/t/l", FakeSchemaTest.node(Kind.LIST));
		final Verdict verdict = WriterGuard.check(Writer.DMA_TOOLBARS, WriterShapes.shapeOf(FakeSchemaTest.root(nodes)), expected);
		WriterGuardTest.check("everything matches: " + verdict.problems.size(), verdict.ok());
	}

	private static void checkNamesEveryDifference()
	{
		final List<Expectation> expected = Arrays.asList(Expectation.of("/a/t/gone", Kind.STRING), Expectation.of("/a/t/typed", Kind.INT), Expectation.of("/a/t/list", Kind.LIST),
			Expectation.of("/a/t/fine", Kind.STRING));
		final Map<String, SchemaNode> nodes = FakeSchemaTest.map();
		nodes.put("/a/t/typed", FakeSchemaTest.node(Kind.STRING));
		nodes.put("/a/t/list", FakeSchemaTest.node(Kind.STRING)); // was a list, now single
		nodes.put("/a/t/fine", FakeSchemaTest.node(Kind.STRING));
		final Verdict verdict = WriterGuard.check(Writer.PUBLICATION_OWNER, WriterShapes.shapeOf(FakeSchemaTest.root(nodes)), expected);
		WriterGuardTest.check("three differences, in order: " + verdict.problems.size(), verdict.problems.size() == 3 && verdict.problems.get(0).path.equals("/a/t/gone")
			&& verdict.problems.get(0).missing() && verdict.problems.get(1).actual == Kind.STRING && verdict.problems.get(1).expected == Kind.INT
			&& verdict.problems.get(2).expected == Kind.LIST);
		WriterGuardTest.check("a null shape answer is a miss for an OTHER expectation too",
			!WriterGuard.check(Writer.PERSPECTIVE, WriterShapes.shapeOf(FakeSchemaTest.root(FakeSchemaTest.map())), Arrays.asList(Expectation.of("/x", Kind.OTHER))).ok());
	}

	private static void refusalNamesWriterPathVersionAndSwitch()
	{
		final List<Expectation> expected = Arrays.asList(Expectation.of("/root/toolbarNode/toolbar/name", Kind.STRING), Expectation.of("/root/toolbarNode/ordering", Kind.INT));
		final Verdict verdict = WriterGuard.check(Writer.DMA_TOOLBARS, WriterShapes.shapeOf(FakeSchemaTest.root(FakeSchemaTest.map())), expected);
		final String message = verdict.message(Locale.ENGLISH, WriterGuardTest.VERSIONS);
		WriterGuardTest.check("names the writer id: " + message, message.contains("\"dmaToolbars\""));
		WriterGuardTest.check("names the path", message.contains("/root/toolbarNode/toolbar/name") && message.contains("/root/toolbarNode/ordering"));
		WriterGuardTest.check("names the version read at start", message.contains(WriterGuardTest.VERSIONS));
		WriterGuardTest.check("says nothing was written", message.contains("Nothing was written"));
		WriterGuardTest.check("names the switch", message.contains("-Debx.factory.writer.dmaToolbars=false"));
		final StringBuilder many = new StringBuilder();
		final List<Expectation> lots = new java.util.ArrayList<Expectation>();
		for (int i = 0; i < 20; i++)
		{
			lots.add(Expectation.of("/p/f" + i, Kind.STRING));
		}
		final String longMessage = WriterGuard.check(Writer.PERSPECTIVE, WriterShapes.shapeOf(FakeSchemaTest.root(FakeSchemaTest.map())), lots).message(Locale.ENGLISH, WriterGuardTest.VERSIONS);
		WriterGuardTest.check("a long list is cut and counted: " + longMessage, longMessage.contains("/p/f0") && longMessage.contains("/p/f5") && !longMessage.contains("/p/f6")
			&& longMessage.contains("and 14 more"));
		many.append(longMessage);
	}

	private static void refusalIsFrenchWhenAsked()
	{
		final Verdict verdict = WriterGuard.check(Writer.DMA_TOOLBARS, WriterShapes.shapeOf(FakeSchemaTest.root(FakeSchemaTest.map())),
			Arrays.asList(Expectation.of("/root/X/y", Kind.STRING)));
		final String french = verdict.message(Locale.FRENCH, WriterGuardTest.VERSIONS);
		WriterGuardTest.check("French refusal: " + french, french.contains("refusé") && french.contains("/root/X/y") && french.contains(WriterGuardTest.VERSIONS)
			&& french.contains("-Debx.factory.writer.dmaToolbars=false") && french.contains("Rien n'a été écrit"));
		final String disabled = WriterGuard.disabledMessage(Writer.PERSPECTIVE, Locale.FRENCH);
		WriterGuardTest.check("French disabled message: " + disabled, disabled.contains("désactivé") && disabled.contains("-Debx.factory.writer.perspective=false"));
	}

	private static void refusalFromTheSchema()
	{
		WriterShapes.resetLogsForTest();
		final List<Expectation> expected = Arrays.asList(Expectation.of("/t", Kind.TABLE), Expectation.of("/t/a", Kind.STRING));
		WriterGuardTest.check("a matching schema: no refusal", WriterShapes.refusal(Writer.DMA_TOOLBARS, Locale.ENGLISH, FakeSchemaTest.satisfying(expected), expected) == null);
		final String refusal = WriterShapes.refusal(Writer.DMA_TOOLBARS, Locale.ENGLISH, FakeSchemaTest.satisfyingWithout(expected, "/t/a"), expected);
		WriterGuardTest.check("a schema without /t/a: refusal naming it: " + refusal, refusal != null && refusal.contains("/t/a") && refusal.contains("ON EBX"));
		WriterGuardTest.check("the versions string reads the product and the module", WriterShapes.versions().startsWith("ON EBX ") && WriterShapes.versions().contains("; add-ons ")
			&& WriterShapes.versions().contains("; module "));
	}

	private static void aSwitchedOffWriterRefuses()
	{
		final List<Expectation> expected = Arrays.asList(Expectation.of("/t", Kind.TABLE));
		final String property = Writer.PUBLICATION_OWNER.property();
		final String before = System.getProperty(property);
		try
		{
			System.setProperty(property, "false");
			final String refusal = WriterShapes.refusal(Writer.PUBLICATION_OWNER, Locale.ENGLISH, FakeSchemaTest.satisfying(expected), expected);
			WriterGuardTest.check("a writer switched off refuses even when its shape is right: " + refusal, refusal != null && refusal.contains("turned off")
				&& refusal.contains("-D" + property + "=false"));
			WriterGuardTest.check("the other writers are not affected", WriterShapes.refusal(Writer.DMA_TOOLBARS, Locale.ENGLISH, FakeSchemaTest.satisfying(expected), expected) == null);
		}
		finally
		{
			WriterGuardTest.restore(property, before);
		}
	}

	private static void theGlobalSwitchSkipsTheShapeCheckOnly()
	{
		final List<Expectation> expected = Arrays.asList(Expectation.of("/t/a", Kind.STRING));
		final String before = System.getProperty(WriterGuard.GLOBAL_PROPERTY);
		final String perspectiveBefore = System.getProperty(Writer.PERSPECTIVE.property());
		try
		{
			System.setProperty(WriterGuard.GLOBAL_PROPERTY, "false");
			WriterGuardTest.check("shape check off: a wrong shape goes through (the behaviour before the guard)",
				WriterShapes.refusal(Writer.DMA_TOOLBARS, Locale.ENGLISH, FakeSchemaTest.root(FakeSchemaTest.map()), expected) == null);
			System.setProperty(Writer.PERSPECTIVE.property(), "false");
			WriterGuardTest.check("shape check off: a writer switched off is still off", WriterShapes.refusal(Writer.PERSPECTIVE, Locale.ENGLISH, FakeSchemaTest.root(FakeSchemaTest.map()), expected) != null);
		}
		finally
		{
			WriterGuardTest.restore(WriterGuard.GLOBAL_PROPERTY, before);
			WriterGuardTest.restore(Writer.PERSPECTIVE.property(), perspectiveBefore);
		}
	}

	private static void aGuardThatFailsNeverBlocksTheWriter()
	{
		WriterShapes.resetLogsForTest();
		final String refusal = WriterShapes.refusal(Writer.DMA_TOOLBARS, Locale.ENGLISH, FakeSchemaTest.brokenRoot(), Arrays.asList(Expectation.of("/t/a", Kind.STRING)));
		WriterGuardTest.check("a schema that does not answer: the writer goes ahead as before", refusal == null);
	}

	private static void restore(final String pProperty, final String pBefore)
	{
		if (pBefore == null)
		{
			System.clearProperty(pProperty);
		}
		else
		{
			System.setProperty(pProperty, pBefore);
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
