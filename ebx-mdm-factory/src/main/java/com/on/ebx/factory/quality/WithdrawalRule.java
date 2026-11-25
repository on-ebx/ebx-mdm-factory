package com.on.ebx.factory.quality;

/**
 * What to do with an EXISTING {@code MappingStep}/{@code QualityRule} row when a re-run of {@code
 * quality/suggest} finds its evidence no longer supported - the attribute (for a {@code QualityRule}) or the
 * mapping (for a {@code MappingStep}) it was proposed from is no longer fed by an accepted-like {@code
 * AttributeMapping}. Coordinator finding, 2026-09-21, on the real project {@code Companies}: mappings 57
 * ({@code ACCOUNT_TYPE -> Company.legalForm}) and 59 ({@code SIREN -> Company.vatNo}) were accepted, ran once
 * (so {@code QualitySuggester} profiled their landing columns and proposed rules/steps from real values), then
 * REJECTED - but {@code QualityRule} is keyed on the ATTRIBUTE, not the mapping, so rules 13 ({@code
 * CHECK_DIGIT} SIREN) and 14 ({@code UNIQUE}) on {@code Company.vatNo}, whose evidence came ONLY from the now-
 * rejected SIREN mapping, stayed {@code PROPOSED} forever: {@code readAcceptedMappings}/{@code
 * writeQualityRules} in {@code ebx.QualitySuggester} simply never visit {@code Company.vatNo} again once no
 * accepted mapping feeds it, so the stale row is never touched by the ordinary "create or refresh" loop.
 * Accepted as they stand, rule 13 would WARN on every ERP VAT number (a real IBAN/SIREN-shaped VAT, not a
 * SIREN at all) - a false positive baked in by evidence that no longer describes reality.
 * <p>
 * <b>{@code QualitySuggester.readAcceptedMappings} already filters to accepted-like mappings only</b> (checked
 * directly, {@code STATUS}/{@code TechnicalKeyRule.isAcceptedLike} - a still-{@code PROPOSED} or {@code
 * REJECTED} {@code AttributeMapping} was never read in the first place, so its landing column was never
 * profiled and no rule/step was ever proposed FROM it) - so the defect is not "a rejected mapping's evidence
 * gets used", it is "a rule/step proposed from a mapping that was later rejected is never revisited", which is
 * a withdrawal problem, not a filtering one. This class is the withdrawal decision only.
 * <p>
 * <b>The mechanism is deletion, not a status, and only ever for a row nobody decided on</b>: a still-{@code
 * PROPOSED} row is exactly that - a suggestion nobody has looked at - so removing it when its own evidence has
 * evaporated is no different from never having proposed it this run; {@code quality/suggest} will propose it
 * again, fresh, the moment a different accepted mapping brings the same evidence back. A row a person already
 * decided on ({@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}) is NEVER deleted or silently changed - EBX
 * history (`CLAUDE.md` §12, "decider = un geste nominatif") keeps who decided it, and deleting a decided row
 * would erase that record for no good reason; instead the caller must surface a warning on the review line
 * (see {@code review.ReviewRules}'s own {@code attributeMappingWarning} for the exact same shape of warning,
 * reused rather than duplicated).
 * <p>
 * Pure: no EBX class - {@code ebx.QualitySuggester} supplies {@code pCovered} (computed from the SAME
 * accepted-mapping read it already does for the main proposal pass) and this class only answers a status
 * question, unit-tested without a running instance.
 */
public final class WithdrawalRule
{
	private WithdrawalRule()
	{
	}

	/** What a caller should do with an existing {@code MappingStep}/{@code QualityRule} row. */
	public enum Action
	{
		/** Leave the row exactly as it is - either its evidence is still covered, or... (see {@link #WARN}). */
		KEEP,
		/** Nobody ever decided on this row (still {@code PROPOSED}) and its evidence is gone: delete it. */
		WITHDRAW,
		/** A person already decided on this row ({@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}) and its evidence is gone: never touch the row itself, only flag it for a reviewer. */
		WARN
	}

	/**
	 * @param pStatus the row's current {@code status} ({@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/
	 *            {@code APPLIED})
	 * @param pCovered whether the row's own scope (the {@code QualityRule}'s attribute, or the {@code
	 *            MappingStep}'s mapping) is still fed by at least one accepted-like {@code AttributeMapping}
	 *            this run
	 * @return {@link Action#KEEP} when covered, whatever the status; otherwise {@link Action#WITHDRAW} for a
	 *         still-{@code PROPOSED} row, {@link Action#WARN} for anything a person already decided
	 */
	public static Action decide(final String pStatus, final boolean pCovered)
	{
		if (pCovered)
		{
			return Action.KEEP;
		}
		return "PROPOSED".equals(pStatus) ? Action.WITHDRAW : Action.WARN;
	}

	/**
	 * {@link Action#KEEP} unconditionally when {@code pOrigin} is not {@code RULE} - {@code pCovered}/{@code
	 * pStatus} are never even consulted in that case. Added 2026-09-22 (coordinator fix before merge) for
	 * {@code ebx.QualitySuggester.withdrawStaleFoldKeyAttributes} (the {@code StagingAttribute FOLD_KEY} sweep):
	 * a deterministic {@code RULE} sweep has no way to know whether a person's own decision to hand-write a
	 * derived attribute ({@code USER}), or the language model's own reasoning for proposing one ({@code LLM}),
	 * is still valid - only a row THIS SAME deterministic mechanism produced can be re-confirmed or found stale
	 * by re-running that mechanism. Without this gate, a person's own still-{@code PROPOSED} hand-made key
	 * would be silently DELETED the moment a fresh fold measurement happens to find no gain - a real data-loss
	 * bug, not a cleanup.
	 * <p>
	 * <b>Deliberately NOT used by {@code ebx.QualitySuggester.withdrawStaleProposals}</b> (the older {@code
	 * MappingStep}/{@code QualityRule} sweep, unchanged): that sweep's PRIMARY reason to withdraw - "no
	 * accepted-like mapping feeds this attribute/mapping at all any more" - is a structural fact true
	 * regardless of who proposed the row, so it withdraws a stale {@code LLM}/{@code USER} row for THAT reason
	 * exactly as readily as a {@code RULE} one; only a SECOND, narrower reason (the row's own {@code kind} is
	 * one {@code Detectors}/{@code QualityDetectors} no longer proposes at all) is origin-gated there, via
	 * {@code kindStillRuleProposable} folded INTO the {@code pCovered} argument of the plain {@link #decide}
	 * overload - see that method's own Javadoc. Folding {@code FOLD_KEY}'s own origin gate into {@code
	 * pCovered} the same way would not work: {@code pCovered} there means "a fresh measurement found a gain
	 * THIS run", which has no origin-independent fallback reason at all for a derived attribute - so a
	 * still-{@code PROPOSED} {@code USER}/{@code LLM} row would read as uncovered and be withdrawn regardless,
	 * exactly the bug this overload exists to prevent. The two sweeps are answering genuinely different
	 * questions, not the same question with a smaller/bigger gate.
	 */
	public static Action decide(final String pStatus, final boolean pCovered, final String pOrigin)
	{
		if (!"RULE".equals(pOrigin))
		{
			return Action.KEEP;
		}
		return WithdrawalRule.decide(pStatus, pCovered);
	}
}
