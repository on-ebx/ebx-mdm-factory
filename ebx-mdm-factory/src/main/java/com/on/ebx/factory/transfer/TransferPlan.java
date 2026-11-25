package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.landing.RelationRule;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.QualityRuleCheck;

/**
 * The landing -&gt; staging transfer plan of one project, as a pure function of its APPLIED (or already
 * {@code ACCEPTED}, per {@code TechnicalKeyRule}) entities, attributes, mappings and {@code TransferFeed}
 * rows (docs/TARGET-MAPPING.md sec 3.1, "the staging IS the target model"). No EBX class anywhere in this
 * file, same as {@code StagingModel}/{@code StagingRules}: {@code StagingLoader} is the only caller, and only
 * it reads EBX and calls Connectors' {@code MappingEngine}/{@code Transformations}.
 * <p>
 * <b>A feed is an explicit node of a mapping TREE, not an implicit (entity, landing table) grouping any
 * more</b> (target-mapping lot 1, replacing the pre-lot-1 grouping this class used to do on its own): a
 * {@code TransferFeed} row is a root feed (no parent) or a child feed (one parent, possibly a DIFFERENT
 * target entity than its parent - the person/address shape docs/TARGET-MAPPING.md sec 0 was written for). A
 * {@link MappingInfo} carries the id of the {@link TransferFeed} it belongs to, via {@link
 * MappingInfo#feed}; {@link #plan} groups mappings by that id, then wires each feed to its parent by id. A
 * mapping with NO feed id at all (a row written before this lot, {@code FeedBackfill} has not run yet on this
 * project) is grouped into a SYNTHETIC root feed keyed by (entity, landing table) - the exact grouping this
 * class used to do unconditionally - so the loader never refuses old data (docs/TARGET-MAPPING.md sec 3.1).
 * <p>
 * <b>One entity, one or several landing feeds; a feed's OWN entity, not necessarily its container's</b>: a
 * rule-proposed entity is fed by exactly one root feed; a canonical (language-model) entity that unifies
 * several rule-proposed entities is fed by one root feed per member (each landing row of each member becomes
 * its own staging row, keyed by that row's own {@code sourceSystem}/{@code sourceId} - unification across
 * sources happens later, in Match and Merge). A CHILD feed's {@link Feed#entityName} can differ from its
 * parent's (the child-entity case): {@link Entity#feeds} therefore only ever lists a feed whose OWN entity
 * matches that {@link Entity#name} AND that has no parent (a root feed) - a child feed is reached exclusively
 * through {@link Feed#children} of its parent, whatever entity that parent belongs to, and {@code
 * StagingLoader} writes the child entity's rows recursively while processing the parent's own row (never
 * through a separate top-level loop of its own) - see {@link Feed}'s own Javadoc.
 * <p>
 * <b>The foreign-key plan reuses the caller's own field name, never re-derives it</b>: {@link MappingInfo}
 * carries {@link MappingInfo#foreignKeyField}, the exact {@code &lt;attribute&gt;Ref} field name {@code
 * StagingModel.build} actually generated (with its own collision-safe uniqueness pass) for the published
 * staging model - {@code StagingLoader} gets it by calling {@code StagingModel.build} itself (read-only, no
 * EBX write) rather than this class re-deriving field-name uniqueness a second time and risking it drifting
 * out of step with what was actually published. A CHILD feed's {@code parentLinkField} is a DIFFERENT
 * mechanism entirely (see {@link Feed#parentLinkField}'s own Javadoc): it is never generated as a {@link
 * ForeignKeyPlan}, because resolving it needs no lookup at all - {@code StagingLoader} writes it straight
 * from the parent row it is recursively processing, inline, never from an already-staged row read back.
 * <p>
 * <b>A child feed's own mapping of that SAME field is marked, never turned into a {@link ForeignKeyPlan}
 * either</b> (bug fix, docs/TARGET-MAPPING.md sec 3.3/4.2, found live 2026-09-23 on project Personnes'
 * first real run): the language-model pass is free to propose a redundant rule mapping the child entity's
 * OWN link attribute from a landing column of the SAME row (e.g. {@code personne} from {@code Id}) - a
 * harmless-looking rule that used to make {@code StagingLoader} treat it as an ORDINARY foreign key,
 * looking the parent up by a lookup against the staging table, which on a first load has not been WRITTEN
 * yet (only buffered - the parent row of the exact same landing row {@code StagingLoader} is currently
 * processing), so every child row was rejected "unknown parent ...". {@link FeedBuild#toFeed()} now flags
 * any {@link FieldPlan} whose {@link MappingInfo#foreignKeyField} equals the OWNING feed's own {@link
 * Feed#parentLinkField} as {@link FieldPlan#structuralParentLink} - true only ever possible on a CHILD feed
 * (a root feed's {@link Feed#parentLinkField} is always null, so the comparison can never match there) -
 * so {@code StagingLoader} can skip the lookup entirely for that one field and let the structural write win
 * instead (see {@code StagingLoader.processRow}'s own Javadoc). A genuine two-table source, where the SAME
 * attribute is fed by its OWN root feed reading a real foreign-key column, is unaffected: that feed's
 * {@code parentLinkField} is null, so its {@link FieldPlan#structuralParentLink} is always false and the
 * ordinary lookup still runs.
 * <p>
 * <b>Load order, self references and cycles</b> (PLAN.md, task B): {@link #loadOrder} reuses {@code
 * RelationRule.loadOrder}'s own topological sort (parents before children, self references ignored - a
 * table always exists before its own rows reference each other) rather than re-deriving it; entities the
 * sort could not place because of a real multi-entity cycle are appended after every orderable entity, in
 * their original order, so every entity still gets exactly one place in the plan. A foreign-key edge is then
 * classified: <i>immediate</i> when its target entity is placed strictly BEFORE its source entity in the
 * final order (the ordinary parents-first case: the parent's staging table is fully loaded by the time the
 * child is processed, so the reference can be resolved and checked for orphans in the same pass);
 * <i>deferred</i> otherwise - a self reference (target == source) or an edge inside an unresolved cycle
 * (target not strictly earlier) - meaning {@code StagingLoader} must load the row with that one field left
 * {@code null} in its first pass, then fill it in a second pass once every entity of the group has rows to
 * reference. <b>Target-mapping lot 1</b>: every child feed also contributes a structural
 * (childEntity -&gt; parentEntity) edge to this SAME ordering (so a child-only entity's staging table is
 * still initialised, and any ORDINARY foreign key it also carries resolves against an entity load order that
 * already accounts for the feed tree) - but this structural edge never becomes a {@link ForeignKeyPlan}
 * (there is nothing to defer: see {@link Feed#parentLinkField}'s own Javadoc), so it can never appear
 * deferred or immediate in {@link Result#foreignKeys}, only in the entity ordering itself.
 */
