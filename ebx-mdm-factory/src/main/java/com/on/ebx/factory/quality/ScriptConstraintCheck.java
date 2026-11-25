package com.on.ebx.factory.quality;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.capability.ConstraintBodyBuilder;
import com.on.ebx.factory.capability.MeasurementMatch;

/**
 * quality-script-rule lot (2026-09-23, the product owner on project Personnes: "les regles de validation scriptees ne
 * devraient pas s'appliquer egalement au staging, pour ne pas laisser arriver en MDM des donnees erronees ?").
 * The pure decision half of enforcing an APPLIED {@code CapabilitySuggestion} (nature {@code SCRIPT_CONSTRAINT},
 * zone {@code STAGING}) at the staging -&gt; master transfer, the sibling of {@link QualityRuleCheck} for
 * script-backed checks. No EBX class here - {@code ebx.MasterLoader} is the only caller: it reads ONE staging
 * record's own {@code com.onwbp.adaptation.Adaptation#getValidationReport()} (design decision A of the task
 * brief - EBX's own validation engine already evaluated the applied constraint when the staging record was
 * last saved/validated; nothing here ever calls the library function itself, and no script is generated or run
 * from Java, see {@code docs/CAPABILITY-TRANSFER.md}), reduces each {@code ValidationReportItem} it walks into
 * a plain {@link ReportItemView} (severity label, subject path, rendered message - the exact three facts
 * {@code ebx.CapabilityApplier.measureEffect} already reduces a report item to for its own "measured effect"
 * pass, see {@code docs/CAPABILITY-APPLY.md} sec 6), and hands the list here.
 * <p>
 * <b>Attribution reuses {@code capability.MeasurementMatch} as-is</b> - the exact same severity+path gate and
 * exact/prefix message match {@code ebx.CapabilityApplier} already uses to count a validation-report item
 * against one specific applied constraint, so a record flagged by some OTHER warning/error on the same
 * field/record is never mistaken for this constraint firing. When the constraint's own {@code scriptBody} has
 * no extractable message literal ({@link ConstraintBodyBuilder#extractReportedMessage} returns null - a
 * variable or a built message, see that method's own Javadoc), {@link Rule#attribution} is null and this class
 * falls back to path+severity alone (the same "approximate" posture the apply lot's own measurement takes) -
 * never refuses to enforce a rule merely because its message could not be extracted.
 */
public final class ScriptConstraintCheck
{
	private ScriptConstraintCheck()
	{
	}

	/** {@code CapabilitySuggestion.transferPolicy} - see {@code tools/build_xsd.py}'s own CAPABILITY_TRANSFER_POLICY comment: blank means unenforced (never reaches this class - the caller filters it out, same convention {@code QualityRuleCheck.Rule}'s MODEL_CONSTRAINT/UNIQUE/MANDATORY filtering already follows). */
	public static final String REJECT = "REJECT";
	public static final String WARN = "WARN";

	/** One APPLIED, transfer-enforced {@code CapabilitySuggestion} - down to what evaluation needs. The entity/attribute scoping (which staging table this applies to) is the caller's own bookkeeping, same convention as {@link QualityRuleCheck.Rule}. */
	public static final class Rule
	{
		public final String id;
		public final String expectedPath;
		public final String expectedSeverityLabel;
		/** Null: no literal extractable from the constraint's own {@code scriptBody} - approximate attribution (path+severity alone). Non-null: {@link MeasurementMatch#messageMatches} also gates the match. */
		public final ConstraintBodyBuilder.MessageAttribution attribution;
		/** {@link #REJECT} or {@link #WARN} - a blank/unrecognised {@code transferPolicy} row must never reach this class (the caller filters it out before it is even read, same discipline {@link QualityRuleCheck.Rule#isRejectSeverity} documents for MODEL_CONSTRAINT). */
		public final String transferPolicy;
		/** Human-readable, for a {@code TransferReject.reasons}/warning-example entry - e.g. {@code "Fasttrack.lib.phone.isValid on phone"}. Never a code identifier alone: built by the caller from {@code targetImportName}/{@code targetLibrary} + {@code targetFunction} + the attribute name. */
		public final String reasonLabel;

		public Rule(final String pId, final String pExpectedPath, final String pExpectedSeverityLabel, final ConstraintBodyBuilder.MessageAttribution pAttribution,
			final String pTransferPolicy, final String pReasonLabel)
		{
			this.id = pId;
			this.expectedPath = pExpectedPath;
			this.expectedSeverityLabel = pExpectedSeverityLabel;
			this.attribution = pAttribution;
			this.transferPolicy = pTransferPolicy;
			this.reasonLabel = pReasonLabel;
		}

