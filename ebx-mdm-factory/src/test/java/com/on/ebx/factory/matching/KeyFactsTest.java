package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * JUnit-free, like every other test of this module: run the main, a failure throws. Lot M3 ({@code docs/MATCHING.md} &sect;56): {@link KeyFacts} on the 534 records of the
 * B2C master scenario (the numbers below were counted independently by {@code tools/e2e/b2c_missed_pairs.py} on the generated files), and on small hand-made tables.
 */
public final class KeyFactsTest
{
	public static void main(final String[] pArgs) throws Exception
	{
		KeyFactsTest.fixtureCountsMatchTheIndependentCount();
		KeyFactsTest.theE164AndEmailAgreeWithTheNameOnEveryComparablePair();
		KeyFactsTest.theNameKeyAloneIsNotSafeAndAddressKeysHaveSixNamesakes();
		KeyFactsTest.reachOfAnySubsetOfKeys();
		KeyFactsTest.keysOfTheSameBaseAreNotCompared();
		KeyFactsTest.aSampleNeverCountsAsSafe();
		KeyFactsTest.jsonRoundTrip();
		KeyFactsTest.promptNamesTheNumbers();
		KeyFactsTest.emptyAndTinyTables();
		KeyFactsTest.multiTokenKeysAreSplitOnWhiteSpace();
		System.out.println("KeyFactsTest OK");
	}

	private static void fixtureCountsMatchTheIndependentCount()
	{
		final KeyFacts facts = B2cKeyFixtureTest.facts();
		B2cKeyFixtureTest.check(facts.rows() == 534, "534 records, got " + facts.rows());
		B2cKeyFixtureTest.check(facts.recordsBySystem().get("ECOM") == 160 && facts.recordsBySystem().get("STORES") == 234 && facts.recordsBySystem().get("LOYALTY") == 140, "per system");
		final KeyFacts.KeyStat email = facts.key("emailKey");
		B2cKeyFixtureTest.check(email.filledBySystem.get("STORES") == 131 && email.filledBySystem.get("ECOM") == 160 && email.filledBySystem.get("LOYALTY") == 140, "email fill per system");
		B2cKeyFixtureTest.check(email.pairsSharing == 341, "341 pairs share the e-mail key, got " + email.pairsSharing);
		final KeyFacts.KeyStat phone = facts.key("mobileE164");
		B2cKeyFixtureTest.check(phone.pairsSharing == 565, "565 pairs share the phone, got " + phone.pairsSharing);
		B2cKeyFixtureTest.check(phone.filledBySystem.get("STORES") == 234, "every ticket carries a phone");
		final KeyFacts.KeyStat birth = facts.key("birthDate");
		B2cKeyFixtureTest.check(birth.filledBySystem.get("STORES") == null, "no ticket carries a birth date");
		B2cKeyFixtureTest.check(email.sharedBySystem.get("ECOM") > 100, "most web customers share their e-mail with another record");
	}

	private static void theE164AndEmailAgreeWithTheNameOnEveryComparablePair()
	{
		final KeyFacts facts = B2cKeyFixtureTest.facts();
		final KeyFacts.Agreement emailName = facts.agreement("emailKey", "fullNameKey");
		B2cKeyFixtureTest.check(emailName.pairs == 341 && emailName.agree == 341 && emailName.conflict == 0 && emailName.unknown == 0, "email -> name: " + emailName.pairs + "/" + emailName.agree);
		B2cKeyFixtureTest.check(emailName.rate() == 1d && emailName.safe(), "email -> name is safe");
		final KeyFacts.Agreement phoneName = facts.agreement("mobileE164", "fullNameKey");
		B2cKeyFixtureTest.check(phoneName.pairs == 565 && phoneName.agree == 565 && phoneName.conflict == 0, "phone -> name");
		B2cKeyFixtureTest.check(facts.safePair("mobileE164", "fullNameKey"), "phone -> name is safe");
		final KeyFacts.Agreement phoneEmail = facts.agreement("mobileE164", "emailKey");
		B2cKeyFixtureTest.check(phoneEmail.conflict == 0 && phoneEmail.unknown == 224 && phoneEmail.agree == 341, "phone -> email: 341 agree, 224 unknown (the pairs the e-mail cannot reach)");
		B2cKeyFixtureTest.check(phoneEmail.safe(), "phone -> email is safe on the pairs where it can be compared (341, none differ)");
		final KeyFacts.Agreement emailBirth = facts.agreement("emailKey", "birthDate");
		B2cKeyFixtureTest.check(emailBirth.unknown > 0 && emailBirth.conflict == 0, "birth date missing on the tickets");
	}

