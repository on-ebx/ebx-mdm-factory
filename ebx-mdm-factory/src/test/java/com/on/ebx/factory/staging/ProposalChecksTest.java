package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. Fix/staging-proposal-quality (2026-10-17, item 2): the modelling checks on the shape measured on
 * TiersB2CRetestLanding (Customer / CustomerAddress / StoreTransaction), and the same checks on an unrelated retail shape (domain-free).
 */
public final class ProposalChecksTest
{
	private static final ProposalChecks.Words WORDS = new ProposalChecks.Words()
	{
		@Override
		public String text(final String pKey, final Object... pArgs)
		{
			return "[" + pKey + Arrays.toString(pArgs) + "]";
		}
	};

	public static void main(final String[] pArgs)
	{
		ProposalChecksTest.theTicketFactsOnTheCustomerAreFlaggedAsDuplicates();
		ProposalChecksTest.aFieldNoSourceFeedsIsFlaggedForRejection();
		ProposalChecksTest.theTicketSourceLinkSaysOneRowPerRecord();
		ProposalChecksTest.neverFlagsWhatShouldStay();
		ProposalChecksTest.theSameChecksOnAnUnrelatedDomain();
		ProposalChecksTest.theSessionAppliesTheFindingsInPlace();
		ProposalChecksTest.anEntityNoColumnFeedsIsFlaggedForRejection();
		ProposalChecksTest.columnsLeftBehindBySupersededEntitiesAreFlaggedOnTheCanonical();
		System.out.println("ProposalChecksTest OK");
	}

