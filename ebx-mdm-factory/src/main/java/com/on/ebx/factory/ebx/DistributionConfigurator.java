package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.agent.McpClient;
import com.on.ebx.agent.McpException;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.distribution.DistributionMappingRules;
import com.on.ebx.factory.distribution.DistributionMappingRules.Rule;
import com.on.ebx.factory.distribution.TableDestinationDefaults;
import com.on.ebx.factory.distribution.TargetMappingList;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.master.MasterModel;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "Target" / "What goes out"): creates or updates one
 * Connectors OUTBOUND mapping per master table of a project, for one {@code DistributionTarget}. Same
 * calling convention as {@link LandingFlows#upsertFlow} (a dry run first, then for real, both through
 * {@code connectors_mapping_upsert}, via {@link McpServers#connectors} so the caller's own session is used
 * when the in-JVM bridge is registered - CLAUDE.md sec 9) - copied deliberately rather than sharing a helper,
 * since the two mapping shapes (an INBOUND landing flow vs. an OUTBOUND distribution feed) differ in every
 * field that matters.
 * <p>
 * The mapping's own {@code inputs}: {@code mergedRecords=exclude} always (golden records only - the spec's
 * own "What goes out": "MERGED never sent as a record of its own"), {@code crosswalk} following the
 * target's {@code includeCrosswalk} flag. Master tables/fields come from {@code ProjectMasterEntities.readEntities}
 * + {@code MasterModel.buildFromStaging} directly - the same computation {@code DataProductPublication} already
 * used correctly, and the one {@code master.MasterGenerator} itself promotes into the master model.
 * <p>
 * <b>Live bug fix (2026-09-25, DistribTest project)</b>: this used to read {@code StagingEntity.targetTable}
 * directly - a field {@code master.MasterModel}'s own Javadoc says {@code MasterGenerator} "never reads or
 * writes" for a Greenfield project, so it is always {@code null} and "Configure" refused with "has no promoted
 * master table yet" even though the master genuinely held 180 records. Fixed by switching to that same shared
 * computation, rather than the stale {@code targetTable} field.
 * <p>
 * <b>Round 3 fix (2026-09-26, DistribTest, mapping 300 orphaned)</b>: {@code connectors_mapping_upsert}
 * identifies a mapping by (connector, operation, direction, dataset, tablePath, label) together - moving a
 * target to a different connector/operation therefore makes the next Configure create a BRAND NEW mapping
 * and leave the old one dangling, still {@code active}, on the old connector (measured live: target 1 moved
 * from connector "Kafka" to "KafkaMdmDistribTest", Configure created mapping 302 and left mapping 300
 * behind). {@link #deleteIfConnectorOrOperationChanged} now checks each table's existing mapping id before
 * re-upserting it and deletes it first when its connector/operation no longer matches the target's current
 * ones.
 * <p>
 * <b>Live BLOCKER fix (2026-09-25, DistribTest, mapping 300)</b>: the mapping this class creates used to carry
 * no {@code rules[]} at all - {@code connectors_mapping_get} read back {@code inputs {mergedRecords: exclude},
 * rules [], children []}, so every send would have produced an empty JSON document (Connectors'
 * {@code MappingEngine.buildOutbound} builds the outbound document rule by rule). {@link #upsertMapping} now
 * builds one rule per business field via {@link DistributionMappingRules#build} - see that class' own Javadoc
 * for exactly what is kept/excluded and how a re-Configure preserves a hand-added rule.
 * <p>
 * <b>Not verified live</b>: no EBX instance was reachable while writing this class (agent-account /
 * in-JVM-bridge behaviour was read from {@link McpServers}/{@link ConnectorsBridge}'s own Javadoc and from
 * {@link LandingFlows}'s working call sites, not exercised against a running Connectors module here). See
 * {@code docs/DISTRIBUTION.md} for the exact steps to verify after deployment.
 */
public final class DistributionConfigurator
{
	private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER = new com.fasterxml.jackson.databind.ObjectMapper();
	private static final String STEP = "DISTRIBUTION_CONFIGURE";

	private DistributionConfigurator()
	{
	}

	/** Creates/updates the OUTBOUND mapping of every master table of the project for {@code pTargetId}, and writes {@code DistributionTarget.connectorsMappingIds} back. */
	public static ObjectNode configure(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pTargetId) throws OperationException
	{
		final Adaptation project = DistributionConfigurator.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			throw EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " does not exist", pProjectCode);
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		final Adaptation target = DistributionConfigurator.findTarget(pFactory, pTargetId);
		if (target == null || !projectId.equals(target.getString(FactoryPaths.DistributionTarget.PROJECT)))
		{
			throw EngineRefusal.coded("invalid.targetNotOfProject", "Distribution target " + pTargetId + " does not belong to project " + pProjectCode, pTargetId);
		}
		final String masterDataspace = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDataset = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (masterDataspace == null || masterDataspace.trim().isEmpty() || masterDataset == null || masterDataset.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noMasterZone", "Project " + pProjectCode + " has no master (MDM) zone yet: build and load it before configuring a distribution target", pProjectCode);
		}
		// "retire notices from the merge log" (SPEC-retire.md): TOMBSTONE is Kafka-only and the Connectors lot
		// that would let the Kafka connector actually send one (a null-value publish with headers) has not
		// shipped - refuse here rather than silently downgrading to NOTICE or letting it sit unconfigurable.
		// Named after the connector's own type so the message is actionable even when the target's connector
		// happens not to be Kafka at all.
		if (FactoryPaths.DistributionTarget.RETIRE_MODE_TOMBSTONE.equals(target.getString(FactoryPaths.DistributionTarget.RETIRE_MODE)))
		{
			final String connectorType = DistributionConfigurator.describeConnectorType(pSession, pFactory, target.getString(FactoryPaths.DistributionTarget.CONNECTOR));
			throw EngineRefusal.coded("invalid.retireMode", "Distribution target " + pTargetId + " has retireMode=TOMBSTONE, but the '" + connectorType
				+ "' connector does not support it yet (Kafka tombstone/headers support has not shipped in the Connectors module) - use NOTICE or NONE instead.", pTargetId, connectorType);
		}
		// live BLOCKER fix (2026-09-25, DistribTest, mapping 300): needs the master model's own FIELD names now
		// (DistributionMappingRules.build), not just table paths - reads entities/MasterModel directly, the same
		// way ebx.DataProductPublication already does, rather than going through the table-paths-only
		// ProjectMasterEntities.promotedMasterTablePaths this class used before this fix.
		final ProjectMasterEntities.Result entities = ProjectMasterEntities.readEntities(pFactory, projectId);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities.entities);
		if (masterModel.builtEntities.isEmpty())
		{
			throw EngineRefusal.coded("missing.noMasterTable", "Project " + pProjectCode + " has no promoted master table yet", pProjectCode);
		}
		final Map<String, List<String>> businessFieldsByEntity = new LinkedHashMap<String, List<String>>();
		for (final MasterModel.FieldInfo field : masterModel.fields)
		{
			DistributionConfigurator.fieldsOf(businessFieldsByEntity, field.entityName).add(field.masterField);
		}
		for (final MasterModel.ForeignKeyInfo fk : masterModel.foreignKeys)
		{
			DistributionConfigurator.fieldsOf(businessFieldsByEntity, fk.entityName).add(fk.fieldName);
		}

		final String connector = target.getString(FactoryPaths.DistributionTarget.CONNECTOR);
		final String operation = target.getString(FactoryPaths.DistributionTarget.OPERATION);
		final boolean crosswalk = Boolean.TRUE.equals(target.get(FactoryPaths.DistributionTarget.INCLUDE_CROSSWALK));
		// re-configure (Configure clicked again): every table already carries a mapping id from a previous run -
		// read so upsertMapping can preserve a rule a person added by hand directly on that mapping (see its own
		// Javadoc and DistributionMappingRules#merge).
		final Map<String, String> existingMappingIds = TargetMappingList.parse(target.getString(FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS));
		// round 6 (task brief item C, Personnes live incident): a value already stored here (typed by hand in
		// the wizard's 7b "Table -> topic" row, or proposed by an earlier Configure) is a starting point that is
		// never silently overwritten - see TableDestinationDefaults' own Javadoc.
		final Map<String, String> existingTableDestinations = TargetMappingList.parse(target.getString(FactoryPaths.DistributionTarget.TABLE_DESTINATIONS));

		final String stepId = PipelineSteps.begin(pSession, pFactory, projectId, DistributionConfigurator.STEP, pTargetId);
		final ObjectNode detail = DistributionConfigurator.MAPPER.createObjectNode();
		final ArrayNode tableDetails = detail.putArray("tables");
		final Map<String, String> mappingByTable = new LinkedHashMap<String, String>();
		final Map<String, String> destinationByTable = new LinkedHashMap<String, String>();

		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			detail.put("transport", McpServers.connectorsTransport(pSession));
			// wave 11 (audit F-26): the mappings are written as flows through the Connectors contract when it is there (ebx.factory.connectors.via)
			final boolean contract = ConnectorsContractClient.useContract();
			detail.put("flowVia", contract ? "contract" : "tool");
			// round 6 (task brief item C): only a connector whose write operation reads a PER-CALL destination
			// (Kafka's "topic"/"key" mapping inputs, read-only reference KafkaConnector#publish) gets one - a
			// connector-level property alone (what DistributionConnections already surfaces for the 7b read-only
			// "Sends to" hint) mixes every master table onto the same destination, which is exactly the live bug
			// this pass fixes (Personnes: /root/Personne and /root/Adresse both landed on mdm.personnes.person).
			final ConnectorDestinationInfo connectorInfo = DistributionConfigurator.describeConnectorForDestinations(client, connector);
			detail.put("perTableDestinations", connectorInfo.supportsPerTableDestination);
			for (final String entityName : masterModel.builtEntities)
			{
				final String tablePath = MasterModel.tablePath(entityName);
				final ObjectNode tableDetail = tableDetails.addObject();
				tableDetail.put("tablePath", tablePath);
				final List<String> businessFields = businessFieldsByEntity.containsKey(entityName) ? businessFieldsByEntity.get(entityName)
					: java.util.Collections.<String>emptyList();
				// live bug fix (2026-09-26, DistribTest): connectors_mapping_upsert identifies a mapping by
				// (connector, operation, direction, dataset, tablePath, label) TOGETHER - this class' label is
				// keyed by targetId+tablePath only, so moving a target to a different connector/operation makes
				// the next upsert create a BRAND NEW mapping under the new identity and leave the old one
				// orphaned (measured live: target 1 moved from connector "Kafka" to "KafkaMdmDistribTest",
				// Configure created mapping 302 and left mapping 300 dangling, still active, on the old
				// connector). Delete the stale mapping first when its connector/operation no longer matches.
				// Wave 11: through the contract the flow of this label is updated in place even when its connector or operation changed ("the same key updates
				// the same mapping"), so the stale mapping is deleted only when the label does not designate it (a mapping an earlier version labelled otherwise).
				final String previousMappingId = existingMappingIds.get(tablePath);
				final String existingMappingId = contract && DistributionConfigurator.designatedByLabel(pSession, previousMappingId, pTargetId, tablePath)
					? previousMappingId
					: DistributionConfigurator.deleteIfConnectorOrOperationChanged(client, previousMappingId, connector, operation, tableDetail);
				String destination = null;
				if (connectorInfo.supportsPerTableDestination)
				{
					destination = existingTableDestinations.get(tablePath);
					if (destination == null || destination.trim().isEmpty())
					{
						destination = TableDestinationDefaults.proposeDestination(connectorInfo.topicProperty, pProjectCode, tablePath);
					}
					destinationByTable.put(tablePath, destination);
					tableDetail.put("destination", destination);
				}
				final String mappingId = DistributionConfigurator.upsertMapping(pProjectCode, contract ? pSession : null, client, connector, operation, masterDataspace, masterDataset, tablePath,
					pTargetId, crosswalk, businessFields, existingMappingId, destination, tableDetail);
				mappingByTable.put(tablePath, mappingId);
			}
			final String formatted = TargetMappingList.format(mappingByTable);
			final String formattedDestinations = connectorInfo.supportsPerTableDestination ? TargetMappingList.format(destinationByTable) : null;
			DistributionConfigurator.writeMappingIds(pSession, pFactory, pTargetId, formatted, formattedDestinations);
			PipelineSteps.end(pSession, pFactory, stepId, true, "Configured " + mappingByTable.size() + " outbound mapping(s)", detail.toString());
			final ObjectNode out = DistributionConfigurator.MAPPER.createObjectNode();
			out.put("status", "DONE");
			out.put("connectorsMappingIds", formatted);
			if (formattedDestinations != null)
			{
				out.put("tableDestinations", formattedDestinations);
			}
			out.set("detail", detail);
			return out;
		}
		catch (final McpException ex)
		{
			PipelineSteps.end(pSession, pFactory, stepId, false, ErrorText.of(ex), detail.toString());
			throw EngineRefusal.coded("external.configureTarget", "Could not configure distribution target " + pTargetId + ": " + ex.getMessage(), pTargetId);
		}
		catch (final OperationException | RuntimeException | Error ex)
		{
			// an unexpected failure must not leave the step RUNNING: ended FAILED with the reason, the stack logged, the exception still thrown (StepGuard)
			StepGuard.endUnexpected(pSession, pFactory, stepId, ex, detail.toString());
			throw ex;
		}
	}

	private static List<String> fieldsOf(final Map<String, List<String>> pMap, final String pEntityName)
	{
		List<String> list = pMap.get(pEntityName);
		if (list == null)
		{
			list = new ArrayList<String>();
			pMap.put(pEntityName, list);
		}
		return list;
	}

	/** {@code describeConnectorForDestinations}'s answer, reduced to what the per-table-destination logic needs. */
	private static final class ConnectorDestinationInfo
	{
		final boolean supportsPerTableDestination;
		final String topicProperty;

		ConnectorDestinationInfo(final boolean pSupportsPerTableDestination, final String pTopicProperty)
		{
			this.supportsPerTableDestination = pSupportsPerTableDestination;
			this.topicProperty = pTopicProperty;
		}
	}

	/**
	 * Coordinator review fix (2026-09-28, RC2, measured live): a mapping's {@code inputs} are TEMPLATES resolved
	 * PER RECORD by Connectors' own {@code Expressions.resolve} (`ConnectorService` ~line 515, `core/Expressions.java`
	 * in `ebx-connectors`) - only {@code ${...}} placeholders are evaluated, anything else is passed through as a
	 * literal string. The bare field name {@code "id"} that used to sit here was therefore never resolved at all:
	 * every Kafka message on RC2 measured with the LITERAL key {@code id} on every message (console consumer:
	 * {@code id<TAB>{"id":692,...}}), not the record's own id value. The correct input is the TEMPLATE
	 * {@code ${id}} - every table this module builds carries exactly one primary-key field, {@code id}
	 * ({@code master.MasterModel}'s own table-building code always declares {@code primaryKey = ["id"]}, never a
	 * composite key, for a table `master.MasterGenerator` promotes - CLAUDE.md sec 9), so this is used as-is
	 * rather than a live {@code describe_table} call this class has no other reason to make. A future master
	 * table with a genuinely composite key would need this joined as {@code ${field1}|${field2}} instead (the
	 * same {@code |}-separator {@code com.onwbp.adaptation.PrimaryKey} itself uses, CLAUDE.md sec 9) - not needed
	 * today since no table this module generates has one.
	 * <p>
	 * Package-private (not private): {@code ebx.DistributionDispatcher#sendRetireNotice} must NOT reuse this
	 * constant as-is - a direct {@code connectors_call} has no record for Connectors to resolve a template
	 * against, so that call site passes the record's own key VALUE literally instead (see its own Javadoc).
	 */
	static final String DEFAULT_KEY_TEMPLATE = "${id}";

	/**
	 * Round 6 (2026-09-26, task brief item C): whether {@code pConnector}'s write operation accepts a PER-CALL
	 * destination (Kafka's own {@code topic}/{@code key} mapping inputs - {@code KafkaConnector#publish}, read
	 * only, never touched by this module) rather than always sending to one connector-level property. Kept
	 * deliberately narrow to the one connector TYPE actually confirmed to support it ({@code kafka}) - the task
	 * brief explicitly asks for "generic enough for a future JMS queue" but "do not build JMS": a string
	 * allow-list of one entry is exactly that, with nothing JMS-specific baked in, so widening it later is a
	 * one-line change here, not a redesign.
	 * <p>
	 * Reuses {@code connectors_describe(connector)} (no {@code operation} argument) - the SAME call {@link
	 * DistributionConnections#describeOperations} already makes for the 7b "Sends to" read-only hint - and reads
	 * the same {@code properties.topic} field that class already surfaces, so a connector-level topic already
	 * configured becomes the shared prefix {@link TableDestinationDefaults#proposeDestination} builds every
	 * table's own default from. A describe failure degrades to "no per-table destination" (mirrors the mapping
	 * still being created with whatever the connector's own defaults are, exactly as before this round).
	 */
	private static ConnectorDestinationInfo describeConnectorForDestinations(final McpClient pClient, final String pConnector)
	{
		try
		{
			final ObjectNode args = DistributionConfigurator.MAPPER.createObjectNode();
			args.put("connector", pConnector);
			final McpClient.ToolResult result = pClient.callTool("connectors_describe", args);
			if (result.error || result.json == null)
			{
				return new ConnectorDestinationInfo(false, null);
			}
			final String type = result.json.path("type").asText("");
			final boolean supportsPerTableDestination = "kafka".equalsIgnoreCase(type);
			final String topicProperty = result.json.path("properties").path("topic").asText(null);
			return new ConnectorDestinationInfo(supportsPerTableDestination, topicProperty);
		}
		catch (final McpException ex)
		{
			return new ConnectorDestinationInfo(false, null);
		}
	}

	/**
	 * "retire notices from the merge log" (SPEC-retire.md): a standalone {@code connectors_describe} just for
	 * the connector's {@code type}, used only by the TOMBSTONE refusal above (a throwaway client - {@link
	 * #configure}'s own client, created moments later for the real work, is not reused here so that refusal can
	 * happen before any of that heavier work runs). Falls back to {@code "unknown"} on any failure - the
	 * refusal message is still accurate (TOMBSTONE is refused either way), just less specific about why.
	 */
	private static String describeConnectorType(final Session pSession, final Adaptation pFactory, final String pConnector)
	{
		try
		{
			final McpClient client = McpServers.connectors(pSession, pFactory);
			final ObjectNode args = DistributionConfigurator.MAPPER.createObjectNode();
			args.put("connector", pConnector);
			final McpClient.ToolResult result = client.callTool("connectors_describe", args);
			if (result.error || result.json == null)
			{
				return "unknown";
			}
			final String type = result.json.path("type").asText("");
			return type.isEmpty() ? "unknown" : type;
		}
		catch (final McpException ex)
		{
			return "unknown";
		}
		catch (final OperationException ex)
		{
			return "unknown";
		}
	}

	/**
	 * Creates/updates the OUTBOUND mapping of one master table - now WITH its {@code rules[]}
	 * ({@link DistributionMappingRules#build}), the live BLOCKER this pass fixes (class Javadoc): before this,
	 * {@code rules} was never set at all, so {@code connectors_mapping_upsert} defaulted it to {@code []} and
	 * every send would have produced an empty JSON document (Connectors' {@code MappingEngine.buildOutbound}
	 * builds the outbound document rule by rule).
	 * <p>
	 * <b>Re-configure / hand-added rules</b>: when {@code pExistingMappingId} is not null (a previous Configure
	 * already ran), reads the mapping's CURRENT rules with {@code connectors_mapping_get} first and keeps any
	 * rule this class does not itself generate ({@link DistributionMappingRules#merge} - "cheap to detect":
	 * this class owns a fixed, known set of target field names, so anything else was put there by hand). A read
	 * failure (the mapping was deleted directly on Connectors, or the call itself fails) is NOT treated as fatal
	 * - it falls back to the generated rules alone, i.e. a hand-added rule would be overwritten in that one
	 * edge case, same as if this preservation step did not exist at all.
	 * <p>
	 * <b>Not verified live</b>: {@code connectors_mapping_get}'s exact response shape for {@code rules[]} was
	 * read from the live incident report (a raw {@code rules} array of {@code {sourcePath,targetPath,
	 * transformation,policy}} objects, alongside {@code inputs}/{@code children}), never called by this class
	 * before this fix - {@link #readExistingRules} parses defensively and returns an empty list on any surprise.
	 * <p>
	 * <b>Round 6 (task brief item C)</b>: when {@code pDestination} is not null, the mapping's inputs also carry
	 * {@code topic}=that value and {@code key}={@link #DEFAULT_KEY_TEMPLATE} - the per-table destination Kafka's
	 * {@code publish} operation reads in preference to its own connector-level {@code topic}/{@code key.field}
	 * properties (read-only reference, {@code KafkaConnector#publish}). {@code null} (every non-per-table-
	 * destination connector) leaves the mapping's inputs exactly as before this round.
	 * <p>
	 * <b>Coordinator review fix (2026-09-28)</b>: {@code key} is a TEMPLATE resolved per record by Connectors,
	 * not a bare field/JSON-path name - see {@link #DEFAULT_KEY_TEMPLATE}'s own Javadoc for the live measurement
	 * that caught every message on RC2 carrying the literal key {@code id}. A mapping written by an earlier
	 * version of this class (bare {@code "id"}) is silently corrected the next time Configure runs, since
	 * {@code connectors_mapping_upsert} always re-sends the whole {@code inputs} object.
	 */
	private static String upsertMapping(final String pProjectCode, final Session pContractSession, final McpClient pClient, final String pConnector, final String pOperation, final String pMasterDataspace,
		final String pMasterDataset, final String pTablePath, final String pTargetId, final boolean pCrosswalk, final List<String> pBusinessFields,
		final String pExistingMappingId, final String pDestination, final ObjectNode pTableDetail) throws McpException, OperationException
	{
		// 2026-10-12: the document names the master DATASPACE as well as the dataset - see mappingDocument's Javadoc (a blank one is refused there)
		final List<Rule> generatedRules = DistributionMappingRules.build(pBusinessFields, pCrosswalk, pTablePath, pMasterDataspace, pMasterDataset);
		List<Rule> rules = generatedRules;
		if (pExistingMappingId != null && !pExistingMappingId.trim().isEmpty())
		{
			final List<Rule> existingRules = DistributionConfigurator.readExistingRules(pContractSession, pClient, pExistingMappingId, DistributionConfigurator.label(pTargetId, pTablePath));
			rules = DistributionMappingRules.merge(generatedRules, existingRules);
			pTableDetail.put("handAddedRulesKept", rules.size() - generatedRules.size());
		}
		final ObjectNode mapping = DistributionConfigurator.mappingDocument(pProjectCode, pConnector, pOperation, pMasterDataspace, pMasterDataset, pTablePath,
			DistributionConfigurator.label(pTargetId, pTablePath), pCrosswalk, pDestination, rules);
		pTableDetail.put("ruleCount", rules.size());

		if (pContractSession != null)
		{
			final String viaContract = DistributionConfigurator.upsertThroughContract(pContractSession, mapping, pTablePath, pTableDetail);
			if (viaContract != null)
			{
				pTableDetail.put("mapping", viaContract);
				return viaContract;
			}
		}
		final ObjectNode dryArgs = DistributionConfigurator.MAPPER.createObjectNode();
		dryArgs.set("mapping", mapping);
		dryArgs.put("dryRun", true);
		final McpClient.ToolResult dry = pClient.callTool("connectors_mapping_upsert", dryArgs);
		pTableDetail.put("dryRunOk", !dry.error);
		if (dry.error)
		{
			throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert (dry run) refused the mapping of " + pTablePath + ": " + DistributionConfigurator.errorText(dry), pTablePath);
		}

		final ObjectNode realArgs = DistributionConfigurator.MAPPER.createObjectNode();
		realArgs.set("mapping", mapping);
		realArgs.put("dryRun", false);
		final McpClient.ToolResult real = pClient.callTool("connectors_mapping_upsert", realArgs);
		pTableDetail.put("upsertOk", !real.error);
		if (real.error)
		{
			throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert could not write the mapping of " + pTablePath + ": " + DistributionConfigurator.errorText(real), pTablePath);
		}
		final String mappingId = real.json == null ? null : real.json.path("mapping").asText(null);
		if (mappingId == null || mappingId.trim().isEmpty())
		{
			throw EngineRefusal.coded("external.writeMapping", "connectors_mapping_upsert did not return a mapping id for " + pTablePath, pTablePath);
		}
		pTableDetail.put("mapping", mappingId);
		return mappingId;
	}

	/**
	 * Live bug fix (2026-09-26, DistribTest, mapping 300 orphaned): reads {@code pExistingMappingId}'s CURRENT
	 * {@code connector}/{@code operation} (same top-level-then-nested-under-"mapping" defensive read as
	 * {@link #readExistingRules}) and, when either no longer matches the target's current values, deletes it
	 * with {@code connectors_mapping_delete} and returns {@code null} so the caller treats this table as
	 * having no existing mapping (a fresh one is then created under the new connector/operation identity,
	 * exactly as {@code connectors_mapping_upsert} would have done anyway - the only difference is the stale
	 * mapping under the OLD connector no longer lingers, active, alongside the new one). Best-effort: a read
	 * failure is NOT treated as "changed" - it falls back to passing {@code pExistingMappingId} through
	 * unchanged, same as if this check did not exist (never blocks Configure over a transient read error).
	 * <p>
	 * <b>Not verified live</b>: {@code connectors_mapping_get}'s top-level {@code connector}/{@code operation}
	 * field names were not independently confirmed - inferred from the same response shape
	 * {@code readExistingRules} already reads {@code rules}/{@code inputs}/{@code children} from.
	 */
	private static String deleteIfConnectorOrOperationChanged(final McpClient pClient, final String pExistingMappingId, final String pConnector,
		final String pOperation, final ObjectNode pTableDetail)
	{
		if (pExistingMappingId == null || pExistingMappingId.trim().isEmpty())
		{
			return pExistingMappingId;
		}
		try
		{
			final ObjectNode args = DistributionConfigurator.MAPPER.createObjectNode();
			args.put("mapping", pExistingMappingId);
			final McpClient.ToolResult result = pClient.callTool("connectors_mapping_get", args);
			if (result.error || result.json == null)
			{
				return pExistingMappingId;
			}
			JsonNode root = result.json;
			if (!root.has("connector") && root.has("mapping"))
			{
				root = root.path("mapping");
			}
			final String existingConnector = root.path("connector").asText(null);
			final String existingOperation = root.path("operation").asText(null);
			final boolean changed = existingConnector != null && !existingConnector.equals(pConnector)
				|| existingOperation != null && !existingOperation.equals(pOperation);
			if (!changed)
			{
				return pExistingMappingId;
			}
			final ObjectNode deleteArgs = DistributionConfigurator.MAPPER.createObjectNode();
			deleteArgs.put("mapping", pExistingMappingId);
			final McpClient.ToolResult delete = pClient.callTool("connectors_mapping_delete", deleteArgs);
			pTableDetail.put("staleMappingDeleted", pExistingMappingId);
			pTableDetail.put("staleMappingDeleteOk", !delete.error);
			return null;
		}
		catch (final McpException ex)
		{
			pTableDetail.put("staleMappingCheckError", ex.getMessage());
			return pExistingMappingId;
		}
	}

	/** The label of a table's outbound mapping - its flow key through the Connectors contract; also how an earlier Configure's mapping is found again. */
	static String label(final String pTargetId, final String pTablePath)
	{
		return "Distribution: target " + pTargetId + " " + pTablePath;
	}

	/** Wave 11: whether the flow of this table's label IS the mapping {@code pMappingId} (so the contract updates it in place); false when there is none, or the contract cannot say. */
	static boolean designatedByLabel(final Session pSession, final String pMappingId, final String pTargetId, final String pTablePath)
	{
		if (pMappingId == null || pMappingId.trim().isEmpty())
		{
			return false;
		}
		try
		{
			return pMappingId.trim().equals(ContractFlows.idOf(pSession, DistributionConfigurator.label(pTargetId, pTablePath)));
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			return false;
		}
	}

	/**
	 * The OUTBOUND {@code mapping} document of one master table, exactly as it is handed to {@code flows.put} / {@code connectors_mapping_upsert}.
	 * <p>
	 * <b>It always names the master DATASPACE</b> ({@code dataspace}), next to the {@code dataset}. Measured live 2026-10-12 (FT 6.3 RC, mapping 415
	 * "Distribution: target 3 /root/Personne"): the document carried {@code dataset=Master} and no dataspace; the contract stores a flow as it is,
	 * and at send time Connectors resolved the dataset NAME by walking the whole repository and took the first dataspace holding a dataset
	 * "Master" with that table - {@code Personnes_Master}, an orphan of a deleted project - instead of {@code Vol100k_Master}: 300 rows of the wrong
	 * project went to Kafka. Before wave 11 the omission was masked by the Connectors installer's boot-time repair, which guessed the same way.
	 * A dataset name alone is never unique across projects ({@code Master} is every project's), so a blank {@code pMasterDataspace} is refused here
	 * rather than written.
	 */
	static ObjectNode mappingDocument(final String pProjectCode, final String pConnector, final String pOperation, final String pMasterDataspace, final String pMasterDataset,
		final String pTablePath, final String pLabel, final boolean pCrosswalk, final String pDestination, final List<Rule> pRules) throws OperationException
	{
		if (pMasterDataspace == null || pMasterDataspace.trim().isEmpty())
		{
			throw EngineRefusal.coded("missing.noMasterDataspace", "Project " + pProjectCode + " has no master dataspace yet", pProjectCode);
		}
		final ObjectNode mapping = DistributionConfigurator.MAPPER.createObjectNode();
		mapping.put("connector", pConnector);
		mapping.put("operation", pOperation);
		mapping.put("direction", "OUTBOUND");
		mapping.put("dataspace", pMasterDataspace.trim());
		mapping.put("dataset", pMasterDataset);
		mapping.put("tablePath", pTablePath);
		mapping.put("label", pLabel);
		final ObjectNode inputs = mapping.putObject("inputs");
		// golden records only (SPEC-distribution.md "What goes out"): a MERGED/DELETED record is excluded from
		// every scope=all/changed/unsent send by ProductPublisher.publishes (ebx-connectors, read-only reference).
		inputs.put("mergedRecords", "exclude");
		if (pCrosswalk)
		{
			inputs.put("crosswalk", "true");
		}
		if (pDestination != null && !pDestination.trim().isEmpty())
		{
			// round 6 (task brief item C): per-table destination (Kafka's own publish inputs - see upsertMapping's own Javadoc).
			// Coordinator review fix (2026-09-28): "key" is a TEMPLATE resolved per record by Connectors' own Expressions.resolve - a bare field
			// name is a literal, never resolved (measured live: every Kafka message on RC2 carried the literal key "id") - see DEFAULT_KEY_TEMPLATE's Javadoc.
			inputs.put("topic", pDestination.trim());
			inputs.put("key", DistributionConfigurator.DEFAULT_KEY_TEMPLATE);
		}
		final ArrayNode rulesNode = mapping.putArray("rules");
		for (final Rule rule : pRules)
		{
			final ObjectNode ruleNode = rulesNode.addObject();
			ruleNode.put("sourcePath", rule.sourcePath);
			ruleNode.put("targetPath", rule.targetPath);
			ruleNode.put("transformation", rule.transformation);
			ruleNode.put("policy", rule.policy);
		}
		return mapping;
	}

	/**
	 * Wave 11 (audit F-26): the mapping written by the contract's {@code flows.put} (a dry run, then the real write), the answer being its mapping id; null
	 * when the contract is not to be used any more for this write ({@code via=auto} and it went away or was switched off since the check) - the caller
	 * then goes by the tool. A refusal of the flow itself is the same coded {@code external.writeMapping} sentence as the tool's.
	 */
	static String upsertThroughContract(final Session pSession, final ObjectNode pMapping, final String pTablePath, final ObjectNode pTableDetail) throws OperationException
	{
		try
		{
			final JsonNode dry;
			try
			{
				dry = ContractFlows.put(pSession, pMapping, true);
			}
			catch (final ConnectorsContractClient.Refusal ex)
			{
				if (ConnectorsContractClient.mayFallBack(ex) || !ConnectorsContractClient.OPERATION_FAILED.equals(ex.type))
				{
					throw ex;
				}
				pTableDetail.put("dryRunOk", false);
				throw EngineRefusal.coded("external.writeMapping", "flows.put (dry run) refused the mapping of " + pTablePath + ": " + ex.getMessage(), pTablePath);
			}
			pTableDetail.put("dryRunOk", dry != null);
			final JsonNode real = ContractFlows.put(pSession, pMapping, false);
			pTableDetail.put("upsertOk", true);
			final String mappingId = real.hasNonNull("mappingId") ? real.path("mappingId").asText() : null;
			if (mappingId == null || mappingId.trim().isEmpty())
			{
				throw EngineRefusal.coded("external.writeMapping", "flows.put did not return a mapping id for " + pTablePath, pTablePath);
			}
			return mappingId;
		}
		catch (final ConnectorsContractClient.Refusal ex)
		{
			if (ConnectorsContractClient.mayFallBack(ex))
			{
				pTableDetail.put("flowVia", "tool: " + ex.type);
				return null;
			}
			if (ConnectorsContractClient.OPERATION_FAILED.equals(ex.type))
			{
				pTableDetail.put("upsertOk", false);
				throw EngineRefusal.coded("external.writeMapping", "flows.put could not write the mapping of " + pTablePath + ": " + ex.getMessage(), pTablePath);
			}
			throw ConnectorsContractClient.engineRefusal(ex, "flows.put", DistributionConfigurator.label("", pTablePath));
		}
	}

	/** {@code connectors_mapping_get}'s {@code rules[]}, parsed into {@link Rule} - best-effort: a call failure or an unexpected shape answers an empty list (see {@link #upsertMapping}'s own Javadoc on why that is never fatal). */
	static List<Rule> readExistingRules(final Session pContractSession, final McpClient pClient, final String pMappingId, final String pKey)
	{
		final List<Rule> out = new ArrayList<Rule>();
		if (pContractSession != null)
		{
			// wave 11: the flow of this label through the contract (flows.get answers the document flows.put accepts); a mapping an earlier version labelled
			// otherwise is not found that way and is read by id below, as before
			try
			{
				final JsonNode flow = ContractFlows.flow(pContractSession, pKey);
				if (flow != null)
				{
					for (final JsonNode ruleNode : flow.path("rules"))
					{
						final String targetPath = ruleNode.path("targetPath").asText(null);
						if (targetPath != null)
						{
							out.add(new Rule(ruleNode.path("sourcePath").asText(""), targetPath, ruleNode.path("transformation").asText(""), ruleNode.path("policy").asText("ALWAYS")));
						}
					}
					return out;
				}
			}
			catch (final ConnectorsContractClient.Refusal ex)
			{
				// best-effort, as the read by id below
			}
		}
		try
		{
			final ObjectNode args = DistributionConfigurator.MAPPER.createObjectNode();
			args.put("mapping", pMappingId);
			final McpClient.ToolResult result = pClient.callTool("connectors_mapping_get", args);
			if (result.error || result.json == null)
			{
				return out;
			}
			// the live incident's own read showed rules directly at the top level ("inputs {...}, rules [],
			// children []") - also tried under a nested "mapping" object, in case a future/different build wraps
			// the answer the way connectors_mapping_upsert's own reply wraps its "mapping" id.
			JsonNode rulesNode = result.json.path("rules");
			if (!rulesNode.isArray())
			{
				rulesNode = result.json.path("mapping").path("rules");
			}
			for (final JsonNode ruleNode : rulesNode)
			{
				final String sourcePath = ruleNode.path("sourcePath").asText(null);
				final String targetPath = ruleNode.path("targetPath").asText(null);
				if (targetPath == null)
				{
					continue;
				}
				out.add(new Rule(sourcePath == null ? "" : sourcePath, targetPath, ruleNode.path("transformation").asText(""),
					ruleNode.path("policy").asText("ALWAYS")));
			}
		}
		catch (final McpException ex)
		{
			// best-effort - see upsertMapping's own Javadoc
		}
		return out;
	}

	/** {@code pFormattedDestinations} is null for a connector with no per-table destination (round 6, item C) - {@code tableDestinations} is then simply left untouched, same as before this round. */
	private static void writeMappingIds(final Session pSession, final Adaptation pFactory, final String pTargetId, final String pFormatted, final String pFormattedDestinations)
		throws OperationException
	{
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new com.orchestranetworks.service.Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				DistributionConfigurator.writeTarget(pContext, pFactory, pTargetId, pFormatted, pFormattedDestinations);
			}
		});
		if (result.hasFailed())
		{
			throw EngineRefusal.coded("unexpected.saveMappingIds", "Distribution target " + pTargetId + " was configured on Connectors, but writing connectorsMappingIds back failed: " + result.getExceptionFullMessage(java.util.Locale.ENGLISH), pTargetId);
		}
	}

	private static void writeTarget(final ProcedureContext pContext, final Adaptation pFactory, final String pTargetId, final String pFormatted,
		final String pFormattedDestinations) throws Exception
	{
		final Adaptation row = pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
		if (row == null)
		{
			return;
		}
		final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
		value.setValue(pFormatted, FactoryPaths.DistributionTarget.CONNECTORS_MAPPING_IDS);
		if (pFormattedDestinations != null)
		{
			value.setValue(pFormattedDestinations, FactoryPaths.DistributionTarget.TABLE_DESTINATIONS);
		}
		pContext.doModifyContent(row, value);
	}

	static Adaptation findTarget(final Adaptation pFactory, final String pTargetId)
	{
		if (pTargetId == null)
		{
			return null;
		}
		return pFactory.getTable(FactoryPaths.DISTRIBUTION_TARGET).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pTargetId));
	}

	static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	static String errorText(final McpClient.ToolResult pResult)
	{
		if (pResult.json != null && pResult.json.has("error"))
		{
			return pResult.json.path("error").asText();
		}
		return pResult.text == null ? "(no message)" : pResult.text;
	}
}
