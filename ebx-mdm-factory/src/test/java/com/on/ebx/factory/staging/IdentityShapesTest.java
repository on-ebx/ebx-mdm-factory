package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free, like the rest of this module's tests. Lot F2 (2026-10-19): the MEASURED SHAPE FACTS the staging model is given for a text column the catalogue gave no identity tag (e-mail-shaped,
 * phone-shaped, two capitalised words), counted on the values the profiler already reads, and the COVERAGE feedback per target entity (fed by k of the m landing tables that carry its kind of
 * columns, rows it would receive, in the follow-up turn and as a line of the entity's rationale). Facts only: every test also checks what stays silent and that nothing is decided on the model's behalf.
 */
public final class IdentityShapesTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		IdentityShapesTest.shapesOnFixtures();
		IdentityShapesTest.thresholdsAreMeasurementThresholds();
		IdentityShapesTest.aShapeIsNeverATag();
		IdentityShapesTest.theInventoryLabelsTheMeasure();
		IdentityShapesTest.theFollowUpCountsAShapeColumnAsIdentityBearing();
		IdentityShapesTest.theOpenPointSaysHowFirmTheFactIs();
		IdentityShapesTest.coverageOfAnEntityPerSource();
		IdentityShapesTest.coverageIsSilentWhenThereIsNothingToCompare();
		IdentityShapesTest.coverageLineInTheRationaleInBothLanguages();
		IdentityShapesTest.theModelKeepsTheLastWord();
		System.out.println("IdentityShapesTest OK");
	}

	// ------------------------------------------------------------------------------------------------------------ shapes

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

	private static void shapesOnFixtures()
	{
		for (final String yes : new String[] { "a@x.fr", "camille.martin12@gmail.com", " A@X.FR ", "a.b@c.d.org" })
		{
			IdentityFactsTest.check("e-mail-shaped: " + yes, IdentityShapes.emailShaped(yes));
		}
		for (final String no : new String[] { "a@x", "a@@x.fr", "a b@x.fr", "@x.fr", "a@.fr", "a@x.", "ax.fr", "", "12345" })
		{
			IdentityFactsTest.check("not e-mail-shaped: '" + no + "'", !IdentityShapes.emailShaped(no));
		}
		for (final String yes : new String[] { "0612345678", "06 12 34 56 78", "06.12.34.56.78", "+33 6 12 34 56 78", "+33612345678", "(01) 23 45 67 89", "06-12-34-56-78", "0033612345678" })
		{
			IdentityFactsTest.check("phone-shaped, with or without separators: " + yes, IdentityShapes.phoneShaped(yes));
		}
		for (final String no : new String[] { "612345678", "1980-05-12", "12/05/1980", "20261018", "1234567", "1234567890123456", "FID1234567890", "T12345678", "12,50", "", "+" })
		{
			IdentityFactsTest.check("not phone-shaped: '" + no + "'", !IdentityShapes.phoneShaped(no));
		}
		for (final String yes : new String[] { "Camille Martin", "MARTIN Camille", "Jean-Pierre O'Brien".replace("Jean-Pierre O'Brien", "Jean-Pierre O’Brien"), "Hélène Lefèvre" })
		{
			IdentityFactsTest.check("two-capitalised-words-shaped: " + yes, IdentityShapes.nameShaped(yes));
		}
		for (final String no : new String[] { "MARTIN", "camille martin", "Camille de Martin", "Camille 2", "Camille", "J. Martin", "" })
		{
			IdentityFactsTest.check("not two-capitalised-words-shaped: '" + no + "'", !IdentityShapes.nameShaped(no));
		}
		// detection on the values of a column
		final IdentityShapes.Detected emails = IdentityShapes.detect(IdentityShapesTest.counted("a@x.fr", "A@X.fr", "b@x.fr", "", "c@x.fr", ""));
		IdentityFactsTest.check("an e-mail column: 4 filled rows, all e-mail-shaped", emails != null && emails.kind == IdentityFacts.Kind.EMAIL && emails.percent == 100 && emails.rowsRead == 4L
			&& "e-mail-shaped in 100% of sampled values".equals(emails.words()));
		final IdentityShapes.Detected phones = IdentityShapesTest.detect("0612345678", "06 12 34 56 79", "+33 6 12 34 56 80", "07.00.00.00.01");
		IdentityFactsTest.check("a phone column written four ways", phones != null && phones.kind == IdentityFacts.Kind.PHONE && phones.percent == 100);
		final IdentityShapes.Detected plain = IdentityShapesTest.detect("0612345678", "0612345679", "0612345680");
		IdentityFactsTest.check("a phone written without a separator is measured too (the catalogue's regex needs two): " + plain, plain != null && plain.kind == IdentityFacts.Kind.PHONE);
		final IdentityShapes.Detected names = IdentityShapesTest.detect("Camille Martin", "Hugo Dubois", "Léa Durand", "Louis Petit");
		IdentityFactsTest.check("a full-name column", names != null && names.kind == IdentityFacts.Kind.NAME && "two-capitalised-words-shaped in 100% of sampled values".equals(names.words()));
		IdentityFactsTest.check("a column of last names alone is not name-shaped: nothing is claimed", IdentityShapesTest.detect("DUPONT", "MARTIN", "LEROY") == null);
		// not identity-like at all
		IdentityFactsTest.check("a numeric column is not e-mail-shaped nor phone-shaped", IdentityShapesTest.detect("12,50", "8,00", "199,90", "42,00") == null);
		IdentityFactsTest.check("a column of bare identifiers (digits only, no leading 0) is not phone-shaped", IdentityShapesTest.detect("123456789", "234567891", "345678912") == null);
		IdentityFactsTest.check("a column of yyyyMMdd and ISO dates is not phone-shaped", IdentityShapesTest.detect("19800512", "1975-01-30", "12/05/1980") == null);
		IdentityFactsTest.check("nothing filled, nothing measured", IdentityShapes.detect(IdentityShapesTest.counted("", " ")) == null && IdentityShapes.detect(null) == null);
	}

	private static IdentityShapes.Detected detect(final String... pValues)
	{
		return IdentityShapes.detect(IdentityShapesTest.counted(pValues));
	}

	private static void thresholdsAreMeasurementThresholds()
	{
		final List<Detectors.ValueCount> eighty = Arrays.asList(new Detectors.ValueCount("a@x.fr", 80L), new Detectors.ValueCount("n/a", 20L));
		final IdentityShapes.Detected at = IdentityShapes.detect(eighty);
		IdentityFactsTest.check("exactly the e-mail threshold (" + IdentityShapes.EMAIL_MIN_PERCENT + "%) is reported, weighted by rows", at != null && at.percent == 80);
		final List<Detectors.ValueCount> below = Arrays.asList(new Detectors.ValueCount("a@x.fr", 79L), new Detectors.ValueCount("n/a", 21L));
		IdentityFactsTest.check("one row below it is not", IdentityShapes.detect(below) == null);
		final List<Detectors.ValueCount> phone89 = Arrays.asList(new Detectors.ValueCount("0612345678", 89L), new Detectors.ValueCount("n/a", 11L));
		IdentityFactsTest.check("phone and name need " + IdentityShapes.PHONE_MIN_PERCENT + "%: 89% is not enough", IdentityShapes.detect(phone89) == null);
		IdentityFactsTest.check("the text states every threshold", "e-mail 80%, phone 90%, two capitalised words 90%".equals(IdentityShapes.thresholdsText()));
		IdentityFactsTest.check("and the system prompt carries them, with the label of the measure", StagingLlmPass.systemPrompt().contains("measurement thresholds: " + IdentityShapes.thresholdsText())
			&& StagingLlmPass.systemPrompt().contains("under 'identity-like by shape'") && StagingLlmPass.systemPrompt().contains("not a tag"));
	}

	private static void aShapeIsNeverATag()
	{
		final StagingRules.LandingColumnInfo column = IdentityFactsTest.column("EMAIL", false, null, 3L);
		column.shapeIdentity = new IdentityShapes.Detected(IdentityFacts.Kind.EMAIL, 98, 131L);
		IdentityFactsTest.check("no tag, no suggestion: identity-bearing by shape only", IdentityFacts.shapeOnly(column) && IdentityFacts.kindOf(column) == IdentityFacts.Kind.EMAIL
			&& IdentityFacts.tagKindOf(column) == null && column.tags.isEmpty() && IdentityFacts.BASIS_SHAPES.equals(IdentityFacts.basisOf(column)));
		IdentityFactsTest.check("it is told as a measure", "e-mail-shaped in 98% of sampled values".equals(IdentityFacts.shapeWords(column)) && IdentityFacts.tagWords(column).isEmpty());
		// the catalogue's own verdict wins
		final StagingRules.LandingColumnInfo tagged = IdentityFactsTest.column("TEL", false, null, 3L, "PHONE");
		tagged.shapeIdentity = new IdentityShapes.Detected(IdentityFacts.Kind.EMAIL, 98, 10L);
		IdentityFactsTest.check("a tagged column keeps its tag's kind and basis, the shape adds nothing", IdentityFacts.kindOf(tagged) == IdentityFacts.Kind.PHONE && !IdentityFacts.shapeOnly(tagged)
			&& "APPLIED".equals(IdentityFacts.basisOf(tagged)) && IdentityFacts.shapeWords(tagged) == null);
		final StagingRules.LandingColumnInfo suggested = IdentityFactsTest.column("TEL2", false, null, 3L);
		suggested.suggestions.add(IdentityFactsTest.suggestion("PHONE", "REGEX", 96, "PENDING"));
		suggested.shapeIdentity = new IdentityShapes.Detected(IdentityFacts.Kind.PHONE, 100, 10L);
		IdentityFactsTest.check("a suggestion of the catalogue is not a shape fact either", !IdentityFacts.shapeOnly(suggested) && "SAMPLED".equals(IdentityFacts.basisOf(suggested)));
		// a person said no
		final StagingRules.LandingColumnInfo rejected = IdentityFactsTest.column("X", false, null, 3L);
		rejected.suggestions.add(IdentityFactsTest.suggestion("EMAIL", "REGEX", 91, "REJECTED"));
		rejected.shapeIdentity = new IdentityShapes.Detected(IdentityFacts.Kind.EMAIL, 99, 10L);
		IdentityFactsTest.check("a steward rejected that very tag: the shape does not overrule them", IdentityFacts.kindOf(rejected) == null);
		rejected.shapeIdentity = new IdentityShapes.Detected(IdentityFacts.Kind.PHONE, 99, 10L);
		IdentityFactsTest.check("but a rejection of another kind leaves the shape alone", IdentityFacts.kindOf(rejected) == IdentityFacts.Kind.PHONE);
		IdentityFactsTest.check("no shape, nothing", IdentityFacts.kindOf(IdentityFactsTest.column("Y", false, null, 3L)) == null);
	}

	// -------------------------------------------------------------------------------------- the STORES fixture, untagged

	/**
	 * The B2C shape with a ticket table the catalogue never tagged: ECOM and LOYALTY carry applied tags, SHOP.STORES none (its e-mail and phone are only measured from their shapes; its
	 * NOM is one upper-case word). Row counts as given; the identity figures are measured the way {@code ebx.IdentityReader} does, on the columns {@link IdentityFacts#kindOf} selects.
	 */
	private static StagingLlmPass.ProjectInfo project(final long pEcomRows, final long pLoyaltyRows, final long pStoresRows)
	{
		final Map<String, IdentityFacts.Table> facts = IdentityFactsTest.fixture(false);
		final StagingRules.LandingTableInfo ecom = IdentityFactsTest.table("WEB", "ECOM", pEcomRows, IdentityFactsTest.column("customerId", true, "SOURCE", 2L),
			IdentityFactsTest.column("email", false, null, 2L, "PERSONAL_DATA", "EMAIL"), IdentityFactsTest.column("mobile", false, null, 2L, "PHONE"),
			IdentityFactsTest.column("city", false, null, 1L));
		final StagingRules.LandingTableInfo loyalty = IdentityFactsTest.table("LOY", "MEMBERS", pLoyaltyRows, IdentityFactsTest.column("card_number", true, "SOURCE", 2L),
			IdentityFactsTest.column("email", false, null, 2L, "EMAIL"), IdentityFactsTest.column("telephone", false, null, 2L, "PHONE"));
		final String[][] stores = {
			{ "NUM_TICKET", "T10000001", "T10000002", "T10000003", "T10000004", "T10000005", "T10000006" },
			{ "MAGASIN", "PAR-BASTILLE", "PAR-BASTILLE", "LYO-PARTDIEU", "LYO-PARTDIEU", "MRS-PRADO", "MRS-PRADO" },
			{ "MONTANT", "12,50", "8,00", "199,90", "42,00", "7,10", "3,00" },
			{ "NOM", "DUPONT", "DUPONT", "MARTIN", "MARTIN", "LEROY", "" },
			{ "TELEPHONE", "06.11.22.33.44", "+33 6 11 22 33 44", "07.00.00.00.01", "07.00.00.00.01", "07.00.00.00.02", "" },
			{ "EMAIL", "a@x.fr", "A@X.fr", "b@x.fr", "", "c@x.fr", "" } };
		final List<StagingRules.LandingColumnInfo> columns = new ArrayList<StagingRules.LandingColumnInfo>();
		for (final String[] column : stores)
		{
			final StagingRules.LandingColumnInfo info = IdentityFactsTest.column(column[0], "NUM_TICKET".equals(column[0]), "NUM_TICKET".equals(column[0]) ? "USER" : null,
				"NUM_TICKET".equals(column[0]) ? pStoresRows : 3L);
			info.shapeIdentity = IdentityShapes.detect(IdentityShapesTest.counted(Arrays.copyOfRange(column, 1, column.length)));
			columns.add(info);
		}
		final StagingRules.LandingTableInfo ticket = IdentityFactsTest.table("SHOP", "STORES", pStoresRows, columns.toArray(new StagingRules.LandingColumnInfo[0]));
		// the single read of IdentityReader: the columns kindOf selects, in table order
		final List<String> fields = new ArrayList<String>();
		final List<IdentityFacts.Kind> kinds = new ArrayList<IdentityFacts.Kind>();
		final List<Integer> index = new ArrayList<Integer>();
		for (int c = 0; c < columns.size(); c++)
		{
			final IdentityFacts.Kind kind = IdentityFacts.kindOf(columns.get(c));
			if (kind != null)
			{
				fields.add(columns.get(c).fieldName);
				kinds.add(kind);
				index.add(Integer.valueOf(c));
			}
		}
		final IdentityFacts.Accumulator accumulator = new IdentityFacts.Accumulator(fields, kinds, false);
		for (int row = 1; row < stores[0].length; row++)
		{
			final String[] values = new String[fields.size()];
			for (int f = 0; f < values.length; f++)
			{
				values[f] = stores[index.get(f).intValue()][row];
			}
			accumulator.row(values);
		}
		final Map<String, IdentityFacts.Table> measured = new LinkedHashMap<String, IdentityFacts.Table>();
		measured.put("WEB.ECOM", facts.get("WEB.ECOM"));
		measured.put("LOY.MEMBERS", facts.get("LOY.MEMBERS"));
		measured.put("SHOP.STORES", accumulator.finish());
		IdentityFacts.crossTable(measured);
		IdentityFactsTest.attach(ecom, measured.get("WEB.ECOM"));
		IdentityFactsTest.attach(loyalty, measured.get("LOY.MEMBERS"));
		IdentityFactsTest.attach(ticket, measured.get("SHOP.STORES"));
		return new StagingLlmPass.ProjectInfo("B2C", "B2C", Arrays.asList(ecom, loyalty, ticket), Collections.<StagingRules.RelationInfo>emptyList(),
			Arrays.asList(new StagingLlmPass.RuleEntityInfo("Ecom", "WEB", "ECOM"), new StagingLlmPass.RuleEntityInfo("Members", "LOY", "MEMBERS"),
				new StagingLlmPass.RuleEntityInfo("Stores", "SHOP", "STORES")));
	}

	private static StagingLlmPass.Session personSession(final StagingLlmPass.ProjectInfo pProject)
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(pProject, new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(session, "Client", "customerId", "email", "phone");
		IdentityFactsTest.feed(session, "Client", "WEB.ECOM", "customerId,customerId", "email,email", "phone,mobile");
		IdentityFactsTest.feed(session, "Client", "LOY.MEMBERS", "customerId,card_number", "email,email", "phone,telephone");
		return session;
	}

	private static void theInventoryLabelsTheMeasure()
	{
		final String prompt = StagingLlmPass.userPrompt(IdentityShapesTest.project(160L, 140L, 234L));
		final String email = prompt.substring(prompt.indexOf("    - EMAIL (type"), prompt.indexOf("\n", prompt.indexOf("    - EMAIL (type")));
		IdentityFactsTest.check("an untagged e-mail column: the measure, its share, the counts - and no tag: " + email,
			"    - EMAIL (type STRING, fill rate 100%, identity-like by shape (e-mail-shaped in 100% of sampled values; no catalogue tag): 3 distinct values over 4 filled rows, repeated on 2 rows)".equals(email));
		final String phone = prompt.substring(prompt.indexOf("    - TELEPHONE (type"), prompt.indexOf("\n", prompt.indexOf("    - TELEPHONE (type")));
		IdentityFactsTest.check("an untagged phone written with dots: " + phone,
			"    - TELEPHONE (type STRING, fill rate 100%, identity-like by shape (phone-shaped in 100% of sampled values; no catalogue tag): 3 distinct values over 5 filled rows, repeated on 4 rows)".equals(phone));
		final String nom = prompt.substring(prompt.indexOf("    - NOM (type"), prompt.indexOf("\n", prompt.indexOf("    - NOM (type")));
		IdentityFactsTest.check("an upper-case last name alone has no identity shape: nothing said: " + nom, !nom.contains("identity"));
		IdentityFactsTest.check("an amount and a ticket number have none either", !prompt.substring(prompt.indexOf("MONTANT (type"), prompt.indexOf("NOM (type")).contains("identity")
			&& !prompt.substring(prompt.indexOf("NUM_TICKET (type"), prompt.indexOf("MAGASIN (type")).contains("identity"));
		IdentityFactsTest.check("tagged columns keep their line, word for word", prompt.contains("email (type STRING, tags: PERSONAL_DATA, EMAIL, fill rate 100%, identity: 2 distinct values over 2 filled rows"));
		IdentityFactsTest.check("the table still says its key carries no identity TAG: the shapes are a measure, not a tag",
			prompt.contains("- SHOP.STORES (6 columns, 234 rows, key: NUM_TICKET (set by a person; 234 distinct values over 234 rows; carries no identity tag))"));
		IdentityFactsTest.check("no value in the prompt", !prompt.contains("a@x.fr") && !prompt.contains("07.00") && !prompt.contains("DUPONT"));
	}

	private static void theFollowUpCountsAShapeColumnAsIdentityBearing()
	{
		final StagingLlmPass.Session session = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L));
		final List<IdentityCoverage.Gap> gaps = session.identityGaps();
		IdentityFactsTest.check("the untagged ticket table is a gap, its identity measured from shapes: " + gaps.size(), gaps.size() == 1 && "SHOP.STORES".equals(gaps.get(0).landingTableKey)
			&& gaps.get(0).hasShapes() && !gaps.get(0).suggestedOnly() && gaps.get(0).bases.equals(Arrays.asList(IdentityFacts.BASIS_SHAPES)));
		final String line = gaps.get(0).followUpText();
		IdentityFactsTest.check("the sentence, basis first, then the counts and both answers: " + line, line.equals("SHOP.STORES: 5 rows carry identity attributes (measured from value shapes, no catalogue tag: "
			+ "TELEPHONE (phone-shaped in 100% of sampled values), EMAIL (e-mail-shaped in 100% of sampled values); columns TELEPHONE, EMAIL) but feed no entity that holds them; among them "
			+ "1 distinct e-mail, 1 distinct phone are seen in no other source - they will be absent from the master. Feed them (a root feed of Client on SHOP.STORES, one row = one NUM_TICKET) "
			+ "or say in a note why not."));
		final String prompt = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, gaps);
		IdentityFactsTest.check("the follow-up explains what 'measured from value shapes' means, once, with the thresholds: " + prompt, prompt.contains("A column listed under 'measured from value shapes' has no tag in the catalogue")
			&& prompt.contains("e-mail 80%, phone 90%, two capitalised words 90%") && prompt.contains("how the values look, not what the column is")
			&& prompt.indexOf("A column listed under 'measured from value shapes'") == prompt.lastIndexOf("A column listed under 'measured from value shapes'")
			&& !prompt.contains("A tag 'suggested by the catalogue'"));
		IdentityFactsTest.check("the choice is still the model's", prompt.contains("Either answer stands"));
		// a table whose tags are all applied: no paragraph about shapes
		final String tagged = StagingLlmPass.followUpPrompt(IdentityFactsTest.sessionWithPersonEntity(), Collections.<String>emptyList(), null, null, null, IdentityFactsTest.sessionWithPersonEntity().identityGaps());
		IdentityFactsTest.check("applied tags only: no paragraph about shapes", !tagged.contains("measured from value shapes"));
		// fed: no gap
		IdentityFactsTest.feed(session, "Client", "SHOP.STORES", "email,EMAIL", "phone,TELEPHONE");
		IdentityFactsTest.check("a ticket table fed from its shape-measured columns raises nothing", session.identityGaps().isEmpty());
		// the cross-table figure is computed for a shape column just as for a tagged one
		final IdentityFacts.Table stores = IdentityShapesTest.project(160L, 140L, 234L).tables.get(2).identity;
		IdentityFactsTest.check("seen-nowhere-else is measured for shape-based columns: " + stores.seenNowhereElse, Long.valueOf(1L).equals(stores.seenNowhereElse.get(IdentityFacts.Kind.EMAIL))
			&& Long.valueOf(1L).equals(stores.seenNowhereElse.get(IdentityFacts.Kind.PHONE)));
	}

	private static void theOpenPointSaysHowFirmTheFactIs()
	{
		final IdentityCoverage.Gap gap = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L)).identityGaps().get(0);
		final String english = ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.ENGLISH));
		IdentityFactsTest.check("English: " + english, english.equals("To check: STORES: 5 rows carrying an identity (e-mail, phone - columns without a catalogue tag, measured from value shapes) feed no "
			+ "entity that holds it; 1 e-mails, 1 phones seen in no other source, they would be absent from the master - add a root feed of Client on STORES, or say why not (suggestion: check before accepting)."));
		final String french = ProposalChecks.identitySentence(gap, "Client", IdentityFactsTest.words(Locale.FRENCH));
		IdentityFactsTest.check("French: " + french, french.contains("(e-mail, téléphone - colonnes sans étiquette du catalogue, mesurées sur la forme des valeurs) n’alimentent aucune entité qui la porte")
			&& !french.contains("stagingProposal.") && !french.contains("{"));
	}

	// ------------------------------------------------------------------------------------------------------- coverage

	private static void coverageOfAnEntityPerSource()
	{
		final StagingLlmPass.Session session = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L));
		final List<IdentityCoverage.EntityCoverage> coverage = session.entityCoverage();
		IdentityFactsTest.check("one entity, fed by 2 of the 3 tables that carry its kind of columns: " + coverage.size(), coverage.size() == 1 && "Client".equals(coverage.get(0).entity)
			&& coverage.get(0).carrying.equals(Arrays.asList("WEB.ECOM", "LOY.MEMBERS", "SHOP.STORES")) && coverage.get(0).fed.equals(Arrays.asList("WEB.ECOM", "LOY.MEMBERS"))
			&& coverage.get(0).partial() && coverage.get(0).notFed().equals(Arrays.asList("SHOP.STORES")));
		final String line = coverage.get(0).followUpText();
		IdentityFactsTest.check("the line, rows per source: " + line, line.equals("Client is fed by 2 of the 3 landing tables that carry its kind of columns (e-mail, phone); it would receive 300 rows "
			+ "(WEB.ECOM 160, LOY.MEMBERS 140; SHOP.STORES 234 not fed)."));
		final String prompt = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, session.identityGaps(), coverage);
		IdentityFactsTest.check("the follow-up carries it, fenced, with the two answers: " + prompt, prompt.contains(line) && prompt.contains("ENTITY COVERAGE") && prompt.contains("Coverage of the entities you proposed")
			&& prompt.contains("call propose_feed to read it into that entity, or call note with the table key and the reason it does not belong there. Either answer stands"));
		IdentityFactsTest.check("it rides on the one follow-up prompt, after the identity paragraph", prompt.indexOf("IDENTITY WITHOUT A FEED") < prompt.indexOf("ENTITY COVERAGE"));
		// the model feeds the missing table: the line says so, the question goes
		IdentityFactsTest.feed(session, "Client", "SHOP.STORES", "email,EMAIL", "phone,TELEPHONE");
		final List<IdentityCoverage.EntityCoverage> full = session.entityCoverage();
		IdentityFactsTest.check("fed by all three: " + full.get(0).followUpText(), !full.get(0).partial() && full.get(0).followUpText().equals("Client is fed by 3 of the 3 landing tables that carry its kind of "
			+ "columns (e-mail, phone); it would receive 534 rows (WEB.ECOM 160, LOY.MEMBERS 140, SHOP.STORES 234)."));
		final String fullPrompt = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, session.identityGaps(), full);
		IdentityFactsTest.check("nothing is left unfed: no question", fullPrompt.contains("ENTITY COVERAGE") && !fullPrompt.contains("not fed") && !fullPrompt.contains("propose_feed to read it into that entity"));
		IdentityFactsTest.check("without coverage the follow-up says nothing of it", !StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, null, null, null, null).contains("ENTITY COVERAGE"));
		// unknown rows are not invented
		final StagingLlmPass.ProjectInfo project = IdentityShapesTest.project(160L, 140L, 234L);
		project.tables.get(1).landedRows = null;
		final IdentityCoverage.EntityCoverage unknown = IdentityShapesTest.personSession(project).entityCoverage().get(0);
		IdentityFactsTest.check("a table whose rows are unknown is said so and the total is a lower bound: " + unknown.followUpText(), unknown.followUpText().equals("Client is fed by 2 of the 3 landing tables that carry "
			+ "its kind of columns (e-mail, phone); it would receive at least 160 rows (WEB.ECOM 160, LOY.MEMBERS (rows unknown); SHOP.STORES 234 not fed)."));
	}

	private static void coverageIsSilentWhenThereIsNothingToCompare()
	{
		// an entity that reads no identity column has no kind of columns to compare
		final StagingLlmPass.Session session = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L));
		IdentityFactsTest.entity(session, "Achat", "ticket", "amount");
		IdentityFactsTest.feed(session, "Achat", "SHOP.STORES", "ticket,NUM_TICKET", "amount,MONTANT");
		final List<IdentityCoverage.EntityCoverage> coverage = session.entityCoverage();
		IdentityFactsTest.check("only the entity that reads identity columns gets a line: " + coverage.size(), coverage.size() == 1 && "Client".equals(coverage.get(0).entity));
		// a single table carries the kind: nothing to compare with
		final StagingLlmPass.Session lone = new StagingLlmPass.Session(new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(IdentityFactsTest.table("X", "T", 3L,
			IdentityFactsTest.column("id", true, "RULE", 3L), IdentityFactsTest.column("mail", false, null, 3L, "EMAIL"))), Collections.<StagingRules.RelationInfo>emptyList(),
			Arrays.asList(new StagingLlmPass.RuleEntityInfo("T", "X", "T"))), new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(lone, "Contact", "mail");
		IdentityFactsTest.feed(lone, "Contact", "X.T", "mail,mail");
		IdentityFactsTest.check("one carrying table, no line", lone.entityCoverage().isEmpty());
		// no tag, no shape: no kind
		final StagingLlmPass.Session plain = new StagingLlmPass.Session(new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(IdentityFactsTest.table("X", "T", 3L,
			IdentityFactsTest.column("id", true, "RULE", 3L), IdentityFactsTest.column("mail", false, null, 3L)), IdentityFactsTest.table("Y", "U", 3L, IdentityFactsTest.column("id", true, "RULE", 3L),
			IdentityFactsTest.column("mail", false, null, 3L))), Collections.<StagingRules.RelationInfo>emptyList(), Arrays.asList(new StagingLlmPass.RuleEntityInfo("T", "X", "T"))),
			new ArrayList<StagingLlmPass.ExistingTargetEntity>());
		IdentityFactsTest.entity(plain, "Contact", "mail");
		IdentityFactsTest.feed(plain, "Contact", "X.T", "mail,mail");
		IdentityFactsTest.check("a column called mail but neither tagged nor shaped is not a kind of column", plain.entityCoverage().isEmpty());
	}

	private static void coverageLineInTheRationaleInBothLanguages()
	{
		final StagingLlmPass.Session session = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L));
		session.finalizeEntities();
		final List<ProposalChecks.Finding> findings = session.reviewModelling(IdentityFactsTest.words(Locale.ENGLISH));
		final StagingLlmPass.CreatedEntity client = IdentityFactsTest.created(session, "Client");
		IdentityFactsTest.check("the identity open point is still the only finding: " + findings, findings.size() == 1 && ProposalChecks.IDENTITY_SOURCE_UNFED.equals(findings.get(0).kind));
		IdentityFactsTest.check("the rationale starts with the open point, then the coverage line, then the model's own text: " + client.rationale, client.rationale.startsWith("To check: STORES: 5 rows carrying an identity")
			&& client.rationale.contains("Coverage: Client is fed by 2 of the 3 landing tables that carry its kind of columns (e-mail, phone); it would receive 300 rows (WEB.ECOM 160, LOY.MEMBERS 140; SHOP.STORES 234 not fed). "
				+ "One real-world concept."));
		IdentityFactsTest.check("the coverage line changes no confidence (the open point caps it, as before)", client.confidence == ProposalChecks.IDENTITY_UNFED_CONFIDENCE_CAP);
		// French, and an entity fully fed: the line alone, confidence untouched
		final StagingLlmPass.Session fed = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L));
		IdentityFactsTest.feed(fed, "Client", "SHOP.STORES", "email,EMAIL", "phone,TELEPHONE");
		fed.finalizeEntities();
		final List<ProposalChecks.Finding> none = fed.reviewModelling(IdentityFactsTest.words(Locale.FRENCH));
		final StagingLlmPass.CreatedEntity frenchClient = IdentityFactsTest.created(fed, "Client");
		IdentityFactsTest.check("no finding when everything is fed", none.isEmpty());
		IdentityFactsTest.check("French: " + frenchClient.rationale, frenchClient.rationale.startsWith("Couverture : Client est alimentée par 3 des 3 tables de landing qui portent le même genre de colonnes "
			+ "(e-mail, téléphone) ; elle recevrait 534 lignes (WEB.ECOM 160, LOY.MEMBERS 140, SHOP.STORES 234). One real-world concept.") && frenchClient.confidence == 90);
		IdentityFactsTest.check("no raw key, no unfilled argument", !frenchClient.rationale.contains("stagingProposal.") && !frenchClient.rationale.contains("{"));
		// unknown rows, in the viewer's language
		final StagingLlmPass.ProjectInfo project = IdentityShapesTest.project(160L, 140L, 234L);
		project.tables.get(1).landedRows = null;
		final String english = ProposalChecks.coverageSentence(IdentityShapesTest.personSession(project).entityCoverage().get(0), "Client", IdentityFactsTest.words(Locale.ENGLISH));
		IdentityFactsTest.check("English, rows unknown: " + english, english.equals("Coverage: Client is fed by 2 of the 3 landing tables that carry its kind of columns (e-mail, phone); it would receive at least 160 rows "
			+ "(WEB.ECOM 160, LOY.MEMBERS (rows unknown); SHOP.STORES 234 not fed)."));
		final String french = ProposalChecks.coverageSentence(IdentityShapesTest.personSession(project).entityCoverage().get(0), "Client", IdentityFactsTest.words(Locale.FRENCH));
		IdentityFactsTest.check("French, rows unknown: " + french, french.contains("au moins 160 lignes (WEB.ECOM 160, LOY.MEMBERS (nombre de lignes inconnu) ; SHOP.STORES 234 non alimentée)".replace(" ; ", "; ")));
	}

	/** The model answers with a note, with more feeds or with nothing: the written proposal is its own, nothing is fed, rejected or dropped by the Factory. */
	private static void theModelKeepsTheLastWord()
	{
		final StagingLlmPass.Session session = IdentityShapesTest.personSession(IdentityShapesTest.project(160L, 140L, 234L));
		IdentityFactsTest.noted(session, "SHOP.STORES: each row is a purchase; the people are not customers of the shop.");
		final int feeds = session.createdFeeds().size();
		session.finalizeEntities();
		session.reviewModelling(IdentityFactsTest.words(Locale.ENGLISH));
		IdentityFactsTest.check("no feed was added on the model's behalf", session.createdFeeds().size() == feeds);
		IdentityFactsTest.check("the model's note follows the open point as its reason", IdentityFactsTest.created(session, "Client").rationale.contains("each row is a purchase"));
		IdentityFactsTest.check("a second review adds nothing", session.reviewModelling(IdentityFactsTest.words(Locale.ENGLISH)).isEmpty());
		// the Java sources name no business word for the shapes
		final String sources = IdentityShapes.class.getName();
		IdentityFactsTest.check("the shape kinds are the existing identity kinds, no new vocabulary: " + sources, IdentityShapes.shapeWord(IdentityFacts.Kind.OFFICIAL_ID) == null
			&& IdentityShapes.shapeWord(IdentityFacts.Kind.PERSON_ID) == null && IdentityShapes.thresholdOf(IdentityFacts.Kind.OFFICIAL_ID) == 0);
	}
}
