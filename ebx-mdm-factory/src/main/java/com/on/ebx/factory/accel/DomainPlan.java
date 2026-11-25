package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Everything the generation of one domain (lot C1-D) will write, computed from the catalogue, the packs and options chosen, and the ACCEPTED
 * {@code Difference} rows. Pure: no EBX class, nothing is called; {@code ebx.DomainGenerator} executes the plan step by step.
 * <p>
 * Reading of a decided difference ({@link Decision}; the shapes of the {@code detail} JSON are the ones documented on {@link Difference}, which the
 * proposal passes write and {@link DetailShape} normalises):
 * <ul>
 * <li>{@code REMOVE}: {@code where} = a table, group or field path of the pack core (everything under it goes).</li>
 * <li>{@code CHANGE}: {@code where} = an element path of the core, {@code detail.property} + {@code detail.value}, or {@code detail.properties} =
 * {@code {property: value}}; or {@code detail.layout} = a whole Layout Designer JSON for the table {@code where}.</li>
 * <li>{@code USE_BLOCK}: {@code blockKey}, {@code where} = a table path, {@code detail.group} = the group name (optional).</li>
 * <li>{@code USE_LIBRARY}: {@code libraryFunction} = {@code Fasttrack.lib.validator.checkdigit.luhn.isValid}, {@code where} = a field path,
 * {@code detail.exactLength} (int, the project rule wrapped before the library call), {@code detail.message}.</li>
 * <li>{@code USE_WORKFLOW}: {@code workflowType}, {@code where} = the table, {@code detail} = the parameters of the type.</li>
 * <li>{@code ADD}: {@code detail.ops} (patch operations as they are), or {@code detail.kind} = {@code table} ({@code fields}: name, type,
 * maxLength) / {@code group} / {@code field} ({@code fieldType}, {@code maxLength}, {@code mandatory}, {@code target} = a foreign key) / {@code rule} (a draft rule, {@code description},
 * optional {@code script}) / {@code role} ({@code role}, {@code create}, {@code overwrite}, {@code delete}); or {@code detail.layout}.</li>
 * </ul>
 */
public final class DomainPlan
{
	/** A pack the project chose, with its options. */
	public static final class PackChoice
	{
		public final String packKey;
		public final List<String> options;

		public PackChoice(final String pPackKey, final List<String> pOptions)
		{
			this.packKey = pPackKey;
			this.options = pOptions == null ? new ArrayList<String>() : pOptions;
		}
	}

	/** An ACCEPTED difference, as the generator reads it. */
	public static final class Decision
	{
		public String id;
		public int pass;
		public String nature;
		public String what;
		public String where;
		public String packKey;
		public String blockKey;
		public String libraryFunction;
		public String workflowType;
		public JsonNode detail;

		public JsonNode detail()
		{
			return this.detail == null ? Res.MAPPER.createObjectNode() : this.detail;
		}
	}

	/** The names of the generated artifacts of a project. */
	public static final class Names
	{
		public final String model;
		public final String publication;
		public final String dataspace;
		public final String dataset;

		Names(final String pProjectCode)
		{
			final String code = DomainPlan.identifier(pProjectCode);
			this.model = code + "_Domain";
			this.publication = this.model;
			this.dataspace = code + "_Domain";
			this.dataset = "Domain";
		}

		public String workflowModel(final String pProjectCode, final String pName)
		{
			return DomainPlan.identifier(pProjectCode) + "_" + DomainPlan.identifier(pName);
		}
	}

	/** One script to declare, author and publish. */
	public static final class ScriptItem
	{
		/** {@code trigger} or {@code constraint}. */
		public String kind;
		/** {@code beforeCreate}, {@code beforeModify}, {@code ConstraintOnNode}, {@code ConstraintOnTable}. */
		public String type;
		public String name;
		/** {@code table} or {@code node}. */
		public String scope;
		public String tablePath;
		/** The table (trigger, table constraint) or the field (node constraint) the script is declared on. */
		public String elementPath;
		public String idePath;
		public String body;
		/** A draft: authored, flagged, not counted as published until a person confirmed it. */
		public boolean draft;
		/** {@code block:<key>}, {@code library:<function>}, {@code rule:<difference id>}. */
		public String source;
	}

	/** A Layout Designer form for a table, {@code ${publication}} still to substitute (see {@code ebx.LayoutOps}). */
	public static final class LayoutItem
	{
		public String tablePath;
		public ObjectNode layout;
	}

	/** A workflow to import and publish. */
	public static final class WorkflowItem
	{
		public String name;
		public String modelName;
		public String tablePath;
		public WorkflowAssembler.Params params;
	}

	/** A role of the domain and what its profile may do on the dataset. */
	public static final class Role
	{
		public String name;
		public String profile;
		public boolean create;
		public boolean overwrite;
		public boolean delete;
		public boolean occult;
	}

	/** A table preloaded with the pack's rows. */
	public static final class RefTable
	{
		public String packKey;
		public String tablePath;
		public ReferenceTable table;
	}

	/** The matching material of a table (a {@code matchingPolicy} for {@code generate_matching_policy}). */
	public static final class MatchingItem
	{
		public String tablePath;
		public String processCode;
		public ObjectNode matchingPolicy;
	}

