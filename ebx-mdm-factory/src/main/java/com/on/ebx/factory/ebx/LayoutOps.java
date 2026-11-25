package com.on.ebx.factory.ebx;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Writes the {@code FormLayout} row of the Layout Designer for a generated table (lot C1-D, step ACCEL_FORMS; recipe of {@code docs/ACCELERATORS.md}
 * section 11): dataspace {@code LayoutDesigner}, dataset {@code LayoutDesigner}, table {@code /root/FormLayout}, fields {@code model} (the
 * publication URN), {@code table}, {@code active}, {@code layout} (the JSON text), {@code version}, {@code label}. The Layout API lot of the
 * Designer (docs/ACCELERATORS.md section 12) will replace the direct write. The caller then declares the form on the model with
 * {@code execute_operation(set_form_layout)} and publishes.
 */
final class LayoutOps
{
	static final String DESIGNER_CLASS = "com.on.ebx.layout.runtime.ConfigurableForm";
	static final String DATASPACE = "LayoutDesigner";
	static final String DATASET = "LayoutDesigner";
	static final Path FORM_LAYOUT = Path.parse("/root/FormLayout");

	private LayoutOps()
	{
	}

	/** True when the Designer runtime is on the class path of this module (it sits in the server's {@code lib}). */
	static boolean designerPresent()
	{
		try
		{
			Class.forName(LayoutOps.DESIGNER_CLASS, false, LayoutOps.class.getClassLoader());
			return true;
		}
		catch (final ClassNotFoundException | LinkageError ex)
		{
			return false;
		}
	}

	static String publicationUrn(final String pPublicationName)
	{
		return "urn:ebx:publication:" + pPublicationName;
	}

	/** A deep copy of the layout with every {@code ${publication}} replaced by the publication URN. */
	static ObjectNode render(final JsonNode pLayout, final String pPublicationName)
	{
		return (ObjectNode) LayoutOps.replace(pLayout.deepCopy(), "${publication}", LayoutOps.publicationUrn(pPublicationName));
	}

	private static JsonNode replace(final JsonNode pNode, final String pToken, final String pValue)
	{
		if (pNode.isObject())
		{
			final ObjectNode o = (ObjectNode) pNode;
			final Iterator<String> names = o.fieldNames();
			final java.util.List<String> list = new java.util.ArrayList<String>();
			while (names.hasNext())
			{
				list.add(names.next());
			}
			for (final String n : list)
			{
				o.set(n, LayoutOps.replace(o.get(n), pToken, pValue));
			}
		}
		else if (pNode.isArray())
		{
			final ArrayNode a = (ArrayNode) pNode;
			for (int i = 0; i < a.size(); i++)
			{
				a.set(i, LayoutOps.replace(a.get(i), pToken, pValue));
			}
		}
		else if (pNode.isTextual() && pNode.asText().contains(pToken))
		{
			return com.fasterxml.jackson.databind.node.TextNode.valueOf(pNode.asText().replace(pToken, pValue));
		}
		return pNode;
	}

	/** The fields of the {@code FormLayout} row, by field name (without {@code ./}). */
	static Map<String, Object> rowFields(final JsonNode pLayout, final String pTablePath, final String pPublicationName, final int pVersion)
	{
		final ObjectNode layout = LayoutOps.render(pLayout, pPublicationName);
		final Map<String, Object> out = new LinkedHashMap<String, Object>();
		out.put("model", LayoutOps.publicationUrn(pPublicationName));
		out.put("table", pTablePath);
		out.put("active", Boolean.TRUE);
		out.put("layout", layout.toString());
		out.put("version", Integer.valueOf(pVersion));
		out.put("label", layout.path("name").asText(pTablePath.substring(pTablePath.lastIndexOf('/') + 1) + " (pack)"));
		return out;
	}

	static int nextVersion(final Object pStoredVersion)
	{
		return pStoredVersion instanceof Number ? ((Number) pStoredVersion).intValue() + 1 : 1;
	}

	/** The Designer's dataset, or null when its dataspace or dataset is absent. */
	static Adaptation designerDataset(final Repository pRepository)
	{
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(LayoutOps.DATASPACE));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(LayoutOps.DATASET));
	}

	/** Writes (creates, or updates with the version incremented) the row. Returns the version written. */
	static int write(final Session pSession, final Adaptation pDesignerDataset, final JsonNode pLayout, final String pTablePath, final String pPublicationName)
		throws OperationException
	{
		final AdaptationTable table = pDesignerDataset.getTable(LayoutOps.FORM_LAYOUT);
		final String urn = LayoutOps.publicationUrn(pPublicationName);
		final int[] version = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDesignerDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				Adaptation existing = null;
				final RequestResult rows = table.createRequestResult("./model=" + CatalogReader.quote(urn) + " and ./table=" + CatalogReader.quote(pTablePath));
				try
				{
					existing = rows.nextAdaptation();
				}
				finally
				{
					rows.close();
				}
				version[0] = LayoutOps.nextVersion(existing == null ? null : existing.get(Path.parse("./version")));
				final Map<String, Object> fields = LayoutOps.rowFields(pLayout, pTablePath, pPublicationName, version[0]);
				final ValueContextForUpdate value = existing == null ? pContext.getContextForNewOccurrence(table) : pContext.getContext(existing.getAdaptationName());
				for (final Map.Entry<String, Object> f : fields.entrySet())
				{
					value.setValue(f.getValue(), Path.parse("./" + f.getKey()));
				}
				if (existing == null)
				{
					pContext.doCreateOccurrence(value, table);
				}
				else
				{
					pContext.doModifyContent(existing, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("external.formLayout", "Writing the FormLayout row of " + pTablePath + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH), pTablePath);
		}
		return version[0];
	}

	/** Deletes the rows of a publication (project deletion). Returns how many. */
	static int deleteFor(final Session pSession, final Adaptation pDesignerDataset, final String pPublicationName) throws OperationException
	{
		final AdaptationTable table = pDesignerDataset.getTable(LayoutOps.FORM_LAYOUT);
		final int[] count = new int[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDesignerDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final RequestResult rows = table.createRequestResult("./model=" + CatalogReader.quote(LayoutOps.publicationUrn(pPublicationName)));
				final java.util.List<Adaptation> list = new java.util.ArrayList<Adaptation>();
				try
				{
					for (Adaptation r = rows.nextAdaptation(); r != null; r = rows.nextAdaptation())
					{
						list.add(r);
					}
				}
				finally
				{
					rows.close();
				}
				for (final Adaptation r : list)
				{
					pContext.doDelete(r.getAdaptationName(), false);
					count[0]++;
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("external.formLayoutDelete", "Deleting the FormLayout rows of " + pPublicationName + " failed: " + result.getExceptionFullMessage(Locale.ENGLISH),
				pPublicationName);
		}
		return count[0];
	}
}
