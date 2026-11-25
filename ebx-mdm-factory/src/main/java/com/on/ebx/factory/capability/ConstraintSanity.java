package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.capability.ConstraintBodyBuilder.BindingEntry;
import com.on.ebx.factory.quality.ScanGuard;

/**
 * Lot G2 (2026-10-17, docs/UX-STAGING-TEST-FIXES-G2.md): deterministic, generic guards on a {@code suggest_script_constraint} call, applied where the
 * capability pass records its suggestions. The model proposes; these catch what no reading of the data would endorse. Measured on the live test
 * (project TiersB2CRetestFinal): a nine-digit regex on an {@code addressType} whose only sources are {@code constant(HOME)} and {@code constant(BUYER)};
 * a timestamp regex on a country; a function that builds a name key ({@code sortedKeyOf}) positioned as a check. Each refusal is a message back to the
 * model with the reason (a refused call is never stored - the pass's own refusal discipline), so the model can correct or drop it. Nothing here reads
 * a name or a business term: a function's return type, the attribute's constant-ness, and the pass rate of a literal regular expression measured on
 * the attribute's own values. Pure: no EBX type.
 */
public final class ConstraintSanity
{
	private static final String REGEX_LIBRARY_SUFFIX = "validator/regex";

	private ConstraintSanity()
	{
	}

	/**
	 * Why a function cannot be positioned as a constraint from its return type alone, or null. A constraint needs a verdict: a {@code boolean}, or a
	 * {@code json_object} report the body reads ({@code validate(...): json_object} is a real entry point of the libraries). A function returning a plain
	 * value (a string, a number, a date, a collection) COMPUTES something - the model is told to propose it as a derivation instead. An unknown return type
	 * (not parsed) is never refused.
	 */
	public static String returnTypeRefusal(final String pFunctionName, final String pReturnType)
	{
		if (pReturnType == null || pReturnType.trim().isEmpty())
		{
			return null;
		}
		final String type = pReturnType.trim().toLowerCase(Locale.ROOT);
		if ("boolean".equals(type) || "json_object".equals(type))
		{
			return null;
		}
		return "function '" + pFunctionName + "' returns '" + pReturnType.trim() + "', a computed value and not a verdict (boolean) or a report (json_object): it cannot flag a record, "
			+ "so it cannot be a constraint. If its value would be useful as a NEW attribute, propose it with suggest_script_derivation; otherwise do not propose it.";
	}

