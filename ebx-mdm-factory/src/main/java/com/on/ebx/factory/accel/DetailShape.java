package com.on.ebx.factory.accel;

import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.accel.Difference.Nature;

/**
 * Lot C1 integration: the ONE shape of {@link Difference#detail()} per nature (documented on {@link Difference}), shared by the passes that write it
 * (the prompts of {@link AdaptationLlmPass} ask for it, {@link #normalise} repairs what the model wrote) and by the generator that reads it
 * ({@link DomainPlan}). Pure.
 */
public final class DetailShape
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DetailShape()
	{
	}

	/**
	 * Brings the model's detail to the documented shape; never throws, never drops what it does not know (the person sees it and can edit it).
	 * Null stays null when nothing can be inferred.
	 * <ul>
	 * <li>pass 1 ADD: {@code type} becomes {@code fieldType}; a missing {@code kind} is {@code table} when {@code fields} is present, else {@code field}; a
	 * {@code fields} that is a comma separated string becomes {@code [{"name": ...}]}.</li>
	 * <li>pass 2 ADD: {@code kind = rule}, {@code draft = true}, {@code rule} (the sentence; {@code description} is copied into it when absent).</li>
	 * <li>pass 4 ADD: {@code kind = role}; {@code role} is taken from {@code what} ({@code role X}) when the model did not give it.</li>
	 * <li>pass 3 (a layout): the layout itself is wrapped as {@code {"layout": {...}}}.</li>
	 * </ul>
	 */
	public static JsonNode normalise(final int pPass, final Nature pNature, final String pWhat, final JsonNode pDetail)
	{
		if (pPass == 3)
		{
			if (pDetail == null || !pDetail.isObject() || pDetail.has("layout") && pDetail.size() == 1)
			{
				return pDetail;
			}
			return DetailShape.MAPPER.createObjectNode().set("layout", pDetail);
		}
		if (pNature != Nature.ADD)
		{
			return pDetail;
		}
		final ObjectNode d = pDetail != null && pDetail.isObject() ? (ObjectNode) pDetail.deepCopy() : DetailShape.MAPPER.createObjectNode();
		if (pDetail != null && !pDetail.isObject() && !pDetail.isNull())
		{
			d.put("text", pDetail.asText());
		}
		if (pPass == 1)
		{
			if (d.has("type") && !d.has("fieldType"))
			{
				d.set("fieldType", d.remove("type"));
			}
			if (d.path("fields").isTextual())
			{
				final String list = d.path("fields").asText();
				final ArrayNode fields = d.putArray("fields");
				for (final String name : list.split("[,;]"))
				{
					if (!name.trim().isEmpty())
					{
						fields.addObject().put("name", name.trim());
					}
				}
			}
			if (!d.hasNonNull("kind"))
			{
				d.put("kind", d.path("fields").isArray() ? "table" : "field");
			}
		}
		else if (pPass == 2)
		{
			d.put("kind", "rule");
			d.put("draft", true);
			if (!d.hasNonNull("rule") && d.hasNonNull("description"))
			{
				d.set("rule", d.get("description"));
			}
		}
		else if (pPass == 4)
		{
			d.put("kind", "role");
			if (!d.hasNonNull("role") && pWhat != null && !pWhat.trim().isEmpty())
			{
				d.put("role", pWhat.trim().replaceFirst("(?i)^role\\s+", ""));
			}
		}
		return d.size() == 0 ? null : d;
	}

	/** The field type a pass 1 ADD or CHANGE declares ({@code fieldType}, or the older {@code type}); {@code string} when none. */
	public static String fieldType(final JsonNode pDetail)
	{
		if (pDetail == null)
		{
			return "string";
		}
		final String t = pDetail.hasNonNull("fieldType") ? pDetail.get("fieldType").asText() : pDetail.path("type").asText("string");
		return t.isEmpty() ? "string" : t.toLowerCase(Locale.ROOT).equals("datetime") ? "dateTime" : t;
	}

	/** Copy of {@code pDetail} carrying {@code similarTo} (and {@code similarNote}): the wizard shows "this looks like something that exists". */
	public static JsonNode withSimilar(final JsonNode pDetail, final String pSimilarTo, final String pNote)
	{
		final ObjectNode d = pDetail != null && pDetail.isObject() ? (ObjectNode) pDetail.deepCopy() : DetailShape.MAPPER.createObjectNode();
		d.put("similarTo", pSimilarTo);
		if (pNote != null && !pNote.isEmpty())
		{
			d.put("similarNote", pNote);
		}
		return d;
	}
}
