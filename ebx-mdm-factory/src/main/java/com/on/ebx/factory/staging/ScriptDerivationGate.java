package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Lot M5 (2026-10-04): what to do, and to say, about a staging attribute whose {@code derivation} is {@code SCRIPT} - a value EBX
 * itself keeps up to date as a STORED field through two table triggers published on the staging model ({@code quality.ScriptDerivation},
 * lot T1, 2026-10-04: {@code beforeCreate} and {@code beforeModify}) - while those triggers are NOT declared on the model yet.
 * <p>
 * <b>The defect this closes</b> (measured 2026-10-04 on Vol100k and Vol1m: {@code Personne.phoneE164}, {@code derivedFrom = phone},
 * {@code SCRIPT}, capability 107 {@code ACCEPTED} with {@code appliedModel} empty - 100 % of 491,805 rows held the raw phone): an
 * accepted but never applied script derivation is a plain field of the staging table, and the loader used to fill it with a copy
 * of its source ({@code ebx.StagingLoader.readDerivedFields} handed it to {@link DerivedFieldRule}, whose fall-through branch was
 * the matching-key fold, which leaves a phone number untouched). Nothing said so, the wizard showed the attribute as applied, the
 * matching plan compared it as an exact field.
 * <p>
 * <b>The rule</b>: a script-derived attribute is never written by the loader (applied: the triggers compute it; not applied: it has no
 * value to write); while not applied its field is held EMPTY (an explicit null, so a value copied by an older load is cleared on
 * the next load of those rows) and the load says so in one line ({@link #loadNote}). "Applied" means an accepted
 * {@code SCRIPT_DERIVATION} suggestion naming the attribute with a non-blank {@code appliedModel} and
 * {@code appliedElementPath} - what {@code ebx.DerivationApplier} writes and {@code remove} clears. Pure: no EBX class.
 */
public final class ScriptDerivationGate
{
	/** {@code StagingAttribute.derivation} of a script-computed attribute (same value as {@code quality.ScriptDerivation#DERIVATION_SCRIPT}). */
	public static final String DERIVATION_SCRIPT = com.on.ebx.factory.quality.ScriptDerivation.DERIVATION_SCRIPT;

	private ScriptDerivationGate()
	{
	}

	/** One script-derived attribute of one entity, and whether its derivation is applied. */
	public static final class Entry
	{
		public final String entityName;
		public final String attributeName;
		/** The staging attribute it is computed from (name), or {@code null} when unknown. */
		public final String sourceName;
		/** The id of the {@code SCRIPT_DERIVATION} suggestion that would compute it, or {@code null} when none exists at all. */
		public final String suggestionId;
		public final boolean applied;
		/**
		 * Lot P1 (2026-10-04): applied, but a parameter was changed afterwards ({@code capability.DerivationParameters#REAPPLY_MARKER} on the
		 * suggestion): the triggers are live with the OLD parameter, so the stored values are stale until it is applied again. Never true when
		 * {@link #applied} is false. It does NOT change {@link #applied}: the loader's rule (an applied derivation is never written by the loader,
		 * the triggers compute it) holds either way.
		 */
		public final boolean reapplyNeeded;
		/**
		 * Fix/derivation-apply-backfill (2026-10-17, lot G1): the parameters of the binding still {@code toBeProvided} - a person must supply them before the derivation can be applied;
		 * empty when none (and always empty once applied: the apply refuses a binding that has one). Never {@code null}.
		 */
		public final List<String> waitingParameters;
		/** Lot G1: {@code CapabilitySuggestion.appliedAt} of an applied derivation, {@code null} otherwise or when unknown (what {@link DerivationStaleness} compares row write times with). */
		public final java.util.Date appliedAt;

		public Entry(final String pEntityName, final String pAttributeName, final String pSourceName, final String pSuggestionId, final boolean pApplied)
		{
			this(pEntityName, pAttributeName, pSourceName, pSuggestionId, pApplied, false);
		}

		public Entry(final String pEntityName, final String pAttributeName, final String pSourceName, final String pSuggestionId, final boolean pApplied,
			final boolean pReapplyNeeded)
		{
			this(pEntityName, pAttributeName, pSourceName, pSuggestionId, pApplied, pReapplyNeeded, null, null);
		}

		public Entry(final String pEntityName, final String pAttributeName, final String pSourceName, final String pSuggestionId, final boolean pApplied,
			final boolean pReapplyNeeded, final List<String> pWaitingParameters, final java.util.Date pAppliedAt)
		{
			this.entityName = pEntityName;
			this.attributeName = pAttributeName;
			this.sourceName = pSourceName;
			this.suggestionId = pSuggestionId;
			this.applied = pApplied;
			this.reapplyNeeded = pApplied && pReapplyNeeded;
			this.waitingParameters = pApplied || pWaitingParameters == null ? Collections.<String> emptyList() : Collections.unmodifiableList(new ArrayList<String>(pWaitingParameters));
			this.appliedAt = pApplied ? pAppliedAt : null;
		}

		/** Lot G1: not applied because a parameter has still to be provided by a person. */
		public boolean waitsForParameter()
		{
			return !this.applied && !this.waitingParameters.isEmpty();
		}
	}

	/** Lot G1: the not-applied entries that wait for a parameter a person must provide, order kept; {@code null} reads as empty. */
	public static List<Entry> waitingForParameter(final List<Entry> pEntries)
	{
		final List<Entry> out = new ArrayList<Entry>();
		if (pEntries != null)
		{
			for (final Entry entry : pEntries)
			{
				if (entry.waitsForParameter())
				{
					out.add(entry);
				}
			}
		}
		return out;
	}

	/** Whether {@code pDerivation} is the script derivation kind. */
	public static boolean isScript(final String pDerivation)
	{
		return DERIVATION_SCRIPT.equals(pDerivation);
	}

	/** Whether a {@code SCRIPT_DERIVATION} suggestion counts as applied: status {@code ACCEPTED} or {@code APPLIED} and both {@code appliedModel} and {@code appliedElementPath} non-blank. */
	public static boolean isApplied(final String pStatus, final String pAppliedModel, final String pAppliedElementPath)
	{
		final boolean statusOk = "ACCEPTED".equals(pStatus) || "APPLIED".equals(pStatus);
		return statusOk && pAppliedModel != null && !pAppliedModel.trim().isEmpty() && pAppliedElementPath != null && !pAppliedElementPath.trim().isEmpty();
	}

	/** The entries of {@code pEntries} that are not applied, order kept; {@code null} reads as empty. */
	public static List<Entry> notApplied(final List<Entry> pEntries)
	{
		if (pEntries == null)
		{
			return Collections.emptyList();
		}
		final List<Entry> out = new ArrayList<Entry>();
		for (final Entry entry : pEntries)
		{
			if (!entry.applied)
			{
				out.add(entry);
			}
		}
		return out;
	}

	/**
	 * Fix/staging-proposal-quality (2026-10-17, item 1): the suggestion ids a staging load applies ITSELF before reading a row - the not-applied
	 * entries whose suggestion is {@code ACCEPTED} ({@code pAcceptedIds}). Root cause measured on TiersB2CRetestLanding: {@code phoneE164} and
	 * {@code personNameKey} were accepted but never applied (apply is its own step, which "Build and load with what is accepted" did not run), so
	 * the loader held them empty (lot M5) on every row while the folded keys, which Java computes, were filled. Order kept, no duplicate; an entry
	 * with no suggestion, or whose suggestion is not accepted, is not applied (the load note still says so).
	 */
	public static List<String> idsToApply(final List<Entry> pEntries, final java.util.Collection<String> pAcceptedIds)
	{
		final List<String> out = new ArrayList<String>();
		for (final Entry entry : ScriptDerivationGate.notApplied(pEntries))
		{
			// lot G1 (2026-10-17): an entry that waits for a parameter a person must give is not tried (the apply would refuse it): the load note says what to enter
			if (entry.suggestionId != null && pAcceptedIds != null && pAcceptedIds.contains(entry.suggestionId) && !out.contains(entry.suggestionId) && !entry.waitsForParameter())
			{
				out.add(entry.suggestionId);
			}
		}
		return out;
	}

	/** Lot P1: the applied entries whose parameters changed since (see {@link Entry#reapplyNeeded}), order kept; {@code null} reads as empty. */
	public static List<Entry> reapplyNeeded(final List<Entry> pEntries)
	{
		if (pEntries == null)
		{
			return Collections.emptyList();
		}
		final List<Entry> out = new ArrayList<Entry>();
		for (final Entry entry : pEntries)
		{
			if (entry.reapplyNeeded)
			{
				out.add(entry);
			}
		}
		return out;
	}

	/** The names of the not-applied attributes of {@code pEntityName}. */
	public static java.util.Set<String> notAppliedNames(final List<Entry> pEntries, final String pEntityName)
	{
		final java.util.Set<String> out = new java.util.LinkedHashSet<String>();
		for (final Entry entry : ScriptDerivationGate.notApplied(pEntries))
		{
			if (entry.entityName.equals(pEntityName))
			{
				out.add(entry.attributeName);
			}
		}
		return out;
	}

	/** The sentence the staging load records for one not-applied attribute (step message and detail). */
	public static String loadNote(final Entry pEntry)
	{
		if (pEntry.waitsForParameter())
		{
			// lot G1 (2026-10-17): the build may go on, but the person must know the column is empty because of a parameter only they can give
			return pEntry.attributeName + " is a script derivation that waits for the parameter(s) " + pEntry.waitingParameters
				+ ": left empty - enter them in step 4 (the block next to \"Build and load\"), then apply it";
		}
		return pEntry.attributeName + " is declared as a script derivation that is not applied: left empty - apply it (Proposal review, or POST /projects/{code}/capabilities/"
			+ (pEntry.suggestionId == null ? "{id}" : pEntry.suggestionId) + "/apply)";
	}

	/** The sentence about one not-applied attribute for a screen or a warning ({@code entity.attribute}, what it is computed from, where to apply it). */
	public static String openPointText(final Entry pEntry)
	{
		return pEntry.entityName + "." + pEntry.attributeName + (pEntry.sourceName == null ? "" : " (computed from " + pEntry.sourceName + ")")
			+ " is a script derivation that is not applied: it stays empty until it is applied"
			+ (pEntry.suggestionId == null ? " (no SCRIPT_DERIVATION suggestion names it)" : " (capability " + pEntry.suggestionId + ")");
	}

	/** Lot P1: the sentence about one applied derivation whose parameters changed since, for a screen or a warning. */
	public static String reapplyText(final Entry pEntry)
	{
		return pEntry.entityName + "." + pEntry.attributeName + (pEntry.sourceName == null ? "" : " (computed from " + pEntry.sourceName + ")")
			+ " is a script derivation whose parameters changed since it was applied: its stored values are stale until it is applied again"
			+ (pEntry.suggestionId == null ? "" : " (capability " + pEntry.suggestionId + ")");
	}

	/**
	 * Every field a matching policy compares - the clustering fields ({@code fields[].field}) and every field of every node of the decision
	 * tree ({@code decisionTree.fields[].field}, then {@code trueNode}/{@code falseNode} recursively) - as bare names ({@code /phoneE164} to
	 * {@code phoneE164}), in first-seen order. The shape {@code master.MatchingPolicyTranslator} builds; a missing part reads as empty.
	 */
	public static java.util.Set<String> policyFieldNames(final com.fasterxml.jackson.databind.JsonNode pMatchingPolicy)
	{
		final java.util.Set<String> out = new java.util.LinkedHashSet<String>();
		if (pMatchingPolicy == null)
		{
			return out;
		}
		for (final com.fasterxml.jackson.databind.JsonNode field : pMatchingPolicy.path("fields"))
		{
			ScriptDerivationGate.addFieldName(out, field.path("field").asText(""));
		}
		ScriptDerivationGate.collectTreeFields(pMatchingPolicy.path("decisionTree"), out);
		return out;
	}

	private static void collectTreeFields(final com.fasterxml.jackson.databind.JsonNode pNode, final java.util.Set<String> pOut)
	{
		if (pNode == null || pNode.isMissingNode() || pNode.isNull())
		{
			return;
		}
		for (final com.fasterxml.jackson.databind.JsonNode field : pNode.path("fields"))
		{
			ScriptDerivationGate.addFieldName(pOut, field.path("field").asText(""));
		}
		ScriptDerivationGate.collectTreeFields(pNode.path("trueNode"), pOut);
		ScriptDerivationGate.collectTreeFields(pNode.path("falseNode"), pOut);
	}

	private static void addFieldName(final java.util.Set<String> pOut, final String pPath)
	{
		final String name = pPath.startsWith("/") ? pPath.substring(1) : pPath;
		if (!name.isEmpty())
		{
			pOut.add(name);
		}
	}

	/**
	 * The warnings the matching configuration of {@code pEntityName} records: one sentence for every not-applied script derivation of that
	 * entity whose attribute is among {@code pPolicyFieldNames} (see {@link #policyFieldNames}). Empty when none - the common case.
	 */
	public static List<String> planWarnings(final List<Entry> pEntries, final String pEntityName, final java.util.Collection<String> pPolicyFieldNames)
	{
		final List<String> out = new ArrayList<String>();
		if (pPolicyFieldNames == null)
		{
			return out;
		}
		for (final Entry entry : ScriptDerivationGate.notApplied(pEntries))
		{
			if (entry.entityName.equals(pEntityName) && pPolicyFieldNames.contains(entry.attributeName))
			{
				out.add(entry.entityName + "." + entry.attributeName + " is compared by the matching plan but it is a script derivation that is not applied: it is empty, so "
					+ "the evidence it carries is lost - apply it (capability " + (entry.suggestionId == null ? "?" : entry.suggestionId)
					+ "), reload the staging and the master, then run the matching again");
			}
		}
		// lot P1: an applied derivation whose parameters changed since computed its stored values with the OLD parameter
		for (final Entry entry : ScriptDerivationGate.reapplyNeeded(pEntries))
		{
			if (entry.entityName.equals(pEntityName) && pPolicyFieldNames.contains(entry.attributeName))
			{
				out.add(entry.entityName + "." + entry.attributeName + " is compared by the matching plan but its parameters changed since the derivation was applied: its stored values "
					+ "are stale - apply it again (capability " + (entry.suggestionId == null ? "?" : entry.suggestionId) + "), reload the staging and the master, then run the matching again");
			}
		}
		return out;
	}
}
