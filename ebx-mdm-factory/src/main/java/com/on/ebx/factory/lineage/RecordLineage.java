package com.on.ebx.factory.lineage;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.quality.CleaningSteps;

/**
 * The read side of the record-lineage screen (lot 4, `PLAN.md` "Lineage par enregistrement",
 * `docs/MASTER-ZONE.md` &sect;5): a pure function from what {@code ebx.RecordLineageReader} reads off one
 * master record's own {@code Xref} chain, its landing/staging rows, its {@code AttributeMapping}/{@code
 * MappingStep} chain, and (when available) its Match and Merge state, to the JSON `docs/mockups/mdm-factory.html`
 * &sect;5 ("Lineage de l'enregistrement") needs: the journey (system &rarr; landing row &rarr; staging row,
 * one per contributing source), a field-by-field survivorship table (which source fed which value, through
 * which landing column, after which cleaning steps, and whether sources disagree), and the record's merge
 * state.
 * <p>
 * <b>Pure, like {@code master.MasterModel}</b>: no EBX class anywhere in this file, Jackson only - built and
 * unit-tested (see {@code RecordLineageTest}) without an EBX runtime. The composite key values this class is
 * handed ({@link Input#masterKey}, {@link SourceChain#stagingKey}) may themselves contain the {@code |}
 * separator {@code AdaptationTable.computePrimaryKey}/{@code PrimaryKey.format()} would escape (CLAUDE.md
 * &sect;9); this class never parses or re-splits them, it only carries the already-formatted string through
 * to the JSON.
 * <p>
 * <b>"Merge information absent" is a first-class state, never an error</b> (task brief, 2026-09-21): a record
 * whose entity has not been matched yet ({@link MergeInfo#NO_MATCHING_YET}), a server without the Match and
 * Merge add-on on its classpath ({@link MergeInfo#ADDON_NOT_AVAILABLE}), a record the add-on has never
 * written metadata for ({@link MergeInfo#NO_METADATA}), or an unexpected failure reading the add-on's own
 * metadataset ({@link MergeInfo#METADATA_UNREADABLE}, carrying {@link MergeInfo#detail}) all render the same
 * {@code merge.present: false} shape with a named {@code reason} - {@code ebx.RecordLineageReader} decides
 * which reason applies (and catches every failure of its own merge-metadata reads to produce {@code
 * METADATA_UNREADABLE} rather than letting an exception fail the whole answer - coordinator review,
 * 2026-09-21), this class only renders whichever one it is handed.
 * <p>
 * <b>Permission redaction is decided HERE, not by the reader</b> (coordinator review, 2026-09-21): {@code
 * ebx.RecordLineageReader} only computes booleans (is this record/field hidden for the caller's session,
 * through {@code Session.getPermissions()}) and hands them to this class alongside whatever it read;
 * {@link #assemble} is the one place that decides what actually reaches the JSON, so the redaction rules are
 * unit-tested without EBX and enforced even if a caller upstream forgets to null out a value it already knows
 * is restricted. Three shapes:
 * <ul>
 * <li>A whole record hidden ({@link SourceChain#restricted}) is rendered as {@code {masterKey, golden,
 * sourceSystem, restricted: true}} only - no source id, no landing/staging keys or values.</li>
 * <li>A single field hidden on one contributing source ({@link FieldContribution#restricted}) is rendered as
 * {@code {masterKey, sourceSystem, restricted: true}} only - {@link #assemble} never emits {@link
 * FieldContribution#rawValue}/{@link FieldContribution#stagingValue}/{@link FieldContribution#landingColumn}/
 * {@link FieldContribution#cleaningSteps} for it, regardless of what those fields actually carry. A field
 * with ANY restricted contribution reports {@code differs: null} (unknown), never {@code false}.</li>
 * <li>A field hidden on the master record ITSELF ({@link FieldValue#restricted}) is dropped from {@code
 * record.fields} and its name listed in {@code record.restrictedFields} instead.</li>
 * </ul>
 * The master record being hidden ENTIRELY is not representable here at all: {@code ebx.RecordLineageReader}
 * answers a plain 404 before ever building an {@link Input} (see its own Javadoc) - a hidden master record
 * must be indistinguishable from one that does not exist.
 * <p>
 * <b>A field missing on one contributing source is not an error either</b>: {@link FieldContribution} allows
 * every one of its value fields to be {@code null} (no accepted mapping for that field on that source, or the
 * field does not exist yet on that source's own table - the exact {@code FieldAvailabilityRule}/{@code
 * ValueContextForUpdate} gaps {@code ebx.StagingLoader}/{@code ebx.MasterLoader} already treat as
 * "skip, don't fail" for the very same reason); {@link #assemble} computes {@code differs} only from the
 * contributions that actually carry a value and are not restricted.
 */
public final class RecordLineage
{
	/**
	 * Audit F-27: a {@code fieldLineageNote} that starts with this marker is not a sentence but "the add-on's metadata does not have the expected shape":
	 * what follows is the findings ({@code master.MameSchemaWatch#details}), and {@code RecordLineagePage} shows the localized "merge / lineage information
	 * unavailable" text with them. The English notes of {@code ebx.FieldMergeLineageSource} stay plain sentences.
	 */
	public static final String MAME_UNAVAILABLE_PREFIX = "@mame.unavailable:";

	/**
	 * F2-08 (audit 2026-10-23): a {@code fieldLineageNote} that starts with this marker says the merge lineage exists but could not be read on this build
	 * ({@code ebx.FieldMergeLineageSource.Result#unreadable}); what follows is the reason. {@code RecordLineagePage} shows "merge lineage unavailable on this version" instead of an empty list.
	 */
	public static final String MERGE_LINEAGE_UNREADABLE_PREFIX = "@lineage.unreadable:";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	private RecordLineage()
	{
	}

	// =================================================================================================== merge info

	/** {@link MergeInfo#present} / {@link MergeInfo#reason} is the record's Match and Merge state, or the reason it could not be determined. */
	public static final class MergeInfo
	{
		/** The entity's {@code MatchPlan} is not yet {@code APPLIED}: matching has never run for this entity. */
		public static final String NO_MATCHING_YET = "NO_MATCHING_YET";
		/** The Match and Merge add-on is not on this server's classpath (a {@link LinkageError} constructing the reader). */
		public static final String ADDON_NOT_AVAILABLE = "ADDON_NOT_AVAILABLE";
		/** The add-on is available and matching is configured, but this specific record carries no metadata (e.g. loaded after the last activation, or never touched by a run). */
		public static final String NO_METADATA = "NO_METADATA";
		/** Reading the add-on's own metadataset failed unexpectedly (an assumption never seen live - the metadataset naming convention, an undefined path, a runtime failure inside the add-on) - {@link #detail} carries the exception's class and message, never a record value. */
		public static final String METADATA_UNREADABLE = "METADATA_UNREADABLE";

