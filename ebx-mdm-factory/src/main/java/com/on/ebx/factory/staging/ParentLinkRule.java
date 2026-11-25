package com.on.ebx.factory.staging;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The structural link of a child entity to its parent (Adresse.personne, written by the engine into {@code personneRef} for
 * every row of a child feed) has NO decision of its own: like the technical key ({@link TechnicalKeyRule}), it follows its
 * entity. the product owner, project demoBala, 2026-10-21: the matrix asked to accept {@code personne} on Adresse - "it makes no sense,
 * if I don't add it, it will not work". Rejecting it would leave every child row without its parent; accepting it is not a
 * decision about the business either.
 * <p>
 * A child feed stores the FIELD it fills ({@code TransferFeed.parentLinkField}, the {@code <attribute>Ref} name {@link
 * StagingModel#foreignKeyFieldName} derives), not the attribute name: the matrix compared the two and never recognised the row.
 * {@link #isLinkFieldOf} maps one onto the other with the same derivation ({@code Ref}, or {@code Ref_<n>} after a collision).
 */
public final class ParentLinkRule
{
	private ParentLinkRule()
	{
	}

	/** Whether {@code pLinkField} (a child feed's {@code parentLinkField}) is the foreign key field generated for the attribute {@code pAttributeName}. */
	public static boolean isLinkFieldOf(final String pAttributeName, final String pLinkField)
	{
		if (pAttributeName == null || pAttributeName.isEmpty() || pLinkField == null || pLinkField.isEmpty())
		{
			return false;
		}
		final String base = pAttributeName + "Ref";
		return pLinkField.equals(base) || Pattern.matches(Pattern.quote(base) + "_[0-9]+", pLinkField);
	}

	/**
	 * The names, among {@code pReferenceAttributeNames} (the entity's attributes that carry a {@code referenceEntity}), that a child
	 * feed of the same entity fills structurally - one of {@code pLinkFields}.
	 */
	public static Set<String> linkAttributeNames(final Collection<String> pReferenceAttributeNames, final Collection<String> pLinkFields)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final String name : pReferenceAttributeNames)
		{
			for (final String linkField : pLinkFields)
			{
				if (ParentLinkRule.isLinkFieldOf(name, linkField))
				{
					out.add(name);
				}
			}
		}
		return out;
	}
}
