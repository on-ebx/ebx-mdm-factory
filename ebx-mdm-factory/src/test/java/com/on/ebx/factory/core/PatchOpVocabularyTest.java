package com.on.ebx.factory.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.StagingModelDiff;

/**
 * Guards against the exact incident class `docs/MASTER-ZONE.md` &sect;10 documents: {@code
 * master.MasterModel} once emitted a {@code set_element_property} op whose {@code property} was {@code
 * "defaultLabel"} - a TABLE-level facet {@code apply_data_model_patch} does not know about. Every layer of
 * TOOLING short of the real patch call accepted it silently (this pure builder's own JSON, {@code
 * validate_draft}, {@code build_data_model}'s dry run) - only {@code apply_data_model_patch} itself refused
 * it, on a REAL {@code master/generate} call, mid-patch (op 14 of 28), aborting every later op in the same
 * call (the rest of the Company table's own facets, autoIncrement included) with {@code stoppedOnError:
 * true}. Nothing in this codebase's test suite would have caught it before that live call, because no test
 * checked an emitted op's {@code op}/{@code property} against the tool's own vocabulary - only against each
 * op's own expected shape (element_path/value), which "defaultLabel" satisfied perfectly.
 * <p>
 * This test closes that gap: it asserts every operation {@code master.MasterModel}, {@code
 * staging.StagingModel} and {@code staging.StagingModelDiff} emit - the three PURE "patch builders" {@code
 * ebx.MasterGenerator}/{@code ebx.StagingGenerator} feed straight into {@code apply_data_model_patch},
 * unchanged, as {@code operations_json} - uses an {@code op} from {@link #ALLOWED_OPS} and, for {@code
 * set_element_property} specifically, a {@code property} from {@link #ALLOWED_SET_ELEMENT_PROPERTIES}.
 * <p>
 * <b>Both lists below are a literal, DATED copy</b> of what the {@code data-model} MCP server's own tool
 * descriptions said on the date in each list's own comment ({@code apply_data_model_patch}'s description for
 * the op vocabulary; its own refusal message, quoting {@code set_element_property}'s allowed values, for the
 * property list) - re-read them live (that tool's description, or {@code
 * list_operations(name="set_element_property")}) and update this file whenever the server's vocabulary
 * changes. A failure on THIS test after adding a new {@code set_element_property} call anywhere in the three
 * classes above is the point, not a bug in the test: it means either the new property needs adding here
 * (only after confirming LIVE that {@code apply_data_model_patch} actually accepts it) or the new call itself
 * is wrong - exactly the class of mistake that reached a real {@code master/generate} call this time.
 */
public final class PatchOpVocabularyTest
{
	/** {@code apply_data_model_patch}'s own op vocabulary - MEASURED LIVE, 2026-09-22 (its own tool description). */
	private static final Set<String> ALLOWED_OPS = new HashSet<String>(Arrays.asList("create_complex_type", "create_simple_type", "create_table",
		"create_group", "create_field", "set_primary_key", "add_foreign_key", "set_linked_field", "set_association", "set_element_property",
		"unset_element_property", "delete_element"));

	/**
	 * {@code set_element_property}'s own allowed {@code property} values - MEASURED LIVE, 2026-09-22, quoted
	 * verbatim from {@code apply_data_model_patch}'s own refusal message on an unknown property (the exact
	 * incident this test guards against): {@code "defaultLabel"} is deliberately NOT in this list - it is a
	 * TABLE-level facet, {@code set_default_label}'s own job (via {@code execute_operation}), never {@code
	 * set_element_property}'s.
	 */
	private static final Set<String> ALLOWED_SET_ELEMENT_PROPERTIES = new HashSet<String>(Arrays.asList("information", "defaultValue",
		"conversionErrorMessage", "nodeCategory", "comparisonMode", "applyLastModification", "trim", "pattern", "access", "disableValidation",
		"minOccurs", "maxOccurs", "computationRule", "enumeration", "excludedValues", "dynamicEnumerationSource", "nomenclatureSource", "mandatory",
		"uniqueConstraints", "history", "replication", "triggers", "length", "minLength", "maxLength", "totalDigits", "fractionDigits",
		"autoIncrementStart", "autoIncrementStep", "lengthDynamic", "minLengthDynamic", "maxLengthDynamic", "minValue", "maxValue",
		"minValueExclusive", "maxValueExclusive", "minValueDynamic", "maxValueDynamic", "minValueDynamicExclusive", "maxValueDynamicExclusive",
		"widget", "specificEnumeration", "specificNomenclature", "uiBean", "valueFunction", "transformOnExport", "validationRules",
		"inheritedField", "foreignKeyFilter", "foreignKeyMessage", "foreignKeyLabeling", "javaConstraints"));

