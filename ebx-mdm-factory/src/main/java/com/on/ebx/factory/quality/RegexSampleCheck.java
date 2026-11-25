package com.on.ebx.factory.quality;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Measures, in plain Java, what a mapping's regex pipeline makes of sample values once the mapping's cleaning steps have run - the check a cleaning
 * step proposal needs when the mapping it lands on already carries a {@code regex(...)} (lot M8b, 2026-10-04): a step that leaves the regex with
 * nothing to match on every sample value is a defect, whatever the case rule says ({@link CaseOrderRule}).
 * <p>
 * Semantics of the {@code regex} step as Connectors implements it (read 2026-10-04, {@code Transformations}): compile the first argument, {@code
 * find()} in the text, nothing when it does not match, the group named by the second argument or, without one, group 1 when the pattern has a
 * group and group 0 otherwise. Reproduced with {@code java.util.regex} only - Connectors is not called. Only the steps this class can reproduce
 * exactly are modelled: {@code trim}, {@code upper}, {@code lower} (Locale.ROOT) and {@code regex}; the pipeline is evaluated up to and including
 * its LAST {@code regex} step, and when a step of another kind stands in front of it the pipeline is not modelled and the check answers {@code
 * null} ("not measurable", the caller skips silently) - never a guess.
 * <p>
 * Nothing leaves this class but counts and the sample values it was given: the caller decides what may be shown to a model ({@code
 * llmValueSharing}). Pure: no EBX class.
 */
public final class RegexSampleCheck
{
	private RegexSampleCheck()
	{
	}

	/** What the pipeline made of the non-empty sample values. */
	public static final class Outcome
	{
		/** Non-empty sample values tried. */
		public final int tried;
		/** How many of them gave a non-empty text. */
		public final int yielded;
		/** The first (at most two) inputs that gave nothing, as they were given - for the caller to show or to shape. */
		public final List<String> emptyInputs;

		Outcome(final int pTried, final int pYielded, final List<String> pEmptyInputs)
		{
			this.tried = pTried;
			this.yielded = pYielded;
			this.emptyInputs = pEmptyInputs;
		}

		/** Whether there was something to try and the pipeline yielded nothing on every value. */
		public boolean yieldedNothing()
		{
			return this.tried > 0 && this.yielded == 0;
		}
	}

	/**
	 * Applies the cleaning steps, then the pipeline up to its last {@code regex} step, to every non-empty value.
	 *
	 * @param pCleaningSteps the mapping's steps in execution order (rejected ones left out by the caller), applied with {@link CleaningSteps#applyStep}
	 * @param pPipeline the mapping's own pipeline
	 * @param pSamples the sample values (duplicates welcome, {@code null} and blank entries are skipped)
	 * @param pLookups lookups for a reference-based step; {@code null} reads {@link CleaningSteps.Lookups#empty}
	 * @return {@code null} when the check cannot be made: no {@code regex} step, an unreproducible step in front of it, an invalid or refused pattern
	 *         ({@link RegexGuard}), a pattern that ran past its time limit, or no non-empty sample value
	 */
	public static Outcome measure(final List<CleaningSteps.Step> pCleaningSteps, final String pPipeline, final Collection<String> pSamples,
		final CleaningSteps.Lookups pLookups)
	{
		final List<String> steps = CaseOrderRule.steps(pPipeline);
		int last = -1;
		for (int i = 0; i < steps.size(); i++)
		{
			if ("regex".equals(CaseOrderRule.nameOf(steps.get(i))))
			{
				last = i;
			}
		}
		if (last < 0 || pSamples == null)
		{
			return null;
		}
		final List<Modelled> modelled = new ArrayList<Modelled>();
		for (int i = 0; i <= last; i++)
		{
			final Modelled step = Modelled.of(steps.get(i));
			if (step == null)
			{
				return null;
			}
			modelled.add(step);
		}
		int tried = 0;
		int yielded = 0;
		final List<String> emptyInputs = new ArrayList<String>();
		for (final String sample : pSamples)
		{
			if (sample == null || sample.trim().isEmpty())
			{
				continue;
			}
			tried++;
			String value = sample;
			if (pCleaningSteps != null)
			{
				for (final CleaningSteps.Step step : pCleaningSteps)
				{
					value = value == null ? null : CleaningSteps.applyStep(step.kind, step.argument, value, null, pLookups).value;
				}
			}
			try
			{
				for (final Modelled step : modelled)
				{
					if (value == null)
					{
						break;
					}
					value = step.apply(value);
				}
			}
			catch (final RegexGuard.MatchTimeout ex)
			{
				return null; // a pattern that backtracks past its time limit is not measurable: never read as "matched" or "nothing"
			}
			if (value == null || value.trim().isEmpty())
			{
				if (emptyInputs.size() < 2)
				{
					emptyInputs.add(sample);
				}
			}
			else
			{
				yielded++;
			}
		}
		return tried == 0 ? null : new Outcome(tried, yielded, emptyInputs);
	}

	/** One reproducible pipeline step. */
	private static final class Modelled
	{
		private final String name;
		private final Pattern pattern;
		private final Integer group;

		private Modelled(final String pName, final Pattern pPattern, final Integer pGroup)
		{
			this.name = pName;
			this.pattern = pPattern;
			this.group = pGroup;
		}

		/** {@code null} when the step cannot be reproduced exactly (another kind, an invalid pattern, a group argument that is not a number). */
		static Modelled of(final String pStep)
		{
			final String name = CaseOrderRule.nameOf(pStep);
			if ("trim".equals(name) || "upper".equals(name) || "lower".equals(name))
			{
				return new Modelled(name, null, null);
			}
			if (!"regex".equals(name))
			{
				return null;
			}
			final List<String> args = CaseOrderRule.argumentsOf(pStep);
			final Pattern pattern;
			try
			{
				pattern = RegexGuard.compile(args.isEmpty() ? ".*" : args.get(0));
			}
			catch (final PatternSyntaxException ex)
			{
				return null;
			}
			Integer group = null;
			if (args.size() > 1)
			{
				try
				{
					group = Integer.valueOf(Integer.parseInt(args.get(1).trim()));
				}
				catch (final NumberFormatException ex)
				{
					group = null; // Connectors reads a non-number as "no group given"
				}
			}
			return new Modelled(name, pattern, group);
		}

		String apply(final String pValue)
		{
			if ("trim".equals(this.name))
			{
				return pValue.trim();
			}
			if ("upper".equals(this.name))
			{
				return pValue.toUpperCase(Locale.ROOT);
			}
			if ("lower".equals(this.name))
			{
				return pValue.toLowerCase(Locale.ROOT);
			}
			final Matcher matcher = RegexGuard.matcher(this.pattern, pValue);
			if (!matcher.find())
			{
				return null;
			}
			final int index = this.group != null ? this.group.intValue() : (matcher.groupCount() > 0 ? 1 : 0);
			if (index < 0 || index > matcher.groupCount())
			{
				return null;
			}
			return matcher.group(index);
		}
	}
}
