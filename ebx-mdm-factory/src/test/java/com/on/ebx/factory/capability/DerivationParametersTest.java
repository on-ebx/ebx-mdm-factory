package com.on.ebx.factory.capability;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. Lot P1 (2026-10-04): the pure rules of settling a derivation's parameter by a person. Domain-neutral
 * fixture (a "value" attribute and a "default" parameter), shaped after the Vol1m capture where the model had to bind the second parameter to a blank.
 */
public final class DerivationParametersTest
{
	private static final String BINDING = "{\"value\":\"phone\",\"default\":{\"toBeProvided\":\"which default completes the national numbers\"},\"mode\":{\"literal\":\"strict\"}}";

	public static void main(final String[] pArgs) throws Exception
	{
		DerivationParametersTest.settableListsLiteralsAndToBeProvidedNeverAttributes();
		DerivationParametersTest.settlingAToBeProvidedStoresALiteralAndKeepsTheOrderAndTheRest();
		DerivationParametersTest.aBlankValueSettlesNothingButMayClearAnExistingLiteral();
		DerivationParametersTest.refusalsAreClearAndListTheSettableOnes();
		DerivationParametersTest.changedMeansADifferentValue();
		DerivationParametersTest.theReapplyMarkerIsRecognisedExactly();
		DerivationParametersTest.theApplyRefusalNamesTheParametersAndWhereToSupplyThem();
		DerivationParametersTest.aFailedReapplyMessageCarriesTheFailureAndStaysTheMarker();
		System.out.println("DerivationParametersTest OK");
	}

