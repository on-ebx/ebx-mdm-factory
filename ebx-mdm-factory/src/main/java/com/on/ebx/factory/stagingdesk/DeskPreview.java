package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.mappingmatrix.RowTransform;
import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.QualityDetectors;
import com.on.ebx.factory.quality.QualityRuleCheck;
import com.on.ebx.factory.review.ReviewLine;
import com.orchestranetworks.service.Session;

/**
 * The centre of an object group, "&lt;Object&gt;, as it will be loaded" (docs/STAGING-DESK.md L2): for each source (feed) of ONE business object, a few REAL landing rows and what the
 * loader would make of them, one column per field the feed fills. Plain data (no EBX repository access), built by {@link #build} from the matrix model of the project and the rows
 * {@code ebx.DeskPreviewReader} read - so every part of it is unit-tested with fixture rows ({@code DeskPreviewTest}); {@link DeskHtml} draws it.
 * <p>
 * Nothing is computed here that the loader or the matrix does not already compute: a value is {@link RowTransform#apply} (the very method the matrix' "before -&gt; after" sample
 * runs: cleaning steps, then the mapping's pipeline through the Connectors engine), a rule is {@link QualityRuleCheck#verdict} (the transfer engine's own evaluator for PATTERN,
 * REFERENCE and CHECK_DIGIT), a flag is the matrix' own {@code AttentionRules} reason ({@link MatrixModel.Cell#attentionReason}).
 * <ul>
 * <li>A feed whose rows cannot be previewed (a collection feed, no landing table, an empty table) is a {@link Block#fallback}: the desk prints the list of its proposals instead,
 * under the reason, the wording of {@code MatrixModel.Sample#unavailable}.</li>
 * <li>A pipeline that needs the load's own reference data ({@code fk}/{@code lookup}/{@code golden}/{@code mdmstate}), a matching key and a computed field are
 * {@link #RUNTIME}: "computed at load", never a guess. The parent link is {@link #ENGINE}: "its &lt;Parent&gt;, by the engine". The technical key is not a column.</li>
 * <li>{@link #keys()} is what the "Accept as shown" button needs: which feeds were shown and which links were flagged.</li>
 * </ul>
 */
public final class DeskPreview
{
	/** How many landing rows a block shows (the same rows for every column of a block). */
	public static final int ROWS = 5;
	/** A source cell reads at most this many landing columns ("StreetName · HouseNumber"). */
	static final int SOURCE_COLUMNS = 2;

	/** {@link Cell#kind}: a produced value. */
	public static final String VALUE = "VALUE";
	/** {@link Cell#kind}: the pipeline made nothing of this row. */
	public static final String EMPTY = "EMPTY";
	/** {@link Cell#kind}: computed at load (a look-up, a matching key, a computed field) - no value ahead of the load. */
	public static final String RUNTIME = "RUNTIME";
	/** {@link Cell#kind}: written by the engine (the link of a child to its parent). */
	public static final String ENGINE = "ENGINE";
	/** {@link Cell#kind}: the engine refused the row ({@link Cell#text} says why). */
	public static final String ERROR = "ERROR";

	/** {@link Column#kind}: a field fed by a field link. */
	public static final String DATA = "DATA";
	/** {@link Column#kind}: the parent link. */
	public static final String PARENT = "PARENT";
	/** {@link Column#kind}: a matching key or computed field. */
	public static final String COMPUTED = "COMPUTED";

	/** One landing row read for a feed, as the reader hands it over: the documents (the matrix' and the loader's {@code buildRowDocument}), the mapping cleaning steps, and why nothing could be read. */
	public static final class FeedRows
	{
		/** At most {@link DeskPreview#ROWS} documents, in primary-key order, from {@link #offset}. */
		public final List<ObjectNode> documents = new ArrayList<ObjectNode>();
		public int offset;
		/** True when the table holds more rows after these. */
		public boolean more;
		/** "" when the rows were read, else the reason (the matrix' wording, {@code RowTransform.REASON_*}). */
		public String unavailable = "";
		/** The ACCEPTED-or-APPLIED cleaning steps of each shown mapping, read with the loader's own reader. */
		public final Map<String, List<CleaningSteps.Step>> stepsByMappingId = new HashMap<String, List<CleaningSteps.Step>>();
	}

