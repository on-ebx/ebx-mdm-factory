package com.on.ebx.factory.transfer;

import java.util.Locale;

/**
 * Translates an OLD {@code AttributeMapping.transformation}/{@code argument} pair (docs/TARGET-MAPPING.md
 * sec 2.2) into a Connectors rule pipeline string ({@code com.on.ebx.connect.core.Transformations}, read
 * 2026-09-23 in {@code ~/Dev/wt/ebx-connectors-mapping-jar}) - the syntax {@code MappingEngine.compute} runs
 * through {@code TransferPlan.FieldPlan#pipeline}. Pure, no EBX class: {@code StagingLoader} is the only
 * caller.
 * <p>
 * <b>Why this exists at all</b>: {@code AttributeMapping.transformation}/{@code argument} stay the ONLY way
 * for every existing row and for the rule pass (docs/TARGET-MAPPING.md sec 2.2) - nothing about the stored
 * data changes. What changes is HOW {@code StagingLoader} executes a mapping: instead of calling the retired
 * {@code transfer.Transformations} directly, it always runs a pipeline through Connectors' engine, so a
 * hand-written {@code AttributeMapping.pipeline} (set directly, bypassing this class) and a translated one
 * behave identically. This class is the one-time translation step, run once per {@code FieldPlan} (never per
 * row - the answer never depends on the row).
 * <p>
 * <b>Translation table</b> (see the class as read: {@code NONE}/{@code TRIM}/{@code UPPER}/{@code LOWER}/
 * {@code PARSE_DATE}/{@code PARSE_DECIMAL}/{@code PARSE_BOOLEAN}/{@code TRANSCODE}/{@code CONSTANT}):
 * <ul>
 * <li>{@code NONE} -&gt; {@code ""} (empty pipeline: {@code Transformations.apply} returns the value
 * untouched, same as the old {@code NONE}).</li>
 * <li>{@code TRIM} -&gt; {@code trim}; {@code UPPER} -&gt; {@code trim|upper}; {@code LOWER} -&gt;
 * {@code trim|lower} (old TRIM/UPPER/LOWER all trim first).</li>
 * <li>{@code PARSE_DATE(pattern)} -&gt; {@code trim|date(pattern,outPattern)}, {@code outPattern} =
 * {@code yyyy-MM-dd} for a {@code DATE} target, {@code yyyy-MM-dd'T'HH:mm:ss} otherwise ({@code TIMESTAMP}
 * or an unrecognised target - mirrors the old class's own "defensively falls to the wider shape" rule). The
 * new {@code date} step formats the parsed value straight to that pattern as a STRING (unlike the old
 * {@code PARSE_DATE}, which produced a typed {@code java.util.Date}) - correct here because the target is an
 * xs string EBX types itself at write time (docs/TARGET-MAPPING.md sec 2.2), and {@code yyyy-MM-dd}/
 * {@code yyyy-MM-dd'T'HH:mm:ss} are exactly the xs:date/xs:dateTime lexical forms
 * {@code setValueFromXsString} expects. A blank/null pattern cannot be translated (the old class rejected
 * every row instead; a pipeline with no first argument would silently default to {@code dd/MM/yyyy} and
 * misparse instead of failing - worse) - throws {@link IllegalArgumentException}, failing the whole feed at
 * plan time rather than the old per-row reject; see the class's own final-report note.</li>
 * <li>{@code PARSE_DECIMAL(sep)} -&gt; {@code trim|replace(sep,.)|number} when {@code sep} is set and not
 * already {@code .}; {@code trim|number} otherwise (old defaults a blank separator to {@code .} too - no
 * error). <b>Two real, accepted divergences</b>, neither patched over with a second number parser here (the
 * design's own rule: never a second boolean/date/decimal parser outside Connectors' engine): (1) the new
 * {@code number} step never distinguishes an {@code INTEGER} target from a {@code DECIMAL} one - it always
 * yields a decimal string, with no fractional/overflow rejection; a fractional or out-of-range value the old
 * class rejected with a named reason now reaches {@code ValueContextForUpdate.setValueFromXsString} on an
 * {@code xs:int} field, which itself refuses it - still rejected, by {@code BatchRetry}'s per-row
 * EBX-refusal path, just with a less specific reason. (2) {@code number} STRIPS every character that is not
 * a digit/{@code .}/{@code -} before parsing (lot 0's own measurement), so {@code "12abc"} silently becomes
 * {@code 12} where the old class rejected the whole value as "not a valid number" - covered by the required
 * cross-engine test with that exact case.</li>
 * <li>{@code PARSE_BOOLEAN} -&gt; {@code trim|map(true=true;1=true;y=true;o=true;yes=true;oui=true;
 * false=false;0=false;n=false;no=false;non=false)} - deliberately NOT {@code trim|boolean}: lot 0's own
 * measurement (README of {@code ~/Dev/wt/ebx-connectors-mapping-jar}, "Mapping engine jar" table) is that
 * {@code boolean} answers {@code false} for ANY unrecognised word, never {@code null} and never a reject -
 * translating to it would make an invalid boolean SILENTLY BECOME {@code false} instead of failing, which is
 * worse than the divergence it would "fix". {@code map} is case-insensitive (like {@code boolean}) and
 * answers {@code null} on no match (no {@code *} fallback is given on purpose), so an unrecognised word
 * becomes an EMPTY value - a reject only when the attribute is mandatory, same family as any other
 * transformation that legitimately produces nothing. <b>Real, accepted divergence, still</b>: the old class
 * REJECTED an unrecognised word outright (mandatory or not); the translated pipeline only rejects it when the
 * attribute is mandatory - an optional boolean field with a garbage value now goes through as empty instead
 * of failing the row. Covered by the required cross-engine test with the exact case ({@code "maybe"}).</li>
 * <li>{@code TRANSCODE(json)} -&gt; {@code trim|map('K1=V1;K2=V2;*=DEFAULT')} - the JSON object's entries
 * flattened to {@code map}'s own {@code key=value} pairs joined by {@code ;}, the whole thing one single
 * pipeline argument (quoted, so an embedded {@code ,} never splits it - see {@link #arg}). A {@code "*"} JSON
 * entry becomes {@code map}'s own {@code *} fallback key, same semantics. <b>Real, accepted divergence</b>:
 * the old class matched a JSON key CASE-SENSITIVELY ({@code JsonNode.has}); {@code map} matches
 * case-INSENSITIVELY ({@code key.equalsIgnoreCase(s)}) - a transcode table with two keys differing only in
 * case (not a shape the rule pass or the language-model pass ever proposes) would behave differently. A
 * blank/null JSON argument cannot be translated (same "fail at plan time, not at every row" reasoning as
 * {@code PARSE_DATE}) - throws {@link IllegalArgumentException}.</li>
 * <li>{@code CONSTANT(v)} -&gt; {@code constant(v)}, {@code v} quoted (or {@code constant()}, no argument at
 * all, when the OLD argument is {@code null} - {@code Transformations.arg(a,0,null)} then returns its own
 * {@code null} default, exactly the old class's {@code Outcome.ok(pArgument)} with a null argument; a
 * present-but-empty old argument translates to {@code constant('')}, which the new step also reads back as
 * {@code ""} - verified against {@code Transformations}'s own {@code splitArgs}/{@code unquote} by hand,
 * 2026-09-23).</li>
 * </ul>
 * <p>
 * <b>Quoting</b> ({@link #arg}): every pipeline argument this class emits is wrapped in single quotes,
 * unconditionally - {@code Transformations.splitPipeline}/{@code splitArgs} track quote depth per character,
 * so a quoted argument's own commas/parentheses never split it, and {@code unquote} only ever strips the
 * FIRST and LAST character of an argument that both starts and ends with {@code '} - so wrapping an argument
 * that already contains balanced embedded quotes (the {@code yyyy-MM-dd'T'HH:mm:ss} output pattern, which
 * this class deliberately leaves UNQUOTED - see {@link #DATE_PATTERN}/{@link #DATETIME_PATTERN}) would still
 * round-trip correctly, but this class never needs to rely on that: the two fixed date-out-patterns are the
 * only arguments with embedded quotes, and they contain no comma, so they are emitted bare. <b>Known
 * limitation</b>, never worked around here: an argument containing a literal {@code '} character of its own
 * (an exotic decimal separator, a transcode value with an apostrophe) has no escape mechanism in Connectors'
 * simple quoting - the same limitation that syntax has for every other caller of the pipeline, not something
 * specific to this translation.
 */
