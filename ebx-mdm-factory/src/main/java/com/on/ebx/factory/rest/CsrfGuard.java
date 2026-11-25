package com.on.ebx.factory.rest;

import java.util.Locale;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/**
 * Cross-site request forgery guard of the factory's REST API (security review, 2026-09-24). EBX accepts its session
 * cookie on these routes, and most POST routes take only path and query parameters, so a page on another site could
 * make an administrator's browser post one (a project deletion, a master reset). Every state-changing request must
 * therefore carry either an {@code Authorization} header (Basic, token: a browser never adds one cross-site) or an
 * {@code X-Requested-With} header (a custom header forces a CORS pre-flight, which EBX does not grant). Safe methods
 * (GET, HEAD, OPTIONS) are not affected. No screen of the factory calls these routes from the browser.
 */
@Provider
public final class CsrfGuard implements ContainerRequestFilter
{
	@Override
	public void filter(final ContainerRequestContext pContext)
	{
		if (!CsrfGuard.mustCheck(pContext.getMethod()))
		{
			return;
		}
		if (CsrfGuard.present(pContext.getHeaderString("Authorization")) || CsrfGuard.present(pContext.getHeaderString("X-Requested-With")))
		{
			return;
		}
		pContext.abortWith(Response.status(Response.Status.FORBIDDEN)
			.entity("{\"error\":\"A state-changing request needs an Authorization or an X-Requested-With header (cross-site request protection).\"}").build());
	}

	/** Pure: POST, PUT, PATCH and DELETE are checked. */
	static boolean mustCheck(final String pMethod)
	{
		final String method = pMethod == null ? "" : pMethod.toUpperCase(Locale.ROOT);
		return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method) || "DELETE".equals(method);
	}

	private static boolean present(final String pValue)
	{
		return pValue != null && !pValue.trim().isEmpty();
	}
}
