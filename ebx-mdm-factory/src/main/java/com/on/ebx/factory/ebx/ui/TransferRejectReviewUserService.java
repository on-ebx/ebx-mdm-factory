package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.ebx.EngineRefusal;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.ebx.FactoryAccess;
import com.on.ebx.factory.ebx.FactoryInstaller;
import com.on.ebx.factory.ebx.RejectBulkRun;
import com.on.ebx.factory.ebx.RejectReview;
import com.on.ebx.factory.rejects.RejectCsv;
import com.on.ebx.factory.rejects.RejectFilter;
import com.on.ebx.factory.rejects.RejectRef;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.URLQuery;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.UIButtonSpecNavigation;
import com.orchestranetworks.ui.UIButtonSpecSubmit;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceGetContext;
import com.orchestranetworks.userservice.UserServiceGetRequest;
import com.orchestranetworks.userservice.UserServiceGetResponse;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * The "Transfer rejects" screen of one project (audit F-38, wave 10): the page ({@link RejectReviewPage}) is filled from {@link RejectReview}, whose
 * summaries, pages and counts are SQL aggregates - never every reject of the project read and sorted in memory. Opened on a {@code Project} record like
 * the Cockpit; a holder of VIEW reads and exports, a holder of STEWARD also acknowledges and reopens (the right is asked again by the engine).
 * <p>
 * Every control posts through the one submit button with a hidden action field (see {@link RejectReviewPage}); the filter and the offset travel as plain
 * posted fields, so the service keeps no state but the last result. The CSV is a {@link UserServiceGetRequest}: a plain GET of the same filter, streamed.
 */
public final class TransferRejectReviewUserService implements UserService<RecordEntitySelection>
{
	Adaptation project;
	Adaptation dataset;
	Locale locale = Locale.ENGLISH;
	Session session;

	RejectFilter filter = new RejectFilter();
	int offset;
	String notice = "";
	boolean noticeIsError;
	String comment = "";

