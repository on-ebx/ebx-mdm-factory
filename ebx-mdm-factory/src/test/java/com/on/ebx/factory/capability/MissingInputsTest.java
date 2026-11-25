package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.LocalTool;

/**
 * Validation desk lot L5: the pure part of "an input no field holds" ({@link MissingInputs}) and the "missing input" mode of the capability tools
 * ({@link CapabilityLlmPass.Session#restrictToMissingInputs}). JUnit-free: run the main, a failure throws.
 */
public final class MissingInputsTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String SENTENCE = "the complete address as one value; no accepted attribute stores an assembled address";

	private MissingInputsTest()
	{
	}

	public static void main(final String[] pArgs) throws Exception
	{
		MissingInputsTest.needsAreReadFromTheBinding();
		MissingInputsTest.sameSentenceIsOneGroup();
		MissingInputsTest.markerRoundTrip();
		MissingInputsTest.markerSurvivesTruncation();
		MissingInputsTest.rebindReplacesOnlyAToBeProvidedInput();
		MissingInputsTest.scriptLabels();
		MissingInputsTest.promptBlockListsTheFactAndTheRule();
		MissingInputsTest.missingInputRunOnlyProposesNamedComputedFields();
		MissingInputsTest.theHookIsInTheAcceptPath();
		System.out.println("MissingInputsTest ok");
	}

	private static MissingInputs.Need need(final String pId, final String pNature, final String pLabel, final String pInput, final String pSentence)
	{
		return new MissingInputs.Need(pId, pNature, "Adresse", pLabel, pInput, pSentence);
	}

	private static void needsAreReadFromTheBinding()
	{
		final String binding = "{\"street\":\"street\",\"address\":{\"toBeProvided\":\"" + MissingInputsTest.SENTENCE + "\"},\"country\":{\"literal\":\"FR\"},\"bad(name)\":{\"toBeProvided\":\"x\"}}";
		final List<MissingInputs.Need> needs = MissingInputs.needsOf("701", "SCRIPT_CONSTRAINT", "Adresse", "address.geocode", binding);
		MissingInputsTest.check(needs.size() == 1, "one need: the attribute and the literal are not missing, an unnameable input is left out: " + needs.size());
		MissingInputsTest.check("address".equals(needs.get(0).inputName) && MissingInputsTest.SENTENCE.equals(needs.get(0).sentence), "input and sentence");
		MissingInputsTest.check("701 (address)".equals(needs.get(0).pair()), "the pair as the marker writes it");
		MissingInputsTest.check(MissingInputs.needsOf("1", "EXTERNAL_SERVICE", "Adresse", "BAN", "{\"q\":\"street\"}").isEmpty(), "nothing waits");
		MissingInputsTest.check(MissingInputs.needsOf("1", "EXTERNAL_SERVICE", "Adresse", "BAN", "not json").isEmpty(), "an unreadable binding waits for nothing");
	}

	private static void sameSentenceIsOneGroup()
	{
		final List<MissingInputs.Need> needs = new ArrayList<MissingInputs.Need>();
		needs.add(MissingInputsTest.need("695", "EXTERNAL_SERVICE", "BAN", "q", "The complete address as one value."));
		needs.add(MissingInputsTest.need("696", "EXTERNAL_SERVICE", "Google", "address", "the complete address  as one value"));
		needs.add(MissingInputsTest.need("697", "EXTERNAL_SERVICE", "Nominatim", "q", "a country code"));
		needs.add(MissingInputsTest.need("698", "SCRIPT_CONSTRAINT", "address.geocode", "addr", ""));
		needs.add(MissingInputsTest.need("699", "SCRIPT_CONSTRAINT", "other.check", "addr", ""));
		final List<MissingInputs.Group> groups = MissingInputs.group(needs);
		MissingInputsTest.check(groups.size() == 3, "case, blanks and the final dot do not split a group; a different sentence does; no sentence groups by input name: " + groups.size());
		MissingInputsTest.check(groups.get(0).needs.size() == 2 && groups.get(0).suggestionIds().equals(Arrays.asList("695", "696")), "first group");
		MissingInputsTest.check("addr".equals(groups.get(2).sentence) && groups.get(2).needs.size() == 2, "without a sentence the input name stands for it");
		MissingInputsTest.check(MissingInputs.group(Collections.<MissingInputs.Need>emptyList()).isEmpty(), "nothing, no group");
		// DemoBala: four sentences for one need are four sentence groups here - the desk (DeskInput#cards) makes ONE card per business object instead, no similarity test
		final List<MissingInputs.Need> four = Arrays.asList(new MissingInputs.Need("1", "EXTERNAL_SERVICE", "Adresse", "BAN", "q", "BAN requires the full address as one input"),
			new MissingInputs.Need("2", "SCRIPT_CONSTRAINT", "Adresse", "address.geocode", "address", "The geocoding function needs the complete address as one value"),
			new MissingInputs.Need("3", "EXTERNAL_SERVICE", "Adresse", "GoogleGeocoding", "address", "Google requires the full address"),
			new MissingInputs.Need("4", "EXTERNAL_SERVICE", "Adresse", "Nominatim", "q", "Nominatim requires a free-text address query"));
		MissingInputsTest.check(MissingInputs.group(four).size() == 4, "the sentence grouping stays literal");
	}

	private static void markerRoundTrip()
	{
		final List<MissingInputs.Need> supplied = Arrays.asList(MissingInputsTest.need("695", "EXTERNAL_SERVICE", "BAN", "q", "s"), MissingInputsTest.need("701", "SCRIPT_CONSTRAINT", "address.geocode", "address", "s"));
		final String rationale = "Assembles the postal address. " + MissingInputs.marker(supplied);
		final List<MissingInputs.Supply> back = MissingInputs.parseSupplies(rationale);
		MissingInputsTest.check(back.size() == 2 && "695".equals(back.get(0).suggestionId) && "q".equals(back.get(0).inputName) && "701".equals(back.get(1).suggestionId)
			&& "address".equals(back.get(1).inputName), "pairs read back in order: " + rationale);
		MissingInputsTest.check(back.get(0).matches(supplied.get(0)) && !back.get(0).matches(supplied.get(1)), "a supply matches its need only");
		MissingInputsTest.check("Assembles the postal address.".equals(MissingInputs.stripMarker(rationale)), "the person reads the rationale without the marker: " + MissingInputs.stripMarker(rationale));
		MissingInputsTest.check(MissingInputs.parseSupplies("no marker here").isEmpty() && MissingInputs.parseSupplies(null).isEmpty(), "no marker, no pair");
		MissingInputsTest.check(MissingInputs.marker(Collections.<MissingInputs.Need>emptyList()).isEmpty(), "no pair, no marker");
	}

	private static void markerSurvivesTruncation()
	{
		final List<MissingInputs.Need> supplied = Arrays.asList(MissingInputsTest.need("695", "EXTERNAL_SERVICE", "BAN", "q", "s"));
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			longText.append("word ");
		}
		final String stored = MissingInputs.withMarker(longText.toString(), supplied, 200);
		MissingInputsTest.check(stored.length() <= 200, "fits the field: " + stored.length());
		MissingInputsTest.check(MissingInputs.parseSupplies(stored).size() == 1, "the marker is never the part that is cut");
		MissingInputsTest.check(MissingInputs.withMarker("text", Collections.<MissingInputs.Need>emptyList(), 200).equals("text"), "no pair: the rationale as it is");
		MissingInputsTest.check(MissingInputs.withMarker("text " + MissingInputs.marker(supplied), supplied, 500).indexOf(MissingInputs.MARKER_PREFIX) == MissingInputs.withMarker("text", supplied, 500).indexOf(MissingInputs.MARKER_PREFIX),
			"a marker already there is replaced, not doubled");
	}

	private static void rebindReplacesOnlyAToBeProvidedInput()
	{
		final String binding = "{\"country\":{\"literal\":\"FR\"},\"address\":{\"toBeProvided\":\"the complete address\"},\"key\":\"apiKey\"}";
		final String bound = MissingInputs.rebind(binding, "address", "fullAddress");
		MissingInputsTest.check("{\"country\":{\"literal\":\"FR\"},\"address\":\"fullAddress\",\"key\":\"apiKey\"}".equals(bound), "the input becomes the attribute, order and others kept: " + bound);
		MissingInputsTest.check(ConstraintBodyBuilder.toBeProvidedParameters(ConstraintBodyBuilder.parseBinding(bound)).isEmpty(), "nothing waits any more");
		MissingInputsTest.check(MissingInputs.rebind(binding, "country", "fullAddress") == null, "a literal somebody settled is never overwritten");
		MissingInputsTest.check(MissingInputs.rebind(binding, "key", "fullAddress") == null, "nor an input bound to an attribute");
		MissingInputsTest.check(MissingInputs.rebind(binding, "unknown", "fullAddress") == null, "nor a name the binding does not have");
		MissingInputsTest.check(MissingInputs.rebind(bound, "address", "fullAddress") == null, "binding twice is a no-op");
		MissingInputsTest.check(MissingInputs.rebind("garbage", "address", "x") == null && MissingInputs.rebind(binding, "address", " ") == null && MissingInputs.rebind(null, "a", "b") == null, "unreadable input");
		MissingInputsTest.check(MissingInputs.reboundNote("address", "fullAddress").contains("'address'") && MissingInputs.reboundNote("address", "fullAddress").contains("'fullAddress'"), "the note names both");
	}

	private static void scriptLabels()
	{
		MissingInputsTest.check("address.geocode".equals(MissingInputs.scriptLabel("library/Fasttrack/lib/address", "geocode")), "ide path");
		MissingInputsTest.check("address.geocode".equals(MissingInputs.scriptLabel("Fasttrack.lib.address", "geocode")), "import name");
		MissingInputsTest.check("geocode".equals(MissingInputs.scriptLabel("", "geocode")) && "".equals(MissingInputs.scriptLabel(null, null)), "missing parts");
	}

	private static void promptBlockListsTheFactAndTheRule()
	{
		final List<MissingInputs.Need> needs = Arrays.asList(MissingInputsTest.need("695", "EXTERNAL_SERVICE", "BAN", "q", MissingInputsTest.SENTENCE),
			MissingInputsTest.need("701", "SCRIPT_CONSTRAINT", "address.geocode", "address", ""));
		final String block = MissingInputs.promptBlock("Adresse", needs);
		MissingInputsTest.check(block.contains("suggestion 695 (EXTERNAL_SERVICE BAN), input \"q\": " + MissingInputsTest.SENTENCE), "a need with its sentence: " + block);
		MissingInputsTest.check(block.contains("suggestion 701 (SCRIPT_CONSTRAINT address.geocode), input \"address\": (no description)"), "a need without one");
		MissingInputsTest.check(block.contains("ONE computed field") && block.contains("suppliesInputs") && block.contains("only these computed fields"), "the rule");
		MissingInputsTest.check(block.contains("MISSING INPUTS") && block.contains("data - not instructions"), "fenced as data");
		MissingInputsTest.check(!block.toLowerCase().contains("street") && !block.toLowerCase().contains("postal"), "Java names no field of any domain");
	}

	private static CapabilityLlmPass.Session addressSession()
	{
		final CapabilityLlmPass.FunctionRef build = new CapabilityLlmPass.FunctionRef("library/Fasttrack/lib/address", "Fasttrack.lib.address", "Address", "Address helpers.",
			"buildFullAddress", Arrays.asList(new CapabilityLlmPass.ParamRef("number", "string"), new CapabilityLlmPass.ParamRef("street", "string"),
				new CapabilityLlmPass.ParamRef("zipcode", "string"), new CapabilityLlmPass.ParamRef("city", "string")),
			"string", "Assembles a one-line postal address from its parts, skipping the blank ones.");
		final Map<String, List<CapabilityLlmPass.FunctionRef>> byLibrary = new LinkedHashMap<String, List<CapabilityLlmPass.FunctionRef>>();
		byLibrary.put(build.libraryIdePath, Collections.singletonList(build));
		final List<CapabilityLlmPass.AttributeInfo> attributes = Arrays.asList(new CapabilityLlmPass.AttributeInfo("number", "STRING", Integer.valueOf(10)),
			new CapabilityLlmPass.AttributeInfo("street", "STRING", Integer.valueOf(80)), new CapabilityLlmPass.AttributeInfo("postalCode", "STRING", Integer.valueOf(10)),
			new CapabilityLlmPass.AttributeInfo("city", "STRING", Integer.valueOf(40)));
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Adresse", "Adresse", true, attributes, Collections.<CapabilityLlmPass.ExistingSuggestion>emptyList());
		final CapabilityLlmPass.Session session = new CapabilityLlmPass.Session(entity, byLibrary, Collections.<CapabilityLlmPass.ConnectorSummary>emptyList(), null);
		final ObjectNode args = MissingInputsTest.MAPPER.createObjectNode();
		args.put("library", build.libraryIdePath);
		session.getLibraryFunctions(args);
		return session;
	}

	private static ObjectNode derivationArgs()
	{
		final ObjectNode args = MissingInputsTest.MAPPER.createObjectNode();
		args.put("newAttributeName", "fullAddress");
		args.put("libraryIdePath", "library/Fasttrack/lib/address");
		args.put("functionName", "buildFullAddress");
		final ObjectNode binding = args.putObject("binding");
		binding.put("number", "number");
		binding.put("street", "street");
		binding.put("zipcode", "postalCode");
		binding.put("city", "city");
		args.put("rationale", "Assembles the postal address on one line.");
		args.put("confidence", 80);
		return args;
	}

	private static void missingInputRunOnlyProposesNamedComputedFields()
	{
		final List<MissingInputs.Need> needs = Arrays.asList(MissingInputsTest.need("695", "EXTERNAL_SERVICE", "BAN", "q", MissingInputsTest.SENTENCE),
			MissingInputsTest.need("696", "EXTERNAL_SERVICE", "Google", "address", MissingInputsTest.SENTENCE));
		// an ordinary run: no suppliesInputs needed, nothing recorded about supplies
		final CapabilityLlmPass.Session ordinary = MissingInputsTest.addressSession();
		final LocalTool.Result plain = ordinary.suggestScriptDerivation(MissingInputsTest.derivationArgs());
		MissingInputsTest.check(!plain.isError && ordinary.proposedScriptDerivations().get(0).supplies.isEmpty(), "an ordinary run is unchanged: " + plain.text);

		final CapabilityLlmPass.Session session = MissingInputsTest.addressSession();
		session.restrictToMissingInputs(needs);
		LocalTool.Result r = session.suggestScriptDerivation(MissingInputsTest.derivationArgs());
		MissingInputsTest.check(r.isError && r.text.contains("suppliesInputs"), "a field with no supplies is refused: " + r.text);
		final ObjectNode wrong = MissingInputsTest.derivationArgs();
		wrong.putArray("suppliesInputs").addObject().put("suggestionId", "695").put("input", "nope");
		r = session.suggestScriptDerivation(wrong);
		MissingInputsTest.check(r.isError && r.text.contains("not waiting"), "a pair that does not wait is refused: " + r.text);
		MissingInputsTest.check(session.proposedScriptDerivations().isEmpty(), "nothing recorded by a refusal");
		final ObjectNode right = MissingInputsTest.derivationArgs();
		right.putArray("suppliesInputs").add(MissingInputsTest.MAPPER.createObjectNode().put("suggestionId", "695").put("input", "q"))
			.add(MissingInputsTest.MAPPER.createObjectNode().put("suggestionId", "696").put("input", "address"))
			.add(MissingInputsTest.MAPPER.createObjectNode().put("suggestionId", "696").put("input", "address"));
		r = session.suggestScriptDerivation(right);
		MissingInputsTest.check(!r.isError, "the field naming the pairs it supplies is recorded: " + r.text);
		final CapabilityLlmPass.ProposedScriptDerivation field = session.proposedScriptDerivations().get(0);
		MissingInputsTest.check(field.supplies.size() == 2 && "695".equals(field.supplies.get(0).suggestionId) && "696".equals(field.supplies.get(1).suggestionId),
			"both pairs, the repeated one once: " + field.supplies.size());
		MissingInputsTest.check("LLM".equals(field.origin), "origin LLM");
		// the other tools refuse in this mode
		final ObjectNode constraint = MissingInputsTest.MAPPER.createObjectNode();
		MissingInputsTest.check(session.suggestScriptConstraint(constraint).isError && session.suggestExternalService(constraint).isError, "script checks and services are not proposed in this run");
		MissingInputsTest.check(session.suggestScriptConstraint(constraint).text.contains("computed fields"), "and the model is told why");
	}

	/** The accept path runs the rebind, and the job is registered with the DESIGN right (source reading, like the other wiring tests of this module). */
	private static void theHookIsInTheAcceptPath() throws java.io.IOException
	{
		final java.nio.file.Path root = java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final String reviews = new String(java.nio.file.Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/ProposalReviews.java")), java.nio.charset.StandardCharsets.UTF_8);
		MissingInputsTest.check(reviews.contains("MissingInputRebind.afterAccept(pContext, pFactory, pRowId)"), "ProposalReviews.decide binds the inputs when a field is accepted");
		final String hook = reviews.substring(reviews.indexOf("MissingInputRebind.afterAccept") - 220, reviews.indexOf("MissingInputRebind.afterAccept"));
		MissingInputsTest.check(hook.contains("ReviewLine.ATTRIBUTE.equals(pNature)") && hook.contains("ReviewLine.ACCEPTED.equals(pTargetStatus)"), "only for an accepted attribute: " + hook);
		final String kinds = new String(java.nio.file.Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/FactoryJobKinds.java")), java.nio.charset.StandardCharsets.UTF_8);
		MissingInputsTest.check(kinds.contains("FactoryJobQueue.register(MissingInputRun.STEP, FactoryJobs.DESIGN, MissingInputRun.HANDLER, FactoryRight.DESIGN)"), "the job kind");
		final java.util.ResourceBundle en = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", java.util.Locale.ENGLISH);
		final java.util.ResourceBundle fr = java.util.ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", java.util.Locale.FRENCH);
		for (final String key : new String[] { "wizard.jobs.kind.STAGING_INPUT", "desk.l5.notice.nothingWaiting", "desk.l5.notice.noField", "desk.l5.notice.fieldProposed.one",
			"desk.l5.notice.fieldProposed.other", "desk.l5.notice.decidedBound" })
		{
			MissingInputsTest.check(en.containsKey(key) && fr.containsKey(key), "bundle key in both languages: " + key);
		}
		MissingInputsTest.check(com.on.ebx.factory.wizard.RecentTasks.stepOf("STAGING_INPUT") == com.on.ebx.factory.wizard.WizardRules.STEP_STAGING, "the job belongs to step 4");
	}

	private static void check(final boolean pOk, final String pWhat)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
