package com.on.ebx.factory.ebx;

import java.util.ArrayList;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.review.MatchPlanView;

/**
 * JUnit-free: run the main, a failure throws. Lot F1b (2026-10-19): the boundary of the feedback phase. Live (scenario run 7) a date key made Calcite's generated code fail with an
 * {@code ExceptionInInitializerError} - an Error, not an exception - and the whole proposal died with it. Now any Throwable but a virtual machine error is recorded in {@code feedback.error} (the phase)
 * or in the entity's {@code reason} (one simulation), logged, and the proposal goes on with the plans it already wrote.
 */
public final class MatchPlanFeedbackBoundaryTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs)
	{
		MatchPlanFeedbackBoundaryTest.anErrorOfThePhaseLeavesTheProposalIntact();
		MatchPlanFeedbackBoundaryTest.theLiveFailureIsRecordedWithItsCause();
		MatchPlanFeedbackBoundaryTest.aVirtualMachineErrorIsNeverSwallowed();
		MatchPlanFeedbackBoundaryTest.anErrorOfOneSimulationSkipsThatEntityOnly();
		MatchPlanFeedbackBoundaryTest.aSimulationThatJudgesNoPairIsSaidSo();
		MatchPlanFeedbackBoundaryTest.theWholePhaseWithoutASessionNeverThrows();
		System.out.println("MatchPlanFeedbackBoundaryTest OK");
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static MatchPlanFeedbackPass.Context context(final MatchPlanFeedbackPass.Simulator pSimulator)
	{
		final MatchPlanFeedbackPass.Context context = new MatchPlanFeedbackPass.Context();
		context.projectCode = "P";
		context.simulator = pSimulator;
		return context;
	}

	/** An Error thrown inside the phase: nothing escapes, {@code feedback.error} says what, and what the proposal had already recorded is still there. */
	private static void anErrorOfThePhaseLeavesTheProposalIntact()
	{
		final ObjectNode out = MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode();
		out.put("project", "P");
		out.putArray("written").add("Client");
		final ObjectNode node = out.putObject("feedback");
		MatchPlanFeedbackPass.guarded(MatchPlanFeedbackBoundaryTest.context(null), node, new MatchPlanFeedbackPass.Phase()
		{
			@Override
			public void run()
			{
				throw new NoClassDefFoundError("com/google/Baz");
			}
		});
		check("feedback.error is set: " + out, node.path("error").asText().contains("NoClassDefFoundError") || node.path("error").asText().contains("com/google/Baz"));
		check("the proposal's own record is intact", "P".equals(out.path("project").asText()) && "Client".equals(out.path("written").get(0).asText()));
	}

	/** An ExceptionInInitializerError has no message of its own: the cause is what the step detail must carry. */
	private static void theLiveFailureIsRecordedWithItsCause()
	{
		final ObjectNode node = MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode();
		MatchPlanFeedbackPass.guarded(MatchPlanFeedbackBoundaryTest.context(null), node, new MatchPlanFeedbackPass.Phase()
		{
			@Override
			public void run()
			{
				throw MatchPlanFeedbackBoundaryTest.live();
			}
		});
		check("the cause's message is kept: " + node.path("error").asText(), node.path("error").asText().contains("ExceptionInInitializerError")
			&& node.path("error").asText().contains("Invalid DATE value, 'Tue Jan 20 00:00:00 CET 2026'"));
	}

	private static ExceptionInInitializerError live()
	{
		return new ExceptionInInitializerError(new IllegalArgumentException("Invalid DATE value, 'Tue Jan 20 00:00:00 CET 2026'"));
	}

	private static void aVirtualMachineErrorIsNeverSwallowed()
	{
		boolean rethrown = false;
		try
		{
			MatchPlanFeedbackPass.guarded(MatchPlanFeedbackBoundaryTest.context(null), MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode(), new MatchPlanFeedbackPass.Phase()
			{
				@Override
				public void run()
				{
					throw new OutOfMemoryError("heap");
				}
			});
		}
		catch (final OutOfMemoryError ex)
		{
			rethrown = true;
		}
		check("OutOfMemoryError goes through the phase boundary", rethrown);
		rethrown = false;
		try
		{
			MatchPlanFeedbackPass.simulateGuarded(MatchPlanFeedbackBoundaryTest.context(new MatchPlanFeedbackPass.Simulator()
			{
				@Override
				public MatchPlanSimulationRunner.Run simulate(final MatchPlanFeedbackPass.Context pContext, final String pEntity, final String pRequirements, final String pRow)
				{
					throw new StackOverflowError();
				}
			}), "Client", "{}", "1", MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode());
		}
		catch (final StackOverflowError ex)
		{
			rethrown = true;
		}
		check("StackOverflowError goes through the simulation boundary", rethrown);
	}

	/** The simulation of one entity throws the live Error: that entity says why and is skipped; the call returns, the next entity can still be simulated. */
	private static void anErrorOfOneSimulationSkipsThatEntityOnly()
	{
		final ObjectNode clientNode = MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode();
		final MatchPlanFeedbackPass.Context context = MatchPlanFeedbackBoundaryTest.context(new MatchPlanFeedbackPass.Simulator()
		{
			@Override
			public MatchPlanSimulationRunner.Run simulate(final MatchPlanFeedbackPass.Context pContext, final String pEntity, final String pRequirements, final String pRow)
			{
				if ("Client".equals(pEntity))
				{
					throw MatchPlanFeedbackBoundaryTest.live();
				}
				return new MatchPlanSimulationRunner.Run(new MatchPlanView.SimulationResult(new ArrayList<MatchPlanView.SimulationPair>(), 20), null, new ArrayList<String>());
			}
		});
		check("no Run for the entity that threw", MatchPlanFeedbackPass.simulateGuarded(context, "Client", "{}", "1", clientNode) == null);
		check("ran=false and the reason, with the cause: " + clientNode, !clientNode.path("ran").asBoolean(true) && clientNode.path("reason").asText().startsWith("the simulation failed: ")
			&& clientNode.path("reason").asText().contains("Invalid DATE value"));
		final ObjectNode other = MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode();
		check("the next entity is simulated by the same seam (here: it judged no pair)", MatchPlanFeedbackPass.simulateGuarded(context, "Adresse", "{}", "2", other) == null
			&& other.path("reason").asText().startsWith("the simulation judged no pair"));
	}

	private static void aSimulationThatJudgesNoPairIsSaidSo()
	{
		final ObjectNode node = MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode();
		check("null run", MatchPlanFeedbackPass.simulateGuarded(MatchPlanFeedbackBoundaryTest.context(new MatchPlanFeedbackPass.Simulator()
		{
			@Override
			public MatchPlanSimulationRunner.Run simulate(final MatchPlanFeedbackPass.Context pContext, final String pEntity, final String pRequirements, final String pRow)
			{
				return null;
			}
		}), "Client", "{}", "1", node) == null && node.path("reason").asText().equals("the simulation judged no pair"));
	}

	/** {@code run} itself, with nothing to run on (no session of the pass): the NullPointerException is recorded, never thrown, and the rest of the proposal's record is untouched. */
	private static void theWholePhaseWithoutASessionNeverThrows()
	{
		final ObjectNode out = MatchPlanFeedbackBoundaryTest.MAPPER.createObjectNode();
		out.put("ok", true);
		MatchPlanFeedbackPass.run(MatchPlanFeedbackBoundaryTest.context(null), out);
		check("recorded: " + out, out.path("feedback").has("error") && out.path("ok").asBoolean());
	}
}
