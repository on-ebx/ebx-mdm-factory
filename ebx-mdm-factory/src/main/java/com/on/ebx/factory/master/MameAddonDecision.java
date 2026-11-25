package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * What {@code ebx.MasterGenerator.ensureMameAddon} should do about the MAME add-on on the master model,
 * from a parsed {@code list_addons} response (`docs/MATCHING.md` &sect;5) - pure: no EBX class, so every
 * branch is unit-tested against the real, live response shape the coordinator captured on
 * {@code Companies_Master}, 2026-09-21 (quoted verbatim in `docs/MATCHING.md` &sect;5.1 and in this
 * class' own test).
 * <p>
 * <b>The real shape, confirmed live, replaces the earlier depth-bounded-search guess entirely</b>:
 * <pre>{@code
 * {"dataModelName":"Companies_Master","operation":"list_addons","found":true,"addons":[],
 *  "availableAddons":[{"label":"Digital Asset Manager","name":"ebx-addon-dama"},
 *                      {"label":"Match and Merge","name":"ebx-addon-mame"}],
 *  "code":null,"message":null,"nextStep":null}
 * }</pre>
 * {@code addons} is what is DECLARED on this model (empty on {@code Companies_Master} today - the gap
 * `docs/MATCHING.md` &sect;2.2 found); {@code availableAddons} is what is installed and activated at the
 * REPOSITORY level (`add_addon`'s own contract: "Reference an add-on already installed and activated at
 * the repository level"). The add-on's real {@code name} is {@code ebx-addon-mame} - confirmed, not a
 * guess (the earlier version of this class tried the module-id convention first and self-corrected from
 * a write-time soft-miss; that is no longer needed).
 * <p>
 * <b>Never call {@code add_addon} when already declared</b> - its own contract: "throws on blank name or
 * duplicate." {@link #decide} answers {@link Action#ALREADY_DECLARED} before {@code
 * ebx.MasterGenerator.ensureMameAddon} ever considers calling it.
 * <p>
 * <b>A declared entry whose own {@code activated} field is {@code false}</b> is treated the same as
 * {@link Action#NOT_AVAILABLE} - the add-on was declared on this model at some point but is not (or is no
 * longer) activated at the repository level, so matching still cannot work - but ONLY when that field is
 * actually present on the entry: {@code list_addons}'s own contract says entries carry
 * "id, name, version, activated, label", but the live {@code availableAddons} entries above carry only
 * {@code label}/{@code name} - so a missing {@code activated} field is read as "unknown", not "false".
 */
public final class MameAddonDecision
{
	/** Confirmed live, not guessed - `docs/MATCHING.md` &sect;5.1. */
	public static final String MAME_NAME = "ebx-addon-mame";

	private MameAddonDecision()
	{
	}

	public enum Action
	{
		/** The add-on is already declared on the model (and, if the entry says so, activated) - nothing to do, never call add_addon. */
		ALREADY_DECLARED,
		/** Not declared yet, but available at the repository level - call add_addon({@link Decision#name}). */
		DECLARE,
		/** Declared-but-not-activated, or not in the repository's installed catalogue at all - a clean refusal, no retry. */
		NOT_AVAILABLE,
		/** The response could not be read as a list_addons answer at all (missing/malformed 'addons'/'found'). */
		UNREADABLE
	}

	public static final class Decision
	{
		public final Action action;
		/** Non-null only for {@link Action#DECLARE} - the exact name to pass to add_addon. */
		public final String name;
		/** Non-null only for {@link Action#NOT_AVAILABLE}/{@link Action#UNREADABLE} - names what to do about it. */
		public final String message;

		private Decision(final Action pAction, final String pName, final String pMessage)
		{
			this.action = pAction;
			this.name = pName;
			this.message = pMessage;
		}
	}

	public static Decision decide(final JsonNode pListAddonsResponse)
	{
		if (pListAddonsResponse == null || !pListAddonsResponse.path("found").asBoolean(false) || !pListAddonsResponse.path("addons").isArray())
		{
			return new Decision(Action.UNREADABLE, null,
				"list_addons did not answer the expected shape (found=true with an 'addons' array): cannot determine the MAME add-on state.");
		}

		for (final JsonNode addon : pListAddonsResponse.path("addons"))
		{
			if (!MameAddonDecision.MAME_NAME.equals(addon.path("name").asText(null)))
			{
				continue;
			}
			if (addon.has("activated") && !addon.path("activated").asBoolean(true))
			{
				return new Decision(Action.NOT_AVAILABLE, null, "The MAME add-on is declared on this model but not activated at the repository level: "
					+ "ask an administrator to activate it in Administration > Add-ons.");
			}
			return new Decision(Action.ALREADY_DECLARED, null, null);
		}

		final JsonNode availableAddons = pListAddonsResponse.path("availableAddons");
		if (availableAddons.isArray())
		{
			for (final JsonNode addon : availableAddons)
			{
				if (MameAddonDecision.MAME_NAME.equals(addon.path("name").asText(null)))
				{
					return new Decision(Action.DECLARE, MameAddonDecision.MAME_NAME, null);
				}
			}
		}
		return new Decision(Action.NOT_AVAILABLE, null, "The MAME add-on (" + MameAddonDecision.MAME_NAME
			+ ") is not installed/activated at the repository level: ask an administrator to activate it in Administration > Add-ons.");
	}
}
