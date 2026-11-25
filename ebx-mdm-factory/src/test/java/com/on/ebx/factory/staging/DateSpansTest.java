package com.on.ebx.factory.staging;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free, like the rest of this module's tests. Lot F2b (2026-10-19): the SPAN of a date column (earliest and latest month, rows in the last 24 months) given in the inventory, the
 * comparison of the spans of the columns that feed one date attribute (follow-up turn and step-4 open point, EN and FR), and the way a corrected feed replaces a rule. Run 8 of 2026-10-19:
 * {@code Personne.birthDate} fed from two sources' birth dates AND from a ticket table's purchase date. Facts only: every test also checks what stays silent.
 */
public final class DateSpansTest
{
	private static final LocalDate TODAY = LocalDate.of(2026, 10, 19);

	public static void main(final String[] pArgs) throws Exception
	{
		DateSpansTest.spanOfAColumn();
		DateSpansTest.dayAndMonthOrder();
		DateSpansTest.notADateColumn();
		DateSpansTest.theInventoryCarriesTheSpan();
		DateSpansTest.nonOverlappingSpansOnOneAttribute();
		DateSpansTest.overlappingOrSingleSpansAreSilent();
		DateSpansTest.theFollowUpStatesTheFactAndBothAnswers();
		DateSpansTest.aCorrectedFeedReplacesTheRuleNeverDuplicatesIt();
		DateSpansTest.theOpenPointReadsInBothLanguages();
		DateSpansTest.odataDatesHaveASpan();
		System.out.println("DateSpansTest OK");
	}

	/** B2B test (2026-10-23): SAP CreationDate /Date(ms)/ (UTC milliseconds; the offset does not move the month). */
	private static void odataDatesHaveASpan()
	{
		final DateSpans.Span span = DateSpansTest.span("/Date(1598227200000)/", "/Date(1430438400000)/", "/Date(1735603200000+0100)/");
		IdentityFactsTest.check("an OData column has a span: " + (span == null ? null : span.inventoryText()), span != null && span.filledRows == 3L
			&& span.inventoryText().startsWith("values from 2015-05 to 2024-12"));
	}

