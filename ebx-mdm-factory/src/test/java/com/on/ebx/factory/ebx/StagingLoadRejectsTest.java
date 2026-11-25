package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

import com.on.ebx.factory.rejects.RejectCategory;
import com.on.ebx.factory.rejects.RejectReason;
import com.on.ebx.factory.transfer.TransferPlan;

/**
 * JUnit-free: run the main, a failure throws. Staging load and rejects lot (2026-10-17): the loader's decisions that need no EBX session - a child row of a parent EBX refused is
 * rejected ("parent rejected: ..."), never written (no orphan); the refused landing rows are marked REJECTED; the loader's own checks are coded reasons; a child entity counts the
 * rows it was asked to build as "read". What needs a repository (the writes) is pinned by reading the engine source.
 */
public final class StagingLoadRejectsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		StagingLoadRejectsTest.orphansAreRejectedNotWritten();
		StagingLoadRejectsTest.aRefusedChildPassesTheRootCauseOnToItsOwnChildren();
		StagingLoadRejectsTest.unchangedChildrenAreLeftAlone();
		StagingLoadRejectsTest.refusedLandingRowsAreRejected();
		StagingLoadRejectsTest.loaderChecksAreCodedReasons();
		StagingLoadRejectsTest.engineSource();
		System.out.println("StagingLoadRejectsTest OK");
	}

	private static StagingLoader.RowPlan row(final String pId, final String pParentKey)
	{
		final Map<String, Object> fields = new LinkedHashMap<String, Object>();
		fields.put("city", "Paris");
		final StagingLoader.RowPlan row = new StagingLoader.RowPlan("LOYALTY", pId, fields, "L1", new Date(0L), "landing-" + pId, "hash");
		row.parentKey = pParentKey;
		return row;
	}

	private static RejectReason dateReason()
	{
		return RejectReason.convert(RejectReason.CONVERT_DATE, "birthDate", "date_naissance", "10/06/1963", "raw");
	}

	private static void orphansAreRejectedNotWritten()
	{
		final StagingLoader.Refusals refused = new StagingLoader.Refusals();
		refused.byStagingKey.put("LOYALTY|C1", StagingLoadRejectsTest.dateReason());
		refused.landingRowKeys.add("landing-C1");
		final StagingLoader.FeedBuffers children = new StagingLoader.FeedBuffers(0);
		children.stage.add(StagingLoadRejectsTest.row("C1#0", "LOYALTY|C1"));
		children.stage.add(StagingLoadRejectsTest.row("C2#0", "LOYALTY|C2"));
		children.stage.add(StagingLoadRejectsTest.row("C1#1", "LOYALTY|C1"));
		StagingLoader.rejectOrphans("Customer", refused, children);
		StagingLoadRejectsTest.check("only the child of a refused parent leaves the write list: " + children.stage.size(),
			children.stage.size() == 1 && "C2#0".equals(children.stage.get(0).sourceId));
		StagingLoadRejectsTest.check("both children of the refused parent are rejects: " + children.reject.size(), children.reject.size() == 2);
		final StagingLoader.RejectPlan reject = children.reject.get(0);
		StagingLoadRejectsTest.check("the reject keeps the child's own identity",
			"LOYALTY".equals(reject.sourceSystem) && "C1#0".equals(reject.sourceId) && "landing-C1#0".equals(reject.landingRowKey));
		StagingLoadRejectsTest.check("its reason is the parent's: " + reject.english(), RejectCategory.PARENT_REJECTED.equals(reject.first().category)
			&& reject.english().contains("Parent Customer row rejected") && reject.english().contains("birthDate") && reject.english().contains("10/06/1963"));
		StagingLoadRejectsTest.check("the fix is the parent's fix", reject.first().fix(java.util.Locale.FRENCH).contains("date_naissance"));
		// nothing refused: the buffers are untouched
		final StagingLoader.FeedBuffers untouched = new StagingLoader.FeedBuffers(0);
		untouched.stage.add(StagingLoadRejectsTest.row("C9#0", "LOYALTY|C9"));
		StagingLoader.rejectOrphans("Customer", new StagingLoader.Refusals(), untouched);
		StagingLoadRejectsTest.check("no refusal, no change", untouched.stage.size() == 1 && untouched.reject.isEmpty());
		StagingLoader.rejectOrphans("Customer", refused, untouched);
		StagingLoadRejectsTest.check("a child of another parent is kept", untouched.stage.size() == 1 && untouched.reject.isEmpty());
	}

	private static void aRefusedChildPassesTheRootCauseOnToItsOwnChildren()
	{
		final RejectReason root = RejectReason.parentRejected("Customer", StagingLoadRejectsTest.dateReason());
		final StagingLoader.Refusals refused = new StagingLoader.Refusals();
		refused.byStagingKey.put("LOYALTY|C1#0", root); // the address was itself rejected because of the customer
		final StagingLoader.FeedBuffers grandChildren = new StagingLoader.FeedBuffers(0);
		grandChildren.stage.add(StagingLoadRejectsTest.row("C1#0#phone", "LOYALTY|C1#0"));
		StagingLoader.rejectOrphans("CustomerAddress", refused, grandChildren);
		StagingLoadRejectsTest.check("the root cause is passed on, not wrapped again: " + grandChildren.reject.get(0).english(),
			grandChildren.reject.get(0).first().signature().equals(root.signature()) && !grandChildren.reject.get(0).english().contains("CustomerAddress"));
	}

	private static void unchangedChildrenAreLeftAlone()
	{
		final StagingLoader.Refusals refused = new StagingLoader.Refusals();
		refused.byStagingKey.put("LOYALTY|C1", StagingLoadRejectsTest.dateReason());
		final StagingLoader.FeedBuffers children = new StagingLoader.FeedBuffers(0);
		children.unchanged.add(StagingLoadRejectsTest.row("C1#0", "LOYALTY|C1"));
		StagingLoader.rejectOrphans("Customer", refused, children);
		StagingLoadRejectsTest.check("an unchanged child (already in staging) is neither written nor rejected", children.unchanged.size() == 1 && children.reject.isEmpty());
	}

	private static void refusedLandingRowsAreRejected()
	{
		final StagingLoader.Refusals refused = new StagingLoader.Refusals();
		refused.landingRowKeys.add("landing-C1");
		final Map<String, String> status = new LinkedHashMap<String, String>();
		status.put("landing-C1", "STAGED");
		status.put("landing-C2", "STAGED");
		StagingLoader.markRefusedLandingRows(status, refused);
		StagingLoadRejectsTest.check("a landing row whose root row EBX refused is REJECTED, the others stay as decided: " + status,
			"REJECTED".equals(status.get("landing-C1")) && "STAGED".equals(status.get("landing-C2")));
		StagingLoader.markRefusedLandingRows(status, StagingLoader.Refusals.NONE);
		StagingLoadRejectsTest.check("nothing refused, nothing changed", status.size() == 2);
	}

	private static TransferPlan.FieldPlan field(final String pName, final String pColumn, final Integer pMax, final boolean pMandatory) throws Exception
	{
		final Constructor<TransferPlan.FieldPlan> constructor = TransferPlan.FieldPlan.class.getDeclaredConstructor(String.class, String.class, Integer.class, boolean.class,
			String.class, String.class, String.class, String.class, boolean.class, boolean.class, java.util.List.class, java.util.List.class);
		constructor.setAccessible(true);
		return constructor.newInstance(pName, "STRING", pMax, Boolean.valueOf(pMandatory), "", pColumn, null, null, Boolean.FALSE, Boolean.FALSE, Collections.emptyList(),
			Collections.emptyList());
	}

	private static void loaderChecksAreCodedReasons() throws Exception
	{
		final TransferPlan.FieldPlan email = StagingLoadRejectsTest.field("email", "mail", Integer.valueOf(5), true);
		final RejectReason mandatory = StagingLoader.constraintReason(email, null);
		StagingLoadRejectsTest.check("a mandatory field that is empty", mandatory != null && RejectReason.MANDATORY.equals(mandatory.code)
			&& RejectCategory.DATA_CHECK.equals(mandatory.category) && "email".equals(mandatory.field()) && "mail".equals(mandatory.column()));
		final RejectReason tooLong = StagingLoader.constraintReason(email, "abcdefgh");
		StagingLoadRejectsTest.check("a value longer than the field: " + (tooLong == null ? null : tooLong.english()),
			tooLong != null && tooLong.english().contains("8 characters") && tooLong.english().contains("maximum is 5"));
		StagingLoadRejectsTest.check("a value that fits is no reason", StagingLoader.constraintReason(email, "abcde") == null);
		StagingLoadRejectsTest.check("an optional empty field is no reason", StagingLoader.constraintReason(StagingLoadRejectsTest.field("note", "n", null, false), null) == null);
		StagingLoadRejectsTest.check("an empty string is not 'empty' for the mandatory check (as before)", StagingLoader.constraintReason(email, "") == null);
	}

	private static void engineSource() throws IOException
	{
		final String loader = new String(Files.readAllBytes(Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"))
			.resolve("com/on/ebx/factory/ebx/StagingLoader.java")), StandardCharsets.UTF_8);
		StagingLoadRejectsTest.check("a row rejected while it was planned rejects the rows computed from it (no orphan)",
			loader.contains("pParentRejected != null ? pParentRejected : RejectReason.parentRejected(pContext.entityName, reasons.get(0))"));
		StagingLoadRejectsTest.check("a child of a rejected parent is rejected, never written, even when its own checks pass",
			loader.contains("pBuffers.reject.add(new RejectPlan(pLandingRowKey, pSourceSystem, pSourceId, pLoadId, pParentRejected));"));
		StagingLoadRejectsTest.check("the children's write list is filtered before they are flushed",
			loader.indexOf("StagingLoader.rejectOrphans(pContext.entityName, refused,") < loader.indexOf("StagingLoader.flushTree(pSession, pFactory, pProjectId, pEntityIdByName, pContext.children.get(i)"));
		StagingLoadRejectsTest.check("a child entity counts the rows it was asked to build as read", loader.contains("childBuffers.read++;") && loader.contains("pCounters.read += pBuffers.read;"));
		StagingLoadRejectsTest.check("the read counter of a root feed is still counted once, per landing row", loader.contains("pCountersByEntity.get(rootFeed.entityName).read++;"));
		StagingLoadRejectsTest.check("the cause of every reject is counted per entity and reported",
			loader.contains("pCounters.rejectedByCategory.put(category") && loader.contains("entityDetail.putObject(\"rejectedBy\")"));
		StagingLoadRejectsTest.check("the cause, the signature and the raw detail are written on the reject", loader.contains("FactoryPaths.TransferReject.CATEGORY")
			&& loader.contains("FactoryPaths.TransferReject.REASON_CODE") && loader.contains("FactoryPaths.TransferReject.DETAIL"));
		StagingLoadRejectsTest.check("the EBX message is kept whole enough to be classified",
			loader.contains("RejectText.cut(RejectText.detail(result.getExceptionFullMessage(Locale.ENGLISH)), 1500)"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
