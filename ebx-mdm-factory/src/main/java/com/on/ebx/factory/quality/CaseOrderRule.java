package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The structural test behind "a case change must not run BEFORE a regex that reads letter case" (lot M8b, 2026-10-04; found by the 1-million-row
 * volume project Vol1m and the 100,000-row Vol100k): a mapping's cleaning steps ({@code MappingStep}) run on the raw text BEFORE the mapping's own
 * pipeline ({@code AttributeMapping.pipeline}, {@code docs/TARGET-MAPPING.md} sec 3.3 step 1). The language model proposed, for a source whose full
 * name sits in one column ({@code Aurélie-Sophie Navret}, {@code SALNIER Nathalie-Julie}), a last-name pipeline whose pattern tells the token in
 * capitals from the token holding a lower-case letter - and, in the quality pass, an {@code UPPER} cleaning step on the same mapping. The step ran
 * first, every token became a token in capitals, the pattern fell on its first branch and returned the FIRST token as the last name on 153,937 rows.
 * The correct form puts the case change at the END of the pipeline: {@code regex(...)|upper}.
 * <p>
 * Purely structural, never semantic: it reads the TEXT of a pipeline and the KIND of a step, never a value, and does not guess what the regex is
 * for. A pattern "reads letter case" when it contains a Unicode or POSIX case class ({@code \p{Lu}}, {@code \p{Ll}}, {@code \p{Upper}}...) or an
 * ASCII range that spans one ({@code [A-Z]}, {@code [a-z]}, {@code [A-Za-z]}). A step "changes letter case" when it is {@link
 * CleaningSteps#UPPER}, {@link CleaningSteps#LOWER} or {@link CleaningSteps#NORMALISE_IDENTIFIER} (which upper-cases). The test is deliberately
 * conservative in one direction only: a lower-casing step in front of a pattern that only looks for lower-case letters would be harmless, and is
 * refused all the same - the fix (case change last) is always right and costs nothing.
 * <p>
 * The pipeline is cut into steps and arguments exactly as Connectors' {@code Transformations.apply} cuts it (read 2026-10-04: split on {@code |}
 * outside parentheses, the arguments of a step are what lies between its first {@code (} and its last {@code )}, split on commas outside single
 * quotes, the first argument of {@code regex} being the pattern, unquoted) - mirrored here, not shared, because the Connectors classes are not on
 * this module's compile path. Pure: no EBX class - unit-tested without a running instance.
 */
public final class CaseOrderRule
{
	/** A pattern that names a case class: {@code \p{Lu}}, {@code \p{Ll}}, {@code \P{Lu}}, {@code \p{Upper}}, {@code \p{IsLowercase}}, {@code \p{javaUpperCase}}... */
	private static final Pattern CASE_CLASS = Pattern.compile("\\\\[pP]\\{(?:Is)?(?:Lu|Ll|Lt|Upper|Lower|Uppercase|Lowercase|Titlecase|javaUpperCase|javaLowerCase"
		+ "|Uppercase_Letter|Lowercase_Letter|Titlecase_Letter)\\}");

	/** A character class holding an ASCII range that spans case: {@code [A-Z]}, {@code [^a-z]}, {@code [A-Za-z0-9]}... */
	private static final Pattern CASE_RANGE = Pattern.compile("\\[[^\\]]*(?:A-Z|a-z)");

	private CaseOrderRule()
	{
	}

	/** Whether a cleaning step of this kind changes the letter case of the value ({@code UPPER}, {@code LOWER}, {@code NORMALISE_IDENTIFIER}); case-insensitive, {@code null} reads false. */
	public static boolean changesCase(final String pStepKind)
	{
		if (pStepKind == null)
		{
			return false;
		}
		final String kind = pStepKind.trim().toUpperCase(Locale.ROOT);
		return CleaningSteps.UPPER.equals(kind) || CleaningSteps.LOWER.equals(kind) || CleaningSteps.TITLE_CASE.equals(kind)
			|| CleaningSteps.NORMALISE_IDENTIFIER.equals(kind);
	}

	/** Whether a Connectors pipeline step of this name changes the letter case of the value ({@code upper}, {@code lower}, {@code capitalize}); lower-cased name expected, trimmed here. */
	public static boolean pipelineStepChangesCase(final String pStepName)
	{
		if (pStepName == null)
		{
			return false;
		}
		final String name = pStepName.trim().toLowerCase(Locale.ROOT);
		return "upper".equals(name) || "lower".equals(name) || "capitalize".equals(name);
	}

	/** Whether this regex text reads letter case (see the class Javadoc). {@code null} or blank reads false. */
	public static boolean patternReadsCase(final String pPattern)
	{
		if (pPattern == null || pPattern.isEmpty())
		{
			return false;
		}
		return CaseOrderRule.CASE_CLASS.matcher(pPattern).find() || CaseOrderRule.CASE_RANGE.matcher(pPattern).find();
	}

	/** The pattern (first argument, unquoted) of every {@code regex(...)} step of the pipeline, in order; empty when there is none. */
	public static List<String> regexPatterns(final String pPipeline)
	{
		final List<String> out = new ArrayList<String>();
		for (final String step : CaseOrderRule.steps(pPipeline))
		{
			if ("regex".equals(CaseOrderRule.nameOf(step)))
			{
				final List<String> args = CaseOrderRule.argumentsOf(step);
				if (!args.isEmpty())
				{
					out.add(args.get(0));
				}
			}
		}
		return out;
	}