		public final boolean present;
		/** Null when {@link #present}. */
		public final String reason;
		/** Extra, non-sensitive diagnostic text for {@link #METADATA_UNREADABLE} (an exception class + message) - null for every other reason and whenever {@link #present}. */
		public final String detail;
		/** {@code UNSET}/{@code GOLDEN}/{@code MERGED}/{@code PENDING_APPROVAL}/{@code DELETED} (the add-on's own {@code State} enum, exactly as {@code MameRecordState} read it) - null unless {@link #present}. */
		public final String state;
		public final String groupId;
		/** The golden record's own formatted master key - itself when this record already is the golden/anchor being examined. Null unless {@link #present}. */
		public final String goldenKey;
		public final boolean autoCreated;
		/**
		 * {@link #state}, or {@code GOLDEN_WITH_SUSPECTS} when this record IS the golden ({@link #state} =
		 * {@code GOLDEN}), {@code isolated} on the add-on's own {@code RecordMetadata} row, and {@link #group}
		 * carries at least one pending suspect (task brief, 2026-09-22: "a record that is Golden+isolated with
		 * pending suspects is GOLDEN_WITH_SUSPECTS in the lineage model") - see {@link #deriveState}, the pure
		 * function that computes it (unit-tested directly, `RecordLineageTest#deriveState_*`). Never stored
		 * anywhere, never answered by the add-on itself: a label this model computes so the screen can render
		 * "Golden record &middot; 1 suspect pending" without either side inventing a second state machine. Null
		 * unless {@link #present}; equal to {@link #state} for every other combination.
		 */
		public final String derivedState;
		/**
		 * The record's own Match and Merge GROUP - matched (confirmed) members and pending suspects, through
		 * the add-on's public {@code GroupOperations}/{@code Group} API ({@code ebx.MameGroups}, facts measured
		 * live 2026-09-22). Null whenever no group could be resolved (the add-on absent, the group id
		 * unreadable/unparsable, or {@code GroupOperations.getGroup} itself answered "not found") - {@link
		 * #present} can be true with this still null, e.g. a plain {@code UNSET}/{@code DELETED} record that
		 * carries add-on metadata but was never actually grouped.
		 */
		public final GroupInfo group;

		private MergeInfo(final boolean pPresent, final String pReason, final String pDetail, final String pState, final String pGroupId, final String pGoldenKey,
			final boolean pAutoCreated, final String pDerivedState, final GroupInfo pGroup)
		{
			this.present = pPresent;
			this.reason = pReason;
			this.detail = pDetail;
			this.state = pState;
			this.groupId = pGroupId;
			this.goldenKey = pGoldenKey;
			this.autoCreated = pAutoCreated;
			this.derivedState = pDerivedState;
			this.group = pGroup;
		}

		public static MergeInfo absent(final String pReason)
		{
			return new MergeInfo(false, pReason, null, null, null, null, false, null, null);
		}

		/** Same as {@link #absent(String)}, with a non-sensitive diagnostic detail attached - see {@link #METADATA_UNREADABLE}. */
		public static MergeInfo absent(final String pReason, final String pDetail)
		{
			return new MergeInfo(false, pReason, pDetail, null, null, null, false, null, null);
		}

		public static MergeInfo present(final String pState, final String pGroupId, final String pGoldenKey, final boolean pAutoCreated)
		{
			return MergeInfo.present(pState, pGroupId, pGoldenKey, pAutoCreated, pState, null);
		}

		/** Same as the 4-argument factory, with {@link #derivedState}/{@link #group} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public static MergeInfo present(final String pState, final String pGroupId, final String pGoldenKey, final boolean pAutoCreated, final String pDerivedState,
			final GroupInfo pGroup)
		{
			return new MergeInfo(true, null, null, pState, pGroupId, pGoldenKey, pAutoCreated, pDerivedState, pGroup);
		}
	}

	/**
	 * {@code GOLDEN_WITH_SUSPECTS} - see {@link MergeInfo#derivedState}'s own Javadoc for exactly what it
	 * means and why it is derived here rather than stored anywhere. Pure: a plain 3-argument decision, no
	 * EBX type, unit-tested directly.
	 */
	public static String deriveState(final String pState, final Boolean pIsolated, final GroupInfo pGroup)
	{
		final int suspectCount = pGroup == null ? 0 : pGroup.suspects.size();
		if ("GOLDEN".equals(pState) && Boolean.TRUE.equals(pIsolated) && suspectCount > 0)
		{
			return "GOLDEN_WITH_SUSPECTS";
		}
		return pState;
	}

	// =================================================================================================== group

	/**
	 * One member of a Match and Merge GROUP - either a confirmed match or a pending suspect ({@link
	 * GroupInfo#matched}/{@link GroupInfo#suspects}). Same all-or-nothing redaction rule as the MASTER record
	 * itself (task brief, 2026-09-22: "a hidden suspect record -&gt; {restricted:true} only") - unlike {@link
	 * SourceChain#restricted}, which still names the system, a restricted {@code GroupMember} carries NOTHING
	 * else at all, not even its own key: this object describes another master-table record's own visibility,
	 * not a landing/staging chain hanging off an already-known-visible one.
	 */
	public static final class GroupMember
	{
		public final boolean restricted;
		/** Null when {@link #restricted}. */
		public final String masterKey;
		/** The record's own {@code Adaptation.getLabel(Session)} - null when blank/equal to the key (same rule as {@link Input#masterLabel}) or when {@link #restricted}. */
		public final String label;
		/** The record's own {@code sourceSystem} field (master.MasterModel: a plain, optional field on every master table) - null when absent, hidden at the FIELD level, or {@link #restricted}. */
		public final String sourceSystem;
		/** {@code Suspected.matchingProcessId}, best-effort (see {@code ebx.RecordLineageReader#lookupSuspectedProcessId}'s own Javadoc for the unverified pairing direction) - null when not applicable (a matched member) or not found. */
		public final String processId;

		private GroupMember(final boolean pRestricted, final String pMasterKey, final String pLabel, final String pSourceSystem, final String pProcessId)
		{
			this.restricted = pRestricted;
			this.masterKey = pMasterKey;
			this.label = pLabel;
			this.sourceSystem = pSourceSystem;
			this.processId = pProcessId;
		}

		public static GroupMember restricted()
		{
			return new GroupMember(true, null, null, null, null);
		}

		public static GroupMember of(final String pMasterKey, final String pLabel, final String pSourceSystem, final String pProcessId)
		{
			return new GroupMember(false, pMasterKey, pLabel, pSourceSystem, pProcessId);
		}
	}

	/** The record's own Match and Merge group - see {@link MergeInfo#group}'s own Javadoc. */
	public static final class GroupInfo
	{
		public final String id;
		public final String label;
		/**
		 * Confirmed members, {@link GroupMember} list included ({@code matched.size() + 1}, the {@code +1}
		 * being this golden itself). <b>Measured live 2026-09-24</b> (record 217, Companies_Master/Master/
		 * root/Company): {@code ebx.MameGroups}' underlying {@code Group.getMatchedRecords()} DOES include the
		 * golden among its own entries - this Javadoc previously said the opposite. {@code ebx.RecordLineageReader
		 * #buildGroup} filters the golden's own key out of {@link #matched} before this field is computed
		 * ({@code ebx.RecordLineageReader#excludingGolden}), so {@code matched.size() + 1} stays exact; the
		 * exclusion happens in the caller, not here.
		 */
		public final int memberCount;
		public final List<GroupMember> matched;
		public final List<GroupMember> suspects;
		/** True when EITHER {@link #matched} or {@link #suspects} was capped ({@code ebx.MameGroups#CAP}, 50) before the add-on's own iterator was exhausted. */
		public final boolean truncated;
		/**
		 * Whether the caller MAY act on {@link #suspects} (accept/reject - not built this lot, see {@code
		 * docs/RECORD-LINEAGE.md} "Confirming a suspect" for the documented call sequence): a group was
		 * actually resolved, there is at least one suspect to act on, AND the caller's session has read-write
		 * access to the master record examined ({@code SessionPermissions.getAdaptationAccessPermission(
		 * Adaptation).isReadWrite()} - javap-verified against {@code ebx-lib.jar}, the same signature {@code
		 * ebx.RecordLineageReader#isHidden} already uses, checked for {@code isReadWrite()} instead of {@code
		 * isHidden()} here). Never itself performs a write - a read-only flag for the screen to grey out or
		 * show an "Accept" button.
		 */
		public final boolean canAcceptSuspects;

