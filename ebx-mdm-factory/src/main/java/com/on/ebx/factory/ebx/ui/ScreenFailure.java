package com.on.ebx.factory.ebx.ui;

import java.security.SecureRandom;
import java.util.Locale;
import java.util.function.Consumer;

import com.on.ebx.factory.core.Assets;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.userservice.UserServiceEvent;
import com.orchestranetworks.userservice.UserServiceEventContext;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;

/**
 * Audit F-41: what a Factory screen shows when an action or a rendering fails in a way nobody planned for (a {@code
 * RuntimeException}, a {@code LinkageError} after an upgrade...). The failure is logged once in {@code kernel.log} with
 * its stack trace and a short reference ({@code MF-7K2Q9D}); the person sees one localized sentence carrying the same
 * reference - "Something went wrong (reference MF-7K2Q9D). The administrator can find the details in the log." - never a
 * stack trace and never a blank pane. A {@code VirtualMachineError} (out of memory...) is not swallowed: it is rethrown
 * after being logged.
 * <p>
 * An expected refusal ({@code OperationException}) stays the screen's own business: each screen already turns it into
 * its own notice. This class only covers what escapes that.
 */
public final class ScreenFailure
{
	static final String NOTICE_KEY = "ui.failure.notice";
	private static final String ALPHABET = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"; // no 0/O, 1/I: read aloud over the phone
	private static final SecureRandom RANDOM = new SecureRandom();

	private ScreenFailure()
	{
	}

	/** A fresh reference, {@code MF-} and six characters that cannot be misread. Pure. */
	public static String newReference()
	{
		final StringBuilder out = new StringBuilder("MF-");
		for (int i = 0; i < 6; i++)
		{
			out.append(ScreenFailure.ALPHABET.charAt(ScreenFailure.RANDOM.nextInt(ScreenFailure.ALPHABET.length())));
		}
		return out.toString();
	}

	/** The log line (without the stack trace, which the logger adds). Pure. */
	static String logLine(final String pReference, final String pScreen, final String pWhere, final Throwable pFailure)
	{
		return "[mdm-factory] screen " + pScreen + " failed" + (pWhere == null || pWhere.isEmpty() ? "" : " in " + pWhere) + " (reference " + pReference + "): "
			+ pFailure;
	}

	/** Rethrows what must never be swallowed by a screen: a {@code VirtualMachineError} or a {@code ThreadDeath}. */
	@SuppressWarnings("deprecation")
	public static void rethrowIfFatal(final Throwable pFailure)
	{
		if (pFailure instanceof VirtualMachineError)
		{
			throw (VirtualMachineError) pFailure;
		}
		if (pFailure instanceof ThreadDeath)
		{
			throw (ThreadDeath) pFailure;
		}
	}

	/** Logs the failure with a new reference and returns that reference; a fatal error is logged, then rethrown. */
	public static String report(final String pScreen, final String pWhere, final Throwable pFailure)
	{
		final String reference = ScreenFailure.newReference();
		try
		{
			LoggingCategory.getKernel().error(ScreenFailure.logLine(reference, pScreen, pWhere, pFailure), pFailure);
		}
		catch (final RuntimeException logFailure)
		{
			pFailure.addSuppressed(logFailure);
		}
		ScreenFailure.rethrowIfFatal(pFailure);
		return reference;
	}

	/** The sentence the person reads. Pure (bundle only). */
	public static String notice(final Locale pLocale, final String pReference)
	{
		return Messages.get(ScreenFailure.class, pLocale, ScreenFailure.NOTICE_KEY, pReference);
	}

	/** The sentence as a warning notice block, the markup every Factory screen uses ({@code factory.css}). Pure. */
	public static String noticeHtml(final Locale pLocale, final String pReference)
	{
		return "<div class=\"mdmf mdmf-screen-failure\"><div class=\"mdmf-notice mdmf-notice-warn\" role=\"alert\" data-mdmf-failure=\"" + FactoryHtml.esc(pReference)
			+ "\">" + FactoryHtml.esc(ScreenFailure.notice(pLocale, pReference)) + "</div></div>";
	}

	/** Writes the notice, with the module's stylesheet (the failure may have come before the screen wrote its own). */
	public static void writeNotice(final UserServicePaneWriter pWriter, final Locale pLocale, final String pReference)
	{
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version())
			.add("\"/>");
		pWriter.add(ScreenFailure.noticeHtml(pLocale, pReference));
	}

	/**
	 * A pane that writes the perspective's colour tokens ({@link ThemeTokens}), then {@code pInner} and, if it fails, logs it and adds the notice. What {@code pInner} had already written
	 * stays (a pane writes straight into the page), so the notice comes after it; the screens of the module build their main
	 * block as one string before writing it, so in practice the notice stands alone under the stylesheets.
	 */
	public static UserServicePane guard(final String pScreen, final Locale pLocale, final UserServicePane pInner)
	{
		return new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				try
				{
					// audit F-44: the colours of the perspective that displays the screen, before anything that reads them
					pWriter.add(ThemeTokens.styleTagFor(pPaneContext));
					pInner.writePane(pPaneContext, pWriter);
				}
				catch (final RuntimeException | Error ex)
				{
					ScreenFailure.writeNotice(pWriter, pLocale, ScreenFailure.report(pScreen, "rendering", ex));
				}
			}
		};
	}

	/**
	 * An event that runs {@code pInner} and, if it fails, logs it and hands the localized sentence to {@code pNotice} (the
	 * screen's own notice field) - the screen then renders normally, with the notice on top.
	 */
	public static UserServiceEvent guard(final String pScreen, final String pWhere, final Locale pLocale, final UserServiceEvent pInner, final Consumer<String> pNotice)
	{
		return new UserServiceEvent()
		{
			@Override
			public UserServiceEventOutcome processEvent(final UserServiceEventContext pEventContext)
			{
				try
				{
					return pInner.processEvent(pEventContext);
				}
				catch (final RuntimeException | Error ex)
				{
					pNotice.accept(ScreenFailure.notice(pLocale, ScreenFailure.report(pScreen, pWhere, ex)));
					return null;
				}
			}
		};
	}
}