	public final Names names;
	public final String projectCode;
	public final List<String> packKeys = new ArrayList<String>();
	public final List<ObjectNode> modelOps = new ArrayList<ObjectNode>();
	/** Table path to default label template. */
	public final Map<String, String> defaultLabels = new LinkedHashMap<String, String>();
	/** {@code set_element_documentation} operations, flat form, only for elements the model ops create. */
	public final List<ObjectNode> documentationOps = new ArrayList<ObjectNode>();
	public final List<ScriptItem> scripts = new ArrayList<ScriptItem>();
	public final List<LayoutItem> layouts = new ArrayList<LayoutItem>();
	public final List<WorkflowItem> workflows = new ArrayList<WorkflowItem>();
	public final List<Role> roles = new ArrayList<Role>();
	public final List<RefTable> referenceTables = new ArrayList<RefTable>();
	public final List<MatchingItem> matching = new ArrayList<MatchingItem>();
	/** Things the plan could not or did not do, for the summary. */
	public final List<String> notes = new ArrayList<String>();
	/** Optional modules the packs would like (e.g. {@code ebx-addon-mame}). */
	public final Set<String> optionalModules = new LinkedHashSet<String>();

	private final Set<String> tablePaths = new LinkedHashSet<String>();

	private DomainPlan(final String pProjectCode)
	{
		this.projectCode = pProjectCode;
		this.names = new Names(pProjectCode);
	}

	public static String identifier(final String pText)
	{
		final StringBuilder sb = new StringBuilder();
		for (final char c : (pText == null ? "" : pText).toCharArray())
		{
			sb.append(Character.isLetterOrDigit(c) && c < 128 ? c : '_');
		}
		if (sb.length() == 0 || !Character.isLetter(sb.charAt(0)))
		{
			sb.insert(0, 'X');
		}
		return sb.toString();
	}

