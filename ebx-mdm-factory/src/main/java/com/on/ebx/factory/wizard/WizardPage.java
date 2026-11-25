package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.distribution.TableDestinationDefaults;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.on.ebx.factory.landing.ChangeColumnRule;
import com.on.ebx.factory.landing.KeyEvidence;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.landing.LandingKeyChooserJson;
import com.on.ebx.factory.landing.LandingRunProgress;
import com.on.ebx.factory.matching.CandidateFilterCheck;
import com.on.ebx.factory.matching.MatchResultsView;
import com.on.ebx.factory.review.DecisionActions;
import com.on.ebx.factory.review.MatchPlanView;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewMessages;
import com.on.ebx.factory.staging.ProposalProgress;
import com.on.ebx.factory.staging.RunProgress;

/**
 * Renders the "Assistant de projet" wizard (PLAN.md, Ecrans #1; mockup screen 2, 7 steps) as an HTML
 * fragment for a {@code UserServicePane}: no {@code <html>}/{@code <head>}, the caller links
 * {@code factory/factory.css}, {@code factory/review.css} (shared {@code .mdmf-btn}/{@code .mdmf-conf}/
 * {@code .mdmf-rationale} components, same as {@link com.on.ebx.factory.review.ReviewPage}) and this
 * screen's own {@code factory/wizard.css} (stepper rail, step panels, entity cards, pipeline track -
 * nothing else has these). Pure: no EBX class, unit-tested without one.
 * <p>
 * Same technique as the sibling {@code ProductWizardPage} (every step rendered, one shown) combined with
 * the same posted-form technique as {@link com.on.ebx.factory.review.ReviewPage} (one real hidden submit
 * button, every other control a {@code type="button"} with {@code data-mdmf-*} attributes read by the
 * page's own script): unlike the product wizard, several of this wizard's steps run a real, possibly
 * slow, server action (plan/generate the landing, suggest the staging) and must re-render with fresh
 * data from EBX afterwards, so client-only step switching is not enough on its own - see
 * {@code ProjectWizardUserService}/{@code wizard.js} for how the two combine (a step is entered without a
 * round trip when it only means "show a different already-rendered panel"; an action always round-trips
 * and the server reopens on the step the hidden {@code mdmfStep} field named).
 */
public final class WizardPage
{
	private WizardPage()
	{
	}

	// ------------------------------------------------------------------------------------------------------- input

	public static final class Model
	{
		public Locale locale = Locale.ENGLISH;
		/** Wave 13 (security): the EBX login, written in {@code data-mdmf-user} of the root: {@code wizard.js} keys its browser storage by it ({@code userstore.js}). */
		public String user = "";
		public WizardSnapshot snapshot = new WizardSnapshot();
		public WizardRules.StepState[] stepStates = WizardRules.stepStates(new WizardSnapshot());
		public int currentStep = WizardRules.STEP_PROJECT;
		/** The EBX administrator: what stays with the administrator (step reset, the arming override). */
		public boolean administrator;
		/** Audit F-02: the session holds the MDM Factory right DESIGN (administrators included). */
		public boolean designer;
		/**
		 * Audit F-02: the session's widest Factory right, null for none. {@link #render} disables every action button this right
		 * does not cover ({@code core.FactoryRights#wizardAction}); null renders as before F-02 (every button enabled, the
		 * server refuses each click anyway) - a model built by a test without a session.
		 */
		public com.on.ebx.factory.core.FactoryRight right;
		/** Audit F-02: DESIGN, or the pipeline launcher role of the factory settings - the launch and intake actions ({@code FactoryRights#pipelineOperatorMay}). */
		public boolean pipelineOperator;

		/** Lot C1-B (CENTRALISATION, see {@link AccelPages}): the need typed, the packs and options ticked, the model's suggestion and its reason, a running accelerator job. */
		public String accelNeed = "";
		public final List<String> accelTickedPacks = new ArrayList<String>();
		public final List<String> accelTickedOptions = new ArrayList<String>();
		public final List<String> accelSuggestedPacks = new ArrayList<String>();
		public String accelWhy = "";
		public boolean accelJobRunning;

		public String notice;
		/** {@code "ok"} or {@code "warn"}. */
		public String noticeKind = "ok";
		/** Audit F-40: the user guide served by the module, in the person's language; empty = no Help link (a test fixture). */
		public String helpUrl = "";
		/** Audit F-43: the project's last finished background tasks, newest first ({@link RecentTasks}); empty = no list. */
		public final List<RecentTasks.Task> recentTasks = new ArrayList<RecentTasks.Task>();
		/**
		 * UX lot D (2026-10-14): how the latest landing run, configuration and matching run that ended with an outcome ended, keyed by {@link PartialFailures#LANDING}
		 * / {@link PartialFailures#CONFIGURE} / {@link PartialFailures#RUN}, with the items that failed - what the missing-items blocks and the warnings under the
		 * buttons read. Not the last ten tasks of {@link #recentTasks}: a partial failure stays unresolved however many tasks came after it.
		 */
		public final java.util.Map<String, PartialFailures.Last> lastOutcomes = new java.util.LinkedHashMap<String, PartialFailures.Last>();
		/** Audit F-43: the time of the rendering - the Recent tasks list opens by itself when its newest task has just ended. */
		public java.util.Date now = new java.util.Date();
		/** Audit F-39: the engine's raw text behind {@link #notice}, shown in a "details" fold under it; null = no fold. */
		public String noticeDetail;
		public final List<WizardRules.Problem> problems = new ArrayList<WizardRules.Problem>();

		// step 1: what is currently typed, so a validation failure re-shows it rather than the last-saved row
		public WizardRules.ProjectDraft projectDraft = new WizardRules.ProjectDraft();

		// step 2: the "add a source" mini form's transient state
		public WizardRules.SourceDraft sourceDraft = new WizardRules.SourceDraft();
		/** Set once a catalog source has been picked (a real round trip: {@code SOURCE_PICK_CATALOG}), so its schemas can be listed to choose from. */
		public String pickedCatalogSourceId = "";
		public final List<String> pickedCatalogSourceSchemas = new ArrayList<String>();

		// source-onboarding lot (2026-09-23): the "Create a catalog source" panel of step 2, entirely separate
		// from the "add an application" form above it - see WizardPage.renderSourceOnboardingPanel. Every field
		// here is transient, rebuilt on every round trip by ProjectWizardUserService from
		// ebx.SourceOnboarder, never persisted (same rule as pickedCatalogSourceId/Schemas above).
		/**
		 * Coordinator review (2026-09-23): whether the panel is expanded - {@code connections()} is a live
		 * {@code connectors_list} MCP round trip, so it must never be called on every render (every wizard
		 * postback would then pay for it, even a plain SAVE_PROJECT click). The panel's title is a real action
		 * ({@code SOURCE_ONBOARD_OPEN}) when this is false; when true, the body (combobox, checklist) renders -
		 * absent from the markup entirely when false, never merely hidden.
		 */
		public boolean onboardOpen;
		/** Every Connectors connection for the onboarding combobox - {@code ebx.SourceOnboarder#connections}, read ONLY by {@code SOURCE_ONBOARD_OPEN}/{@code SOURCE_ONBOARD_REFRESH}, never by a plain render. Empty and harmless when {@link #onboardConnectionsError} is set. */
		public final List<SourceOnboarding.ConnectionOption> onboardConnections = new ArrayList<SourceOnboarding.ConnectionOption>();
		/** Raw operational text when {@code connections()} itself failed (an {@code OperationException}) - shown as-is in the panel, never localized, same convention as {@link #previewError}. */
		public String onboardConnectionsError;
		/** The connection currently picked in the onboarding combobox - empty when none, mirrors {@link #pickedCatalogSourceId}'s own convention. */
		public String onboardConnectorId = "";
		/** The connector's own display name (label, or id when it has none) for the checklist header - carried separately since {@code ebx.SourceOnboarder.Facts} is not itself exposed to this EBX-free page. */
		public String onboardConnectorDisplay = "";
		/** {@code SourceOnboarding.diagnose} of {@link #onboardConnectorId}'s facts - null until a connection is picked. */
		public SourceOnboarding.Diagnosis onboardDiagnosis;
		/** The last {@code connectors_sync} outcome text ({@code SourceOnboarding.Facts#scanMessage}) after {@code SOURCE_ONBOARD_SCAN} - null otherwise. */
		public String onboardScanMessage;
		/** UX lot U2 (2026-10-13): the single "Add an application" list was opened, so {@link #onboardConnections} holds the connections it merges with the catalog's sources. */
		public boolean addAppOpen;
		/** UX lot U2 (2026-10-13): the ProjectSource id and code of the application of this project that already uses the diagnosed-ready connection's catalog source; empty when none does. */
		public String onboardExistingSourceId = "";
		public String onboardExistingCode = "";

		// sample-files lot (2026-09-23): the "Sources from sample files" panel, above the "Create a catalog
		// source" one - a person drops export files and gets a working source in one gesture (task brief's own
		// decision). Unlike the guided panel above, this one needs no server data to OPEN (the drop zone and its
		// table are pure client-side markup), so it is a plain data-mdmf-toggle, never a real action - only
		// SOURCE_FILES_IMPORT round-trips. These fields are transient, rebuilt by ProjectWizardUserService from
		// ebx.SampleSourceImporter on every SOURCE_FILES_IMPORT postback, empty/null otherwise (same convention
		// as previewTableId/previewRows below).
		/** {@code SampleFileRules.ReportRow} per file of the last {@code SOURCE_FILES_IMPORT} submit - empty until one has run, which also means the panel renders collapsed (see {@link #renderSampleFilesPanel}). */
		public final List<SampleFileRules.ReportRow> sampleFilesReport = new ArrayList<SampleFileRules.ReportRow>();
		/** Set when {@code mdmfSampleFiles} posted empty or unparsable although the action was {@code SOURCE_FILES_IMPORT} - task brief's own "server guard". Null otherwise. */
		public String sampleFilesError;

		// step 3: the landing action gates of whichever source the last action targeted (or the first source, by default)
		public String activeSourceId = "";
		/** The landing table a live sample was just fetched for by {@code TABLE_PREVIEW} ({@link com.on.ebx.factory.ebx.LandingPreview}), if any - empty when no preview panel should render this response. */
		public String previewTableId = "";
		public final List<String> previewColumns = new ArrayList<String>();
		public final List<List<String>> previewRows = new ArrayList<List<String>>();
		/** Null on success; an empty {@link #previewRows} with a null error means the source object legitimately has no rows. */
		public String previewError;
		/** Landing-walkthrough lot (2026-10-16): the page of step 3's walkthrough asked for ({@link LandingWalk#LAUNCH} or a ProjectSource id; resolved by {@link LandingWalk#resolvePage}), empty = the first application to validate. */
		public String walkPage = "";
		/** Landing-walkthrough lot: the person re-opened the walkthrough after a run exists ("Review the applications"); the validations are kept. */
		public boolean walkReview;
		/** Landing-walkthrough lot: ProjectSource ids whose page got the explicit "Yes, send 25 rows to the language model" choice; every other application is "No". */
		public final java.util.Set<String> walkLlmYes = new java.util.LinkedHashSet<String>();
		/** Landing key-study lot (2026-09-23): the table a key study was just run for by {@code TABLE_KEY_STUDY} ({@code ebx.KeyStudy}) - its panel renders under that table's row; empty otherwise. Kept on the service instance across the {@code TABLE_KEY_APPLY} round trip, like the preview. */
		public String keyStudyTableId = "";
		public KeyStudyReport keyStudy;

		// sources+landing redesign, lot LANDING (2026-09-24, docs/SOURCES-LANDING-LANDING.md): the one-click
		// "Prepare the landing" run - status of the latest LANDING_RUN PipelineStep for this project
		// (ebx.LandingRun.status), rebuilt on every render, never persisted on this service instance (same
		// "always read fresh" rule as staging-proposal-run's own fields right below). Same three-state shape:
		// no run yet / running (or paused on a WAITING table - still "running" for this Model's purposes, the
		// wizard keeps polling and shows the banner) / done.
		public boolean landingRunExists;
		public boolean landingRunRunning;
		public boolean landingRunStale;
		public LandingRunProgress landingRunProgress = LandingRunProgress.newRun(java.util.Collections.<LandingRunProgress.TableRef>emptyList());
		/** Live-progress lot (2026-09-26): typical duration of each stage from past runs (ebx.LandingRun#typicalDurations), for the time left. */
		public final java.util.Map<String, Long> landingRunTypicalMs = new java.util.LinkedHashMap<String, Long>();
		/** Server clock at render time, so the page's timers do not depend on the browser's clock. */
		public long nowMs = System.currentTimeMillis();
		/** The "use the language model" checkbox of the run form - defaults on, still administrators/connector-gated underneath (see {@code KeyStudy}'s new overload). */
		/** Landing test fixes (2026-10-17): the last run's choice ({@code LlmConsent}); unchecked when no choice was ever made. */
		public boolean landingRunUseLlm = false;
		/** Landing table ids the current run's progress reports {@link LandingRunProgress#WAITING} - the running screen's "N table(s) wait for you" banner and per-row "Choose the key" buttons. */
		public final List<String> landingRunWaitingTableIds = new ArrayList<String>();
		/** The landing table a key chooser panel is currently open for ({@code LANDING_KEY_CHOOSE}), if any - reuses {@link #keyStudy}'s report shape (a fresh, un-applied study of that table), rendered by {@code renderLandingKeyChooser}. */
		public String landingKeyChooserTableId = "";
		public KeyStudyReport landingKeyChooser;
		/** Set once {@code LANDING_KEY_APPLY} actually ran this response - a short confirmation line under the grid, same convention as {@link #llmRunSummary}. */
		public String landingKeyAppliedMessage;
		/** "Change the key..." (composite-key lot's {@code ebx.LandingKeyChange}) - the table its picker is currently open for, under the "Advanced" per-source table (a GENERATED/LOADED table only); empty otherwise. */
		public String landingKeyChangeTableId = "";
		/** Set once {@code LANDING_KEY_CHANGE} actually ran this response - success or refusal message, same convention as {@link #landingKeyAppliedMessage}. */
		public String landingKeyChangeMessage;
		/** Coordinator follow-up (2026-09-24, docs/mockups/sources-landing/LandingComposite.dc.html): the "Why isn't one column enough?" fold under the done view's summary, for a table whose key has 2+ columns - {@code LANDING_KEY_COMPOSITE_DETAIL} fetches a fresh {@code KeyStudy.study(..., false)} (evidence/sample only, never the language model) for exactly ONE table, kept on the instance like {@link #landingKeyChooser}. */
		public String landingCompositeDetailTableId = "";
		public KeyStudyReport landingCompositeDetail;

		// step 4: bulk-accept threshold and the last language-model pass's own summary (cost/steps), if one just ran
		public int stagingBulkThreshold = 80;
		public String llmRunSummary;

		// staging-proposal-run lot (2026-09-24): the one-click "Propose the staging" run - status of the latest
		// STAGING_PROPOSAL PipelineStep for this project (ebx.StagingProposalRun.status), rebuilt on every
		// render, never persisted on this service instance (same "always read fresh" rule as everything else in
		// this Model that isn't a transient form field).
		/** True once a run has ever been started for this project - false means the "before any proposal" intro renders and nothing else below in this group is meaningful. */
		public boolean proposalRunExists;
		public boolean proposalRunRunning;
		/** Only meaningful when {@link #proposalRunRunning}: still RUNNING past {@code StagingProposalRun.STALE_AFTER_MINUTES}. */
		public boolean proposalRunStale;
		public ProposalProgress proposalRunProgress = ProposalProgress.newRun();
		/** The checkbox state to pre-fill the "Propose the staging" form with - defaults to both on. */
		public boolean proposalRunIncludeQuality = true;
		public boolean proposalRunIncludeCapability = true;
		/** {@code core.MappingMatrixLink.url}/{@code core.FeedDesignerLink.url} for the FIRST business object/its root feed - empty when there is none yet (nothing to review). */
		public String proposalDoneMatrixUrl = "";
		public String proposalDoneDesignerUrl = "";
		/** Validation desk lot L1: {@code core.StagingDeskLink.url} for the FIRST business object - the desk is the fourth view of step 4, next to the matrix; empty when there is none yet. */
		public String proposalDoneDeskUrl = "";
		/** UX lot C3: the Mapping matrix header's "N points need your attention" ({@code MatrixCounter}), read by the service when step 4 is in review; negative = unknown. */
		public int matrixNeedYou = -1;
		/** Staging-screens lot (2026-10-17): the N of the matrix' "Accept the N proposals" button ({@code MatrixCounter.Tally#acceptAll}); negative = unknown. Quoted as "the other N go with one click". */
		public int matrixAcceptAll = -1;

		/**
		 * Staging polish (2026-10-17): the ONE way the matrix' figures reach step 4 - the service and the tests both go through here, so the sentence under
		 * "Review the proposal" can only quote {@code Tally#needYou} and {@code Tally#acceptAll}, the numbers the matrix header and footer draw.
		 */
		public void matrixFigures(final com.on.ebx.factory.mappingmatrix.MatrixCounter.Tally pTally)
		{
			this.matrixNeedYou = pTally == null ? -1 : pTally.needYou;
			this.matrixAcceptAll = pTally == null ? -1 : pTally.acceptAll;
		}

		// wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/StagingLoad.dc.html): the one-click
		// "Fill the shared structure" run at the end of step 4 - same "always read fresh" rule as the proposal
		// run above (ebx.StagingLoadRun.status), plus the grid/rejects data shown once it is not running.
		public boolean stagingLoadRunExists;
		public boolean stagingLoadRunRunning;
		public boolean stagingLoadRunStale;
		public RunProgress stagingLoadRunProgress = RunProgress.newRun(java.util.Arrays.asList("MODEL", "LOAD"));
		/** Staging-live lot (2026-09-27): the running build's job id, for the live panel's Stop (empty when none). */
		public String stagingLoadRunJobId = "";
		/** Matching-live lot (2026-09-27): the last matching action of the project (ebx.MatchingActivityReader), shown under step 6's buttons. */
		public com.on.ebx.factory.matching.MatchingActivity matchingActivity = new com.on.ebx.factory.matching.MatchingActivity();
		/** The grid ({@code ebx.StagingLoadGridReader}) - empty rows while {@link #stagingLoadRunRunning} or before any accepted business object exists. */
		public WizardSnapshot.RunGridInfo stagingLoadGrid = new WizardSnapshot.RunGridInfo();
		/** Rows still held back in STAGING across every application, unresolved - drives the "N rows held back" banner. */
		public long stagingHeldBackCount;
		/** Staging integration (2026-10-17): the causes of the held-back rows by business object ({@code ebx.RejectCauseReader}), null when not read. */
		public Map<String, com.on.ebx.factory.rejects.RejectCauses> stagingRejectCauses;
		/** The same causes for the whole project, null when not read. */
		public com.on.ebx.factory.rejects.RejectCauses stagingRejectCausesAll;
		/** {@code ebx.ui.FactoryLinks#cockpitUrl} - "where rejects are reviewed today" (task brief), opens the Cockpit's own reject list. Empty when the project has no id yet. */
		public String stagingRejectsUrl = "";
		/** Staging preflight lot H1 (2026-10-17): the last check before the build ({@code ebx.StagingPreflight#last}) while no build has run since it; null when none. Shown at the top of step 4. */
		public com.on.ebx.factory.staging.PreflightReport stagingPreflight;

		// wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/MasterStart.dc.html + MasterDone.dc.html):
		// the one-click "Build the master" run of step 5 - same shape as the staging-load run above.
		public boolean masterBuildRunExists;
		public boolean masterBuildRunRunning;
		public boolean masterBuildRunStale;
		public RunProgress masterBuildRunProgress = RunProgress.newRun(java.util.Arrays.asList("TABLES", "COPY", "MATCHING_READY"));
		/** Master-live lot (2026-09-27): the running build's job id, for the live panel's Stop (empty when none). */
		public String masterBuildRunJobId = "";
		/** The "built" grid ({@code ebx.MasterBuildGridReader}) - empty rows before the master has ever been built. */
		public WizardSnapshot.RunGridInfo masterBuildGrid = new WizardSnapshot.RunGridInfo();
		/** {@code entity name -> master row count} KPI tiles ({@link WizardSnapshot.RunGridCell#primaryValue} of the grid's own "Copy" column) - kept alongside the grid so the done view's KPI row does not need to re-walk it. */
		public final List<String[]> masterKpiTiles = new ArrayList<String[]>();
		public long masterHeldBackInStagingCount;
		/** {@code entity name -> "Open the &lt;table&gt; table" URL} - {@code ebx.ui.FactoryLinks#genericTableUrl} on the master dataset, best-effort (see {@code ebx.MasterBuildGridReader}'s own Javadoc on why the path is a guess). */
		public final Map<String, String> masterOpenTableUrls = new LinkedHashMap<String, String>();

		// sources-landing lot SOURCES (2026-09-24): SourcesRun's own status, read fresh on every render - see
		// ebx.SourcesRun's own Javadoc. One card per application currently queued/being prepared (NOT only the
		// one the person happens to have picked in the interactive combobox) - task brief: "one application
		// card per application in progress (not only the currently picked connection)".
		public boolean sourcesRunRunning;
		public boolean sourcesRunStale;
		/**
		 * Wave 11 (audit F-48): at least one application card has no facts yet, or older ones, that a background read is fetching - the root carries {@code
		 * data-mdmf-sources-checking} and {@code wizard.js} re-renders the page a few times until they have come. A card without any ({@link SourcesRunCard#pipeline}
		 * null) says "checking" instead of a pipeline.
		 */
		public boolean sourcesFactsChecking;
		public final List<SourcesRunCard> sourcesRunCards = new ArrayList<SourcesRunCard>();

		/** One "applications in progress" card: a connector id queued into {@code ebx.SourcesRun}, its live {@link ApplicationStages.Pipeline}, and the run's own coarse state/summary for that entry. */
		public static final class SourcesRunCard
		{
			public final String connectorId;
			public final String display;
			public final ApplicationStages.Pipeline pipeline;
			/** {@code SourcesProgress.WAITING}/{@code RUNNING}/{@code DONE}/{@code FAILED} - the run's OWN ledger state, independent of {@link #pipeline}'s live diagnosis (the two usually agree, but the ledger can lag by a few seconds behind a fresh diagnosis, or read {@code FAILED} while the pipeline is still {@code NEEDS_PERSON} rather than {@code READY}). */
			public final String runState;
			public final String runSummary;
			/** Visual pass (2026-09-24): {@code SourceOnboarding.Facts#connectorType} (e.g. {@code sample-file}) - drives the card's "sample file"/"live connection" kind chip, same convention as {@link WizardSnapshot.SourceInfo#platform}. Empty when unknown, read as "live connection". */
			public final String connectorType;

			public SourcesRunCard(final String pConnectorId, final String pDisplay, final ApplicationStages.Pipeline pPipeline, final String pRunState, final String pRunSummary)
			{
				this(pConnectorId, pDisplay, pPipeline, pRunState, pRunSummary, "");
			}

			public SourcesRunCard(final String pConnectorId, final String pDisplay, final ApplicationStages.Pipeline pPipeline, final String pRunState, final String pRunSummary,
				final String pConnectorType)
			{
				this.connectorId = pConnectorId;
				this.display = pDisplay;
				this.pipeline = pPipeline;
				this.runState = pRunState;
				this.runSummary = pRunSummary == null ? "" : pRunSummary;
				this.connectorType = pConnectorType == null ? "" : pConnectorType;
			}
		}

		// deduced-scan lot (2026-09-24, boards 2d/2e): ebx.DeducedScanRun's own status, read fresh on every
		// render, same "always read fresh" rule as sourcesRunRunning above - see that field's own Javadoc.
		public boolean deducedScanRunning;
		public boolean deducedScanStale;
		/** One card per {@code ProjectSource} currently queued/being deduced-scanned (board 2d). */
		public final List<DeducedScanCard> deducedScanCards = new ArrayList<DeducedScanCard>();
		/** One group per application still holding at least one {@code TO_CONFIRM} deduced table (board 2e). */
		public final List<DeducedScanPendingGroup> deducedScanPending = new ArrayList<DeducedScanPendingGroup>();
		/** Set once a confirm/leave-out/rename action just ran this response - a short confirmation line, same convention as {@link #landingKeyAppliedMessage}. */
		public String deducedScanMessage;

		/** One deduced-scan pipeline card: the {@code ProjectSource} id (this lot's own queue key, see {@code ebx.DeducedScanRun}'s Javadoc), its display name, and the live {@link DeducedScanProgress.Entry}. */
		public static final class DeducedScanCard
		{
			public final String projectSourceId;
			public final String display;
			public final DeducedScanProgress.Entry entry;
			/** Fix lot (2026-09-26, defect 14): the application's deduced assets' CURRENT decisions ({@code
			 * ebx.DeducedScanCatalog#decisionCounts}) - all zero (the pre-fix behaviour) when the caller used the
			 * 3-argument constructor, which then falls back to the run's own frozen {@code entry.summary} text. */
			public final int confirmedCount;
			public final int leftOutCount;
			public final int toConfirmCount;

			public DeducedScanCard(final String pProjectSourceId, final String pDisplay, final DeducedScanProgress.Entry pEntry)
			{
				this(pProjectSourceId, pDisplay, pEntry, 0, 0, 0);
			}

			public DeducedScanCard(final String pProjectSourceId, final String pDisplay, final DeducedScanProgress.Entry pEntry, final int pConfirmedCount,
				final int pLeftOutCount, final int pToConfirmCount)
			{
				this.projectSourceId = pProjectSourceId;
				this.display = pDisplay;
				this.entry = pEntry;
				this.confirmedCount = pConfirmedCount;
				this.leftOutCount = pLeftOutCount;
				this.toConfirmCount = pToConfirmCount;
			}
		}

		/** One application's pending deduced tables (board 2e), plus enough of the application's own header to render it (dot color key, display name). */
		public static final class DeducedScanPendingGroup
		{
			public final String projectSourceId;
			public final String display;
			public final List<com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset> assets;

			public DeducedScanPendingGroup(final String pProjectSourceId, final String pDisplay, final List<com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset> pAssets)
			{
				this.projectSourceId = pProjectSourceId;
				this.display = pDisplay;
				this.assets = pAssets;
			}
		}

		// step 6 (wizard-match-tree lot, 2026-09-23): the same MATCH review lines the Proposal review screen
		// renders (ebx.ProposalReviews.lines + attachMatchExtras + attachSurvivorshipInputs), keyed by MatchPlan
		// row id, so each plan card can show the decision tree exactly as ReviewPage does (review.MatchPlanView)
		// instead of MatchPlan.requirements as raw JSON. A plan with no entry here (the service could not build
		// the lines, or a test fixture) falls back to the plain requirements text.
		/** {@code MatchPlan} row id → its {@link ReviewLine#MATCH} line, survivorship input attached. */
		public final Map<String, ReviewLine> matchLines = new HashMap<String, ReviewLine>();
		/** Same keys and meaning as {@code ReviewPage.Input#matchCanSimulate}. */
		public final Map<String, Boolean> matchCanSimulate = new HashMap<String, Boolean>();
		/** Same keys and meaning as {@code ReviewPage.Input#matchSimulationResults} - never persisted, kept on the service instance. */
		public final Map<String, MatchPlanView.SimulationResult> matchSimulationResults = new HashMap<String, MatchPlanView.SimulationResult>();
		/** {@code ebx.MatchPlanSimulationRunner#MAX_PIVOTS}, passed in by the service so this pure page never depends on the EBX-bound side. */
		public int matchMaxSimulationPairs;
		/**
		 * The plan the current postback just acted on (Simulate, a decision, Propose another...): its details
		 * fold reopens automatically so a click inside the panel never reads as "did nothing" after the
		 * repaint - same rule as {@code ReviewPage.Input#expandedMatchRowIds}.
		 */
		public final Set<String> expandedMatchRowIds = new HashSet<String>();

		// wizard-matching-results lot (2026-09-24): the "Matching results" block (KPIs, source band, suspect
		// queue) below the plan cards/run buttons above - see WizardPage#renderStep6's own trailing loop and
		// com.on.ebx.factory.matching.MatchResultsView. Keyed by MatchPlan row id, same convention as
		// matchLines/matchSimulationResults above; a plan with no entry here (matching never activated for that
		// entity, or the plan is not yet ACCEPTED/APPLIED) simply renders no results block.
		public final Map<String, MatchResultsView.Input> matchResults = new HashMap<String, MatchResultsView.Input>();
		/** What the last postback left selected - re-injected into the hidden fields so a decision's own repaint keeps the same tab/pair open (task item 7). */
		public String matchResultsSelectedTab = "";
		public String matchResultsSelectedPairId = "";

		// step-reset lot (2026-09-23): "Clean this step and the following ones" on steps 3-6 (Landing/Staging/
		// Master/Matching) - the product owner: "Pouvoir nettoyer cette etape en cas d'erreur serait interessant. Sachant
		// que nettoyer une etape doit egalement nettoyer les suivantes." Set only after STEP_RESET_PLAN (or a
		// STEP_RESET_CONFIRM that failed and re-shows its own plan) - null means no confirmation panel is open on
		// any step. Rebuilt from ebx.StepResetter's JSON by ProjectWizardUserService, never persisted, same
		// transient-instance-state convention as previewRows/keyStudy above.
		public StepResetView stepReset;
		/** Volume-operations lot 8b (2026-09-28, task 3): true while a {@code StepResetRun} job is queued/running for this project - fed into {@link #renderJobsBanner}'s own {@code active} computation, same role as {@link #stagingLoadRunRunning}. */
		public boolean stepResetRunRunning;

		// wizard-summary lot (2026-09-24): step 7's own computed data - see WizardSummaryData's own Javadoc.
		// Built fresh by ProjectWizardUserService.model() on every render (a status/readiness screen must never
		// show stale data), null only before that class has ever run (a WizardPageTest fixture that does not
		// care about step 7 leaves it null - renderStep7 renders an empty-state message then).
		public WizardSummaryData.Output summary;
		/** The SAME {@link WizardSummaryData.Input} {@link #summary} was computed from - kept alongside it purely so
		 * {@code renderProductionCards}/the banner can read the raw gate booleans/URLs ({@code canLaunchPipeline},
		 * {@code cockpitUrl}...) that {@link WizardSummaryData.Output} deliberately does not carry (that class
		 * only computes the verdict/journey/zones/open-points/completeness/journal - the "go to production"
		 * gates are plain pass-through, not worth a pure-class round trip). Null exactly when {@link #summary} is. */
		public WizardSummaryData.Input summaryInput;
		/** Set by {@code DELIVERY_CHECK} (read-only: {@code ebx.DeliveryDomainReader}) - a one-line component/precheck summary shown under that button, same "transient instance state" convention as {@link #llmRunSummary}. Null otherwise. */
		public String deliveryCheckSummary;

		// distribution-wizard lot (2026-09-24, step 7 "Distribution", boards Main/Modes/DataProduct.dc.html): see
		// DistributionModel's own Javadoc. Built by ProjectWizardUserService#buildDistribution, same "only when
		// this step is the one being shown, or on the very first render" rule buildSummary already established
		// (fix/wizard-live-wave1 bug 7) - every other step's postback pays nothing extra for it. Null exactly
		// when that method never ran (a WizardPageTest fixture indifferent to step 7 leaves it null - renderStep7
		// then shows the wizard.step7dist.empty notice, never a stale table).
		public DistributionModel distribution;
		/**
		 * perf/wizard-distribution-lazy (2026-09-27): true when {@link #distribution} was deliberately NOT built
		 * because step 7 is not the step this render shows - its connection list costs one Connectors round trip
		 * per connector and its data-product check a live warehouse query. renderStep7 then renders a placeholder
		 * carrying {@code data-mdmf-deferred-step}, and wizard.js asks the server for step 7 as soon as the panel
		 * is shown client side.
		 */
		public boolean distributionDeferred;
		/**
		 * Wave 2 (2026-09-30): the step 8 (summary) model was not built for this render - another step is the one shown - so its panel
		 * is a placeholder that loads itself (wizard.js loadDeferredStep: the same STEP_VIEW round trip step 7 uses). Same reason: the
		 * summary reads the master tables.
		 */
		public boolean summaryDeferred;
		/**
		 * Wave 11 (audit F-42): write only the step shown ({@link #currentStep}) in full and a placeholder for each other step ({@link WizardPage#render}). Set by
		 * {@code ProjectWizardUserService#model} (off with {@code -Debx.factory.wizard.allSteps=true}); false here, so a renderer test that looks at a step
		 * other than the current one keeps seeing it, exactly as before.
		 */
		public boolean currentStepOnly;
		/**
		 * Wave 11 (audit F-42), step 4: the business objects a person opened while the cards are folded ({@link WizardPage#ENTITY_FOLD_THRESHOLD}), by entity id, and
		 * the filter they typed - plain state of {@code ProjectWizardUserService}, kept across postbacks and never persisted. After "Unfold all" ({@link #stagingOpenAll})
		 * every card is open except the ids in {@link #stagingClosedEntities}.
		 */
		public final java.util.Set<String> stagingOpenEntities = new java.util.LinkedHashSet<String>();
		public final java.util.Set<String> stagingClosedEntities = new java.util.LinkedHashSet<String>();
		/** Wave 11 (audit F-42): the groups of step 4's "decisions left" card a person opened past {@link WizardPage#STILL_GROUP_LIMIT} items ({@code fields}, {@code rules}, {@code checks}, {@code toApply}, {@code links}). */
		public final java.util.Set<String> stillOpenGroups = new java.util.LinkedHashSet<String>();
		public boolean stagingOpenAll;
		public String stagingFilter = "";
		/**
		 * Automatic-intake wizard lot (2026-10-05): the intake's status and the screen's own transient state, built by {@code ProjectWizardUserService#buildIntake} only when step 8
		 * is the one shown - null otherwise (and in a fixture indifferent to the intake: the Summary then simply has no intake block). See {@link IntakeScreens}.
		 */
		public IntakeScreens.Input intake;

		public boolean projectExists()
		{
			return this.snapshot.project != null && !this.snapshot.project.code.isEmpty();
		}
	}

	/** One item of a {@link StepResetView} - same shape as {@code project.StepResetPlan.Item}, EBX-free. */
	public static final class StepResetItem
	{
		public String kind = "";
		public String name = "";
		public String detail = "";
		/** {@code DELETE}/{@code EXCLUDED}/{@code REFUSED}. */
		public String disposition = "";
		/** Non-empty only when {@link #disposition} is not {@code DELETE}. */
		public String reason = "";
	}

	/**
	 * The confirmation panel's own data, for whichever ONE step it is currently open on ({@link #stage}) - see
	 * {@link Model#stepReset}. Built from {@code ebx.StepResetter.plan}'s JSON (open the panel) or {@code
	 * .reset}'s JSON (after a confirmed cleanup), by {@code ProjectWizardUserService}.
	 */
	public static final class StepResetView
	{
		/** {@code LANDING}/{@code STAGING}/{@code MASTER}/{@code MATCHING} - which step's panel this is. */
		public String stage = "";
		/** Non-null: the whole cleanup is refused (see {@code project.StepResetPlan.Plan#refusal}) - the confirm field is not rendered. */
		public String refusal;
		public final List<StepResetItem> items = new ArrayList<StepResetItem>();
		public final Map<String, Integer> rowCounts = new LinkedHashMap<String, Integer>();
		public final List<String> stepNames = new ArrayList<String>();
		/** Set once {@code STEP_RESET_CONFIRM} has actually run for this rendering - switches the panel to a short result notice instead of the confirm form. */
		public boolean done;
		/** The outcome text shown once {@link #done} - success summary, or the refusal/error message on failure. */
		public String resultMessage;
		public boolean resultOk;
		/**
		 * Volume-operations lot 8b (2026-09-28, task 3): set right after {@code STEP_RESET_CONFIRM} enqueues the
		 * cleanup as a {@code StepResetRun} job - the panel shows a "running in the background" notice instead
		 * of the confirm form (mutually exclusive with {@link #done}: the jobs banner's own {@code JOBS_POLL}
		 * re-render is what eventually flips this to {@link #done}, never this rendering itself).
		 */
		public boolean running;
	}

	// ------------------------------------------------------------------------------------------- distribution (step 7)

	/** One row of {@code DistributionTarget} (docs/DISTRIBUTION.md), as {@code ebx.DistributionReader.listTargets} returns it - board {@code Main.dc.html}'s target list and {@code Modes.dc.html}'s own setup panel. */
	public static final class DistributionTargetRow
	{
		public String id = "";
		/** The connection's own display label (board: "SF Salesforce CRM") - {@code connector} id kept separately for the setup form. */
		public String connector = "";
		public String connectorLabel = "";
		public String operation = "";
		/** {@code REALTIME}/{@code ON_DEMAND}/{@code SCHEDULED}. */
		public String mode = "ON_DEMAND";
		/** {@code DRAFT}/{@code WAITING}/{@code ARMED}/{@code PAUSED}/{@code DISABLED}. */
		public String state = "DRAFT";
		public String lastStatus = "";
		public String lastMessage = "";
		public String lastSendAt = "";
		/** {@code CHANGED}/{@code ALL} - {@code ON_DEMAND}/{@code SCHEDULED} only. */
		public String scope = "CHANGED";
		public String cron = "";
		public boolean approval;
		public String watchedFields = "";
		public boolean includeCrosswalk;
		public boolean includePending;
		public boolean armedViaOverride;
		/** Round 5 (2026-09-26, task brief item 1): the {@code connectors_sync} run id of the batch that actually armed this
		 * target - blank when the target was armed via the admin override instead. This, not {@code state == ARMED} alone,
		 * is what the 7b checklist's "First full send done" line ticks on. */
		public String firstFullSendRunId = "";
		public String connectorsMappingIds = "";
		/** Round 6 (task brief item C): raw {@code "tablePath=destination"} pairs (distribution.TargetMappingList) - only meaningful when the target's connector supports a per-table destination (Kafka). */
		public String tableDestinations = "";
		/**
		 * "Retire notices from the merge log" (SPEC-retire.md, UI lot): {@code NOTICE} (default) / {@code
		 * TOMBSTONE} / {@code NONE} - see {@code core.FactoryPaths.DistributionTarget.RETIRE_MODE_*}.
		 * {@code TOMBSTONE} is selectable here (it is a valid stored value) but {@code
		 * ebx.DistributionConfigurator#configure} refuses it until the connector supports it - the wizard only
		 * shows a static hint, the refusal message itself surfaces through the same {@code
		 * DistributionModel#error} path every other Configure failure already uses.
		 */
		public String retireMode = "NOTICE";
		/** Same lot: default {@code true} on the engine side (EBX XSD default) - mirrored here so a brand-new, unsaved target's checkbox starts checked. */
		public boolean retireUndoneMerges = true;
		/** Lot S2 (2026-10-18): RETIRE notices of this target's history that reached it (a lower bound when {@link #retiresCapped}), and when the merge states were last compared (ISO-8601, blank before the first time). Filled for the open target only. */
		public int retiresSent;
		public boolean retiresCapped;
		public String lastSweepAt = "";
		/** Same lot: this target's own recent send history (board {@code RetireHistory.dc.html}) - populated only for the currently open target ({@link ProjectWizardUserService#buildDistribution}), never for the plain 7a list rows. */
		public final List<EventRow> events = new ArrayList<EventRow>();
	}

	/**
	 * One row of a target's send history ({@code ebx.DistributionReader#listEvents}) - "retire notices from
	 * the merge log" (SPEC-retire.md, UI lot). Pure DTO, no EBX type, so {@link WizardPageTest} can hand-build
	 * one without a live read.
	 */
	public static final class EventRow
	{
		public String id = "";
		public String tablePath = "";
		public String recordKey = "";
		/** {@code CREATE}/{@code MODIFY}/{@code DELETE}/{@code RETIRE}. */
		public String change = "";
		/** {@code PENDING}/{@code SENT}/{@code FAILED}/{@code SKIPPED}. */
		public String status = "";
		public String message = "";
		/** Set only on a RETIRE/CREATE the merge-log watch itself wrote (empty for a plain trigger-written event) - {@code MergeLogWatch}, {@code DistributionEvent.REASON}. {@code MERGE}/{@code UNMERGE}/blank. */
		public String reason = "";
		public String mergeId = "";
		/** The golden a RETIRE row's record was merged into - blank for a RETIRE with no survivor (a deleted golden, or a member with no goldenKey). */
		public String survivorKey = "";
		public String createdAt = "";
	}

	/** One connection candidate for "+ Add a connection" - {@code ebx.DistributionConnections}. */
	public static final class DistributionConnectionRow
	{
		public String connector = "";
		public String label = "";
		/** {@code connectors_describe}'s own connector {@code type} (e.g. {@code kafka}) - round 6 (task brief item C): only a {@code kafka} connector offers the per-table "Table -&gt; topic" rows below. */
		public String type = "";
		public boolean hasWriteOperation;
		public final List<String> writeOperations = new ArrayList<String>();
		/** Task brief item 4: a connector-level destination (Kafka's own {@code topic} property) - blank for an ordinary EBX/dataset-targeted connector. */
		public String topic = "";
		/** The connector's own {@code key.field} property (Kafka: the field used as the message key) - blank when the connector has none. */
		public String keyField = "";
	}

	/** Round 6 (task brief item C): one of the project's master tables, for the per-table "Table -&gt; topic" rows of 7b. */
	public static final class MasterTableRow
	{
		public String tablePath = "";
		public String label = "";
	}

	/** {@code DistributionArmingGate.Readiness}/{@code .Decision} for the project, as board {@code Modes.dc.html}'s arming checklist shows it. */
	public static final class DistributionReadinessRow
	{
		public boolean landingLoadedFromEverySource;
		public boolean masterBuilt;
		public boolean firstMatchingRunFinished;
		/** Distribution round 4 (task brief item 3): true when the project has no matching plan at all - see {@code DistributionArmingGate.Readiness#noMatchingPlanned}'s own Javadoc. */
		public boolean noMatchingPlanned;
		public String landingDetail = "";
		public String masterDetail = "";
		public String matchingDetail = "";

		public boolean matchingSatisfied()
		{
			return this.firstMatchingRunFinished || this.noMatchingPlanned;
		}

		public boolean pipelineReady()
		{
			return this.landingLoadedFromEverySource && this.masterBuilt && this.matchingSatisfied();
		}
	}

	/** One row of the "What the product contains" table (board {@code DataProduct.dc.html}) - {@code ProductPlan.Candidate}/{@code PlannedTable}. */
	public static final class DataProductTableRow
	{
		public String key = "";
		public String label = "";
		public String from = "";
		public long rowCount;
		public boolean included = true;
		/** {@code OWN}/{@code NESTED} - only meaningful for a child candidate. */
		public String nestMode = "OWN";
		public boolean child;
		public boolean ebxTable = true;
		public String publishedName = "";
		/** Coordinator review (2026-09-24): the host master entity's own label, for a child row's "Nested in &lt;PARENT&gt;" select option - empty for a non-child row. */
		public String parentLabel = "";
		/** Coordinator review (2026-09-24): a catalogue asset added via "+ Add assets from the catalogue" this session - the board's own "added" tag (never set for a default master/crosswalk row). */
		public boolean added;
	}

	/** Step 7c "Data product" - {@code ebx.DataProductPublication.detect}/{@code .plan}, {@code distribution.product.ProductPlan}. */
	public static final class DataProductModel
	{
		public boolean catalogInstalled;
		/** Audit F-02 (catalog reads): the person's catalog right hides some of the catalog's assets offered here. */
		public boolean catalogRestricted;
		public boolean snowflakePresent;
		/** Audit F-2-101: true once a person ran "Test the connection"; the probe never runs from a display, so {@link #snowflakeReachable} means nothing before. */
		public boolean snowflakeTested;
		public boolean snowflakeReachable;
		public String snowflakeDetail = "";
		public String snowflakeConnectorId = "";
		public boolean databricksPresent;
		public String databricksDetail = "";
		public final List<DataProductTableRow> tables = new ArrayList<DataProductTableRow>();
		/** Catalogue assets available under "+ Add assets from the catalogue", not yet added. */
		public final List<DataProductTableRow> addableAssets = new ArrayList<DataProductTableRow>();
		public String productName = "";
		public String code = "";
		public String owner = "";
		public String domain = "";
		public String description = "";
		/** {@code SNOWFLAKE}/{@code DATABRICKS}/{@code EBX_ONLY}. */
		public String target = "EBX_ONLY";
		public String schema = "";
		/** {@code ON_DEMAND}/{@code HOURLY}/{@code DAILY}/{@code WEEKLY}. */
		public String refreshMode = "ON_DEMAND";
		public boolean marketplaceListing;
		public int contractColumnCount;
		public final List<String> creationSteps = new ArrayList<String>();
		public final List<String> problems = new ArrayList<String>();
		public final List<String> warnings = new ArrayList<String>();
		/** Set once the product already exists (a re-open of an already-created product) - {@code DataProduct.code} found. */
		public boolean created;
		public String moreSettingsUrl = "";
	}

	/**
	 * Step 7 ("Distribution", boards {@code Main.dc.html}/{@code Modes.dc.html}/{@code DataProduct.dc.html}) -
	 * see {@code docs/WIZARD-DISTRIBUTION.md}. Built fresh by {@code ProjectWizardUserService#buildDistribution}
	 * every time this step is the one shown (or on the wizard's very first render - same rule {@code
	 * buildSummary} uses, since {@code wizard.js} switches steps purely client-side once every panel is
	 * rendered). Pure DTO: no EBX class, so {@code WizardPageTest} can hand-build one.
	 */
	public static final class DistributionModel
	{
		/** "The master holds N golden records" (board {@code Main.dc.html}) - 0 renders no such sentence. */
		public long masterGoldenCount;
		public String masterEntityLabel = "";
		/**
		 * Step 7 screen counts (2026-10-18): what the master holds, said with the right figures - the main object ({@link MasterMain}), its golden records ({@code -1} = no
		 * Match and Merge state, its rows are the figure), its rows, and the rows and number of tables of the whole master. {@link #masterGoldenCount} used to be the golden
		 * records of ALL the tables added up (212 + 534 + 234 = 980 on E2EMASTER20261018181651), which the screen called "golden records".
		 */
		public String masterMainEntity = "";
		public long masterMainGolden = -1L;
		public long masterMainRows;
		public long masterTotalRows;
		public int masterTableCount;
		/** The codes of the other Factory projects and the connections their distribution targets use: a connection made for ANOTHER project is not a likely target of this one. */
		public final java.util.Set<String> otherProjectCodes = new java.util.LinkedHashSet<String>();
		public final java.util.Set<String> otherProjectConnectors = new java.util.LinkedHashSet<String>();
		public final List<DistributionTargetRow> targets = new ArrayList<DistributionTargetRow>();
		public final List<DistributionConnectionRow> connections = new ArrayList<DistributionConnectionRow>();
		/** Round 6 (task brief item C): the project's own master tables, for the per-table "Table -&gt; topic" rows of 7b. */
		public final List<MasterTableRow> masterTables = new ArrayList<MasterTableRow>();
		public DistributionReadinessRow readiness = new DistributionReadinessRow();
		public DataProductModel product = new DataProductModel();
		/** Empty = the 7a list view; a target's id, or {@code "NEW"}, = its 7b setup panel is open. */
		public String openTargetId = "";
		/** The target being set up (a copy for a known id, a blank default for {@code "NEW"}) - null only when {@link #openTargetId} is empty. */
		public DistributionTargetRow openTarget;
		public boolean productPanelOpen;
		/** Audit F-2-101: the connections come from a per-person cache. Reading = a background read is queued or running; Read = at least one read succeeded; the age is that read's. */
		public boolean connectionsReading;
		public boolean connectionsRead;
		public long connectionsAgeSeconds;
		public String connectionsError = "";
		public String notice;
		public String error;
		/** Task brief item 4: every field name of the project's master table(s), union across every built entity - used only to warn when a Kafka-style connection's own {@code key.field} does not name a real master field. */
		public final java.util.Set<String> masterFieldNames = new java.util.LinkedHashSet<String>();
	}

	// ------------------------------------------------------------------------------------------------------ render

	public static String render(final Model pModel)
	{
		final Locale locale = pModel.locale == null ? Locale.ENGLISH : pModel.locale;
		final StringBuilder out = new StringBuilder(16384);
		// staging-proposal-run lot (2026-09-24): read by wizard.js to decide whether to keep polling (a
		// resubmit every 5s while a run is RUNNING) - see that file's own "staging-proposal-run lot" comment.
		// sources-landing lots SOURCES + LANDING (2026-09-24): same polling pattern, read by wizard.js's own
		// SOURCES_RUN_POLL / LANDING_RUN_POLL blocks.
		out.append("<div class=\"mdmf mdmf-wizard\"").append(pModel.user == null || pModel.user.isEmpty() ? "" : " data-mdmf-user=\"" + esc(pModel.user) + "\"").append(pModel.proposalRunRunning ? " data-mdmf-proposal-running=\"true\"" : "")
			.append(pModel.sourcesRunRunning ? " data-mdmf-sources-running=\"true\"" : "")
			.append(pModel.sourcesFactsChecking ? " data-mdmf-sources-checking=\"true\"" : "")
			// deduced-scan lot (2026-09-24): same polling pattern, read by wizard.js's own DEDUCED_SCAN_POLL block.
			.append(pModel.deducedScanRunning ? " data-mdmf-deduced-scan-running=\"true\"" : "")
			.append(pModel.landingRunRunning ? " data-mdmf-landing-running=\"true\"" : "")
			// live bug fix (coordinator, 2026-09-24, bug 3): data-mdmf-landing-running alone (above) also keeps
			// being "true" for as long as the run is PAUSED on a WAITING table (see LandingRun's own "the parent
			// step stays RUNNING through a WAITING pause" note) - it decides which of the three step-3 screens
			// renders, never whether wizard.js should actually resubmit the form. A separate, narrower attribute
			// for the poll itself: only "true" while something is genuinely still TODO/RUNNING (landingRunProgress
			// is not "over" - see LandingRunProgress#isOver) AND no key chooser panel is open on this render (a
			// poll mid-edit would silently drop every "Several columns together" checkbox the person just ticked,
			// since the poll is a full page reload). Once every remaining table is WAITING for a person, nothing
			// is actually running in the background any more, so there is nothing left to poll FOR until that
			// person acts - LANDING_KEY_APPLY resumes real work (and, via landingKeyChooserTableId being cleared
			// server-side, reopens the door for this attribute), which is what "resumes polling" in practice.
			.append(pModel.landingRunRunning && !pModel.landingRunProgress.isOver() && pModel.landingKeyChooserTableId.isEmpty() ? " data-mdmf-landing-poll=\"true\""
				: "")
			// wizard-visual-pass lot (2026-09-24): same polling pattern, read by wizard.js's own
			// STAGING_LOAD_RUN_POLL / MASTER_BUILD_RUN_POLL blocks.
			.append(pModel.stagingLoadRunRunning ? " data-mdmf-staging-load-running=\"true\"" : "")
			.append(pModel.masterBuildRunRunning ? " data-mdmf-master-build-running=\"true\"" : "").append(">");
		WizardPage.renderHiddenFields(out, pModel);
		WizardPage.renderProgress(out);
		WizardPage.renderJobsBanner(out, pModel);
		if (pModel.notice != null)
		{
			// id: wizard.js removes it when the person moves to another step on the client (UX lot U3: it used to stay above every later step)
			out.append("<div id=\"mdmfNotice\" class=\"mdmf-notice mdmf-notice-").append("warn".equals(pModel.noticeKind) ? "warn" : "ok").append("\" role=\"alert\">")
				.append(WizardPage.esc(pModel.notice));
			WizardPage.renderNoticeDetail(out, pModel.noticeDetail, locale);
			out.append("</div>");
		}
		// audit F-43: how the project's last tasks ended - read from FactoryJob, so from any tab, after a restart, the next day
		if (pModel.projectExists())
		{
			out.append(RecentTasks.render(pModel.recentTasks, locale, pModel.now, null, WizardPage.styleOf(pModel), pModel.currentStep));
		}

		out.append("<div class=\"mdmf-wiz\" id=\"mdmfWizRoot\">");
		WizardPage.renderTopbar(out, pModel, locale);
		out.append("<div class=\"mdmf-wiz-main\">");
		// wave 11 (audit F-42): only the step shown is written in full; every other step is a small placeholder that wizard.js replaces by a STEP_VIEW round trip
		// the moment the step is opened (the mechanism steps 6, 7 and 8 already used for their heavy parts) - see Model#currentStepOnly
		for (final int step : WizardRules.steps(pModel.snapshot))
		{
			if (pModel.currentStepOnly && step != pModel.currentStep)
			{
				WizardPage.renderStepPlaceholder(out, pModel, locale, step);
				continue;
			}
			switch (step)
			{
				case WizardRules.STEP_PACK:
				case WizardRules.STEP_SPECIFICATION:
				case WizardRules.STEP_PROPOSAL:
				case WizardRules.STEP_GENERATION:
					AccelPages.render(out, pModel, locale, step);
					break;
				case 1:
					WizardPage.renderStep1(out, pModel, locale);
					break;
				case 2:
					WizardPage.renderStep2(out, pModel, locale);
					break;
				case 3:
					WizardPage.renderStep3(out, pModel, locale);
					break;
				case 4:
					WizardPage.renderStep4(out, pModel, locale);
					break;
				case 5:
					WizardPage.renderStep5(out, pModel, locale);
					break;
				case 6:
					WizardPage.renderStep6(out, pModel, locale);
					break;
				case 7:
					WizardPage.renderStep7(out, pModel, locale);
					break;
				default:
					if (WizardRules.CENTRALISATION.equals(pModel.snapshot.project == null ? null : pModel.snapshot.project.style))
					{
						AccelPages.render(out, pModel, locale, step);
					}
					else
					{
						WizardPage.renderStep8(out, pModel, locale);
					}
					break;
			}
		}
		WizardPage.renderNav(out, pModel, locale);
		out.append("</div></div></div>");
		return pModel.right == null ? out.toString() : WizardPage.disableDenied(out.toString(), pModel.right, pModel.pipelineOperator, locale);
	}

	/**
	 * Wave 11 (audit F-42): the panel of a step that is not the one shown, when {@link Model#currentStepOnly} is on - its title and one line, in a section
	 * {@code wizard.js} finds like any other ({@code .mdmf-wiz-panel[data-step]}). {@code data-mdmf-deferred-step} is what {@code loadDeferredStep} reacts to when
	 * the person opens the step (Back/Next, the rail, a "go to step" link): it posts {@code STEP_VIEW} and the server answers with that step in full.
	 * The button is the way in when the automatic load did not fire.
	 */
	private static void renderStepPlaceholder(final StringBuilder pOut, final Model pModel, final Locale pLocale, final int pStep)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"").append(pStep).append("\" hidden=\"hidden\">");
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, WizardPage.STEP_KEYS[pStep]), null);
		// landing-walkthrough lot (2026-10-16): the text no longer claims "Loading..." when nothing loads (a step shown client side with no round trip stayed on
		// it for good); wizard.js swaps it for the loading line the moment it asks the server for the step (data-mdmf-loading-text)
		final String stepName = WizardMessages.get(pLocale, WizardPage.STEP_KEYS[pStep]);
		pOut.append("<div class=\"mdmf-notice mdmf-notice-target\" data-mdmf-deferred-step=\"").append(pStep).append("\" data-mdmf-loading-text=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step.deferred.loading", stepName))).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step.deferred"))).append("</div>")
			.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"STEP_VIEW\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step.deferred.load", stepName))).append("</button></div></section>");
	}

	/** Audit F-39: the engine's raw text under a localized notice, folded ({@code wizard.css} {@code .mdmf-notice-details}). */
	static void renderNoticeDetail(final StringBuilder pOut, final String pDetail, final Locale pLocale)
	{
		if (pDetail == null || pDetail.trim().isEmpty())
		{
			return;
		}
		pOut.append("<details class=\"mdmf-notice-details\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "engine.details")))
			.append("</summary><div class=\"mdmf-notice-detail-text\">").append(WizardPage.esc(pDetail)).append("</div></details>");
	}

	private static final java.util.regex.Pattern ACTION_ELEMENT = java.util.regex.Pattern.compile("<(button|input|select|a)\\b([^>]*?)\\sdata-mdmf-action=\"([A-Z0-9_]*)\"([^>]*)>");

	/**
	 * Audit F-02: the read-only wizard. Every element carrying a {@code data-mdmf-action} that {@code pGranted} does not cover
	 * ({@code core.FactoryRights#wizardAction}) is rendered disabled, with {@code data-mdmf-denied="true"} (wizard.js ignores its
	 * clicks) and a title naming the right it needs; a notice says the page is read only when DESIGN is not held. The server
	 * refuses each action anyway ({@code ProjectWizardUserService#handle}): this only avoids offering what the user cannot do.
	 * An action the pipeline launcher role opens ({@code FactoryRights#pipelineOperatorMay}) stays enabled when
	 * {@code pPipelineOperator} says the session holds that role. Pure, tested offline.
	 */
	public static String disableDenied(final String pHtml, final com.on.ebx.factory.core.FactoryRight pGranted, final boolean pPipelineOperator, final Locale pLocale)
	{
		final java.util.regex.Matcher matcher = WizardPage.ACTION_ELEMENT.matcher(pHtml);
		final StringBuffer out = new StringBuffer(pHtml.length() + 512);
		boolean anyDenied = false;
		while (matcher.find())
		{
			final String action = matcher.group(3);
			final com.on.ebx.factory.core.FactoryRight needed = com.on.ebx.factory.core.FactoryRights.wizardAction(action);
			if (com.on.ebx.factory.core.FactoryRight.allows(pGranted, needed) || pPipelineOperator && com.on.ebx.factory.core.FactoryRights.pipelineOperatorMay(action))
			{
				matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement(matcher.group(0)));
				continue;
			}
			anyDenied = true;
			final String before = matcher.group(2);
			final String after = matcher.group(4).replaceAll("\\s(disabled|title)=\"[^\"]*\"", "");
			final String title = WizardMessages.get(pLocale, "access.disabledTitle", needed.name());
			matcher.appendReplacement(out, java.util.regex.Matcher.quoteReplacement("<" + matcher.group(1) + before.replaceAll("\\s(disabled|title)=\"[^\"]*\"", "")
				+ " data-mdmf-action=\"" + action + "\"" + after + " disabled=\"disabled\" data-mdmf-denied=\"true\" title=\"" + WizardPage.esc(title) + "\">"));
		}
		matcher.appendTail(out);
		if (!anyDenied || pGranted.covers(com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			return out.toString();
		}
		// the read-only notice, right after the hidden fields block opens the page
		final String notice = "<div class=\"mdmf-notice mdmf-notice-warn\" role=\"note\" data-mdmf-readonly=\"true\">"
			+ WizardPage.esc(WizardMessages.get(pLocale, "access.readOnly", pGranted.name())) + "</div>";
		final int root = out.indexOf("<div class=\"mdmf-wiz\" id=\"mdmfWizRoot\">");
		return root < 0 ? notice + out : out.substring(0, root) + notice + out.substring(root);
	}

	/**
	 * Lot C2 (2026-09-24): the background-jobs banner, filled by {@code wizard.js} from {@code GET
	 * /ebx-mdm-factory/rest/v1/projects/{code}/jobs} every few seconds (queued with its place in line, running with
	 * its stage message, a Stop/Cancel button). It replaces the 5-second full-page self-polls: the page itself is
	 * re-rendered once, when a job it saw running ends. {@code data-mdmf-jobs-active} tells the script that this
	 * render already shows a run in progress, so a job that ended between the render and the first poll still
	 * triggers that one re-render.
	 */
	static void renderJobsBanner(final StringBuilder pOut, final Model pModel)
	{
		if (pModel.snapshot.project == null || pModel.snapshot.project.code.isEmpty())
		{
			return;
		}
		final boolean active = pModel.proposalRunRunning || pModel.sourcesRunRunning || pModel.deducedScanRunning || pModel.landingRunRunning
			|| pModel.stagingLoadRunRunning || pModel.masterBuildRunRunning
			// matching-live lot (2026-09-27): a matching action of a few seconds may end before the first poll - the
			// page must still re-render once to show its per-entity results
			|| pModel.matchingActivity != null && pModel.matchingActivity.isLive()
			// volume-operations lot 8b (2026-09-28, task 3): a step-reset job, same role as the run flags above
			|| pModel.stepResetRunRunning || pModel.accelJobRunning;
		pOut.append("<div class=\"mdmf-jobs\" id=\"mdmfJobs\" role=\"status\" aria-live=\"polite\" style=\"display:none\" data-mdmf-jobs-url=\"")
			.append(WizardPage.esc(WizardPage.JOBS_REST + "projects/" + WizardPage.urlSegment(pModel.snapshot.project.code) + "/jobs?lang=" + WizardPage.esc(pModel.locale == null ? "en" : pModel.locale.getLanguage())))
			.append("\" data-mdmf-jobs-cancel=\"").append(WizardPage.esc(WizardPage.JOBS_REST + "jobs/")).append("\"")
			.append(active ? " data-mdmf-jobs-active=\"true\"" : "")
			// a paused landing run (a table WAITING for a key) is "running" for the step screens but has no live job
			.append(pModel.landingRunRunning && pModel.landingRunProgress.isOver() ? " data-mdmf-jobs-paused=\"true\"" : "").append("></div>");
	}

	/** The module's REST base, as the browser reaches it (same origin as the EBX pages). */
	static final String JOBS_REST = "/ebx-mdm-factory/rest/v1/";

	/** One URL path segment, percent-encoded as UTF-8 (a project code is free text). */
	static String urlSegment(final String pValue)
	{
		try
		{
			return java.net.URLEncoder.encode(pValue, "UTF-8").replace("+", "%20");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static void renderHiddenFields(final StringBuilder pOut, final Model pModel)
	{
		pOut.append(AccelPages.hiddenFields()).append(WizardPage.hidden("mdmfAction", "")).append(WizardPage.hidden("mdmfStep", String.valueOf(pModel.currentStep)))
			.append(WizardPage.hidden("mdmfNature", "")).append(WizardPage.hidden("mdmfId", "")).append(WizardPage.hidden("mdmfParams", "")).append(WizardPage.hidden("mdmfSourceId", pModel.activeSourceId))
			.append(WizardPage.hidden("mdmfTableId", "")).append(WizardPage.hidden("mdmfKeyColumns", "")).append(WizardPage.hidden("mdmfIntakeSource", ""))
			// volume wave 2, lot V2-E: a table's change-column selector and the re-run's "Full refresh" checkbox, copied by wizard.js right before submit
			.append(WizardPage.hidden("mdmfChangeColumn", "")).append(WizardPage.hidden("mdmfChangeKind", "")).append(WizardPage.hidden("mdmfLandingRunFullRefresh", "false"))
			.append(WizardPage.hidden("mdmfCatalogSourceId", pModel.pickedCatalogSourceId))
			// source-onboarding lot (2026-09-23): the onboarding combobox's own picked connection id, same
			// technique as mdmfCatalogSourceId above (written by the combobox's script, then SOURCE_ONBOARD_PICK
			// is clicked to round-trip and read its facts/diagnosis)
			.append(WizardPage.hidden("mdmfOnboardConnector", pModel.onboardConnectorId))
			.append(WizardPage.hidden("mdmfBulkThreshold", String.valueOf(pModel.stagingBulkThreshold)))
			// threshold-left-out lot (2026-09-23): the per-card "include below threshold" checkbox, synced by
			// wizard.js into this hidden field right before submit - same always-blank-on-render idea as
			// mdmfComment/mdmfSeverity below.
			.append(WizardPage.hidden("mdmfIncludeBelowThreshold", ""))
			.append(WizardPage.hidden("mdmfMatchScope", "incremental"))
			// inline-reviews lot (2026-09-23): mdmfComment (the MATCH mini-panels' own comment/reason, filled by
			// wizard.js from the relevant <textarea> right before submit) and mdmfSeverity (a QUALITY line's
			// severity <select>, same idea) - always blank on render, exactly like mdmfNature/mdmfId above.
			.append(WizardPage.hidden("mdmfComment", "")).append(WizardPage.hidden("mdmfSeverity", ""))
			// quality-script-rule lot (2026-09-23): a SCRIPT_CONSTRAINT line's transfer-policy <select>, same idea as mdmfSeverity above
			.append(WizardPage.hidden("mdmfTransferPolicy", ""))
			.append(WizardPage.hidden("mdmfTrustRankEdit", ""))
			// sources-landing lot SOURCES (2026-09-24): the Review screen's own two fields, same "always blank
			// on render, filled by wizard.js right before submit" convention as mdmfTrustRankEdit above.
			.append(WizardPage.hidden("mdmfSourceCodeEdit", "")).append(WizardPage.hidden("mdmfRemoveConnectorId", ""))
			// UX lot U2 (2026-10-13): the ticks of the table lists of step 2, "+id,-id", filled by wizard.js right before TABLES_KEEP
			.append(WizardPage.hidden("mdmfTableTicks", ""))
			// carries the just-created project's id forward across a postback whose launch context selected no
			// record (perspective/toolbar launch of a brand new project) - see ProjectWizardUserService's class
			// Javadoc for why this cannot be relied on as plain Java instance state alone
			.append(WizardPage.hidden("mdmfProjectId", pModel.snapshot.project == null ? "" : pModel.snapshot.project.id))
			// step-reset lot (2026-09-23): mdmfResetStage names which step's "Clean this step..." button/panel the
			// action targets (STEP_RESET_PLAN opens it, STEP_RESET_CONFIRM acts on it); mdmfResetConfirm is the
			// confirmation panel's own project-code input, synced by wizard.js right before STEP_RESET_CONFIRM,
			// same "always blank on render" convention as mdmfComment/mdmfSeverity above.
			.append(WizardPage.hidden("mdmfResetStage", "")).append(WizardPage.hidden("mdmfResetConfirm", ""))
			// staging-proposal-run lot (2026-09-24): the "Propose the staging" checkboxes are real, unnamed
			// checkbox inputs (WizardPage.renderProposalRunForm) - wizard.js copies their checked state into
			// these two hidden fields right before STAGING_PROPOSAL_RUN submits, same "sync right before submit"
			// convention as mdmfSchemas/mdmfIncludeBelowThreshold above. Default "true" so a form submitted
			// without ever touching the checkboxes (the common case) still includes both passes.
			.append(WizardPage.hidden("mdmfProposalIncludeQuality", "true")).append(WizardPage.hidden("mdmfProposalIncludeCapability", "true"))
			// live bug fix (coordinator, 2026-09-24, bug 1): wizard.js's syncAlways() copies the "use the
			// language model" checkbox's checked state into a hidden field named "mdmfLandingRunUseLlm" right
			// before LANDING_RUN_START submits (same convention as the two lines above) - but no such hidden
			// field was ever rendered, so byId("mdmfLandingRunUseLlm") found nothing and the checked state never
			// reached the server: ProjectWizardUserService.paramBool always read a missing parameter as false,
			// so the one-click run NEVER called the language model, whatever the checkbox showed - the naming
			// rule alone then failed to find a key like BusinessPartner, and the table went WAITING with
			// "No column is certain." even though a manual "Study the key" (which calls KeyStudy.study(...,
			// true) directly, with no checkbox involved) had shown a 100%-confidence LLM proposal. Default
			// "true" so a form submitted before the checkbox is ever touched (the common case, checked by
			// default) still uses the language model.
			.append(WizardPage.hidden("mdmfLandingRunUseLlm", "true"))
			// landing-walkthrough lot (2026-10-16): the language-model radio of the application page, filled by wizard.js right before submit
			.append(WizardPage.hidden("mdmfWalkLlm", ""))
			// wizard-matching-results lot (2026-09-24): the "Matching results" block's own tab/pair selection
			// (matching.MatchResultsView.script(), pure client-side switching) is re-applied on load from these
			// two - mdmfMatchGolden/mdmfMatchSuspect carry a decide button's target pair right before
			// MATCH_PAIR_MERGE/MATCH_PAIR_REJECT submits, same "sync right before submit" convention as
			// mdmfTrustRankEdit above; always blank on render.
			.append(WizardPage.hidden("mdmfMatchTab", pModel.matchResultsSelectedTab)).append(WizardPage.hidden("mdmfMatchPairId", pModel.matchResultsSelectedPairId))
			.append(WizardPage.hidden("mdmfMatchGolden", "")).append(WizardPage.hidden("mdmfMatchSuspect", ""))
			// distribution-wizard lot (2026-09-24): mdmfDistSendScope is synced by wizard.js from the clicked
			// "Send now" button's own data-mdmf-dist-send-scope attribute right before submit, same "always blank
			// on render" convention as mdmfMatchGolden above; mdmfDistTargetId is not rendered here - the setup
			// panel itself carries it as a real hidden field, since it must survive a plain SAVE round trip
			// without wizard.js's help (unlike every other id above, which only ever needs to reach the server on
			// the click of a button that ALSO names it via data-mdmf-id).
			.append(WizardPage.hidden("mdmfDistSendScope", ""))
			// live bug fix (2026-09-25, DistribTest, task brief item 4): synced by wizard.js from an unconfigured
			// connection's own "Set up" button (data-mdmf-connector) right before submit, same "always blank on
			// render" convention as mdmfDistSendScope above - read only when DISTRIBUTION_TARGET_OPEN opens a NEW
			// target, to preselect that connector on the freshly-opened setup panel.
			.append(WizardPage.hidden("mdmfDistNewConnector", ""))
			// staging-still-to-decide lot (2026-09-27): "true" only when the clicked button carries
			// data-mdmf-carry-rules (a field's Accept on step 4's "decisions left" card) - synced by wizard.js,
			// always blank on render like every field above.
			.append(WizardPage.hidden("mdmfCarryRules", ""))
			// wave 11 (audit F-42): the step 4 filter box's text, copied by wizard.js from the input named by the button (data-mdmf-filter-input) right before STAGING_FILTER
			.append(WizardPage.hidden("mdmfStagingFilter", ""));
	}

	private static String hidden(final String pId, final String pValue)
	{
		return "<input type=\"hidden\" id=\"" + pId + "\" name=\"" + pId + "\" value=\"" + WizardPage.esc(pValue) + "\"/>";
	}

	/**
	 * wizard-progress lot (2026-09-22): the same indeterminate, bottom-centre toast as
	 * {@code ReviewPage.renderProgress} - same ids ({@code mdmfProgress}/{@code mdmfProgressLabel}), same
	 * markup shape, same {@code .mdmf-progress}/{@code .mdmf-progress-track}/{@code .mdmf-progress-bar}/
	 * {@code .mdmf-progress-label} classes from {@code review.css} (already linked by
	 * {@code ProjectWizardUserService.write}, alongside {@code factory.css} and this screen's own
	 * {@code wizard.css}) - never a second implementation. Rendered once at the top level of the page (like
	 * {@code ReviewPage} does for its own single-page screen), so its ids exist before any button is
	 * clicked, on every step panel. Same discipline as {@code ReviewPage.renderProgress}: always rendered,
	 * hidden by BOTH the inline {@code style="display:none"} AND the {@code .mdmf-progress} class, so a
	 * stale cached {@code review.css} still leaves the toast hidden until JS explicitly shows it.
	 */
	private static void renderProgress(final StringBuilder pOut)
	{
		pOut.append("<div id=\"mdmfProgress\" class=\"mdmf-progress\" style=\"display:none\"><div class=\"mdmf-progress-track\"><div class=\"mdmf-progress-bar\"></div></div>")
			.append("<span id=\"mdmfProgressLabel\" class=\"mdmf-progress-label\"></span></div>");
	}

	// ------------------------------------------------------------------------------------------------------- steps rail

	private static final String[] STEP_KEYS = { "", "wizard.step.project", "wizard.step.sources", "wizard.step.landing", "wizard.step.staging", "wizard.step.transfers",
		"wizard.step.matching", "wizard.step.distribution", "wizard.step.summary", "wizard.step.pack", "wizard.step.specification", "wizard.step.proposal",
		"wizard.step.generation" };

	/** Lot C1-B: the style of the project shown ({@code null} before it is saved): the step list, the position and the count all follow it. */
	private static String styleOf(final Model pModel)
	{
		return pModel.snapshot.project == null ? null : pModel.snapshot.project.style;
	}

	/**
	 * wizard-visual-pass lot (2026-09-24, board {@code docs/mockups/wizard-flow/*.dc.html}): the white header
	 * bar at the TOP of every step - left, an eyebrow ({@code "MDM Factory · Project <code>"}) and a step
	 * line ({@code "Step n of 7 · <step name>"}); right, the 7-step rail as numbered circles joined by
	 * short connector lines, horizontal instead of the previous vertical sidebar list. The rail's own markup
	 * ({@code <ol class="mdmf-wiz-steps"><li data-step="n">...}) is unchanged from before this lot -
	 * {@code wizard.js}'s {@code stepLi}/{@code panel} lookups and the {@code GOTO_STEP} action keep working
	 * without any script change; only {@link #renderSteps} and {@code wizard.css} were touched.
	 */
	private static void renderTopbar(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final String projectCode = pModel.snapshot.project == null || pModel.snapshot.project.code.isEmpty() ? null : pModel.snapshot.project.code;
		final String stepName = WizardMessages.get(pLocale, WizardPage.STEP_KEYS[pModel.currentStep]);
		pOut.append("<div class=\"mdmf-wiz-topbar\"><div class=\"mdmf-wiz-topbar-left\">");
		pOut.append("<div class=\"mdmf-wiz-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.wiz.eyebrow",
			projectCode != null ? projectCode : WizardMessages.get(pLocale, "wizard.wiz.noProject")))).append("</div>");
		// fix/wizard-live-wave1 (2026-09-24, live bug item 3): the number and the step name are their own spans
		// (mdmf-wiz-stepline-n/-name) INSIDE the otherwise-normal, fully localized sentence, so wizard.js's
		// purely client-side GOTO_STEP switch can update just those two small pieces of text when the rail
		// changes - never re-parsing or rebuilding the localized sentence itself, which would break for a
		// locale whose word order differs from English. The surrounding template text (from the bundle) is
		// trusted developer-authored content, so it is safe to embed directly; only the step NAME (also
		// developer-authored here, but escaped defensively like every other WizardMessages.get result on this
		// page) goes through WizardPage.esc before it is wrapped in its span.
		pOut.append("<div class=\"mdmf-wiz-stepline\">").append(WizardMessages.get(pLocale, "wizard.wiz.stepline",
			"<span class=\"mdmf-wiz-stepline-n\">" + WizardRules.positionOf(WizardPage.styleOf(pModel), pModel.currentStep) + "</span>",
			String.valueOf(WizardRules.steps(WizardPage.styleOf(pModel)).length),
			"<span class=\"mdmf-wiz-stepline-name\">" + WizardPage.esc(stepName) + "</span>"));
		// audit F-40: the user guide, opened in a new tab at the section of the step shown (wizard.js moves the anchor with the step)
		if (pModel.helpUrl != null && !pModel.helpUrl.isEmpty())
		{
			pOut.append(" <a class=\"mdmf-wiz-help\" href=\"").append(WizardPage.esc(pModel.helpUrl + "#step-" + pModel.currentStep)).append("\" data-mdmf-help-base=\"")
				.append(WizardPage.esc(pModel.helpUrl)).append("\" target=\"_blank\" rel=\"noopener\" title=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.help.title")))
				.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.help"))).append("</a>");
		}
		pOut.append("</div>");
		pOut.append("</div>");
		WizardPage.renderSteps(pOut, pModel, pLocale);
		pOut.append("</div>");
	}

	private static void renderSteps(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<ol class=\"mdmf-wiz-steps\">");
		int position = 0;
		for (final int step : WizardRules.steps(WizardPage.styleOf(pModel)))
		{
			position++;
			final WizardRules.StepState state = pModel.stepStates[step];
			final boolean current = step == pModel.currentStep;
			// completed-rail lot (2026-09-23): "done" is the step's own completion on the snapshot
			// (WizardRules.StepState.completed), whatever step is open - a step can be both current and done, and
			// wizard.js only ever moves the "current" class around, never "done"
			final String cls = (current ? "current " : "") + (state.completed ? "done " : "") + (!state.reachable ? "blocked " : "");
			// live bug fix (2026-09-25, DistribTest): steps 7/8 (Distribution/Summary) build their own model
			// only "when this step is the one being shown" (model()'s own comment on buildDistribution/
			// buildSummary) - every other step's rail entry stays the plain client-side GOTO_STEP switch (no
			// round trip needed, their data is always computed fresh in model()), but these two must actually
			// reach the server once navigated to, or a rail click shows whatever was last rendered (possibly
			// empty/stale, e.g. from before the master finished building) forever - the wizard is otherwise a
			// dead end, since the empty panel offers no button of its own to trigger a fresh render. STEP_VIEW
			// is a real, harmless action (ProjectWizardUserService#handle: updates nothing, just lets the
			// already-updated this.currentStep drive a fresh model()) - wizard.js's [data-mdmf-action] handler
			// treats any action other than the literal string "GOTO_STEP" as one to actually submit, and the
			// [data-mdmf-goto-step] listener (registered first) has already updated the hidden mdmfStep field
			// by the time it fires, same combo-button technique WizardPage#renderOpenPoints already uses.
			final String stepAction = step == WizardRules.STEP_DISTRIBUTION || step == WizardRules.STEP_SUMMARY ? "STEP_VIEW" : "GOTO_STEP";
			// UX lot U4 (2026-10-13, RAPPORT 2.2 point 5): a step not open yet explains itself twice - on hover (the title, now on the <li>: its disabled button takes
			// no pointer events, wizard.css) and as a one-line note under the rail when it is clicked (wizard.js railNote reads data-mdmf-blocked-note). A click on a
			// disabled button used to do nothing at all.
			final String railNote = WizardRules.railBlockedNote(state);
			final String railNoteText = railNote == null ? null : WizardMessages.get(pLocale, railNote);
			pOut.append("<li data-step=\"").append(step).append("\"").append(cls.isEmpty() ? "" : " class=\"" + cls.trim() + "\"")
				.append(railNoteText == null ? "" : " title=\"" + WizardPage.esc(railNoteText) + "\" data-mdmf-blocked-note=\"" + WizardPage.esc(railNoteText) + "\" tabindex=\"0\"").append(">")
				.append("<button type=\"button\" data-mdmf-action=\"").append(stepAction).append("\" data-mdmf-goto-step=\"").append(step).append("\"")
				.append(step == WizardRules.STEP_DISTRIBUTION ? " data-mdmf-deferred-label=\"distribution\"" : "")
				.append(state.reachable ? "" : " disabled=\"disabled\" title=\"" + WizardPage.esc(WizardMessages.get(pLocale, state.blockedKey)) + "\"").append("><b>")
				.append(position).append("</b><span>").append(WizardPage.esc(WizardMessages.get(pLocale, WizardPage.STEP_KEYS[step]))).append("</span></button></li>");
		}
		pOut.append("</ol><p class=\"mdmf-rail-note\" id=\"mdmfRailNote\" role=\"status\" hidden=\"hidden\"></p>");
	}

	private static void renderNav(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-wiz-nav\">");
		final int[] steps = WizardRules.steps(WizardPage.styleOf(pModel));
		final int position = WizardRules.positionOf(WizardPage.styleOf(pModel), pModel.currentStep);
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" id=\"mdmfWizBack\"").append(position <= 1 ? " disabled=\"disabled\"" : "").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.nav.back"))).append("</button>");
		pOut.append("<span class=\"mdmf-wiz-progress\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.nav.progress", String.valueOf(position),
			String.valueOf(steps.length)))).append("</span>");
		// step-gating lot (2026-09-24): the last step aside, "Next" is disabled whenever the step right after
		// the current one is not reachable yet (WizardRules.stepStates - the SAME rule the rail's own <li>
		// buttons already render disabled with, never a second computation) - with the identical reason as the
		// rail's tooltip, so the two controls never disagree about why. wizard.js's showStep already refuses the
		// switch either way (it reads the rail's own disabled button), this is the visible half of that gate.
		final boolean lastStep = position >= steps.length;
		final WizardRules.StepState nextState = lastStep ? null : pModel.stepStates[steps[position]];
		final boolean nextBlocked = nextState != null && !nextState.reachable;
		// UX lot U4 (2026-10-13): "Next" is a plain button - the one green button of a step is its own call to action (step 1 once the project exists:
		// "Continue to Sources"); and on step 1 it does not exist until the project does (before that "Create the project" is the only forward gesture)
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" id=\"mdmfWizNext\"").append(WizardRules.nextButtonVisible(pModel.projectExists()) ? "" : " hidden=\"hidden\"")
			.append(lastStep || nextBlocked ? " disabled=\"disabled\"" : "")
			.append(nextBlocked ? " title=\"" + WizardPage.esc(WizardMessages.get(pLocale, nextState.blockedKey)) + "\"" : "").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.nav.next"))).append("</button>");
		pOut.append("</div>");
	}

	// ------------------------------------------------------------------------------------------- shared page grammar

	/**
	 * ROUND 1 of the wizard-visual-pass lot (2026-09-24, coordinator split): the shared building blocks
	 * every step's body is being rewritten on top of, one round at a time - this round does steps 1 and 7
	 * in full and swaps every other step's head; later rounds (other agents) reuse these same helpers for
	 * steps 2-6's bodies. Documented in {@code docs/WIZARD-VISUAL.md}. Names and shapes:
	 * <ul>
	 * <li>{@link #renderPageHead}: the 30px/800 h1 + 15px muted lead (max-width 740), replacing every
	 * step's old plain {@code <h2>}/{@code <p class="mdmf-lead">} pair.</li>
	 * <li>{@link #render2ColStart}/{@link #render2ColSplit}/{@link #render2ColEnd}: a growing main column
	 * next to a fixed 320px side column, stacking under 1100px - call {@code Start}, write the main
	 * column's content, call {@code Split}, write the side column's content, call {@code End}.</li>
	 * <li>{@link #renderSideCardOpen}/{@link #renderSideCardClose}: one card of the side column (an
	 * eyebrow + free-form body written in between).</li>
	 * <li>{@link #renderGlossaryCard}: a complete "Words used in this step" side card wrapping the
	 * existing {@link #renderGlossaryTerms}.</li>
	 * <li>{@link #renderPrimaryActionBlockOpen}/{@link #renderPrimaryActionBlockClose}: the purple-tinted
	 * call-to-action card (a big button plus explanation text written in between).</li>
	 * <li>{@link #renderExplainCard}: one numbered explanation card (a round accent badge, a bold title,
	 * a line of text) - callers number their own cards ({@code 1, 2, 3...}).</li>
	 * <li>{@link #renderKpiRowOpen}/{@link #renderKpiTile}/{@link #renderKpiRowClose}: a row of equal
	 * number+label tiles.</li>
	 * <li>{@link #renderStepStateCircle}: a small state circle for {@code DONE}/{@code RUNNING}/
	 * {@code WAITING}/{@code NEEDS_PERSON} - distinct from the landing run's own
	 * {@code renderStateCircle}/{@code mdmf-landing-circle-*}, which stays as it is.</li>
	 * </ul>
	 */
	static void renderPageHead(final StringBuilder pOut, final String pTitle, final String pLead)
	{
		pOut.append("<div class=\"mdmf-ph\"><h1>").append(WizardPage.esc(pTitle)).append("</h1>");
		if (pLead != null && !pLead.isEmpty())
		{
			pOut.append("<p>").append(WizardPage.esc(pLead)).append("</p>");
		}
		pOut.append("</div>");
	}

	static void render2ColStart(final StringBuilder pOut)
	{
		pOut.append("<div class=\"mdmf-2col\"><div class=\"mdmf-2col-main\">");
	}

	static void render2ColSplit(final StringBuilder pOut)
	{
		pOut.append("</div><div class=\"mdmf-2col-side\">");
	}

	static void render2ColEnd(final StringBuilder pOut)
	{
		pOut.append("</div></div>");
	}

	static void renderSideCardOpen(final StringBuilder pOut, final Locale pLocale, final String pEyebrowKey)
	{
		pOut.append("<div class=\"mdmf-side-card\"><span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, pEyebrowKey))).append("</span>");
	}

	static void renderSideCardClose(final StringBuilder pOut)
	{
		pOut.append("</div>");
	}

	/** A complete "Words used in this step" side card - {@code pKeys} are passed straight through to {@link #renderGlossaryTerms}. */
	static void renderGlossaryCard(final StringBuilder pOut, final Locale pLocale, final String... pKeys)
	{
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.wiz.glossaryTitle");
		WizardPage.renderGlossaryTerms(pOut, pLocale, pKeys);
		WizardPage.renderSideCardClose(pOut);
	}

	static void renderPrimaryActionBlockOpen(final StringBuilder pOut)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-primary-block\">");
	}

	static void renderPrimaryActionBlockClose(final StringBuilder pOut)
	{
		pOut.append("</div>");
	}

	/** One numbered explanation card - {@code pNumber} is caller-assigned (1-based), so a caller can skip/reorder cards freely. */
	static void renderExplainCard(final StringBuilder pOut, final int pNumber, final String pTitle, final String pText)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-explain-card\"><span class=\"mdmf-explain-num\">").append(pNumber).append("</span>")
			.append("<span class=\"mdmf-explain-body\"><b>").append(WizardPage.esc(pTitle)).append("</b><span>").append(WizardPage.esc(pText)).append("</span></span></div>");
	}

	static void renderKpiRowOpen(final StringBuilder pOut)
	{
		pOut.append("<div class=\"mdmf-kpi-row\">");
	}

	static void renderKpiTile(final StringBuilder pOut, final String pValue, final String pLabel)
	{
		pOut.append("<div class=\"mdmf-kpi-tile\"><b>").append(WizardPage.esc(pValue)).append("</b><span>").append(WizardPage.esc(pLabel)).append("</span></div>");
	}

	static void renderKpiRowClose(final StringBuilder pOut)
	{
		pOut.append("</div>");
	}

	/** {@code pState} is one of {@code DONE}/{@code RUNNING}/{@code WAITING}/{@code NEEDS_PERSON}; anything else renders a plain empty ring. */
	static void renderStepStateCircle(final StringBuilder pOut, final String pState)
	{
		final String glyph = "DONE".equals(pState) ? "✓" : "RUNNING".equals(pState) ? "…" : "NEEDS_PERSON".equals(pState) ? "!" : "WAITING".equals(pState) ? "–" : "";
		final String cls = "DONE".equals(pState) ? "done" : "RUNNING".equals(pState) ? "running" : "NEEDS_PERSON".equals(pState) ? "needsPerson" : "waiting";
		pOut.append("<span class=\"mdmf-sc mdmf-sc-").append(cls).append("\">").append(glyph).append("</span>");
	}

	// --------------------------------------------------------------------------------------------------- step 1: project

	/**
	 * ROUND 1 of the wizard-visual-pass lot (2026-09-24): rebuilt to match board {@code Main.dc.html}
	 * exactly - the existing style picker ({@link #renderImplementationStyles}) is kept verbatim inside
	 * it, per the coordinator's instruction; only the page grammar around it changed (shared helpers,
	 * &sect; "shared page grammar" above). The section itself is no longer one single {@code mdmf-panel}
	 * card - the page head sits directly on the page background, and each block below (fields, style
	 * picker, target model, primary action) is its own card, same as the board.
	 */
	private static void renderStep1(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"1\"").append(WizardPage.hiddenAttr(pModel, 1)).append(">");
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step1.title"), WizardMessages.get(pLocale, "wizard.step1.lead"));
		final WizardRules.ProjectDraft draft = pModel.projectDraft;
		final boolean exists = pModel.projectExists();
		WizardPage.render2ColStart(pOut);

		// UX lot U4 (2026-10-13, RAPPORT 2.2): the label comes first and the code is proposed from it as the person types (wizard.js, WizardRules#projectCodeFromLabel
		// mirrored), still editable until the project exists. The primary button must be visible without scrolling: two real style cards (compact), the three
		// "coming later" ones folded, the two options nobody has to touch under "Advanced" with their defaults in one sentence below the button.
		pOut.append("<div class=\"mdmf-panel\"><div class=\"mdmf-grid-2\">");
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfLabel\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.label"))).append("</label>")
			.append("<input type=\"text\" id=\"mdmfLabel\" name=\"mdmfLabelValue\" value=\"").append(WizardPage.esc(draft.label)).append("\" autocomplete=\"off\"")
			.append(exists ? "" : " data-mdmf-code-source=\"mdmfCode\"").append("/>")
			.append(WizardPage.fieldError(pModel, pLocale, "label")).append("</div>");
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfCode\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.code"))).append("</label>");
		if (exists)
		{
			pOut.append("<input type=\"text\" id=\"mdmfCode\" value=\"").append(WizardPage.esc(draft.code)).append("\" readonly=\"readonly\"/>")
				.append(WizardPage.hidden("mdmfCodeValue", draft.code));
		}
		else
		{
			pOut.append("<input type=\"text\" id=\"mdmfCode\" name=\"mdmfCodeValue\" value=\"").append(WizardPage.esc(draft.code)).append("\" autocomplete=\"off\"/>");
		}
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, exists ? "wizard.field.code.hint" : "wizard.field.code.hint.proposed"))).append("</p>")
			.append(WizardPage.fieldError(pModel, pLocale, "code")).append("</div>");
		pOut.append("</div>");
		// UX lot C3: the description is optional - folded (opens when it holds text or a refused value), so the button stays above the fold at 1440x900
		pOut.append("<details class=\"mdmf-disclosure mdmf-step1-desc\"").append(!draft.description.isEmpty() || WizardPage.hasFieldError(pModel, "description") ? " open=\"open\"" : "")
			.append("><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.description.toggle"))).append("</summary>")
			.append("<div class=\"mdmf-field\"><label for=\"mdmfDescription\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.description")))
			.append("</label><textarea id=\"mdmfDescription\" name=\"mdmfDescriptionValue\" rows=\"2\">").append(WizardPage.esc(draft.description)).append("</textarea>")
			.append(WizardPage.fieldError(pModel, pLocale, "description")).append("</div></details>");
		pOut.append("</div>");

		// landing leftovers (2026-10-17): the style choice is back ABOVE the button (the last lot had put the button right under the form and a person could create
		// the project without ever seeing it): form, style (two compact cards side by side, the facts folded, the not-yet-built ones behind one line), then the button.
		// The button's block is sticky above the frame's bottom bar (wizard.css), so it stays visible when the cards push it under the fold.
		WizardPage.renderImplementationStyles(pOut, pModel, draft, pLocale);

		WizardPage.renderPrimaryActionBlockOpen(pOut);
		if (exists)
		{
			// the way to step 2 is this button (the bottom "Next" is only the same move, in plain style); saving edited fields is the secondary gesture
			final int nextStep = WizardRules.steps(WizardPage.styleOf(pModel))[1];
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"").append(nextStep).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.continue", WizardMessages.get(pLocale, WizardPage.STEP_KEYS[nextStep])))).append("</button>");
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"SAVE_PROJECT\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.save.update"))).append("</button>");
		}
		else
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"SAVE_PROJECT\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.save.create"))).append("</button>");
		}
		pOut.append("<span class=\"mdmf-primary-text\">");
		if (pModel.snapshot.project != null)
		{
			pOut.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.dataspaces", WizardPage.orDash(pModel.snapshot.project.landingDataspace),
				WizardPage.orDash(pModel.snapshot.project.stagingDataspace)))).append("</span>");
		}
		else
		{
			pOut.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.create.text"))).append("</span>");
		}
		// UX lot C3: what the chosen style makes the wizard do - beside the button instead of a block of its own above it (it cost a row of the page)
		// 2026-10-14: each selectable style's sentence rides on the span (data-mdmf-style-<code>), so wizard.js swaps it when another card is
		// clicked - until then the check mark and this sentence stayed on Consolidation and a Centralisation click looked ignored
		pOut.append("<span class=\"mdmf-style-consequence\" id=\"mdmfStyleConsequence\"");
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (style.selectable)
			{
				pOut.append(" data-mdmf-style-").append(style.code.toLowerCase(Locale.ROOT)).append("=\"")
					.append(WizardPage.esc(WizardMessages.get(pLocale, style.consequenceKey))).append("\"");
			}
		}
		pOut.append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, WizardRules.styleByCode(draft.style == null || draft.style.isEmpty() ? "CONSOLIDATION" : draft.style).consequenceKey)))
			.append("</span></span>");
		WizardPage.renderPrimaryActionBlockClose(pOut);

		WizardPage.renderStep1Advanced(pOut, pModel, draft, pLocale);

		WizardPage.render2ColSplit(pOut);
		WizardPage.renderStep1SideColumn(pOut, pModel, pLocale, draft, exists);
		WizardPage.render2ColEnd(pOut);
		pOut.append("</section>");
	}

	/**
	 * UX lot U4 (2026-10-13): "Staging mode" and "What may reach the language model" under one "Advanced options" fold. Outside it, one sentence says what applies
	 * if the person touches nothing (the mode, and the level of the language-model sharing in force - the effective one when the instance caps it); the fold
	 * opens by itself when something inside needs them ({@link WizardRules#step1AdvancedOpen}: a refused value, a choice above the instance cap). The controls
	 * keep their names and ids, so they post with the form whether the fold is open or not.
	 */
	private static void renderStep1Advanced(final StringBuilder pOut, final Model pModel, final WizardRules.ProjectDraft pDraft, final Locale pLocale)
	{
		final String mode = pDraft.mode == null || pDraft.mode.isEmpty() ? "GREENFIELD" : pDraft.mode;
		final String choice = pDraft.llmValueSharing == null || pDraft.llmValueSharing.trim().isEmpty() ? WizardRules.LLM_LEVEL_DEFAULT : pDraft.llmValueSharing.trim();
		final String effective = WizardRules.effectiveLlmLevel(choice, pModel.snapshot.llmValueSharingMax);
		final boolean capped = !WizardRules.llmLevelAllowed(choice, pModel.snapshot.llmValueSharingMax);
		final boolean fieldError = WizardPage.hasFieldError(pModel, "mode") || WizardPage.hasFieldError(pModel, "llmValueSharing") || WizardPage.hasFieldError(pModel, "llmSampleSize");
		pOut.append("<p class=\"mdmf-hint mdmf-step1-defaults\">").append(WizardMessages.get(pLocale, "wizard.step1.advanced.summary",
			WizardPage.esc(WizardMessages.get(pLocale, "TARGET_EXISTS".equals(mode) ? "wizard.step1.advanced.mode.TARGET_EXISTS" : "wizard.step1.advanced.mode.GREENFIELD")),
			"<b id=\"mdmfAdvLlm\">" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSharing." + effective)) + "</b>")).append("</p>");
		pOut.append("<details class=\"mdmf-disclosure mdmf-step1-advanced\"").append(WizardRules.step1AdvancedOpen(fieldError, capped) ? " open=\"open\"" : "").append("><summary>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step1.advanced.title"))).append("</summary>");
		pOut.append("<div class=\"mdmf-panel\">");
		WizardPage.renderStagingModeCards(pOut, pDraft, pLocale);
		pOut.append("</div><div class=\"mdmf-panel\">");
		WizardPage.renderLlmSharingCards(pOut, pModel, pDraft, pLocale);
		pOut.append("</div></details>");
	}

	/**
	 * The board's "What the project will create" card - the three dataspace names. Once the project is
	 * saved, {@link WizardSnapshot.ProjectInfo}'s own fields are authoritative and used as-is; before that
	 * (still drafting), the names are derived from the typed code with the same {@code <code>_Landing}/
	 * {@code _Staging}/{@code _Master} convention every already-saved project's own fields already follow
	 * (see e.g. {@code renderStep5}'s {@code masterDataspace} hint) - never a separate guess.
	 */
	private static void renderStep1SideColumn(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardRules.ProjectDraft pDraft, final boolean pExists)
	{
		final String code = pExists ? pModel.snapshot.project.code : pDraft.code;
		final String landing = pExists ? pModel.snapshot.project.landingDataspace : (code.isEmpty() ? "" : code + "_Landing");
		final String staging = pExists ? pModel.snapshot.project.stagingDataspace : (code.isEmpty() ? "" : code + "_Staging");
		final String master = pExists && !pModel.snapshot.project.masterDataspace.isEmpty() ? pModel.snapshot.project.masterDataspace : (code.isEmpty() ? "" : code + "_Master");
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.step1.willCreate.title");
		WizardPage.renderStep1DataspaceRow(pOut, pLocale, landing, "wizard.step1.willCreate.landing");
		WizardPage.renderStep1DataspaceRow(pOut, pLocale, staging, "wizard.step1.willCreate.staging");
		WizardPage.renderStep1DataspaceRow(pOut, pLocale, master, "wizard.step1.willCreate.master");
		WizardPage.renderSideCardClose(pOut);
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.step1.glossary.project", "wizard.step1.glossary.goldenRecord", "wizard.step1.glossary.consolidation");
	}

	private static void renderStep1DataspaceRow(final StringBuilder pOut, final Locale pLocale, final String pName, final String pTagKey)
	{
		pOut.append("<div style=\"display:flex;align-items:center;gap:8px\"><span class=\"mdmf-mono\">").append(WizardPage.esc(WizardPage.orDash(pName)))
			.append("</span><span style=\"margin-left:auto;font-size:11px;color:var(--muted)\">").append(WizardPage.esc(WizardMessages.get(pLocale, pTagKey)))
			.append("</span></div>");
	}

	/**
	 * The style choice, redesigned 2026-09-21 around one question ("where is the master data created and
	 * maintained") with an axis caption and the four cards ({@link WizardRules#IMPLEMENTATION_STYLES}) laid
	 * out in that axis order, each with its own diagram ({@link WizardStyleDiagram}) and three identically
	 * labelled facts so the eye compares across cards. Only {@code CONSOLIDATION} is selectable; its radio
	 * carries {@code name="mdmfStyleRadio"} so it posts (the other three share the same {@code name}, for
	 * correct native radiogroup semantics, but never post since they are {@code disabled}). The sentence
	 * below the cards is computed from whichever style is actually selected in the draft, not hard-coded to
	 * Consolidation, even though only Consolidation can be selected today.
	 */
	private static void renderImplementationStyles(final StringBuilder pOut, final Model pModel, final WizardRules.ProjectDraft pDraft, final Locale pLocale)
	{
		final String currentStyle = pDraft.style == null || pDraft.style.isEmpty() ? "CONSOLIDATION" : pDraft.style;
		pOut.append("<div class=\"mdmf-field\">");
		pOut.append("<label id=\"mdmfStyleLabel\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.style"))).append("</label>");
		// UX lot U4 (2026-10-13): the style a person can choose is a card, beside the one marked "coming soon" (Centralisation, 2026-11-10: built,
		// not ready, disabled); the others (Registry, Coexistence: not built yet) take no room - one folded line
		pOut.append("<div class=\"mdmf-style-cards mdmf-style-cards-main\" role=\"radiogroup\" aria-labelledby=\"mdmfStyleLabel\">");
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (style.selectable || style.comingSoon)
			{
				WizardPage.renderStyleCard(pOut, pLocale, style, style.code.equals(currentStyle), pModel.projectExists());
			}
		}
		pOut.append("</div>");
		pOut.append("<details class=\"mdmf-disclosure mdmf-style-later\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.later"))).append("</summary><div class=\"mdmf-style-cards\">");
		for (final WizardRules.ImplementationStyle style : WizardRules.IMPLEMENTATION_STYLES)
		{
			if (!style.selectable && !style.comingSoon)
			{
				WizardPage.renderStyleCard(pOut, pLocale, style, style.code.equals(currentStyle), pModel.projectExists());
			}
		}
		pOut.append("</div></details>");
		pOut.append(WizardPage.fieldError(pModel, pLocale, "style"));
		pOut.append("</div>");
	}

	/** Builds the diagram's plain-string labels once, all resolved through {@link WizardMessages} here - {@link WizardStyleDiagram} itself never looks up a message key. */
	private static WizardStyleDiagram.Labels styleDiagramLabels(final Locale pLocale, final String pStyleLabel)
	{
		return new WizardStyleDiagram.Labels(WizardMessages.get(pLocale, "wizard.style.diagram.title", pStyleLabel),
			WizardMessages.get(pLocale, "wizard.style.column.sources"), WizardMessages.get(pLocale, "wizard.style.column.hub"),
			WizardMessages.get(pLocale, "wizard.style.column.consumers"), WizardMessages.get(pLocale, "wizard.style.tag.created"),
			WizardMessages.get(pLocale, "wizard.style.tag.copy"), WizardMessages.get(pLocale, "wizard.style.tag.keysOnly"),
			WizardMessages.get(pLocale, "wizard.style.tag.subscribers"), WizardMessages.get(pLocale, "wizard.style.tag.read"),
			WizardMessages.get(pLocale, "wizard.style.arrow.keys"), WizardMessages.get(pLocale, "wizard.style.diagram.app1"),
			WizardMessages.get(pLocale, "wizard.style.diagram.app2"));
	}

	static void renderStyleCard(final StringBuilder pOut, final Locale pLocale, final WizardRules.ImplementationStyle pStyle, final boolean pSelected,
		final boolean pProjectSaved)
	{
		// lot C1-B: the style is written when the project is created; once it exists only its own card stays, the others are shown disabled
		final boolean disabled = !pStyle.selectable || pProjectSaved && !pSelected;
		pOut.append("<label class=\"mdmf-choice-card mdmf-style-card").append(pSelected ? " selected" : "").append(disabled ? " mdmf-choice-card-disabled" : "")
			.append("\">");
		pOut.append("<input type=\"radio\" name=\"mdmfStyleRadio\" value=\"").append(pStyle.code).append("\"").append(pSelected ? " checked=\"checked\"" : "")
			.append(disabled ? " disabled=\"disabled\"" : "").append("/>");
		if (!pStyle.selectable && !(pProjectSaved && pSelected))
		{
			// 1.4.2 (F3-114): not on the card of the style a SAVED project already has - an existing Centralisation project is not "coming soon", it is the project's own style
			// "Coming soon" for a style that is built but held back (Centralisation, 2026-11-10), "Coming later" for one not built at all
			pOut.append("<span class=\"mdmf-choice-card-chip mdmf-chip mdmf-chip-target\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, pStyle.comingSoon ? "wizard.comingSoon" : "wizard.notYet"))).append("</span>");
		}
		final String label = WizardMessages.get(pLocale, pStyle.labelKey);
		pOut.append("<span class=\"mdmf-choice-card-title\">").append(WizardPage.esc(label));
		if (pStyle == WizardRules.defaultStyle())
		{
			// "make Consolidation the default explicit... mark it visually as the usual case" (product owner,
			// 2026-09-21) - next to the title, never instead of the selection check
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-accent mdmf-style-card-common\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.mostCommon")))
				.append("</span>");
		}
		pOut.append("</span>");
		// the headline: the card's first fact, in plain bold words - "that is the whole point of the choice"
		pOut.append("<p class=\"mdmf-style-card-headline\">").append(WizardPage.esc(WizardMessages.get(pLocale, pStyle.headlineKey))).append("</p>");
		// compact card (UX lot U4): diagram, title, headline; the facts and "when to choose it" are one click away (a <details> inside the label does not pick the radio)
		// UX lot C3: the drawing and the axis line it belongs to ("In the source applications -> In the MDM hub") live under Details too: they were most of the page's height
		pOut.append("<details class=\"mdmf-style-more\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.more"))).append("</summary>");
		pOut.append("<div class=\"mdmf-style-axis\"><span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.axis.sources")))
			.append("</span><span class=\"mdmf-style-axis-line\" aria-hidden=\"true\"></span><span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.axis.hub")))
			.append("</span></div>");
		pOut.append(WizardStyleDiagram.svg(pStyle, WizardPage.styleDiagramLabels(pLocale, label)));
		pOut.append("<ul class=\"mdmf-style-card-facts\">");
		pOut.append("<li><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.row.golden"))).append("</b> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.golden." + pStyle.goldenRecord.name()))).append("</li>");
		pOut.append("<li><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.row.flows"))).append("</b> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.flow." + pStyle.flow.name()))).append("</li>");
		pOut.append("</ul>");
		pOut.append("<p class=\"mdmf-style-card-when\"><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.style.when.label"))).append("</b> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pStyle.whenKey))).append("</p></details>");
		pOut.append("</label>");
	}

	/** Same card pattern as {@link #renderStyleCard}, without a diagram: only Consolidation-mode-equivalent GREENFIELD is selectable in this lot. */
	private static void renderStagingModeCards(final StringBuilder pOut, final WizardRules.ProjectDraft pDraft, final Locale pLocale)
	{
		final String mode = pDraft.mode == null || pDraft.mode.isEmpty() ? "GREENFIELD" : pDraft.mode;
		pOut.append("<div class=\"mdmf-field\">");
		pOut.append("<label id=\"mdmfModeLabel\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.mode"))).append("</label>");
		pOut.append("<div class=\"mdmf-mode-cards\" role=\"radiogroup\" aria-labelledby=\"mdmfModeLabel\">");
		WizardPage.renderModeCard(pOut, pLocale, "GREENFIELD", "wizard.field.mode.greenfield", true, mode);
		// audit F-40: the target-exists mode is not built - no card for it (it used to show disabled, "coming"); WizardRules still refuses a
		// forged post. A project that already carries the mode (only by a forged or old write) keeps showing its own card, disabled.
		if ("TARGET_EXISTS".equals(mode))
		{
			WizardPage.renderModeCard(pOut, pLocale, "TARGET_EXISTS", "wizard.field.mode.targetExists", false, mode);
		}
		pOut.append("</div></div>");
	}

	private static void renderModeCard(final StringBuilder pOut, final Locale pLocale, final String pCode, final String pTextKey, final boolean pSelectable,
		final String pCurrentMode)
	{
		final boolean selected = pCode.equals(pCurrentMode);
		final boolean disabled = !pSelectable;
		pOut.append("<label class=\"mdmf-choice-card mdmf-mode-card").append(selected ? " selected" : "").append(disabled ? " mdmf-choice-card-disabled" : "")
			.append("\">");
		pOut.append("<input type=\"radio\" name=\"mdmfModeRadio\" value=\"").append(pCode).append("\"").append(selected ? " checked=\"checked\"" : "")
			.append(disabled ? " disabled=\"disabled\"" : "").append("/>");
		if (disabled)
		{
			pOut.append("<span class=\"mdmf-choice-card-chip mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.notYet")))
				.append("</span>");
		}
		pOut.append("<span class=\"mdmf-choice-card-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, pTextKey))).append("</span>");
		pOut.append("</label>");
	}

	/**
	 * wizard-llm-value-sharing lot (2026-09-28): "What may reach the language model" - the project's own
	 * {@code Project.llmValueSharing} / {@code llmSampleSize}, until now editable only on the Project record
	 * form. Same {@code .mdmf-choice-card mdmf-mode-card} radio-card pattern as the staging mode above
	 * ({@link #renderStagingModeCards}), three cards in {@link WizardRules#LLM_LEVELS} order; a card above the
	 * instance cap ({@link WizardSnapshot#llmValueSharingMax}) is disabled with a chip, exactly like the
	 * not-yet mode card, and the checked card is always the EFFECTIVE level ({@link
	 * WizardRules#effectiveLlmLevel}) - a disabled radio never posts, so what is checked is what will be
	 * saved. When the stored/posted choice is above the cap, a warning notice says so in so many words
	 * (never a silently lowered choice). Under the cards: the sample-size field (rendered {@code hidden}
	 * unless SAMPLE_VALUES is the effective level - wizard.js flips it live) and the plain-language
	 * consequence line for the effective level; the line carries every level's text as {@code
	 * data-mdmf-llm-<level>} attributes so wizard.js can swap it on a click without a round trip
	 * ({@code {0}} left in the SAMPLE_VALUES template is the live sample size).
	 */
	private static void renderLlmSharingCards(final StringBuilder pOut, final Model pModel, final WizardRules.ProjectDraft pDraft, final Locale pLocale)
	{
		final String max = pModel.snapshot.llmValueSharingMax;
		final String choice = pDraft.llmValueSharing == null || pDraft.llmValueSharing.trim().isEmpty() ? WizardRules.LLM_LEVEL_DEFAULT : pDraft.llmValueSharing.trim();
		final String effective = WizardRules.effectiveLlmLevel(choice, max);
		final boolean capped = !WizardRules.llmLevelAllowed(choice, max);
		final int sampleSize = WizardRules.llmSampleSize(pDraft.llmSampleSize);
		final String sampleText = pDraft.llmSampleSize == null || pDraft.llmSampleSize.trim().isEmpty() ? String.valueOf(WizardRules.LLM_SAMPLE_SIZE_DEFAULT)
			: pDraft.llmSampleSize.trim();
		final String connector = pModel.snapshot.llmConnectorLabel == null || pModel.snapshot.llmConnectorLabel.isEmpty()
			? WizardMessages.get(pLocale, "wizard.field.llmSharing.noConnector") : pModel.snapshot.llmConnectorLabel;

		pOut.append("<div class=\"mdmf-field mdmf-llm-sharing\">");
		pOut.append("<label id=\"mdmfLlmSharingLabel\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSharing"))).append("</label>");
		if (capped)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn mdmf-llm-capped\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSharing.capped", WizardMessages.get(pLocale, "wizard.field.llmSharing." + max),
					WizardMessages.get(pLocale, "wizard.field.llmSharing." + choice))))
				.append("</div>");
		}
		pOut.append("<div class=\"mdmf-mode-cards mdmf-llm-cards\" role=\"radiogroup\" aria-labelledby=\"mdmfLlmSharingLabel\">");
		for (final String level : WizardRules.LLM_LEVELS)
		{
			final boolean selected = level.equals(effective);
			final boolean disabled = !WizardRules.llmLevelAllowed(level, max);
			pOut.append("<label class=\"mdmf-choice-card mdmf-mode-card mdmf-llm-card").append(selected ? " selected" : "").append(disabled ? " mdmf-choice-card-disabled" : "")
				.append("\">");
			pOut.append("<input type=\"radio\" name=\"mdmfLlmSharingRadio\" value=\"").append(level).append("\"").append(selected ? " checked=\"checked\"" : "")
				.append(disabled ? " disabled=\"disabled\"" : "").append("/>");
			if (disabled)
			{
				pOut.append("<span class=\"mdmf-choice-card-chip mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSharing.cappedChip")))
					.append("</span>");
			}
			pOut.append("<span class=\"mdmf-choice-card-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSharing." + level))).append("</span>");
			pOut.append("</label>");
		}
		pOut.append("</div>");
		pOut.append(WizardPage.fieldError(pModel, pLocale, "llmValueSharing"));

		pOut.append("<div class=\"mdmf-field mdmf-llm-sample\" id=\"mdmfLlmSampleSizeField\"").append("SAMPLE_VALUES".equals(effective) ? "" : " hidden=\"hidden\"").append(">")
			.append("<label for=\"mdmfLlmSampleSize\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSampleSize"))).append("</label>")
			.append("<input type=\"number\" id=\"mdmfLlmSampleSize\" name=\"mdmfLlmSampleSize\" min=\"1\" max=\"").append(WizardRules.LLM_SAMPLE_SIZE_MAX)
			.append("\" step=\"1\" value=\"").append(WizardPage.esc(sampleText)).append("\" autocomplete=\"off\"/>")
			.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.llmSampleSize.hint"))).append("</p>")
			.append(WizardPage.fieldError(pModel, pLocale, "llmSampleSize")).append("</div>");

		final String shownSize = String.valueOf(sampleSize < 0 ? WizardRules.LLM_SAMPLE_SIZE_DEFAULT : sampleSize);
		pOut.append("<p class=\"mdmf-hint mdmf-llm-consequence\" id=\"mdmfLlmConsequence\"");
		for (final String level : WizardRules.LLM_LEVELS)
		{
			pOut.append(" data-mdmf-llm-").append(level.toLowerCase(Locale.ROOT)).append("=\"")
				.append(WizardPage.esc(WizardPage.llmConsequenceText(pLocale, level, "{0}", connector))).append("\"");
		}
		pOut.append(">").append(WizardPage.esc(WizardPage.llmConsequenceText(pLocale, effective, shownSize, connector))).append("</p>");
		pOut.append("</div>");
	}

	/** The one-sentence consequence of {@code pLevel} - only SAMPLE_VALUES names the sample size ({@code pSize}) and the connector ({@code pConnector}). */
	private static String llmConsequenceText(final Locale pLocale, final String pLevel, final String pSize, final String pConnector)
	{
		return "SAMPLE_VALUES".equals(pLevel) ? WizardMessages.get(pLocale, "wizard.field.llmSharing.consequence.SAMPLE_VALUES", pSize, pConnector)
			: WizardMessages.get(pLocale, "wizard.field.llmSharing.consequence." + pLevel);
	}

	// --------------------------------------------------------------------------------------------------- step 2: sources

	/**
	 * Visual pass (2026-09-24, coordinator: "matches docs/mockups/sources-landing/*.dc.html in layout and
	 * hierarchy") - main column ({@link #renderStep2Main}) + right sidebar ({@link #renderStep2Sidebar}), same
	 * structure as {@code Main.dc.html}/{@code SourcesAdding.dc.html}/{@code SourcesReview.dc.html}: a big
	 * dashed drop zone as the first way in (start state only - {@link #renderSampleFilesPanel(StringBuilder,
	 * Model, Locale, boolean)}'s {@code pBig=true}), application progress cards with the 4-circle pipeline,
	 * review cards with an editable code/table ticks/key chips, and the primary "Continue to the landing"
	 * block. {@code startState} (no application ready or queued) decides both which column-1 content renders
	 * and which sidebar panels do - see {@link #renderStep2Sidebar}'s own Javadoc for the exact mapping.
	 */
	private static void renderStep2(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-panel mdmf-wiz-panel\" data-step=\"2\"").append(WizardPage.hiddenAttr(pModel, 2)).append(">");
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step2.title"), WizardMessages.get(pLocale, "wizard.step2.lead"));
		if (!pModel.snapshot.catalogAvailable)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.noCatalog"))).append("</div>");
		}
		WizardPage.renderCatalogRestricted(pOut, pModel, pLocale);
		final boolean startState = pModel.snapshot.sources.isEmpty() && pModel.sourcesRunCards.isEmpty();
		pOut.append("<div class=\"mdmf-src2-layout\"><div class=\"mdmf-src2-main\">");
		WizardPage.renderStep2Main(pOut, pModel, pLocale, startState);
		pOut.append("</div><div class=\"mdmf-src2-sidebar\">");
		WizardPage.renderStep2Sidebar(pOut, pModel, pLocale, startState);
		pOut.append("</div></div>");
		pOut.append("</section>");
	}

	/**
	 * Audit F-02 (catalog reads): the catalog content of this page is read as the person, and their catalog right hides some
	 * of it - said once at the top of the step, so an empty list or a source shown by its id never reads as "nothing found".
	 */
	static void renderCatalogRestricted(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.snapshot.catalogRestricted)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\" data-mdmf-catalog-restricted=\"true\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.catalog.restricted"))).append("</div>");
		}
	}

	private static void renderStep2Main(final StringBuilder pOut, final Model pModel, final Locale pLocale, final boolean pStartState)
	{
		// ready applications (Review) and in-progress ones (Adding) both render regardless of catalogAvailable
		// (a source added before the catalog module was ever configured still has to be listed/manageable) -
		// only the ADD controls below are gated on it, same rule the pre-visual-pass code already followed.
		//
		// Visual pass fix: a not-yet-ready queued application must show EITHER as its own full pipeline card
		// (SourcesAdding.dc.html - no ready application exists yet, so every card is still "in progress") OR
		// as a compact one-line "stuck" row (SourcesReview.dc.html - at least one application is already
		// ready, so the review cards are the main content and a still-blocked one is a footnote), never BOTH -
		// an earlier version rendered every queued card in full AND repeated the not-ready ones as stuck rows
		// right below, which duplicated "SalesforceSample - still scanning" on screen for no reason (caught in
		// this lot's own visual check, docs/SOURCES-LANDING-SOURCES.md).
		if (pModel.snapshot.sources.isEmpty())
		{
			WizardPage.renderApplicationProgressCards(pOut, pModel, pLocale);
		}
		else
		{
			WizardPage.renderReviewCards(pOut, pModel, pLocale);
			WizardPage.renderStuckApplications(pOut, pModel, pLocale);
		}
		// deduced-scan lot (2026-09-24, boards 2d/2e): a deduced-scan card renders like any other in-progress
		// application card (its own pipeline), and a still-TO_CONFIRM table renders its own confirm card right
		// below the review cards, regardless of pModel.snapshot.sources being empty or not - a project can be
		// mid deduced-scan even before ANY application is "ready" in the official-scan sense.
		WizardPage.renderDeducedScanCards(pOut, pModel, pLocale);
		WizardPage.renderDeducedScanConfirm(pOut, pModel, pLocale);
		WizardPage.renderContinueToLanding(pOut, pModel, pLocale);

		if (!pModel.snapshot.catalogAvailable)
		{
			return;
		}
		// UX lot U2 (2026-10-13): ONE way in - a single "Add an application" list merging the connections and the catalog's sources (measured live: two
		// panels listed the same systems, the usable one looked like the unusable ones). The sample files, the catalog form and the step-by-step live
		// system panel are the secondary way, folded below.
		if (pStartState)
		{
			WizardPage.renderAddApplicationPanel(pOut, pModel, pLocale);
			WizardPage.renderMoreWaysToAdd(pOut, pModel, pLocale);
		}
		else
		{
			// SourcesAdding.dc.html's own "Drop more files here... or pick from the catalog" slim row - the SAME controls, collapsed behind one
			// disclosure instead of gone entirely (task brief: "you can come back and add an application at any time").
			// fix lot (2026-09-26, defect 12): kept open across the postback whenever the person opened it or a panel inside it is mid-flow
			// (WizardRules.addMoreApplicationsOpen) - a plain <details> with no explicit "open" collapses on every re-render otherwise, since this
			// whole page is rebuilt from scratch on every round trip.
			final boolean addMoreOpen = pModel.addAppOpen || WizardRules.addMoreApplicationsOpen(pModel.onboardOpen, pModel.onboardConnectorId, pModel.onboardDiagnosis != null,
				pModel.pickedCatalogSourceId, !pModel.sampleFilesReport.isEmpty());
			pOut.append("<details class=\"mdmf-disclosure\"").append(addMoreOpen ? " open=\"open\"" : "").append("><summary>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.addMore"))).append("</summary>");
			WizardPage.renderAddApplicationPanel(pOut, pModel, pLocale);
			WizardPage.renderMoreWaysToAdd(pOut, pModel, pLocale);
			pOut.append("</details>");
		}
	}

	/**
	 * UX lot U2 (2026-10-13): the secondary ways to add an application, folded: sample files, a catalog source picked by hand (with its schemas, for a
	 * catalog source that hosts several applications), and the step-by-step "Connect a live system" panel (its checklist, and the tables-from-data
	 * path for a connection with no catalog scan). Open whenever one of them is mid-flow.
	 */
	private static void renderMoreWaysToAdd(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final boolean open = WizardRules.addMoreApplicationsOpen(pModel.onboardOpen, pModel.onboardConnectorId, pModel.onboardDiagnosis != null, pModel.pickedCatalogSourceId,
			!pModel.sampleFilesReport.isEmpty());
		pOut.append("<details class=\"mdmf-disclosure\" id=\"mdmf-more-ways\"").append(open ? " open=\"open\"" : "").append("><summary>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.moreWays"))).append("</summary>");
		WizardPage.renderSampleFilesPanel(pOut, pModel, pLocale, false);
		WizardPage.renderAddSourceForm(pOut, pModel, pLocale);
		WizardPage.renderSourceOnboardingPanel(pOut, pModel, pLocale);
		pOut.append("</details>");
	}

	/**
	 * UX lot U2 (2026-10-13): the single "Add an application" list. Each row is a connection (or a catalog source with no connection) with a chip that
	 * says what picking it does - {@link AddApplicationOptions}; a row that cannot be added is greyed and says why. Picking a row posts {@code
	 * SOURCE_ADD_APP}: the system is registered in the catalog and scanned when it is not yet, its tables are found and shown here on a card, unticked.
	 * The connections are read live (Connectors), so, like the step-by-step panel, the list is only loaded when the person opens it (an input-styled
	 * button stands in until then).
	 */
	private static void renderAddApplicationPanel(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted mdmf-src2-waycard\" id=\"mdmf-add-application\">");
		pOut.append("<div class=\"mdmf-src2-way-head\"><span class=\"mdmf-src2-way-icon mdmf-src2-way-icon-alt\">").append(WizardPage.ICON_PLUG).append("</span><span>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.title"))).append("</span></div><p class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.lead"))).append("</p>");
		if (!pModel.addAppOpen)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-field-search-btn\" data-mdmf-action=\"SOURCE_APP_OPEN\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.search"))).append("</button>");
			pOut.append("<p class=\"mdmf-hint\"><a class=\"mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.CONNECTORS_TABLE_URL + "?service=@creation"))
				.append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.newConnection"))).append("</a></p></div>");
			return;
		}
		if (pModel.onboardConnectionsError != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(pModel.onboardConnectionsError)).append("</div>");
		}
		final Set<String> projectConnections = new HashSet<String>();
		final Set<String> projectCatalogSources = new HashSet<String>();
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			if (!source.connector.isEmpty())
			{
				projectConnections.add(source.connector);
			}
			if (!source.catalogSourceId.isEmpty())
			{
				projectCatalogSources.add(source.catalogSourceId);
			}
		}
		final List<AddApplicationOptions.Row> rows = AddApplicationOptions.build(pModel.onboardConnections, pModel.snapshot.catalogSources, projectConnections, projectCatalogSources);
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfAddAppSearch\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.label"))).append("</label>");
		pOut.append("<div class=\"mdmf-combo\" id=\"mdmfAddAppCombo\" data-mdmf-nomatch=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.noMatch"))).append("\">");
		pOut.append("<span class=\"mdmf-combo-icon\" aria-hidden=\"true\">&#128269;</span>");
		pOut.append("<input type=\"text\" id=\"mdmfAddAppSearch\" role=\"combobox\" aria-autocomplete=\"list\" aria-expanded=\"false\" aria-controls=\"mdmfAddAppList\""
			+ " autocomplete=\"off\" spellcheck=\"false\" placeholder=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.search")))
			.append("\" value=\"\" data-mdmf-picked-label=\"\"/>");
		pOut.append("<ul class=\"mdmf-combo-list\" id=\"mdmfAddAppList\" role=\"listbox\" hidden=\"hidden\">");
		for (final AddApplicationOptions.Row row : rows)
		{
			final String chip = row.showsTables()
				? WizardMessages.get(pLocale, row.chipKey() + ".tables", WizardPage.countText(pLocale, row.tables, "wizard.unit.table"))
				: row.state == AddApplicationOptions.State.CANNOT_READ
					? WizardMessages.get(pLocale, row.chipKey(), WizardMessages.get(pLocale, "wizard.src2.app.reason." + row.reason))
					: WizardMessages.get(pLocale, row.chipKey());
			final boolean realChip = row.state == AddApplicationOptions.State.SCANNED || row.state == AddApplicationOptions.State.FOUND_FROM_DATA;
			final boolean targetChip = row.state == AddApplicationOptions.State.CONNECTION_ONLY || row.state == AddApplicationOptions.State.DEDUCED_READ;
			final String chipClass = realChip ? "mdmf-chip-real" : targetChip ? "mdmf-chip-target" : "mdmf-chip-muted";
			final String search = (row.label + " " + row.type + " " + row.id + " " + chip).toLowerCase(Locale.ROOT);
			pOut.append("<li role=\"option\" class=\"mdmf-combo-option").append(row.pickable() ? "" : " mdmf-combo-option-off").append("\" data-mdmf-id=\"").append(WizardPage.esc(row.id))
				.append("\" data-mdmf-label=\"").append(WizardPage.esc(row.label)).append("\" data-search=\"").append(WizardPage.esc(search)).append("\" aria-selected=\"false\"")
				.append(row.pickable() ? "" : " aria-disabled=\"true\"").append(">");
			pOut.append("<span class=\"mdmf-combo-label\">").append(WizardPage.esc(row.label)).append("</span>");
			pOut.append("<span class=\"mdmf-combo-meta\">");
			if (!row.type.isEmpty())
			{
				pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(row.type)).append("</span>");
			}
			pOut.append("<span class=\"mdmf-chip ").append(chipClass).append("\">").append(WizardPage.esc(chip)).append("</span></span></li>");
		}
		pOut.append("<li class=\"mdmf-combo-empty\" hidden=\"hidden\"></li>");
		pOut.append("</ul></div>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.hint"))).append("</p>");
		if (!pModel.designer)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.gate.adminOnly"))).append("</p>");
		}
		// the one real action path, like every combobox of this step: clicked by the script when a row is picked
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" id=\"mdmfAddAppPick\" data-mdmf-action=\"SOURCE_ADD_APP\" hidden=\"hidden\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.app.pick"))).append("</button>");
		pOut.append("</div>");
		pOut.append("<p class=\"mdmf-hint\"><a class=\"mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.CONNECTORS_TABLE_URL + "?service=@creation")).append("\" target=\"_top\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.newConnection"))).append("</a></p></div>");
	}

	/**
	 * Start: "Your applications" empty placeholder + glossary (Main.dc.html). Otherwise: "What each stage
	 * does" + the "orange only for a person" note whenever at least one application is still being prepared
	 * (SourcesAdding.dc.html), and/or the trust order + glossary whenever at least one is ready
	 * (SourcesReview.dc.html) - both can show together (an in-progress AND a ready application at once is a
	 * normal state this wizard reaches, unlike either single mockup screenshot).
	 */
	private static void renderStep2Sidebar(final StringBuilder pOut, final Model pModel, final Locale pLocale, final boolean pStartState)
	{
		if (pStartState)
		{
			pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><div class=\"mdmf-eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.yourApps"))).append("</div>");
			pOut.append("<div class=\"mdmf-src2-sidebar-empty\"><div class=\"mdmf-src2-sidebar-empty-boxes\"><span class=\"mdmf-src2-sidebar-empty-box\"></span>"
				+ "<span class=\"mdmf-src2-sidebar-empty-box\"></span><span class=\"mdmf-src2-sidebar-empty-box\"></span></div><p class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.empty"))).append("</p></div></div>");
			pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><div class=\"mdmf-eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.glossary"))).append("</div>");
			WizardPage.renderGlossaryTerms(pOut, pLocale, "wizard.step2.glossary.application", "wizard.step2.glossary.connection", "wizard.step2.glossary.catalog",
				"wizard.step2.glossary.scan", "wizard.step2.glossary.table", "wizard.step2.glossary.key", "wizard.step2.glossary.trustRank", "wizard.step2.glossary.sampleFile");
			pOut.append("</div>");
			return;
		}
		// visual pass round 3 (coordinator): "What each stage does"/"Only the stuck ones need you" belong to
		// the ADDING state only (SourcesAdding.dc.html) - once any application is ready (the Review cards are
		// showing), the sidebar is trust order + glossary alone (SourcesReview.dc.html), even if another
		// application is still being prepared/stuck alongside the ready ones. Gated on snapshot.sources, not
		// sourcesRunCards - a still-queued application does not, by itself, mean "adding state" any more once
		// at least one application is already ready.
		if (pModel.snapshot.sources.isEmpty() && !pModel.sourcesRunCards.isEmpty())
		{
			pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><div class=\"mdmf-eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.stages"))).append("</div>");
			for (final String key : new String[] { "connection", "catalog", "scan", "ready" })
			{
				pOut.append("<p class=\"mdmf-hint\"><b style=\"color:var(--ink)\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.stage." + key)))
					.append("</b> &mdash; ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.stage." + key))).append("</p>");
			}
			pOut.append("</div>");
			pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><div class=\"mdmf-eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.onlyStuck"))).append("</div><p class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.onlyStuck.text"))).append("</p></div>");
		}
		if (!pModel.snapshot.sources.isEmpty())
		{
			WizardPage.renderTrustOrderAndGlossary(pOut, pModel, pLocale);
		}
	}

	private static void renderGlossaryTerms(final StringBuilder pOut, final Locale pLocale, final String... pKeys)
	{
		for (final String key : pKeys)
		{
			pOut.append("<p class=\"mdmf-hint\"><b style=\"color:var(--ink)\">").append(WizardPage.esc(WizardMessages.get(pLocale, key + ".term")))
				.append("</b> &mdash; ").append(WizardPage.esc(WizardMessages.get(pLocale, key + ".def"))).append("</p>");
		}
	}

	/**
	 * A deterministic color for one application's {@code srcDot} (SourcesAdding.dc.html/SourcesReview.dc.html) -
	 * purely cosmetic, picked from the connector/catalog-source id so the SAME application keeps the same
	 * color across a render, never persisted.
	 */
	private static String dotClass(final String pKey)
	{
		final int hash = pKey == null ? 0 : Math.abs(pKey.hashCode());
		return "mdmf-src2-dot-" + (hash % 5);
	}

	/**
	 * Visual pass follow-up (2026-09-24, coordinator: "proper singular/plural through the message bundle") -
	 * {@code pUnitKey + ".one"}/{@code ".many"} give the noun's two forms (e.g. {@code
	 * wizard.unit.application.one=application}, {@code .many=applications}); no {@code {0,choice,...}} pattern
	 * is used anywhere else in this bundle, so this follows the same "two keys, picked in Java" convention
	 * rather than introducing the first one.
	 */
	static String countText(final Locale pLocale, final long pCount, final String pUnitKey)
	{
		return pCount + " " + WizardMessages.get(pLocale, pUnitKey + (pCount == 1 ? ".one" : ".many"));
	}

	/**
	 * Sources-landing lot SOURCES (2026-09-24): ONE CARD PER APPLICATION currently queued into {@code
	 * ebx.SourcesRun} ({@link Model#sourcesRunCards}) - not only the connection the person happens to have
	 * picked in the interactive onboarding combobox (task brief: "not only the currently picked connection").
	 * Each card is its own {@link ApplicationStages} pipeline card (Connection -&gt; Catalog -&gt; Scan -&gt;
	 * Ready) plus the run's own coarse state as a small badge. The sample-file and catalog-pick ways in stay
	 * synchronous (task brief ss2 for catalog-pick; sample files are now QUEUED too as of this lot, so a
	 * just-dropped file's connector id shows up here as soon as the next render happens) and have nothing
	 * extra to show beyond their own report panel. A stage is given the CSS state class {@code
	 * mdmf-stage-<state>} in lower case ({@code done}/{@code running}/{@code waiting}/{@code needs-person}) so
	 * {@code wizard.css} can color exactly one of them orange - the {@link ApplicationStages.StageState#NEEDS_PERSON}
	 * one, per brief ("orange only when a person must act").
	 */
	private static void renderApplicationProgressCards(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.sourcesRunCards.isEmpty())
		{
			return;
		}
		for (final Model.SourcesRunCard card : pModel.sourcesRunCards)
		{
			WizardPage.renderOneApplicationProgressCard(pOut, pLocale, card);
		}
	}

	/**
	 * Visual pass: the 4-circle pipeline joined by lines (SourcesAdding.dc.html), one {@code
	 * mdmf-src2-pipe-stage} per {@link ApplicationStages.Stage} - a filled green check circle when {@code
	 * DONE}, a spinning-style ring when {@code RUNNING}, a dashed empty circle when {@code WAITING}, and
	 * ORANGE ONLY for {@code NEEDS_PERSON} (task brief's own repeated rule). The line segment between two
	 * circles is green once the stage it LEADS INTO is done, grey otherwise - same "how far did we get"
	 * reading as the mockup's own alternating green/grey line color.
	 */
	private static void renderOneApplicationProgressCard(final StringBuilder pOut, final Locale pLocale, final Model.SourcesRunCard pCard)
	{
		// scroll-anchor lot's own convention: a stable id so the Review screen's "See" link on a still-stuck
		// application (renderStuckApplications) can jump straight to this exact card.
		pOut.append("<div class=\"mdmf-panel mdmf-src2-app-card\" id=\"mdmf-app-progress-").append(WizardPage.esc(pCard.connectorId)).append("\">");
		pOut.append("<div class=\"mdmf-src2-app-head\"><span class=\"mdmf-src2-dot ").append(WizardPage.dotClass(pCard.connectorId)).append("\"></span><b>")
			.append(WizardPage.esc(pCard.display)).append("</b><span class=\"mdmf-chip mdmf-chip-target\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "sample-file".equals(pCard.connectorType) ? "wizard.review.kind.sampleFile" : "wizard.review.kind.connection")))
			.append("</span></div>");
		if (pCard.pipeline == null)
		{
			// wave 11 (audit F-48): no facts read yet - a background read is on its way, the page asks again by itself
			pOut.append("<div class=\"mdmf-src2-app-footer\"><div class=\"mdmf-src2-app-footer-text\"><span class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.sources.checking"))).append("</span> <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCES_RUN_POLL\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.checkAgain"))).append("</button>");
			if (!pCard.runSummary.isEmpty())
			{
				pOut.append(" ").append(WizardPage.esc(pCard.runSummary));
			}
			pOut.append("</div><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.sources.run.state." + pCard.runState)))
				.append("</span></div></div>");
			return;
		}
		pOut.append("<div class=\"mdmf-src2-pipe\">");
		final List<ApplicationStages.Stage> stages = pCard.pipeline.stages;
		ApplicationStages.Stage blockedStage = null;
		for (int i = 0; i < stages.size(); i++)
		{
			final ApplicationStages.Stage stage = stages.get(i);
			if (stage.state == ApplicationStages.StageState.NEEDS_PERSON && blockedStage == null)
			{
				blockedStage = stage;
			}
			final String stateClass = stage.state.name().toLowerCase(Locale.ENGLISH).replace('_', '-');
			// visual pass follow-up (2026-09-24, coordinator): the pipeline sub-text is a SHORT fixed phrase
			// per stage/state (wizard.step2.stageShort.<key>.<state>, mockup wording) - the verbose live
			// diagnosis message (stage.messageKey/args, e.g. "The catalog created the scan flows (mapping
			// 240)") moves to a title="" tooltip instead of being shown inline.
			final String fullMessage = WizardMessages.get(pLocale, stage.messageKey, stage.args);
			pOut.append("<div class=\"mdmf-src2-pipe-stage\" title=\"").append(WizardPage.esc(fullMessage)).append("\"><span class=\"mdmf-src2-pipe-circle mdmf-src2-pipe-circle-")
				.append(stateClass).append("\">")
				.append(stage.state == ApplicationStages.StageState.DONE ? "&#10003;" : stage.state == ApplicationStages.StageState.NEEDS_PERSON ? "&#9888;" : "")
				.append("</span><div class=\"mdmf-src2-pipe-label").append(stage.state == ApplicationStages.StageState.WAITING ? " mdmf-src2-pipe-label-waiting" : "")
				.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.stage." + stage.key))).append("</div><div class=\"mdmf-src2-pipe-sub\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.stageShort." + stage.key + "." + stateClass))).append("</div></div>");
			if (i < stages.size() - 1)
			{
				final boolean lineDone = stages.get(i + 1).state == ApplicationStages.StageState.DONE || stage.state == ApplicationStages.StageState.DONE;
				pOut.append("<div class=\"mdmf-src2-pipe-line").append(lineDone ? " mdmf-src2-pipe-line-done" : "").append("\"></div>");
			}
		}
		pOut.append("</div>");
		pOut.append("<div class=\"mdmf-src2-app-footer\"><div class=\"mdmf-src2-app-footer-text\">");
		if (pCard.pipeline.ready)
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.progress.ready")));
		}
		else if (blockedStage != null)
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.progress.needsPerson")));
		}
		else if (!pCard.runSummary.isEmpty())
		{
			pOut.append(WizardPage.esc(pCard.runSummary));
		}
		if (blockedStage != null)
		{
			// visual pass follow-up: the needs-a-person card's own actions on the result line - "Open the
			// connection" (SourceOnboarding.Action#OPEN_CONNECTION, the only action a blocked stage carries)
			// and "Check again", which simply re-renders (SOURCES_RUN_POLL, same no-op poll action the
			// background queue's own auto-refresh already uses - every card's diagnosis is read fresh on
			// every render, so a plain re-render IS "check again").
			if (blockedStage.action == SourceOnboarding.Action.OPEN_CONNECTION)
			{
				pOut.append(" <a class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.connectorRecordUrl(pCard.connectorId)))
					.append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.openConnection"))).append("</a>");
			}
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCES_RUN_POLL\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.checkAgain"))).append("</button>");
		}
		pOut.append("</div><span class=\"mdmf-chip ").append(pCard.pipeline.needsPerson ? "mdmf-chip-warn" : pCard.pipeline.ready ? "mdmf-chip-real" : "mdmf-chip-accent")
			.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.sources.run.state." + pCard.runState))).append("</span></div>");
		pOut.append("</div>");
	}

	// ------------------------------------------------------------------------------------- step 2: deduced scan

	/**
	 * Deduced-scan lot (2026-09-24, board 2d "Sources - a system without an official scan"): one pipeline card
	 * per {@link Model#deducedScanCards} entry - SAME visual component as {@link #renderOneApplicationProgressCard}
	 * (task brief: "match their layout"), five nodes instead of four: a static "Connection" node (always done -
	 * the {@code ProjectSource} already exists by the time a deduced scan can even be queued), then the four
	 * real {@link DeducedScanProgress} stages (How to list / Read a sample / Measure / Name and key).
	 */
	private static void renderDeducedScanCards(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		for (final Model.DeducedScanCard card : pModel.deducedScanCards)
		{
			// UX lot C4 (2026-10-14): a finished run whose application has its own card is shown IN that card (a fold), not as a second card
			if (DeducedScanCards.mergedIntoApplicationCard(card.entry.state, WizardPage.hasApplicationCard(pModel, card.projectSourceId)))
			{
				continue;
			}
			WizardPage.renderOneDeducedScanCard(pOut, pLocale, card);
		}
	}

	/** Whether the application of this project source has its own card on step 2 ({@link #renderReviewCards} draws one per source of the snapshot). */
	private static boolean hasApplicationCard(final Model pModel, final String pProjectSourceId)
	{
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			if (source.id.equals(pProjectSourceId))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * UX lot C4 (2026-10-14): "How the tables were found" - the stages of the run that found the tables of an application read from its data, folded
	 * into the application's own card (they were a second card of the same application, once per run). Nothing for an application with no finished run.
	 */
	private static void renderDeducedScanFold(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		for (final Model.DeducedScanCard card : pModel.deducedScanCards)
		{
			if (card.projectSourceId.equals(pSource.id) && DeducedScanCards.mergedIntoApplicationCard(card.entry.state, true))
			{
				pOut.append("<details class=\"mdmf-disclosure mdmf-deduced-fold\" id=\"mdmf-deduced-").append(WizardPage.esc(card.projectSourceId)).append("\"><summary>")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.foldTitle"))).append("</summary>");
				WizardPage.renderDeducedScanPipe(pOut, pLocale, card.entry);
				pOut.append("</details>");
				return;
			}
		}
	}

	private static void renderOneDeducedScanCard(final StringBuilder pOut, final Locale pLocale, final Model.DeducedScanCard pCard)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-src2-app-card\" id=\"mdmf-deduced-").append(WizardPage.esc(pCard.projectSourceId)).append("\">");
		pOut.append("<div class=\"mdmf-src2-app-head\"><span class=\"mdmf-src2-dot ").append(WizardPage.dotClass(pCard.projectSourceId)).append("\"></span><b>")
			.append(WizardPage.esc(pCard.display)).append("</b><span class=\"mdmf-chip mdmf-chip-target\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.kind.connection"))).append("</span><span class=\"mdmf-chip mdmf-chip-warn\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.chip"))).append("</span></div>");
		WizardPage.renderDeducedScanPipe(pOut, pLocale, pCard.entry);
		pOut.append("<div class=\"mdmf-src2-app-footer\"><div class=\"mdmf-src2-app-footer-text\">");
		// fix lot (2026-09-26, defect 14): once the run is DONE, the CURRENT decisions of its deduced assets win
		// over the run's own frozen entry.summary ("2 table(s) deduced, waiting for confirmation.") - that text
		// never changes again after a Confirm/Leave-out click, however long ago the run itself finished. Falls
		// back to the legacy text when there is no decision data at all (DeducedScanCardSummary.parts empty -
		// an older catalog with no scanOrigin field yet, or the counts were never populated by the caller).
		final List<DeducedScanCardSummary.Part> summaryParts = DeducedScanProgress.DONE.equals(pCard.entry.state)
			? DeducedScanCardSummary.parts(pCard.confirmedCount, pCard.leftOutCount, pCard.toConfirmCount)
			: Collections.<DeducedScanCardSummary.Part>emptyList();
		if (!summaryParts.isEmpty())
		{
			final StringBuilder summary = new StringBuilder();
			for (final DeducedScanCardSummary.Part part : summaryParts)
			{
				if (summary.length() > 0)
				{
					summary.append(", ");
				}
				summary.append(WizardPage.countText(pLocale, part.count, "wizard.unit.table")).append(' ')
					.append(WizardMessages.get(pLocale, "wizard.deduced.summary." + part.kind));
			}
			summary.append('.');
			pOut.append(WizardPage.esc(summary.toString()));
		}
		else if (!pCard.entry.summary.isEmpty())
		{
			pOut.append(WizardPage.esc(pCard.entry.summary));
		}
		else if (DeducedScanProgress.RUNNING.equals(pCard.entry.state))
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.progress.deducing")));
		}
		pOut.append("</div><span class=\"mdmf-chip ")
			.append(DeducedScanProgress.FAILED.equals(pCard.entry.state) ? "mdmf-chip-warn" : DeducedScanProgress.DONE.equals(pCard.entry.state) ? "mdmf-chip-real" : "mdmf-chip-accent")
			.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.state." + pCard.entry.state))).append("</span></div>");
		// fix lot (2026-09-26, defect 8): SKIPPED here means "an enrichment, not a source" (no listing operation
		// at all) or "this connection already has an official scan" - either way this application will never get
		// a table from a deduced scan, so it should not linger in the project's application list/trust ranking.
		// Removal is not automatic (a person might still want to onboard it another way) - a clear "Remove it"
		// action, reusing the exact SOURCE_REMOVE button/guard the Review screen's own Remove already uses
		// (WizardData#removeSource refuses if the landing was already generated - never the case here, a SKIPPED
		// deduced scan wrote no LandingTable at all).
		if (DeducedScanProgress.SKIPPED.equals(pCard.entry.state))
		{
			pOut.append("<div class=\"mdmf-src2-app-footer\">")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"SOURCE_REMOVE\" data-mdmf-source-id=\"")
				.append(WizardPage.esc(pCard.projectSourceId)).append("\" data-mdmf-confirm=\"")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.remove.confirm", pCard.display))).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.remove"))).append("</button></div>");
		}
		pOut.append("</div>");
	}

	/** The five circles of a deduced scan: the static Connection node, then {@link DeducedScanProgress}'s four stages of {@code pEntry}. */
	private static void renderDeducedScanPipe(final StringBuilder pOut, final Locale pLocale, final DeducedScanProgress.Entry pEntry)
	{
		pOut.append("<div class=\"mdmf-src2-pipe\">");
		// node 0: Connection, always DONE by construction (see method Javadoc)
		WizardPage.renderDeducedScanNode(pOut, pLocale, "connection", "DONE", "");
		pOut.append("<div class=\"mdmf-src2-pipe-line mdmf-src2-pipe-line-done\"></div>");
		for (int i = 0; i < DeducedScanProgress.STAGE_KEYS.length; i++)
		{
			final String key = DeducedScanProgress.STAGE_KEYS[i];
			final DeducedScanProgress.Stage stage = pEntry.stage(key);
			String state = stage == null ? DeducedScanProgress.WAITING : stage.state;
			// fix lot (2026-09-26, defect 13a): a stage that never got its own turn (still WAITING, DeducedScanProgress's
			// own Javadoc: "SAMPLE/MEASURE/NAME simply stay WAITING forever" once the whole entry ends SKIPPED at
			// LIST) used to keep reading "waiting" next to a card whose own chip already says "not a source" - a
			// finished, not a stuck, card. Rendered as SKIPPED instead (the wizard.deduced.stageShort.*.skipped
			// messages already existed for this - never wired up to any state before this fix).
			if (DeducedScanProgress.WAITING.equals(state) && DeducedScanProgress.SKIPPED.equals(pEntry.state))
			{
				state = DeducedScanProgress.SKIPPED;
			}
			WizardPage.renderDeducedScanNode(pOut, pLocale, key.toLowerCase(Locale.ENGLISH), state, stage == null ? "" : stage.summary);
			if (i < DeducedScanProgress.STAGE_KEYS.length - 1)
			{
				final boolean lineDone = DeducedScanProgress.DONE.equals(state);
				pOut.append("<div class=\"mdmf-src2-pipe-line").append(lineDone ? " mdmf-src2-pipe-line-done" : "").append("\"></div>");
			}
		}
		pOut.append("</div>");
	}

	/** One pipeline circle+label+sub-text, {@code state} one of {@link DeducedScanProgress}'s constants (or the fixed {@code "DONE"} for the static Connection node). {@code pKeyLower} feeds {@code wizard.deduced.stage.<key>}/{@code wizard.deduced.stageShort.<key>.<state>}. */
	private static void renderDeducedScanNode(final StringBuilder pOut, final Locale pLocale, final String pKeyLower, final String pState, final String pSummary)
	{
		final String stateClass = pState.toLowerCase(Locale.ENGLISH);
		pOut.append("<div class=\"mdmf-src2-pipe-stage\"><span class=\"mdmf-src2-pipe-circle mdmf-src2-pipe-circle-")
			.append("done".equals(stateClass) ? "done" : "running".equals(stateClass) ? "running" : "skipped".equals(stateClass) ? "needs-person" : "waiting").append("\">")
			.append("done".equals(stateClass) ? "&#10003;" : "skipped".equals(stateClass) ? "&#9888;" : "").append("</span><div class=\"mdmf-src2-pipe-label")
			.append("waiting".equals(stateClass) ? " mdmf-src2-pipe-label-waiting" : "").append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.stage." + pKeyLower))).append("</div><div class=\"mdmf-src2-pipe-sub\">")
			.append(WizardPage.esc(pSummary.isEmpty() ? WizardMessages.get(pLocale, "wizard.deduced.stageShort." + pKeyLower + "." + stateClass) : pSummary))
			.append("</div></div>");
	}

	/**
	 * Deduced-scan lot (2026-09-24, board 2e "Sources - confirm a deduced table"): one card per {@link
	 * Model.DeducedScanPendingGroup} x per {@link com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset} -
	 * table name (editable), "N columns x 25 rows read", key chip(s) with confidence, the measured columns
	 * table, the "How it is read" line ({@code Asset.readRecipe}), then Confirm / Choose another key (a link
	 * into step 3's own key chooser, see {@code ebx.DeducedScanConfirm}'s Javadoc for why there is no separate
	 * editor here) / Leave it out.
	 */
	private static void renderDeducedScanConfirm(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		for (final Model.DeducedScanPendingGroup group : pModel.deducedScanPending)
		{
			for (final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset asset : group.assets)
			{
				WizardPage.renderOneDeducedScanConfirmCard(pOut, pLocale, group, asset);
			}
		}
	}

	private static void renderOneDeducedScanConfirmCard(final StringBuilder pOut, final Locale pLocale, final Model.DeducedScanPendingGroup pGroup,
		final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingAsset pAsset)
	{
		// audit D-07: the asset is named by its natural key (source:qualifiedName - quotes, spaces, dots, colons, slashes...).
		// The key travels only in escaped attribute VALUES (data-mdmf-id -> the hidden mdmfId field), which round-trip; an
		// element id is a [A-Za-z0-9_] token derived from it (CatalogKeys.domToken), never the key itself.
		final String assetKey = pAsset.key == null ? "" : pAsset.key;
		pOut.append("<div class=\"mdmf-panel mdmf-src2-app-card mdmf-deduced-confirm-card\">");
		pOut.append("<div class=\"mdmf-src2-app-head\"><span class=\"mdmf-src2-dot ").append(WizardPage.dotClass(pGroup.projectSourceId)).append("\"></span><b>")
			.append(WizardPage.esc(pGroup.display)).append("</b><span class=\"mdmf-chip mdmf-chip-warn\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.chip"))).append("</span>");
		final String nameInputId = "mdmfDeducedName-" + com.on.ebx.factory.core.CatalogKeys.domToken(assetKey);
		pOut.append("<span style=\"margin-left:auto;display:flex;align-items:center;gap:8px\"><label for=\"").append(WizardPage.esc(nameInputId))
			.append("\" class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.table"))).append("</label>")
			.append("<input type=\"text\" class=\"mono\" id=\"").append(WizardPage.esc(nameInputId)).append("\" maxlength=\"60\" value=\"")
			.append(WizardPage.esc(pAsset.tableName)).append("\"/></span></div>");

		final List<String> keyColumns = new ArrayList<String>();
		for (final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingColumn column : pAsset.columns)
		{
			if (column.primaryKey)
			{
				keyColumns.add(column.name);
			}
		}
		pOut.append("<div class=\"mdmf-src2-table-meta\">").append(WizardPage.esc(WizardPage.countText(pLocale, pAsset.columns.size(), "wizard.unit.column")))
			// fix lot (2026-09-26): was a hardcoded 25 (the requested sample cap) regardless of how many rows the
			// source actually held - now the real count the sample read (Asset.rowCount, DeducedScanCatalogWriter).
			.append(" &middot; ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.rowsRead", Integer.valueOf(pAsset.sampleSize))))
			.append("</div><div class=\"mdmf-src2-table-key\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.keyProposed", Integer.valueOf(pAsset.scanConfidence)))).append("<span>");
		WizardPage.renderKeyChips(pOut, keyColumns);
		pOut.append("</span></div>");

		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-deduced-columns\"><thead><tr><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.col.column"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.col.type"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.col.filled"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.col.distinct"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.col.example"))).append("</th></tr></thead><tbody>");
		for (final com.on.ebx.factory.ebx.DeducedScanCatalog.PendingColumn column : pAsset.columns)
		{
			pOut.append("<tr><td><span class=\"mono\">").append(WizardPage.esc(column.name)).append("</span>");
			if (column.primaryKey)
			{
				pOut.append(" <span class=\"mdmf-key-chip\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.keyBadge"))).append("</span>");
			}
			pOut.append("</td><td>").append(WizardPage.esc(column.dataType)).append("</td><td class=\"mono\">")
				.append(column.filled < 0 ? "-" : column.filled + "/" + column.sampleSize).append("</td><td class=\"mono\">")
				.append(column.distinct < 0 ? "-" : String.valueOf(column.distinct)).append("</td><td class=\"mono\">")
				.append(column.example.isEmpty() ? "-" : WizardPage.esc(column.example)).append("</td></tr>");
		}
		pOut.append("</tbody></table></div>");

		pOut.append("<div class=\"mdmf-hint\"><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.howReadLabel"))).append("</b> ")
			.append("<span class=\"mono\">").append(WizardPage.esc(pAsset.readRecipe.operation)).append("</span>");
		if (!pAsset.readRecipe.itemsPath.isEmpty())
		{
			pOut.append(" &middot; ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.rowsIn", pAsset.readRecipe.itemsPath)));
		}
		if (!pAsset.scanRationale.isEmpty())
		{
			pOut.append(" &middot; <span class=\"mdmf-hint\">&quot;").append(WizardPage.esc(pAsset.scanRationale)).append("&quot;</span>");
		}
		pOut.append("</div>");

		pOut.append("<div style=\"display:flex;align-items:center;gap:10px\">");
		// data-mdmf-id/data-mdmf-name-input reuse the generic mdmfId/mdmfAssetName postback fields wizard.js
		// already fills for every button carrying them (same "button names the input's id, read its CURRENT
		// value right before submit" idea as data-mdmf-code-input/data-mdmf-trust-input).
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"DEDUCED_SCAN_CONFIRM\" data-mdmf-id=\"")
			.append(WizardPage.esc(assetKey)).append("\" data-mdmf-name-input=\"").append(WizardPage.esc(nameInputId)).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.confirm", pAsset.tableName))).append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-goto-step=\"3\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.chooseAnotherKey")))
			.append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-danger\" data-mdmf-action=\"DEDUCED_SCAN_LEAVE_OUT\" data-mdmf-id=\"")
			.append(WizardPage.esc(assetKey)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.leaveOut"))).append("</button>");
		pOut.append("</div></div>");
	}

	// -------------------------------------------------------------------------------------------- step 2: review

	/** Every ready application's own Review card (SourcesReview.dc.html) - renders nothing when there are none. */
	private static void renderReviewCards(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.snapshot.sources.isEmpty())
		{
			return;
		}
		// UX lot U2 (2026-10-13): only when tables exist and none is ticked - with no table at all, each card says what to do instead
		if (SourceTableList.tablesButNoneTicked(pModel.snapshot.sources))
		{
			pOut.append("<div class=\"mdmf-panel mdmf-src2-continue\"><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tickBanner")))
				.append("</p></div>");
		}
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			WizardPage.renderReviewCard(pOut, pModel, pLocale, source);
		}
	}

	/**
	 * One ready application's Review card: header (dot, name, kind chip, ready/status chip, "Scan again", editable code on the right) then
	 * either the tables to keep ({@link #renderTableList}) or why there is none ({@link #renderNoTableBody}). The Remove button (untouched
	 * source only) sits in the header row, same place the mockup's own per-card actions live. UX lot U2 (2026-10-13): every card has a
	 * "Scan again" (a catalog-scanned application had none), the tables start unticked, and a card that found nothing says why.
	 */
	private static void renderReviewCard(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-src2-app-card\" id=\"mdmf-app-card-").append(WizardPage.esc(pSource.id)).append("\">");
		pOut.append("<div class=\"mdmf-src2-app-head\"><span class=\"mdmf-src2-dot ").append(WizardPage.dotClass(pSource.id)).append("\"></span><b>")
			.append(WizardPage.esc(WizardPage.displayName(pSource))).append("</b><span class=\"mdmf-chip mdmf-chip-target\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "sample-file".equals(pSource.platform) ? "wizard.review.kind.sampleFile" : "wizard.review.kind.connection")))
			// visual pass follow-up (2026-09-24, coordinator): the mockup's own "ready" chip, not the raw
			// ProjectSource.status (SELECTED/GENERATED/LOADED) - every source on THIS card is, by definition,
			// already a real application of the project (it exists in snapshot.sources at all), which is what
			// "ready" means on the Review screen regardless of how far its own landing has since progressed.
			.append("</span><span class=\"mdmf-chip mdmf-chip-real\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.chip.ready"))).append("</span>");
		// live fix (2026-09-24): Remove used to disappear once a source left SELECTED/untouched - a card with
		// a generated or loaded landing had no Remove at all, inconsistent card to card. It is now on EVERY
		// card, with a confirmation (data-mdmf-confirm, read generically by wizard.js); the actual guard
		// stays server-side in WizardData.removeSource (a source whose landing was already generated answers
		// a clear OperationException instead, shown as the page's own warning banner) - never silently refused
		// here by just hiding the button.
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"SOURCE_REMOVE\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSource.id)).append("\" data-mdmf-confirm=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.remove.confirm", WizardPage.displayName(pSource)))).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.remove"))).append("</button>");
		WizardPage.renderCardScanButton(pOut, pModel, pLocale, pSource);
		final String codeInputId = "mdmfCode-" + pSource.id;
		pOut.append("<span style=\"margin-left:auto;display:flex;align-items:center;gap:8px\"><label for=\"").append(WizardPage.esc(codeInputId))
			.append("\" class=\"mdmf-hint\" style=\"margin:0\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.code"))).append("</label>")
			.append("<input type=\"text\" class=\"mono\" id=\"").append(WizardPage.esc(codeInputId)).append("\" maxlength=\"40\" value=\"")
			.append(WizardPage.esc(pSource.sourceSystem)).append("\" style=\"width:90px\"/>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCE_SET_CODE\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
			.append("\" data-mdmf-code-input=\"").append(WizardPage.esc(codeInputId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.code.save")))
			.append("</button></span></div>");
		if (pSource.tables.isEmpty())
		{
			WizardPage.renderNoTableBody(pOut, pModel, pLocale, pSource);
		}
		else
		{
			WizardPage.renderTableList(pOut, pLocale, pSource, "mdmf-app-card-" + pSource.id);
		}
		WizardPage.renderDeducedScanFold(pOut, pModel, pLocale, pSource); // UX lot C4: the finished run of a source read from its data, no second card
		pOut.append("</div>");
	}

	/**
	 * UX lot U2 (2026-10-13): the header button of a card that asks the source again. An application whose tables were found from the data
	 * (no catalog scan exists for its connection - what {@code DEDUCED_SCAN_START} reads) keeps that path: "Find its tables" while it has none,
	 * "Scan again" once it has. Every other application with a connection gets {@code SOURCE_RESCAN}: the catalog scan of the connection and
	 * the plan, one background job - the catalog-scanned card had no such button (measured live: no way out of an empty Salesforce card).
	 * Designers only (the job needs that right): disabled with its reason for the others.
	 */
	private static void renderCardScanButton(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		final boolean hasDeducedAsset = WizardRules.hasDeducedAsset(pSource);
		if (WizardPage.deducedTablesListable(pSource))
		{
			return; // UX lot C4: the card's body offers "List its tables" (primary) and "Read its data again" (link) - not a second scan button up here
		}
		if (hasDeducedAsset || pSource.tables.isEmpty() && pSource.deducedScan == DeducedScanEligibility.OFFERED)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DEDUCED_SCAN_START\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
				.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, hasDeducedAsset ? "wizard.deduced.rescan" : "wizard.deduced.start"))).append("</button>");
			return;
		}
		if (!pSource.connector.isEmpty() && pSource.deducedScan != DeducedScanEligibility.UNREACHABLE)
		{
			WizardPage.renderScanAgainButton(pOut, pModel, pLocale, pSource, false);
		}
	}

	/**
	 * UX lot C4 (2026-10-14): an application with no planned table whose connection has no catalog scan (its tables are read from the data) and whose
	 * tables are all decided ({@link DeducedPlanRule#listable}) - what it needs is the plan, never another deduced scan.
	 */
	private static boolean deducedTablesListable(final WizardSnapshot.SourceInfo pSource)
	{
		return pSource.tables.isEmpty() && pSource.deducedScan == DeducedScanEligibility.OFFERED && DeducedPlanRule.listable(pSource.unplannedAssets);
	}

	/** The "Scan again" button ({@code SOURCE_RESCAN}); {@code pPrimary} for the card body's own call to action. */
	private static void renderScanAgainButton(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource, final boolean pPrimary)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm").append(pPrimary ? " mdmf-btn-primary" : "").append("\" data-mdmf-action=\"SOURCE_RESCAN\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSource.id)).append("\"").append(pModel.designer ? "" : " disabled=\"disabled\" title=\"" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.gate.adminOnly")) + "\"")
			.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.scanAgain"))).append("</button>");
	}

	/**
	 * UX lot U2 (2026-10-13): why an application shows no table, in the user's words, and the one button that unblocks it. Three states
	 * ({@link SourceTableList#noTableState}): the catalog holds tables with columns (press "Find its tables"), it holds objects but no
	 * column was read (the cause measured live on Salesforce: an object declared by OpenLineage events, never scanned - "Scan again"), or
	 * it holds nothing. The per-object reasons come from the catalog itself ({@link WizardSnapshot.SourceInfo#unplannedAssets}); the same words end the job's message.
	 */
	private static void renderNoTableBody(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		if (pSource.deducedScan == DeducedScanEligibility.UNREACHABLE)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.unreachable", pSource.deducedScanError))).append("</p>");
			return;
		}
		if (WizardPage.deducedTablesListable(pSource))
		{
			// UX lot C4 (2026-10-14): the tables were found from the data and every one is decided: listing them is the next step, not another scan
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.noTable.fromDataListable"))).append("</p><div class=\"mdmf-actions\">");
			WizardPage.renderFindTablesButton(pOut, pLocale, pSource.id, true, "wizard.src2.listTables");
			pOut.append("<button type=\"button\" class=\"mdmf-linkbtn\" data-mdmf-action=\"DEDUCED_SCAN_START\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id)).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.readAgain"))).append("</button></div>");
			return;
		}
		if (pSource.deducedScan == DeducedScanEligibility.OFFERED)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.noTable.fromData"))).append("</p>");
			return;
		}
		if (pSource.connector.isEmpty() && pSource.catalogSourceId.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.tables.none"))).append("</p>");
			return;
		}
		final SourceTableList.NoTableState state = SourceTableList.noTableState(pSource.unplannedAssets);
		// live fix (2026-09-24, project A / PERSONNES_SAP_SAMPLE): "no table yet" used to read the same whether the landing plan had
		// never run, or had run once and was since RESET - only the wording differs; nothing is written here.
		if (LandingTableHint.wasReset(pSource.pipelineSteps) && state == SourceTableList.NoTableState.READY_TO_PLAN)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.tables.wasReset"))).append("</p>");
		}
		else if (state == SourceTableList.NoTableState.READY_TO_PLAN)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.noTable.ready", WizardPage.countText(pLocale, SourceTableList.withColumns(pSource.unplannedAssets), "wizard.unit.table"))))
				.append("</p>");
		}
		else if (state == SourceTableList.NoTableState.NEEDS_SCAN)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.noTable.needsScan"))).append("</p>");
		}
		else
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.noTable.nothing"))).append("</p>");
		}
		final List<String> withoutColumn = SourceTableList.namesWithoutColumn(pSource.unplannedAssets);
		if (!withoutColumn.isEmpty())
		{
			pOut.append("<ul class=\"mdmf-src2-reasons\">");
			for (int i = 0; i < withoutColumn.size() && i < SourceTableList.REASONS_SHOWN; i++)
			{
				pOut.append("<li><b>").append(WizardPage.esc(PlanOutcomes.shortName(withoutColumn.get(i)))).append("</b> - ")
					.append(WizardPage.esc(WizardMessages.get(pLocale, PlanOutcomes.reasonKey(PlanOutcomes.OUTCOME_NO_COLUMN)))).append("</li>");
			}
			if (withoutColumn.size() > SourceTableList.REASONS_SHOWN)
			{
				pOut.append("<li>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.reason.more", withoutColumn.size() - SourceTableList.REASONS_SHOWN))).append("</li>");
			}
			pOut.append("</ul>");
		}
		pOut.append("<div class=\"mdmf-actions\">");
		if (state == SourceTableList.NoTableState.READY_TO_PLAN)
		{
			WizardPage.renderFindTablesButton(pOut, pLocale, pSource.id, true);
		}
		else if (!pSource.connector.isEmpty())
		{
			WizardPage.renderScanAgainButton(pOut, pModel, pLocale, pSource, true);
		}
		else
		{
			WizardPage.renderFindTablesButton(pOut, pLocale, pSource.id, true);
		}
		pOut.append("</div>");
	}

	/** "Find its tables": {@code SOURCE_FIND_TABLES}, the plan of one application as a background job (what step 3's Advanced "Plan" also does, with the new tables unticked). */
	private static void renderFindTablesButton(final StringBuilder pOut, final Locale pLocale, final String pSourceId, final boolean pPrimary)
	{
		WizardPage.renderFindTablesButton(pOut, pLocale, pSourceId, pPrimary, "wizard.src2.findTables");
	}

	/** The same action with its own wording: "List its tables" for an application whose tables were found from the data and are all decided (UX lot C4). */
	private static void renderFindTablesButton(final StringBuilder pOut, final Locale pLocale, final String pSourceId, final boolean pPrimary, final String pLabelKey)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm").append(pPrimary ? " mdmf-btn-primary" : "").append("\" data-mdmf-action=\"SOURCE_FIND_TABLES\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSourceId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, pLabelKey))).append("</button>");
	}

	/**
	 * UX lot U2 (2026-10-13): the tables of one card, most rows first, with "Select all / Select none", a counter, and ONE button that keeps what is
	 * ticked ({@code TABLES_KEEP}). A tick posts nothing: {@code wizard.js} collects the ticks changed since the page was drawn and sends them with
	 * that button - or with "Continue to the landing", for every card at once. A table whose landing was already generated has its scope fixed
	 * (the server refuses a change): its box is disabled.
	 */
	private static void renderTableList(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.SourceInfo pSource, final String pCardId)
	{
		final List<WizardSnapshot.TableInfo> ordered = SourceTableList.order(pSource.tables);
		pOut.append("<div class=\"mdmf-src2-tablebar\"><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.lead")))
			.append("</span><span class=\"mdmf-hint mdmf-tick-count\" data-mdmf-count-template=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.count")))
			.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.count", SourceTableList.ticked(ordered), ordered.size())))
			.append("</span><button type=\"button\" class=\"mdmf-linkbtn\" data-mdmf-tick-all=\"true\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.all")))
			.append("</button><button type=\"button\" class=\"mdmf-linkbtn\" data-mdmf-tick-all=\"false\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.none")))
			.append("</button></div>");
		for (final WizardSnapshot.TableInfo table : ordered)
		{
			WizardPage.renderReviewTableRow(pOut, pLocale, pSource, table);
		}
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"TABLES_KEEP\" data-mdmf-ticks-scope=\"")
			.append(WizardPage.esc(pCardId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.keep"))).append("</button></div>");
	}

	/**
	 * One table row (task brief item 2): the "in scope?" tick ({@code LandingTable.selected}, posted by {@code TABLES_KEEP} - UX lot U2, 2026-10-13: a
	 * tick no longer posts by itself), the table name/size, and its key - "declared by the source" (chips, column
	 * order, {@code mdmf-key-chip}/{@code mdmf-key-plus} - same markup lot LANDING's own step 3 uses) when
	 * {@link WizardSnapshot.TableInfo#declaredKeyColumns} is non-empty, else "key found at the landing" naming
	 * the landing's own current key columns, or "no key yet".
	 */
	private static void renderReviewTableRow(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.SourceInfo pSource, final WizardSnapshot.TableInfo pTable)
	{
		pOut.append("<div class=\"mdmf-src2-table-row\">");
		// visual pass follow-up (2026-09-24, coordinator): a real checkbox (label = table name), not a button labeled "Included"/"Excluded".
		// UX lot U2: no data-mdmf-action any more - the box is read by wizard.js when "Keep these tables" / "Continue" is pressed (data-mdmf-tick-id)
		final String checkboxId = "mdmfTableSel-" + pTable.id;
		final boolean frozen = !"PROPOSED".equals(pTable.status);
		pOut.append("<input type=\"checkbox\" class=\"mdmf-tick\" id=\"").append(WizardPage.esc(checkboxId)).append("\"").append(pTable.selected ? " checked=\"checked\"" : "")
			.append(frozen ? " disabled=\"disabled\" title=\"" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.tables.frozen")) + "\"" : "")
			.append(" data-mdmf-tick-id=\"").append(WizardPage.esc(pTable.id)).append("\"/>");
		// live fix (2026-09-24, project A / PERSONNES_SALESFORCE): "18 columns, 0 rows" for a table that had
		// really loaded 180 - LandingTable.sourceRowCount (the catalog's own profile) is unset for these
		// sample-file sources; lastLoadRows (the landing's own last load) carried the real count all along.
		// See wizard.TableRowCount's own Javadoc for the measurement.
		final TableRowCount rowCount = TableRowCount.of(pTable.sourceRowCountKnown, pTable.sourceRowCount, pTable.lastLoadRows);
		pOut.append("<div class=\"mdmf-src2-table-meta\"><label for=\"").append(WizardPage.esc(checkboxId)).append("\"><b>").append(WizardPage.esc(pTable.tableName))
			.append("</b></label><div class=\"mdmf-hint\">").append(WizardPage.esc(WizardPage.countText(pLocale, pTable.columnCount, "wizard.unit.column")));
		if (rowCount.known)
		{
			pOut.append(", ").append(WizardPage.esc(WizardPage.countText(pLocale, rowCount.value, "wizard.unit.row")));
		}
		pOut.append("</div></div>");
		if (!pTable.declaredKeyColumns.isEmpty())
		{
			// fix lot (2026-09-26, defect 10): this label always read "Key declared by the source:", even for a
			// deduced-scan table whose Column.primaryKey is the language model's own proposal, confirmed by a
			// person. Same origin signal (Asset.scanOrigin), same distinction, applied here too.
			// UX lot U2 (2026-10-13): and "confirmed by a person" said neither who nor when - and the catalog is shared by every project, so a table
			// confirmed in ANOTHER project arrived "confirmed" here (measured: DemoCRM). Now "confirmed by <user> on <date>", or "confirmed earlier".
			pOut.append("<div class=\"mdmf-src2-table-key\">").append(WizardPage.esc(WizardPage.keyLabel(pLocale, pTable))).append("<span>");
			WizardPage.renderKeyChips(pOut, pTable.declaredKeyColumns);
			pOut.append("</span></div>");
		}
		else
		{
			// visual pass follow-up: "no key yet" dropped - a table with no key at all still reads "key found
			// at the landing" (the coordinator's own wording), chips just omitted when keyColumns is empty too.
			// visual pass round 3 (coordinator): the colon-carrying text is only correct when chips actually
			// follow - a stray "Key found at the landing:" with nothing after it when there is no key at all
			// is its own separate, colon-free wording.
			// live fix (2026-09-24): a landing-assigned key (any origin: RULE/LLM/USER/SOURCE) now reads
			// "Key:" plus BOTH the column chips AND an origin chip (renderKeyChips' own 4-arg overload,
			// already used by step 3 - task brief item 5) - it used to read "Key found at the landing:" with
			// no origin shown at all, the only piece of this line that was actually wrong.
			final boolean hasLandingKey = !pTable.keyColumns.isEmpty();
			pOut.append("<div class=\"mdmf-src2-table-key\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, hasLandingKey ? "wizard.review.key.atLanding" : "wizard.review.key.atLandingNone")));
			if (hasLandingKey)
			{
				pOut.append("<span>");
				WizardPage.renderKeyChips(pOut, pLocale, pTable.keyColumns, pTable.keyOrigin);
				pOut.append("</span>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");
	}

	/** The label before the key chips of a table whose key the SOURCE declared ("Key declared by the source:") or the language model proposed and somebody confirmed ("... confirmed by X on D:" / "confirmed earlier:"). */
	private static String keyLabel(final Locale pLocale, final WizardSnapshot.TableInfo pTable)
	{
		if (!"DEDUCED".equals(pTable.scanOrigin))
		{
			return WizardMessages.get(pLocale, "wizard.review.key.declared");
		}
		if (!pTable.scanConfirmedBy.isEmpty() && pTable.scanConfirmedAt > 0L)
		{
			return WizardMessages.get(pLocale, "wizard.review.key.deduced.by", pTable.scanConfirmedBy,
				java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, pLocale).format(new java.util.Date(pTable.scanConfirmedAt)));
		}
		if (pTable.scanConfirmedAt > 0L)
		{
			return WizardMessages.get(pLocale, "wizard.review.key.deduced.on", java.text.DateFormat.getDateInstance(java.text.DateFormat.MEDIUM, pLocale).format(new java.util.Date(pTable.scanConfirmedAt)));
		}
		return WizardMessages.get(pLocale, "wizard.review.key.deduced");
	}

	/** {@code "Key ... on D:"} without its closing colon (and the no-break space French puts before it) - the same sentence as a hint after the chips. */
	static String withoutTrailingColon(final String pText)
	{
		int end = pText.length();
		while (end > 0 && (pText.charAt(end - 1) == ':' || Character.isWhitespace(pText.charAt(end - 1)) || pText.charAt(end - 1) == '\u00A0'))
		{
			end--;
		}
		return pText.substring(0, end);
	}

	/** One chip per column, "+" between them, column order - {@code mdmf-key-chip}/{@code mdmf-key-plus}, the SAME class names lot LANDING's own step 3 key chooser uses (task brief item 2: "keep them"), so both lots render a composite key identically wherever it shows up. */
	private static void renderKeyChips(final StringBuilder pOut, final List<String> pColumns)
	{
		// merge of lots SOURCES + LANDING (2026-09-24): one key markup for the whole wizard - the step-3
		// renderer below (chips + "+" in column order); blank names are dropped here as they always were.
		final List<String> columns = new ArrayList<String>();
		for (final String column : pColumns)
		{
			if (column != null && !column.trim().isEmpty())
			{
				columns.add(column.trim());
			}
		}
		if (!columns.isEmpty())
		{
			WizardPage.renderKeyChips(pOut, Locale.ROOT, columns, null);
		}
	}

	/**
	 * Landing test fixes (2026-10-17): the line under an application of the "in progress" list. An application that already is one of the project's own (its card above says
	 * "ready") and whose run finished says so; the sentence "not part of the landing until it is ready" is only for one that is not yet.
	 */
	static String stuckLineKey(final Model pModel, final Model.SourcesRunCard pCard)
	{
		if (pCard.pipeline == null)
		{
			return "wizard.sources.checking";
		}
		if (SourcesProgress.DONE.equals(pCard.runState))
		{
			for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
			{
				if (source.connector != null && source.connector.equals(pCard.connectorId))
				{
					return "wizard.review.ready.lineOnly";
				}
			}
		}
		return "wizard.review.stuck.lineOnly";
	}

	/**
	 * A one-line row per application queued into {@code ebx.SourcesRun} whose pipeline is NOT {@code ready}
	 * (task brief item 2: "not part of the landing until it is ready") - See (scrolls to its progress card
	 * above, same anchor id convention wizard.js's scroll-anchor lot already uses) and Remove ({@code
	 * SOURCES_RUN_REMOVE}). No-op (renders nothing) when every queued application is already ready or there is
	 * none queued at all.
	 */
	private static void renderStuckApplications(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final List<Model.SourcesRunCard> stuck = new ArrayList<Model.SourcesRunCard>();
		for (final Model.SourcesRunCard card : pModel.sourcesRunCards)
		{
			if (card.pipeline == null || !card.pipeline.ready) // no pipeline yet (wave 11, audit F-48) = still being checked: listed, with that said instead of "stuck"
			{
				stuck.add(card);
			}
		}
		if (stuck.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-panel\">");
		for (final Model.SourcesRunCard card : stuck)
		{
			pOut.append("<div class=\"mdmf-src2-stuck-row\"><span class=\"mdmf-src2-dot ").append(WizardPage.dotClass(card.connectorId)).append("\"></span><b>")
				.append(WizardPage.esc(card.display)).append("</b><span class=\"mdmf-chip ").append("wizard.review.ready.lineOnly".equals(WizardPage.stuckLineKey(pModel, card)) ? "mdmf-chip-real" : "mdmf-chip-warn").append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.sources.run.state." + card.runState))).append("</span>")
				.append("<span style=\"flex-grow:1\">").append(WizardPage.esc(WizardMessages.get(pLocale, WizardPage.stuckLineKey(pModel, card)))).append("</span>")
				.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"#mdmf-app-progress-").append(WizardPage.esc(card.connectorId)).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.stuck.see"))).append("</a> ")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCES_RUN_REMOVE\" data-mdmf-remove-connector-id=\"")
				.append(WizardPage.esc(card.connectorId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.stuck.remove"))).append("</button></div>");
		}
		pOut.append("</div>");
	}

	/**
	 * "When two applications disagree" (task brief item 2): {@link WizardSnapshot.SourceInfo#trustRank} order
	 * (lower = more trusted, ascending), with up/down buttons ({@code SOURCE_TRUST_UP}/{@code
	 * SOURCE_TRUST_DOWN}) - see {@code WizardData#reorderSourceTrust}'s own Javadoc for the "rewrite as 1..n"
	 * write. Plus the step's own glossary block. Renders nothing when there are fewer than two applications
	 * (nothing to disagree about, and reordering one application is meaningless) OR no applications at all -
	 * the glossary alone would be a strange thing to show on an empty step.
	 */
	private static void renderTrustOrderAndGlossary(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.snapshot.sources.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><div class=\"mdmf-eyebrow\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.trust.title"))).append("</div>");
		if (pModel.snapshot.sources.size() < 2)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.trust.onlyOne"))).append("</p>");
		}
		else
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.trust.lead"))).append("</p>");
			final List<WizardSnapshot.SourceInfo> ordered = new ArrayList<WizardSnapshot.SourceInfo>(pModel.snapshot.sources);
			Collections.sort(ordered, new Comparator<WizardSnapshot.SourceInfo>()
			{
				@Override
				public int compare(final WizardSnapshot.SourceInfo pLeft, final WizardSnapshot.SourceInfo pRight)
				{
					return Integer.compare(pLeft.trustRank, pRight.trustRank);
				}
			});
			pOut.append("<div style=\"display:flex;flex-direction:column;gap:8px\">");
			for (int i = 0; i < ordered.size(); i++)
			{
				final WizardSnapshot.SourceInfo source = ordered.get(i);
				final boolean first = i == 0;
				pOut.append("<div class=\"mdmf-src2-trust-row").append(first ? " mdmf-src2-trust-row-first" : "").append("\"><span class=\"mdmf-src2-trust-n")
					.append(first ? " mdmf-src2-trust-n-first" : "").append("\">").append(i + 1).append("</span><span class=\"mdmf-src2-dot ")
					.append(WizardPage.dotClass(source.id)).append("\"></span><span class=\"mdmf-src2-trust-name\">").append(WizardPage.esc(WizardPage.displayName(source)))
					.append("</span>");
				if (i > 0)
				{
					pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCE_TRUST_UP\" data-mdmf-source-id=\"")
						.append(WizardPage.esc(source.id)).append("\" aria-label=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.trust.up")))
						.append("\">&uarr;</button>");
				}
				if (i < ordered.size() - 1)
				{
					pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCE_TRUST_DOWN\" data-mdmf-source-id=\"")
						.append(WizardPage.esc(source.id)).append("\" aria-label=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.trust.down")))
						.append("\">&darr;</button>");
				}
				pOut.append("</div>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><div class=\"mdmf-eyebrow\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sidebar.glossary"))).append("</div>");
		WizardPage.renderGlossaryTerms(pOut, pLocale, "wizard.step2.glossary.application", "wizard.step2.glossary.connection", "wizard.step2.glossary.catalog",
			"wizard.step2.glossary.scan", "wizard.step2.glossary.code", "wizard.step2.glossary.table", "wizard.step2.glossary.key", "wizard.step2.glossary.trustRank");
		pOut.append("</div>");
	}

	/**
	 * "Continue to the landing" (task brief item 2), with the counts actually known: applications (ready
	 * ones, {@code snapshot.sources.size()}), tables (summed over every source), and rows FROM THE CATALOG
	 * ({@code TableInfo#sourceRowCount}, summed) only when at least one table carries a non-zero value -
	 * omitted entirely otherwise (task brief: "omit a number you do not have"), since 0 would misleadingly
	 * read as "zero rows" rather than "unknown". Renders nothing when there is no ready application yet - the
	 * button would have nowhere useful to send anyone.
	 */
	private static void renderContinueToLanding(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.snapshot.sources.isEmpty())
		{
			return;
		}
		// step-gating lot (2026-09-24): the button used to render regardless of whether any table was ticked
		// yet (WizardRules.stepStates' own, now stricter, Landing prerequisite).
		// UX lot U2 (2026-10-13): when it is not met, the banner shows only if tables exist and none is ticked (renderReviewCards says it, once, above the
		// cards) - the old line "Tick at least one table..." also showed when there was nothing to tick, which asked for something impossible.
		final WizardRules.StepState landingState = pModel.stepStates[WizardRules.STEP_LANDING];
		if (!landingState.reachable)
		{
			return;
		}
		int tableCount = 0;
		long rowCount = 0L;
		boolean anyRowCountKnown = false;
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				// fix/wizard-live-wave1 (2026-09-24, live bug: "3 applications, 19 tables" while only 4 tables
				// were ticked): only a SELECTED table is actually going to the landing - an unselected one
				// (still listed, just not chosen) must not inflate this count or its row total.
				if (!table.selected)
				{
					continue;
				}
				tableCount++;
				// live fix (2026-09-24): same fallback as the per-table line (TableRowCount) - a table only
				// known through its landing's own last load used to be silently left out of this sum too.
				final TableRowCount tableRowCount = TableRowCount.of(table.sourceRowCountKnown, table.sourceRowCount, table.lastLoadRows);
				if (tableRowCount.known)
				{
					anyRowCountKnown = true;
					rowCount += tableRowCount.value;
				}
			}
		}
		// visual pass follow-up (2026-09-24, coordinator): proper singular/plural through the bundle
		// (WizardPage.countText), not a single {0} application(s)-style pattern.
		String countsText = WizardPage.countText(pLocale, pModel.snapshot.sources.size(), "wizard.unit.application") + ", "
			+ WizardPage.countText(pLocale, tableCount, "wizard.unit.table");
		if (anyRowCountKnown)
		{
			countsText = countsText + ", " + WizardMessages.get(pLocale, "wizard.review.continue.about", WizardPage.countText(pLocale, rowCount, "wizard.unit.row"));
		}
		// deduced-scan lot (2026-09-24, board 2e), turned into a warning by fix/continue-landing-warning
		// (2026-09-26): a deduced table still TO_CONFIRM no longer disables this button. Next, the rail and the
		// confirm card's own "Choose another key" all reach step 3 anyway, and the contract ("never loaded
		// before a person confirms it") is enforced server-side, per table: LandingFlows -> ReadRecipe.resolve
		// fails a TO_CONFIRM table's flow ("deduced table not confirmed yet") while the others load normally.
		// So this button now behaves like Next and only says what will be left out. ANY TO_CONFIRM table of the
		// project counts, ticked or not.
		final List<String> pendingScanStates = new ArrayList<String>();
		for (final Model.DeducedScanPendingGroup group : pModel.deducedScanPending)
		{
			for (int i = 0; i < group.assets.size(); i++)
			{
				pendingScanStates.add("TO_CONFIRM");
			}
		}
		// UX lot U2 (2026-10-13): also carries the ticks of every card (one TABLES_KEEP for all of them) - wizard.js skips the round trip when nothing was changed
		pOut.append("<div class=\"mdmf-panel mdmf-src2-continue\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\""
			+ " data-mdmf-action=\"TABLES_KEEP\" data-mdmf-ticks-scope=\"all\" data-mdmf-skip-if-clean=\"true\" data-mdmf-goto-step=\"3\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.review.continue"))).append("</button><span class=\"mdmf-hint\">").append(WizardPage.esc(countsText))
			.append("</span>");
		if (WizardRules.hasUnconfirmedDeducedTable(pendingScanStates))
		{
			pOut.append("<span class=\"mdmf-hint mdmf-hint-warn\"><b>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.deduced.pendingAtLanding", Integer.valueOf(pendingScanStates.size())))).append("</b></span>");
		}
		pOut.append("</div>");
	}

	private static void renderAddSourceForm(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// scroll-anchor lot's generic mechanism (wizard.js) restores scroll to the nearest ancestor whose id
		// starts with "mdmf-" of the CLICKED button - this id is also what SOURCE_ONBOARD_USE's own dedicated
		// scroll override (wizard.js) targets explicitly, so a person who just onboarded a source lands back on
		// this exact form, not wherever the onboarding panel happened to be on the page.
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted mdmf-src2-waycard\" id=\"mdmf-add-source-form\">");
		pOut.append("<div class=\"mdmf-src2-way-head\"><span class=\"mdmf-src2-way-icon\">").append(WizardPage.ICON_BOOK).append("</span><span>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.add.title"))).append("</span></div><p class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.add.lead"))).append("</p>");
		WizardPage.renderCatalogSourceCombo(pOut, pModel, pLocale);
		pOut.append(WizardPage.fieldError(pModel, pLocale, "catalogSourceId"));

		if (!pModel.pickedCatalogSourceId.isEmpty())
		{
			// UX lot U2 (2026-10-13): a source with no schema (Salesforce) used to show "No schema scanned yet for this catalog source." - false and alarming
			// (it has no schemas to scan, and the catalog held its objects). The schema choice shows only when there is one to make.
			if (!pModel.pickedCatalogSourceSchemas.isEmpty())
			{
				pOut.append("<div class=\"mdmf-field\"><label>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.schemas"))).append("</label>");
				pOut.append("<div class=\"mdmf-radio-row\">");
				for (final String schema : pModel.pickedCatalogSourceSchemas)
				{
					final boolean checked = pModel.sourceDraft.schemas.contains(schema);
					pOut.append("<label><input type=\"checkbox\" class=\"mdmf-schema-check\" value=\"").append(WizardPage.esc(schema)).append("\"")
						.append(checked ? " checked=\"checked\"" : "").append("/> ").append(WizardPage.esc(schema)).append("</label>");
				}
				pOut.append("</div>");
				pOut.append(WizardPage.hidden("mdmfSchemas", WizardPage.join(pModel.sourceDraft.schemas))).append("</div>");
			}
			else
			{
				pOut.append(WizardPage.hidden("mdmfSchemas", ""));
			}

			// UX lot U2: the code is proposed from the catalog source's or the connection's name and stays editable; the trust rank is not asked
			// (a new application goes last; the arrows of "When two applications disagree" are the one place the order is set); the technical
			// connection id sits in a fold.
			pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfSourceSystem\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.sourceSystem")))
				.append("</label><input type=\"text\" id=\"mdmfSourceSystem\" name=\"mdmfSourceSystem\" value=\"").append(WizardPage.esc(pModel.sourceDraft.sourceSystem))
				.append("\" autocomplete=\"off\"/><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.sourceSystem.hint")))
				.append("</p>").append(WizardPage.fieldError(pModel, pLocale, "sourceSystem")).append("</div>");
			pOut.append("<details class=\"mdmf-disclosure\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.advanced"))).append("</summary>");
			pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfConnector\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.connector")))
				.append("</label><input type=\"text\" id=\"mdmfConnector\" name=\"mdmfConnector\" value=\"").append(WizardPage.esc(pModel.sourceDraft.connector))
				.append("\" autocomplete=\"off\"/><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.connector.hint")))
				.append("</p></div></details>");
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"SOURCE_ADD\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.add.button"))).append("</button></div>");
		}
		pOut.append("</div>");
	}

	/**
	 * The catalog-source picker of the "add an application" form: a searchable combobox (source-combobox lot,
	 * 2026-09-23 - the product owner, on the live wizard with two dozen catalog sources: "le composant de choix de la
	 * source est moche et ne propose pas de recherche"). One text input with {@code role="combobox"} filters a
	 * listbox of every catalog source on what is typed (name, platform, connector and id - the {@code
	 * data-search} attribute, lower-cased server-side so the script never re-derives it); each option shows the
	 * name, a platform chip and the connector, so the twenty-odd {@code (OTHER)} rows of the flat select are
	 * told apart at a glance. Picking an option (click, Enter) writes the hidden {@code mdmfCatalogSourceId}
	 * and clicks the {@code SOURCE_PICK_CATALOG} button - rendered but hidden, so the round trip that loads
	 * the source's schemas stays the ONE existing action path (wizard.js's [data-mdmf-action] handler, its
	 * progress toast included) rather than a second submit implementation. Keyboard: arrows move, Enter
	 * picks, Escape closes and restores the picked label. The "no match" text travels as a data attribute
	 * (same technique as the toggle panels: no JS i18n plumbing).
	 */
	private static void renderCatalogSourceCombo(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		String pickedLabel = "";
		for (final WizardSnapshot.CatalogSourceInfo catalogSource : pModel.snapshot.catalogSources)
		{
			if (catalogSource.id.equals(pModel.pickedCatalogSourceId))
			{
				pickedLabel = catalogSource.label;
			}
		}
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfCatalogSourceSearch\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.catalogSource")))
			.append("</label>");
		pOut.append("<div class=\"mdmf-combo\" id=\"mdmfCatalogSourceCombo\" data-mdmf-nomatch=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.catalogSource.noMatch"))).append("\">");
		pOut.append("<span class=\"mdmf-combo-icon\" aria-hidden=\"true\">&#128269;</span>");
		pOut.append("<input type=\"text\" id=\"mdmfCatalogSourceSearch\" role=\"combobox\" aria-autocomplete=\"list\" aria-expanded=\"false\"")
			.append(" aria-controls=\"mdmfCatalogSourceList\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.catalogSource.search"))).append("\" value=\"").append(WizardPage.esc(pickedLabel))
			.append("\" data-mdmf-picked-label=\"").append(WizardPage.esc(pickedLabel)).append("\"/>");
		pOut.append("<ul class=\"mdmf-combo-list\" id=\"mdmfCatalogSourceList\" role=\"listbox\" hidden=\"hidden\">");
		for (final WizardSnapshot.CatalogSourceInfo catalogSource : pModel.snapshot.catalogSources)
		{
			final boolean picked = catalogSource.id.equals(pModel.pickedCatalogSourceId);
			final String search = (catalogSource.label + " " + catalogSource.platform + " " + catalogSource.connector + " " + catalogSource.id).toLowerCase(Locale.ROOT);
			pOut.append("<li role=\"option\" class=\"mdmf-combo-option").append(picked ? " picked" : "").append("\" data-mdmf-id=\"").append(WizardPage.esc(catalogSource.id))
				.append("\" data-mdmf-label=\"").append(WizardPage.esc(catalogSource.label)).append("\" data-search=\"").append(WizardPage.esc(search))
				.append("\" aria-selected=\"").append(picked ? "true" : "false").append("\">");
			pOut.append("<span class=\"mdmf-combo-label\">").append(WizardPage.esc(catalogSource.label)).append("</span>");
			pOut.append("<span class=\"mdmf-combo-meta\"><span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardPage.orDash(catalogSource.platform)))
				.append("</span>");
			if (!catalogSource.connector.isEmpty())
			{
				pOut.append("<span class=\"mdmf-combo-connector\">").append(WizardPage.esc(catalogSource.connector)).append("</span>");
			}
			pOut.append("</span></li>");
		}
		pOut.append("<li class=\"mdmf-combo-empty\" hidden=\"hidden\"></li>");
		pOut.append("</ul></div>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.field.catalogSource.hint", Integer.valueOf(pModel.snapshot.catalogSources.size()))))
			.append("</p>");
		// the one real action path (see the Javadoc): clicked by the script when an option is picked
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" id=\"mdmfCatalogSourcePick\" data-mdmf-action=\"SOURCE_PICK_CATALOG\" hidden=\"hidden\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.pick"))).append("</button>");
		pOut.append("</div>");
	}

	/** {@code ebx-connectors}' own Connector table - a plain literal path (not this module's own dataset, so {@code FactoryLinks}' dataspace/dataset-key lookup does not apply here). Rewritten client-side from {@code ui/advanced/data/} to {@code ui/custom/<perspectiveKey>/data/} - same script as {@code ProjectExplorerUserService}, appended by {@code ProjectWizardUserService.script} (source-onboarding lot, 2026-09-23). */
	private static final String CONNECTORS_TABLE_URL = "/ebx-ui/ui/advanced/data/BConnectors/Connectors/root/Connector";

	// visual pass round 3 (coordinator): "no emoji anywhere" - inline stroke SVGs, the SAME line-art the
	// mockups themselves use for these exact three icons (docs/mockups/sources-landing/Main.dc.html), never
	// a Unicode glyph. currentColor picks up each icon's own wrapping .mdmf-src2-*-icon span color.
	private static final String ICON_UPLOAD = "<svg width=\"30\" height=\"30\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4\"></path>"
		+ "<polyline points=\"17 8 12 3 7 8\"></polyline><line x1=\"12\" y1=\"3\" x2=\"12\" y2=\"15\"></line></svg>";
	private static final String ICON_BOOK = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M4 19.5A2.5 2.5 0 0 1 6.5 17H20\"></path>"
		+ "<path d=\"M6.5 2H20v20H6.5A2.5 2.5 0 0 1 4 19.5v-15A2.5 2.5 0 0 1 6.5 2z\"></path></svg>";
	private static final String ICON_PLUG = "<svg width=\"16\" height=\"16\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><path d=\"M9 2v6M15 2v6M6 8h12v4a6 6 0 0 1-12 0z\"></path>"
		+ "<path d=\"M12 18v4\"></path></svg>";

	/**
	 * Coordinator review (2026-09-23): {@code .../root/Connector/<id>} is not a documented {@code ebx-ui}
	 * record route - {@code .../root/Connector?predicate=<./id='id' percent-encoded>} is the documented
	 * filtered-table pattern {@code ebx.RecordLineageReader#openUrl} already uses. The quoting/escaping is
	 * duplicated here rather than importing {@code CatalogReader} (this class stays EBX-free) - same rule
	 * ({@code '} doubled), same {@code URLEncoder.encode(predicate, "UTF-8")} over the whole predicate (no
	 * {@code %2F}-restore trick: unlike {@code FactoryLinks.cockpitUrl}, this link carries no
	 * {@code service=...&serviceMode=RECORD}, so the slash-next-to-a-service-parameter refusal that trick
	 * works around does not apply here).
	 */
	private static String connectorRecordUrl(final String pConnectorId)
	{
		final String predicate = "./id=" + WizardPage.quote(pConnectorId);
		try
		{
			return WizardPage.CONNECTORS_TABLE_URL + "?predicate=" + java.net.URLEncoder.encode(predicate, "UTF-8");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			return WizardPage.CONNECTORS_TABLE_URL; // UTF-8 is always supported - unreachable in practice
		}
	}

	/** SQL/xpath-style quoting, apostrophe doubled - same escaping {@code com.on.ebx.factory.ebx.CatalogReader#quote} already applies to every predicate this module builds elsewhere (not imported here: this class stays EBX-free). */
	private static String quote(final String pValue)
	{
		return "'" + String.valueOf(pValue).replace("'", "''") + "'";
	}

	/**
	 * The "Create a catalog source" panel (source-onboarding lot, 2026-09-23; task brief, revised after
	 * coordinator review the same day: {@code connections()} is a live {@code connectors_list} MCP round
	 * trip, so it must never fire on a plain render - the panel's own title is a real action
	 * ({@code SOURCE_ONBOARD_OPEN}) when {@link Model#onboardOpen} is false, and the ENTIRE body (combobox,
	 * checklist) is absent from the markup, not merely hidden, until that action has actually run). Once
	 * open: a connection combobox (same technique as {@link #renderCatalogSourceCombo}), a link to create a
	 * brand new connection in Connectors, a Close button, and, once a connection is picked, the six-step
	 * checklist ({@code SourceOnboarding.diagnose}) with its ONE next-action control.
	 */
	private static void renderSourceOnboardingPanel(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// visual pass follow-up (2026-09-24, coordinator): "Connect a live system" - mockup title/lead/icon,
		// the connection combobox shown OPEN in the card (no separate "Create a catalog source" button to
		// click first) plus the "+ New connection" link. The lazy-load gate behind SOURCE_ONBOARD_OPEN stays
		// (onboardConnections is only populated by that action/SOURCE_ONBOARD_REFRESH - loading the live
		// connector list on every single render would be a wasted MCP round trip on every unrelated postback)
		// - while !onboardOpen, an input-styled button visually stands in for the not-yet-loaded combobox and
		// triggers the same SOURCE_ONBOARD_OPEN action on first click/focus, so the card LOOKS open from the
		// start even though the list is fetched on demand.
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted mdmf-src2-waycard\" id=\"mdmf-source-onboarding\">");
		pOut.append("<div class=\"mdmf-src2-way-head\"><span class=\"mdmf-src2-way-icon mdmf-src2-way-icon-alt\">").append(WizardPage.ICON_PLUG).append("</span><span>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.title"))).append("</span></div><p class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.lead"))).append("</p>");
		if (!pModel.onboardOpen)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-field-search-btn\" data-mdmf-action=\"SOURCE_ONBOARD_OPEN\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.combo.search"))).append("</button>");
			pOut.append("<p class=\"mdmf-hint\"><a class=\"mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.CONNECTORS_TABLE_URL + "?service=@creation"))
				.append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.newConnection"))).append("</a></p>");
			pOut.append("</div>");
			return;
		}
		pOut.append("<div id=\"mdmf-onboard-body\">");
		if (pModel.onboardConnectionsError != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(pModel.onboardConnectionsError)).append("</div>");
		}
		WizardPage.renderOnboardConnectionCombo(pOut, pModel, pLocale);
		pOut.append("<p class=\"mdmf-hint\"><a class=\"mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.CONNECTORS_TABLE_URL + "?service=@creation")).append("\" target=\"_top\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.newConnection"))).append("</a></p>");

		if (!pModel.onboardConnectorId.isEmpty())
		{
			WizardPage.renderOnboardChecklist(pOut, pModel, pLocale);
		}
		pOut.append("</div></div>");
	}

	/** Same technique as {@link #renderCatalogSourceCombo} (search input + pre-rendered listbox, one hidden pick button) - see that method's own Javadoc for the keyboard/click behaviour, driven by {@code wizard.js}'s shared {@code initCombo}. Chips: the connection type, then a status chip (no catalog operation / not yet in the catalog / already in the catalog). */
	private static void renderOnboardConnectionCombo(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		String pickedLabel = "";
		for (final SourceOnboarding.ConnectionOption connection : pModel.onboardConnections)
		{
			if (connection.id.equals(pModel.onboardConnectorId))
			{
				pickedLabel = connection.label;
			}
		}
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfOnboardConnectorSearch\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.combo.label"))).append("</label>");
		pOut.append("<div class=\"mdmf-combo\" id=\"mdmfOnboardConnectorCombo\" data-mdmf-nomatch=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.combo.noMatch"))).append("\">");
		pOut.append("<span class=\"mdmf-combo-icon\" aria-hidden=\"true\">&#128269;</span>");
		pOut.append("<input type=\"text\" id=\"mdmfOnboardConnectorSearch\" role=\"combobox\" aria-autocomplete=\"list\" aria-expanded=\"false\"")
			.append(" aria-controls=\"mdmfOnboardConnectorList\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.combo.search"))).append("\" value=\"").append(WizardPage.esc(pickedLabel))
			.append("\" data-mdmf-picked-label=\"").append(WizardPage.esc(pickedLabel)).append("\"/>");
		pOut.append("<ul class=\"mdmf-combo-list\" id=\"mdmfOnboardConnectorList\" role=\"listbox\" hidden=\"hidden\">");
		for (final SourceOnboarding.ConnectionOption connection : pModel.onboardConnections)
		{
			final boolean picked = connection.id.equals(pModel.onboardConnectorId);
			final String search = (connection.label + " " + connection.type + " " + connection.id).toLowerCase(Locale.ROOT);
			pOut.append("<li role=\"option\" class=\"mdmf-combo-option").append(picked ? " picked" : "").append("\" data-mdmf-id=\"").append(WizardPage.esc(connection.id))
				.append("\" data-mdmf-label=\"").append(WizardPage.esc(connection.label)).append("\" data-search=\"").append(WizardPage.esc(search)).append("\" aria-selected=\"")
				.append(picked ? "true" : "false").append("\">");
			pOut.append("<span class=\"mdmf-combo-label\">").append(WizardPage.esc(connection.label)).append("</span>");
			pOut.append("<span class=\"mdmf-combo-meta\"><span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardPage.orDash(connection.type)))
				.append("</span>");
			final String statusKey = !connection.catalogCapable ? "wizard.onboard.chip.noCatalogOperation"
				: connection.catalogSourceId.isEmpty() ? "wizard.onboard.chip.notInCatalog" : "wizard.onboard.chip.inCatalog";
			final String statusClass = !connection.catalogCapable ? "mdmf-chip-muted" : connection.catalogSourceId.isEmpty() ? "mdmf-chip-target" : "mdmf-chip-accent";
			pOut.append("<span class=\"mdmf-chip ").append(statusClass).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, statusKey))).append("</span>");
			pOut.append("</span></li>");
		}
		pOut.append("<li class=\"mdmf-combo-empty\" hidden=\"hidden\"></li>");
		pOut.append("</ul></div>");
		pOut.append("<p class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardPage.plural(pLocale, pModel.onboardConnections.size(), "wizard.onboard.combo.hint.one", "wizard.onboard.combo.hint.other"))).append("</p>");
		// the one real action path, see renderCatalogSourceCombo's own Javadoc: clicked by the script when an option is picked
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" id=\"mdmfOnboardConnectorPick\" data-mdmf-action=\"SOURCE_ONBOARD_PICK\" hidden=\"hidden\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.combo.pick"))).append("</button>");
		pOut.append("</div>");
	}

	/**
	 * The checklist itself: one row per {@code SourceOnboarding.Step}, icon by state, its message; then the ONE
	 * next-action control the current {@code Diagnosis.nextAction} calls for, plus the always-available
	 * Refresh button. {@code CATALOG_APPLY}/{@code SCAN_RUN} render disabled with the admin-only reason when
	 * {@code !pModel.administrator} (task brief) - the diagnosis text itself stays visible to everyone.
	 */
	private static void renderOnboardChecklist(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-onboard-checklist\">");
		pOut.append("<p><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.selected", pModel.onboardConnectorDisplay))).append("</b></p>");
		if (pModel.onboardDiagnosis == null)
		{
			pOut.append("</div>");
			return;
		}
		WizardPage.renderOnboardChecklistRows(pOut, pLocale, pModel.onboardDiagnosis, pModel.onboardScanMessage);
		pOut.append("<div class=\"mdmf-actions\">");
		WizardPage.renderOnboardNextAction(pOut, pModel, pLocale);
		// sample-files lot (2026-09-23): "Do everything" next to the write button, whenever there IS one to
		// chain past (task brief) - never shown for OPEN_CONNECTION/PICK/NONE, where there is nothing left this
		// button could usefully chain.
		if (pModel.onboardDiagnosis.nextAction == SourceOnboarding.Action.CATALOG_APPLY || pModel.onboardDiagnosis.nextAction == SourceOnboarding.Action.SCAN_RUN)
		{
			WizardPage.renderOnboardWriteButton(pOut, pModel, pLocale, "SOURCE_ONBOARD_ALL", "wizard.onboard.all");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SOURCE_ONBOARD_REFRESH\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.refresh"))).append("</button>");
		pOut.append("</div></div>");
	}

	/**
	 * sample-files lot (2026-09-23): the six-step {@code <ol>} itself, factored out of
	 * {@link #renderOnboardChecklist} so {@link #renderSampleFilesPanel} can render the EXACT same markup for
	 * each file's own {@code SourceOnboarding.Diagnosis} in its report, without duplicating it (task brief:
	 * "the same checklist markup as the onboarding panel"). {@code pScanMessage} is null for a report row - the
	 * per-file import does not carry a separate scan-outcome string through to this rendering layer, only the
	 * diagnosis itself (a reasonable trim: the scan step's own message key already says whether it found data).
	 */
	private static void renderOnboardChecklistRows(final StringBuilder pOut, final Locale pLocale, final SourceOnboarding.Diagnosis pDiagnosis, final String pScanMessage)
	{
		pOut.append("<ol class=\"mdmf-onboard-steps\">");
		for (final SourceOnboarding.Step step : pDiagnosis.steps)
		{
			pOut.append("<li class=\"mdmf-onboard-step mdmf-onboard-step-").append(step.state.name().toLowerCase(Locale.ROOT)).append("\">");
			pOut.append("<span class=\"mdmf-onboard-icon\" aria-hidden=\"true\">").append(WizardPage.onboardStepIcon(step.state)).append("</span> ");
			// coordinator review (2026-09-23): every row (WAITING included) now leads with its own short step
			// name, so three "Waiting on a previous step" rows in a row still show WHICH steps those are - the
			// key is derivable from Step.key alone (wizard.onboard.step.<key>.name), never emitted by
			// SourceOnboarding itself (that class stays a pure state machine, no new message key surface).
			// mdmf-onboard-name (coordinator review, 2026-09-23, static-render fix): .mdmf-onboard-step is a flex
			// row, and without its own class the <b> was just another squeezed flex item - "Source du catalogue"
			// wrapped over three lines while the message text claimed the width. wizard.css keeps it nowrap and
			// intrinsic width (flex:0 0 auto).
			// fix lot (2026-09-25, bug 2 - live 943px-viewport repro, three cards 222px/176px content): the
			// " - " used to be a bare text node between </b> and the message span - a THIRD, unstyled flex item
			// that (harmlessly at a wide card, but not at 176px) still ate a sliver of the row's already-scarce
			// width. Moved inside <b> so the row is exactly two flex items (name, text) - simpler to reason
			// about, and never a stray anonymous flex item wizard.css would need to hide or size.
			pOut.append("<b class=\"mdmf-onboard-name\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.step." + step.key + ".name")))
				.append(" - </b>");
			// mdmf-onboard-text: fix lot (2026-09-25) - flex-basis:100% (wizard.css) means this ALWAYS wraps to
			// its own full-width line under the name, on every card width, never just "the remaining space
			// beside a wide label" (which, at the live 176px content width the coordinator measured, left the
			// message only ~21px wide - one/two letters per line). A predictable two-line row beats a row whose
			// single-line layout only works above some untested width threshold.
			pOut.append("<span class=\"mdmf-onboard-text\"><span>").append(WizardPage.esc(WizardMessages.get(pLocale, step.messageKey, step.args))).append("</span>");
			if ("scan".equals(step.key) && pScanMessage != null)
			{
				pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.scanMessage", pScanMessage))).append("</p>");
			}
			pOut.append("</span>");
			pOut.append("</li>");
		}
		pOut.append("</ol>");
	}

	private static String onboardStepIcon(final SourceOnboarding.State pState)
	{
		switch (pState)
		{
			case DONE:
				return "&#10003;";
			case TODO:
				return "&#8594;";
			case BLOCKED:
				return "&#10007;";
			default:
				return "&#183;";
		}
	}

	private static void renderOnboardNextAction(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final SourceOnboarding.Action action = pModel.onboardDiagnosis.nextAction;
		if (action == SourceOnboarding.Action.NONE)
		{
			return;
		}
		if (action == SourceOnboarding.Action.OPEN_CONNECTION)
		{
			pOut.append("<a class=\"mdmf-btn mdmf-btn-primary mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.connectorRecordUrl(pModel.onboardConnectorId)))
				.append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.openConnection"))).append("</a>");
			return;
		}
		if (action == SourceOnboarding.Action.CATALOG_APPLY)
		{
			WizardPage.renderOnboardWriteButton(pOut, pModel, pLocale, "SOURCE_ONBOARD_CATALOG", "wizard.onboard.catalog");
			return;
		}
		// fix lot (2026-09-25): no catalog operation, but a read-only listing operation exists - one click
		// creates the application AND queues the deduced-scan job (ebx.DeducedScanEntry#start); never gated on
		// pModel.administrator - same non-admin-gated write ebx.WizardData#addSource already is for the
		// official "add an application" form, and DEDUCED_SCAN_START (the existing follow-up action on an
		// already-added tableless application) is not admin-gated either.
		if (action == SourceOnboarding.Action.DEDUCED_SCAN)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"SOURCE_ONBOARD_DEDUCED\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.deducedScan"))).append("</button>");
			return;
		}
		if (action == SourceOnboarding.Action.SCAN_RUN)
		{
			WizardPage.renderOnboardWriteButton(pOut, pModel, pLocale, "SOURCE_ONBOARD_SCAN", "wizard.onboard.scan");
			return;
		}
		// PICK
		// UX lot U2 (2026-10-13): a system that is already an application of this project is not offered as a new one (measured live: "Use this
		// source" showed the add form again and a duplicate could be created) - the panel says so and offers its tables instead
		if (!pModel.onboardExistingSourceId.isEmpty())
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.src2.alreadyApplication", pModel.onboardExistingCode)))
				.append("</span>");
			final WizardSnapshot.SourceInfo existing = new WizardSnapshot.SourceInfo();
			existing.id = pModel.onboardExistingSourceId;
			WizardPage.renderFindTablesButton(pOut, pLocale, existing.id, true);
			WizardPage.renderScanAgainButton(pOut, pModel, pLocale, existing, false);
			return;
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"SOURCE_ONBOARD_USE\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.onboard.use"))).append("</button>");
	}

	/** {@code CATALOG_APPLY}/{@code SCAN_RUN}: real MCP writes, administrators only - same disabled-with-reason pattern as {@link #renderStagingGenerateLoad}. */
	private static void renderOnboardWriteButton(final StringBuilder pOut, final Model pModel, final Locale pLocale, final String pAction, final String pLabelKey)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"").append(pAction).append("\"")
			.append(pModel.designer ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, pLabelKey))).append("</button>");
		if (!pModel.designer)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.gate.adminOnly"))).append("</span>");
		}
	}

	/**
	 * sample-files lot (2026-09-23): "Sources from sample files" - the product owner's decision the same day ("Le
	 * fichier ne doit etre qu'un echantillon qui sert a la configuration"). A drop zone (pure client-side markup
	 * - {@code wizard.js} reads every dropped/browsed file with {@code FileReader}, computes its own connection
	 * id/application code client-side with a ported copy of {@link SampleFileRules}, and lists it in a table),
	 * one hidden textarea ({@code mdmfSampleFiles}) the script fills with the JSON array right before submit,
	 * and one submit button ({@code SOURCE_FILES_IMPORT}). Renders OPEN once a report exists (or the server
	 * guard notice fired) - collapsed otherwise, behind a plain {@code data-mdmf-toggle} (no server round trip
	 * needed just to open it, unlike the guided onboarding panel next to it: the drop zone needs no catalog
	 * data to be usable).
	 */
	private static void renderSampleFilesPanel(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		WizardPage.renderSampleFilesPanel(pOut, pModel, pLocale, false);
	}

	/**
	 * Visual pass (2026-09-24, coordinator: "a big dashed drop zone as the first way in"): {@code pBig} = true
	 * renders the SAME dropzone controls (every id/data-mdmf-* attribute wizard.js depends on is untouched)
	 * inside the {@code mdmf-src2-dropzone} card, ALWAYS open, with a plain icon+heading instead of the
	 * collapsible toggle button - the start screen's own prominent first way in (Main.dc.html). {@code false}
	 * (the pre-existing behavior, used once at least one application exists, inside the "Add more" disclosure)
	 * keeps the small collapsible panel with its own toggle header.
	 */
	private static void renderSampleFilesPanel(final StringBuilder pOut, final Model pModel, final Locale pLocale, final boolean pBig)
	{
		final boolean open = pBig || !pModel.sampleFilesReport.isEmpty() || pModel.sampleFilesError != null;
		pOut.append("<div class=\"mdmf-panel").append(pBig ? " mdmf-src2-dropzone" : " mdmf-panel-muted").append("\" id=\"mdmf-sample-files\">");
		if (pBig)
		{
			pOut.append("<div class=\"mdmf-src2-dropzone-row\"><span class=\"mdmf-src2-dropzone-icon\">").append(WizardPage.ICON_UPLOAD).append("</span><div class=\"mdmf-src2-dropzone-text\"><b>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.title"))).append("</b><div class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.lead"))).append("</div></div></div>");
		}
		else
		{
			pOut.append("<h3 class=\"mdmf-subtitle\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"mdmf-sample-files-body\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.title"))).append("</button></h3>");
		}
		pOut.append("<div id=\"mdmf-sample-files-body\"").append(open ? "" : " hidden=\"hidden\"").append(">");
		if (!pBig)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.lead"))).append("</p>");
		}
		if (pModel.sampleFilesError != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(pModel.sampleFilesError)).append("</div>");
		}

		final java.util.List<Integer> takenRanks = new java.util.ArrayList<Integer>();
		for (final WizardSnapshot.SourceInfo existing : pModel.snapshot.sources)
		{
			takenRanks.add(Integer.valueOf(existing.trustRank));
		}
		pOut.append("<div class=\"mdmf-sample-dropzone\" id=\"mdmfSampleDropzone\" tabindex=\"0\" role=\"button\" data-mdmf-taken-ranks=\"").append(SampleFileRules.takenRanksText(takenRanks)).append("\">");
		pOut.append("<input type=\"file\" id=\"mdmfSampleFileInput\" multiple accept=\".csv,.tsv,.txt,.json,.ndjson,.jsonl\" hidden=\"hidden\"/>");
		pOut.append("<p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.dropzone"))).append("</p>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" id=\"mdmfSampleBrowse\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.browse"))).append("</button>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.accept"))).append("</p>");
		pOut.append("</div>");

		// landing test fixes (2026-10-17): the table scrolls inside its own box instead of being cut by the panel ("Trust rank" and the remove button were out of sight)
		pOut.append("<div class=\"mdmf-scroll-x\" id=\"mdmfSampleTableWrap\"><table class=\"mdmf-table mdmf-sample-table\" id=\"mdmfSampleTable\" hidden=\"hidden\" data-mdmf-remove-label=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.table.remove"))).append("\"><thead><tr>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.table.name"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.table.size"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.table.kind"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.table.code"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.table.trust"))).append("</th>");
		pOut.append("<th></th></tr></thead><tbody id=\"mdmfSampleTableBody\"></tbody></table></div>");
		// the drop zone's own client-only validation text (extension refused, per-file/total cap, duplicate
		// code) travels as data attributes, UNFORMATTED (WizardMessages.get with no args returns the raw
		// pattern - its own contract) - same technique as the combobox's data-mdmf-nomatch, never a second i18n
		// plumbing path for JS. Every {0}/{1} placeholder is filled by wizard.js with a plain string replace,
		// against its own ported copy of SampleFileRules.RAW_BYTES_CAP (documented there) - never re-derived
		// from a server round trip, the same "client validates before ever submitting" split the task brief
		// itself describes for this panel.
		pOut.append("<div id=\"mdmfSampleErrors\" class=\"mdmf-notice mdmf-notice-warn\" hidden=\"hidden\"").append(" data-mdmf-msg-ext=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.error.extension"))).append("\" data-mdmf-msg-size=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.error.size"))).append("\" data-mdmf-msg-total=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.error.total"))).append("\" data-mdmf-msg-duplicate=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.error.duplicate"))).append("\"></div>");

		pOut.append(WizardPage.hiddenTextarea("mdmfSampleFiles"));
		// visual pass follow-up (2026-09-24, coordinator): "Create the sources from these files" appears only
		// once files are dropped - hidden by default (the sample table starts empty on every render, dropped
		// files are never persisted server-side between postbacks), wizard.js un-hides it next to the table
		// itself as soon as a row is added, hides it again once the last row is removed.
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" id=\"mdmfSampleSubmit\" data-mdmf-action=\"SOURCE_FILES_IMPORT\" hidden=\"hidden\"")
			.append(pModel.designer ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.submit")))
			.append("</button>");
		if (!pModel.designer)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.gate.adminOnly"))).append("</span>");
		}
		pOut.append("</div>");

		if (!pModel.sampleFilesReport.isEmpty())
		{
			pOut.append("<div class=\"mdmf-sample-report\">");
			for (final SampleFileRules.ReportRow row : pModel.sampleFilesReport)
			{
				WizardPage.renderSampleFileReportRow(pOut, pLocale, row);
			}
			pOut.append("</div>");
		}
		pOut.append("</div></div>");
	}

	private static void renderSampleFileReportRow(final StringBuilder pOut, final Locale pLocale, final SampleFileRules.ReportRow pRow)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-sample-report-row\">");
		pOut.append("<h4>").append(WizardPage.esc(pRow.name)).append("</h4>");
		if (pRow.queued)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.report.queued"))).append("</p>");
		}
		if (pRow.diagnosis != null)
		{
			WizardPage.renderOnboardChecklistRows(pOut, pLocale, pRow.diagnosis, null);
		}
		if (pRow.applicationAdded)
		{
			pOut.append("<p class=\"mdmf-notice mdmf-notice-ok\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.report.applicationAdded", pRow.sourceId))).append("</p>");
		}
		else if (!pRow.existingApplication.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.report.applicationExists", pRow.existingApplication))).append("</p>");
		}
		if (pRow.error != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step2.sampleFiles.report.error", pRow.error))).append("</div>");
		}
		pOut.append("</div>");
	}

	private static String hiddenTextarea(final String pId)
	{
		return "<textarea id=\"" + pId + "\" name=\"" + pId + "\" style=\"display:none\"></textarea>";
	}

	/**
	 * Live fix (2026-09-24, project A): the Review card's title, and the trust-order list's own name, used
	 * to show {@code ProjectSource.sourceSystem} raw - measured live on project A: {@code
	 * PERSONNES_SALESFORCE}/{@code PERSONNES_SAP_SAMPLE}, an all-caps auto-suggested application CODE
	 * (legitimate, within the 40-char/pattern limit - see docs/SOURCES-LANDING-SOURCES.md "Live fixes" for
	 * what the code field genuinely holds), never meant to double as the readable name. Prefers the catalog
	 * source's own label (already falls back to the catalog id when the catalog has no real label, or the
	 * source was never linked to one), then the connector's own label (when there is no catalog link at
	 * all), then - last resort - the {@code ProjectSource} row's own id.
	 */
	private static String displayName(final WizardSnapshot.SourceInfo pSource)
	{
		if (!pSource.catalogSourceId.isEmpty())
		{
			return pSource.catalogSourceLabel;
		}
		if (!pSource.connectorLabel.isEmpty())
		{
			return pSource.connectorLabel;
		}
		return pSource.id;
	}

	// --------------------------------------------------------------------------------------------------- step 3: landing

	private static void renderStep3(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-panel mdmf-wiz-panel\" data-step=\"3\"").append(WizardPage.hiddenAttr(pModel, 3)).append(">");
		// landing-walkthrough display fix (2026-10-17): while the walkthrough is shown the page head carries ITS lead only; the old one-click lead stays for the run views
		final boolean walkShown = !pModel.snapshot.sources.isEmpty()
			&& LandingWalk.shown(pModel.walkReview, pModel.landingRunRunning, WizardPage.landingRunDone(pModel), !pModel.landingRunProgress.tables().isEmpty());
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step3.title"), WizardMessages.get(pLocale, walkShown ? "wizard.walk.lead" : "wizard.step3.lead"));
		WizardPage.renderCatalogRestricted(pOut, pModel, pLocale);
		if (pModel.snapshot.sources.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.noSource"))).append("</div>");
			WizardPage.renderStepReset(pOut, pModel, pLocale, "LANDING");
			pOut.append("</section>");
			return;
		}

		// landing-walkthrough lot (2026-10-16): before any run exists (or when the person re-opens it) step 3 is a walkthrough, one application at a time, the
		// launch at the end; the run's own views (running, done, partial failure) are unchanged
		final boolean walk = LandingWalk.shown(pModel.walkReview, pModel.landingRunRunning, WizardPage.landingRunDone(pModel), !pModel.landingRunProgress.tables().isEmpty());
		final boolean walkLaunchPage = walk && LandingWalk.LAUNCH.equals(LandingWalk.resolvePage(pModel.snapshot, pModel.walkPage));
		if (walk)
		{
			if (pModel.walkReview)
			{
				pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-quiet\" data-mdmf-action=\"LANDING_WALK_CLOSE\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.review.close"))).append("</button></div>");
			}
			LandingWalkPage.render(pOut, pModel, pLocale);
		}
		else
		{
			// sources+landing redesign, lot LANDING (2026-09-24): the one-click run is now the primary path - see
			// renderLandingRun's own Javadoc. Every pre-existing per-source control (plan/generate/key picker/
			// relations/preview) moves under the "Advanced" disclosure below, kept fully working.
			WizardPage.renderLandingRun(pOut, pModel, pLocale);
		}
		if (walk && !walkLaunchPage)
		{
			WizardPage.renderStepReset(pOut, pModel, pLocale, "LANDING");
			pOut.append("</section>");
			return;
		}

		pOut.append("<details class=\"mdmf-disclosure\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.advanced"))).append("</summary>");
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			WizardPage.renderLandingSource(pOut, pModel, pLocale, source);
		}
		pOut.append("</details>");
		WizardPage.renderStepReset(pOut, pModel, pLocale, "LANDING");
		pOut.append("</section>");
	}

	/**
	 * Sources+landing redesign, lot LANDING (2026-09-24, docs/mockups/sources-landing/Landing.dc.html +
	 * LandingRunning.dc.html + LandingReview.dc.html): the one-click "Prepare the landing" flow, in front of
	 * the "Advanced" per-source controls {@link #renderStep3} still renders right after this - same three-
	 * state shape as {@code renderProposalRun} (step 4's own one-click run, staging-proposal-run lot): (1) no
	 * run yet - a plain-language intro and the primary button with its "use the language model" checkbox;
	 * (2) {@link Model#landingRunRunning} - the table x stage grid, an overall bar, a "table(s) wait for you"
	 * banner with a "Choose the key" button per waiting table (opening {@link #renderLandingKeyChooser} right
	 * under that table's row) and a "Stop the run" button; (3) a run exists, is not running and no table is
	 * left waiting - a short "done" summary and "Continue to the staging" (the wizard's own next-step button,
	 * already rendered elsewhere - this screen only confirms landing is ready).
	 * <p>
	 * <b>Deviation from the mockup</b>, documented in {@code docs/SOURCES-LANDING-LANDING.md}: the mockup's
	 * candidate-card key chooser (live, client-side uniqueness recomputed on the sample as a person adds/
	 * removes columns, "Several columns together" builder) was simplified to reuse the EXISTING manual key-
	 * picker checkboxes ({@link #renderLandingTableRow}'s own {@code .mdmf-key-check} markup and {@code
	 * wizard.js}'s existing collection logic) plus the study's own evidence table - still fully composite-
	 * capable (any combination of checkboxes), but without the live in-browser recompute the mockup shows;
	 * building that recompute (embedding the sample as JSON and duplicating {@code KeyEvidence}'s uniqueness
	 * logic in {@code wizard.js}) was judged out of scope for this lot's remaining time.
	 */
	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 6, measured live on project Personnes): whether step 3
	 * should show the "done" view - previously {@code Model.landingRunExists} alone (a {@code LandingRun}
	 * record from the one-click flow), which reads false, and shows the plain intro again, for a landing that
	 * was loaded BEFORE that one-click run existed (or loaded some other way - a re-run after a step reset,
	 * for instance): the tables themselves are genuinely {@code LOADED} (the Advanced table view already shows
	 * "Change the key..." for them), there is simply no {@code LandingRun} row to read. Decide from the data
	 * too - a selected table whose own {@code status} is {@code LOADED} is exactly what {@link
	 * WizardRules#suggestedStep} already uses to decide a project has moved past step 3 ({@code
	 * anyTableLoaded}), so this mirrors that same rule rather than inventing a second one. {@link
	 * #renderLandingRunDone} itself only ever reads {@code Model#snapshot} (every table's own {@code
	 * lastLoadRows}/{@code keyColumns}/{@code keyOrigin}), never {@code landingRunExists} - it already renders
	 * correctly whichever way this method decides to show it.
	 */
	/**
	 * <b>Bug fixed live 2026-09-24 (project Vague1):</b> this used to be true as soon as a SINGLE ticked table
	 * was {@code LOADED} ({@code landingRunExists}, or "any table loaded") - so a run that loaded 2 of 6 ticked
	 * tables (the other 4 still {@code WAITING}/not yet reached) already showed the "done" panel, which then
	 * summed EVERY selected table's {@code lastLoadRows} regardless of whether it was actually loaded ("6 tables
	 * landed, 345 rows loaded in total" while 4 of the 6 had never loaded). Reuses {@link WizardRules
	 * #allTickedTablesLoaded} - the SAME stricter prerequisite the step rail already gates Staging's
	 * reachability on (step-gating lot, commit 5efdb86) - rather than a second, looser notion of "landing done":
	 * the done panel and the rail must agree. When it is false, {@link #renderLandingRun} falls back to the
	 * existing {@link #renderLandingRunning} grid/banner view instead (reusing that state, not inventing one),
	 * as long as there is a run to show progress from.
	 */
	private static boolean landingRunDone(final Model pModel)
	{
		return WizardRules.allTickedTablesLoaded(pModel.snapshot);
	}

	/** Landing-walkthrough lot (2026-10-16): the quiet link under a run that exists - re-opens the walkthrough, the validations kept. */
	private static void renderWalkReviewLink(final StringBuilder pOut, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-linkbtn\" data-mdmf-action=\"LANDING_WALK_REVIEW\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.walk.review"))).append("</button></div>");
	}

	private static void renderLandingRun(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.landingRunRunning)
		{
			WizardPage.renderLandingRunning(pOut, pModel, pLocale);
			return;
		}
		if (WizardPage.landingRunDone(pModel))
		{
			WizardPage.renderLandingRunDone(pOut, pModel, pLocale);
			WizardPage.renderLandingRunForm(pOut, pModel, pLocale, true);
			WizardPage.renderWalkReviewLink(pOut, pLocale);
		}
		// bug fixed live 2026-09-24 (project Vague1, bug 3): a run that is no longer RUNNING but left some
		// ticked table short of LOADED (closed with a table still WAITING, or reopened after a partial load
		// some other way) must not fall back to the plain "start" intro, which would hide the grid/per-table
		// progress a person still needs to see and act on - reuse the SAME running grid view (LandingRunProgress
		// already distinguishes WAITING/DONE/FAILED/TODO per cell), just without the poll attribute this time.
		else if (!pModel.landingRunProgress.tables().isEmpty())
		{
			WizardPage.renderLandingRunning(pOut, pModel, pLocale);
			// UX lot D (2026-10-14): a run that lost some tables says which, why, and offers to redo only those; when the staging step is open anyway
			// (the gap is explained by that failed run) the way on is there, with a line that says what the staging will leave out
			pOut.append(PartialFailuresView.landingBlock(pModel, pLocale));
			final String landingWarning = PartialFailuresView.landingWarning(pModel, pLocale);
			if (!landingWarning.isEmpty() && pModel.stepStates[WizardRules.STEP_STAGING].reachable)
			{
				pOut.append("<div class=\"mdmf-panel mdmf-landing-cta\"><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.done.continueLead")))
					.append("</p><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-goto-step=\"").append(WizardRules.STEP_STAGING).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.done.continue"))).append("</button>").append(landingWarning).append("</div>");
			}
			// a closed run that left a table unloaded needs a way out other than "Clean": without this button the
			// page showed the old grid and nothing to start a new run with (project ret, 2026-09-27)
			WizardPage.renderLandingRunForm(pOut, pModel, pLocale, true);
			WizardPage.renderWalkReviewLink(pOut, pLocale);
		}
		else
		{
			// coordinator follow-up (2026-09-24): a local two-column layout (main + sidebar), scoped to this
			// intro screen only - never the shared wizard shell (.mdmf-wiz-main), per the common brief's
			// "only step-3 methods" - plus the mockup's own flow picture (application table -> key -> landing
			// table) in front of the four numbered cards.
			pOut.append("<div class=\"mdmf-landing-layout\"><div class=\"mdmf-landing-main\">");
			WizardPage.renderLandingFlowPicture(pOut, pModel, pLocale);
			pOut.append("<div class=\"mdmf-panel\"><div class=\"mdmf-landing-cards\">");
			WizardPage.renderLandingIntroCard(pOut, pLocale, "1", "wizard.step3.run.intro.item.key");
			WizardPage.renderLandingIntroCard(pOut, pLocale, "2", "wizard.step3.run.intro.item.table");
			WizardPage.renderLandingIntroCard(pOut, pLocale, "3", "wizard.step3.run.intro.item.flow");
			WizardPage.renderLandingIntroCard(pOut, pLocale, "4", "wizard.step3.run.intro.item.load");
			pOut.append("</div></div>");
			WizardPage.renderLandingRunForm(pOut, pModel, pLocale, false);
			pOut.append("</div>"); // main
			pOut.append("<div class=\"mdmf-landing-sidebar\"><div class=\"mdmf-panel\"><div class=\"eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.intro.glossaryTitle"))).append("</div><p class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.intro.glossary"))).append("</p></div></div>");
			pOut.append("</div>"); // layout
		}
	}

	/**
	 * Coordinator follow-up (2026-09-24, Landing.dc.html's top row): one row per selected table - the
	 * application table card, an arrow carrying its key (chips) or a "key to find" warning chip, then the
	 * landing table card with the four technical columns every landing table always gets.
	 */
	private static void renderLandingFlowPicture(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		boolean any = false;
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				if (!any)
				{
					pOut.append("<div class=\"mdmf-landing-flowpic\">");
					any = true;
				}
				pOut.append("<div class=\"mdmf-landing-flow-row\">");
				pOut.append("<div class=\"mdmf-landing-flow-card\"><b>").append(WizardPage.esc(source.sourceSystem)).append("</b><span class=\"mdmf-hint\">")
					.append(WizardPage.esc(table.tableName)).append("</span></div>");
				pOut.append("<div class=\"mdmf-landing-flow-arrow\"><span>→</span>");
				if (table.keyColumns.isEmpty())
				{
					pOut.append("<span class=\"mdmf-chip mdmf-chip-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.apps.keyToFind")))
						.append("</span>");
				}
				else
				{
					WizardPage.renderKeyChips(pOut, pLocale, table.keyColumns, null);
				}
				pOut.append("</div>");
				pOut.append("<div class=\"mdmf-landing-flow-card mdmf-landing-flow-card-target\"><b>").append(WizardPage.esc(table.tableName)).append("</b>")
					.append("<div class=\"mdmf-landing-flow-techchips\">");
				for (final String tech : new String[] { "_sourceId", "_loadId", "_receivedAt", "_rowHash" })
				{
					pOut.append("<span class=\"mdmf-chip mdmf-chip-target mono\">").append(tech).append("</span>");
				}
				pOut.append("</div></div>");
				pOut.append("</div>");
			}
		}
		if (any)
		{
			pOut.append("</div>");
		}
	}

	/** One numbered card of the intro's "what the factory does" row - visual parity pass (coordinator, 2026-09-24, Landing.dc.html's four cards) over the plain bullet list this replaced. */
	private static void renderLandingIntroCard(final StringBuilder pOut, final Locale pLocale, final String pNumber, final String pMessageKey)
	{
		pOut.append("<div class=\"mdmf-landing-card\"><span class=\"mdmf-landing-card-n\">").append(pNumber).append("</span><p>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pMessageKey))).append("</p></div>");
	}

	private static void renderLandingRunForm(final StringBuilder pOut, final Model pModel, final Locale pLocale, final boolean pRunAgain)
	{
		final WizardRules.ActionGates gates = WizardRules.actionGates(pModel.snapshot, pModel.designer);
		// visual parity pass (coordinator, 2026-09-24): the primary run - never "run again" - sits in the same
		// accent-tinted "big button" card the mockup gives its main call to action (Landing.dc.html); a re-run
		// stays the smaller, muted treatment it already had.
		pOut.append("<div class=\"mdmf-panel").append(pRunAgain ? " mdmf-panel-muted" : " mdmf-landing-cta").append("\"><div class=\"mdmf-actions\">");
		pOut.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"mdmfLandingRunUseLlmCheckbox\"").append(pModel.landingRunUseLlm ? " checked=\"checked\"" : "")
			.append("/> ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.checkbox.llm"))).append("</label>");
		pOut.append("</div>");
		if (pRunAgain)
		{
			// volume wave 2, lot V2-E: a re-run reads only the rows changed since each table's last load (tables with a change column);
			// this ignores that watermark and reloads every row - what a person wants after a source rebuilt from a backup, or to pick up a
			// row whose change column the source never touched
			pOut.append("<div class=\"mdmf-actions\"><label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"mdmfLandingRunFullRefreshCheckbox\"/> ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.checkbox.fullRefresh"))).append("</label></div>");
		}
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn").append(pRunAgain ? "" : " mdmf-btn-primary mdmf-btn-big")
			.append("\" data-mdmf-action=\"LANDING_RUN_START\" data-mdmf-landing-run-checkboxes=\"1\"")
			.append(gates.generateLanding.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pRunAgain ? "wizard.step3.run.button.again" : "wizard.step3.run.button"))).append("</button>");
		if (!gates.generateLanding.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, gates.generateLanding.reasonKey))).append("</span>");
		}
		pOut.append("</div><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.llm.disclosure"))).append("</p></div>");
	}

	/** Live-progress lot (2026-09-26): the message keys the live panel's script needs, passed as raw patterns ({0} replaced by the script). */
	private static final String[] LANDING_LIVE_LABELS = { "wizard.step3.run.live.now", "wizard.step3.run.live.nowIdle", "wizard.step3.run.live.nowPaused",
		"wizard.step3.run.live.left", "wizard.step3.run.live.leftSoon", "wizard.step3.run.live.leftUnknown", "wizard.step3.run.live.beat",
		"wizard.step3.run.live.beatStale", "wizard.step3.run.live.running", "wizard.step3.run.live.usually", "wizard.step3.run.live.took",
		"wizard.step3.run.live.after.KEY", "wizard.step3.run.live.after.TABLE", "wizard.step3.run.live.after.FLOW", "wizard.step3.run.live.after.LOAD",
		"wizard.step3.run.live.journalEmpty", "wizard.step3.run.progressText", "wizard.step3.run.state.TODO", "wizard.step3.run.state.RUNNING",
		"wizard.step3.run.state.DONE", "wizard.step3.run.state.FAILED", "wizard.step3.run.state.SKIPPED", "wizard.step3.run.state.WAITING",
		"wizard.step3.run.state.WAITING.stage" };

	/**
	 * Live-progress lot (2026-09-26, docs/mockups/landing-live/, a delta of LandingRunning.dc.html): ONE live
	 * header (what runs now, time spent, time left, one Stop), a bar that also moves inside a stage, a heartbeat
	 * line, the per-table grid whose running cells say what they do and for how long, and the run's journal.
	 * Before this lot the grid only changed when the job ENDED (the page is re-rendered once, at the end - see
	 * wizard.js' jobs banner) and a stage's RUNNING state was never written, so a person saw every cell "To do"
	 * for minutes. Now {@code wizard.js} (block "landing live panel") repaints this panel from the progress JSON
	 * the jobs poll already returns every 3 s, and ticks its timers every second in between; what it paints is
	 * the same markup this method writes ({@link #renderLandingCellBody}), so both must change together.
	 */
	private static void renderLandingRunning(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final LandingRunProgress progress = pModel.landingRunProgress;
		final boolean queued = progress.tables().isEmpty();
		final boolean live = pModel.landingRunRunning && !progress.isOver();
		final boolean paused = pModel.landingRunRunning && !queued && progress.isOver() && progress.hasWaiting();
		final com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
		final com.fasterxml.jackson.databind.node.ObjectNode labels = mapper.createObjectNode();
		for (final String key : WizardPage.LANDING_LIVE_LABELS)
		{
			labels.put(key.substring("wizard.step3.run.".length()), WizardMessages.get(pLocale, key));
		}
		final com.fasterxml.jackson.databind.node.ObjectNode typical = mapper.createObjectNode();
		for (final java.util.Map.Entry<String, Long> entry : pModel.landingRunTypicalMs.entrySet())
		{
			typical.put(entry.getKey(), entry.getValue().longValue());
		}
		pOut.append("<div class=\"mdmf-panel mdmf-lr\" id=\"mdmfLandingLive\" data-mdmf-lr-live=\"").append(live).append("\" data-mdmf-lr-server-now=\"")
			.append(pModel.nowMs).append("\" data-mdmf-lr-started=\"").append(progress.startedAt()).append("\" data-mdmf-lr-updated=\"").append(progress.updatedAt())
			.append("\" data-mdmf-lr-typical=\"").append(WizardPage.esc(typical.toString())).append("\" data-mdmf-lr-labels=\"").append(WizardPage.esc(labels.toString()))
			.append("\">");
		if (pModel.landingRunStale)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.stale"))).append("</div>");
		}

		// (A) one live header
		final LandingRunProgress.Cell current = progress.currentCell();
		final LandingRunProgress.TableProgress currentTable = current == null ? null : progress.tableOf(current);
		final String nowText = queued ? WizardMessages.get(pLocale, "wizard.step3.run.live.nowQueued")
			: paused ? WizardMessages.get(pLocale, "wizard.step3.run.live.nowPaused")
				: current == null ? WizardMessages.get(pLocale, "wizard.step3.run.live.nowIdle")
					: currentTable.tableName + " \u00B7 " + (current.activity != null ? RunPanelText.display(current.activity, pLocale) : WizardMessages.get(pLocale, "wizard.step3.run.state.RUNNING"));
		final LandingRunProgress.Estimate estimate = progress.estimate(pModel.landingRunTypicalMs, pModel.nowMs);
		pOut.append("<div class=\"mdmf-lr-head\">");
		final boolean ended = !pModel.landingRunRunning;
		if (!ended)
		{
			pOut.append("<span class=\"mdmf-lr-spinner").append(live || queued ? "" : " mdmf-lr-spinner-off").append("\" aria-hidden=\"true\"></span>");
		}
		pOut.append("<div class=\"mdmf-lr-headtext\"><div class=\"mdmf-lr-title\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale,
				ended ? "wizard.step3.run.live.titleEnded"
					: queued ? "wizard.step3.run.live.titleQueued" : paused ? "wizard.step3.run.live.titlePaused" : "wizard.step3.run.live.title")))
			.append("</div>");
		if (!ended)
		{
			pOut.append("<div class=\"mdmf-lr-now\" id=\"mdmfLrNow\" aria-live=\"polite\"><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.now")))
				.append("</b> <span>").append(WizardPage.esc(nowText)).append("</span></div>");
		}
		pOut.append("</div>");
		pOut.append("<div class=\"mdmf-lr-clock\"><div class=\"mdmf-lr-elapsed\" id=\"mdmfLrElapsed\">")
			.append(progress.startedAt() > 0L ? WizardPage.clockText(pModel.nowMs - progress.startedAt()) : "").append("</div><div class=\"mdmf-lr-left\" id=\"mdmfLrLeft\">")
			.append(WizardPage.esc(live ? WizardPage.leftText(pLocale, estimate.remainingMs) : "")).append("</div></div>");
		if (!ended)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-danger\" data-mdmf-action=\"LANDING_RUN_STOP\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.stop"))).append("</button>");
		}
		pOut.append("</div>");

		// (C) a bar that moves inside a stage, and the heartbeat
		final int total = progress.totalStageCount();
		final int done = progress.doneStageCount();
		pOut.append("<div class=\"mdmf-lr-barrow\"><div class=\"mdmf-proposal-bar\"><div class=\"mdmf-proposal-bar-fill").append(live ? " mdmf-lr-fill-live" : "")
			.append("\" id=\"mdmfLrFill\" style=\"width:").append(estimate.percent).append("%\"></div></div><span class=\"mdmf-lr-stages\" id=\"mdmfLrStages\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.progressText", Integer.valueOf(done), Integer.valueOf(total)))).append("</span></div>");
		if (live)
		{
			pOut.append("<div class=\"mdmf-lr-beat\" id=\"mdmfLrBeat\"><span class=\"mdmf-lr-dot\"></span><span>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.beat")
					.replace("{0}", WizardPage.durationText(Math.max(0L, pModel.nowMs - progress.updatedAt())))))
				.append("</span></div>");
		}

		if (!pModel.landingRunWaitingTableIds.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">")
				.append(WizardPage.esc(WizardPage.plural(pLocale, pModel.landingRunWaitingTableIds.size(), "wizard.step3.run.waitingBanner.one",
					"wizard.step3.run.waitingBanner.other")));
			for (final String waitingId : pModel.landingRunWaitingTableIds)
			{
				pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"LANDING_KEY_CHOOSE\" data-mdmf-table-id=\"")
					.append(WizardPage.esc(waitingId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooseKey")))
					.append("</button>");
			}
			pOut.append("</div>");
		}
		if (pModel.landingKeyAppliedMessage != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(WizardPage.esc(pModel.landingKeyAppliedMessage)).append("</div>");
		}

		// (B, E) the grid
		if (!queued)
		{
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-lr-grid\"><thead><tr><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.table"))).append("</th>");
			for (final String stage : LandingRunProgress.STAGES)
			{
				pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.stage." + stage))).append("</th>");
			}
			pOut.append("</tr></thead><tbody>");
			for (final LandingRunProgress.TableProgress table : progress.tables())
			{
				pOut.append("<tr><td><b>").append(WizardPage.esc(table.tableName)).append("</b><br/><span class=\"mdmf-hint\">").append(WizardPage.esc(table.sourceSystem))
					.append("</span></td>");
				for (final LandingRunProgress.Cell cell : table.cells())
				{
					final boolean passiveWait = LandingRunProgress.WAITING.equals(cell.state) && !LandingRunProgress.KEY.equals(cell.stage);
					pOut.append("<td class=\"mdmf-landing-cell mdmf-landing-cell-").append(passiveWait ? "skipped" : cell.state.toLowerCase(Locale.ROOT))
						.append("\" data-mdmf-lr-cell=\"").append(WizardPage.esc(table.id + "|" + cell.stage)).append("\" data-mdmf-lr-state=\"").append(cell.state)
						.append("\">");
					WizardPage.renderLandingCellBody(pOut, pModel, pLocale, table, cell);
					pOut.append("</td>");
				}
				pOut.append("</tr>");
				if (table.id.equals(pModel.landingKeyChooserTableId) && pModel.landingKeyChooser != null)
				{
					WizardPage.renderLandingKeyChooser(pOut, pModel, pLocale, table);
				}
			}
			pOut.append("</tbody></table></div>");
		}

		// (D) the journal
		pOut.append("<div class=\"mdmf-lr-journal\"><div class=\"mdmf-lr-journal-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.journal")))
			.append("</div><ul id=\"mdmfLrJournal\">");
		final List<LandingRunProgress.Event> events = progress.events();
		if (events.isEmpty())
		{
			pOut.append("<li class=\"mdmf-lr-journal-empty\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.journalEmpty"))).append("</li>");
		}
		final java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT);
		for (int i = 0; i < events.size() && i < 6; i++)
		{
			final LandingRunProgress.Event event = events.get(i);
			pOut.append("<li").append(i == 0 && live ? " class=\"mdmf-lr-journal-live\"" : "").append("><time>").append(time.format(new java.util.Date(event.at)))
				.append("</time><span>").append(WizardPage.esc(RunPanelText.event(event.text, pLocale))).append("</span></li>");
		}
		pOut.append("</ul></div>");
		if (!ended)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.running.leave"))).append("</p>");
		}
		pOut.append("</div>");
	}

	/**
	 * One cell of the live grid: state circle, a label (a running stage says what it does; a stage still to do
	 * says what it waits for) and a second line (time running or spent, the summary, or the key chips). Mirrored
	 * by {@code wizard.js}' {@code cellHtml} - change both together.
	 */
	private static void renderLandingCellBody(final StringBuilder pOut, final Model pModel, final Locale pLocale, final LandingRunProgress.TableProgress pTable,
		final LandingRunProgress.Cell pCell)
	{
		final boolean passiveWait = LandingRunProgress.WAITING.equals(pCell.state) && !LandingRunProgress.KEY.equals(pCell.stage);
		WizardPage.renderStateCircle(pOut, pCell.state, passiveWait);
		final String label;
		if (LandingRunProgress.RUNNING.equals(pCell.state) && pCell.activity != null)
		{
			label = RunPanelText.display(pCell.activity, pLocale);
		}
		else if (LandingRunProgress.TODO.equals(pCell.state))
		{
			label = WizardMessages.get(pLocale, "wizard.step3.run.live.after." + pCell.stage);
		}
		else
		{
			label = WizardMessages.get(pLocale, passiveWait ? "wizard.step3.run.state.WAITING.stage" : "wizard.step3.run.state." + pCell.state);
		}
		pOut.append("<span class=\"mdmf-lr-label\">").append(WizardPage.esc(label)).append("</span>");
		if (LandingRunProgress.RUNNING.equals(pCell.state))
		{
			final Long typical = pModel.landingRunTypicalMs.get(pCell.stage);
			pOut.append("<br/><span class=\"mdmf-hint mdmf-lr-timer\" data-mdmf-lr-since=\"").append(pCell.startedAt).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.running").replace("{0}", WizardPage.durationText(pCell.elapsedMs(pModel.nowMs)))))
				.append(typical == null ? ""
					: " \u00B7 " + WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.usually").replace("{0}", WizardPage.durationText(typical.longValue()))))
				.append("</span>");
			return;
		}
		// the KEY column shows the real key as chips once resolved (WizardSnapshot.TableInfo, the live source of truth)
		final WizardSnapshot.TableInfo tableInfo = LandingRunProgress.KEY.equals(pCell.stage) && LandingRunProgress.DONE.equals(pCell.state)
			? WizardPage.findTableInfo(pModel, pTable.id) : null;
		final String took = pCell.startedAt > 0L && pCell.endedAt > 0L
			? WizardMessages.get(pLocale, "wizard.step3.run.live.took").replace("{0}", WizardPage.durationText(pCell.endedAt - pCell.startedAt)) : null;
		if (tableInfo != null && !tableInfo.keyColumns.isEmpty())
		{
			pOut.append("<br/>");
			WizardPage.renderKeyChips(pOut, pLocale, tableInfo.keyColumns, tableInfo.keyOrigin);
			if (took != null)
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(took)).append("</span>");
			}
		}
		else if (pCell.summary != null || took != null)
		{
			final String summary = pCell.summary == null ? null : RunPanelText.display(pCell.summary, pLocale);
			pOut.append("<br/><span class=\"mdmf-hint\">").append(WizardPage.esc(summary == null ? took : took == null ? summary : summary + " \u00B7 " + took))
				.append("</span>");
		}
	}

	/** "18 s", "1 min 40", "12 min" - the same format as wizard.js' {@code dur}. */
	static String durationText(final long pMs)
	{
		final long seconds = Math.max(0L, pMs) / 1000L;
		if (seconds < 60L)
		{
			return seconds + " s";
		}
		final long minutes = seconds / 60L;
		final long rest = seconds % 60L;
		return minutes >= 10L || rest == 0L ? minutes + " min" : minutes + " min " + (rest < 10L ? "0" : "") + rest;
	}

	/** "1:40", "12:05", "1:02:05" - the header's elapsed clock, same as wizard.js' {@code clock}. */
	static String clockText(final long pMs)
	{
		final long seconds = Math.max(0L, pMs) / 1000L;
		final long h = seconds / 3600L;
		final long m = seconds / 60L % 60L;
		final long sec = seconds % 60L;
		final String mm = h > 0L && m < 10L ? "0" + m : String.valueOf(m);
		return (h > 0L ? h + ":" : "") + mm + ":" + (sec < 10L ? "0" : "") + sec;
	}

	/** Time left, rounded up to the minute past one minute; "almost done" under 30 s; the "no estimate yet" line when unknown. */
	static String leftText(final Locale pLocale, final Long pRemainingMs)
	{
		if (pRemainingMs == null)
		{
			return WizardMessages.get(pLocale, "wizard.step3.run.live.leftUnknown");
		}
		if (pRemainingMs.longValue() < 30000L)
		{
			return WizardMessages.get(pLocale, "wizard.step3.run.live.leftSoon");
		}
		final long ms = pRemainingMs.longValue() < 60000L ? pRemainingMs.longValue() : (pRemainingMs.longValue() + 59999L) / 60000L * 60000L;
		return WizardMessages.get(pLocale, "wizard.step3.run.live.left").replace("{0}", WizardPage.durationText(ms));
	}

	private static WizardSnapshot.TableInfo findTableInfo(final Model pModel, final String pTableId)
	{
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (table.id.equals(pTableId))
				{
					return table;
				}
			}
		}
		return null;
	}

	/**
	 * Coordinator follow-up (2026-09-24, docs/mockups/sources-landing/LandingReview.dc.html): the REAL key
	 * chooser - candidate cards (single columns that look unique on the sample), a "Several columns together"
	 * builder, and the sample table with the current choice highlighted, all recomputed LIVE in the browser
	 * ({@code wizard.js}) as a person clicks a candidate or checks/unchecks a composite column - no round trip
	 * until "Use ... and continue". Replaces the first cut of this lot (a plain checkbox picker, kept only in
	 * the Advanced disclosure's own {@link #renderLandingTableRow} now).
	 * <p>
	 * ONE set of real {@code .mdmf-key-check} checkboxes exists per column (inside the composite builder,
	 * {@link #renderCandidateCard}'s own "cards" above it are plain buttons with {@code data-mdmf-key-select}
	 * that MOVE the checked state there, never a second, independent input for the same column - so there is
	 * exactly one source of truth for "the columns currently chosen", and {@code wizard.js}'s pre-existing
	 * {@code data-mdmf-key-input} collection (this whole panel still renders inside one {@code <td>}) needs no
	 * change to post whatever was last checked to {@code LANDING_KEY_APPLY}.
	 * <p>
	 * The sample is embedded once ({@link LandingKeyChooserJson#sample}, escaped against a premature {@code
	 * </script>}) and the live check mirrors {@link KeyEvidence#combination}'s counting rules in {@code
	 * wizard.js} - never re-fetched, never compared back against a server value. Every localized template this
	 * needs ({@code wizard.step3.run.chooser.*}) is read WITHOUT {@code MessageFormat} arguments (so its
	 * {@code {0}}/{@code {1}} placeholders survive literally) and passed as {@code data-mdmf-i18n-*} attributes
	 * on the panel - {@code wizard.js} does its own trivial {@code {0}}/{@code {1}} substitution, so no
	 * translation table is duplicated in JavaScript.
	 */
	private static void renderLandingKeyChooser(final StringBuilder pOut, final Model pModel, final Locale pLocale, final LandingRunProgress.TableProgress pTable)
	{
		final int columnCount = LandingRunProgress.STAGES.size() + 1;
		pOut.append("<tr><td colspan=\"").append(columnCount).append("\">");
		WizardPage.renderKeyChooserPanel(pOut, pModel, pLocale, pTable.id, "LANDING_KEY_APPLY", "wizard.step3.run.chooser.apply");
		pOut.append("</td></tr>");
	}

	/**
	 * Landing-walkthrough lot (2026-10-16): the body of {@link #renderLandingKeyChooser} (the panel itself, no table row around it) so the walkthrough page can place
	 * the same chooser under a table of its own list; {@code pApplyAction} is the action its button posts ({@code LANDING_KEY_APPLY} resumes a run, {@code
	 * LANDING_WALK_KEY_APPLY} only writes the key).
	 */
	static void renderKeyChooserPanel(final StringBuilder pOut, final Model pModel, final Locale pLocale, final String pTableId, final String pApplyAction, final String pApplyLabelKey)
	{
		final KeyStudyReport report = pModel.landingKeyChooser;
		final String panelId = "mdmfKeyChooser" + WizardPage.esc(pTableId);
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted mdmf-key-chooser\" id=\"").append(panelId).append("\" data-mdmf-table-id=\"")
			.append(WizardPage.esc(pTableId)).append('"');
		WizardPage.renderKeyChooserI18nAttrs(pOut, pLocale);
		pOut.append(">");
		pOut.append("<script type=\"application/json\" class=\"mdmf-key-sample-json\">")
			.append(com.on.ebx.factory.ebx.ui.FactoryHtml.jsonForScript(LandingKeyChooserJson.sample(report))).append("</script>");
		pOut.append("<h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.which"))).append("</h3>");

		// candidate cards: the proposal's own single column first (if it is one column), then every other
		// column unique on the sample, capped so the panel stays readable
		// landing test fixes (2026-10-17): identifier-like columns first, dates, timestamps and amounts last and never preselected (KeyCandidateRank)
		final List<String> shown = new ArrayList<String>();
		pOut.append("<div class=\"mdmf-key-candidates\">");
		final List<String> preselected = com.on.ebx.factory.landing.KeyCandidateRank.preselected(report);
		final boolean proposalCard = report.proposal != null && report.proposal.columns.size() == 1 && preselected.equals(report.proposal.columns);
		if (proposalCard)
		{
			WizardPage.renderCandidateCard(pOut, pLocale, report, report.proposal.columns.get(0), true);
			shown.add(report.proposal.columns.get(0));
		}
		for (final String name : com.on.ebx.factory.landing.KeyCandidateRank.candidates(report, shown, 4 - shown.size()))
		{
			WizardPage.renderCandidateCard(pOut, pLocale, report, name, false);
			shown.add(name);
		}
		pOut.append("</div>");

		// "Several columns together" - the one real checkbox per column, table order (order is the identifier)
		pOut.append("<div class=\"mdmf-panel mdmf-key-composite\"><div style=\"display:flex;align-items:center;gap:10px\"><b>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.composite.title"))).append("</b><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.composite.hint"))).append("</span></div>");
		pOut.append("<div class=\"mdmf-key-composite-columns\">");
		for (final KeyStudyReport.ColumnRow column : report.columns)
		{
			boolean checked = false;
			for (final String name : preselected)
			{
				checked |= name.equalsIgnoreCase(column.name);
			}
			pOut.append("<label class=\"mdmf-key-chip-toggle\"><input type=\"checkbox\" class=\"mdmf-key-check\" value=\"").append(WizardPage.esc(column.name))
				.append('"').append(checked ? " checked=\"checked\"" : "").append("/> ").append(WizardPage.esc(column.name)).append("</label>");
		}
		pOut.append("</div>");
		pOut.append("<div class=\"mdmf-key-stats\" id=\"").append(panelId).append("Stats\"></div>");
		pOut.append("</div>");

		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" id=\"").append(panelId)
			.append("Apply\" data-mdmf-action=\"").append(pApplyAction).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTableId)).append("\" data-mdmf-key-input=\"1\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pApplyLabelKey))).append("</button></div>");

		if (!report.sampleRows.isEmpty())
		{
			pOut.append("<h4 class=\"mdmf-subtitle\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.sample", Integer.valueOf(report.sampleRows.size()),
					Integer.valueOf(report.sampleRows.size()))))
				.append("</h4>");
			WizardPage.renderSampleTableHighlighted(pOut, report.sampleColumns, report.sampleRows);
		}
		pOut.append("</div>");
	}

	/** {@code data-mdmf-i18n-*} attributes {@code wizard.js}'s live recompute reads - raw {@code MessageFormat} patterns (no args passed, so {@code {0}}/{@code {1}} survive), see {@link #renderLandingKeyChooser}'s own Javadoc. */
	static void renderKeyChooserI18nAttrs(final StringBuilder pOut, final Locale pLocale)
	{
		pOut.append(" data-mdmf-i18n-unique-of=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.uniqueOf"))).append('"');
		pOut.append(" data-mdmf-i18n-unique-together-of=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.uniqueTogetherOf"))).append('"');
		pOut.append(" data-mdmf-i18n-not-a-key=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.notAKey"))).append('"');
		pOut.append(" data-mdmf-i18n-never-empty=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.neverEmpty"))).append('"');
		// coordinator follow-up round 2 (2026-09-24): singular/plural through the message bundle, not JS -
		// wizard.js picks emptyOnRow (count==1) or emptyOnRows (count>1) itself, same rowsUniqueComplete-based
		// counting rule renderUniquenessChips uses server-side (KeyEvidence.Combination#rowsUniqueComplete).
		pOut.append(" data-mdmf-i18n-empty-on-row=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.emptyOnRow"))).append('"');
		pOut.append(" data-mdmf-i18n-empty-on-rows=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.emptyOnRows"))).append('"');
		pOut.append(" data-mdmf-i18n-use-and-continue=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.useKeyAndContinue"))).append('"');
		pOut.append(" data-mdmf-i18n-choose-a-column=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.chooseAColumn"))).append('"');
	}

	/** One candidate button of {@link #renderLandingKeyChooser} - a single column, its measured facts, never a real input (see that method's own "one source of truth" note). */
	private static void renderCandidateCard(final StringBuilder pOut, final Locale pLocale, final KeyStudyReport pReport, final String pColumnName,
		final boolean pPrimary)
	{
		// coordinator follow-up (2026-09-24, round 3 exactness fix): computed the SAME way as the composite
		// builder/fold (KeyEvidence.combination over this one column, KeyEvidence.Combination#rowsUniqueComplete)
		// rather than KeyEvidence.Column's own "distinct non-empty values" convention - the two used to disagree
		// on an empty row (see Combination#rowsUniqueComplete's own Javadoc for the exact contradiction this fixes).
		final KeyEvidence.Combination combination = KeyEvidence.combination(pReport.sampleColumns, pReport.sampleRows, Arrays.asList(pColumnName));
		pOut.append("<button type=\"button\" class=\"mdmf-key-candidate").append(pPrimary ? " mdmf-key-candidate-primary" : "").append("\" data-mdmf-key-select=\"")
			.append(WizardPage.esc(pColumnName)).append("\">");
		pOut.append("<div><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(pColumnName)).append("</span>");
		if (pPrimary && pReport.proposal != null)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyOrigin." + pReport.proposal.origin))).append(" · ")
				.append(pReport.proposal.confidence).append("%</span>");
		}
		pOut.append("</div><div>");
		WizardPage.renderUniquenessChips(pOut, pLocale, combination, false);
		pOut.append("</div>");
		pOut.append("</button>");
	}

	private static KeyStudyReport.ColumnRow findColumnRow(final KeyStudyReport pReport, final String pName)
	{
		for (final KeyStudyReport.ColumnRow row : pReport.columns)
		{
			if (row.name.equalsIgnoreCase(pName))
			{
				return row;
			}
		}
		return null;
	}

	/** Same markup as {@link #renderSampleTable}, with {@code data-mdmf-key-col} on every header/cell so {@code wizard.js} can toggle a highlight class as the chosen key changes - see {@link #renderLandingKeyChooser}. */
	private static void renderSampleTableHighlighted(final StringBuilder pOut, final List<String> pColumns, final List<List<String>> pRows)
	{
		WizardPage.renderSampleTableHighlighted(pOut, pColumns, pRows, null);
	}

	/**
	 * Coordinator follow-up (2026-09-24, LandingComposite.dc.html "How a row is recognised"): with
	 * {@code pKeyColumns} (2+ columns), an extra {@code _sourceId} column is appended, computed per row with
	 * {@link com.on.ebx.factory.landing.LandingFlow#sourceIdPreview} over that row's OWN values for those
	 * columns (in {@code pKeyColumns}' order, the table's own key order) - display only, mirrors the SQL
	 * path's escaping exactly, never used to write anything.
	 */
	private static void renderSampleTableHighlighted(final StringBuilder pOut, final List<String> pColumns, final List<List<String>> pRows,
		final List<String> pKeyColumns)
	{
		final boolean showSourceId = pKeyColumns != null && pKeyColumns.size() >= 2;
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr>");
		for (final String column : pColumns)
		{
			pOut.append("<th data-mdmf-key-col=\"").append(WizardPage.esc(column)).append("\">").append(WizardPage.esc(column)).append("</th>");
		}
		if (showSourceId)
		{
			pOut.append("<th class=\"mono\">_sourceId</th>");
		}
		pOut.append("</tr></thead><tbody>");
		for (final List<String> row : pRows)
		{
			pOut.append("<tr>");
			for (int c = 0; c < pColumns.size(); c++)
			{
				final String value = c < row.size() ? row.get(c) : null;
				pOut.append("<td data-mdmf-key-col=\"").append(WizardPage.esc(pColumns.get(c))).append("\">").append(WizardPage.esc(WizardPage.orDash(value)))
					.append("</td>");
			}
			if (showSourceId)
			{
				final List<String> parts = new ArrayList<String>();
				for (final String keyColumn : pKeyColumns)
				{
					final int index = pColumns.indexOf(keyColumn);
					parts.add(index >= 0 && index < row.size() ? row.get(index) : "");
				}
				pOut.append("<td class=\"mono\">").append(WizardPage.esc(com.on.ebx.factory.landing.LandingFlow.sourceIdPreview(parts))).append("</td>");
			}
			pOut.append("</tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	private static void renderLandingRunDone(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		int loadedRows = 0;
		int tableCount = 0;
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				tableCount++;
				loadedRows += table.lastLoadRows;
			}
		}
		// coordinator follow-up round 3 (2026-09-24): two independently pluralized sentences (a table count
		// and a row count), never one template with two "(s)" - see WizardPage#plural's own Javadoc.
		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.done.title")))
			.append("</h3><p class=\"mdmf-lead\">")
			.append(WizardPage.esc(WizardPage.plural(pLocale, tableCount, "wizard.step3.run.done.tablesLanded.one", "wizard.step3.run.done.tablesLanded.other")))
			.append(' ')
			.append(WizardPage.esc(WizardPage.plural(pLocale, loadedRows, "wizard.step3.run.done.rowsLoadedTotal.one", "wizard.step3.run.done.rowsLoadedTotal.other")))
			.append("</p>");
		// coordinator follow-up (2026-09-24, LandingReview.dc.html "Landing so far"): the per-table list - key
		// chips + origin, rows loaded, a Preview link (the SAME TABLE_PREVIEW action/panel the Advanced table
		// already uses) - straight from the snapshot, no round trip.
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (!table.selected)
				{
					continue;
				}
				pOut.append("<div class=\"mdmf-landing-done-row\"><div><b>").append(WizardPage.esc(table.tableName)).append("</b> <span class=\"mdmf-hint\">")
					.append(WizardPage.esc(source.sourceSystem)).append("</span><br/>");
				// UX lot U5 (2026-10-13, RAPPORT 2.6): "proposed by the language model during a deduced scan, confirmed by a person" is step 2's
				// sentence now: who confirmed it and when when the catalog knows, else "confirmed earlier" (keyLabel, the same one as the Sources cards).
				final boolean confirmedKey = "DEDUCED".equals(table.keyOrigin) && "DEDUCED".equals(table.scanOrigin) && !WizardPage.keyColumnList(table.keyColumns).isEmpty();
				WizardPage.renderKeyChips(pOut, pLocale, table.keyColumns, confirmedKey ? "" : table.keyOrigin);
				if (confirmedKey)
				{
					pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(WizardPage.withoutTrailingColon(WizardPage.keyLabel(pLocale, table)))).append("</span>");
				}
				pOut.append("</div><div class=\"mdmf-hint\">")
					.append(WizardPage.esc(WizardPage.plural(pLocale, table.lastLoadRows, "wizard.step3.run.done.rowsLoaded.one", "wizard.step3.run.done.rowsLoaded.other")))
					.append("</div>");
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"TABLE_PREVIEW\" data-mdmf-source-id=\"")
					.append(WizardPage.esc(source.id)).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(table.id)).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.preview"))).append("</button></div>");
				if (table.id.equals(pModel.previewTableId))
				{
					pOut.append("<div class=\"mdmf-landing-done-preview\">");
					if (pModel.previewError != null && !pModel.previewError.isEmpty())
					{
						pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(pModel.previewError)).append("</div>");
					}
					else
					{
						WizardPage.renderSampleTable(pOut, pModel.previewColumns, pModel.previewRows);
					}
					pOut.append("</div>");
				}
			}
		}
		pOut.append("</div>");

		// coordinator follow-up (2026-09-24): the purple "Continue to the staging" call to action - the
		// wizard's own client-side step switch (data-mdmf-goto-step, wizard.js), no round trip.
		// step-gating lot (2026-09-24): this used to render as soon as ANY selected table was loaded
		// (landingRunDone), not once EVERY ticked table was (WizardRules.stepStates' now-stricter Staging
		// prerequisite) - when it is not met yet, one line names what remains instead of the button.
		final WizardRules.StepState stagingState = pModel.stepStates[WizardRules.STEP_STAGING];
		pOut.append("<div class=\"mdmf-panel mdmf-landing-cta\">");
		if (stagingState.reachable)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.done.continueLead"))).append("</p>")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-goto-step=\"").append(WizardRules.STEP_STAGING).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.done.continue"))).append("</button>");
		}
		else
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, stagingState.blockedKey))).append("</p>");
		}
		pOut.append("</div>");

		// coordinator follow-up (2026-09-24, LandingComposite.dc.html): a fold per table whose key has 2+
		// columns - chips/origin come straight from WizardSnapshot.TableInfo (already known, no round trip);
		// the "why one column is not enough" bars need a fresh sample, so they only render once
		// LANDING_KEY_COMPOSITE_DETAIL has fetched one for THIS table (see Model#landingCompositeDetail).
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				if (!table.selected || WizardPage.keyColumnList(table.keyColumns).size() < 2)
				{
					continue;
				}
				WizardPage.renderLandingCompositeFold(pOut, pModel, pLocale, table);
			}
		}
	}

	/**
	 * Coordinator follow-up (2026-09-24): the ONE place a key's columns are drawn as chips (never as raw
	 * "col1+col2" text) - a key is ALWAYS its columns, in table order, joined by "+", one chip per column,
	 * plus an optional origin chip. Used by the applications strip, the Advanced table, the running grid's
	 * {@code KEY} column and the done view's per-table list, so all four read identically.
	 */
	static void renderKeyChips(final StringBuilder pOut, final Locale pLocale, final List<String> pColumns, final String pOrigin)
	{
		if (pColumns.isEmpty())
		{
			pOut.append(WizardPage.esc(WizardPage.orDash(null)));
			return;
		}
		for (int i = 0; i < pColumns.size(); i++)
		{
			if (i > 0)
			{
				pOut.append("<span class=\"kplus\">+</span>");
			}
			pOut.append("<span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(pColumns.get(i))).append("</span>");
		}
		if (pOrigin != null && !pOrigin.isEmpty())
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyOrigin." + pOrigin)))
				.append("</span>");
		}
	}

	static void renderKeyChips(final StringBuilder pOut, final Locale pLocale, final String pKeyColumnsCsv, final String pOrigin)
	{
		WizardPage.renderKeyChips(pOut, pLocale, WizardPage.keyColumnList(pKeyColumnsCsv), pOrigin);
	}

	/**
	 * A 20px state circle before the grid's state text (coordinator follow-up, 2026-09-24: the plain
	 * background-tinted cell alone read as clipped/overlapping text) - a small glyph per {@link
	 * LandingRunProgress} state, never overlapping the text since it is a real inline-block, not an
	 * absolutely-positioned pseudo-element.
	 * <p>
	 * {@code pPassiveWait} (coordinator follow-up round 3, 2026-09-24): a {@link LandingRunProgress#FLOW}/
	 * {@link LandingRunProgress#LOAD} cell {@link LandingRunProgress#WAITING} on its key gets the SAME dashed,
	 * muted circle as {@link LandingRunProgress#SKIPPED} (a dash, not an alert "!") - it is not asking this
	 * cell's own table to act, unlike the {@link LandingRunProgress#KEY} column's own {@code WAITING} (the
	 * solid orange alert circle, a genuine "you need to act now").
	 */
	private static void renderStateCircle(final StringBuilder pOut, final String pState, final boolean pPassiveWait)
	{
		final String glyph = LandingRunProgress.DONE.equals(pState) ? "✓" // check
			: pPassiveWait ? "–" // dashed/muted, same glyph as SKIPPED - see this method's own Javadoc
				: LandingRunProgress.FAILED.equals(pState) || LandingRunProgress.WAITING.equals(pState) ? "!"
					: LandingRunProgress.RUNNING.equals(pState) ? "…" // ellipsis, stands in for a spinner in static HTML
						: LandingRunProgress.SKIPPED.equals(pState) ? "–" // en dash
							: ""; // TODO: an empty ring
		final String stateClass = pPassiveWait ? "waiting-passive" : pState.toLowerCase(Locale.ROOT);
		pOut.append("<span class=\"mdmf-landing-circle mdmf-landing-circle-").append(stateClass).append("\">").append(glyph).append("</span>");
	}

	/**
	 * Coordinator follow-up round 3 (2026-09-24): "make the builder and the cards count the same way" with an
	 * EXACT definition, not an approximation - both now read {@link KeyEvidence.Combination#rowsUniqueComplete}
	 * directly (even a single-column candidate is a one-column combination), mirrored by {@code wizard.js}'s
	 * own recompute. Replaces this method's own earlier approximation (subtracting a guessed bucket count from
	 * {@link KeyEvidence.Combination#distinctTogether}, which {@code KeyEvidenceTest}'s new "several different
	 * partly-empty combinations" case proved wrong as soon as a duplicate existed among the COMPLETE rows
	 * alongside them). {@code rowsUniqueComplete} is computed once, server-side in {@link KeyEvidence
	 * #combination} itself, and is the single source of truth both this method and {@code wizard.js} display.
	 */
	private static void renderUniquenessChips(final StringBuilder pOut, final Locale pLocale, final KeyEvidence.Combination pCombination, final boolean pTogether)
	{
		final boolean unique = pCombination.unique();
		pOut.append("<span class=\"mdmf-chip ").append(unique ? "mdmf-chip-real" : "mdmf-chip-warn").append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pTogether ? "wizard.step3.run.chooser.uniqueTogetherOf" : "wizard.step3.run.chooser.uniqueOf",
				Integer.valueOf(pCombination.rowsUniqueComplete), Integer.valueOf(pCombination.rowsInSample))));
		if (!unique)
		{
			pOut.append(" · ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.notAKey")));
		}
		pOut.append("</span> ");
		final int emptyRows = pCombination.rowsAllEmpty + pCombination.rowsPartlyEmpty;
		if (emptyRows == 0)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-real\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.chooser.neverEmpty")))
				.append("</span>");
		}
		else
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-warn\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, emptyRows == 1 ? "wizard.step3.run.chooser.emptyOnRow" : "wizard.step3.run.chooser.emptyOnRows",
					Integer.valueOf(emptyRows))))
				.append("</span>");
		}
	}

	private static List<String> keyColumnList(final String pKeyColumnsCsv)
	{
		final List<String> out = new ArrayList<String>();
		if (pKeyColumnsCsv != null)
		{
			for (final String column : pKeyColumnsCsv.split(","))
			{
				final String trimmed = column.trim();
				if (!trimmed.isEmpty())
				{
					out.add(trimmed);
				}
			}
		}
		return out;
	}

	private static void renderLandingCompositeFold(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.TableInfo pTable)
	{
		final List<String> keyColumns = WizardPage.keyColumnList(pTable.keyColumns);
		pOut.append("<details class=\"mdmf-panel mdmf-disclosure\"><summary>").append(WizardPage.esc(pTable.tableName)).append(" &mdash; ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.composite.title", Integer.valueOf(keyColumns.size())))).append("</summary>");
		pOut.append("<div style=\"display:flex;align-items:center;gap:5px;flex-wrap:wrap;margin:8px 0\">");
		for (int i = 0; i < keyColumns.size(); i++)
		{
			if (i > 0)
			{
				pOut.append("<span class=\"kplus\">+</span>");
			}
			pOut.append("<span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(keyColumns.get(i))).append("</span>");
		}
		if (!pTable.keyOrigin.isEmpty())
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyOrigin." + pTable.keyOrigin)))
				.append("</span>");
		}
		pOut.append("</div>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.composite.lead"))).append("</p>");

		final boolean hasDetail = pModel.landingCompositeDetail != null && pTable.id.equals(pModel.landingCompositeDetailTableId);
		if (!hasDetail)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"LANDING_KEY_COMPOSITE_DETAIL\" data-mdmf-table-id=\"")
				.append(WizardPage.esc(pTable.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.composite.open")))
				.append("</button>");
		}
		else
		{
			final KeyStudyReport report = pModel.landingCompositeDetail;
			pOut.append("<h4 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.composite.why"))).append("</h4>");
			// coordinator follow-up (2026-09-24): bars (distinct/rows width), not text lines - LandingComposite.dc.html -
			// and the SAME KeyEvidence.combination + Combination#rowsUniqueComplete the candidate cards/live
			// builder use, so a column's own bar here reads exactly like its candidate card would.
			pOut.append("<div class=\"mdmf-composite-bars\">");
			for (final String column : keyColumns)
			{
				WizardPage.renderCompositeBar(pOut, pLocale, column, KeyEvidence.combination(report.sampleColumns, report.sampleRows, Arrays.asList(column)));
			}
			final KeyEvidence.Combination together = KeyEvidence.combination(report.sampleColumns, report.sampleRows, keyColumns);
			WizardPage.renderCompositeBar(pOut, pLocale, WizardPage.joinPlus(keyColumns), together);
			pOut.append("</div>");
			if (!report.sampleRows.isEmpty())
			{
				// coordinator follow-up (2026-09-24): the computed _sourceId column, same escaping rule as the SQL
				// path (LandingFlow#sourceIdPreview) - display only, never a real _sourceId.
				WizardPage.renderSampleTableHighlighted(pOut, report.sampleColumns, report.sampleRows, keyColumns);
			}
		}
		pOut.append("</details>");
	}

	/** One "why one column is not enough" bar - width = rowsUniqueComplete/rowsInSample, coloured by {@link KeyEvidence.Combination#unique}. */
	private static void renderCompositeBar(final StringBuilder pOut, final Locale pLocale, final String pLabel, final KeyEvidence.Combination pCombination)
	{
		final int distinct = pCombination.rowsUniqueComplete;
		final boolean unique = pCombination.unique();
		final int width = pCombination.rowsInSample == 0 ? 0 : Math.min(100, distinct * 100 / pCombination.rowsInSample);
		pOut.append("<div class=\"mdmf-composite-bar-row\"><div class=\"mdmf-composite-bar-label\">").append(WizardPage.esc(pLabel)).append("</div>")
			.append("<div class=\"mdmf-composite-bar-track\"><div class=\"mdmf-composite-bar-fill ").append(unique ? "mdmf-composite-bar-fill-real" : "")
			.append("\" style=\"width:").append(width).append("%\"></div></div>")
			.append("<div class=\"mdmf-composite-bar-value\">").append(distinct).append(" / ").append(pCombination.rowsInSample).append(" &mdash; ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, unique ? "wizard.step3.run.composite.unique" : "wizard.step3.run.composite.repeats")))
			.append("</div></div>");
	}

	private static void renderLandingSource(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource)
	{
		final WizardRules.ActionGates gates = WizardRules.actionGates(pModel.snapshot, pModel.designer, pSource);
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(pSource.sourceSystem)).append(" &mdash; ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.status." + pSource.status))).append("</h3>");
		if (pSource.message != null && !pSource.message.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(pSource.message)).append("</p>");
		}
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"PLAN\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSource.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.plan"))).append("</button>");
		if (pSource.planned)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.planned"))).append("</span>");
		}
		pOut.append("</div>");

		if (!pSource.tables.isEmpty())
		{
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.include")))
				.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.table"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.columns"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.sourceRows"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.proposedKey"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.confidence"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.status"))).append("</th></tr></thead><tbody>");
			for (final WizardSnapshot.TableInfo table : pSource.tables)
			{
				WizardPage.renderLandingTableRow(pOut, pModel, pLocale, pSource, table);
			}
			pOut.append("</tbody></table></div>");
		}

		if (!pSource.relations.isEmpty())
		{
			pOut.append("<h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.relations"))).append("</h3>");
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.relation")))
				.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.origin"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.confidence"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.decision"))).append("</th></tr></thead><tbody>");
			for (final WizardSnapshot.RelationInfo relation : pSource.relations)
			{
				WizardPage.renderRelationRow(pOut, pLocale, relation);
			}
			pOut.append("</tbody></table></div>");
			if (!pSource.loadOrder.isEmpty())
			{
				pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.loadOrder", pSource.loadOrder))).append("</p>");
			}
			if (!pSource.loadOrderCycle.isEmpty())
			{
				pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.cycle", pSource.loadOrderCycle)))
					.append("</div>");
			}
		}

		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"GENERATE\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
			.append("\"").append(gates.generateLanding.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.generate"))).append("</button>");
		if (!gates.generateLanding.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, gates.generateLanding.reasonKey))).append("</span>");
		}
		else
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.generate.hint"))).append("</span>");
		}
		pOut.append("</div>");

		if (!pSource.pipelineSteps.isEmpty())
		{
			WizardPage.renderTrack(pOut, pLocale, pSource.pipelineSteps);
		}
		pOut.append("</div>");
	}

	private static void renderLandingTableRow(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource,
		final WizardSnapshot.TableInfo pTable)
	{
		final boolean editable = "PROPOSED".equals(pTable.status);
		// scroll-anchor lot (2026-09-23): a stable id (the wizard lives inside an EBX workspace pane, so
		// window.scrollY/scrollTo are always a no-op - wizard.js scrolls THIS element back into view instead)
		pOut.append("<tr id=\"mdmf-table-").append(WizardPage.esc(pTable.id)).append("\"><td>");
		if (editable)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"TABLE_TOGGLE\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
				.append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, pTable.selected ? "wizard.step3.included" : "wizard.step3.excluded"))).append("</button>");
		}
		else
		{
			pOut.append("<span class=\"mdmf-chip ").append(pTable.selected ? "mdmf-chip-real" : "mdmf-chip-target").append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, pTable.selected ? "wizard.step3.included" : "wizard.step3.excluded"))).append("</span>");
		}
		pOut.append("</td><td><b>").append(WizardPage.esc(pTable.tableName)).append("</b><br/><span class=\"mdmf-hint\">").append(WizardPage.esc(pTable.sourceObject))
			.append("</span><br/><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"TABLE_PREVIEW\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSource.id)).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.preview"))).append("</button></td>");
		pOut.append("<td class=\"num\">").append(pTable.columnCount).append("</td><td class=\"num\">").append(pTable.sourceRowCount).append("</td>");
		pOut.append("<td>");
		if (editable)
		{
			if (pTable.columnNames.isEmpty())
			{
				// no known columns to check off (should not happen for a PROPOSED table once LandingPlanner has
				// run - defensive only): fall back to the original free-text field rather than leaving the key
				// impossible to set at all
				pOut.append("<input type=\"text\" class=\"mdmf-key-input\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id)).append("\" data-mdmf-table-id=\"")
					.append(WizardPage.esc(pTable.id)).append("\" value=\"").append(WizardPage.esc(pTable.keyColumns)).append("\"/>");
			}
			else
			{
				pOut.append("<div class=\"mdmf-key-picker\">");
				for (final String column : pTable.columnNames)
				{
					final boolean checked = WizardPage.hasColumn(pTable.keyColumns, column);
					pOut.append("<label><input type=\"checkbox\" class=\"mdmf-key-check\" value=\"").append(WizardPage.esc(column)).append("\"")
						.append(checked ? " checked=\"checked\"" : "").append("/> ").append(WizardPage.esc(column)).append("</label>");
				}
				pOut.append("</div>");
			}
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"TABLE_SET_KEY\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
				.append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\" data-mdmf-key-input=\"1\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.setKey"))).append("</button>");
		}
		else
		{
			// coordinator follow-up (2026-09-24): chips, not raw "col1,col2" text - see renderKeyChips.
			WizardPage.renderKeyChips(pOut, pLocale, pTable.keyColumns, pTable.keyOrigin);
			// sources+landing redesign, lot LANDING (2026-09-24): "Change the key..." on an already-loaded table -
			// ebx.LandingKeyChange (composite-key lot). First click (LANDING_KEY_CHANGE_PICK) opens the same
			// manual checkbox picker as a PROPOSED table's, right under this row; its own button IS the
			// confirmation (the label states the consequence), then posts LANDING_KEY_CHANGE.
			if ("GENERATED".equals(pTable.status) || "LOADED".equals(pTable.status))
			{
				pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"LANDING_KEY_CHANGE_PICK\" data-mdmf-source-id=\"")
					.append(WizardPage.esc(pSource.id)).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeKey"))).append("</button>");
			}
		}
		if (pTable.id.equals(pModel.landingKeyChangeTableId))
		{
			WizardPage.renderLandingKeyChangePicker(pOut, pModel, pLocale, pSource, pTable);
		}
		// landing key-study lot (2026-09-23): the study is worth reading on a generated table too (it explains
		// the key the landing was built on), only Apply is gated by editability - see renderKeyStudyRow
		pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"TABLE_KEY_STUDY\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
			.append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy")))
			.append("</button>");
		WizardPage.renderChangeColumn(pOut, pLocale, pSource, pTable);
		pOut.append("</td><td>");
		if (pTable.keyConfidence > 0)
		{
			pOut.append("<span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pTable.keyConfidence).append("%\"></i></span>")
				.append(pTable.keyConfidence).append(" %</span>");
			if (!pTable.keyOrigin.isEmpty())
			{
				pOut.append("<br/><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyOrigin." + pTable.keyOrigin))).append("</span>");
			}
			if (!pTable.keyRationale.isEmpty())
			{
				pOut.append("<details class=\"mdmf-rationale\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.rationale.show"))).append("</summary><p>")
					.append(WizardPage.esc(KeyRationaleText.display(pTable.keyRationale, pLocale))).append("</p></details>");
			}
		}
		else if (pTable.pendingKeyConfidence > 0)
		{
			// a proposal kept below the certainty bar (WizardData.recordKeyProposal): shown as NOT applied
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyPending")))
				.append(" ").append(pTable.pendingKeyConfidence).append(" %</span>");
			if (!pTable.keyRationale.isEmpty())
			{
				pOut.append("<details class=\"mdmf-rationale\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.rationale.show"))).append("</summary><p>")
					.append(WizardPage.esc(KeyRationaleText.display(pTable.keyRationale, pLocale))).append("</p></details>");
			}
		}
		else
		{
			pOut.append("&mdash;");
		}
		pOut.append("</td><td><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.status." + pTable.status)))
			.append("</span>");
		if (pTable.lastLoadRows > 0)
		{
			pOut.append("<br/><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardPage.plural(pLocale, pTable.lastLoadRows, "wizard.step3.lastLoad.one", "wizard.step3.lastLoad.other",
				new Object[] { pTable.lastLoadId })))
				.append("</span>");
		}
		pOut.append("</td></tr>");
		if (pTable.id.equals(pModel.previewTableId))
		{
			WizardPage.renderPreviewRow(pOut, pModel, pLocale);
		}
		if (pModel.keyStudy != null && pTable.id.equals(pModel.keyStudyTableId))
		{
			WizardPage.renderKeyStudyRow(pOut, pModel.keyStudy, pLocale, pSource, pTable);
		}
	}

	/**
	 * Volume wave 2, lot V2-E (2026-10-03): the table's CHANGE column - the source column that says a row changed, so that a reload reads
	 * only the rows changed since the last successful load. A select of the table's own columns (the one chosen by the catalog rule, or
	 * by a person, pre-selected) plus "none - reload in full", the kind of its values (pre-selected from the column's type by {@code
	 * wizard.js} when the column changes) and a Save button; rendered inside the key cell so it posts with the same row markers ({@code
	 * data-mdmf-source-id}/{@code data-mdmf-table-id}, {@code data-mdmf-change-input} for the two selects), with no table row of its own.
	 */
	private static void renderChangeColumn(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.SourceInfo pSource, final WizardSnapshot.TableInfo pTable)
	{
		if (pTable.columnNames.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-change-col\"><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeColumn"))).append("</span> ");
		pOut.append("<select class=\"mdmf-change-column\"><option value=\"\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeColumn.none"))).append("</option>");
		for (final String column : pTable.columnNames)
		{
			final String type = pTable.columnTypes.get(column);
			pOut.append("<option value=\"").append(WizardPage.esc(column)).append("\" data-kind=\"").append(WizardPage.esc(ChangeColumnRule.kindFor(type))).append("\"")
				.append(column.equals(pTable.changeColumn) ? " selected=\"selected\"" : "").append(">").append(WizardPage.esc(column)).append("</option>");
		}
		pOut.append("</select> <select class=\"mdmf-change-kind\">");
		for (final String kind : new String[] { ChangeColumnRule.TIMESTAMP, ChangeColumnRule.MONOTONIC_ID, ChangeColumnRule.ORDERED_STRING })
		{
			pOut.append("<option value=\"").append(kind).append("\"").append(kind.equals(pTable.changeColumnKind) ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeColumn.kind." + kind))).append("</option>");
		}
		pOut.append("</select> <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"TABLE_SET_CHANGE_COLUMN\" data-mdmf-source-id=\"").append(WizardPage.esc(pSource.id))
			.append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\" data-mdmf-change-input=\"1\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeColumn.save"))).append("</button>");
		pOut.append("<br/><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pTable.changeColumn.isEmpty() ? "wizard.step3.changeColumn.hint.none" : "wizard.step3.changeColumn.hint.set")))
			.append("</span></div>");
	}

	/** True when {@code pColumn} is one of the comma-joined names in {@code pCsv} (trimmed comparison) - same shape as {@link WizardSnapshot.TableInfo#keyColumns}. */
	private static boolean hasColumn(final String pCsv, final String pColumn)
	{
		if (pCsv == null || pCsv.isEmpty())
		{
			return false;
		}
		for (final String part : pCsv.split(","))
		{
			if (part.trim().equals(pColumn))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * Sources+landing redesign, lot LANDING (2026-09-24): the "Change the key..." picker for an already-
	 * loaded table ({@code ebx.LandingKeyChange}, composite-key lot) - the same checkbox markup {@link
	 * #renderLandingTableRow} uses for a {@code PROPOSED} table's own key picker, wrapped so {@code
	 * wizard.js}'s existing {@code data-mdmf-key-input} collection (walks up to the closest {@code <td>})
	 * still works: this whole panel renders INSIDE the row's key {@code <td>}, never a separate {@code <tr>}.
	 * The button's own label states the consequence (data loss + reload) - the confirmation IS the click,
	 * per the task brief's "confirm with the person before" (no separate modal was built for this lot).
	 */
	private static void renderLandingKeyChangePicker(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.SourceInfo pSource,
		final WizardSnapshot.TableInfo pTable)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\">");
		pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeKey.warning"))).append("</div>");
		if (pTable.columnNames.isEmpty())
		{
			pOut.append("<input type=\"text\" class=\"mdmf-key-input\" value=\"").append(WizardPage.esc(pTable.keyColumns)).append("\"/>");
		}
		else
		{
			pOut.append("<div class=\"mdmf-key-picker\">");
			for (final String column : pTable.columnNames)
			{
				final boolean checked = WizardPage.hasColumn(pTable.keyColumns, column);
				pOut.append("<label><input type=\"checkbox\" class=\"mdmf-key-check\" value=\"").append(WizardPage.esc(column)).append("\"")
					.append(checked ? " checked=\"checked\"" : "").append("/> ").append(WizardPage.esc(column)).append("</label>");
			}
			pOut.append("</div>");
		}
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-danger mdmf-btn-sm\" data-mdmf-action=\"LANDING_KEY_CHANGE\" data-mdmf-source-id=\"")
			.append(WizardPage.esc(pSource.id)).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\" data-mdmf-key-input=\"1\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.changeKey.apply"))).append("</button></div>");
		if (pModel.landingKeyChangeMessage != null)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(pModel.landingKeyChangeMessage)).append("</p>");
		}
		pOut.append("</div>");
	}

	/**
	 * The live sample fetched by {@code TABLE_PREVIEW} ({@link com.on.ebx.factory.ebx.LandingPreview}), one
	 * extra {@code <tr>} spanning the landing table's 7 columns, right under the table's own row - task
	 * brief: "under that table's row", "behind a small toggle button" (the toggle here is client-side only,
	 * {@code data-mdmf-toggle} - see {@code wizard.js} - since the data is already in the DOM once fetched;
	 * fetching again is a fresh {@code TABLE_PREVIEW} round trip via the row's own "Preview" button).
	 */
	private static void renderPreviewRow(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final String panelId = "mdmfPreview" + WizardPage.esc(pModel.previewTableId);
		pOut.append("<tr><td colspan=\"7\"><div class=\"mdmf-panel mdmf-panel-muted mdmf-preview-panel\" id=\"").append(panelId).append("\">");
		pOut.append("<div class=\"mdmf-actions\"><h3 class=\"mdmf-subtitle\">")
			.append(WizardPage.esc(WizardPage.plural(pLocale, pModel.previewRows.size(), "wizard.step3.preview.title.one", "wizard.step3.preview.title.other"))).append("</h3>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"").append(panelId).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.preview.close"))).append("</button></div>");
		if (pModel.previewError != null && !pModel.previewError.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(pModel.previewError)).append("</div>");
		}
		else if (pModel.previewRows.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.preview.empty"))).append("</p>");
		}
		else
		{
			WizardPage.renderSampleTable(pOut, pModel.previewColumns, pModel.previewRows);
		}
		pOut.append("</div></td></tr>");
	}

	private static void renderSampleTable(final StringBuilder pOut, final List<String> pColumns, final List<List<String>> pRows)
	{
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr>");
		for (final String column : pColumns)
		{
			pOut.append("<th>").append(WizardPage.esc(column)).append("</th>");
		}
		pOut.append("</tr></thead><tbody>");
		for (final List<String> row : pRows)
		{
			pOut.append("<tr>");
			for (final String value : row)
			{
				pOut.append("<td>").append(WizardPage.esc(value)).append("</td>");
			}
			pOut.append("</tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	/**
	 * Landing key-study lot (2026-09-23): the {@code TABLE_KEY_STUDY} panel under the table's own row
	 * ({@code ebx.KeyStudy} builds the {@link KeyStudyReport}) - the proposal first (whose it is, how sure,
	 * why, whether it is unique on the sample, and "Apply this key"), then how the language model went, then
	 * one row per column with what the sample shows of it, then the sample rows themselves. The proposal is
	 * never applied by the study: "Apply this key" is a real round trip ({@code TABLE_KEY_APPLY}) and the
	 * picker above stays the way to pick by hand.
	 */
	private static void renderKeyStudyRow(final StringBuilder pOut, final KeyStudyReport pReport, final Locale pLocale, final WizardSnapshot.SourceInfo pSource,
		final WizardSnapshot.TableInfo pTable)
	{
		pOut.append("<tr><td colspan=\"7\">");
		WizardPage.renderKeyStudyPanel(pOut, pReport, pLocale, pSource, pTable, "TABLE_KEY_APPLY");
		pOut.append("</td></tr>");
	}

	/** Landing-walkthrough lot (2026-10-16): the body of {@link #renderKeyStudyRow} without its table row; {@code pApplyAction} is what "Apply this key" posts ({@code TABLE_KEY_APPLY}, or {@code LANDING_WALK_KEY_STUDY_APPLY} on the walkthrough). */
	static void renderKeyStudyPanel(final StringBuilder pOut, final KeyStudyReport pReport, final Locale pLocale, final WizardSnapshot.SourceInfo pSource,
		final WizardSnapshot.TableInfo pTable, final String pApplyAction)
	{
		final String panelId = "mdmfKeyStudy" + WizardPage.esc(pReport.tableId);
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted mdmf-key-study\" id=\"").append(panelId).append("\">");
		pOut.append("<div class=\"mdmf-actions\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.title", pReport.tableName)))
			.append("</h3><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"").append(panelId).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.close"))).append("</button></div>");
		if (pReport.sampleError != null && !pReport.sampleError.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(pReport.sampleError)).append("</div>");
		}

		// the proposal
		pOut.append("<div class=\"mdmf-key-proposal\">");
		if (pReport.proposal == null)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.noProposal"))).append("</p>");
		}
		else
		{
			pOut.append("<p><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.proposal", pReport.proposal.columnsCsv().replace(",", ", "))))
				.append("</b> <span class=\"mdmf-chip ").append(KeyStudyReport.ORIGIN_LLM.equals(pReport.proposal.origin) ? "mdmf-chip-accent" : "mdmf-chip-target").append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyOrigin." + pReport.proposal.origin))).append("</span> ")
				.append("<span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pReport.proposal.confidence).append("%\"></i></span>")
				.append(pReport.proposal.confidence).append(" %</span></p>");
			if (!pReport.proposal.rationale.isEmpty())
			{
				pOut.append("<p class=\"mdmf-key-rationale\">").append(WizardPage.esc(KeyRationaleText.display(pReport.proposal.rationale, pLocale))).append("</p>");
			}
			final int sampleSize = pReport.sampleRows.size();
			final String uniqueText;
			if (pReport.proposalUniqueInSample == null)
			{
				uniqueText = WizardMessages.get(pLocale, "wizard.step3.keyStudy.uniqueUnknown", Integer.valueOf(sampleSize));
			}
			else if (pReport.proposalUniqueInSample.booleanValue())
			{
				uniqueText = WizardPage.plural(pLocale, sampleSize, "wizard.step3.keyStudy.uniqueYes.one", "wizard.step3.keyStudy.uniqueYes.other");
			}
			else
			{
				uniqueText = WizardPage.plural(pLocale, sampleSize, "wizard.step3.keyStudy.uniqueNo.one", "wizard.step3.keyStudy.uniqueNo.other");
			}
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(uniqueText)).append("</p>");
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" data-mdmf-action=\"").append(pApplyAction).append("\" data-mdmf-source-id=\"")
				.append(WizardPage.esc(pSource.id)).append("\" data-mdmf-table-id=\"").append(WizardPage.esc(pTable.id)).append("\"")
				.append(pReport.editable ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.apply")))
				.append("</button>");
			if (!pReport.editable)
			{
				pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.notEditable"))).append("</span>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");

		// the language model
		if (pReport.llmError != null && !pReport.llmError.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.llm.error", pReport.llmError))).append("</div>");
		}
		else if (pReport.llmRan)
		{
			// wizard-visual-pass ROUND 3 (2026-09-24): four independent counted nouns in one sentence - each
			// pluralized on its own with countWords, then dropped into the message's own {n} slots as an
			// already-formed phrase (never a bare number + "(s)").
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.llm.ran",
				WizardPage.countWords(pLocale, pReport.llmSteps, "wizard.unit.step"), WizardPage.countWords(pLocale, pReport.llmInputTokens, "wizard.unit.token"),
				WizardPage.countWords(pLocale, pReport.llmOutputTokens, "wizard.unit.token"), WizardPage.countWords(pLocale, pReport.sampleRows.size(), "wizard.unit.row"))))
				.append("</p>");
			if (pReport.llmNoOutput)
			{
				pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.llm.noOutput", WizardPage.orDash(pReport.llmFinalText)))).append("</div>");
			}
		}
		else if (pReport.llmSkippedReasonKey != null)
		{
			pOut.append("<p class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.llm.skipped", WizardMessages.get(pLocale, pReport.llmSkippedReasonKey)))).append("</p>");
		}

		// the evidence, one row per column of the table
		if (!pReport.columns.isEmpty())
		{
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-key-evidence\"><thead><tr><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.col.column"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.col.type"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.col.values"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.col.distinct"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.col.unique"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.col.proposed"))).append("</th></tr></thead><tbody>");
			for (final KeyStudyReport.ColumnRow column : pReport.columns)
			{
				final boolean proposed = pReport.proposal != null && pReport.proposal.contains(column.name);
				pOut.append("<tr").append(proposed ? " class=\"mdmf-key-proposed\"" : "").append("><td><b>").append(WizardPage.esc(column.name)).append("</b>")
					.append(column.inCurrentKey ? " <span class=\"mdmf-chip mdmf-chip-real\">" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.current")) + "</span>" : "")
					.append("</td><td class=\"mdmf-hint\">").append(WizardPage.esc(WizardPage.orDash(column.dataType))).append("</td>");
				final KeyEvidence.Column evidence = column.evidence;
				if (evidence == null)
				{
					pOut.append("<td class=\"mdmf-hint\" colspan=\"3\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.notInSample"))).append("</td>");
				}
				else
				{
					pOut.append("<td class=\"mdmf-key-values\">");
					for (int i = 0; i < evidence.values.size(); i++)
					{
						pOut.append(i > 0 ? " " : "").append("<code>").append(WizardPage.esc(evidence.values.get(i))).append("</code>");
					}
					if (evidence.values.isEmpty())
					{
						pOut.append("&mdash;");
					}
					pOut.append("</td><td class=\"num\">").append(evidence.distinct).append(" / ").append(evidence.empty).append("</td><td>")
						.append(evidence.uniqueInSample() ? "&#10003;" : "&mdash;").append("</td>");
				}
				pOut.append("<td>").append(proposed ? "&#10003;" : "").append("</td></tr>");
			}
			pOut.append("</tbody></table></div>");
		}

		// the sample itself
		if (!pReport.sampleRows.isEmpty())
		{
			pOut.append("<h4 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.keyStudy.sample", Integer.valueOf(pReport.sampleRows.size())))).append("</h4>");
			WizardPage.renderSampleTable(pOut, pReport.sampleColumns, pReport.sampleRows);
		}
		pOut.append("</div>");
	}

	private static void renderRelationRow(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.RelationInfo pRelation)
	{
		pOut.append("<tr><td><code>").append(WizardPage.esc(pRelation.fromTable)).append("</code> &rarr; <code>").append(WizardPage.esc(pRelation.toTable))
			.append("</code>");
		if (pRelation.selfReference)
		{
			pOut.append(" <span class=\"mdmf-hint\">(").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.selfReference"))).append(")</span>");
		}
		pOut.append("</td><td><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + pRelation.origin)))
			.append("</span></td><td>");
		if (pRelation.confidence == null)
		{
			pOut.append("&mdash;");
		}
		else
		{
			pOut.append("<span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pRelation.confidence).append("%\"></i></span>")
				.append(pRelation.confidence).append(" %</span>");
		}
		pOut.append("</td>");
		WizardPage.renderDecideCell(pOut, pLocale, "RELATION", pRelation.id, pRelation.status);
		pOut.append("</tr>");
	}

	private static void renderTrack(final StringBuilder pOut, final Locale pLocale, final List<WizardSnapshot.PipelineStepInfo> pSteps)
	{
		pOut.append("<div class=\"mdmf-track\">");
		for (int i = 0; i < pSteps.size(); i++)
		{
			final WizardSnapshot.PipelineStepInfo step = pSteps.get(i);
			final String cls = "DONE".equals(step.status) ? "mdmf-track-done" : "FAILED".equals(step.status) ? "mdmf-track-fail"
				: "RUNNING".equals(step.status) ? "mdmf-track-now" : "mdmf-track-todo";
			pOut.append("<div class=\"mdmf-track-step ").append(cls).append("\"><i aria-hidden=\"true\"></i><b>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.pipelineStep." + step.step))).append("</b>");
			// long-passes-visible lot (2026-09-23): a RUNNING step names how long it has been running, so a
			// person who navigates away and back sees a still-genuinely-in-progress pass for what it is instead
			// of reading "Running" (unchanged for seconds or for many minutes) as "probably did nothing"
			// (the product owner, project Personnes: clicked "Suggest script constraints", saw no result after the page
			// re-rendered, concluded it proposed nothing - it was still running, 4 minutes in).
			final String runningSince = "RUNNING".equals(step.status) ? WizardPage.runningSinceText(pLocale, step) : null;
			if (runningSince != null)
			{
				pOut.append("<small>").append(WizardPage.esc(runningSince)).append("</small>");
			}
			else if (!step.message.isEmpty())
			{
				pOut.append("<small>").append(WizardPage.esc(step.message)).append("</small>");
			}
			pOut.append("</div>");
			if (i < pSteps.size() - 1)
			{
				pOut.append("<span class=\"mdmf-track-rail").append("DONE".equals(step.status) ? " mdmf-track-rail-on" : "").append("\" aria-hidden=\"true\"></span>");
			}
		}
		pOut.append("</div>");
	}

	/**
	 * Long-passes-visible lot (2026-09-23): null unless {@code pStep} is genuinely {@code RUNNING} with a known
	 * {@code startedAtMillis} - a person who navigates away and back must see a still-genuinely-in-progress
	 * pass for what it is instead of reading a bare "Running" (unchanged for seconds or for many minutes) as
	 * "probably did nothing" (the product owner, project Personnes: clicked "Suggest script constraints", saw nothing
	 * after the page re-rendered, concluded it proposed nothing - it was still running, 4 minutes in). Shared
	 * by {@link #renderTrack} (project-level steps) and {@link #renderMatchPlan} (the per-entity MATCHING_RUN
	 * step, not part of any track).
	 */
	private static String runningSinceText(final Locale pLocale, final WizardSnapshot.PipelineStepInfo pStep)
	{
		if (pStep.startedAtMillis <= 0)
		{
			return null;
		}
		final long minutes = Math.max(0, (System.currentTimeMillis() - pStep.startedAtMillis) / 60000L);
		return WizardMessages.get(pLocale, "wizard.track.runningSince", Long.valueOf(minutes));
	}

	// --------------------------------------------------------------------------------------------------- step 4: staging

	private static void renderStep4(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"4\"").append(WizardPage.hiddenAttr(pModel, 4)).append(">");
		final boolean targetExists = pModel.snapshot.project != null && "TARGET_EXISTS".equals(pModel.snapshot.project.mode);
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step4.title"),
			WizardMessages.get(pLocale, targetExists ? "wizard.step4.lead.targetExists" : "wizard.step4.lead.greenfield"));
		if (targetExists)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.notYet"))).append("</div>");
			WizardPage.renderStepReset(pOut, pModel, pLocale, "STAGING");
			pOut.append("</section>");
			return;
		}
		final WizardRules.ActionGates gates = WizardRules.actionGates(pModel.snapshot, pModel.designer);
		WizardPage.render2ColStart(pOut);

		// UX lot U4 (2026-10-13, RAPPORT 2.7 / 2.9 / 3.4): ONE primary button, decided by WizardRules#step4Primary - Propose, then Review N points, then Build and
		// load, then Continue to the master - in the primary block right under the intro; every block whose moment has not come is simply not written (the build
		// block with its two excuses, the Apply buttons before the staging exists), and the block says what comes next. staging-proposal-run lot (2026-09-24): the
		// pre-existing per-entity cards and separate suggest buttons (STAGING_SUGGEST/STAGING_SUGGEST_LLM/quality/capability) stay under the "Advanced"
		// disclosure below, kept fully working - re-running one pass on its own is still sometimes exactly what is needed.
		final WizardRules.Step4Primary primary = WizardRules.step4Primary(pModel.snapshot, pModel.proposalRunRunning, pModel.stagingLoadRunRunning);
		WizardPage.renderStep4Top(pOut, pModel, gates, pLocale, primary);

		pOut.append("<details class=\"mdmf-disclosure\" id=\"mdmf-step4-passes\"").append(primary == WizardRules.Step4Primary.REVIEW && pModel.proposalDoneMatrixUrl.isEmpty() ? " open=\"open\"" : "")
			.append("><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.advanced"))).append("</summary>");
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_SUGGEST\"")
			.append(gates.suggestStaging.enabled ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.suggest")))
			.append("</button>");
		if (!gates.suggestStaging.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, gates.suggestStaging.reasonKey))).append("</span>");
		}
		else if (pModel.designer && pModel.snapshot.llmConfigured)
		{
			// staging-llm-chained lot (2026-09-23): ProjectWizardUserService.suggestStaging runs the language-model pass in
			// the same click whenever its own gate allows it - say so next to the button, since the disclosure below
			// otherwise reads as if only the second button ever reaches the model
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.suggest.llmChained"))).append("</span>");
		}
		pOut.append("</div>");

		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardPage.llmDisclosureText(pModel, pLocale)))
			.append("</p><div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_SUGGEST_LLM\"").append(gates.suggestStagingLlm.enabled ? "" : " disabled=\"disabled\"")
			.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.suggestLlm"))).append("</button>");
		if (!gates.suggestStagingLlm.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, gates.suggestStagingLlm.reasonKey))).append("</span>");
		}
		pOut.append("</div>");
		if (pModel.llmRunSummary != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(WizardPage.esc(pModel.llmRunSummary)).append("</div>");
		}
		pOut.append("</div>");

		if (pModel.snapshot.stagingEntities.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.empty"))).append("</div>");
		}
		else
		{
			pOut.append("<div class=\"mdmf-actions\">");
			pOut.append("<label class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.bulk.threshold")))
				.append(" <input type=\"number\" id=\"mdmfBulkThresholdInput\" min=\"0\" max=\"100\" style=\"width:64px\" value=\"")
				.append(pModel.stagingBulkThreshold).append("\"/> %</label>");
			// threshold-left-out lot (2026-09-23): the button label itself names the live threshold value
			// (product report: "Accept all" looked broken because a below-threshold row was silently left out -
			// the button never said what "all" actually meant) - wizard.js keeps every .mdmf-bulk-threshold-value
			// span in step with the number input above as the person types, no round trip needed for that alone.
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_BULK_ACCEPT\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.bulk.accept"))).append(' ')
				.append(WizardPage.thresholdSuffix(pModel.stagingBulkThreshold)).append("</button>");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"STAGING_BULK_REJECT\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.bulk.reject"))).append(' ')
				.append(WizardPage.thresholdSuffix(pModel.stagingBulkThreshold)).append("</button>");
			if (gates.reopenMappings.enabled)
			{
				// bulk-mapping-cascade lot (2026-09-23, coordinator finding): only shown once there is something
				// to reopen (see WizardRules.ActionGates#reopenMappings) - an explicit, visible action rather than
				// a silent part of the ordinary bulk-accept, see WizardData.reopenOrphanedMappings' own Javadoc
				// for why.
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_REOPEN_MAPPINGS\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.reopenMappings"))).append("</button>");
			}
			pOut.append("</div>");
			// wave 11 (audit F-42): beyond ENTITY_FOLD_THRESHOLD business objects (or while a filter is set) a card is written in full only when it is open
			final int visibleEntities = WizardPage.visibleEntityCount(pModel);
			final boolean folding = WizardPage.entityFolding(pModel, visibleEntities);
			if (folding)
			{
				WizardPage.renderEntityFoldBar(pOut, pModel, pLocale, visibleEntities);
			}
			for (final WizardSnapshot.StagingEntityInfo entity : pModel.snapshot.stagingEntities)
			{
				// staging-focus lot (2026-09-23, the product owner on project A: "il faut simplifier l'ecran ... les
				// entites superseded ... ne doivent pas apparaitre"): a rule entity the language-model pass
				// absorbed into a canonical one is a technical bookkeeping detail (StagingLlmSuggester
				// .supersedeMembers), not part of the structure a reviewer is validating here - skip its card
				// entirely, not just gray it out. It stays in the snapshot and fully visible on the Proposal
				// review screen (ReviewLine-based, a different reader) for anyone who wants to bring it back.
				if (ReviewLine.SUPERSEDED.equals(entity.status))
				{
					continue;
				}
				if (folding && !WizardPage.entityOpen(pModel, entity))
				{
					WizardPage.renderStagingEntityFolded(pOut, pModel, pLocale, entity);
					continue;
				}
				WizardPage.renderStagingEntity(pOut, pModel, pLocale, entity, folding);
			}
		}

		WizardPage.renderQualityAndCapabilityBlock(pOut, pModel, gates, pLocale);
		pOut.append("</details>");
		// the separate Generate / Load buttons, folded (the one-click build is in the primary block)
		pOut.append("<details class=\"mdmf-disclosure\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.advanced"))).append("</summary>");
		WizardPage.renderStagingGenerateLoadAdvanced(pOut, pModel, gates, pLocale);
		pOut.append("</details>");
		WizardPage.render2ColSplit(pOut);
		WizardPage.renderStep4SideColumn(pOut, pModel, pLocale);
		WizardPage.render2ColEnd(pOut);
		// UX lot U4: "Clean this step and the following ones" closes the step (secondary, red outline, one-line warning) - never above the content
		WizardPage.renderStepReset(pOut, pModel, pLocale, "STAGING");
		pOut.append("</section>");
	}

	/**
	 * ROUND 3 of the wizard-visual-pass lot (2026-09-24, board {@code StagingLoad.dc.html}): "Your
	 * decisions" - one row per nature from {@link Step4Counts} (UX lot U3: the numbers the "Proposal ready for review" block and its waiting
	 * line use too), plus the glossary.
	 */
	private static void renderStep4SideColumn(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// UX lot U3 (2026-10-13): one row per nature from Step4Counts - the same numbers as the "Proposal ready for review" block and its waiting line
		// (they used to be three different tallies, the block's never updated after a decision; fix/wizard-live-wave1 and the 2026-09-27 polish
		// had already split accepted from proposed here: that split is now the rule everywhere)
		final Step4Counts counts = Step4Counts.of(pModel.snapshot);
		final boolean stillCard = StillToDecide.of(pModel.snapshot).total() > 0;
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.step4.side.decisions.title");
		if (counts.businessObjects.total() == 0)
		{
			pOut.append("<div>").append(WizardPage.esc(WizardPage.plural(pLocale, 0, "wizard.step4.side.decisions.proposed.one",
				"wizard.step4.side.decisions.proposed.other"))).append("</div>");
		}
		else
		{
			pOut.append("<ul class=\"mdmf-still-tally mdmf-step4-counts\">");
			for (final String key : counts.keys())
			{
				final Step4Counts.Row row = counts.row(key);
				if (row.total() == 0)
				{
					continue;
				}
				pOut.append("<li><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.counts." + key))).append("</b> ").append(row.total()).append(": ");
				final List<String> parts = new ArrayList<String>();
				if (row.accepted > 0)
				{
					parts.add(WizardMessages.get(pLocale, "wizard.step4.counts.accepted", Integer.valueOf(row.accepted)));
				}
				if (row.setAside > 0)
				{
					parts.add(WizardMessages.get(pLocale, "wizard.step4.counts.setAside", Integer.valueOf(row.setAside)));
				}
				if (row.rejected > 0)
				{
					parts.add(WizardMessages.get(pLocale, "wizard.step4.counts.rejected", Integer.valueOf(row.rejected)));
				}
				if (row.optionalWaiting > 0)
				{
					// staging-screens lot (2026-10-17): look-up suggestions still open are optional - said so, never a "to review" chip that reads like a block
					parts.add(WizardPage.plural(pLocale, row.optionalWaiting, "wizard.step4.counts.optional.one", "wizard.step4.counts.optional.other"));
				}
				for (int i = 0; i < parts.size(); i++)
				{
					pOut.append(i == 0 ? "" : ", ").append(WizardPage.esc(parts.get(i)));
				}
				if (row.waiting > 0)
				{
					final String waiting = WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.counts.waiting", Integer.valueOf(row.waiting)));
					pOut.append(parts.isEmpty() ? "" : ", ");
					// the "decisions left" card gathers the waiting rows of the accepted business objects: link to it when it is on the page
					pOut.append(stillCard ? "<a class=\"mdmf-chip mdmf-chip-target\" href=\"#mdmf-still-to-decide\">" + waiting + "</a>" : waiting);
				}
				pOut.append("</li>");
			}
			pOut.append("</ul>");
		}
		if (!pModel.proposalDoneMatrixUrl.isEmpty())
		{
			pOut.append("<a class=\"mdmf-feed-matrix-link\" href=\"").append(WizardPage.esc(pModel.proposalDoneMatrixUrl)).append("\" target=\"_top\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.side.decisions.backToMatrix"))).append("</a>");
		}
		WizardPage.renderSideCardClose(pOut);
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.step4.glossary.businessObject", "wizard.step4.glossary.field", "wizard.step4.glossary.sourceLink",
			"wizard.step4.glossary.rule", "wizard.step4.glossary.check", "wizard.step4.glossary.setAside", "wizard.step4.glossary.heldBack", "wizard.step4.glossary.staging");
	}

	/**
	 * Staging-still-to-decide lot (2026-09-27, validated mockup artboard "Main"; measured live on project ret):
	 * the "N decisions left" card (G) - every item {@link StillToDecide} finds still {@code PROPOSED} under the
	 * accepted business objects, grouped by nature, each with its decision controls in place, right above the
	 * "Fill the shared structure" button. Not rendered at all when nothing is left (a card saying "0 left" is
	 * noise). Every control is an existing action ({@code DECIDE_ACCEPT}/{@code DECIDE_REJECT}, same handler as
	 * the entity cards) except "Set aside" ({@code CAPABILITY_SET_ASIDE}, this lot) - a person's decision is
	 * never taken for them: nothing here decides anything on its own, and "Set aside" only ever moves a row
	 * that is still {@code PROPOSED} ({@code WizardData.setCapabilitiesAside}).
	 */
	private static void renderStillToDecide(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final StillToDecide still = StillToDecide.of(pModel.snapshot);
		final int total = still.total();
		if (total == 0)
		{
			return;
		}
		// UX lot U4 (2026-10-13, RAPPORT 2.9): an accepted derivation can only be applied once the staging model exists (its constraint targets it): before that
		// the card says "will be applied after the first build" instead of offering buttons that answer "Generate the staging zone first" in a banner out of sight
		final boolean stagingExists = WizardRules.stagingExists(pModel.snapshot);
		// staging-screens lot (2026-10-17): the title counts what a decision is needed for; the optional look-up suggestions are listed below but never counted there
		final int blocking = still.blockingTotal();
		pOut.append("<div class=\"mdmf-panel mdmf-still\" id=\"mdmf-still-to-decide\"><div class=\"mdmf-still-head\"><h3 class=\"mdmf-subtitle\">")
			.append(WizardPage.esc(blocking > 0 ? WizardPage.plural(pLocale, blocking, "wizard.step4.still.title.one", "wizard.step4.still.title.other")
				: WizardPage.plural(pLocale, still.optionalTotal(), "wizard.step4.still.title.optional.one", "wizard.step4.still.title.optional.other")))
			.append("</h3>");
		if (!pModel.proposalDoneDesignerUrl.isEmpty())
		{
			pOut.append("<a class=\"mdmf-btn mdmf-btn-sm mdmf-feed-designer-link\" href=\"").append(WizardPage.esc(pModel.proposalDoneDesignerUrl)).append("\" target=\"_top\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.openDesigner"))).append("</a>");
		}
		pOut.append("</div><p class=\"mdmf-lead\">").append(WizardPage.esc(WizardMessages.get(pLocale, blocking > 0 ? "wizard.step4.still.lead" : "wizard.step4.still.lead.optional")))
			.append("</p>");

		if (!still.fields.isEmpty())
		{
			WizardPage.renderStillGroupOpen(pOut, pLocale, "fields", "wizard.step4.still.group.fields", still.fields.size(), null);
			int shown = 0;
			final int limit = WizardPage.stillLimit(pModel, "fields", still.fields.size());
			for (final StillToDecide.FieldItem item : still.fields)
			{
				if (shown++ >= limit)
				{
					break;
				}
				WizardPage.renderStillField(pOut, pLocale, item);
			}
			WizardPage.renderStillMore(pOut, pModel, pLocale, "fields", still.fields.size(), limit);
			pOut.append("</ul></div>");
		}
		if (!still.rules.isEmpty())
		{
			WizardPage.renderStillGroupOpen(pOut, pLocale, "rules", "wizard.step4.still.group.rules", still.rules.size(), null);
			int shown = 0;
			final int limit = WizardPage.stillLimit(pModel, "rules", still.rules.size());
			for (final StillToDecide.RuleItem item : still.rules)
			{
				if (shown++ >= limit)
				{
					break;
				}
				WizardPage.renderStillRule(pOut, pLocale, item);
			}
			WizardPage.renderStillMore(pOut, pModel, pLocale, "rules", still.rules.size(), limit);
			pOut.append("</ul></div>");
		}
		if (!still.checks.isEmpty())
		{
			// "Set all N aside" only when at least two services cannot run as they stand - one is its own button
			final int cannotRun = still.servicesThatCannotRun();
			final String groupAction = cannotRun < 2 ? null
				: "<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"CAPABILITY_SET_ASIDE\" data-mdmf-id=\""
					+ WizardPage.esc(still.idsOfServicesThatCannotRun()) + "\">"
					+ WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.setAllAside", Integer.valueOf(cannotRun))) + "</button>";
			WizardPage.renderStillGroupOpen(pOut, pLocale, "checks", "wizard.step4.still.group.checks", still.checks.size(), groupAction);
			int shown = 0;
			final int limit = WizardPage.stillLimit(pModel, "checks", still.checks.size());
			for (final StillToDecide.CheckItem item : still.checks)
			{
				if (shown++ >= limit)
				{
					break;
				}
				WizardPage.renderStillCheck(pOut, pLocale, item);
			}
			WizardPage.renderStillMore(pOut, pModel, pLocale, "checks", still.checks.size(), limit);
			pOut.append("</ul></div>");
		}
		if (!still.toApply.isEmpty())
		{
			// lot M5: accepted script derivations that nothing computes yet - the attribute stays empty until Apply
			WizardPage.renderStillGroupOpen(pOut, pLocale, "toApply", "wizard.step4.still.group.toApply", still.toApply.size(), null);
			pOut.append("<li class=\"mdmf-still-item\"><div class=\"mdmf-still-why\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, stagingExists ? "wizard.step4.still.toApply.lead" : "wizard.step4.still.toApply.leadBeforeBuild"))).append("</div></li>");
			int shown = 0;
			final int limit = WizardPage.stillLimit(pModel, "toApply", still.toApply.size());
			for (final StillToDecide.CheckItem item : still.toApply)
			{
				if (shown++ >= limit)
				{
					break;
				}
				WizardPage.renderStillToApply(pOut, pLocale, item, stagingExists);
			}
			WizardPage.renderStillMore(pOut, pModel, pLocale, "toApply", still.toApply.size(), limit);
			pOut.append("</ul></div>");
		}
		if (!still.links.isEmpty())
		{
			WizardPage.renderStillGroupOpen(pOut, pLocale, "links", "wizard.step4.still.group.links", still.links.size(), null);
			int shown = 0;
			final int limit = WizardPage.stillLimit(pModel, "links", still.links.size());
			for (final StillToDecide.LinkItem item : still.links)
			{
				if (shown++ >= limit)
				{
					break;
				}
				WizardPage.renderStillLink(pOut, pLocale, item);
			}
			WizardPage.renderStillMore(pOut, pModel, pLocale, "links", still.links.size(), limit);
			pOut.append("</ul></div>");
		}
		pOut.append("</div>");
	}

	/** Wave 11 (audit F-42): a group of the "decisions left" card writes at most this many items until a person asks for the others ({@code STAGING_STILL_MORE}). */
	public static final int STILL_GROUP_LIMIT = 25;

	/** How many items of a group of {@code pSize} are written: all of them when the group is small or was opened, else {@link #STILL_GROUP_LIMIT}. */
	static int stillLimit(final Model pModel, final String pGroup, final int pSize)
	{
		return pSize <= WizardPage.STILL_GROUP_LIMIT || pModel.stillOpenGroups.contains(pGroup) ? pSize : WizardPage.STILL_GROUP_LIMIT;
	}

	/** The last row of a group that is cut (or opened after being cut): how many of how many are shown, and the button that shows the others or folds them again. */
	private static void renderStillMore(final StringBuilder pOut, final Model pModel, final Locale pLocale, final String pGroup, final int pSize, final int pShown)
	{
		if (pSize <= WizardPage.STILL_GROUP_LIMIT)
		{
			return;
		}
		final boolean cut = pShown < pSize;
		pOut.append("<li class=\"mdmf-still-item mdmf-still-more\"><div class=\"mdmf-still-main\"><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, cut ? "wizard.step4.still.more.cut" : "wizard.step4.still.more.all", Integer.valueOf(pShown), Integer.valueOf(pSize))))
			.append("</span></div><div class=\"mdmf-still-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"")
			.append(cut ? "STAGING_STILL_MORE" : "STAGING_STILL_LESS").append("\" data-mdmf-id=\"").append(pGroup).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, cut ? "wizard.step4.still.more.show" : "wizard.step4.still.more.fold", Integer.valueOf(pSize - pShown))))
			.append("</button></div></li>");
	}

	/** One group of {@link #renderStillToDecide}: title, count chip, optional group-level button; leaves a {@code <ul>} open for its items. */
	private static void renderStillGroupOpen(final StringBuilder pOut, final Locale pLocale, final String pGroup, final String pTitleKey, final int pCount,
		final String pGroupActionHtml)
	{
		pOut.append("<div class=\"mdmf-still-group\" data-mdmf-still-group=\"").append(pGroup).append("\"><div class=\"mdmf-still-group-head\"><h4 class=\"mdmf-still-group-title\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pTitleKey))).append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(pCount).append("</span></h4>");
		if (pGroupActionHtml != null)
		{
			pOut.append(pGroupActionHtml);
		}
		pOut.append("</div><ul class=\"mdmf-still-list\">");
	}

	/** Item opening: stable id (same nature+id convention as every decidable row, so a decision's repaint can anchor on it), entity chip, title. */
	private static void renderStillItemOpen(final StringBuilder pOut, final String pNature, final String pId, final String pEntityName, final String pTitleHtml)
	{
		pOut.append("<li class=\"mdmf-still-item\" id=\"mdmf-still-").append(pNature).append('-').append(WizardPage.esc(pId))
			.append("\"><div class=\"mdmf-still-main\"><div class=\"mdmf-still-title\"><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(pEntityName))
			.append("</span> ").append(pTitleHtml).append("</div>");
	}

	private static void renderStillWhy(final StringBuilder pOut, final String pText)
	{
		if (pText != null && !pText.isEmpty())
		{
			pOut.append("<div class=\"mdmf-still-why\">").append(WizardPage.esc(pText)).append("</div>");
		}
	}

	private static void renderStillConfidenceChip(final StringBuilder pOut, final Locale pLocale, final Integer pConfidence)
	{
		if (pConfidence != null)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.confidence", Integer.valueOf(Math.max(0, Math.min(100, pConfidence.intValue()))))))
				.append("</span>");
		}
	}

	/**
	 * Accept/Reject for one item - the SAME {@code DECIDE_ACCEPT}/{@code DECIDE_REJECT} actions the entity cards
	 * post, never a new handler. {@code pCarryRules} adds {@code data-mdmf-carry-rules} on Accept only (a field
	 * whose quality rules this card shows as "decided with the field" - see {@code ProposalReviews.decide}); the
	 * Reject direction needs no flag, rejecting a field already rejects its PROPOSED rules.
	 */
	private static void renderStillAcceptReject(final StringBuilder pOut, final Locale pLocale, final String pNature, final String pId, final boolean pCarryRules)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-accept\" data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"").append(pNature)
			.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\"").append(pCarryRules ? " data-mdmf-carry-rules=\"true\"" : "").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.accept"))).append("</button>");
		pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"").append(pNature)
			.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.reject"))).append("</button>");
	}

	private static void renderStillField(final StringBuilder pOut, final Locale pLocale, final StillToDecide.FieldItem pItem)
	{
		final StringBuilder title = new StringBuilder();
		if (!pItem.label.isEmpty() && !pItem.label.equals(pItem.name))
		{
			title.append("<b>").append(WizardPage.esc(pItem.label)).append("</b> ");
		}
		title.append("<code>").append(WizardPage.esc(pItem.name)).append("</code>");
		WizardPage.renderStillItemOpen(pOut, ReviewLine.ATTRIBUTE, pItem.attributeId, pItem.entityName, title.toString());
		WizardPage.renderStillWhy(pOut, pItem.rationale);
		pOut.append("<div class=\"mdmf-still-chips\">");
		for (final String source : pItem.sources)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-real\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.fedBy", source))).append("</span>");
		}
		WizardPage.renderStillConfidenceChip(pOut, pLocale, pItem.confidence);
		pOut.append("</div>");
		if (pItem.linksAccepted)
		{
			pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.linksAccepted"))).append("</div>");
		}
		if (pItem.carriedRules > 0)
		{
			pOut.append("<div class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardPage.plural(pLocale, pItem.carriedRules, "wizard.step4.still.carriesRules.one", "wizard.step4.still.carriesRules.other")))
				.append("</div>");
		}
		pOut.append("</div><div class=\"mdmf-still-actions\">");
		WizardPage.renderStillAcceptReject(pOut, pLocale, ReviewLine.ATTRIBUTE, pItem.attributeId, pItem.carriedRules > 0);
		pOut.append("</div></li>");
	}

	private static void renderStillRule(final StringBuilder pOut, final Locale pLocale, final StillToDecide.RuleItem pItem)
	{
		WizardPage.renderStillItemOpen(pOut, ReviewLine.QUALITY, pItem.rowId, pItem.entityName,
			"<b>" + WizardPage.esc(pItem.proposition) + "</b> <code>" + WizardPage.esc(pItem.element) + "</code>");
		pOut.append("<div class=\"mdmf-still-chips\">");
		WizardPage.renderStillSeverityChip(pOut, pLocale, pItem.severity);
		if (pItem.valuesSeen != null)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.valuesSeen", pItem.valuesSeen)))
				.append("</span>");
		}
		if (pItem.valuesFailing != null)
		{
			pOut.append("<span class=\"mdmf-chip ").append(pItem.valuesFailing.longValue() > 0 ? "mdmf-chip-bad" : "mdmf-chip-real").append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.valuesFailing", pItem.valuesFailing))).append("</span>");
		}
		WizardPage.renderStillConfidenceChip(pOut, pLocale, pItem.confidence);
		pOut.append("</div>");
		WizardPage.renderStillWhy(pOut, pItem.rationale);
		pOut.append("</div><div class=\"mdmf-still-actions\">");
		if (!pItem.decidedWithField.isEmpty())
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-target mdmf-still-with-field\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.decidedWithField", pItem.decidedWithField))).append("</span>");
		}
		else
		{
			WizardPage.renderStillAcceptReject(pOut, pLocale, ReviewLine.QUALITY, pItem.rowId, false);
		}
		pOut.append("</div></li>");
	}

	/**
	 * A check ({@code SCRIPT_CONSTRAINT}/{@code SCRIPT_DERIVATION}: plain Accept/Reject - accepting one applies it,
	 * same as everywhere else) or a connector service ({@code EXTERNAL_SERVICE}: readiness chips, pricing, then
	 * "Set aside" and, when it names a connector, "Configure" - a link to that connector in Connectors,
	 * {@link #connectorRecordUrl}, the same route step 2's onboarding panel opens). A service is never offered
	 * Accept here: it is advice a person follows up in Connectors, not something the staging build uses.
	 */
	/** Polish lot (2026-09-27): the severity lifted out of a proposition ({@code StillToDecide#splitSeverity}), in the words the quality block already uses. */
	private static void renderStillSeverityChip(final StringBuilder pOut, final Locale pLocale, final String pSeverity)
	{
		if (pSeverity == null || pSeverity.isEmpty())
		{
			return;
		}
		pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.quality.severity." + pSeverity))).append("</span>");
	}

	private static void renderStillCheck(final StringBuilder pOut, final Locale pLocale, final StillToDecide.CheckItem pItem)
	{
		WizardPage.renderStillItemOpen(pOut, pItem.nature, pItem.rowId, pItem.entityName,
			"<b>" + WizardPage.esc(pItem.proposition) + "</b> <code>" + WizardPage.esc(pItem.element) + "</code>");
		pOut.append("<div class=\"mdmf-still-chips\"><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.nature." + pItem.nature))).append("</span>");
		WizardPage.renderStillSeverityChip(pOut, pLocale, pItem.severity);
		if (pItem.secretMissing)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-bad\"").append(pItem.readinessDetail.isEmpty() ? "" : " title=\"" + WizardPage.esc(pItem.readinessDetail) + "\"")
				.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.secretMissing"))).append("</span>");
		}
		if (pItem.queryToWrite)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-bad\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.queryToWrite"))).append("</span>");
		}
		if (!pItem.pricing.isEmpty())
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-target\"").append(pItem.pricingNote.isEmpty() ? "" : " title=\"" + WizardPage.esc(pItem.pricingNote) + "\"")
				.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.pricing." + pItem.pricing))).append("</span>");
		}
		WizardPage.renderStillConfidenceChip(pOut, pLocale, pItem.confidence);
		final List<String> needsParameter = DerivationParamGate.neededBeforeAccept(pItem);
		if (!needsParameter.isEmpty())
		{
			// lot G1: said BEFORE the person accepts - accepting does not compute anything until the parameter is given
			pOut.append("<span class=\"mdmf-chip mdmf-chip-bad\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.check.needsParam.chip"))).append("</span>");
		}
		pOut.append("</div>");
		if (!needsParameter.isEmpty())
		{
			pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.check.needsParam", String.join(", ", needsParameter)))).append("</div>");
		}
		if (pItem.secretMissing && !pItem.readinessDetail.isEmpty())
		{
			pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(pItem.readinessDetail)).append("</div>");
		}
		WizardPage.renderStillWhy(pOut, pItem.rationale);
		pOut.append("</div><div class=\"mdmf-still-actions\">");
		if (pItem.isService())
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"CAPABILITY_SET_ASIDE\" data-mdmf-id=\"").append(WizardPage.esc(pItem.rowId))
				.append("\" title=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.setAside.hint"))).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.setAside"))).append("</button>");
			if (!pItem.connectorId.isEmpty())
			{
				pOut.append(" <a class=\"mdmf-btn mdmf-btn-sm mdmf-leave-link\" href=\"").append(WizardPage.esc(WizardPage.connectorRecordUrl(pItem.connectorId)))
					.append("\" target=\"_top\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.configure"))).append("</a>");
			}
		}
		else
		{
			WizardPage.renderStillAcceptReject(pOut, pLocale, pItem.nature, pItem.rowId, false);
		}
		pOut.append("</div></li>");
	}

	/**
	 * Lot P1 / lot G1: the inputs of the parameters a person may settle for one derivation and the ONE "Save parameters" button ({@code DERIVATION_SET_PARAMS}, read by
	 * {@code wizard.js} through {@code data-mdmf-param-box}). Used by the "To apply" list ({@code pWithLead}) and, since lot G1, by the block at the top of step 4 (the same
	 * derivation may be in both, so the box id is the caller's).
	 */
	private static void renderParamBox(final StringBuilder pOut, final Locale pLocale, final StillToDecide.CheckItem pItem, final String pBoxId, final boolean pWithLead)
	{
		pOut.append("<div class=\"mdmf-param-box\" id=\"").append(pBoxId).append("\">");
		if (pWithLead)
		{
			pOut.append("<div class=\"mdmf-still-why\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.toApply.params.lead"))).append("</div>");
		}
		int index = 0;
		for (final DerivationParameters.Settable parameter : pItem.parameters)
		{
			final String inputId = pBoxId + "-" + index++;
			pOut.append("<div class=\"mdmf-param-row\"><label for=\"").append(inputId).append("\"><code>").append(WizardPage.esc(parameter.name)).append("</code></label>");
			if (parameter.toBeProvided)
			{
				pOut.append(" <span class=\"mdmf-chip mdmf-chip-bad\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.toApply.param.toProvide"))).append("</span>");
				if (!parameter.note.isEmpty())
				{
					pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(parameter.note)).append("</span>");
				}
			}
			pOut.append(" <input type=\"text\" id=\"").append(inputId).append("\" class=\"mdmf-param-input\" data-param=\"").append(WizardPage.esc(parameter.name))
				.append("\" data-initial=\"").append(WizardPage.esc(parameter.value)).append("\" value=\"").append(WizardPage.esc(parameter.value)).append("\"/></div>");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DERIVATION_SET_PARAMS\" data-mdmf-id=\"").append(WizardPage.esc(pItem.rowId))
			.append("\" data-mdmf-param-box=\"").append(pBoxId).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.toApply.params.save")))
			.append("</button></div>");
	}

	/**
	 * Lot G1 (2026-10-17, item 3): the block of step 4's primary action card that asks for the parameter(s) of the ACCEPTED derivations that wait for one - shown whenever
	 * {@link DerivationParamGate#showTopBlock}. The build is not held back; the sentence says what stays empty until then.
	 */
	private static void renderPrimaryParamBlock(final StringBuilder pOut, final Locale pLocale, final List<StillToDecide.CheckItem> pWaiting)
	{
		pOut.append("<div class=\"mdmf-primary-params\" data-mdmf-primary-params=\"").append(pWaiting.size()).append("\"><b>")
			.append(WizardPage.esc(WizardPage.plural(pLocale, pWaiting.size(), "wizard.step4.paramTop.title.one", "wizard.step4.paramTop.title.other"))).append("</b><span class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.paramTop.lead"))).append("</span>");
		for (final StillToDecide.CheckItem item : pWaiting)
		{
			pOut.append("<div class=\"mdmf-primary-param-item\"><span><b>").append(WizardPage.esc(item.proposition)).append("</b> <code>").append(WizardPage.esc(item.element))
				.append("</code></span>");
			WizardPage.renderParamBox(pOut, pLocale, item, "mdmf-params-top-" + WizardPage.esc(item.rowId), false);
			pOut.append("</div>");
		}
		pOut.append("</div>");
	}

	/**
	 * Lot M5: one accepted script derivation still to apply - its proposition and an Apply button (the wizard's own {@code APPLY} action, gated by the staging
	 * model like every apply). <b>Lot P1 (2026-10-04):</b> the parameters a person may settle (each {@code literal} / {@code toBeProvided} entry of the binding)
	 * are listed with a text input each and ONE "Save parameters" button for the derivation ({@code DERIVATION_SET_PARAMS}); Apply is replaced by its reason while a
	 * {@code toBeProvided} remains (the apply is refused server-side too, {@code DerivationApplier.prepare}); an applied derivation whose parameters changed since is
	 * listed with "parameters changed - apply again". Everything written into the page is escaped; an input carries its initial value so the script posts only what
	 * the person changed (a blank to-be-provided is never posted).
	 */
	private static void renderStillToApply(final StringBuilder pOut, final Locale pLocale, final StillToDecide.CheckItem pItem, final boolean pApplyNow)
	{
		WizardPage.renderStillItemOpen(pOut, pItem.nature, pItem.rowId, pItem.entityName,
			"<b>" + WizardPage.esc(pItem.proposition) + "</b> <code>" + WizardPage.esc(pItem.element) + "</code>");
		pOut.append("<div class=\"mdmf-still-chips\"><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.nature." + pItem.nature)))
			.append("</span><span class=\"mdmf-chip mdmf-chip-bad\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pItem.reapplyNeeded ? "wizard.step4.still.toApply.reapplyChip" : "wizard.step4.still.toApply.chip"))).append("</span></div>");
		final List<String> remaining = pItem.toBeProvidedRemaining();
		if (!pItem.parameters.isEmpty())
		{
			WizardPage.renderParamBox(pOut, pLocale, pItem, "mdmf-params-" + WizardPage.esc(pItem.rowId), true);
		}
		pOut.append("</div><div class=\"mdmf-still-actions\">");
		if (!pApplyNow)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.toApply.later"))).append("</span>");
		}
		else if (remaining.isEmpty())
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-accept\" data-mdmf-action=\"APPLY\" data-mdmf-nature=\"").append(pItem.nature)
				.append("\" data-mdmf-id=\"").append(WizardPage.esc(pItem.rowId)).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, pItem.reapplyNeeded ? "review.reapply" : "review.apply")))
				.append("</button>");
		}
		else
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-accept\" disabled=\"disabled\" data-mdmf-apply-blocked=\"toBeProvided\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.apply"))).append("</button> <span class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.toApply.blocked", String.join(", ", remaining)))).append("</span>");
		}
		pOut.append("</div></li>");
	}

	private static void renderStillLink(final StringBuilder pOut, final Locale pLocale, final StillToDecide.LinkItem pItem)
	{
		WizardPage.renderStillItemOpen(pOut, pItem.nature, pItem.rowId, pItem.entityName, "<code>" + WizardPage.esc(pItem.element) + "</code>");
		pOut.append("<div class=\"mdmf-still-chips\"><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.nature." + pItem.nature))).append("</span>");
		WizardPage.renderStillConfidenceChip(pOut, pLocale, pItem.confidence);
		pOut.append("</div>");
		WizardPage.renderStillWhy(pOut, pItem.proposition);
		pOut.append("</div><div class=\"mdmf-still-actions\">");
		WizardPage.renderStillAcceptReject(pOut, pLocale, pItem.nature, pItem.rowId, false);
		pOut.append("</div></li>");
	}

	/**
	 * Staging-proposal-run lot (2026-09-24, docs/mockups/staging-proposal/Main.dc.html): the plain-language intro of the one-click "Propose the staging"
	 * flow, written while nothing was proposed yet. The flow's states and its buttons are {@link #renderStep4Top} / {@link #renderStep4PrimaryBlock} (UX lot U4).
	 */
	private static void renderProposalIntro(final StringBuilder pOut, final Locale pLocale)
	{
		// staging-screens lot (2026-10-17): no title of its own (the page head says the same words) and no inline vocabulary (the side glossary holds it)
		pOut.append("<div class=\"mdmf-panel\"><p class=\"mdmf-lead\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.intro.lead"))).append("</p><ul class=\"mdmf-list\">");
		pOut.append("<li>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.intro.item.structure"))).append("</li>");
		pOut.append("<li>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.intro.item.quality"))).append("</li>");
		pOut.append("<li>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.intro.item.checks"))).append("</li></ul>");
		pOut.append("</div>");
	}

	/** The three checkboxes of the proposal run (ids read by wizard.js when STAGING_PROPOSAL_RUN is clicked) and the line saying what leaves the instance. */
	private static void renderProposalOptions(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" checked=\"checked\" disabled=\"disabled\"/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.checkbox.structure"))).append("</label>");
		pOut.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"mdmfProposalIncludeQualityCheckbox\"")
			.append(pModel.proposalRunIncludeQuality ? " checked=\"checked\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.checkbox.quality"))).append("</label>");
		pOut.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"mdmfProposalIncludeCapabilityCheckbox\"")
			.append(pModel.proposalRunIncludeCapability ? " checked=\"checked\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.checkbox.checks"))).append("</label>");
		pOut.append("</div><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardPage.llmDisclosureText(pModel, pLocale))).append("</p>");
	}

	/** True when the snapshot holds at least one business object that is not SUPERSEDED (the ones step 4 shows). */
	private static boolean hasVisibleBusinessObject(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!ReviewLine.SUPERSEDED.equals(entity.status))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * UX lot U4 (2026-10-13): everything above step 4's "Advanced" fold, in the order a person needs it - while the proposal runs its live list, while the build
	 * runs its live panel, otherwise the primary block ({@link #renderStep4PrimaryBlock}); then the "Proposal ready" counts (when a run exists), the waiting line
	 * (when no run wrote it into that block), the "decisions left" card and the held-back / grid result of the last build. The blocks that used to sit here
	 * whatever the state ("Fill the shared structure" with a greyed button and two excuses) are written only when their moment has come.
	 */
	private static void renderStep4Top(final StringBuilder pOut, final Model pModel, final WizardRules.ActionGates pGates, final Locale pLocale, final WizardRules.Step4Primary pPrimary)
	{
		final boolean hasObject = WizardPage.hasVisibleBusinessObject(pModel.snapshot);
		if (pPrimary == WizardRules.Step4Primary.RUNNING)
		{
			WizardPage.renderProposalRunning(pOut, pModel, pLocale);
		}
		else if (pPrimary == WizardRules.Step4Primary.LOADING)
		{
			WizardPage.renderStagingLive(pOut, pModel, pLocale);
		}
		else
		{
			// staging preflight lot H1 (2026-10-17): the check before the build, when it found something - the first thing the page says, above even the rows a build left out
			WizardPage.renderStep4Preflight(pOut, pModel, pLocale);
			// staging-screens lot (2026-10-17): rows the last build did not load are the first thing the page says (it used to be a banner far below the grid)
			WizardPage.renderStep4RejectsBanner(pOut, pModel, pLocale);
			// the one button of the step comes first; the explanation of a first proposal (what the run does) is read under it, never above it
			WizardPage.renderStep4PrimaryBlock(pOut, pModel, pGates, pLocale, pPrimary, hasObject);
			if (!hasObject)
			{
				WizardPage.renderProposalIntro(pOut, pLocale);
			}
		}
		if (pPrimary != WizardRules.Step4Primary.RUNNING && hasObject && pModel.proposalRunExists)
		{
			WizardPage.renderProposalDone(pOut, pModel, pLocale);
		}
		else if (pPrimary != WizardRules.Step4Primary.RUNNING)
		{
			WizardPage.renderWaitingForReviewHeader(pOut, pModel, pLocale);
		}
		if (pPrimary != WizardRules.Step4Primary.RUNNING)
		{
			// staging-still-to-decide lot (2026-09-27): every still-PROPOSED item of the accepted business objects - see renderStillToDecide's own Javadoc.
			WizardPage.renderStillToDecide(pOut, pModel, pLocale);
			WizardPage.renderStagingLoadResult(pOut, pModel, pLocale, pPrimary);
		}
	}

	private static void renderStep4Link(final StringBuilder pOut, final String pClass, final String pHref, final String pLabel)
	{
		pOut.append("<a class=\"mdmf-btn").append(pClass).append("\" href=\"").append(WizardPage.esc(pHref)).append("\" target=\"_top\">").append(WizardPage.esc(pLabel)).append("</a>");
	}

	/**
	 * Step 4's primary action block - the one green button of the step and what it leads to. {@code PROPOSE}: "Propose the staging" (its three checkboxes folded
	 * under "Options"); {@code REVIEW}: "Review N points" opening the Mapping matrix on the first business object (the Designer link beside it is secondary;
	 * without a matrix link it points at the per-object cards under "Advanced"); {@code BUILD}: "Build and load" (review links secondary); {@code CONTINUE}:
	 * "Continue to the master" ("Build again" and the review links secondary). Under it one sentence says what comes next, and the "Advanced" fold holds the
	 * other ways (build before the review is over, propose again, recompute every row).
	 */
	private static void renderStep4PrimaryBlock(final StringBuilder pOut, final Model pModel, final WizardRules.ActionGates pGates, final Locale pLocale,
		final WizardRules.Step4Primary pPrimary, final boolean pHasObject)
	{
		final boolean buildOk = pGates.generateStaging.enabled;
		final StringBuilder text = new StringBuilder();
		// fix/derivation-apply-backfill (2026-10-17, lot G1, item 3): accepted derivations that wait for a parameter only a person can give are asked for in THIS block
		final List<StillToDecide.CheckItem> waitingParams = DerivationParamGate.waiting(StillToDecide.of(pModel.snapshot).toApply);
		WizardPage.renderPrimaryActionBlockOpen(pOut);
		if (pPrimary == WizardRules.Step4Primary.PROPOSE)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"STAGING_PROPOSAL_RUN\" data-mdmf-proposal-checkboxes=\"1\"")
				.append(pGates.suggestStaging.enabled ? "" : " disabled=\"disabled\"").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.button"))).append("</button>");
			if (!pGates.suggestStaging.enabled)
			{
				text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.suggestStaging.reasonKey))).append("</span>");
			}
			else if (pHasObject)
			{
				text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.allRejected"))).append("</span>");
			}
			text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.next.propose"))).append("</span>");
		}
		else if (pPrimary == WizardRules.Step4Primary.REVIEW)
		{
			// UX lot C3: the button names the action; the figures (the matrix header's, and what goes with one click) are the line under it
			final String label = WizardMessages.get(pLocale, "wizard.step4.primary.review.button");
			if (!pModel.proposalDoneMatrixUrl.isEmpty())
			{
				WizardPage.renderStep4Link(pOut, " mdmf-btn-primary mdmf-btn-big mdmf-feed-matrix-link", pModel.proposalDoneMatrixUrl, label);
			}
			else
			{
				pOut.append("<a class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" href=\"#mdmf-step4-passes\">").append(WizardPage.esc(label)).append("</a>");
			}
			if (!pModel.proposalDoneDeskUrl.isEmpty())
			{
				WizardPage.renderStep4Link(pOut, " mdmf-feed-matrix-link", pModel.proposalDoneDeskUrl, WizardMessages.get(pLocale, "wizard.step4.run.done.reviewDesk"));
			}
			if (!pModel.proposalDoneDesignerUrl.isEmpty())
			{
				WizardPage.renderStep4Link(pOut, " mdmf-feed-designer-link", pModel.proposalDoneDesignerUrl, WizardMessages.get(pLocale, "wizard.step4.run.done.reviewDesigner"));
			}
			// the staging is loaded already: the way on stays one click away (secondary) - points left stay out of the staging, a later build adds them
			if (pModel.stepStates[WizardRules.STEP_TRANSFERS].reachable)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-goto-step=\"").append(WizardRules.STEP_TRANSFERS).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.continue"))).append("</button>");
			}
			final String note = WizardPage.reviewNoteText(pLocale, WizardRules.step4MatrixNote(pModel.matrixNeedYou, pModel.matrixAcceptAll));
			if (!note.isEmpty())
			{
				text.append("<b class=\"mdmf-review-note\">").append(WizardPage.esc(note)).append("</b>");
			}
			WizardPage.appendOptionalLine(text, pModel, pLocale);
			WizardPage.appendWaitingParamLine(text, pLocale, pPrimary, waitingParams);
			text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.next.review"))).append("</span>");
			WizardPage.appendRejectsContinueNote(text, pModel, pLocale);
			if (pModel.stagingLoadRunExists)
			{
				text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.next.reviewLoaded"))).append("</span>");
			}
		}
		else if (pPrimary == WizardRules.Step4Primary.BUILD)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"STAGING_LOAD_RUN\"").append(buildOk ? "" : " disabled=\"disabled\"")
				.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.run.button"))).append("</button>");
			WizardPage.renderStep4CheckButton(pOut, pLocale, buildOk);
			WizardPage.renderStep4ReviewLinks(pOut, pModel, pLocale);
			text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.run.lead"))).append("</span>");
			WizardPage.appendOptionalLine(text, pModel, pLocale);
			WizardPage.appendWaitingParamLine(text, pLocale, pPrimary, waitingParams);
			if (!buildOk)
			{
				text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.generateStaging.reasonKey))).append("</span>");
			}
		}
		else
		{
			// CONTINUE: the staging is loaded and nothing is left to review - the way on is the master
			final WizardRules.StepState transfersState = pModel.stepStates[WizardRules.STEP_TRANSFERS];
			// staging-screens lot (2026-10-17): while rows the build refused are still open, reviewing them is the step's gesture and "Continue to the master" is secondary
			final boolean rejectsOpen = WizardPage.rejectsOpen(pModel);
			if (rejectsOpen && !pModel.stagingRejectsUrl.isEmpty())
			{
				WizardPage.renderStep4Link(pOut, " mdmf-btn-primary mdmf-btn-big", pModel.stagingRejectsUrl, WizardMessages.get(pLocale, "wizard.step4.rejects.top.review"));
			}
			if (transfersState.reachable)
			{
				// the same client-side step switch the end-of-step buttons use (step-gating lot, 2026-09-24)
				pOut.append("<button type=\"button\" class=\"mdmf-btn").append(rejectsOpen && !pModel.stagingRejectsUrl.isEmpty() ? "" : " mdmf-btn-primary mdmf-btn-big")
					.append("\" data-mdmf-goto-step=\"").append(WizardRules.STEP_TRANSFERS).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.continue"))).append("</button>");
			}
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_LOAD_RUN\"").append(buildOk ? "" : " disabled=\"disabled\"").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.run.button.again"))).append("</button>");
			WizardPage.renderStep4ReviewLinks(pOut, pModel, pLocale);
			final int toApply = StillToDecide.of(pModel.snapshot).toApply.size();
			if (!transfersState.reachable)
			{
				text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, transfersState.blockedKey))).append("</span>");
			}
			// lot G1: the staging is never called loaded without saying that a derivation waits for a parameter (its column is empty until then)
			final String continueKey = DerivationParamGate.continueTextKey(toApply, waitingParams.size());
			if (continueKey.startsWith("wizard.step4.primary.next.paramWaiting"))
			{
				text.append("<span class=\"mdmf-step4-param-waiting\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, continueKey, Integer.valueOf(waitingParams.size()), String.join(", ", DerivationParamGate.parameterNames(waitingParams)))))
					.append("</span>");
			}
			else if (toApply > 0)
			{
				text.append("<span>").append(WizardPage.esc(WizardPage.plural(pLocale, toApply, "wizard.step4.primary.next.toApply.one", "wizard.step4.primary.next.toApply.other"))).append("</span>");
			}
			else
			{
				text.append("<span>").append(WizardPage.esc(WizardMessages.get(pLocale, continueKey))).append("</span>");
			}
			WizardPage.appendRejectsContinueNote(text, pModel, pLocale);
			WizardPage.appendOptionalLine(text, pModel, pLocale);
		}
		pOut.append("<span class=\"mdmf-primary-text\">").append(text).append("</span>");
		if (DerivationParamGate.showTopBlock(pPrimary, waitingParams.size()))
		{
			WizardPage.renderPrimaryParamBlock(pOut, pLocale, waitingParams);
		}
		pOut.append("<div class=\"mdmf-primary-extra\">");
		WizardPage.renderStep4Advanced(pOut, pModel, pGates, pLocale, pPrimary);
		pOut.append("</div>");
		WizardPage.renderPrimaryActionBlockClose(pOut);
	}

	/** Lot G1: under the primary block of a state that does not word the staging as done (REVIEW, BUILD): the derivation(s) waiting for a parameter, the build not being held back. */
	private static void appendWaitingParamLine(final StringBuilder pText, final Locale pLocale, final WizardRules.Step4Primary pPrimary, final List<StillToDecide.CheckItem> pWaiting)
	{
		if (DerivationParamGate.addsWaitingLine(pPrimary, pWaiting.size()))
		{
			pText.append("<span class=\"mdmf-step4-param-waiting\">").append(WizardPage.esc(WizardMessages.get(pLocale,
				pWaiting.size() == 1 ? "wizard.step4.primary.paramWaitingLine.one" : "wizard.step4.primary.paramWaitingLine.other", Integer.valueOf(pWaiting.size()),
				String.join(", ", DerivationParamGate.parameterNames(pWaiting))))).append("</span>");
		}
	}

	/**
	 * Staging-screens lot (2026-10-17): "N optional look-up suggestions can wait" - the connector suggestions still open. They are listed on the page and in the matrix, but
	 * never block the build; the line says so wherever the primary block speaks of what is left. Nothing when none is open.
	 */
	private static void appendOptionalLine(final StringBuilder pText, final Model pModel, final Locale pLocale)
	{
		final int optional = Step4Counts.of(pModel.snapshot).optionalWaiting();
		if (optional > 0)
		{
			pText.append("<span class=\"mdmf-step4-optional\">")
				.append(WizardPage.esc(WizardPage.plural(pLocale, optional, "wizard.step4.primary.optional.one", "wizard.step4.primary.optional.other"))).append("</span>");
		}
	}

	/** True when the last build left rows it did not load ({@link Model#stagingHeldBackCount}) and no build is running: the banner at the top, the review link, the secondary "Continue". */
	static boolean rejectsOpen(final Model pModel)
	{
		return pModel.stagingLoadRunExists && !pModel.stagingLoadRunRunning && pModel.stagingHeldBackCount > 0;
	}

	/** One line under the "Continue" button while rows are open: they stay out of the master until someone reviewed them, and continuing does not lose them. */
	private static void appendRejectsContinueNote(final StringBuilder pText, final Model pModel, final Locale pLocale)
	{
		if (WizardPage.rejectsOpen(pModel))
		{
			pText.append("<span class=\"mdmf-step4-rejects-note\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.rejects.continueNote"))).append("</span>");
		}
	}

	/**
	 * Staging-screens lot (2026-10-17): what the grid's per-object rows say about rows not loaded, as {@code "Customer 140, Order 12"} (the objects that have some); empty when the
	 * grid carries no breakdown (the total is then all the banner says).
	 */
	static List<String[]> rejectsPerObject(final Model pModel)
	{
		final List<String[]> out = new ArrayList<String[]>();
		for (final WizardSnapshot.RunGridRow row : pModel.stagingLoadGrid.rows)
		{
			long held = 0L;
			for (final WizardSnapshot.RunGridCell cell : row.cells)
			{
				if (cell.heldBackValue != null)
				{
					held += cell.heldBackValue.longValue();
				}
			}
			if (held > 0L)
			{
				out.add(new String[] { row.label, String.valueOf(held) });
			}
		}
		return out;
	}

	/**
	 * Staging-screens lot (2026-10-17), the cause of rows not loaded, in words. Until lot B ({@code fix/staging-load-rejects}) exposes the cause categories per business object, the
	 * cause is worded neutrally - "refused at load time" - because the old text ("a rule set to stop the row") was printed even when no rule was involved (live: a date the load
	 * refused to convert).
	 * <p>
	 * <b>Lot B hook:</b> {@link #rejectCauseSentence} is the one method to fill; this is the ONE place that wording is chosen (the top banner of step 4 calls it, the grid cells stay neutral).
	 */
	static String rejectCauseWording(final Model pModel, final String pObjectName, final Locale pLocale)
	{
		final String sentence = WizardPage.rejectCauseSentence(pModel, pObjectName, pLocale);
		return sentence != null && !sentence.isEmpty() ? sentence : WizardMessages.get(pLocale, "wizard.step4.rejects.cause.neutral");
	}

	/**
	 * <b>THE hook for lot B (single place to wire at integration):</b> the cause of the rows not loaded for {@code pObjectName} ("" = the whole project), as a phrase for the
	 * banner's parentheses, or null while no cause is known (the neutral wording then stands). Lot B's {@code RejectCauseReader.staging(session, factory, projectCode)}
	 * answers {@code RejectCauses.sentence(locale)} (a rule wording only when {@code blamesARule()}); read it in the service into a {@link Model} field and return it here.
	 */
	static String rejectCauseSentence(final Model pModel, final String pObjectName, final Locale pLocale)
	{
		// staging integration (2026-10-17): wired to lot B's causes; a rule is named only when a quality rule really stopped rows (RejectCauses wording)
		com.on.ebx.factory.rejects.RejectCauses causes = null;
		if (pObjectName != null && !pObjectName.isEmpty() && pModel.stagingRejectCauses != null)
		{
			causes = pModel.stagingRejectCauses.get(pObjectName);
		}
		if (causes == null)
		{
			causes = pModel.stagingRejectCausesAll;
		}
		return causes == null ? null : causes.causePhrase(pLocale);
	}

	/**
	 * Staging preflight lot H1 (2026-10-17): the report of the check before the build ({@link PreflightHtml}) at the top of step 4 - blocking findings with "Apply the fix" / "Build
	 * anyway", warnings alone as a plain notice. The Mapping matrix link of each business object comes from the snapshot's feeds. Nothing when no report is kept or it found nothing.
	 */
	private static void renderStep4Preflight(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.stagingPreflight == null)
		{
			return;
		}
		final Map<String, String> matrixUrls = new LinkedHashMap<String, String>();
		for (final WizardSnapshot.StagingEntityInfo entity : pModel.snapshot.stagingEntities)
		{
			for (final WizardSnapshot.FeedInfo feed : entity.feeds)
			{
				if (feed.parentFeedId.isEmpty() && !feed.matrixUrl.isEmpty() && !matrixUrls.containsKey(entity.name))
				{
					matrixUrls.put(entity.name, feed.matrixUrl);
				}
			}
		}
		pOut.append(PreflightHtml.render(pModel.stagingPreflight, pLocale, matrixUrls, pModel.designer));
	}

	/**
	 * Staging-screens lot (2026-10-17): the banner at the TOP of step 4 once a build left rows out - "140 rows of Customer were not loaded: refused at load time. [Review]" - in place of the
	 * banner that sat below the grid and blamed a rule whatever the cause. The review link opens the rejects screen; nothing when no row is open or a build is running.
	 */
	private static void renderStep4RejectsBanner(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (!WizardPage.rejectsOpen(pModel))
		{
			return;
		}
		final List<String[]> perObject = WizardPage.rejectsPerObject(pModel);
		final long total = pModel.stagingHeldBackCount;
		final String cause = WizardPage.rejectCauseWording(pModel, perObject.size() == 1 ? perObject.get(0)[0] : "", pLocale);
		final String sentence;
		if (perObject.size() == 1)
		{
			sentence = WizardPage.plural(pLocale, total, "wizard.step4.rejects.top.one", "wizard.step4.rejects.top.other", new Object[] { perObject.get(0)[0], cause });
		}
		else if (perObject.size() > 1)
		{
			final StringBuilder list = new StringBuilder();
			for (final String[] one : perObject)
			{
				list.append(list.length() == 0 ? "" : ", ").append(one[0]).append(' ').append(one[1]);
			}
			sentence = WizardMessages.get(pLocale, "wizard.step4.rejects.top.many", Long.valueOf(total), list.toString(), cause);
		}
		else
		{
			sentence = WizardPage.plural(pLocale, total, "wizard.step4.rejects.top.total.one", "wizard.step4.rejects.top.total.other", new Object[] { cause });
		}
		pOut.append("<div class=\"mdmf-notice mdmf-notice-warn mdmf-step4-rejects\" id=\"mdmf-step4-rejects\"><b>").append(WizardPage.esc(sentence)).append("</b> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.rejects.top.hint")));
		if (!pModel.stagingRejectsUrl.isEmpty())
		{
			pOut.append(" <a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(WizardPage.esc(pModel.stagingRejectsUrl)).append("\" target=\"_top\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.rejects.top.review"))).append("</a>");
		}
		pOut.append("</div>");
	}

	/** The line under "Review the proposal" ({@link WizardRules#step4MatrixNote}); empty when the matrix figures are unknown or nothing is left in the matrix. */
	static String reviewNoteText(final Locale pLocale, final WizardRules.ReviewNote pNote)
	{
		if (pNote.kind == WizardRules.ReviewNoteKind.NOTHING_FLAGGED)
		{
			return WizardMessages.get(pLocale, "wizard.step4.primary.review.note.none");
		}
		if (pNote.kind == WizardRules.ReviewNoteKind.FLAGGED)
		{
			return pNote.others == 0
				? WizardPage.plural(pLocale, pNote.attention, "wizard.step4.primary.review.note.only.one", "wizard.step4.primary.review.note.only.other")
				: WizardPage.plural(pLocale, pNote.attention, "wizard.step4.primary.review.note.some.one", "wizard.step4.primary.review.note.some.other",
					new Object[] { Integer.valueOf(pNote.others) });
		}
		return "";
	}

	/** Staging preflight lot H1 (2026-10-17): "Check before building" - the standalone check, a secondary button; the build makes the same check itself before it writes a row. */
	private static void renderStep4CheckButton(final StringBuilder pOut, final Locale pLocale, final boolean pEnabled)
	{
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_PREFLIGHT\"").append(pEnabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.preflight.btn.check"))).append("</button>");
	}

	/** The Matrix and Designer links of the proposal, as secondary buttons (once the review is over, they are for a second look). */
	private static void renderStep4ReviewLinks(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (!pModel.proposalDoneMatrixUrl.isEmpty())
		{
			WizardPage.renderStep4Link(pOut, " mdmf-feed-matrix-link", pModel.proposalDoneMatrixUrl, WizardMessages.get(pLocale, "wizard.step4.run.done.reviewMatrix"));
		}
		if (!pModel.proposalDoneDeskUrl.isEmpty())
		{
			WizardPage.renderStep4Link(pOut, " mdmf-feed-matrix-link", pModel.proposalDoneDeskUrl, WizardMessages.get(pLocale, "wizard.step4.run.done.reviewDesk"));
		}
		if (!pModel.proposalDoneDesignerUrl.isEmpty())
		{
			WizardPage.renderStep4Link(pOut, " mdmf-feed-designer-link", pModel.proposalDoneDesignerUrl, WizardMessages.get(pLocale, "wizard.step4.run.done.reviewDesigner"));
		}
	}

	/**
	 * The folds of the primary block. {@code PROPOSE}: "Options" (what the run covers). Every other state: "Advanced" - build before the review is over (REVIEW
	 * only, with the sentence saying what stays out), "recompute every row" once a build ran (the scope checkbox the build buttons read), and "Propose again"
	 * (the same form as the first proposal, as a secondary button).
	 */
	private static void renderStep4Advanced(final StringBuilder pOut, final Model pModel, final WizardRules.ActionGates pGates, final Locale pLocale, final WizardRules.Step4Primary pPrimary)
	{
		if (pPrimary == WizardRules.Step4Primary.PROPOSE)
		{
			pOut.append("<details class=\"mdmf-disclosure\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.options"))).append("</summary>");
			WizardPage.renderProposalOptions(pOut, pModel, pLocale);
			pOut.append("</details>");
			return;
		}
		pOut.append("<details class=\"mdmf-disclosure\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.advanced"))).append("</summary>");
		if (pPrimary == WizardRules.Step4Primary.REVIEW && pGates.generateStaging.enabled && WizardRules.hasAcceptedBusinessObject(pModel.snapshot))
		{
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_LOAD_RUN\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.primary.buildEarly"))).append("</button><span class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.still.buildNote"))).append("</span></div>");
		}
		if (pGates.generateStaging.enabled && WizardRules.hasAcceptedBusinessObject(pModel.snapshot))
		{
			pOut.append("<div class=\"mdmf-actions\">");
			WizardPage.renderStep4CheckButton(pOut, pLocale, true);
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.preflight.btn.check.hint"))).append("</span></div>");
		}
		if (pModel.stagingLoadRunExists)
		{
			// volume-guards lot (2026-09-28, task item 2): unchecked by default (StagingLoadRun#start's own default is scope "new") - a plain checkbox, posted with the
			// shared wizard form like mdmfDistScopeAll/mdmfDistApproval, no wizard.js sync needed (one project-wide checkbox, not one per row).
			pOut.append("<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfStagingScopeAll\" value=\"true\"/> ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.scopeAll"))).append("</label></div>");
		}
		WizardPage.renderProposalOptions(pOut, pModel, pLocale);
		pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_PROPOSAL_RUN\" data-mdmf-proposal-checkboxes=\"1\"")
			.append(pGates.suggestStaging.enabled ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.button.again")))
			.append("</button>");
		if (!pGates.suggestStaging.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.suggestStaging.reasonKey))).append("</span>");
		}
		pOut.append("</div></details>");
	}

	private static final String[] PROPOSAL_STAGE_KEYS = { ProposalProgress.STRUCTURE_RULE, ProposalProgress.STRUCTURE_LLM, ProposalProgress.PROFILE,
		ProposalProgress.ANOMALY, ProposalProgress.QUALITY, ProposalProgress.CAPABILITY };

	private static void renderProposalRunning(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// UX lot U3 (2026-10-13): the list repaints itself every poll of the jobs banner (wizard.js proposalLive reads the job's progress JSON, the
		// ProposalProgress of the run) - since lot C2 the page is only re-rendered when the job ends, and the list used to stay frozen on its first
		// pass for the whole four minutes. The attributes carry the words the script needs, so it never owns a sentence.
		pOut.append("<div class=\"mdmf-panel\" id=\"mdmfProposalLive\" data-mdmf-pl-now=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.now", "{0}")))
			.append("\" data-mdmf-pl-waiting=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.nowWaiting")))
			.append("\" data-mdmf-pl-count=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.count", "{0}", "{1}")))
			.append("\" data-mdmf-pl-running-for=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.runningFor", "{0}"))).append("\"");
		for (final String state : new String[] { ProposalProgress.TODO, ProposalProgress.RUNNING, ProposalProgress.DONE, ProposalProgress.FAILED, ProposalProgress.SKIPPED })
		{
			pOut.append(" data-mdmf-pl-state-").append(state.toLowerCase(Locale.ROOT)).append("=\"").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.state." + state)))
				.append("\"");
		}
		pOut.append("><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.running.title"))).append("</h3>");
		if (pModel.proposalRunStale)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.stale"))).append("</div>");
		}
		final long nowMillis = System.currentTimeMillis();
		int done = 0;
		String nowLabel = null;
		final StringBuilder items = new StringBuilder();
		for (final String key : WizardPage.PROPOSAL_STAGE_KEYS)
		{
			final ProposalProgress.Stage stage = pModel.proposalRunProgress.stage(key);
			final String state = stage == null ? ProposalProgress.TODO : stage.state;
			final String label = WizardMessages.get(pLocale, "wizard.step4.run.stage." + key);
			if (ProposalProgress.DONE.equals(state) || ProposalProgress.FAILED.equals(state) || ProposalProgress.SKIPPED.equals(state))
			{
				done++;
			}
			if (ProposalProgress.RUNNING.equals(state) && nowLabel == null)
			{
				nowLabel = label;
			}
			items.append("<li class=\"mdmf-proposal-stage mdmf-proposal-stage-").append(state.toLowerCase(Locale.ROOT)).append("\" data-key=\"").append(key)
				.append("\"><span class=\"mdmf-proposal-stage-label\">").append(WizardPage.esc(label)).append("</span> <span class=\"mdmf-proposal-stage-state\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.state." + state))).append("</span> <span class=\"mdmf-hint mdmf-proposal-stage-time\">");
			// the elapsed time of a finished stage, "(12 s)"; of a running one, "running for 1 min 12 s" (the old minutes-only text said "0 min" for the first minute)
			if (stage != null && stage.startedAt != null && stage.endedAt != null)
			{
				items.append('(').append(WizardPage.esc(RecentTasks.durationOf(stage.endedAt.longValue() - stage.startedAt.longValue()))).append(')');
			}
			else if (stage != null && ProposalProgress.RUNNING.equals(state) && stage.startedAt != null)
			{
				items.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.runningFor", RecentTasks.durationOf(nowMillis - stage.startedAt.longValue()))));
			}
			items.append("</span>");
			if (stage != null && stage.summary != null)
			{
				items.append("<div class=\"mdmf-hint mdmf-proposal-stage-summary\">").append(WizardPage.esc(RunPanelText.display(stage.summary, pLocale))).append("</div>");
			}
			items.append("</li>");
		}
		final int total = WizardPage.PROPOSAL_STAGE_KEYS.length;
		pOut.append("<p class=\"mdmf-proposal-now\"><b id=\"mdmfProposalNow\">")
			.append(WizardPage.esc(nowLabel == null ? WizardMessages.get(pLocale, "wizard.step4.run.nowWaiting") : WizardMessages.get(pLocale, "wizard.step4.run.now", nowLabel)))
			.append("</b> <span class=\"mdmf-hint\" id=\"mdmfProposalCount\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.count", Integer.valueOf(done), Integer.valueOf(total)))).append("</span></p>");
		pOut.append("<ul class=\"mdmf-list mdmf-proposal-stages\">").append(items).append("</ul>");
		pOut.append("<div class=\"mdmf-proposal-bar\"><div class=\"mdmf-proposal-bar-fill\" style=\"width:").append(total == 0 ? 0 : done * 100 / total)
			.append("%\"></div></div>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.running.leave"))).append("</p>");
		pOut.append("</div>");
	}

	private static String durationSeconds(final long pStartedAt, final long pEndedAt)
	{
		return String.valueOf(Math.max(0L, (pEndedAt - pStartedAt) / 1000L));
	}

	/**
	 * Counts are computed straight from {@link Model#snapshot} (never re-measured here): business objects and
	 * fields from {@link WizardSnapshot#stagingEntities} (SUPERSEDED excluded, same rule {@link #renderStep4}
	 * already applies to its own cards). "Points that need your eye" is the anomaly stage's own one-line
	 * summary (real counts {@code ebx.AnomalyLlmSuggester} measured, not a number recomputed here) rather than
	 * a fabricated figure - see that stage's {@code summary} below.
	 * <p>
	 * <b>Live bug fix (2026-09-25, PersVol2 volume test):</b> field links/quality rules/checks used to come
	 * from {@link WizardSnapshot#proposedCounts} (PROPOSED-only, {@link #renderWaitingForReviewHeader}'s own
	 * count) - this "Proposal ready for review" panel stays visible long after everything has been reviewed
	 * and loaded (publication is purely additive, nothing here ever hides it), so once every mapping/quality
	 * rule/check had been accepted the panel kept reading "0 field links, 0 quality rules, 0 checks" even with
	 * 39 mappings and 7 quality rules sitting right there, ACCEPTED and loaded. Now reads {@link
	 * WizardSnapshot#decidedCounts} instead (every row not REJECTED) - what the proposal run actually produced,
	 * whatever has since been decided on it.
	 */
	private static void renderProposalDone(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// UX lot U3 (2026-10-13): the numbers come from Step4Counts, the one set the "waiting" line and the "Your decisions" card use too.
		// What the run produced = every row of a nature whatever has been decided since (a total is the sum of its parts, never a stale copy).
		final Step4Counts counts = Step4Counts.of(pModel.snapshot);
		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.run.done.title")))
			.append("</h3>");
		final List<String> produced = WizardPage.countParts(counts, pLocale, false);
		if (!produced.isEmpty())
		{
			pOut.append("<p class=\"mdmf-lead\">").append(WizardPage.esc(WizardPage.join(produced))).append(".</p>");
		}
		final ProposalProgress.Stage anomaly = pModel.proposalRunProgress.stage(ProposalProgress.ANOMALY);
		if (anomaly != null && anomaly.summary != null)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(RunPanelText.display(anomaly.summary, pLocale))).append("</p>");
		}
		WizardPage.renderWaitingLine(pOut, counts, pLocale);
		// UX lot U4 (2026-10-13): the review links moved into the primary block (Review N points is the primary one, the Matrix / Designer links are secondary there)
		pOut.append("</div>");
	}

	/** Quality suggestion (rule + language model) and script-constraint suggestion, right after the entity cards - task item A: "place it where its prerequisites are met". {@code capabilities/suggest-llm} only needs ACCEPTED-or-APPLIED entities (not a generated staging model), so it lives here rather than in step 5. */
	private static void renderQualityAndCapabilityBlock(final StringBuilder pOut, final Model pModel, final WizardRules.ActionGates pGates, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.quality.title")))
			.append("</h3><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.quality.lead"))).append("</p>");
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"QUALITY_SUGGEST\"").append(pGates.suggestQuality.enabled ? "" : " disabled=\"disabled\"")
			.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.quality.suggest"))).append("</button>");
		if (!pGates.suggestQuality.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.suggestQuality.reasonKey))).append("</span>");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"QUALITY_SUGGEST_LLM\"").append(pGates.suggestQualityLlm.enabled ? "" : " disabled=\"disabled\"")
			.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.quality.suggestLlm"))).append("</button>");
		if (!pGates.suggestQualityLlm.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.suggestQualityLlm.reasonKey))).append("</span>");
		}
		pOut.append("</div>");

		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.capability.lead"))).append("</p>");
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"CAPABILITY_SUGGEST_LLM\"")
			.append(pGates.suggestCapabilitiesLlm.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.capability.suggestLlm"))).append("</button>");
		if (!pGates.suggestCapabilitiesLlm.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.suggestCapabilitiesLlm.reasonKey))).append("</span>");
		}
		pOut.append("</div>");
		if (pModel.llmRunSummary != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(WizardPage.esc(pModel.llmRunSummary)).append("</div>");
		}
		// long-passes-visible lot (2026-09-23): both LLM passes' own project-level step, so a RUNNING one stays
		// visible ("running for N min") across a navigate-away-and-back, not only right after the click that
		// started it (the product owner, project Personnes: concluded "Suggest script constraints" proposed nothing
		// after the page re-rendered mid-run, with nothing on it saying a pass was still going).
		final List<WizardSnapshot.PipelineStepInfo> llmSteps = WizardPage.filterSteps(pModel.snapshot.projectSteps, "QUALITY_SUGGEST_LLM", "CAPABILITY_SUGGEST_LLM");
		if (!llmSteps.isEmpty())
		{
			WizardPage.renderTrack(pOut, pLocale, llmSteps);
		}
		pOut.append("</div>");
	}

	/**
	 * Inline-reviews lot (2026-09-23): the old pointer-to-Proposal-review block is gone - the product owner: "l'acces
	 * aux reviews devrait se faire dans l'ecran, sans avoir a le quitter". What is left of it is the one-line
	 * header the task brief asked to keep ("N proposal(s) waiting for review: ..."), right above the entity
	 * cards, whose own {@link WizardSnapshot.StagingEntityInfo#proposalLines} now render every one of these
	 * rows in place, decidable - see {@link #renderProposalLines}. {@code MATCH} is deliberately left out of
	 * this header: those proposals belong to step 6, not step 4.
	 */
	private static void renderWaitingForReviewHeader(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// UX lot U3 (2026-10-13): merged into the "Proposal ready for review" block once a one-click run exists and is over - one waiting line
		// per screen, from the same Step4Counts as the block and the "Your decisions" card
		if (pModel.proposalRunExists && !pModel.proposalRunRunning)
		{
			return;
		}
		WizardPage.renderWaitingLine(pOut, Step4Counts.of(pModel.snapshot), pLocale);
	}

	/**
	 * Step 4's one waiting line ("N proposals waiting for review: ..."), nothing when nothing waits. Natures: every one of {@link Step4Counts}.
	 * Inline-reviews lot (2026-09-23): the proposals themselves are decidable in place under their entity ({@link #renderProposalLines}); this
	 * is the pointer. {@code MATCH} proposals belong to step 6 and are not counted here.
	 */
	private static void renderWaitingLine(final StringBuilder pOut, final Step4Counts pCounts, final Locale pLocale)
	{
		final int total = pCounts.waiting();
		if (total == 0)
		{
			return;
		}
		pOut.append("<p class=\"mdmf-hint mdmf-step4-waiting\">").append(WizardPage.esc(WizardPage.plural(pLocale, total, "wizard.step4.waiting.title.one", "wizard.step4.waiting.title.other")))
			.append(": ").append(WizardPage.esc(WizardPage.join(WizardPage.countParts(pCounts, pLocale, true))));
		// live bug fix (2026-09-25, PersVol2 volume test): cleaning steps used to be named here with no control anywhere near it to decide them -
		// a small inline button right here, scoped to cleaning steps only (ProjectWizardUserService#bulkStaging already sweeps MAPPING_STEP).
		if (pCounts.cleaningSteps.waiting > 0)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_BULK_ACCEPT_STEPS\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.waiting.acceptSteps"))).append("</button>");
		}
		pOut.append("</p>");
	}

	/** "3 business objects", "40 fields"... for every nature of {@code pCounts} that has any row ({@code pWaitingOnly}: only the rows still waiting). */
	private static List<String> countParts(final Step4Counts pCounts, final Locale pLocale, final boolean pWaitingOnly)
	{
		final List<String> parts = new ArrayList<String>();
		for (final String key : pCounts.keys())
		{
			final Step4Counts.Row row = pCounts.row(key);
			final int count = pWaitingOnly ? row.waiting : row.total();
			if (count > 0)
			{
				parts.add(WizardPage.countWords(pLocale, count, WizardPage.COUNT_UNITS.get(key)));
			}
		}
		return parts;
	}

	private static final java.util.Map<String, String> COUNT_UNITS = new java.util.HashMap<String, String>();
	static
	{
		WizardPage.COUNT_UNITS.put("businessObjects", "wizard.unit.businessObject");
		WizardPage.COUNT_UNITS.put("fields", "wizard.unit.field");
		WizardPage.COUNT_UNITS.put("sourceLinks", "wizard.unit.sourceLink");
		WizardPage.COUNT_UNITS.put("cleaningSteps", "wizard.unit.cleaningStep");
		WizardPage.COUNT_UNITS.put("qualityRules", "wizard.unit.qualityRule");
		WizardPage.COUNT_UNITS.put("checks", "wizard.unit.check");
	}

	/**
	 * Threshold-left-out lot (2026-09-23): "(>= N %)" - {@code N} wrapped in its own {@code .mdmf-bulk-
	 * threshold-value} span so wizard.js can keep every occurrence of it on the page in step with the shared
	 * threshold number input as a person types, without a round trip. Appended to every bulk-accept/reject
	 * button label (page-wide, entity-card, quality-card) so "all" never again reads as an unqualified
	 * promise - see this lot's own Javadoc on {@link ProjectWizardUserService#bulkStaging} for why.
	 */
	private static String thresholdSuffix(final int pThreshold)
	{
		return "(&gt;= <span class=\"mdmf-bulk-threshold-value\">" + pThreshold + "</span> %)";
	}

	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): steps 4 and 6 both used to say the fixed sentence "Only
	 * metadata leaves this instance ... never row values" regardless of the project's own {@code
	 * ebx.LlmDataPolicy} level - stale since 2026-09-24, when the staging LLM pass started sending value
	 * SHAPES of text columns (never a real value) whenever the policy allows it, and the matching pass may
	 * send shapes or samples too ({@code ebx.MatchPlanLlmSuggester}). Picks the level-specific key ({@link
	 * WizardSnapshot.ProjectInfo#llmDataPolicyLevel}), falling back to the original fixed wording only if the
	 * level is somehow unrecognized (defence in depth - never a blank disclosure).
	 */
	private static String llmDisclosureText(final Model pModel, final Locale pLocale)
	{
		final String level = pModel.snapshot.project == null ? "SHAPES_ONLY" : pModel.snapshot.project.llmDataPolicyLevel;
		final String key = "wizard.step4.llm.disclosure." + level;
		final String text = WizardMessages.get(pLocale, key);
		return text.equals(key) ? WizardMessages.get(pLocale, "wizard.step4.llm.disclosure") : text;
	}

	/**
	 * Every {@link WizardSnapshot.ProposalLineInfo} of one entity ({@code MAPPING_STEP}/{@code QUALITY}/
	 * {@code SCRIPT_CONSTRAINT}), decidable in place - same columns and the same {@link ReviewLine}-driven
	 * rendering ({@code review.nature.*} labels, rationale details, the {@code SCRIPT_CONSTRAINT} explanation/
	 * measured-effect paragraphs) as the Proposal review screen's own table, reused rather than reinvented -
	 * see {@link #renderDecideActions} for the decision cell itself.
	 */
	private static void renderProposalLines(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.StagingEntityInfo pEntity)
	{
		if (pEntity.proposalLines.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>")
			.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.col.nature"))).append("</th><th>")
			.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.col.element"))).append("</th><th>")
			.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.col.proposition"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.origin"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.confidence"))).append("</th><th>")
			.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.col.rationale"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.decision"))).append("</th></tr></thead><tbody>");
		for (final WizardSnapshot.ProposalLineInfo info : pEntity.proposalLines)
		{
			WizardPage.renderProposalLineRow(pOut, pModel, pLocale, info);
		}
		pOut.append("</tbody></table></div>");
	}

	private static void renderProposalLineRow(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.ProposalLineInfo pInfo)
	{
		final ReviewLine line = pInfo.line;
		// scroll-anchor lot (2026-09-23): nature+id, same stable-id convention as every other decidable row
		pOut.append("<tr id=\"mdmf-").append(line.nature).append('-').append(WizardPage.esc(line.rowId))
			.append("\"><td><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.nature." + line.nature)))
			.append("</span></td><td class=\"mdmf-col-wrap\">").append(WizardPage.esc(line.element)).append("</td><td class=\"mdmf-col-wrap\">")
			.append(WizardPage.esc(line.proposition)).append("</td><td><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + line.origin))).append("</span></td><td>");
		if (line.confidence == null)
		{
			pOut.append("&mdash;");
		}
		else
		{
			final int confidence = Math.max(0, Math.min(100, line.confidence.intValue()));
			pOut.append("<span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(confidence).append("%\"></i></span>").append(confidence)
				.append(" %</span>");
		}
		pOut.append("</td><td>");
		if (line.rationale != null && !line.rationale.isEmpty())
		{
			pOut.append("<details class=\"mdmf-rationale\"><summary>").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.rationale.show"))).append("</summary><p>")
				.append(WizardPage.esc(line.rationale)).append("</p></details>");
		}
		if (line.explanation != null && !line.explanation.isEmpty())
		{
			pOut.append("<p class=\"mdmf-capability-explanation\">").append(WizardPage.esc(line.explanation)).append("</p>");
		}
		if (line.measuredEffect != null && !line.measuredEffect.isEmpty())
		{
			pOut.append("<p class=\"mdmf-capability-effect\">").append(WizardPage.esc(line.measuredEffect)).append("</p>");
		}
		// apply-visibility lot (2026-09-23, coordinator finding, project Personnes): applyMessage is now ALWAYS
		// visible (never only folded into the collapsed rationale) - "Pourquoi le bouton Apply des script
		// constraints ne s'enleve jamais ?" started with the reason sitting on the row, unseen.
		String applyBlockedReasonKey = null;
		// script-backed-derivation lot (2026-09-23): the applyMessage/appliedInfo display is generic to any
		// apply-lot-backed CapabilitySuggestion line - extended to SCRIPT_DERIVATION alongside SCRIPT_CONSTRAINT
		// (fillCapabilityExtras already reads both the same way, see ebx.WizardData). The zone-gate
		// (WizardRules.capabilityApplyGate) stays SCRIPT_CONSTRAINT-only for now - deliberately deferred,
		// docs/SCRIPT-DERIVATION.md - a derivation accepted before the staging model exists simply surfaces
		// ebx.DerivationApplier's own clear error as applyMessage after the fact, rather than greying the
		// button out ahead of time.
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature) || ReviewLine.SCRIPT_DERIVATION.equals(line.nature))
		{
			if (pInfo.applyMessage != null && !pInfo.applyMessage.isEmpty())
			{
				pOut.append("<p class=\"mdmf-capability-applyMessage\">").append(WizardPage.esc(pInfo.applyMessage)).append("</p>");
			}
			if (ReviewLine.APPLIED.equals(line.status) && (!pInfo.appliedModel.isEmpty() || !pInfo.appliedAt.isEmpty()))
			{
				pOut.append("<p class=\"mdmf-hint\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.capability.appliedInfo", WizardPage.orDash(pInfo.appliedModel), WizardPage.orDash(pInfo.appliedAt))))
					.append("</p>");
			}
			if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature))
			{
				final WizardRules.Gate applyGate = WizardRules.capabilityApplyGate(pModel.snapshot, pInfo.zone);
				applyBlockedReasonKey = applyGate.enabled ? null : applyGate.reasonKey;
			}
		}
		pOut.append("</td><td class=\"mdmf-decide\">");
		if (ReviewLine.QUALITY.equals(line.nature))
		{
			WizardPage.renderQualitySeverity(pOut, pLocale, pInfo);
		}
		else if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature) && pInfo.capabilityStaging)
		{
			WizardPage.renderCapabilityTransferPolicy(pOut, pLocale, pInfo);
		}
		WizardPage.renderDecideActions(pOut, pLocale, line.nature, line.rowId, line.status, line.stale, line.staleReason, applyBlockedReasonKey);
		pOut.append("</td></tr>");
	}

	/**
	 * {@code QualityRule.severity} lot (2026-09-23, coordinator finding on project Personnes): a small
	 * WARN/REJECT/MODEL_CONSTRAINT control, wizard-only (the Proposal review screen is deliberately
	 * untouched) - the product owner: REJECT is the mechanism that actually stops a bad row at transfer time, so a
	 * person reviewing the rule must be able to set it from here. {@link WizardSnapshot.ProposalLineInfo
	 * #qualityEvidence} (the same figures {@code ReviewLine.rationale} already folds in, surfaced again right
	 * next to the control) lets a person see what a REJECT would throw away before choosing it. Refused
	 * server-side on an {@code APPLIED} row ({@code WizardData.setQualityRuleSeverity}); disabled here too, so
	 * the control never looks actionable on a row that no longer is.
	 */
	private static void renderQualitySeverity(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.ProposalLineInfo pInfo)
	{
		final boolean applied = ReviewLine.APPLIED.equals(pInfo.line.status);
		pOut.append("<div class=\"mdmf-quality-severity\">");
		if (!pInfo.qualityEvidence.isEmpty())
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(pInfo.qualityEvidence)).append("</span> ");
		}
		pOut.append("<select class=\"mdmf-severity-select\"").append(applied ? " disabled=\"disabled\"" : "").append(">");
		for (final String severity : new String[] { "WARN", "REJECT", "MODEL_CONSTRAINT" })
		{
			pOut.append("<option value=\"").append(severity).append("\"").append(severity.equals(pInfo.severity) ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.quality.severity." + severity))).append("</option>");
		}
		pOut.append("</select> ");
		if (!applied)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"QUALITY_SET_SEVERITY\" data-mdmf-id=\"").append(WizardPage.esc(pInfo.line.rowId))
				.append("\" data-mdmf-severity-select=\"1\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.quality.severity.save"))).append("</button>");
		}
		pOut.append("</div>");
	}

	/**
	 * quality-script-rule lot (2026-09-23, the product owner on project Personnes: "les regles de validation scriptees
	 * ne devraient pas s'appliquer egalement au staging, pour ne pas laisser arriver en master des donnees
	 * erronees ?"): the sibling of {@link #renderQualitySeverity} for a {@code SCRIPT_CONSTRAINT} line of zone
	 * {@code STAGING} - a blank/WARN/REJECT control over {@code CapabilitySuggestion.transferPolicy}, wizard-
	 * only, same convention. Unlike the quality-severity control, NEVER disabled once the constraint is {@code
	 * APPLIED}: {@code transferPolicy} is read live by {@code ebx.MasterLoader} at every transfer, never baked
	 * into the published script body, so changing it after apply is the normal case (see {@code
	 * WizardData.setCapabilityTransferPolicy}'s own Javadoc).
	 */
	private static void renderCapabilityTransferPolicy(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.ProposalLineInfo pInfo)
	{
		pOut.append("<div class=\"mdmf-quality-severity\">");
		if (!pInfo.capabilityEvidence.isEmpty())
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(pInfo.capabilityEvidence)).append("</span> ");
		}
		pOut.append("<select class=\"mdmf-transfer-policy-select\">");
		for (final String policy : new String[] { "", "WARN", "REJECT" })
		{
			final String key = policy.isEmpty() ? "NONE" : policy;
			pOut.append("<option value=\"").append(policy).append("\"").append(policy.equals(pInfo.transferPolicy) ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.capability.transferPolicy." + key))).append("</option>");
		}
		pOut.append("</select> ");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"CAPABILITY_SET_TRANSFER_POLICY\" data-mdmf-id=\"")
			.append(WizardPage.esc(pInfo.line.rowId)).append("\" data-mdmf-transfer-policy-select=\"1\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.capability.transferPolicy.save"))).append("</button>");
		pOut.append("</div>");
	}

	/** "Generate the staging"/"Load the staging", each followed by the relevant slice of {@link WizardSnapshot#projectSteps}. */
	/**
	 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/StagingLoad.dc.html) and UX lot U4 (2026-10-13): what the last "Build and load" left on the
	 * page - the business object x application grid and the held-back banner. The button itself is {@link #renderStep4PrimaryBlock}'s, the live panel of a
	 * running build {@link #renderStagingLive}.
	 */
	private static void renderStagingLoadResult(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardRules.Step4Primary pPrimary)
	{
		// UX lot U4 (2026-10-13): the result of the last build - written only when one ran (the grid used to show its dashes before any build), under the primary
		// block; the build button itself is the primary block's. The live panel of a running build is written by renderStep4Top instead.
		if (pPrimary == WizardRules.Step4Primary.LOADING)
		{
			return;
		}
		if (pModel.stagingLoadRunExists && !pModel.stagingLoadGrid.rows.isEmpty())
		{
			WizardPage.renderRunGrid(pOut, pModel.stagingLoadGrid, pLocale, "wizard.step4.grid.header");
		}
		// staging-screens lot (2026-10-17): the held-back banner is no longer here - it is the first line of step 4 ({@link #renderStep4RejectsBanner})
	}

	/** The pre-existing separate "Generate the staging"/"Load the staging" buttons, now under step 4's "Advanced" disclosure - see {@link #renderStep4PrimaryBlock}'s own Javadoc. */
	private static void renderStagingGenerateLoadAdvanced(final StringBuilder pOut, final Model pModel, final WizardRules.ActionGates pGates, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-panel-muted\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.build.title")))
			.append("</h3>");
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_GENERATE\"")
			.append(pGates.generateStaging.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.generateStaging"))).append("</button>");
		if (!pGates.generateStaging.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.generateStaging.reasonKey))).append("</span>");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STAGING_LOAD\"")
			.append(pGates.loadStaging.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.loadStaging"))).append("</button>");
		if (!pGates.loadStaging.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.loadStaging.reasonKey)));
			// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): the feed-coverage gate names exactly what is
			// missing (WizardRules.Gate#detail) - plain, already-computed English text, appended after the
			// generic localized sentence rather than replacing it (every other gate leaves this null).
			if (pGates.loadStaging.detail != null && !pGates.loadStaging.detail.isEmpty())
			{
				pOut.append(' ').append(WizardPage.esc(pGates.loadStaging.detail));
			}
			pOut.append("</span>");
		}
		pOut.append("</div>");
		final List<WizardSnapshot.PipelineStepInfo> steps = WizardPage.filterSteps(pModel.snapshot.projectSteps, "STAGING_MODEL", "STAGING_DATASET", "STAGING_LOAD");
		if (!steps.isEmpty())
		{
			WizardPage.renderTrack(pOut, pLocale, steps);
		}
		pOut.append("</div>");
	}

	/** Staging-live lot (2026-09-27): message keys the live panel's script needs, passed as raw patterns ({0}/{1} replaced by the script). */
	private static final String[] STAGING_LIVE_LABELS = { "wizard.step3.run.live.now", "wizard.step4.live.nowIdle", "wizard.step3.run.live.leftSoon",
		"wizard.step3.run.live.left", "wizard.step4.live.leftUnknown", "wizard.step3.run.live.beat", "wizard.step3.run.live.beatStale", "wizard.step3.run.live.running",
		"wizard.step3.run.live.took", "wizard.step3.run.live.journalEmpty", "wizard.step4.live.rowsOf", "wizard.step4.live.stagesOf", "wizard.step4.live.cell.next",
		"wizard.step4.live.cell.read", "wizard.step4.live.cell.rows", "wizard.step4.live.cell.written", "wizard.step4.live.cell.unchanged", "wizard.step4.live.cell.held", "wizard.step4.live.cell.failed",
		"wizard.step4.live.stopping", "wizard.step3.run.state.TODO", "wizard.step3.run.state.RUNNING", "wizard.step3.run.state.DONE", "wizard.step3.run.state.FAILED",
		"wizard.step3.run.state.SKIPPED" };

	/**
	 * Staging-live lot (2026-09-27, docs/mockups/staging-live/, a delta of wizard-flow/StagingLoad.dc.html): the
	 * "Fill the shared structure" run while it runs - one live header (what runs now, time spent, time left, one
	 * Stop), the two stages as chips, a bar counting real rows, a heartbeat, the business object x application
	 * grid with real counters after each batch (StagingLoader reports them through RunProgress units), and the
	 * run's journal. It replaces the two-line stage list, frozen until the job ended (the page is only
	 * re-rendered then). {@code wizard.js} (block "staging live panel") repaints it from the progress JSON the
	 * jobs poll returns every 3 s; {@link #stagingCellBody} is mirrored there as {@code cellHtml}.
	 */
	private static void renderStagingLive(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		WizardPage.renderRunLive(pOut, pLocale, pModel.stagingLoadRunProgress, pModel.stagingLoadRunJobId, "mdmfStagingLive", "mdmfSl", "wizard.step4.live.title",
			"wizard.step4.build.stage.", "wizard.step4.live.stage.", "wizard.step4.run.running.leave", false);
	}

	/**
	 * Master-live lot (2026-09-27, docs/mockups/master-live/, a delta of wizard-flow/MasterDone.dc.html): step 5's
	 * "Build the master" while it runs - same panel as step 4's ({@link #renderRunLive}), grid = business object x
	 * Tables / Copy / Matching ready, the copy counting real rows (MasterLoader reports each batch).
	 */
	private static void renderMasterLive(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		WizardPage.renderRunLive(pOut, pLocale, pModel.masterBuildRunProgress, pModel.masterBuildRunJobId, "mdmfMasterLive", "mdmfMb", "wizard.step5.live.title",
			"wizard.step5.build.stage.", "wizard.step5.live.stage.", "wizard.step4.run.running.leave", true);
	}

	/**
	 * Step 5's grid: one row per business object (its COPY unit), columns = the run's stages. A shared stage
	 * (Tables, Matching ready) shows its own state on every row; the COPY column is the object's own unit
	 * ({@link #stagingCellBody}). Mirrored by wizard.js' stage-grid painting.
	 */
	private static void renderRunStageGrid(final StringBuilder pOut, final Locale pLocale, final RunProgress pProgress, final long pNow, final String pPrefix,
		final String pStageChipKeyPrefix)
	{
		final List<RunProgress.Unit> units = pProgress.units();
		pOut.append("<div class=\"mdmf-table-wrap\" id=\"").append(pPrefix).append("GridWrap\"").append(units.isEmpty() ? " style=\"display:none\"" : "")
			.append("><table class=\"mdmf-table mdmf-lr-grid\"><thead><tr id=\"").append(pPrefix).append("Head\"><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.live.col.object"))).append("</th>");
		int n = 0;
		for (final RunProgress.Stage stage : pProgress.stages())
		{
			n++;
			pOut.append("<th>").append(n).append(" \u00B7 ").append(WizardPage.esc(WizardMessages.get(pLocale, pStageChipKeyPrefix + stage.key))).append("</th>");
		}
		pOut.append("</tr></thead><tbody id=\"").append(pPrefix).append("Body\">");
		for (final RunProgress.Unit unit : units)
		{
			pOut.append("<tr><td><b>").append(WizardPage.esc(unit.row)).append("</b></td>");
			for (final RunProgress.Stage stage : pProgress.stages())
			{
				if (unit.col.equals(stage.key))
				{
					pOut.append("<td class=\"mdmf-landing-cell mdmf-landing-cell-").append(unit.state.toLowerCase(Locale.ROOT)).append("\">");
					WizardPage.stagingCellBody(pOut, pLocale, unit, pNow);
				}
				else
				{
					pOut.append("<td class=\"mdmf-landing-cell mdmf-landing-cell-").append(stage.state.toLowerCase(Locale.ROOT)).append("\">");
					WizardPage.runStageCellBody(pOut, pLocale, stage, pNow);
				}
				pOut.append("</td>");
			}
			pOut.append("</tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	/** A shared stage's cell: circle, its state word, then its time (running for / took). Mirrored by wizard.js' {@code stageCellHtml}. */
	private static void runStageCellBody(final StringBuilder pOut, final Locale pLocale, final RunProgress.Stage pStage, final long pNow)
	{
		WizardPage.renderStateCircle(pOut, pStage.state, false);
		pOut.append("<span class=\"mdmf-lr-label\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.state." + pStage.state))).append("</span>");
		if (pStage.startedAt != null)
		{
			final boolean running = RunProgress.RUNNING.equals(pStage.state);
			final long end = pStage.endedAt != null ? pStage.endedAt.longValue() : pNow;
			pOut.append("<br/><span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, running ? "wizard.step3.run.live.running" : "wizard.step3.run.live.took")
				.replace("{0}", WizardPage.durationText(end - pStage.startedAt.longValue())))).append("</span>");
		}
	}

	/**
	 * Master-live lot (2026-09-27): the live run panel of steps 4 and 5, one renderer for both - a
	 * {@link RunProgress} with its stages as chips, its activity, journal and grid units. {@code pPrefix} names
	 * every element id ({@code wizard.js} builds one panel per prefix), {@code pStageGrid} picks the grid: business
	 * object x application with "written this run" (step 4, units per application) or business object x the
	 * stages (step 5, one COPY unit per object between two shared stages).
	 */
	private static void renderRunLive(final StringBuilder pOut, final Locale pLocale, final RunProgress progress, final String pJobId, final String pPanelId,
		final String pPrefix, final String pTitleKey, final String pStageNameKeyPrefix, final String pStageChipKeyPrefix, final String pLeaveKey,
		final boolean pStageGrid)
	{
		final long now = System.currentTimeMillis();
		final com.fasterxml.jackson.databind.node.ObjectNode labels = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		for (final String key : WizardPage.STAGING_LIVE_LABELS)
		{
			labels.put(key.substring(key.indexOf(".live.") + 1), WizardMessages.get(pLocale, key));
		}
		pOut.append("<div class=\"mdmf-panel mdmf-lr mdmf-sl\" id=\"").append(pPanelId).append("\" data-mdmf-lr-prefix=\"").append(pPrefix).append("\" data-mdmf-lr-stage-grid=\"").append(pStageGrid).append("\" data-mdmf-lr-server-now=\"").append(now).append("\" data-mdmf-lr-started=\"")
			.append(progress.startedAt()).append("\" data-mdmf-lr-updated=\"").append(progress.updatedAt()).append("\" data-mdmf-job-id=\"")
			.append(WizardPage.esc(pJobId)).append("\" data-mdmf-lr-labels=\"").append(WizardPage.esc(labels.toString())).append("\">");

		// (A) header
		String nowText = WizardMessages.get(pLocale, "wizard.step4.live.nowIdle");
		for (final RunProgress.Stage stage : progress.stages())
		{
			if (RunProgress.RUNNING.equals(stage.state))
			{
				// staging screens (2026-10-17): what the run wrote is worded in the viewer's language (RunPanelText), like the landing run's panel
				nowText = stage.activity != null ? RunPanelText.display(stage.activity, pLocale) : WizardMessages.get(pLocale, pStageNameKeyPrefix + stage.key);
			}
		}
		pOut.append("<div class=\"mdmf-lr-head\"><span class=\"mdmf-lr-spinner\" aria-hidden=\"true\"></span><div class=\"mdmf-lr-headtext\"><div class=\"mdmf-lr-title\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pTitleKey))).append("</div><div class=\"mdmf-lr-now\" id=\"" + pPrefix + "Now\" aria-live=\"polite\"><b>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.now"))).append("</b> <span>").append(WizardPage.esc(nowText)).append("</span></div></div>");
		pOut.append("<div class=\"mdmf-lr-clock\"><div class=\"mdmf-lr-elapsed\" id=\"" + pPrefix + "Elapsed\">")
			.append(progress.startedAt() > 0L ? WizardPage.clockText(now - progress.startedAt()) : "").append("</div><div class=\"mdmf-lr-left\" id=\"" + pPrefix + "Left\">")
			.append(WizardPage.esc(WizardPage.stagingLeftText(pLocale, progress.remainingMs(now)))).append("</div></div>");
		if (!pJobId.isEmpty())
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-danger\" id=\"" + pPrefix + "Stop\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.stop")))
				.append("</button>");
		}
		pOut.append("</div>");

		// (C) stages as chips, a bar in rows, the heartbeat
		pOut.append("<div class=\"mdmf-lr-barrow\"><span class=\"mdmf-sl-chips\" id=\"" + pPrefix + "Chips\">");
		WizardPage.renderRunChips(pOut, pLocale, progress, pStageChipKeyPrefix);
		final long[] rows = progress.rowsReadOfTotal();
		final int percent = rows[1] > 0L ? (int) Math.min(100L, rows[0] * 100L / rows[1]) : WizardPage.stagePercent(progress);
		pOut.append("</span><div class=\"mdmf-proposal-bar\"><div class=\"mdmf-proposal-bar-fill mdmf-lr-fill-live\" id=\"" + pPrefix + "Fill\" style=\"width:").append(percent)
			.append("%\"></div></div><span class=\"mdmf-lr-stages\" id=\"" + pPrefix + "Count\">").append(WizardPage.esc(WizardPage.stagingCountText(pLocale, progress)))
			.append("</span></div>");
		pOut.append("<div class=\"mdmf-lr-beat\" id=\"" + pPrefix + "Beat\"><span class=\"mdmf-lr-dot\"></span><span>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.beat").replace("{0}", WizardPage.durationText(Math.max(0L, now - progress.updatedAt())))))
			.append("</span></div>");

		// (B) the grid: business object x application, "In staging" = rows written this run - or x the stages (step 5)
		if (pStageGrid)
		{
			WizardPage.renderRunStageGrid(pOut, pLocale, progress, now, pPrefix, pStageChipKeyPrefix);
		}
		else
		{
			final List<String> entities = new ArrayList<String>();
			final List<String> sources = new ArrayList<String>();
			for (final RunProgress.Unit unit : progress.units())
			{
				if (!entities.contains(unit.row))
				{
					entities.add(unit.row);
				}
				if (!sources.contains(unit.col))
				{
					sources.add(unit.col);
				}
			}
			pOut.append("<div class=\"mdmf-table-wrap\" id=\"" + pPrefix + "GridWrap\"").append(entities.isEmpty() ? " style=\"display:none\"" : "")
				.append("><table class=\"mdmf-table mdmf-lr-grid\"><thead><tr id=\"" + pPrefix + "Head\"><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.live.col.object")))
				.append("</th>");
			for (final String source : sources)
			{
				pOut.append("<th>").append(WizardPage.esc(source)).append("</th>");
			}
			pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.live.col.inStaging"))).append("</th></tr></thead><tbody id=\"" + pPrefix + "Body\">");
			for (final String entity : entities)
			{
				long written = 0L;
				pOut.append("<tr><td><b>").append(WizardPage.esc(entity)).append("</b></td>");
				for (final String source : sources)
				{
					final RunProgress.Unit unit = progress.unit(entity, source);
					if (unit == null)
					{
						pOut.append("<td></td>");
						continue;
					}
					written += unit.written;
					pOut.append("<td class=\"mdmf-landing-cell mdmf-landing-cell-").append(unit.state.toLowerCase(Locale.ROOT)).append("\">");
					WizardPage.stagingCellBody(pOut, pLocale, unit, now);
					pOut.append("</td>");
				}
				pOut.append("<td><b>").append(written).append("</b></td></tr>");
			}
			pOut.append("</tbody></table></div>");
		}

		// (D) journal
		pOut.append("<div class=\"mdmf-lr-journal\"><div class=\"mdmf-lr-journal-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.journal")))
			.append("</div><ul id=\"" + pPrefix + "Journal\">");
		final List<RunProgress.Event> events = progress.events();
		if (events.isEmpty())
		{
			pOut.append("<li class=\"mdmf-lr-journal-empty\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step3.run.live.journalEmpty"))).append("</li>");
		}
		final java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT);
		for (int i = 0; i < events.size() && i < 6; i++)
		{
			pOut.append("<li").append(i == 0 ? " class=\"mdmf-lr-journal-live\"" : "").append("><time>").append(time.format(new java.util.Date(events.get(i).at)))
				.append("</time><span>").append(WizardPage.esc(RunPanelText.event(events.get(i).text, pLocale))).append("</span></li>");
		}
		pOut.append("</ul></div><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pLeaveKey))).append("</p></div>");
	}

	/**
	 * Master-live lot (2026-09-27, mockup artboard "Done"): after the run, what it did stays readable - each
	 * stage with its duration and the last lines of its journal - until the next build. Nothing rendered for a
	 * build recorded before the lot (no journal).
	 */
	private static void renderRunTrace(final StringBuilder pOut, final Locale pLocale, final RunProgress pProgress, final String pStageChipKeyPrefix)
	{
		final List<RunProgress.Event> events = pProgress.events();
		if (events.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-panel mdmf-lr-trace\"><div class=\"mdmf-lr-barrow\"><span class=\"mdmf-sl-chips\">");
		WizardPage.renderRunChips(pOut, pLocale, pProgress, pStageChipKeyPrefix);
		pOut.append("</span></div><div class=\"mdmf-lr-journal\"><div class=\"mdmf-lr-journal-title\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.live.lastBuild"))).append("</div><ul>");
		final java.text.SimpleDateFormat time = new java.text.SimpleDateFormat("HH:mm:ss", Locale.ROOT);
		for (int i = 0; i < events.size() && i < 6; i++)
		{
			pOut.append("<li><time>").append(time.format(new java.util.Date(events.get(i).at))).append("</time><span>").append(WizardPage.esc(RunPanelText.event(events.get(i).text, pLocale)))
				.append("</span></li>");
		}
		pOut.append("</ul></div></div>");
	}

	private static void renderRunChips(final StringBuilder pOut, final Locale pLocale, final RunProgress pProgress, final String pStageChipKeyPrefix)
	{
		int n = 0;
		for (final RunProgress.Stage stage : pProgress.stages())
		{
			n++;
			final String tone = RunProgress.DONE.equals(stage.state) ? "mdmf-chip-real" : RunProgress.RUNNING.equals(stage.state) ? "mdmf-sl-chip-run"
				: RunProgress.FAILED.equals(stage.state) ? "mdmf-chip-warn" : "mdmf-chip-muted";
			pOut.append("<span class=\"mdmf-chip ").append(tone).append("\">").append(n).append(" · ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, pStageChipKeyPrefix + stage.key)));
			if (stage.startedAt != null && stage.endedAt != null)
			{
				pOut.append(" · ").append(WizardPage.durationText(stage.endedAt.longValue() - stage.startedAt.longValue()));
			}
			pOut.append("</span> ");
		}
	}

	/** Share of the stages done (a running stage counts half) - the bar before any row count is known. */
	private static int stagePercent(final RunProgress pProgress)
	{
		double done = 0d;
		final List<RunProgress.Stage> stages = pProgress.stages();
		for (final RunProgress.Stage stage : stages)
		{
			done += RunProgress.RUNNING.equals(stage.state) ? 0.5d : RunProgress.TODO.equals(stage.state) ? 0d : 1d;
		}
		return stages.isEmpty() ? 0 : (int) Math.round(done * 100d / stages.size());
	}

	/** "96 of 690 rows" once row totals are known, else "1 of 2 stages". Mirrored by wizard.js. */
	static String stagingCountText(final Locale pLocale, final RunProgress pProgress)
	{
		final long[] rows = pProgress.rowsReadOfTotal();
		if (rows[1] > 0L)
		{
			return WizardMessages.get(pLocale, "wizard.step4.live.rowsOf").replace("{0}", String.valueOf(rows[0])).replace("{1}", String.valueOf(rows[1]));
		}
		int settled = 0;
		for (final RunProgress.Stage stage : pProgress.stages())
		{
			settled += RunProgress.TODO.equals(stage.state) || RunProgress.RUNNING.equals(stage.state) ? 0 : 1;
		}
		return WizardMessages.get(pLocale, "wizard.step4.live.stagesOf").replace("{0}", String.valueOf(settled)).replace("{1}", String.valueOf(pProgress.stages().size()));
	}

	/** Time left at the measured rate; "measuring the speed" before the first batch. */
	static String stagingLeftText(final Locale pLocale, final Long pRemainingMs)
	{
		if (pRemainingMs == null)
		{
			return WizardMessages.get(pLocale, "wizard.step4.live.leftUnknown");
		}
		return WizardPage.leftText(pLocale, pRemainingMs);
	}

	/**
	 * One cell of the staging grid: state circle, a count line and a detail line. Mirrored by wizard.js'
	 * staging {@code cellHtml} - change both together.
	 */
	static void stagingCellBody(final StringBuilder pOut, final Locale pLocale, final RunProgress.Unit pUnit, final long pNow)
	{
		WizardPage.renderStateCircle(pOut, pUnit.state, false);
		final String title;
		if (RunProgress.TODO.equals(pUnit.state))
		{
			title = WizardMessages.get(pLocale, "wizard.step4.live.cell.next");
		}
		else if (RunProgress.RUNNING.equals(pUnit.state) && pUnit.total >= 0L)
		{
			title = WizardMessages.get(pLocale, "wizard.step4.live.cell.read").replace("{0}", String.valueOf(pUnit.read)).replace("{1}", String.valueOf(pUnit.total));
		}
		else if (RunProgress.FAILED.equals(pUnit.state))
		{
			title = WizardMessages.get(pLocale, "wizard.step4.live.cell.failed");
		}
		else
		{
			title = WizardMessages.get(pLocale, "wizard.step4.live.cell.rows").replace("{0}", String.valueOf(pUnit.total >= 0L ? pUnit.read : pUnit.written + pUnit.unchanged));
		}
		pOut.append("<span class=\"mdmf-lr-label\">").append(WizardPage.esc(title)).append("</span>");
		if (RunProgress.TODO.equals(pUnit.state))
		{
			return;
		}
		final StringBuilder line = new StringBuilder(WizardMessages.get(pLocale, "wizard.step4.live.cell.written").replace("{0}", String.valueOf(pUnit.written)));
		// lot V3-S: rows found already up to date are told too - a scope all load of unchanged data writes nothing and would read as idle
		if (pUnit.unchanged > 0L)
		{
			line.append(" · ").append(WizardMessages.get(pLocale, "wizard.step4.live.cell.unchanged").replace("{0}", String.valueOf(pUnit.unchanged)));
		}
		if (pUnit.held > 0L)
		{
			line.append(" · ").append(WizardMessages.get(pLocale, "wizard.step4.live.cell.held").replace("{0}", String.valueOf(pUnit.held)));
		}
		if (pUnit.startedAt > 0L)
		{
			line.append(" · ").append(WizardMessages.get(pLocale, RunProgress.RUNNING.equals(pUnit.state) ? "wizard.step3.run.live.running" : "wizard.step3.run.live.took")
				.replace("{0}", WizardPage.durationText((pUnit.endedAt > 0L ? pUnit.endedAt : pNow) - pUnit.startedAt)));
		}
		pOut.append("<br/><span class=\"mdmf-hint\">").append(WizardPage.esc(line.toString())).append("</span>");
		if (RunProgress.RUNNING.equals(pUnit.state) && pUnit.total > 0L)
		{
			pOut.append("<span class=\"mdmf-sl-mini\"><span style=\"width:").append(Math.min(100L, pUnit.read * 100L / pUnit.total)).append("%\"></span></span>");
		}
	}

	/** Renders {@link WizardSnapshot.RunGridInfo} as the board's "business object x column" table - see {@link WizardSnapshot.RunGridInfo}'s own Javadoc. Nothing rendered when {@code pGrid.rows} is empty (the caller checks). */
	private static void renderRunGrid(final StringBuilder pOut, final WizardSnapshot.RunGridInfo pGrid, final Locale pLocale, final String pHeaderKey)
	{
		// staging-screens lot (2026-10-17): fixed layout, cells wrap - the grid fits its panel whatever the number of applications (it was cut on the right at three); below 640 px its own box scrolls
		pOut.append("<div class=\"mdmf-panel mdmf-run-grid-wrap\"><table class=\"mdmf-run-grid mdmf-run-grid-fixed\"><thead><tr><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pHeaderKey))).append("</th>");
		for (final String column : pGrid.columnLabels)
		{
			pOut.append("<th>").append(WizardPage.esc(column)).append("</th>");
		}
		pOut.append("</tr></thead><tbody>");
		for (final WizardSnapshot.RunGridRow row : pGrid.rows)
		{
			pOut.append("<tr><td><div class=\"mdmf-run-grid-label\">").append(WizardPage.esc(row.label)).append("</div>");
			if (!row.sublabel.isEmpty())
			{
				pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(row.sublabel)).append("</div>");
			}
			pOut.append("</td>");
			for (final WizardSnapshot.RunGridCell cell : row.cells)
			{
				pOut.append("<td><div class=\"mdmf-run-grid-cell\">");
				WizardPage.renderStepStateCircle(pOut, cell.state);
				pOut.append("<div>");
				if (!cell.primaryText.isEmpty())
				{
					pOut.append("<div class=\"mdmf-run-grid-primary\">").append(WizardPage.esc(cell.primaryText)).append("</div>");
				}
				if (!cell.secondaryText.isEmpty())
				{
					pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(cell.secondaryText)).append("</div>");
				}
				pOut.append("</div></div></td>");
			}
			pOut.append("</tr>");
		}
		pOut.append("</tbody></table></div>");
	}

	// ------------------------------------------------------------------------- step 4: folded business objects (wave 11, audit F-42)

	/** More business objects than this and step 4 writes a card in full only for the open ones (the others are one line and a "Show" button). */
	public static final int ENTITY_FOLD_THRESHOLD = 3;

	/** The cards step 4 draws: every entity except the ones the language-model pass absorbed ({@code SUPERSEDED}). */
	static int visibleEntityCount(final Model pModel)
	{
		int count = 0;
		for (final WizardSnapshot.StagingEntityInfo entity : pModel.snapshot.stagingEntities)
		{
			if (!ReviewLine.SUPERSEDED.equals(entity.status))
			{
				count++;
			}
		}
		return count;
	}

	/** Whether step 4 folds at all: a filter is set, or there are more than {@link #ENTITY_FOLD_THRESHOLD} cards. */
	static boolean entityFolding(final Model pModel, final int pVisibleEntities)
	{
		return !pModel.stagingFilter.isEmpty() || pVisibleEntities > WizardPage.ENTITY_FOLD_THRESHOLD;
	}

	/**
	 * Whether {@code pEntity}'s card is written in full while step 4 folds: opened by a person (and kept open across postbacks, {@code
	 * ProjectWizardUserService}), or matching the filter. After "Unfold all" every card is open but those a person folded again.
	 */
	static boolean entityOpen(final Model pModel, final WizardSnapshot.StagingEntityInfo pEntity)
	{
		if (pModel.stagingOpenAll ? !pModel.stagingClosedEntities.contains(pEntity.id) : pModel.stagingOpenEntities.contains(pEntity.id))
		{
			return true;
		}
		return !pModel.stagingFilter.isEmpty() && WizardPage.entityMatchesFilter(pEntity, pModel.stagingFilter);
	}

	/** The filter's words (all of them) against the entity's name, label, target table and its attributes' names and labels, case-insensitive. */
	static boolean entityMatchesFilter(final WizardSnapshot.StagingEntityInfo pEntity, final String pFilter)
	{
		if (pFilter == null || pFilter.trim().isEmpty())
		{
			return true;
		}
		for (final String word : pFilter.trim().toLowerCase(Locale.ROOT).split("\\s+"))
		{
			if (!WizardPage.entityContains(pEntity, word))
			{
				return false;
			}
		}
		return true;
	}

	private static boolean entityContains(final WizardSnapshot.StagingEntityInfo pEntity, final String pWordLower)
	{
		if (pEntity.name.toLowerCase(Locale.ROOT).contains(pWordLower) || pEntity.label.toLowerCase(Locale.ROOT).contains(pWordLower)
			|| pEntity.targetTable.toLowerCase(Locale.ROOT).contains(pWordLower))
		{
			return true;
		}
		for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.name.toLowerCase(Locale.ROOT).contains(pWordLower) || attribute.label.toLowerCase(Locale.ROOT).contains(pWordLower))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether an attribute row is one the filter looked for (the row is shown whatever the filter says: it is marked, never dropped). */
	static boolean attributeMatchesFilter(final WizardSnapshot.AttributeInfo pAttribute, final String pFilter)
	{
		if (pFilter == null || pFilter.trim().isEmpty())
		{
			return false;
		}
		for (final String word : pFilter.trim().toLowerCase(Locale.ROOT).split("\\s+"))
		{
			if (!pAttribute.name.toLowerCase(Locale.ROOT).contains(word) && !pAttribute.label.toLowerCase(Locale.ROOT).contains(word))
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The strip above the cards when they fold: the filter box (a real action - the filter is read by the server, which is how a folded card can still
	 * be found), what it matched, and "Unfold all" / "Fold all". Nothing is hidden by it: a card that does not match is folded, not removed.
	 */
	private static void renderEntityFoldBar(final StringBuilder pOut, final Model pModel, final Locale pLocale, final int pVisibleEntities)
	{
		int matching = 0;
		int open = 0;
		for (final WizardSnapshot.StagingEntityInfo entity : pModel.snapshot.stagingEntities)
		{
			if (ReviewLine.SUPERSEDED.equals(entity.status))
			{
				continue;
			}
			if (!pModel.stagingFilter.isEmpty() && WizardPage.entityMatchesFilter(entity, pModel.stagingFilter))
			{
				matching++;
			}
			if (WizardPage.entityOpen(pModel, entity))
			{
				open++;
			}
		}
		pOut.append("<div class=\"mdmf-fold-bar\" id=\"mdmf-entity-fold-bar\"><div class=\"mdmf-actions\"><label class=\"mdmf-hint\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.filter.label")))
			.append(" <input type=\"text\" id=\"mdmfStagingFilterInput\" class=\"mdmf-fold-filter\" data-mdmf-no-dirty=\"true\" maxlength=\"100\" placeholder=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.filter.placeholder"))).append("\" value=\"").append(WizardPage.esc(pModel.stagingFilter)).append("\"/></label>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" id=\"mdmfStagingFilterApply\" data-mdmf-action=\"STAGING_FILTER\" data-mdmf-filter-input=\"mdmfStagingFilterInput\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.filter.apply"))).append("</button>");
		if (!pModel.stagingFilter.isEmpty())
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_FILTER\" data-mdmf-filter-clear=\"true\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.filter.clear"))).append("</button>");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_ENTITY_OPEN_ALL\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.fold.openAll"))).append("</button>")
			.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_ENTITY_CLOSE_ALL\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.fold.closeAll"))).append("</button></div>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(pModel.stagingFilter.isEmpty()
			? WizardMessages.get(pLocale, "wizard.step4.fold.hint", Integer.valueOf(pVisibleEntities), Integer.valueOf(open), Integer.valueOf(WizardPage.ENTITY_FOLD_THRESHOLD))
			: WizardMessages.get(pLocale, "wizard.step4.filter.result", Integer.valueOf(matching), Integer.valueOf(pVisibleEntities), pModel.stagingFilter))).append("</p></div>");
	}

	/**
	 * A business object that is folded: its name, origin, status and the figures a reviewer decides on, one "Show" button. The card keeps its anchor id (a
	 * restored scroll position lands on it); everything the full card shows is one click away and nothing of it was dropped from the snapshot.
	 */
	private static void renderStagingEntityFolded(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.StagingEntityInfo pEntity)
	{
		int toDecide = 0;
		for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
		{
			if (!attribute.followsEntity() && ReviewLine.PROPOSED.equals(attribute.status))
			{
				toDecide++;
			}
		}
		int proposals = 0;
		for (final WizardSnapshot.ProposalLineInfo line : pEntity.proposalLines)
		{
			if (ReviewLine.PROPOSED.equals(line.line.status))
			{
				proposals++;
			}
		}
		pOut.append("<div class=\"mdmf-entity mdmf-entity-folded\" id=\"mdmf-ENTITY-").append(WizardPage.esc(pEntity.id)).append("\"><header><h3>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity", pEntity.name))).append("</h3><span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + pEntity.origin))).append("</span> <span class=\"mdmf-decide-status mdmf-chip ")
			.append(WizardPage.statusChipClass(pEntity.status)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.status." + pEntity.status))).append("</span>");
		if (pEntity.confidence != null)
		{
			pOut.append(" <span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pEntity.confidence).append("%\"></i></span>")
				.append(pEntity.confidence).append(" %</span>");
		}
		pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_ENTITY_OPEN\" data-mdmf-id=\"").append(WizardPage.esc(pEntity.id)).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.fold.open"))).append("</button></header>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.fold.summary", Integer.valueOf(pEntity.attributes.size()),
			Integer.valueOf(toDecide), Integer.valueOf(proposals), Integer.valueOf(pEntity.feeds.size())))).append("</p></div>");
	}

	/** {@code pNames}, in that order, keeping only the ones actually present in {@code pSteps} - same idea as {@link #renderTrack}'s own source track, for a project-level subset. */
	private static List<WizardSnapshot.PipelineStepInfo> filterSteps(final List<WizardSnapshot.PipelineStepInfo> pSteps, final String... pNames)
	{
		final List<WizardSnapshot.PipelineStepInfo> out = new ArrayList<WizardSnapshot.PipelineStepInfo>();
		for (final String name : pNames)
		{
			for (final WizardSnapshot.PipelineStepInfo step : pSteps)
			{
				if (step.step.equals(name))
				{
					out.add(step);
					break;
				}
			}
		}
		return out;
	}

	private static void renderStagingEntity(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSnapshot.StagingEntityInfo pEntity,
		final boolean pFoldable)
	{
		// staging-focus lot (2026-09-23): the caller (renderStep4) never calls this for a SUPERSEDED entity
		// any more - it is skipped before this method is even reached, so nothing here needs to special-case
		// that status any longer (the grayed-out "mdmf-entity-superseded" card is gone, not just hidden).
		// scroll-anchor lot (2026-09-23): a stable id, same convention as every other decidable block
		pOut.append("<div class=\"mdmf-entity\" id=\"mdmf-ENTITY-").append(WizardPage.esc(pEntity.id))
			.append("\"><header><h3>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity", pEntity.name)))
			.append("</h3><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + pEntity.origin)))
			.append("</span>");
		if (pEntity.confidence != null)
		{
			pOut.append(" <span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pEntity.confidence).append("%\"></i></span>")
				.append(pEntity.confidence).append(" %</span>");
		}
		if (pFoldable)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_ENTITY_CLOSE\" data-mdmf-id=\"").append(WizardPage.esc(pEntity.id)).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.fold.close"))).append("</button>");
		}
		pOut.append("</header>");
		// block-accept lot (2026-09-23): the product owner, "il faut pouvoir accepter tous les elements d'un bloc, pas
		// tous ceux de la page" - the SAME bulk logic as the page-wide buttons (ProjectWizardUserService
		// .bulkStaging, now entity-filtered through a WizardSnapshot.BulkFilter), reusing the one global
		// threshold input. Never shown when nothing in this entity's own card is even eligible to decide -
		// WizardRules.hasDecidableProposals/hasDecidableQualityProposals are the pure rules. One "include below
		// threshold" checkbox per card, shared by both the entity-wide and the quality-only buttons below
		// (threshold-left-out lot, 2026-09-23, product report: a below-threshold row silently dropped out of
		// "Accept all" - a person who genuinely wants everything of this card ticks this rather than loosening
		// the shared threshold for every other entity too).
		final boolean anyDecidable = WizardRules.hasDecidableProposals(pEntity);
		final boolean anyQualityDecidable = WizardRules.hasDecidableQualityProposals(pEntity);
		if (anyDecidable || anyQualityDecidable)
		{
			final String checkboxId = "mdmf-include-below-" + WizardPage.esc(pEntity.id);
			pOut.append("<div class=\"mdmf-actions\"><label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"").append(checkboxId).append("\"/> ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.bulk.includeBelowThreshold"))).append("</label></div><div class=\"mdmf-actions\">");
			if (anyDecidable)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_BULK_ACCEPT_ENTITY\" data-mdmf-id=\"")
					.append(WizardPage.esc(pEntity.id)).append("\" data-mdmf-include-below-input=\"").append(checkboxId).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity.bulkAccept"))).append(' ').append(WizardPage.thresholdSuffix(pModel.stagingBulkThreshold))
					.append("</button>");
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"STAGING_BULK_REJECT_ENTITY\" data-mdmf-id=\"")
					.append(WizardPage.esc(pEntity.id)).append("\" data-mdmf-include-below-input=\"").append(checkboxId).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity.bulkReject"))).append(' ').append(WizardPage.thresholdSuffix(pModel.stagingBulkThreshold))
					.append("</button>");
			}
			// quality-block-bulk lot (2026-09-23): the product owner, "j'aurais aime un accept/reject all au niveau des
			// revues liees a la qualite" - the same sweep, restricted to this entity's MAPPING_STEP/QUALITY
			// proposal lines only (never attributes/mappings/SCRIPT_CONSTRAINT).
			if (anyQualityDecidable)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STAGING_BULK_ACCEPT_QUALITY\" data-mdmf-id=\"")
					.append(WizardPage.esc(pEntity.id)).append("\" data-mdmf-include-below-input=\"").append(checkboxId).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.quality.bulkAccept"))).append(' ').append(WizardPage.thresholdSuffix(pModel.stagingBulkThreshold))
					.append("</button>");
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"STAGING_BULK_REJECT_QUALITY\" data-mdmf-id=\"")
					.append(WizardPage.esc(pEntity.id)).append("\" data-mdmf-include-below-input=\"").append(checkboxId).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.quality.bulkReject"))).append(' ').append(WizardPage.thresholdSuffix(pModel.stagingBulkThreshold))
					.append("</button>");
			}
			pOut.append("</div>");
		}
		if (pEntity.memberEntitiesText != null && !pEntity.memberEntitiesText.isEmpty())
		{
			pOut.append("<p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity.unifies", pEntity.memberEntitiesText))).append("</p>");
		}
		if (!pEntity.supersededBy.isEmpty())
		{
			// a member of a canonical entity that a person already decided on (ACCEPTED or REJECTED, so not
			// SUPERSEDED above) still says which canonical it belongs to and what that means: kept in staging
			// only, never promoted to master (MasterModel's membership rule) - the product owner, project Personnes,
			// 2026-09-23: three equal-looking cards for one target, nothing on the members' own cards said so
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity.memberOf", pEntity.supersededBy))).append("</p>");
		}
		// one status chip only (2026-09-21: renderInlineDecide already draws it) - this used to also draw its own
		// separate status chip first, giving every entity header two "Accepted" chips side by side
		pOut.append("<p>");
		WizardPage.renderInlineDecide(pOut, pLocale, "ENTITY", pEntity.id, pEntity.status);
		pOut.append("</p>");
		final WizardRules.EntityHint hint = WizardRules.entityHint(pEntity);
		if (hint.waitingForAttribute)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.entity.waitingHint" + (hint.waitingCount == 1 ? ".one" : ".other"), pEntity.name,
					Integer.valueOf(hint.waitingCount))))
				.append("</div>");
		}
		if (!pEntity.attributes.isEmpty())
		{
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.attribute")))
				.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.type"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.length"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.mandatory"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.key"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.reference"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.origin"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.confidence"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.decision"))).append("</th></tr></thead><tbody>");
			for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
			{
				WizardPage.renderAttributeRow(pOut, pLocale, attribute, WizardPage.attributeMatchesFilter(attribute, pModel.stagingFilter));
			}
			pOut.append("</tbody></table></div>");
		}
		WizardPage.renderFeeds(pOut, pLocale, pEntity);
		WizardPage.renderProposalLines(pOut, pModel, pLocale, pEntity);
		pOut.append("</div>");
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): this entity's feeds as a tree - every root feed
	 * (empty {@code parentFeedId}) first, then every child feed of THIS entity's own list indented one level
	 * ({@code mdmf-feed-child}). A child feed's parent feed usually lives on a DIFFERENT entity's card ({@code
	 * TransferFeed.entity}), so the "tree" here is a per-entity view of the mapping graph, not a literal single
	 * nested structure spanning cards - exactly what {@code ebx.WizardData.attachFeeds} attaches per entity.
	 * Skipped entirely when the entity has no feed yet (a project mid-way through target-mapping lot 1's
	 * backfill, or one whose staging was proposed before this lot).
	 */
	private static void renderFeeds(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.StagingEntityInfo pEntity)
	{
		if (pEntity.feeds.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-feeds\"><h4 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feeds.title")))
			.append("</h4>");
		for (final WizardSnapshot.FeedInfo feed : pEntity.feeds)
		{
			if (feed.parentFeedId.isEmpty())
			{
				WizardPage.renderFeed(pOut, pLocale, feed, false);
			}
		}
		for (final WizardSnapshot.FeedInfo feed : pEntity.feeds)
		{
			if (!feed.parentFeedId.isEmpty())
			{
				WizardPage.renderFeed(pOut, pLocale, feed, true);
			}
		}
		pOut.append("</div>");
	}

	/** One feed of {@link #renderFeeds} - header (source/landing table, origin, confidence), the accept/reject decision, the editable {@code sourceIdPipeline} field (both root and child feeds carry it), and its mappings table. */
	private static void renderFeed(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.FeedInfo pFeed, final boolean pIndented)
	{
		// editable-in-place gate: docs/TARGET-MAPPING.md sec 4.3, "pipeline and sourceIdPipeline editable in
		// place for a PROPOSED or USER feed/mapping" - the exact predicate WizardData.setFeedSourceIdPipeline/
		// setMappingPipeline refuse on server-side too (defence in depth, never trust the client alone).
		final boolean editable = ReviewLine.PROPOSED.equals(pFeed.status) || "USER".equals(pFeed.origin);
		pOut.append("<div class=\"mdmf-feed").append(pIndented ? " mdmf-feed-child" : "").append("\" id=\"mdmf-FEED-").append(WizardPage.esc(pFeed.id)).append("\">");
		pOut.append("<div class=\"mdmf-feed-header\"><span class=\"mdmf-feed-label\">").append(WizardPage.esc(pFeed.sourceSystem)).append(' ')
			.append(WizardPage.esc(pFeed.landingTableName)).append("</span>");
		if (pIndented)
		{
			pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.childOf", pFeed.parentEntityName)))
				.append("</span>");
		}
		pOut.append(" <span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + pFeed.origin))).append("</span>");
		if (pFeed.confidence != null)
		{
			pOut.append(" <span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pFeed.confidence).append("%\"></i></span>")
				.append(pFeed.confidence).append(" %</span>");
		}
		// feed-designer lot (2026-09-23): a root feed only (designerUrl is empty on a child - see its own
		// Javadoc); target="_top" so the graphical designer opens as a whole page, never inside a modal
		// iframe (CLAUDE.md sec 9) - same technique as ProjectExplorerPage's own Wizard/Cockpit links.
		if (!pFeed.designerUrl.isEmpty())
		{
			pOut.append(" <a class=\"mdmf-btn mdmf-btn-sm mdmf-feed-designer-link\" href=\"").append(WizardPage.esc(pFeed.designerUrl)).append("\" target=\"_top\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.designer"))).append("</a>");
		}
		// mapping-matrix lot (2026-09-23, docs/MAPPING-MATRIX.md): same root-feed-only, target=_top convention
		// as designerUrl just above - the matrix opens on this feed's own entity, every source at once.
		if (!pFeed.matrixUrl.isEmpty())
		{
			pOut.append(" <a class=\"mdmf-btn mdmf-btn-sm mdmf-feed-matrix-link\" href=\"").append(WizardPage.esc(pFeed.matrixUrl)).append("\" target=\"_top\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.matrix"))).append("</a>");
		}
		pOut.append("</div>");
		if (pIndented)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.parentLink", pFeed.parentLinkField)))
				.append("</p>");
		}
		pOut.append("<p>");
		WizardPage.renderInlineDecide(pOut, pLocale, ReviewLine.FEED, pFeed.id, pFeed.status);
		pOut.append("</p>");
		WizardPage.renderFeedSourceIdPipeline(pOut, pLocale, pFeed, editable);
		if (!pFeed.mappings.isEmpty())
		{
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.landingColumn"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.attribute"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.pipeline"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.origin"))).append("</th><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.decision"))).append("</th></tr></thead><tbody>");
			for (final WizardSnapshot.FeedMappingInfo mapping : pFeed.mappings)
			{
				WizardPage.renderFeedMappingRow(pOut, pLocale, mapping);
			}
			pOut.append("</tbody></table></div>");
		}
		pOut.append("</div>");
	}

	/** {@code TransferFeed.sourceIdPipeline} - editable text input + Save button when {@code pEditable}, otherwise the current value (or the loader's own default wording when blank) as plain text. */
	private static void renderFeedSourceIdPipeline(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.FeedInfo pFeed, final boolean pEditable)
	{
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.sourceIdPipeline"))).append(' ');
		if (pEditable)
		{
			final String inputId = "mdmf-feed-sourceIdPipeline-" + WizardPage.esc(pFeed.id);
			pOut.append("<input type=\"text\" id=\"").append(inputId).append("\" class=\"mdmf-feed-sourceIdPipeline-input\" value=\"")
				.append(WizardPage.esc(pFeed.sourceIdPipeline)).append("\"/> ");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"FEED_SET_SOURCE_ID_PIPELINE\" data-mdmf-id=\"")
				.append(WizardPage.esc(pFeed.id)).append("\" data-mdmf-source-id-pipeline-input=\"").append(inputId).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.sourceIdPipeline.save"))).append("</button>");
		}
		else if (pFeed.sourceIdPipeline.isEmpty())
		{
			pOut.append("<code>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.sourceIdPipeline.default"))).append("</code>");
		}
		else
		{
			pOut.append("<code>").append(WizardPage.esc(pFeed.sourceIdPipeline)).append("</code>");
		}
		pOut.append("</p>");
	}

	/** One {@code AttributeMapping} row of a feed - landing column, attribute, editable pipeline (or its read-only transformation fallback), origin, decision. */
	private static void renderFeedMappingRow(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.FeedMappingInfo pMapping)
	{
		final boolean editable = ReviewLine.PROPOSED.equals(pMapping.status) || "USER".equals(pMapping.origin);
		pOut.append("<tr id=\"mdmf-MAPPING-").append(WizardPage.esc(pMapping.id)).append("\"><td>").append(WizardPage.esc(pMapping.landingColumn)).append("</td><td>")
			.append(WizardPage.esc(pMapping.attributeName)).append("</td><td>");
		if (editable)
		{
			final String inputId = "mdmf-mapping-pipeline-" + WizardPage.esc(pMapping.id);
			pOut.append("<input type=\"text\" id=\"").append(inputId).append("\" class=\"mdmf-mapping-pipeline-input\" value=\"")
				.append(WizardPage.esc(pMapping.pipeline)).append("\" placeholder=\"").append(WizardPage.esc(pMapping.transformationDisplay)).append("\"/> ");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"FEED_SET_PIPELINE\" data-mdmf-id=\"").append(WizardPage.esc(pMapping.id))
				.append("\" data-mdmf-pipeline-input=\"").append(inputId).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.feed.pipeline.save")))
				.append("</button>");
		}
		else
		{
			pOut.append(WizardPage.esc(pMapping.pipeline.isEmpty() ? pMapping.transformationDisplay : pMapping.pipeline));
		}
		pOut.append("</td><td><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + pMapping.origin)))
			.append("</span></td>");
		WizardPage.renderDecideCell(pOut, pLocale, ReviewLine.MAPPING, pMapping.id, pMapping.status);
		pOut.append("</tr>");
	}

	private static void renderAttributeRow(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.AttributeInfo pAttribute, final boolean pFilterHit)
	{
		// scroll-anchor lot (2026-09-23): same stable-id convention as every other decidable row
		pOut.append("<tr id=\"mdmf-ATTRIBUTE-").append(WizardPage.esc(pAttribute.id)).append("\"").append(pFilterHit ? " class=\"mdmf-filter-hit\"" : "").append("><td>").append(WizardPage.esc(pAttribute.name));
		// primaryKey (the entity's technical identity, sourceSystem/sourceId) and businessKey (the source's own
		// key, e.g. contactId) are two distinct, real fields now (TechnicalKeyRule) - no more guessing "technical"
		// from the attribute's name
		if (pAttribute.primaryKey)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.key.technical"))).append("</span>");
		}
		else if (pAttribute.businessKey)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.key.business"))).append("</span>");
		}
		pOut.append("</td><td>").append(WizardPage.esc(pAttribute.dataType)).append("</td><td class=\"num\">")
			.append(pAttribute.maxLength == null ? "&mdash;" : String.valueOf(pAttribute.maxLength)).append("</td><td>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pAttribute.mandatory ? "wizard.yes" : "wizard.no"))).append("</td><td>")
			.append(pAttribute.businessKey || pAttribute.primaryKey ? WizardPage.esc(WizardMessages.get(pLocale, "wizard.col.key")) : "&mdash;").append("</td><td>")
			.append(pAttribute.referenceEntityName.isEmpty() ? "&mdash;" : WizardPage.esc(pAttribute.referenceEntityName)).append("</td>");
		pOut.append("<td><span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.origin." + pAttribute.origin)))
			.append("</span></td><td>");
		if (pAttribute.confidence == null)
		{
			pOut.append("&mdash;");
		}
		else
		{
			pOut.append("<span class=\"mdmf-conf\"><span class=\"mdmf-conf-bar\"><i style=\"width:").append(pAttribute.confidence).append("%\"></i></span>")
				.append(pAttribute.confidence).append(" %</span>");
		}
		if (pAttribute.rationale != null && !pAttribute.rationale.isEmpty())
		{
			pOut.append("<details class=\"mdmf-rationale\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.rationale.show"))).append("</summary><p>")
				.append(WizardPage.esc(pAttribute.rationale)).append("</p></details>");
		}
		// bulk-mapping-cascade lot (2026-09-23, coordinator finding): every attribute now shows how many
		// mappings actually feed it, and the same warning ReviewRules.attributeMappingWarning already gives the
		// review screen when the count is zero - never a technical key or a derived (FOLD_KEY) attribute, which
		// have no mapping of their own by design (WizardData only ever writes a non-empty mappingWarning for the
		// case that matters: an ACCEPTED/APPLIED attribute with nothing left to feed it).
		if (!pAttribute.primaryKey && pAttribute.mappingCount > 0)
		{
			pOut.append("<br/><span class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step4.attribute.mappings" + (pAttribute.mappingCount == 1 ? ".one" : ".other"),
					Integer.valueOf(pAttribute.mappingFeedingCount), Integer.valueOf(pAttribute.mappingCount))))
				.append("</span>");
		}
		if (pAttribute.mappingWarning != null && !pAttribute.mappingWarning.isEmpty())
		{
			pOut.append("<br/><span class=\"mdmf-chip mdmf-chip-bad\">").append(WizardPage.esc(com.on.ebx.factory.review.ReviewRules.MAPPING_WARNING.equals(pAttribute.mappingWarning)
				? WizardMessages.get(pLocale, "wizard.step4.attribute.noMapping") : pAttribute.mappingWarning)).append("</span>");
		}
		pOut.append("</td>");
		if (pAttribute.followsEntity())
		{
			// the technical key has no decision of its own (TechnicalKeyRule/ReviewRules.canDecide(status, true)):
			// ONE chip only ("show the status once", 2026-09-21 - the earlier version also showed the ordinary
			// status chip here), never a status chip AND this one, and never Accept/Reject buttons
			pOut.append("<td class=\"mdmf-decide\"><span class=\"mdmf-decide-status mdmf-chip mdmf-chip-accent\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.key.followsEntity"))).append("</span></td>");
		}
		else
		{
			WizardPage.renderDecideCell(pOut, pLocale, "ATTRIBUTE", pAttribute.id, pAttribute.status);
		}
		pOut.append("</tr>");
	}

	// --------------------------------------------------------------------------------------------------- step 5: master

	/**
	 * Pipeline-buttons lot (2026-09-23): "Transfers" (step 5) becomes "Master" - generate and load the
	 * GREENFIELD master zone. {@code master/generate}'s {@code overwriteTarget} flag is deliberately never
	 * exposed here (task A: "expose the flag only if trivial") - it only matters for the recovery path of a
	 * master zone generated with the wrong entity set, which is a {@code master/reset}-then-regenerate flow,
	 * out of this lot's scope.
	 */
	/**
	 * Wizard-visual-pass lot (2026-09-24, docs/mockups/wizard-flow/MasterStart.dc.html + MasterDone.dc.html):
	 * the primary "Build the master" one-click flow ({@code ebx.MasterBuildRun}), same three-state shape as
	 * {@link #renderStep4PrimaryBlock}: (1) not built yet - three {@link #renderExplainCard} cards ("what the
	 * factory does, in one run") then the primary button; (2) {@link Model#masterBuildRunRunning} - the
	 * three-stage progress list; (3) built - KPI tiles, the grid, "Open the table" links, "Continue to
	 * matching". The pre-existing separate Generate/Load buttons stay reachable under "Advanced".
	 */
	private static void renderStep5(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"5\"").append(WizardPage.hiddenAttr(pModel, 5)).append(">");
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step5.title"), WizardMessages.get(pLocale, "wizard.step5.lead"));
		final WizardRules.ActionGates gates = WizardRules.actionGates(pModel.snapshot, pModel.designer);
		WizardPage.render2ColStart(pOut);

		if (pModel.masterBuildRunRunning)
		{
			WizardPage.renderMasterLive(pOut, pModel, pLocale);
		}
		// live bug fix (2026-09-25, DistribTest, task brief item 6): "built" must be judged on the MASTER_LOAD
		// step (the same evidence stepStates' own Transfers.completed already uses), never on the grid's row
		// count - MasterBuildGridReader.build plans one row per ACCEPTED/APPLIED staging entity BEFORE any
		// build even runs (dashes in every cell), so a project with an accepted entity but no build yet already
		// had a non-empty grid, and the "Build and load" button never showed at all.
		else if (pModel.stepStates[WizardRules.STEP_TRANSFERS].completed)
		{
			WizardPage.renderStep5Done(pOut, pModel, pLocale);
		}
		else
		{
			pOut.append("<div class=\"mdmf-eyebrow\" style=\"margin:4px 0\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.hint.minutes")))
				.append("</div><div class=\"mdmf-kpi-row\">");
			WizardPage.renderExplainCard(pOut, 1, WizardMessages.get(pLocale, "wizard.step5.build.explain.tables.title"),
				WizardMessages.get(pLocale, "wizard.step5.build.explain.tables.text"));
			WizardPage.renderExplainCard(pOut, 2, WizardMessages.get(pLocale, "wizard.step5.build.explain.copy.title"),
				WizardMessages.get(pLocale, "wizard.step5.build.explain.copy.text"));
			WizardPage.renderExplainCard(pOut, 3, WizardMessages.get(pLocale, "wizard.step5.build.explain.matching.title"),
				WizardMessages.get(pLocale, "wizard.step5.build.explain.matching.text"));
			pOut.append("</div>");
			WizardPage.renderPrimaryActionBlockOpen(pOut);
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"MASTER_BUILD_RUN\"")
				.append(gates.generateMaster.enabled ? "" : " disabled=\"disabled\"").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.button"))).append("</button>");
			pOut.append("<div class=\"mdmf-primary-text\">");
			if (!gates.generateMaster.enabled)
			{
				pOut.append("<div>").append(WizardPage.esc(WizardMessages.get(pLocale, gates.generateMaster.reasonKey))).append("</div>");
			}
			pOut.append("<div>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.hint.nothingMerged"))).append("</div>");
			pOut.append("</div>");
			WizardPage.renderPrimaryActionBlockClose(pOut);
		}

		pOut.append("<details class=\"mdmf-disclosure\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.advanced")))
			.append("</summary>");
		WizardPage.renderMasterGenerateLoadAdvanced(pOut, pModel, gates, pLocale);
		pOut.append("</details>");
		WizardPage.render2ColSplit(pOut);
		WizardPage.renderStep5SideColumn(pOut, pModel, pLocale);
		WizardPage.render2ColEnd(pOut);
		WizardPage.renderStepReset(pOut, pModel, pLocale, "MASTER");
		pOut.append("</section>");
	}

	/**
	 * ROUND 3 of the wizard-visual-pass lot (2026-09-24, boards {@code MasterStart.dc.html}/{@code
	 * MasterDone.dc.html}): before a build, "From the staging" - one row per staging entity, its row count
	 * read straight off {@link Model#stagingLoadGrid}'s own "In staging" column (the LAST cell of each row
	 * - {@link StagingLoadGridReader}'s own column order), already-formatted text, never re-measured here.
	 * After a build, "Open in EBX" - the same {@link Model#masterOpenTableUrls} the done view's own primary
	 * block already links (no "Record lineage" link here - unlike the board, this model level carries no
	 * record-lineage URL to link to; omitted rather than invented, see {@code WizardSummaryData.Input
	 * #recordLineageUrl}, only built for step 7).
	 */
	private static void renderStep5SideColumn(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		// live bug fix (2026-09-25, task brief item 6): same MASTER_LOAD-based signal as renderStep5's own
		// "which view" decision above, never the grid's row count (see that call site's own comment).
		final boolean built = pModel.stepStates[WizardRules.STEP_TRANSFERS].completed;
		if (built)
		{
			WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.step5.side.openInEbx.title");
			for (final Map.Entry<String, String> entry : pModel.masterOpenTableUrls.entrySet())
			{
				pOut.append("<a href=\"").append(WizardPage.esc(entry.getValue())).append("\" target=\"_top\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.done.openTable", entry.getKey()))).append("</a>");
			}
			WizardPage.renderSideCardClose(pOut);
		}
		else
		{
			WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.step5.side.fromStaging.title");
			boolean anyRow = false;
			for (final WizardSnapshot.RunGridRow row : pModel.stagingLoadGrid.rows)
			{
				if (row.cells.isEmpty())
				{
					continue;
				}
				final WizardSnapshot.RunGridCell inStaging = row.cells.get(row.cells.size() - 1);
				if (inStaging.primaryText.isEmpty())
				{
					continue;
				}
				anyRow = true;
				pOut.append("<div style=\"display:flex;justify-content:space-between\"><span>").append(WizardPage.esc(row.label)).append("</span><b style=\"color:var(--ink)\">")
					.append(WizardPage.esc(inStaging.primaryText)).append("</b></div>");
			}
			// fix/wizard-live-wave1 (2026-09-24, live bug item 9): the card used to render its eyebrow with no
			// body at all when the staging was not loaded yet (every row skipped above) - a one-line hint
			// instead of a silently empty card.
			if (!anyRow)
			{
				pOut.append("<p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.side.fromStaging.empty"))).append("</p>");
			}
			WizardPage.renderSideCardClose(pOut);
		}
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.step5.glossary.master", "wizard.step1.glossary.goldenRecord", "wizard.step5.glossary.crossReference");
	}

	/** {@link Model#masterBuildRunRunning} false and {@link Model#masterBuildGrid} non-empty: the "built" view - KPI tiles, the grid, "Continue to matching". */
	private static void renderStep5Done(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-panel\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.done.title")))
			.append("</h3><p class=\"mdmf-lead\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.done.lead"))).append("</p></div>");

		WizardPage.renderKpiRowOpen(pOut);
		for (final String[] tile : pModel.masterKpiTiles)
		{
			WizardPage.renderKpiTile(pOut, tile[0], tile[1]);
		}
		if (pModel.masterHeldBackInStagingCount > 0)
		{
			WizardPage.renderKpiTile(pOut, String.valueOf(pModel.masterHeldBackInStagingCount),
				WizardMessages.get(pLocale, "wizard.step5.build.done.heldBack", Long.valueOf(pModel.masterHeldBackInStagingCount)));
		}
		WizardPage.renderKpiRowClose(pOut);

		WizardPage.renderRunGrid(pOut, pModel.masterBuildGrid, pLocale, "wizard.step4.grid.header");
		WizardPage.renderRunTrace(pOut, pLocale, pModel.masterBuildRunProgress, "wizard.step5.live.stage.");

		WizardPage.renderPrimaryActionBlockOpen(pOut);
		// step-gating lot (2026-09-24): this used to render whenever masterBuildGrid was non-empty, whether or
		// not MASTER_LOAD had genuinely finished (a partial/failed build could still show a non-empty grid) -
		// gated on WizardRules.stepStates' own Matching prerequisite now, same as the rail and Next.
		final WizardRules.StepState matchingState = pModel.stepStates[WizardRules.STEP_MATCHING];
		if (matchingState.reachable)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"6\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.done.continue"))).append("</button>");
		}
		else
		{
			pOut.append("<div class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, matchingState.blockedKey))).append("</div>");
		}
		pOut.append("<div class=\"mdmf-primary-text\">");
		if (!pModel.masterOpenTableUrls.isEmpty())
		{
			for (final Map.Entry<String, String> entry : pModel.masterOpenTableUrls.entrySet())
			{
				pOut.append("<div><a href=\"").append(WizardPage.esc(entry.getValue())).append("\" target=\"_top\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.build.done.openTable", entry.getKey()))).append("</a></div>");
			}
		}
		pOut.append("</div>");
		WizardPage.renderPrimaryActionBlockClose(pOut);
	}

	/** The pre-existing separate "Generate the master"/"Load the master" buttons, now under step 5's "Advanced" disclosure - see {@link #renderStep5}'s own Javadoc. */
	private static void renderMasterGenerateLoadAdvanced(final StringBuilder pOut, final Model pModel, final WizardRules.ActionGates pGates, final Locale pLocale)
	{
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"MASTER_GENERATE\"")
			.append(pGates.generateMaster.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.generate"))).append("</button>");
		if (!pGates.generateMaster.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.generateMaster.reasonKey))).append("</span>");
		}
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"MASTER_LOAD\"")
			.append(pGates.loadMaster.enabled ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step5.load")))
			.append("</button>");
		if (!pGates.loadMaster.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pGates.loadMaster.reasonKey))).append("</span>");
		}
		pOut.append("</div>");
		if (pModel.snapshot.project != null && !pModel.snapshot.project.masterDataspace.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage
				.esc(WizardMessages.get(pLocale, "wizard.step5.dataspace", pModel.snapshot.project.masterDataspace, pModel.snapshot.project.masterDataset))).append("</p>");
		}
		final List<WizardSnapshot.PipelineStepInfo> steps = WizardPage.filterSteps(pModel.snapshot.projectSteps, "MASTER_MODEL", "MASTER_DATASET", "MASTER_LOAD");
		if (!steps.isEmpty())
		{
			WizardPage.renderTrack(pOut, pLocale, steps);
		}
	}

	// --------------------------------------------------------------------------------------------------- step 6: matching

	private static void renderStep6(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"6\"").append(WizardPage.hiddenAttr(pModel, 6)).append(">");
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step6.title"), WizardMessages.get(pLocale, "wizard.step6.lead"));
		final WizardRules.ActionGates gates = WizardRules.actionGates(pModel.snapshot, pModel.designer);
		WizardPage.render2ColStart(pOut);

		// UX lot U1 (2026-10-13, RAPPORT 2.11/3.1/3.4): ONE primary button, "Propose the matching plans". It runs the rules and - when a language-model
		// connector is configured and an accepted object got no plan from them - the model, in the same job (SuggestRulesRun#MATCHING_HANDLER, the chain step 4's
		// "Propose the staging" already has). "Refine with the language model" stays under "Advanced" for a re-run. Once plans or gaps exist the whole block
		// folds under "Propose the plans again" (still reachable, never removed).
		final List<String> candidates = MatchingPlanCoverage.candidates(pModel.snapshot);
		final WizardSnapshot.PipelineStepInfo modelStep = MatchingPlanCoverage.modelStep(pModel.snapshot);
		final boolean modelRan = modelStep != null;
		final boolean hasPlans = !pModel.snapshot.matchPlans.isEmpty();
		final boolean coverageKnown = hasPlans || modelRan || MatchingPlanCoverage.rulesRan(pModel.snapshot, pModel.recentTasks);
		// lot M17 (2026-10-18, docs/MATCHING.md sec 69): an object the facts mark as needing no plan (one source system, no key shared by two of its rows) has its own card, "No plan -
		// one source only, no shared key (measured fact)", and is neither a gap nor silent
		final java.util.Map<String, String> noPlanFacts = modelRan ? MatchingPlanCoverage.noPlanNeeded(modelStep.detail) : new java.util.LinkedHashMap<String, String>();
		final List<MatchingPlanCoverage.NoPlan> noPlans = coverageKnown ? MatchingPlanCoverage.noPlans(candidates, MatchingPlanCoverage.withPlan(pModel.snapshot), noPlanFacts)
			: new ArrayList<MatchingPlanCoverage.NoPlan>();
		final List<MatchingPlanCoverage.Gap> gaps = coverageKnown
			? MatchingPlanCoverage.gaps(candidates, MatchingPlanCoverage.withPlan(pModel.snapshot), modelRan, modelRan ? MatchingPlanCoverage.modelNotes(modelStep.detail) : null,
				modelRan ? MatchingPlanCoverage.modelRefused(modelStep.detail) : null, modelRan ? MatchingPlanCoverage.modelAttempts(modelStep.detail) : null, noPlanFacts)
			: new ArrayList<MatchingPlanCoverage.Gap>();
		final boolean modelUsable = pModel.designer && pModel.snapshot.llmConfigured;
		final long landingRows = MatchingVolume.landingRows(pModel.snapshot);
		final boolean folded = hasPlans || !gaps.isEmpty() || !noPlans.isEmpty();
		if (folded)
		{
			pOut.append("<details class=\"mdmf-disclosure mdmf-step6-propose\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.proposeAgain")))
				.append("</summary>");
		}
		pOut.append("<div class=\"mdmf-panel mdmf-step6-propose-card\"><div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"MATCHING_SUGGEST\"")
			.append(gates.suggestMatching.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.suggest"))).append("</button>");
		if (!gates.suggestMatching.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, gates.suggestMatching.reasonKey))).append("</span>");
		}
		pOut.append("</div>");
		if (modelUsable)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.suggest.chained"))).append(' ')
				.append(WizardPage.esc(WizardPage.llmDisclosureText(pModel, pLocale))).append("</p>");
		}
		else if (pModel.designer)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.suggest.rulesOnly"))).append("</p>");
		}
		pOut.append("<details class=\"mdmf-disclosure mdmf-step6-advanced\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.advanced")))
			.append("</summary><div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"MATCHING_SUGGEST_LLM\"").append(gates.suggestMatchingLlm.enabled ? "" : " disabled=\"disabled\"")
			.append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.suggestLlm"))).append("</button>");
		if (!gates.suggestMatchingLlm.enabled)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, gates.suggestMatchingLlm.reasonKey))).append("</span>");
		}
		pOut.append("</div></details></div>");
		if (folded)
		{
			pOut.append("</details>");
		}
		if (pModel.llmRunSummary != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(WizardPage.esc(pModel.llmRunSummary)).append("</div>");
		}

		if (!hasPlans && gaps.isEmpty() && noPlans.isEmpty())
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.empty"))).append("</div>");
		}
		else
		{
			if (!gaps.isEmpty())
			{
				pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.gap.summary",
					Integer.valueOf(gaps.size()), Integer.valueOf(candidates.size()), String.join(", ", MatchingPlanCoverage.names(gaps))))).append("</div>");
			}
			// the objects in the order of the staging, each with its plan card(s), or - the point of the lot - the card that says why it has none
			final List<MatchingPlanCoverage.Follower> followers = MatchingPlanCoverage.followers(pModel.snapshot);
			final Set<String> withPlan = MatchingPlanCoverage.withPlan(pModel.snapshot);
			final Set<String> drawn = new HashSet<String>();
			for (final WizardSnapshot.StagingEntityInfo entity : pModel.snapshot.stagingEntities)
			{
				for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
				{
					if (plan.entityName.equals(entity.name))
					{
						WizardPage.renderMatchPlanOf(pOut, pLocale, plan, pModel, landingRows);
						drawn.add(plan.id);
					}
				}
				for (final MatchingPlanCoverage.Gap gap : gaps)
				{
					if (gap.entity.equals(entity.name))
					{
						WizardPage.renderMatchGap(pOut, pLocale, gap, modelUsable && !modelRan && gates.suggestMatchingLlm.enabled);
					}
				}
				// lot M17: an object the facts mark as needing no plan says so, with the measured fact
				for (final MatchingPlanCoverage.NoPlan noPlan : noPlans)
				{
					if (noPlan.entity.equals(entity.name))
					{
						WizardPage.renderMatchNoPlan(pOut, pLocale, noPlan);
					}
				}
				// lot M2 (2026-10-17): a child entity has no plan of its own - its card says whom it follows (when it has no plan row; one an earlier pass wrote is drawn above)
				for (final MatchingPlanCoverage.Follower follower : followers)
				{
					if (follower.entity.equals(entity.name) && !withPlan.contains(follower.entity))
					{
						WizardPage.renderMatchFollows(pOut, pLocale, follower);
					}
				}
			}
			for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
			{
				if (!drawn.contains(plan.id))
				{
					WizardPage.renderMatchPlanOf(pOut, pLocale, plan, pModel, landingRows);
				}
			}
		}

		WizardPage.renderMatchingTriggerChoice(pOut, pModel, pLocale);
		// UX lot D (2026-10-14): the objects the last configuration refused, with the button that configures them again
		pOut.append(PartialFailuresView.configureBlock(pModel, pLocale, gates.configureMatching.enabled && WizardPage.step6ConfiguringCount(pModel) == 0));

		// board's purple "Run matching" call-to-action. Configure stays its own visible action next to it (see
		// docs/WIZARD-VISUAL.md ROUND 2: it flips the add-on's activation, which Run's own gate depends on).
		// UX lot C2 (2026-10-14): while a Configure job is queued or running, Run is disabled with its reason - "Run" clicked 6 s after "Configure" answered
		// "1 entity run, 2 not applied" and the results showed one object of three (UxCrmSf2)
		final int configuring = WizardPage.step6ConfiguringCount(pModel);
		final boolean runEnabled = gates.runMatching.enabled && configuring == 0;
		WizardPage.renderPrimaryActionBlockOpen(pOut);
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"all\"")
			.append(runEnabled ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.RUN_ICON)
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.run.primary"))).append("</button>");
		pOut.append("<span class=\"mdmf-primary-text\"><span><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.run.lead"))).append("</b> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.run.text"))).append("</span>");
		// UX lot U1: ONE excuse line, and only while its condition holds. Before: "Configure at least one matching plan first." stayed beside an enabled
		// Configure button, and "Accept at least one matching plan first." beside an enabled Run - each gate printed its own reason whatever the other said.
		final String excuseKey = WizardPage.step6ExcuseKey(pModel, gates);
		if (configuring > 0 && !"wizard.gate.adminOnly".equals(excuseKey))
		{
			pOut.append("<span class=\"mdmf-hint\" id=\"mdmfStep6Configuring\">")
				.append(WizardPage.esc(WizardMessages.plural(pLocale, configuring, "wizard.step6.run.configuring.one", "wizard.step6.run.configuring.other"))).append("</span>");
		}
		else if (excuseKey != null)
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, excuseKey))).append("</span>");
		}
		pOut.append(PartialFailuresView.runWarning(pModel, pLocale));
		pOut.append("<span class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MATCHING_CONFIGURE\"")
			.append(gates.configureMatching.enabled ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.configure"))).append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MATCHING_RUN\" data-mdmf-scope=\"incremental\"")
			.append(runEnabled ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.run.incremental")))
			.append("</button>");
		pOut.append("</span>");
		final List<WizardSnapshot.PipelineStepInfo> steps = WizardPage.filterSteps(pModel.snapshot.projectSteps, "MATCHING_SUGGEST_LLM");
		// bug fixed live 2026-09-24 (project Vague1, bug 7b): this PipelineStep track is a permanent record of
		// every past run ("1 matching plan(s) written...", AgentRun 46 in the live case) - it never gets cleared
		// or updated when the plan(s) it refers to are later deleted (a project reset wipes every MatchPlan, not
		// the PipelineStep history that documented creating them), so it kept showing a run's summary for plans
		// that no longer exist. Only show it when the project actually still has at least one matching plan.
		if (!steps.isEmpty() && !pModel.snapshot.matchPlans.isEmpty())
		{
			WizardPage.renderTrack(pOut, pLocale, steps);
		}
		pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.mameHint"))).append("</span></span>");
		WizardPage.renderPrimaryActionBlockClose(pOut);
		WizardPage.renderMatchingLive(pOut, pModel, pLocale);
		pOut.append(PartialFailuresView.runBlock(pModel, pLocale));

		// wizard-matching-results lot (2026-09-24): the KPIs/band/suspect-queue block, one per entity whose plan
		// is ACCEPTED or APPLIED (a PROPOSED/REJECTED/SUPERSEDED plan has nothing running yet to show results
		// for) and for which ebx.ui.ProjectWizardUserService actually built one (model.matchResults) - see that
		// class' own Javadoc for why a plan can legitimately have no entry (matching not yet activated).
		// Deliberately BELOW every plan card and the run buttons above (task brief: those stay untouched).
		// id="mdmf-step6-results": always present (even with nothing inside) so the side column's "Next: results"
		// link always resolves, whether or not any plan has produced results yet.
		pOut.append("<div id=\"mdmf-step6-results\">");
		boolean anyResults = false;
		for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
		{
			if (!ReviewLine.ACCEPTED.equals(plan.status) && !ReviewLine.APPLIED.equals(plan.status))
			{
				continue;
			}
			final MatchResultsView.Input input = pModel.matchResults.get(plan.id);
			if (input != null)
			{
				pOut.append(MatchResultsView.render(input, pLocale));
				anyResults = true;
			}
		}
		pOut.append("</div>");
		if (anyResults)
		{
			// board MatchingResults.dc.html's closing call-to-action: on to step 7 (same GOTO_STEP the rail uses)
			WizardPage.renderPrimaryActionBlockOpen(pOut);
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"8\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.summaryCta"))).append("</button><span class=\"mdmf-primary-text\"><span>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.summaryCta.text"))).append("</span></span>");
			WizardPage.renderPrimaryActionBlockClose(pOut);
		}

		WizardPage.render2ColSplit(pOut);
		WizardPage.renderStep6Side(pOut, pLocale, anyResults);
		WizardPage.render2ColEnd(pOut);
		WizardPage.renderStepReset(pOut, pModel, pLocale, "MATCHING");
		pOut.append("</section>");
	}

	/** One plan card, its volume notes folded or inline by the row count of its entity ({@link MatchingVolume}). */
	private static void renderMatchPlanOf(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.MatchPlanInfo pPlan, final Model pModel, final long pLandingRows)
	{
		final MatchResultsView.Input results = pModel.matchResults.get(pPlan.id);
		WizardPage.renderMatchPlan(pOut, pLocale, pPlan, pModel, !MatchingVolume.inline(MatchingVolume.rowsFor(results == null ? null : results.kpis, pLandingRows)));
	}

	/** Whether a matching plan has this status ({@code ACCEPTED}, {@code APPLIED}...). */
	private static boolean hasPlanWithStatus(final Model pModel, final String pStatus)
	{
		for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
		{
			if (pStatus.equals(plan.status))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * UX lot C2: how many objects the Configure job queued or running right now is configuring (the accepted and applied plans), 0 when no Configure is in
	 * progress. Read from the live panel's own activity, so the button and the panel never disagree.
	 */
	static int step6ConfiguringCount(final Model pModel)
	{
		final com.on.ebx.factory.matching.MatchingActivity activity = pModel.matchingActivity;
		if (activity == null || !com.on.ebx.factory.matching.MatchingActivity.CONFIGURE.equals(activity.kind) || !activity.isLive())
		{
			return 0;
		}
		int count = 0;
		for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
		{
			if (ReviewLine.ACCEPTED.equals(plan.status) || ReviewLine.APPLIED.equals(plan.status))
			{
				count++;
			}
		}
		return Math.max(1, count);
	}

	/**
	 * UX lot U1: the one line that says what stands between the person and "Run matching", or null when nothing does. An administrator-only refusal is kept as is;
	 * otherwise: no plan accepted yet; accepted but not configured; or some plans accepted after the last Configure (then Run still works on the configured ones).
	 */
	static String step6ExcuseKey(final Model pModel, final WizardRules.ActionGates pGates)
	{
		if ("wizard.gate.adminOnly".equals(pGates.runMatching.reasonKey) || "wizard.gate.adminOnly".equals(pGates.configureMatching.reasonKey))
		{
			return "wizard.gate.adminOnly";
		}
		final boolean accepted = WizardPage.hasPlanWithStatus(pModel, ReviewLine.ACCEPTED);
		final boolean applied = WizardPage.hasPlanWithStatus(pModel, ReviewLine.APPLIED);
		if (!accepted && !applied)
		{
			return "wizard.gate.needAcceptedMatchPlan";
		}
		if (accepted && !applied)
		{
			return "wizard.step6.run.configureFirst";
		}
		return accepted ? "wizard.step6.run.configureAgain" : null;
	}

	/**
	 * Lot MT (2026-10-04): "When is matching done?" - the project's {@code Project.matchingTrigger}, two cards (on demand / in real time)
	 * with one sentence each and a save button ({@code MATCHING_SET_TRIGGER}, posts {@code mdmfMatchingTriggerRadio}). Same radio-card
	 * pattern as {@link #renderLlmSharingCards}. The choice reaches the add-on's policies at the next Configure - the hint says so.
	 * Not shown before the project exists.
	 */
	private static void renderMatchingTriggerChoice(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		if (pModel.snapshot.project == null || pModel.snapshot.project.id.isEmpty())
		{
			return;
		}
		final String current = pModel.snapshot.project.matchingTrigger;
		pOut.append("<div class=\"mdmf-field mdmf-matching-trigger\">");
		pOut.append("<label id=\"mdmfMatchingTriggerLabel\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.trigger.title"))).append("</label>");
		pOut.append("<div class=\"mdmf-mode-cards\" role=\"radiogroup\" aria-labelledby=\"mdmfMatchingTriggerLabel\">");
		for (final String mode : new String[] { "MANUAL", "REALTIME" })
		{
			final boolean selected = mode.equals(current);
			pOut.append("<label class=\"mdmf-choice-card mdmf-mode-card").append(selected ? " selected" : "").append("\">");
			pOut.append("<input type=\"radio\" name=\"mdmfMatchingTriggerRadio\" value=\"").append(mode).append("\"").append(selected ? " checked=\"checked\"" : "").append("/>");
			pOut.append("<span class=\"mdmf-choice-card-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.trigger." + mode + ".title"))).append("</span>");
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.trigger." + mode + ".text"))).append("</span>");
			pOut.append("</label>");
		}
		pOut.append("</div><div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MATCHING_SET_TRIGGER\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.trigger.save"))).append("</button>");
		// UX lot U1: "Configure matching again to apply it" only means something once plans ARE configured; before, the first Configure applies the choice anyway
		if (WizardPage.hasPlanWithStatus(pModel, ReviewLine.APPLIED))
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.trigger.hint"))).append("</span>");
		}
		pOut.append("</div></div>");
	}

	/** Matching-live lot (2026-09-27): message keys the panel's script needs (raw patterns, {0} replaced by the script). */
	private static final String[] MATCHING_LIVE_LABELS = { "wizard.step6.live.state.QUEUED", "wizard.step6.live.state.RUNNING", "wizard.step6.live.state.DONE",
		"wizard.step6.live.state.FAILED", "wizard.step6.live.state.STOPPED", "wizard.step6.live.inLine", "wizard.step3.run.live.running", "wizard.step3.run.live.took",
		"wizard.step6.live.title.RUN.live", "wizard.step6.live.title.CONFIGURE.live", "wizard.step6.live.title.RUN.QUEUED", "wizard.step6.live.title.CONFIGURE.QUEUED", "wizard.step6.live.details" };

	/**
	 * Matching-live lot (2026-09-27, asked by the product owner on project ret): the last matching action - "Run matching" or
	 * "Configure" - right under their buttons, from the click to after the end. Before, a run of a few seconds
	 * left only a green "started" line and the jobs banner at the top of the page while the page re-opened on the
	 * button: nothing where the person looks. Queued, then one line per entity (in line / running with its timer
	 * / done / failed with the entity step's own message); once a run is over, each entity's pairs to decide,
	 * merged groups and distinct pairs (the step's own results, {@link Model#matchResults}) and a link to its
	 * results block. {@code wizard.js} ("matching live panel") polls {@code GET .../matching/activity} every 2 s
	 * while the action is queued or running; the page itself is re-rendered at the job's end by the jobs banner.
	 */
	private static void renderMatchingLive(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final com.on.ebx.factory.matching.MatchingActivity activity = pModel.matchingActivity;
		if (activity == null || com.on.ebx.factory.matching.MatchingActivity.NONE.equals(activity.status) || pModel.snapshot.project == null)
		{
			return;
		}
		final boolean live = activity.isLive();
		final com.fasterxml.jackson.databind.node.ObjectNode labels = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		for (final String key : WizardPage.MATCHING_LIVE_LABELS)
		{
			labels.put(key.substring(key.indexOf(".live.") + 1), WizardMessages.get(pLocale, key));
		}
		final String url = WizardPage.JOBS_REST + "projects/" + WizardPage.urlSegment(pModel.snapshot.project.code) + "/matching/activity?lang=" + (pLocale == null ? "en" : pLocale.getLanguage());
		pOut.append("<div class=\"mdmf-panel mdmf-lr mdmf-ma\" id=\"mdmfMatchingLive\" data-mdmf-ma-live=\"").append(live).append("\" data-mdmf-ma-url=\"")
			.append(WizardPage.esc(url)).append("\" data-mdmf-lr-server-now=\"").append(pModel.nowMs).append("\" data-mdmf-ma-kind=\"").append(WizardPage.esc(activity.kind))
			.append("\" data-mdmf-lr-labels=\"").append(WizardPage.esc(labels.toString())).append("\">");

		// header: what the action is and where it stands
		pOut.append("<div class=\"mdmf-lr-head\">");
		if (live)
		{
			pOut.append("<span class=\"mdmf-lr-spinner\" aria-hidden=\"true\"></span>");
		}
		final String title;
		if (live)
		{
			title = WizardMessages.get(pLocale, "wizard.step6.live.title." + activity.kind + (com.on.ebx.factory.matching.MatchingActivity.QUEUED.equals(activity.status) ? ".QUEUED" : ".live"));
		}
		else
		{
			final String time = activity.endedAt > 0L ? new java.text.SimpleDateFormat("HH:mm", Locale.ROOT).format(new java.util.Date(activity.endedAt)) : "";
			final String took = activity.startedAt > 0L && activity.endedAt > 0L ? WizardPage.durationText(activity.endedAt - activity.startedAt) : "";
			title = WizardMessages.get(pLocale, "wizard.step6.live.title." + activity.kind + ".ended").replace("{0}", WizardMessages.get(pLocale, "wizard.step6.live.state." + activity.status))
				.replace("{1}", time).replace("{2}", took);
		}
		pOut.append("<div class=\"mdmf-lr-headtext\"><div class=\"mdmf-lr-title\" id=\"mdmfMaTitle\">").append(WizardPage.esc(title)).append("</div>");
		if (!live && !activity.message.isEmpty())
		{
			pOut.append("<div class=\"mdmf-lr-now\">").append(WizardPage.esc(RunPanelText.display(activity.message, pLocale))).append("</div>");
		}
		if (!live)
		{
			// lot M6 (2026-10-18): a configure that validated the main table alone (the add-on's business-object validation threw) says why, in the person's language
			for (final String note : activity.notes)
			{
				pOut.append("<div class=\"mdmf-hint mdmf-hint-warn\">").append(WizardPage.esc(RunPanelText.display(note, pLocale))).append("</div>");
			}
		}
		pOut.append("</div></div>");

		// one line per entity: the action's own entity steps, plus (while live) the accepted plans not reached yet
		final List<String> names = new ArrayList<String>();
		for (final com.on.ebx.factory.matching.MatchingActivity.Entity entity : activity.entities)
		{
			names.add(entity.name);
		}
		final Map<String, WizardSnapshot.MatchPlanInfo> planByEntity = new LinkedHashMap<String, WizardSnapshot.MatchPlanInfo>();
		for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
		{
			if (ReviewLine.ACCEPTED.equals(plan.status) || ReviewLine.APPLIED.equals(plan.status))
			{
				planByEntity.put(plan.entityName, plan);
				if (live && !names.contains(plan.entityName))
				{
					names.add(plan.entityName);
				}
			}
		}
		final boolean withCounts = !live && com.on.ebx.factory.matching.MatchingActivity.RUN.equals(activity.kind);
		// UX lot C2: a finished run lists every accepted plan it left out ("not applied": accepted, never configured) as its own row instead of ignoring it -
		// "1 entity run, 2 not applied" showed a table with one row (UxCrmSf2). It has no step, hence no entity of the activity.
		final Set<String> notConfigured = new HashSet<String>();
		if (withCounts)
		{
			for (final WizardSnapshot.MatchPlanInfo plan : pModel.snapshot.matchPlans)
			{
				if (ReviewLine.ACCEPTED.equals(plan.status) && activity.entity(plan.entityName) == null)
				{
					names.add(plan.entityName);
					notConfigured.add(plan.entityName);
				}
			}
		}
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table mdmf-ma-table\"><thead><tr><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.col.entity"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.col.status"))).append("</th>");
		if (withCounts)
		{
			// UX lot U1: the scope (records compared out of the table's) is a column of its own, not a clause of a paragraph
			for (final String col : new String[] { "compared", "pending", "merged", "distinct" })
			{
				pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.col." + col))).append("</th>");
			}
		}
		pOut.append("<th></th></tr></thead><tbody id=\"mdmfMaBody\">");
		for (final String name : names)
		{
			final com.on.ebx.factory.matching.MatchingActivity.Entity entity = activity.entity(name);
			final String status = entity == null ? "TODO" : entity.status;
			final WizardSnapshot.MatchPlanInfo plan = planByEntity.get(name);
			final MatchResultsView.Input results = plan == null ? null : pModel.matchResults.get(plan.id);
			// UX lot U1: a finished run's row says what happened in one sentence; the engine's own text (and the long volume warning it carries) is behind "Details"
			final MatchingResultRow row = withCounts && entity != null ? MatchingResultRow.of(pLocale, status, entity.message, results == null ? null : results.kpis) : null;
			pOut.append("<tr data-mdmf-ma-entity=\"").append(WizardPage.esc(name)).append("\"><td><b>").append(WizardPage.esc(name)).append("</b></td><td class=\"mdmf-ma-status\">");
			if (notConfigured.contains(name))
			{
				WizardPage.renderStateCircle(pOut, LandingRunProgress.SKIPPED, false);
				pOut.append("<span class=\"mdmf-lr-label\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.state.NOTRUN"))).append("</span>")
					.append("<div class=\"mdmf-ma-sentence\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.result.notConfigured"))).append("</div>");
			}
			else
			{
				WizardPage.matchingEntityStatus(pOut, pLocale, entity, pModel.nowMs, row);
			}
			if (row != null && MatchingVolume.inline(MatchingVolume.rowsFor(results == null ? null : results.kpis, MatchingVolume.landingRows(pModel.snapshot)))
				&& !row.warning.isEmpty())
			{
				pOut.append("<div class=\"mdmf-hint mdmf-hint-warn\">").append(WizardPage.esc(row.warning)).append("</div>");
			}
			pOut.append("</td>");
			if (withCounts)
			{
				pOut.append("<td>").append(WizardPage.esc(row == null ? "\u2013" : row.compared)).append("</td><td>").append(WizardPage.esc(row == null ? "\u2013" : row.pending))
					.append("</td><td>").append(WizardPage.esc(row == null ? "\u2013" : row.merged)).append("</td><td>").append(WizardPage.esc(row == null ? "\u2013" : row.distinct))
					.append("</td>");
			}
			pOut.append("<td>");
			if (!live && results != null)
			{
				pOut.append("<a href=\"#mdmf-match-results-").append(WizardPage.esc(results.entityId)).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.seeResults"))).append("</a>");
			}
			pOut.append("</td></tr>");
		}
		pOut.append("</tbody></table></div></div>");
	}

	/** One entity's status cell: a state circle, the state, then the time and the step's message. Mirrored by wizard.js' matching {@code statusHtml}. */
	private static void matchingEntityStatus(final StringBuilder pOut, final Locale pLocale, final com.on.ebx.factory.matching.MatchingActivity.Entity pEntity, final long pNow,
		final MatchingResultRow pRow)
	{
		if (pEntity == null)
		{
			WizardPage.renderStateCircle(pOut, LandingRunProgress.TODO, false);
			pOut.append("<span class=\"mdmf-lr-label\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.inLine"))).append("</span>");
			return;
		}
		final String circle = com.on.ebx.factory.matching.MatchingActivity.STOPPED.equals(pEntity.status) ? LandingRunProgress.SKIPPED : pEntity.status;
		WizardPage.renderStateCircle(pOut, circle, false);
		pOut.append("<span class=\"mdmf-lr-label\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.live.state." + pEntity.status))).append("</span>");
		final boolean running = com.on.ebx.factory.matching.MatchingActivity.RUNNING.equals(pEntity.status);
		if (pEntity.startedAt > 0L)
		{
			pOut.append(" <span class=\"mdmf-hint mdmf-ma-timer\"").append(running ? " data-mdmf-lr-since=\"" + pEntity.startedAt + "\"" : "").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, running ? "wizard.step3.run.live.running" : "wizard.step3.run.live.took")
					.replace("{0}", WizardPage.durationText((pEntity.endedAt > 0L ? pEntity.endedAt : pNow) - pEntity.startedAt))))
				.append("</span>");
		}
		if (pRow != null)
		{
			// the one sentence, then the raw text under "Details" (never a red log line in the cell)
			if (!pRow.sentence.isEmpty())
			{
				pOut.append("<div class=\"mdmf-ma-sentence").append(com.on.ebx.factory.matching.MatchingActivity.FAILED.equals(pEntity.status) ? " mdmf-ma-error" : "").append("\">")
					.append(WizardPage.esc(pRow.sentence)).append("</div>");
			}
			WizardPage.matchingDetailsFold(pOut, pLocale, pRow.details);
		}
		else if (!pEntity.message.isEmpty() && !running)
		{
			// lot M12: the step message carries the code of its sentences: worded in the viewer's language here too (a configure's entity step, a run without counts)
			final String text = RunPanelText.display(pEntity.message, pLocale);
			if (text.length() > WizardPage.MATCHING_INLINE_MESSAGE_MAX || com.on.ebx.factory.matching.MatchingActivity.FAILED.equals(pEntity.status))
			{
				WizardPage.matchingDetailsFold(pOut, pLocale, text);
			}
			else
			{
				pOut.append("<div class=\"mdmf-hint").append(com.on.ebx.factory.matching.MatchingActivity.FAILED.equals(pEntity.status) ? " mdmf-ma-error" : "").append("\">")
					.append(WizardPage.esc(text)).append("</div>");
			}
		}
	}

	/** A step message longer than this is not shown inline in the live panel: it goes under "Details" (wizard.js mirrors the rule). */
	private static final int MATCHING_INLINE_MESSAGE_MAX = 160;

	/** UX lot U1: the engine's own text of an entity step under a per-row "Details" disclosure; nothing when there is none. */
	private static void matchingDetailsFold(final StringBuilder pOut, final Locale pLocale, final String pText)
	{
		if (pText == null || pText.isEmpty())
		{
			return;
		}
		pOut.append("<details class=\"mdmf-notice-details mdmf-ma-details\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.volume.details")))
			.append("</summary><div class=\"mdmf-hint\">").append(WizardPage.esc(pText)).append("</div></details>");
	}

	/**
	 * Board {@code MatchingPlan.dc.html}'s side column - visual pass lot (2026-09-24): the step's glossary
	 * (Matching plan / Sure match / To decide) and a "Next: results" line - the results block is already on
	 * THIS page (unlike the board's separate screen), so the link scrolls to it rather than navigating away.
	 */
	private static void renderStep6Side(final StringBuilder pOut, final Locale pLocale, final boolean pWithResults)
	{
		if (pWithResults)
		{
			WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.step6.side.undo.title");
			pOut.append("<div>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.side.undo.text"))).append("</div>");
			WizardPage.renderSideCardClose(pOut);
		}
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.step6.glossary.plan", "wizard.step6.glossary.candidateSearch", "wizard.step6.glossary.sureMatch",
			"wizard.step6.glossary.toDecide", "wizard.step1.glossary.goldenRecord");
		pOut.append("<div class=\"mdmf-side-next\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.side.nextLabel"))).append(" <a href=\"#mdmf-step6-results\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.side.nextLink"))).append("</a></div>");
	}

	/**
	 * UX lot U1 (2026-10-13): the card of an accepted business object that has NO matching plan - what the page used to leave out (Organisation after the
	 * language-model pass: no card, no line, no reason). It says why in the person's words ({@link MatchingPlanCoverage#reasonText}), what would let the rules
	 * propose a plan, that the object is left out of matching until a plan exists, and offers "Ask the language model" when it has not been asked yet.
	 * There is no "No matching for this object" button here: that decision belongs to a plan row, and without a plan the object is already out of the run.
	 */
	private static void renderMatchGap(final StringBuilder pOut, final Locale pLocale, final MatchingPlanCoverage.Gap pGap, final boolean pCanAskModel)
	{
		pOut.append("<div class=\"mdmf-match-plan mdmf-match-plan-none\" data-mdmf-gap=\"").append(WizardPage.esc(pGap.entity)).append("\"><header><span class=\"mdmf-match-plan-name\">")
			.append(WizardPage.esc(pGap.entity)).append("</span><span class=\"mdmf-chip mdmf-chip-warn\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.gap.chip"))).append("</span></header>");
		pOut.append("<p><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.gap.title", pGap.entity))).append("</b> ")
			.append(WizardPage.esc(MatchingPlanCoverage.reasonText(pLocale, pGap))).append("</p>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.gap.unblock"))).append(' ')
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.gap.leftOut"))).append("</p>");
		if (pCanAskModel)
		{
			pOut.append("<div class=\"mdmf-match-plan-decide\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"MATCHING_SUGGEST_LLM\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.gap.askModel"))).append("</button></div>");
		}
		pOut.append("</div>");
	}

	/**
	 * Lot M17 (2026-10-18, docs/MATCHING.md sec 69): the card of an object the facts mark as needing no plan - one source system feeds it and no two of its rows share an exact key. A
	 * measured fact, not a warning: no button, nothing is missing.
	 */
	private static void renderMatchNoPlan(final StringBuilder pOut, final Locale pLocale, final MatchingPlanCoverage.NoPlan pNoPlan)
	{
		pOut.append("<div class=\"mdmf-match-plan mdmf-match-plan-follows\" data-mdmf-noplan=\"").append(WizardPage.esc(pNoPlan.entity)).append("\"><header><span class=\"mdmf-match-plan-name\">")
			.append(WizardPage.esc(pNoPlan.entity)).append("</span><span class=\"mdmf-chip\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.noPlan.chip"))).append("</span></header>");
		pOut.append("<p><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.noPlan.title", pNoPlan.entity))).append("</b> ")
			.append(WizardPage.esc(MatchingPlanCoverage.noPlanReason(pLocale))).append("</p>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.noPlan.facts", pNoPlan.source))).append("</p></div>");
	}

	/**
	 * Lot M2 (2026-10-17, docs/MATCHING.md sect;56): the card of a CHILD object (an address, a phone, a purchase of a person). It has no matching plan of its own on purpose: the
	 * parent's plan already compares its values and the master load re-points it to the golden record. No button: nothing is missing.
	 */
	private static void renderMatchFollows(final StringBuilder pOut, final Locale pLocale, final MatchingPlanCoverage.Follower pFollower)
	{
		final String parents = String.join(", ", pFollower.parents);
		pOut.append("<div class=\"mdmf-match-plan mdmf-match-plan-follows\" data-mdmf-follows=\"").append(WizardPage.esc(pFollower.entity)).append("\"><header><span class=\"mdmf-match-plan-name\">")
			.append(WizardPage.esc(pFollower.entity)).append("</span><span class=\"mdmf-chip\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.follows.chip", parents))).append("</span></header>");
		pOut.append("<p><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.follows.title", pFollower.entity, parents))).append("</b> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.matching.follows.text", parents))).append("</p></div>");
	}

	/**
	 * One {@code MatchPlan} line: entity, status, confidence, requirements in plain words, all three actions
	 * decidable in place - Accept, "No matching for this entity" (mandatory confirmation) and "Propose
	 * another" (comment box), the latter two through {@link #renderMatchInlinePanels}, ported from {@code
	 * ReviewPage} (inline-reviews lot, 2026-09-23: the product owner, "l'acces aux reviews devrait se faire dans
	 * l'ecran, sans avoir a le quitter" - this used to point at Proposal review for these two instead).
	 */
	private static void renderMatchPlan(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.MatchPlanInfo pPlan, final Model pModel,
		final boolean pFoldVolumeNotes)
	{
		// wizard visual pass, step 6 round (2026-09-24, board MatchingPlan.dc.html): header = name, origin chip,
		// status chip (only once decided), confidence, and the Simulate action on the right; then the decision
		// tree as the card's main element; then the simulation / which-value-wins strip; then the three decisions.
		final ReviewLine line = pModel.matchLines.get(pPlan.id);
		final boolean awaitingDecision = ReviewLine.PROPOSED.equals(pPlan.status) || ReviewLine.SUPERSEDED.equals(pPlan.status);
		// lot M9 (2026-10-18, docs/MATCHING.md sec 63): the Factory's fallback plan (origin RULE + requirements.fallback) has its own badge and a note under the header
		final MatchingPlanCoverage.FallbackInfo fallback = MatchingPlanCoverage.fallbackOf(pPlan.requirements);
		pOut.append("<div class=\"mdmf-match-plan\"><header><span class=\"mdmf-match-plan-name\">").append(WizardPage.esc(pPlan.entityName)).append("</span>")
			.append("<span class=\"mdmf-chip mdmf-chip-accent\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, fallback != null ? "wizard.step6.origin.RULE_FALLBACK" : "wizard.step6.origin." + pPlan.origin))).append("</span>");
		if (!awaitingDecision)
		{
			pOut.append("<span class=\"mdmf-chip ").append(WizardPage.statusChipClass(pPlan.status)).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.status." + pPlan.status))).append("</span>");
		}
		if (pPlan.confidence != null)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.confidence", pPlan.confidence)))
				.append("</span>");
		}
		if (line != null && line.matchRequirementsJson != null)
		{
			final Boolean canSimulate = pModel.matchCanSimulate.get(pPlan.id);
			pOut.append("<span class=\"mdmf-match-plan-tools\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"SIMULATE\" data-mdmf-nature=\"")
				.append(ReviewLine.MATCH).append("\" data-mdmf-id=\"").append(WizardPage.esc(pPlan.id)).append("\"")
				.append(Boolean.TRUE.equals(canSimulate) ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.PLAY_ICON)
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.simulate"))).append("</button></span>");
		}
		pOut.append("</header>");
		if (fallback != null)
		{
			pOut.append("<p class=\"mdmf-hint mdmf-match-plan-fallback\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.fallback.note", Integer.valueOf(fallback.attempts), fallback.lastRefusal))).append("</p>");
		}
		if (line != null && line.matchRequirementsJson != null)
		{
			WizardPage.renderMatchPlanBody(pOut, pLocale, pPlan, line, pModel, pFoldVolumeNotes);
		}
		else if (!pPlan.requirements.isEmpty())
		{
			pOut.append("<p>").append(WizardPage.esc(pPlan.requirements)).append("</p>");
		}
		if (!pPlan.rationale.isEmpty())
		{
			pOut.append("<details class=\"mdmf-rationale\"><summary>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.rationale"))).append("</summary><p>")
				.append(WizardPage.esc(pPlan.rationale)).append("</p></details>");
		}
		if (!pPlan.reviewComment.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.comment", pPlan.reviewComment))).append("</p>");
		}
		if (!pPlan.message.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(pPlan.message)).append("</p>");
		}
		// lot M8a (2026-10-04, docs/MATCHING.md sect;51): candidate-search notice - the add-on's candidate filters are off on this server, or (tree folded away
		// once decided) the plan has a path with no exact key; nothing for a plan nobody will run
		if (!ReviewLine.REJECTED.equals(pPlan.status) && !ReviewLine.SUPERSEDED.equals(pPlan.status))
		{
			pOut.append(MatchPlanView.renderCandidateSearchNotice(pPlan.requirements, !awaitingDecision, CandidateFilterCheck.jvmOptionEnabled(), pLocale, pFoldVolumeNotes));
		}
		// long-passes-visible lot (2026-09-23): MATCHING_RUN is per-entity, not part of projectSteps' own track
		// (WizardSnapshot#projectSteps own Javadoc) - shown here instead, same wording/rule as renderTrack's.
		if (pPlan.runStep != null && "RUNNING".equals(pPlan.runStep.status))
		{
			final String runningSince = WizardPage.runningSinceText(pLocale, pPlan.runStep);
			if (runningSince != null)
			{
				pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(runningSince)).append("</p>");
			}
		}
		pOut.append("<div class=\"mdmf-match-plan-decide\">");
		WizardPage.renderMatchPlanActions(pOut, pLocale, pPlan.id, pPlan.status);
		pOut.append("</div></div>");
	}

	private static final String PLAY_ICON = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><polygon points=\"6 4 20 12 6 20 6 4\"></polygon></svg>";
	private static final String CHECK_ICON = "<svg width=\"12\" height=\"12\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"3\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><polyline points=\"20 6 9 17 4 12\"></polyline></svg>";
	private static final String RUN_ICON = "<svg width=\"14\" height=\"14\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2.2\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><circle cx=\"6\" cy=\"6\" r=\"3\"></circle><circle cx=\"6\" cy=\"18\" r=\"3\"></circle>"
		+ "<circle cx=\"18\" cy=\"12\" r=\"3\"></circle><path d=\"M6 9v6M8.5 7.5 15.5 11M8.5 16.5l7-4\"></path></svg>";

	/**
	 * The plan card's decision row, board wording and hierarchy (Accept the plan = the purple primary, Propose
	 * another, No matching for this object = quiet) - the SAME actions {@link DecisionActions#forStatus} allows
	 * for a {@code MATCH} line and the SAME data attributes {@link #renderDecideActions} writes (DECIDE_ACCEPT,
	 * the two {@code data-mdmf-toggle} inline panels, Apply/Re-apply/Remove), so {@code wizard.js} and {@code
	 * ProjectWizardUserService} see exactly what they saw before - only the labels, order and emphasis differ.
	 */
	private static void renderMatchPlanActions(final StringBuilder pOut, final Locale pLocale, final String pId, final String pStatus)
	{
		final DecisionActions.Actions actions = DecisionActions.forStatus(pStatus, ReviewLine.MATCH, false);
		final String id = WizardPage.esc(pId);
		if (actions.accept)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"").append(ReviewLine.MATCH)
				.append("\" data-mdmf-id=\"").append(id).append("\">").append(WizardPage.CHECK_ICON).append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.accept")))
				.append("</button>");
		}
		if (actions.repropose)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-toggle=\"mdmf-match-repropose-").append(id).append("\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.repropose"))).append("</button>");
		}
		if (actions.reject && actions.confirmReject)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-quiet\" data-mdmf-toggle=\"mdmf-match-noMatch-").append(id).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.noMatching"))).append("</button>");
		}
		else if (actions.reject)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-quiet\" data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"").append(ReviewLine.MATCH)
				.append("\" data-mdmf-id=\"").append(id).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.noMatching"))).append("</button>");
		}
		if (actions.apply)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"APPLY\" data-mdmf-nature=\"").append(ReviewLine.MATCH)
				.append("\" data-mdmf-id=\"").append(id).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.apply"))).append("</button>");
		}
		if (actions.reapply)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"REAPPLY\" data-mdmf-nature=\"").append(ReviewLine.MATCH)
				.append("\" data-mdmf-id=\"").append(id).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.reapply"))).append("</button>");
		}
		if (actions.measureAgain)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"").append(ReviewLine.MATCH).append("\" data-mdmf-id=\"")
				.append(id).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.measureAgain"))).append("</button>");
		}
		if (actions.remove)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-quiet\" data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"").append(ReviewLine.MATCH)
				.append("\" data-mdmf-id=\"").append(id).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.remove"))).append("</button>");
		}
		WizardPage.renderMatchInlinePanels(pOut, pLocale, ReviewLine.MATCH, pId, actions);
	}

	/**
	 * Wizard-match-tree lot (2026-09-23), reshaped by the step 6 visual round (2026-09-24): an eyebrow row
	 * ("Decision tree" + the leaf legend + the plan's one-line summary), the decision tree itself ({@link
	 * MatchPlanView#renderTree} - the Proposal review screen's own tree, never a second implementation), the
	 * compact simulation / which-value-wins strip ({@link MatchPlanView#renderSummaryStrip}), then evidence,
	 * simulation and survivorship ({@link MatchPlanView#renderDetails}) behind a "Show details" fold, reopened
	 * for the plan the current postback acted on ({@link Model#expandedMatchRowIds}). A plan already decided
	 * folds the tree away with the rest.
	 */
	private static void renderMatchPlanBody(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.MatchPlanInfo pPlan, final ReviewLine pLine,
		final Model pModel, final boolean pFoldVolumeNotes)
	{
		// step 6 plan card (2026-10-18): the fields of the plan read as the entity's attribute labels, not as English words made of their identifiers
		com.on.ebx.factory.review.FieldWords.withLabels(WizardPage.attributeLabels(pModel, pPlan.entityName),
			() -> WizardPage.renderMatchPlanBodyLabelled(pOut, pLocale, pPlan, pLine, pModel, pFoldVolumeNotes));
	}

	/** Master field name to the label of the staging attribute of that name, for the entity; empty when the entity is not in the snapshot or labels nothing. */
	static java.util.Map<String, String> attributeLabels(final Model pModel, final String pEntityName)
	{
		final java.util.Map<String, String> labels = new java.util.HashMap<String, String>();
		if (pModel == null || pModel.snapshot == null || pEntityName == null)
		{
			return labels;
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pModel.snapshot.stagingEntities)
		{
			if (!pEntityName.equals(entity.name))
			{
				continue;
			}
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				if (attribute.name != null && !attribute.name.isEmpty() && attribute.label != null && !attribute.label.trim().isEmpty())
				{
					labels.put(attribute.name, attribute.label.trim());
				}
			}
		}
		return labels;
	}

	private static void renderMatchPlanBodyLabelled(final StringBuilder pOut, final Locale pLocale, final WizardSnapshot.MatchPlanInfo pPlan, final ReviewLine pLine,
		final Model pModel, final boolean pFoldVolumeNotes)
	{
		final Boolean canSimulate = pModel.matchCanSimulate.get(pPlan.id);
		final MatchPlanView.Input input = new MatchPlanView.Input(pLine.element, pLine.matchRequirementsJson, pLine.matchEvidenceJson, pLine.matchAutoMerge,
			Boolean.TRUE.equals(canSimulate), null, pModel.matchSimulationResults.get(pPlan.id), pModel.matchMaxSimulationPairs, pPlan.id, pLocale,
			pLine.survivorshipInput);
		final boolean awaitingDecision = ReviewLine.PROPOSED.equals(pPlan.status) || ReviewLine.SUPERSEDED.equals(pPlan.status);
		final boolean open = pModel.expandedMatchRowIds.contains(pPlan.id);
		pOut.append("<div class=\"mdmf-match-plan-treehead\"><span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.treeTitle")))
			.append("</span>");
		if (awaitingDecision)
		{
			// the legend names only the leaves this tree can draw: without automatic merge a MATCH leaf is drawn as
			// "a person decides" (MatchPlanTreeDiagram's own rule), so "Match" is left out of the legend then.
			pOut.append("<span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.legend.node"))).append("</span>")
				.append(pLine.matchAutoMerge ? "<span class=\"mdmf-chip mdmf-chip-real\">" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.legend.match")) + "</span>" : "")
				.append("<span class=\"mdmf-chip mdmf-chip-warn\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.legend.person"))).append("</span>")
				.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step6.plan.legend.different"))).append("</span>");
		}
		pOut.append("<span class=\"mdmf-match-plan-summary\">").append(WizardPage.esc(pLine.proposition)).append("</span></div>");
		if (awaitingDecision)
		{
			pOut.append("<div class=\"mdmf-match-plan-tree\">").append(MatchPlanView.renderTree(input, pFoldVolumeNotes)).append("</div>");
			pOut.append(MatchPlanView.renderSummaryStrip(input));
		}
		pOut.append("<div class=\"mdmf-match-plan-details\"><details").append(open ? " open=\"open\"" : "").append("><summary>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, awaitingDecision ? "wizard.step6.plan.details" : "wizard.step6.plan.detailsWithTree")))
			.append("</summary>");
		pOut.append(awaitingDecision ? MatchPlanView.renderDetails(input) : MatchPlanView.render(input));
		pOut.append("</details></div>");
	}

	// --------------------------------------------------------------------------------------------------- step 7: summary

	// --------------------------------------------------------------------------------------- step 7: distribution

	/**
	 * Distribution-wizard lot (2026-09-24, boards {@code Main.dc.html}/{@code Modes.dc.html}/
	 * {@code DataProduct.dc.html}) - "where do golden records go". Three sub-views on the one panel, driven by
	 * {@link Model#distribution}, no separate screen (unlike the boards' own three-screen flow, to avoid a
	 * whole second navigation vocabulary for one optional step): the target list (7a, default), a target's
	 * setup card in place of the list once {@link DistributionModel#openTargetId} is set (7b), and the data
	 * product panel below the target list, opened by its own toggle (7c). {@link Model#distribution} null (the
	 * step was never built for this render - see that field's own Javadoc) shows the {@code
	 * wizard.step7dist.empty} notice instead of a stale table.
	 */
	private static void renderStep7(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"7\"").append(WizardPage.hiddenAttr(pModel, 7)).append(">");
		WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step7dist.title"), WizardMessages.get(pLocale, "wizard.step7dist.lead"));
		final DistributionModel d = pModel.distribution;
		if (d == null && pModel.distributionDeferred)
		{
			// perf/wizard-distribution-lazy: see Model#distributionDeferred. The button is the fallback when the
			// automatic load did not fire (the rail's own step 7 button already posts STEP_VIEW by itself).
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\" data-mdmf-deferred-step=\"7\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.deferred"))).append("</div>")
				.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STEP_VIEW\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.deferred.load"))).append("</button></div></section>");
			return;
		}
		if (d == null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.empty"))).append("</div></section>");
			return;
		}
		if (d.error != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(d.error)).append("</div>");
		}
		if (d.notice != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(WizardPage.esc(d.notice)).append("</div>");
		}
		// UX lot U5 (2026-10-13, RAPPORT 2.12): "Distribution is optional" used to sit under a 24-row list; it is the first thing to read.
		pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.skip.hint"))).append("</div>");
		WizardPage.render2ColStart(pOut);
		if (!d.masterMainEntity.isEmpty() && (d.masterMainGolden > 0 || d.masterMainRows > 0))
		{
			// step 7 screen counts (2026-10-18): the golden records of the main object, and the rows of the tables in all - never the one added to the other
			pOut.append("<p class=\"mdmf-hint\" data-mdmf-master-holds=\"true\">")
				.append(WizardPage.esc(DistributionTexts.masterHolds(pLocale, d.masterMainEntity, d.masterMainGolden, d.masterMainRows, d.masterTotalRows, d.masterTableCount)))
				.append("</p>");
		}
		if (!d.openTargetId.isEmpty() && d.openTarget != null)
		{
			WizardPage.renderDistributionTargetSetup(pOut, pModel, pLocale, d);
		}
		else
		{
			WizardPage.renderDistributionTargetList(pOut, pModel, pLocale, d);
			WizardPage.renderDistributionProduct(pOut, pModel, pLocale, d);
		}
		WizardPage.render2ColSplit(pOut);
		WizardPage.renderSideCardOpen(pOut, pLocale, "wizard.step7dist.side.what.title");
		pOut.append("<ul class=\"mdmf-list\">");
		pOut.append("<li>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.side.what.golden"))).append("</li>");
		pOut.append("<li>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.side.what.retire"))).append("</li>");
		pOut.append("</ul>");
		WizardPage.renderSideCardClose(pOut);
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.step7dist.glossary.target", "wizard.step2.glossary.connection", "wizard.step7dist.glossary.armed",
			"wizard.step7dist.glossary.product", "wizard.step7dist.glossary.retire", "wizard.step5.glossary.crossReference");
		WizardPage.render2ColEnd(pOut);

		// step-gating lot's own rule (2026-09-24 - SPEC-distribution.md: "Distribution is optional: it never
		// blocks the Summary"): a plain "Next: summary" link, always enabled, whatever targets/product exist
		// (the sentence saying so is the notice at the top since UX lot U5).
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"8\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.skip"))).append("</button>");
		pOut.append("</div>");
		pOut.append("</section>");
	}

	/**
	 * Live bug fix (2026-09-25, DistribTest, task brief item 4): the board (7a, {@code DistributionMain.dc.html})
	 * lists the candidate CONNECTIONS themselves, not just already-saved targets - a connection with a write
	 * operation offers "Set up" straight from its own row, one without is greyed "No write operation", and an
	 * existing target for a connection shows on THAT connection's own row (state/mode chips), not in a second,
	 * disconnected list. The old rendering only ever showed {@code pD.targets} and hid every candidate behind
	 * the generic "+ Add a connection" button (which stays, for the rare case discovery missed a connection).
	 */
	/**
	 * Live bug fix (2026-09-25, DistribTest, task brief item 2): 114 connections listed alphabetically buried
	 * the few write-capable ones among a long run of "No write operation" rows. Write-capable connections now
	 * come first (an existing target's own connection first among those, since it is the one someone is most
	 * likely here to check on), and every connection without a write operation is folded under one closed-by-
	 * default {@code <details>} disclosure ("N connections without a write operation") instead of a long list
	 * of dead rows - {@link DistributionConnections#listCandidateConnections} already keeps inactive connectors
	 * out entirely (never listed at all, not even folded).
	 */
	private static void renderDistributionTargetList(final StringBuilder pOut, final Model pModel, final Locale pLocale, final DistributionModel pD)
	{
		// UX lot U5 (2026-10-13, RAPPORT 2.12 / img 51b): the flat list of every write-capable connection becomes three sections
		// (DistributionConnectionGroups): likely targets, the sources of this project (sendable, flagged), the other connections (test, demo, H2,
		// sample: folded), then the folded count of the connections without a write operation.
		final java.util.Set<String> targetConnectors = new java.util.HashSet<String>();
		for (final DistributionTargetRow target : pD.targets)
		{
			targetConnectors.add(target.connector);
		}
		final java.util.Set<String> sourceConnectors = new java.util.HashSet<String>();
		for (final WizardSnapshot.SourceInfo source : pModel.snapshot.sources)
		{
			if (!source.connector.isEmpty())
			{
				sourceConnectors.add(source.connector);
			}
		}
		final DistributionConnectionGroups groups = DistributionConnectionGroups.of(pD.connections, targetConnectors, sourceConnectors, pModel.snapshot.project == null ? "" : pModel.snapshot.project.code,
			pD.otherProjectCodes, pD.otherProjectConnectors);
		pOut.append("<div class=\"mdmf-panel\"><div class=\"mdmf-actions\" style=\"justify-content:space-between\"><span class=\"mdmf-eyebrow\">")
			.append(WizardPage.esc(WizardPage.plural(pLocale, groups.writeCapableCount(), "wizard.step7dist.targets.title.one", "wizard.step7dist.targets.title.other")))
			.append("</span>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_TARGET_OPEN\" data-mdmf-id=\"NEW\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.targets.add"))).append("</button></div>");
		// audit F-2-101: the connections are read in the background and kept per person - say which state this is, and offer the explicit refresh
		pOut.append("<p class=\"mdmf-hint\" data-mdmf-connections-state=\"").append(pD.connectionsReading ? "reading" : pD.connectionsRead ? "read" : "empty").append("\">");
		if (pD.connectionsReading)
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.reading")));
		}
		else if (!pD.connectionsRead)
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.notRead")));
		}
		else
		{
			pOut.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.readAgo", Long.valueOf(Math.max(1L, pD.connectionsAgeSeconds / 60L)))));
		}
		if (!pD.connectionsError.isEmpty())
		{
			pOut.append(" ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.readError", pD.connectionsError)));
		}
		pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_CONNECTIONS_REFRESH\"")
			.append(pD.connectionsReading ? " disabled" : "").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.refresh"))).append("</button></p>");
		if (pD.connectionsRead && groups.writeCapableCount() == 0)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.empty"))).append("</p>");
		}
		if (!groups.likely.isEmpty())
		{
			pOut.append("<div class=\"mdmf-eyebrow\" style=\"margin:6px 0\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.group.likely"))).append("</div>");
			for (final DistributionConnectionRow connection : groups.likely)
			{
				WizardPage.renderDistributionConnectionRow(pOut, pLocale, connection, pD.targets, null);
			}
		}
		if (!groups.sources.isEmpty())
		{
			pOut.append("<div class=\"mdmf-eyebrow\" style=\"margin:10px 0 2px\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.group.sources")))
				.append("</div><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.group.sources.hint"))).append("</p>");
			for (final DistributionConnectionRow connection : groups.sources)
			{
				WizardPage.renderDistributionConnectionRow(pOut, pLocale, connection, pD.targets, "wizard.step7dist.connections.sourceChip");
			}
		}
		if (!groups.others.isEmpty())
		{
			pOut.append("<details class=\"mdmf-disclosure\"><summary>")
				.append(WizardPage.esc(WizardPage.plural(pLocale, groups.others.size(), "wizard.step7dist.group.other.one", "wizard.step7dist.group.other.other")))
				.append("</summary>");
			for (final DistributionConnectionRow connection : groups.others)
			{
				WizardPage.renderDistributionConnectionRow(pOut, pLocale, connection, pD.targets, null);
			}
			pOut.append("</details>");
		}
		if (!groups.noWrite.isEmpty())
		{
			pOut.append("<details class=\"mdmf-disclosure\"><summary>")
				.append(WizardPage.esc(WizardPage.plural(pLocale, groups.noWrite.size(), "wizard.step7dist.connections.noWriteGroup.one",
					"wizard.step7dist.connections.noWriteGroup.other")))
				.append("</summary>");
			for (final DistributionConnectionRow connection : groups.noWrite)
			{
				WizardPage.renderDistributionConnectionRow(pOut, pLocale, connection, pD.targets, null);
			}
			pOut.append("</details>");
		}
		// a target whose connector no longer appears among the candidate connections (removed/renamed on the
		// Connectors side since it was configured) still needs a way back to its own setup panel - shown below
		// the connection list, same self-contained card the old rendering used for every target.
		for (final DistributionTargetRow target : pD.targets)
		{
			if (!WizardPage.connectionOf(pD.connections, target.connector))
			{
				WizardPage.renderDistributionTargetCard(pOut, pLocale, target);
			}
		}
		pOut.append("</div>");
	}

	private static boolean connectionOf(final List<DistributionConnectionRow> pConnections, final String pConnector)
	{
		for (final DistributionConnectionRow connection : pConnections)
		{
			if (connection.connector.equals(pConnector))
			{
				return true;
			}
		}
		return false;
	}

	/** One candidate connection's own row: greyed "no write operation", a bare "Set up" when nothing is configured on it yet, or its existing target(s)' state/mode chips otherwise - board {@code DistributionMain.dc.html}. */
	private static void renderDistributionConnectionRow(final StringBuilder pOut, final Locale pLocale, final DistributionConnectionRow pConnection,
		final List<DistributionTargetRow> pTargets, final String pChipKey)
	{
		final List<DistributionTargetRow> own = new ArrayList<DistributionTargetRow>();
		for (final DistributionTargetRow target : pTargets)
		{
			if (target.connector.equals(pConnection.connector))
			{
				own.add(target);
			}
		}
		pOut.append("<div class=\"mdmf-choice-card\" style=\"margin-bottom:10px\">");
		pOut.append("<div class=\"mdmf-actions\" style=\"justify-content:space-between\"><b>").append(WizardPage.esc(pConnection.label)).append("</b>");
		if (pChipKey != null)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardMessages.get(pLocale, pChipKey))).append("</span>");
		}
		if (!pConnection.hasWriteOperation)
		{
			pOut.append("<span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.connections.noWrite")))
				.append("</span></div></div>");
			return;
		}
		if (own.isEmpty())
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_TARGET_OPEN\" data-mdmf-id=\"NEW\" data-mdmf-connector=\"")
				.append(WizardPage.esc(pConnection.connector)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.targets.setUp")))
				.append("</button></div></div>");
			return;
		}
		pOut.append("</div>");
		for (final DistributionTargetRow target : own)
		{
			WizardPage.renderDistributionTargetInline(pOut, pLocale, target);
		}
		pOut.append("</div>");
	}

	private static final String[] TARGET_STATE_CLASSES = { "DRAFT", "WAITING", "ARMED", "PAUSED", "DISABLED" };

	/** One existing target's state/mode/message/"Set up" - nested inside its own connection's card ({@link #renderDistributionConnectionRow}). */
	private static void renderDistributionTargetInline(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget)
	{
		final String stateClass = "ARMED".equals(pTarget.state) ? "real" : ("DISABLED".equals(pTarget.state) ? "muted" : "warn");
		pOut.append("<div class=\"mdmf-actions\" style=\"justify-content:space-between\">");
		pOut.append("<span class=\"mdmf-chip mdmf-chip-").append(stateClass).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.state." + pTarget.state))).append("</span>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_TARGET_OPEN\" data-mdmf-id=\"").append(WizardPage.esc(pTarget.id))
			.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.targets.setUp"))).append("</button></div>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.mode." + pTarget.mode))).append(" &middot; ")
			.append(WizardPage.esc(pTarget.operation)).append("</p>");
		// step 7 screen counts (2026-10-18): the last send, said in the person's language with its date and its rows - the chip keeps the target's state (a disabled target shows its last send)
		final String lastSend = DistributionTexts.lastSend(pLocale, pTarget, java.time.ZoneId.systemDefault());
		if (!lastSend.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\" data-mdmf-last-send=\"true\">").append(WizardPage.esc(lastSend)).append("</p>");
		}
		if (!pTarget.lastMessage.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(RunPanelText.display(pTarget.lastMessage, pLocale))).append("</p>");
		}
	}

	/** A standalone target whose connector is no longer among the candidate connections (see {@link #renderDistributionTargetList}'s own comment). */
	private static void renderDistributionTargetCard(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget)
	{
		pOut.append("<div class=\"mdmf-choice-card\" style=\"margin-bottom:10px\"><div class=\"mdmf-actions\" style=\"justify-content:space-between\">");
		pOut.append("<b>").append(WizardPage.esc(pTarget.connectorLabel.isEmpty() ? pTarget.connector : pTarget.connectorLabel)).append("</b></div>");
		WizardPage.renderDistributionTargetInline(pOut, pLocale, pTarget);
		pOut.append("</div>");
	}

	/** Board {@code Modes.dc.html} - one target's setup panel, shown IN PLACE of the target list once opened. */
	private static void renderDistributionTargetSetup(final StringBuilder pOut, final Model pModel, final Locale pLocale, final DistributionModel pD)
	{
		final DistributionTargetRow t = pD.openTarget;
		final boolean isNew = "NEW".equals(pD.openTargetId);
		pOut.append("<div class=\"mdmf-panel\">");
		pOut.append("<div class=\"mdmf-actions\" style=\"justify-content:space-between\"><b>")
			.append(WizardPage.esc(isNew ? WizardMessages.get(pLocale, "wizard.step7dist.setup.newTitle")
				: WizardMessages.get(pLocale, "wizard.step7dist.setup.title", t.connectorLabel.isEmpty() ? t.connector : t.connectorLabel)))
			.append("</b><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_TARGET_CLOSE\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.back"))).append("</button></div>");

		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.connector")))
			.append("<select name=\"mdmfDistConnector\">");
		boolean matched = false;
		for (final DistributionConnectionRow connection : pD.connections)
		{
			if (!connection.hasWriteOperation)
			{
				continue; // board: connections without a write operation are listed greyed, never selectable here
			}
			final boolean selected = connection.connector.equals(t.connector);
			matched = matched || selected;
			pOut.append("<option value=\"").append(WizardPage.esc(connection.connector)).append("\"").append(selected ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(connection.label)).append("</option>");
		}
		if (!matched && !t.connector.isEmpty())
		{
			pOut.append("<option value=\"").append(WizardPage.esc(t.connector)).append("\" selected=\"selected\">").append(WizardPage.esc(t.connector)).append("</option>");
		}
		pOut.append("</select></label>");
		WizardPage.renderDistributionOperationField(pOut, pLocale, pD, t);
		// task brief item 4: a connector-level destination (Kafka's own topic/key.field) - read-only, no
		// picker (this module never changes Connectors); a warning when key.field is not a real master field.
		WizardPage.renderDistributionDestinationHint(pOut, pLocale, pD, t);
		// round 6 (task brief item C): one editable "Table -> topic" row per master table, only for a connector
		// that actually reads a per-table destination (Kafka) - see ebx.DistributionConfigurator's own Javadoc.
		WizardPage.renderDistributionTableDestinations(pOut, pModel, pLocale, pD, t);

		// live bug fix (2026-09-25, DistribTest, task brief item 3): raw radio buttons far from their labels ->
		// the same option-card pattern renderModeCard already uses for the project mode picker (.mdmf-choice-card/
		// .mdmf-mode-card, board DistributionModes.dc.html: a title + a one-line explanation per card).
		pOut.append("<div class=\"mdmf-field\"><label id=\"mdmfDistModeLabel\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.mode")))
			.append("</label>");
		pOut.append("<div class=\"mdmf-mode-cards\" role=\"radiogroup\" aria-labelledby=\"mdmfDistModeLabel\">");
		WizardPage.renderDistributionModeOption(pOut, pLocale, t, "REALTIME");
		WizardPage.renderDistributionModeOption(pOut, pLocale, t, "ON_DEMAND");
		WizardPage.renderDistributionModeOption(pOut, pLocale, t, "SCHEDULED");
		pOut.append("</div></div>");

		if ("SCHEDULED".equals(t.mode))
		{
			pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.cron")))
				.append("<input type=\"text\" name=\"mdmfDistCron\" value=\"").append(WizardPage.esc(t.cron)).append("\" placeholder=\"0 0 2 * * ?\"/></label>");
		}
		// live bug fix (2026-09-25, task brief item 3): checkboxes ran together on one line - a plain <label>
		// is inline by default, so several in a row flow side by side with no wrap. Each now sits in its own
		// .mdmf-field block (the same block-level wrapper every other field on this panel already uses), one per
		// line, matching the board.
		if (!"REALTIME".equals(t.mode))
		{
			pOut.append("<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfDistScopeAll\" value=\"true\"")
				.append("ALL".equals(t.scope) ? " checked=\"checked\"" : "").append("/> ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.scopeAll"))).append("</label></div>");
			pOut.append("<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfDistApproval\" value=\"true\"")
				.append(t.approval ? " checked=\"checked\"" : "").append("/> ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.approval")))
				.append("</label></div>");
		}
		// "retire notices from the merge log" (SPEC-retire.md, UI lot): only meaningful for a REALTIME target -
		// the golden's own CREATE/MODIFY still comes from the trigger, the retire notices for its merged
		// members come from the merge-log watch (ebx.MergeLogWatcher/distribution.MergeLogWatch), both draining
		// through this same target. Gated on mode the same way scopeAll/approval are gated above.
		if ("REALTIME".equals(t.mode))
		{
			WizardPage.renderDistributionRetireSettings(pOut, pLocale, t);
		}
		pOut.append("<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfDistIncludeCrosswalk\" value=\"true\"")
			.append(t.includeCrosswalk ? " checked=\"checked\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.crosswalk"))).append("</label></div>");
		pOut.append("<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfDistIncludePending\" value=\"true\"")
			.append(t.includePending ? " checked=\"checked\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.pending"))).append("</label></div>");
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.watchedFields")))
			.append("<input type=\"text\" name=\"mdmfDistWatchedFields\" value=\"").append(WizardPage.esc(t.watchedFields)).append("\"/></label>");
		pOut.append("<input type=\"hidden\" name=\"mdmfDistTargetId\" value=\"").append(WizardPage.esc(pD.openTargetId)).append("\"/>");

		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"DISTRIBUTION_TARGET_SAVE\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.save"))).append("</button>");
		pOut.append("</div>");

		if (!isNew)
		{
			WizardPage.renderDistributionArming(pOut, pModel, pLocale, pD, t);
			// "retire notices from the merge log" (SPEC-retire.md, UI lot): board RetireHistory.dc.html - the
			// target's own recent sends, populated only for the currently open target (ProjectWizardUserService
			// #buildDistribution), so this is a no-op (empty list, nothing rendered) on a target whose events
			// could not be read (best-effort, same convention as this whole panel's other reads).
			WizardPage.renderDistributionHistory(pOut, pLocale, t);
		}
		pOut.append("</div>");
	}

	/**
	 * "Retire notices from the merge log" (SPEC-retire.md, UI lot) - board {@code Retire.dc.html}'s dashed
	 * "When two records are merged" block: a static explanation (never live data - the example is
	 * illustrative, the same four lines whatever the project), the {@code retireMode} three-way radio and the
	 * {@code retireUndoneMerges} checkbox. Posted alongside every other 7b field on {@code
	 * DISTRIBUTION_TARGET_SAVE} - see {@code ProjectWizardUserService.Posted#distRetireMode}. Rendered only
	 * for a REALTIME target (see this method's caller): the merge-log watch only ever runs for a REALTIME
	 * target's outbox drain ({@code ebx.DistributionDispatcher#drainTarget}).
	 */
	private static void renderDistributionRetireSettings(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget)
	{
		pOut.append("<div class=\"mdmf-panel\">");
		pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.title"))).append("</span>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.explain"))).append("</p>");
		pOut.append("<p class=\"mdmf-hint\"><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.example.title"))).append("</b></p>");
		pOut.append("<ul class=\"mdmf-hint\">");
		pOut.append("<li><span class=\"mdmf-chip mdmf-chip-real\">CREATE</span> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.example.create"))).append("</li>");
		pOut.append("<li><span class=\"mdmf-chip mdmf-chip-bad\">RETIRE</span> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.example.retire1"))).append("</li>");
		pOut.append("<li><span class=\"mdmf-chip mdmf-chip-bad\">RETIRE</span> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.example.retire2"))).append("</li>");
		pOut.append("<li><span class=\"mdmf-chip mdmf-chip-info\">MODIFY</span> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.example.modify"))).append("</li>");
		pOut.append("</ul>");
		pOut.append("<div class=\"mdmf-field\"><label id=\"mdmfDistRetireModeLabel\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.modeTitle"))).append("</label>");
		pOut.append("<div class=\"mdmf-mode-cards\" role=\"radiogroup\" aria-labelledby=\"mdmfDistRetireModeLabel\">");
		// literal strings, not core.FactoryPaths.DistributionTarget.RETIRE_MODE_* constants - same convention
		// renderDistributionModeOption already uses for REALTIME/ON_DEMAND/SCHEDULED, so this class stays free
		// of any EBX-adjacent type (this class's own "Pure: no EBX class" Javadoc).
		WizardPage.renderDistributionRetireModeOption(pOut, pLocale, pTarget, "NOTICE");
		WizardPage.renderDistributionRetireModeOption(pOut, pLocale, pTarget, "TOMBSTONE");
		WizardPage.renderDistributionRetireModeOption(pOut, pLocale, pTarget, "NONE");
		pOut.append("</div></div>");
		pOut.append("<div class=\"mdmf-field\"><label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfDistRetireUndoneMerges\" value=\"true\"")
			.append(pTarget.retireUndoneMerges ? " checked=\"checked\"" : "").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.undoneMerges"))).append("</label></div>");
		// lot S2 (2026-10-18): what the merge-state sweep has done for this target - only for a saved target (a new one has no history)
		if (!pTarget.id.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\" data-mdmf-retire-facts=\"true\">")
				.append(WizardPage.esc(DistributionTexts.retireFacts(pLocale, pTarget.retiresSent, pTarget.retiresCapped, pTarget.lastSweepAt, java.time.ZoneId.systemDefault())))
				.append("</p>");
		}
		pOut.append("</div>");
	}

	/** Lot S2: "12", or "10000+" when the count stopped at its cap. */
	static String retireCountText(final int pCount, final boolean pCapped)
	{
		return pCapped ? pCount + "+" : String.valueOf(pCount);
	}

	/** Same {@code .mdmf-choice-card mdmf-mode-card} pattern {@link #renderDistributionModeOption} uses, for the {@code retireMode} radio ({@code mdmfDistRetireMode}, not {@code mdmfDistMode}). */
	private static void renderDistributionRetireModeOption(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget, final String pMode)
	{
		// DistributionTargetRow#retireMode already defaults to "NOTICE" (a brand-new, unsaved target never
		// carries a blank value here) - no separate fallback needed, same simplicity as renderDistributionModeOption.
		final boolean selected = pMode.equals(pTarget.retireMode);
		pOut.append("<label class=\"mdmf-choice-card mdmf-mode-card").append(selected ? " selected" : "").append("\">");
		pOut.append("<input type=\"radio\" name=\"mdmfDistRetireMode\" value=\"").append(pMode).append("\"").append(selected ? " checked=\"checked\"" : "").append("/>");
		pOut.append("<span class=\"mdmf-choice-card-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.mode." + pMode))).append("</span>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.retire.mode." + pMode + ".explain"))).append("</p>");
		pOut.append("</label>");
	}

	/**
	 * "Retire notices from the merge log" (SPEC-retire.md, UI lot) - board {@code RetireHistory.dc.html}: the
	 * target's own recent sends, one row per {@link EventRow}. Wording per that board and the task brief:
	 * a RETIRE with a survivor reads "merged into &lt;survivor&gt; - merge &lt;id&gt;"; a RETIRE with no
	 * survivor whose reason is UNMERGE (the merge-log watch's own convention - see {@code
	 * distribution.MergeLogWatch}) reads "golden deleted by an undone merge - no replacing id"; a CREATE
	 * whose reason is UNMERGE (a former member reinstated once golden again) reads "live again after an
	 * undone merge". Every other row falls back to its own stored message, exactly as before this lot.
	 */
	private static void renderDistributionHistory(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget)
	{
		WizardPage.renderDistributionHistory(pOut, pLocale, pTarget, java.time.ZoneId.systemDefault());
	}

	/**
	 * Lot S7 (2026-10-18): the "Time" cell says the send like the target card above it ("18/10 21:40", server time zone, {@link DistributionTexts#when}); the stored UTC ISO text
	 * (2026-10-18T19:40:15.889Z) stays whole in the cell's {@code title}, and is the cell's text when it is not a readable instant.
	 */
	static void renderDistributionHistory(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget, final java.time.ZoneId pZone)
	{
		if (pTarget.events.isEmpty())
		{
			return;
		}
		pOut.append("<div class=\"mdmf-panel\">");
		pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.title"))).append("</span>");
		pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.col.time"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.col.table"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.col.record"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.col.change"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.col.status"))).append("</th>");
		pOut.append("<th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.history.col.detail"))).append("</th>");
		pOut.append("</tr></thead><tbody>");
		for (final EventRow event : pTarget.events)
		{
			final String when = DistributionTexts.when(event.createdAt, pLocale, pZone);
			pOut.append("<tr><td title=\"").append(WizardPage.esc(event.createdAt)).append("\">").append(WizardPage.esc(when.isEmpty() ? event.createdAt : when)).append("</td>");
			pOut.append("<td>").append(WizardPage.esc(event.tablePath)).append("</td>");
			pOut.append("<td>").append(WizardPage.esc(event.recordKey)).append("</td>");
			pOut.append("<td><span class=\"mdmf-chip ").append(WizardPage.distChangeChipClass(event.change)).append("\">")
				.append(WizardPage.esc(event.change)).append("</span></td>");
			pOut.append("<td>").append(WizardPage.esc(event.status)).append("</td>");
			pOut.append("<td>").append(WizardPage.esc(WizardPage.distEventDetail(pLocale, event))).append("</td></tr>");
		}
		pOut.append("</tbody></table></div></div>");
	}

	/** {@link #renderDistributionHistory}'s own change-type chip color, matching the CREATE/MODIFY/RETIRE colors {@link #renderDistributionRetireSettings}'s example list already uses. */
	private static String distChangeChipClass(final String pChange)
	{
		if ("CREATE".equals(pChange))
		{
			return "mdmf-chip-real";
		}
		if ("RETIRE".equals(pChange) || "DELETE".equals(pChange))
		{
			return "mdmf-chip-bad";
		}
		return "mdmf-chip-info"; // MODIFY, and any future change type
	}

	/** {@link #renderDistributionHistory}'s own "Detail" column wording - see that method's Javadoc for the three special cases. */
	private static String distEventDetail(final Locale pLocale, final EventRow pEvent)
	{
		final boolean isUnmerge = "UNMERGE".equals(pEvent.reason);
		if ("RETIRE".equals(pEvent.change) && pEvent.survivorKey != null && !pEvent.survivorKey.isEmpty())
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.history.retire.merged", pEvent.survivorKey, pEvent.mergeId);
		}
		if ("RETIRE".equals(pEvent.change) && isUnmerge)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.history.retire.unmergedGolden");
		}
		if ("CREATE".equals(pEvent.change) && isUnmerge)
		{
			return WizardMessages.get(pLocale, "wizard.step7dist.history.create.liveAgain");
		}
		return pEvent.message;
	}

	/**
	 * Live bug fix (2026-09-25, DistribTest, task brief item 4): the write operation is now a {@code <select>}
	 * of the CHOSEN connection's own {@code writeOperations} (the connections route already returns them -
	 * {@code ebx.DistributionConnections}), pre-selected when there is exactly one - never a free-text input a
	 * person could mistype against an operation the connector does not actually expose. Falls back to the old
	 * free-text input only when the target's connector is not found among the candidate connections, or that
	 * connection reports no write operation at all (an edge case - a target saved before this fix, or a
	 * connector whose operations could not be read - see {@code DistributionConnections}' own "not verified
	 * live" note): a blank select would otherwise be worse than a text box the operator can still fill by hand.
	 * <b>Deviation from the board</b> (documented in {@code docs/WIZARD-DISTRIBUTION.md}'s own "Deviations"):
	 * changing the connector {@code <select>} above does not live-refresh this one without a page reload/Save,
	 * same "no live JS, fields round-trip on Save" simplification already accepted for the mode radios.
	 */
	private static void renderDistributionOperationField(final StringBuilder pOut, final Locale pLocale, final DistributionModel pD, final DistributionTargetRow pTarget)
	{
		DistributionConnectionRow connection = null;
		for (final DistributionConnectionRow candidate : pD.connections)
		{
			if (candidate.connector.equals(pTarget.connector))
			{
				connection = candidate;
				break;
			}
		}
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.operation")));
		if (connection == null || connection.writeOperations.isEmpty())
		{
			pOut.append("<input type=\"text\" name=\"mdmfDistOperation\" value=\"").append(WizardPage.esc(pTarget.operation)).append("\"/></label>");
			return;
		}
		pOut.append("<select name=\"mdmfDistOperation\">");
		boolean matched = false;
		for (final String operation : connection.writeOperations)
		{
			final boolean selected = operation.equals(pTarget.operation) || connection.writeOperations.size() == 1;
			matched = matched || operation.equals(pTarget.operation);
			pOut.append("<option value=\"").append(WizardPage.esc(operation)).append("\"").append(selected ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(operation)).append("</option>");
		}
		if (!matched && !pTarget.operation.isEmpty() && connection.writeOperations.size() > 1)
		{
			pOut.append("<option value=\"").append(WizardPage.esc(pTarget.operation)).append("\" selected=\"selected\">").append(WizardPage.esc(pTarget.operation))
				.append("</option>");
		}
		pOut.append("</select></label>");
	}

	/** Live bug fix (2026-09-25, task brief item 3): the same {@code .mdmf-choice-card mdmf-mode-card} pattern {@link #renderModeCard} already uses for the project mode picker - a title plus a one-line explanation, the radio itself visually hidden (still reachable/checked by assistive tech - {@code .mdmf-choice-card input[type=radio]}'s own CSS). */
	private static void renderDistributionModeOption(final StringBuilder pOut, final Locale pLocale, final DistributionTargetRow pTarget, final String pMode)
	{
		final boolean selected = pMode.equals(pTarget.mode);
		pOut.append("<label class=\"mdmf-choice-card mdmf-mode-card").append(selected ? " selected" : "").append("\">");
		pOut.append("<input type=\"radio\" name=\"mdmfDistMode\" value=\"").append(pMode).append("\"").append(selected ? " checked=\"checked\"" : "").append("/>");
		pOut.append("<span class=\"mdmf-choice-card-title\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.mode." + pMode))).append("</span>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.mode." + pMode + ".explain"))).append("</p>");
		pOut.append("</label>");
	}

	/**
	 * Live addition (2026-09-25, task brief item 4): a Kafka-style connector destination is its OWN property
	 * ({@code topic}/{@code key.field}), never chosen through this mapping - this module never changes
	 * Connectors, so 7b only ever shows what the connector already declares, read-only, plus a warning when
	 * {@code key.field} does not name a real field of the project's master table(s) ({@link
	 * DistributionModel#masterFieldNames}). Renders nothing for an ordinary connector (blank {@code topic}).
	 */
	private static void renderDistributionDestinationHint(final StringBuilder pOut, final Locale pLocale, final DistributionModel pD, final DistributionTargetRow pTarget)
	{
		DistributionConnectionRow connection = null;
		for (final DistributionConnectionRow candidate : pD.connections)
		{
			if (candidate.connector.equals(pTarget.connector))
			{
				connection = candidate;
				break;
			}
		}
		// round 6 (task brief item C): a kafka connector now shows the PER-TABLE rows instead (renderDistributionTableDestinations) -
		// this single connector-level line would otherwise be redundant/misleading for exactly the connectors that fixed the live bug.
		if (connection == null || connection.topic.isEmpty() || "kafka".equalsIgnoreCase(connection.type))
		{
			return;
		}
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(connection.keyField.isEmpty()
			? WizardMessages.get(pLocale, "wizard.step7dist.setup.sendsTo.noKey", connection.topic)
			: WizardMessages.get(pLocale, "wizard.step7dist.setup.sendsTo", connection.topic, connection.keyField))).append("</p>");
		if (!connection.keyField.isEmpty() && !pD.masterFieldNames.isEmpty() && !pD.masterFieldNames.contains(connection.keyField))
		{
			pOut.append("<p class=\"mdmf-hint mdmf-hint-warn\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.keyFieldWarning", connection.keyField))).append("</p>");
		}
	}

	/**
	 * Round 6 (2026-09-26, task brief item C, Personnes live incident): one editable "Table -&gt; topic" row per
	 * master table, replacing the single connector-level "Sends to" line as the actual send destination for a
	 * connector that reads one per call (Kafka's own {@code publish} mapping inputs {@code topic}/{@code key} -
	 * read-only reference {@code KafkaConnector#publish}). Renders nothing at all for a connector that does not
	 * support this (every connector type other than {@code kafka} today - {@link DistributionConnectionRow#type}).
	 * <p>
	 * Each row is a hidden {@code mdmfDistTopicTable} (the table path) paired with a visible
	 * {@code mdmfDistTopicValue} text input - the SAME posted-array pattern the data-product table checklist
	 * already uses ({@code mdmfProductTableChecked}), read back with {@code getParameterValues} on Save
	 * ({@code ProjectWizardUserService#collectTableDestinations}). Prefilled from the target's OWN stored
	 * {@code tableDestinations} when it already has an entry for that table, else from {@link
	 * TableDestinationDefaults#proposeDestination} - a proposal only, never written back until the row is
	 * actually saved (same "never re-applied once stored" rule {@link TableDestinationDefaults} documents).
	 * <p>
	 * Topic creation is explicitly NOT this module's job (task brief): the note under the rows says so plainly
	 * rather than implying a topic picker this module does not build.
	 */
	private static void renderDistributionTableDestinations(final StringBuilder pOut, final Model pModel, final Locale pLocale, final DistributionModel pD,
		final DistributionTargetRow pTarget)
	{
		DistributionConnectionRow connection = null;
		for (final DistributionConnectionRow candidate : pD.connections)
		{
			if (candidate.connector.equals(pTarget.connector))
			{
				connection = candidate;
				break;
			}
		}
		if (connection == null || !"kafka".equalsIgnoreCase(connection.type) || pD.masterTables.isEmpty())
		{
			return;
		}
		final Map<String, String> stored = TargetMappingList.parse(pTarget.tableDestinations);
		final String projectCode = pModel.snapshot.project == null ? "" : pModel.snapshot.project.code;
		pOut.append("<div class=\"mdmf-field\"><label>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.tableDestinations.title")))
			.append("</label>");
		for (final MasterTableRow table : pD.masterTables)
		{
			String destination = stored.get(table.tablePath);
			if (destination == null || destination.trim().isEmpty())
			{
				destination = TableDestinationDefaults.proposeDestination(connection.topic, projectCode, table.tablePath);
			}
			pOut.append("<div class=\"mdmf-field mdmf-field-row\">");
			pOut.append("<input type=\"hidden\" name=\"mdmfDistTopicTable\" value=\"").append(WizardPage.esc(table.tablePath)).append("\"/>");
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(table.label)).append("</span>");
			pOut.append("<input type=\"text\" name=\"mdmfDistTopicValue\" value=\"").append(WizardPage.esc(destination)).append("\"/>");
			pOut.append("</div>");
		}
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.tableDestinations.note"))).append("</p>");
		pOut.append("</div>");
	}

	/**
	 * Board {@code Modes.dc.html}'s "Armed only after the initial loads" checklist plus the target's own
	 * actions (Configure/Install triggers/first full send/arm without it/disarm/send now) - {@code
	 * DistributionArmingGate}/{@code DistributionRunner} on the EBX side.
	 */
	private static void renderDistributionArming(final StringBuilder pOut, final Model pModel, final Locale pLocale, final DistributionModel pD,
		final DistributionTargetRow pTarget)
	{
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"DISTRIBUTION_TARGET_CONFIGURE\" data-mdmf-id=\"").append(WizardPage.esc(pTarget.id))
			.append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.configure"))).append("</button>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"DISTRIBUTION_TARGET_INSTALL_TRIGGERS\" data-mdmf-id=\"")
			.append(WizardPage.esc(pTarget.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.installTriggers")))
			.append("</button>");
		// task brief item 4: connectors_describe is a config-level check only (it never opens a real network
		// connection to the target) - see DistributionConnections#testConnection's own Javadoc for what it does
		// and does not catch.
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"DISTRIBUTION_TARGET_TEST_CONNECTION\" data-mdmf-id=\"")
			.append(WizardPage.esc(pTarget.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.testConnection")))
			.append("</button>");
		pOut.append("</div>");
		if ("REALTIME".equals(pTarget.mode))
		{
			final DistributionReadinessRow r = pD.readiness;
			pOut.append("<div class=\"mdmf-panel\" style=\"background:var(--accent-faint)\"><span class=\"mdmf-eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.arming.title"))).append("</span>");
			WizardPage.renderArmingChecklistItem(pOut, pLocale, r.landingLoadedFromEverySource, "wizard.step7dist.arming.landing");
			WizardPage.renderArmingChecklistItem(pOut, pLocale, r.masterBuilt, "wizard.step7dist.arming.master");
			if (r.noMatchingPlanned)
			{
				WizardPage.renderArmingChecklistItem(pOut, pLocale, true, "wizard.step7dist.arming.matching.none");
			}
			else
			{
				WizardPage.renderArmingChecklistItem(pOut, pLocale, r.firstMatchingRunFinished, "wizard.step7dist.arming.matching");
			}
			final boolean armed = "ARMED".equals(pTarget.state);
			// Round 5 (2026-09-26, task brief item 1, DistribTest live incident): a target armed through the
			// admin override never ran a first full send, so the line must not tick just because state==ARMED.
			// Tick only when a real first-full-send batch actually ran (firstFullSendRunId set); an override
			// gets its own distinct (amber) state, never the plain green tick nor the plain grey "waiting".
			final boolean firstFullSendDone = pTarget.firstFullSendRunId != null && !pTarget.firstFullSendRunId.isEmpty();
			if (!firstFullSendDone && pTarget.armedViaOverride)
			{
				WizardPage.renderArmingChecklistItem(pOut, pLocale, "needsPerson", "!", "wizard.step7dist.arming.firstFullSend.override");
			}
			else
			{
				WizardPage.renderArmingChecklistItem(pOut, pLocale, firstFullSendDone, "wizard.step7dist.arming.firstFullSend");
			}
			pOut.append("<div class=\"mdmf-actions\">");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"DISTRIBUTION_TARGET_FIRST_FULL_SEND\" data-mdmf-id=\"")
				.append(WizardPage.esc(pTarget.id)).append("\"").append(r.pipelineReady() ? "" : " disabled=\"disabled\"").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.arming.runFirstFull"))).append("</button>");
			if (pModel.administrator && !armed)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"DISTRIBUTION_TARGET_ARM_OVERRIDE\" data-mdmf-id=\"")
					.append(WizardPage.esc(pTarget.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.arming.override")))
					.append("</button>");
			}
			pOut.append("</div>");
			pOut.append("</div>");
		}
		else
		{
			pOut.append("<div class=\"mdmf-actions\">");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"DISTRIBUTION_TARGET_SEND_NOW\" data-mdmf-id=\"")
				.append(WizardPage.esc(pTarget.id)).append("\" data-mdmf-dist-send-scope=\"changed\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.sendChanged"))).append("</button>");
			pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"DISTRIBUTION_TARGET_SEND_NOW\" data-mdmf-id=\"").append(WizardPage.esc(pTarget.id))
				.append("\" data-mdmf-dist-send-scope=\"all\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.sendAll"))).append("</button>");
			pOut.append("</div>");
		}
		if (!"DISABLED".equals(pTarget.state))
		{
			pOut.append("<div class=\"mdmf-actions\">");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_TARGET_DISARM\" data-mdmf-id=\"")
				.append(WizardPage.esc(pTarget.id)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.setup.disarm"))).append("</button>");
			pOut.append("</div>");
		}
	}

	private static void renderArmingChecklistItem(final StringBuilder pOut, final Locale pLocale, final boolean pDone, final String pKey)
	{
		WizardPage.renderArmingChecklistItem(pOut, pLocale, pDone ? "done" : "waiting", pDone ? "&#10003;" : "", pKey);
	}

	/** Round 5 (2026-09-26): a third rendering, distinct from plain done/waiting, for the "armed via override,
	 * no first full send ran" checklist line - reuses the same amber style as the "needs a person" state chip. */
	private static void renderArmingChecklistItem(final StringBuilder pOut, final Locale pLocale, final String pStateClass, final String pIcon, final String pKey)
	{
		pOut.append("<div class=\"mdmf-actions\"><span class=\"mdmf-sc mdmf-sc-").append(pStateClass)
			.append("\" style=\"width:18px;height:18px;font-size:10px\">").append(pIcon).append("</span><span>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pKey))).append("</span></div>");
	}

	/** Board {@code DataProduct.dc.html} (7c) - the "publish as a data product" section, below the target list. */
	private static void renderDistributionProduct(final StringBuilder pOut, final Model pModel, final Locale pLocale, final DistributionModel pD)
	{
		final DataProductModel p = pD.product;
		pOut.append("<div class=\"mdmf-panel\">");
		if (!p.catalogInstalled)
		{
			pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.title"))).append("</span>");
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.noCatalog"))).append("</p></div>");
			return;
		}
		if (p.catalogRestricted)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\" data-mdmf-catalog-restricted=\"true\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.catalog.restricted"))).append("</div>");
		}
		if (!pD.productPanelOpen)
		{
			pOut.append("<div class=\"mdmf-actions\" style=\"justify-content:space-between\"><span class=\"mdmf-eyebrow\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.title"))).append("</span>");
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary\" data-mdmf-action=\"DISTRIBUTION_PRODUCT_OPEN\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.open"))).append("</button></div>");
			if (p.snowflakePresent)
			{
				// audit F-2-101: reachability is only known after an explicit test (the probe wakes the customer's warehouse), never at display
				pOut.append("<p class=\"mdmf-hint\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, !p.snowflakeTested ? "wizard.step7dist.product.snowflakeUntested"
						: p.snowflakeReachable ? "wizard.step7dist.product.snowflakeReachable" : "wizard.step7dist.product.snowflakeUnreachable")))
					.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_WAREHOUSE_TEST\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.snowflakeTest"))).append("</button></p>");
			}
			pOut.append("</div>");
			return;
		}
		pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.title"))).append("</span>");
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.lead"))).append("</p>");

		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.name")))
			.append("<input type=\"text\" name=\"mdmfProductName\" value=\"").append(WizardPage.esc(p.productName)).append("\"/></label>");
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.code")))
			.append("<input type=\"text\" name=\"mdmfProductCode\" value=\"").append(WizardPage.esc(p.code)).append("\"/></label>");
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.owner")))
			.append("<input type=\"text\" name=\"mdmfProductOwner\" value=\"").append(WizardPage.esc(p.owner)).append("\"/></label>");
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.domain")))
			.append("<input type=\"text\" name=\"mdmfProductDomain\" value=\"").append(WizardPage.esc(p.domain)).append("\"/></label>");

		pOut.append("<table class=\"mdmf-run-grid\"><thead><tr><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.col.asset")))
			.append("</th><th>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.col.from"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.col.rows"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.col.publishedAs"))).append("</th><th>")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.col.nest"))).append("</th><th></th></tr></thead><tbody>");
		for (final DataProductTableRow row : p.tables)
		{
			pOut.append("<tr><td>").append(WizardPage.esc(row.label));
			if (row.added)
			{
				pOut.append(" <span class=\"mdmf-chip mdmf-chip-accent\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.added"))).append("</span>");
			}
			pOut.append("</td><td>").append(WizardPage.esc(row.from)).append("</td><td>").append(row.rowCount).append("</td><td class=\"mono\">")
				.append(WizardPage.esc(row.publishedName)).append("</td><td>");
			// coordinator review (2026-09-24): the per-child "Own table / Nested in <PARENT>" select - only a
			// child candidate can be nested (ProductPlan.plan's own "not a child table" refusal otherwise), so a
			// non-child row simply has no such control, matching the board (only Address carries it).
			if (row.child)
			{
				pOut.append("<select name=\"mdmfProductNest__").append(WizardPage.esc(row.key)).append("\">");
				pOut.append("<option value=\"OWN\"").append("OWN".equals(row.nestMode) ? " selected=\"selected\"" : "").append(">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.nest.own"))).append("</option>");
				pOut.append("<option value=\"NESTED\"").append("NESTED".equals(row.nestMode) ? " selected=\"selected\"" : "").append(">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.nest.nested", row.parentLabel))).append("</option>");
				pOut.append("</select>");
			}
			pOut.append("</td><td>");
			pOut.append("<label><input type=\"checkbox\" name=\"mdmfProductTableChecked\" value=\"").append(WizardPage.esc(row.key)).append("\"")
				.append(row.included ? " checked=\"checked\"" : "").append("/> ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.included"))).append("</label>");
			pOut.append("</td></tr>");
		}
		pOut.append("</tbody></table>");

		WizardPage.renderDistributionProductAssetCombo(pOut, pLocale, p);

		pOut.append("<div class=\"mdmf-field\"><span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.where"))).append("</span>");
		WizardPage.renderProductTargetOption(pOut, pLocale, p, "SNOWFLAKE", p.snowflakePresent);
		WizardPage.renderProductTargetOption(pOut, pLocale, p, "DATABRICKS", p.databricksPresent);
		WizardPage.renderProductTargetOption(pOut, pLocale, p, "EBX_ONLY", true);
		pOut.append("</div>");
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.schema")))
			.append("<input type=\"text\" name=\"mdmfProductSchema\" value=\"").append(WizardPage.esc(p.schema)).append("\"/></label>");
		pOut.append("<label class=\"mdmf-field\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.refresh")))
			.append("<select name=\"mdmfProductRefresh\">");
		for (final String mode : new String[] { "ON_DEMAND", "HOURLY", "DAILY", "WEEKLY" })
		{
			pOut.append("<option value=\"").append(mode).append("\"").append(mode.equals(p.refreshMode) ? " selected=\"selected\"" : "").append(">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.refresh." + mode))).append("</option>");
		}
		pOut.append("</select></label>");
		pOut.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" name=\"mdmfProductListing\" value=\"true\"").append(p.marketplaceListing ? " checked=\"checked\"" : "")
			.append("/> ").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.listing"))).append("</label>");

		if (p.contractColumnCount > 0)
		{
			pOut.append("<p class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardPage.plural(pLocale, p.contractColumnCount, "wizard.step7dist.product.contract.one", "wizard.step7dist.product.contract.other",
					new Object[] { Integer.valueOf(p.tables.size()) })))
				.append("</p>");
		}
		if (!p.warnings.isEmpty())
		{
			for (final String warning : p.warnings)
			{
				pOut.append("<p class=\"mdmf-hint\">&#9888; ").append(WizardPage.esc(warning)).append("</p>");
			}
		}
		if (!p.problems.isEmpty())
		{
			for (final String problem : p.problems)
			{
				pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\">").append(WizardPage.esc(problem)).append("</div>");
			}
		}
		if (!p.creationSteps.isEmpty())
		{
			pOut.append("<div class=\"mdmf-hint\"><b>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.willCreate"))).append("</b><ul>");
			for (final String step : p.creationSteps)
			{
				pOut.append("<li>").append(WizardPage.esc(step)).append("</li>");
			}
			pOut.append("</ul></div>");
		}
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-big\" data-mdmf-action=\"DISTRIBUTION_PRODUCT_CREATE\"")
			.append(p.problems.isEmpty() ? "" : " disabled=\"disabled\"").append(">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, p.created ? "wizard.step7dist.product.republish" : "wizard.step7dist.product.create"))).append("</button>");
		pOut.append("</div>");
		pOut.append("</div>");
	}

	private static void renderProductTargetOption(final StringBuilder pOut, final Locale pLocale, final DataProductModel pP, final String pTarget, final boolean pAvailable)
	{
		pOut.append("<label class=\"mdmf-hint\"><input type=\"radio\" name=\"mdmfProductTarget\" value=\"").append(pTarget).append("\"")
			.append(pTarget.equals(pP.target) ? " checked=\"checked\"" : "").append(pAvailable ? "" : " disabled=\"disabled\"").append("/> ")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.target." + pTarget)))
			.append(pAvailable ? "" : " (" + WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.target.noConnection")) + ")").append("</label>");
	}

	/**
	 * Coordinator review (2026-09-24, board {@code DistributionDataProduct.dc.html}): "+ Add assets from the
	 * catalogue" - the same searchable combobox widget every other combo on this page uses ({@code combo.js},
	 * {@code window.mdmfInitCombo} - see {@code renderCatalogSourceCombo}'s own Javadoc for the shared markup
	 * contract), listing {@link DataProductModel#addableAssets} (catalogue candidates not yet added - {@code
	 * ProjectWizardUserService#buildDistributionProduct} already excludes an added one from this list). Picking
	 * one clicks the hidden {@code mdmfProductAddAssetPick} button, which posts {@code
	 * DISTRIBUTION_PRODUCT_ADD_ASSET} with the asset's id in {@code mdmfProductAddAssetId} - the SAME "hidden
	 * pick button" round-trip technique {@code renderOnboardConnectionCombo} already uses, wired up in {@code
	 * wizard.js}'s own {@code initCombo(...)} call list.
	 */
	private static void renderDistributionProductAssetCombo(final StringBuilder pOut, final Locale pLocale, final DataProductModel pP)
	{
		pOut.append("<div class=\"mdmf-field\"><label for=\"mdmfProductAssetSearch\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.addAssets"))).append("</label>");
		pOut.append("<div class=\"mdmf-combo\" id=\"mdmfProductAssetCombo\" data-mdmf-nomatch=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.addAssets.noMatch"))).append("\">");
		pOut.append("<span class=\"mdmf-combo-icon\" aria-hidden=\"true\">&#128269;</span>");
		pOut.append("<input type=\"text\" id=\"mdmfProductAssetSearch\" role=\"combobox\" aria-autocomplete=\"list\" aria-expanded=\"false\"")
			.append(" aria-controls=\"mdmfProductAssetList\" autocomplete=\"off\" spellcheck=\"false\" placeholder=\"")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.addAssets.search"))).append("\" value=\"\"/>");
		pOut.append("<ul class=\"mdmf-combo-list\" id=\"mdmfProductAssetList\" role=\"listbox\" hidden=\"hidden\">");
		for (final DataProductTableRow asset : pP.addableAssets)
		{
			final String search = (asset.label + " " + asset.from + " " + asset.key).toLowerCase(Locale.ROOT);
			pOut.append("<li role=\"option\" class=\"mdmf-combo-option\" data-mdmf-id=\"").append(WizardPage.esc(asset.key)).append("\" data-mdmf-label=\"")
				.append(WizardPage.esc(asset.label)).append("\" data-search=\"").append(WizardPage.esc(search)).append("\" aria-selected=\"false\">");
			pOut.append("<span class=\"mdmf-combo-label\">").append(WizardPage.esc(asset.label)).append("</span>");
			pOut.append("<span class=\"mdmf-combo-meta\"><span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(asset.from)).append("</span></span>");
			pOut.append("</li>");
		}
		pOut.append("<li class=\"mdmf-combo-empty\" hidden=\"hidden\"></li>");
		pOut.append("</ul></div>");
		pOut.append("<input type=\"hidden\" id=\"mdmfProductAddAssetId\" name=\"mdmfProductAddAssetId\" value=\"\"/>");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" id=\"mdmfProductAddAssetPick\" data-mdmf-action=\"DISTRIBUTION_PRODUCT_ADD_ASSET\" hidden=\"hidden\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.addAssets.pick"))).append("</button>");
		pOut.append("</div>");
	}

	/**
	 * ROUND 1 of the wizard-visual-pass lot (2026-09-24): rebuilt to match board {@code Summary.dc.html} -
	 * no separate page head above the verdict card (the board has none; the verdict banner itself is the
	 * head, "per its verdict" per the coordinator's instruction), a two-column layout (verdict + journey +
	 * zone cards + open points + go-to-production in the main column, completeness + last full run in the
	 * side column). The empty state (no project data computed yet) is the one case that still shows a
	 * plain {@link #renderPageHead} - there is no verdict to stand in for it.
	 */
	/** Renamed from {@code renderStep7} by the distribution-wizard lot (2026-09-24): Summary is now step 8 - see {@link WizardRules#STEP_SUMMARY}/{@link WizardRules#STEP_DISTRIBUTION}. Message keys under {@code wizard.step7.*} were deliberately left as-is (a mechanical rename across this whole class and {@code ProjectWizardUserService} was judged too large/risky for this lot) - they are internal bundle-key identifiers, not shown to a user. */
	private static void renderStep8(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		pOut.append("<section class=\"mdmf-wiz-panel\" data-step=\"8\"").append(WizardPage.hiddenAttr(pModel, 8)).append(">");
		if (pModel.summary == null)
		{
			WizardPage.renderPageHead(pOut, WizardMessages.get(pLocale, "wizard.step7.title"),
				WizardMessages.get(pLocale, "wizard.step7.lead", pModel.projectExists() ? pModel.snapshot.project.label : ""));
			if (pModel.summaryDeferred)
			{
				// wave 2: see Model#summaryDeferred. The button is the fallback when the automatic load did not fire.
				pOut.append("<div class=\"mdmf-notice mdmf-notice-target\" data-mdmf-deferred-step=\"8\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step8.deferred"))).append("</div>")
					.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"STEP_VIEW\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step8.deferred.load"))).append("</button></div></section>");
				return;
			}
			pOut.append("<div class=\"mdmf-notice mdmf-notice-target\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.empty"))).append("</div></section>");
			return;
		}
		final WizardSummaryData.Output s = pModel.summary;
		if (pModel.intake != null && pModel.intake.open)
		{
			// automatic-intake wizard lot: the intake sub-screen (set-up / running) takes the place of the summary's content, inside this same step
			IntakeScreens.renderScreen(pOut, pModel, pLocale);
			pOut.append("</section>");
			return;
		}
		WizardPage.render2ColStart(pOut);
		WizardPage.renderSummaryBanner(pOut, pModel, pLocale, s);
		WizardPage.renderZoneCards(pOut, pLocale, s);
		WizardPage.renderOpenPoints(pOut, pLocale, s);
		WizardPage.renderProductionCards(pOut, pModel, pLocale);
		WizardPage.render2ColSplit(pOut);
		WizardPage.renderCompleteness(pOut, pLocale, s);
		WizardPage.renderJournal(pOut, pLocale, s);
		// UX lot U5: the words the summary itself uses (open points, the automatic intake card, "Schedule...")
		WizardPage.renderGlossaryCard(pOut, pLocale, "wizard.step8.glossary.openPoint", "wizard.step8.glossary.intake", "wizard.step8.glossary.schedule");
		WizardPage.render2ColEnd(pOut);
		pOut.append("</section>");
	}

	private static void renderSummaryBanner(final StringBuilder pOut, final Model pModel, final Locale pLocale, final WizardSummaryData.Output pS)
	{
		final String kind = "OPERATIONAL".equals(pS.verdict) ? "good" : ("BLOCKED".equals(pS.verdict) ? "bad" : "warn");
		final String glyph = "good".equals(kind) ? "✓" : "!";
		pOut.append("<div class=\"mdmf-panel mdmf-summary-banner mdmf-summary-").append(kind).append("\">");
		pOut.append("<span class=\"mdmf-sc mdmf-sc-").append("good".equals(kind) ? "done" : "needsPerson").append("\" style=\"width:44px;height:44px;font-size:18px;border-radius:12px\">")
			.append(glyph).append("</span>");
		pOut.append("<span class=\"mdmf-summary-text\"><h3>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.verdict." + pS.verdict,
			pModel.projectExists() ? pModel.snapshot.project.label : ""))).append("</h3>");
		pOut.append("<p>").append(WizardPage.esc(WizardPage.plural(pLocale, pS.openPointCount, "wizard.step7.verdict.openCount.one", "wizard.step7.verdict.openCount.other")))
			.append("</p></span>");
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"6\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.backToMatching"))).append("</button>");
		final String cockpitUrl = pModel.summaryInput == null ? "" : pModel.summaryInput.cockpitUrl;
		if (!cockpitUrl.isEmpty())
		{
			pOut.append("<a class=\"mdmf-btn mdmf-btn-primary\" href=\"").append(WizardPage.esc(cockpitUrl)).append("\" target=\"_top\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.openCockpit"))).append("</a>");
		}
		pOut.append("</div></div>");
	}

	private static void renderJourney(final StringBuilder pOut, final Locale pLocale, final WizardSummaryData.Journey pJourney)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-journey-panel\"><h3 class=\"mdmf-subtitle\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journey.title")))
			.append("</h3><p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journey.hint"))).append("</p>");
		pOut.append("<svg class=\"mdmf-journey\" viewBox=\"0 0 ").append(pJourney.width).append(' ').append(pJourney.height).append("\" width=\"").append(pJourney.width)
			.append("\" height=\"").append(pJourney.height).append("\" role=\"img\">");
		for (final WizardSummaryData.JourneyFlow flow : pJourney.flows)
		{
			pOut.append("<path d=\"").append(flow.pathD).append("\" fill=\"").append(flow.color).append("\" opacity=\"0.22\"></path>");
		}
		for (final WizardSummaryData.JourneyNode node : pJourney.nodes)
		{
			pOut.append("<rect x=\"").append(node.x).append("\" y=\"").append(node.y).append("\" width=\"").append(node.w).append("\" height=\"").append(node.h)
				.append("\" rx=\"3\" fill=\"").append(node.greyed ? "#d0d5dd" : node.color).append("\"></rect>");
		}
		pOut.append("</svg>");
		pOut.append("<ul class=\"mdmf-journey-legend\">");
		for (final WizardSummaryData.JourneyNode node : pJourney.nodes)
		{
			if (node.label.isEmpty())
			{
				continue; // the four zone nodes carry no own label here - the zone cards below name them
			}
			// unused from renderStep7 since the ROUND 1 visual pass (board Summary.dc.html has no separate
			// Sankey diagram) - kept compiling; node.detail is already-formatted text, not a raw count, so
			// there is no real singular/plural to pick here - the .other form is always used.
			pOut.append("<li><span style=\"background:").append(node.greyed ? "#d0d5dd" : node.color).append("\"></span>").append(WizardPage.esc(node.label)).append(" - ")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journey.rows.other", node.detail))).append("</li>");
		}
		pOut.append("</ul></div>");
	}

	static final String ZONE_ARROW = "<span class=\"mdmf-zone-arrow\"><svg width=\"18\" height=\"18\" viewBox=\"0 0 24 24\" fill=\"none\" stroke=\"currentColor\" stroke-width=\"2\" "
		+ "stroke-linecap=\"round\" stroke-linejoin=\"round\" aria-hidden=\"true\"><line x1=\"5\" y1=\"12\" x2=\"19\" y2=\"12\"></line><polyline points=\"12 5 19 12 12 19\">"
		+ "</polyline></svg></span>";

	/**
	 * ROUND 1 follow-up: renders the data journey as the board's own four zone cards joined by arrows
	 * (board {@code Summary.dc.html}) - the earlier SVG Sankey ({@link #renderJourney}) is no longer
	 * called from {@link #renderStep7} (kept, unused, rather than deleted - its data still comes from
	 * {@link WizardSummaryData}, unit-tested, and a later round may still want it elsewhere).
	 */
	private static void renderZoneCards(final StringBuilder pOut, final Locale pLocale, final WizardSummaryData.Output pS)
	{
		pOut.append("<span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journey.title"))).append("</span>");
		pOut.append("<div class=\"mdmf-zone-grid\">");
		boolean first = true;
		for (final WizardSummaryData.ZoneCard zone : pS.zones)
		{
			if (!first)
			{
				pOut.append(WizardPage.ZONE_ARROW);
			}
			first = false;
			final String zoneKey = zone.zoneKey.toLowerCase(Locale.ENGLISH);
			pOut.append("<div class=\"mdmf-panel mdmf-zone-card\"><div class=\"mdmf-zone-top\"><span class=\"mdmf-zone-name\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.zoneName." + zoneKey))).append("</span><span class=\"mdmf-chip mdmf-chip-")
				.append("good".equals(zone.statusKind) ? "real" : ("warn".equals(zone.statusKind) ? "warn" : ("failed".equals(zone.statusKind) ? "bad" : "target"))).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.zoneStatus." + zone.statusKind))).append("</span></div><ul class=\"mdmf-zone-facts\">");
			if (zone.lines.isEmpty())
			{
				for (int i = 0; i < zone.facts.size(); i++)
				{
					pOut.append("<li>").append(WizardPage.esc(WizardPage.zoneFactText(pLocale, zoneKey, i, zone.facts.get(i)))).append("</li>");
				}
			}
			for (final WizardSummaryData.ZoneLine line : zone.lines)
			{
				// step 7 screen counts (2026-10-18): one line per table, its own rows - never the rows of several tables added into one figure
				pOut.append("<li data-mdmf-zone-line=\"").append(WizardPage.esc(line.object)).append("\">").append(WizardPage.esc(WizardPage.zoneLineText(pLocale, line))).append("</li>");
			}
			pOut.append("</ul>");
			if (!zone.details.isEmpty())
			{
				// UX lot U3: one line per business object - its plan and the outcome of its last run (a failed run reads in the warning colour)
				pOut.append("<ul class=\"mdmf-zone-facts mdmf-zone-details\">");
				for (final WizardSummaryData.ZoneDetail detail : zone.details)
				{
					pOut.append("<li").append("FAILED".equals(detail.run) ? " class=\"mdmf-zone-detail-bad\"" : "").append(">")
						.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.zoneDetail.line", detail.object,
							WizardMessages.get(pLocale, "wizard.step7.zoneDetail.plan." + detail.plan), WizardMessages.get(pLocale, "wizard.step7.zoneDetail.run." + detail.run))))
						.append("</li>");
				}
				pOut.append("</ul>");
			}
			if (!zone.technicalName.isEmpty())
			{
				pOut.append("<span class=\"mdmf-mono\">").append(WizardPage.esc(zone.technicalName)).append("</span>");
			}
			if (zone.openGotoStep > 0)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"").append(zone.openGotoStep).append("\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.zoneOpen." + zoneKey))).append("</button>");
			}
			else if (!zone.openUrl.isEmpty())
			{
				// visual pass, step 7 round (2026-09-24): the board's own accent-coloured link, never the
				// unstyled default (blue, underlined) this bare <a> rendered as before - .mdmf-link-accent,
				// same var(--accent) token every other accent element on this page already uses.
				pOut.append("<a class=\"mdmf-link-accent").append("staging".equals(zoneKey) ? " mdmf-feed-matrix-link" : "").append("\" href=\"").append(WizardPage.esc(zone.openUrl)).append("\" target=\"_top\">")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.zoneOpen." + zoneKey))).append("</a>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");
	}

	/** "Personne : 699 enregistrements dont 212 golden records (487 fusionn\u00E9s)" for the main object after a run, "Adresse : 1068 enregistrements" for the others. */
	static String zoneLineText(final Locale pLocale, final WizardSummaryData.ZoneLine pLine)
	{
		final String rows = WizardPage.countText(pLocale, pLine.rows, "wizard.unit.record");
		if (pLine.golden == null)
		{
			return WizardMessages.get(pLocale, "wizard.step7.zoneLine.rows", pLine.object, rows);
		}
		final String golden = WizardPage.countText(pLocale, pLine.golden.longValue(), "wizard.unit.goldenRecord");
		if (pLine.merged == null || pLine.merged.longValue() == 0L)
		{
			return WizardMessages.get(pLocale, "wizard.step7.zoneLine.golden.noMerged", pLine.object, rows, golden);
		}
		return WizardMessages.get(pLocale, "wizard.step7.zoneLine.golden", pLine.object, rows, golden, WizardPage.countText(pLocale, pLine.merged.longValue(), "wizard.unit.mergedRecord"));
	}

	/**
	 * One zone-card fact, with every count's noun in its own singular/plural form (visual pass, step 7 round,
	 * 2026-09-24 - never "table(s)"). {@code pFact} is {@link WizardSummaryData}'s own raw value: one count
	 * ({@code "2"}) or two ({@code "2/9"}); each number is paired with its noun ({@link #ZONE_FACT_UNITS}) and
	 * the resulting "N noun" phrases are the arguments of {@code wizard.step7.zoneFact.<zone>.<index>}.
	 */
	private static String zoneFactText(final Locale pLocale, final String pZoneKey, final int pIndex, final String pFact)
	{
		final String key = "wizard.step7.zoneFact." + pZoneKey + "." + pIndex;
		final String[] units = WizardPage.ZONE_FACT_UNITS.get(pZoneKey + "." + pIndex);
		final String[] numbers = pFact.split("/");
		if (units == null || numbers.length != units.length)
		{
			return WizardMessages.get(pLocale, key, pFact);
		}
		final Object[] args = new Object[units.length];
		for (int u = 0; u < units.length; u++)
		{
			long count;
			try
			{
				count = Long.parseLong(numbers[u].trim());
			}
			catch (final NumberFormatException ex)
			{
				return WizardMessages.get(pLocale, key, pFact);
			}
			args[u] = units[u] == null ? String.valueOf(count) : WizardPage.countText(pLocale, count, units[u]);
		}
		return WizardMessages.get(pLocale, key, args);
	}

	/** Noun unit key per zone fact (null = a bare number, e.g. the "out of M" of matching.0) - order matches {@link WizardSummaryData}'s own "N/M" facts. */
	private static final Map<String, String[]> ZONE_FACT_UNITS = new HashMap<String, String[]>();
	static
	{
		WizardPage.ZONE_FACT_UNITS.put("landing.0", new String[] { "wizard.unit.table" });
		WizardPage.ZONE_FACT_UNITS.put("landing.1", new String[] { "wizard.unit.table" });
		WizardPage.ZONE_FACT_UNITS.put("staging.0", new String[] { "wizard.unit.businessObject" });
		WizardPage.ZONE_FACT_UNITS.put("staging.1", new String[] { "wizard.unit.feed", "wizard.unit.mappingRule" });
		WizardPage.ZONE_FACT_UNITS.put("staging.2", new String[] { "wizard.unit.qualityRule", "wizard.unit.constraint" });
		WizardPage.ZONE_FACT_UNITS.put("master.0", new String[] { "wizard.unit.record" });
		WizardPage.ZONE_FACT_UNITS.put("master.1", new String[] { "wizard.unit.goldenRecord" });
		WizardPage.ZONE_FACT_UNITS.put("matching.0", new String[] { "wizard.unit.matchPlan", "wizard.unit.businessObject" });
		WizardPage.ZONE_FACT_UNITS.put("matching.1", new String[] { "wizard.unit.pairToDecide" });
	}

	private static void renderOpenPoints(final StringBuilder pOut, final Locale pLocale, final WizardSummaryData.Output pS)
	{
		// visual pass, step 7 round (2026-09-24): eyebrow label ("OPEN POINTS"), like the board and like the
		// "Data journey" section already does (renderZoneCards) - never a plain <h3>.
		pOut.append("<div class=\"mdmf-panel mdmf-open-points\"><span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.openPoints.title")))
			.append("</span>");
		if (pS.openPoints.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.openPoints.none"))).append("</p>");
		}
		for (final WizardSummaryData.OpenPointInput point : pS.openPoints)
		{
			final String color = "ERROR".equals(point.severity) ? "#f04438" : ("WARN".equals(point.severity) ? "#f5b400" : "#98a2b3");
			pOut.append("<div class=\"mdmf-open-point\"><span class=\"mdmf-open-dot\" style=\"background:").append(color).append("\"></span>")
				.append("<span class=\"mdmf-open-text\"><b>").append(WizardPage.esc(point.title)).append("</b><span>").append(WizardPage.esc(point.detail)).append("</span></span>");
			if (point.gotoStep > 0 && !point.targetId.isEmpty())
			{
				// coordinator review (2026-09-24): "Run it" on a "target not armed" open point must both switch to
				// step 7 AND open that one target's setup panel, in one click. GOTO_STEP alone is purely
				// client-side (wizard.js never posts for it); DISTRIBUTION_TARGET_OPEN alone is a real submit but
				// leaves whatever step was already showing untouched. Composing BOTH attributes on the SAME
				// button works with zero wizard.js change: [data-mdmf-goto-step] gets its own click listener
				// (registered first in that file) that runs showStep(7) - setting wizard.js's own "current step"
				// variable - before the [data-mdmf-action] loop's listener (registered second, same element, both
				// fire on one click: preventDefault on the first listener does not stop the second) reads that
				// now-updated "current step" into the posted mdmfStep field and submits DISTRIBUTION_TARGET_OPEN
				// with the target's id. Verified by WizardPageTest's own assertion on the exact markup this
				// produces, not re-derived from a live browser click order.
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DISTRIBUTION_TARGET_OPEN\" data-mdmf-id=\"")
					.append(WizardPage.esc(point.targetId)).append("\" data-mdmf-goto-step=\"").append(point.gotoStep).append("\">").append(WizardPage.esc(point.actionLabel))
					.append("</button>");
			}
			else if (point.gotoStep > 0)
			{
				pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"").append(point.gotoStep).append("\">")
					.append(WizardPage.esc(point.actionLabel)).append("</button>");
			}
			else if (!point.href.isEmpty())
			{
				pOut.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(WizardPage.esc(point.href)).append("\" target=\"_top\">").append(WizardPage.esc(point.actionLabel))
					.append("</a>");
			}
			pOut.append("</div>");
		}
		pOut.append("</div>");
	}

	private static void renderCompleteness(final StringBuilder pOut, final Locale pLocale, final WizardSummaryData.Output pS)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-completeness\"><span class=\"mdmf-eyebrow\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, pS.completenessMasterFallback ? "wizard.step7.completeness.titleFallback" : "wizard.step7.completeness.title")))
			.append("</span>");
		if (pS.completenessGroups.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.completeness.none"))).append("</p>");
		}
		// UX lot U3 (2026-10-13): scoped per business object - "of the N golden records of X" with the bars of a matched object, "not matched yet" and
		// no bar for an object no matching run has produced goldens for (Organisation read "name 0 %" while its 62 records all had a name)
		// visual pass, step 7 round (2026-09-24): board Summary.dc.html's own bar (.mdmf-completeness-bar,
		// flat accent fill) - the older red/amber/green threshold colouring is gone, matching the board, which
		// never colour-codes this particular bar (the KPI tiles/chips elsewhere already carry that signal).
		for (final WizardSummaryData.CompletenessGroup group : pS.completenessGroups)
		{
			if (!group.entityName.isEmpty())
			{
				pOut.append("<p class=\"mdmf-completeness-scope\"><b>").append(WizardPage.esc(group.entityName)).append("</b> ")
					.append(WizardPage.esc(group.matched
						? WizardMessages.get(pLocale, "wizard.step7.completeness.group.matched", WizardPage.countText(pLocale, group.scopeCount, "wizard.unit.goldenRecord"))
						: WizardMessages.get(pLocale, "wizard.step7.completeness.group.unmatched")))
					.append("</p>");
			}
			for (final WizardSummaryData.CompletenessRow row : group.rows)
			{
				pOut.append("<div class=\"mdmf-completeness-row\"><span>").append(WizardPage.esc(row.label)).append("</span><span class=\"mdmf-completeness-bar\"><i style=\"width:")
					.append(row.pct).append("%\"></i></span><b>").append(row.pct).append(" %</b></div>");
			}
		}
		pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.completeness.hint"))).append("</p></div>");
	}

	private static void renderProductionCards(final StringBuilder pOut, final Model pModel, final Locale pLocale)
	{
		final WizardSummaryData.Input in = pModel.summaryInput; // raw gates - see Model.summaryInput's own Javadoc
		// visual pass, step 7 round (2026-09-24): eyebrow label ("GO TO PRODUCTION"), same as Data journey/Open
		// points - .mdmf-prod-title itself is kept (still referenced by wizard.css) but no longer used here.
		pOut.append("<span class=\"mdmf-eyebrow mdmf-prod-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.title"))).append("</span>");
		pOut.append("<div class=\"mdmf-prod-grid\">");

		pOut.append("<div class=\"mdmf-panel mdmf-prod-card\"><span class=\"mdmf-prod-name\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.chain.title")))
			.append("</span><p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.chain.text"))).append("</p><div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-primary mdmf-btn-sm\" data-mdmf-action=\"PIPELINE_LAUNCH\"")
			.append(in != null && in.canLaunchPipeline ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.chain.launch")))
			.append("</button>");
		if (in != null && !in.canLaunchPipeline && !in.pipelineReason.isEmpty())
		{
			pOut.append("<span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, in.pipelineReason))).append("</span>");
		}
		// audit F-40: "Schedule..." used to be a dead "#" link; it opens the user guide's scheduling section until the screen schedules itself
		if (in != null && !in.scheduleHref.isEmpty())
		{
			pOut.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(WizardPage.esc(in.scheduleHref)).append("\" target=\"").append(in.scheduleAvailable ? "_top" : "_blank")
				.append("\" rel=\"noopener\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.chain.schedule"))).append("</a>");
		}
		pOut.append("</div>");
		if (in != null && !in.scheduleAvailable)
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.chain.scheduleHint"))).append("</p>");
		}
		// automatic-intake wizard lot (2026-10-05, mockup IntakeSummary.dc.html): the separated "Automatic intake" block of this card
		IntakeScreens.renderCardBlock(pOut, pModel, pLocale);
		pOut.append("</div>");

		// coordinator review (2026-09-24, board SummaryDistribution.dc.html): the "Distribute" card - one line
		// per distribution target (mode + state chip, localized off the SAME wizard.step7dist.mode.*/state.*
		// keys the step 7 target cards already use, never a second wording) plus the data product's own line
		// when one was created for this project - see WizardSummaryData.Input#distributionTargets' own Javadoc
		// for why this reads Model.summaryInput directly rather than a computed Output shape.
		pOut.append("<div class=\"mdmf-panel mdmf-prod-card\"><span class=\"mdmf-prod-name\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.distribute.title")))
			.append("</span>");
		if (in != null && in.distributionTargets.isEmpty() && !in.distributionProductExists && in.distributionProductRestricted)
		{
			// audit F-34: no target, and a product the person's catalog right may hide - never "no data product yet", which may be untrue
			pOut.append("<p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.distribute.noTarget"))).append("</p>");
		}
		else if (in == null || in.distributionTargets.isEmpty() && !in.distributionProductExists)
		{
			pOut.append("<p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.distribute.none"))).append("</p>");
		}
		else
		{
			pOut.append("<ul class=\"mdmf-list\">");
			for (final WizardSummaryData.DistributionSummaryTarget target : in.distributionTargets)
			{
				pOut.append("<li>").append(WizardPage.esc(target.label)).append(" &middot; ")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.mode." + target.mode))).append(" &middot; ")
					.append(WizardPage.esc(DistributionTexts.summaryState(pLocale, target.state, target.madeFirstFullSend, target.lastSendAt, target.lastMessage, java.time.ZoneId.systemDefault())));
				if (target.retiresSent >= 0)
				{
					pOut.append(" &middot; ").append(WizardPage.esc(DistributionTexts.retireSent(pLocale, target.retiresSent, target.retiresCapped)));
				}
				pOut.append("</li>");
			}
			if (in.distributionProductExists)
			{
				pOut.append("<li>").append(WizardPage.esc(in.distributionProductLabel)).append(" &middot; ")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7dist.product.refresh." + in.distributionProductRefreshMode))).append(" &middot; ")
					.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.distribute.product"))).append("</li>");
			}
			pOut.append("</ul>");
		}
		if (in != null && !in.distributionProductExists && in.distributionProductRestricted)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-warn\" data-mdmf-catalog-restricted=\"true\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.catalog.restricted")))
				.append("</div>");
		}
		pOut.append("<div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"GOTO_STEP\" data-mdmf-goto-step=\"").append(WizardRules.STEP_DISTRIBUTION).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.distribute.change"))).append("</button>");
		pOut.append("</div></div>");

		pOut.append("<div class=\"mdmf-panel mdmf-prod-card\"><span class=\"mdmf-prod-name\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.deliver.title")))
			.append("</span><p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.deliver.text"))).append("</p><div class=\"mdmf-actions\">");
		pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DELIVERY_CHECK\"")
			.append(in != null && in.canCheckDelivery ? "" : " disabled=\"disabled\"").append(">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.deliver.check")))
			.append("</button>");
		// audit F-40: the runbook link was a dead "#"; it opens the user guide's delivery section
		if (in != null && !in.deliveryDocHref.isEmpty())
		{
			pOut.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(WizardPage.esc(in.deliveryDocHref)).append("\" target=\"_blank\" rel=\"noopener\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.deliver.doc"))).append("</a>");
		}
		pOut.append("</div>");
		if (pModel.deliveryCheckSummary != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-ok\">").append(WizardPage.esc(pModel.deliveryCheckSummary)).append("</div>");
		}
		pOut.append("</div>");

		pOut.append("<div class=\"mdmf-panel mdmf-prod-card\"><span class=\"mdmf-prod-name\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.follow.title")))
			.append("</span><p>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.follow.text"))).append("</p><div class=\"mdmf-actions\">");
		pOut.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(in == null || in.cockpitUrl.isEmpty() ? "#" : WizardPage.esc(in.cockpitUrl)).append("\" target=\"_top\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.follow.cockpit"))).append("</a>");
		pOut.append("<a class=\"mdmf-btn mdmf-btn-sm\" href=\"").append(in == null || in.recordLineageUrl.isEmpty() ? "#" : WizardPage.esc(in.recordLineageUrl)).append("\" target=\"_top\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.prod.follow.lineage"))).append("</a>");
		pOut.append("</div></div>");

		pOut.append("</div>");
	}

	private static void renderJournal(final StringBuilder pOut, final Locale pLocale, final WizardSummaryData.Output pS)
	{
		pOut.append("<div class=\"mdmf-panel mdmf-journal\"><span class=\"mdmf-eyebrow\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journal.title")))
			.append("</span>");
		if (pS.journal.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journal.none"))).append("</p>");
		}
		for (final WizardSummaryData.JournalRow row : pS.journal)
		{
			pOut.append("<div class=\"mdmf-journal-row\"><span>").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.step7.journal.step." + row.stepLabel)))
				.append("</span><span class=\"mdmf-mini-bar\"><i style=\"width:").append(row.barPct).append("%\"></i></span><span>").append(WizardPage.esc(row.timeText))
				.append("</span><span>").append(WizardPage.esc(row.rowsText)).append("</span></div>");
		}
		pOut.append("</div>");
	}

	// ------------------------------------------------------------------------------------------------ step-reset (2026-09-23)

	/**
	 * "Clean this step and the following ones" - a discreet button, administrators only, called right after the
	 * {@code mdmf-lead} paragraph of steps 3 (Landing), 4 (Staging), 5 (Master) and 6 (Matching), one call site
	 * per step, {@code pStage} = {@code "LANDING"}/{@code "STAGING"}/{@code "MASTER"}/{@code "MATCHING"}
	 * matching {@code project.StepResetPlan.Stage}. A click ({@code STEP_RESET_PLAN}) opens (in place, same
	 * step) a confirmation block listing the plan {@code ebx.StepResetter.plan} returned: generated objects by
	 * disposition, the factory rows that would be deleted, the pipeline steps that would move back to {@code
	 * RESET}, and any refusal - with a "type the project code" field and a "Confirm cleanup" button ({@code
	 * STEP_RESET_CONFIRM}), same visual language as {@code ProjectDeletePage}'s own confirmation screen (not
	 * reused directly: that page is a full standalone screen with its own submit button, this is one fold inside
	 * a step panel that already has its own).
	 */
	static void renderStepReset(final StringBuilder pOut, final Model pModel, final Locale pLocale, final String pStage)
	{
		if (!pModel.administrator)
		{
			return;
		}
		final boolean open = pModel.stepReset != null && pStage.equals(pModel.stepReset.stage);
		// UX lot U4 (2026-10-13): called at the END of the step (never above its content), a secondary red-outlined button with a one-line warning
		pOut.append("<p class=\"mdmf-step-reset\">");
		if (!open)
		{
			pOut.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-action=\"STEP_RESET_PLAN\" data-mdmf-reset-stage=\"")
				.append(WizardPage.esc(pStage)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.open"))).append("</button> <span class=\"mdmf-hint\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.warning"))).append("</span>");
			pOut.append("</p>");
			return;
		}
		pOut.append("</p>");
		final StepResetView view = pModel.stepReset;
		pOut.append("<div class=\"mdmf-panel mdmf-step-reset-confirm\"><h3 class=\"mdmf-subtitle\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.title", pStage))).append("</h3>");
		if (view.done)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-").append(view.resultOk ? "ok" : "bad").append("\">").append(WizardPage.esc(view.resultMessage))
				.append("</div></div>");
			return;
		}
		// volume-operations lot 8b (2026-09-28, task 3): the cleanup now runs as a background job - no form, no
		// self-poll of its own, the generic jobs banner (renderJobsBanner, data-mdmf-jobs-active driven by
		// pModel.stepResetRunRunning below) re-renders the whole page once it ends, landing on the done/refusal
		// branch above via ProjectWizardUserService's own JOBS_POLL handling.
		if (view.running)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-info\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.running", pStage)))
				.append("</div></div>");
			return;
		}
		if (view.refusal != null)
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-bad\">").append(WizardPage.esc(view.refusal)).append("</div>");
		}
		else
		{
			pOut.append("<div class=\"mdmf-notice mdmf-notice-bad\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.irreversible")))
				.append("</div>");
		}
		if (!view.items.isEmpty())
		{
			pOut.append("<ul class=\"mdmf-delete-items\">");
			for (final StepResetItem item : view.items)
			{
				final String cls = "DELETE".equals(item.disposition) ? "mdmf-delete-item" : "mdmf-delete-item mdmf-delete-item-ineligible";
				pOut.append("<li class=\"").append(cls).append("\"><span class=\"mdmf-delete-item-name\">").append(WizardPage.esc(item.name)).append("</span>")
					.append(" <span class=\"mdmf-chip mdmf-chip-muted\">").append(WizardPage.esc(item.kind)).append("</span>")
					.append(" <span class=\"mdmf-chip mdmf-chip-").append("DELETE".equals(item.disposition) ? "warn" : "muted").append("\">")
					.append(WizardPage.esc(item.disposition)).append("</span>");
				if (!item.detail.isEmpty())
				{
					pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(item.detail)).append("</p>");
				}
				if (!item.reason.isEmpty())
				{
					pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(item.reason)).append("</p>");
				}
				pOut.append("</li>");
			}
			pOut.append("</ul>");
		}
		if (!view.rowCounts.isEmpty())
		{
			pOut.append("<div class=\"mdmf-table-wrap\"><table class=\"mdmf-table\"><thead><tr><th>")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.rows.table"))).append("</th><th class=\"num\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.rows.count"))).append("</th></tr></thead><tbody>");
			for (final Map.Entry<String, Integer> entry : view.rowCounts.entrySet())
			{
				pOut.append("<tr><td>").append(WizardPage.esc(entry.getKey())).append("</td><td class=\"num\">").append(entry.getValue()).append("</td></tr>");
			}
			pOut.append("</tbody></table></div>");
		}
		if (!view.stepNames.isEmpty())
		{
			pOut.append("<p class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.steps", WizardPage.join(view.stepNames))))
				.append("</p>");
		}
		if (view.refusal == null)
		{
			final String inputId = "mdmfResetConfirmInput";
			pOut.append("<div class=\"mdmf-field\"><label for=\"").append(inputId).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.confirm.label",
					pModel.snapshot.project == null ? "" : pModel.snapshot.project.code)))
				.append("</label><input type=\"text\" id=\"").append(inputId).append("\" name=\"").append(inputId).append("\" value=\"\" autocomplete=\"off\"/></div>");
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-danger\" data-mdmf-action=\"STEP_RESET_CONFIRM\" data-mdmf-reset-stage=\"")
				.append(WizardPage.esc(pStage)).append("\" data-mdmf-confirm-input=\"").append(inputId).append("\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.confirm.button"))).append("</button>");
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STEP_RESET_CANCEL\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.cancel"))).append("</button></div>");
		}
		else
		{
			pOut.append("<div class=\"mdmf-actions\"><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"STEP_RESET_CANCEL\">")
				.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.stepReset.close"))).append("</button></div>");
		}
		pOut.append("</div>");
	}

	// ------------------------------------------------------------------------------------------------------- helpers

	private static void renderDecideCell(final StringBuilder pOut, final Locale pLocale, final String pNature, final String pId, final String pStatus)
	{
		pOut.append("<td class=\"mdmf-decide\">");
		WizardPage.renderInlineDecide(pOut, pLocale, pNature, pId, pStatus);
		pOut.append("</td>");
	}

	/** Every non-{@code MATCH}, non-{@code SCRIPT_CONSTRAINT} caller (RELATION/ENTITY/ATTRIBUTE/MAPPING): never stale, never the extra action set - delegates to {@link #renderDecideActions}. */
	private static void renderInlineDecide(final StringBuilder pOut, final Locale pLocale, final String pNature, final String pId, final String pStatus)
	{
		WizardPage.renderDecideActions(pOut, pLocale, pNature, pId, pStatus, false, null);
	}

	/**
	 * Inline-reviews lot (2026-09-23): the full action set a line's status/nature allows - accept, reject (or,
	 * for a {@code MATCH} "No matching for this entity", a toggle opening its own confirm/comment panel),
	 * "Propose another" (a {@code MATCH}-only toggle opening its own comment panel), apply/measure again/
	 * re-apply/remove ({@code SCRIPT_CONSTRAINT} only) - exactly {@code ReviewPage.renderRow}'s own action
	 * block and {@link #renderMatchInlinePanels}, reused rather than reinvented (task brief: "reuse the Review
	 * screen's own handling, do not reinvent") so the wizard and the Proposal review screen can never drift on
	 * which button a given status/nature offers. {@code pStale}/{@code pStaleReason} are only ever meaningful
	 * for an {@code APPLIED SCRIPT_CONSTRAINT} line ({@code capability.ConstraintStaleness}, read once by
	 * {@code ProposalReviews.lines} and carried on the {@link ReviewLine} itself - never recomputed here).
	 */
	private static void renderDecideActions(final StringBuilder pOut, final Locale pLocale, final String pNature, final String pId, final String pStatus,
		final boolean pStale, final String pStaleReason)
	{
		WizardPage.renderDecideActions(pOut, pLocale, pNature, pId, pStatus, pStale, pStaleReason, null);
	}

	/**
	 * Apply-visibility lot (2026-09-23): {@code pApplyBlockedReasonKey}, non-null only for a {@code
	 * SCRIPT_CONSTRAINT} line whose zone's model is not generated yet ({@code WizardRules
	 * #capabilityApplyGate}) - Apply/Re-apply are then shown as a disabled-looking hint naming why instead of a
	 * live button that would only fail the exact same way every time ("Pourquoi le bouton Apply des script
	 * constraints ne s'enleve jamais ?"). Every other caller keeps using the 7-argument overload above, which
	 * passes null - never blocked.
	 */
	private static void renderDecideActions(final StringBuilder pOut, final Locale pLocale, final String pNature, final String pId, final String pStatus,
		final boolean pStale, final String pStaleReason, final String pApplyBlockedReasonKey)
	{
		pOut.append("<span class=\"mdmf-decide-status mdmf-chip ").append(WizardPage.statusChipClass(pStatus)).append("\">")
			.append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.status." + pStatus))).append("</span>");
		if (pStale)
		{
			pOut.append(" <span class=\"mdmf-chip mdmf-chip-target\" title=\"").append(WizardPage.esc(pStaleReason)).append("\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.capability.stale"))).append("</span>");
		}
		// exactly the actions this status still allows - shared with the review screen (DecisionActions) so the
		// two screens cannot drift; product owner, 2026-09-21, live screenshot: "the Accepted tag seems to be
		// set, but I can still Accept them" (every non-APPLIED row used to draw both buttons regardless of status)
		final DecisionActions.Actions actions = DecisionActions.forStatus(pStatus, pNature, pStale);
		if (actions.accept)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"DECIDE_ACCEPT\" data-mdmf-nature=\"").append(pNature)
				.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.accept"))).append("</button>");
		}
		// reject/repropose lot equivalent (ReviewPage.renderRow): a MATCH "No matching for this entity" never
		// submits directly - it opens its own inline confirmation panel (renderMatchInlinePanels), toggled by
		// data-mdmf-toggle, never data-mdmf-action, so a click here can never bypass the checkbox confirmation.
		if (actions.reject && actions.confirmReject)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"mdmf-match-noMatch-").append(WizardPage.esc(pId))
				.append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching"))).append("</button>");
		}
		else if (actions.reject)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"").append(pNature)
				.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(WizardMessages.get(pLocale, "wizard.reject"))).append("</button>");
		}
		if (actions.repropose)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"mdmf-match-repropose-").append(WizardPage.esc(pId)).append("\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.repropose"))).append("</button>");
		}
		if (actions.apply)
		{
			if (pApplyBlockedReasonKey != null)
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pApplyBlockedReasonKey))).append("</span>");
			}
			else
			{
				pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"APPLY\" data-mdmf-nature=\"").append(pNature)
					.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.apply"))).append("</button>");
			}
		}
		if (actions.measureAgain)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"MEASURE\" data-mdmf-nature=\"").append(pNature).append("\" data-mdmf-id=\"")
				.append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.measureAgain"))).append("</button>");
		}
		if (actions.reapply)
		{
			if (pApplyBlockedReasonKey != null)
			{
				pOut.append(" <span class=\"mdmf-hint\">").append(WizardPage.esc(WizardMessages.get(pLocale, pApplyBlockedReasonKey))).append("</span>");
			}
			else
			{
				pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"REAPPLY\" data-mdmf-nature=\"").append(pNature)
					.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.reapply"))).append("</button>");
			}
		}
		if (actions.remove)
		{
			pOut.append(" <button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"REMOVE\" data-mdmf-nature=\"").append(pNature)
				.append("\" data-mdmf-id=\"").append(WizardPage.esc(pId)).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.remove"))).append("</button>");
		}
		if (ReviewLine.MATCH.equals(pNature))
		{
			WizardPage.renderMatchInlinePanels(pOut, pLocale, pNature, pId, actions);
		}
	}

	/**
	 * Inline-reviews lot (2026-09-23): the two {@code MATCH}-only inline panels, ported from {@code
	 * ReviewPage.renderMatchInlinePanels} verbatim (same ids, same {@code data-mdmf-comment-from}/{@code
	 * data-mdmf-requires-checkbox} contract {@code wizard.js} now also implements - see that file) - both
	 * hidden until their own toggle button (rendered above, in {@link #renderDecideActions}) reveals them, so
	 * a reviewer never has to leave the wizard for "No matching for this entity" or "Propose another"
	 * (the product owner, 2026-09-23: "l'acces aux reviews devrait se faire dans l'ecran, sans avoir a le quitter").
	 */
	private static void renderMatchInlinePanels(final StringBuilder pOut, final Locale pLocale, final String pNature, final String pId,
		final DecisionActions.Actions pActions)
	{
		final String id = WizardPage.esc(pId);
		if (pActions.repropose)
		{
			final String panelId = "mdmf-match-repropose-" + id;
			final String textareaId = panelId + "-text";
			pOut.append("<div class=\"mdmf-match-inline\" id=\"").append(panelId).append("\" style=\"display:none\">")
				.append("<textarea class=\"mdmf-match-comment-input\" id=\"").append(textareaId).append("\" placeholder=\"")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.repropose.placeholder"))).append("\"></textarea>")
				.append("<div class=\"mdmf-actions\">")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-primary\" data-mdmf-action=\"REPROPOSE\" data-mdmf-nature=\"").append(pNature)
				.append("\" data-mdmf-id=\"").append(id).append("\" data-mdmf-comment-from=\"").append(textareaId).append("\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.repropose.send"))).append("</button>")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"").append(panelId).append("\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.comment.cancel"))).append("</button></div></div>");
		}
		if (pActions.reject && pActions.confirmReject)
		{
			final String panelId = "mdmf-match-noMatch-" + id;
			final String textareaId = panelId + "-text";
			final String checkboxId = panelId + "-confirm";
			pOut.append("<div class=\"mdmf-match-inline\" id=\"").append(panelId).append("\" style=\"display:none\">")
				.append("<label class=\"mdmf-hint\"><input type=\"checkbox\" id=\"").append(checkboxId).append("\"/> ")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.confirm"))).append("</label>")
				.append("<textarea class=\"mdmf-match-comment-input\" id=\"").append(textareaId).append("\" placeholder=\"")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.placeholder"))).append("\"></textarea>")
				.append("<div class=\"mdmf-actions\">")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-action=\"DECIDE_REJECT\" data-mdmf-nature=\"").append(pNature)
				.append("\" data-mdmf-id=\"").append(id).append("\" data-mdmf-comment-from=\"").append(textareaId).append("\" data-mdmf-requires-checkbox=\"")
				.append(checkboxId).append("\">").append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.noMatching.send"))).append("</button>")
				.append("<button type=\"button\" class=\"mdmf-btn mdmf-btn-sm\" data-mdmf-toggle=\"").append(panelId).append("\">")
				.append(WizardPage.esc(ReviewMessages.get(pLocale, "review.match.comment.cancel"))).append("</button></div></div>");
		}
	}

	private static String statusChipClass(final String pStatus)
	{
		if ("ACCEPTED".equals(pStatus) || "APPLIED".equals(pStatus))
		{
			return "mdmf-chip-real";
		}
		if ("REJECTED".equals(pStatus))
		{
			return "mdmf-chip-bad";
		}
		return "mdmf-chip-target";
	}

	private static String fieldError(final Model pModel, final Locale pLocale, final String pField)
	{
		for (final WizardRules.Problem problem : pModel.problems)
		{
			if (pField.equals(problem.field))
			{
				return "<p class=\"mdmf-hint\" style=\"color:var(--bad)\">" + WizardPage.esc(WizardMessages.get(pLocale, problem.key)) + "</p>";
			}
		}
		return "";
	}

	private static boolean hasFieldError(final Model pModel, final String pField)
	{
		for (final WizardRules.Problem problem : pModel.problems)
		{
			if (pField.equals(problem.field))
			{
				return true;
			}
		}
		return false;
	}

	private static String hiddenAttr(final Model pModel, final int pStep)
	{
		return pStep == pModel.currentStep ? "" : " hidden=\"hidden\"";
	}

	static String orDash(final String pValue)
	{
		return pValue == null || pValue.isEmpty() ? "-" : pValue;
	}

	private static String join(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append(", ");
			}
			out.append(value);
		}
		return out.toString();
	}

	/** Same idea as {@link #join}, "+"-joined - the convention every key column list of this lot renders as (chips, {@code ebx.LandingRun#keySummary}). */
	private static String joinPlus(final List<String> pValues)
	{
		final StringBuilder out = new StringBuilder();
		for (final String value : pValues)
		{
			if (out.length() > 0)
			{
				out.append('+');
			}
			out.append(value);
		}
		return out.toString();
	}

	/**
	 * Coordinator follow-up round 3 (2026-09-24): "singular/plural via the bundle (one key per form)" - the
	 * ONE place a step-3 count picks between an {@code .one} and an {@code .other} message key, English rule
	 * (singular only at exactly 1, plural everywhere else including 0) - matches every existing {@code
	 * emptyOnRow}/{@code emptyOnRows} pair's own convention, just factored out instead of repeated inline.
	 * Delegates to {@link WizardMessages#plural} (public there, since round 3's other affected classes -
	 * {@code StagingLoadGridReader}, {@code MasterBuildGridReader} - live outside this package).
	 */
	static String plural(final Locale pLocale, final long pCount, final String pOneKey, final String pOtherKey)
	{
		return WizardMessages.plural(pLocale, pCount, pOneKey, pOtherKey);
	}

	/**
	 * wizard-visual-pass ROUND 3 (2026-09-24): overload for a message that carries other placeholders
	 * besides the count itself ({@code {0}} is always the count, {@code pExtraArgs} fill {@code {1}},
	 * {@code {2}}...) - same {@code .one}/{@code .other} convention as the 4-arg form above.
	 */
	static String plural(final Locale pLocale, final long pCount, final String pOneKey, final String pOtherKey, final Object[] pExtraArgs)
	{
		return WizardMessages.plural(pLocale, pCount, pOneKey, pOtherKey, pExtraArgs);
	}

	/**
	 * wizard-visual-pass ROUND 3 (2026-09-24): "N noun" with the noun's own singular/plural form
	 * ({@code pUnitKey + ".one"}/{@code ".other"}) - the building block for a sentence that names SEVERAL
	 * different counted nouns (a single {@link #plural} pair cannot cover more than one), same idea as
	 * {@code ProjectWizardUserService#countWords}. Never a bare "(s)". Delegates to {@link
	 * WizardMessages#countWords}.
	 */
	static String countWords(final Locale pLocale, final long pCount, final String pUnitKey)
	{
		return WizardMessages.countWords(pLocale, pCount, pUnitKey);
	}

	static String esc(final String pText)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;").replace("'", "&#39;");
	}
}