		public GroupInfo(final String pId, final String pLabel, final int pMemberCount, final List<GroupMember> pMatched, final List<GroupMember> pSuspects,
			final boolean pTruncated, final boolean pCanAcceptSuspects)
		{
			this.id = pId;
			this.label = pLabel;
			this.memberCount = pMemberCount;
			this.matched = pMatched == null ? Collections.<GroupMember>emptyList() : pMatched;
			this.suspects = pSuspects == null ? Collections.<GroupMember>emptyList() : pSuspects;
			this.truncated = pTruncated;
			this.canAcceptSuspects = pCanAcceptSuspects;
		}
	}

	// ============================================================================================ suspect comparison

	/**
	 * One field the entity's own {@code MatchPlan.requirements} compares (or pre-clusters on), in the order
	 * the plan's own {@code tiers[].fields[]} declare them, de-duplicated by field name keeping the first
	 * occurrence - {@code ebx.RecordLineageReader#readMatchPlanFields} builds this straight from the plan's
	 * own JSON, never re-deriving what the plan compares from anything else. {@link #algorithm} is the raw
	 * {@code ebx-addon-mame@...} code (CLAUDE.md &sect;8's own "algorithm codes are prefixed and must be
	 * passed as-is") - a human-words translation is the RENDERER's job ({@code lineage.AlgorithmWords}), never
	 * baked in here, so this class stays a plain carrier.
	 */
	public static final class PlanFieldRef
	{
		public final String field;
		/** Null when the plan's own field entry carries none (should not happen for a real plan, defensive only). */
		public final String algorithm;

		public PlanFieldRef(final String pField, final String pAlgorithm)
		{
			this.field = pField;
			this.algorithm = pAlgorithm;
		}
	}

	/**
	 * One pending suspect's OWN master-record field values (read directly off its master-table row, under the
	 * SAME per-field permission check every other value in this model goes through) - the comparison panel's
	 * "compare the looked-up record with its suspects" columns (task brief, 2026-09-22, "decide on a
	 * suspect"). Deliberately a SEPARATE carrier from {@link GroupMember} (which only ever names a suspect,
	 * never its values) and from {@link SourceChain}/{@link Input#suspectSources} (which carries a suspect's
	 * own LANDING/STAGING chain, not its master-table field values) - the comparison panel needs values a
	 * data steward can actually read side by side, which neither of those two already-established shapes
	 * carries.
	 */
	public static final class SuspectFieldSet
	{
		public final String masterKey;
		/** Same all-or-nothing rule as {@link GroupMember#restricted} - a hidden suspect record contributes nothing beyond its own key here either. */
		public final boolean restricted;
		/** Empty (never null) when {@link #restricted}. */
		public final Map<String, FieldValue> fields;

		private SuspectFieldSet(final String pMasterKey, final boolean pRestricted, final Map<String, FieldValue> pFields)
		{
			this.masterKey = pMasterKey;
			this.restricted = pRestricted;
			this.fields = pFields == null ? Collections.<String, FieldValue>emptyMap() : pFields;
		}

		public static SuspectFieldSet restricted(final String pMasterKey)
		{
			return new SuspectFieldSet(pMasterKey, true, null);
		}

		public static SuspectFieldSet of(final String pMasterKey, final Map<String, FieldValue> pFields)
		{
			return new SuspectFieldSet(pMasterKey, false, pFields);
		}
	}

	// ================================================================================================= field value

	/** One field's value, and whether the caller's session may see it at all - the master record's own {@link Input#recordFields} use this; a contributing source's own fields use the coarser {@link FieldContribution#restricted} instead (see class Javadoc for why the two are shaped differently). */
	public static final class FieldValue
	{
		/** Null when {@link #restricted}. */
		public final String value;
		public final boolean restricted;

		private FieldValue(final String pValue, final boolean pRestricted)
		{
			this.value = pValue;
			this.restricted = pRestricted;
		}

		public static FieldValue of(final String pValue)
		{
			return new FieldValue(pValue, false);
		}

		public static FieldValue restricted()
		{
			return new FieldValue(null, true);
		}
	}

	// ================================================================================================= source chain

	/** One contributing master record's own chain: the source system it came from, its landing row, and the staging row built from it - the "système source &rarr; ligne brute &rarr; ligne staging" nodes of the mockup's journey. */
	public static final class SourceChain
	{
		/** The master record this chain belongs to - equal to the record asked about unless this entry is one of a golden's OTHER contributing sources. */
		public final String masterKey;
		/** True for the one entry that is the golden/anchor record being examined (only meaningful when more than one entry is present). */
		public final boolean golden;
		public final String sourceSystem;
		/** True when the caller's session may not see this source's own landing or staging record at all - {@link #assemble} then emits only {@link #masterKey}/{@link #golden}/{@link #sourceSystem}, nothing else on this object, whatever the other fields below carry. */
		public final boolean restricted;
		public final String sourceId;
		public final String landingDataset;
		public final String landingTable;
		public final String landingKey;
		public final String loadId;
		/** The landing row's own {@code _status} at read time (e.g. {@code STAGED}) - may be stale if the row changed since. */
		public final String landingStatus;
		public final Date receivedAt;
		public final String stagingTable;
		public final String stagingKey;
		/** An already-built {@code /ebx-ui/ui/advanced/data/...?predicate=...} link to this source's own landing/staging row (CLAUDE.md sect9, "predicate" query parameter) - null when it could not be built, and NEVER read by {@link #assemble} when {@link #restricted} (see class Javadoc: a restricted source emits nothing beyond {@link #masterKey}/{@link #golden}/{@link #sourceSystem}). Built by {@code ebx.RecordLineageReader} from the record's own primary-key VALUES (never by splitting or re-parsing a formatted key string - CLAUDE.md sect9). */
		public final String landingUrl;
		public final String stagingUrl;
		/**
		 * This source system's declared priority within the PROJECT ({@code ProjectSource.trustRank} -
		 * "lower wins", the same field the eventual merge policy's most-trusted-source order already reads,
		 * `WizardRules`/`WizardSnapshot`'s own Javadoc) - reused here (coordinator review, 2026-09-22) as the
		 * STABLE ordering the graphic screen's colour assignment needs: "the same colour for a system across
		 * every record of the project", which a hash of the name alone cannot deliberately control the
		 * neighbours of. Null when the project never configured a rank for this system (a source added
		 * without visiting the wizard's trust-rank field, or a system this reader could not resolve back to a
		 * {@code ProjectSource} row) - {@link SourceColorPalette}'s ordering falls back to the system's own
		 * name, alphabetically, exactly as instructed, whenever this is null or ties with another source's.
		 */
		public final Integer sourceOrder;
		/**
		 * True for a lane built off a Match and Merge SUSPECT rather than an actual contributing {@code Xref}
		 * source (task brief, 2026-09-22: "the PARCOURS must draw a lane for each SUSPECT record too") -
		 * {@code ebx.RecordLineageReader} resolves a suspect's own chain through the exact same {@code
		 * resolveSource} a contributing source uses, under the same permission rule and bound, and carries the
		 * result in {@link Input#suspectSources} (a SEPARATE list from {@link Input#sources}, never mixed in -
		 * a suspect is not yet a confirmed contributor, so it must never appear in the "champ par champ" field
		 * table's own column set, only in the journey). Always {@code false} for a plain contributing source.
		 */
		public final boolean suspect;
		/**
		 * Cleaning-step KINDS the entity's accepted mappings run for this source system, computed BY SOURCE
		 * SYSTEM rather than by this record's own field contributions (fix, 2026-09-22, "decide on a
		 * suspect" lot task brief, item D) - {@code null} for a plain contributing source (the graphic
		 * builder keeps deriving that case from {@code fields[].contributions[]}, unchanged); populated ONLY
		 * for a {@link #suspect} lane, whose staging card otherwise showed a permanent, wrong "0 steps": a
		 * suspect has no field CONTRIBUTIONS yet (it is not a confirmed source - see this field's own
		 * Javadoc), but the mapping that would clean its values if/when it is confirmed already exists and
		 * already ran on its OWN staging row, so showing "0 steps" was actively misleading, not merely
		 * incomplete. {@code ebx.RecordLineageReader} builds this from the SAME per-(field, source system)
		 * {@code AttributeMapping}/{@code MappingStep} read {@link Input#fields}'s own contributions already
		 * use, just grouped by source system instead of by contributing master key.
		 */
		public final List<String> cleaningStepKinds;

