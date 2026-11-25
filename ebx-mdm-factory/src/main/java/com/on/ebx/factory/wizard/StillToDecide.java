package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeSet;

import com.on.ebx.factory.capability.DerivationParameters;
import com.on.ebx.factory.review.ReviewLine;

/**
 * Staging-still-to-decide lot (2026-09-27, measured live on project {@code ret}): what step 4's "N decisions left"
 * card shows - every row still {@code PROPOSED} under the project's ACCEPTED business objects, gathered in one
 * place and grouped by nature, each with what a person needs to decide it right there. Pure (no EBX type), built
 * from a {@link WizardSnapshot} only, unit-tested in {@code WizardRulesTest}.
 * <p>
 * Why it exists: after "Propose the staging", 7 items were left {@code PROPOSED} on {@code ret} (2 fields whose
 * source links were already accepted, 1 quality rule on one of those fields, 4 connector services of which 3 could
 * not run and 1 whose query was never written). Each lived in a different place (entity card, proposal lines,
 * nowhere at all for the services) and the person "did not know how to handle what is left to validate". The
 * build gate ({@code WizardRules.actionGates#generateStaging}) only needs one accepted business object, so this is
 * about legibility, not a technical gate: the card says what is left, and the build button says what happens to it
 * ({@code wizard.step4.stillToDecide.buildNote}).
 * <p>
 * Scope rules (each one deliberate):
 * <ul>
 * <li>Only business objects that are {@code ACCEPTED}/{@code APPLIED}: a still-{@code PROPOSED} or rejected object
 * is decided as a whole elsewhere (its own card), and {@code SUPERSEDED} ones are hidden everywhere in step 4.</li>
 * <li>A technical-key field ({@code primaryKey}) never appears: it has no decision of its own
 * ({@code staging.TechnicalKeyRule}).</li>
 * <li>A quality rule whose field is itself still {@code PROPOSED} is listed as "decided with the field" instead of
 * getting its own buttons: the field's Accept on this card carries it ({@code ProposalReviews.decide} with
 * {@code carryQualityRules}, see {@link #followsFieldDecision}), and a field's Reject already rejected its
 * {@code PROPOSED} rules and suggestions before this lot ({@code ProposalReviews#cascadeGuardsOfAttribute}).</li>
 * <li>A source link ({@code AttributeMapping}) of a still-{@code PROPOSED} field is not listed either: it already
 * follows its field ({@code staging.MappingFollowRule}).</li>
 * <li>A connector service ({@code EXTERNAL_SERVICE}) is never offered Accept here - it is advice about a connector,
 * not something the staging build uses - but "Set aside" ({@link ReviewLine#SET_ASIDE}: not refused, not applied,
 * kept for later) and, when a connector is named, "Configure".</li>
 * </ul>
 */
public final class StillToDecide
{
	/** The placeholder {@code CapabilityLlmPass} writes into a binding input whose value (a query, a filter) a person still has to write. */
	public static final String TO_BE_PROVIDED = "toBeProvided";

	/** One field ({@code StagingAttribute}) still {@code PROPOSED}. */
	public static final class FieldItem
	{
		public String entityName = "";
		public String attributeId = "";
		public String name = "";
		public String label = "";
		public String rationale = "";
		public Integer confidence;
		/** Source systems whose feed has a not-rejected mapping into this field, sorted - the "fed by" chips. */
		public final List<String> sources = new ArrayList<String>();
		/** True when the field has at least one mapping and every not-rejected one is already {@code ACCEPTED}/{@code APPLIED}: "only the field itself waits". */
		public boolean linksAccepted;
		/** How many {@code PROPOSED} quality rules of this field will be decided together with it. */
		public int carriedRules;
	}

	/** One quality rule ({@code QualityRule}) still {@code PROPOSED}. */
	public static final class RuleItem
	{
		public String entityName = "";
		public String rowId = "";
		public String element = "";
		public String proposition = "";
		/** {@code MODEL_CONSTRAINT}/{@code REJECT}/{@code WARN}, lifted out of the proposition text (shown as a chip), or empty. */
		public String severity = "";
		public String rationale = "";
		public Integer confidence;
		public Long valuesSeen;
		public Long valuesFailing;
		/** Non-empty when the rule's field is itself still {@code PROPOSED}: the rule is decided with that field, no buttons of its own. */
		public String decidedWithField = "";
	}