	public static void main(final String[] pArgs)
	{
		PatchOpVocabularyTest.masterModelOperationsUseOnlyAllowedOpsAndProperties();
		PatchOpVocabularyTest.stagingModelOperationsUseOnlyAllowedOpsAndProperties();
		PatchOpVocabularyTest.stagingModelDiffOperationsUseOnlyAllowedOpsAndProperties();
		System.out.println("PatchOpVocabularyTest OK");
	}

	/**
	 * Every op {@code MasterModel.build} ever emits: the {@code id} field's two autoIncrement facets, a plain
	 * business field's {@code maxLength}, a foreign key field's {@code maxLength}. {@code
	 * osd:table/defaultLabel} is plain DATA now ({@code Result#defaultLabelByTable}), never an op - see that
	 * field's own Javadoc for why (this is the exact regression this whole test class exists to catch).
	 */
	private static void masterModelOperationsUseOnlyAllowedOpsAndProperties()
	{
		final MasterModel.EntityInfo account = new MasterModel.EntityInfo("Account", "Account", "ACCEPTED",
			Arrays.asList(PatchOpVocabularyTest.masterTechnical("sourceSystem", 40), PatchOpVocabularyTest.masterTechnical("sourceId", 255),
				new MasterModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")),
			Collections.<String>emptyList());
		final MasterModel.EntityInfo widget = new MasterModel.EntityInfo("Widget", "Widget", "ACCEPTED",
			Arrays.asList(PatchOpVocabularyTest.masterTechnical("sourceSystem", 40), PatchOpVocabularyTest.masterTechnical("sourceId", 255),
				new MasterModel.AttributeInfo("city", "City", "STRING", Integer.valueOf(80), false, null, "ACCEPTED"),
				new MasterModel.AttributeInfo("accountId", "Account", "STRING", Integer.valueOf(40), false, "Account", "ACCEPTED")),
			Collections.<String>emptyList());
		final MasterModel.Result result = MasterModel.build(Arrays.asList(account, widget));
		PatchOpVocabularyTest.check("MasterModel produced at least one operation to actually exercise this test", result.operations.size() > 0);
		PatchOpVocabularyTest.check("MasterModel found a display field and put it in defaultLabelByTable, NOT in operations (the fix this test guards)",
			result.defaultLabelByTable.containsKey("/root/Widget"));
		PatchOpVocabularyTest.checkVocabulary("MasterModel.build", result.operations);
	}

	/** Every op {@code StagingModel.build} ever emits for a mandatory, maxLength-bearing business field: {@code mandatory}, {@code minOccurs}, {@code maxLength}. */
	private static void stagingModelOperationsUseOnlyAllowedOpsAndProperties()
	{
		final StagingModel.EntityInfo widget = new StagingModel.EntityInfo("Widget", "Widget", "ACCEPTED",
			Arrays.asList(new StagingModel.AttributeInfo("sourceSystem", "Source system", "STRING", Integer.valueOf(40), true, true, null, "ACCEPTED"),
				new StagingModel.AttributeInfo("sourceId", "Source id", "STRING", Integer.valueOf(255), true, true, null, "ACCEPTED"),
				new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), true, false, null, "ACCEPTED")),
			Collections.<String>emptyList());
		final StagingModel.Result result = StagingModel.build(Collections.singletonList(widget));
		PatchOpVocabularyTest.check("StagingModel produced at least one operation to actually exercise this test", result.operations.size() > 0);
		PatchOpVocabularyTest.checkVocabulary("StagingModel.build", result.operations);
	}

	/**
	 * Every op {@code StagingModelDiff.diff} ever emits for a brand-new table (the shape {@code
	 * ebx.MasterGenerator#runRegeneration}/{@code ebx.StagingGenerator} feed to {@code
	 * apply_data_model_patch} for a later-accepted entity): {@code create_table}, {@code create_field}, {@code
	 * set_primary_key}, {@code add_foreign_key}, and a {@code set_element_property} facet.
	 */
	private static void stagingModelDiffOperationsUseOnlyAllowedOpsAndProperties()
	{
		final StagingModel.EntityInfo account = PatchOpVocabularyTest.stagingEntity("Account",
			Arrays.asList(PatchOpVocabularyTest.stagingTechnical("sourceSystem", 40), PatchOpVocabularyTest.stagingTechnical("sourceId", 255),
				PatchOpVocabularyTest.stagingAttribute("name", 255, false)));
		final StagingModel.EntityInfo company = PatchOpVocabularyTest.stagingEntity("Company",
			Arrays.asList(PatchOpVocabularyTest.stagingTechnical("sourceSystem", 40), PatchOpVocabularyTest.stagingTechnical("sourceId", 255),
				new StagingModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), true, false, null, "ACCEPTED"),
				new StagingModel.AttributeInfo("accountId", "Account id", "STRING", Integer.valueOf(40), false, false, "Account", "ACCEPTED")));
		final StagingModel.Result desired = StagingModel.build(Arrays.asList(account, company));
		// only Company is "new" - Account is handed in as already-existing, so the diff's own operations are
		// entirely about Company: table, key fields, set_primary_key, business field, FK field, add_foreign_key, facet
		final StagingModelDiff.Result diff = StagingModelDiff.diff(Collections.singletonList(PatchOpVocabularyTest.existingTableFor(account)), desired);
		PatchOpVocabularyTest.check("StagingModelDiff produced at least one operation to actually exercise this test", diff.operations.size() > 0);
		PatchOpVocabularyTest.checkVocabulary("StagingModelDiff.diff", diff.operations);
	}

	// --------------------------------------------------------------------------------------------------------- checks

	private static void checkVocabulary(final String pSource, final ArrayNode pOperations)
	{
		for (final JsonNode op : pOperations)
		{
			final String opName = op.path("op").asText(null);
			PatchOpVocabularyTest.check(pSource + ": op \"" + opName + "\" is not in apply_data_model_patch's own vocabulary (" + op + ")",
				PatchOpVocabularyTest.ALLOWED_OPS.contains(opName));
			if ("set_element_property".equals(opName))
			{
				final String property = op.path("arguments").path("property").asText(null);
				PatchOpVocabularyTest.check(
					pSource + ": set_element_property property \"" + property + "\" is not in set_element_property's own allowed list (" + op + ")",
					PatchOpVocabularyTest.ALLOWED_SET_ELEMENT_PROPERTIES.contains(property));
			}
		}
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static MasterModel.AttributeInfo masterTechnical(final String pName, final int pMaxLength)
	{
		return new MasterModel.AttributeInfo(pName, "sourceSystem".equals(pName) ? "Source system" : "Source id", "STRING", Integer.valueOf(pMaxLength), true,
			null, "ACCEPTED");
	}

	private static StagingModel.EntityInfo stagingEntity(final String pName, final List<StagingModel.AttributeInfo> pAttributes)
	{
		return new StagingModel.EntityInfo(pName, pName, "ACCEPTED", pAttributes, Collections.<String>emptyList());
	}

	private static StagingModel.AttributeInfo stagingTechnical(final String pName, final int pMaxLength)
	{
		return new StagingModel.AttributeInfo(pName, "sourceSystem".equals(pName) ? "Source system" : "Source id", "STRING", Integer.valueOf(pMaxLength), true,
			true, null, "ACCEPTED");
	}

	private static StagingModel.AttributeInfo stagingAttribute(final String pName, final int pMaxLength, final boolean pMandatory)
	{
		return new StagingModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(pMaxLength), pMandatory, false, null, "ACCEPTED");
	}

	/** Same convention as {@code staging.StagingModelDiffTest}'s own private copy: a from-scratch {@code ExistingTable} matching an already-generated entity's shape exactly. */
	private static StagingModelDiff.ExistingTable existingTableFor(final StagingModel.EntityInfo pEntity)
	{
		final List<StagingModelDiff.ExistingField> fields = new java.util.ArrayList<StagingModelDiff.ExistingField>();
		for (final StagingModel.AttributeInfo attribute : pEntity.attributes)
		{
			fields.add(new StagingModelDiff.ExistingField(attribute.name, "string", attribute.primaryKey, attribute.mandatory, attribute.maxLength, false, null));
		}
		return new StagingModelDiff.ExistingTable(pEntity.name, fields);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
