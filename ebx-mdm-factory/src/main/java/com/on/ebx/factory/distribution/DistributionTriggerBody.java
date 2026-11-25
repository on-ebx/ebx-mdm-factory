package com.on.ebx.factory.distribution;

import java.util.List;

import com.on.ebx.factory.core.FactoryPaths;

/**
 * Distribution lot 1 (engine, SPEC-distribution.md "Real time"): builds the EBX-Script source of one master
 * table's {@code afterCreate}/{@code afterModify}/{@code afterDelete} trigger - authored by
 * {@code ebx.DistributionTriggerInstaller} through {@code update_script}. Pure string templating, no EBX
 * class, so the generated text is unit-tested directly (content only - it is not run through the real EBX
 * Script compiler here, see this class' own "not verified" note).
 * <p>
 * The body never calls a target directly (SPEC-distribution.md: "The script does NOT call the target: it
 * writes one row into an outbox table"): it reads the project's own REALTIME targets from the Factory's
 * dataset (a {@code references model "MdmFactory" from module "ebx-mdm-factory"} packaged reference - the
 * short form is valid because that module packages exactly one XSD by that name, {@code FactoryPaths.MODEL_PATH}),
 * keeps only the ones {@code ARMED} ({@code distribution.DistributionArmingGate#mayQueueRealtimeEvent}), and
 * writes one outbox row per matching target.
 * <p>
 * <b>Round 3 redesign (2026-09-26, DistribTest, PipelineStep 395), replacing the round-2 body entirely:</b>
 * live compilation of the round-2 body on {@code data-model/DistribTest_Master/root/Sample/@trigger/DistributionAfterCreate}
 * answered three real compile errors, all confirmed by hand on that exact script (see {@code ebx.DistributionOutboxInstaller}'s
 * own Javadoc and {@code docs/DISTRIBUTION.md} for the full transcript):
 * <ol>
 * <li>{@code Cannot apply '=' to arguments of type FKTYPE(/root/Project ...) = FKTYPE(...)} on
 * {@code t.project = ?} - {@code DistributionTarget.project} is a foreign key, not a scalar (CLAUDE.md sec 2 "A
 * foreign-key column in SQL is a record"). Fixed: compare through the target's own primary key,
 * {@code t.project.code = ?} ({@code Project}'s primary key is {@code code} - {@code tools/build_xsd.py}).</li>
 * <li>{@code This type of script cannot create transactions} on {@code execute transaction ... on factoryDs} -
 * a TABLE TRIGGER script (unlike a REST service or workflow script) cannot open a transaction of its own AT ALL,
 * so it can never write into a dataspace other than {@code _ebx.dataspace} (its own ambient
 * {@code _ebx.transaction} is the only transaction it ever gets, and that transaction is scoped to
 * {@code _ebx.dataspace}). Writing into {@code MdmFactory} (a different dataspace than any project's master)
 * from a table trigger is therefore categorically impossible, not merely awkward - the round-1/2 design and
 * its "separate transaction, different dataspace" analysis were both wrong on this point.</li>
 * <li>{@code Expression of type 'string' cannot be assigned to variable of type '...primarykeyof factory.Project'
 * / '...factory.DistributionTarget'} - a stray attempt (round 2) to assign a plain string into an FK-typed
 * field; moot once the outbox record carries no FK fields at all (below).</li>
 * </ol>
 * <b>Fix: the outbox now lives IN the master dataspace</b>, as a second dataset next to {@code Master}
 * ({@link FactoryPaths#DISTRIBUTION_OUTBOX_DATASET}, model {@link FactoryPaths#DISTRIBUTION_OUTBOX_MODEL_PATH} -
 * see that model's own header comment). The trigger reaches it with
 * {@code db.findDataset(_ebx.dataspace, 'DistributionOutbox')} and writes through its own ambient
 * {@code _ebx.transaction} directly - no {@code execute transaction} block anywhere in this body any more.
 * Reading {@code MdmFactory.DistributionTarget} (a DIFFERENT dataspace) is still fine - that is a plain
 * {@code DATABASE_READ}, not a transaction, and every table-trigger type has that capability
 * (get_syntax_guide(triggerAfterCreate): "REMOTE_REQUESTS, DATABASE_READ, DATABASE_WRITE, IMPURE,
 * SCRIPT_LIBRARY_ACCESS"). Only the WRITE side needed to move. The outbox row therefore carries
 * {@code target} as a plain STRING (the matching {@code DistributionTarget.id}, formatted) rather than a
 * foreign key - a foreign key to a table of a different dataspace cannot be expressed at all, and
 * {@code ebx.DistributionDispatcher} resolves it back to the real row when it drains the outbox.
 * <p>
 * <b>Live verification actually performed (2026-09-26), and what it does and does not prove:</b> the exact
 * corrected shape below - {@code t.project.code = ?}, no {@code execute transaction}, a
 * {@code db.findDataset(_ebx.dataspace, name)} lookup followed by {@code db.recordOf(_ebx.transaction, ...)}
 * and {@code db.saveRecord} - was saved and compiled clean via {@code update_script}/its embedded validation
 * on the real {@code DistribTest_Master/root/Sample/@trigger/DistributionAfterCreate} script, PROBING the
 * mechanic against the dataspace's existing {@code Master} dataset/{@code Sample} table (the only dataset
 * {@code DistribTest_Master} has - there is no second dataset there yet to prove genuine cross-DATASET reach
 * within the dataspace). A separate attempt referencing the real
 * {@code references model "DistributionOutbox" from module "ebx-mdm-factory"} was refused at compile time
 * with {@code Packaged model 'DistributionOutbox' not found in module 'ebx-mdm-factory'} - expected: that XSD
 * is new in this pass and the module has not been rebuilt/redeployed (a Tomcat restart), so the reference
 * cannot resolve yet. What this DOES establish: the "no execute transaction, {@code _ebx.transaction} writes
 * directly through a name-looked-up dataset handle" mechanic is accepted by the compiler for a table
 * trigger. What it does NOT establish: that the SAME mechanic succeeds at RUNTIME when the looked-up dataset
 * is a genuinely different one than {@code _ebx.dataset} (untested - no second dataset existed to target,
 * and creating one plus firing the trigger by creating a Sample record were both out of scope for this pass'
 * single-script write permission). See {@code docs/DISTRIBUTION.md} for the exact steps to close this out
 * once the module is redeployed: install the triggers on a real project, create one record with an ARMED
 * REALTIME target, and confirm a row appears in that project's {@code DistributionOutbox} dataset.
 * <p>
 * <b>Lot F3 (2026-10-19): the body never references a Match and Merge linked field.</b> The retire-by-comparison block of rounds 2-3
 * (it read {@code Linked_Field_State} and {@code GoldenId} in {@code afterModify}) is gone. It never fired for a real merge or unmerge
 * (measured 2026-09-27: those only touch the add-on's metadataset, so {@code afterModify} does not even run for the merged record), and it
 * had a cost: the add-on's linked fields exist only while the table has a matching configuration, so the moment that configuration was deleted
 * (project deletion, a step reset, the orphan sweep, an administrator in the add-on's screen) the symbols vanished, the WHOLE master model
 * stopped compiling ({@code Trigger script 'DistributionAfterModify' has errors: Cannot resolve symbol 'Linked_Field_State'}, measured
 * 2026-10-18 22:17 on 6.3.0-rc3), every table of the model answered "Table not found" to the add-on and the other configurations stayed as
 * orphans. The trigger's only job is to enqueue {table, record key, CREATE/MODIFY/DELETE}. Whether the record is live (GOLDEN) or merged/deleted
 * is decided at dispatch time in Java by {@code ebx.DistributionDispatcher} (it reads the state with a tolerant Java lookup that answers "unknown"
 * when the field is absent), and the RETIRE notices come from {@code distribution.MergeLogWatch} and {@code distribution.MatchingStateSweep}
 * (lots S2/S8), which read the metadataset. {@code survivorKey} is therefore always written empty here. A project whose triggers were installed by
 * the old generator keeps the old text until {@code ebx.DistributionTriggerInstaller} reinstalls it (it now does so when the stored source differs).
 * <p>
 * <b>Volume-operations lot 8b (2026-09-28, task 1): a Control-row short-circuit, ahead of the SQL fallback
 * above.</b> The task brief's own evidence: 5 million SQL queries against {@code factory."DistributionTarget"}
 * during a 5M-row master load, even while every target of the project was {@code PAUSED} - one query PER
 * SAVED ROW, unconditionally (lot 8a's {@code distribution.ArmedTargetCache} cannot help here: it is a
 * per-JVM Java cache, and a table trigger has no sanctioned way to call an arbitrary custom Java static method
 * from EBX Script, CLAUDE.md sec 2 - see that class' own Javadoc for the two options considered and rejected
 * for THIS lot: a Java {@code osd:trigger} instead of a script one, or a denormalized flag on {@code Project}
 * itself). The cheap thing a SCRIPT trigger CAN do is a plain primary-key lookup (no SQL at all) - so the body
 * below now reads a {@code Control} row of the SAME per-project {@code DistributionOutbox} dataset the write
 * further down already needs (one dataset lookup, reused for both), keyed by the project id, holding a single
 * {@code armedRealtimeTargets} count maintained by Java ({@code ebx.DistributionControlRow}, wired into the
 * exact write paths lot 8a already wired to {@code ArmedTargetCache}: {@code DistributionPause},
 * {@code DistributionRunner#writeTargetState}, {@code DistributionReader#setState}/{@code #upsertTarget}), and
 * returns at once when the row is missing or the count is {@code 0}.
 * <p>
 * <b>Why a missing/zero row may be trusted as "nothing armed" - fail-open would be wrong here</b> (the task
 * brief's own words: a missing row must mean "not armed" only if Java always writes it before a target
 * becomes armed). This is the invariant {@code ebx.DistributionControlRow} maintains, not something this
 * generated script can enforce on its own: (1) every write path that can newly ARM a REALTIME target
 * (as decided by {@code distribution.ArmedRealtimeChange#directionOf}) writes the row's new, already-bumped
 * count BEFORE the target's own {@code state} field commits to {@code ARMED} - see that class' own Javadoc
 * for why that ordering, not the reverse, is the one that never lets a real event slip through the gap
 * between the two non-transactional writes; (2) the row is also recomputed from scratch for every project at
 * module startup and the first time a project's triggers are installed ({@code
 * DistributionTriggerInstaller#install}, right after {@code DistributionOutboxInstaller.ensure}), so an
 * upgrade from a build that predates this lot - or a target armed before its project ever had triggers
 * installed - self-heals without needing every target to be re-armed by hand. A row that is missing for any
 * OTHER reason (the outbox dataset does not exist yet at all) is exactly the "triggers were never installed on
 * this table" case the pre-existing {@code outboxDataset} null-check just below already handled the same way.
 * <p>
 * <b>Deployment: existing installed triggers only pick this up on reinstall/republish.</b> Like every other
 * change to this generated text, an ALREADY-INSTALLED project keeps running its OLD trigger body (the one
 * without this short-circuit) until {@code DistributionTriggerInstaller.install} runs again for it (its own
 * declare-then-author-then-publish sequence is idempotent - re-running it against an already-installed project
 * regenerates and republishes every trigger body unconditionally, {@code authorOne} always calls
 * {@code update_script} with a freshly built body, never skips authoring because the script already exists)
 * or the master model is otherwise republished. Deploying this lot alone (a Tomcat restart to package the new
 * {@code DistributionOutbox.xsd} {@code Control} table) does NOT retrofit a running project's triggers by
 * itself.
 * <p>
 * <b>Lot S3 (2026-10-18): no typed {@code sql} literal, ever.</b> The target lookup was a typed ``sql `...` `` literal; on 6.3.0-rc3 every
 * typed literal crashes the compiler of a data-model trigger script (IDE validate route answers HTTP 500, {@code update_script} answers
 * INVALID_DATA_MODEL "revision history ... could not be loaded"), whatever model it names. It is now {@code db.createQuery(text)} +
 * {@code db.addDataset} + {@code db.setParameter}, the column read with {@code db.field<int>(tuple, 'targetId')} (tuples of a dynamic query are untyped).
 * See {@code docs/DISTRIBUTION.md} "Round 9".
 * <p>
 * <b>Not validated by {@code validate_draft}</b>: that tool only accepts free-standing script types
 * (workflow/REST/roleMapper/taskScheduler/library); a table trigger is a data-model script type and needs
 * {@code validate_saved} against an actually-declared script, which needs write access this pass did not use
 * (task instructions restrict EBX access to read-only tools) - consistent with every earlier round of this
 * class, none of which were validated by {@code validate_draft} either. The new block was checked instead
 * against {@code get_syntax_guide(triggerAfterCreate)}'s own documented {@code core.data} surface
 * ({@code lookupRecord(table, primaryKey): recordType [DATABASE_READ]}, listed there) and against
 * {@code core.complex.primaryKeyOf}/{@code get_api_help}, and mirrors the exact typed-record field-access
 * idiom ({@code record.fieldName}, never {@code db.field<T>} - that is for SQL tuples only) already used
 * elsewhere in this same body ({@code occ.target := ...}).
 */
