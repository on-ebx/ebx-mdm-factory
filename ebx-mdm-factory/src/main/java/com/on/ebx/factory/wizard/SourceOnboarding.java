package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Pure rule of the "Create a catalog source" onboarding panel (step 2 of the wizard, source-onboarding
 * lot, 2026-09-23): given everything the live side ({@code com.on.ebx.factory.ebx.SourceOnboarder},
 * another agent's task) gathered about one Connectors connection, decide which of the five steps of the
 * chain described in {@code scratchpad/source-onboarding-brief.md} (connection -&gt; catalog operation
 * -&gt; catalog source -&gt; scan flows -&gt; scan -&gt; ready) is done, still to do, blocked, or waiting on
 * an earlier one, and what the ONE next action button should be. No EBX or MCP import: unit-tested as is
 * in {@link SourceOnboardingTest}, against fixtures copied from the brief's measured tool answers.
 *
 * <p>
 * Every {@link Step#messageKey} this class can emit, with a one-line English meaning and its {@code {n}}
 * {@link Step#args} (for the next agent to add EN/FR text for, next to the other {@code wizard.step2.*}
 * keys - this lot already added them, see {@code Messages_en/fr.properties}):
 * <ul>
 * <li>{@code wizard.onboard.step.connection.done} {0}=connector label or id - the connection is active.</li>
 * <li>{@code wizard.onboard.step.connection.notFound} {0}=connector id, {1}=the tool's error text -
 * {@code connectors_describe} answered an error, nothing else is reachable.</li>
 * <li>{@code wizard.onboard.step.connection.inactive} {0}=connector label or id - found but not active.</li>
 * <li>{@code wizard.onboard.step.catalogOperation.done} {0}=connector type - the type offers a catalog
 * operation.</li>
 * <li>{@code wizard.onboard.step.catalogOperation.deduced} {0}=connector type - fix lot (2026-09-25): no
 * catalog operation, but at least one read-only listing operation exists - offers the deduced scan instead
 * of a dead end.</li>
 * <li>{@code wizard.onboard.step.catalogOperation.enrichmentOnly} {0}=connector type - fix lot (2026-09-25):
 * no catalog operation AND no read-only operation at all - a lookup-only connection (an enrichment, not a
 * source, contract's own wording); nothing can be written for it.</li>
 * <li>{@code wizard.onboard.step.catalogSource.linked} {0}=catalog source id - a Source already names
 * this connection.</li>
 * <li>{@code wizard.onboard.step.catalogSource.todo} {0}=the plan's own message (English, from
 * {@code connectors_catalog_source}) - the catalog needs a write (create/repoint the source or its
 * namespace) before it can proceed.</li>
 * <li>{@code wizard.onboard.step.catalogSource.manual} {0}=the plan's own message - a person must fix the
 * connection (duplicate or ambiguous namespace), nothing can be written automatically.</li>
 * <li>{@code wizard.onboard.step.catalogSource.manualSampleFileHint} {0}=the plan's own message,
 * {1}=connector id - same as {@code manual}, plus the sample-file-specific fix (give the connection its
 * own host).</li>
 * <li>{@code wizard.onboard.step.catalogSource.catalogUnavailable} {0}=the catalog's own state - the data
 * catalog itself is not deployed/verified, nothing downstream can work.</li>
 * <li>{@code wizard.onboard.step.catalogSource.toolError} {0}=the tool's error text -
 * {@code connectors_catalog_source} was refused outright.</li>
 * <li>{@code wizard.onboard.step.catalogSource.unknown} no args - the plan call answered neither a known
 * action nor an error nor a state problem; defensive fallback, not expected to fire on a real instance.</li>
 * <li>{@code wizard.onboard.step.scanFlows.done} {0}=the scan mapping id - the catalog created the scan
 * flows.</li>
 * <li>{@code wizard.onboard.step.scanFlows.missing} no args - the catalog source is linked but no scan
 * mapping exists: create/link the source again.</li>
 * <li>{@code wizard.onboard.step.scan.done} {0}=last scan status (OK/PARTIAL) - the last scan found data.</li>
 * <li>{@code wizard.onboard.step.scan.todoNever} no args - no scan has ever run.</li>
 * <li>{@code wizard.onboard.step.scan.error} no args - the last scan failed; offers to run it again.</li>
 * <li>{@code wizard.onboard.step.scan.emptyResult} {0}=last scan status (OK/PARTIAL) - the scan completed
 * but found nothing: check the uploaded file or the schema filter.</li>
 * <li>{@code wizard.onboard.step.scan.noColumns} {0}=last scan status - UX lot U2 (2026-10-13): the catalog holds objects for the
 * source but none carries a scanned column: scan again.</li>
 * <li>{@code wizard.onboard.step.ready.done} {0}=catalog source id - everything checks out, the source can
 * be picked.</li>
 * <li>{@code wizard.onboard.step.ready.sourceInactive} {0}=catalog source id - the Source row exists but
 * is not active: activate it in the catalog.</li>
 * <li>{@code wizard.onboard.step.ready.sourceMissing} {0}=catalog source id - defensive fallback: every
 * earlier step reports done yet the catalog has no such Source row (should not happen on a real instance).</li>
 * <li>{@code wizard.onboard.step.waiting} no args - shared by every step that has not been reached yet
 * because an earlier one is not DONE.</li>
 * </ul>
 */
public final class SourceOnboarding
{
	private static final String KEY_CONNECTION = "connection";
	private static final String KEY_CATALOG_OPERATION = "catalogOperation";
	private static final String KEY_CATALOG_SOURCE = "catalogSource";
	private static final String KEY_SCAN_FLOWS = "scanFlows";
	private static final String KEY_SCAN = "scan";
	private static final String KEY_READY = "ready";

	private static final String STATUS_NEVER = "NEVER";
	private static final String STATUS_ERROR = "ERROR";
	private static final String STATUS_OK = "OK";
	private static final String STATUS_PARTIAL = "PARTIAL";

	private static final String CATALOG_STATE_VERIFIED = "VERIFIED";

	private static final String PLAN_ACTION_LINKED = "LINKED";
	private static final String PLAN_ACTION_CREATE = "CREATE";
	private static final String PLAN_ACTION_SET_CONNECTOR = "SET_CONNECTOR";
	private static final String PLAN_ACTION_SET_NAMESPACE = "SET_NAMESPACE";
	private static final String PLAN_ACTION_MANUAL = "MANUAL";

	/** Connectors connection type whose namespace collides on the baseUrl host alone (CLAUDE.md ss14) - the only type this rule special-cases with a fix-it hint. */
	private static final String CONNECTOR_TYPE_SAMPLE_FILE = "sample-file";

	private SourceOnboarding()
	{
	}

	/** Everything the live side gathered for one candidate connection; a null/false field means "not reached" or "not true", never "unknown vs. false" - the live side is responsible for leaving a field at its default when a call was never made. */
	public static final class Facts
	{
		/** The connection id picked in the combobox. */
		public String connectorId;
		/** {@code connectors_describe} answered without an error. */
		public boolean connectorFound;
		/** The tool's own error text when {@link #connectorFound} is false. */
		public String connectorError;
		/** {@code connectors_describe.type}, e.g. {@code "sample-file"}. */
		public String connectorType;
		/** {@code connectors_describe.label}, may be null. */
		public String connectorLabel;
		public boolean connectorActive;
		/** Whether the connection's type advertises a {@code catalog} operation. */
		public boolean catalogOperation;
		/** Lot S10b (2026-10-19): the connection's type also offers a {@code sample} operation (what the catalogue's classification of its columns reads). */
		public boolean sampleOperation;
		/** Lot S10b: the sentence about the sampling that followed the last scan ({@code ebx.SampleClassification}); null when none ran. */
		public String samplingNote;
		/**
		 * Fix lot (2026-09-25): whether {@code connectors_describe}'s {@code operations[]} carries at least one
		 * entry with {@code readOnly=true} (read-recipe-contract.md sec 2 - a missing/absent flag defaults to
		 * {@code false}, never trusted, same rule {@link com.on.ebx.factory.deducedscan.OperationCatalog}
		 * already applies for the deduced-scan pass itself). Only consulted when {@link #catalogOperation} is
		 * false: {@code true} offers the deduced-scan path instead of a dead end, {@code false} means this
		 * connection is lookup-only (an enrichment, not a source - contract's own wording) and nothing can be
		 * written for it.
		 */
		public boolean hasReadOnlyOperation;
		/** {@code connectors_catalog_source.catalog.state} ("VERIFIED" or not, null when the call itself failed). */
		public String catalogState;
		/** {@code connectors_catalog_source.action}: LINKED / CREATE / SET_CONNECTOR / SET_NAMESPACE / MANUAL / null. */
		public String planAction;
		/** {@code connectors_catalog_source.source}, e.g. "SOURCEBIDON". */
		public String planSourceId;
		public String planNamespace;
		public String planMessage;
		/** The tool's own error text when the plan call itself was refused. */
		public String planError;
		/** Whether a catalog {@code Source} row exists for {@link #planSourceId}. */
		public boolean sourceExists;
		public boolean sourceActive;
		/** {@code Source.lastScanStatus}: NEVER / OK / PARTIAL / ERROR / null. */
		public String lastScanStatus;
		/** The parent scan mapping id (feed, not the child columns mapping), null when there is none yet. */
		public Integer scanMappingId;
		/** Whether at least one asset row exists for the source. */
		public boolean assetsScanned;
		/**
		 * UX lot U2 (2026-10-13): whether at least one of those assets holds a scanned column. Measured live the same day: the source
		 * "salesforce://login.salesforce.com" had {@code lastScanStatus} OK and one asset, but that asset came from OpenLineage events
		 * and had no column, so the diagnosis said "Ready" for a source whose landing plan can only answer "0 tables". The scan step is
		 * done only when this is true.
		 */
		public boolean assetsWithColumns;
		/**
		 * source-onboarding lot (2026-09-23), added by the live side ({@code ebx.SourceOnboarder#runScan}): the
		 * {@code connectors_sync} outcome message of the LAST scan this Facts was built from ({@code
		 * LandingFlow.LoadOutcome#message}) - carried through, never read by {@link #diagnose} itself (which
		 * only looks at {@link #lastScanStatus}/{@link #assetsScanned}), purely so the UI can show the real
		 * "Loaded N of M row(s)"/"Load failed: ..." text next to the scan step instead of the bare status word.
		 * Null until a scan has actually run through {@code runScan} in this session.
		 */
		public String scanMessage;
		/**
		 * Audit F-02 (catalog reads): the catalog {@code Source} row and its assets are read as the person
		 * ({@code ebx.SourceOnboarder}), and their catalog right hides them - the scan step then says so instead of
		 * "never scanned".
		 */
		public boolean catalogRestricted;
	}

	/**
	 * source-onboarding lot (2026-09-23): one Connectors connection as the wizard's step-2 onboarding
	 * combobox lists it - built by {@code ebx.SourceOnboarder#connections} from {@code connectors_list} (plus
	 * a cross-reference against the catalog's own {@code Source} rows), consumed by {@code WizardPage}. Kept
	 * here, next to {@link Facts}/{@link Diagnosis}, rather than in {@code ebx.SourceOnboarder} itself: it is a
	 * plain data holder with no EBX/MCP type in it, and {@code WizardPage} must stay EBX-free (its own class
	 * Javadoc) - the same "pure DTO in the wizard package, filled by the ebx package" split {@link
	 * WizardSnapshot} already follows for the rest of this screen.
	 */
	public static final class ConnectionOption
	{
		public final String id;
		/** The connection's own label when it has one, its id otherwise - never blank (see {@link #connectorDisplay}, the same rule {@link Facts} already follows). */
		public final String label;
		public final String type;
		public final boolean active;
		/** Whether the connection's TYPE advertises a {@code catalog} operation ({@code connectors_describe}/{@code connectors_list} operations, name {@code "catalog"} specifically - not just any read/search operation). */
		public final boolean catalogCapable;
		/** The catalog {@code Source} id already naming this connection, empty when none (never null - simpler for the page to test with {@code isEmpty()}, same convention as {@link WizardSnapshot.SourceInfo}'s own string fields). */
		public final String catalogSourceId;
		/**
		 * UX lot U2 (2026-10-13): how many of the catalog source's tables hold a scanned column, -1 when not counted - set by the wizard after listing
		 * (one query per connection that is a catalog source; the plain listing, also answered by REST, does not pay for it). The single "Add an
		 * application" list reads "scanned, N tables" from it.
		 */
		public int scannedTables = -1;
		/**
		 * UX lot C1 (2026-10-13): whether a connection with NO catalog operation can still be read for its tables (the deduced scan: it has an
		 * operation that lists records). Set by the wizard with the table counts ({@code SourceOnboarder#connections}); false when not computed.
		 */
		public boolean readable;
		/** Why {@link #readable} is false ({@link AddApplicationOptions#REASON_NO_READ_OPERATION} / {@link AddApplicationOptions#REASON_DESCRIBE_FAILED}); empty when readable or not computed. */
		public String unreadableReason = "";

		public ConnectionOption(final String pId, final String pLabel, final String pType, final boolean pActive, final boolean pCatalogCapable, final String pCatalogSourceId)
		{
			this.id = pId;
			this.label = pLabel == null || pLabel.isEmpty() ? pId : pLabel;
			this.type = pType == null ? "" : pType;
			this.active = pActive;
			this.catalogCapable = pCatalogCapable;
			this.catalogSourceId = pCatalogSourceId == null ? "" : pCatalogSourceId;
		}
	}

	public enum State
	{
		DONE, TODO, BLOCKED, WAITING
	}

	public enum Action
	{
		NONE, OPEN_CONNECTION, CATALOG_APPLY, SCAN_RUN, PICK,
		/** Fix lot (2026-09-25): the connection has no catalog operation but at least one read-only listing
		 * operation - the card offers {@code ebx.DeducedScanEntry#start} (creates the application AND queues
		 * the deduced-scan job in one click) instead of a dead end. */
		DEDUCED_SCAN
	}

	/** One row of the checklist. {@link #args} are the raw {@code MessageFormat} arguments for {@link #messageKey} - never pre-formatted text, so the UI stays in control of the locale. */
	public static final class Step
	{
		public final String key;
		public final State state;
		public final String messageKey;
		public final Object[] args;
		public final Action action;

		Step(final String pKey, final State pState, final String pMessageKey, final Object[] pArgs, final Action pAction)
		{
			this.key = pKey;
			this.state = pState;
			this.messageKey = pMessageKey;
			this.args = pArgs;
			this.action = pAction;
		}
	}

	/** The full checklist, in step order, plus the one next action to offer and (once ready) the source to pre-pick. */
	public static final class Diagnosis
	{
		public final List<Step> steps;
		public final Action nextAction;
		/** Non-null only when {@link #ready} is true. */
		public final String pickSourceId;
		public final boolean ready;

		Diagnosis(final List<Step> pSteps, final Action pNextAction, final String pPickSourceId, final boolean pReady)
		{
			this.steps = pSteps;
			this.nextAction = pNextAction;
			this.pickSourceId = pPickSourceId;
			this.ready = pReady;
		}
	}

	/**
	 * The six-step diagnosis for {@code pFacts}, in order (connection, catalogOperation, catalogSource,
	 * scanFlows, scan, ready). A step past the first non-DONE one is WAITING, never independently
	 * evaluated - e.g. an unknown connection never even looks at {@link Facts#catalogState}, which the live
	 * side would leave at its Java default (null) anyway in that case.
	 */
	public static Diagnosis diagnose(final Facts pFacts)
	{
		final List<Step> steps = new ArrayList<Step>();
		final Step connection = SourceOnboarding.connectionStep(pFacts);
		steps.add(connection);
		final Step catalogOperation = SourceOnboarding.catalogOperationStep(pFacts, connection.state == State.DONE);
		steps.add(catalogOperation);
		final Step catalogSource = SourceOnboarding.catalogSourceStep(pFacts, catalogOperation.state == State.DONE);
		steps.add(catalogSource);
		final Step scanFlows = SourceOnboarding.scanFlowsStep(pFacts, catalogSource.state == State.DONE);
		steps.add(scanFlows);
		final Step scan = SourceOnboarding.scanStep(pFacts, scanFlows.state == State.DONE);
		steps.add(scan);
		final Step ready = SourceOnboarding.readyStep(pFacts, scan.state == State.DONE);
		steps.add(ready);

		// nextAction = the action of the first non-DONE step; if every step is DONE, ready.action (PICK) wins
		// naturally, since the loop keeps overwriting nextAction on every DONE step until it finds one that is not
		Action nextAction = Action.NONE;
		boolean found = false;
		for (final Step step : steps)
		{
			if (!found)
			{
				nextAction = step.action;
				if (step.state != State.DONE)
				{
					found = true;
				}
			}
		}

		final boolean isReady = ready.state == State.DONE;
		return new Diagnosis(Collections.unmodifiableList(steps), nextAction, isReady ? pFacts.planSourceId : null, isReady);
	}

	// -------------------------------------------------------------------------------------------------------- steps

	private static Step connectionStep(final Facts pFacts)
	{
		if (!pFacts.connectorFound)
		{
			return new Step(SourceOnboarding.KEY_CONNECTION, State.BLOCKED, "wizard.onboard.step.connection.notFound",
				new Object[] { SourceOnboarding.orEmpty(pFacts.connectorId), SourceOnboarding.orEmpty(pFacts.connectorError) }, Action.OPEN_CONNECTION);
		}
		if (!pFacts.connectorActive)
		{
			return new Step(SourceOnboarding.KEY_CONNECTION, State.BLOCKED, "wizard.onboard.step.connection.inactive",
				new Object[] { SourceOnboarding.connectorDisplay(pFacts) }, Action.OPEN_CONNECTION);
		}
		return new Step(SourceOnboarding.KEY_CONNECTION, State.DONE, "wizard.onboard.step.connection.done", new Object[] { SourceOnboarding.connectorDisplay(pFacts) },
			Action.NONE);
	}

	private static Step catalogOperationStep(final Facts pFacts, final boolean pPreviousDone)
	{
		if (!pPreviousDone)
		{
			return SourceOnboarding.waiting(SourceOnboarding.KEY_CATALOG_OPERATION);
		}
		if (pFacts.catalogOperation)
		{
			return new Step(SourceOnboarding.KEY_CATALOG_OPERATION, State.DONE, "wizard.onboard.step.catalogOperation.done",
				new Object[] { SourceOnboarding.orEmpty(pFacts.connectorType) }, Action.NONE);
		}
		// fix lot (2026-09-25): a connection with no catalog operation used to be a flat dead end here
		// (Action.NONE, "cannot be scanned into the catalog") - the deduced scan (ebx.DeducedScanRun) exists for
		// exactly this case, so offer it whenever the connection has at least one read-only listing operation;
		// only a genuinely lookup-only connection (no read-only operation at all - an enrichment, not a source)
		// stays a true dead end.
		if (pFacts.hasReadOnlyOperation)
		{
			return new Step(SourceOnboarding.KEY_CATALOG_OPERATION, State.BLOCKED, "wizard.onboard.step.catalogOperation.deduced",
				new Object[] { SourceOnboarding.orEmpty(pFacts.connectorType) }, Action.DEDUCED_SCAN);
		}
		return new Step(SourceOnboarding.KEY_CATALOG_OPERATION, State.BLOCKED, "wizard.onboard.step.catalogOperation.enrichmentOnly",
			new Object[] { SourceOnboarding.orEmpty(pFacts.connectorType) }, Action.NONE);
	}

	private static Step catalogSourceStep(final Facts pFacts, final boolean pPreviousDone)
	{
		if (!pPreviousDone)
		{
			return SourceOnboarding.waiting(SourceOnboarding.KEY_CATALOG_SOURCE);
		}
		// the tool call itself refused, or the catalog is not deployed/verified: neither is a plan outcome, both
		// take priority over reading planAction (which the live side would have left null in either case anyway)
		if (pFacts.planError != null && pFacts.planError.length() > 0)
		{
			return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.BLOCKED, "wizard.onboard.step.catalogSource.toolError",
				new Object[] { pFacts.planError }, Action.NONE);
		}
		if (pFacts.catalogState != null && !SourceOnboarding.CATALOG_STATE_VERIFIED.equals(pFacts.catalogState))
		{
			return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.BLOCKED, "wizard.onboard.step.catalogSource.catalogUnavailable",
				new Object[] { pFacts.catalogState }, Action.NONE);
		}
		if (SourceOnboarding.PLAN_ACTION_LINKED.equals(pFacts.planAction))
		{
			return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.DONE, "wizard.onboard.step.catalogSource.linked",
				new Object[] { SourceOnboarding.orEmpty(pFacts.planSourceId) }, Action.NONE);
		}
		if (SourceOnboarding.PLAN_ACTION_CREATE.equals(pFacts.planAction) || SourceOnboarding.PLAN_ACTION_SET_CONNECTOR.equals(pFacts.planAction)
			|| SourceOnboarding.PLAN_ACTION_SET_NAMESPACE.equals(pFacts.planAction))
		{
			return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.TODO, "wizard.onboard.step.catalogSource.todo", new Object[] { SourceOnboarding.orEmpty(pFacts.planMessage) },
				Action.CATALOG_APPLY);
		}
		if (SourceOnboarding.PLAN_ACTION_MANUAL.equals(pFacts.planAction))
		{
			if (SourceOnboarding.CONNECTOR_TYPE_SAMPLE_FILE.equals(pFacts.connectorType))
			{
				// two sample-file connections on the same host collide (CLAUDE.md ss14: the namespace is derived
				// from the baseUrl HOST only) - the fix a person can actually apply is to give this connection its
				// own host, so the hint says so instead of leaving them to guess from the plan's generic message
				return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.BLOCKED, "wizard.onboard.step.catalogSource.manualSampleFileHint",
					new Object[] { SourceOnboarding.orEmpty(pFacts.planMessage), SourceOnboarding.orEmpty(pFacts.connectorId) }, Action.OPEN_CONNECTION);
			}
			return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.BLOCKED, "wizard.onboard.step.catalogSource.manual",
				new Object[] { SourceOnboarding.orEmpty(pFacts.planMessage) }, Action.OPEN_CONNECTION);
		}
		// defensive fallback: the plan call answered neither an error, a state problem, nor a known action - not
		// expected on a real instance, but a forged/future Facts must still get a Step rather than a NullPointerException
		return new Step(SourceOnboarding.KEY_CATALOG_SOURCE, State.BLOCKED, "wizard.onboard.step.catalogSource.unknown", new Object[0], Action.NONE);
	}

	private static Step scanFlowsStep(final Facts pFacts, final boolean pPreviousDone)
	{
		if (!pPreviousDone)
		{
			return SourceOnboarding.waiting(SourceOnboarding.KEY_SCAN_FLOWS);
		}
		if (pFacts.scanMappingId != null)
		{
			return new Step(SourceOnboarding.KEY_SCAN_FLOWS, State.DONE, "wizard.onboard.step.scanFlows.done", new Object[] { pFacts.scanMappingId }, Action.NONE);
		}
		// the catalog source is linked/created but no scan mapping exists - never silently blocked, the same
		// CATALOG_APPLY action (create/link the source again) is offered rather than leaving a dead end
		return new Step(SourceOnboarding.KEY_SCAN_FLOWS, State.TODO, "wizard.onboard.step.scanFlows.missing", new Object[0], Action.CATALOG_APPLY);
	}

	private static Step scanStep(final Facts pFacts, final boolean pPreviousDone)
	{
		if (!pPreviousDone)
		{
			return SourceOnboarding.waiting(SourceOnboarding.KEY_SCAN);
		}
		if (pFacts.catalogRestricted)
		{
			// audit F-02 (catalog reads): what the scan left is hidden from this person - never offered as "run the scan"
			return new Step(SourceOnboarding.KEY_SCAN, State.BLOCKED, "wizard.onboard.step.scan.catalogRestricted", new Object[] { SourceOnboarding.orEmpty(pFacts.planSourceId) },
				Action.NONE);
		}
		final String status = pFacts.lastScanStatus;
		if (status == null || SourceOnboarding.STATUS_NEVER.equals(status))
		{
			return new Step(SourceOnboarding.KEY_SCAN, State.TODO, "wizard.onboard.step.scan.todoNever", new Object[0], Action.SCAN_RUN);
		}
		if (SourceOnboarding.STATUS_ERROR.equals(status))
		{
			return new Step(SourceOnboarding.KEY_SCAN, State.TODO, "wizard.onboard.step.scan.error", new Object[0], Action.SCAN_RUN);
		}
		if (SourceOnboarding.STATUS_OK.equals(status) || SourceOnboarding.STATUS_PARTIAL.equals(status))
		{
			if (pFacts.assetsScanned && pFacts.assetsWithColumns)
			{
				return new Step(SourceOnboarding.KEY_SCAN, State.DONE, "wizard.onboard.step.scan.done", new Object[] { status }, Action.NONE);
			}
			if (pFacts.assetsScanned)
			{
				// UX lot U2: objects are known but none carries a column (declared by lineage events, never read): scanning again is the way out
				return new Step(SourceOnboarding.KEY_SCAN, State.TODO, "wizard.onboard.step.scan.noColumns", new Object[] { status }, Action.SCAN_RUN);
			}
			return new Step(SourceOnboarding.KEY_SCAN, State.TODO, "wizard.onboard.step.scan.emptyResult", new Object[] { status }, Action.SCAN_RUN);
		}
		// an unrecognized status value (future enum member, or a forged Facts): treat exactly like "never run"
		// rather than crash or silently DONE - offering SCAN_RUN is always a safe action here
		return new Step(SourceOnboarding.KEY_SCAN, State.TODO, "wizard.onboard.step.scan.todoNever", new Object[0], Action.SCAN_RUN);
	}

	private static Step readyStep(final Facts pFacts, final boolean pPreviousDone)
	{
		if (!pPreviousDone)
		{
			return SourceOnboarding.waiting(SourceOnboarding.KEY_READY);
		}
		if (!pFacts.sourceExists)
		{
			// defensive: every earlier step DONE should imply the Source row exists (scanFlows/scan read off it),
			// but never assume it from this pure class alone - the live side is the one that actually re-reads it
			return new Step(SourceOnboarding.KEY_READY, State.BLOCKED, "wizard.onboard.step.ready.sourceMissing", new Object[] { SourceOnboarding.orEmpty(pFacts.planSourceId) },
				Action.NONE);
		}
		if (!pFacts.sourceActive)
		{
			return new Step(SourceOnboarding.KEY_READY, State.BLOCKED, "wizard.onboard.step.ready.sourceInactive", new Object[] { SourceOnboarding.orEmpty(pFacts.planSourceId) },
				Action.NONE);
		}
		return new Step(SourceOnboarding.KEY_READY, State.DONE, "wizard.onboard.step.ready.done", new Object[] { SourceOnboarding.orEmpty(pFacts.planSourceId) }, Action.PICK);
	}

	private static Step waiting(final String pKey)
	{
		return new Step(pKey, State.WAITING, "wizard.onboard.step.waiting", new Object[0], Action.NONE);
	}

	// ---------------------------------------------------------------------------------------------------- utilities

	private static Object orEmpty(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	/** The connector's label when it has one, its id otherwise - never blank, never null, safe as a {@code {0}} argument. */
	private static String connectorDisplay(final Facts pFacts)
	{
		if (pFacts.connectorLabel != null && pFacts.connectorLabel.length() > 0)
		{
			return pFacts.connectorLabel;
		}
		return pFacts.connectorId == null ? "" : pFacts.connectorId;
	}
}
