package com.on.ebx.factory.staging;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.factory.wizard.WizardMessages;

/**
 * JUnit-free, like the rest of this module's tests. Lot S10 (2026-10-18): the identity FACTS the staging model is given (row count, key, distinct values and repeated rows of
 * the catalog-tagged identity columns), the numeric FEEDBACK of the follow-up turn for a table whose identity feeds nothing, and the open point of the review, in both
 * languages. Nothing here is a decision: every test also checks what stays silent.
 */
public final class IdentityFactsTest
{
	static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		IdentityFactsTest.tagsAndNormalisation();
		IdentityFactsTest.measureOnAFixture();
		IdentityFactsTest.aSampledTableKeepsNoDigestAndSkipsTheCrossTableFigure();
		IdentityFactsTest.inventoryCarriesRowsKeyAndCountsOfTaggedColumnsOnly();
		IdentityFactsTest.compactInventoryAndDescribeCarryTheFacts();
		IdentityFactsTest.followUpForATableWhoseIdentityFeedsNothing();
		IdentityFactsTest.nothingIsAskedWhenTheIdentityIsFed();
		IdentityFactsTest.theProposalStandsWhateverTheModelAnswers();
		IdentityFactsTest.theOpenPointReadsInBothLanguages();
		IdentityFactsTest.systemPromptStatesTheConsequenceAsAGeneralFact();
		IdentityFactsTest.appliedAndSuggestedTagsAreRenderedAsMeasuredFacts();
		IdentityFactsTest.aSuggestedOnlyColumnIsAnIdentityColumn();
		IdentityFactsTest.theOpenPointNamesHowFirmTheTagsAre();
		System.out.println("IdentityFactsTest OK");
	}

	// -------------------------------------------------------------------------------------------------- pure measure

	private static void tagsAndNormalisation()
	{
		IdentityFactsTest.check("an e-mail tag", IdentityFacts.Kind.of(Arrays.asList("PERSONAL_DATA", "EMAIL")) == IdentityFacts.Kind.EMAIL);
		IdentityFactsTest.check("the umbrella tag alone is not an identity", IdentityFacts.Kind.of(Arrays.asList("PERSONAL_DATA", "GENDER")) == null);
		IdentityFactsTest.check("no tag, no kind - whatever the column is called", IdentityFacts.Kind.of(new ArrayList<String>()) == null);
		IdentityFactsTest.check("a person name and an official identifier", IdentityFacts.Kind.of(Arrays.asList("person_name")) == IdentityFacts.Kind.NAME
			&& IdentityFacts.Kind.of(Arrays.asList("VAT_NUMBER")) == IdentityFacts.Kind.OFFICIAL_ID);
		IdentityFactsTest.check("e-mail, phone and official identifier are compared across tables; a name and a source-internal number are not",
			IdentityFacts.Kind.EMAIL.comparable() && IdentityFacts.Kind.PHONE.comparable() && IdentityFacts.Kind.OFFICIAL_ID.comparable() && !IdentityFacts.Kind.NAME.comparable()
				&& !IdentityFacts.Kind.PERSON_ID.comparable());
		IdentityFactsTest.check("e-mail case and spaces do not matter", "a@x.fr".equals(IdentityFacts.normalise(IdentityFacts.Kind.EMAIL, "  A@X.fr ")));
		IdentityFactsTest.check("a national and an international phone are the same value",
			IdentityFacts.normalise(IdentityFacts.Kind.PHONE, "06.11.22.33.44").equals(IdentityFacts.normalise(IdentityFacts.Kind.PHONE, "+33 6 11 22 33 44")));
		IdentityFactsTest.check("a blank value is not a value", IdentityFacts.normalise(IdentityFacts.Kind.EMAIL, "   ") == null && IdentityFacts.normalise(IdentityFacts.Kind.NAME, null) == null);
	}

	/** Three tables; STORES has rows 1-2 on the same e-mail and phone, 3-4 on the same phone and name, one row with no identity. */
	static Map<String, IdentityFacts.Table> fixture(final boolean pStoresSampled)
	{
		final Map<String, IdentityFacts.Table> tables = new LinkedHashMap<String, IdentityFacts.Table>();
		final IdentityFacts.Accumulator stores = new IdentityFacts.Accumulator(Arrays.asList("EMAIL", "TELEPHONE", "NOM"),
			Arrays.asList(IdentityFacts.Kind.EMAIL, IdentityFacts.Kind.PHONE, IdentityFacts.Kind.NAME), pStoresSampled);
		stores.row(new String[] { "a@x.fr", "06 11 22 33 44", "DUPONT" });
		stores.row(new String[] { "A@X.fr", "+33 6 11 22 33 44", "DUPONT" });
		stores.row(new String[] { "b@x.fr", "07 00 00 00 01", "MARTIN" });
		stores.row(new String[] { "", "07 00 00 00 01", "MARTIN" });
		stores.row(new String[] { "c@x.fr", "07 00 00 00 02", "LEROY" });
		stores.row(new String[] { "", null, "" });
		tables.put("SHOP.STORES", stores.finish());
		final IdentityFacts.Accumulator ecom = new IdentityFacts.Accumulator(Arrays.asList("email", "mobile"), Arrays.asList(IdentityFacts.Kind.EMAIL, IdentityFacts.Kind.PHONE), false);
		ecom.row(new String[] { "a@x.fr", "0611223344" });
		ecom.row(new String[] { "d@x.fr", "0600000009" });
		tables.put("WEB.ECOM", ecom.finish());
		final IdentityFacts.Accumulator loyalty = new IdentityFacts.Accumulator(Arrays.asList("email", "telephone"), Arrays.asList(IdentityFacts.Kind.EMAIL, IdentityFacts.Kind.PHONE),
			false);
		loyalty.row(new String[] { "c@x.fr", "0700000002" });
		loyalty.row(new String[] { "e@x.fr", "0600000010" });
		tables.put("LOY.MEMBERS", loyalty.finish());
		IdentityFacts.crossTable(tables);
		return tables;
	}

	private static void measureOnAFixture()
	{
		final IdentityFacts.Table stores = IdentityFactsTest.fixture(false).get("SHOP.STORES");
		final IdentityFacts.Column email = stores.columns.get("EMAIL");
		IdentityFactsTest.check("e-mail: 4 filled, 3 distinct (A@X.fr is a@x.fr), repeated on 2 rows: " + email.inventoryText(),
			email.filledRows == 4L && email.distinctValues == 3L && email.repeatedRows == 2L);
		IdentityFactsTest.check("the sentence the inventory carries", "3 distinct values over 4 filled rows, repeated on 2 rows".equals(email.inventoryText()));
		final IdentityFacts.Column phone = stores.columns.get("TELEPHONE");
		IdentityFactsTest.check("phone: national and international forms are one value: " + phone.inventoryText(),
			phone.filledRows == 5L && phone.distinctValues == 3L && phone.repeatedRows == 4L);
		final IdentityFacts.Column name = stores.columns.get("NOM");
		IdentityFactsTest.check("name: 5 filled, 3 distinct, repeated on 4", name.filledRows == 5L && name.distinctValues == 3L && name.repeatedRows == 4L);
		IdentityFactsTest.check("6 rows read, 5 carry an identity value", stores.rowsRead == 6L && stores.rowsWithIdentity == 5L && !stores.sampled);
		IdentityFactsTest.check("e-mails seen in no other table: only b@x.fr (a is in ECOM, c in LOYALTY): " + stores.seenNowhereElse,
			Long.valueOf(1L).equals(stores.seenNowhereElse.get(IdentityFacts.Kind.EMAIL)));
		IdentityFactsTest.check("phones seen in no other table: 07 00 00 00 01 only (the 06 11.. one is in ECOM, 07 00 00 00 02 in LOYALTY)",
			Long.valueOf(1L).equals(stores.seenNowhereElse.get(IdentityFacts.Kind.PHONE)));
		IdentityFactsTest.check("a name is never compared across tables", !stores.seenNowhereElse.containsKey(IdentityFacts.Kind.NAME));
		final IdentityFacts.Table ecom = IdentityFactsTest.fixture(false).get("WEB.ECOM");
		IdentityFactsTest.check("ECOM: d@x.fr only (a is in STORES)", Long.valueOf(1L).equals(ecom.seenNowhereElse.get(IdentityFacts.Kind.EMAIL)));
		final IdentityFacts.Column empty = new IdentityFacts.Column(IdentityFacts.Kind.EMAIL, 0L, 0L, 0L, 0L);
		IdentityFactsTest.check("an empty column says so", "no filled row".equals(empty.inventoryText()));
	}

	private static void aSampledTableKeepsNoDigestAndSkipsTheCrossTableFigure()
	{
		final Map<String, IdentityFacts.Table> tables = IdentityFactsTest.fixture(true);
		final IdentityFacts.Table stores = tables.get("SHOP.STORES");
		IdentityFactsTest.check("the counts are still given, on the sample, and say so: " + stores.columns.get("EMAIL").inventoryText(),
			stores.sampled && stores.columns.get("EMAIL").inventoryText().endsWith("(measured on a sample of 6 rows)"));
		IdentityFactsTest.check("no 'seen nowhere else' figure for the sampled table", stores.seenNowhereElse.isEmpty() && stores.crossSkipped);
		IdentityFactsTest.check("nor for a complete table compared with a sampled one: its values might be in the rows that were not read",
			tables.get("WEB.ECOM").seenNowhereElse.isEmpty() && tables.get("WEB.ECOM").crossSkipped);
		IdentityFactsTest.check("a table alone has nothing to be compared with: no figure, nothing skipped", IdentityFactsTest.alone().seenNowhereElse.isEmpty());
	}

	private static IdentityFacts.Table alone()
	{
		final IdentityFacts.Accumulator only = new IdentityFacts.Accumulator(Arrays.asList("EMAIL"), Arrays.asList(IdentityFacts.Kind.EMAIL), false);
		only.row(new String[] { "a@x.fr" });
		final Map<String, IdentityFacts.Table> tables = new LinkedHashMap<String, IdentityFacts.Table>();
		tables.put("A.B", only.finish());
		IdentityFacts.crossTable(tables);
		return tables.get("A.B");
	}

	// ------------------------------------------------------------------------------------------------------- fixtures

	static StagingRules.LandingColumnInfo column(final String pName, final boolean pKey, final String pOrigin, final long pDistinct, final String... pTags)
	{
		final StagingRules.LandingColumnInfo column = new StagingRules.LandingColumnInfo("lc-" + pName, pName, pName, "STRING", pKey, new BigDecimal("100"), Long.valueOf(pDistinct), null,
			new ArrayList<String>(Arrays.asList(pTags)));
		column.keyOrigin = pOrigin;
		return column;
	}

	static StagingRules.LandingTableInfo table(final String pSystem, final String pName, final long pRows, final StagingRules.LandingColumnInfo... pColumns)
	{
		final StagingRules.LandingTableInfo table = new StagingRules.LandingTableInfo("t-" + pName, pSystem, pName, null, Arrays.asList(pColumns));
		table.landedRows = Long.valueOf(pRows);
		return table;
	}

	/** Fixture figures written on the tables the way {@code ebx.IdentityReader} does. */
	static void attach(final StagingRules.LandingTableInfo pTable, final IdentityFacts.Table pFacts)
	{
		pTable.identity = pFacts;
		for (final StagingRules.LandingColumnInfo column : pTable.columns)
		{
			column.identity = pFacts.columns.get(column.fieldName);
		}
	}

	/** The B2C shape: a web shop and a loyalty table that hold a customer id, and a ticket table that holds none. */
	static StagingLlmPass.ProjectInfo project()
	{
		return IdentityFactsTest.project(false);
	}

	private static StagingLlmPass.ProjectInfo project(final boolean pStoresSampled)
	{
		final StagingRules.LandingTableInfo ecom = IdentityFactsTest.table("WEB", "ECOM", 2L, IdentityFactsTest.column("customerId", true, "SOURCE", 2L),
			IdentityFactsTest.column("email", false, null, 2L, "PERSONAL_DATA", "EMAIL"), IdentityFactsTest.column("mobile", false, null, 2L, "PHONE"),
			IdentityFactsTest.column("city", false, null, 1L));
		final StagingRules.LandingTableInfo loyalty = IdentityFactsTest.table("LOY", "MEMBERS", 2L, IdentityFactsTest.column("card_number", true, "SOURCE", 2L),
			IdentityFactsTest.column("email", false, null, 2L, "EMAIL"), IdentityFactsTest.column("telephone", false, null, 2L, "PHONE"));
		final StagingRules.LandingTableInfo stores = IdentityFactsTest.table("SHOP", "STORES", 6L, IdentityFactsTest.column("NUM_TICKET", true, "USER", 6L),
			IdentityFactsTest.column("MAGASIN", false, null, 3L), IdentityFactsTest.column("MONTANT", false, null, 6L), IdentityFactsTest.column("NOM", false, null, 3L, "PERSON_NAME"),
			IdentityFactsTest.column("TELEPHONE", false, null, 3L, "PHONE"), IdentityFactsTest.column("EMAIL", false, null, 3L, "EMAIL"));
		final Map<String, IdentityFacts.Table> facts = IdentityFactsTest.fixture(pStoresSampled);
		IdentityFactsTest.attach(ecom, facts.get("WEB.ECOM"));
		IdentityFactsTest.attach(loyalty, facts.get("LOY.MEMBERS"));
		IdentityFactsTest.attach(stores, facts.get("SHOP.STORES"));
		return new StagingLlmPass.ProjectInfo("B2C", "B2C", Arrays.asList(ecom, loyalty, stores), Collections.<StagingRules.RelationInfo>emptyList(),
			Arrays.asList(new StagingLlmPass.RuleEntityInfo("Ecom", "WEB", "ECOM"), new StagingLlmPass.RuleEntityInfo("Members", "LOY", "MEMBERS"),
				new StagingLlmPass.RuleEntityInfo("Stores", "SHOP", "STORES")));
	}

	// ---------------------------------------------------------------------------------------------------------- prompt

	private static void inventoryCarriesRowsKeyAndCountsOfTaggedColumnsOnly()
	{
		final String prompt = StagingLlmPass.userPrompt(IdentityFactsTest.project());
		IdentityFactsTest.check("the ticket table says its rows and its key, who set it and that it carries no identity tag: " + prompt,
			prompt.contains("- SHOP.STORES (6 columns, 6 rows, key: NUM_TICKET (set by a person; 6 distinct values over 6 rows; carries no identity tag))"));
		IdentityFactsTest.check("a source-declared key says so", prompt.contains("- WEB.ECOM (4 columns, 2 rows, key: customerId (declared by the source; 2 distinct values over 2 rows; carries no identity tag))"));
		IdentityFactsTest.check("the e-mail column carries its counts", prompt.contains("EMAIL (type STRING, tags: EMAIL, fill rate 100%, identity: 3 distinct values over 4 filled rows, repeated on 2 rows)"));
		IdentityFactsTest.check("so does the phone", prompt.contains("TELEPHONE (type STRING, tags: PHONE, fill rate 100%, identity: 3 distinct values over 5 filled rows, repeated on 4 rows)"));
		IdentityFactsTest.check("and the name", prompt.contains("NOM (type STRING, tags: PERSON_NAME, fill rate 100%, identity: 3 distinct values over 5 filled rows, repeated on 4 rows)"));
		final String untagged = prompt.substring(prompt.indexOf("MAGASIN (type"), prompt.indexOf("MONTANT (type"));
		IdentityFactsTest.check("a column without identity tag carries none: " + untagged, !untagged.contains("identity:"));
		IdentityFactsTest.check("the key of the ticket table is not an identity column", !prompt.substring(prompt.indexOf("NUM_TICKET (type"), prompt.indexOf("MAGASIN (type")).contains("identity:"));
		IdentityFactsTest.check("no 'seen nowhere else' figure in the inventory (it belongs to the feedback)", !prompt.contains("no other source") && !prompt.contains("nowhere"));
		IdentityFactsTest.check("no value in the prompt", !prompt.contains("a@x.fr") && !prompt.contains("DUPONT") && !prompt.contains("07 00"));
		// unknown rows and no key: nothing invented
		final StagingRules.LandingTableInfo bare = new StagingRules.LandingTableInfo("t", "X", "BARE", Long.valueOf(0L), Arrays.asList(IdentityFactsTest.column("a", false, null, 1L)));
		final String bareText = StagingLlmPass.userPrompt(new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(bare), Collections.<StagingRules.RelationInfo>emptyList(),
			Arrays.asList(new StagingLlmPass.RuleEntityInfo("Bare", "X", "BARE"))));
		IdentityFactsTest.check("a catalog count of 0 is unknown, not '0 rows'; no key, nothing said: " + bareText, bareText.contains("- X.BARE (1 columns)"));
	}

	private static void compactInventoryAndDescribeCarryTheFacts()
	{
		final StagingLlmPass.ProjectInfo project = IdentityFactsTest.project();
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		final ObjectNode args = IdentityFactsTest.MAPPER.createObjectNode().put("table", "SHOP.STORES");
		final LocalTool.Result described = session.describeLandingTable(args);
		IdentityFactsTest.check("describe_landing_table carries the rows, the key and the counts: " + described.text,
			described.text.contains("SHOP.STORES (6 columns, 6 rows, key: NUM_TICKET (set by a person; 6 distinct values over 6 rows; carries no identity tag)):")
				&& described.text.contains("identity: 3 distinct values over 4 filled rows, repeated on 2 rows"));
		// a project big enough for the compact catalogue
		final List<StagingRules.LandingTableInfo> tables = new ArrayList<StagingRules.LandingTableInfo>();
		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = new ArrayList<StagingLlmPass.RuleEntityInfo>();
		for (int t = 0; t < 40; t++)
		{
			final List<StagingRules.LandingColumnInfo> columns = new ArrayList<StagingRules.LandingColumnInfo>();
			for (int c = 0; c < 30; c++)
			{
				columns.add(IdentityFactsTest.column("COLUMN_" + t + "_" + c + "_WITH_A_LONGER_NAME_TO_PAD_SIZE", c == 0, c == 0 ? "RULE" : null, 5L));
			}
			tables.add(IdentityFactsTest.table("ERP", "TABLE_" + t, 234L, columns.toArray(new StagingRules.LandingColumnInfo[0])));
			ruleEntities.add(new StagingLlmPass.RuleEntityInfo("Entity" + t, "ERP", "TABLE_" + t));
		}
		final StagingLlmPass.ProjectInfo big = new StagingLlmPass.ProjectInfo("Big", "Big", tables, Collections.<StagingRules.RelationInfo>emptyList(), ruleEntities);
		IdentityFactsTest.check("this fixture is compact", StagingLlmPass.isCompact(big));
		final String compact = StagingLlmPass.userPrompt(big);
		IdentityFactsTest.check("the compact catalogue gives the rows and the key facts: " + compact.substring(0, 600),
			compact.contains("- ERP.TABLE_0 (30 columns, 234 rows, key: COLUMN_0_0_WITH_A_LONGER_NAME_TO_PAD_SIZE (guessed from the column names; 5 distinct values over 234 rows))"));
	}

	private static void systemPromptStatesTheConsequenceAsAGeneralFact()
	{
		final String system = StagingLlmPass.systemPrompt();
		IdentityFactsTest.check("the consequence, stated once, as a fact", system.contains("A landing table whose rows each carry the identity attributes of an entity but hold no identifier of that entity is still a SOURCE of that entity: every such table left out of the entity's feeds leaves the records seen only in it out of the master."));
		IdentityFactsTest.check("it sits right after rule (10)", system.indexOf("the person reviewing must see it. A landing table whose rows") > system.indexOf("(10) Set businessKey only"));
		IdentityFactsTest.check("it names no domain word", !system.substring(system.indexOf("A landing table whose rows each carry"), system.indexOf("A landing table whose rows each carry") + 300).toLowerCase(Locale.ROOT).contains("ticket"));
		IdentityFactsTest.check("the prompt no longer claims it shows no count", !system.contains("a row count, or any other data content") && system.contains("only counts"));
	}

	// ----------------------------------------------------------------------------------------------------- the feedback

	static StagingLlmPass.Session sessionWithPersonEntity()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(IdentityFactsTest.project(), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(session, "Client", "customerId", "email", "phone");
		IdentityFactsTest.feed(session, "Client", "WEB.ECOM", "customerId,customerId", "email,email", "phone,mobile");
		IdentityFactsTest.feed(session, "Client", "LOY.MEMBERS", "customerId,card_number", "email,email", "phone,telephone");
		return session;
	}

	private static void followUpForATableWhoseIdentityFeedsNothing()
	{
		final StagingLlmPass.Session session = IdentityFactsTest.sessionWithPersonEntity();
		final List<IdentityCoverage.Gap> gaps = session.identityGaps();
		IdentityFactsTest.check("exactly one table is a gap: " + gaps.size(), gaps.size() == 1 && "SHOP.STORES".equals(gaps.get(0).landingTableKey));
		final String line = gaps.get(0).followUpText();
		IdentityFactsTest.check("the numbers the Factory measured, then the two answers: " + line, line.equals("SHOP.STORES: 5 rows carry identity attributes (tags PERSON_NAME (applied), PHONE (applied), EMAIL (applied); "
			+ "columns NOM, TELEPHONE, EMAIL) but feed no entity that holds them; among them 1 distinct e-mail, 1 distinct phone are seen in no other source - they will be absent from "
			+ "the master. Feed them (a root feed of Client on SHOP.STORES, one row = one NUM_TICKET) or say in a note why not."));
		final String prompt = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, gaps);
		IdentityFactsTest.check("the follow-up carries the fenced feedback and the choice: " + prompt, prompt.contains(line) && prompt.contains("IDENTITY WITHOUT A FEED")
			&& prompt.contains("call propose_feed with the entity named") && prompt.contains("call note with the table key and the reason it must not feed that entity")
			&& prompt.contains("Either answer stands"));
		IdentityFactsTest.check("without gaps the follow-up says nothing of it", !StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, null).contains("IDENTITY WITHOUT A FEED"));
		IdentityFactsTest.check("a note does not silence the figures: the table stays a gap and the model is told the counts", IdentityFactsTest.noted(session, "SHOP.STORES: modelled as another kind of record.").identityGaps().size() == 1);
		// a table read by an entity that does not hold the identity of the others stays a gap
		final StagingLlmPass.Session other = IdentityFactsTest.sessionWithPersonEntity();
		IdentityFactsTest.entity(other, "Achat", "ticket", "amount");
		IdentityFactsTest.feed(other, "Achat", "SHOP.STORES", "ticket,NUM_TICKET", "amount,MONTANT");
		IdentityFactsTest.check("feeding the ticket entity from the ticket table feeds no entity that holds the identity", other.identityGaps().size() == 1);
		IdentityFactsTest.check("the entities offered are the ones fed by the identity of the other tables, from the proposal itself", other.identityGaps().get(0).entities.equals(Arrays.asList("Client")));
	}

	private static void nothingIsAskedWhenTheIdentityIsFed()
	{
		final StagingLlmPass.Session session = IdentityFactsTest.sessionWithPersonEntity();
		IdentityFactsTest.feed(session, "Client", "SHOP.STORES", "email,EMAIL", "phone,TELEPHONE");
		IdentityFactsTest.check("a root feed of the entity on the ticket table closes the gap", session.identityGaps().isEmpty());
		IdentityFactsTest.check("and the follow-up has nothing of it", !StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, session.identityGaps()).contains("IDENTITY"));
		// no other table feeds any identity: nothing to compare with, nothing said
		final StagingLlmPass.Session lone = new StagingLlmPass.Session(IdentityFactsTest.project(), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(lone, "Achat", "ticket");
		IdentityFactsTest.feed(lone, "Achat", "SHOP.STORES", "ticket,NUM_TICKET");
		IdentityFactsTest.check("no entity holds an identity yet: no gap is invented", lone.identityGaps().isEmpty());
		// a project without any tag
		final StagingLlmPass.Session untagged = new StagingLlmPass.Session(new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(IdentityFactsTest.table("X", "T",
			3L, IdentityFactsTest.column("id", true, "RULE", 3L), IdentityFactsTest.column("mail", false, null, 3L))), Collections.<StagingRules.RelationInfo>emptyList(),
			Arrays.asList(new StagingLlmPass.RuleEntityInfo("T", "X", "T"))), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.check("a column called mail but not tagged is not an identity column", untagged.identityGaps().isEmpty());
	}

	/** The model answers with a note, or with nothing: the written proposal is the model's, flagged for a person, never fed on its behalf. */
	private static void theProposalStandsWhateverTheModelAnswers()
	{
		final StagingLlmPass.Session session = IdentityFactsTest.sessionWithPersonEntity();
		IdentityFactsTest.noted(session, "SHOP.STORES: each row is a purchase; the people are not customers of the shop.");
		final int feedsBefore = session.createdFeeds().size();
		session.finalizeEntities();
		final List<ProposalChecks.Finding> findings = session.reviewModelling(new ProposalChecks.Words()
		{
			@Override
			public String text(final String pKey, final Object... pArgs)
			{
				return "[" + pKey + Arrays.toString(pArgs) + "]";
			}
		});
		IdentityFactsTest.check("the review reports one open point for the table: " + findings, findings.size() == 1 && ProposalChecks.IDENTITY_SOURCE_UNFED.equals(findings.get(0).kind)
			&& "Client".equals(findings.get(0).entity) && "SHOP.STORES".equals(findings.get(0).landingTableKey));
		IdentityFactsTest.check("no feed was added on the model's behalf", session.createdFeeds().size() == feedsBefore);
		final StagingLlmPass.CreatedEntity client = IdentityFactsTest.created(session, "Client");
		IdentityFactsTest.check("the sentence leads the entity's rationale, its counts as arguments, the model's note follows as the reason: " + client.rationale,
			client.rationale.startsWith("[stagingProposal.check.identityUnfed[STORES, 5, [stagingProposal.identityKind.EMAIL[]]")
				&& client.rationale.contains("each row is a purchase") && client.rationale.contains("[stagingProposal.check.identityUnfed.only["));
		IdentityFactsTest.check("the entity is capped so that a bulk accept never takes it, nothing is rejected", client.confidence == ProposalChecks.IDENTITY_UNFED_CONFIDENCE_CAP);
		IdentityFactsTest.check("a second review adds nothing", session.reviewModelling(new ProposalChecks.Words()
		{
			@Override
			public String text(final String pKey, final Object... pArgs)
			{
				return pKey;
			}
		}).isEmpty());
		// fed after all: no open point
		final StagingLlmPass.Session fed = IdentityFactsTest.sessionWithPersonEntity();
		IdentityFactsTest.feed(fed, "Client", "SHOP.STORES", "email,EMAIL");
		fed.finalizeEntities();
		IdentityFactsTest.check("a table whose identity is fed raises no open point", fed.reviewModelling(new ProposalChecks.Words()
		{
			@Override
			public String text(final String pKey, final Object... pArgs)
			{
				return pKey;
			}
		}).isEmpty());
	}

	private static void theOpenPointReadsInBothLanguages()
	{
		final IdentityCoverage.Gap gap = IdentityFactsTest.sessionWithPersonEntity().identityGaps().get(0);
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final ProposalChecks.Words words = new ProposalChecks.Words()
			{
				@Override
				public String text(final String pKey, final Object... pArgs)
				{
					return WizardMessages.get(locale, pKey, pArgs);
				}
			};
			final String sentence = ProposalChecks.identitySentence(gap, "Client", words);
			IdentityFactsTest.check(locale + ": no raw key, no unfilled argument: " + sentence, !sentence.contains("stagingProposal.") && !sentence.contains("{") && sentence.contains("STORES")
				&& sentence.contains("5") && sentence.contains("1 e-mails, 1 ") && sentence.contains("Client"));
		}
		IdentityFactsTest.check("English: " + ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.ENGLISH)),
			ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.ENGLISH)).equals("To check: STORES: 5 rows carrying an identity (e-mail, phone, name) feed no entity that "
				+ "holds it; 1 e-mails, 1 phones seen in no other source, they would be absent from the master - add a root feed of Client on STORES, or say why not (suggestion: check before accepting)."));
		IdentityFactsTest.check("French: " + ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.FRENCH)),
			ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.FRENCH)).equals("\u00C0 v\u00E9rifier : STORES : 5 lignes portant une identit\u00E9 (e-mail, t\u00E9l\u00E9phone, "
				+ "nom) n\u2019alimentent aucune entit\u00E9 qui la porte ; 1 e-mails, 1 t\u00E9l\u00E9phones vus nulle part ailleurs, ils seraient absents du r\u00E9f\u00E9rentiel - ajoutez un flux racine "
				+ "de Client sur STORES, ou dites pourquoi pas (proposition : v\u00E9rifier avant d\u2019accepter)."));
		// a table read on a sample: the counts are the sample's, the cross-check was not made and the sentence says so
		final StagingLlmPass.ProjectInfo sampledProject = IdentityFactsTest.project(true);
		final StagingLlmPass.Session session = new StagingLlmPass.Session(sampledProject, new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(session, "Client", "customerId", "email", "phone");
		IdentityFactsTest.feed(session, "Client", "WEB.ECOM", "customerId,customerId", "email,email", "phone,mobile");
		final IdentityCoverage.Gap sampledGap = session.identityGaps().get(0);
		IdentityFactsTest.check("no figure of values seen elsewhere: " + sampledGap.followUpText(), sampledGap.seenNowhereElse.isEmpty() && sampledGap.crossSkipped
			&& sampledGap.followUpText().contains("(whether other sources hold the same values was not measured: a table is read on a sample)")
			&& !sampledGap.followUpText().contains("are seen in no other source"));
		IdentityFactsTest.check("the open point says it too: " + ProposalChecks.identitySentence(sampledGap, "Client", IdentityFactsTest.words(Locale.ENGLISH)),
			ProposalChecks.identitySentence(sampledGap, "Client", IdentityFactsTest.words(Locale.ENGLISH)).contains("feed no entity that holds it (whether other sources hold the same values was not measured"));
	}

	// ------------------------------------------------------------------------------- lot S10b: suggestions of the catalogue

	static StagingRules.TagSuggestionInfo suggestion(final String pTag, final String pOrigin, final int pConfidence, final String pStatus)
	{
		return new StagingRules.TagSuggestionInfo(pTag, pOrigin, pConfidence, pStatus);
	}

	/** The B2C shape as the catalogue leaves a file-less H2 source: e-mail applied by its regex, phone and name only suggested (pending review), a rejected suggestion beside. */
	private static StagingLlmPass.ProjectInfo suggestedProject()
	{
		final StagingLlmPass.ProjectInfo base = IdentityFactsTest.project();
		final StagingRules.LandingTableInfo stores = base.tables.get(2);
		for (final StagingRules.LandingColumnInfo column : stores.columns)
		{
			column.tags.clear();
		}
		for (final StagingRules.LandingColumnInfo column : stores.columns)
		{
			if ("EMAIL".equals(column.fieldName))
			{
				column.tags.add("EMAIL");
				column.suggestions.add(IdentityFactsTest.suggestion("EMAIL", "REGEX", 100, "APPLIED"));
			}
			else if ("TELEPHONE".equals(column.fieldName))
			{
				column.suggestions.add(IdentityFactsTest.suggestion("PHONE", "regex", 96, "PENDING"));
			}
			else if ("NOM".equals(column.fieldName))
			{
				column.suggestions.add(IdentityFactsTest.suggestion("PERSON_NAME", "NAME", 60, "PENDING"));
			}
			else if ("MONTANT".equals(column.fieldName))
			{
				column.suggestions.add(IdentityFactsTest.suggestion("PHONE", "REGEX", 91, "REJECTED"));
			}
		}
		return base;
	}

	private static void appliedAndSuggestedTagsAreRenderedAsMeasuredFacts()
	{
		final String prompt = StagingLlmPass.userPrompt(IdentityFactsTest.suggestedProject());
		IdentityFactsTest.check("an applied tag says how the catalogue found it: " + prompt,
			prompt.contains("EMAIL (type STRING, tags: EMAIL (regex matched 100% of sampled values, applied), fill rate 100%, identity: 3 distinct values over 4 filled rows, repeated on 2 rows)"));
		IdentityFactsTest.check("a suggested tag is a suggestion, with its origin, its measure and where a steward stands",
			prompt.contains("TELEPHONE (type STRING, suggested PHONE (regex matched 96% of sampled values, pending review), fill rate 100%, identity: 3 distinct values over 5 filled rows, repeated on 4 rows)"));
		IdentityFactsTest.check("a name hint says so, never a percentage", prompt.contains("NOM (type STRING, suggested PERSON_NAME (name hint of the catalogue, pending review), fill rate 100%, identity:"));
		IdentityFactsTest.check("a rejected suggestion is told as rejected and gives no identity", prompt.contains("MONTANT (type STRING, suggested PHONE (regex matched 91% of sampled values, rejected by a steward), fill rate 100%)"));
		IdentityFactsTest.check("a column with an applied tag and no suggestion row keeps the plain form (the other tables)", prompt.contains("email (type STRING, tags: PERSONAL_DATA, EMAIL, fill rate 100%, identity: 2 distinct values over 2 filled rows"));
		IdentityFactsTest.check("the catalogue's free text is not carried: only tag, origin, confidence, status", !prompt.contains("evidence"));
		final StagingRules.LandingColumnInfo many = IdentityFactsTest.column("X", false, null, 1L);
		for (int i = 0; i < 6; i++)
		{
			many.suggestions.add(IdentityFactsTest.suggestion("TAG" + i, "NAME", 50, "PENDING"));
		}
		final String shown = StagingLlmPass.tagsText(many);
		IdentityFactsTest.check("at most " + StagingLlmPass.SUGGESTIONS_SHOWN + " suggestions beside a column: " + shown, shown.contains("TAG3") && !shown.contains("TAG4"));
		IdentityFactsTest.check("the system prompt tells what a suggested tag is", StagingLlmPass.systemPrompt().contains("suggested by the catalogue from the values it sampled or from the column name and still pending a steward's review"));
	}

	private static void aSuggestedOnlyColumnIsAnIdentityColumn()
	{
		final StagingRules.LandingColumnInfo phone = IdentityFactsTest.column("TELEPHONE", false, null, 3L);
		phone.suggestions.add(IdentityFactsTest.suggestion("PHONE", "REGEX", 96, "PENDING"));
		IdentityFactsTest.check("a pending suggestion makes an identity column", IdentityFacts.kindOf(phone) == IdentityFacts.Kind.PHONE && "SAMPLED".equals(IdentityFacts.basisOf(phone)));
		final StagingRules.LandingColumnInfo name = IdentityFactsTest.column("NOM", false, null, 3L);
		name.suggestions.add(IdentityFactsTest.suggestion("PERSON_NAME", "NAME", 60, "PENDING"));
		IdentityFactsTest.check("a name hint too, and its basis says where it comes from", IdentityFacts.kindOf(name) == IdentityFacts.Kind.NAME && "NAMES".equals(IdentityFacts.basisOf(name)));
		final StagingRules.LandingColumnInfo rejected = IdentityFactsTest.column("X", false, null, 3L);
		rejected.suggestions.add(IdentityFactsTest.suggestion("PHONE", "REGEX", 96, "REJECTED"));
		IdentityFactsTest.check("a suggestion a steward rejected is not an identity", IdentityFacts.kindOf(rejected) == null && IdentityFacts.basisOf(rejected) == null);
		final StagingRules.LandingColumnInfo applied = IdentityFactsTest.column("E", false, null, 3L, "EMAIL");
		applied.suggestions.add(IdentityFactsTest.suggestion("EMAIL", "REGEX", 100, "APPLIED"));
		IdentityFactsTest.check("an applied tag wins: basis APPLIED", "APPLIED".equals(IdentityFacts.basisOf(applied)));
		final StagingRules.LandingColumnInfo accepted = IdentityFactsTest.column("A", false, null, 3L);
		accepted.suggestions.add(IdentityFactsTest.suggestion("PERSON_NAME", "NAME", 60, "ACCEPTED"));
		IdentityFactsTest.check("an accepted suggestion is applied", accepted.suggestions.get(0).applied() && "APPLIED".equals(accepted.suggestions.get(0).basis()));
		IdentityFactsTest.check("a tag that is not an identity (a suggestion of POSTAL_ADDRESS) is none", IdentityFacts.Kind.ofTag("POSTAL_ADDRESS") == null);

		// coverage: the ticket table's identity is only suggested, the other tables' is applied - still a gap, and the gap says how firm the fact is
		final StagingLlmPass.Session session = new StagingLlmPass.Session(IdentityFactsTest.suggestedProject(), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(session, "Client", "customerId", "email", "phone");
		IdentityFactsTest.feed(session, "Client", "WEB.ECOM", "customerId,customerId", "email,email", "phone,mobile");
		IdentityFactsTest.feed(session, "Client", "LOY.MEMBERS", "customerId,card_number", "email,email", "phone,telephone");
		final List<IdentityCoverage.Gap> gaps = session.identityGaps();
		IdentityFactsTest.check("the table whose identity columns are applied (EMAIL) or suggested (phone, name) is a gap: " + gaps.size(), gaps.size() == 1 && "SHOP.STORES".equals(gaps.get(0).landingTableKey));
		final String line = gaps.get(0).followUpText();
		IdentityFactsTest.check("the line names how firm each tag is: " + line, line.contains("(tags PERSON_NAME (suggested by the catalogue from its name hints, pending review), "
			+ "PHONE (suggested by the catalogue from its sampled values, pending review), EMAIL (regex matched 100% of sampled values, applied); columns NOM, TELEPHONE, EMAIL)"));
		IdentityFactsTest.check("it says some tag is only suggested", gaps.get(0).suggestedOnly() && gaps.get(0).bases.containsAll(Arrays.asList("NAMES", "SAMPLED", "APPLIED")));
		final String prompt = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, gaps);
		IdentityFactsTest.check("the follow-up explains what a suggested tag is, once", prompt.contains("A tag 'suggested by the catalogue' is the catalogue's own measurement")
			&& prompt.indexOf("A tag 'suggested by the catalogue'") == prompt.lastIndexOf("A tag 'suggested by the catalogue'"));
		// a table whose identity is suggested but whose columns are fed: no gap
		IdentityFactsTest.feed(session, "Client", "SHOP.STORES", "email,EMAIL", "phone,TELEPHONE");
		IdentityFactsTest.check("fed, the suggested identity raises nothing", session.identityGaps().isEmpty());
		// when every tag is applied, the paragraph about suggestions is not written
		final String applied2 = StagingLlmPass.followUpPrompt(IdentityFactsTest.sessionWithPersonEntity(), Collections.<String>emptyList(), null, null, null, IdentityFactsTest.sessionWithPersonEntity().identityGaps());
		IdentityFactsTest.check("applied tags only: no paragraph about suggestions", !applied2.contains("suggested by the catalogue") && applied2.contains("EMAIL (applied)"));
	}

	private static void theOpenPointNamesHowFirmTheTagsAre()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(IdentityFactsTest.suggestedProject(), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(session, "Client", "customerId", "email", "phone");
		IdentityFactsTest.feed(session, "Client", "WEB.ECOM", "customerId,customerId", "email,email", "phone,mobile");
		IdentityFactsTest.feed(session, "Client", "LOY.MEMBERS", "customerId,card_number", "email,email", "phone,telephone");
		final IdentityCoverage.Gap gap = session.identityGaps().get(0);
		final String english = ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.ENGLISH));
		IdentityFactsTest.check("English names the basis: " + english, english.contains("(e-mail, phone, name - tags applied in the catalogue, tag suggested by the catalogue from its sampled values, pending review, tag "
			+ "suggested by the catalogue from its name hints, pending review) feed no entity that holds it"));
		final String french = ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.FRENCH));
		IdentityFactsTest.check("French too: " + french, french.contains("(e-mail, t\u00E9l\u00E9phone, nom - \u00E9tiquettes pos\u00E9es dans le catalogue, \u00E9tiquette sugg\u00E9r\u00E9e par le catalogue d\u2019apr\u00E8s ses "
			+ "valeurs \u00E9chantillonn\u00E9es, en attente de revue, \u00E9tiquette sugg\u00E9r\u00E9e par le catalogue d\u2019apr\u00E8s le nom de la colonne, en attente de revue) n\u2019alimentent aucune"));
		IdentityFactsTest.check("no raw key", !english.contains("stagingProposal.") && !french.contains("stagingProposal.") && !english.contains("{") && !french.contains("{"));
		// every tag applied: the sentence is the earlier one, nothing about suggestions
		final IdentityCoverage.Gap appliedGap = IdentityFactsTest.sessionWithPersonEntity().identityGaps().get(0);
		IdentityFactsTest.check("applied tags only: unchanged", !ProposalChecks.identitySentence(appliedGap, "Client", IdentityFactsTest.words(Locale.ENGLISH)).contains("suggested"));
	}

	// ---------------------------------------------------------------------------------------------------------- helpers

	static ProposalChecks.Words words(final Locale pLocale)
	{
		return new ProposalChecks.Words()
		{
			@Override
			public String text(final String pKey, final Object... pArgs)
			{
				return WizardMessages.get(pLocale, pKey, pArgs);
			}
		};
	}

	static StagingLlmPass.Session noted(final StagingLlmPass.Session pSession, final String pText)
	{
		pSession.note(IdentityFactsTest.MAPPER.createObjectNode().put("text", pText));
		return pSession;
	}

	static StagingLlmPass.CreatedEntity created(final StagingLlmPass.Session pSession, final String pName)
	{
		for (final StagingLlmPass.CreatedEntity entity : pSession.createdEntities())
		{
			if (entity.name.equals(pName))
			{
				return entity;
			}
		}
		throw new AssertionError("no entity " + pName);
	}

	static void entity(final StagingLlmPass.Session pSession, final String pName, final String... pAttributes)
	{
		final ObjectNode args = IdentityFactsTest.MAPPER.createObjectNode();
		args.put("name", pName);
		args.put("label", pName);
		final ArrayNode attributes = args.putArray("attributes");
		for (final String attribute : pAttributes)
		{
			final ObjectNode node = attributes.addObject();
			node.put("name", attribute);
			node.put("label", attribute);
			node.put("dataType", "STRING");
		}
		args.put("confidence", 90);
		args.put("rationale", "One real-world concept.");
		final LocalTool.Result result = pSession.proposeTargetEntity(args);
		IdentityFactsTest.check("entity " + pName + ": " + result.text, !result.isError);
	}

	static void feed(final StagingLlmPass.Session pSession, final String pEntity, final String pTable, final String... pRules)
	{
		final ObjectNode args = IdentityFactsTest.MAPPER.createObjectNode();
		args.put("entity", pEntity);
		args.put("landingTable", pTable);
		final ArrayNode rules = args.putArray("rules");
		for (final String spec : pRules)
		{
			final String[] parts = spec.split(",", -1);
			final ObjectNode rule = rules.addObject();
			rule.put("attribute", parts[0]);
			rule.put("landingColumn", parts[1]);
		}
		args.put("confidence", 85);
		args.put("rationale", "Read as it stands.");
		final LocalTool.Result result = pSession.proposeFeed(args);
		IdentityFactsTest.check("feed " + pEntity + " <- " + pTable + ": " + result.text, !result.isError);
	}

	static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
