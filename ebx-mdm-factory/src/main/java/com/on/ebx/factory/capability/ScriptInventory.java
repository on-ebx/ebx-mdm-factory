package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Pure parsing of the {@code scripting} MCP server's own JSON responses ({@code list_scripts}, {@code
 * get_script}) and of an EBX Script source text into the plain shapes {@code
 * com.on.ebx.factory.ebx.CapabilityLlmSuggester} needs to build a {@link CapabilityLlmPass.FunctionRef} list -
 * no EBX class anywhere in this file, the same convention {@code master.MatchPlanProfileReader} follows for
 * its own MCP server, so this parsing is unit-tested with hand-built JSON fixtures rather than a live MCP
 * call (task brief for this lot, 2026-09-21: never call a live EBX instance).
 * <p>
 * <b>{@code list_scripts}' shape was measured live on the instance, 2026-09-21</b> (see the task brief):
 * {@code {"success":true,"data":{"items":[{"idePath","label","type","scriptTypeId","dataModel","isReadOnly",
 * "module","pathInModule","isPackaged","referencesDeclaration"}],"nextCursor","hasMore","limit"},
 * "echoedInputs":{...}}}. {@link #parseListScripts} reads exactly that shape. The root category's idePath is
 * verified literally {@code "library"} (coordinator capture, 2026-09-21): {@code
 * ebx.CapabilityLlmSuggester} walks straight from there, no root probe needed any more.
 * <p>
 * <b>{@code get_script}'s real shape - captured live by the coordinator, 2026-09-21</b> (for {@code
 * library/Fasttrack/lib/iban}, called with {@code {"scriptIdePath": "...", "includeSource": true}}):
 * {@code {"success":true,"data":{"idePath","name":"Fasttrack.lib.iban","scriptTypeId":"library","revisionId",
 * "isReadOnly","parameters":[],"documentation":[{"locale":"en-US","label":"<user_input>...</user_input>",
 * "description":"<user_input>...</user_input>"},{"locale":"fr-FR",...}],"source":"/** ... *&#47;\nuses
 * core.string as str;\n..."},"echoedInputs":{...}}}. {@link #parseScript} reads exactly that shape:
 * <ul>
 * <li>the source is {@code data.source} only (an earlier revision of this class guessed several field names
 * - dropped, per the coordinator's own instruction, now that the real key is known);</li>
 * <li>{@code data.name} (e.g. {@code Fasttrack.lib.iban}) is the IMPORT name a constraint would need
 * ({@code import Fasttrack.lib.iban as iban;}) - carried through as {@link ScriptDetails#importName} so a
 * later "apply" step has it, next to the idePath;</li>
 * <li>{@code documentation[]} entries wrap {@code label}/{@code description} in literal {@code
 * <user_input>...</user_input>} tags (an artefact of how the server echoes user-entered text) - {@link
 * #stripUserInputTags} removes them; {@link #parseScript} prefers the {@code en-US} entry, falling back to
 * the first one when there is no exact {@code en-US} match.</li>
 * </ul>
 * <p>
 * <b>Exported functions are lines {@code export function name(p: type, ...): returnType} in the source</b>
 * (task brief) - {@link #parseExportedFunctions} finds every such line with a single, deliberately simple
 * regex (no nested-generic-aware parameter parsing: a parameter list containing a generic type with a comma
 * inside, e.g. {@code dict<string, string>}, would be split incorrectly - documented here as a known
 * limitation rather than attempted, since CLAUDE.md's own EBX Script notes say an exported PARAMETER is
 * always a scalar {@code decimal}/{@code string}/{@code boolean}/{@code date}/{@code dt} in practice, never a
 * generic collection). A plain, non-exported {@code function ...} line is never matched (the regex requires
 * the {@code export} keyword), matching the coordinator's own capture ("Non-exported `function …` lines
 * exist too (ignore them)"). {@link #headerComment} recovers the header comment immediately above the
 * {@code export function} line - task brief: "the header comment is the real documentation", coordinator:
 * "Each export is preceded by a {@code /** ... *&#47;} block with {@code @param}/{@code @return}: capture it
 * as the function's doc" - supporting both a run of {@code //} line comments and a {@code /** ... *&#47;}
 * block comment, stopping at the first blank or code line found scanning upward. Every piece of text this
 * class extracts from a script (source-derived doc, library-level documentation) is bounded ({@link
 * #MAX_DOC_LENGTH}) - coordinator, "bounded" - since it ends up, untrusted, inside the language-model prompt
 * ({@code CapabilityLlmPass}'s own class Javadoc: this text is DATA, never instructions, and never the whole
 * source file).
 */
public final class ScriptInventory
{
	private ScriptInventory()
	{
	}

	/** Caps every piece of script-derived text this class hands to a caller (a function's header-comment doc, a library's documentation) - bounded because it ends up in a language-model prompt as untrusted data. */
	static final int MAX_DOC_LENGTH = 600;

	// ---------------------------------------------------------------------------------------------- list_scripts

	/** One entry of {@code list_scripts}' {@code data.items[]} - a script, or a {@code script_package} (a folder, e.g. {@code library/Fasttrack}). */
	public static final class ScriptRef
	{
		public final String idePath;
		public final String label;
		public final String type;
		public final String scriptTypeId;
		public final boolean isPackaged;

		public ScriptRef(final String pIdePath, final String pLabel, final String pType, final String pScriptTypeId, final boolean pIsPackaged)
		{
			this.idePath = pIdePath;
			this.label = pLabel;
			this.type = pType;
			this.scriptTypeId = pScriptTypeId;
			this.isPackaged = pIsPackaged;
		}

		/** {@code type} is {@code "script_package"} (or {@code isPackaged} is true): a folder to recurse into, not a script to fetch source for. */
		public boolean isFolder()
		{
			return this.isPackaged || "script_package".equals(this.type);
		}
	}

	public static final class ListScriptsResult
	{
		public final List<ScriptRef> items;
		/** Non-empty only when {@link #hasMore} - pass back as {@code cursor} to continue the listing. */
		public final String nextCursor;
		public final boolean hasMore;

		ListScriptsResult(final List<ScriptRef> pItems, final String pNextCursor, final boolean pHasMore)
		{
			this.items = pItems;
			this.nextCursor = pNextCursor;
			this.hasMore = pHasMore;
		}
	}

	/** @return an empty result (never null) if {@code pResponseJson} does not carry {@code data.items} as an array - a soft miss, not a parse failure. */
	public static ListScriptsResult parseListScripts(final JsonNode pResponseJson)
	{
		final JsonNode data = pResponseJson.path("data");
		final List<ScriptRef> items = new ArrayList<ScriptRef>();
		for (final JsonNode item : data.path("items"))
		{
			final String idePath = item.path("idePath").asText(null);
			if (idePath == null)
			{
				continue;
			}
			items.add(new ScriptRef(idePath, item.path("label").asText(idePath), item.path("type").asText(null), item.path("scriptTypeId").asText(null),
				item.path("isPackaged").asBoolean(false)));
		}
		final String nextCursor = data.path("nextCursor").asText("");
		return new ListScriptsResult(items, nextCursor, data.path("hasMore").asBoolean(!nextCursor.isEmpty()));
	}

	// ------------------------------------------------------------------------------------------------- get_script

	/**
	 * Everything {@link #parseScript} extracted about one script: {@link #source} is null exactly when {@link
	 * #sourceNote} explains why (a missing/blank {@code data.source} - see the class Javadoc for the now-real,
	 * captured shape). {@link #importName}/{@link #label}/{@link #description} are the library-level facts a
	 * caller needs regardless of whether the source parsed - {@link #description} is already bounded to
	 * {@link #MAX_DOC_LENGTH} and tag-stripped.
	 */
	public static final class ScriptDetails
	{
		public final String source;
		public final String sourceNote;
		public final String importName;
		public final String label;
		public final String description;

		ScriptDetails(final String pSource, final String pSourceNote, final String pImportName, final String pLabel, final String pDescription)
		{
			this.source = pSource;
			this.sourceNote = pSourceNote;
			this.importName = pImportName;
			this.label = pLabel;
			this.description = pDescription;
		}
	}

	public static ScriptDetails parseScript(final JsonNode pResponseJson)
	{
		final JsonNode data = pResponseJson.path("data");
		final JsonNode sourceNode = data.path("source");
		final String source = sourceNode.isTextual() && !sourceNode.asText().trim().isEmpty() ? sourceNode.asText() : null;
		final String sourceNote = source == null
			? "get_script's response carried no non-blank string at 'data.source' (the real, captured field - coordinator, 2026-09-21): this script's functions cannot be inventoried."
			: null;
		final String importName = data.path("name").asText(null);
		final JsonNode documentation = ScriptInventory.preferredDocumentation(data.path("documentation"));
		final String label = ScriptInventory.stripUserInputTags(documentation.path("label").asText(null));
		final String description = ScriptInventory.truncate(ScriptInventory.stripUserInputTags(documentation.path("description").asText(null)));
		return new ScriptDetails(source, sourceNote, importName, label, description);
	}

	/** The {@code en-US} entry of {@code documentation[]} when present, otherwise the first entry, otherwise a missing node (every {@code .path(...)} read on it then answers blank, never throws). */
	private static JsonNode preferredDocumentation(final JsonNode pDocumentationArray)
	{
		JsonNode first = null;
		for (final JsonNode entry : pDocumentationArray)
		{
			if (first == null)
			{
				first = entry;
			}
			if ("en-US".equals(entry.path("locale").asText(null)))
			{
				return entry;
			}
		}
		return first == null ? pDocumentationArray : first;
	}

	/** Strips a literal {@code <user_input>}/{@code </user_input>} wrapper (an artefact of how the server echoes user-entered text - see class Javadoc); null-safe, tolerant of the tags being absent. */
	static String stripUserInputTags(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		return pText.replace("<user_input>", "").replace("</user_input>", "").trim();
	}

	static String truncate(final String pText)
	{
		if (pText == null || pText.length() <= ScriptInventory.MAX_DOC_LENGTH)
		{
			return pText;
		}
		return pText.substring(0, ScriptInventory.MAX_DOC_LENGTH) + "...";
	}

	// ----------------------------------------------------------------------------------------- exported functions

	public static final class Parameter
	{
		public final String name;
		public final String type;

		public Parameter(final String pName, final String pType)
		{
			this.name = pName;
			this.type = pType;
		}
	}

	public static final class FunctionInfo
	{
		public final String name;
		public final List<Parameter> parameters;
		public final String returnType;
		/** The header comment immediately above the {@code export function} line, bounded, or null when none was found - see {@link #headerComment}. */
		public final String documentation;

		public FunctionInfo(final String pName, final List<Parameter> pParameters, final String pReturnType, final String pDocumentation)
		{
			this.name = pName;
			this.parameters = pParameters == null ? new ArrayList<Parameter>() : pParameters;
			this.returnType = pReturnType;
			this.documentation = pDocumentation;
		}
	}

	/**
	 * A deliberately simple, single-line pattern (see the class Javadoc for what it does not handle):
	 * {@code export function <name>(<params>): <returnType>}. Whitespace-tolerant; the return type is
	 * everything up to (but not including) the first {@code {}/{@code ;}/end of line. Every return type is
	 * accepted here - a library's own return-type CONVENTIONS (e.g. {@code json_object}) are not this class'
	 * concern; {@code CapabilityLlmPass} no longer gates on it either (coordinator, 2026-09-21: "Accept any
	 * return type; store it").
	 */
	private static final Pattern EXPORT_FUNCTION = Pattern.compile("export\\s+function\\s+(\\w+)\\s*\\(([^)]*)\\)\\s*:\\s*([^{;\\r\\n]+)");

	/** Every exported function found in {@code pSource}, in source order - empty (never null) for a blank or function-free source. */
	public static List<FunctionInfo> parseExportedFunctions(final String pSource)
	{
		final List<FunctionInfo> out = new ArrayList<FunctionInfo>();
		if (pSource == null || pSource.trim().isEmpty())
		{
			return out;
		}
		final String[] lines = pSource.split("\r?\n", -1);
		for (int lineIndex = 0; lineIndex < lines.length; lineIndex++)
		{
			final Matcher matcher = ScriptInventory.EXPORT_FUNCTION.matcher(lines[lineIndex]);
			if (matcher.find())
			{
				final String name = matcher.group(1);
				final List<Parameter> parameters = ScriptInventory.parseParameters(matcher.group(2));
				final String returnType = matcher.group(3).trim();
				out.add(new FunctionInfo(name, parameters, returnType, ScriptInventory.truncate(ScriptInventory.headerComment(lines, lineIndex))));
			}
		}
		return out;
	}

	/** {@code "a: string, b: decimal"} -&gt; two {@link Parameter}s. A blank parameter list yields an empty list. Deliberately naive on a top-level comma inside a generic parameter type - see the class Javadoc. */
	private static List<Parameter> parseParameters(final String pRawParameters)
	{
		final List<Parameter> out = new ArrayList<Parameter>();
		if (pRawParameters == null || pRawParameters.trim().isEmpty())
		{
			return out;
		}
		for (final String rawParam : pRawParameters.split(","))
		{
			final String trimmed = rawParam.trim();
			if (trimmed.isEmpty())
			{
				continue;
			}
			final int colon = trimmed.indexOf(':');
			if (colon < 0)
			{
				out.add(new Parameter(trimmed, null));
			}
			else
			{
				out.add(new Parameter(trimmed.substring(0, colon).trim(), trimmed.substring(colon + 1).trim()));
			}
		}
		return out;
	}

	/**
	 * Scans upward from {@code pFunctionLineIndex - 1}: a run of {@code //} line comments (each stripped of
	 * its leading {@code //} and joined with a space), or a {@code /** ... *&#47;} block comment immediately
	 * above (its {@code *} continuation markers stripped, {@code @param}/{@code @return} lines kept as plain
	 * text) - whichever is found first. Stops at the first blank line or non-comment line without a match:
	 * null in that case (no header comment).
	 */
	static String headerComment(final String[] pLines, final int pFunctionLineIndex)
	{
		int index = pFunctionLineIndex - 1;
		while (index >= 0 && pLines[index].trim().isEmpty())
		{
			index--;
		}
		if (index < 0)
		{
			return null;
		}
		final String immediate = pLines[index].trim();
		if (immediate.endsWith("*/"))
		{
			final List<String> blockLines = new ArrayList<String>();
			// the closing line itself may carry text before "*/" (rare, but handled): strip once, then walk upward
			String closing = immediate.substring(0, immediate.length() - 2).trim();
			if (closing.startsWith("*"))
			{
				closing = closing.substring(1).trim();
			}
			if (!closing.isEmpty())
			{
				blockLines.add(0, closing);
			}
			int blockIndex = index - 1;
			while (blockIndex >= 0)
			{
				final String blockLine = pLines[blockIndex].trim();
				if (blockLine.startsWith("/**") || blockLine.startsWith("/*"))
				{
					String opening = blockLine.startsWith("/**") ? blockLine.substring(3) : blockLine.substring(2);
					opening = opening.trim();
					if (!opening.isEmpty())
					{
						blockLines.add(0, opening);
					}
					return blockLines.isEmpty() ? null : ScriptInventory.join(blockLines);
				}
				String middle = blockLine;
				if (middle.startsWith("*"))
				{
					middle = middle.substring(1).trim();
				}
				if (!middle.isEmpty())
				{
					blockLines.add(0, middle);
				}
				blockIndex--;
			}
			// no opening "/*" found within the file: not a well-formed block comment, nothing to report
			return null;
		}
		if (immediate.startsWith("//"))
		{
			final List<String> commentLines = new ArrayList<String>();
			int lineCommentIndex = index;
			while (lineCommentIndex >= 0 && pLines[lineCommentIndex].trim().startsWith("//"))
			{
				commentLines.add(0, pLines[lineCommentIndex].trim().substring(2).trim());
				lineCommentIndex--;
			}
			return commentLines.isEmpty() ? null : ScriptInventory.join(commentLines);
		}
		return null;
	}

	private static String join(final List<String> pLines)
	{
		final StringBuilder out = new StringBuilder();
		for (final String line : pLines)
		{
			if (out.length() > 0)
			{
				out.append(' ');
			}
			out.append(line);
		}
		return out.toString();
	}
}
