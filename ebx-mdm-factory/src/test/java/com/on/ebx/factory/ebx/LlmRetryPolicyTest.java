package com.on.ebx.factory.ebx;

/** JUnit-free, like the tests of the sibling classes: run the main, a failure throws. No network anywhere - pure parsing/decision only. */
public final class LlmRetryPolicyTest
{
	/** The exact live 429 body from {@code LlmRetryPolicy}'s own Javadoc - project {@code PersVol2}, 2026-09-25. */
	private static final String LIVE_429_DETAIL = "LLM call failed: HTTP 429 from https://api.openai.com/v1/chat/completions: { \"error\": { \"message\": "
		+ "\"Rate limit reached for gpt-4o in organization ... on tokens per min (TPM): Limit 30000, Used 27344, Requested 4153. Please try again in 2.994s. "
		+ "Visit https://platform.openai.com/account/rate-limits to learn more.\", \"type\": \"tokens\", \"param\": null, \"code\": \"rate_limit_exceeded\" } }";

	public static void main(final String[] pArgs)
	{
		LlmRetryPolicyTest.isRetryableStatus_429And503AreRetryable();
		LlmRetryPolicyTest.isRetryableStatus_clientErrorsAreNot();
		LlmRetryPolicyTest.httpStatusOf_readsTheLiveDetail();
		LlmRetryPolicyTest.httpStatusOf_readsAPlainLlmExceptionMessage();
		LlmRetryPolicyTest.httpStatusOf_noHttpFragment_isNull();
		LlmRetryPolicyTest.isRetryableMessage_liveDetailIsRetryable();
		LlmRetryPolicyTest.isRetryableMessage_401IsNotRetryable();
		LlmRetryPolicyTest.isRetryableMessage_noStatusAtAll_isNotRetryable();
		LlmRetryPolicyTest.retryAfterSeconds_parsesTheLiveDetail();
		LlmRetryPolicyTest.retryAfterSeconds_toleratesWholeSecondsNoDot();
		LlmRetryPolicyTest.retryAfterSeconds_absentWhenNotStated();
		LlmRetryPolicyTest.nextDelayMs_usesTheProvidersOwnDelay_roundedUp();
		LlmRetryPolicyTest.nextDelayMs_fallsBackToExponentialBackoff();
		LlmRetryPolicyTest.nextDelayMs_exponentialSequenceDoubles();
		LlmRetryPolicyTest.shouldRetry_withinBothBudgets_true();
		LlmRetryPolicyTest.shouldRetry_pastMaxRetries_false();
		LlmRetryPolicyTest.shouldRetry_pastMaxTotalWait_false();
		LlmRetryPolicyTest.shouldRetry_exactlyAtTheWaitCeiling_stillTrue();
		System.out.println("LlmRetryPolicyTest OK");
	}

	private static void isRetryableStatus_429And503AreRetryable()
	{
		LlmRetryPolicyTest.check("429 is retryable", LlmRetryPolicy.isRetryableStatus(429));
		LlmRetryPolicyTest.check("503 is retryable", LlmRetryPolicy.isRetryableStatus(503));
	}

	private static void isRetryableStatus_clientErrorsAreNot()
	{
		LlmRetryPolicyTest.check("400 is not retryable", !LlmRetryPolicy.isRetryableStatus(400));
		LlmRetryPolicyTest.check("401 is not retryable", !LlmRetryPolicy.isRetryableStatus(401));
		LlmRetryPolicyTest.check("403 is not retryable", !LlmRetryPolicy.isRetryableStatus(403));
		LlmRetryPolicyTest.check("404 is not retryable", !LlmRetryPolicy.isRetryableStatus(404));
		LlmRetryPolicyTest.check("500 is not retryable (only 429/503 are)", !LlmRetryPolicy.isRetryableStatus(500));
	}

	private static void httpStatusOf_readsTheLiveDetail()
	{
		final Integer status = LlmRetryPolicy.httpStatusOf(LlmRetryPolicyTest.LIVE_429_DETAIL);
		LlmRetryPolicyTest.check("reads 429 out of the live AgentLoop.Result.detail", status != null && status.intValue() == 429);
	}

	private static void httpStatusOf_readsAPlainLlmExceptionMessage()
	{
		final Integer status = LlmRetryPolicy.httpStatusOf("HTTP 503 from https://api.openai.com/v1/chat/completions: service unavailable");
		LlmRetryPolicyTest.check("reads 503 out of a plain LlmException-shaped message", status != null && status.intValue() == 503);
	}