		public SourceChain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
			final String pLandingDataset, final String pLandingTable, final String pLandingKey, final String pLoadId, final String pLandingStatus, final Date pReceivedAt,
			final String pStagingTable, final String pStagingKey)
		{
			this(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, pLandingDataset, pLandingTable, pLandingKey, pLoadId, pLandingStatus, pReceivedAt, pStagingTable,
				pStagingKey, null, null);
		}

		/** Same as the 13-argument constructor, with the two "open in EBX" links this screen's graphic view adds - kept as a separate overload so every existing caller/fixture (production and test) is unaffected. */
		public SourceChain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
			final String pLandingDataset, final String pLandingTable, final String pLandingKey, final String pLoadId, final String pLandingStatus, final Date pReceivedAt,
			final String pStagingTable, final String pStagingKey, final String pLandingUrl, final String pStagingUrl)
		{
			this(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, pLandingDataset, pLandingTable, pLandingKey, pLoadId, pLandingStatus, pReceivedAt, pStagingTable,
				pStagingKey, pLandingUrl, pStagingUrl, null);
		}

		/** Same as the 15-argument constructor, with {@link #sourceOrder} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public SourceChain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
			final String pLandingDataset, final String pLandingTable, final String pLandingKey, final String pLoadId, final String pLandingStatus, final Date pReceivedAt,
			final String pStagingTable, final String pStagingKey, final String pLandingUrl, final String pStagingUrl, final Integer pSourceOrder)
		{
			this(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, pLandingDataset, pLandingTable, pLandingKey, pLoadId, pLandingStatus, pReceivedAt, pStagingTable,
				pStagingKey, pLandingUrl, pStagingUrl, pSourceOrder, false);
		}

		/** Same as the 16-argument constructor, with {@link #suspect} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public SourceChain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
			final String pLandingDataset, final String pLandingTable, final String pLandingKey, final String pLoadId, final String pLandingStatus, final Date pReceivedAt,
			final String pStagingTable, final String pStagingKey, final String pLandingUrl, final String pStagingUrl, final Integer pSourceOrder, final boolean pSuspect)
		{
			this(pMasterKey, pGolden, pSourceSystem, pRestricted, pSourceId, pLandingDataset, pLandingTable, pLandingKey, pLoadId, pLandingStatus, pReceivedAt, pStagingTable,
				pStagingKey, pLandingUrl, pStagingUrl, pSourceOrder, pSuspect, null);
		}

		/** Same as the 17-argument constructor, with {@link #cleaningStepKinds} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public SourceChain(final String pMasterKey, final boolean pGolden, final String pSourceSystem, final boolean pRestricted, final String pSourceId,
			final String pLandingDataset, final String pLandingTable, final String pLandingKey, final String pLoadId, final String pLandingStatus, final Date pReceivedAt,
			final String pStagingTable, final String pStagingKey, final String pLandingUrl, final String pStagingUrl, final Integer pSourceOrder, final boolean pSuspect,
			final List<String> pCleaningStepKinds)
		{
			this.masterKey = pMasterKey;
			this.golden = pGolden;
			this.sourceSystem = pSourceSystem;
			this.restricted = pRestricted;
			this.sourceId = pSourceId;
			this.landingDataset = pLandingDataset;
			this.landingTable = pLandingTable;
			this.landingKey = pLandingKey;
			this.loadId = pLoadId;
			this.landingStatus = pLandingStatus;
			this.receivedAt = pReceivedAt;
			this.stagingTable = pStagingTable;
			this.stagingKey = pStagingKey;
			this.landingUrl = pLandingUrl;
			this.stagingUrl = pStagingUrl;
			this.sourceOrder = pSourceOrder;
			this.suspect = pSuspect;
			this.cleaningStepKinds = pCleaningStepKinds;
		}
	}

	// =============================================================================================== field lineage

	/** One contributing source's answer for one field: where the value came from, what it was before/after cleaning, and through which landing column - or nulls when this source has no mapped value for the field at all (see class Javadoc). */
	public static final class FieldContribution
	{
		public final String masterKey;
		public final String sourceSystem;
		/** True when the caller's session may not see this field's value on this source's own landing or staging record (or the whole source is itself {@link SourceChain#restricted}) - {@link #assemble} then emits only {@link #masterKey}/{@link #sourceSystem}/{@code restricted: true}, ignoring every value field below. */
		public final boolean restricted;
		/** Null when no accepted {@code AttributeMapping} of this field reads from this source. */
		public final String landingColumn;
		public final String rawValue;
		public final String stagingValue;
		/** {@code MappingStep.kind} values, in position order; never null, may be empty. */
		public final List<String> cleaningSteps;

		public FieldContribution(final String pMasterKey, final String pSourceSystem, final boolean pRestricted, final String pLandingColumn, final String pRawValue,
			final String pStagingValue, final List<String> pCleaningSteps)
		{
			this.masterKey = pMasterKey;
			this.sourceSystem = pSourceSystem;
			this.restricted = pRestricted;
			this.landingColumn = pLandingColumn;
			this.rawValue = pRawValue;
			this.stagingValue = pStagingValue;
			this.cleaningSteps = pCleaningSteps == null ? Collections.<String>emptyList() : pCleaningSteps;
		}
	}

