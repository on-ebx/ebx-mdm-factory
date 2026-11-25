package com.on.ebx.factory.mappingmatrix;

import java.util.Locale;

/**
 * Lot G2 (2026-10-17, docs/UX-STAGING-TEST-FIXES-G2.md): the text a computed-field card shows about the library function it calls, in the VIEWER'S
 * language. Measured on the live test: a French card read the English doc comment of the library ("Order-independent key of a name held in TWO
 * fields...", "The canonical form of a value..."). The library keeps one {@code documentation} per LANGUAGE for the library as a whole, but the
 * documentation of a FUNCTION lives only in the comment above it in the source (one language), so the texts of the functions the factory's
 * catalogue knows are carried by the bundle, keyed by library and function:
 * <pre>
 * capability.fn.&lt;Fasttrack.lib.names&gt;.&lt;sortedKeyOf&gt;.description
 * capability.fn.&lt;...&gt;.&lt;function&gt;.returns
 * capability.fn.&lt;...&gt;.&lt;function&gt;.param.&lt;parameterName&gt;
 * </pre>
 * Rule, per text: a TRANSLATION is a bundle text of the viewer's language that differs from the English one; when there is one it is shown, otherwise the
 * library's own comment is (an English viewer therefore always reads the library: it is English and always current, and the English bundle texts only
 * serve as the reference a translation is told apart from). A function the bundle does not
 * know keeps showing what its library says, never nothing. Pure: no EBX type.
 */
final class MatrixFunctionTexts
{
	private static final String PREFIX = "capability.fn.";

	private MatrixFunctionTexts()
	{
	}

	/** {@code library/Fasttrack/lib/names} (an IDE path) or {@code Fasttrack.lib.names} (an import name) to the import name; {@code ""} when blank. */
	static String importName(final String pLibrary)
	{
		if (pLibrary == null)
		{
			return "";
		}
		String name = pLibrary.trim();
		if (name.startsWith("library/"))
		{
			name = name.substring("library/".length());
		}
		return name.replace('/', '.');
	}

	/** The description of the function, in the viewer's language; {@code ""} when neither the bundle nor the library has one. */
	static String description(final MatrixModel.Computation pComputation, final Locale pLocale)
	{
		return MatrixFunctionTexts.pick(MatrixFunctionTexts.bundleText(pComputation, pLocale, "description"),
			pComputation == null || pComputation.doc == null ? "" : pComputation.doc.description);
	}

	/** The {@code @return} text of the function, in the viewer's language; {@code ""} when neither has one. */
	static String returns(final MatrixModel.Computation pComputation, final Locale pLocale)
	{
		return MatrixFunctionTexts.pick(MatrixFunctionTexts.bundleText(pComputation, pLocale, "returns"),
			pComputation == null || pComputation.doc == null ? "" : pComputation.doc.returns);
	}

	/** The text of one parameter, in the viewer's language; {@code ""} when neither has one. */
	static String param(final MatrixModel.Computation pComputation, final Locale pLocale, final String pParameter)
	{
		return MatrixFunctionTexts.pick(MatrixFunctionTexts.bundleText(pComputation, pLocale, "param." + pParameter),
			pComputation == null || pComputation.doc == null ? "" : pComputation.doc.paramText(pParameter));
	}

	/** The translation of one text for the viewer's language, or {@code ""} when there is none (unknown function, English viewer, a language without its own text). */
	private static String bundleText(final MatrixModel.Computation pComputation, final Locale pLocale, final String pPart)
	{
		if (pComputation == null || pComputation.function.isEmpty() || pComputation.library.isEmpty())
		{
			return "";
		}
		final String key = MatrixFunctionTexts.PREFIX + MatrixFunctionTexts.importName(pComputation.library) + "." + pComputation.function + "." + pPart;
		final String text = MatrixMessages.find(pLocale, key);
		if (text == null || text.trim().isEmpty() || text.equals(MatrixMessages.find(Locale.ENGLISH, key)))
		{
			return "";
		}
		return text.trim();
	}

	private static String pick(final String pTranslation, final String pLibrary)
	{
		return pTranslation.isEmpty() ? (pLibrary == null ? "" : pLibrary) : pTranslation;
	}
}