	public static DomainPlan build(final PackCatalog pCatalog, final String pProjectCode, final String pDomainLabel, final List<PackChoice> pChoices,
		final List<Decision> pAccepted)
	{
		final DomainPlan plan = new DomainPlan(pProjectCode);
		final String domainLabel = pDomainLabel == null || pDomainLabel.isEmpty() ? pProjectCode : pDomainLabel;

		// packs in dependency order: what a pack requires comes first, each once
		final List<Pack> packs = new ArrayList<Pack>();
		for (final PackChoice choice : pChoices)
		{
			plan.addPack(pCatalog, choice.packKey, packs, new LinkedHashSet<String>());
		}
		for (final Pack p : packs)
		{
			plan.packKeys.add(p.key());
			plan.optionalModules.addAll(p.requires().optionalModules());
		}

		final Set<String> removed = new LinkedHashSet<String>();
		final Map<String, ObjectNode> changed = new LinkedHashMap<String, ObjectNode>();
		final Map<String, JsonNode> layoutOverrides = new LinkedHashMap<String, JsonNode>();
		final List<Decision> blocks = new ArrayList<Decision>();
		final List<Decision> libs = new ArrayList<Decision>();
		final List<Decision> adds = new ArrayList<Decision>();
		final List<Decision> workflows = new ArrayList<Decision>();
		for (final Decision d : pAccepted)
		{
			final String nature = d.nature == null ? "" : d.nature;
			if ("REMOVE".equals(nature) && d.where != null)
			{
				removed.add(d.where);
			}
			else if ("CHANGE".equals(nature) || "ADD".equals(nature))
			{
				final JsonNode detail = d.detail();
				if (detail.has("layout") && detail.get("layout").isObject() && d.where != null)
				{
					layoutOverrides.put(d.where, detail.get("layout"));
				}
				else if ("CHANGE".equals(nature))
				{
					final ObjectNode props = DomainPlan.changeProperties(detail);
					if (d.where == null || props == null)
					{
						plan.notes.add("CHANGE '" + d.what + "' skipped: it names no element path or no property.");
					}
					else
					{
						final ObjectNode existing = changed.get(d.where);
						if (existing == null)
						{
							changed.put(d.where, props);
						}
						else
						{
							existing.setAll(props);
						}
					}
				}
				else
				{
					adds.add(d);
				}
			}
			else if ("USE_BLOCK".equals(nature))
			{
				blocks.add(d);
			}
			else if ("USE_LIBRARY".equals(nature))
			{
				libs.add(d);
			}
			else if ("USE_WORKFLOW".equals(nature))
			{
				workflows.add(d);
			}
		}

		// model operations: pack cores (REMOVE and CHANGE applied), then block placements, then ADDs
		final Map<String, List<ObjectNode>> coreByPack = new LinkedHashMap<String, List<ObjectNode>>();
		final Set<String> coreSubjects = new LinkedHashSet<String>();
		for (final Pack p : packs)
		{
			for (final ObjectNode op : p.core().ops())
			{
				final String s = PackOps.subject(op);
				if (s != null)
				{
					coreSubjects.add(s);
				}
			}
		}
		final Map<String, ObjectNode> applicableChanges = new LinkedHashMap<String, ObjectNode>();
		for (final Map.Entry<String, ObjectNode> e : changed.entrySet())
		{
			if (coreSubjects.contains(e.getKey()))
			{
				applicableChanges.put(e.getKey(), e.getValue());
			}
			else
			{
				plan.notes.add("CHANGE of " + e.getKey() + " skipped: the path is not an element of the chosen packs.");
			}
		}
		final Set<String> chosenBlockTables = new LinkedHashSet<String>();
		for (final Pack p : packs)
		{
			final Set<String> mine = new LinkedHashSet<String>();
			final Map<String, ObjectNode> myChanges = new LinkedHashMap<String, ObjectNode>();
			for (final ObjectNode op : p.core().ops())
			{
				final String s = PackOps.subject(op);
				if (s != null && applicableChanges.containsKey(s))
				{
					mine.add(s);
					myChanges.put(s, applicableChanges.get(s));
				}
			}
			for (final ObjectNode op : PackOps.coreOps(p.core(), removed, myChanges))
			{
				if (DomainPlan.pointsInto(op, removed))
				{
					plan.notes.add("Operation " + op.path("op").asText() + " dropped: it points into a removed element.");
				}
				else
				{
					plan.modelOps.add(op);
				}
			}
		}
		for (final ObjectNode op : plan.modelOps)
		{
			if ("create_table".equals(op.path("op").asText()))
			{
				plan.tablePaths.add(op.path("arguments").path("table_path").asText());
			}
		}
		for (final Decision d : adds)
		{
			plan.addDecision(d);
		}
		for (final ObjectNode op : plan.modelOps)
		{
			if ("create_table".equals(op.path("op").asText()))
			{
				plan.tablePaths.add(op.path("arguments").path("table_path").asText());
			}
		}
		final List<BlockPlacement> placements = new ArrayList<BlockPlacement>();
		for (final Pack p : packs)
		{
			for (final BlockPlacement bp : p.core().blockPlacements())
			{
				if (!DomainPlan.under(bp.table(), removed))
				{
					placements.add(bp);
				}
			}
		}
		for (final Decision d : blocks)
		{
			final String group = d.detail().path("group").asText(null);
			if (d.blockKey == null || d.where == null)
			{
				plan.notes.add("USE_BLOCK '" + d.what + "' skipped: no block or no table.");
				continue;
			}
			placements.add(new BlockPlacement(d.blockKey, d.where, group));
		}
		final Set<String> seenPlacement = new LinkedHashSet<String>();
		final List<Object[]> placedBlocks = new ArrayList<Object[]>();
		for (final BlockPlacement bp : placements)
		{
			final Block block = pCatalog.block(bp.block());
			if (block == null || !plan.tablePaths.contains(bp.table()))
			{
				plan.notes.add("Block " + bp.block() + " on " + bp.table() + " skipped: " + (block == null ? "unknown block" : "the table is not in the model"));
				continue;
			}
			if (!seenPlacement.add(bp.block() + "@" + bp.table()))
			{
				continue;
			}
			final String group = bp.group() == null || bp.group().isEmpty() ? block.group() : bp.group();
			plan.modelOps.addAll(PackOps.opsForBlock(block, bp.table(), group));
			placedBlocks.add(new Object[] { block, bp.table(), group });
			chosenBlockTables.add(bp.table());
		}
		// every table, including those ADDed
		plan.tablePaths.clear();
		final Set<String> existing = new LinkedHashSet<String>();
		for (final ObjectNode op : plan.modelOps)
		{
			final String name = op.path("op").asText();
			if ("create_table".equals(name))
			{
				plan.tablePaths.add(op.path("arguments").path("table_path").asText());
			}
			if (name.startsWith("create_"))
			{
				final String s = PackOps.subject(op);
				if (s != null)
				{
					existing.add(s);
				}
			}
		}
		// default labels and documentation, only for what the operations create
		for (final Pack p : packs)
		{
			for (final Map.Entry<String, String> e : p.core().defaultLabels().entrySet())
			{
				if (plan.tablePaths.contains(e.getKey()))
				{
					plan.defaultLabels.put(e.getKey(), e.getValue());
				}
			}
			for (final Map.Entry<String, Map<String, String>> e : p.core().labels().entrySet())
			{
				plan.addDocumentation(e.getKey(), e.getValue(), existing);
			}
		}
		for (final Object[] pb : placedBlocks)
		{
			final Block block = (Block) pb[0];
			final String table = (String) pb[1];
			final String group = (String) pb[2];
			final Map<String, String> groupLabel = new LinkedHashMap<String, String>();
			groupLabel.put("en-US", block.label(Locale.US));
			groupLabel.put("fr-FR", block.label(Locale.FRANCE));
			plan.addDocumentation(table + "/" + group, groupLabel, existing);
			for (final Block.Field f : block.fields())
			{
				final Map<String, String> l = new LinkedHashMap<String, String>();
				l.put("en-US", f.label(Locale.US));
				l.put("fr-FR", f.label(Locale.FRANCE));
				plan.addDocumentation(table + "/" + group + "/" + f.name(), l, existing);
			}
		}

		// scripts: blocks, then library rules, then draft rules
		for (final Object[] pb : placedBlocks)
		{
			final Block block = (Block) pb[0];
			final String table = (String) pb[1];
			final String group = (String) pb[2];
			for (final Block.Script s : block.scripts())
			{
				final ScriptItem item = new ScriptItem();
				item.kind = s.kind();
				item.type = s.type();
				item.name = s.name();
				item.tablePath = table;
				item.source = "block:" + block.key();
				item.body = PackOps.scriptBody(block, s.name(), table, group);
				if ("trigger".equals(s.kind()))
				{
					item.scope = "table";
					item.elementPath = table;
				}
				else
				{
					item.scope = "node";
					item.elementPath = table + "/" + group + "/" + s.field();
				}
				plan.finishScript(item);
			}
		}
		for (final Decision d : libs)
		{
			plan.addLibraryRule(pCatalog.libraries(), d);
		}
		for (final Decision d : adds)
		{
			final JsonNode detail = d.detail();
			if ("rule".equals(detail.path("kind").asText("")) || detail.has("rule"))
			{
				plan.addDraftRule(d);
			}
		}

		// layouts
		for (final Pack p : packs)
		{
			for (final Map.Entry<String, JsonNode> e : p.layouts().entrySet())
			{
				if (plan.tablePaths.contains(e.getKey()) && !layoutOverrides.containsKey(e.getKey()))
				{
					plan.addLayout(e.getKey(), e.getValue(), placedBlocks);
				}
			}
		}
		for (final Map.Entry<String, JsonNode> e : layoutOverrides.entrySet())
		{
			if (plan.tablePaths.contains(e.getKey()))
			{
				plan.addLayout(e.getKey(), e.getValue(), placedBlocks);
			}
			else
			{
				plan.notes.add("Layout for " + e.getKey() + " skipped: the table is not in the model.");
			}
		}

		// workflows and roles
		final Set<String> roleNames = new LinkedHashSet<String>();
		final Map<String, ObjectNode> wfByTable = new LinkedHashMap<String, ObjectNode>();
		for (final Pack p : packs)
		{
			if (p.workflows() == null)
			{
				continue;
			}
			for (final JsonNode w : p.workflows().path("workflows"))
			{
				final String table = w.path("table").asText("");
				if (!plan.tablePaths.contains(table))
				{
					continue;
				}
				if (!"creationWithReview".equals(w.path("type").asText()))
				{
					plan.notes.add("Workflow type " + w.path("type").asText() + " of pack " + p.key() + " is not generated in lot C1.");
					continue;
				}
				wfByTable.put(table, (ObjectNode) w.deepCopy());
			}
		}
		for (final Decision d : workflows)
		{
			if (!"creationWithReview".equals(d.workflowType) || d.where == null || !plan.tablePaths.contains(d.where))
			{
				plan.notes.add("USE_WORKFLOW '" + d.what + "' (" + d.workflowType + ") is not generated in lot C1 or names no table of the model.");
				continue;
			}
			final ObjectNode w = wfByTable.containsKey(d.where) ? wfByTable.get(d.where) : Res.MAPPER.createObjectNode();
			w.put("type", "creationWithReview");
			w.put("table", d.where);
			if (!w.has("name"))
			{
				w.put("name", DomainPlan.tableName(d.where) + "CreationRequest");
			}
			final ObjectNode params = w.has("parameters") ? (ObjectNode) w.get("parameters") : w.putObject("parameters");
			final JsonNode over = d.detail().has("parameters") ? d.detail().get("parameters") : d.detail();
			if (over.isObject())
			{
				params.setAll((ObjectNode) over.deepCopy());
			}
			wfByTable.put(d.where, w);
		}
		for (final Map.Entry<String, ObjectNode> e : wfByTable.entrySet())
		{
			final WorkflowItem item = plan.workflowItem(pProjectCode, domainLabel, e.getKey(), e.getValue(), packs, roleNames);
			if (item != null)
			{
				plan.workflows.add(item);
			}
		}
		roleNames.add("DataSteward");
		for (final Decision d : adds)
		{
			if ("role".equals(d.detail().path("kind").asText("")) && d.detail().hasNonNull("role"))
			{
				roleNames.add(d.detail().get("role").asText());
			}
		}
		for (final String r : roleNames)
		{
			plan.roles.add(DomainPlan.role(r, p(plan, r, adds)));
		}

		// reference rows
		for (final Pack p : packs)
		{
			for (final Map.Entry<String, ReferenceTable> e : p.referenceData().entrySet())
			{
				if (plan.tablePaths.contains(e.getKey()))
				{
					final RefTable rt = new RefTable();
					rt.packKey = p.key();
					rt.tablePath = e.getKey();
					rt.table = e.getValue();
					plan.referenceTables.add(rt);
				}
			}
		}

		// matching
		for (final Pack p : packs)
		{
			if (p.matching() != null)
			{
				plan.addMatching(p.matching(), removed);
			}
		}
		return plan;
	}

