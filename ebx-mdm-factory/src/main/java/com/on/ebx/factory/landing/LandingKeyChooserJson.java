package com.on.ebx.factory.landing;

/**
 * Sources+landing redesign, lot LANDING (2026-09-24, coordinator follow-up on
 * docs/mockups/sources-landing/LandingReview.dc.html "Several columns together"): the ONE call site that
 * decides exactly what sample JSON {@code wizard.js}'s composite-key builder reads client-side, kept as its
 * own tiny pure method (rather than inlined in {@code wizard.WizardPage}) so it has a single, pinned,
 * unit-tested contract - {@code LandingKeyChooserJsonTest} asserts the EXACT string this produces for a
 * fixture, which is also the shape documented for {@code wizard.js}'s own live recompute (mirrors {@link
 * KeyEvidence#combination}'s counting rules, never compared against a server-computed value - see that
 * method's own Javadoc and the JS block's comment in {@code wizard.js}).
 * <p>
 * A thin wrapper over {@link KeyEvidence#sampleJson} on purpose (not a re-implementation): {@code
 * report.sampleColumns}/{@code report.sampleRows} are exactly what {@link KeyEvidence#analyse}/{@link
 * KeyEvidence#combination} were already computed from server-side for the SAME report, so the client and
 * the server are guaranteed to agree on what a "row" and a "column" mean, even though the two counting
 * implementations (Java {@link KeyEvidence#combination}, the JS mirror) never call into each other.
 */
public final class LandingKeyChooserJson
{
	private LandingKeyChooserJson()
	{
	}

	/**
	 * The sample as {@code wizard.js} embeds and reads it: {@code KeyEvidence.sampleJson(report.sampleColumns,
	 * report.sampleRows)} - one JSON object per sample row, keyed by column name, string values only. Never
	 * {@code null}: an empty sample (no rows, or no columns) answers {@code "[]"}.
	 */
	public static String sample(final KeyStudyReport pReport)
	{
		return KeyEvidence.sampleJson(pReport.sampleColumns, pReport.sampleRows);
	}
}
