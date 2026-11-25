package com.on.ebx.factory.workflow;

import com.orchestranetworks.service.OperationException;

import com.on.ebx.factory.core.ScopeParam;
import com.on.ebx.factory.ebx.EngineRefusal;

/**
 * 2026-10-12: the {@code scope} property of a pipeline step is read strictly, like the REST routes it mirrors ({@link ScopeParam}): a blank value is "not
 * given" ({@code null}, the step applies its default), an accepted word is returned as written in the list, and anything else - {@code full} on a matching run
 * used to be read as the default - stops the step with a coded refusal naming the accepted words.
 */
final class BeanScope
{
	private BeanScope()
	{
	}

	static String parse(final String pStep, final String pValue, final String... pAccepted) throws OperationException
	{
		try
		{
			return ScopeParam.parse(pValue, pAccepted);
		}
		catch (final IllegalArgumentException ex)
		{
			throw EngineRefusal.coded("invalid.scope", pStep + ": " + ex.getMessage(), pStep, pValue.trim(), ScopeParam.quoted(pAccepted));
		}
	}
}