	private static JsonNode p(final DomainPlan pPlan, final String pRole, final List<Decision> pAdds)
	{
		for (final Decision d : pAdds)
		{
			if ("role".equals(d.detail().path("kind").asText("")) && pRole.equals(d.detail().path("role").asText()))
			{
				return d.detail();
			}
		}
		return null;
	}

	private static Role role(final String pName, final JsonNode pDetail)
	{
		final Role r = new Role();
		r.name = pName;
		r.profile = "R" + pName;
		final boolean steward = "DataSteward".equals(pName);
		r.create = pDetail != null && pDetail.has("create") ? pDetail.get("create").asBoolean() : !"Reviewer".equals(pName);
		r.overwrite = pDetail != null && pDetail.has("overwrite") ? pDetail.get("overwrite").asBoolean() : true;
		r.delete = pDetail != null && pDetail.has("delete") ? pDetail.get("delete").asBoolean() : steward;
		r.occult = false;
		return r;
	}

	// ------------------------------------------------------------------------------------------------------------ helpers

	private void addPack(final PackCatalog pCatalog, final String pKey, final List<Pack> pOut, final Set<String> pVisiting)
	{
		final Pack pack = pCatalog.pack(pKey);
		if (pack == null)
		{
			this.notes.add("Unknown pack " + pKey + " ignored.");
			return;
		}
		for (final Pack done : pOut)
		{
			if (done.key().equals(pKey))
			{
				return;
			}
		}
		if (!pVisiting.add(pKey))
		{
			return;
		}
		for (final String required : pack.requires().packs())
		{
			this.addPack(pCatalog, required, pOut, pVisiting);
		}
		pOut.add(pack);
	}

