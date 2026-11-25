package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Fix/staging-proposal-quality (2026-10-17, item 2): the deterministic post-check of the modelling the language model proposed, run on the
 * run's own proposals before they are written. Measured on TiersB2CRetestLanding (defect 8): a ticket source fed {@code StoreTransaction} AND
 * the same facts (date, store, amount) were put on {@code Customer}; {@code Customer} kept {@code city} / {@code streetAddress} that no source
 * feeds while {@code CustomerAddress} holds the address; and every ticket became one {@code Customer} without a word about it. The prompt now
 * says so too ({@code StagingLlmPass#systemPrompt}); this class is the safety net for when the model does it anyway.
 * <p>
 * <b>Domain-free</b>: nothing here reads an attribute or entity name for its meaning - only which landing column feeds which attribute, which
 * attributes are business keys, and which entities a table feeds (the same posture as {@link AttentionRules}). Three findings:
 * <ul>
 * <li>{@link #DUPLICATES_RELATED}: an attribute of an entity whose source links ALL read a landing column that another entity of the run also
 * reads from the same landing table - the fact is carried by the other entity (a child of it, or the narrower of two unrelated entities, see
 * {@code entityCarryingAll}), the field is a duplicate. Business keys and mandatory attributes (identifying fields legitimately live on both
 * sides) and the link to the parent are left alone.</li>
 * <li>{@link #NO_SOURCE}: an attribute of an entity that has feeds but is read by no rule of any of them - a field nothing fills.</li>
 * <li>{@link #ONE_ROW_PER_RECORD}: a ROOT feed of an entity that has business key attributes but maps none of them - every row of that landing
 * table becomes its own record of the entity (the ticket table that became one Customer per ticket); stated on the source link, so it is
 * visible, not silent.</li>
 * <li>{@link #NO_FEED} (lot M7, 2026-10-18): a NEW entity of the run that no feed reads - none of its attributes is mapped to any landing column
 * (the technical key and the link to the parent never are, so they do not count). Measured on E2EMASTER20261017235630: {@code ContactEmail}
 * (confidence 89, four attributes at 96-100, not one mapping) was accepted in bulk, built, loaded with 0 rows and given an empty master table and
 * an inert matching policy, with no word anywhere. The entity's confidence is capped and its rationale starts with the sentence that names the
 * fix ("map at least one attribute of X to a landing column, or do not propose it"); every one of its business attributes gets the
 * {@link #NO_SOURCE} sentence (nothing fills them either). When a note of the run names the entity (the model said why), the note follows the
 * sentence as the reason. The entity stays PROPOSED for a person; a bulk accept never takes it ({@code ebx.StagingBulkDecideRun} skips it by
 * structure too, whatever its confidence).</li>
 * <li>{@link #SUPERSEDED_COLUMN_LEFT_BEHIND} (lot M15, 2026-10-18): a NEW entity that replaced the per-table entities of several sources while a filled landing
 * column that one of those entities read is read by NO live entity (a column mapped only to a SUPERSEDED entity is read by nobody - the load never reads it) and no
 * note of the run says why. Measured on E2EMASTER20261018090711: {@code STORES.TELEPHONE} (234 of 234 tickets) was mapped only to the superseded {@code MagasinsVentes...}
 * and never to {@code Client.mobile}: the person's phone was empty on every STORES row. The entity's confidence is capped under the bulk bars and its rationale starts
 * with the sentence that names the columns; its attributes are left alone (the entity is fed, the columns are what to look at).</li>
 * <li>{@link #IDENTITY_SOURCE_UNFED} (lot S10, 2026-10-18): a landing table whose columns carry catalog identity tags and whose rows feed no entity holding the identity attributes of the other tables
 * ({@link IdentityCoverage#gaps}; the holder is derived from the proposal, never from a name). The entity those tables feed gets the counts as a sentence first in its rationale (rows, identity kinds,
 * values seen in no other source) followed by the model's note about the table when it gave one, and its confidence is capped under the bulk thresholds. Nothing is fed or rejected: the model decided.</li>
 * <li>Entity coverage (lot F2, 2026-10-19): not a finding but a line - {@link #KEY_ENTITY_COVERAGE} - in the rationale of each new entity whose feeds read identity-bearing columns: how many of the landing
 * tables that carry its kind of columns feed it and how many rows it would receive ({@link IdentityCoverage#entityCoverage}). No confidence change; the model's feeds stand as they are.</li>
 * </ul>
 * <b>What a finding does</b>: a field finding caps the confidence of the attribute (and of the rules feeding it) under the bulk-accept threshold
 * of the matrix (60), so it lands in the "needs you" queue instead of being swept in, and puts a plain sentence first in its rationale
 * (the sentence says "propose: reject" - the person decides); the source-link finding appends its sentence to the feed's rationale and changes
 * no confidence. Wording comes from the bundle through {@link Words}, in the viewer's language.
 */
public final class ProposalChecks
{
	public static final String DUPLICATES_RELATED = "DUPLICATES_RELATED";
	public static final String NO_SOURCE = "NO_SOURCE";
	public static final String ONE_ROW_PER_RECORD = "ONE_ROW_PER_RECORD";
	public static final String NO_FEED = "NO_FEED";
	/** Lot M15: columns that fed a superseded per-table entity and that no live entity reads. */
	public static final String SUPERSEDED_COLUMN_LEFT_BEHIND = "SUPERSEDED_COLUMN_LEFT_BEHIND";
	/**
	 * Lot S10 (2026-10-18): a landing table whose rows carry identity attributes (catalog identity tags) and that feeds no entity holding the identity attributes of
	 * the other tables - the records seen only there would be absent from the master. Stated as an open point on the entity those other tables feed, with the
	 * counts; the model's note about the table, when it gave one, follows the sentence. The proposal stands: nothing is fed, dropped or decided here.
	 */
	public static final String IDENTITY_SOURCE_UNFED = "IDENTITY_SOURCE_UNFED";
	/**
	 * Lot F2b (2026-10-19): a date attribute fed from several columns whose spans (earliest to latest month, {@link DateSpans}) fall in groups that do not overlap - run 8 of 2026-10-19:
	 * {@code Personne.birthDate} fed from two sources' birth dates AND from a ticket table's purchase date (234 rows of 2026). Stated as an open point on the entity, with the spans and
	 * the rows; no confidence change, nothing is fed, dropped or decided here.
	 */
	public static final String DATE_SPANS_DISJOINT = "DATE_SPANS_DISJOINT";

	/** A duplicate is capped here: under {@code MatrixCompute#DEFAULT_BULK_THRESHOLD} (60) and under the wizard's bulk threshold, so a person looks at it. */
	public static final int DUPLICATE_CONFIDENCE_CAP = 40;
	/** A field no source feeds is capped lower: the proposal is to reject it. */
	public static final int NO_SOURCE_CONFIDENCE_CAP = 20;
	/** An entity no landing column feeds is capped like a field no source feeds: the proposal is to reject it, or to feed it. */
	public static final int NO_FEED_CONFIDENCE_CAP = 20;
	/**
	 * An entity that left columns of the per-table entity it replaced behind is capped under the bulk-accept bars (matrix 60, wizard) so a person sees it, but not as
	 * low as {@link #NO_FEED_CONFIDENCE_CAP}: the entity is fed and sound, the proposal is to look at the columns, not to reject it.
	 */
	public static final int LEFT_BEHIND_CONFIDENCE_CAP = 40;
	/** The entity that holds the identity of the other sources while a table carrying it feeds nothing into it is capped like {@link #LEFT_BEHIND_CONFIDENCE_CAP}: a person looks, nothing is rejected. */
	public static final int IDENTITY_UNFED_CONFIDENCE_CAP = 40;

	/** Bundle keys ({@code stagingProposal.check.*}). */
	public static final String KEY_DUPLICATES_RELATED = "stagingProposal.check.duplicatesRelated";
	public static final String KEY_NO_SOURCE = "stagingProposal.check.noSource";
	public static final String KEY_ONE_ROW_PER_RECORD = "stagingProposal.check.oneRowPerRecord";
	public static final String KEY_NO_FEED = "stagingProposal.check.noFeed";
	public static final String KEY_SUPERSEDED_COLUMN_LEFT_BEHIND = "stagingProposal.check.supersededColumnLeftBehind";
	/** Lot S10: args {0} table, {1} rows carrying an identity, {2} the identity kinds, {3} the "seen nowhere else" clause (may be empty), {4} the entity, {5} how firm the tags are (empty when all are applied). */
	public static final String KEY_IDENTITY_UNFED = "stagingProposal.check.identityUnfed";
	/** Lot S10: args {0} the figures ("46 e-mails, 12 phones"). */
	public static final String KEY_IDENTITY_UNFED_ONLY = "stagingProposal.check.identityUnfed.only";
	/** Lot S10: the "not measured" clause when a table was read on a sample. */
	public static final String KEY_IDENTITY_UNFED_NOT_MEASURED = "stagingProposal.check.identityUnfed.notMeasured";
	/** Lot S10: {@code stagingProposal.identityKind.<KIND>} (singular) and {@code stagingProposal.identityKindMany.<KIND>} (plural), per {@link IdentityFacts.Kind} name. */
	public static final String KEY_IDENTITY_KIND = "stagingProposal.identityKind.";
	public static final String KEY_IDENTITY_KIND_MANY = "stagingProposal.identityKindMany.";
	/** Lot S10b: {@code "; {0}"} after the kinds - how firm the tags are; {@code stagingProposal.identityBasis.<APPLIED|SAMPLED|NAMES|MODEL|OTHER>} are the phrases. */
	public static final String KEY_IDENTITY_UNFED_BASIS = "stagingProposal.check.identityUnfed.basis";
	public static final String KEY_IDENTITY_BASIS = "stagingProposal.identityBasis.";
	/**
	 * Lot F2 (2026-10-19): the coverage line of an entity's rationale. Args {0} the entity, {1} landing tables feeding it, {2} landing tables carrying its kind of columns, {3} the kinds, {4} the
	 * rows it would receive, {5} the breakdown per table. {@code .atLeast} when the rows of some fed table are unknown; {@code .notFed} and {@code .rowsUnknown} are the words of the breakdown.
	 */
	public static final String KEY_ENTITY_COVERAGE = "stagingProposal.check.entityCoverage";
	public static final String KEY_ENTITY_COVERAGE_AT_LEAST = "stagingProposal.check.entityCoverage.atLeast";
	public static final String KEY_ENTITY_COVERAGE_NOT_FED = "stagingProposal.check.entityCoverage.notFed";
	public static final String KEY_ENTITY_COVERAGE_ROWS_UNKNOWN = "stagingProposal.check.entityCoverage.rowsUnknown";
	/** Lot F2b: args {0} entity, {1} attribute, {2} the sources with their spans and rows, {3} the number of groups, {4} the groups; {@code .rows} is the word after a row count. */
	public static final String KEY_DATE_SPANS_DISJOINT = "stagingProposal.check.dateSpansDisjoint";
	public static final String KEY_DATE_SPANS_ROWS = "stagingProposal.check.dateSpansDisjoint.rows";
	public static final String KEY_DATE_SPANS_AND = "stagingProposal.check.dateSpansDisjoint.and";

	private ProposalChecks()
	{
	}

	/** The wording of a finding: a bundle key and its arguments to a sentence in the viewer's language. */
	public interface Words
	{
		String text(String pKey, Object... pArgs);
	}

	/** One finding, for the run's result and the tests. */
	public static final class Finding
	{
		public final String kind;
		public final String entity;
		/** The attribute, or {@code null} for a source-link finding. */
		public final String attribute;
		/** The landing table of the source link, or {@code null} for a field finding. */
		public final String landingTableKey;
		/** {@link #DUPLICATES_RELATED}: the other entity that carries the fact; {@code null} otherwise. */
		public final String relatedEntity;
		public final String sentence;

		Finding(final String pKind, final String pEntity, final String pAttribute, final String pLandingTableKey, final String pRelatedEntity, final String pSentence)
		{
			this.kind = pKind;
			this.entity = pEntity;
			this.attribute = pAttribute;
			this.landingTableKey = pLandingTableKey;
			this.relatedEntity = pRelatedEntity;
			this.sentence = pSentence;
		}

		@Override
		public String toString()
		{
			return this.kind + " " + this.entity + (this.attribute == null ? "" : "." + this.attribute) + (this.landingTableKey == null ? "" : " <- " + this.landingTableKey)
				+ (this.relatedEntity == null ? "" : " (also " + this.relatedEntity + ")");
		}
	}

	/**
	 * Reviews the proposals of one run and rewrites the findings into them, in place: the attribute / rule / feed objects concerned are REPLACED in
	 * {@code pEntities} (their {@code attributes} lists) and {@code pFeeds} (the list itself and each feed's {@code rules}) - the caller keeps its own
	 * indexes in step. Entities that already existed before the run ({@code existingId != null}) carry no attributes here and are not reviewed for
	 * fields; their new feeds still are for the source-link finding.
	 *
	 * @param pTablesByKey the landing tables by {@code "<sourceSystem>.<table>"}, for the table name in a sentence
	 * @return the findings, in entity then attribute order, then source links in feed order
	 */
	public static List<Finding> review(final List<StagingLlmPass.CreatedEntity> pEntities, final List<StagingLlmPass.CreatedFeed> pFeeds,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Words pWords)
	{
		return ProposalChecks.review(pEntities, pFeeds, pTablesByKey, pWords, null);
	}

	/**
	 * {@link #review(List, List, Map, Words)} with the notes of the run: a note that names an entity no feed reads is kept on that entity as the reason
	 * the model gave (lot M7).
	 *
	 * @param pNotes the run's notes, in call order; null or empty when there is none
	 */
	public static List<Finding> review(final List<StagingLlmPass.CreatedEntity> pEntities, final List<StagingLlmPass.CreatedFeed> pFeeds,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Words pWords, final List<String> pNotes)
	{
		return ProposalChecks.review(pEntities, pFeeds, pTablesByKey, pWords, pNotes, null);
	}

	/**
	 * {@link #review(List, List, Map, Words, List)} with the columns left behind (lot M15): a NEW entity that replaced per-table entities whose columns no live
	 * entity reads gets the finding {@link #SUPERSEDED_COLUMN_LEFT_BEHIND} - its confidence is capped at {@link #LEFT_BEHIND_CONFIDENCE_CAP}, its rationale starts with the
	 * sentence that names the columns.
	 *
	 * @param pLeftBehind {@link StagingLlmPass.Session#columnsLeftBehind()}; null or empty when there is none
	 */
	public static List<Finding> review(final List<StagingLlmPass.CreatedEntity> pEntities, final List<StagingLlmPass.CreatedFeed> pFeeds,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Words pWords, final List<String> pNotes, final List<StagingLlmPass.LeftBehind> pLeftBehind)
	{
		return ProposalChecks.review(pEntities, pFeeds, pTablesByKey, pWords, pNotes, pLeftBehind, null);
	}

	/**
	 * {@link #review(List, List, Map, Words, List, List)} with the identity gaps (lot S10, {@link IdentityCoverage#gaps}): for each landing table whose rows carry
	 * identity attributes and feed no entity holding the identity of the other tables, the entity those tables feed gets the sentence {@link #KEY_IDENTITY_UNFED} first in
	 * its rationale (with the counts, then the model's note about the table when it gave one) and its confidence capped at {@link #IDENTITY_UNFED_CONFIDENCE_CAP}, so it
	 * is looked at and never swept in by a bulk accept. An entity adopted from an earlier run is not rewritten; the finding is still reported.
	 *
	 * @param pIdentityGaps {@link StagingLlmPass.Session#identityGaps()}; null or empty when there is none
	 */
	public static List<Finding> review(final List<StagingLlmPass.CreatedEntity> pEntities, final List<StagingLlmPass.CreatedFeed> pFeeds,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Words pWords, final List<String> pNotes, final List<StagingLlmPass.LeftBehind> pLeftBehind,
		final List<IdentityCoverage.Gap> pIdentityGaps)
	{
		return ProposalChecks.review(pEntities, pFeeds, pTablesByKey, pWords, pNotes, pLeftBehind, pIdentityGaps, null);
	}

	/**
	 * {@link #review(List, List, Map, Words, List, List, List)} with the coverage of the entities (lot F2, {@link IdentityCoverage#entityCoverage}): each NEW entity that is fed from some of
	 * the landing tables carrying its kind of columns gets a line {@link #KEY_ENTITY_COVERAGE} in its rationale (after the identity open point when there is one, before the model's own
	 * text). It is information: no finding, no confidence change, nothing is fed or rejected. An entity adopted from an earlier run is not rewritten.
	 *
	 * @param pCoverage {@link StagingLlmPass.Session#entityCoverage()}; null or empty when there is none
	 */
	public static List<Finding> review(final List<StagingLlmPass.CreatedEntity> pEntities, final List<StagingLlmPass.CreatedFeed> pFeeds,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Words pWords, final List<String> pNotes, final List<StagingLlmPass.LeftBehind> pLeftBehind,
		final List<IdentityCoverage.Gap> pIdentityGaps, final List<IdentityCoverage.EntityCoverage> pCoverage)
	{
		return ProposalChecks.review(pEntities, pFeeds, pTablesByKey, pWords, pNotes, pLeftBehind, pIdentityGaps, pCoverage, null);
	}

	/**
	 * {@link #review(List, List, Map, Words, List, List, List, List)} with the date attributes fed from columns whose spans do not overlap (lot F2b, {@link DateSpans#disjoint}): each such
	 * attribute is an open point on its entity ({@link #DATE_SPANS_DISJOINT}) - a sentence with the spans and the rows first in the entity's rationale, followed by the model's note about
	 * the attribute when it gave one. No confidence change of its own.
	 *
	 * @param pDateSpans {@link StagingLlmPass.Session#dateSpanDisjoints()}; null or empty when there is none
	 */
	public static List<Finding> review(final List<StagingLlmPass.CreatedEntity> pEntities, final List<StagingLlmPass.CreatedFeed> pFeeds,
		final Map<String, StagingRules.LandingTableInfo> pTablesByKey, final Words pWords, final List<String> pNotes, final List<StagingLlmPass.LeftBehind> pLeftBehind,
		final List<IdentityCoverage.Gap> pIdentityGaps, final List<IdentityCoverage.EntityCoverage> pCoverage, final List<DateSpans.Disjoint> pDateSpans)
	{
		final List<Finding> findings = new ArrayList<Finding>();
		// entity name -> the "<table>|<column>" pairs read by its rules, and attribute -> the pairs feeding it
		final Map<String, Set<String>> pairsByEntity = new LinkedHashMap<String, Set<String>>();
		final Map<String, Map<String, List<String>>> pairsByAttribute = new LinkedHashMap<String, Map<String, List<String>>>();
		final Map<String, Set<String>> tablesByEntity = new LinkedHashMap<String, Set<String>>();
		for (final StagingLlmPass.CreatedFeed feed : pFeeds)
		{
			Set<String> entityTables = tablesByEntity.get(feed.entityName);
			if (entityTables == null)
			{
				entityTables = new LinkedHashSet<String>();
				tablesByEntity.put(feed.entityName, entityTables);
			}
			entityTables.add(feed.landingTableKey);
			Set<String> entityPairs = pairsByEntity.get(feed.entityName);
			if (entityPairs == null)
			{
				entityPairs = new LinkedHashSet<String>();
				pairsByEntity.put(feed.entityName, entityPairs);
			}
			Map<String, List<String>> byAttribute = pairsByAttribute.get(feed.entityName);
			if (byAttribute == null)
			{
				byAttribute = new LinkedHashMap<String, List<String>>();
				pairsByAttribute.put(feed.entityName, byAttribute);
			}
			for (final StagingLlmPass.CreatedFeedRule rule : feed.rules)
			{
				final String pair = feed.landingTableKey + "|" + rule.landingColumn;
				entityPairs.add(pair);
				List<String> attributePairs = byAttribute.get(rule.attribute);
				if (attributePairs == null)
				{
					attributePairs = new ArrayList<String>();
					byAttribute.put(rule.attribute, attributePairs);
				}
				attributePairs.add(pair);
			}
		}

		for (int e = 0; e < pEntities.size(); e++)
		{
			final StagingLlmPass.CreatedEntity entity = pEntities.get(e);
			if (entity.existingId != null)
			{
				continue; // an adopted entity has no attributes to review here (its feeds of earlier runs are not in pFeeds either)
			}
			if (!pairsByEntity.containsKey(entity.name))
			{
				// lot M7: a new entity no feed reads - the sentence names the fix, the model's own note (when one names the entity) is the reason
				final String sentence = pWords.text(ProposalChecks.KEY_NO_FEED, ProposalChecks.display(pEntities, entity.name));
				final String reason = ProposalChecks.noteNaming(entity.name, pNotes);
				final StagingLlmPass.CreatedEntity flagged = ProposalChecks.withEntityNote(entity, reason == null ? sentence : sentence + " " + reason,
					ProposalChecks.NO_FEED_CONFIDENCE_CAP, pWords);
				pEntities.set(e, flagged);
				findings.add(new Finding(ProposalChecks.NO_FEED, entity.name, null, null, null, sentence));
				continue;
			}
			final Map<String, List<String>> own = pairsByAttribute.get(entity.name);
			for (int i = 0; i < entity.attributes.size(); i++)
			{
				final StagingLlmPass.CreatedAttribute attribute = entity.attributes.get(i);
				if (attribute.name.equals(entity.parentLinkAttribute))
				{
					continue; // the engine fills the link to the parent from the parent row, no rule maps it
				}
				final List<String> pairs = own.get(attribute.name);
				if (pairs == null || pairs.isEmpty())
				{
					final String sentence = pWords.text(ProposalChecks.KEY_NO_SOURCE);
					entity.attributes.set(i, ProposalChecks.withNote(attribute, sentence, ProposalChecks.NO_SOURCE_CONFIDENCE_CAP));
					findings.add(new Finding(ProposalChecks.NO_SOURCE, entity.name, attribute.name, null, null, sentence));
					continue;
				}
				if (attribute.businessKey || attribute.mandatory)
				{
					continue; // an identifying field legitimately lives on both sides
				}
				final String related = ProposalChecks.entityCarryingAll(pairs, entity.name, pairsByEntity, tablesByEntity, pEntities);
				if (related != null)
				{
					final String sentence = pWords.text(ProposalChecks.KEY_DUPLICATES_RELATED, ProposalChecks.display(pEntities, related), ProposalChecks.display(pEntities, entity.name));
					entity.attributes.set(i, ProposalChecks.withNote(attribute, sentence, ProposalChecks.DUPLICATE_CONFIDENCE_CAP));
					ProposalChecks.capRules(pFeeds, entity.name, attribute.name, ProposalChecks.DUPLICATE_CONFIDENCE_CAP);
					findings.add(new Finding(ProposalChecks.DUPLICATES_RELATED, entity.name, attribute.name, null, related, sentence));
				}
			}
		}

		// lot M15: columns of the per-table entities this entity replaced that no live entity reads
		if (pLeftBehind != null)
		{
			for (final String canonical : ProposalChecks.canonicalsOf(pLeftBehind))
			{
				final int index = ProposalChecks.indexOf(pEntities, canonical);
				if (index < 0 || pEntities.get(index).existingId != null)
				{
					continue; // an adopted entity of an earlier run is not rewritten here
				}
				final List<String> columns = new ArrayList<String>();
				final Set<String> superseded = new LinkedHashSet<String>();
				for (final StagingLlmPass.LeftBehind group : pLeftBehind)
				{
					if (group.canonicalEntity.equals(canonical))
					{
						columns.add(group.columnNames());
						superseded.add(group.supersededEntity);
					}
				}
				final StagingLlmPass.CreatedEntity entity = pEntities.get(index);
				final String sentence = pWords.text(ProposalChecks.KEY_SUPERSEDED_COLUMN_LEFT_BEHIND, ProposalChecks.display(pEntities, canonical), String.join(", ", columns),
					String.join(", ", superseded));
				pEntities.set(index, ProposalChecks.withEntityRationale(entity, sentence, ProposalChecks.LEFT_BEHIND_CONFIDENCE_CAP));
				findings.add(new Finding(ProposalChecks.SUPERSEDED_COLUMN_LEFT_BEHIND, canonical, null, null, null, sentence));
			}
		}

		// lot F2: how much of the landing each entity is fed from (a line, not a finding: the model's feeds stand as they are)
		if (pCoverage != null)
		{
			for (final IdentityCoverage.EntityCoverage coverage : pCoverage)
			{
				final int at = ProposalChecks.indexOf(pEntities, coverage.entity);
				if (at >= 0 && pEntities.get(at).existingId == null)
				{
					pEntities.set(at, ProposalChecks.withEntityRationale(pEntities.get(at), ProposalChecks.coverageSentence(coverage, ProposalChecks.display(pEntities, coverage.entity), pWords),
						Integer.MAX_VALUE));
				}
			}
		}

		// lot F2b: a date attribute fed from columns whose spans fall in groups that do not overlap
		if (pDateSpans != null)
		{
			for (final DateSpans.Disjoint disjoint : pDateSpans)
			{
				final int at = ProposalChecks.indexOf(pEntities, disjoint.entity);
				final String sentence = ProposalChecks.dateSpansSentence(disjoint, at >= 0 ? ProposalChecks.display(pEntities, disjoint.entity) : disjoint.entity, pWords);
				if (at >= 0 && pEntities.get(at).existingId == null)
				{
					final String reason = ProposalChecks.noteNaming(disjoint.entity + "." + disjoint.attribute, pNotes);
					pEntities.set(at, ProposalChecks.withEntityRationale(pEntities.get(at), reason == null ? sentence : sentence + " " + reason, Integer.MAX_VALUE));
				}
				findings.add(new Finding(ProposalChecks.DATE_SPANS_DISJOINT, disjoint.entity, disjoint.attribute, null, null, sentence));
			}
		}

		// lot S10: a table carrying identity attributes that feeds no entity holding the identity of the other tables
		if (pIdentityGaps != null)
		{
			for (final IdentityCoverage.Gap gap : pIdentityGaps)
			{
				final String sentence = ProposalChecks.identitySentence(gap, ProposalChecks.holderDisplay(pEntities, gap), pWords);
				final String reason = ProposalChecks.noteNaming(gap.landingTableKey, pNotes);
				final String holder = gap.entities.get(0);
				int index = -1;
				for (final String candidate : gap.entities)
				{
					final int at = ProposalChecks.indexOf(pEntities, candidate);
					if (at >= 0 && pEntities.get(at).existingId == null)
					{
						index = at;
						break;
					}
				}
				if (index >= 0)
				{
					pEntities.set(index, ProposalChecks.withEntityRationale(pEntities.get(index), reason == null ? sentence : sentence + " " + reason,
						ProposalChecks.IDENTITY_UNFED_CONFIDENCE_CAP));
				}
				findings.add(new Finding(ProposalChecks.IDENTITY_SOURCE_UNFED, index >= 0 ? pEntities.get(index).name : holder, null, gap.landingTableKey, null, sentence));
			}
		}

		// the source link that gives one record per row: a root feed of an entity with business keys that maps none of them
		for (int f = 0; f < pFeeds.size(); f++)
		{
			final StagingLlmPass.CreatedFeed feed = pFeeds.get(f);
			if (feed.parentFeedKey != null || feed.parentFeedId != null)
			{
				continue;
			}
			final StagingLlmPass.CreatedEntity entity = ProposalChecks.find(pEntities, feed.entityName);
			if (entity == null || entity.existingId != null)
			{
				continue;
			}
			final Set<String> keys = new LinkedHashSet<String>();
			for (final StagingLlmPass.CreatedAttribute attribute : entity.attributes)
			{
				if (attribute.businessKey && !attribute.name.equals(entity.parentLinkAttribute))
				{
					keys.add(attribute.name);
				}
			}
			if (keys.isEmpty())
			{
				continue;
			}
			boolean mapsAKey = false;
			for (final StagingLlmPass.CreatedFeedRule rule : feed.rules)
			{
				if (keys.contains(rule.attribute))
				{
					mapsAKey = true;
					break;
				}
			}
			if (mapsAKey)
			{
				continue;
			}
			final StagingRules.LandingTableInfo table = pTablesByKey == null ? null : pTablesByKey.get(feed.landingTableKey);
			final String sentence = pWords.text(ProposalChecks.KEY_ONE_ROW_PER_RECORD, table == null ? feed.landingTableKey : table.name,
				ProposalChecks.display(pEntities, entity.name));
			pFeeds.set(f, new StagingLlmPass.CreatedFeed(feed.feedKey, feed.entityName, feed.landingTableKey, feed.parentFeedKey, feed.parentFeedId, feed.rules,
				feed.sourceIdPipeline, feed.confidence, ProposalChecks.prefixed(sentence, feed.rationale), feed.variant));
			findings.add(new Finding(ProposalChecks.ONE_ROW_PER_RECORD, entity.name, null, feed.landingTableKey, null, sentence));
		}
		return findings;
	}

	/**
	 * The first other entity whose rules read EVERY {@code pPairs} column and that carries the fact rather than {@code pOwner}: a descendant of the
	 * owner (a child, a grandchild...), or - when neither descends from the other - an entity fed from FEWER landing tables (the narrower concept
	 * is the one a source-specific fact belongs to). An ancestor of the owner never qualifies (that would flag both sides of the same duplicate),
	 * nor does an entity fed from as many tables when the two are unrelated: nothing says which side is wrong.
	 */
	private static String entityCarryingAll(final List<String> pPairs, final String pOwner, final Map<String, Set<String>> pPairsByEntity,
		final Map<String, Set<String>> pTablesByEntity, final List<StagingLlmPass.CreatedEntity> pEntities)
	{
		for (final Map.Entry<String, Set<String>> other : pPairsByEntity.entrySet())
		{
			final String name = other.getKey();
			if (name.equals(pOwner) || !other.getValue().containsAll(pPairs))
			{
				continue;
			}
			if (ProposalChecks.descendsFrom(pEntities, name, pOwner))
			{
				return name;
			}
			if (ProposalChecks.descendsFrom(pEntities, pOwner, name))
			{
				continue;
			}
			if (pTablesByEntity.get(name).size() < pTablesByEntity.get(pOwner).size())
			{
				return name;
			}
		}
		return null;
	}

	/** Whether {@code pName} is a descendant of {@code pAncestor} through {@code parentEntity} links among the run's entities. */
	private static boolean descendsFrom(final List<StagingLlmPass.CreatedEntity> pEntities, final String pName, final String pAncestor)
	{
		String current = pName;
		for (int depth = 0; depth < 20; depth++)
		{
			final StagingLlmPass.CreatedEntity entity = ProposalChecks.find(pEntities, current);
			if (entity == null || entity.parentEntity == null)
			{
				return false;
			}
			if (entity.parentEntity.equals(pAncestor))
			{
				return true;
			}
			current = entity.parentEntity;
		}
		return false;
	}

	/**
	 * The entity with {@code pSentence} first in its rationale and its confidence capped, every business attribute (not the link to the parent) carrying the
	 * {@link #NO_SOURCE} sentence and cap: a copy, the value object being immutable; {@code memberEntities} are carried over.
	 */
	private static StagingLlmPass.CreatedEntity withEntityNote(final StagingLlmPass.CreatedEntity pEntity, final String pSentence, final int pCap, final Words pWords)
	{
		final List<StagingLlmPass.CreatedAttribute> attributes = new ArrayList<StagingLlmPass.CreatedAttribute>();
		for (final StagingLlmPass.CreatedAttribute attribute : pEntity.attributes)
		{
			attributes.add(attribute.name.equals(pEntity.parentLinkAttribute) ? attribute
				: ProposalChecks.withNote(attribute, pWords.text(ProposalChecks.KEY_NO_SOURCE), ProposalChecks.NO_SOURCE_CONFIDENCE_CAP));
		}
		final StagingLlmPass.CreatedEntity out = new StagingLlmPass.CreatedEntity(pEntity.name, pEntity.label, ProposalChecks.prefixed(pSentence, pEntity.rationale),
			Math.min(pEntity.confidence, pCap), attributes, pEntity.parentEntity, pEntity.parentLinkAttribute, pEntity.parentLinkField, pEntity.existingId);
		out.memberEntities.addAll(pEntity.memberEntities);
		return out;
	}

	/**
	 * The open point of one identity gap, in the viewer's language: {@code STORES : 234 lignes portant une identite de personne n'alimentent aucune entite ; 46 e-mails vus
	 * nulle part ailleurs}. The counts are the Factory's measures; no word says what the table is.
	 */
	static String identitySentence(final IdentityCoverage.Gap pGap, final String pEntityDisplay, final Words pWords)
	{
		final List<String> kinds = new ArrayList<String>();
		for (final IdentityFacts.Kind kind : pGap.kinds)
		{
			kinds.add(pWords.text(ProposalChecks.KEY_IDENTITY_KIND + kind.name()));
		}
		final List<String> counts = new ArrayList<String>();
		for (final IdentityFacts.Kind kind : pGap.kinds)
		{
			final Long count = pGap.seenNowhereElse.get(kind);
			if (count != null)
			{
				counts.add(count + " " + pWords.text(ProposalChecks.KEY_IDENTITY_KIND_MANY + kind.name()));
			}
		}
		final String only = !counts.isEmpty() ? pWords.text(ProposalChecks.KEY_IDENTITY_UNFED_ONLY, String.join(", ", counts))
			: pGap.crossSkipped ? pWords.text(ProposalChecks.KEY_IDENTITY_UNFED_NOT_MEASURED) : "";
		String basis = "";
		if (pGap.suggestedOnly() || pGap.hasShapes())
		{
			final List<String> phrases = new ArrayList<String>();
			for (final String code : pGap.bases)
			{
				phrases.add(pWords.text(ProposalChecks.KEY_IDENTITY_BASIS + code));
			}
			basis = pWords.text(ProposalChecks.KEY_IDENTITY_UNFED_BASIS, String.join(", ", phrases));
		}
		return pWords.text(ProposalChecks.KEY_IDENTITY_UNFED, pGap.tableName, pGap.carryingRows() < 0L ? (Object) "?" : (Object) Long.valueOf(pGap.carryingRows()), String.join(", ", kinds), only,
			pEntityDisplay, basis);
	}

	/**
	 * The coverage line of an entity, in the viewer's language: {@code Coverage: Client is fed by 2 of the 3 landing tables that carry its kind of columns (e-mail, phone); it would
	 * receive 300 rows (WEB.ECOM 160, LOY.MEMBERS 140; SHOP.STORES 234 not fed).} Counts only.
	 */
	static String coverageSentence(final IdentityCoverage.EntityCoverage pCoverage, final String pEntityDisplay, final Words pWords)
	{
		final List<String> kinds = new ArrayList<String>();
		for (final IdentityFacts.Kind kind : pCoverage.kinds)
		{
			kinds.add(pWords.text(ProposalChecks.KEY_IDENTITY_KIND + kind.name()));
		}
		final List<String> fed = new ArrayList<String>();
		for (final String key : pCoverage.fed)
		{
			fed.add(ProposalChecks.coverageTable(pCoverage, key, pWords));
		}
		final List<String> unfed = new ArrayList<String>();
		for (final String key : pCoverage.notFed())
		{
			unfed.add(ProposalChecks.coverageTable(pCoverage, key, pWords) + " " + pWords.text(ProposalChecks.KEY_ENTITY_COVERAGE_NOT_FED));
		}
		final String fedText = String.join(", ", fed);
		final String unfedText = String.join(", ", unfed);
		final String breakdown = fedText.isEmpty() ? unfedText : unfedText.isEmpty() ? fedText : fedText + "; " + unfedText;
		return pWords.text(pCoverage.rowsReceivedIsLowerBound() ? ProposalChecks.KEY_ENTITY_COVERAGE_AT_LEAST : ProposalChecks.KEY_ENTITY_COVERAGE, pEntityDisplay,
			String.valueOf(pCoverage.fed.size()), String.valueOf(pCoverage.carrying.size()), String.join(", ", kinds), String.valueOf(pCoverage.rowsReceived()), breakdown);
	}

	/**
	 * The open point of one date attribute fed from columns whose spans do not overlap, in the viewer's language: {@code To check: Personne.birthDate is fed from ECOM.customers.birth_date
	 * (1951-03..2005-12, 160 rows), ...: the spans fall in 2 groups that do not overlap ([...] and [...]) ...}. The spans and the counts are the Factory's measures.
	 */
	static String dateSpansSentence(final DateSpans.Disjoint pDisjoint, final String pEntityDisplay, final Words pWords)
	{
		final List<String> sources = new ArrayList<String>();
		for (final DateSpans.Source source : pDisjoint.sources)
		{
			sources.add(source.name() + " (" + source.span.range() + ", " + source.span.filledRows + " " + pWords.text(ProposalChecks.KEY_DATE_SPANS_ROWS) + ")");
		}
		return pWords.text(ProposalChecks.KEY_DATE_SPANS_DISJOINT, pEntityDisplay, pDisjoint.attribute, String.join(", ", sources), String.valueOf(pDisjoint.groups.size()),
			pDisjoint.groupsText(pWords.text(ProposalChecks.KEY_DATE_SPANS_AND)));
	}

	private static String coverageTable(final IdentityCoverage.EntityCoverage pCoverage, final String pKey, final Words pWords)
	{
		final Long rows = pCoverage.rows.get(pKey);
		return rows == null ? pKey + " (" + pWords.text(ProposalChecks.KEY_ENTITY_COVERAGE_ROWS_UNKNOWN) + ")" : pKey + " " + rows;
	}

	/** The label of the first entity of the gap that exists in the run (else its name). */
	private static String holderDisplay(final List<StagingLlmPass.CreatedEntity> pEntities, final IdentityCoverage.Gap pGap)
	{
		return ProposalChecks.display(pEntities, pGap.entities.get(0));
	}

	/** The canonical entities of these groups, once each, in order. */
	private static List<String> canonicalsOf(final List<StagingLlmPass.LeftBehind> pLeftBehind)
	{
		final Set<String> out = new LinkedHashSet<String>();
		for (final StagingLlmPass.LeftBehind group : pLeftBehind)
		{
			out.add(group.canonicalEntity);
		}
		return new ArrayList<String>(out);
	}

	private static int indexOf(final List<StagingLlmPass.CreatedEntity> pEntities, final String pName)
	{
		for (int i = 0; i < pEntities.size(); i++)
		{
			if (pEntities.get(i).name.equals(pName))
			{
				return i;
			}
		}
		return -1;
	}

	/** The entity with {@code pSentence} first in its rationale and its confidence capped, attributes untouched: a copy, {@code memberEntities} carried over. */
	private static StagingLlmPass.CreatedEntity withEntityRationale(final StagingLlmPass.CreatedEntity pEntity, final String pSentence, final int pCap)
	{
		final StagingLlmPass.CreatedEntity out = new StagingLlmPass.CreatedEntity(pEntity.name, pEntity.label, ProposalChecks.prefixed(pSentence, pEntity.rationale),
			Math.min(pEntity.confidence, pCap), new ArrayList<StagingLlmPass.CreatedAttribute>(pEntity.attributes), pEntity.parentEntity, pEntity.parentLinkAttribute,
			pEntity.parentLinkField, pEntity.existingId);
		out.memberEntities.addAll(pEntity.memberEntities);
		return out;
	}

	/** The first note that names {@code pEntityName} as a whole word, or null: what the model said about an entity it fed nothing into. */
	static String noteNaming(final String pEntityName, final List<String> pNotes)
	{
		if (pNotes == null)
		{
			return null;
		}
		final java.util.regex.Pattern name = java.util.regex.Pattern.compile("(?<![A-Za-z0-9_])" + java.util.regex.Pattern.quote(pEntityName) + "(?![A-Za-z0-9_])");
		for (final String note : pNotes)
		{
			if (note != null && name.matcher(note).find())
			{
				return note.trim();
			}
		}
		return null;
	}

	private static StagingLlmPass.CreatedAttribute withNote(final StagingLlmPass.CreatedAttribute pAttribute, final String pSentence, final int pCap)
	{
		return new StagingLlmPass.CreatedAttribute(pAttribute.name, pAttribute.label, pAttribute.dataType, pAttribute.maxLength, pAttribute.mandatory,
			pAttribute.businessKey, ProposalChecks.prefixed(pSentence, pAttribute.rationale), Math.min(pAttribute.confidence, pCap));
	}

	/** Caps the confidence of every rule of {@code pEntity} feeding {@code pAttribute}, replacing them in their feeds. */
	private static void capRules(final List<StagingLlmPass.CreatedFeed> pFeeds, final String pEntity, final String pAttribute, final int pCap)
	{
		for (final StagingLlmPass.CreatedFeed feed : pFeeds)
		{
			if (!feed.entityName.equals(pEntity))
			{
				continue;
			}
			for (int i = 0; i < feed.rules.size(); i++)
			{
				final StagingLlmPass.CreatedFeedRule rule = feed.rules.get(i);
				if (rule.attribute.equals(pAttribute) && rule.confidence > pCap)
				{
					feed.rules.set(i, new StagingLlmPass.CreatedFeedRule(rule.attribute, rule.landingColumn, rule.pipeline, rule.rationale, pCap));
				}
			}
		}
	}

	private static String prefixed(final String pSentence, final String pRationale)
	{
		return pRationale == null || pRationale.trim().isEmpty() ? pSentence : pSentence + " " + pRationale;
	}

	private static StagingLlmPass.CreatedEntity find(final List<StagingLlmPass.CreatedEntity> pEntities, final String pName)
	{
		for (final StagingLlmPass.CreatedEntity entity : pEntities)
		{
			if (entity.name.equals(pName))
			{
				return entity;
			}
		}
		return null;
	}

	private static String display(final List<StagingLlmPass.CreatedEntity> pEntities, final String pName)
	{
		final StagingLlmPass.CreatedEntity entity = ProposalChecks.find(pEntities, pName);
		return entity == null || entity.label == null || entity.label.trim().isEmpty() ? pName : entity.label;
	}
}
