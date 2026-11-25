package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Staging preflight lot H1 (2026-10-17): what {@link PreflightCheck} found on ONE accepted link - its identity (entity, attribute, source, column, the chain it runs), the issues
 * (each with its rows, blocking or not) and, when the measured profile has one, the fix that converts every sampled value. Counts, format names and pipeline texts only: never a
 * value, so the report can be stored, served by REST and shown to anyone who sees the project. Pure; JSON round trip for the stored report.
 */
public final class PreflightFinding
{
	/** Rows EBX would refuse at the load: the chain fails on them or leaves a value the typed field cannot store (BLOCKING). */
	public static final String CONVERSION = "CONVERSION";
	/** Rows the chain turns into nothing although the source had a value, on a typed field: the value is lost, the conversion did not work (BLOCKING). */
	public static final String LOST = "LOST";
	/** Rows whose value is longer than the field allows (BLOCKING). */
	public static final String TOO_LONG = "TOO_LONG";
	/** Rows of a phone-like column that lose their leading {@code +} (warning: the number is kept, its country is not). */
	public static final String LOST_PLUS = "LOST_PLUS";
	/** Rows left empty on a mandatory attribute: the loader refuses them (BLOCKING). No pipeline fix: map a column or make the attribute optional. */
	public static final String MANDATORY_EMPTY = "MANDATORY_EMPTY";
	/** Rows repeating the value of the entity's only business key inside this source (warning). */
	public static final String DUPLICATES = "DUPLICATES";
	/** The link could not be replayed here (it reads another column, a live lookup, the clock; or reads a collection): nothing is claimed (information). */
	public static final String NOT_CHECKED = "NOT_CHECKED";
	/**
	 * Lot M7 (2026-10-18): an ACCEPTED entity that no accepted mapping feeds - the build creates its table and the load writes nothing into it (warning, never
	 * blocking: the rest of the build is sound). An entity-level finding: {@link #attributeName}, {@link #column} and {@link #mappingId} are empty.
	 */
	public static final String NO_FEED = "NO_FEED";
	/**
	 * Lot M15 (2026-10-18): an attribute fed by the OTHER sources of its entity but not by this one, although a filled landing column of this source (read by no
	 * mapping at all) holds the same kind of values as the columns that feed it (a warning, never blocking). {@link #sourceSystem}, {@link #tableName} and {@link
	 * #column} name the unmapped column, {@link #fedBy} the sources that do feed the attribute; the issue carries the filled rows and the table's rows.
	 */
	public static final String NOT_FED_BY_SOURCE = "NOT_FED_BY_SOURCE";
	/**
	 * B2B test (2026-10-23): values this link gives an attribute that do not have the identifier shape the OTHER links of the attribute give it in most of their values (SAP's
	 * tax number column fed {@code Organisation.siren} with 125 SIRENs {@code 9(9)} and 25 VAT numbers; Salesforce and the website gave {@code 9(9)} only). A warning, never
	 * blocking; {@link #expectedShape} is that shape, {@link #fedBy} the other sources; the issue carries the rows and the link's rows. Shapes only, never a value.
	 */
	public static final String SHAPE_OUTLIERS = "SHAPE_OUTLIERS";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One thing found on a link. */
	public static final class Issue
	{
		public final String kind;
		public final boolean blocking;
		/** The rows concerned. */
		public final long rows;
		/** The rows looked at (the filled rows of the column), 0 when not meaningful. */
		public final long ofRows;
		/** The figures come from a sample of the column: the real count is at least this. */
		public final boolean atLeast;
		/** One English sentence of what the replay found, pipelines and counts only (the viewer's wording is built from the structured fields). */
		public final String detail;

		public Issue(final String pKind, final boolean pBlocking, final long pRows, final long pOfRows, final boolean pAtLeast, final String pDetail)
		{
			this.kind = pKind;
			this.blocking = pBlocking;
			this.rows = pRows;
			this.ofRows = pOfRows;
			this.atLeast = pAtLeast;
			this.detail = pDetail == null ? "" : pDetail;
		}

		ObjectNode toJson()
		{
			final ObjectNode out = PreflightFinding.MAPPER.createObjectNode();
			out.put("kind", this.kind);
			out.put("blocking", this.blocking);
			out.put("rows", this.rows);
			out.put("of", this.ofRows);
			out.put("atLeast", this.atLeast);
			out.put("detail", this.detail);
			return out;
		}

		static Issue fromJson(final JsonNode pNode)
		{
			return new Issue(pNode.path("kind").asText(""), pNode.path("blocking").asBoolean(false), pNode.path("rows").asLong(0L), pNode.path("of").asLong(0L),
				pNode.path("atLeast").asBoolean(false), pNode.path("detail").asText(""));
		}
	}

	public final String mappingId;
	public final String entityName;
	public final String attributeName;
	public final String sourceSystem;
	public final String tableName;
	public final String column;
	public final String dataType;
	/** The chain the loader runs, as text ({@code ""} = none). */
	public final String pipeline;
	/** How the column's dates or numbers are written ({@code dd/MM/yyyy}, {@code decimal comma}), {@code ""} when it is neither. */
	public final String written;
	/** {@code date} / {@code number} / {@code ""}. */
	public final String writtenKind;
	public final List<Issue> issues = new ArrayList<Issue>();
	/** The pipeline that converts every sampled value, or null when the profile has none. */
	public String fixPipeline;
	/** Rows the fix converts (all the sampled rows of the link). */
	public long fixRows;
	/** A candidate exists but rests on a day / month order no value proves: never applied, the person decides. */
	public String ambiguousPipeline;
	/** {@link #NOT_FED_BY_SOURCE}: the source systems that do feed the attribute; {@link #SHAPE_OUTLIERS}: the other sources of the attribute (empty for every other kind). */
	public final List<String> fedBy = new ArrayList<String>();
	/** {@link #SHAPE_OUTLIERS}: the shape the other links give the attribute ({@code 9(9)}); empty otherwise. */
	public String expectedShape = "";
	/**
	 * B2B test (2026-10-23): identifier shape ({@code quality.Shapes}) of the values the replay gave the attribute, to rows ({@link #OTHER_SHAPE} for a value that is not
	 * identifier-like). Filled by {@link PreflightCheck#check}, read by {@link PreflightCheck#shapeOutliers}; never stored.
	 */
	public final java.util.Map<String, Long> outputShapes = new java.util.LinkedHashMap<String, Long>();
	/** The key of {@link #outputShapes} for a value that is not identifier-like (a name, a phone, a date). */
	public static final String OTHER_SHAPE = "*";
	/** The replayed values were a sample of the column (counts derived from {@link #outputShapes} are lower bounds); never stored. */
	public boolean sampledValues;

