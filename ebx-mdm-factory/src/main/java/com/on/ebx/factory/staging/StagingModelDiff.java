package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Compares the staging model's LIVE shape - {@link #parseExisting}, from what {@code
 * data-model.get_data_model(deep=true)} reports on the real, already-published model - against the shape
 * {@link StagingModel#build} computes from the CURRENTLY accepted proposals, and classifies every
 * difference as ADDITIVE (safe to apply without asking: a new table, a new optional field, a new foreign
 * key field, a longer {@code maxLength}) or NON-ADDITIVE (never applied automatically, whatever the caller
 * asks: a removed table or field, a type change, a shorter {@code maxLength}, optional becoming mandatory,
 * a foreign key added to or changed on a field that already existed, or anything touching the primary key
 * - each of these can lose data already loaded, or refuse a row that saved cleanly before). {@link #diff}
 * also builds the {@code apply_data_model_patch} operations for the additive side, in the order {@code
 * data_model_guide}'s own ordering rule asks for (tables, then their primary-key fields, then {@code
 * set_primary_key}, then the other fields, then foreign keys, then facets) - {@code StagingGenerator} only
 * has to call {@code apply_data_model_patch} once with {@link Result#operations}, then re-publish.
 * <p>
 * Op shapes used here - {@code create_table(table_path)}, {@code create_field(field_path, field_type)},
 * {@code set_primary_key(table_path, fields)} (comma-separated, safe/non-destructive/REPLACE for a
 * composite key - used here only on a table that has none yet, right after creating its two key fields, so
 * "replace" is moot), {@code add_foreign_key(field_path, target_table_path)} ("Field must already be
 * string"), {@code set_element_property(element_path, property, value)} - read live from the tool
 * contracts, 2026-09-20 (see {@code StagingGenerator}'s own Javadoc for what else was read live that day).
 * A composite-key target for {@code add_foreign_key} specifically (a NEW FK field created through a patch,
 * as opposed to {@link StagingModel}'s own tree-based FK, already confirmed live) was never tried live -
 * flagged in {@code StagingGenerator}'s Javadoc as the one unverified assumption in this path.
 * <p>
 * Pure: no EBX class anywhere in this file - Jackson only, the same convention as {@link StagingModel}
 * itself - so this is unit-tested without an EBX runtime; {@code StagingGenerator} is the only caller that
 * reads the live model and hands it here as plain {@link ExistingTable}/{@link ExistingField} values.
 */
public final class StagingModelDiff
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private StagingModelDiff()
	{
	}

	// --------------------------------------------------------------------------------------------------------- input

	/** One field of the LIVE model, exactly as {@link #parseExisting} reads it back from {@code get_data_model(deep=true)}. */
	public static final class ExistingField
	{
		public final String name;
		/** {@code string}/{@code int}/{@code decimal}/{@code boolean}/{@code date}/{@code dateTime}/{@code text}, as EBX itself reports it. */
		public final String type;
		public final boolean primaryKey;
		/** {@code osd:checkNullInput}, read from the field's own constraints - null/absent reads as false, the same convention {@code StagingModel} writes with. */
		public final boolean mandatory;
		public final Integer maxLength;
		public final boolean isForeignKey;
		public final String foreignKeyTargetPath;
		/**
		 * The field's own {@code autoIncrementStart} facet, when set - null when never applied (an {@code int}
		 * primary-key field with no auto-increment facet at all, e.g. a table a patch never fully reached - see
		 * {@code master.MasterFacetRepair}'s own Javadoc). Absent from every caller of the 7-argument constructor
		 * below (every staging fixture: staging's own primary key is {@code sourceSystem}+{@code sourceId}, never
		 * auto-increment) - only {@code master.MasterFacetRepair}'s caller ({@link #parseExisting}) ever reads it.
		 */
		public final Integer autoIncrementStart;

		/** Convenience overload for every caller that never reads the auto-increment facet (every staging fixture, and every test written before 2026-09-22): defaults it to null (never set). */
		public ExistingField(final String pName, final String pType, final boolean pPrimaryKey, final boolean pMandatory, final Integer pMaxLength,
			final boolean pIsForeignKey, final String pForeignKeyTargetPath)
		{
			this(pName, pType, pPrimaryKey, pMandatory, pMaxLength, pIsForeignKey, pForeignKeyTargetPath, null);
		}

		public ExistingField(final String pName, final String pType, final boolean pPrimaryKey, final boolean pMandatory, final Integer pMaxLength,
			final boolean pIsForeignKey, final String pForeignKeyTargetPath, final Integer pAutoIncrementStart)
		{
			this.name = pName;
			this.type = pType;
			this.primaryKey = pPrimaryKey;
			this.mandatory = pMandatory;
			this.maxLength = pMaxLength;
			this.isForeignKey = pIsForeignKey;
			this.foreignKeyTargetPath = pForeignKeyTargetPath;
			this.autoIncrementStart = pAutoIncrementStart;
		}
	}

	/** One table of the LIVE model. {@link #name} is the last path segment ({@code /root/Account} -&gt; {@code Account}), the same name {@code StagingModel} uses for an entity. */
	public static final class ExistingTable
	{
		public final String name;
		public final List<ExistingField> fields;
		/**
		 * The table's own {@code osd:table/defaultLabel}, as {@code get_data_model(deep=true)} reports it under
		 * {@code tableProperties.defaultLabel} - null when absent or blank (never set, e.g. a table a patch never
		 * fully reached - see {@code master.MasterFacetRepair}). Absent from every caller of the 2-argument
		 * constructor below (every test written before 2026-09-22, and {@code StagingGenerator}'s own diff: a
		 * staging table never gets a generated {@code defaultLabel} at all).
		 */
		public final String defaultLabel;

		/** Convenience overload for every caller that never reads the table-level defaultLabel: defaults it to null (never set). */
		public ExistingTable(final String pName, final List<ExistingField> pFields)
		{
			this(pName, pFields, null);
		}

		public ExistingTable(final String pName, final List<ExistingField> pFields, final String pDefaultLabel)
		{
			this.name = pName;
			this.fields = pFields == null ? new ArrayList<ExistingField>() : pFields;
			this.defaultLabel = pDefaultLabel == null || pDefaultLabel.trim().isEmpty() ? null : pDefaultLabel;
		}

		/** Public (not package-private): {@code ebx.CapabilityApplier} (a different package) reuses this exact lookup to check a field exists before declaring a constraint on it. */
		public ExistingField field(final String pName)
		{
			for (final ExistingField field : this.fields)
			{
				if (field.name.equals(pName))
				{
					return field;
				}
			}
			return null;
		}
	}

	/**
	 * Reads {@code get_data_model(deep=true)}'s own JSON (the {@code tables[]} array) into plain {@link
	 * ExistingTable}/{@link ExistingField} values - the exact shape measured live on {@code Companies_Staging},
	 * 2026-09-20: {@code minOccurs}/{@code maxOccurs} come back as STRINGS ("1"), {@code maxLength} inside
	 * {@code constraints} is also a STRING ("40"), {@code isForeignKey}/{@code isPrimaryKey} are a real JSON
	 * boolean when true and simply ABSENT (null) when false - never a literal {@code false} - and {@code
	 * constraints.mandatory} is absent entirely on a field nothing ever marked mandatory, not present-and-false.
	 * Pure: {@code pTables} is the raw {@code JsonNode} of the tool's own {@code tables} field, handed in by the
	 * caller after the one live read - nothing here re-reads anything.
	 * <p>
	 * <b>{@code constraints.autoIncrementStart} and {@code tableProperties.defaultLabel}</b> - added 2026-09-22
	 * for {@code master.MasterFacetRepair} (`docs/MASTER-ZONE.md` &sect;10's own incident: a patch aborted
	 * mid-way leaves a promoted table published with no auto-increment facet and no default label at all, and
	 * {@code MasterGenerator}'s own "genuinely new table" gate never revisits an already-existing one to check).
	 * {@code constraints.autoIncrementStart} is NOT independently MEASURED LIVE this task (no EBX access, per
	 * this task's own constraint) - it is inferred by analogy with the two facet keys directly above that ARE
	 * measured (`constraints.mandatory`/`constraints.maxLength`, both bare {@code settableFacets} tokens used
	 * as-is for the JSON key) and with {@code data_model_schema}'s own {@code settableFacets} listing
	 * {@code autoIncrementStart}/{@code autoIncrementStep} as ordinary tokens of the exact same vocabulary
	 * ({@code master.MasterModel}, its class Javadoc, "settableFacets carries autoIncrementStart/autoIncrementStep
	 * as ordinary set_element_property tokens"). If this specific key name turns out wrong once someone can check
	 * it live, {@code MasterFacetRepair} fails SAFE, not silently: the facet reads as "always absent", so it is
	 * always (redundantly) reapplied - a {@code set_element_property} of {@code autoIncrementStart=1}/
	 * {@code autoIncrementStep=1} to a field that already carries exactly that value is the same call with the
	 * same value, never a destructive one (this project's own facet value is a hardcoded constant, never a
	 * project-specific setting - {@code MasterModel.buildTable} never derives it from anything). {@code
	 * tableProperties.defaultLabel} is the coordinator's own literal specification of this task's incident brief,
	 * not independently re-derived here.
	 */
	public static List<ExistingTable> parseExisting(final JsonNode pTables)
	{
		final List<ExistingTable> out = new ArrayList<ExistingTable>();
		if (pTables == null || !pTables.isArray())
		{
			return out;
		}
		for (final JsonNode table : pTables)
		{
			final String path = table.path("path").asText("");
			final String name = path.contains("/") ? path.substring(path.lastIndexOf('/') + 1) : path;
			final List<ExistingField> fields = new ArrayList<ExistingField>();
			for (final JsonNode fieldNode : table.path("fields"))
			{
				final JsonNode field = fieldNode.path("field");
				final JsonNode constraints = fieldNode.path("constraints");
				final String fieldName = field.path("name").asText("");
				final String type = field.path("type").asText("");
				final boolean primaryKey = field.path("isPrimaryKey").asBoolean(false);
				final boolean mandatory = constraints.path("mandatory").asBoolean(false);
				final Integer maxLength = constraints.has("maxLength") && !constraints.path("maxLength").isNull()
					? Integer.valueOf(constraints.path("maxLength").asInt()) : null;
				final boolean isForeignKey = field.path("isForeignKey").asBoolean(false);
				final String foreignKeyTargetPath = isForeignKey ? field.path("foreignKeyTargetPath").asText(null) : null;
				final Integer autoIncrementStart = constraints.has("autoIncrementStart") && !constraints.path("autoIncrementStart").isNull()
					? Integer.valueOf(constraints.path("autoIncrementStart").asInt()) : null;
				fields.add(new ExistingField(fieldName, type, primaryKey, mandatory, maxLength, isForeignKey, foreignKeyTargetPath, autoIncrementStart));
			}
			final String defaultLabel = table.path("tableProperties").path("defaultLabel").asText(null);
			out.add(new ExistingTable(name, fields, defaultLabel));
		}
		return out;
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** One difference {@link #diff} found, classified, naming the entity (table) it belongs to. */
	public static final class Change
	{
		public final boolean additive;
		public final String entityName;
		public final String description;

		Change(final boolean pAdditive, final String pEntityName, final String pDescription)
		{
			this.additive = pAdditive;
			this.entityName = pEntityName;
			this.description = pDescription;
		}
	}

	public static final class Result
	{
		public final List<Change> additive;
		public final List<Change> nonAdditive;
		/** {@code operations_json} for {@code apply_data_model_patch}: only the additive changes, already ordered; empty (never null) when there is nothing additive to apply. */
		public final ArrayNode operations;

		Result(final List<Change> pAdditive, final List<Change> pNonAdditive, final ArrayNode pOperations)
		{
			this.additive = pAdditive;
			this.nonAdditive = pNonAdditive;
			this.operations = pOperations;
		}

		/** True when the live model already matches what the accepted proposals describe - nothing to do at all, additive or not. */
		public boolean isUpToDate()
		{
			return this.additive.isEmpty() && this.nonAdditive.isEmpty();
		}

		/** Names of every entity with at least one NON-ADDITIVE change of its own: {@code StagingGenerator} must not mark these {@code APPLIED} - their live shape does not (yet) match what was accepted. */
		public List<String> blockedEntityNames()
		{
			final List<String> out = new ArrayList<String>();
			for (final Change change : this.nonAdditive)
			{
				if (!out.contains(change.entityName))
				{
					out.add(change.entityName);
				}
			}
			return out;
		}
	}

	// --------------------------------------------------------------------------------------------------------- diff

	/** Convenience overload for a caller holding a {@code StagingModel.Result} directly (every staging caller): delegates to {@link #diff(List, JsonNode, ArrayNode)}. */
	public static Result diff(final List<ExistingTable> pExisting, final StagingModel.Result pDesired)
	{
		return StagingModelDiff.diff(pExisting, pDesired.tree, pDesired.operations);
	}

	/**
	 * The generic form: {@code pDesiredTree}/{@code pDesiredOperations} are exactly the {@code
	 * build_data_model}-tree-plus-{@code apply_data_model_patch}-operations shape {@link StagingModel.Result}
	 * and {@code master.MasterModel.Result} both produce (same field names: {@code tables[].path},
	 * {@code primaryKey[]}, {@code fields[].field.{name,path,type,isForeignKey,foreignKeyTargetPath}}, and
	 * {@code set_element_property} operations for {@code maxLength}/{@code mandatory}) - generalised so
	 * {@code ebx.MasterGenerator}'s regeneration path can reuse this whole diff engine instead of a second copy
	 * of it, per the coordinator's brief for the master-zone generator ("do not duplicate StagingModelDiff:
	 * generalise it if needed"). This class stays otherwise unaware of which caller it is: {@code StagingModel}
	 * is still imported only for the convenience overload above and for {@link #ExistingField}/{@link
	 * #ExistingTable}'s own doc references, never for anything this method itself reads.
	 */
	public static Result diff(final List<ExistingTable> pExisting, final JsonNode pDesiredTree, final ArrayNode pDesiredOperations)
	{
		final Map<String, ExistingTable> existingByName = new LinkedHashMap<String, ExistingTable>();
		for (final ExistingTable table : pExisting)
		{
			existingByName.put(table.name, table);
		}
		final List<DesiredTable> desiredTables = StagingModelDiff.desiredShapes(pDesiredTree, pDesiredOperations);
		final Map<String, DesiredTable> desiredByName = new LinkedHashMap<String, DesiredTable>();
		for (final DesiredTable table : desiredTables)
		{
			desiredByName.put(table.name, table);
		}

		final List<Change> additive = new ArrayList<Change>();
		final List<Change> nonAdditive = new ArrayList<Change>();
		final ArrayNode createTableOps = StagingModelDiff.MAPPER.createArrayNode();
		final ArrayNode keyFieldOps = StagingModelDiff.MAPPER.createArrayNode();
		final ArrayNode primaryKeyOps = StagingModelDiff.MAPPER.createArrayNode();
		final ArrayNode fieldOps = StagingModelDiff.MAPPER.createArrayNode();
		final ArrayNode foreignKeyOps = StagingModelDiff.MAPPER.createArrayNode();
		final ArrayNode facetOps = StagingModelDiff.MAPPER.createArrayNode();

		// a table the live model still has but the accepted proposals no longer describe: never dropped automatically
		for (final ExistingTable existingTable : pExisting)
		{
			if (!desiredByName.containsKey(existingTable.name))
			{
				nonAdditive.add(new Change(false, existingTable.name,
					existingTable.name + ": table would be removed (no longer an accepted entity) - never applied automatically."));
			}
		}

		for (final DesiredTable table : desiredTables)
		{
			final ExistingTable existingTable = existingByName.get(table.name);
			if (existingTable == null)
			{
				StagingModelDiff.diffNewTable(table, additive, createTableOps, keyFieldOps, primaryKeyOps, fieldOps, foreignKeyOps, facetOps);
				continue;
			}
			StagingModelDiff.diffExistingTable(table, existingTable, additive, nonAdditive, fieldOps, foreignKeyOps, facetOps);
		}

		final ArrayNode operations = StagingModelDiff.MAPPER.createArrayNode();
		operations.addAll(createTableOps);
		operations.addAll(keyFieldOps);
		operations.addAll(primaryKeyOps);
		operations.addAll(fieldOps);
		operations.addAll(foreignKeyOps);
		operations.addAll(facetOps);
		return new Result(additive, nonAdditive, operations);
	}

	private static void diffNewTable(final DesiredTable pTable, final List<Change> pAdditive, final ArrayNode pCreateTableOps, final ArrayNode pKeyFieldOps,
		final ArrayNode pPrimaryKeyOps, final ArrayNode pFieldOps, final ArrayNode pForeignKeyOps, final ArrayNode pFacetOps)
	{
		pAdditive.add(new Change(true, pTable.name, pTable.name + ": new table (" + pTable.fields.size() + " field(s))."));
		pCreateTableOps.add(StagingModelDiff.createTableOp(pTable.path));
		final List<String> keyFieldNames = new ArrayList<String>();
		for (final DesiredField field : pTable.fields)
		{
			if (!field.primaryKey)
			{
				continue;
			}
			keyFieldNames.add(field.name);
			pKeyFieldOps.add(StagingModelDiff.createFieldOp(field.path, field.type));
		}
		if (!keyFieldNames.isEmpty())
		{
			pPrimaryKeyOps.add(StagingModelDiff.setPrimaryKeyOp(pTable.path, keyFieldNames));
		}
		for (final DesiredField field : pTable.fields)
		{
			if (field.primaryKey)
			{
				continue;
			}
			pFieldOps.add(StagingModelDiff.createFieldOp(field.path, field.type));
			if (field.isForeignKey)
			{
				pForeignKeyOps.add(StagingModelDiff.addForeignKeyOp(field.path, field.foreignKeyTargetPath));
			}
			StagingModelDiff.addFacetOps(pFacetOps, field);
		}
	}

	private static void diffExistingTable(final DesiredTable pTable, final ExistingTable pExistingTable, final List<Change> pAdditive,
		final List<Change> pNonAdditive, final ArrayNode pFieldOps, final ArrayNode pForeignKeyOps, final ArrayNode pFacetOps)
	{
		final List<String> desiredFieldNames = new ArrayList<String>();
		for (final DesiredField field : pTable.fields)
		{
			desiredFieldNames.add(field.name);
			final ExistingField existingField = pExistingTable.field(field.name);
			if (existingField == null)
			{
				if (field.primaryKey)
				{
					// should not happen (sourceSystem/sourceId are fixed once a table exists) - refused, never guessed at
					pNonAdditive.add(new Change(false, pTable.name, pTable.name + "." + field.name + ": would add a primary-key field - touches the primary key, never applied."));
					continue;
				}
				pAdditive.add(new Change(true, pTable.name, pTable.name + "." + field.name + ": new field."));
				pFieldOps.add(StagingModelDiff.createFieldOp(field.path, field.type));
				if (field.isForeignKey)
				{
					pForeignKeyOps.add(StagingModelDiff.addForeignKeyOp(field.path, field.foreignKeyTargetPath));
				}
				StagingModelDiff.addFacetOps(pFacetOps, field);
				continue;
			}
			StagingModelDiff.diffField(pTable, field, existingField, pAdditive, pNonAdditive, pFacetOps);
		}
		for (final ExistingField existingField : pExistingTable.fields)
		{
			if (!desiredFieldNames.contains(existingField.name))
			{
				pNonAdditive
					.add(new Change(false, pTable.name, pTable.name + "." + existingField.name + ": field would be removed - never applied automatically."));
			}
		}
	}

	private static void diffField(final DesiredTable pTable, final DesiredField pField, final ExistingField pExisting, final List<Change> pAdditive,
		final List<Change> pNonAdditive, final ArrayNode pFacetOps)
	{
		final String element = pTable.name + "." + pField.name;
		if (!pField.type.equals(pExisting.type))
		{
			pNonAdditive.add(new Change(false, pTable.name, element + ": type would change from " + pExisting.type + " to " + pField.type + " - never applied."));
			return;
		}
		if (pField.primaryKey != pExisting.primaryKey)
		{
			pNonAdditive.add(new Change(false, pTable.name, element + ": would change the primary key - never applied."));
			return;
		}
		if (pField.isForeignKey != pExisting.isForeignKey
			|| (pField.isForeignKey && !StagingModelDiff.equalsOrBothNull(pField.foreignKeyTargetPath, pExisting.foreignKeyTargetPath)))
		{
			pNonAdditive.add(new Change(false, pTable.name, element + ": would add or retarget a foreign key on a field that already exists - never applied."));
			return;
		}
		if (pField.maxLength != null && !pField.maxLength.equals(pExisting.maxLength))
		{
			// pExisting.maxLength == null reads as "no cap at all yet" (a field created but never patched with one) - treated the
			// same as "shorter than infinite", i.e. additive, same as any other lengthening
			if (pExisting.maxLength == null || pField.maxLength.intValue() > pExisting.maxLength.intValue())
			{
				pAdditive.add(new Change(true, pTable.name,
					element + ": maxLength " + (pExisting.maxLength == null ? "unset" : pExisting.maxLength) + " -> " + pField.maxLength + " (longer)."));
				pFacetOps.add(StagingModelDiff.setElementPropertyOp(pField.path, "maxLength", String.valueOf(pField.maxLength)));
			}
			else
			{
				pNonAdditive.add(new Change(false, pTable.name,
					element + ": maxLength would shrink from " + pExisting.maxLength + " to " + pField.maxLength + " - never applied."));
			}
		}
		if (pField.mandatory != pExisting.mandatory)
		{
			if (pField.mandatory)
			{
				pNonAdditive.add(new Change(false, pTable.name, element + ": would become mandatory - never applied (an already-loaded row might be empty)."));
			}
			else
			{
				pAdditive.add(new Change(true, pTable.name, element + ": no longer mandatory."));
				pFacetOps.add(StagingModelDiff.setElementPropertyOp(pField.path, "mandatory", "false"));
				pFacetOps.add(StagingModelDiff.setElementPropertyOp(pField.path, "minOccurs", "0"));
			}
		}
	}

	private static boolean equalsOrBothNull(final String pLeft, final String pRight)
	{
		return pLeft == null ? pRight == null : pLeft.equals(pRight);
	}

	// -------------------------------------------------------------------------------------------------- desired shape

	private static final class DesiredField
	{
		final String name;
		final String path;
		final String type;
		final boolean primaryKey;
		final boolean isForeignKey;
		final String foreignKeyTargetPath;
		final Integer maxLength;
		final boolean mandatory;

		DesiredField(final String pName, final String pPath, final String pType, final boolean pPrimaryKey, final boolean pIsForeignKey,
			final String pForeignKeyTargetPath, final Integer pMaxLength, final boolean pMandatory)
		{
			this.name = pName;
			this.path = pPath;
			this.type = pType;
			this.primaryKey = pPrimaryKey;
			this.isForeignKey = pIsForeignKey;
			this.foreignKeyTargetPath = pForeignKeyTargetPath;
			this.maxLength = pMaxLength;
			this.mandatory = pMandatory;
		}
	}

	private static final class DesiredTable
	{
		final String name;
		final String path;
		final List<DesiredField> fields;

		DesiredTable(final String pName, final String pPath, final List<DesiredField> pFields)
		{
			this.name = pName;
			this.path = pPath;
			this.fields = pFields;
		}
	}

	/** Reads a desired tree (structure) merged with its desired operations (the facets the tree cannot carry) into the same shape {@link #parseExisting} produces, so the two sides compare directly. */
	private static List<DesiredTable> desiredShapes(final JsonNode pDesiredTree, final ArrayNode pDesiredOperations)
	{
		final Map<String, Map<String, String>> facetsByElementPath = new LinkedHashMap<String, Map<String, String>>();
		for (final JsonNode op : pDesiredOperations)
		{
			if (!"set_element_property".equals(op.path("op").asText()))
			{
				continue;
			}
			final JsonNode arguments = op.path("arguments");
			final String elementPath = arguments.path("element_path").asText();
			Map<String, String> facets = facetsByElementPath.get(elementPath);
			if (facets == null)
			{
				facets = new LinkedHashMap<String, String>();
				facetsByElementPath.put(elementPath, facets);
			}
			facets.put(arguments.path("property").asText(), arguments.path("value").asText());
		}

		final List<DesiredTable> out = new ArrayList<DesiredTable>();
		for (final JsonNode table : pDesiredTree.path("tables"))
		{
			final String tablePath = table.path("path").asText();
			final String tableName = tablePath.substring(tablePath.lastIndexOf('/') + 1);
			final List<String> primaryKeyNames = new ArrayList<String>();
			for (final JsonNode key : table.path("primaryKey"))
			{
				primaryKeyNames.add(key.asText());
			}
			final List<DesiredField> fields = new ArrayList<DesiredField>();
			for (final JsonNode fieldNode : table.path("fields"))
			{
				final JsonNode field = fieldNode.path("field");
				final String name = field.path("name").asText();
				final String fieldPath = field.path("path").asText();
				final String type = field.path("type").asText();
				final boolean primaryKey = primaryKeyNames.contains(name);
				final boolean isForeignKey = field.path("isForeignKey").asBoolean(false);
				final String foreignKeyTargetPath = isForeignKey ? field.path("foreignKeyTargetPath").asText(null) : null;
				final Map<String, String> facets = facetsByElementPath.get(fieldPath);
				final Integer maxLength = facets != null && facets.containsKey("maxLength") ? Integer.valueOf(facets.get("maxLength")) : null;
				final boolean mandatory = facets != null && "true".equals(facets.get("mandatory"));
				fields.add(new DesiredField(name, fieldPath, type, primaryKey, isForeignKey, foreignKeyTargetPath, maxLength, mandatory));
			}
			out.add(new DesiredTable(tableName, tablePath, fields));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------------ ops

	private static void addFacetOps(final ArrayNode pFacetOps, final DesiredField pField)
	{
		if (pField.maxLength != null)
		{
			pFacetOps.add(StagingModelDiff.setElementPropertyOp(pField.path, "maxLength", String.valueOf(pField.maxLength)));
		}
		if (pField.mandatory)
		{
			pFacetOps.add(StagingModelDiff.setElementPropertyOp(pField.path, "mandatory", "true"));
			pFacetOps.add(StagingModelDiff.setElementPropertyOp(pField.path, "minOccurs", "1"));
		}
	}

	private static ObjectNode createTableOp(final String pTablePath)
	{
		final ObjectNode op = StagingModelDiff.MAPPER.createObjectNode();
		op.put("op", "create_table");
		op.putObject("arguments").put("table_path", pTablePath);
		return op;
	}

	private static ObjectNode createFieldOp(final String pFieldPath, final String pFieldType)
	{
		final ObjectNode op = StagingModelDiff.MAPPER.createObjectNode();
		op.put("op", "create_field");
		final ObjectNode arguments = op.putObject("arguments");
		arguments.put("field_path", pFieldPath);
		arguments.put("field_type", pFieldType);
		return op;
	}

	private static ObjectNode setPrimaryKeyOp(final String pTablePath, final List<String> pFieldNames)
	{
		final ObjectNode op = StagingModelDiff.MAPPER.createObjectNode();
		op.put("op", "set_primary_key");
		final ObjectNode arguments = op.putObject("arguments");
		arguments.put("table_path", pTablePath);
		final StringBuilder fields = new StringBuilder();
		for (int i = 0; i < pFieldNames.size(); i++)
		{
			if (i > 0)
			{
				fields.append(',');
			}
			fields.append(pFieldNames.get(i));
		}
		arguments.put("fields", fields.toString());
		return op;
	}

	private static ObjectNode addForeignKeyOp(final String pFieldPath, final String pTargetTablePath)
	{
		final ObjectNode op = StagingModelDiff.MAPPER.createObjectNode();
		op.put("op", "add_foreign_key");
		final ObjectNode arguments = op.putObject("arguments");
		arguments.put("field_path", pFieldPath);
		arguments.put("target_table_path", pTargetTablePath);
		return op;
	}

	private static ObjectNode setElementPropertyOp(final String pElementPath, final String pProperty, final String pValue)
	{
		final ObjectNode op = StagingModelDiff.MAPPER.createObjectNode();
		op.put("op", "set_element_property");
		final ObjectNode arguments = op.putObject("arguments");
		arguments.put("element_path", pElementPath);
		arguments.put("property", pProperty);
		arguments.put("value", pValue);
		return op;
	}
}
