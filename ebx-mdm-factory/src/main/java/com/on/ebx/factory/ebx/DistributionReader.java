package com.on.ebx.factory.ebx;

import java.util.Date;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.MergeLogState;
import com.on.ebx.factory.distribution.TargetValidator;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Distribution lot 1 (engine): the plain reads/writes behind {@code rest.FactoryResource}'s distribution
 * routes that are not specific to Connectors/data-model/scripting orchestration (that part is
 * {@link DistributionConfigurator}/{@link DistributionRunner}/{@link DistributionTriggerInstaller}) - list
 * targets, upsert one from a JSON body ({@code distribution.TargetValidator} gates it), set its state
 * directly (disarm), list/replay events. Kept separate from those three so a caller only pulls in the
 * Connectors/MCP surface it actually needs.
 */
public final class DistributionReader
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DistributionReader()
	{
	}

	public static ObjectNode listTargets(final Adaptation pFactory, final String pProjectCode) throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		final ArrayNode targets = out.putArray("targets");
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), "./project=" + CatalogReader.quote(projectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				targets.add(DistributionReader.targetJson(row));
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static ObjectNode targetJson(final Adaptation pRow)
	{
		final ObjectNode node = DistributionReader.MAPPER.createObjectNode();
		node.put("id", pRow.getOccurrencePrimaryKey().format());
		node.put("connector", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.CONNECTOR));
		node.put("operation", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.OPERATION));
		node.put("connectorsMappingIds", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
		node.put("tableDestinations", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.TABLE_DESTINATIONS));
		node.put("mode", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.MODE));
		node.put("cron", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.CRON));
		node.put("scope", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.SCOPE));
		node.put("approval", Boolean.TRUE.equals(UserView.get(pRow, FactoryPaths.DistributionTarget.APPROVAL)));
		node.put("watchedFields", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.WATCHED_FIELDS));
		node.put("includeCrosswalk", Boolean.TRUE.equals(UserView.get(pRow, FactoryPaths.DistributionTarget.INCLUDE_CROSSWALK)));
		node.put("includePending", Boolean.TRUE.equals(UserView.get(pRow, FactoryPaths.DistributionTarget.INCLUDE_PENDING)));
		node.put("state", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.STATE));
		node.put("armedViaOverride", Boolean.TRUE.equals(UserView.get(pRow, FactoryPaths.DistributionTarget.ARMED_VIA_OVERRIDE)));
		node.put("firstFullSendRunId", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.FIRST_FULL_SEND_RUN_ID));
		node.put("lastStatus", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.LAST_STATUS));
		// step 7 screen counts (2026-10-18): the send's sentence is stored with its code marker; REST readers get the plain English, the wizard the coded text to word in the person's language
		final String lastMessageStored = DistributionReader.text(pRow, FactoryPaths.DistributionTarget.LAST_MESSAGE);
		node.put("lastMessage", com.on.ebx.factory.wizard.EngineMessageCodec.stripMarkers(lastMessageStored));
		node.put("lastMessageCoded", lastMessageStored);
		node.put("scheduleKey", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.SCHEDULE_KEY));
		node.put("launcherName", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.LAUNCHER_NAME));
		// "retire notices from the merge log" (SPEC-retire.md)
		node.put("retireMode", DistributionReader.text(pRow, FactoryPaths.DistributionTarget.RETIRE_MODE));
		node.put("retireUndoneMerges", !Boolean.FALSE.equals(UserView.get(pRow, FactoryPaths.DistributionTarget.RETIRE_UNDONE_MERGES)));
		final Object lastSendAt = UserView.get(pRow, FactoryPaths.DistributionTarget.LAST_SEND_AT);
		node.put("lastSendAt", lastSendAt instanceof Date ? ((Date) lastSendAt).toInstant().toString() : "");
		// lot S2: when the state sweep last compared the merge states of this target (stored by ebx.MatchingStateSweeper; blank before the first one)
		node.put("lastSweepAt", MergeLogState.parse(DistributionReader.text(pRow, FactoryPaths.DistributionTarget.MERGE_LOG_WATERMARK)).sweep().at);
		return node;
	}

	/**
	 * Step 7 screen counts (2026-10-18): what the OTHER projects of this Factory leave on the connections list of this one - their codes ({@code codes}) and the connections their
	 * distribution targets use ({@code connectors}). The wizard files a connection made for another project under "other connections" (a test project's Kafka was listed as
	 * a likely target of the next one). Cheap: the project and target tables, one pass each, no Connectors call.
	 */
	public static ObjectNode otherProjects(final Adaptation pFactory, final String pProjectCode)
	{
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		final ArrayNode codes = out.putArray("codes");
		final ArrayNode connectors = out.putArray("connectors");
		final java.util.Set<String> otherIds = new java.util.HashSet<String>();
		final java.util.Set<String> seenConnectors = new java.util.LinkedHashSet<String>();
		RequestResult projects = UserView.select(pFactory.getTable(FactoryPaths.PROJECT), null);
		try
		{
			for (Adaptation row = projects.nextAdaptation(); row != null; row = projects.nextAdaptation())
			{
				final String code = DistributionReader.text(row, FactoryPaths.Project.CODE);
				if (!code.isEmpty() && !code.equals(pProjectCode))
				{
					codes.add(code);
					otherIds.add(row.getOccurrencePrimaryKey().format());
				}
			}
		}
		finally
		{
			projects.close();
		}
		final RequestResult targets = UserView.select(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), null);
		try
		{
			for (Adaptation row = targets.nextAdaptation(); row != null; row = targets.nextAdaptation())
			{
				if (otherIds.contains(DistributionReader.text(row, FactoryPaths.DistributionTarget.PROJECT)))
				{
					seenConnectors.add(DistributionReader.text(row, FactoryPaths.DistributionTarget.CONNECTOR));
				}
			}
		}
		finally
		{
			targets.close();
		}
		for (final String connector : seenConnectors)
		{
			if (!connector.isEmpty())
			{
				connectors.add(connector);
			}
		}
		return out;
	}

	/** Lot S2: how far {@link #retireFacts} counts before it says "at least". */
	static final int RETIRE_COUNT_CAP = 10000;

	/**
	 * Lot S2 (2026-10-18): the figures the wizard shows beside a REALTIME target's retire settings and in the summary: {@code retiresSent} = the RETIRE notices
	 * of its history that reached the target (counted up to {@link #RETIRE_COUNT_CAP}; {@code retiresCapped} says it stopped there), {@code lastSweepAt} = the
	 * later of the stored time of the last real sweep and the last time this JVM looked at the target. One scan of the history for the target: called for the
	 * open target and the summary's REALTIME rows only, never for the plain list.
	 */
	public static ObjectNode retireFacts(final Adaptation pFactory, final String pTargetId)
	{
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		int sent = 0;
		boolean capped = false;
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT), "./target=" + CatalogReader.quote(pTargetId) + " and ./change="
			+ CatalogReader.quote(FactoryPaths.DistributionEvent.CHANGE_RETIRE) + " and ./status=" + CatalogReader.quote(FactoryPaths.DistributionEvent.STATUS_SENT));
		try
		{
			while (rows.nextAdaptation() != null)
			{
				if (sent >= DistributionReader.RETIRE_COUNT_CAP)
				{
					capped = true;
					break;
				}
				sent++;
			}
		}
		finally
		{
			rows.close();
		}
		out.put("retiresSent", sent);
		out.put("retiresCapped", capped);
		String stored = "";
		final Adaptation target = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
		if (target != null)
		{
			stored = MergeLogState.parse(DistributionReader.text(target, FactoryPaths.DistributionTarget.MERGE_LOG_WATERMARK)).sweep().at;
		}
		final long looked = MatchingStateSweeper.lastCheckMillis(pTargetId);
		long storedMillis = 0L;
		try
		{
			storedMillis = stored.isEmpty() ? 0L : java.time.Instant.parse(stored).toEpochMilli();
		}
		catch (final java.time.format.DateTimeParseException ex)
		{
			storedMillis = 0L;
		}
		out.put("lastSweepAt", looked > storedMillis ? java.time.Instant.ofEpochMilli(looked).toString() : stored);
		return out;
	}

	private static String text(final Adaptation pRow, final com.orchestranetworks.schema.Path pField)
	{
		final String value = UserView.string(pRow, pField);
		return value == null ? "" : value;
	}

	/**
	 * Creates a target (blank/absent {@code id}) or updates one in place. Body: {@code connector, operation,
	 * mode, cron, scope, approval, watchedFields, includeCrosswalk, includePending} - validated with
	 * {@code distribution.TargetValidator} before any write.
	 */
	public static ObjectNode upsertTarget(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pJsonBody) throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final JsonNode body;
		try
		{
			body = DistributionReader.MAPPER.readTree(pJsonBody == null ? "{}" : pJsonBody);
		}
		catch (final com.fasterxml.jackson.core.JsonProcessingException ex)
		{
			// X3-08: a bound refusal (nesting depth, string length...) is told from a malformed document, each with its code and its bundle sentence
			throw com.on.ebx.factory.core.JsonLimits.isBoundRefusal(ex)
				? EngineRefusal.coded("invalid.jsonBeyondBound", com.on.ebx.factory.core.JsonLimits.refusal(ex, "the request body"))
				: EngineRefusal.coded("invalid.malformedJson", com.on.ebx.factory.core.JsonLimits.refusal(ex, "the request body"));
		}
		final String connector = body.path("connector").asText(null);
		final String operation = body.path("operation").asText(null);
		final String mode = body.path("mode").asText(FactoryPaths.DistributionTarget.MODE_ON_DEMAND);
		final String cron = body.path("cron").asText("");
		final String scope = body.path("scope").asText("");
		final TargetValidator.Result validation = TargetValidator.validate(connector, operation, mode, cron, scope);
		if (!validation.valid)
		{
			throw EngineRefusal.coded("invalid.targetFields", "Distribution target: " + String.join("; ", validation.errors));
		}
		final String existingId = body.path("id").asText(null);
		// volume-operations lot 8b (2026-09-28, task 1): mode can change here (state does not - it is only ever
		// set on CREATE, to DRAFT, which never counts); read the existing row's own (mode, state) BEFORE the
		// write below, so an update that moves an already-ARMED target's mode into/out of REALTIME is caught -
		// see DistributionControlRow's own Javadoc. Null for a brand-new target (never an ARM/DISARM transition:
		// its state starts DRAFT, which never counts either).
		final Adaptation existingBefore = existingId == null || existingId.trim().isEmpty() ? null
			: UserView.byKey(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), PrimaryKey.parseString(existingId));
		final String oldMode = existingBefore == null ? null : UserView.string(existingBefore, FactoryPaths.DistributionTarget.MODE);
		final String oldState = existingBefore == null ? null : UserView.string(existingBefore, FactoryPaths.DistributionTarget.STATE);
		DistributionControlRow.beforeStateChange(pSession, pFactory, projectId, oldMode, oldState, mode, oldState);
		final String[] holder = new String[1];
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value;
				if (existingId != null && !existingId.trim().isEmpty())
				{
					final Adaptation row = UserView.byKey(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), PrimaryKey.parseString(existingId));
					if (row == null)
					{
						throw new IllegalStateException("Distribution target " + existingId + " does not exist");
					}
					value = pContext.getContext(row.getAdaptationName());
				}
				else
				{
					value = pContext.getContextForNewOccurrence(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET));
					value.setValue(projectId, FactoryPaths.DistributionTarget.PROJECT);
					value.setValue(FactoryPaths.DistributionTarget.STATE_DRAFT, FactoryPaths.DistributionTarget.STATE);
				}
				value.setValue(connector, FactoryPaths.DistributionTarget.CONNECTOR);
				value.setValue(operation, FactoryPaths.DistributionTarget.OPERATION);
				value.setValue(mode, FactoryPaths.DistributionTarget.MODE);
				value.setValue(cron, FactoryPaths.DistributionTarget.CRON);
				value.setValue(scope.isEmpty() ? "CHANGED" : scope, FactoryPaths.DistributionTarget.SCOPE);
				value.setValue(Boolean.valueOf(body.path("approval").asBoolean(false)), FactoryPaths.DistributionTarget.APPROVAL);
				value.setValue(body.path("watchedFields").asText(""), FactoryPaths.DistributionTarget.WATCHED_FIELDS);
				value.setValue(Boolean.valueOf(body.path("includeCrosswalk").asBoolean(false)), FactoryPaths.DistributionTarget.INCLUDE_CROSSWALK);
				value.setValue(Boolean.valueOf(body.path("includePending").asBoolean(false)), FactoryPaths.DistributionTarget.INCLUDE_PENDING);
				// round 6 (task brief item C): a partial field, same "present = write it, absent = leave
				// untouched" discipline as a Data Services PUT (CLAUDE.md sec 6) - the wizard's 7b "Table -> topic"
				// rows post it on every save once the connector supports per-table destinations, but a caller
				// that never mentions it (an older client, or a non-Kafka target) must not wipe a value
				// ebx.DistributionConfigurator's own Configure step may have written.
				if (body.has("tableDestinations"))
				{
					value.setValue(body.path("tableDestinations").asText(""), FactoryPaths.DistributionTarget.TABLE_DESTINATIONS);
				}
				// "retire notices from the merge log" (SPEC-retire.md): same partial-write discipline as
				// tableDestinations above - a caller that never mentions retireMode/retireUndoneMerges must not
				// silently reset a value the wizard, or a previous save, already set (the enumeration's own
				// default of NOTICE only takes effect on the FIRST save of a brand new target, via EBX's own
				// XSD default on create - never applied here on an update path).
				if (body.has("retireMode"))
				{
					value.setValue(body.path("retireMode").asText(FactoryPaths.DistributionTarget.RETIRE_MODE_NOTICE), FactoryPaths.DistributionTarget.RETIRE_MODE);
				}
				if (body.has("retireUndoneMerges"))
				{
					value.setValue(Boolean.valueOf(body.path("retireUndoneMerges").asBoolean(true)), FactoryPaths.DistributionTarget.RETIRE_UNDONE_MERGES);
				}
				if (existingId != null && !existingId.trim().isEmpty())
				{
					pContext.doModifyContent(UserView.byKey(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), PrimaryKey.parseString(existingId)), value);
					holder[0] = existingId;
				}
				else
				{
					holder[0] = pContext.doCreateOccurrence(value, pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET)).getOccurrencePrimaryKey().format();
				}
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveTarget", "Distribution target: could not save: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH));
		}
		// volume-operations lot 8a (2026-09-28, task 3): mode (ON_DEMAND/SCHEDULED/REALTIME) can change on an
		// update - see ArmedTargetCache's own Javadoc for why this cache is not (yet) reachable from the
		// trigger script itself.
		com.on.ebx.factory.distribution.ArmedTargetCache.invalidate(projectId);
		// volume-operations lot 8b: the DISARM half of the mode change above - see this method's own comment.
		DistributionControlRow.afterStateChange(pSession, pFactory, projectId, oldMode, oldState, mode, oldState);
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		out.put("status", "DONE");
		out.put("id", holder[0]);
		return out;
	}

	public static ObjectNode setState(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pState) throws OperationException
	{
		final Adaptation before = UserView.byKey(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), PrimaryKey.parseString(pTargetId));
		final String projectId = before == null ? null : UserView.string(before, FactoryPaths.DistributionTarget.PROJECT);
		final String oldMode = before == null ? null : UserView.string(before, FactoryPaths.DistributionTarget.MODE);
		final String oldState = before == null ? null : UserView.string(before, FactoryPaths.DistributionTarget.STATE);
		// volume-operations lot 8b (2026-09-28, task 1): this route is normally "disarm" (a DISARM transition,
		// refreshed AFTER below), but the method is generic - handle an ARM transition too, before the commit,
		// same discipline as every other write path. See DistributionControlRow's own Javadoc.
		DistributionControlRow.beforeStateChange(pSession, pFactory, projectId, oldMode, oldState, oldMode, pState);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = UserView.byKey(pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET), PrimaryKey.parseString(pTargetId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pState, FactoryPaths.DistributionTarget.STATE);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveTargetState", "Distribution target " + pTargetId + ": could not set state " + pState + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pTargetId);
		}
		// volume-operations lot 8a (2026-09-28, task 3): see upsertTarget's own comment above.
		com.on.ebx.factory.distribution.ArmedTargetCache.invalidate(projectId);
		// volume-operations lot 8b: the DISARM half - safe to refresh after the commit, see this method's own
		// comment above.
		DistributionControlRow.afterStateChange(pSession, pFactory, projectId, oldMode, oldState, oldMode, pState);
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		out.put("status", "DONE");
		out.put("state", pState);
		return out;
	}

	public static ObjectNode listEvents(final Adaptation pFactory, final String pProjectCode, final String pTargetId, final String pStatus, final int pLimit)
		throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final StringBuilder predicate = new StringBuilder("./project=").append(CatalogReader.quote(projectId));
		if (pTargetId != null && !pTargetId.trim().isEmpty())
		{
			predicate.append(" and ./target=").append(CatalogReader.quote(pTargetId));
		}
		if (pStatus != null && !pStatus.trim().isEmpty())
		{
			predicate.append(" and ./status=").append(CatalogReader.quote(pStatus));
		}
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		final ArrayNode events = out.putArray("events");
		final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT), predicate.toString());
		try
		{
			int count = 0;
			final int limit = pLimit <= 0 ? 50 : pLimit;
			for (Adaptation row = rows.nextAdaptation(); row != null && count < limit; row = rows.nextAdaptation(), count++)
			{
				final ObjectNode event = DistributionReader.MAPPER.createObjectNode();
				event.put("id", row.getOccurrencePrimaryKey().format());
				event.put("target", DistributionReader.text(row, FactoryPaths.DistributionEvent.TARGET));
				event.put("tablePath", DistributionReader.text(row, FactoryPaths.DistributionEvent.TABLE_PATH));
				event.put("recordKey", DistributionReader.text(row, FactoryPaths.DistributionEvent.RECORD_KEY));
				event.put("change", DistributionReader.text(row, FactoryPaths.DistributionEvent.CHANGE));
				event.put("status", DistributionReader.text(row, FactoryPaths.DistributionEvent.STATUS));
				final Object attempts = UserView.get(row, FactoryPaths.DistributionEvent.ATTEMPTS);
				event.put("attempts", attempts instanceof Number ? ((Number) attempts).intValue() : 0);
				event.put("message", DistributionReader.text(row, FactoryPaths.DistributionEvent.MESSAGE));
				// "retire notices from the merge log" (SPEC-retire.md): set only on a RETIRE/CREATE the merge-log
				// watch itself wrote - empty for every event a table trigger wrote directly.
				event.put("reason", DistributionReader.text(row, FactoryPaths.DistributionEvent.REASON));
				event.put("mergeId", DistributionReader.text(row, FactoryPaths.DistributionEvent.MERGE_ID));
				// UI lot (SPEC-retire.md "History and UI"): the wizard's target history needs the golden a
				// RETIRE event's source was merged into, and when the event was written - neither was read here
				// before (targetJson's own lastSendAt is the target's last send, not this row's own timestamp).
				event.put("survivorKey", DistributionReader.text(row, FactoryPaths.DistributionEvent.SURVIVOR_KEY));
				final Object createdAt = UserView.get(row, FactoryPaths.DistributionEvent.CREATED_AT);
				event.put("createdAt", createdAt instanceof Date ? ((Date) createdAt).toInstant().toString() : "");
				events.add(event);
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	/**
	 * Replays one FAILED history event (or every FAILED event of the target/project when {@code pEventId} is
	 * blank).
	 * <p>
	 * <b>Round 3 change</b>: {@code DistributionEvent} in MdmFactory is now a TERMINAL history/audit table only
	 * ({@code ebx.DistributionDispatcher} writes a row here on SENT/SKIPPED/FAILED-exhausted, never reads it
	 * back) - the dispatcher drains a project's OWN {@code DistributionOutbox} dataset instead (see
	 * {@code DistributionTriggerBody}'s own Javadoc for why the outbox could not stay here). Flipping a history
	 * row's {@code status} back to {@code PENDING} would therefore no longer do anything: nothing reads it.
	 * Replaying now means inserting a FRESH {@code PENDING} row into that project's outbox dataset, carrying the
	 * same {@code target}/{@code tablePath}/{@code recordKey}/{@code change}/{@code survivorKey} as the FAILED
	 * history row - the dispatcher picks it up on its next pass exactly as if the master-table trigger had just
	 * written it. The original history row is left untouched (it stays the record of the earlier failure); the
	 * replay's own eventual outcome gets its own new history row once it reaches a terminal state again.
	 */
	public static ObjectNode replayEvents(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId, final String pEventId)
		throws OperationException
	{
		final java.util.List<Adaptation> historyRows = new java.util.ArrayList<Adaptation>();
		if (pEventId != null && !pEventId.trim().isEmpty())
		{
			final Adaptation row = UserView.byKey(pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT), PrimaryKey.parseString(pEventId.trim()));
			if (row != null)
			{
				historyRows.add(row);
			}
		}
		else
		{
			final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
			if (project == null)
			{
				throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
			}
			final String projectId = project.getOccurrencePrimaryKey().format();
			final StringBuilder predicate = new StringBuilder("./project=").append(CatalogReader.quote(projectId)).append(" and ./status=")
				.append(CatalogReader.quote(FactoryPaths.DistributionEvent.STATUS_FAILED));
			if (pTargetId != null && !pTargetId.trim().isEmpty())
			{
				predicate.append(" and ./target=").append(CatalogReader.quote(pTargetId));
			}
			final RequestResult rows = UserView.select(pFactory.getTable(FactoryPaths.DISTRIBUTION_EVENT), predicate.toString());
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					historyRows.add(row);
				}
			}
			finally
			{
				rows.close();
			}
		}
		int replayed = 0;
		for (final Adaptation historyRow : historyRows)
		{
			if (DistributionReader.requeueOutboxRow(pSession, pFactory, historyRow))
			{
				replayed++;
			}
		}
		final ObjectNode out = DistributionReader.MAPPER.createObjectNode();
		out.put("status", "DONE");
		out.put("replayed", replayed);
		out.put("consideredCount", historyRows.size());
		return out;
	}

	/** Inserts one fresh PENDING row into the project's master-dataspace outbox dataset, from one FAILED history row's own fields. @return false when the project's master/outbox could not be resolved (best-effort - a single bad history row never aborts a batch replay). */
	private static boolean requeueOutboxRow(final Session pSession, final Adaptation pFactory, final Adaptation pHistoryRow)
	{
		final String projectId = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.PROJECT);
		final Adaptation project = projectId == null ? null : UserView.byKey(pFactory.getTable(FactoryPaths.PROJECT), PrimaryKey.parseString(projectId));
		if (project == null)
		{
			return false;
		}
		final String masterDataspaceKey = UserView.string(project, FactoryPaths.Project.MASTER_DATASPACE);
		if (masterDataspaceKey == null || masterDataspaceKey.trim().isEmpty())
		{
			return false;
		}
		final com.onwbp.adaptation.AdaptationHome masterHome = pFactory.getHome().getRepository()
			.lookupHome(com.orchestranetworks.instance.HomeKey.forBranchName(masterDataspaceKey));
		if (masterHome == null)
		{
			return false;
		}
		final Adaptation outboxDataset = masterHome.findAdaptationOrNull(com.onwbp.adaptation.AdaptationName.forName(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET));
		if (outboxDataset == null)
		{
			return false;
		}
		final String targetId = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.TARGET);
		final String tablePath = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.TABLE_PATH);
		final String recordKey = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.RECORD_KEY);
		final String change = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.CHANGE);
		final String survivorKey = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.SURVIVOR_KEY);
		// "retire notices from the merge log" (SPEC-retire.md): carried along so a replayed RETIRE's document
		// still gets its reason/mergeId - see ebx.DistributionDispatcher#sendRetireNotice.
		final String reason = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.REASON);
		final String mergeId = UserView.string(pHistoryRow, FactoryPaths.DistributionEvent.MERGE_ID);
		final com.onwbp.adaptation.AdaptationTable outboxTable = outboxDataset.getTable(FactoryPaths.DistributionOutbox.EVENT);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, masterHome).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(outboxTable);
				value.setValue(targetId, FactoryPaths.DistributionOutbox.TARGET);
				value.setValue(tablePath, FactoryPaths.DistributionOutbox.TABLE_PATH);
				value.setValue(recordKey, FactoryPaths.DistributionOutbox.RECORD_KEY);
				value.setValue(change, FactoryPaths.DistributionOutbox.CHANGE);
				value.setValue(survivorKey, FactoryPaths.DistributionOutbox.SURVIVOR_KEY);
				value.setValue(FactoryPaths.DistributionOutbox.STATUS_PENDING, FactoryPaths.DistributionOutbox.STATUS);
				value.setValue(Integer.valueOf(0), FactoryPaths.DistributionOutbox.ATTEMPTS);
				value.setValue(new java.util.Date(), FactoryPaths.DistributionOutbox.CREATED_AT);
				if (reason != null)
				{
					value.setValue(reason, FactoryPaths.DistributionOutbox.REASON);
				}
				if (mergeId != null)
				{
					value.setValue(mergeId, FactoryPaths.DistributionOutbox.MERGE_ID);
				}
				pContext.doCreateOccurrence(value, outboxTable);
			}
		});
		return !result.hasFailed();
	}
}
