package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Lot C1-B (docs/LOT-C1.md sec 5-6): the EBX-bound reads and writes of the CENTRALISATION wizard path - the {@code ProjectPack},
 * {@code Difference} and {@code DomainObject} rows into the {@link WizardSnapshot}, the choice of packs, the need, and the edit of a
 * difference's JSON. Thin on purpose: what a screen shows and decides lives in {@code wizard.AccelPages}/{@code WizardRules}. The
 * decision of a difference is NOT here: it goes through {@link ProposalReviews#decide} (nature {@code DIFFERENCE}).
 */
final class AccelData
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	/** {@code ProjectPack.specification} and {@code Difference.detail} are both capped at this many characters in the XSD. */
	static final int TEXT_MAX = 20000;
	/** The job kinds of this path all start with this prefix (sub-lot C: {@code ACCEL_PROPOSAL}, sub-lot D: {@code ACCEL_GENERATE}). */
	static final String JOB_PREFIX = "ACCEL_";

	private AccelData()
	{
	}

	// ----------------------------------------------------------------------------------------------------------- reading

	/** Fills the CENTRALISATION part of {@code pSnapshot} (catalogue, chosen packs, differences, domain objects, running job). Best effort per part: one unreadable part never blanks the others. */
	static void read(final Adaptation pFactory, final String pProjectId, final Locale pLocale, final WizardSnapshot pSnapshot)
	{
		try
		{
			pSnapshot.packCatalogue.addAll(AccelHooks.packCatalogue(pLocale));
		}
		catch (final RuntimeException ex)
		{
			// the catalogue is a nice-to-have of the Pack step (it says so when empty); never break the snapshot for it
			pSnapshot.packCatalogue.clear();
		}
		AccelData.readPacks(pFactory, pProjectId, pSnapshot);
		final Adaptation project = AccelData.project(pFactory, pProjectId);
		pSnapshot.accelBlankStart = AccelData.blankStart(project);
		pSnapshot.accelSpecification = AccelData.projectSpecification(project);
		AccelData.readDifferences(pFactory, pProjectId, pSnapshot);
		AccelData.readDomainObjects(pFactory, pProjectId, pSnapshot);
		AccelData.readJob(pFactory, pProjectId, pSnapshot);
	}

	private static List<Adaptation> rowsOf(final Adaptation pFactory, final Path pTable, final String pProjectId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult rows = UserView.current().rows(pFactory.getTable(pTable), "./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			rows.close();
		}
		Collections.sort(out, new Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pA, final Adaptation pB)
			{
				return Long.compare(AccelData.idOf(pA), AccelData.idOf(pB));
			}
		});
		return out;
	}

	private static long idOf(final Adaptation pRow)
	{
		try
		{
			return Long.parseLong(pRow.getOccurrencePrimaryKey().format());
		}
		catch (final NumberFormatException ex)
		{
			return Long.MAX_VALUE;
		}
	}

	static Adaptation project(final Adaptation pFactory, final String pProjectId)
	{
		return pProjectId == null || pProjectId.isEmpty() ? null : AccelData.byKey(pFactory.getTable(FactoryPaths.PROJECT), pProjectId);
	}

	/** {@code Project.accelBlankStart}; false when the project or the field (a model that predates it) is missing. */
	static boolean blankStart(final Adaptation pProject)
	{
		return pProject != null && pProject.getSchemaNode().getNode(FactoryPaths.Project.ACCEL_BLANK_START) != null
			&& Boolean.TRUE.equals(UserView.current().value(pProject, FactoryPaths.Project.ACCEL_BLANK_START));
	}

	/** {@code Project.accelSpecification}; empty when the project or the field is missing. */
	static String projectSpecification(final Adaptation pProject)
	{
		return pProject == null || pProject.getSchemaNode().getNode(FactoryPaths.Project.ACCEL_SPECIFICATION) == null ? ""
			: AccelData.text(pProject, FactoryPaths.Project.ACCEL_SPECIFICATION);
	}

	/** Writes the need (and, when {@code pBlank} is not null, the blank-start flag) on the project row; a model without the fields is left alone. */
	private static void writeProject(final ProcedureContext pContext, final Adaptation pProject, final String pNeed, final Boolean pBlank) throws OperationException
	{
		if (pProject == null || pProject.getSchemaNode().getNode(FactoryPaths.Project.ACCEL_SPECIFICATION) == null)
		{
			return;
		}
		final ValueContextForUpdate value = pContext.getContext(pProject.getAdaptationName());
		value.setValue(AccelData.orNull(pNeed), FactoryPaths.Project.ACCEL_SPECIFICATION);
		if (pBlank != null)
		{
			value.setValue(pBlank, FactoryPaths.Project.ACCEL_BLANK_START);
		}
		pContext.doModifyContent(pProject, value);
	}

	/** Second permissions pass (2026-10-14): through {@link UserView#current()} - the user's view inside a screen's snapshot read, the engine's otherwise. */
	private static String text(final Adaptation pRow, final Path pPath)
	{
		return UserView.current().text(pRow, pPath);
	}

	private static Adaptation byKey(final com.onwbp.adaptation.AdaptationTable pTable, final String pKey)
	{
		final UserView view = UserView.current();
		return view.engine() ? pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pKey)) : view.lookup(pTable, pKey);
	}

	private static void readPacks(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot)
	{
		for (final Adaptation row : AccelData.rowsOf(pFactory, FactoryPaths.PROJECT_PACK, pProjectId))
		{
			final WizardSnapshot.ProjectPackInfo info = new WizardSnapshot.ProjectPackInfo();
			info.id = row.getOccurrencePrimaryKey().format();
			info.packKey = AccelData.text(row, FactoryPaths.ProjectPack.PACK_KEY);
			info.packVersion = AccelData.text(row, FactoryPaths.ProjectPack.PACK_VERSION);
			info.specification = AccelData.text(row, FactoryPaths.ProjectPack.SPECIFICATION);
			final String kind = AccelData.text(row, FactoryPaths.ProjectPack.SPECIFICATION_KIND);
			info.specificationKind = kind.isEmpty() ? FactoryPaths.ProjectPack.KIND_TEXT : kind;
			final String chosenBy = AccelData.text(row, FactoryPaths.ProjectPack.CHOSEN_BY);
			info.chosenBy = chosenBy.isEmpty() ? FactoryPaths.ProjectPack.CHOSEN_BY_USER : chosenBy;
			final String status = AccelData.text(row, FactoryPaths.ProjectPack.STATUS);
			info.status = status.isEmpty() ? FactoryPaths.ProjectPack.STATUS_CHOSEN : status;
			final List<?> options = UserView.current().list(row, FactoryPaths.ProjectPack.OPTIONS);
			if (options != null)
			{
				for (final Object option : options)
				{
					if (option != null)
					{
						info.options.add(option.toString());
					}
				}
			}
			pSnapshot.projectPacks.add(info);
		}
	}

	private static void readDifferences(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot)
	{
		final List<WizardSnapshot.DifferenceInfo> out = new ArrayList<WizardSnapshot.DifferenceInfo>();
		for (final Adaptation row : AccelData.rowsOf(pFactory, FactoryPaths.DIFFERENCE, pProjectId))
		{
			final WizardSnapshot.DifferenceInfo info = new WizardSnapshot.DifferenceInfo();
			info.id = row.getOccurrencePrimaryKey().format();
			final Object pass = UserView.current().value(row, FactoryPaths.Difference.PASS);
			info.pass = pass instanceof Number ? ((Number) pass).intValue() : 1;
			info.nature = AccelData.text(row, FactoryPaths.Difference.NATURE);
			info.what = AccelData.text(row, FactoryPaths.Difference.WHAT);
			info.where = AccelData.text(row, FactoryPaths.Difference.WHERE);
			final String place = AccelData.text(row, FactoryPaths.Difference.PLACE_STATE);
			info.placeState = place.isEmpty() ? FactoryPaths.Difference.PLACED : place;
			info.detail = AccelData.text(row, FactoryPaths.Difference.DETAIL);
			info.why = AccelData.text(row, FactoryPaths.Difference.WHY);
			info.packKey = AccelData.text(row, FactoryPaths.Difference.PACK_KEY);
			info.blockKey = AccelData.text(row, FactoryPaths.Difference.BLOCK_KEY);
			info.libraryFunction = AccelData.text(row, FactoryPaths.Difference.LIBRARY_FUNCTION);
			info.workflowType = AccelData.text(row, FactoryPaths.Difference.WORKFLOW_TYPE);
			info.appliedObject = AccelData.text(row, FactoryPaths.Difference.APPLIED_OBJECT);
			info.origin = AccelData.text(row, FactoryPaths.Difference.ORIGIN);
			final Object confidence = UserView.current().value(row, FactoryPaths.Difference.CONFIDENCE);
			info.confidence = confidence instanceof Number ? Integer.valueOf(((Number) confidence).intValue()) : null;
			info.rationale = AccelData.text(row, FactoryPaths.Difference.RATIONALE);
			final String status = AccelData.text(row, FactoryPaths.Difference.STATUS);
			info.status = status.isEmpty() ? "PROPOSED" : status;
			out.add(info);
		}
		// grouped by pass: a stable sort keeps the id order inside a pass
		Collections.sort(out, new Comparator<WizardSnapshot.DifferenceInfo>()
		{
			@Override
			public int compare(final WizardSnapshot.DifferenceInfo pA, final WizardSnapshot.DifferenceInfo pB)
			{
				return Integer.compare(pA.pass, pB.pass);
			}
		});
		pSnapshot.differences.addAll(out);
	}

	private static void readDomainObjects(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot)
	{
		final AdaptationTable steps = pFactory.getTable(FactoryPaths.PIPELINE_STEP);
		final Map<String, String> statusByStep = new HashMap<String, String>();
		for (final Adaptation row : AccelData.rowsOf(pFactory, FactoryPaths.DOMAIN_OBJECT, pProjectId))
		{
			final WizardSnapshot.DomainObjectInfo info = new WizardSnapshot.DomainObjectInfo();
			info.id = row.getOccurrencePrimaryKey().format();
			info.kind = AccelData.text(row, FactoryPaths.DomainObject.KIND);
			info.name = AccelData.text(row, FactoryPaths.DomainObject.NAME);
			info.path = AccelData.text(row, FactoryPaths.DomainObject.PATH);
			info.detail = AccelData.text(row, FactoryPaths.DomainObject.DETAIL);
			final Object created = UserView.current().value(row, FactoryPaths.DomainObject.CREATED_ON);
			info.createdOn = created instanceof Date ? new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm").format((Date) created) : "";
			final String stepId = AccelData.text(row, FactoryPaths.DomainObject.STEP);
			if (!stepId.isEmpty())
			{
				if (!statusByStep.containsKey(stepId))
				{
					final Adaptation step = AccelData.byKey(steps, stepId);
					statusByStep.put(stepId, step == null ? "" : AccelData.text(step, FactoryPaths.PipelineStep.STATUS));
				}
				info.stepStatus = statusByStep.get(stepId);
			}
			pSnapshot.domainObjects.add(info);
		}
	}

	private static void readJob(final Adaptation pFactory, final String pProjectId, final WizardSnapshot pSnapshot)
	{
		try
		{
			final JsonNode active = FactoryJobStatus.of(UserView.current(), pFactory, pProjectId).path("active");
			for (final JsonNode job : active)
			{
				final String kind = job.path("kind").asText("");
				if (kind.startsWith(AccelData.JOB_PREFIX))
				{
					final WizardSnapshot.AccelJobInfo info = new WizardSnapshot.AccelJobInfo();
					info.kind = kind;
					info.status = job.path("status").asText("RUNNING");
					info.message = job.has("stepMessage") ? job.path("stepMessage").asText("") : job.path("message").asText("");
					info.progressJson = job.path("progressJson").asText("");
					pSnapshot.accelJob = info;
					return;
				}
			}
		}
		catch (final RuntimeException ex)
		{
			// a job read that fails shows no progress, never a broken step
			pSnapshot.accelJob = null;
		}
	}

	// ----------------------------------------------------------------------------------------------------------- writing

	/**
	 * Writes the packs chosen for the project: a {@code ProjectPack} row per key (created if missing, its options and the need rewritten if it
	 * exists), and removes the {@code CHOSEN} rows of packs no longer chosen. A {@code GENERATED} row is never touched. {@code pOptionsByPack}
	 * maps a pack key to the option keys taken. {@code pVersions} gives each key's catalogue version (empty when unknown).
	 */
	static void savePacks(final Session pSession, final Adaptation pFactory, final String pProjectId, final List<String> pPackKeys,
		final Map<String, List<String>> pOptionsByPack, final Map<String, String> pVersions, final String pNeed, final String pChosenBy) throws OperationException
	{
		final String need = pNeed == null ? "" : TextLimits.truncate(pNeed.trim(), AccelData.TEXT_MAX);
		final Map<String, Adaptation> existing = new LinkedHashMap<String, Adaptation>();
		for (final Adaptation row : AccelData.rowsOf(pFactory, FactoryPaths.PROJECT_PACK, pProjectId))
		{
			existing.put(AccelData.text(row, FactoryPaths.ProjectPack.PACK_KEY), row);
		}
		final AdaptationTable table = pFactory.getTable(FactoryPaths.PROJECT_PACK);
		final Adaptation project = AccelData.project(pFactory, pProjectId);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				// no pack ticked = "Skip the packs, start blank": the need alone is the start
				AccelData.writeProject(pContext, project, need, Boolean.valueOf(pPackKeys.isEmpty()));
				for (final String key : pPackKeys)
				{
					final Adaptation row = existing.get(key);
					final List<String> options = pOptionsByPack.get(key) == null ? new ArrayList<String>() : pOptionsByPack.get(key);
					if (row == null)
					{
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						value.setValue(pProjectId, FactoryPaths.ProjectPack.PROJECT);
						value.setValue(key, FactoryPaths.ProjectPack.PACK_KEY);
						value.setValue(AccelData.orNull(pVersions.get(key)), FactoryPaths.ProjectPack.PACK_VERSION);
						value.setValue(options, FactoryPaths.ProjectPack.OPTIONS);
						value.setValue(AccelData.orNull(need), FactoryPaths.ProjectPack.SPECIFICATION);
						value.setValue(FactoryPaths.ProjectPack.KIND_TEXT, FactoryPaths.ProjectPack.SPECIFICATION_KIND);
						value.setValue(pChosenBy, FactoryPaths.ProjectPack.CHOSEN_BY);
						value.setValue(FactoryPaths.ProjectPack.STATUS_CHOSEN, FactoryPaths.ProjectPack.STATUS);
						pContext.doCreateOccurrence(value, table);
					}
					else if (!FactoryPaths.ProjectPack.STATUS_GENERATED.equals(AccelData.text(row, FactoryPaths.ProjectPack.STATUS)))
					{
						final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
						value.setValue(options, FactoryPaths.ProjectPack.OPTIONS);
						value.setValue(AccelData.orNull(need), FactoryPaths.ProjectPack.SPECIFICATION);
						pContext.doModifyContent(row, value);
					}
				}
				for (final Map.Entry<String, Adaptation> entry : existing.entrySet())
				{
					if (!pPackKeys.contains(entry.getKey()) && !FactoryPaths.ProjectPack.STATUS_GENERATED.equals(AccelData.text(entry.getValue(), FactoryPaths.ProjectPack.STATUS)))
					{
						pContext.doDelete(entry.getValue().getAdaptationName(), false);
					}
				}
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
	}

	/**
	 * Writes the need on the project and on every pack row of the project; returns how many rows it reached, the project included (0 = neither the
	 * project field, a model that predates it, nor a pack row: the caller keeps the text on the screen).
	 */
	static int saveNeed(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pNeed) throws OperationException
	{
		final List<Adaptation> rows = AccelData.rowsOf(pFactory, FactoryPaths.PROJECT_PACK, pProjectId);
		final Adaptation project = AccelData.project(pFactory, pProjectId);
		final boolean onProject = project != null && project.getSchemaNode().getNode(FactoryPaths.Project.ACCEL_SPECIFICATION) != null;
		if (rows.isEmpty() && !onProject)
		{
			return 0;
		}
		final String need = pNeed == null ? "" : TextLimits.truncate(pNeed.trim(), AccelData.TEXT_MAX);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				AccelData.writeProject(pContext, project, need, null);
				for (final Adaptation row : rows)
				{
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(AccelData.orNull(need), FactoryPaths.ProjectPack.SPECIFICATION);
					pContext.doModifyContent(row, value);
				}
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
		return rows.size() + (onProject ? 1 : 0);
	}

	/**
	 * "Edit" on a difference: {@code pDetailJson} replaces {@code Difference.detail} (the line is then accepted by the caller). It must parse as a
	 * JSON object, and a line already decided or applied is not editable. Written as {@code pSession}, so the audit trail names the person.
	 */
	static void replaceDetail(final Session pSession, final Adaptation pFactory, final String pDifferenceId, final String pDetailJson) throws OperationException
	{
		final String json = pDetailJson == null ? "" : pDetailJson.trim();
		try
		{
			final JsonNode parsed = AccelData.MAPPER.readTree(json);
			if (parsed == null || !parsed.isObject())
			{
				throw EngineRefusal.of("wizard.accel.detailInvalid", "The edited detail must be a JSON object.");
			}
		}
		catch (final java.io.IOException ex)
		{
			throw EngineRefusal.of("wizard.accel.detailInvalid", "The edited detail is not valid JSON: " + ex.getMessage());
		}
		if (json.length() > AccelData.TEXT_MAX)
		{
			throw EngineRefusal.of("wizard.accel.detailInvalid", "The edited detail is longer than " + AccelData.TEXT_MAX + " characters.");
		}
		final Adaptation row = ProposalReviews.byId(pFactory.getTable(FactoryPaths.DIFFERENCE), pDifferenceId);
		if (row == null)
		{
			throw EngineRefusal.of("wizard.accel.differenceGone", "The difference no longer exists.");
		}
		if (!"PROPOSED".equals(AccelData.text(row, FactoryPaths.Difference.STATUS)))
		{
			throw EngineRefusal.of("wizard.accel.notEditable", "Only a difference still waiting for a decision can be edited.");
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(json, FactoryPaths.Difference.DETAIL);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw WizardData.failure(result, pSession == null ? Locale.ENGLISH : pSession.getLocale());
		}
	}

	private static String orNull(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty() ? null : pValue;
	}
}
