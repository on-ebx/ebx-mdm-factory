package com.on.ebx.factory.ebx;

import java.util.UUID;

import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.security.JsonWebToken;
import com.orchestranetworks.security.JsonWebTokenHandler;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.UserReference;

/**
 * The {@code EBXUID} cookie EBX itself sets at login, minted here for a session's user, so a call to EBX's own MCP
 * servers ({@code /ebx-assistant/mcp/v1/*}) runs as that user with no password (lot 3, measured 2026-09-24 through
 * {@code /connection/auth-probe}: accepted by data-model, match-merge and scripting).
 * <p>
 * Internal EBX API ({@code com.orchestranetworks.security.JsonWebTokenHandler}, found by {@code javap} on 6.3.0-dev):
 * {@code createToken(user, activitySeconds, sessionId, trustSeconds, sessionSeconds)} then {@code formatToken}. The
 * session id must not be null (EBX answers 500 without one); a fresh random one per token. Any failure - the class
 * gone after an upgrade, a changed signature, a refused user - returns null, and the caller falls back to the agent
 * account: this class never breaks a step. The cookie only ever travels to this node ({@link LocalEndpoint}).
 * <p>
 * <b>Documented exception (audit F2-08, README "Waves 4 and 5" point 6): the one private EBX API the module keeps, because no public API mints a
 * session token for another user.</b> {@code JsonWebTokenHandler} is named in THIS class only, and {@code docs/OPERATIONS.md} section 7.4 lists it.
 * What breaks if the editor renames or changes it: {@link #startupCheck} logs one ERROR at start, {@code /status} reports
 * {@link #startupMintable()} = false, and every call to EBX's own MCP servers falls back to the stored agent account (or fails, when the fallback
 * is forbidden). Nothing else in the module depends on it. (The auth-probe diagnostic of {@code AuthProbe} names it too, off by default: audit F2-09.)
 */
public final class SessionToken
{
	/**
	 * Covers one client's conversation (a model build and publication: one call up to 300 s; an agent run: 600 s wall
	 * clock) with a margin; shortened from 3600 s by the security review (2026-09-24) - a token that leaked would stay
	 * usable that long.
	 */
	static final int VALIDITY_SECONDS = 900;

	private SessionToken()
	{
	}

	/** {@code EBXUID=<token>} for {@code pSession}'s user, or null when it cannot be minted. */
	public static String cookie(final Session pSession, final Repository pRepository)
	{
		if (pSession == null)
		{
			return null;
		}
		// lot C1: a background job runs with the system session but FOR its launcher (Actor.as): EBX's servers must see
		// the launcher, with the launcher's rights - never the system user
		final Actor actor = Actor.explicit();
		if (actor != null && actor.userId != null && !actor.userId.trim().isEmpty())
		{
			return SessionToken.cookie(com.orchestranetworks.service.Profile.forUser(actor.userId), pRepository);
		}
		return SessionToken.cookie(pSession.getUserReference(), pRepository);
	}

	/** Same, for the session's own user only: private, so nothing in the module can mint a token for another login. */
	private static String cookie(final UserReference pUser, final Repository pRepository)
	{
		if (pUser == null || pRepository == null)
		{
			return null;
		}
		try
		{
			return SessionToken.mint(pUser, pRepository);
		}
		catch (final Throwable ex)
		{
			return null;
		}
	}

	/**
	 * F2-08/F2-09: the one door of {@code AuthProbe} to the internal token API. {@code EBXUID=<token>} for {@code pSession}'s OWN user, valid {@code pSeconds}, with a session id; throws
	 * whatever the API throws so the diagnostic can say what changed. Never for another login.
	 */
	static String probeCookie(final Session pSession, final Repository pRepository, final int pSeconds)
	{
		return SessionToken.mint(pSession.getUserReference(), pRepository, pSeconds);
	}

	/** The token value of a {@code EBXUID=<token>} cookie string, null when it has none. */
	static String tokenOf(final String pCookie)
	{
		final int eq = pCookie == null ? -1 : pCookie.indexOf('=');
		return eq < 0 || eq == pCookie.length() - 1 ? null : pCookie.substring(eq + 1);
	}

	/** The cookie, or null for an empty value; throws whatever the internal API throws (the class gone, a changed signature). */
	private static String mint(final UserReference pUser, final Repository pRepository)
	{
		return SessionToken.mint(pUser, pRepository, SessionToken.VALIDITY_SECONDS);
	}

	private static String mint(final UserReference pUser, final Repository pRepository, final int pSeconds)
	{
		final JsonWebTokenHandler handler = JsonWebTokenHandler.getInstance(pRepository);
		final JsonWebToken token = handler.createToken(pUser, pSeconds, UUID.randomUUID().toString(), pSeconds, pSeconds);
		final String value = handler.formatToken(token);
		return value == null || value.isEmpty() ? null : JsonWebTokenHandler.COOKIE_NAME + "=" + value;
	}

	// ------------------------------------------------------------------------------------------------ startup self-test

	/** The outcome of {@link #startupCheck}: null until the check ran; shown on {@code /status} for administrators. */
	private static volatile Boolean startupMintable;

	/** Whether the startup check could mint a token; null when it has not run (or could not run at all). */
	public static Boolean startupMintable()
	{
		return SessionToken.startupMintable;
	}

	/**
	 * Audit F-16: once, at repository startup, proves that the INTERNAL token API this module depends on still works on this build -
	 * a version that renames or changes {@code JsonWebTokenHandler} would otherwise surface only as every generation step failing later. Mints
	 * one token for the system session's user and throws it away (no MCP server is called: this proves the class and its signature, not
	 * that the servers accept the token). One ERROR line when it cannot, naming the consequence. Never throws.
	 */
	static void startupCheck(final Repository pRepository, final Session pSystemSession)
	{
		String failure = null;
		try
		{
			final UserReference user = pSystemSession == null ? null : pSystemSession.getUserReference();
			if (user == null || pRepository == null)
			{
				failure = "no system session to mint it for";
			}
			else if (SessionToken.mint(user, pRepository) == null)
			{
				failure = "EBX returned an empty token";
			}
		}
		catch (final Throwable ex)
		{
			failure = ex.getClass().getName() + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
		}
		SessionToken.startupMintable = Boolean.valueOf(failure == null);
		if (failure == null)
		{
			return;
		}
		boolean fallbackAllowed = true;
		try
		{
			fallbackAllowed = CredentialPolicy.fallbackAllowed(FactoryInstaller.dataset(pRepository));
		}
		catch (final Throwable ex)
		{
			// the policy cannot be read: say the safe thing below
			fallbackAllowed = false;
		}
		try
		{
			LoggingCategory.getKernel().error(SessionToken.startupFailureMessage(failure, fallbackAllowed));
		}
		catch (final Throwable ex)
		{
			// a logging failure must never take startup down
		}
	}

	/** Pure: the one ERROR line of a failed startup check. */
	static String startupFailureMessage(final String pFailure, final boolean pFallbackAllowed)
	{
		return "[mdm-factory] a session token for EBX's own MCP servers cannot be minted (" + pFailure + "): "
			+ (pFallbackAllowed ? "every call to /ebx-assistant/mcp/v1/* will run as the stored AGENT ACCOUNT instead of the caller, because the agent fallback is allowed (setting agentFallback)."
				: "every call to /ebx-assistant/mcp/v1/* (model builds, scripts, matching configuration) will FAIL, because the agent fallback is not allowed (setting agentFallback = FORBIDDEN, or AUTO on a TARGET instance).")
			+ " Likely cause: an EBX upgrade changed com.orchestranetworks.security.JsonWebTokenHandler.";
	}
}
