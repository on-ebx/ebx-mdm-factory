package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.ebx.MappingMatrixReader;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.ebx.ScriptDerivations;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixHtml;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.AccessPermission;
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
 * "Mapping matrix" (docs/MAPPING-MATRIX.md): reads the whole project with {@link MappingMatrixReader},
 * renders it with {@link MatrixHtml} inside one {@link UserServicePane}, and posts every decision (accept/
 * reject/bulk-accept of an entity, attribute, feed or mapping, and entity-tab switching) through the ONE
 * shared hidden submit button ({@link #SUBMIT_ID}) and the {@code mdmmAction}/{@code mdmmRowId}/{@code
 * mdmmRowId2}/{@code mdmmComment} hidden fields {@link MatrixHtml} renders - the exact {@code
 * data-mdmf-action} convention {@link ProjectWizardUserService} already uses (CLAUDE.md &sect;9: one real
 * form, every button either a plain JS toggle or a driver of this one submit).
 * <p>
 * {@code setupObjectContext} runs again on every postback (CLAUDE.md &sect;9), so every piece of state
 * this page needs across a round trip - the active entity, the highlighted source, the "only todo"
 * toggle, the selected cell - is read back from its own hidden field EVERY time, never assumed to survive
 * on its own; {@link #handle} (the actual decision) always re-reads and re-validates through {@link
 * ProposalReviews#decide}, never trusting what the page last rendered, same rule every other screen of
 * this module follows.
 * <p>
 * <b>Coordinator review (2026-09-23):</b> two fixes on top of the first cut of this screen. (1) Every
 * decision {@link #handle} performs now RE-CHECKS {@link #canEdit()} first and refuses, fail-closed, with a
 * message, rather than trusting that the buttons {@link MatrixHtml} rendered were actually disabled - a
 * hand-crafted request must never bypass the read-only gate just because the page happened to render
 * enabled buttons for a session that has since lost write access. (2) The missing ENTITY/ATTRIBUTE/FEED
 * decisions and the two-step cell "accept the attribute and this mapping" - see {@link #decideOne}/{@link
 * #acceptAttributeThenMapping} and {@code mappingmatrix.MatrixCompute#cellAcceptAction}. Every bulk sweep
 * ({@link #bulkEntity}/{@link #bulkFeed}) now goes through {@code MatrixCompute#planEntityBulk}/{@code
 * #planFeedBulk} - the SAME cascade order and the SAME {@code wizard.WizardRules#bulkEligibility} rule
 * {@code ProjectWizardUserService#bulkStaging} uses for the rows it already knows, extended with FEEDS
 * (which {@code bulkStaging} never touches) - and reports ok/skipped/left-out-below-threshold, never just
 * an accepted count.
 */
public final class MappingMatrixUserService implements UserService<TableViewEntitySelection>
{
	private static final String SUBMIT_ID = "mdmmSubmit";

	Adaptation factory;
	Session session;
	Locale locale = Locale.ENGLISH;
	String projectId;
	/** fix/wizard-live-wave1 (2026-09-24, live bug item 2): the project's own business code (never {@link #projectId}, an occurrence primary key) - what {@code FactoryLinks.wizardUrl} needs for the "Back to the wizard" link. */
	String projectCode;
	boolean projectResolved;

	String activeEntityId;
	String focusSourceId;
	boolean onlyTodo;
	/** UX lot U6: true once the page has posted its own "Only what's left to do" state - until then (first display) the default rule {@code MatrixCompute#defaultOnlyTodo} applies. */
	boolean onlyTodoPosted;
	/** UX lot U6: set by a bulk accept; the render that answers it opens with "Only what's left to do" ON and the browser remembers it. */
	boolean bulkJustDone;
	String selectedCellKey;
	/** Staging-screens lot (2026-10-17): the page's scroll offset as posted by the script before a submit ({@code mdmmScroll}); negative = none. Echoed back so the render puts the page where it was. */
	int scrollTop = -1;
	/** Staging-screens lot (2026-10-17): set by {@link #loadStaging} once the staging build is started - the render answering it sends the page back to the wizard's step 4 (live panel). */
	boolean goToWizard;
	/** The "left to decide" panel: "" closed, "open", "all" - {@link MatrixHtml.State#queueMode}, posted back in {@code mdmmQueue}. */
	String queueMode = "";
	int bulkThreshold = MatrixCompute.DEFAULT_BULK_THRESHOLD;

	/**
	 * Matrix review queue lot (2026-09-30): the keys of the "left to decide" list as the LAST render showed them, in
	 * order - kept on the service instance (it survives the round trip) so a decision can say which item followed
	 * the decided one without re-reading the project first. Null until the first render.
	 */
	List<String> renderedQueueKeys;
	/** Set by {@link #handle} for a single decision: the decided item's key and the keys that followed it - consumed by the next {@link #write}, which selects the next item still to decide. */
	String advanceFromKey;
	List<String> advanceFollowingKeys;
	/** Validation desk lot L2: the feeds shown and the links flagged for an object, supplied by the desk for {@code ACCEPT_ENTITY_AS_SHOWN}; null in the matrix itself. */
	java.util.function.BiFunction<MatrixModel.Project, MatrixModel.Entity, MatrixCompute.ShownKeys> asShownKeys;
	/** What the last {@link #applyPlan} decided and skipped, for a caller that words its own notice. */
	private int lastApplyOk;
	private int lastApplySkipped;

	/** True from a postback that carried an action until the render that answers it - the page then scrolls the selection into view. */
	boolean postback;

	String message;
	boolean messageIsError;

	/**
	 * Validation desk lot L1 (docs/STAGING-DESK.md): a matrix handler for the desk to hand a decision to. The desk posts the matrix' own hidden fields and action names, so
	 * {@link #handle} - right check, project check, write-access check, then {@code ProposalReviews#decide}, the entity bulk or {@code WizardData#setCapabilitiesAside} - is the
	 * one path both screens write through; after the call the desk reads {@link #message} / {@link #messageIsError}.
	 */
	static MappingMatrixUserService forDesk(final Adaptation pFactory, final Session pSession, final Locale pLocale, final String pProjectId, final String pProjectCode,
		final int pBulkThreshold)
	{
		return MappingMatrixUserService.forDesk(pFactory, pSession, pLocale, pProjectId, pProjectCode, pBulkThreshold, null);
	}

	/**
	 * Same, with what {@code ACCEPT_ENTITY_AS_SHOWN} needs (Validation desk lot L2): {@code pAsShownKeys} answers, for the object freshly read, the feeds the desk showed rows of and the
	 * links its preview flagged ({@code ebx.DeskPreviewReader#shownKeys}). Without it that action is refused.
	 */
	static MappingMatrixUserService forDesk(final Adaptation pFactory, final Session pSession, final Locale pLocale, final String pProjectId, final String pProjectCode,
		final int pBulkThreshold, final java.util.function.BiFunction<MatrixModel.Project, MatrixModel.Entity, MatrixCompute.ShownKeys> pAsShownKeys)
	{
		final MappingMatrixUserService matrix = new MappingMatrixUserService();
		matrix.asShownKeys = pAsShownKeys;
		matrix.factory = pFactory;
		matrix.session = pSession;
		matrix.locale = pLocale;
		matrix.projectId = pProjectId;
		matrix.projectCode = pProjectCode;
		matrix.projectResolved = true;
		matrix.bulkThreshold = pBulkThreshold;
		return matrix;
	}

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.factory = pContext.getEntitySelection().getDataset();
		this.session = pContext.getSession();
		if (!this.projectResolved)
		{
			this.projectResolved = true;
			final String code = AbstractRecordLineageUserService.param(pContext, "project");
			// second permissions pass (2026-10-14): with the user's view - a hidden project reads like an unknown code
			final Adaptation project = code.isEmpty() ? null : this.asUser(() -> ProposalReviews.findProjectByCode(this.factory, code));
			this.projectId = project == null ? null : project.getOccurrencePrimaryKey().format();
			this.projectCode = project == null ? null : code;
			this.activeEntityId = AbstractRecordLineageUserService.param(pContext, "entity");
		}
		// hidden fields posted by mappingmatrix.MatrixHtml's own script - read fresh on every request (class Javadoc)
		final String postedEntity = AbstractRecordLineageUserService.param(pContext, "mdmmEntity");
		if (!postedEntity.isEmpty())
		{
			this.activeEntityId = postedEntity;
		}
		final String postedFocus = AbstractRecordLineageUserService.param(pContext, "mdmmFocus");
		this.focusSourceId = postedFocus.isEmpty() ? null : postedFocus;
		final String postedTodo = AbstractRecordLineageUserService.param(pContext, "mdmmOnlyTodo");
		this.onlyTodoPosted = !postedTodo.isEmpty();
		this.onlyTodo = "1".equals(postedTodo);
		final String postedScroll = AbstractRecordLineageUserService.param(pContext, "mdmmScroll");
		this.scrollTop = MappingMatrixUserService.parseScroll(postedScroll);
		final String postedSelected = AbstractRecordLineageUserService.param(pContext, "mdmmSelected");
		this.selectedCellKey = postedSelected.isEmpty() ? null : postedSelected;
		final String postedQueue = AbstractRecordLineageUserService.param(pContext, "mdmmQueue");
		this.queueMode = "open".equals(postedQueue) || "all".equals(postedQueue) ? postedQueue : "";
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.title"));
		// second permissions pass (2026-10-14): dataspace, dataset, Project table and this service's own permission, read with the user's session
		String closed = ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (closed == null && !ProjectAccess.serviceOnTable(this.session, MappingMatrixServiceDeclaration.SERVICE_KEY, pContext.getEntitySelection().getTable()))
		{
			closed = Messages.get(MappingMatrixUserService.class, this.locale, "screen.service.refused");
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
		// audit F-41: an unplanned failure of an action or of the rendering becomes a localized notice with a logged reference
		final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.apply"),
			ScreenFailure.guard("mapping matrix", "action", this.locale, new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					MappingMatrixUserService.this.handle(pEventContext);
					return null;
				}
			}, pNotice -> {
				this.message = pNotice;
				this.messageIsError = true;
			}));
		submit.setId(MappingMatrixUserService.SUBMIT_ID);
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setLeftButtons(submit);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(ScreenFailure.guard("mapping matrix", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				MappingMatrixUserService.this.write(pWriter);
			}
		}));
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext,
		final UserServiceEventOutcome pEventOutcome)
	{
		return pEventOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// nothing: plain HTML/JS, decisions are read back from posted hidden fields on submit (class Javadoc)
	}

	// ------------------------------------------------------------------------------------------------------ submit

	void handle(final UserServiceEventContext pContext)
	{
		this.message = null;
		this.messageIsError = false;
		// audit F-02: the DESIGN right, re-checked server-side (see the declaration's rule)
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(), com.on.ebx.factory.core.FactoryRights.service(MappingMatrixServiceDeclaration.SERVICE_NAME)))
		{
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "review.notice.adminOnly");
			this.messageIsError = true;
			return;
		}
		// second permissions pass (2026-10-14): the project is re-read with the user's view on every submit - hidden since, it is gone
		if (this.projectId != null && ProjectAccess.openProject(pContext.getSession(), this.factory, this.projectId) == null)
		{
			this.projectId = null;
			this.projectCode = null;
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "screen.project.unknown"));
			return;
		}
		final String action = AbstractRecordLineageUserService.param(pContext, "mdmmAction");
		final String rowId = AbstractRecordLineageUserService.param(pContext, "mdmmRowId");
		final String rowId2 = AbstractRecordLineageUserService.param(pContext, "mdmmRowId2");
		final String comment = pContext.getParameter("mdmmComment");
		if (this.projectId == null || action.isEmpty())
		{
			return; // GOTO_ENTITY with no id, or a stray submit - nothing to do beyond the entity/focus fields already re-read
		}
		this.postback = true;
		if ("GOTO_ENTITY".equals(action))
		{
			this.activeEntityId = rowId; // pure navigation - needs no write access, so this runs BEFORE the canEdit() gate below
			return;
		}
		// coordinator review (2026-09-23): re-checked on every decision, never inferred from the buttons
		// MatrixHtml happened to render enabled/disabled for an earlier read of the session's permission -
		// fail closed with a message rather than silently doing nothing.
		if (!this.canEdit())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.readOnly"));
			return;
		}
		if (MappingMatrixUserService.isSingleDecision(action))
		{
			// the item selected when the button was pressed (the list's and the inspector's buttons select first): once
			// the decision is written, the page selects the item that followed it
			this.advanceFromKey = AbstractRecordLineageUserService.param(pContext, "mdmmSelected");
			this.advanceFollowingKeys = this.keysAfter(this.advanceFromKey);
		}
		if ("ACCEPT_ENTITY".equals(action))
		{
			this.decideOne(ReviewLine.ENTITY, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		if ("REJECT_ENTITY".equals(action))
		{
			// task brief: no comment field on ENTITY - "just decide"
			this.decideOne(ReviewLine.ENTITY, rowId, ReviewLine.REJECTED, null);
			return;
		}
		if ("ACCEPT_ATTRIBUTE".equals(action))
		{
			// fix/matrix-computed-field: a computed field's function is decided with it - ProposalReviews carries the
			// field's PROPOSED SCRIPT_DERIVATION suggestion along (a no-op for any other field)
			this.decideAttributeWithItsDerivation(rowId);
			return;
		}
		if ("REJECT_ATTRIBUTE".equals(action))
		{
			this.decideOne(ReviewLine.ATTRIBUTE, rowId, ReviewLine.REJECTED, null);
			return;
		}
		if ("ACCEPT_FEED".equals(action))
		{
			this.decideOne(ReviewLine.FEED, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		if ("REJECT_FEED".equals(action))
		{
			this.decideOne(ReviewLine.FEED, rowId, ReviewLine.REJECTED, comment);
			return;
		}
		if ("ACCEPT_MAPPING".equals(action))
		{
			this.decideOne(ReviewLine.MAPPING, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		if ("REJECT_MAPPING".equals(action))
		{
			this.decideOne(ReviewLine.MAPPING, rowId, ReviewLine.REJECTED, comment);
			return;
		}
		if ("ACCEPT_ATTR_THEN_MAPPING".equals(action))
		{
			this.acceptAttributeThenMapping(rowId, rowId2);
			return;
		}
		if ("BULK_FEED".equals(action))
		{
			this.bulkJustDone = true;
			this.bulkFeed(rowId);
			return;
		}
		if ("BULK_ENTITY".equals(action))
		{
			this.bulkJustDone = true;
			this.bulkEntity(rowId);
			return;
		}
		if ("ACCEPT_ENTITY_AS_SHOWN".equals(action))
		{
			this.bulkJustDone = true;
			this.bulkEntityAsShown(rowId);
			return;
		}
		// staging-proposal matrix lot (delta 1): a guard is decided/severity-set through the SAME
		// ProposalReviews#decide / ebx.GuardWrites this project's other screens already use - never a second
		// write path. rowId2 carries the guard's own nature (QUALITY/SCRIPT_CONSTRAINT/EXTERNAL_SERVICE/
		// SCRIPT_DERIVATION), the same two-row-id convention ACCEPT_ATTR_THEN_MAPPING already established.
		if ("ACCEPT_GUARD".equals(action))
		{
			this.decideOne(rowId2, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		// Validation desk lot L4: the rule is accepted WITHOUT the condition the model proposed for it - the condition is dropped (a PROPOSED rule of this project only), then it is
		// the same decision as ACCEPT_GUARD
		if ("ACCEPT_GUARD_AS_PROPOSED".equals(action))
		{
			final String cleared = com.on.ebx.factory.ebx.DeskCheckWrites.clearCondition(this.session, this.factory, this.projectId, rowId);
			if (!com.on.ebx.factory.ebx.DeskCheckWrites.OK.equals(cleared))
			{
				this.fail(com.on.ebx.factory.stagingdesk.DeskMessages.get(this.locale, "desk.l4.notice.clearFailed." + cleared));
				return;
			}
			this.decideOne(rowId2, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		if ("REJECT_GUARD".equals(action))
		{
			this.decideOne(rowId2, rowId, ReviewLine.REJECTED, null);
			return;
		}
		// matrix review queue lot (2026-09-30): "Set aside" of a connector suggestion - the wizard card's own write
		// (WizardData#setCapabilitiesAside: this project, EXTERNAL_SERVICE, still PROPOSED, else skipped), never a second path.
		if ("SET_ASIDE_GUARD".equals(action))
		{
			this.setAside(rowId);
			return;
		}
		if ("SET_GUARD_SEVERITY".equals(action))
		{
			this.setGuardSeverity(rowId, rowId2, comment);
			return;
		}
		// delta 3: the suggested fix(es) of a flagged cell - all decided through the SAME ProposalReviews#decide
		// path, never a bespoke write. Accepting the alternative mapping is an ordinary MAPPING accept: lot
		// "run"'s own AttributeMapping.alternativeTo cascade (ProposalReviews#decide) rejects the mapping it
		// replaces - this screen only calls decide, exactly as it already does for every other mapping accept.
		if ("ACCEPT_ALTERNATIVE".equals(action))
		{
			this.decideOne(ReviewLine.MAPPING, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		if ("REMOVE_DUPLICATES".equals(action))
		{
			this.removeDuplicates(rowId);
			return;
		}
		if ("LOAD_STAGING".equals(action))
		{
			this.loadStaging();
			return;
		}
		if ("BULK_PROJECT".equals(action))
		{
			this.bulkJustDone = true;
			this.bulkProject();
			return;
		}
		// UX lot U6: every candidate look-up suggestion of one field at once (ids joined by commas) - set aside, or rejected
		if ("SET_ASIDE_GUARDS".equals(action))
		{
			this.setAsideMany(rowId);
			return;
		}
		if ("REJECT_GUARDS".equals(action))
		{
			this.rejectMany(rowId);
			return;
		}
		// codes transcoding lot H3 (2026-10-17): the code table of a mapping (a TRANSCODE_REFERENCE step with its own table) is decided through the SAME
		// ProposalReviews#decide as every other MappingStep; its Edit writes the person's targets back to the step (ebx.CodeTableEdits), nothing else
		if ("ACCEPT_CODES".equals(action))
		{
			this.decideOne(ReviewLine.MAPPING_STEP, rowId, ReviewLine.ACCEPTED, null);
			return;
		}
		if ("REJECT_CODES".equals(action))
		{
			this.decideOne(ReviewLine.MAPPING_STEP, rowId, ReviewLine.REJECTED, null);
			return;
		}
		if ("SAVE_CODES".equals(action))
		{
			this.saveCodes(rowId, comment);
			return;
		}
	}

	/** The scroll offset posted by the page ({@code mdmmScroll}): a non-negative whole number, else -1 (none). Pure. */
	static int parseScroll(final String pPosted)
	{
		if (pPosted == null || pPosted.isEmpty() || pPosted.length() > 9)
		{
			return -1;
		}
		try
		{
			final int value = Integer.parseInt(pPosted.trim());
			return value < 0 ? -1 : value;
		}
		catch (final NumberFormatException ex)
		{
			return -1;
		}
	}

	/**
	 * Staging-screens lot (2026-10-17): "Build and load the staging" of the footer - the wizard's own {@code STAGING_LOAD_RUN} (same job, same gate, same right), started from
	 * here; the render that answers goes back to the wizard's step 4, where the live panel shows it. Before, the footer only offered a link back to the wizard, whose real button
	 * sat below the fold under a sentence saying it would appear. Refused while anything is still to decide (the check is made again here, never trusted from the button).
	 */
	private void loadStaging()
	{
		if (this.projectCode == null || this.projectCode.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noProject"));
			return;
		}
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "wizard.gate.adminOnly"));
			return;
		}
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, this.activeEntityId, this.locale));
		final int left = MatrixCompute.itemsStillToDecide(project, this.bulkThreshold);
		if (left > 0)
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.buildNotReady", Integer.valueOf(left)));
			return;
		}
		try
		{
			final com.fasterxml.jackson.databind.node.ObjectNode result = com.on.ebx.factory.ebx.StagingLoadRun.start(this.session, this.factory, this.projectCode,
				com.on.ebx.factory.ebx.StagingLoadRun.STAGES, false);
			if (!result.path("started").asBoolean(false))
			{
				this.fail(result.path("reason").asText(Messages.get(MappingMatrixUserService.class, this.locale, "wizard.step4.build.refused")));
				return;
			}
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.buildStarted");
			this.goToWizard = true;
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			this.fail(EngineNotice.inline(ex, this.locale));
		}
	}

	/** The decisions after which the page moves to the next item still to decide - one row each, never a sweep, a navigation or a setting. */
	private static boolean isSingleDecision(final String pAction)
	{
		return "ACCEPT_ENTITY".equals(pAction) || "REJECT_ENTITY".equals(pAction) || "ACCEPT_ATTRIBUTE".equals(pAction) || "REJECT_ATTRIBUTE".equals(pAction)
			|| "ACCEPT_FEED".equals(pAction) || "REJECT_FEED".equals(pAction) || "ACCEPT_MAPPING".equals(pAction) || "REJECT_MAPPING".equals(pAction)
			|| "ACCEPT_ATTR_THEN_MAPPING".equals(pAction) || "ACCEPT_GUARD".equals(pAction) || "REJECT_GUARD".equals(pAction) || "SET_ASIDE_GUARD".equals(pAction)
			|| "ACCEPT_ALTERNATIVE".equals(pAction);
	}

	/** The keys that followed {@code pKey} in the last rendered list ({@link MatrixCompute#keysAfter}); empty when nothing was rendered yet. */
	private List<String> keysAfter(final String pKey)
	{
		return MatrixCompute.keysAfter(this.renderedQueueKeys, pKey);
	}

	/** Codes transcoding lot H3: "Edit" of a code table - the targets typed by the person are written to the proposal's step ({@link com.on.ebx.factory.ebx.CodeTableEdits}), which then belongs to them. */
	private void saveCodes(final String pStepId, final String pEditsJson)
	{
		if (pStepId == null || pStepId.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		final com.on.ebx.factory.ebx.CodeTableEdits.Result result = com.on.ebx.factory.ebx.CodeTableEdits.save(this.session, this.factory, pStepId, pEditsJson);
		if (!result.ok)
		{
			this.fail(result.reason);
			return;
		}
		this.message = Messages.get(MappingMatrixUserService.class, this.locale, result.unresolvedRows > 0L ? "mappingMatrix.notice.codesSavedIncomplete" : "mappingMatrix.notice.codesSaved",
			Long.valueOf(result.unresolvedRows));
	}

	private void setAside(final String pGuardId)
	{
		if (pGuardId == null || pGuardId.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		try
		{
			final int moved = com.on.ebx.factory.ebx.WizardData.setCapabilitiesAside(this.session, this.factory, this.projectId, java.util.Collections.singletonList(pGuardId));
			if (moved == 0)
			{
				this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.setAsideNone"));
				return;
			}
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.setAside");
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			this.fail(EngineNotice.inline(ex, this.locale));
		}
	}

	/** UX lot U6: "Set aside all" of the look-up suggestions of one field - one write ({@code WizardData#setCapabilitiesAside} skips what is not a still-proposed service of this project). */
	private void setAsideMany(final String pIds)
	{
		final List<String> ids = MappingMatrixUserService.splitIds(pIds);
		if (ids.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		try
		{
			final int moved = com.on.ebx.factory.ebx.WizardData.setCapabilitiesAside(this.session, this.factory, this.projectId, ids);
			if (moved == 0)
			{
				this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.setAsideNone"));
				return;
			}
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.setAsideMany", Integer.valueOf(moved), Integer.valueOf(ids.size() - moved));
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			this.fail(EngineNotice.inline(ex, this.locale));
		}
	}

	/** UX lot U6: "Reject all" - each suggestion through the same {@link ProposalReviews#decide} as a single Reject. */
	private void rejectMany(final String pIds)
	{
		final List<String> ids = MappingMatrixUserService.splitIds(pIds);
		if (ids.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		int ok = 0;
		int skipped = 0;
		for (final String id : ids)
		{
			if (ProposalReviews.decide(this.session, this.factory, ReviewLine.EXTERNAL_SERVICE, id, ReviewLine.REJECTED).ok)
			{
				ok++;
			}
			else
			{
				skipped++;
			}
		}
		this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.rejectedMany", Integer.valueOf(ok), Integer.valueOf(skipped));
	}

	/** The ids of a comma-joined posted list, blanks dropped - never more than 100 (a group holds a handful). */
	static List<String> splitIds(final String pIds)
	{
		final List<String> out = new ArrayList<String>();
		if (pIds == null)
		{
			return out;
		}
		for (final String part : pIds.split(","))
		{
			final String id = part.trim();
			if (!id.isEmpty() && out.size() < 100)
			{
				out.add(id);
			}
		}
		return out;
	}

	/** Delta 1: dispatches through {@code ebx.GuardWrites#setSeverity}, which itself dispatches by nature to the write that already exists ({@code WizardData}) - see that class' own Javadoc for why. */
	private void setGuardSeverity(final String pGuardId, final String pNature, final String pSeverity)
	{
		if (pGuardId == null || pGuardId.isEmpty() || pNature == null || pNature.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		try
		{
			com.on.ebx.factory.ebx.GuardWrites.setSeverity(this.session, this.factory, pNature, pGuardId, pSeverity);
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.decided");
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			this.fail(EngineNotice.inline(ex, this.locale));
		}
	}

	/** Delta 3: "Remove the N duplicate fields" - re-reads the entity fresh (never trusts what was rendered before this submit) and rejects every attribute {@code mappingmatrix.MatrixCompute#duplicateFieldAttributeIds} still names. */
	private void removeDuplicates(final String pEntityId)
	{
		if (pEntityId == null || pEntityId.isEmpty())
		{
			return;
		}
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale));
		final MatrixModel.Entity entity = project.entityById(pEntityId);
		if (entity == null)
		{
			return;
		}
		int ok = 0;
		int skipped = 0;
		for (final String attributeId : MatrixCompute.duplicateFieldAttributeIds(entity))
		{
			final ProposalReviews.DecisionResult result = ProposalReviews.decide(this.session, this.factory, ReviewLine.ATTRIBUTE, attributeId, ReviewLine.REJECTED);
			if (result.ok)
			{
				ok++;
			}
			else
			{
				skipped++;
			}
		}
		this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.bulk", Integer.valueOf(ok), Integer.valueOf(skipped));
	}

	/**
	 * Delta 4: the footer's project-wide "Accept the N unflagged proposals" - re-reads the WHOLE project fresh
	 * and runs {@code MatrixCompute#planProjectBulk}, then sweeps every entity's own mapping cleaning steps
	 * (see {@link #sweepMappingSteps}'s own Javadoc - {@code MatrixCompute} never models them).
	 */
	private void bulkProject()
	{
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale));
		final int[] stepCounts = new int[2];
		for (final MatrixModel.Entity entity : project.entities)
		{
			this.sweepMappingSteps(entity, entity.feeds, stepCounts);
		}
		this.applyPlan(MatrixCompute.planProjectBulk(project, this.bulkThreshold), stepCounts);
	}

	/** ACCEPT of a field: the ordinary decision plus, for a computed field, its {@code PROPOSED} function ({@code ProposalReviews#decide}'s {@code pCarryScriptDerivation}). */
	private void decideAttributeWithItsDerivation(final String pAttributeId)
	{
		if (pAttributeId == null || pAttributeId.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		final ProposalReviews.DecisionResult result =
			ProposalReviews.decide(this.session, this.factory, ReviewLine.ATTRIBUTE, pAttributeId, ReviewLine.ACCEPTED, null, false, true);
		if (!result.ok)
		{
			this.fail(result.reason);
			return;
		}
		// Validation desk lot L5: a computed field proposed for suggestions that waited for an input no field holds has just bound them
		if (result.inputsBound > 0)
		{
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "desk.l5.notice.decidedBound", Integer.valueOf(result.inputsBound));
			return;
		}
		// lot G1 (2026-10-17, item 3): accepting a computed field whose function needs a parameter says so now - the notice only, the accept/apply gating itself is unchanged
		final List<String> parameters = this.asUser(() -> ScriptDerivations.parametersToProvide(this.factory, pAttributeId));
		this.message = parameters.isEmpty() ? Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.decided")
			: Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.decidedNeedsParam", String.join(", ", parameters));
	}

	private void decideOne(final String pNature, final String pRowId, final String pTargetStatus, final String pComment)
	{
		if (pRowId == null || pRowId.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		final ProposalReviews.DecisionResult result = ProposalReviews.decide(this.session, this.factory, pNature, pRowId, pTargetStatus, pComment);
		if (!result.ok)
		{
			this.fail(result.reasonIn(this.locale));
			return;
		}
		this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.decided");
	}

	/**
	 * The cell's own two-step accept ({@code mappingmatrix.MatrixCompute.CellAcceptAction#ACCEPT_ATTRIBUTE_THEN_MAPPING}):
	 * accepts the attribute first, and only calls the mapping's own accept when that succeeded - never
	 * attempted at all when the entity itself is not accepted-like (the client never even builds this
	 * action then, see {@code MatrixHtml#renderScript}'s own {@code acceptAction} branches; this method is
	 * the server-side half of the same rule, re-validated exactly like every other decision).
	 */
	private void acceptAttributeThenMapping(final String pAttributeId, final String pMappingId)
	{
		if (pAttributeId == null || pAttributeId.isEmpty() || pMappingId == null || pMappingId.isEmpty())
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		final ProposalReviews.DecisionResult attributeResult = ProposalReviews.decide(this.session, this.factory, ReviewLine.ATTRIBUTE, pAttributeId, ReviewLine.ACCEPTED);
		if (!attributeResult.ok)
		{
			this.fail(attributeResult.reason);
			return;
		}
		final ProposalReviews.DecisionResult mappingResult = ProposalReviews.decide(this.session, this.factory, ReviewLine.MAPPING, pMappingId, ReviewLine.ACCEPTED);
		if (!mappingResult.ok)
		{
			this.fail(mappingResult.reason);
			return;
		}
		this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.decided");
	}

	/**
	 * Re-reads the entity fresh (never trusts what was rendered before this submit - class Javadoc), runs
	 * {@code MatrixCompute#planFeedBulk} and sweeps this feed's own mapping cleaning steps (see
	 * {@link #sweepMappingSteps}'s own Javadoc).
	 */
	private void bulkFeed(final String pFeedId)
	{
		if (this.activeEntityId == null || pFeedId == null || pFeedId.isEmpty())
		{
			return;
		}
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale));
		final MatrixModel.Entity entity = project.entityById(this.activeEntityId);
		if (entity == null)
		{
			return;
		}
		for (final MatrixModel.Feed feed : entity.feeds)
		{
			if (feed.id.equals(pFeedId))
			{
				final int[] stepCounts = new int[2];
				this.sweepMappingSteps(entity, java.util.Collections.singletonList(feed), stepCounts);
				this.applyPlan(MatrixCompute.planFeedBulk(entity, feed, this.bulkThreshold), stepCounts);
				return;
			}
		}
	}

	/**
	 * Re-reads the entity fresh, runs {@code MatrixCompute#planEntityBulk} (entity, then non-key attributes,
	 * then feeds, then mappings) and sweeps this entity's own mapping cleaning steps (see
	 * {@link #sweepMappingSteps}'s own Javadoc).
	 */
	private void bulkEntity(final String pEntityId)
	{
		if (pEntityId == null || pEntityId.isEmpty())
		{
			return;
		}
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale));
		final MatrixModel.Entity entity = project.entityById(pEntityId);
		if (entity == null)
		{
			return;
		}
		final int[] stepCounts = new int[2];
		this.sweepMappingSteps(entity, entity.feeds, stepCounts);
		this.applyPlan(MatrixCompute.planEntityBulk(entity, this.bulkThreshold), stepCounts);
	}

	/**
	 * Validation desk lot L2, "Accept &lt;object&gt; as shown": re-reads the project fresh, asks the desk which feeds it showed rows of and which links its preview flags, plans with
	 * {@code MatrixCompute#planEntityAsShown} (every shown proposal whatever its confidence, the flagged ones left) and writes it through {@link #applyPlan} - the same
	 * {@code ProposalReviews#decide} every other decision uses. No cleaning step is swept: the preview applied only the accepted ones.
	 */
	private void bulkEntityAsShown(final String pEntityId)
	{
		if (pEntityId == null || pEntityId.isEmpty() || this.asShownKeys == null)
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		final MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, null, this.locale));
		final MatrixModel.Entity entity = project.entityById(pEntityId);
		if (entity == null)
		{
			this.fail(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noSelection"));
			return;
		}
		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityAsShown(entity, this.asShownKeys.apply(project, entity));
		if (plan.toDecide.isEmpty())
		{
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.asShownNone");
			return;
		}
		this.applyPlan(plan, new int[2]);
		this.message = this.messageIsError ? this.message : Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.asShown",
			Integer.valueOf(this.lastApplyOk), Integer.valueOf(this.lastApplySkipped), Integer.valueOf(plan.held));
	}

	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test): {@code mappingmatrix.MatrixModel}/{@code MatrixCompute}
	 * never model a mapping's own cleaning steps at all (only {@code Cell#pipelineSteps}, plain display kind
	 * names) - so every bulk-accept plan {@code MatrixCompute} builds is silent about them, whatever the
	 * mapping cell's own status. A mapping already {@code ACCEPTED}/{@code APPLIED} from an earlier pass
	 * never reappears in {@code pPlan.toDecide} either (only {@code PROPOSED} rows do - {@code
	 * WizardRules#bulkEligibility}), which is exactly the PersVol2 case: mappings 414-664 were already
	 * {@code APPLIED} while their {@code STRIP_CHARS}/{@code TRANSCODE_REFERENCE} {@code MappingStep} rows
	 * (added by a later capability/quality pass) stayed {@code PROPOSED} forever, never applied at load. This
	 * sweep runs independently of the plan, over every mapping cell of the given feeds (skipping a flagged
	 * attribute's cells entirely, same rule {@code MatrixCompute#planEntityBulk} applies to its own guards),
	 * and decides every {@code MappingStep} at or above the SAME bulk threshold, whether its parent mapping
	 * was already accepted-like or was just accepted by this very click. Adds its own ok/skipped counts into
	 * {@code pOutCounts} ({@code [0]=ok}, {@code [1]=skipped}) rather than returning a new object, so the
	 * caller can fold them into the SAME notice {@link #applyPlan} already reports.
	 */
	private void sweepMappingSteps(final MatrixModel.Entity pEntity, final Iterable<MatrixModel.Feed> pFeeds, final int[] pOutCounts)
	{
		for (final MatrixModel.Feed feed : pFeeds)
		{
			for (final java.util.Map.Entry<String, MatrixModel.Cell> entry : feed.cellsByAttributeId.entrySet())
			{
				final MatrixModel.Cell cell = entry.getValue();
				if (cell.mappingId == null || MatrixCompute.attributeFlagged(pEntity, entry.getKey()))
				{
					continue;
				}
				for (final ReviewLine line : this.asUser(() -> ProposalReviews.mappingStepLinesOf(this.factory, cell.mappingId, this.locale)))
				{
					if (!com.on.ebx.factory.wizard.WizardRules.bulkEligibility(line.status, line.confidence, this.bulkThreshold, false).eligible)
					{
						continue;
					}
					final ProposalReviews.DecisionResult result = ProposalReviews.decide(this.session, this.factory, ReviewLine.MAPPING_STEP, line.rowId, ReviewLine.ACCEPTED);
					pOutCounts[result.ok ? 0 : 1]++;
				}
			}
		}
	}

	/**
	 * Applies every {@link MatrixCompute.PlannedDecision} of {@code pPlan} in order (entity/attribute/feed/
	 * mapping - the plan's own iteration order already reflects the required cascade), through the SAME
	 * {@link ProposalReviews#decide} every other decision on this screen uses, folds in {@code
	 * pMappingStepCounts} ({@link #sweepMappingSteps}'s own {@code [ok, skipped]}, live bug fix 2026-09-25),
	 * and reports ok/skipped/left-out-below-threshold - the same three-way notice {@code
	 * ProjectWizardUserService#bulkStaging} gives, never just a bare accepted count.
	 */
	private void applyPlan(final MatrixCompute.BulkPlan pPlan, final int[] pMappingStepCounts)
	{
		int ok = pMappingStepCounts[0];
		int skipped = pMappingStepCounts[1];
		for (final MatrixCompute.PlannedDecision decision : pPlan.toDecide)
		{
			final ProposalReviews.DecisionResult result = ProposalReviews.decide(this.session, this.factory, decision.nature, decision.id, ReviewLine.ACCEPTED);
			if (result.ok)
			{
				ok++;
			}
			else
			{
				skipped++;
			}
		}
		this.lastApplyOk = ok;
		this.lastApplySkipped = skipped;
		if (pPlan.leftOut > 0)
		{
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.bulkFull", Integer.valueOf(ok), Integer.valueOf(skipped),
				Integer.valueOf(pPlan.leftOut), Integer.valueOf(pPlan.lowestLeftOutConfidence));
		}
		else
		{
			this.message = Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.bulk", Integer.valueOf(ok), Integer.valueOf(skipped));
		}
	}

	private void fail(final String pMessage)
	{
		this.message = pMessage;
		this.messageIsError = true;
	}

	// ----------------------------------------------------------------------------------------------------- display

	private void write(final UserServicePaneWriter pWriter)
	{
		// second permissions pass (2026-10-14): a project hidden from the user (or deleted) since the last request reads like no project
		if (this.projectId != null && ProjectAccess.openProject(this.session, this.factory, this.projectId) == null)
		{
			this.projectId = null;
			this.projectCode = null;
		}
		if (this.projectId == null)
		{
			pWriter.add("<p>").add(Messages.get(MappingMatrixUserService.class, this.locale, "mappingMatrix.notice.noProject")).add("</p>");
			return;
		}
		MatrixModel.Project project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, this.activeEntityId, this.locale));
		if (this.activeEntityId == null || this.activeEntityId.isEmpty() || project.entityById(this.activeEntityId) == null)
		{
			this.activeEntityId = project.entities.isEmpty() ? null : project.entities.get(0).id;
		}
		// matrix review queue lot (2026-09-30): after a single decision, select the next item still to decide - computed
		// from the model just re-read, on the list the page showed before (keys that followed the decided item).
		// setupObjectContext has already re-read the posted (now stale) mdmmSelected, so this is the place to replace it.
		List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(project, this.bulkThreshold);
		if (this.advanceFromKey != null)
		{
			final MatrixCompute.StillItem next = MatrixCompute.nextAfterDecision(items, this.advanceFromKey, this.advanceFollowingKeys);
			this.advanceFromKey = null;
			this.advanceFollowingKeys = null;
			if (next != null)
			{
				this.selectedCellKey = next.key;
				if (!next.entityId.equals(this.activeEntityId))
				{
					// the next item lives in another business object's tab: open it (its samples are read for the open object only)
					this.activeEntityId = next.entityId;
					project = this.asUser(() -> MappingMatrixReader.read(this.factory, this.session, this.projectId, this.activeEntityId, this.locale));
					items = MatrixCompute.stillToDecide(project, this.bulkThreshold);
				}
			}
		}
		// UX lot U6: "Only what's left to do" is the default view once something is decided (and right after a bulk accept), when something is left
		boolean rememberTodo = false;
		if (this.bulkJustDone)
		{
			this.onlyTodo = !items.isEmpty();
			rememberTodo = this.onlyTodo;
		}
		else if (!this.onlyTodoPosted)
		{
			this.onlyTodo = MatrixCompute.defaultOnlyTodo(project, items.size());
		}
		this.bulkJustDone = false;
		this.onlyTodoPosted = true;
		this.renderedQueueKeys = new ArrayList<String>();
		for (final MatrixCompute.StillItem item : items)
		{
			this.renderedQueueKeys.add(item.key);
		}
		final MatrixHtml.State state = new MatrixHtml.State();
		state.queueMode = this.queueMode;
		state.scrollToSelection = this.postback;
		this.postback = false;
		state.locale = this.locale;
		state.activeEntityId = this.activeEntityId;
		state.focusSourceId = this.focusSourceId;
		state.onlyTodo = this.onlyTodo;
		state.selectedCellKey = this.selectedCellKey;
		state.scrollTop = this.scrollTop;
		state.goToWizard = this.goToWizard;
		this.goToWizard = false;
		state.bulkThreshold = this.bulkThreshold;
		state.message = this.message;
		state.messageIsError = this.messageIsError;
		state.readOnly = !this.canEdit();
		// fix/wizard-live-wave1 (2026-09-24, live bug item 2): "Back to the wizard", step 4 (Staging) - the step
		// both this screen and the Feed designer are reached from (WizardPage#renderFeed's own matrix/designer
		// links). this.projectCode (the business code), never this.projectId (an occurrence primary key) - see
		// that field's own Javadoc.
		if (this.projectCode != null && !this.projectCode.isEmpty())
		{
			state.wizardUrl = FactoryLinks.wizardUrl(this.factory.getHome().getKey().format(), this.factory.getAdaptationName().getStringName(), this.projectCode,
				com.on.ebx.factory.wizard.WizardRules.STEP_STAGING);
			// Validation desk lot L1: the fourth view of step 4, in the header's view switch
			state.deskUrl = com.on.ebx.factory.core.StagingDeskLink.url(this.factory.getHome().getKey().format(), this.factory.getAdaptationName().getStringName(), this.projectCode,
				this.activeEntityId);
		}
		pWriter.add(MatrixHtml.render(project, state));
		// fix/matrix-perspective-loss (2026-09-27): the navigation scripts go FIRST. EBX runs every addJS of a page
		// as ONE script, so an exception in an earlier part stops every later part: live on project RET a link
		// without a confidence wrote "confidence":, into the inspector's JSON, the matrix script threw at load,
		// and the perspective rewrite below it was never installed - the Designer switch then left the
		// perspective. Registered before the interaction script, they no longer depend on it.
		// contextual-return lot (2026-09-24): sends Close back to the wizard when this screen was reached from
		// one of its "Mapping matrix"/"Designer" links (ProjectWizardUserService#model, armed by
		// FactoryHtml.originCaptureScript(".mdmf-feed-designer-link, .mdmf-feed-matrix-link")) - same mechanism
		// FeedDesignerUserService#write already uses, reused verbatim (FactoryHtml's own Javadoc: the token
		// lives in sessionStorage, which survives this screen's own in-page postbacks - entity switch, accept -
		// untouched, since none of them navigates away; only a fresh page load ever needs it read back).
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session), state.wizardUrl));
		// the inspector's own "Open this feed in the Designer" link (MatrixHtml#script, class
		// mdmf-matrix-designer-link) is a full-page (target=_top) departure with no return token of its own -
		// only the voluntary-leave marking, so EBX's leave-page guard never fires for it (same reasoning
		// ProjectWizardUserService#write already applies to its own plain Connectors links).
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-matrix-designer-link"));
		// fix/wizard-live-wave1 (2026-09-24, live bug item 2): keeps EVERY /ebx-ui/ link of this screen inside
		// whatever perspective it was opened from - the Matrix|Designer switch, the "Back to the wizard" link
		// below, and the entity inspector's "Open this feed in the Designer" link (added to the DOM by
		// MatrixHtml's own JS well after this page load, hence a click-time rewrite rather than the one-shot
		// querySelectorAll the wizard/explorer use for their own, fully static links - see the script's own
		// Javadoc).
		pWriter.addJS_cr(FactoryHtml.perspectiveLinkRewriteScript());
		// fix/mapping-matrix-live (2026-09-24): the interaction script MUST go through addJS_cr, never a
		// hand-written <script> tag inside the HTML above (CLAUDE.md sec 9 - that is why every click on this
		// screen was dead live) - the exact mechanism FeedDesignerUserService#write already uses for its own
		// (larger) inline script.
		pWriter.addJS_cr(MatrixHtml.script(this.locale));
		// UX lot U6: the person's own choice of "Only what's left to do" is remembered per user and project (wave 13 store, key
		// mdmf:<login>:matrixTodo:<project>, a visual preference, no business data); after a bulk accept the default is written back as ON
		pWriter.addJS_cr(MappingMatrixUserService.todoMemoryScript(FactoryHtml.userId(this.session), this.projectCode, rememberTodo, !items.isEmpty()));
	}

	/**
	 * The script that remembers the "Only what's left to do" toggle: {@code window.mdmmTodoRemember(on)} writes it, and at load a remembered value
	 * is applied (only when something is left - an empty grid would be a blank page) unless a bulk accept has just set the default ON.
	 */
	static String todoMemoryScript(final String pUser, final String pProjectCode, final boolean pRememberOn, final boolean pItemsLeft)
	{
		return FactoryHtml.userStoreLib() + "(function(){var ST=window.mdmfStore.forUser(" + ProposalReviewUserService.jsString(pUser == null ? "" : pUser) + ").local;var k='matrixTodo:'+"
			+ ProposalReviewUserService.jsString(pProjectCode == null ? "" : pProjectCode) + ";"
			+ "window.mdmmTodoRemember=function(on){ST.set(k,on?'1':'0');};"
			+ (pRememberOn ? "ST.set(k,'1');" : "var v=ST.get(k);if(" + pItemsLeft + "&&(v==='1'||v==='0')&&window.mdmmApplyTodo){window.mdmmApplyTodo(v==='1');}")
			+ "})();";
	}

	/** {@code SessionPermissions.getAdaptationAccessPermission(Adaptation).isReadWrite()} on the dataset's own root record - same fail-closed posture as {@code FeedDesignerUserService#canEdit}. Called both to decide what {@link MatrixHtml} renders AND, again, inside {@link #handle} before acting on any decision (class Javadoc). */
	/** A pure read of a shared reader with the user's view ({@code UserView#scoped}): rows and fields the user's EBX permissions show. */
	private <T> T asUser(final java.util.function.Supplier<T> pRead)
	{
		return com.on.ebx.factory.ebx.UserView.scoped(com.on.ebx.factory.ebx.UserView.of(this.session), pRead);
	}

	boolean canEdit()
	{
		if (this.session == null || this.factory == null)
		{
			return false;
		}
		try
		{
			final AccessPermission permission = this.session.getPermissions().getAdaptationAccessPermission(this.factory);
			// second permissions pass (2026-10-14): the dataset AND the project record must be writable for this user (the decisions then run with
			// the user's own session: EBX checks each written row)
			return permission != null && permission.isReadWrite()
				&& com.on.ebx.factory.ebx.UserView.of(this.session).recordWritable(ProjectAccess.openProject(this.session, this.factory, this.projectId));
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}
}
