package com.on.ebx.factory.guard;

import com.on.ebx.factory.landing.TextLimits;
import com.on.ebx.factory.review.ReviewLine;

/**
 * Staging-proposal lot 0 (docs/STAGING-PROPOSAL.md): one "guard" of a staging model - a {@code QualityRule}
 * row or a {@code CapabilitySuggestion} row of nature {@code SCRIPT_CONSTRAINT}/{@code EXTERNAL_SERVICE}/
 * {@code SCRIPT_DERIVATION} - normalised to one shape so the Mapping matrix's future "Rules and checks"
 * column and the Feed designer's future guard panel can read either kind without knowing which table it
 * came from. Pure: no EBX class, unit-tested without a live repository - the EBX-bound reader is
 * {@code ebx.GuardReader}, exactly the split {@code review.ReviewLine}/{@code ebx.ProposalReviews} already
 * keep for the proposal-review screen (this class deliberately mirrors {@code ReviewLine}'s own shape and
 * field naming rather than inventing a new vocabulary).
 * <p>
 * {@link #nature} is always one of {@link ReviewLine#QUALITY}, {@link ReviewLine#SCRIPT_CONSTRAINT},
 * {@link ReviewLine#EXTERNAL_SERVICE} or {@link ReviewLine#SCRIPT_DERIVATION} - the four natures the task
 * brief calls "guards". {@link #transferPolicy} is only ever meaningful for {@link ReviewLine#SCRIPT_CONSTRAINT}
 * (see {@code CAPABILITY_TRANSFER_POLICY} in {@code tools/build_xsd.py}); {@link #severity} is meaningful for
 * {@link ReviewLine#QUALITY} ({@code QUALITY_RULE_SEVERITY}: {@code MODEL_CONSTRAINT}/{@code REJECT}/
 * {@code WARN}) and for {@link ReviewLine#SCRIPT_CONSTRAINT} ({@code CAPABILITY_SEVERITY}: {@code WARN}/
 * {@code ERROR}) - null for the other two natures, never a meaningless default (same "no default value
 * where it means nothing" rule the XSD comments already state for these fields).
 */
public final class Guard
{
	public final String id;
	/** {@link ReviewLine#QUALITY}/{@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#EXTERNAL_SERVICE}/{@link ReviewLine#SCRIPT_DERIVATION}. */
	public final String nature;
	public final String entityId;
	/** Null for an entity-level guard (a cross-column {@code QualityRule}, or a {@code CapabilitySuggestion} with no single-attribute focus). */
	public final String attributeId;
	/**
	 * {@code QualityRule.kind} (MANDATORY/PATTERN/REFERENCE/CHECK_DIGIT/UNIQUE) for {@link ReviewLine#QUALITY};
	 * the exported function's short name (e.g. {@code isValid}, from {@code CapabilitySuggestion.targetFunction})
	 * for {@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#SCRIPT_DERIVATION}; the connector operation
	 * name for {@link ReviewLine#EXTERNAL_SERVICE}. Never null (empty string when the underlying row carries
	 * none).
	 */
	public final String kind;
	/** {@code QualityRule.argument}, or empty for a capability guard (its own "argument" is {@link #binding}, deliberately not folded in here - see {@code ebx.GuardReader}). */
	public final String argument;
	/** Null when not meaningful for this {@link #nature} - see the class Javadoc. */
	public final String severity;
	/** {@link ReviewLine#SCRIPT_CONSTRAINT} + zone STAGING only ({@code CAPABILITY_TRANSFER_POLICY}: blank/{@code WARN}/{@code REJECT}); null for every other nature. */
	public final String transferPolicy;
	public final String status;
	public final String origin;
	/** 0-100, or null. */
	public final Integer confidence;
	public final String rationale;
	/** {@code QualityRule.valuesSeen} - null when not read for this nature/row. */
	public final Long valuesSeen;
	/** {@code QualityRule.valuesFailing} - null when not read for this nature/row. */
	public final Long valuesFailing;
	/** At most a handful of "failing"/"before -&gt; after" examples, already plain text - null when none. */
	public final String examples;
	/**
	 * UX lot U6 (docs/UX-MATRIX-QUEUE.md), {@link ReviewLine#EXTERNAL_SERVICE} only: the connector the suggestion calls ({@code CapabilitySuggestion.connectorId}), so
	 * three look-ups of the same kind through three different providers can be told apart in the queue. Empty for every other nature.
	 */
	public final String serviceConnector;
	/** {@link ReviewLine#EXTERNAL_SERVICE} only: {@code Boolean.TRUE} ready to use, {@code Boolean.FALSE} not ready (secret or configuration missing), null unknown / not a service. */
	public final Boolean serviceReady;
	/** {@link ReviewLine#EXTERNAL_SERVICE} only: why it is not ready ({@code CapabilitySuggestion.readinessDetail}), already plain text, or "". */
	public final String serviceReadinessDetail;
	/** {@link ReviewLine#EXTERNAL_SERVICE} only: {@code FREE}/{@code FREEMIUM}/{@code PAID}/{@code UNKNOWN}, or "" when not read. */
	public final String servicePricing;
	/**
	 * Validation desk live fix (2026-10-22), any capability nature: the suggestion's binding still carries {@code toBeProvided} - an input no accepted
	 * field holds - or its row says {@code ready = false}. Both mean "cannot run as proposed" ({@link #cannotRunAsProposed}). False for a quality rule.
	 */
	public final boolean needsInput;
	/** Same, any capability nature: {@code CapabilitySuggestion.ready} is false (secret or configuration missing). */
	public final boolean notReady;
	/** Why it is not ready ({@code CapabilitySuggestion.readinessDetail}), plain text, or "" - any capability nature. */
	public final String notReadyDetail;