public final class TransferPlan
{
	private TransferPlan()
	{
	}

	// --------------------------------------------------------------------------------------------------------- input

	/**
	 * Which {@link TransferFeed} (docs/TARGET-MAPPING.md sec 2.1) one {@link MappingInfo} belongs to, and -
	 * only meaningful when {@link #feedId} is set - that feed's own place in the mapping tree. A mapping with
	 * no feed at all ({@link #feedId} null - a row written before target-mapping lot 1, {@code FeedBackfill}
	 * has not run yet) reads as {@link #none()}: {@link #plan} then groups it into a SYNTHETIC root feed keyed
	 * by (entity, landing table), the same grouping this class used to do unconditionally before this lot.
	 */
	public static final class FeedRef
	{
		/** {@code TransferFeed.id}; null = pre-lot-1 row, synthetically grouped by (entity, landing table). */
		public final String feedId;
		/** Only read when {@link #feedId} is set: the PARENT {@code TransferFeed.id}, or null for a root feed. */
		public final String parentFeedId;
		/** JSON path of the child collection in the landing row document; "" (never null) means "the row itself is the one element". */
		public final String collectionPath;
		/** Child feed only: the staging field of the CHILD entity that holds the parent's staging key. Null for a root feed. */
		public final String parentLinkField;
		/** Connectors pipeline computing this feed's own {@code sourceId}; null/blank means the default of docs/TARGET-MAPPING.md sec 2.1/3.3. */
		public final String sourceIdPipeline;
		/** {@code TransferFeed.variant}: the role of one of several children of the same row (MAILING, OTHER); null otherwise. */
		public final String variant;

		public FeedRef(final String pFeedId, final String pParentFeedId, final String pCollectionPath, final String pParentLinkField, final String pSourceIdPipeline)
		{
			this(pFeedId, pParentFeedId, pCollectionPath, pParentLinkField, pSourceIdPipeline, null);
		}

		public FeedRef(final String pFeedId, final String pParentFeedId, final String pCollectionPath, final String pParentLinkField, final String pSourceIdPipeline,
			final String pVariant)
		{
			this.feedId = pFeedId;
			this.parentFeedId = pParentFeedId;
			this.collectionPath = pCollectionPath == null ? "" : pCollectionPath;
			this.parentLinkField = pParentLinkField;
			this.sourceIdPipeline = pSourceIdPipeline;
			this.variant = pVariant == null || pVariant.trim().isEmpty() ? null : pVariant.trim();
		}

		/** A mapping that carries no {@code TransferFeed} reference at all - see the class Javadoc. */
		public static FeedRef none()
		{
			return new FeedRef(null, null, "", null, null);
		}
	}

