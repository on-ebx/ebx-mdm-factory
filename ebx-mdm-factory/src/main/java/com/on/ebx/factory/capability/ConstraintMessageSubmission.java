package com.on.ebx.factory.capability;

/**
 * The state behind the {@code submit_constraint_message} local tool {@code ebx.CapabilityApplier} exposes for
 * the ONE-shot follow-up language-model call that writes a record-facing message for a TEMPLATE (a
 * boolean-returning target function) constraint whose row has no stored {@code userMessage} yet - "Re-apply"
 * lot (2026-09-21), extending the "the meaning must come from the LLM, not from Java" rule the non-boolean
 * LLM-authored path already follows ({@code ConstraintBodySubmission}'s own required {@code message}
 * argument, apply-ux fix) to the deterministic template path too: a template body is just as record-facing to
 * a business reviewer as an LLM-authored one, and the two phone constraints applied before this lot still
 * carry the OLD Java-built technical wording precisely because the template path never tried a language
 * model at all. Pure, same reasoning as {@link ConstraintExplanationSubmission}: sanitised the SAME way
 * {@code suggest_script_constraint}'s own {@code message} argument is
 * ({@link CapabilityLlmPass#sanitizeUserMessage} - single line, collapsed whitespace, bounded to
 * {@value CapabilityLlmPass#USER_MESSAGE_MAX_LENGTH} characters), refused only when empty/unusable after
 * sanitising - never for being merely long or multi-line (truncated instead).
 */
public final class ConstraintMessageSubmission
{
	private String message;

	/** One call of {@code submit_constraint_message}. */
	public Outcome submit(final String pRawMessage)
	{
		final String sanitized = CapabilityLlmPass.sanitizeUserMessage(pRawMessage);
		if (sanitized.isEmpty())
		{
			return new Outcome(false, "the message must not be empty.");
		}
		this.message = sanitized;
		return new Outcome(true, "accepted.");
	}

	/** The accepted, sanitised message - null until a submission is accepted. */
	public String message()
	{
		return this.message;
	}

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
