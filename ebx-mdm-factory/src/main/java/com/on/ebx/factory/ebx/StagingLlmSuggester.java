package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.connect.core.TransformationContext;
import com.on.ebx.connect.core.Transformations;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.ForeignWording;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.Shapes;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.DateSpans;
import com.on.ebx.factory.staging.IdentityCoverage;
import com.on.ebx.factory.staging.IdentityShapes;
import com.on.ebx.factory.staging.StagingLlmPass;
import com.on.ebx.factory.staging.LandingScope;
import com.on.ebx.factory.staging.NormalisationProfile;
import com.on.ebx.factory.staging.NormalisationProfiler;
import com.on.ebx.factory.staging.ProposalChecks;
import com.on.ebx.factory.staging.StagingRules;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.on.ebx.factory.staging.TokenCase;
import com.on.ebx.factory.staging.TransformVerifier;
import com.on.ebx.factory.wizard.WizardMessages;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Runs {@link StagingLlmPass} - the semantic, "target model first" language-model pass rewritten by
 * target-mapping lot 2 (docs/TARGET-MAPPING.md sec 4.2) - over a GREENFIELD project's landing inventory,
 * through {@link AgentRuntime}. The EBX side of the work is (a) read the project's landing tables/columns/
 * relations and its rule-proposed ({@code origin=RULE}) entities into the plain value objects {@link
 * StagingLlmPass} understands - metadata only, no sample value ever leaves the instance in this pass - (b)
 * run them through {@link AgentRuntime}, giving it a {@link StagingLlmPass.Session}'s local tools as the only
 * thing the model may call, and (c) once the run is over, write whatever {@link
 * StagingLlmPass.Session#createdEntities()}/{@link StagingLlmPass.Session#createdFeeds()} accumulated as real
 * {@code StagingEntity}/{@code StagingAttribute}/{@code TransferFeed}/{@code AttributeMapping} rows (origin
 * {@code LLM}, status {@code PROPOSED}) - even a run cut short by a budget still gets whatever it validly
 * proposed before the cut. Every entity also gets its own {@code sourceSystem}/{@code sourceId} technical-key
 * attributes ({@link StagingRules#technicalAttributeProposals()}), the same as a rule-proposed one: a target
 * staging row still comes from one landing row of one source system, whichever feed wrote it.
 * <p>
 * Unlike the retired canonical-entity pass, there is no "align these two entities" step any more: two feeds
 * of the SAME target entity, one per source, are what makes two applications' data land in one place - {@link
 * StagingLlmPass.Session#finalizeEntities()} computes {@code memberEntities} (the rule-proposed entities this
 * entity's own feeds cover) structurally, from the feed tree, and this class supersedes them exactly as
 * before ({@link #supersedeMembers}, unchanged).
 */
public final class StagingLlmSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	// job-engine lot C3 (2026-09-24): package-visible (not private) so StagingSuggestLlmRun can look up the
	// PipelineStep row this method itself already opens/closes (this class performs no gating of its own on
	// pSession - see that class's own Javadoc).
	static final String STEP_STAGING_SUGGEST = "STAGING_SUGGEST";
	/** {@code PipelineStep.scope} for this pass, next to {@code StagingSuggester}'s own empty scope for the rule pass. */
	static final String STEP_SCOPE = "LLM";
	private static final String PHASE = "STAGING_SUGGEST_LLM";
	/** {@code StagingEntity.rationale} / {@code StagingAttribute.rationale} / {@code AttributeMapping.rationale} / {@code TransferFeed.rationale}: all {@code maxLen=2000} in the XSD. */
	private static final int RATIONALE_MAX_LENGTH = 2000;

	private StagingLlmSuggester()
	{
	}

	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return StagingLlmSuggester.suggest(pSession, pFactory, pProjectCode, null);
	}

	/**
	 * Fix/staging-proposal-quality (2026-10-17, item 3): {@code pLocale} is the language the labels and rationales are asked for (the launcher's
	 * UI locale, captured at enqueue time by the jobs - the job session is the system user's); {@code null} reads the session's own locale.
	 * Technical names stay English identifiers whatever the language.
	 */
	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final java.util.Locale pLocale)
		throws OperationException
	{
		// audit F-15: the pass writes the project's DESIGN proposals; the queued job already holds that resource, a REST call or a wizard action does not - see EngineClaim
		final EngineClaim claim = EngineClaim.enter(pFactory, pSession, pProjectCode, "STAGING_SUGGEST_LLM_DIRECT", FactoryJobs.DESIGN);
		try
		{
			return StagingLlmSuggester.suggestClaimed(pSession, pFactory, pProjectCode,
				pLocale != null ? pLocale : pSession == null || pSession.getLocale() == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
		finally
		{
			claim.release();
		}
	}

	private static ObjectNode suggestClaimed(final Session pSession, final Adaptation pFactory, final String pProjectCode, final java.util.Locale pLocale)
		throws OperationException
	{
		final Adaptation project = StagingLlmSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		// delivery lot 1: a project delivered here by EBX Staging is designed elsewhere - no suggestion on a target
		ProjectOrigin.refuseIfDelivered(pFactory, project);
		final String projectId = project.getOccurrencePrimaryKey().format();
		final String mode = project.getString(FactoryPaths.Project.MODE);
		if (!"GREENFIELD".equals(mode))
		{
			throw EngineRefusal.coded("conflict.llmGreenfieldOnly", "Project " + pProjectCode + " is mode " + mode + ": the language model staging pass reads the landing inventory, GREENFIELD only.", pProjectCode, mode);
		}

		// shared, one-time catch-up (ebx.MemberEntitiesBackfill's own Javadoc: the exact incident this call
		// exists to never repeat, for a row written by the RETIRED pass before memberEntities was structured)
		MemberEntitiesBackfill.run(pSession, pFactory, projectId);

		// PersVol5 (2026-09-26): the one-click proposal run profiles the landing columns AFTER this pass (StagingProposalRun,
		// stage PROFILE), so a source whose catalogue scan gave no null rate (H2, sample file) reached this pass with every
		// fillRate null: the model saw no fill rate, and unreadFilledColumns - which never reports an unprofiled column -
		// found nothing (ERP.BP_ADDRESS.HOUSE_NUMBER, filled in every row, read by no feed, was never asked about). Measure
		// the missing ones first; a table already profiled is skipped, and the profiler never throws for one table.
		LandingColumnProfiler.profile(pSession, pFactory, pProjectCode, true);

		final Map<String, String> entityIdToName = new HashMap<String, String>();
		final List<Adaptation> entityRowsList = new ArrayList<Adaptation>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				entityIdToName.put(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.StagingEntity.NAME));
				entityRowsList.add(row);
			}
		}
		finally
		{
			entityRows.close();
		}

		final Map<String, StagingRules.LandingTableInfo> tablesByKey = new LinkedHashMap<String, StagingRules.LandingTableInfo>();
		final List<StagingRules.RelationInfo> relations = new ArrayList<StagingRules.RelationInfo>();
		int sourceCount = 0;
		final int[] outOfScope = new int[1];
		final LlmDataPolicy dataPolicy = LlmDataPolicy.of(pFactory);
		final boolean shareShapes = dataPolicy.levelFor(project) != LlmDataPolicy.Level.NONE;
		final String landingDataspace = project.getString(FactoryPaths.Project.LANDING_DATASPACE);
		final Map<String, String> landingDatasetBySystem = new HashMap<String, String>();
		final RequestResult sourceRows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation source = sourceRows.nextAdaptation(); source != null; source = sourceRows.nextAdaptation())
			{
				sourceCount++;
				final String sourceId = source.getOccurrencePrimaryKey().format();
				final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				final Map<String, String> tableNameById = new HashMap<String, String>();
				final String landingDataset = source.getString(FactoryPaths.ProjectSource.LANDING_DATASET);
				landingDatasetBySystem.put(sourceSystem, landingDataset);
				for (final StagingRules.LandingTableInfo table : StagingLlmSuggester.readTables(pFactory, sourceId, sourceSystem, tableNameById, outOfScope))
				{
					tablesByKey.put(table.sourceSystem + "." + table.name, table);
					if (shareShapes)
					{
						StagingLlmSuggester.fillShapes(pSession, pFactory, dataPolicy, landingDataspace, landingDataset, table,
							dataPolicy.levelFor(project) == LlmDataPolicy.Level.SAMPLE_VALUES);
					}
				}
				relations.addAll(StagingLlmSuggester.readRelations(pFactory, sourceId, sourceSystem, tableNameById));
			}
		}
		finally
		{
			sourceRows.close();
		}
		if (tablesByKey.isEmpty())
		{
			throw EngineRefusal.coded("missing.noLandingTable", "Project " + pProjectCode + " has no landing table yet: plan and generate the landing of at least one source first.", pProjectCode);
		}

		// lot S10 (2026-10-18): the rows of each landing table and, for the columns the catalog tagged as an identity, how many distinct values they hold and how many
		// rows repeat one - counts only, one pass per table (IdentityReader); facts the prompt gives the model, never a decision
		// lot S10b: ... after the tags and tag suggestions the catalogue itself holds for those columns (applied tags are re-read live: a landing planned before the catalogue sampled
		// its source carries none), which is what decides which columns are identity columns - the catalogue's measurement, never a Factory word list
		StagingLlmSuggester.attachCatalogTags(pSession, pFactory, tablesByKey);
		IdentityReader.measure(pSession, pFactory, landingDataspace, landingDatasetBySystem, tablesByKey);
		// B2B test (2026-10-23, docs/TARGET-MAPPING.md sec 23): what only a row shows - a column repeating the row's key, holding another source's keys, continuing a value cut
		// at a fixed width, mixing identifier shapes, writing identifiers with separators - one more pass per table, facts with their rule, never a decision
		final LlmDataPolicy.Level policyLevel = dataPolicy.levelFor(project);
		RowFactsReader.measure(pSession, pFactory, landingDataspace, landingDatasetBySystem, tablesByKey, new RowFactsReader.ShapePolicy()
		{
			@Override
			public boolean shapesAllowed(final StagingRules.LandingTableInfo pTable, final StagingRules.LandingColumnInfo pColumn)
			{
				return StagingLlmSuggester.mayShowShapes(policyLevel, dataPolicy, pTable, pColumn.fieldName);
			}
		}, policyLevel == LlmDataPolicy.Level.SAMPLE_VALUES);

		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = new ArrayList<StagingLlmPass.RuleEntityInfo>();
		final Map<String, String> ruleEntityIdByName = new HashMap<String, String>();
		final Map<String, String> existingEntityIdByName = new HashMap<String, String>();
		final List<StagingLlmPass.ExistingTargetEntity> priorTargetEntities = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		for (final Adaptation row : entityRowsList)
		{
			final String origin = row.getString(FactoryPaths.StagingEntity.ORIGIN);
			final String entityId = row.getOccurrencePrimaryKey().format();
			final String entityName = row.getString(FactoryPaths.StagingEntity.NAME);
			existingEntityIdByName.put(entityName, entityId);
			if ("RULE".equals(origin))
			{
				final StagingLlmPass.RuleEntityInfo ruleEntity = StagingLlmSuggester.readRuleEntity(pFactory, row);
				// 2026-10-12: a rule entity written for a table that has left the scope since is not offered to the model
				if (LandingScope.keepsRuleEntity(tablesByKey.keySet(), ruleEntity))
				{
					ruleEntities.add(ruleEntity);
				}
				ruleEntityIdByName.put(entityName, entityId);
			}
			else if ("LLM".equals(origin))
			{
				priorTargetEntities.add(StagingLlmSuggester.readExistingTargetEntity(pFactory, row, entityIdToName));
			}
		}
		if (ruleEntities.size() < 1)
		{
			throw EngineRefusal.coded("missing.noRuleEntity", "Project " + pProjectCode
				+ " has no rule-proposed entity yet: run POST /projects/" + pProjectCode + "/staging/suggest first.", pProjectCode);
		}

		final StagingLlmPass.ProjectInfo projectInfo = new StagingLlmPass.ProjectInfo(pProjectCode, project.getString(FactoryPaths.Project.LABEL),
			new ArrayList<StagingRules.LandingTableInfo>(tablesByKey.values()), relations, ruleEntities);
		final StagingLlmPass.Session llmSession = new StagingLlmPass.Session(projectInfo, priorTargetEntities);
		llmSession.setRuleProbe(new LandingRuleProbe(pSession, pFactory, landingDataspace, landingDatasetBySystem, tablesByKey, dataPolicy, dataPolicy.levelFor(project)));

		// staging-llm-rerun (docs/TARGET-MAPPING.md "Relance de la passe LLM"): the project's own state BEFORE
		// this run - not a Session-internal tally of idempotent no-ops, which would depend on whether the model
		// even bothered calling propose_target_entity/propose_feed again for something it was told is already
		// covered. Every prior entity/feed is by construction "left alone" (never recreated), so this count is
		// unconditionally true regardless of what this run does.
		final int entitiesAlreadyExisted = priorTargetEntities.size();
		int feedsAlreadyExistedCount = 0;
		for (final StagingLlmPass.ExistingTargetEntity prior : priorTargetEntities)
		{
			feedsAlreadyExistedCount += prior.feeds.size();
		}
		final int feedsAlreadyExisted = feedsAlreadyExistedCount;

		final ToolSet tools;
		try
		{
			tools = ToolSet.build(Collections.<String, ToolSet.Server>emptyMap(), Collections.singletonMap("factory", llmSession.toolGroup()));
		}
		catch (final McpException ex)
		{
			// unreachable in practice: no MCP server is ever given to this ToolSet, only the local "factory" group
			throw EngineRefusal.codedWithCause("external.agentTools", "Could not build the agent's tool set: " + ex.getMessage(), ex);
		}

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, StagingLlmSuggester.STEP_STAGING_SUGGEST, StagingLlmSuggester.STEP_SCOPE);
		final ObjectNode out = StagingLlmSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("ruleEntities", ruleEntities.size());
		out.put("landingTables", tablesByKey.size());
		out.put("landingTablesOutOfScope", outOfScope[0]);
		out.put("entitiesAlreadyExisted", entitiesAlreadyExisted);
		out.put("feedsAlreadyExisted", feedsAlreadyExisted);
		try
		{
			final AgentRuntime.Outcome outcome = AgentRuntime.run(pSession, pFactory, projectId, StagingLlmSuggester.PHASE,
				"Design the target staging model of project " + pProjectCode + " from its landing inventory, and feed it from the landing tables.",
				StagingLlmPass.systemPrompt(pLocale), StagingLlmPass.userPrompt(projectInfo, priorTargetEntities), tools);

			final ObjectNode agentRun = out.putObject("agentRun");
			agentRun.put("id", outcome.runId);
			agentRun.put("status", AgentRuntime.statusOf(outcome.status));
			agentRun.put("steps", outcome.steps);
			agentRun.put("inputTokens", outcome.inputTokens);
			agentRun.put("outputTokens", outcome.outputTokens);
			agentRun.put("refusedToolCalls", outcome.refusedToolCalls);
			if (outcome.detail != null)
			{
				agentRun.put("detail", outcome.detail);
			}

			// PersVol volume test (2026-09-24): a landing table the model neither fed nor explained gets ONE more turn,
			// same session (so what it already proposed stands), before anything is written - see
			// StagingLlmPass.Session#tablesNotYetCovered.
			// PersVol4 (2026-09-26): the same turn also covers filled columns of a table that IS read but that no rule reads
			// (the OTHER_* address of CRM.CONTACT, 457 rows, forgotten while MAILING_* was fed) - asked only when one of them
			// is filled in StagingLlmPass.UNREAD_COLUMN_FOLLOW_UP_FILL_RATE percent of rows or more. Java only lists them;
			// grouping them into a variant is the model's job.
			final List<String> notYetCovered = llmSession.stoppedEarly() ? Collections.<String> emptyList() : llmSession.tablesNotYetCovered();
			final Map<String, List<StagingRules.LandingColumnInfo>> unreadColumns = llmSession.stoppedEarly()
				? Collections.<String, List<StagingRules.LandingColumnInfo>> emptyMap() : llmSession.unreadColumns(false);
			final Map<String, List<StagingRules.LandingColumnInfo>> askedColumns = StagingLlmPass.worthAFollowUp(unreadColumns) ? unreadColumns
				: Collections.<String, List<StagingRules.LandingColumnInfo>> emptyMap();
			// lot M7 (2026-10-18, E2EMASTER20261017235630): a target entity the run fed from no landing column (ContactEmail: four attributes, no feed) gets the
			// same turn - feed it, or say why; what stays unfed is written PROPOSED with the refusal on its row (ProposalChecks.NO_FEED), never accepted in bulk
			final List<String> unfedEntities = llmSession.stoppedEarly() ? Collections.<String> emptyList() : llmSession.entitiesNotYetFed();
			// lot M15 (2026-10-18, E2EMASTER20261018090711): the columns a superseded per-table entity read and no live entity reads (STORES.TELEPHONE: mapped only
			// to the superseded MagasinsVentes..., so Client.mobile stayed empty for every STORES row) are named with their entity and its canonical, in the same turn
			final List<StagingLlmPass.LeftBehind> leftBehind = llmSession.stoppedEarly() ? Collections.<StagingLlmPass.LeftBehind> emptyList()
				: llmSession.columnsLeftBehind();
			final boolean askLeftBehind = StagingLlmPass.worthAFollowUp(StagingLlmPass.columnsOf(leftBehind));
			final List<StagingLlmPass.LeftBehind> askedLeftBehind = askLeftBehind ? leftBehind : Collections.<StagingLlmPass.LeftBehind> emptyList();
			final Map<String, List<StagingRules.LandingColumnInfo>> askedUnread = StagingLlmPass.withoutColumns(askedColumns, askedLeftBehind);
			// lot S10 (2026-10-18, E2E B2C): a landing table whose rows carry identity attributes and that feeds no entity holding the identity of the other tables
			// (STORES left out of the person entity in 2 runs of 5: 300 rows, 103 goldens instead of 165) gets the same turn, with the counts - feed it or say why not
			final List<IdentityCoverage.Gap> identityGaps = llmSession.stoppedEarly() ? Collections.<IdentityCoverage.Gap> emptyList() : llmSession.identityGaps();
			// lot F2 (2026-10-19): the coverage of each entity by the landing tables carrying its kind of columns rides on this same turn; it opens the turn only when some entity leaves such a table unfed
			final List<IdentityCoverage.EntityCoverage> entityCoverage = llmSession.stoppedEarly() ? Collections.<IdentityCoverage.EntityCoverage> emptyList() : llmSession.entityCoverage();
			boolean coveragePartial = false;
			for (final IdentityCoverage.EntityCoverage coverage : entityCoverage)
			{
				coveragePartial |= coverage.partial();
			}
			// lot F2b (2026-10-19): a date attribute fed from columns whose spans do not overlap (a birth date fed from a purchase date) gets the same turn, with the spans
			final List<DateSpans.Disjoint> dateSpans = llmSession.stoppedEarly() ? Collections.<DateSpans.Disjoint> emptyList() : llmSession.dateSpanDisjoints();
			if (!notYetCovered.isEmpty() || !askedColumns.isEmpty() || !unfedEntities.isEmpty() || !askedLeftBehind.isEmpty() || !identityGaps.isEmpty() || coveragePartial
				|| !dateSpans.isEmpty())
			{
				final String task = "Feed or explain what agent run " + outcome.runId + " of project " + pProjectCode + " left out: "
					+ StagingLlmSuggester.joinClauses(
						notYetCovered.isEmpty() ? null : "tables " + StagingLlmPass.join(notYetCovered),
						askedUnread.isEmpty() ? null : "unread columns " + StagingLlmPass.renderColumns(askedUnread),
						askedLeftBehind.isEmpty() ? null : "columns fed to a superseded entity and not to its canonical " + StagingLlmSuggester.leftBehindText(askedLeftBehind),
						unfedEntities.isEmpty() ? null : "entities without a feed " + StagingLlmPass.join(unfedEntities),
						identityGaps.isEmpty() ? null : "tables carrying identity attributes that feed no entity holding them " + StagingLlmSuggester.identityGapTables(identityGaps),
						coveragePartial ? "entities not fed from every landing table that carries their kind of columns " + StagingLlmSuggester.partialCoverageEntities(entityCoverage) : null,
						dateSpans.isEmpty() ? null : "date attributes fed from columns whose spans do not overlap " + StagingLlmSuggester.dateSpanAttributes(dateSpans)) + ".";
				final AgentRuntime.Outcome followUp = AgentRuntime.run(pSession, pFactory, projectId, StagingLlmSuggester.PHASE, task,
					StagingLlmPass.systemPrompt(pLocale), StagingLlmPass.userPrompt(projectInfo, priorTargetEntities) + "\n\n"
						+ StagingLlmPass.followUpPrompt(llmSession, notYetCovered, askedColumns, unfedEntities, askedLeftBehind, identityGaps, entityCoverage, dateSpans), tools);
				final ObjectNode followUpRun = out.putObject("followUpAgentRun");
				followUpRun.put("id", followUp.runId);
				followUpRun.put("status", AgentRuntime.statusOf(followUp.status));
				followUpRun.put("tables", StagingLlmPass.join(notYetCovered));
				followUpRun.put("unreadColumns", StagingLlmPass.renderColumns(askedUnread));
				followUpRun.put("columnsLeftBehind", StagingLlmPass.renderColumns(StagingLlmPass.columnsOf(askedLeftBehind)));
				followUpRun.put("entitiesWithoutFeed", StagingLlmPass.join(unfedEntities));
				followUpRun.put("identityWithoutFeed", StagingLlmSuggester.identityGapTables(identityGaps));
				followUpRun.put("entityCoverage", StagingLlmSuggester.coverageTexts(entityCoverage));
				followUpRun.put("dateSpansDisjoint", StagingLlmSuggester.dateSpanAttributes(dateSpans));
				followUpRun.put("steps", followUp.steps);
				followUpRun.put("inputTokens", followUp.inputTokens);
				followUpRun.put("outputTokens", followUp.outputTokens);
				followUpRun.put("refusedToolCalls", followUp.refusedToolCalls);
			}

			llmSession.finalizeEntities();
			out.put("earlyStop", llmSession.stoppedEarly());
			if (llmSession.stoppedEarly())
			{
				out.put("earlyStopReason", llmSession.stopReason());
			}

			// fix/staging-proposal-quality (2026-10-17, item 2): the deterministic modelling checks, before anything is written (docs/UX-STAGING-TEST-FIXES-A.md)
			final List<ProposalChecks.Finding> modelling = llmSession.reviewModelling(new ProposalChecks.Words()
			{
				@Override
				public String text(final String pKey, final Object... pArgs)
				{
					return WizardMessages.get(pLocale, pKey, pArgs);
				}
			});
			final ArrayNode modellingNode = out.putArray("modellingFindings");
			for (final ProposalChecks.Finding finding : modelling)
			{
				final ObjectNode line = modellingNode.addObject();
				line.put("kind", finding.kind);
				line.put("entity", finding.entity);
				if (finding.attribute != null)
				{
					line.put("attribute", finding.attribute);
				}
				if (finding.landingTableKey != null)
				{
					line.put("landingTable", finding.landingTableKey);
				}
				if (finding.relatedEntity != null)
				{
					line.put("relatedEntity", finding.relatedEntity);
				}
			}

			StagingLlmSuggester.write(pSession, pFactory, projectId, llmSession, tablesByKey, ruleEntityIdByName, existingEntityIdByName, out);

			final ArrayNode notesNode = out.putArray("notes");
			for (final String note : llmSession.notes())
			{
				notesNode.add(note);
			}
			final ArrayNode entitiesWithoutFeedNode = out.putArray("entitiesWithoutFeed");
			for (final String name : llmSession.entitiesWithoutFeed())
			{
				entitiesWithoutFeedNode.add(name);
			}
			final ArrayNode tablesWithoutFeedNode = out.putArray("tablesWithoutFeedOrNote");
			for (final String tableKey : llmSession.tablesWithoutFeedOrNote())
			{
				tablesWithoutFeedNode.add(tableKey);
			}

			final Map<String, List<StagingRules.LandingColumnInfo>> leftOutOnPurpose = llmSession.unreadColumns(true);
			final Map<String, List<StagingRules.LandingColumnInfo>> stillUnread = llmSession.unreadColumns(false);
			out.put("columnsLeftOutOnPurpose", StagingLlmPass.renderColumns(leftOutOnPurpose));
			out.put("unreadFilledColumns", StagingLlmPass.renderColumns(stillUnread));
			// lot M15: what a superseded per-table entity read and no live entity reads, after the follow-up turn (the record step flagged the canonical's row with it)
			out.put("columnsLeftBehind", StagingLlmPass.renderColumns(StagingLlmPass.columnsOf(llmSession.columnsLeftBehind())));
			// PersVol7 (2026-09-28): what was decided on every column written in two case layouts (order-aware rule, kept whole, or an
			// order-blind rule kept after its one refusal); Vol100kN (2026-10-12): and "order unknown" for a rule that splits a
			// multi-token column whose casing does not tell the parts apart
			final ArrayNode orderDecisionsNode = out.putArray("tokenOrderDecisions");
			for (final String decision : llmSession.orderDecisions())
			{
				orderDecisionsNode.add(decision);
			}
			// staging feed-conversion lot E (2026-10-17): rules whose conversion to a typed attribute was corrected or flagged by the value check
			final ArrayNode conversionNode = out.putArray("valueConversionDecisions");
			for (final String decision : llmSession.conversionDecisions())
			{
				conversionNode.add(decision);
			}
			// PersVol8 (2026-09-28): rules kept after their one refusal although they give two attributes the same output from one column
			final ArrayNode identicalNode = out.putArray("identicalOutputDecisions");
			for (final String decision : llmSession.identicalOutputDecisions())
			{
				identicalNode.add(decision);
			}

			final StringBuilder message = new StringBuilder();
			message.append(out.path("entitiesCreated").asInt(0)).append(" target entity(ies) created, ").append(out.path("feedsCreated").asInt(0))
				.append(" feed(s) created, ").append(entitiesAlreadyExisted).append(" entity(ies) and ").append(feedsAlreadyExisted)
				.append(" feed(s) already existed (left alone), ").append(out.path("entitiesSuperseded").asInt(0))
				.append(" rule entity(ies) superseded (agent run ").append(outcome.runId).append(", ").append(AgentRuntime.statusOf(outcome.status))
				.append(llmSession.stoppedEarly() ? ", stopped early" : "").append(").");
			if (out.path("childKeysCreated").asInt(0) > 0)
			{
				message.append(' ').append(out.path("childKeysCreated").asInt(0)).append(" key attribute(s) built from child records proposed (to accept, then regenerate the staging).");
			}
			if (!llmSession.entitiesWithoutFeed().isEmpty() || !llmSession.tablesWithoutFeedOrNote().isEmpty())
			{
				message.append(' ').append(llmSession.entitiesWithoutFeed().size()).append(" entity(ies) without a feed")
					.append(llmSession.entitiesWithoutFeed().isEmpty() ? "" : " (" + StagingLlmPass.join(llmSession.entitiesWithoutFeed())
						+ ": no landing column feeds them, left proposed with the refusal on the row, never accepted in bulk)")
					.append(", ").append(llmSession.tablesWithoutFeedOrNote().size()).append(" landing table(s) without a feed or note.");
			}
			if (!modelling.isEmpty())
			{
				message.append(' ').append(modelling.size()).append(" modelling point(s) flagged for review (a field duplicated on a related entity, a field no source feeds, an entity no landing column feeds, a table carrying identity attributes that feeds no entity holding them, or one record per source row): ")
					.append(StagingLlmPass.join(StagingLlmSuggester.describe(modelling))).append('.');
			}
			if (!stillUnread.isEmpty())
			{
				message.append(" Filled columns no feed reads: ").append(StagingLlmPass.renderColumns(stillUnread)).append('.');
			}
			if (!leftOutOnPurpose.isEmpty())
			{
				message.append(" Left out on purpose: ").append(StagingLlmPass.renderColumns(leftOutOnPurpose)).append('.');
			}
			if (!llmSession.orderDecisions().isEmpty())
			{
				message.append(" Columns whose parts may be written in either order (two case layouts, or casing that does not tell them apart): ").append(StagingLlmPass.join(llmSession.orderDecisions())).append('.');
			}
			if (!llmSession.conversionDecisions().isEmpty())
			{
				message.append(" Rules whose conversion to a typed attribute the value check corrected or flagged: ").append(StagingLlmPass.join(llmSession.conversionDecisions())).append('.');
			}
			if (!llmSession.identicalOutputDecisions().isEmpty())
			{
				message.append(" Two attributes given one output from one column: ").append(StagingLlmPass.join(llmSession.identicalOutputDecisions())).append('.');
			}
			PipelineSteps.end(pSession, pFactory, stepId, true, message.toString(), out.toString());
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), out.toString());
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, out.toString());
			throw ex;
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------- reading

	/**
	 * {@link StagingLlmPass.RuleProbe} on the project's landing data: the most frequent distinct values of the column (capped),
	 * each run through the rule's Connectors pipeline exactly as {@code StagingLoader} will run it, then counted - how many
	 * filled values came out empty - and shaped. Nothing but counts and shapes goes back to the session, so nothing but
	 * counts and shapes can reach the model. One sample per column, read once per run.
	 */
	private static final class LandingRuleProbe implements StagingLlmPass.RuleProbe
	{
		private final LlmDataPolicy policy;
		private final LlmDataPolicy.Level level;
		private static final int SAMPLE_DISTINCT = 50;
		private final Session session;
		private final Adaptation factory;
		private final String landingDataspace;
		private final Map<String, String> datasetBySystem;
		private final Map<String, StagingRules.LandingTableInfo> tablesByKey;
		private final Map<String, List<String>> sampleByColumn = new HashMap<String, List<String>>();
		/** {@code "<table>.<column>"} -> the distinct values (with rows) {@link #verifyTyped} judged, read once per run. */
		private final Map<String, QualitySuggester.ColumnQuery> typedValuesByColumn = new HashMap<String, QualitySuggester.ColumnQuery>();

		LandingRuleProbe(final Session pSession, final Adaptation pFactory, final String pLandingDataspace, final Map<String, String> pDatasetBySystem,
			final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final LlmDataPolicy pPolicy, final LlmDataPolicy.Level pLevel)
		{
			this.policy = pPolicy;
			this.level = pLevel;
			this.session = pSession;
			this.factory = pFactory;
			this.landingDataspace = pLandingDataspace;
			this.datasetBySystem = pDatasetBySystem;
			this.tablesByKey = pTablesByKey;
		}

		@Override
		public StagingLlmPass.ProbeResult probe(final String pLandingTableKey, final String pLandingColumn, final String pPipeline)
		{
			final StagingRules.LandingTableInfo table = this.tablesByKey.get(pLandingTableKey);
			if (table == null || this.landingDataspace == null)
			{
				return null;
			}
			final AdaptationHome home = this.factory.getHome().getRepository().lookupHome(HomeKey.forBranchName(this.landingDataspace));
			final String datasetName = this.datasetBySystem.get(table.sourceSystem);
			final Adaptation dataset = home == null || datasetName == null ? null : home.findAdaptationOrNull(AdaptationName.forName(datasetName));
			if (dataset == null)
			{
				return null;
			}
			try
			{
				final List<String> sample = this.sample(dataset, table.name, pLandingColumn);
				final TransformationContext ctx = new TransformationContext(dataset, null, this.session);
				// the same pipeline with a sentinel '*=' added to each map(...): tells which sampled values the map does not list
				final String sentinelPipeline = StagingLlmPass.withMapSentinel(pPipeline);
				final List<Detectors.ValueCount> in = new ArrayList<Detectors.ValueCount>();
				final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
				final List<String> outputs = new ArrayList<String>();
				final List<String> failures = new ArrayList<String>();
				int unlisted = 0;
				for (final String value : sample)
				{
					final String[] failure = new String[1];
					final String result = LandingRuleProbe.run(ctx, pLandingColumn, pPipeline, value, failure);
					failures.add(failure[0]);
					in.add(new Detectors.ValueCount(value, null, 1));
					outputs.add(result);
					if (result != null && !result.trim().isEmpty())
					{
						out.add(new Detectors.ValueCount(result, null, 1));
					}
					if (sentinelPipeline != null)
					{
						final String marked = LandingRuleProbe.run(ctx, pLandingColumn, sentinelPipeline, value, new String[1]);
						if (marked != null && marked.toLowerCase(java.util.Locale.ROOT).contains(StagingLlmPass.MAP_SENTINEL))
						{
							unlisted++;
						}
					}
				}
				final StagingLlmPass.ProbeResult measured = StagingLlmPass.ProbeResult.of(sample, outputs, failures, unlisted, StagingLlmSuggester.topShapes(in),
					StagingLlmSuggester.topShapes(out));
				// the counts always go; the shapes of the values (and the case layout of their tokens) only where the policy allows them
				return StagingLlmSuggester.mayShowShapes(this.level, this.policy, table, pLandingColumn) ? measured : measured.withoutValueShapes();
			}
			catch (final RuntimeException ex)
			{
				return null; // best effort: an unreadable column is not a reason to refuse a rule
			}
		}

		/**
		 * Staging feed-conversion lot E (2026-10-17): {@link TransformVerifier#verify} on the column's own distinct values (the most frequent
		 * {@link QualitySuggester#DEFAULT_MAX_DISTINCT_VALUES}, with their rows - the sample the normalisation pass reads, not the 50 values of
		 * the emptiness probe), through the Connectors engine the loader runs, with the column's measured profile for the replacements.
		 * Computed in Java: the session only receives the verdict, rates and pipeline texts. Not gated by the data policy for that reason (a
		 * sensitive column is verified like any other: nothing leaves the instance). Null when the column cannot be read.
		 */
		@Override
		public TransformVerifier.Result verifyTyped(final String pLandingTableKey, final String pLandingColumn, final String pPipeline, final String pDataType)
		{
			final StagingRules.LandingTableInfo table = this.tablesByKey.get(pLandingTableKey);
			if (table == null || this.landingDataspace == null)
			{
				return null;
			}
			final AdaptationHome home = this.factory.getHome().getRepository().lookupHome(HomeKey.forBranchName(this.landingDataspace));
			final String datasetName = this.datasetBySystem.get(table.sourceSystem);
			final Adaptation dataset = home == null || datasetName == null ? null : home.findAdaptationOrNull(AdaptationName.forName(datasetName));
			if (dataset == null)
			{
				return null;
			}
			try
			{
				final String key = table.name + "." + pLandingColumn;
				QualitySuggester.ColumnQuery read = this.typedValuesByColumn.get(key);
				if (read == null)
				{
					final QualitySuggester.LandingTableRef ref = new QualitySuggester.LandingTableRef(dataset, dataset.getTable(Path.parse("/root/" + table.name)));
					read = QualitySuggester.distinctValues(this.session, ref, pLandingColumn, null, QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES);
					this.typedValuesByColumn.put(key, read);
				}
				final List<Detectors.ValueCount> values = read.values;
				// staging polish (2026-10-17): on a table above mdm.factory.profile.maxRowsFullScan rows the values are those of its first rows, and the verdict says so
				final NormalisationProfile profile = NormalisationProfiler.profile(values, -1L).markRowSample(read.sampledRows);
				final TransformationContext ctx = new TransformationContext(dataset, null, this.session);
				final TransformVerifier.Evaluator evaluator = new TransformVerifier.Evaluator()
				{
					@Override
					public String apply(final String pPipelineToRun, final String pValue) throws Exception
					{
						final com.fasterxml.jackson.databind.node.ObjectNode document = StagingLlmSuggester.MAPPER.createObjectNode();
						document.put(pLandingColumn, pValue);
						ctx.setDocumentRoot(document);
						return Transformations.text(Transformations.apply(pPipelineToRun, pValue, ctx));
					}
				};
				return TransformVerifier.verify(pDataType, pPipeline, values, profile, evaluator, read.sampled() || values.size() >= QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES,
					read.sampledRows);
			}
			catch (final RuntimeException ex)
			{
				return null; // best effort, like the probe: an unreadable column is not a reason to change a rule
			}
		}

		/**
		 * {@code pPipeline} applied to one value as {@code StagingLoader} would; null when it throws (a pipeline that throws on
		 * a real value empties it), with what failed in {@code pFailure[0]} ({@link #describe}: never the value).
		 */
		private static String run(final TransformationContext pContext, final String pColumn, final String pPipeline, final String pValue,
			final String[] pFailure)
		{
			final com.fasterxml.jackson.databind.node.ObjectNode document = StagingLlmSuggester.MAPPER.createObjectNode();
			document.put(pColumn, pValue);
			pContext.setDocumentRoot(document);
			try
			{
				return Transformations.text(Transformations.apply(pPipeline, pValue, pContext));
			}
			catch (final Exception ex)
			{
				pFailure[0] = LandingRuleProbe.describe(ex);
				return null;
			}
		}

		/**
		 * What made a pipeline throw, for the model: the text of a pattern syntax error or of an unknown step (they hold no
		 * value), otherwise the exception's class only - a message such as NumberFormatException's quotes the value, and
		 * values never reach the model.
		 */
		private static String describe(final Exception pException)
		{
			if (pException instanceof java.util.regex.PatternSyntaxException)
			{
				final java.util.regex.PatternSyntaxException syntax = (java.util.regex.PatternSyntaxException) pException;
				return "invalid regex pattern '" + syntax.getPattern() + "': " + syntax.getDescription() + " near index " + syntax.getIndex();
			}
			final String message = pException.getMessage();
			if (ForeignWording.isUnknownTransformation(pException))
			{
				return message;
			}
			return pException.getClass().getSimpleName();
		}

		private List<String> sample(final Adaptation pDataset, final String pTableName, final String pColumn)
		{
			final String key = pTableName + "." + pColumn;
			List<String> sample = this.sampleByColumn.get(key);
			if (sample == null)
			{
				sample = new ArrayList<String>();
				final QualitySuggester.LandingTableRef ref = new QualitySuggester.LandingTableRef(pDataset, pDataset.getTable(Path.parse("/root/" + pTableName)));
				for (final Detectors.ValueCount value : QualitySuggester.distinctValues(this.session, ref, pColumn, null, LandingRuleProbe.SAMPLE_DISTINCT).values)
				{
					if (value.value != null && !value.value.trim().isEmpty())
					{
						sample.add(value.value);
					}
				}
				this.sampleByColumn.put(key, sample);
			}
			return sample;
		}
	}

	/**
	 * Whether the shapes of a landing column's values may reach the model: not under {@code NONE}, not for a column of the
	 * sensitive list (tested on the landing field and on its source column).
	 */
	static boolean mayShowShapes(final LlmDataPolicy.Level pLevel, final LlmDataPolicy pPolicy, final StagingRules.LandingTableInfo pTable, final String pFieldName)
	{
		if (pLevel == LlmDataPolicy.Level.NONE || pPolicy.isSensitive(pFieldName))
		{
			return false;
		}
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			if (column.fieldName.equals(pFieldName) && pPolicy.isSensitive(column.sourceColumn))
			{
				return false;
			}
		}
		return true;
	}

	/** Distinct values read per text column to compute its shapes: enough to see the dominant formats, never shown as values. */
	private static final int SHAPE_SAMPLE_DISTINCT = 200;
	private static final int SHAPES_SHOWN = 3;

	/**
	 * Fills {@link StagingRules.LandingColumnInfo#shapes} for every TEXT column of {@code pTable} the data policy does
	 * not mark sensitive: the most frequent value shapes ({@link Shapes#of}), with their share of the non-empty values
	 * read. What lets the model see that a text birth date is {@code 9(8)} (so {@code date(yyyyMMdd)}), or that a
	 * full name is written {@code AAAA Aaaa} in one source (PersVol volume test, 2026-09-24). Best effort: a landing
	 * table that cannot be read leaves its columns without shapes.
	 */
	private static void fillShapes(final Session pSession, final Adaptation pFactory, final LlmDataPolicy pPolicy, final String pLandingDataspace,
		final String pLandingDataset, final StagingRules.LandingTableInfo pTable, final boolean pShowValues)
	{
		if (pLandingDataspace == null || pLandingDataset == null)
		{
			return;
		}
		final AdaptationHome home = pFactory.getHome().getRepository().lookupHome(HomeKey.forBranchName(pLandingDataspace));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pLandingDataset));
		if (dataset == null)
		{
			return;
		}
		final QualitySuggester.LandingTableRef ref;
		try
		{
			ref = new QualitySuggester.LandingTableRef(dataset, dataset.getTable(Path.parse("/root/" + pTable.name)));
		}
		catch (final RuntimeException ex)
		{
			return; // table not in the published landing model (yet)
		}
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			final String columnType = column.inferredType == null || column.inferredType.trim().isEmpty() ? "STRING" : column.inferredType.trim();
			if ("DATE".equals(columnType) || "TIMESTAMP".equals(columnType))
			{
				// lot F2b (2026-10-19): a typed date column keeps no shape, but its SPAN (earliest and latest month) is read from the same bounded sample - a count-like fact,
				// given for a sensitive column too (a month, never a day or a value)
				try
				{
					final QualitySuggester.ColumnQuery dates = QualitySuggester.distinctValues(pSession, ref, column.fieldName, null, StagingLlmSuggester.SHAPE_SAMPLE_DISTINCT);
					column.dateSpan = StagingLlmSuggester.spanOf(column, dates);
				}
				catch (final RuntimeException ex)
				{
					// best effort, as below
				}
				continue;
			}
			if (!"STRING".equals(columnType))
			{
				continue;
			}
			final boolean sensitive = pPolicy.isSensitive(column.sourceColumn) || pPolicy.isSensitive(column.fieldName);
			if (sensitive)
			{
				// staging feed-conversion lot E (2026-10-17): a sensitive column keeps its values AND its shapes from the model, but how a date or a
				// number is WRITTEN ("dd/MM/yyyy (100%)", "decimal comma") says nothing of any one row and is the only way the model can convert
				// a birth date it cannot see (loyalty date_naissance, TiersB2CRetestStaging). Computed here from the sample, never rendered as one.
				try
				{
					final QualitySuggester.ColumnQuery formats = QualitySuggester.distinctValues(pSession, ref, column.fieldName, null,
						StagingLlmSuggester.SHAPE_SAMPLE_DISTINCT);
					column.profileFacts = NormalisationProfiler.promptFacts(formats.values, column.distinctCount == null ? -1L : column.distinctCount.longValue(),
						formats.sampledRows, true, false);
					// lot F2: how the values are WRITTEN (e-mail-shaped, phone-shaped...) is a share of the rows, not a value - measured on this same sample, kept for a sensitive column too
					column.shapeIdentity = IdentityShapes.detect(formats.values);
					// lot F2b: and the span of a text column whose values are dates - the same sample, months only
					column.dateSpan = StagingLlmSuggester.spanOf(column, formats);
				}
				catch (final RuntimeException ex)
				{
					// best effort, as below
				}
				continue;
			}
			try
			{
				final QualitySuggester.ColumnQuery query = QualitySuggester.distinctValues(pSession, ref, column.fieldName, null,
					StagingLlmSuggester.SHAPE_SAMPLE_DISTINCT);
				column.shapes.addAll(StagingLlmSuggester.topShapes(query.values));
				// lot F2: the same sample, counted for identity shapes (e-mail, phone, two capitalised words): a measure for a column the catalogue did not tag, no second read
				column.shapeIdentity = IdentityShapes.detect(query.values);
				// lot F2b: and the span of a text column whose values are dates (earliest and latest month), from the same sample
				column.dateSpan = StagingLlmSuggester.spanOf(column, query);
				// PersVol7 (2026-09-28): where the capitals sit - a full name written in two orders shows two layouts
				final TokenCase tokenCase = new TokenCase();
				for (final Detectors.ValueCount value : query.values)
				{
					tokenCase.add(value.value, value.count);
				}
				column.tokenCase = tokenCase;
				// staging normalisation lot D (2026-10-17): the same sample, profiled (date pattern, number/phone shape, letter case, spaces, repeated codes)
				column.profileFacts = NormalisationProfiler.promptFacts(query.values, column.distinctCount == null ? -1L : column.distinctCount.longValue(),
					query.sampledRows, false, pShowValues);
			}
			catch (final RuntimeException ex)
			{
				// best effort: a column that cannot be read keeps no shape
			}
		}
	}

	/**
	 * Lot F2b: the span of a date column from the distinct values the shape profiler read ({@link DateSpans#measure}); null when the column is no date column. The span says it is
	 * measured on the most frequent values only when the column has more distinct values than were read, or the table was read on a sample.
	 */
	static DateSpans.Span spanOf(final StagingRules.LandingColumnInfo pColumn, final QualitySuggester.ColumnQuery pQuery)
	{
		final boolean partial = pQuery.sampled() || (pColumn.distinctCount != null ? pColumn.distinctCount.longValue() > pQuery.values.size()
			: pQuery.values.size() >= DateSpans.VALUES_READ_CAP);
		return DateSpans.measure(pQuery.values, partial, java.time.LocalDate.now());
	}

	/** The {@link #SHAPES_SHOWN} most frequent shapes of {@code pValues}, each with its share, e.g. {@code "9(8) (100%)"}. */
	static List<String> topShapes(final List<Detectors.ValueCount> pValues)
	{
		final Map<String, Long> byShape = new LinkedHashMap<String, Long>();
		long total = 0;
		for (final Detectors.ValueCount value : pValues)
		{
			if (value.value == null)
			{
				continue;
			}
			final String shape = Shapes.of(value.value);
			final Long seen = byShape.get(shape);
			byShape.put(shape, Long.valueOf((seen == null ? 0L : seen.longValue()) + value.count));
			total += value.count;
		}
		final List<Map.Entry<String, Long>> sorted = new ArrayList<Map.Entry<String, Long>>(byShape.entrySet());
		Collections.sort(sorted, new java.util.Comparator<Map.Entry<String, Long>>()
		{
			@Override
			public int compare(final Map.Entry<String, Long> pLeft, final Map.Entry<String, Long> pRight)
			{
				return pRight.getValue().compareTo(pLeft.getValue());
			}
		});
		final List<String> out = new ArrayList<String>();
		for (final Map.Entry<String, Long> entry : sorted)
		{
			if (out.size() >= StagingLlmSuggester.SHAPES_SHOWN)
			{
				break;
			}
			out.add(entry.getKey() + " (" + Math.round(100.0 * entry.getValue().longValue() / Math.max(1L, total)) + "%)");
		}
		return out;
	}

	private static List<StagingRules.LandingTableInfo> readTables(final Adaptation pFactory, final String pProjectSourceId, final String pSourceSystem,
		final Map<String, String> pTableNameById, final int[] pOutOfScope)
	{
		final List<StagingRules.LandingTableInfo> out = new ArrayList<StagingRules.LandingTableInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_TABLE).createRequestResult("./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!LandingScope.isInScope(row.get(FactoryPaths.LandingTable.SELECTED)))
				{
					// 2026-10-12: "In scope?" = false takes the table out of the staging passes too (nothing proposed for it, nothing shown to the model)
					pOutOfScope[0]++;
					continue;
				}
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.LandingTable.TABLE_NAME);
				pTableNameById.put(id, name);
				final Object rawRowCount = row.get(FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
				final Long rowCount = rawRowCount instanceof Number ? Long.valueOf(((Number) rawRowCount).longValue()) : null;
				out.add(new StagingRules.LandingTableInfo(id, pSourceSystem, name, rowCount, StagingLlmSuggester.readColumns(pFactory, id)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static List<StagingRules.LandingColumnInfo> readColumns(final Adaptation pFactory, final String pLandingTableId)
	{
		final List<StagingRules.LandingColumnInfo> out = new ArrayList<StagingRules.LandingColumnInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final String sourceColumn = row.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN);
				final String fieldName = row.getString(FactoryPaths.LandingColumn.FIELD_NAME);
				final String inferredType = row.getString(FactoryPaths.LandingColumn.INFERRED_TYPE);
				final boolean sourceKey = Boolean.TRUE.equals(row.get(FactoryPaths.LandingColumn.SOURCE_KEY));
				final Object rawFillRate = row.get(FactoryPaths.LandingColumn.FILL_RATE);
				final java.math.BigDecimal fillRate = rawFillRate instanceof java.math.BigDecimal ? (java.math.BigDecimal) rawFillRate : null;
				final Object rawDistinctCount = row.get(FactoryPaths.LandingColumn.DISTINCT_COUNT);
				final Long distinctCount = rawDistinctCount instanceof Number ? Long.valueOf(((Number) rawDistinctCount).longValue()) : null;
				final Object rawMaxLength = row.get(FactoryPaths.LandingColumn.MAX_LENGTH);
				final Integer maxLength = rawMaxLength instanceof Number ? Integer.valueOf(((Number) rawMaxLength).intValue()) : null;
				final List<String> tags = new ArrayList<String>();
				final List<?> tagsList = row.getList(FactoryPaths.LandingColumn.TAGS);
				if (tagsList != null)
				{
					for (final Object tag : tagsList)
					{
						tags.add(String.valueOf(tag));
					}
				}
				final StagingRules.LandingColumnInfo info = new StagingRules.LandingColumnInfo(id, sourceColumn, fieldName, inferredType, sourceKey, fillRate, distinctCount, maxLength, tags);
				if (sourceKey)
				{
					info.keyOrigin = row.getString(FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN); // lot S10: who set the key, a fact the model is told
				}
				out.add(info);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** {@code LandingRelation} rows of status {@code PROPOSED} or {@code ACCEPTED} only - never {@code REJECTED}, same rule as {@code StagingSuggester}. */
	private static List<StagingRules.RelationInfo> readRelations(final Adaptation pFactory, final String pProjectSourceId, final String pSourceSystem,
		final Map<String, String> pTableNameById)
	{
		final List<StagingRules.RelationInfo> out = new ArrayList<StagingRules.RelationInfo>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_RELATION).createRequestResult(
			"./projectSource=" + CatalogReader.quote(pProjectSourceId) + " and (./status='ACCEPTED' or ./status='PROPOSED')");
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String fromName = pTableNameById.get(row.getString(FactoryPaths.LandingRelation.FROM_TABLE));
				final String toName = pTableNameById.get(row.getString(FactoryPaths.LandingRelation.TO_TABLE));
				if (fromName == null || toName == null)
				{
					continue;
				}
				final List<String> fromColumns = new ArrayList<String>();
				final List<?> rawColumns = row.getList(FactoryPaths.LandingRelation.FROM_COLUMNS);
				if (rawColumns != null)
				{
					for (final Object column : rawColumns)
					{
						fromColumns.add(String.valueOf(column));
					}
				}
				final String status = row.getString(FactoryPaths.LandingRelation.STATUS);
				final Object rawConfidence = row.get(FactoryPaths.LandingRelation.CONFIDENCE);
				final int confidence = rawConfidence instanceof Number ? ((Number) rawConfidence).intValue() : 0;
				out.add(new StagingRules.RelationInfo(row.getOccurrencePrimaryKey().format(), pSourceSystem, fromName, fromColumns, toName, status, confidence));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** One rule-proposed entity's landing table, resolved through its own ROOT {@code TransferFeed} (written by {@code StagingSuggester}/{@code FeedBackfill} for every RULE entity) - never a second bookkeeping field. Null when the entity has no root feed yet (should not happen once {@code staging/suggest} has run, but this pass never fails a whole run over it). */
	private static StagingLlmPass.RuleEntityInfo readRuleEntity(final Adaptation pFactory, final Adaptation pEntityRow)
	{
		final String entityId = pEntityRow.getOccurrencePrimaryKey().format();
		final RequestResult feedRows = pFactory.getTable(FactoryPaths.TRANSFER_FEED)
			.createRequestResult("./entity=" + CatalogReader.quote(entityId) + " and osd:is-null(./parentFeed)");
		Adaptation feedRow;
		try
		{
			feedRow = feedRows.nextAdaptation();
		}
		finally
		{
			feedRows.close();
		}
		if (feedRow == null)
		{
			return null;
		}
		final String landingTableKey = StagingLlmSuggester.landingTableKeyOf(pFactory, feedRow.getString(FactoryPaths.TransferFeed.LANDING_TABLE));
		if (landingTableKey == null)
		{
			return null;
		}
		final int dot = landingTableKey.indexOf('.');
		// lot M15: the columns this rule entity's live mappings read (the root feed and its children), which a canonical entity that replaces it must still read
		final List<String> readColumns = new ArrayList<String>(StagingLlmSuggester.readColumnsOfFeed(pFactory, feedRow.getOccurrencePrimaryKey().format()));
		final RequestResult childRows = pFactory.getTable(FactoryPaths.TRANSFER_FEED).createRequestResult(
			"./entity=" + CatalogReader.quote(entityId) + " and osd:is-not-null(./parentFeed)");
		try
		{
			for (Adaptation child = childRows.nextAdaptation(); child != null; child = childRows.nextAdaptation())
			{
				for (final String column : StagingLlmSuggester.readColumnsOfFeed(pFactory, child.getOccurrencePrimaryKey().format()))
				{
					if (!readColumns.contains(column))
					{
						readColumns.add(column);
					}
				}
			}
		}
		finally
		{
			childRows.close();
		}
		return new StagingLlmPass.RuleEntityInfo(pEntityRow.getString(FactoryPaths.StagingEntity.NAME), landingTableKey.substring(0, dot),
			landingTableKey.substring(dot + 1), readColumns);
	}

	/**
	 * {@code "<sourceSystem>.<table>"} of a {@code LandingTable} row, exactly as {@link StagingLlmPass.Session}'s
	 * own catalogue keys it - shared by {@link #readRuleEntity} and {@link #readExistingTargetEntity} rather than
	 * re-derived (both need it for the same reason: a {@code TransferFeed.landingTable} foreign key is an EBX id,
	 * never the string key the pure {@link StagingLlmPass} understands). Null when the landing table or its own
	 * source is missing (an orphaned row should never crash a whole run over it - the caller skips it instead).
	 */
	private static String landingTableKeyOf(final Adaptation pFactory, final String pLandingTableId)
	{
		final Adaptation landingTable = pLandingTableId == null ? null
			: pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
		if (landingTable == null)
		{
			return null;
		}
		final String tableName = landingTable.getString(FactoryPaths.LandingTable.TABLE_NAME);
		final String projectSourceId = landingTable.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation projectSource = projectSourceId == null ? null
			: pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(projectSourceId));
		final String sourceSystem = projectSource == null ? null : projectSource.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		return sourceSystem == null || tableName == null ? null : sourceSystem + "." + tableName;
	}

	/** The non-null clauses joined with "; " (the follow-up run's goal line). */
	private static String joinClauses(final String... pClauses)
	{
		final StringBuilder out = new StringBuilder();
		for (final String clause : pClauses)
		{
			if (clause != null)
			{
				out.append(out.length() == 0 ? "" : "; ").append(clause);
			}
		}
		return out.toString();
	}

	/** {@code "Client: STORES.Magasins: TELEPHONE; ..."} - the columns left behind, per canonical entity, for the follow-up run's goal. */
	/**
	 * Lot S10b: writes into the landing columns what the data catalogue holds for them - their applied tags (added when the frozen copy of the plan lacks them) and their tag
	 * suggestions - read as the person who runs the pass. Best effort: a table whose asset is not found, a catalogue without suggestions, a catalogue the person cannot read: the
	 * columns keep what the plan copied.
	 */
	static void attachCatalogTags(final Session pSession, final Adaptation pFactory, final Map<String, StagingRules.LandingTableInfo> pTablesByKey)
	{
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			return;
		}
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		for (final StagingRules.LandingTableInfo table : pTablesByKey.values())
		{
			try
			{
				final Adaptation landingRow = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(table.id));
				final String assetKey = landingRow == null ? null : CatalogReader.resolveAssetKey(reader, catalog, pFactory, landingRow);
				if (assetKey == null)
				{
					continue;
				}
				final Map<String, CatalogReader.ColumnTagFacts> facts = CatalogReader.columnTagFacts(reader, catalog, assetKey);
				for (final StagingRules.LandingColumnInfo column : table.columns)
				{
					final CatalogReader.ColumnTagFacts found = facts.get(column.sourceColumn);
					if (found == null)
					{
						continue;
					}
					for (final String tag : found.applied)
					{
						if (!column.tags.contains(tag))
						{
							column.tags.add(tag);
						}
					}
					column.suggestions.addAll(found.suggestions);
				}
			}
			catch (final RuntimeException ex)
			{
				// best effort, as the shapes
			}
		}
	}

	/** The landing table keys of these identity gaps, comma separated. */
	private static String identityGapTables(final List<IdentityCoverage.Gap> pGaps)
	{
		final List<String> keys = new ArrayList<String>();
		for (final IdentityCoverage.Gap gap : pGaps)
		{
			keys.add(gap.landingTableKey);
		}
		return StagingLlmPass.join(keys);
	}

	/** The entities of these coverage lines that leave a carrying table unfed, comma separated. */
	private static String partialCoverageEntities(final List<IdentityCoverage.EntityCoverage> pCoverage)
	{
		final List<String> names = new ArrayList<String>();
		for (final IdentityCoverage.EntityCoverage coverage : pCoverage)
		{
			if (coverage.partial())
			{
				names.add(coverage.entity);
			}
		}
		return StagingLlmPass.join(names);
	}

	/** {@code Personne.birthDate}, comma separated: the date attributes of these disjoint spans. */
	private static String dateSpanAttributes(final List<DateSpans.Disjoint> pDisjoint)
	{
		final List<String> names = new ArrayList<String>();
		for (final DateSpans.Disjoint one : pDisjoint)
		{
			names.add(one.entity + "." + one.attribute);
		}
		return StagingLlmPass.join(names);
	}

	/** The coverage lines as the follow-up turn carried them, one per entity, for the run's trace. */
	private static String coverageTexts(final List<IdentityCoverage.EntityCoverage> pCoverage)
	{
		final List<String> lines = new ArrayList<String>();
		for (final IdentityCoverage.EntityCoverage coverage : pCoverage)
		{
			lines.add(coverage.followUpText());
		}
		return String.join(" | ", lines);
	}

	private static String leftBehindText(final List<StagingLlmPass.LeftBehind> pLeftBehind)
	{
		final StringBuilder out = new StringBuilder();
		for (final StagingLlmPass.LeftBehind group : pLeftBehind)
		{
			out.append(out.length() == 0 ? "" : "; ").append(group.canonicalEntity).append(" (").append(group.supersededEntity).append("): ")
				.append(StagingLlmPass.renderColumns(StagingLlmPass.columnsOf(java.util.Collections.singletonList(group))));
		}
		return out.toString();
	}

	/**
	 * One {@code origin=LLM} {@code StagingEntity} row already on the project, resolved to what {@link
	 * StagingLlmPass.Session} needs for idempotence: its attribute names, and - when one of its attributes
	 * carries {@code referenceEntity} (the ONLY mechanism this pass ever uses to set it - see {@link
	 * StagingLlmPass}'s own class Javadoc) - the parent entity's name and the name of that reference attribute,
	 * plus (staging-llm-rerun, docs/TARGET-MAPPING.md "Relance de la passe LLM") every {@code TransferFeed} it
	 * already has, so {@link StagingLlmPass.Session#proposeFeed} does not recreate one on a second run.
	 */
	private static StagingLlmPass.ExistingTargetEntity readExistingTargetEntity(final Adaptation pFactory, final Adaptation pEntityRow,
		final Map<String, String> pEntityIdToName)
	{
		final String entityId = pEntityRow.getOccurrencePrimaryKey().format();
		final List<String> attributeNames = new ArrayList<String>();
		String parentEntity = null;
		String parentLinkAttribute = null;
		final List<String> childKeysChildren = new ArrayList<String>();
		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				final String attributeName = attributeRow.getString(FactoryPaths.StagingAttribute.NAME);
				attributeNames.add(attributeName);
				// lot M8b: a CHILD_KEYS attribute a person did not reject already covers its child entity - propose_child_keys is idempotent on it
				if (ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(attributeRow.getString(FactoryPaths.StagingAttribute.DERIVATION))
					&& !"REJECTED".equals(attributeRow.getString(FactoryPaths.StagingAttribute.STATUS)))
				{
					final String child = ChildKeysDerivation.childEntityOrNull(attributeRow.getString(FactoryPaths.StagingAttribute.DERIVATION_ARGUMENT));
					if (child != null)
					{
						childKeysChildren.add(child);
					}
				}
				final String referenceEntityId = attributeRow.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
				if (referenceEntityId != null && parentEntity == null)
				{
					parentEntity = pEntityIdToName.get(referenceEntityId);
					parentLinkAttribute = attributeName;
				}
			}
		}
		finally
		{
			attributeRows.close();
		}
		final List<StagingLlmPass.ExistingFeed> feeds = new ArrayList<StagingLlmPass.ExistingFeed>();
		final RequestResult feedRows = pFactory.getTable(FactoryPaths.TRANSFER_FEED).createRequestResult("./entity=" + CatalogReader.quote(entityId));
		try
		{
			for (Adaptation feedRow = feedRows.nextAdaptation(); feedRow != null; feedRow = feedRows.nextAdaptation())
			{
				final String landingTableKey = StagingLlmSuggester.landingTableKeyOf(pFactory, feedRow.getString(FactoryPaths.TransferFeed.LANDING_TABLE));
				if (landingTableKey == null)
				{
					continue; // an orphaned feed row is invisible to idempotence rather than crashing the whole run over it
				}
				final String feedId = feedRow.getOccurrencePrimaryKey().format();
				feeds.add(new StagingLlmPass.ExistingFeed(feedId, landingTableKey, feedRow.getString(FactoryPaths.TransferFeed.PARENT_FEED),
					feedRow.getString(FactoryPaths.TransferFeed.STATUS), feedRow.getString(FactoryPaths.TransferFeed.VARIANT),
					StagingLlmSuggester.readColumnsOfFeed(pFactory, feedId)));
			}
		}
		finally
		{
			feedRows.close();
		}
		return new StagingLlmPass.ExistingTargetEntity(entityId, pEntityRow.getString(FactoryPaths.StagingEntity.NAME),
			pEntityRow.getString(FactoryPaths.StagingEntity.STATUS), attributeNames, parentEntity, parentLinkAttribute, feeds, childKeysChildren);
	}

	/**
	 * Field names of the landing columns a feed's PROPOSED or accepted mappings read, a {@code constant(...)} rule
	 * excluded (it reads nothing) - what {@link StagingLlmPass.Session#unreadColumns(boolean)} needs from an earlier run.
	 */
	private static List<String> readColumnsOfFeed(final Adaptation pFactory, final String pFeedId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./feed=" + CatalogReader.quote(pFeedId));
		try
		{
			for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
			{
				final String status = mappingRow.getString(FactoryPaths.AttributeMapping.STATUS);
				if (!"PROPOSED".equals(status) && !TechnicalKeyRule.isAcceptedLike(status))
				{
					continue;
				}
				final String pipeline = mappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE);
				if (pipeline != null && pipeline.trim().toLowerCase(Locale.ROOT).startsWith("constant("))
				{
					continue;
				}
				final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
				final Adaptation landingColumn = landingColumnId == null ? null
					: pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingColumnId));
				final String fieldName = landingColumn == null ? null : landingColumn.getString(FactoryPaths.LandingColumn.FIELD_NAME);
				if (fieldName != null && !out.contains(fieldName))
				{
					out.add(fieldName);
				}
			}
		}
		finally
		{
			mappingRows.close();
		}
		return out;
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	// ------------------------------------------------------------------------------------------------------- writing

	/**
	 * Once a target entity has at least one feed, the rule entities it covers ({@code memberEntities}) are no
	 * longer a choice of their own: the target is fed straight from their landing tables, never from their
	 * staging tables, and a member is never promoted to master ({@code MasterModel}). Moved to {@code
	 * SUPERSEDED} - the status the matching plans already use for "a newer proposal replaced this one" - so a
	 * member a person had already ACCEPTED or REJECTED is left exactly as decided. Returns how many were
	 * moved. Unchanged from the retired pass (same incident, same fix - {@code StagingLlmSuggester}'s own
	 * history before this rewrite).
	 */
	private static int supersedeMembers(final ProcedureContext pContext, final AdaptationTable pEntitiesTable, final String pTargetEntityId,
		final List<String> pMemberIds) throws OperationException
	{
		if (pMemberIds == null || pMemberIds.isEmpty())
		{
			return 0;
		}
		int moved = 0;
		for (final String memberId : pMemberIds)
		{
			final Adaptation member = pEntitiesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(memberId));
			if (member == null || !ReviewLine.PROPOSED.equals(member.getString(FactoryPaths.StagingEntity.STATUS)))
			{
				continue;
			}
			final ValueContextForUpdate value = pContext.getContext(member.getAdaptationName());
			value.setValue(ReviewLine.SUPERSEDED, FactoryPaths.StagingEntity.STATUS);
			pContext.doModifyContent(member, value);
			moved++;
		}
		return moved;
	}

	private static List<String> describe(final List<ProposalChecks.Finding> pFindings)
	{
		final List<String> out = new ArrayList<String>();
		for (final ProposalChecks.Finding finding : pFindings)
		{
			out.add(finding.toString());
		}
		return out;
	}

	private static void write(final Session pSession, final Adaptation pFactory, final String pProjectId, final StagingLlmPass.Session pLlmSession,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Map<String, String> pRuleEntityIdByName,
		final Map<String, String> pExistingEntityIdByName, final ObjectNode pOut) throws OperationException
	{
		final List<StagingLlmPass.CreatedEntity> entities = pLlmSession.createdEntities();
		final List<StagingLlmPass.CreatedFeed> feeds = pLlmSession.createdFeeds();
		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final AdaptationTable feedsTable = pFactory.getTable(FactoryPaths.TRANSFER_FEED);
		final AdaptationTable mappingsTable = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final ArrayNode entitiesNode = pOut.putArray("entities");
		// entities, attributes, feeds, mappings created; rule entities superseded; CHILD_KEYS attributes created (also counted in the attributes)
		final int[] counts = new int[6];

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				// entity name -> id, seeded with every entity already on the project (so a parentEntity naming a
				// PRIOR entity this run never even touched still resolves), then updated as each new one is created
				final Map<String, String> entityIdByName = new HashMap<String, String>(pExistingEntityIdByName);
				// (entityName|attributeName) -> id, seeded lazily below for an ADOPTED entity's pre-existing attributes
				final Map<String, String> attributeIdByKey = new HashMap<String, String>();

				for (final StagingLlmPass.CreatedEntity entity : entities)
				{
					final List<String> memberIds = new ArrayList<String>();
					for (final String memberName : entity.memberEntities)
					{
						final String memberId = pRuleEntityIdByName.get(memberName);
						if (memberId != null)
						{
							memberIds.add(memberId);
						}
					}

					final String entityId;
					int attributeCount = 0;
					if (entity.existingId != null)
					{
						entityId = entity.existingId;
						// merge freshly-computed memberEntities into whatever the row already carries - see the class Javadoc
						StagingLlmSuggester.mergeMemberEntities(pContext, entitiesTable, entityId, memberIds);
					}
					else
					{
						final ValueContextForUpdate entityValue = pContext.getContextForNewOccurrence(entitiesTable);
						entityValue.setValue(pProjectId, FactoryPaths.StagingEntity.PROJECT);
						entityValue.setValue(entity.name, FactoryPaths.StagingEntity.NAME);
						entityValue.setValue(entity.label, FactoryPaths.StagingEntity.LABEL);
						entityValue.setValue(memberIds, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
						entityValue.setValue("LLM", FactoryPaths.StagingEntity.ORIGIN);
						entityValue.setValue(Integer.valueOf(entity.confidence), FactoryPaths.StagingEntity.CONFIDENCE);
						entityValue.setValue(TextLimits.truncate(entity.rationale, StagingLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.StagingEntity.RATIONALE);
						entityValue.setValue("PROPOSED", FactoryPaths.StagingEntity.STATUS);
						entityId = pContext.doCreateOccurrence(entityValue, entitiesTable).getOccurrencePrimaryKey().format();
						entityIdByName.put(entity.name, entityId);
						counts[0]++;

						// technical identity - every target entity needs it, same as a rule-proposed one (StagingRules)
						for (final StagingRules.AttributeProposal technical : StagingRules.technicalAttributeProposals())
						{
							final ValueContextForUpdate technicalValue = pContext.getContextForNewOccurrence(attributesTable);
							technicalValue.setValue(entityId, FactoryPaths.StagingAttribute.ENTITY);
							technicalValue.setValue(technical.name, FactoryPaths.StagingAttribute.NAME);
							technicalValue.setValue(technical.label, FactoryPaths.StagingAttribute.LABEL);
							technicalValue.setValue(technical.dataType, FactoryPaths.StagingAttribute.DATA_TYPE);
							technicalValue.setValue(technical.maxLength, FactoryPaths.StagingAttribute.MAX_LENGTH);
							technicalValue.setValue(Boolean.valueOf(technical.mandatory), FactoryPaths.StagingAttribute.MANDATORY);
							technicalValue.setValue(Boolean.valueOf(technical.businessKey), FactoryPaths.StagingAttribute.BUSINESS_KEY);
							technicalValue.setValue(Boolean.valueOf(technical.primaryKey), FactoryPaths.StagingAttribute.PRIMARY_KEY);
							technicalValue.setValue(Boolean.valueOf(technical.enumerated), FactoryPaths.StagingAttribute.ENUMERATED);
							technicalValue.setValue("LLM", FactoryPaths.StagingAttribute.ORIGIN);
							technicalValue.setValue(Integer.valueOf(technical.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
							technicalValue.setValue(TextLimits.truncate(technical.rationale, StagingLlmSuggester.RATIONALE_MAX_LENGTH),
								FactoryPaths.StagingAttribute.RATIONALE);
							technicalValue.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
							pContext.doCreateOccurrence(technicalValue, attributesTable);
							attributeCount++;
						}

						for (final StagingLlmPass.CreatedAttribute attribute : entity.attributes)
						{
							final ValueContextForUpdate attributeValue = pContext.getContextForNewOccurrence(attributesTable);
							attributeValue.setValue(entityId, FactoryPaths.StagingAttribute.ENTITY);
							attributeValue.setValue(attribute.name, FactoryPaths.StagingAttribute.NAME);
							attributeValue.setValue(attribute.label, FactoryPaths.StagingAttribute.LABEL);
							attributeValue.setValue(attribute.dataType, FactoryPaths.StagingAttribute.DATA_TYPE);
							if (attribute.maxLength != null)
							{
								attributeValue.setValue(attribute.maxLength, FactoryPaths.StagingAttribute.MAX_LENGTH);
							}
							attributeValue.setValue(Boolean.valueOf(attribute.mandatory), FactoryPaths.StagingAttribute.MANDATORY);
							attributeValue.setValue(Boolean.valueOf(attribute.businessKey), FactoryPaths.StagingAttribute.BUSINESS_KEY);
							attributeValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.PRIMARY_KEY);
							attributeValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.ENUMERATED);
							if (attribute.name.equals(entity.parentLinkAttribute))
							{
								final String parentEntityId = entityIdByName.get(entity.parentEntity);
								if (parentEntityId != null)
								{
									attributeValue.setValue(parentEntityId, FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
								}
							}
							attributeValue.setValue("LLM", FactoryPaths.StagingAttribute.ORIGIN);
							// per-field-rationale lot (2026-09-24): the attribute's OWN confidence/rationale (StagingLlmPass falls
							// back to the entity's only when the model left one blank) - never the whole entity's copied onto every field.
							attributeValue.setValue(Integer.valueOf(attribute.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
							attributeValue.setValue(TextLimits.truncate(attribute.rationale, StagingLlmSuggester.RATIONALE_MAX_LENGTH),
								FactoryPaths.StagingAttribute.RATIONALE);
							attributeValue.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
							final String attributeId = pContext.doCreateOccurrence(attributeValue, attributesTable).getOccurrencePrimaryKey().format();
							attributeIdByKey.put(entity.name + "|" + attribute.name, attributeId);
							attributeCount++;
						}
					}
					counts[1] += attributeCount;
					if (entity.existingId == null)
					{
						counts[4] += StagingLlmSuggester.supersedeMembers(pContext, entitiesTable, entityId, memberIds);
					}

					final ObjectNode line = entitiesNode.addObject();
					line.put("name", entity.name);
					line.put("existing", entity.existingId != null);
					line.put("attributes", attributeCount);
				}

				// lot M8b (2026-10-04): the CHILD_KEYS attributes the model proposed on a parent entity - an entity of this run (created just above) or an
				// earlier one (seeded in entityIdByName). PROPOSED like every proposal: a person accepts it, the staging model is regenerated, and the load
				// computes it from the child records (ChildKeysPass).
				for (final StagingLlmPass.CreatedChildKeys keys : pLlmSession.createdChildKeys())
				{
					final String parentEntityId = entityIdByName.get(keys.parentEntity);
					if (parentEntityId == null)
					{
						continue; // defensive only: Session validated the parent against its own entities
					}
					final ValueContextForUpdate keysValue = pContext.getContextForNewOccurrence(attributesTable);
					keysValue.setValue(parentEntityId, FactoryPaths.StagingAttribute.ENTITY);
					keysValue.setValue(keys.name, FactoryPaths.StagingAttribute.NAME);
					keysValue.setValue(keys.label, FactoryPaths.StagingAttribute.LABEL);
					keysValue.setValue("STRING", FactoryPaths.StagingAttribute.DATA_TYPE);
					keysValue.setValue(Integer.valueOf(keys.maxLength()), FactoryPaths.StagingAttribute.MAX_LENGTH);
					keysValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.MANDATORY);
					keysValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.BUSINESS_KEY);
					keysValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.PRIMARY_KEY);
					keysValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.ENUMERATED);
					keysValue.setValue(ChildKeysDerivation.DERIVATION_CHILD_KEYS, FactoryPaths.StagingAttribute.DERIVATION);
					keysValue.setValue(keys.argument, FactoryPaths.StagingAttribute.DERIVATION_ARGUMENT);
					keysValue.setValue("LLM", FactoryPaths.StagingAttribute.ORIGIN);
					keysValue.setValue(Integer.valueOf(keys.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
					keysValue.setValue(TextLimits.truncate(keys.rationale, StagingLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.StagingAttribute.RATIONALE);
					keysValue.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
					pContext.doCreateOccurrence(keysValue, attributesTable);
					counts[1]++;
					counts[5]++;
				}

				// feeds and their mappings, in call order (a child feed always follows the parent it names)
				final Map<String, String> feedIdByFeedKey = new HashMap<String, String>();
				int feedCount = 0;
				int mappingCount = 0;
				for (final StagingLlmPass.CreatedFeed feed : feeds)
				{
					final String entityId = entityIdByName.get(feed.entityName);
					final StagingRules.LandingTableInfo table = pTablesByKey.get(feed.landingTableKey);
					if (entityId == null || table == null)
					{
						continue; // defensive only: every name here came from Session's own validated maps
					}
					final StagingLlmPass.CreatedEntity ownerEntity = StagingLlmSuggester.findEntity(entities, feed.entityName);
					final ValueContextForUpdate feedValue = pContext.getContextForNewOccurrence(feedsTable);
					feedValue.setValue(entityId, FactoryPaths.TransferFeed.ENTITY);
					feedValue.setValue(table.id, FactoryPaths.TransferFeed.LANDING_TABLE);
					final boolean isChildFeed = feed.parentFeedKey != null || feed.parentFeedId != null;
					if (isChildFeed)
					{
						// staging-llm-rerun (docs/TARGET-MAPPING.md "Relance de la passe LLM"): the parent feed is
						// either one THIS run just created (parentFeedKey, looked up in the id map built as we go)
						// or a PRE-EXISTING row from an earlier run (parentFeedId, already a real persisted id -
						// StagingLlmPass.Session#resolveParentFeedRef never sets both at once).
						final String parentFeedId = feed.parentFeedKey != null ? feedIdByFeedKey.get(feed.parentFeedKey) : feed.parentFeedId;
						if (parentFeedId != null)
						{
							feedValue.setValue(parentFeedId, FactoryPaths.TransferFeed.PARENT_FEED);
						}
						if (ownerEntity != null && ownerEntity.parentLinkField != null)
						{
							feedValue.setValue(ownerEntity.parentLinkField, FactoryPaths.TransferFeed.PARENT_LINK_FIELD);
						}
					}
					if (feed.sourceIdPipeline != null)
					{
						feedValue.setValue(feed.sourceIdPipeline, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE);
					}
					if (feed.variant != null)
					{
						feedValue.setValue(feed.variant, FactoryPaths.TransferFeed.VARIANT);
					}
					feedValue.setValue("LLM", FactoryPaths.TransferFeed.ORIGIN);
					feedValue.setValue(Integer.valueOf(feed.confidence), FactoryPaths.TransferFeed.CONFIDENCE);
					feedValue.setValue(TextLimits.truncate(feed.rationale, StagingLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.TransferFeed.RATIONALE);
					feedValue.setValue("PROPOSED", FactoryPaths.TransferFeed.STATUS);
					final String feedId = pContext.doCreateOccurrence(feedValue, feedsTable).getOccurrencePrimaryKey().format();
					feedIdByFeedKey.put(feed.feedKey, feedId);
					feedCount++;

					for (final StagingLlmPass.CreatedFeedRule rule : feed.rules)
					{
						// bug fix, docs/TARGET-MAPPING.md sec 3.3/4.2 (project Personnes, 2026-09-23): StagingLlmPass.Session
						// already drops a CHILD feed's rule on its own link-to-parent attribute before it ever reaches a
						// CreatedFeed - this is defence in depth only, for a CreatedFeed built some other way (a run recorded
						// before this fix, or a future caller that skips Session). Writing it would let StagingLoader treat
						// the structural link as an ordinary foreign key again - see StagingLoader#processRow's own Javadoc.
						if (isChildFeed && ownerEntity != null && rule.attribute.equals(ownerEntity.parentLinkAttribute))
						{
							continue;
						}
						final String attributeId = StagingLlmSuggester.resolveAttributeId(pFactory, attributeIdByKey, feed.entityName, rule.attribute, entityId);
						final StagingRules.LandingColumnInfo column = StagingLlmSuggester.findColumn(table, rule.landingColumn);
						if (attributeId == null || column == null)
						{
							continue; // defensive only
						}
						final ValueContextForUpdate mappingValue = pContext.getContextForNewOccurrence(mappingsTable);
						mappingValue.setValue(attributeId, FactoryPaths.AttributeMapping.ATTRIBUTE);
						mappingValue.setValue(column.id, FactoryPaths.AttributeMapping.LANDING_COLUMN);
						mappingValue.setValue(feedId, FactoryPaths.AttributeMapping.FEED);
						if (rule.pipeline != null)
						{
							mappingValue.setValue("NONE", FactoryPaths.AttributeMapping.TRANSFORMATION);
							mappingValue.setValue(rule.pipeline, FactoryPaths.AttributeMapping.PIPELINE);
						}
						else
						{
							// rule-pass-style default, from the COLUMN's inferred type (docs/TARGET-MAPPING.md sec 4.2)
							final StagingRules.MappingProposal typeDriven = StagingRules.typeDrivenMapping(column.id, column.inferredType);
							mappingValue.setValue(typeDriven.transformation, FactoryPaths.AttributeMapping.TRANSFORMATION);
							if (typeDriven.argument != null)
							{
								mappingValue.setValue(typeDriven.argument, FactoryPaths.AttributeMapping.ARGUMENT);
							}
						}
						mappingValue.setValue("LLM", FactoryPaths.AttributeMapping.ORIGIN);
						// per-field-rationale lot (2026-09-24): the rule's OWN confidence/rationale (StagingLlmPass falls back to
						// the feed's only when the model left one blank) - never the whole feed's copied onto every mapping.
						mappingValue.setValue(Integer.valueOf(rule.confidence), FactoryPaths.AttributeMapping.CONFIDENCE);
						mappingValue.setValue(com.on.ebx.factory.wizard.EngineMessageCodec.fit(rule.rationale, StagingLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.AttributeMapping.RATIONALE);
						mappingValue.setValue("PROPOSED", FactoryPaths.AttributeMapping.STATUS);
						pContext.doCreateOccurrence(mappingValue, mappingsTable);
						mappingCount++;
					}
				}
				counts[2] = feedCount;
				counts[3] = mappingCount;
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveStagingProposals", "Writing the language-model staging proposals of project " + pProjectId + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProjectId);
		}
		pOut.put("entitiesCreated", counts[0]);
		pOut.put("attributesCreated", counts[1]);
		pOut.put("feedsCreated", counts[2]);
		pOut.put("mappingsCreated", counts[3]);
		pOut.put("entitiesSuperseded", counts[4]);
		pOut.put("childKeysCreated", counts[5]);
	}

	/**
	 * An entity adopted from an earlier run ({@link StagingLlmPass.CreatedEntity#existingId} set) already has
	 * its OWN {@code memberEntities}, decided when it was first written - a NEW feed this run may still cover a
	 * landing table (and therefore a rule entity) that row never listed, so this merges rather than overwrites:
	 * read-modify-write, adding only ids not already present. {@link #supersedeMembers} is still called on the
	 * FULL merged set right after, so a member added only now is superseded too, exactly as if it had been
	 * there from the start.
	 */
	private static void mergeMemberEntities(final ProcedureContext pContext, final AdaptationTable pEntitiesTable, final String pEntityId,
		final List<String> pNewMemberIds) throws OperationException
	{
		final Adaptation row = pEntitiesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pEntityId));
		if (row == null)
		{
			return;
		}
		final List<?> existing = row.getList(FactoryPaths.StagingEntity.MEMBER_ENTITIES);
		final List<String> merged = new ArrayList<String>();
		if (existing != null)
		{
			for (final Object id : existing)
			{
				merged.add(String.valueOf(id));
			}
		}
		boolean changed = false;
		for (final String newId : pNewMemberIds)
		{
			if (!merged.contains(newId))
			{
				merged.add(newId);
				changed = true;
			}
		}
		if (changed)
		{
			final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
			value.setValue(merged, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			pContext.doModifyContent(row, value);
		}
		StagingLlmSuggester.supersedeMembers(pContext, pEntitiesTable, pEntityId, merged);
	}

	/** An attribute id for {@code (entityName, attributeName)} - from THIS run's writes, or read from EBX for an entity adopted from an earlier run (cached). */
	private static String resolveAttributeId(final Adaptation pFactory, final Map<String, String> pAttributeIdByKey, final String pEntityName,
		final String pAttributeName, final String pEntityId)
	{
		final String key = pEntityName + "|" + pAttributeName;
		final String cached = pAttributeIdByKey.get(key);
		if (cached != null)
		{
			return cached;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
			.createRequestResult("./entity=" + CatalogReader.quote(pEntityId) + " and ./name=" + CatalogReader.quote(pAttributeName));
		final Adaptation row;
		try
		{
			row = rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
		if (row == null)
		{
			return null;
		}
		final String id = row.getOccurrencePrimaryKey().format();
		pAttributeIdByKey.put(key, id);
		return id;
	}

	private static StagingLlmPass.CreatedEntity findEntity(final List<StagingLlmPass.CreatedEntity> pEntities, final String pName)
	{
		for (final StagingLlmPass.CreatedEntity entity : pEntities)
		{
			if (entity.name.equals(pName))
			{
				return entity;
			}
		}
		return null;
	}

	private static StagingRules.LandingColumnInfo findColumn(final StagingRules.LandingTableInfo pTable, final String pFieldName)
	{
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			if (column.fieldName.equals(pFieldName))
			{
				return column;
			}
		}
		return null;
	}
}
