package com.on.ebx.factory.ebx;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.TextLimits;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "On demand / Scheduled"): creates the workflow launcher
 * row ({@code Bworkflow_launchers/workflow_launchers_1.0}, Data Services REST - CLAUDE.md sec 7 "Workflow
 * launchers are writable over Data Services") for a target's {@code MdmFactoryDistribution} workflow, then
 * for a {@code SCHEDULED} target a Scheduler REST API schedule
 * (POST/PUT {@code /ebx-dataservices/rest/scheduler/v1/schedules}, bare JSON array body, {@code taskKey}
 * {@code EBX|SchedulerWorkflowLaunch}, parameter {@code workflowLauncherName} - CLAUDE.md sec 2 "taskScheduler"
 * bullet: never set {@code userReference}, the array body is mandatory, plain object/{@code {"rows":...}} is
 * refused). Both calls run as the caller's own session ({@link SessionToken}/{@link LocalEndpoint}, the
 * agent account only as a {@link CredentialPolicy}-gated fallback) - no stored credential of its own.
 * <p>
 * <b>Not verified live</b>: no EBX instance was reachable while writing this class. In particular:
 * <ul>
 * <li>The workflow-launcher dataset's exact dataspace/dataset/table path is transcribed from a prior
 * session's own note (CLAUDE.md sec 7) and was not re-read from a live {@code GET} here - if the real path
 * differs, every call in this class answers a plain 404, easy to tell apart from a real refusal.</li>
 * <li>The Scheduler REST API's exact JSON shape (field names, whether the endpoint is {@code POST} for a
 * create and {@code PUT} for an update, or one verb for both) is transcribed from CLAUDE.md sec 2's own
 * "registering a schedule" bullet, not re-verified here.</li>
 * </ul>
 * See {@code docs/DISTRIBUTION.md} for the exact live steps to verify (a dry, idempotent {@code GET} of both
 * endpoints first, before ever attempting a write, is the recommended order).
 */
public final class DistributionScheduler
{
	private static final ObjectMapper MAPPER = new ObjectMapper();
	private static final int CONNECT_TIMEOUT_MS = 5000;
	private static final int READ_TIMEOUT_MS = 30000;
	/** Deduced path (CLAUDE.md sec 7); not re-verified live - see this class' own Javadoc. */
	private static final String LAUNCHER_PATH = "/ebx-dataservices/rest/data/v1/Bworkflow_launchers/workflow_launchers_1.0/root/definition";
	private static final String SCHEDULES_PATH = "/ebx-dataservices/rest/scheduler/v1/schedules";
	private static final String WORKFLOW_MODEL_NAME = "MdmFactoryDistribution";
	private static final String SCHEDULER_TASK_KEY = "EBX|SchedulerWorkflowLaunch";

	private DistributionScheduler()
	{
	}

	/** Creates (never updates in place - a distinct launcher name per target avoids the "no update tool" gap this REST table has, same reasoning as CLAUDE.md's "there is no upsert") the workflow launcher row for a target, and writes {@code DistributionTarget.launcherName} back. */
	public static ObjectNode createLauncher(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pProjectCode) throws OperationException
	{
		final String launcherName = "MdmFactoryDistribution_" + pTargetId;
		final ObjectNode content = DistributionScheduler.MAPPER.createObjectNode();
		content.put("name", launcherName);
		content.put("workflow_publication_name", DistributionScheduler.WORKFLOW_MODEL_NAME);
		final ArrayNode params = content.putArray("data_context_parameter");
		DistributionScheduler.addParam(params, "projectCode", pProjectCode);
		DistributionScheduler.addParam(params, "targetId", pTargetId);
		// Data Services batch insert envelope: {"rows":[{"content":{...}}]} - CLAUDE.md sec 6
		final ArrayNode rows = DistributionScheduler.MAPPER.createArrayNode();
		final ObjectNode row = rows.addObject();
		row.set("content", content);
		final ObjectNode insertBody = DistributionScheduler.MAPPER.createObjectNode();
		insertBody.set("rows", rows);

		final HttpOutcome outcome = DistributionScheduler.call(pSession, pFactory, "POST", DistributionScheduler.LAUNCHER_PATH, insertBody.toString());
		final ObjectNode out = DistributionScheduler.MAPPER.createObjectNode();
		out.put("status", outcome.ok ? "DONE" : "FAILED");
		out.put("httpStatus", outcome.httpStatus);
		out.put("message", outcome.message);
		out.put("launcherName", launcherName);
		if (outcome.ok)
		{
			DistributionScheduler.writeTargetField(pSession, pFactory, pTargetId, FactoryPaths.DistributionTarget.LAUNCHER_NAME, launcherName);
		}
		return out;
	}

	/** {@code SCHEDULED} targets only: creates the Scheduler REST API schedule on {@code EBX|SchedulerWorkflowLaunch}, and writes {@code DistributionTarget.scheduleKey} back. Requires {@code launcherName} already set ({@link #createLauncher} first). */
	public static ObjectNode createSchedule(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pCronExpression, final String pLauncherName)
		throws OperationException
	{
		final String scheduleKey = "MdmFactoryDistribution_" + pTargetId;
		final ObjectNode schedule = DistributionScheduler.MAPPER.createObjectNode();
		schedule.put("key", scheduleKey);
		schedule.put("taskKey", DistributionScheduler.SCHEDULER_TASK_KEY);
		schedule.put("expression", pCronExpression);
		final ObjectNode parameters = schedule.putObject("parameters");
		parameters.put("workflowLauncherName", pLauncherName);
		final ArrayNode body = DistributionScheduler.MAPPER.createArrayNode();
		body.add(schedule);

		final HttpOutcome outcome = DistributionScheduler.call(pSession, pFactory, "POST", DistributionScheduler.SCHEDULES_PATH, body.toString());
		final ObjectNode out = DistributionScheduler.MAPPER.createObjectNode();
		out.put("status", outcome.ok ? "DONE" : "FAILED");
		out.put("httpStatus", outcome.httpStatus);
		out.put("message", outcome.message);
		out.put("scheduleKey", scheduleKey);
		if (outcome.ok)
		{
			DistributionScheduler.writeTargetField(pSession, pFactory, pTargetId, FactoryPaths.DistributionTarget.SCHEDULE_KEY, scheduleKey);
		}
		return out;
	}

