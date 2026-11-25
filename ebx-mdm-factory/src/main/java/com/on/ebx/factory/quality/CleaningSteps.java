package com.on.ebx.factory.quality;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Executes an ordered {@code MappingStep} pipeline on ONE text value, before {@code
 * transfer.Transformations} sees it (docs/NORMALISATION.md sec 3.2: {@code AttributeMapping.transformation}
 * keeps its current, narrow, type-driven job unchanged - this class only ever hands it an already-cleaned
 * string). Pure: no EBX class, never throws on data - an unresolved lookup or an unrecognised kind leaves the
 * value UNCHANGED (counted, not invented; see the class Javadoc of {@link Detectors} for the same rule
 * applied to proposing a step in the first place).
 * <p>
 * <b>Lookup-based kinds ({@link #COUNTRY_TO_ISO2}/{@link #CURRENCY_TO_ISO}/{@link #TRANSCODE_REFERENCE}) take
 * their lookup tables as INPUT</b> ({@link Lookups}, built by the EBX side from a {@code ReferenceBinding}'s
 * reference dataset plus its optional synonym map - see {@code ebx.QualitySuggester}), never fetched by this
 * class itself. Matching is done after {@link #normalizeKey}: case-folding, accent-stripping ({@code
 * java.text.Normalizer}) and punctuation/whitespace removal, so {@code "U.S."}, {@code "US"} and {@code "us"}
 * all key to the same entry, and a two-word alias like {@code "United States"} matches a reference label of
 * the same words regardless of spacing.
 * <p>
 * <b>{@link #TRANSCODE_REFERENCE} may be scoped by a second column</b> (docs/NORMALISATION.md sec 1.7: a
 * legal-form abbreviation is only meaningful paired with the row's own country - {@code SA} is French
 * "Société anonyme" in one country and something else, or nothing, in another). The argument is a small JSON
 * object, {@code {"reference":"LEGAL_FORM","scopeField":"country"}}; {@code scopeField} is metadata for the
 * CALLER (which landing/staging field to read the scope value from) - this class itself only ever receives
 * the scope value already resolved, as {@code pRowScopeValue}, and never reads a row on its own.
 */
public final class CleaningSteps
{
	public static final String TRIM = "TRIM";
	public static final String COLLAPSE_SPACES = "COLLAPSE_SPACES";
	public static final String EMPTY_TO_NULL = "EMPTY_TO_NULL";
	public static final String UPPER = "UPPER";
	public static final String LOWER = "LOWER";
	public static final String STRIP_CHARS = "STRIP_CHARS";
	public static final String STRIP_ACCENTS = "STRIP_ACCENTS";
	public static final String COUNTRY_TO_ISO2 = "COUNTRY_TO_ISO2";
	public static final String CURRENCY_TO_ISO = "CURRENCY_TO_ISO";
	public static final String TRANSCODE_REFERENCE = "TRANSCODE_REFERENCE";
	public static final String NORMALISE_IDENTIFIER = "NORMALISE_IDENTIFIER";
	/** 2026-10-17 (staging normalisation lot D): first letter of every word in capitals, the rest in lower case - the common case the normalisation pass proposes when one source writes a field in capitals and the others in title case. */
	public static final String TITLE_CASE = "TITLE_CASE";

	/** {@link #EMPTY_TO_NULL}'s default sentinel set when the step's own argument is blank - the empty-ish tokens the task names, plus the two the study measured live (NONE/UNKNOWN). Matched case-insensitively, after trim. */
	private static final List<String> DEFAULT_EMPTY_SENTINELS = Collections
		.unmodifiableList(java.util.Arrays.asList("N/A", "NA", "-", "?", "NULL", "NONE", "UNKNOWN", "N.C.", "N.D."));
	/** {@link #NORMALISE_IDENTIFIER}'s default separators to strip when the step's own argument is blank: space, dot, dash - the three the real REGISTRATION_NO data actually carries (docs/NORMALISATION.md sec 1.6). */
	private static final String DEFAULT_IDENTIFIER_SEPARATORS = " .-";
	/**
	 * {@link #stripAccents}'s own table of Latin letters {@code java.text.Normalizer}'s NFD form does NOT
	 * decompose into a base letter plus a combining mark - a plain NFD-then-drop-combining-marks pass (the
	 * technique {@link #normalizeKey} already uses) leaves every one of these untouched, since there is no
	 * combining mark to drop in the first place. Substituted BEFORE decomposition, character by character, so a
	 * genuinely accented letter elsewhere in the same value (e.g. {@code "Straße"}) still goes through the
	 * ordinary NFD path afterwards. Deliberately NOT a transliteration table for non-Latin scripts - see the
	 * class Javadoc's own note on {@link #stripAccents}.
	 */
	private static final Map<Character, String> ACCENT_FOLD_MAP;
	static
	{
		final Map<Character, String> map = new LinkedHashMap<Character, String>();
		map.put(Character.valueOf('ß'), "ss"); // ß
		map.put(Character.valueOf('Æ'), "AE"); // Æ
		map.put(Character.valueOf('æ'), "ae"); // æ
		map.put(Character.valueOf('Œ'), "OE"); // Œ
		map.put(Character.valueOf('œ'), "oe"); // œ
		map.put(Character.valueOf('Ø'), "O"); // Ø
		map.put(Character.valueOf('ø'), "o"); // ø
		map.put(Character.valueOf('Đ'), "D"); // Đ
		map.put(Character.valueOf('đ'), "d"); // đ
		map.put(Character.valueOf('Ł'), "L"); // Ł
		map.put(Character.valueOf('ł'), "l"); // ł
		map.put(Character.valueOf('Þ'), "TH"); // Þ
		map.put(Character.valueOf('þ'), "th"); // þ
		ACCENT_FOLD_MAP = Collections.unmodifiableMap(map);
	}

	private CleaningSteps()
	{
	}

	// -------------------------------------------------------------------------------------------------------- input

	/** One accepted-or-applied {@code MappingStep} row, down to what this class needs of it. */
	public static final class Step
	{
		public final String kind;
		/** Meaning depends on {@link #kind} - see the class Javadoc and {@code MdmFactory.xsd}'s own field documentation. */
		public final String argument;

		public Step(final String pKind, final String pArgument)
		{
			this.kind = pKind;
			this.argument = pArgument;
		}
	}

	/**
	 * The reference lookup tables a {@link #COUNTRY_TO_ISO2}/{@link #CURRENCY_TO_ISO}/{@link
	 * #TRANSCODE_REFERENCE} step resolves against, keyed by the reference kind named in {@code
	 * ReferenceBinding.kind} ({@code "COUNTRY"}, {@code "CURRENCY"}, {@code "LEGAL_FORM"}), every value
	 * already run through {@link #normalizeKey} by the builder ({@code ebx.QualitySuggester}) - this class
	 * never normalizes a lookup TABLE key itself, only the raw value being looked up, so the two sides always
	 * meet on the same normalization.
	 */
	public static final class Lookups
	{
		/** referenceKind -&gt; normalized alias -&gt; code. */
		private final Map<String, Map<String, String>> flat;
		/** referenceKind -&gt; normalized scope code -&gt; normalized alias -&gt; code. */
		private final Map<String, Map<String, Map<String, String>>> scoped;

		public Lookups(final Map<String, Map<String, String>> pFlat, final Map<String, Map<String, Map<String, String>>> pScoped)
		{
			this.flat = pFlat == null ? Collections.<String, Map<String, String>>emptyMap() : pFlat;
			this.scoped = pScoped == null ? Collections.<String, Map<String, Map<String, String>>>emptyMap() : pScoped;
		}

		public static Lookups empty()
		{
			return new Lookups(null, null);
		}

		/** The reference kinds that have a FLAT (unscoped) lookup table - what the normalisation pass tries a low-cardinality column against. */
		public java.util.Set<String> flatKinds()
		{
			return Collections.unmodifiableSet(new java.util.LinkedHashSet<String>(this.flat.keySet()));
		}

		/** Codes transcoding lot H3 (2026-10-17): the distinct codes the flat table of {@code pReferenceKind} resolves to (empty when the kind was never registered) - the target list a proposal may reuse. */
		public java.util.Set<String> codesOf(final String pReferenceKind)
		{
			final Map<String, String> table = this.flat.get(pReferenceKind);
			return table == null ? new java.util.LinkedHashSet<String>() : new java.util.LinkedHashSet<String>(table.values());
		}

		/** @return the code for {@code pRawValue} under the flat table of {@code pReferenceKind}, or {@code null} when unresolved or the kind was never registered. */
		public String resolveFlat(final String pReferenceKind, final String pRawValue)
		{
			final Map<String, String> table = this.flat.get(pReferenceKind);
			return table == null ? null : table.get(CleaningSteps.normalizeKey(pRawValue));
		}

		/** @return the code for {@code pRawValue} under the table of {@code pReferenceKind} SCOPED by {@code pRawScopeValue}, or {@code null} when unresolved, the kind is not registered, or the scope itself has no table. */
		public String resolveScoped(final String pReferenceKind, final String pRawScopeValue, final String pRawValue)
		{
			final Map<String, Map<String, String>> byScope = this.scoped.get(pReferenceKind);
			if (byScope == null || pRawScopeValue == null)
			{
				return null;
			}
			final Map<String, String> table = byScope.get(CleaningSteps.normalizeKey(pRawScopeValue));
			return table == null ? null : table.get(CleaningSteps.normalizeKey(pRawValue));
		}
	}

	// -------------------------------------------------------------------------------------------------------- output

	/** One step's (or one pipeline's) verdict on one raw value: never throws, only ever reports what it did. */
	public static final class Result
	{
		/** {@code null} is legitimate (e.g. {@link #EMPTY_TO_NULL} matched, or the input itself was null/blank). */
		public final String value;
		/** {@code true} when {@link #value} differs from the trimmed input this step (or pipeline) started from. */
		public final boolean changed;
		/** {@code true} when a lookup-based step found no match and therefore left the value unchanged - the value is real data, never invented. */
		public final boolean unresolved;

		Result(final String pValue, final boolean pChanged, final boolean pUnresolved)
		{
			this.value = pValue;
			this.changed = pChanged;
			this.unresolved = pUnresolved;
		}
	}

	// ------------------------------------------------------------------------------------------------------- apply

	/**
	 * Applies ONE step to ONE raw value. {@code pRawValue} of {@code null} or all-blank is passed through as
	 * {@code null}, unchanged, for every kind (mirroring {@code Transformations.apply}'s own "empty input is
	 * not a reject" rule): a cleanup step has nothing to clean on an already-empty field.
	 *
	 * @param pRowScopeValue only read by a SCOPED {@link #TRANSCODE_REFERENCE} (see the class Javadoc); ignored by every other kind, and by an unscoped reference.
	 */
	public static Result applyStep(final String pKind, final String pArgument, final String pRawValue, final String pRowScopeValue, final Lookups pLookups)
	{
		if (pRawValue == null || pRawValue.trim().isEmpty())
		{
			return new Result(null, false, false);
		}
		final String raw = pRawValue.trim();
		final String kind = pKind == null ? "" : pKind.trim().toUpperCase(Locale.ROOT);
		final Lookups lookups = pLookups == null ? Lookups.empty() : pLookups;

		if (CleaningSteps.TRIM.equals(kind))
		{
			return new Result(raw, !raw.equals(pRawValue), false);
		}
		if (CleaningSteps.COLLAPSE_SPACES.equals(kind))
		{
			final String collapsed = raw.replaceAll("\\s+", " ");
			return new Result(collapsed, !collapsed.equals(raw), false);
		}
		if (CleaningSteps.EMPTY_TO_NULL.equals(kind))
		{
			return CleaningSteps.emptyToNull(raw, pArgument);
		}
		if (CleaningSteps.UPPER.equals(kind))
		{
			final String upper = raw.toUpperCase(Locale.ROOT);
			return new Result(upper, !upper.equals(raw), false);
		}
		if (CleaningSteps.LOWER.equals(kind))
		{
			final String lower = raw.toLowerCase(Locale.ROOT);
			return new Result(lower, !lower.equals(raw), false);
		}
		if (CleaningSteps.TITLE_CASE.equals(kind))
		{
			final String titled = CleaningSteps.titleCase(raw);
			return new Result(titled, !titled.equals(raw), false);
		}
		if (CleaningSteps.STRIP_CHARS.equals(kind))
		{
			final String stripped = CleaningSteps.strip(raw, pArgument);
			return new Result(stripped, !stripped.equals(raw), false);
		}
		if (CleaningSteps.STRIP_ACCENTS.equals(kind))
		{
			final String stripped = CleaningSteps.stripAccents(raw);
			return new Result(stripped, !stripped.equals(raw), false);
		}
		if (CleaningSteps.NORMALISE_IDENTIFIER.equals(kind))
		{
			final String separators = pArgument == null || pArgument.isEmpty() ? CleaningSteps.DEFAULT_IDENTIFIER_SEPARATORS : pArgument;
			final String normalised = CleaningSteps.strip(raw, separators).toUpperCase(Locale.ROOT);
			return new Result(normalised, !normalised.equals(raw), false);
		}
		if (CleaningSteps.COUNTRY_TO_ISO2.equals(kind))
		{
			return CleaningSteps.resolveOrKeep(raw, lookups.resolveFlat("COUNTRY", raw));
		}
		if (CleaningSteps.CURRENCY_TO_ISO.equals(kind))
		{
			return CleaningSteps.resolveOrKeep(raw, lookups.resolveFlat("CURRENCY", raw));
		}
		if (CleaningSteps.TRANSCODE_REFERENCE.equals(kind))
		{
			return CleaningSteps.transcodeReference(raw, pArgument, pRowScopeValue, lookups);
		}
		// an unrecognised kind (a future value this build does not know yet, or a typo in argument JSON elsewhere):
		// left unchanged rather than thrown - the class Javadoc's own "never throws on data" rule
		return new Result(raw, false, false);
	}

	/**
	 * The landing field name a {@link #TRANSCODE_REFERENCE} step of {@code pSteps} needs as its row-scope
	 * value (see the class Javadoc), or {@code null} when no step of the pipeline needs one - what {@code
	 * ebx.StagingLoader} reads off the SAME landing row before calling {@link #applyPipeline}. A pipeline is
	 * never expected to carry more than one scoped step in this lot (the row-scope value is per-pipeline, not
	 * per-step), so only the first one found is used.
	 */
	public static String scopeFieldOf(final List<Step> pSteps)
	{
		if (pSteps == null)
		{
			return null;
		}
		for (final Step step : pSteps)
		{
			if (CleaningSteps.TRANSCODE_REFERENCE.equals(step.kind))
			{
				final String scopeField = CleaningSteps.jsonStringField(step.argument == null ? "" : step.argument, "scopeField");
				if (scopeField != null && !scopeField.isEmpty())
				{
					return scopeField;
				}
			}
		}
		return null;
	}

	/** Chains every step of {@code pSteps}, in order, feeding one step's output into the next; stops early (the remaining steps see nothing) once a step turns the value {@code null}. Aggregates {@code changed}/{@code unresolved} across every step, against the ORIGINAL trimmed input. */
	public static Result applyPipeline(final List<Step> pSteps, final String pRawValue, final String pRowScopeValue, final Lookups pLookups)
	{
		if (pRawValue == null || pRawValue.trim().isEmpty() || pSteps == null || pSteps.isEmpty())
		{
			return new Result(pRawValue == null || pRawValue.trim().isEmpty() ? null : pRawValue, false, false);
		}
		final String original = pRawValue.trim();
		String value = pRawValue;
		boolean unresolved = false;
		for (final Step step : pSteps)
		{
			final Result stepResult = CleaningSteps.applyStep(step.kind, step.argument, value, pRowScopeValue, pLookups);
			value = stepResult.value;
			unresolved = unresolved || stepResult.unresolved;
			if (value == null)
			{
				break;
			}
		}
		final boolean changed = value == null ? true : !value.equals(original);
		return new Result(value, changed, unresolved);
	}

	// ------------------------------------------------------------------------------------------------------- kinds

	private static Result emptyToNull(final String pRaw, final String pArgument)
	{
		final List<String> sentinels = pArgument == null || pArgument.trim().isEmpty() ? CleaningSteps.DEFAULT_EMPTY_SENTINELS
			: java.util.Arrays.asList(pArgument.split("\\|"));
		for (final String sentinel : sentinels)
		{
			if (pRaw.equalsIgnoreCase(sentinel.trim()))
			{
				return new Result(null, true, false);
			}
		}
		return new Result(pRaw, false, false);
	}

	/** Removes every occurrence of every character of {@code pChars} from {@code pRaw}; a blank/null {@code pChars} is a no-op. */
	private static String strip(final String pRaw, final String pChars)
	{
		if (pChars == null || pChars.isEmpty())
		{
			return pRaw;
		}
		final StringBuilder out = new StringBuilder(pRaw.length());
		for (int i = 0; i < pRaw.length(); i++)
		{
			final char c = pRaw.charAt(i);
			if (pChars.indexOf(c) < 0)
			{
				out.append(c);
			}
		}
		return out.toString();
	}

	/**
	 * Nobiliary / family-name particles kept in lower case INSIDE a name (staging polish, 2026-10-17). A small, documented list of name typography,
	 * not a per-domain rule: it only decides how a word is spelt once the step is already proposed, never whether a value is a name. Lower-cased
	 * only when the word is neither the first nor the last word of the value ({@code Jean de la Fontaine}, {@code Rue du Mont}; {@code De Gaulle},
	 * {@code Kim Van} stay as a first or last word). The elision forms {@code d'} and {@code l'} follow the same rule ({@code Jean d'Artagnan},
	 * {@code Rue de l'Eglise}; first word: {@code D'Artagnan}).
	 */
	static final Set<String> NAME_PARTICLES = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("de", "du", "des", "del", "le", "la",
		"van", "von", "der", "den", "di", "da", "dos")));

	/**
	 * {@link #TITLE_CASE}: a NAME-AWARE title case, decided word by word (staging polish, 2026-10-17). One function for every caller - the normalisation pass
	 * proposing the step, the verifier counting the changes ({@code StepChainCheck}) and the loader's {@code MappingStep} all reach it through
	 * {@link #applyStep}. Rules, in the order they are applied:
	 * <ol>
	 * <li><b>Words</b> are cut at white space, hyphens and dashes, slashes, parentheses, commas and dots ({@code JEAN-PIERRE} gives {@code Jean-Pierre},
	 * {@code J.R. SMITH} gives {@code J.R. Smith}); an apostrophe (straight or typographic) cuts a word into PARTS that are each capitalised
	 * ({@code O'BRIEN} gives {@code O'Brien}, {@code L'OREAL} gives {@code L'Oreal}).</li>
	 * <li>A part with a <b>digit</b> is kept as it is ({@code 3EME}, {@code B2B}, {@code 12A}).</li>
	 * <li>A part that <b>already mixes capitals and lower case</b> is kept as it is ({@code McDonald}, {@code MacDonald}, {@code DiCaprio}, {@code iPhone}): the
	 * source knew better than a rule, and the profile counts such a value as "mixed, left as it is" too. Only all-capitals and all-lower-case parts move.</li>
	 * <li>Otherwise the part is lower-cased and its first LETTER gets the title-case form of its code point (accents kept: an accented capital E at the
	 * start of a first name stays accented); {@code Locale.ROOT}, so no Turkish dotless-i surprise.</li>
	 * <li><b>Mc prefix</b>: {@code MCDONALD} gives {@code McDonald} when the part has at least three letters after {@code Mc}. <b>Only Mc is safe</b>:
	 * {@code Mac} is left alone ({@code MACHIN}, {@code MACON}, {@code MACRON} are not Scottish names), so {@code MACDONALD} gives {@code Macdonald}
	 * (a source that wants {@code MacDonald} writes it that way and rule 3 keeps it).</li>
	 * <li><b>Particles</b> ({@link #NAME_PARTICLES}, plus the elisions {@code d'} and {@code l'}) are lower case when the word is neither the first nor the
	 * last word of the value: {@code JEAN DE LA FONTAINE} gives {@code Jean de la Fontaine}; the first word stays capitalised ({@code DE GAULLE} gives
	 * {@code De Gaulle}, {@code D'ARTAGNAN} gives {@code D'Artagnan}, {@code JEAN D'ARTAGNAN} gives {@code Jean d'Artagnan}) and so does the last one
	 * ({@code THI LE} gives {@code Thi Le}). The documented, language-neutral-ish limit: {@code NGUYEN LE HUNG} becomes {@code Nguyen le Hung} (a surname
	 * {@code Le} in the middle is indistinguishable from the French article). A particle the source already wrote with a capital ({@code De}) is mixed
	 * case, rule 3, and kept.</li>
	 * </ol>
	 * Everything that is not a letter (digits, punctuation, separators) is copied. Null in, null out; never throws on data.
	 */
	public static String titleCase(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		// 1. words (UTF-16 start/end indexes)
		final List<int[]> words = new ArrayList<int[]>();
		int wordStart = -1;
		for (int i = 0; i < pValue.length();)
		{
			final int cp = pValue.codePointAt(i);
			if (CleaningSteps.isTitleCaseBoundary(cp))
			{
				if (wordStart >= 0)
				{
					words.add(new int[] { wordStart, i });
					wordStart = -1;
				}
			}
			else if (wordStart < 0)
			{
				wordStart = i;
			}
			i += Character.charCount(cp);
		}
		if (wordStart >= 0)
		{
			words.add(new int[] { wordStart, pValue.length() });
		}
		// 2. first / last word that carries a letter (the particle rule never lower-cases those)
		int firstLetterWord = -1;
		int lastLetterWord = -1;
		for (int w = 0; w < words.size(); w++)
		{
			if (CleaningSteps.hasLetter(pValue, words.get(w)[0], words.get(w)[1]))
			{
				if (firstLetterWord < 0)
				{
					firstLetterWord = w;
				}
				lastLetterWord = w;
			}
		}
		// 3. rebuild
		final StringBuilder out = new StringBuilder(pValue.length());
		int copied = 0;
		for (int w = 0; w < words.size(); w++)
		{
			final int start = words.get(w)[0];
			final int end = words.get(w)[1];
			out.append(pValue, copied, start);
			out.append(CleaningSteps.titleCaseWord(pValue.substring(start, end), w != firstLetterWord, w != lastLetterWord));
			copied = end;
		}
		out.append(pValue, copied, pValue.length());
		return out.toString();
	}

	private static boolean isTitleCaseBoundary(final int pCodePoint)
	{
		if (Character.isWhitespace(pCodePoint) || Character.isSpaceChar(pCodePoint))
		{
			return true;
		}
		switch (pCodePoint)
		{
			case '-':
			case '/':
			case '(':
			case ')':
			case ',':
			case '.':
				return true;
			default:
				// hyphen, non-breaking hyphen, figure dash, en dash, em dash
				return pCodePoint >= 0x2010 && pCodePoint <= 0x2015;
		}
	}

	private static boolean isApostrophe(final int pCodePoint)
	{
		return pCodePoint == '\'' || pCodePoint == 0x2019 || pCodePoint == 0x02BC;
	}

	private static boolean hasLetter(final String pText, final int pStart, final int pEnd)
	{
		for (int i = pStart; i < pEnd;)
		{
			final int cp = pText.codePointAt(i);
			if (Character.isLetter(cp))
			{
				return true;
			}
			i += Character.charCount(cp);
		}
		return false;
	}

	/**
	 * One word (no boundary inside, apostrophes allowed); {@code pNotFirst} is false for the first word of the value (nothing is lower-cased there) and
	 * {@code pNotLast} for its last word (a bare particle is never the end of a name; an elision {@code d'} always has something after it).
	 */
	private static String titleCaseWord(final String pWord, final boolean pNotFirst, final boolean pNotLast)
	{
		final List<String> parts = new ArrayList<String>();
		final List<String> apostrophes = new ArrayList<String>();
		int partStart = 0;
		for (int i = 0; i < pWord.length();)
		{
			final int cp = pWord.codePointAt(i);
			final int width = Character.charCount(cp);
			if (CleaningSteps.isApostrophe(cp))
			{
				parts.add(pWord.substring(partStart, i));
				apostrophes.add(pWord.substring(i, i + width));
				partStart = i + width;
			}
			i += width;
		}
		parts.add(pWord.substring(partStart));
		final String first = parts.get(0);
		if (pNotFirst && parts.size() == 2 && first.length() == 1 && ("d".equalsIgnoreCase(first) || "l".equalsIgnoreCase(first)))
		{
			// elision particle inside a name: Jean d'Artagnan, Rue de l'Eglise
			return first.toLowerCase(Locale.ROOT) + apostrophes.get(0) + CleaningSteps.titleCasePart(parts.get(1), true);
		}
		if (pNotFirst && pNotLast && parts.size() == 1 && CleaningSteps.NAME_PARTICLES.contains(first.toLowerCase(Locale.ROOT)) && CleaningSteps.caseKind(first) == 1)
		{
			return first.toLowerCase(Locale.ROOT);
		}
		final StringBuilder out = new StringBuilder(pWord.length());
		for (int i = 0; i < parts.size(); i++)
		{
			out.append(CleaningSteps.titleCasePart(parts.get(i), true));
			if (i < apostrophes.size())
			{
				out.append(apostrophes.get(i));
			}
		}
		return out.toString();
	}

	/** 0 = no cased letter, 1 = a single case (all capitals or all lower case), 2 = mixed, 3 = has a digit. */
	private static int caseKind(final String pPart)
	{
		boolean upper = false;
		boolean lower = false;
		for (int i = 0; i < pPart.length();)
		{
			final int cp = pPart.codePointAt(i);
			if (Character.isDigit(cp))
			{
				return 3;
			}
			upper |= Character.isUpperCase(cp);
			lower |= Character.isLowerCase(cp);
			i += Character.charCount(cp);
		}
		return upper && lower ? 2 : upper || lower ? 1 : 0;
	}

	private static String titleCasePart(final String pPart, final boolean pMcAllowed)
	{
		if (CleaningSteps.caseKind(pPart) != 1)
		{
			return pPart; // no cased letter, already mixed, or carries a digit: kept as it is
		}
		final String lower = pPart.toLowerCase(Locale.ROOT);
		final StringBuilder out = new StringBuilder(lower.length());
		boolean capitalised = false;
		boolean allLetters = true;
		for (int i = 0; i < lower.length();)
		{
			final int cp = lower.codePointAt(i);
			allLetters &= Character.isLetter(cp);
			if (!capitalised && Character.isLetter(cp))
			{
				out.appendCodePoint(Character.toTitleCase(cp));
				capitalised = true;
			}
			else
			{
				out.appendCodePoint(cp);
			}
			i += Character.charCount(cp);
		}
		// Mc prefix (only Mc: Mac is not safe, MACHIN/MACON are plain words): "Mcdonald" -> "McDonald", at least three letters after the prefix
		if (pMcAllowed && allLetters && lower.startsWith("mc") && lower.codePointCount(0, lower.length()) >= 5)
		{
			final int third = lower.codePointAt(2);
			return "Mc" + new String(Character.toChars(Character.toTitleCase(third))) + lower.substring(2 + Character.charCount(third));
		}
		return out.toString();
	}

	private static Result resolveOrKeep(final String pRaw, final String pResolved)
	{
		return pResolved == null ? new Result(pRaw, false, true) : new Result(pResolved, !pResolved.equals(pRaw), false);
	}

	/** @param pArgument a JSON object, {@code {"reference":"<kind>"}} or {@code {"reference":"<kind>","scopeField":"<name>"}} - see the class Javadoc. Malformed/missing JSON: the value is left unchanged, not thrown (this method reads its own argument the same tolerant way every other kind reads a raw value). */
	private static Result transcodeReference(final String pRaw, final String pArgument, final String pRowScopeValue, final Lookups pLookups)
	{
		if (pArgument == null || pArgument.trim().isEmpty())
		{
			return new Result(pRaw, false, true);
		}
		// codes transcoding lot H3 (2026-10-17): an argument that carries its OWN code table (CodeMap) resolves against it, no ReferenceBinding needed
		final CodeMap own = CodeMap.parseCached(pArgument);
		if (own != null)
		{
			return CleaningSteps.resolveOrKeep(pRaw, own.lookup(pRaw));
		}
		final String referenceKind = CleaningSteps.jsonStringField(pArgument, "reference");
		if (referenceKind == null)
		{
			return new Result(pRaw, false, true);
		}
		final String scopeField = CleaningSteps.jsonStringField(pArgument, "scopeField");
		final String resolved = scopeField == null || scopeField.isEmpty() ? pLookups.resolveFlat(referenceKind, pRaw)
			: pLookups.resolveScoped(referenceKind, pRowScopeValue, pRaw);
		return CleaningSteps.resolveOrKeep(pRaw, resolved);
	}

	/** A minimal, dependency-free {@code "key":"value"} reader for the small, fixed-shape JSON this class's own arguments use - not a general JSON parser (the EBX-side reader, {@code ebx.QualitySuggester}, uses Jackson to WRITE these arguments; this side only ever reads them back, and avoiding a full parse keeps this pure package's only dependency the JDK). {@code null} when the key is absent or the text is not shaped like a JSON object. */
	static String jsonStringField(final String pJson, final String pKey)
	{
		final String needle = "\"" + pKey + "\"";
		final int keyIndex = pJson.indexOf(needle);
		if (keyIndex < 0)
		{
			return null;
		}
		final int colon = pJson.indexOf(':', keyIndex + needle.length());
		if (colon < 0)
		{
			return null;
		}
		final int firstQuote = pJson.indexOf('"', colon + 1);
		if (firstQuote < 0)
		{
			return null;
		}
		final int secondQuote = pJson.indexOf('"', firstQuote + 1);
		if (secondQuote < 0)
		{
			return null;
		}
		return pJson.substring(firstQuote + 1, secondQuote);
	}

	// ------------------------------------------------------------------------------------------------ strip accents

	/**
	 * Removes every combining diacritical mark from {@code pValue} - {@code "Zürich"} -&gt; {@code "Zurich"},
	 * {@code "São Paulo"} -&gt; {@code "Sao Paulo"}, {@code "Straße"} -&gt; {@code "Strasse"}, {@code "Łódź"} -&gt;
	 * {@code "Lodz"} - WITHOUT changing case ({@link #UPPER} composes on top when both are wanted) and WITHOUT
	 * touching punctuation or spacing. Two passes: (1) {@link #ACCENT_FOLD_MAP}'s small, explicit table for the
	 * Latin letters {@code java.text.Normalizer}'s NFD form does not decompose ({@code ß}, {@code Æ}/{@code æ},
	 * {@code Œ}/{@code œ}, {@code Ø}/{@code ø}, {@code Đ}/{@code đ}, {@code Ł}/{@code ł}, {@code Þ}/{@code þ});
	 * (2) {@code Normalizer.normalize(..., Form.NFD)} then dropping every {@link Character#NON_SPACING_MARK}
	 * character, which handles every ordinary precomposed accented letter ({@code é}, {@code ü}, {@code ñ},
	 * {@code ç}...). <b>This is NOT a transliteration</b>: a non-Latin script (Greek, Cyrillic, Arabic, CJK...)
	 * is left completely untouched apart from stripping any combining mark it happens to carry - there is no
	 * attempt to romanise it. {@code null} in, {@code null} out; never throws.
	 */
	public static String stripAccents(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final StringBuilder substituted = new StringBuilder(pValue.length());
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			final String mapped = CleaningSteps.ACCENT_FOLD_MAP.get(Character.valueOf(c));
			if (mapped != null)
			{
				substituted.append(mapped);
			}
			else
			{
				substituted.append(c);
			}
		}
		final String decomposed = Normalizer.normalize(substituted.toString(), Normalizer.Form.NFD);
		final StringBuilder out = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length(); i++)
		{
			final char c = decomposed.charAt(i);
			if (Character.getType(c) == Character.NON_SPACING_MARK)
			{
				continue;
			}
			out.append(c);
		}
		return out.toString();
	}

	// ----------------------------------------------------------------------------------------------------- fold key

	/**
	 * The ONE fold every {@code FOLD_KEY} derived {@code StagingAttribute} uses (product-owner decision,
	 * 2026-09-22 - PLAN.md, "la valeur stockee garde ses accents et sa casse"): trim, collapse any run of
	 * whitespace to a single space, {@link #stripAccents}, then upper-case ({@link Locale#ROOT}). Replaces
	 * what {@code quality.CrossSystemFold} used to propose as a {@code STRIP_ACCENTS}+{@code UPPER}
	 * {@code MappingStep} pair on the DISPLAY attribute itself (rewriting the stored value - the data-quality
	 * loss the 2026-09-22 decision fixes): the fold now only ever feeds a SEPARATE, technical derived
	 * attribute next to the display one, which stays untouched.
	 * <p>
	 * {@code null} in, {@code null} out; an empty or all-whitespace input, or one that folds down to nothing
	 * (a string of only combining marks, say), also reads as {@code null} - never an empty string, so a
	 * caller can tell "no meaningful key" apart from "the empty string happens to be the key" with a single
	 * null check. Never throws - same convention as every other method in this class.
	 */
	public static String foldKey(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String collapsed = pValue.trim().replaceAll("\\s+", " ");
		if (collapsed.isEmpty())
		{
			return null;
		}
		final String folded = CleaningSteps.stripAccents(collapsed).toUpperCase(Locale.ROOT).trim();
		return folded.isEmpty() ? null : folded;
	}

	// --------------------------------------------------------------------------------------------------- lookup key

	/**
	 * Case-folded, accent-stripped, punctuation-and-whitespace-stripped form of a value, used on BOTH sides of
	 * a lookup so {@code "U.S."}, {@code "US"} and {@code "us"} (docs/NORMALISATION.md sec 1.8) all key to the
	 * same entry, and a multi-word alias matches regardless of internal spacing ({@code "United States"} and
	 * a reference label of the same two words compare equal). {@code java.text.Normalizer} does the accent
	 * decomposition (NFD, then every combining mark is dropped); only letters and digits survive.
	 */
	public static String normalizeKey(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		final String decomposed = Normalizer.normalize(pValue.trim(), Normalizer.Form.NFD);
		final StringBuilder out = new StringBuilder(decomposed.length());
		for (int i = 0; i < decomposed.length(); i++)
		{
			final char c = decomposed.charAt(i);
			if (Character.getType(c) == Character.NON_SPACING_MARK)
			{
				continue;
			}
			final char upper = Character.toUpperCase(c);
			if (Character.isLetterOrDigit(upper))
			{
				out.append(upper);
			}
		}
		return out.toString();
	}

	/**
	 * Builds a flat {@link Lookups} table from a reference table's rows (one entry per (row, label field)
	 * pair, so several columns all resolve to the same code) plus an optional synonym overlay - the shape
	 * {@code ebx.QualitySuggester} feeds after reading a {@code ReferenceBinding} and its target table. Pure
	 * helper kept here (not duplicated on the EBX side) so the normalization used to BUILD a lookup table is
	 * guaranteed to be the exact same one used to READ it.
	 *
	 * @param pRows one entry per reference row: code -&gt; every label value that should resolve to it (already read off the row, never re-fetched here).
	 * @param pSynonyms alias (as written by a person, any casing/punctuation) -&gt; code; overlays {@code pRows}, so a synonym can override a reference label if they ever disagree.
	 */
	public static Map<String, String> buildFlatTable(final Map<String, List<String>> pRows, final Map<String, String> pSynonyms)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pRows != null)
		{
			for (final Map.Entry<String, List<String>> row : pRows.entrySet())
			{
				final String code = row.getKey();
				out.put(CleaningSteps.normalizeKey(code), code);
				for (final String label : row.getValue())
				{
					if (label != null && !label.trim().isEmpty())
					{
						out.put(CleaningSteps.normalizeKey(label), code);
					}
				}
			}
		}
		if (pSynonyms != null)
		{
			for (final Map.Entry<String, String> synonym : pSynonyms.entrySet())
			{
				out.put(CleaningSteps.normalizeKey(synonym.getKey()), synonym.getValue());
			}
		}
		return out;
	}
}
