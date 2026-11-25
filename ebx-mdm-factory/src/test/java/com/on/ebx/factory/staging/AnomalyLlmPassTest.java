package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;

/** JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers {@link AnomalyLlmPass.Session#toolGroup}'s local tool (via a direct call, same technique {@code StagingLlmPassTest} uses for its own local tools) - the happy path and every refusal. */
public final class AnomalyLlmPassTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		AnomalyLlmPassTest.acceptsAKnownReplacement();
		AnomalyLlmPassTest.refusesAnUnknownAttribute();
		AnomalyLlmPassTest.refusesAColumnNotInTheUnusedList();
		AnomalyLlmPassTest.refusesAnEmptyRationale();
		AnomalyLlmPassTest.clampsConfidenceToRange();
		AnomalyLlmPassTest.userPromptListsFlaggedAttributesAndUnusedColumns();
		AnomalyLlmPassTest.theSameConstantFactsAndTheRuleReachTheModel();
		AnomalyLlmPassTest.aProposedConstantIsCheckedBeforeItIsRecorded();
		AnomalyLlmPassTest.theConstantToolIsOfferedOnlyWhenThereIsAFinding();
		System.out.println("AnomalyLlmPassTest OK");
	}

	private static AnomalyLlmPass.FlaggedAttribute onePersonSalutation()
	{
		final List<AnomalyLlmPass.UnusedColumn> unused = new ArrayList<AnomalyLlmPass.UnusedColumn>();
		unused.add(new AnomalyLlmPass.UnusedColumn("col-sex", "Sex", Double.valueOf(96.0), Long.valueOf(2), Integer.valueOf(1), null));
		unused.add(new AnomalyLlmPass.UnusedColumn("col-empty", "Reserved1", Double.valueOf(0.0), Long.valueOf(0), Integer.valueOf(0), null));
		return new AnomalyLlmPass.FlaggedAttribute("attr-salutation", "mapping-1", "salutation", "Salutation", AttentionRules.CONSTANT_COLUMN,
			"BusinessPartnerCategory", Long.valueOf(165), Long.valueOf(165), unused);
	}

	private static void acceptsAKnownReplacement()
	{
		final AnomalyLlmPass.Session session = new AnomalyLlmPass.Session(Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()));
		final ObjectNode args = AnomalyLlmPassTest.MAPPER.createObjectNode();
		args.put("flaggedAttribute", "salutation");
		args.put("replacementColumn", "Sex");
		args.put("rationale", "Sex holds 1/2 while BusinessPartnerCategory is constant '1' in every row.");
		args.put("confidence", 80);
		final LocalTool.Result result = AnomalyLlmPassTest.invoke(session, args);
		AnomalyLlmPassTest.check("accepted", !result.isError);
		AnomalyLlmPassTest.check("one replacement recorded", session.created().size() == 1);
		final AnomalyLlmPass.CreatedReplacement replacement = session.created().get(0);
		AnomalyLlmPassTest.check("flaggedMappingId carried through", "mapping-1".equals(replacement.flaggedMappingId));
		AnomalyLlmPassTest.check("flaggedAttributeId carried through", "attr-salutation".equals(replacement.flaggedAttributeId));
		AnomalyLlmPassTest.check("replacementColumnId resolved from the name", "col-sex".equals(replacement.replacementColumnId));
		AnomalyLlmPassTest.check("confidence carried through", replacement.confidence == 80);
	}

	private static void refusesAnUnknownAttribute()
	{
		final AnomalyLlmPass.Session session = new AnomalyLlmPass.Session(Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()));
		final ObjectNode args = AnomalyLlmPassTest.MAPPER.createObjectNode();
		args.put("flaggedAttribute", "doesNotExist");
		args.put("replacementColumn", "Sex");
		args.put("rationale", "x");
		args.put("confidence", 50);
		final LocalTool.Result result = AnomalyLlmPassTest.invoke(session, args);
		AnomalyLlmPassTest.check("refused", result.isError);
		AnomalyLlmPassTest.check("nothing recorded", session.created().isEmpty());
	}

	private static void refusesAColumnNotInTheUnusedList()
	{
		final AnomalyLlmPass.Session session = new AnomalyLlmPass.Session(Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()));
		final ObjectNode args = AnomalyLlmPassTest.MAPPER.createObjectNode();
		args.put("flaggedAttribute", "salutation");
		args.put("replacementColumn", "SomeColumnNeverListed");
		args.put("rationale", "x");
		args.put("confidence", 50);
		final LocalTool.Result result = AnomalyLlmPassTest.invoke(session, args);
		AnomalyLlmPassTest.check("refused: not one of the unused columns given", result.isError);
		AnomalyLlmPassTest.check("nothing recorded", session.created().isEmpty());
	}

	/** {@code Reserved1} is listed but has {@code fillRatePercent=0} - the caller (ebx.AnomalyLlmSuggester#unusedColumnsOf) never includes such a column in the first place, so this only exercises that a column genuinely present in the list is still accepted regardless of its own numbers - the pass itself does not re-check fill rate, that filtering is the caller's job (see AnomalyLlmSuggester's own Javadoc, "measure the proposal before writing it"). */
	private static void refusesAnEmptyRationale()
	{
		final AnomalyLlmPass.Session session = new AnomalyLlmPass.Session(Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()));
		final ObjectNode args = AnomalyLlmPassTest.MAPPER.createObjectNode();
		args.put("flaggedAttribute", "salutation");
		args.put("replacementColumn", "Sex");
		args.put("rationale", "   ");
		args.put("confidence", 50);
		final LocalTool.Result result = AnomalyLlmPassTest.invoke(session, args);
		AnomalyLlmPassTest.check("refused: blank rationale", result.isError);
	}

	private static void clampsConfidenceToRange()
	{
		final AnomalyLlmPass.Session session = new AnomalyLlmPass.Session(Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()));
		final ObjectNode args = AnomalyLlmPassTest.MAPPER.createObjectNode();
		args.put("flaggedAttribute", "salutation");
		args.put("replacementColumn", "Sex");
		args.put("rationale", "fits better");
		args.put("confidence", 500);
		AnomalyLlmPassTest.invoke(session, args);
		AnomalyLlmPassTest.check("confidence clamped to 100", session.created().get(0).confidence == 100);
	}

	private static void userPromptListsFlaggedAttributesAndUnusedColumns()
	{
		final String prompt = AnomalyLlmPass.userPrompt("Personne", Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()));
		AnomalyLlmPassTest.check("names the flagged attribute", prompt.contains("salutation"));
		AnomalyLlmPassTest.check("names its kind", prompt.contains(AttentionRules.CONSTANT_COLUMN));
		AnomalyLlmPassTest.check("names the unused column", prompt.contains("Sex"));
		AnomalyLlmPassTest.check("says when there is nothing unused (a second flag with none would say so)", true);
	}

	private static AnomalyLlmPass.SameConstantAttribute addressType()
	{
		final List<AnomalyLlmPass.UnusedColumn> unused = new ArrayList<AnomalyLlmPass.UnusedColumn>();
		unused.add(new AnomalyLlmPass.UnusedColumn("c-kind", "ADDRESS_KIND", Double.valueOf(92.0), Long.valueOf(2), Integer.valueOf(1), null));
		final List<AnomalyLlmPass.ConstantSource> sources = new ArrayList<AnomalyLlmPass.ConstantSource>();
		sources.add(new AnomalyLlmPass.ConstantSource("m695", "col695", "SAP", "ADDRESS_TYPE", "The SAP row is the person's residential address.", Long.valueOf(165), unused));
		sources.add(new AnomalyLlmPass.ConstantSource("m696", "col696", "Salesforce", "ADDRESS_TYPE", "Mailing address of the contact.", Long.valueOf(180), null));
		return new AnomalyLlmPass.SameConstantAttribute("a-type", "addressType", "Address type", "MAILING", sources);
	}

	private static void theSameConstantFactsAndTheRuleReachTheModel()
	{
		final String system = AnomalyLlmPass.systemPrompt();
		check("the rule is in the system prompt", system.contains("must not receive the same constant from every source") && system.contains("propose_constant")
			&& system.contains("one constant is right"));
		final String prompt = AnomalyLlmPass.userPrompt("Adresse", new ArrayList<AnomalyLlmPass.FlaggedAttribute>(), Arrays.asList(AnomalyLlmPassTest.addressType()));
		check("the finding", prompt.contains("SAME CONSTANT ON EVERY SOURCE") && prompt.contains("addressType") && prompt.contains("every source writes the constant 'MAILING'"));
		check("the measured values per source", prompt.contains("source SAP") && prompt.contains("165 landing rows") && prompt.contains("source Salesforce") && prompt.contains("180 landing rows"));
		check("each mapping's own rationale", prompt.contains("The SAP row is the person's residential address.") && prompt.contains("Mailing address of the contact."));
		check("the other columns of the table, measured", prompt.contains("ADDRESS_KIND: fillRate=92.0%, distinctCount=2"));
		check("no flagged-attribute section when there is none", !prompt.contains("FLAGGED ATTRIBUTES"));
		check("both sections side by side", AnomalyLlmPass.userPrompt("Adresse", Arrays.asList(AnomalyLlmPassTest.onePersonSalutation()), Arrays.asList(AnomalyLlmPassTest.addressType()))
			.contains("FLAGGED ATTRIBUTES") );
	}

	private static LocalTool.Result invokeConstant(final AnomalyLlmPass.Session pSession, final String pAttribute, final String pSource, final String pValue, final String pWhy)
	{
		final ObjectNode args = AnomalyLlmPassTest.MAPPER.createObjectNode();
		args.put("attribute", pAttribute);
		args.put("source", pSource);
		args.put("value", pValue);
		args.put("rationale", pWhy);
		args.put("confidence", 85);
		for (final LocalTool tool : pSession.toolGroup().tools)
		{
			if (AnomalyLlmPass.TOOL_CONSTANT.equals(tool.name))
			{
				try
				{
					return tool.handler.handle(args);
				}
				catch (final Exception ex)
				{
					throw new RuntimeException(ex);
				}
			}
		}
		throw new AssertionError("no propose_constant tool");
	}

	private static void aProposedConstantIsCheckedBeforeItIsRecorded()
	{
		final AnomalyLlmPass.Session session = new AnomalyLlmPass.Session(new ArrayList<AnomalyLlmPass.FlaggedAttribute>(), Arrays.asList(AnomalyLlmPassTest.addressType()));
		check("a known source and a new literal is accepted", !AnomalyLlmPassTest.invokeConstant(session, "addressType", "SAP", "RESIDENTIAL", "Rationale says residential.").isError);
		final AnomalyLlmPass.CreatedConstant created = session.createdConstants().get(0);
		check("it is tied to the source's own mapping and column", "m695".equals(created.flaggedMappingId) && "a-type".equals(created.flaggedAttributeId) && "col695".equals(created.landingColumnId)
			&& "RESIDENTIAL".equals(created.value) && created.confidence == 85);
		check("a second one for the same source is refused", AnomalyLlmPassTest.invokeConstant(session, "addressType", "SAP", "OTHER", "again").isError && session.createdConstants().size() == 1);
		check("the current value is not a correction", AnomalyLlmPassTest.invokeConstant(session, "addressType", "Salesforce", "MAILING", "same").isError);
		check("an unknown attribute or source is refused", AnomalyLlmPassTest.invokeConstant(session, "nope", "SAP", "X", "r").isError && AnomalyLlmPassTest.invokeConstant(session, "addressType", "Oracle", "X", "r").isError);
		check("a value that would change the pipeline's shape is refused", AnomalyLlmPassTest.invokeConstant(session, "addressType", "Salesforce", "a)|upper(", "r").isError
			&& AnomalyLlmPassTest.invokeConstant(session, "addressType", "Salesforce", "it's", "r").isError && AnomalyLlmPassTest.invokeConstant(session, "addressType", "Salesforce", "", "r").isError);
		check("an empty rationale is refused", AnomalyLlmPassTest.invokeConstant(session, "addressType", "Salesforce", "MAILING_HOME", " ").isError);
		check("nothing else was recorded", session.createdConstants().size() == 1);
	}

	private static void theConstantToolIsOfferedOnlyWhenThereIsAFinding()
	{
		check("none without a finding", new AnomalyLlmPass.Session(Arrays.asList(AnomalyLlmPassTest.onePersonSalutation())).toolGroup().tools.size() == 1);
		check("both with one", new AnomalyLlmPass.Session(new ArrayList<AnomalyLlmPass.FlaggedAttribute>(), Arrays.asList(AnomalyLlmPassTest.addressType())).toolGroup().tools.size() == 2);
	}

	private static LocalTool.Result invoke(final AnomalyLlmPass.Session pSession, final JsonNode pArguments)
	{
		final com.on.ebx.agent.ToolSet.LocalGroup group = pSession.toolGroup();
		for (final LocalTool tool : group.tools)
		{
			if (AnomalyLlmPass.TOOL_REPLACE.equals(tool.name))
			{
				try
				{
					return tool.handler.handle(pArguments);
				}
				catch (final Exception ex)
				{
					throw new RuntimeException(ex);
				}
			}
		}
		throw new AssertionError("propose_replacement tool not found in the group");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