	/** One check ({@code SCRIPT_CONSTRAINT}/{@code SCRIPT_DERIVATION}) or connector service ({@code EXTERNAL_SERVICE}) still {@code PROPOSED}. */
	public static final class CheckItem
	{
		public String entityName = "";
		public String nature = "";
		public String rowId = "";
		public String element = "";
		public String proposition = "";
		/** Same as {@link RuleItem#severity} (a {@code SCRIPT_CONSTRAINT} carries one; a service or derivation does not). */
		public String severity = "";
		public String rationale = "";
		public Integer confidence;
		/** {@code EXTERNAL_SERVICE} only: the connector named by the suggestion - empty otherwise. */
		public String connectorId = "";
		/** {@code EXTERNAL_SERVICE} only: {@code ready=false} on the row - "secret missing". */
		public boolean secretMissing;
		public String readinessDetail = "";
		/** {@code EXTERNAL_SERVICE} only: the binding still carries {@link #TO_BE_PROVIDED} - "query to write". */
		public boolean queryToWrite;
		public String pricing = "";
		public String pricingNote = "";
		/**
		 * {@code SCRIPT_DERIVATION} items of {@link #toApply} only (lot P1, 2026-10-04): the parameters a person may settle - each {@code literal} or
		 * {@code toBeProvided} entry of the binding, in binding order (an attribute-bound parameter is never listed). Empty for every other item.
		 */
		public List<DerivationParameters.Settable> parameters = new ArrayList<DerivationParameters.Settable>();
		/** Lot P1: an already APPLIED derivation whose parameters changed since ("parameters changed - apply again"); false for an ACCEPTED one. */
		public boolean reapplyNeeded;

		/** Lot P1: the names of the parameters still {@code toBeProvided} - while any remains, Apply is disabled. */
		public List<String> toBeProvidedRemaining()
		{
			final List<String> out = new ArrayList<String>();
			for (final DerivationParameters.Settable parameter : this.parameters)
			{
				if (parameter.toBeProvided)
				{
					out.add(parameter.name);
				}
			}
			return out;
		}

		public boolean isService()
		{
			return ReviewLine.EXTERNAL_SERVICE.equals(this.nature);
		}

		/** A service that cannot run as it stands - what "Set all N aside" sweeps. */
		public boolean cannotRun()
		{
			return this.isService() && (this.secretMissing || this.queryToWrite);
		}
	}

	/** One source link ({@code TransferFeed}/{@code AttributeMapping}) or cleaning step ({@code MappingStep}) still {@code PROPOSED}. */
	public static final class LinkItem
	{
		public String entityName = "";
		/** {@link ReviewLine#FEED}/{@link ReviewLine#MAPPING}/{@link ReviewLine#MAPPING_STEP}. */
		public String nature = "";
		public String rowId = "";
		public String element = "";
		public String proposition = "";
		public Integer confidence;
	}

	/** Accepted/left per nature - the right rail's "Your decisions" rows. */
	public static final class Tally
	{
		public int accepted;
		public int left;
		public int setAside;
	}

	public final List<FieldItem> fields = new ArrayList<FieldItem>();
	public final List<RuleItem> rules = new ArrayList<RuleItem>();
	public final List<CheckItem> checks = new ArrayList<CheckItem>();
	public final List<LinkItem> links = new ArrayList<LinkItem>();
	/**
	 * Lot M5 (2026-10-04): every {@code SCRIPT_DERIVATION} suggestion that is {@code ACCEPTED} but not {@code APPLIED} - decided, yet its
	 * derived attribute is still empty because nothing computes it (the loader holds an unapplied script derivation empty,
	 * {@code staging.ScriptDerivationGate}). Not a decision to take, an action to run: the card offers Apply. Before this lot such a
	 * line left the card altogether once accepted, and the wizard showed the attribute as done.
	 */
	public final List<CheckItem> toApply = new ArrayList<CheckItem>();
	public final Tally fieldTally = new Tally();
	public final Tally ruleTally = new Tally();
	public final Tally checkTally = new Tally();
	public final Tally linkTally = new Tally();

	private StillToDecide()
	{
	}

	/** Every item listed on the card - the "N" of "N decisions left", rules decided with their field included (each is still one thing a person sees move). */
	public int total()
	{
		return this.fields.size() + this.rules.size() + this.checks.size() + this.links.size() + this.toApply.size();
	}

	/** Staging-screens lot (2026-10-17): the connector look-up suggestions on the card - optional, never a reason to hold the build back (same rule as {@code Step4Counts#optionalWaiting}). */
	public int optionalTotal()
	{
		int count = 0;
		for (final CheckItem item : this.checks)
		{
			if (item.isService())
			{
				count++;
			}
		}
		return count;
	}