public final class PipelineCompat
{
	/** {@code MappingEngine.compute}'s own xs:date lexical form - what {@code setValueFromXsString} expects for a {@code DATE} target. */
	static final String DATE_PATTERN = "yyyy-MM-dd";
	/** xs:dateTime lexical form (no zone: EBX stores a local date-time) - the {@code TIMESTAMP}, and defensively any other, target. */
	static final String DATETIME_PATTERN = "yyyy-MM-dd'T'HH:mm:ss";

	private PipelineCompat()
	{
	}

	/**
	 * @param pTransformation {@code NONE}/{@code TRIM}/{@code UPPER}/{@code LOWER}/{@code PARSE_DATE}/
	 *            {@code PARSE_DECIMAL}/{@code PARSE_BOOLEAN}/{@code TRANSCODE}/{@code CONSTANT}
	 *            (case-insensitive; blank reads as {@code NONE}, exactly like the retired {@code
	 *            transfer.Transformations#apply}).
	 * @param pArgument meaning depends on {@code pTransformation} - see the class Javadoc.
	 * @param pTargetDataType the staging attribute's own {@code dataType}; only {@code PARSE_DATE} reads it
	 *            (to pick {@link #DATE_PATTERN} vs {@link #DATETIME_PATTERN}).
	 * @return a Connectors pipeline string, never null (empty for {@code NONE}).
	 * @throws IllegalArgumentException the transformation needs an argument this call did not supply
	 *             ({@code PARSE_DATE} with no pattern, {@code TRANSCODE} with no/blank JSON), or names a
	 *             transformation this translator does not know.
	 */
	public static String translate(final String pTransformation, final String pArgument, final String pTargetDataType)
	{
		final String transform = pTransformation == null || pTransformation.trim().isEmpty() ? "NONE" : pTransformation.trim().toUpperCase(Locale.ROOT);
		if ("NONE".equals(transform))
		{
			return "";
		}
		if ("TRIM".equals(transform))
		{
			return "trim";
		}
		if ("UPPER".equals(transform))
		{
			return "trim|upper";
		}
		if ("LOWER".equals(transform))
		{
			return "trim|lower";
		}
		if ("PARSE_DATE".equals(transform))
		{
			if (pArgument == null || pArgument.trim().isEmpty())
			{
				throw new IllegalArgumentException("PARSE_DATE has no date pattern configured: cannot translate to a pipeline");
			}
			final String outPattern = "DATE".equals(upper(pTargetDataType)) ? PipelineCompat.DATE_PATTERN : PipelineCompat.DATETIME_PATTERN;
			// the out-pattern is emitted bare (see the class Javadoc's "Quoting" section: no comma, safe either way)
			return "trim|date(" + PipelineCompat.arg(pArgument.trim()) + "," + outPattern + ")";
		}
		if ("PARSE_DECIMAL".equals(transform))
		{
			final String separator = pArgument == null || pArgument.trim().isEmpty() ? "." : pArgument.trim();
			return ".".equals(separator) ? "trim|number" : "trim|replace(" + PipelineCompat.arg(separator) + ",.)|number";
		}
		if ("PARSE_BOOLEAN".equals(transform))
		{
			// deliberately NOT trim|boolean - see the class Javadoc's PARSE_BOOLEAN entry (boolean silently
			// answers false for any unrecognised word; map answers null, a reject only when mandatory)
			return "trim|map(true=true;1=true;y=true;o=true;yes=true;oui=true;false=false;0=false;n=false;no=false;non=false)";
		}
		if ("TRANSCODE".equals(transform))
		{
			return "trim|map(" + PipelineCompat.arg(PipelineCompat.transcodeToMapArgument(pArgument)) + ")";
		}
		if ("CONSTANT".equals(transform))
		{
			return pArgument == null ? "constant()" : "constant(" + PipelineCompat.arg(pArgument) + ")";
		}
		throw new IllegalArgumentException("PipelineCompat does not know how to translate transformation '" + pTransformation + "'");
	}

