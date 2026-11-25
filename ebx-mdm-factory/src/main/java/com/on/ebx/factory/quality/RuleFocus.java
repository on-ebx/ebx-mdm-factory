package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Desk lot L4b (2026-10-22): the quality LLM pass NARROWED to one existing rule - what the desk's "Measure" button asks the model once the breakdown is stored. Not a second
 * prompt: the pass's own prompt (the column, its profile, the existing rule with its breakdown, the system prompt's rule about conditions) plus {@link #promptBlock}, which says
 * this run is for that rule alone; the session refuses everything else ({@link #proposalProblem}, {@link #REFUSAL_STEP}). The model proposes the condition or says none applies;
 * Java measured the breakdown and checks the answer ({@code Session#readCondition}), it never picks a value.
 * <p>
 * Also carries the run's outcome back to the caller ({@link #asked}, {@link #conditionAttribute}, {@link #note}), filled by the EBX-bound code; pure otherwise.
 */
public final class RuleFocus
{
	/** The refusal text of a cleaning step in a narrowed run. */
	public static final String REFUSAL_STEP = "this run is only about one existing quality rule: propose no cleaning step.";

	public final String attributeId;
	public final String kind;
	public final String argument;

	/** Outcome: whether the model was asked (the run started and ended normally). */
	public boolean asked;
	/** Outcome: why it was not asked, a short sentence; {@code null} when it was. */
	public String notAskedReason;
	/** Outcome: the condition the model proposed (validated and written on the rule), or {@code null}. */
	public String conditionAttribute;
	public List<String> conditionValues = Collections.emptyList();
	/** Outcome: what the model said when it proposed nothing, or {@code null}. */
	public String note;

	public RuleFocus(final String pAttributeId, final String pKind, final String pArgument)
	{
		this.attributeId = pAttributeId;
		this.kind = pKind;
		this.argument = pArgument;
	}

	/**
	 * Why the model cannot be asked at this data-sharing level, or {@code null} when it can: a condition names values of the grouping field, and the prompt only lists them when the
	 * project shares real values ({@code FailureBreakdown#promptText}); a sensitive column never reaches the model at all.
	 */
	public static String whyNotAsked(final QualityLlmPass.Mode pMode, final boolean pSensitive)
	{
		if (pSensitive)
		{
			return "the column is sensitive for this instance, so it is never shown to the model";
		}
		if (pMode != QualityLlmPass.Mode.SAMPLE_VALUES)
		{
			return "the data-sharing level of the project does not let the model see the values of the other field, so it cannot name a condition";
		}
		return null;
	}

	/** The refusal for a call that is not "this rule, given a condition", or {@code null} when the call is that. */
	public String proposalProblem(final String pKind, final String pArgument, final String pConditionAttribute)
	{
		if (!Objects.equals(this.kind, pKind) || !Objects.equals(this.argument, pArgument))
		{
			return "this run is only about the existing " + this.kind + " rule" + (this.argument == null ? "" : " (" + this.argument + ")")
				+ ": propose that rule with a condition, or call note if no condition applies.";
		}
		if (pConditionAttribute == null || pConditionAttribute.trim().isEmpty())
		{
			return "this run exists to give the existing rule a condition: give conditionAttribute and conditionValues, or call note if no condition applies.";
		}
		return null;
	}

	/** The instruction appended to the ordinary user prompt for the column {@code pEntity.pAttribute}. */
	public String promptBlock(final String pEntity, final String pAttribute)
	{
		final String column = pEntity + "." + pAttribute;
		return "THIS RUN IS NARROWED to one question about " + column + ": its existing " + this.kind + " rule" + (this.argument == null ? "" : " with argument " + this.argument)
			+ " was measured where it fails (the failure breakdown listed under the column above). Read it. If the failures are concentrated in some values of another field, "
			+ "call propose_quality_rule ONCE for that same rule (same kind and argument, severity WARN) with conditionAttribute and conditionValues taken from the breakdown: "
			+ "the rule then applies only to those values. If no condition applies - the failures are spread, or they are real errors of the data - call note ONCE with "
			+ "{attribute: \"" + pAttribute + "\", reason} saying why in one short sentence. Propose nothing else: no cleaning step, no other rule.";
	}

	/** The first note of a session's notes ("entity.attribute: reason") reduced to the reason, or {@code null}. */
	public static String reasonOf(final List<String> pNotes)
	{
		if (pNotes == null)
		{
			return null;
		}
		final List<String> kept = new ArrayList<String>();
		for (final String note : pNotes)
		{
			if (note == null || note.startsWith("Run stopped early:"))
			{
				continue;
			}
			final int colon = note.indexOf(": ");
			kept.add(colon > 0 && colon < 80 ? note.substring(colon + 2) : note);
		}
		return kept.isEmpty() ? null : kept.get(0).trim();
	}
}
