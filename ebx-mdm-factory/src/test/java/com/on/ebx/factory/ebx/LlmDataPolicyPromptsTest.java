package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.on.ebx.factory.capability.CapabilityLlmPass;
import com.on.ebx.factory.deducedscan.ColumnMeasurer;
import com.on.ebx.factory.quality.Detectors;

/**
 * JUnit-free: run the main, a failure throws. The decisions the deduced-scan and capability prompt builders take from
 * the data policy ({@link LlmDataPolicy}), on a marker value that must not be readable in the prompt they build. The
 * staging rule probe has its own check in {@code StagingLlmPassTest} (its refusal text) and
 * {@link #stagingProbeDecision}.
 */
public final class LlmDataPolicyPromptsTest
{
	private static final String MARKER = "Zqxmarker7";

	public static void main(final String[] pArgs)
	{
		LlmDataPolicyPromptsTest.levelToSharing();
		LlmDataPolicyPromptsTest.deducedScanWithheldColumns();
		LlmDataPolicyPromptsTest.capabilityShapes();
		LlmDataPolicyPromptsTest.stagingProbeDecision();
		System.out.println("LlmDataPolicyPromptsTest OK");
	}

	private static void levelToSharing()
	{
		LlmDataPolicyPromptsTest.check("NONE shares nothing", LlmDataPolicy.sharingOf(LlmDataPolicy.Level.NONE) == com.on.ebx.factory.landing.KeyLlmPass.Sharing.NONE);
		LlmDataPolicyPromptsTest.check("SHAPES_ONLY shares shapes", LlmDataPolicy.sharingOf(LlmDataPolicy.Level.SHAPES_ONLY) == com.on.ebx.factory.landing.KeyLlmPass.Sharing.SHAPES);
		LlmDataPolicyPromptsTest.check("SAMPLE_VALUES shares values", LlmDataPolicy.sharingOf(LlmDataPolicy.Level.SAMPLE_VALUES) == com.on.ebx.factory.landing.KeyLlmPass.Sharing.VALUES);
	}

	private static void deducedScanWithheldColumns()
	{
		final LlmDataPolicy policy = new LlmDataPolicy(LlmDataPolicy.Level.SAMPLE_VALUES, null);
		final List<ColumnMeasurer.Measured> measured = new ArrayList<ColumnMeasurer.Measured>();
		for (final String name : new String[] { "id", "properties.bic", "properties.password", "contact.birthdate", "email", "iban" })
		{
			measured.add(new ColumnMeasurer.Measured(name, ColumnMeasurer.TEXT, 1, 1, 1, LlmDataPolicyPromptsTest.MARKER));
		}
		final Set<String> withheld = DeducedScanLlmSuggester.withheldColumns(policy, measured);
		LlmDataPolicyPromptsTest.check("a flattened sensitive name is withheld, on its last segment too",
			withheld.equals(new HashSet<String>(Arrays.asList("properties.bic", "properties.password", "contact.birthdate", "iban"))));
		final Set<String> none = DeducedScanLlmSuggester.withheldColumns(new LlmDataPolicy(LlmDataPolicy.Level.SAMPLE_VALUES, "NONE"), measured);
		LlmDataPolicyPromptsTest.check("an administrator who cleared the list withholds nothing", none.isEmpty());
	}

	private static void capabilityShapes()
	{
		final List<Detectors.ValueCount> values = Arrays.asList(new Detectors.ValueCount(LlmDataPolicyPromptsTest.MARKER, null, 3));
		for (final LlmDataPolicy.Level level : LlmDataPolicy.Level.values())
		{
			for (final boolean sensitive : new boolean[] { false, true })
			{
				final String prompt = LlmDataPolicyPromptsTest.capabilityPrompt(CapabilityLlmSuggester.shapesFor(level, sensitive, values));
				LlmDataPolicyPromptsTest.check("the marker never reaches the capability prompt (" + level + ", sensitive=" + sensitive + ")",
					!prompt.contains(LlmDataPolicyPromptsTest.MARKER));
				final boolean shapeShown = prompt.contains(com.on.ebx.factory.quality.Shapes.of(LlmDataPolicyPromptsTest.MARKER) + " (3)");
				LlmDataPolicyPromptsTest.check("shape shown only when the level is not NONE and the column is not sensitive (" + level + ", " + sensitive + ")",
					shapeShown == (level != LlmDataPolicy.Level.NONE && !sensitive));
			}
		}
	}

	private static String capabilityPrompt(final List<CapabilityLlmPass.ShapeCount> pShapes)
	{
		final CapabilityLlmPass.AttributeInfo attribute = new CapabilityLlmPass.AttributeInfo("code", "STRING", Integer.valueOf(20), Long.valueOf(10), Double.valueOf(100.0),
			Long.valueOf(3), pShapes, Collections.<String>emptyList(), Collections.<String>emptyList());
		final CapabilityLlmPass.EntityInfo entity = new CapabilityLlmPass.EntityInfo("Product", "Product", true, Arrays.asList(attribute),
			Collections.<CapabilityLlmPass.ExistingSuggestion>emptyList());
		return CapabilityLlmPass.userPrompt(entity, Collections.<CapabilityLlmPass.LibrarySummary>emptyList(), Collections.<CapabilityLlmPass.ConnectorSummary>emptyList());
	}

	private static void stagingProbeDecision()
	{
		final com.on.ebx.factory.staging.StagingRules.LandingTableInfo table = new com.on.ebx.factory.staging.StagingRules.LandingTableInfo("t1", "ERP", "CUSTOMER",
			Long.valueOf(100), Arrays.asList(
				new com.on.ebx.factory.staging.StagingRules.LandingColumnInfo("c1", "BIC_CODE", "bic_code", "STRING", false, null, null, null, null),
				new com.on.ebx.factory.staging.StagingRules.LandingColumnInfo("c2", "NAME", "name", "STRING", false, null, null, null, null)));
		final LlmDataPolicy policy = new LlmDataPolicy(LlmDataPolicy.Level.SAMPLE_VALUES, null);
		LlmDataPolicyPromptsTest.check("NONE: no shape", !StagingLlmSuggester.mayShowShapes(LlmDataPolicy.Level.NONE, policy, table, "name"));
		LlmDataPolicyPromptsTest.check("SHAPES_ONLY: a plain column gives its shape", StagingLlmSuggester.mayShowShapes(LlmDataPolicy.Level.SHAPES_ONLY, policy, table, "name"));
		LlmDataPolicyPromptsTest.check("SAMPLE_VALUES: the sensitive source column of a field gives none",
			!StagingLlmSuggester.mayShowShapes(LlmDataPolicy.Level.SAMPLE_VALUES, policy, table, "bic_code"));
	}

	private static void check(final String pWhat, final boolean pOk)
	{
		if (!pOk)
		{
			throw new AssertionError(pWhat);
		}
	}
}
