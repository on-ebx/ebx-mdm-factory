package com.on.ebx.factory.staging;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** JUnit-free, like the tests of the sibling rules (landing package): run the main, a failure throws. */
public final class StagingRulesTest
{
	public static void main(final String[] pArgs)
	{
		StagingRulesTest.realDemo();
		StagingRulesTest.maxLengthRounding();
		StagingRulesTest.mandatoryWithProfile();
		StagingRulesTest.enumeratedThresholds();
		StagingRulesTest.multiColumnRelationIsSkipped();
		StagingRulesTest.entityNamePrefixedOnCollision();
		StagingRulesTest.backfillLinksOnlyRuleAttributesWithAnUnlinkedReference();
		StagingRulesTest.backfillIgnoresWhatDoesNotMatch();
		StagingRulesTest.technicalAttributeProposalsArePrimaryKeyNotBusinessKey();
		StagingRulesTest.backfillPrimaryKeyOnlyTouchesUnsetRuleTechnicalAttributes();
		StagingRulesTest.alignTechnicalKeyStatusToTheEntitysCurrentStatus();
		StagingRulesTest.isMissingTechnicalKeyDetectsNoPrimaryKeyRowAtAll();
		StagingRulesTest.technicalKeyBackfillProposalsMatchTheOrdinaryTechnicalKeyShape();
		StagingRulesTest.technicalKeyBackfillStatusFollowsTheEntityOrDefaultsToProposed();
		StagingRulesTest.alignMappingStatusFollowsTheAttributesCurrentStatus();
		System.out.println("StagingRulesTest OK");
	}