	/**
	 * What EBX itself would BLOCK a save on, on the xs-STRING {@code MappingEngine.compute} now hands back -
	 * moved here from the retired {@code transfer.Transformations#checkConstraints} verbatim (same two
	 * checks, same {@code null}-vs-empty-string quirk: a present-but-empty string, e.g. a
	 * {@code CONSTANT('')} translation, is NOT "empty" for the mandatory check - only an actual {@code null}
	 * is; see the class Javadoc's {@code CONSTANT} entry for why a translated pipeline can legitimately
	 * produce {@code ""}).
	 */
	public static String checkConstraints(final String pAttributeName, final String pValue, final Integer pMaxLength, final boolean pMandatory)
	{
		if (pValue == null)
		{
			return pMandatory ? pAttributeName + ": mandatory but empty" : null;
		}
		if (pMaxLength != null && pValue.length() > pMaxLength.intValue())
		{
			return pAttributeName + ": " + pValue.length() + " characters, maximum " + pMaxLength;
		}
		return null;
	}

	// -------------------------------------------------------------------------------------------------------- helpers

	/**
	 * A {@code {"M":"MALE","F":"FEMALE","*":"UNKNOWN"}} JSON object (the old {@code TRANSCODE} argument
	 * shape) flattened to {@code map}'s own {@code key=value;key=value} syntax - a tiny hand-rolled JSON
	 * object reader, not a second copy of the retired class's parser: this only ever reads a JSON OBJECT of
	 * STRING values (what {@code StagingRules}/{@code StagingLlmSuggester} ever write for a TRANSCODE
	 * argument), never nested structures.
	 */
	private static String transcodeToMapArgument(final String pArgumentJson)
	{
		if (pArgumentJson == null || pArgumentJson.trim().isEmpty())
		{
			throw new IllegalArgumentException("TRANSCODE has no transcoding table configured: cannot translate to a pipeline");
		}
		final String trimmed = pArgumentJson.trim();
		if (!trimmed.startsWith("{") || !trimmed.endsWith("}"))
		{
			throw new IllegalArgumentException("TRANSCODE argument is not a JSON object: '" + pArgumentJson + "'");
		}
		final String inner = trimmed.substring(1, trimmed.length() - 1).trim();
		final StringBuilder out = new StringBuilder();
		if (!inner.isEmpty())
		{
			for (final String rawEntry : PipelineCompat.splitJsonEntries(inner))
			{
				final int colon = rawEntry.indexOf(':');
				if (colon < 0)
				{
					continue;
				}
				final String key = PipelineCompat.unquoteJsonString(rawEntry.substring(0, colon).trim());
				final String value = PipelineCompat.unquoteJsonString(rawEntry.substring(colon + 1).trim());
				if (out.length() > 0)
				{
					out.append(';');
				}
				out.append(key).append('=').append(value);
			}
		}
		return out.toString();
	}

