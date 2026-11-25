package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.delivery.TargetRule;
import com.on.ebx.factory.landing.LlmConsent;
import com.on.ebx.factory.ebx.AccelWizard;
import com.on.ebx.factory.ebx.CapabilityApplyRun;
import com.on.ebx.factory.ebx.CapabilityLlmSuggester;
import com.on.ebx.factory.ebx.CapabilitySuggestLlmRun;
import com.on.ebx.factory.ebx.CatalogReader;
import com.on.ebx.factory.ebx.DeducedScanCatalog;
import com.on.ebx.factory.ebx.DeducedScanConfirm;
import com.on.ebx.factory.ebx.DeducedScanEntry;
import com.on.ebx.factory.ebx.DeducedScanRun;
import com.on.ebx.factory.ebx.DerivationParameterSettings;
import com.on.ebx.factory.wizard.DeducedScanProgress;
import com.on.ebx.factory.ebx.DataProductPublication;
import com.on.ebx.factory.ebx.DistributionConnections;
import com.on.ebx.factory.ebx.DistributionStepFacts;
import com.on.ebx.factory.ebx.DistributionReader;
import com.on.ebx.factory.ebx.DistributionRunner;
import com.on.ebx.factory.ebx.DistributionSendRun;
import com.on.ebx.factory.distribution.DistributionArmingGate;
import com.on.ebx.factory.ebx.GoldenRecordsCache;
import com.on.ebx.factory.ebx.GoldenRecordsReader;
import com.on.ebx.factory.ebx.Intake;
import com.on.ebx.factory.wizard.DistributionTexts;
import com.on.ebx.factory.wizard.IntakeScreens;
import com.on.ebx.factory.wizard.MasterMain;
import com.on.ebx.factory.ebx.PipelineWorkflowLauncher;
import com.on.ebx.factory.ebx.ProjectMasterEntities;
import com.on.ebx.factory.ebx.LandingFlows;
import com.on.ebx.factory.ebx.LandingGenerator;
import com.on.ebx.factory.ebx.KeyStudy;
import com.on.ebx.factory.ebx.KeyStudyRun;
import com.on.ebx.factory.ebx.LandingKeyChange;
import com.on.ebx.factory.ebx.LandingKeyChangeRun;
import com.on.ebx.factory.ebx.LandingPlanner;
import com.on.ebx.factory.ebx.LandingPreview;
import com.on.ebx.factory.ebx.FactoryJobQueue;
import com.on.ebx.factory.ebx.LandingRun;
import com.on.ebx.factory.ebx.LandingSourceRun;
import com.on.ebx.factory.ebx.SourceRescanRun;
import com.on.ebx.factory.ebx.OnboardStepRun;
import com.on.ebx.factory.landing.LandingRunProgress;
import com.on.ebx.factory.ebx.MasterBuildGridReader;
import com.on.ebx.factory.ebx.MasterBuildRun;
import com.on.ebx.factory.ebx.MatchPlanLlmSuggester;
import com.on.ebx.factory.ebx.MatchPlanSimulationRunner;
import com.on.ebx.factory.ebx.MatchingActivationGuard;
import com.on.ebx.factory.ebx.MatchingConfigureRun;
import com.on.ebx.factory.ebx.MatchingConfigurator;
import com.on.ebx.factory.ebx.MatchingResultsBuilder;
import com.on.ebx.factory.ebx.MatchingResultsRun;
import com.on.ebx.factory.ebx.MatchingResultsStore;
import com.on.ebx.factory.ebx.MatchingRunRun;
import com.on.ebx.factory.ebx.RecordLineageReader;
import com.on.ebx.factory.matching.MatchResultsSnapshot;
import com.on.ebx.factory.matching.MatchResultsView;
import com.on.ebx.factory.ebx.ProjectOrigin;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.ebx.StagingBulkDecideRun;
import com.on.ebx.factory.ebx.QualitySuggestLlmRun;
import com.on.ebx.factory.ebx.SampleSourceImporter;
import com.on.ebx.factory.ebx.SourceOnboarder;
import com.on.ebx.factory.ebx.SourcesRun;
import com.on.ebx.factory.ebx.StagingSuggestLlmRun;
import com.on.ebx.factory.ebx.SuggestRulesRun;
import com.on.ebx.factory.ebx.DistributionSetupRun;
import com.on.ebx.factory.ebx.IntakeSwitchRun;
import com.on.ebx.factory.ebx.WizardActionRuns;
import com.on.ebx.factory.ebx.MatchingSuggestLlmRun;
import com.on.ebx.factory.ebx.StagingProposalRun;
import com.on.ebx.factory.staging.ProposalProgress;
import com.on.ebx.factory.ebx.StagingLoadGridReader;
import com.on.ebx.factory.ebx.StagingLoadRun;
import com.on.ebx.factory.staging.RunProgress;
import com.on.ebx.factory.ebx.StepResetter;
import com.on.ebx.factory.ebx.StepResetRun;
import com.on.ebx.factory.ebx.WizardData;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.review.MatchPlanView;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewRules;
import com.on.ebx.factory.wizard.ApplicationCodes;
import com.on.ebx.factory.wizard.ApplicationStages;
import com.on.ebx.factory.wizard.AddApplicationOptions;
import com.on.ebx.factory.wizard.NewTableSelection;
import com.on.ebx.factory.wizard.SampleFileRules;
import com.on.ebx.factory.wizard.SourceOnboarding;
import com.on.ebx.factory.wizard.LandingWalk;
import com.on.ebx.factory.wizard.SourceTableList;
import com.on.ebx.factory.wizard.SourcesProgress;
import com.on.ebx.factory.wizard.MatchingResultRow;
import com.on.ebx.factory.wizard.WizardPage;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.on.ebx.factory.wizard.WizardSummaryData;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * The "Assistant de projet" wizard (PLAN.md screen 1; mockup screen 2, 7 steps): guided path over the
 * same {@code Project}/{@code ProjectSource}/{@code LandingTable}/{@code LandingColumn}/
 * {@code LandingRelation}/{@code StagingEntity}/{@code StagingAttribute}/{@code AttributeMapping} rows the
 * tables already hold - never a separate state (task brief). Launched from the {@code Project} table view
 * ("New project (wizard)"), or on a selected {@code Project} record to resume it.
 * <p>
 * Same posted-form technique as {@link ProposalReviewUserService} (one real hidden submit button, every
 * other control a {@code type="button"} with {@code data-mdmf-*} attributes read by the page's own
 * script, {@code wizard.js}) combined with {@code ProductWizardPage}'s "every step rendered, one shown"
 * layout: since {@code setupObjectContext} runs fresh on every single HTTP request (CLAUDE.md §9), every
 * piece of state that must survive a postback (which step is open, the in-progress "add a source" form,
 * the bulk-accept threshold) is read back from the posted hidden fields at the top of {@link #handle},
 * never assumed to still be sitting on a Java field from a previous request - the same rule
 * {@link ProposalReviewUserService#decide} already follows for its filter fields.
 * <p>
 * Every write here runs with the CALLING session's own rights: {@link WizardData}'s writes and
 * {@link ProposalReviews#decide} take the session explicitly, and the administrator check for the
 * agent-account actions (generate landing, refine staging with the language model) is re-checked in
 * {@link #handle} itself, not only by disabling the button - a forged request must still be refused, the
 * same defence {@code FactoryConnectionUserService#test} and {@code FactoryResource} already apply.
 */
public final class ProjectWizardUserService implements UserService<TableViewEntitySelection>
{
	static final String SUBMIT_ID = "mdmfWizSubmit";
	/** Quality-block-bulk lot (2026-09-23): the natures the "Accept/Reject all quality proposals of this entity" buttons sweep. */
	private static final Set<String> QUALITY_BLOCK_NATURES = new HashSet<String>(java.util.Arrays.asList(ReviewLine.MAPPING_STEP, ReviewLine.QUALITY));
	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): the "N proposal(s) waiting for review" header
	 * ({@code WizardPage#renderWaitingForReviewHeader}) named cleaning steps among what was waiting but gave
	 * no control to decide them from there - the per-entity bulk-accept/the project-wide one are both behind
	 * the "Advanced" disclosure, several scrolls away. Project-wide, {@code MAPPING_STEP} only (never the
	 * other natures {@link #QUALITY_BLOCK_NATURES} already covers) - see {@code WizardPage
	 * #renderWaitingForReviewHeader}'s own new button.
	 */
	private static final Set<String> CLEANING_STEP_NATURES = java.util.Collections.singleton(ReviewLine.MAPPING_STEP);
	/** Distribution-wizard lot (2026-09-24): builds the JSON bodies {@code DistributionReader.upsertTarget}/{@code DataProductPublication.plan}/{@code .create} take. */
	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();

	Adaptation factory;
	Locale locale = Locale.ENGLISH;
	Session session;
	/**
	 * Second permissions pass (2026-10-14): null when the user may open the wizard on this dataset (dataspace, dataset, {@code Project} table and the
	 * wizard's service permission on it); otherwise the refusal the pane shows instead of the wizard.
	 */
	String closed;
	private com.on.ebx.factory.ebx.UserView userView;

	/** The user's view of the data (records and fields their EBX permissions show), what every screen read of this wizard goes through. */
	com.on.ebx.factory.ebx.UserView view()
	{
		if (this.userView == null || this.userView.session() != this.session)
		{
			this.userView = com.on.ebx.factory.ebx.UserView.of(this.session);
		}
		return this.userView;
	}

	/** A pure read of a shared reader with the user's view ({@code UserView#scoped}): only around reads, never around a write, a job or a call. */
	<T> T asUser(final java.util.function.Supplier<T> pRead)
	{
		return com.on.ebx.factory.ebx.UserView.scoped(this.view(), pRead);
	}

	/** {@link #asUser} for a read that throws {@link OperationException}. */
	<T> T asUserRead(final com.on.ebx.factory.ebx.UserView.Read<T> pRead) throws OperationException
	{
		return com.on.ebx.factory.ebx.UserView.scopedRead(this.view(), pRead);
	}

	/**
	 * Second permissions pass (2026-10-14): {@code mdmfSourceId} and {@code mdmfTableId}, when posted, name a {@code ProjectSource} / {@code LandingTable}
	 * the user may see and that belongs to the current project (the table through its source); otherwise the posted id is dropped.
	 */
	private void dropForeignPostedRows(final Posted pPosted)
	{
		final com.on.ebx.factory.ebx.UserView view = this.view();
		final String project = this.projectId == null ? "" : this.projectId;
		if (!pPosted.sourceId.isEmpty())
		{
			final Adaptation source = view.lookup(this.factory.getTable(FactoryPaths.PROJECT_SOURCE), pPosted.sourceId);
			if (source == null || !project.equals(view.text(source, FactoryPaths.ProjectSource.PROJECT)))
			{
				pPosted.sourceId = "";
				this.activeSourceId = "";
			}
		}
		if (!pPosted.tableId.isEmpty())
		{
			final Adaptation table = view.lookup(this.factory.getTable(FactoryPaths.LANDING_TABLE), pPosted.tableId);
			final Adaptation source = table == null ? null
				: view.lookup(this.factory.getTable(FactoryPaths.PROJECT_SOURCE), view.text(table, FactoryPaths.LandingTable.PROJECT_SOURCE));
			if (source == null || !project.equals(view.text(source, FactoryPaths.ProjectSource.PROJECT)))
			{
				pPosted.tableId = "";
			}
		}
	}

	/** The project record the wizard is on, null when none, unknown or hidden from the user (answered alike). */
	Adaptation openProject()
	{
		return this.projectId == null || this.projectId.isEmpty() ? null : ProjectAccess.openProject(this.session, this.factory, this.projectId);
	}

	/** Null: a brand new project, not yet created. */
	String projectId;
	/** True when launched on a selected {@code Project} record: no project switcher is offered. */
	boolean projectLocked;
	/**
	 * Delivery lot 2 ({@code docs/DELIVERY.md} sec 6.2): true when the wizard was reached with no project
	 * selected (a "New project (wizard)" toolbar launch, {@link #projectLocked} false) and this instance's
	 * {@code FactorySettings.role} is {@code TARGET}. The toolbar button itself cannot be hidden per role (a
	 * static XML entry, CLAUDE.md sec 8's own "a referenced toolbar" note), so this shows the refusal instead
	 * of the step-1 form - {@link WizardData#saveProject} refuses the same way server-side regardless, which
	 * is the real gate; this is presentation only.
	 */
	boolean blockedNewProject;

	String notice;
	String noticeKind = "ok";
	/** Audit F-43: how many finished tasks the Recent tasks list shows. */
	static final int RECENT_TASKS = 10;
	/** Audit F-40: the user guide's address for this person, resolved at each rendering ({@link #writePage}); empty before the first. */
	String helpUrl = "";
	/** Audit F-44: the raw colours of the perspective that displays the wizard, read at each rendering; handed to the user guide. */
	Map<String, String> theme = java.util.Collections.emptyMap();

	/** Audit F-40: the user guide page of a locale - French for French, English otherwise. Pure. */
	static String userGuide(final Locale pLocale)
	{
		return "factory/user-guide-" + (pLocale != null && "fr".equals(pLocale.getLanguage()) ? "fr" : "en") + ".html";
	}
	/** Audit F-39: the engine's raw text behind {@link #notice}, kept only while the notice is still the one it explains ({@link #noticeDetailFor}). */
	String noticeDetail;
	String noticeDetailFor;
	final List<WizardRules.Problem> problems = new ArrayList<WizardRules.Problem>();

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		final TableViewEntitySelection selection = pContext.getEntitySelection();
		this.factory = selection.getDataset();
		this.session = pContext.getSession();
		final AdaptationTable table = selection.getTable();
		final List<Adaptation> selected = new ArrayList<Adaptation>();
		try
		{
			final RequestResult rows = selection.getSelectedRecords().execute();
			try
			{
				for (Adaptation record = rows.nextAdaptation(); record != null && selected.size() <= 1; record = rows.nextAdaptation())
				{
					selected.add(record);
				}
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			// launched from the toolbar/perspective with nothing selected: a brand new project
		}
		if (selected.size() == 1 && table != null && FactoryPaths.PROJECT.format().equals(table.getTablePath().format()))
		{
			// second permissions pass (2026-10-14): a selection can be forged in a URL like any key - a record the user cannot see is answered like
			// an unknown code (the wizard then starts a new project with the usual notice), never opened
			if (ProjectAccess.projectOpens(this.session, this.factory, selected.get(0)))
			{
				this.projectId = selected.get(0).getOccurrencePrimaryKey().format();
				this.projectLocked = true;
				return;
			}
			this.unknownProjectCode = selected.get(0).getOccurrencePrimaryKey().format();
		}
		// project explorer lot (2026-09-23): a card's "Wizard" link (FactoryLinks#wizardUrl) carries a
		// "project" launch parameter instead of an actual record selection - resolve it by PRIMARY KEY
		// (computePrimaryKey, never a string-built key - CLAUDE.md's own warning) rather than the XPath-predicate
		// lookup ProposalReviews.findProjectByCode uses, per the task brief for this lot. Tried only ONCE per
		// service instance (the same "already initialised on an earlier round trip" guard
		// RecordLineageUserService#setupObjectContext uses for its own launch parameter): setupObjectContext runs
		// again on every postback of this same dialog (class Javadoc), where there is no record selection either,
		// and the request may or may not still carry the original query string - re-resolving it every time would
		// risk clobbering a project the user has since navigated to/away from via the posted form.
		if (!this.urlProjectChecked)
		{
			this.urlProjectChecked = true;
			// fix/wizard-live-wave1 (2026-09-24, live bug item 2): an optional "step" launch parameter, read the
			// SAME one-time way as "project" - MappingMatrixLink/FeedDesignerLink's own "Back to the wizard" link
			// (FactoryLinks#wizardUrl(String,String,String,int)) sets it to WizardRules.STEP_STAGING so Close on
			// either screen re-opens the wizard back where the person left it, not step 1. Reachability is still
			// checked the normal way once the snapshot is known (model()'s existing "step not reachable" fallback
			// to suggestedStep), so an out-of-range or not-yet-reachable step never gets stuck.
			final String urlStep = AbstractRecordLineageUserService.param(pContext, "step");
			if (!urlStep.isEmpty())
			{
				try
				{
					final int step = Integer.parseInt(urlStep.trim());
					if (step >= 1 && step <= WizardRules.STEP_MAX)
					{
						this.currentStep = step;
					}
				}
				catch (final NumberFormatException ex)
				{
					// ignore a malformed value - keeps the default STEP_PROJECT
				}
			}
			final String urlProjectCode = AbstractRecordLineageUserService.param(pContext, "project");
			if (!urlProjectCode.isEmpty())
			{
				// second permissions pass (2026-10-14): with the user's own view - a project the user cannot see reads like an unknown code
				final AdaptationTable projects = this.factory.getTable(FactoryPaths.PROJECT);
				final Adaptation resolved = ProjectAccess.openProject(this.session, this.factory, projects.computePrimaryKey(new Object[] { urlProjectCode }).format());
				if (resolved != null)
				{
					this.projectId = resolved.getOccurrencePrimaryKey().format();
					this.projectLocked = true;
					return;
				}
				// falls through to the "new project" behaviour below, with a notice naming the unknown code
				this.unknownProjectCode = urlProjectCode;
			}
		}
		this.blockedNewProject = !TargetRule.projectCreationAllowed(ProjectOrigin.role(this.factory));
	}

	/** Guards {@link #setupObjectContext}'s one-time read of the {@code project} launch parameter - see its own comment. */
	boolean urlProjectChecked;
	/**
	 * fix/wizard-live-wave1 (2026-09-24, live bug item 11): true once {@link #handle} has processed a real form
	 * submit - see {@link #model()}'s own comment on why {@link #buildSummary} needs this to decide when it is
	 * safe to skip the extra step-7 reads.
	 */
	boolean postedOnce;
	/** Set by {@link #setupObjectContext} when the {@code project} launch parameter names a code that no longer
	 * resolves; surfaced as a warning notice on the very first render ({@link #write}). Null otherwise. */
	String unknownProjectCode;

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.title"));
		// second permissions pass (2026-10-14): the dataspace, the dataset, the Project table and the wizard's own service permission on it, read
		// with the user's session at each display - a launch by URL is checked like the toolbar button
		this.closed = ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (this.closed == null && !ProjectAccess.serviceOnTable(this.session, ProjectWizardServiceDeclaration.SERVICE_KEY, this.factory.getTable(FactoryPaths.PROJECT)))
		{
			this.closed = Messages.get(ProjectWizardUserService.class, this.locale, "screen.service.refused");
		}
		if (this.closed != null)
		{
			pConfigurator.setRightButtons(pConfigurator.newCloseButton());
			pConfigurator.setContent(new UserServicePane()
			{
				@Override
				public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
				{
					pWriter.add(ThemeTokens.styleTagFor(pPaneContext));
					FactoryHtml.writeClosed(pWriter, ProjectWizardUserService.this.closed);
				}
			});
			return;
		}
		if (this.blockedNewProject)
		{
			// no useful action: the wizard was reached to create a project this instance refuses to create
			// (see the field's own Javadoc) - a close button only, never the submit button
			pConfigurator.setRightButtons(pConfigurator.newCloseButton());
			pConfigurator.setContent(new UserServicePane()
			{
				@Override
				public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
				{
					pWriter.add(ThemeTokens.styleTagFor(pPaneContext));
					ProjectWizardUserService.this.writeBlocked(pWriter);
				}
			});
			return;
		}
		final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.title"), new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				ProjectWizardUserService.this.handle(pEventContext);
				return null;
			}
		});
		submit.setId(ProjectWizardUserService.SUBMIT_ID);
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setLeftButtons(submit);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				// audit F-44: the perspective's colours, written first; the user guide link carries them too
				ProjectWizardUserService.this.theme = ThemeTokens.read(ThemeTokens.perspective(pPaneContext));
				pWriter.add(ThemeTokens.styleTag(ProjectWizardUserService.this.theme));
				ProjectWizardUserService.this.write(pWriter);
			}
		});
	}

	/** Delivery lot 2: the whole content of the pane when {@link #blockedNewProject} is true - same stylesheet and notice markup as {@link #write}, no stepper, no form. */
	void writeBlocked(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<div class=\"mdmf mdmf-wizard\"><div class=\"mdmf-notice mdmf-notice-warn\" role=\"alert\">")
			.add(FactoryHtml.esc(TargetRule.refusal())).add("</div></div>");
		// project explorer lot, contextual return (2026-09-23): a blocked "new project" launch can still have
		// come from the explorer's own New-project link - Close should still go back there, not to the table
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session)));
	}

	// ------------------------------------------------------------------------------------------------ posted state

	/**
	 * Everything the page posts back on every submit, re-read fresh at the top of {@link #handle} - see the
	 * class Javadoc for why this cannot be Java instance state surviving across requests.
	 */
	private static final class Posted
	{
		int step;
		String projectId = "";
		String sourceId = "";
		/** trust-rank-edit lot (2026-09-23): the value of a source card's trust-rank input, -1 when the posted action carried none. */
		int trustRankEdit = -1;
		/** sources-landing lot SOURCES (2026-09-24): the Review screen's editable application code input - {@code SOURCE_SET_CODE}. */
		String sourceCodeEdit = "";
		/** Same lot: the connector id of a still-stuck application row's "Remove" button - {@code SOURCES_RUN_REMOVE}. */
		String removeConnectorId = "";
		String tableId = "";
		/** UX lot U2 (2026-10-13): {@code mdmfTableTicks} of "Keep these tables" - the ticks the person changed on the cards, {@code +id}/{@code -id} comma-joined ({@link SourceTableList#parseTicks}). */
		String tableTicks = "";
		/** Landing-walkthrough lot (2026-10-16): {@code mdmfWalkLlm} - "yes" or "no", the radio of the application page being validated; blank when the page showed none. */
		String walkLlm = "";
		/** Same lot: {@code mdmfId} of a walkthrough navigation button - a ProjectSource id or {@code LAUNCH}. */
		String walkTarget = "";
		String keyColumns = "";
		/** Volume wave 2, lot V2-E: {@code mdmfChangeColumn}/{@code mdmfChangeKind} of a table's change-column selector ({@code TABLE_SET_CHANGE_COLUMN}); blank column = "none, reload in full". */
		String changeColumn = "";
		String changeKind = "";
		String catalogSourceId = "";
		/** source-onboarding lot (2026-09-23): the connection picked in step 2's "Create a catalog source" combobox - {@code Posted.onboardConnector} per the task brief, mirrors {@code catalogSourceId}'s own convention. */
		String onboardConnector = "";
		/** sample-files lot (2026-09-23): the raw JSON text of the hidden {@code mdmfSampleFiles} textarea - {@code wizard.js} fills it right before submit, {@code SampleSourceImporter.parseFileInputs} parses it tolerantly. */
		String sampleFilesRaw = "";
		int bulkThreshold = 80;
		/**
		 * Threshold-left-out lot (2026-09-23): the per-entity "include below threshold" checkbox, synced by
		 * {@code wizard.js} into the shared hidden field {@code mdmfIncludeBelowThreshold} before submit -
		 * {@code false} for the two page-wide bulk buttons, which never offer the checkbox at all (their own
		 * {@code data-mdmf-action} carries no {@code data-mdmf-include-below-input}, so the field stays
		 * whatever it last was; {@link #handle} only reads it for the entity-scoped/quality-scoped actions).
		 */
		boolean includeBelowThreshold;
		/**
		 * Volume-guards lot (2026-09-28, task item 2): the "Recompute every row" checkbox next to step 4's build
		 * button(s) - {@code mdmfStagingScopeAll}, a plain checkbox posted directly with the shared wizard form
		 * (unlike {@link #includeBelowThreshold}, no {@code wizard.js} sync is needed: this is one project-wide
		 * checkbox, not one per row, so it posts with whichever action button is clicked, same as {@code
		 * mdmfDistScopeAll}/{@code mdmfDistApproval} on the distribution target form). Unchecked (false) by
		 * default: {@code StagingLoadRun#start}'s own default is scope {@code new}.
		 */
		boolean stagingScopeAll;
		/** Pipeline-buttons lot: which button of the two MATCHING_RUN ones was clicked - {@code incremental} (default) or {@code all}. */
		String matchScope = "incremental";
		/** wizard-matching-results lot (2026-09-24): the "Matching results" block's own posted state - see {@code wizard.js}'s own comment on {@code data-mdmf-match-*}. */
		String matchTab = "";
		/** Lot MT (2026-10-04): step 6's matching-trigger radio ({@code MATCHING_SET_TRIGGER}), raw. */
		String matchingTrigger = "";
		String matchPairId = "";
		String matchGolden = "";
		String matchSuspect = "";
		WizardRules.ProjectDraft projectDraft = new WizardRules.ProjectDraft();
		WizardRules.SourceDraft sourceDraft = new WizardRules.SourceDraft();

		// distribution-wizard lot (2026-09-24, step 7): the target setup form (Modes.dc.html) and the data
		// product form (DataProduct.dc.html) - see WizardPage#renderDistributionTargetSetup/renderDistributionProduct
		// for the exact field names these mirror.
		String distTargetId = "";
		String distConnector = "";
		String distOperation = "";
		String distMode = "ON_DEMAND";
		String distCron = "";
		boolean distScopeAll;
		boolean distApproval;
		boolean distIncludeCrosswalk;
		boolean distIncludePending;
		String distWatchedFields = "";
		String distSendScope = "changed";
		/**
		 * "Retire notices from the merge log" (SPEC-retire.md, UI lot): {@code mdmfDistRetireMode} - kept RAW
		 * (no default substituted here, unlike {@link #distMode}), since an empty value is exactly how {@link
		 * ProjectWizardUserService#saveDistributionTarget} tells "the retire block was not even rendered for
		 * this submit (a non-REALTIME target)" from "the block was rendered and NOTICE/TOMBSTONE/NONE was
		 * posted" - the same partial-write discipline {@code ebx.DistributionReader#upsertTarget} already
		 * applies server-side (only a body that actually names the field may change it).
		 */
		String distRetireMode = "";
		boolean distRetireUndoneMerges;
		/** Round 6 (task brief item C): the 7b "Table -&gt; topic" rows - {@code mdmfDistTopicTable}/{@code mdmfDistTopicValue}, zipped by posted index (see {@code #handle}'s own parsing). Empty for any connector that does not render these rows (every non-Kafka connector today), which {@code #saveDistributionTarget} reads as "leave tableDestinations untouched". */
		final Map<String, String> distTableDestinations = new LinkedHashMap<String, String>();
		String productName = "";
		String productCode = "";
		String productOwner = "";
		String productDomain = "";
		String productTarget = "EBX_ONLY";
		String productSchema = "";
		String productRefresh = "ON_DEMAND";
		boolean productListing;
		final Map<String, Boolean> productTableIncluded = new HashMap<String, Boolean>();
		/** Coordinator review (2026-09-24): the "+ Add assets from the catalogue" combo's own picked asset id - {@code DISTRIBUTION_PRODUCT_ADD_ASSET}. */
		String productAddAssetId = "";
	}

	private static Posted readPosted(final UserServiceEventContext pContext)
	{
		final Posted posted = new Posted();
		posted.step = ProjectWizardUserService.paramInt(pContext, "mdmfStep", WizardRules.STEP_PROJECT);
		posted.projectId = ProjectWizardUserService.param(pContext, "mdmfProjectId");
		posted.sourceId = ProjectWizardUserService.param(pContext, "mdmfSourceId");
		posted.trustRankEdit = ProjectWizardUserService.paramInt(pContext, "mdmfTrustRankEdit", -1);
		posted.sourceCodeEdit = ProjectWizardUserService.param(pContext, "mdmfSourceCodeEdit");
		posted.removeConnectorId = ProjectWizardUserService.param(pContext, "mdmfRemoveConnectorId");
		posted.tableId = ProjectWizardUserService.param(pContext, "mdmfTableId");
		posted.tableTicks = ProjectWizardUserService.param(pContext, "mdmfTableTicks");
		posted.walkLlm = ProjectWizardUserService.param(pContext, "mdmfWalkLlm");
		posted.walkTarget = ProjectWizardUserService.param(pContext, "mdmfId");
		posted.keyColumns = ProjectWizardUserService.param(pContext, "mdmfKeyColumns");
		posted.changeColumn = ProjectWizardUserService.param(pContext, "mdmfChangeColumn");
		posted.changeKind = ProjectWizardUserService.param(pContext, "mdmfChangeKind");
		posted.catalogSourceId = ProjectWizardUserService.param(pContext, "mdmfCatalogSourceId");
		posted.onboardConnector = ProjectWizardUserService.param(pContext, "mdmfOnboardConnector");
		posted.sampleFilesRaw = ProjectWizardUserService.param(pContext, "mdmfSampleFiles");
		posted.bulkThreshold = ProjectWizardUserService.paramInt(pContext, "mdmfBulkThreshold", 80);
		posted.includeBelowThreshold = ProjectWizardUserService.paramBool(pContext, "mdmfIncludeBelowThreshold");
		posted.stagingScopeAll = ProjectWizardUserService.paramBool(pContext, "mdmfStagingScopeAll");
		final String matchScope = ProjectWizardUserService.param(pContext, "mdmfMatchScope");
		posted.matchScope = matchScope.isEmpty() ? "incremental" : matchScope;
		posted.matchTab = ProjectWizardUserService.param(pContext, "mdmfMatchTab");
		posted.matchingTrigger = ProjectWizardUserService.param(pContext, "mdmfMatchingTriggerRadio");
		posted.matchPairId = ProjectWizardUserService.param(pContext, "mdmfMatchPairId");
		posted.matchGolden = ProjectWizardUserService.param(pContext, "mdmfMatchGolden");
		posted.matchSuspect = ProjectWizardUserService.param(pContext, "mdmfMatchSuspect");
		posted.projectDraft.code = ProjectWizardUserService.param(pContext, "mdmfCodeValue");
		posted.projectDraft.label = ProjectWizardUserService.param(pContext, "mdmfLabelValue");
		posted.projectDraft.description = ProjectWizardUserService.param(pContext, "mdmfDescriptionValue");
		final String mode = ProjectWizardUserService.param(pContext, "mdmfModeRadio");
		posted.projectDraft.mode = mode.isEmpty() ? "GREENFIELD" : mode;
		final String style = ProjectWizardUserService.param(pContext, "mdmfStyleRadio");
		posted.projectDraft.style = style.isEmpty() ? "CONSOLIDATION" : style;
		// wizard-llm-value-sharing lot (2026-09-28): raw, empty when not posted - WizardRules.projectProblems
		// validates, WizardData.saveProject lands an empty value on the defaults
		posted.projectDraft.llmValueSharing = ProjectWizardUserService.param(pContext, "mdmfLlmSharingRadio");
		posted.projectDraft.llmSampleSize = ProjectWizardUserService.param(pContext, "mdmfLlmSampleSize");
		posted.sourceDraft.catalogSourceId = posted.catalogSourceId;
		posted.sourceDraft.sourceSystem = ProjectWizardUserService.param(pContext, "mdmfSourceSystem");
		posted.sourceDraft.connector = ProjectWizardUserService.param(pContext, "mdmfConnector");
		posted.sourceDraft.trustRank = ProjectWizardUserService.paramInt(pContext, "mdmfTrustRank", 100);
		final String schemas = ProjectWizardUserService.param(pContext, "mdmfSchemas");
		if (!schemas.isEmpty())
		{
			for (final String schema : schemas.split(","))
			{
				if (!schema.trim().isEmpty())
				{
					posted.sourceDraft.schemas.add(schema.trim());
				}
			}
		}
		// distribution-wizard lot (2026-09-24): see Posted's own field comment.
		posted.distTargetId = ProjectWizardUserService.param(pContext, "mdmfDistTargetId");
		posted.distConnector = ProjectWizardUserService.param(pContext, "mdmfDistConnector");
		posted.distOperation = ProjectWizardUserService.param(pContext, "mdmfDistOperation");
		final String distMode = ProjectWizardUserService.param(pContext, "mdmfDistMode");
		posted.distMode = distMode.isEmpty() ? "ON_DEMAND" : distMode;
		posted.distCron = ProjectWizardUserService.param(pContext, "mdmfDistCron");
		posted.distScopeAll = ProjectWizardUserService.paramBool(pContext, "mdmfDistScopeAll");
		posted.distApproval = ProjectWizardUserService.paramBool(pContext, "mdmfDistApproval");
		posted.distIncludeCrosswalk = ProjectWizardUserService.paramBool(pContext, "mdmfDistIncludeCrosswalk");
		posted.distIncludePending = ProjectWizardUserService.paramBool(pContext, "mdmfDistIncludePending");
		posted.distWatchedFields = ProjectWizardUserService.param(pContext, "mdmfDistWatchedFields");
		// "retire notices from the merge log" (SPEC-retire.md, UI lot): raw, see Posted#distRetireMode's own comment.
		posted.distRetireMode = ProjectWizardUserService.param(pContext, "mdmfDistRetireMode");
		posted.distRetireUndoneMerges = ProjectWizardUserService.paramBool(pContext, "mdmfDistRetireUndoneMerges");
		final String distSendScope = ProjectWizardUserService.param(pContext, "mdmfDistSendScope");
		posted.distSendScope = distSendScope.isEmpty() ? "changed" : distSendScope;
		posted.productName = ProjectWizardUserService.param(pContext, "mdmfProductName");
		posted.productCode = ProjectWizardUserService.param(pContext, "mdmfProductCode");
		posted.productOwner = ProjectWizardUserService.param(pContext, "mdmfProductOwner");
		posted.productDomain = ProjectWizardUserService.param(pContext, "mdmfProductDomain");
		final String productTarget = ProjectWizardUserService.param(pContext, "mdmfProductTarget");
		posted.productTarget = productTarget.isEmpty() ? "EBX_ONLY" : productTarget;
		posted.productSchema = ProjectWizardUserService.param(pContext, "mdmfProductSchema");
		final String productRefresh = ProjectWizardUserService.param(pContext, "mdmfProductRefresh");
		posted.productRefresh = productRefresh.isEmpty() ? "ON_DEMAND" : productRefresh;
		posted.productListing = ProjectWizardUserService.paramBool(pContext, "mdmfProductListing");
		final String[] checkedTables = pContext.getParameterValues("mdmfProductTableChecked");
		if (checkedTables != null)
		{
			for (final String key : checkedTables)
			{
				if (key != null && !key.trim().isEmpty())
				{
					posted.productTableIncluded.put(key, Boolean.TRUE); // audit D-07: a catalog asset key is kept as posted, never trimmed
				}
			}
		}
		posted.productAddAssetId = ProjectWizardUserService.paramRaw(pContext, "mdmfProductAddAssetId"); // a catalog asset key (audit D-07), kept as posted
		// round 6 (task brief item C): the 7b "Table -> topic" rows - one mdmfDistTopicTable/mdmfDistTopicValue
		// pair per master table, posted in the SAME order they were rendered (WizardPage#renderDistributionTableDestinations) -
		// zipped by index rather than a single delimited field, the same posted-array shape mdmfProductTableChecked
		// already uses above.
		final String[] topicTables = pContext.getParameterValues("mdmfDistTopicTable");
		final String[] topicValues = pContext.getParameterValues("mdmfDistTopicValue");
		if (topicTables != null && topicValues != null)
		{
			for (int i = 0; i < topicTables.length && i < topicValues.length; i++)
			{
				final String tablePath = topicTables[i] == null ? "" : topicTables[i].trim();
				if (!tablePath.isEmpty())
				{
					posted.distTableDestinations.put(tablePath, topicValues[i] == null ? "" : topicValues[i].trim());
				}
			}
		}
		return posted;
	}

	// transient render state, filled by handle() from Posted + whatever the action needs to show next
	// fix/wizard-live-wave1 (2026-09-24, live bug: "reopening the wizard on a project in progress lands on step
	// 1"): 0 ("undecided"), not WizardRules.STEP_PROJECT - model() already reads a 0 here as "pick
	// WizardRules.suggestedStep(snapshot) instead" (the first step that still has work to do), but that branch
	// could never fire from a field default of 1 (STEP_PROJECT is a valid step number, indistinguishable from a
	// genuine choice). A real postback (handle()) or the "step" launch parameter (setupObjectContext) still
	// overwrites this with an explicit, non-zero step before model() ever reads it - only a first render with
	// neither ever sees the 0 default now.
	int currentStep;
	String activeSourceId = "";
	String pickedCatalogSourceId = "";
	final List<String> pickedCatalogSourceSchemas = new ArrayList<String>();
	/**
	 * source-onboarding lot (2026-09-23; revised after coordinator review the same day): step 2's "Create a
	 * catalog source" panel - kept on this instance across postbacks (task brief), refreshed ONLY by the
	 * seven {@code SOURCE_ONBOARD_*} actions below, never on an unrelated postback: both
	 * {@code ebx.SourceOnboarder#facts} and {@code #connections} are live MCP round trips, and every other
	 * wizard action (accepting a staging line, saving the project...) would otherwise pay for one of them on
	 * every single click. {@code model()} only ever COPIES these fields - it must never call
	 * {@code SourceOnboarder} itself (that was the coordinator's own review finding: a plain render used to
	 * call {@code connections()} unconditionally).
	 */
	boolean onboardOpen;
	final List<SourceOnboarding.ConnectionOption> onboardConnections = new ArrayList<SourceOnboarding.ConnectionOption>();
	/** Raw operational text when the last {@code connections()} read failed (an {@code OperationException}) - shown as-is, never localized, same convention as {@link #previewError}. */
	String onboardConnectionsError;
	String onboardConnectorId = "";
	/** The picked connector's own label (or id when it has none), for the checklist header - {@code ebx.SourceOnboarder.Facts} is not itself carried on this instance. */
	String onboardConnectorDisplay = "";
	SourceOnboarding.Diagnosis onboardDiagnosis;
	/** UX lot U2 (2026-10-13): whether the single "Add an application" list was opened (its connections loaded) - distinct from {@link #onboardOpen}, the step-by-step panel's own flag. */
	boolean addAppOpen;
	/** The last {@code connectors_sync} outcome text after {@code SOURCE_ONBOARD_SCAN} - null otherwise. */
	String onboardScanMessage;
	/**
	 * Job-engine lot C3 (2026-09-24): {@link OnboardStepRun#CATALOG_STEP}/{@link OnboardStepRun#SCAN_STEP} while
	 * a job of one of them is queued/running for {@link #onboardConnectorId} of this project - empty otherwise.
	 * Both write actions now run on a job's own thread, so the postback that enqueues one returns before any
	 * fresh {@link SourceOnboarding.Diagnosis} exists to show; {@code JOBS_POLL} (the jobs banner's own re-render,
	 * fired once when it sees the job it was following end) re-reads the diagnosis fresh - a live {@code
	 * connectors_describe} round trip, {@link SourceOnboarder#facts} - the first time this field is non-empty AND
	 * the job is no longer live, then clears it: see {@link #handle}'s own {@code JOBS_POLL} branch. Persists
	 * across postbacks exactly like {@link #onboardOpen}/{@link #onboardConnectorId} (this service instance is
	 * NOT rebuilt on every request - only its FORM fields reset that way, see CLAUDE.md &sect;9 and this class's
	 * own Javadoc).
	 */
	String onboardPendingKind = "";
	/** sample-files lot (2026-09-23): the last {@code SOURCE_FILES_IMPORT} report, one row per file - cleared on the next unrelated postback, same instance-state convention as every {@code onboard*} field above. */
	final List<SampleFileRules.ReportRow> sampleFilesReport = new ArrayList<SampleFileRules.ReportRow>();
	/** Set when {@code SOURCE_FILES_IMPORT} posted an empty/unparsable {@code mdmfSampleFiles} - task brief's own "server guard". Null otherwise. */
	String sampleFilesError;
	/**
	 * Wave 11 (audit F-42): step 4 folds its business-object cards beyond {@link WizardPage#ENTITY_FOLD_THRESHOLD}; these are what a person opened, folded again and
	 * typed in the filter box - kept across postbacks (the cards are written only when open, so the state must outlive the page), never persisted.
	 */
	final java.util.Set<String> stagingOpenEntities = new java.util.LinkedHashSet<String>();
	final java.util.Set<String> stagingClosedEntities = new java.util.LinkedHashSet<String>();
	boolean stagingOpenAll;
	String stagingFilter = "";
	/** The groups of the "decisions left" card opened past {@link WizardPage#STILL_GROUP_LIMIT} items. */
	final java.util.Set<String> stagingStillOpenGroups = new java.util.LinkedHashSet<String>();
	WizardRules.ProjectDraft projectDraft = new WizardRules.ProjectDraft();
	WizardRules.SourceDraft sourceDraft = new WizardRules.SourceDraft();
	int stagingBulkThreshold = 80;
	String llmRunSummary;
	/** Data-sample lot: the landing table a live sample was just fetched for, and what {@link LandingPreview#sample} answered - see {@link WizardPage.Model}'s own fields, this only carries them across into {@link #model()}. */
	String previewTableId = "";
	final List<String> previewColumns = new ArrayList<String>();
	final List<List<String>> previewRows = new ArrayList<List<String>>();
	String previewError;
	/** Landing-walkthrough lot (2026-10-16): the page of step 3's walkthrough (a ProjectSource id, {@code LandingWalk.LAUNCH}, or empty = the first application to validate), whether it was re-opened after a run, and the applications whose page said "Yes" to the language model. Plain instance state, like the open panels. */
	String walkPage = "";
	boolean walkReview;
	final java.util.Set<String> walkLlmYes = new java.util.LinkedHashSet<String>();
	/** Landing test fixes (2026-10-17): {@link #walkLlmYes} was filled once from the last run's answer (a person who then says No stays at No). */
	boolean walkLlmSeeded;
	/** Landing key-study lot (2026-09-23): the last {@code TABLE_KEY_STUDY} report, kept across the {@code TABLE_KEY_APPLY} round trip (the proposal it applies is this object's) - same instance-state convention as the preview fields above. */
	KeyStudyReport keyStudy;
	/** Sources+landing redesign, lot LANDING (2026-09-24): the {@code LANDING_KEY_CHOOSE} panel's own study, kept across {@code LANDING_KEY_APPLY} - same convention as {@link #keyStudy}, a separate field since both panels can be open at once (one under the running grid, one under the Advanced per-source table). */
	String landingKeyChooserTableId = "";
	KeyStudyReport landingKeyChooser;
	/** Audit F-34 (wave 10): the table whose study a queued {@link KeyStudyRun} job is making for the key study panel / the chooser; empty = none. See {@link #pickUpKeyStudies}. */
	String keyStudyPendingTableId = "";
	String landingKeyChooserPendingTableId = "";
	String landingKeyAppliedMessage;
	/** "Change the key..." panel (already-loaded table, {@code ebx.LandingKeyChange}) - which table it is open for, and the last result message. */
	String landingKeyChangeTableId = "";
	String landingKeyChangeMessage;
	/** Coordinator follow-up (2026-09-24): the done view's "Why isn't one column enough?" fold - which table it is open for, and its fresh evidence-only study (no language model - {@code KeyStudy.study(..., false)}). */
	String landingCompositeDetailTableId = "";
	KeyStudyReport landingCompositeDetail;
	/**
	 * Wizard-match-tree lot (2026-09-23): step 6's Simulate results, keyed by {@code MatchPlan} row id - kept on
	 * this service instance across postbacks and never persisted, exactly like {@code
	 * ProposalReviewUserService#matchSimulationResults} (see {@code review.MatchPlanView.SimulationResult}).
	 */
	final Map<String, MatchPlanView.SimulationResult> matchSimulationResults = new HashMap<String, MatchPlanView.SimulationResult>();
	/** The MATCH plan the current postback acted on, if any - its details fold reopens on the repaint ({@code WizardPage.Model#expandedMatchRowIds}); reset on every postback. */
	String lastActedMatchRowId;
	/**
	 * wizard-matching-results lot (2026-09-24): what the last postback left selected in the "Matching results"
	 * block - re-injected into the hidden fields ({@code WizardPage.Model#matchResultsSelectedTab}/
	 * {@code matchResultsSelectedPairId}) so a decision's own repaint keeps the same tab/pair open (task item
	 * 7), same "plain instance state across one postback" convention as {@link #lastActedMatchRowId}.
	 */
	String matchResultsSelectedTab = "";
	String matchResultsSelectedPairId = "";
	/** Which entity {@link #matchResultsSelectedPairId} belongs to - a second entity's block must never inherit the first one's selection. */
	String matchResultsSelectedEntity = "";
	/** Set when the last {@code MATCH_PAIR_MERGE}/{@code MATCH_PAIR_REJECT}/{@code MATCH_PAIR_BULK_MERGE} failed - shown as a notice on that entity's results block, cleared on every unrelated postback. */
	String matchDecisionError;
	/**
	 * Step-reset lot (2026-09-23): the confirmation panel's own data, kept on the service instance exactly like
	 * {@link #keyStudy}/{@link #onboardDiagnosis} - null means no panel is open. {@code STEP_RESET_PLAN} sets it
	 * (open, or re-open after a failed confirm); {@code STEP_RESET_CANCEL} clears it; a plain render never
	 * builds or clears it (persists across an unrelated postback the same way {@link #keyStudy} does).
	 */
	WizardPage.StepResetView stepReset;
	/**
	 * Volume-operations lot 8b (2026-09-28, task 3): {@link StepResetRun#STEP} while a job of it is queued/
	 * running for this project - empty otherwise. Same convention as {@code onboardPendingKind} above: {@code
	 * STEP_RESET_CONFIRM} sets it right after enqueuing (the postback returns before the reset has actually
	 * run); {@code JOBS_POLL} re-reads {@link StepResetRun#status} fresh the first time it sees the job is no
	 * longer live, then clears it - see {@link #handle}'s own {@code JOBS_POLL} branch.
	 */
	String stepResetPendingKind = "";
	/** The stage {@link #stepResetPendingKind} was queued for - needed by {@code JOBS_POLL} to re-read {@link StepResetRun#status} for the right stage. */
	String stepResetPendingStage = "";

	/** Wizard-summary lot (2026-09-24): {@code DELIVERY_CHECK}'s own result text - same transient-instance-state convention as {@link #stepReset}/{@link #llmRunSummary}. Null until that action has run once. */
	String deliveryCheckSummary;

	// distribution-wizard lot (2026-09-24, step 7): transient instance state, same "kept across postbacks,
	// never persisted" convention as stepReset/onboardOpen above. Empty = the 7a target list; a target's real
	// id, or "NEW", = its 7b setup panel is showing instead.
	String distOpenTargetId = "";
	/**
	 * Live bug fix (2026-09-25, DistribTest, task brief item 4): 7a now offers "Set up" straight from a
	 * connection row that has no target yet (not just a generic "+ Add a connection" with an empty picker) -
	 * the connector that button names ({@code data-mdmf-connector} on it, read into {@code mdmfDistNewConnector}
	 * by wizard.js) is carried here so {@link #buildDistribution} can preselect it on the freshly-opened NEW
	 * target's setup panel, same "the click already told the server what it meant" idea as the Summary open
	 * point's combined button (WIZARD-DISTRIBUTION.md item 1). Cleared once the setup panel closes.
	 */
	String distOpenNewConnector = "";
	/** Set by DISTRIBUTION_PRODUCT_OPEN - the 7c panel's own fields/table only render once true (a live {@code DataProductPublication.detect}/{@code plan} round trip, never paid for on an unrelated postback). */
	boolean distProductPanelOpen;
	String distNotice;
	String distError;

	/**
	 * Coordinator review (2026-09-24): the data product panel's own draft, persisted across postbacks so
	 * unticking a table, changing a child's nest mode, or adding a catalogue asset survives a re-render
	 * (previously every render re-defaulted the whole draft - the exact "contract preview must follow these
	 * choices" bug the review flagged). Seeded once from {@code DataProductPublication.detect}'s own
	 * {@code defaultDraft} ({@link #buildDistributionProduct}), then only ever updated by {@link
	 * #syncProductDraftFromPosted} - called by the panel's own actions ({@code DISTRIBUTION_PRODUCT_ADD_ASSET}/
	 * {@code _CREATE}), never by an unrelated postback elsewhere in the wizard (a known, documented scope
	 * boundary - see {@code docs/WIZARD-DISTRIBUTION.md}).
	 */
	private static final class ProductDraftState
	{
		boolean initialized;
		String productName = "";
		String code = "";
		String owner = "";
		String domain = "";
		String target = "EBX_ONLY";
		String schema = "";
		String refreshMode = "ON_DEMAND";
		boolean marketplaceListing;
		/** Every table/asset key the panel has ever rendered a checkbox for, and whether it is currently ticked - absent means never seen (treated as a fresh default candidate, included by default). */
		final Map<String, Boolean> included = new java.util.LinkedHashMap<String, Boolean>();
		/** Per-child-key {@code OWN}/{@code NESTED} override - absent means {@code OWN} (the planner's own default). */
		final Map<String, String> nestMode = new java.util.LinkedHashMap<String, String>();
		/** Catalogue asset keys added via the combo - a subset of {@link #included}'s keys. */
		final Set<String> addedAssetIds = new java.util.LinkedHashSet<String>();
	}

	private final ProductDraftState distProductDraft = new ProductDraftState();

	// automatic-intake wizard lot (2026-10-05, docs/AUTOMATIC-INTAKE.md "The screens"): transient instance state, same "kept across postbacks, never persisted" convention as
	// distOpenTargetId above. The intake lives INSIDE the Summary step: intakeOpen swaps the Summary's content for the set-up / running screen (by the intake's own state).
	boolean intakeOpen;
	/** Running screen only: the settings panel is showing. */
	boolean intakeSettingsOpen;
	/** What was typed when a save / switch-on was refused, shown again on the next render; null otherwise. */
	IntakeScreens.Draft intakeDraft;

	/** Audit F-02: what a refused click reads - the right it needed and the default role that has it. */
	static String denial(final java.util.Locale pLocale, final com.on.ebx.factory.core.FactoryRight pNeeded)
	{
		return pNeeded == com.on.ebx.factory.core.FactoryRight.ADMINISTRATOR || pNeeded == null
			? Messages.get(ProjectWizardUserService.class, pLocale, "access.denied.admin")
			: Messages.get(ProjectWizardUserService.class, pLocale, "access.denied", pNeeded.name(), com.on.ebx.factory.ebx.FactoryAccess.roleFor(pNeeded));
	}

	void handle(final UserServiceEventContext pContext)
	{
		// audit F-41: whatever escapes an action (only OperationException was caught) becomes a localized notice with a reference
		// logged in kernel.log - never EBX's own error page; the page then renders normally with the notice on top
		try
		{
			this.handleAction(pContext);
		}
		catch (final RuntimeException | Error ex)
		{
			this.warn(ScreenFailure.notice(this.locale, ScreenFailure.report("wizard", "action " + ProjectWizardUserService.param(pContext, "mdmfAction"), ex)));
		}
	}

	private void handleAction(final UserServiceEventContext pContext)
	{
		final Posted posted = ProjectWizardUserService.readPosted(pContext);
		this.postedOnce = true;
		this.requestSnapshots.clear(); // F2-107: one reading per request, never one carried over from an earlier request
		this.lastActedMatchRowId = null;
		// UX lot U3 (2026-10-13): a notice belongs to the render of the action that produced it. It used to live in this instance until another
		// action replaced it (the green "Planning the landing in the background" of 16:56 was still on top of step 6 at 17:33): every action
		// now starts from a clean line, and the one that has something to say sets it again.
		this.notice = null;
		this.noticeKind = "ok";
		this.noticeDetail = null;
		this.noticeDetailFor = null;
		// setupObjectContext only resolves a project from an actual EBX record selection (a toolbar launch on
		// one Project row); a brand new project created earlier in this same open dialog carries no such
		// selection, so mdmfProjectId (rendered by WizardPage from the last known snapshot) is the fallback -
		// see the class Javadoc and WizardPage#renderHiddenFields
		if ((this.projectId == null || this.projectId.isEmpty()) && !posted.projectId.isEmpty())
		{
			// second permissions pass (2026-10-14): a posted id is the page's word, re-checked with the user's view before it is adopted
			if (ProjectAccess.openProject(this.session, this.factory, posted.projectId) != null)
			{
				this.projectId = posted.projectId;
			}
			else
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "screen.project.unknown"));
				return;
			}
		}
		this.currentStep = posted.step < 1 || posted.step > WizardRules.STEP_MAX ? WizardRules.STEP_PROJECT : posted.step;
		this.activeSourceId = posted.sourceId;
		this.pickedCatalogSourceId = posted.catalogSourceId;
		this.sourceDraft = posted.sourceDraft;
		this.stagingBulkThreshold = posted.bulkThreshold;
		this.projectDraft = posted.projectDraft;
		if (!this.pickedCatalogSourceId.isEmpty())
		{
			this.pickedCatalogSourceSchemas.addAll(WizardData.schemasOf(CatalogReader.Reader.of(this.session), this.factory, this.pickedCatalogSourceId));
		}

		final String action = ProjectWizardUserService.param(pContext, "mdmfAction");
		// audit F-02 (was: administrators only): each action asks its own right (core.FactoryRights.wizardAction - VIEW to look,
		// STEWARD to decide suspect pairs, DESIGN to design and run, a few stay with the administrator; an action not listed asks
		// the administrator). The permission rule only hides the service: a forged or stale postback is refused here, before
		// anything is read from or written to EBX. The posted step and project above are only display state.
		final com.on.ebx.factory.core.FactoryRight needed = com.on.ebx.factory.core.FactoryRights.wizardAction(action);
		if (!com.on.ebx.factory.ebx.FactoryAccess.allows(pContext.getSession(), this.factory, action, needed))
		{
			this.warn(ProjectWizardUserService.denial(this.locale, needed));
			return;
		}
		// second permissions pass (2026-10-14): the Factory right is necessary, EBX's permissions on the project decide too. The dataset and the
		// service are checked again (a postback outlives a change of rights), then RecordGate: a project the user cannot see is answered like an
		// unknown one; an action that writes or queues anything about the project needs read-write access on its record; creating a project needs
		// EBX's creation right on the Project table. Past this point the procedures run with the user's own session (EBX checks each write) and
		// the jobs run as the system user - they were allowed here, for this user, on this project.
		if (ProjectAccess.refusal(this.session, this.factory, this.locale) != null
			|| !ProjectAccess.serviceOnTable(this.session, ProjectWizardServiceDeclaration.SERVICE_KEY, this.factory.getTable(FactoryPaths.PROJECT)))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "screen.service.refused"));
			return;
		}
		final Adaptation projectRecord = this.openProject();
		final com.on.ebx.factory.core.RecordGate.Verdict verdict = com.on.ebx.factory.core.RecordGate.onWizardAction(needed, "SAVE_PROJECT".equals(action),
			this.projectId, projectRecord != null, projectRecord != null, this.view().recordWritable(projectRecord),
			this.view().canCreate(this.factory.getTable(FactoryPaths.PROJECT)));
		if (verdict == com.on.ebx.factory.core.RecordGate.Verdict.UNKNOWN)
		{
			this.projectId = null;
			this.projectLocked = false;
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "screen.project.unknown"));
			return;
		}
		if (verdict == com.on.ebx.factory.core.RecordGate.Verdict.READ_ONLY)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "screen.readOnly"));
			return;
		}
		if (verdict == com.on.ebx.factory.core.RecordGate.Verdict.NO_CREATE)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "screen.noCreate"));
			return;
		}
		// the source and the landing table the page posts are its word too: one the user cannot see, or of another project, is dropped (answered
		// like no selection - a stale id of a removed source after its removal must not block the next action); a job queued with them runs as the
		// system user
		this.dropForeignPostedRows(posted);
		try
		{
			if ("GOTO_STEP".equals(action))
			{
				// pure navigation: wizard.js normally handles this without a round trip; kept for a no-JS request
				return;
			}
			if ("STEP_VIEW".equals(action))
			{
				// live bug fix (2026-09-25, DistribTest): the rail's own step 7/8 buttons (WizardPage#renderSteps)
				// submit this real, otherwise-harmless action instead of the plain client-side GOTO_STEP, purely
				// to force a round trip - this.currentStep already reflects the posted step (set above, before
				// this if-chain), so simply falling through to the normal render lets model()'s own "only when
				// this step is the one being shown" rule (buildDistribution/buildSummary) recompute fresh data,
				// which a pure client-side rail switch could never trigger on its own.
				return;
			}
			// wave 11 (audit F-42): step 4's folded business objects and its filter - plain state of this instance, like the other open panels
			if ("STAGING_ENTITY_OPEN".equals(action) || "STAGING_ENTITY_CLOSE".equals(action))
			{
				final String entityId = ProjectWizardUserService.param(pContext, "mdmfId");
				final boolean open = "STAGING_ENTITY_OPEN".equals(action);
				if (!entityId.isEmpty())
				{
					// after "Unfold all" a card is open unless listed as closed; otherwise it is open only when listed as open
					final java.util.Set<String> list = this.stagingOpenAll ? this.stagingClosedEntities : this.stagingOpenEntities;
					if (open == this.stagingOpenAll)
					{
						list.remove(entityId);
					}
					else
					{
						list.add(entityId);
					}
				}
				return;
			}
			if ("STAGING_STILL_MORE".equals(action) || "STAGING_STILL_LESS".equals(action))
			{
				final String group = ProjectWizardUserService.param(pContext, "mdmfId");
				if ("STAGING_STILL_MORE".equals(action))
				{
					this.stagingStillOpenGroups.add(group);
				}
				else
				{
					this.stagingStillOpenGroups.remove(group);
				}
				return;
			}
			if ("STAGING_ENTITY_OPEN_ALL".equals(action))
			{
				this.stagingOpenAll = true;
				this.stagingOpenEntities.clear();
				this.stagingClosedEntities.clear();
				return;
			}
			if ("STAGING_ENTITY_CLOSE_ALL".equals(action))
			{
				this.stagingOpenAll = false;
				this.stagingOpenEntities.clear();
				this.stagingClosedEntities.clear();
				return;
			}
			if ("STAGING_FILTER".equals(action))
			{
				final String filter = ProjectWizardUserService.param(pContext, "mdmfStagingFilter").trim();
				this.stagingFilter = filter.length() > 100 ? filter.substring(0, 100) : filter;
				return;
			}
			if ("SAVE_PROJECT".equals(action))
			{
				this.saveProject(posted);
				return;
			}
			if ("SOURCE_PICK_CATALOG".equals(action))
			{
				this.pickCatalogSource();
				return;
			}
			if ("SOURCE_ADD".equals(action))
			{
				this.addSource(posted);
				return;
			}
			if ("SOURCE_REMOVE".equals(action))
			{
				this.removeSource(posted);
				return;
			}
			if ("SOURCE_SET_TRUST".equals(action))
			{
				this.setSourceTrustRank(posted);
				return;
			}
			// sources-landing lot SOURCES (2026-09-24): the Review screen's own three write actions - editable
			// code, trust-rank reorder (up/down), and dropping a still-stuck queued application.
			if ("SOURCE_SET_CODE".equals(action))
			{
				this.setSourceCode(posted);
				return;
			}
			if ("SOURCE_TRUST_UP".equals(action) || "SOURCE_TRUST_DOWN".equals(action))
			{
				this.reorderSourceTrust(posted, "SOURCE_TRUST_UP".equals(action));
				return;
			}
			if ("SOURCES_RUN_REMOVE".equals(action))
			{
				this.removeQueuedSource(posted);
				return;
			}
			// source-onboarding lot (2026-09-23; revised after coordinator review the same day): the "Create a
			// catalog source" panel's own seven actions - see each method's own Javadoc.
			// UX lot U2 (2026-10-13): the single "Add an application" list - opening it loads the connections (like SOURCE_ONBOARD_OPEN, never on a plain
			// render), picking a row registers/scans/plans through the same job as "Do everything" without touching the step-by-step panel's own state
			if ("SOURCE_APP_OPEN".equals(action))
			{
				this.addAppOpen = true;
				this.onboardRefreshConnections();
				return;
			}
			if ("SOURCE_ADD_APP".equals(action))
			{
				this.addApplicationFromConnection(posted);
				return;
			}
			if ("SOURCE_ONBOARD_OPEN".equals(action))
			{
				this.onboardOpenPanel();
				return;
			}
			if ("SOURCE_ONBOARD_CLOSE".equals(action))
			{
				this.onboardClosePanel();
				return;
			}
			if ("SOURCE_ONBOARD_PICK".equals(action))
			{
				this.onboardPick(posted);
				return;
			}
			if ("SOURCE_ONBOARD_REFRESH".equals(action))
			{
				this.onboardRefresh(posted);
				return;
			}
			if ("SOURCE_ONBOARD_CATALOG".equals(action))
			{
				this.onboardCatalog(posted);
				return;
			}
			if ("SOURCE_ONBOARD_SCAN".equals(action))
			{
				this.onboardScan(posted);
				return;
			}
			if ("SOURCE_ONBOARD_USE".equals(action))
			{
				this.onboardUse();
				return;
			}
			// fix lot (2026-09-25, bug 1): the checklist's DEDUCED_SCAN next action - see ebx.DeducedScanEntry's
			// own Javadoc for what one click does (creates the application, queues the deduced-scan job).
			if ("SOURCE_ONBOARD_DEDUCED".equals(action))
			{
				this.onboardDeduced(posted);
				return;
			}
			// sample-files lot (2026-09-23): the "Do everything" button on the guided onboarding panel
			// (SOURCE_ONBOARD_ALL, next to CATALOG_APPLY/SCAN_RUN) and the "Sources from sample files" panel's
			// own single submit (SOURCE_FILES_IMPORT) - see each method's own Javadoc.
			if ("SOURCE_ONBOARD_ALL".equals(action))
			{
				this.onboardAll(posted);
				return;
			}
			if ("SOURCE_FILES_IMPORT".equals(action))
			{
				this.sourceFilesImport(posted);
				return;
			}
			// sources-landing lot SOURCES (2026-09-24): the "applications in progress" cards' own self-poll
			// (wizard.js, data-mdmf-sources-running) - a plain re-render, no side effect at all: model() below
			// always reads SourcesRun.status fresh, exactly like STAGING_PROPOSAL_POLL.
			if ("SOURCES_RUN_POLL".equals(action))
			{
				return;
			}
			// deduced-scan lot (2026-09-24, boards 2d/2e): DEDUCED_SCAN_START queues one ProjectSource (posted
			// as mdmfSourceId, the shared field every other step-2 action already reuses) into ebx.DeducedScanRun;
			// DEDUCED_SCAN_POLL is the pipeline cards' own self-poll (wizard.js, data-mdmf-deduced-scan-running),
			// a plain re-render like SOURCES_RUN_POLL above - model() always reads DeducedScanRun.status fresh.
			// CONFIRM/LEAVE_OUT act on one Asset row (mdmfId - see wizard.js's own data-mdmf-id convention).
			if ("DEDUCED_SCAN_START".equals(action))
			{
				DeducedScanRun.start(this.session, this.factory, this.projectId, posted.sourceId);
				return;
			}
			if ("DEDUCED_SCAN_POLL".equals(action))
			{
				return;
			}
			if ("DEDUCED_SCAN_CONFIRM".equals(action))
			{
				this.deducedScanConfirm(pContext);
				return;
			}
			if ("DEDUCED_SCAN_LEAVE_OUT".equals(action))
			{
				this.deducedScanLeaveOut(pContext);
				return;
			}
			// UX lot U2 (2026-10-13): step 2's own actions - the ticks of every card in one write, "Find its tables" (a plan whose new tables start
			// unticked and whose result says why when it finds nothing) and "Scan again" (catalog scan then plan, one background job)
			if ("TABLES_KEEP".equals(action))
			{
				this.keepTables(posted);
				return;
			}
			if ("SOURCE_FIND_TABLES".equals(action))
			{
				this.findTables(posted.sourceId);
				return;
			}
			if ("SOURCE_RESCAN".equals(action))
			{
				this.rescanSource(posted.sourceId);
				return;
			}
			if ("TABLE_TOGGLE".equals(action))
			{
				WizardData.toggleTableSelected(this.session, this.factory, posted.tableId);
				return;
			}
			if ("TABLE_SET_KEY".equals(action))
			{
				WizardData.setTableKey(this.session, this.factory, posted.tableId, posted.keyColumns);
				return;
			}
			if ("TABLE_SET_CHANGE_COLUMN".equals(action))
			{
				// volume wave 2, lot V2-E: never refused for a table's status (unlike the key) - see WizardData.setTableChangeColumn
				WizardData.setTableChangeColumn(this.session, this.factory, posted.tableId, posted.changeColumn, posted.changeKind);
				return;
			}
			if ("TABLE_PREVIEW".equals(action))
			{
				this.preview(posted.tableId);
				return;
			}
			if ("TABLE_KEY_STUDY".equals(action))
			{
				this.keyStudy(posted.tableId);
				return;
			}
			if ("TABLE_KEY_APPLY".equals(action))
			{
				this.applyKeyStudy(posted.tableId);
				return;
			}
			// sources+landing redesign, lot LANDING (2026-09-24): the one-click "Prepare the landing" run - see
			// ebx.LandingRun's own Javadoc. LANDING_RUN_POLL is the "while running" screen's self-poll (wizard.js,
			// data-mdmf-landing-running), a plain re-render like STAGING_PROPOSAL_POLL above: model() always reads
			// LandingRun.status fresh.
			if (action.startsWith("LANDING_WALK_"))
			{
				this.landingWalk(pContext, action, posted);
				return;
			}
			if ("LANDING_RUN_START".equals(action))
			{
				this.startLandingRun(pContext);
				return;
			}
			if ("LANDING_RETRY_FAILED".equals(action))
			{
				this.retryFailedLandingTables(pContext);
				return;
			}
			if ("LANDING_RUN_POLL".equals(action))
			{
				// live bug fix (coordinator, 2026-09-24, bug 4): a poll is not the action that produced these
				// notices - this.notice ("The landing run has started.", set once by startLandingRun) and
				// landingKeyAppliedMessage ("Key saved.", set once by applyLandingKey) are plain instance fields
				// that live for as long as this service instance is open (setupObjectContext does not rebuild
				// it on every request - only the FORM fields reset that way, see CLAUDE.md 9), so with nothing
				// here clearing them they kept re-rendering on every single 5s reload, for as long as the run
				// kept polling - "for minutes", per the live report. Cleared here so each notice shows exactly
				// once, on the response of the action that actually produced it.
				this.notice = null;
				this.landingKeyAppliedMessage = null;
				return;
			}
			if ("LANDING_RUN_STOP".equals(action))
			{
				this.stopLandingRun();
				return;
			}
			if ("LANDING_KEY_CHOOSE".equals(action))
			{
				this.chooseLandingKey(posted.tableId);
				return;
			}
			if ("LANDING_KEY_APPLY".equals(action))
			{
				this.applyLandingKey(posted.tableId, posted.keyColumns);
				return;
			}
			// composite-key lot's ebx.LandingKeyChange - "Change the key..." on an already-loaded table, under
			// the Advanced per-source table. LANDING_KEY_CHANGE_PICK only opens the picker (a cheap, no-write
			// round trip); LANDING_KEY_CHANGE is the real write, its own button label stating the consequence -
			// see WizardPage.renderLandingKeyChangePicker's own Javadoc for why no separate confirm step exists.
			if ("LANDING_KEY_CHANGE_PICK".equals(action))
			{
				this.landingKeyChangeTableId = posted.tableId;
				this.landingKeyChangeMessage = null;
				return;
			}
			if ("LANDING_KEY_CHANGE".equals(action))
			{
				this.changeLandingKey(posted.tableId, posted.keyColumns);
				return;
			}
			// coordinator follow-up (2026-09-24): the done view's "Why isn't one column enough?" fold - a fresh,
			// evidence-only study (no language model), never applied - see WizardPage.renderLandingCompositeFold.
			if ("LANDING_KEY_COMPOSITE_DETAIL".equals(action))
			{
				this.landingCompositeDetailTableId = posted.tableId;
				this.landingCompositeDetail = KeyStudy.study(this.session, this.factory, posted.tableId, false);
				return;
			}
			if ("PLAN".equals(action))
			{
				this.plan(posted.sourceId);
				return;
			}
			if ("GENERATE".equals(action))
			{
				this.generate(posted.sourceId);
				return;
			}
			if ("DECIDE_ACCEPT".equals(action) || "DECIDE_REJECT".equals(action))
			{
				this.decide(pContext, "DECIDE_ACCEPT".equals(action));
				return;
			}
			// lot C1-B: the CENTRALISATION path (pack choice, specification, proposal passes, generation)
			if (action.startsWith("ACCEL_"))
			{
				this.accelAction(pContext, action);
				return;
			}
			// inline-reviews lot (2026-09-23): every SCRIPT_CONSTRAINT/MATCH action the Proposal review screen
			// offers beyond plain Accept/Reject, now also reachable in place - see each method's own Javadoc.
			if ("SIMULATE".equals(action))
			{
				// wizard-match-tree lot (2026-09-23): the Simulate button of the plan's details fold - the same
				// mdmfNature/mdmfId triad every action button carries, see simulateMatch's own Javadoc.
				this.simulateMatch(pContext);
				return;
			}
			if ("APPLY".equals(action) || "REAPPLY".equals(action))
			{
				this.applyCapabilityDirectly(pContext);
				return;
			}
			if ("REMOVE".equals(action))
			{
				this.removeCapability(pContext);
				return;
			}
			if ("MEASURE".equals(action))
			{
				this.measureCapability(pContext);
				return;
			}
			if ("REPROPOSE".equals(action))
			{
				this.reproposeMatch(pContext);
				return;
			}
			if ("QUALITY_SET_SEVERITY".equals(action))
			{
				this.setQualitySeverity(pContext);
				return;
			}
			// staging-still-to-decide lot (2026-09-27): "Set aside" / "Set all N aside" on step 4's "decisions
			// left" card - see setCapabilitiesAside's own Javadoc.
			if ("CAPABILITY_SET_ASIDE".equals(action))
			{
				this.setCapabilitiesAside(pContext);
				return;
			}
			if ("CAPABILITY_SET_TRANSFER_POLICY".equals(action))
			{
				this.setCapabilityTransferPolicy(pContext);
				return;
			}
			// lot P1 (2026-10-04): step 4's "To apply" list - a person settles the parameter(s) of a script derivation
			if ("DERIVATION_SET_PARAMS".equals(action))
			{
				this.setDerivationParameters(pContext);
				return;
			}
			// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): the feed tree's own editable pipeline/
			// sourceIdPipeline controls - same wizard-only convention as QUALITY_SET_SEVERITY/
			// CAPABILITY_SET_TRANSFER_POLICY above.
			if ("FEED_SET_PIPELINE".equals(action))
			{
				this.setMappingPipeline(pContext);
				return;
			}
			if ("FEED_SET_SOURCE_ID_PIPELINE".equals(action))
			{
				this.setFeedSourceIdPipeline(pContext);
				return;
			}
			if ("STAGING_SUGGEST".equals(action))
			{
				this.suggestStaging();
				return;
			}
			if ("STAGING_SUGGEST_LLM".equals(action))
			{
				this.suggestStagingLlm();
				return;
			}
			if ("STAGING_PROPOSAL_RUN".equals(action))
			{
				this.startProposalRun(pContext);
				return;
			}
			// staging-proposal-run lot (2026-09-24): the "while running" screen's own self-poll (wizard.js,
			// data-mdmf-proposal-running) - a plain re-render, no side effect at all: model() below always reads
			// StagingProposalRun.status fresh, so simply falling through to the ordinary render is enough.
			if ("STAGING_PROPOSAL_POLL".equals(action))
			{
				return;
			}
			// lot C2 (2026-09-24): the jobs banner's one re-render when a job it saw running ends - a plain re-render
			if ("JOBS_POLL".equals(action))
			{
				// job-engine lot C3 (2026-09-24): see onboardPendingKind's own Javadoc - re-read the onboarding
				// diagnosis fresh, exactly once, the first JOBS_POLL after the catalog/scan job it was set for is
				// no longer QUEUED/RUNNING (never while it still is - that would pay for a live
				// connectors_describe round trip on every 3s poll for nothing).
				if (!this.onboardPendingKind.isEmpty() && !this.onboardStepStillPending())
				{
					this.onboardPendingKind = "";
					if (!this.onboardConnectorId.isEmpty())
					{
						this.onboardRefreshDiagnosis();
					}
				}
				// volume-operations lot 8b (2026-09-28, task 3): same idiom - the step-reset job's own postback
				// returned before StepResetter.resetHoldingProject had actually run; re-read its result fresh,
				// exactly once, the first poll after it is no longer QUEUED/RUNNING.
				if (!this.stepResetPendingKind.isEmpty() && !this.stepResetRunRunning())
				{
					this.stepResetPendingKind = "";
					this.stepReset = this.stepResetStatusView(this.stepResetPendingStage);
				}
				return;
			}
			if ("STAGING_BULK_ACCEPT".equals(action))
			{
				this.bulkStaging(true, WizardSnapshot.BulkFilter.wholeProject());
				return;
			}
			if ("STAGING_BULK_REJECT".equals(action))
			{
				this.bulkStaging(false, WizardSnapshot.BulkFilter.wholeProject());
				return;
			}
			// block-accept lot (2026-09-23): the SAME sweep, restricted to one entity's own card - see
			// bulkStaging's own Javadoc. Quality-block-bulk lot (2026-09-23): the same restricted to this
			// entity's MAPPING_STEP/QUALITY proposal lines only - see WizardSnapshot.BulkFilter's own Javadoc.
			if ("STAGING_BULK_ACCEPT_ENTITY".equals(action))
			{
				this.bulkStaging(true, new WizardSnapshot.BulkFilter(ProjectWizardUserService.param(pContext, "mdmfId"), null, posted.includeBelowThreshold));
				return;
			}
			if ("STAGING_BULK_REJECT_ENTITY".equals(action))
			{
				this.bulkStaging(false, new WizardSnapshot.BulkFilter(ProjectWizardUserService.param(pContext, "mdmfId"), null, posted.includeBelowThreshold));
				return;
			}
			if ("STAGING_BULK_ACCEPT_QUALITY".equals(action))
			{
				this.bulkStaging(true, new WizardSnapshot.BulkFilter(ProjectWizardUserService.param(pContext, "mdmfId"), ProjectWizardUserService.QUALITY_BLOCK_NATURES, posted.includeBelowThreshold));
				return;
			}
			if ("STAGING_BULK_REJECT_QUALITY".equals(action))
			{
				this.bulkStaging(false, new WizardSnapshot.BulkFilter(ProjectWizardUserService.param(pContext, "mdmfId"), ProjectWizardUserService.QUALITY_BLOCK_NATURES, posted.includeBelowThreshold));
				return;
			}
			// live bug fix (2026-09-25, PersVol2 volume test): see CLEANING_STEP_NATURES' own Javadoc - a
			// project-wide, always-reachable way to accept cleaning steps, right next to the header that
			// names them as waiting.
			if ("STAGING_BULK_ACCEPT_STEPS".equals(action))
			{
				this.bulkStaging(true, new WizardSnapshot.BulkFilter(null, ProjectWizardUserService.CLEANING_STEP_NATURES, false));
				return;
			}
			if ("STAGING_REOPEN_MAPPINGS".equals(action))
			{
				this.reopenMappings();
				return;
			}
			if ("QUALITY_SUGGEST".equals(action))
			{
				this.suggestQuality();
				return;
			}
			if ("QUALITY_SUGGEST_LLM".equals(action))
			{
				this.suggestQualityLlm();
				return;
			}
			if ("CAPABILITY_SUGGEST_LLM".equals(action))
			{
				this.suggestCapabilitiesLlm();
				return;
			}
			if ("STAGING_GENERATE".equals(action))
			{
				this.generateStagingModel();
				return;
			}
			if ("STAGING_LOAD".equals(action))
			{
				this.loadStagingData(posted.stagingScopeAll);
				return;
			}
			// wizard-visual-pass lot (2026-09-24): the one-click "Fill the shared structure" primary button -
			// chains STAGING_GENERATE then STAGING_LOAD in the background, see ebx.StagingLoadRun's own Javadoc
			// for why (both a Cockpit/MCP-heavy model build and a real transfer, together comfortably over the
			// "about two minutes" the board itself states).
			if ("STAGING_LOAD_RUN".equals(action))
			{
				this.startStagingLoadRun(posted.stagingScopeAll, false);
				return;
			}
			// staging preflight lot H1 (2026-10-17): "Build anyway" (sets the check before the build aside), "Check before building" and "Apply the fix" of its report
			if ("STAGING_LOAD_RUN_ANYWAY".equals(action))
			{
				this.startStagingLoadRun(posted.stagingScopeAll, true);
				return;
			}
			if ("STAGING_PREFLIGHT".equals(action))
			{
				this.checkBeforeBuild(false, "");
				return;
			}
			if ("STAGING_PREFLIGHT_FIX".equals(action))
			{
				this.checkBeforeBuild(true, ProjectWizardUserService.param(pContext, "mdmfId"));
				return;
			}
			// self-poll, same no-op contract as STAGING_PROPOSAL_POLL above - model() always reads
			// StagingLoadRun.status fresh.
			if ("STAGING_LOAD_RUN_POLL".equals(action))
			{
				return;
			}
			if ("MASTER_GENERATE".equals(action))
			{
				this.generateMasterModel();
				return;
			}
			if ("MASTER_LOAD".equals(action))
			{
				this.loadMasterData();
				return;
			}
			// wizard-visual-pass lot (2026-09-24): the one-click "Build the master" primary button - chains
			// MASTER_GENERATE then MASTER_LOAD in the background, same reasoning as STAGING_LOAD_RUN above.
			if ("MASTER_BUILD_RUN".equals(action))
			{
				this.startMasterBuildRun();
				return;
			}
			if ("MASTER_BUILD_RUN_POLL".equals(action))
			{
				return;
			}
			if ("MATCHING_SUGGEST".equals(action))
			{
				this.suggestMatchingPlans();
				return;
			}
			if ("MATCHING_SUGGEST_LLM".equals(action))
			{
				this.suggestMatchingPlansLlm();
				return;
			}
			if ("MATCHING_SET_TRIGGER".equals(action))
			{
				this.setMatchingTrigger(posted.matchingTrigger);
				return;
			}
			if ("MATCHING_CONFIGURE".equals(action))
			{
				this.configureMatchingPlans();
				return;
			}
			if ("MATCHING_RUN".equals(action))
			{
				this.runMatchingPlans(posted.matchScope);
				return;
			}
			// wizard-matching-results lot (2026-09-24): the suspect queue's own decide actions - both go
			// through the ALREADY-EXISTING RecordLineageReader.decideSuspect (never a second write path).
			// F2-106: a page of the group queue - the page rides in the tab field ("PENDING@n"), the repaint writes only that page
			if ("MATCH_PAGE".equals(action))
			{
				this.matchResultsSelectedTab = posted.matchTab.isEmpty() ? "PENDING" : posted.matchTab;
				this.matchResultsSelectedEntity = ProjectWizardUserService.param(pContext, "mdmfId");
				this.matchResultsSelectedPairId = "";
				return;
			}
			if ("MATCH_PAIR_MERGE".equals(action) || "MATCH_PAIR_REJECT".equals(action))
			{
				final String entityName = ProjectWizardUserService.param(pContext, "mdmfId");
				this.decideMatchPair(entityName, posted.matchGolden, posted.matchSuspect, "MATCH_PAIR_MERGE".equals(action));
				this.matchResultsSelectedTab = posted.matchTab.isEmpty() ? "PENDING" : posted.matchTab;
				this.matchResultsSelectedEntity = entityName;
				this.matchResultsSelectedPairId = "";
				return;
			}
			// step 6 queue per group (2026-10-18): one decision for the whole group of records (mdmfMatchGolden = the group's record keys, mdmfMatchSuspect = the keys a person ticked "not this one")
			if ("MATCH_GROUP_MERGE".equals(action) || "MATCH_GROUP_REJECT".equals(action))
			{
				final String entityName = ProjectWizardUserService.param(pContext, "mdmfId");
				this.decideMatchGroup(entityName, posted.matchGolden, posted.matchSuspect, "MATCH_GROUP_MERGE".equals(action));
				this.matchResultsSelectedTab = posted.matchTab.isEmpty() ? "PENDING" : posted.matchTab;
				this.matchResultsSelectedEntity = entityName;
				this.matchResultsSelectedPairId = "";
				return;
			}
			if ("MATCH_PAIR_BULK_MERGE".equals(action))
			{
				final String entityName = ProjectWizardUserService.param(pContext, "mdmfId");
				this.bulkMergeMatchPairs(entityName, posted.bulkThreshold);
				this.matchResultsSelectedTab = "PENDING";
				this.matchResultsSelectedEntity = entityName;
				this.matchResultsSelectedPairId = "";
				return;
			}
			// wave 2 (2026-09-30): "Refresh results" - queues the MatchingResultsRun job that recomputes the stored matching results
			// of one entity (never computed while rendering). Administrators only, like a decision.
			if ("MATCH_RESULTS_REFRESH".equals(action))
			{
				this.refreshMatchResults(ProjectWizardUserService.param(pContext, "mdmfId"));
				this.matchResultsSelectedTab = "PENDING";
				this.matchResultsSelectedEntity = ProjectWizardUserService.param(pContext, "mdmfId");
				this.matchResultsSelectedPairId = "";
				return;
			}
			// wizard-summary lot (2026-09-24): step 7's two "go to production" actions.
			if ("PIPELINE_LAUNCH".equals(action))
			{
				this.launchPipeline();
				return;
			}
			if ("DELIVERY_CHECK".equals(action))
			{
				this.checkDelivery();
				return;
			}
			// distribution-wizard lot (2026-09-24, step 7 "Distribution", SPEC-distribution.md/docs/DISTRIBUTION*.md):
			// see each method's own Javadoc. Every write goes straight through the lot-1/lot-2 EBX-bound classes
			// (DistributionReader/Configurator/Runner/TriggerInstaller, DataProductPublication) - no HTTP loopback,
			// same in-process call convention every other wizard action already uses.
			if ("DISTRIBUTION_TARGET_OPEN".equals(action))
			{
				this.distOpenTargetId = ProjectWizardUserService.param(pContext, "mdmfId");
				// live bug fix (2026-09-25, task brief item 4): only meaningful for a NEW target opened straight
				// from an unconfigured connection's own "Set up" button - see the field's own Javadoc.
				this.distOpenNewConnector = "NEW".equals(this.distOpenTargetId) ? ProjectWizardUserService.param(pContext, "mdmfDistNewConnector") : "";
				this.distError = null;
				return;
			}
			if ("DISTRIBUTION_TARGET_CLOSE".equals(action))
			{
				this.distOpenTargetId = "";
				this.distOpenNewConnector = "";
				this.distError = null;
				return;
			}
			if ("DISTRIBUTION_TARGET_SAVE".equals(action))
			{
				this.saveDistributionTarget(posted);
				return;
			}
			if ("DISTRIBUTION_TARGET_CONFIGURE".equals(action))
			{
				this.runDistributionTargetAction(ProjectWizardUserService.param(pContext, "mdmfId"), "configure");
				return;
			}
			if ("DISTRIBUTION_TARGET_INSTALL_TRIGGERS".equals(action))
			{
				this.runDistributionTargetAction(ProjectWizardUserService.param(pContext, "mdmfId"), "installTriggers");
				return;
			}
			if ("DISTRIBUTION_TARGET_FIRST_FULL_SEND".equals(action))
			{
				this.runDistributionTargetAction(ProjectWizardUserService.param(pContext, "mdmfId"), "firstFullSend");
				return;
			}
			if ("DISTRIBUTION_TARGET_ARM_OVERRIDE".equals(action))
			{
				this.runDistributionTargetAction(ProjectWizardUserService.param(pContext, "mdmfId"), "armOverride");
				return;
			}
			if ("DISTRIBUTION_TARGET_DISARM".equals(action))
			{
				this.runDistributionTargetAction(ProjectWizardUserService.param(pContext, "mdmfId"), "disarm");
				return;
			}
			if ("DISTRIBUTION_TARGET_TEST_CONNECTION".equals(action))
			{
				this.runDistributionTargetAction(ProjectWizardUserService.param(pContext, "mdmfId"), "testConnection");
				return;
			}
			if ("DISTRIBUTION_TARGET_SEND_NOW".equals(action))
			{
				final String scope = ProjectWizardUserService.param(pContext, "mdmfDistSendScope");
				this.sendDistributionTargetNow(ProjectWizardUserService.param(pContext, "mdmfId"), scope.isEmpty() ? posted.distSendScope : scope);
				return;
			}
			if ("DISTRIBUTION_CONNECTIONS_REFRESH".equals(action))
			{
				// audit F-2-101: the read runs on a background thread; the page says "reading" and re-renders when it is in
				DistributionStepFacts.shared().refreshInBackground(SourceOnboarder.viewerId(this.session), this.readProjectCode(), this.distributionFactsReader(this.readProjectCode()));
				return;
			}
			if ("DISTRIBUTION_WAREHOUSE_TEST".equals(action))
			{
				// audit F-2-101: the ONLY place the warehouse probe ("select 1") runs - it wakes a suspended warehouse, so it is an explicit click
				final String testedProject = this.readProjectCode();
				final DistributionStepFacts.Entry tested = DistributionStepFacts.shared().probeNow(SourceOnboarder.viewerId(this.session), testedProject,
					this.distributionFactsReader(testedProject));
				this.distNotice = tested == null || tested.warehouseReachable == null
					? Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.product.snowflakeNone")
					: tested.warehouseDetail;
				return;
			}
			if ("DISTRIBUTION_PRODUCT_OPEN".equals(action))
			{
				this.distProductPanelOpen = true;
				return;
			}
			if ("DISTRIBUTION_PRODUCT_ADD_ASSET".equals(action))
			{
				this.addDistributionProductAsset(pContext, posted);
				return;
			}
			if ("DISTRIBUTION_PRODUCT_CREATE".equals(action))
			{
				this.createDistributionProduct(pContext, posted);
				return;
			}
			// automatic-intake wizard lot (2026-10-05): the Summary's "Automatic intake" screens - see handleIntake. Every posted value is read here, in the event
			// callback (CLAUDE.md section 9: setupObjectContext runs AFTER it, so nothing it re-reads may overwrite what an action decided).
			if (action.startsWith("INTAKE_"))
			{
				this.handleIntake(action, pContext);
				return;
			}
			// step-reset lot (2026-09-23): "Clean this step and the following ones" on steps 3-6 - see
			// WizardPage.renderStepReset's own Javadoc for the confirmation panel this drives.
			if ("STEP_RESET_PLAN".equals(action))
			{
				this.stepResetPlan(pContext);
				return;
			}
			if ("STEP_RESET_CONFIRM".equals(action))
			{
				this.stepResetConfirm(pContext);
				return;
			}
			// audit F-43: "Run again" on a task of the Recent tasks list that a server restart interrupted
			if ("JOB_RERUN".equals(action))
			{
				this.rerunJob(pContext);
				return;
			}
			if ("STEP_RESET_CANCEL".equals(action))
			{
				this.stepReset = null;
				return;
			}
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/**
	 * Audit F-43: queues again a job of this project that a server restart interrupted, with the parameters it had - only for a kind
	 * {@link com.on.ebx.factory.wizard.RecentTasks#rerunnable} says may run twice; the queue checks the right of the kind.
	 */
	private void rerunJob(final UserServiceEventContext pContext) throws OperationException
	{
		final String jobId = ProjectWizardUserService.param(pContext, "mdmfId");
		Adaptation row = null;
		try
		{
			// second permissions pass (2026-10-14): a job row the user cannot see is answered like a forged id
			row = jobId.isEmpty() ? null : this.view().lookup(this.factory.getTable(FactoryPaths.FACTORY_JOB), jobId);
		}
		catch (final RuntimeException ex)
		{
			row = null; // a forged or stale id
		}
		final String kind = row == null ? "" : String.valueOf(row.getString(FactoryPaths.FactoryJob.KIND));
		if (row == null || this.projectId == null || !this.projectId.equals(row.getString(FactoryPaths.FactoryJob.PROJECT))
			|| !com.on.ebx.factory.wizard.RecentTasks.INTERRUPTED.equals(row.getString(FactoryPaths.FactoryJob.STATUS))
			|| !com.on.ebx.factory.wizard.RecentTasks.rerunnable(kind))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.recent.rerunRefused"));
			return;
		}
		final String raw = row.getString(FactoryPaths.FactoryJob.PARAMS);
		ObjectNode params;
		try
		{
			final com.fasterxml.jackson.databind.JsonNode parsed = ProjectWizardUserService.MAPPER.readTree(raw == null || raw.isEmpty() ? "{}" : raw);
			params = parsed instanceof ObjectNode ? (ObjectNode) parsed : ProjectWizardUserService.MAPPER.createObjectNode();
		}
		catch (final java.io.IOException ex)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.recent.rerunRefused"));
			return;
		}
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(this.session, this.factory, this.projectId, kind, params);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, queued.merged ? "wizard.recent.rerunMerged" : "wizard.recent.rerunQueued",
			com.on.ebx.factory.wizard.RecentTasks.kindLabel(kind, this.locale));
		this.noticeKind = "ok";
	}

	// ------------------------------------------------------------------------------------------------ step 1: project

	private void saveProject(final Posted pPosted) throws OperationException
	{
		final WizardRules.ProjectDraft draft = pPosted.projectDraft;
		final boolean exists = this.projectId != null && !this.projectId.isEmpty();
		// wizard-llm-value-sharing lot (2026-09-28): the snapshot is read for a NEW project too, for the
		// instance cap (WizardSnapshot#llmValueSharingMax) - a level the instance would not honour is refused
		// here, never silently lowered at save time
		final WizardSnapshot current = WizardData.readSnapshot(this.view(), this.factory, exists ? this.projectId : null, this.session);
		final String ownCode = exists && current.project != null ? current.project.code : null;
		final List<WizardRules.Problem> problems = WizardRules.projectProblems(draft, WizardData.existingProjectCodes(this.factory), ownCode, current.llmValueSharingMax);
		if (!problems.isEmpty())
		{
			this.problems.addAll(problems);
			this.currentStep = WizardRules.STEP_PROJECT;
			return;
		}
		final String id = WizardData.saveProject(this.session, this.factory, this.projectId, draft);
		this.projectId = id;
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, exists ? "wizard.notice.projectUpdated" : "wizard.notice.projectCreated");
		if (!exists)
		{
			// lot C1-B: the step after the project in the chosen style's own list (Sources for CONSOLIDATION, Pack for CENTRALISATION)
			this.currentStep = WizardRules.steps(draft.style)[1];
		}
	}

	// ------------------------------------------------------------------------------------------------ step 2: sources

	private void pickCatalogSource()
	{
		if (this.pickedCatalogSourceId.isEmpty())
		{
			this.problems.add(new WizardRules.Problem(WizardRules.STEP_SOURCES, "catalogSourceId", "wizard.error.catalogSourceRequired"));
			return;
		}
		// bug fixed live 2026-09-24 (project Vague1): this used to always read pickedCatalogSourceSchemas.get(0)
		// - EVERY schema of the picked catalog source, in whatever order WizardData.schemasOf returns them, not
		// the schema(s) the person actually ticked - so the suggested code came from an arbitrary, unrelated
		// schema (here "DATA_PRODUCTS") instead of the ticked "CRM_SOURCE". this.sourceDraft.schemas is the
		// posted, ticked subset (mdmfSchemas): prefer it, and only fall back to the full list when nothing is
		// ticked yet (a fresh catalog-source pick, before any checkbox has been touched).
		final List<String> tickedOrAll = this.sourceDraft.schemas.isEmpty() ? this.pickedCatalogSourceSchemas : this.sourceDraft.schemas;
		final WizardSnapshot snapshot = this.requestSnapshot();
		if (this.sourceDraft.connector.isEmpty())
		{
			this.sourceDraft.connector = WizardData.connectorOfCatalogSource(snapshot, this.pickedCatalogSourceId);
		}
		if (this.sourceDraft.sourceSystem.isEmpty())
		{
			// UX lot U2 (2026-10-13): a code is ALWAYS proposed. Measured live: a source with no schema (Salesforce) left the field
			// empty and the person had to invent one. ApplicationCodes falls back on the catalog source's label, the connection's
			// label, the connection id (a URL-like label only as a last resort), de-duplicated against the project's codes - and a
			// specific schema name still wins (ERP_SOURCE / CRM_SOURCE of one catalog source, volume-test bug of 2026-09-24).
			String catalogLabel = "";
			for (final WizardSnapshot.CatalogSourceInfo catalogSource : snapshot.catalogSources)
			{
				if (catalogSource.id.equals(this.pickedCatalogSourceId))
				{
					catalogLabel = catalogSource.label;
				}
			}
			this.sourceDraft.sourceSystem = ApplicationCodes.propose(tickedOrAll.isEmpty() ? "" : tickedOrAll.get(0), catalogLabel,
				WizardData.connectorLabelOf(this.factory.getHome().getRepository(), this.sourceDraft.connector), this.sourceDraft.connector,
				WizardData.existingSourceSystemCodes(this.factory, this.projectId));
		}
		// the trust order is set once, by the arrows of the side panel: a new application goes last (UX lot U2, 2026-10-13)
		this.sourceDraft.trustRank = WizardRules.nextTrustRank(WizardData.existingTrustRanks(this.factory, this.projectId));
	}

	private void addSource(final Posted pPosted) throws OperationException
	{
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		final List<WizardRules.Problem> problems = WizardRules.sourceProblems(pPosted.sourceDraft, WizardData.existingSourceSystemCodes(this.factory, this.projectId));
		if (!problems.isEmpty())
		{
			this.problems.addAll(problems);
			return;
		}
		// UX lot U2 (2026-10-13): no number is asked any more - the new application is appended last in the trust order
		pPosted.sourceDraft.trustRank = WizardRules.nextTrustRank(WizardData.existingTrustRanks(this.factory, this.projectId));
		final String newSourceId = WizardData.addSource(this.session, this.factory, this.projectId, pPosted.sourceDraft);
		// sources-landing lot SOURCES (2026-09-24): same "plan right away" rule as SourcesRun's own
		// addApplicationIfReady, for the catalog-pick path (the one way in that was never routed through
		// SourcesRun - it is already a single synchronous round trip with a person watching it, so it stays
		// that way; only the background paths needed queuing). A plan failure does not undo the add.
		try
		{
			LandingPlanner.plan(this.session, this.factory, newSourceId, false); // UX lot U2: the tables found start unticked
		}
		catch (final OperationException ex)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.sourceAdded") + " " + EngineNotice.inline(ex, this.locale));
			this.pickedCatalogSourceId = "";
			this.pickedCatalogSourceSchemas.clear();
			this.sourceDraft = new WizardRules.SourceDraft();
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.sourceAdded");
		this.pickedCatalogSourceId = "";
		this.pickedCatalogSourceSchemas.clear();
		this.sourceDraft = new WizardRules.SourceDraft();
	}

	private void removeSource(final Posted pPosted) throws OperationException
	{
		WizardData.removeSource(this.session, this.factory, pPosted.sourceId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.sourceRemoved");
	}

	/**
	 * UX lot U2 (2026-10-13): {@code TABLES_KEEP} - "Keep these tables" on a card, and "Continue to the landing" for every card: the ticks the
	 * person changed since the page was drawn, collected in the browser and sent in ONE action (a tick used to be a full round trip each:
	 * 15 unticks of the 17 Salesforce tables = 15 postbacks).
	 */
	private void keepTables(final Posted pPosted) throws OperationException
	{
		final List<String>[] ticks = SourceTableList.parseTicks(pPosted.tableTicks);
		if (ticks[0].isEmpty() && ticks[1].isEmpty())
		{
			return; // nothing changed since the page was drawn
		}
		final int[] counts = WizardData.setTablesSelected(this.session, this.factory, this.projectId, ticks[0], ticks[1]);
		if (counts[1] > 0)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.src2.notice.tablesFrozen", Integer.valueOf(counts[1])));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.src2.notice.tablesKept", Integer.valueOf(ticks[0].size() + ticks[1].size()));
	}

	/**
	 * UX lot U2 (2026-10-13): {@code SOURCE_ADD_APP} - a row of the single "Add an application" list was picked. The connection goes through {@link
	 * SourcesRun} (catalog source and scan when they are missing, then the application and its tables, the new tables unticked): one background job
	 * per connection, the cards of the applications in progress follow it. Designers only; a system that already is an application of the project is
	 * answered, never added twice ({@link SourcesRun} checks it too).
	 */
	private void addApplicationFromConnection(final Posted pPosted) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (pPosted.onboardConnector.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		// UX lot C1 (2026-10-13): a connection with no catalog operation (a REST source...) is read from its data - the existing deduced scan, which keeps
		// the person's earlier decisions on tables already found - instead of the catalog scan it does not have. The list was loaded when it was opened;
		// a stale session without it reads the connections again
		if (this.onboardConnections.isEmpty())
		{
			this.onboardRefreshConnections();
		}
		for (final SourceOnboarding.ConnectionOption option : this.onboardConnections)
		{
			if (option.id.equals(pPosted.onboardConnector) && AddApplicationOptions.usesDeducedScan(option))
			{
				final DeducedScanEntry.Result entry = DeducedScanEntry.start(this.session, this.factory, this.projectId, option.id);
				this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.notice.deducedStarted", entry.sourceSystem);
				this.currentStep = WizardRules.STEP_SOURCES;
				return;
			}
		}
		final ObjectNode result = SourcesRun.startById(this.session, this.factory, this.projectId, pPosted.onboardConnector, "", 100);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, result.path("merged").asBoolean(false) ? "wizard.sources.run.queued" : "wizard.sources.run.started",
			pPosted.onboardConnector);
		this.currentStep = WizardRules.STEP_SOURCES;
	}

	/** UX lot U2 (2026-10-13): {@code SOURCE_FIND_TABLES} - the plan of one application as a background job, its new tables unticked ({@link LandingSourceRun#startFindTables}). */
	private void findTables(final String pSourceId) throws OperationException
	{
		final ObjectNode result = LandingSourceRun.startFindTables(this.session, this.factory, pSourceId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, result.path("merged").asBoolean(false) ? "wizard.src2.notice.findQueued" : "wizard.src2.notice.findStarted");
	}

	/** UX lot U2 (2026-10-13): {@code SOURCE_RESCAN} - "Scan again" on any application card: the catalog scan of its connection, then the plan, one background job ({@link SourceRescanRun}). */
	private void rescanSource(final String pSourceId) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final ObjectNode result = SourceRescanRun.start(this.session, this.factory, pSourceId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, result.path("merged").asBoolean(false) ? "wizard.src2.notice.rescanQueued" : "wizard.src2.notice.rescanStarted");
	}

	/** trust-rank-edit lot (2026-09-23): {@code SOURCE_SET_TRUST} - see {@code WizardPage.renderStep2}'s own comment and {@link WizardData#setSourceTrustRank}. */
	private void setSourceTrustRank(final Posted pPosted) throws OperationException
	{
		if (pPosted.trustRankEdit < 1)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.field.trustRank.hint"));
			return;
		}
		WizardData.setSourceTrustRank(this.session, this.factory, pPosted.sourceId, pPosted.trustRankEdit);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.trustRankSaved", Integer.valueOf(pPosted.trustRankEdit));
	}

	/** Sources-landing lot SOURCES (2026-09-24): the Review screen's editable application code - {@code SOURCE_SET_CODE}. Validation (blank/pattern/length/uniqueness) happens inside {@link WizardData#setSourceCode} itself; a refusal is shown as a warning, the old code untouched. */
	private void setSourceCode(final Posted pPosted)
	{
		try
		{
			WizardData.setSourceCode(this.session, this.factory, this.projectId, pPosted.sourceId, pPosted.sourceCodeEdit);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.review.notice.codeSaved", pPosted.sourceCodeEdit.trim());
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/** Sources-landing lot SOURCES (2026-09-24): {@code SOURCE_TRUST_UP}/{@code SOURCE_TRUST_DOWN} - see {@link WizardData#reorderSourceTrust}'s own Javadoc for the "rewrite as 1..n" rule. */
	private void reorderSourceTrust(final Posted pPosted, final boolean pUp) throws OperationException
	{
		WizardData.reorderSourceTrust(this.session, this.factory, this.projectId, pPosted.sourceId, pUp);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.review.notice.trustReordered");
	}

	/** Sources-landing lot SOURCES (2026-09-24): "Remove" on a still-stuck queued application - {@code SOURCES_RUN_REMOVE}. Best-effort (see {@link SourcesRun#cancel}'s own Javadoc): never refuses, a card the poll already moved past is simply not found any more. */
	private void removeQueuedSource(final Posted pPosted)
	{
		final String projectCode = this.projectCode();
		if (projectCode != null)
		{
			SourcesRun.cancel(this.session, this.factory, projectCode, pPosted.removeConnectorId);
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.review.notice.stuckRemoved");
	}

	// ------------------------------------------------------------------------------------ step 2: deduced scan

	/** {@code DEDUCED_SCAN_CONFIRM} (board 2e): optionally renames the {@code Asset} first (mdmfAssetName, only if non-blank), then {@code CONFIRMED} + {@code scanConfirmedBy}/{@code At} - see {@code ebx.DeducedScanConfirm}'s own Javadoc. */
	private void deducedScanConfirm(final UserServiceEventContext pContext) throws OperationException
	{
		// audit D-07: mdmfId is the asset KEY (source:qualifiedName - quotes, spaces, colons...), posted from an escaped
		// attribute value: read raw, never trimmed (a name may end with a space), never parsed as a number
		final String assetKey = ProjectWizardUserService.paramRaw(pContext, "mdmfId");
		final String newName = ProjectWizardUserService.param(pContext, "mdmfAssetName");
		if (assetKey.trim().isEmpty())
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.deduced.notice.noAsset");
			this.noticeKind = "warn";
			return;
		}
		final Adaptation catalog = CatalogReader.dataset(this.factory.getHome().getRepository());
		if (catalog == null)
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step2.noCatalog");
			this.noticeKind = "warn";
			return;
		}
		try
		{
			// a rename moves the asset to the key of its new name: the confirmation goes to that key
			final String confirmedKey = newName.trim().isEmpty() ? assetKey : DeducedScanConfirm.rename(this.session, catalog, assetKey, newName.trim());
			DeducedScanConfirm.confirm(this.session, catalog, confirmedKey, this.session.getUserReference() == null ? "" : this.session.getUserReference().getUserId());
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.deduced.notice.confirmed");
			this.autoPlanLandingIfDeducedScanDone(catalog, confirmedKey);
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/** {@code DEDUCED_SCAN_LEAVE_OUT} (board 2e): marks the {@code Asset} {@code LEFT_OUT} - never loaded, never offered again unless a fresh deduced scan re-writes it. */
	private void deducedScanLeaveOut(final UserServiceEventContext pContext) throws OperationException
	{
		final String assetKey = ProjectWizardUserService.paramRaw(pContext, "mdmfId"); // audit D-07: see deducedScanConfirm
		if (assetKey.trim().isEmpty())
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.deduced.notice.noAsset");
			this.noticeKind = "warn";
			return;
		}
		final Adaptation catalog = CatalogReader.dataset(this.factory.getHome().getRepository());
		if (catalog == null)
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step2.noCatalog");
			this.noticeKind = "warn";
			return;
		}
		try
		{
			DeducedScanConfirm.leaveOut(this.session, catalog, assetKey, this.session.getUserReference() == null ? "" : this.session.getUserReference().getUserId());
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.deduced.notice.leftOut");
			this.autoPlanLandingIfDeducedScanDone(catalog, assetKey);
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/**
	 * Fix lot (2026-09-26): defect 2 - confirming (or leaving out) the LAST still-{@code TO_CONFIRM} deduced
	 * table of an application used to leave the wizard stuck: the application's card kept saying "No table yet"
	 * and step 3 stayed blocked, because nothing ever ran {@code LandingPlanner.plan} for it - the only thing
	 * that reads a confirmed table's {@code Column.primaryKey} and turns it into a real, tickable
	 * {@code LandingTable} row (the same call {@code POST /sources/{id}/landing/plan} makes, live-verified as
	 * the manual fix). Runs it automatically once every deduced {@code Asset} of the SAME application
	 * (same {@code Asset.source}) is decided (no longer {@code TO_CONFIRM}) - a table still pending elsewhere
	 * in the same application means there is more to decide first, so nothing runs yet. Best-effort: a failure
	 * here is logged, never surfaced over the confirm/leave-out notice the person just saw - they can still open
	 * step 3 and run "Prepare the landing" by hand, same fallback the manual REST call already was.
	 */
	private void autoPlanLandingIfDeducedScanDone(final Adaptation pCatalog, final String pAssetKey)
	{
		// audit F-02 (catalog reads): as the person who just decided (a table they cannot see is not "pending" for them; the
		// plan itself reads as them too)
		final CatalogReader.Reader reader = CatalogReader.Reader.of(this.session);
		final String catalogSourceId = DeducedScanCatalog.assetSource(reader, pCatalog, pAssetKey);
		if (catalogSourceId == null || catalogSourceId.isEmpty())
		{
			return;
		}
		if (!DeducedScanCatalog.pendingAssets(reader, pCatalog, catalogSourceId).isEmpty())
		{
			return; // another table of this same application is still TO_CONFIRM - not done yet
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			return;
		}
		final WizardSnapshot snapshot = WizardData.readSnapshot(this.view(), this.factory, this.projectId, this.session);
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			if (catalogSourceId.equals(source.catalogSourceId))
			{
				try
				{
					LandingPlanner.plan(this.session, this.factory, source.id, NewTableSelection.selectNew(null)); // UX lot C1: found-from-the-data tables start unticked too
				}
				catch (final OperationException ex)
				{
					LoggingCategory.getKernel().warn("[mdm-factory] auto landing-plan after deduced-scan decision failed for project source " + source.id + ": "
						+ ex.getMessage());
				}
				return;
			}
		}
	}

	// ------------------------------------------------------------------------------------ step 2: source onboarding

	/**
	 * {@code SOURCE_ONBOARD_OPEN}: the panel's own title button - the ONLY places {@code
	 * ebx.SourceOnboarder#connections} is ever called are this method and {@link #onboardRefresh} (coordinator
	 * review, 2026-09-23: a plain render must never pay for a live {@code connectors_list} round trip).
	 */
	private void onboardOpenPanel()
	{
		this.onboardOpen = true;
		this.onboardRefreshConnections();
	}

	/** {@code SOURCE_ONBOARD_CLOSE}: no MCP call, no toast - just resets the panel's open flag and whatever diagnosis was showing, so a later re-open starts fresh. */
	private void onboardClosePanel()
	{
		this.onboardOpen = false;
		this.onboardConnectorId = "";
		this.onboardConnectorDisplay = "";
		this.onboardDiagnosis = null;
		this.onboardScanMessage = null;
	}

	private void onboardRefreshConnections()
	{
		this.onboardConnections.clear();
		this.onboardConnectionsError = null;
		try
		{
			this.onboardConnections.addAll(SourceOnboarder.connections(this.session, this.factory, true)); // UX lot U2: with the table counts of the single "Add an application" list
		}
		catch (final OperationException ex)
		{
			this.onboardConnectionsError = EngineNotice.inline(ex, this.locale);
		}
	}

	/** {@code SOURCE_ONBOARD_PICK}: a connection was picked in the onboarding combobox - read its facts/diagnosis fresh, the same round trip {@link #pickCatalogSource} uses for the catalog-source combobox. */
	private void onboardPick(final Posted pPosted)
	{
		if (pPosted.onboardConnector.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		this.onboardConnectorId = pPosted.onboardConnector;
		this.onboardRefreshDiagnosis();
	}

	/** {@code SOURCE_ONBOARD_REFRESH}: re-reads the connection list AND the currently picked connection's facts/diagnosis - the checklist's own "Refresh" button, so a connection created meanwhile in Connectors shows up too (coordinator review, 2026-09-23). */
	private void onboardRefresh(final Posted pPosted)
	{
		this.onboardRefreshConnections();
		if (!pPosted.onboardConnector.isEmpty())
		{
			this.onboardConnectorId = pPosted.onboardConnector;
		}
		if (this.onboardConnectorId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		this.onboardRefreshDiagnosis();
	}

	private void onboardRefreshDiagnosis()
	{
		final SourceOnboarding.Facts facts = SourceOnboarder.facts(this.session, this.factory, this.onboardConnectorId);
		this.onboardDiagnosis = SourceOnboarding.diagnose(facts);
		this.onboardConnectorDisplay = facts.connectorLabel == null || facts.connectorLabel.isEmpty() ? this.onboardConnectorId : facts.connectorLabel;
	}

	/** Job-engine lot C3 (2026-09-24): whether a {@link #onboardPendingKind} job is still QUEUED or RUNNING for this project - see that field's own Javadoc. */
	private boolean onboardStepStillPending()
	{
		if (this.projectId == null || this.projectId.isEmpty())
		{
			return false;
		}
		for (final Adaptation job : FactoryJobQueue.active(this.factory, this.projectId))
		{
			if (this.onboardPendingKind.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				return true;
			}
		}
		return false;
	}

	/** Volume-operations lot 8b (2026-09-28, task 3): whether a {@link #stepResetPendingKind} job is still QUEUED or RUNNING for this project - same pattern as {@link #onboardStepStillPending}. */
	private boolean stepResetRunRunning()
	{
		if (this.projectId == null || this.projectId.isEmpty())
		{
			return false;
		}
		for (final Adaptation job : FactoryJobQueue.active(this.factory, this.projectId))
		{
			if (this.stepResetPendingKind.equals(job.getString(FactoryPaths.FactoryJob.KIND)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * {@code SOURCE_ONBOARD_CATALOG}: administrators only, re-checked here (the button is already disabled
	 * otherwise, same defence every other agent-account action in this class already applies). Job-engine lot C3
	 * (2026-09-24): no longer writes the catalog source synchronously - queues it ({@link OnboardStepRun},
	 * resource {@link com.on.ebx.factory.ebx.FactoryJobs#SOURCES}) and returns at once; the checklist re-reads
	 * fresh once the job is no longer pending (see {@link #onboardPendingKind}'s own Javadoc).
	 */
	private void onboardCatalog(final Posted pPosted) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (!pPosted.onboardConnector.isEmpty())
		{
			this.onboardConnectorId = pPosted.onboardConnector;
		}
		if (this.onboardConnectorId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		final ObjectNode result = OnboardStepRun.startCatalog(this.session, this.factory, this.projectId, this.onboardConnectorId);
		this.onboardPendingKind = OnboardStepRun.CATALOG_STEP;
		final String display = this.onboardConnectorDisplay.isEmpty() ? this.onboardConnectorId : this.onboardConnectorDisplay;
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
			result.path("merged").asBoolean(false) ? "wizard.onboard.notice.catalogQueued" : "wizard.onboard.notice.catalogStarted", display);
	}

	/**
	 * {@code SOURCE_ONBOARD_SCAN}: administrators only, re-checked here. Job-engine lot C3 (2026-09-24): no
	 * longer runs the catalog scan synchronously - queues it ({@link OnboardStepRun}, resource {@link
	 * com.on.ebx.factory.ebx.FactoryJobs#SOURCES}) and returns at once; the checklist (and the scan's own
	 * outcome text, {@code SourceOnboarding.Facts#scanMessage}) re-reads fresh once the job is no longer
	 * pending (see {@link #onboardPendingKind}'s own Javadoc).
	 */
	private void onboardScan(final Posted pPosted) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (!pPosted.onboardConnector.isEmpty())
		{
			this.onboardConnectorId = pPosted.onboardConnector;
		}
		if (this.onboardConnectorId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		final ObjectNode result = OnboardStepRun.startScan(this.session, this.factory, this.projectId, this.onboardConnectorId);
		this.onboardPendingKind = OnboardStepRun.SCAN_STEP;
		final String display = this.onboardConnectorDisplay.isEmpty() ? this.onboardConnectorId : this.onboardConnectorDisplay;
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
			result.path("merged").asBoolean(false) ? "wizard.onboard.notice.scanQueued" : "wizard.onboard.notice.scanStarted", display);
	}

	/**
	 * {@code SOURCE_ONBOARD_USE}: the onboarded source is ready ({@code Diagnosis.ready}) - reuses
	 * {@link #pickCatalogSource}'s own logic (task brief) to pre-pick it in the "add an application" form
	 * exactly as if it had come from the catalog-source combobox, then surfaces the "now in the catalog"
	 * notice, and closes the panel via {@link #onboardClosePanel} (also resetting {@link #onboardOpen}, per
	 * the coordinator review). {@code wizard.js} scrolls to the add form instead of the top of the page (its
	 * own {@code SOURCE_ONBOARD_USE} override) - never {@code window.scrollTo} (CLAUDE.md &sect;15).
	 */
	private void onboardUse()
	{
		if (this.onboardDiagnosis == null || !this.onboardDiagnosis.ready || this.onboardDiagnosis.pickSourceId == null)
		{
			return;
		}
		// UX lot U2 (2026-10-13): measured live, "Use this source" on a system that was already an application of the project showed the add
		// form again - a second application for the same system could be created. The panel does not offer it any more (see
		// WizardPage#renderOnboardNextAction); a stale click is answered here too.
		final String[] existing = WizardData.applicationOfCatalogSource(this.factory, this.projectId, this.onboardDiagnosis.pickSourceId);
		if (existing != null)
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.src2.notice.alreadyApplication", existing[1]);
			return;
		}
		this.pickedCatalogSourceId = this.onboardDiagnosis.pickSourceId;
		this.pickedCatalogSourceSchemas.clear();
		this.pickedCatalogSourceSchemas.addAll(WizardData.schemasOf(CatalogReader.Reader.of(this.session), this.factory, this.pickedCatalogSourceId));
		this.pickCatalogSource();
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.notice.picked", this.pickedCatalogSourceId);
		this.onboardClosePanel();
	}

	/**
	 * Fix lot (2026-09-25), bug 1: {@code SOURCE_ONBOARD_DEDUCED} - the checklist's own next action when the
	 * picked connection has no catalog operation but at least one read-only listing operation ({@link
	 * SourceOnboarding.Action#DEDUCED_SCAN}). One click, through {@link DeducedScanEntry#start}: creates the
	 * application (same code/trust-rank rules as the official "add an application" path) AND queues the
	 * deduced-scan job on it, then closes the panel exactly like {@link #onboardUse()} does for the official
	 * path's own "Use this source" - the project's step-2 board picks up the freshly-queued job's four-stage
	 * pipeline (board 2d) on its own next poll, no extra state to carry here.
	 */
	private void onboardDeduced(final Posted pPosted) throws OperationException
	{
		if (!pPosted.onboardConnector.isEmpty())
		{
			this.onboardConnectorId = pPosted.onboardConnector;
		}
		if (this.onboardConnectorId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		final DeducedScanEntry.Result result = DeducedScanEntry.start(this.session, this.factory, this.projectId, this.onboardConnectorId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.notice.deducedStarted", result.sourceSystem);
		this.onboardClosePanel();
		// fix lot (2026-09-26): a fresh application with a deduced scan queued has ZERO landing tables yet, which
		// used to make WizardRules.suggestedStep jump straight to Landing (see that method's own fix comment) -
		// stay on Sources explicitly, same defensive pattern saveProject already uses for its own step move, so
		// the person keeps watching the pipeline card (board 2d) they just started, not an empty Landing step.
		this.currentStep = WizardRules.STEP_SOURCES;
	}

	/**
	 * sample-files lot (2026-09-23); sources-landing lot SOURCES (2026-09-24): {@code SOURCE_ONBOARD_ALL} -
	 * "Do everything" on the guided panel. Administrators only, re-checked here like every other write action
	 * in this class. USED to chain {@code SourceOnboarder#onboardAll} synchronously on this request thread and
	 * pre-pick the source once ready; now only QUEUES the connector id into {@link SourcesRun} and returns at
	 * once - the catalog+scan chain, and the auto-add-on-ready it used to trigger via {@link #onboardUse()},
	 * both run on {@code SourcesRun}'s own background thread (see {@code SourcesRun#addApplicationIfReady}, the
	 * direct replacement for {@link #onboardUse()} in this flow - there is no form left to pre-fill, nobody is
	 * watching this request once it returns). The wizard's "applications in progress" cards ({@link #model()},
	 * fed by {@link SourcesRun#status}) carry the real progress from here on - same "queue and return, poll for
	 * the rest" shape as {@link #startProposalRun}.
	 */
	private void onboardAll(final Posted pPosted) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (!pPosted.onboardConnector.isEmpty())
		{
			this.onboardConnectorId = pPosted.onboardConnector;
		}
		if (this.onboardConnectorId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.onboard.error.connectorRequired"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		// job-engine lot C1 (2026-09-24): every request now goes through FactoryJobQueue, so "started" no longer
		// means "ran synchronously" - "merged" (an identical request was already QUEUED) is the closest read of
		// the old "queued=true" meaning ("something is already in progress, this one waits its turn").
		final ObjectNode result = SourcesRun.startById(this.session, this.factory, this.projectId, this.onboardConnectorId, "", 100);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
			result.path("merged").asBoolean(false) ? "wizard.sources.run.queued" : "wizard.sources.run.started", this.onboardConnectorId);
	}

	/**
	 * sample-files lot (2026-09-23): {@code SOURCE_FILES_IMPORT} - the "Sources from sample files" panel's one
	 * submit. Administrators only (the button is already disabled otherwise - same defence in depth as every
	 * other write action here). {@code pPosted.sampleFilesRaw} empty/unparsable while the action fired at all
	 * is the task brief's own "server guard": {@code wizard.js} only ever clicks this button after filling the
	 * hidden textarea with at least one file, so an empty parse here means the textarea's content did not reach
	 * the server - most likely the POST was too large for the connector's own size limit (CLAUDE.md's own
	 * "measured facts" note: no reliable way to read that limit from inside EBX, so the notice can only name
	 * this class's own conservative cap, not Tomcat's real one).
	 */
	private void sourceFilesImport(final Posted pPosted) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		final List<SampleSourceImporter.FileInput> files = SampleSourceImporter.parseFileInputs(pPosted.sampleFilesRaw);
		this.sampleFilesError = null;
		if (files.isEmpty())
		{
			this.sampleFilesError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step2.sampleFiles.error.empty",
				String.valueOf(SampleFileRules.RAW_BYTES_CAP / 1024));
			this.sampleFilesReport.clear();
			return;
		}
		final SampleSourceImporter.Report report = SampleSourceImporter.importFiles(this.session, this.factory, this.projectId, files);
		this.sampleFilesReport.clear();
		this.sampleFilesReport.addAll(report.files);
	}

	// ------------------------------------------------------------------------------------------------ step 3: landing

	/**
	 * Job-engine lot C3 (2026-09-24): no longer plans synchronously - queues it ({@link LandingSourceRun},
	 * resource {@link com.on.ebx.factory.ebx.FactoryJobs#LANDING}, the SAME resource the one-click {@link
	 * LandingRun} uses, so the two never race) and returns at once; the jobs banner follows it and the counts
	 * this notice used to show now live in the job's own result message instead ({@code FactoryJob.message}).
	 */
	private void plan(final String pSourceId) throws OperationException
	{
		final ObjectNode result = LandingSourceRun.startPlan(this.session, this.factory, pSourceId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
			result.path("merged").asBoolean(false) ? "wizard.step3.notice.planQueued" : "wizard.step3.notice.planStarted");
	}

	/**
	 * Administrators only, re-checked here (the button is already disabled otherwise). Job-engine lot C3
	 * (2026-09-24): no longer runs generate, then the flows, then the load synchronously in this one request -
	 * queues the whole chain as one job ({@link LandingSourceRun}, resource {@link
	 * com.on.ebx.factory.ebx.FactoryJobs#LANDING}, the SAME resource the one-click {@link LandingRun} uses) and
	 * returns at once; the jobs banner follows it. A second click is still harmless for the same reasons as
	 * before ({@link LandingGenerator#generate} skips a {@code PipelineStep} already {@code DONE}; {@link
	 * LandingFlows#load} is meant to be repeated), and an identical still-QUEUED request folds into it ({@link
	 * FactoryJobQueue#enqueue}). {@link LandingFlows#generate} is always called with {@code force = true}
	 * inside the job (read-recipe lot, 2026-09-24 - see the OLD version of this method's own Javadoc, kept on
	 * {@link LandingSourceRun#GENERATE_HANDLER}, for why). The partial-failure/success distinction this method
	 * used to draw with {@code warn()}/{@code notice} now lives in the job's own outcome ({@link
	 * FactoryJobQueue.ExpectedFailure} on a partial failure - a routine, expected outcome, not a hard crash).
	 */
	private void generate(final String pSourceId) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final ObjectNode result = LandingSourceRun.startGenerate(this.session, this.factory, pSourceId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
			result.path("merged").asBoolean(false) ? "wizard.step3.notice.generateQueued" : "wizard.step3.notice.generateStarted");
	}

	/**
	 * Data-sample lot: a live {@code connectors_call read} on the table's own source object, small {@code max}
	 * ({@link LandingPreview#DEFAULT_MAX}) - see {@link LandingPreview}'s own Javadoc for why this is the only
	 * way to show real values for a file-based connector. Never throws (the caller's {@code handle} would
	 * treat it as a hard failure and abandon the render, but a preview that could not be read is exactly the
	 * kind of thing {@link WizardPage.Model#previewError} is for): {@link LandingPreview#sample} already
	 * reports its own failure in {@code Result.error} instead of throwing.
	 */
	private void preview(final String pTableId)
	{
		this.previewTableId = pTableId;
		this.previewColumns.clear();
		this.previewRows.clear();
		final LandingPreview.Result result = LandingPreview.sample(this.session, this.factory, pTableId, LandingPreview.DEFAULT_MAX);
		this.previewColumns.addAll(result.columns);
		this.previewRows.addAll(result.rows);
		this.previewError = result.error;
	}

	/**
	 * Landing key-study lot (2026-09-23): {@link KeyStudy#study} - a live sample, the evidence on it, and the
	 * language model's proposal when the session is an administrator's and a connector is configured. The
	 * report is kept on the instance for the panel and for {@link #applyKeyStudy}; the study itself writes
	 * nothing. A failure of the sample or of the model is part of the report, not a hard failure of the page.
	 */
	private void keyStudy(final String pTableId) throws OperationException
	{
		// audit F-34 (wave 10): with the language model in the study it is a queued job (KeyStudyRun); the report is taken at a later display (pickUpKeyStudies)
		if (this.projectId != null && !this.projectId.isEmpty() && KeyStudyRun.usesLanguageModel(this.session, this.factory))
		{
			KeyStudyRun.start(this.session, this.factory, this.projectId, pTableId, KeyStudyRun.SLOT_PANEL);
			this.keyStudyPendingTableId = pTableId;
			this.keyStudy = null;
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.keyStudy.queued");
			return;
		}
		this.keyStudy = KeyStudy.study(this.session, this.factory, pTableId);
		// the plain preview of the same table would now show the same rows twice
		if (pTableId.equals(this.previewTableId))
		{
			this.previewTableId = "";
		}
	}

	/**
	 * Audit F-34 (wave 10): takes the studies the queued {@link KeyStudyRun} jobs finished for this person ({@link KeyStudyRun#take}) and shows them where the
	 * synchronous study used to put them. A study whose job is over with no report (it failed, or the server restarted) is said once, with a pointer to
	 * "Recent tasks"; one whose job is still waiting or running is simply not there yet.
	 */
	private void pickUpKeyStudies()
	{
		if (this.keyStudyPendingTableId.isEmpty() && this.landingKeyChooserPendingTableId.isEmpty())
		{
			return;
		}
		final String user = com.on.ebx.factory.ebx.Actor.current(this.session).userId;
		if (!this.keyStudyPendingTableId.isEmpty())
		{
			final KeyStudyReport report = KeyStudyRun.take(this.projectId, user, KeyStudyRun.SLOT_PANEL, this.keyStudyPendingTableId);
			if (report != null)
			{
				this.keyStudy = report;
				if (this.keyStudyPendingTableId.equals(this.previewTableId))
				{
					this.previewTableId = "";
				}
				this.keyStudyPendingTableId = "";
			}
			else if (!KeyStudyRun.active(this.factory, this.projectId))
			{
				this.keyStudyPendingTableId = "";
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.keyStudy.noResult"));
			}
		}
		if (!this.landingKeyChooserPendingTableId.isEmpty())
		{
			final KeyStudyReport report = KeyStudyRun.take(this.projectId, user, KeyStudyRun.SLOT_CHOOSER, this.landingKeyChooserPendingTableId);
			if (report != null)
			{
				this.landingKeyChooserTableId = this.landingKeyChooserPendingTableId;
				this.landingKeyChooser = report;
				this.landingKeyChooserPendingTableId = "";
			}
			else if (!KeyStudyRun.active(this.factory, this.projectId))
			{
				this.landingKeyChooserPendingTableId = "";
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.keyStudy.noResult"));
			}
		}
	}

	/** "Apply this key": the report's proposal becomes the table's key ({@code sourceKeyOrigin} LLM or RULE), then the panel stays open showing the picker updated. */
	private void applyKeyStudy(final String pTableId) throws OperationException
	{
		if (this.keyStudy == null || !pTableId.equals(this.keyStudy.tableId))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.keyStudyStale"));
			return;
		}
		KeyStudy.apply(this.session, this.factory, this.keyStudy);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.keyApplied", this.keyStudy.tableName,
			this.keyStudy.proposal.columnsCsv().replace(",", ", "));
	}

	// ------------------------------------------------------------------------------------------------ landing-walkthrough lot (2026-10-16)

	/** The ticks the page carried along with a walkthrough button (the same one-write path as step 2's "Keep these tables"); nothing when none changed. */
	private void keepWalkTicks(final Posted pPosted) throws OperationException
	{
		final List<String>[] ticks = SourceTableList.parseTicks(pPosted.tableTicks);
		if (!ticks[0].isEmpty() || !ticks[1].isEmpty())
		{
			final int[] counts = WizardData.setTablesSelected(this.session, this.factory, this.projectId, ticks[0], ticks[1]);
			if (counts[1] > 0)
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.src2.notice.tablesFrozen", Integer.valueOf(counts[1])));
			}
		}
	}

	/** The choice of the language-model radio of the page being left, kept for the run (blank = the page showed none: the choice is unchanged). */
	private void rememberWalkLlm(final Posted pPosted, final String pSourceId)
	{
		if (pSourceId == null || pSourceId.isEmpty() || pPosted.walkLlm.isEmpty())
		{
			return;
		}
		if ("yes".equals(pPosted.walkLlm))
		{
			this.walkLlmYes.add(pSourceId);
		}
		else
		{
			this.walkLlmYes.remove(pSourceId);
		}
	}

	/** Whether the explicit language-model consent was given on the page of this application ({@code Yes}); the default is always No. */
	private boolean walkLlmAllowed(final String pSourceId)
	{
		return this.walkLlmYes.contains(pSourceId);
	}

	private void closeWalkPanels()
	{
		this.keyStudy = null;
		this.keyStudyPendingTableId = "";
		this.landingKeyChooserTableId = "";
		this.landingKeyChooserPendingTableId = "";
		this.landingKeyChooser = null;
	}

	/** The actions of step 3's walkthrough: {@code LANDING_WALK_GOTO/REVIEW/CLOSE/VALIDATE/STUDY/STUDY_APPLY/KEY_CHOOSE/KEY_APPLY/LAUNCH}. */
	private void landingWalk(final UserServiceEventContext pContext, final String pAction, final Posted pPosted) throws OperationException
	{
		final String sourceId = this.walkSourceOf(pPosted);
		if ("LANDING_WALK_REVIEW".equals(pAction))
		{
			this.walkReview = true;
			this.walkPage = "";
			this.closeWalkPanels();
			return;
		}
		if ("LANDING_WALK_CLOSE".equals(pAction))
		{
			this.walkReview = false;
			this.closeWalkPanels();
			return;
		}
		if ("LANDING_WALK_GOTO".equals(pAction))
		{
			this.keepWalkTicks(pPosted);
			this.rememberWalkLlm(pPosted, sourceId);
			this.walkPage = pPosted.walkTarget;
			this.closeWalkPanels();
			return;
		}
		if ("LANDING_WALK_LAUNCH".equals(pAction))
		{
			this.launchFromWalk();
			return;
		}
		this.keepWalkTicks(pPosted);
		this.rememberWalkLlm(pPosted, sourceId);
		if ("LANDING_WALK_VALIDATE".equals(pAction))
		{
			final WizardSnapshot snapshot = WizardData.readSnapshot(this.view(), this.factory, this.projectId, this.session);
			final WizardSnapshot.SourceInfo source = LandingWalk.source(snapshot, sourceId);
			if (source == null)
			{
				return;
			}
			final String reason = LandingWalk.validateReasonKey(source);
			if (reason != null)
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, reason));
				return;
			}
			WizardData.setLandingValidated(this.session, this.factory, this.projectId, sourceId, true);
			this.walkPage = LandingWalk.neighbour(snapshot, sourceId, 1);
			this.closeWalkPanels();
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.walk.notice.validated", LandingWalk.name(snapshot, source));
			return;
		}
		if ("LANDING_WALK_STUDY".equals(pAction))
		{
			// "Check now on 25 rows": the language model takes part only when this application's page said Yes
			this.landingKeyChooserTableId = "";
			this.landingKeyChooser = null;
			if (this.walkLlmAllowed(sourceId))
			{
				this.keyStudy(pPosted.tableId);
			}
			else
			{
				this.keyStudy = KeyStudy.study(this.session, this.factory, pPosted.tableId, false);
			}
			return;
		}
		if ("LANDING_WALK_STUDY_APPLY".equals(pAction))
		{
			if (this.keyStudy == null || !pPosted.tableId.equals(this.keyStudy.tableId) || this.keyStudy.proposal == null)
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.keyStudyStale"));
				return;
			}
			// applied as the person's own choice (origin USER): a click is a decision, whoever proposed the columns
			final String columns = this.keyStudy.proposal.columnsCsv();
			WizardData.setTableKey(this.session, this.factory, pPosted.tableId, columns);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.walk.notice.keySaved", columns.replace(",", ", "));
			this.keyStudy = null;
			return;
		}
		if ("LANDING_WALK_KEY_CHOOSE".equals(pAction))
		{
			this.keyStudy = null;
			final KeyStudyReport runProposal = KeyStudyRun.runProposal(pPosted.tableId, System.currentTimeMillis());
			this.landingKeyChooserTableId = pPosted.tableId;
			this.landingKeyChooser = runProposal != null ? runProposal : KeyStudy.study(this.session, this.factory, pPosted.tableId, this.walkLlmAllowed(sourceId));
			return;
		}
		if ("LANDING_WALK_KEY_APPLY".equals(pAction))
		{
			if (pPosted.keyColumns.trim().isEmpty())
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.walk.key.chooseColumn"));
				return;
			}
			WizardData.setTableKey(this.session, this.factory, pPosted.tableId, pPosted.keyColumns);
			KeyStudyRun.forgetRunProposal(pPosted.tableId);
			this.landingKeyChooserTableId = "";
			this.landingKeyChooser = null;
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.walk.notice.keySaved", pPosted.keyColumns.replace(",", ", "));
		}
	}

	/** The application a walkthrough button is about: the posted source, else the one the posted table belongs to. */
	private String walkSourceOf(final Posted pPosted)
	{
		if (!pPosted.sourceId.isEmpty())
		{
			return pPosted.sourceId;
		}
		return "";
	}

	/** "Prepare the landing" of the launch page: only with every application validated, the language model only for the applications that said Yes. */
	private void launchFromWalk() throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, snapshot.designer);
		if (!gates.generateLanding.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gates.generateLanding.reasonKey));
			return;
		}
		final String reason = LandingWalk.launchReasonKey(snapshot);
		if (reason != null)
		{
			final String first = LandingWalk.firstToValidate(snapshot);
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, reason, first == null ? "" : LandingWalk.name(snapshot, LandingWalk.source(snapshot, first))));
			return;
		}
		final java.util.List<String> llmSources = new ArrayList<String>();
		for (final WizardSnapshot.SourceInfo source : snapshot.sources)
		{
			if (this.walkLlmYes.contains(source.id) && LandingWalk.tablesWithUncertainKey(source) > 0)
			{
				llmSources.add(source.id);
			}
		}
		final ObjectNode result = LandingRun.start(this.session, this.factory, snapshot.project == null ? null : snapshot.project.code, !llmSources.isEmpty(), false, null, llmSources);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.run.refused")));
			return;
		}
		this.walkReview = false;
	}

	/**
	 * Sources+landing redesign, lot LANDING (2026-09-24): the one-click "Prepare the landing" button - starts
	 * {@link LandingRun} in the background and returns at once; the page then re-renders into the "while
	 * running" state ({@link #model()} reads {@link LandingRun#status} fresh on every render). Same
	 * {@code generateLanding} gate every other landing action re-validates server-side.
	 */
	private void startLandingRun(final UserServiceEventContext pContext) throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, snapshot.designer);
		if (!gates.generateLanding.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gates.generateLanding.reasonKey));
			return;
		}
		final String projectCode = snapshot.project == null ? null : snapshot.project.code;
		final boolean useLlm = ProjectWizardUserService.paramBool(pContext, "mdmfLandingRunUseLlm");
		// volume wave 2, lot V2-E: the "Full refresh" checkbox of a re-run (blank, so false, when the checkbox is not on the page: a first run is full anyway)
		final boolean fullRefresh = ProjectWizardUserService.paramBool(pContext, "mdmfLandingRunFullRefresh");
		final ObjectNode result = LandingRun.start(this.session, this.factory, projectCode, useLlm, fullRefresh);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.run.refused")));
			return;
		}
		// live-progress lot (2026-09-26): no "The landing run has started." notice any more - the running panel
		// itself says so, with what runs now; a third banner above it only repeated it
	}

	/**
	 * UX lot D (2026-10-14): "Retry the 2 failed tables" - the landing run again, for the tables the latest landing job reported as failed and that are still not
	 * loaded. The list is recomputed here from the job queue and the tables' own status, never taken from the page (a table loaded since by another route is
	 * left out; a stale page cannot ask for a table that was never part of the failure). Same {@code generateLanding} gate as every landing run.
	 */
	private void retryFailedLandingTables(final UserServiceEventContext pContext) throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, snapshot.designer);
		if (!gates.generateLanding.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gates.generateLanding.reasonKey));
			return;
		}
		final List<String> ids = new ArrayList<String>();
		for (final com.on.ebx.factory.wizard.PartialFailures.Item item : com.on.ebx.factory.wizard.PartialFailures.stillMissing(
			this.asUser(() -> com.on.ebx.factory.ebx.FactoryJobStatus.lastOutcomes(this.factory, this.projectId)).get(com.on.ebx.factory.wizard.PartialFailures.LANDING),
			com.on.ebx.factory.wizard.PartialFailures.unloadedTickedTables(snapshot)))
		{
			ids.add(item.id);
		}
		if (ids.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.partial.landing.nothingToRetry"));
			return;
		}
		final String projectCode = snapshot.project == null ? null : snapshot.project.code;
		final ObjectNode result = LandingRun.start(this.session, this.factory, projectCode, ProjectWizardUserService.paramBool(pContext, "mdmfLandingRunUseLlm"), false, ids);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.run.refused")));
		}
	}

	private void stopLandingRun() throws OperationException
	{
		final String projectCode = this.projectCode();
		if (projectCode == null)
		{
			return;
		}
		LandingRun.stop(this.session, this.factory, projectCode);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.run.stopRequested");
	}

	/** The key chooser panel under a WAITING (or any) table's row in the running grid - a fresh, un-applied study, same shape as {@link #keyStudy}/{@link #applyKeyStudy}. */
	private void chooseLandingKey(final String pTableId) throws OperationException
	{
		// 2026-10-07: the landing run already studied this table and left its proposal waiting for a person - open on it (pre-ticked), no second study
		final KeyStudyReport runProposal = KeyStudyRun.runProposal(pTableId, System.currentTimeMillis());
		if (runProposal != null)
		{
			this.landingKeyChooserPendingTableId = "";
			this.landingKeyChooserTableId = pTableId;
			this.landingKeyChooser = runProposal;
			return;
		}
		// audit F-34 (wave 10): queued when the language model takes part, like keyStudy above
		if (this.projectId != null && !this.projectId.isEmpty() && KeyStudyRun.usesLanguageModel(this.session, this.factory))
		{
			KeyStudyRun.start(this.session, this.factory, this.projectId, pTableId, KeyStudyRun.SLOT_CHOOSER);
			this.landingKeyChooserPendingTableId = pTableId;
			this.landingKeyChooserTableId = "";
			this.landingKeyChooser = null;
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.keyStudy.queued");
			return;
		}
		this.landingKeyChooserTableId = pTableId;
		this.landingKeyChooser = KeyStudy.study(this.session, this.factory, pTableId);
	}

	/** "Use this key and continue": writes the key and resumes the run for that table's source - {@link LandingRun#applyKeyAndResume}. */
	private void applyLandingKey(final String pTableId, final String pColumnsCsv) throws OperationException
	{
		final WizardSnapshot snapshot = WizardData.readSnapshot(this.view(), this.factory, this.projectId, this.session);
		if (snapshot.project == null)
		{
			return;
		}
		LandingRun.applyKeyAndResume(this.session, this.factory, snapshot.project.code, pTableId, pColumnsCsv);
		KeyStudyRun.forgetRunProposal(pTableId);
		this.landingKeyChooserTableId = "";
		this.landingKeyChooser = null;
		// the chooser can be opened from the walkthrough's page of an application (a table the last run left waiting for its key, 2026-10-17): the run goes on, so
		// the person returns to its view (the walkthrough would otherwise stay on screen over a running landing)
		this.walkReview = false;
		this.landingKeyAppliedMessage = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.run.chooser.applied");
	}

	/**
	 * "Change the key..." on an already-loaded table - {@link LandingKeyChange#change}, composite-key lot. Audit F-35 (wave 5): it now only validates
	 * ({@link LandingKeyChange#refusal}, a refusal is a hint, never a page-abandoning exception) and queues {@link LandingKeyChangeRun}: deleting a big
	 * landing table's rows no longer happens inside this request. The jobs banner follows it; a second click while one is queued or running is refused.
	 */
	private void changeLandingKey(final String pTableId, final String pColumnsCsv) throws OperationException
	{
		final WizardSnapshot snapshot = WizardData.readSnapshot(this.view(), this.factory, this.projectId, this.session);
		if (snapshot.project == null)
		{
			return;
		}
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.landingKeyChangeMessage = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly");
			return;
		}
		final List<String> columns = LandingKeyChangeRun.splitColumns(pColumnsCsv);
		final String refusal = LandingKeyChange.refusal(this.factory, snapshot.project.code, pTableId, columns);
		if (refusal != null)
		{
			this.landingKeyChangeMessage = refusal;
			return;
		}
		if (LandingKeyChangeRun.isActive(this.factory, this.projectId, pTableId))
		{
			this.landingKeyChangeMessage = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.keyChange.alreadyActive");
			return;
		}
		LandingKeyChangeRun.start(this.session, this.factory, this.projectId, snapshot.project.code, pTableId, columns);
		this.landingKeyChangeTableId = "";
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step3.keyChange.queued");
	}

	// ------------------------------------------------------------------------------------------------ lot C1-B: CENTRALISATION

	/** Lot C1-B: the packs ticked on the Pack step, the options, and the model's last suggestion - plain instance state, rebuilt by the actions below. */
	private String accelNeed = "";
	private final List<String> accelTickedPacks = new ArrayList<String>();
	private final List<String> accelTickedOptions = new ArrayList<String>();
	private List<String> accelSuggestedPacks = new ArrayList<String>();
	private String accelWhy = "";
	private boolean accelFromModel;

	private static List<String> csv(final String pValue)
	{
		final List<String> out = new ArrayList<String>();
		for (final String one : pValue.split(","))
		{
			if (!one.trim().isEmpty() && !out.contains(one.trim()))
			{
				out.add(one.trim());
			}
		}
		return out;
	}

	/**
	 * Lot C1-B: the six {@code ACCEL_*} actions. Nothing is decided here: the need and the packs are written by {@code AccelWizard}, the
	 * passes and the generation are the hooks of sub-lots C and D (a hook not filled yet answers the notice {@code wizard.accel.notAvailable}).
	 */
	private void accelAction(final UserServiceEventContext pContext, final String pAction) throws OperationException
	{
		this.accelNeed = ProjectWizardUserService.param(pContext, "mdmfAccelNeed");
		final List<String> packs = ProjectWizardUserService.csv(ProjectWizardUserService.param(pContext, "mdmfAccelPacks"));
		final List<String> options = ProjectWizardUserService.csv(ProjectWizardUserService.param(pContext, "mdmfAccelOptions"));
		this.accelTickedPacks.clear();
		this.accelTickedPacks.addAll(packs);
		this.accelTickedOptions.clear();
		this.accelTickedOptions.addAll(options);
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.noProject"));
			return;
		}
		if ("ACCEL_SAVE_NEED".equals(pAction))
		{
			final int rows = AccelWizard.saveNeed(this.session, this.factory, this.projectId, this.accelNeed);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, rows > 0 ? "wizard.accel.needSaved" : "wizard.accel.needKept");
			this.noticeKind = "ok";
		}
		else if ("ACCEL_SUGGEST_PACKS".equals(pAction))
		{
			if (this.accelNeed.isEmpty())
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.needRequired"));
				return;
			}
			final AccelWizard.Suggestion suggestion = AccelWizard.suggestPacks(this.session, this.factory, this.projectId, this.accelNeed, this.locale);
			this.accelSuggestedPacks = suggestion.packs;
			this.accelWhy = suggestion.why;
			this.accelFromModel = true;
			this.accelTickedPacks.clear();
			this.accelTickedPacks.addAll(suggestion.packs);
			this.accelTickedOptions.clear();
			this.accelTickedOptions.addAll(suggestion.options);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.suggested", Integer.valueOf(suggestion.packs.size()));
			this.noticeKind = "ok";
		}
		else if ("ACCEL_CHOOSE_PACKS".equals(pAction))
		{
			// the ticked packs may be none: "Skip the packs, start blank" is a valid start (docs/ACCELERATORS.md sec 4, the specification alone)
			final boolean asSuggested = this.accelFromModel && this.accelSuggestedPacks.equals(packs);
			AccelWizard.choosePacks(this.session, this.factory, this.projectId, packs, options, this.accelNeed, asSuggested, this.locale);
			this.notice = packs.isEmpty() ? Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.blankChosen")
				: Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.packsSaved", Integer.valueOf(packs.size()));
			this.noticeKind = "ok";
			this.currentStep = WizardRules.STEP_SPECIFICATION;
		}
		else if ("ACCEL_RUN_PROPOSAL".equals(pAction))
		{
			AccelWizard.saveNeed(this.session, this.factory, this.projectId, this.accelNeed);
			AccelWizard.runProposal(this.session, this.factory, this.projectId, this.projectCode(), this.locale);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.proposalQueued");
			this.noticeKind = "ok";
			this.currentStep = WizardRules.STEP_PROPOSAL;
		}
		else if ("ACCEL_RUN_PASS".equals(pAction))
		{
			final int pass = ProjectWizardUserService.paramInt(pContext, "mdmfAccelPass", 0);
			if (pass < 1 || pass > 4)
			{
				return;
			}
			AccelWizard.runPass(this.session, this.factory, this.projectId, this.projectCode(), pass, ProjectWizardUserService.paramBool(pContext, "mdmfAccelRunAll"), this.locale);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.passQueued", Integer.valueOf(pass));
			this.noticeKind = "ok";
		}
		else if ("ACCEL_GENERATE".equals(pAction))
		{
			AccelWizard.generate(this.session, this.factory, this.projectId, this.projectCode(), this.locale);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.accel.generationQueued");
			this.noticeKind = "ok";
			this.currentStep = WizardRules.STEP_GENERATION;
		}
	}

	// ------------------------------------------------------------------------------------------------ decisions

	/**
	 * Relation/entity/attribute/mapping (step 4) and, since the inline-reviews lot (2026-09-23),
	 * MAPPING_STEP/QUALITY/SCRIPT_CONSTRAINT (step 4) and MATCH (step 6, including "No matching for this
	 * entity" with its comment) decisions all reuse {@link ProposalReviews#decide}: the one decision path,
	 * never a second implementation (task brief). {@code mdmfComment} is only ever meaningful for a MATCH
	 * "No matching for this entity" - {@code ProposalReviews.decide} itself only writes it for exactly that
	 * nature/target combination, so reading it unconditionally here is harmless for every other line, same
	 * as {@code ProposalReviewUserService.decideOne}.
	 */
	private void decide(final UserServiceEventContext pContext, final boolean pAccept)
	{
		final String nature = ProjectWizardUserService.param(pContext, "mdmfNature");
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String comment = ProjectWizardUserService.param(pContext, "mdmfComment");
		final String targetStatus = pAccept ? ReviewLine.ACCEPTED : ReviewLine.REJECTED;
		if (ReviewLine.MATCH.equals(nature))
		{
			this.lastActedMatchRowId = id;
		}
		// staging-still-to-decide lot (2026-09-27): a field's Accept on step 4's "decisions left" card carries
		// that field's PROPOSED quality rules (the card shows them as "decided with the field", no buttons of
		// their own) - posted as mdmfCarryRules=true by that card's field buttons only; every other Accept in the
		// wizard leaves it blank, unchanged behaviour (see ProposalReviews.decide's 7-argument overload).
		final boolean carryRules = "true".equals(ProjectWizardUserService.param(pContext, "mdmfCarryRules"));
		// lot C1-B: "Edit" on a difference posts the edited JSON in mdmfDetail; it replaces Difference.detail BEFORE the line is accepted
		final String editedDetail = ProjectWizardUserService.param(pContext, "mdmfDetail");
		if (pAccept && ReviewLine.DIFFERENCE.equals(nature) && !editedDetail.isEmpty())
		{
			try
			{
				AccelWizard.editDifference(this.session, this.factory, id, editedDetail);
			}
			catch (final OperationException ex)
			{
				this.warn(EngineNotice.inline(ex, this.locale));
				return;
			}
		}
		final ProposalReviews.DecisionResult result = ProposalReviews.decide(this.session, this.factory, nature, id, targetStatus, comment, carryRules);
		if (!result.ok)
		{
			this.warn(result.reasonIn(this.locale));
			return;
		}
		if (result.rulesFollowed > 0)
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.acceptedWithRules", Integer.valueOf(result.rulesFollowed));
			return;
		}
		// apply-visibility lot (2026-09-23, coordinator finding on project Personnes): accepting a
		// SCRIPT_CONSTRAINT whose zone's model does not exist yet used to attempt the apply anyway and fail
		// with the exact same applyMessage every time ("model 'Personnes_Master' does not exist yet") - the
		// accept decision itself is still recorded, but the apply attempt is skipped and the notice says so
		// instead of surfacing a doomed-from-the-start failure as a warning.
		// lot M5 (2026-10-04): a SCRIPT_DERIVATION is applied the same way (ReviewRules.shouldApplyAfterAccept), through ebx.CapabilityDispatcher
		if (ReviewRules.shouldApplyAfterAccept(targetStatus, nature))
		{
			final String zone = WizardData.capabilityZone(this.factory, id);
			final WizardSnapshot snapshot = WizardData.readSnapshot(this.view(), this.factory, this.projectId, this.session);
			if (WizardRules.capabilityApplyGate(snapshot, zone).enabled)
			{
				// apply lot equivalent (ProposalReviewUserService.decideOne): accepting a SCRIPT_CONSTRAINT
				// applies it directly - the product owner's own decision, no separate button. An apply failure
				// never undoes the accept decision itself (ProposalReviews.decide already committed it) -
				// ebx.CapabilityApplier leaves the row ACCEPTED with its own applyMessage, surfaced as a warning.
				// audit F-34: the apply (an LLM-drafted script for a derivation, a model publication, a measure over the table) is queued -
				// the same job the stand-alone Apply button queues - instead of running inside this request
				this.queueCapabilityJob(CapabilityApplyRun.applyKindFor(zone), id, "review.notice.applyFailed", "wizard.notice.acceptedApplyQueued");
			}
			else
			{
				this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.acceptedApplyDeferred");
			}
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, pAccept ? "wizard.notice.accepted" : "wizard.notice.rejected");
	}

	/**
	 * Audit F-34: queues one capability job (apply or remove of a suggestion, kind from its zone) and says so; an unknown zone or a
	 * refused queue becomes the warning {@code pFailureKey}.
	 */
	private void queueCapabilityJob(final String pKind, final String pId, final String pFailureKey, final String pQueuedKey)
	{
		if (pKind == null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, pFailureKey, "unknown zone"));
			return;
		}
		try
		{
			CapabilityApplyRun.start(this.session, this.factory, this.projectId, pKind, this.projectCode(), pId);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, pQueuedKey);
			this.noticeKind = "ok";
		}
		catch (final OperationException ex)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, pFailureKey, EngineNotice.inline(ex, this.locale)));
		}
	}

	/** {@code REMOVE}: takes an APPLIED SCRIPT_CONSTRAINT back off the model - {@code ebx.CapabilityApplier.remove}, mirroring {@code ProposalReviewUserService.removeOne}. */
	private void removeCapability(final UserServiceEventContext pContext)
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		// audit F-34: removing republishes the model - queued (CapabilityApplyRun, kind by the row's own zone) instead of run in this request
		this.queueCapabilityJob(CapabilityApplyRun.removeKindFor(WizardData.capabilityZone(this.factory, id)), id, "review.notice.removeFailed",
			"wizard.jobs.startedNotice");
	}

	/**
	 * {@code MEASURE}: re-measures an APPLIED SCRIPT_CONSTRAINT on demand.
	 * <p>
	 * Lot C3 (2026-09-24): queued as a job ({@link CapabilityApplyRun}) instead of running {@code
	 * CapabilityApplier.measure} inline - its own Javadoc: "{@code AdaptationTable.getValidationReport()} walks
	 * the whole table", which is not bounded by the {@code DEFAULT_MEASURE_MAX_RECORDS} guard that only protects
	 * the AUTOMATIC phase-B measurement after an apply ({@code /measure} "always measures, whatever the size").
	 * The job shares its resource (STAGING or MASTER, from the row's own zone) with that zone's staging/master
	 * build, so a measure never reads a model half-way through being republished.
	 */
	private void measureCapability(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String zone = WizardData.capabilityZone(this.factory, id);
		final String kind = CapabilityApplyRun.measureKindFor(zone);
		if (kind == null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.notice.measureFailed", "unknown zone '" + zone + "'"));
			return;
		}
		CapabilityApplyRun.start(this.session, this.factory, this.projectId, kind, this.projectCode(), id);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * {@code APPLY}/{@code REAPPLY}: the stand-alone Apply action on an ACCEPTED, not-yet-APPLIED line, or a
	 * re-apply on a stale APPLIED one - the exact same call either way (re-applying is not skipped).
	 * <p>
	 * Apply-visibility lot (2026-09-23): {@code WizardPage} only draws this action as a live button when
	 * {@code WizardRules.capabilityApplyGate} is enabled (a hint is shown instead otherwise) - re-checked here
	 * too, the same defence-in-depth the class Javadoc already applies to every admin-only action, against a
	 * forged request naming a row whose zone is still ungenerated.
	 * <p>
	 * Lot C3 (2026-09-24): queued as a job ({@link CapabilityApplyRun}) instead of calling {@link
	 * #applyCapability} inline - {@code CapabilityApplier.apply} publishes a data-model script (an MCP round
	 * trip, the same family {@link StagingLoadRun}/{@link MasterBuildRun} already moved to the queue), sharing
	 * its resource (STAGING or MASTER, from the row's own zone) with that zone's staging/master build so the two
	 * never race the same model. The auto-apply that follows accepting a {@code SCRIPT_CONSTRAINT} line ({@link
	 * #decide}) is NOT touched by this lot - it still calls {@link #applyCapability} directly, out of this
	 * action's scope.
	 */
	private void applyCapabilityDirectly(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String zone = WizardData.capabilityZone(this.factory, id);
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.Gate gate = WizardRules.capabilityApplyGate(snapshot, zone);
		if (!gate.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gate.reasonKey));
			return;
		}
		final String kind = CapabilityApplyRun.applyKindFor(zone);
		if (kind == null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.notice.applyFailed", "unknown zone '" + zone + "'"));
			return;
		}
		CapabilityApplyRun.start(this.session, this.factory, this.projectId, kind, this.projectCode(), id);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * {@code REPROPOSE}: "Propose another" on a MATCH line - {@link ProposalReviews#startMatchRepropose} marks
	 * the row SUPERSEDED and stores the comment, then {@code MatchPlanLlmSuggester.suggest} runs scoped to
	 * this one entity; a run that did not actually write/refresh/repeat a plan reverts the row to PROPOSED
	 * with a plain-words note ({@link ProposalReviews#revertMatchPlanToProposed}) rather than leaving it stuck
	 * at SUPERSEDED - mirrors {@code ProposalReviewUserService.reproposeOne} exactly.
	 */
	private void reproposeMatch(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String comment = ProjectWizardUserService.param(pContext, "mdmfComment");
		this.lastActedMatchRowId = id;
		final ProposalReviews.ReproposeStart start = ProposalReviews.startMatchRepropose(this.session, this.factory, id, comment);
		if (!start.ok)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.notice.refused", start.reason));
			return;
		}
		// audit F-34: the language model is asked by a queued job (MatchingSuggestLlmRun.startRepropose) - it puts the plan back to PROPOSED
		// with the reason when the model writes nothing new for the entity, as this method did inline; its outcome is the job's, in the banner
		try
		{
			MatchingSuggestLlmRun.startRepropose(this.session, this.factory, this.projectCode(), start.entityCode, id);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.reproposeQueued", start.entityCode);
			this.noticeKind = "ok";
		}
		catch (final OperationException ex)
		{
			ProposalReviews.revertMatchPlanToProposed(this.session, this.factory, id, com.on.ebx.factory.landing.ErrorText.of(ex));
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.match.repropose.failed", EngineNotice.inline(ex, this.locale)));
		}
	}

	/**
	 * {@code QUALITY_SET_SEVERITY}: {@code QualityRule.severity} lot (2026-09-23, coordinator finding on
	 * project Personnes) - wizard-only, the Proposal review screen is deliberately untouched.
	 */
	private void setQualitySeverity(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String severity = ProjectWizardUserService.param(pContext, "mdmfSeverity");
		WizardData.setQualityRuleSeverity(this.session, this.factory, id, severity);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.severitySet");
	}

	/**
	 * {@code CAPABILITY_SET_ASIDE}: staging-still-to-decide lot (2026-09-27, project ret) - wizard-only.
	 * {@code mdmfId} is one suggestion id, or several comma-separated ("Set all N aside"); {@link
	 * WizardData#setCapabilitiesAside} re-checks each row (this project, {@code EXTERNAL_SERVICE}, still {@code
	 * PROPOSED}) and skips any other, so the count in the notice is what really moved.
	 */
	private void setCapabilitiesAside(final UserServiceEventContext pContext) throws OperationException
	{
		final String ids = ProjectWizardUserService.param(pContext, "mdmfId");
		final List<String> list = new ArrayList<String>();
		for (final String id : ids.split(","))
		{
			if (!id.trim().isEmpty())
			{
				list.add(id.trim());
			}
		}
		final int moved = WizardData.setCapabilitiesAside(this.session, this.factory, this.projectId, list);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.setAside", Integer.valueOf(moved));
	}

	/**
	 * {@code CAPABILITY_SET_TRANSFER_POLICY}: quality-script-rule lot (2026-09-23) - wizard-only, same
	 * convention as {@link #setQualitySeverity} above.
	 */
	private void setCapabilityTransferPolicy(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String transferPolicy = ProjectWizardUserService.param(pContext, "mdmfTransferPolicy");
		WizardData.setCapabilityTransferPolicy(this.session, this.factory, id, transferPolicy);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.severitySet");
	}

	/**
	 * {@code DERIVATION_SET_PARAMS}: lot P1 (2026-10-04) - a person settles the parameter(s) of a {@code SCRIPT_DERIVATION} from step 4's "To apply" list. The
	 * posted state is read HERE, in the event callback only ({@code mdmfId}, and {@code mdmfParams}: a JSON object {@code {"<parameter>":"<value>"}} of what the
	 * person changed, written by {@code wizard.js}); the rules and the write are {@code ebx.DerivationParameterSettings} (the same as the REST route). Administrators
	 * only, like Apply: the value ends up in a script the system runs on every record. Never applies by itself.
	 */
	private void setDerivationParameters(final UserServiceEventContext pContext) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String raw = ProjectWizardUserService.param(pContext, "mdmfParams");
		final java.util.Map<String, String> values = new java.util.LinkedHashMap<String, String>();
		try
		{
			final com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(raw.isEmpty() ? "{}" : raw);
			final java.util.Iterator<java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode>> fields = node.fields();
			while (fields.hasNext())
			{
				final java.util.Map.Entry<String, com.fasterxml.jackson.databind.JsonNode> field = fields.next();
				values.put(field.getKey(), field.getValue().isTextual() ? field.getValue().asText() : null);
			}
		}
		catch (final java.io.IOException ex)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.derivationParamsRefused", "the posted parameters are unreadable"));
			return;
		}
		try
		{
			final com.fasterxml.jackson.databind.node.ObjectNode result = DerivationParameterSettings.settle(this.session, this.factory, this.projectCode(), id, values);
			final boolean reapply = result.path("reapplyNeeded").asBoolean(false);
			final int remaining = result.path("toBeProvidedRemaining").size();
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
				reapply ? "wizard.notice.derivationParamsSavedReapply" : remaining > 0 ? "wizard.notice.derivationParamsSavedRemaining" : "wizard.notice.derivationParamsSaved",
				Integer.valueOf(remaining));
		}
		catch (final DerivationParameters.Refusal ex)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.derivationParamsRefused", EngineNotice.inline(ex, this.locale)));
		}
	}

	/** {@code FEED_SET_PIPELINE}: target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3) - wizard-only, same convention as {@link #setCapabilityTransferPolicy}. */
	private void setMappingPipeline(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String pipeline = ProjectWizardUserService.param(pContext, "mdmfPipeline");
		WizardData.setMappingPipeline(this.session, this.factory, id, pipeline);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.pipelineSaved");
	}

	/** {@code FEED_SET_SOURCE_ID_PIPELINE}: target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3) - the sibling of {@link #setMappingPipeline} for a feed's own {@code sourceIdPipeline}. */
	private void setFeedSourceIdPipeline(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		final String sourceIdPipeline = ProjectWizardUserService.param(pContext, "mdmfSourceIdPipeline");
		WizardData.setFeedSourceIdPipeline(this.session, this.factory, id, sourceIdPipeline);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.sourceIdPipelineSaved");
	}

	// ------------------------------------------------------------------------------------------------ step 4: staging

	/**
	 * The rule pass, then - staging-llm-chained lot (2026-09-23) - the language-model pass in the SAME click
	 * whenever that pass's own gate allows it (administrator, connector configured, at least two rule
	 * entities: exactly {@code WizardRules.actionGates(...).suggestStagingLlm}, re-evaluated on a fresh
	 * snapshot once the rules have written their entities). The rule pass is the model's INPUT, not an
	 * alternative to it ({@code StagingLlmSuggester} reads the RULE entities and unifies them into canonical
	 * ones, whose data comes straight from the landing tables), so with a model at hand the rule entities
	 * were never the thing to decide on - the product owner, project Personnes: "a partir du moment ou un LLM est
	 * configure, Propose the staging doit l'utiliser". The members the model absorbs are moved to
	 * {@code SUPERSEDED} by {@code StagingLlmSuggester.supersedeMembers}, so only the canonical entities are
	 * left to decide. A model failure after a successful rule pass is reported as a warning that says both.
	 * <p>
	 * Audit F-34 (wave 10): the whole chain is a queued job now ({@link SuggestRulesRun#startStaging}, resource DESIGN) instead of work done inside this
	 * request - the gate of the rule pass is checked here, on the person's own snapshot; the language-model gate is re-evaluated by the job once the
	 * rules have written their entities, as it was here. The sentence this method showed is the job's message (the banner, then "Recent tasks").
	 */
	private void suggestStaging() throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, snapshot.designer);
		if (!gates.suggestStaging.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gates.suggestStaging.reasonKey));
			return;
		}
		SuggestRulesRun.startStaging(this.session, this.factory, snapshot.project == null ? null : snapshot.project.code, snapshot.designer && snapshot.llmConfigured);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Job-engine lot C3 (2026-09-24): queues {@link StagingSuggestLlmRun} (resource DESIGN) and returns at
	 * once - the language-model pass itself (2-4 min) now runs in the background, followed by the generic
	 * jobs banner, instead of blocking this postback. Administrators only, re-checked here.
	 * <p>
	 * Staging-focus lot (2026-09-23): the button {@code WizardPage} draws is disabled whenever {@code
	 * WizardRules.actionGates(...).suggestStagingLlm} is - that is client-side chrome, not a guard, so the
	 * FULL gate (not just the administrator check this method already had) is recomputed here on a fresh
	 * snapshot and refused with the same message a forged request would otherwise bypass, exactly the
	 * defence-in-depth {@link #applyCapabilityDirectly} already applies to its own gate. {@code
	 * StagingSuggestLlmRun} performs no session-dependent gating of its own - this gate is the ONLY thing
	 * standing between a click and the background run, which is why it stays here, synchronous, on the real
	 * caller session, rather than moving into the job (see that class's own Javadoc, "Role and locale"). This
	 * is the STAND-ALONE button only - {@link #suggestStaging}'s own chain into {@link #runStagingLlm} is
	 * untouched, still synchronous, exactly as before this lot.
	 */
	private void suggestStagingLlm() throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, snapshot.designer);
		if (!gates.suggestStagingLlm.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gates.suggestStagingLlm.reasonKey));
			return;
		}
		StagingSuggestLlmRun.start(this.session, this.factory, this.projectCode());
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Staging-proposal-run lot (2026-09-24): the one-click "Propose the staging" button - starts {@link
	 * StagingProposalRun} in the background and returns at once; the page then re-renders into the "while
	 * running" state ({@link #model()} reads {@link StagingProposalRun#status} fresh on every render). The
	 * same {@code suggestStaging} gate (structure needs at least a landing to work over) is re-checked here,
	 * defence in depth like every other action this class re-validates server-side.
	 */
	private void startProposalRun(final UserServiceEventContext pContext) throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		final WizardRules.ActionGates gates = WizardRules.actionGates(snapshot, snapshot.designer);
		if (!gates.suggestStaging.enabled)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, gates.suggestStaging.reasonKey));
			return;
		}
		final String projectCode = snapshot.project == null ? null : snapshot.project.code;
		final boolean includeQuality = ProjectWizardUserService.paramBool(pContext, "mdmfProposalIncludeQuality");
		final boolean includeCapability = ProjectWizardUserService.paramBool(pContext, "mdmfProposalIncludeCapability");
		final ObjectNode result = StagingProposalRun.start(this.session, this.factory, projectCode, includeQuality, includeCapability);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step4.run.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step4.run.started");
	}

	/**
	 * Accepts (or rejects) every PROPOSED staging line at or above {@link #stagingBulkThreshold}, in
	 * dependency order - entities first, then their attributes, then the attributes' mappings - since
	 * {@link ProposalReviews#decide} refuses an attribute whose entity is not yet accepted ({@code
	 * ReviewRules}). Each row is re-validated by {@link ProposalReviews#decide} exactly like a single
	 * decision; a row the rules refuse (e.g. its entity was itself just rejected) is simply skipped, counted
	 * in the notice, never silently dropped.
	 * <p>
	 * <b>Bulk-mapping-cascade lot (2026-09-23, coordinator finding on project Personnes):</b> this method's
	 * own Javadoc always promised "then the attributes' mappings" but the code stopped at attributes - a
	 * mapping {@code ProposalReviews.decide}'s own {@code MappingFollowRule} cascade does not reach (a
	 * person's own {@code USER} mapping, or one already decided by anyone before this attribute's own
	 * decision) never got decided here at all, so a staging load could write nothing although every attribute
	 * read "Accepted". Every still-{@code PROPOSED} mapping of the attribute, at or above the same threshold,
	 * is now decided right after its attribute - a mapping the cascade already moved is simply no longer
	 * {@code PROPOSED} by the time this loop reaches it, so it is neither re-decided nor double-counted. A
	 * mapping a bulk REJECT earlier left {@code REJECTED} is NOT reopened here - see {@link
	 * WizardData#reopenOrphanedMappings} (the dedicated "Re-open mappings" button) for that, deliberately kept
	 * a separate, explicit action.
	 * <p>
	 * <b>Block-accept lot (2026-09-23, the product owner: "il faut pouvoir accepter tous les elements d'un bloc, pas
	 * tous ceux de la page"):</b> {@code pFilter.entityId} restricts the whole sweep to one entity's own card -
	 * itself, its attributes, their mappings, and the inline proposal lines grouped under it - null (the two
	 * page-wide buttons, {@link WizardSnapshot.BulkFilter#wholeProject()}) sweeps every entity of the project,
	 * unchanged. One method either way: the "Accept all of this entity" / "Reject all shown of this entity"
	 * card buttons are not a second implementation, only a narrower call of this same one.
	 * <p>
	 * <b>Quality-block-bulk lot (2026-09-23, the product owner: "j'aurais aime un accept/reject all au niveau des
	 * revues liees a la qualite"):</b> a non-null {@code pFilter.natures} restricts the sweep to the inline
	 * proposal lines of exactly those natures - the entity/attribute/mapping loop is skipped entirely (a
	 * "quality-only" bulk never touches an entity's own accept/reject state).
	 * <p>
	 * <b>Threshold-left-out lot (2026-09-23, coordinator finding on project Personnes: "Quand je clique sur
	 * Accept all, toutes les cleaning steps de Sample n'ont pas ete acceptees"):</b> a row below the threshold
	 * used to be silently dropped, with no trace in the notice. {@link WizardRules#bulkEligibility} now
	 * classifies every row into decided / refused-by-a-rule / left-out-below-threshold, and the notice names
	 * all three, with the lowest left-out confidence, whenever at least one row was left out -
	 * {@link WizardSnapshot.BulkFilter#includeBelowThreshold} (the per-card checkbox) sweeps those rows in
	 * instead, exactly like raising the threshold for this one card only.
	 * <p>
	 * <b>Apply-visibility lot (2026-09-23, coordinator finding on project Personnes):</b> a SCRIPT_CONSTRAINT
	 * row this sweep just accepted is queued for the apply only when {@link
	 * WizardRules#capabilityApplyGate} finds its zone's model already generated - one whose zone is not ready
	 * yet is left {@code ACCEPTED} with no apply attempt (which would only fail the same way every time), and
	 * counted separately in the notice instead.
	 */
	private void bulkStaging(final boolean pAccept, final WizardSnapshot.BulkFilter pFilter)
	{
		// audit F-47 (wave 5): the sweep (and the apply of the script constraints it accepted) runs as a queued job, ebx.StagingBulkDecideRun - see its Javadoc;
		// this method only queues it. Everything the Javadoc above describes is what the job does, unchanged; the outcome sentence is the job's, in the banner.
		try
		{
			if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
				return;
			}
			if (StagingBulkDecideRun.isActive(this.factory, this.projectId))
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.bulkAlreadyActive"));
				return;
			}
			StagingBulkDecideRun.start(this.session, this.factory, this.projectId, this.projectCode(), pAccept, pFilter.entityId, pFilter.natures,
				pFilter.includeBelowThreshold, this.stagingBulkThreshold);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.bulkQueued");
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/**
	 * Bulk-mapping-cascade lot (2026-09-23, coordinator finding on project Personnes): explicit, single-click
	 * un-blocking of an attribute a bulk reject/re-accept cycle left with no working mapping - see
	 * {@link WizardData#reopenOrphanedMappings}'s own Javadoc for why this is a dedicated action rather than
	 * folded silently into {@link #bulkStaging}.
	 */
	private void reopenMappings() throws OperationException
	{
		final int count = WizardData.reopenOrphanedMappings(this.session, this.factory, this.projectId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, ProjectWizardUserService.pluralKey("wizard.notice.mappingsReopened", count), Integer.valueOf(count));
	}

	// ------------------------------------------------------------------------------------------------ step 4: quality & capabilities

	/**
	 * Audit F-34 (wave 10): queued ({@link SuggestRulesRun#startQuality}, resource DESIGN) - the pass profiles the project's data, which is not bounded by a
	 * request. The queue checks the DESIGN right of the kind; the sentence this method showed is the job's message.
	 */
	private void suggestQuality() throws OperationException
	{
		SuggestRulesRun.startQuality(this.session, this.factory, this.projectCode());
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Job-engine lot C3 (2026-09-24): queues {@link QualitySuggestLlmRun} (resource DESIGN) and returns at
	 * once - the language-model pass itself now runs in the background, followed by the generic jobs banner
	 * (which also carries the "long-passes-visible" zero-result reason this action used to build here - see
	 * {@link QualitySuggestLlmRun}'s own Javadoc). Administrators only, re-checked here - queuing a job still
	 * costs nothing, but only an administrator may spend language-model tokens.
	 */
	private void suggestQualityLlm() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		QualitySuggestLlmRun.start(this.session, this.factory, this.projectCode());
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Job-engine lot C3 (2026-09-24): queues {@link CapabilitySuggestLlmRun} (resource DESIGN) and returns at
	 * once - same reasoning as {@link #suggestQualityLlm}. Needs no staging model generated yet
	 * (CapabilityLlmSuggester iterates ACCEPTED-or-APPLIED staging entities directly). Administrators only,
	 * re-checked here.
	 */
	private void suggestCapabilitiesLlm() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		CapabilitySuggestLlmRun.start(this.session, this.factory, this.projectCode());
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	// ------------------------------------------------------------------------------------------------ step 4: build the staging zone

	/**
	 * Lot C3 (2026-09-24): the "Advanced: run generate/load separately" stand-alone button - queues {@link
	 * StagingLoadRun} restricted to {@link StagingLoadRun#MODEL} instead of running {@code StagingGenerator.generate}
	 * (a data-model-server MCP round trip, minutes-long, see {@code StagingLoadRun}'s own Javadoc) inside this
	 * postback. Same job kind/resource (STAGING) as the one-click "Build and load" ({@link #startStagingLoadRun}),
	 * so the two can never race the same staging model - one simply queues behind the other. Administrators only,
	 * re-checked here - acts as the agent account on the MCP servers, like landing generate.
	 */
	private void generateStagingModel() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		final ObjectNode result = StagingLoadRun.start(this.session, this.factory, projectCode, java.util.Collections.singletonList(StagingLoadRun.MODEL));
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step4.build.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Lot C3 (2026-09-24): same idea as {@link #generateStagingModel}, restricted to {@link StagingLoadRun#LOAD} -
	 * a real transfer over the whole project's landing tables, which the coordinator's own timing notes (this
	 * class's earlier Javadoc, before this lot) already called out as comfortably minutes-long on a real project.
	 * Administrators only, re-checked here.
	 *
	 * @param pScopeAll volume-guards lot (2026-09-28, task item 2): the posted "Recompute every row" checkbox
	 *            ({@code mdmfStagingScopeAll}) - {@code false} (the default, unchecked) runs the ordinary
	 *            incremental transfer; {@code true} forces a full re-transform of every landing row. See
	 *            {@code StagingLoadRun#start(Session, Adaptation, String, java.util.List, boolean)}'s own Javadoc.
	 */
	private void loadStagingData(final boolean pScopeAll) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		final ObjectNode result = StagingLoadRun.start(this.session, this.factory, projectCode, java.util.Collections.singletonList(StagingLoadRun.LOAD), pScopeAll);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step4.build.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Wizard-visual-pass lot (2026-09-24): the primary "Fill the shared structure" button - starts {@link
	 * StagingLoadRun} in the background and returns at once; the same gate as {@link #generateStagingModel}
	 * (the whole run is refused up front if the caller cannot even start the first stage).
	 *
	 * @param pScopeAll same "Recompute every row" checkbox as {@link #loadStagingData(boolean)} - see that
	 *            method's own Javadoc.
	 */
	private void startStagingLoadRun(final boolean pScopeAll, final boolean pBuildAnyway) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		// staging preflight lot H1 (2026-10-17): the run makes the check before the build itself (unless a person chose "Build anyway": pBuildAnyway)
		final ObjectNode result = StagingLoadRun.start(this.session, this.factory, projectCode, StagingLoadRun.STAGES, pScopeAll, pBuildAnyway);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step4.build.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step4.build.started");
	}

	/**
	 * Staging preflight lot H1 (2026-10-17): "Check before building" and "Apply the fix" of the report at the top of step 4. The check reads the landing's distinct values (a
	 * bounded sample per mapped column, no connector call) and replays every accepted link: F2-102 - a queued job ({@link WizardActionRuns#startPreflight}), the report is stored for the page.
	 * {@code pApplyFix}: first writes the fix(es) the stored report proposed (one link, or every fixable link when {@code pMappingId} is empty) - the pipeline of the link, its status
	 * unchanged - then checks again, so the page shows what is left. Design right, re-checked here.
	 */
	private void checkBeforeBuild(final boolean pApplyFix, final String pMappingId) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		// F2-102: the check reads the landing's distinct values and replays every accepted link: a queued job, the report is stored for the page as before and the
		// job's message is the sentence this postback used to show
		WizardActionRuns.startPreflight(this.session, this.factory, this.projectCode(), pApplyFix, pMappingId);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	// ------------------------------------------------------------------------------------------------ step 5: master

	/**
	 * Lot C3 (2026-09-24): the "Advanced: run generate/load separately" stand-alone button - queues {@link
	 * MasterBuildRun} restricted to {@link MasterBuildRun#TABLES} instead of running {@code MasterGenerator.generate}
	 * (an MCP round trip of the same "About two minutes" family as {@link #startMasterBuildRun}'s own board text)
	 * inside this postback. Same job kind/resource (MASTER) as the one-click "Build the master", so the two never
	 * race the same master model. Administrators only, re-checked here. {@code overwriteTarget} is deliberately
	 * never exposed - see {@code WizardPage.renderStep5}'s own Javadoc.
	 */
	private void generateMasterModel() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		final ObjectNode result = MasterBuildRun.start(this.session, this.factory, projectCode, java.util.Collections.singletonList(MasterBuildRun.TABLES));
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step5.build.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/** Lot C3 (2026-09-24): same idea as {@link #generateMasterModel}, restricted to {@link MasterBuildRun#COPY} - copies every promoted business object's staging rows into the master. Administrators only, re-checked here. */
	private void loadMasterData() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		final ObjectNode result = MasterBuildRun.start(this.session, this.factory, projectCode, java.util.Collections.singletonList(MasterBuildRun.COPY));
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step5.build.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/** Wizard-visual-pass lot (2026-09-24): the primary "Build the master" button - starts {@link MasterBuildRun} in the background, same shape as {@link #startStagingLoadRun}. */
	private void startMasterBuildRun() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		final ObjectNode result = MasterBuildRun.start(this.session, this.factory, projectCode);
		if (!result.path("started").asBoolean(false))
		{
			this.warn(result.path("reason").asText(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step5.build.refused")));
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step5.build.started");
	}

	/**
	 * Wizard-match-tree lot (2026-09-23): {@code SIMULATE} on a plan card - the exact same call and the exact
	 * same gate as {@code ProposalReviewUserService.simulateOne} ({@code PROPOSED}/{@code ACCEPTED}/{@code
	 * APPLIED} simulate, on the preview policy for the first two - {@code ebx.MatchingPreviewPolicy} - and on
	 * the real one for the last; only {@code REJECTED} refuses). The result lives on {@link
	 * #matchSimulationResults} until the dialog closes; the card reopens its details fold on the repaint.
	 */
	private void simulateMatch(final UserServiceEventContext pContext) throws OperationException
	{
		final String id = ProjectWizardUserService.param(pContext, "mdmfId");
		this.lastActedMatchRowId = id;
		ReviewLine matchLine = null;
		for (final ReviewLine line : this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale)))
		{
			if (ReviewLine.MATCH.equals(line.nature) && id.equals(line.rowId))
			{
				matchLine = line;
				break;
			}
		}
		if (matchLine == null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.notice.rowNotFound"));
			return;
		}
		if (!ProposalReviewUserService.canSimulateMatchPlan(matchLine.status))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.match.simulate.notApplied"));
			return;
		}
		// F2-102: 20 candidate searches plus the coverage queries: a queued job (WizardActionRuns.SIMULATE_STEP). The result is kept in memory for this person and taken
		// at the display that follows the end of the job (pickUpSimulations); until then the card shows nothing new
		WizardActionRuns.startSimulation(pContext.getSession(), this.factory, this.projectCode(), id, matchLine.element, matchLine.matchRequirementsJson);
		this.simulationPendingRowIds.add(id);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	// ------------------------------------------------------------------------------------------------ step 6: matching

	/** Administrators only, re-checked here - the deterministic, rule-only pass. Audit F-34 (wave 10): queued ({@link SuggestRulesRun#startMatching}, resource DESIGN). */
	private void suggestMatchingPlans() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		// UX lot U1 (2026-10-13): one button. When a language-model connector is configured the job follows the rules with the model for the objects the
		// rules left without a plan (SuggestRulesRun#MATCHING_HANDLER), the way step 4's "Propose the staging" does; the gate of that second pass is re-read
		// by the job once the rules have written
		final WizardSnapshot snapshot = this.requestSnapshot();
		SuggestRulesRun.startMatching(this.session, this.factory, this.projectCode(), snapshot.designer && snapshot.llmConfigured);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Job-engine lot C3 (2026-09-24): queues {@link MatchingSuggestLlmRun} (resource DESIGN) and returns at
	 * once - the language-model pass itself now runs in the background, followed by the generic jobs banner.
	 * Administrators only, re-checked here - spends language-model tokens. This is the whole-project sweep
	 * only ({@link #reproposeMatch}'s own single-entity repropose, which shares {@link MatchPlanLlmSuggester},
	 * is untouched - a single-entity repropose is fast enough to stay synchronous).
	 */
	private void suggestMatchingPlansLlm() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		MatchingSuggestLlmRun.start(this.session, this.factory, this.projectCode());
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Administrators only, re-checked here. Lot C3 (2026-09-24, jobs-matching): queues {@link MatchingConfigureRun}
	 * (resource MATCHING) instead of calling {@link MatchingConfigurator#configure} inside this postback - the
	 * per-entity {@code match-merge} MCP call chain (get/generate/generate/patch/get again, per promoted entity)
	 * is easily past a single HTTP round trip on a project with more than a couple of entities. The jobs banner
	 * (wizard.js) follows it and shows the same result sentence once it ends.
	 */
	private void configureMatchingPlans() throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		MatchingConfigureRun.start(this.session, this.factory, projectCode);
		// matching-live lot (2026-09-27): no green "started" line - the live panel under the button says it
	}

	/**
	 * Lot MT (2026-10-04): step 6's "When is matching done" choice - saves {@code Project.matchingTrigger} (administrators only, re-checked
	 * here). A value that is neither empty nor one of the two modes is refused, never lowered to the default. Saving does not touch the
	 * add-on: the notice says the next Configure applies it.
	 */
	private void setMatchingTrigger(final String pPosted) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (!com.on.ebx.factory.master.MatchingTriggerMode.isAccepted(pPosted))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.trigger.invalid"));
			return;
		}
		if (this.projectId == null || this.projectId.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.blocked.needProject"));
			return;
		}
		WizardData.saveMatchingTrigger(this.session, this.factory, this.projectId, pPosted);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.trigger.saved");
	}

	/**
	 * Administrators only, re-checked here. Lot C3 (2026-09-24, jobs-matching): queues {@link MatchingRunRun}
	 * (resource MATCHING) instead of launching the real matching run inside this postback - see {@link
	 * #configureMatchingPlans}'s own Javadoc for why. {@code pScope} ("incremental"/"all", the button clicked) is
	 * captured into the job's params here, at enqueue time, exactly as every other migrated action captures what
	 * it needs from the clicking session/request before handing off to the system-session job.
	 */
	private void runMatchingPlans(final String pScope) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String projectCode = this.projectCode();
		final boolean scopeAll = "all".equalsIgnoreCase(pScope);
		MatchingRunRun.start(this.session, this.factory, projectCode, scopeAll);
		// matching-live lot (2026-09-27): no green "started" line - the live panel under the button says it
	}

	// --------------------------------------------------------------------------------------- wizard-matching-results

	/**
	 * One suspect-pair decision from the "Matching results" queue - re-checks the administrator role server
	 * side (the buttons already render disabled otherwise - task brief: "re-check server side, show
	 * refusals") and otherwise calls {@link RecordLineageReader#decideSuspect} exactly as {@code
	 * RecordLineageUserService}'s own lineage screen does, never a second write path. Failures are surfaced
	 * as {@link #matchDecisionError}, shown on the entity's own results block on the repaint.
	 */
	private void decideMatchPair(final String pEntityName, final String pGoldenKey, final String pSuspectKey, final boolean pAccept)
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.STEWARD))
		{
			this.matchDecisionError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.decide.gate");
			return;
		}
		if (pEntityName == null || pEntityName.isEmpty() || pGoldenKey == null || pGoldenKey.isEmpty() || pSuspectKey == null || pSuspectKey.isEmpty())
		{
			return;
		}
		try
		{
			final String projectCode = this.projectCode();
			final RecordLineageReader.DecisionResult result = RecordLineageReader.decideSuspect(this.session, this.factory, projectCode, pEntityName, pGoldenKey, pSuspectKey,
				pAccept);
			if (!result.success)
			{
				this.matchDecisionError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.decideFailed", result.outcome);
			}
		}
		catch (final OperationException ex)
		{
			this.matchDecisionError = EngineNotice.inline(ex, this.locale);
		}
	}

	/**
	 * {@code MATCH_GROUP_MERGE} / {@code MATCH_GROUP_REJECT} (2026-10-18): decides a whole group of suspect records. The group is read again from the add-on's own suspect pairs at the click
	 * (what the card showed may be stale), planned by {@link SuspectGroups#plan}, and every pair goes through the same write path as a single pair ({@link RecordLineageReader#decideSuspect}:
	 * {@code Group.acceptSuspectsAndMergeGroups} / {@code Group.rejectSuspects}) - rejections first, so no merge has changed the groups they name, then the accepts in an order that merges the
	 * whole group. A pair's failure never stops the others; the notice counts what went through.
	 */
	private void decideMatchGroup(final String pEntityName, final String pMembersCsv, final String pExcludedCsv, final boolean pMerge)
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.STEWARD))
		{
			this.matchDecisionError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.decide.gate");
			return;
		}
		if (pEntityName == null || pEntityName.isEmpty() || pMembersCsv == null || pMembersCsv.isEmpty())
		{
			return;
		}
		final String projectCode = this.projectCode();
		final java.util.Set<String> members = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(pMembersCsv.split(",")));
		final java.util.Set<String> excluded = new java.util.LinkedHashSet<String>();
		if (pExcludedCsv != null && !pExcludedCsv.isEmpty())
		{
			for (final String key : pExcludedCsv.split(","))
			{
				if (members.contains(key))
				{
					excluded.add(key);
				}
			}
		}
		final List<String[]> stored = MatchingResultsBuilder.suspectPairsAmong(this.session, this.factory, projectCode, pEntityName, members);
		final List<com.on.ebx.factory.matching.SuspectGroups.Group> groups = com.on.ebx.factory.matching.SuspectGroups.components(stored, null);
		final com.on.ebx.factory.matching.SuspectGroups.Plan plan;
		com.on.ebx.factory.matching.SuspectGroups.Group group = null;
		for (final com.on.ebx.factory.matching.SuspectGroups.Group candidate : groups)
		{
			if (group == null || candidate.members.size() > group.members.size())
			{
				group = candidate; // the posted keys are one group; a stale card can only have lost pairs, never gained a second group
			}
		}
		if (group == null)
		{
			this.matchDecisionError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.group.gone");
			return;
		}
		plan = com.on.ebx.factory.matching.SuspectGroups.plan(group, excluded, pMerge);
		int rejected = 0;
		int accepted = 0;
		int failed = 0;
		final List<MatchResultsSnapshot.Decision> decided = new ArrayList<MatchResultsSnapshot.Decision>();
		final String user = this.session.getUserReference().getUserId();
		final String now = java.time.Instant.now().toString();
		for (final String[] pair : plan.reject)
		{
			try
			{
				final RecordLineageReader.DecisionResult result = RecordLineageReader.decideSuspect(this.session, this.factory, projectCode, pEntityName, pair[0], pair[1], false, false);
				if (result.success)
				{
					rejected++;
					decided.add(new MatchResultsSnapshot.Decision(pair[0], pair[1], false, user, now));
				}
				else
				{
					failed++;
				}
			}
			catch (final OperationException ex)
			{
				failed++;
			}
		}
		for (final String[] pair : plan.accept)
		{
			try
			{
				final RecordLineageReader.DecisionResult result = RecordLineageReader.decideSuspect(this.session, this.factory, projectCode, pEntityName, pair[0], pair[1], true, false);
				if (result.success)
				{
					accepted++;
					decided.add(new MatchResultsSnapshot.Decision(pair[0], pair[1], true, user, now));
				}
				else
				{
					failed++;
				}
			}
			catch (final OperationException ex)
			{
				failed++;
			}
		}
		MatchingResultsStore.afterDecisions(this.session, this.factory, projectCode, this.projectId, pEntityName, decided);
		if (failed > 0)
		{
			this.matchDecisionError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.group.failed", Integer.valueOf(failed),
				Integer.valueOf(accepted + rejected));
		}
		else
		{
			this.notice = pMerge ? Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.group.merged", Integer.valueOf(plan.merged.size()),
				Integer.valueOf(rejected)) : Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.group.kept", Integer.valueOf(rejected));
		}
	}

	/** {@code MATCH_RESULTS_REFRESH}: queues a {@link MatchingResultsRun} for the entity (folded into an identical queued one) - see the block's own notice. */
	private void refreshMatchResults(final String pEntityName) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.STEWARD))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.decide.gate"));
			return;
		}
		if (pEntityName == null || pEntityName.isEmpty())
		{
			return;
		}
		MatchingResultsRun.start(this.session, this.factory, this.projectCode(), pEntityName);
	}

	/**
	 * "Merge the N pairs >= threshold" (task item 5) - re-reads the entity's CURRENT pending pairs fresh
	 * (never trusts whatever the last render showed, which could be stale by the time this click lands),
	 * decides each eligible one exactly like {@link #decideMatchPair} (same write path), and reports how many
	 * succeeded/were refused. A single pair's failure never stops the rest.
	 */
	private void bulkMergeMatchPairs(final String pEntityName, final int pThresholdPercent)
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.STEWARD))
		{
			this.matchDecisionError = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.decide.gate");
			return;
		}
		if (pEntityName == null || pEntityName.isEmpty())
		{
			return;
		}
		// wave 2 (2026-09-30): the eligible pairs are read fresh (the click may land long after the render) but cheaply - the
		// pending pairs alone, off the metadataset's Suspected table, no KPI/band/merged-group aggregate, no decision-tree
		// evaluation - instead of a whole MatchingResultsBuilder.build on the HTTP thread. The stored results are patched and a
		// refresh queued ONCE at the end (decideSuspect's own per-decision hook is switched off for the loop).
		final String projectCode = this.projectCode();
		final MatchingResultsBuilder.Context context = new MatchingResultsBuilder.Context(projectCode, pEntityName, pEntityName,
			MatchingResultsStore.compareFields(this.factory, this.projectId, pEntityName), pThresholdPercent, "PENDING", "", true, null, null);
		final List<com.on.ebx.factory.matching.MatchStats.SuspectPair> pending = MatchingResultsBuilder.buildPendingPairs(this.session, this.factory, context,
			MatchingResultsBuilder.MAX_PENDING_PAIRS_SHOWN);
		final List<com.on.ebx.factory.matching.MatchStats.SuspectPair> eligible = com.on.ebx.factory.matching.MatchStats.eligibleForBulk(pending, pThresholdPercent);
		// F2-102: up to 60 Match and Merge decisions: a queued job (WizardActionRuns.BULK_MERGE_STEP). The pairs above were read as the PERSON, so their permissions decided
		// which ones are eligible; the job decides exactly those, then patches the stored results and queues ONE refresh
		if (eligible.isEmpty())
		{
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.bulkDone", Integer.valueOf(0), Integer.valueOf(0));
			return;
		}
		final List<String[]> pairs = new ArrayList<String[]>();
		for (final com.on.ebx.factory.matching.MatchStats.SuspectPair pair : eligible)
		{
			pairs.add(new String[] { pair.goldenKey, pair.suspectKey });
		}
		try
		{
			WizardActionRuns.startBulkMerge(this.session, this.factory, projectCode, pEntityName, pairs);
		}
		catch (final OperationException ex)
		{
			this.matchDecisionError = EngineNotice.inline(ex, this.locale);
			return;
		}
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/** Start of the project's latest MASTER_LOAD in epoch millis (0 = none/unknown): a stored matching-results snapshot computed before it describes an older master. */
	private static long masterLoadStartedAt(final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.PipelineStepInfo step : pSnapshot.projectSteps)
		{
			if ("MASTER_LOAD".equals(step.step))
			{
				return step.startedAtMillis;
			}
		}
		return 0L;
	}

	private static WizardSnapshot.StagingEntityInfo entityByName(final WizardSnapshot pSnapshot, final String pEntityName)
	{
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (entity.name.equals(pEntityName))
			{
				return entity;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ step-reset (2026-09-23)

	/**
	 * {@code STEP_RESET_PLAN}: opens (or re-opens, after editing the stage) the confirmation panel {@link
	 * WizardPage#renderStepReset} draws on the named step - administrators only, re-checked here (the button
	 * itself only renders for one, but a direct request must never be trusted on its own). Read-only - {@link
	 * StepResetter#plan} never writes.
	 */
	private void stepResetPlan(final UserServiceEventContext pContext) throws OperationException
	{
		if (!this.session.isUserInRole(Profile.ADMINISTRATOR))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String stage = ProjectWizardUserService.param(pContext, "mdmfResetStage");
		final String projectCode = this.projectCode();
		if (projectCode == null || projectCode.isEmpty())
		{
			return;
		}
		this.stepReset = ProjectWizardUserService.stepResetView(StepResetter.plan(this.session, this.factory, projectCode, stage));
	}

	/**
	 * {@code STEP_RESET_CONFIRM}: re-checks the administrator role server-side (the same "a client-side check
	 * only hides the control" posture as every other admin-only wizard action), then a cheap confirm-text
	 * pre-check (the same ceremony {@link com.on.ebx.factory.project.StepResetPlan#validate} itself applies -
	 * kept here too, synchronously, since {@link StepResetRun#start} no longer validates before enqueuing: a
	 * mistyped project code would otherwise only surface after a full queue/run/poll round trip instead of at
	 * once). Volume-operations lot 8b (2026-09-28, task 3): the actual reset now runs as a {@link StepResetRun}
	 * job instead of {@link StepResetter#reset} directly - the panel switches to a "running" view at once
	 * ({@link WizardPage.StepResetView#running}), and {@link #stepResetRunRunning()}/the {@code JOBS_POLL}
	 * branch in {@link #handle} pick the "done" report back up from {@link StepResetRun#status} once the job
	 * ends (the rich report survives the async move - see that method's own Javadoc for why, unlike project
	 * deletion, a step reset never removes the audit row it is read from).
	 */
	private void stepResetConfirm(final UserServiceEventContext pContext) throws OperationException
	{
		if (!this.session.isUserInRole(Profile.ADMINISTRATOR))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final String stage = ProjectWizardUserService.param(pContext, "mdmfResetStage");
		final String confirm = ProjectWizardUserService.param(pContext, "mdmfResetConfirm");
		final String projectCode = this.projectCode();
		if (projectCode == null || projectCode.isEmpty())
		{
			return;
		}
		if (confirm == null || !confirm.equals(projectCode))
		{
			final WizardPage.StepResetView view = ProjectWizardUserService.stepResetView(StepResetter.plan(this.session, this.factory, projectCode, stage));
			view.refusal = "Pass ?confirm=" + projectCode + " (the exact project code) to actually clean up from " + stage + " onward - this cannot be undone.";
			this.stepReset = view;
			return;
		}
		try
		{
			final ObjectNode queued = StepResetRun.start(this.session, this.factory, projectCode, stage, confirm);
			final WizardPage.StepResetView view = new WizardPage.StepResetView();
			view.stage = stage;
			view.running = true;
			this.stepReset = view;
			this.stepResetPendingKind = StepResetRun.STEP;
			this.stepResetPendingStage = stage;
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
				queued.path("merged").asBoolean(false) ? "wizard.stepReset.notice.queued" : "wizard.stepReset.notice.started", stage); // staging-screens lot (2026-10-17): the {0} of both messages was printed as is
		}
		catch (final OperationException ex)
		{
			final WizardPage.StepResetView view = ProjectWizardUserService.stepResetView(StepResetter.plan(this.session, this.factory, projectCode, stage));
			view.refusal = EngineNotice.inline(ex, this.locale);
			this.stepReset = view;
		}
	}

	/**
	 * Volume-operations lot 8b (2026-09-28, task 3): builds the panel's "done" (or refused) view from {@link
	 * StepResetRun#status} once the job {@link #stepResetPendingKind} was tracking is no longer live - called
	 * only from the {@code JOBS_POLL} branch of {@link #handle}. {@code status=="DONE"} reads exactly the same
	 * {@code detailJson} shape {@link #stepResetConfirm}'s old synchronous success branch used to read from
	 * {@link StepResetter#reset}'s own direct return value; anything else (a refused confirm caught only inside
	 * the job, {@code FAILED}, {@code CANCELLED}, or the job/project somehow gone) re-reads the plan fresh and
	 * shows the job's own message as the refusal - same "re-plan on failure" idiom {@link #stepResetConfirm}'s
	 * own catch block already uses.
	 */
	private WizardPage.StepResetView stepResetStatusView(final String pStage) throws OperationException
	{
		final String projectCode = this.projectCode();
		if (projectCode == null || projectCode.isEmpty())
		{
			return this.stepReset;
		}
		final ObjectNode status = StepResetRun.status(this.factory, projectCode, pStage);
		if ("DONE".equals(status.path("status").asText("")))
		{
			final JsonNode reportJson;
			try
			{
				reportJson = new com.fasterxml.jackson.databind.ObjectMapper().readTree(status.path("detailJson").asText("{}"));
			}
			catch (final com.fasterxml.jackson.core.JsonProcessingException ex)
			{
				// the audit row's own detail somehow isn't valid JSON - fall through to the plain message below
				final WizardPage.StepResetView fallback = new WizardPage.StepResetView();
				fallback.stage = pStage;
				fallback.done = true;
				fallback.resultOk = true;
				fallback.resultMessage = status.path("message").asText("");
				this.notice = fallback.resultMessage;
				return fallback;
			}
			final WizardPage.StepResetView view = new WizardPage.StepResetView();
			view.stage = pStage;
			view.done = true;
			view.resultOk = true;
			view.resultMessage = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.stepReset.done",
				this.countWords(reportJson.path("stepsReset").asInt(0), "wizard.unit.pipelineStep"),
				this.countWords(ProjectWizardUserService.rowsDeletedTotal(reportJson), "wizard.unit.row"));
			this.notice = view.resultMessage;
			return view;
		}
		final WizardPage.StepResetView view = ProjectWizardUserService.stepResetView(StepResetter.plan(this.session, this.factory, projectCode, pStage));
		final String message = status.path("message").asText("");
		view.refusal = message.isEmpty() ? "The cleanup did not complete - reopen it to try again." : message;
		return view;
	}

	private static int rowsDeletedTotal(final JsonNode pReportJson)
	{
		int total = 0;
		final java.util.Iterator<String> names = pReportJson.path("rowsDeleted").fieldNames();
		while (names.hasNext())
		{
			total += pReportJson.path("rowsDeleted").path(names.next()).asInt(0);
		}
		return total;
	}

	private static WizardPage.StepResetView stepResetView(final JsonNode pPlanJson)
	{
		final WizardPage.StepResetView view = new WizardPage.StepResetView();
		view.stage = pPlanJson.path("from").asText("");
		view.refusal = pPlanJson.hasNonNull("refusal") ? pPlanJson.path("refusal").asText() : null;
		for (final JsonNode itemNode : pPlanJson.path("items"))
		{
			final WizardPage.StepResetItem item = new WizardPage.StepResetItem();
			item.kind = itemNode.path("kind").asText("");
			item.name = itemNode.path("name").asText("");
			item.detail = itemNode.path("detail").asText("");
			item.disposition = itemNode.path("disposition").asText("");
			item.reason = itemNode.path("reason").asText("");
			view.items.add(item);
		}
		final java.util.Iterator<String> rowNames = pPlanJson.path("rowCounts").fieldNames();
		while (rowNames.hasNext())
		{
			final String name = rowNames.next();
			view.rowCounts.put(name, Integer.valueOf(pPlanJson.path("rowCounts").path(name).asInt(0)));
		}
		for (final JsonNode stepNameNode : pPlanJson.path("stepNames"))
		{
			view.stepNames.add(stepNameNode.asText());
		}
		return view;
	}

	private String projectCode()
	{
		// audit F-31: a primary-key lookup, not a whole WizardSnapshot - see WizardData.projectCode
		return WizardData.projectCode(this.factory, this.projectId);
	}

	// ------------------------------------------------------------------------------------------------ step 8: automatic intake

	/**
	 * Automatic-intake wizard lot (2026-10-05): the ten {@code INTAKE_*} actions of the Summary's intake block and screens. Each mutating one re-checks on the server that
	 * the caller may operate the intake ({@link Intake#mayOperate}, the facade's own rule - the wizard is administrators-only already, a hidden or disabled button is chrome,
	 * not a guard) and then calls the ONE facade method of the action; a refusal (an {@link OperationException}, its sentence) reaches the person as the page notice through
	 * {@link #handle}'s own catch. Nothing else is decided here: the engine and the facade keep every rule.
	 * <ul>
	 * <li>{@code INTAKE_OPEN} / {@code INTAKE_CLOSE}: show / hide the sub-screen (no EBX call).</li>
	 * <li>{@code INTAKE_SETTINGS_OPEN} / {@code INTAKE_SETTINGS_CLOSE}: the running screen's settings panel (no EBX call).</li>
	 * <li>{@code INTAKE_SAVE}: {@link Intake#saveSettings}.</li>
	 * <li>{@code INTAKE_SWITCH_ON}: {@link Intake#saveSettings} then {@link Intake#switchOn} in one action; a refused save does not switch on.</li>
	 * <li>{@code INTAKE_READ_NOW}: {@link Intake#readNow} for the application the card's button posted ({@code mdmfIntakeSource}): a background landing load, then the chain follows.</li>
	 * <li>{@code INTAKE_PAUSE} / {@code INTAKE_RESUME} / {@code INTAKE_SWITCH_OFF} / {@code INTAKE_PROCESS_NOW}: {@link Intake#pause} / {@link Intake#resume} /
	 * {@link Intake#switchOff} / {@link Intake#processNow}.</li>
	 * </ul>
	 * "Read now" of a Connectors application is NOT wired: the landing step's one-source action ({@code GENERATE}) regenerates the model and the flows before it loads
	 * (docs/AUTOMATIC-INTAKE.md "The screens"), which is not a read.
	 */
	private void handleIntake(final String pAction, final UserServiceEventContext pContext) throws OperationException
	{
		if ("INTAKE_OPEN".equals(pAction))
		{
			this.intakeOpen = true;
			this.intakeSettingsOpen = false;
			this.intakeDraft = null;
			return;
		}
		if ("INTAKE_CLOSE".equals(pAction))
		{
			this.intakeOpen = false;
			this.intakeSettingsOpen = false;
			this.intakeDraft = null;
			return;
		}
		if ("INTAKE_SETTINGS_OPEN".equals(pAction) || "INTAKE_SETTINGS_CLOSE".equals(pAction))
		{
			this.intakeSettingsOpen = "INTAKE_SETTINGS_OPEN".equals(pAction);
			this.intakeDraft = null;
			return;
		}
		final String projectCode = this.readProjectCode();
		if (projectCode.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.err.noProject"));
			return;
		}
		if (!Intake.mayOperate(this.session, this.factory))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.notOperator"));
			return;
		}
		if ("INTAKE_SAVE".equals(pAction))
		{
			if (this.saveIntakeSettings(pContext, projectCode))
			{
				this.intakeSettingsOpen = false;
				this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.notice.saved");
				this.noticeKind = "ok";
			}
			return;
		}
		if ("INTAKE_SWITCH_ON".equals(pAction))
		{
			if (!this.saveIntakeSettings(pContext, projectCode))
			{
				return; // a refused save never switches on: the sentence is already the page notice, what was typed is shown again
			}
			// F2-102: the installation of the triggers (one publish pair per application) is a queued job; the cheap checks and their refusals are made here
			final ObjectNode queued = IntakeSwitchRun.startOn(this.session, this.factory, projectCode);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
				queued.path("started").asBoolean(false) ? "wizard.intake.notice.switchOnQueued" : "wizard.intake.notice.switchedOn");
			this.noticeKind = "ok";
			return;
		}
		if ("INTAKE_PAUSE".equals(pAction))
		{
			Intake.pause(this.session, this.factory, projectCode);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.notice.paused");
			this.noticeKind = "ok";
			return;
		}
		if ("INTAKE_RESUME".equals(pAction))
		{
			Intake.resume(this.session, this.factory, projectCode);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.notice.resumed");
			this.noticeKind = "ok";
			return;
		}
		if ("INTAKE_SWITCH_OFF".equals(pAction))
		{
			final ObjectNode queued = IntakeSwitchRun.startOff(this.session, this.factory, projectCode); // F2-102: the removal of the triggers is a queued job
			this.intakeOpen = false; // OFF: back to the Summary, where "Set up" is offered again
			this.intakeSettingsOpen = false;
			this.intakeDraft = null;
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale,
				queued.path("started").asBoolean(false) ? "wizard.intake.notice.switchOffQueued" : "wizard.intake.notice.switchedOff");
			this.noticeKind = "ok";
			return;
		}
		if ("INTAKE_READ_NOW".equals(pAction))
		{
			// the application posted by the card's own button (data-mdmf-intake-source -> mdmfIntakeSource), read here in the event callback; Intake.readNow re-checks everything
			final String source = ProjectWizardUserService.param(pContext, "mdmfIntakeSource");
			Intake.readNow(this.session, this.factory, projectCode, source);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.notice.readNow", source);
			this.noticeKind = "ok";
			return;
		}
		if ("INTAKE_PROCESS_NOW".equals(pAction))
		{
			final com.on.ebx.factory.intake.IntakeStatus status = Intake.processNow(this.session, this.factory, projectCode);
			this.notice = Messages.get(ProjectWizardUserService.class, this.locale, status.queuedAfterChain ? "wizard.intake.notice.processNowQueued" : "wizard.intake.notice.processNow");
			this.noticeKind = "ok";
		}
	}

	/**
	 * Reads the settings form (the fields {@code IntakeScreens} renders: {@code mdmfIntakeCount}, {@code mdmfIntakeSrc<i>}/{@code Quiet<i>}/{@code MaxWait<i>}/{@code MinRows<i>}/
	 * {@code Batch<i>}, {@code mdmfIntakeReview}, {@code mdmfIntakeStopOn}/{@code StopPct}) and saves it with {@link Intake#saveSettings}. True when saved. What was typed is
	 * kept in {@link #intakeDraft} first, so a refusal (a number the person mistyped here, or the facade's own range sentence) shows it again; nothing is written when anything
	 * is refused. A post without the form (no {@code mdmfIntakeCount}) is refused rather than read as "every option off".
	 */
	private boolean saveIntakeSettings(final UserServiceEventContext pContext, final String pProjectCode) throws OperationException
	{
		final String count = ProjectWizardUserService.param(pContext, "mdmfIntakeCount");
		if (count.isEmpty())
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.err.noForm"));
			return false;
		}
		final IntakeScreens.Draft draft = new IntakeScreens.Draft();
		draft.reviewRejects = ProjectWizardUserService.paramBool(pContext, "mdmfIntakeReview");
		draft.stopOn = ProjectWizardUserService.paramBool(pContext, "mdmfIntakeStopOn");
		draft.stopPercent = ProjectWizardUserService.param(pContext, "mdmfIntakeStopPct");
		int sourceCount;
		try
		{
			sourceCount = Integer.parseInt(count);
		}
		catch (final NumberFormatException ex)
		{
			sourceCount = 0;
		}
		for (int i = 0; i < sourceCount; i++)
		{
			final IntakeScreens.SourceDraft source = new IntakeScreens.SourceDraft();
			source.sourceSystem = ProjectWizardUserService.param(pContext, "mdmfIntakeSrc" + i);
			source.quietSeconds = ProjectWizardUserService.param(pContext, "mdmfIntakeQuiet" + i);
			source.maxWaitMinutes = ProjectWizardUserService.param(pContext, "mdmfIntakeMaxWait" + i);
			source.minRows = ProjectWizardUserService.param(pContext, "mdmfIntakeMinRows" + i);
			source.batchSignal = ProjectWizardUserService.paramBool(pContext, "mdmfIntakeBatch" + i);
			draft.sources.add(source);
		}
		this.intakeDraft = draft;

		Integer stopPercent = null;
		if (draft.stopOn)
		{
			stopPercent = ProjectWizardUserService.optionalInt(draft.stopPercent);
			if (stopPercent == null)
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.err.stopPercent"));
				return false;
			}
		}
		final List<Intake.SourceSettings> sources = new ArrayList<Intake.SourceSettings>();
		for (final IntakeScreens.SourceDraft source : draft.sources)
		{
			final Intake.SourceSettings settings = new Intake.SourceSettings();
			settings.sourceSystem = source.sourceSystem;
			settings.quietSeconds = ProjectWizardUserService.optionalInt(source.quietSeconds);
			settings.maxWaitMinutes = ProjectWizardUserService.optionalInt(source.maxWaitMinutes);
			if (!source.minRows.isEmpty())
			{
				settings.minRows = ProjectWizardUserService.optionalInt(source.minRows);
				if (settings.minRows == null)
				{
					this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.err.minRows"));
					return false;
				}
			}
			settings.batchSignal = Boolean.valueOf(source.batchSignal);
			sources.add(settings);
		}
		Intake.saveSettings(this.session, this.factory, pProjectCode, Boolean.valueOf(draft.reviewRejects), stopPercent, sources);
		this.intakeDraft = null;
		return true;
	}

	/** A whole number, or null when the text is empty or not one. */
	private static Integer optionalInt(final String pText)
	{
		if (pText == null || pText.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return Integer.valueOf(Integer.parseInt(pText.trim()));
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	/**
	 * Builds {@link WizardPage.Model#intake} for the Summary step: the facade's status (read-only, cheap - no row counting) and the screen's transient state. A status that cannot
	 * be read (the facade's sentence, or an unexpected failure) is carried as {@link IntakeScreens.Input#error}: the Summary still renders, its intake block shows the sentence.
	 */
	private void buildIntake(final WizardSnapshot pSnapshot, final WizardPage.Model pModel)
	{
		if (pSnapshot.project == null || this.projectId == null || this.projectId.isEmpty())
		{
			return;
		}
		final IntakeScreens.Input in = new IntakeScreens.Input();
		try
		{
			in.status = Intake.status(this.session, this.factory, pSnapshot.project.code);
		}
		catch (final OperationException ex)
		{
			in.error = EngineNotice.inline(ex, this.locale);
		}
		catch (final RuntimeException ex)
		{
			in.error = ex.getClass().getSimpleName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		}
		in.installLine = IntakeSwitchRun.stateLine(this.factory, this.projectId, this.locale); // F2-102: installing, or interrupted - which applications have their triggers
		in.open = this.intakeOpen;
		in.settingsOpen = this.intakeSettingsOpen;
		in.draft = this.intakeDraft;
		pModel.intake = in;
	}

	// ------------------------------------------------------------------------------------------------ step 7: summary

	/**
	 * Wizard-summary lot (2026-09-24): "Chain the loads" -> launches {@code MdmFactoryPipeline} for this
	 * project ({@link PipelineWorkflowLauncher}). Administrators only, re-checked here the same defence-in-
	 * depth every other agent-account action in this class already applies ({@link #suggestStagingLlm}'s own
	 * Javadoc) - the button {@code WizardPage} draws is disabled client-side, which is chrome, not a guard.
	 */
	private void launchPipeline() throws OperationException
	{
		final WizardSnapshot snapshot = this.requestSnapshot();
		if (snapshot.project == null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.gate.noProject"));
			return;
		}
		// audit F-02: DESIGN, or the pipeline launcher role (the workflow's own gate, workflow.PipelineLauncher, checks the same)
		if (!com.on.ebx.factory.ebx.FactoryAccess.mayOperatePipeline(this.session, this.factory))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.gate.adminOnly"));
			return;
		}
		final com.orchestranetworks.instance.Repository repository = this.factory.getHome().getRepository();
		if (!PipelineWorkflowLauncher.isPublished(repository))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.gate.notPublished"));
			return;
		}
		// F2-102: a queued job (WizardActionRuns.PIPELINE_STEP); the instance's creator stays this person
		WizardActionRuns.startPipeline(this.session, this.factory, snapshot.project.code);
		this.notice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.jobs.startedNotice");
	}

	/**
	 * Wizard-summary lot (2026-09-24): "Check the delivery domain" -> the SAME read-only route {@code
	 * DeliveryDomainReader}/{@code GET /projects/{code}/delivery/domain} already exposes (docs/DELIVERY.md),
	 * called directly rather than through an HTTP loopback - never a new delivery engine, per the task
	 * brief. Any authenticated user may call it (the REST route itself carries no extra permission check,
	 * docs/DELIVERY.md sec 4) - this class still requires a saved project.
	 */
	private void checkDelivery() throws OperationException
	{
		final String projectCode = this.projectCode();
		if (projectCode == null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.gate.noProject"));
			return;
		}
		final ObjectNode domain = this.asUserRead(() -> com.on.ebx.factory.ebx.DeliveryDomainReader.read(this.factory.getHome().getRepository(), this.factory, projectCode));
		final int componentCount = domain.path("components").size();
		final JsonNode preCheck = domain.path("preCheck");
		int errors = 0;
		int warnings = 0;
		for (final JsonNode issue : preCheck.path("items"))
		{
			final String level = issue.path("level").asText("");
			if (com.on.ebx.factory.delivery.DeliveryPreCheck.ERROR.equals(level))
			{
				errors++;
			}
			else if (com.on.ebx.factory.delivery.DeliveryPreCheck.WARN.equals(level))
			{
				warnings++;
			}
		}
		this.deliveryCheckSummary = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.prod.deliver.checkResult",
			this.countWords(componentCount, "wizard.unit.component"), this.countWords(errors, "wizard.unit.error"), this.countWords(warnings, "wizard.unit.warning"));
	}

	/**
	 * Wizard-summary lot (2026-09-24): builds {@link WizardSummaryData.Input} for {@link #model()}. Combines
	 * what {@link WizardSnapshot} already carries (sources, staging feeds/mappings/quality-constraint
	 * proposal status, match plans) with the two small readers this lot added: {@link WizardSummaryReader}
	 * (staging load counters, pipeline-step durations, unresolved reject count - {@code CockpitData} does
	 * not carry per-entity master volumes or step durations, and duplicating its own EBX plumbing risked a
	 * merge conflict with the parallel step 6 lot touching the same package) and {@link GoldenRecordsReader}
	 * (master row counts, golden/pending-suspect counts, golden-record fill rate - CLAUDE.md &sect;8). Null
	 * ({@link WizardPage.Model#summary} stays null) when the project has not been saved yet.
	 */
	private void buildSummary(final WizardSnapshot pSnapshot, final WizardPage.Model pModel)
	{
		if (pSnapshot.project == null || this.projectId == null || this.projectId.isEmpty())
		{
			return;
		}
		final String dataspaceKey = this.factory.getHome().getKey().format();
		final String datasetName = this.factory.getAdaptationName().getStringName();
		final WizardSummaryData.Input in = new WizardSummaryData.Input();
		in.projectLabel = pSnapshot.project.label.isEmpty() ? pSnapshot.project.code : pSnapshot.project.label;

		// ---- sources / landing
		long landingRows = 0;
		int landingTableCount = 0;
		int landingOtherTableCount = 0;
		boolean landingLoaded = false;
		String landingOpenUrl = "";
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			final WizardSummaryData.SourceInput si = new WizardSummaryData.SourceInput();
			si.name = source.sourceSystem;
			long sourceRows = 0;
			for (final WizardSnapshot.TableInfo table : source.tables)
			{
				// UX lot U3 (2026-10-13): the Landing card counts the tables that were kept (ticked) and loaded; the unticked ones found in the
				// source are a side note (it read "19 tables" for 4 loaded and 15 left unticked), and their rows are not "received"
				if (!table.selected)
				{
					landingOtherTableCount++;
					continue;
				}
				sourceRows += table.lastLoadRows > 0 ? table.lastLoadRows : table.sourceRowCount;
				if ("LOADED".equals(table.status))
				{
					landingTableCount++;
					landingLoaded = true;
				}
				if (landingOpenUrl.isEmpty() && !source.landingDataset.isEmpty())
				{
					landingOpenUrl = FactoryLinks.genericTableUrl(pSnapshot.project.landingDataspace, source.landingDataset, "/root/" + table.tableName);
				}
			}
			si.receivedRows = sourceRows;
			landingRows += sourceRows;
			in.sources.add(si);
		}
		in.landingTableCount = landingTableCount;
		in.landingOtherTableCount = landingOtherTableCount;
		in.landingDataspace = pSnapshot.project.landingDataspace;
		in.landingLoaded = landingLoaded;
		in.landingOpenUrl = landingOpenUrl;

		// ---- staging
		final java.util.Map<String, WizardSummaryReader.EntityLoad> loadsByName = new java.util.LinkedHashMap<String, WizardSummaryReader.EntityLoad>();
		for (final WizardSummaryReader.EntityLoad load : WizardSummaryReader.readEntityLoads(this.view(), this.factory, this.projectId))
		{
			loadsByName.put(load.entityName, load);
		}
		int feedCount = 0;
		int mappingCount = 0;
		int qualityAccepted = 0;
		int constraintsApplied = 0;
		String stagingOpenUrl = "";
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (ReviewLine.SUPERSEDED.equals(entity.status))
			{
				continue; // absorbed rule entity - not its own row of the staging zone
			}
			feedCount += entity.feeds.size();
			for (final WizardSnapshot.FeedInfo feed : entity.feeds)
			{
				mappingCount += feed.mappings.size();
				if (stagingOpenUrl.isEmpty() && feed.parentFeedId.isEmpty() && !feed.matrixUrl.isEmpty())
				{
					stagingOpenUrl = feed.matrixUrl;
				}
			}
			for (final WizardSnapshot.ProposalLineInfo line : entity.proposalLines)
			{
				final boolean acceptedLike = ReviewLine.ACCEPTED.equals(line.line.status) || ReviewLine.APPLIED.equals(line.line.status);
				if (!acceptedLike)
				{
					continue;
				}
				if (ReviewLine.QUALITY.equals(line.line.nature))
				{
					qualityAccepted++;
				}
				else if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.line.nature))
				{
					constraintsApplied++;
				}
			}
			final boolean inScope = ReviewLine.ACCEPTED.equals(entity.status) || ReviewLine.APPLIED.equals(entity.status);
			if (!inScope)
			{
				continue;
			}
			final WizardSummaryData.ZoneEntityInput zi = new WizardSummaryData.ZoneEntityInput();
			zi.name = entity.name;
			final WizardSummaryReader.EntityLoad load = loadsByName.get(entity.name);
			if (load != null)
			{
				zi.rows = load.written;
				zi.rejected = load.rejected;
			}
			in.stagingEntities.add(zi);
		}
		in.stagingFeedCount = feedCount;
		in.stagingMappingCount = mappingCount;
		in.qualityRulesAccepted = qualityAccepted;
		in.constraintsApplied = constraintsApplied;
		in.stagingDataspace = pSnapshot.project.stagingDataspace;
		in.stagingOpenUrl = stagingOpenUrl;

		// ---- master / matching (GoldenRecordsReader - CLAUDE.md ss8)
		final com.on.ebx.factory.master.MasterModel.Result masterModel = ProjectWizardUserService.readMasterModel(this.factory, this.projectId);
		final GoldenRecordsReader.Result golden = this.readGoldenRecords(pSnapshot, masterModel);
		for (final String entityName : masterModel.builtEntities)
		{
			final WizardSummaryData.ZoneEntityInput zi = new WizardSummaryData.ZoneEntityInput();
			zi.name = entityName;
			final Long rows = golden.rowsByEntity.get(entityName);
			zi.rows = rows == null ? Long.valueOf(0) : rows;
			zi.golden = golden.goldenByEntity.get(entityName);
			zi.merged = golden.mergedByEntity.get(entityName);
			in.masterEntities.add(zi);
		}
		in.masterMainEntity = ProjectWizardUserService.mainEntity(masterModel, golden);
		in.masterDataspace = pSnapshot.project.masterDataspace;
		in.masterDataset = pSnapshot.project.masterDataset;
		if (!in.masterEntities.isEmpty())
		{
			in.masterOpenUrl = FactoryLinks.genericTableUrl(pSnapshot.project.masterDataspace, pSnapshot.project.masterDataset,
				com.on.ebx.factory.master.MasterModel.tablePath(in.masterEntities.get(0).name));
		}
		in.matchingAvailable = golden.matchingAvailable;
		in.goldenCount = golden.goldenCount;
		in.pendingSuspects = golden.pendingSuspects;
		in.matchPlansApplied = WizardRules.matchPlansApplied(pSnapshot);
		in.matchPlansTotal = pSnapshot.matchPlans.size();
		// UX lot U3 (2026-10-13): every master object with its plan and the outcome of its last matching run - the Matching card, its open points and the
		// scope of the completeness card all read these (the card used to say "2 plans applied, out of 2" after a failed run and with a third object planless)
		// lot M2 (2026-10-17): a child object that follows its parent is not "an object without a plan" - it takes no part in these counts
		final java.util.Set<String> followingWithoutPlan = new java.util.HashSet<String>();
		for (final com.on.ebx.factory.wizard.MatchingPlanCoverage.Follower follower : com.on.ebx.factory.wizard.MatchingPlanCoverage.followers(pSnapshot))
		{
			if (ProjectWizardUserService.planOf(pSnapshot, follower.entity) == null)
			{
				followingWithoutPlan.add(follower.entity);
				// lot S7 (2026-10-18): its completeness is measured on the golden copies the cascade merge gave its table (none / no metadataset = not matched yet)
				final Long followerGolden = golden.goldenByEntity.get(follower.entity);
				in.followerGolden.put(follower.entity, Long.valueOf(followerGolden == null ? 0L : followerGolden.longValue()));
			}
		}
		for (final WizardSummaryData.ZoneEntityInput masterEntity : in.masterEntities)
		{
			if (followingWithoutPlan.contains(masterEntity.name))
			{
				continue;
			}
			final WizardSnapshot.MatchPlanInfo plan = ProjectWizardUserService.planOf(pSnapshot, masterEntity.name);
			in.matchObjects.add(new WizardSummaryData.MatchObjectInput(masterEntity.name, plan == null ? "" : plan.status,
				plan == null || plan.runStep == null ? "" : plan.runStep.status));
		}
		for (final GoldenRecordsReader.AttributeFill fill : golden.attributes)
		{
			final WizardSummaryData.CompletenessInput row = new WizardSummaryData.CompletenessInput();
			row.attributeLabel = fill.name;
			row.filled = fill.filled;
			row.total = fill.total;
			row.entityName = fill.entityName;
			row.attribute = fill.attribute;
			in.completeness.add(row);
		}
		in.completenessMasterFallback = golden.masterFallback;

		// ---- open points
		final int unresolvedRejects = WizardSummaryReader.unresolvedRejectCount(this.view(), this.factory, this.projectId);
		if (unresolvedRejects > 0)
		{
			in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", Messages.get(ProjectWizardUserService.class, this.locale, ProjectWizardUserService.pluralKey("wizard.step7.open.rejects.title", unresolvedRejects),
				Integer.valueOf(unresolvedRejects)), Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.rejects.detail"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.gotoRejects"), 0, FactoryLinks.rejectsUrl(dataspaceKey, datasetName, pSnapshot.project.code))); // wave 10 (audit F-38): the review screen, not the Cockpit
		}
		if (in.pendingSuspects > 0)
		{
			in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN", Messages.get(ProjectWizardUserService.class, this.locale, ProjectWizardUserService.pluralKey("wizard.step7.open.suspects.title", in.pendingSuspects),
				Long.valueOf(in.pendingSuspects)), Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.suspects.detail"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.decide"), WizardRules.STEP_MATCHING, ""));
		}
		// UX lot U3: a matching run that FAILED and a business object with no plan while others have one are open points (neither was listed)
		for (final String failedObject : WizardSummaryData.failedRuns(in))
		{
			in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR", Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.matchFailed.title", failedObject),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.matchFailed.detail"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.matching"), WizardRules.STEP_MATCHING, ""));
		}
		// UX lot D: a partial failure nobody resolved (tables not loaded, objects refused at configuration, objects that failed a run) stays an open point
		// until a later run of the same kind succeeds; the objects already flagged above as "last matching run failed" are not repeated
		in.openPoints.addAll(com.on.ebx.factory.wizard.PartialFailures.openPoints(pModel.lastOutcomes,
			pSnapshot, WizardSummaryData.failedRuns(in), this.locale));
		for (final String planless : WizardSummaryData.withoutPlan(in))
		{
			in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN", Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.noPlan.title", planless),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.noPlan.detail"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.matching"), WizardRules.STEP_MATCHING, ""));
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				if (!attribute.mappingWarning.isEmpty())
				{
					in.openPoints.add(new WizardSummaryData.OpenPointInput("ERROR",
						Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.mappingGap.title", entity.name, attribute.name),
						Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.mappingGap.detail"),
						Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.mapping"), WizardRules.STEP_STAGING, ""));
				}
			}
		}
		for (final java.util.Map.Entry<String, Integer> entry : pSnapshot.proposedCounts.entrySet())
		{
			final int count = entry.getValue() == null ? 0 : entry.getValue().intValue();
			if (count <= 0)
			{
				continue;
			}
			final int gotoStep = ReviewLine.MATCH.equals(entry.getKey()) ? WizardRules.STEP_MATCHING : WizardRules.STEP_STAGING;
			in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN",
				Messages.get(ProjectWizardUserService.class, this.locale, ProjectWizardUserService.pluralKey("wizard.step7.open.proposed.title", count), Integer.valueOf(count),
					Messages.get(ProjectWizardUserService.class, this.locale, "review.nature." + entry.getKey())),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.proposed.detail"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.decide"), gotoStep, ""));
		}
		// automatic-intake wizard lot (2026-10-05): the "no schedule" point and the "intake paused" point are decided by WizardSummaryData.withIntake from the intake's state -
		// the caller only words them (the pause reason is data) and hands over the state it read (empty when the status could not be read: treated as OFF)
		final com.on.ebx.factory.intake.IntakeStatus intakeStatus = pModel.intake == null ? null : pModel.intake.status;
		in.intakeState = intakeStatus == null || intakeStatus.state == null ? "" : intakeStatus.state;
		if (landingLoaded)
		{
			in.noSchedulePoint = new WizardSummaryData.OpenPointInput("INFO", Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.schedule.title"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.schedule.detail"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.prod.chain.schedule"), 0, "");
		}
		if ("PAUSED".equals(in.intakeState))
		{
			in.intakePausedPoint = new WizardSummaryData.OpenPointInput("WARN", Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.openPoint.paused.title"),
				Messages.get(ProjectWizardUserService.class, this.locale, "wizard.intake.openPoint.paused.detail", com.on.ebx.factory.wizard.IntakeScreens.pauseReasonText(this.locale, intakeStatus.pauseReason)),
				"", 0, "");
		}
		// coordinator review (2026-09-24): the "Distribute" card's own rows and a "<target> not armed" open
		// point per REALTIME target still not ARMED - same best-effort read (ebx.DistributionReader.listTargets)
		// buildDistribution already uses for step 7, reusing its own targetRow(JsonNode) mapper so the two
		// screens never disagree on what a target looks like.
		try
		{
			final JsonNode targets = this.asUserRead(() -> DistributionReader.listTargets(this.factory, pSnapshot.project.code)).path("targets");
			for (final JsonNode targetJson : targets)
			{
				final WizardPage.DistributionTargetRow row = ProjectWizardUserService.targetRow(targetJson);
				final WizardSummaryData.DistributionSummaryTarget dst = new WizardSummaryData.DistributionSummaryTarget();
				dst.label = row.connectorLabel.isEmpty() ? row.connector : row.connectorLabel;
				dst.mode = row.mode;
				dst.state = row.state;
				dst.madeFirstFullSend = DistributionTexts.madeFirstFullSend(row.firstFullSendRunId);
				dst.lastSendAt = row.lastSendAt;
				dst.lastMessage = row.lastMessage;
				if ("REALTIME".equals(row.mode))
				{
					// lot S2 (2026-10-18): "N retire notices sent" on the Distribute card
					try
					{
						final JsonNode facts = this.asUserRead(() -> DistributionReader.retireFacts(this.factory, row.id));
						dst.retiresSent = facts.path("retiresSent").asInt(0);
						dst.retiresCapped = facts.path("retiresCapped").asBoolean(false);
					}
					catch (final OperationException ex)
					{
						dst.retiresSent = -1;
					}
				}
				in.distributionTargets.add(dst);
				// step 7 screen counts (2026-10-18): a target the person disabled AFTER its first full send is not "not armed yet" - the Distribute card says it did send; only a
				// target that never made its first full send is an open point
				if (DistributionTexts.notArmedIsOpenPoint(row.mode, row.state, dst.madeFirstFullSend))
				{
					in.openPoints.add(new WizardSummaryData.OpenPointInput("WARN",
						Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.targetNotArmed.title", dst.label),
						Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.targetNotArmed.detail"),
						Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.open.targetNotArmed.action"), WizardRules.STEP_DISTRIBUTION, "", row.id));
				}
			}
		}
		catch (final OperationException ex)
		{
			// best-effort - a Connectors/distribution read failure here must never take down the rest of step 8
		}
		try
		{
			final JsonNode existingProduct = DataProductPublication.existingForSummary(this.session, this.factory, pSnapshot.project.code);
			in.distributionProductExists = existingProduct.path("exists").asBoolean(false);
			// audit F-34: the product may exist and be hidden from this person by their catalog right (DataProductPublication.existingForSummary)
			in.distributionProductRestricted = existingProduct.path("catalogRestricted").asBoolean(false);
			in.distributionProductLabel = existingProduct.path("name").asText("");
			in.distributionProductRefreshMode = existingProduct.path("refreshMode").asText("ON_DEMAND");
		}
		catch (final RuntimeException ex)
		{
			// best-effort - see the try block above
		}

		// ---- go to production
		// audit F-02: DESIGN, or the pipeline launcher role
		final boolean mayLaunch = com.on.ebx.factory.ebx.FactoryAccess.mayOperatePipeline(this.session, this.factory);
		in.canLaunchPipeline = mayLaunch && PipelineWorkflowLauncher.isPublished(this.factory.getHome().getRepository());
		in.pipelineReason = mayLaunch ? "wizard.step7.gate.notPublished" : "wizard.step7.gate.adminOnly";
		in.scheduleAvailable = false;
		// audit F-40: until the screen schedules a run itself, "Schedule..." opens the user guide's section on scheduling
		in.scheduleHref = this.helpUrl.isEmpty() ? "" : this.helpUrl + "#schedule";
		in.canCheckDelivery = true;
		in.deliveryReason = "";
		in.deliveryHref = "";
		in.deliveryDocHref = this.helpUrl.isEmpty() ? "" : this.helpUrl + "#delivery";
		in.cockpitUrl = FactoryLinks.cockpitUrl(dataspaceKey, datasetName, pSnapshot.project.code);
		in.recordLineageUrl = CockpitPage.lineageUrl(pSnapshot.project.code);

		// ---- last full run (WizardSummaryReader - PipelineStep startedAt/endedAt, WizardSnapshot has millis only)
		final List<WizardSummaryReader.StepRun> steps = WizardSummaryReader.readSteps(this.view(), this.factory, this.projectId);
		this.journalStep(in, steps, "LANDING_LOAD", "LANDING", this.countWords(landingRows, "wizard.unit.row"));
		final long stagingRows = ProjectWizardUserService.sumZoneRows(in.stagingEntities);
		this.journalStep(in, steps, "STAGING_LOAD", "STAGING",
			this.countWords(stagingRows, "wizard.unit.row"));
		final long masterRows = ProjectWizardUserService.sumZoneRows(in.masterEntities);
		this.journalStep(in, steps, "MASTER_LOAD", "MASTER", this.countWords(masterRows, "wizard.unit.row"));
		// step 7 screen counts (2026-10-18): the records the matching runs COMPARED are read in each run's own message (its scope sentence: the pivots), never the golden records
		// of the master (980 "compared" was the golden records of three tables added up); a run that does not say leaves the figure out
		final java.util.Map<String, Long> comparedByObject = new java.util.LinkedHashMap<String, Long>();
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if (plan.runStep != null && "DONE".equals(plan.runStep.status))
			{
				final long pivots = com.on.ebx.factory.landing.RunTexts.pivotCount(plan.runStep.message);
				if (pivots >= 0L)
				{
					comparedByObject.put(plan.entityName, Long.valueOf(pivots));
				}
			}
		}
		this.journalStep(in, steps, "MATCHING_RUN", "MATCHING", DistributionTexts.comparedText(comparedByObject,
			Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7.journal.compared")));

		pModel.summaryInput = in;
		pModel.summary = WizardSummaryData.compute(in);
	}

	/** The match plan of a business object (by name), or null: the furthest one when several rows exist (APPLIED, then ACCEPTED, then PROPOSED, else the first found). */
	private static WizardSnapshot.MatchPlanInfo planOf(final WizardSnapshot pSnapshot, final String pEntityName)
	{
		WizardSnapshot.MatchPlanInfo found = null;
		int foundRank = -1;
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if (!pEntityName.equals(plan.entityName))
			{
				continue;
			}
			final int rank = "APPLIED".equals(plan.status) ? 3 : "ACCEPTED".equals(plan.status) ? 2 : "PROPOSED".equals(plan.status) ? 1 : 0;
			if (rank > foundRank)
			{
				found = plan;
				foundRank = rank;
			}
		}
		return found;
	}

	/** {@code com.on.ebx.factory.master.MasterModel.buildFromStaging} on the project's own staging entities - shared by {@link #buildSummary} and {@link #buildDistribution} (coordinator review, 2026-09-24 - "do not duplicate the entity-spec assembly"). */
	private static com.on.ebx.factory.master.MasterModel.Result readMasterModel(final com.onwbp.adaptation.Adaptation pFactory, final String pProjectId)
	{
		final ProjectMasterEntities.Result masterEntities = ProjectMasterEntities.readEntities(pFactory, pProjectId);
		return com.on.ebx.factory.master.MasterModel.buildFromStaging(masterEntities.entities);
	}

	/**
	 * Builds the {@code GoldenRecordsReader.EntitySpec} list (one per built master entity, its own accepted-
	 * like business attributes) and reads golden counts/fill rates in one call - the exact assembly {@code
	 * buildSummary} always needed for step 8's completeness card, now shared with {@code buildDistribution}'s
	 * "the master holds N golden records" sentence (coordinator review, 2026-09-24) so the two screens never
	 * duplicate this read or disagree on the count.
	 */
	private GoldenRecordsReader.Result readGoldenRecords(final WizardSnapshot pSnapshot, final com.on.ebx.factory.master.MasterModel.Result pMasterModel)
	{
		final ProjectMasterEntities.Result masterEntities = ProjectMasterEntities.readEntities(this.factory, this.projectId);
		final java.util.Map<String, com.on.ebx.factory.staging.StagingModel.EntityInfo> entityInfoByName =
			new java.util.LinkedHashMap<String, com.on.ebx.factory.staging.StagingModel.EntityInfo>();
		for (final com.on.ebx.factory.staging.StagingModel.EntityInfo entityInfo : masterEntities.entities)
		{
			entityInfoByName.put(entityInfo.name, entityInfo);
		}
		final List<GoldenRecordsReader.EntitySpec> specs = new ArrayList<GoldenRecordsReader.EntitySpec>();
		for (final String entityName : pMasterModel.builtEntities)
		{
			final com.on.ebx.factory.staging.StagingModel.EntityInfo entityInfo = entityInfoByName.get(entityName);
			final List<String> businessAttributes = new ArrayList<String>();
			if (entityInfo != null)
			{
				for (final com.on.ebx.factory.staging.StagingModel.AttributeInfo attribute : entityInfo.attributes)
				{
					final boolean acceptedLike = ReviewLine.ACCEPTED.equals(attribute.status) || ReviewLine.APPLIED.equals(attribute.status);
					if (acceptedLike && !attribute.primaryKey)
					{
						businessAttributes.add(attribute.name);
					}
				}
			}
			specs.add(new GoldenRecordsReader.EntitySpec(entityName, com.on.ebx.factory.master.MasterModel.tablePath(entityName), businessAttributes));
		}
		// wave 2 (2026-09-30): cached per (project, master dataset, matching epoch, latest master load/matching runs) - see GoldenRecordsCache
		final String cacheKey = GoldenRecordsCache.key(this.projectId, pSnapshot.project.masterDataspace, pSnapshot.project.masterDataset,
			MatchingResultsStore.epoch(this.projectId), ProjectWizardUserService.masterChangeToken(pSnapshot))
			// second permissions pass (2026-10-14): the figures are counted with the person's session (records the person may see): one entry per person
			+ "|user=" + FactoryHtml.userId(this.session);
		final GoldenRecordsReader.Result cached = GoldenRecordsCache.get(cacheKey);
		if (cached != null)
		{
			return cached;
		}
		final GoldenRecordsReader.Result fresh = this.asUser(() -> GoldenRecordsReader.read(this.session, this.factory.getHome().getRepository(),
			pSnapshot.project.masterDataspace, pSnapshot.project.masterDataset, specs));
		GoldenRecordsCache.put(cacheKey, fresh);
		return fresh;
	}

	/** Step 7 screen counts (2026-10-18): the main master object, see {@link MasterMain} - the built object no other built object is a child of (a foreign key to it), the largest when there are several. */
	static String mainEntity(final com.on.ebx.factory.master.MasterModel.Result pMasterModel, final GoldenRecordsReader.Result pGolden)
	{
		final java.util.Set<String> built = new java.util.HashSet<String>(pMasterModel.builtEntities);
		final java.util.Set<String> children = new java.util.HashSet<String>();
		for (final com.on.ebx.factory.master.MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
		{
			if (built.contains(fk.entityName) && built.contains(fk.targetEntityName) && !fk.entityName.equals(fk.targetEntityName))
			{
				children.add(fk.entityName);
			}
		}
		return MasterMain.pick(new ArrayList<String>(pMasterModel.builtEntities), children, pGolden.rowsByEntity);
	}

	/** What changes when the master or its matching did: the start time and status of the latest MASTER_LOAD and of every plan's latest MATCHING_RUN (all already on the snapshot). */
	private static String masterChangeToken(final WizardSnapshot pSnapshot)
	{
		final StringBuilder token = new StringBuilder();
		for (final WizardSnapshot.PipelineStepInfo step : pSnapshot.projectSteps)
		{
			if ("MASTER_LOAD".equals(step.step) || "MASTER_MODEL".equals(step.step))
			{
				token.append(step.step).append(':').append(step.startedAtMillis).append(':').append(step.status).append(';');
			}
		}
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if (plan.runStep != null)
			{
				token.append(plan.entityName).append(':').append(plan.runStep.startedAtMillis).append(':').append(plan.runStep.status).append(';');
			}
		}
		return token.toString();
	}

	// ------------------------------------------------------------------------------------- step 7: distribution

	/**
	 * Distribution-wizard lot (2026-09-24): the EBX-bound assembly point for step 7, same "only called when the
	 * step is the one being shown" rule as {@link #buildSummary}. Reads {@code ebx.DistributionReader}/{@code
	 * DistributionConnections}/{@code DistributionRunner}/{@code DistributionArmingGate} directly - no HTTP
	 * loopback (SPEC-distribution.md/CLAUDE.md 9's own in-JVM rule). Every read here is best-effort: a failure
	 * (a Connectors round trip that cannot reach the MCP bridge, most likely) sets {@code
	 * WizardPage.Model.distribution.error} instead of taking down the rest of the wizard render.
	 */
	private void buildDistribution(final WizardSnapshot pSnapshot, final WizardPage.Model pModel)
	{
		if (pSnapshot.project == null || this.projectId == null || this.projectId.isEmpty())
		{
			return;
		}
		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		d.notice = this.distNotice;
		d.productPanelOpen = this.distProductPanelOpen;
		// coordinator review (2026-09-24): "the master holds N golden records" now reuses the SAME entity-spec
		// assembly/GoldenRecordsReader.read call buildSummary already needs for step 8's completeness card -
		// readMasterModel/readGoldenRecords, factored out right above buildSummary - rather than a second,
		// duplicate read. Best-effort: a project whose master was never built (masterModel.builtEntities empty)
		// or a read failure simply leaves masterGoldenCount at 0 (WizardPage#renderStep7's own null-safe guard
		// renders no such sentence), never a wrong number.
		try
		{
			final com.on.ebx.factory.master.MasterModel.Result masterModel = ProjectWizardUserService.readMasterModel(this.factory, this.projectId);
			if (!masterModel.builtEntities.isEmpty())
			{
				// step 7 screen counts (2026-10-18): the golden records of the MAIN object and the rows of the tables in all - goldenCount (all tables added up) is not a count of golden records
				final GoldenRecordsReader.Result golden = this.readGoldenRecords(pSnapshot, masterModel);
				d.masterMainEntity = ProjectWizardUserService.mainEntity(masterModel, golden);
				final Long mainGolden = golden.goldenByEntity.get(d.masterMainEntity);
				final Long mainRows = golden.rowsByEntity.get(d.masterMainEntity);
				d.masterMainGolden = mainGolden == null || mainGolden.longValue() <= 0L ? -1L : mainGolden.longValue(); // 0 = no run has made a golden yet: the rows are the figure
				d.masterMainRows = mainRows == null ? 0L : mainRows.longValue();
				d.masterGoldenCount = Math.max(0L, d.masterMainGolden);
				long total = 0L;
				for (final Long rows : golden.rowsByEntity.values())
				{
					total += rows.longValue();
				}
				d.masterTotalRows = total;
				d.masterTableCount = masterModel.builtEntities.size();
			}
			// task brief item 4: every master field name, union across every built entity - Kafka key.field
			// warning below. The reserved fields every master table carries on its own are always present.
			d.masterFieldNames.add("id");
			d.masterFieldNames.add("sourceSystem");
			d.masterFieldNames.add("sourceId");
			for (final com.on.ebx.factory.master.MasterModel.FieldInfo field : masterModel.fields)
			{
				d.masterFieldNames.add(field.masterField);
			}
			for (final com.on.ebx.factory.master.MasterModel.ForeignKeyInfo fk : masterModel.foreignKeys)
			{
				d.masterFieldNames.add(fk.fieldName);
			}
			// round 6 (task brief item C): one row per master table, for the 7b "Table -> topic" rows
			// (WizardPage#renderDistributionTableDestinations) - same builtEntities/tablePath this class' own
			// DistributionConfigurator uses to iterate tables when Configure runs.
			for (final String entityName : masterModel.builtEntities)
			{
				final WizardPage.MasterTableRow tableRow = new WizardPage.MasterTableRow();
				tableRow.tablePath = com.on.ebx.factory.master.MasterModel.tablePath(entityName);
				tableRow.label = entityName;
				d.masterTables.add(tableRow);
			}
		}
		catch (final RuntimeException ex)
		{
			// best-effort - see this method's own Javadoc
		}
		try
		{
			final JsonNode targets = this.asUserRead(() -> DistributionReader.listTargets(this.factory, pSnapshot.project.code)).path("targets");
			for (final JsonNode target : targets)
			{
				d.targets.add(ProjectWizardUserService.targetRow(target));
			}
		}
		catch (final OperationException ex)
		{
			d.error = EngineNotice.inline(ex, this.locale);
		}
		// audit F-2-101 (lot B3): a display never calls Connectors. The connections come from the per-person cache; a missing or expired entry is read again
		// on a background thread (the page says "reading the connections" and asks again by itself), and the person's "Refresh the connections" does the same
		final String viewer = SourceOnboarder.viewerId(this.session);
		final DistributionStepFacts.Found connectionFacts = DistributionStepFacts.shared().lookup(viewer, pSnapshot.project.code, DistributionStepFacts.maxAgeMillis());
		if (!connectionFacts.fresh)
		{
			DistributionStepFacts.shared().refreshInBackground(viewer, pSnapshot.project.code, this.distributionFactsReader(pSnapshot.project.code));
		}
		d.connectionsReading = DistributionStepFacts.shared().isReading(viewer, pSnapshot.project.code);
		if (d.connectionsReading)
		{
			pModel.sourcesFactsChecking = true; // the page's own poll (wizard.js data-mdmf-sources-checking) re-renders until the read is in
		}
		if (connectionFacts.entry != null)
		{
			d.connectionsRead = connectionFacts.entry.connections != null;
			d.connectionsAgeSeconds = Math.max(0L, (System.currentTimeMillis() - connectionFacts.entry.readAt) / 1000L);
			if (connectionFacts.entry.readError != null)
			{
				d.connectionsError = connectionFacts.entry.readError;
			}
			if (connectionFacts.entry.connections != null)
			{
				for (final JsonNode connection : connectionFacts.entry.connections.path("connections"))
				{
					final WizardPage.DistributionConnectionRow row = new WizardPage.DistributionConnectionRow();
					row.connector = connection.path("connector").asText("");
					row.label = connection.path("label").asText(row.connector);
					row.type = connection.path("type").asText("");
					row.hasWriteOperation = connection.path("hasWriteOperation").asBoolean(false);
					final JsonNode writeOps = connection.path("writeOperations");
					for (final JsonNode op : writeOps)
					{
						row.writeOperations.add(op.asText(""));
					}
					// task brief item 4: a Kafka-style connector-level destination (topic/key.field) - blank for an
					// ordinary connector, see ebx.DistributionConnections' own Javadoc.
					row.topic = connection.path("topic").asText("");
					row.keyField = connection.path("keyField").asText("");
					d.connections.add(row);
				}
			}
		}
		// step 7 screen counts (2026-10-18): the other projects' codes and connections, for the "likely targets" list
		try
		{
			final JsonNode others = DistributionReader.otherProjects(this.factory, pSnapshot.project.code);
			for (final JsonNode code : others.path("codes"))
			{
				d.otherProjectCodes.add(code.asText(""));
			}
			for (final JsonNode connector : others.path("connectors"))
			{
				d.otherProjectConnectors.add(connector.asText(""));
			}
		}
		catch (final RuntimeException ex)
		{
			// best-effort: without it the list is the one it always was
		}
		final DistributionArmingGate.Readiness readiness = DistributionRunner.readiness(this.factory, this.projectId);
		d.readiness.landingLoadedFromEverySource = readiness.landingLoadedFromEverySource;
		d.readiness.masterBuilt = readiness.masterBuilt;
		d.readiness.firstMatchingRunFinished = readiness.firstMatchingRunFinished;
		d.readiness.noMatchingPlanned = readiness.noMatchingPlanned;

		if (!this.distOpenTargetId.isEmpty())
		{
			d.openTargetId = this.distOpenTargetId;
			if ("NEW".equals(this.distOpenTargetId))
			{
				d.openTarget = new WizardPage.DistributionTargetRow();
				// live bug fix (2026-09-25, task brief item 4): preselect the connector the "Set up" button on
				// that connection's own 7a row named, when it named one this project actually offers.
				if (!this.distOpenNewConnector.isEmpty())
				{
					for (final WizardPage.DistributionConnectionRow connection : d.connections)
					{
						if (connection.connector.equals(this.distOpenNewConnector) && connection.hasWriteOperation)
						{
							d.openTarget.connector = this.distOpenNewConnector;
							break;
						}
					}
				}
			}
			else
			{
				for (final WizardPage.DistributionTargetRow row : d.targets)
				{
					if (row.id.equals(this.distOpenTargetId))
					{
						d.openTarget = row;
						break;
					}
				}
				if (d.openTarget == null)
				{
					this.distOpenTargetId = ""; // the target no longer exists (deleted elsewhere) - fall back to the list
				}
				else
				{
					// "retire notices from the merge log" (SPEC-retire.md, UI lot): board RetireHistory.dc.html -
					// this target's own recent sends, read only for the currently OPEN target (never for every
					// row of the plain 7a list, which would mean one listEvents call per target on every render).
					// Best-effort: a read failure leaves the events list empty, WizardPage#renderDistributionHistory
					// then simply renders nothing rather than surfacing a second error alongside the panel's own.
					try
					{
						final JsonNode events = this.asUserRead(() -> DistributionReader.listEvents(this.factory, pSnapshot.project.code, d.openTarget.id, null, 20)).path("events");
						for (final JsonNode eventJson : events)
						{
							final WizardPage.EventRow event = new WizardPage.EventRow();
							event.id = eventJson.path("id").asText("");
							event.tablePath = eventJson.path("tablePath").asText("");
							event.recordKey = eventJson.path("recordKey").asText("");
							event.change = eventJson.path("change").asText("");
							event.status = eventJson.path("status").asText("");
							event.message = eventJson.path("message").asText("");
							event.reason = eventJson.path("reason").asText("");
							event.mergeId = eventJson.path("mergeId").asText("");
							event.survivorKey = eventJson.path("survivorKey").asText("");
							event.createdAt = eventJson.path("createdAt").asText("");
							d.openTarget.events.add(event);
						}
					}
					catch (final OperationException ex)
					{
						// best-effort - see this block's own comment above
					}
					// lot S2 (2026-10-18): "N retire notices sent - last sweep", REALTIME only (the sweep runs for those); best-effort like the history above
					if ("REALTIME".equals(d.openTarget.mode))
					{
						try
						{
							final JsonNode facts = this.asUserRead(() -> DistributionReader.retireFacts(this.factory, d.openTarget.id));
							d.openTarget.retiresSent = facts.path("retiresSent").asInt(0);
							d.openTarget.retiresCapped = facts.path("retiresCapped").asBoolean(false);
							d.openTarget.lastSweepAt = facts.path("lastSweepAt").asText("");
						}
						catch (final OperationException ex)
						{
							// best-effort
						}
					}
				}
			}
		}

		if (this.distProductPanelOpen)
		{
			this.buildDistributionProduct(pSnapshot, d);
		}
		else
		{
			// audit F-2-101 (lot B3): the warehouse facts are the cached ones; the closed panel only shows whether a Snowflake connection exists and what the last
			// explicit test said - the probe itself never runs from a display
			final JsonNode snowflake = DistributionStepFacts.warehousesJson(connectionFacts.entry).path("snowflake");
			d.product.catalogInstalled = CatalogReader.dataset(this.factory.getHome().getRepository()) != null;
			d.product.snowflakePresent = snowflake.path("present").asBoolean(false);
			d.product.snowflakeTested = snowflake.path("tested").asBoolean(false);
			d.product.snowflakeReachable = snowflake.path("reachable").asBoolean(false);
		}

		pModel.distribution = d;
		this.distNotice = null; // shown once, same "cleared after being copied to the model" convention as this.notice
	}

	/** The two reads {@link DistributionStepFacts} may make, as this person (audit F-2-101): handed to a background thread or to an explicit action, never called by a display. */
	private DistributionStepFacts.Reader distributionFactsReader(final String pProjectCode)
	{
		final Session readerSession = this.session;
		final Adaptation readerFactory = this.factory;
		return new DistributionStepFacts.Reader()
		{
			@Override
			public ObjectNode readConnections() throws OperationException
			{
				return DistributionConnections.listCandidateConnections(readerSession, readerFactory);
			}

			@Override
			public ObjectNode probeWarehouse(final String pConnectorId)
			{
				return DataProductPublication.probeWarehouse(readerSession, readerFactory, pConnectorId);
			}
		};
	}

	private static WizardPage.DistributionTargetRow targetRow(final JsonNode pJson)
	{
		final WizardPage.DistributionTargetRow row = new WizardPage.DistributionTargetRow();
		row.id = pJson.path("id").asText("");
		row.connector = pJson.path("connector").asText("");
		row.connectorLabel = row.connector;
		row.operation = pJson.path("operation").asText("");
		row.connectorsMappingIds = pJson.path("connectorsMappingIds").asText("");
		row.tableDestinations = pJson.path("tableDestinations").asText("");
		row.mode = pJson.path("mode").asText("ON_DEMAND");
		row.state = pJson.path("state").asText("DRAFT");
		row.lastStatus = pJson.path("lastStatus").asText("");
		// step 7 screen counts (2026-10-18): the coded text (English + marker) when the reader gives it - the page words it in the person's language
		row.lastMessage = pJson.has("lastMessageCoded") ? pJson.path("lastMessageCoded").asText("") : pJson.path("lastMessage").asText("");
		row.lastSendAt = pJson.path("lastSendAt").asText("");
		row.scope = pJson.path("scope").asText("CHANGED");
		row.cron = pJson.path("cron").asText("");
		row.approval = pJson.path("approval").asBoolean(false);
		row.watchedFields = pJson.path("watchedFields").asText("");
		row.includeCrosswalk = pJson.path("includeCrosswalk").asBoolean(false);
		row.includePending = pJson.path("includePending").asBoolean(false);
		// "retire notices from the merge log" (SPEC-retire.md, UI lot): a target row from before this lot has
		// the field present but null on the EBX side, which DistributionReader#targetJson's own text() turns
		// into "" (not a missing JSON key) - .asText("NOTICE") would not catch that, same blank-defaulting as
		// e.g. distMode above.
		final String retireMode = pJson.path("retireMode").asText("");
		row.retireMode = retireMode.isEmpty() ? "NOTICE" : retireMode;
		row.retireUndoneMerges = pJson.path("retireUndoneMerges").asBoolean(true);
		row.lastSweepAt = pJson.path("lastSweepAt").asText("");
		row.armedViaOverride = pJson.path("armedViaOverride").asBoolean(false);
		row.firstFullSendRunId = pJson.path("firstFullSendRunId").asText("");
		return row;
	}

	/**
	 * Coordinator review (2026-09-24): {@code DISTRIBUTION_PRODUCT_OPEN}/{@code _ADD_ASSET}/{@code _CREATE}'s
	 * own re-read - {@code detect} for the live candidate/warehouse facts, merged with the PERSISTED draft
	 * ({@link #distProductDraft}, seeded once from {@code detect}'s own {@code defaultDraft} on first open,
	 * never re-defaulted afterwards), then {@code plan} (no write) for the contract preview/problems/warnings.
	 * A catalogue-asset candidate ({@code source=CATALOG_ASSET}) is offered under {@link
	 * WizardPage.DataProductModel#addableAssets} until its key is in {@link ProductDraftState#addedAssetIds},
	 * at which point it moves into the main {@link WizardPage.DataProductModel#tables} list like any other row.
	 */
	private void buildDistributionProduct(final WizardSnapshot pSnapshot, final WizardPage.DistributionModel pD)
	{
		try
		{
			final DistributionStepFacts.Found warehouseFacts = DistributionStepFacts.shared().lookup(SourceOnboarder.viewerId(this.session), pSnapshot.project.code,
				Long.MAX_VALUE);
			final JsonNode detect = DataProductPublication.detect(this.session, this.factory, pSnapshot.project.code, DistributionStepFacts.warehousesJson(warehouseFacts.entry));
			pD.product.catalogInstalled = detect.path("catalogInstalled").asBoolean(false);
			pD.product.catalogRestricted = detect.path("catalogRestricted").asBoolean(false);
			final JsonNode snowflake = detect.path("warehouses").path("snowflake");
			pD.product.snowflakePresent = snowflake.path("present").asBoolean(false);
			pD.product.snowflakeTested = snowflake.path("tested").asBoolean(false);
			pD.product.snowflakeReachable = snowflake.path("reachable").asBoolean(false);
			pD.product.snowflakeConnectorId = snowflake.path("connectorId").asText("");
			if (!this.distProductDraft.initialized)
			{
				final JsonNode defaultDraft = detect.path("defaultDraft");
				this.distProductDraft.productName = defaultDraft.path("productName").asText("");
				this.distProductDraft.code = defaultDraft.path("code").asText("");
				this.distProductDraft.owner = defaultDraft.path("owner").asText("");
				this.distProductDraft.domain = defaultDraft.path("domain").asText("");
				this.distProductDraft.target = defaultDraft.path("target").asText("EBX_ONLY");
				this.distProductDraft.schema = defaultDraft.path("schema").asText("");
				this.distProductDraft.refreshMode = defaultDraft.path("refreshMode").asText("ON_DEMAND");
				for (final JsonNode t : defaultDraft.path("tables"))
				{
					this.distProductDraft.included.put(t.path("key").asText(""), Boolean.valueOf(t.path("included").asBoolean(true)));
				}
				this.distProductDraft.initialized = true;
			}
			pD.product.productName = this.distProductDraft.productName;
			pD.product.code = this.distProductDraft.code;
			pD.product.owner = this.distProductDraft.owner;
			pD.product.domain = this.distProductDraft.domain;
			pD.product.target = this.distProductDraft.target;
			pD.product.schema = this.distProductDraft.schema;
			pD.product.refreshMode = this.distProductDraft.refreshMode;
			pD.product.marketplaceListing = this.distProductDraft.marketplaceListing;

			// coordinator review (2026-09-24): a first pass collects every candidate's own label, keyed by its
			// candidate key, so a child row's own row (built in the second pass below) can name its parent
			// ("Nested in <PARENT>") - ProductPlan.Candidate.parentKey is the OTHER candidate's key, never its
			// label (distribution/product/ProductPlan.java, read there, not imported here).
			final java.util.Map<String, String> labelByKey = new java.util.LinkedHashMap<String, String>();
			for (final JsonNode candidate : detect.path("candidates"))
			{
				labelByKey.put(candidate.path("key").asText(""), candidate.path("label").asText(""));
			}
			for (final JsonNode candidate : detect.path("candidates"))
			{
				final String key = candidate.path("key").asText("");
				final WizardPage.DataProductTableRow row = new WizardPage.DataProductTableRow();
				row.key = key;
				row.label = candidate.path("label").asText(key);
				row.from = candidate.path("origin").asText("");
				row.rowCount = candidate.path("rowCount").asLong(0);
				row.child = candidate.path("child").asBoolean(false);
				row.ebxTable = candidate.path("ebxTable").asBoolean(true);
				if (row.child)
				{
					final String parentKey = candidate.path("parentKey").asText("");
					row.parentLabel = labelByKey.containsKey(parentKey) ? labelByKey.get(parentKey) : parentKey;
				}
				final boolean isCatalogAsset = "CATALOG_ASSET".equals(candidate.path("source").asText(""));
				if (isCatalogAsset && !this.distProductDraft.addedAssetIds.contains(key))
				{
					row.included = false;
					pD.product.addableAssets.add(row);
					continue;
				}
				row.added = isCatalogAsset;
				final Boolean included = this.distProductDraft.included.get(key);
				row.included = included == null || included.booleanValue();
				final String nestMode = this.distProductDraft.nestMode.get(key);
				row.nestMode = nestMode == null ? "OWN" : nestMode;
				pD.product.tables.add(row);
				if (!this.distProductDraft.included.containsKey(key))
				{
					this.distProductDraft.included.put(key, Boolean.TRUE); // remember it so future unchecking is detected (see syncProductDraftFromPosted)
				}
			}
			final JsonNode plan = DataProductPublication.plan(this.session, this.factory, pSnapshot.project.code, this.buildProductDraftJson());
			ProjectWizardUserService.applyPlanResult(pD.product, plan);
		}
		catch (final OperationException ex)
		{
			pD.error = EngineNotice.inline(ex, this.locale);
		}
	}

	/**
	 * Coordinator review (2026-09-24): every table/asset key {@link #distProductDraft} already knows about
	 * ({@link ProductDraftState#included}'s keys - seeded by {@link #buildDistributionProduct}, which always
	 * runs before any action on this panel since {@code model()} calls it unconditionally once the panel is
	 * open) gets its ticked state re-read from the posted checkbox set ({@code
	 * Posted#productTableIncluded} - present only for CHECKED boxes, see that field's own Javadoc), and, for a
	 * child row, its {@code mdmfProductNest__<key>} select. Called only from this panel's own actions (Add
	 * asset/Create) - a documented scope boundary, see {@link #distProductDraft}'s own Javadoc.
	 */
	private void syncProductDraftFromPosted(final UserServiceEventContext pContext, final Posted pPosted)
	{
		this.distProductDraft.productName = pPosted.productName;
		this.distProductDraft.code = pPosted.productCode;
		this.distProductDraft.owner = pPosted.productOwner;
		this.distProductDraft.domain = pPosted.productDomain;
		this.distProductDraft.target = pPosted.productTarget;
		this.distProductDraft.schema = pPosted.productSchema;
		this.distProductDraft.refreshMode = pPosted.productRefresh;
		this.distProductDraft.marketplaceListing = pPosted.productListing;
		for (final String key : new ArrayList<String>(this.distProductDraft.included.keySet()))
		{
			this.distProductDraft.included.put(key, Boolean.valueOf(pPosted.productTableIncluded.containsKey(key)));
			final String nest = ProjectWizardUserService.param(pContext, "mdmfProductNest__" + key);
			if (!nest.isEmpty())
			{
				this.distProductDraft.nestMode.put(key, nest);
			}
		}
	}

	/** {@code DISTRIBUTION_PRODUCT_ADD_ASSET} - the "+ Add assets from the catalogue" combo's pick button. */
	private void addDistributionProductAsset(final UserServiceEventContext pContext, final Posted pPosted)
	{
		this.distProductPanelOpen = true;
		this.syncProductDraftFromPosted(pContext, pPosted);
		if (!pPosted.productAddAssetId.isEmpty())
		{
			this.distProductDraft.addedAssetIds.add(pPosted.productAddAssetId);
			this.distProductDraft.included.put(pPosted.productAddAssetId, Boolean.TRUE);
		}
	}

	/**
	 * Coordinator review (2026-09-24): the JSON body {@code DataProductPublication.plan}/{@code .create} take,
	 * built from the PERSISTED draft ({@link #distProductDraft}) - {@code ProductPlan.plan}'s own contract
	 * (read in that class, not imported here) means a table absent from {@code tables} is excluded by default,
	 * so only INCLUDED, non-added-asset keys need an explicit entry; an added catalogue asset only needs its id
	 * in {@code addedCatalogAssetIds} (the planner auto-creates its {@code TableChoice} - see that class' own
	 * {@code plan} method).
	 */
	private ObjectNode buildProductDraftJson()
	{
		final ObjectNode draft = ProjectWizardUserService.MAPPER.createObjectNode();
		draft.put("productName", this.distProductDraft.productName);
		draft.put("code", this.distProductDraft.code);
		draft.put("owner", this.distProductDraft.owner);
		draft.put("domain", this.distProductDraft.domain);
		draft.put("target", this.distProductDraft.target);
		draft.put("connectorId", "");
		draft.put("schema", this.distProductDraft.schema);
		draft.put("refreshMode", this.distProductDraft.refreshMode);
		draft.put("marketplaceListing", this.distProductDraft.marketplaceListing);
		final com.fasterxml.jackson.databind.node.ArrayNode tables = draft.putArray("tables");
		for (final java.util.Map.Entry<String, Boolean> entry : this.distProductDraft.included.entrySet())
		{
			if (!Boolean.TRUE.equals(entry.getValue()) || this.distProductDraft.addedAssetIds.contains(entry.getKey()))
			{
				continue;
			}
			final ObjectNode t = tables.addObject();
			t.put("key", entry.getKey());
			t.put("included", true);
			final String nestMode = this.distProductDraft.nestMode.get(entry.getKey());
			t.put("nestMode", nestMode == null ? "OWN" : nestMode);
		}
		final com.fasterxml.jackson.databind.node.ArrayNode added = draft.putArray("addedCatalogAssetIds");
		for (final String assetId : this.distProductDraft.addedAssetIds)
		{
			if (Boolean.TRUE.equals(this.distProductDraft.included.get(assetId)))
			{
				added.add(assetId);
			}
		}
		return draft;
	}

	private static void applyPlanResult(final WizardPage.DataProductModel pP, final JsonNode pPlan)
	{
		pP.contractColumnCount = pPlan.path("contractColumnCount").asInt(0);
		pP.problems.clear();
		for (final JsonNode problem : pPlan.path("problems"))
		{
			pP.problems.add(problem.path("detail").asText(problem.toString()));
		}
		pP.warnings.clear();
		for (final JsonNode warning : pPlan.path("warnings"))
		{
			pP.warnings.add(warning.asText(""));
		}
		pP.creationSteps.clear();
		for (final JsonNode step : pPlan.path("creationSteps"))
		{
			pP.creationSteps.add(step.asText(""));
		}
	}

	/** {@code DISTRIBUTION_TARGET_SAVE} - see {@code ebx.DistributionReader.upsertTarget}'s own Javadoc for the body shape/validation. */
	private void saveDistributionTarget(final Posted pPosted)
	{
		final ObjectNode body = ProjectWizardUserService.MAPPER.createObjectNode();
		if (!pPosted.distTargetId.isEmpty() && !"NEW".equals(pPosted.distTargetId))
		{
			body.put("id", pPosted.distTargetId);
		}
		body.put("connector", pPosted.distConnector);
		body.put("operation", pPosted.distOperation);
		body.put("mode", pPosted.distMode);
		body.put("cron", pPosted.distCron);
		body.put("scope", pPosted.distScopeAll ? "ALL" : "CHANGED");
		body.put("approval", pPosted.distApproval);
		body.put("watchedFields", pPosted.distWatchedFields);
		body.put("includeCrosswalk", pPosted.distIncludeCrosswalk);
		body.put("includePending", pPosted.distIncludePending);
		// "retire notices from the merge log" (SPEC-retire.md, UI lot): only present at all when 7b actually
		// rendered the retire block (a REALTIME target - WizardPage#renderDistributionRetireSettings) - mirrors
		// DistributionReader.upsertTarget's own "present = write it, absent = leave untouched" discipline
		// (same convention as tableDestinations just below), so saving a non-REALTIME target never resets a
		// retireMode/retireUndoneMerges a previous REALTIME save already set.
		if (!pPosted.distRetireMode.isEmpty())
		{
			body.put("retireMode", pPosted.distRetireMode);
			body.put("retireUndoneMerges", pPosted.distRetireUndoneMerges);
		}
		// round 6 (task brief item C): only present at all when the 7b panel actually rendered "Table -> topic"
		// rows (a Kafka connector) - DistributionReader.upsertTarget's own partial-field discipline then leaves
		// a non-Kafka target's tableDestinations untouched instead of wiping it to "".
		if (!pPosted.distTableDestinations.isEmpty())
		{
			body.put("tableDestinations", com.on.ebx.factory.distribution.TargetMappingList.format(pPosted.distTableDestinations));
		}
		try
		{
			final JsonNode result = DistributionReader.upsertTarget(this.session, this.factory, this.readProjectCode(), body.toString());
			this.distOpenTargetId = result.path("id").asText(pPosted.distTargetId);
			this.distNotice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.targetSaved");
		}
		catch (final OperationException ex)
		{
			this.distOpenTargetId = pPosted.distTargetId.isEmpty() ? "NEW" : pPosted.distTargetId;
			this.warnEngine(ex);
		}
	}

	/** {@code DISTRIBUTION_TARGET_CONFIGURE}/{@code _INSTALL_TRIGGERS}/{@code _FIRST_FULL_SEND}/{@code _ARM_OVERRIDE}/{@code _DISARM} - one dispatcher, since all five share the "act on one target id, show its result as a notice, stay on its setup panel" shape. */
	private void runDistributionTargetAction(final String pTargetId, final String pKind)
	{
		if (pTargetId == null || pTargetId.trim().isEmpty())
		{
			return;
		}
		this.distOpenTargetId = pTargetId;
		try
		{
			final String projectCode = this.readProjectCode();
			final ObjectNode result;
			if ("configure".equals(pKind) || "installTriggers".equals(pKind))
			{
				// audit F-34 (wave 10): both are queued - one Connectors mapping per master table, or three triggers per master table and a data-model
				// publication, are not work for a request. The jobs banner follows them; the result sentence is the job's message (Recent tasks)
				this.queueDistributionSetup(projectCode, pTargetId, pKind);
				return;
			}
			else if ("firstFullSend".equals(pKind))
			{
				// audit F-11: queued (one connectors_sync per mapping would hold this request); the jobs banner follows it
				this.queueDistributionSend(projectCode, pTargetId, null);
				return;
			}
			else if ("armOverride".equals(pKind))
			{
				if (!this.session.isUserInRole(com.orchestranetworks.service.Profile.ADMINISTRATOR))
				{
					this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "review.notice.adminOnly"));
					return;
				}
				result = DistributionRunner.armWithoutFirstFullSend(this.session, this.factory, pTargetId);
			}
			else if ("testConnection".equals(pKind))
			{
				result = DistributionConnections.testConnectionForTarget(this.session, this.factory, pTargetId);
			}
			else
			{
				result = DistributionReader.setState(this.session, this.factory, pTargetId, FactoryPaths.DistributionTarget.STATE_DISABLED);
			}
			this.distNotice = result.has("message") ? result.path("message").asText() : Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.done");
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/**
	 * Audit F-34 (wave 10): queues the configuration of a target's mappings or the installation of the realtime triggers ({@link DistributionSetupRun}); the
	 * DESIGN right is checked by the queue, and a second identical request while one is queued or running folds into it.
	 */
	private void queueDistributionSetup(final String pProjectCode, final String pTargetId, final String pKind) throws OperationException
	{
		if ("configure".equals(pKind))
		{
			DistributionSetupRun.startConfigure(this.session, this.factory, pProjectCode, pTargetId);
		}
		else
		{
			DistributionSetupRun.startTriggers(this.session, this.factory, pProjectCode);
		}
		this.distNotice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.setupQueued");
	}

	/** {@code DISTRIBUTION_TARGET_SEND_NOW}. */
	private void sendDistributionTargetNow(final String pTargetId, final String pScope)
	{
		if (pTargetId == null || pTargetId.trim().isEmpty())
		{
			return;
		}
		this.distOpenTargetId = pTargetId;
		try
		{
			// audit F-11: queued, not run inside this request
			this.queueDistributionSend(this.readProjectCode(), pTargetId, pScope == null || pScope.trim().isEmpty() ? "" : pScope);
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	/**
	 * Audit F-11: "First full send" ({@code pScope} null) and "Send now" enqueue the {@link DistributionSendRun} job the REST route already uses
	 * (resource DISTRIBUTION: a project never runs two sends at once) instead of running one blocking {@code connectors_sync} per mapping inside
	 * the postback. Administrators only, like the REST routes. A second click while a send of the same target is queued or running is refused
	 * with a sentence; the first full send's arming-gate refusal is shown at once (not as a failed job).
	 */
	private void queueDistributionSend(final String pProjectCode, final String pTargetId, final String pScope) throws OperationException
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		if (DistributionSendRun.isActive(this.factory, this.projectId, pTargetId))
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.sendAlreadyActive"));
			return;
		}
		if (pScope == null)
		{
			final String refusal = DistributionSendRun.firstFullRefusal(this.factory, pProjectCode);
			if (refusal != null)
			{
				this.warn(refusal);
				return;
			}
			DistributionSendRun.startFirstFull(this.session, this.factory, pProjectCode, pTargetId);
		}
		else
		{
			DistributionSendRun.startSendNow(this.session, this.factory, pProjectCode, pTargetId, pScope);
		}
		this.distNotice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.sendQueued");
	}

	/**
	 * {@code DISTRIBUTION_PRODUCT_CREATE} - re-plans and writes (idempotent) in one call, {@code ebx.DataProductPublication.create}'s own contract.
	 * <p>
	 * Audit F-34 (wave 10): the write (warehouse objects, the catalog's product rows) is a queued job ({@link DistributionSetupRun#startProduct}, resource
	 * DISTRIBUTION). The draft is planned HERE first, as the person - what their catalog right hides is not in the plan, so a problem is shown at once and
	 * nothing is queued - and the job, which reads with the system session, writes the draft the plan accepted.
	 */
	private void createDistributionProduct(final UserServiceEventContext pContext, final Posted pPosted)
	{
		this.distProductPanelOpen = true;
		this.syncProductDraftFromPosted(pContext, pPosted);
		try
		{
			final String projectCode = this.readProjectCode();
			// the connector id is re-detected here rather than trusted from the panel's last render (this
			// instance-state draft never carries it) - a create is a deliberate, infrequent click, unlike the
			// panel's own preview re-plan which already pays for a detect() call every time it renders anyway.
			// audit F-2-101: no probe on the way (the cached connections, read now when this person has none); "Create" never wakes the warehouse by itself
			DistributionStepFacts.Found createFacts = DistributionStepFacts.shared().lookup(SourceOnboarder.viewerId(this.session), projectCode, Long.MAX_VALUE);
			if (createFacts.entry == null || createFacts.entry.connections == null)
			{
				DistributionStepFacts.shared().refreshNow(SourceOnboarder.viewerId(this.session), projectCode, this.distributionFactsReader(projectCode));
				createFacts = DistributionStepFacts.shared().lookup(SourceOnboarder.viewerId(this.session), projectCode, Long.MAX_VALUE);
			}
			final JsonNode detect = DataProductPublication.detect(this.session, this.factory, projectCode, DistributionStepFacts.warehousesJson(createFacts.entry));
			final ObjectNode body = this.buildProductDraftJson();
			body.put("connectorId", detect.path("warehouses").path("snowflake").path("connectorId").asText(""));
			final JsonNode plan = DataProductPublication.plan(this.session, this.factory, projectCode, body);
			if (!plan.path("ok").asBoolean(false))
			{
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.productPlanFailed"));
				return;
			}
			DistributionSetupRun.startProduct(this.session, this.factory, projectCode, body);
			this.distNotice = Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step7dist.notice.productQueued");
		}
		catch (final OperationException ex)
		{
			this.warnEngine(ex);
		}
	}

	private String readProjectCode()
	{
		final String code = WizardData.projectCode(this.factory, this.projectId);
		return code == null ? "" : code;
	}

	private static long sumZoneRows(final List<WizardSummaryData.ZoneEntityInput> pEntities)
	{
		long total = 0;
		for (final WizardSummaryData.ZoneEntityInput entity : pEntities)
		{
			if (entity.rows != null)
			{
				total += entity.rows.longValue();
			}
		}
		return total;
	}

	/** One row of the "Last full run" journal - the LATEST run of {@code pStep} across every scope, by {@code startedAt}. No row added when it never ran. */
	/**
	 * Wizard visual pass, step 7 round (2026-09-24): "N noun" with the noun's own singular/plural form ({@code
	 * pUnitKey + ".one"}/{@code ".many"}, same two-key convention as {@code wizard.WizardPage#countText}) - never
	 * a bare "(s)".
	 */
	private String countWords(final long pCount, final String pUnitKey)
	{
		return pCount + " " + Messages.get(ProjectWizardUserService.class, this.locale, pUnitKey + (pCount == 1 ? ".one" : ".many"));
	}

	/** {@code pBaseKey + ".one"} at exactly 1, {@code ".other"} otherwise - same English rule as {@code wizard.WizardPage#plural}. */
	private static String pluralKey(final String pBaseKey, final long pCount)
	{
		return pBaseKey + (pCount == 1 ? ".one" : ".other");
	}

	private void journalStep(final WizardSummaryData.Input pIn, final List<WizardSummaryReader.StepRun> pSteps, final String pStep, final String pLabel, final String pRowsText)
	{
		WizardSummaryReader.StepRun latest = null;
		for (final WizardSummaryReader.StepRun run : pSteps)
		{
			if (!pStep.equals(run.step))
			{
				continue;
			}
			if (latest == null || (run.startedAt != null && (latest.startedAt == null || run.startedAt.after(latest.startedAt))))
			{
				latest = run;
			}
		}
		if (latest == null || latest.startedAt == null || latest.endedAt == null)
		{
			return; // never ran, or still running - not part of a COMPLETE run's own journal
		}
		final WizardSummaryData.JournalStepInput step = new WizardSummaryData.JournalStepInput();
		step.stepLabel = pLabel;
		step.seconds = Math.max(0, (latest.endedAt.getTime() - latest.startedAt.getTime()) / 1000L);
		step.rowsText = pRowsText;
		pIn.journal.add(step);
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private void warn(final String pMessage)
	{
		this.noticeKind = "warn";
		this.notice = pMessage;
	}

	/**
	 * Audit F-39: a refusal or failure of the engine - its localized sentence ({@link EngineNotice}: a coded refusal in the person's
	 * language, otherwise a lead sentence with the engine's English text in a "details" fold).
	 */
	private void warnEngine(final Throwable pFailure)
	{
		final EngineNotice notice = EngineNotice.of(pFailure, this.locale);
		this.warn(notice.sentence);
		this.noticeDetail = notice.detail;
		this.noticeDetailFor = notice.sentence;
	}

	private static String param(final UserServiceEventContext pContext, final String pName)
	{
		final String value = pContext.getParameter(pName);
		return value == null ? "" : value.trim();
	}

	/** A posted value exactly as sent, {@code ""} when absent - for a catalog key (audit D-07), which may begin or end with a space. */
	private static String paramRaw(final UserServiceEventContext pContext, final String pName)
	{
		final String value = pContext.getParameter(pName);
		return value == null ? "" : value;
	}

	private static int paramInt(final UserServiceEventContext pContext, final String pName, final int pDefault)
	{
		final String value = ProjectWizardUserService.param(pContext, pName);
		if (value.isEmpty())
		{
			return pDefault;
		}
		try
		{
			return Integer.parseInt(value);
		}
		catch (final NumberFormatException ex)
		{
			return pDefault;
		}
	}

	/** Threshold-left-out lot (2026-09-23): a hidden field {@code wizard.js} sets to the literal text {@code "true"} - anything else (including absent) reads as {@code false}. */
	private static boolean paramBool(final UserServiceEventContext pContext, final String pName)
	{
		return "true".equals(ProjectWizardUserService.param(pContext, pName));
	}

	// ------------------------------------------------------------------------------------------------------ the page

	void write(final UserServicePaneWriter pWriter)
	{
		// audit F-41: model() and the page do dozens of reads and some MCP calls - a failure there used to leave a blank pane
		try
		{
			this.writePage(pWriter);
		}
		catch (final RuntimeException | Error ex)
		{
			ScreenFailure.writeNotice(pWriter, this.locale, ScreenFailure.report("wizard", "rendering step " + this.currentStep, ex));
		}
	}

	private void writePage(final UserServicePaneWriter pWriter)
	{
		// project explorer lot (2026-09-23): surfaced once, on the very first render after setupObjectContext
		// resolved (or failed to resolve) the "project" launch parameter - cleared right away so it never
		// reappears on a later postback's re-render of this same service instance.
		if (this.unknownProjectCode != null)
		{
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.notice.unknownProjectCode", this.unknownProjectCode));
			this.unknownProjectCode = null;
		}
		// second permissions pass (2026-10-14): a project deleted, or hidden from the user since the last round trip, is no longer shown
		if (this.projectId != null && !this.projectId.isEmpty() && this.openProject() == null)
		{
			this.projectId = null;
			this.projectLocked = false;
			this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "screen.project.unknown"));
		}
		// fix/review-assets (2026-09-21): Assets.version() changes with every build.sh run - a fixed "?v=1"
		// kept the browser serving a stale stylesheet forever across a real CSS change (see Assets' own Javadoc)
		// audit F-40: the user guide in the person's language (www/common/html/factory/user-guide-<lang>.html)
		this.helpUrl = ThemeTokens.withQuery(pWriter.getURLForResource(ResourceType.HTML, ProjectWizardUserService.userGuide(this.locale)), this.theme);
		// audit F-41: the page is built before anything is written, so a failure while building it leaves the notice alone on the pane
		final String page = WizardPage.render(this.model());
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/review.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/wizard.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add(page);
		this.writeScripts(pWriter);
		// project explorer lot, contextual return (2026-09-23): sends Close back to the explorer when this
		// wizard was reached from one of its Wizard/New-project links - see FactoryHtml's own Javadoc
		// fix/cockpit-return-to-wizard (2026-09-26): keeps that origin across this wizard's own postbacks
		pWriter.addJS_cr(FactoryHtml.wizardOriginKeepScript(FactoryHtml.userId(this.session), this.projectCode()));
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session)));
		// feed-designer lot (2026-09-23): arms this wizard's own "Designer" links so the designer's Close
		// button can come back HERE instead of the native Project table - same mechanism, reused the other
		// direction (the wizard is usually a RETURN target, from the explorer; here it is also a SOURCE).
		// fix/mapping-matrix-live (2026-09-24): the "Mapping matrix" link (WizardPage#renderFeed,
		// mdmf-feed-matrix-link) is armed the SAME way, so its own Close comes back here too.
		// fix/cockpit-return-to-wizard (2026-09-25): every Cockpit link of the wizard (step 4's held-back
		// banner, step 7's "Open the cockpit", its production "Follow" card and its rejects open point - all
		// built from FactoryLinks.cockpitUrl) is armed the same way, so the Cockpit's Close (which already runs
		// originReturnScript) comes back to this wizard instead of the native Project table. Matched on the
		// href rather than a class so no call site can be forgotten.
		// Same day, measured live: the stored address must be built here (project + current step), EBX drops
		// serviceParams from the wizard's own address - see FactoryHtml#originCaptureScript(String, String, List).
		pWriter.addJS_cr(FactoryHtml.originCaptureScript(FactoryHtml.userId(this.session), ".mdmf-feed-designer-link, .mdmf-feed-matrix-link, a[href*=\"service=FactoryCockpit\"], a[href*=\"service=TransferRejectReview\"]",
			this.returnUrlsByStep()));
		// source-onboarding lot (2026-09-23): the onboarding panel's two Connectors links (WizardPage's own
		// CONNECTORS_TABLE_URL constant) - same rewrite ProjectExplorerUserService's own script applies to its
		// Wizard/Cockpit links, so they stay inside whatever perspective this wizard itself was opened in
		// instead of dropping into ebx-ui's perspective-agnostic browser (CLAUDE.md ss9's own note on this link
		// family).
		pWriter.addJS_cr("(function(){var m=/\\/ui\\/custom\\/([^\\/]+)\\//.exec(window.top.location.pathname);if(!m)return;"
			+ "var o='/ui/advanced/data/',n='/ui/custom/'+m[1]+'/data/';"
			+ "document.querySelectorAll('a[href*=\"'+o+'\"]').forEach(function(a){a.setAttribute('href',a.getAttribute('href').split(o).join(n));});})();");
		// leave-guard lot (2026-09-23): the onboarding panel's two plain Connectors links (WizardPage.renderOnboardWriteButton's
		// sibling "new connection" hint and "open connection" next-action link) - not routed through
		// originCaptureScript above (no return-to-explorer token needed for a Connectors link), only the
		// voluntary-leave marking so neither ever triggers EBX's "Quitter le site Web ?" prompt.
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link"));
	}

	/** One wizard URL per step (index 0 = step 1) for the current project, empty when there is no project yet - see {@link FactoryHtml#originCaptureScript(String, String, List)}. */
	private List<String> returnUrlsByStep()
	{
		final List<String> out = new ArrayList<String>();
		final String projectCode = this.projectCode();
		if (projectCode == null || projectCode.isEmpty())
		{
			return out;
		}
		final String dataspaceKey = this.factory.getHome().getKey().format();
		final String datasetName = this.factory.getAdaptationName().getStringName();
		for (int step = 1; step <= WizardRules.STEP_MAX; step++)
		{
			out.add(FactoryLinks.wizardUrl(dataspaceKey, datasetName, projectCode, step));
		}
		return out;
	}

	/**
	 * F2-107: the snapshot read by an action of this request that writes nothing itself (it reads, then at most queues a job): kept for the display that follows
	 * ({@link #takeRequestSnapshot}) instead of being read a second time. Cleared at the start of every action ({@link #handleAction}) and consumed by the display, so it never
	 * outlives the request. An action that writes before the page is drawn keeps reading its own snapshot directly.
	 */
	private final OncePerRequest<WizardSnapshot> requestSnapshots = new OncePerRequest<WizardSnapshot>();
	private final OncePerRequest.Reader<WizardSnapshot> snapshotReader = new OncePerRequest.Reader<WizardSnapshot>()
	{
		@Override
		public WizardSnapshot read()
		{
			return WizardData.readSnapshot(ProjectWizardUserService.this.view(), ProjectWizardUserService.this.factory, ProjectWizardUserService.this.projectId,
				ProjectWizardUserService.this.session);
		}
	};
	/** F2-102: the plans whose queued simulation has not been taken yet; see {@link #pickUpSimulations}. */
	private final java.util.Set<String> simulationPendingRowIds = new java.util.LinkedHashSet<String>();

	private WizardSnapshot requestSnapshot()
	{
		return this.requestSnapshots.get(this.snapshotReader);
	}

	private WizardSnapshot takeRequestSnapshot()
	{
		return this.requestSnapshots.take(this.snapshotReader);
	}

	/**
	 * F2-102: takes the simulations the queued {@code MATCHING_SIMULATE_RUN} jobs finished for this person and shows them where the synchronous simulation put them. A plan whose
	 * job is over with no result (the server restarted) is said once; one whose job is still waiting or running is simply not there yet.
	 */
	private void pickUpSimulations()
	{
		if (this.simulationPendingRowIds.isEmpty() || this.projectId == null)
		{
			return;
		}
		final String user = com.on.ebx.factory.ebx.Actor.current(this.session).userId;
		final boolean active = WizardActionRuns.simulationActive(this.factory, this.projectId);
		for (final java.util.Iterator<String> it = this.simulationPendingRowIds.iterator(); it.hasNext();)
		{
			final String rowId = it.next();
			final MatchPlanView.SimulationResult result = WizardActionRuns.take(this.projectId, user, rowId);
			if (result != null)
			{
				this.matchSimulationResults.put(rowId, result);
				it.remove();
			}
			else if (!active)
			{
				it.remove();
				this.warn(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.simulate.noResult"));
			}
		}
	}

	WizardPage.Model model()
	{
		this.pickUpKeyStudies();
		this.pickUpSimulations();
		// F2-107: an action that only read the snapshot (or queued a job) leaves it here, so the display that follows does not read a second one
		final WizardSnapshot snapshot = this.takeRequestSnapshot();
		// fix (2026-09-27): the Review card's "Run a deduced scan" button follows DeducedScanRun's own rule
		WizardData.attachDeducedScanEligibility(this.factory, this.session, snapshot);
		// UX lot U2 (2026-10-13): why an application with no planned table has none (objects without a scanned column)
		WizardData.attachUnplannedAssets(this.factory, this.session, snapshot);
		// feed-designer lot (2026-09-23): a root feed's "Designer" link - built here (dataspaceKey/datasetName
		// come from this.factory, an EBX type WizardSnapshot never carries) and set back onto the plain
		// snapshot object WizardPage.renderFeed already renders, same as every other computed-in-the-service
		// field this class copies onto the model rather than into WizardData's own read.
		final String dataspaceKey = this.factory.getHome().getKey().format();
		final String datasetName = this.factory.getAdaptationName().getStringName();
		// mapping-matrix lot (2026-09-23, docs/MAPPING-MATRIX.md): same "built here, copied onto the plain
		// snapshot" technique as designerUrl above - the project CODE (not its EBX id) is what
		// core.MappingMatrixLink/ebx.ui.MappingMatrixUserService resolve the project by.
		final String projectCode = this.projectCode();
		String firstEntityMatrixUrl = "";
		String firstEntityDesignerUrl = "";
		String firstEntityDeskUrl = "";
		for (final WizardSnapshot.StagingEntityInfo entity : snapshot.stagingEntities)
		{
			for (final WizardSnapshot.FeedInfo feed : entity.feeds)
			{
				if (feed.parentFeedId.isEmpty())
				{
					feed.designerUrl = com.on.ebx.factory.core.FeedDesignerLink.url(dataspaceKey, datasetName, feed.id);
					feed.matrixUrl = com.on.ebx.factory.core.MappingMatrixLink.url(dataspaceKey, datasetName, projectCode, entity.id);
					// staging-proposal-run lot (2026-09-24): the "done" screen's two review buttons target the
					// FIRST non-SUPERSEDED business object and its OWN first root feed - same rule renderStep4
					// already applies to which entities get a card at all (ReviewLine.SUPERSEDED skipped).
					if (firstEntityMatrixUrl.isEmpty() && !ReviewLine.SUPERSEDED.equals(entity.status))
					{
						firstEntityMatrixUrl = feed.matrixUrl;
						firstEntityDesignerUrl = feed.designerUrl;
						// Validation desk lot L1: the fourth view of step 4, opened on the same business object as the matrix link
						firstEntityDeskUrl = com.on.ebx.factory.core.StagingDeskLink.url(dataspaceKey, datasetName, projectCode, entity.id);
					}
				}
			}
		}
		final WizardPage.Model model = new WizardPage.Model();
		// staging-proposal-run lot (2026-09-24): the one-click run's own status, read fresh on every render -
		// see ebx.StagingProposalRun's own Javadoc.
		model.proposalDoneMatrixUrl = firstEntityMatrixUrl;
		model.proposalDoneDesignerUrl = firstEntityDesignerUrl;
		model.proposalDoneDeskUrl = firstEntityDeskUrl;
		if (this.projectId != null && !this.projectId.isEmpty() && snapshot.project != null)
		{
			final ObjectNode runStatus = StagingProposalRun.status(this.factory, snapshot.project.code);
			model.proposalRunExists = runStatus.path("exists").asBoolean(false);
			model.proposalRunRunning = runStatus.path("running").asBoolean(false);
			model.proposalRunStale = runStatus.path("stale").asBoolean(false);
			model.proposalRunProgress = ProposalProgress.parse(runStatus.path("detailJson").asText(null));

			// wizard-visual-pass lot (2026-09-24): the "Fill the shared structure" one-click run's own status,
			// same "read fresh on every render" rule - see ebx.StagingLoadRun's own Javadoc. The grid/rejects
			// data is only built once the run is NOT running: a mid-run read of the staging table would show a
			// partial, misleading count (StagingLoader writes rows as it goes), and the running screen shows
			// its own stage list instead of the grid.
			final ObjectNode stagingLoadRunStatus = StagingLoadRun.status(this.factory, projectCode);
			model.stagingLoadRunExists = stagingLoadRunStatus.path("exists").asBoolean(false);
			model.stagingLoadRunRunning = stagingLoadRunStatus.path("running").asBoolean(false);
			model.stagingLoadRunStale = stagingLoadRunStatus.path("stale").asBoolean(false);
			model.stagingLoadRunProgress = RunProgress.parse(stagingLoadRunStatus.path("detailJson").asText(null), StagingLoadRun.STAGES);
			// staging-live lot (2026-09-27): the job the live panel's own Stop cancels (GET/POST .../jobs)
			model.stagingLoadRunJobId = stagingLoadRunStatus.path("jobId").asText("");
			// UX lot C3: step 4's "Review the proposal" quotes the Mapping matrix header's figure, read with the matrix' own reader and counter (only when step 4 is in review)
			if (!firstEntityMatrixUrl.isEmpty() && WizardRules.step4Primary(snapshot, model.proposalRunRunning, model.stagingLoadRunRunning) == WizardRules.Step4Primary.REVIEW)
			{
				try
				{
					// staging-screens lot (2026-10-17): the matrix' own tally - the points that need a person AND the N of its "Accept the N proposals" button - so the
					// two screens quote the same figures (step 4 used to subtract from its own snapshot tally: "the other 96" next to "Accept the 93")
					final com.on.ebx.factory.mappingmatrix.MatrixCounter.Tally tally = com.on.ebx.factory.mappingmatrix.MatrixCounter
						.of(this.asUser(() -> com.on.ebx.factory.ebx.MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale)));
					model.matrixFigures(tally);
				}
				catch (final RuntimeException ex)
				{
					model.matrixFigures(null); // the line is a help, never a reason to lose the page
				}
			}
			// matching-live lot (2026-09-27): step 6's last matching action (Run or Configure), one line per entity
			model.matchingActivity = this.asUser(() -> com.on.ebx.factory.ebx.MatchingActivityReader.read(this.factory, this.projectId));
			model.stagingRejectsUrl = FactoryLinks.rejectsUrl(dataspaceKey, datasetName, projectCode); // wave 10 (audit F-38): the review screen
			// staging preflight lot H1 (2026-10-17): the last check before the build, while no build has run since it (the page stops showing a report that is history)
			if (!model.stagingLoadRunRunning)
			{
				model.stagingPreflight = this.asUser(() ->
				{
					final com.on.ebx.factory.staging.PreflightReport last = com.on.ebx.factory.ebx.StagingPreflight.last(this.factory, projectCode);
					return last != null && !com.on.ebx.factory.ebx.StagingPreflight.builtSince(this.factory, projectCode, last) ? last : null;
				});
			}
			if (!model.stagingLoadRunRunning)
			{
				model.stagingLoadGrid = this.asUser(() -> StagingLoadGridReader.build(this.session, this.factory, this.projectId, snapshot, this.locale));
				model.stagingHeldBackCount = Math.max(0L, this.asUser(() -> MasterBuildGridReader.heldBackInStagingCount(this.factory, this.projectId)));
				if (model.stagingHeldBackCount > 0L)
				{
					// staging integration (2026-10-17): the real cause of the held-back rows for the step 4 banner (lot B), read only when there are some
					final com.on.ebx.factory.ebx.RejectCauseReader.Causes causes = this.asUser(() -> com.on.ebx.factory.ebx.RejectCauseReader.staging(this.session, this.factory, projectCode));
					model.stagingRejectCauses = causes.byEntity();
					model.stagingRejectCausesAll = causes.project();
				}
			}

			// wizard-visual-pass lot (2026-09-24): same for step 5's "Build the master" one-click run.
			final ObjectNode masterBuildRunStatus = MasterBuildRun.status(this.factory, projectCode);
			model.masterBuildRunExists = masterBuildRunStatus.path("exists").asBoolean(false);
			model.masterBuildRunRunning = masterBuildRunStatus.path("running").asBoolean(false);
			model.masterBuildRunStale = masterBuildRunStatus.path("stale").asBoolean(false);
			model.masterBuildRunProgress = RunProgress.parse(masterBuildRunStatus.path("detailJson").asText(null), MasterBuildRun.STAGES);
			// master-live lot (2026-09-27): the job the live panel's own Stop cancels
			model.masterBuildRunJobId = masterBuildRunStatus.path("jobId").asText("");
			if (!model.masterBuildRunRunning)
			{
				model.masterBuildGrid = this.asUser(() -> MasterBuildGridReader.build(this.factory, this.projectId, snapshot, this.locale));
				model.masterHeldBackInStagingCount = Math.max(0L, this.asUser(() -> MasterBuildGridReader.heldBackInStagingCount(this.factory, this.projectId)));
				final boolean masterTargetKnown = !snapshot.project.masterDataspace.isEmpty() && !snapshot.project.masterDataset.isEmpty();
				for (final WizardSnapshot.RunGridRow row : model.masterBuildGrid.rows)
				{
					final WizardSnapshot.RunGridCell copyCell = row.cells.size() > 1 ? row.cells.get(1) : null;
					if (copyCell != null && copyCell.primaryValue != null)
					{
						model.masterKpiTiles.add(new String[] { String.valueOf(copyCell.primaryValue), row.label });
					}
					if (masterTargetKnown)
					{
						for (final WizardSnapshot.StagingEntityInfo entity : snapshot.stagingEntities)
						{
							if (entity.name.equals(row.label) && !entity.targetTable.isEmpty())
							{
								model.masterOpenTableUrls.put(row.label,
									FactoryLinks.genericTableUrl(snapshot.project.masterDataspace, snapshot.project.masterDataset, entity.targetTable));
							}
						}
					}
				}
			}

			// sources-landing lot SOURCES (2026-09-24): SourcesRun's own status, same "read fresh on every
			// render" rule. Each queued connector's card shows its pipeline from the last facts read (wave 11, audit F-48: read from
			// SourceOnboarder's cache, refreshed off this thread - never one to three Connectors calls per card per render as before).
			final ObjectNode sourcesRunStatus = SourcesRun.status(this.factory, snapshot.project.code);
			model.sourcesRunRunning = sourcesRunStatus.path("running").asBoolean(false);
			model.sourcesRunStale = sourcesRunStatus.path("stale").asBoolean(false);
			for (final JsonNode entryNode : sourcesRunStatus.path("entries"))
			{
				final String connectorId = entryNode.path("connectorId").asText("");
				if (connectorId.isEmpty())
				{
					continue;
				}
				// wave 11 (audit F-48): a display never calls Connectors. The card shows the last facts any live read left (the onboarding job reads them
				// several times while it runs; a person's own action reads them too); facts that are missing or too old are asked for again on a
				// background thread, and the card says "checking" until they come - the page then asks again by itself (data-mdmf-sources-checking)
				final String entryState = entryNode.path("state").asText(SourcesProgress.WAITING);
				final boolean jobActive = SourcesProgress.WAITING.equals(entryState) || SourcesProgress.RUNNING.equals(entryState);
				final SourceOnboarder.Cached cached = SourceOnboarder.cachedFacts(SourceOnboarder.viewerId(this.session), connectorId,
					jobActive ? SourceOnboarder.CACHE_RUNNING_MAX_AGE_MS : SourceOnboarder.CACHE_SETTLED_MAX_AGE_MS);
				if (cached == null || !cached.fresh)
				{
					SourceOnboarder.refreshInBackground(this.session, this.factory, connectorId);
					model.sourcesFactsChecking = true;
				}
				if (cached == null)
				{
					model.sourcesRunCards.add(new WizardPage.Model.SourcesRunCard(connectorId, connectorId, null, entryState, entryNode.path("summary").asText(""), ""));
					continue;
				}
				final SourceOnboarding.Facts cardFacts = cached.facts;
				final SourceOnboarding.Diagnosis cardDiagnosis = SourceOnboarding.diagnose(cardFacts);
				final String display = cardFacts.connectorLabel == null || cardFacts.connectorLabel.isEmpty() ? connectorId : cardFacts.connectorLabel;
				model.sourcesRunCards.add(new WizardPage.Model.SourcesRunCard(connectorId, display, ApplicationStages.from(cardDiagnosis),
					entryState, entryNode.path("summary").asText(""), cardFacts.connectorType));
			}

			// deduced-scan lot (2026-09-24): ebx.DeducedScanRun's own status - same "read fresh on every
			// render" rule. Entries are keyed by ProjectSource occurrence id (see that class's own Javadoc);
			// the display name is looked up in snapshot.sources (already read above), same convention as the
			// pending-tables block below - no extra EBX read needed for a plain label.
			final ObjectNode deducedScanStatus = DeducedScanRun.status(this.factory, this.projectId);
			model.deducedScanRunning = deducedScanStatus.path("running").asBoolean(false);
			model.deducedScanStale = deducedScanStatus.path("stale").asBoolean(false);
			final DeducedScanProgress deducedScanProgress = DeducedScanProgress.parse(deducedScanStatus.path("detailJson").asText(""));
			// fix lot (2026-09-26, defect 14): fetched here (moved up from the board-2e block right below,
			// unchanged there) so a DONE card's end line can be built from the CURRENT decisions of its deduced
			// assets (DeducedScanCatalog#decisionCounts) rather than the run's own frozen entry.summary text -
			// see WizardPage.renderOneDeducedScanCard's own comment on why.
			final Adaptation catalogForPending = CatalogReader.dataset(this.factory.getHome().getRepository());
			// audit F-02 (catalog reads): boards 2d and 2e show the deduced tables: read as the person
			final CatalogReader.Reader pendingReader = CatalogReader.Reader.of(this.session);
			for (final DeducedScanProgress.Entry entry : deducedScanProgress.entries())
			{
				String deducedDisplay = entry.connectorId;
				String catalogSourceIdForCounts = "";
				for (final WizardSnapshot.SourceInfo candidate : snapshot.sources)
				{
					if (candidate.id.equals(entry.connectorId))
					{
						deducedDisplay = candidate.sourceSystem.isEmpty() ? candidate.connector : candidate.sourceSystem;
						catalogSourceIdForCounts = candidate.catalogSourceId;
						break;
					}
				}
				final DeducedScanCatalog.DecisionCounts counts = catalogForPending == null || catalogSourceIdForCounts.isEmpty() ? new DeducedScanCatalog.DecisionCounts()
					: DeducedScanCatalog.decisionCounts(pendingReader, catalogForPending, catalogSourceIdForCounts);
				model.deducedScanCards
					.add(new WizardPage.Model.DeducedScanCard(entry.connectorId, deducedDisplay, entry, counts.confirmed, counts.leftOut, counts.toConfirm));
			}
			// board 2e: every TO_CONFIRM table of every application of this project, grouped by source -
			// independent of the run's own progress (a deduced scan can finish a run and leave tables pending
			// confirmation for as long as nobody has decided them, long after DeducedScanRun itself is DONE).
			// Reuses snapshot.sources (every ProjectSource row of the project, already read above) rather than
			// a second raw query - a deduced-scan candidate is, by construction, an application already added
			// through the normal step-2 flow whose official scan found nothing (task brief: "a project source
			// whose connection has no catalog operation" - the ProjectSource already exists at that point).
			if (catalogForPending != null)
			{
				for (final WizardSnapshot.SourceInfo pendingSource : snapshot.sources)
				{
					if (pendingSource.catalogSourceId.isEmpty())
					{
						continue; // no catalog Source at all yet - either never deduced-scanned, or still WAITING/RUNNING (shown by deducedScanCards instead)
					}
					final List<DeducedScanCatalog.PendingAsset> pending = DeducedScanCatalog.pendingAssets(pendingReader, catalogForPending, pendingSource.catalogSourceId);
					if (pending.isEmpty())
					{
						continue;
					}
					final String pendingDisplay = pendingSource.sourceSystem.isEmpty() ? pendingSource.connector : pendingSource.sourceSystem;
					model.deducedScanPending.add(new WizardPage.Model.DeducedScanPendingGroup(pendingSource.id, pendingDisplay, pending));
				}
			}
			if (pendingReader.restricted())
			{
				snapshot.catalogRestricted = true; // the deduced tables of an application exist, the person's catalog right hides them
			}
			// sources+landing redesign, lot LANDING (2026-09-24): the one-click "Prepare the landing" run's own
			// status, read fresh on every render - see ebx.LandingRun's own Javadoc. "running" here also covers
			// the paused-on-a-WAITING-table state (LandingRun.maybeCloseStep keeps the step RUNNING for exactly
			// that reason), which is deliberate: the wizard keeps showing the grid/banner/Stop button until the
			// run is truly over, not just until the executor happens to be idle.
			// live bug fix (coordinator, 2026-09-24, bug 6): pass the session so status() can reconcile the
			// progress grid against each table's own real LandingTable.status (a table built/loaded through the
			// Advanced disclosure's "Generate the landing" button, in parallel, would otherwise never be
			// reflected here) - see LandingRun.status's own updated Javadoc.
			final ObjectNode landingRunStatus = LandingRun.status(this.session, this.factory, snapshot.project.code);
			model.landingRunExists = landingRunStatus.path("exists").asBoolean(false);
			model.landingRunRunning = landingRunStatus.path("running").asBoolean(false);
			model.landingRunStale = landingRunStatus.path("stale").asBoolean(false);
			model.landingRunProgress = LandingRunProgress.parse(landingRunStatus.path("detailJson").asText(null));
			// live-progress lot (2026-09-26): the stage durations of past runs, for "about N min left" - read only
			// while a run is on screen (one query over the landing-run steps of the instance)
			if (model.landingRunRunning)
			{
				model.landingRunTypicalMs.putAll(LandingRun.typicalDurations(this.factory));
			}
			model.landingRunWaitingTableIds.addAll(model.landingRunProgress.waitingTableIds());
		}
		model.landingKeyChooserTableId = this.landingKeyChooserTableId;
		model.landingKeyChooser = this.landingKeyChooser;
		model.landingKeyAppliedMessage = this.landingKeyAppliedMessage;
		model.landingKeyChangeTableId = this.landingKeyChangeTableId;
		model.landingKeyChangeMessage = this.landingKeyChangeMessage;
		model.landingCompositeDetailTableId = this.landingCompositeDetailTableId;
		model.landingCompositeDetail = this.landingCompositeDetail;
		model.locale = this.locale;
		model.user = FactoryHtml.userId(this.session);
		model.snapshot = snapshot;
		// UX lot D (2026-10-14): how the latest landing run, configuration and matching run ended and what failed in them. Read BEFORE the step states:
		// a landing gap that a failed run explains opens the staging step (with a warning) instead of blocking it.
		if (snapshot.project != null && this.projectId != null && !this.projectId.isEmpty())
		{
			model.lastOutcomes.putAll(this.asUser(() -> com.on.ebx.factory.ebx.FactoryJobStatus.lastOutcomes(this.factory, this.projectId)));
			snapshot.landingGapIsKnown = com.on.ebx.factory.wizard.PartialFailures.landingGapIsKnown(snapshot, model.lastOutcomes.get(com.on.ebx.factory.wizard.PartialFailures.LANDING));
		}
		model.stepStates = WizardRules.stepStates(snapshot);
		model.administrator = snapshot.administrator;
		model.designer = snapshot.designer;
		// audit F-02: no right at all renders every action disabled (the permission rule normally keeps such a session out)
		model.right = snapshot.right == null ? com.on.ebx.factory.core.FactoryRight.AUTHENTICATED : snapshot.right;
		model.pipelineOperator = com.on.ebx.factory.ebx.FactoryAccess.mayOperatePipeline(this.session, this.factory);
		model.notice = this.notice;
		model.noticeKind = this.noticeKind;
		model.helpUrl = this.helpUrl;
		// audit F-43: the last tasks of the project (FactoryJob), with "Run again" only where this person may queue the kind
		if (snapshot.project != null && this.projectId != null && !this.projectId.isEmpty())
		{
			model.recentTasks.addAll(com.on.ebx.factory.ebx.FactoryJobStatus.recentTasks(this.factory, this.projectId, ProjectWizardUserService.RECENT_TASKS, this.session));
		}
		// landing test fixes (2026-10-17): the re-run checkbox opens on the answer the last run was started with, and the walkthrough's radios on the applications that said yes
		if (this.projectId != null && !this.projectId.isEmpty())
		{
			final Adaptation lastRun = FactoryJobQueue.latest(this.factory, this.projectId, LandingRun.STEP);
			final String lastParams = lastRun == null ? null : lastRun.getString(FactoryPaths.FactoryJob.PARAMS);
			model.landingRunUseLlm = LlmConsent.lastChoice(lastParams);
			if (!this.walkLlmSeeded)
			{
				this.walkLlmSeeded = true;
				this.walkLlmYes.addAll(LlmConsent.lastSources(lastParams));
				model.walkLlmYes.addAll(this.walkLlmYes);
			}
		}
		model.noticeDetail = this.notice != null && this.notice.equals(this.noticeDetailFor) ? this.noticeDetail : null;
		model.problems.addAll(this.problems);
		model.currentStep = this.currentStep == 0 ? WizardRules.suggestedStep(snapshot) : this.currentStep;
		// lot C1-B: a step the project's style does not list (a Consolidation id on a Centralisation project, or the reverse) is not reachable either
		if (!model.stepStates[model.currentStep].reachable || WizardRules.positionOf(snapshot.project == null ? null : snapshot.project.style, model.currentStep) == 0)
		{
			model.currentStep = WizardRules.suggestedStep(snapshot);
		}
		model.accelNeed = this.accelNeed;
		model.accelTickedPacks.addAll(this.accelTickedPacks);
		model.accelTickedOptions.addAll(this.accelTickedOptions);
		model.accelSuggestedPacks.addAll(this.accelSuggestedPacks);
		model.accelWhy = this.accelWhy;
		model.accelJobRunning = snapshot.accelJob != null;
		// wave 11 (audit F-42): the step shown is written in full, the others as placeholders that load themselves when opened
		model.currentStepOnly = !Boolean.getBoolean(ProjectWizardUserService.ALL_STEPS_PROPERTY);
		model.stagingOpenEntities.addAll(this.stagingOpenEntities);
		model.stagingClosedEntities.addAll(this.stagingClosedEntities);
		model.stagingOpenAll = this.stagingOpenAll;
		model.stagingFilter = this.stagingFilter;
		model.stillOpenGroups.addAll(this.stagingStillOpenGroups);
		if (snapshot.project != null)
		{
			model.projectDraft.code = snapshot.project.code;
			// lot C1-B: the style of a saved project is fixed (written at creation): the cards show it, whatever was posted
			model.projectDraft.style = snapshot.project.style;
			model.projectDraft.label = this.projectDraft.label.isEmpty() && this.notice == null ? snapshot.project.label : this.projectDraft.label;
			model.projectDraft.description = this.projectDraft.description.isEmpty() && this.notice == null ? snapshot.project.description : this.projectDraft.description;
			// wizard-llm-value-sharing lot (2026-09-28): what was just posted wins while it is being edited
			// (a validation failure re-shows it), the stored value otherwise - same rule as label above
			model.projectDraft.llmValueSharing = this.projectDraft.llmValueSharing.isEmpty() || this.notice != null ? snapshot.project.llmValueSharing
				: this.projectDraft.llmValueSharing;
			model.projectDraft.llmSampleSize = this.projectDraft.llmSampleSize.isEmpty() || this.notice != null ? String.valueOf(snapshot.project.llmSampleSize)
				: this.projectDraft.llmSampleSize;
		}
		else
		{
			model.projectDraft = this.projectDraft;
		}
		model.sourceDraft = this.sourceDraft;
		model.pickedCatalogSourceId = this.pickedCatalogSourceId;
		model.pickedCatalogSourceSchemas.addAll(this.pickedCatalogSourceSchemas);
		// source-onboarding lot (2026-09-23; revised after coordinator review the same day): every onboard*
		// field is plain instance state, copied here - model() must NEVER call SourceOnboarder itself
		// (connections() is a live connectors_list MCP round trip; the earlier revision called it
		// unconditionally on every render, which meant every wizard postback, on any step, paid for it - now
		// it is only ever read by SOURCE_ONBOARD_OPEN/REFRESH, see their own Javadoc).
		model.onboardOpen = this.onboardOpen;
		model.addAppOpen = this.addAppOpen;
		model.onboardConnections.addAll(this.onboardConnections);
		model.onboardConnectionsError = this.onboardConnectionsError;
		model.onboardConnectorId = this.onboardConnectorId;
		model.onboardConnectorDisplay = this.onboardConnectorDisplay;
		model.onboardDiagnosis = this.onboardDiagnosis;
		model.onboardScanMessage = this.onboardScanMessage;
		// UX lot U2 (2026-10-13): a diagnosed-ready system that is already an application of this project - the panel then offers its tables, not the add form
		if (this.onboardDiagnosis != null && this.onboardDiagnosis.ready && this.onboardDiagnosis.pickSourceId != null)
		{
			final String[] existingApplication = WizardData.applicationOfCatalogSource(this.factory, this.projectId, this.onboardDiagnosis.pickSourceId);
			if (existingApplication != null)
			{
				model.onboardExistingSourceId = existingApplication[0];
				model.onboardExistingCode = existingApplication[1];
			}
		}
		// sample-files lot (2026-09-23): same plain-instance-state copy as every onboard* field above.
		model.sampleFilesReport.addAll(this.sampleFilesReport);
		model.sampleFilesError = this.sampleFilesError;
		model.activeSourceId = this.activeSourceId;
		model.stagingBulkThreshold = this.stagingBulkThreshold;
		model.llmRunSummary = this.llmRunSummary;
		model.previewTableId = this.previewTableId;
		model.previewColumns.addAll(this.previewColumns);
		model.previewRows.addAll(this.previewRows);
		model.previewError = this.previewError;
		model.keyStudy = this.keyStudy;
		model.walkPage = this.walkPage;
		model.walkReview = this.walkReview;
		model.walkLlmYes.addAll(this.walkLlmYes);
		model.keyStudyTableId = this.keyStudy == null ? "" : this.keyStudy.tableId;
		// wizard-match-tree lot (2026-09-23): the MATCH review lines behind step 6's plan cards - built exactly
		// as ProposalReviewUserService.write does (lines + attachMatchExtras + attachSurvivorshipInputs), only
		// when the snapshot has plans to show, so every other step pays nothing for it.
		if (this.projectId != null && !snapshot.matchPlans.isEmpty())
		{
			final List<ReviewLine> lines = this.asUser(() -> ProposalReviews.attachSurvivorshipInputs(this.factory, this.session, this.projectId,
				ProposalReviewUserService.attachMatchExtras(this.factory, snapshot.reviewLines != null && this.locale != null && this.locale.equals(snapshot.reviewLinesLocale)
					? snapshot.reviewLines : this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale)))));
			for (final ReviewLine line : lines)
			{
				if (ReviewLine.MATCH.equals(line.nature))
				{
					model.matchLines.put(line.rowId, line);
					model.matchCanSimulate.put(line.rowId, Boolean.valueOf(ProposalReviewUserService.canSimulateMatchPlan(line.status)));
				}
			}
			model.matchSimulationResults.putAll(this.matchSimulationResults);
			model.matchMaxSimulationPairs = MatchPlanSimulationRunner.MAX_PIVOTS;
			if (this.lastActedMatchRowId != null)
			{
				model.expandedMatchRowIds.add(this.lastActedMatchRowId);
			}
		}
		// wizard-matching-results lot (2026-09-24): the KPIs/band/suspect-queue block - one MatchingResultsBuilder
		// call per ACCEPTED/APPLIED plan (a PROPOSED/REJECTED/SUPERSEDED plan has nothing running to show yet).
		// policyCode = the entity's own name, same convention MatchPlanSimulationRunner#simulate already uses
		// for a non-preview (real, published) policy - master.MatchingPolicyTranslator publishes it under that
		// code. compareFieldNames = the entity's own non-technical, non-key attributes (StagingAttribute names
		// double as master field names - master.MasterModel derives them 1:1), capped inside the builder.
		// wave 2 (2026-09-30, lot V2-A): NOTHING is computed here any more. Volume-4 gated MatchingResultsBuilder.build with
		// "this step is shown, or first render" - but every fresh navigation is a first render, so at 100 000 rows every step
		// of the wizard waited 32-46 s on the whole-table aggregates and the add-on group reads behind this block (thread dumps:
		// queryStateSourceCounts / queryMergedGroupsCount / queryBandSegments / MetadataServiceImpl.findRecordsInGroup). The
		// block is now computed when it can change (end of MatchingConfigurator.run for each entity, the MatchingResultsRun
		// refresh job queued by a decision or by the "Refresh results" button), stored in the entity's latest MATCHING_RUN
		// PipelineStep detail, and only READ here - it rides on the plan.runStep the snapshot already read, so it costs no extra
		// EBX read. A missing or stale snapshot renders a small notice with a button that queues the job; never a synchronous
		// rebuild. The pair cards are written only when step 6 is the step shown (deferPairs), see MatchResultsView.
		if (this.projectId != null && snapshot.project != null)
		{
			final long latestChangeAt = ProjectWizardUserService.masterLoadStartedAt(snapshot);
			for (final WizardSnapshot.MatchPlanInfo plan : snapshot.matchPlans)
			{
				if (!ReviewLine.ACCEPTED.equals(plan.status) && !ReviewLine.APPLIED.equals(plan.status))
				{
					continue;
				}
				if (plan.runStep == null || !("DONE".equals(plan.runStep.status) || "RUNNING".equals(plan.runStep.status)))
				{
					continue; // never ran, or the last run failed/stopped/was reset: nothing to describe, no block (the plan cards above say why)
				}
				final boolean canDecide = com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.STEWARD); // audit F-02: a data steward decides
				final String selectedTab = this.matchResultsSelectedTab.isEmpty() ? "PENDING" : this.matchResultsSelectedTab;
				final String selectedPairId = plan.entityName.equals(this.matchResultsSelectedEntity) ? this.matchResultsSelectedPairId : "";
				// only a DONE run's results describe anything (a RESET row - a step reset invalidated it - or a failed run has none to show)
				final MatchResultsSnapshot.Decoded decoded = "DONE".equals(plan.runStep.status) ? MatchResultsSnapshot.fromDetail(plan.runStep.detail) : null;
				String state = MatchResultsSnapshot.state(decoded, latestChangeAt);
				final MatchResultsView.Input input = decoded == null ? new MatchResultsView.Input() : decoded.input;
				if (decoded == null)
				{
					input.entityId = plan.entityName;
					input.entityName = plan.entityName;
				}
				input.snapshotState = state;
				// step 6 pair card (2026-10-18): the rule of the plan that produced a pair, in words - read from the plan the card above already holds, never stored
				final ReviewLine planLine = model.matchLines.get(plan.id);
				input.planNodes = planLine == null ? null : com.on.ebx.factory.review.MatchPlanFacts.nodes(planLine.matchRequirementsJson);
				if (!MatchResultsSnapshot.FRESH.equals(state) || "RUNNING".equals(plan.runStep.status))
				{
					// the (cheap, one FactoryJob scan) question only matters when the block is not fresh; a run in progress will carry results at its end
					input.refreshRunning = "RUNNING".equals(plan.runStep.status) || MatchingResultsRun.pending(this.factory, this.projectId);
				}
				input.deferPairs = model.currentStep != WizardRules.STEP_MATCHING;
				// UX lot C2: the "Last run" line of this block was the step's raw log text (Xref.goldenKey refreshed ..., the 1-million-row warning) that the table above
				// had already folded. It is now the same one sentence as the table's row (MatchingResultRow), the raw text under "Details" - and absent when the live
				// table right above shows that very run for this entity.
				final com.on.ebx.factory.matching.MatchingActivity shownRun = model.matchingActivity;
				final boolean tableCarriesIt = shownRun != null && com.on.ebx.factory.matching.MatchingActivity.RUN.equals(shownRun.kind) && !shownRun.isLive()
					&& shownRun.entity(plan.entityName) != null;
				if (!tableCarriesIt)
				{
					final MatchingResultRow runRow = MatchingResultRow.of(this.locale, plan.runStep.status, plan.runStep.message, input.kpis);
					input.lastRunText = runRow.sentence.isEmpty() ? null : Messages.get(ProjectWizardUserService.class, this.locale, "wizard.step6.results.lastRun", runRow.sentence);
					input.lastRunDetails = runRow.details;
				}
				input.selectedTab = selectedTab;
				input.selectedPairId = selectedPairId;
				input.bulkThresholdPercent = this.stagingBulkThreshold;
				input.canDecide = canDecide;
				input.lastDecisionError = plan.entityName.equals(this.matchResultsSelectedEntity) ? this.matchDecisionError : null;
				// second permissions pass (2026-10-14): the stored block was computed by a job with the engine's view - cut down here to what this
				// person may see of the master table (pairs, groups and members with a hidden record left out, hidden values blanked, the
				// whole-table figures left out when anything was hidden)
				model.matchResults.put(plan.id, com.on.ebx.factory.ebx.MatchResultsPermissions.forUser(this.view(), this.factory.getHome().getRepository(),
					snapshot.project.masterDataspace, snapshot.project.masterDataset, plan.entityName,
					com.on.ebx.factory.ebx.MatchingResultsStore.compareFields(this.factory, this.projectId, plan.entityName), input));
			}
		}
		model.matchResultsSelectedTab = this.matchResultsSelectedTab;
		model.matchResultsSelectedPairId = this.matchResultsSelectedPairId;
		// step-reset lot (2026-09-23): plain instance state, same convention as keyStudy/onboard*/previewTableId
		// above - rebuilt only by STEP_RESET_PLAN/STEP_RESET_CONFIRM/STEP_RESET_CANCEL, never by a plain render.
		model.stepReset = this.stepReset;
		// volume-operations lot 8b (2026-09-28, task 3): fed into the jobs banner - see stepResetPendingKind's own Javadoc.
		model.stepResetRunRunning = !this.stepResetPendingKind.isEmpty() && this.stepResetRunRunning();
		// wizard-summary lot (2026-09-24): only computed when step 7 is actually the one being shown - every
		// other step's postback (the overwhelming majority) never pays for the extra EBX reads/GoldenRecordsReader
		// table scan this builds.
		// fix/wizard-live-wave1 (2026-09-24, live bug item 11): that optimisation assumed step 7 would only ever
		// be "the one being shown" through a server round trip that posted mdmfStep=7 - but wizard.js switches
		// steps PURELY CLIENT SIDE (WizardPage renders all 7 panels up front, hidden/shown by CSS, no round trip
		// - see wizard.js's own "step switching" comment), and none of step 7's own controls posts back with
		// mdmfStep=7 either (GOTO_STEP is client-side; its Cockpit/back-to-matching links are a full navigation
		// or another client-side jump). So model.currentStep was in practice NEVER STEP_SUMMARY on a real
		// request, and this.buildSummary NEVER ran: opening the wizard (always server-rendered starting on
		// whatever this.currentStep already was - STEP_PROJECT on a fresh load) then clicking through the rail
		// to step 7 always showed the "no data yet" empty state, whatever the project's real state was. Also
		// compute it on the FIRST render of a genuinely fresh page load (!this.postedOnce, set by handle() -
		// see that field's own Javadoc) - cheap (once per page open) and the only way to guarantee step 7 has
		// real data by the time the browser can jump there with no further trip to the server.
		model.deliveryCheckSummary = this.deliveryCheckSummary;
		// wave 2 (2026-09-30): no longer also on the first render - buildSummary reads the master tables (GoldenRecordsReader:
		// record/golden counts and one fill-rate count per attribute, whole-table aggregates) and was part of the 32-46 s first
		// render at 100 000 rows on EVERY step. Same deferral as the distribution step below: the panel is marked deferred, the rail's
		// own step 8 button posts STEP_VIEW, and wizard.js loadDeferredStep does the same for Back/Next and the goto-step links. The
		// aggregates themselves are cached (GoldenRecordsCache), so a second view costs nothing until a load or a decision changes them.
		final boolean centralisation = snapshot.project != null && WizardRules.CENTRALISATION.equals(snapshot.project.style);
		if (centralisation)
		{
			// lot C1-B: the CENTRALISATION summary and every other step of the path are written from the snapshot alone (wizard.AccelPages):
			// no zone tables, no intake, no distribution target to read
			model.summaryDeferred = false;
			model.distributionDeferred = false;
			return model;
		}
		if (model.currentStep == WizardRules.STEP_SUMMARY)
		{
			// automatic-intake wizard lot (2026-10-05): first - the summary's open points depend on the intake's state
			this.buildIntake(snapshot, model);
			this.buildSummary(snapshot, model);
		}
		else
		{
			model.summaryDeferred = snapshot.project != null && this.projectId != null && !this.projectId.isEmpty();
		}
		// distribution-wizard lot (2026-09-24): only when this step is the one being shown.
		// perf/wizard-distribution-lazy (2026-09-27): no longer also on the first render. buildDistribution lists
		// every connection (connectors_list + one connectors_describe per connector) and runs a live "select 1"
		// on the Snowflake connection: measured on PersVol6 (kernel.log 2026-09-27 00:24), it was ~10 s of an
		// 11 s wizard opening, for a step most openings never show. Step 7 is instead marked deferred, and
		// reached through a server round trip: the rail's own STEP_VIEW, or wizard.js when Back/Next or a
		// goto-step link shows the placeholder (WizardPage.Model#distributionDeferred).
		if (model.currentStep == WizardRules.STEP_DISTRIBUTION)
		{
			this.buildDistribution(snapshot, model);
		}
		else
		{
			// same guard as buildDistribution's own first line: no project yet, nothing to load later either
			model.distributionDeferred = snapshot.project != null && this.projectId != null && !this.projectId.isEmpty();
		}
		return model;
	}

	/**
	 * wizard-progress lot (2026-09-22): {@code wizard.js} is still read verbatim from the resource file
	 * ({@link #wizardJsBody()}) - the templating is a single {@code var} statement prepended in front of it,
	 * declaring the localized progress-toast labels the script's own {@code progress(...)} function reads
	 * (same {@code mdmfWizardProgressLabels.<key>} shape as {@code ProposalReviewUserService.pageJs}'s
	 * {@code progressLabel} object, just declared as a global instead of a local since this script's body
	 * stays a plain static resource rather than a Java-built string). {@link ProposalReviewUserService#jsString}
	 * is reused as-is (same package) rather than re-implemented here - every embedded label is real,
	 * localized message text, never assumed to be JS-safe.
	 */
	static String labelsScript(final Locale pLocale)
	{
		final String labels = "var mdmfWizardProgressLabels={plan:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.plan")) + ",generate:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.generate")) + ",stagingSuggest:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.stagingSuggest")) + ",stagingSuggestLlm:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.stagingSuggestLlm")) + ",preview:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.preview"))
			// landing key-study lot (2026-09-23): a live sample then, for an administrator, a language-model run
			+ ",keyStudy:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.keyStudy"))
			// wave 2 (2026-09-30): the deferred step 6 (pairs) / step 8 (summary) load - wizard.js loadDeferredStep
			+ ",stepLoad:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.stepLoad"))
			// perf/wizard-distribution-lazy (2026-09-27): the deferred step 7 load (wizard.js loadDeferredStep)
			+ ",distributionLoad:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.distributionLoad"))
			// pipeline-buttons lot (2026-09-23)
			// lot C1-B: pass 0 (pack choice) is a synchronous language-model call
			+ ",accelSuggest:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.accelSuggest"))
			+ ",qualitySuggestLlm:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.qualitySuggestLlm"))
			+ ",capabilitySuggestLlm:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.capabilitySuggestLlm"))
			+ ",stagingGenerate:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.stagingGenerate"))
			+ ",stagingLoad:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.stagingLoad"))
			+ ",masterGenerate:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.masterGenerate"))
			+ ",masterLoad:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.masterLoad"))
			+ ",matchingSuggestLlm:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.matchingSuggestLlm"))
			+ ",matchingConfigure:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.matchingConfigure"))
			+ ",matchingRun:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.matchingRun"))
			// inline-reviews lot (2026-09-23): the same review.progress.* labels ReviewPage's own screen already
			// uses for these exact same actions (SCRIPT_CONSTRAINT apply/remove/measure, MATCH repropose) - same
			// bundle (com.on.ebx.factory.ebx.ui.Messages), reused rather than duplicated under a wizard.* key.
			+ ",apply:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "review.progress.apply"))
			+ ",remove:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "review.progress.remove"))
			+ ",measure:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "review.progress.measure"))
			+ ",repropose:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "review.progress.repropose"))
			+ ",simulate:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.simulate"))
			// source-onboarding lot (2026-09-23; revised after coordinator review the same day): OPEN/PICK/REFRESH
			// read live Connectors state (connectors_list, and for PICK/REFRESH also connectors_describe/
			// connectors_catalog_source), CATALOG/SCAN are real MCP writes - see wizard.js's own action-handler
			// comment for which of the seven actions gets a toast at all (SOURCE_ONBOARD_USE/CLOSE do not: both
			// are plain, fast EBX-only reads/resets, as fast as SOURCE_PICK_CATALOG already is with no toast)
			+ ",onboardOpen:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.onboardOpen")) + ",onboardPick:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.onboardPick")) + ",onboardCatalog:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.onboardCatalog")) + ",onboardScan:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.onboardScan"))
			// sample-files lot (2026-09-23): SOURCE_ONBOARD_ALL chains up to two real MCP writes, SOURCE_FILES_IMPORT
			// runs the whole per-file chain (connection write + onboarding + application add) for every dropped file
			// automatic-intake wizard lot (2026-10-05): switching on installs triggers and republishes the landing models (synchronous, can take minutes), switching off removes them
			+ ",intakeSwitchOn:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.intakeSwitchOn"))
			+ ",intakeSwitchOff:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.intakeSwitchOff"))
			+ ",onboardAll:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.onboardAll")) + ",sampleFilesImport:"
			+ ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.progress.sampleFilesImport")) + "};\n";
		// lot C2 (2026-09-24): the jobs banner's own labels (wizard.js, "jobs banner" block)
		final String jobLabels = "var mdmfJobLabels={queued:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.queued"))
			+ ",running:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.running"))
			+ ",stopRequested:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.stopRequested"))
			+ ",stop:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.stop"))
			+ ",cancel:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.cancel"))
			+ ",refresh:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.refresh"))
			+ ",ended:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.ended"))
			+ ",dismiss:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.dismiss"))
			+ ",status:{DONE:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.status.DONE"))
			+ ",FAILED:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.status.FAILED"))
			+ ",CANCELLED:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.status.CANCELLED"))
			+ ",INTERRUPTED:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.status.INTERRUPTED")) + "}"
			// audit F-43: every kind the bundle names (wizard.jobs.kind.*), not a hand-kept list that missed the kinds added later
			+ ",kinds:" + ProjectWizardUserService.jobKindLabels(pLocale) + "};\n";
		// wizard-match-tree lot (2026-09-23): the decision-tree block's own node selection and fold expansion
		// (review.MatchPlanTreeDiagram#SCRIPT, exposed by MatchPlanView.treeScript) - the same script the
		// Proposal review page appends; it expects a `root` variable, so it gets its own scope here, wizard.js's
		// own `root` being private to that file's closure.
		// wave 11: the one sentence wizard.js needs that is not a progress label (a confirmation before a step is replaced by another one)
		final String wizardLabels = "var mdmfWizardLabels={leaveDirty:" + ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.step.leaveDirty")) + "};\n";
		return labels + jobLabels + wizardLabels + JobNotice.script(pLocale) + FactoryHtml.resourceText("combo.js") + "\n";
	}

	/**
	 * Wave 11 (audit F-42): the small block of the wizard's script that is always written inline - the decision-tree and the matching-results
	 * scripts (a thousand characters each, Java constants of other packages), in a scope of their own: {@code wizard.js}'s {@code root} is private to that file.
	 */
	static String scriptTail()
	{
		return "\n(function(){var root=document.querySelector('.mdmf-wizard');if(!root)return;"
			+ MatchPlanView.treeScript()
			// wizard-matching-results lot (2026-09-24): the "Matching results" block's own tab/pair switching -
			// same scoping idea (a private `root`), appended right after the decision-tree script above.
			+ com.on.ebx.factory.matching.MatchResultsView.script() + "})();\n";
	}

	/** The system property that brings back the former page, every step written at every display: {@code -Debx.factory.wizard.allSteps=true}. */
	static final String ALL_STEPS_PROPERTY = "ebx.factory.wizard.allSteps";

	/** The system property that brings back the former way (the whole script written inline at every display): {@code -Debx.factory.wizard.inlineScripts=true}. */
	static final String INLINE_SCRIPTS_PROPERTY = "ebx.factory.wizard.inlineScripts";

	/**
	 * The whole script in one piece, {@code wizard.js} included: what the page carried at every display before wave 11, and what it still carries
	 * when {@link #INLINE_SCRIPTS_PROPERTY} is set. By default {@link #write} writes {@link #scriptHead} and {@link #scriptTail} and lets the browser fetch
	 * {@code wizard.js} (a static file, cached under a build-stamped address). The offline tests read this form.
	 */
	static String script(final Locale pLocale)
	{
		return ProjectWizardUserService.scriptHead(pLocale) + ProjectWizardUserService.wizardJsBody() + ProjectWizardUserService.scriptTail();
	}

	/**
	 * What the page needs before {@code wizard.js} runs: its localized labels, the end-of-task notice and the shared combobox - declared inline,
	 * since they depend on the language (the notice and the combobox are also read from the class path by two other screens).
	 */
	static String scriptHead(final Locale pLocale)
	{
		return ProjectWizardUserService.labelsScript(pLocale);
	}

	/**
	 * Wave 11 (audit F-42): loads {@code wizard.js} (99 KB, identical at every display) from its static address instead of copying it into every page.
	 * If the file cannot be fetched (the address answers an error, a proxy returns something else) it is asked once more with a changing query that no
	 * cache can have kept, and if that fails too the page shows its own notice ({@code #mdmfScriptFail}) instead of a wizard whose buttons do nothing.
	 * The script sets {@code window.mdmfWizardReady} when it starts.
	 */
	static String loaderScript(final String pUrl)
	{
		return "(function(){var url=" + ProposalReviewUserService.jsString(pUrl) + ",retried=false;"
			+ "function fail(){var n=document.getElementById('mdmfScriptFail');if(n)n.hidden=false;}"
			+ "function load(u){var s=document.createElement('script');s.type='text/javascript';s.async=false;"
			+ "s.onload=function(){if(!window.mdmfWizardReady)again();};s.onerror=again;"
			+ "(document.body||document.head).appendChild(s);s.src=u;}"
			+ "function again(){if(retried){fail();return;}retried=true;load(url+(url.indexOf('?')<0?'?':'&')+'r='+(new Date().getTime()));}"
			+ "load(url);})();";
	}

	/** Writes the page's scripts: inline in one piece when {@link #INLINE_SCRIPTS_PROPERTY} says so (or when the class path has no copy of {@code wizard.js}), else the head, the loader of the static file and the tail. */
	private void writeScripts(final UserServicePaneWriter pWriter)
	{
		if (Boolean.getBoolean(ProjectWizardUserService.INLINE_SCRIPTS_PROPERTY))
		{
			try
			{
				pWriter.addJS_cr(ProjectWizardUserService.script(this.locale));
				return;
			}
			catch (final IllegalStateException ex)
			{
				// no wizard.js next to the classes (an Eclipse-only build never ran build.sh): the static file is the way then
				LoggingCategory.getKernel().warn("[mdm-factory] " + ProjectWizardUserService.INLINE_SCRIPTS_PROPERTY + " is set but wizard.js is not on the class path: " + ex.getMessage());
			}
		}
		pWriter.add("<div class=\"mdmf\"><div id=\"mdmfScriptFail\" class=\"mdmf-notice mdmf-notice-warn\" role=\"alert\" hidden=\"hidden\">")
			.add(FactoryHtml.esc(Messages.get(ProjectWizardUserService.class, this.locale, "wizard.script.failed"))).add("</div></div>");
		pWriter.addJS_cr(ProjectWizardUserService.scriptHead(this.locale));
		pWriter.addJS_cr(ProjectWizardUserService.loaderScript(pWriter.getURLForResource(ResourceType.JSCRIPT, "factory/wizard.js") + "?v=" + Assets.version()));
		pWriter.addJS_cr(ProjectWizardUserService.scriptTail());
	}

	/** Audit F-43: a JS object literal of every job kind's label in the bundle ({@code wizard.jobs.kind.<KIND>}), sorted. */
	static String jobKindLabels(final Locale pLocale)
	{
		final java.util.TreeSet<String> kinds = new java.util.TreeSet<String>();
		final java.util.ResourceBundle bundle = java.util.ResourceBundle.getBundle(ProjectWizardUserService.class.getPackage().getName() + ".Messages",
			pLocale == null ? Locale.ENGLISH : pLocale, ProjectWizardUserService.class.getClassLoader());
		for (final String key : bundle.keySet())
		{
			if (key.startsWith("wizard.jobs.kind.") && key.substring("wizard.jobs.kind.".length()).matches("[A-Z0-9_]+"))
			{
				kinds.add(key.substring("wizard.jobs.kind.".length()));
			}
		}
		final StringBuilder out = new StringBuilder("{");
		for (final String kind : kinds)
		{
			out.append(out.length() == 1 ? "" : ",").append(kind).append(':')
				.append(ProposalReviewUserService.jsString(Messages.get(ProjectWizardUserService.class, pLocale, "wizard.jobs.kind." + kind)));
		}
		return out.append('}').toString();
	}

	/**
	 * {@code wizard.js} as the class path holds it (copied there by {@code build.sh} from {@code www/common/jscripts/factory}, its one source); the combobox
	 * it calls ({@code combo.js}, {@code window.mdmfInitCombo}) comes before it in {@link #scriptHead}.
	 */
	private static String wizardJsBody()
	{
		return FactoryHtml.resourceText("wizard.js");
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// every check runs inside handle(), against fresh EBX data - see the class Javadoc
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