	private static ObjectNode changeProperties(final JsonNode pDetail)
	{
		final ObjectNode out = Res.MAPPER.createObjectNode();
		if (pDetail.hasNonNull("property") && pDetail.has("value"))
		{
			out.put(pDetail.get("property").asText(), pDetail.get("value").asText());
		}
		if (pDetail.path("properties").isObject())
		{
			final Iterator<Map.Entry<String, JsonNode>> it = pDetail.get("properties").fields();
			while (it.hasNext())
			{
				final Map.Entry<String, JsonNode> e = it.next();
				out.put(e.getKey(), e.getValue().asText());
			}
		}
		return out.size() == 0 ? null : out;
	}

	/** True when a path argument of the operation (any argument named *_path) lies under a removed path: an association's foreign key, for one. */
	private static boolean pointsInto(final ObjectNode pOp, final Set<String> pRemoved)
	{
		if (pRemoved.isEmpty())
		{
			return false;
		}
		final Iterator<Map.Entry<String, JsonNode>> it = pOp.path("arguments").fields();
		while (it.hasNext())
		{
			final Map.Entry<String, JsonNode> e = it.next();
			if (e.getKey().endsWith("_path") && e.getValue().isTextual() && DomainPlan.under(e.getValue().asText(), pRemoved))
			{
				return true;
			}
		}
		return false;
	}

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

	static String tableName(final String pPath)
	{
		return pPath.substring(pPath.lastIndexOf('/') + 1);
	}

	/** The table a path lies in: the longest known table path that is a prefix of it, or null. */
	String tableOf(final String pPath)
	{
		String best = null;
		for (final String t : this.tablePaths)
		{
			if ((pPath.equals(t) || pPath.startsWith(t + "/")) && (best == null || t.length() > best.length()))
			{
				best = t;
			}
		}
		return best;
	}

	private void addDocumentation(final String pPath, final Map<String, String> pLabels, final Set<String> pExisting)
	{
		if (!pExisting.contains(pPath))
		{
			return;
		}
		for (final Map.Entry<String, String> e : pLabels.entrySet())
		{
			if (e.getValue() == null || e.getValue().isEmpty())
			{
				continue;
			}
			final ObjectNode op = Res.MAPPER.createObjectNode();
			op.put("op", "set_element_documentation");
			op.putObject("arguments").put("element_path", pPath).put("label", e.getValue()).put("locale", e.getKey());
			this.documentationOps.add(op);
		}
	}

	private void addDecision(final Decision pDecision)
	{
		final JsonNode detail = pDecision.detail();
		final String kind = detail.path("kind").asText("");
		final boolean path = pDecision.where != null && pDecision.where.startsWith("/root/");
		if (!detail.path("ops").isArray() && !"rule".equals(kind) && !"role".equals(kind) && !detail.has("rule") && !path)
		{
			this.notes.add("ADD '" + pDecision.what + "' skipped: \"" + pDecision.where + "\" is not an absolute path, a person must place it.");
			return;
		}
		if (detail.path("ops").isArray())
		{
			for (final JsonNode op : detail.get("ops"))
			{
				if (op.isObject() && op.hasNonNull("op") && PackOps.KNOWN_OPS.contains(op.get("op").asText()))
				{
					this.modelOps.add((ObjectNode) op.deepCopy());
				}
				else
				{
					this.notes.add("ADD '" + pDecision.what + "': an operation outside the patch vocabulary was dropped.");
				}
			}
			return;
		}
		if ("table".equals(kind) && pDecision.where != null)
		{
			final ObjectNode t = Res.MAPPER.createObjectNode();
			t.put("op", "create_table");
			t.putObject("arguments").put("table_path", pDecision.where);
			this.modelOps.add(t);
			final ObjectNode id = Res.MAPPER.createObjectNode();
			id.put("op", "create_field");
			id.putObject("arguments").put("field_path", pDecision.where + "/id").put("field_type", "int").put("is_primary_key", true);
			this.modelOps.add(id);
			final ObjectNode auto = Res.MAPPER.createObjectNode();
			auto.put("op", "set_element_property");
			auto.putObject("arguments").put("element_path", pDecision.where + "/id").put("property", "autoIncrementStart").put("value", "1");
			this.modelOps.add(auto);
			for (final JsonNode f : detail.path("fields"))
			{
				this.addField(pDecision.where + "/" + f.path("name").asText(), DetailShape.fieldType(f), f.path("maxLength"), f.path("mandatory").asBoolean(false));
			}
			return;
		}
		if ("group".equals(kind))
		{
			final int slash = pDecision.where.lastIndexOf('/');
			final ObjectNode g = Res.MAPPER.createObjectNode();
			g.put("op", "create_group");
			g.putObject("arguments").put("table_path", pDecision.where.substring(0, slash)).put("group_name", pDecision.where.substring(slash + 1));
			this.modelOps.add(g);
			return;
		}
		if ("field".equals(kind) || detail.has("fieldType"))
		{
			if (pDecision.where == null)
			{
				this.notes.add("ADD '" + pDecision.what + "' skipped: no path.");
				return;
			}
			this.addField(pDecision.where, DetailShape.fieldType(detail), detail.path("maxLength"), detail.path("mandatory").asBoolean(false));
			if (detail.hasNonNull("target") && detail.get("target").asText().startsWith("/root/"))
			{
				final ObjectNode fk = Res.MAPPER.createObjectNode();
				fk.put("op", "add_foreign_key");
				fk.putObject("arguments").put("field_path", pDecision.where).put("target_table_path", detail.get("target").asText());
				this.modelOps.add(fk);
			}
			return;
		}
		if ("rule".equals(kind) || detail.has("rule") || "role".equals(kind))
		{
			return;
		}
		this.notes.add("ADD '" + pDecision.what + "' not understood by the generator, left for a person.");
	}

