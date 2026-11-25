package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.wizard.WizardSnapshot;

/**
 * The Validation desk (docs/STAGING-DESK.md), as plain data: the decisions of step 4 regrouped by WHAT THE PERSON ACTUALLY DECIDES instead of by row type.
 * Built from the very list the Mapping matrix counts ({@link MatrixCompute#stillToDecide}) and, for what the matrix model does not hold (a service that cannot run,
 * the suggestions set aside, the fields that follow their object), from the wizard's own snapshot - never from a second reading of the rows. Pure (no EBX type),
 * unit-tested in {@code DeskGroupsTest}; {@link DeskGroups} does the regrouping, {@link DeskHtml} draws it.
 * <p>
 * Four kinds of group, in this order in the left column: one {@link #OBJECT} group per business object ("Accept what you see"), one {@link #CHECKS} group per business
 * object, one {@link #CANNOT_RUN} group for the whole project; plus two lists that are not decisions - {@link #follows} (nothing to decide) and {@link #optional}
 * (look-up suggestions that could run: folded, never counted).
 */
public final class DeskModel
{
	public static final String OBJECT = "OBJECT";
	public static final String CHECKS = "CHECKS";
	public static final String CANNOT_RUN = "CANNOT_RUN";

	/** {@link Follow#kind}: the entity's technical key ({@code sourceSystem}, {@code sourceId}). */
	public static final String FOLLOW_KEY = "KEY";
	/** {@link Follow#kind}: the structural link of a child entity to its parent ({@code Adresse.personne}). */
	public static final String FOLLOW_PARENT = "PARENT";

	/** One still-to-decide item of the matrix' list, with the facts the desk needs to print it (the list item alone carries no rationale, no type, no landing column). */
	public static final class Line
	{
		public final MatrixCompute.StillItem item;
		/** The proposer's own words ({@code Attr.rationale}, {@code Cell.rationale} or {@code Guard.rationale}), "" when none. */
		public String rationale = "";
		/** A field: its type and whether the target requires it; "" for the other kinds. */
		public String dataType = "";
		public boolean mandatory;
		/** A computed field or matching key: how it is derived and from which field. */
		public String derivation = "";
		public String derivedFrom = "";
		/** A field link: the landing column read and the cleaning steps applied to it. */
		public String column = "";
		public List<String> steps = Collections.emptyList();
		/** A source link or field link: the source system. */
		public String sourceName = "";
		/** A rule or check: what it tests ({@code PATTERN}, {@code phone.isValid}), its argument and its severity. */
		/** The flag is {@code SAME_CONSTANT}: lot L3's callout shows it, the older "points to check" list does not repeat it. */
		public boolean sameConstant;
		public String guardKind = "";
		public String guardArgument = "";
		public String severity = "";

		Line(final MatrixCompute.StillItem pItem)
		{
			this.item = pItem;
		}

		public boolean flagged()
		{
			return MatrixCompute.REASON_ATTENTION.equals(this.item.reason);
		}
	}

	/** A connector look-up suggestion that cannot run as it stands ({@code wizard.StillToDecide.CheckItem#cannotRun}). */
	public static final class ServiceLine
	{
		public String id = "";
		public String entityName = "";
		/** {@code Entity.field}, or the entity alone. */
		public String element = "";
		public String proposition = "";
		public String connector = "";
		public String rationale = "";
		public Integer confidence;
		/** {@code CapabilitySuggestion.ready} is false: no secret or no configuration. */
		public boolean noSecret;
		public String readinessDetail = "";
		/** The binding still carries {@code toBeProvided}: an input no field holds. */
		public boolean needsInput;
		/** {@code EXTERNAL_SERVICE} or the nature of the script check that cannot run ({@code SCRIPT_CONSTRAINT}): what Reject posts. */
		public String nature = ReviewLine.EXTERNAL_SERVICE;

		/** Only a connector service can be set aside ({@code WizardData#setCapabilitiesAside} skips every other nature): a script check can only be rejected. */
		public boolean canSetAside()
		{
			return ReviewLine.EXTERNAL_SERVICE.equals(this.nature);
		}
	}

	/** An attribute that follows its business object: nothing to decide. */
	public static final class Follow
	{
		public final String entityName;
		public final String name;
		public final String kind;

		public Follow(final String pEntityName, final String pName, final String pKind)
		{
			this.entityName = pEntityName;
			this.name = pName;
			this.kind = pKind;
		}
	}

	/** A folded line: look-up suggestions that could run, for one field - optional, never counted. */
	public static final class OptionalLine
	{
		public final String entityName;
		public final String fieldName;
		public final int count;

		public OptionalLine(final String pEntityName, final String pFieldName, final int pCount)
		{
			this.entityName = pEntityName;
			this.fieldName = pFieldName;
			this.count = pCount;
		}
	}

