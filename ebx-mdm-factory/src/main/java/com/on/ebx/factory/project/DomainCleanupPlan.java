package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * What removing a generated domain (lot C1-D) must delete, in which order, from the {@code DomainObject} rows alone. Pure, no EBX type: the
 * EBX-facing {@code ebx.DomainCleanup} executes the items with {@code GeneratedObjectDeleter}. Order, downstream first: the matching configuration
 * (it owns a metadataset inside the domain dataspace), the workflow publication then its model, the dataspace (the dataset and the reference rows
 * go with it), the form layouts, the published data model (the scripts and the model permissions go with it). Each item carries one of the
 * {@link ProjectDeletionPlan} options, so the project deletion screen's ticks keep their meaning: matching, flows, dataspaces, models.
 */
public final class DomainCleanupPlan
{
	public enum Kind
	{
		MATCHING_CONFIG, WORKFLOW_PUBLICATION, WORKFLOW_MODEL, DOMAIN_DATASPACE, FORM_LAYOUTS, DATA_MODEL
	}

	/** A {@code DomainObject} row, as facts. */
	public static final class Row
	{
		public final String kind;
		public final String name;
		public final String path;

		public Row(final String pKind, final String pName, final String pPath)
		{
			this.kind = pKind;
			this.name = pName;
			this.path = pPath;
		}
	}

	/** One removal. */
	public static final class Item
	{
		public final Kind kind;
		public final String name;
		/** The table path of a matching configuration; null otherwise. */
		public final String tablePath;
		/** The data model that owns the object (matching configurations are addressed through it). */
		public final String modelName;
		public final String option;

		Item(final Kind pKind, final String pName, final String pTablePath, final String pModelName, final String pOption)
		{
			this.kind = pKind;
			this.name = pName;
			this.tablePath = pTablePath;
			this.modelName = pModelName;
			this.option = pOption;
		}
	}

	private DomainCleanupPlan()
	{
	}

	public static List<Item> build(final List<Row> pRows)
	{
		String model = null;
		for (final Row r : pRows)
		{
			if ("DATA_MODEL".equals(r.kind) || "PUBLICATION".equals(r.kind))
			{
				model = r.name;
			}
		}
		final List<Item> items = new ArrayList<Item>();
		final Set<String> seen = new LinkedHashSet<String>();
		for (final Row r : pRows)
		{
			if ("MATCHING_POLICY".equals(r.kind) && r.path != null && !r.path.isEmpty() && seen.add("M:" + r.path))
			{
				items.add(new Item(Kind.MATCHING_CONFIG, r.path, r.path, model, ProjectDeletionPlan.OPTION_MATCHING));
			}
		}
		for (final Row r : pRows)
		{
			if ("WORKFLOW_PUBLICATION".equals(r.kind) && seen.add("WP:" + r.name))
			{
				items.add(new Item(Kind.WORKFLOW_PUBLICATION, r.name, null, model, ProjectDeletionPlan.OPTION_FLOWS));
			}
		}
		for (final Row r : pRows)
		{
			if ("WORKFLOW_MODEL".equals(r.kind) && seen.add("WM:" + r.name))
			{
				items.add(new Item(Kind.WORKFLOW_MODEL, r.name, null, model, ProjectDeletionPlan.OPTION_FLOWS));
			}
		}
		for (final Row r : pRows)
		{
			if ("DATASPACE".equals(r.kind) && seen.add("D:" + r.name))
			{
				items.add(new Item(Kind.DOMAIN_DATASPACE, r.name, null, model, ProjectDeletionPlan.OPTION_DATASPACES));
			}
		}
		boolean hasLayouts = false;
		for (final Row r : pRows)
		{
			hasLayouts |= "LAYOUT".equals(r.kind);
		}
		if (hasLayouts && model != null)
		{
			items.add(new Item(Kind.FORM_LAYOUTS, model, null, model, ProjectDeletionPlan.OPTION_MODELS));
		}
		if (model != null)
		{
			items.add(new Item(Kind.DATA_MODEL, model, null, model, ProjectDeletionPlan.OPTION_MODELS));
		}
		return items;
	}
}
