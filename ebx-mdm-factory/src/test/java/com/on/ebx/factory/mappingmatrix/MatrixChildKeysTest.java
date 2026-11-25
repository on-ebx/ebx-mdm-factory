package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.Locale;

import com.on.ebx.factory.review.ReviewRules;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Lot M3 (2026-10-03): a {@code
 * CHILD_KEYS} attribute is derived (so it has no source link and no mapping by design, exactly like a matching key) and the
 * Mapping matrix says so with its own wording, naming the child entity - never "Computed from another field".
 */
public final class MatrixChildKeysTest
{
	public static void main(final String[] pArgs)
	{
		MatrixChildKeysTest.aChildKeysAttributeIsDerivedSoItNeedsNoMapping();
		MatrixChildKeysTest.theMatrixWordsItWithTheChildEntity();
		MatrixChildKeysTest.everyDerivedWordingExistsInBothLanguages();
		System.out.println("MatrixChildKeysTest OK");
	}

	private static MatrixModel.Attr attr()
	{
		// derivedFrom carries the child entity name for a CHILD_KEYS row (ebx.MappingMatrixReader), the source attribute's name for the others
		return new MatrixModel.Attr("7", "adresseKeys", "STRING", false, false, false, "ACCEPTED", null, null, "CHILD_KEYS", "Adresse");
	}

	/** The "no mapping" gates (ReviewRules.attributeMappingWarning, the wizard's accepted-attribute-without-mapping rule) test "derivation is set": CHILD_KEYS is. */
	private static void aChildKeysAttributeIsDerivedSoItNeedsNoMapping()
	{
		final MatrixModel.Attr attr = MatrixChildKeysTest.attr();
		MatrixChildKeysTest.check("derived", attr.isDerived());
		MatrixChildKeysTest.check("not a matching key: the matrix queue treats it as a computed row, never swept in bulk", !MatrixCompute.isFoldKey(attr));
		MatrixChildKeysTest.check("accepted and with no mapping at all: no warning", ReviewRules.attributeMappingWarning("ACCEPTED", false, attr.isDerived(), new ArrayList<String>()) == null);
	}

	private static void theMatrixWordsItWithTheChildEntity()
	{
		final MatrixModel.Attr attr = MatrixChildKeysTest.attr();
		MatrixChildKeysTest.check("flag (en): " + MatrixHtml.derivedLabel(attr, "mappingMatrix.flag.derived.", Locale.ENGLISH),
			"Keys of the Adresse records".equals(MatrixHtml.derivedLabel(attr, "mappingMatrix.flag.derived.", Locale.ENGLISH)));
		MatrixChildKeysTest.check("flag (fr): " + MatrixHtml.derivedLabel(attr, "mappingMatrix.flag.derived.", Locale.FRENCH),
			"Clés des enregistrements Adresse".equals(MatrixHtml.derivedLabel(attr, "mappingMatrix.flag.derived.", Locale.FRENCH)));
		final String line2 = MatrixHtml.derivedLabel(attr, "mappingMatrix.cell.derived.line2.", Locale.ENGLISH);
		MatrixChildKeysTest.check("cell line (en): " + line2, line2.contains("Adresse") && line2.contains("one key per"));
		final String explain = MatrixHtml.derivedLabel(attr, "mappingMatrix.explain.derived.", Locale.ENGLISH);
		MatrixChildKeysTest.check("explanation (en) names the child and says it is computed after it is loaded: " + explain, explain.contains("Adresse") && explain.contains("AFTER"));
		final String explainFr = MatrixHtml.derivedLabel(attr, "mappingMatrix.explain.derived.", Locale.FRENCH);
		MatrixChildKeysTest.check("explanation (fr) formats its apostrophe: " + explainFr, explainFr.contains("s'en sert") && explainFr.contains("Adresse"));
	}

	/** The fallback "OTHER" text would also read, so check the dedicated key itself is in the bundle for both languages. */
	private static void everyDerivedWordingExistsInBothLanguages()
	{
		for (final String prefix : new String[] { "mappingMatrix.flag.derived.", "mappingMatrix.cell.derived.line2.", "mappingMatrix.explain.derived." })
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				MatrixChildKeysTest.check("dedicated key exists: " + prefix + "CHILD_KEYS " + locale, !MatrixMessages.get(locale, prefix + "CHILD_KEYS", "X").equals(prefix + "CHILD_KEYS")
					&& !MatrixMessages.get(locale, prefix + "CHILD_KEYS", "X").equals(MatrixMessages.get(locale, prefix + "OTHER", "X")));
			}
		}
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			MatrixChildKeysTest.check("review wording exists (" + locale + ")", MatrixMessages.get(locale, "review.prop.attribute.childkeys", "STRING", "Adresse").contains("Adresse"));
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
