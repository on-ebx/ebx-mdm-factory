package com.on.ebx.factory.rejects;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * JUnit-free: run the main, a failure throws. EBX refusal classes lot H4 (2026-10-17): every class of EBX refusal the loaders can meet - maximum length, list of values,
 * foreign key, mandatory, duplicate primary key, pattern, range / digit facets, blocking constraint, table trigger - is recognised from a REAL message, named by field, source
 * column and value, worded in both languages with a fix, stored with its marker, grouped by signature, and the MASTER stage words its rejects the same way. The messages are in
 * {@code src/test/resources/com/on/ebx/factory/rejects/ebx-refusals.txt}, each with where it was read (a live log, or EBX's own bundle).
 */
public final class RejectRefusalClassesTest
{
	private static final Locale FR = Locale.FRENCH;
	private static final Locale EN = Locale.ENGLISH;

	private static Map<String, Sample> samples;

	/** One real text of the resource file. */
	private static final class Sample
	{
		String source = "";
		String subject = "";
		String text = "";
		final List<String[]> frames = new ArrayList<String[]>();

		/** The three shapes EBX gives a message: bare, behind its subject (the user printer), behind the wrapper of an unexpected error. */
		String[] shapes()
		{
			final List<String> out = new ArrayList<String>();
			out.add(this.text);
			if (!this.subject.isEmpty())
			{
				out.add(this.subject + " : " + this.text);
			}
			out.add("Unexpected error...\n\r &gt; Caused by: " + this.text);
			return out.toArray(new String[0]);
		}
	}

	public static void main(final String[] pArgs) throws Exception
	{
		RejectRefusalClassesTest.load();
		RejectRefusalClassesTest.everySampleHasItsSource();
		RejectRefusalClassesTest.eachClassIsNamedAndWorded();
		RejectRefusalClassesTest.theShapeOfTheMessageDoesNotMatter();
		RejectRefusalClassesTest.aFieldIsNeverGuessed();
		RejectRefusalClassesTest.theSubjectNamesTheField();
		RejectRefusalClassesTest.conversionStillWorks();
		RejectRefusalClassesTest.unknownTextsStayGeneric();
		RejectRefusalClassesTest.constraintsAndTriggers();
		RejectRefusalClassesTest.storedAndGrouped();
		RejectRefusalClassesTest.everyNewCodeIsWordedInBothLocales();
		RejectRefusalClassesTest.masterStage();
		RejectRefusalClassesTest.bundlesKeepToOneBlockAtTheEnd();
		System.out.println("RejectRefusalClassesTest OK");
	}

	// ------------------------------------------------------------------------------------------------ resource

	private static void load() throws IOException
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		final Path file = sourceDir == null ? Paths.get("src/test/resources/com/on/ebx/factory/rejects/ebx-refusals.txt")
			: Paths.get(sourceDir).resolve("../../test/resources/com/on/ebx/factory/rejects/ebx-refusals.txt").normalize();
		RejectRefusalClassesTest.samples = new LinkedHashMap<String, Sample>();
		Sample current = null;
		for (final String line : Files.readAllLines(file, StandardCharsets.UTF_8))
		{
			if (line.startsWith("@@ "))
			{
				current = new Sample();
				RejectRefusalClassesTest.samples.put(line.substring(3).trim(), current);
			}
			else if (current != null && line.startsWith("source: "))
			{
				current.source = line.substring("source: ".length());
			}
			else if (current != null && line.startsWith("subject: "))
			{
				current.subject = line.substring("subject: ".length());
			}
			else if (current != null && line.startsWith("text: "))
			{
				current.text = line.substring("text: ".length()).replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t");
			}
			else if (current != null && line.startsWith("frame: "))
			{
				final String[] parts = line.substring("frame: ".length()).split("\\|");
				for (int i = 0; i < parts.length; i++)
				{
					parts[i] = parts[i].trim();
				}
				current.frames.add(parts);
			}
		}
	}

	private static Sample sample(final String pName)
	{
		final Sample sample = RejectRefusalClassesTest.samples.get(pName);
		RejectRefusalClassesTest.check("sample " + pName + " exists", sample != null);
		return sample;
	}

	private static void everySampleHasItsSource()
	{
		for (final Map.Entry<String, Sample> entry : RejectRefusalClassesTest.samples.entrySet())
		{
			final Sample sample = entry.getValue();
			RejectRefusalClassesTest.check(entry.getKey() + ": says where the text was read: " + sample.source, sample.source.startsWith("live") || sample.source.startsWith("bundle"));
			RejectRefusalClassesTest.check(entry.getKey() + ": has a text or a stack", !sample.text.isEmpty() || !sample.frames.isEmpty());
		}
	}

	// -------------------------------------------------------------------------------------------------- the row

	private static List<RejectClassifier.FieldInfo> fields()
	{
		return Arrays.asList(new RejectClassifier.FieldInfo("name", "nom", "STRING", Integer.valueOf(20), false, null),
			new RejectClassifier.FieldInfo("longName", "nom_long", "STRING", Integer.valueOf(40), false, null), new RejectClassifier.FieldInfo("zip", "cp", "STRING"),
			new RejectClassifier.FieldInfo("country", "pays", "STRING"), new RejectClassifier.FieldInfo("title", "civilite", "STRING"),
			new RejectClassifier.FieldInfo("family", "famille", "STRING", null, false, "Family"), new RejectClassifier.FieldInfo("code", "code", "STRING"),
			new RejectClassifier.FieldInfo("lastName", "nom_famille", "STRING", null, true, null), new RejectClassifier.FieldInfo("bic", "bic", "STRING"),
			new RejectClassifier.FieldInfo("email", "mail", "STRING"), new RejectClassifier.FieldInfo("amount", "montant", "DECIMAL"),
			new RejectClassifier.FieldInfo("rate", "taux", "DECIMAL"), new RejectClassifier.FieldInfo("quantity", "qte", "INTEGER"));
	}

	private static Map<String, Object> row(final Object... pKeyValues)
	{
		final Map<String, Object> row = new LinkedHashMap<String, Object>();
		for (int i = 0; i + 1 < pKeyValues.length; i += 2)
		{
			row.put(String.valueOf(pKeyValues[i]), pKeyValues[i + 1]);
		}
		return row;
	}

	private static String repeat(final char pChar, final int pCount)
	{
		final char[] chars = new char[pCount];
		Arrays.fill(chars, pChar);
		return new String(chars);
	}

	private static RejectReason classify(final String pSample, final Map<String, Object> pRow)
	{
		return RejectClassifier.refusal(RejectRefusalClassesTest.sample(pSample).text, RejectRefusalClassesTest.fields(), pRow);
	}

	// ------------------------------------------------------------------------------------------------ classes

	/** One class: the code, the field and column found, and what both languages say (the value, the limit, the words of the class). */
	private static void expect(final String pSample, final Map<String, Object> pRow, final String pCode, final String pField, final String pColumn, final String[] pEnglish,
		final String[] pFrench)
	{
		final RejectReason reason = RejectRefusalClassesTest.classify(pSample, pRow);
		RejectRefusalClassesTest.check(pSample + ": code " + reason.code, pCode.equals(reason.code));
		RejectRefusalClassesTest.check(pSample + ": an EBX refusal", RejectCategory.EBX_REFUSAL.equals(reason.category));
		RejectRefusalClassesTest.check(pSample + ": field " + reason.field(), pField.equals(reason.field()));
		RejectRefusalClassesTest.check(pSample + ": column " + reason.column(), pColumn.equals(reason.column()));
		final String en = reason.sentence(RejectRefusalClassesTest.EN);
		final String fr = reason.sentence(RejectRefusalClassesTest.FR);
		for (final String part : pEnglish)
		{
			RejectRefusalClassesTest.check(pSample + ": English says \"" + part + "\": " + en, en.contains(part));
		}
		for (final String part : pFrench)
		{
			RejectRefusalClassesTest.check(pSample + ": French says \"" + part + "\": " + fr, fr.contains(part));
		}
		RejectRefusalClassesTest.check(pSample + ": the raw text is the detail, once unescaped", !reason.detail.isEmpty() && !reason.detail.contains("&gt;")
			&& reason.detail.contains(RejectRefusalClassesTest.sample(pSample).text.substring(0, Math.min(12, RejectRefusalClassesTest.sample(pSample).text.length())).replace("\\n", "")));
		RejectRefusalClassesTest.check(pSample + ": a fix is given in both languages", !reason.fix(RejectRefusalClassesTest.EN).isEmpty() && !reason.fix(RejectRefusalClassesTest.FR).isEmpty());
		RejectRefusalClassesTest.check(pSample + ": no placeholder left", RejectRefusalClassesTest.clean(reason, en) && RejectRefusalClassesTest.clean(reason, fr)
			&& RejectRefusalClassesTest.clean(reason, reason.fix(RejectRefusalClassesTest.FR)) && RejectRefusalClassesTest.clean(reason, reason.fix(RejectRefusalClassesTest.EN))
			&& RejectRefusalClassesTest.clean(reason, reason.group(RejectRefusalClassesTest.EN)));
	}

	/** No unfilled {n} and no doubled quote in a sentence (the pattern of a pattern reason is the one text allowed to hold braces). */
	private static boolean clean(final RejectReason pReason, final String pSentence)
	{
		final String text = RejectReason.EBX_PATTERN.equals(pReason.code) && pReason.args.length > 3 ? pSentence.replace(pReason.args[3], "") : pSentence;
		return text.indexOf('{') < 0 && text.indexOf("''") < 0;
	}

	private static void eachClassIsNamedAndWorded()
	{
		final String longName = RejectRefusalClassesTest.repeat('x', 34);
		RejectRefusalClassesTest.expect("maxLength", RejectRefusalClassesTest.row("name", longName, "zip", "75001", "longName", RejectRefusalClassesTest.repeat('y', 30)),
			RejectReason.TOO_LONG, "name", "nom", new String[] { "name (source column nom)", "34 characters", "maximum is 20" },
			new String[] { "name (colonne source nom)", "34 caractères", "maximum est 20" });
		RejectRefusalClassesTest.expect("minLength", RejectRefusalClassesTest.row("zip", "12"), RejectReason.EBX_MIN_LENGTH, "zip", "cp",
			new String[] { "zip (source column cp)", "2 characters", "minimum is 5" }, new String[] { "zip (colonne source cp)", "2 caractères", "minimum est 5" });
		RejectRefusalClassesTest.expect("exactLength", RejectRefusalClassesTest.row("country", "FRA"), RejectReason.EBX_LENGTH, "country", "pays",
			new String[] { "country (source column pays)", "3 characters", "exactly 2" }, new String[] { "3 caractères", "exactement 2" });
		RejectRefusalClassesTest.expect("enumeration", RejectRefusalClassesTest.row("title", "V1", "name", "Anna"), RejectReason.EBX_ENUMERATION, "title", "civilite",
			new String[] { "title (source column civilite)", "'V1'", "not one of the values the field allows" },
			new String[] { "title (colonne source civilite)", "« V1 »", "ne fait pas partie des valeurs" });
		RejectRefusalClassesTest.expect("foreignKey", RejectRefusalClassesTest.row("family", "K1", "code", "K1"), RejectReason.EBX_FOREIGN_KEY, "family", "famille",
			new String[] { "family (source column famille)", "'K1'", "matches no record of the table Family" },
			new String[] { "« K1 »", "aucun enregistrement de la table Family" });
		RejectRefusalClassesTest.expect("mandatoryLabel", RejectRefusalClassesTest.row("name", "Anna"), RejectReason.MANDATORY, "lastName", "nom_famille",
			new String[] { "lastName (source column nom_famille)", "mandatory but empty" }, new String[] { "lastName (colonne source nom_famille)", "obligatoire mais vide" });
		RejectRefusalClassesTest.expect("mandatoryBare", RejectRefusalClassesTest.row("name", "Anna"), RejectReason.MANDATORY, "lastName", "nom_famille",
			new String[] { "mandatory but empty" }, new String[] { "obligatoire mais vide" });
		RejectRefusalClassesTest.expect("patternBic", RejectRefusalClassesTest.row("bic", "BAD"), RejectReason.EBX_PATTERN, "bic", "bic",
			new String[] { "bic (source column bic)", "'BAD'", "does not match the required pattern [A-Z]{6}[A-Z0-9]{2}([A-Z0-9]{3})?" },
			new String[] { "« BAD »", "ne respecte pas le motif exigé [A-Z]{6}" });
		RejectRefusalClassesTest.expect("patternEmail", RejectRefusalClassesTest.row("email", "nobody"), RejectReason.EBX_PATTERN, "email", "mail",
			new String[] { "'nobody'", "[^@\\s]+@[^@\\s]+\\.[^@\\s]+" }, new String[] { "« nobody »" });
		RejectRefusalClassesTest.expect("patternWithDot", RejectRefusalClassesTest.row("code", "abc"), RejectReason.EBX_PATTERN, "code", "code",
			new String[] { "'abc'", "required pattern [A-Z]+." }, new String[] { "« abc »" });
		RejectRefusalClassesTest.expect("totalDigits", RejectRefusalClassesTest.row("amount", "1234567.5", "rate", "0.5"), RejectReason.EBX_TOTAL_DIGITS, "amount", "montant",
			new String[] { "amount (source column montant)", "'1234567.5'", "too many digits, at most 5" }, new String[] { "trop de chiffres, 5 au plus" });
		RejectRefusalClassesTest.expect("fractionDigits", RejectRefusalClassesTest.row("rate", "0.12345", "amount", "10.50"), RejectReason.EBX_FRACTION_DIGITS, "rate", "taux",
			new String[] { "'0.12345'", "too many decimals, at most 2" }, new String[] { "trop de décimales, 2 au plus" });
		RejectRefusalClassesTest.expect("minInclusive", RejectRefusalClassesTest.row("quantity", "0", "amount", "5"), RejectReason.EBX_RANGE, "quantity", "qte",
			new String[] { "quantity (source column qte)", "'0'", "must be greater than or equal to 1" }, new String[] { "doit être supérieure ou égale à 1" });
		RejectRefusalClassesTest.expect("maxInclusive", RejectRefusalClassesTest.row("quantity", "250"), RejectReason.EBX_RANGE, "quantity", "qte",
			new String[] { "must be less than or equal to 100" }, new String[] { "inférieure ou égale à 100" });
		RejectRefusalClassesTest.expect("minExclusive", RejectRefusalClassesTest.row("rate", "0.5"), RejectReason.EBX_RANGE, "rate", "taux",
			new String[] { "must be greater than 0.5" }, new String[] { "supérieure à 0.5" });
		RejectRefusalClassesTest.expect("maxExclusive", RejectRefusalClassesTest.row("rate", "99.5"), RejectReason.EBX_RANGE, "rate", "taux",
			new String[] { "must be less than 99.5" }, new String[] { "inférieure à 99.5" });
		// a duplicate key names the key EBX gave (the long form of the log), or only says it (the user printer's short form)
		final RejectReason long1 = RejectRefusalClassesTest.classify("duplicateKeyLong", RejectRefusalClassesTest.row());
		RejectRefusalClassesTest.check("duplicate key: code " + long1.code, RejectReason.EBX_DUPLICATE_KEY.equals(long1.code) && RejectCategory.EBX_REFUSAL.equals(long1.category));
		RejectRefusalClassesTest.check("it names the key without the ./ of EBX's predicate: " + long1.english(),
			long1.english().contains("(sourceSystem='S1' and sourceId='K1')") && long1.english().startsWith("A row with the same key already exists"));
		RejectRefusalClassesTest.check("and in French: " + long1.sentence(RejectRefusalClassesTest.FR),
			long1.sentence(RejectRefusalClassesTest.FR).startsWith("Une ligne avec la même clé existe déjà (sourceSystem='S1'"));
		final RejectReason short1 = RejectRefusalClassesTest.classify("duplicateKeyShort", RejectRefusalClassesTest.row());
		RejectRefusalClassesTest.check("the short form is recognised across its line break: " + short1.english(),
			RejectReason.EBX_DUPLICATE_KEY.equals(short1.code) && "A row with the same key already exists.".equals(short1.english()));
		RejectRefusalClassesTest.check("duplicate key: a fix in French", long1.fix(RejectRefusalClassesTest.FR).contains("doublon"));
	}

	private static void theShapeOfTheMessageDoesNotMatter()
	{
		final Map<String, Map<String, Object>> rows = new LinkedHashMap<String, Map<String, Object>>();
		rows.put("maxLength", RejectRefusalClassesTest.row("name", RejectRefusalClassesTest.repeat('x', 34)));
		rows.put("minLength", RejectRefusalClassesTest.row("zip", "12"));
		rows.put("enumeration", RejectRefusalClassesTest.row("title", "V1"));
		rows.put("foreignKey", RejectRefusalClassesTest.row("family", "K1"));
		rows.put("mandatoryLabel", RejectRefusalClassesTest.row("name", "x"));
		rows.put("patternBic", RejectRefusalClassesTest.row("bic", "BAD"));
		rows.put("totalDigits", RejectRefusalClassesTest.row("amount", "1234567.5"));
		rows.put("fractionDigits", RejectRefusalClassesTest.row("rate", "0.12345"));
		rows.put("minInclusive", RejectRefusalClassesTest.row("quantity", "0"));
		rows.put("duplicateKeyLong", RejectRefusalClassesTest.row());
		rows.put("duplicateKeyShort", RejectRefusalClassesTest.row());
		for (final Map.Entry<String, Map<String, Object>> entry : rows.entrySet())
		{
			final Sample sample = RejectRefusalClassesTest.sample(entry.getKey());
			final RejectReason bare = RejectClassifier.refusal(sample.text, RejectRefusalClassesTest.fields(), entry.getValue());
			for (final String shape : sample.shapes())
			{
				// the loader's own prefix (the master stage used to add it) and a trailing wrapper change nothing
				for (final String prefix : new String[] { "", "EBX refused the save: " })
				{
					final RejectReason reason = RejectClassifier.refusal(prefix + shape, RejectRefusalClassesTest.fields(), entry.getValue());
					RejectRefusalClassesTest.check(entry.getKey() + ": same class whatever the shape (" + shape.replace('\n', ' ').replace('\r', ' ') + "): " + reason.code,
						bare.code.equals(reason.code) && bare.signature().equals(reason.signature()));
					RejectRefusalClassesTest.check(entry.getKey() + ": same arguments", Arrays.equals(bare.args, reason.args));
				}
			}
		}
	}

	private static void aFieldIsNeverGuessed()
	{
		// two fields hold a value over the limit and neither has a plan limit of its own: no field is named, the limit still is
		final Map<String, Object> two = RejectRefusalClassesTest.row("zip", RejectRefusalClassesTest.repeat('a', 30), "code", RejectRefusalClassesTest.repeat('b', 31));
		final RejectReason reason = RejectRefusalClassesTest.classify("maxLength", two);
		RejectRefusalClassesTest.check("two candidates: no field " + reason.field(), reason.field().isEmpty() && "20".equals(reason.args[3]));
		RejectRefusalClassesTest.check("the sentence says a field without naming one: " + reason.english(), reason.english().startsWith("A field: ? characters, the maximum is 20."));
		// two fields hold the quoted value: the foreign key whose plan names a target wins, a plain tie names none
		final RejectReason fk = RejectRefusalClassesTest.classify("foreignKey", RejectRefusalClassesTest.row("family", "K1", "code", "K1"));
		RejectRefusalClassesTest.check("the foreign key is the one with a target: " + fk.field(), "family".equals(fk.field()) && "Family".equals(fk.args[3]));
		final RejectReason tie = RejectRefusalClassesTest.classify("enumeration", RejectRefusalClassesTest.row("title", "V1", "code", "V1"));
		RejectRefusalClassesTest.check("a tie on a plain value names none: " + tie.field(), tie.field().isEmpty() && "V1".equals(tie.args[2]));
		RejectRefusalClassesTest.check("and says so: " + tie.sentence(RejectRefusalClassesTest.FR), tie.sentence(RejectRefusalClassesTest.FR).startsWith("Un champ : « V1 »"));
		// a pattern nobody violates (the value is not in the row): the pattern is still given, with "its value"
		final RejectReason unseen = RejectRefusalClassesTest.classify("patternBic", RejectRefusalClassesTest.row("bic", "AAAAFRPP"));
		RejectRefusalClassesTest.check("no violating value, no field: " + unseen.english(), unseen.field().isEmpty() && unseen.english().contains("its value does not match the required pattern"));
		// the foreign key without a plan target says "the table it refers to"
		final RejectReason plainFk = RejectClassifier.refusal(RejectRefusalClassesTest.sample("foreignKey").text, Arrays.asList(new RejectClassifier.FieldInfo("f", "c", "STRING")),
			RejectRefusalClassesTest.row("f", "K1"));
		RejectRefusalClassesTest.check("no target known: " + plainFk.english(), plainFk.english().contains("matches no record of the table it refers to"));
		// no row at all (a stored text read back later): the class is still found
		final RejectReason noRow = RejectClassifier.refusal(RejectRefusalClassesTest.sample("enumeration").text, null, null);
		RejectRefusalClassesTest.check("no row: " + noRow.code, RejectReason.EBX_ENUMERATION.equals(noRow.code) && noRow.field().isEmpty());
	}

	private static void theSubjectNamesTheField()
	{
		// a pattern is checked against every text of the row: with two texts that do not match it, the row alone cannot say which field EBX meant - the subject can
		final Map<String, Object> two = RejectRefusalClassesTest.row("bic", "BAD", "email", "a@b.fr");
		final String bare = RejectRefusalClassesTest.sample("patternBic").text;
		RejectRefusalClassesTest.check("ambiguous without a subject: no field", RejectRefusalClassesTest.classify("patternBic", two).field().isEmpty());
		final RejectReason named = RejectClassifier.refusal("[/root/Customer[./id=1]/bic] : " + bare, RejectRefusalClassesTest.fields(), two);
		RejectRefusalClassesTest.check("the subject names it: " + named.field() + " " + named.args[2], "bic".equals(named.field()) && "bic".equals(named.column()) && "BAD".equals(named.args[2]));
		final RejectReason wrapped = RejectClassifier.refusal("Unexpected error...\n\r &gt; Caused by: [/root/Customer[./id='X|1']/email] : " + bare, RejectRefusalClassesTest.fields(), two);
		RejectRefusalClassesTest.check("also behind the wrapper, predicates left out: " + wrapped.field(), "email".equals(wrapped.field()) && "a@b.fr".equals(wrapped.args[2]));
		final RejectReason table = RejectClassifier.refusal("In table '/root/Customer' - Selection xpath ./id=1 : " + bare, RejectRefusalClassesTest.fields(), two);
		RejectRefusalClassesTest.check("a table's subject names no field", table.field().isEmpty());
		final RejectReason unknown = RejectClassifier.refusal("[/root/Customer[./id=1]/nothing] : " + bare, RejectRefusalClassesTest.fields(), two);
		RejectRefusalClassesTest.check("a subject that is not a field of the feed is ignored", unknown.field().isEmpty());
		// the length is only stated when the row holds a value over the limit (a subject can name a field whose value is not in the row)
		final RejectReason noValue = RejectClassifier.refusal("[/root/Customer[./id=1]/name] : " + RejectRefusalClassesTest.sample("maxLength").text, RejectRefusalClassesTest.fields(),
			RejectRefusalClassesTest.row("name", "short"));
		RejectRefusalClassesTest.check("field named, length not invented: " + noValue.english(), "name".equals(noValue.field()) && "?".equals(noValue.args[2]));
	}

	private static void conversionStillWorks()
	{
		final RejectReason date = RejectClassifier.refusal(RejectRefusalClassesTest.sample("dateConversion").text,
			Arrays.asList(new RejectClassifier.FieldInfo("birthDate", "date_naissance", "DATE")), RejectRefusalClassesTest.row("birthDate", "10/06/1963"));
		RejectRefusalClassesTest.check("the conversion of lot B is untouched: " + date.signature(), "convert.date|birthDate|date_naissance".equals(date.signature()));
	}

	private static void unknownTextsStayGeneric()
	{
		final String[] unknown = { RejectRefusalClassesTest.sample("unknown").text, "Unexpected error...\n\r &gt; Caused by: requested transaction 1833 is in the future of the last transaction 1832",
			"EBX refused the save: something else entirely" };
		for (final String text : unknown)
		{
			final RejectReason reason = RejectClassifier.refusal(text, RejectRefusalClassesTest.fields(), RejectRefusalClassesTest.row("name", "x"));
			RejectRefusalClassesTest.check("generic: " + text.replace('\n', ' '), RejectReason.EBX_REFUSED.equals(reason.code) && RejectCategory.EBX_REFUSAL.equals(reason.category));
			RejectRefusalClassesTest.check("its raw text is kept folded as detail", !reason.detail.isEmpty() && !reason.detail.contains("&gt;"));
		}
	}

	// ------------------------------------------------------------------------------ constraints and triggers

	/** The error EBX raises for a blocking rule: a class whose name ends the way EBX's does (no EBX class is needed to test the reading of it). */
	private static final class UowErrorWithUserMessage extends Exception
	{
		private static final long serialVersionUID = 1L;

		UowErrorWithUserMessage(final String pMessage)
		{
			super(pMessage);
		}
	}

	private static Throwable withStack(final String pSample, final String pMessage)
	{
		final Sample sample = RejectRefusalClassesTest.sample(pSample);
		final StackTraceElement[] stack = new StackTraceElement[sample.frames.size()];
		for (int i = 0; i < stack.length; i++)
		{
			final String[] frame = sample.frames.get(i);
			stack[i] = new StackTraceElement(frame[0], frame[1], frame[2], Integer.parseInt(frame[3]));
		}
		final Throwable error = new IllegalStateException(pMessage);
		error.setStackTrace(stack);
		return new RuntimeException("wrapper", error);
	}

	private static void constraintsAndTriggers()
	{
		// a script trigger: the stack of the live failure names it, the text alone could not
		final Sample trigger = RejectRefusalClassesTest.sample("triggerScript");
		final String header = RefusalHints.header(RejectRefusalClassesTest.withStack("triggerStack", "Dataset 'Outbox' not found"));
		RejectRefusalClassesTest.check("the header names the script and its line: " + header, "[mdmf-hint trigger=DistributionAfterCreate;line=26]\n".equals(header));
		final RejectReason reason = RejectClassifier.refusal(header + trigger.text, RejectRefusalClassesTest.fields(), RejectRefusalClassesTest.row());
		RejectRefusalClassesTest.check("a trigger failure: " + reason.code, RejectReason.EBX_TRIGGER.equals(reason.code) && RejectCategory.EBX_REFUSAL.equals(reason.category));
		RejectRefusalClassesTest.check("it names the trigger and what it said: " + reason.english(),
			"The table trigger 'DistributionAfterCreate' failed: Dataset 'Outbox' not found in dataspace 'Project_Master'".equals(reason.english()));
		RejectRefusalClassesTest.check("in French: " + reason.sentence(RejectRefusalClassesTest.FR),
			"Le trigger de table « DistributionAfterCreate » a échoué : Dataset 'Outbox' not found in dataspace 'Project_Master'".equals(reason.sentence(RejectRefusalClassesTest.FR)));
		RejectRefusalClassesTest.check("the header is not part of the detail", !reason.detail.contains("mdmf-hint") && reason.detail.contains("Caused by: Dataset 'Outbox'"));
		RejectRefusalClassesTest.check("the fix says to read the detail and fix the trigger: " + reason.fix(RejectRefusalClassesTest.EN), reason.fix(RejectRefusalClassesTest.EN).contains("trigger"));
		RejectRefusalClassesTest.check("the same text with no header is NOT a trigger failure (it cannot tell)",
			RejectReason.EBX_REFUSED.equals(RejectClassifier.refusal(trigger.text, null, null).code));
		RejectRefusalClassesTest.check("no trigger frame, no header", "".equals(RefusalHints.header(RejectRefusalClassesTest.withStack("plainStack", "x"))));
		// a Java trigger has no script frame: its class names it
		final Throwable java = new IllegalStateException("boom");
		java.setStackTrace(new StackTraceElement[] { new StackTraceElement("com.acme.PriceTrigger", "handleAfterModify", "PriceTrigger.java", 40),
			new StackTraceElement("com.onwbp.adaptation.TrgModifyTriggerApply$Impl", "apply", "TrgModifyTriggerApply.java", 10) });
		RejectRefusalClassesTest.check("a Java trigger is named by its class: " + RefusalHints.header(java), "[mdmf-hint trigger=PriceTrigger;line=40]\n".equals(RefusalHints.header(java)));

		// a blocking constraint: EBX's error with a user message, no wrapper
		final String constraintHeader = RefusalHints.header(new UowErrorWithUserMessage("IBAN is incorrect."));
		RejectRefusalClassesTest.check("the header of a user-message error: " + constraintHeader, "[mdmf-hint constraint]\n".equals(constraintHeader));
		for (final String name : new String[] { "constraintScript", "constraintTable" })
		{
			final Sample sample = RejectRefusalClassesTest.sample(name);
			for (final String shape : new String[] { sample.text, sample.subject + " : " + sample.text })
			{
				final RejectReason constraint = RejectClassifier.refusal(constraintHeader + shape, RejectRefusalClassesTest.fields(), RejectRefusalClassesTest.row());
				RejectRefusalClassesTest.check(name + ": a constraint: " + constraint.code, RejectReason.CONSTRAINT.equals(constraint.code)
					&& RejectCategory.EBX_REFUSAL.equals(constraint.category));
				RejectRefusalClassesTest.check(name + ": its own message, the subject set aside: " + constraint.english(),
					("A constraint of the model refused the row: " + sample.text).equals(constraint.english()));
				RejectRefusalClassesTest.check(name + ": in French: " + constraint.sentence(RejectRefusalClassesTest.FR),
					("Une contrainte du modèle a refusé la ligne : " + sample.text).equals(constraint.sentence(RejectRefusalClassesTest.FR)));
			}
		}
		// a constraint that has a name (a script rule of the transfer) says it, and is a quality rule
		final RejectReason named = RejectReason.constraint(RejectCategory.QUALITY_RULE, "Fasttrack.lib.phone.isValid on /root/Phone", "Phone number is not valid", "");
		RejectRefusalClassesTest.check("a named constraint: " + named.english(),
			"The constraint 'Fasttrack.lib.phone.isValid on /root/Phone' refused the row: Phone number is not valid".equals(named.english()) && RejectCategory.QUALITY_RULE.equals(named.category));
		RejectRefusalClassesTest.check("rows refused by the same constraint share a signature, whatever the message", named.signature().equals(
			RejectReason.constraint(RejectCategory.QUALITY_RULE, "Fasttrack.lib.phone.isValid on /root/Phone", "Other message", "").signature()));
		// a facet message wins over the user-message hint: the class is the facet's, not "a constraint"
		final RejectReason facet = RejectClassifier.refusal(constraintHeader + RejectRefusalClassesTest.sample("maxLength").subject + " : " + RejectRefusalClassesTest.sample("maxLength").text,
			RejectRefusalClassesTest.fields(), RejectRefusalClassesTest.row("name", RejectRefusalClassesTest.repeat('x', 25)));
		RejectRefusalClassesTest.check("a facet message is a facet even from a user-message error: " + facet.code, RejectReason.TOO_LONG.equals(facet.code) && "name".equals(facet.field()));
	}

	// -------------------------------------------------------------------------------------- store and group

	private static List<RejectReason> oneOfEach()
	{
		final List<RejectReason> all = new ArrayList<RejectReason>();
		final Map<String, Map<String, Object>> rows = new LinkedHashMap<String, Map<String, Object>>();
		rows.put("maxLength", RejectRefusalClassesTest.row("name", RejectRefusalClassesTest.repeat('x', 34)));
		rows.put("minLength", RejectRefusalClassesTest.row("zip", "12"));
		rows.put("exactLength", RejectRefusalClassesTest.row("country", "FRA"));
		rows.put("enumeration", RejectRefusalClassesTest.row("title", "V1"));
		rows.put("foreignKey", RejectRefusalClassesTest.row("family", "K1"));
		rows.put("mandatoryLabel", RejectRefusalClassesTest.row("name", "x"));
		rows.put("patternBic", RejectRefusalClassesTest.row("bic", "BAD"));
		rows.put("totalDigits", RejectRefusalClassesTest.row("amount", "1234567.5"));
		rows.put("fractionDigits", RejectRefusalClassesTest.row("rate", "0.12345"));
		rows.put("minInclusive", RejectRefusalClassesTest.row("quantity", "0"));
		rows.put("maxExclusive", RejectRefusalClassesTest.row("rate", "99.5"));
		rows.put("duplicateKeyLong", RejectRefusalClassesTest.row());
		for (final Map.Entry<String, Map<String, Object>> entry : rows.entrySet())
		{
			all.add(RejectClassifier.refusal(RejectRefusalClassesTest.sample(entry.getKey()).text, RejectRefusalClassesTest.fields(), entry.getValue()));
		}
		all.add(RejectReason.constraint(RejectCategory.EBX_REFUSAL, "", "IBAN is incorrect.", "raw"));
		all.add(RejectReason.trigger("Outbox", "Dataset not found", "raw"));
		return all;
	}

	private static void storedAndGrouped()
	{
		for (final RejectReason reason : RejectRefusalClassesTest.oneOfEach())
		{
			final String stored = RejectReasons.store(Arrays.asList(reason), 2000);
			RejectRefusalClassesTest.check(reason.code + ": the marker is stored: " + stored, stored.contains("[mdmf:rejects.reason." + reason.code + "|"));
			RejectRefusalClassesTest.check(reason.code + ": REST reads plain English", RejectReasons.english(stored).equals(reason.english()));
			final RejectReasons.Shown fr = RejectReasons.read(stored, reason.category, reason.detail, RejectRefusalClassesTest.FR).get(0);
			RejectRefusalClassesTest.check(reason.code + ": read back in French, same sentence and signature: " + fr.sentence,
				fr.sentence.equals(reason.sentence(RejectRefusalClassesTest.FR)) && fr.signature.equals(reason.signature()) && fr.category.equals(reason.category));
			RejectRefusalClassesTest.check(reason.code + ": a fix and the raw detail come with it", !fr.fix.isEmpty() && fr.raw.equals(RejectText.detail(reason.detail)));
		}
		// the value is not part of the grouping signature: two rows with different bad values are one kind of reason
		final RejectReason a = RejectClassifier.refusal(RejectRefusalClassesTest.sample("enumeration").text, RejectRefusalClassesTest.fields(), RejectRefusalClassesTest.row("title", "V1"));
		final RejectReason b = RejectClassifier.refusal(RejectRefusalClassesTest.sample("enumeration").text.replace("V1", "V2"), RejectRefusalClassesTest.fields(),
			RejectRefusalClassesTest.row("title", "V2"));
		RejectRefusalClassesTest.check("same field, same kind: " + a.signature(), a.signature().equals(b.signature()) && "ebx.enumeration|title|civilite".equals(a.signature()));
		RejectRefusalClassesTest.check("the group wording has no value: " + a.group(RejectRefusalClassesTest.EN), !a.group(RejectRefusalClassesTest.EN).contains("V1"));
		RejectRefusalClassesTest.check("another field is another group", !a.signature().equals(RejectReason.enumeration("country", "pays", "V1", "").signature()));
		// a long reason list keeps its markers whole
		final List<RejectReason> many = RejectRefusalClassesTest.oneOfEach();
		final String stored = RejectReasons.store(many, 600);
		RejectRefusalClassesTest.check("never cut inside a marker: " + stored.length(), stored.length() <= 600 && stored.endsWith("]"));
		// the category of each class feeds the cause sentence the same way as the others
		final RejectCauses causes = new RejectCauses();
		for (final RejectReason reason : RejectRefusalClassesTest.oneOfEach())
		{
			causes.add(reason.category, 1L);
		}
		RejectRefusalClassesTest.check("the sentence of the causes: " + causes.sentence(RejectRefusalClassesTest.EN),
			causes.sentence(RejectRefusalClassesTest.EN).startsWith("14 rows set aside (") && causes.sentence(RejectRefusalClassesTest.EN).contains("refused by EBX")
				&& !causes.sentence(RejectRefusalClassesTest.EN).contains("rule"));
	}

	private static void everyNewCodeIsWordedInBothLocales()
	{
		final List<RejectReason> all = new ArrayList<RejectReason>(RejectRefusalClassesTest.oneOfEach());
		all.add(RejectReason.duplicateKey("", "", ""));
		all.add(RejectReason.trigger("", "boom", ""));
		all.add(RejectReason.constraint(RejectCategory.QUALITY_RULE, "rule", "msg", ""));
		all.add(RejectReason.unknownMasterParent("customer", "customerRef", "Customer", "S|1"));
		all.add(RejectReason.tooLongRefused("f", "c", 12, 10, ""));
		all.add(RejectReason.mandatoryRefused("f", "c", ""));
		all.add(RejectReason.enumeration("", "", "", ""));
		all.add(RejectReason.foreignKey("f", "c", "v", "", ""));
		all.add(RejectReason.range("f", "c", "v", RejectReason.OP_GT, "5", ""));
		all.add(RejectReason.range("f", "c", "v", RejectReason.OP_LT, "5", ""));
		for (final RejectReason reason : all)
		{
			for (final Locale locale : new Locale[] { RejectRefusalClassesTest.EN, RejectRefusalClassesTest.FR })
			{
				final String sentence = reason.sentence(locale);
				RejectRefusalClassesTest.check(reason.code + " (" + locale + "): worded: " + sentence, !sentence.startsWith("rejects.") && !sentence.isEmpty());
				RejectRefusalClassesTest.check(reason.code + " (" + locale + "): no placeholder or doubled quote left: " + sentence, RejectRefusalClassesTest.clean(reason, sentence));
				final String group = reason.group(locale);
				RejectRefusalClassesTest.check(reason.code + " (" + locale + "): group wording: " + group, !group.startsWith("rejects.") && RejectRefusalClassesTest.clean(reason, group));
				final String fix = reason.fix(locale);
				RejectRefusalClassesTest.check(reason.code + " (" + locale + "): a fix without placeholders: " + fix, !fix.isEmpty() && RejectRefusalClassesTest.clean(reason, fix));
			}
		}
	}

	// ----------------------------------------------------------------------------------------- MASTER stage

	private static void masterStage()
	{
		// a master reason names the STAGING field the value is read from where staging names the source column
		final List<RejectClassifier.FieldInfo> master = Arrays.asList(new RejectClassifier.FieldInfo("lastName", RejectReason.stagingColumn("lastName"), "STRING"),
			new RejectClassifier.FieldInfo("company", RejectReason.stagingColumn("companyRef"), "STRING", null, false, "Company"),
			new RejectClassifier.FieldInfo("iban", RejectReason.stagingColumn("iban"), "STRING"));
		final RejectReason fk = RejectClassifier.refusal("EBX refused the save: " + RejectRefusalClassesTest.sample("foreignKey").text, master,
			RejectRefusalClassesTest.row("lastName", "Doe", "company", "K1", "iban", "FR76"));
		RejectRefusalClassesTest.check("master foreign key: " + fk.english(), RejectReason.EBX_FOREIGN_KEY.equals(fk.code) && "company".equals(fk.field())
			&& fk.english().equals("company (staging field companyRef): 'K1' matches no record of the table Company."));
		RejectRefusalClassesTest.check("in French: " + fk.sentence(RejectRefusalClassesTest.FR),
			fk.sentence(RejectRefusalClassesTest.FR).startsWith("company (champ du staging companyRef) : « K1 » ne correspond"));
		RejectRefusalClassesTest.check("its fix names the staging field: " + fk.fix(RejectRefusalClassesTest.EN), fk.fix(RejectRefusalClassesTest.EN).contains("the staging field companyRef"));
		RejectRefusalClassesTest.check("the signature keeps the staging column apart: " + fk.signature(), "ebx.foreignKey|company|staging:companyRef".equals(fk.signature()));

		// the parent that has not reached master, and the parent this load rejected
		final RejectReason unresolved = RejectReason.unknownMasterParent("company", "companyRef", "Company", "SAP|C1");
		RejectRefusalClassesTest.check("not reached master: " + unresolved.english(), RejectCategory.DATA_CHECK.equals(unresolved.category)
			&& unresolved.english().equals("company (staging field companyRef): refers to a Company (SAP|C1) that has not reached master."));
		RejectRefusalClassesTest.check("in French: " + unresolved.sentence(RejectRefusalClassesTest.FR), unresolved.sentence(RejectRefusalClassesTest.FR)
			.equals("company (champ du staging companyRef) : référence un Company (SAP|C1) qui n’est pas encore arrivé dans le master."));
		final RejectedParents parents = new RejectedParents();
		RejectRefusalClassesTest.check("nothing rejected yet: not reached master", RejectReason.UNKNOWN_MASTER_PARENT.equals(parents.forChild("company", "companyRef", "Company", "SAP|C1").code));
		final RejectReason rejectedParent = RejectClassifier.refusal(RejectRefusalClassesTest.sample("patternBic").text, master, RejectRefusalClassesTest.row("iban", "BAD"));
		parents.record("Company", "SAP|C1", rejectedParent);
		final RejectReason child = parents.forChild("company", "companyRef", "Company", "SAP|C1");
		RejectRefusalClassesTest.check("a parent this load rejected: its reason is passed on", RejectReason.PARENT.equals(child.code) && RejectCategory.PARENT_REJECTED.equals(child.category));
		RejectRefusalClassesTest.check("the child says why: " + child.sentence(RejectRefusalClassesTest.FR), child.sentence(RejectRefusalClassesTest.FR)
			.startsWith("Ligne parente Company rejetée : iban (champ du staging iban) : « BAD » ne respecte pas le motif"));
		RejectRefusalClassesTest.check("and passes the parent's fix on", child.fix(RejectRefusalClassesTest.EN).contains("pattern"));
		RejectRefusalClassesTest.check("the grouping signature nests the parent's: " + child.signature(), "parent|ebx.pattern|iban|staging:iban".equals(child.signature()));
		// a grandchild keeps the ROOT cause (no "parent rejected: parent rejected")
		parents.record("Contract", "SAP|K1", child);
		final RejectReason grandchild = parents.forChild("contract", "contractRef", "Contract", "SAP|K1");
		RejectRefusalClassesTest.check("the grandchild has the root cause: " + grandchild.signature(), child.signature().equals(grandchild.signature()) && child.english().equals(grandchild.english()));
		RejectRefusalClassesTest.check("another parent is not affected", RejectReason.UNKNOWN_MASTER_PARENT.equals(parents.forChild("company", "companyRef", "Company", "SAP|C2").code));
		// bounded
		final RejectedParents bounded = new RejectedParents();
		for (int i = 0; i < RejectedParents.MAX + 10; i++)
		{
			bounded.record("E", "k" + i, rejectedParent);
		}
		RejectRefusalClassesTest.check("bounded: " + bounded.size(), bounded.size() == RejectedParents.MAX && bounded.rejected("E", "k" + (RejectedParents.MAX + 5)) == null);

		// the master stage's script constraint: a quality rule named by the rule, the same sentence as an EBX blocking constraint
		final RejectReason rule = RejectReason.constraint(RejectCategory.QUALITY_RULE, "Fasttrack.lib.iban.isValid on /root/Account", "IBAN is incorrect.", "");
		RejectRefusalClassesTest.check("a script rule at master: " + rule.english(), rule.english().equals("The constraint 'Fasttrack.lib.iban.isValid on /root/Account' refused the row: IBAN is incorrect.")
			&& RejectCategory.QUALITY_RULE.equals(rule.category));
		final RejectCauses masterCauses = new RejectCauses();
		masterCauses.add(rule.category, 2L).add(fk.category, 3L).add(child.category, 4L).add(unresolved.category, 1L);
		RejectRefusalClassesTest.check("the banner words master rejects like staging's: " + masterCauses.sentence(RejectRefusalClassesTest.FR),
			masterCauses.sentence(RejectRefusalClassesTest.FR).startsWith("10 lignes mises de côté (") && masterCauses.sentence(RejectRefusalClassesTest.FR).contains("règle de qualité : 2")
				&& masterCauses.sentence(RejectRefusalClassesTest.FR).contains("refus d’EBX") && masterCauses.sentence(RejectRefusalClassesTest.FR).contains("parente a été rejetée : 4"));
	}

	// --------------------------------------------------------------------------------------------- bundles

	private static void bundlesKeepToOneBlockAtTheEnd() throws IOException
	{
		final String sourceDir = System.getProperty("mdmf.sourceDir");
		final Path resources = sourceDir == null ? Paths.get("src/main/resources/com/on/ebx/factory/ebx/ui")
			: Paths.get(sourceDir).resolve("../resources/com/on/ebx/factory/ebx/ui").normalize();
		for (final String language : new String[] { "en", "fr" })
		{
			final List<String> lines = Files.readAllLines(resources.resolve("Messages_" + language + ".properties"), StandardCharsets.US_ASCII);
			int header = -1;
			for (int i = 0; i < lines.size(); i++)
			{
				if (lines.get(i).equals("# ebx refusal classes (2026-10-17)"))
				{
					header = i;
				}
			}
			RejectRefusalClassesTest.check(language + ": the block \"ebx refusal classes (2026-10-17)\" exists", header >= 0);
			final List<String> block = lines.subList(header + 1, lines.size());
			int keys = 0;
			for (final String line : block)
			{
				if (line.startsWith("#"))
				{
					break; // integration 2026-10-17: another lot's block may follow this one
				}
				if (!line.trim().isEmpty())
				{
					RejectRefusalClassesTest.check(language + ": only rejects.* keys in the block: " + line, line.startsWith("rejects."));
					keys++;
				}
			}
			RejectRefusalClassesTest.check(language + ": the block is complete: " + keys, keys == 48);
		}
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