	/**
	 * The real Snowflake demo (PLAN.md, 2026-09-20): ERP.SUPPLIER (22 columns, key SUPPLIER_ID), CRM.ACCOUNT
	 * (18 columns, key ACCOUNT_ID), CRM.CONTACT (11 columns, key CONTACT_ID); no profile at all on any of the
	 * three tables; two proposed relations (CONTACT.ACCOUNT_ID -> ACCOUNT confidence 80, ACCOUNT.PARENT_ACCOUNT_ID
	 * -> ACCOUNT confidence 65, self reference), both still PROPOSED (not yet accepted).
	 */
	private static void realDemo()
	{
		final StagingRules.LandingTableInfo supplier = StagingRulesTest.table("t-supplier", "ERP", "SUPPLIER", 55L, StagingRulesTest.supplierColumns());
		final StagingRules.LandingTableInfo account = StagingRulesTest.table("t-account", "CRM", "ACCOUNT", 52L, StagingRulesTest.accountColumns());
		final StagingRules.LandingTableInfo contact = StagingRulesTest.table("t-contact", "CRM", "CONTACT", 89L, StagingRulesTest.contactColumns());

		final List<StagingRules.RelationInfo> relations = Arrays.asList(
			new StagingRules.RelationInfo("rel-contact-account", "CRM", "CONTACT", Arrays.asList("ACCOUNT_ID"), "ACCOUNT", "PROPOSED", 80),
			new StagingRules.RelationInfo("rel-account-parent", "CRM", "ACCOUNT", Arrays.asList("PARENT_ACCOUNT_ID"), "ACCOUNT", "PROPOSED", 65));

		final StagingRules.Result result = StagingRules.propose(Arrays.asList(supplier, account, contact), relations, 50);

		StagingRulesTest.check("three entities, no collision", result.entities.size() == 3);
		final StagingRules.EntityProposal supplierEntity = StagingRulesTest.entity(result, "Supplier");
		final StagingRules.EntityProposal accountEntity = StagingRulesTest.entity(result, "Account");
		final StagingRules.EntityProposal contactEntity = StagingRulesTest.entity(result, "Contact");
		StagingRulesTest.check("entity names: Supplier, Account, Contact (no source-system prefix needed)",
			supplierEntity != null && accountEntity != null && contactEntity != null);
		StagingRulesTest.check("no entity was prefixed", !supplierEntity.rationale.contains("Prefixed") && !accountEntity.rationale.contains("Prefixed")
			&& !contactEntity.rationale.contains("Prefixed"));

		// Contact: 11 source columns + sourceSystem + sourceId = 13 attributes
		StagingRulesTest.check("Contact has 13 attributes: " + contactEntity.attributes.size(), contactEntity.attributes.size() == 13);
		final List<String> contactNames = new ArrayList<String>();
		for (final StagingRules.AttributeProposal a : contactEntity.attributes)
		{
			contactNames.add(a.name);
		}
		StagingRulesTest.check("Contact attribute names: " + contactNames,
			contactNames.containsAll(Arrays.asList("sourceSystem", "sourceId", "contactId", "accountId", "firstName", "lastName", "email", "phone", "title",
				"hasOptedOutOfEmail", "createdDate", "lastModifiedDate", "leadSource")));

		// the reference of accountId -> Account
		final StagingRules.AttributeProposal accountId = StagingRulesTest.attribute(contactEntity, "accountId");
		StagingRulesTest.check("accountId references Account", "Account".equals(accountId.referenceEntity));
		StagingRulesTest.check("accountId confidence is the relation's (80), not the type-driven default", accountId.confidence == 80);
		StagingRulesTest.check("accountId rationale cites the relation and says to accept it first",
			accountId.rationale.contains("ACCOUNT_ID") && accountId.rationale.toLowerCase(Locale.ROOT).contains("accept"));
		StagingRulesTest.check("accountId.fromRelationId is the LandingRelation's own id, not a text match",
			"rel-contact-account".equals(accountId.fromRelationId));

		// the self reference of parentAccountId
		final StagingRules.AttributeProposal parentAccountId = StagingRulesTest.attribute(accountEntity, "parentAccountId");
		StagingRulesTest.check("parentAccountId references Account (self reference)", "Account".equals(parentAccountId.referenceEntity));
		StagingRulesTest.check("parentAccountId confidence is the relation's (65)", parentAccountId.confidence == 65);
		StagingRulesTest.check("parentAccountId is not a business key: PARENT_ACCOUNT_ID is not the source key", !parentAccountId.businessKey);
		StagingRulesTest.check("parentAccountId.fromRelationId is its own relation's id, distinct from accountId's",
			"rel-account-parent".equals(parentAccountId.fromRelationId));

		// a plain, non-relation attribute never carries a fromRelationId
		StagingRulesTest.check("email.fromRelationId is null: no relation involved",
			StagingRulesTest.attribute(contactEntity, "email").fromRelationId == null);

		// business keys: only the three declared source keys
		final StagingRules.AttributeProposal supplierId = StagingRulesTest.attribute(supplierEntity, "supplierId");
		final StagingRules.AttributeProposal accountIdAttr = StagingRulesTest.attribute(accountEntity, "accountId");
		final StagingRules.AttributeProposal contactId = StagingRulesTest.attribute(contactEntity, "contactId");
		StagingRulesTest.check("the three source keys are business keys and mandatory",
			supplierId.businessKey && supplierId.mandatory && accountIdAttr.businessKey && accountIdAttr.mandatory && contactId.businessKey && contactId.mandatory);
		StagingRulesTest.check("a source key is never the staging primary key: that is sourceSystem/sourceId alone",
			!supplierId.primaryKey && !accountIdAttr.primaryKey && !contactId.primaryKey);
		StagingRulesTest.check("a non-key column is not mandatory without a profile, and says why",
			!StagingRulesTest.attribute(contactEntity, "email").mandatory
				&& StagingRulesTest.attribute(contactEntity, "email").rationale.toLowerCase(Locale.ROOT).contains("no profile"));

		// BOOLEAN / DATE / TIMESTAMP columns: type, mapping transformation and argument
		final StagingRules.AttributeProposal hasOptedOut = StagingRulesTest.attribute(contactEntity, "hasOptedOutOfEmail");
		StagingRulesTest.check("hasOptedOutOfEmail is BOOLEAN, mapped with PARSE_BOOLEAN, no argument", "BOOLEAN".equals(hasOptedOut.dataType)
			&& "PARSE_BOOLEAN".equals(hasOptedOut.mapping.transformation) && hasOptedOut.mapping.argument == null);
		StagingRulesTest.check("hasOptedOutOfEmail has no maxLength (not a string)", hasOptedOut.maxLength == null);

		final StagingRules.AttributeProposal createdOn = StagingRulesTest.attribute(accountEntity, "createdOn");
		StagingRulesTest.check("createdOn is DATE, mapped with PARSE_DATE 'yyyy-MM-dd'",
			"DATE".equals(createdOn.dataType) && "PARSE_DATE".equals(createdOn.mapping.transformation) && "yyyy-MM-dd".equals(createdOn.mapping.argument));

		final StagingRules.AttributeProposal changedAt = StagingRulesTest.attribute(supplierEntity, "changedAt");
		final StagingRules.AttributeProposal createdDate = StagingRulesTest.attribute(contactEntity, "createdDate");
		final StagingRules.AttributeProposal lastModifiedDate = StagingRulesTest.attribute(contactEntity, "lastModifiedDate");
		for (final StagingRules.AttributeProposal ts : Arrays.asList(changedAt, createdDate, lastModifiedDate))
		{
			StagingRulesTest.check(ts.name + " is TIMESTAMP, mapped with PARSE_DATE \"yyyy-MM-dd'T'HH:mm:ss\"", "TIMESTAMP".equals(ts.dataType)
				&& "PARSE_DATE".equals(ts.mapping.transformation) && "yyyy-MM-dd'T'HH:mm:ss".equals(ts.mapping.argument));
		}

		// a plain STRING column: TRIM, default length 255, confidence 70 (everything about it is a default)
		final StagingRules.AttributeProposal email = StagingRulesTest.attribute(contactEntity, "email");
		StagingRulesTest.check("email is STRING/TRIM/255/confidence 70", "STRING".equals(email.dataType) && "TRIM".equals(email.mapping.transformation)
			&& Integer.valueOf(255).equals(email.maxLength) && email.confidence == 70);

		// the two technical attributes: no mapping, since AttributeMapping.landingColumn is mandatory
		final StagingRules.AttributeProposal sourceSystem = StagingRulesTest.attribute(contactEntity, "sourceSystem");
		final StagingRules.AttributeProposal sourceId = StagingRulesTest.attribute(contactEntity, "sourceId");
		StagingRulesTest.check("sourceSystem/sourceId carry no mapping (AttributeMapping.landingColumn is mandatory)",
			sourceSystem.mapping == null && sourceId.mapping == null);
		StagingRulesTest.check("sourceSystem/sourceId are the entity's staging primary key, not its business key (two distinct notions)",
			sourceSystem.primaryKey && sourceId.primaryKey && !sourceSystem.businessKey && !sourceId.businessKey);

		StagingRulesTest.check("no relation was skipped: both are single-column", result.skippedRelations.isEmpty());
		boolean noProfileNoted = false;
		for (final String note : result.notes)
		{
			if (note.contains("3 of 3"))
			{
				noProfileNoted = true;
			}
		}
		StagingRulesTest.check("a note says none of the 3 tables were profiled: " + result.notes, noProfileNoted);
	}