	private void addField(final String pPath, final String pType, final JsonNode pMaxLength, final boolean pMandatory)
	{
		final ObjectNode f = Res.MAPPER.createObjectNode();
		f.put("op", "create_field");
		f.putObject("arguments").put("field_path", pPath).put("field_type", pType);
		this.modelOps.add(f);
		if (pMaxLength != null && pMaxLength.isNumber())
		{
			this.modelOps.add(DomainPlan.property(pPath, "maxLength", pMaxLength.asText()));
		}
		if (pMandatory)
		{
			this.modelOps.add(DomainPlan.property(pPath, "mandatory", "true"));
			this.modelOps.add(DomainPlan.property(pPath, "minOccurs", "1"));
		}
	}

	private static ObjectNode property(final String pPath, final String pProperty, final String pValue)
	{
		final ObjectNode op = Res.MAPPER.createObjectNode();
		op.put("op", "set_element_property");
		op.putObject("arguments").put("element_path", pPath).put("property", pProperty).put("value", pValue);
		return op;
	}

	private void finishScript(final ScriptItem pItem)
	{
		if ("trigger".equals(pItem.kind))
		{
			pItem.idePath = "data-model/" + this.names.model + pItem.tablePath + "/@trigger/" + pItem.name;
		}
		else if ("node".equals(pItem.scope))
		{
			pItem.idePath = "data-model/" + this.names.model + pItem.elementPath + "/@nodeConstraint/" + pItem.name;
		}
		else
		{
			pItem.idePath = "data-model/" + this.names.model + pItem.tablePath + "/@constraint/" + pItem.name;
		}
		this.scripts.add(pItem);
	}

	private String uniqueScriptName(final String pBase, final String pElement)
	{
		String name = pBase;
		int i = 2;
		boolean clash = true;
		while (clash)
		{
			clash = false;
			for (final ScriptItem s : this.scripts)
			{
				if (s.name.equals(name) && s.elementPath.equals(pElement))
				{
					clash = true;
					name = pBase + i++;
					break;
				}
			}
		}
		return name;
	}

	private void addLibraryRule(final LibraryCatalog pLibs, final Decision pDecision)
	{
		final String fn = pDecision.libraryFunction;
		if (!pLibs.knows(fn) || pDecision.where == null)
		{
			this.notes.add("USE_LIBRARY '" + pDecision.what + "' skipped: " + (pDecision.where == null ? "no field" : fn + " is not in the library catalogue") + ".");
			return;
		}
		final String table = this.tableOf(pDecision.where);
		if (table == null || pDecision.where.equals(table))
		{
			this.notes.add("USE_LIBRARY '" + pDecision.what + "' skipped: " + pDecision.where + " is not a field of the model.");
			return;
		}
		final int dot = fn.lastIndexOf('.');
		final String libName = fn.substring(0, dot);
		final String function = LibraryCatalog.bareName(fn.substring(dot + 1));
		boolean simple = false;
		final LibraryCatalog.Library lib = pLibs.library(libName);
		for (final String sig : lib.functions())
		{
			if (LibraryCatalog.bareName(sig).equals(function))
			{
				final String args = sig.substring(sig.indexOf('(') + 1, sig.indexOf(')'));
				simple = args.indexOf(',') < 0 && args.trim().length() > 0 && sig.endsWith("boolean");
			}
		}
		final String relative = pDecision.where.substring(table.length() + 1).replace('/', '.');
		final JsonNode detail = pDecision.detail();
		final String message = DomainPlan.scriptText(detail.path("message").asText("The value does not pass the " + function + " check of " + libName + "."));
		final StringBuilder b = new StringBuilder();
		b.append("uses core.message as messageContext;\n");
		b.append("import ").append(libName).append(" as lib;\n");
		b.append("export procedure check(record : .").append(DomainPlan.tableName(table)).append(")\nbegin\n");
		b.append("  if isNull(record.").append(relative).append(") then return;\n");
		if (detail.path("exactLength").isNumber())
		{
			b.append("  if record.").append(relative).append(".length <> ").append(detail.get("exactLength").asInt()).append(" then\n");
			b.append("    messageContext.addError('").append(DomainPlan.scriptText(detail.path("lengthMessage").asText("The value must have exactly "
					+ detail.get("exactLength").asInt() + " characters."))).append("');\n");
		}
		if (simple)
		{
			b.append("  if not lib.").append(function).append("(record.").append(relative).append(") then\n");
			b.append("    messageContext.addWarning('").append(message).append("');\n");
		}
		b.append("end\nexport procedure setup()\nbegin\nend\n");
		final ScriptItem item = new ScriptItem();
		item.kind = "constraint";
		item.type = "ConstraintOnNode";
		item.scope = "node";
		item.tablePath = table;
		item.elementPath = pDecision.where;
		item.name = this.uniqueScriptName(DomainPlan.identifier(DomainPlan.tableName(pDecision.where) + "_" + function + "_Check"), pDecision.where);
		item.source = "library:" + fn;
		item.body = b.toString();
		item.draft = !simple;
		if (!simple)
		{
			this.notes.add("USE_LIBRARY " + fn + " has a signature the generator does not call (not one string in, boolean out): authored as a draft.");
		}
		this.finishScript(item);
	}

