package com.on.ebx.factory.capability;

/**
 * The state behind the {@code submit_explanation} local tool {@code ebx.CapabilityApplier} exposes for the
 * ONE follow-up language-model call that back-translates a language-model-authored constraint body into
 * plain language (a template body never needs this - {@code ConstraintBodyBuilder.buildTemplateExplanation}
 * is deterministic). Pure, same reasoning as {@link ConstraintBodySubmission}: kept out of the EBX-bound
 * class so the one real rule here (an empty explanation is refused) is unit-testable directly.
 */
public final class ConstraintExplanationSubmission
{
	private String text;
	private boolean normalized;

	/**
	 * One call of {@code submit_explanation} - normalised the same way {@code submit_constraint_body} is
	 * (fix/review-assets, 2026-09-21: same double-JSON-escape symptom is possible here too, a multi-sentence
	 * explanation sent as one physical line with literal backslash-n between sentences), but WITHOUT the EBX
	 * Script string-literal protection ({@link ConstraintBodyBuilder#normalizeEscapedLineBreaks}'s
	 * {@code pProtectStringLiterals=false}) - this text is prose, not code, so an apostrophe in "the record's
	 * value" must never be mistaken for opening a code string literal.
	 */
	public Outcome submit(final String pText)
	{
		final String normalizedText = ConstraintBodyBuilder.normalizeEscapedLineBreaks(pText, false);
		this.normalized = pText != null && !pText.equals(normalizedText);
		if (normalizedText == null || normalizedText.trim().isEmpty())
		{
			return new Outcome(false, "the explanation must not be empty.");
		}
		this.text = normalizedText.trim();
		return new Outcome(true, "accepted.");
	}

	public String text()
	{
		return this.text;
	}

	/** True when the accepted submission had its literal backslash-n/t/r/" sequences unescaped before being stored - see {@link ConstraintBodyBuilder#normalizeEscapedLineBreaks}'s own Javadoc. */
	public boolean wasNormalized()
	{
		return this.normalized;
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