	/** One master/staging business field, and every contributing source's answer for it. */
	public static final class FieldInput
	{
		public final String field;
		public final List<FieldContribution> contributions;
		/**
		 * The contributing {@link FieldContribution#masterKey} a REAL post-merge survivorship answer names as
		 * retained for this field - null means "no known answer, deduce one instead" (see {@link #assemble}'s
		 * "champ par champ" rule): either no real merge has run yet for this group, or {@code
		 * ebx.FieldMergeLineageSource} (implemented 2026-09-22, "decide on a suspect" lot - reflection-based,
		 * best-effort, see its own Javadoc) could not identify a single contributing source with confidence
		 * for this field. The ONE line that ever needed to change to wire a real answer through was the call
		 * that builds this object ({@code ebx.RecordLineageReader}'s own {@code fieldInputs} loop) - passing
		 * the real key here makes {@link #assemble} prefer it over the deduction automatically; nothing else
		 * in this class, {@code RecordLineageGraphic}, or {@code lineage-graphic.js} needed to change.
		 */
		public final String knownRetainedMasterKey;
		/**
		 * A short, human survivorship-rule sentence (the mockup's own "source la plus fiable"/"valeur la plus
		 * fréquente" line) to show next to the golden cell - ONLY meaningful, and only ever rendered
		 * (coordinator review, 2026-09-22: "the survivorship rule text appears at the right of the golden
		 * cell only when known"), alongside a {@link #knownRetainedMasterKey}. Null today (the same seam as
		 * that field - {@code ebx.FieldMergeLineageSource}'s own real answer, {@code
		 * MergeValueLineageCompact.Items[].mergeFunction}, would supply both together); this class never
		 * fabricates one for a merely DEDUCED retained cell.
		 */
		public final String survivorshipRule;
		/**
		 * The field's own human label (the master schema's {@code SchemaNode#getLabel}, coordinator review,
		 * 2026-09-22: "field names in raw camelCase small caps" on the golden column) - null when the reader
		 * found none worth showing (blank, or equal to {@link #field} itself), in which case {@link #assemble}
		 * and the graphic screen both fall back to the bare technical name, never a blank label.
		 */
		public final String fieldLabel;

		public FieldInput(final String pField, final List<FieldContribution> pContributions)
		{
			this(pField, pContributions, null);
		}

		/** Same as the 2-argument constructor, with a known (not deduced) retained-source answer - see {@link #knownRetainedMasterKey}. */
		public FieldInput(final String pField, final List<FieldContribution> pContributions, final String pKnownRetainedMasterKey)
		{
			this(pField, pContributions, pKnownRetainedMasterKey, null);
		}

		/** Same as the 3-argument constructor, with {@link #survivorshipRule} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public FieldInput(final String pField, final List<FieldContribution> pContributions, final String pKnownRetainedMasterKey, final String pSurvivorshipRule)
		{
			this(pField, pContributions, pKnownRetainedMasterKey, pSurvivorshipRule, null);
		}

		/** Same as the 4-argument constructor, with {@link #fieldLabel} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public FieldInput(final String pField, final List<FieldContribution> pContributions, final String pKnownRetainedMasterKey, final String pSurvivorshipRule,
			final String pFieldLabel)
		{
			this.field = pField;
			this.contributions = pContributions == null ? Collections.<FieldContribution>emptyList() : pContributions;
			this.knownRetainedMasterKey = pKnownRetainedMasterKey;
			this.survivorshipRule = pSurvivorshipRule;
			this.fieldLabel = pFieldLabel;
		}
	}

	// ======================================================================================================= input

	public static final class Input
	{
		public final String project;
		public final String entity;
		public final String masterTable;
		public final String masterKey;
		/** The requested master record's own current business field values (field name -&gt; value/restricted), whatever its merge state. */
		public final Map<String, FieldValue> recordFields;
		public final List<SourceChain> sources;
		public final List<FieldInput> fields;
		public final MergeInfo merge;
		/**
		 * Set only when field-level merge provenance ({@code MergeValueLineageCompact}) would be meaningful
		 * (more than one contributing source) but could not be read - the seam {@code ebx.RecordLineageReader}
		 * leaves in place rather than guessing at the add-on's repeatable-group Java API (see its own Javadoc).
		 * Null otherwise.
		 */
		public final String fieldLineageNote;
		/** An already-built "open in EBX" link to the master/golden record itself - see {@code SourceChain#landingUrl}'s own Javadoc for how it is built and why {@link RecordLineage} never re-derives it from a key string. Null when it could not be built. */
		public final String masterUrl;
		/**
		 * The master record's own EBX label (coordinator review, 2026-09-22: "the golden card and the page
		 * header show the primary key where the mockup shows the record's NAME") - {@code
		 * ebx.RecordLineageReader} reads it with {@code Adaptation.getLabel(Session)}, subject to the SAME
		 * hidden-record rule as everything else about the master record (a hidden master 404s before an
		 * {@code Input} is ever built - see this class's own class Javadoc). Null whenever the label is
		 * blank OR equal to {@link #masterKey} (the reader's own fallback check, done once there rather than
		 * repeated at every rendering site) - a null here always means "show the key instead", never "show
		 * nothing".
		 */
		public final String masterLabel;
		/**
		 * Suspect lanes for the journey view - see {@link SourceChain#suspect}'s own Javadoc for why these are
		 * a SEPARATE list from {@link #sources} rather than entries mixed into it. Never null (empty when the
		 * record has no pending suspect, or none could be resolved).
		 */
		public final List<SourceChain> suspectSources;
		/**
		 * The fields the entity's own {@code MatchPlan} compares, in plan order (task brief, 2026-09-22,
		 * "decide on a suspect" lot: the comparison panel shows these FIRST, then every other field
		 * collapsed) - never null, empty when the entity has no accepted plan yet, or its {@code
		 * requirements} could not be parsed (never a hard failure - {@code
		 * ebx.RecordLineageReader#readMatchPlanFields}'s own Javadoc).
		 */
		public final List<PlanFieldRef> matchPlanFields;
		/**
		 * One entry per pending suspect this reader could resolve field VALUES for - the comparison panel's
		 * own data (see {@link SuspectFieldSet}'s own Javadoc for why this is a separate carrier from {@link
		 * GroupMember}/{@link SourceChain}). Never null; empty whenever {@link MergeInfo#group} carries no
		 * suspect, or none could be resolved. {@link #assemble} emits a top-level {@code comparison} object
		 * ONLY when this is non-empty - see its own comment.
		 */
		public final List<SuspectFieldSet> suspectFieldSets;

		public Input(final String pProject, final String pEntity, final String pMasterTable, final String pMasterKey, final Map<String, FieldValue> pRecordFields,
			final List<SourceChain> pSources, final List<FieldInput> pFields, final MergeInfo pMerge, final String pFieldLineageNote)
		{
			this(pProject, pEntity, pMasterTable, pMasterKey, pRecordFields, pSources, pFields, pMerge, pFieldLineageNote, null);
		}

