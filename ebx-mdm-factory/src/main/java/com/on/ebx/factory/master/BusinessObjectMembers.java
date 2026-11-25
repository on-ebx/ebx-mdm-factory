package com.on.ebx.factory.master;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Lot M4 (2026-10-18, {@code docs/MATCHING.md} section 59): what the add-on requires of a business object for a matching run on its MAIN table to work, and what the Factory writes so
 * that it does. Pure: Jackson only, no EBX class, no MCP call ({@code ebx.MameBusinessObjects} applies the decisions).
 * <p>
 * <b>What the add-on requires (read in the live answers of project {@code E2EMASTER20261017222813} and in the bytecode of {@code ebx-addon-6.3.0.5-RC3}, 2026-10-18).</b>
 * <ol>
 * <li><b>The table configuration of every table of the object carries the business object</b> ({@code MatchingTableConfig.getBusinessObject}). Only
 * {@code generate_matching_policy} / {@code generate_merge_policy} with {@code businessObject} give it: they create the business object record and, through
 * {@code BusinessObjectUtil.createTableConfigurationIfNotExist}, one configuration per table of the object (main AND members, without any policy) - and that call SKIPS a table that
 * already has a configuration. {@code set_trusted_sources} and {@code set_table_settings} have no {@code businessObject} argument: called first on the main table they create a
 * configuration WITHOUT the business object, and the next {@code generate_matching_policy} with a {@code businessObjectField} answers "businessObjectField names a field on a member table
 * of a business object, and the matching-table configuration of /root/Personne carries no business object" (project {@code E2EMASTER20261017222251}, {@code get_business_object_config}:
 * {@code businessObject.id = null}, the main table listed as a member with empty policies). No tool repairs or deletes such a configuration ({@code delete_matching_config} and
 * {@code apply_matching_config} reject every business object table, {@code MameConfigWriter.rejectIfBusinessObjectTable}): the Factory therefore never creates it ({@link Binding}).</li>
 * <li><b>A run on the main table needs a default matching policy on EVERY member table</b> ("The default matching policy is not defined" for {@code /root/AdresseClient}, project
 * {@code ...222813}, whose member configuration existed, was ENABLED with its metadataset, and held no policy), and a merge policy ({@code docs/MATCHING.md} section 33, CLAUDE.md
 * section 8 of the knowledge base).</li>
 * <li>A matching run ON a member table ends in a NullPointerException ({@code product-bugs/2026-10-17-mame-auto-merge-table-without-business-object-npe.md}): a member is configured but
 * never run alone ({@link ChildEntityMatching#MEMBER_MERGE_DEFECT_PRESENT}).</li>
 * </ol>
 * <p>
 * <b>The member policy</b> ({@link #inertMatchingPolicy}, {@link #inertMergePolicy}): a policy that exists to satisfy the checks above and does nothing. Matching: one pre-clustering field and one
 * comparison on the technical {@code /id} (two distinct records never share it), the true branch a {@code SUSPECTED} leaf (never {@code MATCHED}: nothing can merge on its own), the false
 * branch {@code NOT_MATCHED} (the add-on refuses a node whose two branches reach the same leaf); no execution mode is sent (a member refuses them and reads back 0 = manual). Merge: the
 * MODE OF THE MAIN TABLE's merge policy, identity survivors on the technical fields, no golden source, no constant.
 * <p>
 * <b>Lot M8 (2026-10-18 02:57, project {@code E2EMASTER20261018005227}, {@code PipelineStep} 2311, {@code docs/MATCHING.md} section 62):</b> the run of the main table is refused when a
 * member's merge policy does not carry the same mode as the main table's: {@code matchRecords refused (invalid parameter): The 'Run match' service cannot be executed. Please check if merge
 * policies are configured for all related tables. Ensure the merge policies use the same 'Mode'.} - read live with {@code get_matching_config}: {@code /root/Adresse} mode {@code DISABLED}
 * (the inert policy of lot M4), {@code /root/Client} mode {@code ONLY_DUPLICATES}. The member's mode is therefore read off the main table's merge policy in the SAME
 * {@code get_business_object_config} answer ({@link #mainMergeMode}), never a constant; a member whose merge policy already stands in another mode is aligned by a
 * {@code set_merge_mode} patch ({@link Writes#alignMergeModes}).
 */
public final class BusinessObjectMembers
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** The suffix of a Simulate preview policy code ({@code ebx.MatchingPreviewPolicy}); such a policy does not count as a real policy of a stray configuration. */
	public static final String PREVIEW_SUFFIX = "__preview";
	/** The one field of an inert policy: the technical primary key of every master table. */
	public static final String INERT_FIELD = "/id";
	/**
	 * The merge mode of a main table's merge policy when nothing says otherwise: the Factory's own default for the main table ({@code MatchingPolicyTranslator.buildMergePolicy},
	 * {@code hints.path("mode").asText("ONLY_DUPLICATES")}, {@code MatchPlanRules} merge hints, {@code MatchPlanLlmPass.Session.MERGE_MODE}). Used for a member only when the main table's
	 * own mode cannot be read ({@link #mainMergeMode} null and no desired policy at hand).
	 */
	public static final String MERGE_MODE_DEFAULT = "ONLY_DUPLICATES";

	private static final String MOST_COMPLETED = "mame.addon.reference.data.recordSelectionPolicy.mostCompleted.label";

	private BusinessObjectMembers()
	{
	}

	// ------------------------------------------------------------------------------------------------ is the object bound?

	/** What {@code get_business_object_config} says about the business object of a table, before the Factory writes anything. */
	public enum Binding
	{
		/** The object has no configuration yet (CONFIG_NOT_FOUND): the first {@code generate_matching_policy} with {@code businessObject} binds it. */
		CLEAN,
		/** The business object record exists ({@code businessObject.id} is set): its tables carry it. */
		BOUND,
		/** A configuration exists for a table of the object but the object itself has no record ({@code id = null}): written without {@code businessObject}, unrepairable by any tool. */
		STRAY,
		/** Not a declared business object (BUSINESS_OBJECT_NOT_FOUND, anything else): the caller does not use the business object tools. */
		NOT_DECLARED
	}

	/** @param pBusinessObjectConfigAnswer the raw answer of {@code get_business_object_config} (its {@code ToolResult.json}), null reads as {@link Binding#NOT_DECLARED} */
	public static Binding binding(final JsonNode pBusinessObjectConfigAnswer)
	{
		if (pBusinessObjectConfigAnswer == null)
		{
			return Binding.NOT_DECLARED;
		}
		if (!MameAnswer.success(pBusinessObjectConfigAnswer, false))
		{
			final String code = MameAnswer.code(pBusinessObjectConfigAnswer);
			return "CONFIG_NOT_FOUND".equals(code) || "TABLE_NOT_FOUND".equals(code) ? Binding.CLEAN : Binding.NOT_DECLARED;
		}
		final JsonNode id = MameAnswer.field(pBusinessObjectConfigAnswer, "businessObject").path("id");
		if (!id.isMissingNode() && !id.isNull() && !id.asText("").trim().isEmpty())
		{
			return Binding.BOUND;
		}
		final JsonNode members = MameAnswer.field(pBusinessObjectConfigAnswer, "members");
		return members.isArray() && members.size() > 0 ? Binding.STRAY : Binding.CLEAN;
	}

	/** The refusal for a {@link Binding#STRAY} configuration - said before anything is written, with what to do (nothing in the MCP tools can). */
	public static String strayRefusal(final String pMainTable, final String pProjectModel)
	{
		return "The Match and Merge configuration of " + pMainTable + " (model " + pProjectModel + ") was created without its business object: get_business_object_config reads the object with no id although the table"
			+ " has a configuration. It happens when set_trusted_sources or set_table_settings (they have no businessObject argument) ran on the table before the first generate_matching_policy with the"
			+ " business object. The accepted plan compares a field of a member table, and generate_matching_policy refuses such a field (\"the matching-table configuration of " + pMainTable
			+ " carries no business object\"); no tool"
			+ " repairs it (delete_matching_config and apply_matching_config are refused on a business object table). Delete the table configuration of " + pMainTable
			+ " in Administration > Match and Merge, or rebuild the master zone of the project, then configure again.";
	}

	// ------------------------------------------------------------------------------------------------ what each member holds

	/** One table of the business object, as {@code get_business_object_config} lists it (a member is listed only when it has a configuration). */
	public static final class Member
	{
		public final String table;
		public final List<String> matchingCodes;
		public final List<String> mergeCodes;
		/** {@code tableSettings.defaultMatchingPolicy}, null when blank. */
		public final String defaultMatchingCode;
		/** Lot M8: the {@code mode} of each merge policy, by code; a policy whose mode the answer does not give is absent (its mode is unknown, never assumed). */
		public final Map<String, String> mergeModes;

		public Member(final String pTable, final List<String> pMatchingCodes, final List<String> pMergeCodes, final String pDefaultMatchingCode)
		{
			this(pTable, pMatchingCodes, pMergeCodes, pDefaultMatchingCode, new LinkedHashMap<String, String>());
		}

		public Member(final String pTable, final List<String> pMatchingCodes, final List<String> pMergeCodes, final String pDefaultMatchingCode, final Map<String, String> pMergeModes)
		{
			this.table = pTable;
			this.matchingCodes = Collections.unmodifiableList(pMatchingCodes);
			this.mergeCodes = Collections.unmodifiableList(pMergeCodes);
			this.defaultMatchingCode = pDefaultMatchingCode;
			this.mergeModes = Collections.unmodifiableMap(pMergeModes);
		}

		/** The bare entity name of the table ({@code /root/Adresse} gives {@code Adresse}). */
		public String entity()
		{
			return BusinessObjectMembers.entityOf(this.table);
		}

		/** A default matching policy that really exists on the table - the add-on's condition for a run of the object. */
		public boolean hasDefaultMatching()
		{
			return this.defaultMatchingCode != null && this.matchingCodes.contains(this.defaultMatchingCode);
		}
	}

	/** {@code /root/Adresse} gives {@code Adresse}; anything else is returned as it is. */
	public static String entityOf(final String pTablePath)
	{
		return pTablePath != null && pTablePath.startsWith("/root/") ? pTablePath.substring("/root/".length()) : pTablePath;
	}

	/** The tables of the object other than its main table, with their policies, from the raw {@code get_business_object_config} answer. Empty when the answer lists none. */
	public static List<Member> membersOf(final JsonNode pBusinessObjectConfigAnswer, final String pMainTable)
	{
		final List<Member> out = new ArrayList<Member>();
		final JsonNode members = MameAnswer.field(pBusinessObjectConfigAnswer, "members");
		if (!members.isArray())
		{
			return out;
		}
		for (final JsonNode member : members)
		{
			final String table = member.path("table").asText(null);
			if (table == null || table.equals(pMainTable))
			{
				continue;
			}
			final String defaultCode = member.path("tableSettings").path("defaultMatchingPolicy").asText(null);
			out.add(new Member(table, codes(member.path("matchingPolicies"), "processCode"), codes(member.path("mergePolicies"), "code"),
				defaultCode == null || defaultCode.trim().isEmpty() ? null : defaultCode.trim(), BusinessObjectMembers.mergeModes(member.path("mergePolicies"))));
		}
		return out;
	}

	/** {@code code -> mode} of the merge policies that carry both; the order of the answer. */
	private static Map<String, String> mergeModes(final JsonNode pMergePolicies)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		if (pMergePolicies.isArray())
		{
			for (final JsonNode policy : pMergePolicies)
			{
				final String code = policy.path("code").asText(null);
				final String mode = policy.path("mode").asText(null);
				if (code != null && !code.isEmpty() && mode != null && !mode.trim().isEmpty())
				{
					out.put(code, mode.trim());
				}
			}
		}
		return out;
	}

	/**
	 * Lot M8: the merge mode of the MAIN table of the object, read in the {@code get_business_object_config} answer (the main table is listed among the members, with the same
	 * {@code MameConfig} shape as {@code get_matching_config}): the mode of the merge policy whose code is the main table's entity name (the Factory's convention), else the mode of the
	 * first merge policy that is not a Simulate preview, else the first with a mode. Null when the answer gives none - the caller then takes the policy it is about to write or
	 * {@link #MERGE_MODE_DEFAULT}; never a guess about the add-on.
	 */
	public static String mainMergeMode(final JsonNode pBusinessObjectConfigAnswer, final String pMainTable)
	{
		final JsonNode members = MameAnswer.field(pBusinessObjectConfigAnswer, "members");
		if (!members.isArray())
		{
			return null;
		}
		for (final JsonNode member : members)
		{
			if (!pMainTable.equals(member.path("table").asText(null)))
			{
				continue;
			}
			final Map<String, String> modes = BusinessObjectMembers.mergeModes(member.path("mergePolicies"));
			final String own = modes.get(BusinessObjectMembers.entityOf(pMainTable));
			if (own != null)
			{
				return own;
			}
			for (final Map.Entry<String, String> entry : modes.entrySet())
			{
				if (!entry.getKey().endsWith(BusinessObjectMembers.PREVIEW_SUFFIX))
				{
					return entry.getValue();
				}
			}
			return modes.isEmpty() ? null : modes.values().iterator().next();
		}
		return null;
	}

	/** The mode the members must carry: the main table's own ({@link #mainMergeMode}), else the one the Factory is writing on it ({@code pDesiredMainMode}), else {@link #MERGE_MODE_DEFAULT}. */
	public static String memberMergeMode(final String pMainMergeMode, final String pDesiredMainMode)
	{
		if (pMainMergeMode != null && !pMainMergeMode.trim().isEmpty())
		{
			return pMainMergeMode.trim();
		}
		if (pDesiredMainMode != null && !pDesiredMainMode.trim().isEmpty())
		{
			return pDesiredMainMode.trim();
		}
		return BusinessObjectMembers.MERGE_MODE_DEFAULT;
	}

	private static List<String> codes(final JsonNode pPolicies, final String pField)
	{
		final List<String> out = new ArrayList<String>();
		if (pPolicies.isArray())
		{
			for (final JsonNode policy : pPolicies)
			{
				final String code = policy.path(pField).asText(null);
				if (code != null && !code.isEmpty())
				{
					out.add(code);
				}
			}
		}
		return out;
	}

	/** What must be written on one member so that a run of the object is accepted. */
	public static final class Writes
	{
		/** No matching policy at all: write the inert one. */
		public final boolean matching;
		/** No merge policy at all: write the inert one. */
		public final boolean merge;
		/** The member has matching policies but none is its default (or the default names no policy): set the default. */
		public final boolean defaultPolicy;
		/**
		 * Lot M8: the codes of the member's merge policies whose mode is known and differs from the main table's ({@link #mergeMode}): each is patched with {@code set_merge_mode}. The
		 * add-on refuses the run of the object otherwise ("Ensure the merge policies use the same 'Mode'").
		 */
		public final List<String> alignMergeModes;
		/** The mode every merge policy of the member must carry (the main table's). */
		public final String mergeMode;

		Writes(final boolean pMatching, final boolean pMerge, final boolean pDefaultPolicy, final List<String> pAlignMergeModes, final String pMergeMode)
		{
			this.matching = pMatching;
			this.merge = pMerge;
			this.defaultPolicy = pDefaultPolicy;
			this.alignMergeModes = Collections.unmodifiableList(pAlignMergeModes);
			this.mergeMode = pMergeMode;
		}

		public boolean nothing()
		{
			return !this.matching && !this.merge && !this.defaultPolicy && this.alignMergeModes.isEmpty();
		}
	}

	/**
	 * The writes a member needs so that a run of the object is accepted, {@code pMergeMode} being the main table's merge mode ({@link #memberMergeMode}). A member that already holds
	 * policies is never rewritten (an earlier pass configured its own plan: left as it is, {@code ChildEntityMatching#keptAsIsNote}): only the missing parts are added, so a run never
	 * meets "the default matching policy is not defined" - and a merge policy that stands in another mode than the main table's has its mode aligned (lot M8), nothing else of it.
	 */
	public static Writes writesFor(final Member pMember, final String pMergeMode)
	{
		final boolean matching = pMember.matchingCodes.isEmpty();
		final boolean merge = pMember.mergeCodes.isEmpty();
		final boolean defaultPolicy = !matching && !pMember.hasDefaultMatching();
		final List<String> align = new ArrayList<String>();
		for (final Map.Entry<String, String> entry : pMember.mergeModes.entrySet())
		{
			if (!entry.getValue().equals(pMergeMode))
			{
				align.add(entry.getKey());
			}
		}
		return new Writes(matching, merge, defaultPolicy, align, pMergeMode);
	}

	/** Every member that needs a write, in the answer's order. */
	public static List<Member> membersToWrite(final List<Member> pMembers, final String pMergeMode)
	{
		final List<Member> out = new ArrayList<Member>();
		for (final Member member : pMembers)
		{
			if (!BusinessObjectMembers.writesFor(member, pMergeMode).nothing())
			{
				out.add(member);
			}
		}
		return out;
	}

	/** The code of the real matching policy a member without a default should get as default: the first that is not a Simulate preview, else the first. */
	public static String defaultCandidate(final Member pMember)
	{
		for (final String code : pMember.matchingCodes)
		{
			if (!code.endsWith(BusinessObjectMembers.PREVIEW_SUFFIX))
			{
				return code;
			}
		}
		return pMember.matchingCodes.isEmpty() ? null : pMember.matchingCodes.get(0);
	}

	// ------------------------------------------------------------------------------------------------ the inert policies

	/** The inert matching policy of a member (class Javadoc): code = the entity name; no execution mode (a member refuses them and reads back manual). */
	public static ObjectNode inertMatchingPolicy(final String pEntityName)
	{
		final ObjectNode policy = BusinessObjectMembers.MAPPER.createObjectNode();
		policy.put("processCode", pEntityName);
		policy.put("active", true);
		policy.put("matchAgainstMergedRecords", false);
		policy.put("executePurge", "MANUAL");
		policy.put("showMatchType", false);
		final ArrayNode fields = policy.putArray("fields");
		final ObjectNode field = fields.addObject();
		field.put("field", BusinessObjectMembers.INERT_FIELD);
		field.put("searchStrategy", "@default");
		field.put("weight", 1);
		field.put("bothNullValueHandling", "NOT_MATCH");
		field.put("oneNullValueHandling", "NOT_MATCH");
		final ObjectNode tree = policy.putObject("decisionTree");
		tree.put("name", "Never: same technical id");
		tree.put("function", "All fields match");
		tree.putArray("params");
		final ObjectNode treeField = tree.putArray("fields").addObject();
		treeField.put("field", BusinessObjectMembers.INERT_FIELD);
		treeField.put("algorithm", "ebx-addon-mame@exact");
		treeField.put("bothNullValueHandling", "NOT_MATCH");
		treeField.put("oneNullValueHandling", "NOT_MATCH");
		final ObjectNode param = treeField.putArray("params").addObject();
		param.put("name", "minimumScore");
		param.put("value", "100");
		final ObjectNode trueNode = tree.putObject("trueNode");
		trueNode.put("name", "SUSPECTED");
		trueNode.putNull("function");
		final ObjectNode falseNode = tree.putObject("falseNode");
		falseNode.put("name", "NOT_MATCHED");
		falseNode.putNull("function");
		return policy;
	}

	/**
	 * The inert merge policy of a member in {@code pMode} - the MAIN table's mode ({@link #memberMergeMode}): identity survivors on the technical fields ({@code no merge}, {@code max}),
	 * no {@code autoCreateNewGoldenSource}, no {@code constant}, no trusted-source function. In {@code ONLY_DUPLICATES} on a business-object table the add-on "merges" every singleton in
	 * place and applies the survivors to it (CLAUDE.md section 8 trap (4), {@code docs/MATCHING.md} section 62): with these survivors and the default {@code longest} over one record, the
	 * record is rewritten with its own values - nothing changes; a {@code constant} or a golden source here would rewrite every member row at each run.
	 */
	public static ObjectNode inertMergePolicy(final String pEntityName, final String pMode)
	{
		final ObjectNode policy = BusinessObjectMembers.MAPPER.createObjectNode();
		policy.put("code", pEntityName);
		policy.put("mode", pMode);
		policy.put("useForManualMerge", true);
		policy.put("applyPermission", false);
		policy.putArray("recordSelectionFunctionChain").addObject().put("function", BusinessObjectMembers.MOST_COMPLETED);
		policy.putArray("defaultFieldMergeFunctionChain").addObject().put("function", "longest");
		final ArrayNode survivors = policy.putArray("survivors");
		for (final String field : new String[] { "sourceSystem", "sourceId", "_loadId" })
		{
			BusinessObjectMembers.survivor(survivors, field, "no merge");
		}
		BusinessObjectMembers.survivor(survivors, "_receivedAt", "max");
		return policy;
	}

	private static void survivor(final ArrayNode pSurvivors, final String pField, final String pFunction)
	{
		final ObjectNode survivor = pSurvivors.addObject();
		survivor.put("field", "/" + pField);
		survivor.putArray("mergeFunctionChain").addObject().put("function", pFunction);
	}

	// ------------------------------------------------------------------------------------------------ wording

	/**
	 * One sentence for the configure job: what was written on a member and why it never runs by itself. The mode is named as "the mode of the main table" so that a reader knows where
	 * it comes from; an aligned policy says which mode it left (lot M8).
	 */
	public static String writtenSentence(final String pEntity, final String pParent, final Writes pWrites, final Map<String, String> pModesBefore)
	{
		final List<String> parts = new ArrayList<String>();
		if (pWrites.matching)
		{
			parts.add("an inert matching policy (one comparison on the technical id, no execution mode)");
		}
		if (pWrites.merge)
		{
			parts.add("an inert merge policy (mode " + pWrites.mergeMode + ", the mode of " + pParent + "'s merge policy, identity survivors)");
		}
		if (pWrites.defaultPolicy)
		{
			parts.add("its default matching policy");
		}
		final List<String> aligned = new ArrayList<String>();
		for (final String code : pWrites.alignMergeModes)
		{
			final String before = pModesBefore == null ? null : pModesBefore.get(code);
			aligned.add(code + (before == null ? "" : " (was " + before + ")"));
		}
		if (!aligned.isEmpty())
		{
			parts.add("the mode of its merge polic" + (aligned.size() == 1 ? "y " : "ies ") + String.join(", ", aligned) + " set to " + pWrites.mergeMode
				+ ", the mode of " + pParent + "'s merge policy (the add-on refuses a run of the object while a member's merge policy is in another mode)");
		}
		return pEntity + " (member of the business object of " + pParent + "): " + String.join(" and ", parts)
			+ " written because the add-on refuses a run of the object while a member has no default matching policy and no merge policy; "
			+ pEntity + " is never run on its own, it follows " + pParent + ".";
	}

	/** The refusal when a member still lacks what the run needs after the writes (re-read), {@code pMergeMode} being the main table's merge mode. */
	public static String stillMissingRefusal(final String pMainTable, final List<Member> pStillMissing, final String pMergeMode)
	{
		final List<String> names = new ArrayList<String>();
		for (final Member member : pStillMissing)
		{
			final Writes writes = BusinessObjectMembers.writesFor(member, pMergeMode);
			final List<String> modes = new ArrayList<String>();
			for (final String code : writes.alignMergeModes)
			{
				modes.add(code + " in mode " + member.mergeModes.get(code));
			}
			names.add(member.table + (writes.matching ? " (no matching policy)" : writes.defaultPolicy ? " (no default matching policy)" : "") + (writes.merge ? " (no merge policy)" : "")
				+ (modes.isEmpty() ? "" : " (merge policy " + String.join(", ", modes) + " where " + pMainTable + " is in mode " + pMergeMode + ")"));
		}
		return "After the member policies were written, get_business_object_config still shows what a run of " + pMainTable + " refuses: " + String.join("; ", names)
			+ ". Configure again; if it repeats, read the member's configuration in Administration > Match and Merge.";
	}
}
