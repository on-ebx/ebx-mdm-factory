package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** A cross-domain building block ({@code blocks/<key>/block.json}): fields, the group they live in, scripts, a layout panel. */
public final class Block
{
	/** One field of the block. {@code maxLength} is null when not set. */
	public static final class Field
	{
		private final String name;
		private final String type;
		private final Integer maxLength;
		private final boolean readOnly;
		private final Map<String, String> label;

		Field(final JsonNode pNode, final String pWhere)
		{
			this.name = Res.required(pNode, "name", pWhere);
			this.type = Res.required(pNode, "type", pWhere);
			this.maxLength = pNode.hasNonNull("maxLength") ? Integer.valueOf(pNode.get("maxLength").asInt()) : null;
			this.readOnly = pNode.path("readOnly").asBoolean(false);
			this.label = Collections.unmodifiableMap(Res.localized(pNode.get("label")));
		}

		public String name()
		{
			return this.name;
		}

		public String type()
		{
			return this.type;
		}

		public Integer maxLength()
		{
			return this.maxLength;
		}

		public boolean readOnly()
		{
			return this.readOnly;
		}

		public String label(final Locale pLocale)
		{
			return Res.pick(this.label, pLocale);
		}
	}

	/** One script of the block: a trigger or a constraint, with its body template (placeholders {@code ${table}}, {@code ${group}}). */
	public static final class Script
	{
		private final String kind;
		private final String type;
		private final String name;
		private final String field;
		private final String bodyPath;
		private final String body;
		private final List<String> uses;

		Script(final JsonNode pNode, final String pBlockKey, final String pWhere)
		{
			this.kind = Res.required(pNode, "kind", pWhere);
			this.type = Res.required(pNode, "type", pWhere);
			this.name = Res.required(pNode, "name", pWhere);
			this.field = Res.str(pNode, "field");
			this.bodyPath = Res.required(pNode, "body", pWhere);
			this.body = Res.text("blocks/" + pBlockKey + "/" + this.bodyPath);
			this.uses = Collections.unmodifiableList(Res.strings(pNode.get("uses")));
		}

		/** {@code trigger} or {@code constraint}. */
		public String kind()
		{
			return this.kind;
		}

		/** {@code beforeCreate}, {@code beforeModify}, {@code ConstraintOnNode}... */
		public String type()
		{
			return this.type;
		}

		public String name()
		{
			return this.name;
		}

		/** The field a node constraint is anchored on (name inside the block), or null. */
		public String field()
		{
			return this.field;
		}

		public String bodyPath()
		{
			return this.bodyPath;
		}

		/** The body template, placeholders unresolved. */
		public String body()
		{
			return this.body;
		}

		/** Published libraries the body calls. */
		public List<String> uses()
		{
			return this.uses;
		}
	}

	private final String key;
	private final String version;
	private final Map<String, String> label;
	private final Map<String, String> description;
	private final String placement;
	private final String group;
	private final List<Field> fields;
	private final List<Script> scripts;
	private final JsonNode layoutPanel;

	Block(final String pKey, final JsonNode pRoot)
	{
		final String where = "blocks/" + pKey + "/block.json";
		if (!pKey.equals(Res.str(pRoot, "key")))
		{
			throw new IllegalStateException("Accelerator file " + where + ": 'key' must be '" + pKey + "' but is '" + Res.str(pRoot, "key") + "'");
		}
		this.key = pKey;
		this.version = Res.required(pRoot, "version", where);
		this.label = Collections.unmodifiableMap(Res.localized(pRoot.get("label")));
		this.description = Collections.unmodifiableMap(Res.localized(pRoot.get("description")));
		this.placement = Res.str(pRoot, "placement");
		this.group = Res.required(pRoot, "group", where);
		final List<Field> f = new ArrayList<Field>();
		for (final JsonNode item : pRoot.path("fields"))
		{
			f.add(new Field(item, where));
		}
		if (f.isEmpty())
		{
			throw new IllegalStateException("Accelerator file " + where + ": a block needs at least one field");
		}
		this.fields = Collections.unmodifiableList(f);
		final List<Script> s = new ArrayList<Script>();
		for (final JsonNode item : pRoot.path("scripts"))
		{
			s.add(new Script(item, pKey, where));
		}
		this.scripts = Collections.unmodifiableList(s);
		this.layoutPanel = pRoot.path("layout").has("panel") ? pRoot.path("layout").get("panel") : null;
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

	/** {@code table} for every block of lot C1. */
	public String placement()
	{
		return this.placement;
	}

	/** Default group name. */
	public String group()
	{
		return this.group;
	}

	public List<Field> fields()
	{
		return this.fields;
	}

	public List<Script> scripts()
	{
		return this.scripts;
	}

	public Script script(final String pName)
	{
		for (final Script s : this.scripts)
		{
			if (s.name().equals(pName))
			{
				return s;
			}
		}
		return null;
	}

	/** The Layout Designer panel the block adds ({@code title}, {@code ro}, {@code hideCreate}), or null. */
	public JsonNode layoutPanel()
	{
		return this.layoutPanel;
	}

	/** One compact line for prompts. */
	public String promptLine()
	{
		final StringBuilder sb = new StringBuilder(this.key).append(": ").append(this.description(Locale.US)).append(" Fields: ");
		for (int i = 0; i < this.fields.size(); i++)
		{
			sb.append(i > 0 ? ", " : "").append(this.fields.get(i).name()).append(" (").append(this.fields.get(i).type()).append(')');
		}
		return sb.toString();
	}
}
