package com.on.ebx.factory.capability;

import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the rest of this module's tests: run the main, a failure throws. */
public final class ScriptInventoryTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		ScriptInventoryTest.parseListScriptsRealShape();
		ScriptInventoryTest.parseListScriptsMissingDataIsEmpty();
		ScriptInventoryTest.parseScriptRealShape();
		ScriptInventoryTest.parseScriptStripsUserInputTagsAndPrefersEnUs();
		ScriptInventoryTest.parseScriptFallsBackToFirstLocaleWhenNoEnUs();
		ScriptInventoryTest.parseScriptSourceMissingGivesNoteNamingDataSource();
		ScriptInventoryTest.parseExportedFunctionsSingleWithLineCommentHeader();
		ScriptInventoryTest.parseExportedFunctionsBlockCommentHeader();
		ScriptInventoryTest.parseExportedFunctionsMultipleAndNoParams();
		ScriptInventoryTest.parseExportedFunctionsNoHeaderComment();
		ScriptInventoryTest.parseExportedFunctionsEmptySource();
		ScriptInventoryTest.parseExportedFunctionsIgnoresNonExportedFunctions();
		ScriptInventoryTest.parseExportedFunctionsAcceptsAnyReturnType();
		System.out.println("ScriptInventoryTest OK");
	}

	// ------------------------------------------------------------------------------------------------ list_scripts

	/** The real shape measured live, 2026-09-21 (task brief): {@code {"success":true,"data":{"items":[...],"nextCursor","hasMore","limit"},"echoedInputs":{...}}}. */
	private static void parseListScriptsRealShape() throws Exception
	{
		final String json = "{\"success\":true,\"data\":{\"items\":["
			+ "{\"idePath\":\"library/Fasttrack\",\"label\":\"Fasttrack\",\"type\":\"script_package\",\"scriptTypeId\":null,\"dataModel\":null,"
			+ "\"isReadOnly\":false,\"module\":\"ebx-scripting\",\"pathInModule\":null,\"isPackaged\":true,\"referencesDeclaration\":false},"
			+ "{\"idePath\":\"library/Fasttrack/lib/iban\",\"label\":\"iban\",\"type\":\"script\",\"scriptTypeId\":\"library\",\"dataModel\":null,"
			+ "\"isReadOnly\":false,\"module\":\"ebx-scripting\",\"pathInModule\":null,\"isPackaged\":false,\"referencesDeclaration\":false}"
			+ "],\"nextCursor\":\"c2\",\"hasMore\":true,\"limit\":100},\"echoedInputs\":{}}";
		final JsonNode node = ScriptInventoryTest.MAPPER.readTree(json);
		final ScriptInventory.ListScriptsResult result = ScriptInventory.parseListScripts(node);
		ScriptInventoryTest.check("two items parsed", result.items.size() == 2);
		ScriptInventoryTest.check("first is a folder", result.items.get(0).isFolder());
		ScriptInventoryTest.check("second is a real script, not a folder", !result.items.get(1).isFolder());
		ScriptInventoryTest.check("idePath of the script preserved", "library/Fasttrack/lib/iban".equals(result.items.get(1).idePath));
		ScriptInventoryTest.check("hasMore true", result.hasMore);
		ScriptInventoryTest.check("nextCursor carried", "c2".equals(result.nextCursor));
	}

	private static void parseListScriptsMissingDataIsEmpty() throws Exception
	{
		final JsonNode node = ScriptInventoryTest.MAPPER.readTree("{\"success\":false,\"code\":\"NOT_FOUND\"}");
		final ScriptInventory.ListScriptsResult result = ScriptInventory.parseListScripts(node);
		ScriptInventoryTest.check("empty, never null, on a soft miss", result.items.isEmpty());
		ScriptInventoryTest.check("hasMore false", !result.hasMore);
	}

	// --------------------------------------------------------------------------------------------------- get_script

	/**
	 * The real shape captured live by the coordinator, 2026-09-21, for {@code library/Fasttrack/lib/iban}:
	 * {@code {"success":true,"data":{"idePath","name":"Fasttrack.lib.iban","scriptTypeId":"library",
	 * "revisionId","isReadOnly","parameters":[],"documentation":[{"locale":"en-US","label":
	 * "<user_input>...</user_input>","description":"<user_input>...</user_input>"},{"locale":"fr-FR",...}],
	 * "source":"..."},"echoedInputs":{...}}}.
	 */
	private static void parseScriptRealShape() throws Exception
	{
		final String json = "{\"success\":true,\"data\":{\"idePath\":\"library/Fasttrack/lib/iban\",\"name\":\"Fasttrack.lib.iban\",\"scriptTypeId\":\"library\","
			+ "\"revisionId\":\"r1\",\"isReadOnly\":false,\"parameters\":[],"
			+ "\"documentation\":[{\"locale\":\"en-US\",\"label\":\"<user_input>Fasttrack IBAN library</user_input>\","
			+ "\"description\":\"<user_input>Shared IBAN helpers: normalize, format, validate.</user_input>\"},"
			+ "{\"locale\":\"fr-FR\",\"label\":\"<user_input>Biblioth\\u00e8que IBAN Fasttrack</user_input>\",\"description\":\"<user_input>Aides IBAN partag\\u00e9es.</user_input>\"}],"
			+ "\"source\":\"export function isValid(rawIban: string): boolean\\n{\\n\\treturn true;\\n}\\n\"},\"echoedInputs\":{}}";
		final ScriptInventory.ScriptDetails details = ScriptInventory.parseScript(ScriptInventoryTest.MAPPER.readTree(json));
		ScriptInventoryTest.check("source found at data.source", details.source != null && details.source.contains("export function isValid"));
		ScriptInventoryTest.check("no source note when source was found", details.sourceNote == null);
		ScriptInventoryTest.check("import name is data.name", "Fasttrack.lib.iban".equals(details.importName));
		ScriptInventoryTest.check("label tag-stripped, en-US preferred", "Fasttrack IBAN library".equals(details.label));
		ScriptInventoryTest.check("description tag-stripped, en-US preferred", "Shared IBAN helpers: normalize, format, validate.".equals(details.description));
	}

	private static void parseScriptStripsUserInputTagsAndPrefersEnUs()
	{
		ScriptInventoryTest.check("strips both tags", "plain text".equals(ScriptInventory.stripUserInputTags("<user_input>plain text</user_input>")));
		ScriptInventoryTest.check("no-op when tags are absent", "already plain".equals(ScriptInventory.stripUserInputTags("already plain")));
		ScriptInventoryTest.check("null-safe", ScriptInventory.stripUserInputTags(null) == null);
	}

	private static void parseScriptFallsBackToFirstLocaleWhenNoEnUs() throws Exception
	{
		final String json = "{\"success\":true,\"data\":{\"idePath\":\"x\",\"name\":\"X\","
			+ "\"documentation\":[{\"locale\":\"fr-FR\",\"label\":\"<user_input>Bibliotheque</user_input>\",\"description\":\"<user_input>Texte.</user_input>\"}],"
			+ "\"source\":\"export function f(): boolean\\n{ return true; }\"}}";
		final ScriptInventory.ScriptDetails details = ScriptInventory.parseScript(ScriptInventoryTest.MAPPER.readTree(json));
		ScriptInventoryTest.check("falls back to the only entry when there is no en-US", "Bibliotheque".equals(details.label));
	}

	private static void parseScriptSourceMissingGivesNoteNamingDataSource() throws Exception
	{
		final ScriptInventory.ScriptDetails details = ScriptInventory.parseScript(ScriptInventoryTest.MAPPER.readTree("{\"success\":true,\"data\":{\"idePath\":\"x\"}}"));
		ScriptInventoryTest.check("source is null when data.source is missing", details.source == null);
		ScriptInventoryTest.check("note names 'data.source' specifically (only field tried - coordinator capture)", details.sourceNote != null && details.sourceNote.contains("data.source"));
	}

	// -------------------------------------------------------------------------------------------- exported functions

	private static void parseExportedFunctionsSingleWithLineCommentHeader()
	{
		final String source = "// Checks whether a phone number is well formed.\n"
			+ "// Returns true when the value matches the configured national plan.\n"
			+ "export function isValid(value: string): boolean\n"
			+ "{\n"
			+ "\treturn true;\n"
			+ "}\n";
		final List<ScriptInventory.FunctionInfo> functions = ScriptInventory.parseExportedFunctions(source);
		ScriptInventoryTest.check("one function found", functions.size() == 1);
		final ScriptInventory.FunctionInfo function = functions.get(0);
		ScriptInventoryTest.check("name", "isValid".equals(function.name));
		ScriptInventoryTest.check("one parameter", function.parameters.size() == 1);
		ScriptInventoryTest.check("parameter name/type", "value".equals(function.parameters.get(0).name) && "string".equals(function.parameters.get(0).type));
		ScriptInventoryTest.check("return type", "boolean".equals(function.returnType));
		ScriptInventoryTest.check("header comment joined: " + function.documentation,
			function.documentation != null && function.documentation.contains("Checks whether a phone number is well formed.")
				&& function.documentation.contains("Returns true when the value matches the configured national plan."));
	}

	/** Real doc-comment shape (coordinator capture): {@code @param}/{@code @return} lines are kept as plain text, not specially parsed. */
	private static void parseExportedFunctionsBlockCommentHeader()
	{
		final String source = "/**\n"
			+ " * Validates a raw IBAN against its country's length and MOD-97 check.\n"
			+ " * @param rawIban the raw IBAN value\n"
			+ " * @param addressCountryCode fallback country when the IBAN itself carries none\n"
			+ " * @return a json_object with a status field\n"
			+ " */\n"
			+ "export function validate(rawIban: string, addressCountryCode: string): json_object\n"
			+ "{\n"
			+ "\treturn null;\n"
			+ "}\n";
		final List<ScriptInventory.FunctionInfo> functions = ScriptInventory.parseExportedFunctions(source);
		ScriptInventoryTest.check("one function found", functions.size() == 1);
		final ScriptInventory.FunctionInfo function = functions.get(0);
		ScriptInventoryTest.check("two parameters", function.parameters.size() == 2);
		ScriptInventoryTest.check("second parameter", "addressCountryCode".equals(function.parameters.get(1).name) && "string".equals(function.parameters.get(1).type));
		ScriptInventoryTest.check("return type is json_object, captured as-is", "json_object".equals(function.returnType));
		ScriptInventoryTest.check("block comment joined, @param/@return kept as text: " + function.documentation,
			function.documentation != null && function.documentation.contains("Validates a raw IBAN") && function.documentation.contains("@param rawIban")
				&& function.documentation.contains("@return a json_object"));
	}

	private static void parseExportedFunctionsMultipleAndNoParams()
	{
		final String source = "export function isEmpty(): boolean\n"
			+ "{\n"
			+ "\treturn true;\n"
			+ "}\n"
			+ "\n"
			+ "export function normalise(value: string): string\n"
			+ "{\n"
			+ "\treturn value;\n"
			+ "}\n";
		final List<ScriptInventory.FunctionInfo> functions = ScriptInventory.parseExportedFunctions(source);
		ScriptInventoryTest.check("two functions found", functions.size() == 2);
		ScriptInventoryTest.check("first has no parameters", functions.get(0).parameters.isEmpty());
		ScriptInventoryTest.check("second name", "normalise".equals(functions.get(1).name));
	}

	private static void parseExportedFunctionsNoHeaderComment()
	{
		final String source = "const x := 1;\n"
			+ "export function bare(value: string): boolean\n"
			+ "{\n"
			+ "\treturn true;\n"
			+ "}\n";
		final List<ScriptInventory.FunctionInfo> functions = ScriptInventory.parseExportedFunctions(source);
		ScriptInventoryTest.check("one function found", functions.size() == 1);
		ScriptInventoryTest.check("no header comment: documentation is null", functions.get(0).documentation == null);
	}

	private static void parseExportedFunctionsEmptySource()
	{
		ScriptInventoryTest.check("null source gives an empty list", ScriptInventory.parseExportedFunctions(null).isEmpty());
		ScriptInventoryTest.check("blank source gives an empty list", ScriptInventory.parseExportedFunctions("   \n\t\n").isEmpty());
		ScriptInventoryTest.check("a source with no export function gives an empty list", ScriptInventory.parseExportedFunctions("const x := 1;\n").isEmpty());
	}

	/** Coordinator capture: "Non-exported `function …` lines exist too (ignore them)". */
	private static void parseExportedFunctionsIgnoresNonExportedFunctions()
	{
		final String source = "function internalHelper(x: string): string\n"
			+ "{\n"
			+ "\treturn x;\n"
			+ "}\n"
			+ "\n"
			+ "export function expectedLengthOf(countryCode: string): int\n"
			+ "{\n"
			+ "\treturn 0;\n"
			+ "}\n";
		final List<ScriptInventory.FunctionInfo> functions = ScriptInventory.parseExportedFunctions(source);
		ScriptInventoryTest.check("only the exported one is found", functions.size() == 1 && "expectedLengthOf".equals(functions.get(0).name));
	}

	/** Coordinator, 2026-09-21: "Accept any return type" - real library functions return int, json_object, boolean, string alike; this class must never filter by return type. */
	private static void parseExportedFunctionsAcceptsAnyReturnType()
	{
		final String source = "export function expectedLengthOf(countryCode: string): int\n{ return 0; }\n"
			+ "export function validate(rawIban: string, addressCountryCode: string): json_object\n{ return null; }\n";
		final List<ScriptInventory.FunctionInfo> functions = ScriptInventory.parseExportedFunctions(source);
		ScriptInventoryTest.check("both functions found regardless of return type", functions.size() == 2);
		ScriptInventoryTest.check("int return type captured as-is", "int".equals(functions.get(0).returnType));
		ScriptInventoryTest.check("json_object return type captured as-is", "json_object".equals(functions.get(1).returnType));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
