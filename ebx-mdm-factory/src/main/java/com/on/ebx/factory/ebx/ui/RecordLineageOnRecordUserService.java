package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.ebx.ProposalReviews;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;

/**
 * The record-level "Record lineage" action, offered directly on a master record by {@link
 * RecordLineageOnRecordServiceDeclaration} (module-level, see its own Javadoc for the mechanism and the
 * activation rule that keeps it off every table outside a project's own promoted master zone).
 * <p>
 * <b>Derives project/entity/key from the record being viewed, then reuses the exact same read+render as
 * the original screen</b> (task brief: "render the same page through the same reader ... no second read
 * path"): everything past {@link #setupObjectContext} below - the lookup itself, the page, the posted-back
 * project/entity/key form that still lets a steward look up a DIFFERENT record without leaving the screen -
 * lives in {@link AbstractRecordLineageUserService}, shared verbatim with {@link RecordLineageUserService}.
 * <p>
 * <b>{@code this.factory} is NEVER {@code pContext.getEntitySelection().getDataset()}</b> here, unlike the
 * {@code OnTableView} screen (where the service is declared ON the {@code Project} table of the factory's
 * own {@code MdmFactory} dataset, so the selection's dataset genuinely IS the factory dataset). This action
 * is declared on a MASTER table (e.g. {@code Companies_Master}/{@code Master}/{@code /root/Company}) - the
 * selection's dataset is the MASTER dataset, so the factory's own {@code MdmFactory} dataset is looked up
 * separately, by {@link HomeKey}/{@link AdaptationName}, the same resolution {@code
 * com.on.ebx.factory.ebx.MasterZoneIndex#compute} already uses (with a system session there; here, with the
 * viewer's own {@link com.orchestranetworks.service.Session} - reading the factory's own {@code Project}
 * table for its {@code masterDataspace}/{@code masterDataset}/{@code code} fields only, not a business
 * record).
 * <p>
 * <b>Project/entity are resolved from data, never guessed from the URL</b>: the project is the one whose
 * OWN {@code masterDataspace}/{@code masterDataset} equal the record's dataspace/dataset (the exact pair
 * {@code MasterZoneIndex} already used to decide this action should be offered at all); the entity name is
 * the inverse of {@code MasterModel.tablePath(entityName)} ({@code "/root/" + entityName}, so {@code
 * "/root/Company"} -&gt; {@code "Company"}). If either resolution fails - a race between the permission
 * rule's cache and a master zone reset/regeneration mid-session, see {@code MasterZoneIndex}'s own Javadoc -
 * this degrades to a plain notice, never a stack trace, exactly like {@link
 * com.on.ebx.factory.ebx.RecordLineageReader#read}'s own failures do for the original screen.
 */
public final class RecordLineageOnRecordUserService extends AbstractRecordLineageUserService<RecordEntitySelection>
{
	/** Always record-bound (coordinator review, 2026-09-23): project/entity/key are resolved from the record being viewed, below - see {@code AbstractRecordLineageUserService#isRecordBound}'s own Javadoc for what this hides and what it does not. */
	@Override
	boolean isRecordBound()
	{
		return true;
	}

	/** Second permissions pass (2026-10-14): the master record the action was opened on, where its service permission is read. */
	private Adaptation openedOn;

	@Override
	boolean serviceEnabled(final com.orchestranetworks.service.Session pSession)
	{
		return this.openedOn == null || ProjectAccess.serviceOnRecord(pSession, this.serviceKey(), this.openedOn);
	}

