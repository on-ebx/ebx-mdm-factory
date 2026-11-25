package com.on.ebx.factory.capability;

/**
 * The retry/validation state behind the {@code submit_constraint_body} local tool {@code
 * ebx.CapabilityApplier} exposes to the language model when a target function does not return {@code
 * boolean} (a deterministic template is used instead - see {@code ConstraintBodyBuilder}). Pure: no EBX
 * class, no {@code com.on.ebx.agent} type either - {@code ebx.CapabilityApplier} is the only caller,
 * wrapping {@link #submit} in a {@code LocalTool.Handler} so this class itself stays unit-testable without
 * an {@code AgentLoop}/HTTP layer, the same split {@code capability.CapabilityLlmPass} (pure) /
 * {@code ebx.CapabilityLlmSuggester} (EBX-bound) already established for the suggestion pass. Not
 * thread-safe: one instance per language-model run, like every other {@code Session}-shaped class in this
 * codebase.
 * <p>
 * <b>fix/body-compile-loop (2026-09-21)</b>: a REAL apply of an LLM-authored body (suggestion 6,
 * {@code Fasttrack.lib.iban.validate}) passed every structural check, the agent run ended with
 * {@code submit_constraint_body} answering "accepted", and ONLY THEN did the actual compile
 * ({@code get_script}/{@code update_script}) fail - run AFTER the agent run had already ended, so the model
 * was told it had succeeded and never got a chance to see the real compiler error or fix it (it had
 * written {@code =} assignments and invented {@code v.isNotNull(...)}, neither of which a structural check
 * is meant to catch). The compile step now happens INSIDE {@link #submit}, through an injected
 * {@link ConstraintCompiler} - "accepted" is only ever returned for a body that has ACTUALLY compiled.
 * Structural refusals and compile failures are bounded by two SEPARATE counters
 * ({@link #attempts()}/{@link #compileAttempts()}) - a model that keeps failing structural checks never
 * touches the compile budget at all; only a structurally valid submission spends a compile attempt, so a
 * model that fixes its structure on attempt 3 still gets a full compile budget of its own.
 * <p>
 * <b>fix/node-scope-multi-attribute (2026-09-21)</b>: two more real captures.
 * <ol>
 * <li><b>{@code setup()} is now built by the factory, never trusted from the model</b> (item 1's own
 * choice: OVERWRITE rather than reject). {@link #submit} still runs {@link
 * ConstraintBodyBuilder#checkSubmittedSource} on exactly what the model wrote (so a missing {@code setup(}
 * entry point is still refused structurally, same as before), but once that passes, whatever {@code
 * setup()} block the model actually wrote is REPLACED, verbatim, by {@link
 * ConstraintBodyBuilder#buildSetupBlock} ({@code v.setDependencyToLocalNode()} for one dependency
 * attribute, one {@code v.addDependencyToModify(...)} call per attribute otherwise) BEFORE the result is
 * ever compiled - the model's own {@code setup()} content, right or wrong, never reaches the compiler at
 * all. Chosen over "refuse a submission whose setup differs from the expected one" because computing the
 * exact expected {@code addDependencyToModify} list correctly requires knowing every distinct bound
 * attribute, which is exactly the kind of bookkeeping a model is more likely to get wrong (and would then
 * spend a whole compile/structural attempt discovering) than Java is, from data it already has - overwriting
 * removes an entire class of retries rather than diagnosing them.</li>
 * <li><b>the record-facing message may now be WRITTEN by the model itself</b>, through a required second
 * argument, when the suggestion has no stored one yet ({@code pRequiresWrittenMessage} - item 4: a
 * Java-built fallback that echoed the function's own raw documentation sentence proved unusable to a
 * business reviewer). Validated and sanitised the SAME way {@code suggest_script_constraint}'s own
 * {@code message} argument is ({@link CapabilityLlmPass#sanitizeUserMessage} - same package, reused
 * directly): required, single line, bounded to {@value CapabilityLlmPass#USER_MESSAGE_MAX_LENGTH}
 * characters. Exposed only on success ({@link #writtenMessage()}), for the caller to backfill onto the
 * row's own {@code userMessage} - a message from a REJECTED attempt is never surfaced, since the model may
 * revise it on a later attempt along with the source.</li>
 * </ol>
 * <p>
 * <b>fix/setup-alias (2026-09-21)</b>: the overwrite above hardcoded the alias {@code v} for {@code
 * setDependencyToLocalNode()}/{@code addDependencyToModify(...)} - but nothing guaranteed the model's own
 * source declared {@code uses core.validation as v;} at all. A real body that compiled clean an hour earlier
 * had exactly the headers {@code uses core.message as msg;}, {@code uses core.json as json;}, {@code import
 * Fasttrack.lib.iban as iban;} - no {@code core.validation} line, since its own (model-written, soon to be
 * discarded) {@code setup()} was empty and never needed one. Overwriting {@code setup()} with a hardcoded
 * {@code v.} call against that source would fail to compile on a line the model never wrote, and the error
 * fed back ("undeclared unit 'v'" or similar) would make no sense to it. {@link #submit} now resolves the
 * real alias from the model's OWN submitted source first ({@link ConstraintBodyBuilder#resolveValidationAlias}
 * - reuses the model's existing {@code uses core.validation as <alias>;} when present, replaces a bare
 * {@code uses core.validation;} with an aliased one in place, or inserts a fresh {@code uses core.validation
 * as <alias>;} line after the last existing {@code uses} line otherwise, picking an alias that never collides
 * with another {@code uses}/{@code import} alias already in the source) and passes THAT alias, and that
 * possibly-adjusted source, into {@link ConstraintBodyBuilder#buildSetupBlock}/{@link
 * ConstraintBodyBuilder#replaceSetupBlock} - the deterministic template path is unaffected (it always emits
 * {@code uses core.validation as v;} itself, so {@code v} is always correct there).
 */
