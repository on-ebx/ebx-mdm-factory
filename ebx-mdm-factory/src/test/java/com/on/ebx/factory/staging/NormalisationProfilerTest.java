package com.on.ebx.factory.staging;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free, like the other tests of the module: run the main, a failure throws.
 * <p>
 * Profiler of the staging normalisation lot D, on the values of the B2C test files of 2026-10-17 (three fictional
 * customer files: web shop JSON, store tickets CSV, loyalty members CSV). The values are built here the way the files'
 * generator writes them, so the test needs no file; when the real files are present on the machine
 * ({@code -Dmdmf.b2cDir=...} or the default folder of the test project) the same checks also run on them.
 */
public final class NormalisationProfilerTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		NormalisationProfilerTest.birthDatesWrittenDayFirstAreProvenDayFirst();
		NormalisationProfilerTest.ambiguousSlashDatesAreNotProven();
		NormalisationProfilerTest.monthFirstIsProvenOnlyByAValueAboveTwelveInTheSecondPosition();
		NormalisationProfilerTest.mixedOrdersGiveNoCandidate();
		NormalisationProfilerTest.isoDatesAndDateTimes();
		NormalisationProfilerTest.phonesWithPlusNationalAndDotted();
		NormalisationProfilerTest.digitsOnlyIdentifiersAreNotPhones();
		NormalisationProfilerTest.datesAndDecimalsAreNotPhones();
		NormalisationProfilerTest.decimalCommaAndDot();
		NormalisationProfilerTest.caseKindsOfNamesAndEmails();
		NormalisationProfilerTest.codeListIsRecognisedOnlyWhenShortAndRepeated();
		NormalisationProfilerTest.spacesSentinelsAndAccents();
		NormalisationProfilerTest.factsLineNeverHoldsAValueUnlessAllowed();
		NormalisationProfilerTest.realFilesWhenPresent();
		NormalisationProfilerTest.odataDatesAreADateFamilyConvertedWithEpochdate();
		NormalisationProfilerTest.zeroPaddedPartnerNumbersAreNotPhones();
		System.out.println("NormalisationProfilerTest OK");
	}

	// ------------------------------------------------------------------------------------------------------ dates

	/** B2B test (2026-10-23): SAP CreationDate /Date(1598227200000)/ - the model proposed date(dd/MM/yyyy) and the value check measured 0 valid. */
	private static void odataDatesAreADateFamilyConvertedWithEpochdate()
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(NormalisationProfilerTest.counts("/Date(1598227200000)/", 3, "/Date(1430438400000)/", 2,
			"/Date(1735603200000+0100)/", 1), -1L);
		NormalisationProfilerTest.check("every value is a date of the OData family", profile.isDateLike() && profile.dateFamilies.get(NormalisationProfiler.ODATA).longValue() == 6L);
		NormalisationProfilerTest.check("not a number, not a phone", profile.numberRows() == 0L && profile.phoneLike == 0L);
		final String facts = profile.factsLine(false);
		NormalisationProfilerTest.check("the model is told the format and the step: " + facts,
			facts.contains("date written OData /Date(ms)/ (epoch milliseconds, UTC) (100%) - convert it with trim|epochdate"));
		final List<NormalisationProfiler.DateCandidate> candidates = NormalisationProfiler.dateCandidates(profile, false);
		NormalisationProfilerTest.check("one proven candidate", candidates.size() == 1 && candidates.get(0).certain && "trim|epochdate".equals(candidates.get(0).pipeline));
		NormalisationProfilerTest.check("a date-time target gets the pattern",
			"trim|epochdate(yyyy-MM-dd'T'HH:mm:ss)".equals(NormalisationProfiler.dateCandidates(profile, true).get(0).pipeline));
		NormalisationProfilerTest.check("a sensitive column keeps the format fact", profile.formatFactsLine().contains("OData /Date(ms)/"));
	}

	/** B2B test (2026-10-23): 0001690977 (SAP BusinessPartner, ten digits, zero-padded) was counted as a national phone; 00 followed by 0 is no number in any country. */
	private static void zeroPaddedPartnerNumbersAreNotPhones()
	{
		final NormalisationProfile partners = NormalisationProfiler.profile(NormalisationProfilerTest.counts("0001690977", 1, "0001691014", 1, "0001592791", 1, "0001700001", 1), -1L);
		NormalisationProfilerTest.check("zero-padded partner numbers are not phone-like", partners.phoneLike == 0L && !partners.isPhoneLike());
		NormalisationProfilerTest.check("with separators either", NormalisationProfiler.zeroAfterInternationalPrefix("00 01 69 09 77"));
		final NormalisationProfile phones = NormalisationProfiler.profile(NormalisationProfilerTest.counts("0033612345678", 2, "0612345678", 2, "+33612345678", 1), -1L);
		NormalisationProfilerTest.check("00 then a country code, a national 0 and a + stay phones", phones.phoneLike == 5L && phones.phoneWith00 == 2L);
		NormalisationProfilerTest.check("a + is never this case", !NormalisationProfiler.zeroAfterInternationalPrefix("+0001"));
		NormalisationProfilerTest.check("and the identity shape agrees", !IdentityShapes.phoneShaped("0001690977") && IdentityShapes.phoneShaped("0612345678"));
	}

	/** Fidelite_Membres.date_naissance: dd/MM/yyyy (the generator reverses an ISO date). */
	private static void birthDatesWrittenDayFirstAreProvenDayFirst()
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(NormalisationProfilerTest.counts("10/06/1963", 4, "17/02/1998", 3, "03/03/1981", 2, "28/12/1975", 1), -1L);
		NormalisationProfilerTest.check("all four are dates", profile.isDateLike() && profile.dateRows() == 10L);
		NormalisationProfilerTest.check("one family dd/MM/yyyy", profile.dateFamilies.size() == 1 && profile.dateFamilies.containsKey(NormalisationProfiler.DMY_SLASH));
		final long[] order = profile.dateOrder.get(NormalisationProfiler.DMY_SLASH);
		NormalisationProfilerTest.check("17/02 and 28/12 prove day first", order[0] == 4L && order[1] == 0L && order[2] == 6L);
		final List<NormalisationProfiler.DateCandidate> candidates = NormalisationProfiler.dateCandidates(profile, false);
		NormalisationProfilerTest.check("one certain candidate", candidates.size() == 1 && candidates.get(0).certain);
		NormalisationProfilerTest.check("the candidate is date(dd/MM/yyyy,yyyy-MM-dd)", "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(candidates.get(0).pipeline));
		NormalisationProfilerTest.check("the suggestion names it", "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(NormalisationProfiler.suggestedDatePipeline(profile)));
		final List<NormalisationProfiler.DateCandidate> timestamp = NormalisationProfiler.dateCandidates(profile, true);
		NormalisationProfilerTest.check("a date-time target gets the T pattern", "trim|date(dd/MM/yyyy,yyyy-MM-dd'T'HH:mm:ss)".equals(timestamp.get(0).pipeline));
	}

	private static void ambiguousSlashDatesAreNotProven()
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(NormalisationProfilerTest.counts("01/02/2020", 5, "03/04/2021", 5), -1L);
		final List<NormalisationProfiler.DateCandidate> candidates = NormalisationProfiler.dateCandidates(profile, false);
		NormalisationProfilerTest.check("a candidate exists but is not certain", candidates.size() == 1 && !candidates.get(0).certain);
		NormalisationProfilerTest.check("the order is called not provable",
			NormalisationProfiler.describeFamily(profile, NormalisationProfiler.DMY_SLASH).contains("not provable"));
		NormalisationProfilerTest.check("no pipeline is recommended in the facts", NormalisationProfiler.suggestedDatePipeline(profile) == null);
	}

	private static void monthFirstIsProvenOnlyByAValueAboveTwelveInTheSecondPosition()
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(NormalisationProfilerTest.counts("12/25/2020", 3, "01/02/2020", 2), -1L);
		final List<NormalisationProfiler.DateCandidate> candidates = NormalisationProfiler.dateCandidates(profile, false);
		NormalisationProfilerTest.check("MM/dd/yyyy when only the second position exceeds 12", candidates.size() == 1 && candidates.get(0).certain
			&& "trim|date(MM/dd/yyyy,yyyy-MM-dd)".equals(candidates.get(0).pipeline));
	}

	private static void mixedOrdersGiveNoCandidate()
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(NormalisationProfilerTest.counts("25/12/2020", 3, "12/25/2020", 2), -1L);
		NormalisationProfilerTest.check("day first and month first both proven: no single pattern", NormalisationProfiler.dateCandidates(profile, false).isEmpty());
	}

	private static void isoDatesAndDateTimes()
	{
		final NormalisationProfile iso = NormalisationProfiler.profile(NormalisationProfilerTest.counts("2026-08-27", 3, "2025-01-31", 1), -1L);
		NormalisationProfilerTest.check("ISO dates", iso.dateFamilies.size() == 1 && iso.dateFamilies.containsKey(NormalisationProfiler.ISO));
		final NormalisationProfile dateTime = NormalisationProfiler.profile(NormalisationProfilerTest.counts("2025-08-01T10:33:00Z", 2, "2025-08-02 11:00:00", 1), -1L);
		NormalisationProfilerTest.check("ISO date-times", dateTime.dateFamilies.size() == 1 && dateTime.dateFamilies.containsKey(NormalisationProfiler.ISO_DT));
		NormalisationProfilerTest.check("an ISO date-time to a date is isodate, to a timestamp isodatetime",
			"trim|isodate".equals(NormalisationProfiler.dateCandidates(dateTime, false).get(0).pipeline)
				&& "trim|isodatetime".equals(NormalisationProfiler.dateCandidates(dateTime, true).get(0).pipeline));
		final NormalisationProfile impossible = NormalisationProfiler.profile(NormalisationProfilerTest.counts("2026-13-45", 2), -1L);
		NormalisationProfilerTest.check("an impossible ISO date is no date", impossible.dateRows() == 0L);
		final NormalisationProfile compact = NormalisationProfiler.profile(NormalisationProfilerTest.counts("19630610", 2, "19980217", 2), -1L);
		NormalisationProfilerTest.check("compact yyyyMMdd", "trim|date(yyyyMMdd,yyyy-MM-dd)".equals(NormalisationProfiler.dateCandidates(compact, false).get(0).pipeline));
		final NormalisationProfile slashTime = NormalisationProfiler.profile(NormalisationProfilerTest.counts("25/12/2020 09:05:00", 2, "26/12/2020 10:00:00", 1), -1L);
		NormalisationProfilerTest.check("a time part gives a quoted pattern",
			"trim|date('dd/MM/yyyy HH:mm:ss',yyyy-MM-dd'T'HH:mm:ss)".equals(NormalisationProfiler.dateCandidates(slashTime, true).get(0).pipeline));
	}

	// ----------------------------------------------------------------------------------------------------- phones

	private static void phonesWithPlusNationalAndDotted()
	{
		final NormalisationProfile web = NormalisationProfiler.profile(NormalisationProfilerTest.counts("+33 6 46 86 05 23", 5, "+33 6 15 99 47 40", 5), -1L);
		NormalisationProfilerTest.check("web phones are phone-like, all with +", web.isPhoneLike() && web.phoneWithPlus == 10L);
		NormalisationProfilerTest.check("the separator is the space, not the +", " ".equals(web.phoneSeparators));
		final NormalisationProfile tickets = NormalisationProfiler.profile(NormalisationProfilerTest.counts("06.01.56.28.86", 4, "06.87.14.56.66", 4), -1L);
		NormalisationProfilerTest.check("dotted national phones", tickets.isPhoneLike() && tickets.phoneNationalZero == 8L && ".".equals(tickets.phoneSeparators));
		final NormalisationProfile loyalty = NormalisationProfiler.profile(NormalisationProfilerTest.counts("0660041941", 3, "0689355074", 2), -1L);
		NormalisationProfilerTest.check("plain national digits starting with 0", loyalty.isPhoneLike() && loyalty.phoneSeparators.isEmpty() && loyalty.phoneNationalZero == 5L);
		final NormalisationProfile international = NormalisationProfiler.profile(NormalisationProfilerTest.counts("0033646860523", 3), -1L);
		NormalisationProfilerTest.check("00 prefix counted", international.phoneWith00 == 3L);
	}

	private static void digitsOnlyIdentifiersAreNotPhones()
	{
		final NormalisationProfile ids = NormalisationProfiler.profile(NormalisationProfilerTest.counts("12345678", 3, "98765432", 3), -1L);
		NormalisationProfilerTest.check("digits not starting with 0 are identifiers, not phones", !ids.isPhoneLike());
	}

	private static void datesAndDecimalsAreNotPhones()
	{
		final NormalisationProfile dates = NormalisationProfiler.profile(NormalisationProfilerTest.counts("10/06/1963", 4), -1L);
		NormalisationProfilerTest.check("a date is no phone", !dates.isPhoneLike());
		final NormalisationProfile decimals = NormalisationProfiler.profile(NormalisationProfilerTest.counts("1234567.89", 4), -1L);
		NormalisationProfilerTest.check("a decimal is no phone", !decimals.isPhoneLike());
	}

	// ---------------------------------------------------------------------------------------------------- numbers

	/** Magasins_Ventes.MONTANT_TTC: 117,70 */
	private static void decimalCommaAndDot()
	{
		final NormalisationProfile comma = NormalisationProfiler.profile(NormalisationProfilerTest.counts("117,70", 3, "233,04", 3, "9,00", 1), -1L);
		NormalisationProfilerTest.check("decimal comma", comma.numberShapes.size() == 1 && comma.numberShapes.containsKey("DEC_COMMA"));
		final NormalisationProfile mix = NormalisationProfiler.profile(NormalisationProfilerTest.counts("1.234,56", 2, "1,234.56", 2, "12.5", 1, "7", 1), -1L);
		NormalisationProfilerTest.check("thousand shapes told apart",
			mix.numberShapes.containsKey("THOUSAND_DOT_COMMA") && mix.numberShapes.containsKey("THOUSAND_COMMA_DOT") && mix.numberShapes.containsKey("DEC_DOT")
				&& mix.numberShapes.containsKey("INT"));
	}

	// ------------------------------------------------------------------------------------------------------- case

	private static void caseKindsOfNamesAndEmails()
	{
		final NormalisationProfile tickets = NormalisationProfiler.profile(NormalisationProfilerTest.counts("FOURNIER", 5, "DURAND", 4, "LE GALL", 2), -1L);
		NormalisationProfilerTest.check("capitals", NormalisationProfile.CASE_UPPER.equals(tickets.dominantCase()) && tickets.upper == 11L);
		final NormalisationProfile web = NormalisationProfiler.profile(NormalisationProfilerTest.counts("Richard", 5, "Jean-Pierre", 2, "O'Brien", 1, "de la Fontaine", 1), -1L);
		NormalisationProfilerTest.check("title case with hyphen and apostrophe", web.title == 8L && web.mixed == 1L);
		final NormalisationProfile emails = NormalisationProfiler.profile(NormalisationProfilerTest.counts("julie.richard62@free.fr", 8, "CHLOE.MICHEL88@ORANGE.FR", 2), -1L);
		NormalisationProfilerTest.check("80% lower, 20% upper", emails.lower == 8L && emails.upper == 2L && NormalisationProfile.CASE_LOWER.equals(emails.dominantCase()));
		final NormalisationProfile mixedEmail = NormalisationProfiler.profile(NormalisationProfilerTest.counts("Jean.Dupont@x.fr", 3), -1L);
		NormalisationProfilerTest.check("a capital in a lower e-mail is mixed, not title", mixedEmail.mixed == 3L);
		final NormalisationProfile codes = NormalisationProfiler.profile(NormalisationProfilerTest.counts("75011", 3, "69003", 3), -1L);
		NormalisationProfilerTest.check("digits have no case", codes.letterless == 6L && !codes.mostlyLetters());
		final NormalisationProfile uncased = NormalisationProfiler.profile(NormalisationProfilerTest.counts("北京", 4), -1L);
		NormalisationProfilerTest.check("a script without case has none", uncased.letterBearing() == 0L && uncased.letterless == 4L);
	}

	// -------------------------------------------------------------------------------------------------- codes etc.

	private static void codeListIsRecognisedOnlyWhenShortAndRepeated()
	{
		final NormalisationProfile level = NormalisationProfiler.profile(NormalisationProfilerTest.counts("Bronze", 50, "Argent", 45, "Or", 45), 3L);
		NormalisationProfilerTest.check("three repeated codes", level.isCodeList() && level.codes.size() == 3);
		final NormalisationProfile unique = NormalisationProfiler.profile(NormalisationProfilerTest.counts("a1", 1, "a2", 1, "a3", 1), 3L);
		NormalisationProfilerTest.check("values seen once are not a code list", !unique.isCodeList());
		final NormalisationProfile sampled = NormalisationProfiler.profile(NormalisationProfilerTest.counts("a", 10, "b", 10), 5000L);
		NormalisationProfilerTest.check("a sample of a wide column is not a code list", sampled.sampled && !sampled.isCodeList());
	}

	private static void spacesSentinelsAndAccents()
	{
		final NormalisationProfile profile = NormalisationProfiler.profile(NormalisationProfilerTest.counts(" Paris", 2, "Lyon  Part Dieu", 3, "N/A", 4, "n/a", 1, "Montélimar", 2, "Nice", 5), -1L);
		NormalisationProfilerTest.check("leading space counted", profile.leadingTrailing == 2L);
		NormalisationProfilerTest.check("inner run counted", profile.innerRuns == 3L);
		NormalisationProfilerTest.check("the N/A sentinel, whatever its case", profile.sentinels.size() == 1 && profile.sentinels.get("N/A").longValue() == 5L);
		NormalisationProfilerTest.check("accent counted", profile.accented == 2L);
	}

	private static void factsLineNeverHoldsAValueUnlessAllowed()
	{
		final NormalisationProfile level = NormalisationProfiler.profile(NormalisationProfilerTest.counts("Bronze", 50, "Argent", 45, "Or", 45), 3L);
		NormalisationProfilerTest.check("without values: counts only", !level.factsLine(false).contains("Bronze") && level.factsLine(false).contains("3 repeated codes"));
		NormalisationProfilerTest.check("with values: the codes", level.factsLine(true).contains("Bronze"));
		final NormalisationProfile dates = NormalisationProfiler.profile(NormalisationProfilerTest.counts("10/06/1963", 4, "17/02/1998", 3), -1L);
		final String facts = dates.factsLine(false);
		NormalisationProfilerTest.check("the facts name the pattern and the right step, never a date", facts.contains("dd/MM/yyyy") && facts.contains("trim|date(dd/MM/yyyy,yyyy-MM-dd)")
			&& !facts.contains("1963"));
		final NormalisationProfile phones = NormalisationProfiler.profile(NormalisationProfilerTest.counts("+33 6 46 86 05 23", 5), -1L);
		NormalisationProfilerTest.check("phone fact keeps the plus", phones.factsLine(false).contains("never strip the leading +") && !phones.factsLine(false).contains("46"));
	}

	// ------------------------------------------------------------------------------------------------- real files

	private static void realFilesWhenPresent() throws IOException
	{
		final File dir = new File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"));
		final File loyalty = new File(dir, "Fidelite_Membres.csv");
		final File tickets = new File(dir, "Magasins_Ventes.csv");
		if (!loyalty.isFile() || !tickets.isFile())
		{
			System.out.println("  (B2C files not found: real-file checks skipped)");
			return;
		}
		final List<Map<String, String>> members = NormalisationProfilerTest.csv(loyalty, ',');
		final NormalisationProfile birth = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "date_naissance"), -1L);
		NormalisationProfilerTest.check("real loyalty birth dates are 100% dd/MM/yyyy, day first", birth.isDateLike() && birth.dateFamilies.size() == 1
			&& birth.dateOrder.get(NormalisationProfiler.DMY_SLASH)[0] > 0L && birth.dateOrder.get(NormalisationProfiler.DMY_SLASH)[1] == 0L);
		final NormalisationProfile membership = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "date_adhesion"), -1L);
		NormalisationProfilerTest.check("real membership dates are ISO", membership.dateFamilies.size() == 1 && membership.dateFamilies.containsKey(NormalisationProfiler.ISO));
		final NormalisationProfile phone = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "telephone"), -1L);
		NormalisationProfilerTest.check("real loyalty phones are national digits", phone.isPhoneLike() && phone.phoneSeparators.isEmpty());
		final NormalisationProfile city = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "ville"), -1L);
		NormalisationProfilerTest.check("real loyalty cities are in capitals", NormalisationProfile.CASE_UPPER.equals(city.dominantCase()));
		final NormalisationProfile level = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "niveau"), -1L);
		NormalisationProfilerTest.check("real loyalty level is a 3-code list", level.codes.size() == 3);
		final NormalisationProfile email = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "email"), -1L);
		NormalisationProfilerTest.check("real loyalty e-mails: mostly lower (109), some upper (31)", email.lower == 109L && email.upper == 31L);
		final List<Map<String, String>> sales = NormalisationProfilerTest.csv(tickets, ';');
		final NormalisationProfile amount = NormalisationProfiler.profile(NormalisationProfilerTest.column(sales, "MONTANT_TTC"), -1L);
		NormalisationProfilerTest.check("real ticket amounts are decimal comma", amount.numberShapes.containsKey("DEC_COMMA") && amount.numberShapes.size() == 1);
		final NormalisationProfile dotted = NormalisationProfiler.profile(NormalisationProfilerTest.column(sales, "TELEPHONE"), -1L);
		NormalisationProfilerTest.check("real ticket phones are dotted", dotted.isPhoneLike() && ".".equals(dotted.phoneSeparators));
		// lot E (2026-10-17, coordinator addition (b)): the phone attribute across ALL its sources - web (+33), tickets (06.01...), loyalty (0612...)
		final NormalisationProfile webMobile = NormalisationProfiler.profile(NormalisationPassTest.jsonStrings(new File(dir, "ECOM_Clients.json"), "mobile"), -1L);
		final NormalisationProfiler.PhoneMix mix = NormalisationProfiler.phoneMix(Arrays.asList(webMobile, dotted, phone));
		NormalisationProfilerTest.check("real phones across 3 sources are phone-like with national numbers: " + mix.sentence(), mix.isPhoneLike() && mix.sources == 3
			&& mix.needsDefaultCountry() && mix.internationalRows == 160L);
		NormalisationProfilerTest.check("the sentence says a default country is needed, with shares and no number: " + mix.sentence(),
			mix.sentence().contains("national format") && mix.sentence().contains("default country") && !mix.sentence().matches(".*\\d{7,}.*"));
		NormalisationProfilerTest.check("web alone is all international, so a blank default would be right there",
			!NormalisationProfiler.phoneMix(Arrays.asList(webMobile)).needsDefaultCountry());
		NormalisationProfilerTest.check("a non-phone attribute has no sentence", NormalisationProfiler.phoneMix(Arrays.asList(NormalisationProfiler.profile(
			NormalisationProfilerTest.counts("Dupont", 10), -1L))).sentence().isEmpty() && NormalisationProfiler.phoneMix(null).sentence().isEmpty());
		final NormalisationProfile name = NormalisationProfiler.profile(NormalisationProfilerTest.column(sales, "NOM"), -1L);
		NormalisationProfilerTest.check("real ticket last names are in capitals", NormalisationProfile.CASE_UPPER.equals(name.dominantCase()));
		final NormalisationProfile loyaltyName = NormalisationProfiler.profile(NormalisationProfilerTest.column(members, "nom"), -1L);
		NormalisationProfilerTest.check("real loyalty last names are title case", NormalisationProfile.CASE_TITLE.equals(loyaltyName.dominantCase()));
	}

	// ----------------------------------------------------------------------------------------------------- helpers

	static List<Detectors.ValueCount> counts(final Object... pPairs)
	{
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			out.add(new Detectors.ValueCount((String) pPairs[i], ((Integer) pPairs[i + 1]).longValue()));
		}
		return out;
	}

	static List<Detectors.ValueCount> column(final List<Map<String, String>> pRows, final String pColumn)
	{
		final Map<String, Long> byValue = new LinkedHashMap<String, Long>();
		for (final Map<String, String> row : pRows)
		{
			final String value = row.get(pColumn);
			if (value == null || value.isEmpty())
			{
				continue;
			}
			final Long seen = byValue.get(value);
			byValue.put(value, Long.valueOf(seen == null ? 1L : seen.longValue() + 1L));
		}
		final List<Detectors.ValueCount> out = new ArrayList<Detectors.ValueCount>();
		for (final Map.Entry<String, Long> entry : byValue.entrySet())
		{
			out.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
		}
		return out;
	}

	/** The generator's CSV: no quoted separator inside a value. */
	static List<Map<String, String>> csv(final File pFile, final char pSeparator) throws IOException
	{
		final List<String> lines = Files.readAllLines(pFile.toPath(), StandardCharsets.UTF_8);
		final String[] header = lines.get(0).split(String.valueOf(pSeparator), -1);
		final List<Map<String, String>> out = new ArrayList<Map<String, String>>();
		for (int i = 1; i < lines.size(); i++)
		{
			if (lines.get(i).isEmpty())
			{
				continue;
			}
			final String[] cells = lines.get(i).split(String.valueOf(pSeparator), -1);
			final Map<String, String> row = new LinkedHashMap<String, String>();
			for (int c = 0; c < header.length && c < cells.length; c++)
			{
				row.put(header[c], cells[c]);
			}
			out.add(row);
		}
		return out;
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("NormalisationProfilerTest: " + pLabel);
		}
	}
}
