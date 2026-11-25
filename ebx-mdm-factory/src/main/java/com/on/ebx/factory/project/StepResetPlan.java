package com.on.ebx.factory.project;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.project.ProjectDeletionPlan.MatchingFacts;
import com.on.ebx.factory.project.ProjectDeletionPlan.SourceFacts;

/**
 * Decides what cleaning up a project's pipeline FROM a given stage onward would actually touch, and whether
 * it is allowed to right now - no EBX type anywhere, same discipline as {@link ProjectDeletionPlan}, which
 * this class reuses for its input ({@link ProjectDeletionPlan.Facts}/{@link SourceFacts}/{@link
 * MatchingFacts} - not duplicated) rather than the object it decides about (this is a RESET, not a delete of
 * the project itself: the project's own rows, {@code ProjectSource} included, are never touched).
 * <p>
 * Written 2026-09-23 answering the product owner's request: <i>"Pouvoir nettoyer cette etape en cas d'erreur serait
 * interessant. Sachant que nettoyer une etape doit egalement nettoyer les suivantes."</i> - a second LLM pass
 * on the Staging step created a duplicate entity; the fix needed a way to blow away Staging (and everything
 * downstream of it: Master, Matching) without deleting the whole project and starting over from Sources.
 * <p>
 * <b>Pipeline order and reset direction.</b> {@link Stage} is declared in PIPELINE order ({@code LANDING} <
 * {@code STAGING} < {@code MASTER} < {@code MATCHING}); resetting {@code from} a stage resets that stage AND
 * every stage downstream of it (higher ordinal), since each stage's generated objects are built FROM the
 * previous one's output. {@link #build} therefore visits stage GROUPS most-downstream-first ({@code
 * MATCHING}, then {@code MASTER}, then {@code STAGING}, then {@code LANDING} - only the groups at or after
 * {@code from}) - the same "downstream before upstream, so a dependency such as MAME's metadataset living
 * inside the master dataspace is always cleared before the dataspace itself" rule {@link ProjectDeletionPlan}
 * already established, generalised across stages. Within one group the sub-order is the same as {@link
 * ProjectDeletionPlan}'s own: matching configuration (owns a metadataset INSIDE the master dataspace) before
 * the master dataspace; flows before dataspaces before models.
 * <p>
 * <b>Three-way disposition per generated object, DELIBERATELY STRICTER than {@link ProjectDeletionPlan}'s own
 * plain eligible/ineligible</b> (the product owner's own framing, quoted in full because it inverts the deletion
 * screen's own posture): <i>"NON eligible, et alors le reset est REFUSE (pas ignore : on ne peut pas remettre
 * une etape a zero en laissant un objet genere en place - c'est different de la suppression ou l'option est
 * simplement decochee)"</i>:
 * <ul>
 * <li>{@link Disposition#DELETE} - a generated object this reset must remove for the stage to be genuinely
 * clean;</li>
 * <li>{@link Disposition#EXCLUDED} - the deliberate carve-outs: a Connectors flow whose mapping writes into ANOTHER project's
 * landing dataspace ({@link ProjectDeletionPlan#foreignFlowReason}, 2026-10-03: left alone, the reset goes on), and the
 * master zone (dataspace and model) of a {@code TARGET_EXISTS} project: never touched (it belongs to the target instance), but this does NOT block
 * the rest of the reset - the factory's own rows and the matching configuration on that table are still
 * cleaned (the product owner: <i>"zone MDM d'un projet TARGET_EXISTS -&gt; jamais supprimee, mais les lignes de la
 * factory et la config matching le sont"</i>). This is the one place {@link #build} does NOT propagate
 * upward the way {@link ProjectDeletionPlan#matchingItem} does (an ineligible master dataspace there also
 * makes its matching item ineligible) - see {@link #build}'s own comment on {@code MATCHING_CONFIG} for why
 * that propagation is deliberately NOT repeated here;</li>
 * <li>{@link Disposition#REFUSED} - a delivered project, a dataspace/model whose stored name is not the one
 * this factory would itself compute, or with no {@code PipelineStep} evidence the factory created it: {@link
 * #build} still lists the item (with its reason, for display), but {@link Plan#refusal} is then non-null and
 * the whole reset must not run - a stage cannot be "cleaned" while leaving a generated object nobody can
 * even prove the factory owns still sitting in place.</li>
 * </ul>
 * A generated object that is simply absent (a blank stored name: that step never ran) is not listed at all -
 * same rule as {@link ProjectDeletionPlan}.
 * <p>
 * <b>What is NOT covered by disposition at all: rows and {@code PipelineStep} resets.</b> Every table row the
 * reset deletes ({@link Plan#rowCounts}, pre-counted by the EBX-facing gathering step and only ever scoped to
 * the tables this {@code from} actually touches - see {@link Facts#rowCounts}'s own Javadoc for why counting
 * itself is not pure) and every {@code PipelineStep} name moved to {@code RESET} ({@link #stepNames}, a pure
 * function of {@code from} alone - no EBX data needed, the step vocabulary of each stage is fixed) are always
 * acted on once the reset is not refused; they carry no eligibility of their own.
 */
