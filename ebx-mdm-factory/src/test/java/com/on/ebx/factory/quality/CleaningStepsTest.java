package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class CleaningStepsTest
{
	public static void main(final String[] pArgs)
	{
		CleaningStepsTest.nullAndBlankInputIsAlwaysNullNeverChanged();
		CleaningStepsTest.trim();
		CleaningStepsTest.collapseSpaces();
		CleaningStepsTest.emptyToNullDefaultSentinels();
		CleaningStepsTest.emptyToNullCustomSentinelsAndTheLegalFormNaTrap();
		CleaningStepsTest.upperAndLower();
		CleaningStepsTest.stripChars();
		CleaningStepsTest.titleCase();
		CleaningStepsTest.stripAccents();
		CleaningStepsTest.foldKey();
		CleaningStepsTest.normaliseIdentifierOnRealRegistrationNoShapes();
		CleaningStepsTest.normaliseIdentifierDefaultSeparators();
		CleaningStepsTest.countryDirectMatchesFromTheRealMeasuredSpellings();
		CleaningStepsTest.countrySynonymMatchesFromTheRealMeasuredSpellings();
		CleaningStepsTest.countryUnresolvedIsLeftUnchangedAndCounted();
		CleaningStepsTest.currencyAlreadyIso4217IsANoOp();
		CleaningStepsTest.transcodeReferenceScopedByCountryLegalForm();
		CleaningStepsTest.transcodeReferenceUnscopedFallsBackToFlat();
		CleaningStepsTest.pipelineChainsStepsAndAggregatesChangedAndUnresolved();
		CleaningStepsTest.pipelineStopsAtANullResult();
		CleaningStepsTest.unknownKindIsANoOpNeverThrows();
		CleaningStepsTest.scopeFieldOfFindsTheTranscodeReferenceScope();
		System.out.println("CleaningStepsTest OK");
	}

	/** Staging normalisation lot D (2026-10-17): the common case the pass proposes for a source written in capitals. */
	private static void titleCase()
	{
		CleaningStepsTest.check("TITLE_CASE: capitals to title case", "Fournier".equals(CleaningSteps.applyStep(CleaningSteps.TITLE_CASE, null, "FOURNIER", null, null).value));
		CleaningStepsTest.check("TITLE_CASE: hyphen and apostrophe start a word", "Jean-Pierre O'Brien".equals(CleaningSteps.titleCase("JEAN-PIERRE O'BRIEN")));
		CleaningStepsTest.check("TITLE_CASE: typographic apostrophe", "L\u2019Or\u00e9al".equals(CleaningSteps.titleCase("L\u2019OR\u00c9AL")));
		CleaningStepsTest.check("TITLE_CASE: lower case to title case, digits untouched", "12 Rue Pasteur".equals(CleaningSteps.titleCase("12 rue pasteur")));
		CleaningStepsTest.check("TITLE_CASE: already title case is not changed", !CleaningSteps.applyStep(CleaningSteps.TITLE_CASE, null, "Martin", null, null).changed);
		CleaningStepsTest.check("TITLE_CASE: null in, null out", CleaningSteps.titleCase(null) == null);
	}

	private static void nullAndBlankInputIsAlwaysNullNeverChanged()
	{
		for (final String kind : new String[] { CleaningSteps.TRIM, CleaningSteps.COLLAPSE_SPACES, CleaningSteps.EMPTY_TO_NULL, CleaningSteps.UPPER,
			CleaningSteps.LOWER, CleaningSteps.STRIP_CHARS, CleaningSteps.STRIP_ACCENTS, CleaningSteps.NORMALISE_IDENTIFIER, CleaningSteps.COUNTRY_TO_ISO2,
			CleaningSteps.CURRENCY_TO_ISO })
		{
			final CleaningSteps.Result nullResult = CleaningSteps.applyStep(kind, null, null, null, null);
			CleaningStepsTest.check(kind + "(null): null, not changed, not unresolved",
				nullResult.value == null && !nullResult.changed && !nullResult.unresolved);
			final CleaningSteps.Result blankResult = CleaningSteps.applyStep(kind, null, "   ", null, null);
			CleaningStepsTest.check(kind + "(blank): null, not changed, not unresolved",
				blankResult.value == null && !blankResult.changed && !blankResult.unresolved);
		}
	}

	private static void trim()
	{
		final CleaningSteps.Result changed = CleaningSteps.applyStep(CleaningSteps.TRIM, null, "  Acme  ", null, null);
		CleaningStepsTest.check("trims and reports changed", "Acme".equals(changed.value) && changed.changed);
		final CleaningSteps.Result clean = CleaningSteps.applyStep(CleaningSteps.TRIM, null, "Acme", null, null);
		CleaningStepsTest.check("already trimmed: not changed (the task's own zero-defect rule)", "Acme".equals(clean.value) && !clean.changed);
	}

	private static void collapseSpaces()
	{
		final CleaningSteps.Result changed = CleaningSteps.applyStep(CleaningSteps.COLLAPSE_SPACES, null, "Acme   Inc", null, null);
		CleaningStepsTest.check("collapses an inner run to one space", "Acme Inc".equals(changed.value) && changed.changed);
		final CleaningSteps.Result clean = CleaningSteps.applyStep(CleaningSteps.COLLAPSE_SPACES, null, "Acme Inc", null, null);
		CleaningStepsTest.check("a single space is not changed", "Acme Inc".equals(clean.value) && !clean.changed);
	}

	private static void emptyToNullDefaultSentinels()
	{
		for (final String sentinel : new String[] { "N/A", "n/a", "NA", "-", "?", "Null", "none", "UNKNOWN" })
		{
			final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.EMPTY_TO_NULL, null, sentinel, null, null);
			CleaningStepsTest.check(sentinel + " -> null", result.value == null && result.changed);
		}
		final CleaningSteps.Result realValue = CleaningSteps.applyStep(CleaningSteps.EMPTY_TO_NULL, null, "Acme Inc", null, null);
		CleaningStepsTest.check("a real value is left alone", "Acme Inc".equals(realValue.value) && !realValue.changed);
	}

	/** docs/NORMALISATION.md sec 1.3's own headline caution: SUPPLIER.LEGAL_FORM's "NA" (2 real rows, US national banks) is the legally correct abbreviation for "National Association", not a null-like sentinel - scoping the argument per column is what avoids destroying it. */
	private static void emptyToNullCustomSentinelsAndTheLegalFormNaTrap()
	{
		final CleaningSteps.Result scoped = CleaningSteps.applyStep(CleaningSteps.EMPTY_TO_NULL, "N/A|-|?", "NA", null, null);
		CleaningStepsTest.check("a column-scoped sentinel list that deliberately excludes NA leaves it alone: " + scoped.value, "NA".equals(scoped.value) && !scoped.changed);
	}

	private static void upperAndLower()
	{
		final CleaningSteps.Result upper = CleaningSteps.applyStep(CleaningSteps.UPPER, null, "bnpafrpp", null, null);
		CleaningStepsTest.check("upper-cases and reports changed", "BNPAFRPP".equals(upper.value) && upper.changed);
		final CleaningSteps.Result alreadyUpper = CleaningSteps.applyStep(CleaningSteps.UPPER, null, "BNPAFRPP", null, null);
		CleaningStepsTest.check("already upper: not changed", !alreadyUpper.changed);
		final CleaningSteps.Result lower = CleaningSteps.applyStep(CleaningSteps.LOWER, null, "Jane@Example.COM", null, null);
		CleaningStepsTest.check("lower-cases and reports changed", "jane@example.com".equals(lower.value) && lower.changed);
	}

	private static void stripChars()
	{
		final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.STRIP_CHARS, " .-", "CHE-106.831.974", null, null);
		CleaningStepsTest.check("strips every listed character: " + result.value, "CHE106831974".equals(result.value) && result.changed);
		final CleaningSteps.Result noOp = CleaningSteps.applyStep(CleaningSteps.STRIP_CHARS, " .-", "CHE106831974", null, null);
		CleaningStepsTest.check("nothing to strip: not changed", !noOp.changed);
	}

	/**
	 * PLAN.md 2026-09-21: CRM/ERP city spellings that only coincide once accents are stripped AND the case is
	 * folded ({@link CleaningSteps#UPPER} separately) - {@code "Zürich"} -&gt; {@code "Zurich"}. Also covers the
	 * task's own worked examples for the Latin letters {@code Normalizer}'s NFD form does not decompose ({@code
	 * "Straße"}, {@code "Łódź"}) and the "leave everything else alone" rules: case is untouched, punctuation and
	 * spacing are untouched, an already-ASCII value is a no-op, and a non-Latin script (Greek, Cyrillic) is left
	 * completely unchanged since it carries no combining mark to strip - this is a diacritic stripper, never a
	 * transliteration.
	 */
	private static void stripAccents()
	{
		final CleaningSteps.Result zurich = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "Zürich", null, null);
		CleaningStepsTest.check("Zürich -> Zurich: " + zurich.value, "Zurich".equals(zurich.value) && zurich.changed);
		final CleaningSteps.Result saoPaulo = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "São Paulo", null, null);
		CleaningStepsTest.check("São Paulo -> Sao Paulo (space kept): " + saoPaulo.value, "Sao Paulo".equals(saoPaulo.value) && saoPaulo.changed);
		final CleaningSteps.Result lodz = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "Łódź", null, null);
		CleaningStepsTest.check("Lodz (special-letter table for L/l, NFD for o/z): " + lodz.value, "Lodz".equals(lodz.value) && lodz.changed);
		final CleaningSteps.Result strasse = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "Straße", null, null);
		CleaningStepsTest.check("Straße -> Strasse (ß has no combining mark to drop, needs the explicit table): " + strasse.value,
			"Strasse".equals(strasse.value) && strasse.changed);

		final CleaningSteps.Result caseUntouched = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "ZÜRICH", null, null);
		CleaningStepsTest.check("case is never changed by this step (composes with UPPER): " + caseUntouched.value, "ZURICH".equals(caseUntouched.value));

		final CleaningSteps.Result alreadyAscii = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "Acme Corp", null, null);
		CleaningStepsTest.check("already ASCII: not changed", "Acme Corp".equals(alreadyAscii.value) && !alreadyAscii.changed);

		// a Greek word with no combining mark of its own: unchanged, and specifically NOT transliterated to Latin
		// letters - a word that DID carry a Greek tonos accent would still lose that one combining mark (the
		// class Javadoc's own "apart from their combining marks" caveat), which is not what this case tests.
		final CleaningSteps.Result greek = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "ΑΘΗΝΑ", null, null);
		CleaningStepsTest.check("a non-Latin script is left completely unchanged - this is not a transliteration: " + greek.value,
			"ΑΘΗΝΑ".equals(greek.value) && !greek.changed);
		final CleaningSteps.Result cyrillic = CleaningSteps.applyStep(CleaningSteps.STRIP_ACCENTS, null, "Москва", null, null);
		CleaningStepsTest.check("Cyrillic likewise unchanged: " + cyrillic.value, "Москва".equals(cyrillic.value) && !cyrillic.changed);
	}

	/**
	 * {@link CleaningSteps#foldKey} - the ONE fold every {@code FOLD_KEY} derived attribute uses (2026-09-22
	 * product-owner decision): trim, collapse inner whitespace, strip accents, upper-case; an empty/all-blank
	 * or fully-folded-away result reads as {@code null}. Two domains besides city/company (a Polish city, a
	 * German word, a Cyrillic word) so nothing here is accidentally keyed on party-data vocabulary.
	 */
	private static void foldKey()
	{
		CleaningStepsTest.check("null -> null", CleaningSteps.foldKey(null) == null);
		CleaningStepsTest.check("empty -> null", CleaningSteps.foldKey("") == null);
		CleaningStepsTest.check("all-whitespace -> null", CleaningSteps.foldKey("   ") == null);
		CleaningStepsTest.check("leading/trailing spaces trimmed, accents stripped, upper-cased: " + CleaningSteps.foldKey("  Zürich "),
			"ZURICH".equals(CleaningSteps.foldKey("  Zürich ")));
		CleaningStepsTest.check("inner run of spaces collapsed to one: " + CleaningSteps.foldKey("São   Paulo"), "SAO PAULO".equals(CleaningSteps.foldKey("São   Paulo")));
		CleaningStepsTest.check("Straße -> STRASSE (explicit accent-fold table, then upper): " + CleaningSteps.foldKey("Straße"),
			"STRASSE".equals(CleaningSteps.foldKey("Straße")));
		CleaningStepsTest.check("Łódź -> LODZ (special-letter table for L/l, NFD for o/z, then upper): " + CleaningSteps.foldKey("Łódź"),
			"LODZ".equals(CleaningSteps.foldKey("Łódź")));
		CleaningStepsTest.check("a non-Latin script is upper-cased, never transliterated: " + CleaningSteps.foldKey("Москва"),
			"МОСКВА".equals(CleaningSteps.foldKey("Москва")));
		CleaningStepsTest.check("already-clean ASCII is upper-cased like anything else - the fold always includes case: " + CleaningSteps.foldKey("Acme Corp"),
			"ACME CORP".equals(CleaningSteps.foldKey("Acme Corp")));
	}

	/** The real REGISTRATION_NO shapes docs/NORMALISATION.md sec 1.6 measured live: a Swiss UID with dots, a German number with a space, a French SIREN with inner spaces. */
	private static void normaliseIdentifierOnRealRegistrationNoShapes()
	{
		CleaningStepsTest.check("Swiss UID: CHE-106.831.974 -> CHE106831974",
			"CHE106831974".equals(CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, null, "CHE-106.831.974", null, null).value));
		CleaningStepsTest.check("German Handelsregister: HRB 32000 -> HRB32000",
			"HRB32000".equals(CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, null, "HRB 32000", null, null).value));
		CleaningStepsTest.check("French SIREN with inner spaces: 775 670 284 -> 775670284",
			"775670284".equals(CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, null, "775 670 284", null, null).value));
		CleaningStepsTest.check("lower-case LEI is upper-cased too (docs/NORMALISATION.md sec 1.2's own defect)",
			"N4JDFKKH2FTD8RKFXO39".equals(CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, null, "n4jdfkkh2ftd8rkfxo39", null, null).value));
	}

	private static void normaliseIdentifierDefaultSeparators()
	{
		final CleaningSteps.Result blankArgument = CleaningSteps.applyStep(CleaningSteps.NORMALISE_IDENTIFIER, "", "007 457 141", null, null);
		CleaningStepsTest.check("blank argument falls back to the default separators (space/dot/dash): " + blankArgument.value,
			"007457141".equals(blankArgument.value));
	}

	// ----------------------------------------------------------------------------------------------------- country

	/**
	 * The exact 16 distinct spellings measured live on ACCOUNT.BILLING_COUNTRY (docs/NORMALISATION.md sec 1.8),
	 * against a Country reference fixture shaped like the real Geographies/Country table (Alpha2Code/Alpha3Code/
	 * Name/NameFR) - asserting which resolve WITHOUT any synonym, exactly reproducing the study's own tier split.
	 */
	private static void countryDirectMatchesFromTheRealMeasuredSpellings()
	{
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(CleaningStepsTest.countryTable(), null);
		// "U.S." resolves directly here (not via a synonym): normalizeKey strips every punctuation character, so
		// "U.S." and the Alpha2Code "US" collapse to the exact same key "US" - a genuine, if pleasant, side effect
		// of punctuation removal being total rather than dot-specific (docs/NORMALISATION.md sec 1.8 measured this
		// tier split against a plain exact-match rule, which does NOT strip internal punctuation the same way).
		final String[] direct = { "France", "FRANCE", "Fr", "USA", "U.S.", "Canada", "Suisse", "China", "Spain", "Netherlands" };
		for (final String value : direct)
		{
			final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.COUNTRY_TO_ISO2, null, value, null, lookups);
			CleaningStepsTest.check(value + " resolves directly (no synonym needed): got " + result.value + " unresolved=" + result.unresolved,
				!result.unresolved && result.value != null && result.value.length() == 2);
		}
	}

	private static void countrySynonymMatchesFromTheRealMeasuredSpellings()
	{
		final CleaningSteps.Lookups withoutSynonyms = new CleaningSteps.Lookups(CleaningStepsTest.countryTable(), null);
		final String[] needsSynonym = { "United States", "UK", "Deutschland", "Italia", "Great Britain", "Holland" };
		for (final String value : needsSynonym)
		{
			final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.COUNTRY_TO_ISO2, null, value, null, withoutSynonyms);
			CleaningStepsTest.check(value + " does NOT resolve without a synonym", result.unresolved);
		}
		final Map<String, String> flat = new LinkedHashMap<String, String>(CleaningStepsTest.countryTable().get("COUNTRY"));
		flat.putAll(CleaningSteps.buildFlatTable(null, CleaningStepsTest.countrySynonyms()));
		final CleaningSteps.Lookups withSynonyms = new CleaningSteps.Lookups(java.util.Collections.singletonMap("COUNTRY", flat), null);
		for (final String value : needsSynonym)
		{
			final CleaningSteps.Result resolved = CleaningSteps.applyStep(CleaningSteps.COUNTRY_TO_ISO2, null, value, null, withSynonyms);
			CleaningStepsTest.check(value + " resolves once the synonym dictionary is loaded: " + resolved.value, !resolved.unresolved && resolved.value != null);
		}
	}

	private static void countryUnresolvedIsLeftUnchangedAndCounted()
	{
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(CleaningStepsTest.countryTable(), null);
		final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.COUNTRY_TO_ISO2, null, "Atlantis", null, lookups);
		CleaningStepsTest.check("an unknown country is left unchanged and marked unresolved - never invented",
			"Atlantis".equals(result.value) && !result.changed && result.unresolved);
	}

	private static void currencyAlreadyIso4217IsANoOp()
	{
		final Map<String, String> currency = new LinkedHashMap<String, String>();
		currency.put("EUR", "EUR");
		currency.put("USD", "USD");
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(java.util.Collections.singletonMap("CURRENCY", currency), null);
		final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.CURRENCY_TO_ISO, null, "EUR", null, lookups);
		CleaningStepsTest.check("already ISO 4217: resolved, but not changed (a no-op, docs/NORMALISATION.md sec 1.9's own finding)",
			!result.unresolved && !result.changed && "EUR".equals(result.value));
	}

	// -------------------------------------------------------------------------------------------------- legal form

	/** docs/NORMALISATION.md sec 1.7: the join key is (Country, Abbreviation), not Abbreviation alone - "SA" means something different per country. */
	private static void transcodeReferenceScopedByCountryLegalForm()
	{
		final Map<String, Map<String, String>> byCountry = new LinkedHashMap<String, Map<String, String>>();
		final Map<String, String> france = new LinkedHashMap<String, String>();
		france.put(CleaningSteps.normalizeKey("SA"), "QVPB"); // France's Societe anonyme, the real code read live 2026-09-20
		final Map<String, String> spain = new LinkedHashMap<String, String>();
		spain.put(CleaningSteps.normalizeKey("SA"), "SOME_ES_CODE"); // a different LegalForm.Code for the same abbreviation, another country
		byCountry.put(CleaningSteps.normalizeKey("FR"), france);
		byCountry.put(CleaningSteps.normalizeKey("ES"), spain);
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(null, java.util.Collections.singletonMap("LEGAL_FORM", byCountry));

		final String argument = "{\"reference\":\"LEGAL_FORM\",\"scopeField\":\"country\"}";
		final CleaningSteps.Result frenchSa = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, argument, "SA", "FR", lookups);
		CleaningStepsTest.check("SA scoped by FR resolves to the French code: " + frenchSa.value, "QVPB".equals(frenchSa.value));
		final CleaningSteps.Result spanishSa = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, argument, "SA", "ES", lookups);
		CleaningStepsTest.check("the SAME abbreviation scoped by ES resolves to a DIFFERENT code: " + spanishSa.value, "SOME_ES_CODE".equals(spanishSa.value));
		final CleaningSteps.Result unknownCountry = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, argument, "SA", "ZZ", lookups);
		CleaningStepsTest.check("an unscoped/unknown country leaves the value unchanged and unresolved", unknownCountry.unresolved && "SA".equals(unknownCountry.value));
	}

	private static void transcodeReferenceUnscopedFallsBackToFlat()
	{
		final Map<String, String> flat = new LinkedHashMap<String, String>();
		flat.put(CleaningSteps.normalizeKey("SA"), "SOME_CODE");
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(java.util.Collections.singletonMap("SOME_KIND", flat), null);
		final CleaningSteps.Result result = CleaningSteps.applyStep(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"SOME_KIND\"}", "SA", null, lookups);
		CleaningStepsTest.check("no scopeField in the argument: resolved against the flat table", "SOME_CODE".equals(result.value));
	}

	// -------------------------------------------------------------------------------------------------------- pipeline

	private static void pipelineChainsStepsAndAggregatesChangedAndUnresolved()
	{
		final List<CleaningSteps.Step> steps = Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRIM, null),
			new CleaningSteps.Step(CleaningSteps.NORMALISE_IDENTIFIER, null));
		final CleaningSteps.Result result = CleaningSteps.applyPipeline(steps, "  che-106.831.974  ", null, null);
		CleaningStepsTest.check("chained TRIM then NORMALISE_IDENTIFIER: " + result.value, "CHE106831974".equals(result.value) && result.changed);
	}

	private static void pipelineStopsAtANullResult()
	{
		final List<CleaningSteps.Step> steps = Arrays.asList(new CleaningSteps.Step(CleaningSteps.EMPTY_TO_NULL, null), new CleaningSteps.Step(CleaningSteps.UPPER, null));
		final CleaningSteps.Result result = CleaningSteps.applyPipeline(steps, "N/A", null, null);
		CleaningStepsTest.check("EMPTY_TO_NULL turns it null; UPPER never sees a null value and never throws", result.value == null && result.changed);
	}

	/** {@code ebx.StagingLoader}'s own use of this (task D, the execution hook): find which landing field to read as the row-scope value BEFORE calling {@link CleaningSteps#applyPipeline}. */
	private static void scopeFieldOfFindsTheTranscodeReferenceScope()
	{
		final List<CleaningSteps.Step> withScope = Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRIM, null),
			new CleaningSteps.Step(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"LEGAL_FORM\",\"scopeField\":\"country\"}"));
		CleaningStepsTest.check("finds the scope field name", "country".equals(CleaningSteps.scopeFieldOf(withScope)));

		final List<CleaningSteps.Step> withoutScope = Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRIM, null),
			new CleaningSteps.Step(CleaningSteps.COUNTRY_TO_ISO2, null));
		CleaningStepsTest.check("no TRANSCODE_REFERENCE step: null", CleaningSteps.scopeFieldOf(withoutScope) == null);

		final List<CleaningSteps.Step> unscopedTranscode = Arrays.asList(new CleaningSteps.Step(CleaningSteps.TRANSCODE_REFERENCE, "{\"reference\":\"SOME_KIND\"}"));
		CleaningStepsTest.check("a TRANSCODE_REFERENCE with no scopeField: null", CleaningSteps.scopeFieldOf(unscopedTranscode) == null);

		CleaningStepsTest.check("null steps list: null", CleaningSteps.scopeFieldOf(null) == null);
	}

	private static void unknownKindIsANoOpNeverThrows()
	{
		final CleaningSteps.Result result = CleaningSteps.applyStep("SOME_FUTURE_KIND", null, "Acme", null, null);
		CleaningStepsTest.check("an unrecognised kind leaves the value unchanged rather than throwing", "Acme".equals(result.value) && !result.changed);
	}

	// ------------------------------------------------------------------------------------------------------ fixtures

	/** A Country reference fixture shaped like the real Geographies/Country table read live 2026-09-20: Alpha2Code (the code), Name, NameFR, Alpha3Code as label fields. */
	private static Map<String, Map<String, String>> countryTable()
	{
		final Map<String, List<String>> rows = new LinkedHashMap<String, List<String>>();
		rows.put("FR", Arrays.asList("France", "France", "FRA"));
		rows.put("US", Arrays.asList("United States of America", "Etats-Unis", "USA"));
		rows.put("GB", Arrays.asList("United Kingdom", "Royaume-Uni", "GBR"));
		rows.put("DE", Arrays.asList("Germany", "Allemagne", "DEU"));
		rows.put("IT", Arrays.asList("Italy", "Italie", "ITA"));
		rows.put("CH", Arrays.asList("Switzerland", "Suisse", "CHE"));
		rows.put("ES", Arrays.asList("Spain", "Espagne", "ESP"));
		rows.put("CA", Arrays.asList("Canada", "Canada", "CAN"));
		rows.put("CN", Arrays.asList("China", "Chine", "CHN"));
		rows.put("NL", Arrays.asList("Netherlands", "Pays-Bas", "NLD"));
		final Map<String, String> flat = CleaningSteps.buildFlatTable(rows, null);
		final Map<String, Map<String, String>> out = new LinkedHashMap<String, Map<String, String>>();
		out.put("COUNTRY", flat);
		return out;
	}

	/** The small, explicit synonym dictionary docs/NORMALISATION.md sec 1.8 recommends: reviewed once, then a plain lookup forever after. */
	private static Map<String, String> countrySynonyms()
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		out.put("UK", "GB");
		out.put("USA", "US");
		out.put("U.S.", "US");
		out.put("United States", "US");
		out.put("Holland", "NL");
		out.put("Great Britain", "GB");
		out.put("Deutschland", "DE");
		out.put("Italia", "IT");
		return out;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
