package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.lineage.RecordTitleRule;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;

/**
 * The GREENFIELD master (golden zone) data model of one project, as a pure function of its already-built
 * staging entities (`docs/MASTER-ZONE.md` lots 2/3, `PLAN.md` "Zone MDM"): one table per PROMOTED
 * {@code StagingEntity}, the same {@code build_data_model} tree-plus-patch-ops shape {@link StagingModel}
 * already produces for staging - but keyed, filled and constrained differently, because a master table holds
 * both source records AND golden records the Match and Merge add-on creates, in the same table
 * (`docs/MASTER-ZONE.md` §1: {@code RecordMetadata.functionalId}/{@code goldenId} both target the business
 * table itself; confirmed live on the real {@code Companies} table, §1.2/§2.1).
 * <p>
 * <b>Which entities are promoted</b>: an entity this class is HANDED (already ACCEPTED-or-{@code APPLIED},
 * and already staged - {@link #build} takes exactly the staging-eligible entities, the same input shape
 * {@link StagingModel#build} takes) is promoted to master UNLESS it is a MEMBER of another staged entity
 * ({@code StagingEntity.memberEntities}): with {@code Company} = {@code Supplier} + {@code Account}
 * accepted, the master holds {@code Company} (and {@code Contact}), never {@code Supplier} nor
 * {@code Account} - they stay staging-only, still the natural place to inspect "what did the ERP/CRM side
 * originally say" (`docs/MASTER-ZONE.md` §2.4). This is computed here from {@link EntityInfo#memberEntities}
 * alone, unconditionally - not from {@code StagingEntity.targetTable} (a field this generator never reads or
 * writes; §2.4's own recommendation to use it was not followed literally, because the coordinator's brief for
 * this class states the membership rule directly in terms of {@code memberEntities}).
 * <p>
 * <b>Primary key: a plain auto-increment {@code id}, never {@code sourceSystem}+{@code sourceId}.</b> A
 * golden record the add-on creates by merging several source records has no single source system of its own;
 * the real {@code Companies} table's own key ({@code Identification/Id}, {@code type: "int"},
 * {@code autoIncrement: true}, sole primary key) is the add-on's own expectation for a table it manages -
 * read live, 2026-09-20, {@code describe_table} on {@code Companies}/{@code Companies}#/root/Companies:
 * <i>{@code {"name":"Id","type":"int","autoIncrement":true,...}}</i>, {@code primaryKeyFields: ["Identification/Id"]}.
 * {@code build_data_model}'s own tree does NOT lower {@code autoIncrement} (its own description lists it under
 * "NOT built from the tree - use apply_data_model_patch"; confirmed live the same day: {@code data_model_schema}'s
 * {@code settableFacets} carries {@code autoIncrementStart}/{@code autoIncrementStep} as ordinary
 * {@code set_element_property} tokens, nothing else) - so {@code id} is declared as a plain {@code int} field
 * in {@link Result#tree}'s {@code primaryKey} array (build_data_model sets {@code minOccurs=1} on a
 * primary-key field by itself, the same {@code StagingModel} already relies on), and the two auto-increment
 * facets are emitted here as {@code set_element_property} operations - once per NEW table, kept apart in
 * {@link Result#autoIncrementOps} so {@code MasterGenerator}'s regeneration path (a later-accepted entity,
 * `docs/MASTER-ZONE.md` lot 2) can still find them: {@code StagingModelDiff}'s generic diff engine only knows
 * {@code maxLength}/{@code mandatory}, never auto-increment, so a brand-new master table discovered by a
 * regeneration diff still needs these two ops appended explicitly by the caller.
 * <p>
 * <b>{@code sourceSystem}/{@code sourceId} move to plain, OPTIONAL fields</b> (never part of the primary key,
 * never mandatory): a golden the add-on auto-creates has no source system of its own and must still be
 * saveable, so these two fields can legitimately be empty on such a row. {@code Xref} keeps carrying them for
 * every SOURCE record (`tools/build_xsd.py`, {@code Xref.sourceSystem}/{@code sourceId}, both {@code min=1}
 * already). <b>No {@code uniqueConstraints} facet is emitted for the pair</b>, even though
 * {@code data_model_schema}'s {@code settableFacets} documents the token as available on a table
 * ({@code "comma-separated field names forming the tuple; ADDITIVE ... TABLES ONLY"}, read live 2026-09-20):
 * its null-tolerance (whether several rows that are BOTH empty - every golden the add-on creates - would
 * collide on it) could only be confirmed by a real write, forbidden in this lot's read-only session (CLAUDE.md
 * §8/§13: verify before trusting, do not guess). Left unset on purpose; uniqueness of a SOURCE row is instead
 * enforced the same way {@code StagingLoader} already enforces staging's own primary key: by
 * {@code ebx.MasterLoader} looking the record up through {@code Xref} before deciding insert vs. update,
 * never by scanning the table or trusting a DB-level constraint that was never verified.
 * <p>
 * <b>Business attributes: same types/lengths as staging, but NEVER mandatory.</b> A promoted attribute's own
 * {@code maxLength} is preserved (a {@code set_element_property} op, exactly like {@link StagingModel}'s),
 * but {@code mandatory} is never lowered here, regardless of what the staging attribute itself carries -
 * `docs/MASTER-ZONE.md`'s own study (§3.1) and this class's brief agree on why: a golden record the add-on
 * auto-creates by merging several source rows may end up with no value at all for a field only SOME of its
 * contributing sources ever filled in, and EBX's mandatory constraint is BLOCKING at save time (the knowledge
 * base's own "blocking vs. non-blocking" rule, CLAUDE.md §2) - a mandatory business field would then refuse
 * the very merge the add-on exists to perform. The only fields this class ever needs to be non-nullable are
 * the primary key ({@code id}, {@code minOccurs=1} from being listed in {@code primaryKey}) and whatever the
 * add-on's OWN activation later adds to its OWN metadata table ({@code RecordMetadata.functionalId}/
 * {@code autoCreated}, both mandatory there - read live, 2026-09-20, {@code describe_table} on
 * {@code Companies_Companies_MDS}/root/RecordMetadata, dataspace and dataset both named {@code Companies} on
 * that project) - neither of which this class builds; that happens inside {@code activate_matching} itself, a
 * later lot (`docs/MASTER-ZONE.md` lot 4), never called here. <b>That example name is not the general rule</b>:
 * the metadataset is one per business TABLE, {@code <masterDatasetName>_<tableLastStep>_MDS} in the master
 * dataspace (MEASURED LIVE 2026-09-22, see {@link MameMetadataset}'s own Javadoc) - {@code
 * Companies_Companies_MDS} only matches that formula here because the table's own last path step ({@code
 * Companies}) happens to equal the dataset name, by coincidence of this project's naming, not because the
 * dataspace and dataset names are what the formula actually uses.
 * <p>
 * <b>Technical fields {@code _loadId}/{@code _receivedAt} (only these two - no {@code _status}, no
 * {@code _rejectReason}).</b> Kept, per this class's own brief, unlike the study's own recommendation to drop
 * every staging-style bookkeeping field (`docs/MASTER-ZONE.md` §2.1 argued they would either duplicate
 * {@code RecordMetadata} or collide with the add-on's own {@code Linked_Field_*} projections) - the brief
 * overrides that recommendation explicitly for these two, presumably so a master record can still say when it
 * was last written without a join through {@code Xref}; {@code _status}/{@code _rejectReason} stay OUT, since
 * unlike staging a master row is never itself "rejected" (a row {@code ebx.MasterLoader} cannot write becomes
 * a {@code TransferReject} row instead, the same {@code stage = MASTER} convention staging already uses).
 * <p>
 * <b>Foreign keys, redirected to the promoted (canonical) entity - the one genuinely new mechanical piece.</b>
 * An accepted attribute whose {@code referenceEntity} names a STAGED entity that is itself a MEMBER of some
 * other staged (canonical) entity is redirected: the generated {@code <attribute>Ref} field targets the
 * CANONICAL entity's master table instead of the member's (which has none). This works because the canonical
 * entity's own staging rows are keyed by their originating MEMBER's own {@code sourceSystem}/{@code sourceId}
 * (confirmed on the real load, `PLAN.md` lot 3: 107 {@code Company} rows = 55 from {@code Supplier} + 52 from
 * {@code Account}, each keeping the member's own source key) - so the exact same key VALUE
 * {@code Contact.accountIdRef} already carries at the STAGING level is, unmodified, also the staging key of
 * the corresponding {@code Company} row; {@code ebx.MasterLoader} resolves it to a master id via one
 * {@code Xref} lookup keyed by (project, stagingTable = the REDIRECTED entity name, that same key value) -
 * never by re-deriving anything or re-reading the member's own staging table. A reference this class cannot
 * resolve at all (target neither promoted nor a member of anything promoted) is left as plain data, exactly
 * like {@link StagingModel}'s own handling of an unbuilt reference target, and reported in {@link Result#notes}.
 * <p>
 * <b>A business attribute named like one of the table's own fields is promoted under {@code <name>_src}</b>
 * ({@link MasterFieldNameRule}, 2026-09-23, `docs/MASTER-ZONE.md` &sect;13): the table's own {@code id} primary
 * key, the {@code sourceSystem}/{@code sourceId} pass-throughs and {@code _loadId}/{@code _receivedAt} are
 * compared case-insensitively against every accepted business attribute - a Salesforce {@code Id} attribute
 * (the live case, project {@code ScratchMatch}) becomes the master field {@code Id_src}, the
 * {@link FieldInfo} keeps both names so the transfer plan and loader follow, and a {@link Result#notes} line
 * says so. Without it, {@code build_data_model}'s dry run refuses the whole model
 * ({@code SubTypeAlreadyExistsException ... AspectKey[id]}) and {@code master/generate} answers 409.
 * <p>
 * <b>Never adds a Match-and-Merge-specific field.</b> {@code activate_matching} (a write tool, never called by
 * this class) is what patches {@code Association_Reverse}/{@code Linked_Field_*} onto the real {@code Companies}
 * table when matching is turned on (`docs/MASTER-ZONE.md` §1.2/§2.1); this generator's job stops at a plain
 * business table with a real primary key.
 * <p>
 * Pure: no EBX class anywhere in this file, Jackson only - unit-tested without an EBX runtime, the same
 * convention as {@link StagingModel} itself. Depends on {@code staging.StagingModel} (to compute, with
 * {@code StagingModel}'s OWN eligibility rule, exactly which of the handed-in entities actually get a staging
 * table at all - the same set this class must draw its "promoted" candidates from) and on
 * {@code staging.TechnicalKeyRule} (the shared accepted-like test) - reused rather than re-derived, so the two
 * generators can never silently disagree on which attribute counts as "accepted".
 */
