package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.quality.ChildKeysDerivation;

/**
 * The structural check of a {@code CHILD_KEYS} attribute the language model proposes on a parent entity of the staging model (lot M8b, 2026-10-04,
 * {@code docs/MATCHING.md} sec 50). Pure: no EBX class, unit-tested without an instance.
 * <p>
 * It answers the same questions {@code ebx.ChildKeysPass} asks at load time of a definition read off a {@code StagingAttribute} row - the derivation
 * argument parses ({@link ChildKeysDerivation#parse}), the child entity exists and is not the parent, every named field is an attribute of the child, the
 * child refers to the parent through exactly ONE attribute - but at PROPOSAL time, against the entities the proposal pass holds (the entities of the
 * run and the project's earlier ones), so that a definition which could never run is refused while the model can still correct it, instead of failing a
 * load days later. The wording of the refusals follows the load-time ones. Plus three refusals that only make sense for a proposal: a key built from a
 * technical identity attribute ({@code sourceSystem}/{@code sourceId}) or from a reference to another entity identifies a ROW of the source or the
 * parent itself, never the child's content, and a name already taken on the parent.
 * <p>
 * {@code ebx.ChildKeysPass#resolve} holds the load-time twin of this logic over {@link StagingModel.EntityInfo} too; both can delegate to one
 * implementation, left as it is in this lot because that class is being changed by another one.
 * <p>
 * Not decided here, on purpose: WHICH child attributes identify a child record and in which order. That is the model's call, from metadata; this class
 * only checks that what it names exists.
 */