	/** What the card's title counts: the items a decision is needed for - {@link #total()} without the optional suggestions. */
	public int blockingTotal()
	{
		return this.total() - this.optionalTotal();
	}

	/** How many connector services cannot run as they stand ({@link CheckItem#cannotRun}). */
	public int servicesThatCannotRun()
	{
		int count = 0;
		for (final CheckItem item : this.checks)
		{
			if (item.cannotRun())
			{
				count++;
			}
		}
		return count;
	}

	/** Comma-separated row ids of {@link #servicesThatCannotRun()} - posted by "Set all N aside" in one round trip. */
	public String idsOfServicesThatCannotRun()
	{
		final StringBuilder out = new StringBuilder();
		for (final CheckItem item : this.checks)
		{
			if (item.cannotRun())
			{
				if (out.length() > 0)
				{
					out.append(',');
				}
				out.append(item.rowId);
			}
		}
		return out.toString();
	}

	/**
	 * Whether a quality rule in status {@code pRuleStatus} moves together with its field's decision on this card:
	 * only a rule nobody has decided yet ({@code PROPOSED}) - a person's own Accept/Reject on the rule is never
	 * overridden. Shared with {@code ebx.ProposalReviews}'s cascade so the card's promise and the write cannot
	 * drift.
	 */
	public static boolean followsFieldDecision(final String pRuleStatus)
	{
		return ReviewLine.PROPOSED.equals(pRuleStatus);
	}

	/** Whether a service binding still waits for a person to write one of its inputs (the Salesforce query of project {@code ret}). */
	public static boolean queryToWrite(final String pBinding)
	{
		return pBinding != null && pBinding.contains(StillToDecide.TO_BE_PROVIDED);
	}

	/** Whether {@code pStatus} reads as accepted ({@code ACCEPTED}/{@code APPLIED}). */
	private static boolean accepted(final String pStatus)
	{
		return ReviewLine.ACCEPTED.equals(pStatus) || ReviewLine.APPLIED.equals(pStatus);
	}

	public static StillToDecide of(final WizardSnapshot pSnapshot)
	{
		final StillToDecide out = new StillToDecide();
		for (final WizardSnapshot.StagingEntityInfo entity : pSnapshot.stagingEntities)
		{
			if (!StillToDecide.accepted(entity.status))
			{
				continue;
			}
			out.collect(entity);
		}
		return out;
	}

	private void collect(final WizardSnapshot.StagingEntityInfo pEntity)
	{
		final java.util.Map<String, WizardSnapshot.AttributeInfo> proposedFieldsById = new java.util.LinkedHashMap<String, WizardSnapshot.AttributeInfo>();
		final java.util.Set<String> proposedFieldNames = new java.util.HashSet<String>();
		for (final WizardSnapshot.AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.followsEntity())
			{
				continue;
			}
			if (ReviewLine.PROPOSED.equals(attribute.status))
			{
				proposedFieldsById.put(attribute.id, attribute);
				proposedFieldNames.add(attribute.name);
			}
			else if (StillToDecide.accepted(attribute.status))
			{
				this.fieldTally.accepted++;
			}
		}

		final java.util.Map<String, FieldItem> fieldItems = new java.util.LinkedHashMap<String, FieldItem>();
		for (final WizardSnapshot.AttributeInfo attribute : proposedFieldsById.values())
		{
			final FieldItem item = new FieldItem();
			item.entityName = pEntity.name;
			item.attributeId = attribute.id;
			item.name = attribute.name;
			item.label = attribute.label == null ? "" : attribute.label;
			item.rationale = attribute.rationale == null ? "" : attribute.rationale;
			item.confidence = attribute.confidence;
			final TreeSet<String> sources = new TreeSet<String>();
			for (final WizardSnapshot.FeedInfo feed : pEntity.feeds)
			{
				if (ReviewLine.REJECTED.equals(feed.status))
				{
					continue;
				}
				for (final WizardSnapshot.FeedMappingInfo mapping : feed.mappings)
				{
					if (attribute.name.equals(mapping.attributeName) && !ReviewLine.REJECTED.equals(mapping.status) && !feed.sourceSystem.isEmpty())
					{
						sources.add(feed.sourceSystem);
					}
				}
			}
			item.sources.addAll(sources);
			int feeding = 0;
			boolean allAccepted = true;
			for (final WizardSnapshot.MappingInfo mapping : attribute.mappings)
			{
				if (ReviewLine.REJECTED.equals(mapping.status))
				{
					continue;
				}
				feeding++;
				if (!StillToDecide.accepted(mapping.status))
				{
					allAccepted = false;
				}
			}
			item.linksAccepted = feeding > 0 && allAccepted;
			fieldItems.put(attribute.id, item);
			this.fields.add(item);
			this.fieldTally.left++;
		}

