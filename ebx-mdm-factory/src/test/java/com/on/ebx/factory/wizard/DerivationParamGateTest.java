package com.on.ebx.factory.wizard;

import java.util.ArrayList;
import java.util.List;

import com.on.ebx.factory.capability.DerivationParameters;

/** JUnit-free: run the main, a failure throws. Fix/derivation-apply-backfill (2026-10-17, lot G1, item 3). */
public final class DerivationParamGateTest
{
	public static void main(final String[] pArgs)
	{
		DerivationParamGateTest.listsOnlyWhatWaitsForAParameter();
		DerivationParamGateTest.showsTheBlockWhereTheBuildIsAsked();
		DerivationParamGateTest.neverCallsTheStagingDoneWithoutTheWaitingParameter();
		DerivationParamGateTest.saysItBeforeTheAccept();
		System.out.println("DerivationParamGateTest OK");
	}

	private static StillToDecide.CheckItem item(final String pId, final String pBinding)
	{
		final StillToDecide.CheckItem item = new StillToDecide.CheckItem();
		item.rowId = pId;
		item.nature = "SCRIPT_DERIVATION";
		item.parameters = DerivationParameters.settable(pBinding);
		return item;
	}

	private static List<StillToDecide.CheckItem> toApply()
	{
		final List<StillToDecide.CheckItem> out = new ArrayList<StillToDecide.CheckItem>();
		// capability 226: a literal and a parameter to be provided
		out.add(DerivationParamGateTest.item("226", "{\"value\":\"mobile\",\"defaultCountry\":{\"toBeProvided\":\"country of the national numbers\"}}"));
		// an accepted derivation whose only parameter is a literal: ready to apply, nothing to ask
		out.add(DerivationParamGateTest.item("227", "{\"value\":\"lastName\",\"locale\":{\"literal\":\"fr\"}}"));
		// a second one waiting, same parameter name
		out.add(DerivationParamGateTest.item("229", "{\"value\":\"phone\",\"defaultCountry\":{\"toBeProvided\":\"country\"}}"));
		return out;
	}

	private static void listsOnlyWhatWaitsForAParameter()
	{
		final List<StillToDecide.CheckItem> waiting = DerivationParamGate.waiting(DerivationParamGateTest.toApply());
		DerivationParamGateTest.check("two wait", waiting.size() == 2 && "226".equals(waiting.get(0).rowId) && "229".equals(waiting.get(1).rowId));
		DerivationParamGateTest.check("a distinct name once", DerivationParamGate.parameterNames(waiting).toString().equals("[defaultCountry]"));
		DerivationParamGateTest.check("null reads as empty", DerivationParamGate.waiting(null).isEmpty() && DerivationParamGate.parameterNames(null).isEmpty());
	}

	private static void showsTheBlockWhereTheBuildIsAsked()
	{
		for (final WizardRules.Step4Primary state : new WizardRules.Step4Primary[] { WizardRules.Step4Primary.REVIEW, WizardRules.Step4Primary.BUILD, WizardRules.Step4Primary.CONTINUE })
		{
			DerivationParamGateTest.check("shown in " + state, DerivationParamGate.showTopBlock(state, 1));
			DerivationParamGateTest.check("not shown in " + state + " with nothing waiting", !DerivationParamGate.showTopBlock(state, 0));
		}
		DerivationParamGateTest.check("not while a build runs", !DerivationParamGate.showTopBlock(WizardRules.Step4Primary.LOADING, 2));
		DerivationParamGateTest.check("not while the proposal runs", !DerivationParamGate.showTopBlock(WizardRules.Step4Primary.RUNNING, 2));
		DerivationParamGateTest.check("not before any proposal", !DerivationParamGate.showTopBlock(WizardRules.Step4Primary.PROPOSE, 2));
		DerivationParamGateTest.check("an extra line under review and build only", DerivationParamGate.addsWaitingLine(WizardRules.Step4Primary.REVIEW, 1)
			&& DerivationParamGate.addsWaitingLine(WizardRules.Step4Primary.BUILD, 1) && !DerivationParamGate.addsWaitingLine(WizardRules.Step4Primary.CONTINUE, 1)
			&& !DerivationParamGate.addsWaitingLine(WizardRules.Step4Primary.BUILD, 0));
	}

	private static void neverCallsTheStagingDoneWithoutTheWaitingParameter()
	{
		// the live sentence: "The staging is built and loaded" was the text under the card while capability 226 waited
		DerivationParamGateTest.check("a waiting parameter wins over everything", "wizard.step4.primary.next.paramWaiting.one".equals(DerivationParamGate.continueTextKey(0, 1))
			&& "wizard.step4.primary.next.paramWaiting.one".equals(DerivationParamGate.continueTextKey(3, 1)));
		DerivationParamGateTest.check("several", "wizard.step4.primary.next.paramWaiting.other".equals(DerivationParamGate.continueTextKey(3, 2)));
		DerivationParamGateTest.check("derivations to apply, none waiting", "wizard.step4.primary.next.toApply.one".equals(DerivationParamGate.continueTextKey(1, 0))
			&& "wizard.step4.primary.next.toApply.other".equals(DerivationParamGate.continueTextKey(2, 0)));
		DerivationParamGateTest.check("only then the plain sentence", "wizard.step4.primary.next.continue".equals(DerivationParamGate.continueTextKey(0, 0)));
	}

	private static void saysItBeforeTheAccept()
	{
		final StillToDecide.CheckItem proposed = DerivationParamGateTest.item("230", "{\"value\":\"mobile\",\"defaultCountry\":{\"toBeProvided\":\"country\"}}");
		DerivationParamGateTest.check("needs defaultCountry", DerivationParamGate.neededBeforeAccept(proposed).toString().equals("[defaultCountry]"));
		DerivationParamGateTest.check("a literal needs nothing", DerivationParamGate.neededBeforeAccept(DerivationParamGateTest.item("231", "{\"value\":\"mobile\",\"c\":{\"literal\":\"FR\"}}")).isEmpty());
		DerivationParamGateTest.check("a constraint or a service carries no parameter", DerivationParamGate.neededBeforeAccept(new StillToDecide.CheckItem()).isEmpty()
			&& DerivationParamGate.neededBeforeAccept(null).isEmpty());
	}

	private static void check(final String pMessage, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}
}
