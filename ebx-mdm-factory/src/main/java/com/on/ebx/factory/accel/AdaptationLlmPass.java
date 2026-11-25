package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.accel.Difference.Nature;
import com.on.ebx.factory.core.PromptData;

/**
 * The prompts and the parser of the proposal passes (lot C1-C, docs/ACCELERATORS.md 6b and 16, LOT-C1.md section 4). Pure: no EBX
 * class, tested without a server.
 * <ul>
 * <li>pass 0: pack choice ({@code {"packs": [...], "options": [...], "why": "..."}});</li>
 * <li>pass 1: model (ADD, CHANGE, REMOVE, USE_BLOCK), at most {@link #CAP_MODEL} items;</li>
 * <li>pass 2: rules (USE_LIBRARY, ADD of a draft rule), at most {@link #CAP_RULES};</li>
 * <li>pass 3: one Layout Designer layout per table;</li>
 * <li>pass 4: workflows and roles (USE_WORKFLOW, ADD of a role), at most {@link #CAP_WORKFLOWS}.</li>
 * </ul>
 * One shot times out (Connectors read timeout, measured 2026-10-12): the prompts ask for one line per item, a "why" under 12 words
 * and a one-line "detail". The specification is the user's own text and is always fenced as data ({@link PromptData#fence}); the
 * model never sees a table value.
 */
public final class AdaptationLlmPass
{
	public static final int CAP_MODEL = 20;
	public static final int CAP_RULES = 12;
	public static final int CAP_WORKFLOWS = 8;
	/** Longest pack layout pasted into the forms prompt, per table. */
	static final int LAYOUT_PROMPT_MAX = 2500;

	public static final String SPECIFICATION_LABEL = "SPECIFICATION";
	public static final String MODEL_LABEL = "DECIDED MODEL";
	public static final String LIBRARIES_LABEL = "LIBRARY CATALOGUE";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AdaptationLlmPass()
	{
	}

	/** The item cap of a pass: fixed for 1, 2 and 4; the number of tables of the decided model for 3; 0 (none) for pass 0. */
	public static int cap(final int pPass, final AdaptationContext pContext)
	{
		switch (pPass)
		{
		case 1:
			return AdaptationLlmPass.CAP_MODEL;
		case 2:
			return AdaptationLlmPass.CAP_RULES;
		case 3:
			return pContext == null ? 0 : pContext.model().tables().size();
		case 4:
			return AdaptationLlmPass.CAP_WORKFLOWS;
		default:
			return 0;
		}
	}

	// ------------------------------------------------------------------------------------------------ prompts

	public static String systemPrompt(final int pPass)
	{
		return AdaptationLlmPass.systemPrompt(pPass, false);
	}