	private static List<Detectors.ValueCount> counted(final String... pValues)
	{
		final Map<String, Long> byValue = new LinkedHashMap<String, Long>();
		for (final String value : pValues)
		{
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

	private static DateSpans.Span span(final String... pValues)
	{
		return DateSpans.measure(DateSpansTest.counted(pValues), false, DateSpansTest.TODAY);
	}

	// ------------------------------------------------------------------------------------------------------------ span

	private static void spanOfAColumn()
	{
		final DateSpans.Span birth = DateSpansTest.span("1951-03-02", "1980-07-15", "2005-12-30", "1980-07-15", "1999-01-31");
		IdentityFactsTest.check("an ISO column: month precision, rows counted: " + birth.inventoryText(), birth.monthPrecision && "values from 1951-03 to 2005-12 (filled rows: 5)".equals(birth.inventoryText())
			&& "1951-03..2005-12".equals(birth.range()) && birth.recentRows == 0L);
		final DateSpans.Span purchases = DateSpansTest.span("2026-01-05", "2026-10-12", "2026-10-12", "2024-11-03", "2023-01-01");
		IdentityFactsTest.check("rows in the last 24 months (from 2024-10) when above 0: " + purchases.inventoryText(), "values from 2023-01 to 2026-10 (filled rows: 5), 4 rows in the last 24 months".equals(purchases.inventoryText()));
		final DateSpans.Span single = DateSpansTest.span("2026-10-12");
		IdentityFactsTest.check("one row says 'row'", single.inventoryText().endsWith(", 1 row in the last 24 months"));
		IdentityFactsTest.check("a date-time and a blank are read: only filled rows count", "values from 2025-02 to 2026-03 (filled rows: 2), 2 rows in the last 24 months".equals(
			DateSpansTest.span("2025-02-03T10:15:00Z", "2026-03-04 08:00:00", "", " ").inventoryText()));
		IdentityFactsTest.check("a compact yyyyMMdd is a date", DateSpansTest.span("19800512", "20050101").range().equals("1980-05..2005-01"));
		final DateSpans.Span partial = DateSpans.measure(DateSpansTest.counted("2026-01-05", "2026-10-12"), true, DateSpansTest.TODAY);
		IdentityFactsTest.check("a column read on its most frequent values says so: " + partial.inventoryText(), partial.inventoryText().startsWith("values from 2026-01 to 2026-10 (filled rows: 2; measured on the 2 most frequent distinct values)"));
		IdentityFactsTest.check("no day and no value in the text", !birth.inventoryText().contains("1980-07") && !birth.inventoryText().contains("02"));
	}

	private static void dayAndMonthOrder()
	{
		final DateSpans.Span dayFirst = DateSpansTest.span("25/12/1950", "03/06/1980", "31/01/2006");
		IdentityFactsTest.check("a first part above 12 proves day first: month precision: " + dayFirst.range(), dayFirst.monthPrecision && "1950-12..2006-01".equals(dayFirst.range()));
		final DateSpans.Span monthFirst = DateSpansTest.span("12/25/1950", "06/03/1980", "01/31/2006");
		IdentityFactsTest.check("a second part above 12 proves month first: " + monthFirst.range(), monthFirst.monthPrecision && "1950-12..2006-01".equals(monthFirst.range()));
		final DateSpans.Span unproven = DateSpansTest.span("03/06/1950", "04/05/1980", "02/01/2006");
		IdentityFactsTest.check("no part above 12: the order is not guessed, the span is kept at the year: " + unproven.inventoryText(), !unproven.monthPrecision
			&& "1950..2006".equals(unproven.range()) && "values from 1950 to 2006 (filled rows: 3)".equals(unproven.inventoryText()));
		final DateSpans.Span both = DateSpansTest.span("25/12/1950", "12/25/1980");
		IdentityFactsTest.check("both orders proven (a mixture): year precision", !both.monthPrecision);
		IdentityFactsTest.check("a year-only span overlaps by the year", unproven.overlaps(DateSpansTest.span("1950-06-01")) && !unproven.overlaps(DateSpansTest.span("2007-01-01")));
		IdentityFactsTest.check("a date that exists in neither order is no date", DateSpansTest.span("31/31/1980", "99/99/1999") == null);
	}

	private static void notADateColumn()
	{
		IdentityFactsTest.check("amounts", DateSpansTest.span("12,50", "8,00", "199,90") == null);
		IdentityFactsTest.check("identifiers", DateSpansTest.span("WEB-100001", "WEB-100002", "T12345678") == null);
		IdentityFactsTest.check("8-digit numbers that are not dates", DateSpansTest.span("12345678", "98765432", "55555555") == null);
		IdentityFactsTest.check("nothing filled", DateSpansTest.span("", " ") == null && DateSpans.measure(null, false, DateSpansTest.TODAY) == null);
		final List<Detectors.ValueCount> seventyNine = Arrays.asList(new Detectors.ValueCount("2026-01-05", 79L), new Detectors.ValueCount("n/a", 21L));
		IdentityFactsTest.check("below " + DateSpans.MIN_DATE_PERCENT + "% of the filled rows parsing as dates: not a date column", DateSpans.measure(seventyNine, false, DateSpansTest.TODAY) == null);
		final List<Detectors.ValueCount> eighty = Arrays.asList(new Detectors.ValueCount("2026-01-05", 80L), new Detectors.ValueCount("n/a", 20L));
		IdentityFactsTest.check("at " + DateSpans.MIN_DATE_PERCENT + "% it is", DateSpans.measure(eighty, false, DateSpansTest.TODAY) != null && DateSpans.measure(eighty, false, DateSpansTest.TODAY).filledRows == 80L);
	}

	// ------------------------------------------------------------------------------------------------------- fixtures

	private static StagingRules.LandingColumnInfo dateColumn(final String pName, final DateSpans.Span pSpan)
	{
		final StagingRules.LandingColumnInfo column = IdentityFactsTest.column(pName, false, null, 5L);
		column.dateSpan = pSpan;
		return column;
	}

	/** Birth dates in two sources, purchase dates in the ticket table; row counts as in the B2C fixture. */
	private static StagingLlmPass.ProjectInfo project(final String pStoresDate)
	{
		final StagingRules.LandingTableInfo ecom = IdentityFactsTest.table("ECOM", "customers", 160L, IdentityFactsTest.column("customerId", true, "SOURCE", 160L),
			DateSpansTest.dateColumn("birth_date", DateSpansTest.span("1951-03-02", "1980-07-15", "2005-12-30")));
		final StagingRules.LandingTableInfo loyalty = IdentityFactsTest.table("LOYALTY", "Fidelite_Membres", 140L, IdentityFactsTest.column("card_number", true, "SOURCE", 140L),
			DateSpansTest.dateColumn("date_naissance", DateSpansTest.span("25/12/1950", "03/06/1980", "31/01/2006")));
		final StagingRules.LandingTableInfo stores = IdentityFactsTest.table("STORES", "Magasins_Ventes", 234L, IdentityFactsTest.column("NUM_TICKET", true, "SOURCE", 234L),
			DateSpansTest.dateColumn("DATE_ACHAT", DateSpansTest.span(pStoresDate.split(","))),
			DateSpansTest.dateColumn("DATE_NAISSANCE", DateSpansTest.span("1960-04-01", "1999-09-09")));
		return new StagingLlmPass.ProjectInfo("B2C", "B2C", Arrays.asList(ecom, loyalty, stores), Collections.<StagingRules.RelationInfo>emptyList(),
			Arrays.asList(new StagingLlmPass.RuleEntityInfo("Ecom", "ECOM", "customers"), new StagingLlmPass.RuleEntityInfo("Loyalty", "LOYALTY", "Fidelite_Membres"),
				new StagingLlmPass.RuleEntityInfo("Stores", "STORES", "Magasins_Ventes")));
	}

	private static void personEntity(final StagingLlmPass.Session pSession, final String pBirthType)
	{
		final ObjectNode args = IdentityFactsTest.MAPPER.createObjectNode();
		args.put("name", "Personne");
		args.put("label", "Personne");
		final ArrayNode attributes = args.putArray("attributes");
		final ObjectNode id = attributes.addObject();
		id.put("name", "externalId").put("label", "externalId").put("dataType", "STRING");
		final ObjectNode birth = attributes.addObject();
		birth.put("name", "birthDate").put("label", "birthDate").put("dataType", pBirthType);
		args.put("confidence", 90);
		args.put("rationale", "One real-world concept.");
		final LocalTool.Result result = pSession.proposeTargetEntity(args);
		IdentityFactsTest.check("entity: " + result.text, !result.isError);
	}

	private static LocalTool.Result dateFeed(final StagingLlmPass.Session pSession, final String pTable, final String pColumn, final String pPipeline, final boolean pRemove)
	{
		final ObjectNode args = IdentityFactsTest.MAPPER.createObjectNode();
		args.put("entity", "Personne");
		args.put("landingTable", pTable);
		final ArrayNode rules = args.putArray("rules");
		final ObjectNode rule = rules.addObject();
		rule.put("attribute", "birthDate");
		rule.put("landingColumn", pColumn);
		if (pPipeline != null)
		{
			rule.put("pipeline", pPipeline);
		}
		if (pRemove)
		{
			rule.put("remove", true);
		}
		rule.put("rationale", "A date.");
		rule.put("confidence", 80);
		args.put("confidence", 85);
		args.put("rationale", "Read as it stands.");
		return pSession.proposeFeed(args);
	}

	private static StagingLlmPass.Session session(final String pStoresDate, final boolean pFeedStores)
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(DateSpansTest.project(pStoresDate), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		DateSpansTest.personEntity(session, "DATE");
		IdentityFactsTest.check("ecom feed", !DateSpansTest.dateFeed(session, "ECOM.customers", "birth_date", "date(yyyy-MM-dd)", false).isError);
		IdentityFactsTest.check("loyalty feed", !DateSpansTest.dateFeed(session, "LOYALTY.Fidelite_Membres", "date_naissance", "date(dd/MM/yyyy)", false).isError);
		if (pFeedStores)
		{
			final LocalTool.Result stores = DateSpansTest.dateFeed(session, "STORES.Magasins_Ventes", "DATE_ACHAT", "date(yyyy-MM-dd)", false);
			IdentityFactsTest.check("stores feed: " + stores.text, !stores.isError);
		}
		return session;
	}

	private static final String PURCHASES = "2026-01-05,2026-10-12,2026-10-12,2026-05-30";

	private static void theInventoryCarriesTheSpan()
	{
		final String prompt = StagingLlmPass.userPrompt(DateSpansTest.project(DateSpansTest.PURCHASES));
		final String birth = prompt.substring(prompt.indexOf("    - birth_date (type"), prompt.indexOf("\n", prompt.indexOf("    - birth_date (type")));
		IdentityFactsTest.check("a birth date column: its span, no recent rows: " + birth, "    - birth_date (type STRING, fill rate 100%, values from 1951-03 to 2005-12 (filled rows: 3))".equals(birth));
		final String loyalty = prompt.substring(prompt.indexOf("    - date_naissance (type"), prompt.indexOf("\n", prompt.indexOf("    - date_naissance (type")));
		IdentityFactsTest.check("a dd/MM/yyyy column whose day order its values prove: " + loyalty, loyalty.contains("values from 1950-12 to 2006-01 (filled rows: 3)"));
		final String purchases = prompt.substring(prompt.indexOf("    - DATE_ACHAT (type"), prompt.indexOf("\n", prompt.indexOf("    - DATE_ACHAT (type")));
		IdentityFactsTest.check("purchase dates: the span and the recent rows: " + purchases, purchases.contains("values from 2026-01 to 2026-10 (filled rows: 4), 4 rows in the last 24 months"));
		IdentityFactsTest.check("a column that is not a date has none", !prompt.substring(prompt.indexOf("customerId (type"), prompt.indexOf("birth_date (type")).contains("values from"));
		// a sensitive column keeps its format fact and gets the span too (a month, never a day or a value)
		final StagingRules.LandingColumnInfo sensitive = DateSpansTest.dateColumn("date_naissance", DateSpansTest.span("25/12/1950", "03/06/1980"));
		sensitive.profileFacts = ", measured: date written dd/MM/yyyy (100%)";
		final String described = StagingLlmPass.describeColumn(sensitive);
		IdentityFactsTest.check("format then span: " + described, described.endsWith(", measured: date written dd/MM/yyyy (100%), values from 1950-12 to 1980-06 (filled rows: 2))"));
	}

	// ------------------------------------------------------------------------------------------------- one attribute

	private static void nonOverlappingSpansOnOneAttribute()
	{
		final StagingLlmPass.Session session = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		final List<DateSpans.Disjoint> disjoint = session.dateSpanDisjoints();
		IdentityFactsTest.check("one attribute, two groups: " + disjoint.size(), disjoint.size() == 1 && "Personne".equals(disjoint.get(0).entity) && "birthDate".equals(disjoint.get(0).attribute)
			&& disjoint.get(0).groups.size() == 2 && disjoint.get(0).groups.get(0).equals(Arrays.asList(Integer.valueOf(0), Integer.valueOf(1))) && disjoint.get(0).groups.get(1).equals(Arrays.asList(Integer.valueOf(2))));
		IdentityFactsTest.check("the sentence: " + disjoint.get(0).followUpText(), disjoint.get(0).followUpText().equals("Personne.birthDate is fed from ECOM.customers.birth_date (1951-03..2005-12, 3 rows), "
			+ "LOYALTY.Fidelite_Membres.date_naissance (1950-12..2006-01, 3 rows), STORES.Magasins_Ventes.DATE_ACHAT (2026-01..2026-10, 4 rows): the spans fall in 2 groups that do not overlap each other "
			+ "([ECOM.customers.birth_date, LOYALTY.Fidelite_Membres.date_naissance] and [STORES.Magasins_Ventes.DATE_ACHAT]) - the same attribute would hold dates from separate periods. Fix the mapping "
			+ "(propose_feed again on the same entity and table with the rule for birthDate replaced, or sent with \"remove\": true to drop it), or say in a note why they belong together."));
	}

	private static void overlappingOrSingleSpansAreSilent()
	{
		IdentityFactsTest.check("a ticket date inside the others' span overlaps: silent", DateSpansTest.session("1999-01-05,2001-10-12", true).dateSpanDisjoints().isEmpty());
		IdentityFactsTest.check("a span that touches the edge month overlaps too", DateSpansTest.session("2005-12-01,2006-12-31", true).dateSpanDisjoints().isEmpty());
		IdentityFactsTest.check("not fed from the ticket table: one group, silent", DateSpansTest.session(DateSpansTest.PURCHASES, false).dateSpanDisjoints().isEmpty());
		// a string attribute is never looked at
		final StagingLlmPass.Session text = new StagingLlmPass.Session(DateSpansTest.project(DateSpansTest.PURCHASES), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		DateSpansTest.personEntity(text, "STRING");
		DateSpansTest.dateFeed(text, "ECOM.customers", "birth_date", null, false);
		DateSpansTest.dateFeed(text, "STORES.Magasins_Ventes", "DATE_ACHAT", null, false);
		IdentityFactsTest.check("a STRING attribute fed from two date columns: no fact (dates only)", text.dateSpanDisjoints().isEmpty());
		// a column with no span (not a date column) is left out
		final StagingLlmPass.ProjectInfo project = DateSpansTest.project(DateSpansTest.PURCHASES);
		project.tables.get(2).columns.get(1).dateSpan = null;
		final StagingLlmPass.Session unread = new StagingLlmPass.Session(project, new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		DateSpansTest.personEntity(unread, "DATE");
		DateSpansTest.dateFeed(unread, "ECOM.customers", "birth_date", "date(yyyy-MM-dd)", false);
		DateSpansTest.dateFeed(unread, "STORES.Magasins_Ventes", "DATE_ACHAT", "date(yyyy-MM-dd)", false);
		IdentityFactsTest.check("a source with no span is not compared", unread.dateSpanDisjoints().isEmpty());
	}

	private static void theFollowUpStatesTheFactAndBothAnswers()
	{
		final StagingLlmPass.Session session = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		final List<DateSpans.Disjoint> disjoint = session.dateSpanDisjoints();
		final String prompt = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, null, null, disjoint);
		IdentityFactsTest.check("fenced, with the sentence and both answers: " + prompt, prompt.contains("DATE SPANS") && prompt.contains(disjoint.get(0).followUpText())
			&& prompt.contains("A span says when the values of a column fall, not what the column is") && prompt.contains("call note with the entity.attribute") && prompt.contains("Either answer stands"));
		IdentityFactsTest.check("without a mismatch the follow-up says nothing of it", !StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, null, null, null).contains("DATE SPANS")
			&& !StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, null, null, Collections.<DateSpans.Disjoint>emptyList()).contains("DATE SPANS"));
		IdentityFactsTest.check("the tool schema offers 'remove'", StagingLlmPass.systemPrompt().length() > 0 && StagingLlmPass.toolSchemaText().contains("\"remove\""));
	}

	// ----------------------------------------------------------------------------------------- a corrected feed

	private static void aCorrectedFeedReplacesTheRuleNeverDuplicatesIt()
	{
		StagingLlmPass.Session session = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		final int feedsBefore = session.createdFeeds().size();
		// the same rule again: nothing new, refused as before
		LocalTool.Result same = DateSpansTest.dateFeed(session, "STORES.Magasins_Ventes", "DATE_ACHAT", "date(yyyy-MM-dd)", false);
		IdentityFactsTest.check("an identical re-proposal changes nothing: " + same.text, same.isError && session.createdFeeds().size() == feedsBefore);
		// the same attribute from another column replaces the rule: here the ticket table's own other date column, whose span overlaps the others
		session = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		final LocalTool.Result replaced = DateSpansTest.dateFeed(session, "STORES.Magasins_Ventes", "DATE_NAISSANCE", "date(yyyy-MM-dd)", false);
		IdentityFactsTest.check("a different column for an attribute the feed already maps replaces the rule: " + replaced.text, !replaced.isError && replaced.text.contains("REPLACED"));
		StagingLlmPass.CreatedFeed stores = null;
		for (final StagingLlmPass.CreatedFeed feed : session.createdFeeds())
		{
			if ("STORES.Magasins_Ventes".equals(feed.landingTableKey))
			{
				stores = feed;
			}
		}
		IdentityFactsTest.check("one rule, the new one, not two: " + stores.rules.size(), stores.rules.size() == 1 && "DATE_NAISSANCE".equals(stores.rules.get(0).landingColumn) && session.createdFeeds().size() == feedsBefore);
		IdentityFactsTest.check("the mismatch is gone with the rule", session.dateSpanDisjoints().isEmpty());
		// "remove" on a feed with a single rule: refused, a feed keeps at least one rule
		session = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		final LocalTool.Result last = DateSpansTest.dateFeed(session, "STORES.Magasins_Ventes", "DATE_ACHAT", null, true);
		IdentityFactsTest.check("removing the only rule of a feed is refused, with the way out: " + last.text, last.isError && last.text.contains("with none") && last.text.contains("note"));
		// "remove" on a feed with two rules drops one
		session = new StagingLlmPass.Session(DateSpansTest.project(DateSpansTest.PURCHASES), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		DateSpansTest.personEntity(session, "DATE");
		final ObjectNode two = IdentityFactsTest.MAPPER.createObjectNode();
		two.put("entity", "Personne").put("landingTable", "STORES.Magasins_Ventes");
		final ArrayNode rules = two.putArray("rules");
		rules.addObject().put("attribute", "externalId").put("landingColumn", "NUM_TICKET").put("rationale", "Key.").put("confidence", 90);
		final ObjectNode date = rules.addObject();
		date.put("attribute", "birthDate").put("landingColumn", "DATE_ACHAT").put("pipeline", "date(yyyy-MM-dd)").put("rationale", "A date.").put("confidence", 70);
		two.put("confidence", 85).put("rationale", "Read as it stands.");
		IdentityFactsTest.check("two-rule feed", !session.proposeFeed(two).isError);
		IdentityFactsTest.feed(session, "Personne", "ECOM.customers", "externalId,customerId");
		final LocalTool.Result removed = DateSpansTest.dateFeed(session, "STORES.Magasins_Ventes", "DATE_ACHAT", null, true);
		IdentityFactsTest.check("the rule is dropped, the other stands: " + removed.text, !removed.isError && removed.text.contains("removed"));
		for (final StagingLlmPass.CreatedFeed feed : session.createdFeeds())
		{
			if ("STORES.Magasins_Ventes".equals(feed.landingTableKey))
			{
				IdentityFactsTest.check("one rule left: externalId", feed.rules.size() == 1 && "externalId".equals(feed.rules.get(0).attribute));
			}
		}
		IdentityFactsTest.check("'remove' of an attribute the feed does not map is refused", DateSpansTest.dateFeed(session, "STORES.Magasins_Ventes", "DATE_ACHAT", null, true).isError);
		IdentityFactsTest.check("'remove' on a feed not proposed yet is refused", DateSpansTest.dateFeed(session, "LOYALTY.Fidelite_Membres", "date_naissance", null, true).isError);
	}

	// ----------------------------------------------------------------------------------------------------- open point

	private static void theOpenPointReadsInBothLanguages()
	{
		final StagingLlmPass.Session session = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		IdentityFactsTest.noted(session, "Personne.birthDate: the ticket date is the first purchase date, kept on purpose.");
		session.finalizeEntities();
		final List<ProposalChecks.Finding> findings = DateSpansTest.onlyDateSpans(session.reviewModelling(IdentityFactsTest.words(Locale.ENGLISH)));
		IdentityFactsTest.check("one open point on the entity: " + findings, findings.size() == 1 && ProposalChecks.DATE_SPANS_DISJOINT.equals(findings.get(0).kind) && "Personne".equals(findings.get(0).entity)
			&& "birthDate".equals(findings.get(0).attribute));
		final StagingLlmPass.CreatedEntity person = IdentityFactsTest.created(session, "Personne");
		IdentityFactsTest.check("English, first in the rationale, then the model's note as the reason: " + person.rationale, person.rationale.startsWith("To check: Personne.birthDate is fed from "
			+ "ECOM.customers.birth_date (1951-03..2005-12, 3 rows), LOYALTY.Fidelite_Membres.date_naissance (1950-12..2006-01, 3 rows), STORES.Magasins_Ventes.DATE_ACHAT (2026-01..2026-10, 4 rows): "
			+ "the spans fall in 2 groups that do not overlap ([ECOM.customers.birth_date, LOYALTY.Fidelite_Membres.date_naissance] and [STORES.Magasins_Ventes.DATE_ACHAT]) - the same attribute would hold "
			+ "dates from separate periods. Fix the mapping, or say why they belong together (suggestion: check before accepting).") && person.rationale.contains("the first purchase date, kept on purpose"));
		IdentityFactsTest.check("no confidence change of its own", person.confidence == 90);
		final StagingLlmPass.Session french = DateSpansTest.session(DateSpansTest.PURCHASES, true);
		french.finalizeEntities();
		french.reviewModelling(IdentityFactsTest.words(Locale.FRENCH));
		final String text = IdentityFactsTest.created(french, "Personne").rationale;
		IdentityFactsTest.check("French: " + text, text.startsWith("À vérifier : Personne.birthDate est alimenté depuis ECOM.customers.birth_date (1951-03..2005-12, 3 lignes), "
			+ "LOYALTY.Fidelite_Membres.date_naissance (1950-12..2006-01, 3 lignes), STORES.Magasins_Ventes.DATE_ACHAT (2026-01..2026-10, 4 lignes) : les plages se répartissent en 2 groupes qui ne se recouvrent pas "
			+ "([ECOM.customers.birth_date, LOYALTY.Fidelite_Membres.date_naissance] et [STORES.Magasins_Ventes.DATE_ACHAT]) - le même attribut contiendrait des dates de périodes distinctes. "
			+ "Corrigez le mapping, ou dites pourquoi elles vont ensemble (proposition : vérifier avant d’accepter)."));
		IdentityFactsTest.check("no raw key, no unfilled argument", !text.contains("stagingProposal.") && !text.contains("{"));
		// overlapping: no point, no change
		final StagingLlmPass.Session fine = DateSpansTest.session("1999-01-05", true);
		fine.finalizeEntities();
		IdentityFactsTest.check("overlapping spans raise nothing", DateSpansTest.onlyDateSpans(fine.reviewModelling(IdentityFactsTest.words(Locale.ENGLISH))).isEmpty());
	}

	/** The test entity also has an attribute no rule feeds (an unrelated finding of the review): keep this lot's findings only. */
	private static List<ProposalChecks.Finding> onlyDateSpans(final List<ProposalChecks.Finding> pFindings)
	{
		final List<ProposalChecks.Finding> out = new ArrayList<ProposalChecks.Finding>();
		for (final ProposalChecks.Finding finding : pFindings)
		{
			if (ProposalChecks.DATE_SPANS_DISJOINT.equals(finding.kind))
			{
				out.add(finding);
			}
		}
		return out;
	}
}
