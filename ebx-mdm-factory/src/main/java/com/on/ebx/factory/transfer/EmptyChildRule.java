package com.on.ebx.factory.transfer;

import java.util.List;
import java.util.Map;

/**
 * The pure decision behind one CHILD staging row's "is there anything real to write here?" check
 * (bug found live 2026-09-25, volume test project {@code PersVol2} - docs/TARGET-MAPPING.md sec 12): a
 * child feed with {@code collectionPath} empty (docs/TARGET-MAPPING.md sec 3.3 step 6) runs once per
 * PARENT row whether or not the source actually carries that child - a CRM contact with no {@code
 * OTHER_*} address columns still produces an {@code Adresse} child element (the row itself), and every
 * one of its mapped fields comes back null from {@code MappingEngine.compute}. Nothing in the existing
 * pipeline ever asked "did this element actually contribute a value" - {@code ebx.StagingLoader}
 * happily wrote a staging row carrying only its {@code addressType} constant, {@code sourceSystem}/
 * {@code sourceId} and the structural link to the parent: 737 such {@code Adresse} rows on {@code
 * PersVol2}, one per contact with no {@code OTHER} address.
 * <p>
 * <b>What counts as "empty"</b>: every field this feed maps from the SOURCE row (a non-{@code
 * constant(...)} pipeline) is null or blank. A field whose pipeline is {@code constant(...)} never
 * makes a child non-empty on its own - {@code addressType &lt;- constant(OTHER)} is what tells the
 * MAILING and OTHER child feeds of the same row apart, and it always produces a value whether or not
 * the source has an OTHER address, so counting it would make {@link #isEmpty} always answer {@code
 * false}. The structural parent link ({@code Feed#parentLinkField}) and the technical {@code
 * sourceSystem}/{@code sourceId} fields are never passed to this method at all - they are written by
 * {@code StagingLoader} outside the {@code Feed#fields} loop (see its own Javadoc), so they can never
 * appear in {@code pValues} under a {@code FieldPlan#stagingField} key in the first place.
 * <p>
 * A feed with NO non-constant field at all (every mapped field is a literal) is never considered
 * empty - there is nothing "missing" to detect, and flagging it would silently drop a legitimate,
 * always-populated child entity. {@link #isEmpty} therefore only ever answers {@code true} once it has
 * actually SEEN at least one non-constant field.
 * <p>
 * No EBX class here, same convention as every other pure class of this package: {@code
 * ebx.StagingLoader} is the only caller, and it alone decides what happens next (skip a brand-new row,
 * or leave a PRE-EXISTING one in place and report it - see {@code StagingLoader.processRow}'s own
 * Javadoc for why a previously-written empty child is never deleted here).
 */
public final class EmptyChildRule
{
	/** A pipeline step recognised as producing a literal, independent of the source row - see the class Javadoc. */
	private static final String CONSTANT_PREFIX = "constant(";

	private EmptyChildRule()
	{
	}

	/**
	 * @param pFields the child feed's own {@link TransferPlan.FieldPlan}s ({@code Feed#fields})
	 * @param pValues this row's computed values, keyed by {@link TransferPlan.FieldPlan#stagingField} -
	 *            {@code StagingLoader}'s own {@code fields} map, read BEFORE {@code DerivedFieldRule.apply}
	 *            (a derived default must never mask a genuinely empty child - see the class Javadoc)
	 * @return true only when at least one non-{@code constant(...)} field was examined and every one of
	 *         them is null/blank
	 */
	public static boolean isEmpty(final List<TransferPlan.FieldPlan> pFields, final Map<String, Object> pValues)
	{
		boolean sawMappedField = false;
		for (final TransferPlan.FieldPlan field : pFields)
		{
			if (EmptyChildRule.isConstant(field.pipeline))
			{
				continue;
			}
			sawMappedField = true;
			if (!EmptyChildRule.isBlank(pValues.get(field.stagingField)))
			{
				return false;
			}
		}
		return sawMappedField;
	}

	/** Package-visible for its own unit test - a pipeline is a {@code Transformations} step list (e.g. {@code constant(OTHER)}, {@code trim|upper}); only a LEADING {@code constant(} counts, matching {@code Transformations.apply}'s own single-target-value contract. */
	static boolean isConstant(final String pPipeline)
	{
		return pPipeline != null && pPipeline.trim().startsWith(EmptyChildRule.CONSTANT_PREFIX);
	}

	/** Package-visible for its own unit test - {@code MappingEngine.compute}'s {@code Values} return xs-strings: null (no value) and "" (an empty one) both read as blank. */
	static boolean isBlank(final Object pValue)
	{
		return pValue == null || (pValue instanceof String && ((String) pValue).trim().isEmpty());
	}
}
