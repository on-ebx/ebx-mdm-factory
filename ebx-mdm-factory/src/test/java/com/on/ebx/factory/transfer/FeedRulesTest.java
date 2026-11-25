package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingEngine;
import com.on.ebx.connect.core.TransformationContext;
import com.on.ebx.factory.quality.CleaningSteps;

/**
 * JUnit-free: run the main, a failure throws. Lot M6 (2026-10-04): the ECOM feed of Personne as measured on Vol100k / Vol1m -
 * two mappings on one landing column ({@code FULL_NAME}), the regexes of the live mappings, an {@code UPPER} step on {@code lastName}
 * only. Before the fix the cleaned value replaced the shared column and {@code firstName} came out empty.
 */
public final class FeedRulesTest
{
	private static final String FIRST = "regex(^(?:[\\p{Lu}'-]+\\s+)?([\\p{L}'-]*\\p{Ll}[\\p{L}'-]*))";
	private static final String LAST = "regex((?:^(?=[\\p{Lu}'-]+\\s)|(?<=\\s)(?=[\\p{L}'-]+$))([\\p{L}'-]+))";

	public static void main(final String[] pArgs) throws Exception
	{
		FeedRulesTest.aStepOnOneMappingDoesNotTouchTheOtherMappingOfTheSameColumn();
		FeedRulesTest.theSharedColumnOfTheDocumentIsNeverRewritten();
		FeedRulesTest.aFeedWithoutStepKeepsItsSourceColumnAndNeedsNoCopy();
		FeedRulesTest.aFieldThatCleansToNothingReadsNull();
		System.out.println("FeedRulesTest OK");
	}

	private static TransferPlan.FieldPlan field(final String pStagingField, final String pPipeline, final String pSourcePath,
		final List<CleaningSteps.Step> pSteps)
	{
		return new TransferPlan.FieldPlan(pStagingField, "STRING", null, false, pPipeline, pSourcePath, null, null, false, false, pSteps, null);
	}

	private static List<TransferPlan.FieldPlan> ecomFields()
	{
		final List<TransferPlan.FieldPlan> fields = new ArrayList<TransferPlan.FieldPlan>();
		fields.add(FeedRulesTest.field("firstName", FeedRulesTest.FIRST, "FULL_NAME", null));
		fields.add(FeedRulesTest.field("lastName", FeedRulesTest.LAST, "FULL_NAME", Arrays.asList(new CleaningSteps.Step(CleaningSteps.UPPER, null))));
		return fields;
	}

	private static Set<String> writable(final List<TransferPlan.FieldPlan> pFields)
	{
		final Set<String> out = new HashSet<String>();
		for (final TransferPlan.FieldPlan field : pFields)
		{
			out.add(field.stagingField);
		}
		return out;
	}

	private static MappingEngine.Values compute(final List<TransferPlan.FieldPlan> pFields, final String pFullName) throws Exception
	{
		final Set<String> writable = FeedRulesTest.writable(pFields);
		final ObjectNode document = new ObjectMapper().createObjectNode();
		document.put("FULL_NAME", pFullName);
		final ObjectNode working = FeedRules.hasCleaningSteps(pFields, writable) ? FeedRules.cleanedDocument(document, pFields, writable, null) : document;
		final MappingDefinition definition = FeedRules.definition("feed-1", "/root/Personne", pFields, writable);
		return MappingEngine.compute(definition, working, new TransformationContext(null, null, null));
	}

	private static void aStepOnOneMappingDoesNotTouchTheOtherMappingOfTheSameColumn() throws Exception
	{
		final List<TransferPlan.FieldPlan> fields = FeedRulesTest.ecomFields();
		MappingEngine.Values values = FeedRulesTest.compute(fields, "Aurélie-Sophie Navret");
		FeedRulesTest.check("first name read on the RAW value: " + values.get("./firstName"), "Aurélie-Sophie".equals(values.get("./firstName")));
		values = FeedRulesTest.compute(fields, "SALNIER Nathalie-Julie");
		FeedRulesTest.check("first name of an inverted name: " + values.get("./firstName"), "Nathalie-Julie".equals(values.get("./firstName")));
		// the last name keeps its own step: UPPER runs, then its regex (documented order, per field)
		FeedRulesTest.check("the last name still has its UPPER step applied before its regex: " + values.get("./lastName"),
			"SALNIER".equals(values.get("./lastName")));
	}

	private static void theSharedColumnOfTheDocumentIsNeverRewritten() throws Exception
	{
		final List<TransferPlan.FieldPlan> fields = FeedRulesTest.ecomFields();
		final ObjectNode document = new ObjectMapper().createObjectNode();
		document.put("FULL_NAME", "Aurélie-Sophie Navret");
		final ObjectNode copy = FeedRules.cleanedDocument(document, fields, FeedRulesTest.writable(fields), null);
		FeedRulesTest.check("the original document is untouched", "Aurélie-Sophie Navret".equals(document.get("FULL_NAME").asText()));
		FeedRulesTest.check("the copy keeps the raw shared column", "Aurélie-Sophie Navret".equals(copy.get("FULL_NAME").asText()));
		FeedRulesTest.check("the copy carries the cleaned value under the field's own alias",
			"AURÉLIE-SOPHIE NAVRET".equals(copy.get(FeedRules.cleanedColumn("lastName")).asText()));
		FeedRulesTest.check("no alias for a field without step", copy.get(FeedRules.cleanedColumn("firstName")) == null);
		FeedRulesTest.check("firstName's rule reads the shared column", "FULL_NAME".equals(FeedRules.ruleSourcePath(fields.get(0))));
		FeedRulesTest.check("lastName's rule reads its alias", FeedRules.cleanedColumn("lastName").equals(FeedRules.ruleSourcePath(fields.get(1))));
	}

	private static void aFeedWithoutStepKeepsItsSourceColumnAndNeedsNoCopy() throws Exception
	{
		final List<TransferPlan.FieldPlan> fields = Collections.singletonList(FeedRulesTest.field("firstName", FeedRulesTest.FIRST, "FULL_NAME", null));
		FeedRulesTest.check("no cleaning step: no copy needed", !FeedRules.hasCleaningSteps(fields, FeedRulesTest.writable(fields)));
		final MappingEngine.Values values = FeedRulesTest.compute(fields, "Aurélie-Sophie Navret");
		FeedRulesTest.check("value: " + values.get("./firstName"), "Aurélie-Sophie".equals(values.get("./firstName")));
		// a field not writable on the live schema never gets a rule nor counts as cleaning
		final List<TransferPlan.FieldPlan> two = FeedRulesTest.ecomFields();
		final Set<String> onlyFirst = new HashSet<String>(Arrays.asList("firstName"));
		FeedRulesTest.check("a non-writable field with a step does not make the feed clean", !FeedRules.hasCleaningSteps(two, onlyFirst));
	}

	private static void aFieldThatCleansToNothingReadsNull() throws Exception
	{
		final List<TransferPlan.FieldPlan> fields = Collections.singletonList(
			FeedRulesTest.field("lastName", "", "FULL_NAME", Arrays.asList(new CleaningSteps.Step(CleaningSteps.UPPER, null))));
		final MappingEngine.Values values = FeedRulesTest.compute(fields, "   ");
		final String value = values.get("./lastName");
		FeedRulesTest.check("a blank source stays empty: " + value, value == null || value.isEmpty());
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
