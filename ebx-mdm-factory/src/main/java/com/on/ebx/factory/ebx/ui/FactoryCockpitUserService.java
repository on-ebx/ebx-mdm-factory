package com.on.ebx.factory.ebx.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.ProjectOrigin;
import com.on.ebx.factory.review.ReviewEntityFormat;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/**
 * Reads the {@code Project} record and everything the cockpit reports about it (sources, pipeline steps,
 * landing tables, relations, staging entities, transfer rejects, quality rules, agent runs) into the plain value objects
 * {@link CockpitData} takes, then renders {@link CockpitPage}. Every table read here belongs to the
 * factory's own dataset, with the caller's own rights (second permissions pass, 2026-10-14: the project record, the service permission, every row through
 * {@code UserView#rows} and every field through {@code UserView#text}/{@code #value}, so a hidden row is not counted and a hidden field is shown empty) — no MCP call, no agent account, unlike
 * {@code FactoryConnectionUserService}'s test button.
 * <p>
 * {@link #resolveSourceSystem} duplicates a short walk {@code com.on.ebx.factory.ebx.ProposalReviews} also
 * does (entity - attribute - mapping - landing column - landing table - project source), rather than reusing
 * it: that method is {@code private} in a class owned by a different lot's work in this same release, so
 * this stays a self-contained, if slightly repeated, thin reader.
 */