public final class StepResetPlan
{
	private StepResetPlan()
	{
	}

	/** Pipeline order - see the class Javadoc. Resetting FROM a stage resets it and every later (higher-ordinal) one. */
	public enum Stage
	{
		LANDING, STAGING, MASTER, MATCHING
	}

	public enum Kind
	{
		MATCHING_CONFIG, MASTER_DATASPACE, MASTER_MODEL, STAGING_DATASPACE, STAGING_MODEL, LANDING_FLOW, LANDING_DATASPACE, LANDING_MODEL
	}

	public enum Disposition
	{
		DELETE, EXCLUDED, REFUSED
	}

	// =================================================================================================== input facts

	/**
	 * Wraps {@link ProjectDeletionPlan.Facts} (reused whole, not duplicated - it already carries every stored/
	 * computed name, every step-evidence boolean and the {@link SourceFacts}/{@link MatchingFacts} this plan
	 * needs) plus the one thing it does not carry: row counts already scoped to the chosen {@code from} stage.
	 * <p>
	 * <b>Why counting is not done in this pure class</b>: unlike {@link ProjectDeletionPlan}, where "the
	 * project's own rows" is always the SAME fixed set regardless of what is ticked, a step reset's row set
	 * genuinely depends on {@code from} - e.g. {@code TransferReject} rows are filtered to {@code stage=MASTER}
	 * when {@code from=MASTER} but ALL of them (both stages) when {@code from=STAGING} (a {@code StagingEntity}
	 * row cannot survive with a dangling mandatory foreign key from a {@code STAGING}-stage reject). Computing
	 * that filtered count needs a live EBX query, so it is the EBX-facing {@code StepResetter} that counts and
	 * hands the already-scoped result here, exactly like {@link ProjectDeletionPlan.Facts#rowCounts} itself is
	 * handed in pre-computed rather than counted by {@link ProjectDeletionPlan#build}.
	 */
	public static final class Facts
	{
		public final ProjectDeletionPlan.Facts base;
		/** Table name to row count, in the SAME deletion order {@code StepResetter} will delete them in, already scoped to one {@code from}. */
		public final Map<String, Integer> rowCounts;

		public Facts(final ProjectDeletionPlan.Facts pBase, final Map<String, Integer> pRowCounts)
		{
			this.base = pBase;
			this.rowCounts = pRowCounts == null ? new LinkedHashMap<String, Integer>() : pRowCounts;
		}
	}

	// ========================================================================================================== plan

	public static final class Item
	{
		public final Kind kind;
		public final String name;
		public final String detail;
		public final Disposition disposition;
		/** Non-null only when {@link #disposition} is not {@link Disposition#DELETE}. */
		public final String reason;

