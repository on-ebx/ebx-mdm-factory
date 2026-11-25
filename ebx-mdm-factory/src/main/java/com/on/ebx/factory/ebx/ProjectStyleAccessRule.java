package com.on.ebx.factory.ebx;

import com.on.ebx.factory.ebx.ui.Messages;
import com.on.ebx.factory.wizard.WizardRules;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.AccessRule;
import com.orchestranetworks.service.AccessRuleForCreate;
import com.orchestranetworks.service.AccessRuleForCreateContext;
import com.orchestranetworks.service.Session;

/**
 * 1.4.2 (F3-13, gap 11 of the 1.4.1 review): the style of a project, closed on EVERY permission-checked path, creation included. Until 1.4.1 only the modification was
 * closed ({@link ModuleOnlyFieldRule}); a holder of the DESIGN right could still create a {@code Project} row of the style CENTRALISATION ("coming soon") by Data Services
 * or by the table's form, because the field had no rule at creation.
 * <ul>
 * <li><b>Modification</b>: read only to every person, a project keeps the style it was created with ({@link #FIXED_CODE}); the platform and the system user write.</li>
 * <li><b>Creation</b>: read only too ({@link #CLOSED_CODE}), so a row created by hand or by Data Services carries the default (CONSOLIDATION, the only open style), and a
 * request that names a style is refused with the coded bilingual message. The ONE way to set it is the wizard: {@code WizardData.saveProject} holds a {@link Ticket} on
 * the calling thread while its procedure runs, and only for a style that {@link WizardRules#styleSelectable is open}. An access rule cannot see the value being written,
 * hence the ticket; it is a thread-local the wizard sets itself, so no request can forge it. A restore of an archive (administrator, privileges disabled) does not meet
 * this rule and checks the archive's style itself ({@code ProjectRestore}).</li>
 * </ul>
 * A Centralisation project that already exists stays readable, deletable, and keeps working: nothing here touches its other fields.
 */
public final class ProjectStyleAccessRule implements AccessRule, AccessRuleForCreate
{
	/** Code of the refusal at creation (bundle key {@code access.style.closed}). */
	public static final String CLOSED_CODE = "MDMF-STYLE-1";

	/** Code of the refusal at modification (bundle key {@code access.style.fixed}). */
	public static final String FIXED_CODE = "MDMF-STYLE-2";

	private static final ThreadLocal<String> TICKET = new ThreadLocal<String>();

	/** Held by the wizard around the creation of a project; closing it ends the permission. */
	public static final class Ticket implements AutoCloseable
	{
		private final String previous;

		Ticket(final String pPrevious)
		{
			this.previous = pPrevious;
		}

		@Override
		public void close()
		{
			if (this.previous == null)
			{
				ProjectStyleAccessRule.TICKET.remove();
			}
			else
			{
				ProjectStyleAccessRule.TICKET.set(this.previous);
			}
		}
	}

	/** Permission to write the style {@code pStyle} on this thread until the ticket is closed; refused (IllegalArgumentException) for a style that is not open. */
	static Ticket open(final String pStyle)
	{
		if (!WizardRules.styleSelectable(pStyle))
		{
			throw new IllegalArgumentException(ProjectStyleAccessRule.CLOSED_CODE + ": the style '" + pStyle + "' is not open");
		}
		final String previous = ProjectStyleAccessRule.TICKET.get();
		ProjectStyleAccessRule.TICKET.set(pStyle);
		return new Ticket(previous);
	}

	/** Pure: who writes the style of an existing project through a permission-checked path. */
	static boolean writableOnModify(final boolean pPlatform, final boolean pSystemUser)
	{
		return ModuleOnlyFieldRule.writable(pPlatform, pSystemUser);
	}

	/** Pure: who writes the style of a new project through a permission-checked path: the platform, the system user, or the wizard holding a ticket for an open style. */
	static boolean writableOnCreate(final boolean pPlatform, final boolean pSystemUser, final String pTicketStyle)
	{
		return pPlatform || pSystemUser || pTicketStyle != null && WizardRules.styleSelectable(pTicketStyle);
	}

	@Override
	public AccessPermission getPermission(final Adaptation pRecord, final Session pSession, final SchemaNode pNode)
	{
		if (ProjectStyleAccessRule.writableOnModify(pSession == null, pSession != null && pSession.isSystemUser()))
		{
			return AccessPermission.getReadWrite();
		}
		return AccessPermission.getReadOnly(ProjectStyleAccessRule.message("access.style.fixed"));
	}

	@Override
	public AccessPermission getPermission(final AccessRuleForCreateContext pContext)
	{
		final Session session = pContext.getSession();
		if (ProjectStyleAccessRule.writableOnCreate(session == null, session != null && session.isSystemUser(), ProjectStyleAccessRule.TICKET.get()))
		{
			return AccessPermission.getReadWrite();
		}
		return AccessPermission.getReadOnly(ProjectStyleAccessRule.message("access.style.closed"));
	}

	private static UserMessage message(final String pKey)
	{
		return Messages.getInfo(com.on.ebx.factory.ebx.ui.AboutServiceDeclaration.class, pKey);
	}
}