	private static void httpStatusOf_noHttpFragment_isNull()
	{
		LlmRetryPolicyTest.check("no HTTP fragment: null", LlmRetryPolicy.httpStatusOf("connect timed out") == null);
		LlmRetryPolicyTest.check("null message: null", LlmRetryPolicy.httpStatusOf(null) == null);
	}

	private static void isRetryableMessage_liveDetailIsRetryable()
	{
		LlmRetryPolicyTest.check("the live 429 detail is retryable", LlmRetryPolicy.isRetryableMessage(LlmRetryPolicyTest.LIVE_429_DETAIL));
	}

	private static void isRetryableMessage_401IsNotRetryable()
	{
		LlmRetryPolicyTest.check("an auth failure is never retried",
			!LlmRetryPolicy.isRetryableMessage("HTTP 401 from https://api.openai.com/v1/chat/completions: invalid api key"));
	}

	private static void isRetryableMessage_noStatusAtAll_isNotRetryable()
	{
		LlmRetryPolicyTest.check("a non-HTTP failure (e.g. a connect timeout) is never retried", !LlmRetryPolicy.isRetryableMessage("connect timed out"));
	}

	private static void retryAfterSeconds_parsesTheLiveDetail()
	{
		final Double seconds = LlmRetryPolicy.retryAfterSeconds(LlmRetryPolicyTest.LIVE_429_DETAIL);
		LlmRetryPolicyTest.check("parses 2.994 out of 'Please try again in 2.994s.'", seconds != null && Math.abs(seconds.doubleValue() - 2.994) < 0.0001);
	}

	private static void retryAfterSeconds_toleratesWholeSecondsNoDot()
	{
		final Double seconds = LlmRetryPolicy.retryAfterSeconds("please try again in 3s");
		LlmRetryPolicyTest.check("a whole-second delay (no decimal point) parses too", seconds != null && seconds.doubleValue() == 3.0);
	}

	private static void retryAfterSeconds_absentWhenNotStated()
	{
		LlmRetryPolicyTest.check("a 503 with no stated delay: null", LlmRetryPolicy.retryAfterSeconds("HTTP 503 from https://x: service unavailable") == null);
		LlmRetryPolicyTest.check("null message: null", LlmRetryPolicy.retryAfterSeconds(null) == null);
	}

	private static void nextDelayMs_usesTheProvidersOwnDelay_roundedUp()
	{
		LlmRetryPolicyTest.check("2.994s becomes 2994ms (ceil of an exact millisecond value), never a shorter wait than the provider asked for",
			LlmRetryPolicy.nextDelayMs(1, LlmRetryPolicyTest.LIVE_429_DETAIL) == 2994L);
	}

	private static void nextDelayMs_fallsBackToExponentialBackoff()
	{
		LlmRetryPolicyTest.check("no stated delay, attempt 1: base backoff (2000ms)",
			LlmRetryPolicy.nextDelayMs(1, "HTTP 503 from https://x: overloaded") == 2000L);
	}

	private static void nextDelayMs_exponentialSequenceDoubles()
	{
		final String noDelayStated = "HTTP 503 from https://x: overloaded";
		LlmRetryPolicyTest.check("attempt 1: 2000ms", LlmRetryPolicy.nextDelayMs(1, noDelayStated) == 2000L);
		LlmRetryPolicyTest.check("attempt 2: 4000ms", LlmRetryPolicy.nextDelayMs(2, noDelayStated) == 4000L);
		LlmRetryPolicyTest.check("attempt 3: 8000ms", LlmRetryPolicy.nextDelayMs(3, noDelayStated) == 8000L);
		LlmRetryPolicyTest.check("attempt 4: 16000ms", LlmRetryPolicy.nextDelayMs(4, noDelayStated) == 16000L);
	}

	private static void shouldRetry_withinBothBudgets_true()
	{
		LlmRetryPolicyTest.check("attempt 1, nothing waited yet, a 2s delay: allowed", LlmRetryPolicy.shouldRetry(1, 0L, 2000L));
	}

	private static void shouldRetry_pastMaxRetries_false()
	{
		LlmRetryPolicyTest.check("attempt 5 (past MAX_RETRIES=4): refused however little was waited", !LlmRetryPolicy.shouldRetry(5, 0L, 1000L));
	}

	private static void shouldRetry_pastMaxTotalWait_false()
	{
		LlmRetryPolicyTest.check("55s already waited, a 10s delay would cross the 60s ceiling: refused", !LlmRetryPolicy.shouldRetry(2, 55000L, 10000L));
	}

	private static void shouldRetry_exactlyAtTheWaitCeiling_stillTrue()
	{
		LlmRetryPolicyTest.check("landing EXACTLY on the 60s ceiling is still allowed (<=, not <)", LlmRetryPolicy.shouldRetry(2, 50000L, 10000L));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
