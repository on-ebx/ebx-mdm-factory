package com.on.ebx.factory.ebx;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaNode;
import com.orchestranetworks.service.LoggingCategory;

/**
 * Seam for field-by-field POST-MERGE provenance ({@code MergeValueLineageCompact}, `docs/MASTER-ZONE.md`
 * &sect;5.1: {@code id, mergingProcessId, sourceField, Items[]{fieldPath, recordId, sourceIndex, goldenIndex,
 * mergeFunction, condition, customValue, sourceValue, manualModified, modifiedByDataSteward}}).
 * <p>
 * <b>2026-09-22 - now implemented, conservatively.</b> The earlier version of this file left {@code Items[]}
 * entirely unread because reaching a live instance to confirm {@code Adaptation.get(Path)}'s behaviour on a
 * repeatable, {@code osd:class}-annotated group was not possible that session. This lot establishes the
 * mechanism from DOCUMENTATION rather than a live probe (still never run live - see class Javadoc below for
 * exactly what remains unverified):
 * <ul>
 * <li>The EBX Developer Guide's own {@code references/mapping_to_java.html} ("Mapping to Java") page,
 * section "Multiple cardinality on a complex element": <i>"If the attribute maxOccurs is greater than 1,
 * then the corresponding instance in Java is: An instance of java.util.List for an aggregated list, where
 * every element in the list is an instance of the Java class determined by the mapping of simple types, or
 * An instance of AdaptationTable, if the property osd:table is specified."</i> Read together with the
 * SIMPLE-element section just above it (the general "maxOccurs&gt;1 -&gt; List" rule) and the section "Mapping
 * of complex types to custom JavaBeans" (an {@code osd:class} node is a plain read-write JavaBean, no
 * "contextual computation" allowed in its accessors) - this is the DOCUMENTED, GENERIC EBX mechanism for
 * ANY {@code osd:class}-annotated repeatable group, not an add-on-specific or internal API. {@code
 * MergeValueLineageCompact}'s {@code Items} element declares exactly this shape in the add-on's own schema
 * (`webapps/ebx-addon-mame/WEB-INF/ebx/schema/metadata/MetadataTemplate.xsd`, read 2026-09-22):
 * {@code osd:class="com.orchestranetworks.addon.mame.model.metadata.MergeValueLineageItem"}, no {@code
 * osd:table} sibling - so {@code Adaptation.get(Path.parse("./Items"))} on a {@code MergeValueLineageCompact}
 * record is documented to answer a {@code java.util.List} of that bean type.</li>
 * <li><b>That bean class is NOT on this module's own compile classpath, and this class never references it
 * by name.</b> {@code javap}/{@code unzip -l} against {@code ebx-addons.jar} (2026-09-22, the same jar
 * {@link MameGroups}/{@link MameRecordState}/{@link MameSuspectDecision} already compile against) shows only
 * {@code com.orchestranetworks.addon.mame.model.metadata.MatchingResultType} in that package - {@code
 * MergeValueLineageItem}/{@code MergeValueLineageCompact} exist only inside the DEPLOYED {@code
 * ebx-addon-mame} webapp's own {@code WEB-INF/classes} (confirmed by the SAME two commands), a private,
 * per-webapp classpath this module's own webapp classloader cannot see. The local Java API doc
 * (`.../ebx-addon-mame/www/common/html/onHelp/advanced/Java_API/com/orchestranetworks/addon/mame/model/
 * metadata/`) lists only {@code MatchingResultType} too - {@code MergeValueLineageItem} carries no Javadoc
 * page at all, confirming it is the add-on's own INTERNAL bean, never part of its published Java API.
 * {@link #read} therefore reads {@code Items} through plain REFLECTION ({@code Object.getClass().getMethod
 * (...)}) rather than a compile-time reference - this needs no add-on class on the classpath at all (so no
 * {@link LinkageError} seam is needed here, unlike {@link MameGroups}/{@link MameRecordState}/{@link
 * MameSuspectDecision}, which DO reference add-on service types that ARE on this classpath) and degrades to
 * "unread" for a single item, or the whole call, on ANY reflective failure - never a guess, never a stack
 * trace reaching the caller.</li>
 * <li><b>Which fields are safe to trust BY NAME, and which are not.</b> {@code fieldPath}/{@code
 * mergeFunction}/{@code sourceValue}/{@code manualModified}/{@code modifiedByDataSteward} are unambiguous
 * from their own names. {@code recordId}/{@code sourceIndex}/{@code goldenIndex} are NOT - which of the
 * group's own contributing records {@code recordId} names (a master key? a staging key? an internal row
 * id?), and what {@code sourceIndex}/{@code goldenIndex} index into, was never confirmed live and no
 * documentation for this internal bean exists to check against. Per the task brief's own instruction ("if a
 * field's meaning is unclear ... say so and read conservatively"), {@link #read} tries {@code recordId}
 * against a contributing master key ONLY as a plain string-equality guess (documented as exactly that,
 * below), and otherwise relies entirely on {@code sourceValue} - a value this reader can independently
 * verify by comparing it to what the contributing sources actually hold, never trusted on its own.</li>
 * </ul>
 * <p>
 * <b>How the retained contributor is identified, and why it can still come back unknown.</b> <i>Superseded by
 * the "corrected against a REAL row" section below - kept only for the historical reasoning.</i> For the
 * requested field, {@link #read} originally planned to find the {@code MergeValueLineageCompact} row(s)
 * matching the golden's own {@code mergingProcessId} and the business field name, then try TWO independent
 * identification strategies over {@code Items}: by {@code recordId} (an item whose {@code recordId} equals
 * one of the group's own contributing master keys), and, as a fallback, by {@code sourceValue} (an item
 * whose {@code sourceValue} equals the comparable value of exactly one contributing source for this field).
 * <b>The {@code sourceValue} strategy was never implemented for real and does not exist in {@link
 * #readFromItems}</b> - see "corrected against a REAL row" below: a live row showed {@code sourceValue} is
 * always null, so only the {@code recordId} strategy remains. When it fails to produce a single answer,
 * {@link Result#knownRetainedMasterKey} stays null - {@code ebx.RecordLineageReader} then leaves the field to
 * the existing DEDUCED fallback ({@code lineage.RecordLineage.FieldInput}'s own Javadoc), exactly as before
 * this lot.
 * <p>
 * <b>The survivorship-rule sentence</b> ({@link Result#survivorshipRule}) is built from the SAME item's
 * {@code mergeFunction} string, translated by {@link #FUNCTION_WORDS} - a small, best-effort word list built
 * from `docs/MATCHING.md`'s own measured record-selection/default-field-merge function names (both the raw
 * i18n-key middle segments, e.g. {@code mostCompleted}, and the human words the same document also recorded
 * for the default-field-merge chain, e.g. {@code "longest"}). Never verified against a REAL {@code
 * mergeFunction} string from a real merge - the raw string is shown verbatim, quoted, whenever it does not
 * match a known word, so nothing is ever silently dropped or mistranslated into something it did not say.
 * {@code modifiedByDataSteward}/{@code manualModified} (both booleans, unambiguous by name) override/append
 * to that sentence - see {@link #FUNCTION_WORDS}' own comment.
 * <p>
 * <b>Every failure degrades to {@link Result#unavailable()}, never an exception reaching {@code
 * ebx.RecordLineageReader}</b> - the entire body of {@link #read} runs inside one {@code catch
 * (RuntimeException)}, on top of the per-item, per-accessor guards described above.
 * <p>
 * <b>2026-09-22 - corrected against a REAL {@code MergeValueLineageCompact} row (coordinator's live read,
 * dataspace {@code Companies_Master}, dataset {@code Master}, table {@code /root/Company}, group merged into
 * golden 322 from 290/248).</b> Two assumptions above turned out wrong:
 * <ol>
 * <li><b>One row per {@code mergingProcessId}, not one row per FIELD.</b> The real row's own {@code
 * sourceField} was {@code null}; its single {@code Items[]} array covered EVERY business field of the merge
 * ({@code /sourceSystem}, {@code /sourceId}, {@code /name}, {@code /city}, {@code /vatNo}, {@code /id}, ... -
 * thirteen items for a merge of two source records). So {@link #read} no longer filters the row query by
 * {@code sourceField} at all - it fetches the ONE row for {@code mergingProcessId} and finds the field's own
 * item INSIDE {@code Items[]} by {@code fieldPath}, which is exactly {@code "/" + <business field name>}
 * (matches every item in the real row, e.g. {@code "/sourceSystem"}, {@code "/cityKey"}) - the same
 * record-relative leading-slash convention EBX itself uses elsewhere (CLAUDE.md &sect;9, {@code
 * workspaceDisplay=record:} predicates).</li>
 * <li><b>{@code sourceValue} is {@code null} on every item of the real row</b>, technical and business fields
 * alike - the strategy-2 fallback below (matching {@code sourceValue} against a contribution's own comparable
 * value) could never once succeed on this build and has been REMOVED, not merely deprioritised. The winner is
 * identified ONLY by {@code recordId} - the winning record's own formatted primary key ({@code "290"},
 * {@code "248"}, or the golden's own {@code "322"} for a technical/PK field like {@code /id}) - matched
 * against the caller's own contributing master keys; task brief, 2026-09-22: "the value is read from the
 * winning source record in the business table (NOT {@code sourceValue}, which is null)" - this reader does not
 * itself re-read that value (the caller, {@code ebx.RecordLineageReader}, already holds every contribution's
 * own value in {@code pContributingValuesByKey}/{@code lineage.RecordLineage.FieldContribution}; this reader
 * only names WHICH contributing key won).</li>
 * </ol>
 * The field-matching/rule-building logic (once {@code Items} is already a {@code List} of add-on beans) is
 * split into {@link #readFromItems}, a small, directly testable seam: any object exposing {@code
 * getFieldPath()}/{@code getRecordId()}/{@code getMergeFunction()}/{@code isModifiedByDataSteward()}/{@code
 * isManualModified()} (the exact reflective contract {@link #stringProperty}/{@link #booleanProperty} already
 * use) exercises the REAL matching/rule-building logic, fixture-built from the measured JSON above, with
 * neither {@code ebx-addons.jar}'s own internal bean nor a running server (task brief: "add a small interface
 * between raw items and the reader so the pure part is testable") - see {@code FieldMergeLineageSourceTest}.
 */
