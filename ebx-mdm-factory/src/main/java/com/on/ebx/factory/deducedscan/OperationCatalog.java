package com.on.ebx.factory.deducedscan;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deduced-scan lot (2026-09-24): pure value objects and filtering for the "How to list" stage. Product rule
 * (contract sec 2, restated in sec "Why"): "Java refuses anything not readOnly=true even if the LLM names
 * it" - {@link #readOnlyOperations} is the ONE place that rule is enforced, so every caller (the tool handler
 * that validates the model's choice, a future re-check before {@code connectors_call}) goes through it rather
 * than re-deriving the filter. A connector whose {@code connectors_describe} answer has not yet been upgraded
 * with the {@code readOnly} field (contract sec 2, a separate module/lot) reads every operation as NOT
 * read-only (a missing/unparsable flag defaults to {@code false}, never to "trust it") - see {@link
 * OperationInfo#OperationInfo}: the safer failure mode is "the deduced scan finds nothing to list yet",
 * never "it silently calls a write operation".
 */
public final class OperationCatalog
{
	private OperationCatalog()
	{
	}

	/**
	 * One operation of {@code connectors_describe}'s {@code operations[]}, as much as this stage needs.
	 * Bug fix 2026-09-26: {@code connectors_describe} answers a real operation's inputs as TWO separate
	 * string arrays, {@code requiredInputs}/{@code optionalInputs} (plus {@code kind}/{@code description}) -
	 * not a single {@code inputs}/{@code parameters}/{@code params} array of objects, which {@link
	 * com.on.ebx.factory.ebx.DeducedScanOperations} used to assume exclusively. Measured live on OpenFDA
	 * (project DeducedTest, PipelineStep 405): every operation came back with an empty {@link #inputNames},
	 * so the model was never told any operation needed an input at all, and picked all three (udi,
	 * deviceSearch, drug - each a single-record lookup by DI/brand/NDC) as "listings". The distinction
	 * between required and optional matters on its own, independent of the parsing fix: a listing operation
	 * that in truth NEEDS a selecting value (a brand, an id) to run at all is a search, not a listing - see
	 * {@link #missingRequiredInputs}/{@link #filterCallable}, the callability guard this bug fix adds.
	 */
	public static final class OperationInfo
	{
		public final String name;
		public final boolean readOnly;
		/** e.g. {@code "READ"}/{@code "SEARCH"}/{@code "WRITE"} - {@code null} when the source shape does not carry one (legacy connector, or a hand-built test fixture). Informational only: {@link #readOnly} remains the sole enforcement point, never this. */
		public final String kind;
		/** {@code null} when not carried by the source shape. */
		public final String description;
		/** Input names the operation cannot run without - what {@link #missingRequiredInputs} checks a {@link ListChoice} against. */
		public final List<String> requiredInputNames;
		/** Input names the operation accepts but does not require. */
		public final List<String> optionalInputNames;
		/** The type the connector declares for an input, by input name (audit F-17: {@code integer} makes {@link InputValueRules} demand a number); an input whose type the connector does not say is absent. Never null. */
		public final Map<String, String> inputTypes;
		/** {@link #requiredInputNames} + {@link #optionalInputNames}, in that order - what {@link #firstViolation} checks a chosen input key against (a chosen input may name any declared input, required or not). */
		public final List<String> inputNames;

		/** Legacy 3-arg shape, kept for callers/tests that only ever knew one flat input-name list (a connector whose {@code connectors_describe} answer predates the {@code requiredInputs}/{@code optionalInputs} split - see {@link com.on.ebx.factory.ebx.DeducedScanOperations}'s own fallback). Every name is treated as OPTIONAL: with no way to tell which inputs are actually required, the safer default is "the callability guard never blocks on it", not "block on everything". */
		public OperationInfo(final String pName, final boolean pReadOnly, final List<String> pInputNames)
		{
			this(pName, pReadOnly, java.util.Collections.<String>emptyList(), pInputNames, null, null);
		}

		public OperationInfo(final String pName, final boolean pReadOnly, final List<String> pRequiredInputNames, final List<String> pOptionalInputNames,
			final String pDescription, final String pKind)
		{
			this(pName, pReadOnly, pRequiredInputNames, pOptionalInputNames, pDescription, pKind, null);
		}

		public OperationInfo(final String pName, final boolean pReadOnly, final List<String> pRequiredInputNames, final List<String> pOptionalInputNames,
			final String pDescription, final String pKind, final Map<String, String> pInputTypes)
		{
			this.inputTypes = pInputTypes == null ? new LinkedHashMap<String, String>() : pInputTypes;
			this.name = pName;
			this.readOnly = pReadOnly;
			this.kind = pKind;
			this.description = pDescription;
			this.requiredInputNames = pRequiredInputNames == null ? java.util.Collections.<String>emptyList() : pRequiredInputNames;
			this.optionalInputNames = pOptionalInputNames == null ? java.util.Collections.<String>emptyList() : pOptionalInputNames;
			final List<String> all = new ArrayList<String>(this.requiredInputNames);
			all.addAll(this.optionalInputNames);
			this.inputNames = all;
		}
	}

	/** First operation of {@code pOperations} named {@code pName}, or {@code null}. */
	public static OperationInfo findByName(final List<OperationInfo> pOperations, final String pName)
	{
		if (pOperations == null || pName == null)
		{
			return null;
		}
		for (final OperationInfo operation : pOperations)
		{
			if (operation != null && pName.equals(operation.name))
			{
				return operation;
			}
		}
		return null;
	}

	/** Only the {@code readOnly=true} operations of {@code pOperations} - see class Javadoc. Never throws, never returns a non-read-only entry. */
	public static List<OperationInfo> readOnlyOperations(final List<OperationInfo> pOperations)
	{
		final List<OperationInfo> out = new ArrayList<OperationInfo>();
		if (pOperations == null)
		{
			return out;
		}
		for (final OperationInfo operation : pOperations)
		{
			if (operation != null && operation.readOnly)
			{
				out.add(operation);
			}
		}
		return out;
	}

	/** {@code true} when {@code pReadOnlyOperations} (already filtered by {@link #readOnlyOperations}) has no entry at all - the contract's "lookup-only connection" case: "no list operation -&gt; enrichment, not a source, nothing written". This function only reports the absence of ANY readOnly operation; a real project still lets the language model choose WHICH of them lists records (domain knowledge, never a Java name/keyword test - product rule, contract "Why"). */
	public static boolean hasNoReadOnlyOperation(final List<OperationInfo> pReadOnlyOperations)
	{
		return pReadOnlyOperations == null || pReadOnlyOperations.isEmpty();
	}

	/** One operation name the model chose to list records with, plus the literal input values it filled in (contract sec "Why": "the LLM chooses ... with their inputs"). */
	public static final class ListChoice
	{
		public final String operation;
		public final Map<String, String> inputs;

		public ListChoice(final String pOperation, final Map<String, String> pInputs)
		{
			this.operation = pOperation;
			this.inputs = pInputs == null ? new LinkedHashMap<String, String>() : pInputs;
		}
	}

	/**
	 * Refuses a {@link ListChoice} whose operation is not among {@code pReadOnlyOperations} by NAME - the
	 * actual enforcement of "Java refuses anything not readOnly=true even if the LLM named it" (class
	 * Javadoc): a model that names a write (or unknown) operation gets a plain refusal message it can act on,
	 * never a silent skip or a call that reaches {@code connectors_call}. Also refuses an input key the
	 * operation never declared (defence in depth - the tool schema should already constrain this, but a
	 * language model's JSON is never trusted twice removed).
	 */
	public static String firstViolation(final ListChoice pChoice, final List<OperationInfo> pReadOnlyOperations)
	{
		if (pChoice == null || pChoice.operation == null || pChoice.operation.trim().isEmpty())
		{
			return "No operation named.";
		}
		OperationInfo matched = null;
		for (final OperationInfo candidate : pReadOnlyOperations)
		{
			if (candidate.name.equals(pChoice.operation))
			{
				matched = candidate;
				break;
			}
		}
		if (matched == null)
		{
			return "'" + pChoice.operation + "' is not a read-only listing operation of this connection.";
		}
		for (final String inputKey : pChoice.inputs.keySet())
		{
			if (!matched.inputNames.contains(inputKey))
			{
				return "'" + pChoice.operation + "' does not declare an input named '" + inputKey + "'.";
			}
		}
		return OperationCatalog.valueViolation(matched, pChoice);
	}

	/**
	 * Audit F-17: the first input VALUE of {@code pChoice} that {@link InputValueRules} refuses for {@code pOperation}, as a sentence that names the operation and the
	 * input, or null. Checked before the first call to the external system and again when the operation is sampled, so a value the model picked is never sent unread.
	 */
	public static String valueViolation(final OperationInfo pOperation, final ListChoice pChoice)
	{
		if (pOperation == null || pChoice == null)
		{
			return null;
		}
		for (final Map.Entry<String, String> entry : pChoice.inputs.entrySet())
		{
			final String why = InputValueRules.firstViolation(entry.getKey(), entry.getValue(), pOperation.inputTypes.get(entry.getKey()));
			if (why != null)
			{
				return "'" + pOperation.name + "' input '" + entry.getKey() + "': " + why;
			}
		}
		return null;
	}

	/**
	 * Bug fix 2026-09-26 (contract point 3, the callability guard): required input names of {@code
	 * pOperation} that {@code pChoice} did not fill with a non-blank value. {@code firstViolation} above
	 * only checks that a chosen input KEY is one the operation actually declares - it never checked that
	 * every REQUIRED key got a real value, which is how OpenFDA's {@code deviceSearch} (required input
	 * {@code brand}) was chosen and "called" with no brand at all. An operation the model chooses but can't
	 * actually run without a selecting value is a search/lookup, not a listing (see {@link #filterCallable}).
	 * Empty result = callable. {@code pOperation == null} answers empty (nothing to check) - the caller is
	 * expected to have already rejected an unmatched choice via {@link #firstViolation}.
	 */
	public static List<String> missingRequiredInputs(final OperationInfo pOperation, final ListChoice pChoice)
	{
		final List<String> out = new ArrayList<String>();
		if (pOperation == null || pChoice == null)
		{
			return out;
		}
		for (final String required : pOperation.requiredInputNames)
		{
			final String value = pChoice.inputs.get(required);
			if (value == null || value.trim().isEmpty())
			{
				out.add(required);
			}
		}
		return out;
	}

	/** A one-line, per-operation explanation of why {@code pOperation} can't be called as chosen - {@code null} when it can ({@code pMissingRequiredInputs} empty). */
	public static String notCallableReason(final OperationInfo pOperation, final List<String> pMissingRequiredInputs)
	{
		if (pOperation == null || pMissingRequiredInputs == null || pMissingRequiredInputs.isEmpty())
		{
			return null;
		}
		final StringBuilder names = new StringBuilder();
		for (final String name : pMissingRequiredInputs)
		{
			if (names.length() > 0)
			{
				names.append(", ");
			}
			names.append('\'').append(name).append('\'');
		}
		return pOperation.name + " needs a value for " + names + " to list anything: looks like a search, not a listing.";
	}

	/** {@link #filterCallable}'s answer: the {@link ListChoice}s that can actually be called, and one reason per one that can't. */
	public static final class CallabilityResult
	{
		public final List<ListChoice> callable = new ArrayList<ListChoice>();
		public final List<String> notCallableReasons = new ArrayList<String>();
	}

	/**
	 * Bug fix 2026-09-26 (contract point 3): partitions {@code pChosen} into the choices that can actually be
	 * called (every required input of their operation got a non-blank value) and the ones that can't, each
	 * with a {@link #notCallableReason}. A choice naming an operation not found in {@code pOperations} is
	 * also reported as not callable (defence in depth - {@link #firstViolation} should already have refused
	 * it upstream). The caller (contract point 3) treats an EMPTY {@link CallabilityResult#callable} as the
	 * enrichment/lookup-only outcome - the same end state as {@code pChosen} itself being empty - never as a
	 * failure.
	 */
	public static CallabilityResult filterCallable(final List<ListChoice> pChosen, final List<OperationInfo> pOperations)
	{
		final CallabilityResult out = new CallabilityResult();
		if (pChosen == null)
		{
			return out;
		}
		for (final ListChoice choice : pChosen)
		{
			final OperationInfo matched = OperationCatalog.findByName(pOperations, choice == null ? null : choice.operation);
			if (matched == null)
			{
				out.notCallableReasons.add("'" + (choice == null ? "" : choice.operation) + "' is not among the read-only operations.");
				continue;
			}
			final List<String> missing = OperationCatalog.missingRequiredInputs(matched, choice);
			final String badValue = OperationCatalog.valueViolation(matched, choice);
			if (badValue != null)
			{
				// audit F-17: reported in the step, no call made
				out.notCallableReasons.add(badValue);
			}
			else if (missing.isEmpty())
			{
				out.callable.add(choice);
			}
			else
			{
				out.notCallableReasons.add(OperationCatalog.notCallableReason(matched, missing));
			}
		}
		return out;
	}
}
