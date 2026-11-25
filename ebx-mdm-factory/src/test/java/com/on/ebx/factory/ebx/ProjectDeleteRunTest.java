package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free, like the other pure-package tests: run the main, a failure throws. Only {@link ProjectDeleteRun#totalRows} is pure/testable without an EBX instance - every other method needs a live Adaptation. */
public final class ProjectDeleteRunTest
{
	public static void main(final String[] pArgs)
	{
		ProjectDeleteRunTest.sumsEveryFieldValue();
		ProjectDeleteRunTest.zeroForNullOrNonObject();
		ProjectDeleteRunTest.zeroForEmptyObject();
		ProjectDeleteRunTest.keptNoteNamesWhatStaysBecauseItIsInUse();
		ProjectDeleteRunTest.matchingNoteReadsEachItemAndQuotesTheAddonWhenItDidNotDelete();
		System.out.println("ProjectDeleteRunTest OK");
	}

	private static void sumsEveryFieldValue()
	{
		final ObjectMapper mapper = new ObjectMapper();
		final ObjectNode rowsDeleted = mapper.createObjectNode();
		rowsDeleted.put("Xref", 120);
		rowsDeleted.put("TransferReject", 4);
		rowsDeleted.put("PipelineStep", 30);
		ProjectDeleteRunTest.check("sums every table's row count", ProjectDeleteRun.totalRows(rowsDeleted) == 154L);
	}

	private static void zeroForNullOrNonObject()
	{
		ProjectDeleteRunTest.check("null -> 0", ProjectDeleteRun.totalRows(null) == 0L);
		final ObjectMapper mapper = new ObjectMapper();
		final JsonNode arrayNode = mapper.createArrayNode();
		ProjectDeleteRunTest.check("a non-object JsonNode -> 0", ProjectDeleteRun.totalRows(arrayNode) == 0L);
	}

	private static void zeroForEmptyObject()
	{
		final ObjectNode empty = new ObjectMapper().createObjectNode();
		ProjectDeleteRunTest.check("an empty object -> 0", ProjectDeleteRun.totalRows(empty) == 0L);
	}

	private static void keptNoteNamesWhatStaysBecauseItIsInUse()
	{
		final ObjectMapper mapper = new ObjectMapper();
		final com.fasterxml.jackson.databind.node.ArrayNode generated = mapper.createArrayNode();
		final ObjectNode deleted = generated.addObject();
		deleted.put("kind", "CATALOG_SOURCE").put("name", "ECOMCLIENTS").put("outcome", "DELETED");
		final ObjectNode kept = generated.addObject();
		kept.put("kind", "CONNECTION").put("name", "EcomClients").put("outcome", "KEPT_IN_USE").put("message", "mapping 481 still runs on this connection: kept.");
		final ObjectNode ineligible = generated.addObject();
		ineligible.put("kind", "CONNECTION").put("name", "Other").put("outcome", "KEPT_INELIGIBLE").put("message", "shared");
		ProjectDeleteRunTest.check("only the in-use item is named: " + ProjectDeleteRun.keptNote(generated), ProjectDeleteRun.keptNote(generated)
			.equals(" Kept: CONNECTION EcomClients (mapping 481 still runs on this connection: kept.)"));
		ProjectDeleteRunTest.check("nothing kept, nothing said", ProjectDeleteRun.keptNote(mapper.createArrayNode()).isEmpty() && ProjectDeleteRun.keptNote(null).isEmpty());
	}

	/** Lot M18 (second round, live 2026-10-18 15:56): the job ended DONE with the main table's configuration left and nothing said why. */
	private static void matchingNoteReadsEachItemAndQuotesTheAddonWhenItDidNotDelete()
	{
		final ObjectMapper mapper = new ObjectMapper();
		final com.fasterxml.jackson.databind.node.ArrayNode generated = mapper.createArrayNode();
		generated.addObject().put("kind", "MATCHING_BUSINESS_OBJECT").put("name", "/root/Personne").put("outcome", "DELETED").put("message", "business object configuration 27 deleted");
		generated.addObject().put("kind", "MATCHING_CONFIG").put("name", "/root/Adresse").put("outcome", "DELETED").put("answer", "{\"success\":true}");
		generated.addObject().put("kind", "MATCHING_CONFIG").put("name", "/root/Personne").put("outcome", "KEPT_BUSINESS_OBJECT").put("message", "stays in place")
			.put("answer", "{\"code\":\"INVALID_PARAMS\",\"message\":\"Table /root/Personne is part of a business object; use the business-object tools instead.\"}");
		generated.addObject().put("kind", "MASTER_MODEL").put("name", "X_Master").put("outcome", "DELETED");
		final String note = ProjectDeleteRun.matchingNote(generated);
		ProjectDeleteRunTest.check("every matching item is named with its outcome, the others are not: " + note, note.startsWith(" Matching: business object /root/Personne DELETED; /root/Adresse DELETED; /root/Personne KEPT_BUSINESS_OBJECT (")
			&& !note.contains("X_Master"));
		ProjectDeleteRunTest.check("the add-on's own answer is quoted for the one that did not delete", note.contains("is part of a business object"));
		ProjectDeleteRunTest.check("and not for the ones that did", note.indexOf("success") < 0);
		ProjectDeleteRunTest.check("no matching item, nothing said", ProjectDeleteRun.matchingNote(mapper.createArrayNode()).isEmpty() && ProjectDeleteRun.matchingNote(null).isEmpty());
		final ObjectNode left = mapper.createObjectNode();
		left.put("businessObjectConfigurations", 0).put("tableConfigurations", 1);
		ProjectDeleteRunTest.check("rows left in the add-on's dataset are said", ProjectDeleteRun.leftNote(left).contains("0 business-object and 1 table configuration row(s)"));
		left.put("tableConfigurations", 0);
		ProjectDeleteRunTest.check("nothing left, nothing said", ProjectDeleteRun.leftNote(left).isEmpty() && ProjectDeleteRun.leftNote(null).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
