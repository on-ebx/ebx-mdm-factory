package com.on.ebx.factory.intake;

/**
 * Automatic intake lot: the pure rule of the USER actions that end the intake. Switching OFF removes the landing triggers and forgets the project while a chain would
 * still be running master and matching steps - those steps' gate ({@code workflow.IntakeGate}) needs the intake ON or PAUSED and the enabler recorded - so a user switch-off
 * is refused while the project has a RUNNING chain. The refusal can never lock anyone out: an instance stuck in error is closed {@code FAILED} by the engine ({@code
 * IntakeEngine.followChain}: an instance in error ends the chain), and a pause (always allowed) stops new chains meanwhile. The forced OFF of a project deletion or a step reset
 * ({@code Intake.forceOff}) is unconditional: the objects the chain works on are going away.
 */
public final class IntakeSwitchRules
{
	private IntakeSwitchRules()
	{
	}

	/** The refusal sentence for a user switch-off, or null when it is allowed. */
	public static String refuseSwitchOff(final boolean pChainRunning)
	{
		return pChainRunning ? "A chain is running: pause the intake, wait for it to end, then switch it off." : null;
	}
}
