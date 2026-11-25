package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.accel.DomainPlan;
import com.on.ebx.factory.accel.PackCatalog;
import com.on.ebx.factory.accel.ReferenceTable;
import com.on.ebx.factory.accel.WorkflowAssembler;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.core.PublishDecision;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.master.MameAddonDecision;
import com.on.ebx.factory.master.MameAnswer;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.schema.Path;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeCreationSpec;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Generation of one domain of a Centralisation project (lot C1-D, {@code docs/LOT-C1.md} section 7): a {@link FactoryJobQueue} job running nine
 * steps in order, each a {@code PipelineStep} (names {@link #STEPS}), each skipped when already {@code DONE} ({@link PipelineSteps#isDone}) and each
 * writing its {@code DomainObject} rows. A failure ends the step FAILED with the reason and stops the job; a step that cannot run for want of an
 * optional piece (scripting or workflow-modeling server, Layout Designer runtime, Match and Merge add-on) ends SKIPPED with a message and the job goes
 * on - a skipped step is not DONE, so the next run tries it again. What to write comes from the pure {@link DomainPlan}; the recipes are the
 * measured ones of {@code docs/ACCELERATORS.md} sections 10-15. Every tool answer is checked ({@code error}, {@code success}/{@code valid}/{@code found},
 * {@code rolledBack}, {@code code}) - never "no exception" taken for success.
 * <p>
 * The domain dataspace is a CHILD of {@code Reference} created in Java as {@link MasterGenerator} does; no dataset is ever created in {@code Reference}
 * itself (a dataset created there leaks into every existing child dataspace, measured 2026-10-12).
 * <p>
 * Not run live: sub-lot D was written with no EBX call allowed (see {@code docs/LOT-C1.md} "D - done").
 */
public final class DomainGenerator
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static final String STEP = "ACCEL_GENERATE_RUN";
	public static final String S_DATASPACE = "ACCEL_DATASPACE";
	public static final String S_MODEL = "ACCEL_MODEL";
	public static final String S_REFERENCE_ROWS = "ACCEL_REFERENCE_ROWS";
	public static final String S_SCRIPTS = "ACCEL_SCRIPTS";
	public static final String S_FORMS = "ACCEL_FORMS";
	public static final String S_ROLES = "ACCEL_ROLES";
	public static final String S_WORKFLOW = "ACCEL_WORKFLOW";
	public static final String S_MATCHING = "ACCEL_MATCHING";
	public static final String S_PERMISSIONS = "ACCEL_PERMISSIONS";
	public static final String S_SUMMARY = "ACCEL_SUMMARY";
	public static final List<String> STEPS = Collections.unmodifiableList(Arrays.asList(DomainGenerator.S_DATASPACE, DomainGenerator.S_MODEL, DomainGenerator.S_REFERENCE_ROWS,
		DomainGenerator.S_SCRIPTS, DomainGenerator.S_FORMS, DomainGenerator.S_ROLES, DomainGenerator.S_WORKFLOW, DomainGenerator.S_MATCHING, DomainGenerator.S_PERMISSIONS, DomainGenerator.S_SUMMARY));

	/** The {@code DomainObject.kind} values (docs/LOT-C1.md section 5). */
	public static final String K_DATA_MODEL = "DATA_MODEL";
	public static final String K_PUBLICATION = "PUBLICATION";
	public static final String K_DATASPACE = "DATASPACE";
	public static final String K_DATASET = "DATASET";
	public static final String K_SCRIPT = "SCRIPT";
	public static final String K_ROLE = "ROLE";
	public static final String K_WORKFLOW_MODEL = "WORKFLOW_MODEL";
	public static final String K_WORKFLOW_PUBLICATION = "WORKFLOW_PUBLICATION";
	public static final String K_LAYOUT = "LAYOUT";
	public static final String K_MATCHING_POLICY = "MATCHING_POLICY";
	public static final String K_PERMISSIONS = "PERMISSIONS";
	public static final String K_REFERENCE_ROWS = "REFERENCE_ROWS";

	private DomainGenerator()
	{
	}

	/**
	 * The entry point the hook of sub-lot B ({@code AccelHooks.generate}) calls: queues the generation of the project's domain and returns at once
	 * ({@code started}, {@code jobId}, {@code merged}, {@code position}).
	 *
	 * @param pProjectId the primary key of the {@code Project} row (not its code)
	 */
	public static ObjectNode queue(final Session pSession, final Adaptation pFactory, final String pProjectId) throws OperationException
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectId);
		}
		final ObjectNode params = DomainGenerator.MAPPER.createObjectNode();
		params.put("projectId", pProjectId);
		final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, pProjectId, DomainGenerator.STEP, params);
		final ObjectNode out = DomainGenerator.MAPPER.createObjectNode();
		out.put("started", true);
		out.put("jobId", queued.jobId);
		out.put("merged", queued.merged);
		out.put("position", queued.position);
		return out;
	}

	/** The job's work, registered at module start ({@code FactoryJobKinds}); resource MASTER: the domain is the project's golden zone. */
	static final FactoryJobQueue.Handler HANDLER = new FactoryJobQueue.Handler()
	{
		@Override
		public String run(final Session pJobSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId, final JsonNode pParams) throws Exception
		{
			return DomainGenerator.run(pJobSession, pHandle, pFactory, pProjectId);
		}
	};

	/** What a step body gets. */
	private static final class Ctx
	{
		final Session session;
		final Adaptation factory;
		final String projectId;
		final String projectCode;
		final DomainPlan plan;
		String stepId;

		Ctx(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final DomainPlan pPlan)
		{
			this.session = pSession;
			this.factory = pFactory;
			this.projectId = pProjectId;
			this.projectCode = pProjectCode;
			this.plan = pPlan;
		}

		McpClient client(final String pServer) throws OperationException
		{
			return McpServers.client(this.session, this.factory, pServer);
		}
	}

	private interface Body
	{
		/** Returns the step's message; sets {@code skipped} in the detail to end the step SKIPPED. */
		String run(Ctx pContext, ObjectNode pDetail) throws Exception;
	}

	static String run(final Session pSession, final FactoryJobs.Handle pHandle, final Adaptation pFactory, final String pProjectId) throws Exception
	{
		final Adaptation project = pFactory.getTable(FactoryPaths.PROJECT).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectId));
		if (project == null)
		{
			throw EngineRefusal.projectNotFound(pProjectId);
		}
		final String code = project.getString(FactoryPaths.Project.CODE);
		final DomainPlan plan = DomainPlan.build(PackCatalog.load(), code, DomainObjectWrites.projectLabelOrCode(project), DomainInputs.packs(pFactory, pProjectId),
			DomainInputs.accepted(pFactory, pProjectId));
		if (plan.packKeys.isEmpty() && plan.modelOps.isEmpty())
		{
			// a blank start is generated from its accepted ADD lines alone; with neither a pack nor one of those there is no model to write
			throw EngineRefusal.coded("missing.noPack", "Project " + code + " has no pack chosen and no accepted model line: nothing to generate.", code);
		}
		final String parentStep = PipelineSteps.begin(pSession, pFactory, pProjectId, DomainGenerator.STEP, "");
		FactoryJobs.attach(pHandle, parentStep);
		final ObjectNode progress = DomainGenerator.MAPPER.createObjectNode();
		final ArrayNode stepsNode = progress.putArray("steps");
		progress.put("total", 10); // the wizard's bar reads done/total (AccelPages.renderJob)
		progress.put("done", 0);
		final Ctx ctx = new Ctx(pSession, pFactory, pProjectId, code, plan);
		try
		{
			final Map<String, Body> bodies = new LinkedHashMap<String, Body>();
			bodies.put(DomainGenerator.S_DATASPACE, DomainGenerator::dataspace);
			bodies.put(DomainGenerator.S_MODEL, DomainGenerator::model);
			bodies.put(DomainGenerator.S_REFERENCE_ROWS, DomainGenerator::referenceRows);
			bodies.put(DomainGenerator.S_SCRIPTS, DomainGenerator::scripts);
			bodies.put(DomainGenerator.S_FORMS, DomainGenerator::forms);
			bodies.put(DomainGenerator.S_ROLES, DomainGenerator::roles);
			bodies.put(DomainGenerator.S_WORKFLOW, DomainGenerator::workflow);
			bodies.put(DomainGenerator.S_MATCHING, DomainGenerator::matching);
			bodies.put(DomainGenerator.S_PERMISSIONS, DomainGenerator::permissions);
			bodies.put(DomainGenerator.S_SUMMARY, DomainGenerator::summary);
			for (final Map.Entry<String, Body> e : bodies.entrySet())
			{
				if (pHandle.cancelled())
				{
					final String message = "Domain generation stopped (" + pHandle.stopReason() + ").";
					PipelineSteps.endStopped(pSession, pFactory, parentStep, message, progress.toString());
					return message;
				}
				final ObjectNode line = stepsNode.addObject();
				line.put("step", e.getKey());
				line.put("status", "RUNNING");
				PipelineSteps.updateDetail(pSession, pFactory, parentStep, progress.toString());
				DomainGenerator.runStep(ctx, e.getKey(), e.getValue(), line);
				progress.put("done", stepsNode.size());
				PipelineSteps.updateDetail(pSession, pFactory, parentStep, progress.toString());
			}
			final String message = "Domain " + plan.names.dataspace + " generated" + (plan.drafts().isEmpty() ? "." : "; " + plan.drafts().size() + " draft rule(s) held back.");
			PipelineSteps.end(pSession, pFactory, parentStep, true, message, progress.toString());
			return message;
		}
		catch (final OperationException | RuntimeException | Error ex)
		{
			PipelineSteps.end(pSession, pFactory, parentStep, false, ErrorText.of(ex), progress.toString());
			throw ex;
		}
	}

	private static void runStep(final Ctx pCtx, final String pName, final Body pBody, final ObjectNode pLine) throws Exception
	{
		if (PipelineSteps.isDone(pCtx.factory, pCtx.projectId, pName, ""))
		{
			pLine.put("status", "ALREADY_DONE");
			return;
		}
		final String stepId = PipelineSteps.begin(pCtx.session, pCtx.factory, pCtx.projectId, pName, "");
		pCtx.stepId = stepId;
		final ObjectNode detail = DomainGenerator.MAPPER.createObjectNode();
		try
		{
			final String message = pBody.run(pCtx, detail);
			if (detail.path("skipped").asBoolean(false))
			{
				PipelineSteps.endSkipped(pCtx.session, pCtx.factory, stepId, message, detail.toString());
				pLine.put("status", "SKIPPED");
			}
			else
			{
				PipelineSteps.end(pCtx.session, pCtx.factory, stepId, true, message, detail.toString());
				pLine.put("status", "DONE");
			}
			pLine.put("message", message);
		}
		catch (final McpException ex)
		{
			detail.put("transportError", ex.getMessage());
			PipelineSteps.end(pCtx.session, pCtx.factory, stepId, false, ErrorText.of(ex), detail.toString());
			pLine.put("status", "FAILED");
			pLine.put("message", ErrorText.of(ex));
			throw EngineRefusal.codedWithCause("external.mcpGenerateDomain", "MCP call failed in " + pName + " of project " + pCtx.projectCode + ": " + ex.getMessage(), ex, pName);
		}
		catch (final OperationException ex)
		{
			PipelineSteps.end(pCtx.session, pCtx.factory, stepId, false, ErrorText.of(ex), detail.toString());
			pLine.put("status", "FAILED");
			pLine.put("message", ErrorText.of(ex));
			throw ex;
		}
		catch (final RuntimeException | Error ex)
		{
			StepGuard.endUnexpected(pCtx.session, pCtx.factory, stepId, ex, detail.toString());
			pLine.put("status", "FAILED");
			throw ex;
		}
	}

	// ---------------------------------------------------------------------------------------------------------------- 1

	private static String dataspace(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		final String key = c.plan.names.dataspace;
		final Repository repository = c.factory.getHome().getRepository();
		AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(key));
		if (home == null)
		{
			final HomeCreationSpec spec = new HomeCreationSpec();
			spec.setKey(HomeKey.forBranchName(key));
			final AdaptationHome parentHome = repository.getReferenceBranch();
			spec.setParent(parentHome);
			spec.setOwner(Profile.ADMINISTRATOR);
			final String permissionsFrom = FactoryAccess.copyZonePermissions(spec, repository, parentHome);
			pDetail.put("permissionsCopiedFrom", permissionsFrom == null ? "none" : permissionsFrom);
			spec.setLabel(UserMessage.createInfo(key));
			spec.setDescription(UserMessage.createInfo("Domain of project " + c.projectCode + (c.plan.packKeys.isEmpty() ? " (started without a pack)." : " (accelerator packs " + c.plan.packKeys + ").")));
			home = repository.createHome(spec, c.session);
			pDetail.put("created", true);
		}
		else if (DomainGenerator.row(c, K_DATASPACE, key) == null)
		{
			throw EngineRefusal.coded("conflict.domainDataspaceExists", "The dataspace " + key + " already exists and was not created by this project: refusing to adopt it.", key);
		}
		else
		{
			pDetail.put("created", false);
		}
		DomainObjectWrites.record(c.session, c.factory, c.projectId, K_DATASPACE, key, key, pDetail.toString(), c.stepId);
		return "Dataspace " + key + " ready (child of the reference dataspace).";
	}

	// ---------------------------------------------------------------------------------------------------------------- 2

	private static String model(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		final String model = c.plan.names.model;
		final McpClient client = c.client(McpServers.DATA_MODEL);
		final ObjectNode getArgs = DomainGenerator.MAPPER.createObjectNode();
		getArgs.put("data_model_name", model);
		final McpClient.ToolResult lookup = client.callTool("get_data_model", getArgs);
		final boolean exists = lookup.json != null && lookup.json.path("found").asBoolean(false);
		if (exists && DomainGenerator.row(c, K_DATA_MODEL, model) == null)
		{
			throw EngineRefusal.coded("conflict.domainModelExists", "The data model " + model + " already exists and was not created by this project: refusing to adopt it.", model);
		}
		if (!exists)
		{
			final ObjectNode createArgs = DomainGenerator.MAPPER.createObjectNode();
			createArgs.put("data_model_name", model);
			final McpClient.ToolResult created = client.callTool("create_data_model", createArgs);
			DomainGenerator.require(created, "create_data_model " + model);
			pDetail.put("modelCreated", true);
		}
		DomainObjectWrites.record(c.session, c.factory, c.projectId, K_DATA_MODEL, model, model, "{}", c.stepId);

		// ONE patch: the cores, the blocks, the decided differences, then the labels (all flat set_element_documentation)
		final ArrayNode ops = DomainGenerator.MAPPER.createArrayNode();
		for (final ObjectNode op : c.plan.modelOps)
		{
			ops.add(op);
		}
		for (final ObjectNode op : c.plan.documentationOps)
		{
			ops.add(op);
		}
		final ObjectNode patchArgs = DomainGenerator.MAPPER.createObjectNode();
		patchArgs.put("data_model_name", model);
		patchArgs.put("operations_json", ops.toString());
		final McpClient.ToolResult patch = client.callTool("apply_data_model_patch", patchArgs);
		pDetail.put("operations", ops.size());
		DomainGenerator.require(patch, "apply_data_model_patch " + model);
		final JsonNode pj = patch.json;
		if (pj != null && (pj.path("rolledBack").asBoolean(false) || pj.path("failed").asInt(0) > 0 || "false".equals(pj.path("valid").asText("true"))
			|| "false".equals(pj.path("compiled").asText("true"))))
		{
			// name the op that failed first: the answer's results[] carries index, op and error (first real run, 2026-10-12)
			final StringBuilder failing = new StringBuilder();
			for (final JsonNode r : pj.path("results"))
			{
				if ("error".equals(r.path("status").asText()))
				{
					final int idx = r.path("index").asInt(-1);
					failing.append(" op#").append(idx).append(' ').append(r.path("op").asText()).append(' ');
					if (idx >= 0 && idx < ops.size())
						failing.append(ops.get(idx).path("arguments").toString()).append(' ');
					failing.append("-> ").append(r.path("error").asText());
					break;
				}
			}
			throw EngineRefusal.coded("external.patchIncomplete", "apply_data_model_patch left " + model + " unchanged or invalid (rolledBack=" + pj.path("rolledBack").asText("?") + ", failed="
				+ pj.path("failed").asText("?") + ", valid=" + pj.path("valid").asText("?") + "):" + failing + (failing.length() == 0 ? " " + WorkflowModelOps.shortText(patch.text) : ""), model);
		}

		// record labels of the tables (cosmetic: a failure is a note)
		final ArrayNode labelNotes = pDetail.putArray("defaultLabelNotes");
		for (final Map.Entry<String, String> e : c.plan.defaultLabels.entrySet())
		{
			final ObjectNode args = DomainGenerator.MAPPER.createObjectNode();
			args.put("data_model_name", model);
			args.put("operation", "set_default_label");
			args.put("arguments_json", DomainGenerator.MAPPER.createObjectNode().put("element_path", e.getKey()).put("label", e.getValue()).toString());
			final McpClient.ToolResult r = client.callTool("execute_operation", args);
			if (WorkflowModelOps.failed(r))
			{
				labelNotes.add("Default label of " + e.getKey() + " not set: " + WorkflowModelOps.shortText(r.text));
			}
		}

		MasterGenerator.ensureEmbedded(client, model, pDetail);

		boolean mame = false;
		if (c.plan.optionalModules.contains("ebx-addon-mame"))
		{
			final MasterGenerator.MameAddonCheck check = MasterGenerator.checkMameAddonDeclared(client, model, pDetail);
			switch (check.decision.action)
			{
				case ALREADY_DECLARED:
					mame = true;
					break;
				case DECLARE:
				{
					final ObjectNode addArgs = DomainGenerator.MAPPER.createObjectNode();
					addArgs.put("data_model_name", model);
					addArgs.put("operation", "add_addon");
					addArgs.put("arguments_json", DomainGenerator.MAPPER.createObjectNode().put("name", check.decision.name).toString());
					final McpClient.ToolResult added = client.callTool("execute_operation", addArgs);
					if (WorkflowModelOps.failed(added) || "ADDON_NOT_AVAILABLE".equals(added.json == null ? null : added.json.path("code").asText(null)))
					{
						pDetail.put("mameNote", "add_addon refused: " + WorkflowModelOps.shortText(added.text));
					}
					else
					{
						mame = true;
					}
					break;
				}
				default:
					pDetail.put("mameNote", "The Match and Merge add-on is not available on this instance: no matching will be configured.");
					break;
			}
		}
		pDetail.put("mameDeclared", mame);

		DomainGenerator.publish(client, model, "the domain model");
		DomainObjectWrites.record(c.session, c.factory, c.projectId, K_PUBLICATION, model, model, "{\"mame\":" + mame + "}", c.stepId);

		// the dataset, in the domain dataspace - never in the reference dataspace
		final Repository repository = c.factory.getHome().getRepository();
		final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(c.plan.names.dataspace));
		if (home == null)
		{
			throw EngineRefusal.coded("missing.domainDataspace", "The domain dataspace " + c.plan.names.dataspace + " does not exist (step " + S_DATASPACE + " did not run).", c.plan.names.dataspace);
		}
		if (home.findAdaptationOrNull(AdaptationName.forName(c.plan.names.dataset)) == null)
		{
			final ObjectNode createArgs = DomainGenerator.MAPPER.createObjectNode();
			createArgs.put("data_model_name", model);
			createArgs.put("operation", "create_dataset");
			createArgs.put("arguments_json", DomainGenerator.MAPPER.createObjectNode().put("publication_name", model).put("dataset_name", c.plan.names.dataset)
				.put("dataspace", c.plan.names.dataspace).toString());
			DomainGenerator.require(client.callTool("execute_operation", createArgs), "create_dataset " + c.plan.names.dataset);
			pDetail.put("datasetCreated", true);
		}
		DomainObjectWrites.record(c.session, c.factory, c.projectId, K_DATASET, c.plan.names.dataspace + "/" + c.plan.names.dataset, c.plan.names.dataset, "{}", c.stepId);
		return "Model " + model + " built (" + ops.size() + " operations), published; dataset " + c.plan.names.dataset + " created in " + c.plan.names.dataspace + ".";
	}

	// ---------------------------------------------------------------------------------------------------------------- 3

	private static String referenceRows(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		if (c.plan.referenceTables.isEmpty())
		{
			return "No reference table to load.";
		}
		final Adaptation dataset = DomainGenerator.dataset(c);
		int created = 0;
		int existing = 0;
		final ArrayNode tables = pDetail.putArray("tables");
		for (final DomainPlan.RefTable rt : c.plan.referenceTables)
		{
			final ReferenceDataLoader.Result r = ReferenceDataLoader.load(c.session, dataset, rt.table);
			created += r.created;
			existing += r.existing;
			final ObjectNode line = tables.addObject();
			line.put("table", rt.tablePath);
			line.put("created", r.created);
			line.put("existing", r.existing);
			line.put("duplicatesInFile", r.duplicatesInFile);
			line.put("withoutKey", r.withoutKey);
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_REFERENCE_ROWS, rt.tablePath, rt.tablePath, line.toString(), c.stepId);
		}
		return created + " reference row(s) written in " + c.plan.referenceTables.size() + " table(s), " + existing + " already there.";
	}

	// ---------------------------------------------------------------------------------------------------------------- 4

	private static String scripts(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		if (c.plan.scripts.isEmpty())
		{
			return "No script to declare.";
		}
		final ObjectNode pre = McpServers.preflight(c.session, c.factory, McpServers.SCRIPTING);
		if (!pre.path("ready").asBoolean(false))
		{
			pDetail.put("skipped", true);
			return "The scripting server is not reachable (" + pre.path("summary").asText("no detail") + "): " + c.plan.scripts.size() + " script(s) were not declared.";
		}
		final McpClient dataModel = c.client(McpServers.DATA_MODEL);
		final McpClient scripting = c.client(McpServers.SCRIPTING);
		final ArrayNode lines = pDetail.putArray("scripts");
		int drafts = 0;
		for (final DomainPlan.ScriptItem s : c.plan.scripts)
		{
			final ObjectNode line = lines.addObject();
			line.put("name", s.name);
			line.put("element", s.elementPath);
			line.put("draft", s.draft);
			line.put("source", s.source);
			final boolean authored = DomainScripts.declareAndAuthor(dataModel, scripting, c.plan.names.model, s, line);
			line.put("authoredOk", authored);
			drafts += s.draft ? 1 : 0;
			final ObjectNode rowDetail = DomainGenerator.MAPPER.createObjectNode();
			rowDetail.put("draft", s.draft);
			rowDetail.put("source", s.source);
			rowDetail.put("authored", authored);
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_SCRIPT, s.idePath, s.elementPath, rowDetail.toString(), c.stepId);
		}
		DomainGenerator.publish(dataModel, c.plan.names.model, "the scripts");
		pDetail.put("drafts", drafts);
		return c.plan.scripts.size() + " script(s) declared, authored and published once" + (drafts == 0 ? "." : "; " + drafts + " draft rule(s) authored but held back (flagged draft).");
	}

	// ---------------------------------------------------------------------------------------------------------------- 5

	private static String forms(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		if (c.plan.layouts.isEmpty())
		{
			return "No form layout in the plan.";
		}
		if (!LayoutOps.designerPresent())
		{
			pDetail.put("skipped", true);
			return "The Layout Designer runtime (" + LayoutOps.DESIGNER_CLASS + ") is not on this instance: the standard record forms stay.";
		}
		final Adaptation designer = LayoutOps.designerDataset(c.factory.getHome().getRepository());
		if (designer == null)
		{
			pDetail.put("skipped", true);
			return "The Layout Designer dataset (" + LayoutOps.DATASPACE + "/" + LayoutOps.DATASET + ") is not there: the standard record forms stay.";
		}
		final McpClient client = c.client(McpServers.DATA_MODEL);
		final ArrayNode lines = pDetail.putArray("layouts");
		for (final DomainPlan.LayoutItem l : c.plan.layouts)
		{
			final int version = LayoutOps.write(c.session, designer, l.layout, l.tablePath, c.plan.names.publication);
			final ObjectNode declare = DomainGenerator.MAPPER.createObjectNode();
			declare.put("data_model_name", c.plan.names.model);
			declare.put("operation", "set_form_layout");
			declare.put("arguments_json", DomainGenerator.MAPPER.createObjectNode().put("element_path", l.tablePath).put("class_name", LayoutOps.DESIGNER_CLASS).toString());
			DomainGenerator.require(client.callTool("execute_operation", declare), "set_form_layout " + l.tablePath);
			lines.addObject().put("table", l.tablePath).put("version", version);
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_LAYOUT, l.tablePath, l.tablePath, "{\"version\":" + version + "}", c.stepId);
		}
		DomainGenerator.publish(client, c.plan.names.model, "the form layouts");
		return c.plan.layouts.size() + " form layout(s) written and declared; model republished.";
	}

	// ---------------------------------------------------------------------------------------------------------------- 5b

	/** The built-in directory: dataspace and dataset {@code ebx-directory}, table {@code /directory/roles}, key {@code name}. */
	static final String DIRECTORY = "ebx-directory";
	static final String DIRECTORY_ROLES = "/directory/roles";

	/**
	 * Makes sure every role of the plan exists in the directory, BEFORE the workflow step: publication of a workflow model is refused
	 * ("The role X does not exist") for a role the directory does not know — met on the first real run (fourn1, 2026-10-12, roles
	 * Purchasing and Finance proposed by pass 4 and accepted). A role that already exists is left untouched; a created one carries only
	 * its name (EBX shows it as its label). Roles are never deleted by a reset or a deletion of the project: they are shared, and users
	 * may have been given them meanwhile; the DomainObject row says whether the project created it.
	 */
	private static String roles(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		if (c.plan.roles.isEmpty())
		{
			return "No role in the plan.";
		}
		final Repository repository = c.factory.getHome().getRepository();
		final AdaptationHome home = repository.lookupHome(HomeKey.forBranchName(DomainGenerator.DIRECTORY));
		final Adaptation directory = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(DomainGenerator.DIRECTORY));
		if (directory == null)
		{
			throw EngineRefusal.coded("missing.directory", "The built-in directory dataset " + DomainGenerator.DIRECTORY + " is not reachable: roles cannot be created (an external directory?).",
				DomainGenerator.DIRECTORY);
		}
		final AdaptationTable table = directory.getTable(Path.parse(DomainGenerator.DIRECTORY_ROLES));
		final ArrayNode lines = pDetail.putArray("roles");
		int created = 0;
		for (final DomainPlan.Role r : c.plan.roles)
		{
			final boolean exists = table.lookupAdaptationByPrimaryKey(table.computePrimaryKey(new Object[] { r.name })) != null;
			if (!exists)
			{
				final ProcedureResult done = ProgrammaticService.createForSession(c.session, home).execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setAllPrivileges(true);
						final ValueContextForUpdate value = pContext.getContextForNewOccurrence(table);
						value.setValue(r.name, Path.parse("./name"));
						pContext.doCreateOccurrence(value, table);
					}
				});
				if (done.hasFailed())
				{
					throw EngineRefusal.coded("external.roleCreate", "Creating the role " + r.name + " in the directory failed: " + done.getExceptionFullMessage(Locale.ENGLISH), r.name);
				}
				created++;
			}
			lines.addObject().put("role", r.name).put("profile", r.profile).put("created", !exists);
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_ROLE, r.name, DomainGenerator.DIRECTORY_ROLES, "{\"created\":" + !exists + "}", c.stepId);
		}
		// a role with no member sends the first work item offered to it to the ERROR state (CLAUDE.md section 7): say it here
		return c.plan.roles.size() + " role(s) present in the directory (" + created + " created, " + (c.plan.roles.size() - created)
			+ " already there); roles are never removed by a reset. A created role has NO member yet: assign users to the roles before launching the workflow, or its review tasks go to the error state.";
	}

	// ---------------------------------------------------------------------------------------------------------------- 6

	private static String workflow(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		if (c.plan.workflows.isEmpty())
		{
			return "No workflow in the plan.";
		}
		final ArrayNode lines = pDetail.putArray("workflows");
		for (final DomainPlan.WorkflowItem w : c.plan.workflows)
		{
			final ObjectNode model = WorkflowAssembler.assemble(w.params);
			final boolean republish = DomainGenerator.row(c, K_WORKFLOW_PUBLICATION, w.modelName) != null;
			final WorkflowModelOps.Outcome outcome = WorkflowModelOps.importAndPublish(c.session, c.factory, model, w.modelName, republish);
			if (outcome.skipped)
			{
				pDetail.put("skipped", true);
				return outcome.message;
			}
			lines.addObject().put("model", w.modelName).put("table", w.tablePath).put("updated", outcome.updated);
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_WORKFLOW_MODEL, w.modelName, w.tablePath, "{}", c.stepId);
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_WORKFLOW_PUBLICATION, w.modelName, w.tablePath, "{\"updated\":" + outcome.updated + "}", c.stepId);
		}
		return c.plan.workflows.size() + " workflow(s) imported and published.";
	}

	// ---------------------------------------------------------------------------------------------------------------- 7

	private static String matching(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		if (c.plan.matching.isEmpty())
		{
			return "No matching policy in the plan.";
		}
		final String model = c.plan.names.model;
		final McpClient dataModel = c.client(McpServers.DATA_MODEL);
		final MasterGenerator.MameAddonCheck check = MasterGenerator.checkMameAddonDeclared(dataModel, model, pDetail);
		if (check.decision.action != MameAddonDecision.Action.ALREADY_DECLARED)
		{
			pDetail.put("skipped", true);
			return "The Match and Merge add-on is not declared on " + model + " (not available on this instance): no matching policy was configured.";
		}
		final McpClient mame = c.client(McpServers.MATCH_MERGE);
		final ArrayNode lines = pDetail.putArray("policies");
		for (final DomainPlan.MatchingItem m : c.plan.matching)
		{
			final ObjectNode line = lines.addObject();
			line.put("table", m.tablePath);
			line.put("processCode", m.processCode);
			final ObjectNode getArgs = DomainGenerator.MAPPER.createObjectNode();
			getArgs.put("table", m.tablePath);
			final McpClient.ToolResult stored = MameCalls.call(mame, "get_matching_config", getArgs, model);
			boolean has = false;
			if (MameAnswer.success(stored.json, false))
			{
				for (final JsonNode p : MameAnswer.field(stored.json, "matchingPolicies"))
				{
					has |= m.processCode.equals(p.path("processCode").asText());
				}
			}
			if (!has)
			{
				final ObjectNode generate = DomainGenerator.MAPPER.createObjectNode();
				generate.put("table", m.tablePath);
				generate.set("matchingPolicy", m.matchingPolicy);
				MatchingConfigurator.requireSuccess(MameCalls.call(mame, "generate_matching_policy", generate, model), "generate_matching_policy");
			}
			line.put("generated", !has);
			// inline matching on creation AND on update (docs/ACCELERATORS.md section 10: mode 3; mode 2 intercepts nothing)
			final ArrayNode patchOps = DomainGenerator.MAPPER.createArrayNode();
			for (final String param : new String[] { "matchingOnCreation", "matchingOnUpdate" })
			{
				final ObjectNode op = patchOps.addObject();
				op.put("op", "set_execution_mode");
				op.putObject("target").put("policyCode", m.processCode).put("paramName", param);
				op.put("value", "3");
			}
			final ObjectNode patch = DomainGenerator.MAPPER.createObjectNode();
			patch.put("table", m.tablePath);
			patch.set("operations", patchOps);
			MatchingConfigurator.requireSuccess(MameCalls.call(mame, "patch_matching_config", patch, model), "patch_matching_config (set_execution_mode 3)");
			final ObjectNode activate = DomainGenerator.MAPPER.createObjectNode();
			activate.put("table", m.tablePath);
			final McpClient.ToolResult activated = MameCalls.call(mame, "activate_matching", activate, model);
			if (!MameAnswer.success(activated.json, false) || !MatchingActivationGuard.isReady(MatchingActivationGuard.parseStatus(activated.json)))
			{
				throw EngineRefusal.coded("external.activateMatching", "activate_matching did not report ENABLED with a metadataset for " + m.tablePath + ": " + WorkflowModelOps.shortText(activated.text),
					m.tablePath);
			}
			DomainObjectWrites.record(c.session, c.factory, c.projectId, K_MATCHING_POLICY, m.tablePath + "#" + m.processCode, m.tablePath, "{\"mode\":3}", c.stepId);
		}
		return c.plan.matching.size() + " matching policy(ies) configured (inline on creation and update) and activated.";
	}

	// ---------------------------------------------------------------------------------------------------------------- 8

	private static String permissions(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		final McpClient client = c.client(McpServers.DATA_MODEL);
		final String model = c.plan.names.model;
		final Map<String, String> expected = new LinkedHashMap<String, String>();
		// the value access default FIRST: a profile record created by a table-actions call starts hidden (docs/ACCELERATORS.md section 15)
		for (final DomainPlan.Role r : c.plan.roles)
		{
			DomainGenerator.operation(client, model, "set_permission_value_access_default", DomainGenerator.MAPPER.createObjectNode().put("profile", r.profile).put("access", "W"));
			expected.put(r.profile, "W");
		}
		for (final DomainPlan.Role r : c.plan.roles)
		{
			DomainGenerator.operation(client, model, "set_permission_table_default_actions", DomainGenerator.MAPPER.createObjectNode().put("profile", r.profile)
				.put("create_allowed", r.create).put("overwrite_allowed", r.overwrite).put("delete_allowed", r.delete).put("occult_allowed", r.occult));
		}
		DomainGenerator.operation(client, model, "set_permission_value_access_default", DomainGenerator.MAPPER.createObjectNode().put("profile", "Beveryone").put("access", "R"));
		expected.put("Beveryone", "R");
		// administrators are NOT above a model's permissions extension: unlisted, they fall to the everyone row (read only) and the
		// creation task answered "Access denied" to admin on the first real run (2026-10-12) — give them a full row explicitly
		DomainGenerator.operation(client, model, "set_permission_value_access_default", DomainGenerator.MAPPER.createObjectNode().put("profile", "Badministrator").put("access", "W"));
		DomainGenerator.operation(client, model, "set_permission_table_default_actions", DomainGenerator.MAPPER.createObjectNode().put("profile", "Badministrator")
			.put("create_allowed", true).put("overwrite_allowed", true).put("delete_allowed", true).put("occult_allowed", true));
		expected.put("Badministrator", "W");

		final ObjectNode readArgs = DomainGenerator.MAPPER.createObjectNode();
		readArgs.put("data_model_name", model);
		readArgs.put("operation", "get_data_model_permissions");
		final McpClient.ToolResult read = client.callTool("execute_query", readArgs);
		DomainGenerator.require(read, "get_data_model_permissions");
		final List<String> wrong = new ArrayList<String>();
		for (final Map.Entry<String, String> e : expected.entrySet())
		{
			final String found = DomainGenerator.valueAccessOf(read.json, e.getKey());
			if (!e.getValue().equals(found))
			{
				wrong.add(e.getKey() + " reads " + found + " (expected " + e.getValue() + ")");
			}
		}
		if (!wrong.isEmpty())
		{
			throw EngineRefusal.coded("external.permissionsReadBack", "The permissions read back from " + model + " are not what was written: " + wrong, model);
		}
		DomainGenerator.publish(client, model, "the permissions");
		final ObjectNode rowDetail = DomainGenerator.MAPPER.createObjectNode();
		for (final Map.Entry<String, String> e : expected.entrySet())
		{
			rowDetail.put(e.getKey(), e.getValue());
		}
		DomainObjectWrites.record(c.session, c.factory, c.projectId, K_PERMISSIONS, model, model, rowDetail.toString(), c.stepId);
		pDetail.set("valueAccess", rowDetail);
		return "Permissions written, read back and published for " + c.plan.roles.size() + " role(s) (everyone read-only, administrators full). Dataspace and dataset permissions stay a manual step.";
	}

	/** The {@code valueAccessDefault} of the profile in a {@code get_data_model_permissions} answer, searched wherever the profile record sits; null when absent. */
	static String valueAccessOf(final JsonNode pNode, final String pProfile)
	{
		if (pNode == null)
		{
			return null;
		}
		if (pNode.isObject())
		{
			if (pProfile.equals(pNode.path("profile").asText(null)) && pNode.has("valueAccessDefault"))
			{
				return pNode.get("valueAccessDefault").asText();
			}
			for (final JsonNode child : pNode)
			{
				final String r = DomainGenerator.valueAccessOf(child, pProfile);
				if (r != null)
				{
					return r;
				}
			}
		}
		else if (pNode.isArray())
		{
			for (final JsonNode child : pNode)
			{
				final String r = DomainGenerator.valueAccessOf(child, pProfile);
				if (r != null)
				{
					return r;
				}
			}
		}
		return null;
	}

	// ---------------------------------------------------------------------------------------------------------------- 9

	private static String summary(final Ctx c, final ObjectNode pDetail) throws Exception
	{
		final AdaptationTable projects = c.factory.getTable(FactoryPaths.PROJECT);
		final AdaptationTable packs = c.factory.getTable(FactoryPaths.PROJECT_PACK);
		final ProcedureResult result = ProgrammaticService.createForSession(c.session, c.factory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation project = projects.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(c.projectId));
				final ValueContextForUpdate value = pContext.getContext(project.getAdaptationName());
				value.setValue(c.plan.names.dataspace, FactoryPaths.Project.DOMAIN_DATASPACE);
				value.setValue(c.plan.names.dataset, FactoryPaths.Project.DOMAIN_DATASET);
				value.setValue(c.plan.names.model, FactoryPaths.Project.DOMAIN_MODEL);
				pContext.doModifyContent(project, value);
				final RequestResult rows = packs.createRequestResult("./project=" + CatalogReader.quote(c.projectId));
				final List<Adaptation> list = new ArrayList<Adaptation>();
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
					final ValueContextForUpdate v = pContext.getContext(r.getAdaptationName());
					v.setValue(FactoryPaths.ProjectPack.STATUS_GENERATED, FactoryPaths.ProjectPack.STATUS);
					pContext.doModifyContent(r, v);
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.writeBackDomain", "The domain was generated, but writing back the Project domain fields failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		final Map<String, Integer> byKind = new LinkedHashMap<String, Integer>();
		for (final DomainObjectWrites.Row row : DomainObjectWrites.list(c.factory, c.projectId))
		{
			final Integer n = byKind.get(row.kind);
			byKind.put(row.kind, Integer.valueOf(n == null ? 1 : n.intValue() + 1));
		}
		final ObjectNode kinds = pDetail.putObject("objects");
		for (final Map.Entry<String, Integer> e : byKind.entrySet())
		{
			kinds.put(e.getKey(), e.getValue().intValue());
		}
		final ArrayNode notes = pDetail.putArray("notes");
		for (final String n : c.plan.notes)
		{
			notes.add(n);
		}
		final ArrayNode drafts = pDetail.putArray("draftRules");
		for (final DomainPlan.ScriptItem d : c.plan.drafts())
		{
			drafts.add(d.name + " on " + d.elementPath);
		}
		pDetail.put("manualStep", "Dataspace and dataset permissions (the other two locks of a non-administrator) are not written by the generation.");
		return "Domain " + c.plan.names.dataspace + "/" + c.plan.names.dataset + " recorded on the project; packs " + c.plan.packKeys + " marked GENERATED.";
	}

	// ------------------------------------------------------------------------------------------------------------ helpers

	private static DomainObjectWrites.Row row(final Ctx c, final String pKind, final String pName)
	{
		for (final DomainObjectWrites.Row r : DomainObjectWrites.list(c.factory, c.projectId))
		{
			if (pKind.equals(r.kind) && pName.equals(r.name))
			{
				return r;
			}
		}
		return null;
	}

	private static Adaptation dataset(final Ctx c) throws OperationException
	{
		final AdaptationHome home = c.factory.getHome().getRepository().lookupHome(HomeKey.forBranchName(c.plan.names.dataspace));
		final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(c.plan.names.dataset));
		if (dataset == null)
		{
			throw EngineRefusal.coded("missing.domainDataset", "The domain dataset " + c.plan.names.dataspace + "/" + c.plan.names.dataset + " does not exist (step " + S_MODEL + " did not finish).",
				c.plan.names.dataset);
		}
		return dataset;
	}

	/** An error result, or a soft miss, ends the step. */
	private static void require(final McpClient.ToolResult pResult, final String pWhat) throws OperationException
	{
		if (WorkflowModelOps.failed(pResult))
		{
			throw EngineRefusal.coded("external.toolFailed", pWhat + " failed: " + WorkflowModelOps.shortText(pResult.text), pWhat);
		}
	}

	private static void operation(final McpClient pClient, final String pModel, final String pOperation, final ObjectNode pArguments) throws McpException, OperationException
	{
		final ObjectNode args = DomainGenerator.MAPPER.createObjectNode();
		args.put("data_model_name", pModel);
		args.put("operation", pOperation);
		args.put("arguments_json", pArguments.toString());
		DomainGenerator.require(pClient.callTool("execute_operation", args), pOperation + " " + pArguments);
	}

	private static void publish(final McpClient pClient, final String pModel, final String pWhy) throws McpException, OperationException
	{
		final ObjectNode args = DomainGenerator.MAPPER.createObjectNode();
		args.put("data_model_name", pModel);
		final McpClient.ToolResult publish = pClient.callTool("publish_data_model", args);
		DomainGenerator.require(publish, "publish_data_model (" + pWhy + ")");
		if (!PublishDecision.published(publish.json))
		{
			throw EngineRefusal.coded("invalid.modelNotValid", "publish_data_model refused to publish " + pModel + " (" + pWhy + "): " + WorkflowModelOps.shortText(publish.text), pModel);
		}
	}
}
