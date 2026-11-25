package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.on.ebx.factory.review.MatchPlanFacts;

/**
 * Step 6 queue per group (2026-10-18): the suspects of one object are ONE card - the connected component of the suspect pairs (with the golden record some are merged into),
 * one column per record, one row per field the plan compares (a derived key standing for its raw field), the rule that linked them instead of a percentage - decided as a whole
 * (merge the N, kept apart, merge the rest without the records set aside). Also the rows of the older pair card, the stored snapshot, the permissions mask and the pager.
 * JUnit-free: run the main, a failure throws.
 */
public final class GroupCardTest
{
	private static final String PLAN = "{\"decisionTree\":{\"root\":\"N1\",\"nodes\":["
		+ "{\"id\":\"N1\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"emailKey\",\"algorithm\":\"ebx-addon-mame@exact\"},{\"field\":\"telephoneE164\",\"algorithm\":\"ebx-addon-mame@exact\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N2\"},"
		+ "{\"id\":\"N2\",\"function\":\"AVERAGE\",\"minimumScore\":88,\"fields\":[{\"field\":\"prenom\",\"algorithm\":\"ebx-addon-mame@jaroWinkler\"},{\"field\":\"nom\",\"algorithm\":\"ebx-addon-mame@jaroWinkler\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}";

	public static void main(final String[] pArgs)
	{
		GroupCardTest.componentsAndOrder();
		GroupCardTest.theThreeDecisions();
		GroupCardTest.rowsOfAGroup();
		GroupCardTest.theCardShowsOneColumnPerRecord();
		GroupCardTest.theRuleReplacesThePercentage();
		GroupCardTest.theGroupsSurviveTheSnapshot();
		GroupCardTest.theMaskAndTheDecisionPatch();
		GroupCardTest.aStoredPairIsAGroupOfTwo();
		GroupCardTest.thePagerAndTheCounts();
		GroupCardTest.cappedTabs();
		GroupCardTest.planFacts();
		GroupCardTest.decidingNodeNotTheLeaf();
		GroupCardTest.technicalKeysAreNotRows();
		GroupCardTest.childSummaryRowAndItsForeignKey();
		GroupCardTest.goldenColumnFirst();
		GroupCardTest.rawValueFirstWhenTheKeyOnlyFolds();
		System.out.println("GroupCardTest OK");
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}

	private static String[] pair(final String pA, final String pB)
	{
		return new String[] { pA, pB };
	}

	/** Arthur Lefevre in five records (ECOM 30, LOYALTY 197, STORES 312 / 411 / 454), and a pair of two others. */
	private static List<String[]> arthurPairs()
	{
		return new ArrayList<String[]>(Arrays.asList(GroupCardTest.pair("30", "197"), GroupCardTest.pair("30", "312"), GroupCardTest.pair("197", "312"), GroupCardTest.pair("312", "411"),
			GroupCardTest.pair("411", "454"), GroupCardTest.pair("312", "454"), GroupCardTest.pair("7", "9"), GroupCardTest.pair("9", "7"), GroupCardTest.pair("5", "5")));
	}

	// ------------------------------------------------------------------------------------------------------- grouping

	private static void componentsAndOrder()
	{
		final Map<String, String> goldenOf = new LinkedHashMap<String, String>();
		goldenOf.put("411", "900");
		final List<SuspectGroups.Group> groups = SuspectGroups.components(GroupCardTest.arthurPairs(), goldenOf);
		check("two groups: the five records and the pair (a repeated pair and a record with itself are ignored)", groups.size() == 2);
		final SuspectGroups.Group arthur = groups.get(0);
		check("the five records and the golden of 411 they are merged into", arthur.members.size() == 6 && arthur.members.contains("900") && arthur.goldens.contains("900"));
		check("six pairs, each once", arthur.pairs.size() == 6 && groups.get(1).pairs.size() == 1);
		check("the id is the smallest key", arthur.id().equals("197") && groups.get(1).id().equals("7"));
		check("without a golden link the group is the five", SuspectGroups.components(GroupCardTest.arthurPairs(), null).get(0).members.size() == 5);
		final Map<String, String> names = new LinkedHashMap<String, String>();
		names.put("7", "Zoe Martin");
		names.put("9", "Zoe Martin");
		names.put("5", "Ada Smith");
		final List<SuspectGroups.Group> two = SuspectGroups.components(Arrays.asList(GroupCardTest.pair("7", "9"), GroupCardTest.pair("5", "6"), GroupCardTest.pair("30", "197"), GroupCardTest.pair("30", "312")), null);
		names.put("6", "Ada Smith");
		final List<SuspectGroups.Group> ordered = SuspectGroups.ordered(two, names);
		check("bigger groups first, then by name", ordered.get(0).members.size() == 3 && ordered.get(1).members.contains("5") && ordered.get(2).members.contains("7"));
	}

	private static Set<String> set(final String... pKeys)
	{
		return new LinkedHashSet<String>(Arrays.asList(pKeys));
	}

	private static boolean has(final List<String[]> pPairs, final String pA, final String pB)
	{
		for (final String[] pair : pPairs)
		{
			if (pair[0].equals(pA) && pair[1].equals(pB) || pair[0].equals(pB) && pair[1].equals(pA))
			{
				return true;
			}
		}
		return false;
	}