	private static Map<String, String> values(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			out.put(pPairs[i], pPairs[i + 1]);
		}
		return out;
	}

	private static void settableListsLiteralsAndToBeProvidedNeverAttributes()
	{
		final List<DerivationParameters.Settable> settable = DerivationParameters.settable(DerivationParametersTest.BINDING);
		DerivationParametersTest.check("two settable, binding order: " + settable.size(), settable.size() == 2 && "default".equals(settable.get(0).name) && "mode".equals(settable.get(1).name));
		DerivationParametersTest.check("the to-be-provided carries the model's note and an empty value", settable.get(0).toBeProvided && settable.get(0).value.isEmpty()
			&& settable.get(0).note.equals("which default completes the national numbers"));
		DerivationParametersTest.check("the literal carries its value and no note", !settable.get(1).toBeProvided && "strict".equals(settable.get(1).value) && settable.get(1).note.isEmpty());
		DerivationParametersTest.check("unreadable binding: nothing settable", DerivationParameters.settable("not json").isEmpty() && DerivationParameters.settable(null).isEmpty());
		DerivationParametersTest.check("to-be-provided names", DerivationParameters.toBeProvidedNames(DerivationParametersTest.BINDING).equals(Collections.singletonList("default")));
	}

	private static void settlingAToBeProvidedStoresALiteralAndKeepsTheOrderAndTheRest() throws Exception
	{
		final DerivationParameters.Update update = DerivationParameters.settle(DerivationParametersTest.BINDING, DerivationParametersTest.values("default", "FR"));
		DerivationParametersTest.check("stored as a literal, the attribute entry and the other literal untouched, order kept: " + update.bindingJson,
			update.bindingJson.equals("{\"value\":\"phone\",\"default\":{\"literal\":\"FR\"},\"mode\":{\"literal\":\"strict\"}}"));
		DerivationParametersTest.check("changed = [default], nothing remains to be provided", update.changed.equals(Collections.singletonList("default")) && update.toBeProvidedRemaining.isEmpty());
		final String two = "{\"value\":\"phone\",\"a\":{\"toBeProvided\":\"x\"},\"b\":{\"toBeProvided\":\"y\"}}";
		final DerivationParameters.Update one = DerivationParameters.settle(two, DerivationParametersTest.values("b", "EUR"));
		DerivationParametersTest.check("one of two settled: the other remains: " + one.toBeProvidedRemaining, one.toBeProvidedRemaining.equals(Collections.singletonList("a")));
		DerivationParametersTest.check("a value with quotes and a backslash is stored as data, escaped by JSON: " + DerivationParameters.settle(two, DerivationParametersTest.values("a", "x\"y\\z")).bindingJson,
			DerivationParameters.settle(two, DerivationParametersTest.values("a", "x\"y\\z")).bindingJson.contains("\"a\":{\"literal\":\"x\\\"y\\\\z\"}"));
	}

	private static void aBlankValueSettlesNothingButMayClearAnExistingLiteral() throws Exception
	{
		boolean refused = false;
		try
		{
			DerivationParameters.settle(DerivationParametersTest.BINDING, DerivationParametersTest.values("default", "  "));
		}
		catch (final DerivationParameters.Refusal ex)
		{
			refused = ex.status == 400 && ex.getMessage().contains("still to be provided") && ex.getMessage().contains("blank");
		}
		DerivationParametersTest.check("a blank value never settles a toBeProvided (400)", refused);
		final DerivationParameters.Update cleared = DerivationParameters.settle(DerivationParametersTest.BINDING, DerivationParametersTest.values("mode", ""));
		DerivationParametersTest.check("a blank literal is allowed on an entry that already was a literal: " + cleared.bindingJson, cleared.bindingJson.contains("\"mode\":{\"literal\":\"\"}")
			&& cleared.changed.equals(Collections.singletonList("mode")) && cleared.toBeProvidedRemaining.equals(Collections.singletonList("default")));
	}

	private static void refusalsAreClearAndListTheSettableOnes()
	{
		DerivationParametersTest.refuses("unknown parameter", DerivationParametersTest.values("nope", "x"), "unknown parameter 'nope'", "[default, mode]");
		DerivationParametersTest.refuses("attribute-bound parameter", DerivationParametersTest.values("value", "other"), "bound to the attribute 'phone'", "never changed through this route");
		DerivationParametersTest.refuses("empty body", new LinkedHashMap<String, String>(), "no parameter given", "[default, mode]");
		final Map<String, String> nullValue = new LinkedHashMap<String, String>();
		nullValue.put("default", null);
		DerivationParametersTest.refuses("null value", nullValue, "must be a string", "default");
		DerivationParametersTest.check("one bad entry among good ones changes nothing and refuses the whole request", DerivationParametersTest.refusesQuietly(DerivationParametersTest.values("default", "FR", "nope", "x")));
	}

	private static boolean refusesQuietly(final Map<String, String> pValues)
	{
		try
		{
			DerivationParameters.settle(DerivationParametersTest.BINDING, pValues);
			return false;
		}
		catch (final DerivationParameters.Refusal ex)
		{
			return ex.status == 400;
		}
	}

	private static void refuses(final String pLabel, final Map<String, String> pValues, final String pPart1, final String pPart2)
	{
		try
		{
			DerivationParameters.settle(DerivationParametersTest.BINDING, pValues);
			DerivationParametersTest.check(pLabel + " must be refused", false);
		}
		catch (final DerivationParameters.Refusal ex)
		{
			DerivationParametersTest.check(pLabel + ": " + ex.getMessage(), ex.status == 400 && ex.getMessage().contains(pPart1) && ex.getMessage().contains(pPart2));
		}
	}

	private static void changedMeansADifferentValue() throws Exception
	{
		final DerivationParameters.Update same = DerivationParameters.settle(DerivationParametersTest.BINDING, DerivationParametersTest.values("mode", "strict"));
		DerivationParametersTest.check("the same literal again changes nothing", same.changed.isEmpty() && same.bindingJson.contains("\"mode\":{\"literal\":\"strict\"}"));
		final DerivationParameters.Update both = DerivationParameters.settle(DerivationParametersTest.BINDING, DerivationParametersTest.values("mode", "loose", "default", "DE"));
		DerivationParametersTest.check("both changed, in binding order: " + both.changed, both.changed.equals(Arrays.asList("default", "mode")));
	}

	private static void theReapplyMarkerIsRecognisedExactly()
	{
		DerivationParametersTest.check("the marker", DerivationParameters.isReapplyMarker(DerivationParameters.REAPPLY_MARKER) && DerivationParameters.isReapplyMarker(" " + DerivationParameters.REAPPLY_MARKER + "\n"));
		DerivationParametersTest.check("anything else is not", !DerivationParameters.isReapplyMarker(null) && !DerivationParameters.isReapplyMarker("")
			&& !DerivationParameters.isReapplyMarker("Backfill failed") && !DerivationParameters.isReapplyMarker(DerivationParameters.REAPPLY_MARKER + " Backfill failed"));
	}

	private static void aFailedReapplyMessageCarriesTheFailureAndStaysTheMarker()
	{
		final String message = DerivationParameters.failedReapplyMessage("publish_data_model failed for Vol_Staging");
		DerivationParametersTest.check("failure text then marker: " + message, message.startsWith("publish_data_model failed for Vol_Staging ") && message.endsWith(DerivationParameters.REAPPLY_MARKER));
		DerivationParametersTest.check("still recognised as 'apply again'", DerivationParameters.isReapplyMarker(message));
		DerivationParametersTest.check("no failure text gives the plain marker", DerivationParameters.REAPPLY_MARKER.equals(DerivationParameters.failedReapplyMessage("  ")));
		final StringBuilder longText = new StringBuilder();
		for (int i = 0; i < 3000; i++)
		{
			longText.append('x');
		}
		final String cut = DerivationParameters.failedReapplyMessage(longText.toString());
		DerivationParametersTest.check("a long failure is cut, never the marker: " + cut.length(), cut.length() <= DerivationParameters.APPLY_MESSAGE_MAX && cut.endsWith(DerivationParameters.REAPPLY_MARKER)
			&& DerivationParameters.isReapplyMarker(cut));
		DerivationParametersTest.check("a note appended AFTER the marker is not one", !DerivationParameters.isReapplyMarker(message + " Backfill failed"));
		DerivationParametersTest.check("a text that merely mentions the marker mid-way is not one", !DerivationParameters.isReapplyMarker("see: " + DerivationParameters.REAPPLY_MARKER + " (old)"));
	}

	private static void theApplyRefusalNamesTheParametersAndWhereToSupplyThem()
	{
		final String message = DerivationParameters.toBeProvidedMessage("107", Arrays.asList("country"), "ret");
		DerivationParametersTest.check("names the parameter, the wizard and the route: " + message, message.contains("[country]") && message.contains("wizard") && message.contains("step 4")
			&& message.contains("PUT /projects/ret/capabilities/107/binding"));
		DerivationParametersTest.check("the rationale sentence names the parameters", DerivationParameters.stillToProvideSentence(Arrays.asList("country", "currency"))
			.equals("A person must still provide country, currency (wizard, step 4) before this derivation can be applied; until then the new attribute stays empty."));
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
