package com.on.ebx.factory.staging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.on.ebx.factory.core.PromptData;
import com.on.ebx.factory.landing.KeyLlmPass;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CodeMap;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.Shapes;

/**
 * Codes transcoding lot H3 (2026-10-17, docs/CODES-TRANSCODING.md): the language-model half of "codes to align". When two
 * sources feed one attribute with a short repeated list of codes that do not line up (lot D's detector, {@link
 * NormalisationPass.Alignment}: {@code Bronze}/{@code Argent}/{@code Or} against another system's {@code B}/{@code S}/{@code G}; {@code M.}/{@code Mme}
 * against {@code MR}/{@code MRS}), the model proposes ONE canonical list of target codes and, where it may see the codes, a
 * complete table source code -&gt; target code for every source. Same shape as every other {@code *LlmPass} of the factory:
 * this class is pure (prompts, the tool schema, a {@link Session} that validates and records what the model called);
 * {@code ebx.CodeTranscodingSuggester} runs it through {@code AgentRuntime} and writes the proposal as {@code MappingStep}
 * rows ({@code TRANSCODE_REFERENCE} with a {@link CodeMap} argument) a person reviews in the Mapping matrix.
 * <p>
 * <b>Suggestions are language-model first</b> (CLAUDE.md sec 15): nothing below reads an attribute or column name, a tag or a
 * vocabulary; the Java side only checks what the model answered, against the values.
 * <p>
 * <b>What the model receives follows the project's data-sharing level</b> ({@link KeyLlmPass.Sharing}, {@code ebx.LlmDataPolicy};
 * there is no separate "short code lists" allowance: a code is a value): under {@code VALUES} (level {@code SAMPLE_VALUES}, and
 * neither the attribute nor a source column is on the sensitive list) the codes of every source with their row counts, and
 * the answer must map EVERY code; under {@code SHAPES} (level {@code SHAPES_ONLY}) the shape of each code ({@code Aaaaa}) with its
 * rows, and under {@code NONE} only the number of codes and rows of each source. Without the codes the model can only propose the TARGET list, from the
 * attribute's name and label: the table is then written with the observed codes on the left and an EMPTY target, except
 * where the code already is a target (same {@link CleaningSteps#normalizeKey}) or the chosen reference resolves it, and a
 * person fills the rest in the matrix. <b>An unlisted code is a failure</b>, like {@code map(...)}'s rule: the tool answers with
 * what is missing and the model is asked to resend; the table that does reach a step is verified against the values ({@link
 * CodeMap#cover}) and a source it does not cover completely carries a confidence below the bulk-accept bar.
 */
public final class CodeTranscodingPass
{
	/** Start of the rationale of every step this pass writes (not {@link NormalisationPass#MARKER}: the normalisation sweeps must not recognise these rows as their own). */
	public static final String MARKER = "Code transcoding: ";
	public static final String TOOL_PROPOSE = "propose_code_transcoding";
	/** The most a language-model table is trusted when it covers every code: a person still reads it. */
	public static final int MAX_CONFIDENCE = 85;
	static final int MAX_TARGETS = 60;
	static final int MAX_CODE_LENGTH = 100;
	private static final int MAX_ERRORS_SHOWN = 8;
	private static final int REFERENCE_SHOWN = 40;
	private static final int MAX_EXAMPLES = 5;
	private static final int RATIONALE_MAX = 600;

	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final JsonNode PROPOSE_SCHEMA = CodeTranscodingPass.schema("{\"type\":\"object\",\"properties\":{"
		+ "\"attribute\":{\"type\":\"string\",\"description\":\"The attribute, exactly as listed.\"},"
		+ "\"targets\":{\"type\":\"array\",\"items\":{\"type\":\"string\"},\"minItems\":1,\"description\":\"The common code list every source is mapped onto, in the order a reader expects.\"},"
		+ "\"reference\":{\"type\":\"string\",\"description\":\"Optional: one of the listed reference lists, when the targets are its codes.\"},"
		+ "\"mappings\":{\"type\":\"array\",\"description\":\"One entry per source, only when the codes are given in the task: every code of that source with its target.\",\"items\":{\"type\":\"object\","
		+ "\"properties\":{\"source\":{\"type\":\"string\",\"description\":\"The source label, exactly as listed.\"},"
		+ "\"codes\":{\"type\":\"array\",\"items\":{\"type\":\"object\",\"properties\":{\"from\":{\"type\":\"string\"},\"to\":{\"type\":\"string\"}},\"required\":[\"from\",\"to\"]}}},"
		+ "\"required\":[\"source\",\"codes\"]}},"
		+ "\"rationale\":{\"type\":\"string\",\"description\":\"Why this list and these correspondences - two or three sentences a data steward can check.\"},"
		+ "\"confidence\":{\"type\":\"integer\",\"minimum\":0,\"maximum\":100}},"
		+ "\"required\":[\"attribute\",\"targets\",\"rationale\",\"confidence\"]}");

