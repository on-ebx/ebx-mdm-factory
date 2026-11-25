package com.on.ebx.factory.delivery;

/**
 * Whether a new project may be created on this instance. {@code FactorySettings.role} is {@code DEVELOPMENT}
 * (default, every instance until this lot) or {@code TARGET} - a delivery destination that only ever receives
 * projects by EBX Staging import ({@code docs/DELIVERY.md} sec 6.2: Factory ids are auto-increment, so a
 * target that also created projects locally could collide with an imported one's id). Pure, same shape as
 * {@link OriginRule}: {@code ebx.ProjectOrigin} reads the EBX row, {@code WizardData.saveProject} enforces it
 * on creation, {@code ProjectWizardUserService} shows the refusal instead of the "new project" form when the
 * wizard is reached with no project selected.
 */
public final class TargetRule
{
	private TargetRule()
	{
	}

	public static final String ROLE_TARGET = "TARGET";
	public static final String ROLE_DEVELOPMENT = "DEVELOPMENT";

	/** False only when {@code pRole} is {@code TARGET}; null, blank or {@code DEVELOPMENT} all allow creation (an unstamped settings row - older, or written before this field existed - counts as DEVELOPMENT). */
	public static boolean projectCreationAllowed(final String pRole)
	{
		return !TargetRule.ROLE_TARGET.equals(pRole == null ? null : pRole.trim());
	}

	public static String refusal()
	{
		return "This instance is a delivery target: projects are designed on the development instance and arrive by EBX Staging.";
	}
}
