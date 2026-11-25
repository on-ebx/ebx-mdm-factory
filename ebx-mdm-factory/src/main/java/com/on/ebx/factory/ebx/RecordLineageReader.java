package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.lineage.RecordLineage;
import com.on.ebx.factory.lineage.RecordTitleRule;
import com.on.ebx.factory.master.DecisionTreePlan;
import com.on.ebx.factory.master.GoldenKeySelfHeal;
import com.on.ebx.factory.master.MameMetadataset;
import com.on.ebx.factory.master.MameSchemaWatch;
import com.on.ebx.factory.master.MasterFieldNameRule;
import com.on.ebx.factory.master.MasterModel;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.AccessPermission;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.SessionPermissions;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The EBX-bound READ side of the record-lineage screen (lot 4, `PLAN.md` "Lineage par enregistrement",
 * `docs/MASTER-ZONE.md` &sect;5): resolves ONE master record - its own {@code Xref} chain (and, for a
 * merged/golden record, every OTHER contributing master record's own chain), the landing/staging rows those
 * chains point at, the {@code AttributeMapping}/{@code MappingStep} chain that produced each field, and (when
 * available) the record's Match and Merge state - then hands all of it to the pure {@code
 * lineage.RecordLineage#assemble} to build the JSON the mockup (`docs/mockups/mdm-factory.html` &sect;5) needs.
 * <p>
 * <b>Bounded, never a table scan of the business data</b>: exactly one master record lookup, at most one
 * {@code Xref} lookup per contributing master key (a handful at most - one per source that was ever merged
 * into this golden), one landing and one staging record lookup per contributing key, and one bounded reverse
 * scan of the add-on's {@code RecordMetadata} metadataset filtered by {@code goldenId} (a group is a handful
 * of rows, never the whole table - {@code createRequestResult} with an XPath predicate, the same pattern
 * {@code ebx.MasterLoader.RecordMetadataMergeCheck} already uses for a single-row lookup on the same table).
 * The only per-project, non-trivially-bounded reads are the project's own {@code StagingEntity}/{@code
 * StagingAttribute}/{@code AttributeMapping}/{@code MappingStep} rows (small factory-owned tables, the same
 * ones {@code ebx.StagingLoader}/{@code ebx.MasterLoader} already read in full on every run).
 * <p>
 * <b>Runs with the CALLER's own session</b>, like {@code ebx.StagingLoader}/{@code ebx.MasterLoader}: no MCP
 * call, no agent-account credentials - the caller needs read access to the project's landing/staging/master
 * dataspaces and to the factory's own dataset. Read-only end to end: never creates a {@code Procedure}.
 * <p>
 * <b>This class computes access-permission FLAGS; {@code lineage.RecordLineage} decides what to emit</b>
 * (coordinator review, 2026-09-21 - `docs/RECORD-LINEAGE.md` "Authorisation" has the full writeup and what
 * was NOT verified live). {@code AdaptationTable.createRequestResult}/{@code lookupAdaptationByPrimaryKey}
 * are NOT permission-filtered by themselves - passing a {@link Session} around does not make a read
 * permission-aware on its own. Every record and field this class reads is checked against {@code
 * pSession.getPermissions()} ({@link SessionPermissions#getAdaptationAccessPermission(Adaptation)} for a
 * whole record, {@link SessionPermissions#getNodeAccessPermission(SchemaNode, Adaptation)} for one field,
 * both verified by {@code javap} against {@code ebx-lib.jar} - see {@link #isHidden}/{@link #isFieldHidden}):
 * <ul>
 * <li>The MASTER record itself: {@link #isHidden} right after the lookup - hidden renders as a plain 404,
 * word-for-word the same message as "no such record", so a caller can never tell "hidden" from
 * "does not exist".</li>
 * <li>Every contributing source's own landing/staging record: {@link #isHidden} feeds {@code
 * ResolvedSource#restricted}, which becomes {@code RecordLineage.SourceChain#restricted}.</li>
 * <li>Every field, on the master record and on each contributing source's landing/staging record: {@link
 * #isFieldHidden} feeds {@code RecordLineage.FieldValue#restricted}/{@code
 * RecordLineage.FieldContribution#restricted}.</li>
 * </ul>
 * Both permission checks fail CLOSED in every ambiguous case, never open: an unexpected {@link
 * RuntimeException} from the permission API itself is treated as hidden, and so is a {@code null} {@link
 * AccessPermission} answer (coordinator review, 2026-09-21 - an earlier version of this class treated
 * {@code null} as visible, inconsistently with the exception path) - see the two methods' own Javadoc.
 * <b>Not run live this session</b> (the coordinator's own constraint): the two signatures are real
 * (`javap`), but no request against this route with a genuinely restricted profile was ever made.
 * <p>
 * <b>"Merge information absent" is decided BEFORE ever touching the add-on</b> - {@code MatchPlan.status} is a
 * plain fact of the factory's own data: a plan that is not yet {@code APPLIED} means matching has never run
 * for this entity, so {@code RecordLineage.MergeInfo#NO_MATCHING_YET} is answered without calling {@link
 * MameRecordState} at all (which, in turn, is the only place that can answer {@code ADDON_NOT_AVAILABLE} /
 * {@code NO_METADATA} - see its own Javadoc).
 * <p>
 * <b>Every failure of the merge-metadata reads degrades to {@code METADATA_UNREADABLE}, never an exception
 * reaching the caller</b> (coordinator review, 2026-09-21): {@link #computeMerge} wraps the ENTIRE merge
 * determination - {@link MameRecordState} and {@link MameGroups} already never throw (see their own
 * Javadoc). <b>Public-API refactor, 2026-09-24</b> (CLAUDE.md &sect;9): the contributing-key/isolated-flag
 * derivation no longer depends on the metadataset naming convention ({@code master.MameMetadataset}) at
 * all - it now goes through {@link MameGroups#read}. <b>Measured live 2026-09-24</b> (see {@link
 * #mergedContributorKeys}'s and {@link #excludingGolden}'s own Javadoc): {@code getMatchedRecords()} includes
 * MERGED members (contributors correct) AND the group's own golden record (a record alone in its group still
 * appeared in it, which the isolated-flag fix now accounts for). Only the golden's
 * own {@code RecordMetadata.mergingProcessId} ({@link MameMetadatasetReads}) still touches the metadataset
 * directly, and still depends on that naming convention. {@link #computeMerge} catches every {@link
 * RuntimeException} from any of this, turning it into {@code RecordLineage.MergeInfo#METADATA_UNREADABLE}
 * with the exception's class and message as {@code detail} (never a record value) - the chain master &larr;
 * staging &larr; landing is still returned, HTTP 200.
 * <p>
 * <b>Field-level, post-merge provenance is a documented seam, not a guess</b> - see {@link
 * FieldMergeLineageSource}'s own Javadoc for exactly why {@code MergeValueLineageCompact.Items[]} is not read
 * this lot.
 */
public final class RecordLineageReader
{
	private RecordLineageReader()
	{
	}

	/** Unknown project, entity, or master record - the REST layer maps this to {@code 404}. */
	public static final class NotFoundException extends OperationException
	{
		private static final long serialVersionUID = 1L;

		NotFoundException(final String pMessage)
		{
			super(pMessage);
		}
	}

	/** A blank or unparsable {@code key} query parameter - the REST layer maps this to {@code 400}. */
	public static final class BadKeyException extends OperationException
	{
		private static final long serialVersionUID = 1L;

		BadKeyException(final String pMessage)
		{
			super(pMessage);
		}
	}

	// ============================================================================================== entry point

	/** F2-112: contributors of a golden read per page; the default page size, overridable by this system property or by the caller of the five-argument-plus-window overload. */
	public static final String MAX_CONTRIBUTORS_PROPERTY = "ebx.factory.lineage.maxContributors";
	public static final int DEFAULT_MAX_CONTRIBUTORS = 50;

	/** The page size of contributors: the property when it holds a positive integer, else {@link #DEFAULT_MAX_CONTRIBUTORS}. */
	public static int maxContributors()
	{
		final String raw = System.getProperty(RecordLineageReader.MAX_CONTRIBUTORS_PROPERTY);
		if (raw != null)
		{
			try
			{
				final int value = Integer.parseInt(raw.trim());
				if (value > 0)
				{
					return value;
				}
			}
			catch (final NumberFormatException notANumber)
			{
				// a malformed setting falls back to the default, loudly enough: the screen states "N of M shown", so the effective size is always visible
			}
		}
		return RecordLineageReader.DEFAULT_MAX_CONTRIBUTORS;
	}

	/**
	 * F2-112: the contributors to resolve on this read. The golden anchor always comes first (the screen cannot be drawn without it); the other contributors are cut to the window
	 * [pOffset, pOffset + pLimit). Pure: no EBX class.
	 */
	static List<String> contributorWindow(final Set<String> pKeys, final String pGoldenKey, final int pOffset, final int pLimit)
	{
		final List<String> out = new ArrayList<String>();
		final int offset = Math.max(0, pOffset);
		final int limit = Math.max(1, pLimit);
		if (pGoldenKey != null && pKeys.contains(pGoldenKey))
		{
			out.add(pGoldenKey);
		}
		int index = 0;
		for (final String key : pKeys)
		{
			if (key.equals(pGoldenKey))
			{
				continue;
			}
			if (index >= offset && index < offset + limit)
			{
				out.add(key);
			}
			index++;
		}
		return out;
	}

	/** Contributors of the first page, {@link #maxContributors()} at most. */
	public static ObjectNode read(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName, final String pKeyText)
		throws OperationException
	{
		return RecordLineageReader.read(pSession, pFactory, pProjectCode, pEntityName, pKeyText, 0, RecordLineageReader.maxContributors());
	}

	/**
	 * Same read, the contributors by page (F2-112): the answer carries {@code contributors: {total, shown, offset, limit, nextOffset}} ({@code nextOffset} absent on the last page)
	 * so a screen can say "N of M shown" and link to the next ones. {@code pContributorOffset} counts the contributors other than the golden itself.
	 */
	public static ObjectNode read(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName, final String pKeyText,
		final int pContributorOffset, final int pContributorLimit) throws OperationException
	{
		if (pKeyText == null || pKeyText.trim().isEmpty())
		{
			throw new BadKeyException("The 'key' query parameter is required.");
		}
		if (pEntityName == null || pEntityName.trim().isEmpty())
		{
			throw new RecordLineageReader.NotFoundException("An entity name is required.");
		}

		final Adaptation project = RecordLineageReader.findProject(pSession, pFactory, pProjectCode);
		if (project == null)
		{
			throw new RecordLineageReader.NotFoundException("Project " + pProjectCode + " not found.");
		}
		final String projectId = project.getOccurrencePrimaryKey().format();

		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (RecordLineageReader.blank(masterDataspaceKey) || RecordLineageReader.blank(masterDatasetName))
		{
			throw new RecordLineageReader.NotFoundException(
				"Project " + pProjectCode + " has no master zone yet: run POST /projects/" + pProjectCode + "/master/generate first.");
		}
		final String stagingDataspaceKey = project.getString(FactoryPaths.Project.STAGING_DATASPACE);
		final String stagingDatasetName = project.getString(FactoryPaths.Project.STAGING_DATASET);
		final String landingDataspaceKey = project.getString(FactoryPaths.Project.LANDING_DATASPACE);

		final Map<String, String> entityIdByName = new LinkedHashMap<String, String>();
		final List<StagingModel.EntityInfo> entities = RecordLineageReader.readEntities(pFactory, projectId, entityIdByName);
		final MasterModel.Result masterModel = MasterModel.buildFromStaging(entities);
		if (!masterModel.builtEntities.contains(pEntityName))
		{
			throw new RecordLineageReader.NotFoundException("Entity " + pEntityName + " is not a promoted master entity of project " + pProjectCode + ".");
		}
		StagingModel.EntityInfo entityInfo = null;
		for (final StagingModel.EntityInfo candidate : entities)
		{
			if (candidate.name.equals(pEntityName))
			{
				entityInfo = candidate;
				break;
			}
		}
		final String masterTablePath = MasterModel.tablePath(pEntityName);

		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		if (masterDataset == null)
		{
			throw new RecordLineageReader.NotFoundException("Master dataset " + masterDataspaceKey + "/" + masterDatasetName + " could not be found.");
		}
		final AdaptationTable masterTable;
		try
		{
			masterTable = masterDataset.getTable(Path.parse(masterTablePath));
			if (masterTable == null || masterTable.getTableOccurrenceRootNode() == null)
			{
				throw new RecordLineageReader.NotFoundException("Entity " + pEntityName + " has no table yet in the master dataset.");
			}
		}
		catch (final RuntimeException ex)
		{
			throw new RecordLineageReader.NotFoundException("Entity " + pEntityName + " has no table yet in the master dataset: " + ex.getMessage());
		}

		final PrimaryKey key;
		final Adaptation masterRecord;
		try
		{
			key = PrimaryKey.parseString(pKeyText.trim());
			masterRecord = masterTable.lookupAdaptationByPrimaryKey(key);
		}
		catch (final RuntimeException ex)
		{
			throw new RecordLineageReader.BadKeyException("Malformed master key '" + pKeyText + "': " + ex.getMessage());
		}
		if (masterRecord == null)
		{
			throw new RecordLineageReader.NotFoundException(RecordLineageReader.recordNotFoundMessage(pEntityName, pKeyText, pProjectCode));
		}
		// permissions, fact 1 (coordinator review, 2026-09-21): a hidden master record must be indistinguishable
		// from one that does not exist at all - same exception type, same message, built by the same helper.
		if (RecordLineageReader.isHidden(pSession, masterRecord))
		{
			throw new RecordLineageReader.NotFoundException(RecordLineageReader.recordNotFoundMessage(pEntityName, pKeyText, pProjectCode));
		}
		final String masterKey = masterRecord.getOccurrencePrimaryKey().format();
		// "open in EBX" link for the graphic screen (CLAUDE.md sect9's own documented, already-proven
		// "predicate" query parameter pattern) - built from the record's own primary-key VALUES via
		// recordPredicate, never by splitting/re-parsing masterKey (a formatted string that may itself embed
		// the "|" PrimaryKey separator - CLAUDE.md sect9). Null (never an exception) if it cannot be built.
		final String masterUrl = RecordLineageReader.openUrl(RecordLineageReader.recordPredicate(masterRecord), masterDataspaceKey, masterDatasetName, masterTablePath);

		// business field names of this entity, restricted to what actually exists on the PUBLISHED master
		// table (the same "never trust the model to be as fresh as the proposals" guard StagingLoader/
		// MasterLoader already apply via FieldAvailabilityRule - see their own Javadoc)
		// 2026-09-22 (product-owner decision, PLAN.md): a FOLD_KEY derived attribute (quality.CrossSystemFold)
		// is a TECHNICAL matching key, not a business value - excluded from the field-by-field screen entirely
		// (the simpler of the two options the task allowed: never shown, rather than shown collapsed under a
		// tag) so a folded value like "ZURICH" never appears next to the display attribute it was built to
		// leave untouched. Read once, fresh, rather than extending StagingModel.AttributeInfo's shape (that
		// class treats a derived attribute as perfectly ordinary everywhere else - see quality.CrossSystemFold's
		// own Javadoc - this reader is the one place that needs to tell the two apart). Computed here, BEFORE
		// masterLabel below (moved up from its original position, right after fieldNames/fieldLabelsByName),
		// because the title fallback (RecordTitleRule, coordinator review 2026-09-22, "fact 1") must skip a
		// derived matching key exactly like the field-by-field table already does.
		final Set<String> derivedAttributeNames = RecordLineageReader.readDerivedAttributeNames(pFactory, entityIdByName.get(pEntityName));

		// the field names RecordTitleRule may pick a title from for this entity - shared with the group's own
		// matched/suspect members below (same entity/table, see computeMerge/buildGroup/buildGroupMember).
		final List<String> titleFieldNames = RecordLineageReader.titleFieldNames(entityInfo, derivedAttributeNames);
		// the record's own display title (coordinator review, 2026-09-22, "fact 1": the golden card/page header
		// showed the bare key "183" for a record whose own EBX label is blank/echoes the key, because the
		// generated master model carries no osd:table/defaultLabel - see master.MasterModel's own Javadoc for
		// the model-build-time half of this fix). Falls back, in order, to the record's own "name"/"label"/
		// "title" field, then a "...Name" field (e.g. accountName), then the first other eligible string field -
		// RecordTitleRule (lineage package, no EBX class, unit-tested) is the pure decision; only NON-restricted
		// field values (isFieldHidden already false, see #titleFieldCandidates) are ever offered to it. Null
		// when nothing beats the bare key - the existing "masterLabel == null -> show the key" convention
		// downstream (RecordLineage/RecordLineagePage/RecordLineageGraphic) is unchanged on purpose, so this fix
		// touches only how the value is chosen, never how it is rendered.
		final String masterLabel = RecordLineageReader.masterLabel(masterRecord, pSession, masterKey,
			RecordLineageReader.titleFieldCandidates(pSession, masterRecord, titleFieldNames));

		final List<String> fieldNames = new ArrayList<String>();
		// the master schema's own field LABEL (coordinator review, 2026-09-22: "the golden column shows field
		// names in raw camelCase small caps" - SchemaNode#getLabel is session-aware, same read level as
		// masterRecord.getLabel(pSession) a few lines above) - null when the schema carries none, or a label
		// equal to the bare name (no fabricated distinction), so RecordLineage.FieldInput's own "null means
		// fall back to the name" convention applies unchanged.
		final Map<String, String> fieldLabelsByName = new LinkedHashMap<String, String>();
		// fieldNames are MASTER field names (what this record's own table carries, what the lineage table shows,
		// what MergeValueLineageCompact's fieldPath names); a staging/landing read of the same attribute goes
		// through stagingFieldByMasterField, because the two differ for an attribute MasterFieldNameRule renamed
		// (2026-09-23, docs/MASTER-ZONE.md §13: a Salesforce "Id" attribute is the master field "Id_src") -
		// before that rule, such a field simply vanished from the lineage (the getNode guard below found nothing).
		final Map<String, String> stagingFieldByMasterField = new LinkedHashMap<String, String>();
		if (entityInfo != null)
		{
			final Map<String, String> masterFieldByAttribute = MasterFieldNameRule.masterFieldNames(entityInfo.attributes);
			for (final StagingModel.AttributeInfo attribute : entityInfo.attributes)
			{
				if (attribute.primaryKey || derivedAttributeNames.contains(attribute.name) || !TechnicalKeyRule.isAcceptedLike(attribute.status))
				{
					continue;
				}
				final String masterField = masterFieldByAttribute.get(attribute.name);
				final SchemaNode fieldNode = masterTable.getTableOccurrenceRootNode().getNode(Path.parse("./" + masterField));
				if (fieldNode == null)
				{
					continue;
				}
				fieldNames.add(masterField);
				stagingFieldByMasterField.put(masterField, attribute.name);
				final String label = fieldNode.getLabel(pSession);
				fieldLabelsByName.put(masterField, label == null || label.trim().isEmpty() || label.equals(masterField) ? null : label);
			}
		}

		// permissions, fact 3: a field hidden on the master record itself never reaches record.fields - the
		// PURE model (RecordLineage.assemble) is the one that decides to drop it, this reader only flags it.
		final Map<String, RecordLineage.FieldValue> recordFields = new LinkedHashMap<String, RecordLineage.FieldValue>();
		for (final String field : fieldNames)
		{
			if (RecordLineageReader.isFieldHidden(pSession, masterRecord, field))
			{
				recordFields.put(field, RecordLineage.FieldValue.restricted());
			}
			else
			{
				recordFields.put(field, RecordLineage.FieldValue.of(RecordLineageReader.safeString(masterRecord, field)));
			}
		}

		// merge status - MatchPlan.status is checked BEFORE ever touching the add-on (class Javadoc); every
		// failure of the metadata reads below is caught INSIDE computeMerge and degrades to METADATA_UNREADABLE,
		// never an exception reaching the caller (coordinator review, 2026-09-21).
		final MergeResult mergeResult = RecordLineageReader.computeMerge(repository, pFactory, entityIdByName.get(pEntityName), masterRecord, masterKey,
			masterDataspaceKey, masterDatasetName, masterTablePath, masterTable, pSession, titleFieldNames, fieldNames);
		final RecordLineage.MergeInfo mergeInfo = mergeResult.info;
		final Set<String> contributingKeys = mergeResult.contributingKeys;
		final String goldenAnchorKey = mergeInfo.present ? mergeInfo.goldenKey : masterKey;

		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 7): the staging table is resolved here (moved up from
		// its original position below the self-heal call) because self-heal now DERIVES each Xref row's natural
		// key from the master record's own sourceSystem/sourceId (XrefByMasterRecord), which needs it.
		final AdaptationHome stagingHome = RecordLineageReader.blank(stagingDataspaceKey) ? null : repository.lookupHome(HomeKey.forBranchName(stagingDataspaceKey));
		final Adaptation stagingDataset = stagingHome == null || RecordLineageReader.blank(stagingDatasetName) ? null
			: stagingHome.findAdaptationOrNull(AdaptationName.forName(stagingDatasetName));
		final AdaptationTable stagingTable = stagingDataset == null ? null : stagingDataset.getTable(Path.parse(StagingModel.tablePath(pEntityName)));

		// self-heal (2026-09-23, "Keeping Xref.goldenKey fresh"): computeMerge just resolved the real truth
		// (goldenAnchorKey + contributingKeys, straight from RecordMetadata) - correct any Xref row that
		// disagrees with it right here, on this very read, rather than waiting for the next matching/run or
		// an explicit refresh. Never fails the read (own Javadoc); a no-op for a read-only caller.
		RecordLineageReader.selfHealGoldenKeys(pSession, pFactory, projectId, pEntityName, masterTable, stagingTable, masterTablePath, goldenAnchorKey,
			contributingKeys);

		// field origins (landing column + cleaning steps, per source system) - one small read of the entity's
		// own AttributeMapping/MappingStep rows, shared by every contributing source below
		// read by ATTRIBUTE name (AttributeMapping rows hang off StagingAttribute), re-keyed by MASTER field name
		// so every lookup below (originsByField.get(field), cleaningKindsForSourceSystem) stays keyed like fieldNames
		final Map<String, Map<String, FieldOrigin>> originsByField = new LinkedHashMap<String, Map<String, FieldOrigin>>();
		{
			final Map<String, Map<String, FieldOrigin>> originsByAttribute = RecordLineageReader.readFieldOrigins(pFactory, entityIdByName.get(pEntityName),
				new ArrayList<String>(stagingFieldByMasterField.values()));
			for (final String field : fieldNames)
			{
				final Map<String, FieldOrigin> origins = originsByAttribute.get(stagingFieldByMasterField.get(field));
				if (origins != null)
				{
					originsByField.put(field, origins);
				}
			}
		}

		final AdaptationHome landingHome = RecordLineageReader.blank(landingDataspaceKey) ? null : repository.lookupHome(HomeKey.forBranchName(landingDataspaceKey));

		final String stagingTablePath = StagingModel.tablePath(pEntityName);
		// stable colour ordering for the graphic screen (coordinator review, 2026-09-22): ProjectSource's own
		// trustRank ("lower wins", already the merge policy's most-trusted-source order - WizardRules/
		// WizardSnapshot's own Javadoc) doubles as "the order of the project's sources", the same for every
		// record of the project regardless of which one is being looked at - see SourceChain#sourceOrder.
		final Map<String, Integer> sourceOrderBySystem = RecordLineageReader.readSourceTrustRank(pFactory, projectId);
		final List<RecordLineage.SourceChain> sourceChains = new ArrayList<RecordLineage.SourceChain>();
		final Map<String, ResolvedSource> resolvedByKey = new LinkedHashMap<String, ResolvedSource>();
		// F2-112: a golden of thousands of members resolves one page of contributors (three reads each), never all of them
		final List<String> windowKeys = RecordLineageReader.contributorWindow(contributingKeys, goldenAnchorKey, pContributorOffset, pContributorLimit);
		for (final String contributingKey : windowKeys)
		{
			final ResolvedSource resolved = RecordLineageReader.resolveSource(pSession, pFactory, projectId, pEntityName, masterTable, contributingKey, landingHome,
				stagingTable);
			if (resolved != null)
			{
				resolvedByKey.put(contributingKey, resolved);
				final boolean golden = contributingKey.equals(goldenAnchorKey);
				// "open in EBX" links (see the masterUrl comment above for the technique and its caveats) -
				// never even attempted for a restricted source: the whole point of restriction is that this
				// reader must not hand out anything that could reveal the record exists, a link included.
				final String landingUrl = resolved.restricted ? null
					: RecordLineageReader.openUrl(RecordLineageReader.recordPredicate(resolved.landingRow), landingDataspaceKey, resolved.landingDataset,
						"/root/" + resolved.landingTable);
				final String stagingUrl = resolved.restricted ? null
					: RecordLineageReader.openUrl(RecordLineageReader.recordPredicate(resolved.stagingRow), stagingDataspaceKey, stagingDatasetName, stagingTablePath);
				// permissions, fact 2: a hidden landing/staging record renders as system-name-only - see
				// RecordLineage's own Javadoc for exactly what that means; this reader only carries the flag.
				sourceChains.add(new RecordLineage.SourceChain(contributingKey, golden, resolved.sourceSystem, resolved.restricted, resolved.sourceId,
					resolved.landingDataset, resolved.landingTable, resolved.landingKey, resolved.loadId, resolved.landingStatus, resolved.receivedAt, pEntityName,
					resolved.stagingKey, landingUrl, stagingUrl, sourceOrderBySystem.get(resolved.sourceSystem)));
			}
		}

		// suspect lanes for the journey view (task brief, 2026-09-22: "the PARCOURS must draw a lane for each
		// SUSPECT record too") - resolved the EXACT same way a contributing source is (resolveSource, under
		// the same permission rule and bound), kept in a SEPARATE list from sourceChains (see
		// RecordLineage.SourceChain#suspect's own Javadoc for why): a suspect is not yet a confirmed
		// contributor, so it must never enter the "champ par champ" field table's own column set. A suspect
		// already among contributingKeys (should not happen - a group's own suspects are by definition not yet
		// members) is skipped rather than duplicated, defensively.
		final List<RecordLineage.SourceChain> suspectSourceChains = new ArrayList<RecordLineage.SourceChain>();
		if (mergeResult.group != null)
		{
			for (final RecordLineage.GroupMember suspect : mergeResult.group.suspects)
			{
				if (suspect.restricted || suspect.masterKey == null || contributingKeys.contains(suspect.masterKey))
				{
					continue;
				}
				final ResolvedSource resolved = RecordLineageReader.resolveSource(pSession, pFactory, projectId, pEntityName, masterTable, suspect.masterKey, landingHome,
					stagingTable);
				if (resolved != null)
				{
					final String landingUrl = resolved.restricted ? null
						: RecordLineageReader.openUrl(RecordLineageReader.recordPredicate(resolved.landingRow), landingDataspaceKey, resolved.landingDataset,
							"/root/" + resolved.landingTable);
					final String stagingUrl = resolved.restricted ? null
						: RecordLineageReader.openUrl(RecordLineageReader.recordPredicate(resolved.stagingRow), stagingDataspaceKey, stagingDatasetName, stagingTablePath);
					// fix, 2026-09-22 (task brief item D): this suspect's OWN cleaning-step kinds, by source
					// system - see SourceChain#cleaningStepKinds' own Javadoc for why a suspect (no field
					// CONTRIBUTIONS yet) needs this computed differently from a confirmed source. Null (never
					// an empty list standing in for "restricted") when the source is itself restricted - never
					// even attempted, same discipline as every other field on a restricted source.
					final List<String> cleaningStepKinds = resolved.restricted ? null
						: RecordLineageReader.cleaningKindsForSourceSystem(originsByField, resolved.sourceSystem);
					suspectSourceChains.add(new RecordLineage.SourceChain(suspect.masterKey, false, resolved.sourceSystem, resolved.restricted, resolved.sourceId,
						resolved.landingDataset, resolved.landingTable, resolved.landingKey, resolved.loadId, resolved.landingStatus, resolved.receivedAt, pEntityName,
						resolved.stagingKey, landingUrl, stagingUrl, sourceOrderBySystem.get(resolved.sourceSystem), true, cleaningStepKinds));
				}
			}
		}

		// field-by-field, POST-MERGE survivorship (2026-09-22, "decide on a suspect" lot - see
		// ebx.FieldMergeLineageSource's own Javadoc for the mechanism and its "never verified live" caveats).
		// Resolved once per record, reused for every field below - null whenever the golden carries no
		// mergingProcessId (no real merge has run yet), in which case every field simply falls back to the
		// existing DEDUCED rule (lineage.RecordLineage.FieldInput's own Javadoc) exactly as before this lot.
		final AdaptationTable mergeValueLineageTable = mergeResult.mergingProcessId == null ? null
			: MameMetadatasetReads.resolveMergeValueLineageTable(repository, masterDataspaceKey, masterDatasetName, masterTablePath);

		final List<RecordLineage.FieldInput> fieldInputs = new ArrayList<RecordLineage.FieldInput>();
		final String[] mergeLineageUnreadable = new String[1];
		for (final String field : fieldNames)
		{
			final Map<String, FieldOrigin> originsBySource = originsByField.get(field);
			final String stagingField = stagingFieldByMasterField.containsKey(field) ? stagingFieldByMasterField.get(field) : field;
			final List<RecordLineage.FieldContribution> contributions = new ArrayList<RecordLineage.FieldContribution>();
			final Map<String, String> comparableByKey = new LinkedHashMap<String, String>();
			for (final Map.Entry<String, ResolvedSource> entry : resolvedByKey.entrySet())
			{
				final ResolvedSource resolved = entry.getValue();
				final FieldOrigin origin = originsBySource == null ? null : originsBySource.get(resolved.sourceSystem);
				// permissions, facts 2/3: the WHOLE source hidden, or just this one field on its landing/staging
				// record - either way the contribution is restricted, and (defense in depth, on top of what
				// RecordLineage.assemble itself already enforces) the value is never even read into it.
				final boolean fieldRestricted = resolved.restricted
					|| (origin != null && RecordLineageReader.isFieldHidden(pSession, resolved.landingRow, origin.landingColumn))
					|| RecordLineageReader.isFieldHidden(pSession, resolved.stagingRow, stagingField);
				final String rawValue = !fieldRestricted && origin != null ? RecordLineageReader.safeString(resolved.landingRow, origin.landingColumn) : null;
				final String stagingValue = fieldRestricted ? null : RecordLineageReader.safeString(resolved.stagingRow, stagingField);
				contributions.add(new RecordLineage.FieldContribution(entry.getKey(), resolved.sourceSystem, fieldRestricted,
					fieldRestricted ? null : (origin == null ? null : origin.landingColumn), rawValue, stagingValue,
					fieldRestricted ? null : (origin == null ? null : origin.cleaningSteps)));
				if (!fieldRestricted)
				{
					final String comparable = stagingValue != null ? stagingValue : rawValue;
					if (comparable != null)
					{
						comparableByKey.put(entry.getKey(), comparable);
					}
				}
			}
			String knownRetainedMasterKey = null;
			String survivorshipRule = null;
			if (mergeValueLineageTable != null)
			{
				final FieldMergeLineageSource.Result mergeLineage = FieldMergeLineageSource.read(mergeValueLineageTable, mergeResult.mergingProcessId, field,
					comparableByKey);
				if (mergeLineage.available)
				{
					knownRetainedMasterKey = mergeLineage.knownRetainedMasterKey;
					survivorshipRule = mergeLineage.survivorshipRule;
				}
				else if (mergeLineage.unreadableReason != null && mergeLineageUnreadable[0] == null)
				{
					mergeLineageUnreadable[0] = mergeLineage.unreadableReason; // F2-08: said on the screen, never an empty list
				}
			}
			fieldInputs.add(new RecordLineage.FieldInput(field, contributions, knownRetainedMasterKey, survivorshipRule, fieldLabelsByName.get(field)));
		}

		// see FieldMergeLineageSource's own two constants for exactly what each means - never the old,
		// blanket "not verified" note now that a real (if best-effort) read is attempted.
		final String fieldLineageNote;
		// audit F-27: the add-on's metadata does not have the expected shape - say so on the screen (RecordLineagePage translates the marker) instead of showing "nothing merged"
		final String mameUnavailable = MameSchemaWatch.details(MameMetadataset.name(masterDatasetName, masterTablePath));
		if (mameUnavailable != null)
		{
			fieldLineageNote = RecordLineage.MAME_UNAVAILABLE_PREFIX + mameUnavailable;
		}
		else if (mergeLineageUnreadable[0] != null && mergeInfo.present && contributingKeys.size() > 1)
		{
			fieldLineageNote = RecordLineage.MERGE_LINEAGE_UNREADABLE_PREFIX + mergeLineageUnreadable[0];
		}
		else if (!mergeInfo.present || contributingKeys.size() <= 1)
		{
			fieldLineageNote = null;
		}
		else if (mergeResult.mergingProcessId == null)
		{
			fieldLineageNote = FieldMergeLineageSource.NO_MERGE_YET;
		}
		else
		{
			fieldLineageNote = FieldMergeLineageSource.PARTIAL_OR_UNVERIFIED;
		}

		// "compare with suspects" panel data (2026-09-22, "decide on a suspect" lot) - the fields the plan
		// compares, and every pending suspect's own field VALUES already read alongside the group above.
		final List<RecordLineage.PlanFieldRef> matchPlanFields = RecordLineageReader.readMatchPlanFields(pFactory, entityIdByName.get(pEntityName));

		final RecordLineage.Input input = new RecordLineage.Input(pProjectCode, pEntityName, masterTablePath, masterKey, recordFields, sourceChains, fieldInputs,
			mergeInfo, fieldLineageNote, masterUrl, masterLabel, suspectSourceChains, matchPlanFields, mergeResult.suspectFieldSets);
		final ObjectNode answer = RecordLineage.assemble(input);
		answer.set("contributors", RecordLineageReader.contributorsNode(contributingKeys.size(), goldenAnchorKey != null && contributingKeys.contains(goldenAnchorKey), windowKeys.size(),
			pContributorOffset, pContributorLimit));
		return answer;
	}

	/** F2-112: {total, shown, offset, limit[, nextOffset]} - the figures of "N of M shown". Pure. */
	static ObjectNode contributorsNode(final int pTotal, final boolean pGoldenIncluded, final int pShown, final int pOffset, final int pLimit)
	{
		final ObjectNode node = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode();
		final int offset = Math.max(0, pOffset);
		final int limit = Math.max(1, pLimit);
		final int others = pTotal - (pGoldenIncluded ? 1 : 0);
		final int shownOthers = pShown - (pGoldenIncluded ? 1 : 0);
		node.put("total", pTotal);
		node.put("shown", pShown);
		node.put("offset", offset);
		node.put("limit", limit);
		if (offset + shownOthers < others)
		{
			node.put("nextOffset", offset + limit);
		}
		return node;
	}

	// ============================================================================================ suspect decisions

	/** One of {@link MameSuspectDecision}'s own outcome constants, or a request-level failure this method detects itself before ever calling it (see the constants below). */
	public static final class DecisionResult
	{
		public final boolean success;
		public final String outcome;
		/** Non-null only on a failure/partial outcome - never a record value (see {@link MameSuspectDecision.Result#detail}). */
		public final String detail;
		/** Non-null only for {@link MameSuspectDecision#ACCEPTED_AND_MERGED}. */
		public final String goldenKey;
		/**
		 * The scoped {@code Xref.goldenKey} refresh {@link #decideSuspect} attempts after a SUCCESSFUL
		 * decision (2026-09-23, "Keeping Xref.goldenKey fresh") - same shape as {@code
		 * MatchingConfigurator#run}'s own {@code goldenKeyRefresh} counters. All three are non-null together,
		 * only when the refresh actually ran; null on every request-level failure above (nothing to refresh:
		 * the decision itself never reached the add-on) and whenever {@link #goldenKeyRefreshError} is set.
		 */
		public final Integer goldenKeyRefreshSet;
		public final Integer goldenKeyRefreshCleared;
		public final Integer goldenKeyRefreshUnchanged;
		/**
		 * Non-null only when the refresh attempt itself failed - mirrors {@code
		 * MatchingConfigurator#run}'s own {@code goldenKeyRefreshSkipped} note. The decision above ({@link
		 * #success}/{@link #outcome}) is NEVER affected by this: a refresh failure can never turn a
		 * successful accept/reject into a reported failure (class Javadoc, "never let a refresh failure turn
		 * a successful decision into a failure").
		 */
		public final String goldenKeyRefreshError;

		DecisionResult(final boolean pSuccess, final String pOutcome, final String pDetail, final String pGoldenKey)
		{
			this(pSuccess, pOutcome, pDetail, pGoldenKey, null, null, null, null);
		}

		DecisionResult(final boolean pSuccess, final String pOutcome, final String pDetail, final String pGoldenKey, final Integer pGoldenKeyRefreshSet,
			final Integer pGoldenKeyRefreshCleared, final Integer pGoldenKeyRefreshUnchanged, final String pGoldenKeyRefreshError)
		{
			this.success = pSuccess;
			this.outcome = pOutcome;
			this.detail = pDetail;
			this.goldenKey = pGoldenKey;
			this.goldenKeyRefreshSet = pGoldenKeyRefreshSet;
			this.goldenKeyRefreshCleared = pGoldenKeyRefreshCleared;
			this.goldenKeyRefreshUnchanged = pGoldenKeyRefreshUnchanged;
			this.goldenKeyRefreshError = pGoldenKeyRefreshError;
		}
	}

	/** The anchor record has no add-on metadata / no group at all to decide against - a plain request-level failure this method detects itself, distinct from anything {@link MameSuspectDecision} can answer. */
	public static final String NO_GROUP = "NO_GROUP";
	/** The caller's session has no read-write access to the anchor master record ({@link #canReadWrite}) - the SAME check {@code RecordLineage.GroupInfo#canAcceptSuspects} already exposes as a read-only flag; re-checked here since a write must never trust a flag computed for an earlier, possibly stale render. */
	public static final String NO_WRITE_ACCESS = "NO_WRITE_ACCESS";
	/** {@code pSuspectKeyText} does not resolve to a visible record of the same master table. */
	public static final String SUSPECT_NOT_FOUND = "SUSPECT_NOT_FOUND";

	/**
	 * Confirms ({@code pAccept}) or rejects a pending suspect of the anchor record's own Match and Merge
	 * group - the write half of the "decide on a suspect" lot (2026-09-22). Re-resolves the anchor record
	 * and its group fresh (never reuses anything computed by an earlier {@link #read} call, which may be
	 * stale by the time a steward clicks a decision button) through the SAME project/entity/master-table
	 * resolution {@link #read} performs at its own head - a small, deliberately separate copy (this
	 * codebase's own "self-contained, if slightly repeated, thin reader" precedent, `docs/RECORD-LINEAGE.md`
	 * "Shared rendering") rather than a shared helper that would have forced touching {@link #read} itself.
	 * <p>
	 * <b>Never run live this session</b> (task constraint) - the actual add-on calls are documented and
	 * {@code javap}-verified ({@link MameSuspectDecision}'s own Javadoc), but neither this method nor a
	 * button built on it has ever been clicked against a real group.
	 * <p>
	 * <b>Refreshes {@code Xref.goldenKey} right after a SUCCESSFUL decision (2026-09-23, "Keeping
	 * Xref.goldenKey fresh")</b> - a confirm/reject can move records between groups, or (per {@link
	 * MameSuspectDecision}'s own Javadoc, "FALSE on this build") trigger an automatic merge, either of which
	 * leaves the cached {@code goldenKey} of every affected {@code Xref} row stale until something re-reads
	 * the add-on's own record state. The scope is the suspect, the anchor, and every record that now shares
	 * either one's golden ({@link #suspectDecisionRefreshScope}, reusing {@link #resolveContributingKeys}) -
	 * {@link GoldenKeyRefresher#refreshScoped}, never the whole-table {@link GoldenKeyRefresher#refresh}.
	 * Never let a refresh failure turn a successful decision into a failure: it is caught and reported
	 * separately on {@link DecisionResult#goldenKeyRefreshError}, the decision's own {@code success}/{@code
	 * outcome} are untouched either way.
	 */
	public static DecisionResult decideSuspect(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName,
		final String pAnchorKeyText, final String pSuspectKeyText, final boolean pAccept) throws OperationException
	{
		return RecordLineageReader.decideSuspect(pSession, pFactory, pProjectCode, pEntityName, pAnchorKeyText, pSuspectKeyText, pAccept, true);
	}

	/**
	 * {@link #decideSuspect(Session, Adaptation, String, String, String, String, boolean)} with the wave-2 results hook made
	 * explicit: after a SUCCESSFUL decision the stored matching results of the entity ({@code MatchResultsSnapshot}) are patched
	 * (the pair leaves the queue, the counts move, the block is flagged stale) and a refresh job is queued - once per call. A
	 * caller that decides many pairs in a row (the wizard's bulk merge) passes {@code false} and calls {@link
	 * MatchingResultsStore#afterDecisions} once at the end instead of patching the same row a dozen times.
	 */
	public static DecisionResult decideSuspect(final Session pSession, final Adaptation pFactory, final String pProjectCode, final String pEntityName,
		final String pAnchorKeyText, final String pSuspectKeyText, final boolean pAccept, final boolean pTrackResults) throws OperationException
	{
		if (RecordLineageReader.blank(pAnchorKeyText) || RecordLineageReader.blank(pSuspectKeyText))
		{
			throw new RecordLineageReader.BadKeyException("Both the record's own key and the suspect's key are required.");
		}
		final Adaptation project = RecordLineageReader.findProject(pSession, pFactory, pProjectCode);
		if (project == null)
		{
			throw new RecordLineageReader.NotFoundException("Project " + pProjectCode + " not found.");
		}
		final String masterDataspaceKey = project.getString(FactoryPaths.Project.MASTER_DATASPACE);
		final String masterDatasetName = project.getString(FactoryPaths.Project.MASTER_DATASET);
		if (RecordLineageReader.blank(masterDataspaceKey) || RecordLineageReader.blank(masterDatasetName))
		{
			throw new RecordLineageReader.NotFoundException("Project " + pProjectCode + " has no master zone yet.");
		}
		final String masterTablePath = MasterModel.tablePath(pEntityName);
		final Repository repository = pFactory.getHome().getRepository();
		final AdaptationHome masterHome = repository.lookupHome(HomeKey.forBranchName(masterDataspaceKey));
		final Adaptation masterDataset = masterHome == null ? null : masterHome.findAdaptationOrNull(AdaptationName.forName(masterDatasetName));
		final AdaptationTable masterTable;
		try
		{
			masterTable = masterDataset == null ? null : masterDataset.getTable(Path.parse(masterTablePath));
		}
		catch (final RuntimeException ex)
		{
			throw new RecordLineageReader.NotFoundException("Entity " + pEntityName + " has no table in the master dataset: " + ex.getMessage());
		}
		if (masterTable == null || masterTable.getTableOccurrenceRootNode() == null)
		{
			throw new RecordLineageReader.NotFoundException("Entity " + pEntityName + " has no table yet in the master dataset.");
		}
		final Adaptation anchorRecord;
		try
		{
			anchorRecord = masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pAnchorKeyText.trim()));
		}
		catch (final RuntimeException ex)
		{
			throw new RecordLineageReader.BadKeyException("Malformed master key '" + pAnchorKeyText + "': " + ex.getMessage());
		}
		if (anchorRecord == null || RecordLineageReader.isHidden(pSession, anchorRecord))
		{
			throw new RecordLineageReader.NotFoundException(RecordLineageReader.recordNotFoundMessage(pEntityName, pAnchorKeyText, pProjectCode));
		}
		if (!RecordLineageReader.canReadWrite(pSession, anchorRecord))
		{
			return new DecisionResult(false, RecordLineageReader.NO_WRITE_ACCESS, null, null);
		}
		final MameRecordState.Result stateResult = MameRecordState.get().read(anchorRecord);
		if (!stateResult.addonPresent)
		{
			return new DecisionResult(false, MameSuspectDecision.ADDON_NOT_AVAILABLE, null, null);
		}
		if (stateResult.groupId == null)
		{
			return new DecisionResult(false, RecordLineageReader.NO_GROUP, null, null);
		}
		final java.math.BigDecimal groupId;
		try
		{
			groupId = new java.math.BigDecimal(stateResult.groupId);
		}
		catch (final RuntimeException ex)
		{
			return new DecisionResult(false, RecordLineageReader.NO_GROUP, null, null);
		}
		final Adaptation suspectRecord;
		try
		{
			suspectRecord = masterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pSuspectKeyText.trim()));
		}
		catch (final RuntimeException ex)
		{
			throw new RecordLineageReader.BadKeyException("Malformed suspect key '" + pSuspectKeyText + "': " + ex.getMessage());
		}
		if (suspectRecord == null || RecordLineageReader.isHidden(pSession, suspectRecord))
		{
			return new DecisionResult(false, RecordLineageReader.SUSPECT_NOT_FOUND, null, null);
		}
		final MameSuspectDecision.Result result = pAccept ? MameSuspectDecision.get().accept(pSession, masterTable, groupId, suspectRecord)
			: MameSuspectDecision.get().reject(pSession, masterTable, groupId, suspectRecord);
		// ACCEPTED_MERGE_POLICY_NOT_MANUAL (coordinator follow-up, 2026-09-22) is still a SUCCESS here, same
		// as ACCEPTED_MERGE_FAILED - the accept itself (moving the suspect into the group) went through; only
		// the automatic merge attempt did not, for a specific, now-named reason.
		final boolean success = MameSuspectDecision.ACCEPTED.equals(result.outcome) || MameSuspectDecision.ACCEPTED_AND_MERGED.equals(result.outcome)
			|| MameSuspectDecision.ACCEPTED_MERGE_FAILED.equals(result.outcome) || MameSuspectDecision.ACCEPTED_MERGE_POLICY_NOT_MANUAL.equals(result.outcome)
			|| MameSuspectDecision.REJECTED.equals(result.outcome);
		Integer refreshSet = null;
		Integer refreshCleared = null;
		Integer refreshUnchanged = null;
		String refreshError = null;
		if (success)
		{
			// see method Javadoc, "Refreshes Xref.goldenKey right after a SUCCESSFUL decision" - a reject is
			// included too (MameSuspectDecision#reject's own state can, in principle, change group membership
			// through the add-on's own bookkeeping) so this is never gated on pAccept.
			try
			{
				final String projectId = project.getOccurrencePrimaryKey().format();
				final String anchorKey = anchorRecord.getOccurrencePrimaryKey().format();
				final String suspectKey = suspectRecord.getOccurrencePrimaryKey().format();
				final Set<String> scope = RecordLineageReader.suspectDecisionRefreshScope(pSession, masterTable, anchorRecord, anchorKey, suspectRecord, suspectKey);
				final GoldenKeyRefresher.Result refresh = GoldenKeyRefresher.refreshScoped(pSession, pFactory, projectId, masterTablePath, masterDataspaceKey,
					masterDatasetName, scope);
				refreshSet = refresh.set;
				refreshCleared = refresh.cleared;
				refreshUnchanged = refresh.unchanged;
			}
			catch (final OperationException ex)
			{
				refreshError = ex.getMessage();
			}
			catch (final RuntimeException ex)
			{
				refreshError = ex.getClass().getName() + ": " + ex.getMessage();
			}
		}
		if (success && pTrackResults)
		{
			MatchingResultsStore.afterDecisions(pSession, pFactory, pProjectCode, project.getOccurrencePrimaryKey().format(), pEntityName,
				java.util.Collections.singletonList(new com.on.ebx.factory.matching.MatchResultsSnapshot.Decision(pAnchorKeyText.trim(), pSuspectKeyText.trim(), pAccept,
					pSession.getUserReference().getUserId(), java.time.Instant.now().toString())));
		}
		return new DecisionResult(success, result.outcome, result.detail, result.goldenKey, refreshSet, refreshCleared, refreshUnchanged, refreshError);
	}

	/**
	 * The refresh scope for {@link #decideSuspect}'s own post-decision {@code Xref.goldenKey} refresh
	 * (2026-09-23, "Keeping Xref.goldenKey fresh") - the suspect, the anchor, and every record that now
	 * shares either one's golden, via {@link #resolveContributingKeys} (public-API refactor, 2026-09-24: the
	 * exact same group-based derivation {@link #computeMerge} uses for a plain lineage read, rather than a
	 * second, subtly different query). Re-reads BOTH records' own state FRESH (never trusts anything computed
	 * before the accept/reject call) since either one - or neither - may now be {@code MERGED}, depending on
	 * whether {@code acceptSuspectsAndMergeGroups} itself performed an automatic merge ({@link
	 * MameSuspectDecision}'s own Javadoc: it can, on this build).
	 */
	private static Set<String> suspectDecisionRefreshScope(final Session pSession, final AdaptationTable pMasterTable, final Adaptation pAnchorRecord,
		final String pAnchorKey, final Adaptation pSuspectRecord, final String pSuspectKey)
	{
		final Set<String> scope = new LinkedHashSet<String>();
		scope.add(pAnchorKey);
		scope.add(pSuspectKey);
		scope.addAll(RecordLineageReader.resolveContributingKeys(pSession, pMasterTable, pAnchorRecord, pAnchorKey));
		scope.addAll(RecordLineageReader.resolveContributingKeys(pSession, pMasterTable, pSuspectRecord, pSuspectKey));
		return scope;
	}

	/**
	 * A record's own effective golden key, plus every OTHER record already MERGED into that same golden's
	 * group - public-API refactor, 2026-09-24 (CLAUDE.md &sect;9), replacing the earlier reverse {@code
	 * RecordMetadata.goldenId=...} metadataset scan. {@code pOwnKey} is returned alone (a size-1 set) when the
	 * add-on is unavailable, the record carries no group, or the group itself could not be resolved - see
	 * {@link #mergedContributorKeys}'s own Javadoc: measured live 2026-09-24, this derivation is correct.
	 */
	private static Set<String> resolveContributingKeys(final Session pSession, final AdaptationTable pMasterTable, final Adaptation pRecord, final String pOwnKey)
	{
		final Set<String> out = new LinkedHashSet<String>();
		final MameRecordState.Result state = MameRecordState.get().read(pRecord);
		final String goldenKey = state.addonPresent && "MERGED".equals(state.state) && state.goldenKey != null ? state.goldenKey : pOwnKey;
		out.add(goldenKey);
		if (state.addonPresent && state.groupId != null)
		{
			java.math.BigDecimal groupIdValue;
			try
			{
				groupIdValue = new java.math.BigDecimal(state.groupId);
			}
			catch (final RuntimeException ex)
			{
				groupIdValue = null; // an unparsable groupId - no group to scan, same "never guess" discipline as computeMerge
			}
			if (groupIdValue != null)
			{
				final MameGroups.Result groupResult = MameGroups.get().read(pSession, pMasterTable, groupIdValue);
				if (groupResult.addonPresent && groupResult.found)
				{
					out.addAll(RecordLineageReader.mergedContributorKeys(groupResult));
				}
			}
		}
		return out;
	}

	private static String recordNotFoundMessage(final String pEntityName, final String pKeyText, final String pProjectCode)
	{
		return "No " + pEntityName + " record with key " + pKeyText + " in project " + pProjectCode + ".";
	}

	// ==================================================================================================== sources

	private static final class ResolvedSource
	{
		final String sourceSystem;
		/** True when the caller's session may not see this source's landing OR staging record at all (permissions, fact 2) - the whole {@code RecordLineage.SourceChain} then renders as system-name-only, and every field contribution from this source is restricted too, regardless of field-level permission. */
		final boolean restricted;
		final String sourceId;
		final String landingDataset;
		final String landingTable;
		final String landingKey;
		final String loadId;
		final String landingStatus;
		final Date receivedAt;
		final String stagingKey;
		final Adaptation landingRow;
		final Adaptation stagingRow;

		ResolvedSource(final String pSourceSystem, final boolean pRestricted, final String pSourceId, final String pLandingDataset, final String pLandingTable,
			final String pLandingKey, final String pLoadId, final String pLandingStatus, final Date pReceivedAt, final String pStagingKey, final Adaptation pLandingRow,
			final Adaptation pStagingRow)
		{
			this.sourceSystem = pSourceSystem;
			this.restricted = pRestricted;
			this.sourceId = pSourceId;
			this.landingDataset = pLandingDataset;
			this.landingTable = pLandingTable;
			this.landingKey = pLandingKey;
			this.loadId = pLoadId;
			this.landingStatus = pLandingStatus;
			this.receivedAt = pReceivedAt;
			this.stagingKey = pStagingKey;
			this.landingRow = pLandingRow;
			this.stagingRow = pStagingRow;
		}
	}

	/**
	 * One contributing master key's own {@code Xref} row (at most one exists per master key - {@code
	 * ebx.MasterLoader.writeXref} never updates {@code masterKey} more than once per staged source, and a
	 * merge never rewrites {@code Xref} itself, `docs/MASTER-ZONE.md` sec 3.4), plus the landing and staging
	 * rows it points at. Returns null when no {@code Xref} row exists for this master key at all - a real,
	 * unsurprising case for an add-on-{@code autoCreated} golden that has no source row of its own, never an
	 * error.
	 */
	private static ResolvedSource resolveSource(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityName,
		final AdaptationTable pMasterTable, final String pMasterKey, final AdaptationHome pLandingHome, final AdaptationTable pStagingTable)
	{
		// volume lot 2a (2026-09-28, docs/VOLUME.md, task 3/7): DERIVE the Xref row from the master record's
		// own sourceSystem/sourceId (XrefByMasterRecord) instead of a project+masterTable+masterKey predicate
		// scan - see that class' own Javadoc for why (a declared <index> is a no-op on EBX 6.3, coordinator,
		// 2026-09-28).
		// second permissions pass (2026-10-14): a contributing (merged) or suspect master record the caller cannot see is not cited at all - not even
		// as "restricted" (that would still say it exists and which system it came from)
		Adaptation contributing = null;
		try
		{
			contributing = pMasterTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pMasterKey));
		}
		catch (final RuntimeException malformed)
		{
			contributing = null;
		}
		if (contributing != null && RecordLineageReader.isHidden(pSession, contributing))
		{
			return null;
		}
		final Adaptation xref = XrefByMasterRecord.lookup(pFactory, pProjectId, pEntityName, pMasterTable, pStagingTable, pMasterKey);
		if (xref == null)
		{
			return null;
		}
		final String sourceSystem = xref.getString(FactoryPaths.Xref.SOURCE_SYSTEM);
		final String sourceId = xref.getString(FactoryPaths.Xref.SOURCE_ID);
		final String landingDataset = xref.getString(FactoryPaths.Xref.LANDING_DATASET);
		final String landingTable = xref.getString(FactoryPaths.Xref.LANDING_TABLE);
		final String landingKey = xref.getString(FactoryPaths.Xref.LANDING_KEY);
		final String loadId = xref.getString(FactoryPaths.Xref.LOAD_ID);
		final String stagingKey = xref.getString(FactoryPaths.Xref.STAGING_KEY);

		Adaptation landingRow = null;
		String landingStatus = null;
		Date receivedAt = null;
		if (pLandingHome != null && !RecordLineageReader.blank(landingDataset) && !RecordLineageReader.blank(landingTable) && !RecordLineageReader.blank(landingKey))
		{
			final Adaptation landingDatasetAdaptation = pLandingHome.findAdaptationOrNull(AdaptationName.forName(landingDataset));
			if (landingDatasetAdaptation != null)
			{
				try
				{
					final AdaptationTable landingTableAdaptation = landingDatasetAdaptation.getTable(Path.parse("/root/" + landingTable));
					landingRow = landingTableAdaptation == null ? null : landingTableAdaptation.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(landingKey));
				}
				catch (final RuntimeException ex)
				{
					landingRow = null; // a DRIFTED/renamed landing table - never fails the whole lineage read over one contributing source
				}
				if (landingRow != null)
				{
					landingStatus = RecordLineageReader.safeString(landingRow, FactoryPaths.Landing.STATUS);
					final Object rawReceivedAt = RecordLineageReader.safeGet(landingRow, FactoryPaths.Landing.RECEIVED_AT);
					receivedAt = rawReceivedAt instanceof Date ? (Date) rawReceivedAt : null;
				}
			}
		}

		Adaptation stagingRow = null;
		if (pStagingTable != null && !RecordLineageReader.blank(stagingKey))
		{
			try
			{
				stagingRow = pStagingTable.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(stagingKey));
			}
			catch (final RuntimeException ex)
			{
				stagingRow = null;
			}
		}

		// permissions, fact 2: EITHER record hidden restricts the whole source - never partially, since a
		// landing-only or staging-only view of "this source" would still leak that the other half exists.
		final boolean restricted = RecordLineageReader.isHidden(pSession, landingRow) || RecordLineageReader.isHidden(pSession, stagingRow);
		// second permissions pass (2026-10-14): a source whose landing or staging record is hidden is no longer cited (it was cited as "restricted",
		// with its system name): what the caller cannot see is left out, never announced
		if (restricted)
		{
			return null;
		}

		return new ResolvedSource(sourceSystem, restricted, sourceId, landingDataset, landingTable, landingKey, loadId, landingStatus, receivedAt, stagingKey, landingRow,
			stagingRow);
	}

	// ============================================================================================== field origins

	private static final class FieldOrigin
	{
		final String landingColumn;
		final List<String> cleaningSteps;

		FieldOrigin(final String pLandingColumn, final List<String> pCleaningSteps)
		{
			this.landingColumn = pLandingColumn;
			this.cleaningSteps = pCleaningSteps;
		}
	}

	/**
	 * Field name -&gt; source system -&gt; where its value comes from, built from the entity's own ACCEPTED-or-
	 * {@code APPLIED} {@code AttributeMapping} rows (one per (field, source table) pair - a canonical entity fed
	 * by several members has one mapping row per member, exactly the shape {@code ebx.StagingLoader.readMappings}
	 * already resolves the same way, reused here read-only).
	 */
	private static Map<String, Map<String, FieldOrigin>> readFieldOrigins(final Adaptation pFactory, final String pEntityId, final List<String> pFieldNames)
	{
		final Map<String, Map<String, FieldOrigin>> out = new LinkedHashMap<String, Map<String, FieldOrigin>>();
		if (pEntityId == null)
		{
			return out;
		}
		final Map<String, RecordLineageReader.LandingColumnRef> columnCache = new HashMap<String, RecordLineageReader.LandingColumnRef>();
		final Map<String, RecordLineageReader.LandingTableRef> tableCache = new HashMap<String, RecordLineageReader.LandingTableRef>();
		final Map<String, String> sourceSystemCache = new HashMap<String, String>();

		final RequestResult attributeRows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation attributeRow = attributeRows.nextAdaptation(); attributeRow != null; attributeRow = attributeRows.nextAdaptation())
			{
				final String attributeName = attributeRow.getString(FactoryPaths.StagingAttribute.NAME);
				if (!pFieldNames.contains(attributeName))
				{
					continue;
				}
				final String attributeId = attributeRow.getOccurrencePrimaryKey().format();
				final Map<String, FieldOrigin> bySource = new LinkedHashMap<String, FieldOrigin>();
				final RequestResult mappingRows = pFactory.getTable(FactoryPaths.ATTRIBUTE_MAPPING).createRequestResult("./attribute=" + CatalogReader.quote(attributeId));
				try
				{
					for (Adaptation mappingRow = mappingRows.nextAdaptation(); mappingRow != null; mappingRow = mappingRows.nextAdaptation())
					{
						if (!TechnicalKeyRule.isAcceptedLike(mappingRow.getString(FactoryPaths.AttributeMapping.STATUS)))
						{
							continue;
						}
						final String landingColumnId = mappingRow.getString(FactoryPaths.AttributeMapping.LANDING_COLUMN);
						final RecordLineageReader.LandingColumnRef column = RecordLineageReader.landingColumn(pFactory, landingColumnId, columnCache);
						if (column == null)
						{
							continue;
						}
						final RecordLineageReader.LandingTableRef table = RecordLineageReader.landingTableRef(pFactory, column.landingTableId, tableCache);
						if (table == null)
						{
							continue;
						}
						final String sourceSystem = RecordLineageReader.projectSourceField(pFactory, table.projectSourceId, FactoryPaths.ProjectSource.SOURCE_SYSTEM,
							sourceSystemCache);
						if (sourceSystem == null)
						{
							continue;
						}
						final String mappingId = mappingRow.getOccurrencePrimaryKey().format();
						bySource.put(sourceSystem, new FieldOrigin(column.fieldName, RecordLineageReader.readCleaningStepKinds(pFactory, mappingId)));
					}
				}
				finally
				{
					mappingRows.close();
				}
				out.put(attributeName, bySource);
			}
		}
		finally
		{
			attributeRows.close();
		}
		return out;
	}

	/** This mapping's ACCEPTED-or-{@code APPLIED} {@code MappingStep} kinds, in {@code position} order - the same eligibility {@code ebx.StagingLoader.readCleaningSteps} applies, kinds only (this screen shows what ran, not its arguments). */
	private static List<String> readCleaningStepKinds(final Adaptation pFactory, final String pMappingId)
	{
		final List<Adaptation> rows = new ArrayList<Adaptation>();
		final RequestResult stepRows = pFactory.getTable(FactoryPaths.MAPPING_STEP).createRequestResult("./mapping=" + CatalogReader.quote(pMappingId));
		try
		{
			for (Adaptation row = stepRows.nextAdaptation(); row != null; row = stepRows.nextAdaptation())
			{
				if (TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.MappingStep.STATUS)))
				{
					rows.add(row);
				}
			}
		}
		finally
		{
			stepRows.close();
		}
		java.util.Collections.sort(rows, new java.util.Comparator<Adaptation>()
		{
			@Override
			public int compare(final Adaptation pLeft, final Adaptation pRight)
			{
				final Object leftPosition = pLeft.get(FactoryPaths.MappingStep.POSITION);
				final Object rightPosition = pRight.get(FactoryPaths.MappingStep.POSITION);
				final int left = leftPosition instanceof Number ? ((Number) leftPosition).intValue() : Integer.MAX_VALUE;
				final int right = rightPosition instanceof Number ? ((Number) rightPosition).intValue() : Integer.MAX_VALUE;
				return Integer.compare(left, right);
			}
		});
		final List<String> out = new ArrayList<String>();
		for (final Adaptation row : rows)
		{
			out.add(row.getString(FactoryPaths.MappingStep.KIND));
		}
		return out;
	}

	/**
	 * Every distinct cleaning-step KIND the entity's accepted mappings run for {@code pSourceSystem}, across
	 * every business field, first-seen order - fix, 2026-09-22 (task brief item D): unlike a plain
	 * contributing source (whose ribbon already reads its steps off actual field CONTRIBUTIONS), a suspect
	 * has no contributions yet, so its own cleaning summary must come from the MAPPING itself (which fields
	 * exist for this source, and what steps they run) rather than from data this record has not yet
	 * contributed. Same {@code originsByField} this method's caller already built for every contributing
	 * source's own field contributions - reused, never re-read.
	 */
	private static List<String> cleaningKindsForSourceSystem(final Map<String, Map<String, FieldOrigin>> pOriginsByField, final String pSourceSystem)
	{
		final Set<String> kinds = new LinkedHashSet<String>();
		if (pSourceSystem != null)
		{
			for (final Map<String, FieldOrigin> bySource : pOriginsByField.values())
			{
				final FieldOrigin origin = bySource == null ? null : bySource.get(pSourceSystem);
				if (origin != null && origin.cleaningSteps != null)
				{
					kinds.addAll(origin.cleaningSteps);
				}
			}
		}
		return new ArrayList<String>(kinds);
	}

	private static final class LandingColumnRef
	{
		final String fieldName;
		final String landingTableId;

		LandingColumnRef(final String pFieldName, final String pLandingTableId)
		{
			this.fieldName = pFieldName;
			this.landingTableId = pLandingTableId;
		}
	}

	private static LandingColumnRef landingColumn(final Adaptation pFactory, final String pLandingColumnId, final Map<String, LandingColumnRef> pCache)
	{
		if (pLandingColumnId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingColumnId))
		{
			return pCache.get(pLandingColumnId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_COLUMN).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingColumnId));
		final LandingColumnRef ref = row == null ? null
			: new LandingColumnRef(row.getString(FactoryPaths.LandingColumn.FIELD_NAME), row.getString(FactoryPaths.LandingColumn.LANDING_TABLE));
		pCache.put(pLandingColumnId, ref);
		return ref;
	}

	private static final class LandingTableRef
	{
		final String tableName;
		final String projectSourceId;

		LandingTableRef(final String pTableName, final String pProjectSourceId)
		{
			this.tableName = pTableName;
			this.projectSourceId = pProjectSourceId;
		}
	}

	private static LandingTableRef landingTableRef(final Adaptation pFactory, final String pLandingTableId, final Map<String, LandingTableRef> pCache)
	{
		if (pLandingTableId == null)
		{
			return null;
		}
		if (pCache.containsKey(pLandingTableId))
		{
			return pCache.get(pLandingTableId);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.LANDING_TABLE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pLandingTableId));
		final LandingTableRef ref = row == null ? null
			: new LandingTableRef(row.getString(FactoryPaths.LandingTable.TABLE_NAME), row.getString(FactoryPaths.LandingTable.PROJECT_SOURCE));
		pCache.put(pLandingTableId, ref);
		return ref;
	}

	private static String projectSourceField(final Adaptation pFactory, final String pProjectSourceId, final Path pField, final Map<String, String> pCache)
	{
		if (pProjectSourceId == null)
		{
			return null;
		}
		final String cacheKey = pProjectSourceId + "#" + pField.format();
		if (pCache.containsKey(cacheKey))
		{
			return pCache.get(cacheKey);
		}
		final Adaptation row = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pProjectSourceId));
		final String value = row == null ? null : row.getString(pField);
		pCache.put(cacheKey, value);
		return value;
	}

	// ==================================================================================================== match plan

	/** The entity's own {@code MatchPlan.status}, or null when none exists yet - see class Javadoc for why this gates {@link MameRecordState} entirely. */
	private static String readMatchPlanStatus(final Adaptation pFactory, final String pEntityId)
	{
		if (pEntityId == null)
		{
			return null;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			return row == null ? null : row.getString(FactoryPaths.MatchPlan.STATUS);
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * The entity's own accepted {@code MatchPlan.requirements} (a JSON string, {@code
	 * master.MatchPlanRules}/{@code master.MatchPlanLlmPass}'s own shape - a flat {@code decisionTree}
	 * {id, function, fields[{field, algorithm}], onTrue, onFalse}, or a pre-lot {@code tiers[]} cascade,
	 * either read via {@link DecisionTreePlan#readFrom}), flattened into one {@link RecordLineage.PlanFieldRef}
	 * per DISTINCT field name, first occurrence order across ALL of the plan's nodes (union - binary-decision-
	 * tree-screen lot, 2026-09-22: unchanged for a plain cascade, complete for a genuinely branching tree) -
	 * the comparison panel's own "fields the plan compares" (task brief, 2026-09-22, "decide on a suspect"
	 * lot). Never fails the whole read: a missing plan, a blank {@code requirements}, or malformed JSON all
	 * answer an empty list, exactly as "no plan to show a comparison order from" (the comparison panel then
	 * shows every business field with no plan-first ordering - never an error).
	 */
	private static List<RecordLineage.PlanFieldRef> readMatchPlanFields(final Adaptation pFactory, final String pEntityId)
	{
		final List<RecordLineage.PlanFieldRef> out = new ArrayList<RecordLineage.PlanFieldRef>();
		if (pEntityId == null)
		{
			return out;
		}
		String requirementsJson = null;
		final RequestResult rows = pFactory.getTable(FactoryPaths.MATCH_PLAN).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			final Adaptation row = rows.nextAdaptation();
			requirementsJson = row == null ? null : row.getString(FactoryPaths.MatchPlan.REQUIREMENTS);
		}
		finally
		{
			rows.close();
		}
		if (RecordLineageReader.blank(requirementsJson))
		{
			return out;
		}
		try
		{
			final com.fasterxml.jackson.databind.JsonNode root = new com.fasterxml.jackson.databind.ObjectMapper().readTree(requirementsJson);
			final Set<String> seen = new LinkedHashSet<String>();
			// binary-decision-tree-screen lot (2026-09-22): walks the real tree via DecisionTreePlan (union of
			// ALL nodes' fields, pre-order) instead of the retired cascade-only compatibility view - unchanged
			// for a plain cascade, complete for a genuinely branching tree.
			for (final DecisionTreePlan.Node node : DecisionTreePlan.readFrom(root).nodes)
			{
				for (final DecisionTreePlan.FieldRef field : node.fields)
				{
					final String name = field.field;
					if (name == null || !seen.add(name))
					{
						continue;
					}
					out.add(new RecordLineage.PlanFieldRef(name, field.algorithm));
				}
			}
		}
		catch (final java.io.IOException ex)
		{
			// malformed requirements JSON - never fails the whole lineage read, see method Javadoc
		}
		catch (final RuntimeException ex)
		{
			// defensive - same reasoning
		}
		return out;
	}

	/**
	 * Self-heals a stale {@code Xref.goldenKey} the moment {@link #read} has the real truth in hand
	 * (2026-09-23, "Keeping Xref.goldenKey fresh") - {@code pGoldenKey}/{@code pContributingKeys} are exactly
	 * what {@link #computeMerge} just resolved from the add-on's own public record state ({@link
	 * MameRecordState}/{@link MameGroups}, public-API refactor 2026-09-24), so no second metadata read
	 * happens here, only an {@code Xref} read/compare/write. A cache that went stale after a
	 * steward merged/unmerged/changed state through the add-on's own STANDARD services (no hook exists on
	 * that path at all - the add-on's public API has no listener/callback type, confirmed by listing {@code
	 * ebx-addons.jar}) is corrected right here, on the very read that noticed the disagreement, rather than
	 * waiting for the next {@code matching/run} or an explicit {@code matching/refresh-golden-keys}.
	 * <p>
	 * <b>Gated on write access to the FACTORY dataset itself</b> ({@link #canReadWrite} on {@code pFactory} -
	 * the SAME permission signature {@code merge.group.actions.canAcceptSuspects} already uses, applied here
	 * to the dataset the {@code Xref} rows actually live in, not the master record being examined, since a
	 * factory read-only caller must never be able to write {@code Xref} as a side effect of merely looking at
	 * a lineage). A read-only caller triggers no write attempt at all.
	 * <p>
	 * <b>Never fails the read.</b> Every EBX call here is wrapped; a failure is logged ({@code
	 * com.orchestranetworks.service.LoggingCategory}) and swallowed - {@link #read} calls this purely for its
	 * side effect and never inspects a return value. The actual "which rows differ" decision is pure ({@link
	 * GoldenKeySelfHeal#rowsToWrite}, unit-tested) - this method only supplies the EBX read (current {@code
	 * Xref.goldenKey} per contributing master key) and the EBX write.
	 * <p>
	 * <b>Runs even for a single contributing key (2026-09-23, measured on golden 322 / member 290 after an
	 * Unmerge)</b> - a earlier version returned early on {@code pContributingKeys.size() <= 1} on the theory
	 * "an isolated record can never disagree with itself". That is wrong: {@code pContributingKeys} is the
	 * CURRENT truth, but {@code Xref.goldenKey} is a CACHE that can go stale independently of it. A record that
	 * is isolated NOW (its own {@link #computeMerge} resolved {@code contributingKeys == {itself}}) can still
	 * hold a stale {@code Xref.goldenKey} pointing at a golden it no longer belongs to - exactly what an
	 * add-on Unmerge produces: {@code RecordMetadata.goldenId} goes back to null (so the record is its own
	 * golden again, {@code goldenKey == pMasterKey}, size-1 {@code contributingKeys}), but nothing on the
	 * Unmerge path ever touches {@code Xref} (no hook exists, see this method's own Javadoc above), so the row
	 * keeps pointing at the golden that no longer claims it. The single-key case still degrades safely: the
	 * scoped {@code Xref} read below finds either a stale row (corrected, one write) or none at all - the
	 * add-on's own auto-created golden records never get an {@code Xref} row of their own, so a DELETED
	 * auto-created golden looked up directly (its own {@code contributingKeys} collapses to just itself once
	 * nothing points at it any more) resolves an empty {@code currentByMasterKey} and {@link
	 * GoldenKeySelfHeal#rowsToWrite} answers an empty map - nothing is written, exactly as before this fix.
	 */
	private static void selfHealGoldenKeys(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pEntityName,
		final AdaptationTable pMasterTable, final AdaptationTable pStagingTable, final String pMasterTablePath, final String pGoldenKey,
		final Set<String> pContributingKeys)
	{
		if (RecordLineageReader.blank(pGoldenKey) || pContributingKeys == null || pContributingKeys.isEmpty())
		{
			return; // nothing to correct: no golden key to correct TOWARDS, or nothing resolved to check
		}
		try
		{
			if (!RecordLineageReader.canReadWrite(pSession, pFactory))
			{
				return; // read-only caller - never even attempt the write (class Javadoc)
			}
			// volume lot 2a (2026-09-28, docs/VOLUME.md, task 3/7): each contributing key's own Xref row is
			// DERIVED (XrefByMasterRecord) from the master record's own sourceSystem/sourceId, never a
			// masterTable/masterKey-in-scope predicate scan (a declared <index> is a no-op on EBX 6.3,
			// coordinator, 2026-09-28). pContributingKeys is always small (a group's own members), so per-key
			// derivation is cheaper than any scan would have been anyway.
			final Map<String, Adaptation> rowsByMasterKey = new LinkedHashMap<String, Adaptation>();
			final Map<String, String> currentByMasterKey = new LinkedHashMap<String, String>();
			for (final String masterKey : pContributingKeys)
			{
				final Adaptation xrefRow = XrefByMasterRecord.lookup(pFactory, pProjectId, pEntityName, pMasterTable, pStagingTable, masterKey);
				if (xrefRow == null)
				{
					continue; // no Xref row for this key (e.g. an add-on auto-created golden) - nothing to correct
				}
				rowsByMasterKey.put(masterKey, xrefRow);
				currentByMasterKey.put(masterKey, xrefRow.getString(FactoryPaths.Xref.GOLDEN_KEY));
			}
			final Map<String, String> toWrite = GoldenKeySelfHeal.rowsToWrite(pGoldenKey, currentByMasterKey);
			if (toWrite.isEmpty())
			{
				return;
			}
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					for (final Map.Entry<String, String> entry : toWrite.entrySet())
					{
						final Adaptation row = rowsByMasterKey.get(entry.getKey());
						if (row == null)
						{
							continue;
						}
						final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
						value.setValue(entry.getValue(), FactoryPaths.Xref.GOLDEN_KEY);
						pContext.doModifyContent(row, value);
					}
				}
			});
			if (result.hasFailed())
			{
				RecordLineageReader.logGoldenKeySelfHealFailure(pMasterTablePath, result.getExceptionFullMessage(java.util.Locale.ENGLISH));
			}
		}
		catch (final RuntimeException ex)
		{
			RecordLineageReader.logGoldenKeySelfHealFailure(pMasterTablePath, ex.getClass().getName() + ": " + ex.getMessage());
		}
	}

	/** Same "log, never throw" convention {@code ebx.CapabilityApplier}'s own {@code LoggingCategoryHolder} uses - kept inline here (one call site) rather than a shared holder class. */
	private static void logGoldenKeySelfHealFailure(final String pMasterTablePath, final String pDetail)
	{
		try
		{
			com.orchestranetworks.service.LoggingCategory.getKernel()
				.error("[mdm-factory] Xref.goldenKey self-heal failed for " + pMasterTablePath + ": " + pDetail);
		}
		catch (final RuntimeException ignored)
		{
			// logging itself must never throw past this point - see method Javadoc, "never fails the read"
		}
	}

	private static final class MergeResult
	{
		final RecordLineage.MergeInfo info;
		final Set<String> contributingKeys;
		/** Null whenever {@link RecordLineage.MergeInfo#group} is null too - see that field's own Javadoc for exactly when. Carried separately (not just read back off {@code info.group}) purely so {@link #read} does not need to re-derive it from the assembled {@code MergeInfo}. */
		final RecordLineage.GroupInfo group;
		/** {@code RecordMetadata.mergingProcessId} of the GOLDEN record - null whenever unreadable or no real merge has run yet for this group (see {@link MameMetadatasetReads#readMergingProcessId}, no public equivalent). Feeds {@link FieldMergeLineageSource#read} in {@link #read}; never itself gates {@link #info}/{@link #group}. */
		final Integer mergingProcessId;
		/** Field VALUES of every pending suspect this reader could resolve - the comparison panel's own data (task brief, 2026-09-22, "decide on a suspect" lot). Never null; empty whenever {@link #group} carries no suspect. */
		final List<RecordLineage.SuspectFieldSet> suspectFieldSets;

		MergeResult(final RecordLineage.MergeInfo pInfo, final Set<String> pContributingKeys, final RecordLineage.GroupInfo pGroup, final Integer pMergingProcessId,
			final List<RecordLineage.SuspectFieldSet> pSuspectFieldSets)
		{
			this.info = pInfo;
			this.contributingKeys = pContributingKeys;
			this.group = pGroup;
			this.mergingProcessId = pMergingProcessId;
			this.suspectFieldSets = pSuspectFieldSets == null ? java.util.Collections.<RecordLineage.SuspectFieldSet>emptyList() : pSuspectFieldSets;
		}

		static MergeResult of(final RecordLineage.MergeInfo pInfo, final Set<String> pContributingKeys)
		{
			return new MergeResult(pInfo, pContributingKeys, null, null, null);
		}
	}

	private static Set<String> singleton(final String pKey)
	{
		final Set<String> out = new LinkedHashSet<String>();
		out.add(pKey);
		return out;
	}

	/**
	 * Determines {@link RecordLineage.MergeInfo} (including its {@link RecordLineage.MergeInfo#group}) and
	 * every contributing master key, with EVERY failure past the {@code MatchPlan.status} check caught here
	 * and turned into {@code METADATA_UNREADABLE} - never an exception reaching {@link #read} (coordinator
	 * review, 2026-09-21; see class Javadoc). {@link MameRecordState#read} already never throws on its own
	 * (its own Javadoc), and neither does {@link MameGroups#read} (its own Javadoc).
	 * <p>
	 * <b>Public-API refactor, 2026-09-24 (CLAUDE.md &sect;9 "use the public API wherever it can do the
	 * job").</b> Until this fix, the contributing-key list came from a reverse {@code ./goldenId=...} scan of
	 * the add-on's own {@code RecordMetadata} metadataset table, and the {@code isolated} flag from that same
	 * table's {@code isolated} field. Both now come from a SINGLE {@link MameGroups#read} call, resolved
	 * once here and reused for the contributing-key scan ({@link #mergedContributorKeys} - measured correct
	 * live, see its own Javadoc), the {@code isolated} flag ({@link #excludingGolden}'s own Javadoc below),
	 * and the displayed {@link RecordLineage.GroupInfo} ({@link #buildGroup}, which no longer re-fetches the
	 * group itself). Only the golden's own {@code RecordMetadata.mergingProcessId} still has no public
	 * equivalent - see {@link MameMetadatasetReads}'s own Javadoc for why.
	 * <p>
	 * <b>Measured live 2026-09-24 on Companies_Master/Master/root/Company, record 217</b> (a golden alone in
	 * its own group - metadataset {@code isolated: true}, {@code hasSuspects: true}): {@code
	 * Group.getMatchedRecords()} is NOT empty for it, so the naive {@code matchedRecords.isEmpty()} this
	 * method used until this fix answered {@code isolated == false} - the record's own state then rendered
	 * plain "Golden record" instead of "Golden record &middot; 1 suspect(s) pending" ({@code
	 * GOLDEN_WITH_SUSPECTS}, see {@link RecordLineage#deriveState}). The most likely explanation, confirmed by
	 * the same measurement: {@code getMatchedRecords()} includes the group's OWN golden record among its
	 * "matched" entries - not only genuinely other members. The same session confirmed goldens 323/324's
	 * {@link #mergedContributorKeys} stayed correct (their MERGED members were found as expected), so that
	 * derivation is untouched by this fix; only the golden-inclusive-ness of the raw list needed accounting
	 * for, done once by {@link #excludingGolden} and applied both here (isolation) and in {@link #buildGroup}
	 * (displayed member count/list) - see that method's own Javadoc for exactly why a state check alone
	 * (as {@link #mergedContributorKeys} already does) is not enough for {@code isolated}: the golden's own
	 * state is {@code GOLDEN}, not {@code MERGED}, so it would never be filtered by state, only by key.
	 */
	private static MergeResult computeMerge(final Repository pRepository, final Adaptation pFactory, final String pEntityId, final Adaptation pMasterRecord,
		final String pMasterKey, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath, final AdaptationTable pMasterTable,
		final Session pSession, final List<String> pTitleFieldNames, final List<String> pFieldNames)
	{
		try
		{
			final String matchPlanStatus = RecordLineageReader.readMatchPlanStatus(pFactory, pEntityId);
			if (!"APPLIED".equals(matchPlanStatus))
			{
				return MergeResult.of(RecordLineage.MergeInfo.absent(RecordLineage.MergeInfo.NO_MATCHING_YET), RecordLineageReader.singleton(pMasterKey));
			}
			final MameRecordState.Result stateResult = MameRecordState.get().read(pMasterRecord);
			if (!stateResult.addonPresent)
			{
				return MergeResult.of(RecordLineage.MergeInfo.absent(RecordLineage.MergeInfo.ADDON_NOT_AVAILABLE), RecordLineageReader.singleton(pMasterKey));
			}
			if (stateResult.state == null)
			{
				return MergeResult.of(RecordLineage.MergeInfo.absent(RecordLineage.MergeInfo.NO_METADATA), RecordLineageReader.singleton(pMasterKey));
			}
			final String goldenKey = "MERGED".equals(stateResult.state) && stateResult.goldenKey != null ? stateResult.goldenKey : pMasterKey;

			// group (matched + suspects), through the add-on's public group API (ebx.MameGroups) - resolved off
			// THIS record's own groupId (a MERGED member shares its golden's groupId - same assumption #buildGroup
			// already made before this refactor), read ONCE and reused below for the contributing-key scan, the
			// isolated flag, and the displayed GroupInfo (see method Javadoc).
			java.math.BigDecimal groupIdValue = null;
			if (stateResult.groupId != null)
			{
				try
				{
					groupIdValue = new java.math.BigDecimal(stateResult.groupId);
				}
				catch (final RuntimeException ex)
				{
					groupIdValue = null; // an unparsable groupId - merge.group simply stays absent, never a guess
				}
			}
			final MameGroups.Result groupResult = groupIdValue == null ? null : MameGroups.get().read(pSession, pMasterTable, groupIdValue);
			final boolean groupResolved = groupResult != null && groupResult.addonPresent && groupResult.found;

			final Set<String> contributingKeys = new LinkedHashSet<String>();
			contributingKeys.add(goldenKey);
			if (groupResolved)
			{
				contributingKeys.addAll(RecordLineageReader.mergedContributorKeys(groupResult));
			}

			// isolated flag - derived from the group itself (public-API refactor, see method Javadoc): a single
			// confirmed member besides the golden itself (excludingGolden non-empty) means NOT isolated - see
			// excludingGolden's own Javadoc for why the golden's own key must be filtered out of the raw
			// matchedRecords list first (measured live 2026-09-24: getMatchedRecords() includes the golden).
			// Stays null/unknown (never guessed) when no group could be resolved at all - RecordLineage.
			// deriveState then never derives GOLDEN_WITH_SUSPECTS from an unreadable flag, same as before.
			final Boolean isolated = groupResolved
				? Boolean.valueOf(RecordLineageReader.excludingGolden(RecordLineageReader.formatKeysOrEmpty(groupResult.matchedRecords), goldenKey).isEmpty())
				: null;

			RecordLineage.GroupInfo group = null;
			final List<RecordLineage.SuspectFieldSet> suspectFieldSets = new ArrayList<RecordLineage.SuspectFieldSet>();
			if (groupResolved)
			{
				final boolean canWriteMaster = RecordLineageReader.canReadWrite(pSession, pMasterRecord);
				group = RecordLineageReader.buildGroup(pSession, groupResult, groupIdValue, goldenKey, pRepository, pMasterDataspaceKey, pMasterDatasetName,
					pMasterTablePath, canWriteMaster, pTitleFieldNames, pFieldNames, suspectFieldSets);
			}

			// the golden's own RecordMetadata.mergingProcessId (2026-09-22, "decide on a suspect" lot) - no
			// public equivalent (see MameMetadatasetReads' own Javadoc); feeds FieldMergeLineageSource#read in
			// #read, never gates anything above (a record can be a perfectly good GOLDEN with no recorded merge
			// process at all - see FieldMergeLineageSource.NO_MERGE_YET).
			final Integer mergingProcessId = MameMetadatasetReads.readMergingProcessId(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, goldenKey);

			final String derivedState = RecordLineage.deriveState(stateResult.state, isolated, group);
			return new MergeResult(RecordLineage.MergeInfo.present(stateResult.state, stateResult.groupId, goldenKey, stateResult.autoCreated, derivedState, group),
				contributingKeys, group, mergingProcessId, suspectFieldSets);
		}
		catch (final RuntimeException ex)
		{
			return MergeResult.of(RecordLineage.MergeInfo.absent(RecordLineage.MergeInfo.METADATA_UNREADABLE, ex.getClass().getName() + ": " + ex.getMessage()),
				RecordLineageReader.singleton(pMasterKey));
		}
	}

	/**
	 * "Every OTHER record already MERGED into this golden's group" - the contributing-key derivation, replacing
	 * the earlier reverse {@code RecordMetadata.goldenId=...} metadataset scan (public-API refactor, 2026-09-24,
	 * CLAUDE.md &sect;9). <b>Measured live 2026-09-24</b> (Companies_Master/Master/root/Company, goldens 323
	 * and 324): this filter is CORRECT - {@code Group.getMatchedRecords()} was confirmed to include members
	 * already in state {@code MERGED}, and filtering that list down to state {@code MERGED} only reproduced
	 * exactly the members the removed metadataset scan matched. The SAME measurement also showed {@code
	 * getMatchedRecords()} includes the group's own GOLDEN record (see {@link #excludingGolden}'s own Javadoc,
	 * {@link #computeMerge}'s own Javadoc "Measured live 2026-09-24") - which does NOT affect this method: the
	 * golden is never itself in state {@code MERGED} (it is what everything else merges INTO, not the other
	 * way round), so it is already excluded here by the {@code state == MERGED} check below, without needing
	 * {@link #excludingGolden}'s key-based filter at all. {@code RecordLineage.GroupInfo#memberCount}'s own
	 * {@code matched.size() + 1} comment is exact for the SAME reason, once {@link #buildGroup} applies {@link
	 * #excludingGolden} to the DISPLAYED matched list (a different list than this method's MERGED-only one).
	 */
	private static Set<String> mergedContributorKeys(final MameGroups.Result pGroupResult)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final Adaptation record : pGroupResult.matchedRecords)
		{
			final MameRecordState.Result state = MameRecordState.get().read(record);
			if (state.addonPresent && "MERGED".equals(state.state))
			{
				final String key = RecordLineageReader.formatKeyOrNull(record);
				if (key != null)
				{
					out.add(key);
				}
			}
		}
		return out;
	}

	/** {@code Adaptation} -&gt; formatted primary key, dropping anything {@link #formatKeyOrNull} could not format - the {@code Adaptation}-bound half of {@link #excludingGolden}'s own key-list contract, kept separate so the pure decision itself stays EBX-free and directly unit-testable. */
	private static List<String> formatKeysOrEmpty(final List<Adaptation> pRecords)
	{
		final List<String> out = new ArrayList<String>();
		for (final Adaptation record : pRecords)
		{
			final String key = RecordLineageReader.formatKeyOrNull(record);
			if (key != null)
			{
				out.add(key);
			}
		}
		return out;
	}

	/**
	 * Pure decision (measured live 2026-09-24, Companies_Master/Master/root/Company record 217 - see {@link
	 * #computeMerge}'s own Javadoc for the full writeup): {@code Group.getMatchedRecords()} was found to
	 * include the group's OWN golden record among its "matched" entries, even for a golden that is alone in
	 * its group (no other member at all) - so a raw {@code matchedRecords.isEmpty()}/{@code size()} can never
	 * be used directly to decide isolation or to count members; the golden's own formatted key must be
	 * filtered out of the list FIRST. This holds whether or not the add-on happens to include the record on a
	 * given build - filtering a key that turns out to already be absent is a no-op, never wrong.
	 * <p>
	 * Takes and returns plain formatted keys (never an {@code Adaptation}), so this exact filtering logic is
	 * reachable by a unit test without any EBX/add-on classpath - same "small, directly testable seam" pattern
	 * as {@code FieldMergeLineageSource#readFromItems} (see {@code FieldMergeLineageSourceTest}). Order is
	 * preserved; a {@code null} entry in {@code pMatchedKeys} is dropped defensively (should not happen - every
	 * caller already filters nulls via {@link #formatKeyOrNull}/{@link #formatKeysOrEmpty}).
	 * <p>
	 * <b>{@link #mergedContributorKeys} does not need this filter</b>: it already keeps only records whose own
	 * state is {@code MERGED}, and the group's golden is never itself in state {@code MERGED} (it is what
	 * everything else merges INTO) - so the golden is excluded there by state, never by key, and stays
	 * unmodified by this fix (task constraint: "only change what the measurement supports").
	 */
	static List<String> excludingGolden(final List<String> pMatchedKeys, final String pGoldenKey)
	{
		final List<String> out = new ArrayList<String>();
		for (final String key : pMatchedKeys)
		{
			if (key != null && !key.equals(pGoldenKey))
			{
				out.add(key);
			}
		}
		return out;
	}

	/**
	 * Builds {@link RecordLineage.GroupInfo} from an ALREADY-RESOLVED {@link MameGroups.Result} - public-API
	 * refactor, 2026-09-24: {@link #computeMerge} now reads the group ONCE and passes it here (this method no
	 * longer performs its own {@link MameGroups#read} call, unlike before this fix), so the same group also
	 * feeds the contributing-key scan and the {@code isolated} flag without a second lookup. Callers must
	 * check {@code pGroupResult.addonPresent && pGroupResult.found} before calling this method - see {@link
	 * #computeMerge}'s own {@code groupResolved} guard.
	 * <p>
	 * <b>Excludes the golden's own record from {@code matched}/{@link RecordLineage.GroupInfo#memberCount}</b>
	 * (fix, 2026-09-24, {@link #excludingGolden}'s own Javadoc) - {@code pGroupResult.matchedRecords} was
	 * measured live to include the group's golden among its own entries, so without this filter the golden
	 * would have shown up as one of its own "matched" members and {@code memberCount} (still computed as
	 * {@code matched.size() + 1}) would have double-counted it.
	 */
	private static RecordLineage.GroupInfo buildGroup(final Session pSession, final MameGroups.Result pGroupResult, final java.math.BigDecimal pGroupId,
		final String pGoldenKey, final Repository pRepository, final String pMasterDataspaceKey, final String pMasterDatasetName, final String pMasterTablePath,
		final boolean pCanWriteMaster, final List<String> pTitleFieldNames, final List<String> pFieldNames, final List<RecordLineage.SuspectFieldSet> pOutSuspectFieldSets)
	{
		final MameGroups.Result result = pGroupResult;
		// the golden itself must never appear as one of its own "matched" members - see excludingGolden's own
		// Javadoc for the live measurement (2026-09-24) that Group.getMatchedRecords() includes it. Filtered by
		// KEY (formatKeyOrNull, never permission-gated) before buildGroupMember, so a restricted golden record
		// (should not happen - the caller already resolved it) can never end up counted twice.
		final Set<String> otherMatchedKeys = new HashSet<String>(RecordLineageReader.excludingGolden(RecordLineageReader.formatKeysOrEmpty(result.matchedRecords), pGoldenKey));
		final List<RecordLineage.GroupMember> matched = new ArrayList<RecordLineage.GroupMember>();
		for (final Adaptation record : result.matchedRecords)
		{
			final String key = RecordLineageReader.formatKeyOrNull(record);
			if (key != null && !otherMatchedKeys.contains(key))
			{
				continue; // the group's own golden - see excludingGolden's own Javadoc
			}
			// second permissions pass (2026-10-14): a member the caller cannot see is left out - not cited as "restricted", not counted
			if (RecordLineageReader.isHidden(pSession, record))
			{
				continue;
			}
			final RecordLineage.GroupMember member = RecordLineageReader.buildGroupMember(pSession, record, null, pTitleFieldNames);
			if (member != null)
			{
				matched.add(member);
			}
		}
		final List<RecordLineage.GroupMember> suspects = new ArrayList<RecordLineage.GroupMember>();
		for (final Adaptation record : result.suspectRecords)
		{
			// second permissions pass (2026-10-14): a suspect the caller cannot see is left out (no key, no lane, no field set, no count)
			if (RecordLineageReader.isHidden(pSession, record))
			{
				continue;
			}
			final String suspectKey = RecordLineageReader.formatKeyOrNull(record);
			final String processId = suspectKey == null ? null
				: MameMetadatasetReads.readSuspectedProcessId(pRepository, pMasterDataspaceKey, pMasterDatasetName, pMasterTablePath, pGoldenKey, suspectKey);
			final RecordLineage.GroupMember member = RecordLineageReader.buildGroupMember(pSession, record, processId, pTitleFieldNames);
			if (member != null)
			{
				suspects.add(member);
			}
			// suspect field VALUES, for the "compare with suspects" panel (task brief, 2026-09-22, "decide on a
			// suspect" lot) - built here, off the SAME raw Adaptation the group API just handed back, rather
			// than a second lookup by key later (this is the one place this reader still holds it).
			if (pOutSuspectFieldSets != null && suspectKey != null)
			{
				pOutSuspectFieldSets.add(RecordLineageReader.buildSuspectFieldSet(pSession, record, suspectKey, pFieldNames));
			}
		}
		final boolean canAcceptSuspects = pCanWriteMaster && !suspects.isEmpty();
		return new RecordLineage.GroupInfo(pGroupId.toPlainString(), result.groupLabel, matched.size() + 1, matched, suspects,
			result.matchedTruncated || result.suspectTruncated, canAcceptSuspects);
	}

	/**
	 * {@link RecordLineage.SuspectFieldSet} for one suspect master-table record - the SAME all-or-nothing
	 * hidden-record rule as {@link #buildGroupMember}, and the SAME per-field permission check {@link #read}
	 * already applies to the anchor's own {@code recordFields} ({@link #isFieldHidden}). {@code pFieldNames}
	 * is the anchor's own business field list (same table/entity, by construction - see {@link
	 * #buildGroupMember}'s own Javadoc for the identical reasoning about {@code pTitleFieldNames}).
	 */
	private static RecordLineage.SuspectFieldSet buildSuspectFieldSet(final Session pSession, final Adaptation pRecord, final String pMasterKey,
		final List<String> pFieldNames)
	{
		if (RecordLineageReader.isHidden(pSession, pRecord))
		{
			return RecordLineage.SuspectFieldSet.restricted(pMasterKey);
		}
		final Map<String, RecordLineage.FieldValue> fields = new LinkedHashMap<String, RecordLineage.FieldValue>();
		for (final String field : pFieldNames)
		{
			if (RecordLineageReader.isFieldHidden(pSession, pRecord, field))
			{
				fields.put(field, RecordLineage.FieldValue.restricted());
			}
			else
			{
				fields.put(field, RecordLineage.FieldValue.of(RecordLineageReader.safeString(pRecord, field)));
			}
		}
		return RecordLineage.SuspectFieldSet.of(pMasterKey, fields);
	}

	/**
	 * {@link RecordLineage.GroupMember} for one matched/suspect master-table {@link Adaptation} - the SAME
	 * all-or-nothing hidden-record rule as the master record itself (class Javadoc): a hidden member carries
	 * nothing beyond {@link RecordLineage.GroupMember#restricted}, not even its own key. Null only when
	 * {@code pRecord} itself is null (should not happen - a defensive no-op, never a fabricated entry).
	 * {@code pTitleFieldNames} is the SAME list {@link #read} built for the anchor master record (coordinator
	 * review, 2026-09-22, "fact 1": a group's matched/suspect members are, by construction, other records of
	 * the SAME table/entity, so the same candidate field names apply unchanged) - {@link #masterLabel} applies
	 * the identical {@link RecordTitleRule} fallback used for the anchor, under this record's own permissions.
	 */
	private static RecordLineage.GroupMember buildGroupMember(final Session pSession, final Adaptation pRecord, final String pProcessId,
		final List<String> pTitleFieldNames)
	{
		if (pRecord == null)
		{
			return null;
		}
		if (RecordLineageReader.isHidden(pSession, pRecord))
		{
			return RecordLineage.GroupMember.restricted();
		}
		final String masterKey = RecordLineageReader.formatKeyOrNull(pRecord);
		final String label = RecordLineageReader.masterLabel(pRecord, pSession, masterKey,
			RecordLineageReader.titleFieldCandidates(pSession, pRecord, pTitleFieldNames));
		final String sourceSystem = RecordLineageReader.isFieldHidden(pSession, pRecord, "sourceSystem") ? null : RecordLineageReader.safeString(pRecord, "sourceSystem");
		return RecordLineage.GroupMember.of(masterKey, label, sourceSystem, pProcessId);
	}

	private static String formatKeyOrNull(final Adaptation pRecord)
	{
		try
		{
			return pRecord.getOccurrencePrimaryKey().format();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * {@code SessionPermissions.getAdaptationAccessPermission(Adaptation).isReadWrite()} - the SAME
	 * permission signature {@link #isHidden} already uses (javap-verified against {@code ebx-lib.jar}),
	 * checked for read-write here instead of hidden. Feeds {@code RecordLineage.GroupInfo#canAcceptSuspects}
	 * only - never gates a read, and never itself performs a write. Fails CLOSED like {@link #isHidden}: an
	 * unexpected {@link RuntimeException} or a {@code null} {@link AccessPermission} both answer false.
	 */
	private static boolean canReadWrite(final Session pSession, final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return false;
		}
		try
		{
			final AccessPermission permission = pSession.getPermissions().getAdaptationAccessPermission(pRecord);
			return permission != null && permission.isReadWrite();
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	// ================================================================================================ permissions

	/**
	 * {@code SessionPermissions.getAdaptationAccessPermission(Adaptation)} (verified by {@code javap} against
	 * {@code ebx-lib.jar}: {@code public abstract AccessPermission getAdaptationAccessPermission(Adaptation)}
	 * on {@link SessionPermissions}, obtained from {@link Session#getPermissions()}) - fails CLOSED in every
	 * ambiguous case, never open: an unexpected {@link RuntimeException} from the permission API itself is
	 * treated as hidden, and so is a {@code null} {@link AccessPermission} answer (coordinator review,
	 * 2026-09-21 - the API's own contract for when it may return {@code null} is not documented; "unknown" and
	 * "hidden" must render identically here, never "unknown" and "visible"). {@code pRecord == null} answers
	 * false (nothing to hide - the caller already treats "no record" as its own, separate case).
	 */
	private static boolean isHidden(final Session pSession, final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return false;
		}
		try
		{
			final SessionPermissions permissions = pSession.getPermissions();
			final AccessPermission permission = permissions.getAdaptationAccessPermission(pRecord);
			return permission == null || permission.isHidden();
		}
		catch (final RuntimeException ex)
		{
			return true;
		}
	}

	/**
	 * {@code SessionPermissions.getNodeAccessPermission(SchemaNode, Adaptation)} (verified by {@code javap}
	 * against {@code ebx-lib.jar}: {@code public abstract AccessPermission getNodeAccessPermission(SchemaNode,
	 * Adaptation)} on {@link SessionPermissions}) - same fail-closed rule as {@link #isHidden}, including a
	 * {@code null} {@link AccessPermission} answer. A field that does not exist on {@code pRecord}'s own schema
	 * answers false (not hidden): that is a shape mismatch (a drifted table), not a permission matter - safe
	 * ONLY because every caller of this method also reads the field's value through {@link #safeGet}/{@link
	 * #safeString}, which independently re-checks the very same {@code getSchemaNode().getNode(...) != null}
	 * condition and answers {@code null} for a missing field regardless of this method's own answer (confirmed
	 * at every one of the three call sites in {@link #read}, 2026-09-21 coordinator review) - so a {@code false}
	 * here never lets a real value through when the node does not exist, only the field's mapped NAME (never a
	 * value) reaches the JSON. A future caller that could emit a value without going through {@code safeGet}/
	 * {@code safeString} first must treat a missing node as hidden ({@code true}), not as visible.
	 */
	private static boolean isFieldHidden(final Session pSession, final Adaptation pRecord, final String pFieldName)
	{
		if (pRecord == null || pFieldName == null)
		{
			return false;
		}
		try
		{
			final SchemaNode node = pRecord.getSchemaNode().getNode(Path.parse("./" + pFieldName));
			if (node == null)
			{
				return false;
			}
			final SessionPermissions permissions = pSession.getPermissions();
			final AccessPermission permission = permissions.getNodeAccessPermission(node, pRecord);
			return permission == null || permission.isHidden();
		}
		catch (final RuntimeException ex)
		{
			return true;
		}
	}

	// ======================================================================================================= misc

	/**
	 * Second permissions pass (2026-10-14): the project by its key (Project's key IS /code) with the caller's view - a project the caller cannot see
	 * is answered like an unknown one ({@link NotFoundException} upstream); the engine (null or system session) reads it as before.
	 */
	private static Adaptation findProject(final Session pSession, final Adaptation pFactory, final String pProjectCode)
	{
		if (pProjectCode == null || pProjectCode.trim().isEmpty())
		{
			return null;
		}
		final AdaptationTable projects = pFactory.getTable(FactoryPaths.PROJECT);
		try
		{
			return UserView.of(pSession).lookup(projects, projects.computePrimaryKey(new Object[] { pProjectCode.trim() }));
		}
		catch (final RuntimeException malformed)
		{
			return null;
		}
	}

	/**
	 * Every {@code StagingEntity} of the project, whatever its status - now a thin call onto the shared
	 * {@link ProjectMasterEntities#readEntities} (coordinator review, 2026-09-22, "Record lineage"
	 * record-level action lot: this method and {@code RecordLineageUserService}'s own near-identical private
	 * copy were the reason a THIRD one, for the new module-level activation cache, would have been one too
	 * many - see that class's own Javadoc). Behaviour unchanged: same predicate, same fields, same {@code
	 * memberEntities} resolution; {@code pEntityIdByName} is still filled the same way for this method's own
	 * callers ({@code MatchPlan}/{@code AttributeMapping} lookups keyed by entity id).
	 */
	private static List<StagingModel.EntityInfo> readEntities(final Adaptation pFactory, final String pProjectId, final Map<String, String> pEntityIdByName)
	{
		final ProjectMasterEntities.Result result = ProjectMasterEntities.readEntities(pFactory, pProjectId);
		for (final Map.Entry<String, String> entry : result.entityIdToName.entrySet())
		{
			pEntityIdByName.put(entry.getValue(), entry.getKey()); // name -> id, this method's own long-standing contract
		}
		return result.entities;
	}

	/** Names of every {@code StagingAttribute} of {@code pEntityId} whose {@code derivation} is set (a {@code FOLD_KEY} matching key, 2026-09-22) - see the field-by-field exclusion above for why. {@code pEntityId} of {@code null}/blank (an entity somehow not found) reads as "no derived attributes", never a lookup error. */
	private static Set<String> readDerivedAttributeNames(final Adaptation pFactory, final String pEntityId)
	{
		final Set<String> out = new HashSet<String>();
		if (RecordLineageReader.blank(pEntityId))
		{
			return out;
		}
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String derivation = row.getString(FactoryPaths.StagingAttribute.DERIVATION);
				if (derivation != null && !derivation.trim().isEmpty())
				{
					out.add(row.getString(FactoryPaths.StagingAttribute.NAME));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	// ============================================================================================ "open in EBX" links

	/**
	 * An already-formatted XPath predicate that selects EXACTLY {@code pRecord}, for the {@code
	 * /ebx-ui/ui/advanced/data/...?predicate=...} link pattern (CLAUDE.md sect9, "to link to a filtered table,
	 * use the ebx-ui route's predicate query parameter" - documented as working, not a guess). Built from the
	 * table's own {@code AdaptationTable.getPrimaryKeySpec()} (the primary key's field PATHS) plus the
	 * record's own current VALUE at each of those paths - never by splitting or re-parsing a formatted
	 * {@link PrimaryKey} string, which would risk mishandling the {@code |}/{@code \} escaping a composite key
	 * can carry (CLAUDE.md sect9, "never build or split a composite primary key as a string"). {@code
	 * getPrimaryKeySpec()} answers absolute-looking paths (e.g. {@code /id}); a {@code workspaceDisplay=
	 * record:}-style predicate needs the relative form ({@code ./id}) instead (same section) - handled below.
	 * Returns null (never throws) whenever any part of this cannot be read: a missing PK component, a schema
	 * mismatch, or any other {@link RuntimeException} - the caller then simply has no link for this record,
	 * never a broken one.
	 */
	private static String recordPredicate(final Adaptation pRecord)
	{
		if (pRecord == null)
		{
			return null;
		}
		try
		{
			final AdaptationTable table = pRecord.getContainerTable();
			final Path[] pkPaths = table == null ? null : table.getPrimaryKeySpec();
			if (pkPaths == null || pkPaths.length == 0)
			{
				return null;
			}
			final StringBuilder predicate = new StringBuilder();
			for (final Path pkPath : pkPaths)
			{
				if (pRecord.getSchemaNode().getNode(pkPath) == null)
				{
					return null; // a schema mismatch - never build a partial/misleading predicate
				}
				final Object value = pRecord.get(pkPath);
				if (value == null)
				{
					return null;
				}
				final String formatted = pkPath.format();
				final String relative = formatted.startsWith("/") ? "." + formatted : formatted;
				if (predicate.length() > 0)
				{
					predicate.append(" and ");
				}
				predicate.append(relative).append("=").append(CatalogReader.quote(String.valueOf(value)));
			}
			return predicate.toString();
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * {@code /ebx-ui/ui/advanced/data/B<dataspace>/<dataset><tablePath>?predicate=<predicate>} - the
	 * documented pattern (CLAUDE.md sect9); the client script rewrites {@code ui/advanced/data/} to {@code
	 * ui/custom/<perspectiveKey>/data/} when it detects the current page is inside a perspective (same
	 * section, "fixUrl" - the catalog module's own `journey.js` does the same rewrite client-side). Never
	 * verified against a live instance this session (task constraint) - see the module's own report for
	 * exactly what that means to double-check before trusting this link in anger.
	 */
	private static String openUrl(final String pPredicate, final String pDataspaceKey, final String pDatasetName, final String pTablePath)
	{
		if (pPredicate == null || RecordLineageReader.blank(pDataspaceKey) || RecordLineageReader.blank(pDatasetName) || RecordLineageReader.blank(pTablePath))
		{
			return null;
		}
		try
		{
			return "/ebx-ui/ui/advanced/data/B" + RecordLineageReader.urlSegment(pDataspaceKey) + "/" + RecordLineageReader.urlSegment(pDatasetName) + pTablePath
				+ "?predicate=" + java.net.URLEncoder.encode(pPredicate, "UTF-8");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			return null; // UTF-8 is always supported - unreachable in practice, never fails the whole read over one link
		}
	}

	private static String urlSegment(final String pValue)
	{
		try
		{
			return java.net.URLEncoder.encode(pValue, "UTF-8");
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			return pValue;
		}
	}

	// ============================================================================================== source order

	/**
	 * The project's own {@code ProjectSource.trustRank} ("lower wins"), keyed by {@code sourceSystem} - see
	 * {@code RecordLineage.SourceChain#sourceOrder}'s own Javadoc for why this doubles as the graphic
	 * screen's stable colour ordering. A system with no {@code ProjectSource} row at all (should not happen
	 * for a source that actually contributed a record, but never trusted blindly) is simply absent from the
	 * map - {@link SourceColorPalette}'s own ordering falls back to the system name alphabetically for it.
	 */
	private static Map<String, Integer> readSourceTrustRank(final Adaptation pFactory, final String pProjectId)
	{
		final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT_SOURCE).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String system = row.getString(FactoryPaths.ProjectSource.SOURCE_SYSTEM);
				if (system == null)
				{
					continue;
				}
				final Object rank = row.get(FactoryPaths.ProjectSource.TRUST_RANK);
				if (rank instanceof Number)
				{
					out.put(system, Integer.valueOf(((Number) rank).intValue()));
				}
			}
		}
		finally
		{
			rows.close();
		}
		return out;
	}

	// ==================================================================================================== master label

	/**
	 * {@code Adaptation.getLabel(Session)} (verified by {@code javap} against {@code ebx-lib.jar}: {@code
	 * public abstract String getLabel(Session)} on {@link Adaptation}) - session-aware, so it already
	 * resolves to the caller's own locale without this reader choosing one itself - when that label is blank
	 * or equal to {@code pMasterKey} (a table with no real label configured typically echoes the formatted key
	 * back as its "label"), falls back to {@link RecordTitleRule#pickTitle} over {@code pTitleCandidates}
	 * instead of giving up (coordinator review, 2026-09-22, "fact 1": a generated master model carries no
	 * {@code osd:table/defaultLabel} - see {@code master.MasterModel}'s own Javadoc for the model-build-time
	 * half of this fix - so the EBX label alone is not enough here). Null only when NEITHER the label nor any
	 * candidate field beats the bare key, or the call itself fails - the existing "null means show the key"
	 * convention every caller of this method already relies on is unchanged; only what counts as "a real
	 * value" grew. {@code pTitleCandidates} must already be restricted to fields the CALLER'S session may see
	 * (this method applies no permission check of its own - see {@code #titleFieldCandidates}).
	 */
	private static String masterLabel(final Adaptation pMasterRecord, final Session pSession, final String pMasterKey,
		final List<RecordTitleRule.Field> pTitleCandidates)
	{
		try
		{
			final String label = pMasterRecord.getLabel(pSession);
			final String picked = RecordTitleRule.pickTitle(label, pMasterKey, pTitleCandidates);
			return picked == null || picked.equals(pMasterKey) ? null : picked;
		}
		catch (final RuntimeException ex)
		{
			return null;
		}
	}

	/**
	 * The field NAMES {@link #masterLabel} may fall back to for one entity - every promoted, accepted-like
	 * business attribute that is a plain STRING (blank/null {@code dataType} reads as STRING - {@code
	 * StagingModel.AttributeInfo}'s own Javadoc) and not a foreign key (a {@code referenceEntity} attribute
	 * becomes a {@code <name>Ref} field pointing at ANOTHER record - never a business value of THIS one) -
	 * the primary key is excluded structurally (it is never in {@code pEntityInfo.attributes} without {@code
	 * primaryKey} set, and this method skips those), and a derived matching-key attribute (2026-09-22, FOLD_KEY
	 * - see the field-by-field exclusion earlier in {@link #read}) is excluded the same way. Empty (never null)
	 * when {@code pEntityInfo} is null (should not happen for an already-resolved entity, defensive only).
	 */
	private static List<String> titleFieldNames(final StagingModel.EntityInfo pEntityInfo, final Set<String> pDerivedAttributeNames)
	{
		final List<String> out = new ArrayList<String>();
		if (pEntityInfo == null)
		{
			return out;
		}
		// MASTER field names (these are read off master records): the same MasterFieldNameRule the field-by-field
		// table applies, so a renamed attribute (Salesforce "Id" -> "Id_src") is still a title candidate
		final Map<String, String> masterFieldByAttribute = MasterFieldNameRule.masterFieldNames(pEntityInfo.attributes);
		for (final StagingModel.AttributeInfo attribute : pEntityInfo.attributes)
		{
			if (attribute.primaryKey || pDerivedAttributeNames.contains(attribute.name) || attribute.referenceEntity != null
				|| !TechnicalKeyRule.isAcceptedLike(attribute.status))
			{
				continue;
			}
			final String dataType = attribute.dataType;
			if (dataType != null && !dataType.trim().isEmpty() && !"STRING".equals(dataType))
			{
				continue;
			}
			out.add(masterFieldByAttribute.get(attribute.name));
		}
		return out;
	}

	/**
	 * {@code pFieldNames} resolved into {@link RecordTitleRule.Field}s for ONE record, under the SAME
	 * permission check every other field of this record goes through ({@link #isFieldHidden}) - a hidden field
	 * is simply left out of the list, never included with a null/placeholder value, so {@link
	 * RecordTitleRule#pickTitle} can never surface a value the caller's session may not see.
	 */
	private static List<RecordTitleRule.Field> titleFieldCandidates(final Session pSession, final Adaptation pRecord, final List<String> pFieldNames)
	{
		final List<RecordTitleRule.Field> out = new ArrayList<RecordTitleRule.Field>();
		if (pRecord == null || pFieldNames == null)
		{
			return out;
		}
		for (final String name : pFieldNames)
		{
			if (RecordLineageReader.isFieldHidden(pSession, pRecord, name))
			{
				continue;
			}
			out.add(new RecordTitleRule.Field(name, RecordLineageReader.safeString(pRecord, name)));
		}
		return out;
	}

	/** {@code Adaptation.get} throws on a path the table does not define (CLAUDE.md sec 9) - guarded, never a per-field crash over one drifted contributing table. */
	private static Object safeGet(final Adaptation pRow, final String pFieldName)
	{
		if (pRow == null || pFieldName == null)
		{
			return null;
		}
		final Path path = Path.parse("./" + pFieldName);
		return pRow.getSchemaNode().getNode(path) == null ? null : pRow.get(path);
	}

	private static String safeString(final Adaptation pRow, final String pFieldName)
	{
		final Object value = RecordLineageReader.safeGet(pRow, pFieldName);
		if (value == null)
		{
			return null;
		}
		if (value instanceof Date)
		{
			return ((Date) value).toInstant().toString();
		}
		return String.valueOf(value);
	}
}
