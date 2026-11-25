package com.on.ebx.factory.landing;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;

/** JUnit-free: run the main, a failure throws. */
public final class KeyLlmPassTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		final List<String> sampleColumns = Arrays.asList("BusinessPartner", "FirstName", "LastName");
		final List<List<String>> sampleRows = Arrays.asList(Arrays.asList("1000123", "Jean", "Martin"), Arrays.asList("1000124", "Marie", "Martin"));
		final List<KeyEvidence.Column> evidence = KeyEvidence.analyse(sampleColumns, sampleRows);
		final List<KeyLlmPass.ColumnFacts> columns = new ArrayList<KeyLlmPass.ColumnFacts>();
		columns.add(new KeyLlmPass.ColumnFacts("BusinessPartner", "STRING", Boolean.FALSE, false, KeyEvidence.find(evidence, "BusinessPartner")));
		columns.add(new KeyLlmPass.ColumnFacts("FirstName", "STRING", null, true, KeyEvidence.find(evidence, "FirstName")));
		columns.add(new KeyLlmPass.ColumnFacts("LastName", "STRING", null, true, KeyEvidence.find(evidence, "LastName")));
		columns.add(new KeyLlmPass.ColumnFacts("Region", "STRING", null, false, null));
		final KeyLlmPass.TableFacts facts = new KeyLlmPass.TableFacts("SAP", "Personnes_SAP_Sample", "Personnes_SAP_Sample", 180L, "RULE", columns, sampleColumns, sampleRows);

		final String prompt = KeyLlmPass.userPrompt(facts);
		KeyLlmPassTest.check("the prompt carries the sample values", prompt.contains("1000123") && prompt.contains("\"Martin\""));
		KeyLlmPassTest.check("the prompt carries the per-column evidence", prompt.contains("\"uniqueInSample\":true") && prompt.contains("\"distinct\":1"));
		KeyLlmPassTest.check("the current key and its origin are stated", prompt.contains("\"currentKey\":[\"FirstName\",\"LastName\"]") && prompt.contains("\"currentKeyOrigin\":\"RULE\""));
		KeyLlmPassTest.check("a column absent from the sample says so", prompt.contains("\"sample\":\"not in the sample\""));
		// lot B (2026-09-24): NONE shows no value at all; a withheld column shows none whatever the level
		final String none = KeyLlmPass.userPrompt(facts, KeyLlmPass.Sharing.NONE, java.util.Collections.<String>emptySet());
		KeyLlmPassTest.check("NONE: no value, no shape: " + none, !none.contains("1000123") && !none.contains("Martin")
			&& !none.contains(com.on.ebx.factory.quality.Shapes.of("Martin")) && none.contains("\"uniqueInSample\":true"));
		final String withheld = KeyLlmPass.userPrompt(facts, KeyLlmPass.Sharing.VALUES, new java.util.HashSet<String>(Arrays.asList("lastname")));
		KeyLlmPassTest.check("a withheld column shows no value even in VALUES mode: " + withheld,
			withheld.contains("1000123") && !withheld.contains("Martin") && withheld.contains("withheld by the data policy"));
		// security review (2026-09-24): without SAMPLE_VALUES, shapes only - no real value leaves
		final String shaped = KeyLlmPass.userPrompt(facts, false);
		KeyLlmPassTest.check("shapes mode sends no real value: " + shaped, !shaped.contains("1000123") && !shaped.contains("Martin") && !shaped.contains("Jean"));
		KeyLlmPassTest.check("shapes mode keeps the shapes and the evidence: " + shaped,
			shaped.contains("\"" + com.on.ebx.factory.quality.Shapes.of("1000123") + "\"") && shaped.contains("\"" + com.on.ebx.factory.quality.Shapes.of("Martin") + "\"")
				&& shaped.contains("\"uniqueInSample\":true"));
		// bug fixed live 2026-09-27 (project DeducedTest3): shapes mode must say the values are masked and name the
		// fields to trust, so the model does not conclude "not unique" from several rows showing the same masked
		// shape (e.g. "A99") for what are actually distinct real values.
		KeyLlmPassTest.check("shapes mode discloses masking and points at the measured fields: " + shaped,
			shaped.contains("\"sampleValueNote\"") && shaped.contains("MASKED SHAPES") && shaped.contains("distinct")
				&& shaped.contains("uniqueInSample"));
		KeyLlmPassTest.check("VALUES mode carries no masking disclaimer: " + prompt, !prompt.contains("sampleValueNote"));
		KeyLlmPassTest.check("NONE mode discloses withholding and points at the measured fields: " + none,
			none.contains("\"sampleValueNote\"") && none.contains("withheld") && none.contains("distinct") && none.contains("uniqueInSample"));
		KeyLlmPassTest.check("sampleValueNote is null for VALUES, non-null for SHAPES/NONE",
			KeyLlmPass.sampleValueNote(KeyLlmPass.Sharing.VALUES) == null && KeyLlmPass.sampleValueNote(KeyLlmPass.Sharing.SHAPES) != null
				&& KeyLlmPass.sampleValueNote(KeyLlmPass.Sharing.NONE) != null);
		KeyLlmPassTest.check("the system prompt names the tool and forbids prose", KeyLlmPass.systemPrompt().contains("propose_key EXACTLY ONCE") && KeyLlmPass.systemPrompt().contains("NOT recorded"));

		final KeyLlmPass.Session session = new KeyLlmPass.Session(facts);
		KeyLlmPassTest.check("a fresh session recorded nothing", session.recordedNothing() && session.proposal() == null);
		final ToolSet.LocalGroup group = session.toolGroup();
		final LocalTool propose = KeyLlmPassTest.tool(group, KeyLlmPass.TOOL_PROPOSE);
		final LocalTool note = KeyLlmPassTest.tool(group, KeyLlmPass.TOOL_NOTE);
		KeyLlmPassTest.check("both tools are offered", propose != null && note != null);

		LocalTool.Result result = propose.handler.handle(KeyLlmPassTest.json("{\"columns\":[\"Id\"],\"confidence\":90,\"rationale\":\"x\"}"));
		KeyLlmPassTest.check("an unknown column is refused, naming the real ones", result.isError && result.text.contains("Unknown column 'Id'") && result.text.contains("BusinessPartner"));
		KeyLlmPassTest.check("and nothing was recorded", session.proposal() == null);

		result = propose.handler.handle(KeyLlmPassTest.json("{\"columns\":[\"businesspartner\",\"BusinessPartner\"],\"confidence\":140,\"rationale\":\"  The system's own partner number, unique in the sample.  \"}"));
		KeyLlmPassTest.check("a valid call is recorded", !result.isError && session.proposal() != null && !session.recordedNothing());
		KeyLlmPassTest.check("names are resolved to their exact spelling and de-duplicated", session.proposal().columns.equals(Arrays.asList("BusinessPartner")));
		KeyLlmPassTest.check("confidence is clamped to 100", session.proposal().confidence == 100);
		KeyLlmPassTest.check("the rationale is trimmed", session.proposal().rationale.equals("The system's own partner number, unique in the sample."));

		result = propose.handler.handle(KeyLlmPassTest.json("{\"columns\":[\"FirstName\"],\"confidence\":10,\"rationale\":\"\"}"));
		KeyLlmPassTest.check("an empty rationale is refused", result.isError && result.text.contains("rationale"));
		KeyLlmPassTest.check("the earlier proposal is kept", session.proposal().columns.equals(Arrays.asList("BusinessPartner")));

		result = propose.handler.handle(KeyLlmPassTest.json("{\"columns\":[],\"confidence\":10,\"rationale\":\"x\"}"));
		KeyLlmPassTest.check("an empty column list is refused", result.isError);

		result = propose.handler.handle(KeyLlmPassTest.json("{\"columns\":[\"FirstName\",\"LastName\"],\"confidence\":35,\"rationale\":\"No stable identifier; natural composite, unique in the sample.\"}"));
		KeyLlmPassTest.check("a later valid call replaces the proposal (composite, in order)", session.proposal().columns.equals(Arrays.asList("FirstName", "LastName")) && session.proposal().confidence == 35);

		result = note.handler.handle(KeyLlmPassTest.json("{\"text\":\"\"}"));
		KeyLlmPassTest.check("an empty note is refused", result.isError);
		result = note.handler.handle(KeyLlmPassTest.json("{\"text\":\"Region is a code, not an identifier.\"}"));
		KeyLlmPassTest.check("a note is kept", !result.isError && session.notes().size() == 1);
		System.out.println("KeyLlmPassTest OK");
	}

	private static LocalTool tool(final ToolSet.LocalGroup pGroup, final String pName)
	{
		for (final LocalTool tool : pGroup.tools)
		{
			if (tool.name.equals(pName))
			{
				return tool;
			}
		}
		return null;
	}

	private static JsonNode json(final String pJson) throws IOException
	{
		return KeyLlmPassTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
