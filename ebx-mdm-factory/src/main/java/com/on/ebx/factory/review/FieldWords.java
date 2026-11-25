package com.on.ebx.factory.review;

/**
 * Turns a technical field name into words a business reviewer reads without translating in their head -
 * tree-plain-words lot (2026-09-23, the product owner on the wizard's Matching step: "Les messages affichés sont
 * aussi trop techniques"): {@code firstName} → "First name", {@code vat_no} → "Vat no", {@code VATNumber} →
 * "VAT number", {@code accountId} → "Account id". Pure string work, no locale, no lookup: the factory has no
 * business label for a master field at this point (the LLM's plan names the master field itself), so the
 * identifier's own word boundaries are the best available signal. A blank name answers "?" - never blank.
 */
public final class FieldWords
{
	private FieldWords()
	{
	}

	/**
	 * Step 6 plan card (2026-10-18): the labels of the entity's attributes, in force while ONE plan card is written ({@link #withLabels}). A field the entity labels reads as its label
	 * ("Date d'achat"), in the language the label was written in, instead of the English words made of its identifier ("Purchase date"). Thread-bound and restored by the caller; no labels
	 * (every other screen, every test) is the plain humanising of before.
	 */
	private static final ThreadLocal<java.util.Map<String, String>> LABELS = new ThreadLocal<java.util.Map<String, String>>();

	/** Runs {@code pWork} with {@code pLabels} (master field name to label) used by {@link #humanize}; the previous labels are restored afterwards, whatever happens. */
	public static void withLabels(final java.util.Map<String, String> pLabels, final Runnable pWork)
	{
		final java.util.Map<String, String> previous = FieldWords.LABELS.get();
		FieldWords.LABELS.set(pLabels == null || pLabels.isEmpty() ? null : pLabels);
		try
		{
			pWork.run();
		}
		finally
		{
			if (previous == null)
			{
				FieldWords.LABELS.remove();
			}
			else
			{
				FieldWords.LABELS.set(previous);
			}
		}
	}

	public static String humanize(final String pFieldName)
	{
		if (pFieldName == null || pFieldName.trim().isEmpty())
		{
			return "?";
		}
		final java.util.Map<String, String> labels = FieldWords.LABELS.get();
		final String labelled = labels == null ? null : labels.get(pFieldName.trim());
		if (labelled != null && !labelled.trim().isEmpty())
		{
			return labelled.trim();
		}
		final String name = pFieldName.trim();
		final StringBuilder out = new StringBuilder(name.length() + 4);
		for (int i = 0; i < name.length(); i++)
		{
			final char c = name.charAt(i);
			if (c == '_' || c == '-' || c == '.' || c == '/')
			{
				if (out.length() > 0 && out.charAt(out.length() - 1) != ' ')
				{
					out.append(' ');
				}
				continue;
			}
			if (i > 0 && Character.isUpperCase(c))
			{
				final char previous = name.charAt(i - 1);
				final boolean nextIsLower = i + 1 < name.length() && Character.isLowerCase(name.charAt(i + 1));
				// "firstName" -> boundary before N; "VATNumber" -> boundary before N (the last upper of an acronym run)
				if (Character.isLowerCase(previous) || Character.isDigit(previous) || Character.isUpperCase(previous) && nextIsLower)
				{
					if (out.length() > 0 && out.charAt(out.length() - 1) != ' ')
					{
						out.append(' ');
					}
				}
			}
			out.append(c);
		}
		// lower-case every word that is not an acronym run, then capitalize the first letter
		final String[] words = out.toString().trim().split(" ");
		final StringBuilder result = new StringBuilder();
		for (final String word : words)
		{
			if (word.isEmpty())
			{
				continue;
			}
			if (result.length() > 0)
			{
				result.append(' ');
			}
			result.append(FieldWords.isAcronym(word) ? word : word.toLowerCase(java.util.Locale.ROOT));
		}
		if (result.length() == 0)
		{
			return "?";
		}
		result.setCharAt(0, Character.toUpperCase(result.charAt(0)));
		return result.toString();
	}

	/** Two or more characters, every letter upper-case ("VAT", "ID", "EU2") - kept as written. */
	private static boolean isAcronym(final String pWord)
	{
		if (pWord.length() < 2)
		{
			return false;
		}
		boolean anyLetter = false;
		for (int i = 0; i < pWord.length(); i++)
		{
			final char c = pWord.charAt(i);
			if (Character.isLetter(c))
			{
				anyLetter = true;
				if (!Character.isUpperCase(c))
				{
					return false;
				}
			}
		}
		return anyLetter;
	}

	/**
	 * Relation-fields lot (2026-09-26, `docs/MATCHING.md` dated entry): {@code "Adresse › City"} for a
	 * relation field ({@code pVia} non-blank - {@code master.DecisionTreePlan.FieldRef#via}/{@code
	 * ReviewDecisionTree.Field#via}'s own convention), plain {@link #humanize(String)} otherwise. The ONE place
	 * a relation field's child entity name is prefixed onto its humanized field name, so every review screen
	 * (tree diagram, plan table) shows it the same way.
	 */
	public static String humanize(final String pFieldName, final String pVia)
	{
		final String plain = FieldWords.humanize(pFieldName);
		return pVia == null || pVia.trim().isEmpty() ? plain : pVia.trim() + " › " + plain;
	}

	/** {@link #humanize} of every name, joined by {@code pSeparator} - a blank list answers "?". */
	public static String humanizeAll(final java.util.List<String> pFieldNames, final String pSeparator)
	{
		final StringBuilder out = new StringBuilder();
		for (final String name : pFieldNames)
		{
			if (out.length() > 0)
			{
				out.append(pSeparator);
			}
			out.append(FieldWords.humanize(name));
		}
		return out.length() == 0 ? "?" : out.toString();
	}
}
