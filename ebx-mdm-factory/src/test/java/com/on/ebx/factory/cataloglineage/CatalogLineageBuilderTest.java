package com.on.ebx.factory.cataloglineage;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.AttributeInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.AttributeMappingInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.CleaningStep;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.EntityInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.LandingTableInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.ProjectInput;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.Result;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.SourceColumn;
import com.on.ebx.factory.cataloglineage.CatalogLineageBuilder.SourceZone;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Builds the demo
 * project's shape directly (task brief: 2 sources, 3 landing tables, {@code Company} canonical fed by
 * {@code Supplier}+{@code Account}, {@code Contact}) rather than a synthetic toy shape, so a reader can
 * cross-check this test against `docs/CATALOG-LINEAGE.md`'s own worked example.
 */
public final class CatalogLineageBuilderTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final Date WHEN = new Date(1758412800000L); // 2025-09-21T00:00:00Z, arbitrary but fixed

	public static void main(final String[] pArgs)
	{
		CatalogLineageBuilderTest.demoProjectShapeProducesExpectedJobs();
		CatalogLineageBuilderTest.rejectedMappingNeverDeclared();
		CatalogLineageBuilderTest.renamedReservedAttributeDeclaredUnderItsMasterName();
		CatalogLineageBuilderTest.transformationTextIsPipelineThenTransformation();
		CatalogLineageBuilderTest.noStepsNoTransformationIsIdentity();
		CatalogLineageBuilderTest.onlyPromotedEntitiesGetAMasterJob();
		CatalogLineageBuilderTest.idempotentByteIdenticalOutput();
		CatalogLineageBuilderTest.stableOrderingRegardlessOfInputOrder();
		CatalogLineageBuilderTest.namesRoundTripThroughJsonUnaltered();
		CatalogLineageBuilderTest.blankLandingColumnIsSkippedWithANote();
		CatalogLineageBuilderTest.landingNamingMatchesEbxConnectorsConvention();
		System.out.println("CatalogLineageBuilderTest OK");
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	/**
	 * The demo project's shape: ERP source (Supplier landing table), CRM source (Account + Contact landing
	 * tables); {@code Company} = canonical entity fed by {@code Supplier} (name) and {@code Account}
	 * (name, and two REJECTED mappings: {@code accountType -> legalForm}, {@code siren -> vatNo}, the real
	 * case named in the task brief - so their {@link AttributeInput#mappings} lists are EMPTY here, exactly
	 * as {@code ebx.CatalogLineageDeclarer} would hand them after filtering out anything not
	 * ACCEPTED-or-APPLIED); {@code Contact} = single-source entity, promoted on its own.
	 */
	private static ProjectInput demoProject()
	{
		final SourceZone erp = new SourceZone("ERP", "Demo_Landing_ERP", CatalogLineageBuilderTest.list(new LandingTableInput("ERP",
			"SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER", "Supplier",
			CatalogLineageBuilderTest.list(new SourceColumn("NAME1", "name"), new SourceColumn("SUPPLIER_ID", "supplierId")))));
		final SourceZone crm = new SourceZone("CRM", "Demo_Landing_CRM",
			CatalogLineageBuilderTest.list(
				new LandingTableInput("CRM", "SNOWFLAKE_LEARNING_DB.CRM_SOURCE.ACCOUNT", "Account",
					CatalogLineageBuilderTest.list(new SourceColumn("ACCOUNT_NAME", "accountName"), new SourceColumn("ACCOUNT_TYPE", "accountType"),
						new SourceColumn("SIREN", "siren"))),
				new LandingTableInput("CRM", "SNOWFLAKE_LEARNING_DB.CRM_SOURCE.CONTACT", "Contact",
					CatalogLineageBuilderTest.list(new SourceColumn("CONTACT_NAME", "contactName")))));

		final AttributeInput name = new AttributeInput("name",
			CatalogLineageBuilderTest.list(new AttributeMappingInput("ERP", "Supplier", "name", "TRIM", null, CatalogLineageBuilderTest.list(new CleaningStep("TRIM", null))),
				new AttributeMappingInput("CRM", "Account", "accountName", "NONE", null, Collections.<CleaningStep>emptyList())));
		// both REJECTED in real life (task brief) - handed here with an EMPTY mapping list, exactly as the
		// EBX-side declarer would after filtering to ACCEPTED-or-APPLIED only
		final AttributeInput legalForm = new AttributeInput("legalForm", Collections.<AttributeMappingInput>emptyList());
		final AttributeInput vatNo = new AttributeInput("vatNo", Collections.<AttributeMappingInput>emptyList());
		final EntityInput supplier = new EntityInput("Supplier", false,
			CatalogLineageBuilderTest.list(new AttributeInput("name", CatalogLineageBuilderTest.list(
				new AttributeMappingInput("ERP", "Supplier", "name", "NONE", null, Collections.<CleaningStep>emptyList())))));
		final EntityInput account = new EntityInput("Account", false,
			CatalogLineageBuilderTest.list(new AttributeInput("name", CatalogLineageBuilderTest.list(
				new AttributeMappingInput("CRM", "Account", "accountName", "NONE", null, Collections.<CleaningStep>emptyList())))));
		final EntityInput company = new EntityInput("Company", true, CatalogLineageBuilderTest.list(name, legalForm, vatNo));
		final EntityInput contact = new EntityInput("Contact", true, CatalogLineageBuilderTest.list(new AttributeInput("contactName",
			CatalogLineageBuilderTest.list(new AttributeMappingInput("CRM", "Contact", "contactName", "NONE", null, Collections.<CleaningStep>emptyList())))));

		return new ProjectInput("Demo", "ebx://localhost:8080", "Demo_Landing", CatalogLineageBuilderTest.list(erp, crm), "Demo_Staging", "Demo_Staging",
			CatalogLineageBuilderTest.list(supplier, account, company, contact), "Demo_Master", "Demo_Master");
	}

	@SafeVarargs
	private static <T> List<T> list(final T... pItems)
	{
		return new ArrayList<T>(Arrays.asList(pItems));
	}

	// ------------------------------------------------------------------------------------------------------ tests

	private static void demoProjectShapeProducesExpectedJobs()
	{
		final Result result = CatalogLineageBuilder.build(CatalogLineageBuilderTest.demoProject(), CatalogLineageBuilderTest.WHEN);
		CatalogLineageBuilderTest.check("3 landing jobs (Supplier, Account, Contact)", result.counts.landingJobs == 3);
		CatalogLineageBuilderTest.check("4 staging jobs (Supplier, Account, Company, Contact)", result.counts.stagingJobs == 4);
		CatalogLineageBuilderTest.check("2 master jobs (Company, Contact - Supplier/Account are not promoted)", result.counts.masterJobs == 2);
		CatalogLineageBuilderTest.check("column links counted", result.counts.columnLinks > 0);
		final List<String> jobNames = CatalogLineageBuilderTest.jobNames(result);
		CatalogLineageBuilderTest.check("landing:Demo:ERP:Supplier present", jobNames.contains("landing:Demo:ERP:Supplier"));
		CatalogLineageBuilderTest.check("landing:Demo:CRM:Account present", jobNames.contains("landing:Demo:CRM:Account"));
		CatalogLineageBuilderTest.check("landing:Demo:CRM:Contact present", jobNames.contains("landing:Demo:CRM:Contact"));
		CatalogLineageBuilderTest.check("staging:Demo:Company present", jobNames.contains("staging:Demo:Company"));
		CatalogLineageBuilderTest.check("master:Demo:Company present", jobNames.contains("master:Demo:Company"));
		CatalogLineageBuilderTest.check("master:Demo:Supplier absent (not promoted)", !jobNames.contains("master:Demo:Supplier"));
	}

	private static void rejectedMappingNeverDeclared()
	{
		final Result result = CatalogLineageBuilder.build(CatalogLineageBuilderTest.demoProject(), CatalogLineageBuilderTest.WHEN);
		final ObjectNode stagingCompany = CatalogLineageBuilderTest.job(result, "staging:Demo:Company");
		final JsonNode fields = stagingCompany.at("/outputs/0/facets/columnLineage/fields");
		CatalogLineageBuilderTest.check("name is declared (has an accepted mapping)", fields.has("name"));
		CatalogLineageBuilderTest.check("legalForm is NEVER declared (both its mappings are REJECTED)", !fields.has("legalForm"));
		CatalogLineageBuilderTest.check("vatNo is NEVER declared (both its mappings are REJECTED)", !fields.has("vatNo"));
		// the master crossing still copies legalForm/vatNo as plain staging attributes (StagingAttribute.status,
		// not AttributeMapping.status, governs whether the FIELD exists) - only the landing-to-staging edge is
		// missing, never the whole attribute
		final ObjectNode masterCompany = CatalogLineageBuilderTest.job(result, "master:Demo:Company");
		final JsonNode masterFields = masterCompany.at("/outputs/0/facets/columnLineage/fields");
		CatalogLineageBuilderTest.check("legalForm still exists as a master field (identity copy of the staging attribute)", masterFields.has("legalForm"));
	}

	/** 2026-09-23 (`docs/MASTER-ZONE.md` &sect;13): a promoted attribute named {@code Id} is the master field {@code Id_src} - the master job's output field says so, its input field is still the staging attribute {@code Id}. */
	private static void renamedReservedAttributeDeclaredUnderItsMasterName()
	{
		final EntityInput sample = new EntityInput("SalesforceSample", true, CatalogLineageBuilderTest.list(
			new AttributeInput("Id", CatalogLineageBuilderTest.list(new AttributeMappingInput("SF", "SalesforceSample", "id", "NONE", null, Collections.<CleaningStep>emptyList()))),
			new AttributeInput("Name", CatalogLineageBuilderTest.list(new AttributeMappingInput("SF", "SalesforceSample", "name", "NONE", null, Collections.<CleaningStep>emptyList())))));
		final SourceZone sf = new SourceZone("SF", "Scratch_Landing_SF", CatalogLineageBuilderTest.list(new LandingTableInput("SF", "sample.json", "SalesforceSample",
			CatalogLineageBuilderTest.list(new SourceColumn("Id", "id"), new SourceColumn("Name", "name")))));
		final ProjectInput project = new ProjectInput("Scratch", "ebx://localhost:8080", "Scratch_Landing", CatalogLineageBuilderTest.list(sf), "Scratch_Staging", "Scratch_Staging",
			CatalogLineageBuilderTest.list(sample), "Scratch_Master", "Scratch_Master");
		final Result result = CatalogLineageBuilder.build(project, CatalogLineageBuilderTest.WHEN);
		final JsonNode stagingFields = CatalogLineageBuilderTest.job(result, "staging:Scratch:SalesforceSample").at("/outputs/0/facets/columnLineage/fields");
		CatalogLineageBuilderTest.check("the STAGING table keeps the attribute name Id (staging has no id field of its own)", stagingFields.has("Id") && !stagingFields.has("Id_src"));
		final JsonNode masterFields = CatalogLineageBuilderTest.job(result, "master:Scratch:SalesforceSample").at("/outputs/0/facets/columnLineage/fields");
		CatalogLineageBuilderTest.check("the MASTER table declares Id_src, never Id", masterFields.has("Id_src") && !masterFields.has("Id"));
		CatalogLineageBuilderTest.check("Id_src's input is the staging field Id", "Id".equals(masterFields.at("/Id_src/inputFields/0/field").asText()));
		CatalogLineageBuilderTest.check("its transformation says why the name differs", masterFields.at("/Id_src/inputFields/0/transformations/0/description").asText().contains("Promoted as Id_src"));
		CatalogLineageBuilderTest.check("Name is an identity copy with no description", masterFields.has("Name") && masterFields.at("/Name/inputFields/0/transformations/0/description").isMissingNode());
	}

	private static void transformationTextIsPipelineThenTransformation()
	{
		final AttributeMappingInput mapping = new AttributeMappingInput("ERP", "Supplier", "name", "UPPER", null,
			CatalogLineageBuilderTest.list(new CleaningStep("TRIM", null), new CleaningStep("COLLAPSE_SPACES", null)));
		final String words = CatalogLineageBuilder.transformationWords(mapping);
		CatalogLineageBuilderTest.check("pipeline before transformation, in order",
			"Trim leading/trailing spaces; Collapse repeated inner spaces; UPPER".equals(words));
	}

	private static void noStepsNoTransformationIsIdentity()
	{
		final Result result = CatalogLineageBuilder.build(CatalogLineageBuilderTest.demoProject(), CatalogLineageBuilderTest.WHEN);
		final ObjectNode landingSupplier = CatalogLineageBuilderTest.job(result, "landing:Demo:ERP:Supplier");
		final JsonNode inField = landingSupplier.at("/outputs/0/facets/columnLineage/fields/name/inputFields/0");
		CatalogLineageBuilderTest.check("a plain 1:1 landing column is IDENTITY", "IDENTITY".equals(inField.at("/transformations/0/subtype").asText()));
		CatalogLineageBuilderTest.check("IDENTITY carries no description", inField.at("/transformations/0/description").isMissingNode());
	}

	private static void onlyPromotedEntitiesGetAMasterJob()
	{
		final Result result = CatalogLineageBuilder.build(CatalogLineageBuilderTest.demoProject(), CatalogLineageBuilderTest.WHEN);
		final List<String> jobNames = CatalogLineageBuilderTest.jobNames(result);
		CatalogLineageBuilderTest.check("master:Demo:Contact present (Contact is promoted)", jobNames.contains("master:Demo:Contact"));
		CatalogLineageBuilderTest.check("master:Demo:Account absent (Account is a member, not promoted)", !jobNames.contains("master:Demo:Account"));
	}

	private static void idempotentByteIdenticalOutput()
	{
		final ProjectInput input = CatalogLineageBuilderTest.demoProject();
		final Result first = CatalogLineageBuilder.build(input, CatalogLineageBuilderTest.WHEN);
		final Result second = CatalogLineageBuilder.build(input, CatalogLineageBuilderTest.WHEN);
		CatalogLineageBuilderTest.check("same event count", first.events.size() == second.events.size());
		for (int i = 0; i < first.events.size(); i++)
		{
			CatalogLineageBuilderTest.check("event " + i + " is byte-identical on a second build", first.events.get(i).toString().equals(second.events.get(i).toString()));
		}
	}

	/** Same project, but every list handed in is reversed/reshuffled - the output must not depend on input order. */
	private static void stableOrderingRegardlessOfInputOrder()
	{
		final ProjectInput canonical = CatalogLineageBuilderTest.demoProject();
		final ProjectInput reversed = new ProjectInput(canonical.projectCode, canonical.ebxNamespace, canonical.landingDataspace,
			CatalogLineageBuilderTest.reversed(canonical.sources), canonical.stagingDataspace, canonical.stagingDataset,
			CatalogLineageBuilderTest.reversed(canonical.entities), canonical.masterDataspace, canonical.masterDataset);
		final Result a = CatalogLineageBuilder.build(canonical, CatalogLineageBuilderTest.WHEN);
		final Result b = CatalogLineageBuilder.build(reversed, CatalogLineageBuilderTest.WHEN);
		CatalogLineageBuilderTest.check("same number of events regardless of input order", a.events.size() == b.events.size());
		final List<String> namesA = CatalogLineageBuilderTest.jobNames(a);
		final List<String> namesB = CatalogLineageBuilderTest.jobNames(b);
		CatalogLineageBuilderTest.check("job order itself is stable (sorted), not a function of input order", namesA.equals(namesB));
		for (int i = 0; i < a.events.size(); i++)
		{
			CatalogLineageBuilderTest.check("event " + i + " content identical regardless of input order", a.events.get(i).toString().equals(b.events.get(i).toString()));
		}
	}

	private static void namesRoundTripThroughJsonUnaltered()
	{
		final String weirdField = "a\"weird\\name";
		final AttributeInput attribute = new AttributeInput(weirdField, CatalogLineageBuilderTest.list(
			new AttributeMappingInput("ERP", "Supplier", weirdField, "NONE", null, Collections.<CleaningStep>emptyList())));
		final EntityInput entity = new EntityInput("Weird", true, CatalogLineageBuilderTest.list(attribute));
		final SourceZone erp = new SourceZone("ERP", "Demo_Landing_ERP", CatalogLineageBuilderTest.list(
			new LandingTableInput("ERP", "SNOWFLAKE_LEARNING_DB.ERP_SOURCE.SUPPLIER", "Supplier", CatalogLineageBuilderTest.list(new SourceColumn(weirdField, weirdField)))));
		final ProjectInput input = new ProjectInput("Demo", "ebx://localhost:8080", "Demo_Landing", CatalogLineageBuilderTest.list(erp), "Demo_Staging", "Demo_Staging",
			CatalogLineageBuilderTest.list(entity), "Demo_Master", "Demo_Master");
		final Result result = CatalogLineageBuilder.build(input, CatalogLineageBuilderTest.WHEN);
		final ObjectNode staging = CatalogLineageBuilderTest.job(result, "staging:Demo:Weird");
		final String roundTripped = CatalogLineageBuilderTest.reparse(staging).at("/outputs/0/facets/columnLineage/fields").fieldNames().next();
		CatalogLineageBuilderTest.check("a field name with a quote and a backslash round-trips through JSON unaltered", weirdField.equals(roundTripped));
	}

	private static void blankLandingColumnIsSkippedWithANote()
	{
		final AttributeInput broken = new AttributeInput("broken", CatalogLineageBuilderTest.list(new AttributeMappingInput("ERP", "", "", "NONE", null,
			Collections.<CleaningStep>emptyList())));
		final EntityInput entity = new EntityInput("Broken", false, CatalogLineageBuilderTest.list(broken));
		final ProjectInput input = new ProjectInput("Demo", "ebx://localhost:8080", "Demo_Landing", Collections.<SourceZone>emptyList(), "Demo_Staging", "Demo_Staging",
			CatalogLineageBuilderTest.list(entity), null, null);
		final Result result = CatalogLineageBuilder.build(input, CatalogLineageBuilderTest.WHEN);
		CatalogLineageBuilderTest.check("nothing usable: no event produced", result.events.isEmpty());
		CatalogLineageBuilderTest.check("but the skip is explained, never silent", !result.notes.isEmpty());
	}

	private static void landingNamingMatchesEbxConnectorsConvention()
	{
		CatalogLineageBuilderTest.check("<dataspace>/<dataset>/root/<table> - ebx-connectors' own EbxCatalog/OpenLineageEmitter convention",
			"Companies_Landing/CompaniesLandingErp/root/Supplier".equals(CatalogLineageBuilder.ebxName("Companies_Landing", "CompaniesLandingErp", "Supplier")));
	}

	// --------------------------------------------------------------------------------------------------- helpers

	private static <T> List<T> reversed(final List<T> pList)
	{
		final List<T> out = new ArrayList<T>(pList);
		Collections.reverse(out);
		return out;
	}

	private static List<String> jobNames(final Result pResult)
	{
		final List<String> out = new ArrayList<String>();
		for (final ObjectNode event : pResult.events)
		{
			out.add(event.at("/job/name").asText());
		}
		return out;
	}

	private static ObjectNode job(final Result pResult, final String pName)
	{
		for (final ObjectNode event : pResult.events)
		{
			if (pName.equals(event.at("/job/name").asText()))
			{
				return event;
			}
		}
		throw new AssertionError("no job named " + pName + " among " + CatalogLineageBuilderTest.jobNames(pResult));
	}

	private static JsonNode reparse(final ObjectNode pNode)
	{
		try
		{
			return CatalogLineageBuilderTest.MAPPER.readTree(pNode.toString());
		}
		catch (final Exception ex)
		{
			throw new AssertionError(ex);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