	/**
	 * @param pBlank the project started blank ("Skip the packs, start blank"): no pack, so the passes design the domain from the specification alone
	 *        (every model element is an ADD) instead of adapting a pack. Ignored for pass 0.
	 */
	public static String systemPrompt(final int pPass, final boolean pBlank)
	{
		final StringBuilder sb = new StringBuilder();
		if (pPass == 0)
		{
			sb.append("You choose the starting accelerator packs of a master-data domain on ON EBX. You are given the catalogue of packs and the customer's need. ")
				.append("Answer with ONE JSON object and nothing else (no code fence, no prose): {\"packs\": [pack keys], \"options\": [option keys], \"why\": \"one sentence\"}. ")
				.append("Use only pack keys and option keys of the catalogue; choose the smallest set that covers the need; a pack that another needs is listed by that pack, you may omit it. ")
				.append("Keep \"why\" under 30 words.");
			return PromptData.withNotice(sb.toString());
		}
		if (pBlank)
		{
			sb.append("You design a master-data domain of ON EBX from a customer's specification alone: there is NO starting pack, the model starts empty. ")
				.append("You propose its elements as ADD lines (and USE_BLOCK, USE_LIBRARY, USE_WORKFLOW where the catalogue has a fitting piece); a person decides each one, ")
				.append("so propose what the specification asks or clearly implies, and what such a domain cannot work without. ");
		}
		else
		{
			sb.append("You adapt an accelerator pack of ON EBX (a ready data model with its building blocks, rules and workflows) to a customer's specification. ")
				.append("You propose DIFFERENCES from the pack; a person decides each one, so propose only what the specification asks or clearly implies. ");
		}
		sb
			.append("Answer with ONE JSON array and nothing else (no code fence, no prose before or after). One line per item, an object with the keys: ")
			.append("\"nature\", \"what\" (a few words), \"where\" (an absolute path such as /root/Company/Purchasing/paymentTerms, never prose), \"detail\" (one JSON object on one line), ")
			.append(pBlank ? "\"why\" (under 12 words), \"pack\" (always \"\": there is no pack)" : "\"why\" (under 12 words), \"pack\" (the pack key the item adapts)");
		switch (pPass)
		{
		case 1:
			sb.append(", \"block\" (USE_BLOCK only). ")
				.append("This is pass 1, the MODEL. Natures: ADD (a table, group or field the pack lacks: detail {\"kind\":\"table|group|field\",\"fieldType\":\"string|int|decimal|date|dateTime|boolean|text\",\"maxLength\":n,\"mandatory\":true,\"target\":\"/root/Table\" for a foreign key; for a table \"fields\":[{\"name\":\"x\",\"type\":\"string\",\"maxLength\":n}]}; where is always an absolute path), ")
				.append("CHANGE (make mandatory, or change the length of, something the pack has: detail {\"property\":\"mandatory|maxLength\",\"value\":\"...\"}), ")
				.append("REMOVE (a pack table, group or field the specification does not need), USE_BLOCK (put a building block of the catalogue on a table: where = the table path, \"block\" = its key, detail {\"group\":\"name\"} if it must be renamed). ")
				.append("Before an ADD, check the blocks and the pack: a field a block already carries is a USE_BLOCK, never a new field. Never remove the main table of a pack. ")
				.append("Do NOT propose validation rules, workflows, forms or roles: they come in later passes. The guidance of the pack says how the domain is usually modelled: follow it.");
			if (pBlank)
			{
				sb.append(" With no pack there is nothing to CHANGE or REMOVE: propose each table as ONE ADD of kind table carrying all its fields in \"fields\" (an id key is added for you), ")
					.append("then the foreign keys between tables as ADD of kind field with \"target\", and USE_BLOCK where a block covers a need (an address, a contact, an identifier...).");
			}
			break;
		case 2:
			sb.append(", \"function\" (USE_LIBRARY only). ")
				.append("This is pass 2, the RULES on the decided model. Natures: USE_LIBRARY (a field is checked by a published library function: \"function\" = the fully qualified name exactly as listed, for example Fasttrack.lib.phone.isValid; ")
				.append("where = the field path; detail {\"exactLength\":n if the project requires an exact length before the library call, \"message\":\"the warning shown\"} or {}), ")
				.append("ADD (a new draft rule no library covers: where = the field or table it guards, detail {\"rule\":\"the rule in one sentence\"}; a person reviews it before it is ever published). ")
				.append("Never rewrite what a library does: wrap it. Do not propose a library on a field whose type already guarantees the format (a date library on a date field is useless). ")
				.append("Do NOT propose approvals, workflows, roles or model changes: they belong to other passes.");
			break;
		case 3:
			sb.append(". This is pass 3, the RECORD FORMS. Answer instead with an array of objects {\"table\": \"/root/Table\", \"layout\": {the Layout Designer JSON of that table, minified}, \"why\": \"...\"}, ")
				.append("at most ONE per table. Start from the pack layout given for the table and change only what the decided model requires (fields added, removed or renamed, blocks placed); ")
				.append("reference fields by their path inside the table (for example /Identification/name); put each block of the catalogue in its panel; never place the technical associations of the add-ons. ")
				.append("Give a layout only for the tables whose layout must change or that have none; keep the answer short.");
			break;
		case 4:
			sb.append(", \"workflow\" (USE_WORKFLOW only). ")
				.append("This is pass 4, the WORKFLOWS and ROLES on the decided model. Natures: USE_WORKFLOW (a workflow type of the catalogue applied to a table: \"workflow\" = its key, where = the table path, ")
				.append("detail = the parameters of the type, as listed, flat, for example {\"requester\":\"Requester\",\"reviewers\":[\"Purchasing\",\"Finance\"],\"mode\":\"sequential\",\"acceptLabel\":\"...\",\"rejectLabel\":\"...\"}), ADD (a role the workflow needs: what = \"role <name>\", detail {\"role\":\"name\",\"does\":\"one phrase\"}). ")
				.append("Use only the workflow types listed; describe the business steps, not EBX settings.");
			break;
		default:
			throw new IllegalArgumentException("pass " + pPass);
		}
		if (pPass != 3)
		{
			sb.append(" At most ").append(pPass == 1 ? AdaptationLlmPass.CAP_MODEL : pPass == 2 ? AdaptationLlmPass.CAP_RULES : AdaptationLlmPass.CAP_WORKFLOWS)
				.append(" items. An empty array [] is a valid answer.");
		}
		sb.append(" Keep every string short: a long answer is cut off and lost.");
		return PromptData.withNotice(sb.toString());
	}

