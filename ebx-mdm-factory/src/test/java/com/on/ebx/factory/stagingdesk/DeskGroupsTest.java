package com.on.ebx.factory.stagingdesk;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.on.ebx.factory.guard.Guard;
import com.on.ebx.factory.mappingmatrix.MatrixCompute;
import com.on.ebx.factory.mappingmatrix.MatrixModel;
import com.on.ebx.factory.review.ReviewLine;
import com.on.ebx.factory.wizard.WizardSnapshot;

/**
 * Validation desk lot L1 (docs/STAGING-DESK.md): the regrouping of what is still to decide - an object group, a checks group, a cannot-run group with both reasons,
 * the passive "follow their object" list, the optional suggestions kept apart, the figures, the empty state and the selection after a decision. JUnit-free, like
 * its neighbours: run the main, a failure throws. The fixture ({@link #project}, {@link #snapshot}) is shared with {@code DeskHtmlTest}.
 */
public final class DeskGroupsTest
{
	public static void main(final String[] pArgs)
	{
		DeskGroupsTest.regroupsByWhatThePersonDecides();
		DeskGroupsTest.theObjectGroupCountsItsProposalsAndWhatNeedsAnEye();
		DeskGroupsTest.checksAreOneGroupPerBusinessObject();
		DeskGroupsTest.cannotRunHoldsBothReasonsAndOnlyWhatCannotRun();
		DeskGroupsTest.theFieldsThatFollowTheirObjectAreListedAndNeverDecided();
		DeskGroupsTest.aLookUpThatCouldRunStaysOptionalAndUncounted();
		DeskGroupsTest.figuresCountEveryDecidableRow();
		DeskGroupsTest.theEmptyStateSummarisesAcceptedSetAsideAndFollowed();
		DeskGroupsTest.withoutASnapshotTheMatrixAloneAnswers();
		DeskGroupsTest.theSelectionStaysOnTheGroupOrMovesToTheNextOne();
		System.out.println("DeskGroupsTest OK");
	}

	// -------------------------------------------------------------------------------------------------------------- fixture

	private static Guard guard(final String pId, final String pNature, final String pAttributeId, final String pKind, final String pArgument, final String pStatus,
		final int pConfidence, final Boolean pReady, final String pDetail)
	{
		final boolean service = ReviewLine.EXTERNAL_SERVICE.equals(pNature);
		return new Guard(pId, pNature, "e2", pAttributeId, pKind, pArgument, ReviewLine.QUALITY.equals(pNature) || ReviewLine.SCRIPT_CONSTRAINT.equals(pNature) ? "WARN" : null, null, pStatus,
			"LLM", Integer.valueOf(pConfidence), "suggested by the model", null, null, null, service ? "Geo" : "", service ? pReady : null, pDetail, service ? "FREE" : "");
	}

	private static MatrixModel.Cell cell(final String pStatus, final String pMappingId, final String pColumn, final int pConfidence, final boolean pFlag)
	{
		return new MatrixModel.Cell(pStatus, pMappingId, pColumn, pFlag ? Arrays.asList("constant") : null, Integer.valueOf(pConfidence), "LLM", "because of " + pColumn, null, 0, null,
			MatrixModel.Sample.of(pColumn + " value", pColumn + " value"), pFlag ? "CONSTANT_AMBIGUOUS" : "", pFlag ? "the reason says residential but the constant says mailing" : "", null);
	}

