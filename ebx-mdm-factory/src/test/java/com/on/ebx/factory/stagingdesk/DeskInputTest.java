package com.on.ebx.factory.stagingdesk;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.on.ebx.factory.capability.MissingInputs;

/**
 * Validation desk lot L5 (docs/STAGING-DESK.md): the cards of "an input no field holds" - how the waiting needs and the proposed computed fields become cards
 * ({@link DeskInput#cards}), and what {@link DeskInputHtml} prints and posts, alone and in the "Cannot run as proposed" centre. JUnit-free: run the main, a failure throws.
 */
public final class DeskInputTest
{
	private static final String SENTENCE = "the complete address as one value; no accepted attribute stores an assembled address";
	private static final String BINDING = "{\"number\":\"houseNumber\",\"street\":\"streetName\",\"zipcode\":\"postalCode\",\"city\":\"city\"}";

	private DeskInputTest()
	{
	}

	public static void main(final String[] pArgs)
	{
		DeskInputTest.sameNeedIsOneCardAndTheFieldAttachesToIt();
		DeskInputTest.aFieldMergesTheCardsOfDifferentWords();
		DeskInputTest.differentSentencesOfOneObjectAreOneCardWithOneButton();
		DeskInputTest.onlyWaitingSuggestionsMakeACard();
		DeskInputTest.aCardWithoutFieldOffersToProposeOne();
		DeskInputTest.aRunningJobReplacesTheButton();
		DeskInputTest.aProposedFieldIsShownWithItsScriptAndDecided();
		DeskInputTest.aDecidedFieldHasNoButtons();
		DeskInputTest.readOnlyDisablesAndTextIsEscaped();
		DeskInputTest.theCardsSitAboveTheListOfTheCannotRunCentre();
		DeskInputTest.bothLanguagesHaveTheTexts();
		System.out.println("DeskInputTest OK");
	}

	private static MissingInputs.Need need(final String pId, final String pNature, final String pLabel, final String pInput, final String pSentence)
	{
		return new MissingInputs.Need(pId, pNature, "Adresse", pLabel, pInput, pSentence);
	}

	private static DeskInput four()
	{
		final DeskInput input = new DeskInput();
		input.needs.add(DeskInputTest.need("g5", "EXTERNAL_SERVICE", "BAN", "q", DeskInputTest.SENTENCE));
		input.needs.add(DeskInputTest.need("g6", "SCRIPT_CONSTRAINT", "address.geocode", "address", DeskInputTest.SENTENCE));
		input.needs.add(DeskInputTest.need("g7", "EXTERNAL_SERVICE", "GoogleGeocoding", "address", DeskInputTest.SENTENCE));
		input.needs.add(DeskInputTest.need("g8", "EXTERNAL_SERVICE", "Nominatim", "q", DeskInputTest.SENTENCE));
		return input;
	}

	private static DeskInput.Field field(final String pStatus, final String... pSuppliedIds)
	{
		final java.util.List<MissingInputs.Supply> supplies = new java.util.ArrayList<MissingInputs.Supply>();
		for (final String id : pSuppliedIds)
		{
			final String[] parts = id.split(":");
			supplies.add(new MissingInputs.Supply(parts[0], parts[1]));
		}
		return new DeskInput.Field("a9", "fullAddress", "Full address", "STRING", pStatus, "s9", "Adresse", "library/Fasttrack/lib/address", "buildFullAddress", DeskInputTest.BINDING,
			"Assembles the postal address on one line.", Integer.valueOf(80), supplies);
	}

	private static Set<String> ids(final String... pIds)
	{
		return new HashSet<String>(Arrays.asList(pIds));
	}

	private static void sameNeedIsOneCardAndTheFieldAttachesToIt()
	{
		final DeskInput input = DeskInputTest.four();
		input.fields.add(DeskInputTest.field("PROPOSED", "g5:q", "g6:address", "g7:address", "g8:q"));
		final List<DeskInput.Card> cards = input.cards(DeskInputTest.ids("g5", "g6", "g7", "g8"));
		DeskInputTest.check("one card for the four", cards.size() == 1 && cards.get(0).suggestions() == 4);
		DeskInputTest.check("the sentence of the model", DeskInputTest.SENTENCE.equals(cards.get(0).sentence));
		DeskInputTest.check("the names to show, in order", cards.get(0).labels().equals(Arrays.asList("BAN", "address.geocode", "GoogleGeocoding", "Nominatim")));
		DeskInputTest.check("the field is on it and binds four", cards.get(0).fields.size() == 1 && cards.get(0).fields.get(0).suppliedAmong(cards.get(0).needs) == 4);
	}

