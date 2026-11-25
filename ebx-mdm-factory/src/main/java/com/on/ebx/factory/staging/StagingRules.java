package com.on.ebx.factory.staging;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.landing.Words;

/**
 * Deterministic, rule-only first pass of the staging suggestion for a GREENFIELD project (no master model
 * yet: the factory itself proposes the entities). Reads the landing tables of every source of the project
 * and proposes one staging entity per table, one attribute per landing column plus the two technical
 * identity attributes every entity needs, and one mapping per attribute. Everything comes back {@code
 * PROPOSED} with an origin, a confidence and a rationale: nothing here writes to EBX, and nothing here
 * merges entities across applications or aligns columns of different sources - that is the job of the
 * later language-model pass, which starts from what this pass wrote. Pure: no EBX class, unit-tested as is.
 * <p>
 * Word splitting ({@link Words}, shared with {@code RelationRule}) drives both the PascalCase entity name
 * and the camelCase attribute name, so {@code SUPPLIER_ID} reads the same way whether it ends up naming a
 * table or a field.
 */
public final class StagingRules
{
	/** Staging text attribute lengths only ever land on one of these sizes - never an arbitrary profiled number. */
	private static final int[] ROUND_LENGTHS = { 10, 20, 40, 80, 120, 255, 500, 1000, 2000, 4000 };
	private static final int DEFAULT_STRING_LENGTH = 255;
	private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);

	private StagingRules()
	{
	}

	// --------------------------------------------------------------------------------------------------------- input

	/** A source column of a landing table, with the frozen profile {@code LandingPlanner} wrote (all three null when the table was never profiled). */
	public static final class LandingColumnInfo
	{
		/** Opaque id of the {@code LandingColumn} row: carried through to the mapping proposal, not otherwise read. */
		public final String id;
		public final String sourceColumn;
		public final String fieldName;
		/** {@code STRING}/{@code INTEGER}/{@code DECIMAL}/{@code BOOLEAN}/{@code DATE}/{@code TIMESTAMP}; blank or null reads as {@code STRING}. */
		public final String inferredType;
		public final boolean sourceKey;
		/** 0-100, null when the table was never profiled. */
		public final BigDecimal fillRate;
		public final Long distinctCount;
		public final Integer maxLength;
		/** Catalog tags (EMAIL, VAT, PHONE...): carried through for a later, semantic pass; these rules never read them. */
		public final List<String> tags;
		/**
		 * Most frequent value SHAPES of a text column ({@code quality.Shapes}: letters to a/A, digits to 9, the rest kept),
		 * each with its share, e.g. {@code "99999999 (100%)"} - filled by {@code StagingLlmSuggester} only when the project's
		 * LLM data policy allows shapes; empty otherwise. Never a real value.
		 */
		public final List<String> shapes = new ArrayList<String>();
		/**
		 * Where the capitals sit in the multi-token values of a text column ({@link TokenCase}), filled with {@link #shapes}
		 * by {@code StagingLlmSuggester} from the same sample; null when not measured. Counts only, never a value.
		 */
		public TokenCase tokenCase;
		/**
		 * Staging normalisation lot D (2026-10-17): what the values of a text column look like, measured ({@link
		 * NormalisationProfile#factsLine}): date pattern, number and phone shape, letter case, spaces, repeated codes - as one
		 * {@code ", measured: ..."} sentence for the model, empty when not measured or when the data policy keeps the shapes
		 * of the column from the model. Counts and format names; the codes themselves only at the policy's SAMPLE_VALUES level.
		 */
		public String profileFacts = "";
		/**
		 * Lot S10 (2026-10-18): who set the landing key this column belongs to ({@code SOURCE}, {@code RULE}, {@code LLM}, {@code USER}, {@code DEDUCED}); null for a
		 * column outside the key or when unknown. A fact the Factory already holds, given to the model as it is.
		 */
		public String keyOrigin;
		/**
		 * Lot S10: how many distinct values this IDENTITY column (a catalog identity tag, {@link IdentityFacts.Kind}) holds and on how many rows a value repeats;
		 * null for a column without identity tag or that could not be read. Counts only, never a value.
		 */
		public IdentityFacts.Column identity;
		/**
		 * Lot F2 (2026-10-19): which identity shape the values of this text column have ({@link IdentityShapes}: e-mail-shaped, phone-shaped, two capitalised words, in which share of the
		 * sampled values), measured from the values the profiler reads for the shapes; null when none holds or when the values were not read. A MEASURE of how the values look, never a tag:
		 * it makes the column identity-bearing for the counts only where the catalogue gave it no identity tag ({@link IdentityFacts#kindOf}).
		 */
		public IdentityShapes.Detected shapeIdentity;
		/**
		 * Lot F2b (2026-10-19): the span of a date column (typed DATE/TIMESTAMP, or a text column whose values are mostly dates): earliest and latest month, rows, rows in the last 24 months
		 * ({@link DateSpans}); null when the column is no date column or was not read. Measured on the values the shape profiler reads; counts and months, never a day or a value.
		 */
		public DateSpans.Span dateSpan;
		/**
		 * B2B test (2026-10-23): what the row-level pass measured on this column ({@link RowFacts#columnText}: own key, keys of another source, a value cut at a fixed width, several
		 * identifier shapes, separators in identifiers), with the rule each fact calls for; empty when nothing fired.
		 */
		public String rowFacts = "";
		/**
		 * Lot S10b (2026-10-19): what the data catalogue itself suggested for this column ({@code TagSuggestion}: tag, origin REGEX / NAME / DICTIONARY / LLM, confidence, status) -
		 * measurements and verdicts of the catalogue, read as they stand; {@link #tags} stays the tags APPLIED. Never a value: the free-text evidence is not carried.
		 */
		public final List<TagSuggestionInfo> suggestions = new ArrayList<TagSuggestionInfo>();

		public LandingColumnInfo(final String pId, final String pSourceColumn, final String pFieldName, final String pInferredType, final boolean pSourceKey,
			final BigDecimal pFillRate, final Long pDistinctCount, final Integer pMaxLength, final List<String> pTags)
		{
			this.id = pId;
			this.sourceColumn = pSourceColumn;
			this.fieldName = pFieldName;
			this.inferredType = pInferredType;
			this.sourceKey = pSourceKey;
			this.fillRate = pFillRate;
			this.distinctCount = pDistinctCount;
			this.maxLength = pMaxLength;
			this.tags = pTags == null ? new ArrayList<String>() : pTags;
		}
	}

	/**
	 * Lot S10b (2026-10-19): one {@code TagSuggestion} row of the data catalogue for a column - the tag, how the catalogue found it ({@code REGEX}: the share of sampled values that
	 * match is the confidence; {@code NAME}: a word of the column name; {@code DICTIONARY}; {@code LLM}) and where it stands ({@code APPLIED}, {@code ACCEPTED}, {@code PENDING} review,
	 * {@code REJECTED}). Pure value object; {@link #words} is what the prompt and the review say of it.
	 */
	public static final class TagSuggestionInfo
	{
		public final String tag;
		public final String origin;
		public final int confidence;
		public final String status;

		public TagSuggestionInfo(final String pTag, final String pOrigin, final int pConfidence, final String pStatus)
		{
			this.tag = pTag == null ? "" : pTag.trim().toUpperCase(java.util.Locale.ROOT);
			this.origin = pOrigin == null ? "" : pOrigin.trim().toUpperCase(java.util.Locale.ROOT);
			this.confidence = pConfidence;
			this.status = pStatus == null ? "" : pStatus.trim().toUpperCase(java.util.Locale.ROOT);
		}

		/** The tag is on the column (the catalogue applied it itself, or a steward accepted it). */
		public boolean applied()
		{
			return "APPLIED".equals(this.status) || "ACCEPTED".equals(this.status);
		}

		public boolean rejected()
		{
			return "REJECTED".equals(this.status);
		}

		/** {@code regex matched 96% of sampled values} / {@code name hint of the catalogue} / ... : how the catalogue found it. */
		public String how()
		{
			switch (this.origin)
			{
				case "REGEX":
					return "regex matched " + this.confidence + "% of sampled values";
				case "DICTIONARY":
					return "dictionary matched " + this.confidence + "% of sampled values";
				case "NAME":
					return "name hint of the catalogue";
				case "LLM":
					return "language model of the catalogue, confidence " + this.confidence;
				default:
					return "origin " + (this.origin.isEmpty() ? "unknown" : this.origin) + ", confidence " + this.confidence;
			}
		}

		/** {@code applied} / {@code accepted by a steward} / {@code pending review} / {@code rejected by a steward}. */
		public String statusWords()
		{
			switch (this.status)
			{
				case "APPLIED":
					return "applied";
				case "ACCEPTED":
					return "accepted by a steward";
				case "PENDING":
					return "pending review";
				case "REJECTED":
					return "rejected by a steward";
				default:
					return this.status.isEmpty() ? "status unknown" : this.status.toLowerCase(java.util.Locale.ROOT);
			}
		}

		/** {@code regex matched 96% of sampled values, pending review}. */
		public String words()
		{
			return this.how() + ", " + this.statusWords();
		}

		/**
		 * How firm the fact is, for the sentence that names its origin: {@code APPLIED}; or, for a suggestion, where the catalogue got it - {@code SAMPLED} (a regex or a
		 * dictionary on its sampled values), {@code NAMES} (a name hint), {@code MODEL} (its language model), {@code OTHER}.
		 */
		public String basis()
		{
			if (this.applied())
			{
				return "APPLIED";
			}
			switch (this.origin)
			{
				case "REGEX":
				case "DICTIONARY":
					return "SAMPLED";
				case "NAME":
					return "NAMES";
				case "LLM":
					return "MODEL";
				default:
					return "OTHER";
			}
		}

		/** {@code suggested by the catalogue from its sampled values, pending review} (or {@code applied}): the firmness of the fact in a sentence. */
		public String basisWords()
		{
			switch (this.basis())
			{
				case "APPLIED":
					return "applied";
				case "SAMPLED":
					return "suggested by the catalogue from its sampled values, " + this.statusWords();
				case "NAMES":
					return "suggested by the catalogue from its name hints, " + this.statusWords();
				case "MODEL":
					return "suggested by the catalogue's language model, " + this.statusWords();
				default:
					return "suggested by the catalogue, " + this.statusWords();
			}
		}
	}

	/** One landing table of one project source. */
	public static final class LandingTableInfo
	{
		/** Opaque id of the {@code LandingTable} row. */
		public final String id;
		public final String sourceSystem;
		/** The generated landing table name (what {@code RelationRule}'s own {@code Table.name} also uses to key relations). */
		public final String name;
		/** As profiled by the catalog; null when unknown. */
		public final Long rowCount;
		public final List<LandingColumnInfo> columns;
		/** Lot S10 (2026-10-18): the rows counted in the LANDING table itself (null when not counted); what the feeds read, so what the model is told. */
		public Long landedRows;
		/** Lot S10: the identity figures of the table (one read of its rows); null when it has no identity column or could not be read. */
		public IdentityFacts.Table identity;

		/** The rows the model is told: the landing table's own count, else the catalog's when it is positive (a catalog 0 is often just unknown); null when neither. */
		public Long rows()
		{
			if (this.landedRows != null)
			{
				return this.landedRows;
			}
			return this.rowCount != null && this.rowCount.longValue() > 0L ? this.rowCount : null;
		}

		public LandingTableInfo(final String pId, final String pSourceSystem, final String pName, final Long pRowCount, final List<LandingColumnInfo> pColumns)
		{
			this.id = pId;
			this.sourceSystem = pSourceSystem;
			this.name = pName;
			this.rowCount = pRowCount;
			this.columns = pColumns == null ? new ArrayList<LandingColumnInfo>() : pColumns;
		}
	}

	/**
	 * A {@code LandingRelation} row, already filtered to a single project source (never proposed across two
	 * applications of the same platform - {@code RelationRule}'s own contract, see its Javadoc). Both
	 * {@link #fromTable} and {@link #toTable} therefore belong to the same {@link #sourceSystem}: a landing
	 * table name is only guaranteed unique within one source (two different sources can each generate a
	 * table named {@code ITEM}), so a relation must say which source it is scoped to for {@link #propose} to
	 * resolve the right one.
	 */
	public static final class RelationInfo
	{
		/** Id of the {@code LandingRelation} row: carried through to the attribute proposal ({@link AttributeProposal#fromRelationId}) so the
		 * review screen can require this exact relation accepted, instead of matching it back by table/column names or rationale text. */
		public final String id;
		public final String sourceSystem;
		/** {@link LandingTableInfo#name} of the child table, within {@link #sourceSystem}. */
		public final String fromTable;
		/** Source column names of the child, in key order (rule 6 only ever acts on a single one). */
		public final List<String> fromColumns;
		/** {@link LandingTableInfo#name} of the parent table, within {@link #sourceSystem}. */
		public final String toTable;
		/** {@code PROPOSED} or {@code ACCEPTED} is used; anything else (in particular {@code REJECTED}) is ignored - see {@link #propose}. */
		public final String status;
		public final int confidence;

		public RelationInfo(final String pId, final String pSourceSystem, final String pFromTable, final List<String> pFromColumns, final String pToTable,
			final String pStatus, final int pConfidence)
		{
			this.id = pId;
			this.sourceSystem = pSourceSystem;
			this.fromTable = pFromTable;
			this.fromColumns = pFromColumns == null ? new ArrayList<String>() : pFromColumns;
			this.toTable = pToTable;
			this.status = pStatus;
			this.confidence = pConfidence;
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** How a landing column feeds a staging attribute: always type-driven (rule 7), so always confidence 90. */
	public static final class MappingProposal
	{
		public final String landingColumnId;
		public final String transformation;
		/** Date pattern, decimal separator, or null when the transformation needs none. */
		public final String argument;
		public final int confidence;
		public final String rationale;

		MappingProposal(final String pLandingColumnId, final String pTransformation, final String pArgument, final int pConfidence, final String pRationale)
		{
			this.landingColumnId = pLandingColumnId;
			this.transformation = pTransformation;
			this.argument = pArgument;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}
	}

	/** A proposed {@code StagingAttribute}, with the mapping that feeds it (null for the two technical identity attributes: see {@link #propose}). */
	public static final class AttributeProposal
	{
		public final String name;
		public final String label;
		public final String dataType;
		/** Only for {@code STRING}; null for every other type. */
		public final Integer maxLength;
		public final boolean mandatory;
		/** Part of the SOURCE's own key ({@code contactId}...) - distinct from {@link #primaryKey}, the entity's technical identity. See the class Javadoc. */
		public final boolean businessKey;
		/** The entity's technical identity ({@code sourceSystem}/{@code sourceId} only, set by {@link #technicalAttribute}); never true for a plain source column, however key-like. */
		public final boolean primaryKey;
		public final boolean enumerated;
		/** Name of the {@link EntityProposal} this attribute is a foreign key to; null when it is not one. */
		public final String referenceEntity;
		/** Id of the {@code LandingRelation} {@link #referenceEntity} was deduced from (rule 6 only); null when the reference did not come from a relation - the model's own canonical
		 * attributes never set this (see {@code StagingLlmPass}), and a user-entered reference never does either. Written verbatim to {@code StagingAttribute.fromRelation}. */
		public final String fromRelationId;
		public final int confidence;
		public final String rationale;
		/** Null only for {@code sourceSystem}/{@code sourceId}: {@code AttributeMapping.landingColumn} is a mandatory foreign key, so a mapping without one cannot be written. */
		public final MappingProposal mapping;

		AttributeProposal(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final boolean pBusinessKey, final boolean pPrimaryKey, final boolean pEnumerated, final String pReferenceEntity, final String pFromRelationId,
			final int pConfidence, final String pRationale, final MappingProposal pMapping)
		{
			this.name = pName;
			this.label = pLabel;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.businessKey = pBusinessKey;
			this.primaryKey = pPrimaryKey;
			this.enumerated = pEnumerated;
			this.referenceEntity = pReferenceEntity;
			this.fromRelationId = pFromRelationId;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.mapping = pMapping;
		}
	}

	/** A proposed {@code StagingEntity}, one per landing table. */
	public static final class EntityProposal
	{
		public final String name;
		public final String label;
		/** {@link LandingTableInfo#id} this entity was proposed from - not written anywhere (the model has no such foreign key), only for the caller's own bookkeeping. */
		public final String landingTableId;
		public final String sourceSystem;
		public final int confidence;
		public final String rationale;
		public final List<AttributeProposal> attributes;

		EntityProposal(final String pName, final String pLabel, final String pLandingTableId, final String pSourceSystem, final int pConfidence,
			final String pRationale, final List<AttributeProposal> pAttributes)
		{
			this.name = pName;
			this.label = pLabel;
			this.landingTableId = pLandingTableId;
			this.sourceSystem = pSourceSystem;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.attributes = pAttributes;
		}
	}

	/** A relation the rules looked at but could not turn into a {@code referenceEntity} - for the caller's report, not silently dropped. */
	public static final class SkippedRelation
	{
		public final String fromTable;
		public final List<String> fromColumns;
		public final String toTable;
		public final String reason;

		SkippedRelation(final String pFromTable, final List<String> pFromColumns, final String pToTable, final String pReason)
		{
			this.fromTable = pFromTable;
			this.fromColumns = pFromColumns;
			this.toTable = pToTable;
			this.reason = pReason;
		}
	}

	public static final class Result
	{
		public final List<EntityProposal> entities;
		public final List<SkippedRelation> skippedRelations;
		public final List<String> notes;

		Result(final List<EntityProposal> pEntities, final List<SkippedRelation> pSkippedRelations, final List<String> pNotes)
		{
			this.entities = pEntities;
			this.skippedRelations = pSkippedRelations;
			this.notes = pNotes;
		}
	}

	// --------------------------------------------------------------------------------------------------------- rules

	/**
	 * @param pTables every landing table of every source of the project (rule 1: two tables with the same
	 *        proposed entity name, from different sources, both get their source system prefixed - so
	 *        naming must see the whole project at once, not one source at a time).
	 * @param pRelations {@code LandingRelation} rows of status {@code PROPOSED} or {@code ACCEPTED} only;
	 *        anything else (in particular {@code REJECTED}) may be passed in too, it is filtered out here.
	 * @param pLengthMarginPercent {@code FactorySettings.lengthMargin}.
	 */
	public static Result propose(final List<LandingTableInfo> pTables, final List<RelationInfo> pRelations, final int pLengthMarginPercent)
	{
		final Names names = StagingRules.entityNames(pTables);

		// Keyed by (sourceSystem, table name), never by the bare table name: a landing table name is only
		// guaranteed unique within its own source (two different sources can each generate a table named
		// ITEM), and this map must tell those apart.
		final Map<String, TableBuild> byTableKey = new LinkedHashMap<String, TableBuild>();
		int profiledTables = 0;
		for (final LandingTableInfo table : pTables)
		{
			final String key = StagingRules.tableKey(table.sourceSystem, table.name);
			final TableBuild build = new TableBuild(table, names.finalName.get(key), names.baseName.get(key));
			final Set<String> usedNames = new LinkedHashSet<String>();
			build.attributes.add(StagingRules.technicalAttribute("sourceSystem", "Source system", 40, usedNames));
			build.attributes.add(StagingRules.technicalAttribute("sourceId", "Source id", 255, usedNames));

			boolean tableProfiled = false;
			for (final LandingColumnInfo column : table.columns)
			{
				final AttrBuilder attribute = StagingRules.attributeFor(column, table, pLengthMarginPercent, usedNames);
				build.attributes.add(attribute);
				build.byColumn.put(column.sourceColumn, attribute);
				if (column.fillRate != null || column.distinctCount != null || column.maxLength != null)
				{
					tableProfiled = true;
				}
			}
			if (tableProfiled)
			{
				profiledTables++;
			}
			byTableKey.put(key, build);
		}

		final List<SkippedRelation> skipped = new ArrayList<SkippedRelation>();
		if (pRelations != null)
		{
			for (final RelationInfo relation : pRelations)
			{
				StagingRules.applyRelation(relation, byTableKey, names.finalName, skipped);
			}
		}

		final List<EntityProposal> entities = new ArrayList<EntityProposal>();
		for (final LandingTableInfo table : pTables)
		{
			entities.add(byTableKey.get(StagingRules.tableKey(table.sourceSystem, table.name)).build());
		}

		final List<String> notes = new ArrayList<String>();
		if (profiledTables < pTables.size())
		{
			notes.add("No profile on " + (pTables.size() - profiledTables) + " of " + pTables.size()
				+ " landing tables: lengths default to " + StagingRules.DEFAULT_STRING_LENGTH + " and mandatory flags are proposed from the source key only.");
		}
		if (!skipped.isEmpty())
		{
			notes.add(skipped.size() + " relation(s) skipped: see skippedRelations.");
		}
		return new Result(entities, skipped, notes);
	}

	/** {@code sourceSystem + table name}, the composite key every internal lookup uses (see {@link #propose}). */
	private static String tableKey(final String pSourceSystem, final String pTableName)
	{
		return pSourceSystem + ' ' + pTableName;
	}

	/** Rule 6: a single-column relation of status PROPOSED or ACCEPTED sets referenceEntity/confidence/rationale on the child attribute; anything else is reported. */
	private static void applyRelation(final RelationInfo pRelation, final Map<String, TableBuild> pByTableKey, final Map<String, String> pEntityNameByTable,
		final List<SkippedRelation> pSkipped)
	{
		if (!"PROPOSED".equals(pRelation.status) && !"ACCEPTED".equals(pRelation.status))
		{
			return;
		}
		if (pRelation.fromColumns.size() != 1)
		{
			pSkipped.add(new SkippedRelation(pRelation.fromTable, pRelation.fromColumns, pRelation.toTable,
				"Multi-column relation: no single attribute to carry the reference, not supported by this rule pass."));
			return;
		}
		final String targetEntity = pEntityNameByTable.get(StagingRules.tableKey(pRelation.sourceSystem, pRelation.toTable));
		final TableBuild fromBuild = pByTableKey.get(StagingRules.tableKey(pRelation.sourceSystem, pRelation.fromTable));
		final AttrBuilder attribute = fromBuild == null ? null : fromBuild.byColumn.get(pRelation.fromColumns.get(0));
		if (targetEntity == null || attribute == null)
		{
			pSkipped.add(new SkippedRelation(pRelation.fromTable, pRelation.fromColumns, pRelation.toTable,
				"Table or column not found among the landing tables passed in: cannot propose a reference."));
			return;
		}
		String rationale = "Foreign key to " + targetEntity + ": relation " + pRelation.fromTable + "." + pRelation.fromColumns.get(0) + " -> "
			+ pRelation.toTable + ", confidence " + pRelation.confidence + ".";
		if ("PROPOSED".equals(pRelation.status))
		{
			rationale += " The relation itself is only proposed: accept the relation first.";
		}
		attribute.referenceEntity = targetEntity;
		// the real link the review screen enforces (ReviewRules.canAcceptStagingAttribute): the rationale text above is prose for
		// humans only, from here on - it gets truncated, translated and edited, and cannot tell two same-named relations apart
		attribute.fromRelationId = pRelation.id;
		attribute.confidence = pRelation.confidence;
		attribute.rationale = rationale;
	}

	/** One landing table's attribute builders, kept mutable until {@link #propose} has applied every relation. */
	private static final class TableBuild
	{
		final LandingTableInfo table;
		final String entityName;
		/** The unprefixed, singularised PascalCase name (rule 1): compared against {@link #entityName} to say, in the rationale, whether a collision forced a source-system prefix. */
		final String baseName;
		final List<AttrBuilder> attributes = new ArrayList<AttrBuilder>();
		/** Source column name -> its builder, so rule 6 can find the right one by {@code RelationInfo.fromColumns}. */
		final Map<String, AttrBuilder> byColumn = new LinkedHashMap<String, AttrBuilder>();

		TableBuild(final LandingTableInfo pTable, final String pEntityName, final String pBaseName)
		{
			this.table = pTable;
			this.entityName = pEntityName;
			this.baseName = pBaseName;
		}

		EntityProposal build()
		{
			final List<AttributeProposal> built = new ArrayList<AttributeProposal>();
			for (final AttrBuilder builder : this.attributes)
			{
				built.add(builder.build());
			}
			final String rationale = "One staging entity per landing table " + this.table.name + " (source " + this.table.sourceSystem + ")."
				+ (this.entityName.equals(this.baseName) ? "" : " Prefixed with the source system: another table of the project proposed the same entity name.");
			return new EntityProposal(this.entityName, StagingRules.label(Words.of(this.table.name)), this.table.id, this.table.sourceSystem, 90, rationale,
				built);
		}
	}

	/** A {@link AttributeProposal} not yet final: rule 6 may still set {@link #referenceEntity}/{@link #confidence}/{@link #rationale} on it. */
	private static final class AttrBuilder
	{
		final String name;
		final String label;
		final String dataType;
		final Integer maxLength;
		final boolean mandatory;
		final boolean businessKey;
		final boolean primaryKey;
		final boolean enumerated;
		final MappingProposal mapping;
		String referenceEntity;
		/** Set by {@link #applyRelation} alongside {@link #referenceEntity}; stays null for every other attribute (including the two technical ones, which never carry a reference). */
		String fromRelationId;
		int confidence;
		String rationale;

		AttrBuilder(final String pName, final String pLabel, final String pDataType, final Integer pMaxLength, final boolean pMandatory,
			final boolean pBusinessKey, final boolean pPrimaryKey, final boolean pEnumerated, final int pConfidence, final String pRationale,
			final MappingProposal pMapping)
		{
			this.name = pName;
			this.label = pLabel;
			this.dataType = pDataType;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
			this.businessKey = pBusinessKey;
			this.primaryKey = pPrimaryKey;
			this.enumerated = pEnumerated;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.mapping = pMapping;
		}

		AttributeProposal build()
		{
			return new AttributeProposal(this.name, this.label, this.dataType, this.maxLength, this.mandatory, this.businessKey, this.primaryKey,
				this.enumerated, this.referenceEntity, this.fromRelationId, this.confidence, this.rationale, this.mapping);
		}
	}

	// ------------------------------------------------------------------------------------------------ rule 2: sourceSystem/sourceId

	/**
	 * The two technical identity attributes every entity gets (rule 2): the entity's real primary key in staging, filled by the transfer,
	 * never mapped from a landing column. {@code primaryKey=true}, {@code businessKey=false}: the staging primary key (this entity's own
	 * technical identity) and the source's business key are two distinct notions - see {@code StagingAttribute.primaryKey}'s Javadoc and
	 * PLAN.md, "Suggestion du staging" (2026-09-20: "businessKey est vrai à la fois sur sourceSystem/sourceId... et sur la clé source").
	 */
	private static AttrBuilder technicalAttribute(final String pName, final String pLabel, final int pMaxLength, final Set<String> pUsedNames)
	{
		pUsedNames.add(pName.toLowerCase(Locale.ROOT));
		return new AttrBuilder(pName, pLabel, "STRING", Integer.valueOf(pMaxLength), true, false, true, false, 100,
			"Every staging entity is keyed by sourceSystem + sourceId (the staging primary key), not by the source's own business key, because "
				+ "business keys are not reliable across applications. Filled by the transfer from the landing row's _sourceSystem / _sourceId; no "
				+ "mapping row is written for it because AttributeMapping.landingColumn is a mandatory foreign key and this attribute has no landing "
				+ "column of its own.",
			null);
	}

	/**
	 * The two technical identity attribute proposals of {@link #technicalAttribute}, for a caller outside {@link #propose} - in particular
	 * {@code StagingLlmSuggester}, whose canonical (LLM) entities need the exact same technical key as a rule-proposed one: a canonical
	 * staging row still comes from one landing row of one source system, so {@code sourceSystem}/{@code sourceId} identifies it the same
	 * way (cross-source unification happens later, in Match and Merge - see PLAN.md, "Architecture"). Fresh {@code AttrBuilder}s each call:
	 * safe to call once per entity being created.
	 */
	public static List<AttributeProposal> technicalAttributeProposals()
	{
		final Set<String> usedNames = new LinkedHashSet<String>();
		final List<AttributeProposal> out = new ArrayList<AttributeProposal>();
		out.add(StagingRules.technicalAttribute("sourceSystem", "Source system", 40, usedNames).build());
		out.add(StagingRules.technicalAttribute("sourceId", "Source id", 255, usedNames).build());
		return out;
	}

	/**
	 * Rule 7's own type-driven mapping choice ({@link #attributeFor}), exposed for a caller outside {@link
	 * #propose} that needs the exact same default when it has no explicit pipeline of its own to fall back on
	 * - {@code StagingLlmSuggester} (target-mapping lot 2, docs/TARGET-MAPPING.md sec 4.2): a {@code
	 * propose_feed} rule with no {@code pipeline} argument gets this, never a second type-to-transformation
	 * table re-derived in the {@code ebx} package. Same confidence (90) and rationale shape as the rule pass's
	 * own per-column mapping.
	 */
	public static MappingProposal typeDrivenMapping(final String pLandingColumnId, final String pDataType)
	{
		final String dataType = pDataType == null || pDataType.trim().isEmpty() ? "STRING" : pDataType.trim().toUpperCase(Locale.ROOT);
		return new MappingProposal(pLandingColumnId, StagingRules.transformationFor(dataType), StagingRules.argumentFor(dataType), 90,
			"Type-driven mapping for " + dataType + ".");
	}

	// -------------------------------------------------------------------------------------------- rules 1, 3-5, 7-8: per-column attribute

	private static AttrBuilder attributeFor(final LandingColumnInfo pColumn, final LandingTableInfo pTable, final int pLengthMarginPercent,
		final Set<String> pUsedNames)
	{
		final List<String> words = Words.of(pColumn.sourceColumn);
		final String name = StagingRules.unique(StagingRules.camel(words), pUsedNames);
		final String label = StagingRules.label(words);
		final String dataType = pColumn.inferredType == null || pColumn.inferredType.trim().isEmpty() ? "STRING" : pColumn.inferredType.trim().toUpperCase(Locale.ROOT);
		final boolean businessKey = pColumn.sourceKey;

		final StringBuilder rationale = new StringBuilder();

		// rule 3: mandatory
		final boolean mandatory;
		if (businessKey)
		{
			mandatory = true;
			StagingRules.note(rationale, "Mandatory: part of the source key.");
		}
		else if (pColumn.fillRate != null)
		{
			mandatory = pColumn.fillRate.compareTo(StagingRules.HUNDRED) == 0;
			StagingRules.note(rationale, mandatory ? "Mandatory: profiled fill rate is 100%." : "Not mandatory: profiled fill rate is " + pColumn.fillRate + "%.");
		}
		else
		{
			mandatory = false;
			StagingRules.note(rationale, "no profile: not proposed as mandatory");
		}
		if (businessKey)
		{
			StagingRules.note(rationale, "Part of the source key.");
		}

		// rule 4: maxLength (STRING only)
		Integer maxLength = null;
		boolean sizedFromProfile = false;
		if ("STRING".equals(dataType))
		{
			if (pColumn.maxLength != null)
			{
				final int margined = StagingRules.margined(pColumn.maxLength.intValue(), pLengthMarginPercent);
				maxLength = Integer.valueOf(StagingRules.roundedLength(margined));
				sizedFromProfile = true;
				StagingRules.note(rationale,
					"Length " + maxLength + ": profiled maximum " + pColumn.maxLength + " plus " + pLengthMarginPercent + "% margin, rounded up.");
			}
			else
			{
				maxLength = Integer.valueOf(StagingRules.DEFAULT_STRING_LENGTH);
				StagingRules.note(rationale, "Default length " + StagingRules.DEFAULT_STRING_LENGTH + ": profiling the table would size it precisely.");
			}
		}

		// rule 5: enumerated
		final boolean enumerated = pColumn.distinctCount != null && pTable.rowCount != null && !businessKey && !"BOOLEAN".equals(dataType)
			&& !"DATE".equals(dataType) && !"TIMESTAMP".equals(dataType) && pColumn.distinctCount.longValue() <= 20L && pTable.rowCount.longValue() >= 50L
			&& pColumn.distinctCount.longValue() * 5L <= pTable.rowCount.longValue();
		if (enumerated)
		{
			StagingRules.note(rationale, "Closed list of values: " + pColumn.distinctCount + " distinct values profiled over " + pTable.rowCount + " rows.");
		}

		// rule 8: confidence
		final int confidence = !"STRING".equals(dataType) ? 90 : sizedFromProfile ? 90 : 70;

		// rule 7: mapping, always type-driven
		final MappingProposal mapping = new MappingProposal(pColumn.id, StagingRules.transformationFor(dataType), StagingRules.argumentFor(dataType), 90,
			"Type-driven mapping for " + dataType + ".");

		// primaryKey is only ever true on the two technical attributes (technicalAttribute): a source column, however key-like, is at most businessKey
		return new AttrBuilder(name, label, dataType, maxLength, mandatory, businessKey, false, enumerated, confidence, rationale.toString(), mapping);
	}

	private static void note(final StringBuilder pRationale, final String pNote)
	{
		if (pRationale.length() > 0)
		{
			pRationale.append(' ');
		}
		pRationale.append(pNote);
		if (!pNote.endsWith("."))
		{
			pRationale.append('.');
		}
	}

	private static String transformationFor(final String pDataType)
	{
		if ("BOOLEAN".equals(pDataType))
		{
			return "PARSE_BOOLEAN";
		}
		if ("DATE".equals(pDataType) || "TIMESTAMP".equals(pDataType))
		{
			return "PARSE_DATE";
		}
		if ("INTEGER".equals(pDataType) || "DECIMAL".equals(pDataType))
		{
			return "PARSE_DECIMAL";
		}
		return "TRIM";
	}

	private static String argumentFor(final String pDataType)
	{
		if ("DATE".equals(pDataType))
		{
			return "yyyy-MM-dd";
		}
		if ("TIMESTAMP".equals(pDataType))
		{
			return "yyyy-MM-dd'T'HH:mm:ss";
		}
		if ("INTEGER".equals(pDataType) || "DECIMAL".equals(pDataType))
		{
			return ".";
		}
		return null;
	}

	/** {@code raw * (100 + marginPercent) / 100}, rounded UP (never truncated: a shorter cap than the profiled value would refuse real data). */
	static int margined(final int pRaw, final int pMarginPercent)
	{
		final long scaled = (long) pRaw * (100L + pMarginPercent);
		return (int) ((scaled + 99L) / 100L);
	}

	/** The smallest of {@link #ROUND_LENGTHS} that is at least {@code pRaw}; capped at the largest one. */
	static int roundedLength(final int pRaw)
	{
		for (final int size : StagingRules.ROUND_LENGTHS)
		{
			if (pRaw <= size)
			{
				return size;
			}
		}
		return StagingRules.ROUND_LENGTHS[StagingRules.ROUND_LENGTHS.length - 1];
	}

	// ------------------------------------------------------------------------------------------------------- rule 1: naming

	/** {@link #entityNames}'s result: the final, possibly source-system-prefixed name, and the plain unprefixed base name it started from. */
	private static final class Names
	{
		final Map<String, String> finalName;
		final Map<String, String> baseName;

		Names(final Map<String, String> pFinalName, final Map<String, String> pBaseName)
		{
			this.finalName = pFinalName;
			this.baseName = pBaseName;
		}
	}

	/**
	 * Rule 1: one PascalCase, singularised name per landing table, prefixed with the (PascalCase) source
	 * system whenever two tables of different sources would otherwise propose the same name. Computed over
	 * the whole project at once, so a collision between two sources is always resolved the same way
	 * regardless of which table is processed first.
	 */
	private static Names entityNames(final List<LandingTableInfo> pTables)
	{
		final Map<String, List<LandingTableInfo>> byBaseName = new LinkedHashMap<String, List<LandingTableInfo>>();
		final Map<String, String> baseNameByKey = new LinkedHashMap<String, String>();
		for (final LandingTableInfo table : pTables)
		{
			final String base = StagingRules.pascal(StagingRules.singularize(Words.of(table.name)));
			baseNameByKey.put(StagingRules.tableKey(table.sourceSystem, table.name), base);
			List<LandingTableInfo> group = byBaseName.get(base.toLowerCase(Locale.ROOT));
			if (group == null)
			{
				group = new ArrayList<LandingTableInfo>();
				byBaseName.put(base.toLowerCase(Locale.ROOT), group);
			}
			group.add(table);
		}

		final Map<String, String> nameByKey = new LinkedHashMap<String, String>();
		final Set<String> taken = new LinkedHashSet<String>();
		for (final LandingTableInfo table : pTables)
		{
			final String key = StagingRules.tableKey(table.sourceSystem, table.name);
			final String base = baseNameByKey.get(key);
			final List<LandingTableInfo> group = byBaseName.get(base.toLowerCase(Locale.ROOT));
			final boolean collides = group.size() > 1;
			String name = collides ? StagingRules.pascal(Words.of(table.sourceSystem)) + base : base;
			// defensive final uniqueness pass: two tables of the SAME source system are never expected to collide
			// (the landing model itself already keeps their names unique), but naming must never silently drop an entity
			for (int i = 2; !taken.add(name.toLowerCase(Locale.ROOT)); i++)
			{
				name = (collides ? StagingRules.pascal(Words.of(table.sourceSystem)) + base : base) + "_" + i;
			}
			nameByKey.put(key, name);
		}
		return new Names(nameByKey, baseNameByKey);
	}

	/** Rule 1: strip a final plural {@code s} of the LAST word only, when that word is longer than 3 letters and does not end with {@code ss}. */
	private static List<String> singularize(final List<String> pWords)
	{
		if (pWords.isEmpty())
		{
			return pWords;
		}
		final List<String> out = new ArrayList<String>(pWords);
		final int last = out.size() - 1;
		final String word = out.get(last);
		if (word.length() > 3 && word.endsWith("s") && !word.endsWith("ss"))
		{
			out.set(last, word.substring(0, word.length() - 1));
		}
		return out;
	}

	private static String pascal(final List<String> pWords)
	{
		final StringBuilder out = new StringBuilder();
		for (final String word : pWords)
		{
			out.append(StagingRules.capitalize(word));
		}
		return out.length() == 0 ? "X" : out.toString();
	}

	private static String camel(final List<String> pWords)
	{
		if (pWords.isEmpty())
		{
			return "x";
		}
		final StringBuilder out = new StringBuilder(pWords.get(0).toLowerCase(Locale.ROOT));
		for (int i = 1; i < pWords.size(); i++)
		{
			out.append(StagingRules.capitalize(pWords.get(i)));
		}
		return out.toString();
	}

	private static String label(final List<String> pWords)
	{
		if (pWords.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(StagingRules.capitalize(pWords.get(0)));
		for (int i = 1; i < pWords.size(); i++)
		{
			out.append(' ').append(pWords.get(i).toLowerCase(Locale.ROOT));
		}
		return out.toString();
	}

	private static String capitalize(final String pWord)
	{
		if (pWord == null || pWord.isEmpty())
		{
			return pWord;
		}
		return Character.toUpperCase(pWord.charAt(0)) + pWord.substring(1).toLowerCase(Locale.ROOT);
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

	// --------------------------------------------------------------------------------------------- fromRelation backfill

	/**
	 * One {@code StagingAttribute} already on EBX, of an entity {@link #propose} left alone because it already existed (see {@code
	 * StagingSuggester.suggest}'s own idempotency rule): everything {@link #backfillFromRelation} needs to decide whether it should now
	 * get {@code fromRelation} filled in, and nothing else - a backfill only ever writes that one field, so this carries no other data
	 * from the row (name/label/type/...) that a full write would.
	 */
	public static final class ExistingAttribute
	{
		public final String id;
		public final String name;
		public final boolean hasReferenceEntity;
		public final boolean hasFromRelation;
		/** Whether {@code StagingAttribute.primaryKey} already carries a value (true or false) - see {@link #backfillPrimaryKey}. */
		public final boolean hasPrimaryKey;
		/** The actual value of {@code StagingAttribute.primaryKey} - false when {@link #hasPrimaryKey} is false too (never set). See {@link #alignTechnicalKeyStatus}. */
		public final boolean primaryKey;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}: this row's own current status - see {@link #alignTechnicalKeyStatus}. */
		public final String status;
		/** {@code RULE}/{@code LLM}/{@code USER}: only a {@code RULE} row is ever backfilled - see {@link #backfillFromRelation}/{@link #backfillPrimaryKey}. */
		public final String origin;

		public ExistingAttribute(final String pId, final String pName, final boolean pHasReferenceEntity, final boolean pHasFromRelation,
			final boolean pHasPrimaryKey, final boolean pPrimaryKey, final String pStatus, final String pOrigin)
		{
			this.id = pId;
			this.name = pName;
			this.hasReferenceEntity = pHasReferenceEntity;
			this.hasFromRelation = pHasFromRelation;
			this.hasPrimaryKey = pHasPrimaryKey;
			this.primaryKey = pPrimaryKey;
			this.status = pStatus;
			this.origin = pOrigin;
		}
	}

	/** One existing {@code StagingAttribute} row to write {@code fromRelation} onto - and nothing else, whatever the row's current status. */
	public static final class Backfill
	{
		public final String attributeId;
		public final String relationId;

		public Backfill(final String pAttributeId, final String pRelationId)
		{
			this.attributeId = pAttributeId;
			this.relationId = pRelationId;
		}
	}

	/**
	 * For one entity {@link #propose} left alone because it already existed: which of its EXISTING attribute rows should now get {@code
	 * fromRelation} filled in, from what a fresh run of the rules proposes for an attribute of the same name (rule 6 is deterministic -
	 * same landing tables and relations in, same reference and relation id out - so "the same name" is enough to match them up without
	 * needing to re-run rule 6 against the existing ids). Never touches a row that:
	 * <ul>
	 * <li>is not origin {@code RULE} - a person's or the model's own reference is never second-guessed by a rule-pass backfill;</li>
	 * <li>has no {@code referenceEntity} - nothing to link;</li>
	 * <li>already has a {@code fromRelation} - already linked, whether by an earlier backfill or by a fresh write.</li>
	 * </ul>
	 * Written before this field existed, in particular: the deployed instance's rule-proposed attributes with a reference (PLAN.md,
	 * 2026-09-20 staging pass) are exactly the case this exists for.
	 */
	public static List<Backfill> backfillFromRelation(final List<ExistingAttribute> pExisting, final List<AttributeProposal> pRuledAttributesOfSameEntity)
	{
		final Map<String, String> relationIdByAttributeName = new LinkedHashMap<String, String>();
		for (final AttributeProposal proposed : pRuledAttributesOfSameEntity)
		{
			if (proposed.fromRelationId != null)
			{
				relationIdByAttributeName.put(proposed.name, proposed.fromRelationId);
			}
		}
		final List<Backfill> out = new ArrayList<Backfill>();
		for (final ExistingAttribute existing : pExisting)
		{
			if (!"RULE".equals(existing.origin) || !existing.hasReferenceEntity || existing.hasFromRelation)
			{
				continue;
			}
			final String relationId = relationIdByAttributeName.get(existing.name);
			if (relationId != null)
			{
				out.add(new Backfill(existing.id, relationId));
			}
		}
		return out;
	}

	// --------------------------------------------------------------------------------------------- primaryKey backfill

	/** One existing {@code StagingAttribute} row to write {@code primaryKey=true}/{@code businessKey=false} onto - and nothing else. */
	public static final class PrimaryKeyBackfill
	{
		public final String attributeId;

		public PrimaryKeyBackfill(final String pAttributeId)
		{
			this.attributeId = pAttributeId;
		}
	}

	/**
	 * For one entity {@link #propose} left alone because it already existed: which of its EXISTING {@code sourceSystem}/{@code sourceId}
	 * attribute rows still need {@code primaryKey} set - the same cautious, name-matched backfill as {@link #backfillFromRelation}, for the
	 * same reason: written before {@code StagingAttribute.primaryKey} existed (PLAN.md, 2026-09-20 staging pass, "à trancher avant de
	 * générer le modèle de staging"). Never touches a row that:
	 * <ul>
	 * <li>is not origin {@code RULE} - a person's or the model's own technical-key attribute is never second-guessed here;</li>
	 * <li>is not named {@code sourceSystem} or {@code sourceId} - {@code primaryKey} is never backfilled onto a plain business column;</li>
	 * <li>already has {@code primaryKey} set (true or false) - already told, whether by an earlier backfill or by a fresh write.</li>
	 * </ul>
	 * A backfilled row also gets {@code businessKey} forced to {@code false} (the caller writes both fields for every id returned here):
	 * before this distinction existed, {@code businessKey} was the only flag {@link #technicalAttribute} set, so an old row still carries
	 * {@code businessKey=true} and must be corrected, not just completed.
	 */
	public static List<PrimaryKeyBackfill> backfillPrimaryKey(final List<ExistingAttribute> pExisting)
	{
		final List<PrimaryKeyBackfill> out = new ArrayList<PrimaryKeyBackfill>();
		for (final ExistingAttribute existing : pExisting)
		{
			if (!"RULE".equals(existing.origin) || existing.hasPrimaryKey)
			{
				continue;
			}
			if (!"sourceSystem".equals(existing.name) && !"sourceId".equals(existing.name))
			{
				continue;
			}
			out.add(new PrimaryKeyBackfill(existing.id));
		}
		return out;
	}

	// --------------------------------------------------------------------------------------- technical key status backfill

	/** One existing {@code StagingAttribute} row (a technical key) to write {@link #targetStatus} onto - and nothing else. */
	public static final class TechnicalKeyStatusBackfill
	{
		public final String attributeId;
		public final String targetStatus;

		public TechnicalKeyStatusBackfill(final String pAttributeId, final String pTargetStatus)
		{
			this.attributeId = pAttributeId;
			this.targetStatus = pTargetStatus;
		}
	}

	/**
	 * For one entity {@link #propose} left alone because it already existed: which of its EXISTING technical-key
	 * ({@code primaryKey=true}) attribute rows need their stored {@code status} aligned to what {@link
	 * TechnicalKeyRule#cascadedStatus} says the entity's own CURRENT status implies - the fix for exactly the case a
	 * coordinator review found live on project {@code Companies} (2026-09-20): {@code Account}/{@code Contact}
	 * accepted through the review screen before it cascaded this automatically, leaving {@code sourceSystem}/{@code
	 * sourceId} stuck {@code PROPOSED} and {@code staging/generate} refusing for the wrong reason. Never touches a
	 * row that:
	 * <ul>
	 * <li>is not a technical key ({@code primaryKey} false) - a business attribute keeps its own, independent decision;</li>
	 * <li>is already {@code APPLIED} - done, the same rule {@code ReviewRules.canDecide} enforces everywhere else;</li>
	 * <li>already carries the target status - nothing to align.</li>
	 * </ul>
	 * {@code pEntityStatus} still {@code PROPOSED} yields no backfill at all ({@link TechnicalKeyRule#cascadedStatus}
	 * returns null): nothing to align a not-yet-decided entity's technical key to.
	 */
	public static List<TechnicalKeyStatusBackfill> alignTechnicalKeyStatus(final String pEntityStatus, final List<ExistingAttribute> pExisting)
	{
		final List<TechnicalKeyStatusBackfill> out = new ArrayList<TechnicalKeyStatusBackfill>();
		final String target = TechnicalKeyRule.cascadedStatus(pEntityStatus);
		if (target == null)
		{
			return out;
		}
		for (final ExistingAttribute existing : pExisting)
		{
			if (!existing.primaryKey || "APPLIED".equals(existing.status) || target.equals(existing.status))
			{
				continue;
			}
			out.add(new TechnicalKeyStatusBackfill(existing.id, target));
		}
		return out;
	}

	// ----------------------------------------------------------------------------------------- technical key creation backfill

	/**
	 * True when NONE of an entity's existing attribute rows carry {@code primaryKey=true} - the more severe case {@link
	 * #backfillPrimaryKey} does not cover: that method only corrects the {@code primaryKey} FLAG on a row already named
	 * {@code sourceSystem}/{@code sourceId}; this is for an entity with no such ROW AT ALL, round 4's live case on project
	 * {@code Companies} (PLAN.md, 2026-09-20): {@code Company}, the canonical (LLM) entity unifying {@code Supplier} and
	 * {@code Account}, accepted by the reviewer with its 6 business attributes, but created by the FIRST LLM run - before
	 * this class (or {@code StagingLlmSuggester}) ever proposed a technical key attribute at all. The exact condition
	 * {@code StagingModel} itself already uses to decide whether to skip an entity for the same reason (see its own
	 * Javadoc, "the rarer case of an accepted entity with no primaryKey attribute row AT ALL").
	 */
	public static boolean isMissingTechnicalKey(final List<ExistingAttribute> pExisting)
	{
		for (final ExistingAttribute existing : pExisting)
		{
			if (existing.hasPrimaryKey && existing.primaryKey)
			{
				return false;
			}
		}
		return true;
	}

	/**
	 * The two technical-key attribute proposals to CREATE for an entity {@link #isMissingTechnicalKey} found none for -
	 * same shape as {@link #technicalAttributeProposals} (a fresh {@code AttrBuilder} each call, so safe to call once per
	 * backfilled entity), with a rationale that also explains what {@code sourceSystem}/{@code sourceId} MEAN for a
	 * canonical, multi-source entity like {@code Company}: even though it is fed by both {@code Supplier} and {@code
	 * Account} rows, one staging ROW of {@code Company} still comes from exactly one source row (a {@code Supplier} row
	 * or an {@code Account} row, never a blend of both) - so the very same pair identifies it; unifying the two sources
	 * is Match and Merge's job, later, not this attribute's.
	 */
	public static List<AttributeProposal> technicalKeyBackfillProposals()
	{
		final List<AttributeProposal> out = new ArrayList<AttributeProposal>();
		for (final AttributeProposal template : StagingRules.technicalAttributeProposals())
		{
			out.add(new AttributeProposal(template.name, template.label, template.dataType, template.maxLength, template.mandatory, template.businessKey,
				template.primaryKey, template.enumerated, template.referenceEntity, template.fromRelationId, template.confidence,
				"Backfilled: this entity had no technical-key attribute yet. Every staging entity is keyed by sourceSystem + sourceId, filled by "
					+ "the transfer, never by the source's own business key. For a canonical entity fed by more than one source table, this still "
					+ "holds unchanged: each staging row still comes from exactly one source row (one Supplier row or one Account row, never a "
					+ "blend of both), so the same sourceSystem/sourceId pair identifies it - unifying the sources is Match and Merge's job, later.",
				template.mapping));
		}
		return out;
	}

	/**
	 * The status {@link #technicalKeyBackfillProposals} should be written with, for an entity whose own current status is
	 * {@code pEntityStatus} - the same rule a stored technical key is kept aligned to ({@link TechnicalKeyRule#cascadedStatus}),
	 * with one difference: a still-{@code PROPOSED} entity has nothing to cascade to yet ({@code cascadedStatus} returns
	 * null for it), but the new rows still need SOME status to be written with - {@code PROPOSED}, the same status any
	 * freshly proposed technical key starts at (see {@link #technicalAttribute}'s own callers).
	 */
	public static String technicalKeyBackfillStatus(final String pEntityStatus)
	{
		final String cascaded = TechnicalKeyRule.cascadedStatus(pEntityStatus);
		return cascaded == null ? "PROPOSED" : cascaded;
	}

	// ------------------------------------------------------------------------------------------------- mapping backfill

	/**
	 * One existing {@code AttributeMapping} row of one existing {@code StagingAttribute}: everything {@link #alignMappingStatus}
	 * needs to decide whether it should now get {@code MappingFollowRule.cascadedStatus} backfilled onto it, and nothing else.
	 */
	public static final class ExistingMapping
	{
		public final String id;
		/** {@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}: this row's own current status. */
		public final String status;
		/** {@code RULE}/{@code LLM}/{@code USER}: only a {@code RULE} or {@code LLM} row is ever backfilled - see {@link MappingFollowRule#isEligible}. */
		public final String origin;

		public ExistingMapping(final String pId, final String pStatus, final String pOrigin)
		{
			this.id = pId;
			this.status = pStatus;
			this.origin = pOrigin;
		}
	}

	/** One existing {@code AttributeMapping} row to write {@link #targetStatus} onto - and nothing else. */
	public static final class MappingStatusBackfill
	{
		public final String mappingId;
		public final String targetStatus;

		public MappingStatusBackfill(final String pMappingId, final String pTargetStatus)
		{
			this.mappingId = pMappingId;
			this.targetStatus = pTargetStatus;
		}
	}

	/**
	 * For one {@code StagingAttribute} already on EBX, whose CURRENT status is {@code pAttributeStatus}: which of its
	 * EXISTING {@code AttributeMapping} rows should now get {@link MappingFollowRule#cascadedStatus} backfilled onto
	 * them - the fix for the exact live case a coordinator review found on project {@code Companies} (2026-09-20): 62
	 * mapping rows still {@code PROPOSED} even though every one of their attributes is {@code APPLIED}, because "a
	 * rule-proposed attribute's mapping is not a separate decision at all" and nobody accepts 62 lines one by one.
	 * Only a row {@link MappingFollowRule#isEligible} says yes to is ever touched - never a person's own {@code USER}
	 * mapping, never one already decided by anyone (accepted or rejected). {@code pAttributeStatus} still {@code
	 * PROPOSED} yields no backfill at all ({@link MappingFollowRule#cascadedStatus} returns null): nothing to align a
	 * not-yet-decided attribute's mappings to.
	 */
	public static List<MappingStatusBackfill> alignMappingStatus(final String pAttributeStatus, final List<ExistingMapping> pExisting)
	{
		final List<MappingStatusBackfill> out = new ArrayList<MappingStatusBackfill>();
		final String target = MappingFollowRule.cascadedStatus(pAttributeStatus);
		if (target == null)
		{
			return out;
		}
		for (final ExistingMapping existing : pExisting)
		{
			if (!MappingFollowRule.isEligible(existing.status, existing.origin))
			{
				continue;
			}
			out.add(new MappingStatusBackfill(existing.id, target));
		}
		return out;
	}
}
