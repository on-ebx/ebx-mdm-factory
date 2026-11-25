package com.on.ebx.factory.rejects;

import java.util.ArrayList;
import java.util.List;

/**
 * EBX refusal classes lot H4 (2026-10-17): what the TEXT of a failed save cannot say, read from the exception itself. EBX prints a failed {@code Procedure} as the user printer's
 * sentence ("Unexpected error... > Caused by: Dataset 'DistributionOutbox' not found in dataspace 'Personnes_Master'") and drops the class of the cause, so a table trigger that
 * failed reads exactly like any runtime error. Two facts are visible only in the exception, and this class turns them into ONE header line in front of the stored text:
 * <ul>
 * <li><b>trigger</b>: a stack frame of the trigger machinery ({@code com.onwbp.adaptation.Trg*TriggerApply}, {@code com.orchestranetworks.dsl.script.trigger.*}) is under the failure,
 * i.e. the error was raised while a table trigger ran. The script trigger's name and line are the file name and line of its own frame, measured on the live kernel.log of
 * 2026-09-27: {@code ScriptTableTriggerAfterCreate$$74.onHandle(DistributionAfterCreate:26)}. A Java trigger is named by its class.</li>
 * <li><b>constraint</b>: the exception is a {@code UowErrorWithUserMessage} (the error EBX raises for a blocking rule: a message of its own, no wrapper) or a
 * {@code ConstraintViolationException} / {@code ConstraintWrapperException} is in the chain.</li>
 * </ul>
 * The header is {@code [mdmf-hint trigger=<name>;line=<n>]} or {@code [mdmf-hint constraint]}, followed by a line break and EBX's text. {@link RejectClassifier} reads it and
 * removes it, so the raw detail kept on the reject is EBX's own text. A text with no header (older rows, a failure with no such fact) is read from its words alone. Pure: it
 * takes a {@link Throwable}, so it is tested with an exception whose stack is the one copied from the log.
 */
public final class RefusalHints
{
	static final String OPEN = "[mdmf-hint ";
	private static final int MAX_CAUSES = 10;

	public final boolean trigger;
	public final boolean constraint;
	public final String name;
	public final int line;
	/** The text without its header. */
	public final String text;

	private RefusalHints(final boolean pTrigger, final boolean pConstraint, final String pName, final int pLine, final String pText)
	{
		this.trigger = pTrigger;
		this.constraint = pConstraint;
		this.name = pName == null ? "" : pName;
		this.line = pLine;
		this.text = pText == null ? "" : pText;
	}

	// ----------------------------------------------------------------------------------------------- writing

	/** The header for {@code pException} ("" when it carries no fact worth keeping), ending with a line break so it can be put in front of the text. */
	public static String header(final Throwable pException)
	{
		if (pException == null)
		{
			return "";
		}
		final List<Throwable> chain = RefusalHints.chain(pException);
		boolean constraint = false;
		for (final Throwable one : chain)
		{
			final String className = one.getClass().getName();
			if (className.endsWith("ConstraintViolationException") || className.endsWith("ConstraintWrapperException"))
			{
				constraint = true;
			}
		}
		for (final Throwable one : chain)
		{
			final StackTraceElement[] stack = one.getStackTrace();
			final int boundary = RefusalHints.triggerBoundary(stack);
			if (boundary >= 0)
			{
				String name = "";
				int line = 0;
				for (int i = 0; i < boundary; i++)
				{
					final StackTraceElement frame = stack[i];
					final String file = frame.getFileName();
					if (frame.getClassName().startsWith("com.orchestranetworks.dsl.script.trigger.handler") && file != null && !file.endsWith(".java"))
					{
						name = file;
						line = frame.getLineNumber();
						break;
					}
				}
				if (name.isEmpty())
				{
					for (int i = 0; i < boundary; i++)
					{
						if (RefusalHints.isUserClass(stack[i].getClassName()))
						{
							name = RefusalHints.simpleName(stack[i].getClassName());
							line = stack[i].getLineNumber();
							break;
						}
					}
				}
				return RefusalHints.OPEN + "trigger=" + RefusalHints.clean(name) + ";line=" + Math.max(0, line) + "]\n";
			}
		}
		if (constraint || pException.getClass().getName().endsWith("UowErrorWithUserMessage"))
		{
			return RefusalHints.OPEN + "constraint]\n";
		}
		return "";
	}

	private static List<Throwable> chain(final Throwable pException)
	{
		final List<Throwable> out = new ArrayList<Throwable>();
		Throwable current = pException;
		while (current != null && out.size() < RefusalHints.MAX_CAUSES && !out.contains(current))
		{
			out.add(current);
			current = current.getCause();
		}
		return out;
	}

	/** The index of the first frame of the trigger machinery, -1 when the failure was not raised under a trigger. */
	private static int triggerBoundary(final StackTraceElement[] pStack)
	{
		for (int i = 0; i < pStack.length; i++)
		{
			final String className = pStack[i].getClassName();
			if (className.startsWith("com.onwbp.adaptation.Trg") && className.contains("TriggerApply"))
			{
				return i;
			}
		}
		return -1;
	}

	private static boolean isUserClass(final String pClassName)
	{
		return !(pClassName.startsWith("com.onwbp.") || pClassName.startsWith("com.orchestranetworks.") || pClassName.startsWith("java.") || pClassName.startsWith("javax.")
			|| pClassName.startsWith("jdk.") || pClassName.startsWith("sun."));
	}

	private static String simpleName(final String pClassName)
	{
		String name = pClassName;
		final int dollar = name.indexOf('$');
		if (dollar > 0)
		{
			name = name.substring(0, dollar);
		}
		return name.substring(name.lastIndexOf('.') + 1);
	}

	private static String clean(final String pName)
	{
		return pName == null ? "" : pName.replaceAll("[\\]\\[;=\\r\\n]", "_");
	}

	// ----------------------------------------------------------------------------------------------- reading

	/** The header of {@code pText} read, and the text without it. A text with no header gives no fact and itself. */
	public static RefusalHints parse(final String pText)
	{
		final String text = pText == null ? "" : pText;
		if (!text.startsWith(RefusalHints.OPEN))
		{
			return new RefusalHints(false, false, "", 0, text);
		}
		final int close = text.indexOf(']');
		if (close < 0)
		{
			return new RefusalHints(false, false, "", 0, text);
		}
		final String body = text.substring(RefusalHints.OPEN.length(), close);
		String rest = text.substring(close + 1);
		if (rest.startsWith("\r\n"))
		{
			rest = rest.substring(2);
		}
		else if (rest.startsWith("\n"))
		{
			rest = rest.substring(1);
		}
		boolean trigger = false;
		boolean constraint = false;
		String name = "";
		int line = 0;
		for (final String part : body.split(";"))
		{
			final String item = part.trim();
			if ("constraint".equals(item))
			{
				constraint = true;
			}
			else if (item.startsWith("trigger="))
			{
				trigger = true;
				name = item.substring("trigger=".length());
			}
			else if (item.startsWith("line="))
			{
				try
				{
					line = Integer.parseInt(item.substring("line=".length()));
				}
				catch (final NumberFormatException ex)
				{
					line = 0;
				}
			}
		}
		return new RefusalHints(trigger, constraint, name, line, rest);
	}
}