public final class ConstraintBodySubmission
{
	private final String importName;
	private final String functionName;
	private final ConstraintBodyBuilder.ScopeDecision scope;
	private final String entityName;
	private final int maxStructuralAttempts;
	private final int maxCompileAttempts;
	private final ConstraintCompiler compiler;
	private final boolean requiresWrittenMessage;

	private int structuralAttempts;
	private int compileAttempts;
	private String source;
	private String writtenMessage;
	private String lastError;
	private boolean success;
	private boolean normalized;

	public ConstraintBodySubmission(final String pImportName, final String pFunctionName, final ConstraintBodyBuilder.ScopeDecision pScope,
		final String pEntityName, final int pMaxStructuralAttempts, final int pMaxCompileAttempts, final ConstraintCompiler pCompiler,
		final boolean pRequiresWrittenMessage)
	{
		this.importName = pImportName;
		this.functionName = pFunctionName;
		this.scope = pScope;
		this.entityName = pEntityName;
		this.maxStructuralAttempts = pMaxStructuralAttempts;
		this.maxCompileAttempts = pMaxCompileAttempts;
		this.compiler = pCompiler;
		this.requiresWrittenMessage = pRequiresWrittenMessage;
	}

	/**
	 * One call of {@code submit_constraint_body}: NORMALISED first ({@link
	 * ConstraintBodyBuilder#normalizeEscapedLineBreaks}, EBX-Script-aware - the real captured double-JSON-
	 * escaped-line-break case), then structurally checked on that SAME normalised text ({@link
	 * ConstraintBodyBuilder#checkSubmittedSource}, bounded to {@link #maxStructuralAttempts} - a structural
	 * refusal here never touches the compile budget). When {@code pMessage} is required
	 * ({@link #requiresWrittenMessage}) and missing/unusable after sanitising, that also counts as a
	 * structural-style refusal (no MCP call was made, so it costs nothing to retry). Only THEN is
	 * {@code setup()} overwritten deterministically ({@link ConstraintBodyBuilder#buildSetupBlock}/{@link
	 * ConstraintBodyBuilder#replaceSetupBlock} - see this class's own Javadoc for why) and handed to the
	 * injected {@link ConstraintCompiler} (bounded to {@link #maxCompileAttempts}, its own separate
	 * counter) - a compile failure is fed back with every error's line/column/message AND the offending
	 * source line quoted, and the model is told to fix ALL of them and resubmit the WHOLE source. {@link
	 * #source()} (what a caller would send on for publication) and what actually compiled are always the
	 * identical text - "accepted" is returned once, only for a body that just compiled successfully.
	 */
	public Outcome submit(final String pSource, final String pMessage)
	{
		this.structuralAttempts++;
		if (this.structuralAttempts > this.maxStructuralAttempts)
		{
			return new Outcome(false, "too many failed attempts (" + this.maxStructuralAttempts + "): stop, do not call this tool again.");
		}
		final String normalizedSource = ConstraintBodyBuilder.normalizeEscapedLineBreaks(pSource, true);
		this.normalized = pSource != null && !pSource.equals(normalizedSource);
		final String violation = ConstraintBodyBuilder.checkSubmittedSource(normalizedSource, this.importName, this.functionName, this.scope, true);
		if (violation != null)
		{
			this.lastError = violation;
			return new Outcome(false, "rejected: " + violation + " Fix it and call submit_constraint_body again.");
		}
		String sanitizedMessage = null;
		if (this.requiresWrittenMessage)
		{
			final String normalizedMessage = ConstraintBodyBuilder.normalizeEscapedLineBreaks(pMessage, false);
			if (normalizedMessage == null || normalizedMessage.trim().isEmpty())
			{
				this.lastError = "the 'message' argument is required (this suggestion has no stored message yet): the short sentence a business user "
					+ "reads on the flagged record - not a developer, no code identifiers.";
				return new Outcome(false, "rejected: " + this.lastError + " Fix it and call submit_constraint_body again.");
			}
			sanitizedMessage = CapabilityLlmPass.sanitizeUserMessage(normalizedMessage);
			if (sanitizedMessage.isEmpty())
			{
				this.lastError = "the 'message' argument must contain readable text once sanitised.";
				return new Outcome(false, "rejected: " + this.lastError + " Fix it and call submit_constraint_body again.");
			}
		}
		// fix/setup-alias (2026-09-21): resolve the REAL alias (and, if needed, the adjusted source that
		// declares it) from what the model actually submitted - never assume "v" is declared, see this class's
		// own Javadoc for the real captured body that had no core.validation line at all.
		final ConstraintBodyBuilder.ValidationAliasResolution aliasResolution = ConstraintBodyBuilder.resolveValidationAlias(normalizedSource);
		final String setupBlock = ConstraintBodyBuilder.buildSetupBlock(this.scope, this.entityName, aliasResolution.alias);
		final String sourceWithDeterministicSetup = ConstraintBodyBuilder.replaceSetupBlock(aliasResolution.source, setupBlock);
		this.compileAttempts++;
		if (this.compileAttempts > this.maxCompileAttempts)
		{
			return new Outcome(false, "too many failed compile attempts (" + this.maxCompileAttempts + "): stop, do not call this tool again.");
		}
		final ConstraintCompiler.Result compileResult = this.compiler.compile(sourceWithDeterministicSetup);
		if (!compileResult.success)
		{
			this.lastError = ConstraintBodySubmission.describeCompileFailure(compileResult, sourceWithDeterministicSetup);
			return new Outcome(false, this.lastError + " Fix ALL of the above and resubmit the WHOLE corrected source.");
		}
		this.source = sourceWithDeterministicSetup;
		this.writtenMessage = sanitizedMessage;
		this.success = true;
		return new Outcome(true, "accepted.");
	}

