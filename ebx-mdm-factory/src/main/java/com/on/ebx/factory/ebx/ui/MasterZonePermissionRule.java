package com.on.ebx.factory.ebx.ui;

import com.on.ebx.factory.ebx.MasterZoneIndex;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.ui.selection.TableEntitySelection;
import com.orchestranetworks.userservice.permission.ServicePermissionRule;
import com.orchestranetworks.userservice.permission.ServicePermissionRuleContext;
import com.orchestranetworks.userservice.permission.UserServicePermission;

/**
 * The shared "is this record/table sitting on a project's own promoted master zone" activation decision -
 * extracted, 2026-09-22 (toolbar module-level services lot), from what used to be {@code
 * RecordLineageOnRecordPermissionRule}'s own entire body, so the exact same check could gate other
 * module-level toolbar services besides the pre-existing {@code OnRecord} one - see &sect;12 of {@code
 * docs/MASTER-ZONE.md} for the live incident that made a module-level (rather than schema-extension) service
 * necessary in the first place for anything launched from a DMA-generated master model.
 * <p>
 * <b>Its only caller today is {@code RecordLineageOnRecordPermissionRule}</b> - a same-day follow-up briefly
 * used this generic base for two more {@code OnTableView} services (Cockpit/Proposal review buttons on a
 * master table's toolbar), but the product owner decided, 16:40 the same day, that those two screens belong on the
 * factory's own {@code Project} table instead (`docs/MASTER-ZONE.md` &sect;12, {@code master.MasterToolbars}'s
 * own Javadoc); the two services were deleted. This class is kept generic anyway - it cost nothing to write
 * this way, and it is the obvious base for the next module-level toolbar action on a master table.
 * <p>
 * <b>Generic over {@code S extends TableEntitySelection}, not {@code DatasetEntitySelection}</b>: the
 * coordinator's own brief for this lot suggested {@code DatasetEntitySelection} as the bound, but that
 * interface has no {@code getTable()} - confirmed by the local Java API doc and by {@code javap} on {@code
 * ebx-lib.jar} (`ebx-ps-fasttrack-server/lib`, read 2026-09-22): {@code TableEntitySelection extends
 * DatasetEntitySelection} adds exactly {@code AdaptationTable getTable()}, and both {@link
 * com.orchestranetworks.ui.selection.RecordEntitySelection} and {@link
 * com.orchestranetworks.ui.selection.TableViewEntitySelection} extend {@code TableEntitySelection}
 * (`All Superinterfaces` on both Javadoc pages) - so {@code TableEntitySelection} is the narrowest bound that
 * still gives {@link #getPermission} everything it needs ({@code getDataspace()}/{@code getDataset()}/
 * {@code getTable()}), for either concrete selection type.
 * <p>
 * <b>Fails CLOSED in every ambiguous case, never open</b> (unchanged from the original {@code
 * RecordLineageOnRecordPermissionRule}): any {@link RuntimeException} anywhere in {@link #getPermission} (a
 * missing dataspace/dataset/table on the selection, {@link MasterZoneIndex} itself, or anything else
 * unexpected) is caught and answers {@link UserServicePermission#getDisabled()} - the action simply does not
 * appear, never a stack trace on a record/table form and never a false "offered everywhere". This is the
 * fail-CLOSED half of the pair {@link MasterZoneIndex} documents in its own Javadoc (that class fails SOFT
 * on a cache-refresh failure, i.e. keeps serving whatever it last knew - this rule is what guarantees the
 * actual answer returned to EBX is never "enabled" by mistake).
 * <p>
 * <b>Cheap on the hot path</b>: the actual (dataspace, dataset, table-path) lookup is a single {@code
 * HashSet} membership test ({@code com.on.ebx.factory.ebx.MasterZoneActivation#matches}, the PURE,
 * unit-tested core this rule and {@link MasterZoneIndex} both defer to) against {@link MasterZoneIndex}'s
 * own cache - no EBX read of any kind happens inside this method itself. <b>Not verified live this
 * session</b> (this lot's own constraint, same as the original class): confirm this rule is actually
 * invoked once per action-menu/toolbar render (not, say, once per candidate action times several menus)
 * before trusting the "cheap" claim under real load.
 *
 * @param <S> the concrete selection type - {@link com.orchestranetworks.ui.selection.RecordEntitySelection}
 *        for an {@code OnRecord} declaration, {@link com.orchestranetworks.ui.selection.TableViewEntitySelection}
 *        for an {@code OnTableView} one; either way {@link #getPermission} only ever touches the three
 *        methods {@code TableEntitySelection} itself declares (plus the two it inherits from {@code
 *        DatasetEntitySelection}/{@code DataspaceEntitySelection}), never anything specific to one selection
 *        type over the other.
 */
class MasterZonePermissionRule<S extends TableEntitySelection> implements ServicePermissionRule<S>
{
	@Override
	public UserServicePermission getPermission(final ServicePermissionRuleContext<S> pContext)
	{
		try
		{
			final S selection = pContext.getEntitySelection();
			final AdaptationHome dataspace = selection.getDataspace();
			final Adaptation dataset = selection.getDataset();
			final AdaptationTable table = selection.getTable();
			if (dataspace == null || dataset == null || table == null || table.getTablePath() == null)
			{
				return UserServicePermission.getDisabled();
			}
			final String dataspaceKey = dataspace.getKey().getName();
			// AdaptationName.getStringName(), never .toString() - ROOT CAUSE, found by javap -c against
			// ebx-lib.jar (coordinator review, 2026-09-22, "fact 2", carried over verbatim from the original
			// RecordLineageOnRecordPermissionRule this class replaces the body of): AdaptationName declares a
			// CONCRETE toString() (not abstract, not overridden by AdaptationReference - the class forName()
			// actually returns) that builds an OToString seeded with "this", appends toStringDetail, and
			// finishes with OToString.finishWithHashCode() - i.e. "<ShortClassName>[...]@<hex hashcode>", a
			// debug string that can never equal the plain dataset name (e.g. "Master") MasterZoneIndex's
			// triples are keyed by. getStringName() is the one method Javadoc-documents as "Returns the string
			// name." Using .toString() here would fail CLOSED for every dataset, on every record/table, with
			// zero exception and zero log line, by design (this rule's own fail-closed catch, and
			// MasterZoneIndex's own fail-soft cache, both log nothing on a plain "false").
			final String datasetName = dataset.getAdaptationName().getStringName();
			final String tablePath = table.getTablePath().format();
			return MasterZoneIndex.isMasterZoneTable(dataspaceKey, datasetName, tablePath) ? UserServicePermission.getEnabled() : UserServicePermission.getDisabled();
		}
		catch (final RuntimeException ex)
		{
			// fail CLOSED, never a stack trace and never a log line on this hot path (task brief) - the action
			// just does not appear this time; MasterZoneIndex's own cache is unaffected by this catch.
			return UserServicePermission.getDisabled();
		}
	}
}