	private CodeTranscodingPass()
	{
	}

	private static JsonNode schema(final String pJson)
	{
		try
		{
			return CodeTranscodingPass.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			throw new IllegalStateException("invalid built-in tool schema: " + ex.getMessage(), ex);
		}
	}

	// ----------------------------------------------------------------------------------------------------- input

	/** One source column that feeds the attribute with a short list of codes. */
	public static final class Source
	{
		public final String mappingId;
		public final String sourceSystem;
		public final String sourceColumn;
		/** Distinct codes with their rows, most frequent first. */
		public final List<Detectors.ValueCount> values;

		public Source(final String pMappingId, final String pSourceSystem, final String pSourceColumn, final List<Detectors.ValueCount> pValues)
		{
			this.mappingId = pMappingId;
			this.sourceSystem = pSourceSystem == null ? "" : pSourceSystem;
			this.sourceColumn = pSourceColumn == null ? "" : pSourceColumn;
			this.values = pValues == null ? new ArrayList<Detectors.ValueCount>() : pValues;
		}

		/** What the model calls this source: the system and the column (two columns of one system may feed one attribute). */
		public String label()
		{
			return this.sourceColumn.isEmpty() ? this.sourceSystem : this.sourceSystem + " / " + this.sourceColumn;
		}

		long rows()
		{
			long total = 0L;
			for (final Detectors.ValueCount value : this.values)
			{
				total += value.count;
			}
			return total;
		}
	}

	/** A configured reference list a proposal may reuse. */
	public static final class Reference
	{
		public final String kind;
		/** Every code of the reference, in table order. */
		public final List<String> codes;

		public Reference(final String pKind, final java.util.Collection<String> pCodes)
		{
			this.kind = pKind;
			this.codes = new ArrayList<String>(pCodes);
		}
	}

	/** One attribute whose sources do not line up, as asked of the model. */
	public static final class Request
	{
		public final String attributeId;
		public final String entityName;
		public final String attributeName;
		public final String attributeLabel;
		public final KeyLlmPass.Sharing sharing;
		public final List<Source> sources;

		public Request(final String pAttributeId, final String pEntityName, final String pAttributeName, final String pAttributeLabel, final KeyLlmPass.Sharing pSharing,
			final List<Source> pSources)
		{
			this.attributeId = pAttributeId;
			this.entityName = pEntityName == null ? "" : pEntityName;
			this.attributeName = pAttributeName == null ? "" : pAttributeName;
			this.attributeLabel = pAttributeLabel == null || pAttributeLabel.trim().isEmpty() ? this.attributeName : pAttributeLabel;
			this.sharing = pSharing == null ? KeyLlmPass.Sharing.NONE : pSharing;
			this.sources = pSources;
		}
	}

	// --------------------------------------------------------------------------------------------------- output

