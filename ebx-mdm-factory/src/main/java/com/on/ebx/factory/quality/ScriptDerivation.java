package com.on.ebx.factory.quality;

/**
 * {@code StagingAttribute.derivation} value for a field whose value EBX itself computes from a published script library function - the
 * sibling of {@link CrossSystemFold#DERIVATION_FOLD_KEY} and {@link ValidValueDerivation#DERIVATION_VALID_VALUE}, chosen over a Java-side
 * computation for exactly the reason those two are computed in Java: normalising a cross-system value (e.g. a phone number written
 * {@code +33612345601} by one source system and {@code 06.12.34.56.01} by another) needs a REAL transformation, not a fold/pass-through, and
 * the one place that transformation should live is the SAME published EBX Script library a {@code SCRIPT_CONSTRAINT} already validates with
 * ({@code Fasttrack.lib.phone.toE164}, one definition, CLAUDE.md &sect;3 - "never a throwaway script").
 * <p>
 * <b>Design decision D (measured, not B/A) - docs/SCRIPT-DERIVATION.md &sect;1 has the full comparison.</b> In short: the transfer engine (Java)
 * cannot call an EBX Script library function directly - there is no Java API for it. Candidate A (a published {@code restService} wrapper called
 * over HTTP by the engine) was rejected: one more permanent published endpoint per function (CLAUDE.md &sect;3), a real outbound HTTP round trip
 * per row, and credentials the engine would have to hold. Candidate D reuses the recipe CLAUDE.md &sect;5 already measured for a script
 * CONSTRAINT: declare on the data-model server, author on the scripting server, publish.
 * <p>
 * <b>Lot T1 (2026-10-04) changed WHAT is declared, not the decision.</b> The first version declared a computed function field ({@code
 * set_script_function}, script type {@code functionField}). Measured live on 6.3.0-rc2: a function field is INDEXED by default and an indexed one
 * cannot {@code import} a script library ({@code SCRIPT_LIBRARY_ACCESS} not available), while matching needs the field indexed. So the derived
 * attribute is now an ORDINARY STORED field, fed by two EBX-Script table triggers - {@code beforeCreate} and {@code beforeModify}, script types that
 * do have {@code SCRIPT_LIBRARY_ACCESS} - declared with {@code set_script_trigger} and authored by {@code capability.DerivationBodyBuilder} (docs/SCRIPT-DERIVATION.md
 * &sect;10). One mechanism serves real time and bulk loads; rows that exist when the derivation is applied are recomputed by a backfill pass
 * ({@code ebx.DerivationBackfillPass}), since a trigger only fires on a write. A stored value is read by {@code ebx.MasterLoader} like any other
 * staging field, so the master load needed no change.
 * <p>
 * <b>The one place THIS derivation kind must be told apart from the other two</b>: unlike {@code FOLD_KEY}/{@code VALID_VALUE} (computed by
 * {@code staging.DerivedFieldRule} in Java, at load time), a {@code SCRIPT} field is computed BY EBX ITSELF once its triggers are declared and
 * published. {@code ebx.StagingLoader} must therefore never write a value into it (a value written would at best be overwritten by the trigger,
 * at worst be the stale copy it is meant to replace) and must never force a {@code null} default into it while the derivation is applied (see
 * {@code transfer.NullFillRule#defaultsFor}'s own Javadoc and {@code ebx.StagingLoader#readDerivedFields}, both of which exclude this constant).
 * While the derivation is NOT applied no trigger exists and the field is held empty ({@code staging.ScriptDerivationGate}, lot M5).
 */
public final class ScriptDerivation
{
	private ScriptDerivation()
	{
	}

	/** {@code StagingAttribute.derivation} value this class documents - see the class Javadoc. */
	public static final String DERIVATION_SCRIPT = "SCRIPT";
}