	/** One cell of the table. */
	public static final class Cell
	{
		public final String kind;
		/** The value, or the engine's error; "" for {@link #EMPTY}, {@link #RUNTIME}, {@link #ENGINE}. */
		public final String text;
		/** A rule of the field that would fail on this value: its label, else "". */
		public final String ruleLabel;
		/** {@code WARN} or {@code REJECT} when {@link #ruleLabel} is set. */
		public final String ruleSeverity;
		/** "sample before cleaning steps..." when a cleaning step could not run, else "". */
		public final String note;

		Cell(final String pKind, final String pText, final String pRuleLabel, final String pRuleSeverity, final String pNote)
		{
			this.kind = pKind;
			this.text = pText == null ? "" : pText;
			this.ruleLabel = pRuleLabel == null ? "" : pRuleLabel;
			this.ruleSeverity = pRuleSeverity == null ? "" : pRuleSeverity;
			this.note = pNote == null ? "" : pNote;
		}

		public boolean ruleFails()
		{
			return !this.ruleLabel.isEmpty();
		}
	}

	/** One column: a field of the object fed by this source, with its "how". */
	public static final class Column
	{
		public final String kind;
		public final String attributeId;
		public final String name;
		/** The landing column read, or the pipeline ({@code constant(MAILING)}, {@code regex ^(\d+)}); for {@link #PARENT} the parent object's name; "" for {@link #COMPUTED}. */
		public final String how;
		public final String mappingId;
		/** The matrix' own reason this link needs an eye ({@code AttentionRules}), "" when not flagged. */
		public final String attention;
		/** True when a rule of the field fails on at least one shown value. */
		public boolean ruleFails;

		Column(final String pKind, final String pAttributeId, final String pName, final String pHow, final String pMappingId, final String pAttention)
		{
			this.kind = pKind;
			this.attributeId = pAttributeId;
			this.name = pName;
			this.how = pHow == null ? "" : pHow;
			this.mappingId = pMappingId;
			this.attention = pAttention == null ? "" : pAttention;
		}

		/** The link waits: the matrix flags it, or a rule fails on a shown value. */
		public boolean flagged()
		{
			return !this.attention.isEmpty() || this.ruleFails;
		}
	}

	/** "K of M rows have no field, field": the fields of one source that are not filled in every landing row. */
	public static final class FillLine
	{
		public final long missing;
		public final long total;
		public final List<String> fields = new ArrayList<String>();

		FillLine(final long pMissing, final long pTotal)
		{
			this.missing = pMissing;
			this.total = pTotal;
		}
	}

	/** One source (feed) of the object. */
	public static final class Block
	{
		public final String feedId;
		public final String sourceName;
		public final String tableName;
		/** Rows of the landing table, null when unknown. */
		public final Long rowCount;
		public int offset;
		public boolean more;
		/** "" when the rows are shown, else why they are not (the list of the feed's proposals is printed instead). */
		public String fallback = "";
		/** The grey column: "StreetName · HouseNumber", "" when no column of this source is worth a raw column. */
		public String sourceHeader = "";
		public final List<String> sourceCells = new ArrayList<String>();
		public final List<Column> columns = new ArrayList<Column>();
		/** One list per landing row, one cell per {@link #columns} entry. */
		public final List<List<Cell>> rows = new ArrayList<List<Cell>>();
		public final List<FillLine> fillLines = new ArrayList<FillLine>();
		/** The ids of the mappings (links) of this block, shown or not - for the fallback list. */
		public final List<String> mappingIds = new ArrayList<String>();

		Block(final String pFeedId, final String pSourceName, final String pTableName, final Long pRowCount)
		{
			this.feedId = pFeedId;
			this.sourceName = pSourceName;
			this.tableName = pTableName;
			this.rowCount = pRowCount;
		}

		public boolean isFallback()
		{
			return !this.fallback.isEmpty();
		}
	}