		Item(final Kind pKind, final String pName, final String pDetail, final Disposition pDisposition, final String pReason)
		{
			this.kind = pKind;
			this.name = pName;
			this.detail = pDetail;
			this.disposition = pDisposition;
			this.reason = pReason;
		}
	}

	public static final class Plan
	{
		public final String projectCode;
		public final Stage from;
		/** Execution order - see the class Javadoc. */
		public final List<Item> items;
		public final Map<String, Integer> rowCounts;
		/** {@code PipelineStep} step names moved to {@code RESET} - see {@link #stepNames}. */
		public final List<String> stepNames;
		/** Non-null: the whole reset must be refused (delivered project, or a {@link Disposition#REFUSED} item) - {@link #items} is still populated for display. */
		public final String refusal;

		Plan(final String pProjectCode, final Stage pFrom, final List<Item> pItems, final Map<String, Integer> pRowCounts, final List<String> pStepNames,
			final String pRefusal)
		{
			this.projectCode = pProjectCode;
			this.from = pFrom;
			this.items = pItems;
			this.rowCounts = pRowCounts;
			this.stepNames = pStepNames;
			this.refusal = pRefusal;
		}
	}

	/**
	 * Lot C1-D: the {@code PipelineStep} names the generation of a Centralisation domain writes, in order. A reset of a domain moves them to
	 * {@code RESET} (so {@code ebx.DomainGenerator} runs them again) after {@link DomainCleanupPlan#build} removed what they generated.
	 */
	public static List<String> domainStepNames()
	{
		return java.util.Collections.unmodifiableList(java.util.Arrays.asList("ACCEL_DATASPACE", "ACCEL_MODEL", "ACCEL_REFERENCE_ROWS", "ACCEL_SCRIPTS", "ACCEL_FORMS",
			"ACCEL_ROLES", "ACCEL_WORKFLOW", "ACCEL_MATCHING", "ACCEL_PERMISSIONS", "ACCEL_SUMMARY", "ACCEL_GENERATE_RUN"));
	}

	/** Lot C1-D: what resetting a generated domain removes - the {@code DomainObject} kinds learnt by the plan, see {@link DomainCleanupPlan}. */
	public static List<DomainCleanupPlan.Item> domainItems(final List<DomainCleanupPlan.Row> pDomainObjects)
	{
		return DomainCleanupPlan.build(pDomainObjects);
	}

	/** Whether stage {@code pGroup} is reset when starting {@code pFrom} - pipeline order, see the class Javadoc. */
	private static boolean inScope(final Stage pFrom, final Stage pGroup)
	{
		return pGroup.ordinal() >= pFrom.ordinal();
	}