final class FieldMergeLineageSource
{
	/*
	 * Audit F2-08 (lot H) - SUPERSEDES every statement about reflection in the class Javadoc above: the items are no longer read through reflection on the add-on's internal bean. {@code Items} is a repeatable group of the
	 * add-on's own metadataset model, so each of its fields is a SCHEMA NODE ({@code MergeValueLineageCompact/Items/fieldPath}...) that the public
	 * {@code SchemaNode.executeRead(occurrence)} reads, with no add-on class on the classpath. When this build's model no longer declares one of
	 * those nodes (or a read fails), the answer is an explicit {@link Result#unreadable} with the reason, one WARN per start, and never an empty
	 * list taken for "nothing merged". The add-on's bean stays the add-on's business; its class is never named here.
	 */
	private static final String[] ITEM_NODES = { "fieldPath", "recordId", "mergeFunction", "modifiedByDataSteward", "manualModified" };
	private static final AtomicBoolean UNREADABLE_LOGGED = new AtomicBoolean(false);

	/** Reads one property of one {@code Items} occurrence; the production implementation goes through the schema nodes, a test one through a map. */
	interface ItemFields
	{
		Object get(Object pItem, String pNode);
	}

	/** Raised when this build's metadataset model does not declare an {@code Items} node this reader needs. */
	static final class ItemsUnreadableException extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		ItemsUnreadableException(final String pMessage)
		{
			super(pMessage);
		}
	}

	/** Shown in {@code fieldLineageNote} only when the golden's own {@code mergingProcessId} could not be read at all (no real merge run recorded for this group yet - the normal, expected state before the first merge, not a failure). */
	static final String NO_MERGE_YET = "Field-by-field merge provenance (MergeValueLineageCompact) is not available yet: this record's group has no recorded "
		+ "mergingProcessId (RecordMetadata.mergingProcessId), which normally means no merge has run for it yet. Retained-value cells below are DEDUCED, not read.";
	/** Shown in {@code fieldLineageNote} when a {@code mergingProcessId} exists but this reader could not resolve a confident retained contributor for at least one field - see class Javadoc for exactly what was and was not tried. */
	static final String PARTIAL_OR_UNVERIFIED = "Field-by-field merge provenance (MergeValueLineageCompact.Items[]) was read where a unique retained contributor "
		+ "could be identified; some fields still show a DEDUCED retained value only, or none. This mechanism was never exercised against a real merge this "
		+ "session (see docs/RECORD-LINEAGE.md \"Survivorship\") - verify against the first real merge before trusting it in production.";

	private FieldMergeLineageSource()
	{
	}

	static final class Result
	{
		final boolean available;
		/** Null when no single contributing master key could be identified with confidence - see class Javadoc. */
		final String knownRetainedMasterKey;
		/** Null unless {@link #knownRetainedMasterKey} is also set - {@code lineage.RecordLineage.FieldInput}'s own contract (a survivorship rule is only ever shown next to a KNOWN answer). */
		final String survivorshipRule;
		/** Null unless the lineage could not be READ on this build (the model or a read changed): then the text says why, so a screen can say "unavailable on this version" instead of showing nothing. */
		final String unreadableReason;

		private Result(final boolean pAvailable, final String pKnownRetainedMasterKey, final String pSurvivorshipRule)
		{
			this(pAvailable, pKnownRetainedMasterKey, pSurvivorshipRule, null);
		}

		private Result(final boolean pAvailable, final String pKnownRetainedMasterKey, final String pSurvivorshipRule, final String pUnreadableReason)
		{
			this.unreadableReason = pUnreadableReason;
			this.available = pAvailable;
			this.knownRetainedMasterKey = pKnownRetainedMasterKey;
			this.survivorshipRule = pSurvivorshipRule;
		}

		static Result unavailable()
		{
			return new Result(false, null, null);
		}

		/** The merge lineage exists but cannot be read on this build: {@link #available} is false and {@link #unreadableReason} says why. */
		static Result unreadable(final String pReason)
		{
			return new Result(false, null, null, pReason);
		}

		static Result of(final String pKnownRetainedMasterKey, final String pSurvivorshipRule)
		{
			return new Result(true, pKnownRetainedMasterKey, pSurvivorshipRule);
		}
	}

	/**
	 * Best-effort human words for a {@code mergeFunction} string - see class Javadoc for provenance and the
	 * "never verified live" caveat. Keys are matched case-insensitively, trimmed; an unrecognised value is
	 * shown as {@code "merge rule '<raw>'"} rather than dropped.
	 */
	private static final Map<String, String> FUNCTION_WORDS = new HashMap<String, String>();
	static
	{
		FieldMergeLineageSource.FUNCTION_WORDS.put("longest", "longest value");
		FieldMergeLineageSource.FUNCTION_WORDS.put("last update", "most recently updated source");
		FieldMergeLineageSource.FUNCTION_WORDS.put("lastupdate", "most recently updated source");
		FieldMergeLineageSource.FUNCTION_WORDS.put("max", "maximum value");
		FieldMergeLineageSource.FUNCTION_WORDS.put("min", "minimum value");
		FieldMergeLineageSource.FUNCTION_WORDS.put("most frequent", "most frequent value");
		FieldMergeLineageSource.FUNCTION_WORDS.put("mostfrequent", "most frequent value");
		FieldMergeLineageSource.FUNCTION_WORDS.put("most trusted source", "most trusted source");
		FieldMergeLineageSource.FUNCTION_WORDS.put("mosttrustedsource", "most trusted source");
		FieldMergeLineageSource.FUNCTION_WORDS.put("mostcompleted", "most complete record");
		FieldMergeLineageSource.FUNCTION_WORDS.put("firstacquired", "first acquired record");
		FieldMergeLineageSource.FUNCTION_WORDS.put("mostrecentlyacquired", "most recently acquired record");
		FieldMergeLineageSource.FUNCTION_WORDS.put("wasgolden", "record that was already golden");
	}

	private static String functionWords(final String pRawFunction)
	{
		if (pRawFunction == null || pRawFunction.trim().isEmpty())
		{
			return null;
		}
		final String key = pRawFunction.trim().toLowerCase(java.util.Locale.ENGLISH);
		final String words = FieldMergeLineageSource.FUNCTION_WORDS.get(key);
		return words != null ? words : "merge rule '" + pRawFunction.trim() + "'";
	}

	/**
	 * @param pCompactTable the {@code MergeValueLineageCompact} table of the golden's own metadataset (see
	 *            {@link MameMetadatasetReads#resolveMergeValueLineageTable} - no public API returns this, see
	 *            that class' own Javadoc) - null degrades to {@link Result#unavailable()}.
	 * @param pMergingProcessId the golden's own {@code RecordMetadata.mergingProcessId} - required.
	 * @param pFieldName the business field name (matched against {@code sourceField} verbatim - never
	 *            translated; a mismatch between this field's own name and how the add-on spells {@code
	 *            sourceField} would simply read as "no row found", the same as any other absent mapping in
	 *            this reader).
	 * @param pContributingValuesByKey contributing master key -&gt; this field's own comparable value
	 *            (staged, else raw) for every NON-restricted contribution - built by the caller from the
	 *            exact same data {@code lineage.RecordLineage.FieldContribution} already carries.
	 */
	static Result read(final AdaptationTable pCompactTable, final Integer pMergingProcessId, final String pFieldName, final Map<String, String> pContributingValuesByKey)
	{
		if (pCompactTable == null || pMergingProcessId == null || pFieldName == null || pCompactTable.getTableOccurrenceRootNode() == null)
		{
			return Result.unavailable();
		}
		try
		{
			// 2026-09-22 (class Javadoc, "corrected against a REAL row"): ONE row per mergingProcessId, covering
			// EVERY field in its own Items[] - never filtered by sourceField any more (the real row's own
			// sourceField was observed null).
			final RequestResult rows = pCompactTable.createRequestResult("./mergingProcessId=" + pMergingProcessId.intValue());
			final Adaptation compactRow;
			try
			{
				compactRow = rows.nextAdaptation();
			}
			finally
			{
				rows.close();
			}
			if (compactRow == null)
			{
				return Result.unavailable();
			}
			final Object rawItems = compactRow.getSchemaNode().getNode(Path.parse("./Items")) == null ? null : compactRow.get(Path.parse("./Items"));
			if (!(rawItems instanceof java.util.List))
			{
				return Result.unavailable(); // not the documented shape on this build - never guessed further, see class Javadoc
			}
			return FieldMergeLineageSource.readFromItems((java.util.List<?>) rawItems, pFieldName, pContributingValuesByKey,
				FieldMergeLineageSource.schemaFields(compactRow.getSchemaNode().getNode(Path.parse("./Items"))));
		}
		catch (final ItemsUnreadableException ex)
		{
			return FieldMergeLineageSource.unreadable(ex.getMessage());
		}
		catch (final RuntimeException ex)
		{
			return FieldMergeLineageSource.unreadable("a read of the merge lineage failed: " + ex);
		}
	}

	/**
	 * The pure matching/rule-building half of {@link #read}, split out 2026-09-22 so a test fixture can
	 * exercise it without {@code ebx-addons.jar}'s own internal bean or a running server - see class Javadoc
	 * "corrected against a REAL row". {@code pItems} is whatever {@code Adaptation.get(Path.parse("./Items"))}
	 * already handed {@link #read} (real add-on beans there, a plain test fixture in {@code
	 * FieldMergeLineageSourceTest} - either way read purely through {@link #stringProperty}/{@link
	 * #booleanProperty} reflection, so ANY object exposing the same five accessor names works identically).
	 * <p>
	 * Finds the ONE item whose {@code fieldPath} equals {@code "/" + pFieldName} (verbatim, case-sensitive -
	 * matches the real row's own spelling, e.g. {@code "/sourceSystem"}), then accepts it ONLY when its {@code
	 * recordId} names one of {@code pContributingValuesByKey}'s own keys - an unrecognised {@code recordId}
	 * (e.g. a technical/PK field's own item names the GOLDEN's key, which is never itself a "contributing"
	 * key in this map) answers {@link Result#unavailable()} rather than a wrong guess. Never throws: any
	 * unexpected shape simply fails to match and falls through to unavailable.
	 */
	static Result readFromItems(final java.util.List<?> pItems, final String pFieldName, final Map<String, String> pContributingValuesByKey,
		final ItemFields pFields)
	{
		final String fieldPath = "/" + pFieldName;
		Object matchedItem = null;
		for (final Object item : pItems)
		{
			if (fieldPath.equals(FieldMergeLineageSource.string(pFields, item, "fieldPath")))
			{
				matchedItem = item;
				break;
			}
		}
		if (matchedItem == null)
		{
			return Result.unavailable();
		}
		final String recordId = FieldMergeLineageSource.string(pFields, matchedItem, "recordId");
		if (recordId == null || pContributingValuesByKey == null || !pContributingValuesByKey.containsKey(recordId))
		{
			return Result.unavailable();
		}
		final String mergeFunction = FieldMergeLineageSource.string(pFields, matchedItem, "mergeFunction");
		final Boolean modifiedByDataSteward = FieldMergeLineageSource.bool(pFields, matchedItem, "modifiedByDataSteward");
		final Boolean manualModified = FieldMergeLineageSource.bool(pFields, matchedItem, "manualModified");
		String rule = Boolean.TRUE.equals(modifiedByDataSteward) ? "data steward decision" : FieldMergeLineageSource.functionWords(mergeFunction);
		if (rule == null)
		{
			rule = "recorded by the merge, rule not named";
		}
		else if (Boolean.TRUE.equals(manualModified) && !Boolean.TRUE.equals(modifiedByDataSteward))
		{
			rule = rule + " (manually adjusted)";
		}
		return Result.of(recordId, rule);
	}

	/** The production {@link ItemFields}: each property is the schema node of the same name under {@code Items}, read with the public {@code SchemaNode.executeRead}. */
	static ItemFields schemaFields(final SchemaNode pItemsNode)
	{
		if (pItemsNode == null)
		{
			throw new ItemsUnreadableException("this build's metadataset model has no Items node under MergeValueLineageCompact");
		}
		final Map<String, SchemaNode> nodes = new HashMap<String, SchemaNode>();
		for (final String name : FieldMergeLineageSource.ITEM_NODES)
		{
			final SchemaNode node = pItemsNode.getNode(Path.parse("./" + name));
			if (node == null)
			{
				throw new ItemsUnreadableException("this build's metadataset model has no Items/" + name + " node under MergeValueLineageCompact");
			}
			nodes.put(name, node);
		}
		return new ItemFields()
		{
			@Override
			public Object get(final Object pItem, final String pNode)
			{
				return nodes.get(pNode).executeRead(pItem);
			}
		};
	}

	/** Answers "unreadable" and says so in {@code kernel.log}, once per start (a screen opened a hundred times must not write a hundred lines). */
	private static Result unreadable(final String pReason)
	{
		if (FieldMergeLineageSource.UNREADABLE_LOGGED.compareAndSet(false, true))
		{
			try
			{
				LoggingCategory.getKernel().warn("[mdm-factory] merge lineage unavailable on this version of the Match and Merge add-on: " + pReason
					+ " (MergeValueLineageCompact.Items, audit F2-08). Retained values are shown as deduced; report it with the add-on build.");
			}
			catch (final Throwable ex)
			{
				// a logging failure never reaches the caller
			}
		}
		return Result.unreadable(pReason);
	}

	private static String string(final ItemFields pFields, final Object pItem, final String pNode)
	{
		if (pItem == null)
		{
			return null;
		}
		final Object value = pFields.get(pItem, pNode);
		return value == null ? null : String.valueOf(value);
	}

	private static Boolean bool(final ItemFields pFields, final Object pItem, final String pNode)
	{
		if (pItem == null)
		{
			return null;
		}
		final Object value = pFields.get(pItem, pNode);
		return value instanceof Boolean ? (Boolean) value : null;
	}
}
