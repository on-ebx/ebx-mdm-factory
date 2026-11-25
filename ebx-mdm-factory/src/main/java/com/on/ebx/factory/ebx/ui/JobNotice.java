package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

/**
 * The end-of-task notice (wave 10): the script of {@code jobnotice.js} with its labels, written into the Factory screens that follow background tasks -
 * the wizard (its jobs banner hands every poll to {@code mdmfJobNotice.check}) and the proposal review ({@code mdmfJobNotice.watch}, only while a task
 * launched from that page is active). The labels are declared as {@code window.mdmfJobNoticeLabels}; the kinds' names are the bundle's {@code wizard.jobs.kind.*}.
 */
final class JobNotice
{
	private JobNotice()
	{
	}

	/** The per-user storage library ({@code userstore.js}, wave 13), the labels declaration, then the notice's own script. */
	static String script(final Locale pLocale)
	{
		return FactoryHtml.userStoreLib() + JobNotice.labels(pLocale) + FactoryHtml.resourceText("jobnotice.js") + "\n";
	}

	static String labels(final Locale pLocale)
	{
		return "window.mdmfJobNoticeLabels={done:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.done"))
			+ ",failed:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.failed"))
			+ ",nothing:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.nothing"))
			+ ",partial:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.partial"))
			+ ",open:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.open"))
			+ ",close:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.close"))
			+ ",more:" + ProposalReviewUserService.jsString(Messages.get(JobNotice.class, pLocale, "wizard.jobnotice.more"))
			+ ",kinds:" + ProjectWizardUserService.jobKindLabels(pLocale) + "};\n";
	}

	/** The module's REST route of a project's jobs, as the browser reaches it (same origin as the EBX pages). */
	static String jobsUrl(final String pProjectCode)
	{
		try
		{
			return "/ebx-mdm-factory/rest/v1/projects/" + java.net.URLEncoder.encode(pProjectCode, "UTF-8").replace("+", "%20") + "/jobs";
		}
		catch (final java.io.UnsupportedEncodingException ex)
		{
			throw new IllegalStateException(ex);
		}
	}

	/** A call that reads the project's jobs once and then follows them while one of this person's is active ({@code jobnotice.js}'s {@code watch}), for a screen with no jobs banner. */
	static String watchCall(final String pJobsUrl)
	{
		return "window.mdmfJobNotice.watch(" + ProposalReviewUserService.jsString(pJobsUrl) + ",{});\n";
	}
}
