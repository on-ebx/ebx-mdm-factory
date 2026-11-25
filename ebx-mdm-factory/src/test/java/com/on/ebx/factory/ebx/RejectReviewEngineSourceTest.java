package com.on.ebx.factory.ebx;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * JUnit-free: run the main, a failure throws. Waves 10 (audit F-38 and F-30): a structural check of the engine sources the offline suite cannot run (they need an EBX
 * session) - the steward right and the comment are asked by the engine whoever calls it, the reads are SQL aggregates, a new rejection reopens an acknowledged reject,
 * the retention reaches acknowledged rejects, and the intake chain records who it runs for. The source folder comes from {@code -Dmdmf.sourceDir}.
 */
public final class RejectReviewEngineSourceTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).resolve("com/on/ebx/factory");
		final String review = RejectReviewEngineSourceTest.read(root.resolve("ebx/RejectReview.java"));
		RejectReviewEngineSourceTest.check("acknowledging asks STEWARD in the engine", review.contains("FactoryAccess.require(pSession, FactoryRight.STEWARD, \"acknowledging transfer rejects\")"));
		RejectReviewEngineSourceTest.check("reopening asks STEWARD in the engine", review.contains("FactoryAccess.require(pSession, FactoryRight.STEWARD, \"reopening transfer rejects\")"));
		RejectReviewEngineSourceTest.check("a comment is required to acknowledge", review.contains("A comment is required to acknowledge a transfer reject."));
		RejectReviewEngineSourceTest.check("the user and the time are recorded", review.contains("FactoryPaths.TransferReject.REVIEWED_BY") && review.contains("FactoryPaths.TransferReject.REVIEWED_AT")
			&& review.contains("Actor.current(pSession).userId"));
		RejectReviewEngineSourceTest.check("the reads are SQL, run as the caller", review.contains("pFactory.createQuery(pSql)") && review.contains("query.setSession(pSession)"));
		RejectReviewEngineSourceTest.check("a resolved row is never touched by an action", review.contains("Boolean.TRUE.equals(row.get(FactoryPaths.TransferReject.RESOLVED))"));
		RejectReviewEngineSourceTest.check("the bulk acknowledge forces the open status", review.contains("open.status = RejectFilter.OPEN"));
		RejectReviewEngineSourceTest.check("the export streams pages, not the whole list", review.contains("EXPORT_PAGE") && review.contains("RejectCsv.line(row)"));
		final String cockpit = RejectReviewEngineSourceTest.read(root.resolve("ebx/ui/FactoryCockpitUserService.java"));
		RejectReviewEngineSourceTest.check("the cockpit's summary is the aggregate query, its sample is 10 rows", cockpit.contains("RejectReview.summary(") && cockpit.contains("REJECTS_READ_CAP = 10"));
		for (final String loader : new String[] { "ebx/StagingLoader.java", "ebx/MasterLoader.java" })
		{
			RejectReviewEngineSourceTest.check(loader + " opens a reject again when it rejects the row again",
				RejectReviewEngineSourceTest.read(root.resolve(loader)).contains("value.setValue(Boolean.FALSE, FactoryPaths.TransferReject.ACKNOWLEDGED)"));
		}
		final String retention = RejectReviewEngineSourceTest.read(root.resolve("ebx/FactoryRetention.java"));
		RejectReviewEngineSourceTest.check("the retention uses the predicate that reaches acknowledged rejects", retention.contains("FactoryRetention.REJECT_PURGE_PREDICATE,"));
		final String store = RejectReviewEngineSourceTest.read(root.resolve("ebx/IntakeStore.java"));
		RejectReviewEngineSourceTest.check("an intake chain records who it runs for", store.contains("FactoryPaths.IntakeChain.STARTED_BY"));
		RejectReviewEngineSourceTest.check("the engine passes the person who switched the intake on",
			RejectReviewEngineSourceTest.read(root.resolve("ebx/IntakeEngine.java")).contains("IntakeStore.text(pProject, FactoryPaths.Project.INTAKE_ENABLED_BY)"));
		final String service = RejectReviewEngineSourceTest.read(root.resolve("ebx/ui/TransferRejectReviewUserService.java"));
		RejectReviewEngineSourceTest.check("the CSV request asks VIEW again", service.contains("FactoryAccess.has(caller, FactoryRight.VIEW)"));
		RejectReviewEngineSourceTest.check("an action asks STEWARD before anything else", service.contains("FactoryAccess.has(pCaller, FactoryRight.STEWARD)"));
		// staging load rejects (2026-10-17)
		RejectReviewEngineSourceTest.check("the groups of reasons are one aggregate query, run as the caller", review.contains("RejectSql.groups(pProjectCode, pFilter, pLimit)")
			&& review.contains("RejectSql.groupCount(pProjectCode, pFilter)"));
		RejectReviewEngineSourceTest.check("the service reads the kind of reason from the form and from the CSV request", service.contains("pContext.getParameter(\"mdmfRjCode\")")
			&& service.contains("pContext.getParameter(\"code\")") && service.contains("query.add(\"code\", this.filter.reasonCode)"));
		RejectReviewEngineSourceTest.check("the screen's back link follows where it was opened from", service.contains("FactoryHtml.originBackLinkScript(FactoryHtml.userId(this.session), \"#\" + RejectReviewPage.BACK_ID)"));
		RejectReviewEngineSourceTest.check("the cockpit words the coded reasons in the viewer's language", cockpit.contains("RejectReasons.read("));
		System.out.println("RejectReviewEngineSourceTest OK");
	}

	private static String read(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
