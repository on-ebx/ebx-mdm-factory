package com.on.ebx.factory.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.factory.core.PromptData;
import com.on.ebx.factory.quality.Shapes;

/**
 * The prompts of the two example agents (agentic framework lot, 2026-10-30). Pure: what the model reads is built from the JSON the behaviour gathered,
 * which already follows the project's language-model data policy ({@link #share}). The record data sits inside a {@link PromptData} fence, and every
 * system prompt carries {@link PromptData#SYSTEM_NOTICE}.
 */
public final class AgentPrompts
{
	/** How a value reaches the prompt: the value itself, its shape, or nothing ({@code LlmDataPolicy.Level}, read by the caller). */
	public enum Sharing
	{
		VALUES, SHAPES, NONE
	}

	/** The answer format every behaviour asks for ({@link AgentDecisions}). */
	static final String ANSWER_FORMAT = "Answer with ONE JSON object and nothing else: {\"decisions\": [{\"target\": \"<the id of the item>\", \"action\": \"<an action>\", "
		+ "\"arguments\": {...}, \"confidence\": <0-100>, \"rationale\": \"<one sentence>\"}]}. One decision per item at most. When you cannot decide an item, answer "
		+ "its action ABSTAIN rather than guess: a person reviews what you abstain on. Confidence is how sure you are that a careful data steward would take the "
		+ "same decision; your decision may be applied without review when it is high.";

	private AgentPrompts()
	{
	}

	/** Pure: what a value becomes in a prompt. A sensitive field never shows anything but whether it is filled. */
	public static String share(final Sharing pSharing, final boolean pSensitive, final String pValue)
	{
		if (pValue == null || pValue.isEmpty())
		{
			return "";
		}
		if (pSensitive || pSharing == Sharing.NONE)
		{
			return "*";
		}
		if (pSharing == Sharing.SHAPES)
		{
			return Shapes.of(pValue);
		}
		return pValue.length() > 200 ? pValue.substring(0, 200) : pValue;
	}

	/** The data steward agent: one decision per suspect pair. */
	public static String stewardSystem(final String pPurpose, final String pInstructions, final Sharing pSharing)
	{
		final StringBuilder out = new StringBuilder();
		out.append("You are a data steward agent of an MDM hub. Match and Merge found SUSPECT pairs: two master records that may describe the same real-world "
			+ "party. For each pair decide MERGE (the same party: the suspect joins the golden record's group) or DISTINCT (two different parties), or ABSTAIN. "
			+ "Each pair lists its fields with a verdict computed by the hub (EQ equal, FUZZY close, DIFF different, MISSING empty on one side)");
		out.append(pSharing == Sharing.VALUES ? " and both values." : pSharing == Sharing.SHAPES
			? " and the SHAPE of both values (letters as a/A, digits as 9): you do not see the values themselves." : ", without the values.");
		out.append(" A field shown as * is sensitive: only whether it is filled is shown. Merging two different parties is far worse than leaving a true pair "
			+ "pending: be conservative, MERGE only on agreeing identifiers or a strong agreement of name and address. Arguments: none.\n");
		AgentPrompts.appendPurpose(out, pPurpose, pInstructions);
		out.append(AgentPrompts.ANSWER_FORMAT);
		out.append(PromptData.SYSTEM_NOTICE);
		return out.toString();
	}

	/** The quality agent: one correction per finding of the validation report. */
	public static String qualitySystem(final String pPurpose, final String pInstructions, final Sharing pSharing)
	{
		final StringBuilder out = new StringBuilder();
		out.append("You are a data quality agent of an MDM hub. EBX validation flagged the records below; each finding names the record, the field, the message "
			+ "of the failed check and the field's current value");
		out.append(pSharing == Sharing.VALUES ? "." : pSharing == Sharing.SHAPES ? " as a SHAPE (letters as a/A, digits as 9), never the value itself."
			: " - here without the value.");
		out.append(" Other fields of the record are given for context. For each finding propose CORRECT with arguments {\"value\": \"<the corrected value of that "
			+ "field>\"} when the correction follows from the data itself (a format to fix, a value another field of the same record proves), or ABSTAIN. Never "
			+ "invent a value: a correction that is a guess is worse than none. A field shown as * is sensitive: ABSTAIN on it.");
		if (pSharing != Sharing.VALUES)
		{
			out.append(" Without the values you can rarely write a correction: ABSTAIN unless the correct value is fully determined by the shape and the message.");
		}
		out.append('\n');
		AgentPrompts.appendPurpose(out, pPurpose, pInstructions);
		out.append(AgentPrompts.ANSWER_FORMAT);
		out.append(PromptData.SYSTEM_NOTICE);
		return out.toString();
	}

	/** The task: the gathered items, fenced. */
	public static String task(final String pLabel, final JsonNode pItems)
	{
		return PromptData.fence(pLabel, pItems == null ? "[]" : pItems.toString()) + "\n\nDecide now; answer with the JSON object only.";
	}

	private static void appendPurpose(final StringBuilder pOut, final String pPurpose, final String pInstructions)
	{
		if (pPurpose != null && !pPurpose.trim().isEmpty())
		{
			pOut.append("Purpose of this agent, set by its owner: ").append(pPurpose.trim()).append('\n');
		}
		if (pInstructions != null && !pInstructions.trim().isEmpty())
		{
			pOut.append("House rules of the owner: ").append(pInstructions.trim()).append('\n');
		}
	}
}
