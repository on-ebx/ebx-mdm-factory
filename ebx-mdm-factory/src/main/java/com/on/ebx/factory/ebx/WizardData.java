package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ChangeColumnRule;
import com.on.ebx.factory.landing.KeyCertainty;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.review.ProposalWording;
import com.on.ebx.factory.review.ReviewEntityFormat;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewRules;
import com.on.ebx.factory.staging.MappingFollowRule;
import com.on.ebx.factory.wizard.DeducedScanEligibility;
import com.on.ebx.factory.wizard.WizardRules;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Thin EBX-bound reader and writer behind the "Assistant de projet" wizard: reads the project's rows out
 * of the factory dataset (and the data catalog, read-only, through {@link CatalogReader}) into a plain
 * {@link WizardSnapshot}, and writes the wizard's own small edits (create/update the project, add/remove
 * a source, toggle a landing table's scope, edit its proposed key). The long-running actions themselves
 * (plan/generate the landing, generate its flows, load it, suggest the staging with or without the
 * language model) are NOT reimplemented here: {@code ProjectWizardUserService} calls
 * {@link LandingPlanner}/{@link LandingGenerator}/{@link LandingFlows}/{@link StagingSuggester}/
 * {@link StagingLlmSuggester} directly, exactly like {@code FactoryResource} does, and a proposal's
 * Accept/Reject reuses {@link ProposalReviews#decide} - never a second decision path (this class does not
 * duplicate any of that logic, only the plain reads {@link WizardSnapshot} needs and the handful of
 * writes {@link WizardRules}'s own validated drafts produce).
 * <p>
 * Every write here runs in a {@link ProgrammaticService#createForSession(Session,
 * com.onwbp.adaptation.AdaptationHome) session procedure} with the CALLING user's own session, never a
 * system session and never {@code setAllPrivileges} - same rule, and the same reason, as
 * {@link ProposalReviews}: EBX's own revision history attributes the edit to that person.
 * <p>
 * <b>EBX API assumptions made here that could not be exercised against a live instance</b> (thin,
 * compile-only class - see the module's final report): {@code ProcedureContext.doDelete(AdaptationName,
 * boolean)} is used for {@link #removeSource} exactly as the sibling data-catalog module's
 * {@code Favorites}/{@code Tagging} classes use it (signature confirmed by {@code javap} against
 * {@code ebx-lib.jar}, 2026-09-20: {@code doDelete(AdaptationName, boolean)} throws
 * {@code OperationException}/{@code ConstraintViolationException}) - never run here.
 */
public final class WizardData
{
	private WizardData()
	{
	}

	// -------------------------------------------------------------------------------------- connector facts (live fix)

	// sources-landing live fix (2026-09-24): the same "read as data, no compile dependency on ebx-connectors"
	// duplication SampleSourceImporter/DeliveryDomainReader already apply, for the same reason - only the
	// connector's OWN type/label are needed here, nothing else.
	private static final String CONNECTORS_DATASPACE = "Connectors";
	private static final String CONNECTORS_DATASET = "Connectors";
	private static final Path CONNECTOR_TABLE_PATH = Path.parse("/root/Connector");
	private static final Path CONNECTOR_TYPE_PATH = Path.parse("./type");
	private static final Path CONNECTOR_LABEL_PATH = Path.parse("./label");

	/** {@code Connector.type}/{@code Connector.label} of one connector id - both empty when the Connectors dataspace, the connector row, or the id itself is missing. */
	private static final class ConnectorFacts
	{
		final String type;
		final String label;

		ConnectorFacts(final String pType, final String pLabel)
		{
			this.type = pType == null ? "" : pType;
			this.label = pLabel == null ? "" : pLabel;
		}
	}

	/**
	 * Sources-landing live fix (2026-09-24, project A): measured live that {@code Source.platform} (the data
	 * catalog's OWN field, what {@link #sourceInfo} used to read for the Review screen's "kind" chip) is a
	 * coarse category - {@code OTHER} for both {@code PersonnesSalesforceSample} and {@code
	 * PersonnesSapSample}, two genuine {@code sample-file} connectors - never the string {@code sample-file}
	 * itself. The connector's OWN {@code type} field (in the Connectors dataspace, {@code
	 * PersonnesSalesforceSample.type = "sample-file"}, confirmed live the same day) is the field that
	 * actually carries this, exactly what {@code SourceOnboarder.Facts#connectorType} already reads (through
	 * {@code connectors_describe}) for the still-in-progress cards; this reads the same fact directly off the
	 * Connectors dataspace instead, since {@link #sourceInfo} builds the Review screen's snapshot on every
	 * render with no session/MCP round trip today, and adding one here for a handful of ready sources is not
	 * worth the extra failure mode.
	 */
	private static ConnectorFacts connectorFacts(final Repository pRepository, final String pConnectorId)
	{
		if (pRepository == null || pConnectorId == null || pConnectorId.trim().isEmpty())
		{
			return new ConnectorFacts("", "");
		}
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(WizardData.CONNECTORS_DATASPACE));
		final Adaptation connectors = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(WizardData.CONNECTORS_DATASET));
		if (connectors == null)
		{
			return new ConnectorFacts("", "");
		}
		final Adaptation row = WizardData.byKey(connectors.getTable(WizardData.CONNECTOR_TABLE_PATH), PrimaryKey.parseString(pConnectorId));
		if (row == null)
		{
			return new ConnectorFacts("", "");
		}
		return new ConnectorFacts(WizardData.text(row, WizardData.CONNECTOR_TYPE_PATH), WizardData.text(row, WizardData.CONNECTOR_LABEL_PATH));
	}

	/** UX lot C4 (2026-10-14): the connection's own type ({@code ""} when the connection is not found) - what {@code NewTableSelection#selectNew} reads for a plan made outside the wizard page (the deduced-scan job). A plain table read, no MCP call. */
	public static String connectorTypeOf(final Repository pRepository, final String pConnectorId)
	{
		return WizardData.connectorFacts(pRepository, pConnectorId).type;
	}

	/** UX lot U2 (2026-10-13): the connection's own label ({@code ""} when the connection is not found) - the name proposed for a new application's code when the catalog source's own label is a technical one. A plain table read, no MCP call. */
	public static String connectorLabelOf(final Repository pRepository, final String pConnectorId)
	{
		return WizardData.connectorFacts(pRepository, pConnectorId).label;
	}

	// ------------------------------------------------------------------------------ deduced-scan eligibility (fix)

	/**
	 * {@code hasCatalogOperation} per {@code connectorId|Connector.type}: a connection's operation set follows
	 * its type, and the wizard re-renders on every poll - one {@code connectors_describe} per connection and
	 * type for the life of the module, not one per render. A failed describe is never cached (the next render
	 * asks again).
	 */
	private static final java.util.concurrent.ConcurrentMap<String, Boolean> CATALOG_OPERATION_BY_CONNECTOR = new java.util.concurrent.ConcurrentHashMap<String, Boolean>();

	/**
	 * Fix (2026-09-27, project A, Personnes_SAP_Sample): sets {@link WizardSnapshot.SourceInfo#deducedScan} on
	 * every application with no table, by the rule {@code DeducedScanRun}'s LIST stage applies ({@link
	 * DeducedScanEligibility#of}), so the Review card offers "Run a deduced scan" only where that stage would
	 * accept the connection. Kept out of {@link #readSnapshot}, which background runs call too: only the wizard
	 * page's own model needs it.
	 */
	public static void attachDeducedScanEligibility(final Adaptation pFactory, final Session pSession, final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (!source.tables.isEmpty() || source.connector.isEmpty())
			{
				continue;
			}
			final String type = WizardData.connectorFacts(pFactory.getHome().getRepository(), source.connector).type;
			final String cacheKey = source.connector + "|" + type;
			final Boolean cached = WizardData.CATALOG_OPERATION_BY_CONNECTOR.get(cacheKey);
			if (cached != null)
			{
				source.deducedScan = DeducedScanEligibility.of(null, cached.booleanValue());
				continue;
			}
			final DeducedScanOperations.Described described = DeducedScanOperations.describe(pSession, pFactory, source.connector);
			source.deducedScan = DeducedScanEligibility.of(described.error, described.hasCatalogOperation);
			if (described.error == null)
			{
				WizardData.CATALOG_OPERATION_BY_CONNECTOR.put(cacheKey, Boolean.valueOf(described.hasCatalogOperation));
			}
			else
			{
				source.deducedScanError = described.error;
			}
		}
	}

	/**
	 * UX lot U2 (2026-10-13): sets {@link WizardSnapshot.SourceInfo#unplannedAssets} on every application with no planned table and a catalog
	 * source, so its card can say WHY there is nothing to tick ("Account: no column scanned yet") instead of "0 tables". Read as the person
	 * (audit F-02). Kept out of {@link #readSnapshot}, which background runs call too: only the wizard page's own model needs it.
	 */
	public static void attachUnplannedAssets(final Adaptation pFactory, final Session pSession, final WizardSnapshot pSnapshot)
	{
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null)
		{
			return;
		}
		final CatalogReader.Reader reader = CatalogReader.Reader.of(pSession);
		for (final WizardSnapshot.SourceInfo source : pSnapshot.sources)
		{
			if (source.tables.isEmpty() && !source.catalogSourceId.isEmpty())
			{
				source.unplannedAssets.addAll(CatalogReader.assetBriefs(reader, catalog, source.catalogSourceId));
			}
		}
	}

	// -------------------------------------------------------------------------------------------------- reading

	/**
	 * Second permissions pass (2026-10-14): the snapshot a SCREEN shows, read with the user's view - only the rows the user's EBX permissions let them
	 * see (records of every Factory table it reads, through {@link UserView#rows}/{@link UserView#lookup}) and only the fields not hidden from them
	 * (dataset-level rules, and record-dependent rules per row up to {@link UserView#MAX_RECORD_FIELD_CHECKS}); a hidden project reads like no project.
	 * The scope covers this read only: nothing in it writes, queues or calls a server.
	 */
	public static WizardSnapshot readSnapshot(final UserView pView, final Adaptation pFactory, final String pProjectId, final Session pSession)
	{
		return UserView.scoped(pView, () -> WizardData.readSnapshot(pFactory, pProjectId, pSession));
	}

	/**
	 * The engine's snapshot (every row, every field), what the background runs read; a screen calls {@link #readSnapshot(UserView, Adaptation, String,
	 * Session)}. Inside a {@link UserView#scoped} read, the scope's view applies.
	 *
	 * @param pProjectId null for a brand new, not-yet-created project: every list stays empty, {@link WizardSnapshot#project} stays null.
	 */
	public static WizardSnapshot readSnapshot(final Adaptation pFactory, final String pProjectId, final Session pSession)
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		snapshot.administrator = pSession != null && pSession.isUserInRole(Profile.ADMINISTRATOR);
		// audit F-02: the design and run actions, and the language-model passes, belong to the DESIGN right
		snapshot.designer = FactoryAccess.hasInPerson(pSession, com.on.ebx.factory.core.FactoryRight.DESIGN);
		snapshot.right = FactoryAccess.granted(pSession);

		final Adaptation settings = WizardData.byKey(pFactory.getTable(FactoryPaths.SETTINGS), PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		final String llm = settings == null ? null : WizardData.str(settings, FactoryPaths.Settings.LLM_CONNECTOR);
		snapshot.llmConfigured = llm != null && !llm.trim().isEmpty();
		// wizard-llm-value-sharing lot (2026-09-28): the instance cap exactly as LlmDataPolicy reads it (same
		// parse, same fail-closed default), and the connector's own label for step 1's consequence line -
		// connectorFacts is a plain table read, no MCP round trip (see its own Javadoc), so it is safe here.
		snapshot.llmValueSharingMax = LlmDataPolicy.level(settings == null ? null : WizardData.str(settings, FactoryPaths.Settings.LLM_VALUE_SHARING_MAX),
			LlmDataPolicy.Level.SHAPES_ONLY).name();
		if (snapshot.llmConfigured)
		{
			final String connectorLabel = WizardData.connectorFacts(pFactory.getHome().getRepository(), llm.trim()).label;
			snapshot.llmConnectorLabel = connectorLabel.isEmpty() ? llm.trim() : connectorLabel;
		}

		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		snapshot.catalogAvailable = catalog != null;
		// audit F-02 (catalog reads): everything this snapshot reads in the catalog is shown in the wizard, so it is read as the
		// person (the catalog's rights hide rows from a person without a catalog right); a job that reads a snapshot passes its
		// system session, which reads everything
		final CatalogReader.Reader catalogReader = CatalogReader.Reader.of(pSession);
		if (catalog != null)
		{
			for (final CatalogReader.Source source : CatalogReader.sources(catalogReader, catalog))
			{
				snapshot.catalogSources.add(new WizardSnapshot.CatalogSourceInfo(source.id, source.label, source.platform, source.connector));
			}
		}
		snapshot.catalogRestricted = catalogReader.restricted();

		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			return snapshot;
		}
		final Adaptation project = WizardData.byKey(pFactory.getTable(FactoryPaths.PROJECT), PrimaryKey.parseString(pProjectId));
		if (project == null)
		{
			return snapshot;
		}
		snapshot.project = WizardData.projectInfo(project);
		// live bug fix (2026-09-25, PersVol2 volume test): see WizardSnapshot.ProjectInfo#llmDataPolicyLevel's
		// own Javadoc - the level actually in force for THIS project, read the same way LlmDataPolicy's other
		// callers do (staging/matching LLM passes), never assumed.
		snapshot.project.llmDataPolicyLevel = LlmDataPolicy.of(pFactory).levelFor(project).name();
		// audit F-32: every table below is read once for the whole snapshot (see Reads), not once per source/table/entity/attribute/feed/plan
		final Reads reads = new Reads(pFactory, pProjectId, catalogReader);
		WizardData.readSources(reads, snapshot);
		if ("GREENFIELD".equals(snapshot.project.mode))
		{
			WizardData.readStagingEntities(reads, snapshot);
			WizardData.attachFeeds(reads, snapshot);
			WizardData.readMatchPlans(reads, snapshot);
			WizardData.attachProposalLines(pFactory, pProjectId, pSession == null ? Locale.ENGLISH : pSession.getLocale(), snapshot);
			WizardData.attachServiceSuggestions(pFactory, pProjectId, snapshot);
			WizardData.readProposedCounts(pFactory, pProjectId, snapshot);
		}
		WizardData.readProjectSteps(reads, snapshot);
		WizardData.readDistributionActive(pFactory, snapshot);
		// lot C1-B: the CENTRALISATION path reads its own tables (a CONSOLIDATION project has none of these rows, and pays nothing)
		if (WizardRules.CENTRALISATION.equals(snapshot.project.style))
		{
			AccelData.read(pFactory, pProjectId, pSession == null ? Locale.ENGLISH : pSession.getLocale(), snapshot);
		}
		snapshot.catalogRestricted = catalogReader.restricted();
		return snapshot;
	}

	/**
	 * Audit F-31 (2026-10-06): the code of the project {@code pProjectId} names - what {@code readSnapshot(...).project.code} answered, without
	 * reading the whole snapshot (the wizard asked for it 31 times per click, to return one string). One lookup by primary key, which is the
	 * index ({@code Project}'s key is {@code /code}); null when no project has that id, "" when its code is empty, exactly as the snapshot's
	 * {@code project} was null or carried the text. Never cached: a project deleted in this same service instance must read as gone.
	 */
	public static String projectCode(final Adaptation pFactory, final String pProjectId)
	{
		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			return null;
		}
		final Adaptation project = WizardData.byKey(pFactory.getTable(FactoryPaths.PROJECT), PrimaryKey.parseString(pProjectId));
		return project == null ? null : WizardData.text(project, FactoryPaths.Project.CODE);
	}

	/**
	 * Distribution lot 3 (wizard UI, 2026-09-24): best-effort read of {@link WizardSnapshot#distributionActive}
	 * - see that field's own Javadoc. {@code DistributionReader.listTargets} throws {@code OperationException}
	 * only when the project itself cannot be found (already excluded here by the caller's own null-project
	 * guard) or reads the table with a plain {@code RequestResult} - either way, a failure here must never take
	 * down the rest of the snapshot (the wizard's most-used read), so it is swallowed and leaves the field
	 * {@code false}, same discipline as {@link #attachFeeds}'s own defensive reads elsewhere in this class.
	 */
	private static void readDistributionActive(final Adaptation pFactory, final WizardSnapshot pSnapshot)
	{
		if (pSnapshot.project == null)
		{
			return;
		}
		try
		{
			final com.fasterxml.jackson.databind.JsonNode targets = DistributionReader.listTargets(pFactory, pSnapshot.project.code).path("targets");
			for (final com.fasterxml.jackson.databind.JsonNode target : targets)
			{
				final String state = target.path("state").asText("");
				if (FactoryPaths.DistributionTarget.STATE_ARMED.equals(state) || FactoryPaths.DistributionTarget.STATE_PAUSED.equals(state))
				{
					pSnapshot.distributionActive = true;
					return;
				}
			}
		}
		catch (final OperationException | RuntimeException ex)
		{
			// best-effort - see this method's own Javadoc
		}
	}

	/**
	 * Pipeline-buttons lot: every project-scope step past staging suggestion - see
	 * {@link WizardSnapshot#projectSteps}'s own Javadoc for the exact step list and why {@code
	 * MATCHING_CONFIG}/{@code MATCHING_RUN} are excluded. {@link PipelineSteps} is package-private in this
	 * same package: reused directly, never re-derived (same "empty scope means the whole project, stored as
	 * NULL, never the empty string" fix {@code PipelineSteps.scopeEquals} already carries).
	 */
	private static final String[] PROJECT_STEPS = { "STAGING_SUGGEST", "QUALITY_SUGGEST", "QUALITY_SUGGEST_LLM", "CAPABILITY_SUGGEST_LLM", "STAGING_MODEL",
		"STAGING_DATASET", "STAGING_LOAD", "MASTER_MODEL", "MASTER_DATASET", "MASTER_LOAD", "MATCHING_SUGGEST_LLM" };

	private static void readProjectSteps(final Reads pReads, final WizardSnapshot pSnapshot)
	{
		if (pReads.projectId == null || pReads.projectId.trim().isEmpty())
		{
			return;
		}
		for (final String step : WizardData.PROJECT_STEPS)
		{
			final Adaptation latest = pReads.steps().projectWide(step);
			if (latest != null)
			{
				final WizardSnapshot.PipelineStepInfo info = new WizardSnapshot.PipelineStepInfo(step, WizardData.text(latest, FactoryPaths.PipelineStep.STATUS),
					WizardData.text(latest, FactoryPaths.PipelineStep.MESSAGE), WizardData.startedAtMillis(latest));
				if ("MATCHING_SUGGEST_LLM".equals(step))
				{
					// UX lot U1 (2026-10-13): the model's own words - its skip notes and refusals - say why an accepted object got no matching plan (step 6's "No plan for X" card)
					info.detail = WizardData.text(latest, FactoryPaths.PipelineStep.DETAIL);
				}
				pSnapshot.projectSteps.add(info);
			}
		}
	}

	/** {@code PipelineStep.startedAt} in epoch millis, 0 when unset - see {@link WizardSnapshot.PipelineStepInfo#startedAtMillis}'s own Javadoc. */
	private static long startedAtMillis(final Adaptation pRow)
	{
		final Object value = WizardData.val(pRow, FactoryPaths.PipelineStep.STARTED_AT);
		return value instanceof Date ? ((Date) value).getTime() : 0L;
	}

	/** Every {@code MatchPlan} of a promoted entity of this project (step 6) - see {@link WizardSnapshot#matchPlans}. */
	private static void readMatchPlans(final Reads pReads, final WizardSnapshot pSnapshot)
	{
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			for (final Adaptation row : pReads.plansOf(entity.id))
			{
				final WizardSnapshot.MatchPlanInfo plan = new WizardSnapshot.MatchPlanInfo();
				plan.id = row.getOccurrencePrimaryKey().format();
				plan.entityName = entity.name;
				final String origin = WizardData.text(row, FactoryPaths.MatchPlan.ORIGIN);
				plan.origin = origin.isEmpty() ? "RULE" : origin;
				final Object confidence = WizardData.val(row, FactoryPaths.MatchPlan.CONFIDENCE);
				plan.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
				plan.rationale = WizardData.text(row, FactoryPaths.MatchPlan.RATIONALE);
				final String status = WizardData.text(row, FactoryPaths.MatchPlan.STATUS);
				plan.status = status.isEmpty() ? "PROPOSED" : status;
				plan.requirements = WizardData.text(row, FactoryPaths.MatchPlan.REQUIREMENTS);
				plan.message = WizardData.text(row, FactoryPaths.MatchPlan.MESSAGE);
				plan.reviewComment = WizardData.text(row, FactoryPaths.MatchPlan.REVIEW_COMMENT);
				plan.runStep = WizardData.pipelineStepInfo(pReads.steps().scopedOrWide("MATCHING_RUN", entity.name), "MATCHING_RUN", true);
				pSnapshot.matchPlans.add(plan);
			}
		}
	}

	/**
	 * Long-passes-visible lot (2026-09-23): the {@link WizardSnapshot.PipelineStepInfo} of a latest {@code PipelineStep} row ({@code pLatest}, found
	 * by {@link PipelineStepIndex} - one scan per snapshot since audit F-32, a query per plan before), or null if it never ran - same "latest per
	 * (step, scope)" idea as {@link #readProjectSteps}/{@link #readPipelineSteps}. Wave 2 (2026-09-30): the index is filtered on the PROJECT too -
	 * the predicate used to be step + scope only, so two projects with an entity of the same name (Personne on several volume tests) read each
	 * other's latest run; harmless for a status line, wrong now that the row also carries the stored matching results ({@link
	 * WizardSnapshot.PipelineStepInfo#detail}).
	 */
	private static WizardSnapshot.PipelineStepInfo pipelineStepInfo(final Adaptation pLatest, final String pStep, final boolean pWithDetail)
	{
		if (pLatest == null)
		{
			return null;
		}
		final WizardSnapshot.PipelineStepInfo info = new WizardSnapshot.PipelineStepInfo(pStep, WizardData.text(pLatest, FactoryPaths.PipelineStep.STATUS),
			WizardData.text(pLatest, FactoryPaths.PipelineStep.MESSAGE), WizardData.startedAtMillis(pLatest));
		if (pWithDetail)
		{
			info.detail = WizardData.text(pLatest, FactoryPaths.PipelineStep.DETAIL);
		}
		return info;
	}

	/**
	 * Inline-reviews lot (2026-09-23): every {@code MAPPING_STEP}/{@code QUALITY}/{@code SCRIPT_CONSTRAINT}
	 * proposal of the project, read through {@link ProposalReviews#lines} - the SAME reader the Proposal
	 * review screen itself uses, never a second implementation - then grouped under the {@link
	 * WizardSnapshot.StagingEntityInfo} it concerns by matching {@link ReviewLine#element} against that
	 * entity's own name: {@code ProposalReviews} builds a {@code QUALITY}/{@code SCRIPT_CONSTRAINT} line's
	 * element as {@code entityName} (entity-level) or {@code entityName + "." + attributeName} (attribute-
	 * level), and a {@code MAPPING_STEP} line's element AS ITS PARENT MAPPING'S OWN ({@code sourceColumn +
	 * " -> " + entityName + "." + attributeName}) - so a mapping step is matched on the part after the arrow.
	 * Must run AFTER {@link #readStagingEntities} (needs the entity list to match against) and before {@link
	 * #readProposedCounts} (counts the natures this grouped, rather than scanning the tables a second time).
	 */
	private static void attachProposalLines(final Adaptation pFactory, final String pProjectId, final Locale pLocale, final WizardSnapshot pSnapshot)
	{
		final List<ReviewLine> lines = ProposalReviews.lines(pFactory, pProjectId, pLocale);
		pSnapshot.reviewLines = lines;
		pSnapshot.reviewLinesLocale = pLocale;
		for (final ReviewLine line : lines)
		{
			// script-backed-derivation lot (2026-09-23): SCRIPT_DERIVATION joins the inline set the exact same
			// way SCRIPT_CONSTRAINT already does - see fillCapabilityExtras' own call site below (generic,
			// reads whatever CapabilitySuggestion.nature the row actually has).
			if (!ReviewLine.MAPPING_STEP.equals(line.nature) && !ReviewLine.QUALITY.equals(line.nature) && !ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature)
				&& !ReviewLine.SCRIPT_DERIVATION.equals(line.nature))
			{
				continue;
			}
			final String matchAgainst;
			if (ReviewLine.MAPPING_STEP.equals(line.nature))
			{
				final int arrow = line.element.indexOf(" → ");
				matchAgainst = arrow < 0 ? line.element : line.element.substring(arrow + 3);
			}
			else
			{
				matchAgainst = line.element;
			}
			for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
			{
				if (matchAgainst.equals(entity.name) || matchAgainst.startsWith(entity.name + "."))
				{
					final WizardSnapshot.ProposalLineInfo info = new WizardSnapshot.ProposalLineInfo(line);
					if (ReviewLine.QUALITY.equals(line.nature))
					{
						WizardData.fillQualityExtras(pFactory, line.rowId, info);
					}
					else if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature) || ReviewLine.SCRIPT_DERIVATION.equals(line.nature))
					{
						WizardData.fillCapabilityExtras(pFactory, line.rowId, info);
					}
					entity.proposalLines.add(info);
					break;
				}
			}
		}
	}

	/**
	 * Staging-still-to-decide lot (2026-09-27, project ret): every {@code EXTERNAL_SERVICE} {@code
	 * CapabilitySuggestion} of the project, whatever its status, onto {@link
	 * WizardSnapshot.StagingEntityInfo#capabilities} of its own entity (matched by id, never by name). One scan
	 * of the table for the whole project. The attribute name is resolved from the entity's own attribute list
	 * (already read), so no second lookup per row.
	 */
	private static void attachServiceSuggestions(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot)
	{
		final java.util.Map<String, WizardSnapshot.StagingEntityInfo> entitiesById = new java.util.HashMap<String, WizardSnapshot.StagingEntityInfo>();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			entitiesById.put(entity.id, entity);
		}
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), "./project=" + CatalogReader.quote(pProjectId) + " and ./nature=" + CatalogReader.quote(ReviewLine.EXTERNAL_SERVICE));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final WizardSnapshot.StagingEntityInfo entity = entitiesById.get(WizardData.text(row, FactoryPaths.CapabilitySuggestion.ENTITY));
				if (entity == null)
				{
					continue;
				}
				final WizardSnapshot.CapabilityInfo info = new WizardSnapshot.CapabilityInfo();
				info.id = row.getOccurrencePrimaryKey().format();
				info.attributeId = WizardData.text(row, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
				for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
				{
					if (attribute.id.equals(info.attributeId))
					{
						info.attributeName = attribute.name;
						break;
					}
				}
				info.connectorId = WizardData.text(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_ID);
				info.connectorOperation = WizardData.text(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_OPERATION);
				info.ready = !Boolean.FALSE.equals(WizardData.val(row, FactoryPaths.CapabilitySuggestion.READY));
				info.readinessDetail = WizardData.text(row, FactoryPaths.CapabilitySuggestion.READINESS_DETAIL);
				info.binding = WizardData.text(row, FactoryPaths.CapabilitySuggestion.BINDING);
				info.pricing = WizardData.text(row, FactoryPaths.CapabilitySuggestion.PRICING);
				info.pricingNote = WizardData.text(row, FactoryPaths.CapabilitySuggestion.PRICING_NOTE);
				info.proposition = ProposalWording.externalServiceProposition(info.connectorId, info.connectorOperation, info.ready, info.pricing);
				info.rationale = WizardData.text(row, FactoryPaths.CapabilitySuggestion.RATIONALE);
				final Object confidence = WizardData.val(row, FactoryPaths.CapabilitySuggestion.CONFIDENCE);
				info.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
				final String status = WizardData.text(row, FactoryPaths.CapabilitySuggestion.STATUS);
				info.status = status.isEmpty() ? ReviewLine.PROPOSED : status;
				entity.capabilities.add(info);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * {@code QualityRule.severity} lot: the raw severity value and a compact evidence line ({@code
	 * ProposalWording} - the same wording {@code ReviewLine.rationale} already folds these numbers into, see
	 * {@link WizardSnapshot.ProposalLineInfo}'s own Javadoc) - one extra read per {@code QUALITY} line, the
	 * same trade-off every other per-row enrichment in this class already makes.
	 */
	private static void fillQualityExtras(final Adaptation pFactory, final String pRuleId, final WizardSnapshot.ProposalLineInfo pInfo)
	{
		final Adaptation rule = WizardData.lookup(pFactory, FactoryPaths.QUALITY_RULE, pRuleId);
		if (rule == null)
		{
			return;
		}
		final String severity = WizardData.text(rule, FactoryPaths.QualityRule.SEVERITY);
		pInfo.severity = severity.isEmpty() ? "WARN" : severity;
		pInfo.attributeId = WizardData.text(rule, FactoryPaths.QualityRule.ATTRIBUTE);
		final Long valuesSeen = WizardData.longOf(rule, FactoryPaths.QualityRule.VALUES_SEEN);
		final Long valuesFailing = WizardData.longOf(rule, FactoryPaths.QualityRule.VALUES_FAILING);
		pInfo.valuesSeen = valuesSeen;
		pInfo.valuesFailing = valuesFailing;
		final StringBuilder evidence = new StringBuilder(ProposalWording.qualityRuleEvidence(valuesSeen, valuesFailing));
		if (!"MODEL_CONSTRAINT".equals(pInfo.severity))
		{
			final Long lastLoadChecked = WizardData.longOf(rule, FactoryPaths.QualityRule.LAST_LOAD_CHECKED);
			final Long lastLoadFailing = WizardData.longOf(rule, FactoryPaths.QualityRule.LAST_LOAD_FAILING);
			final String lastLoad = ProposalWording.qualityRuleLastLoad(lastLoadChecked, lastLoadFailing);
			if (lastLoad != null && !lastLoad.isEmpty())
			{
				if (evidence.length() > 0)
				{
					evidence.append(' ');
				}
				evidence.append(lastLoad);
			}
		}
		pInfo.qualityEvidence = evidence.toString();
	}

	private static Long longOf(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final Object value = WizardData.val(pRow, pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}

	/**
	 * quality-script-rule lot (2026-09-23): the raw {@code transferPolicy} value and a compact evidence line
	 * ({@code ProposalWording.qualityRuleLastLoad} - the same wording {@code lastLoadChecked}/{@code
	 * lastLoadFailing} already gets for a {@code QualityRule}, reused as-is since both counters share the
	 * exact same shape and meaning) - only for a {@code SCRIPT_CONSTRAINT} row of zone {@code STAGING}: a
	 * {@code MASTER}-zone constraint has nothing to enforce at transfer, {@link WizardSnapshot.ProposalLineInfo
	 * #transferPolicy}/{@code capabilityEvidence} are left blank for it (the wizard then draws no control).
	 */
	/**
	 * apply-visibility lot (2026-09-23): {@code zone}/{@code applyMessage}/{@code appliedModel}/{@code
	 * appliedAt} are read for EVERY {@code SCRIPT_CONSTRAINT} row, whatever its zone - {@code applyMessage} is
	 * exactly the "why does Apply never go away" answer (coordinator finding, project Personnes: ids 10-14,
	 * zone MASTER, stuck ACCEPTED with an applyMessage nobody could see). The transfer-policy control's own
	 * fields ({@code transferPolicy}/{@code capabilityEvidence}/{@code capabilityStaging}) stay {@code
	 * STAGING}-only, unchanged (a {@code MASTER}-zone constraint has nothing to enforce at transfer).
	 */
	private static void fillCapabilityExtras(final Adaptation pFactory, final String pSuggestionId, final WizardSnapshot.ProposalLineInfo pInfo)
	{
		final Adaptation row = WizardData.lookup(pFactory, FactoryPaths.CAPABILITY_SUGGESTION, pSuggestionId);
		if (row == null)
		{
			return;
		}
		pInfo.zone = WizardData.text(row, FactoryPaths.CapabilitySuggestion.ZONE);
		pInfo.applyMessage = WizardData.text(row, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
		if (ReviewLine.SCRIPT_DERIVATION.equals(pInfo.line.nature))
		{
			pInfo.binding = WizardData.text(row, FactoryPaths.CapabilitySuggestion.BINDING);
		}
		if (ReviewLine.APPLIED.equals(pInfo.line.status))
		{
			pInfo.appliedModel = WizardData.text(row, FactoryPaths.CapabilitySuggestion.APPLIED_MODEL);
			final Object appliedAtValue = WizardData.val(row, FactoryPaths.CapabilitySuggestion.APPLIED_AT);
			pInfo.appliedAt = appliedAtValue instanceof Date ? appliedAtValue.toString() : "";
		}
		if (!"STAGING".equals(pInfo.zone))
		{
			return;
		}
		pInfo.capabilityStaging = true;
		pInfo.transferPolicy = WizardData.text(row, FactoryPaths.CapabilitySuggestion.TRANSFER_POLICY);
		final Long lastLoadChecked = WizardData.longOf(row, FactoryPaths.CapabilitySuggestion.LAST_LOAD_CHECKED);
		final Long lastLoadFailing = WizardData.longOf(row, FactoryPaths.CapabilitySuggestion.LAST_LOAD_FAILING);
		pInfo.capabilityEvidence = ProposalWording.qualityRuleLastLoad(lastLoadChecked, lastLoadFailing);
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): every {@code TransferFeed} row of each entity of
	 * the project, read once - right after {@link #readStagingEntities} (needs the entity list, both for the
	 * landing-table/source-system lookups the feed's own label needs and for {@link WizardSnapshot.FeedInfo
	 * #parentEntityName} on a child feed). Mirrors {@code ProposalReviews.addFeedLines}'s own reading (never a
	 * second implementation of "what a feed's mappings are"), but keeps the raw pieces on {@link
	 * WizardSnapshot.FeedInfo} instead of one formatted {@link ReviewLine#rationale} string, since the wizard
	 * renders the tree and the editable pipeline/{@code sourceIdPipeline} fields directly.
	 */
	private static void attachFeeds(final Reads pReads, final WizardSnapshot pSnapshot)
	{
		final java.util.Map<String, String> entityNames = new java.util.HashMap<String, String>();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			entityNames.put(entity.id, entity.name);
		}
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			for (final Adaptation row : pReads.feedsOf(entity.id))
			{
				entity.feeds.add(WizardData.feedInfo(pReads, row, entityNames));
			}
		}
	}

	private static WizardSnapshot.FeedInfo feedInfo(final Reads pReads, final Adaptation pRow, final java.util.Map<String, String> pEntityNames)
	{
		final Adaptation pFactory = pReads.factory;
		final WizardSnapshot.FeedInfo feed = new WizardSnapshot.FeedInfo();
		feed.id = pRow.getOccurrencePrimaryKey().format();
		final String landingTableId = WizardData.text(pRow, FactoryPaths.TransferFeed.LANDING_TABLE);
		final Adaptation landingTable = WizardData.lookup(pFactory, FactoryPaths.LANDING_TABLE, landingTableId);
		feed.landingTableName = landingTable == null ? "" : WizardData.text(landingTable, FactoryPaths.LandingTable.TABLE_NAME);
		final Adaptation projectSource = landingTable == null ? null
			: WizardData.lookup(pFactory, FactoryPaths.PROJECT_SOURCE, WizardData.str(landingTable, FactoryPaths.LandingTable.PROJECT_SOURCE));
		feed.sourceSystem = projectSource == null ? "" : WizardData.text(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		feed.parentFeedId = WizardData.text(pRow, FactoryPaths.TransferFeed.PARENT_FEED);
		if (!feed.parentFeedId.isEmpty())
		{
			final Adaptation parentFeed = WizardData.lookup(pFactory, FactoryPaths.TRANSFER_FEED, feed.parentFeedId);
			final String parentEntityId = parentFeed == null ? null : WizardData.str(parentFeed, FactoryPaths.TransferFeed.ENTITY);
			final String parentEntityName = parentEntityId == null ? null : pEntityNames.get(parentEntityId);
			feed.parentEntityName = parentEntityName == null ? "" : parentEntityName;
			feed.parentLinkField = WizardData.text(pRow, FactoryPaths.TransferFeed.PARENT_LINK_FIELD);
		}
		feed.sourceIdPipeline = WizardData.text(pRow, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE);
		final String origin = WizardData.text(pRow, FactoryPaths.TransferFeed.ORIGIN);
		feed.origin = origin.isEmpty() ? "RULE" : origin;
		final Object confidence = WizardData.val(pRow, FactoryPaths.TransferFeed.CONFIDENCE);
		feed.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
		final String status = WizardData.text(pRow, FactoryPaths.TransferFeed.STATUS);
		feed.status = status.isEmpty() ? "PROPOSED" : status;

		for (final Adaptation mappingRow : pReads.mappingsOfFeed(feed.id))
		{
			feed.mappings.add(WizardData.feedMappingInfo(pFactory, mappingRow));
		}
		return feed;
	}

	private static WizardSnapshot.FeedMappingInfo feedMappingInfo(final Adaptation pFactory, final Adaptation pRow)
	{
		final WizardSnapshot.FeedMappingInfo mapping = new WizardSnapshot.FeedMappingInfo();
		mapping.id = pRow.getOccurrencePrimaryKey().format();
		final Adaptation landingColumn = WizardData.lookup(pFactory, FactoryPaths.LANDING_COLUMN, WizardData.str(pRow, FactoryPaths.AttributeMapping.LANDING_COLUMN));
		mapping.landingColumn = landingColumn == null ? "" : WizardData.text(landingColumn, FactoryPaths.LandingColumn.SOURCE_COLUMN);
		final Adaptation attribute = WizardData.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, WizardData.str(pRow, FactoryPaths.AttributeMapping.ATTRIBUTE));
		mapping.attributeName = attribute == null ? "" : WizardData.text(attribute, FactoryPaths.StagingAttribute.NAME);
		mapping.pipeline = WizardData.text(pRow, FactoryPaths.AttributeMapping.PIPELINE);
		final String transformation = WizardData.text(pRow, FactoryPaths.AttributeMapping.TRANSFORMATION);
		final String argument = WizardData.text(pRow, FactoryPaths.AttributeMapping.ARGUMENT);
		mapping.transformationDisplay = argument.isEmpty() ? transformation : transformation + " (" + argument + ")";
		final String origin = WizardData.text(pRow, FactoryPaths.AttributeMapping.ORIGIN);
		mapping.origin = origin.isEmpty() ? "RULE" : origin;
		final Object confidence = WizardData.val(pRow, FactoryPaths.AttributeMapping.CONFIDENCE);
		mapping.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
		final String status = WizardData.text(pRow, FactoryPaths.AttributeMapping.STATUS);
		mapping.status = status.isEmpty() ? "PROPOSED" : status;
		return mapping;
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): a person editing an {@code AttributeMapping}'s
	 * pipeline in place, wizard-only, same convention as {@link #setQualityRuleSeverity}/{@link
	 * #setCapabilityTransferPolicy}. Marks the row {@code origin = USER} (task brief: "saved as origin USER
	 * when changed by a person") so a later rule/LLM re-run never treats it as still-following its feed
	 * ({@code MappingFollowRule.isEligible} already excludes {@code USER} rows from every cascade). Refused on
	 * a row that is neither {@code PROPOSED} nor already {@code USER} - the same visibility rule the wizard's
	 * own control uses to decide whether to draw the input at all (defence in depth, never trust the client).
	 */
	public static void setMappingPipeline(final Session pSession, final Adaptation pFactory, final String pMappingId, final String pPipeline) throws OperationException
	{
		final String pipeline = pPipeline == null ? "" : pPipeline.trim();
		final AdaptationTable mappings = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = WizardData.byKey(mappings, PrimaryKey.parseString(pMappingId));
				if (row == null)
				{
					return;
				}
				final String status = WizardData.text(row, FactoryPaths.AttributeMapping.STATUS);
				final String origin = WizardData.text(row, FactoryPaths.AttributeMapping.ORIGIN);
				if (!"PROPOSED".equals(status) && !"USER".equals(origin))
				{
					throw EngineRefusal.coded("conflict.pipelineLocked", "This mapping's pipeline can no longer be edited here.");
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pipeline.isEmpty() ? null : pipeline, FactoryPaths.AttributeMapping.PIPELINE);
				value.setValue("USER", FactoryPaths.AttributeMapping.ORIGIN);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): the sibling of {@link #setMappingPipeline} for a
	 * {@code TransferFeed}'s own {@code sourceIdPipeline} - same convention, same {@code origin = USER} marking,
	 * same {@code PROPOSED}-or-{@code USER} refusal.
	 */
	public static void setFeedSourceIdPipeline(final Session pSession, final Adaptation pFactory, final String pFeedId, final String pSourceIdPipeline)
		throws OperationException
	{
		final String sourceIdPipeline = pSourceIdPipeline == null ? "" : pSourceIdPipeline.trim();
		final AdaptationTable feeds = pFactory.getTable(FactoryPaths.TRANSFER_FEED);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = WizardData.byKey(feeds, PrimaryKey.parseString(pFeedId));
				if (row == null)
				{
					return;
				}
				final String status = WizardData.text(row, FactoryPaths.TransferFeed.STATUS);
				final String origin = WizardData.text(row, FactoryPaths.TransferFeed.ORIGIN);
				if (!"PROPOSED".equals(status) && !"USER".equals(origin))
				{
					throw EngineRefusal.coded("conflict.feedKeyLocked", "This feed's source id pipeline can no longer be edited here.");
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(sourceIdPipeline.isEmpty() ? null : sourceIdPipeline, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE);
				value.setValue("USER", FactoryPaths.TransferFeed.ORIGIN);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * apply-visibility lot (2026-09-23): a {@code SCRIPT_CONSTRAINT} row's raw {@code zone} ({@code STAGING}/
	 * {@code MASTER}), for the server-side re-check {@code ProjectWizardUserService.decide}/{@code
	 * applyCapabilityDirectly} run before ever calling {@code CapabilityApplier} - never trust the client, the
	 * same discipline every other decision in this class already follows. Empty when the row is not found.
	 */
	public static String capabilityZone(final Adaptation pFactory, final String pSuggestionId)
	{
		final Adaptation row = WizardData.lookup(pFactory, FactoryPaths.CAPABILITY_SUGGESTION, pSuggestionId);
		return row == null ? "" : WizardData.text(row, FactoryPaths.CapabilitySuggestion.ZONE);
	}

	/**
	 * quality-script-rule lot (2026-09-23): writes a new transfer policy directly - the wizard-only counterpart
	 * to {@code ProposalReviews.decide} for a decision this screen alone offers, same convention {@link
	 * #setQualityRuleSeverity} already established. Unlike that method, NOT refused on an {@code APPLIED} row:
	 * {@code transferPolicy} is read LIVE by {@code ebx.MasterLoader} at every transfer, never baked into the
	 * published script body, so changing it after the constraint is applied is exactly the normal case, not an
	 * edge one. Refuses a row that is not a {@code SCRIPT_CONSTRAINT} of zone {@code STAGING} (nothing to
	 * enforce elsewhere) and any value outside {@code ""}/{@code "WARN"}/{@code "REJECT"}.
	 */
	public static void setCapabilityTransferPolicy(final Session pSession, final Adaptation pFactory, final String pSuggestionId, final String pTransferPolicy)
		throws OperationException
	{
		final String transferPolicy = pTransferPolicy == null ? "" : pTransferPolicy.trim();
		if (!transferPolicy.isEmpty() && !"WARN".equals(transferPolicy) && !"REJECT".equals(transferPolicy))
		{
			throw EngineRefusal.coded("invalid.transferPolicy", "Unknown transfer policy: " + pTransferPolicy, pTransferPolicy);
		}
		final AdaptationTable suggestions = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = WizardData.byKey(suggestions, PrimaryKey.parseString(pSuggestionId));
				if (row == null)
				{
					return;
				}
				if (!"SCRIPT_CONSTRAINT".equals(WizardData.text(row, FactoryPaths.CapabilitySuggestion.NATURE))
					|| !"STAGING".equals(WizardData.text(row, FactoryPaths.CapabilitySuggestion.ZONE)))
				{
					throw EngineRefusal.coded("invalid.transferPolicyScope", "The transfer policy only applies to a script constraint of zone STAGING.");
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(transferPolicy.isEmpty() ? null : transferPolicy, FactoryPaths.CapabilitySuggestion.TRANSFER_POLICY);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Staging-still-to-decide lot (2026-09-27, project ret): "Set aside" / "Set all N aside" on step 4's
	 * "decisions left" card - moves each named {@code CapabilitySuggestion} to {@link ReviewLine#SET_ASIDE} (not
	 * refused, not applied, kept for later). Same wizard-only-write convention as {@link #setQualityRuleSeverity}.
	 * Re-checked server-side, row by row, never trusting the posted ids: the row must belong to {@code
	 * pProjectId}, be of nature {@code EXTERNAL_SERVICE} (a script constraint or derivation is accepted or
	 * rejected, never parked - accepting one applies it to a model) and still be {@code PROPOSED} (a person's
	 * own decision is never overridden - an already decided row is skipped silently, not an error, so a double
	 * click or a stale page cannot undo anything). @return how many rows actually moved.
	 */
	public static int setCapabilitiesAside(final Session pSession, final Adaptation pFactory, final String pProjectId, final List<String> pSuggestionIds)
		throws OperationException
	{
		final AdaptationTable suggestions = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION);
		final int[] moved = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final String id : pSuggestionIds)
				{
					if (id == null || id.trim().isEmpty())
					{
						continue;
					}
					final Adaptation row = WizardData.byKey(suggestions, PrimaryKey.parseString(id.trim()));
					if (row == null || !pProjectId.equals(WizardData.text(row, FactoryPaths.CapabilitySuggestion.PROJECT))
						|| !ReviewLine.EXTERNAL_SERVICE.equals(WizardData.text(row, FactoryPaths.CapabilitySuggestion.NATURE))
						|| !ReviewLine.PROPOSED.equals(WizardData.text(row, FactoryPaths.CapabilitySuggestion.STATUS)))
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(ReviewLine.SET_ASIDE, FactoryPaths.CapabilitySuggestion.STATUS);
					pContext.doModifyContent(row, value);
					moved[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
		return moved[0];
	}

	/**
	 * {@code QualityRule.severity} lot (2026-09-23, coordinator finding on project Personnes): writes a new
	 * severity directly - the wizard-only counterpart to {@code ProposalReviews.decide} for a decision this
	 * screen alone offers (the Proposal review screen is deliberately untouched by this lot). Refuses an
	 * {@code APPLIED} row (nothing left to decide, same rule {@code ReviewRules.canDecide} applies elsewhere)
	 * and any value outside the {@code QUALITY_RULE_SEVERITY} enumeration - defence in depth: the wizard's own
	 * control only ever posts one of the three, but a forged request must still be refused.
	 */
	public static void setQualityRuleSeverity(final Session pSession, final Adaptation pFactory, final String pRuleId, final String pSeverity) throws OperationException
	{
		if (!"WARN".equals(pSeverity) && !"REJECT".equals(pSeverity) && !"MODEL_CONSTRAINT".equals(pSeverity))
		{
			throw EngineRefusal.coded("invalid.severity", "Unknown severity: " + pSeverity, pSeverity);
		}
		final AdaptationTable rules = pFactory.getTable(FactoryPaths.QUALITY_RULE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation rule = WizardData.byKey(rules, PrimaryKey.parseString(pRuleId));
				if (rule == null)
				{
					return;
				}
				if ("APPLIED".equals(WizardData.text(rule, FactoryPaths.QualityRule.STATUS)))
				{
					throw EngineRefusal.coded("conflict.ruleApplied", "This quality rule is already applied: its severity can no longer be changed here.");
				}
				// desk lot L4: a model facet cannot depend on another field - a rule with a condition is never a MODEL_CONSTRAINT
				if ("MODEL_CONSTRAINT".equals(pSeverity) && !WizardData.text(rule, FactoryPaths.QualityRule.CONDITION_ATTRIBUTE).isEmpty())
				{
					throw EngineRefusal.coded("conflict.ruleConditional", "A check that applies only under a condition cannot become a constraint of the model.");
				}
				final ValueContextForUpdate value = pContext.getContext(rule.getAdaptationName());
				value.setValue(pSeverity, FactoryPaths.QualityRule.SEVERITY);
				pContext.doModifyContent(rule, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Step 4's one-line header: how many rows are still {@code PROPOSED} for this project, one count per
	 * nature - see {@link WizardSnapshot#proposedCounts}'s own Javadoc. {@code MAPPING} is free (already
	 * collected while reading the attributes' own mappings); {@code QUALITY}/{@code MAPPING_STEP}/
	 * {@code SCRIPT_CONSTRAINT} are counted off {@link #attachProposalLines}'s own grouping (must run first -
	 * see {@link #readSnapshot}), never a second table scan.
	 */
	private static void readProposedCounts(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot)
	{
		int mappingProposed = 0;
		int mappingDecided = 0;
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			// staging-focus lot (2026-09-23): a SUPERSEDED entity's card is no longer rendered at all (WizardPage
			// skips it) - its own leftover PROPOSED rows must not inflate the "N proposal(s) waiting for review"
			// header either, or the count would point at proposals nobody can actually see or decide any more.
			if (ReviewLine.SUPERSEDED.equals(entity.status))
			{
				continue;
			}
			for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
			{
				for (final WizardSnapshot.MappingInfo mapping : attribute.mappings)
				{
					if ("PROPOSED".equals(mapping.status))
					{
						mappingProposed++;
					}
					// live bug fix (2026-09-25, PersVol2 volume test): see WizardSnapshot#decidedCounts' own
					// Javadoc - every row not REJECTED counts here, PROPOSED/ACCEPTED/APPLIED alike.
					if (!ReviewLine.REJECTED.equals(mapping.status))
					{
						mappingDecided++;
					}
				}
			}
		}
		pSnapshot.proposedCounts.put(ReviewLine.MAPPING, Integer.valueOf(mappingProposed));
		pSnapshot.decidedCounts.put(ReviewLine.MAPPING, Integer.valueOf(mappingDecided));

		// inline-reviews lot (2026-09-23): MAPPING_STEP/QUALITY/SCRIPT_CONSTRAINT are counted straight off
		// what attachProposalLines already grouped under each entity - no second table scan, and the count
		// matches exactly what step 4 goes on to render inline right below the header.
		int mappingStepProposed = 0;
		int qualityProposed = 0;
		int scriptConstraintProposed = 0;
		int mappingStepDecided = 0;
		int qualityDecided = 0;
		int scriptConstraintDecided = 0;
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			// staging-focus lot (2026-09-23): same reasoning as the mapping count above - a hidden entity's
			// proposal lines are not counted as "waiting for review" either.
			if (ReviewLine.SUPERSEDED.equals(entity.status))
			{
				continue;
			}
			for (final WizardSnapshot.ProposalLineInfo proposalLine : entity.proposalLines)
			{
				final boolean proposed = "PROPOSED".equals(proposalLine.line.status);
				final boolean decided = !ReviewLine.REJECTED.equals(proposalLine.line.status);
				if (!proposed && !decided)
				{
					continue;
				}
				if (ReviewLine.MAPPING_STEP.equals(proposalLine.line.nature))
				{
					if (proposed)
					{
						mappingStepProposed++;
					}
					if (decided)
					{
						mappingStepDecided++;
					}
				}
				else if (ReviewLine.QUALITY.equals(proposalLine.line.nature))
				{
					if (proposed)
					{
						qualityProposed++;
					}
					if (decided)
					{
						qualityDecided++;
					}
				}
				else if (ReviewLine.SCRIPT_CONSTRAINT.equals(proposalLine.line.nature))
				{
					if (proposed)
					{
						scriptConstraintProposed++;
					}
					if (decided)
					{
						scriptConstraintDecided++;
					}
				}
			}
		}
		pSnapshot.proposedCounts.put(ReviewLine.MAPPING_STEP, Integer.valueOf(mappingStepProposed));
		pSnapshot.proposedCounts.put(ReviewLine.QUALITY, Integer.valueOf(qualityProposed));
		pSnapshot.proposedCounts.put(ReviewLine.SCRIPT_CONSTRAINT, Integer.valueOf(scriptConstraintProposed));
		pSnapshot.decidedCounts.put(ReviewLine.MAPPING_STEP, Integer.valueOf(mappingStepDecided));
		pSnapshot.decidedCounts.put(ReviewLine.QUALITY, Integer.valueOf(qualityDecided));
		pSnapshot.decidedCounts.put(ReviewLine.SCRIPT_CONSTRAINT, Integer.valueOf(scriptConstraintDecided));

		int matchProposed = 0;
		for (final WizardSnapshot.MatchPlanInfo plan : pSnapshot.matchPlans)
		{
			if ("PROPOSED".equals(plan.status))
			{
				matchProposed++;
			}
		}
		pSnapshot.proposedCounts.put(ReviewLine.MATCH, Integer.valueOf(matchProposed));
	}

	private static WizardSnapshot.ProjectInfo projectInfo(final Adaptation pProject)
	{
		final WizardSnapshot.ProjectInfo info = new WizardSnapshot.ProjectInfo();
		info.id = pProject.getOccurrencePrimaryKey().format();
		info.code = WizardData.text(pProject, FactoryPaths.Project.CODE);
		info.label = WizardData.text(pProject, FactoryPaths.Project.LABEL);
		info.description = WizardData.text(pProject, FactoryPaths.Project.DESCRIPTION);
		final String mode = WizardData.text(pProject, FactoryPaths.Project.MODE);
		info.mode = mode.isEmpty() ? "GREENFIELD" : mode;
		final String style = WizardData.text(pProject, FactoryPaths.Project.STYLE);
		info.style = style.isEmpty() ? "CONSOLIDATION" : style;
		final String status = WizardData.text(pProject, FactoryPaths.Project.STATUS);
		info.status = status.isEmpty() ? "DRAFT" : status;
		info.landingDataspace = WizardData.text(pProject, FactoryPaths.Project.LANDING_DATASPACE);
		info.stagingDataspace = WizardData.text(pProject, FactoryPaths.Project.STAGING_DATASPACE);
		info.masterDataspace = WizardData.text(pProject, FactoryPaths.Project.MASTER_DATASPACE);
		info.masterDataset = WizardData.text(pProject, FactoryPaths.Project.MASTER_DATASET);
		// wizard-llm-value-sharing lot (2026-09-28): the project's own choice and sample size, as stored
		final String llmValueSharing = WizardData.text(pProject, FactoryPaths.Project.LLM_VALUE_SHARING);
		info.llmValueSharing = llmValueSharing.isEmpty() ? WizardRules.LLM_LEVEL_DEFAULT : llmValueSharing;
		final Object llmSampleSize = WizardData.val(pProject, FactoryPaths.Project.LLM_SAMPLE_SIZE);
		info.llmSampleSize = llmSampleSize instanceof Number && ((Number) llmSampleSize).intValue() > 0 ? ((Number) llmSampleSize).intValue()
			: WizardRules.LLM_SAMPLE_SIZE_DEFAULT;
		// lot MT (2026-10-04): the project's matching trigger, normalised (empty or unknown reads MANUAL)
		info.matchingTrigger = com.on.ebx.factory.master.MatchingTriggerMode.parse(WizardData.text(pProject, FactoryPaths.Project.MATCHING_TRIGGER)).name();
		info.domainDataspace = WizardData.text(pProject, FactoryPaths.Project.DOMAIN_DATASPACE);
		info.domainDataset = WizardData.text(pProject, FactoryPaths.Project.DOMAIN_DATASET);
		info.domainModel = WizardData.text(pProject, FactoryPaths.Project.DOMAIN_MODEL);
		info.owner = WizardData.text(pProject, FactoryPaths.Project.OWNER);
		return info;
	}

	private static void readSources(final Reads pReads, final WizardSnapshot pSnapshot)
	{
		final Adaptation pFactory = pReads.factory;
		final String pProjectId = pReads.projectId;
		final List<Adaptation> sourceRows = new ArrayList<Adaptation>();
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				sourceRows.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(sourceRows, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				return Integer.compare(Integer.parseInt(pLeft.getOccurrencePrimaryKey().format()), Integer.parseInt(pRight.getOccurrencePrimaryKey().format()));
			}
		});
		pReads.planLanding(sourceRows);
		for (final Adaptation row : sourceRows)
		{
			pSnapshot.sources.add(WizardData.sourceInfo(pReads, row));
		}
	}

	private static WizardSnapshot.SourceInfo sourceInfo(final Reads pReads, final Adaptation pRow)
	{
		final Adaptation pFactory = pReads.factory;
		final WizardSnapshot.SourceInfo info = new WizardSnapshot.SourceInfo();
		info.id = pRow.getOccurrencePrimaryKey().format();
		info.sourceSystem = WizardData.text(pRow, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		info.catalogSourceId = WizardData.text(pRow, FactoryPaths.ProjectSource.CATALOG_SOURCE);
		info.catalogSourceLabel = info.catalogSourceId;
		info.connector = WizardData.text(pRow, FactoryPaths.ProjectSource.CONNECTOR);
		// sources-landing lot SOURCES (2026-09-24): while reading the same row, a real catalogSourceLabel
		// instead of the id fallback above (a pre-existing gap, not this lot's own bug, fixed here since the
		// read is already happening) - Source.platform (the catalog's own coarse category) is NOT used for
		// the "kind" chip below any more, see the live fix right after.
		if (!info.catalogSourceId.isEmpty())
		{
			final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
			if (catalog != null)
			{
				final CatalogReader.SourceStatus catalogStatus = CatalogReader.sourceById(pReads.catalogReader, catalog, info.catalogSourceId);
				info.platform = catalogStatus.platform;
				if (!catalogStatus.label.isEmpty())
				{
					info.catalogSourceLabel = catalogStatus.label;
				}
			}
		}
		// sources-landing live fix (2026-09-24): the connector's OWN type is the "kind" chip's real signal
		// (Source.platform above is a coarse catalog category, never "sample-file" - see ConnectorFacts'
		// own Javadoc) - overrides info.platform when the connector row is found; falls back to the catalog
		// category above when it is not (a connector renamed/removed after the source was added). Also the
		// connector's own label, a second-priority title fallback below (used only when catalogSourceId is
		// itself blank - no catalog link at all).
		final WizardData.ConnectorFacts connectorFacts = WizardData.connectorFacts(pFactory.getHome().getRepository(), info.connector);
		if (!connectorFacts.type.isEmpty())
		{
			info.platform = connectorFacts.type;
		}
		info.connectorLabel = connectorFacts.label;
		info.landingDataset = WizardData.text(pRow, FactoryPaths.ProjectSource.LANDING_DATASET);
		// landing-walkthrough lot (2026-10-16): a model that has not loaded the new fields yet (a module XSD loads at the next start) reads as "not validated"
		if (pRow.getSchemaNode().getNode(FactoryPaths.ProjectSource.LANDING_VALIDATED) != null)
		{
			info.landingValidated = Boolean.TRUE.equals(WizardData.val(pRow, FactoryPaths.ProjectSource.LANDING_VALIDATED));
			info.landingValidatedBy = info.landingValidated ? WizardData.text(pRow, FactoryPaths.ProjectSource.LANDING_VALIDATED_BY) : "";
			final Object validatedAt = info.landingValidated ? WizardData.val(pRow, FactoryPaths.ProjectSource.LANDING_VALIDATED_AT) : null;
			info.landingValidatedAt = validatedAt instanceof java.util.Date ? new java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss").format((java.util.Date) validatedAt) : "";
		}
		final Object trustRank = WizardData.val(pRow, FactoryPaths.ProjectSource.TRUST_RANK);
		info.trustRank = trustRank instanceof Number ? ((Number) trustRank).intValue() : 100;
		final List<?> schemas = WizardData.lst(pRow, FactoryPaths.ProjectSource.SCHEMA_FILTER);
		if (schemas != null)
		{
			for (final Object schema : schemas)
			{
				info.schemas.add(String.valueOf(schema));
			}
		}
		final String status = WizardData.text(pRow, FactoryPaths.ProjectSource.STATUS);
		info.status = status.isEmpty() ? "SELECTED" : status;
		info.message = WizardData.text(pRow, FactoryPaths.ProjectSource.MESSAGE);

		final java.util.Map<String, String> tableNames = new java.util.HashMap<String, String>();
		final List<Adaptation> tableRows = new ArrayList<Adaptation>(pReads.landingTablesOf(info.id));
		Collections.sort(tableRows, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				return Integer.compare(Integer.parseInt(pLeft.getOccurrencePrimaryKey().format()), Integer.parseInt(pRight.getOccurrencePrimaryKey().format()));
			}
		});
		info.planned = !tableRows.isEmpty();
		for (final Adaptation row : tableRows)
		{
			final WizardSnapshot.TableInfo table = WizardData.tableInfo(pReads, row);
			tableNames.put(table.id, table.tableName);
			info.tables.add(table);
		}
		WizardData.readRelations(pReads, info.id, tableNames, info);
		WizardData.readPipelineSteps(pReads, WizardData.projectIdOfSource(pRow), info);
		return info;
	}

	private static String projectIdOfSource(final Adaptation pProjectSourceRow)
	{
		return WizardData.text(pProjectSourceRow, FactoryPaths.ProjectSource.PROJECT);
	}

	private static WizardSnapshot.TableInfo tableInfo(final Reads pReads, final Adaptation pRow)
	{
		final Adaptation pFactory = pReads.factory;
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.id = pRow.getOccurrencePrimaryKey().format();
		table.sourceObject = WizardData.text(pRow, FactoryPaths.LandingTable.SOURCE_OBJECT);
		table.tableName = WizardData.text(pRow, FactoryPaths.LandingTable.TABLE_NAME);
		table.selected = !Boolean.FALSE.equals(WizardData.val(pRow, FactoryPaths.LandingTable.SELECTED));
		final Object columnCount = WizardData.val(pRow, FactoryPaths.LandingTable.COLUMN_COUNT);
		table.columnCount = columnCount instanceof Number ? ((Number) columnCount).intValue() : 0;
		final Object rowCount = WizardData.val(pRow, FactoryPaths.LandingTable.SOURCE_ROW_COUNT);
		table.sourceRowCount = rowCount instanceof Number ? ((Number) rowCount).longValue() : 0L;
		// sources-landing live fix (2026-09-24): LandingTable.sourceRowCount is a nullable decimal - the
		// catalog rarely profiles a sample-file source's row count, and the coercion above already turns that
		// null into the primitive 0, indistinguishable from a real profiled zero. Recorded separately so
		// WizardPage can fall back to lastLoadRows (below) instead of rendering a misleading "0 rows" - see
		// wizard.TableRowCount.
		table.sourceRowCountKnown = rowCount instanceof Number;
		// sources-landing lot SOURCES (2026-09-24): the source's OWN declared primary key, read straight off
		// the catalog asset this landing table was planned from - a plain display concern, never touches
		// LandingColumn/the key rules (lot KEY's own territory).
		// audit D-07: catalogAsset holds the asset KEY (a string); a table planned before holds the digits of an id that no
		// longer exists - resolveAssetKey then finds the asset again from the project source and the source object.
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		final String catalogAssetKey = catalog == null ? null : CatalogReader.resolveAssetKey(pReads.catalogReader, catalog, pFactory, pRow);
		if (catalogAssetKey != null)
		{
			table.declaredKeyColumns.addAll(CatalogReader.primaryKeyColumns(pReads.catalogReader, catalog, catalogAssetKey));
			// deduced-scan lot (2026-09-24): scanOrigin/scanState/scanConfidence/scanRationale + readRecipe,
			// both guarded against an older catalog that does not have these fields yet (CatalogReader's own
			// schema-node check) - see WizardRules.hasUnconfirmedDeducedTable for what reads
			// scanState back out of the snapshot.
			final CatalogReader.ScanInfo scanInfo = CatalogReader.scanInfo(pReads.catalogReader, catalog, catalogAssetKey);
			table.scanOrigin = scanInfo.scanOrigin;
			table.scanState = scanInfo.scanState;
			table.scanConfidence = scanInfo.scanConfidence;
			table.scanRationale = scanInfo.scanRationale;
			table.scanConfirmedBy = scanInfo.confirmedBy;
			table.scanConfirmedAt = scanInfo.confirmedAt;
			table.readRecipe = com.on.ebx.factory.deducedscan.ReadRecipe.parse(CatalogReader.readRecipeJson(pReads.catalogReader, catalog, catalogAssetKey));
			// live fix (2026-09-24, project Vague1): a catalog rescan updates Asset.rowCount, but
			// LandingTable.sourceRowCount above was only ever copied ONCE, at planning time - step 2 kept
			// showing the pre-rescan count forever after. Read it live instead, falling back to the
			// planned snapshot only when the asset itself is unreadable (catalog/asset row gone) or was
			// never profiled with a row count either way.
			final java.math.BigDecimal liveRowCount = CatalogReader.rowCount(pReads.catalogReader, catalog, catalogAssetKey);
			if (liveRowCount != null)
			{
				table.sourceRowCount = liveRowCount.longValue();
				table.sourceRowCountKnown = true;
			}
		}
		final String status = WizardData.text(pRow, FactoryPaths.LandingTable.STATUS);
		table.status = status.isEmpty() ? "PROPOSED" : status;
		table.lastLoadId = WizardData.text(pRow, FactoryPaths.LandingTable.LAST_LOAD_ID);
		final Object lastLoadRows = WizardData.val(pRow, FactoryPaths.LandingTable.LAST_LOAD_ROWS);
		table.lastLoadRows = lastLoadRows instanceof Number ? ((Number) lastLoadRows).longValue() : 0L;
		table.message = WizardData.text(pRow, FactoryPaths.LandingTable.MESSAGE);

		final List<Adaptation> columnRows = new ArrayList<Adaptation>(pReads.landingColumnsOf(table.id));
		Collections.sort(columnRows, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Integer left = (Integer) WizardData.val(pLeft, FactoryPaths.LandingColumn.POSITION);
				final Integer right = (Integer) WizardData.val(pRight, FactoryPaths.LandingColumn.POSITION);
				final int l = left == null ? Integer.MAX_VALUE : left.intValue();
				final int r = right == null ? Integer.MAX_VALUE : right.intValue();
				return l != r ? Integer.compare(l, r) : 0;
			}
		});
		final StringBuilder keyColumns = new StringBuilder();
		String keyOrigin = "";
		for (final Adaptation column : columnRows)
		{
			table.columnNames.add(WizardData.text(column, FactoryPaths.LandingColumn.SOURCE_COLUMN));
			table.columnTypes.put(WizardData.text(column, FactoryPaths.LandingColumn.SOURCE_COLUMN), WizardData.text(column, FactoryPaths.LandingColumn.INFERRED_TYPE));
			if (Boolean.TRUE.equals(WizardData.val(column, FactoryPaths.LandingColumn.SOURCE_KEY)))
			{
				if (keyColumns.length() > 0)
				{
					keyColumns.append(",");
				}
				keyColumns.append(WizardData.text(column, FactoryPaths.LandingColumn.SOURCE_COLUMN));
				keyOrigin = WizardData.strongerOrigin(keyOrigin, WizardData.text(column, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN));
			}
		}
		table.keyColumns = keyColumns.toString();
		table.keyOrigin = table.keyColumns.isEmpty() ? "" : keyOrigin;
		// landing key-study lot (2026-09-23): confidence and rationale are stored fields now
		// (LandingTable.keyConfidence/keyRationale, written by LandingPlanner, KeyStudy.apply and setTableKey).
		// A table planned before the lot has neither: fall back to the fixed 70 the rule used to be shown with,
		// and to the "Source key: ..." text LandingPlanner has always folded into message.
		final Object keyConfidence = WizardData.val(pRow, FactoryPaths.LandingTable.KEY_CONFIDENCE);
		table.keyConfidence = table.keyColumns.isEmpty() ? 0 : keyConfidence instanceof Number ? ((Number) keyConfidence).intValue() : 70;
		table.pendingKeyConfidence = table.keyColumns.isEmpty() && keyConfidence instanceof Number ? ((Number) keyConfidence).intValue() : 0;
		// volume wave 2, lot V2-E: a model that has not loaded the new fields yet (a module XSD loads at the next start) reads as "none"
		if (pRow.getSchemaNode().getNode(FactoryPaths.LandingTable.CHANGE_COLUMN) != null)
		{
			table.changeColumn = WizardData.text(pRow, FactoryPaths.LandingTable.CHANGE_COLUMN);
			table.changeColumnKind = WizardData.text(pRow, FactoryPaths.LandingTable.CHANGE_COLUMN_KIND);
		}
		table.keyRationale = WizardData.text(pRow, FactoryPaths.LandingTable.KEY_RATIONALE);
		if (table.keyRationale.isEmpty() && table.message.startsWith("Source key: "))
		{
			table.keyRationale = table.message.substring("Source key: ".length());
		}
		return table;
	}

	/**
	 * {@code USER} wins over {@code DEDUCED} over {@code SOURCE} over {@code LLM} over {@code RULE} over empty -
	 * the origin shown for a composite key whose columns were set by different hands.
	 * <p>
	 * <b>Bug fixed live 2026-09-24 (project Vague1, SFDC tables):</b> {@link #originRank} did not know about
	 * {@code SOURCE} (added by the composite-key lot, same day) and fell through to its {@code 0} default - the
	 * same rank the initial, never-yet-set {@code keyOrigin} accumulator starts from in {@link #tableRow}. Since
	 * this method only overrides {@code pCurrent} on a STRICTLY greater rank ({@code candidate > current}), a tie
	 * at {@code 0} never updated it: every {@code SOURCE}-origin column left the aggregated {@code keyOrigin} at
	 * {@code ""}, even though {@code keyColumns} itself was correctly populated from the same columns.
	 * {@code LandingRun.runKeyStage}'s short-circuit for an already-certain key
	 * ({@code "SOURCE".equals(pTable.keyOrigin)}) therefore never matched, and the table fell through to the
	 * full {@code KeyStudy} sample-based path - showing "Needs your eye / No sample evidence yet" for a key the
	 * source itself declares authoritative, which {@code KeyCertainty.certain} would otherwise have accepted
	 * immediately with no sample at all.
	 * <p>
	 * <b>Bug fixed live 2026-09-27 (project DeducedTest3, exact same defect class, reintroduced for a THIRD
	 * origin):</b> when {@code LandingColumn.sourceKeyOrigin} gained {@code DEDUCED} (round 5 fix,
	 * {@code KeyCertainty}'s own Javadoc), this method was not updated to match - {@link #originRank} still
	 * fell through to its {@code 0} default for {@code DEDUCED}, so a table whose key columns all carry
	 * {@code DEDUCED} aggregated to {@code keyOrigin = ""} instead of {@code "DEDUCED"} (same tie-at-zero
	 * mechanism as the 2026-09-24 incident above - {@code keyColumns} itself stayed correctly populated, only
	 * the aggregated origin was lost). {@code LandingRun.runKeyStage} then found an origin
	 * {@code KeyCertainty.alreadySettled} does not recognise, called the language model again on a key a person
	 * had already confirmed via the deduced-scan board, and the model came back with a low-confidence guess
	 * built from a data-policy-MASKED sample (see {@code LlmDataPolicy}, {@code KeyLlmPass}) - "Source key: Not
	 * applied, waiting for a person - Confidence too low". Fixed by adding {@code DEDUCED} to
	 * {@link #originRank}, ranked just under {@code USER}: both represent a person's own confirmation
	 * (board 2e for {@code DEDUCED}, the key picker for {@code USER}), so either should out-rank the
	 * source-declared/model/rule tiers, and {@code USER} (a later, explicit override in the picker) keeps
	 * priority over a standing {@code DEDUCED} confirmation on the same composite key.
	 */
	static String strongerOrigin(final String pCurrent, final String pCandidate)
	{
		final int current = WizardData.originRank(pCurrent);
		final int candidate = WizardData.originRank(pCandidate);
		return candidate > current ? pCandidate : pCurrent;
	}

	private static int originRank(final String pOrigin)
	{
		return "USER".equals(pOrigin) ? 5 : "DEDUCED".equals(pOrigin) ? 4 : "SOURCE".equals(pOrigin) ? 3 : "LLM".equals(pOrigin) ? 2
			: "RULE".equals(pOrigin) ? 1 : 0;
	}

	private static void readRelations(final Reads pReads, final String pProjectSourceId, final java.util.Map<String, String> pTableNames,
		final WizardSnapshot.SourceInfo pSource)
	{
		for (final Adaptation row : pReads.relationsOf(pProjectSourceId))
		{
			final WizardSnapshot.RelationInfo relation = new WizardSnapshot.RelationInfo();
			relation.id = row.getOccurrencePrimaryKey().format();
			final String fromId = WizardData.text(row, FactoryPaths.LandingRelation.FROM_TABLE);
			final String toId = WizardData.text(row, FactoryPaths.LandingRelation.TO_TABLE);
			relation.fromTable = pTableNames.containsKey(fromId) ? pTableNames.get(fromId) : fromId;
			relation.toTable = pTableNames.containsKey(toId) ? pTableNames.get(toId) : toId;
			relation.selfReference = Boolean.TRUE.equals(WizardData.val(row, FactoryPaths.LandingRelation.SELF_REFERENCE));
			final String origin = WizardData.text(row, FactoryPaths.LandingRelation.ORIGIN);
			relation.origin = origin.isEmpty() ? "RULE" : origin;
			final Object confidence = WizardData.val(row, FactoryPaths.LandingRelation.CONFIDENCE);
			relation.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
			relation.rationale = WizardData.text(row, FactoryPaths.LandingRelation.RATIONALE);
			final String status = WizardData.text(row, FactoryPaths.LandingRelation.STATUS);
			relation.status = status.isEmpty() ? "PROPOSED" : status;
			pSource.relations.add(relation);
		}
	}

	private static void readPipelineSteps(final Reads pReads, final String pProjectId, final WizardSnapshot.SourceInfo pSource)
	{
		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			return;
		}
		final String[] steps = { "LANDING_MODEL", "LANDING_DATASET", "LANDING_FLOWS", "LANDING_LOAD" };
		for (final String step : steps)
		{
			// the source's own code, matched as ./scope=<code> was: an empty code selects nothing
			final Adaptation latest = pReads.steps().scopedExact(step, pSource.sourceSystem);
			if (latest != null)
			{
				pSource.pipelineSteps.add(new WizardSnapshot.PipelineStepInfo(step, WizardData.text(latest, FactoryPaths.PipelineStep.STATUS),
					WizardData.text(latest, FactoryPaths.PipelineStep.MESSAGE), WizardData.startedAtMillis(latest)));
			}
		}
	}

	private static void readStagingEntities(final Reads pReads, final WizardSnapshot pSnapshot)
	{
		final Adaptation pFactory = pReads.factory;
		final String pProjectId = pReads.projectId;
		final java.util.Map<String, String> entityNames = new java.util.HashMap<String, String>();
		final List<Adaptation> entityRows = new ArrayList<Adaptation>();
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				entityRows.add(row);
				entityNames.put(row.getOccurrencePrimaryKey().format(), WizardData.text(row, FactoryPaths.StagingEntity.NAME));
			}
		}
		finally
		{
			rows.close();
		}
		pReads.planEntities(entityNames.keySet());
		// member entity id -> canonical entity name, so a SUPERSEDED rule entity can say which canonical absorbed it
		final java.util.Map<String, String> canonicalNameByMemberId = new java.util.HashMap<String, String>();
		final List<WizardSnapshot.StagingEntityInfo> built = new ArrayList<WizardSnapshot.StagingEntityInfo>();
		for (final Adaptation entityRow : entityRows)
		{
			final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
			entity.id = entityRow.getOccurrencePrimaryKey().format();
			entity.name = WizardData.text(entityRow, FactoryPaths.StagingEntity.NAME);
			entity.label = WizardData.text(entityRow, FactoryPaths.StagingEntity.LABEL);
			entity.targetTable = WizardData.text(entityRow, FactoryPaths.StagingEntity.TARGET_TABLE);
			final String origin = WizardData.text(entityRow, FactoryPaths.StagingEntity.ORIGIN);
			entity.origin = origin.isEmpty() ? "RULE" : origin;
			final Object confidence = WizardData.val(entityRow, FactoryPaths.StagingEntity.CONFIDENCE);
			entity.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
			entity.rationale = WizardData.text(entityRow, FactoryPaths.StagingEntity.RATIONALE);
			final String status = WizardData.text(entityRow, FactoryPaths.StagingEntity.STATUS);
			entity.status = status.isEmpty() ? "PROPOSED" : status;
			entity.memberEntitiesText = WizardData.memberEntitiesText(pReads, entityRow);
			final List<?> rawMemberIds = WizardData.lst(entityRow, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
			if (rawMemberIds != null)
			{
				for (final Object rawMemberId : rawMemberIds)
				{
					canonicalNameByMemberId.put(String.valueOf(rawMemberId), entity.name);
				}
			}

			final java.util.Set<String> parentLinkIds = ParentLinkReader.attributeIdsOf(pReads.attributesOf(entity.id), pReads.feedsOf(entity.id));
			for (final Adaptation attributeRow : pReads.attributesOf(entity.id))
			{
				final WizardSnapshot.AttributeInfo attribute = WizardData.attributeInfo(pReads, attributeRow, entityNames);
				attribute.parentLink = parentLinkIds.contains(attribute.id);
				entity.attributes.add(attribute);
			}
			pSnapshot.stagingEntities.add(entity);
			built.add(entity);
		}
		for (final WizardSnapshot.StagingEntityInfo entity : built)
		{
			final String canonical = canonicalNameByMemberId.get(entity.id);
			entity.supersededBy = canonical == null ? "" : canonical;
		}
	}

	/**
	 * {@code StagingEntity.memberEntities} formatted through {@link ReviewEntityFormat#unifiesText}, exactly
	 * as {@code ProposalReviews.addEntityLine} builds it for the review screen (same structured field, same
	 * formatter - reused rather than re-derived): null/empty when this is not a canonical entity or none of
	 * its members could be resolved.
	 */
	private static String memberEntitiesText(final Reads pReads, final Adaptation pEntityRow)
	{
		final Adaptation pFactory = pReads.factory;
		final List<?> rawMemberIds = WizardData.lst(pEntityRow, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
		if (rawMemberIds == null || rawMemberIds.isEmpty())
		{
			return "";
		}
		final List<ReviewEntityFormat.Member> members = new ArrayList<ReviewEntityFormat.Member>();
		for (final Object rawMemberId : rawMemberIds)
		{
			final String memberId = String.valueOf(rawMemberId);
			final Adaptation memberRow = WizardData.lookup(pFactory, FactoryPaths.STAGING_ENTITY, memberId);
			final String memberName = memberRow == null ? memberId : WizardData.str(memberRow, FactoryPaths.StagingEntity.NAME);
			members.add(new ReviewEntityFormat.Member(memberName, WizardData.resolveSourceSystemOfEntity(pReads, memberId)));
		}
		final String text = ReviewEntityFormat.unifiesText(members);
		return text == null ? "" : text;
	}

	/** Duplicated from {@code ProposalReviews} (private there): a rule-proposed entity's source system, through the first business attribute that carries a real {@code AttributeMapping} back to a landing column/table/project source. */
	private static String resolveSourceSystemOfEntity(final Reads pReads, final String pEntityId)
	{
		final Adaptation pFactory = pReads.factory;
		// audit F-32: an entity of this snapshot answers from the attribute/mapping rows already read (one scan each for the whole snapshot);
		// any other entity id keeps the original per-attribute query below
		if (pReads.knowsEntity(pEntityId))
		{
			for (final Adaptation attributeRow : pReads.attributesOf(pEntityId))
			{
				final List<Adaptation> mappingRows = pReads.mappingsOfAttribute(attributeRow.getOccurrencePrimaryKey().format());
				if (mappingRows.isEmpty())
				{
					continue;
				}
				final Adaptation mappingRow = mappingRows.get(0);
				final Adaptation landingColumn = WizardData.lookup(pFactory, FactoryPaths.LANDING_COLUMN, WizardData.str(mappingRow, FactoryPaths.AttributeMapping.LANDING_COLUMN));
				final Adaptation landingTable = landingColumn == null ? null
					: WizardData.lookup(pFactory, FactoryPaths.LANDING_TABLE, WizardData.str(landingColumn, FactoryPaths.LandingColumn.LANDING_TABLE));
				final Adaptation projectSource = landingTable == null ? null
					: WizardData.lookup(pFactory, FactoryPaths.PROJECT_SOURCE, WizardData.str(landingTable, FactoryPaths.LandingTable.PROJECT_SOURCE));
				if (projectSource != null)
				{
					return WizardData.str(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				}
			}
			return null;
		}
		final RequestResult attributeRows = WizardData.rows(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				final RequestResult mappingRows = WizardData.rows(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./attribute=" + CatalogReader.quote(attributeRow.getOccurrencePrimaryKey().format()));
				try
				{
					final Adaptation mappingRow = mappingRows.nextAdaptation();
					if (mappingRow == null)
					{
						continue;
					}
					final Adaptation landingColumn = WizardData.lookup(pFactory, FactoryPaths.LANDING_COLUMN, WizardData.str(mappingRow, FactoryPaths.AttributeMapping.LANDING_COLUMN));
					final Adaptation landingTable = landingColumn == null ? null
						: WizardData.lookup(pFactory, FactoryPaths.LANDING_TABLE, WizardData.str(landingColumn, FactoryPaths.LandingColumn.LANDING_TABLE));
					final Adaptation projectSource = landingTable == null ? null
						: WizardData.lookup(pFactory, FactoryPaths.PROJECT_SOURCE, WizardData.str(landingTable, FactoryPaths.LandingTable.PROJECT_SOURCE));
					if (projectSource != null)
					{
						return WizardData.str(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
					}
				}
				finally
				{
					mappingRows.close();
				}
			}
		}
		finally
		{
			attributeRows.close();
		}
		return null;
	}

	private static Adaptation lookup(final Adaptation pFactory, final com.orchestranetworks.schema.Path pTablePath, final String pId)
	{
		if (pId == null || pId.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return WizardData.byKey(pFactory.getTable(pTablePath), PrimaryKey.parseString(pId));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static WizardSnapshot.AttributeInfo attributeInfo(final Reads pReads, final Adaptation pRow, final java.util.Map<String, String> pEntityNames)
	{
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = pRow.getOccurrencePrimaryKey().format();
		attribute.name = WizardData.text(pRow, FactoryPaths.StagingAttribute.NAME);
		attribute.label = WizardData.text(pRow, FactoryPaths.StagingAttribute.LABEL);
		final String dataType = WizardData.text(pRow, FactoryPaths.StagingAttribute.DATA_TYPE);
		attribute.dataType = dataType.isEmpty() ? "STRING" : dataType;
		final Object maxLength = WizardData.val(pRow, FactoryPaths.StagingAttribute.MAX_LENGTH);
		attribute.maxLength = maxLength instanceof Number ? Integer.valueOf(((Number) maxLength).intValue()) : null;
		attribute.mandatory = Boolean.TRUE.equals(WizardData.val(pRow, FactoryPaths.StagingAttribute.MANDATORY));
		attribute.businessKey = Boolean.TRUE.equals(WizardData.val(pRow, FactoryPaths.StagingAttribute.BUSINESS_KEY));
		attribute.primaryKey = Boolean.TRUE.equals(WizardData.val(pRow, FactoryPaths.StagingAttribute.PRIMARY_KEY));
		final String referenceEntityId = WizardData.text(pRow, FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
		attribute.referenceEntityName = referenceEntityId.isEmpty() ? "" : pEntityNames.containsKey(referenceEntityId) ? pEntityNames.get(referenceEntityId) : referenceEntityId;
		final String origin = WizardData.text(pRow, FactoryPaths.StagingAttribute.ORIGIN);
		attribute.origin = origin.isEmpty() ? "RULE" : origin;
		final Object confidence = WizardData.val(pRow, FactoryPaths.StagingAttribute.CONFIDENCE);
		attribute.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
		attribute.rationale = WizardData.text(pRow, FactoryPaths.StagingAttribute.RATIONALE);
		final String status = WizardData.text(pRow, FactoryPaths.StagingAttribute.STATUS);
		attribute.status = status.isEmpty() ? "PROPOSED" : status;
		final boolean derived = !WizardData.text(pRow, FactoryPaths.StagingAttribute.DERIVATION).isEmpty();
		final List<String> mappingStatuses = new ArrayList<String>();
		for (final Adaptation mappingRow : pReads.mappingsOfAttribute(attribute.id))
		{
			final WizardSnapshot.MappingInfo mapping = new WizardSnapshot.MappingInfo();
			mapping.id = mappingRow.getOccurrencePrimaryKey().format();
			final String mappingOrigin = WizardData.text(mappingRow, FactoryPaths.AttributeMapping.ORIGIN);
			mapping.origin = mappingOrigin.isEmpty() ? "RULE" : mappingOrigin;
			final Object mappingConfidence = WizardData.val(mappingRow, FactoryPaths.AttributeMapping.CONFIDENCE);
			mapping.confidence = mappingConfidence instanceof Number ? Integer.valueOf(((Number) mappingConfidence).intValue()) : null;
			final String mappingStatus = WizardData.text(mappingRow, FactoryPaths.AttributeMapping.STATUS);
			mapping.status = mappingStatus.isEmpty() ? "PROPOSED" : mappingStatus;
			attribute.mappings.add(mapping);
			mappingStatuses.add(mapping.status);
		}
		attribute.mappingCount = mappingStatuses.size();
		int feeding = 0;
		for (final String mappingStatus : mappingStatuses)
		{
			if (!ReviewLine.REJECTED.equals(mappingStatus))
			{
				feeding++;
			}
		}
		attribute.mappingFeedingCount = feeding;
		final String warning = ReviewRules.attributeMappingWarning(attribute.status, attribute.primaryKey, derived, !referenceEntityId.isEmpty(), mappingStatuses);
		attribute.mappingWarning = warning == null ? "" : warning;
		return attribute;
	}

	// ------------------------------------------------------------------------------------------------- reference sets

	public static Set<String> existingProjectCodes(final Adaptation pFactory)
	{
		final Set<String> out = new HashSet<String>();
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.PROJECT), null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(WizardData.str(row, FactoryPaths.Project.CODE));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	public static Set<String> existingSourceSystemCodes(final Adaptation pFactory, final String pProjectId)
	{
		final Set<String> out = new HashSet<String>();
		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			return out;
		}
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(WizardData.str(row, FactoryPaths.ProjectSource.SOURCE_SYSTEM));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Every {@code trustRank} already stored by a source of this project - {@link WizardRules#nextTrustRank}'s
	 * own input, so a NEW source proposed in the step 2 "add an application" form ({@code
	 * ProjectWizardUserService#pickCatalogSource}) gets a distinct rank placing it after every one already
	 * there, instead of the shared default 100 (volume-test bug, project PersVol, 2026-09-24).
	 */
	public static List<Integer> existingTrustRanks(final Adaptation pFactory, final String pProjectId)
	{
		final List<Integer> out = new ArrayList<Integer>();
		if (pProjectId == null || pProjectId.trim().isEmpty())
		{
			return out;
		}
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Object rank = WizardData.val(row, FactoryPaths.ProjectSource.TRUST_RANK);
				out.add(Integer.valueOf(rank instanceof Number ? ((Number) rank).intValue() : 100));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Schemas scanned for a catalog source, for the step 2 "which schemas belong to this application" checklist. Audit F-02
	 * (catalog reads): read as {@code pReader} - the person in the wizard, the job's system session in an onboarding job.
	 */
	public static List<String> schemasOf(final CatalogReader.Reader pReader, final Adaptation pFactory, final String pCatalogSourceId)
	{
		final Adaptation catalog = CatalogReader.dataset(pFactory.getHome().getRepository());
		if (catalog == null || pCatalogSourceId == null || pCatalogSourceId.trim().isEmpty())
		{
			return new ArrayList<String>();
		}
		return CatalogReader.distinctSchemas(pReader, catalog, pCatalogSourceId.trim());
	}

	/** The connector of a catalog source, to pre-fill step 2's connector field - read as data, like {@code CatalogReader} reads everything else. */
	public static String connectorOfCatalogSource(final WizardSnapshot pSnapshot, final String pCatalogSourceId)
	{
		for (final WizardSnapshot.CatalogSourceInfo source : pSnapshot.catalogSources)
		{
			if (source.id.equals(pCatalogSourceId))
			{
				return source.connector;
			}
		}
		return "";
	}

	// -------------------------------------------------------------------------------------------------- writing

	/**
	 * Creates the project when {@code pExistingProjectId} is null, otherwise updates its label/description
	 * (code and mode never change after creation) - and, wizard-llm-value-sharing lot (2026-09-28), its two
	 * language-model fields ({@code llmValueSharing}, {@code llmSampleSize}) in both cases: an empty posted
	 * level/size means "nothing posted" and lands on the defaults, never on null (the level is a mandatory
	 * field). The caller has already refused a level above the instance cap ({@code
	 * WizardRules.projectProblems}), so what is written is what will apply.
	 *
	 * @return the project's id
	 */
	public static String saveProject(final Session pSession, final Adaptation pFactory, final String pExistingProjectId, final WizardRules.ProjectDraft pDraft)
		throws OperationException
	{
		final boolean creating = pExistingProjectId == null || pExistingProjectId.trim().isEmpty();
		final String llmValueSharing = pDraft.llmValueSharing == null || pDraft.llmValueSharing.trim().isEmpty() ? WizardRules.LLM_LEVEL_DEFAULT
			: pDraft.llmValueSharing.trim();
		// an unparseable size only reaches here with a level that never samples (projectProblems checks it for
		// SAMPLE_VALUES): the default is stored, never a clamped guess
		final int parsedSampleSize = WizardRules.llmSampleSize(pDraft.llmSampleSize);
		final int llmSampleSize = parsedSampleSize < 0 ? WizardRules.LLM_SAMPLE_SIZE_DEFAULT : parsedSampleSize;
		if (creating)
		{
			// delivery lot 2 (docs/DELIVERY.md sec 6.2): checked before the procedure even starts, never only by
			// disabling the wizard's button - a forged request must be refused too, the same defence
			// ProjectWizardUserService's own class Javadoc already applies to the agent-account actions
			ProjectOrigin.refuseIfTarget(pFactory);
		}
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final String[] holder = new String[1];
		final ProcedureResult result;
		// 1.4.2 (F3-13): the access rule on Project/style is closed at creation; the wizard is the one writer, for an open style only (ProjectStyleAccessRule.Ticket)
		try (ProjectStyleAccessRule.Ticket ticket = creating ? ProjectStyleAccessRule.open(WizardRules.styleSelectable(pDraft.style) ? pDraft.style : "CONSOLIDATION") : null)
		{
			result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					if (pExistingProjectId == null || pExistingProjectId.trim().isEmpty())
					{
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(projects);
						value.setValue(pDraft.code, FactoryPaths.Project.CODE);
						value.setValue(pDraft.label, FactoryPaths.Project.LABEL);
						value.setValue(WizardData.orNull(pDraft.description), FactoryPaths.Project.DESCRIPTION);
						value.setValue("GREENFIELD", FactoryPaths.Project.MODE);
						// lot C1-B: the chosen style, but only a selectable one (CONSOLIDATION; CENTRALISATION is "coming soon" since 2026-11-10): anything else lands on the safe value,
						// the same defence in depth as MODE above (WizardRules.projectProblems already refuses it server-side)
						value.setValue(WizardRules.styleSelectable(pDraft.style) ? pDraft.style : "CONSOLIDATION", FactoryPaths.Project.STYLE);
						value.setValue("DRAFT", FactoryPaths.Project.STATUS);
						value.setValue(llmValueSharing, FactoryPaths.Project.LLM_VALUE_SHARING);
						value.setValue(Integer.valueOf(llmSampleSize), FactoryPaths.Project.LLM_SAMPLE_SIZE);
						value.setValue(new Date(), FactoryPaths.Project.CREATED_AT);
						// delivery lot 1: the project remembers the instance it was designed on (delivery.OriginRule)
						value.setValue(ProjectOrigin.instanceId(pFactory), FactoryPaths.Project.ORIGIN_REPOSITORY);
						holder[0] = pContext.doCreateOccurrence(value, projects).getOccurrencePrimaryKey().format();
					}
					else
					{
						final Adaptation existing = WizardData.byKey(projects, PrimaryKey.parseString(pExistingProjectId));
						if (existing == null)
						{
							throw EngineRefusal.projectNotFound(pExistingProjectId);
						}
						final ValueContextForUpdate value = pContext.getContext(existing.getAdaptationName());
						value.setValue(pDraft.label, FactoryPaths.Project.LABEL);
						value.setValue(WizardData.orNull(pDraft.description), FactoryPaths.Project.DESCRIPTION);
						value.setValue(llmValueSharing, FactoryPaths.Project.LLM_VALUE_SHARING);
						value.setValue(Integer.valueOf(llmSampleSize), FactoryPaths.Project.LLM_SAMPLE_SIZE);
						pContext.doModifyContent(existing, value);
						holder[0] = pExistingProjectId;
					}
				}
			});
		}
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
		return holder[0];
	}

	/**
	 * Lot MT (2026-10-04): writes {@code Project.matchingTrigger} ({@code MANUAL} or {@code REALTIME}, already checked by the caller with
	 * {@code MatchingTriggerMode.isAccepted}; an empty value lands on {@code MANUAL}). Nothing else of the project changes. The new mode
	 * reaches the add-on's matching policies at the next "Configure matching" ({@code MatchingConfigurator.ensureExecutionMode}).
	 */
	public static void saveMatchingTrigger(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pMode) throws OperationException
	{
		final String mode = com.on.ebx.factory.master.MatchingTriggerMode.parse(pMode).name();
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		final Adaptation existing = pProjectId == null || pProjectId.trim().isEmpty() ? null : WizardData.byKey(projects, PrimaryKey.parseString(pProjectId));
		if (existing == null)
		{
			throw EngineRefusal.projectNotFound(pProjectId);
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(existing.getAdaptationName());
				value.setValue(mode, FactoryPaths.Project.MATCHING_TRIGGER);
				pContext.doModifyContent(existing, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/** @return the new source's id */
	public static String addSource(final Session pSession, final Adaptation pFactory, final String pProjectId, final WizardRules.SourceDraft pDraft) throws OperationException
	{
		return WizardData.addSource(pSession, pFactory, pProjectId, pDraft, pSession == null ? null : pSession.getLocale());
	}

	/**
	 * Same as {@link #addSource(Session, Adaptation, String, WizardRules.SourceDraft)}, with the failure
	 * message's locale given explicitly rather than read from {@code pSession} - job-session-unwrapped fix
	 * (2026-09-25): {@code ebx.SourcesRun}'s background job runs with the SYSTEM session (never wrapped to
	 * fake its locale any more - see that class's own Javadoc on why a hand-made {@code Session} wrapper broke
	 * EBX internals that cast to the kernel's concrete session type), so the requester's locale - captured at
	 * enqueue time, carried in the job's params - has to be passed in here instead of read off {@code
	 * pSession.getLocale()}. {@code pLocale} null falls back to {@link Locale#ENGLISH}, same as the no-locale
	 * overload.
	 */
	public static String addSource(final Session pSession, final Adaptation pFactory, final String pProjectId, final WizardRules.SourceDraft pDraft, final Locale pLocale)
		throws OperationException
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final String[] holder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(sources);
				value.setValue(pProjectId, FactoryPaths.ProjectSource.PROJECT);
				value.setValue(pDraft.sourceSystem, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				value.setValue(pDraft.catalogSourceId, FactoryPaths.ProjectSource.CATALOG_SOURCE);
				value.setValue(WizardData.orNull(pDraft.connector), FactoryPaths.ProjectSource.CONNECTOR);
				value.setValue(new ArrayList<String>(pDraft.schemas), FactoryPaths.ProjectSource.SCHEMA_FILTER);
				value.setValue(Integer.valueOf(pDraft.trustRank), FactoryPaths.ProjectSource.TRUST_RANK);
				value.setValue("SELECTED", FactoryPaths.ProjectSource.STATUS);
				holder[0] = pContext.doCreateOccurrence(value, sources).getOccurrencePrimaryKey().format();
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pLocale == null ? Locale.ENGLISH : pLocale);
		}
		return holder[0];
	}

	/**
	 * trust-rank-edit lot (2026-09-23): rewrites {@code ProjectSource.trustRank} of one source - allowed at any
	 * status (the rank only feeds the merge policy's most-trusted-source order, re-read by the matching step's
	 * next Configure), unlike {@link #removeSource}. An unknown source id is a no-op.
	 */
	public static void setSourceTrustRank(final Session pSession, final Adaptation pFactory, final String pSourceId, final int pTrustRank) throws OperationException
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation source = WizardData.byKey(sources, PrimaryKey.parseString(pSourceId));
				if (source == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
				value.setValue(Integer.valueOf(pTrustRank), FactoryPaths.ProjectSource.TRUST_RANK);
				pContext.doModifyContent(source, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Sources-landing lot SOURCES (2026-09-24): the Review screen's editable application code - the SAME
	 * field {@link #addSource}/{@code WizardRules.SourceDraft#sourceSystem} writes, {@code
	 * ProjectSource.sourceSystem}, pattern {@code [A-Za-z][A-Za-z0-9_]*} (checked in {@code tools/build_xsd.py}
	 * and by {@link WizardRules#sourceProblems}, both reused here rather than re-declared). Validates BEFORE
	 * writing (task brief item 3): blank, over {@link WizardRules#CODE_MAX}, pattern mismatch, or already used
	 * by ANOTHER source of the same project (never by this one - editing a code to its own current value must
	 * not be refused as "taken") all throw with no write attempted.
	 */
	public static void setSourceCode(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pSourceId, final String pCode)
		throws OperationException
	{
		final String code = pCode == null ? "" : pCode.trim();
		if (code.isEmpty())
		{
			throw EngineRefusal.simple("appCode.blank", "The application code cannot be blank.");
		}
		if (!code.matches(WizardRules.CODE_PATTERN) || code.length() > WizardRules.CODE_MAX)
		{
			throw EngineRefusal.simple("appCode.invalid", "The application code must start with a letter and contain only letters, digits and underscore, up to "
				+ WizardRules.CODE_MAX + " characters.", Integer.valueOf(WizardRules.CODE_MAX));
		}
		final java.util.Set<String> existingCodes = WizardData.existingSourceSystemCodes(pFactory, pProjectId);
		final AdaptationTable codeSources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final Adaptation current = WizardData.byKey(codeSources, PrimaryKey.parseString(pSourceId));
		final String currentCode = current == null ? "" : WizardData.text(current, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		if (!code.equals(currentCode) && existingCodes.contains(code))
		{
			throw EngineRefusal.simple("appCode.taken", "Another application of this project already uses the code " + code + ".", code);
		}
		final ProcedureResult codeResult = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation source = WizardData.byKey(codeSources, PrimaryKey.parseString(pSourceId));
				if (source == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
				value.setValue(code, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				pContext.doModifyContent(source, value);
			}
		});
		if (codeResult.hasFailed())
		{
			throw WizardData.failure(codeResult, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Sources-landing lot SOURCES (2026-09-24): "When two applications disagree" - move {@code pSourceId} one
	 * place up ({@code pUp=true}) or down in trust order, then rewrite EVERY source of the project's {@code
	 * trustRank} as 1..n in the new order (task brief: "rewrite ProjectSource.trustRank values as 1..n in the
	 * new order") - never a raw swap of whatever numeric values were there before (every source added through
	 * {@link #addSource}/{@code SourcesRun} defaults to 100, so a plain swap would leave most rows tied). Order
	 * is CURRENT {@code trustRank} ascending (lower = more trusted - confirmed by {@link
	 * WizardSnapshot.SourceInfo#trustRank}'s own Javadoc), ties broken by occurrence id so the order is stable.
	 * Moving the first source up, or the last one down, is a no-op that still renumbers 1..n (two sources could
	 * otherwise tie at the same rank).
	 */
	public static void reorderSourceTrust(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pSourceId, final boolean pUp)
		throws OperationException
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final AdaptationTable trustSources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final RequestResult rowsResult = WizardData.rows(trustSources, "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rowsResult.nextAdaptation(); row != null; row = rowsResult.nextAdaptation())
			{
				rows.add(row);
			}
		}
		finally
		{
			rowsResult.close();
		}
		Collections.sort(rows, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Object leftRank = WizardData.val(pLeft, FactoryPaths.ProjectSource.TRUST_RANK);
				final Object rightRank = WizardData.val(pRight, FactoryPaths.ProjectSource.TRUST_RANK);
				final int l = leftRank instanceof Number ? ((Number) leftRank).intValue() : 100;
				final int r = rightRank instanceof Number ? ((Number) rightRank).intValue() : 100;
				if (l != r)
				{
					return Integer.compare(l, r);
				}
				return Integer.compare(Integer.parseInt(pLeft.getOccurrencePrimaryKey().format()), Integer.parseInt(pRight.getOccurrencePrimaryKey().format()));
			}
		});
		int index = -1;
		for (int i = 0; i < rows.size(); i++)
		{
			if (rows.get(i).getOccurrencePrimaryKey().format().equals(pSourceId))
			{
				index = i;
				break;
			}
		}
		if (index < 0)
		{
			throw EngineRefusal.coded("missing.projectSource", "Application " + pSourceId + " not found in this project.", pSourceId);
		}
		final int swapWith = pUp ? index - 1 : index + 1;
		if (swapWith >= 0 && swapWith < rows.size())
		{
			Collections.swap(rows, index, swapWith);
		}
		final ProcedureResult trustResult = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (int i = 0; i < rows.size(); i++)
				{
					final Adaptation source = WizardData.byKey(trustSources, rows.get(i).getOccurrencePrimaryKey());
					if (source == null)
					{
						continue;
					}
					final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
					value.setValue(Integer.valueOf(i + 1), FactoryPaths.ProjectSource.TRUST_RANK);
					pContext.doModifyContent(source, value);
				}
			}
		});
		if (trustResult.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveFailed", trustResult.getExceptionFullMessage(pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale()));
		}
	}

	/**
	 * Removes an application from the project together with its landing PLAN: its {@code LandingTable} rows (and their columns) and every
	 * {@code LandingRelation} that names the source or one of its tables. Fix of 2026-10-16 (project Zefz, Snowflake): the scan plans the
	 * landing tables as soon as the Review card lists them, so the old guard ("no LandingTable at all") refused every Remove a person could
	 * click. Refuses only once something was really built from the plan: the source left SELECTED, a table is no longer PROPOSED or carries
	 * an inbound Connectors mapping, or a transfer feed, a staging attribute or an attribute mapping refers to the plan. A relation's own
	 * decision (ACCEPTED/REJECTED) is a review choice, not a generated object: it goes with the source. An unknown source id is a no-op.
	 */
	public static void removeSource(final Session pSession, final Adaptation pFactory, final String pSourceId) throws OperationException
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		final AdaptationTable relations = pFactory.getTable(FactoryPaths.LANDING_RELATION);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation source = WizardData.byKey(sources, PrimaryKey.parseString(pSourceId));
				if (source == null)
				{
					return;
				}
				if (!"SELECTED".equals(WizardData.str(source, FactoryPaths.ProjectSource.STATUS)))
				{
					throw EngineRefusal.simple("source.landingGenerated", "This source already has a landing generated for it: it cannot be removed any more.");
				}
				final List<Adaptation> planned = WizardData.drain(WizardData.rows(tables, "./projectSource=" + CatalogReader.quote(pSourceId)));
				final List<Adaptation> plannedColumns = new ArrayList<Adaptation>();
				final List<Adaptation> plannedRelations = WizardData.drain(WizardData.rows(relations, "./projectSource=" + CatalogReader.quote(pSourceId)));
				final Set<String> relationIds = new HashSet<String>();
				for (final Adaptation relation : plannedRelations)
				{
					relationIds.add(relation.getOccurrencePrimaryKey().format());
				}
				for (final Adaptation table : planned)
				{
					final String mapping = WizardData.str(table, FactoryPaths.LandingTable.INBOUND_MAPPING);
					if (!"PROPOSED".equals(WizardData.str(table, FactoryPaths.LandingTable.STATUS)) || (mapping != null && !mapping.trim().isEmpty()))
					{
						throw EngineRefusal.simple("source.landingPlanned", "A landing table of this source was already generated: it cannot be removed any more.");
					}
					final String tableId = CatalogReader.quote(table.getOccurrencePrimaryKey().format());
					if (WizardData.any(pFactory.getTable(FactoryPaths.TRANSFER_FEED), "./landingTable=" + tableId))
					{
						throw EngineRefusal.simple("source.landingPlanned", "A landing table of this source was already generated: it cannot be removed any more.");
					}
					for (final Adaptation column : WizardData.drain(WizardData.rows(columns, "./landingTable=" + tableId)))
					{
						if (WizardData.any(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./landingColumn=" + CatalogReader.quote(column.getOccurrencePrimaryKey().format())))
						{
							throw EngineRefusal.simple("source.landingPlanned", "A landing table of this source was already generated: it cannot be removed any more.");
						}
						plannedColumns.add(column);
					}
					// a relation planned for ANOTHER source may point to (or from) this source's table: it would dangle, so it goes too
					for (final Adaptation relation : WizardData.drain(WizardData.rows(relations, "./fromTable=" + tableId + " or ./toTable=" + tableId)))
					{
						if (relationIds.add(relation.getOccurrencePrimaryKey().format()))
						{
							plannedRelations.add(relation);
						}
					}
				}
				for (final String relationId : relationIds)
				{
					if (WizardData.any(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./fromRelation=" + CatalogReader.quote(relationId)))
					{
						throw EngineRefusal.simple("source.landingPlanned", "A landing table of this source was already generated: it cannot be removed any more.");
					}
				}
				// children before parents: relations and columns hold foreign keys to the tables, the tables to the source
				for (final Adaptation relation : plannedRelations)
				{
					pContext.doDelete(relation.getAdaptationName(), false);
				}
				for (final Adaptation column : plannedColumns)
				{
					pContext.doDelete(column.getAdaptationName(), false);
				}
				for (final Adaptation table : planned)
				{
					pContext.doDelete(table.getAdaptationName(), false);
				}
				pContext.doDelete(source.getAdaptationName(), false);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/** Every row of a request, the request closed. */
	private static List<Adaptation> drain(final RequestResult pRows)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		try
		{
			for (Adaptation row = pRows.nextAdaptation(); row != null; row = pRows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			pRows.close();
		}
		return out;
	}

	/** True when at least one row of the table matches the predicate. */
	private static boolean any(final AdaptationTable pTable, final String pPredicate)
	{
		final RequestResult rows = WizardData.rows(pTable, pPredicate);
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Landing-walkthrough lot (2026-10-16): {@code ProjectSource.landingValidated} of one application of {@code pProjectId} - true with the person and the time, or
	 * false (both cleared). A source of another project or a missing one is left alone. A model that does not have the field yet (the module XSD loads at the
	 * next start) makes this a no-op.
	 */
	public static void setLandingValidated(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pSourceId, final boolean pValidated)
		throws OperationException
	{
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final String user = pSession == null ? "" : Actor.current(pSession).userId;
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation source = WizardData.byKey(sources, PrimaryKey.parseString(pSourceId));
				if (source == null || !pProjectId.equals(WizardData.str(source, FactoryPaths.ProjectSource.PROJECT))
					|| source.getSchemaNode().getNode(FactoryPaths.ProjectSource.LANDING_VALIDATED) == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
				value.setValue(Boolean.valueOf(pValidated), FactoryPaths.ProjectSource.LANDING_VALIDATED);
				value.setValue(pValidated ? TextLimits.truncate(user, 100) : null, FactoryPaths.ProjectSource.LANDING_VALIDATED_BY);
				value.setValue(pValidated ? new java.util.Date() : null, FactoryPaths.ProjectSource.LANDING_VALIDATED_AT);
				pContext.doModifyContent(source, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Landing-walkthrough lot (2026-10-16): inside a running {@code Procedure}, clears the validation of the application a landing table belongs to - called by
	 * every write a person makes to a tick or a key ({@link #toggleTableSelected}, {@link #setTablesSelected}, {@link #setTableKey}, {@link #writeTableKey}),
	 * whichever screen it comes from (step 2 included). Writes nothing when the application is not validated or the field is not in the model yet.
	 */
	static void clearLandingValidation(final ProcedureContext pContext, final AdaptationTable pSources, final Adaptation pLandingTable) throws OperationException
	{
		final String sourceId = WizardData.str(pLandingTable, FactoryPaths.LandingTable.PROJECT_SOURCE);
		final Adaptation source = sourceId.isEmpty() ? null : WizardData.byKey(pSources, PrimaryKey.parseString(sourceId));
		if (source == null || source.getSchemaNode().getNode(FactoryPaths.ProjectSource.LANDING_VALIDATED) == null
			|| !Boolean.TRUE.equals(WizardData.val(source, FactoryPaths.ProjectSource.LANDING_VALIDATED)))
		{
			return;
		}
		final ValueContextForUpdate value = pContext.getContext(source.getAdaptationName());
		value.setValue(Boolean.FALSE, FactoryPaths.ProjectSource.LANDING_VALIDATED);
		value.setValue(null, FactoryPaths.ProjectSource.LANDING_VALIDATED_BY);
		value.setValue(null, FactoryPaths.ProjectSource.LANDING_VALIDATED_AT);
		pContext.doModifyContent(source, value);
	}

	/** Flips {@code LandingTable.selected}; refuses once the table is no longer PROPOSED (a generated/loaded table's scope is fixed). */
	public static void toggleTableSelected(final Session pSession, final Adaptation pFactory, final String pTableId) throws OperationException
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation table = WizardData.byKey(tables, PrimaryKey.parseString(pTableId));
				if (table == null)
				{
					return;
				}
				if (!"PROPOSED".equals(WizardData.str(table, FactoryPaths.LandingTable.STATUS)))
				{
					throw EngineRefusal.simple("table.scopeFrozen", "This table's landing was already generated: its scope can no longer be changed here.");
				}
				final boolean selected = !Boolean.FALSE.equals(WizardData.val(table, FactoryPaths.LandingTable.SELECTED));
				final ValueContextForUpdate value = pContext.getContext(table.getAdaptationName());
				value.setValue(Boolean.valueOf(!selected), FactoryPaths.LandingTable.SELECTED);
				pContext.doModifyContent(table, value);
				WizardData.clearLandingValidation(pContext, pFactory.getTable(FactoryPaths.PROJECT_SOURCE), table);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * UX lot U2 (2026-10-13): the ticks of "Keep these tables" in ONE write: every table of {@code pTickIds} gets {@code selected = true}, every one of
	 * {@code pUntickIds} {@code false}. Only the tables the person changed are sent ({@code SourceTableList#parseTicks}). A table of another project,
	 * a missing one, or one whose landing was already generated (its scope is fixed, like {@link #toggleTableSelected}) is left alone and counted.
	 *
	 * @return {@code {changed, left alone}}
	 */
	public static int[] setTablesSelected(final Session pSession, final Adaptation pFactory, final String pProjectId, final java.util.List<String> pTickIds,
		final java.util.List<String> pUntickIds) throws OperationException
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final AdaptationTable sources = pFactory.getTable(FactoryPaths.PROJECT_SOURCE);
		final int[] counts = new int[2];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (int pass = 0; pass < 2; pass++)
				{
					final boolean wanted = pass == 0;
					for (final String id : wanted ? pTickIds : pUntickIds)
					{
						final Adaptation table = WizardData.byKey(tables, PrimaryKey.parseString(id));
						final Adaptation source = table == null ? null : WizardData.byKey(sources, PrimaryKey.parseString(WizardData.str(table, FactoryPaths.LandingTable.PROJECT_SOURCE)));
						if (table == null || source == null || !pProjectId.equals(WizardData.str(source, FactoryPaths.ProjectSource.PROJECT))
							|| !"PROPOSED".equals(WizardData.str(table, FactoryPaths.LandingTable.STATUS)))
						{
							counts[1]++;
							continue;
						}
						if (!Boolean.FALSE.equals(WizardData.val(table, FactoryPaths.LandingTable.SELECTED)) == wanted)
						{
							continue; // already in that state: nothing to write
						}
						final ValueContextForUpdate value = pContext.getContext(table.getAdaptationName());
						value.setValue(Boolean.valueOf(wanted), FactoryPaths.LandingTable.SELECTED);
						pContext.doModifyContent(table, value);
						WizardData.clearLandingValidation(pContext, sources, table);
						counts[0]++;
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
		return counts;
	}

	/** UX lot U2 (2026-10-13): the application of {@code pProjectId} that already uses the catalog source {@code pCatalogSourceId}, as {@code {ProjectSource id, sourceSystem code}}, or null when none does - what the "Connect a live system" panel checks so it never offers a second application for the same system. */
	public static String[] applicationOfCatalogSource(final Adaptation pFactory, final String pProjectId, final String pCatalogSourceId)
	{
		if (pProjectId == null || pProjectId.trim().isEmpty() || pCatalogSourceId == null || pCatalogSourceId.trim().isEmpty())
		{
			return null;
		}
		final RequestResult rows = WizardData.rows(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + CatalogReader.quote(pProjectId) + " and ./catalogSource=" + CatalogReader.quote(pCatalogSourceId));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : new String[] { row.getOccurrencePrimaryKey().format(), WizardData.str(row, FactoryPaths.ProjectSource.SOURCE_SYSTEM) };
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Keeps a key proposal that was NOT applied (below {@code KeyCertainty}'s bar) on the table itself:
	 * {@code keyConfidence}, {@code keyRationale} and {@code message} only - the {@code LandingColumn.sourceKey}
	 * flags are left untouched, so the table still has no key and still waits for a person. Only on a
	 * still-{@code PROPOSED} table; a no-op otherwise.
	 * <p>
	 * <b>Key-confidence lot (2026-09-28, project DeducedTest3, live incident):</b> also a no-op when the
	 * table's key is already {@link KeyCertainty#alreadySettled} (USER/SOURCE/DEDUCED) - an unapplied study
	 * proposal must never second-guess a key a person already confirmed, whatever confidence it scored. This
	 * is a defensive backstop: {@code ebx.LandingRun#runKeyStage} already skips calling {@code KeyStudy}
	 * entirely for a settled key (that is the fix for the live incident itself, &sect;14 of {@code
	 * docs/DEDUCED-SCAN.md} - {@code WizardData#originRank} not knowing {@code DEDUCED}, since fixed), so this
	 * method should never actually be reached for a settled table any more; it refuses anyway, in case a
	 * future caller (or a bug like that one) calls it on one regardless.
	 */
	public static void recordKeyProposal(final Session pSession, final Adaptation pFactory, final String pTableId, final int pConfidence,
		final String pRationale) throws OperationException
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation table = WizardData.byKey(tables, PrimaryKey.parseString(pTableId));
				if (table == null || !"PROPOSED".equals(WizardData.str(table, FactoryPaths.LandingTable.STATUS)))
				{
					return;
				}
				if (KeyCertainty.alreadySettled(WizardData.aggregatedKeyOrigin(columns, pTableId), true, pConfidence))
				{
					// the aggregation above only reports a settled origin when the table actually has key
					// columns (see #aggregatedKeyOrigin), so pHasKeyColumns=true here is exact, not a guess
					return;
				}
				final String rationale = pRationale == null ? "" : pRationale;
				final ValueContextForUpdate tableValue = pContext.getContext(table.getAdaptationName());
				tableValue.setValue(Integer.valueOf(Math.max(0, Math.min(100, pConfidence))), FactoryPaths.LandingTable.KEY_CONFIDENCE);
				tableValue.setValue(KeyTexts.fit(rationale, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.KEY_RATIONALE);
				tableValue.setValue(KeyTexts.fit("Source key: " + rationale, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.MESSAGE);
				pContext.doModifyContent(table, tableValue);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Key-confidence lot (2026-09-28): the aggregated {@code LandingColumn.sourceKeyOrigin} of a table's
	 * CURRENT key columns, the same fold {@link #strongerOrigin}/{@link #originRank} already do in {@link
	 * #tableRow} - read directly here (rather than through a full {@code tableRow}) since the caller only
	 * needs the one aggregated string. Returns {@code ""} when the table has no key column, exactly like
	 * {@code WizardSnapshotTable#keyOrigin} does.
	 */
	private static String aggregatedKeyOrigin(final AdaptationTable pColumns, final String pTableId) throws OperationException
	{
		String keyOrigin = "";
		final RequestResult rows = WizardData.rows(pColumns, "./landingTable=" + CatalogReader.quote(pTableId));
		try
		{
			for (Adaptation column = rows.nextAdaptation(); column != null; column = rows.nextAdaptation())
			{
				if (Boolean.TRUE.equals(WizardData.val(column, FactoryPaths.LandingColumn.SOURCE_KEY)))
				{
					keyOrigin = WizardData.strongerOrigin(keyOrigin, WizardData.text(column, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return keyOrigin;
	}

	/**
	 * Key-confidence lot (2026-09-28, project DeducedTest3, live incident): rewrites {@code keyConfidence}/
	 * {@code keyRationale}/{@code message} to the canonical settled value ({@link
	 * KeyCertainty#settledConfidence}) - called by {@code ebx.LandingRun#runKeyStage} whenever it finds the
	 * table's key already {@link KeyCertainty#alreadySettled} (USER/SOURCE/DEDUCED) and the table does not
	 * already carry that value, so a stale confidence an earlier faulty pass left on the table (the live
	 * incident this lot fixes: a study proposal's low confidence, written by {@link #recordKeyProposal} back
	 * when {@link #originRank} did not yet know {@code DEDUCED} - &sect;14 of {@code docs/DEDUCED-SCAN.md})
	 * can never survive. Never touches {@code LandingColumn}: only the table's own fields reflect the key
	 * that is already in place. A no-op once the table already carries {@code pConfidence}/{@code pRationale}.
	 */
	public static void settleKeyConfidence(final Session pSession, final Adaptation pFactory, final String pTableId, final int pConfidence,
		final String pRationale) throws OperationException
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation table = WizardData.byKey(tables, PrimaryKey.parseString(pTableId));
				if (table == null)
				{
					return;
				}
				final String rationale = pRationale == null ? "" : pRationale;
				final Object currentConfidence = WizardData.val(table, FactoryPaths.LandingTable.KEY_CONFIDENCE);
				final int current = currentConfidence instanceof Number ? ((Number) currentConfidence).intValue() : -1;
				if (current == pConfidence && rationale.equals(WizardData.text(table, FactoryPaths.LandingTable.KEY_RATIONALE)))
				{
					return;
				}
				final ValueContextForUpdate tableValue = pContext.getContext(table.getAdaptationName());
				tableValue.setValue(Integer.valueOf(Math.max(0, Math.min(100, pConfidence))), FactoryPaths.LandingTable.KEY_CONFIDENCE);
				tableValue.setValue(KeyTexts.fit(rationale, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.KEY_RATIONALE);
				tableValue.setValue(KeyTexts.fit("Source key: " + rationale, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.MESSAGE);
				pContext.doModifyContent(table, tableValue);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Rewrites {@code LandingColumn.sourceKey} for every column of one table from a comma-separated list
	 * of source column names (the mockup's single "proposed key" text field - see {@code WizardSnapshot.TableInfo#keyColumns}):
	 * a column named in {@code pColumns} gets {@code sourceKey=true, sourceKeyOrigin=USER}, every other
	 * column of the table gets {@code sourceKey=false}. Refuses once the table is no longer PROPOSED.
	 */
	public static void setTableKey(final Session pSession, final Adaptation pFactory, final String pTableId, final String pColumns) throws OperationException
	{
		WizardData.setTableKey(pSession, pFactory, pTableId, pColumns, "USER", 100, KeyTexts.chosen().stored());
	}

	/**
	 * Landing key-study lot (2026-09-23): the same rewrite with an explicit {@code sourceKeyOrigin} ({@code
	 * USER} for the picker, {@code LLM}/{@code RULE} for an applied study proposal) and the table's own
	 * {@code keyConfidence}/{@code keyRationale}, so the wizard's confidence bar and rationale fold reflect
	 * whoever chose the key last. Every column of the table gets the origin, the unchecked ones included: the
	 * origin describes the whole key decision, not one column.
	 */
	public static void setTableKey(final Session pSession, final Adaptation pFactory, final String pTableId, final String pColumns, final String pOrigin,
		final int pConfidence, final String pRationale) throws OperationException
	{
		final Set<String> wanted = new HashSet<String>();
		for (final String column : pColumns.split(","))
		{
			final String trimmed = column.trim();
			if (!trimmed.isEmpty())
			{
				wanted.add(trimmed);
			}
		}
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation table = WizardData.byKey(tables, PrimaryKey.parseString(pTableId));
				if (table == null)
				{
					return;
				}
				if (!"PROPOSED".equals(WizardData.str(table, FactoryPaths.LandingTable.STATUS)))
				{
					throw EngineRefusal.simple("table.keyFrozen", "This table's landing was already generated: its key can no longer be changed here.");
				}
				final RequestResult rows = WizardData.rows(columns, "./landingTable=" + CatalogReader.quote(pTableId));
				try
				{
					for (Adaptation column = rows.nextAdaptation(); column != null; column = rows.nextAdaptation())
					{
						final boolean inKey = wanted.contains(WizardData.str(column, FactoryPaths.LandingColumn.SOURCE_COLUMN));
						final boolean wasInKey = Boolean.TRUE.equals(WizardData.val(column, FactoryPaths.LandingColumn.SOURCE_KEY));
						final boolean sameOrigin = pOrigin.equals(WizardData.str(column, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN));
						if (inKey == wasInKey && sameOrigin)
						{
							continue;
						}
						final ValueContextForUpdate value = pContext.getContext(column.getAdaptationName());
						value.setValue(Boolean.valueOf(inKey), FactoryPaths.LandingColumn.SOURCE_KEY);
						value.setValue(pOrigin, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN);
						pContext.doModifyContent(column, value);
					}
				}
				finally
				{
					rows.close();
				}
				final ValueContextForUpdate tableValue = pContext.getContext(table.getAdaptationName());
				tableValue.setValue(Integer.valueOf(Math.max(0, Math.min(100, pConfidence))), FactoryPaths.LandingTable.KEY_CONFIDENCE);
				tableValue.setValue(TextLimits.truncate(pRationale == null ? "" : pRationale, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.KEY_RATIONALE);
				tableValue.setValue(KeyTexts.fit("Source key: " + (pRationale == null ? "" : pRationale), TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.MESSAGE);
				pContext.doModifyContent(table, tableValue);
				WizardData.clearLandingValidation(pContext, pFactory.getTable(FactoryPaths.PROJECT_SOURCE), table);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Volume wave 2, lot V2-E (2026-10-03): a person's choice of the table's CHANGE column (the source column that carries a row's last
	 * change; a reload then reads only the rows changed since the last successful load) and its kind, from step 3. A blank {@code
	 * pColumn} is "none - reload in full". Unlike the key, it may change at any time: the flow is regenerated by every landing run, and a
	 * column the last load did not read through makes the next load a full refresh by itself ({@link ChangeColumnRule#fullRefreshNeeded}).
	 */
	public static void setTableChangeColumn(final Session pSession, final Adaptation pFactory, final String pTableId, final String pColumn, final String pKind)
		throws OperationException
	{
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final Adaptation row = WizardData.byKey(tables, PrimaryKey.parseString(pTableId));
		if (row == null)
		{
			throw EngineRefusal.coded("missing.landingTable", "Landing table " + pTableId + " not found.", pTableId);
		}
		if (row.getSchemaNode().getNode(FactoryPaths.LandingTable.CHANGE_COLUMN) == null)
		{
			throw EngineRefusal.coded("conflict.changeColumnNotLoaded", "The change column is not in the loaded model yet: it is available after the next start of EBX.");
		}
		final java.util.Map<String, String> types = new java.util.LinkedHashMap<String, String>();
		final RequestResult columnRows = WizardData.rows(pFactory.getTable(FactoryPaths.LANDING_COLUMN), "./landingTable=" + CatalogReader.quote(pTableId));
		try
		{
			for (Adaptation column = columnRows.nextAdaptation(); column != null; column = columnRows.nextAdaptation())
			{
				types.put(WizardData.text(column, FactoryPaths.LandingColumn.SOURCE_COLUMN), WizardData.text(column, FactoryPaths.LandingColumn.INFERRED_TYPE));
			}
		}
		finally
		{
			columnRows.close();
		}
		final ChangeColumnRule.Validation validation = ChangeColumnRule.validate(types, pColumn, pKind);
		if (!validation.ok)
		{
			throw EngineRefusal.coded("conflict.refused", validation.message);
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(WizardData.orNull(validation.column), FactoryPaths.LandingTable.CHANGE_COLUMN);
				value.setValue(WizardData.orNull(validation.kind), FactoryPaths.LandingTable.CHANGE_COLUMN_KIND);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Composite-key lot (2026-09-24): the same {@code LandingColumn}/{@code LandingTable} write as
	 * {@link #setTableKey}, WITHOUT its "must still be PROPOSED" refusal - for {@code ebx.LandingKeyChange},
	 * the one caller allowed to change the key of a table whose landing is already GENERATED/LOADED, and
	 * which takes care of deleting the table's existing landing rows and regenerating its flow itself (see
	 * that class' own Javadoc for the full sequence). Deliberately NOT a refactor of {@link #setTableKey} to
	 * share this body (the common-rules brief: "add new methods rather than rewriting shared ones") - a
	 * short duplication of a ~25-line write is a smaller risk than changing a method three parallel lots
	 * also touch. Never call this from the wizard's ordinary key picker or key-study "Apply" - both keep
	 * going through {@link #setTableKey} and its refusal; this is package-visible on purpose, reachable only
	 * from {@code com.on.ebx.factory.ebx}.
	 */
	static void writeTableKey(final Session pSession, final Adaptation pFactory, final String pTableId, final String pColumns, final String pOrigin,
		final int pConfidence, final String pRationale) throws OperationException
	{
		WizardData.writeTableKey(pSession, pFactory, pTableId, pColumns, pOrigin, pConfidence, pRationale, true);
	}

	/**
	 * Landing leftovers (2026-10-17): the same write, {@code pClearValidation = false} for the planning job's own proposal ({@link KeyPrimer}): it is not a
	 * person's decision, so it must not un-validate an application the person validated.
	 */
	static void writeTableKey(final Session pSession, final Adaptation pFactory, final String pTableId, final String pColumns, final String pOrigin,
		final int pConfidence, final String pRationale, final boolean pClearValidation) throws OperationException
	{
		final Set<String> wanted = new HashSet<String>();
		for (final String column : pColumns.split(","))
		{
			final String trimmed = column.trim();
			if (!trimmed.isEmpty())
			{
				wanted.add(trimmed);
			}
		}
		final AdaptationTable tables = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final AdaptationTable columns = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation table = WizardData.byKey(tables, PrimaryKey.parseString(pTableId));
				if (table == null)
				{
					return;
				}
				final RequestResult rows = WizardData.rows(columns, "./landingTable=" + CatalogReader.quote(pTableId));
				try
				{
					for (Adaptation column = rows.nextAdaptation(); column != null; column = rows.nextAdaptation())
					{
						final boolean inKey = wanted.contains(WizardData.str(column, FactoryPaths.LandingColumn.SOURCE_COLUMN));
						final boolean wasInKey = Boolean.TRUE.equals(WizardData.val(column, FactoryPaths.LandingColumn.SOURCE_KEY));
						final boolean sameOrigin = pOrigin.equals(WizardData.str(column, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN));
						if (inKey == wasInKey && sameOrigin)
						{
							continue;
						}
						final ValueContextForUpdate value = pContext.getContext(column.getAdaptationName());
						value.setValue(Boolean.valueOf(inKey), FactoryPaths.LandingColumn.SOURCE_KEY);
						value.setValue(pOrigin, FactoryPaths.LandingColumn.SOURCE_KEY_ORIGIN);
						pContext.doModifyContent(column, value);
					}
				}
				finally
				{
					rows.close();
				}
				final ValueContextForUpdate tableValue = pContext.getContext(table.getAdaptationName());
				tableValue.setValue(Integer.valueOf(Math.max(0, Math.min(100, pConfidence))), FactoryPaths.LandingTable.KEY_CONFIDENCE);
				tableValue.setValue(TextLimits.truncate(pRationale == null ? "" : pRationale, TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.KEY_RATIONALE);
				tableValue.setValue(KeyTexts.fit("Source key: " + (pRationale == null ? "" : pRationale), TextLimits.MESSAGE_MAX_LENGTH), FactoryPaths.LandingTable.MESSAGE);
				pContext.doModifyContent(table, tableValue);
				if (pClearValidation)
				{
					WizardData.clearLandingValidation(pContext, pFactory.getTable(FactoryPaths.PROJECT_SOURCE), table);
				}
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Bulk-mapping-cascade lot (2026-09-23, coordinator finding on project Personnes): moves every
	 * {@code AttributeMapping} row back to {@code PROPOSED} that {@link MappingFollowRule} once cascaded to
	 * {@code REJECTED} (an entity/attribute was rejected, then re-accepted) and that is now the ONLY thing an
	 * accepted attribute has to feed it - i.e. exactly {@link ReviewRules#attributeMappingWarning}'s own
	 * "Accepted, but no mapping feeds it." condition. Deliberately conservative in two ways, both load-
	 * bearing for "a person decides" staying true:
	 * <ul>
	 * <li>only a {@code RULE}/{@code LLM}-origin mapping is touched - a person's own {@code USER} mapping is
	 * never moved by anything but that person;</li>
	 * <li>reopened to {@code PROPOSED}, never straight back to {@code ACCEPTED} - the mapping becomes visible
	 * and decidable again (on its own row, or through the ordinary bulk-accept threshold), it is not silently
	 * re-accepted on the caller's behalf. {@code AttributeMapping} carries no "who/how decided this" trace
	 * distinguishing an entity-level cascade from a reviewer's own individual Reject on that one mapping row,
	 * so treating every REJECTED mapping of an accepted attribute as eligible for the ordinary bulk-accept
	 * (the other option the coordinator's finding offered) would risk silently overturning a genuine,
	 * deliberate single-mapping rejection - this dedicated, explicit action is the safer of the two.</li>
	 * </ul>
	 * @return how many mapping rows were reopened, for the caller's own notice.
	 */
	public static int reopenOrphanedMappings(final Session pSession, final Adaptation pFactory, final String pProjectId) throws OperationException
	{
		final AdaptationTable entities = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final AdaptationTable attributes = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final AdaptationTable mappings = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final int[] holder = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				int reopened = 0;
				final List<String> entityIds = new ArrayList<String>();
				final RequestResult entityRows = WizardData.rows(entities, "./project=" + CatalogReader.quote(pProjectId));
				try
				{
					for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
					{
						entityIds.add(row.getOccurrencePrimaryKey().format());
					}
				}
				finally
				{
					entityRows.close();
				}
				for (final String entityId : entityIds)
				{
					final List<Adaptation> attributeRowList = new ArrayList<Adaptation>();
					final RequestResult attributeRows = WizardData.rows(attributes, "./entity=" + CatalogReader.quote(entityId));
					try
					{
						for (Adaptation row = attributeRows.nextAdaptation(); row != null; row = attributeRows.nextAdaptation())
						{
							attributeRowList.add(row);
						}
					}
					finally
					{
						attributeRows.close();
					}
					for (final Adaptation attribute : attributeRowList)
					{
						final String status = WizardData.text(attribute, FactoryPaths.StagingAttribute.STATUS);
						if (!ReviewLine.ACCEPTED.equals(status) && !ReviewLine.APPLIED.equals(status))
						{
							continue;
						}
						if (Boolean.TRUE.equals(WizardData.val(attribute, FactoryPaths.StagingAttribute.PRIMARY_KEY)))
						{
							continue; // the technical key has no mapping of its own - not this rule's concern
						}
						if (!WizardData.text(attribute, FactoryPaths.StagingAttribute.DERIVATION).isEmpty())
						{
							continue; // a derived (FOLD_KEY) attribute never has a mapping by design
						}
						final String attributeId = attribute.getOccurrencePrimaryKey().format();
						final List<Adaptation> mappingRowList = new ArrayList<Adaptation>();
						boolean hasFeedingMapping = false;
						final RequestResult mappingRows = WizardData.rows(mappings, "./attribute=" + CatalogReader.quote(attributeId));
						try
						{
							for (Adaptation row = mappingRows.nextAdaptation(); row != null; row = mappingRows.nextAdaptation())
							{
								mappingRowList.add(row);
								if (!ReviewLine.REJECTED.equals(WizardData.text(row, FactoryPaths.AttributeMapping.STATUS)))
								{
									hasFeedingMapping = true;
								}
							}
						}
						finally
						{
							mappingRows.close();
						}
						if (hasFeedingMapping || mappingRowList.isEmpty())
						{
							continue; // either it is already fed, or it never had a mapping at all - nothing to reopen
						}
						for (final Adaptation mapping : mappingRowList)
						{
							final String mappingOrigin = WizardData.text(mapping, FactoryPaths.AttributeMapping.ORIGIN);
							if (!"RULE".equals(mappingOrigin) && !"LLM".equals(mappingOrigin))
							{
								continue; // a person's own USER mapping: never touched by this rule
							}
							final ValueContextForUpdate value = pContext.getContext(mapping.getAdaptationName());
							value.setValue("PROPOSED", FactoryPaths.AttributeMapping.STATUS);
							pContext.doModifyContent(mapping, value);
							reopened++;
						}
					}
				}
				holder[0] = reopened;
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? java.util.Locale.ENGLISH : pSession.getLocale());
		}
		return holder[0];
	}

	// ------------------------------------------------------------------------------------------------------- helpers

	/**
	 * Audit F-32 (2026-10-06): what ONE {@link #readSnapshot} reads, each table once. The snapshot used to run a predicate query per source, landing
	 * table, entity, attribute, feed and match plan, plus a dozen {@code PipelineStep} queries - every one a scan of a table shared by all projects (only
	 * the primary key is indexed, CLAUDE.md section 5). The parents are known before their children are read, so the children of ALL of them come
	 * back from one {@link KeyedRows#byKey} scan and are joined in memory; the per-pair {@code PipelineStep} reads are one {@link PipelineStepIndex}
	 * scan. Every list is the rows the old per-parent query returned, in the same (table) order; each table is read the first time one of its rows is
	 * asked for, so a project with no staging entity reads none of the entity tables, as before.
	 */
	private static final class Reads
	{
		final Adaptation factory;
		final String projectId;
		/** Audit F-02 (catalog reads): the person's reader of the catalog for the whole snapshot. */
		final CatalogReader.Reader catalogReader;

		private PipelineStepIndex steps;
		private java.util.Set<String> entityIds;
		private java.util.Map<String, List<Adaptation>> attributes;
		private java.util.Map<String, List<Adaptation>> feeds;
		private java.util.Map<String, List<Adaptation>> plans;
		private java.util.Map<String, List<Adaptation>> mappingsByAttribute;
		private java.util.Map<String, List<Adaptation>> mappingsByFeed;
		private java.util.Map<String, List<Adaptation>> landingTables = new java.util.HashMap<String, List<Adaptation>>();
		private java.util.Map<String, List<Adaptation>> landingColumns = new java.util.HashMap<String, List<Adaptation>>();
		private java.util.Map<String, List<Adaptation>> relations = new java.util.HashMap<String, List<Adaptation>>();

		Reads(final Adaptation pFactory, final String pProjectId, final CatalogReader.Reader pCatalogReader)
		{
			this.factory = pFactory;
			this.projectId = pProjectId;
			this.catalogReader = pCatalogReader;
		}

		/** The step kinds a snapshot shows: the project-wide ones, the four of a source's landing, the matching run of a plan. */
		private static java.util.Set<String> stepKinds()
		{
			final java.util.Set<String> kinds = new java.util.LinkedHashSet<String>(java.util.Arrays.asList(WizardData.PROJECT_STEPS));
			kinds.addAll(java.util.Arrays.asList("LANDING_MODEL", "LANDING_DATASET", "LANDING_FLOWS", "LANDING_LOAD", "MATCHING_RUN"));
			return kinds;
		}

		PipelineStepIndex steps()
		{
			if (this.steps == null)
			{
				this.steps = PipelineStepIndex.read(this.factory, this.projectId, Reads.stepKinds());
			}
			return this.steps;
		}

		private static List<Adaptation> orEmpty(final java.util.Map<String, List<Adaptation>> pMap, final String pKey)
		{
			final List<Adaptation> rows = pMap == null ? null : pMap.get(pKey);
			return rows == null ? new ArrayList<Adaptation>() : rows;
		}

		private static List<String> idsOf(final java.util.Map<String, List<Adaptation>> pMap)
		{
			final List<String> ids = new ArrayList<String>();
			if (pMap != null)
			{
				for (final List<Adaptation> rows : pMap.values())
				{
					for (final Adaptation row : rows)
					{
						ids.add(row.getOccurrencePrimaryKey().format());
					}
				}
			}
			return ids;
		}

		/** Step 2: the landing tables, their columns and the relations of every source of the project, three scans in all. */
		void planLanding(final List<Adaptation> pSourceRows)
		{
			final List<String> sourceIds = new ArrayList<String>();
			for (final Adaptation source : pSourceRows)
			{
				sourceIds.add(source.getOccurrencePrimaryKey().format());
			}
			this.landingTables = KeyedRows.byKey(this.factory.getTable(FactoryPaths.LANDING_TABLE), FactoryPaths.LandingTable.PROJECT_SOURCE, sourceIds);
			this.relations = KeyedRows.byKey(this.factory.getTable(FactoryPaths.LANDING_RELATION), FactoryPaths.LandingRelation.PROJECT_SOURCE, sourceIds);
			this.landingColumns = KeyedRows.byKey(this.factory.getTable(FactoryPaths.LANDING_COLUMN), FactoryPaths.LandingColumn.LANDING_TABLE,
				Reads.idsOf(this.landingTables));
		}

		List<Adaptation> landingTablesOf(final String pSourceId)
		{
			return Reads.orEmpty(this.landingTables, pSourceId);
		}

		List<Adaptation> landingColumnsOf(final String pTableId)
		{
			return Reads.orEmpty(this.landingColumns, pTableId);
		}

		List<Adaptation> relationsOf(final String pSourceId)
		{
			return Reads.orEmpty(this.relations, pSourceId);
		}

		/** Step 4: the entities of the project, known once {@code StagingEntity} was read; their attributes come back from one scan. */
		void planEntities(final java.util.Collection<String> pEntityIds)
		{
			this.entityIds = new java.util.LinkedHashSet<String>(pEntityIds);
			this.attributes = KeyedRows.byKey(this.factory.getTable(FactoryPaths.STAGING_ATTRIBUTE), FactoryPaths.StagingAttribute.ENTITY, this.entityIds);
		}

		boolean knowsEntity(final String pEntityId)
		{
			return this.entityIds != null && this.entityIds.contains(pEntityId);
		}

		List<Adaptation> attributesOf(final String pEntityId)
		{
			return Reads.orEmpty(this.attributes, pEntityId);
		}

		private void loadFeeds()
		{
			if (this.feeds == null)
			{
				this.feeds = KeyedRows.byKey(this.factory.getTable(FactoryPaths.TRANSFER_FEED), FactoryPaths.TransferFeed.ENTITY,
					this.entityIds == null ? new ArrayList<String>() : this.entityIds);
			}
		}

		List<Adaptation> feedsOf(final String pEntityId)
		{
			this.loadFeeds();
			return Reads.orEmpty(this.feeds, pEntityId);
		}

		List<Adaptation> plansOf(final String pEntityId)
		{
			if (this.plans == null)
			{
				this.plans = KeyedRows.byKey(this.factory.getTable(FactoryPaths.MATCH_PLAN), FactoryPaths.MatchPlan.ENTITY,
					this.entityIds == null ? new ArrayList<String>() : this.entityIds);
			}
			return Reads.orEmpty(this.plans, pEntityId);
		}

		List<Adaptation> mappingsOfAttribute(final String pAttributeId)
		{
			this.loadMappings();
			return Reads.orEmpty(this.mappingsByAttribute, pAttributeId);
		}

		List<Adaptation> mappingsOfFeed(final String pFeedId)
		{
			this.loadMappings();
			return Reads.orEmpty(this.mappingsByFeed, pFeedId);
		}

		/** One scan of {@code AttributeMapping} for the attributes AND the feeds of every entity of the snapshot (a row may answer both). */
		private void loadMappings()
		{
			if (this.mappingsByAttribute != null)
			{
				return;
			}
			this.loadFeeds();
			final java.util.Set<String> attributeIds = new java.util.LinkedHashSet<String>(Reads.idsOf(this.attributes));
			final java.util.Set<String> feedIds = new java.util.LinkedHashSet<String>(Reads.idsOf(this.feeds));
			final KeyedRows.Buckets<Adaptation> byAttribute = new KeyedRows.Buckets<Adaptation>(attributeIds);
			final KeyedRows.Buckets<Adaptation> byFeed = new KeyedRows.Buckets<Adaptation>(feedIds);
			if (!attributeIds.isEmpty() || !feedIds.isEmpty())
			{
				String predicate = null;
				if (KeyedRows.filterInQuery(attributeIds.size() + feedIds.size()))
				{
					final String byAttributePredicate = KeyedRows.inPredicate(FactoryPaths.AttributeMapping.ATTRIBUTE.format(), attributeIds);
					final String byFeedPredicate = KeyedRows.inPredicate(FactoryPaths.AttributeMapping.FEED.format(), feedIds);
					predicate = byAttributePredicate.isEmpty() ? byFeedPredicate : byFeedPredicate.isEmpty() ? byAttributePredicate
						: byAttributePredicate + " or " + byFeedPredicate;
				}
				final RequestResult rows = WizardData.rows(this.factory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), predicate);
				try
				{
					for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
					{
						byAttribute.offer(WizardData.str(row, FactoryPaths.AttributeMapping.ATTRIBUTE), row);
						byFeed.offer(WizardData.str(row, FactoryPaths.AttributeMapping.FEED), row);
					}
				}
				finally
				{
					rows.close();
				}
			}
			this.mappingsByFeed = byFeed.result();
			this.mappingsByAttribute = byAttribute.result();
		}
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final String value = WizardData.str(pRow, pPath);
		return value == null ? "" : value;
	}

	// ----------------------------------------------------------------------------------- reads, user view aware
	// Second permissions pass (2026-10-14): every read of this class goes through UserView.current() - the user's view inside
	// readSnapshot(UserView, ...) (a scope opened around the read only), the engine's (every row, every field) everywhere else: jobs, workflow
	// steps and the procedures below read exactly as before.

	private static RequestResult rows(final com.onwbp.adaptation.AdaptationTable pTable, final String pPredicate)
	{
		return UserView.current().rows(pTable, pPredicate);
	}

	private static RequestResult rows(final com.onwbp.adaptation.AdaptationTable pTable, final String pPredicate, final com.onwbp.adaptation.RequestSortCriteria pSort)
	{
		return UserView.current().rows(pTable, pPredicate, pSort);
	}

	private static Adaptation byKey(final com.onwbp.adaptation.AdaptationTable pTable, final PrimaryKey pKey)
	{
		final UserView view = UserView.current();
		return view.engine() ? pTable.lookupAdaptationByPrimaryKey(pKey) : view.lookup(pTable, pKey);
	}

	/** The field as a string (null when empty or hidden from the user of the scope); a non-string value is formatted. */
	private static String str(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final Object value = WizardData.val(pRow, pPath);
		return value == null ? null : value instanceof String ? (String) value : String.valueOf(value);
	}

	private static Object val(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final UserView view = UserView.current();
		return view.engine() ? pRow.get(pPath) : view.value(pRow, pPath);
	}

	@SuppressWarnings("unchecked")
	private static <T> List<T> lst(final Adaptation pRow, final com.orchestranetworks.schema.Path pPath)
	{
		final UserView view = UserView.current();
		if (view.engine())
		{
			return pRow.getList(pPath);
		}
		final Object value = view.value(pRow, pPath);
		return value instanceof List ? (List<T>) value : null;
	}

	private static String orNull(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue;
	}

	/**
	 * Audit F-39: the exception for a failed procedure of this class - the coded refusal it threw ({@link EngineRefusal}, which the
	 * wizard localizes) when there is one, otherwise the procedure's full message as before.
	 */
	static OperationException failure(final ProcedureResult pResult, final Locale pLocale)
	{
		final EngineRefusal coded = EngineRefusal.find(pResult.getException());
		return coded != null ? coded : EngineRefusal.coded("unexpected.saveFailed", pResult.getExceptionFullMessage(pLocale));
	}
}
