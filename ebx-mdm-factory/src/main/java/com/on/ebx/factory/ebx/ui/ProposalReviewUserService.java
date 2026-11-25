package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.CapabilityApplyRun;
import com.on.ebx.factory.ebx.MatchingSuggestLlmRun;
import com.on.ebx.factory.ebx.WizardData;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.on.ebx.factory.review.DecisionActions;
import com.on.ebx.factory.review.ReviewData;
import com.on.ebx.factory.review.ReviewFilter;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewPage;
import com.on.ebx.factory.review.ReviewRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
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
 * "Revue des propositions" (PLAN.md screen 3): every proposal of one project, across the five
 * proposal tables, listed as uniform lines with Accept/Reject. Same plain-HTML-posted-back technique
 * as the sibling data-catalog module's {@code TagReviewUserService}: one real (hidden) submit button,
 * everything else a {@code type="button"} whose click sets hidden fields then clicks it - never the
 * AJAX callback endpoint (CLAUDE.md §9). All the actual consistency logic and rendering lives in
 * {@link ReviewRules}/{@link ReviewData}/{@link ReviewPage} (pure) and {@link ProposalReviews}
 * (EBX-bound reading/writing); this class is the thin glue: read the posted parameters, call them,
 * keep the little bit of state a multi-step confirmation needs between one postback and the next.
 * <p>
 * <b>Briefly not {@code final}, 2026-09-22</b>: a now-reverted toolbar lot had a {@code
 * ProposalReviewOnMasterUserService} subclass launch this screen directly from a master table's own toolbar
 * button. the product owner decided the same day (16:40) that Cockpit/Proposal review belong on the factory's own
 * {@code Project} table, not the master tables (`docs/MASTER-ZONE.md` §12) - that subclass was deleted and
 * this class is {@code final} again.
 */
public final class ProposalReviewUserService implements UserService<TableViewEntitySelection>
{
	private static final int DEFAULT_BULK_THRESHOLD = 80;

	Adaptation factory;
	Locale locale = Locale.ENGLISH;

	String projectId;
	String projectCode;
	String projectLabel;
	boolean projectLocked;

	ReviewFilter filter = ReviewFilter.defaultFilter();
	int bulkThreshold = ProposalReviewUserService.DEFAULT_BULK_THRESHOLD;

	String notice;
	String noticeKind = "ok";

	// a pending multi-row decision, offered for confirmation before anything more is written (bulk accept preview / entity-reject cascade offer)
	ReviewPage.Confirmation pendingConfirmation;
	List<ReviewRules.AffectedRow> pendingRows;

	// ------------------------------------------------------------------ review-match-plan lot (2026-09-22): SIMULATE
	// A separate, clearly delimited block (task brief): the Matching line's own "Simulate on N pairs" action, kept
	// apart from every ACCEPT/REJECT/APPLY/REMOVE/MEASURE decision path above. Results are kept HERE, on the
	// service instance (never persisted to EBX - see review.MatchPlanView.SimulationResult's own Javadoc), keyed
	// by MatchPlan rowId, and re-rendered on every subsequent postback until the project changes.
	final java.util.Map<String, com.on.ebx.factory.review.MatchPlanView.SimulationResult> matchSimulationResults =
		new java.util.HashMap<String, com.on.ebx.factory.review.MatchPlanView.SimulationResult>();

	// tree-diagram-labels fix lot (2026-09-22), item C: every Matching line's expanded body now renders
	// collapsed by default (docs/MATCHING.md 16.10) - this is the ONE exception, the rowId the CURRENT postback
	// just acted on (Simulate, Accept, Reject, a comment...), reopened on the resulting repaint so a click
	// inside the panel never reads as "did nothing". Set fresh at the top of every decide() call (see there),
	// read once by write() below - same "instance field survives exactly one repaint" idea AbstractRecordLineage
	// UserService's own pendingSuspectAction/pendingSuspectKey already use, kept here to a single String since
	// only one row can ever be the target of one postback.
	String lastActedMatchRowId;

	/** Second permissions pass (2026-10-14): the session of the current request and the table the screen was opened on. */
	private Session session;
	private AdaptationTable openedOn;

