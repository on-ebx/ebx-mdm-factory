package com.on.ebx.factory.rejects;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. Staging load and rejects lot (2026-10-17), item 2: an EBX refusal is classified (type conversion named by field, column and value),
 * the reasons are stored as English + marker and read back in the viewer's language, the double escaping is gone, identical reasons share a signature.
 */
public final class RejectReasonTest
{
	private static final Locale FR = Locale.FRENCH;
	/** What the live test stored for the loyalty customers (2026-10-17), HTML-escaped by EBX and prefixed by the loader. */
	private static final String LIVE = "EBX refused the save: Unexpected error...\n\r &gt; Caused by: ODateFormat[objectConverters=null, stringConverters=null]"
		+ "conversion error while handling input string '10/06/1963'";

	public static void main(final String[] pArgs)
	{
		RejectReasonTest.textCleaning();
		RejectReasonTest.dateConversionIsNamed();
		RejectReasonTest.otherConversions();
		RejectReasonTest.unknownRefusalKeepsTheRawText();
		RejectReasonTest.storedRoundTrip();
		RejectReasonTest.englishForRest();
		RejectReasonTest.legacyRowsAreRecognised();
		RejectReasonTest.signatureGroupsIdenticalReasons();
		RejectReasonTest.everyCodeIsWordedInBothLocales();
		RejectReasonTest.parentReasonNests();
		RejectReasonTest.storeNeverCutsAMarker();
		System.out.println("RejectReasonTest OK");
	}

	private static List<RejectClassifier.FieldInfo> fields()
	{
		return Arrays.asList(new RejectClassifier.FieldInfo("customerId", "member_id", "STRING"), new RejectClassifier.FieldInfo("birthDate", "date_naissance", "DATE"),
			new RejectClassifier.FieldInfo("createdOn", "created", "TIMESTAMP"), new RejectClassifier.FieldInfo("loyaltyPoints", "points", "INTEGER"));
	}

	private static Map<String, Object> row(final String pBirth)
	{
		final Map<String, Object> row = new LinkedHashMap<String, Object>();
		row.put("customerId", "FID1002929385");
		row.put("birthDate", pBirth);
		row.put("createdOn", "2026-10-01T10:00:00");
		row.put("loyaltyPoints", "12");
		return row;
	}

	private static void textCleaning()
	{
		RejectReasonTest.check("entities are unescaped once", "a > b < c & \"d\" 'e' f".equals(RejectText.unescapeEntities("a &gt; b &lt; c &amp; &quot;d&quot; &#39;e&#39; f")));
		RejectReasonTest.check("a text with no entity is returned as it is", "plain".equals(RejectText.unescapeEntities("plain")));
		RejectReasonTest.check("one line, no literal &gt;", !RejectText.oneLine(RejectReasonTest.LIVE).contains("&gt;") && !RejectText.oneLine(RejectReasonTest.LIVE).contains("\n"));
		RejectReasonTest.check("the detail keeps its line break, normalised", RejectText.detail("a\n\rb").equals("a\nb"));
	}