	public PreflightFinding(final String pMappingId, final String pEntityName, final String pAttributeName, final String pSourceSystem, final String pTableName,
		final String pColumn, final String pDataType, final String pPipeline, final String pWritten, final String pWrittenKind)
	{
		this.mappingId = pMappingId;
		this.entityName = pEntityName;
		this.attributeName = pAttributeName;
		this.sourceSystem = pSourceSystem;
		this.tableName = pTableName;
		this.column = pColumn;
		this.dataType = pDataType;
		this.pipeline = pPipeline == null ? "" : pPipeline;
		this.written = pWritten == null ? "" : pWritten;
		this.writtenKind = pWrittenKind == null ? "" : pWrittenKind;
	}

	/** True when at least one issue blocks the build. */
	public boolean blocking()
	{
		for (final Issue issue : this.issues)
		{
			if (issue.blocking)
			{
				return true;
			}
		}
		return false;
	}

	/** Rows the build would refuse or empty on this link (the sum of the blocking issues). */
	public long blockingRows()
	{
		long total = 0L;
		for (final Issue issue : this.issues)
		{
			if (issue.blocking)
			{
				total += issue.rows;
			}
		}
		return total;
	}

	/** The issue of {@code pKind}, or null. */
	public Issue issue(final String pKind)
	{
		for (final Issue issue : this.issues)
		{
			if (issue.kind.equals(pKind))
			{
				return issue;
			}
		}
		return null;
	}

	/** True when the finding is only the information that the link was not replayed. */
	public boolean onlyNotChecked()
	{
		return this.issues.size() == 1 && NOT_CHECKED.equals(this.issues.get(0).kind);
	}

	/** True when a blocking issue is one a pipeline fix answers (the conversion fails, the value is lost): what "Apply the fix" is offered for. */
	public boolean fixableBlocking()
	{
		return this.fixable() && ((this.issue(CONVERSION) != null && this.issue(CONVERSION).blocking) || (this.issue(LOST) != null && this.issue(LOST).blocking));
	}

	/** True when the fix can be applied in one click. */
	public boolean fixable()
	{
		return this.fixPipeline != null && !this.fixPipeline.isEmpty() && !this.mappingId.isEmpty();
	}

	ObjectNode toJson()
	{
		final ObjectNode out = PreflightFinding.MAPPER.createObjectNode();
		out.put("mapping", this.mappingId);
		out.put("entity", this.entityName);
		out.put("attribute", this.attributeName);
		out.put("source", this.sourceSystem);
		out.put("table", this.tableName);
		out.put("column", this.column);
		out.put("dataType", this.dataType);
		out.put("pipeline", this.pipeline);
		out.put("written", this.written);
		out.put("writtenKind", this.writtenKind);
		final ArrayNode issuesNode = out.putArray("issues");
		for (final Issue issue : this.issues)
		{
			issuesNode.add(issue.toJson());
		}
		if (this.fixPipeline != null)
		{
			final ObjectNode fix = out.putObject("fix");
			fix.put("pipeline", this.fixPipeline);
			fix.put("rows", this.fixRows);
		}
		if (this.ambiguousPipeline != null)
		{
			out.put("ambiguous", this.ambiguousPipeline);
		}
		if (!this.expectedShape.isEmpty())
		{
			out.put("expectedShape", this.expectedShape);
		}
		if (!this.fedBy.isEmpty())
		{
			final ArrayNode fedByNode = out.putArray("fedBy");
			for (final String source : this.fedBy)
			{
				fedByNode.add(source);
			}
		}
		return out;
	}

	static PreflightFinding fromJson(final JsonNode pNode)
	{
		final PreflightFinding finding = new PreflightFinding(pNode.path("mapping").asText(""), pNode.path("entity").asText(""), pNode.path("attribute").asText(""),
			pNode.path("source").asText(""), pNode.path("table").asText(""), pNode.path("column").asText(""), pNode.path("dataType").asText("STRING"),
			pNode.path("pipeline").asText(""), pNode.path("written").asText(""), pNode.path("writtenKind").asText(""));
		for (final JsonNode issue : pNode.path("issues"))
		{
			finding.issues.add(Issue.fromJson(issue));
		}
		if (pNode.has("fix"))
		{
			finding.fixPipeline = pNode.path("fix").path("pipeline").asText(null);
			finding.fixRows = pNode.path("fix").path("rows").asLong(0L);
		}
		if (pNode.has("ambiguous"))
		{
			finding.ambiguousPipeline = pNode.path("ambiguous").asText(null);
		}
		for (final JsonNode source : pNode.path("fedBy"))
		{
			finding.fedBy.add(source.asText(""));
		}
		finding.expectedShape = pNode.path("expectedShape").asText("");
		return finding;
	}
}