	private com.orchestranetworks.service.ServiceKey serviceKey()
	{
		final com.orchestranetworks.service.ServiceKey registered = RecordLineageOnRecordServiceDeclaration.registered;
		return registered != null ? registered
			: com.orchestranetworks.service.ServiceKey.forModuleServiceName(com.on.ebx.factory.core.FactoryPaths.MODULE_NAME, RecordLineageOnRecordServiceDeclaration.SERVICE_NAME);
	}

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<RecordEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		if (this.projectCode != null)
		{
			return; // already initialised on an earlier round trip of this same service instance
		}
		this.locale = pContext.getSession().getLocale();
		final RecordEntitySelection selection = pContext.getEntitySelection();
		final AdaptationHome masterHome = selection.getDataspace();
		final Adaptation masterDataset = selection.getDataset();
		final AdaptationTable masterTable = selection.getTable();
		final Adaptation masterRecord = selection.getRecord();
		this.openedOn = masterRecord;
		if (masterHome == null || masterDataset == null || masterTable == null || masterTable.getTablePath() == null || masterRecord == null)
		{
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.unexpected"));
			return;
		}
		this.factory = RecordLineageOnRecordUserService.findFactory(masterHome.getRepository());
		if (this.factory == null)
		{
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.unexpected"));
			return;
		}
		final String masterDataspaceKey = masterHome.getKey().getName();
		// AdaptationName.getStringName(), never .toString() - see RecordLineageOnRecordPermissionRule's own
		// Javadoc for the javap -c evidence (coordinator review, 2026-09-22, "fact 2"): .toString() is a debug
		// representation ("<ShortClassName>[...]@<hex hashcode>"), never the plain dataset name this method
		// compares against Project.masterDataset for. Same bug, same fix, found in the same sweep.
		final String masterDatasetName = masterDataset.getAdaptationName().getStringName();
		final String tablePath = masterTable.getTablePath().format();
		// second permissions pass (2026-10-14): among the projects the user may see, with the fields the user may see
		this.session = pContext.getSession();
		final Adaptation factoryDataset = this.factory;
		final String projectCode = this.asUser(() -> RecordLineageOnRecordUserService.findProjectCode(factoryDataset, masterDataspaceKey, masterDatasetName));
		final String entityName = RecordLineageOnRecordUserService.entityNameFromTablePath(tablePath);
		if (projectCode == null || entityName == null)
		{
			// the permission rule already restricts this action to a project's own promoted master tables -
			// reaching here with no matching project/entity means the master zone changed between the menu
			// render and the click (MasterZoneIndex's own TTL/invalidation race, see its Javadoc): a plain
			// notice, never a stack trace.
			this.warn(Messages.get(this.getClass(), this.locale, "lineage.notice.unexpected"));
			return;
		}
		this.projectCode = projectCode;
		this.entityName = entityName;
		this.keyText = masterRecord.getOccurrencePrimaryKey().format();
		this.lookup(pContext.getSession());
	}

	/** The factory's own {@code MdmFactory} dataset - see the class Javadoc for why this is never {@code selection.getDataset()} here. */
	private static Adaptation findFactory(final Repository pRepository)
	{
		if (pRepository == null)
		{
			return null;
		}
		final AdaptationHome factoryHome = pRepository.lookupHome(HomeKey.forBranchName(FactoryPaths.DATASPACE));
		return factoryHome == null ? null : factoryHome.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DATASET));
	}

	/** The project whose OWN {@code masterDataspace}/{@code masterDataset} match the record being viewed. */
	private static String findProjectCode(final Adaptation pFactory, final String pMasterDataspaceKey, final String pMasterDatasetName)
	{
		for (final Adaptation project : ProposalReviews.projects(pFactory))
		{
			if (pMasterDataspaceKey.equals(com.on.ebx.factory.ebx.UserView.string(project, FactoryPaths.Project.MASTER_DATASPACE))
				&& pMasterDatasetName.equals(com.on.ebx.factory.ebx.UserView.string(project, FactoryPaths.Project.MASTER_DATASET)))
			{
				return project.getOccurrencePrimaryKey().format();
			}
		}
		return null;
	}

	/** The inverse of {@code MasterModel.tablePath(entityName)} ({@code "/root/" + entityName}) - null for any other shape, never trusted blindly even though the permission rule already restricted activation to exactly these paths. */
	private static String entityNameFromTablePath(final String pTablePath)
	{
		final String prefix = "/root/";
		return pTablePath != null && pTablePath.startsWith(prefix) && pTablePath.length() > prefix.length() ? pTablePath.substring(prefix.length()) : null;
	}
}