		/** Same as the 9-argument constructor, with {@link #masterUrl} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public Input(final String pProject, final String pEntity, final String pMasterTable, final String pMasterKey, final Map<String, FieldValue> pRecordFields,
			final List<SourceChain> pSources, final List<FieldInput> pFields, final MergeInfo pMerge, final String pFieldLineageNote, final String pMasterUrl)
		{
			this(pProject, pEntity, pMasterTable, pMasterKey, pRecordFields, pSources, pFields, pMerge, pFieldLineageNote, pMasterUrl, null, null);
		}

		/** Same as the 10-argument constructor, with {@link #masterLabel} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public Input(final String pProject, final String pEntity, final String pMasterTable, final String pMasterKey, final Map<String, FieldValue> pRecordFields,
			final List<SourceChain> pSources, final List<FieldInput> pFields, final MergeInfo pMerge, final String pFieldLineageNote, final String pMasterUrl,
			final String pMasterLabel)
		{
			this(pProject, pEntity, pMasterTable, pMasterKey, pRecordFields, pSources, pFields, pMerge, pFieldLineageNote, pMasterUrl, pMasterLabel, null);
		}

		/** Same as the 11-argument constructor, with {@link #suspectSources} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public Input(final String pProject, final String pEntity, final String pMasterTable, final String pMasterKey, final Map<String, FieldValue> pRecordFields,
			final List<SourceChain> pSources, final List<FieldInput> pFields, final MergeInfo pMerge, final String pFieldLineageNote, final String pMasterUrl,
			final String pMasterLabel, final List<SourceChain> pSuspectSources)
		{
			this(pProject, pEntity, pMasterTable, pMasterKey, pRecordFields, pSources, pFields, pMerge, pFieldLineageNote, pMasterUrl, pMasterLabel, pSuspectSources, null,
				null);
		}

		/** Same as the 12-argument constructor, with {@link #matchPlanFields}/{@link #suspectFieldSets} - kept as a separate overload so every existing caller/fixture is unaffected. */
		public Input(final String pProject, final String pEntity, final String pMasterTable, final String pMasterKey, final Map<String, FieldValue> pRecordFields,
			final List<SourceChain> pSources, final List<FieldInput> pFields, final MergeInfo pMerge, final String pFieldLineageNote, final String pMasterUrl,
			final String pMasterLabel, final List<SourceChain> pSuspectSources, final List<PlanFieldRef> pMatchPlanFields,
			final List<SuspectFieldSet> pSuspectFieldSets)
		{
			this.project = pProject;
			this.entity = pEntity;
			this.masterTable = pMasterTable;
			this.masterKey = pMasterKey;
			this.recordFields = pRecordFields;
			this.sources = pSources == null ? Collections.<SourceChain>emptyList() : pSources;
			this.fields = pFields == null ? Collections.<FieldInput>emptyList() : pFields;
			this.merge = pMerge;
			this.fieldLineageNote = pFieldLineageNote;
			this.masterUrl = pMasterUrl;
			this.masterLabel = pMasterLabel;
			this.suspectSources = pSuspectSources == null ? Collections.<SourceChain>emptyList() : pSuspectSources;
			this.matchPlanFields = pMatchPlanFields == null ? Collections.<PlanFieldRef>emptyList() : pMatchPlanFields;
			this.suspectFieldSets = pSuspectFieldSets == null ? Collections.<SuspectFieldSet>emptyList() : pSuspectFieldSets;
		}
	}

	// ===================================================================================================== assemble

