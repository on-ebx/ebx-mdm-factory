package com.on.ebx.factory.designer;

import com.on.ebx.factory.review.ReviewLine;

/**
 * Staging-proposal lot "designer" (docs/STAGING-PROPOSAL.md, docs/FEED-DESIGNER.md sec "Rules and checks"):
 * small, purely computed decisions the Feed designer's field inspector needs - which nature is a "rule" vs
 * a "check" badge, and which severity/transfer-policy choices are legal to offer for a given {@code
 * com.on.ebx.factory.guard.Guard#nature}. No EBX class; the EBX-bound side ({@code
 * ebx.ui.FeedDesignerUserService}) calls these rather than re-deriving the same nature-by-nature logic
 * {@code ebx.GuardWrites#setSeverity} already documents in its own Javadoc - this class only decides WHAT
 * the inspector may offer, never WRITES anything (the write itself stays in {@code GuardWrites}/{@code
 * ebx.ProposalReviews#decide}, per the task brief).
 */
public final class DesignerModel
{
	/** {@code QualityRule.severity} ({@code QUALITY_RULE_SEVERITY} in tools/build_xsd.py). */
	private static final String[] QUALITY_SEVERITIES = { "WARN", "REJECT", "MODEL_CONSTRAINT" };
	/**
	 * {@code CapabilitySuggestion.transferPolicy} ({@code CAPABILITY_TRANSFER_POLICY}) - the first entry is
	 * always the empty string ("not set", {@code ebx.GuardWrites#setSeverity}'s own blank-clears-it rule).
	 */
	private static final String[] SCRIPT_CONSTRAINT_SEVERITIES = { "", "WARN", "REJECT" };
	private static final String[] NO_SEVERITY = new String[0];

	private DesignerModel()
	{
	}

	/**
	 * The legal "when a value fails" choices for a guard of this {@link
	 * com.on.ebx.factory.guard.Guard#nature} - {@link #QUALITY_SEVERITIES} for {@link ReviewLine#QUALITY},
	 * {@link #SCRIPT_CONSTRAINT_SEVERITIES} for {@link ReviewLine#SCRIPT_CONSTRAINT}, empty for every other
	 * nature (a {@link ReviewLine#EXTERNAL_SERVICE}/{@link ReviewLine#SCRIPT_DERIVATION} guard has no
	 * severity concept at all - {@code com.on.ebx.factory.guard.Guard}'s own Javadoc). Never {@code null}.
	 */
	public static String[] severityOptions(final String pNature)
	{
		if (ReviewLine.QUALITY.equals(pNature))
		{
			return DesignerModel.QUALITY_SEVERITIES.clone();
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature))
		{
			return DesignerModel.SCRIPT_CONSTRAINT_SEVERITIES.clone();
		}
		return DesignerModel.NO_SEVERITY;
	}

	/**
	 * Whether a guard of this nature renders as a "check" badge (a script/service capability - {@link
	 * ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#EXTERNAL_SERVICE}/{@link ReviewLine#SCRIPT_DERIVATION})
	 * rather than a "rule" badge ({@link ReviewLine#QUALITY}, a declarative {@code QualityRule}) - the same
	 * distinction the mockup draws (docs/mockups/staging-proposal/Designer.dc.html: {@code .gb.rule}/{@code
	 * .gb.check}). An unrecognised nature defaults to "check" (fail open to the more generic badge, never
	 * silently mislabels a rule as something it is not - {@link ReviewLine#QUALITY} is the one nature that
	 * must be positively matched).
	 */
	public static boolean isCheckNature(final String pNature)
	{
		return !ReviewLine.QUALITY.equals(pNature);
	}
}
