package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.quality.CaseOrderRule;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.CodeMap;
import com.on.ebx.factory.quality.Detectors;
import com.on.ebx.factory.quality.ScanGuard;
import com.on.ebx.factory.quality.Shapes;

/**
 * Staging normalisation lot D (2026-10-17): the deterministic normalisation pass. From the value profile of every
 * landing column that feeds a staging attribute ({@link NormalisationProfiler}) it proposes the catalogue's cleaning
 * steps ({@code MappingStep.kind}: {@code TRIM}, {@code COLLAPSE_SPACES}, {@code EMPTY_TO_NULL}, {@code STRIP_CHARS},
 * {@code UPPER}/{@code LOWER}/{@code TITLE_CASE}, {@code COUNTRY_TO_ISO2}, {@code CURRENCY_TO_ISO}, {@code
 * TRANSCODE_REFERENCE}), each one MEASURED on the values (rows seen, rows changed, rows unresolved, the shapes of a
 * few before/after pairs) exactly as {@code quality.Detectors} does. Pure: no EBX class; {@code
 * ebx.NormalisationSuggester} reads the data, calls {@link #propose} and writes the rows (origin {@code RULE}).
 * <p>
 * <b>Domain-free</b> (CLAUDE.md sec 15: suggestions are language-model first, the module never hard-codes a business
 * domain): no rule below reads a column or attribute name. Everything is decided from the values and from the
 * structure of the proposal (which sources feed the same attribute).
 * <ul>
 * <li><b>White space and placeholders</b>: {@code quality.Detectors#propose} (trim, collapse, empty-ish to null) - the
 * single-column rules the quality pass already owns, called here so they exist before the language-model passes.</li>
 * <li><b>Phone-like column</b> ({@link NormalisationProfile#isPhoneLike}): {@code STRIP_CHARS} of the separators found
 * (space, dot, dash, parentheses, slash, non-breaking space) and <b>never the {@code +}</b> - measured 2026-10-17: a
 * {@code digits()} turned {@code +33 6 46 86 05 23} into a number that no longer says its country. The E.164
 * normalisation stays the job of the computed field ({@code Fasttrack.lib.phone.toE164}), which reads the kept
 * {@code +}.</li>
 * <li><b>Letter case across sources</b>: for an attribute fed by two sources or more, the case most rows use becomes
 * the common one ({@code UPPER}/{@code LOWER}/{@code TITLE_CASE}) and the mappings that deviate get the step, so the
 * stored values agree (one source in capitals, the others in title case: the capitals source gets {@code
 * TITLE_CASE}). Never applied in front of a pipeline whose regex reads letter case ({@link CaseOrderRule}), never to a
 * title-case source (a {@code van der Berg} is not damaged), never together with a reference transcoding.</li>
 * <li><b>Reference transcoding</b>: only when a configured reference ({@code ReferenceBinding}, passed in as {@code
 * CleaningSteps.Lookups}) resolves at least 95 % of the column's rows and would change some - the step is then
 * {@code COUNTRY_TO_ISO2}/{@code CURRENCY_TO_ISO}/{@code TRANSCODE_REFERENCE}. No reference, no step: a mapping of
 * codes is never invented.</li>
 * <li><b>Codes to align</b>: the short lists of repeated codes of two sources that feed the same attribute and do not
 * line up (each has codes the other lacks and fewer than half of the codes are shared, after the steps above) are reported as an {@link Alignment} for a person -
 * never mapped.</li>
 * </ul>
 * Every step's rationale starts with {@link #MARKER} so {@code QualitySuggester}'s withdrawal sweep recognises a row
 * of this pass (an {@code UPPER}/{@code LOWER}/{@code STRIP_CHARS} row of origin {@code RULE} is otherwise swept as the
 * leftover of a removed heuristic).
 */
public final class NormalisationPass
{
	/** Start of the rationale of every row this pass writes. */
	public static final String MARKER = "Normalisation: ";
	private static final int MAX_EXAMPLES = 5;
	/** A reference resolves a column when it covers at least this share (percent) of its rows. */
	static final int REFERENCE_COVERAGE_PERCENT = 95;
	private static final int REFERENCE_MAX_DISTINCT = 400;
	private static final int SAMPLED_MAX_CONFIDENCE = 80;

