package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;

import com.on.ebx.factory.staging.StagingModelDiff;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. Fixture reproduces the
 * live half-built tree of `docs/MASTER-ZONE.md` &sect;10's own incident, 2026-09-22: {@code Contact} fully
 * patched (auto-increment facet AND defaultLabel both present), {@code Company} published with neither (the
 * patch that would have applied them aborted before reaching it).
 */
public final class MasterFacetRepairTest
{
	public static void main(final String[] pArgs)
	{
		MasterFacetRepairTest.repairsMissingAutoIncrement_onAnAlreadyExistingTable();
		MasterFacetRepairTest.repairsMissingDefaultLabel_onAnAlreadyExistingTable();
		MasterFacetRepairTest.completeTable_needsNoRepair();
		MasterFacetRepairTest.tableNotYetBuiltAtAll_isNotARepairCandidate();
		MasterFacetRepairTest.resultIsEmpty_whenNothingNeedsRepair();
		System.out.println("MasterFacetRepairTest OK");
	}

	/** The heart of the incident: Company exists live, but its own {@code id} field carries no {@code autoIncrementStart} facet at all - repaired regardless of the diff engine's own "is this table new this run" question. */
	private static void repairsMissingAutoIncrement_onAnAlreadyExistingTable()
	{
		final MasterModel.Result planned = MasterFacetRepairTest.companyAndContactPlanned();
		final List<StagingModelDiff.ExistingTable> existing = Arrays.asList(MasterFacetRepairTest.contactComplete(), MasterFacetRepairTest.companyMissingAutoIncrementAndLabel());

		final MasterFacetRepair.Result repair = MasterFacetRepair.decide(existing, planned);

		MasterFacetRepairTest.check("Company's autoIncrementStart=1 op is present", MasterFacetRepairTest.hasSetElementPropertyOp(repair.operations, "/root/Company/id",
			"autoIncrementStart", "1"));
		MasterFacetRepairTest.check("Company's autoIncrementStep=1 op is present",
			MasterFacetRepairTest.hasSetElementPropertyOp(repair.operations, "/root/Company/id", "autoIncrementStep", "1"));
		MasterFacetRepairTest.check("Contact's id is untouched (already complete) - no op names /root/Contact/id",
			!MasterFacetRepairTest.hasAnyOpFor(repair.operations, "/root/Contact/id"));
		MasterFacetRepairTest.check("a repair note names Company", MasterFacetRepairTest.anyContains(repair.notes, "/root/Company: id field has no autoIncrementStart"));
	}

	private static void repairsMissingDefaultLabel_onAnAlreadyExistingTable()
	{
		final MasterModel.Result planned = MasterFacetRepairTest.companyAndContactPlanned();
		final List<StagingModelDiff.ExistingTable> existing = Arrays.asList(MasterFacetRepairTest.contactComplete(), MasterFacetRepairTest.companyMissingAutoIncrementAndLabel());

		final MasterFacetRepair.Result repair = MasterFacetRepair.decide(existing, planned);

		MasterFacetRepairTest.check("Company's defaultLabel is queued for repair", "${./name}".equals(repair.labelsToApply.get("/root/Company")));
		MasterFacetRepairTest.check("Contact is not queued for a label repair (already has one)", !repair.labelsToApply.containsKey("/root/Contact"));
		MasterFacetRepairTest.check("a repair note names the Company label", MasterFacetRepairTest.anyContains(repair.notes, "/root/Company: defaultLabel missing"));
	}

	/** A table that already carries both facets is left alone - no redundant op, no redundant note. */
	private static void completeTable_needsNoRepair()
	{
		final MasterModel.Result planned = MasterFacetRepairTest.companyAndContactPlanned();
		final List<StagingModelDiff.ExistingTable> existing = Arrays.asList(MasterFacetRepairTest.contactComplete(), MasterFacetRepairTest.companyComplete());

		final MasterFacetRepair.Result repair = MasterFacetRepair.decide(existing, planned);

		MasterFacetRepairTest.check("nothing to repair when both tables are already complete", repair.isEmpty());
	}

	/** A promoted entity with no live table AT ALL yet is the ordinary "new table" path's job, not this class' - never produces a repair op. */
	private static void tableNotYetBuiltAtAll_isNotARepairCandidate()
	{
		final MasterModel.Result planned = MasterFacetRepairTest.companyAndContactPlanned();
		final List<StagingModelDiff.ExistingTable> existing = Collections.emptyList();

		final MasterFacetRepair.Result repair = MasterFacetRepair.decide(existing, planned);

		MasterFacetRepairTest.check("no repair is produced for a table that does not exist live at all yet", repair.isEmpty());
	}

	private static void resultIsEmpty_whenNothingNeedsRepair()
	{
		final MasterFacetRepair.Result repair = MasterFacetRepair.decide(Collections.<StagingModelDiff.ExistingTable>emptyList(),
			MasterModel.build(Collections.<MasterModel.EntityInfo>emptyList()));
		MasterFacetRepairTest.check("Result.isEmpty() true with nothing promoted and nothing live", repair.isEmpty());
	}

	// -------------------------------------------------------------------------------------------------------- fixtures

