package com.on.ebx.factory.ebx;

import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.workflow.ProcessLauncher;
import com.orchestranetworks.workflow.PublishedProcessKey;
import com.orchestranetworks.workflow.WorkflowEngine;

/**
 * Wizard step 7 ("Summary") lot, 2026-09-24: launches the {@code MdmFactoryPipeline} workflow model
 * ({@code docs/TRANSFER-WORKFLOW.md}) for one project - the "Chain the loads" button's server side. Never
 * launched from Java before this lot (the model was only ever launched by hand through the workflow MCP
 * tools/REST API while building it, per that document's own "no write tool was invoked" discipline for
 * THAT lot) - this class is the first real caller.
 * <p>
 * Follows CLAUDE.md &sect;7's own measured rule ("Choosing a workflow publication from Java"): {@link
 * #isPublished} needs no {@link Session} ({@code WorkflowEngine.getPublishedKeys()}, usable from a plain
 * read path so the wizard can grey the button out with an honest reason when the model was never
 * published on this instance) while {@link #launch} needs one to get a {@link ProcessLauncher}, and sets
 * only the input parameters the published model actually declares ({@link ProcessLauncher#isDefinedAsInputParameter})
 * - a launch must never fail with "not declared in the process definition of data context" just because
 * this class assumes a fixed input shape. No permission check here on purpose, same discipline as {@code
 * workflow.MatchingRunTaskBean}'s own class Javadoc: the model's {@code permissionsOnInstances} (all
 * {@code Badministrator}, {@code docs/TRANSFER-WORKFLOW.md} &sect;2) is the real gate; {@code
 * ProjectWizardUserService} still re-checks {@code administrator} itself before calling {@link #launch} at
 * all, the same defence every other agent-account action in that class already applies, not because this
 * class needs it but because a forged request must be refused before the workflow engine is even asked.
 */
public final class PipelineWorkflowLauncher
{
	public static final String PUBLICATION_NAME = "MdmFactoryPipeline";
	/** Automatic-intake lot: the non-blocking review of the rows held back (tools/workflow/MdmFactoryRejectReview.json). */
	public static final String REVIEW_PUBLICATION_NAME = "MdmFactoryRejectReview";

	private PipelineWorkflowLauncher()
	{
	}

	/** No {@link Session} needed (CLAUDE.md &sect;7) - safe to call on every render to decide whether the button is enabled. */
	public static boolean isPublished(final Repository pRepository)
	{
		return PipelineWorkflowLauncher.isPublished(pRepository, PipelineWorkflowLauncher.PUBLICATION_NAME);
	}

	/** {@link #isPublished(Repository)} for any publication name. */
	public static boolean isPublished(final Repository pRepository, final String pPublicationName)
	{
		if (pRepository == null)
		{
			return false;
		}
		try
		{
			final WorkflowEngine engine = WorkflowEngine.getFromRepository(pRepository, null);
			for (final PublishedProcessKey key : engine.getPublishedKeys())
			{
				if (pPublicationName.equals(key.getName()) && !key.isDisabled())
				{
					return true;
				}
			}
		}
		catch (final RuntimeException ex)
		{
			return false; // never let a lookup failure surface as a 500 on a page render - the button just stays disabled
		}
		return false;
	}

	/**
	 * Launches one instance for {@code pProjectCode}, with {@code loadLanding=true}, {@code
	 * reviewRejects=true}, {@code scope=incremental} ({@code docs/TRANSFER-WORKFLOW.md} &sect;2, the same
	 * inputs instance 562 was launched with) - every one set only if the published model actually declares
	 * it as an input, per this class's own Javadoc.
	 */
	public static String launch(final Repository pRepository, final Session pSession, final String pProjectCode) throws OperationException
	{
		return PipelineWorkflowLauncher.launch(pRepository, pSession, pProjectCode, null);
	}

	/**
	 * Same, for a launch made by a background job (F2-102): the job's session is the system user, so the instance's creator - whose right the pipeline's own gate checks
	 * ({@code workflow.PipelineLauncher}) - is set to the person who asked ({@code ProcessLauncher.setCreator}). Null keeps the session's own user.
	 */
	public static String launch(final Repository pRepository, final Session pSession, final String pProjectCode, final com.orchestranetworks.service.UserReference pCreator)
		throws OperationException
	{
		final WorkflowEngine engine = WorkflowEngine.getFromRepository(pRepository, pSession);
		final ProcessLauncher launcher = engine.getProcessLauncher(PublishedProcessKey.forName(PipelineWorkflowLauncher.PUBLICATION_NAME));
		if (pCreator != null)
		{
			launcher.setCreator(pCreator);
		}
		PipelineWorkflowLauncher.setIfDeclared(launcher, "projectCode", pProjectCode);
		PipelineWorkflowLauncher.setIfDeclared(launcher, "loadLanding", "true");
		PipelineWorkflowLauncher.setIfDeclared(launcher, "reviewRejects", "true");
		PipelineWorkflowLauncher.setIfDeclared(launcher, "scope", "incremental");
		return WorkflowLaunches.launch(launcher).toString();
	}