	/** One group of the left column: what a person decides in one go. */
	public static final class Group
	{
		public final String kind;
		/** {@code obj|<entityId>}, {@code chk|<entityId>} or {@code run|}: what the page posts to select it. */
		public final String key;
		public final String entityId;
		public final String entityName;
		public final List<Line> lines = new ArrayList<Line>();
		public final List<ServiceLine> services = new ArrayList<ServiceLine>();
		/** {@link #OBJECT}: the business object itself still to decide / fields / links, and the distinct sources feeding the links. */
		public int objects;
		public int fields;
		public int links;
		public int sources;
		/** Items that "need your eye" (an AttentionRules flag) and the distinct fields they belong to. */
		public int flaggedItems;
		public int flaggedFields;
		/** Items the Accept button of the business object takes ({@code MatrixCompute.REASON_BULK}). */
		public int withButton;

		Group(final String pKind, final String pKey, final String pEntityId, final String pEntityName)
		{
			this.kind = pKind;
			this.key = pKey;
			this.entityId = pEntityId;
			this.entityName = pEntityName;
		}

		/** What the group counts as proposals. */
		public int proposals()
		{
			return CANNOT_RUN.equals(this.kind) ? this.services.size() : this.lines.size();
		}

		/** Services that lack a secret / lack an input (a service can be both). */
		public int servicesWithoutSecret()
		{
			int count = 0;
			for (final ServiceLine service : this.services)
			{
				if (service.noSecret)
				{
					count++;
				}
			}
			return count;
		}

		public int servicesNeedingInput()
		{
			int count = 0;
			for (final ServiceLine service : this.services)
			{
				if (service.needsInput)
				{
					count++;
				}
			}
			return count;
		}

		/** How many of the group's lines "Set the N aside" moves (the connector services). */
		public int servicesToSetAside()
		{
			int count = 0;
			for (final ServiceLine service : this.services)
			{
				count += service.canSetAside() ? 1 : 0;
			}
			return count;
		}

		/** The suggestion ids "Set the N aside" posts, comma-joined. */
		public String serviceIds()
		{
			final StringBuilder out = new StringBuilder();
			for (final ServiceLine service : this.services)
			{
				if (!service.canSetAside())
				{
					continue;
				}
				if (out.length() > 0)
				{
					out.append(',');
				}
				out.append(service.id);
			}
			return out.toString();
		}
	}

	/** A business object with what was accepted in it. */
	public static final class AcceptedObject
	{
		public final String name;
		public final int fields;
		public final int links;

		public AcceptedObject(final String pName, final int pFields, final int pLinks)
		{
			this.name = pName;
			this.fields = pFields;
			this.links = pLinks;
		}
	}

	/** The empty state: what was accepted, set aside and followed. The lists are cut at a few lines; {@code more*} counts what the cut left out. */
	public static final class Done
	{
		public final List<AcceptedObject> acceptedObjects = new ArrayList<AcceptedObject>();
		public final List<String> acceptedChecks = new ArrayList<String>();
		public int moreChecks;
		public final List<String> setAside = new ArrayList<String>();
		public int moreSetAside;
		public final List<String> followed = new ArrayList<String>();
		public final List<String> objectNames = new ArrayList<String>();
		public int sources;
	}

	public final String projectLabel;
	public final List<Group> groups = new ArrayList<Group>();
	public final List<Follow> follows = new ArrayList<Follow>();
	public final List<OptionalLine> optional = new ArrayList<OptionalLine>();
	public final Done done = new Done();
	/** Rows of the project that are decidable (a business object, a non-key field, a source link, a field link, a rule/check/service) and how many are decided. */
	public int totalProposals;
	public int decidedProposals;

	DeskModel(final String pProjectLabel)
	{
		this.projectLabel = pProjectLabel == null ? "" : pProjectLabel;
	}

	/** The headline number: the groups that need a person, one each. */
	public int decisions()
	{
		return this.groups.size();
	}

	/** Proposals not decided yet (optional suggestions included). */
	public int openProposals()
	{
		return this.totalProposals - this.decidedProposals;
	}

	public boolean isDone()
	{
		return this.groups.isEmpty();
	}

	public Group group(final String pKey)
	{
		if (pKey == null)
		{
			return null;
		}
		for (final Group group : this.groups)
		{
			if (group.key.equals(pKey))
			{
				return group;
			}
		}
		return null;
	}

	/** The key of the group of a business object ({@code StagingEntity} row id), the object group when it has one. */
	public static String objectKey(final String pEntityId)
	{
		return "obj|" + pEntityId;
	}

	public static String checksKey(final String pEntityId)
	{
		return "chk|" + pEntityId;
	}

	public static final String CANNOT_RUN_KEY = "run|";

	/** The keys of the groups, in order - kept by the service to find the next group after a decision. */
	public List<String> keys()
	{
		final List<String> out = new ArrayList<String>();
		for (final Group group : this.groups)
		{
			out.add(group.key);
		}
		return out;
	}

	/** Builds the desk of a project: see {@link DeskGroups#build}. {@code pSnapshot} may be null (the matrix model alone answers, without the cannot-run group and the set-aside list). */
	public static DeskModel of(final MatrixModel.Project pProject, final WizardSnapshot pSnapshot, final int pBulkThreshold)
	{
		return DeskGroups.build(pProject, pSnapshot, pBulkThreshold);
	}
}
