package com.on.ebx.factory.review;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Turns a {@code MappingStep}/{@code QualityRule} row's {@code kind} + {@code argument} (raw enum value plus,
 * for some kinds, a small JSON object - {@code quality.CleaningSteps}'s own argument shapes) into the plain
 * English words a reviewer reads in the Proposal Review screen (review-quality lot, task B) - never the raw
 * JSON a person would otherwise have to parse by eye (e.g. {@code {"reference":"LEGAL_FORM","scopeField":
 * "country"}} becomes "Transcode to reference LEGAL_FORM, scoped by COUNTRY", the task's own example). Pure:
 * no EBX class, and deliberately NOT shared with {@code quality.CleaningSteps}/{@code quality.QualityRuleCheck}
 * (which read the SAME argument shapes for EXECUTION, not display) - a wording bug must never risk an
 * execution bug and vice versa, so this class re-reads the small, fixed argument shapes on its own, tolerantly
 * (a malformed argument degrades to showing the raw text, never throws).
 * <p>
 * {@code position} (for a {@code MappingStep}) and {@code severity} (for a {@code QualityRule}) are folded
 * into the SAME proposition string rather than becoming new {@link ReviewLine} fields - {@link ReviewLine}'s
 * shape is shared by six natures and {@link ReviewPage} renders it generically; adding a nature-specific
 * column for just two of them would either show empty cells everywhere else or need per-nature rendering
 * branches. Folding the two extra facts into the one line of text every nature already has keeps the table
 * uniform and still answers what task B asked for (a reviewer reads "Step 2:" or "WARN " at a glance, in the
 * proposition itself - the first thing on the line).
 */
public final class ProposalWording
{
	private ProposalWording()
	{
	}

	// -------------------------------------------------------------------------------------------- MappingStep

	/**
	 * @param pPosition 1-based execution order within its mapping's pipeline; {@code null} or &lt;= 0 omits the "Step N:" prefix (defensive - every real row has one)
	 */
	public static String mappingStepProposition(final String pKind, final String pArgument, final Integer pPosition)
	{
		final String prefix = pPosition != null && pPosition.intValue() > 0 ? "Step " + pPosition + ": " : "";
		return prefix + ProposalWording.mappingStepWords(pKind, pArgument);
	}

	private static String mappingStepWords(final String pKind, final String pArgument)
	{
		final String kind = pKind == null ? "" : pKind.trim().toUpperCase(Locale.ROOT);
		final String argument = pArgument == null ? "" : pArgument.trim();
		if ("TRIM".equals(kind))
		{
			return "Trim leading/trailing spaces";
		}
		if ("COLLAPSE_SPACES".equals(kind))
		{
			return "Collapse repeated inner spaces";
		}
		if ("EMPTY_TO_NULL".equals(kind))
		{
			return argument.isEmpty() ? "Treat a known empty-ish value as null (N/A, NA, -, ...)"
				: "Treat as null: " + argument.replace('|', ',');
		}
		if ("UPPER".equals(kind))
		{
			return "Convert to upper case";
		}
		if ("LOWER".equals(kind))
		{
			return "Convert to lower case";
		}
		if ("TITLE_CASE".equals(kind))
		{
			return "Convert to title case (first letter of each word in capitals)";
		}
		if ("STRIP_CHARS".equals(kind))
		{
			return argument.isEmpty() ? "Remove characters" : "Remove characters: " + argument;
		}
		if ("STRIP_ACCENTS".equals(kind))
		{
			return "Remove accents (e.g. é → e, ü → u)";
		}
		if ("NORMALISE_IDENTIFIER".equals(kind))
		{
			return "Normalise identifier (strip separators, upper-case)" + (argument.isEmpty() ? "" : ", separators: " + argument);
		}
		if ("COUNTRY_TO_ISO2".equals(kind))
		{
			return "Convert country name to ISO 3166-1 alpha-2 code";
		}
		if ("CURRENCY_TO_ISO".equals(kind))
		{
			return "Convert currency to ISO 4217 code";
		}
		if ("TRANSCODE_REFERENCE".equals(kind))
		{
			return ProposalWording.transcodeReferenceWords(argument);
		}
		// an unrecognised kind (a future value, or a hand-edited row): show it as-is rather than a blank line
		return kind.isEmpty() ? "(no kind set)" : kind + (argument.isEmpty() ? "" : " (" + argument + ")");
	}

	/** {@code {"reference":"LEGAL_FORM","scopeField":"country"}} -&gt; "Transcode to reference LEGAL_FORM, scoped by COUNTRY" - the task's own worked example. A malformed/missing argument degrades to a shorter, still-readable phrase rather than throwing. */
	private static String transcodeReferenceWords(final String pArgument)
	{
		// codes transcoding lot H3 (2026-10-17): a step that carries its own code table says what it maps, never the JSON
		final com.on.ebx.factory.quality.CodeMap table = com.on.ebx.factory.quality.CodeMap.parseCached(pArgument);
		if (table != null)
		{
			final StringBuilder targets = new StringBuilder();
			for (int i = 0; i < table.targets.size() && i < 8; i++)
			{
				targets.append(i > 0 ? ", " : "").append(table.targets.get(i));
			}
			if (table.targets.size() > 8)
			{
				targets.append(", ...");
			}
			return "Map the " + table.codes.size() + " codes of this source onto the common list (" + targets + ")"
				+ (table.basedOn.isEmpty() ? "" : ", the codes of the reference " + table.basedOn);
		}
		final String reference = ProposalWording.jsonStringField(pArgument, "reference");
		if (reference == null)
		{
			return "Transcode to a reference list";
		}
		final String scopeField = ProposalWording.jsonStringField(pArgument, "scopeField");
		return scopeField == null || scopeField.isEmpty() ? "Transcode to reference " + reference
			: "Transcode to reference " + reference + ", scoped by " + scopeField.toUpperCase(Locale.ROOT);
	}

	// -------------------------------------------------------------------------------- StagingAttribute (derived)

	/**
	 * {@code "Add a matching key cityKey, derived from city without accents or case, so that source systems
	 * meet (12 shared value(s) after folding, 0 before)."} - the plain-English headline stored in a
	 * {@code FOLD_KEY} {@code StagingAttribute} proposal's own {@code rationale} (product-owner decision,
	 * 2026-09-22 - {@code quality.CrossSystemFold}): a derived attribute must show its SOURCE and its PURPOSE
	 * at a glance, the same "leading sentence" discipline as every other proposition in this file. Deliberately
	 * says "source systems", never a specific pair like "CRM and ERP" - the task that motivated this wording
	 * happened to involve two named systems, but nothing here may be keyed on a system name (docs/NORMALISATION.md
	 * sec 4's own discipline, restated for this proposer by {@code CrossSystemFold}'s class Javadoc): the same
	 * sentence must read correctly for any number of systems, named anything.
	 */
	public static String foldKeyAttributeProposition(final String pKeyName, final String pSourceLabel, final long pSharedBefore, final long pSharedAfter)
	{
		final String source = pSourceLabel == null || pSourceLabel.trim().isEmpty() ? "?" : pSourceLabel.trim();
		final String key = pKeyName == null || pKeyName.trim().isEmpty() ? "?" : pKeyName.trim();
		return "Add a matching key " + key + ", derived from " + source + " without accents or case, so that source systems meet (" + pSharedAfter
			+ " shared value(s) after folding, " + pSharedBefore + " before).";
	}

	// --------------------------------------------------------------------------------------------- QualityRule

	/** @param pSeverity {@code MODEL_CONSTRAINT}/{@code REJECT}/{@code WARN}; folded as a leading word so a reviewer sees it first, same reasoning as {@link #mappingStepProposition}'s "Step N:" prefix. */
	public static String qualityRuleProposition(final String pKind, final String pArgument, final String pSeverity)
	{
		final String severityPrefix = pSeverity == null || pSeverity.trim().isEmpty() ? "" : pSeverity.trim().toUpperCase(Locale.ROOT) + " - ";
		return severityPrefix + ProposalWording.qualityRuleWords(pKind, pArgument);
	}

	private static String qualityRuleWords(final String pKind, final String pArgument)
	{
		final String kind = pKind == null ? "" : pKind.trim().toUpperCase(Locale.ROOT);
		final String argument = pArgument == null ? "" : pArgument.trim();
		if ("MANDATORY".equals(kind))
		{
			return "Must not be empty";
		}
		if ("PATTERN".equals(kind))
		{
			return argument.isEmpty() ? "Must match a pattern" : "Must match pattern: " + argument;
		}
		if ("REFERENCE".equals(kind))
		{
			return argument.isEmpty() ? "Must resolve against a reference list" : "Must resolve against reference list: " + argument;
		}
		if ("CHECK_DIGIT".equals(kind))
		{
			return argument.isEmpty() ? "Check digit must be valid" : "Check digit must be valid: " + argument;
		}
		if ("UNIQUE".equals(kind))
		{
			return "Should be unique within the entity (evidence for Match and Merge, never enforced as a staging constraint)";
		}
		return kind.isEmpty() ? "(no kind set)" : kind + (argument.isEmpty() ? "" : " (" + argument + ")");
	}

	// --------------------------------------------------------------------------------------- CapabilitySuggestion

	/**
	 * {@code SCRIPT_CONSTRAINT}: {@code "<severity> - Position <function> of <library> (<zone>)"}, the same
	 * "leading word first" convention {@link #qualityRuleProposition} uses for severity - a reviewer sees
	 * severity and zone at a glance. Never the raw {@code binding} JSON (capability-suggestion lot,
	 * mirroring the task's own "kind+argument in plain words, never raw JSON" rule for MappingStep/
	 * QualityRule) - the binding itself is shown separately in the rationale by {@link #capabilityBinding}.
	 */
	public static String scriptConstraintProposition(final String pLibraryIdePath, final String pFunctionName, final String pZone, final String pSeverity)
	{
		final String severityPrefix = pSeverity == null || pSeverity.trim().isEmpty() ? "" : pSeverity.trim().toUpperCase(Locale.ROOT) + " - ";
		final String library = pLibraryIdePath == null ? "?" : pLibraryIdePath;
		final String function = pFunctionName == null ? "?" : pFunctionName;
		final String zone = pZone == null || pZone.trim().isEmpty() ? "" : " (" + pZone.trim().toUpperCase(Locale.ROOT) + ")";
		return severityPrefix + "Position " + function + " of " + library + zone;
	}

	/**
	 * {@code SCRIPT_DERIVATION} (script-backed-derivation lot, 2026-09-23): {@code "Compute <newAttribute> from
	 * <sourceAttribute> with <function> of <library>"} - unlike {@link #scriptConstraintProposition}, there is
	 * no severity/zone to show (a derivation is always STAGING, never flags a record - {@code
	 * quality.ScriptDerivation}'s own Javadoc), but there IS a second name a reviewer needs to see at a glance:
	 * what the NEW attribute is called, distinct from {@code pSourceAttribute} (the existing one it reads).
	 */
	public static String scriptDerivationProposition(final String pNewAttributeName, final String pSourceAttribute, final String pLibraryIdePath,
		final String pFunctionName)
	{
		final String newAttribute = pNewAttributeName == null || pNewAttributeName.trim().isEmpty() ? "?" : pNewAttributeName.trim();
		final String source = pSourceAttribute == null || pSourceAttribute.trim().isEmpty() ? "?" : pSourceAttribute.trim();
		final String library = pLibraryIdePath == null ? "?" : pLibraryIdePath;
		final String function = pFunctionName == null ? "?" : pFunctionName;
		return "Compute " + newAttribute + " from " + source + " with " + function + " of " + library;
	}

	/**
	 * {@code EXTERNAL_SERVICE}: {@code "Look into <operation> of <connector> (<readiness>[, <pricing>])"} - a
	 * reviewer sees readiness AND pricing at a glance, without reading the full rationale (service-choice lot,
	 * 2026-09-21: "a service line shows readiness and pricing", e.g. "Melissa · phone - not configured · paid"
	 * in the task brief's own illustration; rendered here with plain ASCII punctuation, same convention as
	 * every other proposition in this file). Readiness reads {@code ready}/{@code not configured}; pricing is
	 * shown lower case and only when it is not {@code UNKNOWN} - the task brief is explicit that {@code
	 * UNKNOWN} is "neither preferred nor penalised", so showing it would read as a judgment that was never
	 * made.
	 */
	public static String externalServiceProposition(final String pConnectorId, final String pOperation, final boolean pReady, final String pPricing)
	{
		final String connector = pConnectorId == null ? "?" : pConnectorId;
		final String operation = pOperation == null ? "?" : pOperation;
		final StringBuilder detail = new StringBuilder(pReady ? "ready" : "not configured");
		final String pricingWord = ProposalWording.pricingWord(pPricing);
		if (pricingWord != null)
		{
			detail.append(", ").append(pricingWord);
		}
		return "Look into " + operation + " of " + connector + " (" + detail + ")";
	}

	/** {@code FREE}/{@code FREEMIUM}/{@code PAID} (case-insensitive) -&gt; the lower-case word shown in a proposition; {@code UNKNOWN}, null, blank or an unrecognized tag -&gt; null (never shown - see {@link #externalServiceProposition}'s own Javadoc for why {@code UNKNOWN} is silent, not just another word). */
	private static String pricingWord(final String pPricing)
	{
		if (pPricing == null)
		{
			return null;
		}
		final String upper = pPricing.trim().toUpperCase(Locale.ROOT);
		if ("FREE".equals(upper))
		{
			return "free";
		}
		if ("FREEMIUM".equals(upper))
		{
			return "freemium";
		}
		return "PAID".equals(upper) ? "paid" : null;
	}

	/**
	 * {@code {"a":"x","b":"y"}} -&gt; "Binding: a=x, b=y" - a minimal, dependency-free reader of {@code
	 * CapabilitySuggestion.binding}'s shape, kept everywhere else in this codebase as opaque JSON; a
	 * malformed/empty binding degrades to an empty string rather than throwing. A value that is itself an
	 * object of the form {@code {"literal":"..."}} (a script or service parameter bound to a documented
	 * constant - {@code capability.CapabilityLlmPass}, coordinator review 2026-09-21) renders as {@code
	 * a=(literal) x} rather than the raw nested JSON. A value of the form {@code {"toBeProvided":"..."}}
	 * (coverage lot, 2026-09-21: a parameter/input a person must still wire up, rather than a wrong attribute
	 * forced onto it) renders as {@code a: to be provided - reason} - deliberately NOT the {@code a=...} shape
	 * the other two use, so it stands out as unfinished business rather than reading like a settled link (task
	 * brief's own illustration: {@code "registrationAuthority: to be provided - ..."}). Splits top-level commas
	 * depth-aware (tracking {@code {}}/{@code []} nesting), so a nested value with a comma in it does not get
	 * mis-split - the one respect in which this needed to grow past a flat, single-level parser once bindings
	 * could nest.
	 */
	public static String capabilityBinding(final String pBindingJson)
	{
		if (pBindingJson == null || pBindingJson.trim().isEmpty())
		{
			return "";
		}
		final String trimmed = pBindingJson.trim();
		if (trimmed.length() < 2 || trimmed.charAt(0) != '{' || trimmed.charAt(trimmed.length() - 1) != '}')
		{
			return "";
		}
		final List<String> pairs = new ArrayList<String>();
		for (final String entry : ProposalWording.splitTopLevel(trimmed.substring(1, trimmed.length() - 1)))
		{
			final int colon = ProposalWording.firstTopLevelColon(entry);
			if (colon < 0)
			{
				continue;
			}
			final String key = ProposalWording.unquote(entry.substring(0, colon).trim());
			final String rawValue = entry.substring(colon + 1).trim();
			if (key.isEmpty())
			{
				continue;
			}
			pairs.add(ProposalWording.renderBindingPair(key, rawValue));
		}
		if (pairs.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder("Binding: ");
		for (int i = 0; i < pairs.size(); i++)
		{
			if (i > 0)
			{
				out.append(", ");
			}
			out.append(pairs.get(i));
		}
		return out.toString();
	}

	/** One rendered binding entry, key included - a plain quoted string renders {@code key=value}; {@code {"literal":"x"}} renders {@code key=(literal) x}; {@code {"toBeProvided":"reason"}} renders {@code key: to be provided - reason} (coverage lot - deliberately not the {@code key=...} shape, see {@link #capabilityBinding}'s own Javadoc); anything else (a future shape) renders {@code key=<raw JSON>} as-is rather than guessing. */
	private static String renderBindingPair(final String pKey, final String pRawValue)
	{
		if (pRawValue.startsWith("{") && pRawValue.endsWith("}"))
		{
			final String inner = pRawValue.substring(1, pRawValue.length() - 1).trim();
			final int colon = ProposalWording.firstTopLevelColon(inner);
			if (colon > 0)
			{
				final String innerKey = ProposalWording.unquote(inner.substring(0, colon).trim());
				final String innerValue = ProposalWording.unquote(inner.substring(colon + 1).trim());
				if ("literal".equals(innerKey))
				{
					return pKey + "=(literal) " + innerValue;
				}
				if ("toBeProvided".equals(innerKey))
				{
					return pKey + ": to be provided - " + innerValue;
				}
			}
			return pKey + "=" + pRawValue;
		}
		return pKey + "=" + ProposalWording.unquote(pRawValue);
	}

	/**
	 * Apply lot (2026-09-21): {@code "N record(s) checked, M reported"} for an APPLIED {@code
	 * SCRIPT_CONSTRAINT} line's measured effect - null counters (never measured, or a count the measurement
	 * itself could not obtain - {@code ebx.CapabilityApplier}'s own {@code -1} sentinel) read as "not measured"
	 * rather than a misleading "0 checked".
	 */
	public static String capabilityMeasuredEffect(final Long pRecordsChecked, final Long pRecordsReported)
	{
		if (pRecordsChecked == null || pRecordsChecked.longValue() < 0)
		{
			return "Effect not measured.";
		}
		return pRecordsChecked + " record(s) checked, " + (pRecordsReported == null ? 0 : pRecordsReported.longValue()) + " reported.";
	}

	/** Splits {@code pInner} on top-level commas only - a comma inside a nested {@code {}}/{@code []} (or inside a quoted string) never ends an entry. */
	private static List<String> splitTopLevel(final String pInner)
	{
		final List<String> out = new ArrayList<String>();
		int depth = 0;
		boolean inString = false;
		int start = 0;
		for (int i = 0; i < pInner.length(); i++)
		{
			final char c = pInner.charAt(i);
			if (c == '"' && (i == 0 || pInner.charAt(i - 1) != '\\'))
			{
				inString = !inString;
			}
			else if (!inString && (c == '{' || c == '['))
			{
				depth++;
			}
			else if (!inString && (c == '}' || c == ']'))
			{
				depth--;
			}
			else if (!inString && depth == 0 && c == ',')
			{
				out.add(pInner.substring(start, i).trim());
				start = i + 1;
			}
		}
		final String last = pInner.substring(start).trim();
		if (!last.isEmpty())
		{
			out.add(last);
		}
		return out;
	}

	/** The first top-level {@code :} of {@code pEntry} - never one inside a quoted string. */
	private static int firstTopLevelColon(final String pEntry)
	{
		boolean inString = false;
		for (int i = 0; i < pEntry.length(); i++)
		{
			final char c = pEntry.charAt(i);
			if (c == '"' && (i == 0 || pEntry.charAt(i - 1) != '\\'))
			{
				inString = !inString;
			}
			else if (!inString && c == ':')
			{
				return i;
			}
		}
		return -1;
	}

	private static String unquote(final String pValue)
	{
		if (pValue.length() >= 2 && pValue.charAt(0) == '"' && pValue.charAt(pValue.length() - 1) == '"')
		{
			return pValue.substring(1, pValue.length() - 1);
		}
		return pValue;
	}

	// ------------------------------------------------------------------------------------------- MatchPlan (review)

	/**
	 * A Match and Merge algorithm code (e.g. {@code ebx-addon-mame@fuzzyFullText}, {@code MatchingPolicyTranslator}'s
	 * own wire shape) in plain words, for the decision-tree block of the review screen's expanded Matching line
	 * ({@code review.MatchPlanView}, `docs/mockups/review-match-plan.html` block 1) - a reviewer reads "resembles
	 * (fuzzy, full text)", never the raw code. Matched on the part after the last {@code @} (case-insensitive), so
	 * a bare suffix with no module prefix also resolves; an unrecognised code is shown as-is rather than guessed.
	 */
	/**
	 * tree-plain-words lot (2026-09-23): {@link #matchAlgorithmWords} without its parenthesised technical
	 * precision - "close spelling" rather than "close spelling (Jaro-Winkler)", "resembles" rather than
	 * "resembles (fuzzy, full text)" - for the tree's own narrow detail table, where the full words stay as a
	 * tooltip.
	 */
	public static String matchAlgorithmShortWords(final String pAlgorithmCode)
	{
		final String words = ProposalWording.matchAlgorithmWords(pAlgorithmCode);
		final int paren = words.indexOf(" (");
		return paren > 0 ? words.substring(0, paren) : words;
	}

	/**
	 * Step 6 plan card (2026-10-18): the same words in the reader's language ({@code review.match.algorithm.<code>}) - "identique", "orthographe proche" - the English of
	 * {@link #matchAlgorithmWords(String)} for a locale the bundle has no words for, and an unrecognised code as it is.
	 */
	public static String matchAlgorithmWords(final String pAlgorithmCode, final Locale pLocale)
	{
		final String english = ProposalWording.matchAlgorithmWords(pAlgorithmCode);
		if (pAlgorithmCode == null || pAlgorithmCode.trim().isEmpty() || english.equals(pAlgorithmCode.trim()))
		{
			return english;
		}
		final String trimmed = pAlgorithmCode.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(Locale.ROOT).replace("-", "");
		final String key = "review.match.algorithm." + suffix;
		final String words = ReviewMessages.get(pLocale, key);
		return key.equals(words) ? english : words;
	}

	/** {@link #matchAlgorithmWords(String, Locale)} without its parenthesised technical precision, like {@link #matchAlgorithmShortWords(String)}. */
	public static String matchAlgorithmShortWords(final String pAlgorithmCode, final Locale pLocale)
	{
		final String words = ProposalWording.matchAlgorithmWords(pAlgorithmCode, pLocale);
		final int paren = words.indexOf(" (");
		return paren > 0 ? words.substring(0, paren) : words;
	}

	public static String matchAlgorithmWords(final String pAlgorithmCode)
	{
		if (pAlgorithmCode == null || pAlgorithmCode.trim().isEmpty())
		{
			return "?";
		}
		final String trimmed = pAlgorithmCode.trim();
		final int at = trimmed.lastIndexOf('@');
		final String suffix = (at >= 0 ? trimmed.substring(at + 1) : trimmed).toLowerCase(Locale.ROOT);
		if ("exact".equals(suffix))
		{
			return "identical";
		}
		if ("fuzzyfulltext".equals(suffix))
		{
			return "resembles (fuzzy, full text)";
		}
		if ("levenshtein".equals(suffix))
		{
			return "close spelling";
		}
		if ("jarowinkler".equals(suffix))
		{
			return "close spelling (Jaro-Winkler)";
		}
		if ("soundex".equals(suffix) || "beider-morse".equals(suffix) || "beidermorse".equals(suffix) || "phoneticfulltext".equals(suffix))
		{
			return "sounds alike";
		}
		if ("exactfulltext".equals(suffix))
		{
			return "same words";
		}
		if ("range".equals(suffix))
		{
			return "within a range";
		}
		return trimmed;
	}

	// ------------------------------------------------------------------------------------------------- evidence

	/** "N seen, M changed, K unresolved" - a {@code MappingStep}'s own evidence shape, appended to the rationale (never replacing it) so the confidence numbers are visible without a separate column. {@code null} counters read as "never checked yet". */
	public static String mappingStepEvidence(final Long pValuesSeen, final Long pValuesChanged, final Long pValuesUnresolved)
	{
		if (pValuesSeen == null)
		{
			return "Never checked against the landing data yet.";
		}
		final StringBuilder out = new StringBuilder();
		out.append(pValuesSeen).append(" value(s) seen, ").append(pValuesChanged == null ? 0 : pValuesChanged.longValue()).append(" would change");
		if (pValuesUnresolved != null && pValuesUnresolved.longValue() > 0)
		{
			out.append(", ").append(pValuesUnresolved).append(" unresolved");
		}
		out.append('.');
		return out.toString();
	}

	/** "N seen, M failing" - a {@code QualityRule}'s PROPOSAL evidence (from the landing data at suggest time) - distinct from its EXECUTION counters ({@link #qualityRuleLastLoad}). */
	public static String qualityRuleEvidence(final Long pValuesSeen, final Long pValuesFailing)
	{
		if (pValuesSeen == null)
		{
			return "Never checked against the landing data yet.";
		}
		return pValuesSeen + " value(s) seen, " + (pValuesFailing == null ? 0 : pValuesFailing.longValue()) + " failing.";
	}

	/** The transfer-time EXECUTION counters (task C: {@code QualityRule.lastLoad*}) - distinct from, and additional to, {@link #qualityRuleEvidence}'s suggest-time figures. {@code null} means never executed at transfer (a {@code MODEL_CONSTRAINT} rule, or one not yet reached by a load). */
	public static String qualityRuleLastLoad(final Long pLastLoadChecked, final Long pLastLoadFailing)
	{
		if (pLastLoadChecked == null)
		{
			return "Not executed at transfer yet.";
		}
		return "At the last transfer: " + pLastLoadChecked + " row(s) checked, " + (pLastLoadFailing == null ? 0 : pLastLoadFailing.longValue()) + " failing.";
	}

	// ---------------------------------------------------------------------------------------------------- feeds

	/**
	 * Target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): one {@code AttributeMapping} row of a {@code
	 * TransferFeed}, already resolved to plain text by {@code ebx.ProposalReviews} (landing column's bare
	 * name, attribute's own name, and either its pipeline or its {@code transformation}/{@code argument} pair)
	 * - the input {@link #feedMappingsDetail} formats. Pure value holder, no EBX.
	 */
	public static final class FeedMapping
	{
		public final String landingColumn;
		public final String attribute;
		public final String pipelineOrTransformation;

		public FeedMapping(final String pLandingColumn, final String pAttribute, final String pPipelineOrTransformation)
		{
			this.landingColumn = pLandingColumn;
			this.attribute = pAttribute;
			this.pipelineOrTransformation = pPipelineOrTransformation;
		}
	}

	/**
	 * "N mapping(s)." - a {@code TransferFeed} line's own proposition (docs/TARGET-MAPPING.md sec 4.3): the
	 * per-mapping detail itself goes into the rationale ({@link #feedMappingsDetail}), so the proposition stays
	 * a short, glanceable count, the same role a proposition plays for every other nature (e.g. {@link
	 * #qualityRuleProposition}'s "kind (severity)").
	 */
	public static String feedProposition(final int pMappingCount)
	{
		return pMappingCount + " mapping(s).";
	}

	/**
	 * "col -> attr (pipeline); col2 -> attr2 (TRIM)" - every mapping of a feed, joined "; ", the FEED review
	 * line's own "detail" text (docs/TARGET-MAPPING.md sec 4.3), appended after the row's stored rationale like
	 * every other nature's evidence ({@link #mappingStepEvidence}, {@link #qualityRuleEvidence}). Empty (never
	 * null) when the feed has no mapping yet - a feed proposed before its mappings, or one whose mappings were
	 * all removed - so the caller's {@code appendSpaced} simply adds nothing.
	 */
	public static String feedMappingsDetail(final List<FeedMapping> pMappings)
	{
		if (pMappings == null || pMappings.isEmpty())
		{
			return "";
		}
		final StringBuilder out = new StringBuilder();
		for (final FeedMapping mapping : pMappings)
		{
			if (out.length() > 0)
			{
				out.append("; ");
			}
			out.append(mapping.landingColumn).append(" -> ").append(mapping.attribute).append(" (").append(mapping.pipelineOrTransformation).append(')');
		}
		return out.toString();
	}

	/**
	 * "Parent link: personneRef, source id: concat(ID)|suffix(#MAIN)." - a CHILD {@code TransferFeed}'s own
	 * extra detail (docs/TARGET-MAPPING.md sec 2.1): {@code parentLinkField} is always shown as-is, {@code
	 * sourceIdPipeline} falls back to a plain description of the loader's own default (sec 2.1: "the parent's
	 * source id, suffixed by the child's position among its siblings") when blank. Meaningless for a root feed
	 * - never called for one (see {@code ebx.ProposalReviews.addFeedLines}).
	 */
	public static String feedChildDetail(final String pParentLinkField, final String pSourceIdPipeline)
	{
		final String sourceId = pSourceIdPipeline == null || pSourceIdPipeline.trim().isEmpty() ? "default (parent source id + position)" : pSourceIdPipeline.trim();
		return "Parent link: " + pParentLinkField + ", source id: " + sourceId + ".";
	}

	// ---------------------------------------------------------------------------------------------------------

	/** A minimal, dependency-free {@code "key":"value"} reader, the same shape {@code quality.CleaningSteps.jsonStringField} uses for the EXECUTION side - duplicated deliberately (see the class Javadoc: wording must never share code with execution). {@code null} when the key is absent or the text does not look like a JSON object. */
	static String jsonStringField(final String pJson, final String pKey)
	{
		if (pJson == null || pJson.isEmpty())
		{
			return null;
		}
		final String needle = "\"" + pKey + "\"";
		final int keyIndex = pJson.indexOf(needle);
		if (keyIndex < 0)
		{
			return null;
		}
		final int colon = pJson.indexOf(':', keyIndex + needle.length());
		if (colon < 0)
		{
			return null;
		}
		final int firstQuote = pJson.indexOf('"', colon + 1);
		if (firstQuote < 0)
		{
			return null;
		}
		final int secondQuote = pJson.indexOf('"', firstQuote + 1);
		if (secondQuote < 0)
		{
			return null;
		}
		return pJson.substring(firstQuote + 1, secondQuote);
	}
}
