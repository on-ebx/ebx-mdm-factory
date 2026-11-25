package com.on.ebx.factory.rejects;

/**
 * Staging load and rejects lot (2026-10-17): the CAUSE of a transfer reject, stored on {@code TransferReject.category} so a screen can word "N rows set aside" truthfully
 * (the wizard used to say "a configured rule stopped them" for rows no rule had touched). One category per reject, the one of its first reason. Pure.
 * <ul>
 * <li>{@link #QUALITY_RULE}: a quality rule of severity REJECT failed on a field value.</li>
 * <li>{@link #DATA_CHECK}: the loader's own checks of the computed value (mandatory but empty, longer than the staging field, a parent that does not exist, a landing row with no key).</li>
 * <li>{@link #COMPUTATION}: the mapping could not compute the row (a transformation failed, a source id came out empty).</li>
 * <li>{@link #EBX_REFUSAL}: EBX refused to save the row (a value that does not convert to the field's type, a constraint of the staging model).</li>
 * <li>{@link #PARENT_REJECTED}: the row belongs to a parent row that was rejected, so it was not written either (no orphan in staging).</li>
 * <li>{@link #OTHER}: anything older or uncoded.</li>
 * </ul>
 */
public final class RejectCategory
{
	public static final String QUALITY_RULE = "QUALITY_RULE";
	public static final String DATA_CHECK = "DATA_CHECK";
	public static final String COMPUTATION = "COMPUTATION";
	public static final String EBX_REFUSAL = "EBX_REFUSAL";
	public static final String PARENT_REJECTED = "PARENT_REJECTED";
	public static final String OTHER = "OTHER";

	/** The categories in the order a sentence lists them. */
	public static final String[] ALL = { RejectCategory.QUALITY_RULE, RejectCategory.EBX_REFUSAL, RejectCategory.DATA_CHECK, RejectCategory.COMPUTATION,
		RejectCategory.PARENT_REJECTED, RejectCategory.OTHER };

	private RejectCategory()
	{
	}

	/** The category itself when known, {@link #OTHER} for an empty or unknown value (a row written before the field existed). */
	public static String of(final String pValue)
	{
		if (pValue != null)
		{
			for (final String category : RejectCategory.ALL)
			{
				if (category.equals(pValue))
				{
					return category;
				}
			}
		}
		return RejectCategory.OTHER;
	}
}
