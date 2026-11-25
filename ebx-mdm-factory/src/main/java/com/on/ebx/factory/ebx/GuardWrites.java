package com.on.ebx.factory.ebx;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.review.ReviewLine;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Staging-proposal lot 0 (docs/STAGING-PROPOSAL.md): one small write surface for a {@link
 * com.on.ebx.factory.guard.Guard}'s own "severity" concept, whatever table it is really backed by - a
 * {@code QualityRule.severity} for {@link ReviewLine#QUALITY}, a {@code CapabilitySuggestion.transferPolicy}
 * for {@link ReviewLine#SCRIPT_CONSTRAINT} (there is no severity concept at all for {@link
 * ReviewLine#EXTERNAL_SERVICE}/{@link ReviewLine#SCRIPT_DERIVATION} - see {@code Guard}'s own Javadoc). A
 * future guard-review screen (Mapping matrix "Rules and checks" column, Feed designer guard panel) calls
 * {@link #setSeverity} with a {@link Guard#nature} it already has, without needing to know which underlying
 * field that nature writes.
 * <p>
 * <b>Deliberately NOT a new write path</b>: both underlying writes already exist ({@link
 * WizardData#setQualityRuleSeverity}/{@link WizardData#setCapabilityTransferPolicy}, added by the wizard's
 * own quality/quality-script-rule lots) - this class only DISPATCHES to them by nature, so the actual
 * {@code Procedure}/{@code ValueContextForUpdate} write logic is never duplicated (CLAUDE.md's own repeated
 * "never re-derive/re-implement a write path that already exists" lesson). One difference from those two
 * methods' own contract is intentional, not an oversight: {@link WizardData#setQualityRuleSeverity} refuses
 * an {@code APPLIED} row (same rule as {@code review.ReviewRules#canDecide}, exactly what the task brief for
 * this lot asks for), but {@link WizardData#setCapabilityTransferPolicy} deliberately does NOT - its own
 * Javadoc explains why: {@code transferPolicy} is read LIVE by {@code ebx.MasterLoader} at every transfer,
 * never baked into the published script body, so changing it after the constraint is applied is the normal
 * case, not an edge one. Overriding that already-shipped, already-reasoned decision to force a blanket
 * "refuse APPLIED" here would silently break the one case it was written for; this class keeps each
 * delegate's own rule instead of imposing a new one on top.
 * <p>
 * Accept/reject themselves stay in {@code ebx.ProposalReviews#decide} - not duplicated here, per the task
 * brief.
 */
public final class GuardWrites
{
	private GuardWrites()
	{
	}

	/**
	 * Writes {@code pSeverity} on the guard {@code pId} of nature {@code pNature}:
	 * <ul>
	 * <li>{@link ReviewLine#QUALITY} - {@code pSeverity} must be one of {@code WARN}/{@code REJECT}/
	 * {@code MODEL_CONSTRAINT} ({@code QUALITY_RULE_SEVERITY} in {@code tools/build_xsd.py}); refused on an
	 * {@code APPLIED} row.</li>
	 * <li>{@link ReviewLine#SCRIPT_CONSTRAINT} - {@code pSeverity} must be {@code ""}/{@code null} (clears
	 * it), {@code "WARN"} or {@code "REJECT"} ({@code CAPABILITY_TRANSFER_POLICY}); the row must be zone
	 * {@code STAGING}, and an {@code APPLIED} row is NOT refused (see the class Javadoc).</li>
	 * </ul>
	 * Any other nature is refused outright: {@link ReviewLine#EXTERNAL_SERVICE}/{@link
	 * ReviewLine#SCRIPT_DERIVATION} carry no severity concept (a {@link com.on.ebx.factory.guard.Guard} of
	 * either nature always reads {@code severity == null} - see its own Javadoc), so a caller that got here
	 * from a real {@code Guard} never legitimately reaches this branch; it exists only to refuse a forged
	 * request cleanly rather than write something meaningless.
	 */
	public static void setSeverity(final Session pSession, final Adaptation pFactory, final String pNature, final String pId, final String pSeverity)
		throws OperationException
	{
		if (ReviewLine.QUALITY.equals(pNature))
		{
			WizardData.setQualityRuleSeverity(pSession, pFactory, pId, pSeverity);
			return;
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature))
		{
			WizardData.setCapabilityTransferPolicy(pSession, pFactory, pId, pSeverity);
			return;
		}
		throw OperationException.createError("A guard of nature '" + pNature + "' has no severity to set.");
	}

	/**
	 * The APPLIED/refuse check {@link #setSeverity} does not itself impose for a {@link
	 * ReviewLine#SCRIPT_CONSTRAINT} guard (see the class Javadoc) - exposed separately so a caller that wants
	 * the stricter, {@code review.ReviewRules#canDecide}-equivalent behaviour for a {@link
	 * ReviewLine#SCRIPT_CONSTRAINT} guard too (rather than {@code WizardData}'s own deliberate exception) can
	 * check it first and refuse client-side before ever calling {@link #setSeverity}. Reads the row fresh,
	 * never trusts a caller-supplied status. Returns {@code false} when the row does not exist (nothing to
	 * refuse, and nothing {@link #setSeverity} could do about it either).
	 */
	public static boolean isApplied(final Adaptation pFactory, final String pNature, final String pId)
	{
		final AdaptationTable table =
			ReviewLine.QUALITY.equals(pNature) ? pFactory.getTable(FactoryPaths.QUALITY_RULE)
				: ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.EXTERNAL_SERVICE.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature)
					? pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION) : null;
		if (table == null)
		{
			return false;
		}
		final Adaptation row = table.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pId));
		if (row == null)
		{
			return false;
		}
		final String status = ReviewLine.QUALITY.equals(pNature) ? row.getString(FactoryPaths.QualityRule.STATUS)
			: row.getString(FactoryPaths.CapabilitySuggestion.STATUS);
		return ReviewLine.APPLIED.equals(status);
	}
}
