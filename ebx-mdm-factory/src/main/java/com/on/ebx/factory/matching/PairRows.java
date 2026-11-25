package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Step 6, the pair card (2026-10-18): the rows a suspect pair is compared on - what the PLAN compares, not just the raw fields. A key the plan derived from a raw
 * field ({@code telephoneE164} from {@code telephone}, an e-mail key, a name key) replaces its raw field in the comparison: the two records carrying
 * {@code +33606857393} and {@code 0606857393} have the SAME key, so the row reads identical (it used to read "different" on the raw values), and the raw
 * values stay visible as a second line. Rows are labelled with the attribute's label. A subset of the rows is flagged {@link MatchStats.FieldComparison#glance}: the
 * fields the plan compares, topped up with the first fields of the entity up to {@link #GLANCE_MIN}; the card shows only those by default and all of them under
 * "Compare field by field". Pure: values come through {@link Values}.
 */
public final class PairRows
{
	/** The card shows at least this many rows at a glance (the plan's own fields first, then the entity's first fields). */
	public static final int GLANCE_MIN = 6;
	/** Rows kept per pair in the stored block (the exhaustive view). */
	public static final int MAX_ROWS = 16;

	private PairRows()
	{
	}

	/** The derivation of an attribute that exists only for the matching and has no raw field to show: the keys made from the child rows ({@code CHILD_KEYS}). */
	public static boolean isTechnicalKey(final String pDerivation)
	{
		return pDerivation != null && pDerivation.trim().equalsIgnoreCase("CHILD_KEYS");
	}

	/** The names {@code master.MasterModel} may have given the foreign key field built from the staging attribute {@code pAttribute}: {@code <attribute>Ref}, then {@code _2}... when taken. */
	public static List<String> foreignKeyFieldCandidates(final String pAttribute)
	{
		final List<String> out = new ArrayList<String>();
		for (int i = 1; i <= 5; i++)
		{
			out.add(i == 1 ? pAttribute + "Ref" : pAttribute + "Ref_" + i);
		}
		return out;
	}

	/** One compared attribute of the entity. */
	public static final class Attr
	{
		public final String name;
		public final String label;
		/** The raw attribute this one is derived from ({@code telephoneE164} derived from {@code telephone}); empty for an ordinary attribute. */
		public final String derivedFrom;
		/** The plan compares this attribute. */
		public final boolean inPlan;
		/** A key made for the matching alone (derived from the child rows): never a row of the card. */
		public final boolean technical;

		public Attr(final String pName, final String pLabel, final String pDerivedFrom, final boolean pInPlan)
		{
			this(pName, pLabel, pDerivedFrom, pInPlan, false);
		}

		public Attr(final String pName, final String pLabel, final String pDerivedFrom, final boolean pInPlan, final boolean pTechnical)
		{
			this.technical = pTechnical;
			this.name = pName;
			this.label = pLabel == null || pLabel.trim().isEmpty() ? pName : pLabel.trim();
			this.derivedFrom = pDerivedFrom == null ? "" : pDerivedFrom;
			this.inPlan = pInPlan;
		}
	}

	/** A child entity of the compared one (the addresses of a person): its master table is read for the summary row. */
	public static final class Child
	{
		public final String entity;
		public final String label;
		/** The field of the child's master table that points to the parent. */
		public final String fkField;
		/** The child fields summarised (street, postal code, city), in the child's own order. */
		public final List<String> fields;

		public Child(final String pEntity, final String pLabel, final String pFkField, final List<String> pFields)
		{
			this.entity = pEntity;
			this.label = pLabel == null || pLabel.trim().isEmpty() ? pEntity : pLabel.trim();
			this.fkField = pFkField;
			this.fields = pFields == null ? new ArrayList<String>() : pFields;
		}
	}

	/** Everything the pair card needs to know about the entity: its compared attributes, the fields that name a record, its children, the labels of its fields. */
	public static final class Spec
	{
		public final List<Attr> attrs = new ArrayList<Attr>();
		/** The master fields whose values name a record ("Julie Lefevre"): the plan's similarity fields, at most two. Empty: the record's own title. */
		public final List<String> nameFields = new ArrayList<String>();
		public final List<Child> children = new ArrayList<Child>();
		/** Master field name to label, derived keys included (a derived key reads as its raw field's label). */
		public final Map<String, String> labels = new HashMap<String, String>();
		/** The nodes of the plan by the name the engine gave them (and the disambiguated {@code "name (Nn)"} of a repeated one): tells the deciding node from the leaf in an evaluated path. Empty: unknown. */
		public final Set<String> planNodeNames = new LinkedHashSet<String>();
	}

	/** The values of the two records of a pair, by master field name. */
	public interface Values
	{
		String left(String pField);

		String right(String pField);
	}

	/** The rows of a pair, in the entity's own order. {@code pAttrs} in staging order. */
	public static List<MatchStats.FieldComparison> build(final List<Attr> pAllAttrs, final Values pValues)
	{
		final List<Attr> pAttrs = new ArrayList<Attr>();
		for (final Attr attr : pAllAttrs)
		{
			if (!attr.technical)
			{
				pAttrs.add(attr);
			}
		}
		final Map<String, Attr> byName = new HashMap<String, Attr>();
		for (final Attr attr : pAttrs)
		{
			byName.put(attr.name, attr);
		}
		// a raw attribute and the derived key the plan compares in its place
		final Map<String, Attr> derivedOf = new HashMap<String, Attr>();
		final Set<String> merged = new LinkedHashSet<String>();
		for (final Attr attr : pAttrs)
		{
			if (!attr.derivedFrom.isEmpty() && attr.inPlan && byName.containsKey(attr.derivedFrom) && !derivedOf.containsKey(attr.derivedFrom))
			{
				derivedOf.put(attr.derivedFrom, attr);
				merged.add(attr.name);
			}
		}
		final List<MatchStats.FieldComparison> rows = new ArrayList<MatchStats.FieldComparison>();
		final List<Boolean> planRow = new ArrayList<Boolean>();
		final List<Boolean> plainRow = new ArrayList<Boolean>();
		for (final Attr attr : pAttrs)
		{
			if (rows.size() >= PairRows.MAX_ROWS)
			{
				break;
			}
			if (merged.contains(attr.name))
			{
				continue;
			}
			final Attr derived = derivedOf.get(attr.name);
			if (derived == null)
			{
				final String left = pValues.left(attr.name);
				final String right = pValues.right(attr.name);
				rows.add(new MatchStats.FieldComparison(attr.label, attr.name, left, right, "", "", "", false, MatchStats.compareValues(left, right)));
				planRow.add(Boolean.valueOf(attr.inPlan));
				plainRow.add(Boolean.valueOf(attr.derivedFrom.isEmpty()));
				continue;
			}
			final String keyLeft = pValues.left(derived.name);
			final String keyRight = pValues.right(derived.name);
			final String rawLeft = pValues.left(attr.name);
			final String rawRight = pValues.right(attr.name);
			if (isBlank(keyLeft) && isBlank(keyRight))
			{
				// the key is empty on both sides (the raw value could not be normalised): the raw values are what there is to compare
				rows.add(new MatchStats.FieldComparison(attr.label, attr.name, rawLeft, rawRight, "", "", "", false, MatchStats.compareValues(rawLeft, rawRight)));
			}
			else
			{
				rows.add(new MatchStats.FieldComparison(attr.label, derived.name, keyLeft, keyRight, rawLeft, rawRight, attr.name, false,
					MatchStats.compareValues(keyLeft, keyRight)));
			}
			planRow.add(Boolean.TRUE);
			plainRow.add(Boolean.TRUE);
		}
		// at a glance: the plan's fields, then the first ordinary fields until there are GLANCE_MIN rows
		final boolean[] glance = new boolean[rows.size()];
		int shown = 0;
		for (int i = 0; i < rows.size(); i++)
		{
			if (planRow.get(i).booleanValue())
			{
				glance[i] = true;
				shown++;
			}
		}
		for (int i = 0; i < rows.size() && shown < PairRows.GLANCE_MIN; i++)
		{
			if (!glance[i] && plainRow.get(i).booleanValue())
			{
				glance[i] = true;
				shown++;
			}
		}
		final List<MatchStats.FieldComparison> out = new ArrayList<MatchStats.FieldComparison>();
		for (int i = 0; i < rows.size(); i++)
		{
			final MatchStats.FieldComparison row = rows.get(i);
			out.add(new MatchStats.FieldComparison(row.label, row.name, row.left, row.right, row.rawLeft, row.rawRight, row.rawName, glance[i], row.verdict));
		}
		return out;
	}

	/** The values of N records, by master field name. */
	public interface MemberValues
	{
		String value(int pMember, String pField);
	}

	/**
	 * The rows of a GROUP card: the same rows as {@link #build} - a derived key standing for its raw field, the labels, the fields at a glance - with one value per member.
	 */
	public static List<MatchStats.GroupRow> buildGroup(final List<Attr> pAttrs, final int pMembers, final MemberValues pValues)
	{
		final List<MatchStats.GroupRow> out = new ArrayList<MatchStats.GroupRow>();
		// one pair-shaped pass per field would lose the per-member values: build the rows on member 0 / 1 for their shape (derived merge, glance flags), then read every member
		final List<MatchStats.FieldComparison> shape = PairRows.build(pAttrs, new Values()
		{
			@Override
			public String left(final String pField)
			{
				return PairRows.nonNull(pValues.value(0, pField));
			}

			@Override
			public String right(final String pField)
			{
				return PairRows.nonNull(pMembers > 1 ? pValues.value(1, pField) : "");
			}
		});
		// a derived key blank on every member is read as its raw field, as in the pair rows: decide per row on ALL members
		final Map<String, Attr> byName = new HashMap<String, Attr>();
		for (final Attr attr : pAttrs)
		{
			byName.put(attr.name, attr);
		}
		for (final MatchStats.FieldComparison row : shape)
		{
			final List<String> values = new ArrayList<String>();
			final List<String> raws = new ArrayList<String>();
			boolean anyKey = false;
			for (int member = 0; member < pMembers; member++)
			{
				if (!isBlank(pValues.value(member, row.name)))
				{
					anyKey = true;
				}
			}
			final boolean derived = !row.rawName.isEmpty();
			for (int member = 0; member < pMembers; member++)
			{
				if (derived && !anyKey)
				{
					values.add(PairRows.nonNull(pValues.value(member, row.rawName)));
					raws.add("");
				}
				else
				{
					values.add(PairRows.nonNull(pValues.value(member, row.name)));
					raws.add(derived ? PairRows.nonNull(pValues.value(member, row.rawName)) : "");
				}
			}
			out.add(new MatchStats.GroupRow(row.label, derived && !anyKey ? row.rawName : row.name, derived && !anyKey ? "" : row.rawName, row.glance, values, raws));
		}
		return out;
	}

	private static String nonNull(final String pValue)
	{
		return pValue == null ? "" : pValue;
	}

	private static boolean isBlank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	/** A row that summarises the child rows of the two records (an address): its name is {@link MatchStats#CHILD_PREFIX} + the child entity. Always shown at a glance. */
	public static MatchStats.FieldComparison childRow(final String pEntity, final String pLabel, final String pLeft, final String pRight)
	{
		return new MatchStats.FieldComparison(pLabel == null || pLabel.trim().isEmpty() ? pEntity : pLabel, MatchStats.CHILD_PREFIX + pEntity, pLeft, pRight, "", "", "", true,
			MatchStats.compareValues(pLeft, pRight));
	}
}
