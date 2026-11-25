package com.on.ebx.factory.backup;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.core.FactoryPaths;

/** JUnit-free: run the main, a failure throws. Audit F-30 (wave 10): the format, the table list, the value codec, the renumbering and what a restore never brings back. */
public final class ProjectBackupLogicTest
{
	public static void main(final String[] pArgs) throws IOException
	{
		ProjectBackupLogicTest.format();
		ProjectBackupLogicTest.tables();
		ProjectBackupLogicTest.coverage();
		ProjectBackupLogicTest.codec();
		ProjectBackupLogicTest.remap();
		ProjectBackupLogicTest.adjust();
		ProjectBackupLogicTest.references();
		ProjectBackupLogicTest.sources();
		System.out.println("ProjectBackupLogicTest OK");
	}

	private static void format()
	{
		ProjectBackupLogicTest.check("the format is accepted", BackupFormat.refuse(BackupFormat.FORMAT, Integer.valueOf(1)) == null);
		ProjectBackupLogicTest.check("another format is refused", BackupFormat.refuse("something", Integer.valueOf(1)) != null);
		ProjectBackupLogicTest.check("a missing version is refused", BackupFormat.refuse(BackupFormat.FORMAT, null) != null);
		ProjectBackupLogicTest.check("a newer version is refused with the upgrade advice", BackupFormat.refuse(BackupFormat.FORMAT, Integer.valueOf(BackupFormat.VERSION + 1)).contains("upgrade"));
		ProjectBackupLogicTest.check("version 0 is refused", BackupFormat.refuse(BackupFormat.FORMAT, Integer.valueOf(0)) != null);
		ProjectBackupLogicTest.check("project codes", BackupFormat.validProjectCode("Companies_2") && !BackupFormat.validProjectCode("a b") && !BackupFormat.validProjectCode("")
			&& !BackupFormat.validProjectCode(null) && !BackupFormat.validProjectCode("1abc") && !BackupFormat.validProjectCode("a'; drop"));
		ProjectBackupLogicTest.check("file name", BackupFormat.fileName("A b/c", new Date(0L)).matches("project-backup-A_b_c-\\d{8}-\\d{6}\\.json"));
	}

	private static void tables()
	{
		final List<BackupTables.Spec> all = BackupTables.all();
		final Set<String> seen = new HashSet<String>();
		for (final BackupTables.Spec spec : all)
		{
			ProjectBackupLogicTest.check("unique " + spec.name, seen.add(spec.name));
			if (spec.scope == BackupTables.Scope.PARENT)
			{
				ProjectBackupLogicTest.check(spec.name + " comes after its parent " + spec.parent, seen.contains(spec.parent));
				ProjectBackupLogicTest.check("the parent " + spec.parent + " keeps its keys", BackupTables.byName(spec.parent).keepKeys);
				ProjectBackupLogicTest.check(spec.name + " names its scope field", !spec.scopeField.isEmpty());
			}
			if (spec.scope == BackupTables.Scope.PROJECT)
			{
				ProjectBackupLogicTest.check(spec.name + " is scoped by the project field", "project".equals(spec.scopeField));
			}
		}
		ProjectBackupLogicTest.check("the project comes first", "Project".equals(all.get(0).name) && all.get(0).scope == BackupTables.Scope.SELF);
		ProjectBackupLogicTest.check("a queued or running job is never backed up", BackupTables.byName("FactoryJob").extraPredicate.contains("QUEUED")
			&& BackupTables.byName("FactoryJob").extraPredicate.contains("RUNNING"));
		ProjectBackupLogicTest.check("unknown table", BackupTables.byName("Nope") == null && BackupTables.byName(null) == null);
		ProjectBackupLogicTest.check("secrets are never exported", BackupTables.neverExported("agentPassword") && BackupTables.neverExported("apiKey") && BackupTables.neverExported("clientSecret")
			&& BackupTables.neverExported("accessToken") && !BackupTables.neverExported("sourceSystem"));
		ProjectBackupLogicTest.check("the archive says what it leaves out", BackupTables.NOT_INCLUDED.size() >= 5);
		// audit F3-10: the agent definitions are left out ON PURPOSE and the archive says why; the proposals and the drifts travel with their parents
		boolean definitionsSaid = false;
		for (final String line : BackupTables.NOT_INCLUDED)
		{
			definitionsSaid |= line.startsWith("AgentDefinition") && line.contains("run again by itself");
		}
		ProjectBackupLogicTest.check("the archive says why AgentDefinition is left out", definitionsSaid);
		ProjectBackupLogicTest.check("AgentDefinition is not backed up (an enabled agent must not restart by itself)", BackupTables.byName("AgentDefinition") == null);
		ProjectBackupLogicTest.check("AgentProposal follows its run and SourceDrift its landing table", "AgentRun".equals(BackupTables.byName("AgentProposal").parent)
			&& "LandingTable".equals(BackupTables.byName("SourceDrift").parent));
	}

