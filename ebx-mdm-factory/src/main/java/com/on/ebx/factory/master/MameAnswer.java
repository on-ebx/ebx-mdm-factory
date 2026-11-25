package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

/**
 * Pure shape-reading of the {@code match-merge} MCP server's own JSON response envelope - no EBX class, unit-
 * tested with hand-built fixtures, the same convention {@link MameDataModelResolution}/{@link
 * MatchPlanProfileReader} already follow.
 * <p>
 * <b>The bug this class fixes, measured live, 2026-09-22 08:58 (coordinator run of {@code POST
 * /projects/Companies/matching/configure}).</b> {@code ebx.MatchingConfigurator} read {@code
 * validate_matching_config}'s {@code valid} flag at the ROOT of the answer
 * ({@code validateResult.json.path("valid")}), but the real answer wraps its payload under {@code data} -
 * {@code {"success":true,"data":{"valid":true,"messages":[],"table":"/root/Company"}}} - exactly like every
 * other match-merge write/read tool ({@code get_matching_config}, {@code get_matching_activation_status},
 * {@code activate_matching}, ...). The root-only read always found {@code MissingNode}, which {@code
 * asBoolean(false)} silently turns into "invalid", so a genuinely valid, freshly-generated configuration
 * ({@code Company}, 2-tier tree, fields {@code /name}/{@code /city}/{@code /country}) was refused right
 * after {@code generate_matching_policy}/{@code generate_merge_policy} had already stored it. {@code
 * activate}'s own parsing a few lines below (this class' {@link #field}) already read {@code data.state}
 * with a root fallback - the validate parsing was simply left inconsistent with it. Never re-run live from
 * this branch (write tools are forbidden here) - the fix is verified by unit test against the exact captured
 * answer above, not by a live retry.
 * <p>
 * Every method here takes the RAW top-level answer ({@code pJson} = {@code ToolResult.json}), never an
 * already-unwrapped {@code data} node - that keeps one call site's "did the call even carry a payload"
 * question (a transport error, {@code json == null}) and its "is the payload itself telling me no"
 * question (a soft miss, {@code success:false} with a {@code code}) both answerable from the same object.
 */
public final class MameAnswer
{
	private MameAnswer()
	{
	}

	/**
	 * The root-level {@code success} flag every match-merge answer carries (found answer or soft miss alike -
	 * {@code docs/MATCHING.md} &sect;8's measured soft-miss shape: {@code {"success":false,"code":"...",
	 * "message":"...","nextStep":"..."}}). {@code null} json (the call itself failed, no JSON at all) reads as
	 * {@code pDefaultWhenAbsent}, exactly like a present-but-absent field - callers that treat "no answer" and
	 * "answer says nothing about success" the same way (e.g. {@code MatchingConfigurator.requireSuccess}'s
	 * pre-fix behaviour, default {@code true}) get that back verbatim; callers that treat absence as failure
	 * pass {@code false}.
	 */
	public static boolean success(final JsonNode pJson, final boolean pDefaultWhenAbsent)
	{
		return pJson == null ? pDefaultWhenAbsent : pJson.path("success").asBoolean(pDefaultWhenAbsent);
	}

	/** The root-level {@code code} of a soft-miss answer ({@code CONFIG_NOT_FOUND}, {@code TABLE_NOT_FOUND}, ...) - {@code null} on a genuine success, an absent field, or a {@code null} json. */
	public static String code(final JsonNode pJson)
	{
		return pJson == null ? null : pJson.path("code").asText(null);
	}

	/**
	 * {@code pField}, read from {@code data.<pField>} first, the ROOT {@code <pField>} as fallback - the exact
	 * double-read {@code activate_matching}'s state check already did before this fix (now shared instead of
	 * duplicated). Returns a {@link MissingNode} (never {@code null}, never throws) when neither the {@code
	 * data}-nested nor the root field is present, or when {@code pJson} itself is {@code null} - callers apply
	 * their own default via the returned node's own {@code asBoolean}/{@code asText}/{@code isArray}.
	 */
	public static JsonNode field(final JsonNode pJson, final String pField)
	{
		if (pJson == null)
		{
			return MissingNode.getInstance();
		}
		final JsonNode dataField = pJson.path("data").path(pField);
		if (!dataField.isMissingNode())
		{
			return dataField;
		}
		return pJson.path(pField);
	}
}
