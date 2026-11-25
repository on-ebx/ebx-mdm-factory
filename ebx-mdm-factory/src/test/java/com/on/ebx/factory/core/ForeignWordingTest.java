package com.on.ebx.factory.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

/**
 * Audit F-27: the module decides from the wording of other components' errors in ONE class ({@link ForeignWording}); each marker is pinned to a sample of the
 * real message and to messages that must not match, and a scan of the sources refuses a marker written anywhere else. JUnit-free: run the main (the scan needs
 * {@code -Dmdmf.sourceDir}).
 */
public final class ForeignWordingTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		ForeignWordingTest.dataspaceLocked();
		ForeignWordingTest.manualMerge();
		ForeignWordingTest.addonIndexFailure();
		ForeignWordingTest.absentMarkers();
		ForeignWordingTest.alreadyRemoved();
		ForeignWordingTest.unknownTransformation();
		ForeignWordingTest.noMarkerIsWrittenOutsideTheClass();
		System.out.println("ForeignWordingTest OK");
	}

	private static void dataspaceLocked()
	{
		check("the add-on's refusal, as measured 2026-09-27", ForeignWording.isDataspaceLocked("The Run match service cannot be executed: the dataspace is locked by another operation"));
		check("case does not matter", ForeignWording.isDataspaceLocked("DATASPACE IS LOCKED"));
		check("matching paused is not a lock", !ForeignWording.isDataspaceLocked("The matching is paused for this table"));
		check("a policy that is not active is not a lock", !ForeignWording.isDataspaceLocked("Please check the Matching policy configuration"));
		check("null is not a lock", !ForeignWording.isDataspaceLocked(null));
	}

	private static void manualMerge()
	{
		check("the documented precondition", ForeignWording.isManualMergePolicyRefusal("A merge policy should have the property 'Used for manual merge' set to true"));
		check("another merge failure is not that", !ForeignWording.isManualMergePolicyRefusal("Select two or more records that are not 'Merged' or 'Deleted'"));
		check("null", !ForeignWording.isManualMergePolicyRefusal(null));
	}

	private static void addonIndexFailure()
	{
		check("the measured message", ForeignWording.isAddonIndexFailure(new RuntimeException("field [Linked_Field_State] not found; input fields are: [id, sourceSystem]")));
		check("through a wrapper", ForeignWording.isAddonIndexFailure(new IllegalStateException("search failed", new RuntimeException("no Linked_Field_GroupId"))));
		check("an unrelated failure is not one", !ForeignWording.isAddonIndexFailure(new RuntimeException("connection refused")));
		check("null", !ForeignWording.isAddonIndexFailure(null));
		final RuntimeException cyclic = new RuntimeException("loop");
		cyclic.initCause(new RuntimeException("again", cyclic));
		check("a cause that loops back does not hang", !ForeignWording.isAddonIndexFailure(cyclic));
		check("the typed marker is the kernel's candidate-search package", ForeignWording.ADDON_INDEX_PACKAGE.equals("com.orchestranetworks.cce"));
	}

	private static void absentMarkers()
	{
		check("a model that is gone (thrown JSON-RPC error, measured 2026-09-29)", ForeignWording.thrownMeansAbsent(
			"tools/call: Tool invocation failed: Data model not found: 'PersVol4_Master'. Call list_data_models to see available model names.", ForeignWording.DATA_MODEL_NOT_FOUND));
		check("a server that does not exist is NOT an absent model", !ForeignWording.thrownMeansAbsent("MCP server not found: 'data-model'", ForeignWording.DATA_MODEL_NOT_FOUND));
		check("a refusal of rights is NOT an absent model", !ForeignWording.thrownMeansAbsent("Access denied to TOOL 'delete_data_model'", ForeignWording.DATA_MODEL_NOT_FOUND));
		check("a mapping that is gone", ForeignWording.thrownMeansAbsent("No mapping '231'", ForeignWording.CONNECTORS_NO_MAPPING));
		check("the delete answer begins with it", ForeignWording.startsWithNoMapping("  no mapping 231"));
		check("a similar wording elsewhere in the text is not that answer", !ForeignWording.startsWithNoMapping("could not delete: no mapping 231"));
		check("null", !ForeignWording.thrownMeansAbsent(null, ForeignWording.CONNECTORS_NO_MAPPING) && !ForeignWording.startsWithNoMapping(null));
	}

	private static void alreadyRemoved()
	{
		check("not found", ForeignWording.looksAlreadyRemoved("Item 'Phone' was not found in javaConstraints"));
		check("does not exist", ForeignWording.looksAlreadyRemoved("The constraint does not exist"));
		check("a compile error is not 'already removed'", !ForeignWording.looksAlreadyRemoved("Validation failed at line 3"));
		check("null", !ForeignWording.looksAlreadyRemoved(null));
	}

	private static void unknownTransformation()
	{
		check("the mapping library's refusal", ForeignWording.isUnknownTransformation(new IllegalArgumentException("Unknown transformation 'upercase'")));
		check("another IllegalArgumentException is not it", !ForeignWording.isUnknownTransformation(new IllegalArgumentException("bad regex")));
		check("another exception type with the same words is not it", !ForeignWording.isUnknownTransformation(new IllegalStateException("Unknown transformation x")));
	}

	/** The quoted literal of each marker never appears in code or strings of another main source (comments are ignored). */
	private static void noMarkerIsWrittenOutsideTheClass() throws IOException
	{
		final String dir = System.getProperty("mdmf.sourceDir");
		check("-Dmdmf.sourceDir is set", dir != null);
		final List<String> literals = Arrays.asList("\"dataspace is locked\"", "\"manual merge\"", "\"no mapping \"", "\"data model not found\"", "\"Unknown transformation\"",
			"\"unknown item\"", "\"no such\"");
		final List<String> stray = new ArrayList<String>();
		try (Stream<Path> files = Files.walk(Paths.get(dir, "com/on/ebx/factory")))
		{
			for (final Path file : (Iterable<Path>) files.filter(f -> f.toString().endsWith(".java"))::iterator)
			{
				if (file.getFileName().toString().equals("ForeignWording.java"))
				{
					continue;
				}
				final String code = ForeignWordingTest.withoutComments(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				for (final String literal : literals)
				{
					if (code.contains(literal))
					{
						stray.add(file.getFileName() + " has " + literal);
					}
				}
			}
		}
		check("a marker of another component's wording is written outside ForeignWording: " + stray, stray.isEmpty());
	}

	/** Rough: drops block comments and line comments (a {@code //} that starts a line or follows a blank). Enough for a search of quoted literals. */
	static String withoutComments(final String pSource)
	{
		return pSource.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)(^|\\s)//.*$", "$1");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