	/** Rule 4: profiled maxLength 80 with a 50% margin -> 120, which is already a round size. */
	private static void maxLengthRounding()
	{
		StagingRulesTest.check("margined(80,50) = 120", StagingRules.margined(80, 50) == 120);
		StagingRulesTest.check("roundedLength(120) = 120 (already round)", StagingRules.roundedLength(120) == 120);
		StagingRulesTest.check("roundedLength(121) rounds up to 255", StagingRules.roundedLength(121) == 255);
		StagingRulesTest.check("roundedLength(5) rounds up to 10", StagingRules.roundedLength(5) == 10);
		StagingRulesTest.check("roundedLength caps at 4000", StagingRules.roundedLength(9000) == 4000);

		final StagingRules.LandingColumnInfo profiled = new StagingRules.LandingColumnInfo("c1", "DESCRIPTION", "description", "STRING", false, null, null,
			Integer.valueOf(80), null);
		final StagingRules.LandingTableInfo t = StagingRulesTest.table("t1", "ERP", "ITEM", 10L, Arrays.asList(profiled));
		final StagingRules.Result result = StagingRules.propose(Arrays.asList(t), null, 50);
		final StagingRules.AttributeProposal description = StagingRulesTest.attribute(result.entities.get(0), "description");
		StagingRulesTest.check("profiled maxLength 80 + 50% margin -> 120", Integer.valueOf(120).equals(description.maxLength));
		StagingRulesTest.check("confidence 90: sized from a profile", description.confidence == 90);
		StagingRulesTest.check("rationale mentions the profiled maximum", description.rationale.contains("80"));
	}

	/** Rule 3: mandatory only from a 100% profiled fill rate, never guessed without one. */
	private static void mandatoryWithProfile()
	{
		final StagingRules.LandingColumnInfo full = new StagingRules.LandingColumnInfo("c1", "CODE", "code", "STRING", false, new BigDecimal("100.00"), null,
			null, null);
		final StagingRules.LandingColumnInfo partial = new StagingRules.LandingColumnInfo("c2", "NOTE", "note", "STRING", false, new BigDecimal("42.00"), null,
			null, null);
		final StagingRules.LandingTableInfo t = StagingRulesTest.table("t1", "ERP", "ITEM", 10L, Arrays.asList(full, partial));
		final StagingRules.Result result = StagingRules.propose(Arrays.asList(t), null, 50);
		StagingRulesTest.check("100% fill rate: mandatory", StagingRulesTest.attribute(result.entities.get(0), "code").mandatory);
		StagingRulesTest.check("42% fill rate: not mandatory", !StagingRulesTest.attribute(result.entities.get(0), "note").mandatory);
	}

	/** Rule 5: enumerated needs a profile, <=20 distinct values, >=50 rows, and distinct*5 <= rows; never a key, BOOLEAN or a date type. */
	private static void enumeratedThresholds()
	{
		final StagingRules.LandingColumnInfo status = new StagingRules.LandingColumnInfo("c1", "STATUS", "status", "STRING", false, null, Long.valueOf(5),
			null, null);
		final StagingRules.LandingColumnInfo tooManyDistinct = new StagingRules.LandingColumnInfo("c2", "CATEGORY", "category", "STRING", false, null,
			Long.valueOf(30), null, null);
		final StagingRules.LandingTableInfo t = StagingRulesTest.table("t1", "ERP", "ITEM", 100L, Arrays.asList(status, tooManyDistinct));
		final StagingRules.Result result = StagingRules.propose(Arrays.asList(t), null, 50);
		StagingRulesTest.check("5 distinct over 100 rows: enumerated", StagingRulesTest.attribute(result.entities.get(0), "status").enumerated);
		StagingRulesTest.check("30 distinct (>20): not enumerated", !StagingRulesTest.attribute(result.entities.get(0), "category").enumerated);

		// too few rows for the ratio to mean anything
		final StagingRules.LandingColumnInfo smallTable = new StagingRules.LandingColumnInfo("c3", "KIND", "kind", "STRING", false, null, Long.valueOf(3),
			null, null);
		final StagingRules.LandingTableInfo small = StagingRulesTest.table("t2", "ERP", "TINY", 10L, Arrays.asList(smallTable));
		final StagingRules.Result smallResult = StagingRules.propose(Arrays.asList(small), null, 50);
		StagingRulesTest.check("only 10 rows (<50): not enumerated even with 3 distinct values",
			!StagingRulesTest.attribute(smallResult.entities.get(0), "kind").enumerated);
	}

