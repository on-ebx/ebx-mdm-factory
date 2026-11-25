package com.on.ebx.factory.ebx.ui;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Locale;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * JUnit-free: every step or task kind the code declares ({@code STEP = "..."}, {@code STEP_X = "..."}, {@code KIND = "..."}, and the two
 * project steps of the backup) has a real label in English and in French - the Cockpit and the project explorer showed raw keys such as
 * {@code step.PROJECT_BACKUP} for 21 of them until 2026-10-07. Also pins {@link StepLabels#readable} and that a name the bundles do not know
 * never comes back as a key.
 */
public final class StepLabelsTest
{
	private static final Pattern DECLARED = Pattern.compile("(?:STEP|STEP_[A-Z_]+|KIND) = \"([A-Z][A-Z_]+)\"");
	/** Declared constants that are not project steps (an agent step kind, a table name): they fall back to the readable form. */
	private static final Set<String> NOT_STEPS = new TreeSet<String>(java.util.Arrays.asList("LLM", "MAPPING_STEP"));

	public static void main(final String[] pArgs) throws IOException
	{
		StepLabelsTest.check("Intake trigger install".equals(StepLabels.readable("INTAKE_TRIGGER_INSTALL")), "readable form");
		final String unknown = StepLabels.of(Locale.ENGLISH, "SOMETHING_NEW_RUN");
		StepLabelsTest.check(!unknown.startsWith("step.") && !unknown.startsWith("wizard."), "an unknown kind must never show a key: " + unknown);
		final Set<String> names = StepLabelsTest.declaredNames(Paths.get(System.getProperty("mdmf.sourceDir", "ebx-mdm-factory/src/main/java")));
		names.add("PROJECT_BACKUP");
		names.add("PROJECT_RESTORE");
		names.removeAll(StepLabelsTest.NOT_STEPS);
		StepLabelsTest.check(names.size() > 40, "only " + names.size() + " step kinds found: the source scan is broken");
		final Set<String> missing = new TreeSet<String>();
		for (final String name : names)
		{
			for (final Locale locale : new Locale[] { Locale.ENGLISH, Locale.FRENCH })
			{
				if (StepLabels.of(locale, name).equals(StepLabels.readable(name)))
				{
					missing.add(name + " (" + locale + ")");
				}
			}
		}
		StepLabelsTest.check(missing.isEmpty(), "step kinds without a label (add step.<NAME> to both Messages files): " + missing);
		StepLabelsTest.check("Projet : sauvegarde".equals(StepLabels.of(Locale.FRENCH, "PROJECT_BACKUP")), "French label of PROJECT_BACKUP");
		System.out.println("StepLabelsTest OK (" + names.size() + " kinds)");
	}

	private static Set<String> declaredNames(final Path pRoot) throws IOException
	{
		final Set<String> out = new TreeSet<String>();
		try (Stream<Path> walk = Files.walk(pRoot))
		{
			for (final Path file : (Iterable<Path>) walk.filter(p -> p.toString().endsWith(".java"))::iterator)
			{
				final Matcher m = StepLabelsTest.DECLARED.matcher(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
				while (m.find())
				{
					out.add(m.group(1));
				}
			}
		}
		return out;
	}

	private static void check(final boolean pOk, final String pWhat)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
