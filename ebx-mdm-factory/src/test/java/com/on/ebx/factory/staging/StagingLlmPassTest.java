package com.on.ebx.factory.staging;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.AgentLoop;
import com.on.ebx.agent.LlmClient;
import com.on.ebx.agent.LocalTool;
import com.on.ebx.agent.ToolSet;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

/**
 * JUnit-free, like the rest of this module's tests: run the main, a failure throws. Covers the pass rewritten
 * "target model first" (docs/TARGET-MAPPING.md sec 4.2, target-mapping lot 2): a flat-source person/address
 * case (child feed, constant discriminator, sourceIdPipeline), a two-table product/variant case (root feed +
 * ordinary reference-attribute mapping), every refusal path, the compact-catalogue/describe_landing_table
 * gate, and one real {@code AgentLoop} run against a fake Anthropic-shaped HTTP provider.
 */
public final class StagingLlmPassTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		StagingLlmPassTest.userPromptHasNoValueLookingContent();
		StagingLlmPassTest.promptStatesTheModellingRulesAndTheLanguage();
		StagingLlmPassTest.personAddressFlatAndTwoTableCase();
		StagingLlmPassTest.productVariantCase();
		StagingLlmPassTest.entityRefusals();
		StagingLlmPassTest.duplicatesParentLinkPure();
		StagingLlmPassTest.feedRefusals();
		StagingLlmPassTest.childEntityMustReachItsParent();
		StagingLlmPassTest.volumeTestShapes();
		StagingLlmPassTest.unreadFilledColumnsPure();
		StagingLlmPassTest.unreadColumnsFollowUp();
		StagingLlmPassTest.unreadColumnsPersVol5();
		StagingLlmPassTest.ruleProbeAndHarmony();
		StagingLlmPassTest.lossyRuleRefused();
		StagingLlmPassTest.extractionIsNotAFold();
		StagingLlmPassTest.orderBlindSplitRefusedOnce();
		StagingLlmPassTest.orderUnknownIsSurfaced();
		StagingLlmPassTest.identicalOutputsRefusedOnce();
		StagingLlmPassTest.unquotedCommaRefusedBeforeProbe();
		StagingLlmPassTest.compactModeGatesOnDescribe();
		StagingLlmPassTest.earlyStopOnRepeatedRefusal();
		StagingLlmPassTest.idempotenceAgainstExistingEntities();
		StagingLlmPassTest.rerunSkipsExistingFeed();
		StagingLlmPassTest.rerunRefusesRejectedFeed();
		StagingLlmPassTest.rerunChildFeedAttachesToExistingParentFeed();
		StagingLlmPassTest.rerunRefusesDuplicateEntityByAttributes();
		StagingLlmPassTest.rerunRefusesDuplicateEntityByNormalizedName();
		StagingLlmPassTest.rerunPromptListsPriorEntities();
		StagingLlmPassTest.parseMembersPure();
		StagingLlmPassTest.childKeysProposal();
		StagingLlmPassTest.childKeysProposalAgainstPriorEntities();
		StagingLlmPassTest.childKeysIsDescribedToTheModel();
		StagingLlmPassTest.caseChangeAheadOfACaseReadingRegexIsRefusedInAFeed();
		StagingLlmPassTest.agentLoopEndToEnd();
		StagingLlmPassTest.probeRefusalsCarryNoValueTheDataPolicyWithholds();
		StagingLlmPassTest.aFilterSplittingIdentifiersByKindIsAccepted();
		StagingLlmPassTest.aRuleThatDoesNotConvertIsCorrectedOrFlaggedNeverTheWholeFeedRefused();
		StagingLlmPassTest.formatFactsOfASensitiveColumnCarryNoValue();
		StagingLlmPassTest.anEntityWithoutFeedIsRefusedAtTheRecordStep();
		StagingLlmPassTest.aColumnOnlyASupersededEntityReadIsLeftBehind();
		StagingLlmPassTest.aFeedProposedAgainInTheRunGainsTheRulesItLacked();
		System.out.println("StagingLlmPassTest OK");
	}

	/**
	 * Lot M7 (2026-10-18, E2EMASTER20261017235630): the model proposed {@code ContactEmail} (a child of the person, {@code email} + the link) and fed it from no
	 * landing column. The session names it before the run is written ({@code entitiesNotYetFed}), the follow-up turn carries the refusal that names the fix, and
	 * the record step ({@code reviewModelling}) caps the entity under every bulk threshold with the sentence first in its rationale and the model's note as the
	 * reason - the written row is the flagged one. A fed entity is left alone.
	 */
	private static void anEntityWithoutFeedIsRefusedAtTheRecordStep()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "Person.");
		final LocalToolResultCheck created = StagingLlmPassTest.callEntity(session, "ContactEmail", "Adresse electronique", StagingLlmPassTest.attrs("email,STRING", "personne,STRING"),
			"Personne", "personne", 89, "A distinct means of contact.");
		StagingLlmPassTest.check("the entity itself is accepted by the tool (its feeds come later)", !created.isError);
		StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,", "lastName,LAST_NAME,"), null, 90, "Flat.");

		StagingLlmPassTest.check("before the run is written, the unfed entity is named: " + session.entitiesNotYetFed(), session.entitiesNotYetFed().equals(Arrays.asList("ContactEmail")));
		final String followUp = StagingLlmPass.followUpPrompt(session, Collections.<String>emptyList(), null, session.entitiesNotYetFed());
		StagingLlmPassTest.check("the follow-up turn carries the refusal that names the fix: " + followUp,
			followUp.contains("fed NONE of their attributes from any landing column: ContactEmail") && followUp.contains(StagingLlmPass.noFeedRefusal("ContactEmail"))
				&& followUp.contains("map at least one attribute of ContactEmail to a landing column, or do not propose it") && followUp.contains("call note with the entity name"));
		StagingLlmPassTest.check("the system prompt states the rule", StagingLlmPass.systemPrompt().contains("an entity none of whose attributes is fed from a landing column is never built"));

		final StagingLlmPass.CreatedAttribute linkBefore = StagingLlmPassTest.attributeOf(StagingLlmPassTest.entity(session, "ContactEmail"), "personne");
		session.note(StagingLlmPassTest.MAPPER.createObjectNode().put("text", "ContactEmail: the e-mail is a contact channel of its own, kept for a later source."));
		session.finalizeEntities();
		StagingLlmPassTest.check("finalize reports the same gap", session.entitiesWithoutFeed().equals(Arrays.asList("ContactEmail")));
		final List<ProposalChecks.Finding> findings = session.reviewModelling(new ProposalChecks.Words()
		{
			@Override
			public String text(final String pKey, final Object... pArgs)
			{
				return "[" + pKey + Arrays.toString(pArgs) + "]";
			}
		});
		StagingLlmPassTest.check("the record step flags it NO_FEED: " + findings, findings.size() == 1 && ProposalChecks.NO_FEED.equals(findings.get(0).kind) && "ContactEmail".equals(findings.get(0).entity));
		final StagingLlmPass.CreatedEntity written = StagingLlmPassTest.entity(session, "ContactEmail");
		StagingLlmPassTest.check("what the writer reads is the flagged entity: confidence " + written.confidence + ", rationale " + written.rationale,
			written.confidence == ProposalChecks.NO_FEED_CONFIDENCE_CAP && written.rationale.startsWith("[stagingProposal.check.noFeed[Adresse electronique]]")
				&& written.rationale.contains("kept for a later source") && written.rationale.endsWith("A distinct means of contact."));
		StagingLlmPassTest.check("its link to the parent is kept, its e-mail attribute capped", "Personne".equals(written.parentEntity) && "personneRef".equals(written.parentLinkField)
			&& StagingLlmPassTest.attributeOf(written, "personne").confidence == linkBefore.confidence && linkBefore.rationale.equals(StagingLlmPassTest.attributeOf(written, "personne").rationale)
			&& StagingLlmPassTest.attributeOf(written, "email").confidence == ProposalChecks.NO_SOURCE_CONFIDENCE_CAP);
		StagingLlmPassTest.check("the fed entity is left alone", StagingLlmPassTest.entity(session, "Personne").confidence == 90);
	}

	/** The unification scenario of E2EMASTER20261018090711: two sources into one canonical entity, one per-table entity whose phone column the canonical never read. */
	private static StagingLlmPass.Session unifiedClientSession()
	{
		final StagingRules.LandingTableInfo stores = StagingLlmPassTest.table("t-stores", "STORES", "SALES", StagingLlmPassTest.filled("TICKET_ID", true, "100"),
			StagingLlmPassTest.filled("FIRST_NAME", false, "100"), StagingLlmPassTest.filled("LAST_NAME", false, "100"), StagingLlmPassTest.filled("EMAIL", false, "100"),
			StagingLlmPassTest.filled("TELEPHONE", false, "100"), StagingLlmPassTest.filled("SHOP", false, "100"), StagingLlmPassTest.filled("NOTES", false, "50"),
			StagingLlmPassTest.filled("EMPTY_COLUMN", false, "0"));
		final StagingRules.LandingTableInfo ecom = StagingLlmPassTest.table("t-ecom", "ECOM", "CUSTOMER", StagingLlmPassTest.filled("CUSTOMER_ID", true, "100"),
			StagingLlmPassTest.filled("FIRST_NAME", false, "100"), StagingLlmPassTest.filled("LAST_NAME", false, "100"), StagingLlmPassTest.filled("MOBILE", false, "90"));
		// the per-table entity of STORES read what the rule pass maps (every column but the empty one and NOTES, which a person had rejected); ECOM's reads are unknown:
		// nothing is claimed about it
		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = Arrays.asList(
			new StagingLlmPass.RuleEntityInfo("SalesRule", "STORES", "SALES", Arrays.asList("FIRST_NAME", "LAST_NAME", "EMAIL", "TELEPHONE", "SHOP")),
			new StagingLlmPass.RuleEntityInfo("CustomerRule", "ECOM", "CUSTOMER"));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(new StagingLlmPass.ProjectInfo("Unify", "Unify", Arrays.asList(stores, ecom),
			Collections.<StagingRules.RelationInfo>emptyList(), ruleEntities), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Client", "Client", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING", "email,STRING", "mobile,STRING"), null, null, 91,
			"The person.");
		StagingLlmPassTest.callFeed(session, "Client", "ECOM.CUSTOMER", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,", "lastName,LAST_NAME,", "mobile,MOBILE,"), null, 90,
			"Web shop.");
		StagingLlmPassTest.callFeed(session, "Client", "STORES.SALES", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,", "lastName,LAST_NAME,", "email,EMAIL,"), null, 90,
			"Tickets.");
		return session;
	}

	/**
	 * Lot M15 (2026-10-18, E2EMASTER20261018090711): the unification into {@code Client} superseded the per-source entities; STORES.TELEPHONE was mapped only to
	 * the superseded one and never to {@code Client.mobile}. A column counts as read only through a LIVE entity: the session names the columns the per-table entity
	 * read and nobody live reads, with the superseded entity and its canonical, in the follow-up turn (each column once) and as a finding on the canonical's row.
	 */
	private static void aColumnOnlyASupersededEntityReadIsLeftBehind()
	{
		final StagingLlmPass.Session session = StagingLlmPassTest.unifiedClientSession();
		final List<StagingLlmPass.LeftBehind> left = session.columnsLeftBehind();
		StagingLlmPassTest.check("one group: the STORES table of the canonical Client, the per-table entity SalesRule: " + left.size(),
			left.size() == 1 && "Client".equals(left.get(0).canonicalEntity) && "SalesRule".equals(left.get(0).supersededEntity)
				&& "STORES.SALES".equals(left.get(0).landingTableKey));
		StagingLlmPassTest.check("it lists what the per-table entity read and no live entity reads (not NOTES: a person had rejected that mapping; not the empty column)",
			left.get(0).columnNames().equals("STORES.TELEPHONE, STORES.SHOP"));
		StagingLlmPassTest.check("a per-table entity whose reads are unknown (ECOM) is never claimed to have read anything", left.size() == 1 && !"ECOM.CUSTOMER".equals(left.get(0).landingTableKey));

		final Map<String, List<StagingRules.LandingColumnInfo>> unread = session.unreadColumns(false);
		final String followUp = StagingLlmPass.followUpPrompt(session, session.tablesNotYetCovered(), unread, session.entitiesNotYetFed(), left);
		StagingLlmPassTest.check("the follow-up names the superseded entity and its canonical: " + followUp,
			followUp.contains("columns fed to the superseded entity SalesRule and not to its canonical Client, in STORES.SALES")
				&& followUp.contains("TELEPHONE (type STRING, fill rate 100%") && followUp.contains("SHOP (type STRING")
				&& followUp.contains("map it to an attribute of its canonical entity") && followUp.contains("or call note with the table key and the column name written out in full"));
		StagingLlmPassTest.check("each column is listed once: TELEPHONE is not repeated among the unread columns", followUp.indexOf("TELEPHONE (type") == followUp.lastIndexOf("TELEPHONE (type"));
		StagingLlmPassTest.check("the unread columns nobody read (NOTES) stay in the unread paragraph", followUp.contains("NOTES (type STRING"));
		final String withoutLeftBehind = StagingLlmPass.followUpPrompt(session, session.tablesNotYetCovered(), unread, session.entitiesNotYetFed(), null);
		StagingLlmPassTest.check("without the left-behind groups the paragraph is absent", !withoutLeftBehind.contains("superseded entity"));
		StagingLlmPassTest.check("the system prompt tells the model about it", StagingLlmPass.systemPrompt().contains("every filled column those per-table entities read must still be read by a live entity"));

		// a note naming the table and the column explains it
		session.note(StagingLlmPassTest.MAPPER.createObjectNode().put("text", "STORES.SALES: SHOP describes the sale, not the person, left out on purpose."));
		final List<StagingLlmPass.LeftBehind> afterNote = session.columnsLeftBehind();
		StagingLlmPassTest.check("a note that names the table and the column explains it: " + afterNote.size() + " group(s)",
			afterNote.size() == 1 && afterNote.get(0).columnNames().equals("STORES.TELEPHONE"));

		// the record step flags the canonical's row
		final StagingLlmPass.CreatedAttribute mobileBefore = StagingLlmPassTest.attributeOf(StagingLlmPassTest.entity(session, "Client"), "mobile");
		session.finalizeEntities();
		final List<ProposalChecks.Finding> findings = session.reviewModelling(new ProposalChecks.Words()
		{
			@Override
			public String text(final String pKey, final Object... pArgs)
			{
				return "[" + pKey + Arrays.toString(pArgs) + "]";
			}
		});
		StagingLlmPassTest.check("the record step flags Client: " + findings, findings.size() == 1
			&& ProposalChecks.SUPERSEDED_COLUMN_LEFT_BEHIND.equals(findings.get(0).kind) && "Client".equals(findings.get(0).entity));
		final StagingLlmPass.CreatedEntity written = StagingLlmPassTest.entity(session, "Client");
		StagingLlmPassTest.check("the written row is capped under the bulk bars and names the columns first: " + written.confidence + " " + written.rationale,
			written.confidence == ProposalChecks.LEFT_BEHIND_CONFIDENCE_CAP && written.confidence < 60
				&& written.rationale.startsWith("[stagingProposal.check.supersededColumnLeftBehind[Client, STORES.TELEPHONE, SalesRule]]") && written.rationale.endsWith("The person."));
		StagingLlmPassTest.check("its attributes are left alone", written.attributes.size() == 4 && StagingLlmPassTest.attributeOf(written, "mobile") == mobileBefore);
	}

	/**
	 * Lot M15: the follow-up turn asks for {@code STORES.TELEPHONE} on the feed already proposed in the run, and the model used to answer that the feed "cannot be
	 * reproposed" - a second {@code propose_feed} for the same entity and table adds the rules for attributes the feed does not map yet, and is refused when it adds none.
	 */
	private static void aFeedProposedAgainInTheRunGainsTheRulesItLacked()
	{
		final StagingLlmPass.Session session = StagingLlmPassTest.unifiedClientSession();
		final LocalToolResultCheck refused = StagingLlmPassTest.callFeed(session, "Client", "STORES.SALES", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 80, "again");
		StagingLlmPassTest.check("a call that brings nothing new is refused as before: " + refused.text, refused.isError && refused.text.contains("was already proposed in this run"));
		final LocalToolResultCheck extended = StagingLlmPassTest.callFeed(session, "Client", "STORES.SALES", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,", "mobile,TELEPHONE,"),
			null, 85, "The phone of the ticket.");
		StagingLlmPassTest.check("a call that brings a new attribute extends the feed: " + extended.text, !extended.isError && extended.text.contains("extended with 1 rule(s) for mobile")
			&& extended.text.contains("its other rules stand"));
		final StagingLlmPass.CreatedFeed feed = StagingLlmPassTest.feedOn(session, "Client", "STORES.SALES");
		StagingLlmPassTest.check("the feed holds four rules, the new one last, the old ones untouched: " + feed.rules.size(), feed.rules.size() == 4
			&& "mobile".equals(feed.rules.get(3).attribute) && "TELEPHONE".equals(feed.rules.get(3).landingColumn) && "firstName".equals(feed.rules.get(0).attribute));
		StagingLlmPassTest.check("there is still ONE feed for the pair", session.createdFeeds().size() == 2);
		StagingLlmPassTest.check("the column is read again: only SHOP is left behind", session.columnsLeftBehind().size() == 1
			&& session.columnsLeftBehind().get(0).columnNames().equals("STORES.SHOP"));
		final LocalToolResultCheck badColumn = StagingLlmPassTest.callFeed(session, "Client", "STORES.SALES", null, StagingLlmPassTest.rules("lastName,NO_SUCH,"), null, 80, "x");
		StagingLlmPassTest.check("an extension is validated like a feed", badColumn.isError && badColumn.text.contains("is not a column"));
	}

	private static StagingLlmPass.CreatedAttribute attributeOf(final StagingLlmPass.CreatedEntity pEntity, final String pName)
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

	/**
	 * A marker value sits in the sampled input of a probe that empties every value and of one that leaves a map(...) value
	 * unlisted: the refusal text never holds it, shows its shape where shapes are allowed (SHAPES_ONLY and above), and no
	 * shape at all once the probe went through {@link StagingLlmPass.ProbeResult#withoutValueShapes} (NONE, a sensitive column).
	 */
	private static void probeRefusalsCarryNoValueTheDataPolicyWithholds()
	{
		final String marker = "Zqxmarker7";
		final List<String> sample = Arrays.asList(marker, marker + "b", marker + "c");
		final String shape = com.on.ebx.factory.quality.Shapes.of(marker);
		final StagingLlmPass.ProbeResult emptying = StagingLlmPass.ProbeResult.of(sample, Arrays.asList((String) null, null, null), 0, Arrays.asList(shape + " (100%)"),
			Arrays.<String>asList());
		final StagingLlmPass.ProbeResult unlisted = StagingLlmPass.ProbeResult.of(sample, Arrays.asList("x", "y", "z"), 3, Arrays.asList(shape + " (100%)"),
			Arrays.<String>asList());
		for (final StagingLlmPass.ProbeResult result : new StagingLlmPass.ProbeResult[] { emptying, unlisted })
		{
			final String refusal = result == emptying ? StagingLlmPass.probeRefusal("CODE", "upper", result) : StagingLlmPass.lossyRefusal("CODE", "map(A=B)", result);
			StagingLlmPassTest.check("a refusal is produced", refusal != null);
			StagingLlmPassTest.check("shapes allowed: the shape, never the marker", refusal.contains(shape) && !refusal.contains(marker));
			final StagingLlmPass.ProbeResult blind = result.withoutValueShapes();
			final String blindRefusal = result == emptying ? StagingLlmPass.probeRefusal("CODE", "upper", blind) : StagingLlmPass.lossyRefusal("CODE", "map(A=B)", blind);
			StagingLlmPassTest.check("shapes withheld: a refusal is still produced, with its counts", blindRefusal != null && blindRefusal.contains("3"));
			StagingLlmPassTest.check("shapes withheld: neither the marker nor its shape", !blindRefusal.contains(marker) && !blindRefusal.contains(shape)
				&& !blindRefusal.contains("input shapes"));
			StagingLlmPassTest.check("shapes withheld: the case layout and token positions are gone too", blind.inputTokenCase == null && blind.positionByLayout.isEmpty()
				&& blind.positionDetail == null && blind.inputShapes.isEmpty() && blind.outputShapes.isEmpty());
			StagingLlmPassTest.check("the counts are kept", blind.filled == result.filled && blind.emptied == result.emptied && blind.unlisted == result.unlisted);
		}
	}

	/**
	 * Live B2B run 2026-10-23 (agent run 799): SAP's tax number holds a SIREN or a VAT number per row; the model's filters for the two attributes were refused for emptying
	 * most values, and the column was left out. A filter keeping one kind and emptying only the other kind passes; one that keeps a part of its own kind is refused with the
	 * counts and a wider filter.
	 */
	private static void aFilterSplittingIdentifiersByKindIsAccepted()
	{
		final List<String> sample = Arrays.asList("552100554", "552101563", "552102572", "552103581", "DE123456789", "BE0801007390");
		final StagingLlmPass.ProbeResult sirens = StagingLlmPass.ProbeResult.of(sample, Arrays.asList("552100554", "552101563", "552102572", "552103581", null, null), 0,
			Arrays.<String>asList(), Arrays.<String>asList());
		StagingLlmPassTest.check("digits-only kept, lettered emptied: accepted", StagingLlmPass.probeRefusal("TAX", "regex('^[0-9]{9}$')", sirens) == null);
		final List<String> vatSample = Arrays.asList("552100554", "DE123456789", "BE0801007390", "IT12345678901", "FR50676952278");
		final StagingLlmPass.ProbeResult vats = StagingLlmPass.ProbeResult.of(vatSample, Arrays.asList(null, "DE123456789", "BE0801007390", "IT12345678901", "FR50676952278"),
			0, Arrays.<String>asList(), Arrays.<String>asList());
		StagingLlmPassTest.check("lettered kept, digits-only emptied: accepted (nothing emptied past half here either)",
			StagingLlmPass.probeRefusal("TAX", "regex('^\\p{L}{2}[\\p{L}0-9]+$')", vats) == null);
		final StagingLlmPass.ProbeResult narrow = StagingLlmPass.ProbeResult.of(vatSample, Arrays.asList(null, null, null, "IT12345678901", null), 0,
			Arrays.asList("9(9) (20%)", "AA9(9) (20%)"), Arrays.<String>asList());
		final String refusal = StagingLlmPass.probeRefusal("TAX", "regex('^\\p{Lu}{2}[0-9]{11}$',0)", narrow);
		StagingLlmPassTest.check("a lettered filter that keeps one country only is refused with the counts: " + refusal, refusal != null
			&& refusal.contains("keeps 1 identifier(s) with letters but EMPTIES 3 other value(s) with letters") && refusal.contains("regex('^\\p{L}{2}[\\p{L}0-9]+$')")
			&& !refusal.contains("DE123456789"));
		final StagingLlmPass.ProbeResult words = StagingLlmPass.ProbeResult.of(Arrays.asList("Paris", "Lyon", "Nice"), Arrays.asList(null, null, "Nice"), 0,
			Arrays.<String>asList(), Arrays.<String>asList());
		StagingLlmPassTest.check("values that are not identifiers keep the usual refusal", StagingLlmPass.probeRefusal("CITY", "regex('^N.*')", words).contains("filled values EMPTY"));
	}

	// ------------------------------------------------------------------------------------- feed conversion (lot E, 2026-10-17)

	/** The loyalty birth dates (real file when present, else 40 synthetic dd/MM/yyyy ones), with their rows. */
	private static List<com.on.ebx.factory.quality.Detectors.ValueCount> loyaltyBirthDates() throws IOException
	{
		final java.io.File file = new java.io.File(System.getProperty("mdmf.b2cDir", "test-landing-b2c-2026-10-17/fichiers"),
			"Fidelite_Membres.csv");
		if (file.isFile())
		{
			return NormalisationProfilerTest.column(NormalisationProfilerTest.csv(file, ','), "date_naissance");
		}
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> out = new ArrayList<com.on.ebx.factory.quality.Detectors.ValueCount>();
		for (int i = 1; i <= 40; i++)
		{
			out.add(new com.on.ebx.factory.quality.Detectors.ValueCount(String.format("%02d/%02d/19%02d", 10 + i % 18, 1 + i % 12, 50 + i), 1L));
		}
		return out;
	}

	/** A probe over in-memory columns: the emptiness probe and the typed check both run the REAL Connectors pipeline, like {@code LandingRuleProbe}. */
	private static final class ColumnsProbe implements StagingLlmPass.RuleProbe
	{
		private final Map<String, List<com.on.ebx.factory.quality.Detectors.ValueCount>> columns;

		ColumnsProbe(final Map<String, List<com.on.ebx.factory.quality.Detectors.ValueCount>> pColumns)
		{
			this.columns = pColumns;
		}

		@Override
		public StagingLlmPass.ProbeResult probe(final String pTable, final String pColumn, final String pPipeline)
		{
			final List<String> sample = new ArrayList<String>();
			for (final com.on.ebx.factory.quality.Detectors.ValueCount value : this.columns.get(pColumn))
			{
				sample.add(value.value);
			}
			return StagingLlmPassTest.measureWithConnectors(sample, pPipeline);
		}

		@Override
		public TransformVerifier.Result verifyTyped(final String pTable, final String pColumn, final String pPipeline, final String pDataType)
		{
			final List<com.on.ebx.factory.quality.Detectors.ValueCount> values = this.columns.get(pColumn);
			return TransformVerifier.verify(pDataType, pPipeline, values, NormalisationProfiler.profile(values, -1L), new TransformVerifier.Evaluator()
			{
				@Override
				public String apply(final String pChain, final String pValue) throws Exception
				{
					return com.on.ebx.connect.core.Transformations.text(com.on.ebx.connect.core.Transformations.apply(pChain, pValue, null));
				}
			}, false);
		}
	}

	/**
	 * TiersB2CRetestStaging (2026-10-17): the loyalty {@code date_naissance}, written dd/MM/yyyy, mapped with {@code date(yyyy-MM-dd)}, made the probe
	 * refuse the WHOLE feed and the model drop the source. Now the rule is corrected from the column's measured format when that converts every sampled
	 * value, and recorded FLAGGED (confidence under the bulk bar, a "Value check" note) when nothing does; the feed and its other rules stand either way,
	 * and the answer says which rule and why - pipelines and counts, never a value.
	 */
	private static void aRuleThatDoesNotConvertIsCorrectedOrFlaggedNeverTheWholeFeedRefused() throws IOException
	{
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> birth = StagingLlmPassTest.loyaltyBirthDates();
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> mixed = NormalisationProfilerTest.counts("10/06/1963", 5, "1963-06-10", 5, "17/02/1998", 3);
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> ambiguous = NormalisationProfilerTest.counts("03/04/1981", 4, "05/06/1990", 4);
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> iso = NormalisationProfilerTest.counts("2022-01-01", 6, "2025-06-01", 6);
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> names = NormalisationProfilerTest.counts("Michel", 10, "Thomas", 10);
		final Map<String, List<com.on.ebx.factory.quality.Detectors.ValueCount>> columns = new LinkedHashMap<String, List<com.on.ebx.factory.quality.Detectors.ValueCount>>();
		columns.put("nom", names);
		columns.put("date_naissance", birth);
		columns.put("mixed_date", mixed);
		columns.put("ambiguous_date", ambiguous);
		columns.put("date_adhesion", iso);
		final StagingRules.LandingTableInfo members = StagingLlmPassTest.table("t-m", "LOYALTY", "MEMBERS", StagingLlmPassTest.column("card_number", "STRING", true),
			StagingLlmPassTest.column("nom", "STRING", false), StagingLlmPassTest.column("date_naissance", "STRING", false),
			StagingLlmPassTest.column("mixed_date", "STRING", false), StagingLlmPassTest.column("ambiguous_date", "STRING", false),
			StagingLlmPassTest.column("date_adhesion", "STRING", false));
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("B2C", "B2C", Arrays.asList(members),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(new ColumnsProbe(columns));
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("lastName,STRING", "birthDate,DATE", "otherDate,DATE", "thirdDate,DATE",
			"joinDate,DATE"), null, null, 90, "ok");
		final LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "LOYALTY.MEMBERS", null, StagingLlmPassTest.rules("lastName,nom,",
			"birthDate,date_naissance,date(yyyy-MM-dd)", "otherDate,mixed_date,isodate", "thirdDate,ambiguous_date,isodate", "joinDate,date_adhesion,"), null, 85, "loyalty members");
		StagingLlmPassTest.check("the feed is recorded, not refused: " + r.text, !r.isError && r.text.contains("recorded with 5 rule(s)"));
		StagingLlmPassTest.check("the answer names the corrected rule and the pipeline it got: " + r.text,
			r.text.contains("CORRECTED rule birthDate <- date_naissance") && r.text.contains("'trim|date(dd/MM/yyyy,yyyy-MM-dd)' converts all of them"));
		StagingLlmPassTest.check("a mixture no pattern converts is FLAGGED: " + r.text, r.text.contains("FLAGGED rule otherDate <- mixed_date"));
		StagingLlmPassTest.check("an unprovable day/month order is FLAGGED, not guessed: " + r.text,
			r.text.contains("FLAGGED rule thirdDate <- ambiguous_date") && r.text.contains("day/month order is not provable"));
		StagingLlmPassTest.check("the model is told the source stays: " + r.text, r.text.contains("The source is NOT dropped"));
		for (final List<com.on.ebx.factory.quality.Detectors.ValueCount> values : columns.values())
		{
			for (final com.on.ebx.factory.quality.Detectors.ValueCount value : values)
			{
				StagingLlmPassTest.check("no value in the answer: " + value.value, !r.text.contains(value.value));
			}
		}
		final StagingLlmPass.CreatedFeed feed = StagingLlmPassTest.feedOn(session, "Personne", "LOYALTY.MEMBERS");
		final StagingLlmPass.CreatedFeedRule corrected = StagingLlmPassTest.ruleOn(feed, "birthDate");
		StagingLlmPassTest.check("the corrected pipeline is the one recorded", "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(corrected.pipeline));
		StagingLlmPassTest.check("a fix keeps the confidence and says so in the rationale", corrected.confidence == 85 && corrected.rationale.contains(CheckNote.DELIMITER)
			&& corrected.rationale.contains("fixed."));
		final StagingLlmPass.CreatedFeedRule flagged = StagingLlmPassTest.ruleOn(feed, "otherDate");
		StagingLlmPassTest.check("a flagged rule keeps the model's pipeline", "isodate".equals(flagged.pipeline));
		StagingLlmPassTest.check("and is under the bulk-accept bar (" + flagged.confidence + ")", flagged.confidence <= 59);
		StagingLlmPassTest.check("whose old confidence rides in the note", flagged.rationale.contains("[was 85]") && CheckNote.originalConfidence(flagged.rationale).intValue() == 85);
		StagingLlmPassTest.check("a rule that converts as it is carries no note", StagingLlmPassTest.ruleOn(feed, "joinDate").rationale.equals("loyalty members")
			&& StagingLlmPassTest.ruleOn(feed, "lastName").confidence == 85);
		StagingLlmPassTest.check("three decisions surface for the outcome", session.conversionDecisions().size() == 3);
		StagingLlmPassTest.check("the system prompt tells the model not to drop a source over a rule",
			StagingLlmPass.systemPrompt().contains("never a reason to give up its source"));

		// structural refusals are untouched: an unknown attribute still refuses the feed, whatever the values do
		final StagingLlmPass.Session again = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		again.setRuleProbe(new ColumnsProbe(columns));
		StagingLlmPassTest.callEntity(again, "Personne", "Personne", StagingLlmPassTest.attrs("birthDate,DATE"), null, null, 90, "ok");
		final LocalToolResultCheck unknown = StagingLlmPassTest.callFeed(again, "Personne", "LOYALTY.MEMBERS", null, StagingLlmPassTest.rules("nope,nom,",
			"birthDate,date_naissance,date(yyyy-MM-dd)"), null, 85, "x");
		StagingLlmPassTest.check("an unknown attribute still refuses the feed", unknown.isError && unknown.text.contains("is not one of 'Personne'"));
		final LocalToolResultCheck unknownEntity = StagingLlmPassTest.callFeed(again, "Nobody", "LOYALTY.MEMBERS", null, StagingLlmPassTest.rules("birthDate,date_naissance,"),
			null, 85, "x");
		StagingLlmPassTest.check("an unknown entity still refuses it", unknownEntity.isError);

		// a text column of a typed attribute with NO pipeline: converted from the profile too
		final StagingLlmPass.Session bare = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		bare.setRuleProbe(new ColumnsProbe(columns));
		StagingLlmPassTest.callEntity(bare, "Personne", "Personne", StagingLlmPassTest.attrs("birthDate,DATE"), null, null, 90, "ok");
		final LocalToolResultCheck none = StagingLlmPassTest.callFeed(bare, "Personne", "LOYALTY.MEMBERS", null, StagingLlmPassTest.rules("birthDate,date_naissance,"), null, 85, "x");
		StagingLlmPassTest.check("no pipeline on a text date: corrected, not refused: " + none.text, !none.isError
			&& "trim|date(dd/MM/yyyy,yyyy-MM-dd)".equals(StagingLlmPassTest.ruleOn(StagingLlmPassTest.feedOn(bare, "Personne", "LOYALTY.MEMBERS"), "birthDate").pipeline));
	}

	/**
	 * Item 3: a SENSITIVE column (the default list holds {@code *naissance*}) shows the model how its dates and numbers are written - a format and a share of
	 * the rows - and nothing else: no value, no example, no shape, no case, no list of codes. The whole user prompt is searched for every value.
	 */
	private static void formatFactsOfASensitiveColumnCarryNoValue() throws IOException
	{
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> birth = StagingLlmPassTest.loyaltyBirthDates();
		final String sensitive = NormalisationProfiler.promptFacts(birth, -1L, true, true);
		StagingLlmPassTest.check("the date format and its share: " + sensitive, sensitive.contains("date written dd/MM/yyyy (100%)"));
		StagingLlmPassTest.check("the conversion to use: " + sensitive, sensitive.contains("trim|date(dd/MM/yyyy,yyyy-MM-dd)"));
		StagingLlmPassTest.check("nothing but formats: " + sensitive, !sensitive.contains("case ") && !sensitive.contains("phone") && !sensitive.contains("short list"));
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> money = NormalisationProfilerTest.counts("1234,50", 3, "99,9", 2);
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> codes = NormalisationProfilerTest.counts("Bronze", 70, "Argent", 70, "Or", 70);
		final List<com.on.ebx.factory.quality.Detectors.ValueCount> phones = NormalisationProfilerTest.counts("+33 6 46 86 05 23", 9);
		StagingLlmPassTest.check("a decimal comma is a format", NormalisationProfiler.promptFacts(money, -1L, true, false).contains("number written dec comma (100%)"));
		StagingLlmPassTest.check("a short list of codes says nothing for a sensitive column, even with values allowed",
			NormalisationProfiler.promptFacts(codes, -1L, true, true).isEmpty() && NormalisationProfiler.promptFacts(codes, -1L, false, true).contains("Bronze"));
		StagingLlmPassTest.check("a phone shape is not a format fact", NormalisationProfiler.promptFacts(phones, -1L, true, true).isEmpty());

		final StagingRules.LandingColumnInfo column = StagingLlmPassTest.column("date_naissance", "STRING", false);
		column.profileFacts = sensitive;
		final StagingRules.LandingColumnInfo amount = StagingLlmPassTest.column("montant", "STRING", false);
		amount.profileFacts = NormalisationProfiler.promptFacts(money, -1L, true, false);
		final StagingRules.LandingColumnInfo level = StagingLlmPassTest.column("niveau", "STRING", false);
		level.profileFacts = NormalisationProfiler.promptFacts(codes, -1L, true, true);
		final StagingRules.LandingTableInfo members = StagingLlmPassTest.table("t-m", "LOYALTY", "MEMBERS", StagingLlmPassTest.column("card_number", "STRING", true), column, amount,
			level);
		final String prompt = StagingLlmPass.userPrompt(new StagingLlmPass.ProjectInfo("B2C", "B2C", Arrays.asList(members), Collections.<StagingRules.RelationInfo>emptyList(),
			Collections.<StagingLlmPass.RuleEntityInfo>emptyList()));
		StagingLlmPassTest.check("the format reaches the model: " + prompt, prompt.contains("date written dd/MM/yyyy (100%)"));
		for (final List<com.on.ebx.factory.quality.Detectors.ValueCount> values : Arrays.asList(birth, money, codes))
		{
			for (final com.on.ebx.factory.quality.Detectors.ValueCount value : values)
			{
				StagingLlmPassTest.check("no value of a sensitive column in the prompt: " + value.value, !prompt.contains(value.value));
			}
		}
		StagingLlmPassTest.check("no shape either", !prompt.contains("99/99/9999") && !prompt.contains("value shapes"));
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	private static StagingRules.LandingColumnInfo column(final String pFieldName, final String pType, final boolean pKey)
	{
		return new StagingRules.LandingColumnInfo("lc-" + pFieldName, pFieldName, pFieldName, pType, pKey, BigDecimal.valueOf(90), null, null,
			new ArrayList<String>());
	}

	private static StagingRules.LandingTableInfo table(final String pId, final String pSourceSystem, final String pName,
		final StagingRules.LandingColumnInfo... pColumns)
	{
		return new StagingRules.LandingTableInfo(pId, pSourceSystem, pName, Long.valueOf(100), Arrays.asList(pColumns));
	}

	/** The person/address scenario: a flat ERP source and a two-table CRM source, both meant to feed the SAME Personne/Adresse target entities. */
	private static StagingLlmPass.ProjectInfo personAddressProject()
	{
		final StagingRules.LandingTableInfo personFlat = StagingLlmPassTest.table("t-flat", "ERP", "PERSON_FLAT", StagingLlmPassTest.column("ID", "INTEGER", true),
			StagingLlmPassTest.column("FIRST_NAME", "STRING", false), StagingLlmPassTest.column("LAST_NAME", "STRING", false),
			StagingLlmPassTest.column("STREET", "STRING", false), StagingLlmPassTest.column("CITY", "STRING", false),
			StagingLlmPassTest.column("ZIP", "STRING", false));
		final StagingRules.LandingTableInfo person = StagingLlmPassTest.table("t-person", "CRM", "PERSON", StagingLlmPassTest.column("ID", "INTEGER", true),
			StagingLlmPassTest.column("FIRST_NAME", "STRING", false), StagingLlmPassTest.column("LAST_NAME", "STRING", false));
		final StagingRules.LandingTableInfo address = StagingLlmPassTest.table("t-address", "CRM", "ADDRESS", StagingLlmPassTest.column("ID", "INTEGER", true),
			StagingLlmPassTest.column("PERSON_ID", "INTEGER", false), StagingLlmPassTest.column("STREET", "STRING", false),
			StagingLlmPassTest.column("CITY", "STRING", false), StagingLlmPassTest.column("ZIP", "STRING", false));
		final List<StagingRules.RelationInfo> relations = Arrays
			.asList(new StagingRules.RelationInfo("rel-1", "CRM", "ADDRESS", Arrays.asList("PERSON_ID"), "PERSON", "ACCEPTED", 90));
		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = Arrays.asList(new StagingLlmPass.RuleEntityInfo("PersonFlat", "ERP", "PERSON_FLAT"),
			new StagingLlmPass.RuleEntityInfo("Person", "CRM", "PERSON"), new StagingLlmPass.RuleEntityInfo("Address", "CRM", "ADDRESS"));
		return new StagingLlmPass.ProjectInfo("Personnes", "Personnes MDM", Arrays.asList(personFlat, person, address), relations, ruleEntities);
	}

	/** The product/variant scenario: a genuine two-table source only (no flat table), to exercise a root feed + ordinary reference-attribute mapping. */
	private static StagingLlmPass.ProjectInfo productVariantProject()
	{
		final StagingRules.LandingTableInfo product = StagingLlmPassTest.table("t-product", "ERP", "PRODUCT", StagingLlmPassTest.column("ID", "INTEGER", true),
			StagingLlmPassTest.column("NAME", "STRING", false), StagingLlmPassTest.column("PRICE", "DECIMAL", false));
		final StagingRules.LandingTableInfo variant = StagingLlmPassTest.table("t-variant", "ERP", "VARIANT", StagingLlmPassTest.column("ID", "INTEGER", true),
			StagingLlmPassTest.column("PRODUCT_ID", "INTEGER", false), StagingLlmPassTest.column("SKU", "STRING", false),
			StagingLlmPassTest.column("COLOR", "STRING", false));
		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = Arrays.asList(new StagingLlmPass.RuleEntityInfo("Product", "ERP", "PRODUCT"),
			new StagingLlmPass.RuleEntityInfo("Variant", "ERP", "VARIANT"));
		return new StagingLlmPass.ProjectInfo("Catalog", "Catalog MDM", Arrays.asList(product, variant), Collections.<StagingRules.RelationInfo>emptyList(),
			ruleEntities);
	}

	private static StagingRules.LandingColumnInfo filled(final String pFieldName, final boolean pKey, final String pFillRate)
	{
		return new StagingRules.LandingColumnInfo("lc-" + pFieldName, pFieldName, pFieldName, "STRING", pKey, pFillRate == null ? null : new BigDecimal(pFillRate),
			null, null, new ArrayList<String>());
	}

	/** PersVol4 (2026-09-26): the CRM contact's MAILING_* and OTHER_* groups, OTHER_* filled in 38 % of rows. */
	private static StagingRules.LandingTableInfo contactWithTwoAddresses()
	{
		final StagingRules.LandingTableInfo contact = StagingLlmPassTest.table("t-contact", "CRM", "CONTACT", StagingLlmPassTest.filled("CONTACT_ID", true, "100"),
			StagingLlmPassTest.filled("_receivedAt", false, "100"), StagingLlmPassTest.filled("LAST_NAME", false, "100"),
			StagingLlmPassTest.filled("MAILING_STREET", false, "60"), StagingLlmPassTest.filled("MAILING_CITY", false, "60"),
			StagingLlmPassTest.filled("OTHER_STREET", false, "38.3"), StagingLlmPassTest.filled("OTHER_CITY", false, "38.3"),
			StagingLlmPassTest.filled("FAX", false, "0"), StagingLlmPassTest.filled("NEVER_PROFILED", false, null));
		contact.columns.get(5).shapes.add("99 Aaaa Aaaaa (40%)");
		return contact;
	}

	private static List<StagingLlmPass.ExistingTargetEntity> noPriorEntities()
	{
		return new ArrayList<StagingLlmPass.ExistingTargetEntity>();
	}

	// -------------------------------------------------------------------------------------------------------- prompt

	private static void userPromptHasNoValueLookingContent()
	{
		final StagingLlmPass.ProjectInfo project = StagingLlmPassTest.personAddressProject();
		final String prompt = StagingLlmPass.userPrompt(project);

		StagingLlmPassTest.check("mentions every landing table", prompt.contains("ERP.PERSON_FLAT") && prompt.contains("CRM.PERSON") && prompt.contains("CRM.ADDRESS"));
		StagingLlmPassTest.check("mentions columns and types", prompt.contains("FIRST_NAME") && prompt.contains("type STRING"));
		StagingLlmPassTest.check("mentions the key flag", prompt.contains(", key"));
		StagingLlmPassTest.check("mentions fill rate (metadata, allowed)", prompt.contains("fill rate 90%"));
		StagingLlmPassTest.check("mentions the relation", prompt.contains("CRM.ADDRESS.PERSON_ID -> CRM.PERSON"));
		StagingLlmPassTest.check("names the rule entities", prompt.contains("PersonFlat: ERP.PERSON_FLAT") && prompt.contains("Address: CRM.ADDRESS"));
		// lot S10 (2026-10-18): a row count is a COUNT, allowed like a fill rate (LlmDataPolicy); the inventory now gives it - see IdentityFactsTest
		StagingLlmPassTest.check("the row count is given as a count", prompt.toLowerCase(Locale.ROOT).contains("100 rows"));

		final String system = StagingLlmPass.systemPrompt();
		StagingLlmPassTest.check("system prompt names every tool", system.contains("propose_target_entity") && system.contains("propose_feed")
			&& system.contains("describe_landing_table") && system.contains("note"));
		StagingLlmPassTest.check("system prompt gives the transformation catalogue", system.contains("constant(value)") && system.contains("concat(path1,path2"));
		StagingLlmPassTest.check("system prompt excludes EBX-lookup-only steps", !system.contains("fk(") && !system.contains("lookup(") && !system.contains("runid"));
		StagingLlmPassTest.check("system prompt says metadata only, never a sample value", system.toLowerCase(Locale.ROOT).contains("metadata only"));
		StagingLlmPassTest.check("system prompt gives two-domain examples", system.contains("Example 1 (persons/addresses)") && system.contains("Example 2 (products/variants)"));
	}

	// ------------------------------------------------------------------------------------------- required scenario 1

	/**
	 * The task's own required scenario: a flat table feeds Personne + a CHILD feed of Adresse (constant('MAIN')
	 * discriminator, sourceIdPipeline); a two-table source feeds the SAME Adresse via its OWN root feed with an
	 * ordinary reference-attribute mapping. Verifies the whole shape end to end through the pure Session.
	 */
	private static void personAddressFlatAndTwoTableCase()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());

		// Personne: top-level entity
		LocalToolResultCheck r = StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"),
			null, null, 90, "Person, unified across sources.");
		StagingLlmPassTest.check("Personne created", !r.isError && r.text.contains("Personne"));

		// Adresse: child of Personne, link attribute "personne"
		final ArrayNode addressAttrs = StagingLlmPassTest.attrs("street,STRING", "city,STRING", "zip,STRING", "addressType,STRING", "personne,STRING");
		r = StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", addressAttrs, "Personne", "personne", 90, "Address, unified across sources.");
		StagingLlmPassTest.check("Adresse created as a child of Personne", !r.isError && r.text.contains("child of Personne"));
		StagingLlmPassTest.check("two entities recorded", session.createdEntities().size() == 2);
		final StagingLlmPass.CreatedEntity adresse = StagingLlmPassTest.entity(session, "Adresse");
		StagingLlmPassTest.check("parentLinkField computed as personneRef", "personneRef".equals(adresse.parentLinkField));

		// root feed: Personne <- ERP.PERSON_FLAT
		r = StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null,
			StagingLlmPassTest.rules("firstName,FIRST_NAME,", "lastName,LAST_NAME,"), null, 90, "Flat source, person fields.");
		StagingLlmPassTest.check("Personne root feed on the flat table recorded", !r.isError);

		// child feed: Adresse <- ERP.PERSON_FLAT, parent = the Personne feed just created. Also carries a
		// REDUNDANT rule on "personne" (the link-to-parent attribute) from the row's own ID column - exactly
		// the harmless-looking mapping a language model might propose, and exactly the shape that used to make
		// StagingLoader treat it as an ordinary foreign key and reject every child row on a first load (bug fix,
		// docs/TARGET-MAPPING.md sec 3.3/4.2, project Personnes 2026-09-23) - see the assertions below.
		r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT",
			StagingLlmPassTest.rules("street,STREET,", "city,CITY,", "zip,ZIP,", "addressType,STREET,constant(MAIN)", "personne,ID,"),
			"concat(ID)|suffix(#MAIN)", 85, "Flat source, address embedded in the same row.");
		StagingLlmPassTest.check("Adresse child feed on the SAME flat table recorded", !r.isError && r.text.contains("child of Personne|ERP.PERSON_FLAT"));
		StagingLlmPassTest.check("the redundant rule on the link attribute is dropped with a warning, not refused",
			r.text.contains("personne") && r.text.contains("ignored"));

		// root feed: Personne <- CRM.PERSON (second source, same target entity)
		r = StagingLlmPassTest.callFeed(session, "Personne", "CRM.PERSON", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,", "lastName,LAST_NAME,"), null,
			88, "Two-table source, person fields.");
		StagingLlmPassTest.check("Personne second root feed (CRM.PERSON) recorded", !r.isError);

		// root feed: Adresse <- CRM.ADDRESS (its OWN table - not a child feed), personne mapped ordinarily from PERSON_ID
		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.ADDRESS", null,
			StagingLlmPassTest.rules("street,STREET,", "city,CITY,", "zip,ZIP,", "personne,PERSON_ID,"), null, 88,
			"Two-table source, address has its own table with a real personId column.");
		StagingLlmPassTest.check("Adresse root feed on CRM.ADDRESS recorded", !r.isError);

		StagingLlmPassTest.check("four feeds recorded", session.createdFeeds().size() == 4);

		session.finalizeEntities();
		StagingLlmPassTest.check("no gaps: every entity has a feed", session.entitiesWithoutFeed().isEmpty());
		StagingLlmPassTest.check("no gaps: every table is read", session.tablesWithoutFeedOrNote().isEmpty());

		final StagingLlmPass.CreatedEntity personne = StagingLlmPassTest.entity(session, "Personne");
		StagingLlmPassTest.check("Personne's members are PersonFlat and Person", personne.memberEntities.size() == 2
			&& personne.memberEntities.contains("PersonFlat") && personne.memberEntities.contains("Person"));
		final StagingLlmPass.CreatedEntity adresseAfter = StagingLlmPassTest.entity(session, "Adresse");
		StagingLlmPassTest.check("Adresse's members are PersonFlat and Address", adresseAfter.memberEntities.size() == 2
			&& adresseAfter.memberEntities.contains("PersonFlat") && adresseAfter.memberEntities.contains("Address"));

		final StagingLlmPass.CreatedFeed childFeed = StagingLlmPassTest.feedOn(session, "Adresse", "ERP.PERSON_FLAT");
		StagingLlmPassTest.check("child feed has a parent", "Personne|ERP.PERSON_FLAT".equals(childFeed.parentFeedKey));
		StagingLlmPassTest.check("child feed's sourceIdPipeline is the discriminated one", "concat(ID)|suffix(#MAIN)".equals(childFeed.sourceIdPipeline));
		final StagingLlmPass.CreatedFeedRule discriminator = StagingLlmPassTest.ruleOn(childFeed, "addressType");
		StagingLlmPassTest.check("discriminating attribute is mapped constant(MAIN)", "constant(MAIN)".equals(discriminator.pipeline));
		StagingLlmPassTest.check("the redundant rule on the link attribute never made it into the written child feed",
			!StagingLlmPassTest.hasRule(childFeed, "personne"));

		final StagingLlmPass.CreatedFeed rootAddressFeed = StagingLlmPassTest.feedOn(session, "Adresse", "CRM.ADDRESS");
		StagingLlmPassTest.check("the two-table Adresse feed is a ROOT feed (no parent)", rootAddressFeed.parentFeedKey == null);
		final StagingLlmPass.CreatedFeedRule personneRule = StagingLlmPassTest.ruleOn(rootAddressFeed, "personne");
		StagingLlmPassTest.check("personne mapped ordinarily from PERSON_ID on the root feed", "PERSON_ID".equals(personneRule.landingColumn));
	}

	// ------------------------------------------------------------------------------------------- required scenario 2

	private static void productVariantCase()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.productVariantProject(), StagingLlmPassTest.noPriorEntities());

		StagingLlmPassTest.callEntity(session, "Produit", "Produit", StagingLlmPassTest.attrs("name,STRING", "price,DECIMAL"), null, null, 90, "The product itself.");
		final ArrayNode variantAttrs = StagingLlmPassTest.attrs("sku,STRING", "color,STRING", "produit,STRING");
		final LocalToolResultCheck created = StagingLlmPassTest.callEntity(session, "Variante", "Variante", variantAttrs, "Produit", "produit", 90,
			"A sellable variant of a product.");
		StagingLlmPassTest.check("Variante created as a child of Produit", !created.isError);

		StagingLlmPassTest.callFeed(session, "Produit", "ERP.PRODUCT", null, StagingLlmPassTest.rules("name,NAME,", "price,PRICE,"), null, 90, "Product feed.");
		final LocalToolResultCheck feed = StagingLlmPassTest.callFeed(session, "Variante", "ERP.VARIANT", null,
			StagingLlmPassTest.rules("sku,SKU,", "color,COLOR,", "produit,PRODUCT_ID,"), null, 90,
			"Variant has its own table with a real productId column - a root feed, not a child feed.");
		StagingLlmPassTest.check("Variante root feed on VARIANT recorded (no parentFeed needed)", !feed.isError);

		session.finalizeEntities();
		StagingLlmPassTest.check("no gaps", session.entitiesWithoutFeed().isEmpty() && session.tablesWithoutFeedOrNote().isEmpty());
		final StagingLlmPass.CreatedEntity variante = StagingLlmPassTest.entity(session, "Variante");
		StagingLlmPassTest.check("Variante's member is Variant", variante.memberEntities.equals(Arrays.asList("Variant")));
		final StagingLlmPass.CreatedFeed variantFeed = StagingLlmPassTest.feedOn(session, "Variante", "ERP.VARIANT");
		StagingLlmPassTest.check("no child-feed relationship needed here", variantFeed.parentFeedKey == null);
	}

	// --------------------------------------------------------------------------------------------------- refusals

	/**
	 * Every case below uses its OWN, never-repeated entity name: the refusal circuit breaker (3 identical
	 * repeats of the same tool+key end the run - see {@link #earlyStopOnRepeatedRefusal()}) is keyed on the
	 * proposed {@code name}, so probing several DIFFERENT mistakes under the same name would trip it by
	 * accident and hide the very refusal texts this test means to check.
	 */
	private static void entityRefusals()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());

		LocalToolResultCheck r = StagingLlmPassTest.callEntity(session, "PersonFlat", "x", StagingLlmPassTest.attrs("x,STRING"), null, null, 80, "x");
		StagingLlmPassTest.check("name collision with a rule entity is refused", r.isError && r.text.contains("already the name of a rule-proposed entity"));

		r = StagingLlmPassTest.callEntity(session, "Empty1", "x", StagingLlmPassTest.MAPPER.createArrayNode(), null, null, 80, "x");
		StagingLlmPassTest.check("empty attribute list is refused", r.isError && r.text.contains("at least one attribute"));

		r = StagingLlmPassTest.callEntity(session, "Bad1", "x", StagingLlmPassTest.attrs("bad name,STRING"), null, null, 80, "x");
		StagingLlmPassTest.check("an invalid attribute name is refused", r.isError && r.text.contains("camelCase"));

		r = StagingLlmPassTest.callEntity(session, "Bad2", "x", StagingLlmPassTest.attrs("firstName,STRING"), "NoSuchParent", "firstName", 80, "x");
		StagingLlmPassTest.check("an unknown parent entity is refused", r.isError && r.text.contains("unknown parent entity"));

		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING"), null, null, 90, "ok");

		r = StagingLlmPassTest.callEntity(session, "Bad3", "x", StagingLlmPassTest.attrs("street,STRING"), "Personne", null, 80, "x");
		StagingLlmPassTest.check("missing parentLinkAttribute is refused", r.isError && r.text.contains("'parentLinkAttribute' is required"));

		r = StagingLlmPassTest.callEntity(session, "Bad4", "x", StagingLlmPassTest.attrs("street,STRING"), "Personne", "doesNotExist", 80, "x");
		StagingLlmPassTest.check("a parentLinkAttribute not among this call's own attributes is refused", r.isError && r.text.contains("must be one of this entity's own attributes"));

		final ArrayNode businessKeyChild = StagingLlmPassTest.attrs("personne,STRING");
		((ObjectNode) businessKeyChild.get(0)).put("businessKey", true);
		r = StagingLlmPassTest.callEntity(session, "Bad5", "x", businessKeyChild, "Personne", "personne", 80, "x");
		StagingLlmPassTest.check("a business-key attribute on a child is refused", r.isError && r.text.contains("cannot be a business key"));

		// 2026-09-26 (PersVol4): a second attribute carrying the parent's key next to parentLinkAttribute is refused
		r = StagingLlmPassTest.callEntity(session, "Bad6", "x", StagingLlmPassTest.attrs("street,STRING", "personne,STRING", "personneId,STRING"), "Personne",
			"personne", 80, "x");
		StagingLlmPassTest.check("a second reference to the parent (personneId) is refused, naming the link attribute: " + r.text,
			r.isError && r.text.contains("duplicates the link to the parent") && r.text.contains("'personne'") && r.text.contains("personneRef"));
		r = StagingLlmPassTest.callEntity(session, "Bad7", "x", StagingLlmPassTest.attrs("street,STRING", "personne,STRING", "personneRef,STRING"), "Personne",
			"personne", 80, "x");
		StagingLlmPassTest.check("an attribute named like the engine's link field (personneRef) is refused", r.isError && r.text.contains("duplicates the link"));

		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "personne,STRING"), "Personne", "personne", 80, "ok");
		r = StagingLlmPassTest.callEntity(session, "Adresse", "y", StagingLlmPassTest.attrs("z,STRING"), null, null, 80, "x");
		StagingLlmPassTest.check("re-proposing a name already used this run is refused", r.isError && r.text.contains("already proposed earlier in this run"));
	}

	/**
	 * Every rule-content probe below (empty rules, unknown attribute, unknown column, bad pipeline...) is fed
	 * through its OWN throwaway entity ("Probe1", "Probe2", ...) reading the SAME landing table - the refusal
	 * key is {@code entity|landingTable|parentFeed}, so reusing "Personne" for six different mistakes in a row
	 * would trip the 3-strike circuit breaker by accident (see {@link #entityRefusals()}'s own note).
	 */
	private static void feedRefusals()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "personne,STRING"), "Personne", "personne", 90, "ok");
		// each probe keeps an attribute literally named "x" (Probe4/5/6's own rules below reference it), plus a
		// per-probe "markerN" attribute so the six probes do NOT all share the exact same attribute signature -
		// the new semantic-duplicate guard (propose_target_entity, docs/TARGET-MAPPING.md "Relance de la passe
		// LLM") would otherwise refuse Probe2..Probe6 as duplicates of Probe1, since a bare "x" alone is the
		// same business-attribute signature for every one of them.
		for (int i = 1; i <= 6; i++)
		{
			StagingLlmPassTest.callEntity(session, "Probe" + i, "Probe" + i, StagingLlmPassTest.attrs("x,STRING", "marker" + i + ",STRING"), null, null, 90,
				"ok");
		}

		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "NoSuchEntity", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("x,ID,"), null, 80, "x");
		StagingLlmPassTest.check("unknown entity is refused", r.isError && r.text.contains("not a target entity proposed earlier"));

		r = StagingLlmPassTest.callFeed(session, "Probe1", "ERP.NO_SUCH_TABLE", null, StagingLlmPassTest.rules("x,ID,"), null, 80, "x");
		StagingLlmPassTest.check("unknown landing table is refused", r.isError && r.text.contains("unknown landing table"));

		r = StagingLlmPassTest.callFeed(session, "Probe2", "ERP.PERSON_FLAT", null, StagingLlmPassTest.MAPPER.createArrayNode(), null, 80, "x");
		StagingLlmPassTest.check("empty rules is refused", r.isError && r.text.contains("at least one column-to-attribute mapping"));

		r = StagingLlmPassTest.callFeed(session, "Probe3", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("nope,ID,"), null, 80, "x");
		StagingLlmPassTest.check("unknown attribute in a rule is refused", r.isError && r.text.contains("not one of 'Probe3's own attributes"));

		r = StagingLlmPassTest.callFeed(session, "Probe4", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("x,NO_SUCH_COLUMN,"), null, 80, "x");
		StagingLlmPassTest.check("unknown landing column is refused", r.isError && r.text.contains("is not a column of"));

		r = StagingLlmPassTest.callFeed(session, "Probe5", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("x,FIRST_NAME,frobnicate"), null, 80, "x");
		StagingLlmPassTest.check("an out-of-catalogue pipeline step is refused", r.isError && r.text.contains("frobnicate"));

		r = StagingLlmPassTest.callFeed(session, "Probe6", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("x,FIRST_NAME,lookup(/root/X,./a,./b)"), null, 80,
			"x");
		StagingLlmPassTest.check("an EXCLUDED (EBX-lookup-only) transformation is refused", r.isError && r.text.contains("lookup"));

		r = StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,"), "concat(ID)", 80, "x");
		StagingLlmPassTest.check("a root feed's sourceIdPipeline referencing the table's own key column is refused",
			r.isError && r.text.contains("root feed's sourceId is always"));

		// Adresse has no root feed yet: a child feed for it is refused (no parent feed exists to name)
		r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT", StagingLlmPassTest.rules("street,STREET,"), null, 80,
			"x");
		StagingLlmPassTest.check("an unknown parent feed is refused", r.isError && r.text.contains("unknown parent feed"));

		StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 90, "ok");

		// Variante-shaped refusal: a "child" entity fed as a child of a feed whose own entity is NOT its declared parent
		StagingLlmPassTest.callEntity(session, "Other", "Other", StagingLlmPassTest.attrs("x,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callFeed(session, "Other", "CRM.PERSON", null, StagingLlmPassTest.rules("x,FIRST_NAME,"), null, 90, "ok");
		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.PERSON", "Other|CRM.PERSON", StagingLlmPassTest.rules("street,FIRST_NAME,"), null, 80, "x");
		StagingLlmPassTest.check("a parent feed whose entity is not the declared parent is refused",
			r.isError && r.text.contains("not 'Personne'"));

		// a child feed reading a DIFFERENT landing table than its parent (landing rows are flat today) is refused
		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.ADDRESS", "Personne|ERP.PERSON_FLAT", StagingLlmPassTest.rules("street,STREET,"), null, 80,
			"x");
		StagingLlmPassTest.check("a child feed on a different table than its parent is refused", r.isError && r.text.contains("SAME landing table"));

		// a genuine child feed, then a duplicate of the exact same (entity, table) pair
		StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT", StagingLlmPassTest.rules("street,STREET,"), null, 85, "ok");
		r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT", StagingLlmPassTest.rules("street,STREET,"), null, 80,
			"x");
		StagingLlmPassTest.check("a duplicate (entity, landingTable) pair is refused", r.isError && r.text.contains("was already proposed in this run"));
	}

	/**
	 * A child-type entity (Adresse, parent Personne) must reach its parent from every feed: the two shapes seen
	 * on 2026-09-24 are refused - project Personnes (Adresse fed from the SAP flat table by a ROOT feed with no
	 * rule on 'personne', and a child sourceIdPipeline constant('MAIN')) and project Vague1 (the same root feed,
	 * the link mapped from the row's own key). The genuine two-table root feed still passes.
	 */
	private static void childEntityMustReachItsParent()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "city,STRING", "personne,STRING"), "Personne",
			"personne", 90, "ok");

		// probed on ERP.PERSON_FLAT: CRM.ADDRESS gets two more refusals below, and three refusals in a row on the same key end the run
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", null,
			StagingLlmPassTest.rules("street,STREET,", "personne,ID,"), null, 80, "x");
		StagingLlmPassTest.check("a root feed of a child is refused while its parent has no feed yet", r.isError && r.text.contains("propose the feeds of 'Personne' first"));

		StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 90, "ok");
		StagingLlmPassTest.callFeed(session, "Personne", "CRM.PERSON", null, StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 90, "ok");

		r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", null, StagingLlmPassTest.rules("street,STREET,", "city,CITY,"), null, 80, "x");
		StagingLlmPassTest.check("Personnes shape: a root feed of Adresse on the table already feeding Personne is refused",
			r.isError && r.text.contains("must be a CHILD feed") && r.text.contains("Personne|ERP.PERSON_FLAT"));

		r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT", StagingLlmPassTest.rules("street,STREET,"),
			"constant(MAIN)", 80, "y");
		StagingLlmPassTest.check("a child sourceIdPipeline reading no column is refused", r.isError && r.text.contains("SAME sourceId"));

		r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT", StagingLlmPassTest.rules("street,STREET,", "city,CITY,"),
			null, 85, "ok");
		StagingLlmPassTest.check("the child feed without sourceIdPipeline is recorded", !r.isError);

		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.ADDRESS", null, StagingLlmPassTest.rules("street,STREET,", "city,CITY,"), null, 80, "z");
		StagingLlmPassTest.check("a two-table root feed without the link attribute is refused", r.isError && r.text.contains("must map 'personne'"));

		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.ADDRESS", null, StagingLlmPassTest.rules("street,STREET,", "personne,ID,"), null, 80, "w");
		StagingLlmPassTest.check("Vague1 shape: the link mapped from the row's own key is refused", r.isError && r.text.contains("is the key of CRM.ADDRESS itself"));

		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.ADDRESS", null, StagingLlmPassTest.rules("street,STREET,", "personne,PERSON_ID,"), null, 88, "ok");
		StagingLlmPassTest.check("the genuine two-table root feed is recorded", !r.isError);
		StagingLlmPassTest.check("the system prompt no longer suggests a constant sourceId", !StagingLlmPass.systemPrompt().contains("constant('MAIN') and a "));
	}

	/** The pure part of the unread-columns follow-up: which filled columns of a read table no feed reads, and when that is worth a turn. */
	private static void unreadFilledColumnsPure()
	{
		final Map<String, StagingRules.LandingTableInfo> tables = new LinkedHashMap<String, StagingRules.LandingTableInfo>();
		tables.put("CRM.CONTACT", StagingLlmPassTest.contactWithTwoAddresses());
		tables.put("CRM.NOT_READ", StagingLlmPassTest.table("t-nr", "CRM", "NOT_READ", StagingLlmPassTest.filled("X", false, "90")));
		final Map<String, java.util.Set<String>> read = new LinkedHashMap<String, java.util.Set<String>>();
		read.put("CRM.CONTACT", new java.util.HashSet<String>(Arrays.asList("LAST_NAME", "MAILING_STREET", "MAILING_CITY")));

		final Map<String, List<StagingRules.LandingColumnInfo>> unread = StagingLlmPass.unreadFilledColumns(tables, read);
		StagingLlmPassTest.check("only the read table is examined", unread.keySet().equals(Collections.singleton("CRM.CONTACT")));
		StagingLlmPassTest.check("key, technical, empty and unprofiled columns are never listed, the OTHER_* group is",
			StagingLlmPass.renderColumns(unread).equals("CRM.CONTACT: OTHER_STREET, OTHER_CITY"));
		StagingLlmPassTest.check("38 % filled is worth a follow-up", StagingLlmPass.worthAFollowUp(unread));

		final Map<String, List<StagingRules.LandingColumnInfo>> sparse = new LinkedHashMap<String, List<StagingRules.LandingColumnInfo>>();
		sparse.put("CRM.CONTACT", Arrays.asList(StagingLlmPassTest.filled("RARE", false, "4.9")));
		StagingLlmPassTest.check("below 5 % is only reported", !StagingLlmPass.worthAFollowUp(sparse));
		sparse.put("CRM.CONTACT", Arrays.asList(StagingLlmPassTest.filled("RARE", false, "5")));
		StagingLlmPassTest.check("exactly 5 % asks", StagingLlmPass.worthAFollowUp(sparse));
		StagingLlmPassTest.check("nothing unread, nothing to ask",
			!StagingLlmPass.worthAFollowUp(Collections.<String, List<StagingRules.LandingColumnInfo>> emptyMap()));

		StagingLlmPassTest.check("a note naming the table and the full column name explains it",
			StagingLlmPass.explainedByNote("CRM.CONTACT", "OTHER_CITY", Arrays.asList("CRM.CONTACT: OTHER_STREET, OTHER_CITY are a stale address.")));
		StagingLlmPassTest.check("a wildcard does not", !StagingLlmPass.explainedByNote("CRM.CONTACT", "OTHER_CITY", Arrays.asList("CONTACT: OTHER_* left out")));
		StagingLlmPassTest.check("a column name inside a longer one does not",
			!StagingLlmPass.explainedByNote("CRM.CONTACT", "CITY", Arrays.asList("CONTACT: OTHER_CITY left out")));
		StagingLlmPassTest.check("the column named for another table does not",
			!StagingLlmPass.explainedByNote("CRM.CONTACT", "OTHER_CITY", Arrays.asList("ERP.PARTNER: OTHER_CITY left out")));
	}

	/**
	 * PersVol4 (2026-09-26): the model fed Personne and the MAILING address from CRM.CONTACT and forgot OTHER_*; the table
	 * is read, so the left-out-table turn never fired. The session now lists the unread filled columns for the follow-up,
	 * and a note naming them turns them into "left out on purpose".
	 */
	private static void unreadColumnsFollowUp()
	{
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("PersVol4", "PersVol4",
			Arrays.asList(StagingLlmPassTest.contactWithTwoAddresses()), Collections.<StagingRules.RelationInfo>emptyList(),
			Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("lastName,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "city,STRING", "addressType,STRING", "personne,STRING"),
			"Personne", "personne", 90, "ok");
		StagingLlmPassTest.callFeed(session, "Personne", "CRM.CONTACT", null, StagingLlmPassTest.rules("lastName,LAST_NAME,"), null, 90, "ok");
		final LocalToolResultCheck r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", "Personne|CRM.CONTACT", "MAILING",
			StagingLlmPassTest.rules("street,MAILING_STREET,", "city,MAILING_CITY,", "addressType,OTHER_STREET,constant(MAILING)"));
		StagingLlmPassTest.check("the MAILING child is recorded", !r.isError);

		StagingLlmPassTest.check("the table is read, so no left-out table", session.tablesNotYetCovered().isEmpty());
		final Map<String, List<StagingRules.LandingColumnInfo>> unread = session.unreadColumns(false);
		StagingLlmPassTest.check("a constant rule reads nothing: the OTHER_* group is unread",
			StagingLlmPass.renderColumns(unread).equals("CRM.CONTACT: OTHER_STREET, OTHER_CITY"));
		StagingLlmPassTest.check("and worth the follow-up turn", StagingLlmPass.worthAFollowUp(unread));
		final String followUp = StagingLlmPass.followUpPrompt(session, session.tablesNotYetCovered(), unread);
		StagingLlmPassTest.check("the follow-up lists the unread columns with fill rate and shapes, and what the feeds already read",
			followUp.contains("OTHER_STREET (type STRING, fill rate 38.3%, value shapes: 99 Aaaa Aaaaa (40%))")
				&& followUp.contains("feed Adresse|CRM.CONTACT~MAILING (child of Personne|CRM.CONTACT), variant MAILING")
				&& followUp.contains("MAILING_STREET -> street") && !followUp.contains("You ended without feeding"));
		StagingLlmPassTest.check("the follow-up never names a domain grouping itself", !followUp.contains("OTHER_*"));

		session.note(StagingLlmPassTest.MAPPER.createObjectNode().put("text", "CRM.CONTACT: OTHER_STREET, OTHER_CITY hold a stale address, left out."));
		StagingLlmPassTest.check("once noted, nothing is left to ask", session.unreadColumns(false).isEmpty());
		StagingLlmPassTest.check("and the columns read as left out on purpose",
			StagingLlmPass.renderColumns(session.unreadColumns(true)).equals("CRM.CONTACT: OTHER_STREET, OTHER_CITY"));

		final StagingLlmPass.ExistingTargetEntity priorUnknown = new StagingLlmPass.ExistingTargetEntity("e1", "Personne", "PROPOSED",
			Arrays.asList("lastName"), null, null, Arrays.asList(new StagingLlmPass.ExistingFeed("f1", "CRM.CONTACT", null, "PROPOSED", null)));
		final StagingLlmPass.Session rerun = new StagingLlmPass.Session(project, Arrays.asList(priorUnknown));
		StagingLlmPassTest.check("an earlier feed whose columns are unknown never makes its table look unread", rerun.unreadColumns(false).isEmpty());
		final StagingLlmPass.ExistingTargetEntity priorKnown = new StagingLlmPass.ExistingTargetEntity("e1", "Personne", "PROPOSED",
			Arrays.asList("lastName"), null, null,
			Arrays.asList(new StagingLlmPass.ExistingFeed("f1", "CRM.CONTACT", null, "PROPOSED", null, Arrays.asList("LAST_NAME"))));
		final StagingLlmPass.Session rerunKnown = new StagingLlmPass.Session(project, Arrays.asList(priorKnown));
		StagingLlmPassTest.check("an earlier feed with known columns counts them",
			StagingLlmPass.renderColumns(rerunKnown.unreadColumns(false)).equals("CRM.CONTACT: MAILING_STREET, MAILING_CITY, OTHER_STREET, OTHER_CITY"));
	}

	/**
	 * PersVol5 (2026-09-26): ERP.BP_ADDRESS.HOUSE_NUMBER (filled in every row) was read by no canonical feed, yet the follow-up
	 * turn only asked about the left-out ECOM.CUSTOMER table. One rule-pass entity per landing table (BpAddress...), whose
	 * feed reads every column and which the canonical Adresse supersedes, must never make a column "read".
	 */
	private static void unreadColumnsPersVol5()
	{
		final StagingRules.LandingTableInfo contact = StagingLlmPassTest.table("t-c", "PERSVOLCRM", "CONTACT", StagingLlmPassTest.filled("CONTACT_ID", true, "100"),
			StagingLlmPassTest.filled("LAST_NAME", false, "100"), StagingLlmPassTest.filled("MAILING_STREET", false, "90"));
		final StagingRules.LandingTableInfo partner = StagingLlmPassTest.table("t-bp", "PERSVOLERP", "BUSINESS_PARTNER",
			StagingLlmPassTest.filled("BP_ID", true, "100"), StagingLlmPassTest.filled("LAST_NAME", false, "100"));
		final StagingRules.LandingTableInfo address = StagingLlmPassTest.table("t-bpa", "PERSVOLERP", "BP_ADDRESS",
			StagingLlmPassTest.filled("ADDRESS_ID", true, "100"), StagingLlmPassTest.filled("BP_ID", false, "100"),
			StagingLlmPassTest.filled("ADDRESS_TYPE", false, "100"), StagingLlmPassTest.filled("STREET_NAME", false, "100"),
			StagingLlmPassTest.filled("HOUSE_NUMBER", false, "100"), StagingLlmPassTest.filled("POSTAL_CODE", false, "100"),
			StagingLlmPassTest.filled("CITY_NAME", false, "100"), StagingLlmPassTest.filled("COUNTRY", false, "100"));
		final StagingRules.LandingTableInfo customer = StagingLlmPassTest.table("t-ec", "PERSVOLECOM", "CUSTOMER",
			StagingLlmPassTest.filled("CUSTOMER_NO", true, "100"), StagingLlmPassTest.filled("FULL_NAME", false, "100"));
		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = Arrays.asList(new StagingLlmPass.RuleEntityInfo("Contact", "PERSVOLCRM", "CONTACT"),
			new StagingLlmPass.RuleEntityInfo("BusinessPartner", "PERSVOLERP", "BUSINESS_PARTNER"),
			new StagingLlmPass.RuleEntityInfo("BpAddress", "PERSVOLERP", "BP_ADDRESS"), new StagingLlmPass.RuleEntityInfo("Customer", "PERSVOLECOM", "CUSTOMER"));
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("PersVol5", "PersVol5", Arrays.asList(contact, partner, address, customer),
			Collections.<StagingRules.RelationInfo>emptyList(), ruleEntities);
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("lastName,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("personne,STRING", "street,STRING", "postalCode,STRING",
			"city,STRING", "country,STRING", "addressType,STRING"), "Personne", "personne", 90, "ok");
		StagingLlmPassTest.callFeed(session, "Personne", "PERSVOLCRM.CONTACT", null, StagingLlmPassTest.rules("lastName,LAST_NAME,"), null, 90, "ok");
		StagingLlmPassTest.callFeed(session, "Personne", "PERSVOLERP.BUSINESS_PARTNER", null, StagingLlmPassTest.rules("lastName,LAST_NAME,"), null, 90, "ok");
		StagingLlmPassTest.callFeedVariant(session, "Adresse", "PERSVOLCRM.CONTACT", "Personne|PERSVOLCRM.CONTACT", "MAILING",
			StagingLlmPassTest.rules("street,MAILING_STREET,", "addressType,LAST_NAME,constant(MAILING)"));
		final LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Adresse", "PERSVOLERP.BP_ADDRESS", null,
			StagingLlmPassTest.rules("personne,BP_ID,", "street,STREET_NAME,", "postalCode,POSTAL_CODE,", "city,CITY_NAME,", "country,COUNTRY,",
				"addressType,ADDRESS_TYPE,"), null, 90, "ok");
		StagingLlmPassTest.check("the ERP address root feed is recorded", !r.isError);
		final Map<String, List<StagingRules.LandingColumnInfo>> unread = session.unreadColumns(false);
		StagingLlmPassTest.check("HOUSE_NUMBER, read by no canonical feed, is unread", StagingLlmPass.renderColumns(unread).equals("PERSVOLERP.BP_ADDRESS: HOUSE_NUMBER"));
		StagingLlmPassTest.check("and worth the follow-up turn", StagingLlmPass.worthAFollowUp(unread));
		StagingLlmPassTest.check("the forgotten ECOM table is still asked about in the same turn",
			session.tablesNotYetCovered().equals(Collections.singletonList("PERSVOLECOM.CUSTOMER")));
		final String followUp = StagingLlmPass.followUpPrompt(session, session.tablesNotYetCovered(), unread);
		StagingLlmPassTest.check("one follow-up prompt names both the table and the column", followUp.contains("PERSVOLECOM.CUSTOMER")
			&& followUp.contains("HOUSE_NUMBER (type STRING, fill rate 100%"));

		// the live run's real cause: the one-click run profiled the landing AFTER this pass, so every fill rate was null
		// and an unprofiled column is never reported - StagingLlmSuggester.suggest now profiles the missing ones first
		final StagingRules.LandingTableInfo unprofiled = StagingLlmPassTest.table("t-bpa", "PERSVOLERP", "BP_ADDRESS",
			StagingLlmPassTest.filled("ADDRESS_ID", true, null), StagingLlmPassTest.filled("STREET_NAME", false, null),
			StagingLlmPassTest.filled("HOUSE_NUMBER", false, null));
		final StagingLlmPass.Session blind = new StagingLlmPass.Session(new StagingLlmPass.ProjectInfo("PersVol5", "PersVol5", Arrays.asList(unprofiled),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList()), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(blind, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callFeed(blind, "Adresse", "PERSVOLERP.BP_ADDRESS", null, StagingLlmPassTest.rules("street,STREET_NAME,"), null, 90, "ok");
		StagingLlmPassTest.check("without a fill rate nothing is reported (why the suggester profiles first)", blind.unreadColumns(false).isEmpty());

		// an earlier run's feeds: a rule-pass entity's (reading every column) or a superseded / rejected entity's never count
		final List<String> everyColumn = Arrays.asList("ADDRESS_ID", "BP_ID", "ADDRESS_TYPE", "STREET_NAME", "HOUSE_NUMBER", "POSTAL_CODE", "CITY_NAME",
			"COUNTRY");
		final List<StagingLlmPass.ExistingTargetEntity> priors = Arrays.asList(
			new StagingLlmPass.ExistingTargetEntity("56", "BpAddress", "SUPERSEDED", Arrays.asList("houseNumber"), null, null,
				Arrays.asList(new StagingLlmPass.ExistingFeed("84", "PERSVOLERP.BP_ADDRESS", null, "PROPOSED", null, everyColumn))),
			new StagingLlmPass.ExistingTargetEntity("70", "OldAdresse", "SUPERSEDED", Arrays.asList("houseNumber"), null, null,
				Arrays.asList(new StagingLlmPass.ExistingFeed("95", "PERSVOLERP.BP_ADDRESS", null, "PROPOSED", null, everyColumn))),
			new StagingLlmPass.ExistingTargetEntity("71", "RejectedAdresse", "REJECTED", Arrays.asList("houseNumber"), null, null,
				Arrays.asList(new StagingLlmPass.ExistingFeed("96", "PERSVOLERP.BP_ADDRESS", null, "PROPOSED", null, everyColumn))));
		final StagingLlmPass.Session rerun = new StagingLlmPass.Session(project, priors);
		StagingLlmPassTest.callEntity(rerun, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "city,STRING"), null, null, 90, "ok");
		StagingLlmPassTest.callFeed(rerun, "Adresse", "PERSVOLERP.BP_ADDRESS", null, StagingLlmPassTest.rules("street,STREET_NAME,", "city,CITY_NAME,"), null,
			90, "ok");
		StagingLlmPassTest.check("rule-pass, superseded and rejected entities' feeds read nothing",
			StagingLlmPass.renderColumns(rerun.unreadColumns(false)).equals("PERSVOLERP.BP_ADDRESS: BP_ID, ADDRESS_TYPE, HOUSE_NUMBER, POSTAL_CODE, COUNTRY"));
		final StagingLlmPass.Session rerunCanonical = new StagingLlmPass.Session(project, Arrays.asList(new StagingLlmPass.ExistingTargetEntity("60",
			"Adresse", "PROPOSED", Arrays.asList("street"), null, null,
			Arrays.asList(new StagingLlmPass.ExistingFeed("91", "PERSVOLERP.BP_ADDRESS", null, "PROPOSED", null, everyColumn)))));
		StagingLlmPassTest.check("a canonical entity of an earlier run still counts", rerunCanonical.unreadColumns(false).isEmpty());
	}

	/**
	 * PersVol volume test, 2026-09-24: a CRM row holding TWO addresses (MAILING_* and OTHER_*), an ERP partner with a text
	 * birth date shaped 9(8), and a web-shop table the model left out. Each is now either expressible (variants) or
	 * refused with a hint (conversion), and a left-out table is visible to the caller before the run is written.
	 */
	private static void volumeTestShapes()
	{
		final StagingRules.LandingTableInfo contact = StagingLlmPassTest.table("t-contact", "CRM", "CONTACT", StagingLlmPassTest.column("CONTACT_ID", "STRING", true),
			StagingLlmPassTest.column("LAST_NAME", "STRING", false), StagingLlmPassTest.column("MAILING_STREET", "STRING", false),
			StagingLlmPassTest.column("MAILING_CITY", "STRING", false), StagingLlmPassTest.column("OTHER_STREET", "STRING", false),
			StagingLlmPassTest.column("OTHER_CITY", "STRING", false));
		final StagingRules.LandingTableInfo partner = StagingLlmPassTest.table("t-bp", "ERP", "BUSINESS_PARTNER", StagingLlmPassTest.column("BP_ID", "INTEGER", true),
			StagingLlmPassTest.column("LAST_NAME", "STRING", false), StagingLlmPassTest.column("BIRTH_DATE", "STRING", false));
		partner.columns.get(2).shapes.add("9(8) (100%)");
		final StagingRules.LandingTableInfo customer = StagingLlmPassTest.table("t-cust", "ECOM", "CUSTOMER", StagingLlmPassTest.column("CUSTOMER_NO", "STRING", true),
			StagingLlmPassTest.column("FULL_NAME", "STRING", false));
		customer.columns.get(1).shapes.add("Aaaaa Aaaaaa (70%)");
		customer.columns.get(1).shapes.add("AAAAAA Aaaaa (30%)");
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("PersVol", "PersVol", Arrays.asList(contact, partner, customer),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());

		final String prompt = StagingLlmPass.userPrompt(project);
		StagingLlmPassTest.check("value shapes are shown for a text column", prompt.contains("BIRTH_DATE (type STRING, fill rate 90%, value shapes: 9(8) (100%))"));
		StagingLlmPassTest.check("the system prompt explains shapes and several children per row",
			StagingLlmPass.systemPrompt().contains("value SHAPES") && StagingLlmPass.systemPrompt().contains("one CHILD feed per role"));

		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("lastName,STRING", "birthDate,DATE"), null, null, 90, "ok");
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "city,STRING", "addressType,STRING", "personne,STRING"),
			"Personne", "personne", 90, "ok");
		StagingLlmPassTest.callFeed(session, "Personne", "CRM.CONTACT", null, StagingLlmPassTest.rules("lastName,LAST_NAME,"), null, 90, "ok");

		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "ERP.BUSINESS_PARTNER", null,
			StagingLlmPassTest.rules("lastName,LAST_NAME,", "birthDate,BIRTH_DATE,"), null, 80, "x");
		StagingLlmPassTest.check("a text column copied into a DATE attribute as is is refused, with its shapes",
			r.isError && r.text.contains("date(yyyyMMdd)") && r.text.contains("9(8) (100%)"));
		r = StagingLlmPassTest.callFeed(session, "Personne", "ERP.BUSINESS_PARTNER", null,
			StagingLlmPassTest.rules("lastName,LAST_NAME,", "birthDate,BIRTH_DATE,date(yyyyMMdd)"), null, 80, "ok");
		StagingLlmPassTest.check("the same rule with date(yyyyMMdd) is recorded", !r.isError);

		r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", "Personne|CRM.CONTACT", "MAILING",
			StagingLlmPassTest.rules("street,MAILING_STREET,", "city,MAILING_CITY,"));
		StagingLlmPassTest.check("a variant without a constant discriminator is refused", r.isError && r.text.contains("constant(MAILING)"));
		r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", null, "OTHER", StagingLlmPassTest.rules("street,OTHER_STREET,"));
		StagingLlmPassTest.check("a variant on a root feed is refused", r.isError && r.text.contains("CHILD feed only"));
		r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", "Personne|CRM.CONTACT", "mailing",
			StagingLlmPassTest.rules("street,MAILING_STREET,", "addressType,MAILING_STREET,constant(MAILING)"));
		StagingLlmPassTest.check("a lower-case variant is refused", r.isError && r.text.contains("upper-case role name"));
		r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", "Personne|CRM.CONTACT", "MAILING",
			StagingLlmPassTest.rules("street,MAILING_STREET,", "city,MAILING_CITY,", "addressType,MAILING_STREET,constant(MAILING)"));
		StagingLlmPassTest.check("the MAILING child is recorded, addressed with its variant", !r.isError && r.text.contains("Adresse|CRM.CONTACT~MAILING"));
		r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", "Personne|CRM.CONTACT", "OTHER",
			StagingLlmPassTest.rules("street,OTHER_STREET,", "city,OTHER_CITY,", "addressType,OTHER_STREET,constant(OTHER)"));
		StagingLlmPassTest.check("a second child of the same entity on the same row is recorded", !r.isError);
		r = StagingLlmPassTest.callFeedVariant(session, "Adresse", "CRM.CONTACT", "Personne|CRM.CONTACT", "OTHER",
			StagingLlmPassTest.rules("street,OTHER_STREET,", "addressType,OTHER_STREET,constant(OTHER)"));
		StagingLlmPassTest.check("the same variant twice is refused", r.isError && r.text.contains("variant OTHER"));

		StagingLlmPassTest.check("ECOM is not covered yet, and the caller sees it before finalizing",
			session.tablesNotYetCovered().equals(Arrays.asList("ECOM.CUSTOMER")));
		final String followUp = StagingLlmPass.followUpPrompt(session, session.tablesNotYetCovered());
		StagingLlmPassTest.check("the follow-up names the left-out table and recalls what was already proposed",
			followUp.contains("ECOM.CUSTOMER") && followUp.contains("- entity Personne") && followUp.contains("- feed Adresse|CRM.CONTACT~OTHER"));

		session.finalizeEntities();
		int addressFeedsOnContact = 0;
		for (final StagingLlmPass.CreatedFeed feed : session.createdFeeds())
		{
			if (feed.entityName.equals("Adresse") && feed.landingTableKey.equals("CRM.CONTACT"))
			{
				addressFeedsOnContact++;
				StagingLlmPassTest.check("each child feed carries its variant", "MAILING".equals(feed.variant) || "OTHER".equals(feed.variant));
			}
		}
		StagingLlmPassTest.check("two Adresse feeds on one CRM row", addressFeedsOnContact == 2);
		StagingLlmPassTest.check("the gap is still reported at the end", session.tablesWithoutFeedOrNote().equals(Arrays.asList("ECOM.CUSTOMER")));

		// PersVol2 (2026-09-25): an ASCII letter class cuts accented and compound names
		final StagingLlmPass.Session names = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(names, "Client", "Client", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "ok");
		r = StagingLlmPassTest.callFeed(names, "Client", "ECOM.CUSTOMER", null, StagingLlmPassTest.rules("firstName,FULL_NAME,regex(^[A-Za-z]+)"), null, 80, "x");
		StagingLlmPassTest.check("a regex with [A-Za-z] is refused, pointing to \\p{L}", r.isError && r.text.contains("\\p{L}"));
		r = StagingLlmPassTest.callFeed(names, "Client", "ECOM.CUSTOMER", null, StagingLlmPassTest.rules("firstName,FULL_NAME,regex(^(\\p{L}+))"), null, 80, "ok");
		StagingLlmPassTest.check("the same rule with \\p{L} is recorded", !r.isError);
		StagingLlmPassTest.check("the prompt states rules (5) and (6)", StagingLlmPass.systemPrompt().contains("(5) In a regex")
			&& StagingLlmPass.systemPrompt().contains("(6) Harmonise codes"));
	}

	/** PersVol3 (2026-09-25): a regex copying the shape "99" empties the street; ISO codes against country names. */
	private static void ruleProbeAndHarmony()
	{
		final StagingRules.LandingTableInfo crm = StagingLlmPassTest.table("t-c", "CRM", "CONTACT", StagingLlmPassTest.column("CONTACT_ID", "STRING", true),
			StagingLlmPassTest.column("STREET", "STRING", false), StagingLlmPassTest.column("COUNTRY", "STRING", false));
		crm.columns.get(2).shapes.add("Aa(5) (80%)");
		final StagingRules.LandingTableInfo erp = StagingLlmPassTest.table("t-e", "ERP", "PARTNER", StagingLlmPassTest.column("BP_ID", "INTEGER", true),
			StagingLlmPassTest.column("COUNTRY", "STRING", false));
		erp.columns.get(1).shapes.add("AA (100%)");
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(crm, erp),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(new StagingLlmPass.RuleProbe()
		{
			@Override
			public StagingLlmPass.ProbeResult probe(final String pTable, final String pColumn, final String pPipeline)
			{
				final boolean literalShape = pPipeline.contains("^99");
				return new StagingLlmPass.ProbeResult(10, literalShape ? 9 : 0, Arrays.asList("99 aa(8) Aa(8) (70%)"),
					literalShape ? Collections.<String> emptyList() : Arrays.asList("aa(8) Aa(8) (70%)"));
			}
		});
		StagingLlmPassTest.callEntity(session, "Site", "Site", StagingLlmPassTest.attrs("street,STRING", "country,STRING"), null, null, 90, "ok");
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Site", "CRM.CONTACT", null,
			StagingLlmPassTest.rules("street,STREET,regex(^99\\s(.*))", "country,COUNTRY,"), null, 80, "x");
		StagingLlmPassTest.check("a rule emptying 9 of 10 real values is refused, explaining shapes are not literals",
			r.isError && r.text.contains("9 of 10") && r.text.contains("not a literal"));
		r = StagingLlmPassTest.callFeed(session, "Site", "CRM.CONTACT", null,
			StagingLlmPassTest.rules("street,STREET,regex(^\\d+\\s(.*))", "country,COUNTRY,"), null, 80, "ok");
		StagingLlmPassTest.check("the corrected regex is recorded", !r.isError);
		r = StagingLlmPassTest.callFeed(session, "Site", "ERP.PARTNER", null, StagingLlmPassTest.rules("country,COUNTRY,"), null, 80, "ok");
		StagingLlmPassTest.check("codes against words on the same attribute give a harmony note, not a refusal",
			!r.isError && r.text.contains("short codes from ERP") && r.text.contains("words from CRM") && r.text.contains("map(...)"));
		StagingLlmPassTest.check("code-like shapes", StagingLlmPass.codeLike("AA (100%)") && StagingLlmPass.codeLike("9(5)") && !StagingLlmPass.codeLike("Aa(5)"));
	}

	/** A tiny stand-in for Connectors' map / upper, enough to measure the probe on literal values in a unit test. */
	private static String applyForTest(final String pPipeline, final String pValue)
	{
		if ("upper".equals(pPipeline))
		{
			return pValue.toUpperCase(Locale.ROOT);
		}
		final String inner = pPipeline.substring(pPipeline.indexOf('(') + 1, pPipeline.lastIndexOf(')'));
		String fallback = null;
		for (final String arg : inner.split(","))
		{
			for (final String pair : arg.replace("'", "").split(";"))
			{
				final int eq = pair.indexOf('=');
				if (eq < 0)
				{
					continue;
				}
				final String key = pair.substring(0, eq).trim();
				if ("*".equals(key))
				{
					fallback = pair.substring(eq + 1).trim();
				}
				else if (key.equalsIgnoreCase(pValue.trim()))
				{
					return pair.substring(eq + 1).trim();
				}
			}
		}
		return fallback;
	}

	private static StagingLlmPass.ProbeResult measureForTest(final List<String> pSample, final String pPipeline)
	{
		final List<String> outputs = new ArrayList<String>();
		int unlisted = 0;
		final String sentinel = StagingLlmPass.withMapSentinel(pPipeline);
		for (final String value : pSample)
		{
			outputs.add(StagingLlmPassTest.applyForTest(pPipeline, value));
			if (sentinel != null && StagingLlmPass.MAP_SENTINEL.equals(StagingLlmPassTest.applyForTest(sentinel, value)))
			{
				unlisted++;
			}
		}
		return StagingLlmPass.ProbeResult.of(pSample, outputs, unlisted, Arrays.asList("AA (100%)"), Arrays.asList("Aa(5) (60%)"));
	}

	/** PersVol4 volume test (2026-09-26): map(FR=France;DE=Germany;*=Other) on 8 ISO codes emptied nothing and lost 750 countries. */
	private static void lossyRuleRefused()
	{
		final List<String> codes = Arrays.asList("FR", "DE", "IT", "ES", "GB", "PT", "NL", "BE");
		final String partial = "map(FR=France;DE=Germany;*=Other)";
		StagingLlmPassTest.check("the sentinel is added to a map step only",
			("trim|map(FR=France;*=Other,'*=" + StagingLlmPass.MAP_SENTINEL + "')|upper").equals(StagingLlmPass.withMapSentinel("trim|map(FR=France;*=Other)|upper"))
				&& StagingLlmPass.withMapSentinel("trim|upper") == null);
		final StagingLlmPass.ProbeResult partialResult = StagingLlmPassTest.measureForTest(codes, partial);
		StagingLlmPassTest.check("a partial map empties nothing, so the emptying check lets it through",
			partialResult.emptied == 0 && StagingLlmPass.probeRefusal("COUNTRY", partial, partialResult) == null);
		StagingLlmPassTest.check("measured: 8 distinct in, 3 out, 6 folded onto Other, 6 unlisted", partialResult.distinctInputs == 8
			&& partialResult.distinctOutputs == 3 && partialResult.foldedInputs == 6 && partialResult.unlisted == 6);
		final String refusal = StagingLlmPass.lossyRefusal("COUNTRY", partial, partialResult);
		StagingLlmPassTest.check("a map whose *= catches sampled values is refused: list every value or keep the source value",
			refusal != null && refusal.contains("6 of 8") && refusal.contains("EVERY value") && refusal.contains("keep the source value"));
		final StagingLlmPass.ProbeResult noDefault = StagingLlmPassTest.measureForTest(codes, "map(FR=France;DE=Germany;IT=Italy;ES=Spain;GB=United Kingdom)");
		StagingLlmPassTest.check("a partial map WITHOUT default (3 of 8 emptied, under half) is refused too",
			StagingLlmPass.probeRefusal("COUNTRY", "m", noDefault) == null && noDefault.unlisted == 3
				&& StagingLlmPass.lossyRefusal("COUNTRY", "m", noDefault) != null);
		final StagingLlmPass.ProbeResult folded = new StagingLlmPass.ProbeResult(8, 0, null, null, 8, 3, 6, 0);
		StagingLlmPassTest.check("a fold outside any map (8 distinct into 3, 6 unrelated on one output) is refused",
			StagingLlmPass.lossyRefusal("COUNTRY", "regex(...)", folded).contains("folds 8 distinct values into 3"));

		final List<String> mixedCase = Arrays.asList("FR", "fr", "Fr", "DE", "de", "IT", "it", "ES");
		final StagingLlmPass.ProbeResult upper = StagingLlmPassTest.measureForTest(mixedCase, "upper");
		StagingLlmPassTest.check("upper() on mixed-case codes halves the distinct count yet is accepted (fr/FR are the same value)",
			upper.distinctInputs == 8 && upper.distinctOutputs == 4 && upper.foldedInputs == 0 && StagingLlmPass.lossyRefusal("COUNTRY", "upper", upper) == null);

		final String complete = "map(FR=France;DE=Germany;IT=Italy;ES=Spain;GB=United Kingdom;PT=Portugal;NL=Netherlands;BE=Belgium)";
		final StagingLlmPass.ProbeResult full = StagingLlmPassTest.measureForTest(codes, complete);
		StagingLlmPassTest.check("a complete map of 8 codes to 8 names is accepted", full.unlisted == 0 && full.distinctOutputs == 8
			&& StagingLlmPass.lossyRefusal("COUNTRY", complete, full) == null);

		final List<String> mixedForms = Arrays.asList("FR", "France", "DE", "Germany", "IT", "Italie");
		final String harmonise = "map(FR=France;France=France;DE=Germany;Germany=Germany;IT=Italy;Italie=Italy)";
		final StagingLlmPass.ProbeResult codeAndLabel = StagingLlmPassTest.measureForTest(mixedForms, harmonise);
		StagingLlmPassTest.check("a complete harmonising map (a code and its label onto one form) is accepted",
			codeAndLabel.distinctOutputs == 3 && StagingLlmPass.lossyRefusal("COUNTRY", harmonise, codeAndLabel) == null);
		final List<String> dates = Arrays.asList("2024-01-15", "15/01/2024", "2023-05-02", "02/05/2023", "2022-12-31", "31/12/2022");
		final List<String> isoDates = Arrays.asList("2024-01-15", "2024-01-15", "2023-05-02", "2023-05-02", "2022-12-31", "2022-12-31");
		StagingLlmPassTest.check("a date format merging two writings of the same date is accepted", StagingLlmPass.lossyRefusal("BIRTH", "isodate",
			StagingLlmPass.ProbeResult.of(dates, isoDates, 0, null, null)) == null);

		// wired into propose_feed: the partial map is refused, the complete one recorded
		final StagingRules.LandingTableInfo erp = StagingLlmPassTest.table("t-e", "ERP", "ADDRESS", StagingLlmPassTest.column("ID", "INTEGER", true),
			StagingLlmPassTest.column("COUNTRY", "STRING", false));
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(erp),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(new StagingLlmPass.RuleProbe()
		{
			@Override
			public StagingLlmPass.ProbeResult probe(final String pTable, final String pColumn, final String pPipeline)
			{
				return StagingLlmPassTest.measureForTest(codes, pPipeline);
			}
		});
		StagingLlmPassTest.callEntity(session, "Site", "Site", StagingLlmPassTest.attrs("country,STRING"), null, null, 90, "ok");
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Site", "ERP.ADDRESS", null, StagingLlmPassTest.rules("country,COUNTRY," + partial),
			null, 80, "x");
		StagingLlmPassTest.check("propose_feed refuses the partial map", r.isError && r.text.contains("NOT listed in the map"));
		r = StagingLlmPassTest.callFeed(session, "Site", "ERP.ADDRESS", null, StagingLlmPassTest.rules("country,COUNTRY," + complete), null, 80, "ok");
		StagingLlmPassTest.check("propose_feed records the complete map", !r.isError);
	}

	/** The probe measured through the real Connectors pipeline, as LandingRuleProbe runs it (no map sentinel: none of these pipelines maps). */
	private static StagingLlmPass.ProbeResult measureWithConnectors(final List<String> pSample, final String pPipeline)
	{
		final List<String> outputs = new ArrayList<String>();
		final List<String> failures = new ArrayList<String>();
		for (final String value : pSample)
		{
			try
			{
				outputs.add(com.on.ebx.connect.core.Transformations.text(com.on.ebx.connect.core.Transformations.apply(pPipeline, value, null)));
				failures.add(null);
			}
			catch (final Exception ex)
			{
				outputs.add(null);
				failures.add(ex.getClass().getSimpleName());
			}
		}
		return StagingLlmPass.ProbeResult.of(pSample, outputs, failures, 0, null, null);
	}

	/**
	 * PersVol5 (2026-09-26): taking the first (or last) name out of ECOM.CUSTOMER.FULL_NAME, where many full names share a
	 * first name, keeps a PART of each value - it must not read as a fold. The country catch-all and a first letter taken
	 * out of city names still do. And a pipeline that throws is called invalid, not blamed on the value shapes.
	 */
	private static void extractionIsNotAFold()
	{
		StagingLlmPassTest.check("a word of the value is an extraction", StagingLlmPass.isExtraction("Oliver Weber", "Oliver")
			&& StagingLlmPass.isExtraction("OLIVER WEBER", "Weber") && StagingLlmPass.isExtraction("15/01/2024", "2024")
			&& StagingLlmPass.isExtraction("Hel\u00e8ne  Dupont-Martin", "helene martin") && StagingLlmPass.isExtraction("fr", "FR"));
		StagingLlmPassTest.check("a value it does not contain is not", !StagingLlmPass.isExtraction("IT", "Other")
			&& !StagingLlmPass.isExtraction("Paris", "P") && !StagingLlmPass.isExtraction("Weber Oliver", "Oliver Weber")
			&& !StagingLlmPass.isExtraction("Oliver", "") && !StagingLlmPass.isExtraction("France", "FR"));

		final List<String> fullNames = Arrays.asList("Oliver Weber", "Oliver Schmidt", "Oliver Martin", "Oliver Rossi", "Anna Weber", "Anna Keller",
			"Anna Dubois", "Anna Garcia", "Marc Weber", "Marc Keller", "Marc Rossi", "Marc Dubois");
		final String firstName = "regex(^\\p{L}+)|capitalize";
		final StagingLlmPass.ProbeResult first = StagingLlmPassTest.measureWithConnectors(fullNames, firstName);
		StagingLlmPassTest.check("the first-token extraction keeps every value, 12 distinct into 3, all of them extractions",
			first.emptied == 0 && first.distinctInputs == 12 && first.distinctOutputs == 3 && first.extractedInputs == 12 && first.foldedInputs == 0);
		StagingLlmPassTest.check("and is accepted", StagingLlmPass.probeRefusal("FULL_NAME", firstName, first) == null
			&& StagingLlmPass.lossyRefusal("FULL_NAME", firstName, first) == null);
		final String lastName = "regex(\\p{L}+$)|capitalize";
		final StagingLlmPass.ProbeResult last = StagingLlmPassTest.measureWithConnectors(fullNames, lastName);
		StagingLlmPassTest.check("the last-token extraction (last names shared too) is accepted", last.distinctOutputs == 7 && last.extractedInputs == 12
			&& StagingLlmPass.lossyRefusal("FULL_NAME", lastName, last) == null);

		final List<String> dates = Arrays.asList("2024-01-15", "2024-03-02", "2024-11-30", "2023-05-02", "2023-07-14", "2023-12-31", "2022-02-28",
			"2022-06-01", "2022-09-09");
		final String year = "regex(^(\\d{4}))";
		final StagingLlmPass.ProbeResult years = StagingLlmPassTest.measureWithConnectors(dates, year);
		StagingLlmPassTest.check("a regex taking the year out of dates (9 into 3) is accepted", years.distinctInputs == 9 && years.distinctOutputs == 3
			&& StagingLlmPass.lossyRefusal("VALID_FROM", year, years) == null);

		final List<String> codes = Arrays.asList("FR", "DE", "IT", "ES", "GB", "PT", "NL", "BE");
		final List<String> catchAll = Arrays.asList("France", "Germany", "Other", "Other", "Other", "Other", "Other", "Other");
		final StagingLlmPass.ProbeResult other = StagingLlmPass.ProbeResult.of(codes, catchAll, 0, null, null);
		final String refusal = StagingLlmPass.lossyRefusal("COUNTRY", "catch-all", other);
		StagingLlmPassTest.check("the country catch-all is still a fold, and the refusal names what was measured", other.extractedInputs == 0
			&& refusal != null && refusal.contains("folds 8 distinct values into 3 distinct outputs: 6 of them land on an output shared by 3 or more")
			&& refusal.contains("(0 whose output is a word or number of the value itself were not counted)"));
		final List<String> cities = Arrays.asList("Paris", "Pau", "Poitiers", "Lyon", "Lille", "Lens");
		StagingLlmPassTest.check("a first letter taken out of city names is still a fold",
			StagingLlmPass.lossyRefusal("CITY", "regex(^(.))", StagingLlmPassTest.measureWithConnectors(cities, "regex(^(.))")) != null);

		final String broken = "regex(^\\p{L}+)(0)";
		final StagingLlmPass.ProbeResult invalid = StagingLlmPassTest.measureWithConnectors(fullNames, broken);
		final String invalidRefusal = StagingLlmPass.probeRefusal("FULL_NAME", broken, invalid);
		StagingLlmPassTest.check("the live PersVol5 pipeline fails on every value and is called INVALID, with the regex syntax, not the shapes hint",
			invalid.failed == 12 && invalidRefusal != null && invalidRefusal.contains("INVALID") && invalidRefusal.contains("12 of 12")
				&& invalidRefusal.contains("PatternSyntaxException") && invalidRefusal.contains("SECOND argument")
				&& !invalidRefusal.contains("A shape is not a literal"));
		StagingLlmPassTest.check("the street example of the prompt is quoted: unquoted, its comma ends the pattern and the street becomes the number",
			StagingLlmPass.systemPrompt().contains("regex('^\\d+,?\\s*(.*)$')")
				&& StagingLlmPassTest.applyWithConnectors("regex('^\\d+,?\\s*(.*)$')", "12, rue de la Paix").equals("rue de la Paix")
				&& StagingLlmPassTest.applyWithConnectors("regex(^\\d+,?\\s*(.*)$)", "12, rue de la Paix").equals("12"));
	}

	/**
	 * PersVol7 (2026-09-28, docs/TARGET-MAPPING.md sec 17): ECOM.CUSTOMER.FULL_NAME held "Oliver Weber" (70%) and "ROUSSEAU
	 * Stephane" (30%, one token in capitals, first); the model split it by POSITION and crossed the two attributes of
	 * the second layout. Java measures where the capitals sit ({@link TokenCase}) and which position the rule reads
	 * from each layout; a position-only split of a two-layout column is refused ONCE with the numbers, an order-aware
	 * regex (checked through the real Connectors jar) passes, a one-layout column is never bothered.
	 */
	private static void orderBlindSplitRefusedOnce()
	{
		// the sample as the probe sees it: 17 of 50 distinct values with a leading token in capitals (34%), like the live run
		final List<String> mixed = new ArrayList<String>();
		final String[] capitalsFirst = { "ROUSSEAU St\u00e9phane", "MICHEL C\u00e9line", "O'BRIEN L\u00e9a", "DUBOIS Marc", "GARCIA Ana", "LEROY Paul",
			"MOREAU Julie", "SIMON Luc", "LAURENT Eva", "LEFEBVRE Tom", "ROUX Zo\u00e9", "DAVID No\u00e9", "BERTRAND Lou", "MOREL Max", "FOURNIER Ines",
			"GIRARD Hugo", "BONNET Emma" };
		final String[] mixedCase = { "Oliver Weber", "Anna Keller", "Marc Rossi", "Julie Martin", "Paul Dubois", "Eva Garcia", "Tom Leroy", "Zo\u00e9 Moreau",
			"No\u00e9 Simon", "Lou Laurent", "Max Lefebvre", "Ines Roux", "Hugo David", "Emma Bertrand", "Luc Morel", "Ana Fournier", "Leo Girard",
			"Mia Bonnet", "Sam Petit", "Lea Durand", "Jean-Pierre Dupont", "Nina Robert", "Omar Richard", "Yann Dumont", "Iris Lambert", "Axel Fontaine",
			"Elsa Chevalier", "Noor Robin", "Ivan Masson", "Rose Sanchez", "Gael Nguyen", "Lina Mercier", "Enzo Blanc" };
		mixed.addAll(Arrays.asList(capitalsFirst));
		mixed.addAll(Arrays.asList(mixedCase));
		StagingLlmPassTest.check("50 sampled values, 17 with a leading token in capitals", mixed.size() == 50 && capitalsFirst.length == 17);

		final TokenCase layout = TokenCase.of(mixed);
		StagingLlmPassTest.check("the layout is measured as counts: first token all upper case in 34%, last in 0%, neither 66%",
			layout.multiToken() == 50 && layout.firstUpperOnlyPercent() == 34 && layout.lastUpperOnlyPercent() == 0 && layout.neitherUpperPercent() == 66
				&& layout.twoLayouts() && layout.render().equals("first token all upper case in 34% of the 50 multi-token values, last token in 0%, both in 0%, neither in 66%"));
		StagingLlmPassTest.check("a one-layout column (every value 'Aaaa Aaaa') has no second population",
			!TokenCase.of(Arrays.asList(mixedCase)).twoLayouts() && TokenCase.of(Arrays.asList(mixedCase)).firstUpperPercent() == 0);
		StagingLlmPassTest.check("an all-capitals column ('AAAA AAAA', the ERP) is one layout too",
			!TokenCase.of(Arrays.asList("DUPONT JEAN", "WEBER OLIVER", "ROSSI MARC", "KELLER ANNA")).twoLayouts()
				&& TokenCase.of(Arrays.asList("DUPONT JEAN", "WEBER OLIVER")).bothUpperPercent() == 100);
		StagingLlmPassTest.check("a column of single tokens has no layout", !TokenCase.of(Arrays.asList("FR", "de", "IT")).twoLayouts()
			&& !TokenCase.of(Arrays.asList("FR", "de", "IT")).mostlyMultiToken());
		StagingLlmPassTest.check("a second population under 15% does not count", !TokenCase.of(Arrays.asList("DUPONT Jean", "Anna Keller", "Marc Rossi",
			"Julie Martin", "Paul Dubois", "Eva Garcia", "Tom Leroy", "Lou Laurent", "Max Lefebvre", "Ines Roux")).twoLayouts());

		// the live rules (position only), through the real Connectors jar
		final String blindFirst = "regex(^([\\p{L}'-]+)\\s+[\\p{L}'-]+$)";
		final String blindLast = "regex(^[\\p{L}'-]+\\s+([\\p{L}'-]+)$)";
		final StagingLlmPass.ProbeResult first = StagingLlmPassTest.measureWithConnectors(mixed, blindFirst);
		StagingLlmPassTest.check("nothing emptied, nothing folded: the earlier checks let the blind split through",
			first.emptied == 0 && StagingLlmPass.probeRefusal("FULL_NAME", blindFirst, first) == null && StagingLlmPass.lossyRefusal("FULL_NAME", blindFirst, first) == null);
		StagingLlmPassTest.check("the probe sees the FIRST token read from both layouts", "FIRST".equals(first.positionByLayout.get("first-only"))
			&& "FIRST".equals(first.positionByLayout.get("neither")) && first.positionDetail.equals("first-only: FIRST token in 17 of 17, neither: FIRST token in 33 of 33")
			&& first.outputTokenCase.allUpperPercent() == 34);
		final String refusal = StagingLlmPass.orderRefusal("FULL_NAME", blindFirst, first);
		StagingLlmPassTest.check("and refuses it with the numbers and the two regex forms the catalogue can express", refusal != null
			&& refusal.contains("TWO case layouts") && refusal.contains("first token all upper case in 34% of the 50 multi-token values")
			&& refusal.contains("reads the FIRST token from every layout alike (first-only: FIRST token in 17 of 17, neither: FIRST token in 33 of 33)")
			&& refusal.contains("Its output is all upper case in 34% of the 50 values") && refusal.contains(StagingLlmPass.REGEX_CAPITALS_ELSE_LAST)
			&& refusal.contains(StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS) && refusal.contains("no conditional step") && refusal.contains("map the column WHOLE"));
		final StagingLlmPass.ProbeResult last = StagingLlmPassTest.measureWithConnectors(mixed, blindLast);
		StagingLlmPassTest.check("the last-token rule is refused the same way", StagingLlmPass.orderRefusal("FULL_NAME", blindLast, last) != null
			&& StagingLlmPass.orderRefusal("FULL_NAME", blindLast, last).contains("reads the LAST token from every layout alike"));
		StagingLlmPassTest.check("the same blind rule on a one-layout column is not refused",
			StagingLlmPass.orderRefusal("FULL_NAME", blindFirst, StagingLlmPassTest.measureWithConnectors(Arrays.asList(mixedCase), blindFirst)) == null);
		StagingLlmPassTest.check("a rule that keeps the whole value (upper) is not an extraction: not refused",
			StagingLlmPass.orderRefusal("FULL_NAME", "upper", StagingLlmPassTest.measureWithConnectors(mixed, "upper")) == null);

		// the order-aware forms the refusal suggests, through the real jar: a different position per layout
		final StagingLlmPass.ProbeResult capitals = StagingLlmPassTest.measureWithConnectors(mixed, StagingLlmPass.REGEX_CAPITALS_ELSE_LAST);
		StagingLlmPassTest.check("the capitals-else-last regex reads the FIRST token of the capitals layout and the LAST of the other",
			"FIRST".equals(capitals.positionByLayout.get("first-only")) && "LAST".equals(capitals.positionByLayout.get("neither")) && capitals.emptied == 0
				&& StagingLlmPass.orderRefusal("FULL_NAME", StagingLlmPass.REGEX_CAPITALS_ELSE_LAST, capitals) == null
				&& StagingLlmPass.lossyRefusal("FULL_NAME", StagingLlmPass.REGEX_CAPITALS_ELSE_LAST, capitals) == null);
		final StagingLlmPass.ProbeResult lower = StagingLlmPassTest.measureWithConnectors(mixed, StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS);
		StagingLlmPassTest.check("the lower-case-token regex reads the LAST token of the capitals layout and the FIRST of the other",
			"LAST".equals(lower.positionByLayout.get("first-only")) && "FIRST".equals(lower.positionByLayout.get("neither")) && lower.emptied == 0
				&& StagingLlmPass.orderRefusal("FULL_NAME", StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS, lower) == null);
		StagingLlmPassTest.check("exact outputs of the two forms on the PersVol7 layouts",
			"ROUSSEAU".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_CAPITALS_ELSE_LAST, "ROUSSEAU St\u00e9phane"))
				&& "St\u00e9phane".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS, "ROUSSEAU St\u00e9phane"))
				&& "O'BRIEN".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_CAPITALS_ELSE_LAST, "O'BRIEN L\u00e9a"))
				&& "L\u00e9a".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS, "O'BRIEN L\u00e9a"))
				&& "Weber".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_CAPITALS_ELSE_LAST, "Oliver Weber"))
				&& "Oliver".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS, "Oliver Weber"))
				&& "Jean-Pierre".equals(StagingLlmPassTest.applyWithConnectors(StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS, "Jean-Pierre Dupont")));
		StagingLlmPassTest.check("the system prompt names the capitals-else-last form and the refusal of a position-only split",
			StagingLlmPass.systemPrompt().contains(StagingLlmPass.REGEX_CAPITALS_ELSE_LAST) && StagingLlmPass.systemPrompt().contains("position-only split"));

		// wired into propose_feed: refused once per column, then kept and recorded; the order-aware pair and a whole mapping are recorded too
		final StagingRules.LandingColumnInfo fullName = StagingLlmPassTest.column("FULL_NAME", "STRING", false);
		fullName.tokenCase = layout;
		final StagingRules.LandingTableInfo customer = StagingLlmPassTest.table("t-c", "ECOM", "CUSTOMER", StagingLlmPassTest.column("CUSTOMER_NO", "STRING", true),
			fullName, StagingLlmPassTest.column("EMAIL", "STRING", false));
		StagingLlmPassTest.check("the column's token case reaches the model next to its shapes",
			StagingLlmPass.describeColumn(fullName).contains("token case: first token all upper case in 34% of the 50 multi-token values")
				&& !StagingLlmPass.describeColumn(StagingLlmPassTest.column("EMAIL", "STRING", false)).contains("token case"));
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(customer),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		final StagingLlmPass.RuleProbe probe = new StagingLlmPass.RuleProbe()
		{
			@Override
			public StagingLlmPass.ProbeResult probe(final String pTable, final String pColumn, final String pPipeline)
			{
				return StagingLlmPassTest.measureWithConnectors(mixed, pPipeline);
			}
		};
		StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING", "email,STRING"), null, null, 90, "ok");
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null,
			StagingLlmPassTest.rules("firstName,FULL_NAME," + blindFirst, "lastName,FULL_NAME," + blindLast, "email,EMAIL,"), null, 80, "x");
		StagingLlmPassTest.check("propose_feed refuses the position-only split once", r.isError && r.text.contains("TWO case layouts") && session.orderDecisions().isEmpty());
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null,
			StagingLlmPassTest.rules("firstName,FULL_NAME," + blindFirst, "lastName,FULL_NAME," + blindLast, "email,EMAIL,"), null, 80, "x");
		StagingLlmPassTest.check("sent again unchanged, the feed is recorded and the decision kept in the outcome", !r.isError && session.orderDecisions().size() == 2
			&& session.orderDecisions().get(0).startsWith("Personne.firstName <- ECOM.CUSTOMER.FULL_NAME " + blindFirst + ": order-blind rule kept after one refusal (")
			&& session.orderDecisions().get(1).contains("Personne.lastName <- ECOM.CUSTOMER.FULL_NAME") && !session.stoppedEarly());

		session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "ok");
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.rules(
			"firstName,FULL_NAME," + StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS, "lastName,FULL_NAME," + StagingLlmPass.REGEX_CAPITALS_ELSE_LAST), null, 80, "x");
		StagingLlmPassTest.check("the order-aware pair is recorded first time, as such", !r.isError && session.orderDecisions().size() == 2
			&& session.orderDecisions().get(0).contains(": order-aware rule (first token all upper case in 34%")
			&& session.orderDecisions().get(0).contains("first-only: LAST token in 17 of 17, neither: FIRST token in 33 of 33"));

		session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("fullName,STRING"), null, null, 90, "ok");
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.rules("fullName,FULL_NAME,"), null, 80, "x");
		StagingLlmPassTest.check("the column mapped whole onto one attribute is accepted and the fallback recorded", !r.isError
			&& session.orderDecisions().size() == 1 && session.orderDecisions().get(0).equals(
				"Personne.fullName <- ECOM.CUSTOMER.FULL_NAME: kept whole (first token all upper case in 34% of the 50 multi-token values, last token in 0%, both in 0%, neither in 66%)"));
	}

	/**
	 * Vol100kN (2026-10-12, docs/TARGET-MAPPING.md sec 19): LOYALTY.MEMBER.FULL_NAME holds the whole name first-name-first in
	 * half the rows and last-name-first in the others, no marker: 92% of the values are one casing layout ("Aaaa Aaaa"),
	 * 8% all upper case. The model reused the casing split of another source and took the LAST token as the first one on
	 * half the rows; the order-aware check said nothing (one layout, not two). The Factory now says so in the column's
	 * description and records an "order unknown" decision for a regex that splits it.
	 */
	private static void orderUnknownIsSurfaced()
	{
		final List<String> loyalty = new ArrayList<String>();
		final String[] mixedOrder = { "Oliver Weber", "Keller Anna", "Marc Rossi", "Martin Julie", "Paul Dubois", "Garcia Eva", "Tom Leroy", "Moreau Zo\u00e9",
			"No\u00e9 Simon", "Laurent Lou", "Max Lefebvre", "Roux Ines", "Hugo David", "Bertrand Emma", "Luc Morel", "Fournier Ana", "Leo Girard", "Bonnet Mia",
			"Sam Petit", "Durand Lea", "Jean-Pierre Dupont", "Robert Nina", "Omar Richard", "Dumont Yann", "Iris Lambert", "Fontaine Axel", "Elsa Chevalier",
			"Robin Noor", "Ivan Masson", "Sanchez Rose", "Gael Nguyen", "Mercier Lina", "Enzo Blanc", "Perrin Alice", "Hugo Vidal", "Colin Jade", "Noel Remi",
			"Guerin Sara", "Adam Roche", "Barre Lila", "Klein Theo", "Henry Maya", "Muller Jules", "Vincent Zara" };
		final String[] allUpper = { "DUPONT JEAN", "WEBER OLIVER", "ROSSI MARC", "KELLER ANNA" };
		loyalty.addAll(Arrays.asList(mixedOrder));
		loyalty.addAll(Arrays.asList(allUpper));
		StagingLlmPassTest.check("48 distinct values, 92% one casing layout and 8% all upper case", loyalty.size() == 48 && mixedOrder.length == 44);
		final TokenCase layout = TokenCase.of(loyalty);
		StagingLlmPassTest.check("the layout: neither token upper case in 92%, both in 8%, not two populations",
			layout.neitherUpperPercent() == 92 && layout.bothUpperPercent() == 8 && !layout.twoLayouts() && !layout.casingSeparatesParts());
		StagingLlmPassTest.check("the order of the parts is unknown", layout.orderUnknown() && layout.alphabeticPercent() == 100 && layout.meanTokens() == 2.0);
		final String evidence = layout.renderOrderEvidence();
		StagingLlmPassTest.check("the exact evidence text", "multi-token values in one casing layout, the order of the parts cannot be inferred from casing (neither the first nor the last token all upper case in 92%, the first and the last token all upper case in 8%, mean 2.0 words per value): a rule that reads a part by its position or by its case is a guess for the rows written in the other order".equals(evidence));

		// what is NOT order-unknown: casing that separates the parts, single tokens, numbers in the tokens, free text, too few values
		StagingLlmPassTest.check("the PersVol7 column (leading token in capitals in 34%) is not order-unknown: casing tells the parts apart",
			!TokenCase.of(StagingLlmPassTest.twoLayoutNames()).orderUnknown() && TokenCase.of(StagingLlmPassTest.twoLayoutNames()).renderOrderEvidence() == null);
		StagingLlmPassTest.check("single tokens are not", !TokenCase.of(Arrays.asList("FR", "de", "IT", "es", "pt", "nl", "be")).orderUnknown());
		StagingLlmPassTest.check("an address line with a number is not", !TokenCase.of(Arrays.asList("12 rue Haute", "3 avenue Foch", "8 place Neuve",
			"9 quai Bas", "44 rue Longue", "2 allee Verte")).orderUnknown());
		StagingLlmPassTest.check("a free text of many words is not", !TokenCase.of(Arrays.asList("a long comment about the delivery of the order number",
			"another very long remark on the customer and the contact person", "one more note written by the account manager last week",
			"yet another sentence about a visit that took place in the spring", "final long text of this sample column with many words")).orderUnknown());
		StagingLlmPassTest.check("four values prove nothing", !TokenCase.of(Arrays.asList("Oliver Weber", "Keller Anna", "Marc Rossi", "Martin Julie")).orderUnknown());
		StagingLlmPassTest.check("a 20% share of a leading token in capitals separates the parts", !TokenCase.of(Arrays.asList("DUPONT Jean", "ROSSI Marc", "Anna Keller",
			"Julie Martin", "Paul Dubois", "Eva Garcia", "Tom Leroy", "Lou Laurent", "Max Lefebvre", "Ines Roux")).orderUnknown());

		// the evidence reaches the model: the column description, the inventory line and describe_landing_table
		final StagingRules.LandingColumnInfo fullName = StagingLlmPassTest.column("FULL_NAME", "STRING", false);
		fullName.tokenCase = layout;
		StagingLlmPassTest.check("describeColumn carries ', token case: ...' then ', ORDER UNKNOWN: <evidence>'", StagingLlmPass.describeColumn(fullName)
			.contains("token case: first token all upper case in 0% of the 48 multi-token values, last token in 0%, both in 8%, neither in 92%, ORDER UNKNOWN: " + evidence));
		final StagingRules.LandingColumnInfo email = StagingLlmPassTest.column("EMAIL", "STRING", false);
		email.tokenCase = TokenCase.of(Arrays.asList("a@b.fr", "c@d.fr"));
		StagingLlmPassTest.check("a column with no multi-token layout says nothing", !StagingLlmPass.describeColumn(email).contains("ORDER UNKNOWN"));
		final StagingRules.LandingTableInfo member = StagingLlmPassTest.table("t-m", "LOYALTY", "MEMBER", StagingLlmPassTest.column("MEMBER_NO", "STRING", true),
			fullName, email);
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(member),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		final LocalToolResultCheck described = StagingLlmPassTest.callDescribe(session, "LOYALTY.MEMBER");
		StagingLlmPassTest.check("describe_landing_table carries the evidence line", !described.isError && described.text.contains("ORDER UNKNOWN: " + evidence));
		StagingLlmPassTest.check("the inventory of the user prompt carries it too", StagingLlmPass.userPrompt(project).contains("ORDER UNKNOWN: " + evidence));
		StagingLlmPassTest.check("the system prompt tells the model what to do: a full-value attribute, or a stated assumption at a LOW confidence",
			StagingLlmPass.systemPrompt().contains("map the whole value onto ONE full-value attribute") && StagingLlmPass.systemPrompt().contains("a LOW confidence (at most 40)")
				&& StagingLlmPass.systemPrompt().contains("description says \"ORDER UNKNOWN\""));

		// the measurement of a split: a strict, contiguous run of the input's tokens
		StagingLlmPassTest.check("a strict token part", PartOrder.isStrictTokenPart("Anne Marie Dupont", "Dupont") && PartOrder.isStrictTokenPart("Anne Marie Dupont", "Anne Marie")
			&& PartOrder.isStrictTokenPart("Jean-Pierre Dupont", "jean-pierre") && PartOrder.isStrictTokenPart("Zo\u00e9 Moreau", "ZOE"));
		StagingLlmPassTest.check("not a part: the whole value, nothing, another text, a token run with a gap", !PartOrder.isStrictTokenPart("Anne Marie Dupont", "Anne Marie Dupont")
			&& !PartOrder.isStrictTokenPart("Anne Marie Dupont", "") && !PartOrder.isStrictTokenPart("Anne Marie Dupont", null) && !PartOrder.isStrictTokenPart("Anne Marie Dupont", "Martin")
			&& !PartOrder.isStrictTokenPart("Anne Marie Dupont", "Anne Dupont"));
		StagingLlmPassTest.check("regex steps are found, a constant is not", PartOrder.hasRegexStep("regex(^(\\w+))") && PartOrder.hasRegexStep("trim|regex(^(\\w+))")
			&& !PartOrder.hasRegexStep("upper") && !PartOrder.hasRegexStep(null) && !PartOrder.hasRegexStep("constant('regex(x)')"));

		// a casing regex through the real Connectors jar: recorded as "order unknown", not refused, once per attribute rule
		final String capitalsElseLast = StagingLlmPass.REGEX_CAPITALS_ELSE_LAST;
		final String lowerAfterCapitals = StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS;
		final StagingLlmPass.ProbeResult probed = StagingLlmPassTest.measureWithConnectors(loyalty, capitalsElseLast);
		StagingLlmPassTest.check("the probe measures a strict part on every value", PartOrder.splitCounts(probed.outputByInput)[0] == 48
			&& PartOrder.splitCounts(probed.outputByInput)[1] == 48 && PartOrder.splitsUnknownOrder(probed.inputTokenCase, probed.outputByInput));
		StagingLlmPassTest.check("a rule that keeps the whole value is not a split", !PartOrder.splitsUnknownOrder(probed.inputTokenCase,
			StagingLlmPassTest.measureWithConnectors(loyalty, "upper").outputByInput));
		StagingLlmPassTest.check("the same split on a column whose casing separates the parts is not 'order unknown'", !PartOrder.splitsUnknownOrder(
			StagingLlmPassTest.measureWithConnectors(StagingLlmPassTest.twoLayoutNames(), capitalsElseLast).inputTokenCase,
			StagingLlmPassTest.measureWithConnectors(StagingLlmPassTest.twoLayoutNames(), capitalsElseLast).outputByInput));
		final StagingLlmPass.RuleProbe probe = new StagingLlmPass.RuleProbe()
		{
			@Override
			public StagingLlmPass.ProbeResult probe(final String pTable, final String pColumn, final String pPipeline)
			{
				return StagingLlmPassTest.measureWithConnectors(loyalty, pPipeline);
			}
		};
		session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING", "email,STRING"), null, null, 90, "ok");
		final LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "LOYALTY.MEMBER", null, StagingLlmPassTest.rules(
			"firstName,FULL_NAME," + lowerAfterCapitals, "lastName,FULL_NAME," + capitalsElseLast), null, 74, "x");
		StagingLlmPassTest.check("the casing split of the LOYALTY column is not refused", !r.isError && !session.stoppedEarly());
		StagingLlmPassTest.check("it is recorded as 'order unknown', one decision per rule, with the measured layout and counts", session.orderDecisions().size() == 2
			&& session.orderDecisions().get(0).startsWith("Personne.firstName <- LOYALTY.MEMBER.FULL_NAME " + lowerAfterCapitals + ": order unknown (neither the first nor the last token all upper case in 92%")
			&& session.orderDecisions().get(0).contains("the rule takes a part of the value in 44 of 48 multi-token values")
			&& session.orderDecisions().get(0).endsWith("keep the full value and compare it with an order-independent key)")
			&& session.orderDecisions().get(1).startsWith("Personne.lastName <- LOYALTY.MEMBER.FULL_NAME " + capitalsElseLast + ": order unknown ("));

		// mapped whole onto one full-value attribute: nothing to record
		session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("fullName,STRING"), null, null, 90, "ok");
		final LocalToolResultCheck whole = StagingLlmPassTest.callFeed(session, "Personne", "LOYALTY.MEMBER", null, StagingLlmPassTest.rules("fullName,FULL_NAME,"), null, 80, "x");
		StagingLlmPassTest.check("the column mapped whole is accepted without any order decision", !whole.isError && session.orderDecisions().isEmpty());
	}

	/** The PersVol7/PersVol8 sample as the probe sees it: 50 distinct full names, 17 with a leading token in capitals. */
	private static List<String> twoLayoutNames()
	{
		final List<String> mixed = new ArrayList<String>();
		mixed.addAll(Arrays.asList("ROUSSEAU St\u00e9phane", "MICHEL C\u00e9line", "O'BRIEN L\u00e9a", "DUBOIS Marc", "GARCIA Ana", "LEROY Paul", "MOREAU Julie",
			"SIMON Luc", "LAURENT Eva", "LEFEBVRE Tom", "ROUX Zo\u00e9", "DAVID No\u00e9", "BERTRAND Lou", "MOREL Max", "FOURNIER Ines", "GIRARD Hugo", "BONNET Emma"));
		mixed.addAll(Arrays.asList("Oliver Weber", "Anna Keller", "Marc Rossi", "Julie Martin", "Paul Dubois", "Eva Garcia", "Tom Leroy", "Zo\u00e9 Moreau",
			"No\u00e9 Simon", "Lou Laurent", "Max Lefebvre", "Ines Roux", "Hugo David", "Emma Bertrand", "Luc Morel", "Ana Fournier", "Leo Girard", "Mia Bonnet",
			"Sam Petit", "Lea Durand", "Jean-Pierre Dupont", "Nina Robert", "Omar Richard", "Yann Dumont", "Iris Lambert", "Axel Fontaine", "Elsa Chevalier",
			"Noor Robin", "Ivan Masson", "Rose Sanchez", "Gael Nguyen", "Lina Mercier", "Enzo Blanc"));
		return mixed;
	}

	/** Like {@link #rules}, for pipelines that hold a comma: one {@code {attribute, column, pipeline}} triple per rule. */
	private static ArrayNode ruleTriples(final String[]... pRules)
	{
		final ArrayNode out = StagingLlmPassTest.MAPPER.createArrayNode();
		for (final String[] rule : pRules)
		{
			final ObjectNode node = out.addObject();
			node.put("attribute", rule[0]);
			node.put("landingColumn", rule[1]);
			if (rule[2] != null && !rule[2].isEmpty())
			{
				node.put("pipeline", rule[2]);
			}
		}
		return out;
	}

	/** A probe over a fixed sample through the real Connectors jar, counting its calls. */
	private static final class CountingProbe implements StagingLlmPass.RuleProbe
	{
		private final List<String> sample;
		int calls;

		CountingProbe(final List<String> pSample)
		{
			this.sample = pSample;
		}

		@Override
		public StagingLlmPass.ProbeResult probe(final String pTable, final String pColumn, final String pPipeline)
		{
			this.calls++;
			return StagingLlmPassTest.measureWithConnectors(this.sample, pPipeline);
		}
	}

	/**
	 * PersVol8 (2026-09-28, docs/TARGET-MAPPING.md sec 18): the model proposed the SAME order-aware regex (the
	 * capitals-else-last form of sec 17) for firstName AND lastName on ECOM.CUSTOMER.FULL_NAME, so both attributes
	 * received the last name; every earlier check looks at one rule alone. Java now keeps, per propose_feed call and
	 * landing column, the outputs of the first probed rule and compares every later rule of another attribute with
	 * them: identical on 80% or more of the filled values -> refused ONCE per (feed, column, attribute) with the share
	 * and the two attribute names; a rule giving the other part passes; sent again unchanged, it is kept and recorded.
	 */
	private static void identicalOutputsRefusedOnce()
	{
		final List<String> mixed = StagingLlmPassTest.twoLayoutNames();
		final String same = StagingLlmPass.REGEX_CAPITALS_ELSE_LAST;
		final String other = StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS;
		final StagingLlmPass.ProbeResult first = StagingLlmPassTest.measureWithConnectors(mixed, same);
		final StagingLlmPass.ProbeResult second = StagingLlmPassTest.measureWithConnectors(mixed, same);
		StagingLlmPassTest.check("the probe keeps each input's output, in Java only", first.outputByInput.size() == 50
			&& "ROUSSEAU".equals(first.outputByInput.get("ROUSSEAU St\u00e9phane")) && "Weber".equals(first.outputByInput.get("Oliver Weber")));
		final int[] counts = StagingLlmPass.identicalOutputs(second, first);
		StagingLlmPassTest.check("the same regex twice: 50 of 50 identical", counts[0] == 50 && counts[1] == 50);
		final String refusal = StagingLlmPass.identicalRefusal("FULL_NAME", same, "lastName", "firstName", same, second, first);
		StagingLlmPassTest.check("refused with the measured share, the two attribute names and the two labelled forms", refusal != null
			&& refusal.contains("for attribute 'lastName' gives the same output as the rule already proposed for attribute 'firstName'")
			&& refusal.contains("in 50 of 50 values (100%)") && refusal.contains("two attributes fed from one column must receive DIFFERENT parts")
			&& refusal.contains("the token in capitals, else the last token: " + same)
			&& refusal.contains("the token holding a lower-case letter after an optional leading token in capitals: " + other)
			&& refusal.contains("map the column whole onto ONE attribute"));
		final StagingLlmPass.ProbeResult complement = StagingLlmPassTest.measureWithConnectors(mixed, other);
		final int[] differ = StagingLlmPass.identicalOutputs(complement, first);
		StagingLlmPassTest.check("the complementary form gives the other token on every value: 0 of 50, accepted", differ[0] == 0 && differ[1] == 50
			&& StagingLlmPass.identicalRefusal("FULL_NAME", other, "lastName", "firstName", same, complement, first) == null);
		StagingLlmPassTest.check("a rule keeping the whole value against one changing its case is not identical (case counts)",
			StagingLlmPass.identicalOutputs(StagingLlmPassTest.measureWithConnectors(mixed, "trim"), StagingLlmPassTest.measureWithConnectors(mixed, "upper"))[0] == 0);

		// the threshold: 8 of 10 identical is refused, 7 of 10 is not; an emptied value on both sides is never identical
		final List<String> inputs = Arrays.asList("v1", "v2", "v3", "v4", "v5", "v6", "v7", "v8", "v9", "v10");
		final List<String> outputsA = Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "a9", "a10");
		final List<String> eightSame = Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6", "a7", "a8", "x9", "x10");
		final List<String> sevenSame = Arrays.asList("a1", "a2", "a3", "a4", "a5", "a6", "a7", "x8", "x9", "x10");
		final StagingLlmPass.ProbeResult a = StagingLlmPass.ProbeResult.of(inputs, outputsA, 0, null, null);
		StagingLlmPassTest.check("80% identical is refused, 70% is not", StagingLlmPass.IDENTICAL_OUTPUT_MIN_PERCENT == 80
			&& StagingLlmPass.identicalRefusal("C", "p", "b", "a", "q", StagingLlmPass.ProbeResult.of(inputs, eightSame, 0, null, null), a) != null
			&& StagingLlmPass.identicalRefusal("C", "p", "b", "a", "q", StagingLlmPass.ProbeResult.of(inputs, eightSame, 0, null, null), a).contains("in 8 of 10 values (80%)")
			&& StagingLlmPass.identicalRefusal("C", "p", "b", "a", "q", StagingLlmPass.ProbeResult.of(inputs, sevenSame, 0, null, null), a) == null);
		final List<String> allEmpty = Arrays.asList(null, null, null, null, null, null, null, null, null, null);
		StagingLlmPassTest.check("two rules emptying everything are not 'identical' (and a null probe compares nothing)",
			StagingLlmPass.identicalOutputs(StagingLlmPass.ProbeResult.of(inputs, allEmpty, 0, null, null), StagingLlmPass.ProbeResult.of(inputs, allEmpty, 0, null, null))[0] == 0
				&& StagingLlmPass.identicalOutputs(null, a)[1] == 0 && StagingLlmPass.identicalRefusal("C", "p", "b", "a", "q", null, a) == null);

		// the street case of the same run: the house-number rule against the quoted street rule differ; against the unquoted one they coincide
		final List<String> streets = Arrays.asList("12 rue de la Paix", "7, avenue Foch", "125 boulevard Haussmann", "3 place Bellecour", "48 rue Nationale",
			"9, cours Mirabeau", "21 rue du Bac", "66 avenue Jean Jaures", "5 rue Victor Hugo", "101 rue de Rivoli", "18 quai des Belges", "2 rue Neuve");
		final String houseNumber = "regex(^([\\p{N}]+))";
		final String street = "regex('^\\d+,?\\s*(.*)$')";
		final String streetUnquoted = "regex(^\\d+,?\\s*(.*)$)";
		final StagingLlmPass.ProbeResult numbers = StagingLlmPassTest.measureWithConnectors(streets, houseNumber);
		StagingLlmPassTest.check("quoted street rule: no output in common with the house number", StagingLlmPass.identicalOutputs(StagingLlmPassTest.measureWithConnectors(streets, street), numbers)[0] == 0);
		StagingLlmPassTest.check("unquoted: 12 of 12 identical, the live PersVol8 symptom, refused here too",
			StagingLlmPass.identicalRefusal("MAILING_STREET", streetUnquoted, "streetName", "houseNumber", houseNumber,
				StagingLlmPassTest.measureWithConnectors(streets, streetUnquoted), numbers).contains("in 12 of 12 values (100%)"));

		// wired into propose_feed: refused once per (feed, column, attribute); a different second rule passes; insisted upon, kept and recorded
		final StagingRules.LandingColumnInfo fullName = StagingLlmPassTest.column("FULL_NAME", "STRING", false);
		fullName.tokenCase = TokenCase.of(mixed);
		final StagingRules.LandingTableInfo customer = StagingLlmPassTest.table("t-c", "ECOM", "CUSTOMER", StagingLlmPassTest.column("CUSTOMER_NO", "STRING", true),
			fullName, StagingLlmPassTest.column("EMAIL", "STRING", false));
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(customer),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(new CountingProbe(mixed));
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING", "email,STRING"), null, null, 90, "ok");
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.ruleTriples(
			new String[] { "firstName", "FULL_NAME", same }, new String[] { "lastName", "FULL_NAME", same }, new String[] { "email", "EMAIL", "" }), null, 80, "x");
		StagingLlmPassTest.check("propose_feed refuses the second rule, naming the first attribute and the share; nothing recorded", r.isError
			&& r.text.contains("pipeline '" + same + "' for attribute 'lastName' gives the same output as the rule already proposed for attribute 'firstName'")
			&& r.text.contains("in 50 of 50 values (100%)") && session.identicalOutputDecisions().isEmpty() && session.orderDecisions().isEmpty()
			&& !session.stoppedEarly());
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.ruleTriples(
			new String[] { "firstName", "FULL_NAME", same }, new String[] { "lastName", "FULL_NAME", other }, new String[] { "email", "EMAIL", "" }), null, 80, "x");
		StagingLlmPassTest.check("with the complementary form for lastName the feed is recorded, both rules order-aware, no identical decision", !r.isError
			&& session.identicalOutputDecisions().isEmpty() && session.orderDecisions().size() == 2 && !session.stoppedEarly());

		session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(new CountingProbe(mixed));
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "ok");
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.ruleTriples(new String[] { "firstName", "FULL_NAME", same },
			new String[] { "lastName", "FULL_NAME", same }), null, 80, "x");
		StagingLlmPassTest.check("refused once", r.isError && r.text.contains("gives the same output"));
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.ruleTriples(new String[] { "firstName", "FULL_NAME", same },
			new String[] { "lastName", "FULL_NAME", same }), null, 80, "x");
		StagingLlmPassTest.check("sent again unchanged, the feed is recorded and the decision kept in the outcome", !r.isError
			&& session.identicalOutputDecisions().size() == 1 && session.identicalOutputDecisions().get(0).equals(
				"Personne.lastName <- ECOM.CUSTOMER.FULL_NAME " + same + ": same output as firstName in 50 of 50 values, kept after one refusal")
			&& !session.stoppedEarly());

		session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		session.setRuleProbe(new CountingProbe(mixed));
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("fullName,STRING", "lastName,STRING"), null, null, 90, "ok");
		r = StagingLlmPassTest.callFeed(session, "Personne", "ECOM.CUSTOMER", null, StagingLlmPassTest.ruleTriples(new String[] { "fullName", "FULL_NAME", "" },
			new String[] { "lastName", "FULL_NAME", same }), null, 80, "x");
		StagingLlmPassTest.check("a column mapped whole next to an extraction is not compared (only probed rules are)", !r.isError
			&& session.identicalOutputDecisions().isEmpty());
	}

	/**
	 * PersVol8 (2026-09-28, docs/TARGET-MAPPING.md sec 18): {@code regex(^\d+,?\s*(.*)$)} on CRM.CONTACT.MAILING_STREET -
	 * Connectors splits the arguments on the unquoted comma, compiles {@code ^\d+} and the street rule returned the
	 * house number, which the probe accepted as an extraction. The comma is now caught structurally, BEFORE the
	 * probe, by mirroring Connectors' own argument split; quoted patterns and {@code regex(pattern,group)} pass.
	 */
	private static void unquotedCommaRefusedBeforeProbe()
	{
		final String unquoted = "regex(^\\d+,?\\s*(.*)$)";
		final String quoted = "regex('^\\d+,?\\s*(.*)$')";
		StagingLlmPassTest.check("what Connectors really does with the two forms",
			"12".equals(StagingLlmPassTest.applyWithConnectors(unquoted, "12 rue de la Paix")) && "rue de la Paix".equals(StagingLlmPassTest.applyWithConnectors(quoted, "12 rue de la Paix")));
		final String refusal = StagingLlmPass.unquotedCommaRefusal(unquoted);
		StagingLlmPassTest.check("the unquoted comma is refused by its position, with the pattern Connectors would compile and the quoted form", refusal != null
			&& refusal.startsWith("the comma at position 11 splits the arguments of regex()") && refusal.contains("reads '^\\d+' as the pattern")
			&& refusal.contains("regex('^\\d+,?\\s*(.*)$')") && refusal.contains("or write the alternative without a comma"));
		StagingLlmPassTest.check("the quoted form passes", StagingLlmPass.unquotedCommaRefusal(quoted) == null);
		StagingLlmPassTest.check("a {n,m} quantifier and a character class holding a comma are refused unquoted, accepted quoted",
			StagingLlmPass.unquotedCommaRefusal("regex(^\\d{1,3})") != null && StagingLlmPass.unquotedCommaRefusal("regex('^\\d{1,3}')") == null
				&& StagingLlmPass.unquotedCommaRefusal("regex(^(\\d{2,4})-)") != null && StagingLlmPass.unquotedCommaRefusal("regex([a,b]+)") != null
				&& StagingLlmPass.unquotedCommaRefusal("regex('[a,b]+')") == null);
		StagingLlmPassTest.check("regex(pattern,group) with an integer group passes, a quoted pattern with a group too",
			StagingLlmPass.unquotedCommaRefusal("regex(^(\\p{L}+),1)") == null && StagingLlmPass.unquotedCommaRefusal("regex(^\\p{L}+,0)") == null
				&& StagingLlmPass.unquotedCommaRefusal("regex('a,b',1)") == null && StagingLlmPass.unquotedCommaRefusal("regex( '^\\d{1,3}' , 1 )") == null);
		StagingLlmPassTest.check("a quantifier comma next to a group argument is still the first comma refused",
			StagingLlmPass.unquotedCommaRefusal("regex(^\\d{1,3},1)") != null && StagingLlmPass.unquotedCommaRefusal("regex(^\\d{1,3},1)").contains("position 12"));
		StagingLlmPassTest.check("later steps are scanned at their own offset; other steps and no pipeline are left alone",
			StagingLlmPass.unquotedCommaRefusal("trim|" + unquoted + "|upper") != null && StagingLlmPass.unquotedCommaRefusal("trim|" + unquoted + "|upper").contains("position 16")
				&& StagingLlmPass.unquotedCommaRefusal("map(a=b,c=d)") == null && StagingLlmPass.unquotedCommaRefusal("trim|upper") == null
				&& StagingLlmPass.unquotedCommaRefusal(null) == null && StagingLlmPass.unquotedCommaRefusal("") == null);
		StagingLlmPassTest.check("the two order-aware forms of sec 17 hold no comma", StagingLlmPass.unquotedCommaRefusal(StagingLlmPass.REGEX_CAPITALS_ELSE_LAST) == null
			&& StagingLlmPass.unquotedCommaRefusal(StagingLlmPass.REGEX_LOWER_AFTER_CAPITALS) == null);
		StagingLlmPassTest.check("an apostrophe in a class toggles the quoted state exactly as Connectors does: a comma between two of them is quoted",
			StagingLlmPass.unquotedCommaRefusal("regex(^([\\p{L}'-]+),\\s+([\\p{L}'-]+)$)") == null
				&& StagingLlmPass.unquotedCommaRefusal("regex(^([\\p{L}'-]+)\\s+([\\p{L}'-]+),$)") != null);

		// wired into propose_feed: refused before the probe runs; the quoted form is then probed and accepted
		final List<String> streets = Arrays.asList("12 rue de la Paix", "7, avenue Foch", "125 boulevard Haussmann", "3 place Bellecour", "48 rue Nationale",
			"9, cours Mirabeau", "21 rue du Bac", "66 avenue Jean Jaures", "5 rue Victor Hugo", "101 rue de Rivoli", "18 quai des Belges", "2 rue Neuve");
		final StagingRules.LandingTableInfo contact = StagingLlmPassTest.table("t-contact", "CRM", "CONTACT", StagingLlmPassTest.column("CONTACT_ID", "STRING", true),
			StagingLlmPassTest.column("MAILING_STREET", "STRING", false));
		final StagingLlmPass.ProjectInfo project = new StagingLlmPass.ProjectInfo("P", "P", Arrays.asList(contact),
			Collections.<StagingRules.RelationInfo>emptyList(), Collections.<StagingLlmPass.RuleEntityInfo>emptyList());
		final StagingLlmPass.Session session = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		final CountingProbe probe = new CountingProbe(streets);
		session.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("houseNumber,STRING", "streetName,STRING"), null, null, 90, "ok");
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.CONTACT", null, StagingLlmPassTest.ruleTriples(
			new String[] { "houseNumber", "MAILING_STREET", "regex(^([\\p{N}]+))" }, new String[] { "streetName", "MAILING_STREET", unquoted }), null, 80, "x");
		StagingLlmPassTest.check("propose_feed refuses the unquoted comma before probing that rule (only the house-number rule was probed)", r.isError
			&& r.text.startsWith("the comma at position 11 splits the arguments of regex()") && probe.calls == 1 && !session.stoppedEarly());
		r = StagingLlmPassTest.callFeed(session, "Adresse", "CRM.CONTACT", null, StagingLlmPassTest.ruleTriples(
			new String[] { "houseNumber", "MAILING_STREET", "regex(^([\\p{N}]+))" }, new String[] { "streetName", "MAILING_STREET", quoted }), null, 80, "x");
		StagingLlmPassTest.check("the quoted form is probed, differs from the house number, and the feed is recorded", !r.isError && probe.calls == 3
			&& session.identicalOutputDecisions().isEmpty());
		final StagingLlmPass.Session fresh = new StagingLlmPass.Session(project, StagingLlmPassTest.noPriorEntities());
		fresh.setRuleProbe(probe);
		StagingLlmPassTest.callEntity(fresh, "Adresse", "Adresse", StagingLlmPassTest.attrs("houseNumber,STRING", "streetName,STRING"), null, null, 90, "ok");
		r = StagingLlmPassTest.callFeed(fresh, "Adresse", "CRM.CONTACT", null, StagingLlmPassTest.ruleTriples(
			new String[] { "houseNumber", "MAILING_STREET", "regex(^([\\p{N}]+))" }), "concat(CONTACT_ID)|regex(^\\d{1,3})", 80, "x");
		StagingLlmPassTest.check("a sourceIdPipeline is checked the same way: " + r.text, r.isError && r.text.startsWith("sourceIdPipeline: the comma at position"));
	}

	private static String applyWithConnectors(final String pPipeline, final String pValue)
	{
		try
		{
			return com.on.ebx.connect.core.Transformations.text(com.on.ebx.connect.core.Transformations.apply(pPipeline, pValue, null));
		}
		catch (final Exception ex)
		{
			return null;
		}
	}

	private static LocalToolResultCheck callFeedVariant(final StagingLlmPass.Session pSession, final String pEntity, final String pLandingTable,
		final String pParentFeed, final String pVariant, final ArrayNode pRules)
	{
		final ObjectNode args = StagingLlmPassTest.MAPPER.createObjectNode();
		args.put("entity", pEntity);
		args.put("landingTable", pLandingTable);
		if (pParentFeed != null)
		{
			args.put("parentFeed", pParentFeed);
		}
		args.put("variant", pVariant);
		args.set("rules", pRules);
		args.put("confidence", 85);
		args.put("rationale", "Address held in the same CRM row.");
		final LocalTool.Result r = pSession.proposeFeed(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	/** Past {@link StagingLlmPass#INVENTORY_CHAR_BUDGET}, the catalogue goes compact and propose_feed gates on describe_landing_table. */
	private static void compactModeGatesOnDescribe()
	{
		final List<StagingRules.LandingTableInfo> tables = new ArrayList<StagingRules.LandingTableInfo>();
		final List<StagingLlmPass.RuleEntityInfo> ruleEntities = new ArrayList<StagingLlmPass.RuleEntityInfo>();
		// enough tables/columns to blow well past the char budget
		for (int t = 0; t < 40; t++)
		{
			final List<StagingRules.LandingColumnInfo> columns = new ArrayList<StagingRules.LandingColumnInfo>();
			for (int c = 0; c < 30; c++)
			{
				columns.add(StagingLlmPassTest.column("COLUMN_" + t + "_" + c + "_WITH_A_LONGER_NAME_TO_PAD_SIZE", "STRING", c == 0));
			}
			final String name = "TABLE_" + t;
			tables.add(new StagingRules.LandingTableInfo("t-" + t, "ERP", name, Long.valueOf(10), columns));
			ruleEntities.add(new StagingLlmPass.RuleEntityInfo("Entity" + t, "ERP", name));
		}
		final StagingLlmPass.ProjectInfo bigProject = new StagingLlmPass.ProjectInfo("Big", "Big project", tables,
			Collections.<StagingRules.RelationInfo>emptyList(), ruleEntities);
		StagingLlmPassTest.check("this fixture is actually over budget", StagingLlmPass.isCompact(bigProject));
		final String compactPrompt = StagingLlmPass.userPrompt(bigProject);
		StagingLlmPassTest.check("compact prompt lists table names but not every column", compactPrompt.contains("ERP.TABLE_0")
			&& !compactPrompt.contains("COLUMN_0_5_WITH_A_LONGER_NAME_TO_PAD_SIZE"));
		StagingLlmPassTest.check("compact prompt tells the model to call describe_landing_table", compactPrompt.contains("describe_landing_table"));

		final StagingLlmPass.Session session = new StagingLlmPass.Session(bigProject, StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Something", "Something", StagingLlmPassTest.attrs("x,STRING"), null, null, 90, "ok");
		final LocalToolResultCheck blocked = StagingLlmPassTest.callFeed(session, "Something", "ERP.TABLE_0", null,
			StagingLlmPassTest.rules("x,COLUMN_0_0_WITH_A_LONGER_NAME_TO_PAD_SIZE,"), null, 90, "x");
		StagingLlmPassTest.check("propose_feed is refused before describe_landing_table was called", blocked.isError && blocked.text.contains("call describe_landing_table"));

		final LocalToolResultCheck described = StagingLlmPassTest.callDescribe(session, "ERP.TABLE_0");
		StagingLlmPassTest.check("describe_landing_table answers the real columns", !described.isError && described.text.contains("COLUMN_0_0_WITH_A_LONGER_NAME_TO_PAD_SIZE"));

		final LocalToolResultCheck allowed = StagingLlmPassTest.callFeed(session, "Something", "ERP.TABLE_0", null,
			StagingLlmPassTest.rules("x,COLUMN_0_0_WITH_A_LONGER_NAME_TO_PAD_SIZE,"), null, 90, "x");
		StagingLlmPassTest.check("propose_feed succeeds once the table was described", !allowed.isError);

		final LocalToolResultCheck unknownTable = StagingLlmPassTest.callDescribe(session, "ERP.NO_SUCH_TABLE");
		StagingLlmPassTest.check("describe_landing_table refuses an unknown table", unknownTable.isError && unknownTable.text.contains("unknown landing table"));
	}

	/**
	 * Same mechanism as the retired pass's fix C: three identical refusals in a row (same tool, same repeated
	 * key) end the run outright.
	 */
	private static void earlyStopOnRepeatedRefusal()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		LocalToolResultCheck r1 = StagingLlmPassTest.callEntity(session, "PersonFlat", "x", StagingLlmPassTest.attrs("x,STRING"), null, null, 80, "x");
		StagingLlmPassTest.check("1st attempt: plain refusal, not stopped yet", r1.isError && !session.stoppedEarly());
		LocalToolResultCheck r2 = StagingLlmPassTest.callEntity(session, "PersonFlat", "x", StagingLlmPassTest.attrs("x,STRING"), null, null, 80, "x");
		StagingLlmPassTest.check("2nd attempt: plain refusal, not stopped yet", r2.isError && !session.stoppedEarly());
		LocalToolResultCheck r3 = StagingLlmPassTest.callEntity(session, "PersonFlat", "x", StagingLlmPassTest.attrs("x,STRING"), null, null, 80, "x");
		StagingLlmPassTest.check("3rd attempt: told to stop proposing it", r3.isError && r3.text.toLowerCase(Locale.ROOT).contains("stop proposing"));
		StagingLlmPassTest.check("the session is now stopped, with a reason", session.stoppedEarly() && session.stopReason() != null);

		final LocalToolResultCheck r4 = StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING"), null, null,
			90, "valid this time");
		StagingLlmPassTest.check("a subsequent call, even a valid one, is refused because the run is over",
			r4.isError && r4.text.toLowerCase(Locale.ROOT).contains("run is over"));
		StagingLlmPassTest.check("nothing was created: the stop happened before anything valid could be recorded", session.createdEntities().isEmpty());
	}

	/** A re-run must not duplicate a still-PROPOSED target entity, and must never resurrect a reviewer's decision. */
	private static void idempotenceAgainstExistingEntities()
	{
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("4", "Personne", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "firstName", "lastName"), null,
			null));
		prior.add(new StagingLlmPass.ExistingTargetEntity("5", "Rejected", "REJECTED", Arrays.asList("x"), null, null));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), prior);

		final LocalToolResultCheck already = StagingLlmPassTest.callEntity(session, "Personne", "x", StagingLlmPassTest.attrs("firstName,STRING"), null, null,
			90, "again");
		StagingLlmPassTest.check("re-proposing an existing PROPOSED entity is idempotent, not an error",
			!already.isError && already.text.contains("already exists for this project"));
		StagingLlmPassTest.check("nothing new recorded by propose_target_entity alone", session.createdEntities().isEmpty());

		// propose_feed still works against it (lazy adoption), with no new attribute created
		final LocalToolResultCheck fed = StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null,
			StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 90, "feed the existing entity");
		StagingLlmPassTest.check("feeding the existing entity succeeds", !fed.isError);
		StagingLlmPassTest.check("exactly one adopted entity, no attributes of its own", session.createdEntities().size() == 1
			&& "4".equals(session.createdEntities().get(0).existingId) && session.createdEntities().get(0).attributes.isEmpty());

		final LocalToolResultCheck sameName = StagingLlmPassTest.callEntity(session, "Rejected", "x", StagingLlmPassTest.attrs("x,STRING"), null, null, 90,
			"x");
		StagingLlmPassTest.check("proposing the exact name of a rejected entity is refused",
			sameName.isError && sameName.text.contains("REJECTED") && sameName.text.toLowerCase(Locale.ROOT).contains("do not propose it again"));
	}

	// -------------------------------------------------------------------------------------- re-run across separate runs

	/**
	 * The measured bug (docs/TARGET-MAPPING.md, "Relance de la passe LLM", project A 2026-09-23): a root feed
	 * already covering (entity, landing table) from an earlier run must not be recreated on a second one - the
	 * session answers an informational no-op instead, and the gap lists ({@link StagingLlmPass.Session#entitiesWithoutFeed()}/
	 * {@link StagingLlmPass.Session#tablesWithoutFeedOrNote()}) must not treat "nothing new to do" as "nothing
	 * was ever done".
	 */
	private static void rerunSkipsExistingFeed()
	{
		final List<StagingLlmPass.ExistingFeed> personneFeeds = Arrays.asList(new StagingLlmPass.ExistingFeed("101", "ERP.PERSON_FLAT", null, "PROPOSED"));
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("10", "Personne", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "firstName", "lastName"),
			null, null, personneFeeds));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), prior);

		final LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null,
			StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 90, "re-run, same feed");
		StagingLlmPassTest.check("re-proposing an existing feed is a no-op, not an error",
			!r.isError && r.text.contains("already exists from an earlier run") && r.text.contains("nothing to do"));
		StagingLlmPassTest.check("no new feed recorded", session.createdFeeds().isEmpty());

		session.finalizeEntities();
		StagingLlmPassTest.check("the adopted entity is not a gap: it already had a feed before this run",
			session.entitiesWithoutFeed().isEmpty());
		StagingLlmPassTest.check("ERP.PERSON_FLAT is not reported as unread: an earlier run already fed it",
			!session.tablesWithoutFeedOrNote().contains("ERP.PERSON_FLAT"));
	}

	/** A feed a person REJECTED must never be silently skipped like an already-covered one - it is refused outright, same posture as a rejected entity. */
	private static void rerunRefusesRejectedFeed()
	{
		final List<StagingLlmPass.ExistingFeed> feeds = Arrays.asList(new StagingLlmPass.ExistingFeed("201", "ERP.PERSON_FLAT", null, "REJECTED"));
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("11", "Personne", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "firstName"), null, null,
			feeds));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), prior);

		final LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "ERP.PERSON_FLAT", null,
			StagingLlmPassTest.rules("firstName,FIRST_NAME,"), null, 90, "re-run after a rejection");
		StagingLlmPassTest.check("a feed a person rejected is refused, not silently skipped",
			r.isError && r.text.contains("a person rejected this feed"));
		StagingLlmPassTest.check("nothing recorded", session.createdFeeds().isEmpty());
	}

	/** A brand new CHILD feed proposed this run must be able to attach to a ROOT feed an EARLIER run already created - the writer needs the prior feed's own persisted id, not a same-run key that does not exist. */
	private static void rerunChildFeedAttachesToExistingParentFeed()
	{
		final List<StagingLlmPass.ExistingFeed> personneFeeds = Arrays.asList(new StagingLlmPass.ExistingFeed("301", "ERP.PERSON_FLAT", null, "PROPOSED"));
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("12", "Personne", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "firstName"), null, null,
			personneFeeds));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), prior);

		// Adresse is brand new THIS run, declared as a child of the ADOPTED (prior-run) Personne
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse", StagingLlmPassTest.attrs("street,STRING", "personne,STRING"), "Personne", "personne", 90,
			"ok");

		final LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Adresse", "ERP.PERSON_FLAT", "Personne|ERP.PERSON_FLAT",
			StagingLlmPassTest.rules("street,STREET,"), "concat(ID)|suffix(#MAIN)", 85, "child of an earlier run's own root feed");
		StagingLlmPassTest.check("a child feed can attach to a PRIOR run's own root feed",
			!r.isError && r.text.contains("child of Personne|ERP.PERSON_FLAT"));

		final StagingLlmPass.CreatedFeed feed = StagingLlmPassTest.feedOn(session, "Adresse", "ERP.PERSON_FLAT");
		StagingLlmPassTest.check("no same-run parent key: the parent feed was never created this run", feed.parentFeedKey == null);
		StagingLlmPassTest.check("the prior feed's own persisted id is carried instead, for the writer to set PARENT_FEED with",
			"301".equals(feed.parentFeedId));
	}

	/**
	 * The other half of the measured bug: a child entity was reproposed as "Address" instead of reusing the
	 * existing "Adresse" - same seven business attributes, only the entity name (and the link attribute's own
	 * name) differing. The attribute-signature comparison catches it regardless of what either entity, or its
	 * link attribute, is called.
	 */
	private static void rerunRefusesDuplicateEntityByAttributes()
	{
		// productVariantProject() (rule entities Product/Variant only) - personAddressProject() has a
		// rule-proposed entity literally named "Address", which would trip the UNCONDITIONAL rule-entity name
		// collision refusal first and never exercise the check this test targets.
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("20", "Personne", "PROPOSED",
			Arrays.asList("sourceSystem", "sourceId", "firstName", "lastName"), null, null));
		prior.add(new StagingLlmPass.ExistingTargetEntity("21", "Adresse", "PROPOSED",
			Arrays.asList("sourceSystem", "sourceId", "street", "city", "personne"), "Personne", "personne"));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.productVariantProject(), prior);

		final LocalToolResultCheck r = StagingLlmPassTest.callEntity(session, "Address", "Address",
			StagingLlmPassTest.attrs("street,STRING", "city,STRING", "person,STRING"), "Personne", "person", 90, "Address, again");
		StagingLlmPassTest.check("a new entity with the same business attributes as an existing one is refused, naming it",
			r.isError && r.text.contains("'Address' has exactly the attributes of the existing entity 'Adresse'")
				&& r.text.contains("do not propose a synonym or a translation"));
		StagingLlmPassTest.check("nothing was created", session.createdEntities().isEmpty());
	}

	/**
	 * The normalized-name half of the same guard, isolated from the attribute-signature one: "Adresses" (a
	 * trivial plural of "Adresse") proposed with a DIFFERENT attribute set still gets caught, because the two
	 * names normalize to the same string.
	 */
	private static void rerunRefusesDuplicateEntityByNormalizedName()
	{
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("30", "Adresse", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "street"), null, null));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.productVariantProject(), prior);

		final LocalToolResultCheck r = StagingLlmPassTest.callEntity(session, "Adresses", "Adresses", StagingLlmPassTest.attrs("city,STRING"), null, null, 90,
			"Adresses, plural");
		StagingLlmPassTest.check("a new entity whose name normalizes the same as an existing one is refused, even with different attributes",
			r.isError && r.text.contains("'Adresses' has exactly the attributes of the existing entity 'Adresse'"));
	}

	/** The "TARGET ENTITIES ALREADY PROPOSED IN AN EARLIER RUN" section: present with prior entities/feeds, absent without, and the system prompt tells the model what to do with it. */
	private static void rerunPromptListsPriorEntities()
	{
		final List<StagingLlmPass.ExistingFeed> feeds = Arrays.asList(new StagingLlmPass.ExistingFeed("401", "ERP.PERSON_FLAT", null, "PROPOSED"));
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(
			new StagingLlmPass.ExistingTargetEntity("40", "Personne", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "firstName"), null, null, feeds));

		final String prompt = StagingLlmPass.userPrompt(StagingLlmPassTest.personAddressProject(), prior);
		StagingLlmPassTest.check("prior entities section header present", prompt.contains("TARGET ENTITIES ALREADY PROPOSED IN AN EARLIER RUN"));
		StagingLlmPassTest.check("prior entity named with its status and attributes",
			prompt.contains("Personne (PROPOSED), attributes: sourceSystem, sourceId, firstName"));
		StagingLlmPassTest.check("prior feed listed", prompt.contains("feed on ERP.PERSON_FLAT (PROPOSED, root)"));

		StagingLlmPassTest.check("no prior entities -> no section",
			!StagingLlmPass.userPrompt(StagingLlmPassTest.personAddressProject()).contains("ALREADY PROPOSED IN AN EARLIER RUN"));

		final String system = StagingLlmPass.systemPrompt();
		StagingLlmPassTest.check("system prompt tells the model to reuse prior names, never a synonym",
			system.contains("TARGET ENTITIES ALREADY PROPOSED IN AN EARLIER RUN")
				&& system.toLowerCase(Locale.ROOT).contains("never propose a synonym or a translation"));
	}

	private static void parseMembersPure()
	{
		StagingLlmPassTest.check("recovers a two-member list (retired pass's own shape, kept for the backfill)",
			Arrays.asList("Supplier", "Account").equals(StagingLlmPass.parseMembers("Unifies Supplier, Account: Both describe a company.")));
		StagingLlmPassTest.check("a rationale not of that shape parses empty", StagingLlmPass.parseMembers("Entered by a person.").isEmpty());
		StagingLlmPassTest.check("null parses empty", StagingLlmPass.parseMembers(null).isEmpty());
	}

	// ------------------------------------------------------------------------------------------------------ end-to-end

	private static void agentLoopEndToEnd() throws Exception
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.productVariantProject(), StagingLlmPassTest.noPriorEntities());
		final Map<String, ToolSet.LocalGroup> groups = new LinkedHashMap<String, ToolSet.LocalGroup>();
		groups.put("factory", session.toolGroup());
		final ToolSet tools = ToolSet.build(java.util.Collections.<String, ToolSet.Server>emptyMap(), groups);

		final List<JsonNode> received = new ArrayList<JsonNode>();
		final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
		server.createContext("/v1/messages", new HttpHandler()
		{
			@Override
			public void handle(final HttpExchange pExchange) throws IOException
			{
				final JsonNode body = StagingLlmPassTest.MAPPER.readTree(StagingLlmPassTest.read(pExchange.getRequestBody()));
				final int index = received.size();
				received.add(body);
				final String[] blocks = StagingLlmPassTest.scriptedTurns();
				final String block = blocks[Math.min(index, blocks.length - 1)];
				final boolean toolUse = block.contains("\"tool_use\"");
				final String answer = "{\"content\":[" + block + "],\"stop_reason\":\"" + (toolUse ? "tool_use" : "end_turn") + "\",\"usage\":{\"input_tokens\":50,\"output_tokens\":20}}";
				StagingLlmPassTest.answerJson(pExchange, answer);
			}
		});
		server.start();
		try
		{
			final LlmClient client = new LlmClient(LlmClient.Format.ANTHROPIC, "http://127.0.0.1:" + server.getAddress().getPort() + "/v1/messages", null,
				"claude-sonnet-5", 1024, 2000, 5000);
			final AgentLoop loop = new AgentLoop(client, tools, new AgentLoop.Budget(20, 1000000, 60000L), null);
			final AgentLoop.Result result = loop.run(StagingLlmPass.systemPrompt(), StagingLlmPass.userPrompt(StagingLlmPassTest.productVariantProject()));

			StagingLlmPassTest.check("the run completes DONE", result.status == AgentLoop.Status.DONE);
			StagingLlmPassTest.check("the model's final text was captured", result.text != null && result.text.contains("Produit"));
			StagingLlmPassTest.check("one refused/erroring call happened and was corrected (unknown column), no allow-list refusal", result.refusedToolCalls == 0);

			session.finalizeEntities();
			StagingLlmPassTest.check("two entities created", session.createdEntities().size() == 2);
			StagingLlmPassTest.check("two feeds created", session.createdFeeds().size() == 2);
			StagingLlmPassTest.check("no gaps", session.entitiesWithoutFeed().isEmpty() && session.tablesWithoutFeedOrNote().isEmpty());

			final String firstUserMessage = received.get(0).path("messages").path(0).path("content").asText();
			// lot S10: counts (rows) are part of the inventory now; what must never appear is a value
			StagingLlmPassTest.check("the user prompt sent to the provider carries the inventory as counts", firstUserMessage.toLowerCase(Locale.ROOT).contains("100 rows"));
		}
		finally
		{
			server.stop(0);
		}
	}

	/**
	 * Produit + Variante, fed from PRODUCT/VARIANT, with one corrected mistake (an unknown column on the
	 * Variante feed) and a final text answer.
	 */
	private static String[] scriptedTurns()
	{
		return new String[] {
			StagingLlmPassTest.toolUse("c1", "factory__propose_target_entity",
				"{\"name\":\"Produit\",\"label\":\"Produit\",\"attributes\":[{\"name\":\"name\",\"label\":\"Name\",\"dataType\":\"STRING\"},"
					+ "{\"name\":\"price\",\"label\":\"Price\",\"dataType\":\"DECIMAL\"}],\"confidence\":90,\"rationale\":\"The product itself.\"}"),
			StagingLlmPassTest.toolUse("c2", "factory__propose_target_entity",
				"{\"name\":\"Variante\",\"label\":\"Variante\",\"attributes\":[{\"name\":\"sku\",\"label\":\"SKU\",\"dataType\":\"STRING\"},"
					+ "{\"name\":\"color\",\"label\":\"Color\",\"dataType\":\"STRING\"},{\"name\":\"produit\",\"label\":\"Produit\",\"dataType\":\"STRING\"}],"
					+ "\"parentEntity\":\"Produit\",\"parentLinkAttribute\":\"produit\",\"confidence\":90,\"rationale\":\"A sellable variant.\"}"),
			StagingLlmPassTest.toolUse("c3", "factory__propose_feed",
				"{\"entity\":\"Produit\",\"landingTable\":\"ERP.PRODUCT\",\"rules\":[{\"attribute\":\"name\",\"landingColumn\":\"NAME\"},"
					+ "{\"attribute\":\"price\",\"landingColumn\":\"PRICE\"}],\"confidence\":90,\"rationale\":\"Product feed.\"}"),
			// unknown column: refused, the model must correct it on the next call
			StagingLlmPassTest.toolUse("c4", "factory__propose_feed",
				"{\"entity\":\"Variante\",\"landingTable\":\"ERP.VARIANT\",\"rules\":[{\"attribute\":\"sku\",\"landingColumn\":\"SKU_CODE\"}],"
					+ "\"confidence\":90,\"rationale\":\"Variant feed.\"}"),
			StagingLlmPassTest.toolUse("c5", "factory__propose_feed",
				"{\"entity\":\"Variante\",\"landingTable\":\"ERP.VARIANT\",\"rules\":[{\"attribute\":\"sku\",\"landingColumn\":\"SKU\"},"
					+ "{\"attribute\":\"color\",\"landingColumn\":\"COLOR\"},{\"attribute\":\"produit\",\"landingColumn\":\"PRODUCT_ID\"}],"
					+ "\"confidence\":90,\"rationale\":\"Variant has its own table with a real productId column.\"}"),
			StagingLlmPassTest.text("Proposed Produit and Variante (child of Produit), fed from PRODUCT and VARIANT.") };
	}

	// --------------------------------------------------------------------------------------------------- call helpers

	private static final class LocalToolResultCheck
	{
		final String text;
		final boolean isError;

		LocalToolResultCheck(final String pText, final boolean pIsError)
		{
			this.text = pText;
			this.isError = pIsError;
		}
	}

	/** {@code "name,dataType"} pairs -> the JSON array {@code propose_target_entity} expects for {@code attributes}. */
	private static ArrayNode attrs(final String... pSpecs)
	{
		final ArrayNode out = StagingLlmPassTest.MAPPER.createArrayNode();
		for (final String spec : pSpecs)
		{
			final String[] parts = spec.split(",", -1);
			final ObjectNode attribute = out.addObject();
			attribute.put("name", parts[0]);
			attribute.put("label", parts[0]);
			attribute.put("dataType", parts[1]);
		}
		return out;
	}

	/** {@code "attribute,landingColumn,pipeline"} triples (empty pipeline = omitted) -> the JSON array {@code propose_feed} expects for {@code rules}. */
	private static ArrayNode rules(final String... pSpecs)
	{
		final ArrayNode out = StagingLlmPassTest.MAPPER.createArrayNode();
		for (final String spec : pSpecs)
		{
			final String[] parts = spec.split(",", -1);
			final ObjectNode rule = out.addObject();
			rule.put("attribute", parts[0]);
			rule.put("landingColumn", parts[1]);
			if (parts.length > 2 && !parts[2].isEmpty())
			{
				rule.put("pipeline", parts[2]);
			}
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------ lot M8b: CHILD_KEYS

	private static LocalToolResultCheck callChildKeys(final StagingLlmPass.Session pSession, final String pEntity, final String pName, final String pChildEntity,
		final String[] pFields, final String pRationale)
	{
		final ObjectNode args = StagingLlmPassTest.MAPPER.createObjectNode();
		if (pEntity != null)
		{
			args.put("entity", pEntity);
		}
		if (pName != null)
		{
			args.put("name", pName);
		}
		if (pChildEntity != null)
		{
			args.put("childEntity", pChildEntity);
		}
		final ArrayNode fields = args.putArray("fields");
		for (final String field : pFields)
		{
			fields.add(field);
		}
		args.put("confidence", 80);
		if (pRationale != null)
		{
			args.put("rationale", pRationale);
		}
		final LocalTool.Result r = pSession.proposeChildKeys(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	/** Personne + its child Adresse (link attribute personne), as two target entities of one run. */
	private static StagingLlmPass.Session sessionWithPersonneAndAdresse()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "Person.");
		StagingLlmPassTest.callEntity(session, "Adresse", "Adresse",
			StagingLlmPassTest.attrs("street,STRING", "city,STRING", "zip,STRING", "addressType,STRING", "personne,STRING"), "Personne", "personne", 90, "Address.");
		return session;
	}

	private static void childKeysProposal()
	{
		StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "Person.");
		LocalToolResultCheck r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "zip", "street" }, "x");
		StagingLlmPassTest.check("a child entity not proposed yet is refused, naming what to do first: " + r.text, r.isError && r.text.contains("propose it first"));

		session = StagingLlmPassTest.sessionWithPersonneAndAdresse();
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "zip", "street" }, "Persons sharing an address are duplicates.");
		StagingLlmPassTest.check("a valid proposal is recorded: " + r.text, !r.isError && r.text.contains("addressKeys") && r.text.contains("through 'personne'"));
		StagingLlmPassTest.check("exactly one key attribute", session.createdChildKeys().size() == 1);
		final StagingLlmPass.CreatedChildKeys keys = session.createdChildKeys().get(0);
		StagingLlmPassTest.check("parent, name, child, ordered fields", "Personne".equals(keys.parentEntity) && "addressKeys".equals(keys.name) && "Adresse".equals(keys.childEntity)
			&& keys.fields.equals(Arrays.asList("zip", "street")));
		StagingLlmPassTest.check("the argument is the definition, default encoding (none written), and parses", "{\"child\":\"Adresse\",\"fields\":[\"zip\",\"street\"]}".equals(keys.argument)
			&& com.on.ebx.factory.quality.ChildKeysDerivation.parse(keys.argument).encoding.equals(com.on.ebx.factory.quality.ChildKeysDerivation.ENCODING_HASH));
		StagingLlmPassTest.check("label defaults to the name, text length is the documented one", "addressKeys".equals(keys.label) && keys.maxLength() == 2000);
		StagingLlmPassTest.check("the summary of the run names it", session.summaryOfThisRun().contains("key attribute Personne.addressKeys"));

		r = StagingLlmPassTest.callChildKeys(session, "Personne", "otherKeys", "Adresse", new String[] { "city" }, "again");
		StagingLlmPassTest.check("one key attribute per parent and child pair: " + r.text, r.isError && r.text.contains("one per parent and child pair"));
		StagingLlmPassTest.check("still one", session.createdChildKeys().size() == 1);

		// refusals (a fresh session each: three identical refusals would end the run)
		session = StagingLlmPassTest.sessionWithPersonneAndAdresse();
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "zip", "nope" }, "x");
		StagingLlmPassTest.check("a field that is not an attribute of the child is refused with the real ones: " + r.text,
			r.isError && r.text.contains("'nope' is not an attribute of Adresse") && r.text.contains("street"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "personne" }, "x");
		StagingLlmPassTest.check("the link to the parent is no key part: " + r.text, r.isError && r.text.contains("reference to another entity"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "firstName", "Adresse", new String[] { "zip" }, "x");
		StagingLlmPassTest.check("a name already taken on the parent: " + r.text, r.isError && r.text.contains("already has an attribute named 'firstName'"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "9bad", "Adresse", new String[] { "zip" }, "x");
		StagingLlmPassTest.check("a bad attribute name", r.isError && r.text.contains("'name' must start with a letter"));
		r = StagingLlmPassTest.callChildKeys(session, "Nobody", "addressKeys", "Adresse", new String[] { "zip" }, "x");
		StagingLlmPassTest.check("an unknown parent: " + r.text, r.isError && r.text.contains("unknown entity 'Nobody'"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Personne", new String[] { "firstName" }, "x");
		StagingLlmPassTest.check("an entity is not its own child", r.isError && r.text.contains("not the entity that carries the attribute"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] {}, "x");
		StagingLlmPassTest.check("no field", r.isError && r.text.contains("'fields' must list at least one"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "zip", "zip" }, "x");
		StagingLlmPassTest.check("a field twice (ChildKeysDerivation.parse): " + r.text, r.isError && r.text.contains("twice"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "zip" }, null);
		StagingLlmPassTest.check("no rationale", r.isError && r.text.contains("'rationale' must not be empty"));
		StagingLlmPassTest.check("none of the refused calls recorded anything", session.createdChildKeys().isEmpty());

		// an entity that is not a child of the parent
		StagingLlmPassTest.callEntity(session, "Produit", "Produit", StagingLlmPassTest.attrs("name,STRING"), null, null, 80, "Product.");
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "productKeys", "Produit", new String[] { "name" }, "x");
		StagingLlmPassTest.check("a child that does not refer to the parent: " + r.text, r.isError && r.text.contains("has no attribute that refers to Personne"));
	}

	private static void childKeysProposalAgainstPriorEntities()
	{
		final List<StagingLlmPass.ExistingTargetEntity> prior = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		prior.add(new StagingLlmPass.ExistingTargetEntity("10", "Personne", "ACCEPTED", Arrays.asList("sourceSystem", "sourceId", "firstName", "lastName"), null, null));
		prior.add(new StagingLlmPass.ExistingTargetEntity("11", "Adresse", "ACCEPTED", Arrays.asList("sourceSystem", "sourceId", "street", "zip", "personne"), "Personne",
			"personne"));
		prior.add(new StagingLlmPass.ExistingTargetEntity("12", "Contact", "REJECTED", Arrays.asList("sourceSystem", "sourceId", "phone", "personne"), "Personne",
			"personne"));
		StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), prior);
		LocalToolResultCheck r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "zip", "street" }, "x");
		StagingLlmPassTest.check("a key attribute on an entity of an earlier run, whatever its status (not rejected): " + r.text, !r.isError && session.createdChildKeys().size() == 1);
		StagingLlmPassTest.check("it adopts no entity (the writer finds the parent by name)", session.createdEntities().isEmpty());

		session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), prior);
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys", "Adresse", new String[] { "sourceId" }, "x");
		StagingLlmPassTest.check("a technical identity attribute is no key part: " + r.text, r.isError && r.text.contains("technical identity attribute"));
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "contactKeys", "Contact", new String[] { "phone" }, "x");
		StagingLlmPassTest.check("a rejected child does not exist for this purpose", r.isError && r.text.contains("does not exist in this project"));

		final List<StagingLlmPass.ExistingTargetEntity> withKeys = new ArrayList<StagingLlmPass.ExistingTargetEntity>();
		withKeys.add(new StagingLlmPass.ExistingTargetEntity("10", "Personne", "PROPOSED", Arrays.asList("sourceSystem", "sourceId", "firstName", "addressKeys"), null, null,
			null, Arrays.asList("Adresse")));
		withKeys.add(prior.get(1));
		session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), withKeys);
		r = StagingLlmPassTest.callChildKeys(session, "Personne", "addressKeys2", "Adresse", new String[] { "zip" }, "x");
		StagingLlmPassTest.check("a pair an earlier run already covers is informational, nothing recorded: " + r.text,
			!r.isError && r.text.contains("already has a key attribute") && session.createdChildKeys().isEmpty());
	}

	private static void childKeysIsDescribedToTheModel()
	{
		final String system = StagingLlmPass.systemPrompt();
		StagingLlmPassTest.check("the tool is listed", system.contains("- propose_child_keys:"));
		StagingLlmPassTest.check("what it is for", system.contains("two parent records together because they share a child record"));
		StagingLlmPassTest.check("when to propose it", system.contains("Propose it when a child entity carries identifying data of its parent"));
		StagingLlmPassTest.check("which fields make a key", system.contains("the ones that identify the child record, most selective first"));
		StagingLlmPassTest.check("it is a technical key, never an identifier nor a business attribute",
			system.contains("technical matching key: never an identifier of the parent, never a business attribute"));
		StagingLlmPassTest.check("the measured gain", system.contains("90.8 % to 99.15 % at a precision of 99.84 %"));
		StagingLlmPassTest.check("the ordering rule of cleaning steps and pipelines", system.contains("run on the raw text BEFORE the mapping's own pipeline")
			&& system.contains("regex(...)|upper, never upper|regex(...)"));
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		boolean listed = false;
		for (final LocalTool tool : session.toolGroup().tools)
		{
			listed |= "propose_child_keys".equals(tool.name);
		}
		StagingLlmPassTest.check("the tool is in the tool group", listed);
	}

	private static void caseChangeAheadOfACaseReadingRegexIsRefusedInAFeed()
	{
		final StagingLlmPass.Session session = new StagingLlmPass.Session(StagingLlmPassTest.personAddressProject(), StagingLlmPassTest.noPriorEntities());
		StagingLlmPassTest.callEntity(session, "Personne", "Personne", StagingLlmPassTest.attrs("firstName,STRING", "lastName,STRING"), null, null, 90, "Person.");
		LocalToolResultCheck r = StagingLlmPassTest.callFeed(session, "Personne", "CRM.PERSON", null,
			StagingLlmPassTest.rules("lastName,LAST_NAME,upper|regex(^(?:[\\p{Lu}'-]+\\s+)?([\\p{L}'-]*\\p{Ll}[\\p{L}'-]*))"), null, 80, "Last name.");
		StagingLlmPassTest.check("upper in front of a regex reading case is refused with the fix: " + r.text, r.isError && r.text.contains("regex(...)|upper"));
		StagingLlmPassTest.check("no feed recorded", session.createdFeeds().isEmpty());
		r = StagingLlmPassTest.callFeed(session, "Personne", "CRM.PERSON", null,
			StagingLlmPassTest.rules("lastName,LAST_NAME,regex(^(?:[\\p{Lu}'-]+\\s+)?([\\p{L}'-]*\\p{Ll}[\\p{L}'-]*))|upper"), null, 80, "Last name.");
		StagingLlmPassTest.check("the case change at the end is accepted: " + r.text, !r.isError && session.createdFeeds().size() == 1);
	}

	/** 2026-09-26 (docs/TARGET-MAPPING.md sec 14): which attribute names of a child read as a second reference to its parent. */
	private static void duplicatesParentLinkPure()
	{
		StagingLlmPassTest.check("the link attribute itself is never a duplicate", !StagingLlmPass.duplicatesParentLink("personne", "Personne", "personne"));
		StagingLlmPassTest.check("personneId duplicates the link", StagingLlmPass.duplicatesParentLink("personneId", "Personne", "personne"));
		StagingLlmPassTest.check("personne_ref duplicates the link", StagingLlmPass.duplicatesParentLink("personne_ref", "Personne", "personne"));
		StagingLlmPassTest.check("named after the parent while the link is named otherwise", StagingLlmPass.duplicatesParentLink("personne", "Personne", "owner"));
		StagingLlmPassTest.check("ownerKey duplicates a link named owner", StagingLlmPass.duplicatesParentLink("ownerKey", "Personne", "owner"));
		StagingLlmPassTest.check("an ordinary attribute is not a duplicate", !StagingLlmPass.duplicatesParentLink("street", "Personne", "personne"));
		StagingLlmPassTest.check("a longer business name merely starting with the parent's is not a duplicate",
			!StagingLlmPass.duplicatesParentLink("personneType", "Personne", "personne"));
	}

	private static LocalToolResultCheck callEntity(final StagingLlmPass.Session pSession, final String pName, final String pLabel, final ArrayNode pAttributes,
		final String pParentEntity, final String pParentLinkAttribute, final int pConfidence, final String pRationale)
	{
		final ObjectNode args = StagingLlmPassTest.MAPPER.createObjectNode();
		args.put("name", pName);
		args.put("label", pLabel);
		args.set("attributes", pAttributes);
		if (pParentEntity != null)
		{
			args.put("parentEntity", pParentEntity);
		}
		if (pParentLinkAttribute != null)
		{
			args.put("parentLinkAttribute", pParentLinkAttribute);
		}
		args.put("confidence", pConfidence);
		args.put("rationale", pRationale);
		final LocalTool.Result r = pSession.proposeTargetEntity(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	private static LocalToolResultCheck callFeed(final StagingLlmPass.Session pSession, final String pEntity, final String pLandingTable,
		final String pParentFeed, final ArrayNode pRules, final String pSourceIdPipeline, final int pConfidence, final String pRationale)
	{
		final ObjectNode args = StagingLlmPassTest.MAPPER.createObjectNode();
		args.put("entity", pEntity);
		args.put("landingTable", pLandingTable);
		if (pParentFeed != null)
		{
			args.put("parentFeed", pParentFeed);
		}
		args.set("rules", pRules);
		if (pSourceIdPipeline != null)
		{
			args.put("sourceIdPipeline", pSourceIdPipeline);
		}
		args.put("confidence", pConfidence);
		args.put("rationale", pRationale);
		final LocalTool.Result r = pSession.proposeFeed(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	private static LocalToolResultCheck callDescribe(final StagingLlmPass.Session pSession, final String pTable)
	{
		final ObjectNode args = StagingLlmPassTest.MAPPER.createObjectNode();
		args.put("table", pTable);
		final LocalTool.Result r = pSession.describeLandingTable(args);
		return new LocalToolResultCheck(r.text, r.isError);
	}

	private static StagingLlmPass.CreatedEntity entity(final StagingLlmPass.Session pSession, final String pName)
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

	private static StagingLlmPass.CreatedFeed feedOn(final StagingLlmPass.Session pSession, final String pEntity, final String pLandingTableKey)
	{
		for (final StagingLlmPass.CreatedFeed feed : pSession.createdFeeds())
		{
			if (feed.entityName.equals(pEntity) && feed.landingTableKey.equals(pLandingTableKey))
			{
				return feed;
			}
		}
		throw new AssertionError("no feed for " + pEntity + " on " + pLandingTableKey);
	}

	private static StagingLlmPass.CreatedFeedRule ruleOn(final StagingLlmPass.CreatedFeed pFeed, final String pAttribute)
	{
		for (final StagingLlmPass.CreatedFeedRule rule : pFeed.rules)
		{
			if (rule.attribute.equals(pAttribute))
			{
				return rule;
			}
		}
		throw new AssertionError("no rule for " + pAttribute + " on feed " + pFeed.feedKey);
	}

	private static boolean hasRule(final StagingLlmPass.CreatedFeed pFeed, final String pAttribute)
	{
		for (final StagingLlmPass.CreatedFeedRule rule : pFeed.rules)
		{
			if (rule.attribute.equals(pAttribute))
			{
				return true;
			}
		}
		return false;
	}

	// ------------------------------------------------------------------------------------------------------ plumbing

	private static String toolUse(final String pId, final String pName, final String pInputJson)
	{
		return "{\"type\":\"tool_use\",\"id\":\"" + pId + "\",\"name\":\"" + pName + "\",\"input\":" + pInputJson + "}";
	}

	private static String text(final String pText)
	{
		return "{\"type\":\"text\",\"text\":\"" + pText + "\"}";
	}

	private static void answerJson(final HttpExchange pExchange, final String pBody) throws IOException
	{
		final byte[] bytes = pBody.getBytes(StandardCharsets.UTF_8);
		pExchange.getResponseHeaders().add("Content-Type", "application/json");
		pExchange.sendResponseHeaders(200, bytes.length);
		pExchange.getResponseBody().write(bytes);
		pExchange.close();
	}

	private static String read(final InputStream pStream) throws IOException
	{
		final ByteArrayOutputStream out = new ByteArrayOutputStream();
		final byte[] buffer = new byte[4096];
		for (int n = pStream.read(buffer); n >= 0; n = pStream.read(buffer))
		{
			out.write(buffer, 0, n);
		}
		return new String(out.toByteArray(), StandardCharsets.UTF_8);
	}

	/** Fix/staging-proposal-quality (2026-10-17, items 2 and 3): the facts-carried-once rules, and the language of labels and rationales. */
	private static void promptStatesTheModellingRulesAndTheLanguage()
	{
		final String system = StagingLlmPass.systemPrompt();
		StagingLlmPassTest.check("a fact is carried once: no duplicate on the parent, no address field on a parent with an address child",
			system.contains("(8) A fact is carried ONCE") && system.contains("the parent entity does NOT get the same fields from the same columns")
				&& system.contains("the parent keeps none of those fields"));
		StagingLlmPassTest.check("an attribute no source feeds is not declared", system.contains("(9) Every attribute you declare is fed by at least one rule"));
		StagingLlmPassTest.check("a source that is not one row per record says so in the feed rationale",
			system.contains("(10) Set businessKey only") && system.contains("say so in the rationale of that feed") && system.contains("duplicates are merged at matching"));
		StagingLlmPassTest.check("English and no locale add nothing", StagingLlmPass.systemPrompt(Locale.ENGLISH).equals(system) && StagingLlmPass.systemPrompt(null).equals(system)
			&& StagingLlmPass.systemPrompt(Locale.US).equals(system));
		final String french = StagingLlmPass.systemPrompt(Locale.FRENCH);
		StagingLlmPassTest.check("French asks for labels and rationales in French", french.startsWith(system) && french.contains("LANGUAGE: the person reviewing your proposals works in French (fr)")
			&& french.contains("Write the 'label' of every entity and attribute, every rationale, every note and your final summary in French"));
		StagingLlmPassTest.check("technical names stay English identifiers", french.contains("entity names (PascalCase), attribute names (camelCase), variants, pipelines")
			&& french.contains("never translate those"));
		StagingLlmPassTest.check("the region does not change the language", StagingLlmPass.systemPrompt(Locale.CANADA_FRENCH).contains("works in French (fr-CA)"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