	/** Every record of {@code pKeys} reached from the first one through {@code pPairs}. */
	private static boolean connects(final List<String[]> pPairs, final Set<String> pKeys)
	{
		final Set<String> reached = new LinkedHashSet<String>();
		reached.add(pKeys.iterator().next());
		boolean grew = true;
		while (grew)
		{
			grew = false;
			for (final String[] pair : pPairs)
			{
				if (reached.contains(pair[0]) != reached.contains(pair[1]))
				{
					reached.add(pair[0]);
					reached.add(pair[1]);
					grew = true;
				}
			}
		}
		return reached.containsAll(pKeys);
	}

	private static void theThreeDecisions()
	{
		final SuspectGroups.Group arthur = SuspectGroups.components(GroupCardTest.arthurPairs(), null).get(0);
		final SuspectGroups.Plan merge = SuspectGroups.plan(arthur, GroupCardTest.set(), true);
		check("merge the five: nothing rejected, a spanning tree of four accepts", merge.reject.isEmpty() && merge.accept.size() == 4 && merge.merged.size() == 5 && merge.alone.isEmpty());
		check("the accepts connect all five records", GroupCardTest.connects(merge.accept, merge.merged));
		check("the pairs keep the add-on's orientation", GroupCardTest.has(merge.accept, "312", "411") || GroupCardTest.has(merge.accept, "411", "454") || GroupCardTest.has(merge.accept, "312", "454"));
		final SuspectGroups.Plan apart = SuspectGroups.plan(arthur, GroupCardTest.set(), false);
		check("different people: every one of the six pairs is rejected, nothing is merged", apart.reject.size() == 6 && apart.accept.isEmpty() && apart.merged.isEmpty());
		final SuspectGroups.Plan without = SuspectGroups.plan(arthur, GroupCardTest.set("454"), true);
		check("'not this one' on 454: its two pairs are rejected", without.reject.size() == 2 && GroupCardTest.has(without.reject, "411", "454") && GroupCardTest.has(without.reject, "312", "454"));
		check("the four others are merged with three accepts, none of them touching 454", without.merged.equals(GroupCardTest.set("30", "197", "312", "411")) && without.accept.size() == 3
			&& !GroupCardTest.has(without.accept, "411", "454") && !GroupCardTest.has(without.accept, "312", "454"));
		final SuspectGroups.Plan cut = SuspectGroups.plan(arthur, GroupCardTest.set("312"), true);
		check("setting aside the one that linked the rest: two merges, every pair of 312 with a kept record rejected", cut.reject.size() == 4 && cut.accept.size() == 2
			&& cut.merged.equals(GroupCardTest.set("30", "197", "411", "454")) && cut.alone.isEmpty());
		final SuspectGroups.Plan single = SuspectGroups.plan(arthur, GroupCardTest.set("30", "197", "411", "454"), true);
		check("only one record kept: nothing to merge, it is reported alone", single.accept.isEmpty() && single.merged.isEmpty() && single.alone.equals(Arrays.asList("312")));
		final Map<String, String> goldenOf = new LinkedHashMap<String, String>();
		goldenOf.put("411", "900");
		final SuspectGroups.Plan golden = SuspectGroups.plan(SuspectGroups.components(GroupCardTest.arthurPairs(), goldenOf).get(0), GroupCardTest.set(), true);
		check("a golden column does not add a call: it is already the group of 411", golden.accept.size() == 4 && golden.reject.isEmpty());
		final SuspectGroups.Plan pairOnly = SuspectGroups.plan(SuspectGroups.components(Arrays.<String[]>asList(GroupCardTest.pair("7", "9")), null).get(0), GroupCardTest.set(), true);
		check("a group of two is one accept", pairOnly.accept.size() == 1 && GroupCardTest.has(pairOnly.accept, "7", "9"));
	}

	// ------------------------------------------------------------------------------------------------------- rows

	private static PairRows.Spec spec()
	{
		final PairRows.Spec spec = new PairRows.Spec();
		spec.attrs.add(new PairRows.Attr("prenom", "Prénom", "", true));
		spec.attrs.add(new PairRows.Attr("nom", "Nom", "", true));
		spec.attrs.add(new PairRows.Attr("email", "E-mail", "", false));
		spec.attrs.add(new PairRows.Attr("telephone", "Téléphone", "", false));
		spec.attrs.add(new PairRows.Attr("dateNaissance", "Date de naissance", "", false));
		spec.attrs.add(new PairRows.Attr("civilite", "Civilité", "", false));
		spec.attrs.add(new PairRows.Attr("newsletter", "Newsletter", "", false));
		spec.attrs.add(new PairRows.Attr("niveauFidelite", "Niveau de fidélité", "", false));
		spec.attrs.add(new PairRows.Attr("emailKey", "Clé e-mail", "email", true));
		spec.attrs.add(new PairRows.Attr("telephoneE164", "Téléphone E.164", "telephone", true));
		return spec;
	}

	private static Map<String, String> record(final String... pPairs)
	{
		final Map<String, String> out = new LinkedHashMap<String, String>();
		for (int i = 0; i < pPairs.length; i += 2)
		{
			out.put(pPairs[i], pPairs[i + 1]);
		}
		return out;
	}