	/**
	 * Every compiler diagnostic, one per line, each naming its position (when given) AND quoting the
	 * offending source line (trimmed, by its own 1-based line number) - the real captured defect this
	 * responds to was a compile error the model never even saw; a bare list of messages with no context
	 * would repeat the same mistake in a smaller way. {@link ConstraintCompiler.Result#transportError} (the
	 * compile call itself could not be made - never a real compiler diagnostic) is reported as-is instead.
	 */
	private static String describeCompileFailure(final ConstraintCompiler.Result pResult, final String pSource)
	{
		if (pResult.transportError != null)
		{
			return "the compile check itself could not be completed: " + pResult.transportError;
		}
		if (pResult.errors == null || pResult.errors.isEmpty())
		{
			return "the submitted source did not compile (no detailed errors were reported).";
		}
		final String[] lines = pSource == null ? new String[0] : pSource.split("\n", -1);
		final StringBuilder out = new StringBuilder("the submitted source did not compile:");
		for (final ConstraintCompiler.Error error : pResult.errors)
		{
			out.append("\n- ");
			if (error.startLine != null)
			{
				out.append("line ").append(error.startLine);
				if (error.startColumn != null)
				{
					out.append(", column ").append(error.startColumn);
				}
				out.append(": ");
			}
			out.append(error.message == null || error.message.trim().isEmpty() ? "(no message)" : error.message.trim());
			if (error.startLine != null && error.startLine.intValue() >= 1 && error.startLine.intValue() <= lines.length)
			{
				out.append(" | source line ").append(error.startLine).append(": \"").append(lines[error.startLine.intValue() - 1].trim()).append('"');
			}
		}
		return out.toString();
	}