	/**
	 * One {@code AttributeMapping} row of one APPLIED-or-accepted {@code StagingAttribute}, already resolved
	 * by the caller to which landing table it reads from and what the attribute's own facets are - the two
	 * technical identity attributes ({@code sourceSystem}/{@code sourceId}) are never passed in here:
	 * {@code StagingLoader} fills them from the landing row's own {@code _sourceSystem}/{@code _sourceId}
	 * (root feed) or computes them per {@link Feed#sourceIdPipeline}'s own rule (child feed) unconditionally.
	 */
	public static final class MappingInfo
	{
		public final String entityName;
		public final String stagingField;
		/** {@code STRING}/{@code INTEGER}/{@code DECIMAL}/{@code BOOLEAN}/{@code DATE}/{@code TIMESTAMP}; blank or null reads as {@code STRING}. */
		public final String dataType;
		/** Only meaningful for {@code STRING}. */
		public final Integer maxLength;
		public final boolean mandatory;
		/** Name of the entity this attribute is a foreign key to, or null - kept even though {@link #foreignKeyField} carries the same information, for a clearer plan/report. */
		public final String referenceEntity;
		/** The exact {@code &lt;attribute&gt;Ref} field name on the staging table, as {@code StagingModel.build} generated it; null when this attribute is not a foreign key. */
		public final String foreignKeyField;
		/**
		 * A Connectors rule pipeline (e.g. {@code trim|upper}), already resolved by the caller: either the
		 * mapping's own {@code AttributeMapping.pipeline} when set, or {@code PipelineCompat.translate}'s
		 * output from its {@code transformation}/{@code argument} pair otherwise (docs/TARGET-MAPPING.md sec
		 * 2.2). Empty string (never null) means "no transformation - pass the raw value through", exactly
		 * like an empty Connectors pipeline.
		 */
		public final String pipeline;
		/** Field name on the landing table this mapping reads (a {@code LandingColumn.fieldName}) - the JSON key the row's own document carries it under (docs/TARGET-MAPPING.md sec 3.2). */
		public final String landingField;
		public final String landingDataspace;
		public final String landingDataset;
		/** Name of the real EBX landing table, e.g. {@code SUPPLIER} - what {@code /root/&lt;landingTableName&gt;} names. */
		public final String landingTableName;
		/** Written into the staging row's {@code sourceSystem} for every row of this feed (the landing row's own {@code _sourceSystem} is read at load time and MUST equal this - a defensive cross-check {@code StagingLoader} makes, not assumed here). */
		public final String sourceSystem;
		/** This mapping's ACCEPTED-or-APPLIED {@code MappingStep} pipeline, already in {@code position} order - run BEFORE {@link #pipeline} (docs/NORMALISATION.md sec 3.2). Empty (never null) for a mapping with no cleaning step. */
		public final List<CleaningSteps.Step> cleaningSteps;
		/**
		 * This attribute's ACCEPTED-or-APPLIED {@code QualityRule}s of kind {@code REFERENCE}/{@code
		 * CHECK_DIGIT}/{@code PATTERN} - never {@code MODEL_CONSTRAINT} (not executed at transfer at all) or
		 * {@code UNIQUE} (a whole-entity, whole-load concern {@code ebx.StagingLoader} runs as a separate
		 * post-pass, never per value - see {@code quality.QualityRuleCheck}'s own Javadoc). Empty (never null)
		 * for an attribute with no such rule.
		 */
		public final List<QualityRuleCheck.Rule> qualityRules;
		/** Which {@code TransferFeed} this rule belongs to - see {@link FeedRef}'s own Javadoc. */
		public final FeedRef feed;
		/** Staging preflight lot H1 (2026-10-17): the {@code AttributeMapping.id} this rule was read from, or "" when the caller did not say (a test) - the preflight names the link it found something on. Read by nothing in the plan itself. */
		public final String mappingId;

		public MappingInfo(final String pEntityName, final String pStagingField, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final String pReferenceEntity, final String pForeignKeyField, final String pPipeline, final String pLandingField, final String pLandingDataspace,
			final String pLandingDataset, final String pLandingTableName, final String pSourceSystem, final List<CleaningSteps.Step> pCleaningSteps,
			final List<QualityRuleCheck.Rule> pQualityRules, final FeedRef pFeed)
		{
			this(pEntityName, pStagingField, pDataType, pMaxLength, pMandatory, pReferenceEntity, pForeignKeyField, pPipeline, pLandingField, pLandingDataspace, pLandingDataset,
				pLandingTableName, pSourceSystem, pCleaningSteps, pQualityRules, pFeed, "");
		}