	/** A table added to the XSD later must be decided: in a backup, or on the list of what a backup never holds. */
	private static void coverage()
	{
		final Set<String> decidedOut = new HashSet<String>(Arrays.asList("FactorySettings", "ReferenceBinding", "Favorite", "LoadPause", "AccessRight", "DomainObject",
			"AgentDefinition"));
		final Set<String> inBackup = new HashSet<String>();
		for (final BackupTables.Spec spec : BackupTables.all())
		{
			inBackup.add(spec.name);
		}
		for (final com.orchestranetworks.schema.Path table : FactoryPaths.TABLES)
		{
			final String name = table.getLastStep().format();
			ProjectBackupLogicTest.check("table " + name + " is in the backup or decided out of it", inBackup.contains(name) ^ decidedOut.contains(name));
		}
		for (final String name : inBackup)
		{
			boolean known = false;
			for (final com.orchestranetworks.schema.Path table : FactoryPaths.TABLES)
			{
				known |= table.getLastStep().format().equals(name);
			}
			ProjectBackupLogicTest.check("backed-up table " + name + " exists", known);
		}
	}

	private static void codec() throws IOException
	{
		final ObjectMapper mapper = new ObjectMapper();
		final JsonNode node = mapper.readTree("{\"s\":\"text\",\"b\":true,\"i\":\"42\",\"n\":42,\"d\":\"12345678901234567890.12\",\"t\":\"2026-10-07T09:05:03.123\",\"nul\":null,\"l\":[\"a\",\"b\"]}");
		ProjectBackupLogicTest.check("string", "text".equals(ValueCodec.fromJson(node.get("s"), String.class)));
		ProjectBackupLogicTest.check("boolean", Boolean.TRUE.equals(ValueCodec.fromJson(node.get("b"), Boolean.class)));
		ProjectBackupLogicTest.check("integer from text", Integer.valueOf(42).equals(ValueCodec.fromJson(node.get("i"), Integer.class)));
		ProjectBackupLogicTest.check("integer from a number", Integer.valueOf(42).equals(ValueCodec.fromJson(node.get("n"), Integer.class)));
		ProjectBackupLogicTest.check("a decimal keeps every digit", new BigDecimal("12345678901234567890.12").equals(ValueCodec.fromJson(node.get("d"), BigDecimal.class)));
		final Object date = ValueCodec.fromJson(node.get("t"), Date.class);
		ProjectBackupLogicTest.check("date round trip to the millisecond", date instanceof Date && "2026-10-07T09:05:03.123".equals(ValueCodec.toJson(date)));
		ProjectBackupLogicTest.check("null", ValueCodec.fromJson(node.get("nul"), String.class) == null && ValueCodec.fromJson(node.get("absent"), String.class) == null);
		ProjectBackupLogicTest.check("list", ValueCodec.listFromJson(node.get("l"), String.class).equals(Arrays.<Object> asList("a", "b")));
		ProjectBackupLogicTest.check("a missing list is empty", ValueCodec.listFromJson(null, String.class).isEmpty());
		ProjectBackupLogicTest.check("a decimal is written as plain text", "100000000000".equals(ValueCodec.toJson(new BigDecimal("1E+11"))));
		ProjectBackupLogicTest.check("a boolean stays a boolean", Boolean.TRUE.equals(ValueCodec.toJson(Boolean.TRUE)));
		boolean refused = false;
		try
		{
			ValueCodec.fromJson(mapper.readTree("\"x1\""), Integer.class);
		}
		catch (final IllegalArgumentException ex)
		{
			refused = ex.getMessage().contains("x1") && ex.getMessage().contains("Integer");
		}
		ProjectBackupLogicTest.check("a value of the wrong type is refused with its text", refused);
		refused = false;
		try
		{
			ValueCodec.fromJson(mapper.readTree("\"yesterday\""), Date.class);
		}
		catch (final IllegalArgumentException ex)
		{
			refused = true;
		}
		ProjectBackupLogicTest.check("a bad date is refused", refused);
	}

