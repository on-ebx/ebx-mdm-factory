package com.on.ebx.factory.ebx;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.matching.MatchResultsMask;
import com.on.ebx.factory.matching.MatchResultsView;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;

/**
 * Second permissions pass (2026-10-14): the EBX half of {@link MatchResultsMask} - what one person may see of the master table a stored matching results
 * block describes, read with that person's {@link UserView} (the master record by key, then each compared field on it). The block is precomputed by a
 * job with the engine's view; this is applied at each display, before it reaches the page.
 */
public final class MatchResultsPermissions
{
	private MatchResultsPermissions()
	{
	}

	/**
	 * {@code pInput} cut down to {@code pView}; the input itself when the view is the engine's. A master table the person cannot open (dataspace,
	 * dataset or table hidden, or not found) hides every record of the block.
	 */
	public static MatchResultsView.Input forUser(final UserView pView, final Repository pRepository, final String pMasterDataspace, final String pMasterDataset,
		final String pEntityName, final List<String> pTitleFields, final MatchResultsView.Input pInput)
	{
		if (pView == null || pView.engine() || pInput == null)
		{
			return pInput;
		}
		final AdaptationTable table = MatchResultsPermissions.masterTable(pRepository, pMasterDataspace, pMasterDataset, pEntityName);
		final Map<String, Adaptation> records = new HashMap<String, Adaptation>();
		return MatchResultsMask.apply(pInput, pTitleFields, new MatchResultsMask.Visibility()
		{
			@Override
			public boolean record(final String pKey)
			{
				return this.lookup(pKey) != null;
			}

			@Override
			public boolean field(final String pKey, final String pFieldName)
			{
				final Adaptation record = this.lookup(pKey);
				if (record == null || pFieldName == null || pFieldName.isEmpty())
				{
					return false;
				}
				try
				{
					return pView.shows(record, Path.parse(pFieldName));
				}
				catch (final RuntimeException malformed)
				{
					return false;
				}
			}

			@Override
			public boolean childTable(final String pEntity)
			{
				// the card's child summary (an address line) was read by the engine: shown only when this person may open the child's master table
				final AdaptationTable child = MatchResultsPermissions.masterTable(pRepository, pMasterDataspace, pMasterDataset, pEntity);
				return child != null && pView.tableVisible(child);
			}

			private Adaptation lookup(final String pKey)
			{
				if (table == null || pKey == null)
				{
					return null;
				}
				if (!records.containsKey(pKey))
				{
					records.put(pKey, pView.lookup(table, pKey));
				}
				return records.get(pKey);
			}
		});
	}

	private static AdaptationTable masterTable(final Repository pRepository, final String pDataspace, final String pDataset, final String pEntityName)
	{
		if (pRepository == null || pDataspace == null || pDataspace.isEmpty() || pDataset == null || pDataset.isEmpty() || pEntityName == null)
		{
			return null;
		}
		try
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pDataspace));
			final Adaptation dataset = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(pDataset));
			return dataset == null ? null : dataset.getTable(Path.parse(MasterModel.tablePath(pEntityName)));
		}
		catch (final RuntimeException notThere)
		{
			return null;
		}
	}
}
