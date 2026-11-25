package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.staging.ParentLinkRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;

/**
 * Which attributes of one staging entity are its structural link to the parent ({@link ParentLinkRule}), read from the rows:
 * an attribute carrying a {@code referenceEntity} whose {@code <name>Ref} field is the {@code parentLinkField} of a child feed
 * (a feed with a {@code parentFeed}) of the same entity that is not rejected.
 */
public final class ParentLinkReader
{
	private ParentLinkReader()
	{
	}

	/** Ids of the entity's parent-link attributes; empty for a root entity. */
	public static Set<String> attributeIdsOf(final Adaptation pFactory, final String pEntityId)
	{
		return ParentLinkReader.attributeIdsOf(ParentLinkReader.rows(pFactory, FactoryPaths.STAGING_ATTRIBUTE, pEntityId),
			ParentLinkReader.rows(pFactory, FactoryPaths.TRANSFER_FEED, pEntityId));
	}

	/** Same, from rows already read (the wizard's snapshot reads them once). */
	public static Set<String> attributeIdsOf(final List<Adaptation> pAttributeRows, final List<Adaptation> pFeedRows)
	{
		final List<String> linkFields = new ArrayList<String>();
		for (final Adaptation feed : pFeedRows)
		{
			if (ReviewLine.REJECTED.equals(feed.getString(FactoryPaths.TransferFeed.STATUS)) || ParentLinkReader.isEmpty(feed.getString(FactoryPaths.TransferFeed.PARENT_FEED)))
			{
				continue;
			}
			final String linkField = feed.getString(FactoryPaths.TransferFeed.PARENT_LINK_FIELD);
			if (!ParentLinkReader.isEmpty(linkField))
			{
				linkFields.add(linkField);
			}
		}
		final Set<String> out = new LinkedHashSet<String>();
		if (linkFields.isEmpty())
		{
			return out;
		}
		for (final Adaptation attribute : pAttributeRows)
		{
			if (ParentLinkReader.isEmpty(attribute.getString(FactoryPaths.StagingAttribute.REFERENCE_ENTITY)))
			{
				continue;
			}
			final String name = attribute.getString(FactoryPaths.StagingAttribute.NAME);
			for (final String linkField : linkFields)
			{
				if (ParentLinkRule.isLinkFieldOf(name, linkField))
				{
					out.add(attribute.getOccurrencePrimaryKey().format());
					break;
				}
			}
		}
		return out;
	}

	private static List<Adaptation> rows(final Adaptation pFactory, final com.orchestranetworks.schema.Path pTable, final String pEntityId)
	{
		final List<Adaptation> out = new ArrayList<Adaptation>();
		final RequestResult result = UserView.select(pFactory.getTable(pTable), "./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = result.nextAdaptation(); row != null; row = result.nextAdaptation())
			{
				out.add(row);
			}
		}
		finally
		{
			result.close();
		}
		return out;
	}

	private static boolean isEmpty(final String pValue)
	{
		return pValue == null || pValue.isEmpty();
	}
}
