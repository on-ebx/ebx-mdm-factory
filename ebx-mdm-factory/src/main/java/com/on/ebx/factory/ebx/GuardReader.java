package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.wizard.StillToDecide;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;

/**
 * Staging-proposal lot 0 (docs/STAGING-PROPOSAL.md): reads every "guard" of a project - a {@code
 * QualityRule} row (nature {@link ReviewLine#QUALITY}) or a {@code CapabilitySuggestion} row of nature
 * {@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#EXTERNAL_SERVICE}/{@link ReviewLine#SCRIPT_DERIVATION}
 * - into plain {@link Guard} values, grouped by the attribute they apply to. The only class of this lot
 * that touches EBX; {@link Guard} itself and its own {@code label()} stay pure and unit-tested without a
 * live repository, the same {@code ebx.ProposalReviews}/{@code review.ReviewLine} split this module
 * already keeps.
 * <p>
 * Field paths and the row-to-Guard mapping deliberately mirror {@code ProposalReviews#addQualityRuleLines}/
 * {@code #addCapabilitySuggestionLines} - reusing the same {@code FactoryPaths} constants those methods
 * already use, never redeclaring a path - but this class does not build the review screen's own free-text
 * "proposition"/rationale wording ({@code review.ProposalWording}): a {@link Guard} carries the raw kind/
 * argument/severity fields instead, so a caller (the future Mapping matrix "Rules and checks" column, the
 * Feed designer's guard panel) can render its own compact presentation rather than the review screen's
 * verbose one.
 */
public final class GuardReader
{
	/**
	 * The key {@link #read} groups every ENTITY-level guard under (a {@code QualityRule}/
	 * {@code CapabilitySuggestion} row whose {@code attribute} is empty) - deliberately the empty string,
	 * never a real attribute id (attribute ids are the EBX-formatted primary key of a {@code StagingAttribute}
	 * row and are never empty). A caller wanting only one entity's own entity-level guards filters this
	 * bucket by {@link Guard#entityId} itself - the map has no per-entity nesting, since most callers (a
	 * matrix column keyed by attribute) never need it.
	 */
	public static final String ENTITY_LEVEL = "";

	private GuardReader()
	{
	}

	/**
	 * Every guard of every {@code StagingEntity} of the project named {@code pProjectCode}, grouped by
	 * {@link Guard#attributeId} - {@link #ENTITY_LEVEL} for an entity-level row. A {@code REJECTED} or
	 * {@code SUPERSEDED} row is skipped outright (task brief); every other status ({@code PROPOSED}/
	 * {@code ACCEPTED}/{@code APPLIED}) is included, since a reviewer needs to see an already-decided guard
	 * too, not only open proposals. An entity itself {@code REJECTED}/{@code SUPERSEDED} is skipped entirely
	 * (same "hide what nobody acts on" rule {@code ebx.MappingMatrixReader}'s own class Javadoc already
	 * applies to entity cards). Returns an empty map (never null) when the project does not exist or has no
	 * entity - a caller distinguishes "no guards yet" from an error by checking a wider signal (e.g. whether
	 * the project itself resolves), not from this method's own return value.
	 */
	public static Map<String, List<Guard>> read(final Adaptation pFactory, final String pProjectCode)
	{
		final Map<String, List<Guard>> out = new LinkedHashMap<String, List<Guard>>();
		final Adaptation project = GuardReader.findProject(pFactory, pProjectCode);
		if (project == null)
		{
			return out;
		}
		final String projectId = project.getOccurrencePrimaryKey().format();
		for (final String entityId : GuardReader.entityIdsOf(pFactory, projectId))
		{
			GuardReader.addQualityRuleGuards(pFactory, entityId, out);
		}
		GuardReader.addCapabilitySuggestionGuards(pFactory, projectId, out);
		return out;
	}

	// --------------------------------------------------------------------------------------------------- entities

