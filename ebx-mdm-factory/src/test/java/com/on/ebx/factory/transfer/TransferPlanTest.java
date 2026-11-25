package com.on.ebx.factory.transfer;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class TransferPlanTest
{
	public static void main(final String[] pArgs)
	{
		TransferPlanTest.loadOrderParentsFirstOrdinaryCase();
		TransferPlanTest.loadOrderSelfReferenceIsDeferredNotBlocking();
		TransferPlanTest.loadOrderTwoEntityCycleBothEdgesDeferred();
		TransferPlanTest.realDemoSupplierAccountContact();
		TransferPlanTest.canonicalEntityFedByTwoMembersGetsTwoFeeds();
		TransferPlanTest.referenceToAnUnmappedEntityIsNotedNotCrashed();
		TransferPlanTest.cleaningStepsFlowFromMappingInfoIntoFieldPlan();
		TransferPlanTest.qualityRulesFlowFromMappingInfoIntoFieldPlan();
		TransferPlanTest.mappingWithoutFeedIsGroupedIntoASyntheticRootFeed();
		TransferPlanTest.rootFeedWithTwoChildFeedsDifferentEntities();
		TransferPlanTest.childFeedOnTheSameLandingTableEmptyCollectionPath();
		TransferPlanTest.orphanChildFeedIsPromotedToRootAndNoted();
		TransferPlanTest.childFeedMappingOfTheParentLinkFieldIsFlaggedStructural();
		TransferPlanTest.rootFeedMappingOfTheSameAttributeIsNeverFlaggedStructural();
		System.out.println("TransferPlanTest OK");
	}

	// ---------------------------------------------------------------------------------------------------- load order

	private static void loadOrderParentsFirstOrdinaryCase()
	{
		final List<String> entities = Arrays.asList("Contact", "Account");
		final List<TransferPlan.Edge> edges = Arrays.asList(new TransferPlan.Edge("Contact", "Account"));
		final TransferPlan.LoadOrder order = TransferPlan.loadOrder(entities, edges);
		TransferPlanTest.check("Account before Contact: " + order.order, order.order.equals(Arrays.asList("Account", "Contact")));
		TransferPlanTest.check("no cycle", order.cycleEntities.isEmpty());
		TransferPlanTest.check("the edge is immediate, not deferred", order.deferred.isEmpty());
	}

	/** PLAN.md's own real example: ACCOUNT.PARENT_ACCOUNT_ID -> ACCOUNT. A self reference must not block ordering, and its one edge must be deferred (second pass). */
	private static void loadOrderSelfReferenceIsDeferredNotBlocking()
	{
		final List<String> entities = Arrays.asList("Account");
		final List<TransferPlan.Edge> edges = Arrays.asList(new TransferPlan.Edge("Account", "Account"));
		final TransferPlan.LoadOrder order = TransferPlan.loadOrder(entities, edges);
		TransferPlanTest.check("Account is placed: " + order.order, order.order.equals(Arrays.asList("Account")));
		TransferPlanTest.check("not reported as a real cycle", order.cycleEntities.isEmpty());
		TransferPlanTest.check("the self reference is deferred: " + order.deferred, order.deferred.contains("Account->Account"));
	}

	private static void loadOrderTwoEntityCycleBothEdgesDeferred()
	{
		final List<String> entities = Arrays.asList("X", "A", "B");
		final List<TransferPlan.Edge> edges = Arrays.asList(new TransferPlan.Edge("A", "B"), new TransferPlan.Edge("B", "A"));
		final TransferPlan.LoadOrder order = TransferPlan.loadOrder(entities, edges);
		TransferPlanTest.check("X placed first, A and B appended: " + order.order, order.order.equals(Arrays.asList("X", "A", "B")));
		TransferPlanTest.check("A and B reported as a cycle: " + order.cycleEntities, order.cycleEntities.equals(Arrays.asList("A", "B")));
		TransferPlanTest.check("both edges deferred", order.deferred.contains("A->B") && order.deferred.contains("B->A"));
	}

	// -------------------------------------------------------------------------------------------------------- plan()

	/** PLAN.md's real demo: Supplier (no reference), Account (self reference via parentAccountId), Contact (accountId -> Account). Every mapping here has NO feed at all - the pre-lot-1 shape, synthetically grouped. */
	private static void realDemoSupplierAccountContact()
	{
		final List<TransferPlan.MappingInfo> mappings = new ArrayList<TransferPlan.MappingInfo>();
		mappings.add(TransferPlanTest.mapping("Supplier", "name", "STRING", null, "trim", "NAME1", "SUPPLIER"));
		mappings.add(TransferPlanTest.mapping("Account", "name", "STRING", null, "trim", "NAME", "ACCOUNT"));
		mappings.add(TransferPlanTest.referenceMapping("Account", "parentAccountId", "parentAccountIdRef", "Account", "trim", "PARENT_ACCOUNT_ID", "ACCOUNT"));
		mappings.add(TransferPlanTest.mapping("Contact", "email", "STRING", null, "trim", "EMAIL", "CONTACT"));
		mappings.add(TransferPlanTest.referenceMapping("Contact", "accountId", "accountIdRef", "Account", "trim", "ACCOUNT_ID", "CONTACT"));

		final TransferPlan.Result result = TransferPlan.plan(mappings);
		TransferPlanTest.check("three entities: " + TransferPlanTest.names(result.loadOrder), result.loadOrder.size() == 3);
		TransferPlanTest.check("Account is loaded before Contact",
			TransferPlanTest.names(result.loadOrder).indexOf("Account") < TransferPlanTest.names(result.loadOrder).indexOf("Contact"));
		TransferPlanTest.check("no unresolved cycle", result.cycleEntities.isEmpty());

		final TransferPlan.ForeignKeyPlan contactToAccount = TransferPlanTest.fk(result.foreignKeys, "Contact", "accountId");
		TransferPlanTest.check("Contact.accountId -> Account, not deferred", contactToAccount != null && "Account".equals(contactToAccount.targetEntityName) && !contactToAccount.deferred);
		TransferPlanTest.check("the generated field name is carried through, not re-derived", "accountIdRef".equals(contactToAccount.fieldName));

		final TransferPlan.ForeignKeyPlan selfRef = TransferPlanTest.fk(result.foreignKeys, "Account", "parentAccountId");
		TransferPlanTest.check("Account.parentAccountId -> Account, deferred", selfRef != null && selfRef.deferred);

		// the plain attribute is still generated even though it is also a foreign key (StagingModel's own contract: kept as plain data)
		final TransferPlan.Entity contactEntity = TransferPlanTest.entity(result.loadOrder, "Contact");
		final TransferPlan.FieldPlan accountIdField = TransferPlanTest.field(contactEntity, "accountId");
		TransferPlanTest.check("accountId itself is a normal field with its own pipeline", accountIdField != null && "trim".equals(accountIdField.pipeline));
		TransferPlanTest.check("...and also carries the foreign-key field name", "accountIdRef".equals(accountIdField.foreignKeyField));
		TransferPlanTest.check("...not deferred (Account is loaded first)", !accountIdField.foreignKeyDeferred);
	}

	/** A canonical entity fed by two rule entities (Supplier, Account) gets one ROOT feed per member (both synthetic, pre-lot-1 shape), each with only the fields that member supplies. */
	private static void canonicalEntityFedByTwoMembersGetsTwoFeeds()
	{
		final List<TransferPlan.MappingInfo> mappings = new ArrayList<TransferPlan.MappingInfo>();
		mappings.add(TransferPlanTest.mapping("Company", "vatNumber", "STRING", null, "trim", "VAT_NUMBER", "SUPPLIER"));
		mappings.add(TransferPlanTest.mapping("Company", "region", "STRING", null, "trim", "REGION", "ACCOUNT"));

		final TransferPlan.Result result = TransferPlan.plan(mappings);
		TransferPlanTest.check("one entity", result.loadOrder.size() == 1);
		final TransferPlan.Entity company = result.loadOrder.get(0);
		TransferPlanTest.check("two root feeds: " + company.feeds.size(), company.feeds.size() == 2);
		final TransferPlan.Feed supplierFeed = TransferPlanTest.feed(company, "SUPPLIER");
		final TransferPlan.Feed accountFeed = TransferPlanTest.feed(company, "ACCOUNT");
		TransferPlanTest.check("the SUPPLIER feed only carries vatNumber", supplierFeed != null && supplierFeed.fields.size() == 1
			&& "vatNumber".equals(supplierFeed.fields.get(0).stagingField));
		TransferPlanTest.check("the ACCOUNT feed only carries region", accountFeed != null && accountFeed.fields.size() == 1
			&& "region".equals(accountFeed.fields.get(0).stagingField));
		TransferPlanTest.check("neither is a child (no real TransferFeed row involved)", supplierFeed.parentFeedId == null && accountFeed.parentFeedId == null);
	}

	private static void referenceToAnUnmappedEntityIsNotedNotCrashed()
	{
		final List<TransferPlan.MappingInfo> mappings = new ArrayList<TransferPlan.MappingInfo>();
		mappings.add(TransferPlanTest.referenceMapping("Contact", "accountId", "accountIdRef", "Account", "trim", "ACCOUNT_ID", "CONTACT"));
		final TransferPlan.Result result = TransferPlan.plan(mappings);
		TransferPlanTest.check("Contact is still generated", result.loadOrder.size() == 1 && "Contact".equals(result.loadOrder.get(0).name));
		TransferPlanTest.check("a note explains the unmapped target, no exception", !result.notes.isEmpty());
	}

	/** Task D (the execution hook): a mapping's ACCEPTED-or-APPLIED MappingStep pipeline must survive into the FieldPlan {@code StagingLoader} actually reads - never silently dropped between MappingInfo and FieldPlan. */
	private static void cleaningStepsFlowFromMappingInfoIntoFieldPlan()
	{
		final List<com.on.ebx.factory.quality.CleaningSteps.Step> steps = Arrays
			.asList(new com.on.ebx.factory.quality.CleaningSteps.Step(com.on.ebx.factory.quality.CleaningSteps.TRIM, null));
		final TransferPlan.MappingInfo withSteps = new TransferPlan.MappingInfo("Supplier", "name1", "STRING", Integer.valueOf(255), false, null, null, "trim",
			"NAME1", "P_Landing", "ERP", "SUPPLIER", "ERP", steps, null, null);
		final TransferPlan.MappingInfo withoutSteps = TransferPlanTest.mapping("Supplier", "vatNumber", "STRING", Integer.valueOf(40), "trim", "VAT_NO", "SUPPLIER");
		final TransferPlan.Result result = TransferPlan.plan(Arrays.asList(withSteps, withoutSteps));
		final TransferPlan.FieldPlan name1 = TransferPlanTest.fieldNamed(result, "name1");
		final TransferPlan.FieldPlan vatNumber = TransferPlanTest.fieldNamed(result, "vatNumber");
		TransferPlanTest.check("the pipeline reaches the FieldPlan unchanged", name1 != null && name1.cleaningSteps.size() == 1
			&& com.on.ebx.factory.quality.CleaningSteps.TRIM.equals(name1.cleaningSteps.get(0).kind));
		TransferPlanTest.check("a mapping with no MappingStep gets an empty (never null) list", vatNumber != null && vatNumber.cleaningSteps.isEmpty());
	}

	/** Task C (execution hook): an attribute's ACCEPTED-or-APPLIED QualityRules must survive into the FieldPlan {@code StagingLoader} actually reads - never silently dropped between MappingInfo and FieldPlan, the same way {@link #cleaningStepsFlowFromMappingInfoIntoFieldPlan} already proves for MappingStep. */
	private static void qualityRulesFlowFromMappingInfoIntoFieldPlan()
	{
		final List<com.on.ebx.factory.quality.QualityRuleCheck.Rule> rules = Arrays
			.asList(new com.on.ebx.factory.quality.QualityRuleCheck.Rule("QR1", com.on.ebx.factory.quality.QualityDetectors.CHECK_DIGIT, "SIREN",
				com.on.ebx.factory.quality.QualityDetectors.WARN));
		final TransferPlan.MappingInfo withRules = new TransferPlan.MappingInfo("Supplier", "registrationNo", "STRING", Integer.valueOf(20), false, null, null,
			"trim", "REGISTRATION_NO", "P_Landing", "ERP", "SUPPLIER", "ERP", null, rules, null);
		final TransferPlan.MappingInfo withoutRules = TransferPlanTest.mapping("Supplier", "vatNumber", "STRING", Integer.valueOf(40), "trim", "VAT_NO", "SUPPLIER");
		final TransferPlan.Result result = TransferPlan.plan(Arrays.asList(withRules, withoutRules));
		final TransferPlan.FieldPlan registrationNo = TransferPlanTest.fieldNamed(result, "registrationNo");
		final TransferPlan.FieldPlan vatNumber = TransferPlanTest.fieldNamed(result, "vatNumber");
		TransferPlanTest.check("the rule reaches the FieldPlan unchanged", registrationNo != null && registrationNo.qualityRules.size() == 1
			&& "QR1".equals(registrationNo.qualityRules.get(0).id));
		TransferPlanTest.check("an attribute with no QualityRule gets an empty (never null) list", vatNumber != null && vatNumber.qualityRules.isEmpty());
	}

	/** docs/TARGET-MAPPING.md sec 3.1: a mapping with no {@code feed} at all is grouped into a synthetic root feed, keyed by (entity, landing table) - so a project between deployment and its first {@code staging/suggest} (FeedBackfill) still loads. */
	private static void mappingWithoutFeedIsGroupedIntoASyntheticRootFeed()
	{
		final TransferPlan.MappingInfo mapping = TransferPlanTest.mapping("Supplier", "name", "STRING", null, "trim", "NAME1", "SUPPLIER");
		TransferPlanTest.check("no feed reference at all", mapping.feed.feedId == null);
		final TransferPlan.Result result = TransferPlan.plan(Arrays.asList(mapping));
		final TransferPlan.Entity supplier = TransferPlanTest.entity(result.loadOrder, "Supplier");
		TransferPlanTest.check("one root feed, synthesised", supplier != null && supplier.feeds.size() == 1);
		final TransferPlan.Feed feed = supplier.feeds.get(0);
		TransferPlanTest.check("the synthetic feed is a root (no parent)", feed.parentFeedId == null);
		TransferPlanTest.check("landing table carried through", "SUPPLIER".equals(feed.landingTableName));
	}

	/** docs/TARGET-MAPPING.md sec 3.1: one root feed (Personne) with two child feeds - one to a DIFFERENT entity (Adresse), one to the SAME entity as another root (never happens in practice, but the tree must not assume distinct entities either). */
	private static void rootFeedWithTwoChildFeedsDifferentEntities()
	{
		final TransferPlan.FeedRef root = new TransferPlan.FeedRef("F1", null, "", null, null);
		final TransferPlan.FeedRef child1 = new TransferPlan.FeedRef("F2", "F1", "", "personneRef", null);
		final TransferPlan.FeedRef child2 = new TransferPlan.FeedRef("F3", "F1", "", "personneRef", "concat(ID)|suffix(#SHIP)", "SHIP");
		final List<TransferPlan.MappingInfo> mappings = new ArrayList<TransferPlan.MappingInfo>();
		mappings.add(new TransferPlan.MappingInfo("Personne", "name", "STRING", null, false, null, null, "trim", "NAME", "P_Landing", "ERP", "PERSONNE", "ERP",
			null, null, root));
		mappings.add(new TransferPlan.MappingInfo("Adresse", "type", "STRING", null, false, null, null, "constant('MAIN')", "TYPE", "P_Landing", "ERP", "PERSONNE",
			"ERP", null, null, child1));
		mappings.add(new TransferPlan.MappingInfo("Adresse", "type", "STRING", null, false, null, null, "constant('SHIP')", "TYPE", "P_Landing", "ERP", "PERSONNE",
			"ERP", null, null, child2));

		final TransferPlan.Result result = TransferPlan.plan(mappings);
		final TransferPlan.Entity personne = TransferPlanTest.entity(result.loadOrder, "Personne");
		final TransferPlan.Entity adresse = TransferPlanTest.entity(result.loadOrder, "Adresse");
		TransferPlanTest.check("Personne has one root feed", personne != null && personne.feeds.size() == 1);
		TransferPlanTest.check("Adresse has no ROOT feed of its own - both its feeds are children of Personne's root", adresse != null && adresse.feeds.isEmpty());
		TransferPlanTest.check("Personne loaded before Adresse (structural edge)",
			TransferPlanTest.names(result.loadOrder).indexOf("Personne") < TransferPlanTest.names(result.loadOrder).indexOf("Adresse"));
		final TransferPlan.Feed personneRoot = personne.feeds.get(0);
		TransferPlanTest.check("two child feeds under the root", personneRoot.children.size() == 2);
		TransferPlanTest.check("both children target Adresse", "Adresse".equals(personneRoot.children.get(0).entityName)
			&& "Adresse".equals(personneRoot.children.get(1).entityName));
		TransferPlanTest.check("each child carries its own sourceIdPipeline / parentLinkField",
			"personneRef".equals(personneRoot.children.get(0).parentLinkField)
				&& "concat(ID)|suffix(#SHIP)".equals(personneRoot.children.get(1).sourceIdPipeline));
		TransferPlanTest.check("a structural edge never becomes a ForeignKeyPlan", result.foreignKeys.isEmpty());
		TransferPlanTest.check("the variant reaches the planned feed (multi-child lot)", personneRoot.children.get(0).variant == null
			&& "SHIP".equals(personneRoot.children.get(1).variant));
	}

	/** docs/TARGET-MAPPING.md sec 2.1: a child feed on the SAME landing table as its parent, collectionPath empty (the flat-source case). */
	private static void childFeedOnTheSameLandingTableEmptyCollectionPath()
	{
		final TransferPlan.FeedRef root = new TransferPlan.FeedRef("F1", null, "", null, null);
		final TransferPlan.FeedRef child = new TransferPlan.FeedRef("F2", "F1", "", "personneRef", null);
		final List<TransferPlan.MappingInfo> mappings = Arrays.asList(
			new TransferPlan.MappingInfo("Personne", "name", "STRING", null, false, null, null, "trim", "NAME", "P_Landing", "ERP", "PERSONNE", "ERP", null, null, root),
			new TransferPlan.MappingInfo("Adresse", "street", "STRING", null, false, null, null, "trim", "STREET", "P_Landing", "ERP", "PERSONNE", "ERP", null, null,
				child));
		final TransferPlan.Result result = TransferPlan.plan(mappings);
		final TransferPlan.Entity personne = TransferPlanTest.entity(result.loadOrder, "Personne");
		final TransferPlan.Feed childFeed = personne.feeds.get(0).children.get(0);
		TransferPlanTest.check("child feed reads the SAME landing table", "PERSONNE".equals(childFeed.landingTableName));
		TransferPlanTest.check("collectionPath empty: the parent row itself is the one element", "".equals(childFeed.collectionPath));
	}

	/** A child feed whose parent feed has no mapped attribute this run is promoted to a root, not dropped. */
	private static void orphanChildFeedIsPromotedToRootAndNoted()
	{
		final TransferPlan.FeedRef orphanChild = new TransferPlan.FeedRef("F2", "F1-MISSING", "", "personneRef", null);
		final TransferPlan.MappingInfo mapping = new TransferPlan.MappingInfo("Adresse", "street", "STRING", null, false, null, null, "trim", "STREET", "P_Landing",
			"ERP", "ADRESSE", "ERP", null, null, orphanChild);
		final TransferPlan.Result result = TransferPlan.plan(Arrays.asList(mapping));
		final TransferPlan.Entity adresse = TransferPlanTest.entity(result.loadOrder, "Adresse");
		TransferPlanTest.check("promoted to a root feed of its own entity", adresse != null && adresse.feeds.size() == 1);
		TransferPlanTest.check("a note explains the promotion, no exception", !result.notes.isEmpty());
	}

	/**
	 * Bug fix, docs/TARGET-MAPPING.md sec 3.3/4.2 (found live 2026-09-23 on project Personnes' first real run):
	 * a CHILD feed's own mapping of the entity's link-to-parent attribute (redundant with the structural
	 * {@code parentLinkField} write {@code StagingLoader} makes from the parent row) must be flagged {@link
	 * TransferPlan.FieldPlan#structuralParentLink} so the loader never resolves it by an ordinary lookup - see
	 * {@code ebx.StagingLoader#processRow}'s own Javadoc for the "unknown parent" symptom this settles. An
	 * ordinary, non-FK field of the same child feed (street) must never be flagged.
	 */
	private static void childFeedMappingOfTheParentLinkFieldIsFlaggedStructural()
	{
		final TransferPlan.FeedRef root = new TransferPlan.FeedRef("F1", null, "", null, null);
		final TransferPlan.FeedRef child = new TransferPlan.FeedRef("F2", "F1", "", "personneRef", "concat(ID)|suffix(#MAIN)");
		final List<TransferPlan.MappingInfo> mappings = Arrays.asList(
			new TransferPlan.MappingInfo("Personne", "name", "STRING", null, false, null, null, "trim", "NAME", "P_Landing", "ERP", "CUSTOMER", "ERP", null, null,
				root),
			new TransferPlan.MappingInfo("Adresse", "street", "STRING", null, false, null, null, "trim", "STREET", "P_Landing", "ERP", "CUSTOMER", "ERP", null, null,
				child),
			// the redundant rule the language-model pass used to be allowed to propose: personne mapped from ID,
			// exactly like an ordinary foreign key - foreignKeyField "personneRef" is the SAME field the child
			// feed's own parentLinkField already names.
			new TransferPlan.MappingInfo("Adresse", "personne", "STRING", Integer.valueOf(255), false, "Personne", "personneRef", "trim", "ID", "P_Landing", "ERP",
				"CUSTOMER", "ERP", null, null, child));

		final TransferPlan.Result result = TransferPlan.plan(mappings);
		final TransferPlan.Entity personne = TransferPlanTest.entity(result.loadOrder, "Personne");
		final TransferPlan.Feed childFeed = personne.feeds.get(0).children.get(0);
		final TransferPlan.FieldPlan personneField = TransferPlanTest.fieldInFeed(childFeed, "personne");
		final TransferPlan.FieldPlan streetField = TransferPlanTest.fieldInFeed(childFeed, "street");
		TransferPlanTest.check("the redundant link-attribute mapping is flagged structural",
			personneField != null && personneField.structuralParentLink);
		TransferPlanTest.check("an ordinary field of the same child feed is never flagged",
			streetField != null && !streetField.structuralParentLink);
	}

	/**
	 * The two-table-source case (docs/TARGET-MAPPING.md sec 0/4.2, "Example 1"): Adresse fed by its OWN root
	 * feed (no parentFeed, {@code parentLinkField} null) must resolve {@code personne} by an ordinary lookup,
	 * never treat it as structural - a root feed's {@code parentLinkField} is always null, so the same
	 * attribute name that would be flagged on a CHILD feed must never be flagged here.
	 */
	private static void rootFeedMappingOfTheSameAttributeIsNeverFlaggedStructural()
	{
		final TransferPlan.MappingInfo mapping = new TransferPlan.MappingInfo("Adresse", "personne", "STRING", Integer.valueOf(255), false, "Personne",
			"personneRef", "trim", "PERSON_ID", "P_Landing", "ERP", "ADDRESS", "ERP", null, null, null);
		final TransferPlan.Result result = TransferPlan.plan(Arrays.asList(mapping));
		final TransferPlan.FieldPlan personneField = TransferPlanTest.field(TransferPlanTest.entity(result.loadOrder, "Adresse"), "personne");
		TransferPlanTest.check("a root feed's own mapping of the same attribute is never flagged structural",
			personneField != null && !personneField.structuralParentLink);
	}

	private static TransferPlan.FieldPlan fieldNamed(final TransferPlan.Result pResult, final String pStagingField)
	{
		for (final TransferPlan.Entity entity : pResult.loadOrder)
		{
			for (final TransferPlan.Feed feed : entity.feeds)
			{
				final TransferPlan.FieldPlan found = TransferPlanTest.fieldInFeed(feed, pStagingField);
				if (found != null)
				{
					return found;
				}
			}
		}
		return null;
	}

	private static TransferPlan.FieldPlan fieldInFeed(final TransferPlan.Feed pFeed, final String pStagingField)
	{
		for (final TransferPlan.FieldPlan field : pFeed.fields)
		{
			if (pStagingField.equals(field.stagingField))
			{
				return field;
			}
		}
		for (final TransferPlan.Feed child : pFeed.children)
		{
			final TransferPlan.FieldPlan found = TransferPlanTest.fieldInFeed(child, pStagingField);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	// ------------------------------------------------------------------------------------------------------ helpers

	private static TransferPlan.MappingInfo mapping(final String pEntity, final String pField, final String pType, final Integer pMaxLength, final String pPipeline,
		final String pLandingField, final String pLandingTable)
	{
		return new TransferPlan.MappingInfo(pEntity, pField, pType, pMaxLength, false, null, null, pPipeline, pLandingField, "P_Landing", "ERP", pLandingTable, "ERP",
			null, null, null);
	}

	private static TransferPlan.MappingInfo referenceMapping(final String pEntity, final String pField, final String pForeignKeyField, final String pReferenceEntity,
		final String pPipeline, final String pLandingField, final String pLandingTable)
	{
		return new TransferPlan.MappingInfo(pEntity, pField, "STRING", Integer.valueOf(255), false, pReferenceEntity, pForeignKeyField, pPipeline, pLandingField,
			"P_Landing", "ERP", pLandingTable, "ERP", null, null, null);
	}

	private static List<String> names(final List<TransferPlan.Entity> pEntities)
	{
		final List<String> out = new ArrayList<String>();
		for (final TransferPlan.Entity entity : pEntities)
		{
			out.add(entity.name);
		}
		return out;
	}

	private static TransferPlan.Entity entity(final List<TransferPlan.Entity> pEntities, final String pName)
	{
		for (final TransferPlan.Entity entity : pEntities)
		{
			if (entity.name.equals(pName))
			{
				return entity;
			}
		}
		return null;
	}

	private static TransferPlan.Feed feed(final TransferPlan.Entity pEntity, final String pLandingTableName)
	{
		for (final TransferPlan.Feed feed : pEntity.feeds)
		{
			if (feed.landingTableName.equals(pLandingTableName))
			{
				return feed;
			}
		}
		return null;
	}

	private static TransferPlan.FieldPlan field(final TransferPlan.Entity pEntity, final String pStagingField)
	{
		for (final TransferPlan.Feed feed : pEntity.feeds)
		{
			final TransferPlan.FieldPlan found = TransferPlanTest.fieldInFeed(feed, pStagingField);
			if (found != null)
			{
				return found;
			}
		}
		return null;
	}

	private static TransferPlan.ForeignKeyPlan fk(final List<TransferPlan.ForeignKeyPlan> pForeignKeys, final String pEntity, final String pAttribute)
	{
		for (final TransferPlan.ForeignKeyPlan fk : pForeignKeys)
		{
			if (fk.entityName.equals(pEntity) && fk.attributeName.equals(pAttribute))
			{
				return fk;
			}
		}
		return null;
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