	/** Whether any {@code regex(...)} step of the pipeline has a pattern that reads letter case. */
	public static boolean pipelineRegexReadsCase(final String pPipeline)
	{
		for (final String pattern : CaseOrderRule.regexPatterns(pPipeline))
		{
			if (CaseOrderRule.patternReadsCase(pattern))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Why a cleaning step of kind {@code pStepKind} must not be proposed on a mapping that has one of {@code pPipelines}, or {@code null}: the step
	 * changes letter case and a pipeline holds a {@code regex(...)} that reads it.
	 *
	 * @param pLabel the column as the model knows it ({@code Personne.lastName}), for the message
	 * @param pPipelines the pipelines of the mappings that feed the column; {@code null} and blank entries are ignored
	 */
	public static String stepRefusal(final String pLabel, final String pStepKind, final List<String> pPipelines)
	{
		if (!CaseOrderRule.changesCase(pStepKind) || pPipelines == null)
		{
			return null;
		}
		for (final String pipeline : pPipelines)
		{
			if (pipeline != null && CaseOrderRule.pipelineRegexReadsCase(pipeline))
			{
				return "the cleaning step " + pStepKind.trim().toUpperCase(Locale.ROOT) + " changes letter case, and the mapping of " + pLabel + " has a pipeline whose "
					+ "regex reads letter case ('" + pipeline + "'). Cleaning steps run on the raw text BEFORE the mapping's own pipeline, so the regex would "
					+ "see the changed text and its case test could no longer tell one token from another (measured on a volume test: the pattern returned the "
					+ "first token as the last name on 153,937 rows). Do not propose this step on " + pLabel + ": if the case change is wanted, it belongs at "
					+ "the END of the pipeline - regex(...)|upper, not as a cleaning step - and that is the staging proposal's to write, not a step's.";
			}
		}
		return null;
	}

	/**
	 * Why a pipeline must not be proposed, or {@code null}: a step that changes letter case ({@code upper}, {@code lower}, {@code capitalize})
	 * stands BEFORE a {@code regex(...)} step that reads letter case, in the SAME pipeline - the same defect as {@link #stepRefusal} inside one
	 * pipeline.
	 */
	public static String pipelineRefusal(final String pPipeline)
	{
		final List<String> steps = CaseOrderRule.steps(pPipeline);
		String caseChange = null;
		for (final String step : steps)
		{
			final String name = CaseOrderRule.nameOf(step);
			if (CaseOrderRule.pipelineStepChangesCase(name))
			{
				if (caseChange == null)
				{
					caseChange = name;
				}
			}
			else if ("regex".equals(name) && caseChange != null)
			{
				final List<String> args = CaseOrderRule.argumentsOf(step);
				if (!args.isEmpty() && CaseOrderRule.patternReadsCase(args.get(0)))
				{
					return "pipeline '" + pPipeline.trim() + "' changes letter case with " + caseChange + " BEFORE a regex that reads letter case: the regex would "
						+ "see the changed text and its case test could no longer tell one token from another. Put the case change at the END of the "
						+ "pipeline: regex(...)|" + caseChange + ", not " + caseChange + "|regex(...).";
				}
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------- pipeline reading

	/** The steps of a pipeline, split on {@code |} outside parentheses - {@code Transformations.splitPipeline}. Blank steps are dropped. */
	static List<String> steps(final String pPipeline)
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return Collections.emptyList();
		}
		final List<String> out = new ArrayList<String>();
		int depth = 0;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < pPipeline.length(); i++)
		{
			final char c = pPipeline.charAt(i);
			if (c == '(')
			{
				depth++;
			}
			else if (c == ')')
			{
				depth--;
			}
			if (c == '|' && depth == 0)
			{
				out.add(current.toString());
				current = new StringBuilder();
			}
			else
			{
				current.append(c);
			}
		}
		out.add(current.toString());
		final List<String> nonBlank = new ArrayList<String>();
		for (final String step : out)
		{
			if (!step.trim().isEmpty())
			{
				nonBlank.add(step.trim());
			}
		}
		return nonBlank;
	}

	/** The lower-cased name of a step: the text before its first {@code (}. */
	static String nameOf(final String pStep)
	{
		final int open = pStep.indexOf('(');
		return (open < 0 ? pStep : pStep.substring(0, open)).trim().toLowerCase(Locale.ROOT);
	}

	/** The arguments of a step - between its first {@code (} and its last {@code )}, split on commas outside single quotes, each trimmed and unquoted. */
	static List<String> argumentsOf(final String pStep)
	{
		final int open = pStep.indexOf('(');
		if (open < 0)
		{
			return Collections.emptyList();
		}
		final int close = pStep.lastIndexOf(')');
		final String inner = pStep.substring(open + 1, close < open ? pStep.length() : close);
		final List<String> raw = new ArrayList<String>();
		boolean quoted = false;
		StringBuilder current = new StringBuilder();
		for (int i = 0; i < inner.length(); i++)
		{
			final char c = inner.charAt(i);
			if (c == '\'')
			{
				quoted = !quoted;
				current.append(c);
			}
			else if (c == ',' && !quoted)
			{
				raw.add(current.toString());
				current = new StringBuilder();
			}
			else
			{
				current.append(c);
			}
		}
		if (current.length() > 0 || !raw.isEmpty())
		{
			raw.add(current.toString());
		}
		final List<String> out = new ArrayList<String>();
		for (final String argument : raw)
		{
			final String trimmed = argument.trim();
			out.add(trimmed.length() >= 2 && trimmed.startsWith("'") && trimmed.endsWith("'") ? trimmed.substring(1, trimmed.length() - 1) : trimmed);
		}
		return out;
	}
}
