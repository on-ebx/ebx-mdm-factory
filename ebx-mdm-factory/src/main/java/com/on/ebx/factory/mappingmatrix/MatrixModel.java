package com.on.ebx.factory.mappingmatrix;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.capability.FunctionDoc;
import com.on.ebx.factory.guard.Guard;

/**
 * Plain (no EBX type) data shape of the Mapping matrix screen (docs/MAPPING-MATRIX.md): one project's
 * sources, staging entities, {@code TransferFeed}/{@code AttributeMapping} rows resolved into a matrix
 * attribute &times; feed, and the overview graphic's own geometry. Built by {@code
 * ebx.MappingMatrixReader} (the only class of this feature that touches EBX), consumed by {@link
 * MatrixCompute} (derived facts: cell status, coverage, bulk-accept candidates, layout) and {@link
 * MatrixHtml} (rendering) - both pure, unit-tested without a live repository, the same split the rest of
 * this module keeps between its {@code ebx.*} readers and its pure page/model classes.
 * <p>
 * A cell's {@link Cell#status} is one of seven values, the same vocabulary the reference mockup
 * ({@code docs/mockups/mapping-matrix-5-sources.dc.html}) uses: {@link #ACCEPTED}/{@link #PROPOSED}/
 * {@link #REJECTED} (an ordinary {@code AttributeMapping} decision), {@link #KEY} (a source-key/child-link
 * value resolved by matching a landing column against an already-loaded parent key - shown "linked" in
 * the overview, violet dashed), {@link #ENGINE} (the child feed's own {@code parentLinkField}, written by
 * {@code StagingLoader} itself, never by a mapping row), {@link #NONE} (the source simply provides nothing
 * for this attribute - fine, another source may), {@link #MISSING} (a MANDATORY attribute this feed does
 * not cover at all, or covers only with a REJECTED mapping - every row of this feed will fail validation
 * in staging). An eighth value, {@link #DERIVED}, is never stored on a cell: {@code MatrixCompute#effectiveStatus}
 * answers it for every feed of a derived attribute ({@link Attr#isDerived}) - a matching key such as
 * {@code firstNameKey} ({@code StagingAttribute.derivation = FOLD_KEY}), computed at load time from another
 * attribute of the same entity and never fed by a source link (fix/matrix-derived-key-rows, 2026-09-27: until
 * then such a row read "0 / 2 source links", "not provided by this source" and "none proposed", which looks
 * exactly like a broken mapping while there is nothing to map).
 */
public final class MatrixModel
{
	private MatrixModel()
	{
	}

	public static final String ACCEPTED = "ACCEPTED";
	public static final String PROPOSED = "PROPOSED";
	public static final String REJECTED = "REJECTED";
	public static final String KEY = "KEY";
	public static final String ENGINE = "ENGINE";
	public static final String NONE = "NONE";
	public static final String MISSING = "MISSING";
	/** Computed at load time from another attribute of the entity - see the class Javadoc; never stored on a {@link Cell}. */
	public static final String DERIVED = "DERIVED";

	/** One project source, one card of the overview graphic and one column group of the matrix. */
	public static final class Source
	{
		public final String id;
		public final String sourceSystem;
		public final String color;

		public Source(final String pId, final String pSourceSystem, final String pColor)
		{
			this.id = pId;
			this.sourceSystem = pSourceSystem;
			this.color = pColor;
		}
	}

