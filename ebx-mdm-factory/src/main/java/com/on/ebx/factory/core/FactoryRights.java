package com.on.ebx.factory.core;

import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * The decision of audit F-02, in one place: the {@link FactoryRight} each entry point of the module asks. Pure (no EBX
 * dependency), so that a test checks offline that every wizard action, proposal-review action, user service and REST
 * route has a decided right; the job kinds carry theirs at registration ({@code ebx.FactoryJobKinds}).
 * <p>
 * <b>Unknown means administrator.</b> An action, a route or a service this class does not list asks
 * {@link FactoryRight#ADMINISTRATOR}: something added later without a decision here is closed, never open.
 * <p>
 * <b>The pipeline launcher role.</b> The factory setting {@code pipelineLauncherRole} (instance-wide, kept from before
 * F-02) names a role whose members may launch the factory's workflows and operate the automatic intake without the
 * DESIGN right. The few entry points it opens are listed in {@link #PIPELINE_OPERATOR}: they ask DESIGN, OR that role.
 */
public final class FactoryRights
{
	private FactoryRights()
	{
	}

	private static final Map<String, FactoryRight> WIZARD = new LinkedHashMap<String, FactoryRight>();
	private static final Map<String, FactoryRight> REVIEW = new LinkedHashMap<String, FactoryRight>();
	private static final Map<String, FactoryRight> SERVICES = new LinkedHashMap<String, FactoryRight>();
	private static final Map<String, FactoryRight> ROUTES = new LinkedHashMap<String, FactoryRight>();
	private static final Set<String> PIPELINE_OPERATOR = new HashSet<String>();

	private static void wizard(final FactoryRight pRight, final String... pActions)
	{
		for (final String action : pActions)
		{
			if (FactoryRights.WIZARD.put(action, pRight) != null)
			{
				throw new IllegalStateException("wizard action decided twice: " + action);
			}
		}
	}

	private static void review(final FactoryRight pRight, final String... pActions)
	{
		for (final String action : pActions)
		{
			if (FactoryRights.REVIEW.put(action, pRight) != null)
			{
				throw new IllegalStateException("review action decided twice: " + action);
			}
		}
	}

	private static void route(final FactoryRight pRight, final String... pRoutes)
	{
		for (final String route : pRoutes)
		{
			if (FactoryRights.ROUTES.put(route, pRight) != null)
			{
				throw new IllegalStateException("route decided twice: " + route);
			}
		}
	}

	static
	{
		// ------------------------------------------------------------------ wizard (ProjectWizardUserService.handle)
		// navigation, polls and panels that call nothing: the read-only wizard ("" = a post with no action, a plain re-render)
		FactoryRights.wizard(FactoryRight.VIEW, "", "GOTO_STEP", "STEP_VIEW", "MATCH_PAGE", "SOURCES_RUN_POLL", "DEDUCED_SCAN_POLL", "LANDING_RUN_POLL", "STAGING_PROPOSAL_POLL",
			"JOBS_POLL", "STAGING_LOAD_RUN_POLL", "MASTER_BUILD_RUN_POLL", "SOURCE_ONBOARD_CLOSE", "DISTRIBUTION_TARGET_CLOSE", "INTAKE_OPEN", "INTAKE_CLOSE",
			"INTAKE_SETTINGS_OPEN", "INTAKE_SETTINGS_CLOSE",
			// landing-walkthrough lot (2026-10-16): moving between the pages of the Landing walkthrough writes nothing
			"LANDING_WALK_GOTO", "LANDING_WALK_REVIEW", "LANDING_WALK_CLOSE",
			// wave 11 (audit F-42): step 4's folded business objects and filter - what is shown, nothing written
			"STAGING_ENTITY_OPEN", "STAGING_ENTITY_CLOSE", "STAGING_ENTITY_OPEN_ALL", "STAGING_ENTITY_CLOSE_ALL", "STAGING_FILTER", "STAGING_STILL_MORE", "STAGING_STILL_LESS");
		// a data steward's decisions on the matching results of step 6, and the refresh of the queue they decide from
		FactoryRights.wizard(FactoryRight.STEWARD, "MATCH_PAIR_MERGE", "MATCH_PAIR_REJECT", "MATCH_PAIR_BULK_MERGE", "MATCH_GROUP_MERGE", "MATCH_GROUP_REJECT", "MATCH_RESULTS_REFRESH");
		// audit F-43: "Run again" of an interrupted task - the narrowest right that queues a job (like POST /jobs/{id}/cancel); the queue then
		// checks the right of the job's own kind (FactoryJobQueue.enqueue), and the list only offers it to whoever may queue that kind
		FactoryRights.wizard(FactoryRight.STEWARD, "JOB_RERUN");
		// design and run: every action that writes the project, a model, a mapping, a configuration, reads a source
		// through a connection, or sends project metadata to the language model (bounded by the administrator's settings)
		FactoryRights.wizard(FactoryRight.DESIGN, "SAVE_PROJECT", "SOURCE_PICK_CATALOG", "SOURCE_ADD", "SOURCE_REMOVE", "SOURCE_SET_TRUST", "SOURCE_SET_CODE",
			"SOURCE_TRUST_UP", "SOURCE_TRUST_DOWN", "SOURCES_RUN_REMOVE", "SOURCE_ONBOARD_OPEN", "SOURCE_ONBOARD_PICK", "SOURCE_ONBOARD_REFRESH",
			"SOURCE_ONBOARD_CATALOG", "SOURCE_ONBOARD_SCAN", "SOURCE_ONBOARD_USE", "SOURCE_ONBOARD_DEDUCED", "SOURCE_ONBOARD_ALL", "SOURCE_FILES_IMPORT",
			"DEDUCED_SCAN_START", "DEDUCED_SCAN_CONFIRM", "DEDUCED_SCAN_LEAVE_OUT", "TABLE_TOGGLE", "TABLES_KEEP", "SOURCE_FIND_TABLES", "SOURCE_RESCAN", "SOURCE_APP_OPEN", "SOURCE_ADD_APP", "TABLE_SET_KEY", "TABLE_SET_CHANGE_COLUMN", "TABLE_PREVIEW",
			"TABLE_KEY_STUDY", "TABLE_KEY_APPLY", "LANDING_RUN_START", "LANDING_RETRY_FAILED", "LANDING_RUN_STOP", "LANDING_KEY_CHOOSE", "LANDING_KEY_APPLY", "LANDING_KEY_CHANGE_PICK",
			"LANDING_KEY_CHANGE", "LANDING_KEY_COMPOSITE_DETAIL",
			// landing-walkthrough lot (2026-10-16): validate an application, study / choose / apply a key on its page, launch from the last page
			"LANDING_WALK_VALIDATE", "LANDING_WALK_STUDY", "LANDING_WALK_STUDY_APPLY", "LANDING_WALK_KEY_CHOOSE", "LANDING_WALK_KEY_APPLY", "LANDING_WALK_LAUNCH", "PLAN", "GENERATE", "DECIDE_ACCEPT", "DECIDE_REJECT", "SIMULATE", "APPLY", "REAPPLY", "REMOVE",
			"MEASURE", "REPROPOSE", "QUALITY_SET_SEVERITY", "CAPABILITY_SET_ASIDE", "CAPABILITY_SET_TRANSFER_POLICY", "DERIVATION_SET_PARAMS", "FEED_SET_PIPELINE",
			"FEED_SET_SOURCE_ID_PIPELINE", "STAGING_SUGGEST", "STAGING_SUGGEST_LLM", "STAGING_PROPOSAL_RUN", "STAGING_BULK_ACCEPT", "STAGING_BULK_REJECT",
			"STAGING_BULK_ACCEPT_ENTITY", "STAGING_BULK_REJECT_ENTITY", "STAGING_BULK_ACCEPT_QUALITY", "STAGING_BULK_REJECT_QUALITY", "STAGING_BULK_ACCEPT_STEPS",
			"STAGING_REOPEN_MAPPINGS", "QUALITY_SUGGEST", "QUALITY_SUGGEST_LLM", "CAPABILITY_SUGGEST_LLM", "STAGING_GENERATE", "STAGING_LOAD", "STAGING_LOAD_RUN",
			// staging preflight lot H1 (2026-10-17): the check before the build, its fix, and "Build anyway"
			"STAGING_LOAD_RUN_ANYWAY", "STAGING_PREFLIGHT", "STAGING_PREFLIGHT_FIX",
			"MASTER_GENERATE", "MASTER_LOAD", "MASTER_BUILD_RUN", "MATCHING_SUGGEST", "MATCHING_SUGGEST_LLM", "MATCHING_SET_TRIGGER", "MATCHING_CONFIGURE",
			"MATCHING_RUN", "PIPELINE_LAUNCH", "DISTRIBUTION_TARGET_OPEN", "DISTRIBUTION_TARGET_SAVE", "DISTRIBUTION_TARGET_CONFIGURE",
			"DISTRIBUTION_TARGET_INSTALL_TRIGGERS", "DISTRIBUTION_TARGET_FIRST_FULL_SEND", "DISTRIBUTION_TARGET_DISARM", "DISTRIBUTION_TARGET_TEST_CONNECTION", "DISTRIBUTION_CONNECTIONS_REFRESH", "DISTRIBUTION_WAREHOUSE_TEST",
			"DISTRIBUTION_TARGET_SEND_NOW", "DISTRIBUTION_PRODUCT_OPEN", "DISTRIBUTION_PRODUCT_ADD_ASSET", "DISTRIBUTION_PRODUCT_CREATE", "INTAKE_SAVE",
			"INTAKE_SWITCH_ON", "INTAKE_PAUSE", "INTAKE_RESUME", "INTAKE_SWITCH_OFF", "INTAKE_READ_NOW", "INTAKE_PROCESS_NOW",
			// lot C1-B: the CENTRALISATION path - writes the need, asks the model, runs the passes, generates the domain
			"ACCEL_SAVE_NEED", "ACCEL_SUGGEST_PACKS", "ACCEL_CHOOSE_PACKS", "ACCEL_RUN_PROPOSAL", "ACCEL_RUN_PASS", "ACCEL_GENERATE");
		// administrator: resets (they delete generated objects), the arming override (skips the readiness checklist of a
		// real-time target), the delivery check (Staging domain of the instance)
		FactoryRights.wizard(FactoryRight.ADMINISTRATOR, "STEP_RESET_PLAN", "STEP_RESET_CONFIRM", "STEP_RESET_CANCEL", "DISTRIBUTION_TARGET_ARM_OVERRIDE",
			"DELIVERY_CHECK");

		// --------------------------------------------------------- proposal review (ProposalReviewUserService.decide)
		FactoryRights.review(FactoryRight.VIEW, "", "SELECT_PROJECT", "CHANGE_PROJECT", "FILTER", "DISMISS");
		FactoryRights.review(FactoryRight.STEWARD, "ACCEPT", "REJECT", "BULK_ACCEPT_PREVIEW", "BULK_ACCEPT_CONFIRM", "CASCADE_CONFIRM", "SELECT_ACCEPT_PREVIEW",
			"SELECT_REJECT_PREVIEW", "SELECT_REJECT_CONFIRM");
		FactoryRights.review(FactoryRight.DESIGN, "REPROPOSE", "REMOVE", "MEASURE", "SET_AUTO_MERGE", "SIMULATE", "APPLY", "REAPPLY", "SELECT_APPLY_PREVIEW",
			"SELECT_APPLY_CONFIRM");

		// ------------------------------------------------------------------ user services (who sees and opens them)
		FactoryRights.SERVICES.put("ProjectExplorer", FactoryRight.VIEW);
		FactoryRights.SERVICES.put("FactoryCockpit", FactoryRight.VIEW);
		FactoryRights.SERVICES.put("ProjectWizard", FactoryRight.VIEW);
		FactoryRights.SERVICES.put("TransferRejectReview", FactoryRight.VIEW); // audit F-38: reading; acknowledging re-checks STEWARD in ebx.RejectReview
		FactoryRights.SERVICES.put("ProposalReview", FactoryRight.VIEW);
		FactoryRights.SERVICES.put("RecordLineage", FactoryRight.VIEW);
		FactoryRights.SERVICES.put("RecordLineageOnRecord", FactoryRight.VIEW);
		FactoryRights.SERVICES.put("MappingMatrix", FactoryRight.DESIGN);
		FactoryRights.SERVICES.put("StagingDesk", FactoryRight.DESIGN); // the desk decides what the matrix decides, through the matrix' own handler
		FactoryRights.SERVICES.put("FeedDesigner", FactoryRight.DESIGN);
		FactoryRights.SERVICES.put("FactoryConnection", FactoryRight.ADMINISTRATOR);
		FactoryRights.SERVICES.put("ProjectDelete", FactoryRight.ADMINISTRATOR);
		FactoryRights.SERVICES.put("About", FactoryRight.VIEW); // version and documentation links; the jar hashes only show to an administrator

		// ----------------------------------------------------------------------------- REST routes (FactoryResource)
		FactoryRights.route(FactoryRight.AUTHENTICATED, "GET /status", "POST /projects/{code}/intake/{source: [^/:]+}:complete");
		FactoryRights.route(FactoryRight.VIEW, "GET /projects/{code}/jobs", "GET /projects/{code}/staging/preflight", "GET /projects/{code}/matching/activity", "GET /projects/{code}/lineage/{entity}",
			"GET /projects/{code}/xrefs/{entity}", "GET /projects/{code}/intake");
		// a job is cancelled by whoever may queue it: the route asks the narrowest right that queues a job, the handler
		// then checks the right of the job's own kind
		FactoryRights.route(FactoryRight.STEWARD, "POST /jobs/{id}/cancel");
		// agentic framework lot (2026-10-30): run an agent (it then acts for the caller), decide one of its proposals (the caller must also hold its owner profile)
		FactoryRights.route(FactoryRight.STEWARD, "POST /agents/{code}/run", "POST /agents/proposals/{id}/decide");
		// production review 2026-11-12 (F3-02): who an agent acts for is whoever switched it on through these routes (signed by the server); switching ON is a design
		// act (the person answers for the agent's tools and instructions), switching OFF is a steward's emergency stop
		FactoryRights.route(FactoryRight.DESIGN, "POST /agents/{code}/enable");
		FactoryRights.route(FactoryRight.STEWARD, "POST /agents/{code}/disable");
		// source drift lot (2026-10-30, docs/DRIFT.md): a steward decides a schema change, a designer applies it
		FactoryRights.route(FactoryRight.STEWARD, "GET /projects/{code}/drift", "POST /drift/{id}/decide");
		FactoryRights.route(FactoryRight.DESIGN, "POST /drift/{id}/apply");
		FactoryRights.route(FactoryRight.DESIGN, "POST /sources/{id}/landing/plan", "POST /sources/{id}/landing/generate", "POST /sources/{id}/landing/flows",
			"POST /sources/{id}/landing/load", "POST /sources/{id}/relations/check", "GET /sources/onboarding/connections", "GET /sources/onboarding/{connector}",
			"POST /sources/onboarding/{connector}/catalog", "POST /sources/onboarding/{connector}/scan", "POST /sources/onboarding/{connector}/all",
			"POST /projects/{code}/sources/files", "POST /projects/{code}/staging/suggest", "POST /projects/{code}/quality/suggest",
			"POST /projects/{code}/quality/suggest-llm", "POST /projects/{code}/staging/suggest-llm", "POST /projects/{code}/staging/generate",
			"POST /projects/{code}/staging/load", "POST /projects/{code}/staging/preflight", "POST /projects/{code}/master/generate", "POST /projects/{code}/master/load",
			"GET /projects/{code}/distribution/product", "POST /projects/{code}/distribution/product", "POST /projects/{code}/matching/suggest",
			"POST /projects/{code}/matching/suggest-llm", "POST /projects/{code}/matching/configure",
			"POST /projects/{code}/matching/run", "POST /projects/{code}/matching/refresh-golden-keys", "POST /projects/{code}/matching/simulate",
			"POST /projects/{code}/matching/survivorship", "POST /projects/{code}/matching/simulate-preview/remove",
			"POST /projects/{code}/capabilities/suggest-llm", "POST /projects/{code}/capabilities/{id}/apply", "POST /projects/{code}/capabilities/apply",
			"POST /projects/{code}/capabilities/{id}/remove", "POST /projects/{code}/capabilities/{id}/backfill", "PUT /projects/{code}/capabilities/{id}/binding",
			"POST /projects/{code}/capabilities/{id}/measure", "POST /projects/{code}/catalog/declare-lineage", "GET /projects/{code}/distribution/targets",
			"GET /projects/{code}/distribution/connections", "POST /projects/{code}/distribution/targets",
			"POST /projects/{code}/distribution/targets/{targetId}/configure", "POST /projects/{code}/distribution/targets/{targetId}/install-triggers",
			"POST /projects/{code}/distribution/targets/{targetId}/first-full-send", "POST /projects/{code}/distribution/targets/{targetId}/disarm",
			"POST /projects/{code}/distribution/targets/{targetId}/send", "GET /projects/{code}/distribution/events",
			"POST /projects/{code}/distribution/events/replay", "POST /projects/{code}/intake/settings", "POST /projects/{code}/intake/on",
			"POST /projects/{code}/intake/pause", "POST /projects/{code}/intake/resume", "POST /projects/{code}/intake/off", "POST /projects/{code}/intake/process",
			"POST /projects/{code}/intake/{source: [^/:]+}:read");
		FactoryRights.route(FactoryRight.ADMINISTRATOR, "GET /connection/auth-probe", "POST /maintenance/publication-owners", "GET /connection/test",
			"POST /projects/{code}/master/reset", "GET /projects/{code}/deletion/plan", "POST /projects/{code}/delete", "GET /projects/{code}/reset/plan",
			"POST /projects/{code}/reset", "GET /projects/{code}/backup", "POST /projects/{code}/restore", "GET /projects/{code}/delivery/domain", "GET /diagnostics/master-zone-index",
			"POST /projects/{code}/distribution/targets/{targetId}/arm-override", "POST /projects/{code}/distribution/targets/{targetId}/stop");

		// orphan-connections lot (2026-10-17): the list is read-only, the deletion removes connections and catalogue sources - both administrator
		FactoryRights.route(FactoryRight.ADMINISTRATOR, "GET /connections/orphans", "POST /connections/orphans/delete");
		// lot M18 (2026-10-18): the orphan sweep of the Match and Merge add-on's configuration dataset
		FactoryRights.route(FactoryRight.ADMINISTRATOR, "GET /matching/orphans", "POST /matching/orphans/delete");

		// lot H2 (2026-10-17, tools/e2e): the scenario routes - thin wrappers over the wizard's own buttons, each at the right of that button
		FactoryRights.route(FactoryRight.VIEW, "GET /projects/{code}/staging/review", "GET /projects/{code}/rejects/causes"); // + lot A (hard cases): the causes behind step 4's banner
		FactoryRights.route(FactoryRight.DESIGN, "POST /projects/{code}/landing/run", "POST /projects/{code}/staging/propose", "POST /projects/{code}/staging/decide",
			"POST /projects/{code}/staging/decisions");
		// lot E2E-master (2026-10-17): the master and matching scenario routes - the page's "Build the master" and "Propose the matching plans" buttons, at the DESIGN right of their siblings
		FactoryRights.route(FactoryRight.DESIGN, "POST /projects/{code}/master/build", "POST /projects/{code}/matching/propose",
			"POST /projects/{code}/matching/simulate-plan");

		// ------------------------------------------- DESIGN, or the pipeline launcher role (factory setting pipelineLauncherRole)
		for (final String one : new String[] { "PIPELINE_LAUNCH", "INTAKE_SAVE", "INTAKE_SWITCH_ON", "INTAKE_PAUSE", "INTAKE_RESUME", "INTAKE_SWITCH_OFF",
			"INTAKE_READ_NOW", "INTAKE_PROCESS_NOW", "POST /projects/{code}/intake/settings", "POST /projects/{code}/intake/on", "POST /projects/{code}/intake/pause",
			"POST /projects/{code}/intake/resume", "POST /projects/{code}/intake/off", "POST /projects/{code}/intake/process",
			"POST /projects/{code}/intake/{source: [^/:]+}:read" })
		{
			FactoryRights.PIPELINE_OPERATOR.add(one);
		}
	}

	/** The right a wizard action asks; an action not listed asks {@link FactoryRight#ADMINISTRATOR}. */
	public static FactoryRight wizardAction(final String pAction)
	{
		final FactoryRight right = FactoryRights.WIZARD.get(pAction == null ? "" : pAction);
		return right == null ? FactoryRight.ADMINISTRATOR : right;
	}

	/** The right a proposal-review action asks; an action not listed asks {@link FactoryRight#ADMINISTRATOR}. */
	public static FactoryRight reviewAction(final String pAction)
	{
		final FactoryRight right = FactoryRights.REVIEW.get(pAction == null ? "" : pAction);
		return right == null ? FactoryRight.ADMINISTRATOR : right;
	}

	/** The right that shows and opens a user service of the module (its service name); unknown asks {@link FactoryRight#ADMINISTRATOR}. */
	public static FactoryRight service(final String pServiceName)
	{
		final FactoryRight right = pServiceName == null ? null : FactoryRights.SERVICES.get(pServiceName);
		return right == null ? FactoryRight.ADMINISTRATOR : right;
	}

	/** The right a REST route asks, keyed {@code "<METHOD> <@Path value>"}; a route not listed asks {@link FactoryRight#ADMINISTRATOR}. */
	public static FactoryRight route(final String pRouteKey)
	{
		final FactoryRight right = pRouteKey == null ? null : FactoryRights.ROUTES.get(pRouteKey);
		return right == null ? FactoryRight.ADMINISTRATOR : right;
	}

	/** True for a wizard action or a route key that a holder of the pipeline launcher role may use without the DESIGN right. */
	public static boolean pipelineOperatorMay(final String pActionOrRoute)
	{
		return pActionOrRoute != null && FactoryRights.PIPELINE_OPERATOR.contains(pActionOrRoute);
	}

	/** Every decided wizard action (for the tests and the documentation). */
	public static Map<String, FactoryRight> wizardActions()
	{
		return Collections.unmodifiableMap(FactoryRights.WIZARD);
	}

	public static Map<String, FactoryRight> reviewActions()
	{
		return Collections.unmodifiableMap(FactoryRights.REVIEW);
	}

	public static Map<String, FactoryRight> services()
	{
		return Collections.unmodifiableMap(FactoryRights.SERVICES);
	}

	public static Map<String, FactoryRight> routes()
	{
		return Collections.unmodifiableMap(FactoryRights.ROUTES);
	}

	public static Set<String> pipelineOperatorEntries()
	{
		return Collections.unmodifiableSet(FactoryRights.PIPELINE_OPERATOR);
	}
}