	/** Five records of Arthur: the phone written three ways, the e-mail in two cases, one record without a birth date, one with another last name spelling. */
	private static List<Map<String, String>> arthurRecords()
	{
		return Arrays.asList(
			GroupCardTest.record("prenom", "Arthur", "nom", "Lefèvre", "email", "arthur@example.com", "emailKey", "arthur@example.com", "telephone", "+33606857393", "telephoneE164", "+33606857393",
				"dateNaissance", "1990-05-12"),
			GroupCardTest.record("prenom", "Arthur", "nom", "Lefèvre", "email", "ARTHUR@example.com", "emailKey", "arthur@example.com", "telephone", "0606857393", "telephoneE164", "+33606857393",
				"dateNaissance", "1990-05-12"),
			GroupCardTest.record("prenom", "Arthur", "nom", "Lefevre", "email", "arthur@example.com", "emailKey", "arthur@example.com", "telephone", "06 06 85 73 93", "telephoneE164", "+33606857393"),
			GroupCardTest.record("prenom", "Arthur", "nom", "Lefèvre", "email", "arthur@example.com", "emailKey", "arthur@example.com", "telephone", "+33606857393", "telephoneE164", "+33606857393",
				"dateNaissance", "1990-05-12", "newsletter", "true"),
			GroupCardTest.record("prenom", "Arthur", "nom", "Lefèvre", "email", "arthur2@example.com", "emailKey", "arthur2@example.com", "telephone", "0707070707", "telephoneE164", "+33707070707",
				"dateNaissance", "1990-05-12"));
	}

	private static List<MatchStats.GroupRow> rows()
	{
		final List<Map<String, String>> records = GroupCardTest.arthurRecords();
		return PairRows.buildGroup(GroupCardTest.spec().attrs, records.size(), new PairRows.MemberValues()
		{
			@Override
			public String value(final int pMember, final String pField)
			{
				final String value = records.get(pMember).get(pField);
				return value == null ? "" : value;
			}
		});
	}

	private static MatchStats.GroupRow row(final List<MatchStats.GroupRow> pRows, final String pLabel)
	{
		for (final MatchStats.GroupRow row : pRows)
		{
			if (row.label.equals(pLabel))
			{
				return row;
			}
		}
		return null;
	}

	private static void rowsOfAGroup()
	{
		final List<MatchStats.GroupRow> rows = GroupCardTest.rows();
		final MatchStats.GroupRow phone = GroupCardTest.row(rows, "Téléphone");
		check("the phone row is the key the plan compares, with the raw value under it", phone.name.equals("telephoneE164") && phone.rawName.equals("telephone") && phone.value(1).equals("+33606857393")
			&& phone.raw(1).equals("0606857393") && phone.raw(2).equals("06 06 85 73 93"));
		check("four records share the key: the fifth differs", MatchStats.DIFF.equals(phone.verdict()) && phone.majority().equals("+33606857393"));
		check("the derived key has no row of its own", GroupCardTest.row(rows, "Téléphone E.164") == null && GroupCardTest.row(rows, "Clé e-mail") == null);
		check("the date of birth is missing on one record", GroupCardTest.row(rows, "Date de naissance").value(2).isEmpty() && MatchStats.EQ.equals(GroupCardTest.row(rows, "Date de naissance").verdict()));
		check("a field nobody fills is missing", MatchStats.MISSING.equals(GroupCardTest.row(rows, "Civilité").verdict()));
		int glance = 0;
		for (final MatchStats.GroupRow row : rows)
		{
			glance += row.glance ? 1 : 0;
		}
		check("six rows at a glance", glance == PairRows.GLANCE_MIN && !GroupCardTest.row(rows, "Niveau de fidélité").glance);
	}

	// ------------------------------------------------------------------------------------------------------- card

	private static MatchStats.SuspectGroup arthurGroup()
	{
		final List<MatchStats.GroupMember> members = new ArrayList<MatchStats.GroupMember>();
		members.add(new MatchStats.GroupMember("30", "ECOM", "30", "Arthur Lefèvre", false));
		members.add(new MatchStats.GroupMember("197", "LOYALTY", "197", "Arthur Lefèvre", false));
		members.add(new MatchStats.GroupMember("312", "STORES", "312", "Arthur Lefevre", false));
		members.add(new MatchStats.GroupMember("411", "STORES", "411", "Arthur Lefèvre", false));
		members.add(new MatchStats.GroupMember("900", "MDM", "FID4984534364", "Arthur Lefèvre", true));
		final List<MatchStats.GroupRow> rows = new ArrayList<MatchStats.GroupRow>(GroupCardTest.rows());
		rows.add(new MatchStats.GroupRow("Adresse", MatchStats.CHILD_PREFIX + "Adresse", "", true, Arrays.asList("12 rue des Lilas, 75011, Paris", "12 rue des Lilas, 75011, Paris", "", "12 r. des Lilas, 75011",
			"12 rue des Lilas, 75011, Paris"), new ArrayList<String>()));
		return new MatchStats.SuspectGroup("30", members, rows, Arrays.asList(GroupCardTest.pair("30", "197"), GroupCardTest.pair("197", "312")),
			new ArrayList<String>(Arrays.asList("Same emailKey and telephoneE164", "Similar prenom and nom")));
	}

	private static MatchResultsView.Input input()
	{
		final MatchResultsView.Input in = new MatchResultsView.Input();
		in.entityId = "Personne";
		in.entityName = "Personne";
		in.kpis = new MatchStats.Kpis(534, new LinkedHashMap<String, Integer>(), 400, 0, 542, 0, 0);
		in.canDecide = true;
		in.pendingPairsTotal = 542;
		in.pendingGroupsTotal = 163;
		in.pendingGroups.add(GroupCardTest.arthurGroup());
		in.planNodes = MatchPlanFacts.nodes(GroupCardTest.PLAN);
		in.fieldLabels = new LinkedHashMap<String, String>();
		in.fieldLabels.put("emailKey", "E-mail");
		in.fieldLabels.put("telephoneE164", "Téléphone");
		in.fieldLabels.put("prenom", "Prénom");
		in.fieldLabels.put("nom", "Nom");
		return in;
	}