	/** A field shown as a column in at least one block: what "Reject a column..." offers. */
	public static final class FieldRef
	{
		public final String id;
		public final String name;

		FieldRef(final String pId, final String pName)
		{
			this.id = pId;
			this.name = pName;
		}
	}

	public final String entityId;
	public final String entityName;
	public final List<Block> blocks = new ArrayList<Block>();
	public final List<FieldRef> fields = new ArrayList<FieldRef>();
	private final MatrixCompute.ShownKeys keys = new MatrixCompute.ShownKeys();
	/** What "Accept as shown" takes and leaves, from {@link MatrixCompute#planEntityAsShown} on the very keys above. */
	public int takenFields;
	public int takenLinks;
	/** The links still {@code PROPOSED} in the shown feeds (taken or flagged). */
	public int totalLinks;
	public int held;
	/** Nothing to accept: every shown item is decided or flagged. */
	public boolean nothingToTake;

	DeskPreview(final String pEntityId, final String pEntityName)
	{
		this.entityId = pEntityId;
		this.entityName = pEntityName;
	}

	/** The feeds shown and the links flagged: what {@link MatrixCompute#planEntityAsShown} needs. */
	public MatrixCompute.ShownKeys keys()
	{
		return this.keys;
	}

	public Block block(final String pFeedId)
	{
		for (final Block block : this.blocks)
		{
			if (block.feedId.equals(pFeedId))
			{
				return block;
			}
		}
		return null;
	}

	// ----------------------------------------------------------------------------------------------------------------- build

	/**
	 * The preview of one business object. {@code pRowsByFeedId} holds what the reader read for each feed (a feed without an entry is a fallback), {@code pLookups} the reference
	 * data the REFERENCE rules and the cleaning steps need (null: REFERENCE rules are not evaluated rather than failing every value), {@code pSession} what the Connectors engine
	 * is given (null in a test).
	 */
	public static DeskPreview build(final MatrixModel.Project pProject, final MatrixModel.Entity pEntity, final Map<String, FeedRows> pRowsByFeedId, final CleaningSteps.Lookups pLookups,
		final Session pSession)
	{
		return DeskPreview.build(pProject, pEntity, pRowsByFeedId, pLookups, pSession, null);
	}

	/** A rule that applies only to the rows whose field {@link #attributeName} holds one of {@link #values} (lot L4, {@code QualityRule.conditionAttribute}). */
	public static final class RuleCondition
	{
		public final String attributeName;
		public final List<String> values;

		public RuleCondition(final String pAttributeName, final List<String> pValues)
		{
			this.attributeName = pAttributeName;
			this.values = pValues;
		}
	}

