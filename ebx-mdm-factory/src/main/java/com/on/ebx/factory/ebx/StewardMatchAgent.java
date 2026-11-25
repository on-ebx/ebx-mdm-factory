package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.agent.AgentDecisions;
import com.on.ebx.factory.agent.AgentPrompts;
import com.on.ebx.factory.agent.AgentTools;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.matching.MatchStats;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;

/**
 * The data steward agent (agentic framework lot, 2026-10-30): proposes MERGE or DISTINCT on the pending Match and Merge suspect pairs of one entity.
 * <p>
 * Reads the pairs the wizard's step 6 reads ({@link MatchingResultsBuilder#buildPendingPairs}, the same field comparison and verdicts), keeps only the pairs
 * whose two records the acting user may read, and shows each field's verdict with its values as the project's language-model data policy allows
 * ({@link AgentPrompts#share}). An accepted decision is the step-6 decision itself ({@link RecordLineageReader#decideSuspect}: accept = merge into the golden's
 * group, reject = not the same), allowed only when the acting user may write both records.
 */
final class StewardMatchAgent implements AgentFramework.Behaviour
{
	static final String MERGE = "MERGE";
	static final String DISTINCT = "DISTINCT";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public List<String> actions()
	{
		return Arrays.asList(StewardMatchAgent.MERGE, StewardMatchAgent.DISTINCT);
	}

	@Override
	public String tool(final String pAction)
	{
		return AgentTools.SUSPECT_DECIDE;
	}

	private static String entity(final AgentFramework.Run pRun) throws OperationException
	{
		final String entity = pRun.text(FactoryPaths.AgentDefinition.ENTITY);
		if (entity.isEmpty())
		{
			throw EngineRefusal.coded("missing.agentEntity", "Agent " + pRun.agentCode + " names no entity: the data steward agent works on the suspects of one entity.",
				pRun.agentCode);
		}
		return entity;
	}