	private static void remap()
	{
		final IdRemap remap = new IdRemap();
		remap.put("StagingEntity", "5", "105");
		remap.put("Xref", "k", "k2");
		ProjectBackupLogicTest.check("translates", "105".equals(remap.get("StagingEntity", "5")));
		ProjectBackupLogicTest.check("an unknown key is null", remap.get("StagingEntity", "6") == null && remap.get("Nope", "1") == null);
		ProjectBackupLogicTest.check("volume-scale tables are not tracked", !IdRemap.tracked("Xref") && !IdRemap.tracked("TransferReject") && !IdRemap.tracked("DistributionEvent")
			&& IdRemap.tracked("StagingEntity") && remap.get("Xref", "k") == null);
		remap.defer("StagingEntity", "105", "memberEntities", "StagingEntity", Arrays.asList("7", "8"), true);
		ProjectBackupLogicTest.check("deferred", remap.deferred().size() == 1 && remap.deferred().get(0).multi && remap.deferred().get(0).oldTargets.size() == 2);
		ProjectBackupLogicTest.check("size counts the tracked keys", remap.size() == 1);
	}

	private static void adjust()
	{
		final List<String> notes = new ArrayList<String>();
		final Map<String, Object> project = new LinkedHashMap<String, Object>();
		project.put("intakeState", "ON");
		project.put("intakeEnabledBy", "admin");
		RestoreAdjust.apply("Project", project, notes);
		ProjectBackupLogicTest.check("intake off, nobody switched it on", "OFF".equals(project.get("intakeState")) && project.get("intakeEnabledBy") == null && notes.size() == 1);
		final Map<String, Object> target = new LinkedHashMap<String, Object>();
		target.put("state", "ARMED");
		target.put("armedViaOverride", Boolean.TRUE);
		target.put("scheduleKey", "EBX|x");
		RestoreAdjust.apply("DistributionTarget", target, notes);
		ProjectBackupLogicTest.check("target disabled, not armed by override, no schedule", "DISABLED".equals(target.get("state")) && Boolean.FALSE.equals(target.get("armedViaOverride"))
			&& target.get("scheduleKey") == null);
		final Map<String, Object> draft = new LinkedHashMap<String, Object>();
		draft.put("state", "DRAFT");
		RestoreAdjust.apply("DistributionTarget", draft, new ArrayList<String>());
		ProjectBackupLogicTest.check("a draft stays a draft", "DRAFT".equals(draft.get("state")));
		final Map<String, Object> step = new LinkedHashMap<String, Object>();
		step.put("status", "RUNNING");
		RestoreAdjust.apply("PipelineStep", step, notes);
		ProjectBackupLogicTest.check("a running step is interrupted", "INTERRUPTED".equals(step.get("status")));
		final Map<String, Object> done = new LinkedHashMap<String, Object>();
		done.put("status", "DONE");
		RestoreAdjust.apply("PipelineStep", done, notes);
		ProjectBackupLogicTest.check("a finished step is untouched", "DONE".equals(done.get("status")));
		final Map<String, Object> chain = new LinkedHashMap<String, Object>();
		chain.put("status", "RUNNING");
		RestoreAdjust.apply("IntakeChain", chain, notes);
		ProjectBackupLogicTest.check("a running chain fails", "FAILED".equals(chain.get("status")) && chain.get("message") != null);
		final Map<String, Object> other = new LinkedHashMap<String, Object>();
		other.put("status", "RUNNING");
		RestoreAdjust.apply("Xref", other, notes);
		ProjectBackupLogicTest.check("another table is untouched", "RUNNING".equals(other.get("status")));
		final int before = notes.size();
		RestoreAdjust.apply("PipelineStep", new LinkedHashMap<String, Object>(java.util.Collections.singletonMap("status", (Object) "RUNNING")), notes);
		ProjectBackupLogicTest.check("a note is written once", notes.size() == before);
	}