	/** The one place every disposition rule in the class Javadoc is applied, in the execution order {@link Plan#items} is then read in. */
	public static Plan build(final Stage pFrom, final Facts pFacts)
	{
		final ProjectDeletionPlan.Facts f = pFacts.base;
		final List<Item> items = new ArrayList<Item>();
		final List<String> refusalReasons = new ArrayList<String>();

		final boolean targetExists = "TARGET_EXISTS".equals(f.mode);
		final Eligibility masterDataspaceEligibility = StepResetPlan.dataspaceEligibility(f.delivered, f.masterDataspaceStored, f.masterDataspaceComputed,
			f.masterModelStepDone && f.masterDatasetCreatedByFactory, targetExists,
			"the master zone belongs to the target instance (TARGET_EXISTS project): not the factory's to delete");

		// ---- MATCHING group (this project's own matching configuration; MASTER's own dataspace/model reset below)
		if (StepResetPlan.inScope(pFrom, Stage.MATCHING) && !StepResetPlan.blank(f.masterDataspaceStored))
		{
			for (final MatchingFacts matching : f.matching)
			{
				// Deliberately NOT propagated from masterDataspaceEligibility's own EXCLUDED state (unlike
				// ProjectDeletionPlan.matchingItem, which DOES propagate an ineligible master dataspace to its
				// matching items) - see the class Javadoc: a TARGET_EXISTS master zone is excluded from deletion,
				// but its matching configuration is still cleaned. A genuine REFUSED (delivered / name mismatch /
				// no creation evidence) DOES propagate: this factory cannot safely touch a matching configuration
				// living inside a master dataspace it cannot even prove it owns.
				if (masterDataspaceEligibility.kind == Eligibility.Kind.REFUSED)
				{
					final Item item = new Item(Kind.MATCHING_CONFIG, matching.tablePath, "Entity " + matching.entityName + ".", Disposition.REFUSED,
						"the master zone this table belongs to cannot be verified: " + masterDataspaceEligibility.reason);
					items.add(item);
					refusalReasons.add(item.kind + " " + item.name + ": " + item.reason);
				}
				else
				{
					items.add(new Item(Kind.MATCHING_CONFIG, matching.tablePath, "Entity " + matching.entityName + ".", Disposition.DELETE, null));
				}
			}
		}

		// ---- MASTER group
		if (StepResetPlan.inScope(pFrom, Stage.MASTER) && !StepResetPlan.blank(f.masterDataspaceStored))
		{
			StepResetPlan.addDataspaceItem(items, refusalReasons, Kind.MASTER_DATASPACE, f.masterDataspaceStored,
				"Dataset " + f.masterDatasetStored + " and its own tables go with it.", masterDataspaceEligibility);
			final Eligibility masterModelEligibility = StepResetPlan.modelEligibility(f.delivered, f.masterModelStepDone, targetExists,
				"the master zone belongs to the target instance (TARGET_EXISTS project): not the factory's to delete");
			StepResetPlan.addModelItem(items, refusalReasons, Kind.MASTER_MODEL, f.masterModelName, "The published data model, not the dataspace/dataset above.",
				masterModelEligibility);
		}

		// ---- STAGING group
		if (StepResetPlan.inScope(pFrom, Stage.STAGING) && !StepResetPlan.blank(f.stagingDataspaceStored))
		{
			final Eligibility stagingDataspaceEligibility = StepResetPlan.dataspaceEligibility(f.delivered, f.stagingDataspaceStored, f.stagingDataspaceComputed,
				f.stagingModelStepDone && f.stagingDatasetCreatedByFactory, false, null);
			StepResetPlan.addDataspaceItem(items, refusalReasons, Kind.STAGING_DATASPACE, f.stagingDataspaceStored,
				"Dataset " + f.stagingDatasetStored + " and its own tables go with it.", stagingDataspaceEligibility);
			final Eligibility stagingModelEligibility = StepResetPlan.modelEligibility(f.delivered, f.stagingModelStepDone, false, null);
			StepResetPlan.addModelItem(items, refusalReasons, Kind.STAGING_MODEL, f.stagingModelName, "The published data model, not the dataspace/dataset above.",
				stagingModelEligibility);
		}

		// ---- LANDING group: flows (every source), then the shared dataspace, then each source's own model
		if (StepResetPlan.inScope(pFrom, Stage.LANDING))
		{
			for (final SourceFacts source : f.sources)
			{
				for (final String mappingId : source.inboundMappingIds)
				{
					if (f.delivered)
					{
						final Item item = new Item(Kind.LANDING_FLOW, mappingId, "Source " + source.sourceSystem + ".", Disposition.REFUSED,
							StepResetPlan.DELIVERED_REASON);
						items.add(item);
						refusalReasons.add(item.kind + " " + item.name + ": " + item.reason);
					}
					else
					{
						final String foreign = ProjectDeletionPlan.foreignFlowReason(f.landingDataspaceStored, source, mappingId);
						if (foreign != null)
						{
							// a mapping of ANOTHER project is only left alone (the stale pointer goes with the table rows, the reset goes on);
							// one that could not be read is refused - it may be this project's own, so retry once Connectors answers
							final boolean unreadable = SourceFacts.MAPPING_UNREADABLE.equals(source.inboundMappingDataspaces.get(mappingId));
							final Item item = new Item(Kind.LANDING_FLOW, mappingId, "Source " + source.sourceSystem + ".",
								unreadable ? Disposition.REFUSED : Disposition.EXCLUDED, foreign);
							items.add(item);
							if (unreadable)
							{
								refusalReasons.add(item.kind + " " + item.name + ": " + item.reason);
							}
						}
						else
						{
							items.add(new Item(Kind.LANDING_FLOW, mappingId, "Source " + source.sourceSystem + ".", Disposition.DELETE, null));
						}
					}
				}
			}
			if (!StepResetPlan.blank(f.landingDataspaceStored))
			{
				final boolean anyCreatedByFactory = StepResetPlan.anyLandingDatasetCreatedByFactory(f.sources);
				final Eligibility landingDataspaceEligibility = StepResetPlan.dataspaceEligibility(f.delivered, f.landingDataspaceStored, f.landingDataspaceComputed,
					anyCreatedByFactory, false, null);
				StepResetPlan.addDataspaceItem(items, refusalReasons, Kind.LANDING_DATASPACE, f.landingDataspaceStored,
					"Shared by every source of this project; its per-source datasets go with it.", landingDataspaceEligibility);
			}
			for (final SourceFacts source : f.sources)
			{
				if (StepResetPlan.blank(source.landingModel))
				{
					continue;
				}
				final Eligibility landingModelEligibility = StepResetPlan.modelEligibility(f.delivered, source.landingModelStepDone, false, null);
				StepResetPlan.addModelItem(items, refusalReasons, Kind.LANDING_MODEL, source.landingModel, "Landing model of source " + source.sourceSystem + ".",
					landingModelEligibility);
			}
		}

		final String refusal = f.delivered ? StepResetPlan.DELIVERED_REASON : refusalReasons.isEmpty() ? null : StepResetPlan.join(refusalReasons, "; ");
		return new Plan(f.projectCode, pFrom, items, pFacts.rowCounts, StepResetPlan.stepNames(pFrom), refusal);
	}