	/** What the model decided for one attribute, checked. */
	public static final class Proposal
	{
		public final Request request;
		public final List<String> targets;
		/** The reference the targets come from, or "". */
		public final String basedOn;
		/** mappingId -&gt; the table of that source (identity tables included: {@link #drafts} decides what is written). */
		public final Map<String, CodeMap> maps;
		public final int confidence;
		public final String rationale;

		Proposal(final Request pRequest, final List<String> pTargets, final String pBasedOn, final Map<String, CodeMap> pMaps, final int pConfidence, final String pRationale)
		{
			this.request = pRequest;
			this.targets = pTargets;
			this.basedOn = pBasedOn;
			this.maps = pMaps;
			this.confidence = pConfidence;
			this.rationale = pRationale;
		}
	}

	/** One {@code MappingStep} to write. */
	public static final class Draft
	{
		public final String mappingId;
		public final String argument;
		public final int confidence;
		/** The rationale in English: the Java-built note, then the language model's own reasons. */
		public final String rationale;
		/** Staging last points (2026-10-17): the Java-built part as a coded note; {@link #tail} is the model's own text that follows it. */
		public final NoteTexts.Note note;
		public final String tail;
		public final long valuesSeen;
		public final long valuesChanged;
		public final long valuesUnresolved;
		/** Shapes of before/after pairs, never values. */
		public final List<String> examples;
		public final boolean complete;

		Draft(final String pMappingId, final String pArgument, final int pConfidence, final NoteTexts.Note pNote, final String pTail, final long pSeen, final long pChanged,
			final long pUnresolved, final List<String> pExamples, final boolean pComplete)
		{
			this.mappingId = pMappingId;
			this.argument = pArgument;
			this.confidence = pConfidence;
			this.note = pNote;
			this.tail = pTail == null ? "" : pTail.trim();
			this.rationale = (pNote.english() + (this.tail.isEmpty() ? "" : " " + this.tail)).trim();
			this.valuesSeen = pSeen;
			this.valuesChanged = pChanged;
			this.valuesUnresolved = pUnresolved;
			this.examples = pExamples;
			this.complete = pComplete;
		}

		/** The rationale as it is written: the English and the marker of its note (the model's own text between them), within {@code pMax} characters. */
		public String storedRationale(final int pMax)
		{
			return this.note.stored(this.tail, pMax);
		}
	}

	// ----------------------------------------------------------------------------------------------------- prompts

	public static String systemPrompt()
	{
		return "You are the code-list pass of the ON EBX MDM Factory. Several source systems feed ONE attribute of a business object with a short list of "
			+ "repeated codes (a handful of distinct values, each used by many rows), and the systems do not write that list the same way - one writes "
			+ "words, another letters, another numbers. Java measured this: you are given, per attribute, each source with the number of distinct codes "
			+ "and rows and, depending on what the project may share, the codes themselves, their shapes, or only the counts.\n\n"
			+ "Your task, for each attribute: (1) choose ONE target code list that every source can be mapped onto - short, stable, readable, with no code "
			+ "that only one system would understand. When a listed reference list on this instance fits the attribute, reuse its codes and name it in "
			+ "'reference'; otherwise propose a small list of your own. (2) When the codes are given to you, give the correspondence of EVERY code of EVERY "
			+ "source: a code you leave out makes your answer invalid, and so does a target that is not in your list. A code with no counterpart gets its own "
			+ "target code in the list (never an empty one). When the codes are NOT given to you, send only the targets: do not invent correspondences for "
			+ "codes you cannot see; a person will fill them in. Call propose_code_transcoding once per attribute; if the tool reports a problem, correct it "
			+ "and call again.\n\n"
			+ "Judge from the codes and the attribute's name and label only. If you cannot tell what a code means, say so in the rationale and give it your best "
			+ "target with a low confidence rather than skipping it. When every attribute is done, answer with a short summary." + PromptData.SYSTEM_NOTICE;
	}

