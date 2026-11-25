package com.on.ebx.factory.ebx;

import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.FakeSchemaTest;
import com.on.ebx.factory.core.WriterGuard.Kind;
import com.on.ebx.factory.master.MameMetadataContract;
import com.on.ebx.factory.master.MameSchemaWatch;
import com.orchestranetworks.schema.SchemaNode;

/**
 * Audit F-27: {@link MameMetadataGuard} (the one door to the add-on's metadataset tables) and {@link MameStateCodes} (the integer state codes against the
 * add-on's own {@code State} enumeration - read from the add-on jar of the test classpath, i.e. a typed pin of the codes). The metadataset and its tables
 * are stand-ins; no repository. JUnit-free: run the main.
 */
public final class MameMetadataGuardTest
{
	public static void main(final String[] pArgs)
	{
		MameMetadataGuardTest.stateCodesAgainstTheRealEnumeration();
		MameMetadataGuardTest.noMetadatasetIsQuiet();
		MameMetadataGuardTest.missingTableIsReported();
		MameMetadataGuardTest.missingRequiredFieldIsReportedAndRefused();
		MameMetadataGuardTest.missingExpectedFieldIsReportedButReadable();
		MameMetadataGuardTest.aTableThatMatchesIsReadAndQuiet();
		MameMetadataGuardTest.aTableWithoutContractOnlyHasToExist();
		MameSchemaWatch.clearAll();
		System.out.println("MameMetadataGuardTest OK");
	}

	private static void stateCodesAgainstTheRealEnumeration()
	{
		final String real = MameStateCodes.compute(MameStateCodes.class.getClassLoader());
		check("the module's state names are the add-on's State enumeration, in order (a build that inserts a state breaks this): " + real, real.isEmpty());
		check("the add-on class is really on the test classpath (else the line above proves nothing)", MameMetadataGuardTest.addonStateIsLoadable());
		check("the codes agree with the module's constants", MameMetadataContract.STATE_NAMES.get(MameMetadataContract.STATE_MERGED).equals("MERGED"));
		check("no add-on class: nothing to check, no complaint", MameStateCodes.compute(new ClassLoader(null)
		{
		}).isEmpty());
		final String shifted = MameStateCodes.compare(new Object[] { Fake.UNSET, Fake.NEW_ONE, Fake.GOLDEN, Fake.MERGED, Fake.PENDING_APPROVAL, Fake.DELETED });
		check("an inserted state is reported: " + shifted, shifted.contains("has 6 values"));
		final String renamed = MameStateCodes.compare(new Object[] { Fake.UNSET, Fake.GOLDEN, Fake.MERGED, Fake.PENDING_APPROVAL, Fake.NEW_ONE });
		check("a renamed state is reported: " + renamed, renamed.contains("NEW_ONE") && renamed.contains("expects"));
	}

	private enum Fake
	{
		UNSET, GOLDEN, MERGED, PENDING_APPROVAL, DELETED, NEW_ONE
	}

	private static boolean addonStateIsLoadable()
	{
		try
		{
			Class.forName("com.orchestranetworks.addon.mame.model.State");
			return true;
		}
		catch (final ClassNotFoundException ex)
		{
			return false;
		}
	}

	private static void noMetadatasetIsQuiet()
	{
		MameSchemaWatch.clearAll();
		check("no metadataset (matching never activated): null", MameMetadataGuard.table(null, "RecordMetadata") == null);
		check("and no finding", !MameSchemaWatch.any());
	}