	/**
	 * Automatic-intake lot: true when the PUBLISHED pipeline declares the {@code intakeChain} input ({@link ProcessLauncher#isDefinedAsInputParameter}, javap-checked) - an
	 * older publication does not, and an intake chain launched on it would run without its token and be refused by the gate. Needs a session ({@code getProcessLauncher}).
	 * False when the publication is missing or the lookup fails. <b>Not run live yet.</b>
	 */
	public static boolean declaresIntake(final Repository pRepository, final Session pSession)
	{
		try
		{
			final WorkflowEngine engine = WorkflowEngine.getFromRepository(pRepository, pSession);
			return engine.getProcessLauncher(PublishedProcessKey.forName(PipelineWorkflowLauncher.PUBLICATION_NAME)).isDefinedAsInputParameter("intakeChain");
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	/**
	 * Automatic-intake lot: the inputs of an intake chain, in order - an incremental run that reads no source ({@code loadLanding=false}), transfers only the rows at
	 * NEW ({@code stagingScope=new}, {@code scope=incremental}), never waits on a person ({@code reviewRejects=false}: rows held back are reviewed by a separate,
	 * non-blocking workflow), carries its token ({@code intakeChain}) and, when the project sets one, the stop threshold ({@code maxRejectPercent}). Pure.
	 */
	public static java.util.Map<String, String> intakeInputs(final String pProjectCode, final String pChainId, final Integer pMaxRejectPercent)
	{
		final java.util.Map<String, String> inputs = new java.util.LinkedHashMap<String, String>();
		inputs.put("projectCode", pProjectCode);
		inputs.put("loadLanding", "false");
		inputs.put("reviewRejects", "false");
		inputs.put("stagingScope", "new");
		inputs.put("scope", "incremental");
		inputs.put("intakeChain", pChainId);
		if (pMaxRejectPercent != null)
		{
			inputs.put("maxRejectPercent", String.valueOf(pMaxRejectPercent.intValue()));
		}
		return inputs;
	}

	/**
	 * Launches one intake chain from the CALLER'S session (the engine's system-user session; the pattern {@code data-catalog TagReviews} proved from a system context):
	 * {@link #intakeInputs}, each one set only if the published model declares it. The instance key is returned.
	 */
	public static String launchIntake(final Repository pRepository, final Session pSession, final String pProjectCode, final String pChainId, final Integer pMaxRejectPercent)
		throws OperationException
	{
		final WorkflowEngine engine = WorkflowEngine.getFromRepository(pRepository, pSession);
		final ProcessLauncher launcher = engine.getProcessLauncher(PublishedProcessKey.forName(PipelineWorkflowLauncher.PUBLICATION_NAME));
		for (final java.util.Map.Entry<String, String> input : PipelineWorkflowLauncher.intakeInputs(pProjectCode, pChainId, pMaxRejectPercent).entrySet())
		{
			PipelineWorkflowLauncher.setIfDeclared(launcher, input.getKey(), input.getValue());
		}
		launcher.setLabel(com.onwbp.base.text.UserMessage.createInfo("Automatic intake: " + pProjectCode));
		return WorkflowLaunches.launch(launcher).format();
	}

	/** Launches the non-blocking review of the rows held back for a project; the instance key is returned. */
	public static String launchRejectReview(final Repository pRepository, final Session pSession, final String pProjectCode) throws OperationException
	{
		final WorkflowEngine engine = WorkflowEngine.getFromRepository(pRepository, pSession);
		final ProcessLauncher launcher = engine.getProcessLauncher(PublishedProcessKey.forName(PipelineWorkflowLauncher.REVIEW_PUBLICATION_NAME));
		PipelineWorkflowLauncher.setIfDeclared(launcher, "projectCode", pProjectCode);
		launcher.setLabel(com.onwbp.base.text.UserMessage.createInfo("Rows held back: " + pProjectCode));
		return WorkflowLaunches.launch(launcher).format();
	}

	private static void setIfDeclared(final ProcessLauncher pLauncher, final String pName, final String pValue)
	{
		if (pLauncher.isDefinedAsInputParameter(pName))
		{
			pLauncher.setInputParameter(pName, pValue);
		}
	}
}
