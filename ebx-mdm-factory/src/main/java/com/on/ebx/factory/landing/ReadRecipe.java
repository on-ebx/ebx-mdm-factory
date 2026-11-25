package com.on.ebx.factory.landing;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * The catalog's per-asset "read recipe" (read-recipe lot, 2026-09-24, {@code docs/read-recipe-contract.md}
 * section 1 of that lot's shared contract): how a landing table's flow should read ALL of the asset's rows,
 * entirely decided by the connector that catalogued it (Salesforce's own SOQL query operation, a SQL
 * dialect's SELECT, a file connector's {@code read}, EBX's own record read) - the landing generator no
 * longer decides this per connector TYPE the way {@link LandingFlow#dialectFor}/{@code Table#fileFeed} used
 * to (kept only as a fallback - see {@link LandingFlow}'s class Javadoc and {@code docs/LANDING-FLOWS.md}):
 * it only turns whatever recipe the catalog already carries into a Connectors mapping ({@link
 * LandingFlow#mapping}).
 * <p>
 * JSON shape ({@code Asset.readRecipe}, an {@code osd:text}, contract section 1):
 * {@code {"connector":"<id>","operation":"<name>","inputs":{"<name>":"<literal value>",...},
 * "itemsPath":"<path or empty>"}}. {@code inputs} values are always literal strings - a recipe reads every
 * row of the table, so no per-run templating is needed. Pure: no EBX class is used here: {@code
 * ebx.LandingFlows} is the only caller, reading the live {@code Asset} row and its {@code scanState} before
 * ever reaching {@link #resolve}.
 */
public final class ReadRecipe
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public final String connector;
	public final String operation;
	/** Literal string inputs, insertion order preserved (so the generated mapping JSON is deterministic). Never null. */
	public final Map<String, String> inputs;
	/** Never null; {@code ""} means the operation's own answer is itself the array (contract section 1). */
	public final String itemsPath;

	private ReadRecipe(final String pConnector, final String pOperation, final Map<String, String> pInputs, final String pItemsPath)
	{
		this.connector = pConnector;
		this.operation = pOperation;
		this.inputs = pInputs;
		this.itemsPath = pItemsPath;
	}

	/**
	 * Parses one {@code Asset.readRecipe} JSON text. Refuses (never guesses): a blank/malformed document, a
	 * missing {@code connector}/{@code operation}, or an {@code inputs} entry that is not a literal
	 * string/number/boolean (contract: "inputs values are literal strings"). {@code itemsPath} is optional,
	 * defaulting to {@code ""}.
	 */
	public static ReadRecipe parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			throw new IllegalArgumentException("the read recipe is empty");
		}
		final JsonNode node;
		try
		{
			node = ReadRecipe.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new IllegalArgumentException("the read recipe is not valid JSON: " + ex.getMessage(), ex);
		}
		if (node == null || !node.isObject())
		{
			throw new IllegalArgumentException("the read recipe must be a JSON object");
		}
		final String connector = ReadRecipe.textOrNull(node, "connector");
		if (connector == null || connector.trim().isEmpty())
		{
			throw new IllegalArgumentException("the read recipe has no 'connector'");
		}
		final String operation = ReadRecipe.textOrNull(node, "operation");
		if (operation == null || operation.trim().isEmpty())
		{
			throw new IllegalArgumentException("the read recipe has no 'operation'");
		}
		final Map<String, String> inputs = new LinkedHashMap<String, String>();
		final JsonNode inputsNode = node.path("inputs");
		if (inputsNode.isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> fields = inputsNode.fields();
			while (fields.hasNext())
			{
				final Map.Entry<String, JsonNode> field = fields.next();
				final JsonNode value = field.getValue();
				if (value == null || value.isNull() || !value.isValueNode())
				{
					throw new IllegalArgumentException("the read recipe input '" + field.getKey() + "' is not a literal value");
				}
				inputs.put(field.getKey(), value.asText());
			}
		}
		else if (!inputsNode.isMissingNode() && !inputsNode.isNull())
		{
			throw new IllegalArgumentException("the read recipe's 'inputs' must be a JSON object");
		}
		final JsonNode itemsPathNode = node.path("itemsPath");
		final String itemsPath = itemsPathNode.isMissingNode() || itemsPathNode.isNull() ? "" : itemsPathNode.asText("");
		return new ReadRecipe(connector.trim(), operation.trim(), inputs, itemsPath);
	}

	private static String textOrNull(final JsonNode pNode, final String pField)
	{
		final JsonNode value = pNode.path(pField);
		return value.isMissingNode() || value.isNull() ? null : value.asText(null);
	}

	/**
	 * What one landing table should do with the asset it was planned from - the pure decision behind the
	 * contract's rule "an asset without a recipe (or with scanState != CONFIRMED) -> that table FAILS with a
	 * clear message", refined by the coordinator's own preference (task brief): a MISSING recipe (an asset
	 * scanned before {@code readRecipe} existed, or a legacy row) falls back to the old per-connector-type
	 * generator ({@link LandingFlow#dialectFor}/{@code Table#fileFeed}) rather than failing outright, with a
	 * WARN the caller records on the table - only a genuinely blocking state (a deduced table not yet
	 * confirmed/left out, or a recipe that IS present but malformed) fails the table.
	 */
	public static final class Eligibility
	{
		public enum Kind
		{
			/** A {@link ReadRecipe} was parsed: build the mapping from it ({@link LandingFlow#mapping}). */
			RECIPE,
			/** No recipe on the asset (yet): fall back to the legacy per-connector-type generator, with {@link #reason} as a WARN to record. */
			LEGACY_FALLBACK,
			/** This table must not get a flow at all; {@link #reason} is the clear message to show. */
			FAILED
		}

		public final Kind kind;
		public final ReadRecipe recipe;
		public final String reason;

		private Eligibility(final Kind pKind, final ReadRecipe pRecipe, final String pReason)
		{
			this.kind = pKind;
			this.recipe = pRecipe;
			this.reason = pReason;
		}

		private static Eligibility recipe(final ReadRecipe pRecipe)
		{
			return new Eligibility(Kind.RECIPE, pRecipe, null);
		}

		private static Eligibility legacyFallback(final String pReason)
		{
			return new Eligibility(Kind.LEGACY_FALLBACK, null, pReason);
		}

		private static Eligibility failed(final String pReason)
		{
			return new Eligibility(Kind.FAILED, null, pReason);
		}
	}

	/** {@code Asset.scanState} value meaning "a deduced table a person has not confirmed yet" (contract section 1). */
	private static final String SCAN_STATE_TO_CONFIRM = "TO_CONFIRM";
	/** {@code Asset.scanState} value meaning "reviewed and excluded from landing" (contract section 1). */
	private static final String SCAN_STATE_LEFT_OUT = "LEFT_OUT";
	/** Default when {@code Asset.scanState} is blank - contract: "empty = CONFIRMED". */
	private static final String SCAN_STATE_CONFIRMED = "CONFIRMED";

	/**
	 * Decides what a landing table's flow generation should do, from its catalog asset's own {@code
	 * readRecipe}/{@code scanState} (contract section 1; {@code scanOrigin} plays no separate role here -
	 * every state this method acts on is already fully carried by {@code scanState}, since only a DEDUCED
	 * asset can ever be {@code TO_CONFIRM}/{@code LEFT_OUT} and an OFFICIAL one is "always CONFIRMED"). Never
	 * throws.
	 */
	public static Eligibility resolve(final String pReadRecipeJson, final String pScanState)
	{
		final String scanState = pScanState == null || pScanState.trim().isEmpty() ? ReadRecipe.SCAN_STATE_CONFIRMED : pScanState.trim();
		if (ReadRecipe.SCAN_STATE_TO_CONFIRM.equals(scanState))
		{
			return Eligibility.failed("deduced table not confirmed yet");
		}
		if (ReadRecipe.SCAN_STATE_LEFT_OUT.equals(scanState))
		{
			return Eligibility.failed("left out of the scan review: not a landing source");
		}
		if (pReadRecipeJson == null || pReadRecipeJson.trim().isEmpty())
		{
			return Eligibility.legacyFallback("legacy path: re-scan the source to get a read recipe");
		}
		try
		{
			return Eligibility.recipe(ReadRecipe.parse(pReadRecipeJson));
		}
		catch (final IllegalArgumentException ex)
		{
			return Eligibility.failed("the catalog's read recipe for this table is invalid (" + ex.getMessage() + "): re-scan the source");
		}
	}

}
