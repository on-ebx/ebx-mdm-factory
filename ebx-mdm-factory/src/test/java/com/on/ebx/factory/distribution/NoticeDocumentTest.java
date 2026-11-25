package com.on.ebx.factory.distribution;

import java.util.ArrayList;
import java.util.Map;

/** JUnit-free: run the main, a failure throws. */
public final class NoticeDocumentTest
{
	public static void main(final String[] pArgs)
	{
		NoticeDocumentTest.retireWithSurvivor();
		NoticeDocumentTest.retireWithoutSurvivorOrMerge();
		NoticeDocumentTest.deleteNotice();
		NoticeDocumentTest.everyKindSaysWhatItIs();
		NoticeDocumentTest.goldenOnlyPredicate();
		System.out.println("NoticeDocumentTest OK");
	}

	private static void retireWithSurvivor()
	{
		final Map<String, Object> doc = NoticeDocument.retire("347", "692", "MERGE", "5", "2026-10-18T10:00:00Z");
		NoticeDocumentTest.check("order: _change first", new ArrayList<String>(doc.keySet()).get(0).equals("_change"));
		NoticeDocumentTest.check("change", "RETIRE".equals(doc.get("_change")));
		NoticeDocumentTest.check("id", "347".equals(doc.get("id")));
		NoticeDocumentTest.check("retired", Boolean.TRUE.equals(doc.get("retired")));
		NoticeDocumentTest.check("retiredTo", "692".equals(doc.get("retiredTo")));
		NoticeDocumentTest.check("reason and merge id", "MERGE".equals(doc.get("reason")) && "5".equals(doc.get("mergeId")));
		NoticeDocumentTest.check("at", "2026-10-18T10:00:00Z".equals(doc.get("at")));
	}

	private static void retireWithoutSurvivorOrMerge()
	{
		final Map<String, Object> doc = NoticeDocument.retire("692", "  ", null, "", "t");
		NoticeDocumentTest.check("no retiredTo", !doc.containsKey("retiredTo"));
		NoticeDocumentTest.check("no reason", !doc.containsKey("reason"));
		NoticeDocumentTest.check("no mergeId", !doc.containsKey("mergeId"));
		NoticeDocumentTest.check("still a RETIRE", "RETIRE".equals(doc.get("_change")));
	}

	private static void deleteNotice()
	{
		final Map<String, Object> doc = NoticeDocument.delete("12", "t");
		NoticeDocumentTest.check("change", "DELETE".equals(doc.get("_change")));
		NoticeDocumentTest.check("deleted", Boolean.TRUE.equals(doc.get("deleted")));
		NoticeDocumentTest.check("id", "12".equals(doc.get("id")));
		NoticeDocumentTest.check("no retired flag on a delete", !doc.containsKey("retired"));
	}

	/** The one convention: RETIRE, DELETE and the mapped UPSERT all carry _change and the record id. */
	private static void everyKindSaysWhatItIs()
	{
		NoticeDocumentTest.check("the mapped record's member", "_change".equals(DistributionMappingRules.CHANGE_FIELD));
		NoticeDocumentTest.check("the mapped value", "UPSERT".equals(NoticeDocument.UPSERT));
		NoticeDocumentTest.check("the three differ", !"UPSERT".equals("RETIRE") && !"RETIRE".equals("DELETE"));
	}

	private static void goldenOnlyPredicate()
	{
		NoticeDocumentTest.check("a table without Match and Merge: no filter", GoldenOnlySends.predicate(false) == null);
		final String xpath = GoldenOnlySends.predicate(true);
		NoticeDocumentTest.check("merged and deleted are left out", xpath.contains("Linked_Field_State = 2") && xpath.contains("Linked_Field_State = 4"));
		NoticeDocumentTest.check("negated, so a record without metadata passes", xpath.startsWith("not("));
		NoticeDocumentTest.check("golden is live", GoldenOnlySends.isLive(RetireRules.STATE_GOLDEN));
		NoticeDocumentTest.check("pending is live", GoldenOnlySends.isLive(RetireRules.STATE_PENDING_APPROVAL));
		NoticeDocumentTest.check("merged is not", !GoldenOnlySends.isLive(RetireRules.STATE_MERGED));
		NoticeDocumentTest.check("deleted is not", !GoldenOnlySends.isLive(RetireRules.STATE_DELETED));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("NoticeDocumentTest: " + pWhat);
		}
	}
}
