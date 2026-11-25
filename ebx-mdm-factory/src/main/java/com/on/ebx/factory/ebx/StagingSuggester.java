package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.staging.MappingFollowRule;
import com.on.ebx.factory.staging.LandingScope;
import com.on.ebx.factory.staging.StagingRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Runs {@link StagingRules} - the deterministic, rule-only first pass - over every landing table of a
 * GREENFIELD project and writes its proposals as {@code StagingEntity}/{@code StagingAttribute}/{@code
 * AttributeMapping} rows, origin {@code RULE}, status {@code PROPOSED}. A project whose mode is {@code
 * TARGET_EXISTS} is refused: with an existing master model the staging takes its shape and the
 * suggestions are mappings only, a later lot.
 * <p>
 * Idempotent like {@link LandingPlanner}: an entity that already exists for the project (matched by name)
 * is left alone together with its attributes and mappings, whatever their status; only missing entities
 * are added. Records a {@code PipelineStep STAGING_SUGGEST} (empty scope: the whole project) through
 * {@link PipelineSteps}, without the {@code isDone} skip other steps use - a relaunch here is legitimate
 * (a new source added to the project, or new landing tables generated, should be picked up). Acts with the
 * rights of the session given to {@link #suggest}: no MCP call, no agent account, unlike the landing
 * generator - everything this writes stays inside the factory's own dataset.
 */
public final class StagingSuggester
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String STEP_STAGING_SUGGEST = "STAGING_SUGGEST";
	/** {@code StagingEntity.rationale} / {@code StagingAttribute.rationale} / {@code AttributeMapping.rationale}: all three are {@code maxLen=2000} in the XSD. */
	private static final int RATIONALE_MAX_LENGTH = 2000;

	private StagingSuggester()
	{
	}

	public static ObjectNode suggest(final Session pSession, final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = StagingSuggester.findProject(pFactory, pProjectCode);
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
			throw EngineRefusal.coded("conflict.stagingTargetExists", "Project " + pProjectCode + " is mode " + mode
				+ ": the staging suggestion for an existing master model (TARGET_EXISTS) is mappings onto that model, a later lot. GREENFIELD only for now.", pProjectCode, mode);
		}

		final List<StagingRules.LandingTableInfo> tables = new ArrayList<StagingRules.LandingTableInfo>();
		final List<StagingRules.RelationInfo> relations = new ArrayList<StagingRules.RelationInfo>();
		int sourceCount = 0;
		final int[] outOfScope = new int[1];
		final RequestResult sourceRows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation source = sourceRows.nextAdaptation(); source != null; source = sourceRows.nextAdaptation())
			{
				sourceCount++;
				final String sourceId = source.getOccurrencePrimaryKey().format();
				final String sourceSystem = source.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				final Map<String, String> tableNameById = new HashMap<String, String>();
				for (final StagingRules.LandingTableInfo table : StagingSuggester.readTables(pFactory, sourceId, sourceSystem, tableNameById, outOfScope))
				{
					tables.add(table);
				}
				relations.addAll(StagingSuggester.readRelations(pFactory, sourceId, sourceSystem, tableNameById));
			}
		}
		finally
		{
			sourceRows.close();
		}
		if (tables.isEmpty())
		{
			throw EngineRefusal.coded("missing.noLandingTable", "Project " + pProjectCode + " has no landing table yet: plan and generate the landing of at least one source first.", pProjectCode);
		}

		final int lengthMargin = StagingSuggester.readLengthMargin(pFactory);
		final StagingRules.Result ruled = StagingRules.propose(tables, relations, lengthMargin);

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, StagingSuggester.STEP_STAGING_SUGGEST, "");
		final ObjectNode out = StagingSuggester.MAPPER.createObjectNode();
		out.put("project", pProjectCode);
		out.put("sources", sourceCount);
		out.put("landingTables", tables.size());
		out.put("landingTablesOutOfScope", outOfScope[0]);
		try
		{
			StagingSuggester.write(pSession, pFactory, projectId, ruled, out);
			// same catch-up spirit as the passes just above (primaryKeysBackfilled, mappingsAligned, ...): an
			// LLM-origin canonical entity written before memberEntities existed only ever gets it backfilled by
			// re-running the (paid, admin-only) language-model pass - MemberEntitiesBackfill is cheap and safe
			// enough to just run here too, on every RULE-pass call, so it never again depends on that happening
			final MemberEntitiesBackfill.Result memberBackfill = MemberEntitiesBackfill.run(pSession, pFactory, projectId);
			out.put("memberEntitiesBackfilled", memberBackfill.backfilled);
			// target-mapping lot 1 (docs/TARGET-MAPPING.md sec 6): a pre-lot-1 AttributeMapping row has no feed
			// yet - same catch-up spirit and same idempotent-cheap-safe-to-rerun posture as MemberEntitiesBackfill
			final FeedBackfill.Result feedBackfill = FeedBackfill.run(pSession, pFactory, projectId);
			out.put("feedsBackfilled", feedBackfill.feedsCreated);
			out.put("mappingFeedsBackfilled", feedBackfill.mappingsBackfilled);
			// pass F: a feed follows its entity (MappingFollowRule), same "no separate decision" posture as pass E's
			// mapping-follows-attribute sweep just below - a feed accepted/rejected through the review screen before
			// ProposalReviews.decide's own cascade existed, or one FeedBackfill just created for an entity already decided
			out.put("feedsAligned", StagingSuggester.alignFeedStatuses(pSession, pFactory, projectId));
			final ArrayNode notesNode = out.putArray("notes");
			for (final String note : ruled.notes)
			{
				notesNode.add(note);
			}
			final ArrayNode skippedNode = out.putArray("skippedRelations");
			for (final StagingRules.SkippedRelation skip : ruled.skippedRelations)
			{
				final ObjectNode line = skippedNode.addObject();
				line.put("fromTable", skip.fromTable);
				line.put("fromColumns", String.valueOf(skip.fromColumns));
				line.put("toTable", skip.toTable);
				line.put("reason", skip.reason);
			}
			final String message = out.path("entitiesCreated").asInt(0) + " entity(ies) created, " + out.path("entitiesSkipped").asInt(0)
				+ " already existed (left alone).";
			PipelineSteps.end(pSession, pFactory, stepId, true, message, out.toString());
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
				out.add(new StagingRules.LandingTableInfo(id, pSourceSystem, name, rowCount, StagingSuggester.readColumns(pFactory, id)));
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
		final List<Adaptation> columnRows = new ArrayList<Adaptation>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.LANDING_COLUMN).createRequestResult("./landingTable=" + CatalogReader.quote(pLandingTableId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				columnRows.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(columnRows, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Integer left = (Integer) pLeft.get(FactoryPaths.LandingColumn.POSITION);
				final Integer right = (Integer) pRight.get(FactoryPaths.LandingColumn.POSITION);
				final int l = left == null ? Integer.MAX_VALUE : left.intValue();
				final int r = right == null ? Integer.MAX_VALUE : right.intValue();
				return l != r ? Integer.compare(l, r) : 0;
			}
		});

		final List<StagingRules.LandingColumnInfo> out = new ArrayList<StagingRules.LandingColumnInfo>();
		for (final Adaptation row : columnRows)
		{
			final String id = row.getOccurrencePrimaryKey().format();
			final String sourceColumn = row.getString(FactoryPaths.LandingColumn.SOURCE_COLUMN);
			final String fieldName = row.getString(FactoryPaths.LandingColumn.FIELD_NAME);
			final String inferredType = row.getString(FactoryPaths.LandingColumn.INFERRED_TYPE);
			final boolean sourceKey = Boolean.TRUE.equals(row.get(FactoryPaths.LandingColumn.SOURCE_KEY));
			final Object rawFillRate = row.get(FactoryPaths.LandingColumn.FILL_RATE);
			final BigDecimal fillRate = rawFillRate instanceof BigDecimal ? (BigDecimal) rawFillRate : null;
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
			out.add(new StagingRules.LandingColumnInfo(id, sourceColumn, fieldName, inferredType, sourceKey, fillRate, distinctCount, maxLength, tags));
		}
		return out;
	}

	/** {@code LandingRelation} rows of status {@code PROPOSED} or {@code ACCEPTED} only - never {@code REJECTED}, per rule 6. */
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

	private static Adaptation findEntity(final Adaptation pFactory, final String pProjectId, final String pName)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ENTITY)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./name=" + CatalogReader.quote(pName));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	private static int readLengthMargin(final Adaptation pFactory) throws OperationException
	{
		final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (settings == null)
		{
			throw EngineRefusal.coded("missing.settings", "MDM Factory settings: the DEFAULT row is missing");
		}
		final Object margin = settings.get(FactoryPaths.Settings.LENGTH_MARGIN);
		return margin instanceof Number ? ((Number) margin).intValue() : 50;
	}

	// ------------------------------------------------------------------------------------------------------- writing

	/**
	 * Three passes in one procedure: (A) every entity that does not already exist (by name, within the
	 * project) is created bare, so every entity - new or pre-existing - has a known id before any attribute
	 * is written; (B) attributes and mappings are written only for entities created in pass A ({@code
	 * newEntityNames}), resolving {@code referenceEntity} (and {@code fromRelation}) against the ids pass A
	 * collected either way, and {@code primaryKey}/{@code businessKey} written as the rules now propose them
	 * (technical key: {@code primaryKey=true}, {@code businessKey=false}); (C) for every entity pass A found
	 * already existing, {@link StagingRules#backfillFromRelation} decides which of its EXISTING attribute
	 * rows should now get {@code fromRelation} linked, {@link StagingRules#backfillPrimaryKey} which of its
	 * existing {@code sourceSystem}/{@code sourceId} rows should now get {@code primaryKey}/{@code
	 * businessKey} corrected, and {@link StagingRules#alignTechnicalKeyStatus} which of its technical-key
	 * rows should have their {@code status} aligned to what the entity's own CURRENT status implies - the
	 * fix for the exact live case a coordinator review found on project {@code Companies} (2026-09-20):
	 * {@code Account}/{@code Contact} accepted through the review screen before {@code ProposalReviews.decide}
	 * cascaded this automatically, leaving {@code sourceSystem}/{@code sourceId} stuck {@code PROPOSED} and
	 * {@code staging/generate} refusing for the wrong reason; (D) every OTHER entity of the project -
	 * whatever its origin, in particular an LLM/canonical entity that never goes through passes A-C at all
	 * because it has no landing table of its own - that {@link StagingRules#isMissingTechnicalKey} says has
	 * no {@code primaryKey} attribute ROW yet gets the two technical-key attributes created for it (round 4's
	 * live case: {@code Company}, accepted by the reviewer, created by the first LLM run before this class
	 * proposed a technical key on every entity); (E) EVERY {@code StagingAttribute} of the WHOLE project - not
	 * just what passes A-D touched this run - has its {@code AttributeMapping} rows checked against {@link
	 * StagingRules#alignMappingStatus}: a mapping follows its attribute ({@link
	 * com.on.ebx.factory.staging.MappingFollowRule}, the same "no separate decision" posture as the technical
	 * key), so a mapping still {@code PROPOSED} (origin {@code RULE} or {@code LLM}) whose attribute is now
	 * {@code ACCEPTED} or {@code APPLIED} becomes {@code ACCEPTED} - the fix for the round-4 live case: 62
	 * {@code AttributeMapping} rows stuck {@code PROPOSED} while every one of their attributes was already
	 * {@code APPLIED}, and {@code staging/load} refusing "no staging entity with an accepted, mapped attribute"
	 * as a result. Otherwise an entity that already existed is left alone, matching {@link LandingPlanner}'s
	 * own idempotency rule.
	 */
	private static void write(final Session pSession, final Adaptation pFactory, final String pProjectId, final StagingRules.Result pResult, final ObjectNode pOut)
		throws OperationException
	{
		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final AdaptationTable attributesTable = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE);
		final AdaptationTable mappingsTable = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING);
		final AdaptationTable feedsTable = pFactory.getTable(FactoryPaths.TRANSFER_FEED);
		final ArrayNode entitiesNode = pOut.putArray("entities");
		// entities created, entities skipped (already existed), attributes created, mappings created, relations backfilled,
		// primary keys backfilled, technical keys aligned, technical key ATTRIBUTES created (pass D), mappings ALIGNED (pass E),
		// root feeds created (pass B, target-mapping lot 1)
		final int[] counts = new int[10];

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Map<String, String> entityIdByName = new HashMap<String, String>();
				final Set<String> newEntityNames = new HashSet<String>();

				// pass A: make sure every entity name exists (created bare, or already there), so referenceEntity
				// can be resolved in pass B whichever order the entities were proposed in
				for (final StagingRules.EntityProposal entity : pResult.entities)
				{
					final Adaptation existing = StagingSuggester.findEntity(pFactory, pProjectId, entity.name);
					if (existing != null)
					{
						entityIdByName.put(entity.name, existing.getOccurrencePrimaryKey().format());
						counts[1]++;
						final ObjectNode line = entitiesNode.addObject();
						line.put("name", entity.name);
						line.put("status", "ALREADY_EXISTS");
						continue;
					}
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(entitiesTable);
					value.setValue(pProjectId, FactoryPaths.StagingEntity.PROJECT);
					value.setValue(entity.name, FactoryPaths.StagingEntity.NAME);
					value.setValue(entity.label, FactoryPaths.StagingEntity.LABEL);
					value.setValue("RULE", FactoryPaths.StagingEntity.ORIGIN);
					value.setValue(Integer.valueOf(entity.confidence), FactoryPaths.StagingEntity.CONFIDENCE);
					value.setValue(TextLimits.truncate(entity.rationale, StagingSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.StagingEntity.RATIONALE);
					value.setValue("PROPOSED", FactoryPaths.StagingEntity.STATUS);
					final String id = pContext.doCreateOccurrence(value, entitiesTable).getOccurrencePrimaryKey().format();
					entityIdByName.put(entity.name, id);
					newEntityNames.add(entity.name);
					counts[0]++;
				}

				// pass B: attributes and mappings, only for entities this run actually created
				for (final StagingRules.EntityProposal entity : pResult.entities)
				{
					if (!newEntityNames.contains(entity.name))
					{
						continue;
					}
					final String entityId = entityIdByName.get(entity.name);
					// target-mapping lot 1 (docs/TARGET-MAPPING.md sec 4.1): one root TransferFeed per proposed
					// entity, written alongside it - "the rule pass still proposes one entity per landing table",
					// now made explicit as a feed rather than left implicit in (entity, landing table) grouping.
					// entity.landingTableId is StagingRules.EntityProposal's own bookkeeping field (never written
					// anywhere before this lot) - exactly the LandingTable this entity/feed reads.
					final ValueContextForUpdate feedValue = pContext.getContextForNewOccurrence(feedsTable);
					feedValue.setValue(entityId, FactoryPaths.TransferFeed.ENTITY);
					feedValue.setValue(entity.landingTableId, FactoryPaths.TransferFeed.LANDING_TABLE);
					feedValue.setValue("RULE", FactoryPaths.TransferFeed.ORIGIN);
					feedValue.setValue(Integer.valueOf(entity.confidence), FactoryPaths.TransferFeed.CONFIDENCE);
					feedValue.setValue(TextLimits.truncate(entity.rationale, StagingSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.TransferFeed.RATIONALE);
					feedValue.setValue("PROPOSED", FactoryPaths.TransferFeed.STATUS);
					final String feedId = pContext.doCreateOccurrence(feedValue, feedsTable).getOccurrencePrimaryKey().format();
					counts[9]++;

					int attributeCount = 0;
					int mappingCount = 0;
					for (final StagingRules.AttributeProposal attribute : entity.attributes)
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
						attributeValue.setValue(Boolean.valueOf(attribute.primaryKey), FactoryPaths.StagingAttribute.PRIMARY_KEY);
						attributeValue.setValue(Boolean.valueOf(attribute.enumerated), FactoryPaths.StagingAttribute.ENUMERATED);
						if (attribute.referenceEntity != null)
						{
							final String referenceId = entityIdByName.get(attribute.referenceEntity);
							// every entity name StagingRules can produce was created (or found) in pass A, so this is
							// always resolved; left unset defensively if it somehow is not, rather than failing the whole run
							if (referenceId != null)
							{
								attributeValue.setValue(referenceId, FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
							}
						}
						if (attribute.fromRelationId != null)
						{
							attributeValue.setValue(attribute.fromRelationId, FactoryPaths.StagingAttribute.FROM_RELATION);
						}
						attributeValue.setValue("RULE", FactoryPaths.StagingAttribute.ORIGIN);
						attributeValue.setValue(Integer.valueOf(attribute.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
						attributeValue.setValue(TextLimits.truncate(attribute.rationale, StagingSuggester.RATIONALE_MAX_LENGTH), FactoryPaths.StagingAttribute.RATIONALE);
						attributeValue.setValue("PROPOSED", FactoryPaths.StagingAttribute.STATUS);
						final String attributeId = pContext.doCreateOccurrence(attributeValue, attributesTable).getOccurrencePrimaryKey().format();
						attributeCount++;

						// rule 2: sourceSystem/sourceId carry no mapping - AttributeMapping.landingColumn is mandatory
						if (attribute.mapping != null)
						{
							final ValueContextForUpdate mappingValue = pContext.getContextForNewOccurrence(mappingsTable);
							mappingValue.setValue(attributeId, FactoryPaths.AttributeMapping.ATTRIBUTE);
							mappingValue.setValue(attribute.mapping.landingColumnId, FactoryPaths.AttributeMapping.LANDING_COLUMN);
							mappingValue.setValue(feedId, FactoryPaths.AttributeMapping.FEED);
							mappingValue.setValue(attribute.mapping.transformation, FactoryPaths.AttributeMapping.TRANSFORMATION);
							if (attribute.mapping.argument != null)
							{
								mappingValue.setValue(attribute.mapping.argument, FactoryPaths.AttributeMapping.ARGUMENT);
							}
							mappingValue.setValue("RULE", FactoryPaths.AttributeMapping.ORIGIN);
							mappingValue.setValue(Integer.valueOf(attribute.mapping.confidence), FactoryPaths.AttributeMapping.CONFIDENCE);
							mappingValue.setValue(TextLimits.truncate(attribute.mapping.rationale, StagingSuggester.RATIONALE_MAX_LENGTH),
								FactoryPaths.AttributeMapping.RATIONALE);
							mappingValue.setValue("PROPOSED", FactoryPaths.AttributeMapping.STATUS);
							pContext.doCreateOccurrence(mappingValue, mappingsTable);
							mappingCount++;
						}
					}
					counts[2] += attributeCount;
					counts[3] += mappingCount;
					final ObjectNode line = entitiesNode.addObject();
					line.put("name", entity.name);
					line.put("status", "CREATED");
					line.put("attributes", attributeCount);
					line.put("mappings", mappingCount);
				}

				// pass C: fromRelation and primaryKey backfill, only for entities pass A found already existing (a newly created entity's
				// attributes already got both written in pass B - see StagingRules.backfillFromRelation/backfillPrimaryKey's own Javadoc)
				for (final StagingRules.EntityProposal entity : pResult.entities)
				{
					if (newEntityNames.contains(entity.name))
					{
						continue;
					}
					final String entityId = entityIdByName.get(entity.name);
					final List<StagingRules.ExistingAttribute> existing = StagingSuggester.readExistingAttributes(pFactory, entityId);
					final List<StagingRules.Backfill> backfills = StagingRules.backfillFromRelation(existing, entity.attributes);
					for (final StagingRules.Backfill backfill : backfills)
					{
						final Adaptation attributeRow = attributesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(backfill.attributeId));
						if (attributeRow == null)
						{
							continue;
						}
						// ONLY fromRelation is set here - pContext.getContext reads the record's current values, and setValue touches
						// just this one field, so nothing else about the row (in particular its status, whatever it is) changes
						final ValueContextForUpdate backfillValue = pContext.getContext(attributeRow.getAdaptationName());
						backfillValue.setValue(backfill.relationId, FactoryPaths.StagingAttribute.FROM_RELATION);
						pContext.doModifyContent(attributeRow, backfillValue);
						counts[4]++;
					}
					final List<StagingRules.PrimaryKeyBackfill> keyBackfills = StagingRules.backfillPrimaryKey(existing);
					for (final StagingRules.PrimaryKeyBackfill backfill : keyBackfills)
					{
						final Adaptation attributeRow = attributesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(backfill.attributeId));
						if (attributeRow == null)
						{
							continue;
						}
						// primaryKey=true AND businessKey=false, nothing else: see StagingRules.backfillPrimaryKey's own Javadoc for why
						// businessKey must also be corrected here, not just completed
						final ValueContextForUpdate backfillValue = pContext.getContext(attributeRow.getAdaptationName());
						backfillValue.setValue(Boolean.TRUE, FactoryPaths.StagingAttribute.PRIMARY_KEY);
						backfillValue.setValue(Boolean.FALSE, FactoryPaths.StagingAttribute.BUSINESS_KEY);
						pContext.doModifyContent(attributeRow, backfillValue);
						counts[5]++;
					}

					// keep the rows coherent so the screens do not lie (coordinator review, 2026-09-20, live refusal on
					// Companies: Account/Contact accepted before ProposalReviews.decide cascaded this automatically,
					// leaving sourceSystem/sourceId stuck PROPOSED) - align every existing technical-key attribute's
					// status to what its entity's CURRENT status implies (TechnicalKeyRule.cascadedStatus)
					final Adaptation entityRow = entitiesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(entityId));
					final String entityStatus = entityRow == null ? null : entityRow.getString(FactoryPaths.StagingEntity.STATUS);
					final List<StagingRules.TechnicalKeyStatusBackfill> keyStatusBackfills = StagingRules.alignTechnicalKeyStatus(entityStatus, existing);
					for (final StagingRules.TechnicalKeyStatusBackfill backfill : keyStatusBackfills)
					{
						final Adaptation attributeRow = attributesTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(backfill.attributeId));
						if (attributeRow == null)
						{
							continue;
						}
						// ONLY status is set here, to the target StagingRules.alignTechnicalKeyStatus already decided
						final ValueContextForUpdate backfillValue = pContext.getContext(attributeRow.getAdaptationName());
						backfillValue.setValue(backfill.targetStatus, FactoryPaths.StagingAttribute.STATUS);
						pContext.doModifyContent(attributeRow, backfillValue);
						counts[6]++;
					}
				}

				// pass D: an entity of the project this class never even looks at through pResult.entities (any origin but
				// RULE, or a RULE entity whose landing table no longer matches any current proposal) that has no primaryKey
				// attribute ROW AT ALL yet - StagingRules.isMissingTechnicalKey's own Javadoc has the live case (Company,
				// project Companies, 2026-09-20). Scoped to names NOT in entityIdByName (pass A's own domain, RULE entities
				// new or pre-existing) so this never overlaps backfillPrimaryKey above: that one only ever corrects a row
				// that already exists, this one only ever fires when none does, and the two domains are disjoint by name.
				final RequestResult otherEntityRows = entitiesTable.createRequestResult("./project=" + CatalogReader.quote(pProjectId));
				try
				{
					for (Adaptation otherEntityRow = otherEntityRows.nextAdaptation(); otherEntityRow != null; otherEntityRow = otherEntityRows.nextAdaptation())
					{
						final String name = otherEntityRow.getString(FactoryPaths.StagingEntity.NAME);
						if (entityIdByName.containsKey(name))
						{
							continue;
						}
						final String otherEntityId = otherEntityRow.getOccurrencePrimaryKey().format();
						final List<StagingRules.ExistingAttribute> otherExisting = StagingSuggester.readExistingAttributes(pFactory, otherEntityId);
						if (!StagingRules.isMissingTechnicalKey(otherExisting))
						{
							continue;
						}
						final String entityOrigin = otherEntityRow.getString(FactoryPaths.StagingEntity.ORIGIN);
						final String targetStatus = StagingRules.technicalKeyBackfillStatus(otherEntityRow.getString(FactoryPaths.StagingEntity.STATUS));
						for (final StagingRules.AttributeProposal attribute : StagingRules.technicalKeyBackfillProposals())
						{
							final ValueContextForUpdate attributeValue = pContext.getContextForNewOccurrence(attributesTable);
							attributeValue.setValue(otherEntityId, FactoryPaths.StagingAttribute.ENTITY);
							attributeValue.setValue(attribute.name, FactoryPaths.StagingAttribute.NAME);
							attributeValue.setValue(attribute.label, FactoryPaths.StagingAttribute.LABEL);
							attributeValue.setValue(attribute.dataType, FactoryPaths.StagingAttribute.DATA_TYPE);
							if (attribute.maxLength != null)
							{
								attributeValue.setValue(attribute.maxLength, FactoryPaths.StagingAttribute.MAX_LENGTH);
							}
							attributeValue.setValue(Boolean.valueOf(attribute.mandatory), FactoryPaths.StagingAttribute.MANDATORY);
							attributeValue.setValue(Boolean.valueOf(attribute.businessKey), FactoryPaths.StagingAttribute.BUSINESS_KEY);
							attributeValue.setValue(Boolean.valueOf(attribute.primaryKey), FactoryPaths.StagingAttribute.PRIMARY_KEY);
							attributeValue.setValue(Boolean.valueOf(attribute.enumerated), FactoryPaths.StagingAttribute.ENUMERATED);
							attributeValue.setValue(entityOrigin, FactoryPaths.StagingAttribute.ORIGIN);
							attributeValue.setValue(Integer.valueOf(attribute.confidence), FactoryPaths.StagingAttribute.CONFIDENCE);
							attributeValue.setValue(TextLimits.truncate(attribute.rationale, StagingSuggester.RATIONALE_MAX_LENGTH),
								FactoryPaths.StagingAttribute.RATIONALE);
							attributeValue.setValue(targetStatus, FactoryPaths.StagingAttribute.STATUS);
							pContext.doCreateOccurrence(attributeValue, attributesTable);
							counts[7]++;
						}
					}
				}
				finally
				{
					otherEntityRows.close();
				}

				// pass E: a mapping follows its attribute (MappingFollowRule) - independent of which pass created or touched
				// the entity: an attribute's mappings can fall out of sync at any point (accepted through the review screen
				// before this rule existed, or an attribute just backfilled by pass D above), regardless of the entity's own
				// origin. Scans every StagingAttribute of the WHOLE project, not just what this run's passes A-D touched -
				// the round-4 live case (project Companies, 2026-09-20): 62 AttributeMapping rows still PROPOSED even though
				// every one of their attributes was already APPLIED, none of them created or touched by THIS run at all.
				final RequestResult everyEntityRow = entitiesTable.createRequestResult("./project=" + CatalogReader.quote(pProjectId));
				try
				{
					for (Adaptation entityRow = everyEntityRow.nextAdaptation(); entityRow != null; entityRow = everyEntityRow.nextAdaptation())
					{
						final String entityId = entityRow.getOccurrencePrimaryKey().format();
						final RequestResult attributeRowsOfEntity = attributesTable.createRequestResult("./entity=" + CatalogReader.quote(entityId));
						try
						{
							for (Adaptation attributeRow = attributeRowsOfEntity.nextAdaptation(); attributeRow != null; attributeRow = attributeRowsOfEntity
								.nextAdaptation())
							{
								final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
								final String attributeStatus = attributeRow.getString(FactoryPaths.StagingAttribute.STATUS);
								final List<StagingRules.ExistingMapping> mappings = StagingSuggester.readExistingMappings(pFactory, attributeId);
								final List<StagingRules.MappingStatusBackfill> alignments = StagingRules.alignMappingStatus(attributeStatus, mappings);
								for (final StagingRules.MappingStatusBackfill alignment : alignments)
								{
									final Adaptation mappingRow = mappingsTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(alignment.mappingId));
									if (mappingRow == null)
									{
										continue;
									}
									// ONLY status is set here, to the target StagingRules.alignMappingStatus already decided
									final ValueContextForUpdate mappingValue = pContext.getContext(mappingRow.getAdaptationName());
									mappingValue.setValue(alignment.targetStatus, FactoryPaths.AttributeMapping.STATUS);
									pContext.doModifyContent(mappingRow, mappingValue);
									counts[8]++;
								}
							}
						}
						finally
						{
							attributeRowsOfEntity.close();
						}
					}
				}
				finally
				{
					everyEntityRow.close();
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveStagingSuggestion", "Writing the staging suggestion of project " + pProjectId + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProjectId);
		}
		pOut.put("entitiesCreated", counts[0]);
		pOut.put("entitiesSkipped", counts[1]);
		pOut.put("attributesCreated", counts[2]);
		pOut.put("mappingsCreated", counts[3]);
		pOut.put("relationsLinked", counts[4]);
		pOut.put("primaryKeysBackfilled", counts[5]);
		pOut.put("technicalKeysAligned", counts[6]);
		pOut.put("technicalKeysCreated", counts[7]);
		pOut.put("mappingsAligned", counts[8]);
		pOut.put("rootFeedsCreated", counts[9]);
	}

	/**
	 * Target-mapping lot 1 (docs/TARGET-MAPPING.md sec 4.1): "a PROPOSED feed follows its entity's decision
	 * exactly like a mapping follows its attribute" - the same {@link StagingRules#alignMappingStatus}/{@link
	 * MappingFollowRule} rule pass E already applies to a mapping following its attribute, reused verbatim
	 * (it only ever reads a bare status/origin pair, which a {@code TransferFeed} row carries the same shape
	 * as an {@code AttributeMapping} row - see {@link ProposalReviews#cascadeFeeds}'s own Javadoc for the
	 * live-decision half of this same rule). Scans every {@code StagingEntity} of the WHOLE project, not just
	 * what this run's other passes touched - the same "a feed accepted before this rule existed, or created by
	 * {@link FeedBackfill} for an entity already decided" reasoning as pass E's own mapping sweep. A separate
	 * {@link Procedure}, called after {@link #write}/{@link FeedBackfill#run}, not folded into either: unlike
	 * pass E (embedded in {@link #write}'s own procedure because it reads rows that same procedure might have
	 * just created), this sweep is idempotent and cheap enough to always run as its own pass, the same way
	 * {@link MemberEntitiesBackfill}/{@link FeedBackfill} already do.
	 *
	 * @return how many {@code TransferFeed} rows were actually moved.
	 */
	private static int alignFeedStatuses(final Session pSession, final Adaptation pFactory, final String pProjectId) throws OperationException
	{
		final AdaptationTable entitiesTable = pFactory.getTable(FactoryPaths.STAGING_ENTITY);
		final AdaptationTable feedsTable = pFactory.getTable(FactoryPaths.TRANSFER_FEED);
		final List<Adaptation> entityRows = new ArrayList<Adaptation>();
		final RequestResult entityRowsResult = entitiesTable.createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRowsResult.nextAdaptation(); row != null; row = entityRowsResult.nextAdaptation())
			{
				entityRows.add(row);
			}
		}
		finally
		{
			entityRowsResult.close();
		}
		if (entityRows.isEmpty())
		{
			return 0;
		}
		final int[] count = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				for (final Adaptation entityRow : entityRows)
				{
					final String entityId = entityRow.getOccurrencePrimaryKey().format();
					final String entityStatus = entityRow.getString(FactoryPaths.StagingEntity.STATUS);
					final List<StagingRules.ExistingMapping> feeds = StagingSuggester.readExistingFeeds(pFactory, entityId);
					final List<StagingRules.MappingStatusBackfill> alignments = StagingRules.alignMappingStatus(entityStatus, feeds);
					for (final StagingRules.MappingStatusBackfill alignment : alignments)
					{
						final Adaptation feedRow = feedsTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(alignment.mappingId));
						if (feedRow == null)
						{
							continue;
						}
						final ValueContextForUpdate feedValue = pContext.getContext(feedRow.getAdaptationName());
						feedValue.setValue(alignment.targetStatus, FactoryPaths.TransferFeed.STATUS);
						pContext.doModifyContent(feedRow, feedValue);
						count[0]++;
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.alignFeeds", "Aligning TransferFeed status of project " + pProjectId + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pProjectId);
		}
		return count[0];
	}

	/** Every root {@code TransferFeed} row of one entity, reusing {@link StagingRules.ExistingMapping}'s shape (id/status/origin) - see {@link #alignFeedStatuses}'s own Javadoc for why. */
	private static List<StagingRules.ExistingMapping> readExistingFeeds(final Adaptation pFactory, final String pEntityId)
	{
		final List<StagingRules.ExistingMapping> out = new ArrayList<StagingRules.ExistingMapping>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.TRANSFER_FEED).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new StagingRules.ExistingMapping(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.TransferFeed.STATUS),
					row.getString(FactoryPaths.TransferFeed.ORIGIN)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Every {@code AttributeMapping} row of one {@code StagingAttribute}, for {@link StagingRules#alignMappingStatus} to decide from. */
	private static List<StagingRules.ExistingMapping> readExistingMappings(final Adaptation pFactory, final String pAttributeId)
	{
		final List<StagingRules.ExistingMapping> out = new ArrayList<StagingRules.ExistingMapping>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(new StagingRules.ExistingMapping(row.getOccurrencePrimaryKey().format(), row.getString(FactoryPaths.AttributeMapping.STATUS),
					row.getString(FactoryPaths.AttributeMapping.ORIGIN)));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Every {@code StagingAttribute} row of an entity that already existed, for {@link StagingRules#backfillFromRelation} to decide from. */
	private static List<StagingRules.ExistingAttribute> readExistingAttributes(final Adaptation pFactory, final String pEntityId)
	{
		final List<StagingRules.ExistingAttribute> out = new ArrayList<StagingRules.ExistingAttribute>();
		if (pEntityId == null)
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final String name = row.getString(FactoryPaths.StagingAttribute.NAME);
				final boolean hasReferenceEntity = row.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY) != null;
				final boolean hasFromRelation = row.getString(FactoryPaths.StagingAttribute.FROM_RELATION) != null;
				// no default in the XSD (see StagingAttribute.primaryKey's Javadoc): null on this read is the actual signal a backfill acts on
				final boolean hasPrimaryKey = row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY) != null;
				final boolean primaryKey = Boolean.TRUE.equals(row.get(FactoryPaths.StagingAttribute.PRIMARY_KEY));
				final String status = row.getString(FactoryPaths.StagingAttribute.STATUS);
				final String origin = row.getString(FactoryPaths.StagingAttribute.ORIGIN);
				out.add(new StagingRules.ExistingAttribute(id, name, hasReferenceEntity, hasFromRelation, hasPrimaryKey, primaryKey, status, origin));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}
}