	public static ObjectNode assemble(final Input pInput)
	{
		final ObjectNode out = RecordLineage.MAPPER.createObjectNode();
		out.put("project", pInput.project);
		out.put("entity", pInput.entity);
		out.put("masterTable", pInput.masterTable);
		out.put("masterKey", pInput.masterKey);
		if (pInput.masterLabel != null)
		{
			out.put("masterLabel", pInput.masterLabel);
		}
		if (pInput.masterUrl != null)
		{
			out.put("masterUrl", pInput.masterUrl);
		}

		final ObjectNode record = out.putObject("record");
		final ObjectNode recordFields = record.putObject("fields");
		final ArrayNode restrictedFields = record.putArray("restrictedFields");
		if (pInput.recordFields != null)
		{
			for (final Map.Entry<String, FieldValue> field : pInput.recordFields.entrySet())
			{
				if (field.getValue().restricted)
				{
					restrictedFields.add(field.getKey());
				}
				else
				{
					recordFields.put(field.getKey(), field.getValue().value);
				}
			}
		}

		final ObjectNode merge = out.putObject("merge");
		merge.put("present", pInput.merge.present);
		if (pInput.merge.present)
		{
			merge.put("state", pInput.merge.state);
			merge.put("derivedState", pInput.merge.derivedState == null ? pInput.merge.state : pInput.merge.derivedState);
			merge.put("groupId", pInput.merge.groupId);
			merge.put("goldenKey", pInput.merge.goldenKey);
			merge.put("autoCreated", pInput.merge.autoCreated);
			if (pInput.merge.group != null)
			{
				final ObjectNode group = merge.putObject("group");
				group.put("id", pInput.merge.group.id);
				group.put("label", pInput.merge.group.label);
				group.put("memberCount", pInput.merge.group.memberCount);
				group.put("truncated", pInput.merge.group.truncated);
				final ArrayNode matched = group.putArray("matched");
				for (final GroupMember member : pInput.merge.group.matched)
				{
					matched.add(RecordLineage.buildGroupMemberNode(member));
				}
				final ArrayNode suspects = group.putArray("suspects");
				for (final GroupMember member : pInput.merge.group.suspects)
				{
					suspects.add(RecordLineage.buildGroupMemberNode(member));
				}
				final ObjectNode actions = group.putObject("actions");
				actions.put("canAcceptSuspects", pInput.merge.group.canAcceptSuspects);
			}
		}
		else
		{
			merge.put("reason", pInput.merge.reason);
			if (pInput.merge.detail != null)
			{
				merge.put("detail", pInput.merge.detail);
			}
		}

		final ArrayNode sources = out.putArray("sources");
		for (final SourceChain source : pInput.sources)
		{
			sources.add(RecordLineage.buildSourceNode(source));
		}

		// suspect lanes for the journey (task brief, 2026-09-22) - a SEPARATE array, never merged into
		// `sources` above: see SourceChain#suspect's own Javadoc for why the "champ par champ" field table
		// (which reads its own column set from `sources`) must never see one of these.
		final ArrayNode suspectSources = out.putArray("suspectSources");
		for (final SourceChain source : pInput.suspectSources)
		{
			suspectSources.add(RecordLineage.buildSourceNode(source));
		}

		final ArrayNode fields = out.putArray("fields");
		for (final FieldInput field : pInput.fields)
		{
			final ObjectNode node = fields.addObject();
			node.put("field", field.field);
			if (field.fieldLabel != null)
			{
				node.put("fieldLabel", field.fieldLabel);
			}

			// the record's OWN current value for this field ("golden", in the mockup's sense - the master
			// record already holds it, whatever its merge state) - the anchor the "champ par champ" screen
			// compares every contribution against. Never fabricated: null/absent when the field is not on the
			// record at all, or is itself restricted (permission rule 3 - the same redaction record.fields
			// already applies, read here from the SAME pInput.recordFields rather than a second source of truth).
			final FieldValue goldenField = pInput.recordFields == null ? null : pInput.recordFields.get(field.field);
			final boolean goldenRestricted = goldenField != null && goldenField.restricted;
			final String goldenValue = goldenField == null || goldenField.restricted ? null : goldenField.value;
			node.put("goldenRestricted", goldenRestricted);
			if (!goldenRestricted && goldenValue != null)
			{
				node.put("goldenValue", goldenValue);
			}

			// deduced-retained rule (task brief, 2026-09-21 - "champ par champ", no real survivorship answer
			// read yet): count the non-restricted contributions whose own comparable value equals the golden
			// value. Exactly one match is a logical certainty (only that source could have supplied the value
			// the record actually holds) - marked "retained (deduced)". Zero or more than one match is
			// genuinely ambiguous - no cell is ever marked retained by guessing among several agreeing sources.
			// A KNOWN answer (field.knownRetainedMasterKey, once a real implementation of the
			// FieldMergeLineageSource seam supplies one) always wins over this deduction - see that field's
			// own Javadoc for why changing the one caller that builds it is the whole switch.
			final boolean knownAnswer = field.knownRetainedMasterKey != null;
			int goldenMatchCount = 0;
			if (!goldenRestricted && goldenValue != null)
			{
				for (final FieldContribution contribution : field.contributions)
				{
					if (contribution.restricted)
					{
						continue;
					}
					final String comparable = contribution.stagingValue != null ? contribution.stagingValue : contribution.rawValue;
					if (goldenValue.equals(comparable))
					{
						goldenMatchCount++;
					}
				}
			}

			final ArrayNode contributions = node.putArray("contributions");
			final Set<String> distinctValues = new LinkedHashSet<String>();
			boolean anyRestricted = false;
			for (final FieldContribution contribution : field.contributions)
			{
				final ObjectNode contributionNode = contributions.addObject();
				contributionNode.put("masterKey", contribution.masterKey);
				contributionNode.put("sourceSystem", contribution.sourceSystem);
				if (contribution.restricted)
				{
					contributionNode.put("restricted", true);
					anyRestricted = true;
					continue; // no landingColumn/rawValue/stagingValue/cleaningSteps/retained - see class Javadoc
				}
				contributionNode.put("landingColumn", contribution.landingColumn);
				contributionNode.put("rawValue", contribution.rawValue);
				contributionNode.put("stagingValue", contribution.stagingValue);
				final ArrayNode steps = contributionNode.putArray("cleaningSteps");
				for (final String step : contribution.cleaningSteps)
				{
					steps.add(step);
				}
				// the comparable value for "do sources disagree": the staged (typed/cleaned) value when there is
				// one, the raw landing value otherwise - a contribution with neither (no mapping reaches this
				// source for this field) contributes nothing to the comparison, never a spurious "differs"
				final String comparable = contribution.stagingValue != null ? contribution.stagingValue : contribution.rawValue;
				// "do sources disagree" is judged on the FOLDED comparable value (product-owner decision,
				// 2026-09-22, coordinator review - live record Companies_Master/Master//root/Company id 108,
				// "BANK OF CHINA"/"Bank of China": a difference of case/accents/whitespace ALONE is not a
				// genuine disagreement, and used to render a false DIFF badge with the value struck through, read
				// by the user as "why is this value rejected?"). CleaningSteps.foldKey is the ONE fold this
				// whole module uses for exactly this purpose (see its own Javadoc, "la valeur stockee garde ses
				// accents et sa casse") - never a second, ad-hoc normalization here. A value that folds down to
				// nothing (whitespace/combining marks only) falls back to itself, so it still participates in
				// the distinct-value count rather than silently vanishing from it.
				final String folded = comparable == null ? null : CleaningSteps.foldKey(comparable);
				if (comparable != null)
				{
					distinctValues.add(folded != null ? folded : comparable);
				}

				// golden comparison + retained flag - null/unknown when there is nothing to compare (no golden
				// value, or this contribution has none of its own), never a confident answer guessed from silence.
				final Boolean matchesGolden = goldenRestricted || goldenValue == null || comparable == null ? null : Boolean.valueOf(goldenValue.equals(comparable));
				if (matchesGolden == null)
				{
					contributionNode.putNull("matchesGolden");
				}
				else
				{
					contributionNode.put("matchesGolden", matchesGolden.booleanValue());
				}
				// EQUIVALENT (coordinator review, 2026-09-22): true only when the two values are NOT strictly
				// equal (matchesGolden already says that) but fold to the same key - a case/accent/whitespace-only
				// difference, which the graphic screen renders without a strikethrough instead of a false DIFF.
				// Null under the exact same "nothing to compare" guard as matchesGolden - never a guessed answer
				// against an unknown golden value.
				final Boolean equivalentToGolden;
				if (matchesGolden == null)
				{
					equivalentToGolden = null;
				}
				else if (matchesGolden.booleanValue())
				{
					equivalentToGolden = Boolean.FALSE; // already an exact match - not "equivalent but different"
				}
				else
				{
					final String foldedGolden = CleaningSteps.foldKey(goldenValue);
					equivalentToGolden = Boolean.valueOf(foldedGolden != null && folded != null && foldedGolden.equals(folded));
				}
				if (equivalentToGolden == null)
				{
					contributionNode.putNull("equivalentToGolden");
				}
				else
				{
					contributionNode.put("equivalentToGolden", equivalentToGolden.booleanValue());
				}
				// with exactly one contributing source, EVERY field trivially "deduces" that source as retained -
				// technically true, meaningless to show (coordinator review, 2026-09-22: "six identical badges"
				// on a one-source record) - so the deduction is only ever drawn once a field has more than one
				// contribution to choose among; a real (KNOWN) survivorship answer is never suppressed this way,
				// only the DEDUCED guess is. field.contributions.size() (not pInput.sources.size()) is the right
				// count: ebx.RecordLineageReader always builds exactly one contribution per contributing source
				// for every field, so the two counts agree in production, and this stays correct even for a
				// field a caller built with its own, narrower contributions list (fixtures included).
				final boolean retained = knownAnswer ? contribution.masterKey.equals(field.knownRetainedMasterKey)
					: field.contributions.size() > 1 && matchesGolden != null && matchesGolden.booleanValue() && goldenMatchCount == 1;
				contributionNode.put("retained", retained);
				if (retained)
				{
					contributionNode.put("retainedReason", knownAnswer ? "KNOWN" : "DEDUCED");
				}
			}
			// a field with any hidden contribution has an UNKNOWN answer to "do sources disagree" - never a
			// confident false just because the values we could see happened to agree (or there was only one).
			// distinctValues itself already holds FOLDED values (see the loop above) - two sources differing
			// only by case/accents/whitespace count as ONE distinct value here, never a spurious "differs".
			if (anyRestricted)
			{
				node.putNull("differs");
			}
			else
			{
				node.put("differs", distinctValues.size() > 1);
			}
			// the survivorship-rule sentence is only ever shown next to a KNOWN retained cell (coordinator
			// review, 2026-09-22) - never alongside a merely DEDUCED one, which is this class's own inference,
			// not a rule the record's own data ever stated.
			if (knownAnswer && field.survivorshipRule != null)
			{
				node.put("survivorshipRule", field.survivorshipRule);
			}
		}

		if (pInput.fieldLineageNote != null)
		{
			out.put("fieldLineageNote", pInput.fieldLineageNote);
		}

		// "Compare with suspects" panel (task brief, 2026-09-22, "decide on a suspect" lot) - a SEPARATE
		// top-level object, emitted ONLY when there is at least one suspect this reader could resolve field
		// values for (an empty comparison panel would have nothing to decide). The ANCHOR's own values are
		// never repeated here - the renderer reads them straight off record.fields/record.restrictedFields,
		// the one place this model already redacts them.
		if (!pInput.suspectFieldSets.isEmpty())
		{
			final ObjectNode comparison = out.putObject("comparison");
			final ArrayNode planFieldsOut = comparison.putArray("planFields");
			for (final PlanFieldRef planField : pInput.matchPlanFields)
			{
				final ObjectNode node = planFieldsOut.addObject();
				node.put("field", planField.field);
				if (planField.algorithm != null)
				{
					node.put("algorithm", planField.algorithm);
				}
			}
			final ArrayNode suspectsOut = comparison.putArray("suspects");
			for (final SuspectFieldSet suspect : pInput.suspectFieldSets)
			{
				final ObjectNode node = suspectsOut.addObject();
				node.put("masterKey", suspect.masterKey);
				if (suspect.restricted)
				{
					node.put("restricted", true);
					continue; // no field values at all - same all-or-nothing rule as GroupMember/SourceChain#restricted
				}
				final ObjectNode fieldsOut = node.putObject("fields");
				for (final Map.Entry<String, FieldValue> entry : suspect.fields.entrySet())
				{
					final ObjectNode fieldNode = fieldsOut.putObject(entry.getKey());
					final FieldValue suspectValue = entry.getValue();
					if (suspectValue.restricted)
					{
						fieldNode.put("restricted", true);
						fieldNode.put("chip", "UNKNOWN");
						continue;
					}
					fieldNode.put("value", suspectValue.value);
					final FieldValue anchorValue = pInput.recordFields == null ? null : pInput.recordFields.get(entry.getKey());
					fieldNode.put("chip", RecordLineage.chipFor(anchorValue, suspectValue));
				}
			}
		}

		return out;
	}

