package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** Turns blocks and pack cores into {@code apply_data_model_patch} operations and script bodies. Pure. */
public final class PackOps
{
	/** Operations the data-model patch vocabulary knows (`CLAUDE.md` section 5, `MasterModel`, `StagingModelDiff`). */
	public static final Set<String> KNOWN_OPS = Collections.unmodifiableSet(new HashSet<String>(Arrays.asList("create_complex_type",
			"create_simple_type", "create_table", "create_group", "create_field", "set_primary_key", "add_foreign_key", "set_linked_field",
			"set_association", "set_element_property", "unset_element_property", "delete_element")));

	private PackOps()
	{
	}

	/**
	 * Operations adding a block to a table: {@code create_group}, then per field {@code create_field} (+ {@code maxLength}).
	 * {@code readOnly} is NOT a patch property: it is carried by the block's layout panel and the trigger that stamps the field.
	 *
	 * @param pGroupName the group name chosen at placement; null or blank means the block's own default
	 */
	public static List<ObjectNode> opsForBlock(final Block pBlock, final String pTablePath, final String pGroupName)
	{
		final String group = PackOps.groupOf(pBlock, pGroupName);
		final List<ObjectNode> ops = new ArrayList<ObjectNode>();
		final ObjectNode createGroup = PackOps.op("create_group");
		createGroup.with("arguments").put("table_path", pTablePath).put("group_name", group);
		ops.add(createGroup);
		for (final Block.Field f : pBlock.fields())
		{
			final String fieldPath = pTablePath + "/" + group + "/" + f.name();
			final ObjectNode createField = PackOps.op("create_field");
			createField.with("arguments").put("field_path", fieldPath).put("field_type", f.type());
			ops.add(createField);
			if (f.maxLength() != null)
			{
				final ObjectNode max = PackOps.op("set_element_property");
				max.with("arguments").put("element_path", fieldPath).put("property", "maxLength").put("value", String.valueOf(f.maxLength()));
				ops.add(max);
			}
		}
		return ops;
	}

	/**
	 * The body of one script of a block with {@code ${table}} (the table NAME, last segment of its path, as the typed record
	 * {@code .Table} wants it) and {@code ${group}} replaced.
	 *
	 * @throws IllegalArgumentException when the block has no such script
	 * @throws IllegalStateException when a placeholder remains unresolved
	 */
	public static String scriptBody(final Block pBlock, final String pScriptName, final String pTableName, final String pGroupName)
	{
		final Block.Script script = pBlock.script(pScriptName);
		if (script == null)
		{
			throw new IllegalArgumentException("Block " + pBlock.key() + " has no script named " + pScriptName);
		}
		final String table = pTableName.indexOf('/') >= 0 ? pTableName.substring(pTableName.lastIndexOf('/') + 1) : pTableName;
		final String body = script.body().replace("${table}", table).replace("${group}", PackOps.groupOf(pBlock, pGroupName));
		if (body.contains("${"))
		{
			throw new IllegalStateException("Script " + pScriptName + " of block " + pBlock.key() + " keeps an unresolved placeholder: "
					+ body.substring(body.indexOf("${"), Math.min(body.length(), body.indexOf("${") + 20)));
		}
		return body;
	}

	/**
	 * The pack operations minus every operation on a removed path (the path itself and what lies under it; a foreign key or an
	 * association pointing at a removed table goes too), plus the CHANGE differences: for each element path a map
	 * {@code property -> value}; an existing {@code set_element_property} on the same path and property is rewritten in place,
	 * otherwise one is appended. The core is not modified: operations are deep copies.
	 */
	public static List<ObjectNode> coreOps(final PackCore pCore, final Set<String> pRemovedPaths, final Map<String, ObjectNode> pChangedProperties)
	{
		final Set<String> removed = pRemovedPaths == null ? Collections.<String> emptySet() : pRemovedPaths;
		final List<ObjectNode> out = new ArrayList<ObjectNode>();
		for (final ObjectNode op : pCore.ops())
		{
			if (!PackOps.isRemoved(op, removed))
			{
				out.add(op.deepCopy());
			}
		}
		if (pChangedProperties != null)
		{
			for (final Map.Entry<String, ObjectNode> change : pChangedProperties.entrySet())
			{
				final String path = change.getKey();
				if (PackOps.under(path, removed))
				{
					continue;
				}
				final Iterator<Map.Entry<String, JsonNode>> props = change.getValue().fields();
				while (props.hasNext())
				{
					final Map.Entry<String, JsonNode> prop = props.next();
					final String value = prop.getValue().asText();
					boolean replaced = false;
					for (final ObjectNode op : out)
					{
						final JsonNode args = op.path("arguments");
						if ("set_element_property".equals(op.path("op").asText()) && path.equals(args.path("element_path").asText())
								&& prop.getKey().equals(args.path("property").asText()))
						{
							((ObjectNode) args).put("value", value);
							replaced = true;
						}
					}
					if (!replaced)
					{
						final ObjectNode added = PackOps.op("set_element_property");
						added.with("arguments").put("element_path", path).put("property", prop.getKey()).put("value", value);
						out.add(added);
					}
				}
			}
		}
		return out;
	}

	private static String groupOf(final Block pBlock, final String pGroupName)
	{
		return pGroupName == null || pGroupName.trim().isEmpty() ? pBlock.group() : pGroupName.trim();
	}

	private static ObjectNode op(final String pName)
	{
		final ObjectNode op = Res.MAPPER.createObjectNode();
		op.put("op", pName);
		op.putObject("arguments");
		return op;
	}

	/** The element an operation is about, as a path. */
	static String subject(final ObjectNode pOp)
	{
		final JsonNode a = pOp.path("arguments");
		for (final String name : new String[] { "element_path", "field_path" })
		{
			if (a.hasNonNull(name))
			{
				return a.get(name).asText();
			}
		}
		if ("create_group".equals(pOp.path("op").asText()) && a.hasNonNull("table_path") && a.hasNonNull("group_name"))
		{
			return a.get("table_path").asText() + "/" + a.get("group_name").asText();
		}
		return a.path("table_path").asText(null);
	}

	private static boolean isRemoved(final ObjectNode pOp, final Set<String> pRemoved)
	{
		if (pRemoved.isEmpty())
		{
			return false;
		}
		final String subject = PackOps.subject(pOp);
		if (subject != null && PackOps.under(subject, pRemoved))
		{
			return true;
		}
		for (final String target : new String[] { "target_table_path", "target_table" })
		{
			final String t = pOp.path("arguments").path(target).asText(null);
			if (t != null && PackOps.under(t, pRemoved))
			{
				return true;
			}
		}
		return false;
	}

	/** True when the path is a removed path or lies under one. */
	private static boolean under(final String pPath, final Set<String> pRemoved)
	{
		for (final String r : pRemoved)
		{
			if (pPath.equals(r) || pPath.startsWith(r + "/"))
			{
				return true;
			}
		}
		return false;
	}
}
