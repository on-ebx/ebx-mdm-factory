package com.on.ebx.factory.ebx;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * Lot M2 (2026-10-17, docs/MATCHING.md section 57): where the child-entity gating sits, read from the sources (each pass needs the match-merge server or a repository, so it
 * cannot run here): the two proposal passes leave a child without a plan, the configure checks a child's own plan before any write and does not count a child that follows its
 * parent as "not accepted", the run does not count it as "not applied", and a configured child plan is never deleted.
 */
public final class ChildPlanGatingSourceTest
{
	private ChildPlanGatingSourceTest()
	{
	}

	private static String read(final String pName) throws Exception
	{
		return new String(Files.readAllBytes(Paths.get(System.getProperty("mdmf.sourceDir", "src/main/java") + "/com/on/ebx/factory/ebx/" + pName)), StandardCharsets.UTF_8);
	}

	public static void main(final String[] pArgs) throws Exception
	{
		final String rules = ChildPlanGatingSourceTest.read("MatchPlanSuggester.java");
		ChildPlanGatingSourceTest.check("the rule pass skips a child before it looks at any plan", rules.indexOf("parentsByChild.get(proposal.entityName)") > 0
			&& rules.indexOf("parentsByChild.get(proposal.entityName)") < rules.indexOf("final Adaptation existing = MatchPlanSuggester.findMatchPlan"));
		final String llm = ChildPlanGatingSourceTest.read("MatchPlanLlmSuggester.java");
		final int sweepOnly = llm.indexOf("if (MatchPlanLlmSuggester.blank(pEntity))");
		ChildPlanGatingSourceTest.check("the model pass drops the children from the sweep only (a request naming the entity keeps it), before the entity filter",
			sweepOnly > 0 && sweepOnly < llm.indexOf("filterToEntity(promoted, pEntity)"));

		final String configurator = ChildPlanGatingSourceTest.read("MatchingConfigurator.java");
		final int one = configurator.indexOf("private static MatchingConfigurator.ConfigureDecision configureOne(");
		final int leafGuard = configurator.indexOf("ChildEntityMatching.matchLeafRefusal(", one);
		final int firstMcp = configurator.indexOf("McpServers.client(pSession, pFactory, McpServers.MATCH_MERGE)", one);
		ChildPlanGatingSourceTest.check("a child's MATCH leaf is refused before any call to the add-on", leafGuard > 0 && leafGuard < firstMcp);
		final int memberGuard = configurator.indexOf("ChildEntityMatching.memberRunRefusal(", one);
		final int firstWrite = configurator.indexOf("switch (decision)", one);
		ChildPlanGatingSourceTest.check("the business object member refusal comes before the first write", memberGuard > 0 && memberGuard < firstWrite);
		final int loop = configurator.indexOf("ChildEntityMatching.parentsByChild(masterModel);");
		ChildPlanGatingSourceTest.check("configure lists a child without a plan under followsParent, an applied child plan under childKeptAsIs, never under notAccepted",
			loop > 0 && configurator.indexOf("followsParent.add(ChildEntityMatching.followsText(", loop) > loop && configurator.indexOf("childKeptAsIs.add(", loop) > loop
				&& configurator.indexOf("followsParent.add(", loop) < configurator.indexOf("notAccepted.add(", loop));
		ChildPlanGatingSourceTest.check("a configured child plan is left alone: no policy is deleted anywhere in the configure", !configurator.contains("delete_matching_config"));
		ChildPlanGatingSourceTest.check("the run counts a child without a plan under followsParent", configurator.contains("followsParentInRun.add(")
			&& configurator.indexOf("followsParentInRun.add(") < configurator.indexOf("notApplied.add(entityName"));
		ChildPlanGatingSourceTest.check("a preview that could not be removed reaches the job", configurator.contains("pPreviewsLeft.add(") && configurator.contains("\"previewsLeft\""));
		ChildPlanGatingSourceTest.check("the pre-generate removal no longer records true whatever remove answered", !configurator.contains("detail.put(\"previewRemovedBeforeGenerate\", true)"));

		final String reviews = ChildPlanGatingSourceTest.read("ProposalReviews.java");
		ChildPlanGatingSourceTest.check("accepting a match plan and switching its automatic merge on both ask the child rule",
			reviews.split("ReviewRules\\.canAcceptMatchPlan\\(", -1).length - 1 == 2);
		System.out.println("ChildPlanGatingSourceTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