	/**
	 * Staging integration (2026-10-17, automated B2C run E2EB2C20261017181759, CapabilitySuggestion 265): why a LITERAL cannot feed this parameter, or
	 * null. A literal is written into the script as ONE scalar ({@link ConstraintBodyBuilder#renderLiteral}): a collection parameter ({@code list<...>},
	 * {@code set<...>}, a dictionary) never accepts it - the model bound {@code matchesAny(value, patterns: list<string>)} to one pattern string and the
	 * constraint body failed to compile at apply time ("Value of type 'string' is not assignable to parameter of type 'list<string>'"). A {@code decimal}
	 * parameter needs a number, a {@code boolean} one true/false. An unknown parameter type is never refused. Generic: types only, no name.
	 */
	public static String literalTypeRefusal(final String pFunctionName, final String pParameterName, final String pParameterType, final String pValue)
	{
		if (pParameterType == null || pParameterType.trim().isEmpty())
		{
			return null;
		}
		final String type = pParameterType.trim().toLowerCase(Locale.ROOT);
		final String value = pValue == null ? "" : pValue.trim();
		if (type.startsWith("list") || type.startsWith("set") || type.startsWith("dictionary") || type.startsWith("map") || type.endsWith("[]"))
		{
			return "parameter '" + pParameterName + "' of '" + pFunctionName + "' is a " + pParameterType.trim() + ": a literal is ONE value and cannot fill it. Bind it to an "
				+ "attribute holding that collection, or choose a function of the same library that takes a single value (for one pattern: matches rather than matchesAny), "
				+ "or do not propose it.";
		}
		if ("decimal".equals(type) || "int".equals(type) || "integer".equals(type))
		{
			try
			{
				Double.parseDouble(value);
			}
			catch (final NumberFormatException ex)
			{
				return "parameter '" + pParameterName + "' of '" + pFunctionName + "' is a " + pParameterType.trim() + " but the literal '" + value
					+ "' is not a number: give a number, or bind an attribute.";
			}
		}
		if ("boolean".equals(type) && !"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value))
		{
			return "parameter '" + pParameterName + "' of '" + pFunctionName + "' is a boolean but the literal '" + value + "' is neither true nor false.";
		}
		return null;
	}

	/**
	 * Why the check described by these arguments is pointless on the values of the attribute(s) it reads, or null.
	 *
	 * @param pFunction the library function
	 * @param pBinding the resolved binding (parameter to attribute / literal / to-be-provided)
	 * @param pEntity the entity (its attributes carry the {@link ValueEvidence}, when it could be read)
	 * @param pTargetAttribute the attribute the call focuses on, or null for an entity-level check
	 */
	public static String evidenceRefusal(final CapabilityLlmPass.FunctionRef pFunction, final Map<String, BindingEntry> pBinding,
		final CapabilityLlmPass.EntityInfo pEntity, final String pTargetAttribute)
	{
		final Set<String> read = new LinkedHashSet<String>(ConstraintBodyBuilder.attributeBoundNames(pBinding));
		if (pTargetAttribute != null && !pTargetAttribute.trim().isEmpty())
		{
			read.add(pTargetAttribute.trim());
		}
		for (final String name : read)
		{
			final CapabilityLlmPass.AttributeInfo attribute = pEntity.attribute(name);
			final ValueEvidence evidence = attribute == null ? null : attribute.evidence;
			if (evidence != null && evidence.constantOnly)
			{
				return "attribute '" + name + "' is fed only by constants (" + ConstraintSanity.listed(evidence) + ") that the proposal itself set: a check on a closed set of "
					+ "fixed texts can only flag the proposal's own values. Do not propose a constraint on it.";
			}
		}
		return ConstraintSanity.regexRefusal(pFunction, pBinding, pEntity);
	}

	private static String regexRefusal(final CapabilityLlmPass.FunctionRef pFunction, final Map<String, BindingEntry> pBinding, final CapabilityLlmPass.EntityInfo pEntity)
	{
		if (pFunction.libraryIdePath == null || !pFunction.libraryIdePath.endsWith(ConstraintSanity.REGEX_LIBRARY_SUFFIX))
		{
			return null;
		}
		final boolean ignoreCase = "matchesIgnoreCase".equals(pFunction.functionName);
		if (!ignoreCase && !"matches".equals(pFunction.functionName))
		{
			return null;
		}
		String attributeName = null;
		String pattern = null;
		for (final BindingEntry entry : pBinding.values())
		{
			if (entry.kind == BindingEntry.Kind.ATTRIBUTE && attributeName == null)
			{
				attributeName = entry.value;
			}
			else if (entry.kind == BindingEntry.Kind.LITERAL && "pattern".equals(entry.parameter))
			{
				pattern = entry.value;
			}
		}
		final CapabilityLlmPass.AttributeInfo attribute = attributeName == null ? null : pEntity.attribute(attributeName);
		if (attribute == null || attribute.evidence == null || pattern == null)
		{
			return null;
		}
		final Integer rate = attribute.evidence.passRatePercent(pattern, ignoreCase);
		if (rate == null || rate.intValue() > 0)
		{
			return null;
		}
		return "the pattern '" + pattern + "' matches none of the " + attribute.evidence.judgeable() + " distinct value(s) measured on '" + attributeName
			+ "' (" + (attribute.evidence.sampleRows > 0L ? ScanGuard.sampleWords(attribute.evidence.sampleRows) + "; " : "") + "0 % of its rows): applied, it would flag every record."
			+ " Read the profile again and propose a pattern the values can satisfy, or nothing.";
	}

	/** {@code 'HOME', 'BUYER'} - the constants, at most five. */
	private static String listed(final ValueEvidence pEvidence)
	{
		final List<String> parts = new ArrayList<String>();
		for (final ValueEvidence.Weighted weighted : pEvidence.values)
		{
			if (parts.size() >= 5)
			{
				parts.add("...");
				break;
			}
			parts.add("'" + weighted.value + "'");
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < parts.size(); i++)
		{
			out.append(i == 0 ? "" : ", ").append(parts.get(i));
		}
		return out.toString();
	}
}