public final class FactoryCockpitUserService implements UserService<RecordEntitySelection>
{
	Adaptation project;
	Adaptation dataset;
	Locale locale = Locale.ENGLISH;
	/** Audit F-43: who looks at the Cockpit - the Recent tasks list offers "Run again" (in the wizard) only for a kind they may queue. */
	com.orchestranetworks.service.Session session;
	/** Second permissions pass (2026-10-14): every read below goes through the user's own view (records and fields EBX lets this user see). */
	com.on.ebx.factory.ebx.UserView view = com.on.ebx.factory.ebx.UserView.ENGINE;
	/** Null when the user may open this project's Cockpit; otherwise what the pane says instead (the same text for a hidden and an unknown project). */
	String closed;

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
		this.view = com.on.ebx.factory.ebx.UserView.of(this.session);
		// second permissions pass (2026-10-14): a record service can be opened by URL on any key - the dataset, the record and the service
		// permission are checked here, and a project the user cannot see reads exactly like an unknown one
		final String datasetRefusal = ProjectAccess.refusal(this.session, this.dataset, this.locale);
		if (datasetRefusal != null)
		{
			this.closed = datasetRefusal;
		}
		else if (!ProjectAccess.projectOpens(this.session, this.dataset, this.project))
		{
			this.closed = Messages.get(FactoryCockpitUserService.class, this.locale, "screen.project.unknown");
		}
		else if (!ProjectAccess.serviceOnRecord(this.session, FactoryCockpitServiceDeclaration.SERVICE_KEY, this.project))
		{
			this.closed = Messages.get(FactoryCockpitUserService.class, this.locale, "screen.service.refused");
		}
		pConfigurator.setTitle(Messages.get(FactoryCockpitUserService.class, this.locale, "cockpit.title", this.closed != null ? "" : this.view.text(this.project, FactoryPaths.Project.CODE)));
		pConfigurator.setRightButtons(pConfigurator.newCloseButton());
		if (this.closed != null)
		{
			pConfigurator.setContent(new UserServicePane()
			{
				@Override
				public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
				{
					pWriter.add(ThemeTokens.styleTagFor(pPaneContext));
					FactoryHtml.writeClosed(pWriter, FactoryCockpitUserService.this.closed);
				}
			});
			return;
		}
		// audit F-41: an unplanned failure of the rendering becomes a localized notice with a logged reference, never a blank pane
		pConfigurator.setContent(ScreenFailure.guard("cockpit", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				FactoryCockpitUserService.this.write(pWriter);
			}
		}));
	}

	void write(final UserServicePaneWriter pWriter)
	{
		// fix/review-assets (2026-09-21): Assets.version() changes with every build.sh run - see its own Javadoc
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		final CockpitPage.Model model = new CockpitPage.Model();
		model.locale = this.locale;

		final CockpitData.Inputs in = new CockpitData.Inputs();
		in.now = model.now;
		in.project = this.readProject();
		in.sources = this.readSources();
		in.steps = this.readSteps();
		in.tables = this.readTables();
		in.relations = this.readRelations();
		in.entities = this.readEntities();
		in.rejects = this.readRejects();
		// lot volume-4 (2026-09-28, task brief item 8): the REAL unresolved total, a bounded XPath count
		// (WizardSummaryReader#unresolvedRejectCount, same table/predicate this class' own readRejects() used to
		// derive by counting every materialized row) - readRejects() itself now only samples up to
		// REJECTS_READ_CAP rows, so Snapshot#rejects must come from here, never from counting in.rejects.
		in.unresolvedRejectsTotal = Integer.valueOf(WizardSummaryReader.unresolvedRejectCount(this.view, this.dataset, this.projectId()));
		in.rejectSummaries = this.readRejectSummaries();
		in.rejectSummaryPartial = in.rejectSummaries == null;
		in.qualityRules = this.readQualityRules();
		in.matchPlans = this.readMatchPlans();
		in.lastAgentRun = this.readLastAgentRun();
		in.stagingDatasetExists = !this.view.text(this.project, FactoryPaths.Project.STAGING_DATASET).isEmpty();
		// delivery lot 2 (docs/DELIVERY.md sec 6.2): ProjectOrigin reads the same DEFAULT settings row WizardData
		// and the suggest* routes already read - one instanceId/role, no separate lookup here
		in.instanceId = ProjectOrigin.instanceId(this.dataset);
		in.instanceRole = ProjectOrigin.role(this.dataset);

		model.rejectsUrl = FactoryLinks.rejectsUrl(this.dataset.getHome().getKey().format(), this.dataset.getAdaptationName().getStringName(),
			this.projectId());
		// audit F-30: the project's backup, for an administrator (the route asks the same); a plain GET, the browser downloads it with its EBX session
		if (com.on.ebx.factory.ebx.FactoryAccess.has(this.session, com.on.ebx.factory.core.FactoryRight.ADMINISTRATOR))
		{
			model.backupUrl = FactoryCockpitUserService.backupUrl(this.projectId());
		}
		model.snapshot = CockpitData.compute(in);
		pWriter.add(CockpitPage.render(model));
		// audit F-43: the project's last tasks and how they ended, each with a link to its step of the wizard (where "Run again" is)
		final String dataspaceKey = this.dataset.getHome().getKey().format();
		final String datasetName = this.dataset.getAdaptationName().getStringName();
		final String projectCode = this.projectId();
		final String recent = com.on.ebx.factory.wizard.RecentTasks.render(
			com.on.ebx.factory.ebx.FactoryJobStatus.recentTasks(this.dataset, this.projectId(), ProjectWizardUserService.RECENT_TASKS, this.session), this.locale,
			model.now, pStep -> FactoryLinks.wizardUrl(dataspaceKey, datasetName, projectCode, pStep));
		if (!recent.isEmpty())
		{
			pWriter.add("<div class=\"mdmf mdmf-cockpit-recent\">").add(recent).add("</div>");
		}
		// project explorer lot, contextual return (2026-09-23): sends Close back to the explorer when this
		// cockpit was reached from one of its Cockpit links - see FactoryHtml's own Javadoc
		pWriter.addJS_cr(FactoryHtml.originReturnScript(FactoryHtml.userId(this.session)));
		// leave-guard lot (2026-09-23): the Cockpit's own "Record lineage" link (CockpitPage.lineageLink) - a
		// plain full-page link, unrelated to the return-to-explorer token above - only needs the voluntary-leave
		// marking so it never triggers EBX's "Quitter le site Web ?" prompt.
		pWriter.addJS_cr(FactoryHtml.voluntaryLeaveScript(".mdmf-leave-link"));
	}

	// ------------------------------------------------------------------ reading the factory dataset

	private CockpitData.ProjectInfo readProject()
	{
		final CockpitData.ProjectInfo out = new CockpitData.ProjectInfo();
		out.code = this.view.text(this.project, FactoryPaths.Project.CODE);
		out.label = this.view.text(this.project, FactoryPaths.Project.LABEL);
		if (out.label.isEmpty())
		{
			out.label = out.code;
		}
		out.mode = this.view.text(this.project, FactoryPaths.Project.MODE);
		out.status = this.view.text(this.project, FactoryPaths.Project.STATUS);
		out.originRepository = this.view.text(this.project, FactoryPaths.Project.ORIGIN_REPOSITORY);
		return out;
	}

	private String projectId()
	{
		return this.project.getOccurrencePrimaryKey().format();
	}

	private List<CockpitData.SourceInfo> readSources()
	{
		final List<CockpitData.SourceInfo> out = new ArrayList<CockpitData.SourceInfo>();
		final RequestResult rows = this.view.rows(this.dataset.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + FactoryHtml.quote(this.projectId()));
		for (final Adaptation row : FactoryHtml.rows(rows))
		{
			final CockpitData.SourceInfo source = new CockpitData.SourceInfo();
			source.id = row.getOccurrencePrimaryKey().format();
			source.sourceSystem = this.view.text(row, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
			source.status = this.view.text(row, FactoryPaths.ProjectSource.STATUS);
			out.add(source);
		}
		return out;
	}

	/**
	 * Audit F-36: the rows are streamed through a {@link StepDigest} - one entry per (scope, step) pair, the latest run with its run count, which is everything
	 * {@code CockpitData} shows of them - instead of being materialised: a project that has run for months holds thousands of rows. Nothing the screen shows changes.
	 */
	private List<CockpitData.StepInfo> readSteps()
	{
		final StepDigest digest = new StepDigest();
		final RequestResult rows = this.view.rows(this.dataset.getTable(FactoryPaths.PIPELINE_STEP), "./project=" + FactoryHtml.quote(this.projectId()));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final CockpitData.StepInfo step = new CockpitData.StepInfo();
				step.id = row.getOccurrencePrimaryKey().format();
				step.step = this.view.text(row, FactoryPaths.PipelineStep.STEP);
				step.scope = this.view.text(row, FactoryPaths.PipelineStep.SCOPE);
				step.status = this.view.text(row, FactoryPaths.PipelineStep.STATUS);
				step.message = this.view.text(row, FactoryPaths.PipelineStep.MESSAGE);
				step.startedAt = (java.util.Date) this.view.value(row, FactoryPaths.PipelineStep.STARTED_AT);
				step.endedAt = (java.util.Date) this.view.value(row, FactoryPaths.PipelineStep.ENDED_AT);
				digest.offer(step);
			}
		}
		finally
		{
			rows.close();
		}
		return digest.result();
	}

	private List<CockpitData.TableInfo> readTables()
	{
		final List<CockpitData.TableInfo> out = new ArrayList<CockpitData.TableInfo>();
		for (final CockpitData.SourceInfo source : this.readSources())
		{
			final RequestResult rows = this.view.rows(this.dataset.getTable(FactoryPaths.LANDING_TABLE), "./projectSource=" + FactoryHtml.quote(source.id));
			for (final Adaptation row : FactoryHtml.rows(rows))
			{
				final CockpitData.TableInfo table = new CockpitData.TableInfo();
				table.id = row.getOccurrencePrimaryKey().format();
				table.sourceSystem = source.sourceSystem;
				table.sourceObject = this.view.text(row, FactoryPaths.LandingTable.SOURCE_OBJECT);
				table.tableName = this.view.text(row, FactoryPaths.LandingTable.TABLE_NAME);
				table.status = this.view.text(row, FactoryPaths.LandingTable.STATUS);
				table.sourceRowCount = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.LandingTable.SOURCE_ROW_COUNT));
				table.lastLoadId = this.view.text(row, FactoryPaths.LandingTable.LAST_LOAD_ID);
				table.lastLoadAt = (java.util.Date) this.view.value(row, FactoryPaths.LandingTable.LAST_LOAD_AT);
				table.lastLoadRows = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.LandingTable.LAST_LOAD_ROWS));
				table.message = this.view.text(row, FactoryPaths.LandingTable.MESSAGE);
				out.add(table);
			}
		}
		return out;
	}

	private List<CockpitData.RelationInfo> readRelations()
	{
		final List<CockpitData.RelationInfo> out = new ArrayList<CockpitData.RelationInfo>();
		for (final CockpitData.SourceInfo source : this.readSources())
		{
			final RequestResult rows = this.view.rows(this.dataset.getTable(FactoryPaths.LANDING_RELATION), "./projectSource=" + FactoryHtml.quote(source.id));
			for (final Adaptation row : FactoryHtml.rows(rows))
			{
				final CockpitData.RelationInfo relation = new CockpitData.RelationInfo();
				relation.fromTable = this.view.text(row, FactoryPaths.LandingRelation.FROM_TABLE);
				relation.toTable = this.view.text(row, FactoryPaths.LandingRelation.TO_TABLE);
				relation.status = this.view.text(row, FactoryPaths.LandingRelation.STATUS);
				relation.checkedAt = (java.util.Date) this.view.value(row, FactoryPaths.LandingRelation.CHECKED_AT);
				relation.childValues = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.LandingRelation.CHILD_VALUES));
				relation.orphanValues = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.LandingRelation.ORPHAN_VALUES));
				out.add(relation);
			}
		}
		return out;
	}

	private List<CockpitData.EntityInfo> readEntities()
	{
		final List<CockpitData.EntityInfo> out = new ArrayList<CockpitData.EntityInfo>();
		final RequestResult rows = this.view.rows(this.dataset.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + FactoryHtml.quote(this.projectId()));
		for (final Adaptation row : FactoryHtml.rows(rows))
		{
			final CockpitData.EntityInfo entity = new CockpitData.EntityInfo();
			entity.id = row.getOccurrencePrimaryKey().format();
			entity.name = this.view.text(row, FactoryPaths.StagingEntity.NAME);
			entity.status = this.view.text(row, FactoryPaths.StagingEntity.STATUS);
			entity.lastLoadAt = (java.util.Date) this.view.value(row, FactoryPaths.StagingEntity.LAST_LOAD_AT);
			entity.lastLoadRead = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.StagingEntity.LAST_LOAD_READ));
			entity.lastLoadWritten = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.StagingEntity.LAST_LOAD_WRITTEN));
			entity.lastLoadSkipped = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.StagingEntity.LAST_LOAD_SKIPPED));
			entity.lastLoadRejected = FactoryCockpitUserService.asLong(this.view.value(row, FactoryPaths.StagingEntity.LAST_LOAD_REJECTED));
			entity.lastLoadMessage = this.view.text(row, FactoryPaths.StagingEntity.LAST_LOAD_MESSAGE);
			final List<?> rawMemberIds = this.view.list(row, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					final String memberId = String.valueOf(rawMemberId);
					final Adaptation memberRow = this.lookup(FactoryPaths.STAGING_ENTITY, memberId);
					final String memberName = memberRow == null ? memberId : this.view.text(memberRow, FactoryPaths.StagingEntity.NAME);
					entity.members.add(new ReviewEntityFormat.Member(memberName, this.resolveSourceSystem(memberId)));
				}
			}
			out.add(entity);
		}
		return out;
	}

	/** Bound on how many {@code TransferReject} rows {@link #readRejects} ever materializes - {@code CockpitData.Snapshot#latestRejects} only ever shows 10 (wave 10, audit F-38: the per-(entity, stage) breakdown is no longer derived from this sample, it is an aggregate SQL query - {@code ebx.RejectReview#summary}); {@code CockpitData.Snapshot#rejects} (the KPI tile) is wired from {@code CockpitData.Inputs#unresolvedRejectsTotal} instead (set right after this method is called, in {@link #write}), never from counting this list - see that field's own Javadoc. */
	private static final int REJECTS_READ_CAP = 10;

	/**
	 * Lot volume-4 (2026-09-28, task brief item 8): reads at most {@link #REJECTS_READ_CAP} rows, most recent
	 * first, instead of the whole project's reject history - a real feed can reject far more rows than any
	 * Cockpit screen renders. Entity names are resolved ONCE per DISTINCT entity id ({@code entityNameById}),
	 * never once per reject row - a project's rejects are typically concentrated on a handful of entities, so
	 * this turns what used to be up to {@link #REJECTS_READ_CAP} (or more, pre-lot) {@link #lookup} calls into
	 * at most a few.
	 * <p>
	 * Volume lot 3 (2026-09-28, task 6): sorts by {@code rejectedAt} descending directly - {@code
	 * TransferReject.id} is gone (the primary key is now the natural key {@code (project, entity, stage,
	 * sourceSystem, sourceId)}, see {@code tools/build_xsd.py}), and {@code rejectedAt} is the genuinely correct
	 * "most recent" field anyway (the old comment called {@code id} descending "the exact same order... without
	 * needing to fetch every row first to sort them" - true of an auto-increment id, no longer applicable).
	 */
	private List<CockpitData.RejectInfo> readRejects()
	{
		// robustness audit F2-113: ONE bounded SQL query (ORDER BY ... FETCH NEXT 10 ROWS ONLY, the text of the review screen's page) - the database sorts and cuts,
		// where the XPath request sorted every reject of the project (a field with no index) to keep ten
		final List<CockpitData.RejectInfo> out = new ArrayList<CockpitData.RejectInfo>();
		final List<com.on.ebx.factory.rejects.RejectRow> rows;
		try
		{
			rows = com.on.ebx.factory.ebx.RejectReview.page(this.session, this.dataset, this.projectId(), new com.on.ebx.factory.rejects.RejectFilter(), 0,
				FactoryCockpitUserService.REJECTS_READ_CAP);
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().warn("[mdm-factory] cockpit: the latest rejects could not be read (" + ex.getMessage() + "). Query: "
				+ FactoryCockpitUserService.latestRejectsSql(this.projectId()));
			return out;
		}
		for (final com.on.ebx.factory.rejects.RejectRow row : rows)
		{
			final CockpitData.RejectInfo reject = new CockpitData.RejectInfo();
			reject.entityName = row.entityName;
			reject.stage = row.stage;
			reject.sourceSystem = row.sourceSystem;
			reject.sourceId = row.sourceId;
			// staging load rejects (2026-10-17): the coded reasons in the viewer's language (a text with no marker is shown as it is, cleaned of the double escaping)
			final StringBuilder worded = new StringBuilder();
			for (final com.on.ebx.factory.rejects.RejectReasons.Shown shown : com.on.ebx.factory.rejects.RejectReasons.read(row.reasons, row.category, "", this.locale))
			{
				worded.append(worded.length() > 0 ? "; " : "").append(shown.sentence);
			}
			reject.reasons = worded.toString();
			reject.rejectedAt = row.rejectedAt;
			reject.resolved = row.resolved;
			reject.acknowledged = row.acknowledged;
			out.add(reject);
		}
		return out;
	}

	/** Pure: the query {@link #readRejects} runs - the page of the review screen, first ten rows. */
	static String latestRejectsSql(final String pProjectId)
	{
		return com.on.ebx.factory.rejects.RejectSql.page(pProjectId, new com.on.ebx.factory.rejects.RejectFilter(), 0, FactoryCockpitUserService.REJECTS_READ_CAP);
	}

	/** Pure: the query {@link #readLastAgentRun} runs - the project's most recent run, cut by the database. */
	static String lastAgentRunSql(final String pProjectId)
	{
		return "SELECT t.\"phase\" AS PHS, t.\"status\" AS STA, t.\"steps\" AS STP, t.\"inputTokens\" AS ITK, t.\"outputTokens\" AS OTK, t.\"startedAt\" AS SAT, t.\"endedAt\" AS EAT "
			+ "FROM \"AgentRun\" t WHERE t.\"project\".\"code\" = " + com.on.ebx.factory.rejects.RejectSql.literal(pProjectId)
			+ " ORDER BY t.\"startedAt\" DESC, t.\"id\" DESC FETCH NEXT 1 ROWS ONLY";
	}

	/**
	 * Audit F-38 (wave 10): the open rejects per (entity, stage) from ONE aggregate SQL query ({@code ebx.RejectReview#summary}), not from counting a sample of rows;
	 * null when the query cannot run (the cockpit then falls back to the sample, as before).
	 */
	private List<CockpitData.RejectSummary> readRejectSummaries()
	{
		try
		{
			final List<CockpitData.RejectSummary> out = new ArrayList<CockpitData.RejectSummary>();
			for (final com.on.ebx.factory.ebx.RejectReview.Summary summary : com.on.ebx.factory.ebx.RejectReview.summary(this.session, this.dataset,
				this.projectId()))
			{
				out.add(new CockpitData.RejectSummary(summary.entityName, summary.stage, (int) Math.min(Integer.MAX_VALUE, summary.open)));
			}
			return out;
		}
		catch (final com.orchestranetworks.service.OperationException ex)
		{
			// robustness audit F2-105: never a silent fall back to the sample - the log keeps the query and the cause, the screen says the breakdown is partial
			com.orchestranetworks.service.LoggingCategory.getKernel().warn("[mdm-factory] cockpit: the unresolved-rejects breakdown could not be read ("
				+ ex.getMessage() + "), showing the latest rejects only. Query: " + com.on.ebx.factory.rejects.RejectSql.summary(this.projectId()));
			return null;
		}
	}

	/**
	 * Task C: every {@code QualityRule} of this project's own entities - {@code QualityRule} carries no
	 * {@code project} field of its own (it is scoped through {@code entity}), so this filters by first listing
	 * the project's entity ids, the same shape {@link #readRejects}/{@link #readEntities} already use for a
	 * table one hop away from {@code Project}.
	 */
	private List<CockpitData.QualityRuleInfo> readQualityRules()
	{
		final List<CockpitData.QualityRuleInfo> out = new ArrayList<CockpitData.QualityRuleInfo>();
		final RequestResult entityRows = this.view.rows(this.dataset.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + FactoryHtml.quote(this.projectId()));
		for (final Adaptation entityRow : FactoryHtml.rows(entityRows))
		{
			final String entityId = entityRow.getOccurrencePrimaryKey().format();
			final String entityName = this.view.text(entityRow, FactoryPaths.StagingEntity.NAME);
			final RequestResult ruleRows = this.view.rows(this.dataset.getTable(FactoryPaths.QUALITY_RULE), "./entity=" + FactoryHtml.quote(entityId));
			for (final Adaptation ruleRow : FactoryHtml.rows(ruleRows))
			{
				final CockpitData.QualityRuleInfo rule = new CockpitData.QualityRuleInfo();
				rule.entityName = entityName;
				rule.status = this.view.text(ruleRow, FactoryPaths.QualityRule.STATUS);
				rule.lastLoadFailing = FactoryCockpitUserService.asLong(this.view.value(ruleRow, FactoryPaths.QualityRule.LAST_LOAD_FAILING));
				out.add(rule);
			}
		}
		return out;
	}

	/**
	 * Reject/repropose lot (2026-09-22): every {@code MatchPlan} of this project's own entities, same "list
	 * entities first" shape as {@link #readQualityRules} ({@code MatchPlan} is scoped through {@code entity},
	 * carries no {@code project} of its own).
	 */
	private List<CockpitData.MatchPlanInfo> readMatchPlans()
	{
		final List<CockpitData.MatchPlanInfo> out = new ArrayList<CockpitData.MatchPlanInfo>();
		final RequestResult entityRows = this.view.rows(this.dataset.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + FactoryHtml.quote(this.projectId()));
		for (final Adaptation entityRow : FactoryHtml.rows(entityRows))
		{
			final String entityId = entityRow.getOccurrencePrimaryKey().format();
			final String entityName = this.view.text(entityRow, FactoryPaths.StagingEntity.NAME);
			final RequestResult planRows = this.view.rows(this.dataset.getTable(FactoryPaths.MATCH_PLAN), "./entity=" + FactoryHtml.quote(entityId));
			for (final Adaptation planRow : FactoryHtml.rows(planRows))
			{
				final CockpitData.MatchPlanInfo plan = new CockpitData.MatchPlanInfo();
				plan.entityName = entityName;
				plan.status = this.view.text(planRow, FactoryPaths.MatchPlan.STATUS);
				plan.reviewComment = this.view.text(planRow, FactoryPaths.MatchPlan.REVIEW_COMMENT);
				out.add(plan);
			}
		}
		return out;
	}

	/** The project's most recent {@code AgentRun}, whichever phase; null when none has ever run. */
	private CockpitData.AgentRunInfo readLastAgentRun()
	{
		// robustness audit F2-113: the database cuts to the latest run (FETCH NEXT 1 ROWS ONLY); the 90 days of runs are no longer materialised and sorted here
		final String sql = FactoryCockpitUserService.lastAgentRunSql(this.projectId());
		try
		{
			final com.orchestranetworks.query.Query<com.orchestranetworks.query.Tuple> query = this.dataset.createQuery(sql);
			query.setSession(this.session);
			final com.orchestranetworks.query.QueryResult<com.orchestranetworks.query.Tuple> result = query.getResult();
			try
			{
				for (final com.orchestranetworks.query.Tuple tuple : result)
				{
					final CockpitData.AgentRunInfo run = new CockpitData.AgentRunInfo();
					run.phase = FactoryCockpitUserService.str(tuple.get("PHS"));
					run.status = FactoryCockpitUserService.str(tuple.get("STA"));
					final Object steps = tuple.get("STP");
					run.steps = steps instanceof Number ? Integer.valueOf(((Number) steps).intValue()) : null;
					run.inputTokens = FactoryCockpitUserService.asLong(tuple.get("ITK"));
					run.outputTokens = FactoryCockpitUserService.asLong(tuple.get("OTK"));
					run.startedAt = tuple.get("SAT") instanceof java.util.Date ? (java.util.Date) tuple.get("SAT") : null;
					run.endedAt = tuple.get("EAT") instanceof java.util.Date ? (java.util.Date) tuple.get("EAT") : null;
					return run;
				}
				return null;
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex) // includes QueryPermissionException
		{
			com.orchestranetworks.service.LoggingCategory.getKernel().warn("[mdm-factory] cockpit: the last language-model run could not be read (" + ex.getMessage()
				+ "). Query: " + sql);
			return null;
		}
	}

	private static String str(final Object pValue)
	{
		return pValue == null ? "" : String.valueOf(pValue);
	}

	/**
	 * A rule-proposed entity's source system, resolved the same way {@code StagingLlmSuggester.resolveSource}
	 * / {@code ProposalReviews.resolveSourceSystemOfEntity} do: through the first business attribute that
	 * carries a real {@code AttributeMapping} - landing column - landing table - project source. Null when
	 * none of the entity's attributes has a mapping yet, or any link along the way is missing.
	 */
	private String resolveSourceSystem(final String pEntityId)
	{
		final RequestResult attributeRows = this.view.rows(this.dataset.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + FactoryHtml.quote(pEntityId));
		for (final Adaptation attributeRow : FactoryHtml.rows(attributeRows))
		{
			final RequestResult mappingRows = this.view.rows(this.dataset.getTable(FactoryPaths.ATTRIBUTE_MAPPING),
				"./attribute=" + FactoryHtml.quote(attributeRow.getOccurrencePrimaryKey().format()));
			final List<Adaptation> mappings = FactoryHtml.rows(mappingRows);
			if (mappings.isEmpty())
			{
				continue;
			}
			final Adaptation landingColumn = this.lookup(FactoryPaths.LANDING_COLUMN, this.view.text(mappings.get(0), FactoryPaths.AttributeMapping.LANDING_COLUMN));
			final Adaptation landingTable = landingColumn == null ? null : this.lookup(FactoryPaths.LANDING_TABLE, this.view.text(landingColumn, FactoryPaths.LandingColumn.LANDING_TABLE));
			final Adaptation projectSource = landingTable == null ? null : this.lookup(FactoryPaths.PROJECT_SOURCE, this.view.text(landingTable, FactoryPaths.LandingTable.PROJECT_SOURCE));
			if (projectSource != null)
			{
				return this.view.text(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
			}
		}
		return null;
	}

	private Adaptation lookup(final com.orchestranetworks.schema.Path pTable, final String pId)
	{
		if (pId == null || pId.isEmpty())
		{
			return null;
		}
		try
		{
			return this.view.lookup(this.dataset.getTable(pTable), pId);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/** The REST route of the project's backup (audit F-30), the code percent-encoded as one path segment. */
	static String backupUrl(final String pProjectCode)
	{
		try
		{
			return "/ebx-mdm-factory/rest/v1/projects/" + java.net.URLEncoder.encode(pProjectCode, "UTF-8").replace("+", "%20") + "/backup";
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	private static Long asLong(final Object pValue)
	{
		return pValue instanceof Number ? Long.valueOf(((Number) pValue).longValue()) : null;
	}

	@Override
	public void validate(final UserServiceValidateContext<RecordEntitySelection> pContext)
	{
		// read-only: nothing posted to check
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<RecordEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