	private void addDraftRule(final Decision pDecision)
	{
		final JsonNode detail = pDecision.detail();
		final String where = pDecision.where;
		final String table = where == null ? null : this.tableOf(where);
		if (table == null)
		{
			this.notes.add("Draft rule '" + pDecision.what + "' not declared: it names no element of the model.");
			return;
		}
		final String description = detail.path("description").asText(detail.path("rule").asText(pDecision.what == null ? "" : pDecision.what));
		final boolean onTable = where.equals(table);
		final StringBuilder b = new StringBuilder();
		b.append("// DRAFT rule, not enforced until a person confirms it: ").append(description.replace("\n", " ").replace("*/", "")).append("\n");
		if (detail.hasNonNull("script"))
		{
			b.append(detail.get("script").asText());
		}
		else if (onTable)
		{
			b.append("export procedure checkTable()\nbegin\nend\nexport procedure checkRecord(record : .").append(DomainPlan.tableName(table))
				.append(")\nbegin\nend\nexport procedure setup()\nbegin\nend\n");
		}
		else
		{
			b.append("export procedure check(record : .").append(DomainPlan.tableName(table)).append(")\nbegin\nend\nexport procedure setup()\nbegin\nend\n");
		}
		final ScriptItem item = new ScriptItem();
		item.kind = "constraint";
		item.type = onTable ? "ConstraintOnTable" : "ConstraintOnNode";
		item.scope = onTable ? "table" : "node";
		item.tablePath = table;
		item.elementPath = where;
		item.name = this.uniqueScriptName(DomainPlan.identifier("Draft_" + (pDecision.what == null ? "rule" : pDecision.what)).substring(0, Math.min(40,
				DomainPlan.identifier("Draft_" + (pDecision.what == null ? "rule" : pDecision.what)).length())), where);
		item.source = "rule:" + pDecision.id;
		item.body = b.toString();
		item.draft = true;
		this.finishScript(item);
	}

	static String scriptText(final String pText)
	{
		return pText.replace("\\", "\\\\").replace("'", "\\'").replace("\n", " ");
	}

	private void addLayout(final String pTablePath, final JsonNode pLayout, final List<Object[]> pPlacedBlocks)
	{
		final ObjectNode layout = (ObjectNode) pLayout.deepCopy();
		final ArrayNode tabs = layout.path("tabs").isArray() ? (ArrayNode) layout.get("tabs") : null;
		if (tabs != null && tabs.size() > 0 && tabs.get(0).path("rows").isArray())
		{
			final ArrayNode rows = (ArrayNode) tabs.get(0).get("rows");
			for (final Object[] pb : pPlacedBlocks)
			{
				final Block block = (Block) pb[0];
				if (!pTablePath.equals(pb[1]) || block.layoutPanel() == null)
				{
					continue;
				}
				final String group = (String) pb[2];
				final ObjectNode panel = Res.MAPPER.createObjectNode();
				panel.put("type", "panel");
				panel.put("id", block.key() + "-" + DomainPlan.tableName(pTablePath).toLowerCase(Locale.ROOT));
				panel.set("title", block.layoutPanel().path("title").deepCopy());
				if (block.layoutPanel().path("ro").asBoolean(false))
				{
					panel.put("ro", true);
				}
				if (block.layoutPanel().path("hideCreate").asBoolean(false))
				{
					panel.put("hideCreate", true);
				}
				final ArrayNode fields = panel.putArray("fields");
				for (final Block.Field f : block.fields())
				{
					fields.addObject().put("p", "/" + group + "/" + f.name());
				}
				final ObjectNode row = rows.addObject();
				row.put("columns", 1);
				row.putArray("blocks").addArray().add(panel);
			}
		}
		final LayoutItem item = new LayoutItem();
		item.tablePath = pTablePath;
		item.layout = layout;
		this.layouts.add(item);
	}

	private WorkflowItem workflowItem(final String pProjectCode, final String pDomainLabel, final String pTable, final ObjectNode pWorkflow, final List<Pack> pPacks,
		final Set<String> pRoleNames)
	{
		final JsonNode params = pWorkflow.path("parameters");
		final WorkflowAssembler.Params p = new WorkflowAssembler.Params();
		final String name = pWorkflow.path("name").asText(DomainPlan.tableName(pTable) + "CreationRequest");
		p.modelName = this.names.workflowModel(pProjectCode, name);
		p.domainLabel = pDomainLabel;
		final Map<String, String> label = this.tableLabel(pTable, pPacks);
		p.entityLabel = label.containsKey("en-US") ? label.get("en-US") : DomainPlan.tableName(pTable);
		p.entityLabelFr = label.containsKey("fr-FR") ? label.get("fr-FR") : p.entityLabel;
		// a blank start has no pack: the workflow then names none
		p.packKey = pPacks.isEmpty() ? null : pPacks.get(pPacks.size() - 1).key();
		p.packVersion = pPacks.isEmpty() ? null : pPacks.get(pPacks.size() - 1).version();
		p.domainDataspace = this.names.dataspace;
		p.domainDataset = this.names.dataset;
		p.tablePath = pTable;
		final String requester = params.path("requester").asText("Requester");
		p.requesterProfile = "R" + requester;
		pRoleNames.add(requester);
		p.mode = params.path("mode").asText("sequential");
		for (final JsonNode r : params.path("reviewers"))
		{
			final String role = r.isTextual() ? r.asText() : r.path("role").asText("");
			if (role.isEmpty())
			{
				continue;
			}
			pRoleNames.add(role);
			p.reviewers.add(new WorkflowAssembler.Reviewer("R" + role, r.isObject() ? r.path("label").asText(role) : role, r.isObject() ? r.path("labelFr").asText(null) : null));
		}
		if (p.reviewers.isEmpty())
		{
			this.notes.add("Workflow " + name + " has no reviewer: not generated.");
			return null;
		}
		if (!"sequential".equals(p.mode))
		{
			this.notes.add("Workflow " + name + ": mode " + p.mode + " is not generated in lot C1, sequential used.");
			p.mode = "sequential";
		}
		p.acceptLabel = DomainPlan.localized(params.get("acceptLabel"), "en-US", p.acceptLabel);
		p.acceptLabelFr = DomainPlan.localized(params.get("acceptLabel"), "fr-FR", p.acceptLabelFr);
		p.rejectLabel = DomainPlan.localized(params.get("rejectLabel"), "en-US", p.rejectLabel);
		p.rejectLabelFr = DomainPlan.localized(params.get("rejectLabel"), "fr-FR", p.rejectLabelFr);
		final WorkflowItem item = new WorkflowItem();
		item.name = name;
		item.modelName = p.modelName;
		item.tablePath = pTable;
		item.params = p;
		return item;
	}

