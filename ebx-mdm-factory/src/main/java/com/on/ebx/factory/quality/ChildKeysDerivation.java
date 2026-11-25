package com.on.ebx.factory.quality;

import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * {@code StagingAttribute.derivation} value {@code CHILD_KEYS} (lot M3, 2026-10-03, {@code docs/MATCHING.md}
 * "Child keys on the parent"): a PARENT attribute holding ONE exact key per child record that references the
 * parent, so that the add-on's candidate search can bring two parents together because they share a child
 * (the same address, say) while staying on ONE table - the search on a field of the child table costs a
 * Calcite sub-query on the child table per pivot (measured 2026-10-03 on the 1M-row project Vol1m: 78 min 40
 * for 153,937 pivots, against 8 min 54 without the child fields, which lost 6,009 true pairs).
 * <p>
 * Unlike {@code FOLD_KEY}/{@code VALID_VALUE} it is not computed row by row from the parent's own value: the
 * children are loaded AFTER the parent row, so {@code ebx.StagingLoader} fills it in a post-pass of the load
 * ({@code staging.ChildKeysSweep}); {@code derivedFrom} stays empty and {@code StagingAttribute
 * .derivationArgument} carries the definition ({@link Definition}). This class is the pure half: the definition
 * parser, the key of one child record and the value of one parent.
 * <p>
 * <b>The key of one child record.</b> Each part (a child attribute listed in {@code fields}) goes through the
 * house matching fold, {@link CleaningSteps#foldKey} (trim, collapse spaces, strip accents, upper case - never
 * another normalisation), then every whitespace character is removed inside the part (the value is a list of
 * tokens separated by one space, so a part must not carry a space), then every {@value #PART_SEPARATOR} is
 * removed (the only character this class deletes beyond the fold: it is the one that joins the parts, so it
 * must not occur inside one - otherwise two different addresses could build the same key). The parts are
 * joined by {@value #PART_SEPARATOR}: {@code 75008_12_RUEDELAPAIX}. A part that is empty after the fold is
 * either REQUIRED (the child record contributes no key at all) or optional (an empty segment, {@code
 * 75008__RUEDELAPAIX}); by default every part is required, because a key with a hole is a weak match, not an
 * exact one.
 * <p>
 * <b>The encoding of that key (lot M7, 2026-10-04).</b> {@code "encoding":"hash"} (the DEFAULT when the argument does not say) replaces the key by an
 * OPAQUE token, {@link #opaque}: {@value #TOKEN_LENGTH} lowercase letters {@code a}-{@code z}, derived from SHA-256 of the key's UTF-8 bytes. The reason is
 * measured (project Vol1m, 2026-10-04): the add-on's {@code exactFullText} cuts the value into WORDS with the field's own Lucene analyzer and scores {@code
 * matchedWords / max(wordsA, wordsB)}; a readable key such as {@code 13001_18_RUESAINTE-CATHERINE} is several words, so two parents sharing only a postal code
 * or a street name passed a low threshold (recall 99.1 %, precision 87 %, 98.9 % of the false pairs sharing NO whole address). A token with no digit, no
 * separator and no accent is ONE word for any analyzer: the score becomes {@code 1 / max(keysA, keysB)} for a shared address and exactly 0 otherwise.
 * {@code "encoding":"plain"} keeps the readable key (for whoever wants to read the values); it is not recommended for matching.
 * <p>
 * <b>The value of one parent.</b> The distinct keys of its children, sorted (natural {@code String} order, so
 * the value is the same whatever order the children were read in), separated by one space, capped by the
 * attribute's own text length ({@code maxLength}) and optionally by {@code maxTokens}: the cap keeps a prefix
 * of the sorted list and drops the rest, so the same children always give the same value; what it dropped is
 * counted ({@link Value#dropped}), never silent. A key that alone exceeds the length is dropped too.
 * <p>
 * Pure: no EBX class - unit-tested without a running instance.
 */
public final class ChildKeysDerivation
{
	/** {@code StagingAttribute.derivation} value this class documents. */
	public static final String DERIVATION_CHILD_KEYS = "CHILD_KEYS";

	/** {@link Definition#encoding} value: every key becomes an opaque token of letters ({@link #opaque}) - the default. */
	public static final String ENCODING_HASH = "hash";

	/** {@link Definition#encoding} value: the readable key, {@code 75008_12_RUEDELAPAIX}. */
	public static final String ENCODING_PLAIN = "plain";

	/** Length of an opaque token: 26^13 = 2.48e18 exceeds 2^61 = 2.31e18, so 13 letters hold the 61 bits kept from the hash. */
	public static final int TOKEN_LENGTH = 13;

	private static final Charset UTF_8 = Charset.forName("UTF-8");

	/** Joins the parts of ONE child key. Removed from inside a part, so it cannot occur in one. */
	public static final char PART_SEPARATOR = '_';

	/** Separates the keys of the children inside the value. Whitespace is removed from inside a part, so it cannot occur in a key. */
	public static final char TOKEN_SEPARATOR = ' ';

	/** The text length used when the attribute carries no {@code maxLength} of its own - the length the lot documents for the field. */
	public static final int DEFAULT_MAX_LENGTH = 2000;

	private static final Pattern NAME = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private ChildKeysDerivation()
	{
	}

	/** The parsed {@code derivationArgument} of one {@code CHILD_KEYS} attribute. */
	public static final class Definition
	{
		/** Name of the child staging entity (e.g. {@code Adresse}). */
		public final String childEntity;
		/** The child attributes the key is built from, in key order (e.g. {@code postalCode}, {@code houseNumber}, {@code street}). */
		public final List<String> fields;
		/** The subset of {@link #fields} that must be non-empty for a child record to contribute a key - every field when the argument names none. */
		public final Set<String> required;
		/** The child attribute that refers to the parent entity, when the child has several such attributes; {@code null} = the only one. */
		public final String reference;
		/** A cap on the number of keys kept per parent, or {@code null} (only the text length caps). */
		public final Integer maxTokens;
		/** How a key is written: {@link ChildKeysDerivation#ENCODING_HASH} (the default) or {@link ChildKeysDerivation#ENCODING_PLAIN}. Never {@code null}. */
		public final String encoding;

		Definition(final String pChildEntity, final List<String> pFields, final Set<String> pRequired, final String pReference, final Integer pMaxTokens,
			final String pEncoding)
		{
			this.childEntity = pChildEntity;
			this.fields = Collections.unmodifiableList(pFields);
			this.required = Collections.unmodifiableSet(pRequired);
			this.reference = pReference;
			this.maxTokens = pMaxTokens;
			this.encoding = pEncoding;
		}
	}

	/**
	 * Parses {@code {"child":"Adresse","fields":["postalCode","houseNumber","street"]}} plus the optional {@code
	 * "required":[...]} (a subset of {@code fields}), {@code "reference":"personne"}, {@code "maxTokens":N} and {@code "encoding":"hash"|"plain"}
	 * (absent = {@code hash}).
	 *
	 * @throws IllegalArgumentException with a message a person can act on: blank or malformed JSON, a missing or
	 *             badly named child/field, a duplicated field, a {@code required} entry that is not a field, a
	 *             non-positive {@code maxTokens}, an {@code encoding} that is neither {@code hash} nor {@code plain}. Names must match {@code [A-Za-z][A-Za-z0-9_]*} (the pattern of
	 *             a staging attribute name).
	 */
	public static Definition parse(final String pJson)
	{
		if (pJson == null || pJson.trim().isEmpty())
		{
			throw new IllegalArgumentException("the derivation argument is empty: a CHILD_KEYS attribute needs {\"child\":\"<child entity>\",\"fields\":[\"<child attribute>\",...]}");
		}
		final JsonNode root;
		try
		{
			root = ChildKeysDerivation.MAPPER.readTree(pJson);
		}
		catch (final Exception ex)
		{
			throw new IllegalArgumentException("the derivation argument is not valid JSON: " + ex.getMessage());
		}
		if (root == null || !root.isObject())
		{
			throw new IllegalArgumentException("the derivation argument must be a JSON object");
		}
		final String child = ChildKeysDerivation.name(root.path("child"), "child");
		final JsonNode fieldsNode = root.path("fields");
		if (!fieldsNode.isArray() || fieldsNode.size() == 0)
		{
			throw new IllegalArgumentException("\"fields\" must be a non-empty array of child attribute names");
		}
		final List<String> fields = new ArrayList<String>();
		for (final JsonNode field : fieldsNode)
		{
			final String name = ChildKeysDerivation.name(field, "fields[]");
			if (fields.contains(name))
			{
				throw new IllegalArgumentException("\"fields\" names '" + name + "' twice");
			}
			fields.add(name);
		}
		final Set<String> required = new LinkedHashSet<String>();
		final JsonNode requiredNode = root.path("required");
		if (requiredNode.isMissingNode() || requiredNode.isNull())
		{
			required.addAll(fields);
		}
		else
		{
			if (!requiredNode.isArray())
			{
				throw new IllegalArgumentException("\"required\" must be an array of child attribute names");
			}
			for (final JsonNode entry : requiredNode)
			{
				final String name = ChildKeysDerivation.name(entry, "required[]");
				if (!fields.contains(name))
				{
					throw new IllegalArgumentException("\"required\" names '" + name + "', which is not one of \"fields\"");
				}
				required.add(name);
			}
		}
		final JsonNode referenceNode = root.path("reference");
		final String reference = referenceNode.isMissingNode() || referenceNode.isNull() ? null : ChildKeysDerivation.name(referenceNode, "reference");
		final JsonNode maxTokensNode = root.path("maxTokens");
		Integer maxTokens = null;
		if (!maxTokensNode.isMissingNode() && !maxTokensNode.isNull())
		{
			if (!maxTokensNode.isIntegralNumber() || maxTokensNode.asInt() < 1)
			{
				throw new IllegalArgumentException("\"maxTokens\" must be a positive integer");
			}
			maxTokens = Integer.valueOf(maxTokensNode.asInt());
		}
		final JsonNode encodingNode = root.path("encoding");
		String encoding = ChildKeysDerivation.ENCODING_HASH;
		if (!encodingNode.isMissingNode() && !encodingNode.isNull())
		{
			final String text = encodingNode.isTextual() ? encodingNode.asText().trim() : null;
			if (!ChildKeysDerivation.ENCODING_HASH.equals(text) && !ChildKeysDerivation.ENCODING_PLAIN.equals(text))
			{
				throw new IllegalArgumentException("\"encoding\" must be \"" + ChildKeysDerivation.ENCODING_HASH + "\" (opaque tokens, the default) or \""
					+ ChildKeysDerivation.ENCODING_PLAIN + "\" (readable keys)" + (encodingNode.isTextual() ? ", got '" + encodingNode.asText() + "'" : ""));
			}
			encoding = text;
		}
		return new Definition(child, fields, required, reference, maxTokens, encoding);
	}

	/** The child entity named by a derivation argument, or {@code null} when the argument does not parse - for a label, never for a decision. */
	public static String childEntityOrNull(final String pJson)
	{
		try
		{
			return ChildKeysDerivation.parse(pJson).childEntity;
		}
		catch (final IllegalArgumentException ex)
		{
			return null;
		}
	}

	private static String name(final JsonNode pNode, final String pWhere)
	{
		final String text = pNode == null || !pNode.isTextual() ? null : pNode.asText().trim();
		if (text == null || !ChildKeysDerivation.NAME.matcher(text).matches())
		{
			throw new IllegalArgumentException("\"" + pWhere + "\" must be an attribute name ([A-Za-z][A-Za-z0-9_]*)" + (text == null ? "" : ", got '" + text + "'"));
		}
		return text;
	}

	/**
	 * One part of a key: the house matching fold ({@link CleaningSteps#foldKey}: accents stripped, upper case), then every whitespace/space character, control
	 * character and {@link #PART_SEPARATOR} removed. {@code null} when nothing is left (or the input is {@code null}).
	 * <p>
	 * Lot M10 (2026-10-04): written as ONE character loop. It used to call {@code foldKey}, whose trim and {@code replaceAll("\\s+", " ")} (a regex compiled at each
	 * call) are pointless here - every character they touch is removed afterwards - and whose {@code stripAccents} runs a {@link Normalizer} even on plain ASCII.
	 * The result is the same string as {@code foldKey} followed by the removal, for every input ({@code ChildKeysDerivationTest.partIsTheOldFoldForAnyInput}
	 * compares both over a random corpus): ASCII takes a loop with no allocation but the output, anything else goes through {@link CleaningSteps#stripAccents}
	 * and {@link String#toUpperCase(Locale)} exactly as before.
	 */
	public static String part(final String pRaw)
	{
		if (pRaw == null)
		{
			return null;
		}
		boolean ascii = true;
		for (int i = 0; i < pRaw.length(); i++)
		{
			if (pRaw.charAt(i) > 0x7f)
			{
				ascii = false;
				break;
			}
		}
		final StringBuilder out = new StringBuilder(pRaw.length());
		if (ascii)
		{
			for (int i = 0; i < pRaw.length(); i++)
			{
				final char c = pRaw.charAt(i);
				// ASCII characters the removal drops: space, every control character (they include the whitespace ones), DEL and the part separator
				if (c <= ' ' || c == 0x7f || c == ChildKeysDerivation.PART_SEPARATOR)
				{
					continue;
				}
				out.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
			}
			return out.length() == 0 ? null : out.toString();
		}
		final String folded = CleaningSteps.stripAccents(pRaw).toUpperCase(Locale.ROOT);
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

	/**
	 * The key of one child record.
	 *
	 * @param pRawParts the child's values for {@link Definition#fields}, in the same order (missing values as {@code null})
	 * @return the key in the definition's {@link Definition#encoding}: the parts joined by {@link #PART_SEPARATOR} ({@code plain}) or the {@link #opaque} token
	 *         of that string ({@code hash}); {@code null} when a REQUIRED part is empty, or when no part has a value
	 */
	public static String token(final Definition pDefinition, final List<String> pRawParts)
	{
		final String plain = ChildKeysDerivation.plainKey(pDefinition, pRawParts);
		return plain == null || ChildKeysDerivation.ENCODING_PLAIN.equals(pDefinition.encoding) ? plain : ChildKeysDerivation.opaque(plain);
	}

	/**
	 * The opaque token of a readable key: the first 8 bytes of SHA-256 of its UTF-8 bytes, read big-endian, the top 3 bits dropped (61 bits), written in base 26 with
	 * {@code a}-{@code z} on exactly {@value #TOKEN_LENGTH} letters, most significant first. Deterministic across JVMs and runs; no digit, separator or accent, so a
	 * text analyzer cannot cut it. Two different keys collide with a probability of about 2^-61 per pair (a birthday bound of ~1.5 billion distinct keys for even odds).
	 */
	public static String opaque(final String pKey)
	{
		return ChildKeysDerivation.render(ChildKeysDerivation.opaqueValue(ChildKeysDerivation.newDigest(), pKey));
	}

	private static MessageDigest newDigest()
	{
		try
		{
			return MessageDigest.getInstance("SHA-256");
		}
		catch (final NoSuchAlgorithmException ex)
		{
			throw new IllegalStateException("SHA-256 is mandatory in every JVM", ex);
		}
	}

	/** The 61 bits {@link #opaque} writes as letters: the first 8 bytes of SHA-256 big-endian, top 3 bits dropped. Never negative. */
	private static long opaqueValue(final MessageDigest pDigest, final String pKey)
	{
		pDigest.reset();
		final byte[] digest = pDigest.digest(pKey.getBytes(ChildKeysDerivation.UTF_8));
		long value = 0;
		for (int i = 0; i < 8; i++)
		{
			value = (value << 8) | (digest[i] & 0xffL);
		}
		return value >>> 3;
	}

	/** The {@value #TOKEN_LENGTH} letters of a 61-bit value, base 26, most significant first: numeric order of two values is the {@code String} order of their letters. */
	private static String render(final long pValue)
	{
		long value = pValue;
		final char[] out = new char[ChildKeysDerivation.TOKEN_LENGTH];
		for (int i = out.length - 1; i >= 0; i--)
		{
			out[i] = (char) ('a' + (int) (value % 26));
			value /= 26;
		}
		return new String(out);
	}

	/** The readable key of one child record ({@link #token} before the encoding). */
	static String plainKey(final Definition pDefinition, final List<String> pRawParts)
	{
		final StringBuilder out = new StringBuilder();
		boolean any = false;
		for (int i = 0; i < pDefinition.fields.size(); i++)
		{
			final String raw = pRawParts == null || i >= pRawParts.size() ? null : pRawParts.get(i);
			final String part = ChildKeysDerivation.part(raw);
			if (part == null)
			{
				if (pDefinition.required.contains(pDefinition.fields.get(i)))
				{
					return null;
				}
			}
			else
			{
				any = true;
				out.append(part);
			}
			if (i < pDefinition.fields.size() - 1)
			{
				out.append(ChildKeysDerivation.PART_SEPARATOR);
			}
		}
		return any ? out.toString() : null;
	}

	/**
	 * The key of a child record as a {@code long}, with a bounded cache of folded parts (lot M10, 2026-10-04). The sweep of 889,165 child rows folded every part of
	 * every row (three regex-and-Normalizer folds, one SHA-256, one string per row, three times over); this class does the same work once per row at a fraction of
	 * the cost:
	 * <ul>
	 * <li>each DISTINCT raw value of a part is folded once, in a small LRU map per part ({@link #CACHE_ENTRIES}) - a postal code or a street name repeats thousands of
	 * times, and a raw value longer than {@link #MAX_CACHED_LENGTH} is folded without being cached;</li>
	 * <li>one {@link MessageDigest} is kept instead of {@code getInstance} at every row;</li>
	 * <li>with {@code hash} the code is the 61 bits {@link #opaque} writes as letters, so two codes order exactly like the two tokens, and a child key costs 8 bytes
	 * instead of a 13-character string; with {@code plain} the code is the index of the readable key in a dictionary of the keys seen ({@link #reset} empties it:
	 * its order is the order of arrival, not the key order - the caller sorts rendered strings).</li>
	 * </ul>
	 * {@code render(encode(parts))} is exactly {@link ChildKeysDerivation#token} of the same parts (tested). Not thread-safe: one encoder per sweep.
	 */
	public static final class KeyEncoder
	{
		/** Returned by {@link #encode} for a child that contributes no key. Never a valid code. */
		public static final long NO_KEY = -1L;

		/** Folded parts kept per part position; about 8 MB per part for typical address text. */
		public static final int CACHE_ENTRIES = 50000;

		/** A raw value longer than this is folded but not cached (the cache is bounded in entries, so this bounds it in bytes too). */
		public static final int MAX_CACHED_LENGTH = 128;

		private final boolean plain;
		private final int fieldCount;
		private final boolean[] required;
		private final List<Map<String, String>> caches = new ArrayList<Map<String, String>>();
		private final MessageDigest digest = ChildKeysDerivation.newDigest();
		private final StringBuilder key = new StringBuilder(64);
		private final Map<String, Long> dictionary = new HashMap<String, Long>();
		private final List<String> dictionaryKeys = new ArrayList<String>();
		/** Parts served from the cache. */
		public long cacheHits;
		/** Parts folded (a miss, or a value too long to cache). */
		public long folds;

		public KeyEncoder(final Definition pDefinition, final int pCacheEntries)
		{
			this.plain = ChildKeysDerivation.ENCODING_PLAIN.equals(pDefinition.encoding);
			this.fieldCount = pDefinition.fields.size();
			this.required = new boolean[this.fieldCount];
			for (int i = 0; i < this.fieldCount; i++)
			{
				this.required[i] = pDefinition.required.contains(pDefinition.fields.get(i));
				final int max = Math.max(1, pCacheEntries);
				this.caches.add(new LinkedHashMap<String, String>(1024, 0.75f, true)
				{
					private static final long serialVersionUID = 1L;

					@Override
					protected boolean removeEldestEntry(final Map.Entry<String, String> pEldest)
					{
						return this.size() > max;
					}
				});
			}
		}

		/** The code of one child's key; {@link #NO_KEY} when a REQUIRED part is empty or no part has a value (same rule as {@link ChildKeysDerivation#token}). */
		public long encode(final List<String> pRawParts)
		{
			this.key.setLength(0);
			boolean any = false;
			for (int i = 0; i < this.fieldCount; i++)
			{
				final String raw = pRawParts == null || i >= pRawParts.size() ? null : pRawParts.get(i);
				final String part = this.part(i, raw);
				if (part == null)
				{
					if (this.required[i])
					{
						return NO_KEY;
					}
				}
				else
				{
					any = true;
					this.key.append(part);
				}
				if (i < this.fieldCount - 1)
				{
					this.key.append(ChildKeysDerivation.PART_SEPARATOR);
				}
			}
			if (!any)
			{
				return NO_KEY;
			}
			final String plainKey = this.key.toString();
			if (!this.plain)
			{
				return ChildKeysDerivation.opaqueValue(this.digest, plainKey);
			}
			Long id = this.dictionary.get(plainKey);
			if (id == null)
			{
				id = Long.valueOf(this.dictionaryKeys.size());
				this.dictionary.put(plainKey, id);
				this.dictionaryKeys.add(plainKey);
			}
			return id.longValue();
		}

		/** The token a code stands for: the 13 letters ({@code hash}) or the readable key ({@code plain}, only for a code given since the last {@link #reset}). */
		public String render(final long pCode)
		{
			return this.plain ? this.dictionaryKeys.get((int) pCode) : ChildKeysDerivation.render(pCode);
		}

		/** Forgets the dictionary of readable keys (a no-op with {@code hash}): call it when the codes given so far are no longer needed. The part caches stay. */
		public void reset()
		{
			this.dictionary.clear();
			this.dictionaryKeys.clear();
		}

		private String part(final int pIndex, final String pRaw)
		{
			if (pRaw == null)
			{
				return null;
			}
			if (pRaw.length() > MAX_CACHED_LENGTH)
			{
				this.folds++;
				return ChildKeysDerivation.part(pRaw);
			}
			final Map<String, String> cache = this.caches.get(pIndex);
			final String cached = cache.get(pRaw);
			if (cached != null)
			{
				this.cacheHits++;
				return cached.isEmpty() ? null : cached; // "" stands for "nothing left after the fold"; a real part is never empty
			}
			this.folds++;
			final String part = ChildKeysDerivation.part(pRaw);
			cache.put(pRaw, part == null ? "" : part);
			return part;
		}
	}

	/** The value of one parent, with what the cap dropped. */
	public static final class Value
	{
		/** The keys joined by {@link ChildKeysDerivation#TOKEN_SEPARATOR}; {@code null} when no key is kept (the parent has none, or every one was dropped). */
		public final String text;
		/** How many distinct keys the value holds. */
		public final int kept;
		/** How many distinct keys the cap ({@code maxLength} or {@code maxTokens}) left out. */
		public final int dropped;

		Value(final String pText, final int pKept, final int pDropped)
		{
			this.text = pText;
			this.kept = pKept;
			this.dropped = pDropped;
		}
	}

	/**
	 * The value of one parent from the keys of its children.
	 *
	 * @param pTokens the child keys, in any order, duplicates welcome ({@code null} and blank entries are ignored)
	 * @param pMaxLength the longest text allowed; zero or negative reads as {@link #DEFAULT_MAX_LENGTH}
	 * @param pMaxTokens the most keys allowed, or {@code null}
	 */
	public static Value value(final Collection<String> pTokens, final int pMaxLength, final Integer pMaxTokens)
	{
		final int maxLength = pMaxLength > 0 ? pMaxLength : ChildKeysDerivation.DEFAULT_MAX_LENGTH;
		final TreeSet<String> distinct = new TreeSet<String>();
		if (pTokens != null)
		{
			for (final String token : pTokens)
			{
				if (token != null && !token.trim().isEmpty())
				{
					distinct.add(token);
				}
			}
		}
		final StringBuilder out = new StringBuilder();
		int kept = 0;
		boolean full = false;
		for (final String token : distinct)
		{
			if (full)
			{
				break;
			}
			if (pMaxTokens != null && kept >= pMaxTokens.intValue())
			{
				full = true;
				break;
			}
			final int needed = token.length() + (kept == 0 ? 0 : 1);
			if (token.length() > maxLength)
			{
				continue; // a key that alone does not fit is dropped; shorter ones after it still can
			}
			if (out.length() + needed > maxLength)
			{
				full = true;
				break;
			}
			if (kept > 0)
			{
				out.append(ChildKeysDerivation.TOKEN_SEPARATOR);
			}
			out.append(token);
			kept++;
		}
		return new Value(kept == 0 ? null : out.toString(), kept, distinct.size() - kept);
	}
}
