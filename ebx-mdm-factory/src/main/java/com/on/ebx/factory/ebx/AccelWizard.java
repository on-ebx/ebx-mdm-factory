package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.on.ebx.factory.wizard.WizardSnapshot;
import com.onwbp.adaptation.Adaptation;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Session;

/**
 * Lot C1-B: the one public door of the CENTRALISATION wizard path into the {@code ebx} package, for {@code ebx.ui.ProjectWizardUserService}
 * (which cannot see the package-private {@link AccelData} and {@link AccelHooks}). Every method throws an {@link OperationException}
 * the service already knows how to show; a hook that sub-lot C or D has not filled yet ({@link UnsupportedOperationException}) becomes the
 * coded refusal {@code wizard.accel.notAvailable} - a notice on the page, never a stack trace.
 */
public final class AccelWizard
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	private AccelWizard()
	{
	}

	/** What pass 0 answered: the packs and options it suggests ({@code "pack:option"}) and its reason, in the person's language. */
	public static final class Suggestion
	{
		public final List<String> packs = new ArrayList<String>();
		public final List<String> options = new ArrayList<String>();
		public String why = "";
	}

	/** The catalogue of packs, for the screens that need it without a snapshot. */
	public static List<WizardSnapshot.PackInfo> catalogue(final Locale pLocale)
	{
		return AccelHooks.packCatalogue(pLocale);
	}

	/** Pass 0. A suggestion naming a pack the catalogue does not hold is dropped, so the screen never ticks a card it does not draw. */
	public static Suggestion suggestPacks(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pNeed, final Locale pLocale)
		throws OperationException
	{
		final String answer;
		try
		{
			answer = AccelHooks.suggestPacks(pSession, pFactory, pProjectId, pNeed, pLocale);
		}
		catch (final UnsupportedOperationException ex)
		{
			throw AccelWizard.notAvailable();
		}
		final Suggestion out = new Suggestion();
		try
		{
			final JsonNode json = AccelWizard.MAPPER.readTree(answer == null ? "{}" : answer);
			final List<String> known = new ArrayList<String>();
			for (final WizardSnapshot.PackInfo pack : AccelHooks.packCatalogue(pLocale))
			{
				known.add(pack.key);
			}
			for (final JsonNode pack : json.path("packs"))
			{
				if (known.contains(pack.asText()) && !out.packs.contains(pack.asText()))
				{
					out.packs.add(pack.asText());
				}
			}
			for (final JsonNode option : json.path("options"))
			{
				out.options.add(option.asText());
			}
			out.why = json.path("why").asText("");
		}
		catch (final java.io.IOException ex)
		{
			throw EngineRefusal.of("wizard.accel.suggestUnreadable", "The pack suggestion could not be read: " + ex.getMessage());
		}
		return out;
	}

	/**
	 * Saves the chosen packs of the project with the options taken, the need as their specification, and who chose ({@code LLM} when the
	 * model suggested exactly these, {@code USER} otherwise). {@code pOptions} holds {@code "pack:option"} entries.
	 */
	public static void choosePacks(final Session pSession, final Adaptation pFactory, final String pProjectId, final List<String> pPackKeys, final List<String> pOptions,
		final String pNeed, final boolean pChosenByModel, final Locale pLocale) throws OperationException
	{
		final Map<String, List<String>> optionsByPack = new HashMap<String, List<String>>();
		for (final String entry : pOptions)
		{
			final int colon = entry.indexOf(':');
			if (colon > 0 && colon < entry.length() - 1)
			{
				final String pack = entry.substring(0, colon);
				if (!optionsByPack.containsKey(pack))
				{
					optionsByPack.put(pack, new ArrayList<String>());
				}
				optionsByPack.get(pack).add(entry.substring(colon + 1));
			}
		}
		final Map<String, String> versions = new HashMap<String, String>();
		for (final WizardSnapshot.PackInfo pack : AccelHooks.packCatalogue(pLocale))
		{
			versions.put(pack.key, pack.version);
		}
		AccelData.savePacks(pSession, pFactory, pProjectId, pPackKeys, optionsByPack, versions, pNeed, pChosenByModel ? "LLM" : "USER");
	}

	/** @return how many pack rows carry the text now (0 = no pack chosen yet, the text only stays on the screen). */
	public static int saveNeed(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pNeed) throws OperationException
	{
		return AccelData.saveNeed(pSession, pFactory, pProjectId, pNeed);
	}

	/** "Edit" on a difference: replaces its JSON detail (the caller then accepts it through the ordinary decide path). */
	public static void editDifference(final Session pSession, final Adaptation pFactory, final String pDifferenceId, final String pDetailJson) throws OperationException
	{
		AccelData.replaceDetail(pSession, pFactory, pDifferenceId, pDetailJson);
	}

	public static void runProposal(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final Locale pLocale)
		throws OperationException
	{
		try
		{
			AccelHooks.runProposal(pSession, pFactory, pProjectId, pProjectCode, pLocale);
		}
		catch (final UnsupportedOperationException ex)
		{
			throw AccelWizard.notAvailable();
		}
	}

	public static void runPass(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final int pPass, final boolean pRunAll,
		final Locale pLocale) throws OperationException
	{
		try
		{
			AccelHooks.runPass(pSession, pFactory, pProjectId, pProjectCode, pPass, pRunAll, pLocale);
		}
		catch (final UnsupportedOperationException ex)
		{
			throw AccelWizard.notAvailable();
		}
	}

	public static void generate(final Session pSession, final Adaptation pFactory, final String pProjectId, final String pProjectCode, final Locale pLocale)
		throws OperationException
	{
		try
		{
			AccelHooks.generate(pSession, pFactory, pProjectId, pProjectCode, pLocale);
		}
		catch (final UnsupportedOperationException ex)
		{
			throw AccelWizard.notAvailable();
		}
	}

	private static EngineRefusal notAvailable()
	{
		return EngineRefusal.of("wizard.accel.notAvailable", "This part of the accelerator path is not available in this build yet.");
	}
}
