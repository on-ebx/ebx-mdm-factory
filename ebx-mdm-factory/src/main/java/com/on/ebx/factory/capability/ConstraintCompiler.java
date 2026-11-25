package com.on.ebx.factory.capability;

import java.util.List;

/**
 * fix/body-compile-loop (2026-09-21): abstraction over "compile this candidate EBX Script source" - the
 * real work is {@code get_script} (for the current {@code revisionId}) then {@code update_script}, reading
 * {@code data.validation.isSuccess}/{@code errors} back, but this interface deliberately says none of that:
 * it is injected into {@link ConstraintBodySubmission} so the compile-retry loop is unit-tested with a fake
 * compiler (no MCP, no EBX class anywhere in this package), exactly the same reasoning that already kept
 * this whole package free of {@code com.onwbp}/{@code com.orchestranetworks} types. The real implementation
 * ({@code ebx.CapabilityApplier}'s own, wrapping a scripting {@code McpClient}) lives in the EBX-bound
 * layer, the only place allowed to make an MCP call.
 * <p>
 * Second real apply of an LLM-authored body (suggestion 6, {@code Fasttrack.lib.iban.validate}): the
 * structural checks passed (after the line-break normalisation fix), the agent run ended with
 * {@code submit_constraint_body} answering "accepted", and ONLY THEN did the actual compile
 * ({@code get_script}/{@code update_script}, run AFTER the agent run had already finished) fail - the model
 * was told it had succeeded and never got a chance to fix the real problem (it had written {@code =}
 * assignments and invented {@code v.isNotNull(...)}, neither of which the structural checks catch, since
 * they were never meant to be a parser). This interface exists so the compile step can move INSIDE the
 * tool call instead.
 */
public interface ConstraintCompiler
{
	/** Compiles {@code pSource} once. Never throws - a transport/tool-level failure (the compile call itself could not be made) is reported through {@link Result#transportError}, exactly like a real compile failure from the caller's point of view: both count as a failed attempt. */
	Result compile(String pSource);

	/** One compile attempt's outcome. */
	final class Result
	{
		public final boolean success;
		/** Never null; empty when {@link #success} is true or when {@link #transportError} is set instead. */
		public final List<Error> errors;
		/** Set only when the compile call itself could not be made at all (e.g. the scripting server did not answer a usable {@code revisionId}) - never set when {@link #errors} carries real compiler diagnostics. */
		public final String transportError;

		public Result(final boolean pSuccess, final List<Error> pErrors, final String pTransportError)
		{
			this.success = pSuccess;
			this.errors = pErrors;
			this.transportError = pTransportError;
		}
	}

	/** One compiler diagnostic - {@code startLine}/{@code startColumn} are nullable (not every error carries a position). */
	final class Error
	{
		public final Integer startLine;
		public final Integer startColumn;
		public final String message;

		public Error(final Integer pStartLine, final Integer pStartColumn, final String pMessage)
		{
			this.startLine = pStartLine;
			this.startColumn = pStartColumn;
			this.message = pMessage;
		}
	}
}
