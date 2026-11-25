package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/** The workflow types the generator can assemble ({@code workflow-types.json}). */
public final class WorkflowTypes
{
	/** One workflow type. */
	public static final class WorkflowType
	{
		private final String key;
		private final Map<String, String> label;
		private final Map<String, String> parameters;
		private final String shape;
		private final String measured;

		WorkflowType(final JsonNode pNode, final String pWhere)
		{
			this.key = Res.required(pNode, "key", pWhere);
			this.label = Collections.unmodifiableMap(Res.localized(pNode.get("label")));
			final Map<String, String> p = new LinkedHashMap<String, String>();
			final Iterator<Map.Entry<String, JsonNode>> it = pNode.path("parameters").fields();
			while (it.hasNext())
			{
				final Map.Entry<String, JsonNode> e = it.next();
				p.put(e.getKey(), e.getValue().asText());
			}
			this.parameters = Collections.unmodifiableMap(p);
			this.shape = Res.required(pNode, "shape", pWhere);
			this.measured = Res.str(pNode, "measured");
		}

		public String key()
		{
			return this.key;
		}

		public String label(final Locale pLocale)
		{
			return Res.pick(this.label, pLocale);
		}

		/** Parameter name to a short description of its value. */
		public Map<String, String> parameters()
		{
			return this.parameters;
		}

		public String shape()
		{
			return this.shape;
		}

		/** Where the shape was measured, or null. */
		public String measured()
		{
			return this.measured;
		}

		/** False for a type listed for the proposal pass only ("not generated in lot C1"). */
		public boolean generated()
		{
			return !this.shape.startsWith("not generated");
		}
	}

	private final Map<String, WorkflowType> byKey;

	WorkflowTypes(final JsonNode pRoot, final String pWhere)
	{
		final Map<String, WorkflowType> m = new LinkedHashMap<String, WorkflowType>();
		for (final JsonNode item : pRoot.path("types"))
		{
			final WorkflowType t = new WorkflowType(item, pWhere);
			if (m.put(t.key(), t) != null)
			{
				throw new IllegalStateException("Accelerator file " + pWhere + ": workflow type " + t.key() + " declared twice");
			}
		}
		if (m.isEmpty())
		{
			throw new IllegalStateException("Accelerator file " + pWhere + ": no workflow type declared");
		}
		this.byKey = Collections.unmodifiableMap(m);
	}

	/** Null when absent. */
	public WorkflowType type(final String pKey)
	{
		return this.byKey.get(pKey);
	}

	public List<WorkflowType> all()
	{
		return Collections.unmodifiableList(new ArrayList<WorkflowType>(this.byKey.values()));
	}

	/** One line per type: key, label, parameters, whether the generator builds it. No JSON. */
	public String promptText()
	{
		final StringBuilder sb = new StringBuilder();
		for (final WorkflowType t : this.byKey.values())
		{
			sb.append(t.key()).append(" (").append(t.label(Locale.US)).append("): parameters ");
			int i = 0;
			for (final Map.Entry<String, String> e : t.parameters().entrySet())
			{
				sb.append(i++ > 0 ? "; " : "").append(e.getKey()).append(" = ").append(e.getValue());
			}
			sb.append(t.generated() ? " | generated" : " | proposal only, not generated").append('\n');
		}
		return sb.toString();
	}
}