	private static Adaptation findProject(final Adaptation pFactory, final String pProjectCode)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.PROJECT).createRequestResult("./code=" + CatalogReader.quote(pProjectCode));
		try
		{
			return rows.nextAdaptation();
		}
		finally
		{
			rows.close();
		}
	}

	/** Every non-{@code REJECTED}/{@code SUPERSEDED} {@code StagingEntity} id of the project, in table order. */
	private static List<String> entityIdsOf(final Adaptation pFactory, final String pProjectId)
	{
		final List<String> ids = new ArrayList<String>();
		final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ENTITY).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = GuardReader.text(row, FactoryPaths.StagingEntity.STATUS);
				if (ReviewLine.REJECTED.equals(status) || ReviewLine.SUPERSEDED.equals(status))
				{
					continue;
				}
				ids.add(row.getOccurrencePrimaryKey().format());
			}
		}
		finally
		{
			rows.close();
		}
		return ids;
	}

	// --------------------------------------------------------------------------------------------------- QualityRule

	private static void addQualityRuleGuards(final Adaptation pFactory, final String pEntityId, final Map<String, List<Guard>> pOut)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.QUALITY_RULE).createRequestResult("./entity=" + CatalogReader.quote(pEntityId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = GuardReader.text(row, FactoryPaths.QualityRule.STATUS);
				if (ReviewLine.REJECTED.equals(status) || ReviewLine.SUPERSEDED.equals(status))
				{
					continue;
				}
				final String attributeId = GuardReader.text(row, FactoryPaths.QualityRule.ATTRIBUTE);
				final Guard guard = new Guard(row.getOccurrencePrimaryKey().format(), ReviewLine.QUALITY, pEntityId, attributeId.isEmpty() ? null : attributeId,
					row.getString(FactoryPaths.QualityRule.KIND), GuardReader.text(row, FactoryPaths.QualityRule.ARGUMENT),
					row.getString(FactoryPaths.QualityRule.SEVERITY), null, status, GuardReader.text(row, FactoryPaths.QualityRule.ORIGIN),
					GuardReader.confidence(row, FactoryPaths.QualityRule.CONFIDENCE), GuardReader.text(row, FactoryPaths.QualityRule.RATIONALE),
					GuardReader.longOf(row, FactoryPaths.QualityRule.VALUES_SEEN), GuardReader.longOf(row, FactoryPaths.QualityRule.VALUES_FAILING),
					GuardReader.textOrNull(row, FactoryPaths.QualityRule.EXAMPLES));
				GuardReader.append(pOut, attributeId.isEmpty() ? GuardReader.ENTITY_LEVEL : attributeId, guard);
			}
		}
		finally
		{
			rows.close();
		}
	}

	// --------------------------------------------------------------------------------------------------- CapabilitySuggestion

	/**
	 * Every {@code CapabilitySuggestion} of the whole project in one query ({@code CapabilitySuggestion.project}
	 * is a direct foreign key, unlike {@code QualityRule}, which only carries {@code entity} - see
	 * {@code FactoryPaths}) - filtered to the same three natures {@link Guard} covers.
	 */
	private static void addCapabilitySuggestionGuards(final Adaptation pFactory, final String pProjectId, final Map<String, List<Guard>> pOut)
	{
		final RequestResult rows = pFactory.getTable(FactoryPaths.CAPABILITY_SUGGESTION).createRequestResult("./project=" + CatalogReader.quote(pProjectId));
		try
		{
			for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
			{
				final String status = GuardReader.text(row, FactoryPaths.CapabilitySuggestion.STATUS);
				// staging-still-to-decide lot (2026-09-27): a suggestion set aside ("kept for later") is neither a
				// guard that runs nor one waiting for a decision - left out of the matrix/designer chips exactly like
				// a rejected one; it stays visible (and decidable) on the Proposal review screen.
				if (ReviewLine.REJECTED.equals(status) || ReviewLine.SUPERSEDED.equals(status) || ReviewLine.SET_ASIDE.equals(status))
				{
					continue;
				}
				final String nature = row.getString(FactoryPaths.CapabilitySuggestion.NATURE);
				if (!ReviewLine.SCRIPT_CONSTRAINT.equals(nature) && !ReviewLine.EXTERNAL_SERVICE.equals(nature) && !ReviewLine.SCRIPT_DERIVATION.equals(nature))
				{
					continue;
				}
				final String entityId = GuardReader.text(row, FactoryPaths.CapabilitySuggestion.ENTITY);
				final String attributeId = GuardReader.text(row, FactoryPaths.CapabilitySuggestion.ATTRIBUTE);
				final String kind;
				final String severity;
				final String transferPolicy;
				if (ReviewLine.SCRIPT_CONSTRAINT.equals(nature) || ReviewLine.SCRIPT_DERIVATION.equals(nature))
				{
					kind = GuardReader.scriptFunctionLabel(row);
					severity = ReviewLine.SCRIPT_CONSTRAINT.equals(nature) ? row.getString(FactoryPaths.CapabilitySuggestion.SEVERITY) : null;
					transferPolicy = ReviewLine.SCRIPT_CONSTRAINT.equals(nature) ? GuardReader.textOrNull(row, FactoryPaths.CapabilitySuggestion.TRANSFER_POLICY) : null;
				}
				else
				{
					kind = GuardReader.text(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_OPERATION);
					severity = null;
					transferPolicy = null;
				}
				final Guard guard = new Guard(row.getOccurrencePrimaryKey().format(), nature, entityId.isEmpty() ? null : entityId,
					attributeId.isEmpty() ? null : attributeId, kind, "", severity, transferPolicy, status,
					GuardReader.text(row, FactoryPaths.CapabilitySuggestion.ORIGIN), GuardReader.confidence(row, FactoryPaths.CapabilitySuggestion.CONFIDENCE),
					GuardReader.text(row, FactoryPaths.CapabilitySuggestion.RATIONALE), null, null, null,
					ReviewLine.EXTERNAL_SERVICE.equals(nature) ? GuardReader.text(row, FactoryPaths.CapabilitySuggestion.CONNECTOR_ID) : "",
					ReviewLine.EXTERNAL_SERVICE.equals(nature) && row.get(FactoryPaths.CapabilitySuggestion.READY) != null
						? Boolean.valueOf(!Boolean.FALSE.equals(row.get(FactoryPaths.CapabilitySuggestion.READY))) : null,
					ReviewLine.EXTERNAL_SERVICE.equals(nature) ? GuardReader.text(row, FactoryPaths.CapabilitySuggestion.READINESS_DETAIL) : "",
					ReviewLine.EXTERNAL_SERVICE.equals(nature) ? GuardReader.text(row, FactoryPaths.CapabilitySuggestion.PRICING) : "",
					StillToDecide.queryToWrite(GuardReader.text(row, FactoryPaths.CapabilitySuggestion.BINDING)),
					row.get(FactoryPaths.CapabilitySuggestion.READY) != null && Boolean.FALSE.equals(row.get(FactoryPaths.CapabilitySuggestion.READY)),
					GuardReader.text(row, FactoryPaths.CapabilitySuggestion.READINESS_DETAIL));
				GuardReader.append(pOut, attributeId.isEmpty() ? GuardReader.ENTITY_LEVEL : attributeId, guard);
			}
		}
		finally
		{
			rows.close();
		}
	}

	/**
	 * {@code "<alias>.<targetFunction>"}, e.g. {@code "phone.isValid"} - {@code <alias>} is
	 * {@code CapabilityApplier.aliasOf} of the library's own import name (falling back to the library idePath
	 * when {@code targetImportName} is empty, same fallback {@code ProposalReviews} already applies), reused
	 * directly rather than re-derived: both classes are in package {@code ebx}.
	 */
	private static String scriptFunctionLabel(final Adaptation pRow)
	{
		final String function = GuardReader.text(pRow, FactoryPaths.CapabilitySuggestion.TARGET_FUNCTION);
		if (function.isEmpty())
		{
			return "";
		}
		final String importName = GuardReader.text(pRow, FactoryPaths.CapabilitySuggestion.TARGET_IMPORT_NAME);
		final String library = importName.isEmpty() ? GuardReader.text(pRow, FactoryPaths.CapabilitySuggestion.TARGET_LIBRARY) : importName;
		final String alias = CapabilityApplier.aliasOf(library);
		return alias.isEmpty() ? function : alias + "." + function;
	}

	// --------------------------------------------------------------------------------------------------- small helpers

	private static void append(final Map<String, List<Guard>> pOut, final String pKey, final Guard pGuard)
	{
		List<Guard> list = pOut.get(pKey);
		if (list == null)
		{
			list = new ArrayList<Guard>();
			pOut.put(pKey, list);
		}
		list.add(pGuard);
	}

	private static String text(final Adaptation pRow, final Path pPath)
	{
		final String value = pRow.getSchemaNode().getNode(pPath) == null ? null : pRow.getString(pPath);
		return value == null ? "" : value;
	}

	/** Same value as {@link #text}, but {@code null} instead of {@code ""} - for a field a {@link Guard} keeps nullable (rationale/examples). */
	private static String textOrNull(final Adaptation pRow, final Path pPath)
	{
		final String value = GuardReader.text(pRow, pPath);
		return value.isEmpty() ? null : value;
	}

	private static Integer confidence(final Adaptation pRow, final Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value instanceof Number ? Integer.valueOf(((Number) value).intValue()) : null;
	}

	private static Long longOf(final Adaptation pRow, final Path pPath)
	{
		final Object value = pRow.get(pPath);
		return value instanceof Number ? Long.valueOf(((Number) value).longValue()) : null;
	}
}
