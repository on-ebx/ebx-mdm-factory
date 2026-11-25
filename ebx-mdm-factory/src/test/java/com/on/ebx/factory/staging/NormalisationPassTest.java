package com.on.ebx.factory.staging;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free: run the main, a failure throws. The three columns of the B2C test (web shop, store tickets, loyalty
 * members) feed the same attributes, as the language-model pass of 2026-10-17 linked them; no column name is ever read
 * by the pass, so the attribute ids below are arbitrary.
 */
public final class NormalisationPassTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		NormalisationPassTest.lastNamesCapitalsSourceGetsTitleCase();
		NormalisationPassTest.emailsOnlyTheMixedSourceGetsLower();
		NormalisationPassTest.capitalsWinWhenTheyAreTheMajority();
		NormalisationPassTest.noCaseStepForOneSourceOrAReadingRegexOrAnExistingStep();
		NormalisationPassTest.phonesKeepThePlus();
		NormalisationPassTest.whitespaceAndPlaceholders();
		NormalisationPassTest.referenceTranscodingOnlyWithAReference();
		NormalisationPassTest.codesToAlignAreFlaggedNeverMapped();
		NormalisationPassTest.typedColumnsGetNoTextStep();
		NormalisationPassTest.nothingToDoWhenTheValuesAgree();
		NormalisationPassTest.theWholeB2cFiles();
		NormalisationPassTest.mappingsThatDoNotReadTheirColumnGetNoStep();
		System.out.println("NormalisationPassTest OK");
	}

	// -------------------------------------------------------------------------------------------------- case

	/** NOM (tickets) in capitals, nom (loyalty) and lastName (web) in title case, one attribute. */
	private static void lastNamesCapitalsSourceGetsTitleCase()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("web", "lastName", "WEB", NormalisationProfilerTest.counts("Richard", 60, "Martin", 60, "Dupont", 40)),
			NormalisationPassTest.column("tickets", "lastName", "TICKETS", NormalisationProfilerTest.counts("FOURNIER", 100, "DURAND", 134)),
			NormalisationPassTest.column("loyalty", "lastName", "LOYALTY", NormalisationProfilerTest.counts("Michel", 70, "Thomas", 70))), CleaningSteps.Lookups.empty());
		final List<NormalisationPass.Step> tickets = result.stepsOf("tickets");
		check("the capitals source gets one step", tickets.size() == 1 && CleaningSteps.TITLE_CASE.equals(tickets.get(0).kind));
		check("the title-case sources get none", result.stepsOf("web").isEmpty() && result.stepsOf("loyalty").isEmpty());
		final NormalisationPass.Step step = tickets.get(0);
		check("evidence: every row of the source changes", step.valuesSeen == 234L && step.valuesChanged == 234L);
		check("examples are shapes, never values", step.examples.size() == 2 && step.examples.get(0).equals("AAAAAAAA -> Aaaaaaa") || step.examples.get(0).contains("->"));
		check("no example holds a name", !step.examples.toString().contains("FOURNIER") && !step.examples.toString().contains("Fournier"));
		check("the rationale carries the marker and the counts", NormalisationPass.isOwnRationale(step.rationale) && step.rationale.contains("234"));
		check("confidence below the mechanical 100", step.confidence == 80);
	}

	/** loyalty.email: 109 lower, 31 upper; web and tickets all lower. */
	private static void emailsOnlyTheMixedSourceGetsLower()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("web", "email", "WEB", NormalisationProfilerTest.counts("julie.richard62@free.fr", 90, "hugo.robert63@free.fr", 70)),
			NormalisationPassTest.column("tickets", "email", "TICKETS", NormalisationProfilerTest.counts("lucas.fournier41@orange.fr", 140)),
			NormalisationPassTest.column("loyalty", "email", "LOYALTY", NormalisationProfilerTest.counts("antoine.thomas96@free.fr", 109, "CHLOE.MICHEL88@ORANGE.FR", 31))),
			CleaningSteps.Lookups.empty());
		check("only the source that deviates gets LOWER", result.stepsOf("web").isEmpty() && result.stepsOf("tickets").isEmpty());
		final List<NormalisationPass.Step> loyalty = result.stepsOf("loyalty");
		check("LOWER on loyalty, 31 rows change", loyalty.size() == 1 && CleaningSteps.LOWER.equals(loyalty.get(0).kind) && loyalty.get(0).valuesChanged == 31L
			&& loyalty.get(0).valuesSeen == 140L);
		check("LOWER is more certain than a title case", loyalty.get(0).confidence == 90);
	}

	/**
	 * Lot E (2026-10-17, coordinator addition (c)): capitals never win over a source that writes the field in title case, whatever the row counts -
	 * turning "Paris" into "PARIS" destroys what the source knew (live: ECOM lastName "Andr\u00e9" became "ANDR\u00c9" because the ticket source had more rows).
	 * With no title / mixed case anywhere, the majority between capitals and lower case still decides: nothing is destroyed by aligning those.
	 */
	private static void capitalsWinWhenTheyAreTheMajority()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "city", "A", NormalisationProfilerTest.counts("PARIS", 100, "LYON", 100)),
			NormalisationPassTest.column("b", "city", "B", NormalisationProfilerTest.counts("Paris", 30, "Lyon", 30))), CleaningSteps.Lookups.empty());
		check("the capitals source goes to title case, the title-case source is left alone, although capitals have the most rows",
			result.stepsOf("b").isEmpty() && result.stepsOf("a").size() == 1 && CleaningSteps.TITLE_CASE.equals(result.stepsOf("a").get(0).kind));
		check("the rationale says why", result.stepsOf("a").get(0).rationale.contains("would destroy that case"));
		final NormalisationPass.Result threeWays = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "name", "A", NormalisationProfilerTest.counts("DUPONT", 500, "ANDR\u00c9", 500)),
			NormalisationPassTest.column("b", "name", "B", NormalisationProfilerTest.counts("Dupont", 10, "Andr\u00e9", 10)),
			NormalisationPassTest.column("c", "name", "C", NormalisationProfilerTest.counts("dupont", 300, "andr\u00e9", 300))), CleaningSteps.Lookups.empty());
		check("capitals AND lower case both go to the title case that source B keeps", threeWays.stepsOf("a").size() == 1 && threeWays.stepsOf("c").size() == 1
			&& CleaningSteps.TITLE_CASE.equals(threeWays.stepsOf("a").get(0).kind) && CleaningSteps.TITLE_CASE.equals(threeWays.stepsOf("c").get(0).kind)
			&& threeWays.stepsOf("b").isEmpty());
		// nothing mixed anywhere: capitals against lower case, the majority decides and nothing is destroyed
		final NormalisationPass.Result noMixed = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "code", "A", NormalisationProfilerTest.counts("ABC", 100, "DEF", 100)),
			NormalisationPassTest.column("b", "code", "B", NormalisationProfilerTest.counts("abc", 30, "def", 30))), CleaningSteps.Lookups.empty());
		check("no source in title case: the majority (capitals) decides as before", noMixed.stepsOf("a").isEmpty() && noMixed.stepsOf("b").size() == 1
			&& CleaningSteps.UPPER.equals(noMixed.stepsOf("b").get(0).kind));
		check("no clear leader, no step", NormalisationPass.targetCase(10L, 10L, 10L, 30L) == null && NormalisationPass.targetCase(10L, 10L, 0L, 40L) == null);
	}

	private static void noCaseStepForOneSourceOrAReadingRegexOrAnExistingStep()
	{
		final NormalisationPass.Result oneSource = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "name", "A", NormalisationProfilerTest.counts("DUPONT", 10, "Martin", 10)),
			NormalisationPassTest.column("b", "name", "A", NormalisationProfilerTest.counts("DURAND", 10))), CleaningSteps.Lookups.empty());
		check("two mappings of ONE source system are not a disagreement between sources", oneSource.steps.isEmpty());

		final NormalisationPass.Column reading = NormalisationPassTest.column("t", "name", "T", NormalisationProfilerTest.counts("FOURNIER", 50, "DURAND", 50), "regex(\\p{Lu}+)");
		final NormalisationPass.Result regex = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("w", "name", "W", NormalisationProfilerTest.counts("Richard", 60, "Martin", 60)), reading), CleaningSteps.Lookups.empty());
		check("a pipeline whose regex reads case is never put behind a case change", regex.stepsOf("t").isEmpty());

		final Set<String> existing = new LinkedHashSet<String>(Arrays.asList(CleaningSteps.LOWER));
		final NormalisationPass.Column already = NormalisationPassTest.column("t", "name", "T", NormalisationProfilerTest.counts("FOURNIER", 50), "", existing);
		final NormalisationPass.Result kept = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("w", "name", "W", NormalisationProfilerTest.counts("Richard", 60)), already), CleaningSteps.Lookups.empty());
		check("a case step the mapping already has is respected", kept.stepsOf("t").isEmpty());

		final NormalisationPass.Column upperStep = NormalisationPassTest.column("t", "name", "T", NormalisationProfilerTest.counts("FOURNIER", 50), "trim|upper");
		final NormalisationPass.Result piped = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("w", "name", "W", NormalisationProfilerTest.counts("Richard", 60)), upperStep), CleaningSteps.Lookups.empty());
		check("a pipeline that already changes case is left to itself", piped.stepsOf("t").isEmpty());
	}

	// ------------------------------------------------------------------------------------------------- phones

	private static void phonesKeepThePlus()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("web", "phone", "WEB", NormalisationProfilerTest.counts("+33 6 46 86 05 23", 80, "+33 6 15 99 47 40", 80)),
			NormalisationPassTest.column("tickets", "phone", "TICKETS", NormalisationProfilerTest.counts("06.01.56.28.86", 120, "06.87.14.56.66", 114)),
			NormalisationPassTest.column("loyalty", "phone", "LOYALTY", NormalisationProfilerTest.counts("0660041941", 70, "0689355074", 70))), CleaningSteps.Lookups.empty());
		final List<NormalisationPass.Step> web = result.stepsOf("web");
		check("web: strip the space", web.size() == 1 && CleaningSteps.STRIP_CHARS.equals(web.get(0).kind) && " ".equals(web.get(0).argument));
		check("the plus is never in an argument", result.steps.toString().length() > 0 && noPlus(result));
		final List<NormalisationPass.Step> tickets = result.stepsOf("tickets");
		check("tickets: strip the dot", tickets.size() == 1 && ".".equals(tickets.get(0).argument));
		check("loyalty: digits only, nothing to strip", result.stepsOf("loyalty").isEmpty());
		check("the examples show shapes", web.get(0).examples.get(0).contains("+99 9 99 99 99 99") || web.get(0).examples.get(0).contains("->"));
		check("a phone gets no case step", web.size() == 1);
		// the step really keeps the plus
		final CleaningSteps.Result applied = CleaningSteps.applyStep(CleaningSteps.STRIP_CHARS, web.get(0).argument, "+33 6 46 86 05 23", null, null);
		check("+33 6 46 86 05 23 becomes +33646860523", "+33646860523".equals(applied.value));
	}

	private static boolean noPlus(final NormalisationPass.Result pResult)
	{
		for (final NormalisationPass.Step step : pResult.steps)
		{
			if (CleaningSteps.STRIP_CHARS.equals(step.kind) && step.argument != null && step.argument.indexOf('+') >= 0)
			{
				return false;
			}
		}
		return true;
	}

	// ------------------------------------------------------------------------------------------ the quality ones

	private static void whitespaceAndPlaceholders()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "city", "A", NormalisationProfilerTest.counts(" Paris", 5, "Lyon  Part Dieu", 3, "N/A", 2, "Nice", 20))), CleaningSteps.Lookups.empty());
		final Set<String> kinds = new LinkedHashSet<String>();
		for (final NormalisationPass.Step step : result.stepsOf("a"))
		{
			kinds.add(step.kind);
			check("every step of the pass starts with the marker", NormalisationPass.isOwnRationale(step.rationale));
		}
		check("trim, collapse and empty-to-null", kinds.equals(new LinkedHashSet<String>(Arrays.asList(CleaningSteps.TRIM, CleaningSteps.COLLAPSE_SPACES,
			CleaningSteps.EMPTY_TO_NULL))));
		final Set<String> existing = new LinkedHashSet<String>(Arrays.asList(CleaningSteps.TRIM));
		final NormalisationPass.Result again = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "city", "A", NormalisationProfilerTest.counts(" Paris", 5, "Lyon  Part Dieu", 3, "N/A", 2, "Nice", 20), "", existing)),
			CleaningSteps.Lookups.empty());
		boolean trimAgain = false;
		for (final NormalisationPass.Step step : again.stepsOf("a"))
		{
			trimAgain |= CleaningSteps.TRIM.equals(step.kind);
		}
		check("a step the mapping already has is not proposed again", !trimAgain);
	}

	private static void referenceTranscodingOnlyWithAReference()
	{
		final Map<String, List<String>> countries = new LinkedHashMap<String, List<String>>();
		countries.put("FR", Arrays.asList("France"));
		countries.put("DE", Arrays.asList("Allemagne", "Germany"));
		countries.put("ES", Arrays.asList("Espagne"));
		final Map<String, Map<String, String>> flat = new LinkedHashMap<String, Map<String, String>>();
		flat.put("COUNTRY", CleaningSteps.buildFlatTable(countries, null));
		final CleaningSteps.Lookups lookups = new CleaningSteps.Lookups(flat, null);
		final List<Detectors.ValueCount> values = NormalisationProfilerTest.counts("France", 50, "FR", 40, "Allemagne", 30, "ES", 20);
		final NormalisationPass.Result with = NormalisationPass.propose(Arrays.asList(NormalisationPassTest.column("a", "country", "A", values)), lookups);
		check("a configured reference that resolves 100%: COUNTRY_TO_ISO2", with.stepsOf("a").size() == 1 && CleaningSteps.COUNTRY_TO_ISO2.equals(with.stepsOf("a").get(0).kind)
			&& with.stepsOf("a").get(0).confidence == 100 && with.stepsOf("a").get(0).valuesChanged == 80L);
		final NormalisationPass.Result without = NormalisationPass.propose(Arrays.asList(NormalisationPassTest.column("a", "country", "A", values)), CleaningSteps.Lookups.empty());
		check("no reference, no transcoding", without.steps.isEmpty());
		final List<Detectors.ValueCount> city = NormalisationProfilerTest.counts("Paris", 50, "Lyon", 40, "Nice", 30, "France", 5);
		check("a column the reference mostly does not resolve is left alone", NormalisationPass.propose(Arrays.asList(NormalisationPassTest.column("a", "city", "A", city)), lookups).steps.isEmpty());
		final List<Detectors.ValueCount> alreadyCodes = NormalisationProfilerTest.counts("FR", 50, "DE", 40);
		check("codes already in the reference's form: nothing to rewrite", NormalisationPass.propose(Arrays.asList(NormalisationPassTest.column("a", "country", "A", alreadyCodes)), lookups).steps.isEmpty());
		final Map<String, Map<String, String>> other = new LinkedHashMap<String, Map<String, String>>();
		other.put("LEGAL_FORM", CleaningSteps.buildFlatTable(countries, null));
		final NormalisationPass.Result generic = NormalisationPass.propose(Arrays.asList(NormalisationPassTest.column("a", "form", "A", values)), new CleaningSteps.Lookups(other, null));
		check("any other kind is a TRANSCODE_REFERENCE with its kind as argument", generic.stepsOf("a").size() == 1
			&& CleaningSteps.TRANSCODE_REFERENCE.equals(generic.stepsOf("a").get(0).kind) && "{\"reference\":\"LEGAL_FORM\"}".equals(generic.stepsOf("a").get(0).argument));
	}

	private static void codesToAlignAreFlaggedNeverMapped()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("loyalty", "civility", "LOYALTY", NormalisationProfilerTest.counts("M.", 70, "MME", 70)),
			NormalisationPassTest.column("web", "civility", "WEB", NormalisationProfilerTest.counts("M", 80, "F", 80))), CleaningSteps.Lookups.empty());
		check("one alignment flag, no step", result.alignments.size() == 1 && result.steps.isEmpty());
		final NormalisationPass.Alignment alignment = result.alignments.get(0);
		check("it names both mappings", alignment.mappingIds.contains("loyalty") && alignment.mappingIds.contains("web"));
		check("counts, never a code", alignment.text.contains("2 distinct in LOYALTY") && alignment.text.contains("1 in common out of 3") && !alignment.text.contains("MME"));
		final NormalisationPass.Result sameCodes = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "level", "A", NormalisationProfilerTest.counts("Bronze", 70, "Argent", 70)),
			NormalisationPassTest.column("b", "level", "B", NormalisationProfilerTest.counts("BRONZE", 40, "ARGENT", 40))), CleaningSteps.Lookups.empty());
		check("same codes up to case: no alignment flag", sameCodes.alignments.isEmpty());
		final NormalisationPass.Result subset = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "level", "A", NormalisationProfilerTest.counts("Bronze", 70, "Argent", 70, "Or", 70)),
			NormalisationPassTest.column("b", "level", "B", NormalisationProfilerTest.counts("Bronze", 40, "Or", 40))), CleaningSteps.Lookups.empty());
		check("a source that uses a subset of the codes is not misaligned", subset.alignments.isEmpty());
		final NormalisationPass.Result oneSource = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "level", "A", NormalisationProfilerTest.counts("Bronze", 70, "Argent", 70, "Or", 70))), CleaningSteps.Lookups.empty());
		check("one source: nothing to align", oneSource.alignments.isEmpty());
	}

	private static void typedColumnsGetNoTextStep()
	{
		final NormalisationPass.Column date = new NormalisationPass.Column("m", "a", "birth", "birth", "S", "DATE", "", NormalisationProfiler.profile(
			NormalisationProfilerTest.counts(" 10/06/1963 ", 10), -1L), NormalisationProfilerTest.counts(" 10/06/1963 ", 10), -1L, new LinkedHashSet<String>());
		check("a DATE attribute: the verifier's business, no TRIM", NormalisationPass.propose(Arrays.asList(date), CleaningSteps.Lookups.empty()).steps.isEmpty());
	}

	private static void nothingToDoWhenTheValuesAgree()
	{
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "name", "A", NormalisationProfilerTest.counts("Richard", 60, "Martin", 60)),
			NormalisationPassTest.column("b", "name", "B", NormalisationProfilerTest.counts("Richard", 50, "Martin", 50, "Durand", 20))), CleaningSteps.Lookups.empty());
		check("two sources in the same case, clean values, widely shared: nothing", result.steps.isEmpty() && result.alignments.isEmpty());
		final NormalisationPass.Result overlap = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "level", "A", NormalisationProfilerTest.counts("Bronze", 70, "Argent", 70, "Or", 70)),
			NormalisationPassTest.column("b", "level", "B", NormalisationProfilerTest.counts("Bronze", 40, "Argent", 40, "Platine", 40))), CleaningSteps.Lookups.empty());
		check("two thirds shared codes (2 of 4) is not a misalignment", overlap.alignments.isEmpty());
	}

	// ----------------------------------------------------------------------------------------------- real files

	private static void theWholeB2cFiles() throws Exception
	{
		final File dir = new File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"));
		final File loyalty = new File(dir, "Fidelite_Membres.csv");
		final File tickets = new File(dir, "Magasins_Ventes.csv");
		if (!loyalty.isFile() || !tickets.isFile())
		{
			System.out.println("  (B2C files not found: real-file check skipped)");
			return;
		}
		final List<Map<String, String>> members = NormalisationProfilerTest.csv(loyalty, ',');
		final List<Map<String, String>> sales = NormalisationProfilerTest.csv(tickets, ';');
		final List<NormalisationPass.Column> columns = new ArrayList<NormalisationPass.Column>();
		columns.add(NormalisationPassTest.column("w-last", "lastName", "WEB", NormalisationPassTest.jsonStrings(new File(dir, "ECOM_Clients.json"), "lastName")));
		columns.add(NormalisationPassTest.column("w-phone", "phone", "WEB", NormalisationPassTest.jsonStrings(new File(dir, "ECOM_Clients.json"), "mobile")));
		columns.add(NormalisationPassTest.column("t-last", "lastName", "TICKETS", NormalisationProfilerTest.column(sales, "NOM")));
		columns.add(NormalisationPassTest.column("l-last", "lastName", "LOYALTY", NormalisationProfilerTest.column(members, "nom")));
		columns.add(NormalisationPassTest.column("t-phone", "phone", "TICKETS", NormalisationProfilerTest.column(sales, "TELEPHONE")));
		columns.add(NormalisationPassTest.column("l-phone", "phone", "LOYALTY", NormalisationProfilerTest.column(members, "telephone")));
		columns.add(NormalisationPassTest.column("t-mail", "email", "TICKETS", NormalisationProfilerTest.column(sales, "EMAIL")));
		columns.add(NormalisationPassTest.column("l-mail", "email", "LOYALTY", NormalisationProfilerTest.column(members, "email")));
		final NormalisationPass.Result result = NormalisationPass.propose(columns, CleaningSteps.Lookups.empty());
		check("real: web phones strip the space and keep the +", result.stepsOf("w-phone").size() == 1 && " ".equals(result.stepsOf("w-phone").get(0).argument));
		check("real: web last names (title case) get none", result.stepsOf("w-last").isEmpty());
		check("real: tickets last names (capitals) get TITLE_CASE", result.stepsOf("t-last").size() == 1 && CleaningSteps.TITLE_CASE.equals(result.stepsOf("t-last").get(0).kind));
		check("real: loyalty last names get none", result.stepsOf("l-last").isEmpty());
		// the ticket source has the most rows in the live project: the title case of the two others is still what is kept
		final List<Detectors.ValueCount> manyTickets = new ArrayList<Detectors.ValueCount>();
		for (final Detectors.ValueCount value : NormalisationProfilerTest.column(sales, "NOM"))
		{
			manyTickets.add(new Detectors.ValueCount(value.value, value.count * 50L));
		}
		final NormalisationPass.Result heavy = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("w-last", "lastName", "WEB", NormalisationPassTest.jsonStrings(new File(dir, "ECOM_Clients.json"), "lastName")),
			NormalisationPassTest.column("t-last", "lastName", "TICKETS", manyTickets),
			NormalisationPassTest.column("l-last", "lastName", "LOYALTY", NormalisationProfilerTest.column(members, "nom"))), CleaningSteps.Lookups.empty());
		check("real: tickets with 50 times the rows still get TITLE_CASE, the web names are never put in capitals", heavy.stepsOf("t-last").size() == 1
			&& CleaningSteps.TITLE_CASE.equals(heavy.stepsOf("t-last").get(0).kind) && heavy.stepsOf("w-last").isEmpty() && heavy.stepsOf("l-last").isEmpty());
		check("real: ticket phones strip the dot", result.stepsOf("t-phone").size() == 1 && ".".equals(result.stepsOf("t-phone").get(0).argument));
		check("real: loyalty phones need nothing", result.stepsOf("l-phone").isEmpty());
		check("real: loyalty e-mails get LOWER", result.stepsOf("l-mail").size() == 1 && CleaningSteps.LOWER.equals(result.stepsOf("l-mail").get(0).kind));
		check("real: ticket e-mails (already lower) get none", result.stepsOf("t-mail").isEmpty());
	}

	// ----------------------------------------------------------------------------------------------- helpers

	/** The values of one string key in the generator's indented JSON, with their rows. */
	static List<Detectors.ValueCount> jsonStrings(final File pFile, final String pKey) throws java.io.IOException
	{
		final Map<String, Long> byValue = new LinkedHashMap<String, Long>();
		final java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\"" + pKey + "\": \"([^\"]*)\"");
		for (final String line : java.nio.file.Files.readAllLines(pFile.toPath(), java.nio.charset.StandardCharsets.UTF_8))
		{
			final java.util.regex.Matcher matcher = pattern.matcher(line);
			if (matcher.find() && !matcher.group(1).isEmpty())
			{
				final Long seen = byValue.get(matcher.group(1));
				byValue.put(matcher.group(1), Long.valueOf(seen == null ? 1L : seen.longValue() + 1L));
			}
		}
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : byValue.entrySet())
		{
			out.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
		}
		return out;
	}

	/**
	 * Lot G2 (2026-10-17), measured live: a TITLE_CASE was written on a mapping whose pipeline is constant(HOME) - the landing column it nominally reads holds
	 * identifiers in capitals, which the staging never sees. A pipeline that does not read its column is neither profiled nor cleaned, and does not take part in
	 * the case alignment across sources.
	 */
	private static void mappingsThatDoNotReadTheirColumnGetNoStep()
	{
		check("a blank pipeline reads its column", NormalisationPass.readsColumn("", "COL") && NormalisationPass.readsColumn(null, "COL") && NormalisationPass.readsColumn("trim|upper", "COL"));
		check("constant(...) does not", !NormalisationPass.readsColumn("constant(HOME)", "COL") && !NormalisationPass.readsColumn("trim|constant('x')", "COL"));
		check("the clock and the run do not", !NormalisationPass.readsColumn("now()", "COL") && !NormalisationPass.readsColumn("runid", "COL"));
		check("the record's own state does not", !NormalisationPass.readsColumn("golden", "COL") && !NormalisationPass.readsColumn("mdmstate", "COL"));
		check("a concat that names the column reads it", NormalisationPass.readsColumn("concat(FIRST,LAST)", "last") && NormalisationPass.readsColumn("concat(/a, $.COL)", "COL"));
		check("a concat that does not name it does not", !NormalisationPass.readsColumn("concat(FIRST,LAST)", "COL"));
		check("without a column to compare, a concat is not judged", NormalisationPass.readsColumn("concat(FIRST,LAST)", ""));

		final List<Detectors.ValueCount> identifiers = NormalisationProfilerTest.counts("ECO-123456", 100, "ECO-654321", 60);
		final NormalisationPass.Result result = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "addressType", "WEB", identifiers, "constant(HOME)"),
			NormalisationPassTest.column("b", "addressType", "LOYALTY", NormalisationProfilerTest.counts("Home", 70, "Away", 70)),
			NormalisationPassTest.column("c", "addressType", "TICKETS", NormalisationProfilerTest.counts("Home", 70))), CleaningSteps.Lookups.empty());
		check("no step on the constant mapping, whatever its landing column holds", result.stepsOf("a").isEmpty());
		check("and it takes no part in the alignment: the two real sources agree, nothing to align", result.steps.isEmpty());
		// the same column read for real is cleaned as before
		final NormalisationPass.Result read = NormalisationPass.propose(Arrays.asList(
			NormalisationPassTest.column("a", "addressType", "WEB", identifiers),
			NormalisationPassTest.column("b", "addressType", "LOYALTY", NormalisationProfilerTest.counts("Home", 70, "Away", 70))), CleaningSteps.Lookups.empty());
		check("a mapping that reads its column still gets its case step", read.stepsOf("a").size() == 1 && CleaningSteps.TITLE_CASE.equals(read.stepsOf("a").get(0).kind));
	}

	private static NormalisationPass.Column column(final String pMappingId, final String pAttribute, final String pSource, final List<Detectors.ValueCount> pValues)
	{
		return NormalisationPassTest.column(pMappingId, pAttribute, pSource, pValues, "", new LinkedHashSet<String>());
	}

	private static NormalisationPass.Column column(final String pMappingId, final String pAttribute, final String pSource, final List<Detectors.ValueCount> pValues,
		final String pPipeline)
	{
		return NormalisationPassTest.column(pMappingId, pAttribute, pSource, pValues, pPipeline, new LinkedHashSet<String>());
	}

	private static NormalisationPass.Column column(final String pMappingId, final String pAttribute, final String pSource, final List<Detectors.ValueCount> pValues,
		final String pPipeline, final Set<String> pExistingKinds)
	{
		return new NormalisationPass.Column(pMappingId, pAttribute, pAttribute, pAttribute, pSource, "STRING", pPipeline, NormalisationProfiler.profile(pValues, -1L), pValues, -1L,
			pExistingKinds);
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("NormalisationPassTest: " + pLabel);
		}
	}
}
