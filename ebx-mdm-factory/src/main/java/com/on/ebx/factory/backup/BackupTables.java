package com.on.ebx.factory.backup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Which Factory tables a project backup holds, in which order, and how the rows of ONE project are found in each (audit F-30, wave 10). Pure: the
 * engine ({@code ebx.ProjectBackup}) reads the schema for the fields; this class only decides tables and scope. The order is the order of creation
 * at restore: a parent before its children, so a foreign key finds its target already written (the few forward references - an entity listing its
 * member entities, a feed its parent feed - are written in a second step).
 * <p>
 * Not in a backup, and said so in the archive ({@link #NOT_INCLUDED}): the instance-wide tables ({@code FactorySettings}, {@code AccessRight},
 * {@code ReferenceBinding}), the per-user {@code Favorite}, the transient {@code LoadPause} and the queued or running {@code FactoryJob} rows, and
 * everything the Factory generated in EBX (data models and publications, datasets, Connectors mappings, matching configurations).
 */
public final class BackupTables
{
	/** How the rows of the project are found in a table. */
	public enum Scope
	{
		/** The project's own row, key {@code code}. */
		SELF,
		/** A foreign key to {@code Project} named {@link Spec#scopeField}. */
		PROJECT,
		/** A foreign key to a table of this list ({@link Spec#parent}) named {@link Spec#scopeField}: the rows whose parent was exported. */
		PARENT
	}

	/** One table of the backup. */
	public static final class Spec
	{
		public final String name;
		public final Scope scope;
		/** Field name (no path) of the foreign key that scopes the rows; empty for {@link Scope#SELF}. */
		public final String scopeField;
		/** For {@link Scope#PARENT}: the table whose exported keys the rows must point to; empty otherwise. */
		public final String parent;
		/** True when a later table scopes itself through this one: the engine keeps the exported keys. */
		public final boolean keepKeys;
		/** An XPath predicate added to the scope (empty = none), for {@link Scope#PROJECT} tables only. */
		public final String extraPredicate;

		Spec(final String pName, final Scope pScope, final String pScopeField, final String pParent, final boolean pKeepKeys, final String pExtraPredicate)
		{
			this.name = pName;
			this.scope = pScope;
			this.scopeField = pScopeField;
			this.parent = pParent;
			this.keepKeys = pKeepKeys;
			this.extraPredicate = pExtraPredicate;
		}

		/** The model path of the table. */
		public String path()
		{
			return "/root/" + this.name;
		}
	}

	private static final List<Spec> ALL = new ArrayList<Spec>();
	private static final Map<String, Spec> BY_NAME = new LinkedHashMap<String, Spec>();

	private static void add(final Spec pSpec)
	{
		BackupTables.ALL.add(pSpec);
		BackupTables.BY_NAME.put(pSpec.name, pSpec);
	}

	private static void self(final String pName)
	{
		BackupTables.add(new Spec(pName, Scope.SELF, "", "", true, ""));
	}

	private static void project(final String pName, final boolean pKeep)
	{
		BackupTables.add(new Spec(pName, Scope.PROJECT, "project", "", pKeep, ""));
	}

	private static void parent(final String pName, final String pField, final String pParent, final boolean pKeep)
	{
		BackupTables.add(new Spec(pName, Scope.PARENT, pField, pParent, pKeep, ""));
	}

	static
	{
		BackupTables.self("Project");
		BackupTables.project("ProjectSource", true);
		BackupTables.parent("LandingTable", "projectSource", "ProjectSource", true);
		BackupTables.parent("LandingColumn", "landingTable", "LandingTable", false);
		BackupTables.parent("SourceDrift", "landingTable", "LandingTable", false);
		BackupTables.parent("LandingRelation", "projectSource", "ProjectSource", false);
		BackupTables.project("StagingEntity", true);
		BackupTables.parent("StagingAttribute", "entity", "StagingEntity", true);
		BackupTables.parent("TransferFeed", "entity", "StagingEntity", false);
		BackupTables.parent("AttributeMapping", "attribute", "StagingAttribute", true);
		BackupTables.parent("MappingStep", "mapping", "AttributeMapping", false);
		BackupTables.parent("QualityRule", "entity", "StagingEntity", false);
		BackupTables.project("CapabilitySuggestion", false);
		BackupTables.parent("MatchPlan", "entity", "StagingEntity", false);
		BackupTables.project("PipelineStep", false);
		// the queue's history only: a QUEUED or RUNNING job restored would run again
		BackupTables.add(new Spec("FactoryJob", Scope.PROJECT, "project", "", false, "not(./status='QUEUED' or ./status='RUNNING')"));
		BackupTables.project("AgentRun", true);
		BackupTables.parent("AgentStep", "run", "AgentRun", false);
		// agentic framework lot (2026-10-30): the proposals of the runs. AgentDefinition is not here (see NOT_INCLUDED): its key is a code shared by the whole
		// instance, a restore would collide with an agent of the same code, and an enabled definition would run again by itself (re-declare the agents on the target)
		BackupTables.parent("AgentProposal", "run", "AgentRun", false);
		BackupTables.project("DistributionTarget", false);
		BackupTables.project("DistributionEvent", false);
		BackupTables.project("IntakeChain", false);
		BackupTables.project("Xref", false);
		BackupTables.project("TransferReject", false);
		// lot C1-B: the CENTRALISATION path - the chosen packs and the decided differences. DomainObject is not here: it points to PipelineStep rows a restore
		// renumbers, and what the generation wrote lives in ON EBX (see NOT_INCLUDED)
		BackupTables.project("ProjectPack", false);
		BackupTables.project("Difference", false);
	}

	private BackupTables()
	{
	}

	/** Every table of the backup, parents before children. */
	public static List<Spec> all()
	{
		return Collections.unmodifiableList(BackupTables.ALL);
	}

	/** The table of that name, null when a backup never holds it. */
	public static Spec byName(final String pName)
	{
		return pName == null ? null : BackupTables.BY_NAME.get(pName);
	}

	/**
	 * Text fields that hold an id of ANOTHER backed-up table without being a foreign key: renumbered with the table they name at restore.
	 * Key {@code Table.field}, value the table whose ids they hold.
	 */
	public static Map<String, String> stringReferences()
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		out.put("FactoryJob.stepId", "PipelineStep");
		return out;
	}

	/** What a backup never holds, with the reason - copied into every archive so the person reading it knows. */
	public static final List<String> NOT_INCLUDED = Collections.unmodifiableList(java.util.Arrays.asList(
		"FactorySettings, AccessRight and ReferenceBinding: instance-wide settings, not part of a project.",
		"Favorite: the per-user shortcuts of the project explorer.",
		"LoadPause and the QUEUED or RUNNING rows of FactoryJob: transient state of the running server; restoring them would resume work nobody asked for.",
		"Every data model, publication, dataset, dataspace, Connectors mapping, Match and Merge configuration, workflow, script and toolbar the Factory generated in ON EBX "
			+ "(see 'references'): they are rebuilt with the wizard from the restored project, or restored with the delivery archive of EBX Staging.",
		"The data in the landing, staging and master datasets - with one exception, which the person reading the archive must know: AgentProposal (arguments, rationale, applyMessage, target) "
			+ "and AgentRun.inputs can hold values copied from master records. They are in the archive only when the governed agents are open on the instance that wrote it "
			+ "(-Debx.factory.agents.enabled=true); when the agents are closed (the default) those fields are left out and the archive says so under 'valuesOmitted'.",
		"DomainObject: the list of what the generation of a CENTRALISATION domain wrote in ON EBX, rebuilt by running the generation again.",
		"The agent account and the password of the settings.",
		"AgentDefinition: the agents of the project are not restored, on purpose (audit F3-10, 2026-11-12). Its key is a code shared by the whole instance, so a restore would collide "
			+ "with an agent of the same code, and a restored definition that was enabled would run again by itself, under an owner profile nobody chose on the "
			+ "target. Declare the agents again on the restored project; the runs and proposals of the archive are kept as history (their agent code is written as it is, with a "
			+ "warning when the definition is missing)."));

	/**
	 * 1.4.2 (F3-06, gap 11): the fields that can carry VALUES copied from master records, as {@code Table.field}. {@link #valueOmitted} leaves them out of a backup while the
	 * governed agents are closed - a closed function must not put master data in a file.
	 */
	public static final List<String> MASTER_VALUE_FIELDS = Collections.unmodifiableList(java.util.Arrays.asList("AgentProposal.arguments", "AgentProposal.rationale",
		"AgentProposal.applyMessage", "AgentProposal.target", "AgentRun.inputs"));

	/** Pure: whether {@code pTable.pField} is left out of the archive because it may hold master values and the governed agents are closed. */
	public static boolean valueOmitted(final String pTable, final String pField, final boolean pAgentsOpen)
	{
		return !pAgentsOpen && BackupTables.MASTER_VALUE_FIELDS.contains(pTable + "." + pField);
	}

	/** Fields a backup never writes, whatever the table (a secret is not exported even if one is added to a table later). */
	public static boolean neverExported(final String pFieldName)
	{
		final String lower = pFieldName == null ? "" : pFieldName.toLowerCase(java.util.Locale.ROOT);
		return lower.contains("password") || lower.contains("secret") || lower.contains("apikey") || lower.endsWith("token");
	}
}
