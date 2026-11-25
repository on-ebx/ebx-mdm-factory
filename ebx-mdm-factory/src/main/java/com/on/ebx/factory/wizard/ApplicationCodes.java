package com.on.ebx.factory.wizard;

import java.util.Locale;
import java.util.Set;

/**
 * UX lot U2 (2026-10-13): the code proposed for a new application in step 2. Measured live the same day: picking the Salesforce
 * source left "Application code" empty, because the proposal came from a schema name and Salesforce has none; the person had to
 * invent one. The proposal now falls back on what the person recognises: the catalog source's label, the connection's label, the
 * connection id - a technical label (a URL such as {@code salesforce://login.salesforce.com}) is only used when nothing better exists.
 * Pure, tested in {@code ApplicationCodesTest}.
 */
public final class ApplicationCodes
{
	private ApplicationCodes()
	{
	}

	/**
	 * @param pSchemaName the first schema ticked for the application, may be blank (a source with no schema)
	 * @param pCatalogLabel the catalog source's label, may be blank
	 * @param pConnectionLabel the Connectors connection's label, may be blank
	 * @param pConnectionId the Connectors connection id, may be blank
	 * @param pExistingCodes the codes already used in this project (never mutated)
	 * @return a code that matches {@link WizardRules#CODE_PATTERN}, within {@link WizardRules#CODE_MAX}, and is not in {@code pExistingCodes}; never blank
	 */
	public static String propose(final String pSchemaName, final String pCatalogLabel, final String pConnectionLabel, final String pConnectionId, final Set<String> pExistingCodes)
	{
		String base = "";
		// a specific schema name still wins: one catalog source can host several applications (ERP_SOURCE, CRM_SOURCE) told apart by their schema
		final String schemaCode = WizardRules.suggestSourceSystemCode(pSchemaName);
		if (!schemaCode.isEmpty() && !"SRC".equals(schemaCode) && !ApplicationCodes.generic(schemaCode))
		{
			base = schemaCode;
		}
		if (base.isEmpty())
		{
			base = ApplicationCodes.firstReadable(pCatalogLabel, pConnectionLabel, pConnectionId);
		}
		if (base.isEmpty())
		{
			base = ApplicationCodes.slug(ApplicationCodes.technicalName(pCatalogLabel));
		}
		if (base.isEmpty())
		{
			base = "APP";
		}
		return ApplicationCodes.unique(base, pExistingCodes);
	}

	/** The first of the labels that reads as a name (not a URL), slugged; empty when none does. */
	private static String firstReadable(final String... pLabels)
	{
		for (final String label : pLabels)
		{
			if (label != null && !label.trim().isEmpty() && !label.contains("://"))
			{
				final String slug = ApplicationCodes.slug(label);
				if (!slug.isEmpty())
				{
					return slug;
				}
			}
		}
		return "";
	}

	/** {@code salesforce://login.salesforce.com} gives {@code salesforce}: what comes before {@code ://}, the only part of a URL label that names the system. */
	static String technicalName(final String pLabel)
	{
		if (pLabel == null)
		{
			return "";
		}
		final int scheme = pLabel.indexOf("://");
		return scheme > 0 ? pLabel.substring(0, scheme) : pLabel;
	}

	/** Upper case, every run of characters outside {@code A-Z 0-9} becomes one {@code _}, trimmed of {@code _}, starts with a letter ({@code S_} prefix otherwise), cut at {@link WizardRules#CODE_MAX}; empty for a label with no letter or digit. */
	public static String slug(final String pLabel)
	{
		if (pLabel == null)
		{
			return "";
		}
		String upper = pLabel.trim().toUpperCase(Locale.ENGLISH).replaceAll("[^A-Z0-9]+", "_").replaceAll("^_+|_+$", "");
		if (upper.isEmpty())
		{
			return "";
		}
		if (!Character.isLetter(upper.charAt(0)))
		{
			upper = "S_" + upper;
		}
		return upper.length() > WizardRules.CODE_MAX ? upper.substring(0, WizardRules.CODE_MAX).replaceAll("_+$", "") : upper;
	}

	private static boolean generic(final String pCode)
	{
		for (final String name : new String[] { "PUBLIC", "DBO", "DEFAULT", "MAIN", "SYS" })
		{
			if (name.equalsIgnoreCase(pCode))
			{
				return true;
			}
		}
		return false;
	}

	/** {@code pBase}, else {@code pBase_2}, {@code pBase_3}... - within {@link WizardRules#CODE_MAX}. */
	static String unique(final String pBase, final Set<String> pExisting)
	{
		String base = pBase.length() > WizardRules.CODE_MAX ? pBase.substring(0, WizardRules.CODE_MAX) : pBase;
		if (!pExisting.contains(base))
		{
			return base;
		}
		for (int n = 2; n < 1000; n++)
		{
			final String suffix = "_" + n;
			final int keep = Math.max(1, WizardRules.CODE_MAX - suffix.length());
			final String candidate = (base.length() > keep ? base.substring(0, keep) : base) + suffix;
			if (!pExisting.contains(candidate))
			{
				return candidate;
			}
		}
		return base + "_999";
	}
}
