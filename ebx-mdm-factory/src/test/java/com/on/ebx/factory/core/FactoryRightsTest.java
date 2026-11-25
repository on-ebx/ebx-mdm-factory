package com.on.ebx.factory.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * JUnit-free: run the main, a failure throws. Audit F-02: the order of the rights, and a TABLE test - every wizard action,
 * every proposal-review action, every user service and every background job kind of the sources has a decided right
 * (an entry point nobody decided asks the administrator), and what must stay with the administrator does. The REST routes
 * are checked by {@code rest.RestRouteRightsTest}. The source folder comes from {@code -Dmdmf.sourceDir}.
 */
public final class FactoryRightsTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		FactoryRightsTest.order();
		FactoryRightsTest.unknownIsAdministrator();
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		FactoryRightsTest.everyWizardActionIsDecided(root);
		FactoryRightsTest.everyReviewActionIsDecided(root);
		FactoryRightsTest.everyServiceIsDecided(root);
		FactoryRightsTest.everyJobKindIsDecided(root);
		FactoryRightsTest.whatStaysAdministrator();
		System.out.println("FactoryRightsTest OK");
	}

	private static void order()
	{
		FactoryRightsTest.check("DESIGN covers STEWARD and VIEW", FactoryRight.DESIGN.covers(FactoryRight.STEWARD) && FactoryRight.DESIGN.covers(FactoryRight.VIEW));
		FactoryRightsTest.check("STEWARD covers VIEW, not DESIGN", FactoryRight.STEWARD.covers(FactoryRight.VIEW) && !FactoryRight.STEWARD.covers(FactoryRight.DESIGN));
		FactoryRightsTest.check("VIEW covers neither STEWARD nor DESIGN", !FactoryRight.VIEW.covers(FactoryRight.STEWARD) && !FactoryRight.VIEW.covers(FactoryRight.DESIGN));
		FactoryRightsTest.check("DESIGN does not cover ADMINISTRATOR", !FactoryRight.DESIGN.covers(FactoryRight.ADMINISTRATOR));
		FactoryRightsTest.check("ADMINISTRATOR covers everything", FactoryRight.ADMINISTRATOR.covers(FactoryRight.DESIGN));
		FactoryRightsTest.check("nothing covers a null need", !FactoryRight.ADMINISTRATOR.covers(null));
		FactoryRightsTest.check("no right passes AUTHENTICATED only", FactoryRight.allows(null, FactoryRight.AUTHENTICATED) && !FactoryRight.allows(null, FactoryRight.VIEW));
		FactoryRightsTest.check("grantable: VIEW, STEWARD, DESIGN", FactoryRight.grantableNamed("VIEW") == FactoryRight.VIEW
			&& FactoryRight.grantableNamed("STEWARD") == FactoryRight.STEWARD && FactoryRight.grantableNamed(" DESIGN ") == FactoryRight.DESIGN);
		FactoryRightsTest.check("ADMINISTRATOR and AUTHENTICATED are never grantable by a row",
			FactoryRight.grantableNamed("ADMINISTRATOR") == null && FactoryRight.grantableNamed("AUTHENTICATED") == null && FactoryRight.grantableNamed(null) == null
				&& FactoryRight.grantableNamed("design") == null);
	}

	private static void unknownIsAdministrator()
	{
		FactoryRightsTest.check("an unknown wizard action asks the administrator", FactoryRights.wizardAction("SOMETHING_NEW") == FactoryRight.ADMINISTRATOR);
		FactoryRightsTest.check("an unknown review action asks the administrator", FactoryRights.reviewAction("SOMETHING_NEW") == FactoryRight.ADMINISTRATOR);
		FactoryRightsTest.check("an unknown service asks the administrator", FactoryRights.service("SomethingNew") == FactoryRight.ADMINISTRATOR);
		FactoryRightsTest.check("an unknown route asks the administrator", FactoryRights.route("DELETE /projects/{code}") == FactoryRight.ADMINISTRATOR);
		FactoryRightsTest.check("a post with no action is a plain re-render (VIEW)", FactoryRights.wizardAction(null) == FactoryRight.VIEW && FactoryRights.reviewAction("") == FactoryRight.VIEW);
	}

	/** Every {@code "X".equals(action)} of the wizard's dispatcher (handle and handleIntake) has an entry; every entry is a real action. */
	private static void everyWizardActionIsDecided(final Path pRoot) throws IOException
	{
		final String source = FactoryRightsTest.read(pRoot.resolve("com/on/ebx/factory/ebx/ui/ProjectWizardUserService.java"));
		final Set<String> actions = FactoryRightsTest.equalsLiterals(source, "action", "pAction");
		FactoryRightsTest.check("the dispatcher was found (" + actions.size() + " actions)", actions.size() >= 100);
		for (final String action : actions)
		{
			FactoryRightsTest.check("wizard action " + action + " has a decided right", FactoryRights.wizardActions().containsKey(action));
		}
		for (final String decided : FactoryRights.wizardActions().keySet())
		{
			FactoryRightsTest.check("decided wizard action " + decided + " exists", decided.isEmpty() || actions.contains(decided));
		}
		FactoryRightsTest.check("handle() asks FactoryRights.wizardAction before any action",
			source.indexOf("FactoryRights.wizardAction(action)") > 0 && source.indexOf("FactoryRights.wizardAction(action)") < source.indexOf("\"GOTO_STEP\".equals(action)"));
		FactoryRightsTest.check("the wizard no longer refuses every postback to a non-administrator",
			FactoryRightsTest.between(source, "\tvoid handle(", "final String action = ").indexOf("Profile.ADMINISTRATOR") < 0);
		// every rendered data-mdmf-action of the wizard's own pages is a decided action too (a button nobody decided would render disabled for everyone but the administrator)
		final Set<String> rendered = new LinkedHashSet<String>();
		for (final String page : new String[] { "com/on/ebx/factory/wizard/WizardPage.java", "com/on/ebx/factory/wizard/IntakeScreens.java" })
		{
			final Matcher m = Pattern.compile("data-mdmf-action=\\\\\"([A-Z][A-Z0-9_]*)\\\\\"").matcher(FactoryRightsTest.read(pRoot.resolve(page)));
			while (m.find())
			{
				rendered.add(m.group(1));
			}
		}
		FactoryRightsTest.check("rendered actions found (" + rendered.size() + ")", rendered.size() >= 40);
		for (final String action : rendered)
		{
			FactoryRightsTest.check("rendered wizard action " + action + " is decided", FactoryRights.wizardActions().containsKey(action));
		}
	}

	private static void everyReviewActionIsDecided(final Path pRoot) throws IOException
	{
		final String source = FactoryRightsTest.read(pRoot.resolve("com/on/ebx/factory/ebx/ui/ProposalReviewUserService.java"));
		final String decide = FactoryRightsTest.between(source, "\tvoid decide(", "\n\t}\n");
		final Set<String> actions = FactoryRightsTest.equalsLiterals(decide, "action");
		FactoryRightsTest.check("the review dispatcher was found (" + actions.size() + " actions)", actions.size() >= 18);
		for (final String action : actions)
		{
			FactoryRightsTest.check("review action " + action + " has a decided right", FactoryRights.reviewActions().containsKey(action));
		}
		for (final String decided : FactoryRights.reviewActions().keySet())
		{
			FactoryRightsTest.check("decided review action " + decided + " exists", decided.isEmpty() || actions.contains(decided));
		}
		FactoryRightsTest.check("decide() asks FactoryRights.reviewAction before any action",
			decide.indexOf("FactoryRights.reviewAction(action)") > 0 && decide.indexOf("FactoryRights.reviewAction(action)") < decide.indexOf("\"SELECT_PROJECT\".equals(action)"));
		FactoryRightsTest.check("a steward accepts and rejects", FactoryRights.reviewAction("ACCEPT") == FactoryRight.STEWARD && FactoryRights.reviewAction("REJECT") == FactoryRight.STEWARD
			&& FactoryRights.reviewAction("CASCADE_CONFIRM") == FactoryRight.STEWARD);
		FactoryRightsTest.check("applying stays DESIGN", FactoryRights.reviewAction("APPLY") == FactoryRight.DESIGN && FactoryRights.reviewAction("SELECT_APPLY_CONFIRM") == FactoryRight.DESIGN);
	}

	/** Every user service of the module is decided, and its declaration asks the decided right (deny by default: no declaration without a rule). */
	private static void everyServiceIsDecided(final Path pRoot) throws IOException
	{
		final Set<String> names = new LinkedHashSet<String>();
		try (DirectoryStream<Path> files = Files.newDirectoryStream(pRoot.resolve("com/on/ebx/factory/ebx/ui"), "*ServiceDeclaration.java"))
		{
			for (final Path file : files)
			{
				final String source = FactoryRightsTest.read(file);
				final Matcher name = Pattern.compile("SERVICE_NAME = \"([A-Za-z]+)\"").matcher(source);
				FactoryRightsTest.check(file.getFileName() + " declares a service name", name.find());
				names.add(name.group(1));
				FactoryRightsTest.check(file.getFileName() + " has a decided right", FactoryRights.services().containsKey(name.group(1)));
				FactoryRightsTest.check(file.getFileName() + " sets a permission rule", source.contains("setPermissionRule("));
				FactoryRightsTest.check(file.getFileName() + " asks the decided right",
					source.contains("FactoryRights.service(") || source.contains("new RecordLineageOnRecordPermissionRule()"));
			}
		}
		FactoryRightsTest.check("thirteen services", names.size() == 13 && names.equals(FactoryRights.services().keySet()));
		final String recordRule = FactoryRightsTest.read(pRoot.resolve("com/on/ebx/factory/ebx/ui/RecordLineageOnRecordPermissionRule.java"));
		FactoryRightsTest.check("the record-lineage rule asks the decided right first", recordRule.contains("FactoryRights.service(RecordLineageOnRecordServiceDeclaration.SERVICE_NAME)"));
		FactoryRightsTest.check("explorer, cockpit, wizard, review, lineage: VIEW", FactoryRights.service("ProjectExplorer") == FactoryRight.VIEW
			&& FactoryRights.service("FactoryCockpit") == FactoryRight.VIEW && FactoryRights.service("ProjectWizard") == FactoryRight.VIEW
			&& FactoryRights.service("ProposalReview") == FactoryRight.VIEW && FactoryRights.service("RecordLineage") == FactoryRight.VIEW);
		FactoryRightsTest.check("the transfer-reject review is read with VIEW (acknowledging asks STEWARD in the engine)", FactoryRights.service("TransferRejectReview") == FactoryRight.VIEW);
		FactoryRightsTest.check("matrix and designer: DESIGN", FactoryRights.service("MappingMatrix") == FactoryRight.DESIGN && FactoryRights.service("FeedDesigner") == FactoryRight.DESIGN
			&& FactoryRights.service("StagingDesk") == FactoryRight.DESIGN);
	}

	/** Every {@code register}/{@code registerWholeProject} of FactoryJobKinds names a right; the resets and the deletion are the administrator's. */
	private static void everyJobKindIsDecided(final Path pRoot) throws IOException
	{
		final String source = FactoryRightsTest.read(pRoot.resolve("com/on/ebx/factory/ebx/FactoryJobKinds.java"));
		final Matcher m = Pattern.compile("FactoryJobQueue\\.register(WholeProject)?\\(([A-Za-z]+\\.[A-Z_]+),([^;]*)\\);").matcher(source);
		int kinds = 0;
		while (m.find())
		{
			kinds++;
			final String args = m.group(3);
			final Matcher right = Pattern.compile("FactoryRight\\.([A-Z]+)\\s*$").matcher(args.trim());
			FactoryRightsTest.check("job kind " + m.group(2) + " names a right", right.find());
			final FactoryRight decided = FactoryRight.valueOf(right.group(1));
			FactoryRightsTest.check("job kind " + m.group(2) + " is queued by a person's right", decided != FactoryRight.AUTHENTICATED && decided != FactoryRight.VIEW);
			if (m.group(1) != null)
			{
				FactoryRightsTest.check("whole-project kind " + m.group(2) + " stays with the administrator", decided == FactoryRight.ADMINISTRATOR);
			}
			if ("MatchingResultsRun.STEP".equals(m.group(2)))
			{
				FactoryRightsTest.check("the steward's results refresh is STEWARD", decided == FactoryRight.STEWARD);
			}
		}
		FactoryRightsTest.check("every job kind was read (" + kinds + ")", kinds >= 30);
		final String queue = FactoryRightsTest.read(pRoot.resolve("com/on/ebx/factory/ebx/FactoryJobQueue.java"));
		final String enqueue = FactoryRightsTest.between(queue, "public static Queued enqueue(", "synchronized (FactoryJobQueue.ENQUEUE_LOCK)");
		FactoryRightsTest.check("enqueue checks the caller's right before writing the row", enqueue.contains("FactoryJobQueue.mayQueue(pCaller, pFactory, pKind)"));
		FactoryRightsTest.check("an unregistered kind asks the administrator", queue.contains("return FactoryAccess.has(pSession, FactoryRight.ADMINISTRATOR);"));
	}

	private static void whatStaysAdministrator()
	{
		for (final String action : Arrays.asList("STEP_RESET_PLAN", "STEP_RESET_CONFIRM", "DISTRIBUTION_TARGET_ARM_OVERRIDE", "DELIVERY_CHECK"))
		{
			FactoryRightsTest.check(action + " stays with the administrator", FactoryRights.wizardAction(action) == FactoryRight.ADMINISTRATOR);
		}
		for (final String route : Arrays.asList("POST /projects/{code}/delete", "GET /projects/{code}/deletion/plan", "POST /projects/{code}/reset",
			"GET /projects/{code}/reset/plan", "POST /projects/{code}/master/reset", "GET /connection/test", "GET /connection/auth-probe",
			"POST /maintenance/publication-owners", "GET /projects/{code}/delivery/domain", "GET /diagnostics/master-zone-index",
			"POST /projects/{code}/distribution/targets/{targetId}/arm-override"))
		{
			FactoryRightsTest.check(route + " stays with the administrator", FactoryRights.route(route) == FactoryRight.ADMINISTRATOR);
		}
		FactoryRightsTest.check("connection and deletion screens stay with the administrator",
			FactoryRights.service("FactoryConnection") == FactoryRight.ADMINISTRATOR && FactoryRights.service("ProjectDelete") == FactoryRight.ADMINISTRATOR);
		FactoryRightsTest.check("a steward decides suspect pairs", FactoryRights.wizardAction("MATCH_PAIR_MERGE") == FactoryRight.STEWARD
			&& FactoryRights.wizardAction("MATCH_PAIR_BULK_MERGE") == FactoryRight.STEWARD);
		FactoryRightsTest.check("the language-model passes are DESIGN", FactoryRights.wizardAction("STAGING_SUGGEST_LLM") == FactoryRight.DESIGN
			&& FactoryRights.route("POST /projects/{code}/matching/suggest-llm") == FactoryRight.DESIGN);
		for (final Map.Entry<String, FactoryRight> one : FactoryRights.wizardActions().entrySet())
		{
			if (FactoryRights.pipelineOperatorMay(one.getKey()))
			{
				FactoryRightsTest.check("a pipeline-operator action is otherwise DESIGN: " + one.getKey(), one.getValue() == FactoryRight.DESIGN);
			}
		}
		for (final String entry : FactoryRights.pipelineOperatorEntries())
		{
			FactoryRightsTest.check("pipeline-operator entry " + entry + " is a decided action or route",
				FactoryRights.wizardActions().containsKey(entry) || FactoryRights.routes().containsKey(entry));
		}
	}

	/** Every string literal X of {@code "X".equals(<variable>)} for the given variable names. */
	private static Set<String> equalsLiterals(final String pSource, final String... pVariables)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final String variable : pVariables)
		{
			final Matcher m = Pattern.compile("\"([A-Z][A-Z0-9_]*)\"\\.equals\\(" + variable + "\\)").matcher(pSource);
			while (m.find())
			{
				out.add(m.group(1));
			}
		}
		return out;
	}

	private static String between(final String pSource, final String pFrom, final String pTo)
	{
		final int start = pSource.indexOf(pFrom);
		FactoryRightsTest.check("found: " + pFrom, start >= 0);
		final int end = pSource.indexOf(pTo, start + pFrom.length());
		return pSource.substring(start, end < 0 ? pSource.length() : end);
	}

	private static String read(final Path pPath) throws IOException
	{
		return new String(Files.readAllBytes(pPath), StandardCharsets.UTF_8);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
