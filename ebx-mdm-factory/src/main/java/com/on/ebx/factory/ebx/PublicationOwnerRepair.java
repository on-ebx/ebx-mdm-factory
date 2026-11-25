package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.WriterGuard.Expectation;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.core.WriterGuard.Writer;
import com.on.ebx.factory.core.WriterShapes;
import com.on.ebx.factory.migration.PublicationOwnerRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.schema.info.SchemaFacetTableRef;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Migration repair: fills {@code originalDataModel} on the DMA publications EBX Staging imported without it (see
 * {@link PublicationOwnerRule}). The field is read-only to every user over Data Services (HTTP 403 even for an
 * administrator), so it is written here in a {@code Procedure} with all privileges, from the {@code dataModelName} of
 * the version the publication's own {@code dataModel} foreign key points at - never parsed out of the formatted key.
 * An owner already set is never touched. {@code dryRun} lists what would change and writes nothing.
 */
public final class PublicationOwnerRepair
{
	private static final String DATASPACE = "ebx-dataModels-publications";
	private static final String DATASET = "ebx-publications";
	private static final Path PUBLICATIONS = Path.parse("/dataModels/publications");
	private static final Path NAME = Path.parse("./name");
	private static final Path DATA_MODEL = Path.parse("./dataModel");
	private static final Path ORIGINAL_DATA_MODEL = Path.parse("./originalDataModel");
	private static final Path VERSION_MODEL_NAME = Path.parse("./dataModelName");

	private PublicationOwnerRepair()
	{
	}

	/**
	 * Audit F-06: what this repair reads and writes in {@code ebx-dataModels-publications/ebx-publications}. Not measured on a live instance (the
	 * dataset is technical and not offered by the read-only tools): the paths come from the code that ran on the migration of 2026-09-26, so only their
	 * existence is asserted, plus that {@code dataModel} is a foreign key (the repair follows it with its table-reference facet).
	 */
	static List<Expectation> expectations()
	{
		final String t = "/dataModels/publications";
		final List<Expectation> out = new ArrayList<Expectation>();
		out.add(Expectation.of(t, Kind.TABLE));
		out.add(Expectation.of(t + "/name", Kind.OTHER));
		out.add(Expectation.of(t + "/dataModel", Kind.FOREIGN_KEY));
		out.add(Expectation.of(t + "/originalDataModel", Kind.OTHER));
		return out;
	}

	public static ObjectNode run(final Repository pRepository, final Session pSession, final boolean pDryRun)
	{
		final ObjectNode out = new ObjectMapper().createObjectNode();
		out.put("dryRun", pDryRun);
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(PublicationOwnerRepair.DATASPACE));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(PublicationOwnerRepair.DATASET));
		if (dataset == null)
		{
			out.put("error", "Dataset " + PublicationOwnerRepair.DATASPACE + "/" + PublicationOwnerRepair.DATASET + " not found");
			return out;
		}
		// audit F-06: a Procedure with all privileges on EBX's own publications dataset - refuse, writing nothing, when this build does not have the fields below
		final String refusal = WriterShapes.refusal(Writer.PUBLICATION_OWNER, pSession.getLocale(), dataset.getSchemaNode(), PublicationOwnerRepair.expectations());
		if (refusal != null)
		{
			out.put("error", refusal);
			return out;
		}
		final AdaptationTable table = dataset.getTable(PublicationOwnerRepair.PUBLICATIONS);
		final SchemaNode dataModelNode = table.getTableOccurrenceRootNode().getNode(PublicationOwnerRepair.DATA_MODEL);
		final SchemaFacetTableRef dataModelRef = dataModelNode == null ? null : dataModelNode.getFacetOnTableReference();

		final List<Adaptation> toSet = new ArrayList<>();
		final List<String> owners = new ArrayList<>();
		final ArrayNode changed = out.putArray("set");
		final ArrayNode unknown = out.putArray("unknown");
		int kept = 0;
		final RequestResult rows = table.createRequestResult(null);
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final Adaptation version = dataModelRef == null ? null : dataModelRef.getLinkedRecord(row);
				final String modelName = version == null ? null : version.getString(PublicationOwnerRepair.VERSION_MODEL_NAME);
				final String name = row.getString(PublicationOwnerRepair.NAME);
				switch (PublicationOwnerRule.decide(row.getString(PublicationOwnerRepair.ORIGINAL_DATA_MODEL), modelName))
				{
				case KEEP:
					kept++;
					break;
				case SET:
					toSet.add(row);
					owners.add(modelName);
					changed.addObject().put("publication", name).put("owner", modelName);
					break;
				default:
					unknown.add(name);
					break;
				}
			}
		}
		finally
		{
			rows.close();
		}
		out.put("kept", kept);
		if (pDryRun || toSet.isEmpty())
		{
			out.put("written", 0);
			return out;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, home).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				for (int i = 0; i < toSet.size(); i++)
				{
					final Adaptation row = toSet.get(i);
					final ValueContextForUpdate update = pContext.getContext(row.getAdaptationName());
					update.setValue(owners.get(i), PublicationOwnerRepair.ORIGINAL_DATA_MODEL);
					pContext.doModifyContent(row, update);
				}
			}
		});
		if (result.hasFailed())
		{
			out.put("written", 0);
			out.put("error", result.getExceptionFullMessage(Locale.ENGLISH));
			return out;
		}
		out.put("written", toSet.size());
		return out;
	}
}
