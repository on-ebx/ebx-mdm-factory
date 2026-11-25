package com.on.ebx.factory.backup;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The objects the Factory GENERATED in ON EBX for a project, as they are named in the rows of the backup (audit F-30): the archive lists them under
 * {@code references} so the person who holds the file knows what it does not contain. Fed with every exported row, pure (it reads field names and values
 * only). Names are what the rows say: a dataspace or dataset key, a model or publication name, a Connectors mapping id, a matching policy code.
 */
public final class ReferenceCollector
{
	/** One generated object. */
	public static final class Reference
	{
		public final String kind;
		public final String name;
		public final String detail;

		Reference(final String pKind, final String pName, final String pDetail)
		{
			this.kind = pKind;
			this.name = pName;
			this.detail = pDetail;
		}
	}

	private final Map<String, Reference> found = new LinkedHashMap<String, Reference>();

	/** Looks at one exported row ({@code pValues}: field name to value) of {@code pTable}. */
	public void offer(final String pTable, final Map<String, Object> pValues)
	{
		if ("Project".equals(pTable))
		{
			this.dataspace("landing dataspace", pValues.get("landingDataspace"), "");
			this.dataspace("staging dataspace", pValues.get("stagingDataspace"), "dataset " + ReferenceCollector.text(pValues.get("stagingDataset")));
			this.dataspace("master dataspace", pValues.get("masterDataspace"), "dataset " + ReferenceCollector.text(pValues.get("masterDataset")));
		}
		else if ("ProjectSource".equals(pTable))
		{
			final String source = ReferenceCollector.text(pValues.get("sourceSystem"));
			this.add("data model", pValues.get("landingModel"), "landing of " + source);
			this.add("data model publication", pValues.get("landingPublication"), "landing of " + source);
			this.add("dataset", pValues.get("landingDataset"), "landing of " + source);
		}
		else if ("LandingTable".equals(pTable))
		{
			this.add("Connectors mapping", pValues.get("inboundMapping"), "landing flow of " + ReferenceCollector.text(pValues.get("tableName")));
		}
		else if ("MatchPlan".equals(pTable))
		{
			this.add("matching policy", pValues.get("matchingPolicy"), "Match and Merge configuration of the master table");
			this.add("merge policy", pValues.get("mergePolicy"), "Match and Merge configuration of the master table");
		}
		else if ("DistributionTarget".equals(pTable))
		{
			this.add("Connectors connection", pValues.get("connector"), "distribution target");
			final Object ids = pValues.get("connectorsMappingIds");
			if (ids != null)
			{
				for (final String id : String.valueOf(ids).split("[,;\\s]+"))
				{
					this.add("Connectors mapping", id, "distribution target");
				}
			}
			this.add("scheduler schedule", pValues.get("scheduleKey"), "distribution target");
			this.add("workflow launcher", pValues.get("launcherName"), "distribution target");
		}
	}

	/** The generated models of the project by convention (their names are computed from the code, not stored in a row). */
	public void offerModels(final String pStagingModel, final String pMasterModel)
	{
		this.add("data model", pStagingModel, "staging model (name computed from the project code)");
		this.add("data model", pMasterModel, "master model (name computed from the project code)");
	}

	public List<Reference> references()
	{
		return new ArrayList<Reference>(this.found.values());
	}

	private void dataspace(final String pKind, final Object pKey, final String pDetail)
	{
		this.add(pKind, pKey, pDetail);
	}

	private void add(final String pKind, final Object pName, final String pDetail)
	{
		final String name = ReferenceCollector.text(pName);
		if (!name.isEmpty())
		{
			final String id = pKind + "|" + name;
			if (!this.found.containsKey(id))
			{
				this.found.put(id, new Reference(pKind, name, pDetail));
			}
		}
	}

	private static String text(final Object pValue)
	{
		return pValue == null ? "" : String.valueOf(pValue).trim();
	}
}