	/** {@code pConfirm} must equal the project code exactly; a refusal already computed by {@link #build} always wins over a wrong/missing confirm, so the real reason is never masked by "type the code". */
	public static String validate(final Plan pPlan, final String pConfirm)
	{
		if (pPlan.refusal != null)
		{
			return pPlan.refusal;
		}
		if (pConfirm == null || !pConfirm.equals(pPlan.projectCode))
		{
			return "Pass ?confirm=" + pPlan.projectCode + " (the exact project code) to actually clean up from " + pPlan.from
				+ " onward - this cannot be undone.";
		}
		return null;
	}

	// ============================================================================================ step-name vocabulary

	/**
	 * The factory tables whose rows a reset FROM {@code pFrom} deletes, in deletion order (children before the tables their foreign keys name). Audit F3-10 (2026-11-12):
	 * resetting the landing also removes the {@code SourceDrift} rows of its landing tables (a required foreign key to {@code LandingTable}: they describe a landing that no longer
	 * exists, and EBX would leave them pointing at nothing). {@code AgentProposal}, {@code AgentRun} and {@code AgentDefinition} are NOT reset: they are the agents' history and
	 * configuration, they point to the project and never to a landing or staging row; a decision still waiting on data the reset erased fails with a coded message when applied.
	 */
	public static List<String> rowTables(final Stage pFrom)
	{
		final List<String> out = new ArrayList<String>();
		final boolean staging = StepResetPlan.inScope(pFrom, Stage.STAGING);
		if (staging)
		{
			out.add("MappingStep");
			out.add("AttributeMapping");
			out.add("QualityRule");
			out.add("CapabilitySuggestion");
		}
		out.add("MatchPlan");
		out.add("TransferReject");
		if (staging)
		{
			out.add("TransferFeed");
			out.add("StagingAttribute");
			out.add("StagingEntity");
		}
		if (StepResetPlan.inScope(pFrom, Stage.LANDING))
		{
			out.add("SourceDrift");
			out.add("LandingColumn");
			out.add("LandingRelation");
			out.add("LandingTable");
		}
		return out;
	}

