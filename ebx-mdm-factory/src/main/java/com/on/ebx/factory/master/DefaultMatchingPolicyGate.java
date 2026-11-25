package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * The pure decision behind correcting a table's own {@code tableSettings.defaultMatchingPolicy} after a
 * matching-policy write - `docs/MATCHING.md` &sect;26, live measurement 2026-09-23 13:30 (Contact, table
 * {@code /root/Contact}): a {@code Simulate} click's {@code generate_matching_policy} (the FIRST matching
 * policy ever generated for that table) left {@code get_matching_config} answering {@code
 * tableSettings.defaultMatchingPolicy: "Contact__preview"} - the add-on makes the FIRST policy generated on a
 * table its default, whatever that policy's own code, preview policies included. This directly contradicts
 * {@code ebx.MatchingPreviewPolicy}'s own PRIOR class Javadoc claim ("the preview is still never the table's
 * {@code tableSettings.defaultMatchingPolicy} - nothing in this class ever touches that field") - true as to
 * touching (nothing in that class ever WROTE it), false as to the outcome (the add-on wrote it anyway, as a
 * side effect of the very first {@code generate_matching_policy} call that class makes). Left uncorrected, a
 * table simulated before its entity was ever accepted keeps a throwaway {@code __preview} policy as its
 * default indefinitely - including after the entity IS accepted and a real policy exists, since nothing about
 * {@code ebx.MatchingConfigurator.configureOne}'s own {@code generate_matching_policy}/{@code
 * apply_matching_config} calls touches {@code tableSettings} either (that surface belongs to {@code
 * set_table_settings} alone, `CLAUDE.md`'s own live tool signature).
 * <p>
 * <b>Pure, total, no EBX/Jackson-JsonNode dependency in the decision itself</b> ({@link #decide}) - the one
 * JsonNode-aware convenience ({@link #currentDefault}) only extracts the one string the decision needs,
 * kept separate so the decision logic itself is testable with three plain strings and no hand-built envelope.
 * Used identically by three call sites, each supplying its own {@code pRealCode} (or {@code null}):
 * <ul>
 * <li>{@code ebx.MatchingPreviewPolicy#ensureFresh} - {@code pRealCode = null} (no real, accepted policy
 * exists yet on this branch by construction): a default naming the just-generated preview is always {@link
 * Action#CLEAR}, never {@link Action#SET_REAL}.</li>
 * <li>{@code ebx.MatchingConfigurator#configureOne}, after {@code GENERATE_FRESH} and after {@code REWRITE} -
 * {@code pRealCode = } the entity's own real policy code: a default naming the preview, or blank, is {@link
 * Action#SET_REAL}; a default already naming the real code is {@link Action#NONE}.</li>
 * <li>{@code ebx.MatchingPreviewPolicy#remove} - {@code pRealCode = } the table's remaining real merge-policy
 * code if one exists ({@code firstNonPreviewCode}), else {@code null}: a default naming the preview being
 * removed is {@link Action#SET_REAL} (hand it to the surviving real policy) or {@link Action#CLEAR} (nothing
 * real to fall back to) - applied BEFORE the removal itself, since {@code set_table_settings} refuses a
 * non-existent code and it is not known whether removing a policy that is the table's own default would be
 * refused either.</li>
 * </ul>
 */
public final class DefaultMatchingPolicyGate
{
	/** {@link #decide}'s own three outcomes - see class Javadoc for exactly when each call site sees which. */
	public enum Action
	{
		/** {@code set_table_settings} with {@code defaultMatchingPolicy: ""} - no real code to fall back to. */
		CLEAR,
		/** {@code set_table_settings} with {@code defaultMatchingPolicy: <realCode>}. */
		SET_REAL,
		/** Already correct (names the real code) or names something this fix has no opinion about (neither blank nor the preview code, and no real code was supplied) - never touched. */
		NONE
	}

	private DefaultMatchingPolicyGate()
	{
	}

	/**
	 * @param pCurrentDefault {@code tableSettings.defaultMatchingPolicy} as read off a live {@code
	 *            get_matching_config}/{@code set_table_settings} answer - {@code null}/blank both mean "no
	 *            default set at all", treated identically.
	 * @param pPreviewCode the {@code <Entity>__preview} code that MIGHT be sitting there ({@code
	 *            ebx.MatchingPreviewPolicy#policyCode}) - never a generic "any preview-suffixed code" check
	 *            (this class stays independent of {@code ebx.MatchingPreviewPolicy}, same "master never depends
	 *            on ebx" discipline {@code MatchingConfigRewriteBuilder} already follows for its own {@code
	 *            pPreviewCode} parameter): the caller always knows exactly which preview code is in play.
	 * @param pRealCode the real policy code to fall back to, or {@code null}/blank when none is known/exists yet
	 *            (see class Javadoc, per call site).
	 */
	public static Action decide(final String pCurrentDefault, final String pPreviewCode, final String pRealCode)
	{
		final String current = pCurrentDefault == null ? "" : pCurrentDefault.trim();
		final String realCode = pRealCode == null ? "" : pRealCode.trim();
		final boolean hasRealCode = !realCode.isEmpty();
		if (hasRealCode && current.equals(realCode))
		{
			return Action.NONE;
		}
		final boolean namesPreviewOrBlank = current.isEmpty() || current.equals(pPreviewCode);
		if (!namesPreviewOrBlank)
		{
			// names something else entirely (a stranger's code, or - should it ever happen - some other table
			// setting this fix has no evidence about) - never touched by this fix, same "never guess" discipline
			// as ebx.MatchingConfigurator's own ownership refusal.
			return Action.NONE;
		}
		return hasRealCode ? Action.SET_REAL : Action.CLEAR;
	}

	/** {@code data.tableSettings.defaultMatchingPolicy} with a root fallback ({@link MameAnswer#field}) - {@code null} when absent or {@code pReadBackJson} itself is {@code null}. */
	public static String currentDefault(final JsonNode pReadBackJson)
	{
		return MameAnswer.field(pReadBackJson, "tableSettings").path("defaultMatchingPolicy").asText(null);
	}
}
