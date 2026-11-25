package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** A domain pack ({@code packs/<key>/}): identity, requirements, core model, guidance, workflows, matching, layouts, reference rows. */
public final class Pack
{
	/** What the pack needs: other packs, modules that must be present, modules that are used when present. */
	public static final class Requires
	{
		private final List<String> packs;
		private final List<String> modules;
		private final List<String> optionalModules;

		Requires(final JsonNode pNode)
		{
			this.packs = Collections.unmodifiableList(Res.strings(pNode == null ? null : pNode.get("packs")));
			this.modules = Collections.unmodifiableList(Res.strings(pNode == null ? null : pNode.get("modules")));
			this.optionalModules = Collections.unmodifiableList(Res.strings(pNode == null ? null : pNode.get("optionalModules")));
		}

		public List<String> packs()
		{
			return this.packs;
		}

		public List<String> modules()
		{
			return this.modules;
		}

		public List<String> optionalModules()
		{
			return this.optionalModules;
		}
	}

	private final String key;
	private final String version;
	private final Map<String, String> label;
	private final Map<String, String> description;
	private final Requires requires;
	private final List<String> blocks;
	private final List<String> options;
	private final String mainTable;
	private final List<String> keywords;
	private final PackCore core;
	private final String guidance;
	private final JsonNode workflows;
	private final JsonNode matching;
	private final Map<String, JsonNode> layouts;
	private final Map<String, ReferenceTable> referenceData;

	Pack(final String pKey, final JsonNode pPack, final List<String> pLayoutNames)
	{
		final String dir = "packs/" + pKey + "/";
		final String where = dir + "pack.json";
		if (!pKey.equals(Res.str(pPack, "key")))
		{
			throw new IllegalStateException("Accelerator file " + where + ": 'key' must be '" + pKey + "' but is '" + Res.str(pPack, "key") + "'");
		}
		this.key = pKey;
		this.version = Res.required(pPack, "version", where);
		this.label = Collections.unmodifiableMap(Res.localized(pPack.get("label")));
		this.description = Collections.unmodifiableMap(Res.localized(pPack.get("description")));
		this.requires = new Requires(pPack.get("requires"));
		this.blocks = Collections.unmodifiableList(Res.strings(pPack.get("blocks")));
		this.options = Collections.unmodifiableList(Res.strings(pPack.get("options")));
		this.mainTable = Res.required(pPack, "mainTable", where);
		this.keywords = Collections.unmodifiableList(Res.strings(pPack.get("keywords")));
		this.core = new PackCore(Res.json(dir + "core.json"), dir + "core.json");
		this.guidance = Res.exists(dir + "guidance.md") ? Res.text(dir + "guidance.md") : "";
		this.workflows = Res.exists(dir + "workflows.json") ? Res.json(dir + "workflows.json") : null;
		this.matching = Res.exists(dir + "matching.json") ? Res.json(dir + "matching.json") : null;
		final Map<String, JsonNode> l = new LinkedHashMap<String, JsonNode>();
		for (final String name : pLayoutNames)
		{
			final JsonNode layout = Res.json(dir + "layouts/" + name + ".json");
			l.put(Res.required(layout, "table", dir + "layouts/" + name + ".json"), layout);
		}
		this.layouts = Collections.unmodifiableMap(l);
		final Map<String, ReferenceTable> r = new LinkedHashMap<String, ReferenceTable>();
		for (final JsonNode t : pPack.path("tables"))
		{
			final String path = Res.required(t, "path", where);
			final String dataFile = dir + Res.required(t, "data", where);
			r.put(path, new ReferenceTable(path, Res.required(t, "key", where), Res.localized(t.get("columns")), Res.text(dataFile), dataFile));
		}
		this.referenceData = Collections.unmodifiableMap(r);
	}

	public String key()
	{
		return this.key;
	}

	public String version()
	{
		return this.version;
	}

	public String label(final Locale pLocale)
	{
		return Res.pick(this.label, pLocale);
	}

	public String description(final Locale pLocale)
	{
		return Res.pick(this.description, pLocale);
	}

	public Requires requires()
	{
		return this.requires;
	}

	/** Keys of the blocks the pack uses. */
	public List<String> blocks()
	{
		return this.blocks;
	}

	/** Keys of the options a person may switch on. */
	public List<String> options()
	{
		return this.options;
	}

	public String mainTable()
	{
		return this.mainTable;
	}

	public List<String> keywords()
	{
		return this.keywords;
	}

	public PackCore core()
	{
		return this.core;
	}

	/** {@code guidance.md}, or an empty string. */
	public String guidance()
	{
		return this.guidance;
	}

	/** {@code workflows.json}, or null. */
	public JsonNode workflows()
	{
		return this.workflows;
	}

	/** {@code matching.json}, or null. */
	public JsonNode matching()
	{
		return this.matching;
	}

	/** Table path to Layout Designer JSON ({@code model} = {@code ${publication}}). */
	public Map<String, JsonNode> layouts()
	{
		return this.layouts;
	}

	/** Table path to the rows preloaded with the pack. */
	public Map<String, ReferenceTable> referenceData()
	{
		return this.referenceData;
	}

	/** One compact line for the pack-choice prompt. */
	public String promptLine()
	{
		return this.key + ": " + this.description(Locale.US) + " Main table " + this.mainTable + ". Options: "
				+ (this.options.isEmpty() ? "none" : LibraryCatalog.join(this.options, ", ")) + ". Needs packs: "
				+ (this.requires.packs().isEmpty() ? "none" : LibraryCatalog.join(this.requires.packs(), ", ")) + ".";
	}

	List<String> allTexts()
	{
		final List<String> out = new ArrayList<String>(this.label.values());
		out.addAll(this.description.values());
		return out;
	}
}
