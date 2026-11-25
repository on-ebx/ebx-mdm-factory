package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Proposes ONE derived {@code StagingAttribute} (name {@code "<sourceName>Key"}, {@code derivation}
 * {@code FOLD_KEY}) per text attribute fed by two or more source systems, when folding (stripping accents,
 * then upper-casing - {@link CleaningSteps#foldKey}) would make values from different systems coincide that
 * do not coincide as raw text - PLAN.md 2026-09-21/22: a real demo failed to match on {@code city} because
 * CRM wrote {@code "Paris"}/{@code "Zürich"} and ERP wrote {@code "PARIS"}/{@code "ZURICH"}; upper-casing
 * alone cannot close the gap ({@code "ZÜRICH"} still is not {@code "ZURICH"}), and nothing proposed a step
 * for {@code city} at all because {@link Detectors} only ever sees ONE landing column at a time.
 * <p>
 * <b>Product-owner decision, 2026-09-22 (PLAN.md): the fold no longer rewrites the DISPLAY attribute.</b>
 * This class used to propose {@link CleaningSteps#STRIP_ACCENTS}/{@link CleaningSteps#UPPER} as a
 * {@code MappingStep} PAIR on every mapping of the attribute - {@code ebx.StagingLoader.planRow} runs an
 * accepted pipeline on the raw landing value and stores the RESULT in staging, so an accepted fold turned
 * {@code Zürich} into {@code ZURICH} in the golden record itself: a real loss of data quality for anything
 * displayed (names, cities, addresses), for a need that is purely about MATCHING, not storage. This class now
 * proposes a single {@link AttributeProposal} instead: a technical, derived attribute next to the display
 * one (never a {@code MappingStep}), fed at load time by {@code ebx.StagingLoader.planRow} straight from
 * {@link CleaningSteps#foldKey} - the display attribute is never touched, and a fuzzy matching algorithm can
 * still compare it as-is.
 * <p>
 * <b>Why this cannot live in {@link Detectors}</b>: every {@code propose*} method there measures a SINGLE
 * column's own distinct values against itself (does trimming/collapsing/nulling change THIS column) - it has
 * no notion of a second column feeding the SAME staging attribute, let alone of comparing values ACROSS them.
 * The defect this class detects is only visible by comparing the distinct values of every mapping that feeds
 * one attribute against each other - a genuinely different shape of evidence, not a bigger version of the
 * same one-column measurement. Rather than stretch {@link Detectors}' documented "one landing column at a
 * time" contract to fit, this is a separate, small pure class with its own narrow job: {@code
 * ebx.QualitySuggester} groups a project's accepted mappings by attribute (the same grouping {@code
 * ebx.QualityLlmSuggester.runOneEntity} already does for its own union-evidence fix, 2026-09-21) and hands
 * this class one {@link SystemValues} per mapping.
 * <p>
 * <b>Structural only - no column name, catalog tag, or business vocabulary is read</b>: the gate is "does this
 * attribute have accepted mappings naming two or more distinct {@code ProjectSource.sourceSystem} values" (a
 * measured fact about the PROJECT's own wiring, not a guess about what the attribute means), and the proposal
 * itself is decided purely from the MEASURED before/after coincidence counts, exactly the discipline
 * docs/NORMALISATION.md sec 4 and the 2026-09-21 product-owner review already require of every other detector
 * in this package. This is also why {@code StagingEntity.memberEntities} (a text-justification backfill field,
 * possibly stale or empty for an entity created before it existed - see {@code MemberEntitiesBackfill}) is
 * deliberately NOT read here: an attribute's own accepted mappings are the more direct, always-current source
 * of the same fact.
 * <p>
 * <b>Bounded the same way every other detector in this package is</b>: {@link SystemValues#values} is the same
 * capped, most-frequent-first {@code GROUP BY} sample {@code ebx.QualitySuggester.distinctValues} already
 * builds for {@link Detectors#propose} (capped at {@code QualitySuggester.DEFAULT_MAX_DISTINCT_VALUES}) - this
 * class never issues its own query and never invents a different cap or sampling strategy. {@link
 * SystemValues#sampled} carries the same "more distinct values exist than were read" flag {@link Detectors}
 * already computes from the exact SQL aggregate, so a proposal's own confidence and rationale can say
 * honestly when the verdict rests on a sample.
 * <p>
 * <b>ONE proposal per attribute, never per mapping</b> (2026-09-22, a consequence of the derived-attribute
 * redesign, not merely a smaller version of the old "write to every mapping" rule): a {@code FOLD_KEY}
 * derived attribute is computed once, from the attribute's OWN staging value, whichever mapping fed it on a
 * given row - there is no per-system copy to keep in step, unlike the old {@code MappingStep} pair that had
 * to be written identically to every mapping for the fold to actually close the gap on both sides.
 * {@code ebx.QualitySuggester} writes ONE {@code StagingAttribute} row from the single proposal this class
 * returns; idempotence is its own concern (see that class's own Javadoc), not inherited from a per-mapping
 * write path any more.
 * <p>
 * <b>Measured after each mapping's own ACCEPTED-or-APPLIED {@code MappingStep} pipeline, never on raw landing
 * text</b> (coordinator review, 2026-09-21, a real defect on live data - see {@link #applyAcceptedPipeline}'s
 * own Javadoc for the exact worked example): {@code ebx.QualitySuggester} runs {@link #applyAcceptedPipeline}
 * on each mapping's sampled values BEFORE building the {@link SystemValues} this class compares, using the
 * SAME {@link CleaningSteps#applyPipeline} call {@code ebx.StagingLoader} itself makes for a real row. A
 * mapping whose accepted pipeline already resolves its values to the other system's shape (e.g. an accepted
 * {@code COUNTRY_TO_ISO2}) must show ZERO gain from folding case and accents, not a false one measured on text
 * that no longer exists once that mapping runs for real.
 */
public final class CrossSystemFold
{
	/** {@code StagingAttribute.derivation} value this class proposes - the only one that exists so far (`tools/build_xsd.py`'s {@code DERIVATION} vocabulary). */
	public static final String DERIVATION_FOLD_KEY = "FOLD_KEY";

	/**
	 * Deliberately below {@link Detectors}' mechanical 100 for {@code TRIM}/{@code COLLAPSE_SPACES}: this
	 * verdict rests on comparing SAMPLES of several columns against each other, not on a single column's own
	 * exact-vs-sample distinction alone, so a small residual chance that a larger sample would show a
	 * different coincidence count is priced in even before {@link Detectors#cappedConfidence} applies its own,
	 * separate sampling cap.
	 */
	private static final int BASE_CONFIDENCE = 85;
	private static final int MAX_EXAMPLES = 5;

	private CrossSystemFold()
	{
	}

	// -------------------------------------------------------------------------------------------------------- input

	/**
	 * One mapping's own contribution: which source system it comes from, its (capped, most-frequent-first)
	 * distinct values - AS THE MAPPING'S OWN ACCEPTED-or-APPLIED {@code MappingStep} pipeline already leaves
	 * them, never raw landing text (coordinator review, 2026-09-21 - see {@link #applyAcceptedPipeline}'s own
	 * Javadoc for why measuring on raw values is a real defect, not a simplification) - and whether that sample
	 * is known to be partial.
	 */
	public static final class SystemValues
	{
		/** {@code ProjectSource.sourceSystem} of the mapping's landing table - or a fallback identifier (e.g. the landing table name) when that could not be resolved; never business vocabulary, only used to tell one system's column apart from another's and to count how many distinct systems feed the attribute. */
		public final String systemId;
		/** Same sample {@link Detectors#propose} itself is given - capped, most-frequent-first, never re-queried by this class - already run through the mapping's own accepted pipeline by the caller (see {@link #applyAcceptedPipeline}). */
		public final List<Detectors.ValueCount> values;
		/** {@code true} when the column's true distinct-value count exceeds {@link #values}'s own size - the same flag {@code ebx.QualitySuggester}'s exact aggregates already compute for {@link Detectors#propose}'s own sampling caveat. */
		public final boolean sampled;
		/** How many ACCEPTED-or-APPLIED {@code MappingStep} rows were already run on {@link #values} before this measurement - 0 for a mapping with an empty pipeline. Shown in the rationale so a reviewer knows the numbers reflect the data as it is actually loaded, not raw landing text. */
		public final int acceptedStepsApplied;

		public SystemValues(final String pSystemId, final List<Detectors.ValueCount> pValues, final boolean pSampled, final int pAcceptedStepsApplied)
		{
			this.systemId = pSystemId;
			this.values = pValues;
			this.sampled = pSampled;
			this.acceptedStepsApplied = pAcceptedStepsApplied;
		}

		/** Convenience for a caller (or a test) with no accepted pipeline to report - equivalent to {@code acceptedStepsApplied = 0}. */
		public SystemValues(final String pSystemId, final List<Detectors.ValueCount> pValues, final boolean pSampled)
		{
			this(pSystemId, pValues, pSampled, 0);
		}
	}

	// ------------------------------------------------------------------------------------------------------- output

	/**
	 * One {@code FOLD_KEY} derived-attribute proposal - what {@code ebx.QualitySuggester} needs to write a
	 * single new {@code StagingAttribute} row (name, evidence); everything about WHERE it is written (entity,
	 * {@code derivedFrom}, {@code maxLength} copied from the source attribute, label) is the caller's own
	 * concern, since this pure class never reads a {@code StagingAttribute} row directly.
	 */
	public static final class AttributeProposal
	{
		/** {@code "<sourceName>Key"} - the suggested {@code StagingAttribute.name} of the derived attribute; never checked for collision here (see the caller's own idempotence/uniqueness handling). */
		public final String suggestedName;
		public final int confidence;
		public final String rationale;
		public final long sharedBefore;
		public final long sharedAfter;
		/** "before -&gt; after" SHAPES, never real values - see {@link #buildExamples}'s own Javadoc. */
		public final List<String> examples;

		public AttributeProposal(final String pSuggestedName, final int pConfidence, final String pRationale, final long pSharedBefore, final long pSharedAfter,
			final List<String> pExamples)
		{
			this.suggestedName = pSuggestedName;
			this.confidence = pConfidence;
			this.rationale = pRationale;
			this.sharedBefore = pSharedBefore;
			this.sharedAfter = pSharedAfter;
			this.examples = pExamples;
		}
	}

	// ------------------------------------------------------------------------------------------------------- rules

	/**
	 * @param pByMapping one entry per accepted mapping of ONE staging attribute (whatever its source system) -
	 * never deduplicated by system beforehand; this method itself counts the DISTINCT {@link
	 * SystemValues#systemId} values to decide whether the structural gate ("fed by two or more systems") is
	 * met.
	 * @param pColumn the attribute's own type/name context - same shape {@link Detectors#propose} takes;
	 * {@link Detectors.ColumnContext#inferredType} gates text-like only (the same convention every detector in
	 * this package uses), and {@link Detectors.ColumnContext#stagingAttributeName} names the derived attribute
	 * ({@code "<name>Key"}).
	 * @return zero or one {@link AttributeProposal} - a {@code List} only for symmetry with every other
	 * detector in this package (0 or 1 element, never more: there is exactly one attribute in play).
	 */
	public static List<AttributeProposal> propose(final List<SystemValues> pByMapping, final Detectors.ColumnContext pColumn)
	{
		final List<AttributeProposal> out = new ArrayList<AttributeProposal>();
		if (pByMapping == null || pByMapping.size() < 2 || pColumn == null || !Detectors.isTextLike(pColumn.inferredType))
		{
			return out;
		}
		final Set<String> distinctSystems = new LinkedHashSet<String>();
		boolean anySampled = false;
		for (final SystemValues system : pByMapping)
		{
			if (system.systemId != null && !system.systemId.trim().isEmpty())
			{
				distinctSystems.add(system.systemId.trim());
			}
			anySampled = anySampled || system.sampled;
		}
		if (distinctSystems.size() < 2)
		{
			return out; // structural gate: not fed by two (or more) distinct source systems - nothing to reconcile
		}

		// keyed by the DISTINCT SOURCE SYSTEM a value was seen under, never by mapping index (coordinator review,
		// 2026-09-21: two mappings of the SAME system sharing a value must never count as "shared across
		// systems" - a blank/unresolved systemId gets a per-mapping sentinel so it can never spuriously match
		// another equally-blank one)
		final Map<String, Set<String>> beforeGroups = new LinkedHashMap<String, Set<String>>();
		final Map<String, Set<String>> afterGroups = new LinkedHashMap<String, Set<String>>();
		final Map<String, String> firstRawOfFolded = new LinkedHashMap<String, String>();
		long totalValues = 0;
		for (int mappingIndex = 0; mappingIndex < pByMapping.size(); mappingIndex++)
		{
			final SystemValues system = pByMapping.get(mappingIndex);
			final String systemKey = system.systemId == null || system.systemId.trim().isEmpty() ? "?mapping" + mappingIndex : system.systemId.trim();
			if (system.values == null)
			{
				continue;
			}
			for (final Detectors.ValueCount vc : system.values)
			{
				if (vc.value == null)
				{
					continue;
				}
				final String raw = vc.value.trim();
				if (raw.isEmpty())
				{
					continue;
				}
				final String folded = CrossSystemFold.fold(raw);
				if (folded == null)
				{
					continue; // folds down to nothing (e.g. a string of only combining marks) - not a usable key, never grouped under a spurious shared null
				}
				totalValues++;
				CrossSystemFold.addToGroup(beforeGroups, raw, systemKey);
				CrossSystemFold.addToGroup(afterGroups, folded, systemKey);
				if (!firstRawOfFolded.containsKey(folded))
				{
					firstRawOfFolded.put(folded, raw);
				}
			}
		}

		final long sharedBefore = CrossSystemFold.countShared(beforeGroups);
		final long sharedAfter = CrossSystemFold.countShared(afterGroups);
		if (sharedAfter <= sharedBefore)
		{
			return out; // the fold would not make anything coincide that does not already - nothing to propose
		}

		final List<String> examples = CrossSystemFold.buildExamples(beforeGroups, afterGroups, firstRawOfFolded);
		final String suffix = anySampled ? " Measured on a sample of each system's most frequent distinct values, capped the same way as every other cleaning-step proposal." : "";
		final String rationale = "Fed by " + distinctSystems.size() + " source systems; measured after each mapping's own accepted cleaning pipeline ("
			+ CrossSystemFold.appliedStepsClause(pByMapping) + "). A matching key (no accents, no case) makes values coincide across systems that do not "
			+ "coincide otherwise (shared across systems: " + sharedBefore + " before, " + sharedAfter + " after folding case and accents)." + suffix;
		final int confidence = Detectors.cappedConfidence(CrossSystemFold.BASE_CONFIDENCE, anySampled);
		final String suggestedName = (pColumn.stagingAttributeName == null ? "value" : pColumn.stagingAttributeName) + "Key";

		out.add(new AttributeProposal(suggestedName, confidence, rationale, sharedBefore, sharedAfter, examples));
		return out;
	}

	/** {@link CleaningSteps#foldKey} - the exact fold {@code ebx.StagingLoader} itself applies once the derived attribute is accepted. */
	static String fold(final String pRaw)
	{
		return CleaningSteps.foldKey(pRaw);
	}

	private static void addToGroup(final Map<String, Set<String>> pGroups, final String pKey, final String pSystemKey)
	{
		Set<String> systems = pGroups.get(pKey);
		if (systems == null)
		{
			systems = new LinkedHashSet<String>();
			pGroups.put(pKey, systems);
		}
		systems.add(pSystemKey);
	}

	/** How many keys of {@code pGroups} are backed by two or more DIFFERENT SOURCE SYSTEMS - a value seen only within one system's own mapping(s) is not "shared across systems", however many mappings or rows of that ONE system carry it. */
	private static long countShared(final Map<String, Set<String>> pGroups)
	{
		long shared = 0;
		for (final Set<String> systems : pGroups.values())
		{
			if (systems.size() >= 2)
			{
				shared++;
			}
		}
		return shared;
	}

	/** Up to {@link #MAX_EXAMPLES} "before -&gt; after" SHAPES (never real values - the same allow-list discipline {@link Detectors#addExample} already applies; {@code STRIP_ACCENTS} is not in {@code REAL_VALUE_KINDS} - reused here purely as a safe-shape marker, this class no longer proposes it as a {@code MappingStep} kind) for a folded key that newly became shared - i.e. one whose own raw form was NOT already shared before folding, so the example genuinely illustrates what this proposal would fix. */
	private static List<String> buildExamples(final Map<String, Set<String>> pBeforeGroups, final Map<String, Set<String>> pAfterGroups,
		final Map<String, String> pFirstRawOfFolded)
	{
		final List<String> examples = new ArrayList<String>();
		for (final Map.Entry<String, Set<String>> entry : pAfterGroups.entrySet())
		{
			if (examples.size() >= CrossSystemFold.MAX_EXAMPLES)
			{
				break;
			}
			final String folded = entry.getKey();
			if (entry.getValue().size() < 2)
			{
				continue;
			}
			final Set<String> beforeSystems = pBeforeGroups.get(folded);
			if (beforeSystems != null && beforeSystems.size() >= 2)
			{
				continue; // already coincided before folding: not an example of what THIS proposal fixes
			}
			final String raw = pFirstRawOfFolded.get(folded);
			if (raw != null)
			{
				Detectors.addExample(examples, CleaningSteps.STRIP_ACCENTS, raw, folded);
			}
		}
		return examples;
	}

	/** {@code "CRM: 1 accepted step(s); ERP: 0 accepted step(s)"} - one clause per mapping, in {@code pByMapping}'s own order, naming its system and how many accepted steps already ran on its values (see {@link #applyAcceptedPipeline}). */
	private static String appliedStepsClause(final List<SystemValues> pByMapping)
	{
		final StringBuilder out = new StringBuilder();
		for (final SystemValues system : pByMapping)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			final String systemLabel = system.systemId == null || system.systemId.trim().isEmpty() ? "?" : system.systemId.trim();
			out.append(systemLabel).append(": ").append(system.acceptedStepsApplied).append(" accepted step(s)");
		}
		return out.toString();
	}

	// ----------------------------------------------------------------------------------------------- accepted pipeline

	/**
	 * Runs {@code pRawValues} (one mapping's own sampled distinct RAW landing values) through {@code
	 * pAcceptedSteps} (that mapping's ACCEPTED-or-APPLIED {@code MappingStep} pipeline, in {@code position}
	 * order) using {@link CleaningSteps#applyPipeline} - the EXACT SAME call {@code ebx.StagingLoader.planRow}
	 * makes for a real row (its own "task D" hook) - so the before/after measurement in {@link #propose}
	 * reflects the data as it is ACTUALLY loaded, never raw text a reviewer already normalised away.
	 * <p>
	 * <b>Why this matters (coordinator review, 2026-09-21, a real defect on live data)</b>: {@code
	 * Company.country} is fed by CRM ({@code "Fr"}/{@code "France"}/{@code "Suisse"}) and ERP ({@code
	 * "FR"}/{@code "CH"}), and BOTH mappings already carry an ACCEPTED step that resolves them to the same ISO
	 * codes ({@code COUNTRY_TO_ISO2} on one, {@code TRANSCODE_REFERENCE} on the other) - `FR`/`FR`, `CH`/`CH`.
	 * Measured on RAW values, folding case and accents still looks like a "gain" ({@code "Fr"} folds to {@code
	 * "FR"}, which happens to already match ERP's raw {@code "FR"}) even though the real gap was already closed
	 * by the accepted pipeline - proposing a {@code FOLD_KEY} derived attribute in that state is noise: it would
	 * add a technical field that never disambiguates anything a reviewer could not already tell from the plain
	 * (already-transcoded) value, and the rationale's own numbers would misdescribe the data as actually loaded.
	 * <p>
	 * A raw value the pipeline turns {@code null} (an accepted {@code EMPTY_TO_NULL} matching it, say) drops
	 * out entirely - it never reaches staging as a value to fold. Values that collapse onto the same pipeline
	 * output (both `"Fr"` and `"France"` resolving to `"FR"` via an accepted {@code COUNTRY_TO_ISO2}) have
	 * their counts SUMMED, so the result is still one entry per DISTINCT post-pipeline value.
	 * <p>
	 * <b>Never call this with a pipeline {@link #hasUnsupportedScopedStep} answers {@code true} for</b> - a
	 * scoped {@code TRANSCODE_REFERENCE} needs the row's own scope value (e.g. its own country, paired with a
	 * legal-form abbreviation), which a plain single-column distinct-value sample does not carry; passing
	 * {@code null} as the scope value the way this method does would silently under-resolve every scoped row
	 * (never a real error - {@link CleaningSteps#applyStep}'s own "never throws on data" rule - just a quietly
	 * wrong answer). {@code ebx.QualitySuggester} checks {@link #hasUnsupportedScopedStep} first and skips the
	 * whole attribute instead of ever reaching this method in that case.
	 *
	 * @param pAcceptedSteps empty or {@code null}: pure pass-through, returns {@code pRawValues} unchanged (the same convention {@link CleaningSteps#applyPipeline} itself uses for an empty pipeline).
	 */
	public static List<Detectors.ValueCount> applyAcceptedPipeline(final List<Detectors.ValueCount> pRawValues, final List<CleaningSteps.Step> pAcceptedSteps,
		final CleaningSteps.Lookups pLookups)
	{
		if (pAcceptedSteps == null || pAcceptedSteps.isEmpty() || pRawValues == null)
		{
			return pRawValues;
		}
		final Map<String, Long> counts = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount vc : pRawValues)
		{
			if (vc.value == null)
			{
				continue;
			}
			final CleaningSteps.Result result = CleaningSteps.applyPipeline(pAcceptedSteps, vc.value, null, pLookups);
			if (result.value == null)
			{
				continue; // an accepted step (e.g. EMPTY_TO_NULL) matched this value: it never reaches staging
			}
			final Long existing = counts.get(result.value);
			counts.put(result.value, Long.valueOf((existing == null ? 0L : existing.longValue()) + vc.count));
		}
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : counts.entrySet())
		{
			out.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
		}
		return out;
	}

	/**
	 * {@code true} when {@code pSteps} contains a SCOPED {@code TRANSCODE_REFERENCE} (its argument names a
	 * {@code scopeField}) - the one {@code MappingStep} kind this pass cannot evaluate offline: the row-scope
	 * value it needs (e.g. the row's own country, paired with a legal-form abbreviation) only exists on the
	 * real landing row, never in a plain single-column distinct-value sample (see {@link
	 * #applyAcceptedPipeline}'s own Javadoc). Reuses {@link CleaningSteps#scopeFieldOf} - the SAME finder {@code
	 * ebx.StagingLoader} itself calls to know which landing field to read a scope value from - rather than
	 * re-reading the argument JSON a second time. An UNSCOPED {@code TRANSCODE_REFERENCE}, {@code
	 * COUNTRY_TO_ISO2} and {@code CURRENCY_TO_ISO} are all fully evaluable by {@link #applyAcceptedPipeline}
	 * (its {@code Lookups} come from {@code ebx.QualitySuggester.buildLookups}, the exact same reference tables
	 * the loader itself resolves against) - only the SCOPED case needs a row-scope value this pass does not
	 * have.
	 */
	public static boolean hasUnsupportedScopedStep(final List<CleaningSteps.Step> pSteps)
	{
		return CleaningSteps.scopeFieldOf(pSteps) != null;
	}
}
