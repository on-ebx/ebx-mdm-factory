package com.on.ebx.factory.deducedscan;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Deduced-scan lot (2026-09-24): the read-recipe-contract.md JSON shape, section 1 - {@code
 * {"connector":"...","operation":"...","inputs":{...},"itemsPath":"..."}} - built once here so the catalog
 * writer ({@code ebx.DeducedScanCatalogWriter}) and any future reader (a landing generator turning it into a
 * Connectors feed mapping) agree byte for byte on the shape. Field names are FIXED by the contract - never
 * renamed here.
 */
public final class ReadRecipe
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public final String connector;
	public final String operation;
	public final Map<String, String> inputs;
	public final String itemsPath;

	public ReadRecipe(final String pConnector, final String pOperation, final Map<String, String> pInputs, final String pItemsPath)
	{
		this.connector = pConnector == null ? "" : pConnector;
		this.operation = pOperation == null ? "" : pOperation;
		this.inputs = pInputs == null ? new LinkedHashMap<String, String>() : new TreeMap<String, String>(pInputs);
		this.itemsPath = pItemsPath == null ? "" : pItemsPath;
	}

	/** {@code inputs} keys sorted (deterministic, easy to diff/test) - contract only requires the JSON object shape, not a key order. */
	public String toJson()
	{
		final ObjectNode root = ReadRecipe.MAPPER.createObjectNode();
		root.put("connector", this.connector);
		root.put("operation", this.operation);
		final ObjectNode inputsNode = root.putObject("inputs");
		for (final Map.Entry<String, String> entry : this.inputs.entrySet())
		{
			inputsNode.put(entry.getKey(), entry.getValue());
		}
		root.put("itemsPath", this.itemsPath);
		return root.toString();
	}

	/**
	 * Fix lot (2026-09-26, defect 9): a second deduced scan of a connection already in the catalog must
	 * reconcile with the asset it (or an earlier deduced scan) already wrote for the SAME recipe, rather than
	 * writing a duplicate {@code Asset} - see {@code ebx.DeducedScanCatalog#findByRecipe}. Two recipes are the
	 * same asset when connector/operation/itemsPath match exactly and every input (key and value) matches too
	 * - never compared by table name, which can change (a rename, or the model naming it differently on a
	 * second run).
	 */
	@Override
	public boolean equals(final Object pOther)
	{
		if (this == pOther)
		{
			return true;
		}
		if (!(pOther instanceof ReadRecipe))
		{
			return false;
		}
		final ReadRecipe other = (ReadRecipe) pOther;
		return this.connector.equals(other.connector) && this.operation.equals(other.operation) && this.itemsPath.equals(other.itemsPath)
			&& this.inputs.equals(other.inputs);
	}

	@Override
	public int hashCode()
	{
		return this.connector.hashCode() * 31 + this.operation.hashCode() * 17 + this.itemsPath.hashCode() * 7 + this.inputs.hashCode();
	}

	/** Never throws: an unparsable/blank {@code pJson} answers an all-empty recipe, the same "degrade, do not crash" posture every other progress/detail parser in this module follows. */
	public static ReadRecipe parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return new ReadRecipe("", "", null, "");
		}
		try
		{
			final JsonNode root = ReadRecipe.MAPPER.readTree(pJson);
			final Map<String, String> inputs = new LinkedHashMap<String, String>();
			final JsonNode inputsNode = root.path("inputs");
			for (final java.util.Iterator<Map.Entry<String, JsonNode>> it = inputsNode.fields(); it.hasNext();)
			{
				final Map.Entry<String, JsonNode> entry = it.next();
				inputs.put(entry.getKey(), entry.getValue().asText(""));
			}
			return new ReadRecipe(root.path("connector").asText(""), root.path("operation").asText(""), inputs, root.path("itemsPath").asText(""));
		}
		catch (final Exception ex) // deliberately broad - see Javadoc
		{
			return new ReadRecipe("", "", null, "");
		}
	}
}