	/** One staging attribute, one row of the matrix. */
	public static final class Attr
	{
		public final String id;
		public final String name;
		public final String dataType;
		public final boolean mandatory;
		/** This attribute IS the child entity's own link-to-parent attribute (its {@code referenceEntity} names the parent of at least one child feed of this entity). */
		public final boolean parentLink;
		/** This is the synthetic "source key" row (the entity's own {@code sourceId}) - never a real {@code StagingAttribute} row, so it has no decision of its own (same rule as {@code primaryKey} - it follows its entity). */
		public final boolean sourceKey;
		/** The {@code StagingAttribute} row's own {@code status} ({@code PROPOSED}/{@code ACCEPTED}/{@code REJECTED}/{@code APPLIED}) - decided independently of any of its mappings; drives the row header's own Accept/Reject buttons. Empty for the synthetic {@link #sourceKey} row (never rendered). */
		public final String status;
		public final Integer confidence;
		/** Staging-proposal matrix lot: every guard ({@code guard.Guard}) attached to this attribute - {@code QUALITY}/{@code SCRIPT_CONSTRAINT}/{@code EXTERNAL_SERVICE}/{@code SCRIPT_DERIVATION}, read by {@code ebx.GuardReader}. Never null, empty when none proposed. */
		public final List<Guard> guards;
		/** {@code StagingAttribute.derivation} - {@code FOLD_KEY} (a matching key), {@code VALID_VALUE} or {@code SCRIPT} - or {@code ""} for an ordinary, mapping-fed attribute (the common case). A derived attribute has no source link by design and is computed at load time ({@code ebx.StagingLoader}); it keeps its own Accept/Reject (rejecting a matching key is a real decision: the matching plan then falls back to the raw field). */
		public final String derivation;
		/** The NAME of the attribute this one is derived from ({@code StagingAttribute.derivedFrom}, resolved by {@code ebx.MappingMatrixReader}) - {@code ""} when not derived or when the source row could not be read. */
		public final String derivedFrom;
		/** fix/matrix-computed-field: what a {@code SCRIPT}-derived attribute computes (the function, its arguments, its doc, what was measured) - null for every other attribute. */
		public final Computation computation;
		/** Lot G2 (2026-10-17): {@code StagingAttribute.rationale} - the proposer's own text, whose first sentence may be a check of lot A ({@code staging.ProposalChecks}) the card must show. Never null. */
		public final String rationale;
		/** Validation desk lot L2: for a parent-link attribute, the NAME of the parent business object it points to ({@code StagingAttribute.referenceEntity}, resolved by {@code ebx.MappingMatrixReader}) - "" otherwise or when unresolved. */
		public final String parentEntityName;

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey)
		{
			this(pId, pName, pDataType, pMandatory, pParentLink, pSourceKey, "", null);
		}

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey,
			final String pStatus, final Integer pConfidence)
		{
			this(pId, pName, pDataType, pMandatory, pParentLink, pSourceKey, pStatus, pConfidence, null);
		}

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey,
			final String pStatus, final Integer pConfidence, final List<Guard> pGuards)
		{
			this(pId, pName, pDataType, pMandatory, pParentLink, pSourceKey, pStatus, pConfidence, pGuards, "", "");
		}

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey,
			final String pStatus, final Integer pConfidence, final List<Guard> pGuards, final String pDerivation, final String pDerivedFrom)
		{
			this(pId, pName, pDataType, pMandatory, pParentLink, pSourceKey, pStatus, pConfidence, pGuards, pDerivation, pDerivedFrom, null);
		}

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey,
			final String pStatus, final Integer pConfidence, final List<Guard> pGuards, final String pDerivation, final String pDerivedFrom,
			final Computation pComputation)
		{
			this(pId, pName, pDataType, pMandatory, pParentLink, pSourceKey, pStatus, pConfidence, pGuards, pDerivation, pDerivedFrom, pComputation, "");
		}

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey,
			final String pStatus, final Integer pConfidence, final List<Guard> pGuards, final String pDerivation, final String pDerivedFrom,
			final Computation pComputation, final String pRationale)
		{
			this(pId, pName, pDataType, pMandatory, pParentLink, pSourceKey, pStatus, pConfidence, pGuards, pDerivation, pDerivedFrom, pComputation, pRationale, "");
		}

		public Attr(final String pId, final String pName, final String pDataType, final boolean pMandatory, final boolean pParentLink, final boolean pSourceKey,
			final String pStatus, final Integer pConfidence, final List<Guard> pGuards, final String pDerivation, final String pDerivedFrom,
			final Computation pComputation, final String pRationale, final String pParentEntityName)
		{
			this.parentEntityName = pParentEntityName == null ? "" : pParentEntityName;
			this.rationale = pRationale == null ? "" : pRationale;
			this.id = pId;
			this.name = pName;
			this.dataType = pDataType;
			this.mandatory = pMandatory;
			this.parentLink = pParentLink;
			this.sourceKey = pSourceKey;
			this.status = pStatus == null ? "" : pStatus;
			this.confidence = pConfidence;
			this.guards = pGuards == null ? Collections.<Guard>emptyList() : pGuards;
			this.derivation = pDerivation == null ? "" : pDerivation.trim();
			this.derivedFrom = pDerivedFrom == null ? "" : pDerivedFrom;
			this.computation = pComputation;
		}

		/** True for a computed attribute ({@link #derivation} set): no source link, one merged "computed at load" cell instead of one cell per feed. */
		public boolean isDerived()
		{
			return !this.derivation.isEmpty();
		}
	}

	/**
	 * fix/matrix-computed-field (docs/MAPPING-MATRIX.md sec 14): what a computed ({@code SCRIPT}) attribute computes,
	 * read once per render for the active entity by {@code ebx.MappingMatrixReader} from its {@code
	 * CapabilitySuggestion} of nature {@code SCRIPT_DERIVATION}, the library's own doc comment and, once the staging
	 * is loaded, a measurement of the staging table. Plain strings and numbers - every one of them is stored data, a
	 * library text or a count, never a guess.
	 */
	public static final class Computation
	{
		/** The {@code CapabilitySuggestion} row id (nature {@code SCRIPT_DERIVATION}). */
		public final String suggestionId;
		/** That row's status: a field accepted or rejected takes its {@code PROPOSED} suggestion along ({@code ebx.ProposalReviews}). */
		public final String suggestionStatus;
		/** {@code targetLibrary}, e.g. {@code library/Fasttrack/lib/phone}. */
		public final String library;
		/** {@code targetFunction}, e.g. {@code toE164}. */
		public final String function;
		/** {@code binding}: {@code {"parameter":"fieldName","other":{"literal":"x"}}}. */
		public final String binding;
		public final String rationale;
		/** The function's doc comment, or null when the library could not be read / carries none ({@link #docProblem} says which). */
		public final FunctionDoc doc;
		public final String docProblem;
		/** Never null. */
		public final Measurement measurement;

		public Computation(final String pSuggestionId, final String pSuggestionStatus, final String pLibrary, final String pFunction, final String pBinding,
			final String pRationale, final FunctionDoc pDoc, final String pDocProblem, final Measurement pMeasurement)
		{
			this.suggestionId = pSuggestionId == null ? "" : pSuggestionId;
			this.suggestionStatus = pSuggestionStatus == null ? "" : pSuggestionStatus;
			this.library = pLibrary == null ? "" : pLibrary;
			this.function = pFunction == null ? "" : pFunction;
			this.binding = pBinding == null ? "" : pBinding;
			this.rationale = pRationale == null ? "" : pRationale;
			this.doc = pDoc;
			this.docProblem = pDocProblem == null ? "" : pDocProblem;
			this.measurement = pMeasurement == null ? Measurement.notLoaded() : pMeasurement;
		}
	}

	/** What the staging table says about a computed field: per source, how many values were converted and how many came out empty. Never a number that was not read. */
	public static final class Measurement
	{
		/** The entity has not been loaded into the staging yet: nothing to read. */
		public static final String NOT_LOADED = "NOT_LOADED";
		/** Loaded, but the function is not applied to the staging model (the field is a plain, empty field): reading it would measure nothing. */
		public static final String NOT_APPLIED = "NOT_APPLIED";
		/** Loaded and applied, but the table could not be read (see {@link #problem}). */
		public static final String UNAVAILABLE = "UNAVAILABLE";
		/** Counts read. */
		public static final String MEASURED = "MEASURED";

		public final String state;
		public final String problem;
		/** How many rows per source the converted/empty counts were computed on (they read {@code sampled} of {@link SourceCount#filled}). */
		public final int cap;
		public final List<SourceCount> sources;

		private Measurement(final String pState, final String pProblem, final int pCap, final List<SourceCount> pSources)
		{
			this.state = pState;
			this.problem = pProblem == null ? "" : pProblem;
			this.cap = pCap;
			this.sources = pSources == null ? Collections.<SourceCount>emptyList() : pSources;
		}

		public static Measurement notLoaded()
		{
			return new Measurement(NOT_LOADED, "", 0, null);
		}

		public static Measurement notApplied()
		{
			return new Measurement(NOT_APPLIED, "", 0, null);
		}

		public static Measurement unavailable(final String pProblem)
		{
			return new Measurement(UNAVAILABLE, pProblem, 0, null);
		}

		public static Measurement measured(final int pCap, final List<SourceCount> pSources)
		{
			return new Measurement(MEASURED, "", pCap, pSources);
		}

		public boolean isMeasured()
		{
			return MEASURED.equals(this.state);
		}
	}

	/** One source system's numbers of a {@link Measurement}. */
	public static final class SourceCount
	{
		public final String sourceSystem;
		/** Staging rows written by this source. */
		public final long total;
		/** Of those, rows where the field the function reads is filled. */
		public final long filled;
		/** How many filled rows were evaluated (at most {@link Measurement#cap}, the first ones in key order). */
		public final int sampled;
		public final int converted;
		public final int empty;
		/** One real value and what the function made of it - null when no row of that kind was seen. */
		public final String convertedBefore;
		public final String convertedAfter;
		public final String emptyBefore;

		public SourceCount(final String pSourceSystem, final long pTotal, final long pFilled, final int pSampled, final int pConverted, final int pEmpty,
			final String pConvertedBefore, final String pConvertedAfter, final String pEmptyBefore)
		{
			this.sourceSystem = pSourceSystem == null ? "" : pSourceSystem;
			this.total = pTotal;
			this.filled = pFilled;
			this.sampled = pSampled;
			this.converted = pConverted;
			this.empty = pEmpty;
			this.convertedBefore = pConvertedBefore;
			this.convertedAfter = pConvertedAfter;
			this.emptyBefore = pEmptyBefore;
		}
	}

	/** One {@code AttributeMapping}-or-structural cell of the matrix: the mapping(s), if any, of one attribute in one feed. */
	public static final class Cell
	{
		public final String status;
		/** The {@code AttributeMapping} row id this cell decides - null for a structural ({@link #KEY}/{@link #ENGINE}) or empty ({@link #NONE}/{@link #MISSING}) cell. */
		public final String mappingId;
		public final String landingColumn;
		public final List<String> pipelineSteps;
		public final Integer confidence;
		public final String origin;
		public final String rationale;
		public final String reviewComment;
		/** How many OTHER mappings of the same attribute exist in the same feed, shown as "+N" - 0 when this is the only one. */
		public final int extraCount;
		/** Plain-language explanation for a {@link #KEY}/{@link #ENGINE}/{@link #NONE}/{@link #MISSING} cell - unused for a data status. */
		public final String explain;
		public final Sample sample;
		/** Staging-proposal matrix lot: {@code staging.AttentionRules#CONSTANT_COLUMN}/{@code #EMPTY_COLUMN}/{@code #DUPLICATE_FIELD}, or {@code ""} when this cell was not flagged (the common case) - never a database lookup, computed once by {@code ebx.MappingMatrixReader} from measured landing-column numbers. */
		public final String attentionFlag;
		/** Short, already-localized second-line text for {@link #attentionFlag} (e.g. "always the same value", "empty in this source (0 / 165)") - {@code ""} when {@link #attentionFlag} is empty. */
		public final String attentionReason;
		/** A PROPOSED mapping whose {@code AttributeMapping.alternativeTo} names this cell's own {@link #mappingId} - the "suggested fix" the inspector offers on a flagged cell. Null when none was proposed. */
		public final Alternative alternative;
		/** Codes transcoding lot H3 (2026-10-17): the code tables of this mapping's {@code TRANSCODE_REFERENCE} steps that carry their own ({@code quality.CodeMap}), shown as a two-column table with Accept / Edit / Reject; empty for almost every cell. */
		public final List<CodeList> codeLists;
		/** Validation desk lot L2: the landing FIELD this mapping reads ({@code LandingColumn.fieldName}, the key of the row document - {@link #landingColumn} is the label shown), "" when unresolved. */
		public final String landingField;
		/** Validation desk lot L2: {@code LandingColumn.fillRate}, a percentage 0-100, or null when the column was never profiled. */
		public final Double landingFillRate;
		/** Validation desk lot L2: the mapping's resolved pipeline as the loader runs it (not the display steps of {@link #pipelineSteps}, which add "()" to a bare name), "" when none. */
		public final String pipeline;

		public Cell(final String pStatus, final String pMappingId, final String pLandingColumn, final List<String> pPipelineSteps, final Integer pConfidence,
			final String pOrigin, final String pRationale, final String pReviewComment, final int pExtraCount, final String pExplain, final Sample pSample)
		{
			this(pStatus, pMappingId, pLandingColumn, pPipelineSteps, pConfidence, pOrigin, pRationale, pReviewComment, pExtraCount, pExplain, pSample, "", "", null);
		}

		public Cell(final String pStatus, final String pMappingId, final String pLandingColumn, final List<String> pPipelineSteps, final Integer pConfidence,
			final String pOrigin, final String pRationale, final String pReviewComment, final int pExtraCount, final String pExplain, final Sample pSample,
			final String pAttentionFlag, final String pAttentionReason, final Alternative pAlternative)
		{
			this(pStatus, pMappingId, pLandingColumn, pPipelineSteps, pConfidence, pOrigin, pRationale, pReviewComment, pExtraCount, pExplain, pSample, pAttentionFlag, pAttentionReason,
				pAlternative, null);
		}

		public Cell(final String pStatus, final String pMappingId, final String pLandingColumn, final List<String> pPipelineSteps, final Integer pConfidence,
			final String pOrigin, final String pRationale, final String pReviewComment, final int pExtraCount, final String pExplain, final Sample pSample,
			final String pAttentionFlag, final String pAttentionReason, final Alternative pAlternative, final List<CodeList> pCodeLists)
		{
			this(pStatus, pMappingId, pLandingColumn, pPipelineSteps, pConfidence, pOrigin, pRationale, pReviewComment, pExtraCount, pExplain, pSample, pAttentionFlag, pAttentionReason,
				pAlternative, pCodeLists, "", null, "");
		}

		/** Validation desk lot L2: the same cell with what the desk needs to run the mapping on a landing row ({@link #landingField}, {@link #landingFillRate}, {@link #pipeline}). */
		public Cell(final String pStatus, final String pMappingId, final String pLandingColumn, final List<String> pPipelineSteps, final Integer pConfidence,
			final String pOrigin, final String pRationale, final String pReviewComment, final int pExtraCount, final String pExplain, final Sample pSample,
			final String pAttentionFlag, final String pAttentionReason, final Alternative pAlternative, final List<CodeList> pCodeLists, final String pLandingField,
			final Double pLandingFillRate, final String pPipeline)
		{
			this.landingField = pLandingField == null ? "" : pLandingField;
			this.landingFillRate = pLandingFillRate;
			this.pipeline = pPipeline == null ? "" : pPipeline;
			this.codeLists = pCodeLists == null ? Collections.<CodeList>emptyList() : pCodeLists;
			this.status = pStatus;
			this.mappingId = pMappingId;
			this.landingColumn = pLandingColumn;
			this.pipelineSteps = pPipelineSteps == null ? Collections.<String>emptyList() : pPipelineSteps;
			this.confidence = pConfidence;
			this.origin = pOrigin;
			this.rationale = pRationale;
			this.reviewComment = pReviewComment;
			this.extraCount = pExtraCount;
			this.explain = pExplain;
			this.sample = pSample;
			this.attentionFlag = pAttentionFlag == null ? "" : pAttentionFlag;
			this.attentionReason = pAttentionReason == null ? "" : pAttentionReason;
			this.alternative = pAlternative;
		}

		public boolean isData()
		{
			// staging integration (2026-10-17, live): a link the build applied (APPLIED) is still a field link - the header read "0 field links" after a build
			return MatrixModel.ACCEPTED.equals(this.status) || MatrixModel.PROPOSED.equals(this.status) || MatrixModel.REJECTED.equals(this.status)
				|| "APPLIED".equals(this.status);
		}

		public boolean isFlagged()
		{
			return !this.attentionFlag.isEmpty();
		}
	}

	/** Codes transcoding lot H3: one code table of a mapping, as the inspector shows it (the observed codes and their rows come from the step's own argument, nothing is read from the landing). */
	public static final class CodeList
	{
		/** The {@code MappingStep} row id the Accept / Edit / Reject buttons decide. */
		public final String stepId;
		public final String status;
		public final Integer confidence;
		/** The {@code ReferenceBinding} kind the targets come from, or "". */
		public final String basedOn;
		/** The common list every source is mapped onto. */
		public final List<String> targets;
		public final List<CodeRow> rows;
		/** Rows the table was proposed on, and of those the rows with no target yet. */
		public final long total;
		public final long unresolved;
		/** Staging last points (2026-10-17): the observed codes that have rows and no target - while there is one, the table cannot be accepted (ebx.ProposalReviews refuses it, the card disables Accept). */
		public final List<String> missing;

		public CodeList(final String pStepId, final String pStatus, final Integer pConfidence, final String pBasedOn, final List<String> pTargets, final List<CodeRow> pRows,
			final long pTotal, final long pUnresolved)
		{
			this(pStepId, pStatus, pConfidence, pBasedOn, pTargets, pRows, pTotal, pUnresolved, null);
		}

		/** @param pMissing {@code CodeMap#codesWithoutTarget}; null derives it from the rows (a code with no target and a count other than 0) */
		public CodeList(final String pStepId, final String pStatus, final Integer pConfidence, final String pBasedOn, final List<String> pTargets, final List<CodeRow> pRows,
			final long pTotal, final long pUnresolved, final List<String> pMissing)
		{
			final List<String> missing = new java.util.ArrayList<String>();
			if (pMissing != null)
			{
				missing.addAll(pMissing);
			}
			else if (pRows != null)
			{
				for (final CodeRow row : pRows)
				{
					if (row.target.trim().isEmpty() && row.count != 0L)
					{
						missing.add(row.code);
					}
				}
			}
			this.missing = Collections.unmodifiableList(missing);
			this.stepId = pStepId;
			this.status = pStatus;
			this.confidence = pConfidence;
			this.basedOn = pBasedOn == null ? "" : pBasedOn;
			this.targets = pTargets == null ? Collections.<String>emptyList() : pTargets;
			this.rows = pRows == null ? Collections.<CodeRow>emptyList() : pRows;
			this.total = pTotal;
			this.unresolved = pUnresolved;
		}

		/** Whether the buttons may still change it: only a proposal nobody decided. */
		public boolean isOpen()
		{
			return PROPOSED.equals(this.status);
		}

		/** True when Accept must stay disabled: an observed code with rows has no target code. Edit and Reject stay. */
		public boolean acceptBlocked()
		{
			return !this.missing.isEmpty();
		}
	}

	/** One line of a {@link CodeList}: a code as observed, its target (empty when none yet) and the rows that had it. */
	public static final class CodeRow
	{
		public final String code;
		public final String target;
		public final long count;

		public CodeRow(final String pCode, final String pTarget, final long pCount)
		{
			this.code = pCode;
			this.target = pTarget == null ? "" : pTarget;
			this.count = pCount;
		}
	}

	/** The "suggested fix" of a flagged cell - {@link Cell#alternative}. */
	public static final class Alternative
	{
		public final String mappingId;
		public final String landingColumn;
		public final List<String> pipelineSteps;
		public final Sample sample;
		public final String rationale;

		public Alternative(final String pMappingId, final String pLandingColumn, final List<String> pPipelineSteps, final Sample pSample, final String pRationale)
		{
			this.mappingId = pMappingId;
			this.landingColumn = pLandingColumn;
			this.pipelineSteps = pPipelineSteps == null ? Collections.<String>emptyList() : pPipelineSteps;
			this.sample = pSample;
			this.rationale = pRationale == null ? "" : pRationale;
		}
	}

	/** One "before -&gt; after" sample, or the reason none could be computed - see {@code ebx.MappingMatrixReader#sample}. */
	public static final class Sample
	{
		public final String before;
		public final String after;
		public final String unavailableReason;
		/** Caveat on an otherwise available sample - e.g. "cleaning steps skipped: needs a reference lookup this screen cannot provide" (coordinator review, 2026-09-23) when a cleaning step could not be honestly applied, so {@link #after} reads "before cleaning" rather than silently pretending it ran. Empty (never null) when there is nothing to caveat. */
		public final String note;
		/** How many filled source values the sample ran the pipeline on (0 when unavailable) - with {@link #emptied}, the input of {@code staging.AttentionRules#emptiedByRule}. */
		public final int filled;
		/** How many of those {@link #filled} values came out empty. */
		public final int emptied;
		/** Whether the sampled source values are multi-token values whose casing does not tell the parts apart ({@code staging.TokenCase#orderUnknown}) - false when not measured. */
		public final boolean orderUnknown;
		/** Of the {@link #multiTokenFilled} sampled multi-token values, how many the pipeline turned into a strict part of themselves ({@code staging.PartOrder#splitCounts}). */
		public final int splitParts;
		/** Sampled filled values of two tokens or more. */
		public final int multiTokenFilled;
		/** Staging normalisation lot D: what the chain made of each sampled filled value (empty ones kept as null), for {@code TransformVerifier#invalidOutputs}; empty when not collected. */
		public final List<String> afterValues;

		public static Sample of(final String pBefore, final String pAfter)
		{
			return new Sample(pBefore, pAfter, null, "", 1, pAfter == null || pAfter.isEmpty() ? 1 : 0);
		}

		public static Sample of(final String pBefore, final String pAfter, final String pNote)
		{
			return new Sample(pBefore, pAfter, null, pNote, 1, pAfter == null || pAfter.isEmpty() ? 1 : 0);
		}

		/** The first sampled value shown, plus the counts over every filled value the sample evaluated. */
		public static Sample counted(final String pBefore, final String pAfter, final String pNote, final int pFilled, final int pEmptied)
		{
			return new Sample(pBefore, pAfter, null, pNote, pFilled, pEmptied);
		}

		/** Same, with the order measurement ({@code staging.AttentionRules#orderUnknownSplit}'s inputs). */
		public static Sample counted(final String pBefore, final String pAfter, final String pNote, final int pFilled, final int pEmptied,
			final boolean pOrderUnknown, final int pSplitParts, final int pMultiTokenFilled)
		{
			return new Sample(pBefore, pAfter, null, pNote, pFilled, pEmptied, pOrderUnknown, pSplitParts, pMultiTokenFilled);
		}

		/** Same, with the outputs of every sampled value (lot D: the typed-conversion check of the Mapping matrix). */
		public static Sample counted(final String pBefore, final String pAfter, final String pNote, final int pFilled, final int pEmptied,
			final boolean pOrderUnknown, final int pSplitParts, final int pMultiTokenFilled, final List<String> pAfterValues)
		{
			return new Sample(pBefore, pAfter, null, pNote, pFilled, pEmptied, pOrderUnknown, pSplitParts, pMultiTokenFilled, pAfterValues);
		}

		public static Sample unavailable(final String pReason)
		{
			return new Sample(null, null, pReason, "", 0, 0);
		}

		private Sample(final String pBefore, final String pAfter, final String pUnavailableReason, final String pNote, final int pFilled, final int pEmptied)
		{
			this(pBefore, pAfter, pUnavailableReason, pNote, pFilled, pEmptied, false, 0, 0);
		}

		private Sample(final String pBefore, final String pAfter, final String pUnavailableReason, final String pNote, final int pFilled, final int pEmptied,
			final boolean pOrderUnknown, final int pSplitParts, final int pMultiTokenFilled)
		{
			this(pBefore, pAfter, pUnavailableReason, pNote, pFilled, pEmptied, pOrderUnknown, pSplitParts, pMultiTokenFilled, null);
		}

		private Sample(final String pBefore, final String pAfter, final String pUnavailableReason, final String pNote, final int pFilled, final int pEmptied,
			final boolean pOrderUnknown, final int pSplitParts, final int pMultiTokenFilled, final List<String> pAfterValues)
		{
			this.afterValues = pAfterValues == null ? Collections.<String>emptyList() : pAfterValues;
			this.orderUnknown = pOrderUnknown;
			this.splitParts = pSplitParts;
			this.multiTokenFilled = pMultiTokenFilled;
			this.before = pBefore;
			this.after = pAfter;
			this.unavailableReason = pUnavailableReason;
			this.note = pNote == null ? "" : pNote;
			this.filled = pFilled;
			this.emptied = pEmptied;
		}

		public boolean isAvailable()
		{
			return this.unavailableReason == null;
		}
	}

	/** One {@code TransferFeed} of the active entity: one column of the matrix, one curve of the overview. */
	public static final class Feed
	{
		public final String id;
		public final String sourceId;
		public final String landingTableId;
		public final String landingTableName;
		public final String status;
		/** Null for a root feed. */
		public final String parentFeedId;
		/** How this feed reaches the entity, for a reader unfamiliar with the tree - blank for the common "one feed per source" case where nothing needs explaining. */
		public final String arrivalMode;
		/** True when this feed is a child feed reached through an ordinary foreign key resolved by source key (a separate landing table), rather than a genuine parent/child feed pair - drawn dashed violet in the overview, docs/MAPPING-MATRIX.md. */
		public final boolean keyLinked;
		public final List<String> unusedColumns;
		/** Every cell of this feed, keyed by attribute id - an attribute with no entry reads {@link MatrixModel#NONE}/{@link MatrixModel#MISSING} (computed by {@link MatrixCompute}, never stored here). */
		public final java.util.Map<String, Cell> cellsByAttributeId;
		public final Integer confidence;
		/** {@code TransferFeed.reviewComment} - why the feed was rejected, entered from this screen's own header Reject action; shown back in the header tooltip and made available to the inspector. */
		public final String reviewComment;
		/** {@code core.FeedDesignerLink#url} on this feed - set only for a ROOT feed ({@link #parentFeedId} empty), the only kind that link accepts; empty for a child feed (fix/mapping-matrix-live, 2026-09-24: "mark the matrix's own full-page links ... as a voluntary departure"). Computed in {@code ebx.MappingMatrixReader}, never here - this class stays a plain data holder. */
		public final String designerUrl;
		/** Validation desk lot L2: {@code TransferFeed.collectionPath} - "" for the ordinary "one landing row, one record" feed; a feed with one reads a sub-element of the row, which the desk's preview does not resolve. */
		public final String collectionPath;
		/** Validation desk lot L2: where the feed's landing rows live (the project's landing dataspace, the source's landing dataset) - "" when unresolved. */
		public final String landingDataspace;
		public final String landingDataset;
		/** Validation desk lot L2: {@code LandingRowCounts.rowCountOf} of the landing table, null when unknown. */
		public final Long rowCount;

		public Feed(final String pId, final String pSourceId, final String pLandingTableId, final String pLandingTableName, final String pStatus,
			final String pParentFeedId, final String pArrivalMode, final boolean pKeyLinked, final List<String> pUnusedColumns,
			final java.util.Map<String, Cell> pCellsByAttributeId)
		{
			this(pId, pSourceId, pLandingTableId, pLandingTableName, pStatus, pParentFeedId, pArrivalMode, pKeyLinked, pUnusedColumns, pCellsByAttributeId, null, "");
		}

		public Feed(final String pId, final String pSourceId, final String pLandingTableId, final String pLandingTableName, final String pStatus,
			final String pParentFeedId, final String pArrivalMode, final boolean pKeyLinked, final List<String> pUnusedColumns,
			final java.util.Map<String, Cell> pCellsByAttributeId, final Integer pConfidence, final String pReviewComment)
		{
			this(pId, pSourceId, pLandingTableId, pLandingTableName, pStatus, pParentFeedId, pArrivalMode, pKeyLinked, pUnusedColumns, pCellsByAttributeId, pConfidence,
				pReviewComment, "");
		}

		public Feed(final String pId, final String pSourceId, final String pLandingTableId, final String pLandingTableName, final String pStatus,
			final String pParentFeedId, final String pArrivalMode, final boolean pKeyLinked, final List<String> pUnusedColumns,
			final java.util.Map<String, Cell> pCellsByAttributeId, final Integer pConfidence, final String pReviewComment, final String pDesignerUrl)
		{
			this(pId, pSourceId, pLandingTableId, pLandingTableName, pStatus, pParentFeedId, pArrivalMode, pKeyLinked, pUnusedColumns, pCellsByAttributeId, pConfidence, pReviewComment,
				pDesignerUrl, "", "", "", null);
		}

		/** Validation desk lot L2: the same feed with where its landing rows live ({@link #collectionPath}, {@link #landingDataspace}, {@link #landingDataset}, {@link #rowCount}). */
		public Feed(final String pId, final String pSourceId, final String pLandingTableId, final String pLandingTableName, final String pStatus,
			final String pParentFeedId, final String pArrivalMode, final boolean pKeyLinked, final List<String> pUnusedColumns,
			final java.util.Map<String, Cell> pCellsByAttributeId, final Integer pConfidence, final String pReviewComment, final String pDesignerUrl,
			final String pCollectionPath, final String pLandingDataspace, final String pLandingDataset, final Long pRowCount)
		{
			this.collectionPath = pCollectionPath == null ? "" : pCollectionPath;
			this.landingDataspace = pLandingDataspace == null ? "" : pLandingDataspace;
			this.landingDataset = pLandingDataset == null ? "" : pLandingDataset;
			this.rowCount = pRowCount;
			this.id = pId;
			this.sourceId = pSourceId;
			this.landingTableId = pLandingTableId;
			this.landingTableName = pLandingTableName;
			this.status = pStatus;
			this.parentFeedId = pParentFeedId;
			this.arrivalMode = pArrivalMode == null ? "" : pArrivalMode;
			this.keyLinked = pKeyLinked;
			this.unusedColumns = pUnusedColumns == null ? Collections.<String>emptyList() : pUnusedColumns;
			this.cellsByAttributeId = pCellsByAttributeId == null ? Collections.<String, Cell>emptyMap() : pCellsByAttributeId;
			this.confidence = pConfidence;
			this.reviewComment = pReviewComment == null ? "" : pReviewComment;
			this.designerUrl = pDesignerUrl == null ? "" : pDesignerUrl;
		}
	}

	/** One staging entity: one tab, one card of the overview. */
	public static final class Entity
	{
		public final String id;
		public final String name;
		public final List<Attr> attrs;
		public final List<Feed> feeds;
		/** The {@code StagingEntity} row's own {@code status} - decided in the toolbar; every attribute/feed/mapping accept ultimately depends on this being accepted first (see {@code review.ReviewRules#canAcceptStagingAttribute}). */
		public final String status;
		public final Integer confidence;
		/** Staging-proposal matrix lot: every guard with no single attribute of its own ({@code ebx.GuardReader.ENTITY_LEVEL}) - shown as a small line under the object's header. Never null. */
		public final List<Guard> entityGuards;

		public Entity(final String pId, final String pName, final List<Attr> pAttrs, final List<Feed> pFeeds)
		{
			this(pId, pName, pAttrs, pFeeds, "", null);
		}

		public Entity(final String pId, final String pName, final List<Attr> pAttrs, final List<Feed> pFeeds, final String pStatus, final Integer pConfidence)
		{
			this(pId, pName, pAttrs, pFeeds, pStatus, pConfidence, null);
		}

		public Entity(final String pId, final String pName, final List<Attr> pAttrs, final List<Feed> pFeeds, final String pStatus, final Integer pConfidence,
			final List<Guard> pEntityGuards)
		{
			this.id = pId;
			this.name = pName;
			this.attrs = pAttrs == null ? Collections.<Attr>emptyList() : pAttrs;
			this.feeds = pFeeds == null ? Collections.<Feed>emptyList() : pFeeds;
			this.status = pStatus == null ? "" : pStatus;
			this.confidence = pConfidence;
			this.entityGuards = pEntityGuards == null ? Collections.<Guard>emptyList() : pEntityGuards;
		}
	}

	/** The whole project, as read by {@code ebx.MappingMatrixReader#read}. */
	public static final class Project
	{
		public final String projectLabel;
		public final List<Source> sources;
		public final List<Entity> entities;

		public Project(final String pProjectLabel, final List<Source> pSources, final List<Entity> pEntities)
		{
			this.projectLabel = pProjectLabel;
			this.sources = pSources == null ? Collections.<Source>emptyList() : pSources;
			this.entities = pEntities == null ? new ArrayList<Entity>() : pEntities;
		}

		public Entity entityById(final String pId)
		{
			for (final Entity entity : this.entities)
			{
				if (entity.id.equals(pId))
				{
					return entity;
				}
			}
			return null;
		}
	}
}
