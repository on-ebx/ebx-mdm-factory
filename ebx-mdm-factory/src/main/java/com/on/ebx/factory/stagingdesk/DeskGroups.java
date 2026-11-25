package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.wizard.StillToDecide;
import com.on.ebx.factory.wizard.WizardSnapshot;

/**
 * Regroups what is still to decide (docs/STAGING-DESK.md sec 2). Nothing here decides or re-implements a rule: the items are the Mapping matrix' own list
 * ({@link MatrixCompute#stillToDecide}, so the desk and the matrix count the same things), the services that cannot run are the wizard card's own
 * ({@link StillToDecide.CheckItem#cannotRun}, so "Set the N aside" sets aside exactly what the card's button does), and the fields that follow their object are
 * the wizard snapshot's ({@link WizardSnapshot.AttributeInfo#followsEntity}). This class only decides which group an item belongs to and what the groups count.
 * <ul>
 * <li>"Accept what you see": per business object, the object itself, its fields (matching keys and computed fields included), its source links and field
 * links. A line that needs your eye ({@code AttentionRules} flag) stays in the list and is counted apart.</li>
 * <li>"Checks": per business object, the rules and script checks still to decide.</li>
 * <li>"Cannot run as proposed": the connector suggestions that lack a secret ({@code ready = false}) or an input no field holds ({@code toBeProvided} in the
 * binding), one reason each.</li>
 * <li>"Follow their object": the technical key and the parent link - nothing to decide, listed.</li>
 * <li>Look-up suggestions that could run stay optional, as in the matrix (MAPPING-MATRIX sec 15): one folded line per field, never counted.</li>
 * </ul>
 * Pure, unit-tested in {@code DeskGroupsTest}.
 */
public final class DeskGroups
{
	/** The most lines of an accepted / set aside list on the empty state - a long list is cut with "and N more". */
	static final int DONE_LIST_LIMIT = 8;

	private DeskGroups()
	{
	}