	private static void missingTableIsReported()
	{
		MameSchemaWatch.clearAll();
		check("a metadataset without the table: null", MameMetadataGuardTest.resolve("A_MDS", null, "RecordMetadata") == null);
		check("reported once, naming the metadataset, the table and the problem", MameSchemaWatch.findingsOf("A_MDS").size() == 1
			&& MameSchemaWatch.details("A_MDS").contains("A_MDS/RecordMetadata: the table does not exist"));
		MameMetadataGuardTest.resolve("A_MDS", null, "RecordMetadata");
		check("asked again: still one finding", MameSchemaWatch.findingsOf("A_MDS").size() == 1);
		check("the visible state says unavailable, in both languages", MameSchemaWatch.summary(Locale.ENGLISH, "A_MDS").contains("unavailable")
			&& MameSchemaWatch.summary(Locale.FRENCH, "A_MDS").contains("indisponibles"));
		check("the reason text of a reader that answers unavailable carries it", MameMetadataGuard.reasonSuffix().contains("A_MDS/RecordMetadata"));
	}

	private static void missingRequiredFieldIsReportedAndRefused()
	{
		MameSchemaWatch.clearAll();
		final SchemaNode table = MameMetadataGuardTest.tableWith("functionalId", "goldenId", "groupId", "mergingProcessId", "hasSuspects",
			"suspectedCount", "autoCreated"); // "state" was renamed
		check("a required field gone: the reader gets null", MameMetadataGuardTest.resolve("B_MDS", table, "RecordMetadata") == null);
		check("and the finding names the field", MameSchemaWatch.details("B_MDS").contains("the field state does not exist"));
	}

	private static void missingExpectedFieldIsReportedButReadable()
	{
		MameSchemaWatch.clearAll();
		final SchemaNode table = MameMetadataGuardTest.tableWith("functionalId", "state", "groupId", "mergingProcessId", "hasSuspects",
			"suspectedCount", "autoCreated"); // goldenId gone
		check("an expected field gone: the table is still handed out", MameMetadataGuardTest.resolve("C_MDS", table, "RecordMetadata") != null);
		check("but it is reported", MameSchemaWatch.details("C_MDS").contains("the field goldenId does not exist"));
	}

	private static void aTableThatMatchesIsReadAndQuiet()
	{
		MameSchemaWatch.clearAll();
		final SchemaNode table = MameMetadataGuardTest.tableWith("functionalId", "state", "goldenId", "groupId", "mergingProcessId", "hasSuspects", "suspectedCount", "autoCreated");
		check("a table with every field: handed out", MameMetadataGuardTest.resolve("D_MDS", table, "RecordMetadata") != null);
		check("and nothing is reported", !MameSchemaWatch.any());
	}

	private static void aTableWithoutContractOnlyHasToExist()
	{
		MameSchemaWatch.clearAll();
		check("a table nobody reads by field: handed out", MameMetadataGuardTest.resolve("E_MDS", MameMetadataGuardTest.tableWith(), "Matched") != null && !MameSchemaWatch.any());
	}

	// ------------------------------------------------------------------------------------------------------ stand-ins

	/** A table stand-in: its row schema, with the given fields (the EBX table type cannot be faked, the guard reads it through {@link MameMetadataGuard.Source}). */
	private static SchemaNode tableWith(final String... pFields)
	{
		final Map<String, SchemaNode> nodes = FakeSchemaTest.map();
		for (final String field : pFields)
		{
			nodes.put("./" + field, FakeSchemaTest.node(Kind.STRING));
		}
		return FakeSchemaTest.root(nodes);
	}

	/** A metadataset stand-in that has the one table given (null = no table at all: asking for one throws, as EBX does for an unknown path). */
	private static MameMetadataGuard.Source<SchemaNode> dataset(final SchemaNode pTable)
	{
		return new MameMetadataGuard.Source<SchemaNode>()
		{
			@Override
			public SchemaNode table(final String pTableName)
			{
				if (pTable == null)
				{
					throw new IllegalArgumentException("no such table");
				}
				return pTable;
			}

			@Override
			public SchemaNode rowSchema(final SchemaNode pTable)
			{
				return pTable;
			}
		};
	}

	private static SchemaNode resolve(final String pName, final SchemaNode pTable, final String pTableName)
	{
		return MameMetadataGuard.resolve(pName, MameMetadataGuardTest.dataset(pTable), pTableName);
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
