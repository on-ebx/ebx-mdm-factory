package com.on.ebx.factory.master;

import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.staging.StagingModel;

/**
 * The name a promoted BUSINESS attribute gets on its master table - identical to the attribute's own name
 * except when that name collides with a field the master table carries on its own (`docs/MASTER-ZONE.md`
 * &sect;13). Seen live on 2026-09-23 (project {@code ScratchMatch}, staging entity {@code SalesforceSample}
 * built from a Salesforce {@code sample.json}, accepted attribute {@code Id} = the Salesforce record id):
 * {@link MasterModel} adds its own auto-increment primary key {@code id} to every master table, EBX resolves
 * field names case-insensitively for this purpose, and {@code build_data_model}'s dry run refused the model
 * with {@code SubTypeAlreadyExistsException: Type [ComplexType[name=SalesforceSample] 0. id 1. sourceSystem 2.
 * sourceId] has already a subType defined at AspectKey[id]} - {@code master/generate} answered 409 for a
 * project whose staging zone was perfectly fine (staging has no {@code id} field of its own).
 * <p>
 * <b>The rule</b>: a business attribute whose name equals, case-insensitively, one of {@link #RESERVED} -
 * the five fields {@link MasterModel} itself puts on every master table ({@code id}, the two technical-key
 * pass-throughs {@code sourceSystem}/{@code sourceId}, and the two bookkeeping fields {@code _loadId}/
 * {@code _receivedAt}) - is promoted under {@code <name>_src} ({@code Id} -&gt; {@code Id_src}: the SOURCE
 * system's own value of that name, which is exactly what a source record id is). Deterministic and pure: the
 * same attribute list always yields the same names, so every reader of the master table that starts from a
 * {@code StagingAttribute} name (the transfer plan and loader through {@link MasterModel.FieldInfo}, the
 * record-lineage reader, the catalog lineage declaration, the LLM matching suggester) resolves the field the
 * same way by calling this, never by re-deriving it. The improbable second-order clash (an entity that ALSO
 * carries an attribute literally named {@code Id_src}) is resolved by appending the suffix again until the
 * candidate clashes with neither a reserved name nor any sibling attribute of the entity, case-insensitively -
 * still deterministic, never an exception.
 * <p>
 * Only a business attribute goes through this rule: a primary-key attribute ({@code sourceSystem}/{@code
 * sourceId}, {@code TechnicalKeyRule}) IS the master field of that name and is never renamed - {@link
 * MasterModel} never calls this for one, and {@link #masterFieldNames} skips them.
 */
public final class MasterFieldNameRule
{
	/** What a colliding attribute name gets appended: {@code Id} becomes {@code Id_src}. */
	public static final String SUFFIX = "_src";
	/** The fields every master table carries regardless of its business attributes (see {@code MasterModel.buildTable}), compared case-insensitively. */
	public static final List<String> RESERVED = Collections.unmodifiableList(Arrays.asList("id", "sourceSystem", "sourceId", "_loadId", "_receivedAt"));

	private MasterFieldNameRule()
	{
	}

	/** True when {@code pName} equals one of {@link #RESERVED} regardless of case - i.e. would collide with a field the master table carries on its own. */
	public static boolean isReserved(final String pName)
	{
		if (pName == null)
		{
			return false;
		}
		final String lower = pName.toLowerCase(Locale.ROOT);
		for (final String reserved : MasterFieldNameRule.RESERVED)
		{
			if (reserved.toLowerCase(Locale.ROOT).equals(lower))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * The master field name of the BUSINESS attribute {@code pAttributeName}, given every attribute name of its
	 * entity ({@code pEntityAttributeNames}, primary-key attributes included - only used to steer clear of a
	 * sibling when a suffix is needed; may contain {@code pAttributeName} itself). Unchanged unless
	 * {@link #isReserved}; null in, null out.
	 */
	public static String masterFieldName(final String pAttributeName, final Collection<String> pEntityAttributeNames)
	{
		if (pAttributeName == null || !MasterFieldNameRule.isReserved(pAttributeName))
		{
			return pAttributeName;
		}
		final Set<String> taken = new LinkedHashSet<String>();
		for (final String reserved : MasterFieldNameRule.RESERVED)
		{
			taken.add(reserved.toLowerCase(Locale.ROOT));
		}
		if (pEntityAttributeNames != null)
		{
			for (final String sibling : pEntityAttributeNames)
			{
				if (sibling != null)
				{
					taken.add(sibling.toLowerCase(Locale.ROOT));
				}
			}
		}
		String candidate = pAttributeName + MasterFieldNameRule.SUFFIX;
		while (taken.contains(candidate.toLowerCase(Locale.ROOT)))
		{
			candidate = candidate + MasterFieldNameRule.SUFFIX;
		}
		return candidate;
	}

	/** True when {@link #masterFieldName} would answer something other than the attribute's own name. */
	public static boolean isRenamed(final String pAttributeName, final Collection<String> pEntityAttributeNames)
	{
		final String masterName = MasterFieldNameRule.masterFieldName(pAttributeName, pEntityAttributeNames);
		return masterName != null && !masterName.equals(pAttributeName);
	}

	/**
	 * Attribute name -&gt; master field name for every attribute of {@code pAttributes} (whatever its status - a
	 * caller filters on acceptance itself, exactly as it already does): a primary-key attribute maps to its own
	 * name, a business attribute through {@link #masterFieldName}. The convenience every EBX-bound reader that
	 * holds a {@code StagingModel.EntityInfo} uses, so none of them repeats the "collect the sibling names" step.
	 */
	public static Map<String, String> masterFieldNames(final List<StagingModel.AttributeInfo> pAttributes)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pAttributes == null)
		{
			return out;
		}
		final List<String> names = new java.util.ArrayList<String>();
		for (final StagingModel.AttributeInfo attribute : pAttributes)
		{
			names.add(attribute.name);
		}
		for (final StagingModel.AttributeInfo attribute : pAttributes)
		{
			out.put(attribute.name, attribute.primaryKey ? attribute.name : MasterFieldNameRule.masterFieldName(attribute.name, names));
		}
		return out;
	}
}
