package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * Decides which {@code dataModel} argument (if any at all) a caller of the {@code match-merge} MCP server
 * should pass on every following call for one table, from a parsed {@code get_matching_config} answer -
 * pure: no EBX class, unit-tested without a running instance.
 * <p>
 * <b>The bug this class fixes, measured live by the coordinator, 2026-09-21 (`docs/MATCHING.md` &sect;2.2,
 * this entry).</b> {@code ebx.MatchingConfigurator}/{@code ebx.MatchPlanLlmSuggester} used to pass the
 * master model's NAME (e.g. {@code "Companies_Master"}) as {@code dataModel} on every call. Twelve seconds
 * after {@code master/generate} declared the MAME add-on on that exact model and republished it,
 * {@code get_matching_config(table="/root/Company")} (no {@code dataModel}) answered {@code
 * CONFIG_NOT_FOUND} - the correct, ready state, proving the add-on already knew the table with no
 * restart at all - while {@code get_matching_config(table="/root/Company", dataModel="Companies_Master")}
 * answered {@code TABLE_NOT_FOUND} for the exact same table at the exact same instant. The tool's own
 * contract for {@code dataModel} (quoted in `docs/MATCHING.md` &sect;3.1): <i>"the data model
 * (publication) that owns the table, e.g. the SchemaLocation string shown in a config's identity ...
 * supply ONLY to disambiguate a table path configured under more than one data model; omit it otherwise
 * (the table is resolved automatically)."</i> A plain publication name is never that SchemaLocation
 * string, so passing one always breaks resolution instead of narrowing it. The fix: never pass the
 * publication name as {@code dataModel} - omit it by default, and only ever set it from a candidate this
 * class itself extracted out of a real {@code AMBIGUOUS_TABLE} answer.
 * <p>
 * <b>{@code AMBIGUOUS_TABLE}'s own JSON shape, measured live 2026-09-23</b> (Proposal review -&gt; Contact -&gt;
 * Simulate, real FastTrack instance) - no array field of candidates, a plain {@code message}: {@code "declared
 * by more than one data model: urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/
 * schemas/Companies.xsd, urn:ebx:publication:Companies_Master"}. {@link #resolve} is still a best-effort
 * parser (no array-field shape has been seen live even now - only ever built/tested against a hand-guessed
 * fixture): (1) any array-valued field of the response whose elements are plain strings, or objects carrying a
 * {@code name}/{@code dataModel}/{@code publication}/{@code schemaLocation}/{@code value} text field, is read
 * as the candidate list; (2) failing that, every {@code urn:ebx:module:...}/{@code urn:ebx:publication:...}
 * span in {@code message}/{@code nextStep}, delimited by the next comma (the message's OWN list separator) or
 * the end of the text, is read as a candidate - the shape actually measured live, see {@link
 * #extractUrnCandidates}'s own Javadoc for the exact bug an EARLIER version of this class shipped instead (it
 * tokenized on every {@code :}/{@code /}/{@code .} and kept only the bare tail, e.g. {@code Companies_Master}
 * with the {@code urn:ebx:publication:} prefix stripped off - which then answered {@code TABLE_NOT_FOUND} when
 * passed back as {@code dataModel}, CLAUDE.md &sect;8's own documented finding, reproduced by this class'
 * "fix" for the exact bug it exists to avoid); (3) failing THAT too, every identifier-shaped bare token
 * (contains {@code _}, or an internal uppercase letter - typical of an EBX publication name, unlike an English
 * sentence word) is pulled out by regex - kept only for a hypothetical message format that never wraps a
 * candidate in a URN at all, never observed live either way. Either way, a candidate is kept only if it
 * contains the caller's own publication name as a WHOLE token (case-sensitive: {@code Companies_Master} must
 * not match {@code Companies_Master_V2} or {@code XCompanies_Master}) - exactly one surviving candidate is
 * used as {@code dataModel} on this call and every following call of the same pass; zero or several is refused
 * rather than guessed.
 * <p>
 * <b>2026-09-23 14:20 - "resolve once from a read, reuse the result for every write" turned out unsound
 * (`docs/MATCHING.md` &sect;28).</b> Measured live on the PROPOSED {@code Contact} plan: once a configuration
 * already existed on an ambiguous table, {@code get_matching_config(table="/root/Contact")} - no {@code
 * dataModel} at all - answered {@code success:true} (only ONE of the two candidate data models actually carries
 * a configuration, so the add-on stops considering the table ambiguous for a READ once it can serve an answer
 * from the one that has it), while {@code apply_matching_config} on the SAME table, SAME instant, still answered
 * {@code AMBIGUOUS_TABLE} with both candidates - a write is not disambiguated by "only one candidate happens to
 * have data" the way a read apparently is. A caller that resolves {@code dataModel} once from its own first
 * {@code get_matching_config} and threads {@code null} through every following call (this class' own
 * {@link Decision#action} {@link Action#PROCEED}) therefore breaks on exactly this shape - the read succeeds
 * unscoped, every write after it still needs the real value. Separately, {@code
 * get_matching_activation_status(table="/root/Company", dataModel="urn:ebx:publication:Companies_Master")} on a
 * table that was NOT ambiguous at all answered its ordinary {@code ENABLED} - passing the real publication URN
 * is always accepted, ambiguous table or not. Together these two measurements are why {@link #publicationUrn}
 * exists and why {@code ebx.MameCalls} now sends it on EVERY match-merge call for a project's master table from
 * the very first one, rather than omitting it until a read says otherwise - this class' own {@link #resolve} is
 * kept unchanged as the fallback safety net for the rare case where that guessed URN itself still comes back
 * {@code AMBIGUOUS_TABLE} (a wrong project/table pairing, most likely).
 */
public final class MameDataModelResolution
{
	/** The prefix a DMA publication's {@code SchemaLocation} carries, as the add-on itself renders it in an {@code AMBIGUOUS_TABLE} candidate list (class Javadoc, &sect;2.2.2) - {@link #publicationUrn} builds the exact value {@code ebx.MameCalls} now sends as {@code dataModel} up front on every call. */
	public static final String PUBLICATION_URN_PREFIX = "urn:ebx:publication:";
	private static final Pattern IDENTIFIER = Pattern.compile("[A-Za-z][A-Za-z0-9_]*");
	/**
	 * A full {@code urn:ebx:module:...}/{@code urn:ebx:publication:...} span, applied to ONE field's text at a
	 * time (never a {@code message}+{@code nextStep} concatenation - see {@link #extractUrnCandidates}'s own
	 * Javadoc, 2026-09-23 22:xx correction, for why concatenating them let a span bleed from one field into the
	 * other). {@code urn:ebx:publication:...} takes a tight identifier/filename charset - a publication code
	 * never contains a space, so this alone is enough to stop cleanly with no lookahead needed. {@code
	 * urn:ebx:module:...} can legitimately contain spaces in its own trailing segment (a module name may,
	 * CLAUDE.md &sect;9/&sect;12) - reluctantly matched up to whichever comes first: a comma, a semicolon, a
	 * closing bracket, the literal sequence {@code " Pass "} (this tool's own "Pass the optional 'dataModel'
	 * to disambiguate" phrasing bleeding in from a badly-bounded scan, guarded against explicitly), a newline,
	 * or the end of the field.
	 */
	private static final Pattern URN_CANDIDATE = Pattern
		.compile("urn:ebx:module:.+?(?=,|;|\\]|\\z| Pass |\\r|\\n)|urn:ebx:publication:[A-Za-z0-9_.\\-]+", Pattern.DOTALL);

	private MameDataModelResolution()
	{
	}

	/**
	 * {@code "urn:ebx:publication:" + pPublicationName} - the value {@code ebx.MameCalls} now sends as {@code
	 * dataModel} on EVERY match-merge call for a project's master table, from the very first one (class
	 * Javadoc, 2026-09-23 14:20 entry) - never the bare publication name itself (that mistake is the ORIGINAL
	 * bug this whole class exists to avoid, &sect;2.2.1). Pure and total: every caller already has a real
	 * project model name in hand ({@code master.MasterNames#modelName}), so no null-handling is needed.
	 */
	public static String publicationUrn(final String pPublicationName)
	{
		return MameDataModelResolution.PUBLICATION_URN_PREFIX + pPublicationName;
	}

	public enum Action
	{
		/** No ambiguity (a found config, or {@code CONFIG_NOT_FOUND}): omit {@code dataModel} on every following call. */
		PROCEED,
		/** Exactly one {@code AMBIGUOUS_TABLE} candidate names the caller's own publication: use {@link Decision#dataModel} from here on. */
		USE_DATA_MODEL,
		/** {@code get_matching_config} answered {@code TABLE_NOT_FOUND} - the caller decides the message (does the model declare the add-on?). */
		TABLE_NOT_FOUND,
		/** Ambiguous with zero or several matching candidates, or an unrecognised/unparseable answer: refuse outright, never guess. */
		REFUSE
	}

	public static final class Decision
	{
		public final Action action;
		/** Non-null only for {@link Action#USE_DATA_MODEL}. */
		public final String dataModel;
		/** Non-null only for {@link Action#REFUSE}. */
		public final String message;
		/** The raw candidate strings an {@code AMBIGUOUS_TABLE} answer yielded - empty for every other action. */
		public final List<String> candidates;

		private Decision(final Action pAction, final String pDataModel, final String pMessage, final List<String> pCandidates)
		{
			this.action = pAction;
			this.dataModel = pDataModel;
			this.message = pMessage;
			this.candidates = pCandidates;
		}
	}

	/**
	 * @param pResponse the parsed {@code get_matching_config} answer (its top-level {@code success}/
	 *        {@code code}/{@code message}/{@code nextStep} fields) - {@code null} is treated as unparseable
	 * @param pPublicationName the master model's publication name - the value this codebase used to
	 *        (wrongly) pass as {@code dataModel} before this fix; used only to filter an
	 *        {@code AMBIGUOUS_TABLE} answer's candidates down to the one this factory project actually owns
	 */
	public static Decision resolve(final JsonNode pResponse, final String pPublicationName)
	{
		if (pResponse == null)
		{
			return MameDataModelResolution.refuse("get_matching_config returned no parseable JSON.", Collections.<String>emptyList());
		}
		if (pResponse.path("success").asBoolean(false))
		{
			return MameDataModelResolution.proceed();
		}
		final String code = pResponse.path("code").asText(null);
		if ("CONFIG_NOT_FOUND".equals(code))
		{
			return MameDataModelResolution.proceed();
		}
		if ("TABLE_NOT_FOUND".equals(code))
		{
			return new Decision(Action.TABLE_NOT_FOUND, null, null, Collections.<String>emptyList());
		}
		if ("AMBIGUOUS_TABLE".equals(code))
		{
			return MameDataModelResolution.resolveAmbiguous(pResponse, pPublicationName);
		}
		return MameDataModelResolution.refuse("get_matching_config answered " + code + ": " + pResponse.path("message").asText(""),
			Collections.<String>emptyList());
	}

	private static Decision resolveAmbiguous(final JsonNode pResponse, final String pPublicationName)
	{
		final List<String> candidates = MameDataModelResolution.extractCandidates(pResponse);
		final List<String> matching = new ArrayList<String>();
		for (final String candidate : candidates)
		{
			if (MameDataModelResolution.containsWholeToken(candidate, pPublicationName))
			{
				matching.add(candidate);
			}
		}
		if (matching.size() == 1)
		{
			return new Decision(Action.USE_DATA_MODEL, matching.get(0), null, candidates);
		}
		final String message = matching.isEmpty()
			? "get_matching_config answered AMBIGUOUS_TABLE and none of the candidate data models contain '" + pPublicationName
				+ "' as a whole token - the table path is declared under several data models: " + candidates
			: "get_matching_config answered AMBIGUOUS_TABLE and " + matching.size() + " candidate data models contain '" + pPublicationName
				+ "' as a whole token (expected exactly one) - the table path is declared under several data models: " + candidates;
		return MameDataModelResolution.refuse(message, candidates);
	}

	private static Decision proceed()
	{
		return new Decision(Action.PROCEED, null, null, Collections.<String>emptyList());
	}

	private static Decision refuse(final String pMessage, final List<String> pCandidates)
	{
		return new Decision(Action.REFUSE, null, pMessage, pCandidates);
	}

	/**
	 * See the class Javadoc: an explicit array of candidates first, then a tolerant text scan of {@code
	 * message}/{@code nextStep} - URN-shaped spans first (the REAL live shape - see {@link
	 * #extractUrnCandidates}'s own Javadoc for the bug this fixes), a bare-identifier-token scan as the last
	 * resort (kept for a hypothetical message format that never wraps a candidate in a URN at all - never
	 * observed live, but the ORIGINAL guess this class shipped with, still exercised by {@code
	 * MameDataModelResolutionTest#ambiguousNoCandidatesArray_scansMessageText}).
	 */
	private static List<String> extractCandidates(final JsonNode pResponse)
	{
		final List<String> fromArrays = MameDataModelResolution.extractFromArrayFields(pResponse);
		if (!fromArrays.isEmpty())
		{
			return fromArrays;
		}
		final String messageText = pResponse.path("message").asText("");
		final StringBuilder nextStepText = new StringBuilder();
		MameDataModelResolution.appendTextual(pResponse.path("nextStep"), nextStepText);
		final List<String> fromUrns = MameDataModelResolution.extractUrnCandidates(messageText, nextStepText.toString());
		if (!fromUrns.isEmpty())
		{
			return fromUrns;
		}
		// the old bare-token fallback (never seen live carrying a real candidate - kept for a hypothetical
		// non-urn message format, class Javadoc) has no cross-field-bleed risk to guard against: IDENTIFIER
		// tokens are bounded by any non-word character, and the space this concatenation inserts between the
		// two fields is exactly such a boundary - so, unlike extractUrnCandidates, this one may still scan the
		// fields concatenated.
		final String concatenated = messageText + ' ' + nextStepText;
		final List<String> out = new ArrayList<String>();
		final Set<String> seen = new LinkedHashSet<String>();
		final Matcher matcher = MameDataModelResolution.IDENTIFIER.matcher(concatenated);
		while (matcher.find())
		{
			final String token = matcher.group();
			if (MameDataModelResolution.looksLikePublicationName(token) && seen.add(token))
			{
				out.add(token);
			}
		}
		return out;
	}

	/**
	 * <b>The first bug this method fixed, measured live by the coordinator, 2026-09-23 03:0x (Proposal review
	 * -&gt; Contact -&gt; Simulate).</b> A bare-identifier-token scan (still below, as a last resort) tokenizes
	 * on every {@code :}/{@code /}/{@code .}, stripping the {@code urn:ebx:publication:} prefix clean off and
	 * keeping only the bare {@code Companies_Master} tail as the "candidate" - {@code containsWholeToken} then
	 * matches it trivially and the resolved {@code dataModel} ends up being that BARE string, which answers
	 * {@code TABLE_NOT_FOUND} when passed back (CLAUDE.md &sect;8) - the exact bug the whole class exists to
	 * avoid, reproduced one level down. Fixed by extracting the WHOLE {@code urn:ebx:module:...}/{@code
	 * urn:ebx:publication:...} span instead of a bare tail.
	 * <p>
	 * <b>The SECOND bug, measured live minutes later, same session, 2026-09-23 ~03:05 - the first fix's own
	 * candidate-extraction still ran on the CONCATENATION of {@code message} and {@code nextStep}</b> (one
	 * {@code StringBuilder}, joined by a single space): the live answer's {@code message} ends with {@code
	 * "...urn:ebx:publication:Companies_Master"} and {@code nextStep} begins with {@code "Pass the optional
	 * 'dataModel' to disambiguate; candidates: urn:ebx:module:..."} - joined by a space, not a comma, so the
	 * PREVIOUS extraction pattern's {@code [^,]+} (a plain "up to the next comma" span) ran straight through
	 * the field boundary and did not stop until the FIRST comma found INSIDE {@code nextStep}'s own candidate
	 * list - producing a mangled "candidate" reading {@code "urn:ebx:publication:Companies_Master Pass the
	 * optional 'dataModel' to disambiguate; candidates: urn:ebx:module:...xsd"}, which still happened to
	 * CONTAIN {@code Companies_Master} as a whole token (so it "matched" too) - giving TWO matching candidates
	 * (that mangled one, plus the clean one extracted from {@code nextStep}'s own trailing, unambiguous {@code
	 * urn:ebx:publication:Companies_Master}) where there should only ever be one, so {@link #resolve} refused
	 * outright ("2 candidate data models contain 'Companies_Master' as a whole token"). Fixed two ways at
	 * once: (1) {@link #extractCandidates} now calls this method with {@code message} and {@code nextStep} as
	 * TWO SEPARATE strings, never concatenated - a span can no longer bleed from one field into the other; (2)
	 * {@link #URN_CANDIDATE}'s own module alternative now also stops at a plain comma/semicolon/bracket/
	 * newline/the literal {@code " Pass "} sequence, not only at the next {@code urn:ebx:} occurrence - so even
	 * a single field whose OWN text happens to run a candidate into trailing prose stays bounded. Result on the
	 * exact live payload (both fixes needed, either alone is insufficient): {@code
	 * ["urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd",
	 * "urn:ebx:publication:Companies_Master"]} (the SAME two candidates from each field, deduplicated to one
	 * copy of each) - {@code containsWholeToken} matches exactly the second one, {@link Decision#dataModel}
	 * resolves to it, and passing it back to {@code get_matching_config} answers {@code CONFIG_NOT_FOUND}
	 * (measured live by the coordinator).
	 */
	private static List<String> extractUrnCandidates(final String pMessageText, final String pNextStepText)
	{
		final List<String> out = new ArrayList<String>();
		final Set<String> seen = new LinkedHashSet<String>();
		MameDataModelResolution.appendUrnCandidatesFrom(pMessageText, seen, out);
		MameDataModelResolution.appendUrnCandidatesFrom(pNextStepText, seen, out);
		return out;
	}

	/** One field's own text, scanned in isolation - see {@link #extractUrnCandidates}'s own Javadoc for why this must never be a caller-side concatenation of two fields. */
	private static void appendUrnCandidatesFrom(final String pFieldText, final Set<String> pSeen, final List<String> pOut)
	{
		if (pFieldText == null || pFieldText.isEmpty())
		{
			return;
		}
		final Matcher matcher = MameDataModelResolution.URN_CANDIDATE.matcher(pFieldText);
		while (matcher.find())
		{
			final String candidate = MameDataModelResolution.stripTrailingPunctuation(matcher.group().trim());
			if (!candidate.isEmpty() && pSeen.add(candidate))
			{
				pOut.add(candidate);
			}
		}
	}

	/** A candidate extracted up to a comma or the end of the message may still carry trailing sentence punctuation (a closing parenthesis, a period) that is never part of the real URN - stripped here rather than left for {@link #containsWholeToken} to trip over. */
	private static String stripTrailingPunctuation(final String pCandidate)
	{
		int end = pCandidate.length();
		while (end > 0 && ".)\"' \t".indexOf(pCandidate.charAt(end - 1)) >= 0)
		{
			end--;
		}
		return pCandidate.substring(0, end);
	}

	private static List<String> extractFromArrayFields(final JsonNode pResponse)
	{
		final List<String> out = new ArrayList<String>();
		final Iterator<String> fieldNames = pResponse.fieldNames();
		while (fieldNames.hasNext())
		{
			final JsonNode value = pResponse.path(fieldNames.next());
			if (!value.isArray() || value.size() == 0)
			{
				continue;
			}
			final List<String> fromThisArray = new ArrayList<String>();
			boolean everyElementUsable = true;
			for (final JsonNode element : value)
			{
				final String text = MameDataModelResolution.textOf(element);
				if (text == null)
				{
					everyElementUsable = false;
					break;
				}
				fromThisArray.add(text);
			}
			if (everyElementUsable && !fromThisArray.isEmpty())
			{
				out.addAll(fromThisArray);
			}
		}
		return out;
	}

	private static String textOf(final JsonNode pElement)
	{
		if (pElement.isTextual())
		{
			return pElement.asText();
		}
		if (pElement.isObject())
		{
			for (final String key : new String[] { "dataModel", "name", "publication", "schemaLocation", "value" })
			{
				final JsonNode candidate = pElement.path(key);
				if (candidate.isTextual())
				{
					return candidate.asText();
				}
			}
		}
		return null;
	}

	private static void appendTextual(final JsonNode pNode, final StringBuilder pOut)
	{
		if (pNode.isTextual())
		{
			pOut.append(pNode.asText()).append(' ');
		}
		else if (pNode.isArray())
		{
			for (final JsonNode element : pNode)
			{
				MameDataModelResolution.appendTextual(element, pOut);
			}
		}
	}

	/** Typical of an EBX publication name ({@code Companies_Master}) - unlike a plain English message word. */
	private static boolean looksLikePublicationName(final String pToken)
	{
		if (pToken.length() < 4)
		{
			return false;
		}
		if (pToken.indexOf('_') >= 0)
		{
			return true;
		}
		for (int i = 1; i < pToken.length(); i++)
		{
			if (Character.isUpperCase(pToken.charAt(i)))
			{
				return true;
			}
		}
		return false;
	}

	/** Case-sensitive, word-boundary containment: {@code Companies_Master} must not match {@code Companies_Master_V2} or {@code XCompanies_Master}. */
	private static boolean containsWholeToken(final String pCandidate, final String pToken)
	{
		if (pCandidate == null || pToken == null || pToken.isEmpty())
		{
			return false;
		}
		final Pattern pattern = Pattern.compile("(?<![A-Za-z0-9_])" + Pattern.quote(pToken) + "(?![A-Za-z0-9_])");
		return pattern.matcher(pCandidate).find();
	}
}