	/**
	 * Personne (everything decided) and Adresse (the desk's subject): street / postalCode / addressType to accept - addressType carries an attention flag, so does its
	 * SAP link -, one rule and one script check on postalCode, three connector look-ups (one without secret, one whose binding waits for an input, one that could run), and
	 * the parent link {@code personne}, still {@code PROPOSED} in the matrix model and never a decision. {@code pDecided}: the whole project is decided (the empty state).
	 */
	public static MatrixModel.Project project(final boolean pDecided)
	{
		final String open = pDecided ? ReviewLine.ACCEPTED : ReviewLine.PROPOSED;
		final List<MatrixModel.Source> sources = Arrays.asList(new MatrixModel.Source("s1", "SAP", "#2f6fd0"), new MatrixModel.Source("s2", "Salesforce", "#0e9384"));

		final MatrixModel.Attr pKey = new MatrixModel.Attr("a0", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr lastName = new MatrixModel.Attr("a1", "lastName", "STRING", true, false, false, ReviewLine.ACCEPTED, Integer.valueOf(95));
		final Map<String, MatrixModel.Cell> pCells = new LinkedHashMap<String, MatrixModel.Cell>();
		pCells.put("a1", DeskGroupsTest.cell(ReviewLine.ACCEPTED, "m1", "NAME_LAST", 95, false));
		final MatrixModel.Feed pFeed = new MatrixModel.Feed("fP1", "s1", "t1", "BUT000", ReviewLine.ACCEPTED, null, "", false, null, pCells, Integer.valueOf(95), "");
		final MatrixModel.Entity personne = new MatrixModel.Entity("e1", "Personne", Arrays.asList(pKey, lastName), Arrays.asList(pFeed), ReviewLine.ACCEPTED, Integer.valueOf(100));

		final List<Guard> streetGuards = new ArrayList<Guard>();
		final List<Guard> postalGuards = new ArrayList<Guard>();
		postalGuards.add(DeskGroupsTest.guard("g1", ReviewLine.QUALITY, "b3", "PATTERN", "^[0-9 ]+$", open, 90, null, ""));
		postalGuards.add(DeskGroupsTest.guard("g2", ReviewLine.SCRIPT_CONSTRAINT, "b3", "postal.isValid", "", open, 85, null, ""));
		if (!pDecided)
		{
			streetGuards.add(DeskGroupsTest.guard("g5", ReviewLine.EXTERNAL_SERVICE, "b2", "geocode", "", ReviewLine.PROPOSED, 40, Boolean.TRUE, ""));
			// live fix 2026-10-22 (DemoBala, address.geocode): a script check whose binding waits for the full address as one input no accepted field holds
			streetGuards.add(new Guard("g6", ReviewLine.SCRIPT_CONSTRAINT, "e2", "b2", "address.geocode", "", "WARN", null, ReviewLine.PROPOSED, "LLM", Integer.valueOf(55),
				"needs the full address as one input; no such accepted attribute exists", null, null, null, "", null, "", "", true, false, ""));
			streetGuards.add(DeskGroupsTest.guard("g4", ReviewLine.EXTERNAL_SERVICE, "b2", "reverse", "", ReviewLine.PROPOSED, 45, Boolean.TRUE, ""));
			postalGuards.add(DeskGroupsTest.guard("g3", ReviewLine.EXTERNAL_SERVICE, "b3", "validate", "", ReviewLine.PROPOSED, 50, Boolean.FALSE, "its required authentication secret is missing"));
		}
		final MatrixModel.Attr aKey = new MatrixModel.Attr("b0", "sourceId", "STRING", true, false, true);
		final MatrixModel.Attr personneLink = new MatrixModel.Attr("b1", "personne", "STRING", true, true, false, ReviewLine.PROPOSED, Integer.valueOf(100));
		final MatrixModel.Attr street = new MatrixModel.Attr("b2", "street", "STRING", true, false, false, open, Integer.valueOf(92), streetGuards, "", "", null, "Street as one text");
		final MatrixModel.Attr postalCode = new MatrixModel.Attr("b3", "postalCode", "STRING", false, false, false, open, Integer.valueOf(88), postalGuards);
		final MatrixModel.Attr addressType = new MatrixModel.Attr("b4", "addressType", "STRING", false, false, false, open, Integer.valueOf(91));
		final MatrixModel.Attr city = new MatrixModel.Attr("b5", "city", "STRING", false, false, false, ReviewLine.ACCEPTED, Integer.valueOf(90));

		final Map<String, MatrixModel.Cell> sapCells = new LinkedHashMap<String, MatrixModel.Cell>();
		sapCells.put("b2", DeskGroupsTest.cell(open, "m11", "StreetName", 92, false));
		sapCells.put("b3", DeskGroupsTest.cell(open, "m12", "PostalCode", 88, false));
		sapCells.put("b4", DeskGroupsTest.cell(open, "m13", "constant", 91, !pDecided));
		sapCells.put("b5", DeskGroupsTest.cell(ReviewLine.ACCEPTED, "m14", "CityName", 90, false));
		final Map<String, MatrixModel.Cell> sfCells = new LinkedHashMap<String, MatrixModel.Cell>();
		sfCells.put("b2", DeskGroupsTest.cell(open, "m21", "MailingStreet", 90, false));
		sfCells.put("b3", DeskGroupsTest.cell(open, "m22", "MailingPostalCode", 89, false));
		final MatrixModel.Feed sap = new MatrixModel.Feed("fA1", "s1", "t2", "ADRC", open, null, "", false, null, sapCells, Integer.valueOf(90), "", "#designer-sap");
		final MatrixModel.Feed salesforce = new MatrixModel.Feed("fA2", "s2", "t3", "Contact", open, null, "", false, null, sfCells, Integer.valueOf(90), "", "#designer-sf");
		final MatrixModel.Entity adresse = new MatrixModel.Entity("e2", "Adresse", Arrays.asList(aKey, personneLink, street, postalCode, addressType, city), Arrays.asList(sap, salesforce),
			ReviewLine.ACCEPTED, Integer.valueOf(100));
		return new MatrixModel.Project("DemoBala", sources, new ArrayList<MatrixModel.Entity>(Arrays.asList(personne, adresse)));
	}

	private static WizardSnapshot.CapabilityInfo capability(final String pId, final String pAttribute, final boolean pReady, final String pDetail, final String pBinding, final String pStatus)
	{
		final WizardSnapshot.CapabilityInfo capability = new WizardSnapshot.CapabilityInfo();
		capability.id = pId;
		capability.attributeName = pAttribute;
		capability.connectorId = "Geo";
		capability.connectorOperation = "geocode";
		capability.proposition = "Look up " + pAttribute;
		capability.ready = pReady;
		capability.readinessDetail = pDetail;
		capability.binding = pBinding;
		capability.rationale = "could complete the address";
		capability.confidence = Integer.valueOf(50);
		capability.status = pStatus;
		return capability;
	}

	private static WizardSnapshot.AttributeInfo attribute(final String pName, final boolean pPrimaryKey, final boolean pParentLink)
	{
		final WizardSnapshot.AttributeInfo attribute = new WizardSnapshot.AttributeInfo();
		attribute.id = pName;
		attribute.name = pName;
		attribute.primaryKey = pPrimaryKey;
		attribute.parentLink = pParentLink;
		return attribute;
	}

	/** The wizard's snapshot of the same project: the capabilities (readiness, binding, set aside) and the attributes that follow their object. */
	public static WizardSnapshot snapshot(final boolean pDecided)
	{
		final WizardSnapshot snapshot = new WizardSnapshot();
		final WizardSnapshot.StagingEntityInfo personne = new WizardSnapshot.StagingEntityInfo();
		personne.id = "e1";
		personne.name = "Personne";
		personne.status = ReviewLine.ACCEPTED;
		personne.attributes.add(DeskGroupsTest.attribute("sourceSystem", true, false));
		personne.attributes.add(DeskGroupsTest.attribute("sourceId", true, false));
		personne.attributes.add(DeskGroupsTest.attribute("lastName", false, false));
		final WizardSnapshot.StagingEntityInfo adresse = new WizardSnapshot.StagingEntityInfo();
		adresse.id = "e2";
		adresse.name = "Adresse";
		adresse.status = ReviewLine.ACCEPTED;
		adresse.attributes.add(DeskGroupsTest.attribute("sourceSystem", true, false));
		adresse.attributes.add(DeskGroupsTest.attribute("sourceId", true, false));
		adresse.attributes.add(DeskGroupsTest.attribute("personne", false, true));
		adresse.attributes.add(DeskGroupsTest.attribute("street", false, false));
		if (!pDecided)
		{
			adresse.capabilities.add(DeskGroupsTest.capability("g3", "postalCode", false, "its required authentication secret is missing", "{\"q\":\"x\"}", ReviewLine.PROPOSED));
			adresse.capabilities.add(DeskGroupsTest.capability("g4", "street", true, "", "{\"q\":\"x\"}", ReviewLine.PROPOSED));
			adresse.capabilities.add(DeskGroupsTest.capability("g5", "street", true, "", "{\"address\":\"toBeProvided\"}", ReviewLine.PROPOSED));
		}
		adresse.capabilities.add(DeskGroupsTest.capability("g6", "postalCode", true, "", "{}", ReviewLine.SET_ASIDE));
		snapshot.stagingEntities.add(personne);
		snapshot.stagingEntities.add(adresse);
		return snapshot;
	}

	public static DeskModel desk()
	{
		return DeskModel.of(DeskGroupsTest.project(false), DeskGroupsTest.snapshot(false), MatrixCompute.DEFAULT_BULK_THRESHOLD);
	}

	// ---------------------------------------------------------------------------------------------------------------- tests

	private static void regroupsByWhatThePersonDecides()
	{
		final DeskModel desk = DeskGroupsTest.desk();
		final List<String> keys = desk.keys();
		DeskGroupsTest.check("an object group, its checks, then the services that cannot run, in that order: " + keys,
			keys.equals(Arrays.asList("obj|e2", "chk|e2", "run|")));
		DeskGroupsTest.check("the headline is the number of groups that need a person, one each: " + desk.decisions(), desk.decisions() == 3 && !desk.isDone());
		DeskGroupsTest.check("Personne, fully decided, has no group", desk.group("obj|e1") == null && desk.group("chk|e1") == null);
		final DeskModel.Group object = desk.group("obj|e2");
		boolean parentLinkListed = false;
		for (final DeskModel.Line line : object.lines)
		{
			parentLinkListed |= "personne".equals(line.item.label) || "personne".equals(line.item.attributeName);
		}
		DeskGroupsTest.check("the parent link is never a line of a group, although the matrix model holds it as PROPOSED", !parentLinkListed);
		DeskGroupsTest.check("the object group holds what the matrix list holds for the object, rules and checks apart",
			object.lines.size() + desk.group("chk|e2").lines.size() == DeskGroupsTest.itemsOf("e2") - DeskGroupsTest.parentLinkItems() - 1); // minus g6, in "Cannot run as proposed"
	}

	/** The matrix' own list for one business object. */
	private static int itemsOf(final String pEntityId)
	{
		int count = 0;
		for (final MatrixCompute.StillItem item : MatrixCompute.stillToDecide(DeskGroupsTest.project(false), MatrixCompute.DEFAULT_BULK_THRESHOLD))
		{
			count += item.entityId.equals(pEntityId) ? 1 : 0;
		}
		return count;
	}

	private static int parentLinkItems()
	{
		int count = 0;
		for (final MatrixCompute.StillItem item : MatrixCompute.stillToDecide(DeskGroupsTest.project(false), MatrixCompute.DEFAULT_BULK_THRESHOLD))
		{
			count += "b1".equals(item.attributeId) ? 1 : 0;
		}
		return count;
	}

	private static void theObjectGroupCountsItsProposalsAndWhatNeedsAnEye()
	{
		final DeskModel.Group object = DeskGroupsTest.desk().group("obj|e2");
		// 3 fields (street, postalCode, addressType), 2 source links (SAP, Salesforce) + 5 field links = 7 links
		DeskGroupsTest.check("3 fields: " + object.fields, object.fields == 3);
		DeskGroupsTest.check("7 links (2 source links and 5 field links): " + object.links, object.links == 7);
		DeskGroupsTest.check("fed by 2 sources: " + object.sources, object.sources == 2);
		DeskGroupsTest.check("10 proposals in all: " + object.proposals(), object.proposals() == 10);
		DeskGroupsTest.check("the flagged field and its flagged link are 2 items on 1 field: " + object.flaggedItems + "/" + object.flaggedFields, object.flaggedItems == 2 && object.flaggedFields == 1);
		int taken = 0;
		for (final DeskModel.Line line : object.lines)
		{
			taken += line.item.takenByTheButton() ? 1 : 0;
			DeskGroupsTest.check("a flagged line is never taken by the button: " + line.item.label, !line.flagged() || !line.item.takenByTheButton());
		}
		DeskGroupsTest.check("the Accept button of the object takes what the matrix' bulk takes: " + object.withButton + " vs " + taken, object.withButton == taken && taken > 0);
		DeskGroupsTest.check("the lines carry their facts: the field type, the landing column, the source",
			DeskGroupsTest.line(object, "b2|street").dataType.equals("STRING") && DeskGroupsTest.line(object, "b2|street").rationale.equals("Street as one text")
				&& DeskGroupsTest.line(object, "fA1|b3").column.equals("PostalCode") && DeskGroupsTest.line(object, "fA1|b3").sourceName.equals("SAP"));
	}

	private static DeskModel.Line line(final DeskModel.Group pGroup, final String pDescription)
	{
		final String[] parts = pDescription.split("\\|");
		for (final DeskModel.Line line : pGroup.lines)
		{
			final boolean field = line.item.key.equals("attr|" + parts[0]) && parts[1].equals(line.item.label);
			final boolean link = line.item.key.equals(parts[0] + "|" + parts[1]);
			if (field || link)
			{
				return line;
			}
		}
		throw new AssertionError("no line " + pDescription + " in " + pGroup.key);
	}

	private static void checksAreOneGroupPerBusinessObject()
	{
		final DeskModel.Group checks = DeskGroupsTest.desk().group("chk|e2");
		DeskGroupsTest.check("the rule and the script check of Adresse: " + checks.proposals(), checks.proposals() == 2);
		boolean rule = false;
		boolean check = false;
		for (final DeskModel.Line line : checks.lines)
		{
			rule |= MatrixCompute.KIND_RULE.equals(line.item.kind) && line.guardKind.equals("PATTERN") && line.guardArgument.equals("^[0-9 ]+$");
			check |= MatrixCompute.KIND_CHECK.equals(line.item.kind) && line.guardKind.equals("postal.isValid");
		}
		DeskGroupsTest.check("a rule and a check, with their kind and argument", rule && check);
		DeskGroupsTest.check("a connector look-up is not a check", DeskGroupsTest.desk().group("chk|e2").lines.size() == 2);
	}

	private static void cannotRunHoldsBothReasonsAndOnlyWhatCannotRun()
	{
		final DeskModel.Group run = DeskGroupsTest.desk().group("run|");
		DeskGroupsTest.check("two services and a script check cannot run: " + run.services.size(), run.services.size() == 3);
		DeskGroupsTest.check("one has no secret, two need an input no field holds", run.servicesWithoutSecret() == 1 && run.servicesNeedingInput() == 2);
		for (final DeskModel.ServiceLine service : run.services)
		{
			if (service.id.equals("g3"))
			{
				DeskGroupsTest.check("g3: no secret, with the readiness detail, and no missing input", service.noSecret && !service.needsInput
					&& service.readinessDetail.contains("authentication secret") && service.element.equals("Adresse.postalCode"));
			}
			else if (service.id.equals("g6"))
			{
				DeskGroupsTest.check("g6: the script check is here, with its input missing and its own nature", service.needsInput && !service.noSecret && !service.canSetAside()
					&& ReviewLine.SCRIPT_CONSTRAINT.equals(service.nature) && service.element.equals("Adresse.street") && service.proposition.equals("address.geocode"));
			}
			else
			{
				DeskGroupsTest.check("g5: an input no field holds, secret present", service.id.equals("g5") && service.needsInput && !service.noSecret && service.element.equals("Adresse.street"));
			}
		}
		DeskGroupsTest.check("\"Set the N aside\" posts exactly the ids the wizard card's button posts (WizardData#setCapabilitiesAside moves services only, so g6 is not in it)",
			run.serviceIds().equals("g3,g5") && run.serviceIds().equals(com.on.ebx.factory.wizard.StillToDecide.of(DeskGroupsTest.snapshot(false)).idsOfServicesThatCannotRun())
				&& run.servicesToSetAside() == 2);
		DeskGroupsTest.check("a cannot-run group counts its lines as proposals", run.proposals() == 3);
		// a script check that cannot run is in exactly one group
		int seen = 0;
		for (final DeskModel.Group group : DeskGroupsTest.desk().groups)
		{
			for (final DeskModel.Line line : group.lines)
			{
				seen += line.item.id.equals("g6") ? 1 : 0;
			}
		}
		DeskGroupsTest.check("g6 is not under Checks, nor in any object group", seen == 0 && DeskGroupsTest.desk().group("chk|e2").lines.size() == 2);
	}

	private static void theFieldsThatFollowTheirObjectAreListedAndNeverDecided()
	{
		final DeskModel desk = DeskGroupsTest.desk();
		final List<String> follows = new ArrayList<String>();
		for (final DeskModel.Follow follow : desk.follows)
		{
			follows.add(follow.entityName + "." + follow.name + ":" + follow.kind);
		}
		DeskGroupsTest.check("technical keys and the parent link, per object: " + follows, follows.equals(Arrays.asList("Personne.sourceSystem:KEY", "Personne.sourceId:KEY",
			"Adresse.sourceSystem:KEY", "Adresse.sourceId:KEY", "Adresse.personne:PARENT")));
		DeskGroupsTest.check("a followed field is not a proposal: it is not in the figures either", desk.totalProposals == 23);
	}

	private static void aLookUpThatCouldRunStaysOptionalAndUncounted()
	{
		final DeskModel desk = DeskGroupsTest.desk();
		DeskGroupsTest.check("one folded line: the street look-up that could run (g4); g5 and g3 are in the cannot-run group: " + desk.optional.size(),
			desk.optional.size() == 1 && desk.optional.get(0).entityName.equals("Adresse") && desk.optional.get(0).fieldName.equals("street") && desk.optional.get(0).count == 1);
		DeskGroupsTest.check("it is no group and no decision", desk.decisions() == 3);
	}

	private static void figuresCountEveryDecidableRow()
	{
		final DeskModel desk = DeskGroupsTest.desk();
		// Personne 4 of 4; Adresse: entity 1, feeds 0/2, field links 1/6, fields 1/4, guards 0/6 = 3/19
		DeskGroupsTest.check("23 decidable rows, 7 decided: " + desk.decidedProposals + "/" + desk.totalProposals, desk.totalProposals == 23 && desk.decidedProposals == 7);
		DeskGroupsTest.check("16 open", desk.openProposals() == 16);
	}

	private static void theEmptyStateSummarisesAcceptedSetAsideAndFollowed()
	{
		final DeskModel desk = DeskModel.of(DeskGroupsTest.project(true), DeskGroupsTest.snapshot(true), MatrixCompute.DEFAULT_BULK_THRESHOLD);
		DeskGroupsTest.check("nothing left: 0 decisions, no group, the empty state: " + desk.keys(), desk.isDone() && desk.decisions() == 0 && desk.keys().isEmpty());
		DeskGroupsTest.check("everything decided: " + desk.decidedProposals + "/" + desk.totalProposals, desk.openProposals() == 0);
		final DeskModel.Done done = desk.done;
		DeskGroupsTest.check("two business objects, two sources", done.objectNames.equals(Arrays.asList("Personne", "Adresse")) && done.sources == 2);
		DeskGroupsTest.check("accepted: Personne 1 field, 1 link; Adresse 4 fields, 6 links",
			done.acceptedObjects.size() == 2 && done.acceptedObjects.get(0).fields == 1 && done.acceptedObjects.get(0).links == 1 && done.acceptedObjects.get(1).fields == 4
				&& done.acceptedObjects.get(1).links == 6);
		DeskGroupsTest.check("accepted checks are listed with their field: " + done.acceptedChecks, done.acceptedChecks.size() == 2 && done.acceptedChecks.get(0).startsWith("Adresse.postalCode: "));
		DeskGroupsTest.check("the suggestion set aside is listed (the model does not hold it, the snapshot does): " + done.setAside,
			done.setAside.size() == 1 && done.setAside.get(0).equals("Adresse.postalCode: Geo · geocode"));
		DeskGroupsTest.check("the followed fields are listed: " + done.followed, done.followed.size() == 5 && done.followed.contains("Adresse.personne"));
	}

	private static void withoutASnapshotTheMatrixAloneAnswers()
	{
		final DeskModel desk = DeskModel.of(DeskGroupsTest.project(false), null, MatrixCompute.DEFAULT_BULK_THRESHOLD);
		DeskGroupsTest.check("without the snapshot the cannot-run group holds only the script check that cannot run: " + desk.keys(),
			desk.keys().equals(Arrays.asList("obj|e2", "chk|e2", "run|")) && desk.group("run|").services.size() == 1 && desk.group("run|").servicesToSetAside() == 0);
		DeskGroupsTest.check("the matrix' own flags still give the followed fields (source key, parent link)", desk.follows.size() == 3);
		DeskGroupsTest.check("the optional line then holds every look-up of the field", desk.optional.size() == 2);
	}

	private static void theSelectionStaysOnTheGroupOrMovesToTheNextOne()
	{
		final DeskModel desk = DeskGroupsTest.desk();
		final List<String> before = Arrays.asList("obj|e2", "chk|e2", "run|");
		DeskGroupsTest.check("the requested group while it has something left", DeskGroups.resolveSelection(desk, "chk|e2", before).equals("chk|e2"));
		DeskGroupsTest.check("no request: the first group", DeskGroups.resolveSelection(desk, null, null).equals("obj|e2"));
		DeskGroupsTest.check("an unknown request without history: the first group", DeskGroups.resolveSelection(desk, "obj|e9", null).equals("obj|e2"));
		// the checks were decided away: the group that followed them in the last render
		final DeskModel afterChecks = DeskModel.of(DeskGroupsTest.projectWithoutChecks(), DeskGroupsTest.snapshot(false), MatrixCompute.DEFAULT_BULK_THRESHOLD);
		DeskGroupsTest.check("checks gone: " + afterChecks.keys(), afterChecks.keys().equals(Arrays.asList("obj|e2", "run|")));
		DeskGroupsTest.check("the page moves to the group that followed it", DeskGroups.resolveSelection(afterChecks, "chk|e2", before).equals("run|"));
		// the last group was decided away: nothing follows, the first one
		final DeskModel lastGone = DeskModel.of(DeskGroupsTest.project(false), null, MatrixCompute.DEFAULT_BULK_THRESHOLD);
		lastGone.groups.remove(lastGone.group("run|"));
		DeskGroupsTest.check("nothing followed it: the first group", DeskGroups.resolveSelection(lastGone, "run|", before).equals("obj|e2"));
		final DeskModel done = DeskModel.of(DeskGroupsTest.project(true), DeskGroupsTest.snapshot(true), MatrixCompute.DEFAULT_BULK_THRESHOLD);
		DeskGroupsTest.check("nothing left: no selection (the empty state)", DeskGroups.resolveSelection(done, "obj|e2", before).isEmpty());
	}

	/** The same project once the rule and the script check of postalCode are accepted. */
	private static MatrixModel.Project projectWithoutChecks()
	{
		final MatrixModel.Project project = DeskGroupsTest.project(false);
		final MatrixModel.Entity adresse = project.entityById("e2");
		for (final MatrixModel.Attr attr : adresse.attrs)
		{
			if (attr.id.equals("b3"))
			{
				final List<Guard> kept = new ArrayList<Guard>();
				for (final Guard guard : attr.guards)
				{
					if (ReviewLine.EXTERNAL_SERVICE.equals(guard.nature))
					{
						kept.add(guard);
					}
				}
				attr.guards.clear();
				attr.guards.addAll(kept);
			}
		}
		return project;
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
