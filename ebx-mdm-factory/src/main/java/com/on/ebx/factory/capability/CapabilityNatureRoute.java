package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Which applier handles a {@code CapabilitySuggestion} row, decided from its own {@code nature} and nothing else - the one
 * dispatch the review screen and the REST routes ({@code .../capabilities/{id}/apply}, {@code .../capabilities/apply},
 * {@code .../capabilities/{id}/remove}) share through {@code ebx.CapabilityDispatcher} (lot M5, 2026-10-04: until then only the
 * screen knew that a {@code SCRIPT_DERIVATION} goes to {@code ebx.DerivationApplier}; a project driven over REST answered "only
 * SCRIPT_CONSTRAINT suggestions can be applied" and its derived attribute stayed unapplied). Pure: no EBX class.
 */
public final class CapabilityNatureRoute
{
	/** The applier a nature goes to. */
	public enum Route
	{
		/** {@code ebx.CapabilityApplier} - a {@code SCRIPT_CONSTRAINT}. */
		CONSTRAINT,
		/** {@code ebx.DerivationApplier} - a {@code SCRIPT_DERIVATION}. */
		DERIVATION,
		/** Nothing applies it ({@code EXTERNAL_SERVICE}, or an unknown or blank nature): a clear refusal. */
		REFUSED
	}

	private CapabilityNatureRoute()
	{
	}

	/** {@code SCRIPT_CONSTRAINT} to {@link Route#CONSTRAINT}, {@code SCRIPT_DERIVATION} to {@link Route#DERIVATION}, anything else (also {@code null}) to {@link Route#REFUSED}. */
	public static Route route(final String pNature)
	{
		if ("SCRIPT_CONSTRAINT".equals(pNature))
		{
			return Route.CONSTRAINT;
		}
		if ("SCRIPT_DERIVATION".equals(pNature))
		{
			return Route.DERIVATION;
		}
		return Route.REFUSED;
	}

	/** The plain-words refusal for a {@link Route#REFUSED} row: says which natures can be applied and what this one is. */
	public static String refusalMessage(final String pSuggestionId, final String pNature)
	{
		final String nature = pNature == null || pNature.trim().isEmpty() ? "of unknown nature" : pNature;
		if ("EXTERNAL_SERVICE".equals(pNature))
		{
			return "suggestion '" + pSuggestionId + "' is EXTERNAL_SERVICE: nothing applies it - only SCRIPT_CONSTRAINT and SCRIPT_DERIVATION suggestions can be applied";
		}
		return "suggestion '" + pSuggestionId + "' is " + nature + ": only SCRIPT_CONSTRAINT and SCRIPT_DERIVATION suggestions can be applied";
	}

	/** The ids of {@code pNatureById} (id to nature, in the caller's order) that go to {@code pRoute}, order kept. */
	public static List<String> idsOfRoute(final Map<String, String> pNatureById, final Route pRoute)
	{
		final List<String> out = new ArrayList<String>();
		for (final Map.Entry<String, String> entry : pNatureById.entrySet())
		{
			if (CapabilityNatureRoute.route(entry.getValue()) == pRoute)
			{
				out.add(entry.getKey());
			}
		}
		return out;
	}
}
