package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws (same convention as every other test in this codebase).
 * <p>
 * {@link #canonicalEntity_twoMembers_bothSourceKeysResolvedByName} is the exact live case
 * (`docs/MATCHING.md` &sect;19.10, 2026-09-23): {@code Company} (canonical, members {@code Supplier}/{@code
 * Account}) - {@code Supplier.supplierId}/{@code Account.accountId} are each the member's own business-key
 * attribute, {@code Company} itself carries eligible attributes named {@code supplierId}/{@code accountId}
 * but its OWN {@code businessKey} flag on them is {@code false} - {@link SourceKeyResolver#resolve} must
 * still report BOTH, resolved by same-name mapping from the members.
 */
public final class SourceKeyResolverTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		SourceKeyResolverTest.isEligibleSourceKey_allFourConditionsRequired();
		SourceKeyResolverTest.leafEntity_ownEligibleAttribute_resolved();
		SourceKeyResolverTest.leafEntity_noMembers_noEligibleOwnAttribute_empty();
		SourceKeyResolverTest.canonicalEntity_twoMembers_bothSourceKeysResolvedByName();
		SourceKeyResolverTest.canonicalEntity_memberKeyWithNoSameNamedCanonicalAttribute_skippedWithNote();
		SourceKeyResolverTest.canonicalEntity_sameNamedCanonicalAttributeIsAForeignKey_skippedWithNote();
		SourceKeyResolverTest.canonicalEntity_ownEligibleAttributePlusMemberResolved_union();
		SourceKeyResolverTest.canonicalEntity_neverDuplicatesWhenBothOwnAndMemberAgree();
		System.out.println("SourceKeyResolverTest OK");
	}

	// ------------------------------------------------------------------------------------------- isEligibleSourceKey

	private static void isEligibleSourceKey_allFourConditionsRequired()
	{
		SourceKeyResolverTest.check("businessKey+notPK+notFK+accepted: eligible",
			SourceKeyResolver.isEligibleSourceKey(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true)));
		SourceKeyResolverTest.check("not businessKey: not eligible",
			!SourceKeyResolver.isEligibleSourceKey(new SourceKeyResolver.AttributeInfo("supplierId", false, false, false, true)));
		SourceKeyResolverTest.check("also primaryKey: not eligible (technical identity's own fixed rule covers it)",
			!SourceKeyResolver.isEligibleSourceKey(new SourceKeyResolver.AttributeInfo("sourceId", true, true, false, true)));
		SourceKeyResolverTest.check("also a foreign key: not eligible (promoted as a ...Ref field, name not reproducible here)",
			!SourceKeyResolver.isEligibleSourceKey(new SourceKeyResolver.AttributeInfo("supplierRef", true, false, true, true)));
		SourceKeyResolverTest.check("not accepted-like: not eligible",
			!SourceKeyResolver.isEligibleSourceKey(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, false)));
	}

	// ------------------------------------------------------------------------------------------------------- leaf

	private static void leafEntity_ownEligibleAttribute_resolved()
	{
		final List<SourceKeyResolver.AttributeInfo> own = Arrays
			.asList(new SourceKeyResolver.AttributeInfo("contactId", true, false, false, true), new SourceKeyResolver.AttributeInfo("sourceId", true, true, false, true));
		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(own, new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>());
		SourceKeyResolverTest.check("only the eligible one is resolved", result.fieldNames.equals(Arrays.asList("contactId")));
		SourceKeyResolverTest.check("a pure leaf entity (no members) has no notes at all", result.notes.isEmpty());
	}

	private static void leafEntity_noMembers_noEligibleOwnAttribute_empty()
	{
		final List<SourceKeyResolver.AttributeInfo> own = Arrays.asList(new SourceKeyResolver.AttributeInfo("name", false, false, false, true));
		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(own, new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>());
		SourceKeyResolverTest.check("nothing eligible: empty, not an error", result.fieldNames.isEmpty());
	}

	// -------------------------------------------------------------------------------------------------- canonical

	/** The exact live case, `docs/MATCHING.md` &sect;19.10 - see class Javadoc. */
	private static void canonicalEntity_twoMembers_bothSourceKeysResolvedByName()
	{
		// Company's OWN attributes: supplierId/accountId exist but are NOT flagged businessKey (measured live) -
		// still eligible in every OTHER respect, so the same-name mapping can actually land on them.
		final List<SourceKeyResolver.AttributeInfo> companyOwn = Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", false, false, false, true),
			new SourceKeyResolver.AttributeInfo("accountId", false, false, false, true), new SourceKeyResolver.AttributeInfo("sourceId", true, true, false, true));
		final Map<String, List<SourceKeyResolver.AttributeInfo>> members = new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>();
		members.put("Supplier", Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true)));
		members.put("Account", Arrays.asList(new SourceKeyResolver.AttributeInfo("accountId", true, false, false, true)));

		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(companyOwn, members);
		SourceKeyResolverTest.check("both member source keys resolved onto the canonical attributes of the same name",
			result.fieldNames.contains("supplierId") && result.fieldNames.contains("accountId") && result.fieldNames.size() == 2);
		SourceKeyResolverTest.check("one explanatory note per resolved member key", result.notes.size() == 2);
		SourceKeyResolverTest.check("the notes name the resolution mechanism (same-name matching, no unification field)",
			result.notes.get(0).contains("matched by NAME") && result.notes.get(1).contains("matched by NAME"));
	}

	private static void canonicalEntity_memberKeyWithNoSameNamedCanonicalAttribute_skippedWithNote()
	{
		final List<SourceKeyResolver.AttributeInfo> companyOwn = new ArrayList<SourceKeyResolver.AttributeInfo>();
		companyOwn.add(new SourceKeyResolver.AttributeInfo("name", false, false, false, true));
		final Map<String, List<SourceKeyResolver.AttributeInfo>> members = new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>();
		members.put("Supplier", Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true)));

		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(companyOwn, members);
		SourceKeyResolverTest.check("nothing resolved: the canonical entity never even carries that attribute", result.fieldNames.isEmpty());
		SourceKeyResolverTest.check("but the gap is explained in a note", result.notes.size() == 1 && result.notes.get(0).contains("no same-named attribute"));
	}

	private static void canonicalEntity_sameNamedCanonicalAttributeIsAForeignKey_skippedWithNote()
	{
		final List<SourceKeyResolver.AttributeInfo> companyOwn = Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", false, false, true, true));
		final Map<String, List<SourceKeyResolver.AttributeInfo>> members = new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>();
		members.put("Supplier", Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true)));

		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(companyOwn, members);
		SourceKeyResolverTest.check("the canonical attribute exists by name but is itself ineligible (a foreign key here): not resolved",
			result.fieldNames.isEmpty());
		SourceKeyResolverTest.check("explained, not silently dropped", result.notes.size() == 1 && result.notes.get(0).contains("not itself eligible"));
	}

	private static void canonicalEntity_ownEligibleAttributePlusMemberResolved_union()
	{
		final List<SourceKeyResolver.AttributeInfo> companyOwn = Arrays.asList(new SourceKeyResolver.AttributeInfo("groupErpCode", true, false, false, true),
			new SourceKeyResolver.AttributeInfo("supplierId", false, false, false, true));
		final Map<String, List<SourceKeyResolver.AttributeInfo>> members = new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>();
		members.put("Supplier", Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true)));

		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(companyOwn, members);
		SourceKeyResolverTest.check("both the canonical entity's own directly-flagged key AND the member-resolved one are present",
			result.fieldNames.contains("groupErpCode") && result.fieldNames.contains("supplierId") && result.fieldNames.size() == 2);
	}

	private static void canonicalEntity_neverDuplicatesWhenBothOwnAndMemberAgree()
	{
		// defensive: if a canonical attribute were EVER directly flagged businessKey=true itself (not the live
		// shape, but not assumed impossible either), the member resolution for the same name must not add it twice.
		final List<SourceKeyResolver.AttributeInfo> companyOwn = Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true));
		final Map<String, List<SourceKeyResolver.AttributeInfo>> members = new LinkedHashMap<String, List<SourceKeyResolver.AttributeInfo>>();
		members.put("Supplier", Arrays.asList(new SourceKeyResolver.AttributeInfo("supplierId", true, false, false, true)));

		final SourceKeyResolver.Result result = SourceKeyResolver.resolve(companyOwn, members);
		SourceKeyResolverTest.check("never duplicated", result.fieldNames.size() == 1 && "supplierId".equals(result.fieldNames.get(0)));
		SourceKeyResolverTest.check("no note added when the own leaf rule already covered it (nothing new to explain)", result.notes.isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
