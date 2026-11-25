package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

/**
 * JUnit-free, like the tests of the sibling modules: run the main, a failure throws. {@link ChildKeysDerivation}
 * (lot M3, 2026-10-03) is the pure half of the {@code CHILD_KEYS} derivation: the argument parser, the key of one
 * child record and the value of one parent.
 */
public final class ChildKeysDerivationTest
{
	public static void main(final String[] pArgs)
	{
		ChildKeysDerivationTest.parseReadsTheDefinition();
		ChildKeysDerivationTest.parseDefaultsEveryFieldToRequired();
		ChildKeysDerivationTest.parseRefusesWhatCannotBeRight();
		ChildKeysDerivationTest.aPartIsFoldedLikeAMatchingKeyWithNoSpaceAndNoSeparator();
		ChildKeysDerivationTest.partIsTheOldFoldForAnyInput();
		ChildKeysDerivationTest.theKeyEncoderGivesTheSameKeyAsTokenForBothEncodings();
		ChildKeysDerivationTest.theKeyOfOneChildJoinsTheFoldedParts();
		ChildKeysDerivationTest.anEmptyRequiredPartGivesNoKey();
		ChildKeysDerivationTest.anOptionalEmptyPartLeavesAnEmptySegment();
		ChildKeysDerivationTest.twoAddressesNeverShareAKey();
		ChildKeysDerivationTest.theEncodingDefaultsToHashAndPlainIsKept();
		ChildKeysDerivationTest.anOpaqueTokenIsLowercaseLettersOfFixedLengthAndDeterministic();
		ChildKeysDerivationTest.differentKeysGiveDifferentTokensAndTheSameAddressTheSameToken();
		ChildKeysDerivationTest.anUnknownEncodingIsRefusedNamingTheTwoValidOnes();
		ChildKeysDerivationTest.theValueSortsTheOpaqueTokensThemselves();
		ChildKeysDerivationTest.theValueIsSortedDistinctAndIndependentOfTheReadingOrder();
		ChildKeysDerivationTest.theLengthCapKeepsASortedPrefixAndCountsWhatItDropped();
		ChildKeysDerivationTest.theTokenCapAndAKeyTooLongForTheValue();
		ChildKeysDerivationTest.noKeyGivesNoValue();
		ChildKeysDerivationTest.theChildEntityOfAnArgumentForALabel();
		System.out.println("ChildKeysDerivationTest OK");
	}

	private static final String ADDRESS = "{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"houseNumber\",\"street\"]}";
	private static final String ADDRESS_PLAIN = "{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"houseNumber\",\"street\"],\"encoding\":\"plain\"}";

	private static void parseReadsTheDefinition()
	{
		final ChildKeysDerivation.Definition d = ChildKeysDerivation.parse("{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"houseNumber\",\"street\"],"
			+ "\"required\":[\"postalCode\",\"street\"],\"reference\":\"personne\",\"maxTokens\":50}");
		ChildKeysDerivationTest.check("child", "Adresse".equals(d.childEntity));
		ChildKeysDerivationTest.check("fields keep their order", d.fields.equals(Arrays.asList("postalCode", "houseNumber", "street")));
		ChildKeysDerivationTest.check("required is the given subset", d.required.size() == 2 && d.required.contains("postalCode") && d.required.contains("street"));
		ChildKeysDerivationTest.check("reference", "personne".equals(d.reference));
		ChildKeysDerivationTest.check("maxTokens", d.maxTokens != null && d.maxTokens.intValue() == 50);
	}