	private static void dateConversionIsNamed()
	{
		final RejectReason reason = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), RejectReasonTest.row("10/06/1963"));
		RejectReasonTest.check("a conversion refusal is an EBX refusal", RejectCategory.EBX_REFUSAL.equals(reason.category));
		RejectReasonTest.check("it is a date conversion: " + reason.code, RejectReason.CONVERT_DATE.equals(reason.code));
		RejectReasonTest.check("the staging field is found by its value: " + reason.field(), "birthDate".equals(reason.field()));
		RejectReasonTest.check("the source column is named: " + reason.column(), "date_naissance".equals(reason.column()));
		RejectReasonTest.check("the value is the input string", "10/06/1963".equals(reason.args[2]));
		final String en = reason.english();
		RejectReasonTest.check("English names field, column and value: " + en, en.contains("birthDate") && en.contains("date_naissance") && en.contains("10/06/1963") && en.contains("not a valid date"));
		final String fr = reason.sentence(RejectReasonTest.FR);
		RejectReasonTest.check("French names them too: " + fr, fr.contains("birthDate") && fr.contains("date_naissance") && fr.contains("10/06/1963") && fr.contains("date valide"));
		final String fix = reason.fix(RejectReasonTest.FR);
		RejectReasonTest.check("the fix points at the source column and the mapping matrix: " + fix, fix.contains("date_naissance") && fix.contains("matrice"));
		RejectReasonTest.check("the raw text is kept as detail, once unescaped", reason.detail.contains("> Caused by: ODateFormat") && !reason.detail.contains("&gt;"));
		RejectReasonTest.check("no sentence still holds a literal entity", !fr.contains("&gt;") && !en.contains("&gt;") && !fr.contains("&amp;"));
		// the same message with a value that no field holds: the date is still named, the field is not
		final RejectReason unknown = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), RejectReasonTest.row("something else"));
		RejectReasonTest.check("a value no field holds: date conversion, no field", RejectReason.CONVERT_DATE.equals(unknown.code) && unknown.field().isEmpty());
		RejectReasonTest.check("a sentence without a field says so", unknown.sentence(RejectReasonTest.FR).startsWith("Un champ"));
	}

	private static void otherConversions()
	{
		final String dateTime = "Unexpected error... Caused by: ODateTimeFormat[objectConverters=null]conversion error while handling input string '2026-09-17T22:13.000'";
		final Map<String, Object> row = RejectReasonTest.row("1963-06-10");
		row.put("createdOn", "2026-09-17T22:13.000");
		final RejectReason reason = RejectClassifier.refusal(dateTime, RejectReasonTest.fields(), row);
		RejectReasonTest.check("a date-time conversion: " + reason.code + " " + reason.field(), RejectReason.CONVERT_DATETIME.equals(reason.code) && "createdOn".equals(reason.field()));
		// an unknown converter name: the field's own type tells the kind
		final Map<String, Object> points = RejectReasonTest.row("1963-06-10");
		points.put("loyaltyPoints", "12,5");
		final RejectReason number = RejectClassifier.refusal("conversion error while handling input string '12,5'", RejectReasonTest.fields(), points);
		RejectReasonTest.check("an unnamed converter takes the field's type: " + number.code, RejectReason.CONVERT_INTEGER.equals(number.code) && "loyaltyPoints".equals(number.field()));
		final RejectReason decimal = RejectClassifier.refusal("ODecimalFormat[x]conversion error while handling input string 'abc'", null, null);
		RejectReasonTest.check("a decimal converter", RejectReason.CONVERT_DECIMAL.equals(decimal.code));
		final RejectReason bool = RejectClassifier.refusal("OBooleanFormat[x]conversion error while handling input string 'maybe'", null, null);
		RejectReasonTest.check("a boolean converter", RejectReason.CONVERT_BOOLEAN.equals(bool.code));
		// two fields holding the same text: the one whose type agrees with the converter wins
		final Map<String, Object> same = new LinkedHashMap<String, Object>();
		same.put("customerId", "10/06/1963");
		same.put("birthDate", "10/06/1963");
		final RejectReason agreed = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), same);
		RejectReasonTest.check("the type that agrees wins: " + agreed.field(), "birthDate".equals(agreed.field()));
	}

	private static void unknownRefusalKeepsTheRawText()
	{
		final RejectReason reason = RejectClassifier.refusal("EBX refused the save: Unexpected error...\n\r &gt; Caused by: something else &amp; more", RejectReasonTest.fields(),
			RejectReasonTest.row("x"));
		RejectReasonTest.check("a message that is not a conversion stays a generic refusal", RejectReason.EBX_REFUSED.equals(reason.code) && RejectCategory.EBX_REFUSAL.equals(reason.category));
		RejectReasonTest.check("its sentence is on one line and unescaped: " + reason.english(), reason.english().contains("> Caused by: something else & more") && !reason.english().contains("\n"));
		RejectReasonTest.check("the prefix the loader added is not repeated: " + reason.english(), !reason.english().contains("EBX refused the save: EBX refused the save"));
		RejectReasonTest.check("the raw text is the detail", reason.detail.contains("something else & more"));
	}

	private static void storedRoundTrip()
	{
		final RejectReason date = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), RejectReasonTest.row("10/06/1963"));
		final String stored = RejectReasons.store(Arrays.asList(date, RejectReason.mandatory("email", "mail")), 2000);
		RejectReasonTest.check("each reason carries its marker: " + stored, stored.contains("[mdmf:rejects.reason.convert.date|") && stored.contains("[mdmf:rejects.reason.mandatory|"));
		RejectReasonTest.check("no raw EBX text in the stored reasons", !stored.contains("ODateFormat") && !stored.contains("Caused by"));
		final List<RejectReasons.Shown> fr = RejectReasons.read(stored, "EBX_REFUSAL", date.detail, RejectReasonTest.FR);
		RejectReasonTest.check("two reasons read back: " + fr.size(), fr.size() == 2);
		RejectReasonTest.check("in French: " + fr.get(0).sentence, fr.get(0).sentence.contains("date valide") && fr.get(1).sentence.contains("obligatoire"));
		RejectReasonTest.check("the raw detail goes with the first reason only", fr.get(0).raw.contains("Caused by") && fr.get(1).raw.isEmpty());
		RejectReasonTest.check("a fix is given for the first, none needed for none", !fr.get(0).fix.isEmpty());
		final List<RejectReasons.Shown> en = RejectReasons.read(stored, "EBX_REFUSAL", "", Locale.ENGLISH);
		RejectReasonTest.check("in English: " + en.get(0).sentence, en.get(0).sentence.contains("not a valid date"));
	}

	private static void englishForRest()
	{
		final RejectReason date = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), RejectReasonTest.row("10/06/1963"));
		final String stored = RejectReasons.store(Arrays.asList(date), 2000);
		final String english = RejectReasons.english(stored);
		RejectReasonTest.check("REST reads plain English, no marker: " + english, !english.contains("[mdmf:") && english.contains("not a valid date") && english.equals(date.english()));
	}

	private static void legacyRowsAreRecognised()
	{
		// a row written before the lot: no marker, the loader's prefix, EBX's escaped text
		final List<RejectReasons.Shown> shown = RejectReasons.read(RejectReasonTest.LIVE, null, null, RejectReasonTest.FR);
		RejectReasonTest.check("an old conversion refusal is recognised: " + shown.size(), shown.size() == 1);
		RejectReasonTest.check("it is worded in French with the value: " + shown.get(0).sentence, shown.get(0).sentence.contains("10/06/1963") && shown.get(0).sentence.contains("date valide"));
		RejectReasonTest.check("the raw text is folded detail without the double escaping", shown.get(0).raw.contains("> Caused by") && !shown.get(0).raw.contains("&gt;"));
		RejectReasonTest.check("and still groups with the others of its kind", shown.get(0).signature.startsWith("convert.date|"));
		final List<RejectReasons.Shown> other = RejectReasons.read("Customer: 5 characters, maximum 3; other: mandatory but empty", null, null, RejectReasonTest.FR);
		RejectReasonTest.check("an old text that is not a conversion is shown as it is", other.size() == 1 && other.get(0).sentence.equals("Customer: 5 characters, maximum 3; other: mandatory but empty")
			&& other.get(0).signature.isEmpty());
		RejectReasonTest.check("an empty text has no reason", RejectReasons.read("", null, null, RejectReasonTest.FR).isEmpty() && RejectReasons.read(null, null, null, RejectReasonTest.FR).isEmpty());
	}

	private static void signatureGroupsIdenticalReasons()
	{
		final RejectReason a = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), RejectReasonTest.row("10/06/1963"));
		final String other = RejectReasonTest.LIVE.replace("10/06/1963", "24/07/1962");
		final RejectReason b = RejectClassifier.refusal(other, RejectReasonTest.fields(), RejectReasonTest.row("24/07/1962"));
		RejectReasonTest.check("the value is not part of the signature: " + a.signature(), a.signature().equals(b.signature()) && !a.signature().contains("1963"));
		RejectReasonTest.check("the signature names code, field and column: " + a.signature(), "convert.date|birthDate|date_naissance".equals(a.signature()));
		RejectReasonTest.check("a different field is a different group", !a.signature().equals(RejectReason.mandatory("email", "mail").signature()));
		RejectReasonTest.check("the group wording has no value: " + a.group(RejectReasonTest.FR), !a.group(RejectReasonTest.FR).contains("1963") && a.group(RejectReasonTest.FR).contains("birthDate"));
	}

	private static void everyCodeIsWordedInBothLocales()
	{
		final List<RejectReason> all = new ArrayList<RejectReason>();
		for (final String kind : new String[] { RejectReason.CONVERT_DATE, RejectReason.CONVERT_DATETIME, RejectReason.CONVERT_INTEGER, RejectReason.CONVERT_DECIMAL,
			RejectReason.CONVERT_BOOLEAN, RejectReason.CONVERT_VALUE })
		{
			all.add(RejectReason.convert(kind, "f", "col", "v", ""));
		}
		all.add(RejectReason.tooLong("f", "col", 12, 10));
		all.add(RejectReason.mandatory("f", "col"));
		all.add(RejectReason.unknownParent("f", "col", "Customer", "S|1"));
		all.add(RejectReason.quality("f", "col", "PATTERN", "[A-Z]+"));
		all.add(RejectReason.ebxRefused("boom"));
		all.add(RejectReason.compute("x"));
		all.add(RejectReason.read("x"));
		all.add(RejectReason.noKey());
		all.add(RejectReason.sourceIdEmpty("Address", "feed7"));
		all.add(RejectReason.parentRejected("Customer", RejectReason.mandatory("f", "col")));
		for (final RejectReason reason : all)
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, RejectReasonTest.FR })
			{
				final String sentence = reason.sentence(locale);
				RejectReasonTest.check(reason.code + " (" + locale + "): worded, not the raw key: " + sentence, !sentence.startsWith(RejectReason.P) && !sentence.isEmpty());
				RejectReasonTest.check(reason.code + " (" + locale + "): no placeholder left: " + sentence, sentence.indexOf('{') < 0 && sentence.indexOf("''") < 0);
				final String group = reason.group(locale);
				RejectReasonTest.check(reason.code + " (" + locale + "): group wording: " + group, !group.startsWith("rejects.") && group.indexOf('{') < 0);
				final String fix = reason.fix(locale);
				RejectReasonTest.check(reason.code + " (" + locale + "): no placeholder in the fix: " + fix, fix.indexOf('{') < 0 && fix.indexOf("''") < 0);
			}
			RejectReasonTest.check(reason.code + ": the English sentence is the stored one", reason.stored(2000).startsWith(reason.english()));
		}
		RejectReasonTest.check("a field and its column in French: " + RejectReason.mandatory("email", "mail").sentence(RejectReasonTest.FR),
			RejectReason.mandatory("email", "mail").sentence(RejectReasonTest.FR).startsWith("email (colonne source mail)"));
		RejectReasonTest.check("a computed field (no column) says only the field", RejectReason.mandatory("email", "").sentence(RejectReasonTest.FR).startsWith("email :"));
	}

	private static void parentReasonNests()
	{
		final RejectReason root = RejectClassifier.refusal(RejectReasonTest.LIVE, RejectReasonTest.fields(), RejectReasonTest.row("10/06/1963"));
		final RejectReason child = RejectReason.parentRejected("Customer", root);
		RejectReasonTest.check("a rejected parent is its own category", RejectCategory.PARENT_REJECTED.equals(child.category));
		final String fr = child.sentence(RejectReasonTest.FR);
		RejectReasonTest.check("the child says why its parent was rejected: " + fr, fr.startsWith("Ligne parente Customer rejet") && fr.contains("birthDate") && fr.contains("10/06/1963"));
		RejectReasonTest.check("and passes the parent's fix on: " + child.fix(RejectReasonTest.FR), child.fix(RejectReasonTest.FR).contains("date_naissance"));
		RejectReasonTest.check("rows rejected for the same parent cause share a signature: " + child.signature(), "parent|convert.date|birthDate|date_naissance".equals(child.signature()));
		final String stored = RejectReasons.store(Arrays.asList(child), 2000);
		final RejectReasons.Shown read = RejectReasons.read(stored, "PARENT_REJECTED", "", RejectReasonTest.FR).get(0);
		RejectReasonTest.check("it survives the round trip: " + read.sentence, read.sentence.equals(fr) && read.signature.equals(child.signature()));
		RejectReasonTest.check("the group wording reads the parent's group wording: " + read.group, read.group.startsWith("Ligne parente Customer") && !read.group.contains("1963"));
	}

	private static void storeNeverCutsAMarker()
	{
		final List<RejectReason> many = new ArrayList<RejectReason>();
		for (int i = 0; i < 30; i++)
		{
			many.add(RejectReason.mandatory("field" + i, "column" + i));
		}
		final String stored = RejectReasons.store(many, 400);
		RejectReasonTest.check("within the limit: " + stored.length(), stored.length() <= 400);
		RejectReasonTest.check("ends on a marker, never inside one", stored.endsWith("]") && stored.lastIndexOf("[mdmf:") > stored.lastIndexOf("; "));
		RejectReasonTest.check("every kept reason reads back", RejectReasons.read(stored, null, null, Locale.ENGLISH).size() == stored.split("; ", -1).length);
		RejectReasonTest.check("the first reason is always kept, marker whole", RejectReasons.store(many, 10).endsWith("]") && RejectReasons.store(many, 10).contains("[mdmf:rejects.reason.mandatory|s:field0|"));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
