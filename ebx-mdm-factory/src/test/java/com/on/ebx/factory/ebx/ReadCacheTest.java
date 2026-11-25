package com.on.ebx.factory.ebx;

import com.on.ebx.factory.wizard.WizardSnapshot;

/**
 * JUnit-free: run the main, a failure throws. Audit F-33 (2026-10-06): the cache behind the wizard's whole-table aggregates ({@link ReadCache}), the
 * keys that must change with the data,.
 */
public final class ReadCacheTest
{
	public static void main(final String[] pArgs)
	{
		ReadCacheTest.hitMissExpiry();
		ReadCacheTest.bounded();
		ReadCacheTest.keysChangeWithTheData();
		ReadCacheTest.signatureFollowsTheGridInputs();
		System.out.println("ReadCacheTest OK");
	}

	private static void hitMissExpiry()
	{
		final ReadCache<String> cache = new ReadCache<String>(4, 1000L);
		ReadCacheTest.check("miss", cache.get("k", 0L) == null);
		cache.put("k", "v", 100L);
		ReadCacheTest.check("hit", "v".equals(cache.get("k", 1100L)));
		ReadCacheTest.check("expired just past the limit", cache.get("k", 1101L) == null);
		ReadCacheTest.check("an expired entry is gone", cache.size() == 0);
	}

	private static void bounded()
	{
		final ReadCache<Integer> cache = new ReadCache<Integer>(3, 1000L);
		for (int i = 0; i < 10; i++)
		{
			cache.put("k" + i, Integer.valueOf(i), 0L);
		}
		ReadCacheTest.check("bounded", cache.size() == 3);
		ReadCacheTest.check("the oldest went, the newest stayed", cache.get("k0", 0L) == null && cache.get("k9", 0L) != null);
	}

	private static void keysChangeWithTheData()
	{
		final String base = MasterBuildGridReader.heldBackKey("7", "1700");
		ReadCacheTest.check("a reject written or resolved moves the table token and so the key", !base.equals(MasterBuildGridReader.heldBackKey("7", "1701")));
		ReadCacheTest.check("another project, another key", !base.equals(MasterBuildGridReader.heldBackKey("8", "1700")));
		ReadCacheTest.check("same inputs, same key", base.equals(MasterBuildGridReader.heldBackKey("7", "1700")));
		final String counts = CrossSystemMeasures.rowCountsKey("M", "Master", "Company", "U1", "55");
		ReadCacheTest.check("a master write changes the key", !counts.equals(CrossSystemMeasures.rowCountsKey("M", "Master", "Company", "U1", "56")));
		ReadCacheTest.check("another user, another key (the query runs as the session)", !counts.equals(CrossSystemMeasures.rowCountsKey("M", "Master", "Company", "U2", "55")));
		ReadCacheTest.check("another entity, another key", !counts.equals(CrossSystemMeasures.rowCountsKey("M", "Master", "Person", "U1", "55")));
		ReadCacheTest.check("no accidental collision by concatenation", !CrossSystemMeasures.rowCountsKey("a", "bc", "x", "u", "t").equals(CrossSystemMeasures.rowCountsKey("ab", "c", "x", "u", "t")));
	}

	private static WizardSnapshot.StagingEntityInfo entity(final String pId, final String pName, final String pStatus, final int pAttributes)
	{
		final WizardSnapshot.StagingEntityInfo entity = new WizardSnapshot.StagingEntityInfo();
		entity.id = pId;
		entity.name = pName;
		entity.status = pStatus;
		for (int i = 0; i < pAttributes; i++)
		{
			entity.attributes.add(new WizardSnapshot.AttributeInfo());
		}
		return entity;
	}

	private static void signatureFollowsTheGridInputs()
	{
		final WizardSnapshot one = new WizardSnapshot();
		one.stagingEntities.add(ReadCacheTest.entity("1", "Company", "ACCEPTED", 3));
		final String base = StagingLoadGridReader.snapshotSignature(one);
		final WizardSnapshot moreFields = new WizardSnapshot();
		moreFields.stagingEntities.add(ReadCacheTest.entity("1", "Company", "ACCEPTED", 4));
		ReadCacheTest.check("a field added to an entity changes the sublabel, so the key", !base.equals(StagingLoadGridReader.snapshotSignature(moreFields)));
		final WizardSnapshot rejected = new WizardSnapshot();
		rejected.stagingEntities.add(ReadCacheTest.entity("1", "Company", "REJECTED", 3));
		ReadCacheTest.check("an entity that leaves the grid changes the key", !base.equals(StagingLoadGridReader.snapshotSignature(rejected)));
		final WizardSnapshot proposed = new WizardSnapshot();
		proposed.stagingEntities.add(ReadCacheTest.entity("1", "Company", "ACCEPTED", 3));
		proposed.stagingEntities.add(ReadCacheTest.entity("2", "Draft", "PROPOSED", 9));
		ReadCacheTest.check("an entity that is not in the grid does not", base.equals(StagingLoadGridReader.snapshotSignature(proposed)));
		final WizardSnapshot source = new WizardSnapshot();
		source.stagingEntities.add(ReadCacheTest.entity("1", "Company", "ACCEPTED", 3));
		final WizardSnapshot.SourceInfo application = new WizardSnapshot.SourceInfo();
		application.sourceSystem = "ERP";
		source.sources.add(application);
		ReadCacheTest.check("an application adds a column, so changes the key", !base.equals(StagingLoadGridReader.snapshotSignature(source)));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError("ReadCacheTest: " + pWhat);
		}
	}
}
