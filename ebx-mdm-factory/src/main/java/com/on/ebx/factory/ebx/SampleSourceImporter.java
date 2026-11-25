package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.project.ConnectionProvenance;
import com.on.ebx.factory.wizard.SampleFileRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * sample-files lot (2026-09-23): "drop the files in step 2, get a working source" - the product owner's decision the
 * same day ("Le fichier ne doit etre qu'un echantillon qui sert a la configuration"). Per file, in order,
 * failing that one file but continuing with the next: (1) validate the posted data URL against
 * {@link SampleFileRules}; (2) write (create or refresh) a {@code sample-file} Connectors connection named
 * after the file, in dataspace/dataset {@code Connectors}, the same write-into-another-module's-dataset
 * technique as {@code DmaToolbarWriter} (a plain {@code Procedure} on that home, never the MCP agent account -
 * this is a direct EBX write, no tool call); (3) sources-landing lot SOURCES (2026-09-24): QUEUE the SAME
 * onboarding chain the guided panel's own "Do everything" button runs ({@link SourceOnboarder#onboardAll})
 * into {@link SourcesRun} instead of running it in this request - the connection becomes a ready catalog
 * source, and the application is added to the project ({@link WizardData#addSource}, unless one already uses
 * this exact catalog source), entirely on {@code SourcesRun}'s own background thread; this method only
 * queues and returns.
 * <p>
 * Every write runs with the CALLING session's own rights (never the MCP agent account for step 2 - only the
 * queued {@code onboardAll} acts as the agent account on {@code ebx-connectors}, exactly as the guided
 * panel's own writes do - a {@link Session} captured before that background thread starts, same open
 * question as {@link SourcesRun}'s own class Javadoc). No {@code PipelineStep} row for the write in step (2):
 * onboarding a connection is not itself a project pass, same reasoning as {@link SourceOnboarder}'s own class
 * Javadoc - only {@code kernel.log}; {@link SourcesRun} owns the pass's own {@code PipelineStep}.
 */
public final class SampleSourceImporter
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final String LOG_PREFIX = "[mdm-factory] sample source import: ";

	// ebx-connectors' own home/table - same constants as ebx.AgentRuntime's own "read as data, no compile
	// dependency on ebx-connectors" rule, duplicated here for the same reason (this module never depends on
	// ebx-connectors' jar).
	private static final HomeKey CONNECTORS_HOME_KEY = HomeKey.forBranchName("Connectors");
	private static final String CONNECTORS_DATASET = "Connectors";
	private static final Path CONNECTOR_TABLE = Path.parse("/root/Connector");
	private static final Path C_ID = Path.parse("./id");
	private static final Path C_LABEL = Path.parse("./label");
	private static final Path C_TYPE = Path.parse("./type");
	private static final Path C_BASE_URL = Path.parse("./baseUrl");
	private static final Path C_AUTH_TYPE = Path.parse("./authType");
	private static final Path C_ACTIVE = Path.parse("./active");
	private static final Path C_SAMPLE_DATA = Path.parse("./sampleData");

	private static final String CONNECTOR_TYPE_SAMPLE_FILE = "sample-file";

	private SampleSourceImporter()
	{
	}

	// -------------------------------------------------------------------------------------------------------- DTOs

	/** One row posted by the wizard's hidden {@code mdmfSampleFiles} JSON array, or the REST route's body - see {@link #parseFileInputs}. */
	public static final class FileInput
	{
		public final String name;
		public final String dataUrl;
		public final String code;
		public final int trustRank;

		public FileInput(final String pName, final String pDataUrl, final String pCode, final int pTrustRank)
		{
			this.name = pName;
			this.dataUrl = pDataUrl;
			this.code = pCode;
			this.trustRank = pTrustRank;
		}
	}

	public static final class Report
	{
		/** See {@code wizard.SampleFileRules.ReportRow}'s own Javadoc for why this DTO lives in the wizard package rather than here. */
		public final List<SampleFileRules.ReportRow> files = new ArrayList<SampleFileRules.ReportRow>();
	}

	// --------------------------------------------------------------------------------------------------- posting

	/**
	 * Tolerant JSON-array parse of {@code [{name,size,code,trustRank,dataUrl}, ...]} (the wizard's own shape -
	 * {@code size} is ignored, it is a client-side-only display/validation field, never trusted for the
	 * server's own size cap) or {@code [{name,dataUrl,code,trustRank}, ...]} (the REST route's body). Never
	 * throws: blank/unparsable input, or a row missing {@code name}/{@code dataUrl}, is simply left out - the
	 * caller (both {@code ProjectWizardUserService} and {@code FactoryResource}) reads an empty result as "the
	 * files did not reach the server" per the task brief's own server guard.
	 */
	public static List<FileInput> parseFileInputs(final String pJson)
	{
		try
		{
			return SampleSourceImporter.parseFileInputsStrict(pJson);
		}
		catch (final java.io.IOException ex)
		{
			return new ArrayList<FileInput>();
		}
	}

	/**
	 * X3-08: the same parse, but a document that is not JSON, or that goes beyond a Jackson read bound, is THROWN instead of read as "no file": the REST route
	 * answers it with its code ({@code core.JsonLimits}) rather than "No usable file entry". The wizard keeps the tolerant {@link #parseFileInputs}.
	 */
	public static List<FileInput> parseFileInputsStrict(final String pJson) throws java.io.IOException
	{
		final List<FileInput> out = new ArrayList<FileInput>();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		final JsonNode array = SampleSourceImporter.MAPPER.readTree(pJson);
		if (array == null || !array.isArray())
		{
			return out;
		}
		for (final JsonNode row : array)
		{
			final String name = row.path("name").asText("");
			final String dataUrl = row.path("dataUrl").asText("");
			if (name.trim().isEmpty() || dataUrl.trim().isEmpty())
			{
				continue;
			}
			final String code = row.path("code").asText("");
			final int trustRank = row.path("trustRank").asInt(100);
			out.add(new FileInput(name, dataUrl, code, trustRank));
		}
		return out;
	}

	// ---------------------------------------------------------------------------------------------------- import

	public static Report importFiles(final Session pSession, final Adaptation pFactory, final String pProjectId, final List<FileInput> pFiles)
	{
		final Report report = new Report();
		long rawBytesSoFar = 0L;
		for (final FileInput file : pFiles)
		{
			final String connectionId = SampleFileRules.connectionId(file.name);

			final SampleFileRules.ParsedDataUrl parsed = SampleFileRules.parseDataUrl(file.dataUrl);
			if (!parsed.valid)
			{
				report.files.add(SampleFileRules.ReportRow.failed(file.name, connectionId, "The file's data could not be read: it was not in the expected format."));
				continue;
			}
			if (!SampleFileRules.KNOWN_MIMES.contains(parsed.mime))
			{
				report.files.add(SampleFileRules.ReportRow.failed(file.name, connectionId, "Unknown file type: choose a CSV, TSV, JSON or NDJSON file."));
				continue;
			}
			final long rawBytes = SampleFileRules.rawBytes(parsed.payload);
			if (rawBytes > SampleFileRules.RAW_BYTES_CAP)
			{
				report.files.add(SampleFileRules.ReportRow.failed(file.name, connectionId,
					"File " + file.name + " is about " + (rawBytes / 1024) + " KB, over the " + (SampleFileRules.RAW_BYTES_CAP / 1024) + " KB cap for one sample."));
				continue;
			}
			if (rawBytesSoFar + rawBytes > SampleFileRules.RAW_BYTES_CAP)
			{
				report.files.add(SampleFileRules.ReportRow.failed(file.name, connectionId,
					"This file would push the total of this submit past the " + (SampleFileRules.RAW_BYTES_CAP / 1024) + " KB cap: send it in a separate submit."));
				continue;
			}

			final boolean created;
			try
			{
				created = SampleSourceImporter.writeConnection(pSession, connectionId, file.name, file.dataUrl);
			}
			catch (final OperationException ex)
			{
				report.files.add(SampleFileRules.ReportRow.failed(file.name, connectionId, ex.getMessage()));
				continue;
			}
			rawBytesSoFar += rawBytes;
			// lot C (2026-10-17): the proof of who wrote the Connectors row - what a project deletion reads to remove the connections the factory created and only those
			SampleSourceImporter.recordConnection(pSession, pFactory, pProjectId, connectionId, created);

			// sources-landing lot SOURCES (2026-09-24): the catalog+scan chain (what used to be a synchronous
			// SourceOnboarder.onboardAll call right here, then addApplicationIfReady on its result) is now
			// QUEUED into SourcesRun instead of run in this request - task brief ("SOURCE_FILES_IMPORT (after
			// the files are imported, the per-connection catalog+scan part) enqueue into SourcesRun"). Only the
			// file -> connection write above stays synchronous. SourcesRun's own background loop does the
			// ready-check/dedup/WizardData.addSource sequence this method used to do inline (see
			// SourcesRun#addApplicationIfReady, which reuses this class's own applicationUsing) - using
			// pFile.code/pFile.trustRank when the person typed them, falling back to an auto-suggested code
			// otherwise (SourcesRun's own convention for the guided-connection path, applied here too so a
			// blank code in the sample-files table does not silently fail to queue).
			try
			{
				SourcesRun.startById(pSession, pFactory, pProjectId, connectionId, file.code, file.trustRank);
			}
			catch (final OperationException ex)
			{
				report.files.add(new SampleFileRules.ReportRow(file.name, connectionId, "", null, false, "", ex.getMessage()));
				continue;
			}
			report.files.add(SampleFileRules.ReportRow.queued(file.name, connectionId));
		}
		return report;
	}

	/** {@code null} when no application of this project already uses {@code pCatalogSourceId}, its {@code sourceSystem} code otherwise. Package-visible: reused by {@code SourcesRun}'s own auto-add-on-ready, same dedup rule for both entry paths. */
	static String applicationUsing(final Adaptation pFactory, final String pProjectId, final String pCatalogSourceId)
	{
		if (pProjectId == null || pProjectId.trim().isEmpty() || pCatalogSourceId == null || pCatalogSourceId.trim().isEmpty())
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE)
			.createRequestResult("./project=" + CatalogReader.quote(pProjectId) + " and ./catalogSource=" + CatalogReader.quote(pCatalogSourceId));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : row.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
		}
		finally
		{
			rows.close();
		}
	}

	// ------------------------------------------------------------------------------------------------- connection

	/**
	 * Step 2 of the class Javadoc: create or refresh {@code connectionId} as a {@code sample-file} connection -
	 * same write-into-another-module's-dataset technique as {@code DmaToolbarWriter#write} (a plain
	 * {@code Procedure} on the {@code Connectors} home, the caller's own session, never the MCP agent account).
	 * A row that exists with a DIFFERENT type refuses the file outright (the connection id is derived from the
	 * file name, so a genuine collision means the file should be renamed); a row that exists with the SAME type
	 * is updated (label, {@code baseUrl} only if it was blank - a person may have since pointed it at a real
	 * host, task brief: never clobber that - and {@code sampleData}, always replaced: it is only ever a
	 * sample); a row that does not exist is created with every field the task brief lists.
	 */
	private static boolean writeConnection(final Session pSession, final String pConnectionId, final String pFileName, final String pDataUrl) throws OperationException
	{
		final Repository repository = Repository.getDefault();
		final AdaptationHome connectorsHome = repository.lookupHome(SampleSourceImporter.CONNECTORS_HOME_KEY);
		if (connectorsHome == null)
		{
			throw EngineRefusal.coded("missing.connectorsModule", "ebx-connectors is not installed: cannot write connection " + pConnectionId, pConnectionId);
		}
		final Adaptation connectorsDataset = connectorsHome.findAdaptationOrNull(AdaptationName.forName(SampleSourceImporter.CONNECTORS_DATASET));
		if (connectorsDataset == null)
		{
			throw EngineRefusal.coded("missing.connectorsModule", "ebx-connectors is not installed: cannot write connection " + pConnectionId, pConnectionId);
		}
		final AdaptationTable connectorTable = connectorsDataset.getTable(SampleSourceImporter.CONNECTOR_TABLE);
		final Adaptation existing = connectorTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pConnectionId));
		if (existing != null)
		{
			final String existingType = existing.getString(SampleSourceImporter.C_TYPE);
			if (!SampleSourceImporter.CONNECTOR_TYPE_SAMPLE_FILE.equals(existingType))
			{
				throw EngineRefusal.coded("conflict.connectionExists", "A connection " + pConnectionId + " of type " + existingType + " already exists in Connectors: rename the file so it computes a different id.", pConnectionId, existingType);
			}
		}

		final String label = SampleFileRules.baseName(pFileName);
		final String hostUrl = SampleFileRules.host(pConnectionId);
		final String existingBaseUrl = existing == null ? null : existing.getString(SampleSourceImporter.C_BASE_URL);
		final boolean keepExistingBaseUrl = existing != null && existingBaseUrl != null && !existingBaseUrl.trim().isEmpty();

		final ProcedureResult result = ProgrammaticService.createForSession(pSession, connectorsHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				if (existing == null)
				{
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(connectorTable);
					value.setValue(pConnectionId, SampleSourceImporter.C_ID);
					value.setValue(label, SampleSourceImporter.C_LABEL);
					value.setValue(SampleSourceImporter.CONNECTOR_TYPE_SAMPLE_FILE, SampleSourceImporter.C_TYPE);
					value.setValue(hostUrl, SampleSourceImporter.C_BASE_URL);
					value.setValue("NONE", SampleSourceImporter.C_AUTH_TYPE);
					value.setValue(Boolean.TRUE, SampleSourceImporter.C_ACTIVE);
					value.setValue(pDataUrl, SampleSourceImporter.C_SAMPLE_DATA);
					pContext.doCreateOccurrence(value, connectorTable);
				}
				else
				{
					final ValueContextForUpdate value = pContext.getContext(existing.getAdaptationName());
					value.setValue(label, SampleSourceImporter.C_LABEL);
					if (!keepExistingBaseUrl)
					{
						value.setValue(hostUrl, SampleSourceImporter.C_BASE_URL);
					}
					value.setValue(pDataUrl, SampleSourceImporter.C_SAMPLE_DATA);
					pContext.doModifyContent(existing, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveFailed", result.getExceptionFullMessage(pSession == null ? Locale.ENGLISH : pSession.getLocale()));
		}
		LoggingCategory.getKernel().info(SampleSourceImporter.LOG_PREFIX + (existing == null ? "created" : "updated") + " connection " + pConnectionId + " from file " + pFileName);
		return existing == null;
	}

	/**
	 * Lot C (2026-10-17): a {@code SOURCE_CONNECTION} {@code PipelineStep} of the project, scope = the connection id, whose detail says whether the importer
	 * CREATED the Connectors row or found it ({@link ConnectionProvenance#connectionDetail}). Written even when the row already existed (created
	 * false) so the plan can list it as "found, kept". Best effort: a failure here is logged and never fails the import - the connection then has no
	 * proof of ownership, and a project deletion keeps it (never guesses). Nothing is written without a project id.
	 */
	private static void recordConnection(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pConnectionId, final boolean pCreated)
	{
		if (pFactory == null || pProjectId == null || pProjectId.trim().isEmpty())
		{
			return;
		}
		try
		{
			final String stepId = PipelineSteps.begin(pSession, pFactory, pProjectId, ConnectionProvenance.STEP_CONNECTION, pConnectionId);
			PipelineSteps.end(pSession, pFactory, stepId, true, pCreated ? "Connection " + pConnectionId + " created from a sample file." : "Connection " + pConnectionId
				+ " already existed: its sample was refreshed.", ConnectionProvenance.connectionDetail(pConnectionId, SampleSourceImporter.CONNECTOR_TYPE_SAMPLE_FILE, pCreated));
		}
		catch (final OperationException | RuntimeException ex)
		{
			LoggingCategory.getKernel().warn(SampleSourceImporter.LOG_PREFIX + "could not record the provenance of connection " + pConnectionId + " (a project deletion will keep it): " + ex);
		}
	}
}
