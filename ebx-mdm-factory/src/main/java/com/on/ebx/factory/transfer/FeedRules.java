package com.on.ebx.factory.transfer;

import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.JsonNode;
import com.on.ebx.connect.core.MappingDefinition;
import com.on.ebx.connect.core.MappingRule;
import com.on.ebx.factory.quality.CleaningSteps;

/**
 * Lot M6 (2026-10-04): what {@code ebx.StagingLoader} hands to {@code MappingEngine.compute} for ONE feed, and the
 * per-field scope of the cleaning steps.
 * <p>
 * <b>The bug this settles</b> (measured on Vol100k / Vol1m, ECOM feed of Personne): two mappings read the SAME landing
 * column {@code FULL_NAME} ({@code firstName} and {@code lastName}, each with its own regex pipeline, both correct on the
 * raw value) and the {@code lastName} mapping carried one {@code UPPER} cleaning step. The loader applied the steps of a
 * mapping into the row document under the SHARED column name, so {@code firstName}'s regex ran on {@code AURELIE-SOPHIE
 * NAVRET} and found nothing - {@code firstName} empty on 100 % of the rows. A mapping's cleaning steps are about THAT
 * mapping's value only.
 * <p>
 * <b>The rule</b>: a field whose mapping has cleaning steps reads a PER-FIELD ALIAS column ({@link #cleanedColumn}) of a
 * copy of the document; the shared source column is never rewritten, so every other mapping reading it sees the raw
 * landing text. The documented order is unchanged per field (cleaning steps first, then the mapping's own pipeline - a
 * pipeline that depends on what a step changes, such as a case-sensitive regex after {@code UPPER}, still sees the
 * changed value: that is configuration, not scope). A {@code concat(...)} that names the source column inside ANY pipeline
 * now reads the RAW value; before M6 it read the cleaned one if any mapping of the feed cleaned that column.
 */
public final class FeedRules
{
	/** Prefix of the alias key a cleaned field reads; a landing column never starts with it (landing columns are plain identifiers or {@code _}-technical fields). */
	static final String CLEANED_PREFIX = "__cleaned__";

	private FeedRules()
	{
	}

	/** The document key a field with cleaning steps reads instead of its shared source column. */
	public static String cleanedColumn(final String pStagingField)
	{
		return FeedRules.CLEANED_PREFIX + pStagingField;
	}

	/** The {@code MappingRule.sourcePath} of {@code pField}: its own alias when it has cleaning steps, its source column otherwise. */
	public static String ruleSourcePath(final TransferPlan.FieldPlan pField)
	{
		return pField.cleaningSteps.isEmpty() ? pField.sourcePath : FeedRules.cleanedColumn(pField.stagingField);
	}

	/** One Connectors rule per WRITABLE field of the feed, in order, reading {@link #ruleSourcePath}. {@code pTablePath} is the staging table's path. */
	public static MappingDefinition definition(final String pFeedId, final String pTablePath, final List<TransferPlan.FieldPlan> pFields,
		final Set<String> pWritableFields)
	{
		final MappingDefinition definition = new MappingDefinition(0, "mdm-factory", "transfer", MappingDefinition.Direction.INBOUND, pFeedId, null, null,
			pTablePath, "", true);
		int position = 0;
		for (final TransferPlan.FieldPlan field : pFields)
		{
			if (!pWritableFields.contains(field.stagingField))
			{
				continue; // never even given a rule: no transform, no cleaning, no quality check
			}
			definition.addRule(new MappingRule(position++, FeedRules.ruleSourcePath(field), "./" + field.stagingField, field.pipeline, MappingRule.Policy.ALWAYS, null));
		}
		return definition;
	}

	/** True when at least one WRITABLE field of the feed has a cleaning step - computed once per feed so a feed that never cleans skips the document copy altogether. */
	public static boolean hasCleaningSteps(final List<TransferPlan.FieldPlan> pFields, final Set<String> pWritableFields)
	{
		for (final TransferPlan.FieldPlan field : pFields)
		{
			if (pWritableFields.contains(field.stagingField) && !field.cleaningSteps.isEmpty())
			{
				return true;
			}
		}
		return false;
	}

	/**
	 * A COPY of {@code pDocument} carrying, for every writable field with cleaning steps, its cleaned value under {@link
	 * #cleanedColumn}; {@code pDocument} itself and its source columns are left exactly as they are. The row scope of a
	 * {@code TRANSCODE_REFERENCE} step is read from the RAW document.
	 */
	public static ObjectNode cleanedDocument(final ObjectNode pDocument, final List<TransferPlan.FieldPlan> pFields, final Set<String> pWritableFields,
		final CleaningSteps.Lookups pLookups)
	{
		final ObjectNode copy = pDocument.deepCopy();
		for (final TransferPlan.FieldPlan field : pFields)
		{
			if (!pWritableFields.contains(field.stagingField) || field.cleaningSteps.isEmpty())
			{
				continue;
			}
			final String raw = FeedRules.text(pDocument.get(field.sourcePath));
			final String scopeFieldName = CleaningSteps.scopeFieldOf(field.cleaningSteps);
			final String scopeValue = scopeFieldName == null ? null : FeedRules.text(pDocument.get(scopeFieldName));
			final String cleaned = CleaningSteps.applyPipeline(field.cleaningSteps, raw, scopeValue, pLookups).value;
			final String alias = FeedRules.cleanedColumn(field.stagingField);
			if (cleaned == null)
			{
				copy.putNull(alias);
			}
			else
			{
				copy.set(alias, JsonNodeFactory.instance.textNode(cleaned));
			}
		}
		return copy;
	}

	static String text(final JsonNode pNode)
	{
		return pNode == null || pNode.isNull() || pNode.isMissingNode() ? null : pNode.asText(null);
	}
}