	/** The CSV of the filter named by the request's own parameters, under the caller's session; VIEW is asked again here (a URL can be passed around). */
	private final UserServiceGetRequest csvRequest = new UserServiceGetRequest()
	{
		@Override
		public void processRequest(final UserServiceGetContext pContext, final UserServiceGetResponse pResponse)
		{
			final Session caller = pContext.getSession();
			if (!FactoryAccess.has(caller, FactoryRight.VIEW))
			{
				throw new IllegalStateException(FactoryAccess.refusal("exporting transfer rejects", FactoryRight.VIEW));
			}
			final Adaptation factory = FactoryInstaller.dataset(pContext.getRepository());
			// second permissions pass (2026-10-14): the project and the service, checked again for this request's own session
			if (!TransferRejectReviewUserService.this.opens(caller))
			{
				throw new IllegalStateException(Messages.get(TransferRejectReviewUserService.class, caller.getLocale(), "screen.project.unknown"));
			}
			final String code = TransferRejectReviewUserService.this.projectCode();
			final String file = pContext.getParameter("file");
			if (file != null && !file.isEmpty())
			{
				// F2-103: a file prepared by the export job - served to the login that asked for it, never read from another path
				final java.io.File prepared = RejectBulkRun.fileFor(file, FactoryHtml.userId(caller));
				if (prepared == null || !file.startsWith("rejects-" + code.replaceAll("[^A-Za-z0-9_.]", "_") + "-"))
				{
					throw new IllegalStateException(Messages.get(TransferRejectReviewUserService.class, caller.getLocale(), "rejects.export.job.failed", file));
				}
				pResponse.setContentType("text/csv; charset=UTF-8");
				pResponse.setHeader("Content-Disposition", "attachment; filename=\"" + file + "\"");
				try
				{
					final OutputStream out = pResponse.getOutputStream();
					java.nio.file.Files.copy(prepared.toPath(), out);
					out.flush();
				}
				catch (final IOException ex)
				{
					throw new IllegalStateException("The transfer rejects could not be exported: " + ex.getMessage(), ex);
				}
				return;
			}
			final RejectFilter wanted = RejectFilter.parse(pContext.getParameter("entity"), pContext.getParameter("stage"), pContext.getParameter("status"),
				pContext.getParameter("reason"), pContext.getParameter("from"), pContext.getParameter("to"), pContext.getParameter("code"));
			try
			{
				// F2-103: a direct download is for a filter of up to the inline threshold; above it the export is a job
				final long matching = RejectReview.count(caller, factory, code, wanted);
				if (matching > RejectReview.inlineMax())
				{
					throw new IllegalStateException(Messages.get(TransferRejectReviewUserService.class, caller.getLocale(), "rejects.export.tooBig", Long.valueOf(matching)));
				}
			}
			catch (final OperationException ex)
			{
				throw new IllegalStateException("The transfer rejects could not be exported: " + ex.getMessage(), ex);
			}
			pResponse.setContentType("text/csv; charset=UTF-8");
			pResponse.setHeader("Content-Disposition", "attachment; filename=\"" + RejectCsv.fileName(code, new java.util.Date()) + "\"");
			try
			{
				final OutputStream out = pResponse.getOutputStream();
				RejectReview.exportCsv(caller, factory, code, wanted, out);
				out.flush();
			}
			catch (final OperationException | IOException ex)
			{
				throw new IllegalStateException("The transfer rejects could not be exported: " + ex.getMessage(), ex);
			}
		}
	};

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<RecordEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.project = pContext.getEntitySelection().getRecord();
		this.dataset = pContext.getEntitySelection().getDataset();
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<RecordEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		this.session = pContext.getSession();
		// second permissions pass (2026-10-14): a record service can be opened by URL on any key - dataset, record and service permission first
		if (!this.opens(this.session))
		{
			final String message = ProjectAccess.refusal(this.session, this.dataset, this.locale) != null ? ProjectAccess.refusal(this.session, this.dataset, this.locale)
				: Messages.get(TransferRejectReviewUserService.class, this.locale, "screen.project.unknown");
			pConfigurator.setTitle(Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.title", ""));
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
		pConfigurator.setTitle(Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.title", this.projectCode()));
		final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.filter.apply"),
			new UserServiceEvent()
			{
				@Override
				public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
				{
					TransferRejectReviewUserService.this.handle(pEventContext);
					return null;
				}
			});
		submit.setId(RejectReviewPage.SUBMIT_ID);
		pConfigurator.setLeftButtons(submit);
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		pConfigurator.setContent(ScreenFailure.guard("transfer rejects", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				TransferRejectReviewUserService.this.write(pWriter);
			}
		}));
	}

	// ------------------------------------------------------------------------------------------------- the event

	void handle(final UserServiceEventContext pContext)
	{
		this.notice = "";
		this.noticeIsError = false;
		final String action = TransferRejectReviewUserService.nz(pContext.getParameter(RejectReviewPage.ACTION_FIELD));
		this.filter = "CLEAR".equals(action) ? new RejectFilter() : RejectFilter.parse(pContext.getParameter("mdmfRjEntity"), pContext.getParameter("mdmfRjStage"),
			pContext.getParameter("mdmfRjStatus"), pContext.getParameter("mdmfRjReason"), pContext.getParameter("mdmfRjFrom"), pContext.getParameter("mdmfRjTo"),
			pContext.getParameter("mdmfRjCode"));
		this.comment = TransferRejectReviewUserService.nz(pContext.getParameter("mdmfRjComment"));
		int posted = 0;
		try
		{
			posted = Math.max(0, Integer.parseInt(TransferRejectReviewUserService.nz(pContext.getParameter("mdmfRjOffset"))));
		}
		catch (final NumberFormatException ex)
		{
			posted = 0;
		}
		final Session caller = pContext.getSession();
		// second permissions pass (2026-10-14): checked again on every postback - hidden since, nothing is read or written
		if (!this.opens(caller))
		{
			this.notice = Messages.get(TransferRejectReviewUserService.class, this.locale, "screen.project.unknown");
			this.noticeIsError = true;
			return;
		}
		final String code = this.projectCode();
		try
		{
			if ("APPLY".equals(action) || "CLEAR".equals(action) || action.isEmpty())
			{
				this.offset = 0;
			}
			else if ("PAGE_PREV".equals(action))
			{
				this.offset = Math.max(0, posted - RejectReview.PAGE_SIZE);
			}
			else if ("PAGE_NEXT".equals(action))
			{
				this.offset = posted + RejectReview.PAGE_SIZE;
			}
			else if ("EXPORT_JOB".equals(action))
			{
				this.offset = posted;
				if (!FactoryAccess.has(caller, FactoryRight.STEWARD)) // a job kind is queued by a person's right (FactoryRightsTest): the steward's
				{
					throw EngineRefusal.accessDenied("exporting a large set of transfer rejects", FactoryRight.STEWARD);
				}
				final long matching = RejectReview.count(caller, this.dataset, code, this.filter);
				RejectBulkRun.startExport(caller, this.dataset, code, this.filter);
				this.notice = Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.export.job.queued", Long.valueOf(matching));
			}
			else
			{
				this.offset = posted;
				this.act(action, caller, code, pContext);
			}
		}
		catch (final OperationException ex)
		{
			this.notice = EngineNotice.inline(ex, this.locale);
			this.noticeIsError = true;
		}
	}

	private void act(final String pAction, final Session pCaller, final String pCode, final UserServiceEventContext pContext) throws OperationException
	{
		if (!FactoryAccess.has(pCaller, FactoryRight.STEWARD))
		{
			throw EngineRefusal.accessDenied("handling transfer rejects", FactoryRight.STEWARD);
		}
		final List<RejectRef> selected = new ArrayList<RejectRef>();
		final String[] values = pContext.getParameterValues("mdmfRjSel");
		if (values != null)
		{
			for (final String value : values)
			{
				final RejectRef ref = RejectRef.decode(value);
				if (ref != null)
				{
					selected.add(ref);
				}
			}
		}
		final boolean ack = "ACK_SELECTED".equals(pAction) || "ACK_ALL".equals(pAction);
		final boolean all = "ACK_ALL".equals(pAction) || "REOPEN_ALL".equals(pAction);
		if (!ack && !"REOPEN_SELECTED".equals(pAction) && !all)
		{
			return; // an unknown action posts nothing
		}
		if (!all && selected.isEmpty())
		{
			this.notice = Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.notice.nothingSelected");
			this.noticeIsError = true;
			return;
		}
		if (ack && this.comment.trim().isEmpty())
		{
			this.notice = Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.notice.commentRequired");
			this.noticeIsError = true;
			return;
		}
		if (all)
		{
			// F2-103: above the inline threshold the sweep is a job (500 rows per transaction), not part of this request
			final long matching = RejectReview.count(pCaller, this.dataset, pCode, TransferRejectReviewUserService.withStatus(this.filter, ack ? RejectFilter.OPEN : RejectFilter.ACKNOWLEDGED));
			if (matching > RejectReview.inlineMax())
			{
				RejectBulkRun.startBulk(pCaller, this.dataset, pCode, this.filter, ack, this.comment);
				this.notice = Messages.get(TransferRejectReviewUserService.class, this.locale, "rejects.bulk.queued", Long.valueOf(matching));
				this.comment = "";
				return;
			}
		}
		final int changed;
		if (ack)
		{
			changed = all ? RejectReview.acknowledgeMatching(pCaller, this.dataset, pCode, this.filter, this.comment)
				: RejectReview.acknowledge(pCaller, this.dataset, pCode, selected, this.comment);
		}
		else
		{
			changed = all ? RejectReview.reopenMatching(pCaller, this.dataset, pCode, this.filter, this.comment)
				: RejectReview.reopen(pCaller, this.dataset, pCode, selected, this.comment);
		}
		this.notice = Messages.get(TransferRejectReviewUserService.class, this.locale, ack ? "rejects.notice.acknowledged" : "rejects.notice.reopened", Integer.valueOf(changed));
		this.comment = "";
	}

	// ------------------------------------------------------------------------------------------------- the page

	void write(final UserServicePaneWriter pWriter)
	{
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		final RejectReviewPage.Model model = new RejectReviewPage.Model();
		model.locale = this.locale;
		model.projectCode = this.projectCode();
		model.projectLabel = com.on.ebx.factory.ebx.UserView.of(this.session).text(this.project, FactoryPaths.Project.LABEL);
		model.filter = this.filter;
		model.canSteward = FactoryAccess.has(this.session, FactoryRight.STEWARD);
		model.comment = this.comment;
		model.notice = this.notice;
		model.error = this.noticeIsError;
		final String dataspaceKey = this.dataset.getHome().getKey().format();
		final String datasetName = this.dataset.getAdaptationName().getStringName();
		model.cockpitUrl = FactoryLinks.cockpitUrl(dataspaceKey, datasetName, model.projectCode);
		try
		{
			model.summaries.addAll(RejectReview.summary(this.session, this.dataset, model.projectCode));
			final Map<String, String> names = com.on.ebx.factory.ebx.UserView.scoped(com.on.ebx.factory.ebx.UserView.of(this.session),
				() -> RejectReview.entityNamesOf(this.dataset, model.projectCode));
			for (final Map.Entry<String, String> entry : names.entrySet())
			{
				model.entities.add(new String[] { entry.getKey(), entry.getValue() });
			}
			Collections.sort(model.entities, new Comparator<String[]>()
			{
				@Override
				public int compare(final String[] pA, final String[] pB)
				{
					return pA[1].compareToIgnoreCase(pB[1]);
				}
			});
			// staging load rejects (2026-10-17): why the rows were set aside, grouped (the same filter as the list)
			// staging last points (2026-10-17): the groups with rows left to look at come first (RejectSql.groups); room is read for the resolved ones too, each kind capped apart
			final List<RejectReview.Group> fetched = RejectReview.groups(this.session, this.dataset, model.projectCode, this.filter, RejectReviewPage.GROUPS_SHOWN * 2);
			int pendingShown = 0;
			int resolvedShown = 0;
			for (final RejectReview.Group group : fetched)
			{
				if (group.resolvedOnly() ? resolvedShown++ < RejectReviewPage.GROUPS_SHOWN : pendingShown++ < RejectReviewPage.GROUPS_SHOWN)
				{
					model.groups.add(group);
				}
			}
			model.groupTotal = fetched.size() < RejectReviewPage.GROUPS_SHOWN * 2 ? fetched.size()
				: RejectReview.groupCount(this.session, this.dataset, model.projectCode, this.filter);
			model.total = RejectReview.count(this.session, this.dataset, model.projectCode, this.filter);
			if (this.offset >= model.total && model.total > 0)
			{
				this.offset = (int) ((model.total - 1) / RejectReview.PAGE_SIZE) * RejectReview.PAGE_SIZE; // the last page: rows left the filter
			}
			model.offset = this.offset;
			model.rows.addAll(RejectReview.page(this.session, this.dataset, model.projectCode, this.filter, this.offset, RejectReview.PAGE_SIZE));
			if (model.canSteward)
			{
				model.openMatching = RejectReview.count(this.session, this.dataset, model.projectCode, TransferRejectReviewUserService.withStatus(this.filter, RejectFilter.OPEN));
				model.acknowledgedMatching = RejectReview.count(this.session, this.dataset, model.projectCode,
					TransferRejectReviewUserService.withStatus(this.filter, RejectFilter.ACKNOWLEDGED));
			}
		}
		catch (final OperationException ex)
		{
			model.notice = EngineNotice.inline(ex, this.locale);
			model.error = true;
		}
		final URLQuery query = new URLQuery();
		query.add("entity", this.filter.entityId);
		query.add("stage", this.filter.stage);
		query.add("status", this.filter.status);
		query.add("reason", this.filter.reasonContains);
		query.add("code", this.filter.reasonCode);
		query.add("from", RejectFilter.dayText(this.filter.from));
		query.add("to", RejectFilter.dayText(this.filter.to));
		model.csvUrl = pWriter.getURLForGetRequest(this.csvRequest, query);
		model.exportTooBig = model.total > RejectReview.inlineMax();
		model.exportAsJob = model.exportTooBig && model.canSteward;
		for (final String name : RejectBulkRun.filesOf(model.projectCode, FactoryHtml.userId(this.session)))
		{
			final URLQuery fileQuery = new URLQuery();
			fileQuery.add("file", name);
			model.exportFiles.add(new String[] { name, pWriter.getURLForGetRequest(this.csvRequest, fileQuery) });
		}
		pWriter.add(RejectReviewPage.render(model));
		pWriter.addJS_cr(RejectReviewPage.script());
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session)));
		// staging load rejects (2026-10-17): the "back" link of the page goes where the screen was opened from (the wizard's step 4), not always to the Cockpit
		pWriter.addJS_cr(FactoryHtml.originBackLinkScript(FactoryHtml.userId(this.session), "#" + RejectReviewPage.BACK_ID));
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link"));
	}

	/** The key (Project's key IS its code): links and queries need it even when the code field is hidden from the user. */
	private String projectCode()
	{
		return this.project == null ? "" : this.project.getOccurrencePrimaryKey().format();
	}

	/** Second permissions pass (2026-10-14): the dataset, the project record and this service's permission on it, for {@code pSession}. */
	boolean opens(final Session pSession)
	{
		return ProjectAccess.refusal(pSession, this.dataset, pSession == null ? this.locale : pSession.getLocale()) == null
			&& ProjectAccess.projectOpens(pSession, this.dataset, this.project)
			&& ProjectAccess.serviceOnRecord(pSession, TransferRejectReviewServiceDeclaration.SERVICE_KEY, this.project);
	}

	private static RejectFilter withStatus(final RejectFilter pFilter, final String pStatus)
	{
		final RejectFilter out = new RejectFilter();
		out.entityId = pFilter.entityId;
		out.stage = pFilter.stage;
		out.status = pStatus;
		out.reasonContains = pFilter.reasonContains;
		out.reasonCode = pFilter.reasonCode;
		out.from = pFilter.from;
		out.to = pFilter.to;
		return out;
	}

	private static String nz(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	@Override
	public void validate(final UserServiceValidateContext<RecordEntitySelection> pContext)
	{
		// handle() reports its own refusals; nothing to check ahead of the event
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<RecordEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
