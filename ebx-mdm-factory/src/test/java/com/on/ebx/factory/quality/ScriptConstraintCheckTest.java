package com.on.ebx.factory.quality;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.capability.ConstraintBodyBuilder;
import com.on.ebx.factory.capability.MeasurementMatch;

/** JUnit-free, like the sibling {@code QualityRuleCheckTest}: run the main, a failure throws. */
public final class ScriptConstraintCheckTest
{
	public static void main(final String[] pArgs)
	{
		ScriptConstraintCheckTest.noItemsNeverFires();
		ScriptConstraintCheckTest.wrongPathOrSeverityNeverFires();
		ScriptConstraintCheckTest.exactAttributionMatchesOnly();
		ScriptConstraintCheckTest.prefixAttributionMatchesAConcatenation();
		ScriptConstraintCheckTest.approximateAttributionMatchesOnPathAndSeverityAlone();
		ScriptConstraintCheckTest.firstMatchingItemWins();
		ScriptConstraintCheckTest.isRejectPolicyOnlyForReject();
		ScriptConstraintCheckTest.expectedPathForNodeScope();
		ScriptConstraintCheckTest.expectedPathForTableScopeOrMalformed();
		ScriptConstraintCheckTest.expectedSeverityLabelMapsErrorAndDefaultsToWarning();
		ScriptConstraintCheckTest.expectedKeysOneEntryPerDistinctSeverityPathPair();
		ScriptConstraintCheckTest.expectedKeysEmptyForNoRulesOrNull();
		System.out.println("ScriptConstraintCheckTest OK");
	}

	private static ScriptConstraintCheck.Rule rule(final ConstraintBodyBuilder.MessageAttribution pAttribution, final String pPolicy)
	{
		return new ScriptConstraintCheck.Rule("1", "./phone", "WARNING", pAttribution, pPolicy, "Fasttrack.lib.phone.isValid on phone");
	}