	public static String userPrompt(final int pPass, final AdaptationContext pContext)
	{
		final StringBuilder sb = new StringBuilder();
		final PackCatalog catalogue = pContext.catalogue();
		if (pPass == 0)
		{
			sb.append("CATALOGUE OF PACKS AND BLOCKS (one line each)\n").append(catalogue.promptText()).append('\n');
			sb.append("The customer's need, in their own words:\n");
			sb.append(PromptData.fence(AdaptationLlmPass.SPECIFICATION_LABEL, pContext.specification()));
			return sb.toString();
		}
		sb.append("PACKS CHOSEN\n");
		if (pContext.packs().isEmpty())
		{
			sb.append("none: the project starts blank, the model is built from the specification alone\n");
		}
		for (final Pack p : pContext.packs())
		{
			sb.append(p.promptLine()).append('\n');
		}
		sb.append("OPTIONS TAKEN: ").append(pContext.options().isEmpty() ? "none" : ModelPaths.join(pContext.options())).append("\n\n");
		sb.append("The customer's specification:\n");
		sb.append(PromptData.fence(AdaptationLlmPass.SPECIFICATION_LABEL, pContext.specification())).append('\n');
		switch (pPass)
		{
		case 1:
			if (pContext.packs().isEmpty())
			{
				sb.append("PACK CORE: none, the model is empty\n");
				if (!pContext.decided().isEmpty())
				{
					sb.append("The model as decided so far:\n").append(PromptData.fence(AdaptationLlmPass.MODEL_LABEL, pContext.model().render())).append('\n');
				}
			}
			else
			{
				sb.append("PACK CORE (tables, groups and fields with their types)\n").append(ModelPaths.of(catalogue, pContext.packs(), null).render()).append('\n');
			}
			for (final Pack p : pContext.packs())
			{
				sb.append("GUIDANCE OF PACK ").append(p.key()).append('\n').append(p.guidance().trim()).append("\n\n");
			}
			sb.append("BUILDING BLOCKS (one line each)\n");
			for (final Block b : catalogue.blocks())
			{
				sb.append(b.promptLine()).append('\n');
			}
			sb.append("\nPropose the differences of the MODEL, at most ").append(AdaptationLlmPass.CAP_MODEL).append(" items, one line each.\n");
			break;
		case 2:
			sb.append("The model as decided so far:\n").append(PromptData.fence(AdaptationLlmPass.MODEL_LABEL, pContext.model().render())).append('\n');
			sb.append("Published libraries you may use (one line each):\n")
				.append(PromptData.fence(AdaptationLlmPass.LIBRARIES_LABEL, catalogue.libraries().promptText())).append('\n');
			sb.append("Propose the RULES, at most ").append(AdaptationLlmPass.CAP_RULES).append(" items, one line each.\n");
			break;
		case 3:
			sb.append("The model as decided so far:\n").append(PromptData.fence(AdaptationLlmPass.MODEL_LABEL, pContext.model().render())).append('\n');
			for (final Pack p : pContext.packs())
			{
				for (final Map.Entry<String, JsonNode> layout : p.layouts().entrySet())
				{
					String json = layout.getValue().toString();
					if (json.length() > AdaptationLlmPass.LAYOUT_PROMPT_MAX)
					{
						json = json.substring(0, AdaptationLlmPass.LAYOUT_PROMPT_MAX) + "...(cut)";
					}
					sb.append("PACK LAYOUT of ").append(layout.getKey()).append(": ").append(json).append('\n');
				}
			}
			sb.append("\nBLOCK PANELS (a block is shown in one panel)\n");
			for (final Block b : catalogue.blocks())
			{
				if (b.layoutPanel() != null)
				{
					sb.append(b.key()).append(": ").append(b.layoutPanel().toString()).append('\n');
				}
			}
			sb.append("\nPropose the layouts, at most one per table (").append(pContext.model().tables().size()).append(" tables).\n");
			break;
		case 4:
			sb.append("The model as decided so far:\n").append(PromptData.fence(AdaptationLlmPass.MODEL_LABEL, pContext.model().render())).append('\n');
			sb.append("WORKFLOW TYPES (one line each)\n").append(catalogue.workflowTypes().promptText()).append('\n');
			for (final Pack p : pContext.packs())
			{
				if (p.workflows() != null && !p.workflows().isMissingNode())
				{
					sb.append("WORKFLOWS THE PACK ALREADY USES (").append(p.key()).append("): ").append(p.workflows().toString()).append('\n');
				}
			}
			sb.append("\nPropose the WORKFLOWS and ROLES, at most ").append(AdaptationLlmPass.CAP_WORKFLOWS).append(" items, one line each.\n");
			break;
		default:
			throw new IllegalArgumentException("pass " + pPass);
		}
		return sb.toString();
	}

