package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Whether a JSON text the factory later PARSES back fits the fixed-length field it is about to be stored in -
 * and, when it does not, the plain words for the refusal. Pure: no EBX class.
 * <p>
 * Why this exists (live incident, 2026-10-12 20:40, project Vol100kM): the Match and Merge plan of a Person
 * entity (14 decision nodes) was cut by {@code TextLimits.truncate} to the 4000 characters of {@code
 * MatchPlan.requirements}, ending with the display marker {@code ...(truncated)}: unparseable JSON that
 * {@code matching/configure} could not use and the review screen could not draw. Cutting is fine for text a
 * person only reads ({@code PipelineStep.message}); for JSON that code reads back, a cut value is worse than no
 * value, so the writer REFUSES it ({@link #refusal}) and keeps what was there. The limits below are the {@code
 * maxLen} of {@code tools/build_xsd.py} (keep in step - {@code StoredJsonFitTest} states them).
 */
public final class StoredJsonFit
{
	/** {@code MatchPlan.requirements} and {@code MatchPlan.previousRequirements}: a 100-node tree is about 30 000 characters. */
	public static final int REQUIREMENTS_MAX_LENGTH = 100000;
	/** {@code MatchPlan.evidence}. */
	public static final int EVIDENCE_MAX_LENGTH = 100000;
	/** {@code CapabilitySuggestion.binding}. */
	public static final int BINDING_MAX_LENGTH = 4000;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private StoredJsonFit()
	{
	}

	/** True when {@code pJson} (null counts as empty) is no longer than {@code pMaxLength}. */
	public static boolean fits(final String pJson, final int pMaxLength)
	{
		return pJson == null || pJson.length() <= pMaxLength;
	}

	/**
	 * How many nodes a stored plan holds: {@code decisionTree.nodes}, or the old {@code tiers} array of a plan written before the tree;
	 * 0 when the text is not a plan (unparseable, or neither key).
	 */
	public static int planNodeCount(final String pRequirementsJson)
	{
		if (pRequirementsJson == null || pRequirementsJson.isEmpty())
		{
			return 0;
		}
		try
		{
			final JsonNode root = StoredJsonFit.MAPPER.readTree(pRequirementsJson);
			final JsonNode nodes = root.path("decisionTree").path("nodes");
			if (nodes.isArray())
			{
				return nodes.size();
			}
			final JsonNode tiers = root.path("tiers");
			return tiers.isArray() ? tiers.size() : 0;
		}
		catch (final Exception ex)
		{
			return 0;
		}
	}

	/**
	 * The refusal text for a match plan whose requirements JSON does not fit: "plan of N characters exceeds the field limit of M; the model
	 * proposed K nodes".
	 */
	public static String planRefusal(final String pRequirementsJson, final int pMaxLength)
	{
		return "plan of " + (pRequirementsJson == null ? 0 : pRequirementsJson.length()) + " characters exceeds the field limit of " + pMaxLength
			+ "; the model proposed " + StoredJsonFit.planNodeCount(pRequirementsJson) + " nodes";
	}

	/**
	 * Null when a plan's requirements and evidence JSON both fit their fields, else the reason the plan is refused - what {@code
	 * ebx.MatchPlanLlmSuggester.write} and {@code ebx.MatchPlanSuggester} check BEFORE touching a row.
	 */
	public static String planRefusal(final String pRequirementsJson, final String pEvidenceJson)
	{
		if (!StoredJsonFit.fits(pRequirementsJson, StoredJsonFit.REQUIREMENTS_MAX_LENGTH))
		{
			return StoredJsonFit.planRefusal(pRequirementsJson, StoredJsonFit.REQUIREMENTS_MAX_LENGTH);
		}
		if (!StoredJsonFit.fits(pEvidenceJson, StoredJsonFit.EVIDENCE_MAX_LENGTH))
		{
			return StoredJsonFit.refusal("evidence of the plan", pEvidenceJson, StoredJsonFit.EVIDENCE_MAX_LENGTH) + "; the model proposed "
				+ StoredJsonFit.planNodeCount(pRequirementsJson) + " nodes";
		}
		return null;
	}

	/** The refusal text for any other parsed JSON: "<what> of N characters exceeds the field limit of M". */
	public static String refusal(final String pWhat, final String pJson, final int pMaxLength)
	{
		return pWhat + " of " + (pJson == null ? 0 : pJson.length()) + " characters exceeds the field limit of " + pMaxLength;
	}

	/**
	 * What to store in a JSON field: {@code pJson} itself when it fits, never a cut of it. Callers test {@link #fits} first and refuse; this
	 * throws if one forgot, so a truncated JSON can not be written by mistake.
	 */
	public static String requireFit(final String pJson, final int pMaxLength)
	{
		if (!StoredJsonFit.fits(pJson, pMaxLength))
		{
			throw new IllegalStateException(StoredJsonFit.refusal("JSON text", pJson, pMaxLength));
		}
		return pJson;
	}
}
