package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.RecordLineageReader;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;

/**
 * "Lineage de l'enregistrement" (PLAN.md lot 4, mockup screen 5): a small header form (project &rarr;
 * entity &rarr; formatted master key), posted back with the same plain-HTML technique every other screen of
 * this module uses (one real hidden submit button, everything else a {@code type="button"} - CLAUDE.md
 * &sect;9), then the record's journey/field table/merge state rendered by the PURE {@link
 * com.on.ebx.factory.lineage.RecordLineagePage} from whatever {@link RecordLineageReader#read} answers.
 * <p>
 * Everything past the initial project/entity/key resolution below - {@code setupDisplay}, the posted-back
 * form, the lookup, the rendering - lives in {@link AbstractRecordLineageUserService}, shared verbatim with
 * {@link RecordLineageOnRecordUserService} (coordinator review, 2026-09-22, "Record lineage" record-level
 * action lot): see that class's own Javadoc.
 * <p>
 * <b>Three ways in</b>: (1) a deep link with {@code project}/{@code entity}/{@code key} launch parameters
 * (see {@link RecordLineageServiceDeclaration}'s own Javadoc for the exact URL form and why it reaches
 * {@link #setupObjectContext} at all) looks the record up immediately; (2) a toolbar action on a selected
 * {@code Project} record pre-fills the project, same pattern as {@link ProposalReviewUserService}; (3) the
 * perspective action opens with nothing selected, the project selector shown first (like {@link
 * ProposalReviewUserService}/{@link ProjectWizardUserService}).
 */
public final class RecordLineageUserService extends AbstractRecordLineageUserService<TableViewEntitySelection>
{
	// ------------------------------------------------------------------ setup (selection, and the deep-link launch)

	/** Second permissions pass (2026-10-14): the table the screen was opened on, where its service permission is read. */
	private com.onwbp.adaptation.AdaptationTable openedOn;

	@Override
	boolean serviceEnabled(final com.orchestranetworks.service.Session pSession)
	{
		return ProjectAccess.serviceOnTable(pSession, RecordLineageServiceDeclaration.SERVICE_KEY, this.openedOn);
	}

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		this.factory = pContext.getEntitySelection().getDataset();
		this.openedOn = pContext.getEntitySelection().getTable();
		if (this.projectCode != null)
		{
			return; // already initialised on an earlier round trip of this same service instance
		}
		final String urlProject = AbstractRecordLineageUserService.param(pContext, "project");
		if (!urlProject.isEmpty())
		{
			// (1) the documented deep link - RecordLineageServiceDeclaration's own Javadoc has the exact form
			this.projectCode = urlProject;
			final String urlEntity = AbstractRecordLineageUserService.param(pContext, "entity");
			this.entityName = urlEntity.isEmpty() ? null : urlEntity;
			this.keyText = AbstractRecordLineageUserService.param(pContext, "key");
			if (this.entityName != null && !this.keyText.trim().isEmpty())
			{
				this.lookup(pContext.getSession());
			}
			return;
		}
		// (2) a toolbar action on a selected Project record - same resolution shape as ProposalReviewUserService's
		// own setupObjectContext, simplified: this screen only ever needs the project, never a cascade to a proposal's own owning project
		try
		{
			final TableViewEntitySelection selection = pContext.getEntitySelection();
			final RequestResult rows = selection.getSelectedRecords().execute();
			try
			{
				final Adaptation selected = rows.nextAdaptation();
				if (selected != null && rows.nextAdaptation() == null && FactoryPaths.PROJECT.equals(selection.getTable().getTablePath()))
				{
					// second permissions pass (2026-10-14): the key (Project's key IS its code), only for a project the user may see
					this.projectCode = ProjectAccess.projectOpens(pContext.getSession(), this.factory, selected) ? selected.getOccurrencePrimaryKey().format() : null;
				}
			}
			finally
			{
				rows.close();
			}
		}
		catch (final RuntimeException ex)
		{
			// no selection context (a plain perspective launch): fall through to the project selector, (3)
		}
	}
}