public final class DistributionTriggerBody
{
	public static final String AFTER_CREATE = "afterCreate";
	public static final String AFTER_MODIFY = "afterModify";
	public static final String AFTER_DELETE = "afterDelete";

	private DistributionTriggerBody()
	{
	}

	/**
	 * @param pType {@link #AFTER_CREATE}/{@link #AFTER_MODIFY}/{@link #AFTER_DELETE} (also {@code set_script_trigger}'s own {@code type} argument)
	 * @param pTableName the table's own XSD element name (last segment of {@code pTablePath}) - the type name used with {@code pMasterModelName}'s alias
	 * @param pMasterModelName the master model's DMA publication name (installer's {@code pModelName})
	 * @param pPrimaryKeyFields the table's real primary-key field names, in declaration order - read live by the installer, never assumed to be {@code ["id"]}
	 */
	public static String body(final String pType, final String pProjectId, final String pTablePath, final String pTableName, final String pMasterModelName,
		final List<String> pPrimaryKeyFields)
	{
		final String defaultChange = DistributionTriggerBody.AFTER_CREATE.equals(pType) ? "CREATE" : DistributionTriggerBody.AFTER_DELETE.equals(pType) ? "DELETE" : "MODIFY";
		final String entryPoint = "onAfter" + defaultChange.charAt(0) + defaultChange.substring(1).toLowerCase(java.util.Locale.ENGLISH);
		final StringBuilder sb = new StringBuilder();
		sb.append("uses core.data as db;\n");
		sb.append("uses core.datetime as dt;\n");
		sb.append("uses core.complex as cp;\n");
		sb.append("uses core.log as log;\n");
		sb.append("references model \"MdmFactory\" from module \"" + FactoryPaths.MODULE_NAME + "\" as factory;\n");
		sb.append("references model \"DistributionOutbox\" from module \"" + FactoryPaths.MODULE_NAME + "\" as outbox;\n");
		sb.append("references model \"" + DistributionTriggerBody.escape(pMasterModelName) + "\" as master;\n\n");
		sb.append("export procedure " + entryPoint + "()\n");
		sb.append("begin\n");
		sb.append(DistributionTriggerBody.recordKeyBlock(pTableName, pPrimaryKeyFields));
		sb.append("\tconst changeValue := '" + defaultChange + "';\n");
		sb.append("\tconst survivorValue := '';\n");
		// Round 3 fix, item 2: a table trigger cannot open `execute transaction` at all (measured live:
		// "This type of script cannot create transactions") - the outbox must be reached through
		// _ebx.transaction, which is scoped to _ebx.dataspace, so it has to live in the SAME dataspace as
		// the record being saved, as a second dataset next to Master (FactoryPaths.DISTRIBUTION_OUTBOX_DATASET).
		// Resolved HERE (before the SQL fallback below) because the volume-operations lot 8b short-circuit
		// right after it needs the SAME dataset for its own Control-row lookup - one db.findDataset call
		// covers both the read and, if it doesn't return early, the write further down.
		sb.append("\tconst outboxDataset := db.findDataset(_ebx.dataspace, '" + DistributionTriggerBody.escape(FactoryPaths.DISTRIBUTION_OUTBOX_DATASET) + "');\n");
		sb.append("\tif isNull(outboxDataset) then return;\n");
		// Volume-operations lot 8b (task 1): the short-circuit - a plain primary-key lookup, no SQL - see this
		// class' own Javadoc for the exact invariant that makes a missing row or a zero count safe to treat as
		// "nothing armed, return now" rather than a fail-open bug.
		sb.append("\tconst controlTable := db.findTable<outbox.Control>(outboxDataset);\n");
		sb.append("\tvar controlPk := cp.primaryKeyOf<outbox.Control>();\n");
		sb.append("\tcontrolPk.project := '" + DistributionTriggerBody.escape(pProjectId) + "';\n");
		sb.append("\tconst controlRow := db.lookupRecord(controlTable, controlPk);\n");
		sb.append("\tif isNull(controlRow) then return;\n");
		sb.append("\tif controlRow.armedRealtimeTargets = 0i then return;\n");
		// Round 3 fix, item 1: DistributionTarget.project is a foreign key (tools/build_xsd.py: fk='/root/Project') -
		// compare through the target's own primary key (Project's PK is 'code'), not the FK column itself
		// (CLAUDE.md sec 2 "A foreign-key column in SQL is a record"). Unchanged by lot 8b: this fallback still
		// runs whenever the short-circuit above did not return early (an armed target really might exist).
		sb.append("\tconst factoryDs := db.findDataspace('" + DistributionTriggerBody.escape(FactoryPaths.DATASPACE) + "');\n");
		sb.append("\tif isNull(factoryDs) then return;\n");
		sb.append("\tconst factoryDataset := db.findDataset(factoryDs, '" + DistributionTriggerBody.escape(FactoryPaths.DATASET) + "');\n");
		sb.append("\tif isNull(factoryDataset) then return;\n");
		// Lot S3 (2026-10-18, measured live): EVERY typed sql literal crashes the compiler of a data-model trigger script on
		// 6.3.0-rc3 (HTTP 500 of the IDE's validate route, "revision history ... could not be loaded" from update_script),
		// whatever the model it names - so the lookup is a db.createQuery over a dataset added by alias.
		sb.append("\tvar targetQuery := db.createQuery('select t.id as targetId from factory.\"DistributionTarget\" t where t.project.code = ? and t.mode = ? and t.state = ?');\n");
		sb.append("\tdb.addDataset(targetQuery, 'factory', factoryDataset);\n");
		sb.append("\tdb.setParameter(targetQuery, 0i, '" + DistributionTriggerBody.escape(pProjectId) + "');\n");
		sb.append("\tdb.setParameter(targetQuery, 1i, 'REALTIME');\n");
		sb.append("\tdb.setParameter(targetQuery, 2i, 'ARMED');\n");
		sb.append("\tvar results := db.executeQuery(targetQuery);\n");
		sb.append("\tconst outboxTable := db.findTable<outbox.Event>(outboxDataset);\n");
		sb.append("\tfor tuple in results do\n");
		sb.append("\tbegin\n");
		sb.append("\t\tconst targetId := '' | db.field<int>(tuple, 'targetId');\n");
		// No `execute transaction` block here any more: db.recordOf/saveRecord run directly against the
		// trigger's own ambient _ebx.transaction, which the compiler accepts for any dataset of
		// _ebx.dataspace, not only _ebx.dataset (round 3 fix, item 2 - see class Javadoc for the exact
		// live probe this was checked against, and what it does not yet prove).
		sb.append("\t\tvar occ := db.recordOf(_ebx.transaction, outboxTable);\n");
		sb.append("\t\tocc.target := targetId;\n");
		sb.append("\t\tocc.tablePath := '" + DistributionTriggerBody.escape(pTablePath) + "';\n");
		sb.append("\t\tocc.recordKey := recordId;\n");
		sb.append("\t\tocc.change := changeValue;\n");
		sb.append("\t\tocc.survivorKey := survivorValue;\n");
		sb.append("\t\tocc.status := '" + FactoryPaths.DistributionOutbox.STATUS_PENDING + "';\n");
		sb.append("\t\tocc.attempts := 0i;\n");
		sb.append("\t\tocc.createdAt := dt.now();\n");
		sb.append("\t\tdb.saveRecord(occ);\n");
		sb.append("\tend\n");
		sb.append("\tdb.close(results);\n");
		sb.append("end\n");
		return sb.toString();
	}