	/** See {@link DeskModel#of}. */
	static DeskModel build(final MatrixModel.Project pProject, final WizardSnapshot pSnapshot, final int pBulkThreshold)
	{
		final DeskModel model = new DeskModel(pProject.projectLabel);
		final List<MatrixCompute.StillItem> items = MatrixCompute.stillToDecide(pProject, pBulkThreshold);

		// the fields that follow their object are never a decision, whatever the matrix list holds for them
		final Set<String> followedAttributes = new HashSet<String>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				if (attr.sourceKey || attr.parentLink)
				{
					followedAttributes.add(entity.id + "|" + attr.id);
				}
			}
		}

		final List<DeskModel.Group> objectGroups = new ArrayList<DeskModel.Group>();
		final List<DeskModel.Group> checkGroups = new ArrayList<DeskModel.Group>();
		final List<MatrixCompute.StillItem> cannotRunGuards = new ArrayList<MatrixCompute.StillItem>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			final Map<String, String> sourceOfFeed = new HashMap<String, String>();
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				sourceOfFeed.put(feed.id, feed.sourceId);
			}
			final DeskModel.Group object = new DeskModel.Group(DeskModel.OBJECT, DeskModel.objectKey(entity.id), entity.id, entity.name);
			final DeskModel.Group checks = new DeskModel.Group(DeskModel.CHECKS, DeskModel.checksKey(entity.id), entity.id, entity.name);
			final Set<String> flaggedFields = new LinkedHashSet<String>();
			final Set<String> sources = new LinkedHashSet<String>();
			for (final MatrixCompute.StillItem item : items)
			{
				if (!item.entityId.equals(entity.id) || (!item.attributeId.isEmpty() && followedAttributes.contains(entity.id + "|" + item.attributeId)))
				{
					continue;
				}
				final DeskModel.Line line = DeskGroups.line(entity, item, pProject);
				if (item.guard != null && item.guard.cannotRunAsProposed())
				{
					// live fix 2026-10-22: a script check with no secret or an input no field holds cannot run - it is listed in "Cannot run as proposed", never in "Checks"
					cannotRunGuards.add(item);
					continue;
				}
				if (MatrixCompute.KIND_RULE.equals(item.kind) || MatrixCompute.KIND_CHECK.equals(item.kind))
				{
					checks.lines.add(line);
					DeskGroups.count(checks, item, flaggedFields);
					continue;
				}
				object.lines.add(line);
				DeskGroups.count(object, item, flaggedFields);
				if (MatrixCompute.KIND_ENTITY.equals(item.kind))
				{
					object.objects++;
				}
				else if (MatrixCompute.KIND_SOURCE_LINK.equals(item.kind) || MatrixCompute.KIND_FIELD_LINK.equals(item.kind))
				{
					object.links++;
					final String feedId = MatrixCompute.KIND_SOURCE_LINK.equals(item.kind) ? item.id : item.key.substring(0, Math.max(0, item.key.indexOf('|')));
					if (sourceOfFeed.get(feedId) != null)
					{
						sources.add(sourceOfFeed.get(feedId));
					}
				}
				else
				{
					object.fields++;
				}
			}
			object.flaggedFields = flaggedFields.size();
			object.sources = sources.size();
			if (!object.lines.isEmpty())
			{
				objectGroups.add(object);
			}
			if (!checks.lines.isEmpty())
			{
				checks.flaggedFields = checks.flaggedItems;
				checkGroups.add(checks);
			}
		}
		model.groups.addAll(objectGroups);
		model.groups.addAll(checkGroups);

		// suggestions that cannot run: the connector services of the wizard card (snapshot), then the script checks of the matrix list that cannot run either
		final Set<String> cannotRunIds = new HashSet<String>();
		final DeskModel.Group cannotRun = new DeskModel.Group(DeskModel.CANNOT_RUN, DeskModel.CANNOT_RUN_KEY, "", "");
		if (pSnapshot != null)
		{
			final Set<String> knownObjects = new HashSet<String>();
			for (final MatrixModel.Entity entity : pProject.entities)
			{
				knownObjects.add(entity.name);
			}
			for (final StillToDecide.CheckItem check : StillToDecide.of(pSnapshot).checks)
			{
				if (!check.cannotRun() || !knownObjects.contains(check.entityName))
				{
					continue;
				}
				final DeskModel.ServiceLine service = new DeskModel.ServiceLine();
				service.id = check.rowId;
				service.entityName = check.entityName;
				service.element = check.element;
				service.proposition = check.proposition;
				service.connector = check.connectorId;
				service.rationale = check.rationale == null ? "" : check.rationale;
				service.confidence = check.confidence;
				service.noSecret = check.secretMissing;
				service.readinessDetail = check.readinessDetail == null ? "" : check.readinessDetail;
				service.needsInput = check.queryToWrite;
				cannotRun.services.add(service);
				cannotRunIds.add(service.id);
			}
		}
		for (final MatrixCompute.StillItem item : cannotRunGuards)
		{
			if (cannotRunIds.contains(item.id))
			{
				continue; // once only, whichever list it came from
			}
			final Guard guard = item.guard;
			final DeskModel.ServiceLine service = new DeskModel.ServiceLine();
			service.id = item.id;
			service.nature = guard.nature;
			service.entityName = item.entityName;
			service.element = item.entityName + (item.attributeName.isEmpty() ? "" : "." + item.attributeName);
			service.proposition = item.label;
			service.connector = guard.serviceConnector;
			service.rationale = guard.rationale == null ? "" : guard.rationale;
			service.confidence = item.confidence;
			service.noSecret = guard.notReady;
			service.readinessDetail = StillToDecide.readableDetail(guard.notReadyDetail);
			service.needsInput = guard.needsInput;
			cannotRun.services.add(service);
			cannotRunIds.add(service.id);
		}
		if (!cannotRun.services.isEmpty())
		{
			model.groups.add(cannotRun);
		}
		for (final MatrixCompute.ServiceGroup group : MatrixCompute.optionalServices(pProject))
		{
			int couldRun = 0;
			for (final MatrixCompute.StillItem item : group.items)
			{
				if (!cannotRunIds.contains(item.id))
				{
					couldRun++;
				}
			}
			if (couldRun > 0)
			{
				model.optional.add(new DeskModel.OptionalLine(group.entityName, group.attributeName, couldRun));
			}
		}

		DeskGroups.follows(model, pProject, pSnapshot);
		DeskGroups.figures(model, pProject);
		DeskGroups.done(model, pProject, pSnapshot);
		return model;
	}

	private static void count(final DeskModel.Group pGroup, final MatrixCompute.StillItem pItem, final Set<String> pFlaggedFields)
	{
		if (MatrixCompute.REASON_ATTENTION.equals(pItem.reason))
		{
			pGroup.flaggedItems++;
			pFlaggedFields.add(pItem.attributeId.isEmpty() ? pItem.key : pItem.attributeId);
		}
		if (pItem.takenByTheButton())
		{
			pGroup.withButton++;
		}
	}

	/** The facts the desk prints for one item, looked up in the matrix model (the list item itself carries only the label, the reason and the confidence). */
	private static DeskModel.Line line(final MatrixModel.Entity pEntity, final MatrixCompute.StillItem pItem, final MatrixModel.Project pProject)
	{
		final DeskModel.Line line = new DeskModel.Line(pItem);
		MatrixModel.Attr attr = null;
		for (final MatrixModel.Attr candidate : pEntity.attrs)
		{
			if (candidate.id.equals(pItem.attributeId))
			{
				attr = candidate;
			}
		}
		if (MatrixCompute.KIND_FIELD.equals(pItem.kind) || MatrixCompute.KIND_MATCHING_KEY.equals(pItem.kind) || MatrixCompute.KIND_COMPUTED.equals(pItem.kind))
		{
			if (attr != null)
			{
				line.rationale = attr.rationale;
				line.dataType = attr.dataType;
				line.mandatory = attr.mandatory;
				line.derivation = attr.derivation;
				line.derivedFrom = attr.derivedFrom;
			}
		}
		else if (MatrixCompute.KIND_FIELD_LINK.equals(pItem.kind))
		{
			final int bar = pItem.key.indexOf('|');
			final String feedId = bar < 0 ? "" : pItem.key.substring(0, bar);
			for (final MatrixModel.Feed feed : pEntity.feeds)
			{
				if (feed.id.equals(feedId))
				{
					line.sourceName = DeskGroups.sourceName(pProject, feed);
					final MatrixModel.Cell cell = attr == null ? null : feed.cellsByAttributeId.get(attr.id);
					if (cell != null)
					{
						line.column = cell.landingColumn == null ? "" : cell.landingColumn;
						line.steps = cell.pipelineSteps;
						line.rationale = cell.rationale == null ? "" : cell.rationale;
					}
				}
			}
		}
		else if (MatrixCompute.KIND_SOURCE_LINK.equals(pItem.kind))
		{
			for (final MatrixModel.Feed feed : pEntity.feeds)
			{
				if (feed.id.equals(pItem.id))
				{
					line.sourceName = DeskGroups.sourceName(pProject, feed);
				}
			}
		}
		else if (pItem.guard != null)
		{
			final Guard guard = pItem.guard;
			line.rationale = guard.rationale == null ? "" : guard.rationale;
			line.guardKind = guard.kind;
			line.guardArgument = guard.argument;
			line.severity = guard.severity == null ? "" : guard.severity;
		}
		// live fix, round 2: a SAME_CONSTANT flag is shown by its lot L3 callout only
		if (attr != null && (MatrixCompute.KIND_FIELD.equals(pItem.kind) || MatrixCompute.KIND_FIELD_LINK.equals(pItem.kind)))
		{
			final int bar = pItem.key.indexOf('|');
			final String linkFeed = MatrixCompute.KIND_FIELD_LINK.equals(pItem.kind) ? (bar < 0 ? "" : pItem.key.substring(0, bar)) : null;
			for (final MatrixModel.Feed feed : pEntity.feeds)
			{
				final MatrixModel.Cell cell = feed.cellsByAttributeId.get(attr.id);
				if ((linkFeed == null || feed.id.equals(linkFeed)) && cell != null && com.on.ebx.factory.staging.AttentionRules.SAME_CONSTANT.equals(cell.attentionFlag))
				{
					line.sameConstant = true;
				}
			}
		}
		return line;
	}

	private static String sourceName(final MatrixModel.Project pProject, final MatrixModel.Feed pFeed)
	{
		final String name = DeskSourceNames.of(pProject).get(pFeed.sourceId);
		return name == null ? "" : name;
	}

	// ------------------------------------------------------------------------------------------------ follow their object

	/** Fields that have no decision of their own: from the wizard snapshot when there is one ({@code followsEntity}), else from the matrix model. */
	private static void follows(final DeskModel pModel, final MatrixModel.Project pProject, final WizardSnapshot pSnapshot)
	{
		final Set<String> shown = new HashSet<String>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			shown.add(entity.name);
		}
		if (pSnapshot != null)
		{
			for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
			{
				if (!shown.contains(entity.name))
				{
					continue;
				}
				for (final WizardSnapshot.AttributeInfo attribute : entity.attributes)
				{
					if (attribute.followsEntity())
					{
						pModel.follows.add(new DeskModel.Follow(entity.name, attribute.name, attribute.primaryKey ? DeskModel.FOLLOW_KEY : DeskModel.FOLLOW_PARENT));
					}
				}
			}
			return;
		}
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				if (attr.sourceKey || attr.parentLink)
				{
					pModel.follows.add(new DeskModel.Follow(entity.name, attr.name, attr.sourceKey ? DeskModel.FOLLOW_KEY : DeskModel.FOLLOW_PARENT));
				}
			}
		}
	}

	// -------------------------------------------------------------------------------------------------------------- figures

	private static boolean decided(final String pStatus)
	{
		return ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus) || ReviewLine.REJECTED.equals(pStatus);
	}

	/**
	 * "X of Y proposals decided": every decidable row of the matrix model - the business object, a field (never the technical key or the parent link), a source link,
	 * a field link, a rule / check / service - and how many of them are no longer {@code PROPOSED}. A suggestion set aside is not in the model, so it is in neither.
	 */
	static void figures(final DeskModel pModel, final MatrixModel.Project pProject)
	{
		int total = 0;
		int decided = 0;
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			total++;
			decided += DeskGroups.decided(entity.status) ? 1 : 0;
			for (final Guard guard : entity.entityGuards)
			{
				total++;
				decided += DeskGroups.decided(guard.status) ? 1 : 0;
			}
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				total++;
				decided += DeskGroups.decided(feed.status) ? 1 : 0;
				for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
				{
					if (cell.isData() && cell.mappingId != null)
					{
						total++;
						decided += DeskGroups.decided(cell.status) ? 1 : 0;
					}
				}
			}
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				if (attr.sourceKey || attr.parentLink)
				{
					continue;
				}
				total++;
				decided += DeskGroups.decided(attr.status) ? 1 : 0;
				for (final Guard guard : attr.guards)
				{
					total++;
					decided += DeskGroups.decided(guard.status) ? 1 : 0;
				}
			}
		}
		pModel.totalProposals = total;
		pModel.decidedProposals = decided;
	}

	// ----------------------------------------------------------------------------------------------------------- empty state

	/** What the empty state lists: accepted (per business object, then the accepted checks), set aside (snapshot), followed. */
	private static void done(final DeskModel pModel, final MatrixModel.Project pProject, final WizardSnapshot pSnapshot)
	{
		final DeskModel.Done done = pModel.done;
		done.sources = pProject.sources.size();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			done.objectNames.add(entity.name);
			int fields = 0;
			int links = 0;
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				if (!attr.sourceKey && !attr.parentLink && DeskGroups.accepted(attr.status))
				{
					fields++;
				}
			}
			for (final MatrixModel.Feed feed : entity.feeds)
			{
				for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
				{
					if (cell.isData() && cell.mappingId != null && DeskGroups.accepted(cell.status))
					{
						links++;
					}
				}
			}
			if (fields > 0 || links > 0)
			{
				done.acceptedObjects.add(new DeskModel.AcceptedObject(entity.name, fields, links));
			}
		}
		final List<String> checks = new ArrayList<String>();
		for (final MatrixModel.Entity entity : pProject.entities)
		{
			for (final Guard guard : entity.entityGuards)
			{
				DeskGroups.addAcceptedCheck(checks, entity.name, null, guard);
			}
			for (final MatrixModel.Attr attr : entity.attrs)
			{
				for (final Guard guard : attr.guards)
				{
					DeskGroups.addAcceptedCheck(checks, entity.name, attr.name, guard);
				}
			}
		}
		done.acceptedChecks.addAll(DeskGroups.cut(checks));
		done.moreChecks = Math.max(0, checks.size() - DeskGroups.DONE_LIST_LIMIT);
		if (pSnapshot != null)
		{
			final List<String> aside = new ArrayList<String>();
			for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
			{
				for (final WizardSnapshot.CapabilityInfo capability : entity.capabilities)
				{
					if (ReviewLine.SET_ASIDE.equals(capability.status))
					{
						aside.add(entity.name + (capability.attributeName.isEmpty() ? "" : "." + capability.attributeName) + ": "
							+ (capability.connectorId.isEmpty() ? "" : capability.connectorId + " \u00b7 ") + capability.connectorOperation);
					}
				}
			}
			done.setAside.addAll(DeskGroups.cut(aside));
			done.moreSetAside = Math.max(0, aside.size() - DeskGroups.DONE_LIST_LIMIT);
		}
		for (final DeskModel.Follow follow : pModel.follows)
		{
			done.followed.add(follow.entityName + "." + follow.name);
		}
	}

	private static boolean accepted(final String pStatus)
	{
		return ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus);
	}

	private static void addAcceptedCheck(final List<String> pOut, final String pEntityName, final String pAttributeName, final Guard pGuard)
	{
		if (DeskGroups.accepted(pGuard.status))
		{
			pOut.add(pEntityName + (pAttributeName == null ? "" : "." + pAttributeName) + ": " + pGuard.label());
		}
	}

	/** The first {@link #DONE_LIST_LIMIT} entries; the caller counts what is left out. */
	private static List<String> cut(final List<String> pAll)
	{
		return pAll.size() <= DeskGroups.DONE_LIST_LIMIT ? pAll : new ArrayList<String>(pAll.subList(0, DeskGroups.DONE_LIST_LIMIT));
	}

	// ----------------------------------------------------------------------------------------------------------- selection

	/**
	 * The group to show: the requested one while it still has something to decide; once decided away, the next group that followed it in the last render
	 * ({@code pPreviousKeys}); else the first group. Empty when nothing is left (the empty state).
	 */
	public static String resolveSelection(final DeskModel pModel, final String pRequestedKey, final List<String> pPreviousKeys)
	{
		if (pModel.groups.isEmpty())
		{
			return "";
		}
		if (pRequestedKey != null && pModel.group(pRequestedKey) != null)
		{
			return pRequestedKey;
		}
		if (pRequestedKey != null && pPreviousKeys != null)
		{
			final int at = pPreviousKeys.indexOf(pRequestedKey);
			for (int i = at < 0 ? pPreviousKeys.size() : at + 1; i < pPreviousKeys.size(); i++)
			{
				if (pModel.group(pPreviousKeys.get(i)) != null)
				{
					return pPreviousKeys.get(i);
				}
			}
		}
		return pModel.groups.get(0).key;
	}
}