	/** The re-ask: the same prompt, then why the previous answer was refused. */
	public static String reaskPrompt(final String pPrompt, final String pRefusals)
	{
		return pPrompt + "\nYour previous answer had items that were refused:\n" + pRefusals + "Answer again with the JSON array only, correcting these items and keeping the others.\n";
	}

	/** The re-ask after an answer that could not be read (cut off, or not JSON). */
	public static String reaskUnreadable(final String pPrompt, final boolean pTruncated)
	{
		return pPrompt + "\nYour previous answer " + (pTruncated ? "was cut off before the end" : "was not a JSON array that could be read")
			+ ". Answer again with the JSON array only, with fewer items and shorter strings.\n";
	}

	// ------------------------------------------------------------------------------------------------ parsing

	/** What {@link #parseReport} found. */
	public static final class Parsed
	{
		public final List<Difference> items = new ArrayList<Difference>();
		/** Items beyond the cap of the pass, ignored. */
		public int overCap;
		/** Items that were not objects or whose nature was unknown. */
		public int malformed;
		/** False when no JSON could be read at all. */
		public boolean readable;
	}

	/** Items of the answer of pass 1..4, capped; empty when the text holds no JSON. Tolerant to code fences and prose around the JSON. */
	public static List<Difference> parse(final int pPass, final String pText)
	{
		return AdaptationLlmPass.parseReport(pPass, pText, Integer.MAX_VALUE).items;
	}

	/** @param pPass1To4 the pass; @param pMaxLayouts the number of tables (pass 3 only; the other passes use their fixed cap). */
	public static Parsed parseReport(final int pPass, final String pText, final int pMaxLayouts)
	{
		if (pPass < 1 || pPass > 4)
		{
			throw new IllegalArgumentException("pass " + pPass + " has no list of differences");
		}
		final Parsed out = new Parsed();
		final JsonNode root = AdaptationLlmPass.readJson(pText);
		if (root == null)
		{
			return out;
		}
		out.readable = true;
		final JsonNode array = AdaptationLlmPass.itemsOf(root);
		final int cap = pPass == 3 ? pMaxLayouts : pPass == 1 ? AdaptationLlmPass.CAP_MODEL : pPass == 2 ? AdaptationLlmPass.CAP_RULES : AdaptationLlmPass.CAP_WORKFLOWS;
		final Set<String> layoutTables = new HashSet<String>();
		for (final JsonNode item : array)
		{
			if (!item.isObject())
			{
				out.malformed++;
				continue;
			}
			final Difference d = pPass == 3 ? AdaptationLlmPass.layoutOf(item) : AdaptationLlmPass.differenceOf(pPass, item);
			if (d == null)
			{
				out.malformed++;
				continue;
			}
			if (pPass == 3 && !layoutTables.add(d.where()))
			{
				out.overCap++; // one layout per table: the first wins
				continue;
			}
			if (out.items.size() >= cap)
			{
				out.overCap++;
				continue;
			}
			out.items.add(d);
		}
		return out;
	}

	/** Pass 0: the pack choice; null when the text holds no readable object with a "packs" array. */
	public static PackChoice parseChoice(final String pText)
	{
		final JsonNode root = AdaptationLlmPass.readJson(pText);
		if (root == null || !root.isObject() || !root.path("packs").isArray())
		{
			return null;
		}
		return new PackChoice(strings(root.path("packs")), strings(root.path("options")), root.path("why").asText(""));
	}

	private static List<String> strings(final JsonNode pArray)
	{
		final List<String> out = new ArrayList<String>();
		for (final JsonNode n : pArray)
		{
			if (n.isTextual() && !n.asText().trim().isEmpty())
			{
				out.add(n.asText().trim());
			}
		}
		return out;
	}

	private static Difference differenceOf(final int pPass, final JsonNode pItem)
	{
		final Nature nature = Nature.parse(pItem.path("nature").asText(null));
		if (nature == null)
		{
			return null;
		}
		final String where = text(pItem, "where", "path");
		String what = text(pItem, "what", "name");
		if (what.isEmpty())
		{
			what = nature.name().toLowerCase(Locale.ROOT) + " " + where;
		}
		return new Difference(pPass, nature, what, where, Difference.PlaceState.PLACED, DetailShape.normalise(pPass, nature, what, detailOf(pItem.get("detail"))), text(pItem, "why", "reason"),
			text(pItem, "pack", "packKey"), text(pItem, "block", "blockKey"), text(pItem, "function", "libraryFunction", "library"),
			text(pItem, "workflow", "workflowType"));
	}