	/** The master table of the entity, null when the master zone is not built. */
	static AdaptationTable masterTable(final AgentFramework.Run pRun, final String pEntity)
	{
		final String dataspace = pRun.project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String dataset = pRun.project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (dataspace == null || dataset == null)
		{
			return null;
		}
		final AdaptationHome home = pRun.factory.getHome().getRepository().lookupHome(HomeKey.forBranchName(dataspace));
		final Adaptation instance = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(dataset));
		try
		{
			final AdaptationTable table = instance == null ? null : instance.getTable(Path.parse(MasterModel.tablePath(pEntity)));
			return table == null || table.getTableOccurrenceRootNode() == null ? null : table;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	private static Adaptation record(final AdaptationTable pTable, final String pKey)
	{
		try
		{
			return pTable == null || pKey == null ? null : pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pKey));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	@Override
	public AgentFramework.Gathered gather(final AgentFramework.Run pRun) throws OperationException
	{
		final String entity = StewardMatchAgent.entity(pRun);
		final AgentFramework.Gathered out = new AgentFramework.Gathered();
		final AdaptationTable table = StewardMatchAgent.masterTable(pRun, entity);
		if (table == null)
		{
			return out;
		}
		final MatchingResultsBuilder.Context context = new MatchingResultsBuilder.Context(pRun.projectCode, entity, entity,
			MatchingResultsStore.compareFields(pRun.factory, pRun.projectId, entity), 0, MatchStats.PENDING, "", true, null, null);
		// read twice what is shown at most: pairs the acting user may not see are left out
		final List<MatchStats.SuspectPair> pairs = MatchingResultsBuilder.buildPendingPairs(pRun.session, pRun.factory, context, pRun.maxItems * 2);
		for (final MatchStats.SuspectPair pair : pairs)
		{
			if (out.arguments.size() >= pRun.maxItems)
			{
				break;
			}
			final Adaptation golden = StewardMatchAgent.record(table, pair.goldenKey);
			final Adaptation suspect = StewardMatchAgent.record(table, pair.suspectKey);
			if (golden == null || suspect == null || pRun.permissions.getAdaptationAccessPermission(golden).isHidden()
				|| pRun.permissions.getAdaptationAccessPermission(suspect).isHidden())
			{
				out.hidden++;
				continue;
			}
			final ObjectNode shown = StewardMatchAgent.MAPPER.createObjectNode();
			shown.put("goldenSource", pair.goldenSource);
			shown.put("suspectSource", pair.suspectSource);
			final ArrayNode fields = shown.putArray("fields");
			for (final MatchStats.FieldComparison field : pair.fields)
			{
				final ObjectNode one = fields.addObject();
				one.put("field", field.label);
				one.put("verdict", field.verdict);
				final boolean hidden = StewardMatchAgent.fieldHidden(pRun, table, golden, suspect, field.name);
				final boolean sensitive = hidden || pRun.policy.isSensitive(field.name) || pRun.policy.isSensitive(field.label);
				one.put("golden", AgentPrompts.share(pRun.sharing, sensitive, field.left));
				one.put("suspect", AgentPrompts.share(pRun.sharing, sensitive, field.right));
			}
			final ObjectNode arguments = StewardMatchAgent.MAPPER.createObjectNode();
			arguments.put("entity", entity);
			arguments.put("golden", pair.goldenKey);
			arguments.put("suspect", pair.suspectKey);
			out.add(shown, arguments);
		}
		return out;
	}

	/** A field the acting user may not read on either record is shown as sensitive (filled or not, nothing more). */
	private static boolean fieldHidden(final AgentFramework.Run pRun, final AdaptationTable pTable, final Adaptation pGolden, final Adaptation pSuspect, final String pField)
	{
		try
		{
			final com.orchestranetworks.schema.SchemaNode node = pTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + pField));
			return node == null || pRun.permissions.getNodeAccessPermission(node, pGolden).isHidden() || pRun.permissions.getNodeAccessPermission(node, pSuspect).isHidden();
		}
		catch (final RuntimeException ex)
		{
			return true;
		}
	}

	@Override
	public String system(final AgentFramework.Run pRun)
	{
		return AgentPrompts.stewardSystem(pRun.text(FactoryPaths.AgentDefinition.PURPOSE), pRun.text(FactoryPaths.AgentDefinition.INSTRUCTIONS), pRun.sharing);
	}

	@Override
	public ObjectNode arguments(final ObjectNode pItemArguments, final AgentDecisions.Decision pDecision)
	{
		return pItemArguments.deepCopy(); // nothing the model says changes which pair is decided
	}

	@Override
	public String describe(final ObjectNode pArguments)
	{
		return pArguments.path("entity").asText("") + " golden " + pArguments.path("golden").asText("") + " / suspect " + pArguments.path("suspect").asText("");
	}

	@Override
	public boolean permitted(final AgentFramework.Run pRun, final SessionPermissions pPermissions, final String pAction, final ObjectNode pArguments)
	{
		final AdaptationTable table = StewardMatchAgent.masterTable(pRun, pArguments.path("entity").asText(""));
		final Adaptation golden = StewardMatchAgent.record(table, pArguments.path("golden").asText(null));
		final Adaptation suspect = StewardMatchAgent.record(table, pArguments.path("suspect").asText(null));
		return golden != null && suspect != null && pPermissions.getAdaptationAccessPermission(golden).isReadWrite()
			&& pPermissions.getAdaptationAccessPermission(suspect).isReadWrite();
	}

	@Override
	public String apply(final AgentFramework.Run pRun, final Session pSession, final String pAction, final ObjectNode pArguments) throws OperationException
	{
		final boolean merge = StewardMatchAgent.MERGE.equals(pAction);
		final RecordLineageReader.DecisionResult result = RecordLineageReader.decideSuspect(pSession, pRun.factory, pRun.projectCode, pArguments.path("entity").asText(""),
			pArguments.path("golden").asText(""), pArguments.path("suspect").asText(""), merge);
		if (!result.success)
		{
			throw EngineRefusal.coded("conflict.agentSuspectDecision", "Match and Merge refused the decision: " + result.outcome + (result.detail == null ? "" : " - " + result.detail),
				String.valueOf(result.outcome));
		}
		return (merge ? "merged" : "marked distinct") + " (" + result.outcome + (result.goldenKey == null ? "" : ", golden " + result.goldenKey) + ")";
	}
}
