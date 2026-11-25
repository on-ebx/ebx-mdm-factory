package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.distribution.DistributionMappingRules;
import com.on.ebx.factory.distribution.DistributionMappingRules.Rule;
import com.orchestranetworks.service.OperationException;

/**
 * JUnit-free: run the main, a failure throws. Wave 13 (cross-project leak, measured live 2026-10-12): the OUTBOUND mapping document of a distribution
 * target must name the master DATASPACE of its project, next to the dataset - the dataset name alone ("Master") is every project's, and Connectors
 * then resolved it to the first dataspace that held one (an orphan project's), publishing 300 rows of the wrong project.
 */
public final class DistributionMappingDocumentTest
{
	public static void main(final String[] pArgs) throws OperationException
	{
		DistributionMappingDocumentTest.documentNamesTheMasterDataspace();
		DistributionMappingDocumentTest.documentKeepsItsOtherKeys();
		DistributionMappingDocumentTest.blankDataspaceIsRefused();
		System.out.println("DistributionMappingDocumentTest OK");
	}

	private static ObjectNode document(final String pDataspace, final String pDestination, final boolean pCrosswalk) throws OperationException
	{
		final List<Rule> rules = DistributionMappingRules.build(Arrays.asList("name"), pCrosswalk, "/root/Personne", "Vol100k_Master", "Master");
		return DistributionConfigurator.mappingDocument("Vol100k", "Kafka", "publish", pDataspace, "Master", "/root/Personne", "Distribution: target 3 /root/Personne", pCrosswalk,
			pDestination, rules);
	}

	private static void documentNamesTheMasterDataspace() throws OperationException
	{
		final ObjectNode mapping = DistributionMappingDocumentTest.document("Vol100k_Master", null, false);
		DistributionMappingDocumentTest.check("dataspace is present", mapping.hasNonNull("dataspace"));
		DistributionMappingDocumentTest.check("dataspace is the project's master dataspace", "Vol100k_Master".equals(mapping.path("dataspace").asText()));
		DistributionMappingDocumentTest.check("dataset stays the dataset name", "Master".equals(mapping.path("dataset").asText()));
	}

	private static void documentKeepsItsOtherKeys() throws OperationException
	{
		final ObjectNode mapping = DistributionMappingDocumentTest.document("Vol100k_Master", "mdm.personnes.person", true);
		DistributionMappingDocumentTest.check("connector", "Kafka".equals(mapping.path("connector").asText()));
		DistributionMappingDocumentTest.check("operation", "publish".equals(mapping.path("operation").asText()));
		DistributionMappingDocumentTest.check("direction", "OUTBOUND".equals(mapping.path("direction").asText()));
		DistributionMappingDocumentTest.check("tablePath", "/root/Personne".equals(mapping.path("tablePath").asText()));
		DistributionMappingDocumentTest.check("label", "Distribution: target 3 /root/Personne".equals(mapping.path("label").asText()));
		DistributionMappingDocumentTest.check("golden records only", "exclude".equals(mapping.path("inputs").path("mergedRecords").asText()));
		DistributionMappingDocumentTest.check("crosswalk input", "true".equals(mapping.path("inputs").path("crosswalk").asText()));
		DistributionMappingDocumentTest.check("topic input", "mdm.personnes.person".equals(mapping.path("inputs").path("topic").asText()));
		DistributionMappingDocumentTest.check("key template", DistributionConfigurator.DEFAULT_KEY_TEMPLATE.equals(mapping.path("inputs").path("key").asText()));
		DistributionMappingDocumentTest.check("rules written", mapping.path("rules").isArray() && mapping.path("rules").size() > 1);
		DistributionMappingDocumentTest.check("a rule carries its four keys", mapping.path("rules").get(0).hasNonNull("sourcePath") && mapping.path("rules").get(0).has("policy"));
	}

	private static void blankDataspaceIsRefused()
	{
		for (final String blank : new String[] { null, "", "   " })
		{
			boolean refused = false;
			try
			{
				DistributionMappingDocumentTest.document(blank, null, false);
			}
			catch (final OperationException ex)
			{
				refused = ex instanceof EngineRefusal && "engine.missing.noMasterDataspace".equals(((EngineRefusal) ex).code());
			}
			DistributionMappingDocumentTest.check("a blank master dataspace (" + blank + ") is refused with the coded sentence", refused);
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