	/** The user message for the attributes of one business object. {@code pReferences} are the lists configured on the instance (they are reference data, not project data). */
	public static String userPrompt(final String pEntityLabel, final List<Request> pRequests, final List<Reference> pReferences)
	{
		final StringBuilder sb = new StringBuilder();
		sb.append("Business object: ").append(pEntityLabel).append("\n\n"); // a name from the model: inside the fence with the rest
		for (final Request request : pRequests)
		{
			sb.append("Attribute ").append(request.attributeName).append(" (label '").append(request.attributeLabel).append("'): ")
				.append(request.sources.size()).append(" sources write its code list differently.\n");
			for (final Source source : request.sources)
			{
				sb.append("  * source '").append(source.label()).append("': ").append(source.values.size()).append(" distinct codes, ").append(source.rows()).append(" rows");
				if (request.sharing == KeyLlmPass.Sharing.VALUES)
				{
					sb.append("; codes (rows): ");
					for (int i = 0; i < source.values.size(); i++)
					{
						sb.append(i > 0 ? ", " : "").append(source.values.get(i).value.trim()).append(" (").append(source.values.get(i).count).append(')');
					}
				}
				else if (request.sharing == KeyLlmPass.Sharing.SHAPES)
				{
					sb.append("; code shapes (rows): ").append(CodeTranscodingPass.shapesText(source));
				}
				sb.append('\n');
			}
			sb.append(request.sharing == KeyLlmPass.Sharing.VALUES ? "  The codes are given: map every one of them.\n"
				: "  The codes are NOT shared by this project: send the targets only, with no 'mappings'.\n");
		}
		final String data = sb.toString();
		final StringBuilder references = new StringBuilder();
		if (pReferences != null && !pReferences.isEmpty())
		{
			references.append("Reference lists configured on this instance (reuse one only when it really fits the attribute):\n");
			for (final Reference reference : pReferences)
			{
				references.append("  * ").append(reference.kind).append(": ").append(reference.codes.size()).append(" codes");
				if (!reference.codes.isEmpty())
				{
					references.append(", for example ");
					for (int i = 0; i < reference.codes.size() && i < CodeTranscodingPass.REFERENCE_SHOWN; i++)
					{
						references.append(i > 0 ? ", " : "").append(reference.codes.get(i));
					}
				}
				references.append('\n');
			}
		}
		else
		{
			references.append("No reference list is configured on this instance: propose a list of your own.\n");
		}
		return "Propose the common code list of each attribute below, then answer with a short summary. Names and codes come from the source tables.\n"
			+ PromptData.fence("CODE LISTS TO ALIGN", data) + PromptData.fence("REFERENCE LISTS", references.toString());
	}

