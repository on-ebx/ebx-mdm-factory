package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.ResourceBundle;
import java.util.Set;

/**
 * JUnit-free: run the main, a failure throws. UX lot U5 (2026-10-13, RAPPORT 2.6, 2.12, 3.5): the vocabulary of the wizard (no internal word in a message), the
 * glossary of every step, the Distribution list in sections, the landing's "confirmed" key sentence.
 */
public final class WizardPageVocabularyTest
{
	private static final String[] BANNED = { "deduced scan", "official scan", "landing plan", "agent run", "mdmfactorypipeline", "xref", "goldenkey", "capabilities/",
		"mockup", "validated detail", "exécution de l'agent", "exécution de l’agent", "(module ebx-" };

	public static void main(final String[] pArgs)
	{
		WizardPageVocabularyTest.noInternalWordInTheUserMessages();
		WizardPageVocabularyTest.noRepositoryPathInTheJavaLiterals();
		WizardPageVocabularyTest.theNewKeysExistInBothLanguagesWithTheSamePlaceholders();
		WizardPageVocabularyTest.groupsPutLikelyTargetsFirstAndFoldTheTests();
		WizardPageVocabularyTest.distributionPageShowsTheSectionsAndTheOptionalNoticeFirst();
		WizardPageVocabularyTest.theLandingDoneSentenceIsTheConfirmedOne();
		WizardPageVocabularyTest.everyStepGlossaryCoversItsTerms();
		System.out.println("WizardPageVocabularyTest OK");
	}

	// ------------------------------------------------------------------------------------------------------------- bundles