	/**
	 * Same, with the conditions of the rules ({@code pConditions}: rule id to condition, may be null): a rule with a condition flags a row only when the row's value of the condition
	 * field, as the loader would produce it, is one of the condition's values - a row outside it is not flagged, and a row whose condition field is not shown is not flagged either.
	 */
	public static DeskPreview build(final MatrixModel.Project pProject, final MatrixModel.Entity pEntity, final Map<String, FeedRows> pRowsByFeedId, final CleaningSteps.Lookups pLookups,
		final Session pSession, final Map<String, RuleCondition> pConditions)
	{
		final DeskPreview preview = new DeskPreview(pEntity.id, pEntity.name);
		final Map<String, String> sourceNames = DeskSourceNames.of(pProject);
		final Map<String, String> fieldNames = new LinkedHashMap<String, String>();
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			final String sourceName = sourceNames.containsKey(feed.sourceId) ? sourceNames.get(feed.sourceId) : "?";
			final Block block = new Block(feed.id, sourceName, feed.landingTableName, feed.rowCount);
			preview.blocks.add(block);
			for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
			{
				if (cell.mappingId != null)
				{
					block.mappingIds.add(cell.mappingId);
				}
			}
			final FeedRows rows = pRowsByFeedId == null ? null : pRowsByFeedId.get(feed.id);
			if (!feed.collectionPath.isEmpty())
			{
				block.fallback = RowTransform.REASON_COLLECTION;
			}
			else if (rows == null)
			{
				block.fallback = RowTransform.REASON_NO_LANDING;
			}
			else if (!rows.unavailable.isEmpty())
			{
				block.fallback = rows.unavailable;
			}
			else if (rows.documents.isEmpty())
			{
				block.fallback = RowTransform.REASON_EMPTY_TABLE;
			}
			if (block.isFallback())
			{
				continue;
			}
			block.offset = rows.offset;
			block.more = rows.more;
			preview.keys.shownFeedIds.add(feed.id);
			DeskPreview.fillBlock(preview, block, pEntity, feed, rows, pLookups, pSession, fieldNames, pConditions);
		}
		for (final Map.Entry<String, String> field : fieldNames.entrySet())
		{
			preview.fields.add(new FieldRef(field.getKey(), field.getValue()));
		}
		// the plan the button will run, counted for its sentence
		final MatrixCompute.BulkPlan plan = MatrixCompute.planEntityAsShown(pEntity, preview.keys);
		for (final MatrixCompute.PlannedDecision decision : plan.toDecide)
		{
			if (ReviewLine.ATTRIBUTE.equals(decision.nature))
			{
				preview.takenFields++;
			}
			else if (ReviewLine.MAPPING.equals(decision.nature))
			{
				preview.takenLinks++;
			}
		}
		preview.held = plan.held;
		preview.nothingToTake = plan.toDecide.isEmpty();
		for (final MatrixModel.Feed feed : pEntity.feeds)
		{
			if (!preview.keys.shownFeedIds.contains(feed.id))
			{
				continue;
			}
			for (final MatrixModel.Cell cell : feed.cellsByAttributeId.values())
			{
				if (cell.mappingId != null && ReviewLine.PROPOSED.equals(cell.status))
				{
					preview.totalLinks++;
				}
			}
		}
		return preview;
	}

	private static void fillBlock(final DeskPreview pPreview, final Block pBlock, final MatrixModel.Entity pEntity, final MatrixModel.Feed pFeed, final FeedRows pRows,
		final CleaningSteps.Lookups pLookups, final Session pSession, final Map<String, String> pFieldNames, final Map<String, RuleCondition> pConditions)
	{
		// the columns, in the order of the object's fields
		final List<MatrixModel.Attr> attrs = new ArrayList<MatrixModel.Attr>();
		final List<MatrixModel.Cell> cells = new ArrayList<MatrixModel.Cell>();
		for (final MatrixModel.Attr attr : pEntity.attrs)
		{
			if (attr.sourceKey)
			{
				continue; // sourceSystem / sourceId: technical, not shown
			}
			final MatrixModel.Cell cell = pFeed.cellsByAttributeId.get(attr.id);
			if (attr.parentLink)
			{
				if (cell != null && MatrixModel.ENGINE.equals(cell.status))
				{
					pBlock.columns.add(new Column(PARENT, attr.id, attr.name, attr.parentEntityName.isEmpty() ? attr.name : attr.parentEntityName, null, ""));
					attrs.add(attr);
					cells.add(cell);
				}
				continue;
			}
			if (attr.isDerived())
			{
				pBlock.columns.add(new Column(COMPUTED, attr.id, attr.name, "", null, ""));
				attrs.add(attr);
				cells.add(null);
				continue;
			}
			if (cell == null || cell.mappingId == null || !DeskPreview.isShownStatus(cell.status))
			{
				continue; // not fed by this source, or a rejected link
			}
			pBlock.columns.add(new Column(DATA, attr.id, attr.name, DeskPreview.how(cell), cell.mappingId, cell.attentionReason));
			attrs.add(attr);
			cells.add(cell);
			pFieldNames.put(attr.id, attr.name);
		}

		// the rules of each field, built once
		final List<List<QualityRuleCheck.Rule>> rules = new ArrayList<List<QualityRuleCheck.Rule>>();
		final List<List<Guard>> ruleGuards = new ArrayList<List<Guard>>();
		for (int c = 0; c < pBlock.columns.size(); c++)
		{
			final List<QualityRuleCheck.Rule> forColumn = new ArrayList<QualityRuleCheck.Rule>();
			final List<Guard> guards = new ArrayList<Guard>();
			if (DATA.equals(pBlock.columns.get(c).kind))
			{
				for (final Guard guard : attrs.get(c).guards)
				{
					if (DeskPreview.evaluable(guard, pLookups))
					{
						forColumn.add(new QualityRuleCheck.Rule(guard.id, guard.kind, guard.argument, guard.severity));
						guards.add(guard);
					}
				}
			}
			rules.add(forColumn);
			ruleGuards.add(guards);
		}

		// the source column: the landing columns a transformation reads
		final List<String> sourceFields = new ArrayList<String>();
		final List<String> sourceLabels = new ArrayList<String>();
		DeskPreview.pickSourceColumns(pBlock, cells, pRows, sourceFields, sourceLabels);
		pBlock.sourceHeader = String.join(" · ", sourceLabels);

		for (final ObjectNode raw : pRows.documents)
		{
			final StringBuilder source = new StringBuilder();
			for (final String field : sourceFields)
			{
				final String value = RowTransform.text(raw, field);
				if (!value.isEmpty())
				{
					source.append(source.length() == 0 ? "" : " · ").append(value);
				}
			}
			pBlock.sourceCells.add(source.toString());
			// the values as the loader would stage them first (a rule may be conditioned on another field of the row), then the rules
			final int count = pBlock.columns.size();
			final Cell[] fixed = new Cell[count];
			final String[] afters = new String[count];
			final String[] notes = new String[count];
			final Map<String, String> afterByField = new HashMap<String, String>();
			for (int c = 0; c < count; c++)
			{
				final Column column = pBlock.columns.get(c);
				if (PARENT.equals(column.kind))
				{
					fixed[c] = new Cell(ENGINE, "", null, null, null);
					continue;
				}
				final MatrixModel.Cell cell = cells.get(c);
				if (COMPUTED.equals(column.kind) || RowTransform.needsRuntimeLookup(cell.pipeline))
				{
					fixed[c] = new Cell(RUNTIME, "", null, null, null);
					continue;
				}
				if (cell.landingField.isEmpty())
				{
					fixed[c] = new Cell(ERROR, RowTransform.REASON_NO_LANDING, null, null, null);
					continue;
				}
				final List<CleaningSteps.Step> steps = pRows.stepsByMappingId.containsKey(cell.mappingId) ? pRows.stepsByMappingId.get(cell.mappingId)
					: new ArrayList<CleaningSteps.Step>();
				final String scopeField = CleaningSteps.scopeFieldOf(steps);
				final RowTransform.Result result = RowTransform.apply(raw.deepCopy(), cell.landingField, steps, scopeField == null ? null : RowTransform.text(raw, scopeField), cell.pipeline,
					pLookups, pSession);
				if (result.failed())
				{
					fixed[c] = new Cell(ERROR, result.error, null, null, null);
					continue;
				}
				if (result.after == null || result.after.trim().isEmpty())
				{
					fixed[c] = new Cell(EMPTY, "", null, null, result.note);
					continue;
				}
				afters[c] = result.after;
				notes[c] = result.note;
				afterByField.put(column.name, result.after);
			}
			final List<Cell> line = new ArrayList<Cell>();
			for (int c = 0; c < count; c++)
			{
				if (fixed[c] != null)
				{
					line.add(fixed[c]);
					continue;
				}
				final Column column = pBlock.columns.get(c);
				// the transfer engine's own evaluator, on the value as staged, for the rules whose condition the row meets
				String ruleLabel = "";
				String ruleSeverity = "";
				for (int r = 0; r < rules.get(c).size() && ruleLabel.isEmpty(); r++)
				{
					final RuleCondition condition = pConditions == null ? null : pConditions.get(ruleGuards.get(c).get(r).id);
					if (condition != null && !QualityRuleCheck.valueIn(afterByField.get(condition.attributeName), condition.values))
					{
						continue; // outside the rule's condition: not checked
					}
					if (QualityRuleCheck.verdict(rules.get(c).get(r), afters[c], pLookups) == QualityRuleCheck.Verdict.FAIL)
					{
						ruleLabel = ruleGuards.get(c).get(r).label();
						ruleSeverity = QualityDetectors.REJECT.equals(ruleGuards.get(c).get(r).severity) ? QualityDetectors.REJECT : QualityDetectors.WARN;
						column.ruleFails = true;
					}
				}
				line.add(new Cell(VALUE, afters[c], ruleLabel, ruleSeverity, notes[c]));
			}
			pBlock.rows.add(line);
		}

		// the links this block flags (the matrix' own flag, or a rule failing on a shown value)
		for (final Column column : pBlock.columns)
		{
			if (DATA.equals(column.kind) && column.flagged())
			{
				pPreview.keys.flaggedMappingIds.add(column.mappingId);
			}
		}
		DeskPreview.fillLines(pBlock, cells);
	}

	/** A link the preview shows: not rejected, and a real data link. */
	private static boolean isShownStatus(final String pStatus)
	{
		return MatrixModel.PROPOSED.equals(pStatus) || MatrixModel.ACCEPTED.equals(pStatus) || "APPLIED".equals(pStatus);
	}

	/**
	 * The "how" under a header (live fix 2026-10-22, the validated mockup): a constant shows {@code constant(MAILING)} alone; a straight copy - the default normalisation only
	 * ({@code trim()}, or no step) - shows the landing column; a real transformation shows {@code <landing column> . <pipeline>} ({@code MailingStreet . regex('^(\d+)')}). The
	 * pipeline is the link's display steps; a column that cannot be named shows the pipeline alone.
	 */
	static String how(final MatrixModel.Cell pCell)
	{
		final String column = pCell.landingColumn == null || pCell.landingColumn.isEmpty() ? pCell.landingField : pCell.landingColumn;
		final String pipeline = pCell.pipelineSteps.isEmpty() ? (pCell.pipeline == null ? "" : pCell.pipeline) : String.join(" \u203A ", pCell.pipelineSteps);
		if (DeskPreview.isConstant(pCell))
		{
			return pipeline;
		}
		if (DeskPreview.isDefaultNormalisation(pCell))
		{
			return column;
		}
		return column.isEmpty() ? pipeline : column + " \u00B7 " + pipeline;
	}

	/** True when the link only copies the landing value as the loader always does: no step, or the one default step {@code trim} (written {@code trim} or {@code trim()}). */
	static boolean isDefaultNormalisation(final MatrixModel.Cell pCell)
	{
		if (pCell.pipelineSteps.isEmpty())
		{
			return DeskPreview.isTrim(pCell.pipeline);
		}
		for (final String step : pCell.pipelineSteps)
		{
			if (!DeskPreview.isTrim(step))
			{
				return false;
			}
		}
		return true;
	}

	/** Empty, {@code trim} or {@code trim()} - spaces and case ignored. */
	private static boolean isTrim(final String pText)
	{
		final String text = pText == null ? "" : pText.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
		return text.isEmpty() || "trim".equals(text) || "trim()".equals(text);
	}

	/** A rule the transfer engine evaluates per value (PATTERN, REFERENCE, CHECK_DIGIT) that would warn or reject, still to decide or accepted. */
	private static boolean evaluable(final Guard pGuard, final CleaningSteps.Lookups pLookups)
	{
		if (!ReviewLine.QUALITY.equals(pGuard.nature))
		{
			return false;
		}
		if (!ReviewLine.PROPOSED.equals(pGuard.status) && !ReviewLine.ACCEPTED.equals(pGuard.status) && !ReviewLine.APPLIED.equals(pGuard.status))
		{
			return false;
		}
		if (!QualityDetectors.WARN.equals(pGuard.severity) && !QualityDetectors.REJECT.equals(pGuard.severity))
		{
			return false; // MODEL_CONSTRAINT is a model facet, not a per-value check
		}
		if (QualityDetectors.REFERENCE.equals(pGuard.kind))
		{
			return pLookups != null; // without the reference data every value would fail
		}
		return QualityDetectors.PATTERN.equals(pGuard.kind) || QualityDetectors.CHECK_DIGIT.equals(pGuard.kind);
	}

	/**
	 * The landing columns worth a grey "source" column: the ones a link TRANSFORMS (a pipeline other than a constant or a look-up of the load, or cleaning steps), first; when no link transforms
	 * anything, the first columns the links read. At most {@link #SOURCE_COLUMNS}, in the order of the fields. A source that only holds constants has none.
	 */
	private static void pickSourceColumns(final Block pBlock, final List<MatrixModel.Cell> pCells, final FeedRows pRows, final List<String> pFields, final List<String> pLabels)
	{
		for (final boolean transformedOnly : new boolean[] { true, false })
		{
			for (int c = 0; c < pBlock.columns.size() && pFields.size() < SOURCE_COLUMNS; c++)
			{
				final MatrixModel.Cell cell = pCells.get(c);
				if (!DATA.equals(pBlock.columns.get(c).kind) || cell.landingField.isEmpty() || pFields.contains(cell.landingField) || DeskPreview.isConstant(cell))
				{
					continue;
				}
				final boolean transforms = !DeskPreview.isDefaultNormalisation(cell)
					|| (pRows.stepsByMappingId.containsKey(cell.mappingId) && !pRows.stepsByMappingId.get(cell.mappingId).isEmpty());
				if (transformedOnly && (!transforms || RowTransform.needsRuntimeLookup(cell.pipeline)))
				{
					continue;
				}
				pFields.add(cell.landingField);
				pLabels.add(cell.landingColumn == null || cell.landingColumn.isEmpty() ? cell.landingField : cell.landingColumn);
			}
			if (!pFields.isEmpty())
			{
				return;
			}
		}
	}

	/** A pipeline whose value never depends on the landing value ({@code constant(...)}): the column it names is only the field the link is attached to. */
	private static boolean isConstant(final MatrixModel.Cell pCell)
	{
		final String pipeline = pCell.pipeline.isEmpty() && !pCell.pipelineSteps.isEmpty() ? pCell.pipelineSteps.get(0) : pCell.pipeline;
		return pipeline.replace(" ", "").toLowerCase(java.util.Locale.ROOT).startsWith("constant(");
	}

	/** "K of M rows have no ...": the fields of this source whose landing column is filled in less than 100 % of the rows; nothing when the fill rate or the row count is unknown. */
	private static void fillLines(final Block pBlock, final List<MatrixModel.Cell> pCells)
	{
		if (pBlock.rowCount == null || pBlock.rowCount.longValue() <= 0L)
		{
			return;
		}
		final long total = pBlock.rowCount.longValue();
		final Map<Long, FillLine> byMissing = new LinkedHashMap<Long, FillLine>();
		for (int c = 0; c < pBlock.columns.size(); c++)
		{
			final MatrixModel.Cell cell = pCells.get(c);
			// a constant never reads the landing value and a look-up is made at the load: their landing column being empty blames nothing (live fix 2026-10-22);
			// the parent link is not a DATA column
			if (!DATA.equals(pBlock.columns.get(c).kind) || cell.landingFillRate == null || cell.landingFillRate.doubleValue() >= 100d || DeskPreview.isConstant(cell)
				|| RowTransform.needsRuntimeLookup(cell.pipeline))
			{
				continue;
			}
			final long missing = Math.round(total * (100d - cell.landingFillRate.doubleValue()) / 100d);
			if (missing <= 0L)
			{
				continue;
			}
			FillLine line = byMissing.get(Long.valueOf(missing));
			if (line == null)
			{
				line = new FillLine(missing, total);
				byMissing.put(Long.valueOf(missing), line);
			}
			line.fields.add(pBlock.columns.get(c).name);
		}
		final List<FillLine> sorted = new ArrayList<FillLine>(byMissing.values());
		java.util.Collections.sort(sorted, new java.util.Comparator<FillLine>()
		{
			@Override
			public int compare(final FillLine pLeft, final FillLine pRight)
			{
				return Long.compare(pRight.missing, pLeft.missing);
			}
		});
		pBlock.fillLines.addAll(sorted);
	}
}
