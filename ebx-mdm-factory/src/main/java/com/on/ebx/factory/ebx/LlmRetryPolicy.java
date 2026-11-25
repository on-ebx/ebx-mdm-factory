package com.on.ebx.factory.ebx;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Pure retry math for an LLM call that failed with a transient provider error (a rate limit, or the
 * provider reporting itself overloaded) - found live 2026-09-25, project {@code PersVol2}: an agent run
 * died on its 7th step with {@code "LLM call failed: HTTP 429 from https://api.openai.com/v1/
 * chat/completions: { \"error\": { \"message\": \"Rate limit reached for gpt-4o in organization ... on
 * tokens per min (TPM): Limit 30000, Used 27344, Requested 4153. Please try again in 2.994s. ...\" } }"}.
 * <p>
 * <b>What this class does NOT do</b>: {@code on-ebx-agent.jar}'s own {@code LlmClient} already retries a
 * 429/5xx internally, up to 3 attempts, honouring a numeric {@code Retry-After} HEADER when the provider
 * sends one (read by {@code javap -c} on {@code LlmClient.postWithRetry}/{@code backoffMs}, 2026-09-25) -
 * but OpenAI's chat-completions 429 carries no such header, only the delay written INSIDE the JSON error
 * body ("Please try again in 2.994s"), which {@code LlmClient} never parses, so it falls back to its own
 * tiny default backoff (200ms, doubling) and gives up after 3 attempts - nowhere near the ~3s a real TPM
 * window needs to clear. {@code LlmClient} and {@code AgentLoop} are both {@code final} classes of a
 * DIFFERENT repository ({@code on-ebx-agent}, out of this repository's scope to change - the same
 * constraint {@link ProseAnswerRecovery}'s own Javadoc already documents for a different problem) with no
 * interface to decorate: {@code AgentLoop} holds and calls its {@code LlmClient} internally, one call per
 * step, with no hook this module can intercept BEFORE that call to add a second layer of retrying around
 * it. The only failure surface {@code AgentLoop.run(...)} exposes to a caller is its own {@code Result}
 * ({@code status=FAILED}, {@code detail="LLM call failed: " + message} - read the same way, by {@code
 * javap -v} on the constant pool of {@code AgentLoop.class}) - a WHOLE fresh conversation is therefore the
 * only retry unit {@code AgentRuntime} can drive, exactly the same constraint (and the same "a fresh run
 * is not a workaround, it is the only option the library exposes") {@link ProseAnswerRecovery} already
 * documents for its own, unrelated follow-up. {@code AgentRuntime.run} applies this policy around: (1) the
 * ONE direct {@code LlmClient.send} call it owns outright ({@code testConnection}), a genuine per-call
 * retry; (2) the {@code AgentLoop.run(...)} call itself, retried as a whole run when its {@code Result}
 * reports a retryable failure - the closest equivalent this library's shape allows to "retry the failing
 * LLM call", never a partial replay of the steps already completed.
 */
public final class LlmRetryPolicy
{
	/** Never more than this many retry ATTEMPTS after the original call (so up to 5 calls total). */
	public static final int MAX_RETRIES = 4;
	/** Never more than this much time spent WAITING between attempts, cumulative - a hard ceiling independent of {@link #MAX_RETRIES}. */
	public static final long MAX_TOTAL_WAIT_MS = 60000L;
	/** First retry's delay when the provider states none - doubles every subsequent attempt (2s, 4s, 8s, 16s). */
	static final long BASE_BACKOFF_MS = 2000L;

	/** Matches the exact phrasing OpenAI's rate-limit error body uses - "Please try again in 2.994s." (the "Please" and trailing punctuation are optional: match only the fragment this class actually needs). */
	private static final Pattern TRY_AGAIN_PATTERN = Pattern.compile("try again in\\s*([0-9]+(?:\\.[0-9]+)?)\\s*s", Pattern.CASE_INSENSITIVE);
	/** Matches this module's own {@code "HTTP <status>"} prefix - both {@code AgentLoop}'s {@code Result.detail} ("LLM call failed: HTTP 429 from ...") and {@code LlmException.getMessage()} itself use this exact shape (read by {@code javap -c}, 2026-09-25). */
	private static final Pattern HTTP_STATUS_PATTERN = Pattern.compile("HTTP\\s+([0-9]{3})");

	private LlmRetryPolicy()
	{
	}

	/** 429 (rate limit) and 503 (provider reports itself overloaded) only - never 400/401/403/404, which are never going to succeed on retry. */
	public static boolean isRetryableStatus(final int pHttpStatus)
	{
		return pHttpStatus == 429 || pHttpStatus == 503;
	}

	/**
	 * Reads an HTTP status out of a failure message/detail string this module builds or receives verbatim
	 * from {@code on-ebx-agent.jar} (a {@code LlmException} message, or an {@code AgentLoop.Result.detail}) -
	 * both are of the shape {@code "... HTTP <nnn> ..."}. Never throws; {@code null} when no such fragment
	 * is present (a non-HTTP failure - a connect timeout, a malformed URL - is never retried).
	 */
	public static Integer httpStatusOf(final String pMessage)
	{
		if (pMessage == null)
		{
			return null;
		}
		final Matcher matcher = LlmRetryPolicy.HTTP_STATUS_PATTERN.matcher(pMessage);
		if (!matcher.find())
		{
			return null;
		}
		try
		{
			return Integer.valueOf(matcher.group(1));
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	/** {@link #isRetryableStatus} applied to whatever {@link #httpStatusOf} reads out of {@code pMessage} - {@code false} when no HTTP status is found at all. */
	public static boolean isRetryableMessage(final String pMessage)
	{
		final Integer status = LlmRetryPolicy.httpStatusOf(pMessage);
		return status != null && LlmRetryPolicy.isRetryableStatus(status.intValue());
	}

	/**
	 * The provider's own stated delay ("Please try again in 2.994s") in seconds, or {@code null} when the
	 * message carries no such fragment (a 503 typically does not) - the caller falls back to {@link
	 * #nextDelayMs}'s own exponential default in that case. Never throws.
	 */
	public static Double retryAfterSeconds(final String pMessage)
	{
		if (pMessage == null)
		{
			return null;
		}
		final Matcher matcher = LlmRetryPolicy.TRY_AGAIN_PATTERN.matcher(pMessage);
		if (!matcher.find())
		{
			return null;
		}
		try
		{
			return Double.valueOf(matcher.group(1));
		}
		catch (final NumberFormatException ex)
		{
			return null;
		}
	}

	/**
	 * How long to wait before retry attempt {@code pAttempt} (1-based: 1 = the first retry, right after the
	 * original call failed) - the provider's own stated delay ({@link #retryAfterSeconds}) when {@code
	 * pMessage} carries one, rounded up to the millisecond so a sub-second wait never rounds down to zero;
	 * otherwise exponential backoff from {@link #BASE_BACKOFF_MS} (2s, 4s, 8s, 16s for attempts 1-4).
	 */
	public static long nextDelayMs(final int pAttempt, final String pMessage)
	{
		final Double seconds = LlmRetryPolicy.retryAfterSeconds(pMessage);
		if (seconds != null && seconds.doubleValue() > 0)
		{
			return (long) Math.ceil(seconds.doubleValue() * 1000.0);
		}
		final int exponent = Math.max(0, pAttempt - 1);
		return LlmRetryPolicy.BASE_BACKOFF_MS << exponent;
	}

	/**
	 * Whether attempt {@code pAttempt} (1-based) may actually be made, given {@code pWaitedSoFarMs} already
	 * spent waiting on earlier attempts and {@code pNextDelayMs} ({@link #nextDelayMs}'s own answer) this
	 * attempt would add: both {@link #MAX_RETRIES} and {@link #MAX_TOTAL_WAIT_MS} are hard ceilings,
	 * independent of each other - either one being exceeded stops the retries and lets the original failure
	 * surface exactly as it did before this policy existed.
	 */
	public static boolean shouldRetry(final int pAttempt, final long pWaitedSoFarMs, final long pNextDelayMs)
	{
		return pAttempt <= LlmRetryPolicy.MAX_RETRIES && pWaitedSoFarMs + pNextDelayMs <= LlmRetryPolicy.MAX_TOTAL_WAIT_MS;
	}
}
