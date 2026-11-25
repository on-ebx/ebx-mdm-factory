package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.ProjectDeleter;
import com.on.ebx.factory.ebx.ProjectDeleteRun;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Profile;
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
 * The "Delete project" screen: resolves the project the same way {@link ProjectWizardUserService} does (a
 * selected {@code Project} record, or a {@code project} launch parameter resolved once by primary key), reads
 * the plan through {@link ProjectDeleter#plan} for display, and, on submit, re-checks the administrator role
 * and the confirm text, then enqueues the deletion as a {@link ProjectDeleteRun} job (volume-operations lot
 * 8b, 2026-09-28, task 3 - it used to call {@link ProjectDeleter#delete} directly on the request thread). The
 * plan/report/running state is kept on the service instance for the next rendering, the same "request/response
 * lifetime" idiom {@link FactoryConnectionUserService} already uses for its own test button.
 * <p>
 * Once the deletion has actually succeeded ({@code this.lastResult.deleted}, confirmed asynchronously by {@link
 * #pollDelete}), the submit button is withheld from the next {@link #setupDisplay} (the service instance
 * survives the round trip that renders the report, so {@link #projectId} is still set and the row is gone -
 * re-submitting would fail loudly for no reason): only the close button remains, and the page shows a link
 * back to the project explorer instead. While {@code this.lastResult.running} is true, the same submit button
 * is instead driven by {@code write}'s own inline self-poll script every few seconds (see {@link
 * #pollDelete}'s own Javadoc) - it stays labelled "Delete project" and could, in principle, be clicked by hand
 * in that window too, which would land on {@link #handleDelete}'s confirm-text check and just re-show the plan
 * (the confirm/option fields are not rendered while running - see {@code ProjectDeletePage.render}'s early
 * return) rather than doing anything destructive; a real fix (hiding/disabling it) was judged not worth
 * building for this lot.
 */
public final class ProjectDeleteUserService implements UserService<TableViewEntitySelection>
{
	static final String SUBMIT_ID = "mdmfDelete";

	Adaptation factory;
	Locale locale = Locale.ENGLISH;
	/** The session of the last rendering: the deletion plan reads the matching configuration as its user (lot 3). */
	private com.orchestranetworks.service.Session session;

	/** {@code Project.code} formatted primary key of the resolved project; null when none could be resolved at all. */
	String projectId;
	/** Guards the one-time read of the {@code project} launch parameter - see {@code ProjectWizardUserService#setupObjectContext}'s own comment for why this must not re-run on every postback. */
	boolean urlProjectChecked;
	String unknownProjectCode;

	ProjectDeletePage.Model lastResult;

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
			// launched from the toolbar/perspective/explorer with nothing selected
		}
		if (selected.size() == 1 && table != null && FactoryPaths.PROJECT.format().equals(table.getTablePath().format()))
		{
			// second permissions pass (2026-10-14): a project the user cannot see is answered like an unknown code
			if (ProjectAccess.projectOpens(this.session, this.factory, selected.get(0)))
			{
				this.projectId = selected.get(0).getOccurrencePrimaryKey().format();
				return;
			}
			this.unknownProjectCode = selected.get(0).getOccurrencePrimaryKey().format();
			return;
		}
		if (!this.urlProjectChecked)
		{
			this.urlProjectChecked = true;
			final String urlProjectCode = AbstractRecordLineageUserService.param(pContext, "project");
			if (!urlProjectCode.isEmpty())
			{
				final AdaptationTable projects = this.factory.getTable(FactoryPaths.PROJECT);
				// second permissions pass (2026-10-14): with the user's own view - a hidden project reads like an unknown code
				final Adaptation resolved = ProjectAccess.openProject(this.session, this.factory, projects.computePrimaryKey(new Object[] { urlProjectCode }).format());
				if (resolved != null)
				{
					this.projectId = resolved.getOccurrencePrimaryKey().format();
					return;
				}
				this.unknownProjectCode = urlProjectCode;
			}
		}
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		this.session = pContext.getSession();
		pConfigurator.setTitle(Messages.get(ProjectDeleteUserService.class, this.locale, "delete.title"));
		// second permissions pass (2026-10-14): the dataspace, the dataset, the Project table and this service's own permission on it
		String closed = ProjectAccess.refusal(this.session, this.factory, this.locale);
		if (closed == null && !ProjectAccess.serviceOnTable(this.session, ProjectDeleteServiceDeclaration.SERVICE_KEY, this.factory.getTable(FactoryPaths.PROJECT)))
		{
			closed = Messages.get(ProjectDeleteUserService.class, this.locale, "screen.service.refused");
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
		if (this.projectId != null && (this.lastResult == null || !this.lastResult.deleted))
		{
			// volume-operations lot 8b (2026-09-28, task 3): the SAME button now also serves the running screen's
			// own self-poll (wizard.js-style, but self-contained here - this screen has no jobs-banner infra of
			// its own) - a hidden mdmfDeleteAction field, set to "poll" by the inline script in write() and
			// otherwise blank for a real click, tells processEvent which of the two to run. See CLAUDE.md sec 9
			// "a submit button, not the AJAX endpoint" for why this is a form field/button, not a fetch().
			final UIButtonSpecSubmit submit = pConfigurator.newSubmitButton(Messages.get(ProjectDeleteUserService.class, this.locale, "delete.button"),
				new UserServiceEvent()
				{
					@Override
					public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
					{
						if ("poll".equals(pEventContext.getParameter("mdmfDeleteAction")))
						{
							ProjectDeleteUserService.this.pollDelete(pEventContext);
						}
						else
						{
							ProjectDeleteUserService.this.handleDelete(pEventContext);
						}
						return null;
					}
				});
			submit.setId(ProjectDeleteUserService.SUBMIT_ID);
			pConfigurator.setLeftButtons(submit);
		}
		final UIButtonSpecNavigation close = pConfigurator.newCloseButton();
		close.setAskBeforeLeavingModifiedForm(false);
		pConfigurator.setRightButtons(close);
		// audit F-41: an unplanned failure of the rendering becomes a localized notice with a logged reference, never a blank pane
		pConfigurator.setContent(ScreenFailure.guard("project deletion", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				ProjectDeleteUserService.this.write(pWriter);
			}
		}));
	}

	// ------------------------------------------------------------------------------------------------------- the event

	/**
	 * Re-checks the administrator role (a permission rule only hides the toolbar/perspective action, it does
	 * not stop a direct request - same rule {@code FactoryConnectionUserService#test} already follows), a
	 * cheap confirm-text pre-check (the same ceremony {@code ProjectDeletionPlan#validate} itself applies -
	 * kept here too, synchronously, since {@link ProjectDeleteRun#start} no longer validates before enqueuing:
	 * a mistyped project code would otherwise only surface after a full queue/run/poll round trip instead of
	 * at once), then enqueues the deletion as a {@link ProjectDeleteRun} job instead of calling {@link
	 * ProjectDeleter#delete} directly (volume-operations lot 8b, 2026-09-28, task 3). The panel switches to a
	 * plain "running" notice ({@link ProjectDeletePage.Model#running}); {@link #pollDelete} (driven by
	 * {@code write}'s own inline self-poll script - this screen has no jobs-banner infrastructure of its own to
	 * reuse, unlike {@code ProjectWizardUserService}) picks the report back up once the job ends.
	 */
	void handleDelete(final UserServiceEventContext pContext)
	{
		final ProjectDeletePage.Model model = new ProjectDeletePage.Model();
		model.locale = this.locale;
		if (!pContext.getSession().isUserInRole(Profile.ADMINISTRATOR))
		{
			model.error = Messages.get(ProjectDeleteUserService.class, this.locale, "delete.forbidden");
			this.lastResult = model;
			return;
		}
		final Adaptation project = this.projectRow();
		// second permissions pass (2026-10-14): the record must still be visible to this user, and EBX must let them delete it. The deletion job
		// itself then runs as the system user and removes every generated object of the project, hidden rows included - it is the module's own
		// cleanup of what it generated, allowed here by this check
		if (project == null)
		{
			model.error = Messages.get(ProjectDeleteUserService.class, this.locale, "screen.project.unknown");
			this.lastResult = model;
			return;
		}
		if (!com.on.ebx.factory.ebx.UserView.of(this.session).recordDeletable(project)
			|| ProjectAccess.refusal(this.session, this.factory, this.locale) != null
			|| !ProjectAccess.serviceOnTable(this.session, ProjectDeleteServiceDeclaration.SERVICE_KEY, this.factory.getTable(FactoryPaths.PROJECT)))
		{
			model.error = Messages.get(ProjectDeleteUserService.class, this.locale, "screen.noDelete");
			this.lastResult = model;
			return;
		}
		// the key IS the code (Project's primary key is /code): read from the key, so a hidden code field cannot block the confirmation
		final String projectCode = project.getOccurrencePrimaryKey().format();
		model.projectCode = projectCode;
		model.projectLabel = com.on.ebx.factory.ebx.UserView.of(this.session).text(project, FactoryPaths.Project.LABEL);

		final Set<String> options = new LinkedHashSet<String>();
		final String[] posted = pContext.getParameterValues("mdmfGenerated");
		if (posted != null)
		{
			for (final String value : posted)
			{
				if (value != null && !value.trim().isEmpty())
				{
					options.add(value.trim());
				}
			}
		}
		final String confirm = pContext.getParameter("mdmfConfirm");

		if (confirm == null || !confirm.equals(projectCode))
		{
			model.error = "Pass ?confirm=" + projectCode + " (the exact project code) to actually delete project " + projectCode + " - this cannot be undone.";
			try
			{
				ProjectDeleteUserService.fillFromPlan(model, ProjectDeleter.plan(this.session, this.factory, projectCode));
			}
			catch (final OperationException ex2)
			{
				// best effort only
			}
			this.lastResult = model;
			return;
		}

		try
		{
			final ObjectNode queued = ProjectDeleteRun.start(pContext.getSession(), this.factory, projectCode, options, confirm);
			model.running = true;
			model.jobId = queued.path("jobId").asText("");
		}
		catch (final OperationException ex)
		{
			model.error = EngineNotice.inline(ex, this.locale);
			try
			{
				ProjectDeleteUserService.fillFromPlan(model, ProjectDeleter.plan(this.session, this.factory, projectCode));
			}
			catch (final OperationException ex2)
			{
				// best effort only - the plan re-read itself failed too; keep the delete's own error, nothing more to show
			}
		}
		this.lastResult = model;
	}

	/**
	 * Volume-operations lot 8b (2026-09-28, task 3): the "running" screen's own self-poll target, clicked by
	 * {@code write}'s inline script every few seconds while {@code mdmfDeleteRunning} is on the page. Reads
	 * {@link ProjectDeleteRun#status} for the job {@link #lastResult}'s own {@code jobId} carries - looked up by
	 * the job's OWN primary key, which survives a successful delete even though the {@code Project} row itself
	 * does not (see that method's own Javadoc). Still QUEUED/RUNNING: nothing changes, the running notice stays;
	 * {@code DONE}: switches to the (simplified - see {@code ProjectDeleteRun}'s own Javadoc for why) success
	 * report; anything else ({@code FAILED}/{@code CANCELLED}, or the job somehow not found) shows the job's own
	 * message as the error and re-plans, same "re-plan on failure" idiom {@link #handleDelete} already uses.
	 */
	void pollDelete(final UserServiceEventContext pContext)
	{
		if (this.lastResult == null || !this.lastResult.running || this.lastResult.jobId.isEmpty())
		{
			return; // a stray/late poll click - nothing to check
		}
		if (!pContext.getSession().isUserInRole(Profile.ADMINISTRATOR))
		{
			return; // same guard as handleDelete - the button is otherwise only ever driven by the script below
		}
		final ObjectNode status = ProjectDeleteRun.status(this.factory, this.lastResult.jobId);
		if (status.path("running").asBoolean(true))
		{
			return; // still queued/running (or, defensively, the job could not be read at all) - keep waiting
		}
		final String jobStatus = status.path("status").asText("");
		if ("DONE".equals(jobStatus))
		{
			final ProjectDeletePage.Model model = new ProjectDeletePage.Model();
			model.locale = this.locale;
			model.projectCode = this.lastResult.projectCode;
			model.projectLabel = this.lastResult.projectLabel;
			model.deleted = true;
			model.doneMessage = status.path("message").asText("");
			model.explorerUrl = FactoryLinks.explorerUrl(this.factory.getHome().getKey().format(), this.factory.getAdaptationName().getStringName());
			this.lastResult = model;
			return;
		}
		final ProjectDeletePage.Model model = new ProjectDeletePage.Model();
		model.locale = this.locale;
		model.error = status.path("message").asText(Messages.get(ProjectDeleteUserService.class, this.locale, "delete.running.failed"));
		try
		{
			ProjectDeleteUserService.fillFromPlan(model, ProjectDeleter.plan(this.session, this.factory, this.lastResult.projectCode));
		}
		catch (final OperationException ex2)
		{
			// best effort only - the plan re-read itself failed too; keep the job's own error, nothing more to show
		}
		this.lastResult = model;
	}

	// ------------------------------------------------------------------------------------------------------- the page

	void write(final UserServicePaneWriter pWriter)
	{
		// fix/review-assets (2026-09-21) convention: Assets.version() changes with every build.sh run
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add(ProjectDeletePage.render(this.model()));
		// volume-operations lot 8b (2026-09-28, task 3): the hidden field processEvent reads to tell a real
		// "Delete project" click from the self-poll below (CLAUDE.md sec 9: a form field posted by the SAME
		// submit button, never a fetch() to an AJAX endpoint).
		pWriter.add("<input type=\"hidden\" id=\"mdmfDeleteAction\" name=\"mdmfDeleteAction\" value=\"\"/>");
		// same contextual-return as Wizard/Cockpit (fix/explorer-links-in-perspective, 2026-09-23): the explorer's
		// own originCaptureScript arms every .mdmc-btn link, this screen's Delete button included, so Close should
		// return there too instead of landing on the native Project table - a no-op when opened any other way.
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session)));
		// leave-guard lot (2026-09-23): the "back to explorer" link shown once deletion is confirmed - a plain
		// full-page link, not part of the return-to-explorer token scheme above (deletion always ends here, there
		// is nothing to come back TO on this same screen) - only needs the voluntary-leave marking.
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link"));
		// volume-operations lot 8b (2026-09-28, task 3): the running screen's own self-poll - this standalone
		// screen has no jobs-banner infrastructure of its own to reuse (ProjectWizardUserService's is tied to
		// the wizard's own .mdmf-wizard root/mdmfAction state machine), so a small, self-contained equivalent:
		// while the "running" notice is on the page, click the SAME submit button every few seconds with
		// mdmfDeleteAction=poll - re-armed on every re-render for as long as the notice keeps coming back.
		pWriter.addJS_cr("(function(){var r=document.getElementById('mdmfDeleteRunning');if(!r)return;"
			+ "var a=document.getElementById('mdmfDeleteAction');var b=document.getElementById('" + ProjectDeleteUserService.SUBMIT_ID + "');"
			+ "if(!a||!b)return;window.setTimeout(function(){a.value='poll';b.click();},3000);})();");
	}

	ProjectDeletePage.Model model()
	{
		if (this.lastResult != null)
		{
			return this.lastResult;
		}
		final ProjectDeletePage.Model model = new ProjectDeletePage.Model();
		model.locale = this.locale;
		if (this.projectId == null)
		{
			model.error = this.unknownProjectCode != null
				? Messages.get(ProjectDeleteUserService.class, this.locale, "delete.notice.unknownProjectCode", this.unknownProjectCode)
				: Messages.get(ProjectDeleteUserService.class, this.locale, "delete.notice.noProject");
			return model;
		}
		final Adaptation project = this.projectRow();
		if (project == null)
		{
			model.error = Messages.get(ProjectDeleteUserService.class, this.locale, "delete.notice.noProject");
			return model;
		}
		model.projectCode = project.getOccurrencePrimaryKey().format();
		model.projectLabel = com.on.ebx.factory.ebx.UserView.of(this.session).text(project, FactoryPaths.Project.LABEL);
		try
		{
			ProjectDeleteUserService.fillFromPlan(model, ProjectDeleter.plan(this.session, this.factory, model.projectCode));
		}
		catch (final OperationException ex)
		{
			model.error = EngineNotice.inline(ex, this.locale);
		}
		return model;
	}

	private Adaptation projectRow()
	{
		if (this.projectId == null)
		{
			return null;
		}
		// second permissions pass (2026-10-14): read with the user's view at each use - null for a deleted OR a hidden project
		return ProjectAccess.openProject(this.session, this.factory, this.projectId);
	}

	// ------------------------------------------------------------------------------------------------- JSON -> model

	private static void fillFromPlan(final ProjectDeletePage.Model pModel, final JsonNode pPlanJson)
	{
		pModel.projectCode = pPlanJson.path("project").asText(pModel.projectCode);
		pModel.projectLabel = pPlanJson.path("label").asText(pModel.projectLabel);
		pModel.delivered = pPlanJson.path("delivered").asBoolean(false);
		pModel.items.clear();
		for (final JsonNode itemNode : pPlanJson.path("items"))
		{
			final ProjectDeletePage.Item item = new ProjectDeletePage.Item();
			item.kind = itemNode.path("kind").asText("");
			item.option = itemNode.path("option").asText("");
			item.name = itemNode.path("name").asText("");
			item.detail = itemNode.path("detail").asText("");
			item.eligible = itemNode.path("eligible").asBoolean(false);
			item.reason = itemNode.path("reason").asText("");
			pModel.items.add(item);
		}
		pModel.rowCounts.clear();
		ProjectDeleteUserService.fillRowCounts(pModel, pPlanJson.path("rowCounts"));
	}

	// volume-operations lot 8b (2026-09-28, task 3): fillFromDeleteReport (the old synchronous ProjectDeleter.delete
	// -> full generated[]/outcome report reader) was removed here - handleDelete no longer calls delete() directly,
	// and pollDelete has no such JSON to read back (ProjectDeleteRun's own Javadoc explains why). fillFromPlan/
	// fillRowCounts below are still used (the plan is re-read on every refusal path, sync or async).

	private static void fillRowCounts(final ProjectDeletePage.Model pModel, final JsonNode pRowCounts)
	{
		final Iterator<String> fieldNames = pRowCounts.fieldNames();
		while (fieldNames.hasNext())
		{
			final String key = fieldNames.next();
			pModel.rowCounts.put(key, Integer.valueOf(pRowCounts.path(key).asInt(0)));
		}
	}

	// ------------------------------------------------------------------------------------------------------------ misc

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// handleDelete reports its own errors; nothing to check ahead of the event
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