		public boolean isRejectPolicy()
		{
			return ScriptConstraintCheck.REJECT.equals(this.transferPolicy);
		}
	}

	/** One {@code ValidationReportItem}, reduced to the three facts {@link #firingMessage} needs - built by {@code ebx.MasterLoader}, never carries a real EBX type so this class stays pure. */
	public static final class ReportItemView
	{
		public final String severityLabel;
		public final String subjectPath;
		public final String renderedMessage;

		public ReportItemView(final String pSeverityLabel, final String pSubjectPath, final String pRenderedMessage)
		{
			this.severityLabel = pSeverityLabel;
			this.subjectPath = pSubjectPath;
			this.renderedMessage = pRenderedMessage;
		}
	}

	/**
	 * Walks {@code pItems} (one staging record's own validation-report items, already reduced by the caller)
	 * looking for the first one attributable to {@code pRule} ({@link MeasurementMatch#onExpectedPath}, then -
	 * unless {@link Rule#attribution} is null - {@link MeasurementMatch#messageMatches}), same two gates {@code
	 * ebx.CapabilityApplier.measureEffect} already applies for its own "measured effect" count.
	 *
	 * @return the matched item's own rendered message (never null when a match is found - used verbatim in the
	 *         {@code TransferReject} reason / warning example), or {@code null} when the rule does not fire on
	 *         this record.
	 */
	public static String firingMessage(final Rule pRule, final List<ReportItemView> pItems)
	{
		if (pRule == null || pItems == null)
		{
			return null;
		}
		for (final ReportItemView item : pItems)
		{
			if (!MeasurementMatch.onExpectedPath(item.severityLabel, pRule.expectedSeverityLabel, item.subjectPath, pRule.expectedPath))
			{
				continue;
			}
			if (pRule.attribution == null)
			{
				return item.renderedMessage == null ? "" : item.renderedMessage; // approximate: path+severity alone is already the match
			}
			if (MeasurementMatch.messageMatches(pRule.attribution, item.renderedMessage))
			{
				return item.renderedMessage;
			}
		}
		return null;
	}

	/**
	 * Volume lot 3 (2026-09-28, docs/VOLUME.md task 4): every {@link MeasurementMatch#key} this ENTITY's own
	 * {@code pRules} could ever fire on, computed ONCE per entity (never per row) - {@code ebx.MasterLoader}
	 * uses it to skip {@code item.getMessage().formatMessage(...)} for a validation-report item that could not
	 * possibly match ANY of this entity's rules (a different field, or a severity none of them expect), which
	 * used to be computed for every item of every row regardless of relevance. Never a substitute for {@link
	 * #firingMessage}'s own two-gate walk - a key match only means "worth building the rendered message and
	 * running the real gates on", not "this rule fires".
	 */
	public static Set<String> expectedKeys(final List<Rule> pRules)
	{
		final Set<String> out = new HashSet<String>();
		if (pRules == null)
		{
			return out;
		}
		for (final Rule rule : pRules)
		{
			out.add(MeasurementMatch.key(rule.expectedSeverityLabel, rule.expectedPath));
		}
		return out;
	}

	/** {@code "./" + attribute} for a NODE-scope constraint (the attribute is the applied element path's own last segment, e.g. {@code /root/Contact/phone} -&gt; {@code phone}), {@code "."} (the record itself, {@code Path.SELF.format()}) for TABLE scope or a malformed/missing path - never guesses a wrong field. */
	public static String expectedPathFor(final String pConstraintScope, final String pAppliedElementPath)
	{
		if ("NODE".equals(pConstraintScope) && pAppliedElementPath != null)
		{
			final int slash = pAppliedElementPath.lastIndexOf('/');
			if (slash >= 0 && slash < pAppliedElementPath.length() - 1)
			{
				return "./" + pAppliedElementPath.substring(slash + 1);
			}
		}
		return ".";
	}

	/** {@code Severity.ERROR}/{@code Severity.WARNING}'s own {@code toParsableString()} values ("ERROR"/"WARNING") - the same plain-string convention {@code capability.MeasurementMatch}/{@code ebx.CapabilityApplier} already compare severity labels with, never the real EBX {@code Severity} enum (kept out of this pure package). */
	public static String expectedSeverityLabelFor(final String pSeverity)
	{
		return "ERROR".equalsIgnoreCase(pSeverity) ? "ERROR" : "WARNING";
	}
}
