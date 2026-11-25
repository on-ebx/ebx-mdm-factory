package com.on.ebx.factory.landing;

import java.io.IOException;
import java.util.Iterator;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Fits a {@code PipelineStep.detail} JSON document into the column's cap WITHOUT breaking it (audit F-29). A plain cut
 * ({@link TextLimits#truncate}) leaves invalid JSON, and the readers that decide what the factory owns
 * ({@code ProjectDeleter}, {@code MasterReset}, {@code StepResetter}: {@code dataspace.created}) treat an unparsable detail as
 * "no evidence" - so a bulky step could silently turn "this dataspace was created by the factory" into "not ours". Here the
 * ownership keys are written FIRST and kept (nested objects reduced to their scalar fields when they are themselves bulky), the
 * other top-level keys are kept in their original order while they fit, and the rest is named in {@code omitted}; the result
 * says {@code "truncated": true}. A document that is not a JSON object, or that cannot be parsed, falls back to the plain cut.
 * Pure: no EBX class.
 */
public final class DetailFit
{
	/** The keys the ownership readers look at ({@code dataspace.created}, {@code dataset.created}, {@code created}): never dropped. */
	static final String[] OWNERSHIP_KEYS =
		{ "dataspace", "dataset", "created" };
	/** A nested ownership object above this many characters is reduced to its scalar fields. */
	private static final int OWNERSHIP_OBJECT_MAX = 2000;
	private static final int SCALAR_TEXT_MAX = 300;
	/** Room kept for the {@code omitted} list and the closing of the document. */
	private static final int RESERVE = 600;
	private static final int OMITTED_NAMES_MAX = 40;
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DetailFit()
	{
	}

	/** {@code pJson} as is when it fits {@code pMax}; otherwise a valid, smaller JSON object that keeps the ownership keys. */
	public static String fit(final String pJson, final int pMax)
	{
		if (pJson == null || pJson.length() <= pMax)
		{
			return pJson;
		}
		final JsonNode root;
		try
		{
			root = DetailFit.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			return TextLimits.truncate(pJson, pMax);
		}
		if (root == null || !root.isObject())
		{
			return TextLimits.truncate(pJson, pMax);
		}
		final ObjectNode out = DetailFit.MAPPER.createObjectNode();
		out.put("truncated", true);
		out.put("originalLength", pJson.length());
		for (final String key : DetailFit.OWNERSHIP_KEYS)
		{
			if (root.has(key))
			{
				out.set(key, DetailFit.slim(root.get(key)));
			}
		}
		long length = out.toString().length();
		final ArrayNode omitted = DetailFit.MAPPER.createArrayNode();
		final Iterator<Map.Entry<String, JsonNode>> fields = root.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final String key = field.getKey();
			if (out.has(key))
			{
				continue; // an ownership key already kept, or one of our own two
			}
			final long entry = key.length() + field.getValue().toString().length() + 6L;
			if (length + entry <= pMax - DetailFit.RESERVE)
			{
				out.set(key, field.getValue());
				length += entry;
			}
			else if (omitted.size() < DetailFit.OMITTED_NAMES_MAX)
			{
				omitted.add(TextLimits.truncate(key, 100));
			}
		}
		if (omitted.size() > 0)
		{
			out.set("omitted", omitted);
		}
		final String result = out.toString();
		if (result.length() <= pMax)
		{
			return result;
		}
		// the ownership keys alone overflow (a scalar of a huge text...): keep only their scalars, cut short
		final ObjectNode minimal = DetailFit.MAPPER.createObjectNode();
		minimal.put("truncated", true);
		for (final String key : DetailFit.OWNERSHIP_KEYS)
		{
			if (root.has(key))
			{
				minimal.set(key, DetailFit.scalarsOnly(root.get(key)));
			}
		}
		final String minimalText = minimal.toString();
		return minimalText.length() <= pMax ? minimalText : TextLimits.truncate(pJson, pMax);
	}

	private static JsonNode slim(final JsonNode pNode)
	{
		if (pNode.isObject() && pNode.toString().length() > DetailFit.OWNERSHIP_OBJECT_MAX)
		{
			return DetailFit.scalarsOnly(pNode);
		}
		return pNode;
	}

	private static JsonNode scalarsOnly(final JsonNode pNode)
	{
		if (!pNode.isObject())
		{
			return pNode.isTextual() ? DetailFit.MAPPER.getNodeFactory().textNode(TextLimits.truncate(pNode.asText(), DetailFit.SCALAR_TEXT_MAX)) : pNode;
		}
		final ObjectNode copy = DetailFit.MAPPER.createObjectNode();
		final Iterator<Map.Entry<String, JsonNode>> fields = pNode.fields();
		while (fields.hasNext())
		{
			final Map.Entry<String, JsonNode> field = fields.next();
			final JsonNode value = field.getValue();
			if (value.isTextual())
			{
				copy.put(field.getKey(), TextLimits.truncate(value.asText(), DetailFit.SCALAR_TEXT_MAX));
			}
			else if (value.isValueNode())
			{
				copy.set(field.getKey(), value);
			}
		}
		return copy;
	}
}
