package com.on.ebx.factory.ebx.ui;

import java.util.Arrays;

/** UX lot U6: the two pure helpers of the Mapping matrix service - the posted id list of a group action, and the script that remembers "Only what's left to do". */
public final class MappingMatrixUserServiceTest
{
	public static void main(final String[] pArgs)
	{
		MappingMatrixUserServiceTest.idsOfAGroupActionAreSplitAndCapped();
		MappingMatrixUserServiceTest.todoMemoryGoesThroughThePerUserStore();
		MappingMatrixUserServiceTest.theScrollOffsetOfASubmitIsParsedDefensively();
		System.out.println("MappingMatrixUserServiceTest OK");
	}

	private static void idsOfAGroupActionAreSplitAndCapped()
	{
		MappingMatrixUserServiceTest.check("ids are split on commas, blanks dropped", MappingMatrixUserService.splitIds(" 12,13 ,,14").equals(Arrays.asList("12", "13", "14")));
		MappingMatrixUserServiceTest.check("null or blank give nothing", MappingMatrixUserService.splitIds(null).isEmpty() && MappingMatrixUserService.splitIds("  ").isEmpty());
		final StringBuilder many = new StringBuilder();
		for (int i = 0; i < 300; i++)
		{
			many.append(i).append(',');
		}
		MappingMatrixUserServiceTest.check("a hand-made request cannot post more than 100", MappingMatrixUserService.splitIds(many.toString()).size() == 100);
	}

	private static void todoMemoryGoesThroughThePerUserStore()
	{
		final String script = MappingMatrixUserService.todoMemoryScript("jdoe", "UxCrmSf", false, true);
		MappingMatrixUserServiceTest.check("the wave 13 store of the login, local area, under a project-keyed name", script.contains("window.mdmfStore.forUser('jdoe').local") && script.contains("'matrixTodo:'+'UxCrmSf'"));
		MappingMatrixUserServiceTest.check("it defines the remember function and applies a remembered choice when something is left",
			script.contains("window.mdmmTodoRemember=function(on)") && script.contains("if(true&&(v==='1'||v==='0')&&window.mdmmApplyTodo){window.mdmmApplyTodo(v==='1');}"));
		MappingMatrixUserServiceTest.check("with nothing left a remembered choice is never applied (no blank grid)", MappingMatrixUserService.todoMemoryScript("jdoe", "P", false, false).contains("if(false&&"));
		MappingMatrixUserServiceTest.check("after a bulk accept the default is written back as ON, nothing is read",
			MappingMatrixUserService.todoMemoryScript("jdoe", "P", true, true).contains("ST.set(k,'1');") && !MappingMatrixUserService.todoMemoryScript("jdoe", "P", true, true).contains("ST.get(k)"));
		MappingMatrixUserServiceTest.check("a session with no login gets an inert store (it reads and writes nothing)", MappingMatrixUserService.todoMemoryScript("", "P", false, true).contains("forUser('')"));
	}

	/** Staging-screens lot (2026-10-17): the page position posted with a submit ({@code mdmmScroll}) is a small non-negative number; anything else reads as "none". */
	private static void theScrollOffsetOfASubmitIsParsedDefensively()
	{
		MappingMatrixUserServiceTest.check("a posted offset is read", MappingMatrixUserService.parseScroll("1240") == 1240 && MappingMatrixUserService.parseScroll(" 0 ") == 0);
		MappingMatrixUserServiceTest.check("empty, null, negative, text and huge values mean none",
			MappingMatrixUserService.parseScroll("") == -1 && MappingMatrixUserService.parseScroll(null) == -1 && MappingMatrixUserService.parseScroll("-5") == -1
				&& MappingMatrixUserService.parseScroll("abc") == -1 && MappingMatrixUserService.parseScroll("99999999999") == -1);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
