package com.on.ebx.factory.landing;

/** JUnit-free, like the sibling tests: run the main, a failure throws. */
public final class ReadRecipeTest
{
	public static void main(final String[] pArgs)
	{
		ReadRecipeTest.checkParseValidRecipe();
		ReadRecipeTest.checkParseWithoutItemsPath();
		ReadRecipeTest.checkParseRejectsBlank();
		ReadRecipeTest.checkParseRejectsNotAnObject();
		ReadRecipeTest.checkParseRejectsMissingConnector();
		ReadRecipeTest.checkParseRejectsMissingOperation();
		ReadRecipeTest.checkParseRejectsNonObjectInputs();
		ReadRecipeTest.checkParseRejectsNonLiteralInput();
		ReadRecipeTest.checkParsePreservesInputOrder();
		ReadRecipeTest.checkResolveRecipeWhenPresentAndConfirmed();
		ReadRecipeTest.checkResolveEmptyScanStateIsConfirmed();
		ReadRecipeTest.checkResolveLegacyFallbackWhenNoRecipe();
		ReadRecipeTest.checkResolveFailsWhenToConfirm();
		ReadRecipeTest.checkResolveFailsWhenLeftOut();
		ReadRecipeTest.checkResolveToConfirmWinsEvenWithARecipe();
		ReadRecipeTest.checkResolveFailsOnMalformedRecipe();
		System.out.println("ReadRecipeTest OK");
	}

	private static void checkParseValidRecipe()
	{
		final ReadRecipe recipe = ReadRecipe
			.parse("{\"connector\":\"Salesforce\",\"operation\":\"query\",\"inputs\":{\"soql\":\"SELECT Id FROM Account\"},\"itemsPath\":\"records\"}");
		ReadRecipeTest.check("connector", "Salesforce".equals(recipe.connector));
		ReadRecipeTest.check("operation", "query".equals(recipe.operation));
		ReadRecipeTest.check("itemsPath", "records".equals(recipe.itemsPath));
		ReadRecipeTest.check("inputs", "SELECT Id FROM Account".equals(recipe.inputs.get("soql")));
		ReadRecipeTest.check("one input", recipe.inputs.size() == 1);
	}

	private static void checkParseWithoutItemsPath()
	{
		final ReadRecipe recipe = ReadRecipe.parse("{\"connector\":\"sample-file\",\"operation\":\"read\",\"inputs\":{\"path\":\"sample.csv\"}}");
		ReadRecipeTest.check("itemsPath defaults to empty, never null", "".equals(recipe.itemsPath));
	}

	private static void checkParseRejectsBlank()
	{
		ReadRecipeTest.expectParseFailure(null);
		ReadRecipeTest.expectParseFailure("");
		ReadRecipeTest.expectParseFailure("   ");
	}

	private static void checkParseRejectsNotAnObject()
	{
		ReadRecipeTest.expectParseFailure("[1,2,3]");
		ReadRecipeTest.expectParseFailure("not json at all {{{");
	}

	private static void checkParseRejectsMissingConnector()
	{
		ReadRecipeTest.expectParseFailure("{\"operation\":\"query\"}");
	}

	private static void checkParseRejectsMissingOperation()
	{
		ReadRecipeTest.expectParseFailure("{\"connector\":\"Snowflake\"}");
	}

	private static void checkParseRejectsNonObjectInputs()
	{
		ReadRecipeTest.expectParseFailure("{\"connector\":\"Snowflake\",\"operation\":\"query\",\"inputs\":\"sql\"}");
	}

	private static void checkParseRejectsNonLiteralInput()
	{
		ReadRecipeTest.expectParseFailure("{\"connector\":\"Snowflake\",\"operation\":\"query\",\"inputs\":{\"sql\":{\"nested\":true}}}");
		ReadRecipeTest.expectParseFailure("{\"connector\":\"Snowflake\",\"operation\":\"query\",\"inputs\":{\"sql\":null}}");
	}

	/** Contract: "inputs values are literal strings" - order preserved so the generated mapping JSON is deterministic. */
	private static void checkParsePreservesInputOrder()
	{
		final ReadRecipe recipe = ReadRecipe.parse("{\"connector\":\"Snowflake\",\"operation\":\"query\",\"inputs\":{\"b\":\"2\",\"a\":\"1\",\"c\":\"3\"}}");
		final java.util.Iterator<String> keys = recipe.inputs.keySet().iterator();
		ReadRecipeTest.check("b first", "b".equals(keys.next()));
		ReadRecipeTest.check("a second", "a".equals(keys.next()));
		ReadRecipeTest.check("c third", "c".equals(keys.next()));
	}