	private static void aFieldMergesTheCardsOfDifferentWords()
	{
		final DeskInput input = new DeskInput();
		input.needs.add(DeskInputTest.need("g5", "EXTERNAL_SERVICE", "BAN", "q", "the complete address as one value"));
		input.needs.add(DeskInputTest.need("g7", "EXTERNAL_SERVICE", "Google", "address", "the full postal address, one line"));
		input.needs.add(DeskInputTest.need("g9", "EXTERNAL_SERVICE", "Other", "code", "a country code"));
		DeskInputTest.check("without a field, the needs of one business object are ONE card", input.cards(DeskInputTest.ids("g5", "g7", "g9")).size() == 1);
		input.fields.add(DeskInputTest.field("PROPOSED", "g5:q", "g7:address"));
		final List<DeskInput.Card> cards = input.cards(DeskInputTest.ids("g5", "g7", "g9"));
		DeskInputTest.check("the model listed g5 and g7 under one field: one card for them, none merged by a similarity test: " + cards.size(), cards.size() == 2);
		DeskInputTest.check("first card: both, with the field", cards.get(0).suggestions() == 2 && cards.get(0).fields.size() == 1);
		DeskInputTest.check("second card: the country code, no field", cards.get(1).suggestions() == 1 && cards.get(1).fields.isEmpty());
	}

	/** The live DemoBala case: four suggestions, four different sentences, one business object, no field yet. */
	private static DeskInput demoBala()
	{
		final DeskInput input = new DeskInput();
		input.needs.add(DeskInputTest.need("g5", "EXTERNAL_SERVICE", "BAN", "q", "BAN requires the full address as one input"));
		input.needs.add(DeskInputTest.need("g6", "SCRIPT_CONSTRAINT", "address.geocode", "address", "The geocoding function needs the complete address as one value"));
		input.needs.add(DeskInputTest.need("g7", "EXTERNAL_SERVICE", "GoogleGeocoding", "address", "Google requires the full address"));
		input.needs.add(DeskInputTest.need("g8", "EXTERNAL_SERVICE", "Nominatim", "q", "Nominatim requires a free-text address query"));
		return input;
	}

	private static void differentSentencesOfOneObjectAreOneCardWithOneButton()
	{
		final DeskInput input = DeskInputTest.demoBala();
		final Set<String> all = DeskInputTest.ids("g5", "g6", "g7", "g8");
		final List<DeskInput.Card> cards = input.cards(all);
		DeskInputTest.check("one card for the four: " + cards.size(), cards.size() == 1 && cards.get(0).suggestions() == 4);
		final StringBuilder out = new StringBuilder();
		DeskInputHtml.render(out, input, all, false, Locale.ENGLISH);
		final String html = out.toString();
		DeskInputTest.check("title: " + html, html.contains("4 wait for an input no field holds"));
		DeskInputTest.check("each suggestion with its own sentence", html.contains("<b>BAN</b> - BAN requires the full address as one input")
			&& html.contains("<b>Nominatim</b> - Nominatim requires a free-text address query") && html.contains("<b>address.geocode</b>") && html.contains("<b>GoogleGeocoding</b>"));
		DeskInputTest.check("one button", html.split("PROPOSE_INPUT_FIELD", -1).length == 2);
		DeskInputTest.check("French title", DeskInputTest.renderAll(input, Locale.FRENCH).contains("4 en attente d\u2019une entr\u00E9e qu\u2019aucun champ ne porte"));
		// a field for two of them: its card, and ONE remaining card for the others
		input.fields.add(DeskInputTest.field("PROPOSED", "g5:q", "g7:address"));
		final List<DeskInput.Card> after = input.cards(all);
		DeskInputTest.check("field card + one remaining card: " + after.size(), after.size() == 2 && after.get(0).suggestions() == 2 && after.get(0).fields.size() == 1
			&& after.get(1).suggestions() == 2 && after.get(1).fields.isEmpty());
		// another business object stays apart
		input.needs.add(new MissingInputs.Need("g9", "EXTERNAL_SERVICE", "Personne", "Other", "code", "a country code"));
		DeskInputTest.check("another object, another card", input.cards(DeskInputTest.ids("g5", "g6", "g7", "g8", "g9")).size() == 3);
	}

