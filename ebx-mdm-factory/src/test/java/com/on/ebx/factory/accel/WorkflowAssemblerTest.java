package com.on.ebx.factory.accel;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** JUnit-free: run the main, a failure throws. */
public final class WorkflowAssemblerTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		WorkflowAssemblerTest.oneReviewerEqualsTheMeasuredModel();
		WorkflowAssemblerTest.twoReviewersChain();
		WorkflowAssemblerTest.refusals();
		WorkflowAssemblerTest.templateCopiesAreIdentical();
		System.out.println("WorkflowAssemblerTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		PackCatalogTest.check(pWhat, pCondition);
	}

	private static WorkflowAssembler.Params params(final int pReviewers)
	{
		final WorkflowAssembler.Params p = new WorkflowAssembler.Params();
		p.modelName = "AccelProbeSupplierCreation";
		p.domainLabel = "AccelProbe";
		p.entityLabel = "supplier";
		p.entityLabelFr = "un fournisseur";
		p.packKey = "company";
		p.packVersion = "1.0.0";
		p.domainDataspace = "Reference";
		p.domainDataset = "AccelProbe";
		p.tablePath = "/root/Supplier";
		p.requesterProfile = "Badministrator";
		for (int i = 0; i < pReviewers; i++)
		{
			p.reviewers.add(new WorkflowAssembler.Reviewer(i == 0 ? "Badministrator" : "RFinance", i == 0 ? "Purchasing" : "Finance", null));
		}
		p.acceptLabel = "Approve supplier";
		p.rejectLabel = "Send back";
		return p;
	}

	private static JsonNode node(final JsonNode pModel, final int pId)
	{
		for (final JsonNode n : pModel.path("nodes"))
		{
			if (n.path("nodeId").asInt(-1) == pId)
			{
				return n;
			}
		}
		throw new IllegalStateException("no node " + pId);
	}

	private static String param(final JsonNode pParams, final String pName)
	{
		for (final JsonNode p : pParams.path("parameters"))
		{
			if (pName.equals(p.path("name").asText()))
			{
				return p.path("value").asText();
			}
		}
		return null;
	}

	/** The fixture is docs/ACCELERATORS.md section 14: 9 nodes, node by node, and its links. */
	private static void oneReviewerEqualsTheMeasuredModel()
	{
		final ObjectNode m = WorkflowAssembler.assemble(WorkflowAssemblerTest.params(1));
		check("nine nodes", m.path("nodes").size() == 9);
		final String lib = "ebx-root-1.0@com.orchestranetworks.workflow.beans.";
		// node 0: ScriptTaskCreateBranchI18n, child of the domain dataspace, output newHome -> workDataspace
		final JsonNode n0 = WorkflowAssemblerTest.node(m, 0);
		check("n0 class", (lib + "ScriptTaskCreateBranchI18n").equals(n0.path("scriptClass").asText()) && "library".equals(n0.path("scriptMode").asText())
				&& n0.path("scriptLibrary").asBoolean());
		check("n0 initialBranch", "Reference".equals(WorkflowAssemblerTest.paramOf(n0, "beanParams", "initialBranch"))
				&& "true".equals(WorkflowAssemblerTest.paramOf(n0, "beanParams", "ensureBranchClosingOnWorkflowTermination")));
		check("n0 output", "newHome".equals(n0.path("outputMappingParams").get(0).path("name").asText())
				&& "workDataspace".equals(n0.path("outputMappingParams").get(0).path("value").asText()));
		// node 1: creation task
		final JsonNode n1 = WorkflowAssemblerTest.node(m, 1);
		check("n1 creation", "ebx-root-1.0@creation".equals(n1.path("serviceDeclarationKey").asText()) && "userTask".equals(n1.path("kind").asText()));
		check("n1 inputs", "@creation".equals(WorkflowAssemblerTest.param(n1.path("input"), "service")) && "${workDataspace}".equals(WorkflowAssemblerTest.param(n1.path("input"), "branch"))
				&& "AccelProbe".equals(WorkflowAssemblerTest.param(n1.path("input"), "instance")) && "/root/Supplier".equals(WorkflowAssemblerTest.param(n1.path("input"), "xpath")));
		check("n1 output mapping {name: service output, value: variable}", "created".equals(n1.path("output").path("parameters").get(0).path("name").asText())
				&& "recordXPath".equals(n1.path("output").path("parameters").get(0).path("value").asText()));
		check("n1 profile", "Badministrator".equals(n1.path("profiles").get(0).asText()));
		// node 2: condition
		check("n2 condition", (lib + "ConditionIsLastUserTaskAccepted").equals(WorkflowAssemblerTest.node(m, 2).path("scriptClass").asText()));
		// node 3: review, 4: condition
		final JsonNode n3 = WorkflowAssemblerTest.node(m, 3);
		check("n3 review", "ebx-root-1.0@default".equals(n3.path("serviceDeclarationKey").asText()) && "record".equals(WorkflowAssemblerTest.param(n3.path("input"), "firstCallDisplay"))
				&& "${recordXPath}".equals(WorkflowAssemblerTest.param(n3.path("input"), "xpath")));
		check("n3 labels", "Approve supplier".equals(n3.path("customizedAcceptLabel").path("en_US").asText()) && "Send back".equals(n3.path("customizedRejectLabel").path("en_US").asText()));
		check("n4 condition", "condition".equals(WorkflowAssemblerTest.node(m, 4).path("kind").asText()));
		// 5 merge, 6 close, 7 correction, 8 its condition
		check("n5 merge", (lib + "ScriptTaskMergeBranch").equals(WorkflowAssemblerTest.node(m, 5).path("scriptClass").asText())
				&& "${workDataspace}".equals(WorkflowAssemblerTest.paramOf(WorkflowAssemblerTest.node(m, 5), "beanParams", "branchName")));
		check("n6 close", (lib + "ScriptTaskCloseBranch").equals(WorkflowAssemblerTest.node(m, 6).path("scriptClass").asText()));
		final JsonNode n7 = WorkflowAssemblerTest.node(m, 7);
		check("n7 correction", "1".equals(n7.path("previousUserTaskIdReferenceForAllocation").asText()) && "ebx-root-1.0@default".equals(n7.path("serviceDeclarationKey").asText()));
		check("n8 condition", "condition".equals(WorkflowAssemblerTest.node(m, 8).path("kind").asText()));
		// the model header
		check("launching", m.path("model").path("launching").path("quick").asBoolean() && m.path("model").path("launching").path("synchronous").asBoolean());
		check("create right", "Badministrator".equals(m.path("model").path("permissionsOnInstances").path("create").asText())
				&& "Badministrator".equals(m.path("model").path("permissionsOnInstances").path("agentAccessForAdmin").asText())
				&& "Badministrator".equals(m.path("model").path("permissionsOnInstances").path("agentAccessForInbox").asText()));
		check("variables", "workDataspace".equals(m.path("dataContext").path("variables").get(0).path("name").asText())
				&& "-".equals(m.path("dataContext").path("variables").get(1).path("defaultValue").asText()));
		check("model name", "AccelProbeSupplierCreation".equals(m.path("model").path("name").asText()));
		check("no leftover", !m.toString().replace("${workDataspace}", "").replace("${recordXPath}", "").contains("${") && !m.toString().contains("\"_"));
		// links
		final String[] expected = { "0>1:default", "1>2:default", "2>3:ifTrue", "2>6:ifFalse", "3>4:default", "4>5:ifTrue", "4>7:ifFalse", "7>8:default", "8>3:ifTrue", "8>6:ifFalse" };
		check("link count", m.path("links").size() == expected.length);
		for (int i = 0; i < expected.length; i++)
		{
			final JsonNode l = m.path("links").get(i);
			check("link " + expected[i], expected[i].equals(l.path("fromNodeId").asInt() + ">" + l.path("toNodeId").asInt() + ":" + l.path("role").asText()));
		}
	}

	private static String paramOf(final JsonNode pNode, final String pArray, final String pName)
	{
		for (final JsonNode p : pNode.path(pArray))
		{
			if (pName.equals(p.path("name").asText()))
			{
				return p.path("value").asText();
			}
		}
		return null;
	}

	private static void twoReviewersChain()
	{
		final ObjectNode m = WorkflowAssembler.assemble(WorkflowAssemblerTest.params(2));
		check("eleven nodes", m.path("nodes").size() == 11);
		// reviews 3 and 5, conditions 4 and 6, merge 7, close 8, correction 9, its condition 10
		final StringBuilder links = new StringBuilder();
		for (final JsonNode l : m.path("links"))
		{
			links.append(l.path("fromNodeId").asInt()).append('>').append(l.path("toNodeId").asInt()).append(':').append(l.path("role").asText()).append(' ');
		}
		final String s = links.toString();
		check("first review true goes to the second", s.contains("4>5:ifTrue"));
		check("last review true goes to merge", s.contains("6>7:ifTrue"));
		check("every false goes to the correction", s.contains("4>9:ifFalse") && s.contains("6>9:ifFalse"));
		check("correction true returns to the FIRST review", s.contains("10>3:ifTrue") && s.contains("10>8:ifFalse"));
		check("second reviewer profile", "RFinance".equals(WorkflowAssemblerTest.node(m, 5).path("profiles").get(0).asText()));
		check("french fallback", "Finance".equals(WorkflowAssemblerTest.node(m, 5).path("label").asText().replace("Review: ", "")));
	}

	private static void refusals()
	{
		boolean refused = false;
		try
		{
			WorkflowAssembler.assemble(WorkflowAssemblerTest.params(0));
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		check("no reviewer refused", refused);
		refused = false;
		final WorkflowAssembler.Params p = WorkflowAssemblerTest.params(1);
		p.mode = "parallel";
		try
		{
			WorkflowAssembler.assemble(p);
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		check("parallel refused", refused);
	}

	/** The resource and tools/workflow/ carry the same template (checked when run with -Dmdmf.sourceDir). */
	private static void templateCopiesAreIdentical() throws Exception
	{
		final String src = System.getProperty("mdmf.sourceDir");
		if (src == null)
		{
			return;
		}
		final Path root = Paths.get(src).getParent().getParent().getParent().getParent();
		final Path tool = root.resolve("tools/workflow/AccelCreationWithReview.template.json");
		final Path res = Paths.get(src).getParent().resolve("resources/com/on/ebx/factory/accel/workflow/creationWithReview.template.json");
		if (!Files.exists(tool) || !Files.exists(res))
		{
			return;
		}
		check("template copies identical", new String(Files.readAllBytes(tool), StandardCharsets.UTF_8).equals(new String(Files.readAllBytes(res), StandardCharsets.UTF_8)));
	}
}
