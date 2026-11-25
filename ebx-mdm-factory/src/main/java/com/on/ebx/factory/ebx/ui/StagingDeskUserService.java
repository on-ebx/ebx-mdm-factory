package com.on.ebx.factory.ebx.ui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.factory.core.MappingMatrixLink;
import com.on.ebx.factory.ebx.DeskCheckReader;
import com.on.ebx.factory.ebx.DeskPreviewReader;
import com.on.ebx.factory.ebx.MissingInputReader;
import com.on.ebx.factory.ebx.MissingInputRun;
import com.on.ebx.factory.ebx.RuleBreakdownRun;
import com.on.ebx.factory.ebx.MappingMatrixReader;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.ebx.UserView;
import com.on.ebx.factory.ebx.WizardData;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.stagingdesk.DeskCheck;
import com.on.ebx.factory.stagingdesk.DeskCheckHtml;
import com.on.ebx.factory.stagingdesk.DeskGroups;
import com.on.ebx.factory.stagingdesk.DeskInput;
import com.on.ebx.factory.stagingdesk.DeskHtml;
import com.on.ebx.factory.stagingdesk.DeskMessages;
import com.on.ebx.factory.stagingdesk.DeskModel;
import com.on.ebx.factory.stagingdesk.DeskPointsHtml;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
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
 * "Validation desk" (docs/STAGING-DESK.md), lot L1: reads the project like the Mapping matrix ({@link MappingMatrixReader}, plus the wizard's snapshot for what the
 * matrix model does not hold), regroups what is still to decide with {@link DeskModel} / {@link DeskGroups}, draws it with {@link DeskHtml}, and posts every decision
 * through ONE EBX submit button ({@link DeskHtml#SUBMIT_ID}) and the matrix' own hidden fields ({@code mdmmAction}/{@code mdmmRowId}/{@code mdmmRowId2}/{@code mdmmComment}).
 * <p>
 * <b>Nothing is decided here.</b> {@link #handle} hands a decision to {@link MappingMatrixUserService#handle} - the matrix' own handler, built by {@link
 * MappingMatrixUserService#forDesk} - which re-checks the DESIGN right, the project and the write access, then calls {@code ProposalReviews#decide} (one row), the
 * entity bulk ({@code MatrixCompute#planEntityBulk} and the matrix' cleaning-step sweep) or {@code WizardData#setCapabilitiesAside}. The desk adds only the group
 * navigation and a whitelist of the actions it draws a button for.
 * <p>
 * {@code setupObjectContext} runs after the event on a postback (CLAUDE.md &sect;9), so the group the person is on travels in the hidden field {@code mdskGroup}, set by the
 * script BEFORE the submit, and is read back on every request. After a decision the page keeps that group while it still has something to decide, else moves to the
 * group that followed it in the last render ({@link DeskGroups#resolveSelection}).
 */
public final class StagingDeskUserService implements UserService<TableViewEntitySelection>
{
	/** The actions a button of the desk posts - the matrix' vocabulary; any other posted action is refused. */
	private static final Set<String> ACTIONS = new HashSet<String>(Arrays.asList("ACCEPT_ENTITY", "REJECT_ENTITY", "ACCEPT_ATTRIBUTE", "REJECT_ATTRIBUTE", "ACCEPT_FEED", "REJECT_FEED",
		"ACCEPT_MAPPING", "REJECT_MAPPING", "ACCEPT_ATTR_THEN_MAPPING", "ACCEPT_GUARD", "REJECT_GUARD", "SET_ASIDE_GUARD", "SET_ASIDE_GUARDS", "BULK_ENTITY", "ACCEPT_ENTITY_AS_SHOWN",
		// lot L3: "Use <value> for <source>" (the matrix' accept of an alternative mapping) and "Ask for a correction" (a background job, handled here)
		"ACCEPT_ALTERNATIVE", DeskPointsHtml.ASK,
		// lot L4: "Accept as proposed" on a conditioned rule (matrix handler) and "Measure" (a background job, handled here)
		DeskCheckHtml.ACCEPT_AS_PROPOSED, DeskCheckHtml.MEASURE));

	Adaptation factory;
	Session session;
	Locale locale = Locale.ENGLISH;
	String projectId;
	/** The project's business code (never {@link #projectId}, an occurrence primary key) - what the links to the other views need. */
	String projectCode;
	boolean projectResolved;

	/** The group the person is on: the {@code entity} launch parameter at first, then the posted {@code mdskGroup}. */
	String selectedKey;
	/** The scroll offset posted by the script before a submit ({@code mdskScroll}); negative = none. */
	int scrollTop = -1;
	int bulkThreshold = MatrixCompute.DEFAULT_BULK_THRESHOLD;
	/** The keys of the groups as the last render showed them: kept on the service instance, which survives the round trip. */
	List<String> renderedKeys;
	/** Lot L2: the first landing row each feed's preview shows (feed id to offset), moved by "another 5" - pure navigation, kept on the service instance like {@link #renderedKeys}. */
	final java.util.Map<String, Integer> feedOffsets = new java.util.HashMap<String, Integer>();
	/** Lot L4: the check the person opened (a QualityRule id), "" = the list; posted in the hidden field {@code mdskCheck}. */
	String openCheckId = "";

	String message;
	boolean messageIsError;

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.factory = pContext.getEntitySelection().getDataset();
		this.session = pContext.getSession();
		if (!this.projectResolved)
		{
			this.projectResolved = true;
			final String code = AbstractRecordLineageUserService.param(pContext, "project");
			// with the user's view: a hidden project reads like an unknown code
			final Adaptation project = code.isEmpty() ? null : this.asUser(() -> ProposalReviews.findProjectByCode(this.factory, code));
			this.projectId = project == null ? null : project.getOccurrencePrimaryKey().format();
			this.projectCode = project == null ? null : code;
			final String entity = AbstractRecordLineageUserService.param(pContext, "entity");
			this.selectedKey = entity.isEmpty() ? null : DeskModel.objectKey(entity);
		}
		// hidden fields posted by the page's own script - read fresh on every request (class Javadoc)
		final String postedGroup = AbstractRecordLineageUserService.param(pContext, "mdskGroup");
		if (!postedGroup.isEmpty())
		{
			this.selectedKey = postedGroup;
		}
		this.scrollTop = MappingMatrixUserService.parseScroll(AbstractRecordLineageUserService.param(pContext, "mdskScroll"));
		this.openCheckId = AbstractRecordLineageUserService.param(pContext, "mdskCheck");
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(StagingDeskUserService.class, this.locale, "stagingDesk.title"));
		// dataspace, dataset, Project table and this service's own permission, read with the user's session
		String closed = ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (closed == null && !ProjectAccess.serviceOnTable(this.session, StagingDeskServiceDeclaration.SERVICE_KEY, pContext.getEntitySelection().getTable()))
		{
			closed = Messages.get(StagingDeskUserService.class, this.locale, "screen.service.refused");
		}
		if (closed != null)
		{
			final String message = closed;
			pConfigurator.setRightButtons(pConfigurator.newCloseButton());
			pConfigurator.setContent(new UserServicePane()
			{
				@Override
				public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
				{
					pWriter.add(ThemeTokens.styleTagFor(pPaneContext));
					FactoryHtml.writeClosed(pWriter, message);
				}
			});
			return;
		}
		// an unplanned failure of an action or of the rendering becomes a localized notice with a logged reference
		final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(StagingDeskUserService.class, this.locale, "stagingDesk.apply"),
			ScreenFailure.guard("staging desk", "action", this.locale, new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					StagingDeskUserService.this.handle(pEventContext);
					return null;
				}
			}, pNotice -> {
				this.message = pNotice;
				this.messageIsError = true;
			}));
		submit.setId(DeskHtml.SUBMIT_ID);
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setLeftButtons(submit);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(ScreenFailure.guard("staging desk", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				StagingDeskUserService.this.write(pWriter);
			}
		}));
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// nothing: plain HTML/JS, decisions are read back from posted hidden fields on submit (class Javadoc)
	}

	// ------------------------------------------------------------------------------------------------------------ submit

	void handle(final UserServiceEventContext pContext)
	{
		this.message = null;
		this.messageIsError = false;
		final String action = AbstractRecordLineageUserService.param(pContext, "mdmmAction");
		if (action.isEmpty() || "GOTO_GROUP".equals(action))
		{
			return; // pure navigation: the group was read from mdskGroup by setupObjectContext
		}
		if ("NEXT_ROWS".equals(action))
		{
			// lot L2, "another 5": the feed id and the offset the page asked for; nothing is written, the rows are read at the next display
			final String feedId = AbstractRecordLineageUserService.param(pContext, "mdmmRowId");
			final int offset = MappingMatrixUserService.parseScroll(AbstractRecordLineageUserService.param(pContext, "mdmmRowId2"));
			if (!feedId.isEmpty())
			{
				this.feedOffsets.put(feedId, Integer.valueOf(Math.max(0, offset)));
			}
			return;
		}
		if (DeskInput.ACTION_PROPOSE.equals(action))
		{
			this.proposeInputField(pContext);
			return;
		}
		if (!StagingDeskUserService.ACTIONS.contains(action))
		{
			return; // a posted action the desk draws no button for
		}
		// the DESIGN right, re-checked server-side (see the declaration's rule); the matrix handler checks it again with its own service name
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(), com.on.ebx.factory.core.FactoryRights.service(StagingDeskServiceDeclaration.SERVICE_NAME)))
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "review.notice.adminOnly"));
			return;
		}
		if (this.projectId == null)
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "mappingMatrix.notice.noProject"));
			return;
		}
		if (DeskPointsHtml.ASK.equals(action))
		{
			this.askCorrection(AbstractRecordLineageUserService.param(pContext, "mdmmRowId"));
			return;
		}
		if (DeskCheckHtml.MEASURE.equals(action))
		{
			this.measureCheck(AbstractRecordLineageUserService.param(pContext, "mdmmRowId"));
			return;
		}
		// the decision itself: the matrix' own handler (project and write access re-checked there, then ProposalReviews#decide / the entity bulk / setCapabilitiesAside)
		final MappingMatrixUserService matrix = MappingMatrixUserService.forDesk(this.factory, this.session, this.locale, this.projectId, this.projectCode, this.bulkThreshold,
			(pProject, pEntity) -> this.asUser(() -> DeskPreviewReader.shownKeys(this.factory, this.session, pProject, pEntity, this.feedOffsets)));
		matrix.handle(pContext);
		this.message = matrix.message;
		this.messageIsError = matrix.messageIsError;
	}

	/**
	 * Lot L5, "Propose a field for it": queues {@link MissingInputRun} (the capability pass narrowed to the computed field a waiting suggestion needs) as a background job. The same
	 * checks as a decision - the DESIGN right, the project, write access - then nothing else is written here; the job re-reads what waits when it runs.
	 */
	private void proposeInputField(final UserServiceEventContext pContext)
	{
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(), com.on.ebx.factory.core.FactoryRights.service(StagingDeskServiceDeclaration.SERVICE_NAME)))
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "review.notice.adminOnly"));
			return;
		}
		if (this.projectId == null)
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "mappingMatrix.notice.noProject"));
			return;
		}
		if (!MappingMatrixUserService.forDesk(this.factory, this.session, this.locale, this.projectId, this.projectCode, this.bulkThreshold).canEdit())
		{
			this.fail(DeskMessages.get(this.locale, "desk.readOnly"));
			return;
		}
		try
		{
			MissingInputRun.start(this.session, this.factory, this.projectCode);
			this.message = Messages.get(StagingDeskUserService.class, this.locale, "desk.l5.notice.queued");
		}
		catch (final OperationException ex)
		{
			this.fail(ex.getMessage());
		}
	}

	/**
	 * Lot L3, "Ask for a correction": queues the anomaly pass for this business object as a background job ({@link com.on.ebx.factory.ebx.AnomalyReviewRun}); the model's
	 * proposals come back as alternative mappings the callout then offers. Same checks as a decision: write access to the dataset and the project, and an entity of THIS project.
	 */
	private void askCorrection(final String pEntityId)
	{
		final MappingMatrixUserService matrix =
			MappingMatrixUserService.forDesk(this.factory, this.session, this.locale, this.projectId, this.projectCode, this.bulkThreshold);
		if (!matrix.canEdit())
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "mappingMatrix.notice.readOnly"));
			return;
		}
		final Adaptation entity = pEntityId.isEmpty() ? null : this.asUser(() -> UserView.byKey(this.factory.getTable(com.on.ebx.factory.core.FactoryPaths.STAGING_ENTITY),
			com.onwbp.adaptation.PrimaryKey.parseString(pEntityId)));
		if (entity == null || !this.projectId.equals(String.valueOf(entity.get(com.on.ebx.factory.core.FactoryPaths.StagingEntity.PROJECT))))
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "mappingMatrix.notice.noProject"));
			return;
		}
		try
		{
			com.on.ebx.factory.ebx.AnomalyReviewRun.start(this.session, this.factory, this.projectCode, pEntityId);
			this.message = DeskPointsHtml.askedNotice(this.locale, String.valueOf(entity.get(com.on.ebx.factory.core.FactoryPaths.StagingEntity.NAME)));
			this.messageIsError = false;
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			this.fail(DeskPointsHtml.askRefusedNotice(this.locale, EngineNotice.inline(ex, this.locale)));
		}
	}

	/** Lot L4: queues the study of one rule (where does it fail) as a background job - never run in the postback. The matrix' write access is required, the rule is checked to be the project's. */
	private void measureCheck(final String pRuleId)
	{
		if (!MappingMatrixUserService.forDesk(this.factory, this.session, this.locale, this.projectId, this.projectCode, this.bulkThreshold).canEdit())
		{
			this.fail(Messages.get(StagingDeskUserService.class, this.locale, "mappingMatrix.notice.readOnly"));
			return;
		}
		try
		{
			RuleBreakdownRun.start(this.session, this.factory, this.projectId, pRuleId);
			this.message = DeskMessages.get(this.locale, "desk.l4.notice.measureQueued");
		}
		catch (final OperationException ex)
		{
			this.fail(DeskMessages.get(this.locale, "desk.l4.notice.measureRefused"));
		}
	}

	private void fail(final String pMessage)
	{
		this.message = pMessage;
		this.messageIsError = true;
	}

	// ---------------------------------------------------------------------------------------------------------- display

	private void write(final UserServicePaneWriter pWriter)
	{
		// a project hidden from the user (or deleted) since the last request reads like no project
		if (this.projectId != null && ProjectAccess.openProject(this.session, this.factory, this.projectId) == null)
		{
			this.projectId = null;
			this.projectCode = null;
		}
		if (this.projectId == null)
		{
			pWriter.add("<p>").add(Messages.get(StagingDeskUserService.class, this.locale, "mappingMatrix.notice.noProject")).add("</p>");
			return;
		}
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale));
		final WizardSnapshot snapshot = WizardData.readSnapshot(UserView.of(this.session), this.factory, this.projectId, this.session);
		final DeskModel model = DeskModel.of(project, snapshot, this.bulkThreshold);
		// the group of the person while it still has something to decide, else the one that followed it in the last render
		final String selected = DeskGroups.resolveSelection(model, this.selectedKey, this.renderedKeys);
		this.selectedKey = selected.isEmpty() ? null : selected;
		this.renderedKeys = model.keys();

		final DeskHtml.State state = new DeskHtml.State();
		state.locale = this.locale;
		state.selectedKey = selected;
		state.message = this.message;
		state.messageIsError = this.messageIsError;
		state.bulkThreshold = this.bulkThreshold;
		state.scrollTop = this.scrollTop;
		state.readOnly = !MappingMatrixUserService.forDesk(this.factory, this.session, this.locale, this.projectId, this.projectCode, this.bulkThreshold).canEdit();
		final String dataspaceKey = this.factory.getHome().getKey().format();
		final String datasetName = this.factory.getAdaptationName().getStringName();
		final DeskModel.Group group = model.group(selected);
		final String entityId = group == null ? "" : group.entityId;
		if (this.projectCode != null && !this.projectCode.isEmpty())
		{
			state.wizardUrl = FactoryLinks.wizardUrl(dataspaceKey, datasetName, this.projectCode, WizardRules.STEP_STAGING);
			state.matrixUrl = MappingMatrixLink.url(dataspaceKey, datasetName, this.projectCode, entityId.isEmpty() ? null : entityId);
		}
		state.designerUrl = StagingDeskUserService.designerUrl(project, entityId);
		// lot L2: the centre of an object group shows real landing rows of THAT object only; any failure of the preview falls back to the L1 list
		final MatrixModel.Entity entity = group == null || !DeskModel.OBJECT.equals(group.kind) ? null : project.entityById(group.entityId);
		if (entity != null)
		{
			try
			{
				state.preview = this.asUser(() -> DeskPreviewReader.read(this.factory, this.session, project, entity, this.feedOffsets, new DeskPreviewReader.Cost()));
			}
			catch (final RuntimeException ex)
			{
				state.preview = null;
			}
		}
		// lot L5: the cards of the "Cannot run as proposed" group (inputs no field holds); any failure of the read leaves the group as it was
		final boolean cannotRunSelected = group != null && DeskModel.CANNOT_RUN.equals(group.kind);
		if (cannotRunSelected)
		{
			try
			{
				final DeskInput input = this.asUser(() -> MissingInputReader.read(this.factory, this.projectId));
				input.jobRunning = this.asUser(() -> MissingInputRun.isActive(this.factory, this.projectId));
				state.input = input;
			}
			catch (final RuntimeException ex)
			{
				state.input = null;
			}
		}
		// lot L3: the points to check of the object on screen (pure read of the matrix model already built)
		// lot L3b: what the model answered the last time it was asked about this object (the latest DONE anomaly-review job); a failed read means "never asked"
		java.util.Map<String, com.on.ebx.factory.staging.ModelAnswers.Answer> modelAnswers = new java.util.HashMap<String, com.on.ebx.factory.staging.ModelAnswers.Answer>();
		if (entity != null)
		{
			try
			{
				modelAnswers = this.asUser(() -> com.on.ebx.factory.ebx.AnomalyReviewRun.answersOf(this.factory, this.projectId, entity.id));
			}
			catch (final RuntimeException ex)
			{
				modelAnswers = new java.util.HashMap<String, com.on.ebx.factory.staging.ModelAnswers.Answer>();
			}
		}
		state.points = entity == null ? new java.util.ArrayList<DeskPointsHtml.Point>() : DeskPointsHtml.collect(project, entity, modelAnswers);
		// lot L4: the checks of a "Checks" group can be looked at on the values they would flag
		if (group != null && DeskModel.CHECKS.equals(group.kind))
		{
			final java.util.Map<String, String[]> names = new java.util.LinkedHashMap<String, String[]>();
			for (final DeskModel.Line line : group.lines)
			{
				if (line.item.guard != null && com.on.ebx.factory.review.ReviewLine.QUALITY.equals(line.item.guard.nature))
				{
					names.put(line.item.id, new String[] { line.item.entityName, line.item.attributeName });
				}
			}
			try
			{
				state.checks = this.asUser(() -> DeskCheckReader.read(this.factory, this.projectId, names));
			}
			catch (final RuntimeException ex)
			{
				state.checks = new java.util.HashMap<String, DeskCheck>(); // the plain list of L1 stays
			}
			state.openCheckId = state.checks.containsKey(this.openCheckId) ? this.openCheckId : "";
			this.openCheckId = state.openCheckId;
		}
		else
		{
			this.openCheckId = "";
		}
		pWriter.add(DeskHtml.render(model, state));
		// the navigation scripts go FIRST (docs/MAPPING-MATRIX.md sec 10: EBX runs every addJS of a page as ONE script, an exception in an earlier part stops the later ones)
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session), state.wizardUrl));
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-matrix-designer-link"));
		pWriter.addJS_cr(FactoryHtml.perspectiveLinkRewriteScript());
		pWriter.addJS_cr(DeskHtml.script(this.locale));
		// lot L3: the end-of-task notice of the correction asked from a callout (reads the project's jobs once, then follows them only while one of this person's is active)
		if (this.projectCode != null && !this.projectCode.isEmpty())
		{
			pWriter.addJS_cr(JobNotice.script(this.locale) + JobNotice.watchCall(JobNotice.jobsUrl(this.projectCode) + "?lang=" + this.locale.getLanguage()));
		}
	}

	/** The Feed designer link of the first root feed of the business object (else of the project): the same rule as the matrix' view switch, without the per-source refinement. */
	static String designerUrl(final MatrixModel.Project pProject, final String pEntityId)
	{
		String fallback = "";
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				if (feed.parentFeedId != null || feed.designerUrl.isEmpty())
				{
					continue;
				}
				if (entity.id.equals(pEntityId))
				{
					return feed.designerUrl;
				}
				if (fallback.isEmpty())
				{
					fallback = feed.designerUrl;
				}
			}
		}
		return fallback;
	}

	/** A pure read of a shared reader with the user's view ({@code UserView#scoped}): rows and fields the user's EBX permissions show. */
	private <T> T asUser(final java.util.function.Supplier<T> pRead)
	{
		return UserView.scoped(UserView.of(this.session), pRead);
	}
}
