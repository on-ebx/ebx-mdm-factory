package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JUnit-free, like the sibling tests: run the main, a failure throws. Exercises {@link
 * FieldMergeLineageSource#readFromItems} - the pure matching/rule-building seam split out 2026-09-22 (see that
 * class' own Javadoc, "corrected against a REAL row") - against a fixture reproducing the coordinator's live
 * read of a real {@code MergeValueLineageCompact} row (group merged from 290/248 into golden 322, dataspace
 * {@code Companies_Master}, dataset {@code Master}, table {@code /root/Company}, 2026-09-22).
 * <p>
 * The fixture ({@link FakeItem}) is a PLAIN Java object - no EBX/add-on type anywhere - exposing exactly the
 * five accessor names {@code FieldMergeLineageSource#stringProperty}/{@code #booleanProperty} look up
 * reflectively ({@code getFieldPath}, {@code getRecordId}, {@code getMergeFunction}, {@code
 * isModifiedByDataSteward}, {@code isManualModified}): the same contract a real add-on {@code
 * MergeValueLineageItem} bean satisfies, so this test exercises the REAL matching logic, not a mock of it.
 */
public final class FieldMergeLineageSourceTest
{
	public static void main(final String[] pArgs)
	{
		FieldMergeLineageSourceTest.realRowShape_winnerIdentifiedByRecordIdAgainstFieldPath();
		FieldMergeLineageSourceTest.technicalField_nullMergeFunction_answersRuleNotNamed();
		FieldMergeLineageSourceTest.pkField_recordIdIsTheGolden_notAContributor_answersUnavailable();
		FieldMergeLineageSourceTest.unknownFieldPath_answersUnavailable();
		FieldMergeLineageSourceTest.sourceValueIsNeverConsulted_evenWhenPresentAndMatching();
		FieldMergeLineageSourceTest.modifiedByDataSteward_overridesTheRuleSentence();
		FieldMergeLineageSourceTest.manualModified_withoutDataSteward_appendsManuallyAdjusted();
		FieldMergeLineageSourceTest.manualModified_withDataSteward_doesNotDoubleAppend();
		FieldMergeLineageSourceTest.modelWithoutItemsNodes_isExplicitlyUnreadable_notEmpty();
		System.out.println("FieldMergeLineageSourceTest OK");
	}

	/** The exact shape read live 2026-09-22 (class Javadoc): sourceSystem retained from 290, supplierId from 248, both "longest". */
	private static void realRowShape_winnerIdentifiedByRecordIdAgainstFieldPath()
	{
		final List<FakeItem> items = FieldMergeLineageSourceTest.realRow();
		final Map<String, String> contributing = FieldMergeLineageSourceTest.contributingValues();

		final FieldMergeLineageSource.Result sourceSystem = FieldMergeLineageSource.readFromItems(items, "sourceSystem", contributing, FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("sourceSystem available", sourceSystem.available);
		FieldMergeLineageSourceTest.check("sourceSystem retained from 290", "290".equals(sourceSystem.knownRetainedMasterKey));
		FieldMergeLineageSourceTest.check("sourceSystem rule is 'longest value'", "longest value".equals(sourceSystem.survivorshipRule));

		final FieldMergeLineageSource.Result supplierId = FieldMergeLineageSource.readFromItems(items, "supplierId", contributing, FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("supplierId available", supplierId.available);
		FieldMergeLineageSourceTest.check("supplierId retained from 248", "248".equals(supplierId.knownRetainedMasterKey));
	}

	/** `/id` and `/_receivedAt` carry a null mergeFunction in the real row - never dropped, shown as "rule not named". */
	private static void technicalField_nullMergeFunction_answersRuleNotNamed()
	{
		final List<FakeItem> items = FieldMergeLineageSourceTest.realRow();
		// _receivedAt's own recordId (290) IS a contributing key, unlike /id (see the next test) - a technical
		// field can still be "available" once its winner is a real contributor.
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(items, "_receivedAt", FieldMergeLineageSourceTest.contributingValues(), FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("available", result.available);
		FieldMergeLineageSourceTest.check("rule not named for a null mergeFunction", "recorded by the merge, rule not named".equals(result.survivorshipRule));
	}

	/** `/id`'s own recordId is the GOLDEN's own key (322) - never one of the caller's contributing keys - so it must answer unavailable, never a wrong guess. */
	private static void pkField_recordIdIsTheGolden_notAContributor_answersUnavailable()
	{
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(FieldMergeLineageSourceTest.realRow(), "id",
			FieldMergeLineageSourceTest.contributingValues(), FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("unavailable - 322 is the golden, not a contributor", !result.available);
	}

	private static void unknownFieldPath_answersUnavailable()
	{
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(FieldMergeLineageSourceTest.realRow(), "thisFieldDoesNotExist",
			FieldMergeLineageSourceTest.contributingValues(), FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("unavailable - no item carries this fieldPath", !result.available);
	}

	/**
	 * The real row's own {@code sourceValue} is null on every item (class Javadoc) - this fixture instead sets
	 * a sourceValue that WOULD match a contribution's value, to prove the old strategy-2 fallback is gone: only
	 * {@code recordId} is ever consulted now, never {@code sourceValue}.
	 */
	private static void sourceValueIsNeverConsulted_evenWhenPresentAndMatching()
	{
		final FakeItem item = new FakeItem("/name", "999", "longest", false, false); // recordId 999 is not a contributor
		final Map<String, String> contributing = FieldMergeLineageSourceTest.contributingValues(); // has "290" -> "BPCE"
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(Arrays.asList(item), "name", contributing, FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("unavailable - a matching sourceValue on an unrelated item must never substitute for recordId", !result.available);
	}

	private static void modifiedByDataSteward_overridesTheRuleSentence()
	{
		final FakeItem item = new FakeItem("/city", "290", "longest", true, false);
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(Arrays.asList(item), "city", FieldMergeLineageSourceTest.contributingValues(), FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("available", result.available);
		FieldMergeLineageSourceTest.check("data steward decision overrides the function word", "data steward decision".equals(result.survivorshipRule));
	}

	private static void manualModified_withoutDataSteward_appendsManuallyAdjusted()
	{
		final FakeItem item = new FakeItem("/city", "290", "longest", false, true);
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(Arrays.asList(item), "city", FieldMergeLineageSourceTest.contributingValues(), FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("manually adjusted appended", "longest value (manually adjusted)".equals(result.survivorshipRule));
	}

	private static void manualModified_withDataSteward_doesNotDoubleAppend()
	{
		final FakeItem item = new FakeItem("/city", "290", "longest", true, true);
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.readFromItems(Arrays.asList(item), "city", FieldMergeLineageSourceTest.contributingValues(), FieldMergeLineageSourceTest.FIELDS);
		FieldMergeLineageSourceTest.check("data steward decision wins outright", "data steward decision".equals(result.survivorshipRule));
	}

	// --------------------------------------------------------------------------------------------------- fixtures

	/**
	 * A representative slice of the real row read live 2026-09-22 (class Javadoc quotes the full JSON) -
	 * mergingProcessId/table-level fields are not part of this fixture (that half is EBX-bound, in {@link
	 * FieldMergeLineageSource#read} itself, not {@link FieldMergeLineageSource#readFromItems}).
	 */
	private static List<FakeItem> realRow()
	{
		return Arrays.asList(
			new FakeItem("/sourceSystem", "290", "longest", false, false),
			new FakeItem("/sourceId", "248", "longest", false, false),
			new FakeItem("/name", "290", "longest", false, false),
			new FakeItem("/_receivedAt", "290", null, false, false),
			new FakeItem("/cityKey", "290", "longest", false, false),
			new FakeItem("/country", "290", "longest", false, false),
			new FakeItem("/city", "290", "longest", false, false),
			new FakeItem("/id", "322", null, false, false),
			new FakeItem("/_loadId", "290", "longest", false, false),
			new FakeItem("/legalForm", "290", "longest", false, false),
			new FakeItem("/vatNo", "290", "longest", false, false),
			new FakeItem("/supplierId", "248", "longest", false, false),
			new FakeItem("/nameKey", "290", "longest", false, false));
	}

	/** The caller's own contributing master keys (290, 248) - 322 (the golden) is deliberately absent, matching {@code ebx.RecordLineageReader}'s own {@code resolveSource} (no Xref row for an auto-created golden - never a contribution). */
	private static Map<String, String> contributingValues()
	{
		final Map<String, String> values = new LinkedHashMap<String, String>();
		values.put("290", "BPCE");
		values.put("248", "supplier-001");
		return values;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	/** F2-08: a build whose metadataset model lacks the Items nodes is "unreadable, and why", never an empty answer taken for "nothing merged". */
	private static void modelWithoutItemsNodes_isExplicitlyUnreadable_notEmpty()
	{
		boolean refused = false;
		try
		{
			FieldMergeLineageSource.schemaFields(null);
		}
		catch (final FieldMergeLineageSource.ItemsUnreadableException ex)
		{
			refused = ex.getMessage().contains("Items");
		}
		FieldMergeLineageSourceTest.check("a missing Items node is refused with its name", refused);
		final FieldMergeLineageSource.Result result = FieldMergeLineageSource.Result.unreadable("no Items/recordId node");
		FieldMergeLineageSourceTest.check("unreadable is not available", !result.available);
		FieldMergeLineageSourceTest.check("unreadable carries its reason", "no Items/recordId node".equals(result.unreadableReason));
		FieldMergeLineageSourceTest.check("plain unavailable has no reason", FieldMergeLineageSource.Result.unavailable().unreadableReason == null);
	}

	/** F2-08: a simulated metadataset reads its items by node name, as the schema nodes do in production - no reflection, no add-on class. */
	private static final FieldMergeLineageSource.ItemFields FIELDS = new FieldMergeLineageSource.ItemFields()
	{
		@Override
		public Object get(final Object pItem, final String pNode)
		{
			final FakeItem item = (FakeItem) pItem;
			if ("fieldPath".equals(pNode))
			{
				return item.fieldPath;
			}
			if ("recordId".equals(pNode))
			{
				return item.recordId;
			}
			if ("mergeFunction".equals(pNode))
			{
				return item.mergeFunction;
			}
			if ("modifiedByDataSteward".equals(pNode))
			{
				return Boolean.valueOf(item.modifiedByDataSteward);
			}
			if ("manualModified".equals(pNode))
			{
				return Boolean.valueOf(item.manualModified);
			}
			throw new IllegalArgumentException(pNode);
		}
	};

	/** Plain fixture value holder - no EBX/add-on type, and no accessor: nothing here can be reached by reflection on a getter name. */
	private static final class FakeItem
	{
		private final String fieldPath;
		private final String recordId;
		private final String mergeFunction;
		private final boolean modifiedByDataSteward;
		private final boolean manualModified;

		FakeItem(final String pFieldPath, final String pRecordId, final String pMergeFunction, final boolean pModifiedByDataSteward, final boolean pManualModified)
		{
			this.fieldPath = pFieldPath;
			this.recordId = pRecordId;
			this.mergeFunction = pMergeFunction;
			this.modifiedByDataSteward = pModifiedByDataSteward;
			this.manualModified = pManualModified;
		}
	}
}