	/**
	 * The {@code PipelineStep} step names a reset FROM {@code pFrom} moves to {@code RESET} - a pure function of
	 * {@code pFrom} alone: the step vocabulary of each stage is fixed (checked against the actual {@code
	 * PipelineSteps.begin}/{@code isDone} call sites of every generator in package {@code ebx}, one 2026-09-23
	 * sweep - see {@code docs/STEP-RESET.md} for the by-class table this was verified against). WHICH ROWS
	 * carry these names (and their {@code scope}) is an EBX-side detail {@code StepResetter} resolves - a plain
	 * project-wide step resets every scope; {@code MATCHING_*} steps are scoped by ENTITY NAME (one row per
	 * promoted entity, not project-wide) and {@code CAPABILITY_APPLY}/{@code CAPABILITY_REMOVE} are scoped by
	 * the {@code CapabilitySuggestion} row's own id, not a literal zone string - neither is a plain name match,
	 * so this method only names WHICH step names are in scope, never how to find their rows.
	 */
	public static List<String> stepNames(final Stage pFrom)
	{
		final List<String> out = new ArrayList<String>();
		if (StepResetPlan.inScope(pFrom, Stage.MATCHING))
		{
			out.add("MATCHING_SUGGEST_LLM");
			out.add("MATCHING_CONFIG");
			out.add("MATCHING_RUN");
			out.add("MATCHING_SURVIVORSHIP");
			out.add("MATCHING_SIMULATE_PREVIEW");
			out.add("GOLDEN_KEY_REFRESH");
		}
		if (StepResetPlan.inScope(pFrom, Stage.MASTER))
		{
			out.add("MASTER_MODEL");
			out.add("MASTER_DATASET");
			out.add("MASTER_LOAD");
			// CAPABILITY_APPLY/CAPABILITY_REMOVE of a CapabilitySuggestion whose zone is MASTER - the CapabilitySuggestion
			// rows themselves survive a MASTER-only reset (they are STAGING-scoped data), only their applied-to-MASTER
			// effect (now gone with the master model) is invalidated. Resolved by suggestion id, not by this name alone -
			// see the class Javadoc.
			out.add("CAPABILITY_APPLY");
			out.add("CAPABILITY_REMOVE");
		}
		if (StepResetPlan.inScope(pFrom, Stage.STAGING))
		{
			out.add("STAGING_SUGGEST");
			out.add("STAGING_MODEL");
			out.add("STAGING_DATASET");
			out.add("STAGING_LOAD");
			out.add("QUALITY_SUGGEST");
			out.add("QUALITY_SUGGEST_LLM");
			out.add("CAPABILITY_SUGGEST_LLM");
			// every CapabilitySuggestion row is deleted at this stage (see docs/STEP-RESET.md), so every
			// CAPABILITY_APPLY/CAPABILITY_REMOVE step of this project is in scope too - the MASTER group above
			// already added the names; kept here as a comment, not a duplicate add, since this is a name list.
		}
		if (StepResetPlan.inScope(pFrom, Stage.LANDING))
		{
			out.add("LANDING_MODEL");
			out.add("LANDING_DATASET");
			out.add("LANDING_FLOWS");
			out.add("LANDING_LOAD");
		}
		return out;
	}

	// ==================================================================================================== eligibility

	private static final class Eligibility
	{
		enum Kind
		{
			ELIGIBLE, EXCLUDED, REFUSED
		}

		final Kind kind;
		final String reason;

		private Eligibility(final Kind pKind, final String pReason)
		{
			this.kind = pKind;
			this.reason = pReason;
		}

		private static Eligibility ok()
		{
			return new Eligibility(Kind.ELIGIBLE, null);
		}

		private static Eligibility excluded(final String pReason)
		{
			return new Eligibility(Kind.EXCLUDED, pReason);
		}