	private static void addParam(final ArrayNode pParams, final String pName, final String pValue)
	{
		final ObjectNode param = pParams.addObject();
		param.put("name", pName);
		param.put("value", pValue == null ? "" : pValue);
	}

	private static void writeTargetField(final Session pSession, final Adaptation pFactory, final String pTargetId, final com.orchestranetworks.schema.Path pField,
		final String pValue) throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final Adaptation row = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
				if (row == null)
				{
					return;
				}
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(pValue, pField);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveTargetState", "Distribution target " + pTargetId + ": could not write back " + pField + ": " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pTargetId);
		}
	}

	private static final class HttpOutcome
	{
		final boolean ok;
		final int httpStatus;
		final String message;

		HttpOutcome(final boolean pOk, final int pHttpStatus, final String pMessage)
		{
			this.ok = pOk;
			this.httpStatus = pHttpStatus;
			this.message = pMessage;
		}
	}

	/** As the caller's own session (an {@code EBXUID} cookie - {@link SessionToken}), the agent account only as a {@link CredentialPolicy}-gated fallback. Never throws: every failure comes back as a non-ok {@link HttpOutcome}. */
	private static HttpOutcome call(final Session pSession, final Adaptation pFactory, final String pMethod, final String pPath, final String pJsonBody)
	{
		try
		{
			String authorizationHeader = null;
			final String cookie = SessionToken.cookie(pSession, pFactory.getHome().getRepository());
			if (cookie == null)
			{
				CredentialPolicy.checkFallback(pFactory, "ebx-dataservices", pSession == null ? "no session" : "no session token could be minted");
				final Adaptation settings = pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
				final String user = settings == null ? null : settings.getString(FactoryPaths.Settings.AGENT_USER);
				final String password = settings == null ? null : McpServers.secret(settings.getString(FactoryPaths.Settings.AGENT_PASSWORD));
				if (user == null || user.trim().isEmpty() || password == null || password.isEmpty())
				{
					return new HttpOutcome(false, 0, "MDM Factory settings: the agent account is not set");
				}
				authorizationHeader = "Basic "
					+ java.util.Base64.getEncoder().encodeToString((user.trim() + ":" + password).getBytes(StandardCharsets.UTF_8));
			}
			final String base = LocalEndpoint.resolve(pFactory).baseUrl;
			// the session token or the agent account goes only to this EBX node (or a listed internal host), never to any HTTPS address
			CredentialPolicy.requireOwnNode(base);
			final URL url = new URL(LocalEndpoint.trimSlashes(base) + pPath);
			final HttpURLConnection connection = (HttpURLConnection) url.openConnection();
			connection.setInstanceFollowRedirects(false); // never replay the credential to a redirect target
			connection.setRequestMethod(pMethod);
			connection.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
			if (cookie != null)
			{
				connection.setRequestProperty("Cookie", cookie);
			}
			else
			{
				connection.setRequestProperty("Authorization", authorizationHeader);
			}
			connection.setConnectTimeout(DistributionScheduler.CONNECT_TIMEOUT_MS);
			connection.setReadTimeout(DistributionScheduler.READ_TIMEOUT_MS);
			connection.setDoOutput(true);
			final byte[] payload = pJsonBody.getBytes(StandardCharsets.UTF_8);
			final OutputStream requestBody = connection.getOutputStream();
			try
			{
				requestBody.write(payload);
			}
			finally
			{
				requestBody.close();
			}
			final int status = connection.getResponseCode();
			final String responseText = DistributionScheduler.readFully(status >= 200 && status < 300 ? connection.getInputStream() : connection.getErrorStream());
			if (status < 200 || status >= 300)
			{
				return new HttpOutcome(false, status, TextLimits.truncate("HTTP " + status + " " + responseText, TextLimits.MESSAGE_MAX_LENGTH));
			}
			return new HttpOutcome(true, status, TextLimits.truncate(responseText, TextLimits.MESSAGE_MAX_LENGTH));
		}
		catch (final IOException ex)
		{
			return new HttpOutcome(false, 0, TextLimits.truncate("Could not reach ebx-dataservices: " + ex, TextLimits.MESSAGE_MAX_LENGTH));
		}
		catch (final OperationException ex)
		{
			return new HttpOutcome(false, 0, TextLimits.truncate(ex.getMessage(), TextLimits.MESSAGE_MAX_LENGTH));
		}
		catch (final RuntimeException ex)
		{
			return new HttpOutcome(false, 0, TextLimits.truncate("Unexpected failure: " + ex, TextLimits.MESSAGE_MAX_LENGTH));
		}
	}

	private static String readFully(final InputStream pStream) throws IOException
	{
		if (pStream == null)
		{
			return "";
		}
		try
		{
			final ByteArrayOutputStream buffer = new ByteArrayOutputStream();
			final byte[] chunk = new byte[4096];
			int read;
			while ((read = pStream.read(chunk)) >= 0)
			{
				buffer.write(chunk, 0, read);
			}
			return new String(buffer.toByteArray(), StandardCharsets.UTF_8);
		}
		finally
		{
			pStream.close();
		}
	}
}
