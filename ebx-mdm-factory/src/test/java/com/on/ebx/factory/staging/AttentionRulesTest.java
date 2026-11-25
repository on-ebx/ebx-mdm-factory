package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.staging.AttentionRules.Flag;
import com.on.ebx.factory.staging.AttentionRules.MappingInput;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws.
 * <p>
 * Deliberately exercises two unrelated domains (a "people" shape and a "retail article" shape) with the
 * SAME numeric patterns, to prove {@link AttentionRules} is domain-free (no column/attribute name is ever
 * read by the rules under test - see the class Javadoc).
 */
public final class AttentionRulesTest
{
	public static void main(final String[] pArgs)
	{
		AttentionRulesTest.constantColumnFiresAtOrAboveTenFilledRows_people();
		AttentionRulesTest.constantColumnFiresAtOrAboveTenFilledRows_articles();
		AttentionRulesTest.emptyColumnFiresOnZeroFilledRows();
		AttentionRulesTest.rejectedMappingIsIgnoredByEveryRule();
		AttentionRulesTest.nullProfileNeverFiresConstantOrEmpty();
		AttentionRulesTest.duplicateFieldFlagsTheLaterAttributeNotTheFirst_people();
		AttentionRulesTest.duplicateFieldFlagsTheLaterAttributeNotTheFirst_articles();
		AttentionRulesTest.duplicateFieldNeverFiresWhenTheAttributeHasAnIndependentColumnToo();
		AttentionRulesTest.duplicateFieldScopedPerEntity();
		AttentionRulesTest.emptiedByRuleFiresAtHalfOrMore();
		AttentionRulesTest.orderUnknownSplitFlagsASplittingRegexOnAColumnOfUnknownOrder();
		AttentionRulesTest.conversionFailsOnAnyOutputTheTypedAttributeCannotStore();
		AttentionRulesTest.sameConstantFlagsEverySourceWhenAllWriteTheSameConstant();
		AttentionRulesTest.sameConstantNeverFiresOnAnythingLessThanEverySource();
		AttentionRulesTest.constantValueIsReadFromAOneStepConstantOnly();
		System.out.println("AttentionRulesTest OK");
	}

	/** Staging normalisation lot D (2026-10-17): isodate() on a text date. */
	private static void conversionFailsOnAnyOutputTheTypedAttributeCannotStore()
	{
		final int invalid = TransformVerifier.invalidOutputs("DATE", java.util.Arrays.asList("10/06/1963", "1963-06-10"));
		AttentionRulesTest.check("one invalid DATE output raises CONVERSION_FAILS", AttentionRules.conversionFails(invalid));
		AttentionRulesTest.check("all valid: no flag", !AttentionRules.conversionFails(TransformVerifier.invalidOutputs("DATE", java.util.Arrays.asList("1963-06-10"))));
		AttentionRulesTest.check("a text attribute never raises it", !AttentionRules.conversionFails(TransformVerifier.invalidOutputs("STRING", java.util.Arrays.asList("10/06/1963"))));
	}

	private static MappingInput constantRow(final String pAttribute, final String pFeed, final String pPipeline, final String pStatus, final boolean pAlternative)
	{
		return new MappingInput(pAttribute, "col_" + pFeed, "entity_address", pStatus, Double.valueOf(100.0), Long.valueOf(40), Long.valueOf(165), pFeed, pPipeline, pAlternative);
	}

