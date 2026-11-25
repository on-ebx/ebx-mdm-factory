package com.on.ebx.factory.master;

/**
 * Whether {@code ebx.MasterGenerator} may write {@code Project.masterDataspace}/{@code masterDataset}, or
 * must refuse - the coordinator's own review of the first version of this generator ("one parameter is
 * enough and clearer: {@code overwriteTarget=true}") replaced the original two-flag ({@code adopt}/
 * {@code overwriteTarget}) contract with this single one. The demo project on this instance carries
 * hand-typed test values ({@code TestMasterDataspace}/{@code TestMasterDataset}, `docs/MASTER-ZONE.md`) that
 * must never be mistaken for a dataspace this generator actually built.
 * <p>
 * Three outcomes:
 * <ul>
 * <li>{@code Project.masterDataspace} is blank (never generated before) -&gt; proceed, write the computed
 * names.</li>
 * <li>it already holds EXACTLY the computed pair (a prior run of this same generator) -&gt; proceed, same
 * write (idempotent).</li>
 * <li>it holds anything else -&gt; refuse UNLESS the caller passes {@code overwriteTarget=true}, in which case
 * it proceeds anyway and the computed names replace whatever was there. Either way, generation itself always
 * happens under the COMPUTED names - this class, and {@code MasterGenerator}, never read, write, or generate
 * INTO the found (foreign) dataspace; only {@code Project}'s own two pointer FIELDS are ever at stake.</li>
 * </ul>
 * Pure: no EBX class, unit-tested - the refusal message itself is built here (not reassembled ad hoc by
 * {@code MasterGenerator}) so its exact wording ("found", "generated", "only replaces the two fields, nothing
 * is deleted anywhere") is covered by a test rather than left to eyeballing.
 */
public final class MasterTargetDecision
{
	private MasterTargetDecision()
	{
	}

	public static final class Decision
	{
		public final boolean proceed;
		/** Non-null only when {@link #proceed} is false. */
		public final String refusal;

		private Decision(final boolean pProceed, final String pRefusal)
		{
			this.proceed = pProceed;
			this.refusal = pRefusal;
		}
	}

	public static Decision decide(final String pProjectCode, final String pExistingDataspace, final String pExistingDataset, final String pGeneratedDataspace,
		final String pGeneratedDataset, final boolean pOverwriteTarget)
	{
		final boolean blank = pExistingDataspace == null || pExistingDataspace.trim().isEmpty();
		final String existingDataset = pExistingDataset == null ? "" : pExistingDataset.trim();
		final boolean isOwnValue = !blank && pGeneratedDataspace.equals(pExistingDataspace.trim()) && pGeneratedDataset.equals(existingDataset);
		if (blank || isOwnValue || pOverwriteTarget)
		{
			return new Decision(true, null);
		}
		final String refusal = "Project " + pProjectCode + " already has masterDataspace/masterDataset set to " + pExistingDataspace + "/" + pExistingDataset
			+ ", which is not what this generator would produce (" + pGeneratedDataspace + "/" + pGeneratedDataset + "): refusing to overwrite it silently. Pass "
			+ "?overwriteTarget=true to replace those two FIELDS on Project with the generated names - nothing is deleted anywhere, and the generator never reads, "
			+ "writes, or generates into the existing dataspace either way.";
		return new Decision(false, refusal);
	}
}
