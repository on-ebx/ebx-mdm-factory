package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.landing.KeyLlmPass;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.PrimaryKey;

/**
 * What project data may reach the external language model (lot B, 2026-09-24). Two instance-level settings bound what
 * each project asks for with its own {@code llmValueSharing}:
 * <ul>
 * <li>{@code llmValueSharingMax}: {@link Level#NONE} (statistics and metadata only - not even the shapes of values),
 * {@link Level#SHAPES_ONLY} (default: letters to a/A, digits to 9, separators kept) or {@link Level#SAMPLE_VALUES}
 * (a project may then opt in to real values). The effective level is the more restrictive of the instance's and the
 * project's.</li>
 * <li>{@code llmSensitiveColumns}: column-name patterns ({@code *} wildcard, case-insensitive, comma or line separated).
 * A matching column never reaches the model, neither its values nor its shapes: the quality pass leaves it to the
 * deterministic rules, the key study withholds its sample, the matching plan never shows its frequent value. Empty
 * setting: {@link #DEFAULT_SENSITIVE}; {@link #NO_SENSITIVE_COLUMN} ({@code NONE}): no column at all. One exception, the
 * staging language-model pass only (lot E, 2026-10-17): under {@link Level#SHAPES_ONLY} or {@link Level#SAMPLE_VALUES}, a
 * sensitive date or number column tells the model HOW it is written ("date written dd/MM/yyyy (100%)", "decimal comma") -
 * a format name and a share of rows, no value, no example, no shape ({@code NormalisationProfiler#promptFacts}); the model
 * could not otherwise convert a birth date it cannot see.</li>
 * </ul>
 * For a model hosted internally under the company's own confidentiality terms, an administrator may therefore send real
 * data: {@code llmValueSharingMax = SAMPLE_VALUES}, the project's {@code llmValueSharing = SAMPLE_VALUES}, and
 * {@code llmSensitiveColumns = NONE} if even the sensitive columns may go. None of it is the default.
 * <ul>
 * </ul>
 * Column names, types, fill rates and counts always go: they are what the model reasons on. Lot S10 (2026-10-18): that includes a landing table's row count and, for a column
 * the catalog tagged as an identity, how many distinct values it holds, on how many rows a value repeats and how many values no other table holds ({@code IdentityReader}):
 * counts computed from 128-bit digests, whatever the level and even for a sensitive column - no value, no shape, no digest ever reaches the model.
 */
public final class LlmDataPolicy
{
	public enum Level
	{
		NONE, SHAPES_ONLY, SAMPLE_VALUES
	}

	/** Names that identify people or secrets in most sources; replaced entirely by the setting when it is filled. */
	public static final String DEFAULT_SENSITIVE = "*password*, *passwd*, *secret*, *token*, *apikey*, *api_key*, *iban*, bic, *_bic, bic_*, swift*, ssn, *_ssn, "
		+ "*social*security*, nir, *_nir, nir_*, *passport*, *passeport*, *credit*card*, *card*number*, *cvv*, *birth*, *naissance*, *health*, *sante*, "
		+ "*salary*, *salaire*";

	/** The {@code llmSensitiveColumns} value that means "no sensitive column" (the built-in list is used when the setting is EMPTY). */
	public static final String NO_SENSITIVE_COLUMN = "NONE";

	private final Level instanceMax;
	private final List<Pattern> sensitive;

	LlmDataPolicy(final Level pInstanceMax, final String pSensitivePatterns)
	{
		this.instanceMax = pInstanceMax;
		// "NONE": an explicit choice of no sensitive column at all (an internal, confidential model); empty: the built-in list
		this.sensitive = pSensitivePatterns != null && LlmDataPolicy.NO_SENSITIVE_COLUMN.equalsIgnoreCase(pSensitivePatterns.trim())
			? new ArrayList<Pattern>()
			: LlmDataPolicy.compile(pSensitivePatterns == null || pSensitivePatterns.trim().isEmpty() ? LlmDataPolicy.DEFAULT_SENSITIVE : pSensitivePatterns);
	}

	/** The instance's policy, read from the factory settings. */
	public static LlmDataPolicy of(final Adaptation pFactory)
	{
		final Adaptation settings = pFactory == null ? null
			: pFactory.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		return new LlmDataPolicy(LlmDataPolicy.level(settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_VALUE_SHARING_MAX), Level.SHAPES_ONLY),
			settings == null ? null : settings.getString(FactoryPaths.Settings.LLM_SENSITIVE_COLUMNS));
	}

	/** The level for {@code pProject}: the more restrictive of the instance maximum and the project's own choice (default SHAPES_ONLY). */
	public Level levelFor(final Adaptation pProject)
	{
		return this.levelFor(pProject == null ? null : pProject.getString(FactoryPaths.Project.LLM_VALUE_SHARING));
	}

	Level levelFor(final String pProjectChoice)
	{
		final Level project = LlmDataPolicy.level(pProjectChoice, Level.SHAPES_ONLY);
		return project.ordinal() < this.instanceMax.ordinal() ? project : this.instanceMax;
	}

	/**
	 * What a sample value becomes in a prompt that follows {@code pLevel}: the value itself only under
	 * {@link Level#SAMPLE_VALUES}, its shape under {@link Level#SHAPES_ONLY}, nothing under {@link Level#NONE}.
	 */
	public static KeyLlmPass.Sharing sharingOf(final Level pLevel)
	{
		return pLevel == Level.SAMPLE_VALUES ? KeyLlmPass.Sharing.VALUES : pLevel == Level.SHAPES_ONLY ? KeyLlmPass.Sharing.SHAPES : KeyLlmPass.Sharing.NONE;
	}

	/** Whether a column of that name must never reach the model. */
	public boolean isSensitive(final String pColumnName)
	{
		if (pColumnName == null)
		{
			return false;
		}
		final String name = pColumnName.toLowerCase(Locale.ROOT);
		for (final Pattern pattern : this.sensitive)
		{
			if (pattern.matcher(name).matches())
			{
				return true;
			}
		}
		return false;
	}

	static Level level(final String pValue, final Level pDefault)
	{
		if (pValue == null || pValue.trim().isEmpty())
		{
			return pDefault;
		}
		try
		{
			return Level.valueOf(pValue.trim().toUpperCase(Locale.ROOT));
		}
		catch (final IllegalArgumentException ex)
		{
			return Level.NONE; // an unreadable setting fails closed
		}
	}

	/** Pure: {@code *} matches anything, every other character literally; each pattern must match the whole (lower-cased) name. */
	static List<Pattern> compile(final String pPatterns)
	{
		final List<Pattern> out = new ArrayList<Pattern>();
		for (final String raw : pPatterns.split("[,;\\n\\r]+"))
		{
			final String glob = raw.trim().toLowerCase(Locale.ROOT);
			if (glob.isEmpty())
			{
				continue;
			}
			final StringBuilder regex = new StringBuilder();
			final String[] parts = glob.split("\\*", -1);
			for (int i = 0; i < parts.length; i++)
			{
				if (i > 0)
				{
					regex.append(".*");
				}
				if (!parts[i].isEmpty())
				{
					regex.append(Pattern.quote(parts[i]));
				}
			}
			out.add(Pattern.compile(regex.toString()));
		}
		return out;
	}
}
