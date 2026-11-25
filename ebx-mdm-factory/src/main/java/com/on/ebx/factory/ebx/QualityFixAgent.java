package com.on.ebx.factory.ebx;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.agent.AgentDecisions;
import com.on.ebx.factory.agent.AgentPrompts;
import com.on.ebx.factory.agent.AgentTools;
import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.onwbp.base.text.Severity;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;
import com.orchestranetworks.service.ValidationReportItem;
import com.orchestranetworks.service.ValidationReportItemIterator;
import com.orchestranetworks.service.ValidationReportItemSubjectForAdaptation;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The quality agent (agentic framework lot, 2026-10-30): proposes a corrected value for the fields EBX validation flags on the records of one entity, in the
 * staging or the master zone.
 * <p>
 * Reads each record's own validation report ({@code Adaptation.getValidationReport()}, the per-record read {@code CapabilityApplier} measures with) on at most
 * {@link #SCAN_CAP} records, keeps the field-level findings of severity WARNING or more on fields the acting user may read, and shows the field's value and a
 * few other fields of the record as the project's language-model data policy allows. An accepted decision writes the value into that one field, in one
 * procedure whose execution information names the agent and the user it acts for, after checking the acting user may write that field of that record.
 */
final class QualityFixAgent implements AgentFramework.Behaviour
{
	static final String CORRECT = "CORRECT";
	/** Records read at most per run to find the flagged ones. */
	static final int SCAN_CAP = 5000;
	/** Other fields shown with a finding, for context. */
	static final int CONTEXT_FIELDS = 8;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	@Override
	public List<String> actions()
	{
		return Collections.singletonList(QualityFixAgent.CORRECT);
	}

	@Override
	public String tool(final String pAction)
	{
		return AgentTools.RECORD_CORRECT;
	}

	/** The table of the entity in the agent's zone, null when the zone is not built. */
	static AdaptationTable table(final AgentFramework.Run pRun, final String pZone, final String pEntity)
	{
		final boolean master = !"STAGING".equals(pZone);
		final String dataspace = pRun.project.getString(master ? FactoryPaths.Project.MASTER_DATASPACE : FactoryPaths.Project.STAGING_DATASPACE);
		final String dataset = pRun.project.getString(master ? FactoryPaths.Project.MASTER_DATASET : FactoryPaths.Project.STAGING_DATASET);
		if (dataspace == null || dataset == null || pEntity == null || pEntity.isEmpty())
		{
			return null;
		}
		final AdaptationHome home = pRun.factory.getHome().getRepository().lookupHome(HomeKey.forBranchName(dataspace));
		final Adaptation instance = home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(dataset));
		try
		{
			final AdaptationTable table = instance == null ? null : instance.getTable(Path.parse("/root/" + pEntity));
			return table == null || table.getTableOccurrenceRootNode() == null ? null : table;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	@Override
	public AgentFramework.Gathered gather(final AgentFramework.Run pRun) throws OperationException
	{
		final String entity = pRun.text(FactoryPaths.AgentDefinition.ENTITY);
		if (entity.isEmpty())
		{
			throw EngineRefusal.coded("missing.agentEntity", "Agent " + pRun.agentCode + " names no entity: the quality agent works on the records of one entity.",
				pRun.agentCode);
		}
		final String zone = pRun.text(FactoryPaths.AgentDefinition.ZONE).isEmpty() ? "MASTER" : pRun.text(FactoryPaths.AgentDefinition.ZONE);
		final AgentFramework.Gathered out = new AgentFramework.Gathered();
		final AdaptationTable table = QualityFixAgent.table(pRun, zone, entity);
		if (table == null)
		{
			return out;
		}
		final RequestResult records = table.createRequestResult(null);
		try
		{
			int scanned = 0;
			for (Adaptation record = records.nextAdaptation(); record != null && scanned < QualityFixAgent.SCAN_CAP && out.arguments.size() < pRun.maxItems; record = records
				.nextAdaptation())
			{
				scanned++;
				if (pRun.permissions.getAdaptationAccessPermission(record).isHidden())
				{
					continue; // the acting user does not see the record: not even counted, its existence is not theirs to know
				}
				final ValidationReportItemIterator items = record.getValidationReport().getItemsOfSeverityOrMore(Severity.WARNING);
				while (items.hasNext() && out.arguments.size() < pRun.maxItems)
				{
					final ValidationReportItem item = items.nextItem();
					final ValidationReportItemSubjectForAdaptation subject = item.getSubjectForAdaptation();
					final Path path = subject == null ? null : subject.getPathInAdaptation();
					final String field = QualityFixAgent.fieldOf(path == null ? null : path.format());
					if (field == null)
					{
						continue; // a record-level or table-level finding: no single field to correct
					}
					final SchemaNode node = table.getTableOccurrenceRootNode().getNode(Path.parse("./" + field));
					if (node == null || pRun.permissions.getNodeAccessPermission(node, record).isHidden())
					{
						out.hidden++;
						continue;
					}
					out.add(QualityFixAgent.shown(pRun, table, record, field, node, item), QualityFixAgent.arguments(zone, entity, record, field));
				}
			}
		}
		finally
		{
			records.close();
		}
		return out;
	}

	/** Pure: the field name of a finding's path ({@code /Name} or {@code ./Name}), null for the record itself or a nested path. */
	static String fieldOf(final String pPath)
	{
		if (pPath == null)
		{
			return null;
		}
		String path = pPath.trim();
		if (path.startsWith("./"))
		{
			path = path.substring(2);
		}
		else if (path.startsWith("/"))
		{
			path = path.substring(1);
		}
		return path.isEmpty() || ".".equals(path) || path.indexOf('/') >= 0 ? null : path;
	}

	private static ObjectNode shown(final AgentFramework.Run pRun, final AdaptationTable pTable, final Adaptation pRecord, final String pField, final SchemaNode pNode,
		final ValidationReportItem pItem)
	{
		final ObjectNode shown = QualityFixAgent.MAPPER.createObjectNode();
		shown.put("field", pField);
		shown.put("type", pNode.getXsTypeName() == null ? "" : pNode.getXsTypeName().getNameWithoutPrefix());
		shown.put("message", pItem.getMessage() == null ? "" : pItem.getMessage().formatMessage(Locale.ENGLISH));
		shown.put("severity", pItem.getSeverity() == null ? "" : pItem.getSeverity().toParsableString());
		final boolean sensitive = pRun.policy.isSensitive(pField);
		shown.put("value", AgentPrompts.share(pRun.sharing, sensitive, QualityFixAgent.text(pNode, pRecord)));
		final ObjectNode context = shown.putObject("otherFields");
		int count = 0;
		for (final SchemaNode child : pTable.getTableOccurrenceRootNode().getNodeChildren())
		{
			if (count >= QualityFixAgent.CONTEXT_FIELDS)
			{
				break;
			}
			final String name = child.getPathInAdaptation().getLastStep().format();
			if (name.equals(pField) || name.startsWith("_") || !child.isTerminalValue() || child.getMaxOccurs() != 1
				|| pRun.permissions.getNodeAccessPermission(child, pRecord).isHidden())
			{
				continue;
			}
			final String value = QualityFixAgent.text(child, pRecord);
			if (value == null || value.isEmpty())
			{
				continue;
			}
			context.put(name, AgentPrompts.share(pRun.sharing, pRun.policy.isSensitive(name), value));
			count++;
		}
		return shown;
	}

	private static ObjectNode arguments(final String pZone, final String pEntity, final Adaptation pRecord, final String pField)
	{
		final ObjectNode arguments = QualityFixAgent.MAPPER.createObjectNode();
		arguments.put("zone", pZone);
		arguments.put("entity", pEntity);
		arguments.put("record", pRecord.getOccurrencePrimaryKey().format());
		arguments.put("field", pField);
		return arguments;
	}

	private static String text(final SchemaNode pNode, final Adaptation pRecord)
	{
		try
		{
			final Object value = pRecord.get(pNode);
			return value == null ? null : pNode.formatToXsString(value);
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	@Override
	public String system(final AgentFramework.Run pRun)
	{
		return AgentPrompts.qualitySystem(pRun.text(FactoryPaths.AgentDefinition.PURPOSE), pRun.text(FactoryPaths.AgentDefinition.INSTRUCTIONS), pRun.sharing);
	}

	@Override
	public ObjectNode arguments(final ObjectNode pItemArguments, final AgentDecisions.Decision pDecision)
	{
		final ObjectNode out = pItemArguments.deepCopy();
		if (pDecision.arguments.has("value") && !pDecision.arguments.get("value").isContainerNode())
		{
			out.put("value", pDecision.arguments.get("value").isNull() ? null : pDecision.arguments.get("value").asText());
		}
		return out;
	}

	@Override
	public String describe(final ObjectNode pArguments)
	{
		return pArguments.path("entity").asText("") + " " + pArguments.path("record").asText("") + " ." + pArguments.path("field").asText("");
	}

	private static Adaptation record(final AgentFramework.Run pRun, final ObjectNode pArguments, final AdaptationTable pTable)
	{
		try
		{
			return pTable == null ? null : pTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pArguments.path("record").asText("")));
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	@Override
	public boolean permitted(final AgentFramework.Run pRun, final SessionPermissions pPermissions, final String pAction, final ObjectNode pArguments)
	{
		final AdaptationTable table = QualityFixAgent.table(pRun, pArguments.path("zone").asText(""), pArguments.path("entity").asText(""));
		final Adaptation record = QualityFixAgent.record(pRun, pArguments, table);
		final String field = QualityFixAgent.fieldOf(pArguments.path("field").asText(null));
		if (record == null || field == null || !pArguments.has("value"))
		{
			return false;
		}
		final SchemaNode node = table.getTableOccurrenceRootNode().getNode(Path.parse("./" + field));
		return node != null && node.isTerminalValue() && pPermissions.getNodeAccessPermission(node, record).isReadWrite();
	}

	@Override
	public String apply(final AgentFramework.Run pRun, final Session pSession, final String pAction, final ObjectNode pArguments) throws OperationException
	{
		final AdaptationTable table = QualityFixAgent.table(pRun, pArguments.path("zone").asText(""), pArguments.path("entity").asText(""));
		final Adaptation record = QualityFixAgent.record(pRun, pArguments, table);
		final String field = QualityFixAgent.fieldOf(pArguments.path("field").asText(null));
		if (record == null || field == null)
		{
			throw EngineRefusal.coded("missing.agentRecord", "The record " + pArguments.path("record").asText("") + " no longer exists.", pArguments.path("record").asText(""));
		}
		final SchemaNode node = table.getTableOccurrenceRootNode().getNode(Path.parse("./" + field));
		final String text = pArguments.path("value").isNull() ? null : pArguments.path("value").asText(null);
		final Object value;
		try
		{
			value = text == null || text.isEmpty() ? null : node.parseXsString(text);
		}
		catch (final Exception ex)
		{
			throw EngineRefusal.coded("invalid.agentValue", "'" + text + "' is not a valid value for " + field + ": " + ex.getMessage(), field);
		}
		final String before = QualityFixAgent.text(node, record);
		final String info = "agent " + pRun.agentCode + " for " + Actor.current(pSession).userId;
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, record.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setExecutionInformation(info);
				final ValueContextForUpdate update = pContext.getContext(record.getAdaptationName());
				update.setValue(value, Path.parse("./" + field));
				pContext.doModifyContent(record, update);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("conflict.agentCorrection", "EBX refused the correction of " + field + ": " + result.getExceptionFullMessage(Locale.ENGLISH), field);
		}
		return field + ": " + (before == null ? "(empty)" : before) + " -> " + (text == null ? "(empty)" : text);
	}
}