	private static void noInternalWordInTheUserMessages()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			final ResourceBundle bundle = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", locale);
			for (final String key : Collections.list(bundle.getKeys()))
			{
				if (!key.startsWith("wizard.") && !key.startsWith("mappingMatrix."))
				{
					continue;
				}
				final String text = bundle.getString(key).toLowerCase(Locale.ENGLISH);
				for (final String word : WizardPageVocabularyTest.BANNED)
				{
					WizardPageVocabularyTest.check(key + " (" + locale + ") carries the internal word [" + word + "]", !text.contains(word));
				}
			}
		}
	}

	/**
	 * Audit F2-108: the Java of the screens is read too, not only the bundles. No string literal of the wizard package or of the screens (ebx/ui) may carry a path of the
	 * repository (tools/..., docs/...) or the name of the pipeline workflow model: the person has no repository, and the sentence belongs in a bundle, in both languages.
	 */
	private static void noRepositoryPathInTheJavaLiterals()
	{
		final java.nio.file.Path srcRoot = java.nio.file.Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")).resolve("com/on/ebx/factory");
		final List<String> offences = new ArrayList<String>();
		for (final String dir : new String[] { "wizard", "ebx/ui" })
		{
			try (java.util.stream.Stream<java.nio.file.Path> walk = java.nio.file.Files.walk(srcRoot.resolve(dir)))
			{
				for (final java.nio.file.Path file : walk.filter(p -> p.toString().endsWith(".java")).collect(java.util.stream.Collectors.toList()))
				{
					for (final String line : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8))
					{
						final String code = line.trim();
						if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*"))
						{
							continue;
						}
						for (final String banned : new String[] { "tools/", "MdmFactoryPipeline", "docs/" })
						{
							if (WizardPageVocabularyTest.literalContains(code, banned))
							{
								offences.add(srcRoot.relativize(file) + " [" + banned + "]: " + code);
							}
						}
					}
				}
			}
			catch (final java.io.IOException ex)
			{
				throw new IllegalStateException(ex);
			}
		}
		// F2-108 (follow-up): the three intake files that stored English text for the screens carry no path either, and their codes are worded in both languages
		for (final String file : new String[] { "ebx/IntakeEngine.java", "intake/IntakeReadiness.java", "intake/IntakeChainOutcome.java" })
		{
			try
			{
				for (final String line : java.nio.file.Files.readAllLines(srcRoot.resolve(file), java.nio.charset.StandardCharsets.UTF_8))
				{
					final String code = line.trim();
					if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*"))
					{
						continue;
					}
					for (final String banned : new String[] { "tools/", "MdmFactoryPipeline", "docs/", "ebxLog/" })
					{
						if (WizardPageVocabularyTest.literalContains(code, banned))
						{
							offences.add(file + " [" + banned + "]: " + code);
						}
					}
				}
			}
			catch (final java.io.IOException ex)
			{
				throw new IllegalStateException(ex);
			}
		}
		for (final java.util.Locale locale : new java.util.Locale[] { java.util.Locale.ENGLISH, java.util.Locale.FRENCH })
		{
			for (final String stored : new String[] { "STOPPED:6", "PAUSED_BY:ana", "PAUSED_BY:", "FAILED:boom", "FAILED:", "LAUNCH_FAILED:x", "LAUNCH_FAILED:" })
			{
				final String text = IntakeScreens.pauseReasonText(locale, stored);
				WizardPageVocabularyTest.check(stored + "/" + locale + ": worded, not the raw code (" + text + ")", !text.equals(stored) && !text.startsWith("!") && !text.contains("{0}"));
			}
			WizardPageVocabularyTest.check("an old English reason is shown as stored", "stopped: 6 % of the rows held back".equals(IntakeScreens.pauseReasonText(locale, "stopped: 6 % of the rows held back")));
		}
		WizardPageVocabularyTest.check("French reason is French", IntakeScreens.pauseReasonText(java.util.Locale.FRENCH, "STOPPED:6").startsWith("arr"));
		WizardPageVocabularyTest.check("a screen literal names a repository path or the pipeline model:\n" + String.join("\n", offences), offences.isEmpty());
		WizardPageVocabularyTest.check("the scan sees a banned literal", WizardPageVocabularyTest.literalContains("x(\"see tools/x.sh\");", "tools/"));
		WizardPageVocabularyTest.check("the scan ignores code outside quotes", !WizardPageVocabularyTest.literalContains("tools/x(\"ok\");", "tools/"));
	}

	/** Whether a string literal of {@code pCode} contains {@code pWhat}. */
	private static boolean literalContains(final String pCode, final String pWhat)
	{
		boolean in = false;
		final StringBuilder literal = new StringBuilder();
		for (int i = 0; i < pCode.length(); i++)
		{
			final char c = pCode.charAt(i);
			if (c == '\\' && in)
			{
				i++;
				continue;
			}
			if (c == '"')
			{
				if (in && literal.indexOf(pWhat) >= 0)
				{
					return true;
				}
				in = !in;
				literal.setLength(0);
			}
			else if (in)
			{
				literal.append(c);
			}
		}
		return false;
	}

	/** Keys added or reworded by the lot: both languages, same {n}, not blank. */
	private static final String[] LOT_KEYS = { "wizard.step3.keyOrigin.DEDUCED", "wizard.jobs.kind.LANDING_PLAN_RUN", "wizard.progress.plan", "wizard.step3.notice.planStarted",
		"wizard.step3.notice.planQueued", "wizard.jobs.kind.DEDUCED_SCAN", "wizard.step7.gate.notPublished", "wizard.intake.ready.PIPELINE_MODEL.ok",
		"wizard.intake.ready.PIPELINE_MODEL.ko", "wizard.intake.one.text", "wizard.step7dist.targets.title.one", "wizard.step7dist.targets.title.other",
		"wizard.step7dist.connections.empty", "wizard.step7dist.group.likely", "wizard.step7dist.group.sources", "wizard.step7dist.group.sources.hint",
		"wizard.step7dist.connections.sourceChip", "wizard.step7dist.group.other.one", "wizard.step7dist.group.other.other", "wizard.step7dist.skip.hint" };

	private static void theNewKeysExistInBothLanguagesWithTheSamePlaceholders()
	{
		final ResourceBundle en = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.ENGLISH);
		final ResourceBundle fr = ResourceBundle.getBundle("com.on.ebx.factory.ebx.ui.Messages", Locale.FRENCH);
		final List<String> keys = new ArrayList<String>(java.util.Arrays.asList(WizardPageVocabularyTest.LOT_KEYS));
		for (final String prefix : new String[] { "wizard.step2.glossary.connection", "wizard.step2.glossary.catalog", "wizard.step4.glossary.businessObject",
			"wizard.step4.glossary.field", "wizard.step4.glossary.sourceLink", "wizard.step4.glossary.rule", "wizard.step4.glossary.check", "wizard.step4.glossary.setAside",
			"wizard.step6.glossary.candidateSearch", "wizard.step7dist.glossary.retire", "wizard.step8.glossary.openPoint", "wizard.step8.glossary.intake",
			"wizard.step8.glossary.schedule", "wizard.step2.glossary.trustRank" })
		{
			keys.add(prefix + ".term");
			keys.add(prefix + ".def");
		}
		final java.util.regex.Pattern placeholder = java.util.regex.Pattern.compile("\\{(\\d+)\\}");
		for (final String key : keys)
		{
			WizardPageVocabularyTest.check(key + ": in English", en.containsKey(key) && !en.getString(key).trim().isEmpty());
			WizardPageVocabularyTest.check(key + ": in French", fr.containsKey(key) && !fr.getString(key).trim().isEmpty());
			WizardPageVocabularyTest.check(key + ": same placeholders in both languages", WizardPageVocabularyTest.placeholders(placeholder, en.getString(key))
				.equals(WizardPageVocabularyTest.placeholders(placeholder, fr.getString(key))));
			WizardPageVocabularyTest.check(key + ": a French apostrophe never sits next to a placeholder", !fr.getString(key).matches(".*'\\{\\d.*|.*\\}'.*"));
		}
	}

	private static Set<String> placeholders(final java.util.regex.Pattern pPattern, final String pText)
	{
		final Set<String> out = new HashSet<String>();
		final java.util.regex.Matcher matcher = pPattern.matcher(pText);
		while (matcher.find())
		{
			out.add(matcher.group(1));
		}
		return out;
	}

	// ------------------------------------------------------------------------------------------------------------ distribution

	private static WizardPage.DistributionConnectionRow connection(final String pId, final String pLabel, final String pType, final boolean pWrite)
	{
		final WizardPage.DistributionConnectionRow row = new WizardPage.DistributionConnectionRow();
		row.connector = pId;
		row.label = pLabel;
		row.type = pType;
		row.hasWriteOperation = pWrite;
		return row;
	}

	/** The connections of the measured list (RAPPORT 2.12, img 51b), shortened. */
	private static List<WizardPage.DistributionConnectionRow> measuredConnections()
	{
		final List<WizardPage.DistributionConnectionRow> rows = new ArrayList<WizardPage.DistributionConnectionRow>();
		rows.add(WizardPageVocabularyTest.connection("activemq", "Apache ActiveMQ", "activemq", true));
		rows.add(WizardPageVocabularyTest.connection("demoWh", "Data catalog demo warehouse (H2)", "jdbc", true));
		rows.add(WizardPageVocabularyTest.connection("kafkaTest", "Kafka · MDM distribution test", "kafka", true));
		rows.add(WizardPageVocabularyTest.connection("pvCrm", "PersVol CRM (H2 test source)", "jdbc", true));
		rows.add(WizardPageVocabularyTest.connection("h2plain", "Reference store", "h2", true));
		rows.add(WizardPageVocabularyTest.connection("sf", "Salesforce CRM", "salesforce", true));
		rows.add(WizardPageVocabularyTest.connection("democrm", "Demo CRM", "sample-file", true));
		rows.add(WizardPageVocabularyTest.connection("snow", "Snowflake", "snowflake", true));
		rows.add(WizardPageVocabularyTest.connection("kafka", "Apache Kafka", "kafka", true));
		rows.add(WizardPageVocabularyTest.connection("rdonly", "A Read-Only System", "rest", false));
		rows.add(WizardPageVocabularyTest.connection("rdonly2", "Another read-only", "rest", false));
		return rows;
	}

	private static Set<String> set(final String... pValues)
	{
		return new HashSet<String>(java.util.Arrays.asList(pValues));
	}

	private static List<String> ids(final List<WizardPage.DistributionConnectionRow> pRows)
	{
		final List<String> out = new ArrayList<String>();
		for (final WizardPage.DistributionConnectionRow row : pRows)
		{
			out.add(row.connector);
		}
		return out;
	}

	private static void groupsPutLikelyTargetsFirstAndFoldTheTests()
	{
		final DistributionConnectionGroups groups = DistributionConnectionGroups.of(WizardPageVocabularyTest.measuredConnections(), WizardPageVocabularyTest.set("snow"),
			WizardPageVocabularyTest.set("sf"));
		WizardPageVocabularyTest.check("likely: the one with a target first, then alphabetical: " + WizardPageVocabularyTest.ids(groups.likely),
			WizardPageVocabularyTest.ids(groups.likely).equals(java.util.Arrays.asList("snow", "activemq", "kafka")));
		WizardPageVocabularyTest.check("sources: the project's own connection, flagged apart", WizardPageVocabularyTest.ids(groups.sources).equals(java.util.Arrays.asList("sf")));
		WizardPageVocabularyTest.check("others: H2, demo, test and sample connections: " + WizardPageVocabularyTest.ids(groups.others),
			WizardPageVocabularyTest.ids(groups.others).equals(java.util.Arrays.asList("demoWh", "democrm", "kafkaTest", "pvCrm", "h2plain")));
		WizardPageVocabularyTest.check("no write operation: a folded count", groups.noWrite.size() == 2);
		WizardPageVocabularyTest.check("six connections can receive records, the three sections together", groups.writeCapableCount() == 9);
		// a target already set up on a test-looking connection stays in the likely section: somebody did that on purpose
		final DistributionConnectionGroups kept = DistributionConnectionGroups.of(WizardPageVocabularyTest.measuredConnections(), WizardPageVocabularyTest.set("kafkaTest"),
			WizardPageVocabularyTest.set());
		WizardPageVocabularyTest.check("a connection that carries a target is never folded", WizardPageVocabularyTest.ids(kept.likely).contains("kafkaTest")
			&& !WizardPageVocabularyTest.ids(kept.others).contains("kafkaTest"));
		// "whole words only": Attestation or Latest are not tests, "H2" in a longer word is not H2
		WizardPageVocabularyTest.check("a label with 'test' only inside a word is not a test",
			!DistributionConnectionGroups.looksLikeTest(WizardPageVocabularyTest.connection("a", "Attestation hub", "rest", true))
				&& !DistributionConnectionGroups.looksLikeTest(WizardPageVocabularyTest.connection("b", "Latest orders", "rest", true)));
	}

	private static WizardPage.Model model()
	{
		final WizardPage.Model model = new WizardPage.Model();
		model.administrator = true;
		model.designer = true;
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.ProjectInfo project = new WizardSnapshot.ProjectInfo();
		project.id = "1";
		project.code = "UxCrmSf";
		project.label = "UxCrmSf";
		snapshot.project = project;
		model.snapshot = snapshot;
		model.stepStates = WizardRules.stepStates(snapshot);
		return model;
	}

	private static void distributionPageShowsTheSectionsAndTheOptionalNoticeFirst()
	{
		final WizardPage.Model model = WizardPageVocabularyTest.model();
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.id = "1";
		source.sourceSystem = "SFDC";
		source.connector = "sf";
		model.snapshot.sources.add(source);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_DISTRIBUTION;
		final WizardPage.DistributionModel d = new WizardPage.DistributionModel();
		d.connections.addAll(WizardPageVocabularyTest.measuredConnections());
		model.distribution = d;
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			model.locale = locale;
			final String html = WizardPage.render(model);
			final String step = WizardPageVocabularyTest.panelOf(html, "7");
			final int optional = step.indexOf(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.skip.hint")));
			final int likely = step.indexOf(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.group.likely")));
			final int sources = step.indexOf(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.group.sources")));
			final int others = step.indexOf(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.group.other.other", "5")));
			final int noWrite = step.indexOf(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.connections.noWriteGroup.other", "2")));
			WizardPageVocabularyTest.check(locale + ": 'Distribution is optional' is the first thing, before the list", optional >= 0 && optional < likely);
			WizardPageVocabularyTest.check(locale + ": it is said once", step.indexOf(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.skip.hint")), optional + 1) < 0);
			WizardPageVocabularyTest.check(locale + ": sections in order likely < sources < others < no write", likely > 0 && likely < sources && sources < others && others < noWrite);
			WizardPageVocabularyTest.check(locale + ": Salesforce sits in the sources section, flagged",
				step.indexOf("Salesforce CRM") > sources && step.indexOf("Salesforce CRM") < others
					&& step.contains(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.connections.sourceChip"))));
			WizardPageVocabularyTest.check(locale + ": Snowflake and ActiveMQ are listed under the likely targets", step.indexOf("Snowflake") > likely && step.indexOf("Snowflake") < sources
				&& step.indexOf("Apache ActiveMQ") > likely && step.indexOf("Apache ActiveMQ") < sources);
			WizardPageVocabularyTest.check(locale + ": the H2 / demo / test connections sit after the 'other connections' summary",
				step.indexOf("Data catalog demo warehouse (H2)") > others && step.indexOf("PersVol CRM (H2 test source)") > others);
			WizardPageVocabularyTest.check(locale + ": the folded sections carry no 'open' attribute", step.lastIndexOf("<details", others) >= 0
				&& !step.substring(step.lastIndexOf("<details", others), others).contains("open"));
			WizardPageVocabularyTest.check(locale + ": the title counts the connections that can receive records",
				step.contains(WizardPage.esc(WizardMessages.get(locale, "wizard.step7dist.targets.title.other", "9"))));
			WizardPageVocabularyTest.check(locale + ": the way to the summary is still a button", step.contains("data-mdmf-goto-step=\"8\""));
		}
	}

	// -------------------------------------------------------------------------------------------------------------- landing

	private static void theLandingDoneSentenceIsTheConfirmedOne()
	{
		final WizardPage.Model model = WizardPageVocabularyTest.model();
		final WizardSnapshot.SourceInfo source = new WizardSnapshot.SourceInfo();
		source.id = "2";
		source.sourceSystem = "DemoCRM";
		final WizardSnapshot.TableInfo table = new WizardSnapshot.TableInfo();
		table.id = "20";
		table.tableName = "Company";
		table.selected = true;
		table.status = "LOADED";
		table.lastLoadRows = 12;
		table.keyColumns = "id";
		table.keyOrigin = "DEDUCED";
		table.scanOrigin = "DEDUCED";
		source.tables.add(table);
		model.snapshot.sources.add(source);
		model.stepStates = WizardRules.stepStates(model.snapshot);
		model.currentStep = WizardRules.STEP_LANDING;
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			model.locale = locale;
			table.scanConfirmedBy = "";
			table.scanConfirmedAt = 0L;
			String html = WizardPageVocabularyTest.landingDone(WizardPage.render(model));
			WizardPageVocabularyTest.check(locale + ": the done view is shown", html.contains(WizardPage.esc(WizardMessages.get(locale, "wizard.step3.run.done.title"))));
			WizardPageVocabularyTest.check(locale + ": no 'deduced scan' on the page", !html.toLowerCase(Locale.ENGLISH).contains("deduced scan"));
			final String earlier = WizardPage.esc(WizardPage.withoutTrailingColon(WizardMessages.get(locale, "wizard.review.key.deduced")));
			WizardPageVocabularyTest.check(locale + ": 'confirmed earlier' sentence, the one of the Sources cards: " + earlier, html.contains(earlier));
			table.scanConfirmedBy = "philippe";
			table.scanConfirmedAt = 1760000000000L;
			html = WizardPageVocabularyTest.landingDone(WizardPage.render(model));
			WizardPageVocabularyTest.check(locale + ": 'confirmed by <user> on <date>' once the catalog knows it", html.contains("philippe"));
			WizardPageVocabularyTest.check(locale + ": no colon left at the end of the sentence", !html.contains(":</span>") || !html.contains("philippe"));
		}
		WizardPageVocabularyTest.check("the trailing colon and the French no-break space go", WizardPage.withoutTrailingColon("Clé x :").equals("Clé x"));
		// a key chosen by a person or the naming rule keeps its own chip
		table.keyOrigin = "RULE";
		table.scanOrigin = "";
		model.locale = Locale.ENGLISH;
		WizardPageVocabularyTest.check("a rule key keeps the origin chip", WizardPageVocabularyTest.landingDone(WizardPage.render(model))
			.contains(WizardMessages.get(Locale.ENGLISH, "wizard.step3.keyOrigin.RULE")));
	}

	private static String landingDone(final String pHtml)
	{
		return WizardPageVocabularyTest.panelOf(pHtml, "3");
	}

	// ------------------------------------------------------------------------------------------------------------ glossaries

	private static void everyStepGlossaryCoversItsTerms()
	{
		for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
		{
			// step 8 (summary): needs a summary object; the glossary card is checked through the keys it is given
			for (final String key : new String[] { "wizard.step8.glossary.openPoint", "wizard.step8.glossary.intake", "wizard.step8.glossary.schedule" })
			{
				WizardPageVocabularyTest.check(locale + " " + key, !WizardMessages.get(locale, key + ".term").equals(key + ".term")
					&& !WizardMessages.get(locale, key + ".def").equals(key + ".def"));
			}
		}
		final WizardPage.Model model = WizardPageVocabularyTest.model();
		model.locale = Locale.ENGLISH;
		// step 7 and the step-2 empty state render their glossary without further data
		model.currentStep = WizardRules.STEP_DISTRIBUTION;
		model.distribution = new WizardPage.DistributionModel();
		String html = WizardPage.render(model);
		for (final String term : new String[] { "Target", "Connection", "Armed", "Data product", "Retire notice", "Cross-reference" })
		{
			WizardPageVocabularyTest.check("step 7 glossary: " + term, WizardPageVocabularyTest.glossaryOf(html, "7").contains(">" + term + "</b>"));
		}
		model.currentStep = WizardRules.STEP_SOURCES;
		html = WizardPage.render(model);
		for (final String term : new String[] { "Application", "Connection", "Catalog", "Scan", "Table", "Key", "Trust order" })
		{
			WizardPageVocabularyTest.check("step 2 glossary: " + term, WizardPageVocabularyTest.glossaryOf(html, "2").contains(">" + term + "</b>"));
		}
	}

	/** The text of the step panel {@code pStep}. */
	private static String glossaryOf(final String pHtml, final String pStep)
	{
		return WizardPageVocabularyTest.panelOf(pHtml, pStep);
	}

	/** The step panel's markup, from its {@code <section ... data-step="n">} (the rail's own {@code <li data-step>} comes first in the page) to the first {@code </section>}. */
	private static String panelOf(final String pHtml, final String pStep)
	{
		final java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("<section[^>]*data-step=\"" + pStep + "\"").matcher(pHtml);
		WizardPageVocabularyTest.check("step " + pStep + " panel found", matcher.find());
		return pHtml.substring(matcher.start(), pHtml.indexOf("</section>", matcher.start()));
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