	/** Rule 6: a multi-column relation is skipped and reported, never proposed as a reference. */
	private static void multiColumnRelationIsSkipped()
	{
		final StagingRules.LandingColumnInfo orderId = new StagingRules.LandingColumnInfo("c1", "ORDER_ID", "orderId", "INTEGER", true, null, null, null, null);
		final StagingRules.LandingColumnInfo line = new StagingRules.LandingColumnInfo("c2", "LINE", "line", "INTEGER", false, null, null, null, null);
		final StagingRules.LandingTableInfo shipment = StagingRulesTest.table("t1", "ERP", "SHIPMENT", 10L, Arrays.asList(orderId, line));
		final StagingRules.LandingTableInfo order = StagingRulesTest.table("t2", "ERP", "ORDER", 10L, Arrays.asList(orderId, line));
		final List<StagingRules.RelationInfo> relations = Arrays
			.asList(new StagingRules.RelationInfo("rel-shipment-order", "ERP", "SHIPMENT", Arrays.asList("ORDER_ID", "LINE"), "ORDER", "ACCEPTED", 90));
		final StagingRules.Result result = StagingRules.propose(Arrays.asList(shipment, order), relations, 50);
		StagingRulesTest.check("multi-column relation is skipped, not proposed", result.skippedRelations.size() == 1);
		StagingRulesTest.check("reason explains it is multi-column", result.skippedRelations.get(0).reason.toLowerCase(Locale.ROOT).contains("multi-column"));
		StagingRulesTest.check("no attribute of SHIPMENT got a reference",
			StagingRulesTest.attribute(StagingRulesTest.entity(result, "Shipment"), "orderId").referenceEntity == null);
	}

	/** Rule 1: two tables of different sources that would propose the same entity name both get the source system prefixed. */
	private static void entityNamePrefixedOnCollision()
	{
		final StagingRules.LandingColumnInfo id = new StagingRules.LandingColumnInfo("c1", "ITEM_ID", "itemId", "INTEGER", true, null, null, null, null);
		final StagingRules.LandingTableInfo erpItem = StagingRulesTest.table("t1", "ERP", "ITEM", 10L, Arrays.asList(id));
		final StagingRules.LandingTableInfo crmItem = StagingRulesTest.table("t2", "CRM", "ITEM", 10L, Arrays.asList(id));
		final StagingRules.Result result = StagingRules.propose(Arrays.asList(erpItem, crmItem), null, 50);
		StagingRulesTest.check("both prefixed with their source system: ErpItem, CrmItem",
			StagingRulesTest.entity(result, "ErpItem") != null && StagingRulesTest.entity(result, "CrmItem") != null);
		StagingRulesTest.check("no plain \"Item\" entity", StagingRulesTest.entity(result, "Item") == null);
	}

	/**
	 * {@link StagingRules#backfillFromRelation}: an existing, origin RULE attribute with a reference and no {@code fromRelation} yet gets
	 * linked when this run's rules propose the same attribute name with a relation id - the exact case of the rows already on the live
	 * instance before {@code fromRelation} existed (PLAN.md, 2026-09-20 staging pass: 3 entities / 57 attributes / 51 mappings, all RULE).
	 */
	private static void backfillLinksOnlyRuleAttributesWithAnUnlinkedReference()
	{
		final List<StagingRules.AttributeProposal> ruled = Arrays.asList(
			StagingRulesTest.attributeProposal("accountId", "rel-contact-account"), StagingRulesTest.attributeProposal("legalName", null));
		final List<StagingRules.ExistingAttribute> existing = Arrays
			.asList(new StagingRules.ExistingAttribute("attr-1", "accountId", true, false, false, false, "PROPOSED", "RULE"));
		final List<StagingRules.Backfill> backfills = StagingRules.backfillFromRelation(existing, ruled);
		StagingRulesTest.check("one row linked", backfills.size() == 1);
		StagingRulesTest.check("the right row, the right relation", "attr-1".equals(backfills.get(0).attributeId) && "rel-contact-account".equals(backfills.get(0).relationId));
	}