	/** A pure read of a shared reader with the user's view ({@code UserView#scoped}): rows and fields the user's EBX permissions show. */
	private <T> T asUser(final java.util.function.Supplier<T> pRead)
	{
		return com.on.ebx.factory.ebx.UserView.scoped(com.on.ebx.factory.ebx.UserView.of(this.session), pRead);
	}

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		final TableViewEntitySelection selection = pContext.getEntitySelection();
		this.factory = selection.getDataset();
		this.session = pContext.getSession();
		this.openedOn = selection.getTable();
		if (this.projectId == null && !this.projectLocked)
		{
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
				// no selection context (a plain perspective launch): fall through to the project selector
			}
			if (selected.size() == 1)
			{
				final Adaptation project = this.asUser(() -> ProposalReviews.projectOfRecord(this.factory, table, selected.get(0)));
				if (project != null)
				{
					this.projectId = project.getOccurrencePrimaryKey().format();
					this.projectCode = project.getOccurrencePrimaryKey().format();
					this.projectLabel = this.asUser(() -> ProposalReviewUserService.labelOf(project));
					this.projectLocked = true;
					// same default-status fallback as selectProject (task B) - a launch locked onto a project
					// via a toolbar action must not land on an empty screen either
					final List<ReviewLine> lines = this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale));
					final String defaultStatus = ReviewData.defaultStatusFor(lines, this.filter.nature, this.filter.origin);
					this.filter = new ReviewFilter(this.filter.nature, this.filter.origin, defaultStatus, this.filter.minConfidence);
				}
			}
		}
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(ProposalReviewUserService.class, this.locale, "review.label"));
		// second permissions pass (2026-10-14): dataspace, dataset, Project table and this service's own permission, read with the user's session
		String closed = ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (closed == null && !ProjectAccess.serviceOnTable(this.session, ProposalReviewServiceDeclaration.SERVICE_KEY, this.openedOn))
		{
			closed = Messages.get(ProposalReviewUserService.class, this.locale, "screen.service.refused");
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
		// audit F-41: an unplanned failure of a decision or of the rendering becomes a localized notice with a logged reference
		final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(ProposalReviewUserService.class, this.locale, "review.submit"),
			ScreenFailure.guard("proposal review", "decision", this.locale, new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					ProposalReviewUserService.this.decide(pEventContext);
					return null;
				}
			}, pNotice -> {
				this.notice = pNotice;
				this.noticeKind = "warn";
			}));
		submit.setId("mdmfSubmit");
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setLeftButtons(submit);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(ScreenFailure.guard("proposal review", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ProposalReviewUserService.this.write(pWriter, pPaneContext.getSession());
			}
		}));
	}

	// ------------------------------------------------------------------ the decision (form posted back)

	/**
	 * Security review (2026-09-24): deciding (accept/reject) stays open to whoever may open this screen and write the
	 * factory's rows (EBX permissions apply to those writes); what writes into data models or matching configurations,
	 * or sends project data to the language model, is for administrators only - checked here, server-side, not only
	 * hidden in the page. For a non-administrator the action is skipped with a notice; a decision already recorded
	 * stays recorded.
	 */
	private boolean refuseUnlessAdmin(final UserServiceEventContext pContext)
	{
		// audit F-02: the DESIGN right (was: the EBX administrator) - applying, measuring, re-proposing, removing, the auto-merge toggle
		if (com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(), com.on.ebx.factory.core.FactoryRight.DESIGN))
		{
			return false;
		}
		this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.adminOnly"));
		return true;
	}

	void decide(final UserServiceEventContext pContext)
	{
		final String action = ProposalReviewUserService.param(pContext, "mdmfAction");
		this.filter = new ReviewFilter(ProposalReviewUserService.param(pContext, "mdmfFilterNature"), ProposalReviewUserService.param(pContext, "mdmfFilterOrigin"),
			ProposalReviewUserService.param(pContext, "mdmfFilterStatus"), ProposalReviewUserService.paramInt(pContext, "mdmfFilterMinConfidence", 0),
			ProposalReviewUserService.param(pContext, "mdmfFilterReference"));
		this.bulkThreshold = ProposalReviewUserService.paramInt(pContext, "mdmfBulkThreshold", ProposalReviewUserService.DEFAULT_BULK_THRESHOLD);
		// tree-diagram-labels fix lot (2026-09-22), item C: reset on every decide() call, THEN re-set only when
		// this exact postback targeted a MATCH row (whatever the specific action - the row buttons rendered on
		// the collapsed line itself set nature/id exactly the same way the Simulate button inside the panel
		// does) - a completely unrelated action (a filter change, a different row) correctly clears it, so the
		// re-opened row never "sticks" past the one repaint that follows.
		this.lastActedMatchRowId = ReviewLine.MATCH.equals(ProposalReviewUserService.param(pContext, "mdmfNature"))
			? ProposalReviewUserService.param(pContext, "mdmfId") : null;

		// audit F-02: each action asks its own right (core.FactoryRights.reviewAction: VIEW to look, STEWARD to decide, DESIGN to apply);
		// the permission rule only hides the screen, a forged postback is refused here
		final com.on.ebx.factory.core.FactoryRight needed = com.on.ebx.factory.core.FactoryRights.reviewAction(action);
		if (!com.on.ebx.factory.ebx.FactoryAccess.has(pContext.getSession(), needed))
		{
			this.warn(needed == com.on.ebx.factory.core.FactoryRight.ADMINISTRATOR ? Messages.get(ProposalReviewUserService.class, this.locale, "access.denied.admin")
				: Messages.get(ProposalReviewUserService.class, this.locale, "access.denied", needed.name(), com.on.ebx.factory.ebx.FactoryAccess.roleFor(needed)));
			return;
		}
		// second permissions pass (2026-10-14): the project is re-read with the user's view on every postback (hidden since = gone), and an action
		// that decides, applies or re-proposes needs read-write access on the project record; the decisions then run with the user's own session
		// (EBX checks each written row)
		this.session = pContext.getSession();
		if (this.projectId != null && ProjectAccess.openProject(this.session, this.factory, this.projectId) == null)
		{
			this.projectId = null;
			this.projectCode = null;
			this.projectLabel = null;
			this.projectLocked = false;
			this.clearPending();
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "screen.project.unknown"));
			return;
		}
		if (this.projectId != null && !com.on.ebx.factory.core.RecordGate.readsOnly(needed)
			&& !com.on.ebx.factory.ebx.UserView.of(this.session).recordWritable(ProjectAccess.openProject(this.session, this.factory, this.projectId)))
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "screen.readOnly"));
			return;
		}
		if ("SELECT_PROJECT".equals(action))
		{
			this.selectProject(ProposalReviewUserService.param(pContext, "mdmfProjectCode"));
			return;
		}
		if ("CHANGE_PROJECT".equals(action))
		{
			this.projectId = null;
			this.projectCode = null;
			this.projectLabel = null;
			this.projectLocked = false;
			this.clearPending();
			return;
		}
		if ("FILTER".equals(action))
		{
			// already applied above; nothing else to do
			return;
		}
		if ("DISMISS".equals(action))
		{
			this.clearPending();
			return;
		}
		if (this.projectId == null)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.projectRequired"));
			return;
		}
		if ("ACCEPT".equals(action) || "REJECT".equals(action))
		{
			this.decideOne(pContext, action);
			return;
		}
		if ("REPROPOSE".equals(action))
		{
			this.reproposeOne(pContext);
			return;
		}
		if ("REMOVE".equals(action))
		{
			this.removeOne(pContext);
			return;
		}
		if ("MEASURE".equals(action))
		{
			this.measureOne(pContext);
			return;
		}
		if ("SET_AUTO_MERGE".equals(action))
		{
			// automerge-toggle lot (2026-09-23), task 1: a separate, clearly delimited action - never touches
			// status/requirements/reviewComment (ProposalReviews.setMatchPlanAutoMerge's own Javadoc). PAGE_JS's
			// dedicated .mdmf-auto-merge-toggle change listener sets mdmfNature=MATCH/mdmfId before this fires, so
			// lastActedMatchRowId above already reopens this row on the repaint, same as every other MATCH action.
			this.setAutoMerge(pContext);
			return;
		}
		if ("SIMULATE".equals(action))
		{
			// review-match-plan lot (2026-09-22): a separate, clearly delimited action - see the field block
			// above and simulateOne's own Javadoc. Reuses the existing mdmfNature/mdmfId hidden fields (the
			// button sets nature=MATCH, id=the MatchPlan row id) rather than a new hidden field: PAGE_JS's
			// existing generic click handler already copies any data-mdmf-nature/-id off the clicked element,
			// so no PAGE_JS change was needed for this action.
			this.simulateOne(pContext);
			return;
		}
		if ("APPLY".equals(action) || "REAPPLY".equals(action))
		{
			// Re-apply lot (2026-09-21): the EXACT same call as a plain Apply - re-applying an already-APPLIED
			// row is not skipped (docs/CAPABILITY-APPLY.md section 2, "same name replaces in place") - reused,
			// never forked, per the task brief's own instruction.
			this.applyOneDirectly(pContext);
			return;
		}
		if ("BULK_ACCEPT_PREVIEW".equals(action))
		{
			this.previewBulkAccept(pContext);
			return;
		}
		if ("BULK_ACCEPT_CONFIRM".equals(action))
		{
			this.confirmBulk(pContext, ReviewLine.ACCEPTED, "review.bulk.done");
			return;
		}
		if ("CASCADE_CONFIRM".equals(action))
		{
			this.confirmBulk(pContext, ReviewLine.REJECTED, "review.cascade.done");
			return;
		}
		// fix-apply-ux (2026-09-21), item 3: the selection bar's own preview/confirm actions - see
		// previewSelected's own Javadoc for why SELECT_ACCEPT_PREVIEW reuses BULK_ACCEPT_CONFIRM as its
		// confirm action instead of yet another one-off method.
		if ("SELECT_ACCEPT_PREVIEW".equals(action))
		{
			this.previewSelected(pContext, ProposalReviewUserService.SelectionKind.ACCEPT);
			return;
		}
		if ("SELECT_REJECT_PREVIEW".equals(action))
		{
			this.previewSelected(pContext, ProposalReviewUserService.SelectionKind.REJECT);
			return;
		}
		if ("SELECT_APPLY_PREVIEW".equals(action))
		{
			this.previewSelected(pContext, ProposalReviewUserService.SelectionKind.APPLY);
			return;
		}
		if ("SELECT_REJECT_CONFIRM".equals(action))
		{
			this.confirmBulk(pContext, ReviewLine.REJECTED, "review.selection.reject.done");
			return;
		}
		if ("SELECT_APPLY_CONFIRM".equals(action))
		{
			this.confirmApplySelected(pContext);
			return;
		}
	}

	void selectProject(final String pCode)
	{
		this.clearPending();
		final Adaptation project = this.asUser(() -> ProposalReviews.findProjectByCode(this.factory, pCode));
		if (project == null)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.project.none"));
			return;
		}
		this.projectId = project.getOccurrencePrimaryKey().format();
		this.projectCode = project.getOccurrencePrimaryKey().format();
		this.projectLabel = this.asUser(() -> ProposalReviewUserService.labelOf(project));
		this.notice = null;
		// task B, product report 2026-09-21: PROPOSED stays the default status only when something is
		// actually pending for the chosen nature/origin - otherwise fall back to ALL, so a fresh project
		// selection never lands on a screen that reads as broken (ReviewData.defaultStatusFor, pure, tested)
		final List<ReviewLine> lines = this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale));
		final String defaultStatus = ReviewData.defaultStatusFor(lines, this.filter.nature, this.filter.origin);
		this.filter = new ReviewFilter(this.filter.nature, this.filter.origin, defaultStatus, this.filter.minConfidence);
	}

	void decideOne(final UserServiceEventContext pContext, final String pAction)
	{
		final String nature = ProposalReviewUserService.param(pContext, "mdmfNature");
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
		final String targetStatus = "ACCEPT".equals(pAction) ? ReviewLine.ACCEPTED : ReviewLine.REJECTED;
		// reject/repropose lot (2026-09-22): mdmfComment only ever means something for a MATCH "No matching for
		// this entity" - ProposalReviews.decide itself only writes it for exactly that nature/target combination,
		// so passing it unconditionally here is harmless for every other line.
		final String comment = ProposalReviewUserService.param(pContext, "mdmfComment");
		final ProposalReviews.DecisionResult result = ProposalReviews.decide(pContext.getSession(), this.factory, nature, id, targetStatus, comment);
		if (!result.ok)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.refused", result.reasonIn(this.locale)));
			return;
		}
		this.notice = Messages.get(ProposalReviewUserService.class, this.locale, ReviewLine.ACCEPTED.equals(targetStatus) ? "review.notice.accepted" : "review.notice.rejected");
		this.noticeKind = "ok";
		// apply lot (2026-09-21): accepting a SCRIPT_CONSTRAINT applies it directly - the product owner's own
		// decision, "he will not read script code": no separate button, the accept click IS the trigger. An
		// apply failure never undoes the accept decision itself (ProposalReviews.decide already committed it) -
		// ebx.CapabilityApplier leaves the row ACCEPTED with its own applyMessage, surfaced here as a warning.
		// script-backed-derivation lot (2026-09-23): SCRIPT_DERIVATION follows the exact same rule - see
		// ebx.DerivationApplier, dispatched from the SAME applyOne (which itself picks the right applier by nature).
		if ((ReviewLine.SCRIPT_CONSTRAINT.equals(nature) || ReviewLine.SCRIPT_DERIVATION.equals(nature)) && ReviewLine.ACCEPTED.equals(targetStatus))
		{
			this.applyOne(pContext, id);
		}
		final String entityLabel = result.elementLabel == null ? id : result.elementLabel;
		if (result.cascade != null && !result.cascade.isEmpty())
		{
			this.pendingRows = result.cascade;
			this.pendingConfirmation = new ReviewPage.Confirmation(
				Messages.get(ProposalReviewUserService.class, this.locale, "review.cascade.message", entityLabel, Integer.valueOf(result.cascade.size())), "CASCADE_CONFIRM",
				Messages.get(ProposalReviewUserService.class, this.locale, "review.cascade.confirm", Integer.valueOf(result.cascade.size())), result.cascade);
		}
		else if (ReviewLine.ENTITY.equals(nature) && ReviewLine.REJECTED.equals(targetStatus))
		{
			this.notice = Messages.get(ProposalReviewUserService.class, this.locale, "review.cascade.none", entityLabel);
		}
	}

	/**
	 * Reject/repropose lot (2026-09-22): "Propose another" on a {@code MATCH} line - {@code
	 * DecisionActions.Actions#repropose} is the only line this action is ever offered on. Two steps, per the
	 * coordinator's own instruction (2026-09-22 13:20, "never a hole: the previous plan stays acceptable at
	 * every moment"): (1) {@link ProposalReviews#startMatchRepropose} marks the row {@code SUPERSEDED},
	 * keeping the old plan and storing the comment; (2) {@code ebx.MatchPlanLlmSuggester.suggest}, scoped to
	 * this one entity. If that pass did not actually write/refresh/repeat a plan for the entity - it skipped
	 * it, or threw before reaching it - the row is reverted to {@code PROPOSED} with a plain-words note
	 * ({@link ProposalReviews#revertMatchPlanToProposed}) rather than left stuck at {@code SUPERSEDED}.
	 */
	void reproposeOne(final UserServiceEventContext pContext)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
		final String comment = ProposalReviewUserService.param(pContext, "mdmfComment");
		final ProposalReviews.ReproposeStart start = ProposalReviews.startMatchRepropose(pContext.getSession(), this.factory, id, comment);
		if (!start.ok)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.refused", start.reason));
			return;
		}
		// audit F-34 (wave 10): the language model is asked by a queued job (MatchingSuggestLlmRun.startRepropose), as the wizard's own "Propose another" does - it
		// puts the plan back to PROPOSED with the reason when the model writes nothing new for the entity, exactly what this method did inline
		try
		{
			MatchingSuggestLlmRun.startRepropose(pContext.getSession(), this.factory, this.projectCode, start.entityCode, id);
			this.notice = Messages.get(ProposalReviewUserService.class, this.locale, "review.match.repropose.queued", start.entityCode);
			this.noticeKind = "ok";
		}
		catch (final OperationException ex)
		{
			ProposalReviews.revertMatchPlanToProposed(pContext.getSession(), this.factory, id, com.on.ebx.factory.landing.ErrorText.of(ex));
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.match.repropose.failed", EngineNotice.inline(ex, this.locale)));
		}
	}

	/**
	 * fix-apply-ux (2026-09-21): the SAME apply path whether triggered by accepting a still-PROPOSED
	 * SCRIPT_CONSTRAINT line ({@link #decideOne}) or by the new stand-alone Apply action on an ACCEPTED,
	 * not-yet-APPLIED line ({@link #applyOneDirectly}) - one method, one notice-setting contract, so the two
	 * entry points can never drift.
	 */
	private void applyOne(final UserServiceEventContext pContext, final String pId)
	{
		this.applyOne(pContext, pId, ProposalReviewUserService.param(pContext, "mdmfNature"));
	}

	/**
	 * script-backed-derivation lot (2026-09-23): a SCRIPT_DERIVATION row goes through {@code
	 * ebx.DerivationApplier} instead of {@code ebx.CapabilityApplier} - same contract either way (throws
	 * {@code OperationException} on failure, leaves the row ACCEPTED with its own {@code applyMessage}).
	 * {@code pNature} is read from the SAME posted {@code mdmfNature} parameter every action button already
	 * carries ({@code review.ReviewPage}'s own {@code data-mdmf-nature} attribute) - never a fresh EBX lookup,
	 * the caller already knows it (from the accept form, or from the button's own data attribute).
	 */
	private void applyOne(final UserServiceEventContext pContext, final String pId, final String pNature)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		// audit F-34 (wave 10): queued (CapabilityApplyRun, the job the wizard queues for the same click) instead of run in this request - an LLM-drafted
		// derivation, a model publication and a measure over the table are not work for a postback. CapabilityDispatcher still picks the applier from the
		// row's own nature inside the job (lot M5), so the posted nature stays unused here
		this.queueCapability(pContext.getSession(), CapabilityApplyRun.applyKindFor(WizardData.capabilityZone(this.factory, pId)), pId, "review.notice.applyFailed",
			"review.notice.applyQueued");
	}

	/**
	 * Audit F-34 (wave 10): queues one capability job (apply, remove or measure of a suggestion, the kind chosen by the caller from the row's zone, read server-side) and
	 * says so; an unknown zone or a refused queue is the warning {@code pFailureKey}. The notice names Recent tasks: this screen has no banner of its own,
	 * the end-of-task notice ({@code jobnotice.js}) tells the person when the task ends.
	 */
	private void queueCapability(final Session pSession, final String pKind, final String pId, final String pFailureKey, final String pQueuedKey)
	{
		if (pKind == null)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, pFailureKey, "unknown zone"));
			return;
		}
		try
		{
			CapabilityApplyRun.start(pSession, this.factory, this.projectId, pKind, this.projectCode, pId);
			this.notice = Messages.get(ProposalReviewUserService.class, this.locale, pQueuedKey);
			this.noticeKind = "ok";
		}
		catch (final OperationException ex)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, pFailureKey, EngineNotice.inline(ex, this.locale)));
		}
	}

	/** The stand-alone Apply action (fix-apply-ux, 2026-09-21): an ACCEPTED, not-yet-APPLIED SCRIPT_CONSTRAINT line offers this directly - see {@link com.on.ebx.factory.review.DecisionActions#forStatus(String, String)}. */
	void applyOneDirectly(final UserServiceEventContext pContext)
	{
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
		this.applyOne(pContext, id);
	}

	/**
	 * Apply lot (2026-09-21): takes an APPLIED SCRIPT_CONSTRAINT back off the model - {@code
	 * ebx.CapabilityApplier.remove}, never a plain status write (the declaration must actually come off the
	 * published model first). script-backed-derivation lot (2026-09-23): a SCRIPT_DERIVATION row goes through
	 * {@code ebx.DerivationApplier.remove} instead - same dispatch-by-posted-nature as {@link #applyOne}.
	 */
	void removeOne(final UserServiceEventContext pContext)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
		// audit F-34 (wave 10): removing republishes the model - queued (kind from the row's own zone), like the wizard's Remove
		this.queueCapability(pContext.getSession(), CapabilityApplyRun.removeKindFor(WizardData.capabilityZone(this.factory, id)), id, "review.notice.removeFailed",
			"review.notice.removeQueued");
	}

	/** Coordinator review, apply lot (2026-09-21): re-measures an APPLIED SCRIPT_CONSTRAINT on demand, whatever its table's size - {@code ebx.CapabilityApplier.measure} never rolls anything back and never touches {@code status}. */
	void measureOne(final UserServiceEventContext pContext)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
		// audit F-34 (wave 10): the measure walks the whole table's validation report - queued, sharing its zone's resource with that zone's build
		this.queueCapability(pContext.getSession(), CapabilityApplyRun.measureKindFor(WizardData.capabilityZone(this.factory, id)), id, "review.notice.measureFailed",
			"review.notice.measureQueued");
	}

	/**
	 * Automerge-toggle lot (2026-09-23), task 1: the "Merge confirmed matches automatically" checkbox on a
	 * MATCH line's expanded body (only ever posted for a plan {@link review.MatchPlanView#renderAutoMergeControl}
	 * actually rendered as editable - PROPOSED/ACCEPTED - but re-checked server-side regardless, see {@link
	 * ProposalReviews#setMatchPlanAutoMerge}'s own Javadoc). {@code mdmfId} is the {@code MatchPlan} row id
	 * (same field the Simulate button reuses); {@code mdmfAutoMergeValue} is {@code "true"}/{@code "false"},
	 * set by PAGE_JS from the checkbox's own current (post-click) checked state.
	 */
	void setAutoMerge(final UserServiceEventContext pContext)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
		final boolean value = "true".equals(ProposalReviewUserService.param(pContext, "mdmfAutoMergeValue"));
		final ProposalReviews.AutoMergeResult result = ProposalReviews.setMatchPlanAutoMerge(pContext.getSession(), this.factory, id, value);
		if (!result.ok)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.refused", result.reason));
			return;
		}
		this.notice = Messages.get(ProposalReviewUserService.class, this.locale, value ? "review.match.autoMerge.notice.on" : "review.match.autoMerge.notice.off");
		this.noticeKind = "ok";
	}

	/**
	 * review-match-plan lot (2026-09-22), block 3 of the Matching line's expanded body: "Simulate on N
	 * pairs" - a separate, clearly delimited action (never touches a decision/status). {@code mdmfId} is the
	 * {@code MatchPlan} row id (the button sets {@code data-mdmf-nature="MATCH"}); the line is re-read fresh
	 * from {@link ProposalReviews#lines} (never trusted from a stale render) so a plan someone else just
	 * accepted/applied since the page loaded is judged on its CURRENT status, the same "never trusts what was
	 * true a moment ago" discipline every other action in this class already follows. Writes nothing on ITS
	 * OWN account - {@link com.on.ebx.factory.ebx.MatchPlanSimulationRunner#simulate} never touches the
	 * {@code MatchPlan}'s own status/decision fields - but, simulation-preview-policy lot (2026-09-22), it may
	 * now write/rewrite an inactive {@code <Entity>__preview} matching policy on the add-on when the plan is
	 * not yet {@code APPLIED} (see {@code ebx.MatchingPreviewPolicy}) so the add-on has something to actually
	 * simulate against. The result is kept on {@link #matchSimulationResults} (this service instance), never
	 * persisted.
	 * <p>
	 * <b>No longer requires {@code APPLIED}</b> (task brief, 2026-09-22: "Oui, c'est bien" - the product owner's own
	 * decision on the defect where Simulate on a PROPOSED plan used to answer a flat refusal). Only a plan
	 * that will NEVER be matched at all - {@code REJECTED}, or none proposed yet - still refuses; {@code
	 * PROPOSED}/{@code ACCEPTED}/{@code APPLIED} all simulate, on the preview policy for the first two and on
	 * the real one for the last (see {@link com.on.ebx.factory.review.MatchPlanView.SimulationResult#onPreviewPolicy}).
	 */
	void simulateOne(final UserServiceEventContext pContext)
	{
		final String id = ProposalReviewUserService.param(pContext, "mdmfId");
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
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.rowNotFound"));
			return;
		}
		if (!ProposalReviewUserService.canSimulateMatchPlan(matchLine.status))
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.match.simulate.notApplied"));
			return;
		}
		try
		{
			final com.on.ebx.factory.review.MatchPlanView.SimulationResult result = com.on.ebx.factory.ebx.MatchPlanSimulationRunner.simulate(pContext.getSession(),
				this.factory, this.projectCode, this.projectId, matchLine.element, matchLine.matchRequirementsJson, matchLine.rowId);
			this.matchSimulationResults.put(id, result);
			this.notice = Messages.get(ProposalReviewUserService.class, this.locale, "review.match.simulate.done", Integer.valueOf(result.pivotCount),
				Integer.valueOf(result.pairs.size()));
			this.noticeKind = "ok";
		}
		catch (final OperationException ex)
		{
			// self-heal-simulate-activation lot (2026-09-22): a refusal from ebx.MatchingActivationGuard (the
			// table could not be made runnable before simulating - CLAUDE.md sec 8 boot-race, or the table was
			// never activated at all) gets its OWN, more specific wording instead of the generic "Simulation
			// failed: ..." every other OperationException from this method still uses - see that class' own
			// class Javadoc for exactly which refusals carry the marker this checks.
			final String rawMessage = ex.getMessage();
			final String failureLine;
			if (com.on.ebx.factory.ebx.MatchingActivationGuard.isActivationRefusal(rawMessage))
			{
				failureLine = Messages.get(ProposalReviewUserService.class, this.locale, "review.match.simulate.activation.refused",
					com.on.ebx.factory.ebx.MatchingActivationGuard.stripMarker(rawMessage));
			}
			else
			{
				failureLine = Messages.get(ProposalReviewUserService.class, this.locale, "review.match.simulate.failed", EngineNotice.inline(ex, this.locale));
			}
			this.warn(failureLine);
			// lot V2-B (2026-09-30): also shown as one line under the Simulate button (MatchPlanView), not only in the notice at the top
			this.matchSimulationResults.put(id, com.on.ebx.factory.review.MatchPlanView.SimulationResult.failed(failureLine));
		}
	}

	/**
	 * simulation-preview-policy lot (2026-09-22): a {@code MatchPlan} can be simulated as soon as one exists
	 * and the reviewer has not explicitly ruled it out - {@code PROPOSED}/{@code ACCEPTED}/{@code APPLIED} all
	 * qualify (the first two on a preview policy, {@code ebx.MatchingPreviewPolicy}; the last on the real
	 * one), only {@code REJECTED} (or no plan/status at all) does not. Shared by {@link #simulateOne}'s own
	 * guard and the {@code matchCanSimulate} map built for {@link com.on.ebx.factory.review.ReviewPage.Input}
	 * so the button's enabled state and the action it triggers never disagree.
	 */
	static boolean canSimulateMatchPlan(final String pStatus)
	{
		return ReviewLine.PROPOSED.equals(pStatus) || ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus);
	}

	void previewBulkAccept(final UserServiceEventContext pContext)
	{
		final List<ReviewLine> all = this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale));
		final List<ReviewLine> visible = ReviewData.filter(all, this.filter);
		// lot M7 (2026-10-18): an entity no source column feeds is never swept in (whatever its confidence), and the preview says which
		final java.util.Set<String> noFeed = ReviewData.entitiesWithoutFeed(all);
		final List<ReviewLine> candidates = ReviewData.bulkAcceptCandidates(visible, this.bulkThreshold, noFeed);
		final java.util.List<String> skippedNoFeed = new ArrayList<String>();
		for (final ReviewLine line : visible)
		{
			if (ReviewLine.ENTITY.equals(line.nature) && noFeed.contains(line.element) && (line.confidence == null ? 0 : line.confidence.intValue()) >= this.bulkThreshold)
			{
				skippedNoFeed.add(line.element);
			}
		}
		final String noFeedNotice = skippedNoFeed.isEmpty() ? ""
			: " " + Messages.get(ProposalReviewUserService.class, this.locale, skippedNoFeed.size() == 1 ? "review.bulk.noFeedSkipped.one" : "review.bulk.noFeedSkipped.many",
				String.join(", ", skippedNoFeed));
		if (candidates.isEmpty())
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.none") + noFeedNotice);
			return;
		}
		final List<ReviewRules.AffectedRow> rows = new ArrayList<ReviewRules.AffectedRow>();
		for (final ReviewLine line : candidates)
		{
			rows.add(new ReviewRules.AffectedRow(line.nature, line.rowId, line.element, line.status));
		}
		this.pendingRows = rows;
		String message = Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.confirm.message", Integer.valueOf(rows.size()),
			Integer.valueOf(this.bulkThreshold));
		// coordinator review, apply lot (2026-09-21): "the bulk preview text must say how many constraints will be
		// put on which model and that ONE publication per model follows" - a publish takes tens of seconds to
		// minutes, so this is the one thing a reviewer must know before confirming a bulk accept that includes any
		message = message + ProposalReviewUserService.capabilityBulkSummary(this.factory, this.projectCode, rows, this.locale) + noFeedNotice;
		this.pendingConfirmation = new ReviewPage.Confirmation(message, "BULK_ACCEPT_CONFIRM",
			Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.confirm.button"), rows);
	}

	/** " N script constraint(s) will be applied: M to <model>, K to <model> - one publication per model follows." - empty when the candidates include no SCRIPT_CONSTRAINT line at all. */
	static String capabilityBulkSummary(final Adaptation pFactory, final String pProjectCode, final List<ReviewRules.AffectedRow> pCandidates, final Locale pLocale)
	{
		final java.util.Map<String, Integer> counts = ProposalReviews.capabilityModelCounts(pFactory, pProjectCode, pCandidates);
		if (counts.isEmpty())
		{
			return "";
		}
		int total = 0;
		final StringBuilder perModel = new StringBuilder();
		for (final java.util.Map.Entry<String, Integer> entry : counts.entrySet())
		{
			total += entry.getValue().intValue();
			if (perModel.length() > 0)
			{
				perModel.append(", ");
			}
			perModel.append(entry.getValue()).append(' ').append(Messages.get(ProposalReviewUserService.class, pLocale, "review.bulk.capability.to")).append(' ')
				.append(entry.getKey());
		}
		return " " + Messages.get(ProposalReviewUserService.class, pLocale, "review.bulk.capability.summary", Integer.valueOf(total), perModel.toString());
	}

	/** fix-apply-ux (2026-09-21), item 3: which of the selection bar's three buttons was clicked - which {@link com.on.ebx.factory.review.DecisionActions.Actions} flag a line must offer to be included in the preview. */
	private enum SelectionKind
	{
		ACCEPT, REJECT, APPLY
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: resolves {@code mdmfSelectedIds} ({@code "nature|id"} pairs,
	 * comma-joined by PAGE_JS from the checked {@code .mdmf-row-select} boxes) back to real, FRESH
	 * {@link ReviewLine}s - re-read from {@link ProposalReviews#lines}, never trusted from whatever the
	 * checkbox's own data attributes said at render time, exactly the same "never trust the preview" rule
	 * {@link #confirmBulk} already applies to a bulk accept.
	 */
	private List<ReviewLine> selectedLines(final UserServiceEventContext pContext)
	{
		final String raw = ProposalReviewUserService.param(pContext, "mdmfSelectedIds");
		if (raw.isEmpty())
		{
			return new ArrayList<ReviewLine>();
		}
		final java.util.Set<String> keys = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(raw.split(",")));
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		for (final ReviewLine line : this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale)))
		{
			if (keys.contains(line.nature + "|" + line.rowId))
			{
				out.add(line);
			}
		}
		return out;
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: the selection bar's own preview step - "confirmation/preview states
	 * what will happen before executing" (the coordinator's own instruction). Every selected line is
	 * re-validated against the SAME {@link com.on.ebx.factory.review.DecisionActions} a single-row button
	 * would have used (never the checkbox's own, possibly stale, {@code data-mdmf-can-*} attributes) - a
	 * selection that mixes eligible and ineligible lines (e.g. one already APPLIED, changed by someone else
	 * since the page loaded) silently narrows to the ones still eligible, exactly the defensive re-check
	 * {@link #confirmBulk} already applies to a bulk accept.
	 * <p>
	 * {@link SelectionKind#ACCEPT} reuses {@code BULK_ACCEPT_CONFIRM} as its confirm action - the exact same
	 * {@link #confirmBulk} call a threshold-based bulk accept already uses, so "Accept selected" behaves
	 * IDENTICALLY to "Accept everything shown" once confirmed, including applying every {@code
	 * SCRIPT_CONSTRAINT} row the confirm just moved to {@code ACCEPTED} (fix-bulk-accept-applies,
	 * 2026-09-21 - see {@link #confirmBulk}'s own Javadoc).
	 */
	private void previewSelected(final UserServiceEventContext pContext, final ProposalReviewUserService.SelectionKind pKind)
	{
		final List<ReviewLine> selected = this.selectedLines(pContext);
		final List<ReviewRules.AffectedRow> rows = new ArrayList<ReviewRules.AffectedRow>();
		for (final ReviewLine line : selected)
		{
			if (line.primaryKey)
			{
				continue;
			}
			final DecisionActions.Actions actions = DecisionActions.forStatus(line.status, line.nature);
			// reject/repropose lot (2026-09-22): a reject that needs the explicit "No matching for this entity"
			// confirmation is never bulk-selectable - the selection bar has no confirmation dialog of its own, and
			// the coordinator was explicit that this one action "genuinely has a product consequence" (DecisionActions'
			// own Javadoc); only its single-row button, with the inline confirmation, may trigger it.
			final boolean eligible = pKind == ProposalReviewUserService.SelectionKind.ACCEPT ? actions.accept
				: pKind == ProposalReviewUserService.SelectionKind.REJECT ? actions.reject && !actions.confirmReject : actions.apply;
			if (eligible)
			{
				rows.add(new ReviewRules.AffectedRow(line.nature, line.rowId, line.element, line.status));
			}
		}
		if (rows.isEmpty())
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.none"));
			return;
		}
		this.pendingRows = rows;
		final String confirmAction;
		String message;
		switch (pKind)
		{
			case ACCEPT:
				confirmAction = "BULK_ACCEPT_CONFIRM";
				message = Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.accept.confirm.message", Integer.valueOf(rows.size()));
				message = message + ProposalReviewUserService.capabilityBulkSummary(this.factory, this.projectCode, rows, this.locale);
				break;
			case APPLY:
				confirmAction = "SELECT_APPLY_CONFIRM";
				message = Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.apply.confirm.message", Integer.valueOf(rows.size()));
				message = message + ProposalReviewUserService.capabilityBulkSummary(this.factory, this.projectCode, rows, this.locale);
				break;
			case REJECT:
			default:
				confirmAction = "SELECT_REJECT_CONFIRM";
				message = Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.reject.confirm.message", Integer.valueOf(rows.size()));
				break;
		}
		this.pendingConfirmation = new ReviewPage.Confirmation(message, confirmAction, Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.confirm.button"),
			rows);
	}

	/**
	 * fix-apply-ux (2026-09-21), item 3: "Apply selected", confirmed - goes straight through
	 * {@code ebx.CapabilityApplier.applyAll} with the confirmed ids, exactly the batching (one publish per
	 * model) the coordinator's own instruction asked for. Unlike {@link #confirmBulk} (which applies only
	 * the {@code SCRIPT_CONSTRAINT} rows IT just moved to {@code ACCEPTED}, fix-bulk-accept-applies,
	 * 2026-09-21), every row given to this method was already {@code ACCEPTED} going in - applying is the
	 * whole action here, not a side effect of an accept decision. Kept unchanged by that fix.
	 */
	private void confirmApplySelected(final UserServiceEventContext pContext)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		final List<ReviewRules.AffectedRow> rows = this.pendingRows;
		this.clearPending();
		if (rows == null || rows.isEmpty())
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.none"));
			return;
		}
		final List<String> ids = new ArrayList<String>();
		for (final ReviewRules.AffectedRow row : rows)
		{
			ids.add(row.rowId);
		}
		try
		{
			// audit F-34 (wave 10): queued, one job per zone (the model is republished once per zone, as applyAll batches them)
			final int[] counts = this.queueApplyAll(pContext.getSession(), ids);
			this.noticeKind = counts[1] > 0 ? "warn" : "ok";
			this.notice = Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.apply.queued", Integer.valueOf(counts[0]))
				+ (counts[1] > 0 ? " " + Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.apply.skipped", Integer.valueOf(counts[1])) : "");
		}
		catch (final OperationException ex)
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.notice.applyFailed", EngineNotice.inline(ex, this.locale)));
		}
	}

	/**
	 * Audit F-34 (wave 10): queues the application of {@code pIds} - one {@link CapabilityApplyRun#startApplyAll} job per zone, since a job holds the one resource of its
	 * zone. Returns {@code {suggestions queued, suggestions whose zone is unknown and so were not queued}}.
	 */
	private int[] queueApplyAll(final Session pSession, final List<String> pIds) throws OperationException
	{
		final java.util.Map<String, String> zones = new java.util.HashMap<String, String>();
		for (final String id : pIds)
		{
			zones.put(id, WizardData.capabilityZone(this.factory, id));
		}
		int queued = 0;
		int skipped = 0;
		for (final java.util.Map.Entry<String, List<String>> group : CapabilityApplyRun.groupByZone(pIds, zones).entrySet())
		{
			final String kind = CapabilityApplyRun.applyAllKindFor(group.getKey());
			if (kind == null)
			{
				skipped += group.getValue().size();
				continue;
			}
			CapabilityApplyRun.startApplyAll(pSession, this.factory, this.projectId, kind, this.projectCode, group.getValue());
			queued += group.getValue().size();
		}
		return new int[] { queued, skipped };
	}

	/**
	 * Applies a previously offered multi-row decision (bulk accept preview, or the cascade offered after
	 * an entity reject): every row is re-validated fresh by {@link ProposalReviews#decideRef}, exactly
	 * like a single decision - none of this trusts that what was true when the preview was built is still
	 * true now.
	 * <p>
	 * A bulk ACCEPT of {@link ReviewLine#ATTRIBUTE} lines also cascades their mappings (coordinator review,
	 * 2026-09-20: {@code MappingFollowRule} - "supplierId comes from SUPPLIER.SUPPLIER_ID, trimmed" is part
	 * of the same proposal, not a second decision nobody thinks to make) - the summary message says so
	 * ("N attribute(s) accepted, M mapping(s) followed") whenever at least one mapping actually followed,
	 * so accepting 12 attributes that quietly aligned 14 mapping rows is not silently invisible; a mapping
	 * line itself keeps its own Accept/Reject regardless (a reviewer may still refuse one source of a
	 * multi-source alignment) - this only reports what {@link ProposalReviews.DecisionResult#mappingsFollowed}
	 * says actually followed, never forces anything.
	 * <p>
	 * <b>fix-bulk-accept-applies (2026-09-21)</b>: when {@code pTargetStatus} is {@link ReviewLine#ACCEPTED},
	 * every {@code SCRIPT_CONSTRAINT} row THIS call itself just moved to {@code ACCEPTED} (never one already
	 * {@code ACCEPTED} before this call ran, which cannot happen here anyway since {@link #previewBulkAccept}/
	 * {@link #previewSelected} only ever offer a row that {@link com.on.ebx.factory.review.DecisionActions}
	 * says can still be accepted) is applied right after, through {@link #applyAcceptedScriptConstraints} -
	 * the product owner's own decision that acceptance applies directly (CLAUDE.md/{@code docs/CAPABILITY-
	 * APPLY.md}) applied here exactly as it already is to a single-row accept click
	 * ({@link #decideOne}/{@link #applyOne}), so a threshold-based bulk accept and "Accept selected" now
	 * behave identically to a single accept and to each other - and identically to what
	 * {@link #capabilityBulkSummary} has always told the reviewer would happen.
	 */
	void confirmBulk(final UserServiceEventContext pContext, final String pTargetStatus, final String pDoneMessageKey)
	{
		final List<ReviewRules.AffectedRow> rows = this.pendingRows;
		this.clearPending();
		if (rows == null || rows.isEmpty())
		{
			this.warn(Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.none"));
			return;
		}
		int ok = 0;
		int refused = 0;
		int mappingsFollowed = 0;
		// fix-bulk-accept-applies (2026-09-21): which rows THIS call itself moved to pTargetStatus - the only
		// input ReviewRules.idsToApplyAfterAccept needs beyond the rows/target status themselves, so the
		// "which ids get applied" rule stays a pure function tested without EBX (ReviewRulesTest), while only
		// this loop (which must call the real, EBX-bound decideRef) knows which ones actually succeeded.
		final java.util.Set<String> succeededIds = new java.util.LinkedHashSet<String>();
		for (final ReviewRules.AffectedRow row : rows)
		{
			final ProposalReviews.DecisionResult result = ProposalReviews.decideRef(pContext.getSession(), this.factory, row, pTargetStatus);
			if (result.ok)
			{
				ok++;
				mappingsFollowed += result.mappingsFollowed;
				succeededIds.add(row.rowId);
			}
			else
			{
				refused++;
			}
		}
		this.noticeKind = refused > 0 ? "warn" : "ok";
		this.notice = "review.cascade.done".equals(pDoneMessageKey) ? Messages.get(ProposalReviewUserService.class, this.locale, pDoneMessageKey, Integer.valueOf(ok))
			: mappingsFollowed > 0
				? Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.done.mappings", Integer.valueOf(ok), Integer.valueOf(mappingsFollowed),
					Integer.valueOf(refused))
				: Messages.get(ProposalReviewUserService.class, this.locale, pDoneMessageKey, Integer.valueOf(ok), Integer.valueOf(refused));
		// fix-bulk-accept-applies (2026-09-21): every SCRIPT_CONSTRAINT row THIS call just moved to ACCEPTED
		// (never one accepted earlier, never one this call rejected) - see ReviewRules.idsToApplyAfterAccept
		// and this method's own Javadoc for why.
		final List<String> appliedCandidateIds = ReviewRules.idsToApplyAfterAccept(rows, succeededIds, pTargetStatus);
		if (!appliedCandidateIds.isEmpty())
		{
			this.applyAcceptedScriptConstraints(pContext, appliedCandidateIds);
		}
	}

	/**
	 * fix-bulk-accept-applies (2026-09-21): applies every {@code SCRIPT_CONSTRAINT} row {@link #confirmBulk}
	 * just accepted this round - {@code ebx.CapabilityApplier.applyAll}, batched the same way a single Apply
	 * or "Apply selected" already is (one publish per model). Appends its own outcome to the notice
	 * {@link #confirmBulk} already set, rather than replacing it, so the accept/reject counts (and any
	 * mappings-followed count) stay visible alongside the apply outcome.
	 * <p>
	 * <b>If {@code applyAll} itself throws</b>, the accept decisions already written are NOT rolled back -
	 * they are valid decisions on their own, made and committed before this method ever ran - the notice
	 * instead says the application failed and that the affected rows remain {@code ACCEPTED} with Apply
	 * offered to retry (same row-level recovery {@code ebx.CapabilityApplier} itself already provides:
	 * a row that fails to apply is left {@code ACCEPTED} with its own {@code applyMessage}, not silently
	 * lost - see {@code docs/CAPABILITY-APPLY.md}).
	 */
	private void applyAcceptedScriptConstraints(final UserServiceEventContext pContext, final List<String> pIds)
	{
		if (this.refuseUnlessAdmin(pContext))
		{
			return;
		}
		try
		{
			// audit F-34 (wave 10): queued (see queueApplyAll) - the rows stay ACCEPTED until the job applies them, and a failed one stays ACCEPTED with its own message
			final int[] counts = this.queueApplyAll(pContext.getSession(), pIds);
			if (counts[1] > 0)
			{
				this.noticeKind = "warn";
			}
			this.notice = this.notice + ' ' + Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.applied.queued", Integer.valueOf(counts[0]))
				+ (counts[1] > 0 ? " " + Messages.get(ProposalReviewUserService.class, this.locale, "review.selection.apply.skipped", Integer.valueOf(counts[1])) : "");
		}
		catch (final OperationException ex)
		{
			this.noticeKind = "warn";
			this.notice = this.notice + ' ' + Messages.get(ProposalReviewUserService.class, this.locale, "review.bulk.applied.failed", EngineNotice.inline(ex, this.locale));
		}
	}

	void clearPending()
	{
		this.pendingConfirmation = null;
		this.pendingRows = null;
	}

	void warn(final String pMessage)
	{
		this.noticeKind = "warn";
		this.notice = pMessage;
	}

	// ------------------------------------------------------------------ the page

	void write(final UserServicePaneWriter pWriter, final Session pSession)
	{
		// fix/review-assets (2026-09-21): Assets.version() changes with every build.sh run - a fixed "?v=1" was
		// exactly why the product owner's browser kept serving the OLD review.css (no .mdmf-selection-bar, no
		// .mdmf-progress) across the real CSS change that added both - see Assets' own Javadoc.
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/review.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		final List<ReviewPage.ProjectOption> projectOptions = new ArrayList<ReviewPage.ProjectOption>();
		if (this.projectId == null)
		{
			for (final Adaptation project : this.asUser(() -> ProposalReviews.projects(this.factory)))
			{
				projectOptions.add(new ReviewPage.ProjectOption(project.getOccurrencePrimaryKey().format(), this.asUser(() -> ProposalReviewUserService.labelOf(project))));
			}
		}
		// Survivorship-review lot (2026-09-22): attachSurvivorshipInputs runs LAST, after attachMatchExtras - the
		// two attach steps are independent (ReviewLine.withMatchExtras/withSurvivorshipInput each carry the
		// other's field over unchanged, see their own Javadoc), order does not matter for correctness, only for
		// which live reads happen first. pSession is this render's own live session - the only place in this
		// class' several this.asUser(() -> ProposalReviews.lines(...)) call sites that actually renders section 4, so it is the
		// only one that needs the extra (cheap: one row-count query per distinct entity) live reads.
		final List<ReviewLine> allLines = this.projectId == null ? new ArrayList<ReviewLine>()
			: this.asUser(() -> ProposalReviews.attachSurvivorshipInputs(this.factory, pSession, this.projectId,
				this.asUser(() -> ProposalReviewUserService.attachMatchExtras(this.factory, this.asUser(() -> ProposalReviews.lines(this.factory, this.projectId, this.locale))))));
		final List<ReviewLine> visibleLines = ReviewData.filter(allLines, this.filter);
		// simulation-preview-policy lot (2026-09-22): Simulate no longer requires APPLIED - PROPOSED/ACCEPTED
		// simulate on a preview policy (ebx.MatchingPreviewPolicy), APPLIED on the real one; only REJECTED (or no
		// plan at all) still disables the button - see canSimulateMatchPlan's own Javadoc. Already carried on the
		// line's own ReviewLine.status, no extra EBX lookup needed here.
		final java.util.Map<String, Boolean> matchCanSimulate = new java.util.HashMap<String, Boolean>();
		for (final ReviewLine line : allLines)
		{
			if (ReviewLine.MATCH.equals(line.nature))
			{
				matchCanSimulate.put(line.rowId, Boolean.valueOf(ProposalReviewUserService.canSimulateMatchPlan(line.status)));
			}
		}
		// tree-diagram-labels fix lot (2026-09-22), item C: the one row (if any) this exact postback acted on -
		// reopened automatically, see this.lastActedMatchRowId's own Javadoc and ReviewPage.Input#expandedMatchRowIds.
		final java.util.Set<String> expandedMatchRowIds = this.lastActedMatchRowId == null ? java.util.Collections.<String>emptySet()
			: java.util.Collections.singleton(this.lastActedMatchRowId);
		// feed-designer lot (2026-09-23): a root FEED line only (ReviewPage.Input#feedDesignerUrls' own
		// Javadoc) - "root" read off the SAME element text ProposalReviews.addFeedLines builds ("... (child of
		// ..." suffix), rather than a second EBX read of TransferFeed.parentFeed, since this screen already has
		// the fully-rendered line and nothing else here needs the raw row.
		final java.util.Map<String, String> feedDesignerUrls = new java.util.HashMap<String, String>();
		if (this.factory != null)
		{
			final String dataspaceKey = this.factory.getHome().getKey().format();
			final String datasetName = this.factory.getAdaptationName().getStringName();
			for (final ReviewLine line : allLines)
			{
				if (ReviewLine.FEED.equals(line.nature) && line.element.indexOf(" (child of ") < 0)
				{
					feedDesignerUrls.put(line.rowId, com.on.ebx.factory.core.FeedDesignerLink.url(dataspaceKey, datasetName, line.rowId));
				}
			}
		}
		final ReviewPage.Input input = new ReviewPage.Input(this.locale, projectOptions, this.projectId, this.projectLabel == null ? this.projectCode : this.projectLabel,
			this.projectLocked, allLines, visibleLines, this.filter, this.bulkThreshold, this.notice, this.noticeKind, this.pendingConfirmation, matchCanSimulate, null,
			this.matchSimulationResults, com.on.ebx.factory.ebx.MatchPlanSimulationRunner.MAX_PIVOTS, expandedMatchRowIds, feedDesignerUrls);
		pWriter.add(ReviewPage.render(input));
		pWriter.addJS_cr(ProposalReviewUserService.pageJs(this.locale));
		// end-of-task notice (wave 10): this screen has no jobs banner, so it reads the project's jobs once (a task that ended while the person was elsewhere) and
		// follows them only while a task of this person is queued or running - see jobnotice.js
		if (this.projectCode != null && !this.projectCode.isEmpty())
		{
			pWriter.addJS_cr(JobNotice.script(this.locale) + JobNotice.watchCall(JobNotice.jobsUrl(this.projectCode) + "?lang=" + this.locale.getLanguage()));
		}
		// feed-designer lot (2026-09-23): arms this screen's own "Designer" links (FEED lines only - see
		// ReviewPage.Input#feedDesignerUrls) so the designer's Close button can come back HERE - same
		// mechanism as ProjectWizardUserService's own copy of this line, see FactoryHtml's own Javadoc.
		pWriter.addJS_cr(FactoryHtml.originCaptureScript(FactoryHtml.userId(pSession), ".mdmf-feed-designer-link"));
	}

	/**
	 * Reject/repropose lot (2026-09-22): attaches {@code MatchPlan.reviewComment}/{@code message} to every
	 * {@link ReviewLine#MATCH} line, right before rendering - one extra single-row lookup per MATCH line
	 * ({@link ProposalReviews#matchPlanExtras}), deliberately NOT folded into {@link ProposalReviews#lines}
	 * itself (a concurrent lot touches that method for a different field - see {@link ReviewLine#reviewComment}'s
	 * own Javadoc). Every other nature's line is returned unchanged.
	 */
	static List<ReviewLine> attachMatchExtras(final Adaptation pFactory, final List<ReviewLine> pLines)
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>(pLines.size());
		for (final ReviewLine line : pLines)
		{
			if (ReviewLine.MATCH.equals(line.nature))
			{
				final ProposalReviews.MatchPlanExtras extras = ProposalReviews.matchPlanExtras(pFactory, line.rowId);
				out.add(ReviewLine.withMatchExtras(line, extras.reviewComment, extras.message));
			}
			else
			{
				out.add(line);
			}
		}
		return out;
	}

	static String labelOf(final Adaptation pProject)
	{
		// second permissions pass (2026-10-14): the label only when the field is not hidden from the user of the current read scope; the key otherwise
		final String label = com.on.ebx.factory.ebx.UserView.string(pProject, FactoryPaths.Project.LABEL);
		return label == null || label.trim().isEmpty() ? pProject.getOccurrencePrimaryKey().format() : label;
	}

	static String param(final UserServiceEventContext pContext, final String pName)
	{
		final String value = pContext.getParameter(pName);
		return value == null ? "" : value.trim();
	}

	static int paramInt(final UserServiceEventContext pContext, final String pName, final int pDefault)
	{
		final String value = ProposalReviewUserService.param(pContext, pName);
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

	/**
	 * fix-apply-ux (2026-09-21), item 4: how long the progress strip's own safety timer waits before
	 * clearing itself and re-enabling every button - only reached when the page never actually leaves (EBX
	 * rejected the postback on a field constraint, or the request genuinely hung). Audit F-49 (wave 10): was 45 s,
	 * which re-enabled the buttons while a pass of the request (a bulk decision of many rows) still ran; the
	 * publications that justified it are queued jobs now, so the timer is the 15 minutes the wizard uses.
	 */
	static final int PAGE_JS_PROGRESS_TIMEOUT_MS = 900000;

	/** Audit F-49 (wave 10): how long the buttons stay locked after a quick action when the page never comes back (EBX refused the postback, the network dropped) - the long timer above is for what runs in the request. */
	static final int PAGE_JS_LOCK_TIMEOUT_MS = 120000;

	/**
	 * Every interactive control on the page is {@code type="button"} with {@code data-mdmf-*}
	 * attributes; this delegate reads them, fills the hidden fields {@link ReviewPage} rendered, and
	 * clicks the one real submit button - same technique as the sibling data-catalog module's
	 * {@code TagReviewUserService} ({@code MODEL_JS}/the main select-and-act script).
	 * <p>
	 * <b>Coordinator review, apply lot (2026-09-21)</b>: the whole request (this submit included) is
	 * SYNCHRONOUS - accepting a {@code SCRIPT_CONSTRAINT} line, Apply, Remove, Measure again, or confirming
	 * a bulk accept that includes any {@code SCRIPT_CONSTRAINT} line calls {@code ebx.CapabilityApplier},
	 * which can publish a data model (tens of seconds to minutes). There is no AJAX progress channel to poll
	 * here (see the class Javadoc's own "posting a user service's own inputs" reference, CLAUDE.md
	 * &sect;9), so {@code progress(...)} below is the only feedback this synchronous, full-page-postback UI
	 * can give while the request is in flight - shown immediately on click, before the real postback fires.
	 * <p>
	 * <b>fix-apply-ux (2026-09-21), item 4</b>: {@code progress(...)} reuses, verbatim, the indeterminate
	 * strip technique of {@code MaterialSearchUserService.writeProgress} (ebx-ps-fasttrack-web,
	 * {@code com.on.ebx.material.ui.search}: an armed button's click switches the strip's class to "on" and
	 * sets its label, a {@link #PAGE_JS_PROGRESS_TIMEOUT_MS} safety timer clears it back) - never a second
	 * implementation. Unlike that reference (which only hides the strip again on timeout), this version ALSO
	 * re-enables every button then, since a genuinely stuck/rejected postback here must remain retryable, not
	 * leave the page silently locked. Once the postback completes, {@link #decideOne}/{@link #removeOne}/
	 * {@link #measureOne}/{@link #applyOneDirectly}/{@link #confirmBulk} each set a clear, specific result
	 * notice on the re-rendered page ({@code review.notice.applied}/{@code applyFailed}/{@code removed}/
	 * {@code removeFailed}/{@code measured}/{@code measureFailed}/{@code bulk.done}) - the two together are
	 * what the coordinator's own review asked to confirm: a visible "in progress" state, then a clear result.
	 */
	static String pageJs(final Locale pLocale)
	{
		return "(function(){var root=document.querySelector('.mdmf-review');if(!root)return;"
			+ "function set(id,v){var e=document.getElementById(id);if(e)e.value=v;}"
			+ "function val(id){var e=document.getElementById(id);return e?e.value:'';}"
			// fix/review-assets (2026-09-21): box.style.display is set DIRECTLY, in addition to the class swap -
			// the same degrade-gracefully idea as the selection bar's inline margin (ReviewPage.renderSelectionBar):
			// a browser still serving a STALE review.css (the exact bug this fix responds to, missing
			// .mdmf-progress/.mdmf-progress.on entirely) still shows the strip, just unstyled, rather than never
			// showing it at all - the inline style never depends on which stylesheet happens to be cached.
			// audit F-49 (wave 10): every action that goes to the server locks the buttons until the page comes back (lockAll), not only the ones that arm the progress
			// strip - a second click posted the same action twice. The strip's own timer is the long one and never re-enables anything before the page answers:
			// what still runs in this request (a bulk decision of many rows) must not be given a live button after 45 s, which is what the old timer did.
			+ "var locked=false;"
			+ "function unlockAll(){locked=false;root.querySelectorAll('.mdmf-btn').forEach(function(btn){btn.disabled=false;});}"
			+ "function lockAll(ms){locked=true;root.querySelectorAll('.mdmf-btn').forEach(function(btn){btn.disabled=true;});window.setTimeout(unlockAll,ms);}"
			+ "window.addEventListener('pageshow',function(e){if(e.persisted)unlockAll();});"
			+ "function progress(msg){var box=document.getElementById('mdmfProgress');var label=document.getElementById('mdmfProgressLabel');"
			+ "if(label)label.textContent=msg;if(box){box.className='mdmf-progress on';box.style.display='flex';}"
			+ "lockAll(" + ProposalReviewUserService.PAGE_JS_PROGRESS_TIMEOUT_MS + ");"
			+ "window.setTimeout(function(){if(box){box.className='mdmf-progress';box.style.display='none';}},"
			+ ProposalReviewUserService.PAGE_JS_PROGRESS_TIMEOUT_MS + ");}"
			+ "var progressLabel={apply:" + ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.progress.apply"))
			+ ",remove:" + ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.progress.remove")) + ",measure:"
			+ ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.progress.measure")) + ",bulk:"
			+ ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.progress.bulk")) + ",selectReject:"
			+ ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.progress.selectReject")) + ",repropose:"
			+ ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.progress.repropose")) + "};"
			// reject/repropose lot (2026-09-22): the two MATCH-only inline panels (Propose another / No matching for
			// this entity) are shown/hidden by a plain toggle button that carries data-mdmf-toggle (the id of the
			// panel), never data-mdmf-action - so it is never caught by the [data-mdmf-action] handler below and
			// never itself submits anything, only reveals the panel with the real comment/confirm controls.
			+ "root.querySelectorAll('[data-mdmf-toggle]').forEach(function(t){t.addEventListener('click',function(e){e.preventDefault();"
			+ "var panel=document.getElementById(t.getAttribute('data-mdmf-toggle'));if(panel)panel.style.display=(panel.style.display==='none'||!panel.style.display)?'block':'none';"
			+ "});});"
			// automerge-toggle lot (2026-09-23), task 1: the "Merge confirmed matches automatically" checkbox posts
			// itself on its own 'change' event - a distinct control from every [data-mdmf-action] button above (a
			// checkbox has no click-to-submit semantics of its own), so it gets its own small listener here, same
			// pattern as the selectAll/rowBoxes checkboxes just below in spirit (their own dedicated 'change'
			// listeners) even though those never submit anything by themselves.
			+ "root.querySelectorAll('.mdmf-auto-merge-toggle').forEach(function(cb){cb.addEventListener('change',function(){"
			+ "set('mdmfAction','SET_AUTO_MERGE');set('mdmfNature','MATCH');set('mdmfId',cb.getAttribute('data-mdmf-id'));"
			+ "set('mdmfAutoMergeValue',cb.checked?'true':'false');"
			+ "if(!locked)lockAll(" + ProposalReviewUserService.PAGE_JS_LOCK_TIMEOUT_MS + ");"
			+ "var s=document.getElementById('mdmfSubmit');if(s)s.click();});});"
			// fix-apply-ux (2026-09-21), item 3: the selection bar - "select all shown" toggles every row
			// checkbox, any row checkbox change recomputes the "N selected" bar (shown only once count>0) and
			// whether Apply selected shows at all (only while the CURRENT selection includes at least one
			// data-mdmf-can-apply="1" box - an ACCEPTED-not-applied SCRIPT_CONSTRAINT line). Selection state
			// lives only in the DOM's own checkboxes - nothing here is written back to a hidden field until a
			// selection-bar button is actually clicked (see the SELECT_*_PREVIEW branch below), and a fresh
			// render after any postback starts with every checkbox unchecked again - "must not survive the
			// round trip" is satisfied by simply never restoring a checked state, not by extra code.
			+ "var selectionBar=document.getElementById('mdmfSelectionBar');var selectionCount=document.getElementById('mdmfSelectionCount');"
			+ "var selectApplyBtn=document.getElementById('mdmfSelectApplyBtn');var selectAll=document.getElementById('mdmfSelectAll');"
			+ "function rowBoxes(){return root.querySelectorAll('.mdmf-row-select');}"
			+ "function updateSelectionBar(){var checked=root.querySelectorAll('.mdmf-row-select:checked');"
			+ "if(selectionBar)selectionBar.className='mdmf-selection-bar'+(checked.length>0?' on':'');"
			+ "if(selectionCount)selectionCount.textContent=checked.length>0?"
			+ ProposalReviewUserService.jsString(Messages.get(ProposalReviewUserService.class, pLocale, "review.selection.count")).replace("{0}", "'+checked.length+'")
			+ ":'';"
			+ "var canApply=false;checked.forEach(function(cb){if(cb.getAttribute('data-mdmf-can-apply')==='1')canApply=true;});"
			+ "if(selectApplyBtn)selectApplyBtn.className='mdmf-btn mdmf-btn-sm mdmf-btn-primary'+(canApply?' on':'');}"
			+ "if(selectAll)selectAll.addEventListener('change',function(){rowBoxes().forEach(function(cb){cb.checked=selectAll.checked;});updateSelectionBar();});"
			+ "rowBoxes().forEach(function(cb){cb.addEventListener('change',updateSelectionBar);});"
			+ "root.querySelectorAll('[data-mdmf-action]').forEach(function(b){b.addEventListener('click',function(e){e.preventDefault();"
			+ "var action=b.getAttribute('data-mdmf-action');set('mdmfAction',action);"
			+ "var nature=b.getAttribute('data-mdmf-nature');if(nature!==null)set('mdmfNature',nature);"
			+ "var id=b.getAttribute('data-mdmf-id');if(id!==null)set('mdmfId',id);"
			// reject/repropose lot (2026-09-22): the MATCH inline panels' own Send/Confirm buttons carry
			// data-mdmf-comment-from (the textarea's id) - copied into mdmfComment right before submit, exactly the
			// "read the live control, overwrite the hidden mirror" technique this script already uses for the
			// project dropdown. data-mdmf-requires-checkbox (the confirmation checkbox's id) blocks the submit
			// entirely when unchecked - "No matching for this entity" must never fire without it.
			+ "var commentFrom=b.getAttribute('data-mdmf-comment-from');if(commentFrom!==null){var ta=document.getElementById(commentFrom);set('mdmfComment',ta?ta.value:'');}"
			+ "var requiresCheckbox=b.getAttribute('data-mdmf-requires-checkbox');"
			+ "if(requiresCheckbox!==null){var confirmBox=document.getElementById(requiresCheckbox);if(!confirmBox||!confirmBox.checked){if(confirmBox)confirmBox.focus();return;}}"
			+ "var filterNature=b.getAttribute('data-mdmf-filter-nature');if(filterNature!==null)set('mdmfFilterNature',filterNature);"
			// task B empty-state links: a button can also request a STATUS switch - update the <select> itself
			// (not just the hidden field) BEFORE the FILTER branch below reads mdmfStatusSelect's own value, or
			// this would be silently overwritten by whatever the dropdown still shows
			+ "var filterStatus=b.getAttribute('data-mdmf-filter-status');if(filterStatus!==null){var statusSel=document.getElementById('mdmfStatusSelect');if(statusSel)statusSel.value=filterStatus;}"
			+ "if(action==='SELECT_PROJECT'){var sel=document.getElementById(b.getAttribute('data-mdmf-project-select'));set('mdmfProjectCode',sel?sel.value:'');}"
			+ "if(action==='FILTER'||action==='BULK_ACCEPT_PREVIEW'){set('mdmfFilterOrigin',val('mdmfOriginSelect')||'ALL');set('mdmfFilterStatus',val('mdmfStatusSelect')||'ALL');"
			+ "set('mdmfFilterMinConfidence',val('mdmfMinConfidenceInput')||'0');set('mdmfFilterReference',val('mdmfReferenceInput'));}"
			+ "if(action==='BULK_ACCEPT_PREVIEW')set('mdmfBulkThreshold',val('mdmfBulkThresholdInput')||'80');"
			// item 3's own three preview buttons: collect the checked row ids right before the submit, the
			// same "nature|id" shape ProposalReviewUserService.selectedLines parses back on the server
			+ "if(action==='SELECT_ACCEPT_PREVIEW'||action==='SELECT_REJECT_PREVIEW'||action==='SELECT_APPLY_PREVIEW'){var ids=[];"
			+ "root.querySelectorAll('.mdmf-row-select:checked').forEach(function(cb){ids.push(cb.getAttribute('data-mdmf-nature')+'|'+cb.getAttribute('data-mdmf-id'));});"
			+ "set('mdmfSelectedIds',ids.join(','));}"
			// a request that may reach ebx.CapabilityApplier (accepting/removing/re-measuring/applying a
			// SCRIPT_CONSTRAINT line, confirming a bulk accept, or confirming a selection-bar action) is
			// synchronous and can publish a model - show the progress strip immediately, before the real submit
			// click fires (coordinator's own instruction: arm on Accept/Apply/Remove/Measure again/the
			// selection bar - every one of its three confirm actions, reject-selected included even though it
			// alone never publishes, for one consistent "in progress" experience across the whole bar)
			// audit F-34 (wave 10): accepting a script constraint, Apply, Remove, Measure again, Apply selected and Propose another now QUEUE their work (a
			// background job, answered at once) - no strip for them; what still runs in the request is the bulk decisions of many rows
			+ "if(action==='BULK_ACCEPT_CONFIRM'){progress(progressLabel.bulk);}"
			+ "else if(action==='SELECT_REJECT_CONFIRM'){progress(progressLabel.selectReject);}"
			// audit F-49: no strip armed = a quick action, or one that only queues a background job: lock all the same
			+ "if(!locked)lockAll(" + ProposalReviewUserService.PAGE_JS_LOCK_TIMEOUT_MS + ");"
			+ "var s=document.getElementById('mdmfSubmit');if(s)s.click();});});"
			// binary-decision-tree-screen lot (2026-09-22): the decision-tree block's own node selection and fold
			// expansion (com.on.ebx.factory.review.MatchPlanTreeDiagram#SCRIPT) - client-side only, no round trip.
			+ com.on.ebx.factory.review.MatchPlanView.treeScript()
			// tree-diagram-labels fix lot (2026-09-22), item B: the top-of-page counter's own anchors
			// (ReviewPage.renderMatchCounter, "#mdmf-line-<rowId>") land on the collapsed row itself (browser's
			// own native scroll-to-id, no JS needed for that part) - this only opens that line's <details> too,
			// since a plain anchor never does. The expanded row is always the id'd row's own nextElementSibling
			// (ReviewPage.renderTable calls renderMatchExpandedRow immediately after renderRow for the same
			// line - never assumed to be adjacent by a CSS selector on the id itself, which would need escaping
			// a rowId that might contain a character unsafe in a raw attribute-selector string).
			+ "function mdmfOpenFromHash(){var h=window.location.hash;if(!h||h.indexOf('#mdmf-line-')!==0)return;"
			+ "var row=document.getElementById(h.substring(1));if(!row)return;"
			+ "var next=row.nextElementSibling;if(next){var d=next.querySelector('details');if(d)d.open=true;}"
			+ "if(row.scrollIntoView)row.scrollIntoView({block:'center'});}"
			+ "window.addEventListener('hashchange',mdmfOpenFromHash);mdmfOpenFromHash();"
			+ "var hidden=document.getElementById('mdmfSubmit');if(hidden)hidden.style.display='none';})();";
	}

	/** A JavaScript string literal, quotes and line breaks included - same escaping {@code MaterialSearchUserService.jsString} uses (ebx-ps-fasttrack-web), reused here for the same reason: every embedded label is real, localized message text, never assumed to be JS-safe as-is. */
	static String jsString(final String pValue)
	{
		return "'" + FactoryHtml.jsStringBody(pValue) + "'";
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext,
		final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// nothing: the form is plain HTML read back from the request
	}
}
