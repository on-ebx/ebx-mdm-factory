package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.staging.AnomalyLlmPass;
import com.on.ebx.factory.staging.AttentionRules;
import com.on.ebx.factory.staging.ModelAnswers;
import com.on.ebx.factory.staging.StagingRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Staging-proposal-run lot (2026-09-24, docs/STAGING-PROPOSAL.md "run"): runs {@link AnomalyLlmPass} - the
 * language model's review of every {@link AttentionRules#CONSTANT_COLUMN}/{@link
 * AttentionRules#EMPTY_COLUMN} flag the project's current mappings raise - one {@link AgentRuntime} call per
 * flagged business object (batched: every flag of that entity in one call, same "the factory drives the
 * iteration" posture {@code QualityLlmSuggester}/{@code CapabilityLlmSuggester} already follow). {@link
 * AttentionRules#DUPLICATE_FIELD} flags are measured and counted in the returned summary ({@code
 * duplicateFieldFlags}) but never sent to the model - a shared column is not a "wrong column" question, it
 * has no useful "replacement" answer, so nothing is proposed for it (the reviewer's own tools already show
 * it).
 * <p>
 * A replacement the model proposes is written as a NEW {@code AttributeMapping} row (origin {@code LLM},
 * status {@code PROPOSED}, same {@code feed} as the flagged mapping) with {@link
 * FactoryPaths.AttributeMapping#ALTERNATIVE_TO} pointing at the flagged mapping - the flagged mapping itself
 * is never touched (a reviewer decides between the two: reject the original, which now also reads as
 * "AttributeMapping.alternativeTo cascade" material for a future lot, or reject the replacement). Never a
 * real row value: only fill rate / distinct count / max length are ever shown to the model here (unlike
 * {@code QualityLlmPass}, this pass has no {@code Project.llmValueSharing = SAMPLE_VALUES} option of its
 * own yet - deliberately out of scope for this lot, see {@code docs/STAGING-PROPOSAL.md} "run" for why).
 */
public final class AnomalyLlmSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP = "ANOMALY_SUGGEST_LLM";
	private static final String PHASE = "ANOMALY_SUGGEST_LLM";
	private static final int RATIONALE_MAX_LENGTH = 2000;
	/** A candidate replacement column list is capped here - a defensive bound on prompt size, never a product rule. */
	private static final int MAX_UNUSED_COLUMNS_PER_FLAG = 30;

	private AnomalyLlmSuggester()
	{
	}

	/** One measured flag this pass could not (or chose not to) send to the model, or that it sent - for {@code StagingProposalRun}'s own detail JSON. */
	public static final class Result
	{
		public final int constantColumnFlags;
		public final int emptyColumnFlags;
		public final int duplicateFieldFlags;
		/** Validation desk lot L3: {@link AttentionRules#SAME_CONSTANT} mappings measured (every source writes the same constant). */
		public final int sameConstantFlags;
		public final int replacementsProposed;
		public final int entitiesRun;
		public final ObjectNode raw;
		/** Validation desk lot L3b: per SAME_CONSTANT mapping submitted to the model in a run that ended normally, what it answered (see {@link ModelAnswers}). */
		public final List<ModelAnswers.Answer> answers = new ArrayList<ModelAnswers.Answer>();

		Result(final int pConstant, final int pEmpty, final int pDuplicate, final int pReplacements, final int pEntitiesRun, final ObjectNode pRaw)
		{
			this(pConstant, pEmpty, pDuplicate, 0, pReplacements, pEntitiesRun, pRaw);
		}

		Result(final int pConstant, final int pEmpty, final int pDuplicate, final int pSameConstant, final int pReplacements, final int pEntitiesRun, final ObjectNode pRaw)
		{
			this.sameConstantFlags = pSameConstant;
			this.constantColumnFlags = pConstant;
			this.emptyColumnFlags = pEmpty;
			this.duplicateFieldFlags = pDuplicate;
			this.replacementsProposed = pReplacements;
			this.entitiesRun = pEntitiesRun;
			this.raw = pRaw;
		}
	}

	public static Result suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		return AnomalyLlmSuggester.suggest(pSession, pFactory, pProjectCode, null);
	}

	/**
	 * Same, with an explicit locale for the per-entity "done" message below - lot C1 (2026-09-24): {@link
	 * com.on.ebx.factory.ebx.StagingProposalRun} now calls this from a background job running with the SYSTEM
	 * session, whose own {@code getLocale()} would no longer be the launcher's; it passes the launcher's locale
	 * (captured at enqueue time) here instead. Null falls back to {@code pSession}'s own locale, same as before.
	 */
	public static Result suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale) throws OperationException
	{
		return AnomalyLlmSuggester.suggest(pSession, pFactory, pProjectCode, pLocale, null, false);
	}

	/**
	 * Validation desk lot L3: the same pass for ONE business object ({@code pOnlyEntityId}, an occurrence primary key; null = the whole project) and, with {@code
	 * pSameConstantOnly}, for the {@link AttentionRules#SAME_CONSTANT} findings alone - the desk's "Ask for a correction" must not write a second time the
	 * column replacements an earlier full run already proposed.
	 */
	public static Result suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode, final Locale pLocale, final String pOnlyEntityId,
		final boolean pSameConstantOnly) throws OperationException
	{
		final Adaptation project = AnomalyLlmSuggester.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();

		// entities/attributes of the project
		final Map<String, String> entityLabelById = new LinkedHashMap<String, String>();
		final RequestResult entityRows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				// staging-proposal live fix (bug 4): a REJECTED/SUPERSEDED entity (StagingLlmSuggester's own
				// canonical merge writes SUPERSEDED on the rule-pass entities it unifies - MappingScope's own
				// Javadoc explains how that value can exist despite the XSD enum never declaring it) is never
				// worth an agent call - its own staging table is never generated. Same rule
				// MappingScope#eligible now applies for QualityLlmSuggester/CapabilityLlmSuggester, applied
				// directly here since this pass has no MappingScope parameter of its own (always PROPOSAL-like
				// - it only ever runs right after the rule pass, see the class Javadoc).
				final String entityStatus = row.getString(FactoryPaths.StagingEntity.STATUS);
				if ("REJECTED".equals(entityStatus) || "SUPERSEDED".equals(entityStatus))
				{
					continue;
				}
				final String label = row.getString(FactoryPaths.StagingEntity.LABEL);
				entityLabelById.put(row.getOccurrencePrimaryKey().format(),
					label == null || label.trim().isEmpty() ? row.getString(FactoryPaths.StagingEntity.NAME) : label);
			}
		}
		finally
		{
			entityRows.close();
		}

		final Map<String, String> attributeNameById = new HashMap<String, String>();
		final Map<String, String> attributeLabelById = new HashMap<String, String>();
		final Map<String, String> attributeEntityById = new HashMap<String, String>();
		for (final String entityId : entityLabelById.keySet())
		{
			final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(entityId));
			try
			{
				for (Adaptation row = attributeRows.nextAdaptation(); row != null; row = attributeRows.nextAdaptation())
				{
					if (Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY)))
					{
						continue; // technical identity: never a mapping/flag target
					}
					// staging-proposal live fix (bug 4): same exclusion as the entity loop above, for the
					// attribute's own status.
					final String attributeStatus = row.getString(FactoryPaths.StagingAttribute.STATUS);
					if ("REJECTED".equals(attributeStatus) || "SUPERSEDED".equals(attributeStatus))
					{
						continue;
					}
					final String id = row.getOccurrencePrimaryKey().format();
					final String label = row.getString(FactoryPaths.StagingAttribute.LABEL);
					attributeNameById.put(id, row.getString(FactoryPaths.StagingAttribute.NAME));
					attributeLabelById.put(id, label == null || label.trim().isEmpty() ? row.getString(FactoryPaths.StagingAttribute.NAME) : label);
					attributeEntityById.put(id, entityId);
				}
			}
			finally
			{
				attributeRows.close();
			}
		}

		// every non-rejected mapping of the project, joined with its landing column's measured numbers and table
		final AdaptationTable mappingsTable = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final AdaptationTable columnsTable = pFactory.getTable(FactoryPaths.LANDING_COLUMN);
		final AdaptationTable tablesTable = pFactory.getTable(FactoryPaths.LANDING_TABLE);
		final Map<String, Adaptation> tableRowCache = new HashMap<String, Adaptation>();
		final List<AttentionRules.MappingInput> mappingInputs = new ArrayList<AttentionRules.MappingInput>();
		// key "attributeId|landingColumnId" -> the mapping row id, so a flag (which only carries ids) can be
		// traced back to the real AttributeMapping row to write alternativeTo/feed against.
		final Map<String, String> mappingIdByAttributeAndColumn = new HashMap<String, String>();
		final Map<String, String> feedIdByMappingId = new HashMap<String, String>();
		final Map<String, String> mappingRationaleById = new HashMap<String, String>();
		final Set<String> usedColumnIds = new HashSet<String>();
		final Map<String, String> inferredTypeByColumnId = new HashMap<String, String>();
		// staging-proposal live fix (bug 4): TransferFeed status, cached per feed id (see
		// ebx.QualitySuggester#feedStatusOf, same idea).
		final Map<String, String> feedStatusById = new HashMap<String, String>();
		final AdaptationTable feedsTable = pFactory.getTable(FactoryPaths.TRANSFER_FEED);

		for (final String attributeId : attributeNameById.keySet())
		{
			final RequestResult mappingRows = mappingsTable.createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
			try
			{
				for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
				{
					final String status = mappingRow.getString(FactoryPaths.AttributeMapping.STATUS);
					final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
					if (landingColumnId == null || landingColumnId.trim().isEmpty())
					{
						continue;
					}
					// staging-proposal live fix (bug 4): a REJECTED feed is never worth flagging either.
					final String feedId = mappingRow.getString(FactoryPaths.AttributeMapping.FEED);
					if (feedId != null && !feedId.trim().isEmpty())
					{
						String feedStatus = feedStatusById.get(feedId);
						if (feedStatus == null && !feedStatusById.containsKey(feedId))
						{
							final Adaptation feedRow = feedsTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(feedId));
							feedStatus = feedRow == null ? null : feedRow.getString(FactoryPaths.TransferFeed.STATUS);
							feedStatusById.put(feedId, feedStatus);
						}
						if ("REJECTED".equals(feedStatus))
						{
							continue;
						}
					}
					final String mappingId = mappingRow.getOccurrencePrimaryKey().format();
					if (!"REJECTED".equals(status))
					{
						usedColumnIds.add(landingColumnId);
					}
					final Adaptation columnRow = columnsTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(landingColumnId));
					Double fillRate = null;
					Long distinctCount = null;
					Long rowCount = null;
					if (columnRow != null)
					{
						final Object fillRateRaw = columnRow.get(FactoryPaths.LandingColumn.FILL_RATE);
						fillRate = fillRateRaw instanceof Number ? Double.valueOf(((Number) fillRateRaw).doubleValue()) : null;
						final Object distinctRaw = columnRow.get(FactoryPaths.LandingColumn.DISTINCT_COUNT);
						distinctCount = distinctRaw instanceof Number ? Long.valueOf(((Number) distinctRaw).longValue()) : null;
						final String inferredType = columnRow.getString(FactoryPaths.LandingColumn.INFERRED_TYPE);
						if (inferredType != null)
						{
							inferredTypeByColumnId.put(landingColumnId, inferredType);
						}
						final String landingTableId = columnRow.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
						if (landingTableId != null)
						{
							Adaptation tableRow = tableRowCache.get(landingTableId);
							if (tableRow == null && !tableRowCache.containsKey(landingTableId))
							{
								tableRow = tablesTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(landingTableId));
								tableRowCache.put(landingTableId, tableRow);
							}
							if (tableRow != null)
							{
								// staging-proposal live fix: LandingRowCounts.rowCountOf, not the raw
								// sourceRowCount field alone - see its own Javadoc (sourceRowCount stays
								// null for a sample-file source; lastLoadRows does not).
								rowCount = LandingRowCounts.rowCountOf(tableRow);
							}
						}
					}
					final String entityId = attributeEntityById.get(attributeId);
					final boolean alternativeMapping = !AnomalyLlmSuggester.nonNull(mappingRow.getString(FactoryPaths.AttributeMapping.ALTERNATIVE_TO)).isEmpty();
					mappingInputs.add(new AttentionRules.MappingInput(attributeId, landingColumnId, entityId, status, fillRate, distinctCount, rowCount, feedId,
						AnomalyLlmSuggester.pipelineOf(mappingRow), alternativeMapping));
					if (!alternativeMapping)
					{
						// an alternative reads the same column as the mapping it replaces: it must not take that mapping's place in this index
						mappingIdByAttributeAndColumn.put(attributeId + "|" + landingColumnId, mappingId);
					}
					feedIdByMappingId.put(mappingId, mappingRow.getString(FactoryPaths.AttributeMapping.FEED));
					mappingRationaleById.put(mappingId, AnomalyLlmSuggester.nonNull(mappingRow.getString(FactoryPaths.AttributeMapping.RATIONALE)));
				}
			}
			finally
			{
				mappingRows.close();
			}
		}

		final List<AttentionRules.Flag> flags = AttentionRules.detect(mappingInputs);
		int constantCount = 0;
		int emptyCount = 0;
		int duplicateCount = 0;
		int sameConstantCount = 0;
		final Map<String, List<AnomalyLlmPass.FlaggedAttribute>> flaggedByEntity = new LinkedHashMap<String, List<AnomalyLlmPass.FlaggedAttribute>>();
		// lot L3: per entity, per attribute, the sources that write the same constant
		final Map<String, Map<String, List<AttentionRules.Flag>>> sameConstantByEntity = new LinkedHashMap<String, Map<String, List<AttentionRules.Flag>>>();
		for (final AttentionRules.Flag flag : flags)
		{
			if (AttentionRules.DUPLICATE_FIELD.equals(flag.kind))
			{
				duplicateCount++;
				continue;
			}
			if (AttentionRules.SAME_CONSTANT.equals(flag.kind))
			{
				sameConstantCount++;
				Map<String, List<AttentionRules.Flag>> perAttribute = sameConstantByEntity.get(flag.entityId);
				if (perAttribute == null)
				{
					perAttribute = new LinkedHashMap<String, List<AttentionRules.Flag>>();
					sameConstantByEntity.put(flag.entityId, perAttribute);
				}
				List<AttentionRules.Flag> perSource = perAttribute.get(flag.attributeId);
				if (perSource == null)
				{
					perSource = new ArrayList<AttentionRules.Flag>();
					perAttribute.put(flag.attributeId, perSource);
				}
				perSource.add(flag);
				continue;
			}
			if (pSameConstantOnly)
			{
				continue;
			}
			if (AttentionRules.CONSTANT_COLUMN.equals(flag.kind))
			{
				constantCount++;
			}
			else
			{
				emptyCount++;
			}
			final String mappingId = mappingIdByAttributeAndColumn.get(flag.attributeId + "|" + flag.landingColumnId);
			if (mappingId == null)
			{
				continue; // defensive only: every flag came from a row this same method just built
			}
			final Adaptation columnRow = columnsTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(flag.landingColumnId));
			if (columnRow == null)
			{
				continue;
			}
			final String landingTableId = columnRow.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
			final String currentColumnName = columnRow.getString(FactoryPaths.LandingColumn.FIELD_NAME);
			final List<AnomalyLlmPass.UnusedColumn> unused = AnomalyLlmSuggester.unusedColumnsOf(columnsTable, landingTableId, usedColumnIds,
				flag.landingColumnId);
			final AnomalyLlmPass.FlaggedAttribute flagged = new AnomalyLlmPass.FlaggedAttribute(flag.attributeId, mappingId,
				attributeNameById.get(flag.attributeId), attributeLabelById.get(flag.attributeId), flag.kind, currentColumnName, flag.filledRows,
				flag.totalRows, unused);
			List<AnomalyLlmPass.FlaggedAttribute> forEntity = flaggedByEntity.get(flag.entityId);
			if (forEntity == null)
			{
				forEntity = new ArrayList<AnomalyLlmPass.FlaggedAttribute>();
				flaggedByEntity.put(flag.entityId, forEntity);
			}
			forEntity.add(flagged);
		}

		final ObjectNode out = AnomalyLlmSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("constantColumnFlags", constantCount);
		out.put("emptyColumnFlags", emptyCount);
		out.put("duplicateFieldFlags", duplicateCount);
		final ArrayNode entitiesOut = out.putArray("entities");
		int replacementsProposed = 0;
		int entitiesRun = 0;
		final List<ModelAnswers.Answer> answers = new ArrayList<ModelAnswers.Answer>();
		final Set<String> entityIds = new java.util.LinkedHashSet<String>(flaggedByEntity.keySet());
		entityIds.addAll(sameConstantByEntity.keySet());
		out.put("sameConstantFlags", sameConstantCount);
		for (final String entityId : entityIds)
		{
			if (pOnlyEntityId != null && !pOnlyEntityId.equals(entityId))
			{
				continue;
			}
			final List<AnomalyLlmPass.FlaggedAttribute> flaggedAttributes =
				flaggedByEntity.containsKey(entityId) && !pSameConstantOnly ? flaggedByEntity.get(entityId) : new ArrayList<AnomalyLlmPass.FlaggedAttribute>();
			final List<AnomalyLlmPass.SameConstantAttribute> constants = AnomalyLlmSuggester.sameConstants(sameConstantByEntity.get(entityId), mappingIdByAttributeAndColumn,
				mappingRationaleById, attributeNameById, attributeLabelById, columnsTable, tablesTable, pFactory, usedColumnIds, pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING));
			if (flaggedAttributes.isEmpty() && constants.isEmpty())
			{
				continue;
			}
			final ObjectNode entityOut = entitiesOut.addObject();
			entityOut.put("entity", entityLabelById.get(entityId));
			entityOut.put("flagged", flaggedAttributes.size());
			entityOut.put("sameConstant", constants.size());
			entitiesRun++;
			final AnomalyLlmPass.Session llmSession = new AnomalyLlmPass.Session(flaggedAttributes, constants);
			final ToolSet tools;
			try
			{
				tools = ToolSet.build(Collections.<String, ToolSet.Server> emptyMap(), Collections.singletonMap("factory", llmSession.toolGroup()));
			}
			catch (final McpException ex)
			{
				entityOut.put("error", "could not build the agent's tool set: " + ex.getMessage());
				continue;
			}
			final String stepId = AnomalyLlmSuggester.beginStep(pSession, pFactory, projectId, entityId);
			try
			{
				final AgentRuntime.Outcome outcome = AgentRuntime.run(pSession, pFactory, projectId, AnomalyLlmSuggester.PHASE,
					"Review " + (flaggedAttributes.size() + constants.size()) + " flagged attribute(s) of " + entityLabelById.get(entityId) + " for a better source column or constant.",
					AnomalyLlmPass.systemPrompt(), AnomalyLlmPass.userPrompt(entityLabelById.get(entityId), flaggedAttributes, constants), tools);
				entityOut.put("agentRunId", outcome.runId);
				entityOut.put("status", AgentRuntime.statusOf(outcome.status));
				final int written = AnomalyLlmSuggester.write(pSession, pFactory, llmSession, feedIdByMappingId, inferredTypeByColumnId);
				entityOut.put("replacementsProposed", written);
				replacementsProposed += written;
				if ("DONE".equals(AgentRuntime.statusOf(outcome.status)))
				{
					// lot L3b: every source submitted to the model gets its answer - another constant, or none (the model judged the value right)
					final List<String> submitted = new ArrayList<String>();
					for (final AnomalyLlmPass.SameConstantAttribute attribute : constants)
					{
						for (final AnomalyLlmPass.ConstantSource source : attribute.sources)
						{
							submitted.add(source.mappingId);
						}
					}
					final List<String> proposed = new ArrayList<String>();
					for (final AnomalyLlmPass.CreatedConstant created : llmSession.createdConstants())
					{
						proposed.add(created.flaggedMappingId);
					}
					answers.addAll(ModelAnswers.answers(submitted, proposed, outcome.text));
				}
				final Locale doneLocale = pLocale != null ? pLocale : pSession == null || pSession.getLocale() == null ? Locale.ENGLISH : pSession.getLocale();
				PipelineSteps.end(pSession, pFactory, stepId, true, com.on.ebx.factory.wizard.WizardMessages.get(doneLocale, "wizard.step4.anomaly.doneMessage",
					com.on.ebx.factory.wizard.WizardMessages.countWords(doneLocale, written, "wizard.unit.replacementProposed"), outcome.runId), null);
			}
			catch (final OperationException ex)
			{
				entityOut.put("error", ex.getMessage());
				PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), null);
			}
			catch (final RuntimeException | Error ex)
			{
				// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
				StepGuard.endUnexpected(pSession, pFactory, stepId, ex, null);
				throw ex;
			}
		}
		final Result result = new Result(constantCount, emptyCount, duplicateCount, sameConstantCount, replacementsProposed, entitiesRun, out);
		result.answers.addAll(answers);
		return result;
	}

	private static String nonNull(final String pText)
	{
		return pText == null ? "" : pText;
	}

	/** The mapping's pipeline as the loader runs it: the stored one, else the translation of the old transformation/argument pair (a row it cannot express reads as none). */
	private static String pipelineOf(final Adaptation pMappingRow)
	{
		final String pipeline = AnomalyLlmSuggester.nonNull(pMappingRow.getString(FactoryPaths.AttributeMapping.PIPELINE));
		if (!pipeline.trim().isEmpty())
		{
			return pipeline;
		}
		final String transformation = AnomalyLlmSuggester.nonNull(pMappingRow.getString(FactoryPaths.AttributeMapping.TRANSFORMATION));
		if (transformation.isEmpty())
		{
			return "";
		}
		try
		{
			return com.on.ebx.factory.transfer.PipelineCompat.translate(transformation, pMappingRow.getString(FactoryPaths.AttributeMapping.ARGUMENT), "");
		}
		catch (final RuntimeException ex)
		{
			return "";
		}
	}

	/**
	 * Lot L3: the facts of the {@link AttentionRules#SAME_CONSTANT} flags of one business object, per attribute and per source - the constant, the source system, the mapping's own
	 * rationale, the landing row count and the other columns of its landing table, all MEASURED or read; no value is chosen here. A source that already has a PROPOSED alternative is left
	 * out (asking again must not write a second proposal); an attribute with no source left is skipped.
	 */
	private static List<AnomalyLlmPass.SameConstantAttribute> sameConstants(final Map<String, List<AttentionRules.Flag>> pPerAttribute,
		final Map<String, String> pMappingIdByAttributeAndColumn, final Map<String, String> pRationaleByMappingId, final Map<String, String> pAttributeNameById,
		final Map<String, String> pAttributeLabelById, final AdaptationTable pColumnsTable, final AdaptationTable pTablesTable, final Adaptation pFactory,
		final Set<String> pUsedColumnIds, final AdaptationTable pMappingsTable)
	{
		final List<AnomalyLlmPass.SameConstantAttribute> out = new ArrayList<AnomalyLlmPass.SameConstantAttribute>();
		if (pPerAttribute == null)
		{
			return out;
		}
		for (final Map.Entry<String, List<AttentionRules.Flag>> entry : pPerAttribute.entrySet())
		{
			final List<AnomalyLlmPass.ConstantSource> sources = new ArrayList<AnomalyLlmPass.ConstantSource>();
			String value = "";
			for (final AttentionRules.Flag flag : entry.getValue())
			{
				value = flag.constantValue;
				final String mappingId = pMappingIdByAttributeAndColumn.get(flag.attributeId + "|" + flag.landingColumnId);
				final Adaptation columnRow = pColumnsTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(flag.landingColumnId));
				if (mappingId == null || columnRow == null || AnomalyLlmSuggester.hasProposedAlternative(pMappingsTable, mappingId))
				{
					continue;
				}
				final String landingTableId = columnRow.getString(FactoryPaths.LandingColumn.LANDING_TABLE);
				final Adaptation tableRow = landingTableId == null ? null : pTablesTable.lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(landingTableId));
				String sourceSystem = "?";
				Long rowCount = null;
				if (tableRow != null)
				{
					rowCount = LandingRowCounts.rowCountOf(tableRow);
					final String projectSourceId = tableRow.getString(FactoryPaths.LandingTable.PROJECT_SOURCE);
					final Adaptation projectSource =
						projectSourceId == null ? null : pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(com.onwbp.adaptation.PrimaryKey.parseString(projectSourceId));
					if (projectSource != null)
					{
						sourceSystem = projectSource.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
					}
				}
				sources.add(new AnomalyLlmPass.ConstantSource(mappingId, flag.landingColumnId, sourceSystem, columnRow.getString(FactoryPaths.LandingColumn.FIELD_NAME),
					pRationaleByMappingId.get(mappingId), rowCount, AnomalyLlmSuggester.unusedColumnsOf(pColumnsTable, landingTableId, pUsedColumnIds, flag.landingColumnId)));
			}
			if (!sources.isEmpty())
			{
				out.add(new AnomalyLlmPass.SameConstantAttribute(entry.getKey(), pAttributeNameById.get(entry.getKey()), pAttributeLabelById.get(entry.getKey()), value, sources));
			}
		}
		return out;
	}

	/** True when a PROPOSED mapping already names {@code pMappingId} as the one it replaces. */
	private static boolean hasProposedAlternative(final AdaptationTable pMappingsTable, final String pMappingId)
	{
		final RequestResult rows = pMappingsTable.createRequestResult("./alternativeTo=" + CatalogReader.quote(pMappingId) + " and ./status='PROPOSED'");
		try
		{
			return rows.nextAdaptation() != null;
		}
		finally
		{
			rows.close();
		}
	}

	/** Every OTHER, still-unused column of the same landing table, with its own measured shape - capped, and never one with a null/zero fill rate (nothing to measure a proposal against). */
	private static List<AnomalyLlmPass.UnusedColumn> unusedColumnsOf(final AdaptationTable pColumnsTable, final String pLandingTableId,
		final Set<String> pUsedColumnIds, final String pExcludeColumnId)
	{
		final List<AnomalyLlmPass.UnusedColumn> out = new ArrayList<AnomalyLlmPass.UnusedColumn>();
		if (pLandingTableId == null)
		{
			return out;
		}
		final RequestResult rows = pColumnsTable.createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null && out.size() < AnomalyLlmSuggester.MAX_UNUSED_COLUMNS_PER_FLAG;
				row = rows.nextAdaptation())
			{
				final String columnId = row.getOccurrencePrimaryKey().format();
				if (columnId.equals(pExcludeColumnId) || pUsedColumnIds.contains(columnId))
				{
					continue;
				}
				final Object fillRateRaw = row.get(FactoryPaths.LandingColumn.FILL_RATE);
				final Double fillRate = fillRateRaw instanceof Number ? Double.valueOf(((Number) fillRateRaw).doubleValue()) : null;
				if (fillRate == null || fillRate.doubleValue() <= 0.0)
				{
					continue; // "measure the proposal before writing it": an unused column with nothing filled is never a candidate
				}
				final Object distinctRaw = row.get(FactoryPaths.LandingColumn.DISTINCT_COUNT);
				final Long distinctCount = distinctRaw instanceof Number ? Long.valueOf(((Number) distinctRaw).longValue()) : null;
				final Object maxLengthRaw = row.get(FactoryPaths.LandingColumn.MAX_LENGTH);
				final Integer maxLength = maxLengthRaw instanceof Number ? Integer.valueOf(((Number) maxLengthRaw).intValue()) : null;
				out.add(new AnomalyLlmPass.UnusedColumn(columnId, row.getString(FactoryPaths.LandingColumn.FIELD_NAME), fillRate, distinctCount, maxLength,
					java.util.Collections.<String> emptyList()));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static String beginStep(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityId)
		throws OperationException
	{
		return PipelineSteps.begin(pSession, pFactory, pProjectId, AnomalyLlmSuggester.STEP, pEntityId);
	}

	/**
	 * Writes every {@link AnomalyLlmPass.CreatedReplacement} the run accumulated as a new {@code
	 * AttributeMapping} row (origin {@code LLM}, status {@code PROPOSED}, {@code alternativeTo} the flagged
	 * mapping) - "measure the proposal before writing it" (task brief) is already enforced by construction:
	 * {@link AnomalyLlmPass.Session#proposeReplacement} only ever accepts a column from the list {@code
	 * unusedColumnsOf} built, which already excludes a null/zero fill rate and the flagged column itself.
	 */
	private static int write(final Session pSession, final Adaptation pFactory, final AnomalyLlmPass.Session pLlmSession,
		final Map<String, String> pFeedIdByMappingId, final Map<String, String> pInferredTypeByColumnId) throws OperationException
	{
		final List<AnomalyLlmPass.CreatedReplacement> created = pLlmSession.created();
		final List<AnomalyLlmPass.CreatedConstant> createdConstants = pLlmSession.createdConstants();
		if (created.isEmpty() && createdConstants.isEmpty())
		{
			return 0;
		}
		final AdaptationTable mappingsTable = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final int[] count = { 0 };
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final AnomalyLlmPass.CreatedReplacement replacement : created)
				{
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(mappingsTable);
					value.setValue(replacement.flaggedAttributeId, FactoryPaths.AttributeMapping.ATTRIBUTE);
					value.setValue(replacement.replacementColumnId, FactoryPaths.AttributeMapping.LANDING_COLUMN);
					final String feedId = pFeedIdByMappingId.get(replacement.flaggedMappingId);
					if (feedId != null)
					{
						value.setValue(feedId, FactoryPaths.AttributeMapping.FEED);
					}
					final StagingRules.MappingProposal typeDriven = StagingRules.typeDrivenMapping(replacement.replacementColumnId,
						pInferredTypeByColumnId.get(replacement.replacementColumnId));
					value.setValue(typeDriven.transformation, FactoryPaths.AttributeMapping.TRANSFORMATION);
					if (typeDriven.argument != null)
					{
						value.setValue(typeDriven.argument, FactoryPaths.AttributeMapping.ARGUMENT);
					}
					value.setValue("LLM", FactoryPaths.AttributeMapping.ORIGIN);
					value.setValue(Integer.valueOf(replacement.confidence), FactoryPaths.AttributeMapping.CONFIDENCE);
					value.setValue(TextLimits.truncate(replacement.rationale, AnomalyLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.AttributeMapping.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.AttributeMapping.STATUS);
					value.setValue(replacement.flaggedMappingId, FactoryPaths.AttributeMapping.ALTERNATIVE_TO);
					pContext.doCreateOccurrence(value, mappingsTable);
					count[0]++;
				}
				// lot L3: the model's constant for one source - same column and feed as the mapping it replaces, a constant() pipeline, PROPOSED, alternativeTo the original
				for (final AnomalyLlmPass.CreatedConstant constant : createdConstants)
				{
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(mappingsTable);
					value.setValue(constant.flaggedAttributeId, FactoryPaths.AttributeMapping.ATTRIBUTE);
					value.setValue(constant.landingColumnId, FactoryPaths.AttributeMapping.LANDING_COLUMN);
					final String feedId = pFeedIdByMappingId.get(constant.flaggedMappingId);
					if (feedId != null)
					{
						value.setValue(feedId, FactoryPaths.AttributeMapping.FEED);
					}
					value.setValue("NONE", FactoryPaths.AttributeMapping.TRANSFORMATION);
					value.setValue(AnomalyLlmSuggester.constantPipeline(constant.value), FactoryPaths.AttributeMapping.PIPELINE);
					value.setValue("LLM", FactoryPaths.AttributeMapping.ORIGIN);
					value.setValue(Integer.valueOf(constant.confidence), FactoryPaths.AttributeMapping.CONFIDENCE);
					value.setValue(TextLimits.truncate(constant.rationale, AnomalyLlmSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.AttributeMapping.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.AttributeMapping.STATUS);
					value.setValue(constant.flaggedMappingId, FactoryPaths.AttributeMapping.ALTERNATIVE_TO);
					pContext.doCreateOccurrence(value, mappingsTable);
					count[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveAnomalyMappings", "Writing the anomaly-pass replacement mappings failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		return count[0];
	}

	/** {@code constant(VALUE)} the way the other passes write it; a value with a space or a separator is quoted. The pass accepted only a plain literal, so no quote can be inside. */
	static String constantPipeline(final String pValue)
	{
		return pValue.matches("[A-Za-z0-9_.-]+") ? "constant(" + pValue + ")" : "constant('" + pValue + "')";
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
}