	/** Top-level comma split of a flat JSON object's inner text (no nested object/array is ever a TRANSCODE value). */
	private static java.util.List<String> splitJsonEntries(final String pInner)
	{
		final java.util.List<String> out = new java.util.ArrayList<String>();
		boolean inString = false;
		int start = 0;
		for (int i = 0; i < pInner.length(); i++)
		{
			final char c = pInner.charAt(i);
			if (c == '"' && (i == 0 || pInner.charAt(i - 1) != '\\'))
			{
				inString = !inString;
			}
			else if (c == ',' && !inString)
			{
				out.add(pInner.substring(start, i));
				start = i + 1;
			}
		}
		out.add(pInner.substring(start));
		return out;
	}

	private static String unquoteJsonString(final String pText)
	{
		final String trimmed = pText.trim();
		if (trimmed.length() >= 2 && trimmed.startsWith("\"") && trimmed.endsWith("\""))
		{
			return trimmed.substring(1, trimmed.length() - 1).replace("\\\"", "\"").replace("\\\\", "\\");
		}
		return trimmed;
	}

	/** Wraps one pipeline argument in single quotes, unconditionally - see the class Javadoc's "Quoting" section. */
	private static String arg(final String pValue)
	{
		return "'" + pValue + "'";
	}

	private static String upper(final String pText)
	{
		return pText == null ? "" : pText.trim().toUpperCase(Locale.ROOT);
	}
}