	private static void references()
	{
		final ReferenceCollector collector = new ReferenceCollector();
		final Map<String, Object> project = new LinkedHashMap<String, Object>();
		project.put("stagingDataspace", "Companies_Staging");
		project.put("stagingDataset", "Staging");
		project.put("masterDataspace", "");
		collector.offer("Project", project);
		final Map<String, Object> source = new LinkedHashMap<String, Object>();
		source.put("sourceSystem", "ERP");
		source.put("landingModel", "Companies_ERP");
		source.put("landingDataset", "ERP");
		collector.offer("ProjectSource", source);
		final Map<String, Object> table = new LinkedHashMap<String, Object>();
		table.put("inboundMapping", "195");
		table.put("tableName", "PARTNER");
		collector.offer("LandingTable", table);
		collector.offer("LandingTable", table);
		collector.offerModels("Companies_Staging_model", "Companies_Master_model");
		final List<ReferenceCollector.Reference> refs = collector.references();
		final Set<String> names = new HashSet<String>();
		for (final ReferenceCollector.Reference ref : refs)
		{
			names.add(ref.kind + ":" + ref.name);
		}
		ProjectBackupLogicTest.check("dataspace, model, dataset, mapping and the two computed models: " + names, names.contains("staging dataspace:Companies_Staging")
			&& names.contains("data model:Companies_ERP") && names.contains("dataset:ERP") && names.contains("Connectors mapping:195") && names.contains("data model:Companies_Master_model"));
		ProjectBackupLogicTest.check("an empty name is not a reference and a repeated one is listed once", !names.contains("master dataspace:") && refs.size() == 6);
	}

	/** The engine's source: the route and the engine ask the administrator, the archive carries a completion marker, a refused restore never rolls back, defaults are safe. */
	private static void sources() throws IOException
	{
		final Path root = Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java"));
		final String backup = new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/ProjectBackup.java")), StandardCharsets.UTF_8);
		final String restore = new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/ebx/ProjectRestore.java")), StandardCharsets.UTF_8);
		final String resource = new String(Files.readAllBytes(root.resolve("com/on/ebx/factory/rest/FactoryResource.java")), StandardCharsets.UTF_8);
		ProjectBackupLogicTest.check("the backup asks the administrator", backup.contains("FactoryAccess.require(pSession, FactoryRight.ADMINISTRATOR"));
		ProjectBackupLogicTest.check("the restore asks the administrator", restore.contains("FactoryAccess.require(pSession, FactoryRight.ADMINISTRATOR"));
		ProjectBackupLogicTest.check("the archive ends with its completion marker", backup.contains("g.writeBooleanField(\"complete\", true)"));
		ProjectBackupLogicTest.check("the restore refuses an archive without it", restore.contains("ends without its 'complete' marker"));
		ProjectBackupLogicTest.check("a rollback only follows a write", restore.contains("if (run.writing)") && restore.contains("pRun.writing = true;"));
		ProjectBackupLogicTest.check("the restore refuses a project that exists", restore.contains("already exists on this instance"));
		ProjectBackupLogicTest.check("the restore is a dry run unless asked", resource.contains("@QueryParam(\"dryRun\") @DefaultValue(\"true\")"));
		ProjectBackupLogicTest.check("the backup names the project it exports in a step", backup.contains("\"PROJECT_BACKUP\"") && restore.contains("\"PROJECT_RESTORE\""));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
