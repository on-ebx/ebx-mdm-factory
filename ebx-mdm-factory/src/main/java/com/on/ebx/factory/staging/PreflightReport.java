package com.on.ebx.factory.staging;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Staging preflight lot H1 (2026-10-17): the report of the check before "Build and load the staging" - one {@link PreflightFinding} per accepted link that has something to say,
 * the accepted script derivations still waiting for a parameter, and the decision it drives: <b>a blocking finding (rows the build would refuse) stops the build</b> unless a person
 * chose "Build anyway"; warnings (an empty mandatory attribute, a repeated business key, a derivation waiting for its parameter, a lost {@code +}) never block.
 * <p>
 * Stored as the {@code detail} JSON of a {@code PipelineStep} named {@link #STEP} (no data-model change) and served as is by {@code GET /projects/{code}/staging/preflight}. Counts,
 * format names and pipeline texts only. Pure; JSON round trip.
 */
public final class PreflightReport
{
	/** The {@code PipelineStep.step} the last report is stored under (a free text field: not one of the enumerated values, like {@code STAGING_LOAD_RUN}). */
	public static final String STEP = "STAGING_PREFLIGHT";
	public static final int VERSION = 1;
	/** At most this many links with findings are kept in a report. */
	public static final int MAX_FINDINGS = 120;

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** An accepted script derivation that still waits for a parameter only a person can give: its attribute stays empty until then. */
	public static final class Waiting
	{
		public final String entityName;
		public final String attributeName;
		public final List<String> parameters;

		public Waiting(final String pEntityName, final String pAttributeName, final List<String> pParameters)
		{
			this.entityName = pEntityName == null ? "" : pEntityName;
			this.attributeName = pAttributeName == null ? "" : pAttributeName;
			this.parameters = pParameters == null ? new ArrayList<String>() : new ArrayList<String>(pParameters);
		}
	}

	public final String projectCode;
	/** When the check ran, epoch milliseconds. */
	public final long at;
	/** Links replayed on their landing values. */
	public final int linksChecked;
	/** Links that could not be replayed (their chain reads another column, a lookup, the clock; or they belong to a collection feed). */
	public final int linksNotChecked;
	/** The links that have something to say, blocking ones first. */
	public final List<PreflightFinding> findings;
	public final List<Waiting> derivationsWaiting;
	/** English notes on what could not be read (a landing column that did not answer); never a value. */
	public final List<String> notes;

	public PreflightReport(final String pProjectCode, final long pAt, final int pLinksChecked, final int pLinksNotChecked, final List<PreflightFinding> pFindings,
		final List<Waiting> pDerivationsWaiting, final List<String> pNotes)
	{
		this.projectCode = pProjectCode == null ? "" : pProjectCode;
		this.at = pAt;
		this.linksChecked = pLinksChecked;
		this.linksNotChecked = pLinksNotChecked;
		this.findings = pFindings == null ? new ArrayList<PreflightFinding>() : pFindings;
		this.derivationsWaiting = pDerivationsWaiting == null ? new ArrayList<Waiting>() : pDerivationsWaiting;
		this.notes = pNotes == null ? new ArrayList<String>() : pNotes;
	}

	/**
	 * The report of a check: {@code pAll} is the finding of every link replayed or not; the links that replayed clean and the ones not replayed are only counted, the others kept
	 * (blocking links first, then by entity and attribute).
	 */
	public static PreflightReport of(final String pProjectCode, final long pAt, final List<PreflightFinding> pAll, final List<Waiting> pDerivationsWaiting,
		final List<String> pNotes)
	{
		int checked = 0;
		int notChecked = 0;
		final List<PreflightFinding> kept = new ArrayList<PreflightFinding>();
		for (final PreflightFinding finding : pAll == null ? Collections.<PreflightFinding>emptyList() : pAll)
		{
			if (finding.onlyNotChecked())
			{
				notChecked++;
				continue;
			}
			checked++;
			if (!finding.issues.isEmpty())
			{
				kept.add(finding);
			}
		}
		Collections.sort(kept, new java.util.Comparator<PreflightFinding>()
		{
			@Override
			public int compare(final PreflightFinding pLeft, final PreflightFinding pRight)
			{
				if (pLeft.blocking() != pRight.blocking())
				{
					return pLeft.blocking() ? -1 : 1;
				}
				final int entity = pLeft.entityName.compareToIgnoreCase(pRight.entityName);
				return entity != 0 ? entity : pLeft.attributeName.compareToIgnoreCase(pRight.attributeName);
			}
		});
		final List<String> notes = pNotes == null ? new ArrayList<String>() : new ArrayList<String>(pNotes);
		List<PreflightFinding> shown = kept;
		if (kept.size() > PreflightReport.MAX_FINDINGS)
		{
			// the stored detail is bounded (TextLimits.DETAIL_MAX_LENGTH): the blocking links come first, the rest is counted
			shown = new ArrayList<PreflightFinding>(kept.subList(0, PreflightReport.MAX_FINDINGS));
			notes.add((kept.size() - PreflightReport.MAX_FINDINGS) + " more link(s) with findings are not listed");
		}
		return new PreflightReport(pProjectCode, pAt, checked, notChecked, shown, pDerivationsWaiting, notes);
	}

	// ----------------------------------------------------------------------------------------------------- decisions

	/** True when at least one link has rows the build would refuse: the build is stopped (unless a person chose "Build anyway"). */
	public boolean blocking()
	{
		return this.blockingLinks() > 0;
	}

	/** Rows the build would refuse or empty, summed over the links (a lower bound when a link was sampled). */
	public long blockingRows()
	{
		long total = 0L;
		for (final PreflightFinding finding : this.findings)
		{
			total += finding.blockingRows();
		}
		return total;
	}

	public int blockingLinks()
	{
		int total = 0;
		for (final PreflightFinding finding : this.findings)
		{
			if (finding.blocking())
			{
				total++;
			}
		}
		return total;
	}

	/** Warnings: the non-blocking issues of the links, plus one per derivation waiting for a parameter. */
	public int warnings()
	{
		int total = this.derivationsWaiting.size();
		for (final PreflightFinding finding : this.findings)
		{
			for (final PreflightFinding.Issue issue : finding.issues)
			{
				if (!issue.blocking && !PreflightFinding.NOT_CHECKED.equals(issue.kind))
				{
					total++;
				}
			}
		}
		return total;
	}

	/** True when nothing was found at all. */
	public boolean clean()
	{
		return this.findings.isEmpty() && this.derivationsWaiting.isEmpty();
	}

	/** The links that carry a one-click fix and have blocking rows (what "Apply every fix" writes). */
	public List<PreflightFinding> fixable()
	{
		final List<PreflightFinding> out = new ArrayList<PreflightFinding>();
		for (final PreflightFinding finding : this.findings)
		{
			if (finding.fixableBlocking())
			{
				out.add(finding);
			}
		}
		return out;
	}

	/** The finding of a mapping, or null. */
	public PreflightFinding byMapping(final String pMappingId)
	{
		for (final PreflightFinding finding : this.findings)
		{
			if (finding.mappingId.equals(pMappingId))
			{
				return finding;
			}
		}
		return null;
	}

	/** The findings grouped by entity name, in report order. */
	public Map<String, List<PreflightFinding>> byEntity()
	{
		final Map<String, List<PreflightFinding>> out = new LinkedHashMap<String, List<PreflightFinding>>();
		for (final PreflightFinding finding : this.findings)
		{
			List<PreflightFinding> list = out.get(finding.entityName);
			if (list == null)
			{
				list = new ArrayList<PreflightFinding>();
				out.put(finding.entityName, list);
			}
			list.add(finding);
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------- JSON

	public ObjectNode toJson()
	{
		final ObjectNode out = PreflightReport.MAPPER.createObjectNode();
		out.put("version", PreflightReport.VERSION);
		out.put("project", this.projectCode);
		out.put("at", this.at);
		out.put("linksChecked", this.linksChecked);
		out.put("linksNotChecked", this.linksNotChecked);
		out.put("blocking", this.blocking());
		out.put("blockingRows", this.blockingRows());
		out.put("blockingLinks", this.blockingLinks());
		out.put("warnings", this.warnings());
		final ArrayNode findingsNode = out.putArray("findings");
		for (final PreflightFinding finding : this.findings)
		{
			findingsNode.add(finding.toJson());
		}
		final ArrayNode waitingNode = out.putArray("derivationsWaiting");
		for (final Waiting waiting : this.derivationsWaiting)
		{
			final ObjectNode node = waitingNode.addObject();
			node.put("entity", waiting.entityName);
			node.put("attribute", waiting.attributeName);
			final ArrayNode parameters = node.putArray("parameters");
			for (final String parameter : waiting.parameters)
			{
				parameters.add(parameter);
			}
		}
		final ArrayNode notesNode = out.putArray("notes");
		for (final String note : this.notes)
		{
			notesNode.add(note);
		}
		return out;
	}

	/** The report a stored JSON holds, or null when the text is not one (empty, another shape, unreadable). */
	public static PreflightReport fromJson(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			return null;
		}
		final JsonNode root;
		try
		{
			root = PreflightReport.MAPPER.readTree(pJson);
		}
		catch (final IOException ex)
		{
			return null;
		}
		if (root == null || !root.isObject() || !root.has("findings") || !root.has("at"))
		{
			return null;
		}
		final List<PreflightFinding> findings = new ArrayList<PreflightFinding>();
		for (final JsonNode node : root.path("findings"))
		{
			findings.add(PreflightFinding.fromJson(node));
		}
		final List<Waiting> waiting = new ArrayList<Waiting>();
		for (final JsonNode node : root.path("derivationsWaiting"))
		{
			final List<String> parameters = new ArrayList<String>();
			for (final JsonNode parameter : node.path("parameters"))
			{
				parameters.add(parameter.asText(""));
			}
			waiting.add(new Waiting(node.path("entity").asText(""), node.path("attribute").asText(""), parameters));
		}
		final List<String> notes = new ArrayList<String>();
		for (final JsonNode note : root.path("notes"))
		{
			notes.add(note.asText(""));
		}
		return new PreflightReport(root.path("project").asText(""), root.path("at").asLong(0L), root.path("linksChecked").asInt(0), root.path("linksNotChecked").asInt(0), findings,
			waiting, notes);
	}
}
