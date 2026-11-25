package com.on.ebx.factory.capability;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Whether an {@code APPLIED SCRIPT_CONSTRAINT} row's LIVE form - what is actually declared/authored on the
 * model, captured verbatim in the row's own stored fields at the last apply - still matches what applying the
 * SAME row again, right now, would generate. "Re-apply" lot (2026-09-21), {@code docs/CAPABILITY-APPLY.md}
 * open item (c). Pure: no EBX/MCP class anywhere in this file, decided entirely from the row's own stored
 * facts ({@code constraintScope}, {@code appliedElementPath}, {@code scriptBody}, {@code binding}) plus a
 * handful of other stored, non-structural facts ({@code importName}/{@code alias}/{@code functionName}/
 * {@code severity}) a caller resolves once and hands in as plain strings - the same {@code capability.*}
 * pure/{@code ebx.*} EBX-bound split every other class in this package follows ({@code ConstraintBodyBuilder}
 * itself, {@code ConstraintBodySubmission}...).
 * <p>
 * <b>Deliberately conservative for a language-model-authored body</b> (anything whose target function does
 * not return {@code boolean}): free-form model code is NEVER diffed line by line against "what a model might
 * write today" - that would flag almost anything as stale, a harmless rephrasing included. Only three
 * STRUCTURAL facts the factory itself controls are checked, and they happen to be exactly the three real
 * staleness classes seen so far on this project (PLAN.md, 2026-09-21 soir, items (c)/(d)):
 * <ol>
 * <li>the declared SCOPE - {@link ConstraintBodyBuilder#chooseScope} never produces {@code TABLE} any more
 * (fix/node-scope-multi-attribute); a row whose OWN stored {@code constraintScope} still reads {@code TABLE}
 * is stale regardless of anything else, and the other two checks are skipped for it (its own element path
 * carries no attribute segment to check them against - see {@link #splitElementPath});</li>
 * <li>whether the applied {@code setup()} still declares an explicit dependency
 * ({@code core.validation.addDependencyToModify}/{@code setDependencyToLocalNode}) on every attribute the
 * CURRENT binding reads - a textual search on the stored {@code scriptBody}, never a real EBX Script parse;</li>
 * <li>whether the reported message is the OLD, now-removed Java-built technical wording
 * ({@code '<field>' failed <alias>.<function>.} - the exact shape the former {@code ConstraintBodyBuilder
 * .deterministicMessage} used to emit for a NODE constraint; see git history, and {@code docs/CAPABILITY-
 * APPLY.md} section 0a). A TEMPLATE body is a special case of this same check, not a separate one: since a
 * template embeds its message the same way, the identical literal-extraction/comparison catches it too, with
 * no need to know whether the body was template- or language-model-authored in the first place.</li>
 * </ol>
 * A full byte-for-byte re-render of a TEMPLATE body was deliberately NOT attempted: that would also need the
 * target function's parameter TYPES and every accepted attribute's MANDATORY flag, neither of which a plain
 * review-screen render has on hand without an extra live MCP/entity-attribute lookup - out of scope for what
 * must stay a fast, offline decision.
 */
public final class ConstraintStaleness
{
	private ConstraintStaleness()
	{
	}

	/** Everything the review-time reader has on hand about one {@code APPLIED SCRIPT_CONSTRAINT} row - no MCP call, only the row's own stored fields plus the target library/function facts stored alongside the suggestion. */
	public static final class AppliedFacts
	{
		/** {@code CapabilitySuggestion.constraintScope} as stored - {@code "NODE"}/{@code "TABLE"}/blank. */
		public final String constraintScope;
		/** {@code CapabilitySuggestion.appliedElementPath} as stored, e.g. {@code /root/Contact/phone} or {@code /root/Supplier}. */
		public final String appliedElementPath;
		/** {@code CapabilitySuggestion.scriptBody} as stored. */
		public final String scriptBody;
		/** {@code CapabilitySuggestion.binding}, the raw JSON as stored - what the CURRENT dependency set is computed from. */
		public final String bindingJson;
		/** The import a constraint on this row would use today - {@code targetImportName}, or {@code targetLibrary} when blank (the same {@code CapabilityApplier.orElse} fallback). */
		public final String importName;
		/** {@code ConstraintBodyBuilder}-style alias of {@link #importName} (last dotted segment) - the caller resolves this once, the same way {@code CapabilityApplier.aliasOf} does, since that method is EBX-package-private and this class stays EBX-free. */
		public final String alias;
		/** {@code CapabilitySuggestion.targetFunction} as stored. */
		public final String functionName;
		/** {@code CapabilitySuggestion.severity} as stored - {@code WARN}/{@code ERROR}. */
		public final String severity;

		public AppliedFacts(final String pConstraintScope, final String pAppliedElementPath, final String pScriptBody, final String pBindingJson,
			final String pImportName, final String pAlias, final String pFunctionName, final String pSeverity)
		{
			this.constraintScope = pConstraintScope;
			this.appliedElementPath = pAppliedElementPath;
			this.scriptBody = pScriptBody;
			this.bindingJson = pBindingJson;
			this.importName = pImportName;
			this.alias = pAlias;
			this.functionName = pFunctionName;
			this.severity = pSeverity;
		}
	}

	/** {@link #stale} - whether Re-apply would change anything; {@link #reasons} - never empty when {@link #stale}, always empty otherwise. */
	public static final class Result
	{
		public final boolean stale;
		public final List<String> reasons;

		private Result(final boolean pStale, final List<String> pReasons)
		{
			this.stale = pStale;
			this.reasons = pReasons;
		}

		private static Result current()
		{
			return new Result(false, Collections.<String>emptyList());
		}

		private static Result stale(final List<String> pReasons)
		{
			return new Result(true, pReasons);
		}

		/** Every reason, space-joined - the tooltip/"See" block text a reviewer reads; empty string when {@link #stale} is false. */
		public String describe()
		{
			final StringBuilder out = new StringBuilder();
			for (final String reason : this.reasons)
			{
				if (out.length() > 0)
				{
					out.append(' ');
				}
				out.append(reason);
			}
			return out.toString();
		}
	}

	public static Result evaluate(final AppliedFacts pFacts)
	{
		final List<String> reasons = new ArrayList<String>();

		final boolean scopeStale = pFacts.constraintScope != null && !pFacts.constraintScope.trim().isEmpty()
			&& !"NODE".equalsIgnoreCase(pFacts.constraintScope.trim());
		if (scopeStale)
		{
			reasons.add("applied as a table constraint; the current form is a field constraint with explicit dependencies.");
		}

		final String[] pathParts = ConstraintStaleness.splitElementPath(pFacts.appliedElementPath);
		final String entityName = pathParts[0];
		final String anchorAttribute = pathParts[1];

		// the dependency/message checks only make sense once the row IS (or, per chooseScope, would become)
		// NODE - a TABLE element path carries no attribute segment to check them against, and the scope
		// reason above already explains everything a Re-apply of that row would fix
		if (!scopeStale && anchorAttribute != null && entityName != null)
		{
			ConstraintStaleness.checkDependencies(pFacts, entityName, anchorAttribute, reasons);
			ConstraintStaleness.checkMessage(pFacts, anchorAttribute, reasons);
		}

		return reasons.isEmpty() ? Result.current() : Result.stale(reasons);
	}

	private static void checkDependencies(final AppliedFacts pFacts, final String pEntityName, final String pAnchorAttribute, final List<String> pReasons)
	{
		if (pFacts.scriptBody == null || pFacts.scriptBody.isEmpty())
		{
			return;
		}
		final Map<String, ConstraintBodyBuilder.BindingEntry> binding = ConstraintBodyBuilder.parseBinding(pFacts.bindingJson);
		final ConstraintBodyBuilder.ScopeDecision scope = ConstraintBodyBuilder.chooseScope(binding, pAnchorAttribute);
		final List<String> dependencies = ConstraintBodyBuilder.dependencyAttributes(scope);
		for (final String attribute : dependencies)
		{
			if (!ConstraintStaleness.declaresDependency(pFacts.scriptBody, pEntityName, attribute, dependencies.size()))
			{
				pReasons.add("the applied setup() does not declare a dependency on '" + attribute + "': a change to it will not re-run this check.");
			}
		}
	}

	/** A single dependency (the anchor alone) is satisfied by EITHER {@code setDependencyToLocalNode()} or an explicit {@code addDependencyToModify} on that one field - several dependencies each need their OWN {@code addDependencyToModify} call (fix/node-scope-multi-attribute's own rule, {@code ConstraintBodyBuilder#buildSetupBlock}). */
	private static boolean declaresDependency(final String pScriptBody, final String pEntityName, final String pAttribute, final int pDependencyCount)
	{
		final String explicitCall = "addDependencyToModify('/root/" + pEntityName + "/" + pAttribute + "'";
		if (pDependencyCount <= 1)
		{
			return pScriptBody.contains("setDependencyToLocalNode(") || pScriptBody.contains(explicitCall);
		}
		return pScriptBody.contains(explicitCall);
	}

	private static void checkMessage(final AppliedFacts pFacts, final String pAnchorAttribute, final List<String> pReasons)
	{
		final ConstraintBodyBuilder.MessageAttribution attribution = ConstraintBodyBuilder.extractReportedMessage(pFacts.scriptBody, pFacts.severity);
		if (attribution == null)
		{
			return;
		}
		final String technical = "'" + pAnchorAttribute + "' failed " + pFacts.alias + "." + pFacts.functionName + ".";
		if (technical.equals(attribution.text))
		{
			pReasons.add("the record-facing message is the old technical one: " + attribution.text);
		}
	}

	/** {@code /root/Entity/attribute} -&gt; {@code {"Entity", "attribute"}}; {@code /root/Entity} -&gt; {@code {"Entity", null}}; blank/malformed -&gt; {@code {null, null}}. */
	private static String[] splitElementPath(final String pElementPath)
	{
		if (pElementPath == null || pElementPath.trim().isEmpty())
		{
			return new String[] { null, null };
		}
		String trimmed = pElementPath.trim();
		if (trimmed.startsWith("/root/"))
		{
			trimmed = trimmed.substring("/root/".length());
		}
		final int slash = trimmed.indexOf('/');
		return slash < 0 ? new String[] { trimmed, null } : new String[] { trimmed.substring(0, slash), trimmed.substring(slash + 1) };
	}
}
