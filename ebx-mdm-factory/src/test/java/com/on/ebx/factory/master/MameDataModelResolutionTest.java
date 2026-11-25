package com.on.ebx.factory.master;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class MameDataModelResolutionTest
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	public static void main(final String[] pArgs) throws Exception
	{
		MameDataModelResolutionTest.foundConfig_proceeds();
		MameDataModelResolutionTest.configNotFound_proceeds();
		MameDataModelResolutionTest.tableNotFound_reportedAsSuch();
		MameDataModelResolutionTest.unrecognisedCode_refuses();
		MameDataModelResolutionTest.nullResponse_refuses();
		MameDataModelResolutionTest.ambiguousWithCandidatesArray_exactlyOneMatches_usesIt();
		MameDataModelResolutionTest.ambiguousCandidateArray_neverMatchesLongerOrPrefixedName();
		MameDataModelResolutionTest.ambiguousWithObjectCandidates_readsNameField();
		MameDataModelResolutionTest.ambiguousNoCandidatesArray_scansMessageText();
		MameDataModelResolutionTest.ambiguousNoMatchingCandidate_refuses();
		MameDataModelResolutionTest.ambiguousSeveralMatchingCandidates_refuses();
		MameDataModelResolutionTest.ambiguousUnparseable_refuses();
		MameDataModelResolutionTest.ambiguousLiveContactPayload_resolvesToTheFullUrn();
		MameDataModelResolutionTest.ambiguousUrnCandidateWithTrailingSentencePunctuation_stripped();
		MameDataModelResolutionTest.ambiguousLiveContactPayloadWithNextStep_deduplicatesAcrossFields_resolvesToTheFullUrn();
		System.out.println("MameDataModelResolutionTest OK");
	}

	private static void foundConfig_proceeds() throws Exception
	{
		final MameDataModelResolution.Decision decision = MameDataModelResolution
			.resolve(MameDataModelResolutionTest.parse("{\"success\":true,\"data\":{}}"), "Companies_Master");
		MameDataModelResolutionTest.check("action PROCEED", decision.action == MameDataModelResolution.Action.PROCEED);
		MameDataModelResolutionTest.check("no dataModel", decision.dataModel == null);
	}

	private static void configNotFound_proceeds() throws Exception
	{
		final MameDataModelResolution.Decision decision = MameDataModelResolution
			.resolve(MameDataModelResolutionTest.parse("{\"success\":false,\"code\":\"CONFIG_NOT_FOUND\",\"message\":\"No matching/merge configuration for table /root/Company\"}"),
				"Companies_Master");
		MameDataModelResolutionTest.check("action PROCEED", decision.action == MameDataModelResolution.Action.PROCEED);
	}

	/** The exact live measurement, 2026-09-21: passing the model name broke a call that a bare table path resolves fine. */
	private static void tableNotFound_reportedAsSuch() throws Exception
	{
		final MameDataModelResolution.Decision decision = MameDataModelResolution
			.resolve(MameDataModelResolutionTest.parse("{\"success\":false,\"code\":\"TABLE_NOT_FOUND\",\"message\":\"Table not found: /root/Company\"}"),
				"Companies_Master");
		MameDataModelResolutionTest.check("action TABLE_NOT_FOUND", decision.action == MameDataModelResolution.Action.TABLE_NOT_FOUND);
	}

	private static void unrecognisedCode_refuses() throws Exception
	{
		final MameDataModelResolution.Decision decision = MameDataModelResolution
			.resolve(MameDataModelResolutionTest.parse("{\"success\":false,\"code\":\"MODEL_NOT_FOUND\",\"message\":\"boom\"}"), "Companies_Master");
		MameDataModelResolutionTest.check("action REFUSE", decision.action == MameDataModelResolution.Action.REFUSE);
		MameDataModelResolutionTest.check("message carries the raw code", decision.message.contains("MODEL_NOT_FOUND"));
	}

	private static void nullResponse_refuses() throws Exception
	{
		final MameDataModelResolution.Decision decision = MameDataModelResolution.resolve(null, "Companies_Master");
		MameDataModelResolutionTest.check("action REFUSE", decision.action == MameDataModelResolution.Action.REFUSE);
	}

	private static void ambiguousWithCandidatesArray_exactlyOneMatches_usesIt() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"Table found under multiple data models\","
			+ "\"candidates\":[\"Companies_Master\",\"Companies_Reference\"],\"nextStep\":\"retry with dataModel set to one of the candidates\"}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action USE_DATA_MODEL", decision.action == MameDataModelResolution.Action.USE_DATA_MODEL);
		MameDataModelResolutionTest.check("resolved to Companies_Master", "Companies_Master".equals(decision.dataModel));
	}

	/** Companies_Master must not match Companies_Master_V2 (suffix) or XCompanies_Master (prefix) - a whole-token match only. */
	private static void ambiguousCandidateArray_neverMatchesLongerOrPrefixedName() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"ambiguous\","
			+ "\"candidates\":[\"Companies_Master_V2\",\"XCompanies_Master\"]}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action REFUSE (no whole-token match)", decision.action == MameDataModelResolution.Action.REFUSE);
	}

	private static void ambiguousWithObjectCandidates_readsNameField() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"ambiguous\","
			+ "\"candidates\":[{\"name\":\"Companies_Master\"},{\"name\":\"Companies_Reference\"}]}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action USE_DATA_MODEL", decision.action == MameDataModelResolution.Action.USE_DATA_MODEL);
		MameDataModelResolutionTest.check("resolved to Companies_Master", "Companies_Master".equals(decision.dataModel));
	}

	private static void ambiguousNoCandidatesArray_scansMessageText() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\","
			+ "\"message\":\"Table /root/Company is declared under data models Companies_Master and Companies_MasterV2\","
			+ "\"nextStep\":\"retry with dataModel set to one of them\"}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action USE_DATA_MODEL", decision.action == MameDataModelResolution.Action.USE_DATA_MODEL);
		MameDataModelResolutionTest.check("resolved to Companies_Master, not Companies_MasterV2", "Companies_Master".equals(decision.dataModel));
	}

	private static void ambiguousNoMatchingCandidate_refuses() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"ambiguous\",\"candidates\":[\"Other_Model\",\"Yet_Another\"]}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action REFUSE", decision.action == MameDataModelResolution.Action.REFUSE);
		MameDataModelResolutionTest.check("message lists the raw candidates", decision.message.contains("Other_Model"));
	}

	private static void ambiguousSeveralMatchingCandidates_refuses() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"ambiguous\","
			+ "\"candidates\":[\"Companies_Master\",\"Companies_Master\"]}";
		// two identical entries: still two matches, not one - a defensive edge case, not expected live
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action REFUSE (several matches)", decision.action == MameDataModelResolution.Action.REFUSE);
	}

	private static void ambiguousUnparseable_refuses() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"no identifiers in here at all\"}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action REFUSE (nothing to match)", decision.action == MameDataModelResolution.Action.REFUSE);
	}

	/**
	 * The EXACT live payload, measured by the coordinator, 2026-09-23 (Proposal review -&gt; Contact -&gt;
	 * Simulate, real FastTrack instance): {@code message} = "declared by more than one data model:
	 * urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd,
	 * urn:ebx:publication:Companies_Master" - no candidates array. Before the fix, the bare-identifier-token
	 * scan stripped the {@code urn:ebx:publication:} prefix and resolved to the BARE {@code Companies_Master}
	 * - which itself answers {@code TABLE_NOT_FOUND} when passed back as {@code dataModel} (CLAUDE.md &sect;8),
	 * reproducing the exact bug this whole class exists to avoid. The module URN candidate ("EBX FastTrack -
	 * TIBCO EBX Presales environment...") must NOT match (it does not contain "Companies_Master" as a whole
	 * token at all) - only the publication URN does.
	 */
	private static void ambiguousLiveContactPayload_resolvesToTheFullUrn() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\",\"message\":\"declared by more than one data model: "
			+ "urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd, "
			+ "urn:ebx:publication:Companies_Master\"}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action USE_DATA_MODEL", decision.action == MameDataModelResolution.Action.USE_DATA_MODEL);
		MameDataModelResolutionTest.check("resolved to the FULL urn, never the bare model name",
			"urn:ebx:publication:Companies_Master".equals(decision.dataModel));
	}

	/** A candidate at the very end of the message (no trailing comma) can still carry sentence punctuation the URN itself never has - stripped rather than left in {@link MameDataModelResolution.Decision#dataModel}. */
	private static void ambiguousUrnCandidateWithTrailingSentencePunctuation_stripped() throws Exception
	{
		final String json = "{\"success\":false,\"code\":\"AMBIGUOUS_TABLE\","
			+ "\"message\":\"ambiguous (see urn:ebx:publication:Companies_Master).\"}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action USE_DATA_MODEL", decision.action == MameDataModelResolution.Action.USE_DATA_MODEL);
		MameDataModelResolutionTest.check("trailing ')' and '.' stripped from the urn", "urn:ebx:publication:Companies_Master".equals(decision.dataModel));
	}

	/**
	 * The EXACT live payload, both fields, measured by the coordinator, 2026-09-23 ~03:05 (deployed main
	 * {@code 7605a78}, Proposal review -&gt; Contact -&gt; Simulate, real FastTrack instance) - the SECOND live
	 * bug this class hit, minutes after the first fix landed: {@code message} ends with {@code
	 * urn:ebx:publication:Companies_Master} and {@code nextStep} begins with "Pass the optional 'dataModel' to
	 * disambiguate; candidates: urn:ebx:module:...", joined by this class' OWN candidate-extraction code with a
	 * plain space (not a comma) - the previous revision's {@code [^,]+} span then ran straight through that
	 * boundary and consumed all of {@code nextStep}'s own leading prose before stopping at the first comma
	 * INSIDE {@code nextStep}'s own candidate list, producing a mangled second "candidate" that still happened
	 * to match {@code Companies_Master} too - two matches where there must be exactly one, so {@link
	 * MameDataModelResolution#resolve} refused outright (live text: "2 candidate data models contain
	 * 'Companies_Master' as a whole token"). {@code message} and {@code nextStep} must now be scanned entirely
	 * SEPARATELY - see {@link MameDataModelResolution#extractUrnCandidates}'s own Javadoc for the full account.
	 */
	private static void ambiguousLiveContactPayloadWithNextStep_deduplicatesAcrossFields_resolvesToTheFullUrn() throws Exception
	{
		final String json = "{\"code\":\"AMBIGUOUS_TABLE\","
			+ "\"message\":\"Table /root/Contact is declared by more than one data model: "
			+ "urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd, "
			+ "urn:ebx:publication:Companies_Master\","
			+ "\"nextStep\":\"Pass the optional 'dataModel' to disambiguate; candidates: "
			+ "urn:ebx:module:EBX FastTrack - TIBCO EBX Presales environment:/WEB-INF/ebx/schemas/Companies.xsd, "
			+ "urn:ebx:publication:Companies_Master\","
			+ "\"success\":false}";
		final MameDataModelResolution.Decision decision = MameDataModelResolutionTest.parseAndResolve(json, "Companies_Master");
		MameDataModelResolutionTest.check("action USE_DATA_MODEL (exactly one match, not two)",
			decision.action == MameDataModelResolution.Action.USE_DATA_MODEL);
		MameDataModelResolutionTest.check("resolved to the clean, full urn - never a candidate mangled with trailing prose",
			"urn:ebx:publication:Companies_Master".equals(decision.dataModel));
		MameDataModelResolutionTest.check("candidates deduplicated across the two fields down to exactly the two real ones, not four",
			decision.candidates.size() == 2);
	}

	private static MameDataModelResolution.Decision parseAndResolve(final String pJson, final String pPublicationName) throws Exception
	{
		return MameDataModelResolution.resolve(MameDataModelResolutionTest.parse(pJson), pPublicationName);
	}

	private static JsonNode parse(final String pJson) throws Exception
	{
		return MameDataModelResolutionTest.MAPPER.readTree(pJson);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