	/**
	 * {@code =}/{@code ~=}/{@code !=}/empty for the "compare with suspects" panel (task brief, 2026-09-22) -
	 * the SAME fold-based equivalence rule the field-by-field table already uses for {@code
	 * equivalentToGolden} (a case/accent/whitespace-only difference is {@code EQUIVALENT}, never a genuine
	 * {@code DIFF}), reused via the identical {@link CleaningSteps#foldKey} helper rather than a second,
	 * ad-hoc normalization. {@code UNKNOWN} whenever either side is restricted or simply absent from {@code
	 * pInput.recordFields}/{@code pSuspect} - never a confident answer guessed from missing data. {@code
	 * EMPTY} when EITHER side has no value (a suspect's blank field is not comparable, but is not itself a
	 * disagreement either - shown as its own distinct chip rather than folded into {@code DIFF}).
	 */
	private static String chipFor(final FieldValue pAnchor, final FieldValue pSuspect)
	{
		if (pAnchor == null || pAnchor.restricted || pSuspect == null || pSuspect.restricted)
		{
			return "UNKNOWN";
		}
		final String a = pAnchor.value;
		final String b = pSuspect.value;
		if (a == null || b == null)
		{
			return "EMPTY";
		}
		if (a.equals(b))
		{
			return "EQ";
		}
		final String foldedA = CleaningSteps.foldKey(a);
		final String foldedB = CleaningSteps.foldKey(b);
		if (foldedA != null && foldedB != null && foldedA.equals(foldedB))
		{
			return "EQUIVALENT";
		}
		return "DIFF";
	}

	/** One `sources[]`/`suspectSources[]` entry - factored out of {@link #assemble} so both arrays are built by the exact same code (a suspect lane is redacted/shaped identically to a plain contributing one, `SourceChain#suspect` is the only thing that tells them apart, and that flag is emitted here too). */
	private static ObjectNode buildSourceNode(final SourceChain pSource)
	{
		final ObjectNode node = RecordLineage.MAPPER.createObjectNode();
		node.put("masterKey", pSource.masterKey);
		node.put("golden", pSource.golden);
		node.put("sourceSystem", pSource.sourceSystem);
		if (pSource.suspect)
		{
			node.put("suspect", true);
		}
		// not sensitive (a declared project-level priority number, not a business value) - emitted even
		// for a restricted source so colour assignment stays consistent regardless of the caller's own
		// permissions (see SourceChain#sourceOrder's own Javadoc).
		if (pSource.sourceOrder != null)
		{
			node.put("sourceOrder", pSource.sourceOrder.intValue());
		}
		if (pSource.restricted)
		{
			node.put("restricted", true);
			return node; // no sourceId, no landing/staging block - see class Javadoc
		}
		// fix, 2026-09-22 (task brief item D): a SUSPECT lane's own cleaning-step kinds, by source system -
		// see SourceChain#cleaningStepKinds' own Javadoc for why a plain contributing source never sets this
		// (the graphic builder keeps deriving that case from fields[].contributions[] instead).
		if (pSource.cleaningStepKinds != null)
		{
			final ArrayNode kinds = node.putArray("cleaningStepKinds");
			for (final String kind : pSource.cleaningStepKinds)
			{
				kinds.add(kind);
			}
		}
		node.put("sourceId", pSource.sourceId);
		final ObjectNode landing = node.putObject("landing");
		landing.put("dataset", pSource.landingDataset);
		landing.put("table", pSource.landingTable);
		landing.put("key", pSource.landingKey);
		landing.put("loadId", pSource.loadId);
		landing.put("status", pSource.landingStatus);
		landing.put("receivedAt", pSource.receivedAt == null ? null : pSource.receivedAt.toInstant().toString());
		if (pSource.landingUrl != null)
		{
			landing.put("url", pSource.landingUrl);
		}
		final ObjectNode staging = node.putObject("staging");
		staging.put("table", pSource.stagingTable);
		staging.put("key", pSource.stagingKey);
		if (pSource.stagingUrl != null)
		{
			staging.put("url", pSource.stagingUrl);
		}
		return node;
	}

	/** One `merge.group.matched[]`/`merge.group.suspects[]` entry - see {@link GroupMember}'s own Javadoc for the all-or-nothing redaction rule. */
	private static ObjectNode buildGroupMemberNode(final GroupMember pMember)
	{
		final ObjectNode node = RecordLineage.MAPPER.createObjectNode();
		if (pMember == null || pMember.restricted)
		{
			node.put("restricted", true);
			return node;
		}
		node.put("masterKey", pMember.masterKey);
		if (pMember.label != null)
		{
			node.put("label", pMember.label);
		}
		if (pMember.sourceSystem != null)
		{
			node.put("sourceSystem", pMember.sourceSystem);
		}
		if (pMember.processId != null)
		{
			node.put("processId", pMember.processId);
		}
		return node;
	}

	// small helper the reader/tests can both use to build a one-element list without importing Arrays/Collections at every call site
	public static <T> List<T> listOf(final T pOnly)
	{
		final List<T> out = new ArrayList<T>();
		out.add(pOnly);
		return out;
	}
}
