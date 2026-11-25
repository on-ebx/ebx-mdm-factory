package com.on.ebx.factory.core;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Every place where the module decides what to do from the WORDING of another component's error (audit F-27), in one class, each with where the text
 * comes from and when it was read. Such a decision breaks without a trace when the other component rewrites its message - the retry is not made, the
 * "already gone" is reported as a failure - so none is written inline any more: a call site asks this class, and {@code ForeignWordingTest} pins every
 * marker to a sample of the real message and to a message that must NOT match, and scans the sources for a marker written anywhere else.
 * <p>
 * Where the component offers a TYPED answer the module uses it instead, and this class says so: the MCP servers' soft misses carry a {@code code}
 * ({@code TABLE_NOT_FOUND}, {@code MODEL_NOT_REGISTERED}... read from the {@code code} field, never from the message); the add-on's index failures are
 * recognised by the exception class first ({@link #ADDON_INDEX_PACKAGE}). What is left here has no typed alternative on 6.3.0-RC2: a plain
 * {@code InvalidStateException} / an MCP error text.
 * <p>
 * Pure, no EBX type.
 */
public final class ForeignWording
{
	// ------------------------------------------------------------------------------------------ Match and Merge (ebx-addons.jar)

	/**
	 * The add-on's {@code InvalidStateException} when a scoped {@code matchRecords} cannot start because another process still holds the master
	 * dataspace. Measured live 2026-09-27 (PersVol7, docs/MATCHING.md section 41). No typed alternative: the same exception class carries "matching paused"
	 * and "policy inactive". Lower case, compared case-insensitively.
	 */
	public static final String ADDON_DATASPACE_LOCKED = "dataspace is locked";

	/**
	 * The words {@code MergeOperations.mergeGroup} uses when the merge policy is not marked "Used for manual merge" (Javadoc of {@code MergeOperations},
	 * the documented precondition). Never captured from a real refusal: a message without these words falls back to the generic merge failure.
	 */
	public static final String ADDON_MANUAL_MERGE = "manual merge";

	/** A business table whose Lucene index lacks the metadataset-linked fields: the add-on names {@code Linked_Field_State} in its exception text (measured 2026-09-23). */
	public static final String ADDON_LINKED_FIELD = "Linked_Field_";

	/** The kernel's candidate-search package: an exception raised there is recognised by its CLASS, which is the typed check. */
	public static final String ADDON_INDEX_PACKAGE = "com.orchestranetworks.cce";

	// ------------------------------------------------------------------------------------- Connectors / data-model MCP servers

	/**
	 * The Connectors server's sentence for a mapping it cannot find: {@code "no mapping <id>"} from {@code connectors_mapping_delete}, {@code "No mapping '<id>'"}
	 * from its read tools. Lower case. Measured 2026-09-28; the tools answer an error TEXT, there is no code.
	 */
	public static final String CONNECTORS_NO_MAPPING = "no mapping ";

	/** The data-model server's sentence for a model it cannot find ({@code "Data model not found: '<name>'. Call list_data_models ..."}), thrown as a JSON-RPC error. Lower case. Measured 2026-09-29. */
	public static final String DATA_MODEL_NOT_FOUND = "data model not found";

	/**
	 * What {@code unset_element_property} says when the item to remove is already gone. UNVERIFIED (never captured live): a conservative reading of the
	 * words of the data-model server's errors, used only by the idempotent removal of a declaration; a rollback never calls it.
	 */
	public static final List<String> DATA_MODEL_ALREADY_REMOVED = Collections.unmodifiableList(Arrays.asList("not found", "does not exist", "no such", "unknown item"));

	// -------------------------------------------------------------------------------------------------- on-ebx-mapping library

	/** The mapping library's {@code IllegalArgumentException} for a transformation name it does not know (a proposal of the language model): its message starts with this. */
	public static final String MAPPING_UNKNOWN_TRANSFORMATION = "Unknown transformation";

	private ForeignWording()
	{
	}

	/** Whether {@code pMessage} is the add-on's "the dataspace is locked" refusal ({@link #ADDON_DATASPACE_LOCKED}). Null-safe. */
	public static boolean isDataspaceLocked(final String pMessage)
	{
		return pMessage != null && pMessage.toLowerCase(Locale.ROOT).contains(ForeignWording.ADDON_DATASPACE_LOCKED);
	}

	/** Whether {@code pMessage} names the merge policy's "manual merge" flag ({@link #ADDON_MANUAL_MERGE}). Null-safe. */
	public static boolean isManualMergePolicyRefusal(final String pMessage)
	{
		return pMessage != null && pMessage.toLowerCase(Locale.ENGLISH).contains(ForeignWording.ADDON_MANUAL_MERGE);
	}

	/**
	 * Whether {@code pEx}, or anything in its cause chain, is a failure of the add-on's own candidate search: raised from a class of
	 * {@link #ADDON_INDEX_PACKAGE} (the typed check), or carrying {@link #ADDON_LINKED_FIELD} in its message (the one defect this was written for).
	 */
	public static boolean isAddonIndexFailure(final Throwable pEx)
	{
		Throwable current = pEx;
		int depth = 0;
		while (current != null && depth++ < 50)
		{
			final String message = current.getMessage();
			if (message != null && message.contains(ForeignWording.ADDON_LINKED_FIELD))
			{
				return true;
			}
			if (current.getClass().getName().startsWith(ForeignWording.ADDON_INDEX_PACKAGE))
			{
				return true;
			}
			current = current.getCause();
		}
		return false;
	}

	/** Whether a thrown message contains {@code pAbsentMarker} (one of {@link #CONNECTORS_NO_MAPPING}, {@link #DATA_MODEL_NOT_FOUND}; lower case), case-insensitively. Null-safe. */
	public static boolean thrownMeansAbsent(final String pMessage, final String pAbsentMarker)
	{
		return pMessage != null && pMessage.toLowerCase(Locale.ENGLISH).contains(pAbsentMarker);
	}

	/** Whether the whole {@code error} field of a {@code connectors_mapping_delete} answer BEGINS with the "no mapping" sentence (it must not merely contain a similar wording). */
	public static boolean startsWithNoMapping(final String pText)
	{
		return pText != null && pText.trim().toLowerCase(Locale.ENGLISH).startsWith(ForeignWording.CONNECTORS_NO_MAPPING);
	}

	/** Whether the text of a failed {@code unset_element_property} reads as "this item was already gone" ({@link #DATA_MODEL_ALREADY_REMOVED}). Null-safe. */
	public static boolean looksAlreadyRemoved(final String pErrorText)
	{
		if (pErrorText == null)
		{
			return false;
		}
		final String lower = pErrorText.toLowerCase(Locale.ROOT);
		for (final String marker : ForeignWording.DATA_MODEL_ALREADY_REMOVED)
		{
			if (lower.contains(marker))
			{
				return true;
			}
		}
		return false;
	}

	/** Whether {@code pEx} is the mapping library's "unknown transformation" refusal ({@link #MAPPING_UNKNOWN_TRANSFORMATION}): an {@code IllegalArgumentException} whose message starts with it. */
	public static boolean isUnknownTransformation(final Exception pEx)
	{
		return pEx instanceof IllegalArgumentException && pEx.getMessage() != null && pEx.getMessage().startsWith(ForeignWording.MAPPING_UNKNOWN_TRANSFORMATION);
	}
}