	private static void theNameKeyAloneIsNotSafeAndAddressKeysHaveSixNamesakes()
	{
		final KeyFacts facts = B2cKeyFixtureTest.facts();
		final KeyFacts.Agreement nameEmail = facts.agreement("fullNameKey", "emailKey");
		B2cKeyFixtureTest.check(nameEmail.pairs == 694, "694 pairs share the name key (565 true + 129 namesakes), got " + nameEmail.pairs);
		B2cKeyFixtureTest.check(nameEmail.conflict > 0 && !nameEmail.safe(), "namesakes with different e-mails: not safe (" + nameEmail.conflict + " differ)");
		final KeyFacts.Agreement namePhone = facts.agreement("fullNameKey", "mobileE164");
		B2cKeyFixtureTest.check(namePhone.agree == 565 && namePhone.conflict == 129 && !namePhone.safe(), "name -> phone: 129 namesakes with another phone");
		final KeyFacts.Agreement addressName = facts.agreement("addressKeys", "fullNameKey");
		B2cKeyFixtureTest.check(addressName.pairs == 109 && addressName.agree == 103 && addressName.conflict == 6 && !addressName.safe(),
			"six different people share an address: " + addressName.pairs + "/" + addressName.agree + "/" + addressName.conflict);
	}

	private static void reachOfAnySubsetOfKeys()
	{
		final KeyFacts facts = B2cKeyFixtureTest.facts();
		final Map<String, long[]> emailOrAddress = facts.recordsReachedBy(Arrays.asList("emailKey", "addressKeys"));
		B2cKeyFixtureTest.check(emailOrAddress.get("STORES")[0] == 234 && emailOrAddress.get("STORES")[1] == 131, "STORES: 131 of 234 reachable by email or address");
		B2cKeyFixtureTest.check(emailOrAddress.get("ECOM")[1] == 160 && emailOrAddress.get("LOYALTY")[1] == 140, "the other two are fully reachable");
		final Map<String, long[]> withPhone = facts.recordsReachedBy(Arrays.asList("emailKey", "addressKeys", "mobileE164"));
		B2cKeyFixtureTest.check(withPhone.get("STORES")[1] == 234, "adding the phone reaches every ticket");
		B2cKeyFixtureTest.check(facts.recordsReachedBy(Arrays.asList("noSuchKey")).isEmpty(), "an unknown key says nothing");
	}