		for (final WizardSnapshot.ProposalLineInfo info : pEntity.proposalLines)
		{
			final ReviewLine line = info.line;
			if (ReviewLine.QUALITY.equals(line.nature))
			{
				if (StillToDecide.accepted(line.status))
				{
					this.ruleTally.accepted++;
				}
				if (!ReviewLine.PROPOSED.equals(line.status))
				{
					continue;
				}
				final RuleItem item = new RuleItem();
				item.entityName = pEntity.name;
				item.rowId = line.rowId;
				item.element = line.element;
				final String[] split = StillToDecide.splitSeverity(line.proposition);
				item.severity = split[0];
				item.proposition = split[1];
				item.rationale = line.rationale == null ? "" : line.rationale;
				item.confidence = line.confidence;
				item.valuesSeen = info.valuesSeen;
				item.valuesFailing = info.valuesFailing;
				final FieldItem field = info.attributeId.isEmpty() ? null : fieldItems.get(info.attributeId);
				if (field != null)
				{
					item.decidedWithField = field.name;
					field.carriedRules++;
				}
				this.rules.add(item);
				this.ruleTally.left++;
			}
			else if (ReviewLine.SCRIPT_CONSTRAINT.equals(line.nature) || ReviewLine.SCRIPT_DERIVATION.equals(line.nature))
			{
				if (StillToDecide.accepted(line.status))
				{
					this.checkTally.accepted++;
				}
				// lot P1 (2026-10-04): an APPLIED derivation whose parameters were changed afterwards is listed here too ("apply again"): the
				// stored trigger bodies still carry the old parameter. Recognised by the fixed sentence written in applyMessage by the settling route.
				final boolean reapply = ReviewLine.SCRIPT_DERIVATION.equals(line.nature) && ReviewLine.APPLIED.equals(line.status)
					&& DerivationParameters.isReapplyMarker(info.applyMessage);
				if (ReviewLine.SCRIPT_DERIVATION.equals(line.nature) && (ReviewLine.ACCEPTED.equals(line.status) || reapply))
				{
					final CheckItem pending = new CheckItem();
					pending.entityName = pEntity.name;
					pending.nature = line.nature;
					pending.rowId = line.rowId;
					pending.element = line.element;
					pending.proposition = line.proposition;
					pending.rationale = line.rationale == null ? "" : line.rationale;
					pending.confidence = line.confidence;
					pending.parameters = DerivationParameters.settable(info.binding);
					pending.reapplyNeeded = reapply;
					this.toApply.add(pending);
					continue;
				}
				if (!ReviewLine.PROPOSED.equals(line.status))
				{
					continue;
				}
				final CheckItem item = new CheckItem();
				item.entityName = pEntity.name;
				item.nature = line.nature;
				item.rowId = line.rowId;
				item.element = line.element;
				final String[] split = StillToDecide.splitSeverity(line.proposition);
				item.severity = split[0];
				item.proposition = split[1];
				item.rationale = line.rationale == null ? "" : line.rationale;
				item.confidence = line.confidence;
				if (ReviewLine.SCRIPT_DERIVATION.equals(line.nature))
				{
					// fix/derivation-apply-backfill (2026-10-17, lot G1, item 3): a derivation that needs a parameter says so BEFORE it is accepted, not only once it is waiting to be applied
					item.parameters = DerivationParameters.settable(info.binding);
				}
				this.checks.add(item);
				this.checkTally.left++;
			}
			else if (ReviewLine.MAPPING_STEP.equals(line.nature))
			{
				if (StillToDecide.accepted(line.status))
				{
					this.linkTally.accepted++;
				}
				if (!ReviewLine.PROPOSED.equals(line.status))
				{
					continue;
				}
				final LinkItem item = new LinkItem();
				item.entityName = pEntity.name;
				item.nature = line.nature;
				item.rowId = line.rowId;
				item.element = line.element;
				item.proposition = line.proposition;
				item.confidence = line.confidence;
				this.links.add(item);
				this.linkTally.left++;
			}
		}

