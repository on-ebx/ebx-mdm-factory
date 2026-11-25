package com.on.ebx.factory.ebx;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import com.on.ebx.factory.distribution.DistributionBatches;

/** JUnit-free. F2-05: {@code connectors_publish} has no async, so its read timeout is proportional to the rows (the distribution send's rule), capped at 2 h. */
public final class DataProductPublicationTimeoutTest
{
	public static void main(final String[] pArgs)
	{
		final long perRow = DistributionBatches.perRowMs();
		DataProductPublicationTimeoutTest.check("no table: the 300 s base", DataProductPublication.publishTimeoutMs(Collections.<Long>emptyList()) == 300000);
		final List<Long> big = Arrays.asList(Long.valueOf(150000L));
		final int bigMs = DataProductPublication.publishTimeoutMs(big);
		DataProductPublicationTimeoutTest.check("150,000 rows wait longer than a fixed 300 s: " + bigMs, bigMs >= 300000L + 150000L * perRow || bigMs == 7200000);
		DataProductPublicationTimeoutTest.check("rows of several tables add up", DataProductPublication.publishTimeoutMs(Arrays.asList(Long.valueOf(1000L), Long.valueOf(2000L)))
			== DistributionBatches.sendTimeoutMs(3000));
		DataProductPublicationTimeoutTest.check("an unknown count is a full batch, not zero", DataProductPublication.publishTimeoutMs(Arrays.asList(Long.valueOf(-1L)))
			== DistributionBatches.sendTimeoutMs(DistributionBatches.DEFAULT_MAX_ROWS));
		DataProductPublicationTimeoutTest.check("capped at 2 hours", DataProductPublication.publishTimeoutMs(Arrays.asList(Long.valueOf(Long.MAX_VALUE / 4))) == 7200000);
		System.out.println("DataProductPublicationTimeoutTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