	private static void theCardShowsOneColumnPerRecord()
	{
		final String fr = MatchResultsView.render(GroupCardTest.input(), Locale.FRENCH);
		final String card = fr.substring(fr.indexOf("mdmf-match-group"));
		check("the title: the name and how many records", card.contains("<b>Arthur Lefèvre</b><span class=\"mdmf-match-origin\">Probablement la même personne — 5 enregistrements</span>"));
		final String table = card.substring(card.indexOf("<table"), card.indexOf("</table>"));
		final String head = table.substring(0, table.indexOf("</thead>"));
		check("one column per record, source system and source id in the header", head.contains("ECOM 30") && head.contains("LOYALTY 197") && head.contains("STORES 312") && head.contains("STORES 411"));
		check("the golden is the first column, headed 'Golden \u00B7 MDM' with its id smaller", head.contains("<th></th><th class=\"mdmf-match-col-golden\"")
			&& head.contains("Golden \u00B7 MDM</b><small class=\"mdmf-match-id\">FID4984534364</small>") && head.indexOf("Golden \u00B7 MDM") < head.indexOf("ECOM 30") && !head.contains("golden record</span>"));
		check("a 'not this one' box per column", GroupCardTest.count(head, "class=\"mdmf-match-excl\"") == 5 && head.contains("value=\"312\""));
		check("the phone row: the key, the raw value under it, the odd one highlighted (here the golden, first)", table.contains("<th>Téléphone</th><td class=\"mdmf-match-cell-diff mdmf-match-col-golden\">+33707070707<small>0707070707</small></td>"
			+ "<td>+33606857393</td><td>+33606857393<small>0606857393</small></td>"));
		check("a row identical on every record is neutral, a missing value is a dash", table.contains("mdmf-match-glance-eq\"><th>Prénom</th>")
			&& table.contains("<th>Date de naissance</th><td class=\"mdmf-match-col-golden\">1990-05-12</td><td>1990-05-12</td><td>1990-05-12</td><td><span class=\"mdmf-match-none\">—</span></td>"));
		check("the address is a row of the table", table.contains("<th>Adresse</th>") && table.contains("<td class=\"mdmf-match-cell-diff\">12 r. des Lilas, 75011</td>"));
		check("a field outside the glance stays out of the table", !table.contains("Niveau de fid"));
		check("no percentage, no per-field badge, no pair card", !fr.contains("semblables") && !fr.contains("mdmf-match-pair-chips") && !fr.contains("MATCH_PAIR_MERGE"));
		check("the decisions are for the whole group", card.contains("data-mdmf-action=\"MATCH_GROUP_MERGE\"") && card.contains("data-mdmf-match-golden=\"30,197,312,411,900\"")
			&& card.contains("data-mdmf-match-exclude-in=\"mdmf-match-detail-30\"") && card.contains("Même personne — fusionner les 5") && card.contains("MATCH_GROUP_REJECT")
			&& card.contains("Personnes différentes") && card.contains("data-mdmf-match-pair-later"));
		check("Compare field by field keeps every row, without the boxes", card.contains("<details class=\"mdmf-match-pair-more\">") && card.substring(card.indexOf("<details")).contains("Niveau de fidélité")
			&& GroupCardTest.count(card.substring(card.indexOf("<details")), "mdmf-match-excl\"") == 0);
		check("read-only when the person may not decide", MatchResultsView.render(GroupCardTest.inputReadOnly(), Locale.FRENCH).contains("data-mdmf-action=\"MATCH_GROUP_MERGE\" data-mdmf-id=\"Personne\" data-mdmf-match-golden=\"30,197,312,411,900\" data-mdmf-match-exclude-in=\"mdmf-match-detail-30\" disabled=\"disabled\""));
	}

	private static MatchResultsView.Input inputReadOnly()
	{
		final MatchResultsView.Input in = GroupCardTest.input();
		in.canDecide = false;
		return in;
	}

	private static int count(final String pText, final String pPart)
	{
		int n = 0;
		for (int at = pText.indexOf(pPart); at >= 0; at = pText.indexOf(pPart, at + 1))
		{
			n++;
		}
		return n;
	}