		for (final WizardSnapshot.CapabilityInfo capability : pEntity.capabilities)
		{
			if (StillToDecide.accepted(capability.status))
			{
				this.checkTally.accepted++;
			}
			if (ReviewLine.SET_ASIDE.equals(capability.status))
			{
				this.checkTally.setAside++;
			}
			if (!ReviewLine.PROPOSED.equals(capability.status))
			{
				continue;
			}
			final CheckItem item = new CheckItem();
			item.entityName = pEntity.name;
			item.nature = ReviewLine.EXTERNAL_SERVICE;
			item.rowId = capability.id;
			item.element = capability.attributeName.isEmpty() ? pEntity.name : pEntity.name + "." + capability.attributeName;
			item.proposition = capability.proposition;
			item.rationale = capability.rationale;
			item.confidence = capability.confidence;
			item.connectorId = capability.connectorId;
			item.secretMissing = !capability.ready;
			item.readinessDetail = StillToDecide.readableDetail(capability.readinessDetail);
			item.queryToWrite = StillToDecide.queryToWrite(capability.binding);
			item.pricing = capability.pricing;
			item.pricingNote = capability.pricingNote;
			this.checks.add(item);
			this.checkTally.left++;
		}

		for (final WizardSnapshot.FeedInfo feed : pEntity.feeds)
		{
			if (StillToDecide.accepted(feed.status))
			{
				this.linkTally.accepted++;
			}
			else if (ReviewLine.PROPOSED.equals(feed.status))
			{
				final LinkItem item = new LinkItem();
				item.entityName = pEntity.name;
				item.nature = ReviewLine.FEED;
				item.rowId = feed.id;
				item.element = pEntity.name + " ← " + feed.sourceSystem + " " + feed.landingTableName;
				item.confidence = feed.confidence;
				this.links.add(item);
				this.linkTally.left++;
			}
			for (final WizardSnapshot.FeedMappingInfo mapping : feed.mappings)
			{
				if (StillToDecide.accepted(mapping.status))
				{
					this.linkTally.accepted++;
					continue;
				}
				// a mapping of a still-PROPOSED field follows that field (MappingFollowRule) - never a second decision here
				if (!ReviewLine.PROPOSED.equals(mapping.status) || proposedFieldNames.contains(mapping.attributeName))
				{
					continue;
				}
				final LinkItem item = new LinkItem();
				item.entityName = pEntity.name;
				item.nature = ReviewLine.MAPPING;
				item.rowId = mapping.id;
				item.element = feed.sourceSystem + " " + mapping.landingColumn + " → " + pEntity.name + "." + mapping.attributeName;
				item.proposition = mapping.pipeline.isEmpty() ? mapping.transformationDisplay : mapping.pipeline;
				item.confidence = mapping.confidence;
				this.links.add(item);
				this.linkTally.left++;
			}
		}
	}

	/**
	 * Polish lot (2026-09-27, measured live on project ret): a rule's or check's proposition starts with its
	 * severity in capitals ({@code "MODEL_CONSTRAINT - Must match pattern: ..."}, {@code ProposalWording}'s
	 * "leading word" convention, right for the review screen but raw here). Returns {severity, the rest}; the card
	 * shows the severity as a localized chip. A text without that prefix comes back unchanged with no severity.
	 */
	static String[] splitSeverity(final String pProposition)
	{
		final String text = pProposition == null ? "" : pProposition;
		for (final String severity : new String[] { "MODEL_CONSTRAINT", "REJECT", "WARN" })
		{
			if (text.startsWith(severity + " - "))
			{
				return new String[] { severity, text.substring(severity.length() + 3) };
			}
		}
		return new String[] { "", text };
	}

	/**
	 * Polish lot (2026-09-27, measured live on project ret): {@code CapabilitySuggestion.readinessDetail} is
	 * stored as JSON ({@code {"error":"Connector 'Melissa' configuration is invalid: ..."}}) and the card showed it
	 * raw. Keeps the human sentence: the first of {@code error}/{@code message}/{@code detail} when the text is a
	 * JSON object, the text itself otherwise (never throws, an unparsable value is shown as it is).
	 */
	public static String readableDetail(final String pDetail)
	{
		final String text = pDetail == null ? "" : pDetail.trim();
		if (!text.startsWith("{"))
		{
			return text;
		}
		try
		{
			final com.fasterxml.jackson.databind.JsonNode node = new com.fasterxml.jackson.databind.ObjectMapper().readTree(text);
			for (final String key : new String[] { "error", "message", "detail" })
			{
				if (node.hasNonNull(key) && !node.path(key).asText("").trim().isEmpty())
				{
					return node.path(key).asText().trim();
				}
			}
			return "";
		}
		catch (final Exception ex)
		{
			return text;
		}
	}
}
