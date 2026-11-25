package com.on.ebx.factory.accel;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Assembles the canonical workflow model of the {@code creationWithReview} type (lot C1-D, pure). The skeleton is the measured model of
 * {@code docs/ACCELERATORS.md} section 14 ({@code accel/workflow/creationWithReview.template.json}, same text as
 * {@code tools/workflow/AccelCreationWithReview.template.json}); this class substitutes the {@code ${...}} placeholders and expands the two
 * that are structural: {@code ${reviewNodes}} (one review user task + one {@code ConditionIsLastUserTaskAccepted} per reviewer, in order) and
 * {@code ${links}}.
 * <p>
 * Node ids: 0 work dataspace, 1 creation, 2 "Submitted?", then {@code 3 + 2i} review i and {@code 4 + 2i} its condition, then merge, close,
 * correction and its condition. With one reviewer that is the nine nodes of the measured model.
 * Links: creation to condition; condition ifTrue to the first review, ifFalse to close; review to its condition; condition ifTrue to the next
 * review (the last one to merge), ifFalse to the correction; correction to its condition; that condition ifTrue back to the FIRST review,
 * ifFalse to close.
 */
public final class WorkflowAssembler
{
	/** One reviewer: the profile that is offered the task and the labels of the task. */
	public static final class Reviewer
	{
		public final String profile;
		public final String label;
		public final String labelFr;

		public Reviewer(final String pProfile, final String pLabel, final String pLabelFr)
		{
			this.profile = pProfile;
			this.label = pLabel;
			this.labelFr = pLabelFr == null || pLabelFr.isEmpty() ? pLabel : pLabelFr;
		}
	}

	/** The parameters of {@code creationWithReview}. */
	public static final class Params
	{
		public String modelName;
		public String domainLabel;
		public String entityLabel;
		public String entityLabelFr;
		public String packKey;
		public String packVersion;
		public String domainDataspace;
		public String domainDataset;
		public String tablePath;
		public String requesterProfile;
		public final List<Reviewer> reviewers = new ArrayList<Reviewer>();
		/** Only {@code sequential} is generated in lot C1. */
		public String mode = "sequential";
		public String acceptLabel = "Approve";
		public String acceptLabelFr = "Approuver";
		public String rejectLabel = "Send back";
		public String rejectLabelFr = "Renvoyer";
	}

	private WorkflowAssembler()
	{
	}

	public static ObjectNode assemble(final Params pParams)
	{
		WorkflowAssembler.validate(pParams);
		final ObjectNode template = (ObjectNode) Res.json("workflow/creationWithReview.template.json").deepCopy();
		final int n = pParams.reviewers.size();
		final int firstReview = 3;
		final int merge = 3 + 2 * n;
		final int close = merge + 1;
		final int correct = merge + 2;
		final int correctCondition = merge + 3;

		final Map<String, String> values = new LinkedHashMap<String, String>();
		values.put("modelName", pParams.modelName);
		values.put("domainLabel", pParams.domainLabel);
		values.put("entityLabel", pParams.entityLabel);
		values.put("entityLabelFr", pParams.entityLabelFr == null || pParams.entityLabelFr.isEmpty() ? pParams.entityLabel : pParams.entityLabelFr);
		values.put("packKey", pParams.packKey);
		values.put("packVersion", pParams.packVersion);
		values.put("domainDataspace", pParams.domainDataspace);
		values.put("domainDataset", pParams.domainDataset);
		values.put("tablePath", pParams.tablePath);
		values.put("requesterProfile", pParams.requesterProfile);

		final ArrayNode nodes = (ArrayNode) template.get("nodes");
		final ArrayNode outNodes = Res.MAPPER.createArrayNode();
		for (final JsonNode node : nodes)
		{
			final ObjectNode copy = (ObjectNode) node.deepCopy();
			final String id = copy.path("nodeId").asText("");
			if ("${reviewNodes}".equals(id))
			{
				for (int i = 0; i < n; i++)
				{
					outNodes.add(WorkflowAssembler.reviewTask(pParams, i, firstReview + 2 * i, values));
					outNodes.add(WorkflowAssembler.reviewCondition(pParams, i, firstReview + 2 * i + 1));
				}
				continue;
			}
			if ("${mergeNode}".equals(id))
			{
				copy.put("nodeId", merge);
			}
			else if ("${closeNode}".equals(id))
			{
				copy.put("nodeId", close);
			}
			else if ("${correctNode}".equals(id))
			{
				copy.put("nodeId", correct);
			}
			else if ("${correctCondition}".equals(id))
			{
				copy.put("nodeId", correctCondition);
			}
			outNodes.add(WorkflowAssembler.substitute(copy, values));
		}
		template.set("nodes", outNodes);

		final ArrayNode links = template.putArray("links");
		WorkflowAssembler.link(links, 0, 1, "default");
		WorkflowAssembler.link(links, 1, 2, "default");
		WorkflowAssembler.link(links, 2, firstReview, "ifTrue");
		WorkflowAssembler.link(links, 2, close, "ifFalse");
		for (int i = 0; i < n; i++)
		{
			final int task = firstReview + 2 * i;
			final int condition = task + 1;
			WorkflowAssembler.link(links, task, condition, "default");
			WorkflowAssembler.link(links, condition, i == n - 1 ? merge : task + 2, "ifTrue");
			WorkflowAssembler.link(links, condition, correct, "ifFalse");
		}
		WorkflowAssembler.link(links, correct, correctCondition, "default");
		WorkflowAssembler.link(links, correctCondition, firstReview, "ifTrue");
		WorkflowAssembler.link(links, correctCondition, close, "ifFalse");

		template.remove("_measured");
		final ObjectNode model = (ObjectNode) WorkflowAssembler.substitute(template.get("model").deepCopy(), values);
		template.set("model", model);
		final String text = template.toString().replace("${workDataspace}", "").replace("${recordXPath}", "");
		if (text.contains("${"))
		{
			throw new IllegalStateException("The assembled workflow keeps an unresolved placeholder: " + text.substring(text.indexOf("${"), Math.min(text.length(), text.indexOf("${") + 30)));
		}
		return template;
	}