	/** True when the accepted (or last checked) submission had its literal backslash-n/t/r/" sequences unescaped before checking - see {@link ConstraintBodyBuilder#normalizeEscapedLineBreaks}'s own Javadoc for when and why. Recorded in the apply step's own detail so a run that hit this is visible without reading raw AgentStep payloads. */
	public boolean wasNormalized()
	{
		return this.normalized;
	}

	public boolean succeeded()
	{
		return this.success;
	}

	/** Non-null only once {@link #succeeded()} is true - the ACTUALLY-COMPILED source, identical to what the injected {@link ConstraintCompiler} just accepted. */
	public String source()
	{
		return this.source;
	}

	/** fix/node-scope-multi-attribute (2026-09-21), item 4: non-null only when {@link #succeeded()} is true AND a message was actually required ({@code pRequiresWrittenMessage}, constructor) - the sanitised, model-written record-facing message, for the caller to backfill onto the row's own {@code userMessage}. Null when the row already had one (nothing to backfill) or when the run never succeeded. */
	public String writtenMessage()
	{
		return this.writtenMessage;
	}

	/** The most recent violation/compile-failure text, or null if {@link #submit} was never called or its last call succeeded. */
	public String lastError()
	{
		return this.lastError;
	}

	/** How many times a structural check actually ran (every {@link #submit} call, whatever the outcome - the same counter/meaning this method had before compilation moved inside this class). */
	public int attempts()
	{
		return this.structuralAttempts;
	}

	/** How many times a STRUCTURALLY VALID submission was actually handed to the compiler - a separate, independently bounded counter from {@link #attempts()}: a submission refused for a structural reason never increments this one. */
	public int compileAttempts()
	{
		return this.compileAttempts;
	}

	/** The result of one {@link #submit} call - {@link #accepted} mirrors what the caller wraps into {@code LocalTool.Result.ok}/{@code .error}. */
	public static final class Outcome
	{
		public final boolean accepted;
		public final String message;

		Outcome(final boolean pAccepted, final String pMessage)
		{
			this.accepted = pAccepted;
			this.message = pMessage;
		}
	}
}