	/** The kinds this pass may write, besides the three {@code quality.Detectors} owns. */
	public static final Set<String> PROPOSABLE_KINDS = Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList(CleaningSteps.TRIM,
		CleaningSteps.COLLAPSE_SPACES, CleaningSteps.EMPTY_TO_NULL, CleaningSteps.STRIP_CHARS, CleaningSteps.UPPER, CleaningSteps.LOWER, CleaningSteps.TITLE_CASE,
		CleaningSteps.COUNTRY_TO_ISO2, CleaningSteps.CURRENCY_TO_ISO, CleaningSteps.TRANSCODE_REFERENCE)));

	private NormalisationPass()
	{
	}

	/** True for the rationale of a row this pass wrote. */
	public static boolean isOwnRationale(final String pRationale)
	{
		return pRationale != null && pRationale.startsWith(NormalisationPass.MARKER);
	}

	// -------------------------------------------------------------------------------------------------------- input

	/** One mapped landing column, with what the pass needs of it. */
	public static final class Column
	{
		public final String mappingId;
		public final String attributeId;
		public final String attributeName;
		public final String sourceColumn;
		public final String sourceSystem;
		public final String dataType;
		/** The mapping's own pipeline (resolved), or empty. */
		public final String pipeline;
		public final NormalisationProfile profile;
		public final List<Detectors.ValueCount> values;
		public final long exactDistinct;
		/** Kinds of the mapping's non-rejected steps already there, whatever their origin. */
		public final Set<String> existingKinds;
		/** Codes transcoding lot H3 (2026-10-17): the code table of the mapping's non-rejected {@code TRANSCODE_REFERENCE} step that carries its own ({@link CodeMap}), or null. */
		public final CodeMap codeMap;
		/** The business object the attribute belongs to (the code-list proposal is asked per entity); may be empty. */
		public final String entityName;

		public Column(final String pMappingId, final String pAttributeId, final String pAttributeName, final String pSourceColumn, final String pSourceSystem,
			final String pDataType, final String pPipeline, final NormalisationProfile pProfile, final List<Detectors.ValueCount> pValues, final long pExactDistinct,
			final Set<String> pExistingKinds)
		{
			this(pMappingId, pAttributeId, pAttributeName, pSourceColumn, pSourceSystem, pDataType, pPipeline, pProfile, pValues, pExactDistinct, pExistingKinds, null, "");
		}

		public Column(final String pMappingId, final String pAttributeId, final String pAttributeName, final String pSourceColumn, final String pSourceSystem,
			final String pDataType, final String pPipeline, final NormalisationProfile pProfile, final List<Detectors.ValueCount> pValues, final long pExactDistinct,
			final Set<String> pExistingKinds, final CodeMap pCodeMap, final String pEntityName)
		{
			this.codeMap = pCodeMap;
			this.entityName = pEntityName == null ? "" : pEntityName;
			this.mappingId = pMappingId;
			this.attributeId = pAttributeId;
			this.attributeName = pAttributeName;
			this.sourceColumn = pSourceColumn;
			this.sourceSystem = pSourceSystem;
			this.dataType = pDataType;
			this.pipeline = pPipeline == null ? "" : pPipeline;
			this.profile = pProfile;
			this.values = pValues == null ? new ArrayList<Detectors.ValueCount>() : pValues;
			this.exactDistinct = pExactDistinct;
			this.existingKinds = pExistingKinds == null ? new LinkedHashSet<String>() : pExistingKinds;
		}

		boolean isText()
		{
			final String type = this.dataType == null ? "" : this.dataType.trim().toUpperCase(Locale.ROOT);
			return type.isEmpty() || "STRING".equals(type);
		}

		boolean sampled()
		{
			return this.profile.sampled;
		}
	}

	// ------------------------------------------------------------------------------------------------------- output

	/** One step to write on one mapping. */
	public static final class Step
	{
		public final String mappingId;
		public final String kind;
		public final String argument;
		public final int confidence;
		/** The rationale in English ({@link #MARKER} first). */
		public final String rationale;
		/** Staging last points (2026-10-17): the same sentence as a coded note - {@link #storedRationale} is what is written. */
		public final NoteTexts.Note note;
		public final long valuesSeen;
		public final long valuesChanged;
		public final long valuesUnresolved;
		/** Shapes of before/after pairs, never values. */
		public final List<String> examples;

		Step(final String pMappingId, final String pKind, final String pArgument, final int pConfidence, final String pRationale, final long pSeen,
			final long pChanged, final long pUnresolved, final List<String> pExamples)
		{
			this(pMappingId, pKind, pArgument, pConfidence, pRationale, null, pSeen, pChanged, pUnresolved, pExamples);
		}

		Step(final String pMappingId, final String pKind, final String pArgument, final int pConfidence, final NoteTexts.Note pNote, final long pSeen,
			final long pChanged, final long pUnresolved, final List<String> pExamples)
		{
			this(pMappingId, pKind, pArgument, pConfidence, pNote.english(), pNote, pSeen, pChanged, pUnresolved, pExamples);
		}

		private Step(final String pMappingId, final String pKind, final String pArgument, final int pConfidence, final String pRationale, final NoteTexts.Note pNote,
			final long pSeen, final long pChanged, final long pUnresolved, final List<String> pExamples)
		{
			this.note = pNote;
			this.mappingId = pMappingId;
			this.kind = pKind;
			this.argument = pArgument;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.valuesSeen = pSeen;
			this.valuesChanged = pChanged;
			this.valuesUnresolved = pUnresolved;
			this.examples = pExamples;
		}

		/** The rationale as it is written: the English, and the marker of its note, within {@code pMax} characters. */
		public String storedRationale(final int pMax)
		{
			return this.note == null ? com.on.ebx.factory.landing.TextLimits.truncate(this.rationale, pMax) : this.note.stored(pMax);
		}
	}

	/** Short lists of codes of several sources that feed one attribute and do not line up: for a person, never mapped. */
	public static final class Alignment
	{
		public final String attributeId;
		public final List<String> mappingIds;
		/** One English sentence with counts only, no code. */
		public final String text;
		/** Staging last points (2026-10-17): the same sentence as a coded note. */
		public final NoteTexts.Note note;
		/** Codes transcoding lot H3 (2026-10-17): the sources that feed the attribute with a short code list, each with its codes - what the code-list proposal ({@code ebx.CodeTranscodingSuggester}) asks the language model about. */
		public final List<Member> members;
		public final String attributeName;
		public final String entityName;

		Alignment(final String pAttributeId, final List<String> pMappingIds, final NoteTexts.Note pNote, final List<Member> pMembers, final String pAttributeName,
			final String pEntityName)
		{
			this.attributeId = pAttributeId;
			this.mappingIds = pMappingIds;
			this.note = pNote;
			this.text = pNote.english();
			this.members = pMembers;
			this.attributeName = pAttributeName;
			this.entityName = pEntityName;
		}

		/** True when a member already has a code table of its own: the proposal leaves the attribute alone. */
		public boolean hasCodeTable()
		{
			for (final Member member : this.members)
			{
				if (member.hasCodeTable)
				{
					return true;
				}
			}
			return false;
		}
	}

	/** One mapping of an {@link Alignment}: a source column that feeds the attribute with a short list of repeated codes. */
	public static final class Member
	{
		public final String mappingId;
		public final String sourceSystem;
		public final String sourceColumn;
		/** The distinct codes with their rows, after this pass's own steps (trim, case...) and before any code table. */
		public final List<Detectors.ValueCount> values;
		/** Whether the mapping already has a non-rejected step with a code table of its own. */
		public final boolean hasCodeTable;

		Member(final String pMappingId, final String pSourceSystem, final String pSourceColumn, final List<Detectors.ValueCount> pValues, final boolean pHasCodeTable)
		{
			this.mappingId = pMappingId;
			this.sourceSystem = pSourceSystem;
			this.sourceColumn = pSourceColumn;
			this.values = pValues;
			this.hasCodeTable = pHasCodeTable;
		}
	}

	public static final class Result
	{
		public final List<Step> steps = new ArrayList<Step>();
		public final List<Alignment> alignments = new ArrayList<Alignment>();
		public final List<String> notes = new ArrayList<String>();

		public List<Step> stepsOf(final String pMappingId)
		{
			final List<Step> out = new ArrayList<Step>();
			for (final Step step : this.steps)
			{
				if (step.mappingId.equals(pMappingId))
				{
					out.add(step);
				}
			}
			return out;
		}
	}

	// ----------------------------------------------------------------------------------------------------- propose

	/**
	 * @param pAllColumns every mapped column of the project in scope (several mappings may share a landing column)
	 * @param pLookups the project's reference lookups ({@code CleaningSteps.Lookups#empty()} when none)
	 */
	public static Result propose(final List<Column> pAllColumns, final CleaningSteps.Lookups pLookups)
	{
		final Result result = new Result();
		final Map<String, List<Detectors.ValueCount>> working = new LinkedHashMap<String, List<Detectors.ValueCount>>();
		final Set<String> transcoded = new LinkedHashSet<String>();
		// lot G2 (2026-10-17): a mapping whose pipeline does not read its column (constant(HOME)) has nothing for a cleaning step to clean: its landing column
		// is a placeholder, and the values the profile saw are not the ones the staging will hold (a TITLE_CASE was written on constant(HOME), measured live)
		final List<Column> columns = new ArrayList<Column>();
		for (final Column column : pAllColumns)
		{
			if (NormalisationPass.readsColumn(column.pipeline, column.sourceColumn))
			{
				columns.add(column);
			}
		}
		for (final Column column : columns)
		{
			if (column.profile == null || column.profile.filled == 0L)
			{
				continue;
			}
			working.put(column.mappingId, NormalisationPass.singleColumn(column, pLookups == null ? CleaningSteps.Lookups.empty() : pLookups, result, transcoded));
		}
		// codes transcoding lot H3: the codes handed to the language model are the column's values as the source writes them (trimmed, placeholders gone), BEFORE the case
		// alignment - a case step is the deterministic pass's own business, and a table matched without case would otherwise propose to rewrite a source that already writes the list
		final Map<String, List<Detectors.ValueCount>> beforeCase = new LinkedHashMap<String, List<Detectors.ValueCount>>(working);
		NormalisationPass.alignCase(columns, working, transcoded, result);
		NormalisationPass.alignCodes(columns, working, beforeCase, result);
		return result;
	}

	/** Pipeline steps whose output does not come from the column's value: a fixed text, the clock, the run. */
	private static final Set<String> INPUT_FREE_STEPS = Collections.unmodifiableSet(new LinkedHashSet<String>(java.util.Arrays.asList("constant", "now", "runid")));

	/**
	 * Lot G2 (2026-10-17): whether the values a mapping stores come from its landing column - false for a pipeline with a {@code constant}, {@code now} or
	 * {@code runid} step (whatever the column holds is thrown away: {@code constant(HOME)} on a column of identifiers), for a {@code golden} /
	 * {@code mdmstate} step (it reads the record itself) and for a {@code concat} that does not name the column (it reads other paths). A blank pipeline - a plain or
	 * type-driven mapping - reads its column. Structural, never a name: the only text compared is the mapping's own source column.
	 *
	 * @param pPipeline the mapping's declared pipeline, or empty
	 * @param pSourceColumn the landing column the mapping reads; may be empty (then a {@code concat} is not judged by it)
	 */
	public static boolean readsColumn(final String pPipeline, final String pSourceColumn)
	{
		if (pPipeline == null || pPipeline.trim().isEmpty())
		{
			return true;
		}
		for (final com.on.ebx.factory.transfer.TransformationCatalogue.Step step : com.on.ebx.factory.transfer.TransformationCatalogue.parsePipeline(pPipeline))
		{
			final String name = step.name == null ? "" : step.name.trim().toLowerCase(Locale.ROOT);
			if (NormalisationPass.INPUT_FREE_STEPS.contains(name) || "golden".equals(name) || "mdmstate".equals(name))
			{
				return false;
			}
			if (("concat".equals(name) || "joinwidth".equals(name)) && pSourceColumn != null && !pSourceColumn.trim().isEmpty() && !NormalisationPass.namesColumn(step.args, pSourceColumn))
			{
				return false;
			}
		}
		return true;
	}

	/** Whether the comma-separated paths of a {@code concat(...)} include the column (a leading {@code /} or {@code $.} ignored, case ignored). */
	private static boolean namesColumn(final String pArgs, final String pColumn)
	{
		if (pArgs == null)
		{
			return false;
		}
		final String wanted = NormalisationPass.bare(pColumn);
		for (final String path : pArgs.split(","))
		{
			if (NormalisationPass.bare(path).equals(wanted))
			{
				return true;
			}
		}
		return false;
	}

	private static String bare(final String pPath)
	{
		String path = pPath == null ? "" : pPath.trim();
		if (path.startsWith("$."))
		{
			path = path.substring(2);
		}
		while (path.startsWith("/"))
		{
			path = path.substring(1);
		}
		return path.toLowerCase(Locale.ROOT);
	}

	// ------------------------------------------------------------------------------------------------ one column

	private static List<Detectors.ValueCount> singleColumn(final Column pColumn, final CleaningSteps.Lookups pLookups, final Result pResult,
		final Set<String> pTranscoded)
	{
		List<Detectors.ValueCount> working = pColumn.values;
		if (!pColumn.isText())
		{
			return working; // a typed target is the verifier's business: no text step
		}
		final NormalisationProfile profile = pColumn.profile;
		final Detectors.ColumnContext context = new Detectors.ColumnContext(pColumn.sourceColumn, pColumn.attributeName, pColumn.dataType, new ArrayList<String>());
		for (final Detectors.StepProposal proposal : Detectors.propose(pColumn.values, context, pColumn.exactDistinct, pColumn.profile.sampleRows))
		{
			if (pColumn.existingKinds.contains(proposal.kind))
			{
				continue;
			}
			if (proposal.note != null)
			{
				pResult.steps.add(new Step(pColumn.mappingId, proposal.kind, proposal.argument, proposal.confidence, NormalisationPass.prefixed(proposal.note), proposal.valuesSeen,
					proposal.valuesChanged, proposal.valuesUnresolved, proposal.examples));
			}
			else
			{
				pResult.steps.add(new Step(pColumn.mappingId, proposal.kind, proposal.argument, proposal.confidence, NormalisationPass.prefixed(proposal.rationale),
					proposal.valuesSeen, proposal.valuesChanged, proposal.valuesUnresolved, proposal.examples));
			}
			working = NormalisationPass.applyKind(proposal.kind, proposal.argument, working, null).values;
		}

		// phone-like: strip the separators, keep the +
		if (profile.isPhoneLike() && !profile.phoneSeparators.isEmpty() && !pColumn.existingKinds.contains(CleaningSteps.STRIP_CHARS))
		{
			final Applied applied = NormalisationPass.applyKind(CleaningSteps.STRIP_CHARS, profile.phoneSeparators, working, null);
			if (applied.changed > 0L)
			{
				pResult.steps.add(new Step(pColumn.mappingId, CleaningSteps.STRIP_CHARS, profile.phoneSeparators, NormalisationPass.cap(95, pColumn.sampled()),
					NormalisationPass.prefixed(NoteTexts.Note.of(NoteTexts.R_PHONE, Long.valueOf(applied.changed), Long.valueOf(applied.seen))
						.addAll(NormalisationPass.sampledNote(pColumn)).add(NoteTexts.DOT)),
					applied.seen, applied.changed, 0L, applied.examples));
				working = applied.values;
			}
		}

		// a configured reference that resolves the column
		if (!pLookups.flatKinds().isEmpty() && !NormalisationPass.hasTranscode(pColumn.existingKinds) && profile.distinctRead >= 2
			&& profile.distinctRead <= NormalisationPass.REFERENCE_MAX_DISTINCT && profile.mostlyLetters())
		{
			for (final String referenceKind : pLookups.flatKinds())
			{
				final String stepKind = NormalisationPass.stepKindFor(referenceKind);
				final String argument = CleaningSteps.COUNTRY_TO_ISO2.equals(stepKind) || CleaningSteps.CURRENCY_TO_ISO.equals(stepKind) ? null
					: "{\"reference\":\"" + referenceKind + "\"}";
				final Applied applied = NormalisationPass.applyKind(stepKind, argument, working, pLookups);
				if (applied.seen == 0L || applied.changed == 0L)
				{
					continue;
				}
				final long resolved = applied.seen - applied.unresolved;
				if (resolved * 100L < applied.seen * NormalisationPass.REFERENCE_COVERAGE_PERCENT)
				{
					continue;
				}
				final int confidence = NormalisationPass.cap((int) Math.min(100L, (100L * resolved) / applied.seen), pColumn.sampled());
				pResult.steps.add(new Step(pColumn.mappingId, stepKind, argument, confidence,
					NormalisationPass.prefixed(NoteTexts.Note.of(NoteTexts.R_REF_RESOLVES, referenceKind, Long.valueOf(resolved), Long.valueOf(applied.seen), Long.valueOf(applied.changed))
						.addAll(NormalisationPass.sampledNote(pColumn)).add(NoteTexts.DOT)),
					applied.seen, applied.changed, applied.unresolved, applied.examples));
				pTranscoded.add(pColumn.mappingId);
				working = applied.values;
				break;
			}
		}
		return working;
	}

	private static boolean hasTranscode(final Set<String> pKinds)
	{
		return pKinds.contains(CleaningSteps.COUNTRY_TO_ISO2) || pKinds.contains(CleaningSteps.CURRENCY_TO_ISO) || pKinds.contains(CleaningSteps.TRANSCODE_REFERENCE);
	}

	private static String stepKindFor(final String pReferenceKind)
	{
		if ("COUNTRY".equals(pReferenceKind))
		{
			return CleaningSteps.COUNTRY_TO_ISO2;
		}
		if ("CURRENCY".equals(pReferenceKind))
		{
			return CleaningSteps.CURRENCY_TO_ISO;
		}
		return CleaningSteps.TRANSCODE_REFERENCE;
	}

	// ------------------------------------------------------------------------------------------------ case across sources

	private static void alignCase(final List<Column> pColumns, final Map<String, List<Detectors.ValueCount>> pWorking, final Set<String> pTranscoded,
		final Result pResult)
	{
		final Map<String, List<Column>> byAttribute = new LinkedHashMap<String, List<Column>>();
		for (final Column column : pColumns)
		{
			if (column.profile == null || column.profile.filled == 0L || !column.isText() || !column.profile.mostlyLetters())
			{
				continue;
			}
			List<Column> list = byAttribute.get(column.attributeId);
			if (list == null)
			{
				list = new ArrayList<Column>();
				byAttribute.put(column.attributeId, list);
			}
			list.add(column);
		}
		for (final List<Column> columns : byAttribute.values())
		{
			final Set<String> sources = new LinkedHashSet<String>();
			for (final Column column : columns)
			{
				sources.add(column.sourceSystem);
			}
			if (sources.size() < 2)
			{
				continue;
			}
			long upper = 0L;
			long lower = 0L;
			long title = 0L;
			long letters = 0L;
			for (final Column column : columns)
			{
				upper += column.profile.upper;
				lower += column.profile.lower;
				title += column.profile.title;
				letters += column.profile.letterBearing();
			}
			// lot E (2026-10-17, coordinator addition (c)): capitals or lower case would DESTROY the case of a source that writes the field in
			// title or mixed case (ECOM lastName "André" became "ANDRÉ" because the ticket source, in capitals, had more rows). As soon as one
			// source carries it, the common form is the title case, whatever the row counts, and only the all-capitals / all-lower sources move.
			boolean someSourceKeepsItsCase = false;
			for (final Column column : columns)
			{
				if ((column.profile.title + column.profile.mixed) * 2L >= column.profile.letterBearing())
				{
					someSourceKeepsItsCase = true;
				}
			}
			final String target = someSourceKeepsItsCase ? NormalisationProfile.CASE_TITLE : NormalisationPass.targetCase(upper, lower, title, letters);
			if (target == null)
			{
				continue;
			}
			final long onTarget = NormalisationProfile.CASE_UPPER.equals(target) ? upper : NormalisationProfile.CASE_LOWER.equals(target) ? lower : title;
			if (upper + lower + title - onTarget == 0L)
			{
				continue; // nothing deviates (a mixed value is not a deviation: it is left as it is)
			}
			final String stepKind = NormalisationProfile.CASE_UPPER.equals(target) ? CleaningSteps.UPPER
				: NormalisationProfile.CASE_LOWER.equals(target) ? CleaningSteps.LOWER : CleaningSteps.TITLE_CASE;
			for (final Column column : columns)
			{
				if (!NormalisationPass.caseStepApplies(column, target, pTranscoded, stepKind))
				{
					continue;
				}
				final Applied applied = NormalisationPass.applyKind(stepKind, null, pWorking.get(column.mappingId), null);
				if (applied.changed == 0L)
				{
					continue;
				}
				final String targetFragment = NoteTexts.F_CASE + (NormalisationProfile.CASE_UPPER.equals(target) ? "UPPER" : NormalisationProfile.CASE_LOWER.equals(target) ? "LOWER" : "TITLE");
				pResult.steps.add(new Step(column.mappingId, stepKind, null, NormalisationPass.cap(CleaningSteps.TITLE_CASE.equals(stepKind) ? 80 : 90, column.sampled()),
					NormalisationPass.prefixed(NoteTexts.Note.of(NoteTexts.R_CASE_ALIGN, Long.valueOf(upper + lower + title - onTarget), Long.valueOf(upper + lower + title),
						Integer.valueOf(sources.size()), NoteTexts.frag(targetFragment), NoteTexts.frag(someSourceKeepsItsCase ? NoteTexts.F_CASE_WHY_TITLE : NoteTexts.F_CASE_WHY_MOST),
						Long.valueOf(applied.changed), Long.valueOf(applied.seen)).addAll(NormalisationPass.sampledNote(column)).add(NoteTexts.DOT)),
					applied.seen, applied.changed, 0L, applied.examples));
				pWorking.put(column.mappingId, applied.values);
			}
		}
	}

	/** The case most rows use across the sources, or null when none leads clearly (at least half the rows and strictly more than the runner-up). */
	static String targetCase(final long pUpper, final long pLower, final long pTitle, final long pLetters)
	{
		if (pLetters <= 0L)
		{
			return null;
		}
		final long max = Math.max(pUpper, Math.max(pLower, pTitle));
		if (max * 2L < pLetters)
		{
			return null;
		}
		int ties = 0;
		for (final long count : new long[] { pUpper, pLower, pTitle })
		{
			if (count == max)
			{
				ties++;
			}
		}
		if (ties > 1)
		{
			return null;
		}
		return max == pUpper ? NormalisationProfile.CASE_UPPER : max == pLower ? NormalisationProfile.CASE_LOWER : NormalisationProfile.CASE_TITLE;
	}

	private static boolean caseStepApplies(final Column pColumn, final String pTarget, final Set<String> pTranscoded, final String pStepKind)
	{
		final Set<String> kinds = pColumn.existingKinds;
		if (kinds.contains(CleaningSteps.UPPER) || kinds.contains(CleaningSteps.LOWER) || kinds.contains(CleaningSteps.TITLE_CASE)
			|| kinds.contains(CleaningSteps.NORMALISE_IDENTIFIER) || NormalisationPass.hasTranscode(kinds) || pTranscoded.contains(pColumn.mappingId))
		{
			return false;
		}
		if (pColumn.pipeline.length() > 0 && (CaseOrderRule.pipelineRegexReadsCase(pColumn.pipeline) || NormalisationPass.pipelineChangesCase(pColumn.pipeline)))
		{
			return false;
		}
		final NormalisationProfile profile = pColumn.profile;
		if (NormalisationProfile.CASE_TITLE.equals(pTarget))
		{
			final String dominant = profile.dominantCase();
			return NormalisationProfile.CASE_UPPER.equals(dominant) || NormalisationProfile.CASE_LOWER.equals(dominant);
		}
		final long onTarget = profile.caseCount(pTarget);
		return profile.upper + profile.lower + profile.title - onTarget > 0L;
	}

	private static boolean pipelineChangesCase(final String pPipeline)
	{
		for (final com.on.ebx.factory.transfer.TransformationCatalogue.Step step : com.on.ebx.factory.transfer.TransformationCatalogue.parsePipeline(pPipeline))
		{
			if (CaseOrderRule.pipelineStepChangesCase(step.name))
			{
				return true;
			}
		}
		return false;
	}

	// -------------------------------------------------------------------------------------------- codes to align

	private static void alignCodes(final List<Column> pColumns, final Map<String, List<Detectors.ValueCount>> pWorking, final Map<String, List<Detectors.ValueCount>> pBeforeCase,
		final Result pResult)
	{
		final Map<String, List<Column>> byAttribute = new LinkedHashMap<String, List<Column>>();
		for (final Column column : pColumns)
		{
			if (column.profile == null || !column.profile.isCodeList() || !column.isText())
			{
				continue;
			}
			List<Column> list = byAttribute.get(column.attributeId);
			if (list == null)
			{
				list = new ArrayList<Column>();
				byAttribute.put(column.attributeId, list);
			}
			list.add(column);
		}
		for (final Map.Entry<String, List<Column>> entry : byAttribute.entrySet())
		{
			final List<Column> columns = entry.getValue();
			final Map<String, Set<String>> keysBySource = new LinkedHashMap<String, Set<String>>();
			final Map<String, List<String>> mappingsBySource = new LinkedHashMap<String, List<String>>();
			for (final Column column : columns)
			{
				Set<String> keys = keysBySource.get(column.sourceSystem);
				if (keys == null)
				{
					keys = new LinkedHashSet<String>();
					keysBySource.put(column.sourceSystem, keys);
					mappingsBySource.put(column.sourceSystem, new ArrayList<String>());
				}
				mappingsBySource.get(column.sourceSystem).add(column.mappingId);
				final List<Detectors.ValueCount> values = pWorking.containsKey(column.mappingId) ? pWorking.get(column.mappingId) : column.values;
				// codes transcoding lot H3 (2026-10-17): a source that already has a code table is compared by the codes it is mapped TO, so two sources
				// a proposal has lined up are no longer reported as misaligned
				for (final Detectors.ValueCount value : column.codeMap == null ? values : column.codeMap.apply(values))
				{
					final String key = CleaningSteps.normalizeKey(value.value);
					if (!key.isEmpty())
					{
						keys.add(key);
					}
				}
			}
			if (keysBySource.size() < 2)
			{
				continue;
			}
			final List<String> systems = new ArrayList<String>(keysBySource.keySet());
			final Set<String> union = new LinkedHashSet<String>();
			Set<String> common = null;
			for (final Set<String> keys : keysBySource.values())
			{
				union.addAll(keys);
				if (common == null)
				{
					common = new LinkedHashSet<String>(keys);
				}
				else
				{
					common.retainAll(keys);
				}
			}
			// misaligned = some pair of sources where each has a code the other lacks
			boolean misaligned = false;
			for (int i = 0; i < systems.size() && !misaligned; i++)
			{
				for (int j = i + 1; j < systems.size() && !misaligned; j++)
				{
					final Set<String> left = keysBySource.get(systems.get(i));
					final Set<String> right = keysBySource.get(systems.get(j));
					final boolean leftOnly = !right.containsAll(left);
					final boolean rightOnly = !left.containsAll(right);
					// two samples of the same population (first names, cities) overlap widely: only a low overlap is a misalignment
					final Set<String> shared = new LinkedHashSet<String>(left);
					shared.retainAll(right);
					final Set<String> either = new LinkedHashSet<String>(left);
					either.addAll(right);
					misaligned = leftOnly && rightOnly && shared.size() * 2 < either.size();
				}
			}
			if (!misaligned)
			{
				continue;
			}
			final NoteTexts.Note note = new NoteTexts.Note();
			for (int i = 0; i < systems.size(); i++)
			{
				note.add(i == 0 ? NoteTexts.N_ALIGN_FIRST : NoteTexts.N_ALIGN_NEXT, Integer.valueOf(keysBySource.get(systems.get(i)).size()), systems.get(i));
			}
			note.add(NoteTexts.N_ALIGN_TAIL, Integer.valueOf(common.size()), Integer.valueOf(union.size()));
			final List<String> mappingIds = new ArrayList<String>();
			for (final List<String> ids : mappingsBySource.values())
			{
				mappingIds.addAll(ids);
			}
			final List<Member> members = new ArrayList<Member>();
			for (final Column column : columns)
			{
				members.add(new Member(column.mappingId, column.sourceSystem, column.sourceColumn,
					NormalisationPass.mergedByValue(pBeforeCase.containsKey(column.mappingId) ? pBeforeCase.get(column.mappingId) : column.values), column.codeMap != null));
			}
			pResult.alignments.add(new Alignment(entry.getKey(), mappingIds, note, members, columns.get(0).attributeName, columns.get(0).entityName));
		}
	}

	/** The values with the rows of identical spellings added up (a case step may have made two distinct values one), blanks dropped, most frequent first. */
	static List<Detectors.ValueCount> mergedByValue(final List<Detectors.ValueCount> pValues)
	{
		final Map<String, Long> merged = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount value : pValues)
		{
			if (value.value == null || value.value.trim().isEmpty())
			{
				continue;
			}
			final String text = value.value.trim();
			final Long seen = merged.get(text);
			merged.put(text, Long.valueOf((seen == null ? 0L : seen.longValue()) + value.count));
		}
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : merged.entrySet())
		{
			out.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
		}
		Collections.sort(out, new java.util.Comparator<Detectors.ValueCount>()
		{
			@Override
			public int compare(final Detectors.ValueCount pLeft, final Detectors.ValueCount pRight)
			{
				return pLeft.count == pRight.count ? pLeft.value.compareTo(pRight.value) : (pLeft.count > pRight.count ? -1 : 1);
			}
		});
		return out;
	}

	// ------------------------------------------------------------------------------------------------ applying a kind

	private static final class Applied
	{
		final List<Detectors.ValueCount> values = new ArrayList<Detectors.ValueCount>();
		long seen;
		long changed;
		long unresolved;
		final List<String> examples = new ArrayList<String>();
	}

	/** Runs one step on every value; counts the rows seen, changed (the result differs from the value) and unresolved (a lookup found nothing). */
	private static Applied applyKind(final String pKind, final String pArgument, final List<Detectors.ValueCount> pValues, final CleaningSteps.Lookups pLookups)
	{
		final Applied out = new Applied();
		final List<String> seenPairs = new ArrayList<String>();
		for (final Detectors.ValueCount value : pValues)
		{
			if (value.value == null || value.value.trim().isEmpty())
			{
				continue;
			}
			out.seen += value.count;
			final CleaningSteps.Result applied = CleaningSteps.applyStep(pKind, pArgument, value.value, null, pLookups);
			if (applied.unresolved)
			{
				out.unresolved += value.count;
			}
			if (applied.value == null)
			{
				if (!value.value.trim().isEmpty())
				{
					out.changed += value.count;
				}
				continue; // nulled: the next step sees nothing
			}
			if (!applied.value.equals(value.value))
			{
				out.changed += value.count;
				if (out.examples.size() < NormalisationPass.MAX_EXAMPLES)
				{
					final String pair = Shapes.pair(value.value, applied.value);
					if (!seenPairs.contains(pair))
					{
						seenPairs.add(pair);
						out.examples.add(pair);
					}
				}
			}
			out.values.add(new Detectors.ValueCount(applied.value, value.scopeValue, value.count));
		}
		return out;
	}

	private static int cap(final int pConfidence, final boolean pSampled)
	{
		return pSampled ? Math.min(pConfidence, NormalisationPass.SAMPLED_MAX_CONFIDENCE) : pConfidence;
	}

	private static NoteTexts.Note sampledNote(final Column pColumn)
	{
		if (pColumn.profile.sampleRows > 0L)
		{
			return NoteTexts.Note.of(NoteTexts.M_SAMPLE_ROWS, String.format(Locale.ROOT, "%,d", Long.valueOf(pColumn.profile.sampleRows))); // staging polish (2026-10-17): the rows beyond the sample were not read
		}
		return pColumn.sampled() ? NoteTexts.Note.of(NoteTexts.M_SAMPLE_DISTINCT, pColumn.profile.distinctRead, pColumn.profile.exactDistinct)
			: new NoteTexts.Note();
	}

	/** {@link #MARKER} as the first segment of a note. */
	private static NoteTexts.Note prefixed(final NoteTexts.Note pNote)
	{
		return NoteTexts.Note.of(NoteTexts.R_PREFIX).addAll(pNote);
	}

	/** A plain-text rationale (a proposal built without a note) behind {@link #MARKER}. */
	private static String prefixed(final String pText)
	{
		return NormalisationPass.MARKER + pText;
	}
}