	private static Difference layoutOf(final JsonNode pItem)
	{
		final String table = text(pItem, "table", "where");
		if (table.isEmpty() || !pItem.hasNonNull("layout"))
		{
			return null;
		}
		final Nature nature = Nature.parse(pItem.path("nature").asText("CHANGE"));
		return new Difference(3, nature == Nature.ADD ? Nature.ADD : Nature.CHANGE, "layout of " + table, table, Difference.PlaceState.PLACED,
			DetailShape.normalise(3, Nature.CHANGE, "", detailOf(pItem.get("layout"))), text(pItem, "why", "reason"), text(pItem, "pack", "packKey"), "", "", "");
	}

	/** A detail may arrive as an object, or as a string holding JSON, or as plain text (kept as {"text": ...}). */
	private static JsonNode detailOf(final JsonNode pDetail)
	{
		if (pDetail == null || pDetail.isNull())
		{
			return null;
		}
		if (pDetail.isTextual())
		{
			final String s = pDetail.asText().trim();
			if (s.isEmpty())
			{
				return null;
			}
			try
			{
				final JsonNode parsed = AdaptationLlmPass.MAPPER.readTree(s);
				if (parsed != null && parsed.isContainerNode())
				{
					return parsed;
				}
			}
			catch (final Exception ex)
			{
				// plain text below
			}
			return AdaptationLlmPass.MAPPER.createObjectNode().put("text", s);
		}
		return pDetail;
	}

	private static String text(final JsonNode pItem, final String... pNames)
	{
		for (final String name : pNames)
		{
			final JsonNode n = pItem.get(name);
			if (n != null && !n.isNull())
			{
				final String s = n.isValueNode() ? n.asText() : n.toString();
				if (!s.trim().isEmpty())
				{
					return s.trim();
				}
			}
		}
		return "";
	}

	/** The array of items in a parsed answer: the root array, or the first array of the root object, or the object itself as one item. */
	private static JsonNode itemsOf(final JsonNode pRoot)
	{
		if (pRoot.isArray())
		{
			return pRoot;
		}
		if (pRoot.isObject())
		{
			for (final String key : new String[] { "differences", "items", "layouts", "rules", "workflows", "results" })
			{
				if (pRoot.path(key).isArray())
				{
					return pRoot.get(key);
				}
			}
			final Iterator<Map.Entry<String, JsonNode>> it = pRoot.fields();
			while (it.hasNext())
			{
				final JsonNode v = it.next().getValue();
				if (v.isArray())
				{
					return v;
				}
			}
			final com.fasterxml.jackson.databind.node.ArrayNode single = AdaptationLlmPass.MAPPER.createArrayNode();
			single.add(pRoot);
			return single;
		}
		return AdaptationLlmPass.MAPPER.createArrayNode();
	}

	/**
	 * The JSON in a model's answer: cut from the first { or [ to the last } or ] (a code fence or prose around it is tolerated); when that cut does not
	 * parse (prose with a brace in it), the other bracket kind is tried. Null when there is none. A cut-off answer has no closing bracket and gives null.
	 */
	static JsonNode readJson(final String pText)
	{
		if (pText == null)
		{
			return null;
		}
		final int firstArray = pText.indexOf('[');
		final int firstObject = pText.indexOf('{');
		final int lastArray = pText.lastIndexOf(']');
		final int lastObject = pText.lastIndexOf('}');
		final boolean arrayFirst = firstArray >= 0 && (firstObject < 0 || firstArray < firstObject);
		if (arrayFirst ? lastArray <= firstArray : firstObject >= 0 && lastObject <= firstObject)
		{
			return null; // the first bracket never closes: the answer was cut off
		}
		final int[][] tries = arrayFirst ? new int[][] { { firstArray, lastArray }, { firstObject, lastObject } }
			: new int[][] { { firstObject, lastObject }, { firstArray, lastArray } };
		for (final int[] t : tries)
		{
			if (t[0] >= 0 && t[1] > t[0])
			{
				try
				{
					return AdaptationLlmPass.MAPPER.readTree(pText.substring(t[0], t[1] + 1));
				}
				catch (final Exception ex)
				{
					// try the other cut
				}
			}
		}
		return null;
	}
}