		private static Eligibility refuse(final String pReason)
		{
			return new Eligibility(Kind.REFUSED, pReason);
		}
	}

	private static Disposition dispositionOf(final Eligibility.Kind pKind)
	{
		switch (pKind)
		{
			case ELIGIBLE:
				return Disposition.DELETE;
			case EXCLUDED:
				return Disposition.EXCLUDED;
			default:
				return Disposition.REFUSED;
		}
	}

	private static void addDataspaceItem(final List<Item> pItems, final List<String> pRefusalReasons, final Kind pKind, final String pName, final String pDetail,
		final Eligibility pEligibility)
	{
		final Item item = new Item(pKind, pName, pDetail, StepResetPlan.dispositionOf(pEligibility.kind), pEligibility.reason);
		pItems.add(item);
		if (item.disposition == Disposition.REFUSED)
		{
			pRefusalReasons.add(item.kind + " " + item.name + ": " + item.reason);
		}
	}

	private static void addModelItem(final List<Item> pItems, final List<String> pRefusalReasons, final Kind pKind, final String pName, final String pDetail,
		final Eligibility pEligibility)
	{
		StepResetPlan.addDataspaceItem(pItems, pRefusalReasons, pKind, pName, pDetail, pEligibility);
	}

	/** Same rule as {@link ProjectDeletionPlan}'s own private helper of the same shape - see that class' Javadoc. */
	private static Eligibility dataspaceEligibility(final boolean pDelivered, final String pStored, final String pComputed, final boolean pCreatedByFactory,
		final boolean pExtraGate, final String pExtraGateReason)
	{
		if (pDelivered)
		{
			return Eligibility.refuse(StepResetPlan.DELIVERED_REASON);
		}
		if (pExtraGate)
		{
			return Eligibility.excluded(pExtraGateReason);
		}
		if (!pComputed.equals(pStored.trim()))
		{
			return Eligibility.refuse("this dataspace (" + pStored + ") is not the name this factory would itself generate (" + pComputed
				+ "): refusing to guess at ownership from the name alone.");
		}
		if (!pCreatedByFactory)
		{
			return Eligibility.refuse("no PipelineStep evidence that the factory actually CREATED this dataspace (as opposed to finding one that already existed "
				+ "under a matching name): refusing to guess at ownership from the name alone.");
		}
		return Eligibility.ok();
	}

	private static Eligibility modelEligibility(final boolean pDelivered, final boolean pStepDone, final boolean pExtraGate, final String pExtraGateReason)
	{
		if (pDelivered)
		{
			return Eligibility.refuse(StepResetPlan.DELIVERED_REASON);
		}
		if (pExtraGate)
		{
			return Eligibility.excluded(pExtraGateReason);
		}
		if (!pStepDone)
		{
			return Eligibility.refuse("no PipelineStep evidence that the factory actually published this data model: refusing to guess at ownership from the "
				+ "name alone.");
		}
		return Eligibility.ok();
	}

	private static boolean anyLandingDatasetCreatedByFactory(final List<SourceFacts> pSources)
	{
		for (final SourceFacts source : pSources)
		{
			if (source.landingDatasetCreatedByFactory)
			{
				return true;
			}
		}
		return false;
	}

	private static boolean blank(final String pValue)
	{
		return pValue == null || pValue.trim().isEmpty();
	}

	private static String join(final List<String> pValues, final String pSeparator)
	{
		final StringBuilder sb = new StringBuilder();
		for (final String value : pValues)
		{
			if (sb.length() > 0)
			{
				sb.append(pSeparator);
			}
			sb.append(value);
		}
		return sb.toString();
	}

	/** Same text as {@code ProjectDeletionPlan}'s own private constant - kept identical so both screens read the same way; not accessible directly (that field is private), so restated here rather than widening that class' visibility for one string. */
	private static final String DELIVERED_REASON = "this project was delivered here by EBX Staging: remove it on the origin instance, or by hand.";
}
