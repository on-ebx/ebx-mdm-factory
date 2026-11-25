package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.project.DomainCleanupPlan;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Removes the objects of a generated domain (lot C1-D) from its {@code DomainObject} rows: the workflow publication (unpublished with
 * {@code terminateActiveInstances=false} first, so a running instance refuses the deletion instead of being killed), the workflow model, the
 * matching configuration, the domain dataspace (only when the {@code dataspaces} option is ticked, as {@code MasterReset} deletes a dataspace:
 * {@code closeHome} then {@code deleteHome}), the form layouts and the data model. An item whose option is not ticked is reported
 * {@code NOT_SELECTED}; an item that fails is reported and the DomainObject rows are KEPT (the call can be retried). Called by
 * {@code ProjectDeleter} for a project that has DomainObject rows.
 */
final class DomainCleanup
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private DomainCleanup()
	{
	}

	static boolean hasDomain(final Adaptation pFactory, final String pProjectId)
	{
		return !DomainObjectWrites.list(pFactory, pProjectId).isEmpty();
	}

	/** The items a reset or a deletion would remove, as {@code [{kind, name, option}]} (nothing is touched). */
	static ArrayNode plan(final Adaptation pFactory, final String pProjectId)
	{
		final List<DomainCleanupPlan.Row> facts = new ArrayList<DomainCleanupPlan.Row>();
		for (final DomainObjectWrites.Row r : DomainObjectWrites.list(pFactory, pProjectId))
		{
			facts.add(new DomainCleanupPlan.Row(r.kind, r.name, r.path));
		}
		final ArrayNode out = DomainCleanup.MAPPER.createArrayNode();
		for (final DomainCleanupPlan.Item item : DomainCleanupPlan.build(facts))
		{
			final ObjectNode line = out.addObject();
			line.put("kind", item.kind.name());
			line.put("name", item.name);
			line.put("option", item.option);
			line.put("detail", "");
			line.put("disposition", "DELETE");
		}
		return out;
	}

	/** @return the outcomes, one per item; {@code failed} gets the failures. DomainObject rows (a trace, whatever was ticked) are deleted only when nothing failed. */
	static ArrayNode run(final Session pSession, final Adaptation pFactory, final String pProjectId, final Set<String> pOptions, final List<String> pFailed)
		throws OperationException
	{
		final List<DomainObjectWrites.Row> rows = DomainObjectWrites.list(pFactory, pProjectId);
		final List<DomainCleanupPlan.Row> facts = new ArrayList<DomainCleanupPlan.Row>();
		for (final DomainObjectWrites.Row r : rows)
		{
			facts.add(new DomainCleanupPlan.Row(r.kind, r.name, r.path));
		}
		final ArrayNode out = DomainCleanup.MAPPER.createArrayNode();
		for (final DomainCleanupPlan.Item item : DomainCleanupPlan.build(facts))
		{
			final ObjectNode line = out.addObject();
			line.put("kind", item.kind.name());
			line.put("name", item.name);
			if (!pOptions.contains(item.option))
			{
				line.put("outcome", "NOT_SELECTED");
				continue;
			}
			final GeneratedObjectDeleter.Outcome outcome = DomainCleanup.execute(pSession, pFactory, item);
			line.put("outcome", outcome.result);
			if (outcome.message != null)
			{
				line.put("message", outcome.message);
			}
			if (outcome.code != null)
			{
				line.put("reasonCode", outcome.code);
			}
			if ("FAILED".equals(outcome.result))
			{
				pFailed.add(item.kind + " " + item.name + ": " + outcome.message);
			}
		}
		if (pFailed.isEmpty())
		{
			final List<String> ids = new ArrayList<String>();
			for (final DomainObjectWrites.Row r : rows)
			{
				ids.add(r.id);
			}
			DomainObjectWrites.delete(pSession, pFactory, ids);
		}
		return out;
	}

	private static GeneratedObjectDeleter.Outcome execute(final Session pSession, final Adaptation pFactory, final DomainCleanupPlan.Item pItem)
	{
		try
		{
			switch (pItem.kind)
			{
				case MATCHING_CONFIG:
					return GeneratedObjectDeleter.deleteMatchingConfig(pSession, pFactory, pItem.tablePath, pItem.modelName);
				case WORKFLOW_PUBLICATION:
					return GeneratedObjectDeleter.deleteWorkflowPublication(pSession, pFactory, pItem.name);
				case WORKFLOW_MODEL:
					return GeneratedObjectDeleter.deleteWorkflowModel(pSession, pFactory, pItem.name);
				case DOMAIN_DATASPACE:
					return GeneratedObjectDeleter.deleteDataspace(pSession, pFactory, pItem.name);
				case FORM_LAYOUTS:
					return GeneratedObjectDeleter.deleteFormLayouts(pSession, pFactory, pItem.name);
				case DATA_MODEL:
					return GeneratedObjectDeleter.deleteDataModel(pSession, pFactory, pItem.name);
				default:
					return GeneratedObjectDeleter.Outcome.failed("Unknown item kind " + pItem.kind);
			}
		}
		catch (final McpException ex)
		{
			return GeneratedObjectDeleter.Outcome.failed(ex.getMessage());
		}
		catch (final OperationException ex)
		{
			return GeneratedObjectDeleter.Outcome.failed(ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			return GeneratedObjectDeleter.Outcome.failed(ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage());
		}
	}
}