	/**
	 * Lot M7 (2026-10-18), the shape measured on E2EMASTER20261017235630: {@code ContactEmail} (child of the person entity, attributes {@code client} =
	 * the link to the parent, {@code email}), confidence 89, attributes at 96-100, and NOT ONE feed - accepted in bulk, built, loaded with 0 rows. The
	 * check names the fix on the entity, caps it under every bulk threshold, caps its business attributes (nothing fills them) and leaves the link to the
	 * parent alone; a note of the run naming the entity is kept as the model's reason; an entity that has a feed is never touched.
	 */
	private static void anEntityNoColumnFeedsIsFlaggedForRejection()
	{
		final StagingLlmPass.CreatedEntity customer = ProposalChecksTest.entity("Customer", null, ProposalChecksTest.attribute("customerId", true, 95),
			ProposalChecksTest.attribute("email", false, 90));
		final StagingLlmPass.CreatedEntity contactEmail = new StagingLlmPass.CreatedEntity("ContactEmail", "Adresse electronique de contact", "A distinct means of contact.", 89,
			new ArrayList<StagingLlmPass.CreatedAttribute>(Arrays.asList(ProposalChecksTest.attribute("client", false, 98), ProposalChecksTest.attribute("email", false, 96))),
			"Customer", "client", "clientRef", null);
		final List<StagingLlmPass.CreatedEntity> entities = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(customer, contactEmail));
		final List<StagingLlmPass.CreatedFeed> feeds = new ArrayList<StagingLlmPass.CreatedFeed>(Arrays.asList(
			ProposalChecksTest.feed("Customer", "WEB.Clients", ProposalChecksTest.rule("customerId", "ID"), ProposalChecksTest.rule("email", "EMAIL"))));

		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, feeds, ProposalChecksTest.tables(), ProposalChecksTest.WORDS,
			Arrays.asList("WEB.Clients: PHONE is empty in every row, left out.", "ContactEmail: the sources hold one e-mail per client, kept as a distinct entity for later."));
		ProposalChecksTest.check("one finding, NO_FEED on ContactEmail: " + findings, findings.size() == 1 && ProposalChecks.NO_FEED.equals(findings.get(0).kind)
			&& "ContactEmail".equals(findings.get(0).entity) && findings.get(0).attribute == null);
		final StagingLlmPass.CreatedEntity flagged = entities.get(1);
		ProposalChecksTest.check("the entity is replaced in place, capped under the bulk thresholds", flagged != contactEmail && flagged.confidence == ProposalChecks.NO_FEED_CONFIDENCE_CAP);
		ProposalChecksTest.check("its rationale starts with the sentence (the fix) and keeps the model's own words: " + flagged.rationale,
			flagged.rationale.startsWith("[stagingProposal.check.noFeed[Adresse electronique de contact]]") && flagged.rationale.endsWith("A distinct means of contact."));
		ProposalChecksTest.check("the note naming the entity is kept as the reason", flagged.rationale.contains("kept as a distinct entity for later") && !flagged.rationale.contains("PHONE"));
		ProposalChecksTest.check("the parent link, the child shape and the members are carried over", "Customer".equals(flagged.parentEntity) && "client".equals(flagged.parentLinkAttribute)
			&& "clientRef".equals(flagged.parentLinkField) && flagged.existingId == null && flagged.attributes.size() == 2);
		ProposalChecksTest.check("the link to the parent is left alone", flagged.attributes.get(0).confidence == 98 && "why client".equals(flagged.attributes.get(0).rationale));
		ProposalChecksTest.check("the business attribute nothing fills is capped with the no-source sentence", flagged.attributes.get(1).confidence == ProposalChecks.NO_SOURCE_CONFIDENCE_CAP
			&& flagged.attributes.get(1).rationale.startsWith("[stagingProposal.check.noSource[]]"));
		ProposalChecksTest.check("the fed entity is untouched", entities.get(0) == customer && customer.confidence == 90);

		// no note naming it: the sentence alone; an adopted entity (existingId set) is not reviewed here
		final List<StagingLlmPass.CreatedEntity> again = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(customer, contactEmail,
			new StagingLlmPass.CreatedEntity("Old", "Old", "r", 90, new ArrayList<StagingLlmPass.CreatedAttribute>(), null, null, null, "321")));
		final List<ProposalChecks.Finding> plain = ProposalChecks.review(again, new ArrayList<StagingLlmPass.CreatedFeed>(feeds), ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		ProposalChecksTest.check("without a note the sentence stands alone: " + again.get(1).rationale, plain.size() == 1
			&& again.get(1).rationale.equals("[stagingProposal.check.noFeed[Adresse electronique de contact]] A distinct means of contact."));
		ProposalChecksTest.check("an adopted entity is never flagged", again.get(2).confidence == 90);
		ProposalChecksTest.check("the note matcher takes whole words only", ProposalChecks.noteNaming("Email", Arrays.asList("ContactEmail is kept.")) == null
			&& "ContactEmail is kept.".equals(ProposalChecks.noteNaming("ContactEmail", Arrays.asList("x", "ContactEmail is kept."))) && ProposalChecks.noteNaming("X", null) == null);
	}

	/**
	 * Lot M15 (2026-10-18, E2EMASTER20261018090711): {@code Client} replaced the per-source entities while STORES.TELEPHONE was read only by the superseded one. The
	 * canonical gets SUPERSEDED_COLUMN_LEFT_BEHIND: confidence capped under the bulk bars (not as low as NO_FEED), the sentence naming the columns and the superseded
	 * entities first in its rationale, attributes untouched; an entity of an earlier run (adopted) or one the groups do not name is left alone.
	 */
	private static void columnsLeftBehindBySupersededEntitiesAreFlaggedOnTheCanonical()
	{
		final StagingLlmPass.CreatedEntity client = ProposalChecksTest.entity("Client", null, ProposalChecksTest.attribute("email", false, 90), ProposalChecksTest.attribute("mobile", false, 88));
		final StagingLlmPass.CreatedEntity adopted = new StagingLlmPass.CreatedEntity("Old", "Old", "r", 90, new ArrayList<StagingLlmPass.CreatedAttribute>(), null, null, null, "321");
		final List<StagingLlmPass.CreatedEntity> entities = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(client, adopted));
		final List<StagingLlmPass.CreatedFeed> feeds = new ArrayList<StagingLlmPass.CreatedFeed>(Arrays.asList(
			ProposalChecksTest.feed("Client", "WEB.Clients", ProposalChecksTest.rule("email", "EMAIL"), ProposalChecksTest.rule("mobile", "MOBILE")),
			ProposalChecksTest.feed("Client", "SHOP.Tickets", ProposalChecksTest.rule("email", "EMAIL"))));
		final StagingRules.LandingColumnInfo telephone = new StagingRules.LandingColumnInfo("c1", "TELEPHONE", "TELEPHONE", "STRING", false, new java.math.BigDecimal("100"), Long.valueOf(120),
			Integer.valueOf(14), new ArrayList<String>());
		final StagingRules.LandingColumnInfo shop = new StagingRules.LandingColumnInfo("c2", "MAGASIN", "MAGASIN", "STRING", false, new java.math.BigDecimal("100"), Long.valueOf(12),
			Integer.valueOf(14), new ArrayList<String>());
		final List<StagingLlmPass.LeftBehind> left = Arrays.asList(new StagingLlmPass.LeftBehind("Client", "TicketsRule", "SHOP.Tickets", "SHOP", Arrays.asList(telephone, shop)),
			new StagingLlmPass.LeftBehind("Old", "OldRule", "LOY.Members", "LOY", Arrays.asList(telephone)));

		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, feeds, ProposalChecksTest.tables(), ProposalChecksTest.WORDS, null, left);
		ProposalChecksTest.check("one finding, SUPERSEDED_COLUMN_LEFT_BEHIND on Client: " + findings, findings.size() == 1
			&& ProposalChecks.SUPERSEDED_COLUMN_LEFT_BEHIND.equals(findings.get(0).kind) && "Client".equals(findings.get(0).entity) && findings.get(0).attribute == null);
		final StagingLlmPass.CreatedEntity flagged = entities.get(0);
		ProposalChecksTest.check("the entity is replaced in place and capped under the bulk bars, above the no-feed cap: " + flagged.confidence,
			flagged != client && flagged.confidence == ProposalChecks.LEFT_BEHIND_CONFIDENCE_CAP && flagged.confidence < 60 && flagged.confidence > ProposalChecks.NO_FEED_CONFIDENCE_CAP);
		ProposalChecksTest.check("the sentence names the columns by source and the superseded entity, then the model's own rationale: " + flagged.rationale,
			flagged.rationale.equals("[stagingProposal.check.supersededColumnLeftBehind[client, SHOP.TELEPHONE, SHOP.MAGASIN, TicketsRule]] r"));
		ProposalChecksTest.check("its attributes and their confidence are untouched", flagged.attributes.size() == 2 && flagged.attributes.get(1).confidence == 88
			&& flagged.attributes.get(1).rationale.equals("why mobile"));
		ProposalChecksTest.check("an adopted entity of an earlier run is never rewritten", entities.get(1) == adopted && adopted.confidence == 90);

		final List<StagingLlmPass.CreatedEntity> untouched = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(client));
		ProposalChecksTest.check("with nothing left behind there is no finding", ProposalChecks.review(untouched, new ArrayList<StagingLlmPass.CreatedFeed>(feeds), ProposalChecksTest.tables(),
			ProposalChecksTest.WORDS, null, Collections.<StagingLlmPass.LeftBehind>emptyList()).isEmpty() && untouched.get(0) == client);
	}

	private static StagingLlmPass.CreatedAttribute attribute(final String pName, final boolean pBusinessKey, final int pConfidence)
	{
		return new StagingLlmPass.CreatedAttribute(pName, pName, "STRING", Integer.valueOf(255), false, pBusinessKey, "why " + pName, pConfidence);
	}

	private static StagingLlmPass.CreatedFeedRule rule(final String pAttribute, final String pColumn)
	{
		return new StagingLlmPass.CreatedFeedRule(pAttribute, pColumn, null, "from " + pColumn, 90);
	}

	private static StagingLlmPass.CreatedFeed feed(final String pEntity, final String pTable, final StagingLlmPass.CreatedFeedRule... pRules)
	{
		return new StagingLlmPass.CreatedFeed(pEntity + "|" + pTable, pEntity, pTable, null, null, new ArrayList<StagingLlmPass.CreatedFeedRule>(Arrays.asList(pRules)), null, 88,
			"feed " + pEntity, null);
	}

	private static StagingLlmPass.CreatedEntity entity(final String pName, final String pParent, final StagingLlmPass.CreatedAttribute... pAttributes)
	{
		return new StagingLlmPass.CreatedEntity(pName, pName.toLowerCase(), "r", 90, new ArrayList<StagingLlmPass.CreatedAttribute>(Arrays.asList(pAttributes)), pParent,
			pParent == null ? null : pParent.toLowerCase(), null, null);
	}

	private static Map<String, StagingRules.LandingTableInfo> tables()
	{
		final Map<String, StagingRules.LandingTableInfo> tables = new LinkedHashMap<String, StagingRules.LandingTableInfo>();
		for (final String key : new String[] { "WEB.Clients", "SHOP.Tickets", "LOY.Members" })
		{
			final String[] parts = key.split("\\.");
			tables.put(key, new StagingRules.LandingTableInfo(key, parts[0], parts[1], Long.valueOf(100), Collections.<StagingRules.LandingColumnInfo>emptyList()));
		}
		return tables;
	}

	/** The shape of the live test: tickets feed StoreTransaction AND the same columns feed Customer fields. */
	private static List<StagingLlmPass.CreatedEntity> b2cEntities()
	{
		return new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(
			ProposalChecksTest.entity("Customer", null, ProposalChecksTest.attribute("customerId", true, 95), ProposalChecksTest.attribute("email", false, 90),
				ProposalChecksTest.attribute("storePurchaseDate", false, 85), ProposalChecksTest.attribute("storeCode", false, 85), ProposalChecksTest.attribute("purchaseAmount", false, 85),
				ProposalChecksTest.attribute("city", false, 80), ProposalChecksTest.attribute("streetAddress", false, 80), ProposalChecksTest.attribute("postalCode", false, 80)),
			ProposalChecksTest.entity("CustomerAddress", "Customer", ProposalChecksTest.attribute("customer", false, 95), ProposalChecksTest.attribute("postalCode", false, 90)),
			ProposalChecksTest.entity("StoreTransaction", null, ProposalChecksTest.attribute("storePurchaseDate", false, 90), ProposalChecksTest.attribute("storeCode", false, 90),
				ProposalChecksTest.attribute("purchaseAmount", false, 90))));
	}

	private static List<StagingLlmPass.CreatedFeed> b2cFeeds()
	{
		return new ArrayList<StagingLlmPass.CreatedFeed>(Arrays.asList(
			ProposalChecksTest.feed("Customer", "WEB.Clients", ProposalChecksTest.rule("customerId", "ID"), ProposalChecksTest.rule("email", "MAIL"),
				ProposalChecksTest.rule("postalCode", "ZIP")),
			ProposalChecksTest.feed("Customer", "LOY.Members", ProposalChecksTest.rule("customerId", "MEMBER_ID"), ProposalChecksTest.rule("email", "EMAIL")),
			ProposalChecksTest.feed("Customer", "SHOP.Tickets", ProposalChecksTest.rule("email", "MAIL"), ProposalChecksTest.rule("storePurchaseDate", "TICKET_DATE"),
				ProposalChecksTest.rule("storeCode", "STORE"), ProposalChecksTest.rule("purchaseAmount", "AMOUNT")),
			ProposalChecksTest.feed("CustomerAddress", "WEB.Clients", ProposalChecksTest.rule("postalCode", "ZIP")),
			ProposalChecksTest.feed("StoreTransaction", "SHOP.Tickets", ProposalChecksTest.rule("storePurchaseDate", "TICKET_DATE"), ProposalChecksTest.rule("storeCode", "STORE"),
				ProposalChecksTest.rule("purchaseAmount", "AMOUNT"))));
	}

	private static void theTicketFactsOnTheCustomerAreFlaggedAsDuplicates()
	{
		final List<StagingLlmPass.CreatedEntity> entities = ProposalChecksTest.b2cEntities();
		final List<StagingLlmPass.CreatedFeed> feeds = ProposalChecksTest.b2cFeeds();
		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, feeds, ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		final StringBuilder all = new StringBuilder();
		for (final ProposalChecks.Finding finding : findings)
		{
			all.append(finding).append(" | ");
		}
		final String text = all.toString();
		for (final String field : new String[] { "storePurchaseDate", "storeCode", "purchaseAmount" })
		{
			ProposalChecksTest.check(field + " duplicated on Customer: " + text, text.contains("DUPLICATES_RELATED Customer." + field + " (also StoreTransaction)"));
		}
		ProposalChecksTest.check("the postal code the address entity carries too: " + text, text.contains("DUPLICATES_RELATED Customer.postalCode (also CustomerAddress)"));
		ProposalChecksTest.check("the child is never flagged for the parent's field: " + text, !text.contains("CustomerAddress.postalCode"));
		ProposalChecksTest.check("the transaction entity is not flagged either (it is the narrower side): " + text, !text.contains("DUPLICATES_RELATED StoreTransaction"));
		final StagingLlmPass.CreatedAttribute date = ProposalChecksTest.find(entities.get(0), "storePurchaseDate");
		ProposalChecksTest.check("confidence under the bulk threshold: " + date.confidence, date.confidence == ProposalChecks.DUPLICATE_CONFIDENCE_CAP);
		ProposalChecksTest.check("sentence first, original kept: " + date.rationale,
			date.rationale.startsWith("[stagingProposal.check.duplicatesRelated[storetransaction, customer]]") && date.rationale.endsWith("why storePurchaseDate"));
		ProposalChecksTest.check("the rules feeding it are capped too", feeds.get(2).rules.get(1).confidence == ProposalChecks.DUPLICATE_CONFIDENCE_CAP
			&& feeds.get(2).rules.get(0).confidence == 90);
		ProposalChecksTest.check("the other side is untouched", ProposalChecksTest.find(entities.get(2), "storeCode").confidence == 90
			&& ProposalChecksTest.find(entities.get(2), "storeCode").rationale.equals("why storeCode"));
		ProposalChecksTest.check("email is read by the web and loyalty sources too: not a duplicate", ProposalChecksTest.find(entities.get(0), "email").confidence == 90);
	}

	private static void aFieldNoSourceFeedsIsFlaggedForRejection()
	{
		final List<StagingLlmPass.CreatedEntity> entities = ProposalChecksTest.b2cEntities();
		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, ProposalChecksTest.b2cFeeds(), ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		final StagingLlmPass.CreatedAttribute city = ProposalChecksTest.find(entities.get(0), "city");
		final StagingLlmPass.CreatedAttribute street = ProposalChecksTest.find(entities.get(0), "streetAddress");
		ProposalChecksTest.check("city is a NO_SOURCE finding", findings.toString().contains("NO_SOURCE Customer.city") && findings.toString().contains("NO_SOURCE Customer.streetAddress"));
		ProposalChecksTest.check("capped lower than a duplicate: " + city.confidence, city.confidence == ProposalChecks.NO_SOURCE_CONFIDENCE_CAP && street.confidence == 20);
		ProposalChecksTest.check("says reject: " + city.rationale, city.rationale.startsWith("[stagingProposal.check.noSource[]]"));
		ProposalChecksTest.check("a lower original confidence is kept", ProposalChecksTest.withConfidence(5).confidence == 5);
	}

	private static StagingLlmPass.CreatedAttribute withConfidence(final int pConfidence)
	{
		final List<StagingLlmPass.CreatedEntity> entities = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(
			ProposalChecksTest.entity("Customer", null, ProposalChecksTest.attribute("id", true, 90), ProposalChecksTest.attribute("orphan", false, pConfidence))));
		final List<StagingLlmPass.CreatedFeed> feeds = new ArrayList<StagingLlmPass.CreatedFeed>(Arrays.asList(
			ProposalChecksTest.feed("Customer", "WEB.Clients", ProposalChecksTest.rule("id", "ID"))));
		ProposalChecks.review(entities, feeds, ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		return ProposalChecksTest.find(entities.get(0), "orphan");
	}

	private static void theTicketSourceLinkSaysOneRowPerRecord()
	{
		final List<StagingLlmPass.CreatedEntity> entities = ProposalChecksTest.b2cEntities();
		final List<StagingLlmPass.CreatedFeed> feeds = ProposalChecksTest.b2cFeeds();
		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, feeds, ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		ProposalChecksTest.check("one source-link finding, on the ticket table only: " + findings, findings.toString().contains("ONE_ROW_PER_RECORD Customer <- SHOP.Tickets")
			&& !findings.toString().contains("ONE_ROW_PER_RECORD Customer <- WEB.Clients") && !findings.toString().contains("ONE_ROW_PER_RECORD Customer <- LOY.Members"));
		final StagingLlmPass.CreatedFeed tickets = feeds.get(2);
		ProposalChecksTest.check("the note is on the feed's rationale, original kept: " + tickets.rationale,
			tickets.rationale.startsWith("[stagingProposal.check.oneRowPerRecord[Tickets, customer]]") && tickets.rationale.endsWith("feed Customer"));
		ProposalChecksTest.check("no confidence change on the feed, same rules", tickets.confidence == 88 && tickets.rules.size() == 4 && tickets.feedKey.equals("Customer|SHOP.Tickets"));
		ProposalChecksTest.check("the transaction feed (an entity with no business key) says nothing", feeds.get(4).rationale.equals("feed StoreTransaction"));
	}

	private static void neverFlagsWhatShouldStay()
	{
		// a child feed, a link to the parent, a business key read by both sides - and, since lot M7 (2026-10-18), an entity without any feed IS flagged (NO_FEED, nothing else)
		final StagingLlmPass.CreatedEntity parent = ProposalChecksTest.entity("Person", null, ProposalChecksTest.attribute("code", true, 95),
			ProposalChecksTest.attribute("name", false, 90));
		final StagingLlmPass.CreatedEntity child = ProposalChecksTest.entity("Address", "Person", ProposalChecksTest.attribute("person", false, 95),
			ProposalChecksTest.attribute("code", true, 90), ProposalChecksTest.attribute("street", false, 90));
		final StagingLlmPass.CreatedEntity unfed = ProposalChecksTest.entity("Lonely", null, ProposalChecksTest.attribute("a", false, 90));
		final StagingLlmPass.CreatedFeed parentFeed = ProposalChecksTest.feed("Person", "WEB.Clients", ProposalChecksTest.rule("code", "ID"), ProposalChecksTest.rule("name", "NAME"));
		final StagingLlmPass.CreatedFeed childFeed = new StagingLlmPass.CreatedFeed("Address|WEB.Clients", "Address", "WEB.Clients", "Person|WEB.Clients", null,
			new ArrayList<StagingLlmPass.CreatedFeedRule>(Arrays.asList(ProposalChecksTest.rule("code", "ID"), ProposalChecksTest.rule("street", "STREET"))), null, 90, "child", null);
		final List<StagingLlmPass.CreatedEntity> entities = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(parent, child, unfed));
		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, new ArrayList<StagingLlmPass.CreatedFeed>(Arrays.asList(parentFeed, childFeed)),
			ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		ProposalChecksTest.check("only the entity without a feed is flagged, as NO_FEED: " + findings, findings.size() == 1 && ProposalChecks.NO_FEED.equals(findings.get(0).kind)
			&& "Lonely".equals(findings.get(0).entity));
		ProposalChecksTest.check("the fed objects are untouched", entities.get(0) == parent && entities.get(1) == child
			&& ProposalChecksTest.find(parent, "name").rationale.equals("why name") && parentFeed.rationale.equals("feed Person"));
	}

	/** The same checks on an unrelated retail shape: nothing reads a name. */
	private static void theSameChecksOnAnUnrelatedDomain()
	{
		final List<StagingLlmPass.CreatedEntity> entities = new ArrayList<StagingLlmPass.CreatedEntity>(Arrays.asList(
			ProposalChecksTest.entity("Article", null, ProposalChecksTest.attribute("sku", true, 95), ProposalChecksTest.attribute("shelfPrice", false, 85),
				ProposalChecksTest.attribute("colourNote", false, 80)),
			ProposalChecksTest.entity("PriceChange", "Article", ProposalChecksTest.attribute("article", false, 95), ProposalChecksTest.attribute("shelfPrice", false, 90))));
		final List<StagingLlmPass.CreatedFeed> feeds = new ArrayList<StagingLlmPass.CreatedFeed>(Arrays.asList(
			ProposalChecksTest.feed("Article", "WEB.Clients", ProposalChecksTest.rule("sku", "REF"), ProposalChecksTest.rule("shelfPrice", "PRICE")),
			new StagingLlmPass.CreatedFeed("PriceChange|WEB.Clients", "PriceChange", "WEB.Clients", "Article|WEB.Clients", null,
				new ArrayList<StagingLlmPass.CreatedFeedRule>(Arrays.asList(ProposalChecksTest.rule("shelfPrice", "PRICE"))), null, 90, "child", null)));
		final List<ProposalChecks.Finding> findings = ProposalChecks.review(entities, feeds, ProposalChecksTest.tables(), ProposalChecksTest.WORDS);
		ProposalChecksTest.check("duplicate + no source, same as for the people shape: " + findings, findings.size() == 2
			&& findings.toString().contains("DUPLICATES_RELATED Article.shelfPrice (also PriceChange)") && findings.toString().contains("NO_SOURCE Article.colourNote"));
	}

	private static void theSessionAppliesTheFindingsInPlace()
	{
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", new ArrayList<StagingRules.LandingTableInfo>(ProposalChecksTest.tables().values()), null, null);
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, null);
		ProposalChecksTest.check("an empty session has nothing to review", session.reviewModelling(ProposalChecksTest.WORDS).isEmpty());
		ProposalChecksTest.check("and refuses a second call", session.reviewModelling(ProposalChecksTest.WORDS).isEmpty());
	}

	private static StagingLlmPass.CreatedAttribute find(final StagingLlmPass.CreatedEntity pEntity, final String pName)
	{
		for (final StagingLlmPass.CreatedAttribute attribute : pEntity.attributes)
		{
			if (attribute.name.equals(pName))
			{
				return attribute;
			}
		}
		throw new AssertionError("no attribute " + pName + " on " + pEntity.name);
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