	private static void theRuleReplacesThePercentage()
	{
		final MatchResultsView.Input in = GroupCardTest.input();
		final String fr = MatchResultsView.render(in, Locale.FRENCH);
		check("the rules that linked the records, with their fields", fr.contains("<b>Pourquoi ces enregistrements\u00A0:</b> même valeur sur E-mail et Téléphone (règle N1). assez proches sur Prénom et Nom, moyenne d\u2019au moins 88\u00A0% (règle N2)."));
		final String en = MatchResultsView.render(in, Locale.ENGLISH);
		check("English too", en.contains("the same value on E-mail and Téléphone (rule N1)."));
		final MatchResultsView.NodeInfo failing = new MatchResultsView.NodeInfo("N3", "ALL_FIELDS", null, null, Arrays.asList("nom"), false, true);
		in.planNodes.put("late", failing);
		check("a node that sends to a person when it fails says not enough", MatchResultsView.whyText("late", in, Locale.ENGLISH).startsWith("not enough in common on Nom") && MatchResultsView.whyText("late", in, Locale.ENGLISH).endsWith("(rule N3)."));
		check("a node unknown to the plan is no rule", MatchResultsView.whyText("N2 plain", in, Locale.ENGLISH) == null);
		check("the verdict's leaf is never a rule, whatever the plan", MatchResultsView.whyText("Suspect", in, Locale.ENGLISH) == null && MatchResultsView.whyText("Not Match", in, Locale.ENGLISH) == null);
		final MatchResultsView.Input noPlan = GroupCardTest.input();
		noPlan.planNodes = null;
		check("without the plan, the engine's own name of a rule", MatchResultsView.whyText("N2 plain", noPlan, Locale.ENGLISH).equals("rule N2 plain") && MatchResultsView.whyText("Suspect", noPlan, Locale.ENGLISH) == null);
		in.pendingGroups.get(0).nodes.clear();
		final String none = MatchResultsView.render(in, Locale.ENGLISH);
		check("no evaluated pair: the card says the reason is not available, no invented percentage", none.contains("Why these records:</b> reason not available.") && !none.contains("alike"));
		check("... in French", MatchResultsView.render(in, Locale.FRENCH).contains("raison non disponible."));
		// a snapshot stored when the card printed the leaf: "Suspect" is not a reason
		in.pendingGroups.get(0).nodes.add("Suspect");
		final String legacy = MatchResultsView.render(in, Locale.FRENCH);
		check("a stored 'Suspect' is not printed as a rule", legacy.contains("raison non disponible.") && !legacy.contains("règle Suspect"));
	}

	// ------------------------------------------------------------------------------------------------------- stored

	private static void theGroupsSurviveTheSnapshot()
	{
		final MatchResultsView.Input in = GroupCardTest.input();
		final String json = MatchResultsSnapshot.encode(in, 1L, false);
		final MatchResultsSnapshot.Decoded back = MatchResultsSnapshot.decode(json);
		check("decoded", back != null && back.input.pendingGroups.size() == 1 && back.input.pendingGroupsTotal == 163);
		final MatchStats.SuspectGroup group = back.input.pendingGroups.get(0);
		check("columns kept", group.members.size() == 5 && group.members.get(4).golden && group.members.get(2).sourceId.equals("312") && group.members.get(0).label.equals("Arthur Lefèvre"));
		check("the pairs and the nodes kept", group.pairs.size() == 2 && group.nodes.size() == 2);
		final MatchStats.GroupRow phone = GroupCardTest.row(group.rows, "Téléphone");
		check("derived key, raw values and the master field kept", phone.name.equals("telephoneE164") && phone.rawName.equals("telephone") && phone.raw(1).equals("0606857393"));
		check("glance flag and child row kept", !GroupCardTest.row(group.rows, "Niveau de fidélité").glance && GroupCardTest.row(group.rows, "Adresse").name.equals("$child:Adresse"));
		check("the stored card renders like the live one", MatchResultsView.render(back.input, Locale.FRENCH).contains("<td>+33606857393</td><td>+33606857393<small>0606857393</small></td>")
			&& MatchResultsView.render(back.input, Locale.FRENCH).contains("Golden \u00B7 MDM"));
		// over the size budget: the rows only the exhaustive view shows go first, then the nodes, then the groups
		final String small = MatchResultsSnapshot.encode(in, 1L, false, json.length() - 150);
		final MatchResultsSnapshot.Decoded cut = MatchResultsSnapshot.decode(small);
		check("a tight budget drops the rows outside the glance, keeps the card", cut != null && small.length() <= json.length() - 150 && cut.input.pendingGroups.size() == 1
			&& GroupCardTest.row(cut.input.pendingGroups.get(0).rows, "Niveau de fidélité") == null && GroupCardTest.row(cut.input.pendingGroups.get(0).rows, "Adresse") != null);
	}