	private static void parseDefaultsEveryFieldToRequired()
	{
		final ChildKeysDerivation.Definition d = ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS);
		ChildKeysDerivationTest.check("every field required when the argument names none", d.required.size() == 3);
		ChildKeysDerivationTest.check("no reference, no cap by default", d.reference == null && d.maxTokens == null);
	}

	private static void parseRefusesWhatCannotBeRight()
	{
		final String[] bad = { null, "", "  ", "not json", "[]", "{}", "{\"child\":\"Adresse\"}", "{\"child\":\"Adresse\",\"fields\":[]}",
			"{\"child\":\"Adresse\",\"fields\":[\"a\",\"a\"]}", "{\"child\":\"1bad\",\"fields\":[\"a\"]}", "{\"child\":\"Adresse\",\"fields\":[\"a b\"]}",
			"{\"child\":\"Adresse\",\"fields\":[\"a\"],\"required\":[\"b\"]}", "{\"child\":\"Adresse\",\"fields\":[\"a\"],\"maxTokens\":0}",
			"{\"child\":\"Adresse\",\"fields\":[\"a\"],\"maxTokens\":\"3\"}", "{\"child\":\"Adresse\",\"fields\":[1]}" };
		for (final String argument : bad)
		{
			boolean refused = false;
			try
			{
				ChildKeysDerivation.parse(argument);
			}
			catch (final IllegalArgumentException ex)
			{
				refused = ex.getMessage() != null && !ex.getMessage().isEmpty();
			}
			ChildKeysDerivationTest.check("refused with a message: " + argument, refused);
		}
	}

	private static void aPartIsFoldedLikeAMatchingKeyWithNoSpaceAndNoSeparator()
	{
		ChildKeysDerivationTest.check("accents, case and spaces: " + ChildKeysDerivation.part("  rue de l'Église "), "RUEDEL'EGLISE".equals(ChildKeysDerivation.part("  rue de l'Église ")));
		ChildKeysDerivationTest.check("the fold is CleaningSteps.foldKey, nothing else: punctuation stays",
			"AV.DU8MAI1945".equals(ChildKeysDerivation.part("Av. du 8 mai 1945")));
		ChildKeysDerivationTest.check("a no-break space is a space too", "RUEPAIX".equals(ChildKeysDerivation.part("rue paix")));
		ChildKeysDerivationTest.check("the part separator is removed from inside a part: " + ChildKeysDerivation.part("a_b"), "AB".equals(ChildKeysDerivation.part("a_b")));
		ChildKeysDerivationTest.check("null stays null", ChildKeysDerivation.part(null) == null);
		ChildKeysDerivationTest.check("blank is null", ChildKeysDerivation.part("   ") == null);
		ChildKeysDerivationTest.check("only separators is null", ChildKeysDerivation.part("__") == null);
	}

	private static void theKeyOfOneChildJoinsTheFoldedParts()
	{
		final ChildKeysDerivation.Definition d = ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS_PLAIN);
		final String token = ChildKeysDerivation.token(d, Arrays.asList("75008", " 12 ", "Rue de la Paix"));
		ChildKeysDerivationTest.check("postalCode_houseNumber_street: " + token, "75008_12_RUEDELAPAIX".equals(token));
		final String sameAddressOtherSpelling = ChildKeysDerivation.token(d, Arrays.asList("75008", "12", "rue DE la paix"));
		ChildKeysDerivationTest.check("another source's spelling gives the same key", token.equals(sameAddressOtherSpelling));
	}

	private static void anEmptyRequiredPartGivesNoKey()
	{
		final ChildKeysDerivation.Definition d = ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS);
		ChildKeysDerivationTest.check("no house number, all parts required: no key", ChildKeysDerivation.token(d, Arrays.asList("75008", null, "Rue de la Paix")) == null);
		ChildKeysDerivationTest.check("a blank street: no key", ChildKeysDerivation.token(d, Arrays.asList("75008", "12", "  ")) == null);
		ChildKeysDerivationTest.check("fewer values than fields: no key", ChildKeysDerivation.token(d, Arrays.asList("75008", "12")) == null);
		ChildKeysDerivationTest.check("no values at all: no key", ChildKeysDerivation.token(d, null) == null);
	}

	private static void anOptionalEmptyPartLeavesAnEmptySegment()
	{
		final ChildKeysDerivation.Definition d = ChildKeysDerivation
			.parse("{\"child\":\"Adresse\",\"fields\":[\"postalCode\",\"houseNumber\",\"street\"],\"required\":[\"postalCode\",\"street\"],\"encoding\":\"plain\"}");
		final String token = ChildKeysDerivation.token(d, Arrays.asList("75008", null, "Rue de la Paix"));
		ChildKeysDerivationTest.check("empty optional segment: " + token, "75008__RUEDELAPAIX".equals(token));
		final ChildKeysDerivation.Definition none = ChildKeysDerivation.parse("{\"child\":\"Adresse\",\"fields\":[\"a\",\"b\"],\"required\":[]}");
		ChildKeysDerivationTest.check("every part optional and empty: no key, never '_'", ChildKeysDerivation.token(none, Arrays.asList(null, "  ")) == null);
	}

	/** The point of removing the separator inside a part: (12, A_B) and (12_A, B) used to build the same key. */
	private static void twoAddressesNeverShareAKey()
	{
		final ChildKeysDerivation.Definition d = ChildKeysDerivation.parse("{\"child\":\"Adresse\",\"fields\":[\"number\",\"street\"],\"encoding\":\"plain\"}");
		final String first = ChildKeysDerivation.token(d, Arrays.asList("12", "A_B"));
		final String second = ChildKeysDerivation.token(d, Arrays.asList("12_A", "B"));
		ChildKeysDerivationTest.check("12_AB is built from (12, A_B) and 12A_B from (12_A, B): " + first + " / " + second, "12_AB".equals(first) && "12A_B".equals(second));
		ChildKeysDerivationTest.check("they differ", !first.equals(second));
		ChildKeysDerivationTest.check("a key never holds a space", first.indexOf(' ') < 0 && second.indexOf(' ') < 0);
	}

	private static void theEncodingDefaultsToHashAndPlainIsKept()
	{
		ChildKeysDerivationTest.check("absent encoding reads as hash", ChildKeysDerivation.ENCODING_HASH.equals(ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS).encoding));
		ChildKeysDerivationTest.check("explicit hash", ChildKeysDerivation.ENCODING_HASH.equals(ChildKeysDerivation
			.parse("{\"child\":\"Adresse\",\"fields\":[\"a\"],\"encoding\":\"hash\"}").encoding));
		ChildKeysDerivationTest.check("explicit plain", ChildKeysDerivation.ENCODING_PLAIN.equals(ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS_PLAIN).encoding));
		ChildKeysDerivationTest.check("a null encoding reads as the default", ChildKeysDerivation.ENCODING_HASH.equals(ChildKeysDerivation
			.parse("{\"child\":\"Adresse\",\"fields\":[\"a\"],\"encoding\":null}").encoding));
		final List<String> parts = Arrays.asList("75008", "12", "Rue de la Paix");
		ChildKeysDerivationTest.check("plain is unchanged", "75008_12_RUEDELAPAIX".equals(ChildKeysDerivation.token(ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS_PLAIN), parts)));
		ChildKeysDerivationTest.check("hash is the opaque token of the plain key",
			ChildKeysDerivation.opaque("75008_12_RUEDELAPAIX").equals(ChildKeysDerivation.token(ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS), parts)));
		ChildKeysDerivationTest.check("an empty required part gives no key in either encoding",
			ChildKeysDerivation.token(ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS), Arrays.asList("75008", null, "Rue de la Paix")) == null
				&& ChildKeysDerivation.token(ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS_PLAIN), Arrays.asList("75008", null, "Rue de la Paix")) == null);
	}

	private static void anOpaqueTokenIsLowercaseLettersOfFixedLengthAndDeterministic()
	{
		final String token = ChildKeysDerivation.opaque("49000_8_RUEGAMBETTA");
		ChildKeysDerivationTest.check("fixed expected token for a fixed input: " + token, "ixmkfnujmvmbg".equals(token));
		ChildKeysDerivationTest.check("the same input gives the same token", token.equals(ChildKeysDerivation.opaque("49000_8_RUEGAMBETTA")));
		for (final String key : new String[] { "49000_8_RUEGAMBETTA", "13001_18_RUESAINTE-CATHERINE", "75008_12_RUEDEL'EGLISE", "1_1_A", "\u00e9\u4e2d_1_X", "" })
		{
			final String opaque = ChildKeysDerivation.opaque(key);
			ChildKeysDerivationTest.check("13 letters: " + opaque, opaque.length() == ChildKeysDerivation.TOKEN_LENGTH);
			ChildKeysDerivationTest.check("only a-z: " + opaque, opaque.matches("[a-z]{13}"));
		}
	}

	private static void differentKeysGiveDifferentTokensAndTheSameAddressTheSameToken()
	{
		final Set<String> tokens = new HashSet<String>();
		for (int i = 0; i < 20000; i++)
		{
			tokens.add(ChildKeysDerivation.opaque(i + "_" + (i % 97) + "_RUE" + i));
		}
		ChildKeysDerivationTest.check("20,000 different keys give 20,000 different tokens: " + tokens.size(), tokens.size() == 20000);
		ChildKeysDerivationTest.check("two close keys differ", !ChildKeysDerivation.opaque("49000_8_RUEGAMBETTA").equals(ChildKeysDerivation.opaque("49000_9_RUEGAMBETTA")));
		final ChildKeysDerivation.Definition d = ChildKeysDerivation.parse(ChildKeysDerivationTest.ADDRESS);
		ChildKeysDerivationTest.check("another source's spelling of one address gives one token",
			ChildKeysDerivation.token(d, Arrays.asList("75008", "12", "Rue de la Paix")).equals(ChildKeysDerivation.token(d, Arrays.asList(" 75008", "12", "rue DE la paix"))));
	}

	private static void anUnknownEncodingIsRefusedNamingTheTwoValidOnes()
	{
		final String[] bad = { "\"base64\"", "\"HASH\"", "\"\"", "1", "true", "[\"hash\"]" };
		for (final String encoding : bad)
		{
			String message = null;
			try
			{
				ChildKeysDerivation.parse("{\"child\":\"Adresse\",\"fields\":[\"a\"],\"encoding\":" + encoding + "}");
			}
			catch (final IllegalArgumentException ex)
			{
				message = ex.getMessage();
			}
			ChildKeysDerivationTest.check("refused: encoding " + encoding, message != null);
			ChildKeysDerivationTest.check("names both valid values: " + message, message.contains("hash") && message.contains("plain"));
		}
	}

	/** The value sorts the tokens it is given: with opaque tokens that is the order of the letters, whatever the readable keys were. */
	private static void theValueSortsTheOpaqueTokensThemselves()
	{
		final String a = ChildKeysDerivation.opaque("75008_12_RUEDELAPAIX");
		final String b = ChildKeysDerivation.opaque("49000_8_RUEGAMBETTA");
		final String c = ChildKeysDerivation.opaque("13001_18_RUESAINTE-CATHERINE");
		final ChildKeysDerivation.Value value = ChildKeysDerivation.value(Arrays.asList(a, b, c, b), 2000, null);
		final List<String> expected = new ArrayList<String>(Arrays.asList(a, b, c));
		Collections.sort(expected);
		ChildKeysDerivationTest.check("distinct and sorted by token: " + value.text, value.kept == 3 && value.text.equals(expected.get(0) + " " + expected.get(1) + " " + expected.get(2)));
		ChildKeysDerivationTest.check("13 letters and a space per key: " + value.text.length(), value.text.length() == 3 * 13 + 2);
	}

	private static void theValueIsSortedDistinctAndIndependentOfTheReadingOrder()
	{
		final ChildKeysDerivation.Value forward = ChildKeysDerivation.value(Arrays.asList("B_2", "A_1", "B_2", "C_3"), 2000, null);
		final ChildKeysDerivation.Value backward = ChildKeysDerivation.value(Arrays.asList("C_3", "B_2", "A_1", "A_1"), 2000, null);
		ChildKeysDerivationTest.check("sorted, one space, distinct: " + forward.text, "A_1 B_2 C_3".equals(forward.text));
		ChildKeysDerivationTest.check("same value whatever the order", forward.text.equals(backward.text));
		ChildKeysDerivationTest.check("kept 3, dropped 0", forward.kept == 3 && forward.dropped == 0);
		final ChildKeysDerivation.Value withBlanks = ChildKeysDerivation.value(Arrays.asList(null, " ", "X_1"), 2000, null);
		ChildKeysDerivationTest.check("null and blank entries are ignored", "X_1".equals(withBlanks.text) && withBlanks.kept == 1);
	}

	private static void theLengthCapKeepsASortedPrefixAndCountsWhatItDropped()
	{
		// "AAAA BBBB" is 9 characters: a third key would need 5 more
		final ChildKeysDerivation.Value value = ChildKeysDerivation.value(Arrays.asList("CCCC", "AAAA", "BBBB"), 9, null);
		ChildKeysDerivationTest.check("prefix of the sorted keys: " + value.text, "AAAA BBBB".equals(value.text));
		ChildKeysDerivationTest.check("the value fits the length", value.text.length() <= 9);
		ChildKeysDerivationTest.check("one key dropped, counted", value.kept == 2 && value.dropped == 1);
		final ChildKeysDerivation.Value again = ChildKeysDerivation.value(Arrays.asList("BBBB", "CCCC", "AAAA"), 9, null);
		ChildKeysDerivationTest.check("deterministic: the same keys in another order drop the same key", value.text.equals(again.text));
		ChildKeysDerivationTest.check("a length of zero reads as the default 2000", ChildKeysDerivation.value(Collections.singletonList("AAAA"), 0, null).text != null);
		final List<String> many = new ArrayList<String>();
		for (int i = 0; i < 400; i++)
		{
			many.add(String.format("%05d_%02d_STREET%03d", i, i % 50, i));
		}
		final ChildKeysDerivation.Value big = ChildKeysDerivation.value(many, 2000, null);
		ChildKeysDerivationTest.check("never over the field length: " + big.text.length(), big.text.length() <= 2000);
		ChildKeysDerivationTest.check("kept + dropped = distinct", big.kept + big.dropped == 400 && big.dropped > 0);
	}

	private static void theTokenCapAndAKeyTooLongForTheValue()
	{
		final ChildKeysDerivation.Value capped = ChildKeysDerivation.value(Arrays.asList("C", "A", "B", "D"), 2000, Integer.valueOf(2));
		ChildKeysDerivationTest.check("maxTokens 2 keeps the two first: " + capped.text, "A B".equals(capped.text) && capped.kept == 2 && capped.dropped == 2);
		final ChildKeysDerivation.Value tooLong = ChildKeysDerivation.value(Arrays.asList("AAAAAAAAAAAA", "B"), 5, null);
		ChildKeysDerivationTest.check("a key that alone exceeds the length is dropped, the others stay: " + tooLong.text, "B".equals(tooLong.text) && tooLong.dropped == 1);
		final ChildKeysDerivation.Value allTooLong = ChildKeysDerivation.value(Collections.singletonList("AAAAAAAAAAAA"), 5, null);
		ChildKeysDerivationTest.check("every key dropped: no value", allTooLong.text == null && allTooLong.kept == 0 && allTooLong.dropped == 1);
	}

	private static void noKeyGivesNoValue()
	{
		ChildKeysDerivationTest.check("an empty collection", ChildKeysDerivation.value(Collections.<String>emptyList(), 2000, null).text == null);
		ChildKeysDerivationTest.check("null", ChildKeysDerivation.value(null, 2000, null).text == null);
	}

	private static void theChildEntityOfAnArgumentForALabel()
	{
		ChildKeysDerivationTest.check("child entity read", "Adresse".equals(ChildKeysDerivation.childEntityOrNull(ChildKeysDerivationTest.ADDRESS)));
		ChildKeysDerivationTest.check("unreadable argument reads as null, never throws", ChildKeysDerivation.childEntityOrNull("{") == null && ChildKeysDerivation.childEntityOrNull(null) == null);
	}

	/** What {@code part} was before lot M10: {@code foldKey} (regex collapse, Normalizer) then the removal. */
	private static String oldPart(final String pRaw)
	{
		final String folded = CleaningSteps.foldKey(pRaw);
		if (folded == null)
		{
			return null;
		}
		final StringBuilder out = new StringBuilder();
		for (int i = 0; i < folded.length(); i++)
		{
			final char c = folded.charAt(i);
			if (Character.isWhitespace(c) || Character.isSpaceChar(c) || Character.isISOControl(c) || c == ChildKeysDerivation.PART_SEPARATOR)
			{
				continue;
			}
			out.append(c);
		}
		return out.length() == 0 ? null : out.toString();
	}

	/** The single-loop {@code part} (no regex, no Normalizer on ASCII) is the old fold for any input: a random corpus of every character class that matters. */
	private static void partIsTheOldFoldForAnyInput()
	{
		final String[] alphabet = { "a", "z", "Q", "7", "-", "'", ".", "_", " ", "  ", "\t", "\n", "\r", "\u000b", "\f", "\u0000", "\u001f", "\u007f", "\u0085", "\u00a0", "\u2003",
			"\u2028", "\u3000", "\u00e9", "\u00c9", "\u00e8", "\u00f1", "\u00df", "\u00c6", "\u00f8", "\u0142", "\u0111", "\u00fe", "e\u0301", "\u0301", "\u0130", "\u0149",
			"\u01c6", "\ufb01", "\u03c3", "\u03c2", "\ud83d\ude00", "\u4e2d", "\u0627", "\u1e9e" };
		final Random random = new Random(20261004L);
		for (int i = 0; i < 200000; i++)
		{
			final int length = random.nextInt(14);
			final StringBuilder raw = new StringBuilder();
			for (int j = 0; j < length; j++)
			{
				raw.append(alphabet[random.nextInt(alphabet.length)]);
			}
			final String input = raw.toString();
			final String expected = ChildKeysDerivationTest.oldPart(input);
			final String actual = ChildKeysDerivation.part(input);
			ChildKeysDerivationTest.check("part(" + input.replace("\n", "\\n") + ") was '" + expected + "', is '" + actual + "'", expected == null ? actual == null : expected.equals(actual));
		}
		ChildKeysDerivationTest.check("null stays null", ChildKeysDerivation.part(null) == null);
		ChildKeysDerivationTest.check("an empty string has no part", ChildKeysDerivation.part("") == null);
	}

	/** {@code KeyEncoder.render(encode(parts))} is {@code token(parts)}, with the hash and the plain encoding, whatever the optional and required parts hold. */
	private static void theKeyEncoderGivesTheSameKeyAsTokenForBothEncodings()
	{
		final String[] values = { null, "", " ", "75008", "75 008", "12", "12_B", "Rue de la Paix", "rue  de la PAIX", "Rue d'\u00c9t\u00e9", "Stra\u00dfe", "a_b" };
		final String[] definitions = {
			"{\"child\":\"A\",\"fields\":[\"a\",\"b\",\"c\"]}",
			"{\"child\":\"A\",\"fields\":[\"a\",\"b\",\"c\"],\"required\":[\"a\"]}",
			"{\"child\":\"A\",\"fields\":[\"a\",\"b\",\"c\"],\"required\":[]}",
			"{\"child\":\"A\",\"fields\":[\"a\",\"b\",\"c\"],\"encoding\":\"plain\"}",
			"{\"child\":\"A\",\"fields\":[\"a\"],\"required\":[],\"encoding\":\"plain\"}" };
		for (final String json : definitions)
		{
			final ChildKeysDerivation.Definition definition = ChildKeysDerivation.parse(json);
			final ChildKeysDerivation.KeyEncoder encoder = new ChildKeysDerivation.KeyEncoder(definition, 5);
			for (final String a : values)
			{
				for (final String b : values)
				{
					for (final String c : values)
					{
						final List<String> parts = Arrays.asList(a, b, c);
						final String token = ChildKeysDerivation.token(definition, parts);
						final long code = encoder.encode(parts);
						ChildKeysDerivationTest.check(json + " " + parts + ": token " + token,
							token == null ? code == ChildKeysDerivation.KeyEncoder.NO_KEY : code != ChildKeysDerivation.KeyEncoder.NO_KEY && token.equals(encoder.render(code)));
					}
				}
			}
		}
		final ChildKeysDerivation.Definition hash = ChildKeysDerivation.parse(definitions[0]);
		final ChildKeysDerivation.KeyEncoder encoder = new ChildKeysDerivation.KeyEncoder(hash, 10);
		final long first = encoder.encode(Arrays.asList("49000", "8", "Rue Gambetta"));
		final long second = encoder.encode(Arrays.asList("13001", "8", "Rue Gambetta"));
		ChildKeysDerivationTest.check("a hash code is never negative", first >= 0 && second >= 0);
		ChildKeysDerivationTest.check("two codes order like their tokens", (first < second) == (encoder.render(first).compareTo(encoder.render(second)) < 0));
		ChildKeysDerivationTest.check("a long raw value is folded without being cached", encoder.encode(Arrays.asList("1", "2", new String(new char[500]).replace('\0', 'x'))) >= 0);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