	/** DemoBala: Adresse.addressType fed constant(MAILING) by the SAP feed and the Salesforce feed. */
	private static void sameConstantFlagsEverySourceWhenAllWriteTheSameConstant()
	{
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("type", "f695", "constant(MAILING)", "PROPOSED", false),
			AttentionRulesTest.constantRow("type", "f696", "constant('MAILING')", "PROPOSED", false)));
		check("both mappings are flagged", AttentionRulesTest.countOf(flags, AttentionRules.SAME_CONSTANT) == 2);
		final Flag flag = flags.get(0);
		check("the flag carries the value and the number of feeds", "MAILING".equals(flag.constantValue) && flag.feedCount == 2 && "type".equals(flag.attributeId) && "col_f695".equals(flag.landingColumnId));
		// the same shape in an unrelated domain, other words: a "currency" every shop feed writes
		final List<Flag> retail = AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("currency", "shopA", "constant(EUR)", "ACCEPTED", false),
			AttentionRulesTest.constantRow("currency", "shopB", "constant(EUR)", "PROPOSED", false), AttentionRulesTest.constantRow("currency", "shopC", "constant(EUR)", "PROPOSED", false)));
		check("three feeds, three flags", AttentionRulesTest.countOf(retail, AttentionRules.SAME_CONSTANT) == 3 && retail.get(0).feedCount == 3);
	}

	private static void sameConstantNeverFiresOnAnythingLessThanEverySource()
	{
		check("one feed alone says nothing", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "PROPOSED", false))),
			AttentionRules.SAME_CONSTANT) == 0);
		check("two values: the field tells the sources apart", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "PROPOSED", false),
			AttentionRulesTest.constantRow("t", "f2", "constant(B)", "PROPOSED", false))), AttentionRules.SAME_CONSTANT) == 0);
		check("a source that reads its column is not a constant", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "PROPOSED", false),
			AttentionRulesTest.constantRow("t", "f2", "trim|upper", "PROPOSED", false))), AttentionRules.SAME_CONSTANT) == 0);
		check("a rejected feed's mapping is not a source", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "PROPOSED", false),
			AttentionRulesTest.constantRow("t", "f2", "constant(A)", "REJECTED", false))), AttentionRules.SAME_CONSTANT) == 0);
		// once the model's alternative exists (a PROPOSED mapping with alternativeTo) the originals still count and the alternative never does
		check("the alternative of one source does not hide the finding", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "PROPOSED", false),
			AttentionRulesTest.constantRow("t", "f2", "constant(A)", "PROPOSED", false), AttentionRulesTest.constantRow("t", "f1", "constant(B)", "PROPOSED", true))), AttentionRules.SAME_CONSTANT) == 2);
		check("once the original is rejected and the alternative taken, the finding is gone", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "REJECTED", false),
			AttentionRulesTest.constantRow("t", "f2", "constant(A)", "PROPOSED", false), AttentionRulesTest.constantRow("t", "f1", "constant(B)", "ACCEPTED", false))), AttentionRules.SAME_CONSTANT) == 0);
		check("a caller that does not know the feed never gets the flag", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(
			new MappingInput("t", "c1", "e", "PROPOSED", Double.valueOf(100), Long.valueOf(3), Long.valueOf(10)), new MappingInput("t", "c2", "e", "PROPOSED", Double.valueOf(100), Long.valueOf(3), Long.valueOf(10)))),
			AttentionRules.SAME_CONSTANT) == 0);
		check("two entities are never mixed", AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(AttentionRulesTest.constantRow("t", "f1", "constant(A)", "PROPOSED", false),
			new MappingInput("t", "c2", "other_entity", "PROPOSED", Double.valueOf(100), Long.valueOf(40), Long.valueOf(165), "f2", "constant(A)", false))), AttentionRules.SAME_CONSTANT) == 0);
	}

	private static void constantValueIsReadFromAOneStepConstantOnly()
	{
		check("bare", "MAILING".equals(AttentionRules.constantValueOf("constant(MAILING)")));
		check("single quotes", "MAILING".equals(AttentionRules.constantValueOf(" constant('MAILING') ")));
		check("double quotes, a space inside", "TO DO".equals(AttentionRules.constantValueOf("constant(\"TO DO\")")));
		check("upper case step", "A".equals(AttentionRules.constantValueOf("CONSTANT(A)")));
		check("a chain is not a constant", AttentionRules.constantValueOf("constant(A)|upper") == null && AttentionRules.constantValueOf("trim|constant(A)") == null);
		check("empty, missing, other step", AttentionRules.constantValueOf("constant()") == null && AttentionRules.constantValueOf("constant('')") == null
			&& AttentionRules.constantValueOf(null) == null && AttentionRules.constantValueOf("upper") == null && AttentionRules.constantValueOf("") == null);
		check("a quote inside is not a plain literal", AttentionRules.constantValueOf("constant('a'b')") == null);
	}

	// ------------------------------------------------------------------------------------------------- CONSTANT_COLUMN

	/** SAP BusinessPartnerCategory = '1' in all 165 rows mapped to salutation - the task brief's own worked example, reproduced with plain numbers only. */
	private static void constantColumnFiresAtOrAboveTenFilledRows_people()
	{
		final MappingInput row = new MappingInput("attr_salutation", "col_category", "entity_person", "ACCEPTED", Double.valueOf(100.0), Long.valueOf(1),
			Long.valueOf(165));
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(row));
		AttentionRulesTest.check("one CONSTANT_COLUMN flag", AttentionRulesTest.countOf(flags, AttentionRules.CONSTANT_COLUMN) == 1);
		final Flag flag = flags.get(0);
		AttentionRulesTest.check("flag targets the right attribute", "attr_salutation".equals(flag.attributeId));
		AttentionRulesTest.check("flag carries the filled/total/distinct numbers", flag.filledRows.longValue() == 165L && flag.totalRows.longValue() == 165L
			&& flag.distinctCount.longValue() == 1L);
	}

	/** Same numeric shape, unrelated domain: a "unit of measure" column of a product catalog holding one single value across every filled row. */
	private static void constantColumnFiresAtOrAboveTenFilledRows_articles()
	{
		final MappingInput row = new MappingInput("attr_unitOfMeasure", "col_uom", "entity_article", "PROPOSED", Double.valueOf(100.0), Long.valueOf(1),
			Long.valueOf(40));
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(row));
		AttentionRulesTest.check("one CONSTANT_COLUMN flag on the article entity too", AttentionRulesTest.countOf(flags, AttentionRules.CONSTANT_COLUMN) == 1);

		// below the 10-filled-rows floor: never flagged, whatever the distinct count
		final MappingInput tooFew = new MappingInput("attr_unitOfMeasure", "col_uom", "entity_article", "PROPOSED", Double.valueOf(100.0), Long.valueOf(1),
			Long.valueOf(9));
		AttentionRulesTest.check("9 filled rows never fires CONSTANT_COLUMN",
			AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(tooFew)), AttentionRules.CONSTANT_COLUMN) == 0);

		// more than one distinct value: never CONSTANT_COLUMN, whatever the row count
		final MappingInput notConstant = new MappingInput("attr_unitOfMeasure", "col_uom", "entity_article", "PROPOSED", Double.valueOf(100.0),
			Long.valueOf(2), Long.valueOf(200));
		AttentionRulesTest.check("two distinct values never fires CONSTANT_COLUMN",
			AttentionRulesTest.countOf(AttentionRules.detect(AttentionRulesTest.list(notConstant)), AttentionRules.CONSTANT_COLUMN) == 0);
	}

	// ------------------------------------------------------------------------------------------------- EMPTY_COLUMN

	/** MobilePhone empty in both sources - the task brief's own worked example, plus the same shape on an article's "discontinuedReason" column. */
	private static void emptyColumnFiresOnZeroFilledRows()
	{
		final MappingInput person = new MappingInput("attr_mobilePhone", "col_mobile", "entity_person", "ACCEPTED", Double.valueOf(0.0), Long.valueOf(0),
			Long.valueOf(200));
		final MappingInput article = new MappingInput("attr_discontinuedReason", "col_reason", "entity_article", "PROPOSED", Double.valueOf(0.0),
			Long.valueOf(0), Long.valueOf(500));
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(person, article));
		AttentionRulesTest.check("two EMPTY_COLUMN flags, one per domain", AttentionRulesTest.countOf(flags, AttentionRules.EMPTY_COLUMN) == 2);
		AttentionRulesTest.check("an EMPTY_COLUMN row never also reads CONSTANT_COLUMN", AttentionRulesTest.countOf(flags, AttentionRules.CONSTANT_COLUMN) == 0);
	}

	// ------------------------------------------------------------------------------------------------- ignored rows

	private static void rejectedMappingIsIgnoredByEveryRule()
	{
		final MappingInput rejected = new MappingInput("attr_x", "col_x", "entity_e", "REJECTED", Double.valueOf(0.0), Long.valueOf(0), Long.valueOf(500));
		AttentionRulesTest.check("a REJECTED mapping raises nothing", AttentionRules.detect(AttentionRulesTest.list(rejected)).isEmpty());
	}

	private static void nullProfileNeverFiresConstantOrEmpty()
	{
		final MappingInput neverProfiled = new MappingInput("attr_x", "col_x", "entity_e", "PROPOSED", null, null, null);
		AttentionRulesTest.check("a never-profiled column raises nothing (degrades gracefully)",
			AttentionRules.detect(AttentionRulesTest.list(neverProfiled)).isEmpty());
	}

	// ------------------------------------------------------------------------------------------------- DUPLICATE_FIELD

	/** SAP StreetName feeds both mailingStreet and streetName - the task brief's own worked example. */
	private static void duplicateFieldFlagsTheLaterAttributeNotTheFirst_people()
	{
		final MappingInput first = new MappingInput("attr_mailingStreet", "col_streetName", "entity_person", "ACCEPTED", null, null, null);
		final MappingInput second = new MappingInput("attr_streetName", "col_streetName", "entity_person", "PROPOSED", null, null, null);
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(first, second));
		AttentionRulesTest.check("exactly one DUPLICATE_FIELD flag", AttentionRulesTest.countOf(flags, AttentionRules.DUPLICATE_FIELD) == 1);
		final Flag flag = AttentionRulesTest.firstOf(flags, AttentionRules.DUPLICATE_FIELD);
		AttentionRulesTest.check("the SECOND (later) attribute is the one flagged, never the first", "attr_streetName".equals(flag.attributeId));
		AttentionRulesTest.check("the shared column is named", "col_streetName".equals(flag.landingColumnId));
		AttentionRulesTest.check("the flag names the other attribute it shares the column with",
			flag.sharedWithAttributeIds.size() == 1 && "attr_mailingStreet".equals(flag.sharedWithAttributeIds.get(0)));
	}

	/** Same shape, unrelated domain: a "packSize" landing column feeding both a product's own packSize and its displaySize attribute. */
	private static void duplicateFieldFlagsTheLaterAttributeNotTheFirst_articles()
	{
		final MappingInput first = new MappingInput("attr_packSize", "col_packSize", "entity_article", "ACCEPTED", null, null, null);
		final MappingInput second = new MappingInput("attr_displaySize", "col_packSize", "entity_article", "PROPOSED", null, null, null);
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(first, second));
		AttentionRulesTest.check("exactly one DUPLICATE_FIELD flag on the article entity too",
			AttentionRulesTest.countOf(flags, AttentionRules.DUPLICATE_FIELD) == 1);
		AttentionRulesTest.check("the later attribute is flagged", "attr_displaySize".equals(AttentionRulesTest.firstOf(flags, AttentionRules.DUPLICATE_FIELD).attributeId));
	}

	/** An attribute fed by one shared column AND one column nobody else uses is never flagged - task brief: "never both". */
	private static void duplicateFieldNeverFiresWhenTheAttributeHasAnIndependentColumnToo()
	{
		final MappingInput owner = new MappingInput("attr_a", "col_shared", "entity_e", "ACCEPTED", null, null, null);
		final MappingInput laterButAlsoIndependent1 = new MappingInput("attr_b", "col_shared", "entity_e", "PROPOSED", null, null, null);
		final MappingInput laterButAlsoIndependent2 = new MappingInput("attr_b", "col_onlyB", "entity_e", "PROPOSED", null, null, null);
		final List<Flag> flags =
			AttentionRules.detect(AttentionRulesTest.list(owner, laterButAlsoIndependent1, laterButAlsoIndependent2));
		AttentionRulesTest.check("attr_b has its own independent column too: never flagged", AttentionRulesTest.countOf(flags, AttentionRules.DUPLICATE_FIELD) == 0);
	}

	private static void duplicateFieldScopedPerEntity()
	{
		final MappingInput e1First = new MappingInput("attr_a", "col_shared", "entity_1", "ACCEPTED", null, null, null);
		final MappingInput e1Second = new MappingInput("attr_b", "col_shared", "entity_1", "PROPOSED", null, null, null);
		// entity_2 happens to reuse the same landing column id (never possible in practice - ids are unique
		// across the project - but the rule must not let it leak across entities regardless).
		final MappingInput e2Only = new MappingInput("attr_c", "col_shared", "entity_2", "PROPOSED", null, null, null);
		final List<Flag> flags = AttentionRules.detect(AttentionRulesTest.list(e1First, e1Second, e2Only));
		AttentionRulesTest.check("only the entity_1 pair produces a flag", AttentionRulesTest.countOf(flags, AttentionRules.DUPLICATE_FIELD) == 1);
		AttentionRulesTest.check("entity_2's lone claim on the same column id is never flagged (no sibling in ITS OWN entity)",
			"attr_b".equals(AttentionRulesTest.firstOf(flags, AttentionRules.DUPLICATE_FIELD).attributeId));
	}

	// ------------------------------------------------------------------------------------------------- helpers

	private static List<MappingInput> list(final MappingInput... pRows)
	{
		final List<MappingInput> out = new ArrayList<MappingInput>();
		for (final MappingInput row : pRows)
		{
			out.add(row);
		}
		return out;
	}

	private static int countOf(final List<Flag> pFlags, final String pKind)
	{
		int n = 0;
		for (final Flag flag : pFlags)
		{
			if (pKind.equals(flag.kind))
			{
				n++;
			}
		}
		return n;
	}

	private static Flag firstOf(final List<Flag> pFlags, final String pKind)
	{
		for (final Flag flag : pFlags)
		{
			if (pKind.equals(flag.kind))
			{
				return flag;
			}
		}
		throw new AssertionError("no flag of kind " + pKind);
	}

	private static void emptiedByRuleFiresAtHalfOrMore()
	{
		check("9 of 10 emptied fires", AttentionRules.emptiedByRule("regexExtract('^9+ (.*)', 1)", 10, 9));
		check("5 of 10 emptied fires (half)", AttentionRules.emptiedByRule("trim", 10, 5));
		check("4 of 10 emptied does not fire", !AttentionRules.emptiedByRule("trim", 10, 4));
		check("no filled input never fires", !AttentionRules.emptiedByRule("trim", 0, 0));
		check("constant( never fires", !AttentionRules.emptiedByRule("constant('SHIP')", 10, 10));
		check("constant( with spaces/case never fires", !AttentionRules.emptiedByRule("  Constant('X')", 3, 3));
		check("blank pipeline never fires", !AttentionRules.emptiedByRule("", 10, 10));
		check("null pipeline never fires", !AttentionRules.emptiedByRule(null, 10, 10));
	}

	/**
	 * Vol100kN (2026-10-12): a regex splitting a multi-token column whose order cannot be inferred (LOYALTY.MEMBER.FULL_NAME)
	 * went through "accept all". Pure numbers: whether the sampled column is order-unknown, how many multi-token sampled
	 * values the rule turned into a strict part of themselves; no name read. A retail example (a product label of words) gives the same flag.
	 */
	private static void orderUnknownSplitFlagsASplittingRegexOnAColumnOfUnknownOrder()
	{
		final String casingRegex = "regex((?:^(?=[\\p{Lu}'-]+\\s)|(?<=\\s)(?=[\\p{L}'-]+$))([\\p{L}'-]+))";
		check("a splitting regex on an order-unknown column fires", AttentionRules.orderUnknownSplit(casingRegex, true, 20, 20));
		check("a regex step after another step fires too", AttentionRules.orderUnknownSplit("trim|regex(^(\\w+))", true, 11, 20));
		check("when the casing tells the parts apart it does not", !AttentionRules.orderUnknownSplit(casingRegex, false, 20, 20));
		check("a rule that keeps the whole value does not", !AttentionRules.orderUnknownSplit("trim|upper", true, 0, 20));
		check("a regex that takes a part of fewer than half of the values does not", !AttentionRules.orderUnknownSplit(casingRegex, true, 9, 20));
		check("no multi-token sampled value never fires", !AttentionRules.orderUnknownSplit(casingRegex, true, 0, 0));
		check("a constant never fires", !AttentionRules.orderUnknownSplit("constant('regex(x)')", true, 20, 20));
		check("a blank or null pipeline never fires", !AttentionRules.orderUnknownSplit("", true, 20, 20) && !AttentionRules.orderUnknownSplit(null, true, 20, 20));
		check("the flag kind", "ORDER_UNKNOWN_SPLIT".equals(AttentionRules.ORDER_UNKNOWN_SPLIT));
		// the retail twin: a two-word product label split by position
		final TokenCase labels = TokenCase.of(java.util.Arrays.asList("Blue Chair", "Chair Green", "Table Oak", "Oak Shelf", "Red Lamp", "Lamp Brass", "Pine Desk", "Desk Walnut"));
		check("a column of word pairs in one layout is order-unknown whatever the domain", labels.orderUnknown());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