	private static String renderAll(final DeskInput pInput, final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder();
		DeskInputHtml.render(out, pInput, DeskInputTest.ids("g5", "g6", "g7", "g8"), false, pLocale);
		return out.toString();
	}

	private static void onlyWaitingSuggestionsMakeACard()
	{
		final DeskInput input = DeskInputTest.four();
		DeskInputTest.check("g6 and g8 are not in the cannot-run group any more (set aside, bound): two left",
			input.cards(DeskInputTest.ids("g5", "g7")).get(0).suggestions() == 2);
		DeskInputTest.check("none waiting, no card", input.cards(Collections.<String>emptySet()).isEmpty());
		final DeskInput other = new DeskInput();
		other.fields.add(DeskInputTest.field("PROPOSED", "g5:q"));
		DeskInputTest.check("a field with nothing waiting makes no card", other.cards(DeskInputTest.ids("g5")).isEmpty());
		final StringBuilder out = new StringBuilder();
		DeskInputHtml.render(out, null, DeskInputTest.ids("g5"), false, Locale.ENGLISH);
		DeskInputHtml.render(out, new DeskInput(), DeskInputTest.ids("g5"), false, Locale.ENGLISH);
		DeskInputTest.check("no input, no card", out.length() == 0);
	}

	private static String render(final DeskInput pInput, final boolean pReadOnly, final Locale pLocale)
	{
		final StringBuilder out = new StringBuilder();
		DeskInputHtml.render(out, pInput, DeskInputTest.ids("g5", "g6", "g7", "g8"), pReadOnly, pLocale);
		return out.toString();
	}

	private static void aCardWithoutFieldOffersToProposeOne()
	{
		final String html = DeskInputTest.render(DeskInputTest.four(), false, Locale.ENGLISH);
		DeskInputTest.check("4 waiting for the sentence: " + html, html.contains("4 waiting for: " + DeskInputTest.SENTENCE));
		DeskInputTest.check("the names of the suggestions", html.contains(">BAN<") && html.contains(">address.geocode<") && html.contains(">GoogleGeocoding<") && html.contains(">Nominatim<"));
		DeskInputTest.check("the button posts the desk's own action, no id", html.contains("onclick=\"mdskDo('PROPOSE_INPUT_FIELD','','',false)\"") && html.contains("Propose a field for it"));
		DeskInputTest.check("no accept yet", !html.contains("ACCEPT_ATTRIBUTE"));
	}

	private static void aRunningJobReplacesTheButton()
	{
		final DeskInput input = DeskInputTest.four();
		input.jobRunning = true;
		final String html = DeskInputTest.render(input, false, Locale.ENGLISH);
		DeskInputTest.check("a proposal is being prepared, no second button", html.contains("being prepared") && !html.contains("PROPOSE_INPUT_FIELD"));
	}

	private static void aProposedFieldIsShownWithItsScriptAndDecided()
	{
		final DeskInput input = DeskInputTest.four();
		input.fields.add(DeskInputTest.field("PROPOSED", "g5:q", "g6:address", "g7:address", "g8:q"));
		final String html = DeskInputTest.render(input, false, Locale.ENGLISH);
		DeskInputTest.check("the name, with the business object, as a computed field", html.contains("<code>Adresse.fullAddress</code>") && html.contains("Computed field"));
		DeskInputTest.check("the call: the function and the fields that feed it", html.contains("buildFullAddress(houseNumber, streetName, postalCode, city)"));
		DeskInputTest.check("the arguments in the matrix' words", html.contains("zipcode → field postalCode"));
		DeskInputTest.check("the function's text and the model's rationale", html.contains("Assembles a one-line postal address from its parts, skipping the blank ones.")
			&& html.contains("Assembles the postal address on one line."));
		DeskInputTest.check("Accept and Reject are the attribute decisions", html.contains("mdskDo('ACCEPT_ATTRIBUTE','a9','',false)") && html.contains("mdskDo('REJECT_ATTRIBUTE','a9','',false)"));
		DeskInputTest.check("the note says what accepting binds", html.contains("Accepting binds the 4 waiting suggestions."));
		DeskInputTest.check("no propose button next to a proposed field", !html.contains("PROPOSE_INPUT_FIELD"));
		final String one = DeskInputTest.render(DeskInputTest.withField("PROPOSED", "g5:q"), false, Locale.ENGLISH);
		DeskInputTest.check("a field supplying one of four binds one", one.contains("Accepting binds the 1 waiting suggestion."));
	}