	private static void checkResolveRecipeWhenPresentAndConfirmed()
	{
		final ReadRecipe.Eligibility result = ReadRecipe.resolve("{\"connector\":\"Salesforce\",\"operation\":\"query\",\"inputs\":{},\"itemsPath\":\"records\"}", "CONFIRMED");
		ReadRecipeTest.check("kind RECIPE", result.kind == ReadRecipe.Eligibility.Kind.RECIPE);
		ReadRecipeTest.check("recipe carried", result.recipe != null && "Salesforce".equals(result.recipe.connector));
		ReadRecipeTest.check("no reason", result.reason == null);
	}

	private static void checkResolveEmptyScanStateIsConfirmed()
	{
		final ReadRecipe.Eligibility result = ReadRecipe.resolve("{\"connector\":\"Salesforce\",\"operation\":\"query\"}", "");
		ReadRecipeTest.check("blank scanState treated as CONFIRMED (contract: empty = CONFIRMED)", result.kind == ReadRecipe.Eligibility.Kind.RECIPE);
	}

	private static void checkResolveLegacyFallbackWhenNoRecipe()
	{
		final ReadRecipe.Eligibility blank = ReadRecipe.resolve("", "CONFIRMED");
		ReadRecipeTest.check("blank recipe -> LEGACY_FALLBACK, not FAILED", blank.kind == ReadRecipe.Eligibility.Kind.LEGACY_FALLBACK);
		ReadRecipeTest.check("legacy reason mentions re-scan", blank.reason != null && blank.reason.contains("re-scan"));
		ReadRecipeTest.check("no recipe carried", blank.recipe == null);
		final ReadRecipe.Eligibility nullRecipe = ReadRecipe.resolve(null, null);
		ReadRecipeTest.check("null recipe and null scanState -> LEGACY_FALLBACK too", nullRecipe.kind == ReadRecipe.Eligibility.Kind.LEGACY_FALLBACK);
	}

	private static void checkResolveFailsWhenToConfirm()
	{
		final ReadRecipe.Eligibility result = ReadRecipe.resolve("{\"connector\":\"HubSpot\",\"operation\":\"list\"}", "TO_CONFIRM");
		ReadRecipeTest.check("kind FAILED", result.kind == ReadRecipe.Eligibility.Kind.FAILED);
		ReadRecipeTest.check("message names the contract wording", "deduced table not confirmed yet".equals(result.reason));
	}

	private static void checkResolveFailsWhenLeftOut()
	{
		final ReadRecipe.Eligibility result = ReadRecipe.resolve("{\"connector\":\"HubSpot\",\"operation\":\"list\"}", "LEFT_OUT");
		ReadRecipeTest.check("kind FAILED", result.kind == ReadRecipe.Eligibility.Kind.FAILED);
		ReadRecipeTest.check("reason non-empty", result.reason != null && !result.reason.trim().isEmpty());
	}

	/** scanState gates BEFORE the recipe is even looked at - a TO_CONFIRM asset must never land, however good-looking its recipe already is. */
	private static void checkResolveToConfirmWinsEvenWithARecipe()
	{
		final ReadRecipe.Eligibility result = ReadRecipe.resolve("{\"connector\":\"HubSpot\",\"operation\":\"list\",\"inputs\":{}}", "TO_CONFIRM");
		ReadRecipeTest.check("still FAILED, recipe ignored", result.kind == ReadRecipe.Eligibility.Kind.FAILED);
	}

	/** A recipe that IS present but malformed must FAIL, not silently degrade to the legacy generator (a broken recipe is a real, surprising state, not "nothing to read from"). */
	private static void checkResolveFailsOnMalformedRecipe()
	{
		final ReadRecipe.Eligibility result = ReadRecipe.resolve("{not json", "CONFIRMED");
		ReadRecipeTest.check("kind FAILED", result.kind == ReadRecipe.Eligibility.Kind.FAILED);
		ReadRecipeTest.check("mentions re-scan", result.reason != null && result.reason.contains("re-scan"));
		final ReadRecipe.Eligibility missingConnector = ReadRecipe.resolve("{\"operation\":\"query\"}", "CONFIRMED");
		ReadRecipeTest.check("missing connector also FAILED", missingConnector.kind == ReadRecipe.Eligibility.Kind.FAILED);
	}

	private static void expectParseFailure(final String pJson)
	{
		try
		{
			ReadRecipe.parse(pJson);
			throw new AssertionError("expected a parse failure for: " + pJson);
		}
		catch (final IllegalArgumentException ex)
		{
			// expected
		}
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