		public MappingInfo(final String pEntityName, final String pStagingField, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final String pReferenceEntity, final String pForeignKeyField, final String pPipeline, final String pLandingField, final String pLandingDataspace,
			final String pLandingDataset, final String pLandingTableName, final String pSourceSystem, final List<CleaningSteps.Step> pCleaningSteps,
			final List<QualityRuleCheck.Rule> pQualityRules, final FeedRef pFeed, final String pMappingId)
		{
			this.mappingId = pMappingId == null ? "" : pMappingId;
			this.entityName = pEntityName;
			this.stagingField = pStagingField;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.referenceEntity = pReferenceEntity;
			this.foreignKeyField = pForeignKeyField;
			this.pipeline = pPipeline == null ? "" : pPipeline;
			this.landingField = pLandingField;
			this.landingDataspace = pLandingDataspace;
			this.landingDataset = pLandingDataset;
			this.landingTableName = pLandingTableName;
			this.sourceSystem = pSourceSystem;
			this.cleaningSteps = pCleaningSteps == null ? Collections.<CleaningSteps.Step>emptyList() : pCleaningSteps;
			this.qualityRules = pQualityRules == null ? Collections.<QualityRuleCheck.Rule>emptyList() : pQualityRules;
			this.feed = pFeed == null ? FeedRef.none() : pFeed;
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** One field of one {@link Feed}: everything {@code StagingLoader} needs to run a rule and write one staging field. */
	public static final class FieldPlan
	{
		public final String stagingField;
		public final String dataType;
		public final Integer maxLength;
		public final boolean mandatory;
		/** A Connectors rule pipeline - see {@link MappingInfo#pipeline}. */
		public final String pipeline;
		/** The landing row document's own JSON key this field reads (a {@code MappingRule.sourcePath}, resolved by {@code Json.at} against the row document). */
		public final String sourcePath;
		/** Non-null when this field is ALSO a foreign key: the {@code &lt;attribute&gt;Ref} field to write alongside it. */
		public final String foreignKeyField;
		public final String targetEntityName;
		/** True when {@link #foreignKeyField} must be resolved in a second pass (self reference or an unresolved cycle) rather than while this feed is first loaded. */
		public final boolean foreignKeyDeferred;
		/**
		 * True when {@link #foreignKeyField} is the OWNING feed's own {@link Feed#parentLinkField} - i.e. this
		 * mapping is a redundant rule on the child entity's link-to-parent attribute (see the class Javadoc's
		 * own note on the bug this settles). Only ever true on a CHILD feed ({@link Feed#parentLinkField} is
		 * always null on a root feed, so the comparison this is computed from can never match there).
		 * {@code StagingLoader} must skip the ordinary foreign-key lookup (and the orphan reject) for a field
		 * flagged this way and let the structural {@code parentLinkField} write win instead - never resolve it
		 * by lookup, since the parent row it would look up is still buffered, not yet written, in the very
		 * batch that is processing this child row.
		 */
		public final boolean structuralParentLink;
		/** Run BEFORE {@link #pipeline}, on the raw landing text - see {@link MappingInfo#cleaningSteps}. Never null, possibly empty. */
		public final List<CleaningSteps.Step> cleaningSteps;
		/** Run AFTER {@link #pipeline}, on the resulting xs-string value - see {@link MappingInfo#qualityRules}. Never null, possibly empty. */
		public final List<QualityRuleCheck.Rule> qualityRules;

		FieldPlan(final String pStagingField, final String pDataType, final Integer pMaxLength, final boolean pMandatory, final String pPipeline,
			final String pSourcePath, final String pForeignKeyField, final String pTargetEntityName, final boolean pForeignKeyDeferred,
			final boolean pStructuralParentLink, final List<CleaningSteps.Step> pCleaningSteps, final List<QualityRuleCheck.Rule> pQualityRules)
		{
			this.stagingField = pStagingField;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.pipeline = pPipeline == null ? "" : pPipeline;
			this.sourcePath = pSourcePath;
			this.foreignKeyField = pForeignKeyField;
			this.targetEntityName = pTargetEntityName;
			this.foreignKeyDeferred = pForeignKeyDeferred;
			this.structuralParentLink = pStructuralParentLink;
			this.cleaningSteps = pCleaningSteps == null ? Collections.<CleaningSteps.Step>emptyList() : pCleaningSteps;
			this.qualityRules = pQualityRules == null ? Collections.<QualityRuleCheck.Rule>emptyList() : pQualityRules;
		}
	}

	/**
	 * One node of a landing -&gt; staging mapping tree (docs/TARGET-MAPPING.md sec 2.1/3.1): a root feed
	 * (({@link #parentFeedId} null) reads a landing table straight into {@link #entityName}; a child feed
	 * ({@link #parentFeedId} set) reads the SAME landing row ({@link #collectionPath} empty) or an
	 * array/object found in it, one child staging row per element, into its OWN {@link #entityName} - which
	 * can be a different entity than its parent's (the person/address shape). {@code StagingLoader} processes
	 * a root feed's landing table row by row; for each row, after writing that feed's own entity, it
	 * recurses depth-first into {@link #children}, computing each child element's document and {@code
	 * sourceId} and writing the child entity's row - all inline, in the SAME pass as the parent row, never
	 * through a later lookup.
	 */
	public static final class Feed
	{
		public final String feedId;
		/** Null for a root feed. */
		public final String parentFeedId;
		/** The TARGET entity this feed writes - not necessarily the same as its parent's (or its {@link Entity} container's, for a nested child). */
		public final String entityName;
		public final String landingDataspace;
		public final String landingDataset;
		public final String landingTableName;
		public final String sourceSystem;
		/** "" (never null): the row itself is the one element. Non-empty (not exercised by any real configuration yet - landing rows are flat today): a JSON array/object path in the row document. */
		public final String collectionPath;
		/**
		 * Child feed only (null for a root feed): the staging FIELD of THIS feed's own entity that holds the
		 * parent's staging key - the {@code <attribute>Ref} field {@code StagingModel.build} generated for
		 * this entity's {@code referenceEntity} attribute pointing at the parent entity. Deliberately NOT
		 * modelled as a {@link ForeignKeyPlan}: {@code StagingLoader} writes it directly from the parent row
		 * it is recursively processing (that row's own, just-computed primary key), which needs no database
		 * lookup and therefore no "deferred until the parent table is fully loaded" distinction at all - see
		 * the class Javadoc's own note on why this never appears in {@link Result#foreignKeys}.
		 */
		public final String parentLinkField;
		/** See {@link MappingInfo#feed}; null/blank means the default of docs/TARGET-MAPPING.md sec 2.1/3.3 ({@code StagingLoader}'s own concern, not this class's). */
		public final String sourceIdPipeline;
		/** See {@link FeedRef#variant}: when set and {@link #sourceIdPipeline} is empty, {@code StagingLoader} suffixes the child's sourceId with it. */
		public final String variant;
		public final List<FieldPlan> fields;
		/** Every feed whose {@link #parentFeedId} resolved to THIS feed's {@link #feedId}, in encountered order. Empty (never null) for a leaf feed. */
		public final List<Feed> children;

		Feed(final String pFeedId, final String pParentFeedId, final String pEntityName, final String pLandingDataspace, final String pLandingDataset,
			final String pLandingTableName, final String pSourceSystem, final String pCollectionPath, final String pParentLinkField, final String pSourceIdPipeline,
			final String pVariant, final List<FieldPlan> pFields, final List<Feed> pChildren)
		{
			this.variant = pVariant;
			this.feedId = pFeedId;
			this.parentFeedId = pParentFeedId;
			this.entityName = pEntityName;
			this.landingDataspace = pLandingDataspace;
			this.landingDataset = pLandingDataset;
			this.landingTableName = pLandingTableName;
			this.sourceSystem = pSourceSystem;
			this.collectionPath = pCollectionPath == null ? "" : pCollectionPath;
			this.parentLinkField = pParentLinkField;
			this.sourceIdPipeline = pSourceIdPipeline;
			this.fields = pFields == null ? Collections.<FieldPlan>emptyList() : pFields;
			this.children = pChildren == null ? Collections.<Feed>emptyList() : pChildren;
		}
	}

	/**
	 * One staging entity of the plan, in load order (parents before children - see the class Javadoc). {@link
	 * #feeds} lists only this entity's OWN ROOT feeds (parentFeedId null and entityName == this entity's
	 * name) - an entity fed exclusively as a CHILD of another entity's feed tree (never itself a root) still
	 * gets its own {@link Entity} here (its staging table needs initialising, its counters need a home), just
	 * with an empty {@link #feeds}: its rows are written recursively while its parent's own root feed runs,
	 * never through a top-level loop of its own - see {@link Feed}'s own Javadoc.
	 */
	public static final class Entity
	{
		public final String name;
		public final List<Feed> feeds;

		Entity(final String pName, final List<Feed> pFeeds)
		{
			this.name = pName;
			this.feeds = pFeeds;
		}
	}

	/** One foreign-key field of one entity, for the caller's own bookkeeping (the same information is also inline on the owning {@link FieldPlan}). */
	public static final class ForeignKeyPlan
	{
		public final String entityName;
		public final String attributeName;
		public final String fieldName;
		public final String targetEntityName;
		public final boolean deferred;

		ForeignKeyPlan(final String pEntityName, final String pAttributeName, final String pFieldName, final String pTargetEntityName, final boolean pDeferred)
		{
			this.entityName = pEntityName;
			this.attributeName = pAttributeName;
			this.fieldName = pFieldName;
			this.targetEntityName = pTargetEntityName;
			this.deferred = pDeferred;
		}
	}

	public static final class Result
	{
		/** Every entity that has at least one feed (root or child) somewhere in the plan, parents before children; an entity referenced only as a FK target but never itself mapped is not generated - never assumed loadable. */
		public final List<Entity> loadOrder;
		public final List<ForeignKeyPlan> foreignKeys;
		/** Entities the topological sort could not place on its own (a real multi-entity cycle, beyond a plain self reference) - still present in {@link #loadOrder}, appended at the end. */
		public final List<String> cycleEntities;
		public final List<String> notes;

		Result(final List<Entity> pLoadOrder, final List<ForeignKeyPlan> pForeignKeys, final List<String> pCycleEntities, final List<String> pNotes)
		{
			this.loadOrder = pLoadOrder;
			this.foreignKeys = pForeignKeys;
			this.cycleEntities = pCycleEntities;
			this.notes = pNotes;
		}
	}

	// --------------------------------------------------------------------------------------------------------- plan

	public static Result plan(final List<MappingInfo> pMappings)
	{
		// 1. group mappings into FeedBuilds, keyed by the feed's OWN id when it has one, else a SYNTHETIC key
		// (entity, landing table) exactly like the pre-lot-1 grouping - see FeedRef's own Javadoc.
		final Map<String, FeedBuild> byFeedKey = new LinkedHashMap<String, FeedBuild>();
		for (final MappingInfo mapping : pMappings)
		{
			final String feedKey = mapping.feed.feedId != null ? mapping.feed.feedId
				: "~" + mapping.entityName + "#" + mapping.landingDataspace + "#" + mapping.landingDataset + "#" + mapping.landingTableName;
			FeedBuild build = byFeedKey.get(feedKey);
			if (build == null)
			{
				// a synthetic feed (no real TransferFeed row) is always a root: pre-lot-1 data never had a tree
				final String parentFeedId = mapping.feed.feedId != null ? mapping.feed.parentFeedId : null;
				build = new FeedBuild(feedKey, parentFeedId, mapping.entityName, mapping.landingDataspace, mapping.landingDataset, mapping.landingTableName,
					mapping.sourceSystem, mapping.feed.collectionPath, mapping.feed.parentLinkField, mapping.feed.sourceIdPipeline, mapping.feed.variant);
				byFeedKey.put(feedKey, build);
			}
			build.mappings.add(mapping);
		}

		// 2. wire each feed to its parent by id; a child whose parent never appears in this run (the parent
		// feed itself has zero accepted mappings this run) is promoted to a root of its OWN entity instead of
		// silently dropped - noted, never crashed.
		final List<String> notes = new ArrayList<String>();
		final Set<String> attachedAsChild = new LinkedHashSet<String>();
		for (final FeedBuild build : byFeedKey.values())
		{
			if (build.parentFeedId == null)
			{
				continue;
			}
			final FeedBuild parent = byFeedKey.get(build.parentFeedId);
			if (parent == null)
			{
				notes.add("Feed " + build.feedKey + " (" + build.entityName + "): its parent feed " + build.parentFeedId
					+ " has no mapped attribute in this transfer run - treated as a root feed of " + build.entityName + ".");
				continue;
			}
			parent.children.add(build);
			attachedAsChild.add(build.feedKey);
		}

		// 3. every entity that owns at least one feed (root or child) anywhere in the plan, and the structural
		// (child -> parent) entity edges from the feed tree just wired above - see the class Javadoc's own note
		// on why a structural edge never becomes a ForeignKeyPlan. Feed objects themselves are only built once,
		// below, once deferredPairs (which needs the entity load order, which needs these edges) is known.
		final Set<String> allEntityNames = new LinkedHashSet<String>();
		final List<Edge> structuralEdges = new ArrayList<Edge>();
		for (final FeedBuild build : byFeedKey.values())
		{
			allEntityNames.add(build.entityName);
			for (final FeedBuild child : build.children)
			{
				if (!child.entityName.equals(build.entityName))
				{
					structuralEdges.add(new Edge(child.entityName, build.entityName));
				}
			}
		}

		// 4. ordinary mapping-based FK edges - unchanged from before this lot
		final List<Edge> mappingEdges = new ArrayList<Edge>();
		for (final MappingInfo mapping : pMappings)
		{
			if (mapping.foreignKeyField != null && mapping.referenceEntity != null)
			{
				if (!allEntityNames.contains(mapping.referenceEntity))
				{
					notes.add(mapping.entityName + "." + mapping.stagingField + ": referenced entity " + mapping.referenceEntity
						+ " has no mapped attribute in this transfer run - the reference will be left unresolved.");
					continue;
				}
				mappingEdges.add(new Edge(mapping.entityName, mapping.referenceEntity));
			}
		}

		final List<Edge> everyEdge = new ArrayList<Edge>(mappingEdges);
		everyEdge.addAll(structuralEdges);
		final LoadOrder order = TransferPlan.loadOrder(new ArrayList<String>(allEntityNames), everyEdge);

		// which (entityName, referenceEntity) pairs are deferred - computed once, reused for every mapping of that pair.
		// Only ever consulted for MAPPING edges (foreignKeys below): a structural edge never produces a ForeignKeyPlan,
		// so its own deferred-ness (it may well land in order.deferred too) is simply never looked up.
		final Set<String> deferredPairs = new LinkedHashSet<String>();
		for (final Edge edge : mappingEdges)
		{
			if (order.deferred.contains(edge.fromEntity + "->" + edge.toEntity))
			{
				deferredPairs.add(edge.fromEntity + "->" + edge.toEntity);
			}
		}

		final List<ForeignKeyPlan> foreignKeys = new ArrayList<ForeignKeyPlan>();
		for (final MappingInfo mapping : pMappings)
		{
			if (mapping.foreignKeyField == null || mapping.referenceEntity == null || !allEntityNames.contains(mapping.referenceEntity))
			{
				continue;
			}
			final boolean deferred = deferredPairs.contains(mapping.entityName + "->" + mapping.referenceEntity);
			foreignKeys.add(new ForeignKeyPlan(mapping.entityName, mapping.stagingField, mapping.foreignKeyField, mapping.referenceEntity, deferred));
		}
		// FieldPlan.foreignKeyDeferred must agree with the ForeignKeyPlan just built for the SAME (entity, field) -
		// computed the same way, inline, inside FeedBuild#toFeed() below via this same deferredPairs set.
		for (final FeedBuild build : byFeedKey.values())
		{
			build.deferredPairs = deferredPairs;
		}
		final Map<String, List<Feed>> rootFeedsByEntity = new LinkedHashMap<String, List<Feed>>();
		for (final FeedBuild build : byFeedKey.values())
		{
			if (attachedAsChild.contains(build.feedKey))
			{
				continue;
			}
			List<Feed> roots = rootFeedsByEntity.get(build.entityName);
			if (roots == null)
			{
				roots = new ArrayList<Feed>();
				rootFeedsByEntity.put(build.entityName, roots);
			}
			roots.add(build.toFeed());
		}

		final List<Entity> entities = new ArrayList<Entity>();
		for (final String entityName : order.order)
		{
			if (!allEntityNames.contains(entityName))
			{
				continue; // an entity only ever seen as a FK target, never itself mapped - nothing to load
			}
			final List<Feed> roots = rootFeedsByEntity.get(entityName);
			entities.add(new Entity(entityName, roots == null ? Collections.<Feed>emptyList() : roots));
		}

		return new Result(entities, foreignKeys, order.cycleEntities, notes);
	}

	/** Mutable scaffolding for one feed while {@link #plan} assembles the tree; {@link #toFeed()} freezes it into the public {@link Feed}. */
	private static final class FeedBuild
	{
		final String feedKey;
		final String parentFeedId;
		final String entityName;
		final String landingDataspace;
		final String landingDataset;
		final String landingTableName;
		final String sourceSystem;
		final String collectionPath;
		final String parentLinkField;
		final String sourceIdPipeline;
		final String variant;
		final List<MappingInfo> mappings = new ArrayList<MappingInfo>();
		final List<FeedBuild> children = new ArrayList<FeedBuild>();
		/** Set once by {@link #plan} after the entity load order is known, consumed by {@link #toFeed()}. */
		Set<String> deferredPairs = Collections.emptySet();

		FeedBuild(final String pFeedKey, final String pParentFeedId, final String pEntityName, final String pLandingDataspace, final String pLandingDataset,
			final String pLandingTableName, final String pSourceSystem, final String pCollectionPath, final String pParentLinkField, final String pSourceIdPipeline,
			final String pVariant)
		{
			this.variant = pVariant;
			this.feedKey = pFeedKey;
			this.parentFeedId = pParentFeedId;
			this.entityName = pEntityName;
			this.landingDataspace = pLandingDataspace;
			this.landingDataset = pLandingDataset;
			this.landingTableName = pLandingTableName;
			this.sourceSystem = pSourceSystem;
			this.collectionPath = pCollectionPath;
			this.parentLinkField = pParentLinkField;
			this.sourceIdPipeline = pSourceIdPipeline;
		}

		Feed toFeed()
		{
			final List<FieldPlan> fields = new ArrayList<FieldPlan>();
			for (final MappingInfo mapping : this.mappings)
			{
				final boolean deferred = mapping.foreignKeyField != null && mapping.referenceEntity != null
					&& this.deferredPairs.contains(mapping.entityName + "->" + mapping.referenceEntity);
				// see FieldPlan#structuralParentLink's own Javadoc - this.parentLinkField is null on a root feed,
				// so a root feed's identically-named attribute (the genuine two-table source case) never matches.
				final boolean structuralParentLink = mapping.foreignKeyField != null && mapping.foreignKeyField.equals(this.parentLinkField);
				fields.add(new FieldPlan(mapping.stagingField, mapping.dataType, mapping.maxLength, mapping.mandatory, mapping.pipeline, mapping.landingField,
					mapping.foreignKeyField, mapping.referenceEntity, deferred, structuralParentLink, mapping.cleaningSteps, mapping.qualityRules));
			}
			final List<Feed> childFeeds = new ArrayList<Feed>();
			for (final FeedBuild child : this.children)
			{
				childFeeds.add(child.toFeed());
			}
			return new Feed(this.feedKey, this.parentFeedId, this.entityName, this.landingDataspace, this.landingDataset, this.landingTableName, this.sourceSystem,
				this.collectionPath, this.parentLinkField, this.sourceIdPipeline, this.variant, fields, childFeeds);
		}
	}

	// ---------------------------------------------------------------------------------------------------- load order

	/** One entity -&gt; entity foreign-key edge, child to parent (same direction as {@code RelationRule.Edge}). */
	public static final class Edge
	{
		public final String fromEntity;
		public final String toEntity;

		public Edge(final String pFromEntity, final String pToEntity)
		{
			this.fromEntity = pFromEntity;
			this.toEntity = pToEntity;
		}
	}

	public static final class LoadOrder
	{
		/** Every entity name, parents before children; entities of an unresolved cycle are appended at the end, in their original relative order. */
		public final List<String> order;
		/** Subset of {@link #order}: entities the topological sort could not place on its own. Empty when every edge is a plain self reference or already acyclic. */
		public final List<String> cycleEntities;
		/** {@code "fromEntity->toEntity"} pairs whose foreign key must be resolved in a second pass: a self reference, or an edge that is not placed strictly before its source in {@link #order}. */
		public final Set<String> deferred;

		LoadOrder(final List<String> pOrder, final List<String> pCycleEntities, final Set<String> pDeferred)
		{
			this.order = pOrder;
			this.cycleEntities = pCycleEntities;
			this.deferred = pDeferred;
		}
	}

	/**
	 * Reuses {@code RelationRule.loadOrder}'s topological sort (parents before children, a self reference
	 * ignored - see its own Javadoc) rather than re-deriving it, then classifies every edge as immediate or
	 * deferred by comparing positions in the resulting order (see the class Javadoc, "Load order, self
	 * references and cycles"). Pure, independently unit-tested (self reference, a two-entity cycle, and the
	 * ordinary parents-first case).
	 */
	public static LoadOrder loadOrder(final List<String> pEntityNames, final List<Edge> pEdges)
	{
		final List<RelationRule.Edge> relationEdges = new ArrayList<RelationRule.Edge>();
		for (final Edge edge : pEdges)
		{
			relationEdges.add(new RelationRule.Edge(edge.fromEntity, edge.toEntity, edge.fromEntity.equals(edge.toEntity)));
		}
		final RelationRule.LoadOrder base = RelationRule.loadOrder(pEntityNames, relationEdges);
		final List<String> order = new ArrayList<String>(base.order);
		order.addAll(base.cycle);

		final Map<String, Integer> position = new LinkedHashMap<String, Integer>();
		for (int i = 0; i < order.size(); i++)
		{
			position.put(order.get(i), Integer.valueOf(i));
		}
		// base.cycle entities are APPENDED in their original relative order, not placed by the topological sort
		// (RelationRule.loadOrder could not resolve them at all) - two cycle members can therefore land in a
		// position that LOOKS parents-first by pure chance (e.g. B->A next to an appended [A, B]) without the
		// sort ever having verified it. Any edge touching a cycle entity, on EITHER end, must defer regardless
		// of what the positions alone would suggest.
		final Set<String> cycleSet = new LinkedHashSet<String>(base.cycle);

		final Set<String> deferred = new LinkedHashSet<String>();
		for (final Edge edge : pEdges)
		{
			final boolean selfReference = edge.fromEntity.equals(edge.toEntity);
			final boolean touchesCycle = cycleSet.contains(edge.fromEntity) || cycleSet.contains(edge.toEntity);
			final Integer fromPos = position.get(edge.fromEntity);
			final Integer toPos = position.get(edge.toEntity);
			final boolean immediate = !selfReference && !touchesCycle && fromPos != null && toPos != null && toPos.intValue() < fromPos.intValue();
			if (!immediate)
			{
				deferred.add(edge.fromEntity + "->" + edge.toEntity);
			}
		}
		return new LoadOrder(order, base.cycle, deferred);
	}
}