	public Guard(final String pId, final String pNature, final String pEntityId, final String pAttributeId, final String pKind, final String pArgument,
		final String pSeverity, final String pTransferPolicy, final String pStatus, final String pOrigin, final Integer pConfidence, final String pRationale,
		final Long pValuesSeen, final Long pValuesFailing, final String pExamples)
	{
		this(pId, pNature, pEntityId, pAttributeId, pKind, pArgument, pSeverity, pTransferPolicy, pStatus, pOrigin, pConfidence, pRationale, pValuesSeen, pValuesFailing,
			pExamples, "", null, "", "");
	}

	public Guard(final String pId, final String pNature, final String pEntityId, final String pAttributeId, final String pKind, final String pArgument,
		final String pSeverity, final String pTransferPolicy, final String pStatus, final String pOrigin, final Integer pConfidence, final String pRationale,
		final Long pValuesSeen, final Long pValuesFailing, final String pExamples, final String pServiceConnector, final Boolean pServiceReady,
		final String pServiceReadinessDetail, final String pServicePricing)
	{
		this(pId, pNature, pEntityId, pAttributeId, pKind, pArgument, pSeverity, pTransferPolicy, pStatus, pOrigin, pConfidence, pRationale, pValuesSeen, pValuesFailing,
			pExamples, pServiceConnector, pServiceReady, pServiceReadinessDetail, pServicePricing, false, false, "");
	}

	public Guard(final String pId, final String pNature, final String pEntityId, final String pAttributeId, final String pKind, final String pArgument,
		final String pSeverity, final String pTransferPolicy, final String pStatus, final String pOrigin, final Integer pConfidence, final String pRationale,
		final Long pValuesSeen, final Long pValuesFailing, final String pExamples, final String pServiceConnector, final Boolean pServiceReady,
		final String pServiceReadinessDetail, final String pServicePricing, final boolean pNeedsInput, final boolean pNotReady, final String pNotReadyDetail)
	{
		this.needsInput = pNeedsInput;
		this.notReady = pNotReady;
		this.notReadyDetail = pNotReadyDetail == null ? "" : pNotReadyDetail;
		this.id = pId;
		this.nature = pNature;
		this.entityId = pEntityId;
		this.attributeId = pAttributeId;
		this.kind = pKind == null ? "" : pKind;
		this.argument = pArgument == null ? "" : pArgument;
		this.severity = pSeverity;
		this.transferPolicy = pTransferPolicy;
		this.status = pStatus;
		this.origin = pOrigin;
		this.confidence = pConfidence;
		this.rationale = pRationale;
		this.valuesSeen = pValuesSeen;
		this.valuesFailing = pValuesFailing;
		this.examples = pExamples;
		this.serviceConnector = pServiceConnector == null ? "" : pServiceConnector;
		this.serviceReady = pServiceReady;
		this.serviceReadinessDetail = pServiceReadinessDetail == null ? "" : pServiceReadinessDetail;
		this.servicePricing = pServicePricing == null ? "" : pServicePricing;
	}

	/**
	 * True for a capability suggestion (script check, connector service, derivation) that cannot run as proposed: not ready, or an input no field holds. Such an item is
	 * never a "check" to accept: the Validation desk lists it under "Cannot run as proposed". A script derivation is not one of them: the wizard card lets a person
	 * settle its missing input, so it stays a decision of its own.
	 */
	public boolean cannotRunAsProposed()
	{
		return !ReviewLine.QUALITY.equals(this.nature) && !ReviewLine.SCRIPT_DERIVATION.equals(this.nature) && (this.needsInput || this.notReady);
	}

	/** How many characters of {@link #argument} the {@link #label()} of a {@link ReviewLine#QUALITY} guard keeps, before a truncation marker. */
	private static final int ARGUMENT_DIGEST_LENGTH = 24;

	/**
	 * A short, purely computed display label - never a database lookup, never localized (the caller decides
	 * whether/how to translate {@link #kind} itself): {@code "<kind> <argument digest>"} for
	 * {@link ReviewLine#QUALITY} (e.g. {@code "PATTERN ^[A-Z]{2}\\d{9}$"}, argument cut to
	 * {@link #ARGUMENT_DIGEST_LENGTH} characters via {@code landing.TextLimits#truncate} - the same helper
	 * {@code ebx.PipelineSteps}'s own message fields already use, so a long regex never blows the label past
	 * a readable length); {@code "<kind>"} alone when {@link #argument} is empty. {@code "<kind>"} for
	 * {@link ReviewLine#SCRIPT_CONSTRAINT}/{@link ReviewLine#SCRIPT_DERIVATION}, where {@link #kind} is
	 * already the short function name (e.g. {@code "phone.isValid"}) - built by the EBX-bound reader
	 * ({@code ebx.GuardReader}, which calls {@code ebx.CapabilityApplier#aliasOf} directly, both being in
	 * package {@code ebx}: no duplication needed here, unlike a pure/impure boundary that truly has none in
	 * common - see {@code transfer.TransformationCatalogue}'s own Javadoc for that different case). {@code "<kind>"} for {@link ReviewLine#EXTERNAL_SERVICE} too (the connector
	 * operation name). Never null - {@code "?"} when {@link #kind} itself is empty.
	 */
	public String label()
	{
		if (this.kind.isEmpty())
		{
			return "?";
		}
		if (!ReviewLine.QUALITY.equals(this.nature) || this.argument.isEmpty())
		{
			return this.kind;
		}
		return this.kind + " " + TextLimits.truncate(this.argument, Guard.ARGUMENT_DIGEST_LENGTH);
	}
}