	private static String localized(final JsonNode pNode, final String pLocale, final String pDefault)
	{
		if (pNode == null || pNode.isNull())
		{
			return pDefault;
		}
		if (pNode.isTextual())
		{
			return pNode.asText();
		}
		return pNode.path(pLocale).asText(pDefault);
	}

	private Map<String, String> tableLabel(final String pTable, final List<Pack> pPacks)
	{
		for (final Pack p : pPacks)
		{
			final Map<String, String> l = p.core().labels().get(pTable);
			if (l != null)
			{
				return l;
			}
		}
		return Collections.<String, String> emptyMap();
	}

	private void addMatching(final JsonNode pMatching, final Set<String> pRemoved)
	{
		final String table = pMatching.path("table").asText("");
		if (!this.tablePaths.contains(table))
		{
			return;
		}
		final ArrayNode fields = Res.MAPPER.createArrayNode();
		for (final JsonNode f : pMatching.path("candidateFields"))
		{
			final String path = table + f.path("field").asText();
			if (!DomainPlan.under(path, pRemoved) && this.hasField(path))
			{
				final ObjectNode field = fields.addObject();
				field.put("field", f.path("field").asText());
				field.put("searchStrategy", "@default");
				field.put("weight", f.path("weight").asInt(1));
				field.put("bothNullValueHandling", "NOT_MATCH");
				field.put("oneNullValueHandling", "NOT_MATCH");
			}
		}
		final ObjectNode tree = DomainPlan.prune((ObjectNode) pMatching.path("decisionTree").deepCopy(), table, pRemoved, this);
		if (fields.size() == 0 || tree == null || !tree.hasNonNull("function"))
		{
			this.notes.add("Matching on " + table + " skipped: the decided differences removed what the pack's policy compares.");
			return;
		}
		final MatchingItem item = new MatchingItem();
		item.tablePath = table;
		item.processCode = DomainPlan.tableName(table) + "_entry";
		final ObjectNode policy = Res.MAPPER.createObjectNode();
		policy.put("processCode", item.processCode);
		policy.put("active", true);
		policy.put("matchingOnCreation", 3);
		policy.put("matchingOnUpdate", 3);
		policy.put("matchAgainstMergedRecords", false);
		policy.put("executePurge", "MANUAL");
		policy.put("showMatchType", false);
		policy.set("fields", fields);
		policy.set("decisionTree", tree);
		item.matchingPolicy = policy;
		this.matching.add(item);
	}

	private boolean hasField(final String pPath)
	{
		for (final ObjectNode op : this.modelOps)
		{
			if ("create_field".equals(op.path("op").asText()) && pPath.equals(op.path("arguments").path("field_path").asText()))
			{
				return true;
			}
		}
		return false;
	}

	/** Drops the compared fields that no longer exist; a node left with none is skipped (its false branch stands in). Null when nothing is left. */
	private static ObjectNode prune(final ObjectNode pNode, final String pTable, final Set<String> pRemoved, final DomainPlan pPlan)
	{
		if (!pNode.hasNonNull("function"))
		{
			return pNode;
		}
		final ArrayNode kept = Res.MAPPER.createArrayNode();
		for (final JsonNode f : pNode.path("fields"))
		{
			final String path = pTable + f.path("field").asText();
			if (!DomainPlan.under(path, pRemoved) && pPlan.hasField(path))
			{
				kept.add(f);
			}
		}
		final JsonNode t = pNode.get("trueNode");
		final JsonNode f = pNode.get("falseNode");
		final ObjectNode trueNode = t instanceof ObjectNode ? DomainPlan.prune((ObjectNode) t, pTable, pRemoved, pPlan) : null;
		final ObjectNode falseNode = f instanceof ObjectNode ? DomainPlan.prune((ObjectNode) f, pTable, pRemoved, pPlan) : null;
		if (kept.size() == 0)
		{
			return falseNode;
		}
		pNode.set("fields", kept);
		if (trueNode != null)
		{
			pNode.set("trueNode", trueNode);
		}
		if (falseNode != null)
		{
			pNode.set("falseNode", falseNode);
		}
		return pNode;
	}

	/** Scripts the plan holds back as drafts. */
	public List<ScriptItem> drafts()
	{
		final List<ScriptItem> out = new ArrayList<ScriptItem>();
		for (final ScriptItem s : this.scripts)
		{
			if (s.draft)
			{
				out.add(s);
			}
		}
		return out;
	}
}
