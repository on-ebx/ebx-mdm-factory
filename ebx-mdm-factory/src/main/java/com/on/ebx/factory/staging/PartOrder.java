package com.on.ebx.factory.staging;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Splitting a multi-token value whose ORDER cannot be inferred (lot {@code feat/name-order-unknown}, Vol100kN
 * 2026-10-12). {@code LOYALTY.MEMBER.FULL_NAME} held the whole name first-name-first in half of the rows and
 * last-name-first in the others, with no marker: 92% of the values had one casing layout, 8% were all upper case. The
 * model reused the casing-based split it had proposed for another source and took the LAST token as the first name
 * whenever nothing was upper-cased - wrong on half the rows, and the earlier order-aware check
 * ({@link StagingLlmPass#orderRefusal}) said nothing because it only covers columns written in TWO case layouts.
 * <p>
 * Pure and domain-free: it measures whether a rule's output is a strict part of a multi-token input, and decides
 * whether a rule that does so reads a column whose {@link TokenCase#orderUnknown order is unknown}. It never says what
 * the parts are; the answer ("keep the full value and compare it with an order-independent key") is the person's.
 */
public final class PartOrder
{
	/** A rule counts as a SPLIT of the column when its output is a strict part of the input in at least this share (percent) of the multi-token filled inputs. */
	public static final int SPLIT_MIN_PERCENT = 50;

	private PartOrder()
	{
	}

	/**
	 * Whether {@code pOutput} is a strict, contiguous run of the tokens of {@code pInput} (case, accents, punctuation
	 * ignored): {@code Anne Marie Dupont} to {@code Dupont} or to {@code Anne Marie}; not {@code Anne Marie Dupont}
	 * to itself, to an empty text or to text that is not in the input.
	 */
	public static boolean isStrictTokenPart(final String pInput, final String pOutput)
	{
		final List<String> input = PartOrder.folded(pInput);
		final List<String> output = PartOrder.folded(pOutput);
		if (output.isEmpty() || output.size() >= input.size())
		{
			return false;
		}
		for (int start = 0; start + output.size() <= input.size(); start++)
		{
			if (input.subList(start, start + output.size()).equals(output))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code {parts, multiTokenInputs}}: among the filled inputs of two tokens or more of {@code pOutputByInput} (input
	 * to what the rule made of it, null or blank when emptied), how many came out as a strict part of the input.
	 */
	public static int[] splitCounts(final Map<String, String> pOutputByInput)
	{
		int parts = 0;
		int multi = 0;
		if (pOutputByInput != null)
		{
			for (final Map.Entry<String, String> entry : pOutputByInput.entrySet())
			{
				if (TokenCase.tokens(entry.getKey()).size() < 2)
				{
					continue;
				}
				multi++;
				if (entry.getValue() != null && PartOrder.isStrictTokenPart(entry.getKey(), entry.getValue()))
				{
					parts++;
				}
			}
		}
		return new int[] { parts, multi };
	}

	/** Pure arithmetic of {@link #SPLIT_MIN_PERCENT}. */
	public static boolean isSplit(final int pParts, final int pMultiTokenInputs)
	{
		return pMultiTokenInputs > 0 && pParts * 100 >= SPLIT_MIN_PERCENT * pMultiTokenInputs;
	}

	/**
	 * Whether a rule applied to {@code pInputCase}'s column splits a value whose order is unknown: the column is
	 * {@link TokenCase#orderUnknown} AND the rule reads a strict part of most multi-token values. A rule that keeps
	 * the whole value, or builds something else, is not one.
	 */
	public static boolean splitsUnknownOrder(final TokenCase pInputCase, final Map<String, String> pOutputByInput)
	{
		if (pInputCase == null || !pInputCase.orderUnknown())
		{
			return false;
		}
		final int[] counts = PartOrder.splitCounts(pOutputByInput);
		return PartOrder.isSplit(counts[0], counts[1]);
	}

	/** Whether a pipeline holds a {@code regex(...)} step - the Connectors step that takes a part of a value. A constant is never a split. */
	public static boolean hasRegexStep(final String pPipeline)
	{
		if (pPipeline == null)
		{
			return false;
		}
		final String lower = pPipeline.trim().toLowerCase(Locale.ROOT);
		return !lower.startsWith("constant(") && (lower.startsWith("regex(") || lower.contains("|regex(") || lower.contains("| regex("));
	}

	/**
	 * The text recorded in the run outcome ({@code tokenOrderDecisions[]} and the {@code PipelineStep} message) for a
	 * rule that splits a column whose order is unknown.
	 */
	public static String decision(final String pRuleName, final String pPipeline, final TokenCase pInputCase, final Map<String, String> pOutputByInput)
	{
		final int[] counts = PartOrder.splitCounts(pOutputByInput);
		return pRuleName + " " + pPipeline + ": order unknown (" + pInputCase.renderLayouts() + "; the rule takes a part of the value in " + counts[0] + " of "
			+ counts[1] + " multi-token values, so the rows written in the other order are likely wrong - keep the full value and compare it with an order-independent key)";
	}

	private static List<String> folded(final String pValue)
	{
		final List<String> out = new java.util.ArrayList<String>();
		for (final String token : TokenCase.tokens(pValue))
		{
			final String folded = TokenCase.foldedLetters(token);
			if (!folded.isEmpty())
			{
				out.add(folded);
			}
		}
		return out;
	}
}
