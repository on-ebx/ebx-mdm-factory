package com.on.ebx.factory.review;

import java.util.List;

/**
 * Formats a canonical (language-model) {@code StagingEntity}'s members, e.g. {@code "Supplier (ERP),
 * Account (CRM)"}, for the review screen's proposition text ({@code review.prop.entity.unifies}). Reads
 * from the real, structured {@code StagingEntity.memberEntities} foreign key now that one exists
 * (2026-09-20 coordinator review: "prose gets truncated, translated and edited; logic must not depend on
 * it") - {@code ProposalReviews} resolves each member's name and source system from EBX and hands the
 * pairs here; this class only formats them. Pure: no EBX class, unit-tested as is.
 */
public final class ReviewEntityFormat
{
	private ReviewEntityFormat()
	{
	}

	/** One resolved member of a canonical entity: its rule-proposed name, and the source system it comes from (null when it could not be resolved). */
	public static final class Member
	{
		public final String name;
		public final String sourceSystem;

		public Member(final String pName, final String pSourceSystem)
		{
			this.name = pName;
			this.sourceSystem = pSourceSystem;
		}
	}

	/**
	 * @return e.g. {@code "Supplier (ERP), Account (CRM)"} - a member whose source system could not be
	 * resolved shows its bare name; null when {@code pMembers} is empty (not a canonical entity, or its
	 * members are not yet resolved), so the caller falls back to a different proposition text instead of
	 * showing an empty "Unifies " sentence.
	 */
	public static String unifiesText(final List<Member> pMembers)
	{
		if (pMembers == null || pMembers.isEmpty())
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < pMembers.size(); i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			final Member member = pMembers.get(i);
			out.append(member.name);
			if (member.sourceSystem != null && !member.sourceSystem.trim().isEmpty())
			{
				out.append(" (").append(member.sourceSystem.trim()).append(')');
			}
		}
		return out.toString();
	}
}