public final class MasterModel
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** A master foreign-key field holds the target's plain int id, formatted as text: far shorter than staging's composite-key field, kept generous anyway. */
	private static final int FOREIGN_KEY_FIELD_MAX_LENGTH = 40;
	private static final String ID_FIELD = "id";
	private static final String SOURCE_SYSTEM = "sourceSystem";
	private static final String SOURCE_ID = "sourceId";
	private static final String LOAD_ID = "_loadId";
	private static final String RECEIVED_AT = "_receivedAt";

	private MasterModel()
	{
	}

	// --------------------------------------------------------------------------------------------------------- input

	/** Alias kept for readability at call sites: the exact same shape {@code StagingModel.EntityInfo} already is. */
	public static final class EntityInfo
	{
		public final String name;
		public final String label;
		public final String status;
		public final List<AttributeInfo> attributes;
		public final List<String> memberEntities;

		public EntityInfo(final String pName, final String pLabel, final String pStatus, final List<AttributeInfo> pAttributes, final List<String> pMemberEntities)
		{
			this.name = pName;
			this.label = pLabel;
			this.status = pStatus;
			this.attributes = pAttributes == null ? new ArrayList<AttributeInfo>() : pAttributes;
			this.memberEntities = pMemberEntities == null ? new ArrayList<String>() : pMemberEntities;
		}

		static EntityInfo of(final StagingModel.EntityInfo pStagingEntity)
		{
			final List<AttributeInfo> attributes = new ArrayList<AttributeInfo>();
			for (final StagingModel.AttributeInfo attribute : pStagingEntity.attributes)
			{
				attributes.add(AttributeInfo.of(attribute));
			}
			return new EntityInfo(pStagingEntity.name, pStagingEntity.label, pStagingEntity.status, attributes, pStagingEntity.memberEntities);
		}
	}

	public static final class AttributeInfo
	{
		public final String name;
		public final String label;
		public final String dataType;
		public final Integer maxLength;
		public final boolean primaryKey;
		public final String referenceEntity;
		public final String status;

		public AttributeInfo(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pPrimaryKey,
			final String pReferenceEntity, final String pStatus)
		{
			this.name = pName;
			this.label = pLabel;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.primaryKey = pPrimaryKey;
			this.referenceEntity = pReferenceEntity;
			this.status = pStatus;
		}

		static AttributeInfo of(final StagingModel.AttributeInfo pStagingAttribute)
		{
			return new AttributeInfo(pStagingAttribute.name, pStagingAttribute.label, pStagingAttribute.dataType, pStagingAttribute.maxLength,
				pStagingAttribute.primaryKey, pStagingAttribute.referenceEntity, pStagingAttribute.status);
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** One generated business (or source-system-pass-through) field: what {@code ebx.MasterTransferPlan} reads a value from, and what field it writes into. */
	public static final class FieldInfo
	{
		public final String entityName;
		/** Name of the STAGING field this value comes from - identical to {@link #masterField} unless {@link MasterFieldNameRule} forced a suffix (a business attribute named like the table's own {@code id}/{@code sourceSystem}/{@code sourceId}/{@code _loadId}/{@code _receivedAt}, e.g. Salesforce's {@code Id} -&gt; {@code Id_src}). */
		public final String stagingField;
		public final String masterField;
		public final String dataType;

		FieldInfo(final String pEntityName, final String pStagingField, final String pMasterField, final String pDataType)
		{
			this.entityName = pEntityName;
			this.stagingField = pStagingField;
			this.masterField = pMasterField;
			this.dataType = pDataType;
		}
	}

	/** One generated foreign-key field, already redirected to the promoted (canonical) target entity. */
	public static final class ForeignKeyInfo
	{
		public final String entityName;
		public final String attributeName;
		public final String fieldName;
		/** The PROMOTED entity name this field targets - already redirected from a member to its canonical entity when applicable. */
		public final String targetEntityName;

		ForeignKeyInfo(final String pEntityName, final String pAttributeName, final String pFieldName, final String pTargetEntityName)
		{
			this.entityName = pEntityName;
			this.attributeName = pAttributeName;
			this.fieldName = pFieldName;
			this.targetEntityName = pTargetEntityName;
		}
	}

	public static final class Result
	{
		public final ObjectNode tree;
		/** {@code set_element_property} operations for maxLength, applied once at CREATE time (see {@link #autoIncrementOps} for the id field's own two facets). */
		public final ArrayNode operations;
		/** Per entity: the two {@code autoIncrementStart}/{@code autoIncrementStep} ops for its {@code id} field - kept apart because {@code StagingModelDiff}'s generic engine cannot compute them for a table discovered by a regeneration diff. */
		public final Map<String, ArrayNode> autoIncrementOps;
		/**
		 * Per table (keyed by {@code tablePath}, e.g. {@code /root/Company} - NOT the entity name): the label
		 * TEMPLATE (e.g. {@code ${./name}}) {@link RecordTitleRule#pickDisplayFieldName} picked for that table's
		 * {@code osd:table/defaultLabel}, when an eligible display attribute was found; absent (no map entry)
		 * otherwise, exactly like {@link #autoIncrementOps} being absent for an entity with none.
		 * <p>
		 * <b>Deliberately NOT a {@code set_element_property} op, unlike every other facet this class emits</b> -
		 * MEASURED LIVE, 2026-09-22: {@code apply_data_model_patch} refused a {@code set_element_property} whose
		 * {@code property} was {@code "defaultLabel"} outright ({@code "Invalid property 'defaultLabel'. Allowed
		 * values are ... [no defaultLabel]"}), on op index 14 of 28, aborting the whole patch
		 * ({@code stoppedOnError: true}) and leaving every LATER op (ops 15-27, the rest of the Company table's own
		 * facets, autoIncrement included) unapplied - `docs/MASTER-ZONE.md` &sect;10. {@code set_element_property}'s
		 * allowed-property list is FIELD-level only; a table's default label has its own dedicated operation,
		 * {@code set_default_label} (verified live the same day, {@code list_operations(name="set_default_label")}:
		 * category {@code element}, behavior {@code ADDITIVE_RETRYABLE}, args {@code element_path} (the table's
		 * {@code /root/<Table>} path) + {@code label} (the template) - runs through {@code execute_operation}, not
		 * {@code apply_data_model_patch}. So this map carries plain data (table path -&gt; label template) for
		 * {@code ebx.MasterGenerator} to apply itself, one {@code execute_operation} call per table, AFTER a
		 * successful patch/build and BEFORE {@code publish_data_model} - never folded into {@link #operations}.
		 * {@code ebx.MasterGenerator} is expected to apply this ONLY for a genuinely NEW table (mirroring exactly
		 * how it already gates {@link #autoIncrementOps} during regeneration) - never re-applied to an
		 * already-published table, whose title convention (if any) an administrator may since have overridden by
		 * hand. Best-effort by design: a failure to set a label is cosmetic (EBX falls back to the formatted
		 * primary key, and {@code lineage.RecordTitleRule}'s own read-time fallback covers every table regardless -
		 * see that class's Javadoc), so it must never fail the whole generation.
		 */
		public final Map<String, String> defaultLabelByTable;
		public final List<String> skippedEntities;
		public final List<FieldInfo> fields;
		public final List<ForeignKeyInfo> foreignKeys;
		public final List<String> notes;
		/**
		 * Names of every promoted entity a table was actually built for - <b>parents before children</b> (2026-09-22:
		 * a stable topological sort by {@code referenceEntity}/{@link #resolveReference}, see {@code
		 * MasterModel.orderParentsBeforeChildren}'s own Javadoc for the exact algorithm and the cycle fallback).
		 * Every caller that proposes, configures or runs matching per entity ({@code ebx.MatchPlanSuggester}/
		 * {@code ebx.MatchPlanLlmSuggester} building their own entity lists in this order,
		 * {@code ebx.MatchingConfigurator#configure}/{@code #run} iterating this list directly) inherits the
		 * guarantee from here - no caller needs its own ordering logic.
		 */
		public final List<String> builtEntities;
		/** {@code "<Member>: member of <Canonical>, kept in staging only."} - one line per staged entity excluded from promotion. */
		public final List<String> keptInStagingOnly;
		/** Member entity name -&gt; its canonical entity name, for every STAGED entity's {@code memberEntities} - the same map {@link #resolveTarget} itself is built from. Exposed so {@link #resolveReference} (and, through it, every other caller that needs "does this reference reach a promoted entity") shares this ONE resolution instead of each re-deriving it - see that method's own Javadoc. */
		private final Map<String, String> memberToCanonical;

		Result(final ObjectNode pTree, final ArrayNode pOperations, final Map<String, ArrayNode> pAutoIncrementOps,
			final Map<String, String> pDefaultLabelByTable, final List<String> pSkippedEntities, final List<FieldInfo> pFields,
			final List<ForeignKeyInfo> pForeignKeys, final List<String> pNotes, final List<String> pBuiltEntities, final List<String> pKeptInStagingOnly,
			final Map<String, String> pMemberToCanonical)
		{
			this.tree = pTree;
			this.operations = pOperations;
			this.autoIncrementOps = pAutoIncrementOps;
			this.defaultLabelByTable = pDefaultLabelByTable == null ? new LinkedHashMap<String, String>() : pDefaultLabelByTable;
			this.skippedEntities = pSkippedEntities;
			this.fields = pFields;
			this.foreignKeys = pForeignKeys;
			this.notes = pNotes;
			this.builtEntities = pBuiltEntities;
			this.keptInStagingOnly = pKeptInStagingOnly;
			this.memberToCanonical = pMemberToCanonical == null ? new LinkedHashMap<String, String>() : pMemberToCanonical;
		}

		/**
		 * The ONE place outside {@link #buildTable} this promotion/redirection rule is applied - every caller
		 * that needs to know "does an attribute referencing {@code pReferenceEntity} reach a real master
		 * entity, and which one" (a child-entity check, a foreign key, anything) must call this rather than
		 * re-deriving it from {@link #builtEntities}/{@code StagingEntity.memberEntities} a second time (found
		 * the hard way, coordinator review 2026-09-21: {@code ebx.MatchPlanLlmSuggester}'s own copy of this
		 * rule only ever checked "is {@code pReferenceEntity} directly promoted", so a reference to a MEMBER
		 * entity - e.g. {@code Contact.accountId -> Account}, where {@code Account} is a member of the
		 * canonical {@code Company} - resolved to nothing, and {@code Contact} was wrongly offered as an
		 * eligible (non-child) entity).
		 * <p>
		 * Deliberately does NOT special-case a self-reference (unlike a "child entity" check must - see
		 * {@code master.MatchPlanRules#childOf}'s own Javadoc): a self-referencing FK ({@code
		 * Account.parentAccountId -> Account}) is exactly as valid a foreign key as any other reference to a
		 * promoted entity, in {@link #buildTable}'s own use of this same rule. A caller that needs "and not
		 * myself" compares the returned name against its own entity's name itself.
		 *
		 * @return the promoted entity name a reference to {@code pReferenceEntity} should target: itself if
		 * already promoted, its canonical entity if it is a redirected member (and that canonical is itself
		 * promoted), or null if neither (the reference reaches nothing in the master zone).
		 */
		public String resolveReference(final String pReferenceEntity)
		{
			return MasterModel.resolveTarget(pReferenceEntity, new LinkedHashSet<String>(this.builtEntities), this.memberToCanonical);
		}

		/**
		 * Cascade lot (2026-09-23, `docs/MATCHING.md` &sect;12.4/&sect;22): the table path of every PROMOTED entity that
		 * itself holds a foreign key TARGETING {@code pEntityName} - i.e. {@code pEntityName}'s own child
		 * entities in this project's master zone (with {@code Company}/{@code Contact}, {@code
		 * childTablePaths("Company")} answers {@code {"/root/Contact"}}, from {@code Contact.accountIdRef}).
		 * Read directly off {@link #foreignKeys} ({@code fk.entityName} = the child holding the FK field,
		 * {@code fk.targetEntityName} = the already-redirected-to-canonical parent it points to - see {@link
		 * ForeignKeyInfo}'s own Javadoc), mapped through {@link MasterModel#tablePath}. A self-reference ({@code
		 * fk.entityName.equals(fk.targetEntityName)}) is never its own child - excluded, same as {@link
		 * #orderParentsBeforeChildren} already excludes it from the dependency graph for the same reason.
		 * <p>
		 * Used by {@code ebx.MatchingConfigurator}/{@code master.RelatedTableAlignment} to decide which of a
		 * merge policy's own {@code relatedTables[]} entries (the add-on's own inverse-FK auto-additions,
		 * CLAUDE.md &sect;8) is aligned to {@code RelatedTableAlignment.WANTED} on a merge - never used to add or remove an entry,
		 * only to pick which ALREADY-PRESENT one qualifies.
		 */
		public Set<String> childTablePaths(final String pEntityName)
		{
			final Set<String> out = new LinkedHashSet<String>();
			for (final ForeignKeyInfo fk : this.foreignKeys)
			{
				if (pEntityName != null && pEntityName.equals(fk.targetEntityName) && !pEntityName.equals(fk.entityName))
				{
					out.add(MasterModel.tablePath(fk.entityName));
				}
			}
			return out;
		}
	}

	// --------------------------------------------------------------------------------------------------------- build

	/** Overload for a caller already holding {@code staging.StagingModel.EntityInfo} (every real caller): converts, then delegates to {@link #build(List)}. */
	public static Result buildFromStaging(final List<StagingModel.EntityInfo> pStagingEntities)
	{
		final List<EntityInfo> entities = new ArrayList<EntityInfo>();
		for (final StagingModel.EntityInfo entity : pStagingEntities)
		{
			entities.add(EntityInfo.of(entity));
		}
		return MasterModel.build(entities);
	}

	public static Result build(final List<EntityInfo> pEntities)
	{
		// which of the handed-in entities are even eligible for a table AT ALL: the exact same bar StagingModel
		// itself applies (accepted-like, a technical key, at least one accepted business attribute) - reusing
		// StagingModel.build here (read-only, builds nothing) keeps the two generators from ever disagreeing on
		// this eligibility test, instead of this class re-deriving it a second time
		final List<StagingModel.EntityInfo> asStagingEntities = new ArrayList<StagingModel.EntityInfo>();
		for (final EntityInfo entity : pEntities)
		{
			final List<StagingModel.AttributeInfo> attributes = new ArrayList<StagingModel.AttributeInfo>();
			for (final AttributeInfo attribute : entity.attributes)
			{
				attributes.add(new StagingModel.AttributeInfo(attribute.name, attribute.label, attribute.dataType, attribute.maxLength, false, attribute.primaryKey,
					attribute.referenceEntity, attribute.status));
			}
			asStagingEntities.add(new StagingModel.EntityInfo(entity.name, entity.label, entity.status, attributes, entity.memberEntities));
		}
		final StagingModel.Result stagingResult = StagingModel.build(asStagingEntities);
		final Set<String> stagedNames = new LinkedHashSet<String>(stagingResult.builtEntities);

		final Map<String, EntityInfo> byName = new LinkedHashMap<String, EntityInfo>();
		for (final EntityInfo entity : pEntities)
		{
			byName.put(entity.name, entity);
		}

		// member -> canonical redirection map, from every STAGED entity's memberEntities (regardless of whether
		// the canonical itself ends up promoted below - resolved defensively at FK-build time either way)
		final Map<String, String> memberToCanonical = new LinkedHashMap<String, String>();
		final List<String> keptInStagingOnly = new ArrayList<String>();
		for (final String name : stagedNames)
		{
			final EntityInfo entity = byName.get(name);
			if (entity == null)
			{
				continue;
			}
			for (final String member : entity.memberEntities)
			{
				if (stagedNames.contains(member))
				{
					memberToCanonical.put(member, name);
				}
			}
		}
		final Set<String> promoted = new LinkedHashSet<String>();
		for (final String name : stagedNames)
		{
			if (memberToCanonical.containsKey(name))
			{
				keptInStagingOnly.add(name + ": member of " + memberToCanonical.get(name) + ", kept in staging only.");
			}
			else
			{
				promoted.add(name);
			}
		}

		final ObjectNode root = MasterModel.MAPPER.createObjectNode();
		final ArrayNode tables = root.putArray("tables");
		final ArrayNode operations = MasterModel.MAPPER.createArrayNode();
		final Map<String, ArrayNode> autoIncrementOps = new LinkedHashMap<String, ArrayNode>();
		final Map<String, String> defaultLabelByTable = new LinkedHashMap<String, String>();
		final List<FieldInfo> fields = new ArrayList<FieldInfo>();
		final List<ForeignKeyInfo> foreignKeys = new ArrayList<ForeignKeyInfo>();
		final List<String> notes = new ArrayList<String>();
		final List<String> builtEntities = new ArrayList<String>();

		for (final String name : promoted)
		{
			final EntityInfo entity = byName.get(name);
			MasterModel.buildTable(entity, promoted, memberToCanonical, tables, operations, autoIncrementOps, defaultLabelByTable, fields, foreignKeys, notes);
			builtEntities.add(name);
		}

		// Parent-before-child ordering (2026-09-22, "child entities are matched too" lot): builtEntities used to be
		// exactly the iteration order of `promoted`, itself derived from stagedNames -> stagingResult.builtEntities ->
		// the CALLER's own row order (e.g. StagingEntity table read order) - nothing about that order ever guaranteed
		// a child (an entity with an accepted attribute referencing a different promoted entity, resolved through
		// resolveReference/resolveTarget the same way a real foreign key is) came after its parent. Every consumer of
		// this list (ebx.MatchPlanSuggester/ebx.MatchPlanLlmSuggester building their own entity lists in this same
		// order, ebx.MatchingConfigurator.configure/run iterating `masterModel.builtEntities` directly) now needs that
		// guarantee, since a child's own MatchPlan proposal/configuration/run may want its parent's to exist first.
		// This was previously "safe by luck" on the one real demo project (Company happened to be read before
		// Contact) - fixed here, once, for every caller, rather than in each of them separately.
		final List<String> ordered = MasterModel.orderParentsBeforeChildren(builtEntities, foreignKeys);

		return new Result(root, operations, autoIncrementOps, defaultLabelByTable, stagingResult.skippedEntities, fields, foreignKeys, notes, ordered,
			keptInStagingOnly, memberToCanonical);
	}

	/**
	 * A stable topological sort of {@code pBuiltEntities} (parents before children), using {@code pForeignKeys}'
	 * entity-&gt;target edges (self-references excluded - {@link #buildTable} already allows them, and they carry
	 * no ordering constraint) as the dependency graph. Kahn's algorithm: repeatedly emit an entity with no
	 * remaining unemitted dependency, breaking ties by {@code pBuiltEntities}' own original order (never
	 * alphabetised, never re-derived on every read - the same "stable, reproducible order" discipline
	 * {@code MatchingPolicyTranslator}'s own field ordering already documents) so an already-correct order (every
	 * project seen so far, where the parent's own {@code StagingEntity} row happened to be read first) is returned
	 * completely unchanged.
	 * <p>
	 * <b>A cycle (two promoted entities each referencing the other) is structurally possible</b> - two foreign
	 * keys can point at each other - and would make a strict topological order impossible. Rather than throw (this
	 * is a build-time, best-effort convenience, not a correctness-critical invariant the rest of the pipeline
	 * depends on to avoid a crash), any entity left over once no more zero-dependency entity can be found is
	 * appended in its ORIGINAL relative order, exactly as if this method were never called for that remaining
	 * subset - a cycle degrades to "no additional guarantee for these entities", never an exception.
	 */
	private static List<String> orderParentsBeforeChildren(final List<String> pBuiltEntities, final List<ForeignKeyInfo> pForeignKeys)
	{
		final Map<String, Set<String>> dependsOn = new LinkedHashMap<String, Set<String>>();
		for (final String name : pBuiltEntities)
		{
			dependsOn.put(name, new LinkedHashSet<String>());
		}
		for (final ForeignKeyInfo fk : pForeignKeys)
		{
			if (fk.targetEntityName != null && !fk.targetEntityName.equals(fk.entityName) && dependsOn.containsKey(fk.entityName)
				&& dependsOn.containsKey(fk.targetEntityName))
			{
				dependsOn.get(fk.entityName).add(fk.targetEntityName);
			}
		}

		final List<String> ordered = new ArrayList<String>();
		final Set<String> emitted = new LinkedHashSet<String>();
		final Set<String> remaining = new LinkedHashSet<String>(pBuiltEntities);
		boolean progressed = true;
		while (!remaining.isEmpty() && progressed)
		{
			progressed = false;
			for (final String name : pBuiltEntities)
			{
				if (!remaining.contains(name))
				{
					continue;
				}
				if (emitted.containsAll(dependsOn.get(name)))
				{
					ordered.add(name);
					emitted.add(name);
					remaining.remove(name);
					progressed = true;
				}
			}
		}
		// a cycle (progressed == false with entities still remaining): append them in their original relative
		// order, never thrown - see this method's own Javadoc
		for (final String name : pBuiltEntities)
		{
			if (remaining.contains(name))
			{
				ordered.add(name);
			}
		}
		return ordered;
	}

	private static void buildTable(final EntityInfo pEntity, final Set<String> pPromoted, final Map<String, String> pMemberToCanonical, final ArrayNode pTables,
		final ArrayNode pOperations, final Map<String, ArrayNode> pAutoIncrementOps, final Map<String, String> pDefaultLabelByTable,
		final List<FieldInfo> pFields, final List<ForeignKeyInfo> pForeignKeys, final List<String> pNotes)
	{
		final String tablePath = MasterModel.tablePath(pEntity.name);
		final ObjectNode table = pTables.addObject();
		table.put("path", tablePath);
		table.put("label", pEntity.label == null || pEntity.label.trim().isEmpty() ? pEntity.name : pEntity.label);
		final ArrayNode primaryKey = table.putArray("primaryKey");
		final ArrayNode fieldsNode = table.putArray("fields");

		final Set<String> usedNames = new LinkedHashSet<String>();
		usedNames.add(MasterModel.ID_FIELD.toLowerCase(Locale.ROOT));
		primaryKey.add(MasterModel.ID_FIELD);
		MasterModel.field(fieldsNode, tablePath, MasterModel.ID_FIELD, "int", "Id");
		final ArrayNode autoIncrementOps = MasterModel.MAPPER.createArrayNode();
		autoIncrementOps.add(MasterModel.setElementProperty(tablePath, MasterModel.ID_FIELD, "autoIncrementStart", "1"));
		autoIncrementOps.add(MasterModel.setElementProperty(tablePath, MasterModel.ID_FIELD, "autoIncrementStep", "1"));
		pAutoIncrementOps.put(pEntity.name, autoIncrementOps);
		pOperations.addAll(autoIncrementOps);

		final List<AttributeInfo> keyAttributes = new ArrayList<AttributeInfo>();
		final List<AttributeInfo> businessAttributes = new ArrayList<AttributeInfo>();
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.primaryKey)
			{
				keyAttributes.add(attribute);
			}
			else if (TechnicalKeyRule.isAcceptedLike(attribute.status))
			{
				businessAttributes.add(attribute);
			}
		}
		MasterModel.sortSourceSystemFirst(keyAttributes);

		// sourceSystem/sourceId: plain, OPTIONAL fields (never the primary key, never mandatory - see class
		// Javadoc). A key attribute's field name is the attribute's own name, never run through unique() (unlike
		// the Ref field below), exactly as StagingModel.buildTable itself never renames a plain field. A BUSINESS
		// attribute's field name goes through MasterFieldNameRule instead (2026-09-23, docs/MASTER-ZONE.md §13):
		// unchanged unless it collides, case-insensitively, with one of the fields this table carries on its own
		// (id, sourceSystem, sourceId, _loadId, _receivedAt) - the live case was a Salesforce "Id" attribute
		// against the id primary key declared a few lines above, which EBX refused at build_data_model's dry run
		// (SubTypeAlreadyExistsException ... AspectKey[id]); such an attribute is promoted as "<name>_src" and the
		// FieldInfo below carries both names, so ebx.MasterTransferPlan/ebx.MasterLoader follow the rename with no
		// change of their own. usedNames still gets every plain (master) name registered, up front, so a later Ref
		// field can be checked against ALL of them, not just the ones already processed.
		final List<String> attributeNames = new ArrayList<String>();
		for (final AttributeInfo attribute : pEntity.attributes)
		{
			attributeNames.add(attribute.name);
		}
		final Map<String, String> masterNameByAttribute = new LinkedHashMap<String, String>();
		for (final AttributeInfo attribute : keyAttributes)
		{
			usedNames.add(attribute.name.toLowerCase(Locale.ROOT));
		}
		for (final AttributeInfo attribute : businessAttributes)
		{
			final String masterName = MasterFieldNameRule.masterFieldName(attribute.name, attributeNames);
			masterNameByAttribute.put(attribute.name, masterName);
			usedNames.add(masterName.toLowerCase(Locale.ROOT));
		}
		for (final AttributeInfo attribute : keyAttributes)
		{
			MasterModel.field(fieldsNode, tablePath, attribute.name, "string", attribute.label);
			MasterModel.maxLengthOp(pOperations, tablePath, attribute.name, attribute);
			pFields.add(new FieldInfo(pEntity.name, attribute.name, attribute.name, "STRING"));
		}
		for (final AttributeInfo attribute : businessAttributes)
		{
			final String masterName = masterNameByAttribute.get(attribute.name);
			MasterModel.field(fieldsNode, tablePath, masterName, MasterModel.ebxType(attribute.dataType), attribute.label);
			MasterModel.maxLengthOp(pOperations, tablePath, masterName, attribute);
			// deliberately no mandatory op here, whatever attribute.mandatory would have said in staging - see class Javadoc
			pFields.add(new FieldInfo(pEntity.name, attribute.name, masterName, attribute.dataType));
			if (!masterName.equals(attribute.name))
			{
				pNotes.add(pEntity.name + "." + attribute.name + ": promoted as master field " + masterName + " - its name collides (case-insensitively) with the '"
					+ MasterModel.reservedNameFor(attribute.name) + "' field every master table carries; the transfer mapping and the master loader follow the rename.");
			}

			if (attribute.referenceEntity != null)
			{
				final String target = MasterModel.resolveTarget(attribute.referenceEntity, pPromoted, pMemberToCanonical);
				if (target == null)
				{
					pNotes.add(pEntity.name + "." + attribute.name + ": referenced entity " + attribute.referenceEntity
						+ " is not promoted to master (and unifies into nothing that is): kept as plain data, no foreign key created.");
				}
				else
				{
					final String refFieldName = MasterModel.unique(attribute.name + "Ref", usedNames);
					final String targetTablePath = MasterModel.tablePath(target);
					MasterModel.foreignKeyField(fieldsNode, tablePath, refFieldName, attribute.label, targetTablePath);
					pOperations.add(MasterModel.setElementProperty(tablePath, refFieldName, "maxLength", String.valueOf(MasterModel.FOREIGN_KEY_FIELD_MAX_LENGTH)));
					pForeignKeys.add(new ForeignKeyInfo(pEntity.name, attribute.name, refFieldName, target));
				}
			}
		}

		MasterModel.field(fieldsNode, tablePath, MasterModel.LOAD_ID, "string", "Load");
		MasterModel.field(fieldsNode, tablePath, MasterModel.RECEIVED_AT, "dateTime", "Received");

		// osd:table/defaultLabel: a generated master table otherwise carries none, so EBX's own
		// Adaptation.getLabel(Session) echoes the formatted PRIMARY KEY back as the record's "label" - the
		// record-lineage screen's own read-time fallback (RecordTitleRule, ebx.RecordLineageReader#masterLabel)
		// covers this for every table regardless, but pointing a NEW table's defaultLabel at the same display
		// attribute directly is the real fix: every OTHER EBX screen (the plain record form's title bar, a
		// foreign-key picker elsewhere in this same master model, Data Services' own record listings) benefits
		// too, not just this one screen. Same three-tier name priority as RecordTitleRule.pickTitle, applied to
		// NAMES only (no record exists yet at model-build time) via RecordTitleRule#pickDisplayFieldName -
		// restricted to plain STRING business attributes with no referenceEntity (a "*Ref" field points at
		// ANOTHER record, never a business value of this one; keyword attributes/sourceSystem/sourceId are
		// excluded by construction, they are never in businessAttributes). Absent from pDefaultLabelByTable (no
		// map entry at all) when nothing qualifies - the table then keeps EBX's own key-based label, exactly
		// RecordTitleRule.pickTitle's own last-resort behaviour. Stored as plain data (label TEMPLATE, keyed by
		// tablePath), never as a set_element_property op - see Result#defaultLabelByTable's own Javadoc for why:
		// MEASURED LIVE, 2026-09-22, apply_data_model_patch refuses "defaultLabel" as a set_element_property
		// property outright and aborts the whole patch (docs/MASTER-ZONE.md §10). ebx.MasterGenerator applies
		// this itself, one execute_operation(set_default_label) call per NEW table.
		final List<String> displayFieldCandidates = new ArrayList<String>();
		for (final AttributeInfo attribute : businessAttributes)
		{
			if (attribute.referenceEntity != null)
			{
				continue; // becomes a "*Ref" field pointing at another record - not a display value of THIS one
			}
			final String dataType = attribute.dataType;
			if (dataType != null && !dataType.trim().isEmpty() && !"STRING".equals(dataType))
			{
				continue; // StagingModel.AttributeInfo's own Javadoc: blank/null dataType reads as STRING
			}
			displayFieldCandidates.add(masterNameByAttribute.get(attribute.name)); // the MASTER name: a renamed field is still a display candidate under its master name
		}
		final String displayField = RecordTitleRule.pickDisplayFieldName(displayFieldCandidates);
		if (displayField != null)
		{
			pDefaultLabelByTable.put(tablePath, "${./" + displayField + "}");
		}
	}

	/**
	 * @return the promoted entity name a reference to {@code pReferenceEntity} should target: itself if already
	 *         promoted, its canonical entity if it is a redirected member, or null if neither (kept as plain data).
	 */
	private static String resolveTarget(final String pReferenceEntity, final Set<String> pPromoted, final Map<String, String> pMemberToCanonical)
	{
		if (pPromoted.contains(pReferenceEntity))
		{
			return pReferenceEntity;
		}
		final String canonical = pMemberToCanonical.get(pReferenceEntity);
		if (canonical != null && pPromoted.contains(canonical))
		{
			return canonical;
		}
		return null;
	}

	/** The {@link MasterFieldNameRule#RESERVED} entry {@code pAttributeName} collides with (its own spelling, for the note) - only called for a name {@link MasterFieldNameRule#isReserved} already answered true for. */
	private static String reservedNameFor(final String pAttributeName)
	{
		for (final String reserved : MasterFieldNameRule.RESERVED)
		{
			if (reserved.equalsIgnoreCase(pAttributeName))
			{
				return reserved;
			}
		}
		return pAttributeName;
	}

	private static void sortSourceSystemFirst(final List<AttributeInfo> pKeyAttributes)
	{
		final List<AttributeInfo> ordered = new ArrayList<AttributeInfo>();
		AttributeInfo sourceSystem = null;
		AttributeInfo sourceId = null;
		final List<AttributeInfo> others = new ArrayList<AttributeInfo>();
		for (final AttributeInfo attribute : pKeyAttributes)
		{
			if (MasterModel.SOURCE_SYSTEM.equals(attribute.name))
			{
				sourceSystem = attribute;
			}
			else if (MasterModel.SOURCE_ID.equals(attribute.name))
			{
				sourceId = attribute;
			}
			else
			{
				others.add(attribute);
			}
		}
		if (sourceSystem != null)
		{
			ordered.add(sourceSystem);
		}
		if (sourceId != null)
		{
			ordered.add(sourceId);
		}
		ordered.addAll(others);
		pKeyAttributes.clear();
		pKeyAttributes.addAll(ordered);
	}

	/** Mirrors {@code StagingModel.ebxType} (kept private and duplicated rather than shared cross-package, so this class's build never depends on staging's internal representation - same DATA_TYPE vocabulary, unlikely to drift). */
	private static String ebxType(final String pDataType)
	{
		final String type = pDataType == null ? "" : pDataType.trim().toUpperCase(Locale.ROOT);
		if ("INTEGER".equals(type))
		{
			return "int";
		}
		if ("DECIMAL".equals(type))
		{
			return "decimal";
		}
		if ("BOOLEAN".equals(type))
		{
			return "boolean";
		}
		if ("DATE".equals(type))
		{
			return "date";
		}
		if ("TIMESTAMP".equals(type))
		{
			return "dateTime";
		}
		return "string";
	}

	private static void field(final ArrayNode pFields, final String pTablePath, final String pName, final String pType, final String pLabel)
	{
		final ObjectNode field = pFields.addObject().putObject("field");
		field.put("name", pName);
		field.put("path", pTablePath + "/" + pName);
		field.put("type", pType);
		field.put("label", pLabel == null || pLabel.trim().isEmpty() ? pName : pLabel);
	}

	private static void foreignKeyField(final ArrayNode pFields, final String pTablePath, final String pName, final String pLabel, final String pTargetTablePath)
	{
		final ObjectNode field = pFields.addObject().putObject("field");
		field.put("name", pName);
		field.put("path", pTablePath + "/" + pName);
		field.put("type", "string");
		field.put("label", pLabel == null || pLabel.trim().isEmpty() ? pName : pLabel);
		field.put("isForeignKey", true);
		field.put("foreignKeyTargetPath", pTargetTablePath);
	}

	private static void maxLengthOp(final ArrayNode pOperations, final String pTablePath, final String pFieldName, final AttributeInfo pAttribute)
	{
		if (!"STRING".equalsIgnoreCase(pAttribute.dataType == null ? "STRING" : pAttribute.dataType) || pAttribute.maxLength == null)
		{
			return;
		}
		pOperations.add(MasterModel.setElementProperty(pTablePath, pFieldName, "maxLength", String.valueOf(pAttribute.maxLength)));
	}

	private static ObjectNode setElementProperty(final String pTablePath, final String pFieldName, final String pProperty, final String pValue)
	{
		return MasterModel.setElementProperty(pTablePath + "/" + pFieldName, pProperty, pValue);
	}

	/**
	 * {@code set_element_property}'s allowed {@code property} vocabulary is FIELD-level only (MEASURED LIVE,
	 * 2026-09-22 - see {@link Result#defaultLabelByTable}'s Javadoc): every current caller of this overload
	 * passes a FIELD element_path ({@code pTablePath + "/" + pFieldName}, via the 4-arg overload above) - a
	 * table-level property such as {@code defaultLabel} is never built this way any more, it goes through
	 * {@link Result#defaultLabelByTable} and {@code execute_operation(set_default_label)} instead.
	 */
	private static ObjectNode setElementProperty(final String pElementPath, final String pProperty, final String pValue)
	{
		final ObjectNode op = MasterModel.MAPPER.createObjectNode();
		op.put("op", "set_element_property");
		final ObjectNode arguments = op.putObject("arguments");
		arguments.put("element_path", pElementPath);
		arguments.put("property", pProperty);
		arguments.put("value", pValue);
		return op;
	}

	private static String unique(final String pName, final Set<String> pTaken)
	{
		String name = pName;
		for (int i = 2; !pTaken.add(name.toLowerCase(Locale.ROOT)); i++)
		{
			name = pName + "_" + i;
		}
		return name;
	}

	/** Where {@link #build} puts a table of the given (promoted) entity name: {@code /root/<EntityName>} - the same convention as {@code StagingModel.tablePath}, deliberately: {@code ebx.MasterLoader} and {@code ebx.MasterTransferPlan} build one from the entity name the same way regardless of zone. */
	public static String tablePath(final String pEntityName)
	{
		return "/root/" + pEntityName;
	}
}