	private static String shapesText(final Source pSource)
	{
		final Map<String, Long> byShape = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount value : pSource.values)
		{
			final String shape = Shapes.of(value.value.trim());
			final Long seen = byShape.get(shape);
			byShape.put(shape, Long.valueOf((seen == null ? 0L : seen.longValue()) + value.count));
		}
		final StringBuilder out = new StringBuilder();
		for (final Map.Entry<String, Long> entry : byShape.entrySet())
		{
			out.append(out.length() > 0 ? ", " : "").append(entry.getKey()).append(" (").append(entry.getValue()).append(')');
		}
		return out.toString();
	}

	// ---------------------------------------------------------------------------------------------------- session

	/** One agent run's worth of local state. Not thread-shared - one instance per {@code AgentRuntime.run} call, like {@code AnomalyLlmPass.Session}. */
	public static final class Session
	{
		private final Map<String, Request> byName = new LinkedHashMap<String, Request>();
		private final Map<String, Reference> references = new LinkedHashMap<String, Reference>();
		private final CleaningSteps.Lookups lookups;
		private final Map<String, Proposal> proposals = new LinkedHashMap<String, Proposal>();
		private final List<String> notes = new ArrayList<String>();

		/**
		 * @param pLookups the configured references' lookups (resolves a code the reference knows, for the target-only case); may be {@code Lookups.empty()}
		 */
		public Session(final List<Request> pRequests, final List<Reference> pReferences, final CleaningSteps.Lookups pLookups)
		{
			for (final Request request : pRequests)
			{
				this.byName.put(request.attributeName, request);
			}
			if (pReferences != null)
			{
				for (final Reference reference : pReferences)
				{
					this.references.put(reference.kind, reference);
				}
			}
			this.lookups = pLookups == null ? CleaningSteps.Lookups.empty() : pLookups;
		}

		/** The valid proposals, one per attribute (a later valid call replaces an earlier one). */
		public List<Proposal> proposals()
		{
			return new ArrayList<Proposal>(this.proposals.values());
		}

		public List<String> notes()
		{
			return this.notes;
		}

		public ToolSet.LocalGroup toolGroup()
		{
			final List<LocalTool> tools = new ArrayList<LocalTool>();
			tools.add(new LocalTool(CodeTranscodingPass.TOOL_PROPOSE,
				"Propose the common code list of an attribute whose sources write it differently, and (when the codes are given) the correspondence of every source code.",
				CodeTranscodingPass.PROPOSE_SCHEMA, new LocalTool.Handler()
				{
					@Override
					public LocalTool.Result handle(final JsonNode pArguments)
					{
						return Session.this.propose(pArguments);
					}
				}));
			return new ToolSet.LocalGroup(tools);
		}

		LocalTool.Result propose(final JsonNode pArguments)
		{
			final String attributeName = pArguments.path("attribute").asText(null);
			final Request request = attributeName == null ? null : this.byName.get(attributeName.trim());
			if (request == null)
			{
				return LocalTool.Result.error("'attribute' must be one of the attributes listed in the task, exactly as given: " + this.byName.keySet() + ".");
			}
			final List<String> problems = new ArrayList<String>();
			final List<String> targets = this.readTargets(pArguments.path("targets"), problems);
			String basedOn = "";
			final String referenceKind = pArguments.path("reference").asText("").trim();
			if (!referenceKind.isEmpty())
			{
				final Reference reference = this.references.get(referenceKind);
				if (reference == null)
				{
					problems.add("'reference' " + referenceKind + " is not one of the listed reference lists (" + this.references.keySet() + "): leave it out, or name a listed one.");
				}
				else
				{
					basedOn = referenceKind;
					final Set<String> allowed = new LinkedHashSet<String>(reference.codes);
					for (int i = 0; i < targets.size(); i++)
					{
						if (!allowed.contains(targets.get(i)))
						{
							String canonical = null;
							for (final String code : allowed)
							{
								if (CodeMap.keyOf(code).equals(CodeMap.keyOf(targets.get(i))))
								{
									canonical = code;
									break;
								}
							}
							if (canonical == null)
							{
								problems.add("target '" + targets.get(i) + "' is not a code of the reference " + referenceKind + ": use its codes, or drop 'reference'.");
							}
							else
							{
								targets.set(i, canonical);
							}
						}
					}
				}
			}
			final Map<String, CodeMap> maps = new LinkedHashMap<String, CodeMap>();
			if (problems.isEmpty())
			{
				if (request.sharing == KeyLlmPass.Sharing.VALUES)
				{
					this.mapEverySource(request, targets, basedOn, pArguments.path("mappings"), maps, problems);
				}
				else
				{
					for (final Source source : request.sources)
					{
						maps.put(source.mappingId, CodeTranscodingPass.skeleton(source, targets, basedOn, this.lookups));
					}
					if (pArguments.path("mappings").isArray() && pArguments.path("mappings").size() > 0)
					{
						this.notes.add("the model sent correspondences for " + request.attributeName + " although the codes were not shared: ignored.");
					}
				}
			}
			if (!problems.isEmpty())
			{
				return LocalTool.Result.error(CodeTranscodingPass.problemsText(problems) + " Nothing was recorded for '" + request.attributeName + "': call the tool again with the corrections.");
			}
			for (final Map.Entry<String, CodeMap> entry : maps.entrySet())
			{
				if (entry.getValue().toJson().length() > CodeMap.MAX_JSON_LENGTH)
				{
					return LocalTool.Result.error("The table of one source is too long to be stored (more than " + CodeMap.MAX_JSON_LENGTH + " characters): shorten the target codes.");
				}
			}
			final String rationale = pArguments.path("rationale").asText("").trim();
			final int confidence = Math.max(0, Math.min(100, pArguments.path("confidence").asInt(0)));
			this.proposals.put(request.attributeName, new Proposal(request, targets, basedOn, maps, confidence, rationale.length() > CodeTranscodingPass.RATIONALE_MAX
				? rationale.substring(0, CodeTranscodingPass.RATIONALE_MAX - 3) + "..." : rationale));
			return LocalTool.Result.ok("Recorded for '" + request.attributeName + "': " + targets.size() + " target codes, " + maps.size() + " sources"
				+ (request.sharing == KeyLlmPass.Sharing.VALUES ? "." : "; the correspondences are left to a person (the codes were not shared)."));
		}

		private List<String> readTargets(final JsonNode pNode, final List<String> pProblems)
		{
			final List<String> targets = new ArrayList<String>();
			if (!pNode.isArray() || pNode.size() == 0)
			{
				pProblems.add("'targets' must be a non-empty list of codes.");
				return targets;
			}
			final Set<String> keys = new LinkedHashSet<String>();
			for (final JsonNode node : pNode)
			{
				final String target = node.asText("").trim();
				if (target.isEmpty())
				{
					pProblems.add("'targets' holds an empty code.");
					continue;
				}
				if (target.length() > CodeTranscodingPass.MAX_CODE_LENGTH)
				{
					pProblems.add("a target code is longer than " + CodeTranscodingPass.MAX_CODE_LENGTH + " characters.");
					continue;
				}
				if (!keys.add(CodeMap.keyOf(target)))
				{
					pProblems.add("target '" + target + "' reads the same as another target once case and punctuation are ignored (matching ignores both): give distinct codes.");
					continue;
				}
				targets.add(target);
			}
			if (targets.size() > CodeTranscodingPass.MAX_TARGETS)
			{
				pProblems.add("'targets' holds " + targets.size() + " codes; the most accepted is " + CodeTranscodingPass.MAX_TARGETS + ".");
			}
			return targets;
		}

		private void mapEverySource(final Request pRequest, final List<String> pTargets, final String pBasedOn, final JsonNode pMappings, final Map<String, CodeMap> pOut,
			final List<String> pProblems)
		{
			if (!pMappings.isArray() || pMappings.size() == 0)
			{
				pProblems.add("'mappings' is required: the codes of every source are given in the task, map each of them.");
				return;
			}
			final Map<String, Source> bySource = new LinkedHashMap<String, Source>();
			for (final Source source : pRequest.sources)
			{
				bySource.put(source.label(), source);
			}
			final Map<String, JsonNode> answered = new LinkedHashMap<String, JsonNode>();
			for (final JsonNode entry : pMappings)
			{
				final String label = entry.path("source").asText("").trim();
				if (!bySource.containsKey(label))
				{
					pProblems.add("'mappings' names a source '" + label + "' that is not listed: " + bySource.keySet() + ".");
					continue;
				}
				answered.put(label, entry.path("codes"));
			}
			final Map<String, String> targetByKey = new LinkedHashMap<String, String>();
			for (final String target : pTargets)
			{
				targetByKey.put(CodeMap.keyOf(target), target);
			}
			for (final Source source : pRequest.sources)
			{
				final JsonNode codes = answered.get(source.label());
				if (codes == null || !codes.isArray())
				{
					pProblems.add("source '" + source.label() + "' has no entry in 'mappings'.");
					continue;
				}
				final Map<String, String> toByCodeKey = new LinkedHashMap<String, String>();
				for (final JsonNode pair : codes)
				{
					final String from = pair.path("from").asText("").trim();
					final String to = pair.path("to").asText("").trim();
					if (from.isEmpty())
					{
						continue;
					}
					final String target = targetByKey.get(CodeMap.keyOf(to));
					if (target == null)
					{
						pProblems.add("source '" + source.label() + "': '" + from + "' goes to '" + to + "', which is not in 'targets' - add it to the list or choose a listed target.");
						continue;
					}
					final String key = CodeMap.keyOf(from);
					final String seen = toByCodeKey.get(key);
					if (seen != null && !seen.equals(target))
					{
						pProblems.add("source '" + source.label() + "': '" + from + "' is given two different targets.");
						continue;
					}
					toByCodeKey.put(key, target);
				}
				final Map<String, String> table = new LinkedHashMap<String, String>();
				final Map<String, Long> counts = new LinkedHashMap<String, Long>();
				final List<String> missing = new ArrayList<String>();
				for (final Detectors.ValueCount value : source.values)
				{
					final String code = value.value.trim();
					final String target = toByCodeKey.get(CodeMap.keyOf(code));
					if (target == null)
					{
						missing.add(code);
					}
					table.put(code, target == null ? "" : target);
					counts.put(code, Long.valueOf(value.count));
				}
				if (!missing.isEmpty())
				{
					pProblems.add("source '" + source.label() + "': " + missing.size() + " code(s) of the task have no correspondence: " + missing
						+ ". Every code must be mapped.");
					continue;
				}
				final CodeMap map = new CodeMap(table, pTargets, counts, pBasedOn);
				if (!map.conflicts().isEmpty())
				{
					pProblems.add("source '" + source.label() + "': two of its codes read the same once case and punctuation are ignored and go to different targets.");
					continue;
				}
				pOut.put(source.mappingId, map);
			}
		}
	}

	private static String problemsText(final List<String> pProblems)
	{
		final StringBuilder out = new StringBuilder("Your answer is not valid: ");
		for (int i = 0; i < pProblems.size() && i < CodeTranscodingPass.MAX_ERRORS_SHOWN; i++)
		{
			out.append(i > 0 ? " " : "").append(pProblems.get(i));
		}
		if (pProblems.size() > CodeTranscodingPass.MAX_ERRORS_SHOWN)
		{
			out.append(" (and ").append(pProblems.size() - CodeTranscodingPass.MAX_ERRORS_SHOWN).append(" more).");
		}
		return out.toString();
	}

	/**
	 * The table of a source whose codes the model did not see: every observed code on the left, a target only where it needs no model - the code already
	 * IS a target (same key), or the chosen reference resolves it. Everything else stays empty for a person.
	 */
	static CodeMap skeleton(final Source pSource, final List<String> pTargets, final String pBasedOn, final CleaningSteps.Lookups pLookups)
	{
		final Map<String, String> targetByKey = new LinkedHashMap<String, String>();
		for (final String target : pTargets)
		{
			targetByKey.put(CodeMap.keyOf(target), target);
		}
		final Map<String, String> table = new LinkedHashMap<String, String>();
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount value : pSource.values)
		{
			final String code = value.value.trim();
			String target = targetByKey.get(CodeMap.keyOf(code));
			if (target == null && !pBasedOn.isEmpty())
			{
				final String resolved = pLookups.resolveFlat(pBasedOn, code);
				target = resolved == null ? null : targetByKey.get(CodeMap.keyOf(resolved));
			}
			table.put(code, target == null ? "" : target);
			counts.put(code, Long.valueOf(value.count));
		}
		return new CodeMap(table, pTargets, counts, pBasedOn);
	}

	// ------------------------------------------------------------------------------------------------- the rows

	/**
	 * The {@code MappingStep} rows of a proposal: one per source whose table changes something, measured on the source's values. A source whose codes already are the
	 * targets (an identity table with every code mapped) gets none. A source the table covers completely carries the model's confidence (at most {@link
	 * #MAX_CONFIDENCE}); one it does not carries the share of rows it covers, at most {@code pFlagCap} - under the bulk-accept bar, so the matrix queues it.
	 *
	 * @param pFlagCap the highest confidence of a link that needs a person ({@code NormalisationSuggester.FLAG_CONFIDENCE_CAP}, 59)
	 */
	public static List<Draft> drafts(final Proposal pProposal, final int pFlagCap)
	{
		final List<Draft> out = new ArrayList<Draft>();
		final boolean shared = pProposal.request.sharing == KeyLlmPass.Sharing.VALUES;
		for (final Source source : pProposal.request.sources)
		{
			final CodeMap map = pProposal.maps.get(source.mappingId);
			if (map == null)
			{
				continue;
			}
			final CodeMap.Coverage coverage = map.cover(source.values);
			if (coverage.complete() && map.isIdentity())
			{
				continue; // this source already writes the common list
			}
			final int confidence = coverage.complete() ? Math.min(pProposal.confidence, CodeTranscodingPass.MAX_CONFIDENCE) : Math.min(coverage.percent(), pFlagCap);
			final List<String> examples = new ArrayList<String>();
			for (final Map.Entry<String, String> entry : map.codes.entrySet())
			{
				if (examples.size() >= CodeTranscodingPass.MAX_EXAMPLES)
				{
					break;
				}
				if (!entry.getValue().isEmpty() && !entry.getValue().equals(entry.getKey()))
				{
					final String pair = Shapes.pair(entry.getKey(), entry.getValue());
					if (!examples.contains(pair))
					{
						examples.add(pair);
					}
				}
			}
			out.add(new Draft(source.mappingId, map.toJson(), confidence, noteOf(pProposal, source, map, coverage, shared), pProposal.rationale, coverage.rows, coverage.changed,
				coverage.unresolved, examples, coverage.complete()));
		}
		return out;
	}

	/** The Java-built part of the rationale of one step: counts only (no code); the model's reasons follow it ({@link Draft#tail}). */
	static NoteTexts.Note noteOf(final Proposal pProposal, final Source pSource, final CodeMap pMap, final CodeMap.Coverage pCoverage, final boolean pShared)
	{
		final NoteTexts.Note out = NoteTexts.Note.of(NoteTexts.C_PREFIX);
		out.add(NoteTexts.C_HEAD, Integer.valueOf(pMap.codes.size()), pSource.label(), Long.valueOf(pCoverage.rows), Integer.valueOf(pProposal.targets.size()));
		if (!pProposal.basedOn.isEmpty())
		{
			out.add(NoteTexts.C_BASED_ON, pProposal.basedOn);
		}
		out.add(NoteTexts.DOT);
		if (!pShared)
		{
			out.add(NoteTexts.C_NOT_SHARED, Long.valueOf(pCoverage.resolved), Long.valueOf(pCoverage.rows));
		}
		else if (!pCoverage.complete())
		{
			out.add(NoteTexts.C_UNRESOLVED, Long.valueOf(pCoverage.unresolved), Long.valueOf(pCoverage.rows));
		}
		return out;
	}

	/** True for the rationale of a row this pass wrote. */
	public static boolean isOwnRationale(final String pRationale)
	{
		return pRationale != null && pRationale.startsWith(CodeTranscodingPass.MARKER);
	}

	/** The sources of an alignment as the pass asks about them: the members whose codes can be read, no source of fewer than one code. */
	public static List<Source> sourcesOf(final NormalisationPass.Alignment pAlignment)
	{
		final List<Source> out = new ArrayList<Source>();
		for (final NormalisationPass.Member member : pAlignment.members)
		{
			if (!member.values.isEmpty())
			{
				out.add(new Source(member.mappingId, member.sourceSystem, member.sourceColumn, member.values));
			}
		}
		return out;
	}

	/** A readable label for logs and notes. */
	public static String describe(final Request pRequest)
	{
		return pRequest.entityName + "." + pRequest.attributeName + " (" + pRequest.sources.size() + " sources, " + pRequest.sharing.name().toLowerCase(Locale.ROOT) + ")";
	}
}