	/**
	 * Every case {@link StagingRules#backfillFromRelation} must leave alone: already linked, no reference at all, origin USER, origin LLM
	 * (the model's own canonical attributes, {@code StagingLlmPass}, never carry a reference or a {@code fromRelation} in the first place -
	 * this proves the rule does not mistakenly touch them even if one somehow had a same-named match), and no matching proposal name.
	 */
	private static void backfillIgnoresWhatDoesNotMatch()
	{
		final List<StagingRules.AttributeProposal> ruled = Arrays.asList(StagingRulesTest.attributeProposal("accountId", "rel-contact-account"),
			StagingRulesTest.attributeProposal("parentAccountId", "rel-account-parent"), StagingRulesTest.attributeProposal("legalName", null));
		final List<StagingRules.ExistingAttribute> existing = Arrays.asList(
			new StagingRules.ExistingAttribute("already-linked", "accountId", true, true, false, false, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("no-reference", "legalName", false, false, false, false, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("user-origin", "accountId", true, false, false, false, "PROPOSED", "USER"),
			new StagingRules.ExistingAttribute("llm-origin", "accountId", true, false, false, false, "PROPOSED", "LLM"),
			new StagingRules.ExistingAttribute("no-match", "someOtherName", true, false, false, false, "PROPOSED", "RULE"));
		StagingRulesTest.check("none of the five rows get backfilled", StagingRules.backfillFromRelation(existing, ruled).isEmpty());
	}

	/** {@link StagingRules#technicalAttributeProposals()}: what {@code StagingLlmSuggester} reuses for a canonical entity's own technical key. */
	private static void technicalAttributeProposalsArePrimaryKeyNotBusinessKey()
	{
		final List<StagingRules.AttributeProposal> technical = StagingRules.technicalAttributeProposals();
		StagingRulesTest.check("exactly sourceSystem and sourceId", technical.size() == 2 && "sourceSystem".equals(technical.get(0).name)
			&& "sourceId".equals(technical.get(1).name));
		for (final StagingRules.AttributeProposal attribute : technical)
		{
			StagingRulesTest.check(attribute.name + " is primaryKey, not businessKey, mandatory, no mapping",
				attribute.primaryKey && !attribute.businessKey && attribute.mandatory && attribute.mapping == null);
		}
	}

	/**
	 * {@link StagingRules#backfillPrimaryKey}: only an origin RULE, sourceSystem/sourceId row with primaryKey still unset is backfilled -
	 * every other row (already set, wrong name, wrong origin) is left alone (PLAN.md, 2026-09-20 staging pass).
	 */
	private static void backfillPrimaryKeyOnlyTouchesUnsetRuleTechnicalAttributes()
	{
		final List<StagingRules.ExistingAttribute> existing = Arrays.asList(
			new StagingRules.ExistingAttribute("unset-source-system", "sourceSystem", false, false, false, false, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("unset-source-id", "sourceId", false, false, false, false, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("already-set", "sourceSystem", false, false, true, true, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("wrong-name", "contactId", false, false, false, false, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("wrong-origin", "sourceId", false, false, false, false, "PROPOSED", "USER"));
		final List<StagingRules.PrimaryKeyBackfill> backfills = StagingRules.backfillPrimaryKey(existing);
		StagingRulesTest.check("only the two unset RULE technical attributes are backfilled: " + backfills.size(), backfills.size() == 2);
		final List<String> ids = new ArrayList<String>();
		for (final StagingRules.PrimaryKeyBackfill backfill : backfills)
		{
			ids.add(backfill.attributeId);
		}
		StagingRulesTest.check("the right two rows", ids.contains("unset-source-system") && ids.contains("unset-source-id"));
	}

	/**
	 * {@link StagingRules#alignTechnicalKeyStatus}: the exact live defect (PLAN.md, 2026-09-20, project {@code Companies}) - {@code
	 * Account} accepted through the review screen before the cascade existed, its {@code sourceSystem}/{@code sourceId} still {@code
	 * PROPOSED}. A re-run of {@code staging/suggest} must align them to {@code ACCEPTED} (the entity's own current status), leave a
	 * business attribute (not primaryKey) alone whatever its status, never touch an already-{@code APPLIED} technical key, never touch one
	 * already at the target status, and propose nothing at all for an entity still {@code PROPOSED} (nothing to align to yet).
	 */
	private static void alignTechnicalKeyStatusToTheEntitysCurrentStatus()
	{
		final List<StagingRules.ExistingAttribute> accountAttributes = Arrays.asList(
			new StagingRules.ExistingAttribute("account-sourceSystem", "sourceSystem", false, false, true, true, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("account-sourceId", "sourceId", false, false, true, true, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("account-name", "name", false, false, false, false, "PROPOSED", "RULE"));
		final List<StagingRules.TechnicalKeyStatusBackfill> accepted = StagingRules.alignTechnicalKeyStatus("ACCEPTED", accountAttributes);
		StagingRulesTest.check("both technical-key rows aligned to ACCEPTED, the business attribute untouched", accepted.size() == 2);
		for (final StagingRules.TechnicalKeyStatusBackfill backfill : accepted)
		{
			StagingRulesTest.check("target is ACCEPTED", "ACCEPTED".equals(backfill.targetStatus));
			StagingRulesTest.check("only the two technical-key rows", backfill.attributeId.startsWith("account-source"));
		}

		// entity APPLIED (already generated): still ACCEPTED, never speculatively APPLIED
		final List<StagingRules.TechnicalKeyStatusBackfill> applied = StagingRules.alignTechnicalKeyStatus("APPLIED", accountAttributes);
		StagingRulesTest.check("entity APPLIED still cascades to ACCEPTED, not APPLIED", applied.size() == 2 && "ACCEPTED".equals(applied.get(0).targetStatus));

		// entity REJECTED cascades REJECTED
		final List<StagingRules.TechnicalKeyStatusBackfill> rejected = StagingRules.alignTechnicalKeyStatus("REJECTED", accountAttributes);
		StagingRulesTest.check("entity REJECTED cascades REJECTED", rejected.size() == 2 && "REJECTED".equals(rejected.get(0).targetStatus));

		// entity still PROPOSED: nothing to align to yet
		StagingRulesTest.check("entity PROPOSED: no backfill at all", StagingRules.alignTechnicalKeyStatus("PROPOSED", accountAttributes).isEmpty());

		// already aligned, and already APPLIED: neither is touched again
		final List<StagingRules.ExistingAttribute> alreadyDone = Arrays.asList(
			new StagingRules.ExistingAttribute("already-accepted", "sourceSystem", false, false, true, true, "ACCEPTED", "RULE"),
			new StagingRules.ExistingAttribute("already-applied", "sourceId", false, false, true, true, "APPLIED", "RULE"));
		StagingRulesTest.check("nothing left to align: one already ACCEPTED, one already APPLIED (untouchable)",
			StagingRules.alignTechnicalKeyStatus("ACCEPTED", alreadyDone).isEmpty());
	}

	/**
	 * {@link StagingRules#isMissingTechnicalKey}: true only when NO existing row carries {@code primaryKey=true} - the round-4 live case,
	 * {@code Company} (project {@code Companies}, 2026-09-20): a canonical entity accepted with 6 business attributes but no technical key
	 * row at all. A row that merely has the flag unset (the case {@link StagingRules#backfillPrimaryKey} already covers) does NOT count as
	 * missing once at least one row IS flagged true; an entity with no attribute rows whatsoever also counts as missing.
	 */
	private static void isMissingTechnicalKeyDetectsNoPrimaryKeyRowAtAll()
	{
		final List<StagingRules.ExistingAttribute> companyLikeNoKeyAtAll = Arrays.asList(
			new StagingRules.ExistingAttribute("company-name", "name", false, false, false, false, "ACCEPTED", "LLM"),
			new StagingRules.ExistingAttribute("company-revenue", "annualRevenue", false, false, false, false, "ACCEPTED", "LLM"));
		StagingRulesTest.check("no row at all is primaryKey=true: missing", StagingRules.isMissingTechnicalKey(companyLikeNoKeyAtAll));

		final List<StagingRules.ExistingAttribute> unsetButNotMissing = Arrays.asList(
			new StagingRules.ExistingAttribute("unset-source-system", "sourceSystem", false, false, false, false, "PROPOSED", "RULE"),
			new StagingRules.ExistingAttribute("some-key", "someOtherKey", false, false, true, true, "PROPOSED", "RULE"));
		StagingRulesTest.check("one row IS flagged true elsewhere: not missing, even though another row's flag is merely unset",
			!StagingRules.isMissingTechnicalKey(unsetButNotMissing));

		final List<StagingRules.ExistingAttribute> properlyKeyed = Arrays.asList(
			new StagingRules.ExistingAttribute("source-system", "sourceSystem", false, false, true, true, "ACCEPTED", "RULE"),
			new StagingRules.ExistingAttribute("source-id", "sourceId", false, false, true, true, "ACCEPTED", "RULE"));
		StagingRulesTest.check("both technical-key rows present and flagged: not missing", !StagingRules.isMissingTechnicalKey(properlyKeyed));

		StagingRulesTest.check("no attribute rows at all: missing", StagingRules.isMissingTechnicalKey(new ArrayList<StagingRules.ExistingAttribute>()));
	}

	/**
	 * {@link StagingRules#technicalKeyBackfillProposals}: the same shape as {@link StagingRules#technicalAttributeProposals} (primaryKey,
	 * not businessKey, mandatory, no mapping), with a rationale that explains sourceSystem/sourceId for a multi-source canonical entity.
	 */
	private static void technicalKeyBackfillProposalsMatchTheOrdinaryTechnicalKeyShape()
	{
		final List<StagingRules.AttributeProposal> backfill = StagingRules.technicalKeyBackfillProposals();
		StagingRulesTest.check("exactly sourceSystem and sourceId", backfill.size() == 2 && "sourceSystem".equals(backfill.get(0).name)
			&& "sourceId".equals(backfill.get(1).name));
		for (final StagingRules.AttributeProposal attribute : backfill)
		{
			StagingRulesTest.check(attribute.name + " is primaryKey, not businessKey, mandatory, no mapping",
				attribute.primaryKey && !attribute.businessKey && attribute.mandatory && attribute.mapping == null);
			StagingRulesTest.check(attribute.name + " rationale explains the multi-source case",
				attribute.rationale.contains("one source row") || attribute.rationale.contains("one Supplier row"));
		}
	}

	/** {@link StagingRules#technicalKeyBackfillStatus}: cascades like {@link StagingRules#alignTechnicalKeyStatus}, except PROPOSED (nothing to cascade to yet) defaults to PROPOSED rather than nothing. */
	private static void technicalKeyBackfillStatusFollowsTheEntityOrDefaultsToProposed()
	{
		StagingRulesTest.check("ACCEPTED entity -> ACCEPTED key", "ACCEPTED".equals(StagingRules.technicalKeyBackfillStatus("ACCEPTED")));
		StagingRulesTest.check("APPLIED entity -> ACCEPTED key (never speculatively APPLIED)", "ACCEPTED".equals(StagingRules.technicalKeyBackfillStatus("APPLIED")));
		StagingRulesTest.check("REJECTED entity -> REJECTED key", "REJECTED".equals(StagingRules.technicalKeyBackfillStatus("REJECTED")));
		StagingRulesTest.check("PROPOSED entity -> PROPOSED key (default, not null)", "PROPOSED".equals(StagingRules.technicalKeyBackfillStatus("PROPOSED")));
	}

	/**
	 * {@link StagingRules#alignMappingStatus}: the exact live defect (PLAN.md, 2026-09-20, project {@code Companies}) - 62 {@code
	 * AttributeMapping} rows still {@code PROPOSED} even though every one of their attributes is {@code APPLIED}. Only an eligible row
	 * (still PROPOSED, origin RULE or LLM) is backfilled - a USER mapping and an already-decided one (by anyone) are left alone - and an
	 * attribute still PROPOSED yields no backfill at all.
	 */
	private static void alignMappingStatusFollowsTheAttributesCurrentStatus()
	{
		final List<StagingRules.ExistingMapping> mappings = Arrays.asList(new StagingRules.ExistingMapping("m-rule", "PROPOSED", "RULE"),
			new StagingRules.ExistingMapping("m-llm", "PROPOSED", "LLM"), new StagingRules.ExistingMapping("m-user", "PROPOSED", "USER"),
			new StagingRules.ExistingMapping("m-already-accepted", "ACCEPTED", "RULE"),
			new StagingRules.ExistingMapping("m-person-rejected", "REJECTED", "RULE"), new StagingRules.ExistingMapping("m-applied", "APPLIED", "RULE"));

		final List<StagingRules.MappingStatusBackfill> accepted = StagingRules.alignMappingStatus("APPLIED", mappings);
		StagingRulesTest.check("attribute APPLIED: exactly the two eligible (PROPOSED + RULE/LLM) rows backfilled to ACCEPTED, never APPLIED",
			accepted.size() == 2);
		final List<String> acceptedIds = new ArrayList<String>();
		for (final StagingRules.MappingStatusBackfill backfill : accepted)
		{
			StagingRulesTest.check("target is ACCEPTED, not APPLIED", "ACCEPTED".equals(backfill.targetStatus));
			acceptedIds.add(backfill.mappingId);
		}
		StagingRulesTest.check("the right two rows, USER/already-decided ones untouched", acceptedIds.contains("m-rule") && acceptedIds.contains("m-llm")
			&& !acceptedIds.contains("m-user") && !acceptedIds.contains("m-already-accepted") && !acceptedIds.contains("m-person-rejected")
			&& !acceptedIds.contains("m-applied"));

		final List<StagingRules.MappingStatusBackfill> rejected = StagingRules.alignMappingStatus("REJECTED", mappings);
		StagingRulesTest.check("attribute REJECTED: the two eligible rows cascade to REJECTED", rejected.size() == 2
			&& "REJECTED".equals(rejected.get(0).targetStatus) && "REJECTED".equals(rejected.get(1).targetStatus));

		StagingRulesTest.check("attribute still PROPOSED: nothing to align yet", StagingRules.alignMappingStatus("PROPOSED", mappings).isEmpty());
	}

	private static StagingRules.AttributeProposal attributeProposal(final String pName, final String pFromRelationId)
	{
		return new StagingRules.AttributeProposal(pName, pName, "STRING", null, false, false, false, false, pFromRelationId == null ? null : "SomeEntity",
			pFromRelationId, 90, "rationale", null);
	}

	// --------------------------------------------------------------------------------------------------------- fixtures

	private static List<StagingRules.LandingColumnInfo> supplierColumns()
	{
		final List<StagingRules.LandingColumnInfo> out = new ArrayList<StagingRules.LandingColumnInfo>();
		out.add(StagingRulesTest.col("SUPPLIER_ID", "INTEGER", true));
		out.add(StagingRulesTest.col("NAME1", "STRING", false));
		out.add(StagingRulesTest.col("NAME2", "STRING", false));
		out.add(StagingRulesTest.col("STREET", "STRING", false));
		out.add(StagingRulesTest.col("CITY", "STRING", false));
		out.add(StagingRulesTest.col("POSTAL_CODE", "STRING", false));
		out.add(StagingRulesTest.col("COUNTRY", "STRING", false));
		out.add(StagingRulesTest.col("REGION", "STRING", false));
		out.add(StagingRulesTest.col("PHONE", "STRING", false));
		out.add(StagingRulesTest.col("FAX", "STRING", false));
		out.add(StagingRulesTest.col("EMAIL", "STRING", false));
		out.add(StagingRulesTest.col("VAT_NUMBER", "STRING", false));
		out.add(StagingRulesTest.col("CURRENCY", "STRING", false));
		out.add(StagingRulesTest.col("PAYMENT_TERMS", "STRING", false));
		out.add(StagingRulesTest.col("BANK_ACCOUNT", "STRING", false));
		out.add(StagingRulesTest.col("IBAN", "STRING", false));
		out.add(StagingRulesTest.col("PURCHASING_GROUP", "STRING", false));
		out.add(StagingRulesTest.col("INCOTERMS", "STRING", false));
		out.add(StagingRulesTest.col("PURCHASING_BLOCK", "BOOLEAN", false));
		out.add(StagingRulesTest.col("DELETION_FLAG", "BOOLEAN", false));
		out.add(StagingRulesTest.col("CHANGED_AT", "TIMESTAMP", false));
		out.add(StagingRulesTest.col("CREATED_BY", "STRING", false));
		StagingRulesTest.check("SUPPLIER has 22 columns", out.size() == 22);
		return out;
	}

	private static List<StagingRules.LandingColumnInfo> accountColumns()
	{
		final List<StagingRules.LandingColumnInfo> out = new ArrayList<StagingRules.LandingColumnInfo>();
		out.add(StagingRulesTest.col("ACCOUNT_ID", "INTEGER", true));
		out.add(StagingRulesTest.col("PARENT_ACCOUNT_ID", "INTEGER", false));
		out.add(StagingRulesTest.col("NAME", "STRING", false));
		out.add(StagingRulesTest.col("INDUSTRY", "STRING", false));
		out.add(StagingRulesTest.col("ANNUAL_REVENUE", "DECIMAL", false));
		out.add(StagingRulesTest.col("BILLING_STREET", "STRING", false));
		out.add(StagingRulesTest.col("BILLING_CITY", "STRING", false));
		out.add(StagingRulesTest.col("BILLING_COUNTRY", "STRING", false));
		out.add(StagingRulesTest.col("SHIPPING_STREET", "STRING", false));
		out.add(StagingRulesTest.col("SHIPPING_CITY", "STRING", false));
		out.add(StagingRulesTest.col("SHIPPING_COUNTRY", "STRING", false));
		out.add(StagingRulesTest.col("PHONE", "STRING", false));
		out.add(StagingRulesTest.col("WEBSITE", "STRING", false));
		out.add(StagingRulesTest.col("OWNER", "STRING", false));
		out.add(StagingRulesTest.col("STATUS", "STRING", false));
		out.add(StagingRulesTest.col("TYPE", "STRING", false));
		out.add(StagingRulesTest.col("CREATED_ON", "DATE", false));
		out.add(StagingRulesTest.col("RATING", "STRING", false));
		StagingRulesTest.check("ACCOUNT has 18 columns", out.size() == 18);
		return out;
	}

	private static List<StagingRules.LandingColumnInfo> contactColumns()
	{
		final List<StagingRules.LandingColumnInfo> out = new ArrayList<StagingRules.LandingColumnInfo>();
		out.add(StagingRulesTest.col("CONTACT_ID", "INTEGER", true));
		out.add(StagingRulesTest.col("ACCOUNT_ID", "INTEGER", false));
		out.add(StagingRulesTest.col("FIRST_NAME", "STRING", false));
		out.add(StagingRulesTest.col("LAST_NAME", "STRING", false));
		out.add(StagingRulesTest.col("EMAIL", "STRING", false));
		out.add(StagingRulesTest.col("PHONE", "STRING", false));
		out.add(StagingRulesTest.col("TITLE", "STRING", false));
		out.add(StagingRulesTest.col("HAS_OPTED_OUT_OF_EMAIL", "BOOLEAN", false));
		out.add(StagingRulesTest.col("CREATED_DATE", "TIMESTAMP", false));
		out.add(StagingRulesTest.col("LAST_MODIFIED_DATE", "TIMESTAMP", false));
		out.add(StagingRulesTest.col("LEAD_SOURCE", "STRING", false));
		StagingRulesTest.check("CONTACT has 11 columns", out.size() == 11);
		return out;
	}

	private static StagingRules.LandingColumnInfo col(final String pSourceColumn, final String pInferredType, final boolean pSourceKey)
	{
		// the real demo has NO profile at all on these tables: fillRate, distinctCount and maxLength all null
		return new StagingRules.LandingColumnInfo(pSourceColumn, pSourceColumn, pSourceColumn.toLowerCase(Locale.ROOT), pInferredType, pSourceKey, null, null,
			null, null);
	}

	private static StagingRules.LandingTableInfo table(final String pId, final String pSourceSystem, final String pName, final Long pRowCount,
		final List<StagingRules.LandingColumnInfo> pColumns)
	{
		return new StagingRules.LandingTableInfo(pId, pSourceSystem, pName, pRowCount, pColumns);
	}

	private static StagingRules.EntityProposal entity(final StagingRules.Result pResult, final String pName)
	{
		for (final StagingRules.EntityProposal e : pResult.entities)
		{
			if (e.name.equals(pName))
			{
				return e;
			}
		}
		return null;
	}

	private static StagingRules.AttributeProposal attribute(final StagingRules.EntityProposal pEntity, final String pName)
	{
		for (final StagingRules.AttributeProposal a : pEntity.attributes)
		{
			if (a.name.equals(pName))
			{
				return a;
			}
		}
		throw new AssertionError("No attribute " + pName + " on entity " + pEntity.name);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
