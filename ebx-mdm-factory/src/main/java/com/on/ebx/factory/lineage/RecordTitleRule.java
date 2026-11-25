package com.on.ebx.factory.lineage;

import java.util.List;
import java.util.Locale;

/**
 * The PURE decision behind a record's own display title, wherever the lineage screen would otherwise show a
 * bare primary key (coordinator review, 2026-09-22, "fact 1": the golden card title and page heading on
 * master record {@code Company} key 183 both showed "183" - the record's own EBX label was blank because the
 * generated master model carries no {@code osd:table/defaultLabel}, so {@code Adaptation.getLabel(Session)}
 * echoes the formatted key back). No EBX class anywhere in this file - unit-tested without an EBX runtime,
 * the same discipline every other pure model in this module follows ({@code RecordLineage}, {@code
 * MasterModel}, {@code MasterZoneActivation}, ...).
 * <p>
 * Two entry points, one shared name-priority heuristic:
 * <ul>
 * <li>{@link #pickTitle} - given a record's own label (if any), its key, and an ordered list of its OWN field
 * (name, value) pairs, picks the best VALUE to show as a title. Used by {@code ebx.RecordLineageReader} for
 * the master record itself and for a Match and Merge group's matched/suspect members (same all-or-nothing
 * redaction rule as everywhere else in that class: a candidate list built by the EBX-bound caller only ever
 * contains fields the viewer may actually see - a restricted field is simply never offered, never flagged).</li>
 * <li>{@link #pickDisplayFieldName} - given only the entity's own field NAMES (no values, no record - called
 * once per table at MODEL-BUILD time), picks which field {@code master.MasterModel} should point a table's
 * {@code osd:table/defaultLabel} at, so the fallback in {@link #pickTitle} is increasingly not even needed as
 * new master tables are generated (see that class's own Javadoc for why an EXISTING table is never touched).</li>
 * </ul>
 * <p>
 * <b>The priority order, in both entry points</b>: a field named (case-insensitively) exactly {@code name},
 * {@code label} or {@code title}; else a field whose name ends in {@code Name} with something before it
 * ({@code accountName}, {@code legalName} - a bare {@code name} is already covered by the first tier, so this
 * tier never re-matches it); else the first remaining field in the caller's own order that is not a
 * technical one ({@code sourceSystem}/{@code sourceId} by name, or a {@code _}-prefixed field) - the caller is
 * responsible for excluding the primary key itself and any derived/technical matching-key attribute (e.g. a
 * {@code quality.CrossSystemFold} key) from the list before it ever reaches this class, since "is this field
 * the primary key" is a structural fact this pure class has no way to know from a name alone.
 */
public final class RecordTitleRule
{
	private RecordTitleRule()
	{
	}

	/** One candidate field of the record being titled: its name and its own (already permission-filtered, already read) value. */
	public static final class Field
	{
		public final String name;
		/** Null/blank is treated as "not a candidate" - never picked, never counted as having satisfied a tier. */
		public final String value;

		public Field(final String pName, final String pValue)
		{
			this.name = pName;
			this.value = pValue;
		}
	}

	/**
	 * The record's own display title: {@code pLabel} when it is non-blank and different from {@code pKey}
	 * (EBX's own {@code Adaptation.getLabel(Session)}, already known to be meaningful); otherwise the first
	 * eligible value out of {@code pFields}, in the priority order the class Javadoc describes; otherwise
	 * {@code pKey} itself - this method never returns {@code null} and never returns a blank string.
	 */
	public static String pickTitle(final String pLabel, final String pKey, final List<Field> pFields)
	{
		final String label = RecordTitleRule.blankToNull(pLabel);
		if (label != null && !label.equals(pKey))
		{
			return label;
		}
		if (pFields != null)
		{
			for (final Field field : pFields)
			{
				if (RecordTitleRule.isExactNameMatch(field.name))
				{
					final String value = RecordTitleRule.blankToNull(field.value);
					if (value != null)
					{
						return value;
					}
				}
			}
			for (final Field field : pFields)
			{
				if (RecordTitleRule.isNameSuffixMatch(field.name))
				{
					final String value = RecordTitleRule.blankToNull(field.value);
					if (value != null)
					{
						return value;
					}
				}
			}
			for (final Field field : pFields)
			{
				if (RecordTitleRule.isEligibleFallbackField(field.name))
				{
					final String value = RecordTitleRule.blankToNull(field.value);
					if (value != null)
					{
						return value;
					}
				}
			}
		}
		return pKey;
	}

	/**
	 * The field NAME {@code master.MasterModel} should point a new table's {@code defaultLabel} at, applying
	 * the SAME three tiers as {@link #pickTitle} over names alone (no values exist yet at model-build time) -
	 * null when nothing in {@code pFieldNames} qualifies (the table keeps its key-based EBX label, exactly
	 * {@link #pickTitle}'s own last-resort behaviour at read time).
	 */
	public static String pickDisplayFieldName(final List<String> pFieldNames)
	{
		if (pFieldNames == null)
		{
			return null;
		}
		for (final String name : pFieldNames)
		{
			if (RecordTitleRule.isExactNameMatch(name))
			{
				return name;
			}
		}
		for (final String name : pFieldNames)
		{
			if (RecordTitleRule.isNameSuffixMatch(name))
			{
				return name;
			}
		}
		for (final String name : pFieldNames)
		{
			if (RecordTitleRule.isEligibleFallbackField(name))
			{
				return name;
			}
		}
		return null;
	}

	private static boolean isExactNameMatch(final String pName)
	{
		if (pName == null)
		{
			return false;
		}
		final String lower = pName.toLowerCase(Locale.ROOT);
		return "name".equals(lower) || "label".equals(lower) || "title".equals(lower);
	}

	/** {@code accountName}/{@code legalName} - not a bare {@code name} (already {@link #isExactNameMatch}'s own tier, checked first so the two tiers never both claim the same field). */
	private static boolean isNameSuffixMatch(final String pName)
	{
		if (pName == null)
		{
			return false;
		}
		final String lower = pName.toLowerCase(Locale.ROOT);
		return lower.length() > "name".length() && lower.endsWith("name");
	}

	/** Never {@code sourceSystem}/{@code sourceId} (case-insensitive - the entity's own technical identity, {@code StagingModel.AttributeInfo.primaryKey}'s Javadoc) and never a {@code _}-prefixed technical field. */
	private static boolean isEligibleFallbackField(final String pName)
	{
		if (pName == null || pName.isEmpty() || pName.charAt(0) == '_')
		{
			return false;
		}
		final String lower = pName.toLowerCase(Locale.ROOT);
		return !"sourcesystem".equals(lower) && !"sourceid".equals(lower);
	}

	private static String blankToNull(final String pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		final String trimmed = pValue.trim();
		return trimmed.isEmpty() ? null : trimmed;
	}
}
