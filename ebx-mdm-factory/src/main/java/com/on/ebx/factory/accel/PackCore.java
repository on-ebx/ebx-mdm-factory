package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** {@code core.json} of a pack: the patch operations, block placements, labels and default labels. */
public final class PackCore
{
	private final List<ObjectNode> ops;
	private final List<BlockPlacement> placements;
	private final Map<String, Map<String, String>> labels;
	private final Map<String, String> defaultLabels;

	PackCore(final JsonNode pRoot, final String pWhere)
	{
		final List<ObjectNode> o = new ArrayList<ObjectNode>();
		final JsonNode opsNode = pRoot.get("ops");
		if (opsNode == null || !opsNode.isArray())
		{
			throw new IllegalStateException("Accelerator file " + pWhere + ": 'ops' must be an array");
		}
		for (final JsonNode op : opsNode)
		{
			if (!op.isObject() || !op.hasNonNull("op") || !op.path("arguments").isObject())
			{
				throw new IllegalStateException("Accelerator file " + pWhere + ": every op needs 'op' and an 'arguments' object, got " + op);
			}
			o.add((ObjectNode) op);
		}
		this.ops = Collections.unmodifiableList(o);
		final List<BlockPlacement> p = new ArrayList<BlockPlacement>();
		for (final JsonNode item : pRoot.path("blockPlacements"))
		{
			p.add(new BlockPlacement(Res.required(item, "block", pWhere), Res.required(item, "table", pWhere), Res.str(item, "group")));
		}
		this.placements = Collections.unmodifiableList(p);
		final Map<String, Map<String, String>> l = new LinkedHashMap<String, Map<String, String>>();
		final java.util.Iterator<Map.Entry<String, JsonNode>> it = pRoot.path("labels").fields();
		while (it.hasNext())
		{
			final Map.Entry<String, JsonNode> e = it.next();
			l.put(e.getKey(), Collections.unmodifiableMap(Res.localized(e.getValue())));
		}
		this.labels = Collections.unmodifiableMap(l);
		this.defaultLabels = Collections.unmodifiableMap(Res.localized(pRoot.get("defaultLabels")));
	}

	/** The operations in the {@code apply_data_model_patch} vocabulary, in order. Do not mutate: copy first. */
	public List<ObjectNode> ops()
	{
		return this.ops;
	}

	public List<BlockPlacement> blockPlacements()
	{
		return this.placements;
	}

	/** Element path to {locale: label}. */
	public Map<String, Map<String, String>> labels()
	{
		return this.labels;
	}

	/** Table path to default-label template, e.g. {@code ${./name}}. */
	public Map<String, String> defaultLabels()
	{
		return this.defaultLabels;
	}

	/** The paths created by {@code create_table}, in order. */
	public List<String> tablePaths()
	{
		return this.argumentsOf("create_table", "table_path");
	}

	/** The paths created by {@code create_field}, in order (duplicates kept, so a test can catch them). */
	public List<String> fieldPaths()
	{
		return this.argumentsOf("create_field", "field_path");
	}

	private List<String> argumentsOf(final String pOp, final String pArgument)
	{
		final List<String> out = new ArrayList<String>();
		for (final ObjectNode op : this.ops)
		{
			if (pOp.equals(op.path("op").asText()))
			{
				out.add(op.path("arguments").path(pArgument).asText());
			}
		}
		return out;
	}
}