	private static void keysOfTheSameBaseAreNotCompared()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		rows.add(new KeyFacts.Row("A", new String[] { "X@Y", "x@y" }));
		rows.add(new KeyFacts.Row("B", new String[] { "X@Y", "x@y" }));
		final KeyFacts facts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("emailKey", "email", false), new KeyFacts.KeySpec("email", "email", false)), false);
		B2cKeyFixtureTest.check(facts.agreements().isEmpty(), "a key and the field it is derived from are one base: no agreement is reported");
		B2cKeyFixtureTest.check(facts.key("emailKey").pairsSharing == 1, "but the pair is still counted");
	}

	private static void aSampleNeverCountsAsSafe()
	{
		final KeyFacts sampled = KeyFacts.compute(B2cKeyFixtureTest.rows(), B2cKeyFixtureTest.specs(), true);
		B2cKeyFixtureTest.check(sampled.agreement("emailKey", "fullNameKey").safe(), "the arithmetic is the same");
		B2cKeyFixtureTest.check(!sampled.safePair("emailKey", "fullNameKey") && sampled.sampled(), "but a prefix of the table relaxes nothing");
		B2cKeyFixtureTest.check(sampled.promptText("Client").contains("lower bounds") && !sampled.promptText("Client").contains("[SAFE"), "and the prompt says so");
	}

	private static void jsonRoundTrip()
	{
		final KeyFacts facts = B2cKeyFixtureTest.facts();
		final JsonNode json = facts.toJson();
		final KeyFacts back = KeyFacts.fromJson(json);
		B2cKeyFixtureTest.check(back != null && back.toJson().equals(json), "toJson/fromJson are inverse");
		B2cKeyFixtureTest.check(back.safePair("emailKey", "fullNameKey") && back.recordsReachedBy(Arrays.asList("emailKey")).get("STORES")[1] == 131, "the restored facts answer the same");
		B2cKeyFixtureTest.check(json.toString().length() < 6000, "small enough to live in the plan: " + json.toString().length());
		B2cKeyFixtureTest.check(KeyFacts.fromJson(null) == null && KeyFacts.fromJson(com.fasterxml.jackson.databind.node.NullNode.getInstance()) == null, "garbage reads as none");
	}

	private static void promptNamesTheNumbers()
	{
		final String text = B2cKeyFixtureTest.facts().promptText("Client");
		B2cKeyFixtureTest.check(text.contains("534 record(s) of Client"), "rows");
		B2cKeyFixtureTest.check(text.contains("STORES 131 (56%)"), "fill per system with its share: " + text);
		B2cKeyFixtureTest.check(text.contains("same emailKey -> fullNameKey: 341 pair(s), agree 341, differ 0, unknown 0, rate 100.0% [SAFE"), "the agreement line: " + text);
		B2cKeyFixtureTest.check(text.contains("same fullNameKey -> emailKey") && !text.contains("same fullNameKey -> emailKey: 694 pair(s), agree 341, differ 0"), "the weaker direction is reported with its own numbers");
		B2cKeyFixtureTest.check(text.contains("Records that fill NONE of the keys above, per source system: ECOM 0 of 160, LOYALTY 0 of 140, STORES 0 of 234"), "every ticket fills the phone or the name: " + text);
	}

	private static void emptyAndTinyTables()
	{
		final KeyFacts empty = KeyFacts.compute(new ArrayList<KeyFacts.Row>(), Arrays.asList(new KeyFacts.KeySpec("a", "a", false)), false);
		B2cKeyFixtureTest.check(empty.rows() == 0 && empty.agreements().isEmpty() && empty.recordsReachedBy(Arrays.asList("a")).isEmpty(), "empty table: nothing, no error");
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		rows.add(new KeyFacts.Row("A", new String[] { "1", "x" }));
		rows.add(new KeyFacts.Row("B", new String[] { "1", "x" }));
		rows.add(new KeyFacts.Row("B", new String[] { "1", "y" }));
		final KeyFacts tiny = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("k", "k", false), new KeyFacts.KeySpec("j", "j", false)), false);
		final KeyFacts.Agreement agreement = tiny.agreement("k", "j");
		B2cKeyFixtureTest.check(agreement.pairs == 3 && agreement.agree == 1 && agreement.conflict == 2 && !agreement.safe(), "three records, one agreement, two conflicts");
		B2cKeyFixtureTest.check(tiny.agreement("j", "k").pairs == 1, "only the two x share j");
	}

	private static void multiTokenKeysAreSplitOnWhiteSpace()
	{
		final List<KeyFacts.Row> rows = new ArrayList<KeyFacts.Row>();
		rows.add(new KeyFacts.Row("A", new String[] { "t1 t2", "n" }));
		rows.add(new KeyFacts.Row("B", new String[] { "t2", "n" }));
		rows.add(new KeyFacts.Row("B", new String[] { "t9", "n" }));
		final KeyFacts facts = KeyFacts.compute(rows, Arrays.asList(new KeyFacts.KeySpec("children", "children", true), new KeyFacts.KeySpec("name", "name", false)), false);
		B2cKeyFixtureTest.check(facts.key("children").pairsSharing == 1 && facts.key("children").approximate, "t2 is shared by two records; several tokens make it approximate");
		B2cKeyFixtureTest.check(facts.key("children").sharedBySystem.get("A") == 1 && facts.key("children").sharedBySystem.get("B") == 1, "shared records per system");
		B2cKeyFixtureTest.check(facts.agreement("children", "name").agree == 1, "the pair agrees on the name");
		B2cKeyFixtureTest.check(facts.agreement("name", "children") == null, "a multi-token key is never the partner of an agreement");
	}
}
