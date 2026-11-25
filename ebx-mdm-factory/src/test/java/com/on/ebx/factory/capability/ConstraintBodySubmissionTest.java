package com.on.ebx.factory.capability;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** JUnit-free: run the main, a failure throws. */
public final class ConstraintBodySubmissionTest
{
	private static final ConstraintBodyBuilder.ScopeDecision NODE = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE, "iban",
		Arrays.asList("iban"));

	// fix/setup-alias (2026-09-21): carries its own "uses core.validation as v;" line so the overwrite is a
	// true no-op (byte-identical source before/after) for every test below that asserts source() equals this
	// fixture verbatim - a source with NO such line (the real captured gap this fix closes) has its own
	// dedicated fixtures/tests, see NO_VALIDATION_SOURCE below.
	private static final String GOOD_SOURCE = "uses core.validation as v;\nimport Fasttrack.lib.iban as iban;\n\nexport procedure check(record : .Contact)\nbegin\n"
		+ "  const r := iban.validate(record.iban, 'FR');\nend\n\nexport procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n";

	private static final String BAD_SOURCE = "import Fasttrack.lib.iban as iban;\n\nexport procedure check(record : .Contact)\nbegin\nend\n\n"
		+ "export procedure setup()\nbegin\nend\n"; // never calls the function

	/**
	 * fix/node-scope-multi-attribute (2026-09-21), item 1: the real proven shape - suggestion 6's own
	 * lesson, re-cast as NODE scope with TWO bound attributes (anchor {@code iban} plus {@code country}) -
	 * replaces the earlier TABLE-scope fixture this test file used before the TABLE apply path was removed
	 * (see {@code ConstraintBodyBuilder}'s own Javadoc for why). The model's own {@code setup()} here still
	 * calls {@code setDependencyToLocalNode()} - deliberately WRONG for two dependencies, to prove {@link
	 * ConstraintBodySubmission#submit} overwrites it regardless of what the model wrote (item 1's own
	 * "overwrite, never trust" choice).
	 */
	private static final ConstraintBodyBuilder.ScopeDecision NODE_TWO_ATTRIBUTES = new ConstraintBodyBuilder.ScopeDecision(ConstraintBodyBuilder.Scope.NODE,
		"iban", Arrays.asList("iban", "country"));
	private static final String TWO_ATTRIBUTE_SOURCE = "uses core.message as msg;\nuses core.validation as v;\nuses core.json as json;\n\n"
		+ "import Fasttrack.lib.iban as iban;\n\n" + "export procedure check(record : .Person)\nbegin\n" + "  if isNull(record.iban) then return;\n"
		+ "  const report := iban.validate(record.iban, record.country);\n" + "  if not json.getBoolean(report, 'valid') then\n  begin\n"
		+ "    msg.addWarning('The IBAN is not valid.');\n  end\nend\n\n"
		+ "export procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n"; // wrong on purpose - see Javadoc above

	/**
	 * fix/review-assets (2026-09-21): the real captured shape - one physical line, every intended line break
	 * sent as the two characters backslash+n - re-cast onto {@link #NODE_TWO_ATTRIBUTES} (fix/node-scope-
	 * multi-attribute: the TABLE-scope original of this fixture no longer applies, see {@code
	 * ConstraintBodyBuilderTest}'s own copy of this fixture for the full trace of why).
	 */
	private static final String DOUBLE_ESCAPED_TWO_ATTRIBUTE_SOURCE = "uses core.message as msg;\\nuses core.validation as v;\\nuses core.json as json;\\n\\n"
		+ "import Fasttrack.lib.iban as iban;\\n\\n" + "export procedure check(record : .Person)\\nbegin\\n" + "  if isNull(record.iban) then return;\\n"
		+ "  const report := iban.validate(record.iban, record.country);\\n" + "  if not json.getBoolean(report, 'valid') then\\n  begin\\n"
		+ "    msg.addWarning('The IBAN is not valid.');\\n  end\\nend\\n\\n" + "export procedure setup()\\nbegin\\n  v.setDependencyToLocalNode();\\nend\\n";

	/**
	 * fix/setup-alias (2026-09-21): the real captured header set that compiled clean an hour before this fix -
	 * {@code uses core.message as msg;}, {@code uses core.json as json;}, {@code import Fasttrack.lib.iban as
	 * iban;} - NO {@code core.validation} line at all, since the model's own (soon-discarded) {@code setup()}
	 * was empty and never needed one.
	 */
	private static final String NO_VALIDATION_SOURCE = "uses core.message as msg;\nuses core.json as json;\n\nimport Fasttrack.lib.iban as iban;\n\n"
		+ "export procedure check(record : .Person)\nbegin\n  if isNull(record.iban) then return;\n"
		+ "  const report := iban.validate(record.iban, record.country);\n  if not json.getBoolean(report, 'valid') then\n  begin\n"
		+ "    msg.addWarning('The IBAN is not valid.');\n  end\nend\n\n" + "export procedure setup()\nbegin\nend\n";

	/** Same shape, but the model already declared {@code core.validation} under its own alias - must be REUSED, never replaced with {@code v}. */
	private static final String CUSTOM_ALIAS_SOURCE = "uses core.message as msg;\nuses core.validation as validationContext;\nuses core.json as json;\n\n"
		+ "import Fasttrack.lib.iban as iban;\n\n" + "export procedure check(record : .Person)\nbegin\n  if isNull(record.iban) then return;\n"
		+ "  const report := iban.validate(record.iban, record.country);\n  if not json.getBoolean(report, 'valid') then\n  begin\n"
		+ "    msg.addWarning('The IBAN is not valid.');\n  end\nend\n\n"
		+ "export procedure setup()\nbegin\n  validationContext.setDependencyToLocalNode();\nend\n";

	public static void main(final String[] pArgs)
	{
		ConstraintBodySubmissionTest.firstValidSubmissionSucceeds();
		ConstraintBodySubmissionTest.invalidSubmissionIsRefusedAndCanBeRetried();
		ConstraintBodySubmissionTest.succeedsOnSecondAttemptAfterCorrection();
		ConstraintBodySubmissionTest.exhaustsBudgetAfterMaxAttempts();
		ConstraintBodySubmissionTest.attemptsCountedEvenOnRefusal();
		ConstraintBodySubmissionTest.wasNormalizedFalseWhenSourceAlreadyHasRealLineBreaks();
		ConstraintBodySubmissionTest.doubleEscapedSubmissionIsAcceptedAndFlaggedAsNormalized();
		ConstraintBodySubmissionTest.storedSourceIsTheNormalizedOneNotTheRawSubmission();
		ConstraintBodySubmissionTest.retryingAFreshInstanceStartsWithNoStaleAttemptCounter();
		ConstraintBodySubmissionTest.compileFailureOnFirstAttemptThenSuccessOnSecond();
		ConstraintBodySubmissionTest.threeCompileFailuresExhaustTheCompileBudget();
		ConstraintBodySubmissionTest.structuralRefusalNeverSpendsACompileAttempt();
		ConstraintBodySubmissionTest.compileFailureMessageQuotesLineColumnMessageAndSourceLine();
		ConstraintBodySubmissionTest.compilerNeverCalledWhenStructurallyInvalid();
		ConstraintBodySubmissionTest.onlyACompiledBodyEverReturnsAccepted();
		ConstraintBodySubmissionTest.transportErrorFromTheCompilerIsReportedAndCountsAsAFailedAttempt();
		ConstraintBodySubmissionTest.setupIsOverwrittenEvenWhenTheModelsOwnSetupIsWrongForTwoDependencies();
		ConstraintBodySubmissionTest.submitInsertsAValidationAliasLineWhenTheModelDeclaredNone();
		ConstraintBodySubmissionTest.submitReusesAnExistingValidationAliasEndToEnd();
		ConstraintBodySubmissionTest.messageNotRequiredWhenRowAlreadyHasOneWrittenMessageStaysNull();
		ConstraintBodySubmissionTest.messageRequiredAndProvidedIsSanitizedAndReturnedOnSuccess();
		ConstraintBodySubmissionTest.messageRequiredButMissingIsRefusedWithoutSpendingACompileAttempt();
		ConstraintBodySubmissionTest.messageRequiredButBlankAfterSanitizingIsRefused();
		ConstraintBodySubmissionTest.messageRequiredIsTruncatedToTheSameBoundAsSuggestionTime();

		System.out.println("ConstraintBodySubmissionTest OK");
	}

	// ---------------------------------------------------------------------------------------------- test doubles

	/**
	 * fix/body-compile-loop (2026-09-21): a fake {@link ConstraintCompiler} - one {@link ConstraintCompiler.Result} per call, in order; once
	 * exhausted, the last one given is repeated (so a single-result fake can back an unbounded number of calls). Counts its own calls so a
	 * test can assert the compiler was (or was not) reached at all.
	 */
	private static final class FakeCompiler implements ConstraintCompiler
	{
		private final List<Result> results;
		private int calls;

		FakeCompiler(final Result... pResults)
		{
			this.results = Arrays.asList(pResults);
		}

		@Override
		public Result compile(final String pSource)
		{
			final Result result = this.results.get(Math.min(this.calls, this.results.size() - 1));
			this.calls++;
			return result;
		}

		int calls()
		{
			return this.calls;
		}
	}

	private static ConstraintCompiler.Result compileSuccess()
	{
		return new ConstraintCompiler.Result(true, Collections.<ConstraintCompiler.Error>emptyList(), null);
	}

	private static ConstraintCompiler.Result compileFailure(final Integer pLine, final Integer pColumn, final String pMessage)
	{
		return new ConstraintCompiler.Result(false, Arrays.asList(new ConstraintCompiler.Error(pLine, pColumn, pMessage)), null);
	}

	private static final ConstraintCompiler ALWAYS_COMPILES = new FakeCompiler(ConstraintBodySubmissionTest.compileSuccess());

	private static ConstraintBodySubmission newSubmission(final ConstraintBodyBuilder.ScopeDecision pScope, final int pMaxStructural, final int pMaxCompile,
		final ConstraintCompiler pCompiler, final boolean pRequiresWrittenMessage)
	{
		return new ConstraintBodySubmission("Fasttrack.lib.iban", "validate", pScope, "Contact", pMaxStructural, pMaxCompile, pCompiler, pRequiresWrittenMessage);
	}

	// -------------------------------------------------------------------------------------------- existing behaviour

	private static void firstValidSubmissionSucceeds()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("accepted", outcome.accepted);
		ConstraintBodySubmissionTest.check("succeeded() true", submission.succeeded());
		ConstraintBodySubmissionTest.check("source captured", ConstraintBodySubmissionTest.GOOD_SOURCE.equals(submission.source()));
		ConstraintBodySubmissionTest.check("one attempt counted", submission.attempts() == 1);
		ConstraintBodySubmissionTest.check("one compile attempt counted too", submission.compileAttempts() == 1);
	}

	private static void invalidSubmissionIsRefusedAndCanBeRetried()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		ConstraintBodySubmissionTest.check("refused: " + outcome.message, !outcome.accepted);
		ConstraintBodySubmissionTest.check("message tells the model to retry", outcome.message.contains("call submit_constraint_body again"));
		ConstraintBodySubmissionTest.check("not succeeded yet", !submission.succeeded());
		ConstraintBodySubmissionTest.check("lastError recorded", submission.lastError() != null && submission.lastError().contains("never calls"));
	}

	private static void succeedsOnSecondAttemptAfterCorrection()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		final ConstraintBodySubmission.Outcome second = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("second attempt accepted", second.accepted);
		ConstraintBodySubmissionTest.check("succeeded overall", submission.succeeded());
		ConstraintBodySubmissionTest.check("two attempts counted", submission.attempts() == 2);
	}

	private static void exhaustsBudgetAfterMaxAttempts()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		final ConstraintBodySubmission.Outcome fourth = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("a 4th attempt is refused outright, even with a good source", !fourth.accepted);
		ConstraintBodySubmissionTest.check("budget message", fourth.message.contains("too many failed attempts"));
		ConstraintBodySubmissionTest.check("never succeeded", !submission.succeeded());
	}

	private static void attemptsCountedEvenOnRefusal()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		ConstraintBodySubmissionTest.check("two failed attempts counted", submission.attempts() == 2);
	}

	private static void wasNormalizedFalseWhenSourceAlreadyHasRealLineBreaks()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("no normalisation needed: the fixture already has real line breaks", !submission.wasNormalized());
	}

	/** fix/review-assets (2026-09-21): the real captured payload - end to end through submit(), not just checkSubmittedSource directly. */
	private static void doubleEscapedSubmissionIsAcceptedAndFlaggedAsNormalized()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE_TWO_ATTRIBUTES, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.DOUBLE_ESCAPED_TWO_ATTRIBUTE_SOURCE, null);
		ConstraintBodySubmissionTest.check("accepted: " + outcome.message, outcome.accepted);
		ConstraintBodySubmissionTest.check("succeeded", submission.succeeded());
		ConstraintBodySubmissionTest.check("flagged as normalized", submission.wasNormalized());
	}

	/** What gets stored (and what a caller would send to update_script) is the NORMALIZED text, with real line breaks - never the raw, still-one-line submission. */
	private static void storedSourceIsTheNormalizedOneNotTheRawSubmission()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE_TWO_ATTRIBUTES, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		submission.submit(ConstraintBodySubmissionTest.DOUBLE_ESCAPED_TWO_ATTRIBUTE_SOURCE, null);
		ConstraintBodySubmissionTest.check("the stored source has real line breaks", submission.source().indexOf('\n') >= 0);
		ConstraintBodySubmissionTest.check("the stored source is not the raw, still-one-line submission",
			!ConstraintBodySubmissionTest.DOUBLE_ESCAPED_TWO_ATTRIBUTE_SOURCE.equals(submission.source()));
	}

	/**
	 * fix/review-assets (2026-09-21), item 4 (retry path): a fresh {@link ConstraintBodySubmission} instance
	 * - exactly what {@code ebx.CapabilityApplier.runLlmBodyAuthoring} constructs on every call, including a
	 * retry via the Apply button after a previous ACCEPTED-with-applyMessage failure - starts with a clean
	 * attempt counter, never carrying over a previous run's refusals. This is a structural guarantee (no
	 * static/shared state anywhere in this class), asserted here directly rather than only inferred.
	 */
	private static void retryingAFreshInstanceStartsWithNoStaleAttemptCounter()
	{
		final ConstraintBodySubmission firstRun = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 1, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		firstRun.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		ConstraintBodySubmissionTest.check("the first (exhausted) run's budget is spent", firstRun.attempts() == 1);
		final ConstraintBodySubmission retry = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 1, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		ConstraintBodySubmissionTest.check("a fresh instance starts at zero attempts, independent of any earlier one", retry.attempts() == 0);
		final ConstraintBodySubmission.Outcome outcome = retry.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("the fresh instance's own first attempt succeeds normally, not refused by a stale budget", outcome.accepted);
	}

	// -------------------------------------------------------------------------------------------- fix/body-compile-loop

	/**
	 * fix/body-compile-loop (2026-09-21), item 1: the real captured sequence - a structurally valid source
	 * fails to COMPILE on attempt 1 (a fake compiler standing in for the real one), the model is told exactly
	 * why and resubmits, and the SAME structurally valid source compiles on attempt 2 - "errors on attempt 1,
	 * success on attempt 2" (the coordinator's own test spec).
	 */
	private static void compileFailureOnFirstAttemptThenSuccessOnSecond()
	{
		final ConstraintCompiler compiler = new FakeCompiler(ConstraintBodySubmissionTest.compileFailure(Integer.valueOf(5), Integer.valueOf(3), "Unexpected token '='"),
			ConstraintBodySubmissionTest.compileSuccess());
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3, compiler, false);
		final ConstraintBodySubmission.Outcome first = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("first attempt refused (compile failure): " + first.message, !first.accepted);
		ConstraintBodySubmissionTest.check("not succeeded yet", !submission.succeeded());
		ConstraintBodySubmissionTest.check("one compile attempt spent", submission.compileAttempts() == 1);
		final ConstraintBodySubmission.Outcome second = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("second attempt accepted (compiled)", second.accepted);
		ConstraintBodySubmissionTest.check("succeeded overall", submission.succeeded());
		ConstraintBodySubmissionTest.check("source captured is the one that actually compiled", ConstraintBodySubmissionTest.GOOD_SOURCE.equals(submission.source()));
		ConstraintBodySubmissionTest.check("two compile attempts spent in total", submission.compileAttempts() == 2);
	}

	/**
	 * fix/body-compile-loop (2026-09-21), item 1: "three failures ⇒ rollback" (the coordinator's own test
	 * spec) - the rollback itself is EBX-bound (ebx.CapabilityApplier's own catch/rollback, unchanged by this
	 * fix, see the module report), what THIS pure class guarantees is the precondition rollback depends on:
	 * after {@link #MAX_COMPILE_ATTEMPTS}-worth of real compile failures, the submission never succeeds and a
	 * 4th attempt (even with the exact same structurally valid source) is refused outright, without spending
	 * a 4th real compile call.
	 */
	private static void threeCompileFailuresExhaustTheCompileBudget()
	{
		final ConstraintCompiler compiler = new FakeCompiler(ConstraintBodySubmissionTest.compileFailure(Integer.valueOf(5), Integer.valueOf(3), "Unexpected token '='"));
		// structural budget deliberately larger than the compile budget: a 4th call must be refused for
		// EXHAUSTING THE COMPILE BUDGET specifically, never mistaken for a structural-budget refusal
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 5, 3, compiler, false);
		submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		final ConstraintBodySubmission.Outcome fourth = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("a 4th attempt is refused outright, even with a structurally valid source", !fourth.accepted);
		ConstraintBodySubmissionTest.check("budget message names compile attempts specifically", fourth.message.contains("too many failed compile attempts"));
		ConstraintBodySubmissionTest.check("never succeeded", !submission.succeeded());
		ConstraintBodySubmissionTest.check("the compiler itself was never called a 4th time - the budget check happens before calling it",
			((FakeCompiler) compiler).calls() == 3);
	}

	/** A structural refusal never spends a compile attempt, whatever the structural budget's own state - the two counters are genuinely independent. */
	private static void structuralRefusalNeverSpendsACompileAttempt()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		ConstraintBodySubmissionTest.check("two structural attempts counted", submission.attempts() == 2);
		ConstraintBodySubmissionTest.check("zero compile attempts - the source never even reached the compiler", submission.compileAttempts() == 0);
	}

	/** The compiler is never even called for a structurally invalid submission - a FakeCompiler that would fail the test if called proves this directly. */
	private static void compilerNeverCalledWhenStructurallyInvalid()
	{
		final ConstraintCompiler poisoned = new ConstraintCompiler()
		{
			@Override
			public Result compile(final String pSource)
			{
				throw new AssertionError("the compiler must never be called for a structurally invalid submission");
			}
		};
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3, poisoned, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.BAD_SOURCE, null);
		ConstraintBodySubmissionTest.check("refused for the structural reason, not a compiler crash", !outcome.accepted && outcome.message.contains("never calls"));
	}

	/**
	 * fix/body-compile-loop (2026-09-21), item 1: a compile failure names every error's line, column and
	 * message AND quotes the offending source line by its own 1-based line number - the real captured defect
	 * (the model told "accepted" while the compile silently failed afterward) must never recur: the model
	 * has to be able to see and act on the actual problem from the tool's own rejection.
	 */
	private static void compileFailureMessageQuotesLineColumnMessageAndSourceLine()
	{
		// line 6 (1-based) is the badly-compiling statement, "  x = 1;" - matches a real captured error's own
		// shape: {"startLine":15,"startColumn":16,"message":"Unexpected token '='"}. The rest of the source is
		// deliberately structurally VALID (calls iban.validate, defines both entry points) so this exercises
		// describeCompileFailure's own formatting, not a structural refusal. Carries its own "uses core.validation
		// as v;" line (fix/setup-alias, 2026-09-21) so resolveValidationAlias is a no-op here and the hardcoded
		// line number stays stable - the alias-insertion behaviour itself has its own dedicated tests.
		final String source = "uses core.validation as v;\nimport Fasttrack.lib.iban as iban;\nexport procedure check(record : .Contact)\nbegin\n"
			+ "  const r := iban.validate(record.iban, 'FR');\n  x = 1;\nend\n\nexport procedure setup()\nbegin\n  v.setDependencyToLocalNode();\nend\n";
		final ConstraintCompiler compiler = new FakeCompiler(ConstraintBodySubmissionTest.compileFailure(Integer.valueOf(6), Integer.valueOf(4), "Unexpected token '='"));
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3, compiler, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(source, null);
		ConstraintBodySubmissionTest.check("refused", !outcome.accepted);
		ConstraintBodySubmissionTest.check("names the line", outcome.message.contains("line 6"));
		ConstraintBodySubmissionTest.check("names the column", outcome.message.contains("column 4"));
		ConstraintBodySubmissionTest.check("carries the compiler's own message", outcome.message.contains("Unexpected token '='"));
		ConstraintBodySubmissionTest.check("quotes the offending source line", outcome.message.contains("x = 1;"));
		ConstraintBodySubmissionTest.check("tells the model to resubmit the WHOLE source", outcome.message.contains("resubmit the WHOLE corrected source"));
	}

	/** "Only a compiled body returns 'accepted'" (the coordinator's own instruction) - a structurally valid, never-compiling source is refused on every single attempt, exactly {@link #MAX_COMPILE_ATTEMPTS} times, and never once accepted. */
	private static void onlyACompiledBodyEverReturnsAccepted()
	{
		final ConstraintCompiler neverCompiles = new FakeCompiler(ConstraintBodySubmissionTest.compileFailure(null, null, "syntax error"));
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 5, 3, neverCompiles, false);
		for (int i = 0; i < 5; i++)
		{
			final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
			ConstraintBodySubmissionTest.check("attempt " + i + " never accepted", !outcome.accepted);
		}
		ConstraintBodySubmissionTest.check("never succeeded", !submission.succeeded());
		ConstraintBodySubmissionTest.check("source() stays null - nothing was ever actually compiled", submission.source() == null);
	}

	/** {@link ConstraintCompiler.Result#transportError} (the compile call itself could not be made, e.g. no usable revisionId) is reported and still counts as a failed compile attempt, exactly like a real compiler diagnostic would. */
	private static void transportErrorFromTheCompilerIsReportedAndCountsAsAFailedAttempt()
	{
		final ConstraintCompiler compiler = new FakeCompiler(new ConstraintCompiler.Result(false, Collections.<ConstraintCompiler.Error>emptyList(),
			"could not read the declared script's revisionId"));
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3, compiler, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("refused", !outcome.accepted);
		ConstraintBodySubmissionTest.check("the transport error text is reported", outcome.message.contains("could not read the declared script's revisionId"));
		ConstraintBodySubmissionTest.check("counted as a compile attempt", submission.compileAttempts() == 1);
	}

	// -------------------------------------------------------------------------------------------- fix/node-scope-multi-attribute

	/**
	 * item 1: the model's own {@code setup()} is deliberately WRONG for the two dependencies this constraint
	 * actually has ({@link #TWO_ATTRIBUTE_SOURCE} calls {@code setDependencyToLocalNode()}, which would only
	 * track the anchor {@code iban} field, silently missing every record whose {@code country} alone
	 * changed) - {@link ConstraintBodySubmission#submit} overwrites it with the factory's own deterministic
	 * block regardless, so the compiled/stored source never carries the model's mistake.
	 */
	private static void setupIsOverwrittenEvenWhenTheModelsOwnSetupIsWrongForTwoDependencies()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE_TWO_ATTRIBUTES, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.TWO_ATTRIBUTE_SOURCE, null);
		ConstraintBodySubmissionTest.check("accepted: " + outcome.message, outcome.accepted);
		ConstraintBodySubmissionTest.check("the model's own (wrong) setup is gone", !submission.source().contains("setDependencyToLocalNode"));
		ConstraintBodySubmissionTest.check("the factory's own dependency on the anchor is there instead",
			submission.source().contains("v.addDependencyToModify('/root/Contact/iban');"));
		ConstraintBodySubmissionTest.check("and on the other bound attribute too", submission.source().contains("v.addDependencyToModify('/root/Contact/country');"));
	}

	// -------------------------------------------------------------------------------------------------- fix/setup-alias

	/**
	 * The real captured failure this fix responds to, end to end through {@link ConstraintBodySubmission#submit}:
	 * a body with NO {@code core.validation} line at all must still be accepted, with the deterministic
	 * overwrite INSERTING a working {@code uses core.validation as v;} line (nothing else in this source
	 * claims {@code v}) - never emitting a {@code setup()} that calls through an alias the source never
	 * declares.
	 */
	private static void submitInsertsAValidationAliasLineWhenTheModelDeclaredNone()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE_TWO_ATTRIBUTES, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.NO_VALIDATION_SOURCE, null);
		ConstraintBodySubmissionTest.check("accepted: " + outcome.message, outcome.accepted);
		ConstraintBodySubmissionTest.check("a working uses core.validation as v; line was inserted", submission.source().contains("uses core.validation as v;"));
		ConstraintBodySubmissionTest.check("never duplicated", submission.source().indexOf("core.validation") == submission.source().lastIndexOf("core.validation"));
		ConstraintBodySubmissionTest.check("both dependency calls go through the inserted alias",
			submission.source().contains("v.addDependencyToModify('/root/Contact/iban');") && submission.source().contains("v.addDependencyToModify('/root/Contact/country');"));
		ConstraintBodySubmissionTest.check("the model's own body is untouched",
			submission.source().contains("const report := iban.validate(record.iban, record.country);"));
	}

	/** The model already declared {@code core.validation} under its own alias - {@code submit} must REUSE it, never overwrite with {@code v} or insert a second line. */
	private static void submitReusesAnExistingValidationAliasEndToEnd()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE_TWO_ATTRIBUTES, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.CUSTOM_ALIAS_SOURCE, null);
		ConstraintBodySubmissionTest.check("accepted: " + outcome.message, outcome.accepted);
		ConstraintBodySubmissionTest.check("the setup block calls through the model's own alias",
			submission.source().contains("validationContext.addDependencyToModify('/root/Contact/iban');")
				&& submission.source().contains("validationContext.addDependencyToModify('/root/Contact/country');"));
		ConstraintBodySubmissionTest.check("only one uses core.validation line, unchanged",
			submission.source().indexOf("core.validation") == submission.source().lastIndexOf("core.validation"));
	}

	/** item 4: when the row already has a stored message, no {@code message} argument is required, and {@link ConstraintBodySubmission#writtenMessage()} stays null even on success - nothing to backfill. */
	private static void messageNotRequiredWhenRowAlreadyHasOneWrittenMessageStaysNull()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, false);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("accepted without any message argument", outcome.accepted);
		ConstraintBodySubmissionTest.check("nothing to backfill - the row already had its own message", submission.writtenMessage() == null);
	}

	/** item 4: when the row has no stored message, a usable {@code message} argument is sanitised (collapsed to one line, trimmed) and exposed through {@link ConstraintBodySubmission#writtenMessage()} only on success. */
	private static void messageRequiredAndProvidedIsSanitizedAndReturnedOnSuccess()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, true);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, "  The IBAN\nis not valid.  ");
		ConstraintBodySubmissionTest.check("accepted: " + outcome.message, outcome.accepted);
		ConstraintBodySubmissionTest.check("message sanitised (collapsed, trimmed): '" + submission.writtenMessage() + "'",
			"The IBAN is not valid.".equals(submission.writtenMessage()));
	}

	/** item 4: a required message that is missing entirely is refused BEFORE the compiler is ever reached - the structural budget is spent, the compile budget is not. */
	private static void messageRequiredButMissingIsRefusedWithoutSpendingACompileAttempt()
	{
		final ConstraintCompiler poisoned = new ConstraintCompiler()
		{
			@Override
			public Result compile(final String pSource)
			{
				throw new AssertionError("the compiler must never be called when a required message is missing");
			}
		};
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3, poisoned, true);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, null);
		ConstraintBodySubmissionTest.check("refused: " + outcome.message, !outcome.accepted);
		ConstraintBodySubmissionTest.check("names the missing message", outcome.message.contains("'message' argument is required"));
		ConstraintBodySubmissionTest.check("never succeeded", !submission.succeeded());
		ConstraintBodySubmissionTest.check("zero compile attempts spent", submission.compileAttempts() == 0);
	}

	/** item 4: a required message that sanitises down to nothing (whitespace only) is refused the same way as a missing one. */
	private static void messageRequiredButBlankAfterSanitizingIsRefused()
	{
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, true);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, "     ");
		ConstraintBodySubmissionTest.check("refused: " + outcome.message, !outcome.accepted);
		ConstraintBodySubmissionTest.check("never succeeded", !submission.succeeded());
		ConstraintBodySubmissionTest.check("zero compile attempts spent", submission.compileAttempts() == 0);
	}

	/** item 4: the model-written message is bounded to the SAME length as {@code suggest_script_constraint}'s own (truncated, never refused for being merely too long). */
	private static void messageRequiredIsTruncatedToTheSameBoundAsSuggestionTime()
	{
		final StringBuilder longMessage = new StringBuilder();
		for (int i = 0; i < 40; i++)
		{
			longMessage.append("1234567890");
		}
		ConstraintBodySubmissionTest.check("fixture is longer than the bound", longMessage.length() > CapabilityLlmPass.USER_MESSAGE_MAX_LENGTH);
		final ConstraintBodySubmission submission = ConstraintBodySubmissionTest.newSubmission(ConstraintBodySubmissionTest.NODE, 3, 3,
			ConstraintBodySubmissionTest.ALWAYS_COMPILES, true);
		final ConstraintBodySubmission.Outcome outcome = submission.submit(ConstraintBodySubmissionTest.GOOD_SOURCE, longMessage.toString());
		ConstraintBodySubmissionTest.check("accepted (truncated, never refused for length alone): " + outcome.message, outcome.accepted);
		ConstraintBodySubmissionTest.check("truncated to the shared bound", submission.writtenMessage().length() == CapabilityLlmPass.USER_MESSAGE_MAX_LENGTH);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
