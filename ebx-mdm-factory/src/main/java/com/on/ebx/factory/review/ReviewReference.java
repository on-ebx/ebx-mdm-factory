package com.on.ebx.factory.review;

/**
 * The short, human-sayable reference every review line carries (coordinator report, 2026-09-21:
 * "Je ne vois pas les Ids des choses a revoir dans l'ecran" - the coordinator reads the factory
 * tables by id, "MappingStep 8", "AttributeMapping 63"..., and had no way to point the product owner at a
 * line): one stable prefix per {@link ReviewLine} nature plus the row's own primary key, e.g.
 * {@code MAP-63} for {@code AttributeMapping} row 63. Built in this ONE pure place so the review
 * screen ({@link ReviewPage}), the REST routes ({@code ebx.CapabilityApplier}) and any future tool
 * derive the exact same string - never duplicated, never guessed at the call site.
 * <p>
 * {@link #matches} additionally implements the tolerant parsing the Reference search box needs
 * ({@link ReviewFilter}): case-insensitive, indifferent to a space or hyphen between the prefix and
 * the number ({@code STEP-8}/{@code step 8}/{@code step8} all mean the same thing).
 */
public final class ReviewReference
{
	private ReviewReference()
	{
	}

	/**
	 * One short prefix per nature - the examples the coordinator asked for: {@code MAP-63},
	 * {@code STEP-8}, {@code RULE-12}, {@code ATTR-36}, {@code ENT-4}, {@code REL-2},
	 * {@code SCRIPT-6}, {@code SVC-9}, {@code MATCH-1}. Null for a nature that is not one of
	 * {@link ReviewLine}'s own constants (never expected in practice - every line {@code
	 * ebx.ProposalReviews} builds carries a real nature).
	 */
	public static String prefixFor(final String pNature)
	{
		if (ReviewLine.RELATION.equals(pNature))
		{
			return "REL";
		}
		if (ReviewLine.ENTITY.equals(pNature))
		{
			return "ENT";
		}
		if (ReviewLine.ATTRIBUTE.equals(pNature))
		{
			return "ATTR";
		}
		if (ReviewLine.MAPPING.equals(pNature))
		{
			return "MAP";
		}
		if (ReviewLine.MAPPING_STEP.equals(pNature))
		{
			return "STEP";
		}
		if (ReviewLine.QUALITY.equals(pNature))
		{
			return "RULE";
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature))
		{
			return "SCRIPT";
		}
		if (ReviewLine.EXTERNAL_SERVICE.equals(pNature))
		{
			return "SVC";
		}
		if (ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return "DERIVE";
		}
		if (ReviewLine.MATCH.equals(pNature))
		{
			return "MATCH";
		}
		if (ReviewLine.FEED.equals(pNature))
		{
			return "FEED";
		}
		return null;
	}

	/**
	 * The underlying table's own name ({@code LandingRelation}, {@code AttributeMapping}...) - never
	 * shown as the reference text itself, only as the tooltip naming what {@link #format} points at
	 * ("MappingStep 8"), per the coordinator's own request.
	 */
	public static String tableNameFor(final String pNature)
	{
		if (ReviewLine.RELATION.equals(pNature))
		{
			return "LandingRelation";
		}
		if (ReviewLine.ENTITY.equals(pNature))
		{
			return "StagingEntity";
		}
		if (ReviewLine.ATTRIBUTE.equals(pNature))
		{
			return "StagingAttribute";
		}
		if (ReviewLine.MAPPING.equals(pNature))
		{
			return "AttributeMapping";
		}
		if (ReviewLine.MAPPING_STEP.equals(pNature))
		{
			return "MappingStep";
		}
		if (ReviewLine.QUALITY.equals(pNature))
		{
			return "QualityRule";
		}
		if (ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) || ReviewLine.EXTERNAL_SERVICE.equals(pNature) || ReviewLine.SCRIPT_DERIVATION.equals(pNature))
		{
			return "CapabilitySuggestion";
		}
		if (ReviewLine.MATCH.equals(pNature))
		{
			return "MatchPlan";
		}
		if (ReviewLine.FEED.equals(pNature))
		{
			return "TransferFeed";
		}
		return null;
	}

	/** {@code MAP-63}; null when {@code pNature} has no {@link #prefixFor} or {@code pRowId} is blank. */
	public static String format(final String pNature, final String pRowId)
	{
		final String prefix = ReviewReference.prefixFor(pNature);
		if (prefix == null || pRowId == null || pRowId.trim().isEmpty())
		{
			return null;
		}
		return prefix + "-" + pRowId.trim();
	}

	/** {@code "MappingStep 8"} - the tooltip text naming the table and key (never the reference text itself). */
	public static String tooltip(final String pNature, final String pRowId)
	{
		final String tableName = ReviewReference.tableNameFor(pNature);
		if (tableName == null || pRowId == null || pRowId.trim().isEmpty())
		{
			return null;
		}
		return tableName + " " + pRowId.trim();
	}

	/**
	 * Uppercased, letters/digits only - the same normal form for a formatted reference and a typed
	 * search query, so {@code STEP-8}, {@code step 8} and {@code STEP8} compare equal. Never negative
	 * on null ({@code ""}), so a caller need not null-check before calling it.
	 */
	public static String normalize(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		final StringBuilder out = new StringBuilder(pValue.length());
		for (int i = 0; i < pValue.length(); i++)
		{
			final char c = pValue.charAt(i);
			if (Character.isLetterOrDigit(c))
			{
				out.append(Character.toUpperCase(c));
			}
		}
		return out.toString();
	}

	/** Whether a normalized (see {@link #normalize}) string is all digits - a bare row id, with no nature prefix of its own. */
	public static boolean isNumeric(final String pNormalized)
	{
		if (pNormalized == null || pNormalized.isEmpty())
		{
			return false;
		}
		for (int i = 0; i < pNormalized.length(); i++)
		{
			if (!Character.isDigit(pNormalized.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}
}
