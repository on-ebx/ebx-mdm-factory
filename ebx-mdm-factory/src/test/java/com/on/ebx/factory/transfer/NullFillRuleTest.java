package com.on.ebx.factory.transfer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.staging.StagingModel;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class NullFillRuleTest
{
	public static void main(final String[] pArgs)
	{
		NullFillRuleTest.canonicalEntityTwoMembersOneLosesAMapping();
		NullFillRuleTest.attributeFedByNobodyIsNulledOnEveryFeed();
		NullFillRuleTest.keyAndTechnicalAttributesNeverCleared();
		NullFillRuleTest.notAcceptedAttributeNeverCleared();
		NullFillRuleTest.foreignKeyFieldFollowsItsOwnAttribute();
		NullFillRuleTest.feedCoveringEverythingProducesNoDefaults();
		NullFillRuleTest.childKeysAttributeIsNeverForcedNull();
		NullFillRuleTest.scriptDerivationIsHeldEmptyUntilAppliedAndNeverWrittenOnceApplied();
		System.out.println("NullFillRuleTest OK");
	}

	/**
	 * The real bug this rule fixes: {@code Company} unifies {@code Supplier} (ERP) and {@code Account} (CRM).
	 * {@code Account.ACCOUNT_TYPE -> Company.legalForm} and {@code Account.SIREN -> Company.vatNo} were
	 * accepted, ran once, then REJECTED - {@code legalForm}/{@code vatNo} must now be nulled on every row the
	 * Account feed writes, while the Supplier feed (which still maps both) must never be touched.
	 */
	private static void canonicalEntityTwoMembersOneLosesAMapping()
	{
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(NullFillRuleTest.business("name"), NullFillRuleTest.business("country"),
			NullFillRuleTest.business("legalForm"), NullFillRuleTest.business("vatNo"));
		final Map<String, String> noForeignKeys = Collections.emptyMap();

		final Set<String> supplierFeed = new HashSet<String>(Arrays.asList("name", "country", "legalForm", "vatNo"));
		final Map<String, Object> supplierDefaults = NullFillRule.defaultsFor(attributes, noForeignKeys, supplierFeed);
		NullFillRuleTest.check("Supplier still maps every field: nothing to null", supplierDefaults.isEmpty());

		final Set<String> accountFeedAfterRejection = new HashSet<String>(Arrays.asList("name", "country")); // legalForm/vatNo mapping rejected
		final Map<String, Object> accountDefaults = NullFillRule.defaultsFor(attributes, noForeignKeys, accountFeedAfterRejection);
		NullFillRuleTest.check("Account no longer maps legalForm: must be forced null", accountDefaults.containsKey("legalForm"));
		NullFillRuleTest.check("legalForm's forced value is null", accountDefaults.get("legalForm") == null);
		NullFillRuleTest.check("Account no longer maps vatNo: must be forced null", accountDefaults.containsKey("vatNo"));
		NullFillRuleTest.check("Account never touches name (it still maps it)", !accountDefaults.containsKey("name"));
		NullFillRuleTest.check("Account never touches country (it still maps it)", !accountDefaults.containsKey("country"));
		NullFillRuleTest.check("exactly the two orphaned fields, nothing more", accountDefaults.size() == 2);
	}

	/**
	 * Lot M5 (2026-10-04): a SCRIPT attribute whose function is NOT applied is forced to an explicit null on every row (an older load's copy
	 * of its source is cleared, and the field is never left holding a raw value); an APPLIED one is never touched (EBX computes it).
	 */
	private static void scriptDerivationIsHeldEmptyUntilAppliedAndNeverWrittenOnceApplied()
	{
		final Map<String, String> noForeignKeys = Collections.emptyMap();
		final Set<String> feed = new HashSet<String>(Arrays.asList("name", "phone"));
		final StagingModel.AttributeInfo unapplied = new StagingModel.AttributeInfo("phoneE164", "phoneE164", "STRING", null, false, false, null, "ACCEPTED", "SCRIPT", false);
		final Map<String, Object> held = NullFillRule.defaultsFor(Arrays.asList(NullFillRuleTest.business("name"), NullFillRuleTest.business("phone"), unapplied), noForeignKeys, feed);
		NullFillRuleTest.check("an unapplied script derivation is forced to an explicit null", held.containsKey("phoneE164") && held.get("phoneE164") == null);
		NullFillRuleTest.check("nothing else is touched", held.size() == 1);

		final StagingModel.AttributeInfo applied = new StagingModel.AttributeInfo("phoneE164", "phoneE164", "STRING", null, false, false, null, "ACCEPTED", "SCRIPT", true);
		final Map<String, Object> left = NullFillRule.defaultsFor(Arrays.asList(NullFillRuleTest.business("name"), applied), noForeignKeys, feed);
		NullFillRuleTest.check("an applied script derivation is never written (EBX computes it)", left.isEmpty());

		final StagingModel.AttributeInfo legacy = new StagingModel.AttributeInfo("phoneE164", "phoneE164", "STRING", null, false, false, null, "ACCEPTED", "SCRIPT");
		NullFillRuleTest.check("the 9-argument form keeps the pre-M5 reading (applied)", NullFillRule.defaultsFor(Arrays.asList(legacy), noForeignKeys, feed).isEmpty());
	}

	/** An attribute with zero accepted mapping anywhere (never appears in ANY feed's coverage) must be nulled on every feed's own row, not just the ones that once had it. */
	private static void attributeFedByNobodyIsNulledOnEveryFeed()
	{
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(NullFillRuleTest.business("name"), NullFillRuleTest.business("notes"));
		final Map<String, String> noForeignKeys = Collections.emptyMap();
		final Set<String> feedA = new HashSet<String>(Arrays.asList("name")); // "notes" never mapped by anyone
		final Set<String> feedB = new HashSet<String>(Arrays.asList("name"));

		NullFillRuleTest.check("feed A nulls the never-fed attribute", NullFillRule.defaultsFor(attributes, noForeignKeys, feedA).containsKey("notes"));
		NullFillRuleTest.check("feed B nulls the never-fed attribute too", NullFillRule.defaultsFor(attributes, noForeignKeys, feedB).containsKey("notes"));
	}

	/** The primary key (sourceSystem/sourceId) and a not-yet-accepted attribute must never appear in the output, whatever the feed covers. */
	private static void keyAndTechnicalAttributesNeverCleared()
	{
		final StagingModel.AttributeInfo sourceSystem = new StagingModel.AttributeInfo("sourceSystem", "Source system", "STRING", null, true, true, null, "PROPOSED");
		final StagingModel.AttributeInfo sourceId = new StagingModel.AttributeInfo("sourceId", "Source id", "STRING", null, true, true, null, "PROPOSED");
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(sourceSystem, sourceId, NullFillRuleTest.business("name"));
		final Map<String, String> noForeignKeys = Collections.emptyMap();
		final Set<String> feedCoversNothingButKey = Collections.emptySet(); // worst case: a feed mapping nothing at all

		final Map<String, Object> defaults = NullFillRule.defaultsFor(attributes, noForeignKeys, feedCoversNothingButKey);
		NullFillRuleTest.check("sourceSystem is never in the output", !defaults.containsKey("sourceSystem"));
		NullFillRuleTest.check("sourceId is never in the output", !defaults.containsKey("sourceId"));
		NullFillRuleTest.check("the one business attribute IS nulled (not covered)", defaults.containsKey("name"));
		NullFillRuleTest.check("nothing else leaks in", defaults.size() == 1);
	}

	/** A business attribute that is still PROPOSED (never generated a field on the staging table at all) must never be forced null either - there is nothing to clear. */
	private static void notAcceptedAttributeNeverCleared()
	{
		final StagingModel.AttributeInfo stillProposed = new StagingModel.AttributeInfo("draftField", "Draft", "STRING", null, false, false, null, "PROPOSED");
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(stillProposed);
		final Map<String, Object> defaults = NullFillRule.defaultsFor(attributes, Collections.<String, String>emptyMap(), Collections.<String>emptySet());
		NullFillRuleTest.check("a PROPOSED attribute has no generated field to clear", defaults.isEmpty());
	}

	/** A foreign-key attribute not covered by the feed must have BOTH its plain attribute name and its <attribute>Ref field nulled - never one without the other, and never the Ref field alone. */
	private static void foreignKeyFieldFollowsItsOwnAttribute()
	{
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(NullFillRuleTest.business("name"), NullFillRuleTest.business("account"));
		final Map<String, String> foreignKeys = new HashMap<String, String>();
		foreignKeys.put("account", "accountRef");
		final Set<String> feedNotCoveringAccount = new HashSet<String>(Arrays.asList("name"));

		final Map<String, Object> defaults = NullFillRule.defaultsFor(attributes, foreignKeys, feedNotCoveringAccount);
		NullFillRuleTest.check("the plain attribute is nulled", defaults.containsKey("account"));
		NullFillRuleTest.check("its Ref field is nulled too", defaults.containsKey("accountRef"));
		NullFillRuleTest.check("both null, nothing invented", defaults.get("account") == null && defaults.get("accountRef") == null);

		final Set<String> feedCoveringAccount = new HashSet<String>(Arrays.asList("name", "account"));
		final Map<String, Object> noDefaults = NullFillRule.defaultsFor(attributes, foreignKeys, feedCoveringAccount);
		NullFillRuleTest.check("a feed that DOES cover the attribute never nulls its Ref field either", !noDefaults.containsKey("accountRef"));
		NullFillRuleTest.check("nor the plain attribute", !noDefaults.containsKey("account"));
	}

	private static void feedCoveringEverythingProducesNoDefaults()
	{
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(NullFillRuleTest.business("a"), NullFillRuleTest.business("b"));
		final Set<String> feed = new HashSet<String>(Arrays.asList("a", "b"));
		NullFillRuleTest.check("nothing to null when the feed covers every attribute",
			NullFillRule.defaultsFor(attributes, Collections.<String, String>emptyMap(), feed).isEmpty());
	}

	/**
	 * Lot M3 (2026-10-03): a {@code CHILD_KEYS} attribute is written by the staging post-pass, never by a feed - no feed "covers" it, so
	 * without this exclusion every feed would seed a null into it and a parent row rewritten by its feed would lose the keys of its
	 * children (and nothing would put them back when no child changed that run). An ordinary uncovered attribute next to it is still nulled.
	 */
	private static void childKeysAttributeIsNeverForcedNull()
	{
		final StagingModel.AttributeInfo childKeys = new StagingModel.AttributeInfo("adresseKeys", "Address keys", "STRING", 2000, false, false, null, "ACCEPTED", "CHILD_KEYS");
		final List<StagingModel.AttributeInfo> attributes = Arrays.asList(NullFillRuleTest.business("name"), NullFillRuleTest.business("notes"), childKeys);
		final Map<String, Object> defaults = NullFillRule.defaultsFor(attributes, Collections.<String, String>emptyMap(), new HashSet<String>(Arrays.asList("name")));
		NullFillRuleTest.check("the uncovered ordinary attribute is still nulled", defaults.containsKey("notes"));
		NullFillRuleTest.check("the CHILD_KEYS attribute is left alone", !defaults.containsKey("adresseKeys"));
		NullFillRuleTest.check("nothing else", defaults.size() == 1);
	}

	private static StagingModel.AttributeInfo business(final String pName)
	{
		return new StagingModel.AttributeInfo(pName, pName, "STRING", 255, false, false, null, "ACCEPTED");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
