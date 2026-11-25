package com.on.ebx.factory.quality;

import java.util.Locale;

/**
 * Check-digit algorithms with no ready-made implementation on this module's classpath (docs/NORMALISATION.md
 * sec 5.3: {@code commons-validator} is not even a dependency of this module, and it has no LEI validator
 * regardless) - about ten lines each, computed directly rather than pulled in as a dependency (the task's own
 * instruction: "implement mod 97 / Luhn yourself, they are ten lines each"). Pure: no EBX class, no regex - a
 * malformed input is simply not valid, never an exception.
 * <p>
 * <b>IBAN and LEI share the same ISO/IEC 7064 MOD 97-10 algorithm</b> (letters A-Z expand to 10-35, the
 * resulting decimal string mod 97 must equal 1) - IBAN (ISO 13616) additionally moves its first 4 characters
 * to the end before that computation; LEI (ISO 17442) does not - it checks the mod 97 of the 20-character
 * string exactly as printed.
 * <p>
 * <b>SIREN/SIRET use the plain Luhn (mod 10) algorithm</b>, not a France-specific one - this resolves the
 * knowledge base's own open question ("whether LuhnCheckDigit's exact weighting matches INSEE's SIREN
 * algorithm", docs/NORMALISATION.md sec 1.6): it does. SIRET is a SIREN plus a 5-digit NIC establishment
 * code, 14 digits total, checked as ONE 14-digit Luhn number, not two separate 9- and 5-digit checks.
 * <p>
 * <b>Every fixture in {@code CheckDigitsTest} was independently computed in Python and cross-checked against
 * this class before being embedded as a test</b> (2026-09-20), including two of this project's own real,
 * live landing values: {@code SUPPLIER.REGISTRATION_NO} "552120222" and "662042449" (both genuine French
 * SIREN, read live from {@code Companies_Landing/ERP.SUPPLIER}) and {@code SUPPLIER.LEI}
 * "MLU0ZO3ML4LN2LL2TL39" (HSBC's real LEI, same table) - plus two published IBAN examples and Bank of
 * America's real, public LEI ("529900T8BM49AURSDO55").
 * <p>
 * <b>{@link #isValidGs1Mod10} added for the quality/LLM toolbox lot (2026-09-21)</b>, so the toolbox is not
 * only oriented towards party/company identifiers - it covers a GS1 check digit (GTIN-8/12/13/14, SSCC-18,
 * any length really: the algorithm only cares about position relative to the check digit, not the overall
 * length). It is NOT the same weighting as {@link #isValidLuhn} - GS1 alternates weights 3/1 starting from
 * the data digit immediately left of the check digit, Luhn doubles every second digit counting the check
 * digit itself as position 1 - two published examples (Wikipedia's own GTIN worked examples) are cross-checked
 * in {@code CheckDigitsTest}: EAN-13 "4006381333931" and UPC-A "036000291452", both real, publicly documented
 * barcodes, plus an invalid variant of each (last digit flipped).
 */
public final class CheckDigits
{
	private CheckDigits()
	{
	}

	// --------------------------------------------------------------------------------------------------------- Luhn

	/**
	 * True when {@code pDigits} (SIREN: 9 digits, SIRET: 14 digits) is Luhn-valid: from the rightmost digit,
	 * every second digit is doubled (subtracting 9 when the result exceeds 9), and the total must be a
	 * multiple of 10. Any non-digit character, or an empty/null string, is simply not valid - never throws.
	 */
	public static boolean isValidLuhn(final String pDigits)
	{
		if (pDigits == null || pDigits.isEmpty() || !CheckDigits.isAllDigits(pDigits))
		{
			return false;
		}
		int total = 0;
		final int length = pDigits.length();
		for (int i = 0; i < length; i++)
		{
			int digit = pDigits.charAt(length - 1 - i) - '0';
			if (i % 2 == 1) // every second digit, counting from the rightmost (0-indexed here)
			{
				digit *= 2;
				if (digit > 9)
				{
					digit -= 9;
				}
			}
			total += digit;
		}
		return total % 10 == 0;
	}

	// --------------------------------------------------------------------------------------------- ISO 7064 MOD 97-10

	/**
	 * True when {@code pIban} (separators already stripped by the caller, any case) passes the ISO 13616
	 * check: the first 4 characters moved to the end, letters expanded, mod 97 == 1.
	 */
	public static boolean isValidIban(final String pIban)
	{
		if (pIban == null || pIban.length() < 5)
		{
			return false;
		}
		final String rearranged = pIban.substring(4) + pIban.substring(0, 4);
		return CheckDigits.mod97(rearranged) == 1;
	}

	/**
	 * True when {@code pLei} (20 characters, any case, separators already stripped) passes the ISO 17442
	 * check: mod 97 == 1, no rearrangement (unlike IBAN).
	 */
	public static boolean isValidLei(final String pLei)
	{
		if (pLei == null || pLei.length() != 20)
		{
			return false;
		}
		return CheckDigits.mod97(pLei) == 1;
	}

	/**
	 * Letters A-Z expand to 10-35 (ISO/IEC 7064's alphanumeric-to-numeric rule), then the resulting decimal
	 * string is reduced mod 97 digit by digit (no {@code BigInteger} needed for a 20+ character value, the
	 * standard iterative technique: {@code remainder = (remainder * 10 + nextDigit) % 97} applied twice per
	 * expanded letter). -1 on any character that is not a letter or digit (never reached for a value this
	 * class's own callers already filtered to alphanumeric, but defensive rather than throwing).
	 */
	private static int mod97(final String pValue)
	{
		int remainder = 0;
		final String upper = pValue.toUpperCase(Locale.ROOT);
		final int length = upper.length();
		for (int i = 0; i < length; i++)
		{
			final char c = upper.charAt(i);
			if (Character.isDigit(c))
			{
				remainder = (remainder * 10 + (c - '0')) % 97;
			}
			else if (c >= 'A' && c <= 'Z')
			{
				final int expanded = c - 'A' + 10; // 10-35
				remainder = (remainder * 10 + expanded / 10) % 97;
				remainder = (remainder * 10 + expanded % 10) % 97;
			}
			else
			{
				return -1;
			}
		}
		return remainder;
	}

	// --------------------------------------------------------------------------------------------------- GS1 mod 10

	/**
	 * True when {@code pDigits} (all-digit, at least 2 characters: the shortest meaningful case is one data
	 * digit plus its check digit) passes the GS1 general specification's mod-10 check: starting from the digit
	 * immediately to the LEFT of the rightmost (check) digit and moving left, weights alternate 3, 1, 3, 1, ...;
	 * the check digit is valid when {@code (10 - (weightedSum % 10)) % 10} equals the rightmost digit. Any
	 * non-digit character, or fewer than 2 characters, is simply not valid - never throws.
	 */
	public static boolean isValidGs1Mod10(final String pDigits)
	{
		if (pDigits == null || pDigits.length() < 2 || !CheckDigits.isAllDigits(pDigits))
		{
			return false;
		}
		final int length = pDigits.length();
		final int checkDigit = pDigits.charAt(length - 1) - '0';
		int sum = 0;
		int weight = 3;
		for (int i = length - 2; i >= 0; i--)
		{
			sum += (pDigits.charAt(i) - '0') * weight;
			weight = weight == 3 ? 1 : 3;
		}
		final int expected = (10 - sum % 10) % 10;
		return expected == checkDigit;
	}

	private static boolean isAllDigits(final String pText)
	{
		for (int i = 0; i < pText.length(); i++)
		{
			if (!Character.isDigit(pText.charAt(i)))
			{
				return false;
			}
		}
		return true;
	}
}
