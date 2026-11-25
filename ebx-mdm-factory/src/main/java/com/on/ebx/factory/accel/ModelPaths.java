package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The model as it stands after some packs and some decided differences: the tables, groups and fields (path to type) the passes
 * address, and the compact text listing the prompts paste in ("a compact listing: table paths and fields with types", no values).
 * Pure. Used by {@link AdaptationLlmPass} (the listing) and {@link DifferenceCheck} (path resolution).
 */
public final class ModelPaths
{
	/** {@code /root/Name} followed by any number of {@code /name}. */
	static final Pattern PATH = Pattern.compile("/root(/[A-Za-z][A-Za-z0-9_]*)+");

	private final Map<String, String> tables = new LinkedHashMap<String, String>();
	private final Set<String> groups = new LinkedHashSet<String>();
	private final Map<String, String> fields = new LinkedHashMap<String, String>();
	private final Map<String, String> foreignKeys = new LinkedHashMap<String, String>();
	private final Set<String> mainTables = new LinkedHashSet<String>();
	/** Block key to the table paths it sits on, for the listing. */
	private final Map<String, List<String>> blocksOn = new LinkedHashMap<String, List<String>>();

	private ModelPaths()
	{
	}

	/**
	 * @param pDecided the differences standing after the previous passes (not rejected): ADD and REMOVE and USE_BLOCK of pass 1 change the paths
	 */
	public static ModelPaths of(final PackCatalog pCatalogue, final List<Pack> pPacks, final List<Difference> pDecided)
	{
		final ModelPaths m = new ModelPaths();
		final Set<String> removed = new LinkedHashSet<String>();
		if (pDecided != null)
		{
			for (final Difference d : pDecided)
			{
				if (d.nature() == Difference.Nature.REMOVE && !d.where().isEmpty())
				{
					removed.add(d.where());
				}
			}
		}
		for (final Pack pack : pPacks)
		{
			m.mainTables.add(pack.mainTable());
			final List<ObjectNode> ops = PackOps.coreOps(pack.core(), removed, null);
			for (final ObjectNode op : ops)
			{
				m.addOp(op);
			}
			for (final BlockPlacement bp : pack.core().blockPlacements())
			{
				final Block block = pCatalogue == null ? null : pCatalogue.block(bp.block());
				if (block != null && !removed.contains(bp.table()))
				{
					m.addBlock(block, bp.table(), bp.group());
				}
			}
		}
		if (pDecided != null)
		{
			for (final Difference d : pDecided)
			{
				m.apply(pCatalogue, d);
			}
		}
		return m;
	}

	private void addOp(final ObjectNode pOp)
	{
		final String name = pOp.path("op").asText();
		final JsonNode a = pOp.path("arguments");
		if ("create_table".equals(name))
		{
			this.tables.put(a.path("table_path").asText(), "");
		}
		else if ("create_group".equals(name))
		{
			this.groups.add(a.path("table_path").asText() + "/" + a.path("group_name").asText());
		}
		else if ("create_field".equals(name))
		{
			this.fields.put(a.path("field_path").asText(), a.path("field_type").asText("string"));
		}
		else if ("add_foreign_key".equals(name))
		{
			this.foreignKeys.put(a.path("field_path").asText(), a.path("target_table_path").asText(a.path("target_table").asText()));
		}
	}

	private void addBlock(final Block pBlock, final String pTable, final String pGroupName)
	{
		final String group = pGroupName == null || pGroupName.trim().isEmpty() ? pBlock.group() : pGroupName.trim();
		this.groups.add(pTable + "/" + group);
		for (final Block.Field f : pBlock.fields())
		{
			this.fields.put(pTable + "/" + group + "/" + f.name(), f.type());
		}
		List<String> on = this.blocksOn.get(pBlock.key());
		if (on == null)
		{
			on = new ArrayList<String>();
			this.blocksOn.put(pBlock.key(), on);
		}
		on.add(pTable);
	}

