package com.on.ebx.factory.mappingmatrix;

import java.util.Arrays;
import java.util.Locale;

import com.on.ebx.factory.capability.FunctionDoc;

/**
 * Lot G2 (2026-10-17): a computed-field card shows what the library function does in the viewer's language. The live card of a French session read the
 * English doc comment of the library; the texts of the known functions now come from the bundle (keyed by library and function), the library's own text is
 * the fallback for any other function and for an English viewer.
 */
public final class MatrixFunctionTextsTest
{
	private static final String LIBRARY_SOURCE = String.join("\n",
		"/**",
		" * The canonical form to store: '+' then the calling code then the national significant number, no separators.",
		" * @param value the number, in either form, may be null",
		" * @return the E.164 string, or null when the number is not valid",
		" */",
		"export function toE164(value: string, country: string): string",
		"begin",
		"  return null;",
		"end",
		"/**",
		" * Does something this bundle has never heard of. It has a second sentence.",
		" * @param thing the thing, in English only",
		" * @return the result, in English only",
		" */",
		"export function brandNew(thing: string): string",
		"begin",
		"  return null;",
		"end");

	public static void main(final String[] pArgs)
	{
		MatrixFunctionTextsTest.importNameFromAnIdePathOrAnImportName();
		MatrixFunctionTextsTest.aFrenchViewerReadsTheBundleTextOfAKnownFunction();
		MatrixFunctionTextsTest.anEnglishViewerReadsTheLibrarysOwnText();
		MatrixFunctionTextsTest.anUnknownFunctionKeepsTheLibraryText();
		MatrixFunctionTextsTest.withoutTheLibraryTheBundleStillAnswersAndAnUnknownFunctionAnswersNothing();
		MatrixFunctionTextsTest.theCardAndTheInspectorCarryTheViewersLanguage();
		MatrixFunctionTextsTest.everyKnownFunctionHasBothLanguages();
		System.out.println("MatrixFunctionTextsTest OK");
	}

	private static MatrixModel.Computation computation(final String pLibrary, final String pFunction, final boolean pWithDoc)
	{
		return new MatrixModel.Computation("98", "PROPOSED", pLibrary, pFunction, "{\"value\":\"phone\",\"country\":{\"literal\":\"\"}}", "why",
			pWithDoc ? FunctionDoc.parse(MatrixFunctionTextsTest.LIBRARY_SOURCE, pFunction) : null, pWithDoc ? "" : "no library", MatrixModel.Measurement.notLoaded());
	}

	private static void importNameFromAnIdePathOrAnImportName()
	{
		check("ide path", "Fasttrack.lib.phone".equals(MatrixFunctionTexts.importName("library/Fasttrack/lib/phone")));
		check("import name", "Fasttrack.lib.names".equals(MatrixFunctionTexts.importName("Fasttrack.lib.names")));
		check("blank", MatrixFunctionTexts.importName(null).isEmpty() && MatrixFunctionTexts.importName(" ").isEmpty());
	}

	private static void aFrenchViewerReadsTheBundleTextOfAKnownFunction()
	{
		final MatrixModel.Computation c = MatrixFunctionTextsTest.computation("library/Fasttrack/lib/phone", "toE164", true);
		final String description = MatrixFunctionTexts.description(c, Locale.FRENCH);
		check("the description is the French one: " + description, description.startsWith("La forme canonique à stocker"));
		check("the return text is French", MatrixFunctionTexts.returns(c, Locale.FRENCH).startsWith("la chaîne E.164"));
		check("a parameter text is French", MatrixFunctionTexts.param(c, Locale.FRENCH, "value").contains("peut être nul"));
		check("a parameter the bundle does not know falls back to the library's (none: empty)", MatrixFunctionTexts.param(c, Locale.FRENCH, "nope").isEmpty());
		check("the cell's short return reads French too", MatrixComputed.returnsShort(c, Locale.FRENCH).equals("la chaîne E.164"));
	}

	private static void anEnglishViewerReadsTheLibrarysOwnText()
	{
		final MatrixModel.Computation c = MatrixFunctionTextsTest.computation("library/Fasttrack/lib/phone", "toE164", true);
		check("the library's description", MatrixFunctionTexts.description(c, Locale.ENGLISH).equals(c.doc.description));
		check("the library's return text", MatrixFunctionTexts.returns(c, Locale.ENGLISH).equals(c.doc.returns));
		check("no locale is English", MatrixFunctionTexts.description(c, null).equals(c.doc.description));
		check("the short return, as before", "the E.164 string".equals(MatrixComputed.returnsShort(c)));
	}

