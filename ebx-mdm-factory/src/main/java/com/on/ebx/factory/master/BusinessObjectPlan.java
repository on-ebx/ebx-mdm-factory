package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * The Match and Merge business objects of a master model (`docs/MATCHING.md` sect;33, native relation fields since add-on 6.3.0.5-RC3).
 * <p>
 * A relation field of a decision tree (a child table's field compared through the child rows: {@code Adresse.city} on a {@code Personne}
 * plan) needs the master model to declare, for each parent table that has promoted child tables, ONE business object whose main table is the
 * parent and whose relationships are the parent's inverse associations to the children. Measured live 2026-10-12 on the scratch model W13BO:
 * the relationship MUST be carried by the main table (an inverse association {@code /root/Personne/adresses}, a string host field plus
 * {@code set_association tableRefInverse} over the child's foreign key); passing the child's foreign key itself is refused at publication
 * ("recursive business object models are not supported").
 * <p>
 * Everything here is pure (no EBX class, no MCP): what to declare ({@link #plan}), the names ({@link #businessObjectName},
 * {@link #pickHostFieldName}), what is still missing on a model that already holds part of it ({@link #reconcile} - the idempotence decision of the
 * master generator), and which business objects a table belongs to ({@link #candidateMainTables}, used by the matching configurator and
 * the activation guard). {@code ebx.MasterBusinessObjects} applies the decisions through the {@code data-model} MCP server.
 */
public final class BusinessObjectPlan
{
	/** Fields the Factory or the add-on put on a master table - never a host field name. */
	private static final Set<String> RESERVED_NAMES;
	static
	{
		final Set<String> reserved = new LinkedHashSet<String>();
		reserved.add("id");
		reserved.add("sourceSystem");
		reserved.add("sourceId");
		reserved.add("_loadId");
		reserved.add("_receivedAt");
		RESERVED_NAMES = Collections.unmodifiableSet(reserved);
	}

	private BusinessObjectPlan()
	{
	}

	/** One child table of a parent: the foreign key the inverse association is built over. */
	public static final class Relationship
	{
		public final String childEntity;
		public final String childTablePath;
		/** Bare name of the child's foreign key field to the parent, e.g. {@code personneRef}. */
		public final String fkFieldName;

		Relationship(final String pChildEntity, final String pFkFieldName)
		{
			this.childEntity = pChildEntity;
			this.childTablePath = MasterModel.tablePath(pChildEntity);
			this.fkFieldName = pFkFieldName;
		}

		/** Absolute path of the child's foreign key field, e.g. {@code /root/Adresse/personneRef}. */
		public String fkFieldPath()
		{
			return this.childTablePath + "/" + this.fkFieldName;
		}
	}

	/** One business object to declare: the parent table, its name, its child relationships (never empty). */
	public static final class Declaration
	{
		public final String parentEntity;
		public final String mainTablePath;
		public final String name;
		public final List<Relationship> relationships;
		/** One line per foreign key NOT declared (a second foreign key of the same child to the same parent): the relationship is by child table, one per child. */
		public final List<String> skippedForeignKeys;
		/** The fields the master model itself plans on the parent table (its attributes and the technical ones): a live field named like a host field and NOT in this set is a leftover of an earlier, interrupted generation. */
		public final Set<String> plannedFieldNames;

		Declaration(final String pParentEntity, final List<Relationship> pRelationships, final List<String> pSkipped, final Set<String> pPlannedFieldNames)
		{
			this.plannedFieldNames = Collections.unmodifiableSet(pPlannedFieldNames);
			this.parentEntity = pParentEntity;
			this.mainTablePath = MasterModel.tablePath(pParentEntity);
			this.name = BusinessObjectPlan.businessObjectName(pParentEntity);
			this.relationships = Collections.unmodifiableList(pRelationships);
			this.skippedForeignKeys = Collections.unmodifiableList(pSkipped);
		}
	}

	/** The business object name for a parent entity: {@code <Parent>BO} (the name measured on W13BO). */
	public static String businessObjectName(final String pParentEntity)
	{
		return pParentEntity + "BO";
	}

	/**
	 * One declaration per promoted parent entity that has at least one promoted child entity, in {@link MasterModel.Result#builtEntities} order
	 * (parents first). A self-reference is never a child (a recursive business object is refused at publication). A child holding two foreign keys
	 * to the same parent gets ONE relationship, over the first foreign key (the plan's {@code via} names a child entity, so a second one would be
	 * ambiguous); the others are listed in {@link Declaration#skippedForeignKeys}. A master with no child entity gives an empty list.
	 */
	public static List<Declaration> plan(final MasterModel.Result pMasterModel)
	{
		final List<Declaration> out = new ArrayList<Declaration>();
		for (final String parent : pMasterModel.builtEntities)
		{
			final Map<String, Relationship> byChild = new LinkedHashMap<String, Relationship>();
			final List<String> skipped = new ArrayList<String>();
			for (final MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
			{
				if (!parent.equals(fk.targetEntityName) || parent.equals(fk.entityName))
				{
					continue;
				}
				if (byChild.containsKey(fk.entityName))
				{
					skipped.add(MasterModel.tablePath(fk.entityName) + "/" + fk.fieldName + " (a relationship from " + fk.entityName + " to " + parent
						+ " already uses " + byChild.get(fk.entityName).fkFieldName + ")");
					continue;
				}
				byChild.put(fk.entityName, new Relationship(fk.entityName, fk.fieldName));
			}
			if (!byChild.isEmpty())
			{
				final Set<String> planned = new LinkedHashSet<String>(BusinessObjectPlan.RESERVED_NAMES);
				for (final MasterModel.FieldInfo field : pMasterModel.fields)
				{
					if (parent.equals(field.entityName))
					{
						planned.add(field.masterField);
					}
				}
				out.add(new Declaration(parent, new ArrayList<Relationship>(byChild.values()), skipped, planned));
			}
		}
		return out;
	}

	/**
	 * The main tables of the business objects {@code pEntityName} may belong to, in the order they are tried: its own table first when it has child
	 * entities (it is the main table of its own object), then the table of every parent it is a child of. Pure candidates: the add-on says which of
	 * them is really declared ({@code get_business_object_config}).
	 */
	public static List<String> candidateMainTables(final MasterModel.Result pMasterModel, final String pEntityName)
	{
		final Set<String> out = new LinkedHashSet<String>();
		if (!pMasterModel.childTablePaths(pEntityName).isEmpty())
		{
			out.add(MasterModel.tablePath(pEntityName));
		}
		for (final MasterModel.ForeignKeyInfo fk : pMasterModel.foreignKeys)
		{
			if (pEntityName.equals(fk.entityName) && fk.targetEntityName != null && !pEntityName.equals(fk.targetEntityName))
			{
				out.add(MasterModel.tablePath(fk.targetEntityName));
			}
		}
		return new ArrayList<String>(out);
	}

	/** The host field of the inverse association: the child entity name with a lower-case first letter and a plural {@code s}, e.g. {@code Adresse} -&gt; {@code adresses}. */
	static String hostFieldBase(final String pChildEntity)
	{
		final String name = pChildEntity == null || pChildEntity.isEmpty() ? "children" : Character.toLowerCase(pChildEntity.charAt(0)) + pChildEntity.substring(1);
		return name.endsWith("s") ? name : name + "s";
	}

	/** True when {@code pFieldName} is the default host field name of one of the children of {@code pDeclaration}. */
	private static boolean isDefaultHostName(final Declaration pDeclaration, final String pFieldName)
	{
		for (final Relationship relationship : pDeclaration.relationships)
		{
			if (pFieldName.equals(BusinessObjectPlan.hostFieldBase(relationship.childEntity)))
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A host field name for {@code pChildEntity} that clashes with nothing in {@code pTaken} (the parent table's fields, the host names already picked
	 * in this run): {@link #hostFieldBase}, else {@code base_assoc}, {@code base_assoc2}, ...
	 */
	public static String pickHostFieldName(final String pChildEntity, final Set<String> pTaken)
	{
		final String base = BusinessObjectPlan.hostFieldBase(pChildEntity);
		if (!pTaken.contains(base) && !BusinessObjectPlan.RESERVED_NAMES.contains(base))
		{
			return base;
		}
		String candidate = base + "_assoc";
		int i = 2;
		while (pTaken.contains(candidate))
		{
			candidate = base + "_assoc" + i;
			i++;
		}
		return candidate;
	}

	// ------------------------------------------------------------------------------------------ idempotence

	/** What the model already holds, as read through {@code get_data_model_business_objects} and {@code describe_table} of the parent. */
	public static final class ExistingState
	{
		public final boolean extensionEnabled;
		/** Names of every field/group/association under the parent table. */
		public final Set<String> parentFieldNames;
		/** Host field name -&gt; the foreign key path of the inverse association it exposes, for the parent table's existing associations. */
		public final Map<String, String> associationFkByHostField;
		/** Name of the existing business object whose main table is the parent, or null. */
		public final String businessObjectName;
		/** Relationship paths already attached to that business object. */
		public final Set<String> relationshipPaths;

		public ExistingState(final boolean pExtensionEnabled, final Set<String> pParentFieldNames, final Map<String, String> pAssociationFkByHostField,
			final String pBusinessObjectName, final Set<String> pRelationshipPaths)
		{
			this.extensionEnabled = pExtensionEnabled;
			this.parentFieldNames = pParentFieldNames == null ? Collections.<String>emptySet() : pParentFieldNames;
			this.associationFkByHostField = pAssociationFkByHostField == null ? Collections.<String, String>emptyMap() : pAssociationFkByHostField;
			this.businessObjectName = pBusinessObjectName;
			this.relationshipPaths = pRelationshipPaths == null ? Collections.<String>emptySet() : pRelationshipPaths;
		}

		/** Nothing declared yet: the state of a model generated before this lot. */
		public static ExistingState empty(final Set<String> pParentFieldNames)
		{
			return new ExistingState(false, pParentFieldNames, null, null, null);
		}
	}

	/** One relationship of a {@link Steps}: the final host field name and which of its three writes are still to do. */
	public static final class RelationshipStep
	{
		public final Relationship relationship;
		public final String hostFieldName;
		public final String relationshipPath;
		/** {@code create_field} of the host field (a string field). */
		public final boolean createHostField;
		/** {@code set_association} (tableRefInverse over the child's foreign key). */
		public final boolean setAssociation;
		/** {@code add_business_object_relationship}. */
		public final boolean addRelationship;

		RelationshipStep(final Relationship pRelationship, final String pHostFieldName, final String pRelationshipPath, final boolean pCreateHostField,
			final boolean pSetAssociation, final boolean pAddRelationship)
		{
			this.relationship = pRelationship;
			this.hostFieldName = pHostFieldName;
			this.relationshipPath = pRelationshipPath;
			this.createHostField = pCreateHostField;
			this.setAssociation = pSetAssociation;
			this.addRelationship = pAddRelationship;
		}

		public boolean nothingToDo()
		{
			return !this.createHostField && !this.setAssociation && !this.addRelationship;
		}
	}

	/** {@link #reconcile}'s answer for one {@link Declaration}. */
	public static final class Steps
	{
		public final Declaration declaration;
		/** {@code enable_extension businessObjects} is still to run (once per model: the caller runs it before the first declaration needing it). */
		public final boolean enableExtension;
		/** The name the object carries (the existing one when the parent table already is the main table of an object). */
		public final String businessObjectName;
		public final boolean createBusinessObject;
		public final List<RelationshipStep> relationships;

		Steps(final Declaration pDeclaration, final boolean pEnableExtension, final String pBusinessObjectName, final boolean pCreateBusinessObject,
			final List<RelationshipStep> pRelationships)
		{
			this.declaration = pDeclaration;
			this.enableExtension = pEnableExtension;
			this.businessObjectName = pBusinessObjectName;
			this.createBusinessObject = pCreateBusinessObject;
			this.relationships = Collections.unmodifiableList(pRelationships);
		}

		/** True when the model already holds everything this declaration needs. */
		public boolean nothingToDo()
		{
			if (this.enableExtension || this.createBusinessObject)
			{
				return false;
			}
			for (final RelationshipStep step : this.relationships)
			{
				if (!step.nothingToDo())
				{
					return false;
				}
			}
			return true;
		}
	}

	/**
	 * What is still missing for {@code pDeclaration} given {@code pExisting} - the master generator's idempotence rule. A second generation finds
	 * everything in place and writes nothing; a half-done one (the host field exists, the association was refused) resumes at the missing write.
	 * An existing association over the same foreign key is reused whatever its name (a host field named by hand); a name already taken by another
	 * field is never reused.
	 */
	public static Steps reconcile(final Declaration pDeclaration, final ExistingState pExisting)
	{
		final Set<String> taken = new LinkedHashSet<String>(pExisting.parentFieldNames);
		// a field the master model does not plan and that is no association is the host field of an interrupted run (create_field ran, set_association did not): free to reuse
		for (final String fieldName : pExisting.parentFieldNames)
		{
			if (!pDeclaration.plannedFieldNames.contains(fieldName) && !pExisting.associationFkByHostField.containsKey(fieldName)
				&& BusinessObjectPlan.isDefaultHostName(pDeclaration, fieldName))
			{
				taken.remove(fieldName);
			}
		}
		final Map<String, String> hostByFk = new LinkedHashMap<String, String>();
		for (final Map.Entry<String, String> association : pExisting.associationFkByHostField.entrySet())
		{
			if (!hostByFk.containsKey(association.getValue()))
			{
				hostByFk.put(association.getValue(), association.getKey());
			}
		}
		final String objectName = pExisting.businessObjectName == null ? pDeclaration.name : pExisting.businessObjectName;
		final List<RelationshipStep> steps = new ArrayList<RelationshipStep>();
		for (final Relationship relationship : pDeclaration.relationships)
		{
			final String existingHost = hostByFk.get(relationship.fkFieldPath());
			final String host;
			final boolean createField;
			final boolean setAssociation;
			if (existingHost != null)
			{
				host = existingHost;
				createField = false;
				setAssociation = false;
			}
			else
			{
				host = BusinessObjectPlan.pickHostFieldName(relationship.childEntity, taken);
				// a field of that name that is not our association (a leftover of a half-done run) is reused as the host: create_field is idempotent
				createField = !pExisting.parentFieldNames.contains(host);
				setAssociation = true;
			}
			taken.add(host);
			final String relationshipPath = pDeclaration.mainTablePath + "/" + host;
			steps.add(new RelationshipStep(relationship, host, relationshipPath, createField, setAssociation, !pExisting.relationshipPaths.contains(relationshipPath)));
		}
		return new Steps(pDeclaration, !pExisting.extensionEnabled, objectName, pExisting.businessObjectName == null, steps);
	}

	// ------------------------------------------------------------------------------------------ configurator decision

	/** What the matching configurator does for a table, given its plan and the business objects the add-on knows. */
	public enum Route
	{
		/** The table belongs to a declared business object: the business-object tools ({@code businessObject} argument, {@code activate_business_object_matching}). */
		BUSINESS_OBJECT,
		/** No business object and no relation field in the plan: the table tools, as before. */
		TABLE,
		/** The plan compares a child field and no business object is declared: refused (coded {@code missing.businessObject}), never a silent fallback. */
		REFUSE_MISSING_BUSINESS_OBJECT
	}

	/**
	 * @param pPlanHasRelationFields the accepted plan compares at least one child field ({@code via})
	 * @param pOwnBusinessObjectDeclared the table is itself the main table of a declared business object (the relation fields of its plan are read
	 *            through ITS children, so a business object of a parent it is only a member of does not serve them)
	 * @param pDeclaredBusinessObjectMainOrNull the main table of the first declared business object the table belongs to ({@link #candidateMainTables} probed
	 *            against the add-on), null when none
	 */
	public static Route route(final boolean pPlanHasRelationFields, final boolean pOwnBusinessObjectDeclared, final String pDeclaredBusinessObjectMainOrNull)
	{
		if (pPlanHasRelationFields && !pOwnBusinessObjectDeclared)
		{
			return Route.REFUSE_MISSING_BUSINESS_OBJECT;
		}
		return pDeclaredBusinessObjectMainOrNull != null ? Route.BUSINESS_OBJECT : Route.TABLE;
	}

	/**
	 * Which of {@code pCandidates} (in order) the add-on declares as a business object main table; {@code pDeclared} holds the ones it answered for.
	 * Null when none.
	 */
	public static String firstDeclared(final List<String> pCandidates, final Set<String> pDeclared)
	{
		for (final String candidate : pCandidates)
		{
			if (pDeclared.contains(candidate))
			{
				return candidate;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------ add-on answers

	/**
	 * Does a {@code get_business_object_config} answer say the business object is declared? Measured/documented 2026-10-12: {@code success} = yes;
	 * {@code CONFIG_NOT_FOUND} (declared, no member configured yet) = yes; {@code TABLE_NOT_FOUND} (a member table does not resolve - the model has
	 * no dataset over it) = declared as well, the model holds it; {@code BUSINESS_OBJECT_NOT_FOUND} and anything else (an {@code AMBIGUOUS_TABLE}
	 * that {@code MameCalls} could not settle) = not declared.
	 */
	public static boolean isDeclared(final boolean pSuccess, final String pCode)
	{
		return pSuccess || "CONFIG_NOT_FOUND".equals(pCode) || "TABLE_NOT_FOUND".equals(pCode);
	}

	/**
	 * Is {@code pTablePath} ready in a {@code get_business_object_activation_status} answer ({@code members[]} of {@code {table, state, published, paused,
	 * metadatasetExists}})? Ready = the member is listed, {@code ENABLED} and its metadataset exists - the same test as
	 * {@code MatchingActivationGuard.isReady}, per member. A member with no configuration is omitted by the server: not ready.
	 */
	public static boolean memberReady(final JsonNode pStatusAnswer, final String pTablePath)
	{
		final JsonNode members = MameAnswer.field(pStatusAnswer, "members");
		if (members == null || !members.isArray())
		{
			return false;
		}
		for (final JsonNode member : members)
		{
			if (pTablePath.equals(member.path("table").asText(null)))
			{
				return "ENABLED".equals(member.path("state").asText(null)) && member.path("metadatasetExists").asBoolean(false);
			}
		}
		return false;
	}

	/** One line per member of a {@code get_business_object_activation_status} answer ({@code /root/Adresse ENABLED (metadataset)}), for a refusal message or a step detail. */
	public static String describeMembers(final JsonNode pStatusAnswer)
	{
		final JsonNode members = MameAnswer.field(pStatusAnswer, "members");
		if (members == null || !members.isArray() || members.size() == 0)
		{
			return "no member listed";
		}
		final StringBuilder out = new StringBuilder();
		for (final JsonNode member : members)
		{
			out.append(out.length() == 0 ? "" : ", ").append(member.path("table").asText("?")).append(' ').append(member.path("state").asText("?"))
				.append(member.path("metadatasetExists").asBoolean(false) ? " (metadataset)" : " (no metadataset)");
		}
		return out.toString();
	}

	// ------------------------------------------------------------------------------------------ data-model answers

	/**
	 * Reads what the model holds for {@code pMainTablePath} from two answers of the {@code data-model} server, both measured on W13BO 2026-10-12:
	 * {@code get_data_model_business_objects} ({@code businessObjectsEnabled}, {@code businessObjects[]{name, mainTable, relationships[]{relationshipPath}}})
	 * and {@code describe_table} of the main table ({@code children[]{name, isAssociation, association{fieldToSource}}}). Either answer null/odd reads as
	 * "nothing there" - the generator then writes what is missing and the add-on's idempotence (create_field answers {@code alreadyExists}) covers the rest.
	 */
	public static ExistingState readExisting(final JsonNode pBusinessObjectsAnswer, final JsonNode pDescribeMainTableAnswer, final String pMainTablePath)
	{
		boolean enabled = false;
		String name = null;
		final Set<String> relationshipPaths = new LinkedHashSet<String>();
		if (pBusinessObjectsAnswer != null)
		{
			enabled = pBusinessObjectsAnswer.path("businessObjectsEnabled").asBoolean(false);
			for (final JsonNode businessObject : pBusinessObjectsAnswer.path("businessObjects"))
			{
				if (name == null && pMainTablePath.equals(businessObject.path("mainTable").asText(null)))
				{
					name = businessObject.path("name").asText(null);
					for (final JsonNode relationship : businessObject.path("relationships"))
					{
						final String path = relationship.path("relationshipPath").asText(null);
						if (path != null)
						{
							relationshipPaths.add(path);
						}
					}
				}
			}
		}
		final Set<String> fieldNames = new LinkedHashSet<String>();
		final Map<String, String> associationFkByHost = new LinkedHashMap<String, String>();
		if (pDescribeMainTableAnswer != null)
		{
			for (final JsonNode child : pDescribeMainTableAnswer.path("children"))
			{
				final String childName = child.path("name").asText(null);
				if (childName == null)
				{
					continue;
				}
				fieldNames.add(childName);
				final String fk = child.path("association").path("fieldToSource").asText(null);
				if (child.path("isAssociation").asBoolean(false) && fk != null && "tableRefInverse".equals(child.path("associationType").asText(null)))
				{
					associationFkByHost.put(childName, fk);
				}
			}
		}
		return new ExistingState(enabled, fieldNames, associationFkByHost, name, relationshipPaths);
	}

	/**
	 * Why a {@code data-model} write answer is a failure, or null when it succeeded: {@code refused: true}, {@code found: false} (an unknown business
	 * object or relationship) or a {@code code} other than success. The text is the server's own message when it gives one.
	 */
	public static String failureOf(final JsonNode pAnswer)
	{
		if (pAnswer == null || pAnswer.isMissingNode() || pAnswer.isNull())
		{
			return null;
		}
		final boolean refused = pAnswer.path("refused").asBoolean(false);
		final boolean notFound = pAnswer.has("found") && !pAnswer.path("found").asBoolean(true);
		if (!refused && !notFound)
		{
			return null;
		}
		final String code = pAnswer.path("code").asText(null);
		final String message = pAnswer.path("message").asText(null);
		return (code == null ? "" : code + ": ") + (message == null ? (refused ? "refused" : "not found") : message);
	}

	// ------------------------------------------------------------------------------------------ what a business-object table may be written with

	/** How the configurator (re)writes the policies of a table. */
	public enum WriteMode
	{
		/** The ordinary table: {@code apply_matching_config} (desired state, whole list) for a rewrite. */
		APPLY_CONFIG,
		/**
		 * A table that belongs to a business object: {@code generate_matching_policy} + {@code generate_merge_policy} with {@code businessObject} for a policy that does not exist
		 * yet, {@code patch_matching_config} for the link and for every in-place change (a second generate of the same {@code processCode} is refused, "already exists" - Vol1mR
		 * 2026-10-12, {@link BusinessObjectRewritePlan}). Measured on W13BO 2026-10-12: {@code apply_matching_config} on such a table is REFUSED
		 * ({@code INVALID_PARAMS "Table ... is part of a business object; use the business-object tools instead"}) with or without {@code businessObject}.
		 */
		GENERATE
	}

	/** Rewrite route of a table: {@link WriteMode#GENERATE} when it belongs to a business object, {@link WriteMode#APPLY_CONFIG} otherwise. */
	public static WriteMode writeMode(final boolean pInBusinessObject)
	{
		return pInBusinessObject ? WriteMode.GENERATE : WriteMode.APPLY_CONFIG;
	}

	/**
	 * Is {@code apply_matching_config} usable on this table? Never on a business-object table (see {@link WriteMode#GENERATE}): not for the rewrite, not for the related-table
	 * alignment, not for a preview rewrite.
	 */
	public static boolean mayUseApplyMatchingConfig(final boolean pInBusinessObject)
	{
		return BusinessObjectPlan.writeMode(pInBusinessObject) == WriteMode.APPLY_CONFIG;
	}

	/**
	 * Is the related-table alignment ({@code alignForeignKeys} of the merge policy's {@code relatedTables[]}) done for this table? Not on a business-object table: measured on
	 * W13BO 2026-10-12, {@code generate_merge_policy} with {@code relatedTables:[{path:/root/Adresse,...}]} is refused ("is not a related table of this table: this table has no related
	 * table") and the read-back {@code relatedTables} is {@code []}: the child relationship of the object replaces the alignment, the add-on cascades through the object.
	 */
	public static boolean alignsRelatedTables(final boolean pInBusinessObject)
	{
		return !pInBusinessObject;
	}

	// ------------------------------------------------------------------------------------------ member of an object (lot W13b)

	/**
	 * Is the table a MEMBER of its business object, i.e. a business-object table that is not the object's main table? Measured live 2026-10-12 (Vol1mR, step 1433):
	 * {@code generate_matching_policy} on such a table answers "An element may not be updated (no write permission on the following element:
	 * /executeMatching/matchingExecOnUpdate)" when the policy carries {@code matchingOnCreation}/{@code matchingOnUpdate}; the same policy without them is accepted
	 * (read back with both at 0), and the main table accepts them.
	 *
	 * @param pBusinessObjectMainOrNull the main table of the declared object the table belongs to ({@code MameBusinessObjects.Scope#businessObject}), null for no object
	 */
	public static boolean isMember(final String pBusinessObjectMainOrNull, final String pTablePath)
	{
		return pBusinessObjectMainOrNull != null && !pBusinessObjectMainOrNull.equals(pTablePath);
	}

	/**
	 * Removes {@code matchingOnCreation}/{@code matchingOnUpdate} from a matching policy about to be sent for a MEMBER table ({@link #isMember}); the member's matching is
	 * driven by its business object. Returns whether anything was removed. A table that is not a member keeps them.
	 */
	public static boolean dropExecutionModesOfMember(final ObjectNode pMatchingPolicy, final boolean pMember)
	{
		if (!pMember)
		{
			return false;
		}
		final boolean had = pMatchingPolicy.has("matchingOnCreation") || pMatchingPolicy.has("matchingOnUpdate");
		pMatchingPolicy.remove("matchingOnCreation");
		pMatchingPolicy.remove("matchingOnUpdate");
		return had;
	}

	/** The {@code executionMode} entry of a member table's step detail: nothing is read or patched, the object drives it. */
	public static ObjectNode executionModeSkippedOfMember()
	{
		final ObjectNode node = new ObjectMapper().createObjectNode();
		node.put("skipped", "business object member");
		return node;
	}

	/**
	 * The "trigger mode" sentence of the configure step message: for an ordinary table or a main table the mode and its execution value, for a member that it follows
	 * its business object (the Factory never writes the member's own execution modes).
	 */
	public static String triggerModeSentence(final String pModeName, final int pExecutionValue, final boolean pMember, final String pBusinessObjectMain)
	{
		return pMember ? "trigger mode " + pModeName + ": the matching of this table follows its business object " + pBusinessObjectMain
			: "trigger mode " + pModeName + ": execution mode " + pExecutionValue;
	}

	/**
	 * The child tables the comparison of the merge policy's {@code relatedTables} aligns: none for a business-object table (the object's child relationship replaces the
	 * alignment, {@link #alignsRelatedTables}; the add-on reads the generated list back as {@code MANUAL}, which would otherwise read as a difference at every
	 * configure - Vol1mR step 1435, a pure related-tables difference that sent the main table into a rewrite that cannot happen).
	 */
	public static Set<String> comparisonChildTablePaths(final boolean pInBusinessObject, final Set<String> pChildTablePaths)
	{
		return pInBusinessObject ? new LinkedHashSet<String>() : pChildTablePaths;
	}
}