	/** Builds {@code recordId} as EBX's own {@code formatPrimaryKey} of a typed key value populated field by field - never a hand-built pipe-joined string (CLAUDE.md sec 9). Works identically for one field or several; falls back to {@code id} when the installer could not read the real key (defensive - every table this module generates always has at least that field). */
	private static String recordKeyBlock(final String pTableName, final List<String> pPrimaryKeyFields)
	{
		final List<String> fields = pPrimaryKeyFields == null || pPrimaryKeyFields.isEmpty() ? java.util.Collections.singletonList("id") : pPrimaryKeyFields;
		final StringBuilder sb = new StringBuilder();
		sb.append("\tvar pk := cp.primaryKeyOf<master." + pTableName + ">();\n");
		for (final String field : fields)
		{
			sb.append("\tpk." + field + " := _ebx.record." + field + ";\n");
		}
		sb.append("\tconst recordId := cp.formatPrimaryKey(pk);\n");
		return sb.toString();
	}

	/** EBX Script string-literal escaping (backslash-style, CLAUDE.md sec 2 - never doubled quotes): a bare project id/table path/model name is not expected to carry a quote or backslash, but this is the one place it would be handled if it ever did. */
	static String escape(final String pValue)
	{
		if (pValue == null)
		{
			return "";
		}
		return pValue.replace("\\", "\\\\").replace("'", "\\'");
	}
}