	private static void anUnknownFunctionKeepsTheLibraryText()
	{
		final MatrixModel.Computation c = MatrixFunctionTextsTest.computation("library/Fasttrack/lib/phone", "brandNew", true);
		check("a French viewer sees what the library says when the bundle has nothing", MatrixFunctionTexts.description(c, Locale.FRENCH).equals(c.doc.description)
			&& MatrixFunctionTexts.returns(c, Locale.FRENCH).equals("the result, in English only") && MatrixFunctionTexts.param(c, Locale.FRENCH, "thing").equals("the thing, in English only"));
	}

	private static void withoutTheLibraryTheBundleStillAnswersAndAnUnknownFunctionAnswersNothing()
	{
		final MatrixModel.Computation known = MatrixFunctionTextsTest.computation("library/Fasttrack/lib/names", "sortedKeyOf", false);
		check("French, library unreadable: the bundle text", MatrixFunctionTexts.description(known, Locale.FRENCH).startsWith("Clé de nom"));
		check("English, library unreadable: nothing (the English bundle text is the reference of a translation, never shown)", MatrixFunctionTexts.description(known, Locale.ENGLISH).isEmpty());
		final MatrixModel.Computation unknown = MatrixFunctionTextsTest.computation("library/Acme/lib/x", "f", false);
		check("nothing known, nothing invented", MatrixFunctionTexts.description(unknown, Locale.FRENCH).isEmpty() && MatrixFunctionTexts.returns(unknown, Locale.FRENCH).isEmpty());
	}

	private static void theCardAndTheInspectorCarryTheViewersLanguage()
	{
		final MatrixModel.Computation c = MatrixFunctionTextsTest.computation("library/Fasttrack/lib/phone", "toE164", true);
		final MatrixModel.Attr attr = new MatrixModel.Attr("a1", "phoneE164", "STRING", false, false, false, "PROPOSED", Integer.valueOf(86), null, "SCRIPT", "phone", c);
		final MatrixModel.Entity entity = new MatrixModel.Entity("e1", "Person", Arrays.asList(attr), Arrays.<MatrixModel.Feed>asList(), "ACCEPTED", Integer.valueOf(100));
		final String fr = MatrixComputed.cellJson("{\"x\":1}", entity, attr, Locale.FRENCH);
		check("the inspector's 'what' is French: " + fr, fr.contains("\"cWhat\":\"La forme canonique \\u00e0 stocker") || fr.contains("\"cWhat\":\"La forme canonique à stocker"));
		check("its 'when empty' is French", fr.contains("la chaîne E.164"));
		check("the argument text is French", fr.contains("peut être nul"));
		check("not the English comment", !fr.contains("The canonical form to store"));
		final String en = MatrixComputed.cellJson("{\"x\":1}", entity, attr, Locale.ENGLISH);
		check("English keeps the library's own text", en.contains("The canonical form to store") && en.contains("the E.164 string, or null when the number is not valid"));
		check("the cell's second line follows the language", MatrixComputed.cellLine2(attr, Locale.FRENCH).contains("la chaîne E.164")
			&& MatrixComputed.cellLine2(attr, Locale.ENGLISH).contains("the E.164 string"));
	}

	/** Every function of the bundle has a description in both languages (the return and parameter texts are optional per function but come in pairs). */
	private static void everyKnownFunctionHasBothLanguages()
	{
		final String[][] known = { { "Fasttrack.lib.names", "sortedKey" }, { "Fasttrack.lib.names", "sortedKeyOf" }, { "Fasttrack.lib.names", "sortedKeyAny" },
			{ "Fasttrack.lib.phone", "toE164" }, { "Fasttrack.lib.phone", "toE164WithDefault" }, { "Fasttrack.lib.phone", "normalize" }, { "Fasttrack.lib.phone", "countryOf" },
			{ "Fasttrack.lib.phone", "nationalNumberOf" }, { "Fasttrack.lib.phone", "callingCodeOf" }, { "Fasttrack.lib.phone", "countryOfCallingCode" },
			{ "Fasttrack.lib.address", "buildFullAddress" }, { "Fasttrack.lib.address", "normalizeLocal" }, { "Fasttrack.lib.address", "orEmpty" } };
		for (final String[] function : known)
		{
			for (final String part : new String[] { "description", "returns" })
			{
				final String key = "capability.fn." + function[0] + "." + function[1] + "." + part;
				check(key + " in English", MatrixMessages.find(Locale.ENGLISH, key) != null);
				final String fr = MatrixMessages.find(Locale.FRENCH, key);
				check(key + " in French", fr != null && !fr.equals(MatrixMessages.find(Locale.ENGLISH, key)));
			}
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("MatrixFunctionTextsTest: " + pWhat);
		}
	}
}