	private static DeskInput withField(final String pStatus, final String... pSupplied)
	{
		final DeskInput input = DeskInputTest.four();
		input.fields.add(DeskInputTest.field(pStatus, pSupplied));
		return input;
	}

	private static void aDecidedFieldHasNoButtons()
	{
		final String html = DeskInputTest.render(DeskInputTest.withField("ACCEPTED", "g5:q"), false, Locale.ENGLISH);
		DeskInputTest.check("shown, with no decision to take: " + html, html.contains("Adresse.fullAddress") && html.contains("already decided") && !html.contains("ACCEPT_ATTRIBUTE")
			&& html.split("PROPOSE_INPUT_FIELD", -1).length == 2);
	}

	private static void readOnlyDisablesAndTextIsEscaped()
	{
		final DeskInput input = new DeskInput();
		input.needs.add(DeskInputTest.need("g5", "EXTERNAL_SERVICE", "<b>BAN</b>", "q", "the <script>alert(1)</script> value"));
		final StringBuilder out = new StringBuilder();
		DeskInputHtml.render(out, input, DeskInputTest.ids("g5"), true, Locale.ENGLISH);
		final String html = out.toString();
		DeskInputTest.check("no raw markup from the model or the connector", !html.contains("<script>") && !html.contains("<b>BAN") && html.contains("&lt;script&gt;"));
		DeskInputTest.check("read only: the button is disabled", html.contains("disabled=\"disabled\" onclick=\"mdskDo('PROPOSE_INPUT_FIELD'"));
		final DeskInput withField = DeskInputTest.four();
		withField.fields.add(DeskInputTest.field("PROPOSED", "g5:q"));
		final String decide = DeskInputTest.render(withField, true, Locale.ENGLISH);
		DeskInputTest.check("and Accept / Reject too", decide.contains("disabled=\"disabled\" onclick=\"mdskDo('ACCEPT_ATTRIBUTE'") && decide.contains("disabled=\"disabled\" onclick=\"mdskDo('REJECT_ATTRIBUTE'"));
	}

	private static void theCardsSitAboveTheListOfTheCannotRunCentre()
	{
		final DeskHtml.State state = new DeskHtml.State();
		state.selectedKey = "run|";
		final DeskInput input = new DeskInput();
		input.needs.add(DeskInputTest.need("g5", "EXTERNAL_SERVICE", "Geo", "address", DeskInputTest.SENTENCE));
		input.needs.add(DeskInputTest.need("g6", "SCRIPT_CONSTRAINT", "address.geocode", "address", DeskInputTest.SENTENCE));
		input.needs.add(DeskInputTest.need("g3", "EXTERNAL_SERVICE", "Geo", "q", "this one does not need an input any more"));
		state.input = input;
		final String html = DeskHtml.render(DeskGroupsTest.desk(), state);
		final int hero = html.indexOf("<h1>Cannot run as proposed</h1>");
		final int card = html.indexOf("2 waiting for: " + DeskInputTest.SENTENCE);
		final int list = html.indexOf("<table class=\"mdskTable\">", hero);
		DeskInputTest.check("the card is under the title and above the list: " + hero + " < " + card + " < " + list, hero >= 0 && hero < card && card < list);
		DeskInputTest.check("g3 (no secret only) is not in a card: the group lists only what needs an input", html.indexOf("this one does not need") < 0);
		state.selectedKey = "obj|e2";
		DeskInputTest.check("the other groups carry no card", !DeskHtml.render(DeskGroupsTest.desk(), state).contains("waiting for:"));
		state.selectedKey = "run|";
		state.input = null;
		DeskInputTest.check("without input data the group is what it was", !DeskHtml.render(DeskGroupsTest.desk(), state).contains("waiting for:"));
	}

	private static void bothLanguagesHaveTheTexts()
	{
		final DeskInput input = DeskInputTest.four();
		input.fields.add(DeskInputTest.field("PROPOSED", "g5:q", "g6:address", "g7:address", "g8:q"));
		final String french = DeskInputTest.render(input, false, Locale.FRENCH);
		DeskInputTest.check("French: the title, the button, the note", french.contains("4 en attente de : ") && french.contains("Accepter le champ") && french.contains("lie les 4 suggestions en attente"));
		DeskInputTest.check("French: the bundle's own texts, no raw key", !french.contains("desk.l5."));
		DeskInputTest.check("English: no raw key either", !DeskInputTest.render(DeskInputTest.four(), false, Locale.ENGLISH).contains("desk.l5."));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
