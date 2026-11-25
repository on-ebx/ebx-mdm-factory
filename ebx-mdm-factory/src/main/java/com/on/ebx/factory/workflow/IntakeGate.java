package com.on.ebx.factory.workflow;

/**
 * Automatic intake lot (docs/AUTOMATIC-INTAKE.md): the ONE narrow extra path through the gate of {@link PipelineLauncher}. Pure: the decision from plain facts the
 * reader ({@code PipelineLauncher#intakeActor}) collects.
 * <p>
 * <b>Why a path at all.</b> {@link PipelineLauncher} requires the instance CREATOR to hold the pipeline role. An intake chain is launched by the engine's system-user
 * session ({@code ebx.IntakeEngine}), so there is no person to check. Instead the chain carries a token in the data-context variable {@code intakeChain}: the primary key
 * (a random UUID) of its {@code IntakeChain} row. The work then runs as the person who SWITCHED THE INTAKE ON ({@code Project.intakeEnabledBy}), provided that person still
 * holds the role NOW - so revoking the role of whoever enabled it stops the automation at its next step, and every {@code PipelineStep} still says who it is done for
 * ({@code intake <chain id> - workflow <instance key>} in {@code via}).
 * <p>
 * <b>ON or PAUSED, not only ON.</b> {@code Intake.pause} is allowed at any time, also while a chain runs: its staging step may have passed and the master and matching
 * steps are then still to run. A pause means "no NEW chain; the running one finishes" - denying PAUSED would send those steps to the creator check (a system user) and end
 * the instance in error halfway for a plain pause. The chain row being {@code RUNNING} is the proof the chain was launched legitimately while the intake was ON (a launch
 * only happens in state ON); {@code OFF} denies. A user switch-off is refused while a chain runs ({@code intake.IntakeSwitchRules}), so OFF with a RUNNING chain only happens
 * through the forced OFF of a project deletion or a step reset, where the objects the chain works on are going away anyway.
 * <p>
 * <b>Who could forge it, and why that grants nothing new.</b> The token is only read from the instance's own data context, which is set when the instance is LAUNCHED;
 * launching {@code MdmFactoryPipeline} needs its {@code permissionsOnInstances.create} (administrators). An administrator launching with a made-up token gets the
 * fall-through (the creator check, unchanged). With a REAL token (a UUID that exists only in {@code IntakeChain}, readable where that table is) the only effect is that the
 * project's own incremental pipeline runs as its enabler - work that enabler's intake was already doing - and only while the chain row is {@code RUNNING}, belongs to the
 * project named by the bean's own {@code projectCode}, and the project's intake is {@code ON}. Residual risk, named: if the publication's {@code create} permission is ever
 * widened to people who can also read {@code IntakeChain}, such a person could run the incremental pipeline of an intake-enabled project (never anything else) while a chain
 * is running; keep the table's read access to administrators.
 */
final class IntakeGate
{
	private IntakeGate()
	{
	}

	/** The verdict: {@link #allowed} with the user to act as, or not allowed with a reason (for the log only; the caller then falls through to the creator check). */
	static final class Decision
	{
		final boolean allowed;
		final String actAs;
		final String reason;

		private Decision(final boolean pAllowed, final String pActAs, final String pReason)
		{
			this.allowed = pAllowed;
			this.actAs = pActAs;
			this.reason = pReason;
		}

		static Decision allow(final String pActAs)
		{
			return new Decision(true, pActAs, null);
		}

		static Decision deny(final String pReason)
		{
			return new Decision(false, null, pReason);
		}
	}

	/**
	 * @param pVariableDefined the data context declares {@code intakeChain} (an old publication does not: reading it would throw)
	 * @param pToken its value
	 * @param pChainFound an {@code IntakeChain} row with that primary key exists
	 * @param pChainStatus its {@code status}
	 * @param pChainProjectCode the code of the project the row belongs to
	 * @param pBeanProjectCode the bean's own {@code projectCode} property
	 * @param pIntakeState the project's {@code intakeState}
	 * @param pEnabledBy the project's {@code intakeEnabledBy}
	 * @param pEnabledByHoldsRole that user holds the pipeline role NOW
	 */
	static Decision decide(final boolean pVariableDefined, final String pToken, final boolean pChainFound, final String pChainStatus, final String pChainProjectCode,
		final String pBeanProjectCode, final String pIntakeState, final String pEnabledBy, final boolean pEnabledByHoldsRole)
	{
		if (!pVariableDefined || pToken == null || pToken.trim().isEmpty())
		{
			return Decision.deny("no intake token");
		}
		if (!pChainFound)
		{
			return Decision.deny("no such intake chain");
		}
		if (!"RUNNING".equals(pChainStatus))
		{
			return Decision.deny("the intake chain is not running");
		}
		if (pBeanProjectCode == null || pChainProjectCode == null || !pBeanProjectCode.trim().equals(pChainProjectCode))
		{
			return Decision.deny("the intake chain belongs to another project");
		}
		// ON or PAUSED: a pause means "no NEW chain", the running one finishes. The RUNNING row is what proves the chain was launched while the intake was ON;
		// only OFF (or anything else, an empty state included) denies.
		if (!"ON".equals(pIntakeState) && !"PAUSED".equals(pIntakeState))
		{
			return Decision.deny("the intake of the project is not ON or PAUSED");
		}
		if (pEnabledBy == null || pEnabledBy.trim().isEmpty())
		{
			return Decision.deny("nobody is recorded as having switched the intake on");
		}
		if (!pEnabledByHoldsRole)
		{
			return Decision.deny(pEnabledBy + ", who switched the intake on, no longer holds the pipeline role");
		}
		return Decision.allow(pEnabledBy.trim());
	}
}
