package com.on.ebx.factory.ebx;

import java.util.concurrent.atomic.AtomicReference;

import com.on.ebx.factory.master.MameMetadataContract;

/**
 * Checks, once per JVM, that the integer codes this module reads from {@code RecordMetadata.state} ({@link MameMetadataContract#STATE_GOLDEN}...) are
 * still the ordinals of the add-on's own {@code com.orchestranetworks.addon.mame.model.State} enumeration (audit F-27). The codes were measured live on
 * 6.3.0 (CLAUDE.md section 8); the add-on stores the ordinal, so a build that inserts a state would shift every later code and every reader would
 * silently count the wrong records as merged or deleted. The check is typed (the enum itself, found by name so that a server without the add-on is not an
 * error: nothing to read then, nothing to check). A mismatch is reported through {@code MameSchemaWatch} by {@link MameMetadataGuard}, which then
 * refuses to hand the table to a reader.
 */
final class MameStateCodes
{
	private static final String STATE_CLASS = "com.orchestranetworks.addon.mame.model.State";
	private static final AtomicReference<String> RESULT = new AtomicReference<String>();
	private static final String CONSISTENT = "";

	private MameStateCodes()
	{
	}

	/** Null when the codes match the add-on's enumeration (or the add-on is absent); otherwise a sentence saying how they differ. Computed once. */
	static String mismatch()
	{
		String value = MameStateCodes.RESULT.get();
		if (value == null)
		{
			value = MameStateCodes.compute(MameStateCodes.class.getClassLoader());
			MameStateCodes.RESULT.set(value);
		}
		return value.isEmpty() ? null : value;
	}

	/** {@link #mismatch()} for a given class loader, uncached (tests). Empty string = consistent. */
	static String compute(final ClassLoader pLoader)
	{
		try
		{
			final Class<?> state = Class.forName(MameStateCodes.STATE_CLASS, false, pLoader);
			return MameStateCodes.compare(state.getEnumConstants());
		}
		catch (final ClassNotFoundException ex)
		{
			return MameStateCodes.CONSISTENT; // no add-on class: no metadataset to read either
		}
		catch (final LinkageError ex)
		{
			return MameStateCodes.CONSISTENT;
		}
		catch (final RuntimeException ex)
		{
			return MameStateCodes.CONSISTENT;
		}
	}

	/** The module's state names against the constants of the add-on's enumeration, in its order (the index IS the stored code). Empty string = they agree. Package-visible for the test. */
	static String compare(final Object[] pConstants)
	{
		if (pConstants == null)
		{
			return MameStateCodes.CONSISTENT;
		}
		final java.util.List<String> expected = MameMetadataContract.STATE_NAMES;
		if (pConstants.length != expected.size())
		{
			return "the add-on's State enumeration has " + pConstants.length + " values, this module knows " + expected.size() + " (" + MameStateCodes.names(pConstants) + ")";
		}
		for (int i = 0; i < pConstants.length; i++)
		{
			if (!expected.get(i).equals(((Enum<?>) pConstants[i]).name()))
			{
				return "the add-on's State enumeration reads " + MameStateCodes.names(pConstants) + ", this module expects " + expected;
			}
		}
		return MameStateCodes.CONSISTENT;
	}

	private static String names(final Object[] pConstants)
	{
		final StringBuilder out = new StringBuilder("[");
		for (int i = 0; i < pConstants.length; i++)
		{
			out.append(i == 0 ? "" : ", ").append(((Enum<?>) pConstants[i]).name());
		}
		return out.append(']').toString();
	}
}