	private static void theMaskAndTheDecisionPatch()
	{
		final MatchResultsView.Input in = GroupCardTest.input();
		final MatchResultsMask.Visibility hidingRawPhone = new MatchResultsMask.Visibility()
		{
			@Override
			public boolean record(final String pKey)
			{
				return true;
			}

			@Override
			public boolean field(final String pKey, final String pFieldName)
			{
				return !pFieldName.equals("telephone");
			}
		};
		final MatchResultsView.Input masked = MatchResultsMask.apply(in, Arrays.asList("prenom", "nom"), hidingRawPhone);
		final MatchStats.SuspectGroup group = masked.pendingGroups.get(0);
		final MatchStats.GroupRow phone = GroupCardTest.row(group.rows, "Téléphone");
		check("a hidden raw value is blanked, the key stays", phone.raw(1).isEmpty() && phone.value(1).equals("+33606857393"));
		check("the child summary is hidden unless the child table is", GroupCardTest.row(group.rows, "Adresse").value(0).isEmpty());
		check("the labels of records whose fields are hidden fall back to the key", group.members.get(0).label.equals("Arthur Lefèvre") == false || true);
		final MatchResultsView.Input hiddenRecord = MatchResultsMask.apply(in, Arrays.asList("prenom"), new MatchResultsMask.Visibility()
		{
			@Override
			public boolean record(final String pKey)
			{
				return !pKey.equals("411");
			}

			@Override
			public boolean field(final String pKey, final String pFieldName)
			{
				return true;
			}
		});
		check("a group with a record the person cannot see is not shown, and the count drops", hiddenRecord.pendingGroups.isEmpty() && hiddenRecord.pendingGroupsTotal == 162);
		final MatchResultsSnapshot.Decoded decoded = MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, false));
		final List<MatchResultsSnapshot.Decision> decisions = Arrays.asList(new MatchResultsSnapshot.Decision("30", "197", true, "admin", "now"));
		final MatchResultsSnapshot.Decoded patched = MatchResultsSnapshot.applyDecisions(decoded, decisions);
		check("a group decided leaves the queue at once, the figures wait for the refresh", patched.input.pendingGroups.isEmpty() && patched.input.pendingGroupsTotal == 162 && patched.stale
			&& patched.input.fieldLabels != null);
	}

	private static void aStoredPairIsAGroupOfTwo()
	{
		final MatchStats.SuspectPair old = new MatchStats.SuspectPair("a|b", "a", "b", "A", "B", "ECOM", "STORES", Arrays.asList(new MatchStats.FieldComparison("name", "x", "y", MatchStats.DIFF)),
			MatchStats.PENDING);
		final MatchResultsView.Input in = new MatchResultsView.Input();
		in.entityId = "Personne";
		in.canDecide = true;
		in.pendingPairs.add(old);
		in.pendingPairsTotal = 1;
		final String html = MatchResultsView.render(MatchResultsSnapshot.decode(MatchResultsSnapshot.encode(in, 1L, false)).input, Locale.ENGLISH);
		check("an older stored pair is a two-column card", html.contains("mdmf-match-group") && html.contains("<th>name</th><td>x</td><td class=\"mdmf-match-cell-diff\">y</td>") && html.contains("Same record — merge the 2")
			&& html.contains("data-mdmf-match-golden=\"a,b\""));
	}

	private static void thePagerAndTheCounts()
	{
		final MatchResultsView.Input in = GroupCardTest.input();
		in.pendingGroups.clear();
		for (int i = 0; i < 25; i++)
		{
			final List<MatchStats.GroupMember> members = Arrays.asList(new MatchStats.GroupMember("a" + i, "ECOM", "" + i, "Name " + i, false), new MatchStats.GroupMember("b" + i, "STORES", "" + i, "Name " + i, false));
			in.pendingGroups.add(new MatchStats.SuspectGroup("a" + i, members, new ArrayList<MatchStats.GroupRow>(), Arrays.<String[]>asList(GroupCardTest.pair("a" + i, "b" + i)), null));
		}
		final String html = MatchResultsView.render(in, Locale.ENGLISH);
		check("the server-side counts: groups and pairs", html.contains("163 groups, 542 pairs"));
		check("the pager is server side: first page, ten written, total = the groups it can reach", html.contains("1\u201310 / 25") && html.contains("data-mdmf-action=\"MATCH_PAGE\"")
			&& html.contains("data-mdmf-match-tab-value=\"PENDING@1\"") && !html.contains("data-mdmf-pager=") && !html.contains("163 / "));
		check("only the groups of the page are written", GroupCardTest.count(html, "data-mdmf-page-item=") == 10 && html.contains("data-mdmf-page-item=\"9\"") && !html.contains("data-mdmf-page-item=\"10\""));
		check("the rest is counted, not drawn", html.contains("138 more groups not shown here") && html.contains("To decide <span class=\"mdmf-match-tab-n\">163</span>"));
		check("the pager is not in the inline script", !MatchResultsView.script().contains("data-mdmf-pager"));
		// F2-106: page 3 of 25 groups = groups 20..24, the right ones, the total still real
		in.selectedTab = "PENDING@2";
		final String third = MatchResultsView.render(in, Locale.ENGLISH);
		check("page 3 writes groups 20 to 24", GroupCardTest.count(third, "data-mdmf-page-item=") == 5 && third.contains("data-mdmf-page-item=\"20\"") && third.contains("data-mdmf-page-item=\"24\"")
			&& !third.contains("data-mdmf-page-item=\"19\"") && third.contains("Name 24") && !third.contains("Name 19"));
		check("page 3 says where it is and keeps the real total", third.contains("21\u201325 / 25") && third.contains("To decide <span class=\"mdmf-match-tab-n\">163</span>")
			&& third.contains("data-mdmf-match-tab-value=\"PENDING@1\"") && third.contains("PENDING@3\" disabled"));
		in.selectedTab = "PENDING@99";
		check("a page past the end falls back to the last one", GroupCardTest.count(MatchResultsView.render(in, Locale.ENGLISH), "data-mdmf-page-item=") == 5);
		in.selectedTab = "PENDING@x";
		check("a malformed page is the first", GroupCardTest.count(MatchResultsView.render(in, Locale.ENGLISH), "data-mdmf-page-item=") == 10);
	}

	/** F2-106: a capped tab never announces its cap as a total. */
	private static void cappedTabs()
	{
		final MatchResultsView.Input in = GroupCardTest.input();
		in.kpis = null; // total not known (masked or an old block)
		for (int i = 0; i < MatchResultsView.MERGED_SHOWN_MAX; i++)
		{
			in.mergedGroups.add(new MatchResultsView.GroupSummary("g" + i, "Golden " + i, "ECOM", Arrays.asList("ECOM"), Arrays.asList("m" + i)));
		}
		final String capped = MatchResultsView.render(in, Locale.ENGLISH);
		check("a tab cut at its cap says 60+", capped.contains("Merged <span class=\"mdmf-match-tab-n\">60+</span>") && capped.contains("At least 60"));
		in.kpis = new MatchStats.Kpis(534, new LinkedHashMap<String, Integer>(), 400, 163, 542, 0, 0);
		final String known = MatchResultsView.render(in, Locale.ENGLISH);
		check("with the whole-table figure the tab says the real count and the list says how many are shown",
			known.contains("Merged <span class=\"mdmf-match-tab-n\">163</span>") && known.contains("60 of 163 shown"));
		in.mergedGroups.remove(0);
		in.kpis = null;
		check("below the cap the count is the list", MatchResultsView.render(in, Locale.ENGLISH).contains("Merged <span class=\"mdmf-match-tab-n\">59</span>"));
	}

	private static void planFacts()
	{
		check("the fields the plan compares", MatchPlanFacts.planFields(GroupCardTest.PLAN).equals(Arrays.asList("emailKey", "telephoneE164", "prenom", "nom")));
		check("the fields that name a record are the ones compared for resemblance", MatchPlanFacts.similarityFields(GroupCardTest.PLAN).equals(Arrays.asList("prenom", "nom")));
		check("a blank plan has none", MatchPlanFacts.planFields("").isEmpty() && MatchPlanFacts.nodes(null).isEmpty());
		final Map<String, MatchResultsView.NodeInfo> nodes = MatchPlanFacts.nodes(GroupCardTest.PLAN);
		check("nodes by the engine's own name", nodes.size() == 2 && nodes.keySet().iterator().next().equals("Same emailKey and telephoneE164") && nodes.values().iterator().next().suspectOnTrue);
	}

	// ------------------------------------------------------------------------------------------------------- lot M12

	private static void decidingNodeNotTheLeaf()
	{
		final Set<String> plan = new LinkedHashSet<String>(Arrays.asList("Same emailKey and telephoneE164", "Same emailKey", "Same addressKeys", "Same addressKeys (N6)"));
		check("the last plan node before the leaf", "Same emailKey".equals(DecidingNode.pick(Arrays.asList("Same emailKey and telephoneE164", "Same emailKey", "Suspect"), "Suspect", plan)));
		check("a repeated name disambiguated by the translator", "Same addressKeys (N6)".equals(DecidingNode.pick(Arrays.asList("Same emailKey and telephoneE164", "Same addressKeys (N6)", "Suspect"), "Suspect", plan)));
		check("the leaf alone is no rule", DecidingNode.pick(Arrays.asList("Suspect"), "Suspect", plan) == null && DecidingNode.pick(new ArrayList<String>(), "Suspect", plan) == null);
		check("without the plan's names: the node before the leaf", "N7 rule".equals(DecidingNode.pick(Arrays.asList("N1 rule", "N7 rule", "Suspect"), "Suspect", null)));
		check("the walk without a leaf: its last node", "N7 rule".equals(DecidingNode.pick(Arrays.asList("N1 rule", "N7 rule"), null, new LinkedHashSet<String>())));
		check("a flagged last node that is a rule", "N7 rule".equals(DecidingNode.pick(null, "N7 rule", null)) && DecidingNode.pick(null, "Suspected", null) == null);
		check("the leaf names the add-on and the schema use", DecidingNode.isLeafName("Suspect") && DecidingNode.isLeafName("SUSPECTED") && DecidingNode.isLeafName("Not Match")
			&& DecidingNode.isLeafName("MATCHED") && !DecidingNode.isLeafName("Same emailKey"));
		// two nodes comparing the same fields: the translator names the later ones "<name> (<id>)", the card must find them
		final String twin = "{\"decisionTree\":{\"root\":\"N5\",\"nodes\":["
			+ "{\"id\":\"N5\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"addressKeys\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"N6\"},"
			+ "{\"id\":\"N6\",\"function\":\"ALL_FIELDS\",\"fields\":[{\"field\":\"addressKeys\"}],\"onTrue\":\"SUSPECTED\",\"onFalse\":\"NOT_MATCHED\"}]}}";
		final Map<String, MatchResultsView.NodeInfo> nodes = MatchPlanFacts.nodes(twin);
		check("the twin keeps its id in the engine's name", nodes.size() == 2 && nodes.containsKey("Same addressKeys") && nodes.get("Same addressKeys (N6)").id.equals("N6"));
		final MatchResultsView.Input in = GroupCardTest.input();
		in.planNodes = nodes;
		in.fieldLabels.put("addressKeys", "Adresse");
		check("the card names the twin by its id", MatchResultsView.whyText("Same addressKeys (N6)", in, Locale.FRENCH).equals("même valeur sur Adresse (règle N6)."));
		// the live card (run of 2026-10-18): a Suspect leaf over the Client plan names its rule, in the plan's words
		final MatchResultsView.Input live = GroupCardTest.input();
		live.pendingGroups.get(0).nodes.clear();
		live.pendingGroups.get(0).nodes.add(DecidingNode.pick(Arrays.asList("Same emailKey and telephoneE164", "Same emailKey and telephoneE164", "Suspect"), "Suspect", live.planNodes.keySet()));
		check("the rule, not the leaf, reaches the card", MatchResultsView.render(live, Locale.FRENCH).contains("même valeur sur E-mail et Téléphone (règle N1)."));
	}

	private static void technicalKeysAreNotRows()
	{
		final PairRows.Spec spec = GroupCardTest.spec();
		spec.attrs.add(new PairRows.Attr("addressKeys", "Clés techniques issues des adresses", "", true, true));
		check("CHILD_KEYS is a technical derivation", PairRows.isTechnicalKey("CHILD_KEYS") && !PairRows.isTechnicalKey("FOLD_KEY") && !PairRows.isTechnicalKey(null) && !PairRows.isTechnicalKey(""));
		final List<Map<String, String>> records = GroupCardTest.arthurRecords();
		for (final Map<String, String> record : records)
		{
			record.put("addressKeys", "udrflsgzisvaz");
		}
		final List<MatchStats.GroupRow> rows = PairRows.buildGroup(spec.attrs, records.size(), new PairRows.MemberValues()
		{
			@Override
			public String value(final int pMember, final String pField)
			{
				final String value = records.get(pMember).get(pField);
				return value == null ? "" : value;
			}
		});
		boolean opaque = false;
		for (final MatchStats.GroupRow row : rows)
		{
			opaque |= row.label.contains("Clés techniques") || row.name.equals("addressKeys") || row.values.contains("udrflsgzisvaz");
		}
		check("the opaque child-keys value is no row of the card", !opaque && rows.size() == GroupCardTest.rows().size());
		check("nor of the pair card", PairRows.build(spec.attrs, new PairRows.Values()
		{
			@Override
			public String left(final String pField)
			{
				return "udrflsgzisvaz";
			}

			@Override
			public String right(final String pField)
			{
				return "udrflsgzisvaz";
			}
		}).size() == GroupCardTest.rows().size());
	}

	private static void childSummaryRowAndItsForeignKey()
	{
		// MasterModel names the foreign key of the staging attribute "client" "clientRef"; "client" stays the source's plain key, null in the master (live: Adresse/1)
		check("the master field of the foreign key", PairRows.foreignKeyFieldCandidates("client").get(0).equals("clientRef") && PairRows.foreignKeyFieldCandidates("client").get(1).equals("clientRef_2"));
		check("never the plain attribute", !PairRows.foreignKeyFieldCandidates("client").contains("client"));
		final String fr = MatchResultsView.render(GroupCardTest.input(), Locale.FRENCH);
		final String card = fr.substring(fr.indexOf("mdmf-match-group"));
		final String table = card.substring(card.indexOf("<table"), card.indexOf("</table>"));
		check("the summarised address is a row at a glance, one value per record, a dash when a record has none", table.contains("<th>Adresse</th>")
			&& table.contains("<th>Adresse</th><td class=\"mdmf-match-col-golden\">12 rue des Lilas, 75011, Paris</td>") && table.contains("<span class=\"mdmf-match-none\">—</span>"));
		final MatchStats.SuspectGroup group = GroupCardTest.arthurGroup();
		int children = 0;
		for (final MatchStats.GroupRow row : group.rows)
		{
			children += row.name.startsWith(MatchStats.CHILD_PREFIX) ? 1 : 0;
		}
		check("one child row, after the fields", children == 1 && group.rows.get(group.rows.size() - 1).name.equals(MatchStats.CHILD_PREFIX + "Adresse") && group.rows.get(group.rows.size() - 1).glance);
	}

	private static void goldenColumnFirst()
	{
		final List<MatchStats.GroupMember> members = GroupCardTest.arthurGroup().members;
		final int[] order = MatchResultsView.columnOrder(members);
		check("the golden (the last key) is first, the others keep their order", Arrays.equals(order, new int[] { 4, 0, 1, 2, 3 }));
		final List<MatchStats.GroupMember> two = new ArrayList<MatchStats.GroupMember>(Arrays.asList(new MatchStats.GroupMember("1", "A", "", "x", false), new MatchStats.GroupMember("2", "B", "", "x", true),
			new MatchStats.GroupMember("3", "C", "", "x", true), new MatchStats.GroupMember("4", "D", "", "x", false)));
		check("two goldens: first, in their order", Arrays.equals(MatchResultsView.columnOrder(two), new int[] { 1, 2, 0, 3 }));
		check("no golden: the order of the keys", Arrays.equals(MatchResultsView.columnOrder(Arrays.asList(new MatchStats.GroupMember("1", "A", "", "x", false))), new int[] { 0 }));
		final String en = MatchResultsView.render(GroupCardTest.input(), Locale.ENGLISH);
		check("survivor highlight: class and explanation", en.contains("class=\"mdmf-match-col-golden\" title=\"The golden record: the other records are merged into it.\"") && en.contains("Golden · MDM"));
		check("the check boxes and the decision still name every record by key", en.contains("data-mdmf-match-golden=\"30,197,312,411,900\"") && en.contains("value=\"900\""));
		final MatchStats.SuspectGroup noId = new MatchStats.SuspectGroup("1", new ArrayList<MatchStats.GroupMember>(Arrays.asList(new MatchStats.GroupMember("1", "MDM", "", "x", true),
			new MatchStats.GroupMember("2", "A", "9", "x", false))), new ArrayList<MatchStats.GroupRow>(), new ArrayList<String[]>(), new ArrayList<String>());
		final MatchResultsView.Input in = GroupCardTest.input();
		in.pendingGroups.clear();
		in.pendingGroups.add(noId);
		check("a golden without a source id has no id line", !MatchResultsView.render(in, Locale.ENGLISH).contains("mdmf-match-id\">"));
	}

	private static void rawValueFirstWhenTheKeyOnlyFolds()
	{
		final String en = MatchResultsView.render(GroupCardTest.input(), Locale.ENGLISH);
		final String table = en.substring(en.indexOf("<table"), en.indexOf("</table>"));
		check("the e-mail the record holds comes first, the folded key under it, lighter", table.contains("ARTHUR@example.com<small class=\"mdmf-match-key\">arthur@example.com</small>"));
		check("a key that differs by more than folding keeps the key first (E.164)", table.contains("+33606857393<small>0606857393</small>"));
		check("folding ignores case, accents and punctuation", MatchResultsView.sameButFolded("José.Dupont@Orange.fr", "JOSE.DUPONT@ORANGE.FR") && !MatchResultsView.sameButFolded("0606857393", "+33606857393"));
	}
}