	private void apply(final PackCatalog pCatalogue, final Difference pDifference)
	{
		final String where = pDifference.where();
		if (!ModelPaths.PATH.matcher(where).matches() || pDifference.pass() != 1)
		{
			return;
		}
		final JsonNode detail = pDifference.detail();
		switch (pDifference.nature())
		{
		case ADD:
			final String kind = detail == null ? "" : detail.path("kind").asText("");
			final int depth = where.split("/").length - 2; // /root/T = 1
			if ("table".equals(kind) || kind.isEmpty() && depth == 1)
			{
				this.tables.put(where, "");
				// the fields an ADD of a table carries, and the id key DomainPlan adds to it: later passes address them (a blank start has no other fields)
				if ("table".equals(kind))
				{
					this.fields.put(where + "/id", "int");
					for (final JsonNode f : detail.path("fields"))
					{
						final String name = f.path("name").asText("");
						if (!name.isEmpty())
						{
							this.fields.put(where + "/" + name, DetailShape.fieldType(f));
						}
					}
				}
			}
			else if ("group".equals(kind))
			{
				this.groups.add(where);
			}
			else
			{
				this.fields.put(where, DetailShape.fieldType(detail));
			}
			break;
		case CHANGE:
			if (detail != null && (detail.hasNonNull("type") || detail.hasNonNull("fieldType")) && this.fields.containsKey(where))
			{
				this.fields.put(where, DetailShape.fieldType(detail));
			}
			break;
		case REMOVE:
			this.removeUnder(where);
			break;
		case USE_BLOCK:
			final Block block = pCatalogue == null ? null : pCatalogue.block(pDifference.blockKey());
			if (block != null)
			{
				this.addBlock(block, where, detail == null ? null : detail.path("group").asText(null));
			}
			break;
		default:
			break;
		}
	}

	private void removeUnder(final String pPath)
	{
		this.tables.keySet().removeIf(p -> p.equals(pPath) || p.startsWith(pPath + "/"));
		this.groups.removeIf(p -> p.equals(pPath) || p.startsWith(pPath + "/"));
		this.fields.keySet().removeIf(p -> p.equals(pPath) || p.startsWith(pPath + "/"));
	}

	public Set<String> tables()
	{
		return Collections.unmodifiableSet(this.tables.keySet());
	}

	public Set<String> groups()
	{
		return Collections.unmodifiableSet(this.groups);
	}

	/** Field path to type. */
	public Map<String, String> fields()
	{
		return Collections.unmodifiableMap(this.fields);
	}

	public Set<String> mainTables()
	{
		return Collections.unmodifiableSet(this.mainTables);
	}

	/** Tables, groups and fields: everything a {@code where} can name. */
	public Set<String> allPaths()
	{
		final Set<String> all = new LinkedHashSet<String>(this.tables.keySet());
		all.addAll(this.groups);
		all.addAll(this.fields.keySet());
		return all;
	}

	public boolean has(final String pPath)
	{
		return this.tables.containsKey(pPath) || this.groups.contains(pPath) || this.fields.containsKey(pPath);
	}

	/**
	 * One line per table: {@code /root/Company: id:int, Identification{name:string, ...}, legalForm:string->/root/ReferenceData/LegalForm}.
	 * Names and types only, never a value.
	 */
	public String render()
	{
		final StringBuilder sb = new StringBuilder();
		for (final String table : this.tables.keySet())
		{
			sb.append(table).append(this.mainTables.contains(table) ? " (main)" : "").append(": ");
			final List<String> parts = new ArrayList<String>();
			final Set<String> done = new LinkedHashSet<String>();
			for (final Map.Entry<String, String> f : this.fields.entrySet())
			{
				final String path = f.getKey();
				if (!path.startsWith(table + "/"))
				{
					continue;
				}
				final String rest = path.substring(table.length() + 1);
				final int slash = rest.indexOf('/');
				if (slash < 0)
				{
					parts.add(this.fieldText(rest, path));
				}
				else
				{
					final String group = rest.substring(0, slash);
					if (done.add(group))
					{
						final List<String> inner = new ArrayList<String>();
						for (final Map.Entry<String, String> g : this.fields.entrySet())
						{
							if (g.getKey().startsWith(table + "/" + group + "/"))
							{
								inner.add(this.fieldText(g.getKey().substring(table.length() + group.length() + 2), g.getKey()));
							}
						}
						parts.add(group + "{" + join(inner) + "}");
					}
				}
			}
			sb.append(join(parts)).append('\n');
		}
		for (final Map.Entry<String, List<String>> b : this.blocksOn.entrySet())
		{
			sb.append("block ").append(b.getKey()).append(" on ").append(join(b.getValue())).append('\n');
		}
		return sb.toString();
	}

	private String fieldText(final String pName, final String pPath)
	{
		final String fk = this.foreignKeys.get(pPath);
		return pName + ":" + this.fields.get(pPath) + (fk == null ? "" : "->" + fk);
	}

	static String join(final List<String> pItems)
	{
		final StringBuilder sb = new StringBuilder();
		for (int i = 0; i < pItems.size(); i++)
		{
			sb.append(i > 0 ? ", " : "").append(pItems.get(i));
		}
		return sb.toString();
	}
}