	private static void validate(final Params p)
	{
		for (final String[] f : new String[][] { { "modelName", p.modelName }, { "domainDataspace", p.domainDataspace }, { "domainDataset", p.domainDataset },
				{ "tablePath", p.tablePath }, { "requesterProfile", p.requesterProfile }, { "entityLabel", p.entityLabel }, { "domainLabel", p.domainLabel } })
		{
			if (f[1] == null || f[1].trim().isEmpty())
			{
				throw new IllegalArgumentException("creationWithReview needs " + f[0]);
			}
		}
		if (p.reviewers.isEmpty())
		{
			throw new IllegalArgumentException("creationWithReview needs at least one reviewer");
		}
		if (!"sequential".equals(p.mode))
		{
			throw new IllegalArgumentException("creationWithReview mode '" + p.mode + "' is not generated in lot C1 (sequential only)");
		}
		if (p.packKey == null)
		{
			p.packKey = "-";
		}
		if (p.packVersion == null)
		{
			p.packVersion = "-";
		}
	}

	private static ObjectNode reviewTask(final Params p, final int pIndex, final int pId, final Map<String, String> pValues)
	{
		final Reviewer r = p.reviewers.get(pIndex);
		final ObjectNode t = Res.MAPPER.createObjectNode();
		t.put("nodeId", pId);
		t.put("kind", "userTask");
		t.put("label", "Review: " + r.label);
		t.put("description", "Reviewed in the work dataspace; approve to go on, send back to correct.");
		t.put("hiddenInGraphicalView", false);
		t.put("userTaskVersion", 2);
		t.put("agentAccess", "allowed");
		t.put("agentGuidance", "askHuman");
		t.put("serviceDeclarationKey", "ebx-root-1.0@default");
		t.putArray("profiles").add(r.profile);
		t.putArray("offeredToProfiles").add(r.profile);
		final ArrayNode in = t.putObject("input").putArray("parameters");
		WorkflowAssembler.param(in, "branch", "${workDataspace}");
		WorkflowAssembler.param(in, "instance", p.domainDataset);
		WorkflowAssembler.param(in, "xpath", "${recordXPath}");
		WorkflowAssembler.param(in, "firstCallDisplay", "record");
		t.put("enableReject", true);
		t.put("enableConfirmationRequest", true);
		t.put("enableComment", true);
		t.put("commentStrategyCode", "requiredIfRejected");
		final ObjectNode accept = t.putObject("customizedAcceptLabel");
		accept.put("en_US", p.acceptLabel);
		accept.put("fr_FR", p.acceptLabelFr);
		final ObjectNode reject = t.putObject("customizedRejectLabel");
		reject.put("en_US", p.rejectLabel);
		reject.put("fr_FR", p.rejectLabelFr);
		final ObjectNode term = t.putObject("termination");
		term.put("completionStrategy", "allWorkItemsCompleted");
		term.put("rejectionThreshold", 0);
		return t;
	}

	private static ObjectNode reviewCondition(final Params p, final int pIndex, final int pId)
	{
		final ObjectNode c = Res.MAPPER.createObjectNode();
		c.put("nodeId", pId);
		c.put("kind", "condition");
		c.put("label", "Approved by " + p.reviewers.get(pIndex).label + "?");
		c.put("hiddenInGraphicalView", false);
		c.put("scriptClass", "ebx-root-1.0@com.orchestranetworks.workflow.beans.ConditionIsLastUserTaskAccepted");
		c.put("conditionLibrary", true);
		return c;
	}

	private static void param(final ArrayNode pTo, final String pName, final String pValue)
	{
		pTo.addObject().put("name", pName).put("value", pValue);
	}

	private static void link(final ArrayNode pLinks, final int pFrom, final int pTo, final String pRole)
	{
		pLinks.addObject().put("fromNodeId", pFrom).put("toNodeId", pTo).put("role", pRole);
	}

	/** Replaces {@code ${name}} in every text of the tree; a workflow variable written {@code ${workDataspace}} (not in the map) is kept. */
	static JsonNode substitute(final JsonNode pNode, final Map<String, String> pValues)
	{
		if (pNode.isObject())
		{
			final ObjectNode o = (ObjectNode) pNode;
			final List<String> names = new ArrayList<String>();
			for (final Iterator<String> it = o.fieldNames(); it.hasNext();)
			{
				names.add(it.next());
			}
			for (final String name : names)
			{
				if (name.startsWith("_"))
				{
					o.remove(name);
					continue;
				}
				o.set(name, WorkflowAssembler.substitute(o.get(name), pValues));
			}
			return o;
		}
		if (pNode.isArray())
		{
			final ArrayNode a = (ArrayNode) pNode;
			for (int i = 0; i < a.size(); i++)
			{
				a.set(i, WorkflowAssembler.substitute(a.get(i), pValues));
			}
			return a;
		}
		if (pNode.isTextual())
		{
			String s = pNode.asText();
			for (final Map.Entry<String, String> e : pValues.entrySet())
			{
				s = s.replace("${" + e.getKey() + "}", e.getValue() == null ? "" : e.getValue());
			}
			return Res.MAPPER.getNodeFactory().textNode(s);
		}
		return pNode;
	}
}
