package com.on.ebx.factory.staging;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/** JUnit-free, like the tests of the sibling modules: run the main, a failure throws. */
public final class ParentLinkRuleTest
{
	public static void main(final String[] pArgs)
	{
		ParentLinkRuleTest.linkFieldIsTheGeneratedRefName();
		ParentLinkRuleTest.linkAttributeNamesFromFeeds();
		System.out.println("ParentLinkRuleTest OK");
	}

	/** demoBala, 2026-10-21: the feed stores personneRef, the attribute is personne - the matrix compared the two as they were. */
	private static void linkFieldIsTheGeneratedRefName()
	{
		ParentLinkRuleTest.check("personneRef is the link field of personne", ParentLinkRule.isLinkFieldOf("personne", "personneRef"));
		ParentLinkRuleTest.check("the name StagingModel derives is recognised",
			ParentLinkRule.isLinkFieldOf("personne", StagingModel.foreignKeyFieldName("personne", new HashSet<String>(Arrays.asList("rue", "ville")))));
		final Set<String> taken = new HashSet<String>(Arrays.asList("personneref"));
		ParentLinkRuleTest.check("after a collision (personneRef_2) too", ParentLinkRule.isLinkFieldOf("personne", StagingModel.foreignKeyFieldName("personne", taken)));
		ParentLinkRuleTest.check("the attribute name itself is not the field", !ParentLinkRule.isLinkFieldOf("personne", "personne"));
		ParentLinkRuleTest.check("another attribute's field is not", !ParentLinkRule.isLinkFieldOf("personne", "paysRef"));
		ParentLinkRuleTest.check("a longer name sharing the prefix is not", !ParentLinkRule.isLinkFieldOf("personne", "personneRefX"));
		ParentLinkRuleTest.check("empty link field is not", !ParentLinkRule.isLinkFieldOf("personne", ""));
		ParentLinkRuleTest.check("null link field is not", !ParentLinkRule.isLinkFieldOf("personne", null));
	}

	private static void linkAttributeNamesFromFeeds()
	{
		ParentLinkRuleTest.check("the referenced attribute a child feed fills is the parent link",
			ParentLinkRule.linkAttributeNames(Arrays.asList("personne", "pays"), Arrays.asList("personneRef", "personneRef")).equals(Collections.singleton("personne")));
		ParentLinkRuleTest.check("a root entity (no child feed) has none",
			ParentLinkRule.linkAttributeNames(Arrays.asList("pays"), Collections.<String>emptyList()).isEmpty());
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
