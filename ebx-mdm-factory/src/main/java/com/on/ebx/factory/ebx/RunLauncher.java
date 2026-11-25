package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * The one place where the wizard's {@code ...Run.start} of F2-102 ({@link IntakeSwitchRun}, {@link WizardActionRuns}) turns its parameters into a row of {@link FactoryJobQueue}: the
 * project is looked up by code, the job queued, and the answer ({@code started}, {@code jobId}, {@code merged}, {@code position}) returned. A test swaps in a fake launcher
 * ({@link #use}) to prove that an action only queues and hands the request back at once.
 */
final class RunLauncher
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** Queues one job for the project of that code. */
	interface Launcher
	{
		ObjectNode launch(Session pSession, Adaptation pFactory, String pProjectCode, String pKind, ObjectNode pParams) throws OperationException;
	}

	private static final Launcher REAL = new Launcher()
	{
		@Override
		public ObjectNode launch(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams) throws OperationException
		{
			final Adaptation project = RunLauncher.findProject(pFactory, pProjectCode);
			if (project == null)
			{
				throw EngineRefusal.projectNotFound(pProjectCode);
			}
			final FactoryJobQueue.Queued queued = FactoryJobQueue.enqueue(pSession, pFactory, project.getOccurrencePrimaryKey().format(), pKind, pParams);
			final ObjectNode out = RunLauncher.MAPPER.createObjectNode();
			out.put("started", true);
			out.put("jobId", queued.jobId);
			out.put("merged", queued.merged);
			out.put("position", queued.position);
			return out;
		}
	};

	private static volatile Launcher current = RunLauncher.REAL;

	private RunLauncher()
	{
	}

	static ObjectNode launch(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pKind, final ObjectNode pParams) throws OperationException
	{
		return RunLauncher.current.launch(pSession, pFactory, pProjectCode, pKind, pParams);
	}

	/** For a test: every launch goes to {@code pLauncher} until {@link #reset}. */
	static void use(final Launcher pLauncher)
	{
		RunLauncher.current = pLauncher;
	}

	static void reset()
	{
		RunLauncher.current = RunLauncher.REAL;
	}

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}
}
