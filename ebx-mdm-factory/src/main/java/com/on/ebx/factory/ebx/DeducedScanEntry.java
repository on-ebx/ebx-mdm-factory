package com.on.ebx.factory.ebx;

import java.util.List;
import java.util.Set;

import com.on.ebx.factory.core.CatalogRightMessages;
import com.on.ebx.factory.deducedscan.OperationCatalog;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.on.ebx.factory.wizard.WizardRules;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Fix lot (2026-09-25), bug 1 of the fix brief: the "Connect a live system" card
 * ({@code ebx.ui.ProjectWizardUserService}/{@code wizard.WizardPage}'s onboarding panel) used to be a dead
 * end for a connection with no {@code catalog} operation - {@link SourceOnboarding#diagnose} answered a
 * {@link SourceOnboarding.State#BLOCKED} {@code catalogOperation} step with {@link SourceOnboarding.Action
 * #NONE} ("cannot be scanned into the catalog: pick a connection of a type that offers a catalog operation"),
 * even though {@link com.on.ebx.factory.ebx.DeducedScanRun}'s own deduced-scan pass exists for exactly this
 * case. {@link DeducedScanRun#start} was only ever offered as a follow-up action on an ALREADY-EXISTING,
 * tableless {@code ProjectSource} ({@code docs/DEDUCED-SCAN.md} sec 6, its own "guess") - a brand new
 * connection picked in the onboarding panel never reached one. This class is the missing entry point: given a
 * connector with no catalog operation, it decides (never trusting a diagnosis the caller might be holding
 * stale - a fresh {@code connectors_describe} through {@link DeducedScanOperations#describe}) whether the
 * connection has at least one read-only operation at all:
 * <ul>
 * <li>none at all - the contract's "lookup-only connection" case (a GLEIF/Sirene/VIES/BAN-style enrichment,
 * never a source): refuses, writes nothing at all;</li>
 * <li>at least one - finds or creates a minimal catalog {@code Source} for it ({@link
 * DeducedScanCatalog#ensureSource}, the SAME helper {@link DeducedScanRun} itself already relies on for a
 * connection that skipped the official onboarding chain), adds it as an application of the project with the
 * SAME code-proposal/trust-rank rules {@link SourcesRun}'s own {@code addApplicationIfReady} applies for the
 * official path (no schemas are known yet for a source with zero catalogued tables, so the code always falls
 * back to the connector id itself - the same fallback {@code addApplicationIfReady} already takes when a
 * connection's own schema list is empty), then queues the deduced-scan job on the freshly-created {@code
 * ProjectSource} ({@link DeducedScanRun#start}) - the SAME job board 2d already polls for, so the
 * application's card switches straight into the four-stage pipeline (How to list / Read a sample / Measure /
 * Name and key) the instant this method returns.
 * </ul>
 * Deliberately does NOT go through {@link SourceOnboarder}/{@link SourceOnboarding#diagnose} at all - that
 * whole six-step chain (catalog source / scan flows / scan / ready) is the OFFICIAL scan's own path
 * (read-recipe-contract.md sec "Why") and does not apply here: a deduced-scanned application's "ready" state
 * is board 2e's own confirm screen, not this diagnosis.
 */
public final class DeducedScanEntry
{
	private DeducedScanEntry()
	{
	}

	/** What {@link #start} did, for the caller's own notice text. */
	public static final class Result
	{
		public final String projectSourceId;
		public final String sourceSystem;
		public final String catalogSourceId;

		Result(final String pProjectSourceId, final String pSourceSystem, final String pCatalogSourceId)
		{
			this.projectSourceId = pProjectSourceId;
			this.sourceSystem = pSourceSystem;
			this.catalogSourceId = pCatalogSourceId;
		}
	}

	/**
	 * @throws OperationException {@code pConnectorId} is blank, {@code connectors_describe} itself failed, the
	 *         connection actually HAS a catalog operation (use the guided catalog-scan path instead - this is
	 *         defensive, the checklist never offers this action in that case), it has NO read-only operation at
	 *         all (the enrichment-only refusal - contract: "create nothing"), the data catalog is not reachable,
	 *         another application of this project already uses the same catalog source, or the write itself
	 *         failed - nothing is written on any of these paths.
	 */
	public static Result start(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectorId) throws OperationException
	{
		if (pConnectorId == null || pConnectorId.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.noConnection", "No connection picked.");
		}
		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			throw EngineRefusal.coded("invalid.noProject", "No project.");
		}

		final DeducedScanOperations.Described described = DeducedScanOperations.describe(pSession, pFactory, pConnectorId);
		if (described.error != null)
		{
			throw EngineRefusal.coded("external.failed", described.error);
		}
		if (described.hasCatalogOperation)
		{
			throw EngineRefusal.coded("conflict.officialCatalogOp", "Connector " + pConnectorId + " has an official catalog operation: use the guided catalog-scan path instead.", pConnectorId);
		}
		final List<OperationCatalog.OperationInfo> readOnlyOperations = OperationCatalog.readOnlyOperations(described.operations);
		if (OperationCatalog.hasNoReadOnlyOperation(readOnlyOperations))
		{
			// contract's own "lookup-only connection" wording (also DeducedScanRun's SKIPPED entry message for
			// the same case, reached later if a deduced scan somehow still runs against it) - create nothing.
			throw EngineRefusal.coded("conflict.enrichmentOnly", "Connector " + pConnectorId + " looks up one record at a time: an enrichment, not a source. Nothing was created.", pConnectorId);
		}

		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			throw EngineRefusal.coded("missing.catalogUnavailable", "The data catalog is not available.");
		}
		// fix lot (2026-09-25): a raw EBX write failure here (e.g. a constraint refusal) used to surface as
		// the bare EBX message with no indication of WHAT failed - wrap it so the wizard's notice names the
		// connector and the step, same convention as every other write in this class.
		final String catalogSourceId;
		try
		{
			catalogSourceId = DeducedScanCatalog.ensureSource(pSession, catalog, pConnectorId, "");
		}
		catch (final OperationException ex)
		{
			// audit F-02 (catalog reads): the creation runs under the person's session - a refusal of the catalog's rights names the right
			final String refused = CatalogRightMessages.refused(CatalogRightMessages.CatalogWrite.REGISTER_SOURCE, ex.getMessage());
			throw EngineRefusal.codedWithCause("external.registerCatalog", refused != null ? refused : "Could not register " + pConnectorId + " in the data catalog: " + ex.getMessage(), ex, pConnectorId);
		}

		final String existingSourceSystem = SampleSourceImporter.applicationUsing(pFactory, pProjectId, catalogSourceId);
		if (existingSourceSystem != null)
		{
			throw EngineRefusal.coded("conflict.alreadyApplication", "Connector " + pConnectorId + " is already an application of this project (" + existingSourceSystem + ").", pConnectorId, existingSourceSystem);
		}

		final WizardRules.SourceDraft draft = new WizardRules.SourceDraft();
		draft.catalogSourceId = catalogSourceId;
		draft.connector = pConnectorId;
		// no schemas: a fresh deduced-scan candidate has no catalogued tables yet (they only exist once the
		// scan this method queues below has run and a person confirmed at least one on board 2e) - same
		// "fall back to the connector id" rule SourcesRun#addApplicationIfReady already applies when a
		// connection's own schema list is empty.
		final Set<String> existingCodes = WizardData.existingSourceSystemCodes(pFactory, pProjectId);
		draft.sourceSystem = WizardRules.proposeApplicationCode(pConnectorId, pConnectorId, existingCodes);
		draft.trustRank = WizardRules.nextTrustRank(WizardData.existingTrustRanks(pFactory, pProjectId));

		final List<WizardRules.Problem> problems = WizardRules.sourceProblems(draft, existingCodes);
		if (!problems.isEmpty())
		{
			// defensive: uniqueCode-style de-duplication above should already make this unreachable - kept as a
			// hard stop rather than silently writing something WizardRules itself would refuse (same discipline
			// as SourcesRun#addApplicationIfReady).
			throw EngineRefusal.coded("unexpected.addApplication", "Could not add the application automatically (" + problems.get(0).key + ").");
		}
		final String newSourceId = WizardData.addSource(pSession, pFactory, pProjectId, draft);

		DeducedScanRun.start(pSession, pFactory, pProjectId, newSourceId);

		return new Result(newSourceId, draft.sourceSystem, catalogSourceId);
	}
}
