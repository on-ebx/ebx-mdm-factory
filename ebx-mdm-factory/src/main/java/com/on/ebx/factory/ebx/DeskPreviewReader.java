package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.mappingmatrix.RowTransform;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.stagingdesk.DeskPreview;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.Session;

/**
 * The EBX-bound half of the Validation desk's preview of real rows (docs/STAGING-DESK.md L2): reads, for ONE business object, a few landing rows per feed and the cleaning steps of
 * the mappings it shows, then hands them to the pure {@link DeskPreview#build}. It reads the way the Mapping matrix' sample does ({@code MappingMatrixReader#sample}) - the same
 * landing table lookup, the same {@code UserView.select} (the caller runs it inside {@code UserView.scoped}, so only the rows the user's EBX permissions show), the same
 * {@code StagingLoader#buildRowDocument} - and shows only the columns a link reads, never a whole landing row.
 * <p>
 * <b>What a render reads</b> (the cost, in reads): per feed of the object, ONE request on the landing table that stops after {@code offset + 6} rows (never a scan: a landing
 * table can hold a million rows), one row document per row read, and ONE request on {@code MappingStep} per shown mapping; plus, only when a shown mapping has a cleaning step or
 * the field has a REFERENCE rule, one pass over the reference bindings ({@code QualitySuggester#buildLookups}, the loader's own). Nothing is read for the other objects.
 */
public final class DeskPreviewReader
{
	private DeskPreviewReader()
	{
	}

	/** What a render measured: for the page's footnote and the tests of the cost - the landing rows and the {@code MappingStep} requests it read. */
	public static final class Cost
	{
		public int landingRequests;
		public int landingRowsRead;
		public int stepRequests;
		public boolean lookupsLoaded;
	}

	/** The preview of one business object at the given page offsets (feed id to first row; absent = 0). */
	public static DeskPreview read(final Adaptation pFactory, final Session pSession, final MatrixModel.Project pProject, final MatrixModel.Entity pEntity,
		final Map<String, Integer> pOffsets, final Cost pCost)
	{
		final Map<String, DeskPreview.FeedRows> rowsByFeed = new HashMap<String, DeskPreview.FeedRows>();
		boolean needLookups = false;
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (!feed.collectionPath.isEmpty())
			{
				continue; // the preview falls back to the list; nothing to read
			}
			final DeskPreview.FeedRows rows = new DeskPreview.FeedRows();
			final Integer offset = pOffsets == null ? null : pOffsets.get(feed.id);
			rows.offset = offset == null ? 0 : Math.max(0, offset.intValue());
			rowsByFeed.put(feed.id, rows);
			DeskPreviewReader.readLandingRows(pFactory, feed, rows, pCost);
			if (rows.documents.isEmpty() && rows.offset > 0 && rows.unavailable.isEmpty())
			{
				rows.offset = 0; // the table shrank since the last page: back to the first rows
				DeskPreviewReader.readLandingRows(pFactory, feed, rows, pCost);
			}
			for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
			{
				if (cell.mappingId == null || ReviewLine.REJECTED.equals(cell.status) || RowTransform.needsRuntimeLookup(cell.pipeline))
				{
					continue;
				}
				final List<CleaningSteps.Step> steps = StagingLoader.readCleaningSteps(pFactory, cell.mappingId);
				pCost.stepRequests++;
				rows.stepsByMappingId.put(cell.mappingId, steps);
				needLookups |= !steps.isEmpty();
			}
		}
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			for (final Guard guard : attr.guards)
			{
				needLookups |= ReviewLine.QUALITY.equals(guard.nature) && QualityDetectors.REFERENCE.equals(guard.kind);
			}
		}
		CleaningSteps.Lookups lookups = null;
		if (needLookups)
		{
			lookups = QualitySuggester.buildLookups(pFactory, new ArrayList<String>());
			pCost.lookupsLoaded = true;
		}
		// lot L4: a rule with a condition flags only the rows that meet it
		final List<String> ruleIds = new ArrayList<String>();
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			for (final Guard guard : attr.guards)
			{
				if (ReviewLine.QUALITY.equals(guard.nature))
				{
					ruleIds.add(guard.id);
				}
			}
		}
		return DeskPreview.build(pProject, pEntity, rowsByFeed, lookups, pSession, DeskCheckReader.conditions(pFactory, ruleIds));
	}

	/** The feeds shown and the links flagged for one object - what the "Accept as shown" action plans on (the desk's service hands this to the matrix' handler). */
	public static MatrixCompute.ShownKeys shownKeys(final Adaptation pFactory, final Session pSession, final MatrixModel.Project pProject, final MatrixModel.Entity pEntity,
		final Map<String, Integer> pOffsets)
	{
		return DeskPreviewReader.read(pFactory, pSession, pProject, pEntity, pOffsets, new Cost()).keys();
	}

	/**
	 * At most {@link DeskPreview#ROWS} rows from {@code pRows.offset}, in the table's own (primary key) order: ONE request, read forward and abandoned after the last row, plus
	 * one row to know whether "another 5" exists. Skipping to an offset reads the rows before it (EBX has no seek on a request); the offset only grows by 5 per click.
	 */
	private static void readLandingRows(final Adaptation pFactory, final MatrixModel.Feed pFeed, final DeskPreview.FeedRows pRows, final Cost pCost)
	{
		if (pFeed.landingDataspace.isEmpty() || pFeed.landingDataset.isEmpty() || "?".equals(pFeed.landingTableName))
		{
			pRows.unavailable = RowTransform.REASON_NO_LANDING;
			return;
		}
		try
		{
			final Repository repository = pFactory.getHome().getRepository();
			final AdaptationHome landingHome = repository.lookupHome(HomeKey.forBranchName(pFeed.landingDataspace));
			final Adaptation landingDataset = landingHome == null ? null : landingHome.findAdaptationOrNull(AdaptationName.forName(pFeed.landingDataset));
			if (landingDataset == null)
			{
				pRows.unavailable = RowTransform.REASON_DATASET_NOT_FOUND;
				return;
			}
			final AdaptationTable landingTable = landingDataset.getTable(Path.parse("/root/" + pFeed.landingTableName));
			pCost.landingRequests++;
			final RequestResult result = UserView.select(landingTable, null);
			try
			{
				int index = 0;
				for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
				{
					pCost.landingRowsRead++;
					if (index >= pRows.offset + DeskPreview.ROWS)
					{
						pRows.more = true; // one row past the page: "another 5" has something to show
						break;
					}
					if (index >= pRows.offset)
					{
						pRows.documents.add(StagingLoader.buildRowDocument(row));
					}
					index++;
				}
			}
			finally
			{
				result.close();
			}
		}
		catch (final RuntimeException ex) // never let a preview break the page: the desk prints the list of the feed instead, with the reason
		{
			pRows.documents.clear();
			pRows.unavailable = ex.getMessage() == null ? ex.toString() : ex.getMessage();
		}
	}
}