	/** Company (with a bare "name" display attribute) + Contact, both promoted, plain sourceSystem/sourceId keys - the same shape MasterModelTest's own realDemo fixture uses. */
	private static MasterModel.Result companyAndContactPlanned()
	{
		final MasterModel.EntityInfo company = new MasterModel.EntityInfo("Company", "Company", "ACCEPTED", Arrays.asList(
			MasterFacetRepairTest.technical("sourceSystem"), MasterFacetRepairTest.technical("sourceId"),
			new MasterModel.AttributeInfo("name", "Name", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")), Collections.<String>emptyList());
		final MasterModel.EntityInfo contact = new MasterModel.EntityInfo("Contact", "Contact", "ACCEPTED", Arrays.asList(
			MasterFacetRepairTest.technical("sourceSystem"), MasterFacetRepairTest.technical("sourceId"),
			new MasterModel.AttributeInfo("email", "Email", "STRING", Integer.valueOf(255), false, null, "ACCEPTED")), Collections.<String>emptyList());
		return MasterModel.build(Arrays.asList(company, contact));
	}

	private static MasterModel.AttributeInfo technical(final String pName)
	{
		return new MasterModel.AttributeInfo(pName, pName, "STRING", Integer.valueOf(40), true, null, "ACCEPTED");
	}

	/** Contact exactly as {@code apply_data_model_patch} left it in the incident: ops 0-13 all applied. */
	private static StagingModelDiff.ExistingTable contactComplete()
	{
		final List<StagingModelDiff.ExistingField> fields = new ArrayList<StagingModelDiff.ExistingField>();
		fields.add(new StagingModelDiff.ExistingField("id", "int", true, true, null, false, null, Integer.valueOf(1)));
		fields.add(new StagingModelDiff.ExistingField("sourceSystem", "string", false, false, Integer.valueOf(40), false, null));
		fields.add(new StagingModelDiff.ExistingField("sourceId", "string", false, false, Integer.valueOf(40), false, null));
		fields.add(new StagingModelDiff.ExistingField("email", "string", false, false, Integer.valueOf(255), false, null));
		return new StagingModelDiff.ExistingTable("Contact", fields, "${./email}");
	}

	/** Company exactly as the incident's op-14 abort left it: table and fields created (ops 0-13 never reached it - it starts at op 15), but no autoIncrementStart/Step, and no defaultLabel. */
	private static StagingModelDiff.ExistingTable companyMissingAutoIncrementAndLabel()
	{
		final List<StagingModelDiff.ExistingField> fields = new ArrayList<StagingModelDiff.ExistingField>();
		fields.add(new StagingModelDiff.ExistingField("id", "int", true, true, null, false, null)); // no autoIncrementStart - the 7-arg overload defaults it to null
		fields.add(new StagingModelDiff.ExistingField("sourceSystem", "string", false, false, Integer.valueOf(40), false, null));
		fields.add(new StagingModelDiff.ExistingField("sourceId", "string", false, false, Integer.valueOf(40), false, null));
		fields.add(new StagingModelDiff.ExistingField("name", "string", false, false, Integer.valueOf(255), false, null));
		return new StagingModelDiff.ExistingTable("Company", fields); // no defaultLabel either - the 2-arg overload defaults it to null
	}

	/** Company with both facets already present - the state a successful CREATE or an earlier successful repair leaves it in. */
	private static StagingModelDiff.ExistingTable companyComplete()
	{
		final List<StagingModelDiff.ExistingField> fields = new ArrayList<StagingModelDiff.ExistingField>();
		fields.add(new StagingModelDiff.ExistingField("id", "int", true, true, null, false, null, Integer.valueOf(1)));
		fields.add(new StagingModelDiff.ExistingField("sourceSystem", "string", false, false, Integer.valueOf(40), false, null));
		fields.add(new StagingModelDiff.ExistingField("sourceId", "string", false, false, Integer.valueOf(40), false, null));
		fields.add(new StagingModelDiff.ExistingField("name", "string", false, false, Integer.valueOf(255), false, null));
		return new StagingModelDiff.ExistingTable("Company", fields, "${./name}");
	}

	// ---------------------------------------------------------------------------------------------------------- misc

	private static boolean hasSetElementPropertyOp(final ArrayNode pOperations, final String pElementPath, final String pProperty, final String pValue)
	{
		for (final com.fasterxml.jackson.databind.JsonNode op : pOperations)
		{
			if (!"set_element_property".equals(op.path("op").asText()))
			{
				continue;
			}
			final com.fasterxml.jackson.databind.JsonNode arguments = op.path("arguments");
			if (pElementPath.equals(arguments.path("element_path").asText()) && pProperty.equals(arguments.path("property").asText())
				&& pValue.equals(arguments.path("value").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean hasAnyOpFor(final ArrayNode pOperations, final String pElementPath)
	{
		for (final com.fasterxml.jackson.databind.JsonNode op : pOperations)
		{
			if (pElementPath.equals(op.path("arguments").path("element_path").asText()))
			{
				return true;
			}
		}
		return false;
	}

	private static boolean anyContains(final List<String> pStrings, final String pFragment)
	{
		for (final String string : pStrings)
		{
			if (string.contains(pFragment))
			{
				return true;
			}
		}
		return false;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