	private static void noItemsNeverFires()
	{
		ScriptConstraintCheckTest.check("no items: never fires", ScriptConstraintCheck.firingMessage(
			ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.WARN), Collections.<ScriptConstraintCheck.ReportItemView>emptyList()) == null);
	}

	private static void wrongPathOrSeverityNeverFires()
	{
		final ScriptConstraintCheck.Rule rule = ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.WARN);
		final List<ScriptConstraintCheck.ReportItemView> wrongPath = Arrays.asList(new ScriptConstraintCheck.ReportItemView("WARNING", "./email", "not accepted."));
		ScriptConstraintCheckTest.check("wrong path: never fires", ScriptConstraintCheck.firingMessage(rule, wrongPath) == null);
		final List<ScriptConstraintCheck.ReportItemView> wrongSeverity = Arrays.asList(new ScriptConstraintCheck.ReportItemView("ERROR", "./phone", "not accepted."));
		ScriptConstraintCheckTest.check("wrong severity: never fires", ScriptConstraintCheck.firingMessage(rule, wrongSeverity) == null);
	}

	private static void exactAttributionMatchesOnly()
	{
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage("msg.addWarning('Phone: not accepted.');", "WARN");
		final ScriptConstraintCheck.Rule rule = ScriptConstraintCheckTest.rule(attribution, ScriptConstraintCheck.REJECT);
		final List<ScriptConstraintCheck.ReportItemView> matching = Arrays.asList(new ScriptConstraintCheck.ReportItemView("WARNING", "./phone", "Phone: not accepted."));
		ScriptConstraintCheckTest.check("exact message match fires", "Phone: not accepted.".equals(ScriptConstraintCheck.firingMessage(rule, matching)));
		final List<ScriptConstraintCheck.ReportItemView> unrelated = Arrays.asList(new ScriptConstraintCheck.ReportItemView("WARNING", "./phone", "Some other warning."));
		ScriptConstraintCheckTest.check("an unrelated warning on the same field never fires this rule", ScriptConstraintCheck.firingMessage(rule, unrelated) == null);
	}

	private static void prefixAttributionMatchesAConcatenation()
	{
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage("msg.addWarning('Phone: not accepted.' | detail);", "WARN");
		final ScriptConstraintCheck.Rule rule = ScriptConstraintCheckTest.rule(attribution, ScriptConstraintCheck.WARN);
		final List<ScriptConstraintCheck.ReportItemView> matching = Arrays
			.asList(new ScriptConstraintCheck.ReportItemView("WARNING", "./phone", "Phone: not accepted. (invalid national number)"));
		ScriptConstraintCheckTest.check("prefix match fires", ScriptConstraintCheck.firingMessage(rule, matching) != null);
	}

	private static void approximateAttributionMatchesOnPathAndSeverityAlone()
	{
		// scriptBody carrying a variable/built message (nothing extractable) -> null attribution, same
		// posture ebx.CapabilityApplier's own measurement takes ("approximate")
		final ScriptConstraintCheck.Rule rule = ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.REJECT);
		final List<ScriptConstraintCheck.ReportItemView> anyMessage = Arrays.asList(new ScriptConstraintCheck.ReportItemView("WARNING", "./phone", "whatever the message is"));
		ScriptConstraintCheckTest.check("path+severity alone fires when attribution is null", ScriptConstraintCheck.firingMessage(rule, anyMessage) != null);
	}

	private static void firstMatchingItemWins()
	{
		final ScriptConstraintCheck.Rule rule = ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.WARN);
		final List<ScriptConstraintCheck.ReportItemView> items = Arrays.asList(new ScriptConstraintCheck.ReportItemView("WARNING", "./email", "unrelated"),
			new ScriptConstraintCheck.ReportItemView("WARNING", "./phone", "first phone message"), new ScriptConstraintCheck.ReportItemView("WARNING", "./phone", "second phone message"));
		ScriptConstraintCheckTest.check("the first matching item's message is returned", "first phone message".equals(ScriptConstraintCheck.firingMessage(rule, items)));
	}

	private static void isRejectPolicyOnlyForReject()
	{
		ScriptConstraintCheckTest.check("REJECT policy", ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.REJECT).isRejectPolicy());
		ScriptConstraintCheckTest.check("WARN policy is not reject", !ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.WARN).isRejectPolicy());
	}

	private static void expectedPathForNodeScope()
	{
		ScriptConstraintCheckTest.check("NODE scope: last path segment", "./phone".equals(ScriptConstraintCheck.expectedPathFor("NODE", "/root/Contact/phone")));
	}

	private static void expectedPathForTableScopeOrMalformed()
	{
		ScriptConstraintCheckTest.check("TABLE scope: the record itself", ".".equals(ScriptConstraintCheck.expectedPathFor("TABLE", "/root/Contact")));
		ScriptConstraintCheckTest.check("NODE scope with no slash: falls back to the record, never guesses", ".".equals(ScriptConstraintCheck.expectedPathFor("NODE", "phone")));
		ScriptConstraintCheckTest.check("null path: falls back to the record", ".".equals(ScriptConstraintCheck.expectedPathFor("NODE", null)));
	}

	private static void expectedSeverityLabelMapsErrorAndDefaultsToWarning()
	{
		ScriptConstraintCheckTest.check("ERROR maps to ERROR", "ERROR".equals(ScriptConstraintCheck.expectedSeverityLabelFor("ERROR")));
		ScriptConstraintCheckTest.check("WARN maps to WARNING", "WARNING".equals(ScriptConstraintCheck.expectedSeverityLabelFor("WARN")));
		ScriptConstraintCheckTest.check("anything else defaults to WARNING", "WARNING".equals(ScriptConstraintCheck.expectedSeverityLabelFor(null)));
	}

	/** Volume lot 3 (2026-09-28, task 4): one key per DISTINCT (severity, path) pair - two rules on the same field/severity collapse to one key, since either one firing is "worth building the message for". */
	private static void expectedKeysOneEntryPerDistinctSeverityPathPair()
	{
		final ScriptConstraintCheck.Rule phoneRule = ScriptConstraintCheckTest.rule(null, ScriptConstraintCheck.WARN);
		final ScriptConstraintCheck.Rule emailRule = new ScriptConstraintCheck.Rule("2", "./email", "ERROR", null, ScriptConstraintCheck.REJECT, "email check");
		final ScriptConstraintCheck.Rule duplicatePhoneRule = new ScriptConstraintCheck.Rule("3", "./phone", "WARNING", null, ScriptConstraintCheck.WARN, "another phone rule");
		final Set<String> keys = ScriptConstraintCheck.expectedKeys(Arrays.asList(phoneRule, emailRule, duplicatePhoneRule));
		ScriptConstraintCheckTest.check("two distinct pairs -> two keys, the duplicate collapses", keys.size() == 2);
		ScriptConstraintCheckTest.check("the phone key is present", keys.contains(MeasurementMatch.key("WARNING", "./phone")));
		ScriptConstraintCheckTest.check("the email key is present", keys.contains(MeasurementMatch.key("ERROR", "./email")));
		ScriptConstraintCheckTest.check("an item that matches neither rule's key is absent", !keys.contains(MeasurementMatch.key("WARNING", "./city")));
	}

	private static void expectedKeysEmptyForNoRulesOrNull()
	{
		ScriptConstraintCheckTest.check("empty rule list: empty set", ScriptConstraintCheck.expectedKeys(Collections.<ScriptConstraintCheck.Rule>emptyList()).isEmpty());
		ScriptConstraintCheckTest.check("null rule list: empty set, never throws", ScriptConstraintCheck.expectedKeys(null).isEmpty());
	}

	private static void check(final String pLabel, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pLabel);
		}
	}
}