public final class ChildKeysProposalRule
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ChildKeysProposalRule()
	{
	}

	/** An accepted proposal: what the writer stores in {@code StagingAttribute}. */
	public static final class Proposal
	{
		public final String parentEntity;
		public final String attributeName;
		public final String childEntity;
		/** The child attributes the key is built from, in key order. */
		public final List<String> fields;
		/** The child's attribute that refers to the parent. */
		public final String referenceAttribute;
		/** {@code StagingAttribute.derivationArgument}: {@code {"child":"...","fields":[...]}} - no {@code encoding} (the default, opaque tokens, applies). */
		public final String argument;

		Proposal(final String pParentEntity, final String pAttributeName, final String pChildEntity, final List<String> pFields, final String pReferenceAttribute,
			final String pArgument)
		{
			this.parentEntity = pParentEntity;
			this.attributeName = pAttributeName;
			this.childEntity = pChildEntity;
			this.fields = pFields;
			this.referenceAttribute = pReferenceAttribute;
			this.argument = pArgument;
		}
	}

	/** Either a {@link Proposal} or the reason it is refused, never both. */
	public static final class Check
	{
		public final Proposal proposal;
		public final String refusal;

		Check(final Proposal pProposal, final String pRefusal)
		{
			this.proposal = pProposal;
			this.refusal = pRefusal;
		}

		public boolean accepted()
		{
			return this.proposal != null;
		}
	}

	/** The {@code derivationArgument} of a proposal: child and ordered fields, nothing else. */
	public static String argumentJson(final String pChildEntity, final List<String> pFields)
	{
		final ObjectNode node = ChildKeysProposalRule.MAPPER.createObjectNode();
		node.put("child", pChildEntity);
		final ArrayNode fields = node.putArray("fields");
		if (pFields != null)
		{
			for (final String field : pFields)
			{
				fields.add(field);
			}
		}
		return node.toString();
	}

	/**
	 * @param pParentEntity the entity that will carry the attribute
	 * @param pAttributeName the name of the new attribute
	 * @param pChildEntity the entity whose records make the keys
	 * @param pFields the child attributes that make ONE key, most selective first
	 * @param pEntities every target entity the proposer knows, with its attributes (a child's link attribute carries {@link StagingModel.AttributeInfo#referenceEntity})
	 */
	public static Check check(final String pParentEntity, final String pAttributeName, final String pChildEntity, final List<String> pFields,
		final List<StagingModel.EntityInfo> pEntities)
	{
		if (pAttributeName == null || !StagingLlmPass.NAME_PATTERN.matcher(pAttributeName).matches())
		{
			return ChildKeysProposalRule.refused("'name' must start with a letter and contain only letters, digits and underscore (camelCase), e.g. addressKeys.");
		}
		StagingModel.EntityInfo parent = null;
		StagingModel.EntityInfo child = null;
		for (final StagingModel.EntityInfo entity : pEntities)
		{
			if (entity.name.equals(pParentEntity))
			{
				parent = entity;
			}
			if (entity.name.equals(pChildEntity))
			{
				child = entity;
			}
		}
		if (parent == null)
		{
			return ChildKeysProposalRule.refused("unknown entity '" + pParentEntity
				+ "': the attribute goes on a target entity already proposed in this run, or an existing one of the project.");
		}
		for (final StagingModel.AttributeInfo attribute : parent.attributes)
		{
			if (attribute.name.equalsIgnoreCase(pAttributeName))
			{
				return ChildKeysProposalRule.refused("'" + pParentEntity + "' already has an attribute named '" + attribute.name + "': choose another name.");
			}
		}
		if (pChildEntity == null || pChildEntity.equals(pParentEntity))
		{
			return ChildKeysProposalRule.refused("'childEntity' must name the entity whose records make the keys, and it is not the entity that carries the attribute.");
		}
		if (child == null)
		{
			return ChildKeysProposalRule.refused("the child entity '" + pChildEntity + "' does not exist in this project: propose it first (propose_target_entity, with "
				+ "parentEntity '" + pParentEntity + "'), then this attribute.");
		}
		final ChildKeysDerivation.Definition spec;
		try
		{
			spec = ChildKeysDerivation.parse(ChildKeysProposalRule.argumentJson(pChildEntity, pFields));
		}
		catch (final IllegalArgumentException ex)
		{
			return ChildKeysProposalRule.refused(ex.getMessage());
		}
		final List<String> referenceAttributes = new ArrayList<String>();
		for (final StagingModel.AttributeInfo attribute : child.attributes)
		{
			if (pParentEntity.equals(attribute.referenceEntity))
			{
				referenceAttributes.add(attribute.name);
			}
		}
		if (referenceAttributes.isEmpty())
		{
			return ChildKeysProposalRule.refused(pChildEntity + " has no attribute that refers to " + pParentEntity + ": the keys are read from the records of an entity "
				+ "that is a child of '" + pParentEntity + "' (propose_target_entity with parentEntity '" + pParentEntity + "' and its parentLinkAttribute).");
		}
		if (referenceAttributes.size() > 1)
		{
			return ChildKeysProposalRule.refused(pChildEntity + " has " + referenceAttributes.size() + " attributes that refer to " + pParentEntity + " "
				+ referenceAttributes + ": the keys of one of them cannot be proposed from here.");
		}
		final String referenceAttribute = referenceAttributes.get(0);
		for (final String field : spec.fields)
		{
			StagingModel.AttributeInfo found = null;
			for (final StagingModel.AttributeInfo attribute : child.attributes)
			{
				if (attribute.name.equals(field))
				{
					found = attribute;
				}
			}
			if (found == null)
			{
				final List<String> available = new ArrayList<String>();
				for (final StagingModel.AttributeInfo attribute : child.attributes)
				{
					if (!attribute.primaryKey && attribute.referenceEntity == null)
					{
						available.add(attribute.name);
					}
				}
				return ChildKeysProposalRule.refused("'" + field + "' is not an attribute of " + pChildEntity + (available.isEmpty() ? "" : " (its attributes: " + available + ")"));
			}
			if (found.primaryKey)
			{
				return ChildKeysProposalRule.refused("'" + field + "' is a technical identity attribute of " + pChildEntity + " (it identifies a row of the source, not the child's "
					+ "content): build the key from the attributes that describe the child record itself.");
			}
			if (found.referenceEntity != null || field.equals(referenceAttribute))
			{
				return ChildKeysProposalRule.refused("'" + field + "' is a reference to another entity: a key part is a value of the child record itself, not the link to the "
					+ "parent (that link is the same for every child of one parent and would tell nothing).");
			}
		}
		return new Check(new Proposal(pParentEntity, pAttributeName, pChildEntity, spec.fields, referenceAttribute,
			ChildKeysProposalRule.argumentJson(pChildEntity, spec.fields)), null);
	}

	private static Check refused(final String pMessage)
	{
		return new Check(null, pMessage);
	}
}
