package com.on.ebx.factory.wizard;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import com.on.ebx.factory.landing.KeyCertainty;
import com.on.ebx.factory.landing.KeyPrimeRunner;
import com.on.ebx.factory.landing.KeyStudyReport;
import com.on.ebx.factory.landing.KeyTexts;
import com.on.ebx.factory.landing.SourceKeyRule;

/** JUnit-free: run the main, a failure throws. Key rationale i18n (2026-10-17): codes, the English-template parser, English and French wording, fallbacks. */
public final class KeyRationaleTextTest
{
	private static final Locale EN = Locale.ENGLISH;
	private static final Locale FR = Locale.FRENCH;

	public static void main(final String[] pArgs)
	{
		// the English of every code is the SAME in the module bundle (the English viewer reads the bundle)
		for (final String key : KeyTexts.ENGLISH.keySet())
		{
			final Object[] args = new Object[] { "AAA", "BBB", "CCC" };
			final String bundle = WizardMessages.get(EN, key, args);
			final String builtIn = KeyTexts.ENGLISH_WORDS.get(key, args);
			// the stored English keeps the historic words "deduced scan"; the screen's English avoids that internal word (WizardPageVocabularyTest)
			check("bundle English == built-in English for " + key, builtIn.equals(bundle) || key.endsWith("deduced") && bundle.contains("found from the data") || key.endsWith("origin.DEDUCED") && bundle.contains("found from the data"));
			check("French exists for " + key, !key.equals(WizardMessages.get(FR, key, args)) && !WizardMessages.get(FR, key, args).equals(bundle) || key.endsWith("sum.with"));
		}

		// each SourceKeyRule template, column names with spaces, dots, accents: code -> English -> parse gives the same code and column
		final String[] names = { "customerId", "customer id", "a.b.id", "Numéro client", "order_number", "x (y); z" };
		for (final String name : names)
		{
			final List<SourceKeyRule.Candidate> generic = Arrays.asList(new SourceKeyRule.Candidate(name.equals("customerId") ? name : "plain", false, BigDecimal.ZERO, BigDecimal.ONE));
			check("none: " + name, KeyTexts.parse(KeyTexts.none().english).key.equals(KeyTexts.NONE));
			for (final KeyTexts.Text text : new KeyTexts.Text[] { KeyTexts.named(name, false), KeyTexts.named(name, true), KeyTexts.noProfile(name), KeyTexts.genericId(name, false),
				KeyTexts.genericId(name, true) })
			{
				final KeyTexts.Parsed parsed = KeyTexts.parse(text.english);
				check("parse " + text.key + " / " + name, parsed != null && parsed.key.equals(text.key) && parsed.args.length == 1 && parsed.args[0].equals(name));
				final KeyTexts.Parsed viaMarker = KeyTexts.parse(text.stored());
				check("marker + English parse " + text.key, viaMarker != null && viaMarker.args[0].equals(name));
			}
			check("generic list used", generic.size() == 1);
		}
		// the texts the rule really produces
		SourceKeyRule.Proposal p = SourceKeyRule.propose("ORDERS", Arrays.asList(new SourceKeyRule.Candidate("ORDER_ID", true, null, null)));
		check("declared parse", KeyTexts.parse(p.rationale).key.equals(KeyTexts.DECLARED));
		p = SourceKeyRule.propose("ORDERS", Arrays.asList(new SourceKeyRule.Candidate("foo", false, null, null)));
		check("none parse", p.columns.isEmpty() && KeyTexts.parse(p.rationale).key.equals(KeyTexts.NONE));
		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(new SourceKeyRule.Candidate("SUPPLIER_ID", false, null, null)));
		check("noProfile parse", KeyTexts.parse(p.rationale).key.equals(KeyTexts.NO_PROFILE) && KeyTexts.parse(p.rationale).args[0].equals("SUPPLIER_ID"));
		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(new SourceKeyRule.Candidate("SUPPLIER_ID", false, BigDecimal.ZERO, BigDecimal.ONE)));
		check("named parse (profile)", KeyTexts.parse(p.rationale).key.equals(KeyTexts.NAMED) && p.rationale.contains("profile"));
		p = SourceKeyRule.propose("SUPPLIER", Arrays.asList(new SourceKeyRule.Candidate("SUPPLIER_ID", false, BigDecimal.ZERO, BigDecimal.ONE)), true);
		check("named parse (sample)", KeyTexts.parse(p.rationale).key.equals(KeyTexts.NAMED_SAMPLE) && p.rationale.contains("sample") && !p.rationale.contains("profile"));
		p = SourceKeyRule.propose("customers", Arrays.asList(new SourceKeyRule.Candidate("customerId", false, BigDecimal.ZERO, BigDecimal.ONE), new SourceKeyRule.Candidate("email", false, BigDecimal.ZERO, BigDecimal.ONE)), true);
		final String example = KeyRationaleText.display(p.stored(), FR);
		check("the sample case in French", p.confidence > 0 && example.contains("customerId") && example.contains("échantillon"));
		p = SourceKeyRule.propose("orders", Arrays.asList(new SourceKeyRule.Candidate("customerId", false, BigDecimal.ZERO, BigDecimal.ONE)), false);
		final String generic = p.rationale;
		check("generic id, profile", KeyTexts.parse(generic).key.equals(KeyTexts.GENERIC_ID));
		check("generic id French (profile)", KeyRationaleText.display(generic, FR).equals(
			"Aucune clé déclarée par la source ; customerId est la seule colonne nommée comme un identifiant (Id, _id, Number), sans valeur vide et sans doublon dans le profil. À confirmer."));
		check("generic id French (sample)", KeyRationaleText.display(SourceKeyRule.propose("orders", Arrays.asList(new SourceKeyRule.Candidate("customerId", false, BigDecimal.ZERO, BigDecimal.ONE)), true).stored(), FR)
			.endsWith("sans doublon dans l'échantillon. À confirmer."));

		// REST / MCP / logs: the English field is plain, the stored form is English + marker, a reader that ignores the marker sees English
		check("rationale is plain English", !p.rationale.contains("[mdmf:") && p.stored().startsWith(p.rationale) && p.stored().contains("[mdmf:wizard.keytext.genericId|s:customerId]"));
		check("decode raw gives the English", EngineMessageCodec.decode(p.stored()).raw.equals(p.rationale));

		// display: English, French, marker, bare English, fallbacks
		check("English display of a coded text", KeyRationaleText.display(p.stored(), EN).equals(p.rationale));
		check("English display of a bare old text", KeyRationaleText.display(p.rationale, EN).equals(p.rationale));
		check("French display of a bare old text equals the coded one", KeyRationaleText.display(p.rationale, FR).equals(KeyRationaleText.display(p.stored(), FR)));
		check("null locale reads English", KeyRationaleText.display(p.stored(), null).equals(p.rationale));
		final String llm = "The id column is unique in the sample and named like the identifier; nothing refutes it.";
		check("free text (LLM) untouched", KeyRationaleText.display(llm, FR).equals(llm) && KeyRationaleText.display(llm, EN).equals(llm));
		check("empty and null", KeyRationaleText.display("", FR).isEmpty() && KeyRationaleText.display(null, FR).isEmpty());
		check("a marker of another family is removed, text kept", KeyRationaleText.display("Something [mdmf:wizard.other|s:x]", FR).equals("Something"));
		check("a code the bundle lacks falls back to the English", KeyRationaleText.display("Some text [mdmf:wizard.keytext.nope|s:x]", FR).equals("Some text"));
		check("declared French", KeyRationaleText.display(KeyTexts.declared().stored(), FR).equals("Clé primaire déclarée par la source."));
		check("none French keeps its apostrophe", KeyRationaleText.display(KeyTexts.none().english, FR).contains("comme l'identifiant de la table"));
		check("named French doubles-apostrophe resolved", KeyRationaleText.display(KeyTexts.named("a'b", false).stored(), FR).contains("a'b est nommée comme l'identifiant"));

		// KeyCertainty
		final KeyCertainty.Settled settled = KeyCertainty.settledConfidence("USER");
		check("settled stored", KeyRationaleText.display(settled.stored(), FR).equals("Clé choisie par une personne dans l'assistant."));
		check("settled rationale is plain", settled.rationale.equals("Key chosen by a person in the wizard."));
		check("stale: stored canonical with marker is up to date", KeyCertainty.staleSettledCorrection("USER", 100, settled.stored()) == null);
		check("stale: bare canonical is up to date", KeyCertainty.staleSettledCorrection("USER", 100, settled.rationale) == null);
		check("stale: other text is corrected", KeyCertainty.staleSettledCorrection("USER", 100, "x") != null);
		check("deduced and source parse", KeyTexts.parse(KeyCertainty.settledConfidence("DEDUCED").rationale).key.equals(KeyTexts.DEDUCED)
			&& KeyTexts.parse(KeyCertainty.settledConfidence("SOURCE").rationale).key.equals(KeyTexts.DECLARED));
		check("changed after load", KeyRationaleText.display(KeyTexts.changedAfterLoad().stored(), FR).startsWith("Clé modifiée après le chargement"));

		// KeyPrimeRunner notes
		final java.util.List<String> notes = new java.util.ArrayList<String>();
		final KeyStudyReport.Proposal none = null;
		KeyPrimeRunner.run(Arrays.asList(new KeyPrimeRunner.Candidate("1", "T1", true, true, false, false)), new KeyPrimeRunner.Study()
		{
			@Override
			public KeyStudyReport.Proposal study(final String pId)
			{
				throw new IllegalStateException("connection refused: host {x}");
			}
		}, new KeyPrimeRunner.Apply()
		{
			@Override
			public void apply(final String pId, final KeyStudyReport.Proposal pProposal)
			{
			}
		}, new KeyPrimeRunner.Note()
		{
			@Override
			public void note(final String pTableId, final String pText)
			{
				notes.add(pText);
			}
		}, 1000L, null);
		check("a failed read is noted", notes.size() == 1 && notes.get(0).startsWith("Key not measured: connection refused: host {x}") && none == null);
		check("note English", KeyRationaleText.display(notes.get(0), EN).equals("Key not measured: connection refused: host {x}"));
		check("note French", KeyRationaleText.display(notes.get(0), FR).equals("Clé non mesurée : connection refused: host {x}"));
		check("timeout parse", KeyTexts.parse("Key not measured: the sample was not read within 20 s").key.equals(KeyTexts.NOT_MEASURED_TIMEOUT));
		check("timeout French", KeyRationaleText.display(KeyTexts.notMeasuredTimeout(20).stored(), FR).equals("Clé non mesurée : l'échantillon n'a pas été lu en 20 s"));

		// flattened
		check("flattened parse", KeyTexts.parse(com.on.ebx.factory.landing.FlattenedDocument.MESSAGE).key.equals(KeyTexts.FLATTENED));
		check("flattened + load parse", KeyTexts.parse(com.on.ebx.factory.landing.FlattenedDocument.MESSAGE + " (1 row was loaded.)").key.equals(KeyTexts.FLATTENED_LOAD));
		check("flattened French", KeyRationaleText.display(com.on.ebx.factory.landing.FlattenedDocument.MESSAGE, FR).startsWith("Ce fichier ressemble"));

		// the waiting note, built as it was before this lot (old data) and as it is now
		final String oldLlm = "Not applied, waiting for a person - Suggested by the language model (62%, low confidence, unique across the whole source, waiting for a person to confirm it."
			+ " Proposal: email+name (proposed by the language model). Two columns that together identify the row.";
		final KeyTexts.Parsed parsed = KeyTexts.parse(oldLlm);
		check("old waiting note parsed", parsed != null && KeyTexts.PENDING.equals(parsed.key) && "llm".equals(parsed.args[0]) && ((Number) parsed.args[1]).intValue() == 62 && "T".equals(parsed.args[2])
			&& "email+name".equals(parsed.args[3]) && "LLM".equals(parsed.args[4]) && parsed.tail.equals("Two columns that together identify the row."));
		final KeyTexts.Text now = KeyTexts.pending("llm", 62, Boolean.TRUE, "email+name", "LLM", "Two columns that together identify the row.");
		check("new waiting note English == the old one", now.english.equals(oldLlm));
		final String frPending = KeyRationaleText.display(now.stored(), FR);
		check("waiting note French", frPending.equals("Non appliquée, en attente d'une personne - Suggérée par le modèle de langage (62 %, confiance faible, unique sur toute la source, en attente de confirmation par une personne)."
			+ " Proposition : email+name (proposée par le modèle de langage). Two columns that together identify the row."));
		check("old waiting note displays like the new", KeyRationaleText.display(oldLlm, FR).equals(frPending));
		check("waiting note English display is the English", KeyRationaleText.display(now.stored(), EN).equals(oldLlm));
		final String ruleRationale = KeyTexts.named("id", true).stored();
		final KeyTexts.Text withRule = KeyTexts.pending("noEvidence", 0, null, "", "RULE", ruleRationale);
		check("waiting note with a rule rationale: tail is translated too", KeyRationaleText.display(withRule.stored(), FR).endsWith("la colonne id est nommée comme l'identifiant et l'échantillon ne montre aucune valeur vide ni doublon.")
			&& KeyRationaleText.display(withRule.stored(), FR).contains("Aucune clé."));
		for (final String reason : new String[] { "noEvidence", "notUnique", "sampleOnly", "noneCertain" })
		{
			final KeyTexts.Text t = KeyTexts.pending(reason, 0, Boolean.FALSE, "a", "RULE", "R");
			final KeyTexts.Parsed back = KeyTexts.parse(t.english);
			check("waiting reason round trip " + reason, back != null && reason.equals(back.args[0]) && back.tail.equals("R"));
			check("waiting reason has French " + reason, !KeyRationaleText.display(t.stored(), FR).equals(t.english));
		}

		// marker never cut
		final String longText = "x" + new String(new char[3000]).replace('\0', 'y');
		final String fitted = KeyTexts.fit(longText + " [mdmf:wizard.keytext.chosen]", 2000);
		check("fit keeps the marker", fitted.length() == 2000 && fitted.endsWith("[mdmf:wizard.keytext.chosen]"));
		check("fit cuts plain text", KeyTexts.fit(longText, 2000).length() == 2000 && KeyTexts.fit("short", 2000).equals("short"));
		System.out.println("KeyRationaleTextTest OK");
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
