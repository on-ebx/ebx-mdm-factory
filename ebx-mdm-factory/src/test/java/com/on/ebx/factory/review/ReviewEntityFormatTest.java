package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ReviewEntityFormatTest
{
	public static void main(final String[] pArgs)
	{
		ReviewEntityFormatTest.twoMembersWithSourceSystems();
		ReviewEntityFormatTest.memberWithoutSourceSystemShowsBareName();
		ReviewEntityFormatTest.emptyOrNullMembersReturnNull();
		System.out.println("ReviewEntityFormatTest OK");
	}

	/** The real demo (PLAN.md, 2026-09-20): Company unifies Supplier (ERP) and Account (CRM). */
	private static void twoMembersWithSourceSystems()
	{
		final List<ReviewEntityFormat.Member> members = Arrays.asList(new ReviewEntityFormat.Member("Supplier", "ERP"), new ReviewEntityFormat.Member("Account", "CRM"));
		ReviewEntityFormatTest.check("both members shown with their source system", "Supplier (ERP), Account (CRM)".equals(ReviewEntityFormat.unifiesText(members)));
	}

	private static void memberWithoutSourceSystemShowsBareName()
	{
		final List<ReviewEntityFormat.Member> members = new ArrayList<ReviewEntityFormat.Member>();
		members.add(new ReviewEntityFormat.Member("Supplier", "ERP"));
		members.add(new ReviewEntityFormat.Member("Orphan", null));
		members.add(new ReviewEntityFormat.Member("Blank", "   "));
		ReviewEntityFormatTest.check("an unresolved or blank source system falls back to the bare name",
			"Supplier (ERP), Orphan, Blank".equals(ReviewEntityFormat.unifiesText(members)));
	}

	private static void emptyOrNullMembersReturnNull()
	{
		ReviewEntityFormatTest.check("empty list -> null (not an empty \"Unifies \" sentence)", ReviewEntityFormat.unifiesText(new ArrayList<ReviewEntityFormat.Member>()) == null);
		ReviewEntityFormatTest.check("null list -> null", ReviewEntityFormat.unifiesText(null) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
