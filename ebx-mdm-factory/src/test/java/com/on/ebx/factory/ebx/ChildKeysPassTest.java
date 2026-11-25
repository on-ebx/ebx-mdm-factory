package com.on.ebx.factory.ebx;

import com.on.ebx.factory.quality.ChildKeysDerivation;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. The pure decisions of the {@code
 * CHILD_KEYS} post-pass ({@link ChildKeysPass}, lot M3, 2026-10-03): when a sweep is due, and when the stored computation
 * no longer matches the definition. The EBX reads and writes themselves need a running instance and are not covered here.
 */
public final class ChildKeysPassTest
{
	public static void main(final String[] pArgs)
	{
		ChildKeysPassTest.aSweepIsDueOnlyWhenSomethingChangedOrWasNeverComputed();
		ChildKeysPassTest.theDefinitionTagFollowsTheArgumentAndTheLength();
		ChildKeysPassTest.theDefinitionTagFollowsTheEffectiveEncoding();
		ChildKeysPassTest.aMarkerWrittenBeforeTheEncodingExistedIsStale();
		ChildKeysPassTest.decimalsReadWithoutTrailingZeros();
		System.out.println("ChildKeysPassTest OK");
	}

	private static void aSweepIsDueOnlyWhenSomethingChangedOrWasNeverComputed()
	{
		// needed(all, parentWritten, childWritten, lastRunPresent, definitionUnchanged)
		ChildKeysPassTest.check("nothing written, computed before with the same definition: skipped", !ChildKeysPass.needed(false, 0, 0, true, true));
		ChildKeysPassTest.check("scope all: always", ChildKeysPass.needed(true, 0, 0, true, true));
		ChildKeysPassTest.check("a parent row was written (a new parent has no value yet)", ChildKeysPass.needed(false, 3, 0, true, true));
		ChildKeysPassTest.check("a child row was written (added, changed, moved)", ChildKeysPass.needed(false, 0, 1, true, true));
		ChildKeysPassTest.check("never computed (the attribute was just added): even with nothing written", ChildKeysPass.needed(false, 0, 0, false, true));
		ChildKeysPassTest.check("a previous run stopped halfway (marker emptied): recomputed", ChildKeysPass.needed(false, 0, 0, false, false));
		ChildKeysPassTest.check("the argument or the length changed since the last computation", ChildKeysPass.needed(false, 0, 0, true, false));
	}

	private static ChildKeysPass.Definition definition(final String pArgument, final int pMaxLength)
	{
		return new ChildKeysPass.Definition("Personne", "adresseKeys", "12", pArgument, pMaxLength, ChildKeysDerivation.parse(pArgument), "personne", "personneRef", null);
	}

	private static void theDefinitionTagFollowsTheArgumentAndTheLength()
	{
		final String address = "{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"street\"]}";
		final String tag = ChildKeysPass.definitionTag(ChildKeysPassTest.definition(address, 2000));
		ChildKeysPassTest.check("stable for the same definition", tag.equals(ChildKeysPass.definitionTag(ChildKeysPassTest.definition(address, 2000))));
		ChildKeysPassTest.check("changes with the length", !tag.equals(ChildKeysPass.definitionTag(ChildKeysPassTest.definition(address, 1000))));
		ChildKeysPassTest.check("changes with the argument",
			!tag.equals(ChildKeysPass.definitionTag(ChildKeysPassTest.definition("{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"city\"]}", 2000))));
	}

	/** An absent encoding is the default (hash): spelling it out changes the argument text, the tag, but never the effective encoding's letter. */
	private static void theDefinitionTagFollowsTheEffectiveEncoding()
	{
		final String address = "{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"street\"]}";
		final String plainAddress = "{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"street\"],\"encoding\":\"plain\"}";
		final String tag = ChildKeysPass.definitionTag(ChildKeysPassTest.definition(address, 2000));
		ChildKeysPassTest.check("no encoding is the hash encoding: " + tag, tag.startsWith("h"));
		ChildKeysPassTest.check("plain has its own tag: " + ChildKeysPass.definitionTag(ChildKeysPassTest.definition(plainAddress, 2000)),
			ChildKeysPass.definitionTag(ChildKeysPassTest.definition(plainAddress, 2000)).startsWith("p"));
		ChildKeysPassTest.check("the same argument and length, the other encoding: another tag",
			!ChildKeysPass.definitionTag(address, 2000, "hash").equals(ChildKeysPass.definitionTag(address, 2000, "plain")));
	}

	/**
	 * The existing Vol1m attribute: its argument has no "encoding", its last run was written by lot M3 as "(def <hex>)" with
	 * hex = hash(argument|length). Whatever that hex is, the new tag differs: the readable values are recomputed once.
	 */
	private static void aMarkerWrittenBeforeTheEncodingExistedIsStale()
	{
		final String vol1m = "{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"houseNumber\",\"street\"]}";
		final String oldTag = Integer.toHexString((vol1m + "|" + 2000).hashCode());
		final String oldMessage = "889165 child row(s) read in 3 pass(es), 480123 parent(s) with keys: 480123 updated, 0 cleared, 0 unchanged (def " + oldTag + ")";
		final String newTag = ChildKeysPass.definitionTag(ChildKeysPassTest.definition(vol1m, 2000));
		ChildKeysPassTest.check("a marker of an earlier build is stale", !ChildKeysPass.definitionUnchanged(oldMessage, newTag));
		ChildKeysPassTest.check("so a sweep is due even with nothing written and a last run present",
			ChildKeysPass.needed(false, 0, 0, true, ChildKeysPass.definitionUnchanged(oldMessage, newTag)));
		// every tag a pre-M7 build could have written is hexadecimal: none can equal a tag that starts with h or p
		for (int i = -1000; i < 1000; i++)
		{
			final String legacy = Integer.toHexString(i * 7919);
			ChildKeysPassTest.check("a legacy hex tag never equals a new one: " + legacy, !legacy.equals(newTag) && !legacy.equals(ChildKeysPass.definitionTag(vol1m, 2000, "plain")));
		}
		final String freshMessage = "889165 child row(s) read in 3 pass(es) (def " + newTag + ")";
		ChildKeysPassTest.check("a marker written by this build is current", ChildKeysPass.definitionUnchanged(freshMessage, newTag));
		ChildKeysPassTest.check("no message at all is stale", !ChildKeysPass.definitionUnchanged(null, newTag) && !ChildKeysPass.definitionUnchanged("", newTag));
		ChildKeysPassTest.check("after the sweep with no change: skipped", !ChildKeysPass.needed(false, 0, 0, true, ChildKeysPass.definitionUnchanged(freshMessage, newTag)));
	}

	/** A child's number part: 12.0 and 12 are the same address number. */
	private static void decimalsReadWithoutTrailingZeros()
	{
		ChildKeysPassTest.check("12.00 reads as 12", "12".equals(ChildKeysPass.text(new java.math.BigDecimal("12.00"))));
		ChildKeysPassTest.check("100 stays 100, never 1E+2", "100".equals(ChildKeysPass.text(new java.math.BigDecimal("1E+2"))));
		ChildKeysPassTest.check("text stays text", "75008".equals(ChildKeysPass.text("75008")));
		ChildKeysPassTest.check("null stays null", ChildKeysPass.text(null) == null);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
