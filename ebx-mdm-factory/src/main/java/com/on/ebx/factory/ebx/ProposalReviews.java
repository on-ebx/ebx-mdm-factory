package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.capability.ConstraintStaleness;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.master.ChildEntityMatching;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.master.TrustedSourceRanking;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.quality.WithdrawalRule;
import com.on.ebx.factory.review.MatchPlanView;
import com.on.ebx.factory.review.MatchSurvivorshipBlock;
import com.on.ebx.factory.review.ProposalWording;
import com.on.ebx.factory.review.ReviewEntityFormat;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.review.ReviewMessages;
import com.on.ebx.factory.review.ReviewRules;
import com.on.ebx.factory.staging.MappingFollowRule;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.on.ebx.factory.wizard.RationaleText;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Thin EBX-bound reader and writer behind the {@code ProposalReview} screen: reads the project's
 * proposals out of the five tables into plain {@link ReviewLine} value objects (resolving every
 * foreign key to a human-readable label along the way), and writes a decision. Everything that
 * decides whether a decision is CONSISTENT lives in the pure {@link ReviewRules}; this class only
 * resolves the EBX data that rule needs and applies the write. Every write runs in a
 * {@link ProgrammaticService#createForSession(Session, com.onwbp.adaptation.AdaptationHome) session
 * procedure} with the CALLING user's own session, never a system session and never
 * {@code setAllPrivileges} - so EBX's own revision history attributes the decision to that person,
 * which is the entire point (PLAN.md, "Identites et droits": "accepter une proposition est un geste
 * nominatif").
 */
public final class ProposalReviews
{
	private ProposalReviews()
	{
	}

	// ------------------------------------------------------------------------------------------------- project

	public static List<Adaptation> projects(final Adaptation pFactory)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.PROJECT), null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	public static Adaptation findProjectByCode(final Adaptation pFactory, final String pCode)
	{
		if (pCode == null || pCode.trim().isEmpty())
		{
			return null;
		}
		// second permissions pass (2026-10-14): by primary key (Project's key IS /code), so a hidden code FIELD does not make a visible project
		// unreachable; inside a user's read scope (UserView#scoped) a hidden project answers null like an unknown code
		final com.onwbp.adaptation.AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		try
		{
			return UserView.byKey(projects, projects.computePrimaryKey(new Object[] { pCode.trim() }));
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
	}

	/**
	 * The project of a record the service may have been launched on (toolbar action): the {@code Project}
	 * record itself, or - by traversal through its own foreign keys - a record of any of the five
	 * proposal tables. Null when {@code pRecord} is null or belongs to none of these six tables (a launch
	 * from the perspective menu, with no record selected).
	 */
	public static Adaptation projectOfRecord(final Adaptation pFactory, final AdaptationTable pTable, final Adaptation pRecord)
	{
		if (pTable == null || pRecord == null)
		{
			return null;
		}
		final String tablePath = pTable.getTablePath().format();
		if (FactoryPaths.PROJECT.format().equals(tablePath))
		{
			return UserView.current().recordVisible(pRecord) ? pRecord : null;
		}
		if (FactoryPaths.LANDING_RELATION.format().equals(tablePath))
		{
			final Adaptation projectSource = ProposalReviews.lookup(pFactory, FactoryPaths.PROJECT_SOURCE, UserView.string(pRecord, FactoryPaths.LandingRelation.PROJECT_SOURCE));
			return projectSource == null ? null : ProposalReviews.lookup(pFactory, FactoryPaths.PROJECT, UserView.string(projectSource, FactoryPaths.ProjectSource.PROJECT));
		}
		if (FactoryPaths.STAGING_ENTITY.format().equals(tablePath))
		{
			return ProposalReviews.lookup(pFactory, FactoryPaths.PROJECT, UserView.string(pRecord, FactoryPaths.StagingEntity.PROJECT));
		}
		if (FactoryPaths.STAGING_ATTRIBUTE.format().equals(tablePath))
		{
			return ProposalReviews.projectOfEntityId(pFactory, UserView.string(pRecord, FactoryPaths.StagingAttribute.ENTITY));
		}
		if (FactoryPaths.ATTRIBUTE_MAPPING.format().equals(tablePath))
		{
			final Adaptation attribute = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, UserView.string(pRecord, FactoryPaths.AttributeMapping.ATTRIBUTE));
			return attribute == null ? null : ProposalReviews.projectOfEntityId(pFactory, UserView.string(attribute, FactoryPaths.StagingAttribute.ENTITY));
		}
		if (FactoryPaths.MATCH_PLAN.format().equals(tablePath))
		{
			return ProposalReviews.projectOfEntityId(pFactory, UserView.string(pRecord, FactoryPaths.MatchPlan.ENTITY));
		}
		return null;
	}

	private static Adaptation projectOfEntityId(final Adaptation pFactory, final String pEntityId)
	{
		final Adaptation entity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, pEntityId);
		return entity == null ? null : ProposalReviews.lookup(pFactory, FactoryPaths.PROJECT, UserView.string(entity, FactoryPaths.StagingEntity.PROJECT));
	}

	/** Package-private (not {@code private}): {@code ebx.CapabilityApplier} reuses this exact dangling-id-safe lookup rather than a second copy of it. */
	static Adaptation lookup(final Adaptation pFactory, final Path pTablePath, final String pId)
	{
		if (pId == null || pId.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return UserView.byKey(pFactory.getTable(pTablePath), PrimaryKey.parseString(pId));
		}
		catch (final RuntimeException ex)
		{
			return null; // a malformed or dangling id reads as "not found", never as a service error
		}
	}

	// -------------------------------------------------------------------------------------------------- reading

	/**
	 * Every proposal of the project, across the five tables, as uniform {@link ReviewLine}s: every
	 * foreign key the mockup's "element"/"proposition" text needs is resolved here (table names, entity
	 * names, attribute names), so {@link com.on.ebx.factory.review.ReviewData}/{@code ReviewPage} never
	 * touch EBX.
	 */
	public static List<ReviewLine> lines(final Adaptation pFactory, final String pProjectId, final Locale pLocale)
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		final Map<String, String> landingTableNames = new HashMap<String, String>();
		// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): a FEED line's element names the source system
		// of the landing table it reads ("Personne <- ERP LANDING_PERSON") - built alongside landingTableNames
		// below, one extra ProjectSource lookup per source (never per landing table)
		final Map<String, String> landingTableSourceSystems = new HashMap<String, String>();
		final Map<String, String> stagingEntityNames = new HashMap<String, String>();

		final List<String> sourceIds = new ArrayList<String>();
		final RequestResult sourceRows = UserView.select(pFactory.getTable(FactoryPaths.PROJECT_SOURCE), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = sourceRows.nextAdaptation(); row != null; row = sourceRows.nextAdaptation())
			{
				sourceIds.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			sourceRows.close();
		}
		for (final String sourceId : sourceIds)
		{
			final Adaptation projectSourceRow = ProposalReviews.lookup(pFactory, FactoryPaths.PROJECT_SOURCE, sourceId);
			final String sourceSystem = projectSourceRow == null ? null : UserView.string(projectSourceRow, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
			final RequestResult tableRows = UserView.select(pFactory.getTable(FactoryPaths.LANDING_TABLE), "./projectSource=" + CatalogReader.quote(sourceId));
			try
			{
				for (Adaptation row = tableRows.nextAdaptation(); row != null; row = tableRows.nextAdaptation())
				{
					final String tableId = row.getOccurrencePrimaryKey().format();
					landingTableNames.put(tableId, UserView.string(row, FactoryPaths.LandingTable.TABLE_NAME));
					if (sourceSystem != null)
					{
						landingTableSourceSystems.put(tableId, sourceSystem);
					}
				}
			}
			finally
			{
				tableRows.close();
			}
			ProposalReviews.readRelations(pFactory, sourceId, landingTableNames, pLocale, out);
		}

		final List<String> entityIds = new ArrayList<String>();
		final RequestResult entityRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ENTITY), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = entityRows.nextAdaptation(); row != null; row = entityRows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				entityIds.add(id);
				stagingEntityNames.put(id, UserView.string(row, FactoryPaths.StagingEntity.NAME));
				ProposalReviews.addEntityLine(pFactory, row, id, pLocale, out);
			}
		}
		finally
		{
			entityRows.close();
		}

		final Map<String, String> attributeNames = new HashMap<String, String>();
		final Map<String, String> attributeEntityIds = new HashMap<String, String>();
		for (final String entityId : entityIds)
		{
			final RequestResult attributeRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(entityId));
			try
			{
				for (Adaptation row = attributeRows.nextAdaptation(); row != null; row = attributeRows.nextAdaptation())
				{
					final String id = row.getOccurrencePrimaryKey().format();
					attributeNames.put(id, UserView.string(row, FactoryPaths.StagingAttribute.NAME));
					attributeEntityIds.put(id, entityId);
					ProposalReviews.addAttributeLine(pFactory, row, id, entityId, stagingEntityNames, pLocale, out);
				}
			}
			finally
			{
				attributeRows.close();
			}
			ProposalReviews.readMatchPlans(pFactory, entityId, stagingEntityNames.get(entityId), pLocale, out);
			// task B: entity-level (attribute null) and attribute-level QualityRule rows of THIS entity - every
			// attribute of the entity was just added to attributeNames above, in the same iteration, so the
			// lookup below always resolves for a row genuinely scoped to this entity
			ProposalReviews.addQualityRuleLines(pFactory, entityId, stagingEntityNames.get(entityId), attributeNames, pLocale, out);
			// capability-suggestion lot: entity-level and attribute-level CapabilitySuggestion rows of THIS entity, same reasoning
			ProposalReviews.addCapabilitySuggestionLines(pFactory, entityId, stagingEntityNames.get(entityId), attributeNames, pLocale, out);
			// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): TransferFeed rows of THIS entity - a feed's
			// mappings only ever reference attributes of its OWN entity, already in attributeNames above, same
			// reasoning as the two calls just above
			ProposalReviews.addFeedLines(pFactory, entityId, stagingEntityNames.get(entityId), attributeNames, landingTableNames, landingTableSourceSystems, pLocale, out);
		}

		for (final Map.Entry<String, String> attribute : attributeNames.entrySet())
		{
			ProposalReviews.readMappings(pFactory, attribute.getKey(), attributeEntityIds.get(attribute.getKey()), attribute.getValue(), stagingEntityNames,
				landingTableNames, pLocale, out);
		}
		return out;
	}

	private static void readRelations(final Adaptation pFactory, final String pProjectSourceId, final Map<String, String> pLandingTableNames, final Locale pLocale,
		final List<ReviewLine> pOut)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.LANDING_RELATION), "./projectSource=" + CatalogReader.quote(pProjectSourceId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final String fromTable = pLandingTableNames.get(UserView.string(row, FactoryPaths.LandingRelation.FROM_TABLE));
				final String toTable = pLandingTableNames.get(UserView.string(row, FactoryPaths.LandingRelation.TO_TABLE));
				final List<?> columns = UserView.values(row, FactoryPaths.LandingRelation.FROM_COLUMNS);
				final StringBuilder columnList = new StringBuilder();
				if (columns != null)
				{
					for (final Object column : columns)
					{
						if (columnList.length() > 0)
						{
							columnList.append(", ");
						}
						columnList.append(String.valueOf(column));
					}
				}
				final String element = (fromTable == null ? "?" : fromTable) + "." + columnList;
				final boolean selfReference = Boolean.TRUE.equals(UserView.get(row, FactoryPaths.LandingRelation.SELF_REFERENCE));
				final String suffix = selfReference ? ReviewMessages.get(pLocale, "review.prop.relation.selfSuffix") : "";
				final String proposition = ReviewMessages.get(pLocale, "review.prop.relation", toTable == null ? "?" : toTable, suffix);
				pOut.add(new ReviewLine(ReviewLine.RELATION, id, element, proposition, ProposalReviews.text(row, FactoryPaths.LandingRelation.ORIGIN),
					ProposalReviews.confidence(row, FactoryPaths.LandingRelation.CONFIDENCE), ProposalReviews.text(row, FactoryPaths.LandingRelation.RATIONALE),
					ProposalReviews.text(row, FactoryPaths.LandingRelation.STATUS), false, false));
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * A canonical (LLM) entity's proposition names its members ({@code review.prop.entity.unifies}, e.g.
	 * "Unifies Supplier (ERP), Account (CRM)") read from the real {@code memberEntities} foreign key, not
	 * from parsing the rationale text (2026-09-20 coordinator review) - {@link ReviewEntityFormat} does the
	 * (tested) formatting once every member's name and source system are resolved here. Falls back to the
	 * existing "feeds master table" / "new entity" text when {@code memberEntities} is empty (a
	 * rule-proposed entity, or an older canonical row whose backfill has not run/resolved).
	 */
	private static void addEntityLine(final Adaptation pFactory, final Adaptation pRow, final String pId, final Locale pLocale, final List<ReviewLine> pOut)
	{
		final List<?> rawMemberIds = UserView.values(pRow, FactoryPaths.StagingEntity.MEMBER_ENTITIES);
		String proposition = null;
		if (rawMemberIds != null && !rawMemberIds.isEmpty())
		{
			final List<ReviewEntityFormat.Member> members = new ArrayList<ReviewEntityFormat.Member>();
			for (final Object rawMemberId : rawMemberIds)
			{
				final String memberId = String.valueOf(rawMemberId);
				final Adaptation memberRow = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, memberId);
				final String memberName = memberRow == null ? memberId : UserView.string(memberRow, FactoryPaths.StagingEntity.NAME);
				members.add(new ReviewEntityFormat.Member(memberName, ProposalReviews.resolveSourceSystemOfEntity(pFactory, memberId)));
			}
			final String memberList = ReviewEntityFormat.unifiesText(members);
			if (memberList != null)
			{
				proposition = ReviewMessages.get(pLocale, "review.prop.entity.unifies", memberList);
			}
		}
		if (proposition == null)
		{
			final String targetTable = ProposalReviews.text(pRow, FactoryPaths.StagingEntity.TARGET_TABLE);
			proposition = targetTable.isEmpty() ? ReviewMessages.get(pLocale, "review.prop.entity.new") : ReviewMessages.get(pLocale, "review.prop.entity.target", targetTable);
		}
		pOut.add(new ReviewLine(ReviewLine.ENTITY, pId, UserView.string(pRow, FactoryPaths.StagingEntity.NAME), proposition,
			ProposalReviews.text(pRow, FactoryPaths.StagingEntity.ORIGIN), ProposalReviews.confidence(pRow, FactoryPaths.StagingEntity.CONFIDENCE),
			ProposalReviews.text(pRow, FactoryPaths.StagingEntity.RATIONALE), ProposalReviews.text(pRow, FactoryPaths.StagingEntity.STATUS), false, false));
	}

	/**
	 * A rule-proposed entity's source system, resolved the same way {@code StagingLlmSuggester.resolveSource}
	 * does: through the first business attribute that carries a real {@code AttributeMapping} - landing
	 * column - landing table - project source. Null when none of the entity's attributes has a mapping yet
	 * (the two technical identity attributes never do - see {@code StagingRules}), or any link along the
	 * way is missing.
	 */
	private static String resolveSourceSystemOfEntity(final Adaptation pFactory, final String pEntityId)
	{
		final RequestResult attributeRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				final RequestResult mappingRows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./attribute=" + CatalogReader.quote(attributeRow.getOccurrencePrimaryKey().format()));
				try
				{
					final Adaptation mappingRow = mappingRows.nextAdaptation();
					if (mappingRow == null)
					{
						continue;
					}
					final Adaptation landingColumn = ProposalReviews.lookup(pFactory, FactoryPaths.LANDING_COLUMN, UserView.string(mappingRow, FactoryPaths.AttributeMapping.LANDING_COLUMN));
					final Adaptation landingTable = landingColumn == null ? null
						: ProposalReviews.lookup(pFactory, FactoryPaths.LANDING_TABLE, UserView.string(landingColumn, FactoryPaths.LandingColumn.LANDING_TABLE));
					final Adaptation projectSource = landingTable == null ? null
						: ProposalReviews.lookup(pFactory, FactoryPaths.PROJECT_SOURCE, UserView.string(landingTable, FactoryPaths.LandingTable.PROJECT_SOURCE));
					if (projectSource != null)
					{
						return UserView.string(projectSource, FactoryPaths.ProjectSource.SOURCE_SYSTEM);
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

	/** Whether this attribute row is its entity's structural link to the parent ({@link com.on.ebx.factory.staging.ParentLinkRule}). */
	private static boolean isParentLink(final Adaptation pFactory, final Adaptation pAttributeRow)
	{
		if (ProposalReviews.text(pAttributeRow, FactoryPaths.StagingAttribute.REFERENCE_ENTITY).isEmpty())
		{
			return false;
		}
		return ParentLinkReader.attributeIdsOf(pFactory, ProposalReviews.text(pAttributeRow, FactoryPaths.StagingAttribute.ENTITY))
			.contains(pAttributeRow.getOccurrencePrimaryKey().format());
	}

	private static void addAttributeLine(final Adaptation pFactory, final Adaptation pRow, final String pId, final String pEntityId,
		final Map<String, String> pStagingEntityNames, final Locale pLocale, final List<ReviewLine> pOut)
	{
		final String entityName = pStagingEntityNames.get(pEntityId);
		final String name = UserView.string(pRow, FactoryPaths.StagingAttribute.NAME);
		final String element = (entityName == null ? "?" : entityName) + "." + name;
		final String referenceEntityId = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
		final String dataType = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.DATA_TYPE);
		// derived attribute (2026-09-22, StagingAttribute.derivedFrom/derivation): a FOLD_KEY row has no
		// referenceEntity of its own, so this must be checked BEFORE the plain ref/no-ref branch below, and its
		// proposition names the SOURCE attribute (never a raw derivedFrom id) so a reviewer sees where the key
		// comes from and what it is for at a glance - same "show source and purpose" bar as every other line.
		final String derivedFromId = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.DERIVED_FROM);
		final boolean derived = !ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.DERIVATION).isEmpty();
		final String proposition;
		if (derived && ChildKeysDerivation.DERIVATION_CHILD_KEYS.equals(ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.DERIVATION)))
		{
			// lot M3: no source attribute - the keys come from the child records the argument names
			proposition = ReviewMessages.get(pLocale, "review.prop.attribute.childkeys", dataType, ChildKeysPass.childEntityLabel(pRow));
		}
		else if (derived)
		{
			final Adaptation sourceAttribute = derivedFromId.isEmpty() ? null : ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, derivedFromId);
			final String sourceName = sourceAttribute == null ? "?" : UserView.string(sourceAttribute, FactoryPaths.StagingAttribute.NAME);
			proposition = ReviewMessages.get(pLocale, "review.prop.attribute.foldkey", dataType, sourceName);
		}
		else if (!referenceEntityId.isEmpty())
		{
			final Adaptation referenceEntity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, referenceEntityId);
			final String referenceName = referenceEntity == null ? referenceEntityId : UserView.string(referenceEntity, FactoryPaths.StagingEntity.NAME);
			proposition = ReviewMessages.get(pLocale, "review.prop.attribute.ref", dataType, referenceName);
		}
		else
		{
			proposition = ReviewMessages.get(pLocale, "review.prop.attribute.plain", dataType);
		}
		final boolean businessKey = Boolean.TRUE.equals(UserView.get(pRow, FactoryPaths.StagingAttribute.BUSINESS_KEY));
		// the parent link (ParentLinkRule) follows its entity like the technical key: same "no decision of its own" line
		final boolean primaryKey = Boolean.TRUE.equals(UserView.get(pRow, FactoryPaths.StagingAttribute.PRIMARY_KEY))
			|| ProposalReviews.isParentLink(pFactory, pRow);
		final String status = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.STATUS);
		// the contradiction a reviewer must see, never something this blocks on (ReviewRules.attributeMappingWarning): an
		// accepted attribute every one of whose mappings is rejected has nothing left to feed it - surfaced on the existing
		// rationale text (already rendered for every line) rather than a new field, so no *Page* rendering change is needed.
		// A derived attribute never has a mapping of its own by design (see ReviewRules' own Javadoc on pDerived) - passed
		// here so an accepted FOLD_KEY row is never flagged with this false alarm.
		final String warning = ReviewRules.attributeMappingWarning(status, primaryKey, derived, !referenceEntityId.isEmpty(), ProposalReviews.mappingStatusesOf(pFactory, pId));
		final String rationale = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.RATIONALE);
		pOut.add(new ReviewLine(ReviewLine.ATTRIBUTE, pId, element, proposition, ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.ORIGIN),
			ProposalReviews.confidence(pRow, FactoryPaths.StagingAttribute.CONFIDENCE), warning == null ? rationale : warning + " " + rationale, status, businessKey,
			primaryKey));
	}

	/** Every {@code AttributeMapping.status} of one attribute, for {@link ReviewRules#attributeMappingWarning} to decide from - never resolved further (only the status matters to that rule). */
	private static List<String> mappingStatusesOf(final Adaptation pFactory, final String pAttributeId)
	{
		final List<String> out = new ArrayList<String>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(UserView.string(row, FactoryPaths.AttributeMapping.STATUS));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static void readMappings(final Adaptation pFactory, final String pAttributeId, final String pEntityId, final String pAttributeName,
		final Map<String, String> pStagingEntityNames, final Map<String, String> pLandingTableNames, final Locale pLocale, final List<ReviewLine> pOut)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final String entityName = pStagingEntityNames.get(pEntityId);
				final String target = (entityName == null ? "?" : entityName) + "." + pAttributeName;
				final Adaptation landingColumn = ProposalReviews.lookup(pFactory, FactoryPaths.LANDING_COLUMN, UserView.string(row, FactoryPaths.AttributeMapping.LANDING_COLUMN));
				final String sourceColumn;
				if (landingColumn == null)
				{
					sourceColumn = "?";
				}
				else
				{
					final String tableName = pLandingTableNames.get(UserView.string(landingColumn, FactoryPaths.LandingColumn.LANDING_TABLE));
					sourceColumn = (tableName == null ? "?" : tableName) + "." + UserView.string(landingColumn, FactoryPaths.LandingColumn.SOURCE_COLUMN);
				}
				// a canonical (LLM) attribute has one AttributeMapping row per source it aligns (StagingLlmPass.CreatedAttribute.mappings):
				// several lines here can share the same target, so the source must be in the ELEMENT text too, not only the proposition,
				// or two such rows read as duplicates (coordinator review, 2026-09-20) - e.g. "SUPPLIER.NAME1 -> Company.name"
				final String element = sourceColumn + " → " + target;
				final String transformation = ProposalReviews.text(row, FactoryPaths.AttributeMapping.TRANSFORMATION);
				final String argument = ProposalReviews.text(row, FactoryPaths.AttributeMapping.ARGUMENT);
				final String proposition = argument.isEmpty() ? transformation : transformation + " (" + argument + ")";
				final String mappingStatus = ProposalReviews.text(row, FactoryPaths.AttributeMapping.STATUS);
				pOut.add(new ReviewLine(ReviewLine.MAPPING, id, element, proposition, ProposalReviews.text(row, FactoryPaths.AttributeMapping.ORIGIN),
					ProposalReviews.confidence(row, FactoryPaths.AttributeMapping.CONFIDENCE),
					RationaleText.display(ProposalReviews.text(row, FactoryPaths.AttributeMapping.RATIONALE), pLocale), mappingStatus, false, false));
				// task B: this mapping's own ACCEPTED-or-not MappingStep pipeline, one ReviewLine per step - a
				// step's decision is independent per step (docs/NORMALISATION.md sec 3.2), shown under the same
				// element text as its parent mapping so a reviewer sees which pipeline a step belongs to
				ProposalReviews.addMappingStepLines(pFactory, id, element, mappingStatus, pLocale, pOut);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * One {@link ReviewLine#MAPPING_STEP} per {@code MappingStep} of the mapping {@code pMappingId} - kind and
	 * argument in plain words ({@link ProposalWording}, never raw JSON), the evidence counters folded into the
	 * rationale (task B: "seen/changed/unresolved"), and two independent warnings, both appended to the SAME
	 * rationale text rather than a new field (the same "reuse the existing rationale slot" convention {@code
	 * addAttributeLine} already established for its own contradiction warning):
	 * <ul>
	 * <li>order dependency (docs/NORMALISATION.md sec 3.2: a step's decision is independent per step) - a step
	 * whose evidence was computed assuming an EARLIER step of the same pipeline would run, when that earlier
	 * step is actually REJECTED, carries a note rather than being blocked;</li>
	 * <li>evidence staleness (coordinator finding, 2026-09-21, {@code quality.WithdrawalRule}) - a step a
	 * person already decided on (ACCEPTED/REJECTED/APPLIED) whose mapping is no longer accepted-like is
	 * flagged, never silently left to look current.</li>
	 * </ul>
	 */
	/**
	 * Live bug fix (2026-09-25, PersVol2 volume test, Mapping matrix "Accept the N unflagged proposals"): the
	 * SAME {@code MappingStep} rows {@link #addMappingStepLines} builds for one mapping, exposed publicly so
	 * {@code ebx.ui.MappingMatrixUserService}'s bulk-accept sweep can decide them too. {@code mappingmatrix.
	 * MatrixModel}/{@code MatrixCompute} never model a mapping's own cleaning steps at all (only {@code
	 * Cell#pipelineSteps}, plain display strings, kind names only) - without this, the matrix's project-wide
	 * "Accept the N unflagged proposals" button accepted a mapping cell but silently left its cleaning steps
	 * PROPOSED, so they were never applied at load. The wizard's OWN bulk-accept ({@code
	 * ProjectWizardUserService#bulkStaging}) does not have this gap - it already sweeps {@code
	 * WizardSnapshot.StagingEntityInfo#proposalLines}, which includes {@code MAPPING_STEP}.
	 */
	public static List<ReviewLine> mappingStepLinesOf(final Adaptation pFactory, final String pMappingId, final Locale pLocale)
	{
		final List<ReviewLine> out = new ArrayList<ReviewLine>();
		ProposalReviews.addMappingStepLines(pFactory, pMappingId, "", "", pLocale, out);
		return out;
	}

	private static void addMappingStepLines(final Adaptation pFactory, final String pMappingId, final String pMappingElement, final String pMappingStatus,
		final Locale pLocale, final List<ReviewLine> pOut)
	{
		final List<Adaptation> steps = new ArrayList<Adaptation>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.MAPPING_STEP), "./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				steps.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		if (steps.isEmpty())
		{
			return;
		}
		java.util.Collections.sort(steps, new java.util.Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				return ProposalReviews.intOf(pLeft, FactoryPaths.MappingStep.POSITION) - ProposalReviews.intOf(pRight, FactoryPaths.MappingStep.POSITION);
			}
		});
		final java.util.Set<Integer> rejectedPositions = new java.util.HashSet<Integer>();
		for (final Adaptation step : steps)
		{
			if (ReviewLine.REJECTED.equals(UserView.string(step, FactoryPaths.MappingStep.STATUS)))
			{
				rejectedPositions.add(Integer.valueOf(ProposalReviews.intOf(step, FactoryPaths.MappingStep.POSITION)));
			}
		}
		final boolean mappingCovered = TechnicalKeyRule.isAcceptedLike(pMappingStatus);
		for (final Adaptation step : steps)
		{
			final int position = ProposalReviews.intOf(step, FactoryPaths.MappingStep.POSITION);
			final String status = ProposalReviews.text(step, FactoryPaths.MappingStep.STATUS);
			final String proposition = ProposalWording.mappingStepProposition(UserView.string(step, FactoryPaths.MappingStep.KIND),
				ProposalReviews.text(step, FactoryPaths.MappingStep.ARGUMENT), Integer.valueOf(position));
			// staging last points (2026-10-17): the passes' notes are coded - worded here in the viewer's language (the lines are built per locale)
			final StringBuilder rationale = new StringBuilder(RationaleText.display(ProposalReviews.text(step, FactoryPaths.MappingStep.RATIONALE), pLocale));
			ProposalReviews.appendSpaced(rationale,
				ProposalWording.mappingStepEvidence(ProposalReviews.longOf(step, FactoryPaths.MappingStep.VALUES_SEEN),
					ProposalReviews.longOf(step, FactoryPaths.MappingStep.VALUES_CHANGED), ProposalReviews.longOf(step, FactoryPaths.MappingStep.VALUES_UNRESOLVED)));
			boolean earlierRejected = false;
			for (final Integer rejectedPosition : rejectedPositions)
			{
				if (rejectedPosition.intValue() < position)
				{
					earlierRejected = true;
					break;
				}
			}
			if (earlierRejected)
			{
				ProposalReviews.appendSpaced(rationale, ReviewMessages.get(pLocale, "review.mappingStep.orderWarning"));
			}
			if (WithdrawalRule.decide(status, mappingCovered) == WithdrawalRule.Action.WARN)
			{
				ProposalReviews.appendSpaced(rationale, ReviewMessages.get(pLocale, "review.mappingStep.staleWarning"));
			}
			pOut.add(new ReviewLine(ReviewLine.MAPPING_STEP, step.getOccurrencePrimaryKey().format(), pMappingElement, proposition,
				ProposalReviews.text(step, FactoryPaths.MappingStep.ORIGIN), ProposalReviews.confidence(step, FactoryPaths.MappingStep.CONFIDENCE), rationale.toString(),
				status, false, false));
		}
	}

	/**
	 * Every {@code QualityRule} of one entity - entity-level (attribute {@code null}) and attribute-level
	 * alike - as {@link ReviewLine#QUALITY} lines: severity folded into the proposition ({@link
	 * ProposalWording}), suggest-time evidence and (task C) the transfer-time {@code lastLoad*} counters both
	 * folded into the rationale, and the same evidence-staleness warning {@link #addMappingStepLines} computes
	 * - an attribute-scoped rule whose attribute no longer has an accepted-like mapping, already decided on by
	 * a person, is flagged rather than left to look current. An entity-level rule ({@code attribute} empty)
	 * has no such coverage notion and is never flagged this way.
	 */
	private static void addQualityRuleLines(final Adaptation pFactory, final String pEntityId, final String pEntityName, final Map<String, String> pAttributeNames,
		final Locale pLocale, final List<ReviewLine> pOut)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.QUALITY_RULE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String attributeId = ProposalReviews.text(row, FactoryPaths.QualityRule.ATTRIBUTE);
				final String attributeName = attributeId.isEmpty() ? null : pAttributeNames.get(attributeId);
				final String element = attributeName == null ? (pEntityName == null ? "?" : pEntityName) : (pEntityName == null ? "?" : pEntityName) + "." + attributeName;
				final String kind = UserView.string(row, FactoryPaths.QualityRule.KIND);
				final String argument = ProposalReviews.text(row, FactoryPaths.QualityRule.ARGUMENT);
				final String severity = UserView.string(row, FactoryPaths.QualityRule.SEVERITY);
				final String proposition = ProposalWording.qualityRuleProposition(kind, argument, severity);
				final String status = ProposalReviews.text(row, FactoryPaths.QualityRule.STATUS);
				final StringBuilder rationale = new StringBuilder(ProposalReviews.text(row, FactoryPaths.QualityRule.RATIONALE));
				ProposalReviews.appendSpaced(rationale, ProposalWording.qualityRuleEvidence(ProposalReviews.longOf(row, FactoryPaths.QualityRule.VALUES_SEEN),
					ProposalReviews.longOf(row, FactoryPaths.QualityRule.VALUES_FAILING)));
				if (!QualityDetectors.MODEL_CONSTRAINT.equals(severity))
				{
					ProposalReviews.appendSpaced(rationale, ProposalWording.qualityRuleLastLoad(ProposalReviews.longOf(row, FactoryPaths.QualityRule.LAST_LOAD_CHECKED),
						ProposalReviews.longOf(row, FactoryPaths.QualityRule.LAST_LOAD_FAILING)));
				}
				if (!attributeId.isEmpty())
				{
					final boolean covered = ProposalReviews.attributeHasAcceptedMapping(pFactory, attributeId);
					if (WithdrawalRule.decide(status, covered) == WithdrawalRule.Action.WARN)
					{
						ProposalReviews.appendSpaced(rationale, ReviewMessages.get(pLocale, "review.quality.staleWarning"));
					}
				}
				pOut.add(new ReviewLine(ReviewLine.QUALITY, row.getOccurrencePrimaryKey().format(), element, proposition, ProposalReviews.text(row, FactoryPaths.QualityRule.ORIGIN),
					ProposalReviews.confidence(row, FactoryPaths.QualityRule.CONFIDENCE), rationale.toString(), status, false, false));
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Every {@code CapabilitySuggestion} of one entity - entity-level (attribute empty) and attribute-level
	 * alike - as {@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#EXTERNAL_SERVICE} lines (capability-
	 * suggestion lot): the two natures the task asked for, wired through the same generic {@code
	 * ReviewLine}/{@code ReviewPage} machinery {@link #addMappingStepLines}/{@link #addQualityRuleLines}
	 * already established for MAPPING_STEP/QUALITY - no new rendering branch needed. The proposition is
	 * built in plain words by {@link ProposalWording#scriptConstraintProposition}/{@link
	 * ProposalWording#externalServiceProposition}, never the raw {@code binding} JSON; the binding itself is
	 * appended to the rationale by {@link ProposalWording#capabilityBinding} so a reviewer can see exactly
	 * which attribute feeds which parameter/input without leaving the table. Decision only (accept/reject) -
	 * this screen never attaches a script constraint or calls a service, per the task brief.
	 */
	private static void addCapabilitySuggestionLines(final Adaptation pFactory, final String pEntityId, final String pEntityName,
		final Map<String, String> pAttributeNames, final Locale pLocale, final List<ReviewLine> pOut)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String attributeId = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
				final String attributeName = attributeId.isEmpty() ? null : pAttributeNames.get(attributeId);
				final String element = attributeName == null ? (pEntityName == null ? "?" : pEntityName) : (pEntityName == null ? "?" : pEntityName) + "." + attributeName;
				final String nature = UserView.string(row, FactoryPaths.CapabilitySuggestion.NATURE);
				final String status = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.STATUS);
				final String proposition;
				if (ReviewLine.SCRIPT_CONSTRAINT.equals(nature))
				{
					proposition = ProposalWording.scriptConstraintProposition(UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY),
						UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION), ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.ZONE),
						UserView.string(row, FactoryPaths.CapabilitySuggestion.SEVERITY));
				}
				else if (ReviewLine.SCRIPT_DERIVATION.equals(nature))
				{
					// script-backed-derivation lot (2026-09-23): CapabilitySuggestion.attribute names the NEW
					// (derived) attribute for this nature - its own derivedFrom names the SOURCE one, one extra
					// read (never in pAttributeNames, which only ever covers what the caller already resolved).
					final Adaptation attributeRow = attributeId.isEmpty() ? null : ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, attributeId);
					final String sourceAttributeId = attributeRow == null ? null : UserView.string(attributeRow, FactoryPaths.StagingAttribute.DERIVED_FROM);
					final String sourceAttributeName = sourceAttributeId == null ? null : pAttributeNames.get(sourceAttributeId);
					proposition = ProposalWording.scriptDerivationProposition(attributeName, sourceAttributeName, UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY),
						UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION));
				}
				else
				{
					proposition = ProposalWording.externalServiceProposition(UserView.string(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_ID),
						UserView.string(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_OPERATION), !Boolean.FALSE.equals(UserView.get(row, FactoryPaths.CapabilitySuggestion.READY)),
						UserView.string(row, FactoryPaths.CapabilitySuggestion.PRICING));
				}
				final StringBuilder rationale = new StringBuilder(ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.RATIONALE));
				ProposalReviews.appendSpaced(rationale, ProposalWording.capabilityBinding(ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.BINDING)));
				if (ReviewLine.EXTERNAL_SERVICE.equals(nature))
				{
					if (Boolean.FALSE.equals(UserView.get(row, FactoryPaths.CapabilitySuggestion.READY)))
					{
						final String detail = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.READINESS_DETAIL);
						ProposalReviews.appendSpaced(rationale, ReviewMessages.get(pLocale, "review.capability.notReady", detail));
					}
					// service-choice lot (coordinator, 2026-09-21, item 2): never hide a NOT_CONFIGURED/costlier
					// suggestion - alternativeTo says which READY/free service it was offered ahead of, so a
					// reviewer sees the trade-off without reading every row in the table
					final String alternativeTo = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.ALTERNATIVE_TO);
					if (!alternativeTo.isEmpty())
					{
						ProposalReviews.appendSpaced(rationale, ReviewMessages.get(pLocale, "review.capability.alternativeTo", alternativeTo));
					}
				}
				// apply lot (2026-09-21): accepting a SCRIPT_CONSTRAINT applies it directly (product owner's decision) -
				// an APPLIED line shows the plain-language explanation and measured effect a reviewer never gets from the
				// script source itself; a failed apply attempt leaves the line ACCEPTED with applyMessage explaining why.
				// Coordinator review: phase B (explanation/measurement) failures never revert an APPLIED row - applyMessage
				// on an APPLIED line is a WARNING about a missing part, not a failure, and reads with different wording.
				String explanation = null;
				String measuredEffect = null;
				boolean stale = false;
				String staleReason = null;
				if (ReviewLine.SCRIPT_CONSTRAINT.equals(nature))
				{
					if (ReviewLine.APPLIED.equals(status))
					{
						explanation = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.EXPLANATION);
						measuredEffect = ProposalWording.capabilityMeasuredEffect(ProposalReviews.longOf(row, FactoryPaths.CapabilitySuggestion.RECORDS_CHECKED),
							ProposalReviews.longOf(row, FactoryPaths.CapabilitySuggestion.RECORDS_REPORTED));
						// Re-apply lot (2026-09-21): a PURE decision (capability.ConstraintStaleness) from the row's
						// own stored facts alone - no MCP call, no diff of free-form model code, see that class'
						// own Javadoc for exactly what is and is not checked.
						final String importName = ProposalReviews.orElse(UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME),
							UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY));
						final ConstraintStaleness.AppliedFacts facts = new ConstraintStaleness.AppliedFacts(
							ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.CONSTRAINT_SCOPE),
							ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.APPLIED_ELEMENT_PATH),
							ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.SCRIPT_BODY), UserView.string(row, FactoryPaths.CapabilitySuggestion.BINDING),
							importName, CapabilityApplier.aliasOf(importName), UserView.string(row, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION),
							UserView.string(row, FactoryPaths.CapabilitySuggestion.SEVERITY));
						final ConstraintStaleness.Result staleness = ConstraintStaleness.evaluate(facts);
						stale = staleness.stale;
						staleReason = stale ? staleness.describe() : null;
					}
					final String applyMessage = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
					if (!applyMessage.isEmpty())
					{
						ProposalReviews.appendSpaced(rationale,
							ReviewMessages.get(pLocale, ReviewLine.APPLIED.equals(status) ? "review.capability.applyWarning" : "review.capability.applyFailed", applyMessage));
					}
				}
				else if (ReviewLine.SCRIPT_DERIVATION.equals(nature))
				{
					// script-backed-derivation lot (2026-09-23): explanation and applyMessage carry over exactly
					// like SCRIPT_CONSTRAINT (the apply-lot fields are shared, tools/build_xsd.py's own comment)
					// - never measuredEffect/staleness, which have no meaning for a derivation (quality
					// .ScriptDerivation's own Javadoc: it computes a value, it never flags a record).
					if (ReviewLine.APPLIED.equals(status))
					{
						explanation = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.EXPLANATION);
					}
					final String applyMessage = ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.APPLY_MESSAGE);
					if (!applyMessage.isEmpty())
					{
						ProposalReviews.appendSpaced(rationale,
							ReviewMessages.get(pLocale, ReviewLine.APPLIED.equals(status) ? "review.capability.applyWarning" : "review.capability.applyFailed", applyMessage));
					}
				}
				final String lineNature = ReviewLine.SCRIPT_CONSTRAINT.equals(nature) ? ReviewLine.SCRIPT_CONSTRAINT
					: ReviewLine.SCRIPT_DERIVATION.equals(nature) ? ReviewLine.SCRIPT_DERIVATION : ReviewLine.EXTERNAL_SERVICE;
				pOut.add(new ReviewLine(lineNature, row.getOccurrencePrimaryKey().format(), element, proposition,
					ProposalReviews.text(row, FactoryPaths.CapabilitySuggestion.ORIGIN), ProposalReviews.confidence(row, FactoryPaths.CapabilitySuggestion.CONFIDENCE),
					rationale.toString(), status, false, false, explanation, measuredEffect, stale, staleReason));
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Every {@code TransferFeed} row of one entity - target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): a
	 * feed is what actually loads the entity's rows from a landing table, so it is reviewed like an attribute
	 * or a mapping. Label ({@link ReviewLine#element}): {@code "<entity> <- <source system> <landing table>"},
	 * plus {@code " (child of <parent entity>)"} when the feed reads under a parent feed (sec 2.1,
	 * {@code parentFeed}) - the parent's ENTITY, resolved through the parent feed row, never assumed to be the
	 * current entity's own {@code referenceEntity} (the two are the same value by construction, but this reads
	 * the feed tree directly rather than a second, indirect path). The row's own stored rationale (why the
	 * rule/LLM pass proposed this feed) is kept first, then the mapping-by-mapping detail and, for a child feed,
	 * its {@code parentLinkField}/{@code sourceIdPipeline} are appended after it - the same "stored rationale
	 * first, computed detail appended" convention {@link #addQualityRuleLines}/{@link #addMappingStepLines}
	 * already use, never a raw replacement of the rationale text.
	 */
	private static void addFeedLines(final Adaptation pFactory, final String pEntityId, final String pEntityName, final Map<String, String> pAttributeNames,
		final Map<String, String> pLandingTableNames, final Map<String, String> pLandingTableSourceSystems, final Locale pLocale, final List<ReviewLine> pOut)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.TRANSFER_FEED), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final String landingTableId = ProposalReviews.text(row, FactoryPaths.TransferFeed.LANDING_TABLE);
				final String landingTableNameValue = pLandingTableNames.get(landingTableId);
				final String sourceSystemValue = pLandingTableSourceSystems.get(landingTableId);
				final String parentFeedId = ProposalReviews.text(row, FactoryPaths.TransferFeed.PARENT_FEED);
				String element = (pEntityName == null ? "?" : pEntityName) + " <- " + (sourceSystemValue == null ? "?" : sourceSystemValue) + " "
					+ (landingTableNameValue == null ? "?" : landingTableNameValue);
				if (!parentFeedId.isEmpty())
				{
					final Adaptation parentFeed = ProposalReviews.lookup(pFactory, FactoryPaths.TRANSFER_FEED, parentFeedId);
					final String parentEntityId = parentFeed == null ? null : UserView.string(parentFeed, FactoryPaths.TransferFeed.ENTITY);
					final Adaptation parentEntity = parentEntityId == null ? null : ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, parentEntityId);
					final String parentEntityName = parentEntity == null ? "?" : UserView.string(parentEntity, FactoryPaths.StagingEntity.NAME);
					element = element + " (child of " + parentEntityName + ")";
				}
				final List<ProposalWording.FeedMapping> mappings = ProposalReviews.readFeedMappings(pFactory, id, pAttributeNames);
				final String proposition = ProposalWording.feedProposition(mappings.size());
				final StringBuilder rationale = new StringBuilder(ProposalReviews.text(row, FactoryPaths.TransferFeed.RATIONALE));
				ProposalReviews.appendSpaced(rationale, ProposalWording.feedMappingsDetail(mappings));
				if (!parentFeedId.isEmpty())
				{
					ProposalReviews.appendSpaced(rationale, ProposalWording.feedChildDetail(ProposalReviews.text(row, FactoryPaths.TransferFeed.PARENT_LINK_FIELD),
						ProposalReviews.text(row, FactoryPaths.TransferFeed.SOURCE_ID_PIPELINE)));
				}
				pOut.add(new ReviewLine(ReviewLine.FEED, id, element, proposition, ProposalReviews.text(row, FactoryPaths.TransferFeed.ORIGIN),
					ProposalReviews.confidence(row, FactoryPaths.TransferFeed.CONFIDENCE), rationale.toString(), ProposalReviews.text(row, FactoryPaths.TransferFeed.STATUS),
					false, false));
			}
		}
		finally
		{
			rows.close();
		}
	}

	/** Every {@code AttributeMapping} row of one feed ({@code ./feed=<id>}), resolved to the plain pieces {@link ProposalWording#feedMappingsDetail} formats - landing column's bare name, the mapping's attribute name (from {@code pAttributeNames}, already scoped to this feed's entity by {@link #addFeedLines}'s own caller), and either the mapping's {@code pipeline} or its {@code transformation}/{@code argument} pair when no pipeline is set (the exact same fallback {@link #readMappings}'s own proposition text uses). */
	private static List<ProposalWording.FeedMapping> readFeedMappings(final Adaptation pFactory, final String pFeedId, final Map<String, String> pAttributeNames)
	{
		final List<ProposalWording.FeedMapping> out = new ArrayList<ProposalWording.FeedMapping>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./feed=" + CatalogReader.quote(pFeedId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Adaptation landingColumn = ProposalReviews.lookup(pFactory, FactoryPaths.LANDING_COLUMN, UserView.string(row, FactoryPaths.AttributeMapping.LANDING_COLUMN));
				final String columnName = landingColumn == null ? "?" : UserView.string(landingColumn, FactoryPaths.LandingColumn.SOURCE_COLUMN);
				final String attributeId = UserView.string(row, FactoryPaths.AttributeMapping.ATTRIBUTE);
				final String attributeName = pAttributeNames.get(attributeId);
				final String pipeline = ProposalReviews.text(row, FactoryPaths.AttributeMapping.PIPELINE);
				final String pipelineOrTransformation;
				if (!pipeline.isEmpty())
				{
					pipelineOrTransformation = pipeline;
				}
				else
				{
					final String transformation = ProposalReviews.text(row, FactoryPaths.AttributeMapping.TRANSFORMATION);
					final String argument = ProposalReviews.text(row, FactoryPaths.AttributeMapping.ARGUMENT);
					pipelineOrTransformation = argument.isEmpty() ? transformation : transformation + " (" + argument + ")";
				}
				out.add(new ProposalWording.FeedMapping(columnName, attributeName == null ? "?" : attributeName, pipelineOrTransformation));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/** Whether at least one {@code AttributeMapping} of this attribute is currently accepted-like - the "covered" input {@code quality.WithdrawalRule} needs, computed fresh (never cached) so the review screen never shows a warning that just became stale itself. */
	private static boolean attributeHasAcceptedMapping(final Adaptation pFactory, final String pAttributeId)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(UserView.string(row, FactoryPaths.AttributeMapping.STATUS)))
				{
					return true;
				}
			}
			return false;
		}
		finally
		{
			rows.close();
		}
	}

	private static void appendSpaced(final StringBuilder pText, final String pAppend)
	{
		if (pAppend == null || pAppend.isEmpty())
		{
			return;
		}
		if (pText.length() > 0)
		{
			pText.append(' ');
		}
		pText.append(pAppend);
	}

	private static int intOf(final Adaptation pRow, final Path pPath)
	{
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Number ? ((Number) value).intValue() : 0;
	}

	private static Long longOf(final Adaptation pRow, final Path pPath)
	{
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}

	/**
	 * review-match-plan lot (2026-09-22): the proposition is now a short summary ({@code
	 * review.MatchPlanView.collapsedTitle} - "N tier(s) - suspect only"/"... - may merge automatically"),
	 * never the truncated {@code requirements} JSON the screen showed before ({@code
	 * review.prop.match.requirements}) - the full requirements/evidence/autoMerge are carried on the {@link
	 * ReviewLine} itself so {@code review.ReviewPage} can render the expanded body (decision tree, evidence,
	 * simulation) via {@code review.MatchPlanView}.
	 */
	private static void readMatchPlans(final Adaptation pFactory, final String pEntityId, final String pEntityName, final Locale pLocale, final List<ReviewLine> pOut)
	{
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.MATCH_PLAN), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String id = row.getOccurrencePrimaryKey().format();
				final String requirements = ProposalReviews.text(row, FactoryPaths.MatchPlan.REQUIREMENTS);
				final boolean autoMerge = Boolean.TRUE.equals(UserView.get(row, FactoryPaths.MatchPlan.AUTO_MERGE));
				final String proposition = requirements.isEmpty() ? ReviewMessages.get(pLocale, "review.prop.match.empty")
					: MatchPlanView.collapsedTitle(requirements, autoMerge, pLocale);
				final String evidence = ProposalReviews.text(row, FactoryPaths.MatchPlan.EVIDENCE);
				// reviewComment/matchNote are deliberately null here (never resolved by this method) - the reject/
				// repropose lot attaches them afterwards, from its own separate lookup, via ReviewLine.withMatchExtras
				// (ProposalReviews.matchPlanExtras / ProposalReviewUserService.attachMatchExtras) - see docs/MATCHING.md
				// §10.5 for why the two lots deliberately never touch the same constructor call.
				pOut.add(new ReviewLine(ReviewLine.MATCH, id, pEntityName == null ? "?" : pEntityName, proposition,
					ProposalReviews.text(row, FactoryPaths.MatchPlan.ORIGIN), ProposalReviews.confidence(row, FactoryPaths.MatchPlan.CONFIDENCE),
					ProposalReviews.text(row, FactoryPaths.MatchPlan.RATIONALE), ProposalReviews.text(row, FactoryPaths.MatchPlan.STATUS), false, false, null, null, false,
					null, requirements.isEmpty() ? null : requirements, evidence.isEmpty() ? null : evidence, autoMerge, null, null));
			}
		}
		finally
		{
			rows.close();
		}
	}

	private static String text(final Adaptation pRow, final Path pPath)
	{
		final String value = UserView.string(pRow, pPath);
		return value == null ? "" : value;
	}

	/** Re-apply lot (2026-09-21): the same "preferred, or fallback when blank" rule {@code CapabilityApplier.orElse} applies at apply time - what a Re-apply of this row would actually import, for {@code capability.ConstraintStaleness}'s own message check. */
	private static String orElse(final String pPreferred, final String pFallback)
	{
		return pPreferred == null || pPreferred.trim().isEmpty() ? pFallback : pPreferred.trim();
	}

	private static Integer confidence(final Adaptation pRow, final Path pPath)
	{
		final Object value = UserView.get(pRow, pPath);
		return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
	}

	// --------------------------------------------------------------------------------------------- match extras

	/** {@code MatchPlan.reviewComment}/{@code message} of one row, read directly by id - see {@link ReviewLine#reviewComment}/{@link ReviewLine#matchNote}'s own Javadoc for why this is a separate, single-row lookup rather than an addition to {@link #readMatchPlans} (a concurrent lot, reject/repropose, 2026-09-22, extends that method for a different field - PLAN.md 2026-09-22 13:00). Empty strings (never null) on a dangling/blank row, same convention as {@link #text}. */
	public static final class MatchPlanExtras
	{
		public final String reviewComment;
		public final String message;

		MatchPlanExtras(final String pReviewComment, final String pMessage)
		{
			this.reviewComment = pReviewComment;
			this.message = pMessage;
		}
	}

	public static MatchPlanExtras matchPlanExtras(final Adaptation pFactory, final String pRowId)
	{
		final Adaptation row = ProposalReviews.byId(pFactory.getTable(FactoryPaths.MATCH_PLAN), pRowId);
		if (row == null)
		{
			return new MatchPlanExtras("", "");
		}
		return new MatchPlanExtras(ProposalReviews.text(row, FactoryPaths.MatchPlan.REVIEW_COMMENT), ProposalReviews.text(row, FactoryPaths.MatchPlan.MESSAGE));
	}

	// --------------------------------------------------------------------------------- survivorship review input

	/**
	 * Survivorship-review lot (2026-09-22, coordinator follow-up): attaches a real {@code
	 * MatchSurvivorshipBlock.Input} to every {@link ReviewLine#MATCH} line - source cards (the project's own
	 * trusted-source ranking + wizard descriptions, {@link TrustedSourceRanking#order}/{@code
	 * MatchingConfigurator#readTrustedSources}, plus per-system record counts, {@code
	 * CrossSystemMeasures#rowCountsBySystem} - ONE query per DISTINCT entity, cached by entity name across
	 * every line of that entity, never per line), the entity's structurally-identified source-key field
	 * name(s) ({@code MatchingConfigurator#readSourceKeyFieldNames}, reused verbatim - made package-visible
	 * for exactly this reuse, never a second copy), and its derived matching-key fields ({@code
	 * MatchPlanLlmSuggester#readDerivedFromByName}, same reuse rule). Deliberately NOT folded into {@link
	 * #lines} itself (same "a later lot attaches its own field separately" convention {@link
	 * ReviewLine#withMatchExtras}'s own Javadoc already established) - this needs a live {@code Session} (for
	 * the one row-count query per entity) that {@link #lines} does not take.
	 * <p>
	 * <b>Per-attribute per-system FILL RATES are never recomputed here</b> - {@code
	 * review.MatchSurvivorshipBlock} reads them straight back from each line's OWN {@link
	 * ReviewLine#matchEvidenceJson} (already parsed by {@code review.MatchPlanView} into the same {@code
	 * List<MatchPlanFieldEvidence>} block 2 uses): the measures {@code MatchPlanLlmSuggester#toLlmEntity}
	 * computes at PROPOSAL time are persisted into {@code MatchPlanEvidence.Field#filledBySystem} by {@code
	 * master.MatchPlanLlmPass#mergeSurvivorshipEvidence} already, so this method has nothing further to do for
	 * that part - exactly the "persist at proposal time, read back at render time" split the task brief asked
	 * for.
	 * <p>
	 * A project with no {@code MASTER_DATASPACE}/{@code MASTER_DATASET} set yet (no master zone), or one whose
	 * master dataset cannot be opened, degrades to source cards with no record count - never a crash, never a
	 * live query attempted against nothing. A line whose {@link ReviewLine#matchRequirementsJson} is null (not
	 * a real MATCH plan row) or whose {@code entity} cannot be resolved back to a {@code StagingEntity} id is
	 * returned with {@link MatchSurvivorshipBlock#emptyInput()} instead - the block itself renders gracefully
	 * either way (fixed sentences + generic technical rows).
	 */
	public static List<ReviewLine> attachSurvivorshipInputs(final Adaptation pFactory, final Session pSession, final String pProjectId, final List<ReviewLine> pLines)
	{
		final Adaptation project = ProposalReviews.byId(pFactory.getTable(FactoryPaths.PROJECT), pProjectId);
		if (project == null)
		{
			return pLines;
		}
		final List<TrustedSourceRanking.Source> orderedSources = TrustedSourceRanking.order(MatchingConfigurator.readTrustedSources(pFactory, pProjectId));
		final String masterDataspaceKey = UserView.string(project, FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = UserView.string(project, FactoryPaths.Project.MASTER_DATASET);
		final Adaptation masterDataset = masterDataspaceKey == null || masterDataspaceKey.trim().isEmpty() || masterDatasetName == null
			|| masterDatasetName.trim().isEmpty() ? null
				: MatchPlanLlmSuggester.openMasterDataset(pFactory.getHome().getRepository(), masterDataspaceKey, masterDatasetName);

		final List<ReviewLine> out = new ArrayList<ReviewLine>(pLines.size());
		// built at most once per ENTITY NAME (line.element) - cheap insurance against a future lot ever showing
		// more than one MATCH line per entity, never observed today
		final Map<String, MatchSurvivorshipBlock.Input> byEntity = new HashMap<String, MatchSurvivorshipBlock.Input>();
		for (final ReviewLine line : pLines)
		{
			if (!ReviewLine.MATCH.equals(line.nature) || line.matchRequirementsJson == null)
			{
				out.add(line);
				continue;
			}
			MatchSurvivorshipBlock.Input input = byEntity.get(line.element);
			if (input == null)
			{
				input = ProposalReviews.buildSurvivorshipInput(pFactory, pSession, line.rowId, line.element, orderedSources, masterDataset);
				byEntity.put(line.element, input);
			}
			out.add(ReviewLine.withSurvivorshipInput(line, input));
		}
		return out;
	}

	private static MatchSurvivorshipBlock.Input buildSurvivorshipInput(final Adaptation pFactory, final Session pSession, final String pMatchPlanRowId,
		final String pEntityName, final List<TrustedSourceRanking.Source> pOrderedSources, final Adaptation pMasterDataset)
	{
		final Adaptation plan = ProposalReviews.byId(pFactory.getTable(FactoryPaths.MATCH_PLAN), pMatchPlanRowId);
		final String entityId = plan == null ? null : UserView.string(plan, FactoryPaths.MatchPlan.ENTITY);

		final Map<String, Long> rowCountBySystem = pMasterDataset == null || !CrossSystemMeasures.isQuotable(pEntityName) ? null
			: CrossSystemMeasures.rowCountsBySystemCached(pSession, pMasterDataset, pEntityName);
		final List<MatchSurvivorshipBlock.SourceCard> sources = new ArrayList<MatchSurvivorshipBlock.SourceCard>();
		for (int i = 0; i < pOrderedSources.size(); i++)
		{
			final TrustedSourceRanking.Source source = pOrderedSources.get(i);
			final Long recordCount = rowCountBySystem == null ? null : rowCountBySystem.get(source.code);
			sources.add(new MatchSurvivorshipBlock.SourceCard(source.code, i + 1, source.description, recordCount));
		}

		// 2026-09-23 fix, docs/MATCHING.md sec 19.10: same shared resolver as configureOne/applySurvivorship/the
		// preview - a canonical entity's source key lives on its members' own attributes (notes discarded here,
		// this is a read-only display list, not a recorded PipelineStep detail).
		final List<String> sourceKeyFieldNames = entityId == null ? Collections.<String> emptyList()
			: MatchingConfigurator.readSourceKeyFieldNames(pFactory, entityId, new ArrayList<String>());
		final List<MatchSurvivorshipBlock.DerivedKeyField> derivedKeyFields = new ArrayList<MatchSurvivorshipBlock.DerivedKeyField>();
		if (entityId != null)
		{
			for (final Map.Entry<String, String> entry : MatchPlanLlmSuggester.readDerivedFromByName(pFactory, entityId).entrySet())
			{
				derivedKeyFields.add(new MatchSurvivorshipBlock.DerivedKeyField(entry.getKey(), entry.getValue()));
			}
		}

		final String requirementsJson = plan == null ? null : ProposalReviews.text(plan, FactoryPaths.MatchPlan.REQUIREMENTS);
		final String recordSelectionFallback = ProposalReviews.recordSelectionFallback(requirementsJson);
		// master-field-filter lot (2026-09-23, docs/MATCHING.md sec 26): flags any survivorship field the entity's
		// own master table does not (yet) carry - the same gap master.MasterFieldFilter silently corrects for the
		// merge policy the add-on actually receives (this class never imports master.MasterFieldFilter itself -
		// EBX-free by design - so the schema check is inlined the same way MatchingConfigurator's own
		// Predicate<String> seam is). Best-effort end to end (see the helper's own Javadoc): a missing master
		// table/zone, an unresolvable table path, or an unparsable requirements document all degrade to "nothing
		// flagged", never a failed review render.
		final Set<String> fieldsNotOnMaster = ProposalReviews.survivorshipFieldsNotOnMaster(pMasterDataset, pEntityName, requirementsJson);
		return new MatchSurvivorshipBlock.Input(sources, recordSelectionFallback, sourceKeyFieldNames, derivedKeyFields, fieldsNotOnMaster);
	}

	/**
	 * Every bare field name of {@code pRequirementsJson}'s own {@code survivorship.fields[]} that is NOT a node
	 * of {@code pEntityName}'s master table (looked up in {@code pMasterDataset}) - {@code
	 * MatchSurvivorshipBlock.Input#fieldsNotOnMaster}'s own contract. Empty (never null, never throws) when
	 * {@code pMasterDataset} is {@code null} (no master zone yet), the entity's table cannot be resolved there
	 * (an entity added after the last {@code master/generate}, or any other lookup failure), or the requirements
	 * document is blank/unparsable - a display hint only, never allowed to fail the whole review render, same
	 * discipline {@link #recordSelectionFallback} already applies to the same document.
	 */
	private static Set<String> survivorshipFieldsNotOnMaster(final Adaptation pMasterDataset, final String pEntityName, final String pRequirementsJson)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (pMasterDataset == null || pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final AdaptationTable masterTable = pMasterDataset.getTable(Path.parse(MasterModel.tablePath(pEntityName)));
			if (masterTable == null)
			{
				return out;
			}
			final JsonNode requirements = new ObjectMapper().readTree(pRequirementsJson);
			for (final JsonNode rule : requirements.path("survivorship").path("fields"))
			{
				final String field = rule.path("field").asText(null);
				if (field != null && !field.trim().isEmpty() && masterTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + field.trim())) == null)
				{
					out.add(field.trim());
				}
			}
		}
		catch (final Exception ex)
		{
			// best-effort display hint only - never fail the whole review render over a lookup/parse error here
		}
		return out;
	}

	/** {@code MatchPlan.requirements.mergePolicy.recordSelection} - the same path {@code master.MatchingPolicyTranslator} itself reads off the parsed document; parsed independently here (this class never imports {@code master}) rather than shared as a Java type, same JSON-is-the-contract discipline the rest of this codebase already applies at this exact package boundary. Null on a blank/unparsable document - {@code review.MatchSurvivorshipBlock} then shows the trusted-source-only sentence instead of a fabricated fallback word. */
	private static String recordSelectionFallback(final String pRequirementsJson)
	{
		if (pRequirementsJson == null || pRequirementsJson.trim().isEmpty())
		{
			return null;
		}
		try
		{
			final JsonNode requirements = new ObjectMapper().readTree(pRequirementsJson);
			final String value = requirements.path("mergePolicy").path("recordSelection").asText(null);
			return value == null || value.trim().isEmpty() ? null : value.trim();
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	// -------------------------------------------------------------------------------------------------- deciding

	/** What a decision (or its consistency check) came back with: whether it went through, why not, and - for an accepted entity rejection - what it now proposes cascading onto. */
	public static final class DecisionResult
	{
		public boolean ok;
		public String reason;
		/** Staging last points (2026-10-17): the bundle key and arguments of {@link #reason} when it can be worded in the viewer's language ({@link #reasonIn}); null otherwise. */
		public String reasonKey;
		public Object[] reasonArgs;
		public String elementLabel;
		public String newStatus;
		public List<ReviewRules.AffectedRow> cascade;
		/** How many {@code AttributeMapping} rows {@link MappingFollowRule} cascaded onto - non-zero only for an {@link ReviewLine#ATTRIBUTE} decision ({@link #cascadeMappings}) or a {@link ReviewLine#FEED} decision ({@link #cascadeMappingsOfFeed}, target-mapping lot 3). */
		public int mappingsFollowed;
		/** Staging-still-to-decide lot (2026-09-27): how many {@code PROPOSED} {@code QualityRule} rows an {@link ReviewLine#ATTRIBUTE} ACCEPT carried along - non-zero only when the caller asked for it ({@code carryQualityRules}). */
		public int rulesFollowed;
		/** fix/matrix-computed-field: how many {@code PROPOSED} {@code SCRIPT_DERIVATION} suggestions an {@link ReviewLine#ATTRIBUTE} ACCEPT carried along - non-zero only when the caller asked for it ({@code carryScriptDerivation}). */
		public int derivationsFollowed;
		/** Validation desk lot L5: how many {@code toBeProvided} inputs of waiting suggestions an {@link ReviewLine#ATTRIBUTE} ACCEPT bound to the accepted computed field ({@code MissingInputRebind}). */
		public int inputsBound;

		/** {@link #reason} in {@code pLocale} when the refusal has a code, else the reason as it is (English). */
		public String reasonIn(final Locale pLocale)
		{
			if (this.reasonKey == null)
			{
				return this.reason;
			}
			final String text = ReviewMessages.get(pLocale == null ? Locale.ENGLISH : pLocale, this.reasonKey, this.reasonArgs == null ? new Object[0] : this.reasonArgs);
			return this.reasonKey.equals(text) ? this.reason : text;
		}
	}

	/** {@code MatchPlan.reviewComment}'s own {@code maxLen} (`tools/build_xsd.py`) - the reject/repropose lot's own defensive cap, applied here even though the field already refuses anything longer, the same "never trust a caller" discipline {@code TextLimits} exists for. */
	private static final int REVIEW_COMMENT_MAX_LENGTH = 2000;

	/**
	 * Mapping-matrix lot (2026-09-23, docs/MAPPING-MATRIX.md): whether {@code pNature} is one of the two
	 * natures whose own row carries a {@code reviewComment} field the same way {@code MatchPlan} does
	 * ({@link ReviewLine#MAPPING}/{@link ReviewLine#FEED}) - pure (no EBX type touched), unit-tested directly.
	 */
	static boolean reviewCommentApplies(final String pNature)
	{
		return ReviewLine.MAPPING.equals(pNature) || ReviewLine.FEED.equals(pNature);
	}

	/** The {@code reviewComment} field of the table {@link #reviewCommentApplies} says {@code pNature} has one; {@code null} otherwise. */
	static Path reviewCommentField(final String pNature)
	{
		if (ReviewLine.MAPPING.equals(pNature))
		{
			return FactoryPaths.AttributeMapping.REVIEW_COMMENT;
		}
		if (ReviewLine.FEED.equals(pNature))
		{
			return FactoryPaths.TransferFeed.REVIEW_COMMENT;
		}
		return null;
	}

	/**
	 * Decides one line: re-reads the row (and whatever {@link ReviewRules} needs to see) fresh inside the
	 * procedure - never trusts what the page last rendered - validates with {@link ReviewRules}, and
	 * writes only when it says {@link ReviewRules.Decision#allowed}. Runs as {@code pSession}: see the
	 * class Javadoc for why that particular session matters.
	 */
	public static DecisionResult decide(final Session pSession, final Adaptation pFactory, final String pNature, final String pRowId, final String pTargetStatus)
	{
		return ProposalReviews.decide(pSession, pFactory, pNature, pRowId, pTargetStatus, null);
	}

	/**
	 * Reject/repropose lot (2026-09-22): the same decision, with an optional reviewer comment - only ever
	 * written when {@code pNature} is {@link ReviewLine#MATCH} and {@code pTargetStatus} is {@link
	 * ReviewLine#REJECTED} ("No matching for this entity", the only nature/target combination the review
	 * screen's own {@code mdmfComment} field is wired to - {@code review.DecisionActions.Actions#confirmReject}).
	 * A blank/null comment writes nothing extra - the ordinary 5-argument overload above is exactly this
	 * method called with {@code null}.
	 */
	public static DecisionResult decide(final Session pSession, final Adaptation pFactory, final String pNature, final String pRowId, final String pTargetStatus,
		final String pComment)
	{
		return ProposalReviews.decide(pSession, pFactory, pNature, pRowId, pTargetStatus, pComment, false);
	}

	/**
	 * Staging-still-to-decide lot (2026-09-27, project ret): the same decision; {@code pCarryQualityRules} true
	 * makes an {@link ReviewLine#ATTRIBUTE} ACCEPT also accept that field's still-{@code PROPOSED} quality rules
	 * ({@code wizard.StillToDecide#followsFieldDecision}), in the same procedure. Only step 4's "decisions left"
	 * card asks for it - that card shows such a rule as "decided with the field" and draws no button of its own,
	 * so accepting the field there must settle the rule too. Every other caller (Proposal review screen, entity
	 * cards, bulk sweeps with their confidence threshold) keeps the rule a decision of its own, exactly as
	 * before: a sub-threshold rule is never accepted behind the threshold's back. The REJECT direction needs no
	 * flag: rejecting a field already rejects its PROPOSED rules and suggestions ({@link
	 * #cascadeGuardsOfAttribute}). A rule a person already decided is never touched either way.
	 */
	public static DecisionResult decide(final Session pSession, final Adaptation pFactory, final String pNature, final String pRowId, final String pTargetStatus,
		final String pComment, final boolean pCarryQualityRules)
	{
		return ProposalReviews.decide(pSession, pFactory, pNature, pRowId, pTargetStatus, pComment, pCarryQualityRules, false);
	}

	/**
	 * fix/matrix-computed-field (docs/MAPPING-MATRIX.md sec 14): the same decision; {@code pCarryScriptDerivation} true
	 * makes an {@link ReviewLine#ATTRIBUTE} ACCEPT also accept that field's still-{@code PROPOSED} {@code
	 * SCRIPT_DERIVATION} suggestion ({@link #derivationFollowsField}), in the same procedure - the suggestion IS the
	 * computed field's own definition ({@code CapabilitySuggestion.attribute} is the NEW attribute), so the Mapping
	 * matrix offers one Accept for both. The status only, exactly like every other accept of a suggestion outside the
	 * Proposal review screen: nothing is applied to a model here (the apply step of {@code ebx.DerivationApplier} stays
	 * where it was). The REJECT direction needs no flag: rejecting a field already rejects its {@code PROPOSED}
	 * suggestions ({@link #cascadeGuardsOfAttribute}). A suggestion a person already decided is never touched.
	 */
	public static DecisionResult decide(final Session pSession, final Adaptation pFactory, final String pNature, final String pRowId, final String pTargetStatus,
		final String pComment, final boolean pCarryQualityRules, final boolean pCarryScriptDerivation)
	{
		final DecisionResult out = new DecisionResult();
		final Path tablePath = ProposalReviews.tablePathOf(pNature);
		if (tablePath == null)
		{
			out.ok = false;
			out.reason = "Unknown nature: " + pNature;
			return out;
		}
		final String comment = pComment == null ? "" : TextLimits.truncate(pComment.trim(), ProposalReviews.REVIEW_COMMENT_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				ProposalReviews.decideInProcedure(pContext, pFactory, pNature, tablePath, pRowId, pTargetStatus, comment, out);
				if (out.ok && pCarryQualityRules && ReviewLine.ATTRIBUTE.equals(pNature) && ReviewLine.ACCEPTED.equals(pTargetStatus))
				{
					out.rulesFollowed = ProposalReviews.acceptProposedRulesOfAttribute(pContext, pFactory, pRowId);
				}
				if (out.ok && pCarryScriptDerivation && ReviewLine.ATTRIBUTE.equals(pNature) && ReviewLine.ACCEPTED.equals(pTargetStatus))
				{
					out.derivationsFollowed = ProposalReviews.acceptProposedDerivationsOfAttribute(pContext, pFactory, pRowId);
				}
				// Validation desk lot L5: a computed field proposed for suggestions that wait for an input no field holds binds that input when it is accepted, wherever it is accepted from
				if (out.ok && ReviewLine.ATTRIBUTE.equals(pNature) && ReviewLine.ACCEPTED.equals(pTargetStatus))
				{
					out.inputsBound = MissingInputRebind.afterAccept(pContext, pFactory, pRowId);
				}
			}
		});
		if (result.hasFailed() && out.reason == null)
		{
			out.ok = false;
			out.reason = result.getExceptionFullMessage(Locale.ENGLISH);
		}
		return out;
	}

	private static void decideInProcedure(final ProcedureContext pContext, final Adaptation pFactory, final String pNature, final Path pTablePath, final String pRowId,
		final String pTargetStatus, final String pComment, final DecisionResult pOut) throws OperationException
	{
		final AdaptationTable table = pFactory.getTable(pTablePath);
		final Adaptation row = ProposalReviews.byId(table, pRowId);
		if (row == null)
		{
			pOut.ok = false;
			pOut.reason = ReviewMessages.get(Locale.ENGLISH, "review.notice.rowNotFound");
			return;
		}
		final String currentStatus = ProposalReviews.statusOf(pNature, row);
		// ATTRIBUTE has its own overload: a technical-key row (StagingAttribute.primaryKey) has no decision of its
		// own, whatever its status - ReviewRules.canDecide(String, boolean)'s own Javadoc / TechnicalKeyRule
		final ReviewRules.Decision transition = ReviewLine.ATTRIBUTE.equals(pNature)
			? ReviewRules.canDecide(currentStatus,
				Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.PRIMARY_KEY)) || ProposalReviews.isParentLink(pFactory, row))
			: ReviewRules.canDecide(currentStatus);
		if (!transition.allowed)
		{
			pOut.ok = false;
			pOut.reason = transition.reason;
			return;
		}
		if (ReviewLine.ACCEPTED.equals(pTargetStatus))
		{
			final ReviewRules.Decision consistency = ProposalReviews.checkAccept(pFactory, pNature, row);
			if (!consistency.allowed)
			{
				pOut.ok = false;
				pOut.reason = consistency.reason;
				pOut.reasonKey = consistency.reasonKey;
				pOut.reasonArgs = consistency.reasonArgs;
				return;
			}
		}
		ProposalReviews.writeStatus(pContext, pNature, row, pTargetStatus);
		// reject/repropose lot (2026-09-22): "No matching for this entity" is the only reject flavor that carries a
		// reviewer comment - written in the SAME procedure/context as the status write above, never a second round
		// trip. Every other nature/target combination leaves pComment unused, exactly as before this lot.
		if (ReviewLine.MATCH.equals(pNature) && ReviewLine.REJECTED.equals(pTargetStatus) && pComment != null && !pComment.isEmpty())
		{
			final ValueContextForUpdate commentValue = pContext.getContext(row.getAdaptationName());
			commentValue.setValue(pComment, FactoryPaths.MatchPlan.REVIEW_COMMENT);
			pContext.doModifyContent(row, commentValue);
		}
		// mapping-matrix lot (2026-09-23, docs/MAPPING-MATRIX.md): a MAPPING/FEED row carries the same
		// free-text reviewer comment as a MATCH plan, entered from the Mapping matrix screen's inspector -
		// written on REJECTED (same "blank writes nothing extra" rule as the MATCH case above), cleared on
		// ACCEPTED (or any other re-decision) so a later rejection reason never lingers on a row a person has
		// since accepted or re-proposed - see ProposalReviews#reviewCommentField/reviewCommentApplies (pure,
		// unit-tested in ProposalReviewsCommentTest without any EBX runtime).
		if (ProposalReviews.reviewCommentApplies(pNature))
		{
			final Path commentField = ProposalReviews.reviewCommentField(pNature);
			final String toWrite = ReviewLine.REJECTED.equals(pTargetStatus) && pComment != null && !pComment.isEmpty() ? pComment : null;
			final ValueContextForUpdate commentValue = pContext.getContext(row.getAdaptationName());
			commentValue.setValue(toWrite, commentField);
			pContext.doModifyContent(row, commentValue);
		}
		pOut.ok = true;
		pOut.newStatus = pTargetStatus;
		if (ReviewLine.ENTITY.equals(pNature))
		{
			pOut.elementLabel = UserView.string(row, FactoryPaths.StagingEntity.NAME);
		}
		// keep the rows coherent so the screens do not lie: an entity's own Accept/Reject cascades onto its technical
		// key attributes in the SAME procedure (TechnicalKeyRule - they have no decision of their own), before the
		// cascade-candidates-for-rejection lookup below, so a just-rejected technical key never shows up as "open"
		if (ReviewLine.ENTITY.equals(pNature) && (ReviewLine.ACCEPTED.equals(pTargetStatus) || ReviewLine.REJECTED.equals(pTargetStatus)))
		{
			ProposalReviews.cascadeTechnicalKey(pContext, pFactory, row.getOccurrencePrimaryKey().format(), pTargetStatus);
			// target-mapping lot 1 (docs/TARGET-MAPPING.md sec 4.1): "a PROPOSED feed follows its entity's decision
			// exactly like a mapping follows its attribute" - same MappingFollowRule, same eligibility (still
			// PROPOSED, origin RULE/LLM), just scoped to this entity's TransferFeed rows instead of one
			// attribute's AttributeMapping rows. No review-screen line or counter for this yet (lot 3's own scope,
			// docs/TARGET-MAPPING.md sec 4.3) - the cascade itself must not wait for it.
			ProposalReviews.cascadeFeeds(pContext, pFactory, row.getOccurrencePrimaryKey().format(), pTargetStatus);
			// staging-proposal-run lot (2026-09-24, task brief "rejecting a business object cascades through its
			// fields"): REJECT only - accepting an entity still leaves each of its (non-technical) fields to be
			// decided on its own, unchanged behaviour; every field this sweeps is itself cascaded exactly like an
			// ordinary attribute rejection (its own mappings, quality rules and capability suggestions follow).
			if (ReviewLine.REJECTED.equals(pTargetStatus))
			{
				ProposalReviews.cascadeAttributesOfEntity(pContext, pFactory, row.getOccurrencePrimaryKey().format());
			}
		}
		// a mapping follows its attribute (MappingFollowRule) the same way a technical key follows its entity - "supplierId comes
		// from SUPPLIER.SUPPLIER_ID, trimmed" is part of the same proposal as accepting supplierId itself, not a second decision
		if (ReviewLine.ATTRIBUTE.equals(pNature) && (ReviewLine.ACCEPTED.equals(pTargetStatus) || ReviewLine.REJECTED.equals(pTargetStatus)))
		{
			pOut.mappingsFollowed = ProposalReviews.cascadeMappings(pContext, pFactory, row.getOccurrencePrimaryKey().format(), pTargetStatus);
			// staging-proposal-run lot (2026-09-24, task brief "rejecting a FIELD also rejects its PROPOSED quality
			// rules and PROPOSED capability suggestions anchored on it"): REJECT only, same posture as every other
			// guard cascade in this method - an ACCEPTED/APPLIED guard is never touched.
			if (ReviewLine.REJECTED.equals(pTargetStatus))
			{
				ProposalReviews.cascadeGuardsOfAttribute(pContext, pFactory, row.getOccurrencePrimaryKey().format());
				// mapping matrix review queue lot (2026-09-30): a matching key derived from this field has nothing left
				// to key - rejected with it while still PROPOSED (MappingFollowRule#foldKeyFollowsRejection).
				ProposalReviews.cascadeFoldKeysOfAttribute(pContext, pFactory, row.getOccurrencePrimaryKey().format());
			}
		}
		// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): "accept/reject a feed cascades to its PROPOSED
		// mappings" - same mappingsFollowed counter as the ATTRIBUTE case above, already surfaced by the bulk-
		// accept notice ("N mapping(s) followed too") and by DecisionResult's own Javadoc, which this extends
		// rather than duplicates: a feed decision is the only other nature whose cascade lands on AttributeMapping.
		if (ReviewLine.FEED.equals(pNature) && (ReviewLine.ACCEPTED.equals(pTargetStatus) || ReviewLine.REJECTED.equals(pTargetStatus)))
		{
			pOut.mappingsFollowed = ProposalReviews.cascadeMappingsOfFeed(pContext, pFactory, row.getOccurrencePrimaryKey().format(), pTargetStatus);
		}
		// staging-proposal-run lot (2026-09-24, task brief "rejecting a mapping rejects its PROPOSED MappingSteps" /
		// "accepting a mapping that has alternativeTo rejects the mapping it replaces"): the only nature-specific
		// cascade a MAPPING row's own decision needed until now (its ATTRIBUTE-follow direction is cascadeMappings
		// above; the reverse direction - a mapping cascading onto its OWN MappingSteps/sibling - had none before).
		if (ReviewLine.MAPPING.equals(pNature))
		{
			if (ReviewLine.REJECTED.equals(pTargetStatus))
			{
				ProposalReviews.cascadeMappingStepsOfMapping(pContext, pFactory, row.getOccurrencePrimaryKey().format());
			}
			else if (ReviewLine.ACCEPTED.equals(pTargetStatus))
			{
				ProposalReviews.rejectAlternativeToOriginal(pContext, pFactory, row);
			}
		}
		if (ReviewLine.ENTITY.equals(pNature) && ReviewLine.REJECTED.equals(pTargetStatus))
		{
			pOut.cascade = ReviewRules.cascadeCandidates(ProposalReviews.attributesAndMappingsOf(pFactory, row.getOccurrencePrimaryKey().format()));
		}
	}

	/**
	 * Sets every ELIGIBLE {@code AttributeMapping} row of one attribute ({@link MappingFollowRule#isEligible}: still
	 * {@code PROPOSED}, origin {@code RULE} or {@code LLM}) to {@link MappingFollowRule#cascadedStatus(String)} of {@code
	 * pTargetStatus} - the same status the attribute itself was just decided to, {@code ACCEPTED} or {@code REJECTED}.
	 * Unlike {@link #cascadeTechnicalKey} a mapping DOES keep its own decision (a reviewer may refuse one source of a
	 * multi-source alignment without touching the others) - this only moves what {@link MappingFollowRule} says has no
	 * decision of its own YET: a person's own {@code USER} mapping, or one already accepted/rejected by anyone, is
	 * never touched. @return how many rows were actually moved, for the caller's own bulk-accept reporting.
	 */
	private static int cascadeMappings(final ProcedureContext pContext, final Adaptation pFactory, final String pAttributeId, final String pTargetStatus)
		throws OperationException
	{
		return ProposalReviews.cascadeMappingsWhere(pContext, pFactory, "./attribute=" + CatalogReader.quote(pAttributeId), pTargetStatus);
	}

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): "accept/reject a feed cascades to its {@code
	 * PROPOSED} mappings" - the SAME rule {@link #cascadeMappings} already applies to a mapping following its
	 * attribute, just scoped to the feed's own {@code AttributeMapping} rows ({@code ./feed=<id>}) instead of
	 * an attribute's. Shares {@link #cascadeMappingsWhere} with {@link #cascadeMappings} rather than a second
	 * copy of the loop - only the filter predicate differs.
	 */
	private static int cascadeMappingsOfFeed(final ProcedureContext pContext, final Adaptation pFactory, final String pFeedId, final String pTargetStatus)
		throws OperationException
	{
		return ProposalReviews.cascadeMappingsWhere(pContext, pFactory, "./feed=" + CatalogReader.quote(pFeedId), pTargetStatus);
	}

	/**
	 * Sets every ELIGIBLE {@code AttributeMapping} row matching {@code pXpathPredicate} ({@link
	 * MappingFollowRule#isEligible}: still {@code PROPOSED}, origin {@code RULE} or {@code LLM}) to {@link
	 * MappingFollowRule#cascadedStatus(String)} of {@code pTargetStatus} - the shared loop behind {@link
	 * #cascadeMappings} (scoped to one attribute) and {@link #cascadeMappingsOfFeed} (scoped to one feed). A
	 * mapping DOES keep its own decision (a reviewer may refuse one source of a multi-source alignment without
	 * touching the others) - this only moves what {@link MappingFollowRule} says has no decision of its own YET.
	 * @return how many rows were actually moved, for the caller's own bulk-accept reporting.
	 */
	private static int cascadeMappingsWhere(final ProcedureContext pContext, final Adaptation pFactory, final String pXpathPredicate, final String pTargetStatus)
		throws OperationException
	{
		final String mappingTarget = MappingFollowRule.cascadedStatus(pTargetStatus);
		if (mappingTarget == null) // defensive: pTargetStatus is always ACCEPTED or REJECTED here, both of which cascade to something
		{
			return 0;
		}
		int count = 0;
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), pXpathPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!MappingFollowRule.isEligible(UserView.string(row, FactoryPaths.AttributeMapping.STATUS), UserView.string(row, FactoryPaths.AttributeMapping.ORIGIN)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(mappingTarget, FactoryPaths.AttributeMapping.STATUS);
				pContext.doModifyContent(row, value);
				count++;
			}
		}
		finally
		{
			rows.close();
		}
		return count;
	}

	/**
	 * Sets every ELIGIBLE {@code TransferFeed} row of one entity ({@link MappingFollowRule#isEligible}: still
	 * {@code PROPOSED}, origin {@code RULE} or {@code LLM}) to {@link MappingFollowRule#cascadedStatus(String)}
	 * of {@code pTargetStatus} - target-mapping lot 1 (docs/TARGET-MAPPING.md sec 4.1), the exact same rule
	 * {@link #cascadeMappings} already applies to a mapping following its attribute, reused verbatim rather
	 * than a second copy: {@code MappingFollowRule} only ever reads a bare (status, origin) pair, which a
	 * {@code TransferFeed} row carries the same shape as an {@code AttributeMapping} row. Unlike {@link
	 * #cascadeTechnicalKey} a feed DOES keep its own decision (a reviewer may accept a canonical entity while
	 * still reviewing one member's feed on its own) - only a feed {@link MappingFollowRule#isEligible} says yes
	 * to is ever touched. No counter is returned (unlike {@link #cascadeMappings}'s {@code int}): the review
	 * screen has no feed line yet (lot 3, docs/TARGET-MAPPING.md sec 4.3) to report it on.
	 */
	private static void cascadeFeeds(final ProcedureContext pContext, final Adaptation pFactory, final String pEntityId, final String pTargetStatus)
		throws OperationException
	{
		final String feedTarget = MappingFollowRule.cascadedStatus(pTargetStatus);
		if (feedTarget == null) // defensive: pTargetStatus is always ACCEPTED or REJECTED here, both of which cascade to something
		{
			return;
		}
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.TRANSFER_FEED), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!MappingFollowRule.isEligible(UserView.string(row, FactoryPaths.TransferFeed.STATUS), UserView.string(row, FactoryPaths.TransferFeed.ORIGIN)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(feedTarget, FactoryPaths.TransferFeed.STATUS);
				pContext.doModifyContent(row, value);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Sets every technical-key ({@code primaryKey=true}) attribute of one entity to {@code pTargetStatus} -
	 * the same status the entity itself was just decided to, {@code ACCEPTED} or {@code REJECTED} - skipping
	 * a row already {@code APPLIED} (done, never touched again, same rule {@link ReviewRules#canDecide}
	 * enforces everywhere else). Unlike an ordinary decision this writes unconditionally, without going
	 * through {@link ReviewRules#canDecide(String, boolean)} - the very method that refuses a technical-key
	 * row a decision of its own - because this call IS that attribute's decision, made on its behalf by its
	 * entity, not a second, independent one.
	 */
	private static void cascadeTechnicalKey(final ProcedureContext pContext, final Adaptation pFactory, final String pEntityId, final String pTargetStatus)
		throws OperationException
	{
		final Set<String> parentLinkIds = ParentLinkReader.attributeIdsOf(pFactory, pEntityId);
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				// fix/parent-link-follows-entity: the parent link follows its entity too (ParentLinkRule)
				if (!Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.PRIMARY_KEY))
					&& !parentLinkIds.contains(row.getOccurrencePrimaryKey().format()))
				{
					continue;
				}
				if (ReviewLine.APPLIED.equals(UserView.string(row, FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pTargetStatus, FactoryPaths.StagingAttribute.STATUS);
				pContext.doModifyContent(row, value);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Staging-proposal-run lot (2026-09-24): rejecting a business object rejects every ELIGIBLE ({@link
	 * MappingFollowRule#isEligible}: still {@code PROPOSED}, origin {@code RULE}/{@code LLM}) NON-technical
	 * field of it too - {@link #cascadeTechnicalKey} already handles the technical-key ones, unconditionally,
	 * a few lines above the call site. Every field this rejects is then cascaded exactly like an ordinary
	 * attribute rejection would be: its own {@link #cascadeMappings} and {@link #cascadeGuardsOfAttribute}.
	 */
	private static void cascadeAttributesOfEntity(final ProcedureContext pContext, final Adaptation pFactory, final String pEntityId)
		throws OperationException
	{
		final List<String> rejectedAttributeIds = new ArrayList<String>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (Boolean.TRUE.equals(UserView.get(row, FactoryPaths.StagingAttribute.PRIMARY_KEY)) || ProposalReviews.isParentLink(pFactory, row))
				{
					continue; // cascadeTechnicalKey already handled it, unconditionally
				}
				if (!MappingFollowRule.isEligible(UserView.string(row, FactoryPaths.StagingAttribute.STATUS), UserView.string(row, FactoryPaths.StagingAttribute.ORIGIN)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.REJECTED, FactoryPaths.StagingAttribute.STATUS);
				pContext.doModifyContent(row, value);
				rejectedAttributeIds.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		for (final String attributeId : rejectedAttributeIds)
		{
			ProposalReviews.cascadeMappings(pContext, pFactory, attributeId, ReviewLine.REJECTED);
			ProposalReviews.cascadeGuardsOfAttribute(pContext, pFactory, attributeId);
		}
	}

	/**
	 * Mapping matrix review queue lot (2026-09-30): rejecting a FIELD also rejects the matching keys ({@code
	 * StagingAttribute.derivation = FOLD_KEY}) derived from it ({@code derivedFrom} = this field) that are still
	 * {@code PROPOSED} ({@link MappingFollowRule#foldKeyFollowsRejection}) - each then cascaded like any rejected
	 * field (its own mappings, quality rules and suggestions). An ACCEPTED/REJECTED/APPLIED key is never touched.
	 * Until then nothing carried the rejection over: {@code firstNameKey} stayed PROPOSED after {@code firstName}
	 * was rejected.
	 */
	private static void cascadeFoldKeysOfAttribute(final ProcedureContext pContext, final Adaptation pFactory, final String pAttributeId) throws OperationException
	{
		final List<String> rejectedKeyIds = new ArrayList<String>();
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./derivedFrom=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!MappingFollowRule.foldKeyFollowsRejection(ProposalReviews.text(row, FactoryPaths.StagingAttribute.DERIVATION),
					UserView.string(row, FactoryPaths.StagingAttribute.STATUS)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.REJECTED, FactoryPaths.StagingAttribute.STATUS);
				pContext.doModifyContent(row, value);
				rejectedKeyIds.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		for (final String keyId : rejectedKeyIds)
		{
			ProposalReviews.cascadeMappings(pContext, pFactory, keyId, ReviewLine.REJECTED);
			ProposalReviews.cascadeGuardsOfAttribute(pContext, pFactory, keyId);
		}
	}

	/**
	 * Staging-proposal-run lot (2026-09-24, task brief "rejecting a FIELD also rejects its PROPOSED quality
	 * rules and PROPOSED capability suggestions (SCRIPT_CONSTRAINT/EXTERNAL_SERVICE/SCRIPT_DERIVATION)
	 * anchored on it"): every still-{@code PROPOSED} {@code QualityRule}/{@code CapabilitySuggestion} row of
	 * {@code pAttributeId} is rejected - an {@code ACCEPTED}/{@code APPLIED} one is never touched (same "never
	 * touch a decided row" rule as every other cascade in this class).
	 */
	private static void cascadeGuardsOfAttribute(final ProcedureContext pContext, final Adaptation pFactory, final String pAttributeId)
		throws OperationException
	{
		ProposalReviews.rejectProposedWhere(pContext, pFactory.getTable(FactoryPaths.QUALITY_RULE), "./attribute=" + CatalogReader.quote(pAttributeId),
			FactoryPaths.QualityRule.STATUS);
		ProposalReviews.rejectProposedWhere(pContext, pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION),
			"./attribute=" + CatalogReader.quote(pAttributeId), FactoryPaths.CapabilitySuggestion.STATUS);
	}

	/**
	 * Staging-still-to-decide lot (2026-09-27): the ACCEPT counterpart of {@link #cascadeGuardsOfAttribute}'s
	 * quality-rule half, used only when the caller asked for it (see {@link #decide(Session, Adaptation, String,
	 * String, String, String, boolean)}). Moves every {@code QualityRule} of {@code pAttributeId} that {@code
	 * StillToDecide.followsFieldDecision} accepts ({@code PROPOSED} only) to {@code ACCEPTED}; severity is left as
	 * proposed (never {@code REJECT} from the model alone). Capability suggestions are deliberately NOT carried:
	 * accepting a script constraint applies it to a model, a decision of its own. @return how many rules moved.
	 */
	private static int acceptProposedRulesOfAttribute(final ProcedureContext pContext, final Adaptation pFactory, final String pAttributeId)
		throws OperationException
	{
		int moved = 0;
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.QUALITY_RULE), "./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!com.on.ebx.factory.wizard.StillToDecide.followsFieldDecision(UserView.string(row, FactoryPaths.QualityRule.STATUS)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.ACCEPTED, FactoryPaths.QualityRule.STATUS);
				pContext.doModifyContent(row, value);
				moved++;
			}
		}
		finally
		{
			rows.close();
		}
		return moved;
	}

	/**
	 * fix/matrix-computed-field: the ACCEPT counterpart of {@link #cascadeGuardsOfAttribute}'s capability-suggestion half,
	 * for the ONE nature that is the field's own definition - {@code SCRIPT_DERIVATION} (used only when the caller asked
	 * for it, see {@link #decide(Session, Adaptation, String, String, String, String, boolean, boolean)}). A {@code
	 * SCRIPT_CONSTRAINT}/{@code EXTERNAL_SERVICE} on the same field is NOT carried: accepting one applies it to a model, a
	 * decision of its own. @return how many suggestions moved.
	 */
	private static int acceptProposedDerivationsOfAttribute(final ProcedureContext pContext, final Adaptation pFactory, final String pAttributeId)
		throws OperationException
	{
		int moved = 0;
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), "./attribute=" + CatalogReader.quote(pAttributeId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!ProposalReviews.derivationFollowsField(UserView.string(row, FactoryPaths.CapabilitySuggestion.NATURE), UserView.string(row, FactoryPaths.CapabilitySuggestion.STATUS)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.ACCEPTED, FactoryPaths.CapabilitySuggestion.STATUS);
				pContext.doModifyContent(row, value);
				moved++;
			}
		}
		finally
		{
			rows.close();
		}
		return moved;
	}

	/** Whether a suggestion of this nature and status is accepted together with its field: a {@code SCRIPT_DERIVATION} still {@code PROPOSED}, nothing else (pure, unit-tested). */
	static boolean derivationFollowsField(final String pNature, final String pStatus)
	{
		return ReviewLine.SCRIPT_DERIVATION.equals(pNature) && ReviewLine.PROPOSED.equals(pStatus);
	}

	/** Staging-proposal-run lot (2026-09-24): rejecting a mapping rejects its own still-{@code PROPOSED} {@code MappingStep} rows - never an {@code ACCEPTED}/{@code APPLIED} one. */
	private static void cascadeMappingStepsOfMapping(final ProcedureContext pContext, final Adaptation pFactory, final String pMappingId)
		throws OperationException
	{
		ProposalReviews.rejectProposedWhere(pContext, pFactory.getTable(FactoryPaths.MAPPING_STEP), "./mapping=" + CatalogReader.quote(pMappingId),
			FactoryPaths.MappingStep.STATUS);
	}

	/** Every row of {@code pTable} matching {@code pXpathPredicate}, currently {@link ReviewLine#PROPOSED}, is set to {@link ReviewLine#REJECTED} - shared by every guard/step cascade above. */
	private static void rejectProposedWhere(final ProcedureContext pContext, final AdaptationTable pTable, final String pXpathPredicate,
		final Path pStatusPath) throws OperationException
	{
		final RequestResult rows = UserView.select(pTable, pXpathPredicate);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				if (!ReviewLine.PROPOSED.equals(UserView.string(row, pStatusPath)))
				{
					continue;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.REJECTED, pStatusPath);
				pContext.doModifyContent(row, value);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * Staging-proposal-run lot (2026-09-24, task brief "accepting a mapping that has alternativeTo rejects the
	 * mapping it replaces"): {@code pAcceptedRow} is the mapping JUST ACCEPTED; when it carries {@link
	 * FactoryPaths.AttributeMapping#ALTERNATIVE_TO}, the original mapping it names is rejected too (a review
	 * comment says why - same field/convention as an ordinary reviewer-entered rejection comment), UNLESS it is
	 * already {@link ReviewLine#REJECTED} (nothing to do - never re-writes an already-settled row, and never
	 * touches an {@code ACCEPTED}/{@code APPLIED} original either way: task brief says "rejects", not "forces",
	 * and a person who had already accepted the original before accepting this alternative made a choice this
	 * cascade must not silently overturn). The original's own {@code PROPOSED} {@code MappingStep} rows follow
	 * it, exactly like an ordinary mapping rejection.
	 */
	private static void rejectAlternativeToOriginal(final ProcedureContext pContext, final Adaptation pFactory, final Adaptation pAcceptedRow)
		throws OperationException
	{
		final String alternativeToId = ProposalReviews.text(pAcceptedRow, FactoryPaths.AttributeMapping.ALTERNATIVE_TO);
		if (alternativeToId.isEmpty())
		{
			return;
		}
		final Adaptation original = ProposalReviews.lookup(pFactory, FactoryPaths.ATTRIBUTE_MAPPING, alternativeToId);
		if (original == null || ReviewLine.REJECTED.equals(UserView.string(original, FactoryPaths.AttributeMapping.STATUS))
			|| ReviewLine.ACCEPTED.equals(UserView.string(original, FactoryPaths.AttributeMapping.STATUS))
			|| ReviewLine.APPLIED.equals(UserView.string(original, FactoryPaths.AttributeMapping.STATUS)))
		{
			return;
		}
		final ValueContextForUpdate value = pContext.getContext(original.getAdaptationName());
		value.setValue(ReviewLine.REJECTED, FactoryPaths.AttributeMapping.STATUS);
		value.setValue("Replaced by an accepted alternative mapping.", FactoryPaths.AttributeMapping.REVIEW_COMMENT);
		pContext.doModifyContent(original, value);
		ProposalReviews.cascadeMappingStepsOfMapping(pContext, pFactory, alternativeToId);
	}

	/** Re-reads the entity/reference-entity/relation this attribute's or mapping's ACCEPT depends on, fresh, and asks {@link ReviewRules}. Everything else is unconditional. */
	private static ReviewRules.Decision checkAccept(final Adaptation pFactory, final String pNature, final Adaptation pRow)
	{
		if (ReviewLine.ATTRIBUTE.equals(pNature))
		{
			final Adaptation entity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, UserView.string(pRow, FactoryPaths.StagingAttribute.ENTITY));
			final String entityStatus = entity == null ? null : UserView.string(entity, FactoryPaths.StagingEntity.STATUS);
			final String entityName = entity == null ? UserView.string(pRow, FactoryPaths.StagingAttribute.ENTITY) : UserView.string(entity, FactoryPaths.StagingEntity.NAME);
			final String referenceEntityId = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.REFERENCE_ENTITY);
			String referenceEntityName = null;
			String referenceEntityStatus = null;
			String relationStatus = null;
			if (!referenceEntityId.isEmpty())
			{
				final Adaptation referenceEntity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, referenceEntityId);
				referenceEntityName = referenceEntity == null ? referenceEntityId : UserView.string(referenceEntity, FactoryPaths.StagingEntity.NAME);
				referenceEntityStatus = referenceEntity == null ? null : UserView.string(referenceEntity, FactoryPaths.StagingEntity.STATUS);
				// a real foreign key (StagingAttribute.fromRelation), not a rationale text match: null here genuinely means "this
				// reference did not come from a LandingRelation" (a person entered it, or the model proposed it directly - a
				// canonical attribute from StagingLlmPass never sets referenceEntity at all, so it never reaches this branch either)
				final String relationId = ProposalReviews.text(pRow, FactoryPaths.StagingAttribute.FROM_RELATION);
				if (!relationId.isEmpty())
				{
					final Adaptation relation = ProposalReviews.lookup(pFactory, FactoryPaths.LANDING_RELATION, relationId);
					relationStatus = relation == null ? null : UserView.string(relation, FactoryPaths.LandingRelation.STATUS);
				}
			}
			return ReviewRules.canAcceptStagingAttribute(entityStatus, entityName, referenceEntityName, referenceEntityStatus, relationStatus);
		}
		if (ReviewLine.MAPPING.equals(pNature))
		{
			final Adaptation attribute = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ATTRIBUTE, UserView.string(pRow, FactoryPaths.AttributeMapping.ATTRIBUTE));
			final String attributeStatus = attribute == null ? null : UserView.string(attribute, FactoryPaths.StagingAttribute.STATUS);
			final String attributeName = attribute == null ? UserView.string(pRow, FactoryPaths.AttributeMapping.ATTRIBUTE) : UserView.string(attribute, FactoryPaths.StagingAttribute.NAME);
			return ReviewRules.canAcceptAttributeMapping(attributeStatus, attributeName);
		}
		if (ReviewLine.MAPPING_STEP.equals(pNature))
		{
			// staging last points (2026-10-17): a code table with an observed code that has rows and no target is not accepted - here, so the matrix, the Proposal review,
			// the wizard, every bulk accept and the REST decisions route are all refused the same way
			return ReviewRules.canAcceptMappingStep(UserView.string(pRow, FactoryPaths.MappingStep.KIND), ProposalReviews.text(pRow, FactoryPaths.MappingStep.ARGUMENT));
		}
		if (ReviewLine.MATCH.equals(pNature))
		{
			// lot M2 (2026-10-17, docs/MATCHING.md sect;56): the plan of a child entity may only hold SUSPECTED leaves
			return ReviewRules.canAcceptMatchPlan(ProposalReviews.entityNameOfPlan(pFactory, pRow), ProposalReviews.childParentsOfPlan(pFactory, pRow),
				ProposalReviews.text(pRow, FactoryPaths.MatchPlan.REQUIREMENTS));
		}
		// RELATION, ENTITY, FEED and the two capability-suggestion natures (SCRIPT_CONSTRAINT/EXTERNAL_SERVICE)
		// carry no extra accept precondition beyond canDecide (already checked by the caller): any non-APPLIED
		// status reads as "ok" here, so this reuses canDecide(null) rather than adding a second public factory
		// method on Decision. Accepting a capability suggestion is a decision only - nothing is attached or
		// called (task brief, capability-suggestion lot). A FEED's own accept precondition (docs/TARGET-MAPPING.md
		// sec 4.3, "Load the staging" gated on feed coverage) lives in WizardRules, not here - it blocks the
		// LOAD action, not the feed's own accept decision.
		return ReviewRules.canDecide(null);
	}

	/** Lot M2: the name of the entity a {@code MatchPlan} row is for, or the raw id when it cannot be read. */
	private static String entityNameOfPlan(final Adaptation pFactory, final Adaptation pPlanRow)
	{
		final String entityId = ProposalReviews.text(pPlanRow, FactoryPaths.MatchPlan.ENTITY);
		final Adaptation entity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, entityId);
		return entity == null ? entityId : UserView.string(entity, FactoryPaths.StagingEntity.NAME);
	}

	/**
	 * Lot M2: the parents of the entity a {@code MatchPlan} row is for when it is a child in the project's master model (a foreign key to another promoted entity), else empty.
	 * Never throws: a model that cannot be built reads as "not a child" (the configure checks again).
	 */
	static List<String> childParentsOfPlan(final Adaptation pFactory, final Adaptation pPlanRow)
	{
		try
		{
			final Adaptation entity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, ProposalReviews.text(pPlanRow, FactoryPaths.MatchPlan.ENTITY));
			if (entity == null)
			{
				return Collections.<String>emptyList();
			}
			final String projectId = UserView.string(entity, FactoryPaths.StagingEntity.PROJECT);
			final MasterModel.Result model = MasterModel.buildFromStaging(MatchingConfigurator.readEntities(pFactory, projectId, new HashMap<String, String>()));
			return ChildEntityMatching.parentsOf(model, UserView.string(entity, FactoryPaths.StagingEntity.NAME));
		}
		catch (final RuntimeException ex)
		{
			return Collections.<String>emptyList();
		}
	}

	private static List<ReviewRules.AffectedRow> attributesAndMappingsOf(final Adaptation pFactory, final String pEntityId)
	{
		final List<ReviewRules.AffectedRow> out = new ArrayList<ReviewRules.AffectedRow>();
		final RequestResult attributeRows = UserView.select(pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation attribute = attributeRows.nextAdaptation(); attribute != null; attribute = attributeRows.nextAdaptation())
			{
				final String attributeId = attribute.getOccurrencePrimaryKey().format();
				out.add(new ReviewRules.AffectedRow(ReviewLine.ATTRIBUTE, attributeId, UserView.string(attribute, FactoryPaths.StagingAttribute.NAME),
					UserView.string(attribute, FactoryPaths.StagingAttribute.STATUS)));
				final RequestResult mappingRows = UserView.select(pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING), "./attribute=" + CatalogReader.quote(attributeId));
				try
				{
					for (Adaptation mapping = mappingRows.nextAdaptation(); mapping != null; mapping = mappingRows.nextAdaptation())
					{
						out.add(new ReviewRules.AffectedRow(ReviewLine.MAPPING, mapping.getOccurrencePrimaryKey().format(),
							UserView.string(attribute, FactoryPaths.StagingAttribute.NAME), UserView.string(mapping, FactoryPaths.AttributeMapping.STATUS)));
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
		return out;
	}

	/** Applies one already-decided cascade or bulk-accept row inside its own consistency check, same rules as a single decision. Used by both bulk flows. */
	public static DecisionResult decideRef(final Session pSession, final Adaptation pFactory, final ReviewRules.AffectedRow pRef, final String pTargetStatus)
	{
		return ProposalReviews.decide(pSession, pFactory, pRef.nature, pRef.rowId, pTargetStatus);
	}

	// ------------------------------------------------------------------------------------------- match repropose

	/**
	 * Reject/repropose lot (2026-09-22): what {@code ebx.ui.ProposalReviewUserService}'s {@code REPROPOSE}
	 * action needs before calling {@code ebx.MatchPlanLlmSuggester.suggest} - the plan's own entity (to scope
	 * the language-model call to that one entity) and whether marking the row {@link ReviewLine#SUPERSEDED}
	 * itself went through.
	 */
	public static final class ReproposeStart
	{
		public boolean ok;
		public String reason;
		public String entityCode;
	}

	/**
	 * Lot M16 (docs/MATCHING.md sec 68): stores the statements of the last simulation of a plan (candidate coverage) in its {@code requirements} ({@code simulationFacts}) so that "Propose
	 * another" can give them to the model as numbered facts. Best effort: a plan that cannot be read, or whose requirements would no longer fit, is left untouched. The tree, the clustering
	 * and the weights are not touched. Lot F1: true when the statements are on the plan (stored now or already there), false when they did not fit or the write failed.
	 */
	public static boolean recordSimulationFacts(final Session pSession, final Adaptation pFactory, final String pRowId, final List<String> pFacts)
	{
		final Adaptation row = ProposalReviews.byId(pFactory.getTable(FactoryPaths.MATCH_PLAN), pRowId);
		if (row == null)
		{
			return false;
		}
		final String updated = com.on.ebx.factory.matching.CandidateCoverage.withFacts(UserView.string(row, FactoryPaths.MatchPlan.REQUIREMENTS), pFacts);
		if (updated == null || !com.on.ebx.factory.master.StoredJsonFit.fits(updated, com.on.ebx.factory.master.StoredJsonFit.REQUIREMENTS_MAX_LENGTH))
		{
			return false;
		}
		if (updated.equals(UserView.string(row, FactoryPaths.MatchPlan.REQUIREMENTS)))
		{
			return true;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(updated, FactoryPaths.MatchPlan.REQUIREMENTS);
				pContext.doModifyContent(row, value);
			}
		});
		return !result.hasFailed();
	}

	/**
	 * Step 1 of "Propose another": marks the CURRENT plan {@link ReviewLine#SUPERSEDED}, storing the
	 * reviewer's comment when one was given (a blank comment leaves any earlier comment untouched - the
	 * partial-update convention this class already follows everywhere else) - never touches {@code
	 * requirements}/{@code rationale}/{@code confidence}, so the old plan stays exactly as it was and stays
	 * acceptable at any moment (coordinator instruction, 2026-09-22: "never a hole"). Refused only when the
	 * row is {@link ReviewLine#APPLIED} ({@link ReviewRules#canDecide}) - every other status (PROPOSED/
	 * SUPERSEDED/ACCEPTED/REJECTED) may be superseded, matching what {@code review.DecisionActions
	 * .forStatus(String, String)} offers "Propose another" on.
	 */
	public static ReproposeStart startMatchRepropose(final Session pSession, final Adaptation pFactory, final String pRowId, final String pComment)
	{
		final ReproposeStart out = new ReproposeStart();
		final AdaptationTable table = pFactory.getTable(FactoryPaths.MATCH_PLAN);
		final Adaptation row = ProposalReviews.byId(table, pRowId);
		if (row == null)
		{
			out.ok = false;
			out.reason = ReviewMessages.get(Locale.ENGLISH, "review.notice.rowNotFound");
			return out;
		}
		final ReviewRules.Decision transition = ReviewRules.canDecide(UserView.string(row, FactoryPaths.MatchPlan.STATUS));
		if (!transition.allowed)
		{
			out.ok = false;
			out.reason = transition.reason;
			return out;
		}
		final String entityId = UserView.string(row, FactoryPaths.MatchPlan.ENTITY);
		final Adaptation entity = ProposalReviews.lookup(pFactory, FactoryPaths.STAGING_ENTITY, entityId);
		if (entity == null)
		{
			out.ok = false;
			out.reason = "Entity " + entityId + " of this plan no longer exists.";
			return out;
		}
		out.entityCode = UserView.string(entity, FactoryPaths.StagingEntity.NAME);
		final String comment = pComment == null ? "" : TextLimits.truncate(pComment.trim(), ProposalReviews.REVIEW_COMMENT_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.SUPERSEDED, FactoryPaths.MatchPlan.STATUS);
				if (!comment.isEmpty())
				{
					value.setValue(comment, FactoryPaths.MatchPlan.REVIEW_COMMENT);
				}
				pContext.doModifyContent(row, value);
			}
		});
		out.ok = !result.hasFailed();
		if (!out.ok)
		{
			out.reason = result.getExceptionFullMessage(Locale.ENGLISH);
		}
		return out;
	}

	/**
	 * Step 2, only when {@code ebx.MatchPlanLlmSuggester.suggest} did NOT itself write or refresh a plan for
	 * this entity - the pass skipped it, failed, or threw before reaching it: reverts the row from {@link
	 * ReviewLine#SUPERSEDED} back to {@link ReviewLine#PROPOSED}, {@code requirements}/{@code rationale}/
	 * {@code confidence}/{@code reviewComment} all untouched, with a plain-words {@code message} explaining
	 * why (case (c) of the coordinator's own outcomes list, 2026-09-22 13:20: "never a hole"). Best-effort:
	 * a failure here is logged as a refusal reason on the CALLER's own notice, never thrown further - the
	 * row would otherwise stay stuck at {@code SUPERSEDED} with no path back, which is worse than an
	 * inaccurate {@code message}.
	 */
	public static boolean revertMatchPlanToProposed(final Session pSession, final Adaptation pFactory, final String pRowId, final String pReason)
	{
		final AdaptationTable table = pFactory.getTable(FactoryPaths.MATCH_PLAN);
		final Adaptation row = ProposalReviews.byId(table, pRowId);
		if (row == null)
		{
			return false;
		}
		final String message = TextLimits.truncate(pReason == null ? "" : pReason.trim(), TextLimits.MESSAGE_MAX_LENGTH);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(ReviewLine.PROPOSED, FactoryPaths.MatchPlan.STATUS);
				value.setValue(message, FactoryPaths.MatchPlan.MESSAGE);
				pContext.doModifyContent(row, value);
			}
		});
		return !result.hasFailed();
	}

	/** Automerge-toggle lot (2026-09-23, task 1) - see {@link #setMatchPlanAutoMerge}. */
	public static final class AutoMergeResult
	{
		public boolean ok;
		public String reason;
	}

	/**
	 * Automerge-toggle lot (2026-09-23, task 1): writes {@code MatchPlan.autoMerge} for one row and nothing
	 * else - {@code status}/{@code requirements}/{@code reviewComment} all untouched, same single-field-write
	 * shape as {@link #revertMatchPlanToProposed} above. Refused (never written) unless the row is currently
	 * {@link ReviewLine#PROPOSED} or {@link ReviewLine#ACCEPTED} - the same rule {@code
	 * review.MatchPlanView#renderAutoMergeControl} uses to decide whether to show a checkbox at all, enforced
	 * again here since a stale page (or a hand-crafted request) must never be trusted to have applied it
	 * client-side. In particular an {@link ReviewLine#APPLIED} plan is always refused: the task brief is
	 * explicit that this control must never re-apply the policy on the add-on by itself (unlike {@code
	 * ebx.MatchingConfigurator.configure}'s own "compare-then-rewrite" path, which DOES pick up a changed
	 * {@code autoMerge} on an ACCEPTED plan the next time it runs - see that class's own Javadoc, "2026-09-22
	 * 23:50 - compare-then-rewrite" - re-running Configure is therefore how an APPLIED entity's automatic-merge
	 * setting is actually changed, deliberately not wired to this control in this lot).
	 */
	public static AutoMergeResult setMatchPlanAutoMerge(final Session pSession, final Adaptation pFactory, final String pRowId, final boolean pAutoMerge)
	{
		final AutoMergeResult out = new AutoMergeResult();
		final AdaptationTable table = pFactory.getTable(FactoryPaths.MATCH_PLAN);
		final Adaptation row = ProposalReviews.byId(table, pRowId);
		if (row == null)
		{
			out.ok = false;
			out.reason = ReviewMessages.get(Locale.ENGLISH, "review.notice.rowNotFound");
			return out;
		}
		final String status = UserView.string(row, FactoryPaths.MatchPlan.STATUS);
		if (!ReviewLine.PROPOSED.equals(status) && !ReviewLine.ACCEPTED.equals(status))
		{
			out.ok = false;
			out.reason = ReviewMessages.get(Locale.ENGLISH, "review.match.autoMerge.notEditable");
			return out;
		}
		if (pAutoMerge)
		{
			// lot M2: no automatic merge on the plan of a child entity that holds a MATCH leaf
			final ReviewRules.Decision child = ReviewRules.canAcceptMatchPlan(ProposalReviews.entityNameOfPlan(pFactory, row), ProposalReviews.childParentsOfPlan(pFactory, row),
				ProposalReviews.text(row, FactoryPaths.MatchPlan.REQUIREMENTS));
			if (!child.allowed)
			{
				out.ok = false;
				out.reason = child.reason;
				return out;
			}
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(Boolean.valueOf(pAutoMerge), FactoryPaths.MatchPlan.AUTO_MERGE);
				pContext.doModifyContent(row, value);
			}
		});
		out.ok = !result.hasFailed();
		if (!out.ok)
		{
			out.reason = result.getExceptionFullMessage(Locale.ENGLISH);
		}
		return out;
	}

	/**
	 * Apply lot, coordinator review (2026-09-21): "the bulk preview text must say how many constraints will be
	 * put on which model and that ONE publication per model follows". Counts, per model, the {@code
	 * SCRIPT_CONSTRAINT} candidates of a pending bulk decision - {@code StagingNames}/{@code MasterNames}, the
	 * exact naming {@code ebx.CapabilityApplier} itself uses, so this needs no MCP call, only each candidate's
	 * own stored {@code zone}. A dangling id (deleted between building the candidate list and this call) is
	 * simply skipped, same "never trusts what was true a moment ago" discipline the rest of this class follows.
	 */
	public static Map<String, Integer> capabilityModelCounts(final Adaptation pFactory, final String pProjectCode, final List<ReviewRules.AffectedRow> pCandidates)
	{
		final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
		for (final ReviewRules.AffectedRow row : pCandidates)
		{
			if (!ReviewLine.SCRIPT_CONSTRAINT.equals(row.nature))
			{
				continue;
			}
			final Adaptation suggestion = ProposalReviews.byId(pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION), row.rowId);
			if (suggestion == null)
			{
				continue;
			}
			final String zone = ProposalReviews.text(suggestion, FactoryPaths.CapabilitySuggestion.ZONE);
			final String modelName = "MASTER".equals(zone) ? com.on.ebx.factory.master.MasterNames.modelName(pProjectCode)
				: com.on.ebx.factory.staging.StagingNames.modelName(pProjectCode);
			final Integer current = out.get(modelName);
			out.put(modelName, Integer.valueOf(current == null ? 1 : current.intValue() + 1));
		}
		return out;
	}

	private static Path tablePathOf(final String pNature)
	{
		if (ReviewLine.RELATION.equals(pNature))
		{
			return FactoryPaths.LANDING_RELATION;
		}
		if (ReviewLine.ENTITY.equals(pNature))
		{
			return FactoryPaths.STAGING_ENTITY;
		}
		if (ReviewLine.ATTRIBUTE.equals(pNature))
		{
			return FactoryPaths.STAGING_ATTRIBUTE;
		}
		if (ReviewLine.MAPPING.equals(pNature))
		{
			return FactoryPaths.ATTRIBUTE_MAPPING;
		}
		if (ReviewLine.MAPPING_STEP.equals(pNature))
		{
			return FactoryPaths.MAPPING_STEP;
		}
		if (ReviewLine.QUALITY.equals(pNature))
		{
			return FactoryPaths.QUALITY_RULE;
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.EXTERNAL_SERVICE.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return FactoryPaths.CAPABILITY_SUGGESTION;
		}
		if (ReviewLine.MATCH.equals(pNature))
		{
			return FactoryPaths.MATCH_PLAN;
		}
		if (ReviewLine.FEED.equals(pNature))
		{
			return FactoryPaths.TRANSFER_FEED;
		}
		if (ReviewLine.DIFFERENCE.equals(pNature))
		{
			return FactoryPaths.DIFFERENCE;
		}
		return null;
	}

	/** Package-private (not {@code private}): {@code ebx.CapabilityApplier} reuses this exact dangling-id-safe lookup rather than a second copy of it. */
	static Adaptation byId(final AdaptationTable pTable, final String pId)
	{
		if (pId == null || pId.trim().isEmpty())
		{
			return null;
		}
		try
		{
			return UserView.byKey(pTable, PrimaryKey.parseString(pId));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static String statusOf(final String pNature, final Adaptation pRow)
	{
		if (ReviewLine.RELATION.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.LandingRelation.STATUS);
		}
		if (ReviewLine.ENTITY.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.StagingEntity.STATUS);
		}
		if (ReviewLine.ATTRIBUTE.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.StagingAttribute.STATUS);
		}
		if (ReviewLine.MAPPING.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.AttributeMapping.STATUS);
		}
		if (ReviewLine.MAPPING_STEP.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.MappingStep.STATUS);
		}
		if (ReviewLine.QUALITY.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.QualityRule.STATUS);
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.EXTERNAL_SERVICE.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.CapabilitySuggestion.STATUS);
		}
		if (ReviewLine.FEED.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.TransferFeed.STATUS);
		}
		if (ReviewLine.DIFFERENCE.equals(pNature))
		{
			return UserView.string(pRow, FactoryPaths.Difference.STATUS);
		}
		return UserView.string(pRow, FactoryPaths.MatchPlan.STATUS);
	}

	private static void writeStatus(final ProcedureContext pContext, final String pNature, final Adaptation pRow, final String pTargetStatus) throws OperationException
	{
		final ValueContextForUpdate value = pContext.getContext(pRow.getAdaptationName());
		value.setValue(pTargetStatus, ProposalReviews.statusPathOf(pNature));
		pContext.doModifyContent(pRow, value);
	}

	private static Path statusPathOf(final String pNature)
	{
		if (ReviewLine.RELATION.equals(pNature))
		{
			return FactoryPaths.LandingRelation.STATUS;
		}
		if (ReviewLine.ENTITY.equals(pNature))
		{
			return FactoryPaths.StagingEntity.STATUS;
		}
		if (ReviewLine.ATTRIBUTE.equals(pNature))
		{
			return FactoryPaths.StagingAttribute.STATUS;
		}
		if (ReviewLine.MAPPING.equals(pNature))
		{
			return FactoryPaths.AttributeMapping.STATUS;
		}
		if (ReviewLine.MAPPING_STEP.equals(pNature))
		{
			return FactoryPaths.MappingStep.STATUS;
		}
		if (ReviewLine.QUALITY.equals(pNature))
		{
			return FactoryPaths.QualityRule.STATUS;
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.EXTERNAL_SERVICE.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return FactoryPaths.CapabilitySuggestion.STATUS;
		}
		if (ReviewLine.FEED.equals(pNature))
		{
			return FactoryPaths.TransferFeed.STATUS;
		}
		if (ReviewLine.DIFFERENCE.equals(pNature))
		{
			return FactoryPaths.Difference.STATUS;
		}
		return FactoryPaths.MatchPlan.STATUS;
	}
}
