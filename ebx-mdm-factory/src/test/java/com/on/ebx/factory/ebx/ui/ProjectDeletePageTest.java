package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

/** JUnit-free: run the main, a failure throws. Exercises {@link ProjectDeletePage} without any EBX type. */
public final class ProjectDeletePageTest
{
	public static void main(final String[] pArgs)
	{
		ProjectDeletePageTest.planModeChecksAndEnablesAnOptionWithAnEligibleItem();
		ProjectDeletePageTest.planModeDisablesAnOptionWithOnlyIneligibleItems();
		ProjectDeletePageTest.planModeDisablesAnOptionWithNoItemsAtAll();
		ProjectDeletePageTest.ineligibleItemShowsItsReason();
		ProjectDeletePageTest.connectionsOptionListsTheCreatedConnectionAndSource();
		ProjectDeletePageTest.connectionsOptionIsDisabledWhenOnlyKeptItemsAreListed();
		ProjectDeletePageTest.reportModeShowsKeptInUse();
		ProjectDeletePageTest.namesDetailsAndReasonsAreEscaped();
		ProjectDeletePageTest.rowCountsTableShowsATotal();
		ProjectDeletePageTest.deliveredNoticeShownWhenDelivered();
		ProjectDeletePageTest.errorNoticeShownWhenSet();
		ProjectDeletePageTest.reportModeShowsOutcomesAndTheExplorerLink();
		ProjectDeletePageTest.reportModeHasNoCheckboxesOrConfirmField();
		ProjectDeletePageTest.bothLocalesRenderWithNoStrayEscapes();

		System.out.println("ProjectDeletePageTest OK");
	}

	// -------------------------------------------------------------------------------------------------------- blocks

	private static void planModeChecksAndEnablesAnOptionWithAnEligibleItem()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.items.add(ProjectDeletePageTest.eligibleItem("MATCHING_CONFIG", "matching", "/root/Company", "Entity Company."));
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the matching option's checkbox is checked",
			html.indexOf("name=\"mdmfGenerated\" value=\"matching\" checked=\"checked\"") >= 0);
		ProjectDeletePageTest.check("the matching option's checkbox is not disabled", html.indexOf("value=\"matching\" checked=\"checked\" disabled") < 0);
		ProjectDeletePageTest.check("the item's own detail is shown", html.indexOf("Entity Company.") >= 0);
	}

	private static void planModeDisablesAnOptionWithOnlyIneligibleItems()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.items.add(ProjectDeletePageTest.ineligibleItem("MASTER_DATASPACE", "dataspaces", "Companies_Master", "no PipelineStep evidence"));
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the dataspaces option's checkbox is present but unchecked",
			html.indexOf("name=\"mdmfGenerated\" value=\"dataspaces\" disabled=\"disabled\"") >= 0);
		ProjectDeletePageTest.check("never checked when nothing is eligible", html.indexOf("value=\"dataspaces\" checked") < 0);
	}

	private static void planModeDisablesAnOptionWithNoItemsAtAll()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		// no LANDING_FLOW item at all
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the flows checkbox is disabled when the project never generated any flow",
			html.indexOf("name=\"mdmfGenerated\" value=\"flows\" disabled=\"disabled\"") >= 0);
		ProjectDeletePageTest.check("says nothing was generated for that option",
			html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.option.nothing")) >= 0);
	}

	private static void ineligibleItemShowsItsReason()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.items.add(ProjectDeletePageTest.ineligibleItem("STAGING_MODEL", "models", "Companies_Staging",
			"no PipelineStep evidence that the factory actually published this data model"));
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the ineligible item's own reason is shown", html.indexOf("no PipelineStep evidence that the factory actually published") >= 0);
		ProjectDeletePageTest.check("marked as an ineligible line", html.indexOf("mdmf-delete-item-ineligible") >= 0);
	}

	/** Lot C (2026-10-17): the fifth option, with the catalogue source and the connection the factory created. */
	private static void connectionsOptionListsTheCreatedConnectionAndSource()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.items.add(ProjectDeletePageTest.eligibleItem("CATALOG_SOURCE", "connections", "ECOMCLIENTSE2E1017183516", "Catalogue source of connection EcomClientsE2e1017183516."));
		model.items.add(ProjectDeletePageTest.eligibleItem("CONNECTION", "connections", "EcomClientsE2e1017183516", "Sample-file connection created for this project."));
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the connections option's checkbox is checked",
			html.indexOf("name=\"mdmfGenerated\" value=\"connections\" checked=\"checked\"") >= 0);
		ProjectDeletePageTest.check("the option has its own title", html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.option.connections.label")) >= 0);
		ProjectDeletePageTest.check("the catalogue source is named", html.indexOf("ECOMCLIENTSE2E1017183516") >= 0);
		ProjectDeletePageTest.check("the connection is named", html.indexOf(">EcomClientsE2e1017183516<") >= 0);
		ProjectDeletePageTest.check("both kinds have their own label", html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.kind.CATALOG_SOURCE")) >= 0
			&& html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.kind.CONNECTION")) >= 0);
		final ProjectDeletePage.Model fr = ProjectDeletePageTest.model();
		fr.locale = Locale.FRENCH;
		fr.items.addAll(model.items);
		final String htmlFr = ProjectDeletePage.render(fr);
		ProjectDeletePageTest.check("French labels exist for both kinds", htmlFr.indexOf("Source du catalogue") >= 0 && htmlFr.indexOf("Connexion fichier") >= 0);
		ProjectDeletePageTest.check("no stray escape in French", htmlFr.indexOf("\\u") < 0);
	}

	private static void connectionsOptionIsDisabledWhenOnlyKeptItemsAreListed()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.items.add(ProjectDeletePageTest.ineligibleItem("CONNECTION", "connections", "EcomClients",
			"this connection already existed when the project used it (the factory found it, it did not create it): kept."));
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("disabled, never checked", html.indexOf("name=\"mdmfGenerated\" value=\"connections\" disabled=\"disabled\"") >= 0
			&& html.indexOf("value=\"connections\" checked") < 0);
		ProjectDeletePageTest.check("the reason is shown", html.indexOf("the factory found it, it did not create it") >= 0);
	}

	private static void reportModeShowsKeptInUse()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.deleted = true;
		final ProjectDeletePage.Item item = new ProjectDeletePage.Item();
		item.kind = "CONNECTION";
		item.name = "EcomClients";
		item.outcome = "KEPT_IN_USE";
		item.message = "mapping 481 still runs on this connection";
		model.items.add(item);
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the outcome has a label, not a raw key", html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.outcome.KEPT_IN_USE")) >= 0
			&& html.indexOf("delete.outcome.KEPT_IN_USE") < 0);
		ProjectDeletePageTest.check("the message is shown", html.indexOf("mapping 481 still runs on this connection") >= 0);
	}

	// ------------------------------------------------------------------------------------------------------ escaping

	private static void namesDetailsAndReasonsAreEscaped()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.projectLabel = "Companies & co";
		final ProjectDeletePage.Item item = ProjectDeletePageTest.ineligibleItem("MASTER_DATASPACE", "dataspaces", "<script>alert(1)</script>",
			"reason with \"quotes\" & <tags>");
		model.items.add(item);
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("no raw <script> tag leaked from the item name", html.indexOf("<script>alert(1)</script>") < 0);
		ProjectDeletePageTest.check("the name is present, escaped", html.indexOf("&lt;script&gt;alert(1)&lt;/script&gt;") >= 0);
		ProjectDeletePageTest.check("the project title in the lead is escaped exactly once, never doubled", html.indexOf("Companies &amp; co") >= 0 && html.indexOf("&amp;amp;") < 0);
		ProjectDeletePageTest.check("the reason's quotes and tags are escaped", html.indexOf("&quot;quotes&quot;") >= 0 && html.indexOf("&lt;tags&gt;") >= 0);
	}

	// -------------------------------------------------------------------------------------------------------- totals

	private static void rowCountsTableShowsATotal()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.rowCounts.put("StagingEntity", Integer.valueOf(3));
		model.rowCounts.put("Xref", Integer.valueOf(107));
		model.rowCounts.put("Project", Integer.valueOf(1));
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("every table is listed", html.indexOf("StagingEntity") >= 0 && html.indexOf("Xref") >= 0 && html.indexOf(">Project<") >= 0);
		ProjectDeletePageTest.check("the total is the sum of every row count (3+107+1=111)", html.indexOf(">111<") >= 0);
	}

	// -------------------------------------------------------------------------------------------------------- notices

	private static void deliveredNoticeShownWhenDelivered()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.delivered = true;
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the delivered notice is shown",
			html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.notice.delivered")) >= 0);
	}

	private static void errorNoticeShownWhenSet()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.error = "Project X not found";
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the error is shown", html.indexOf("Project X not found") >= 0);
		ProjectDeletePageTest.check("marked as a bad notice", html.indexOf("mdmf-notice-bad") >= 0);
	}

	// --------------------------------------------------------------------------------------------------- report mode

	private static void reportModeShowsOutcomesAndTheExplorerLink()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.deleted = true;
		model.explorerUrl = "/ebx/?branch=MdmFactory&instance=MdmFactory&xpath=/root/Project&service=ProjectExplorer";
		final ProjectDeletePage.Item deletedItem = new ProjectDeletePage.Item();
		deletedItem.kind = "MATCHING_CONFIG";
		deletedItem.name = "/root/Company";
		deletedItem.outcome = "DELETED";
		deletedItem.message = "matching configuration and its metadataset deleted";
		model.items.add(deletedItem);
		final ProjectDeletePage.Item failedItem = new ProjectDeletePage.Item();
		failedItem.kind = "LANDING_FLOW";
		failedItem.name = "231";
		failedItem.outcome = "FAILED";
		failedItem.message = "connectors_mapping_delete refused";
		model.items.add(failedItem);
		model.rowCounts.put("Project", Integer.valueOf(1));

		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("the DELETED outcome is shown", html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.outcome.DELETED")) >= 0);
		ProjectDeletePageTest.check("the deleted item's own message is shown", html.indexOf("matching configuration and its metadataset deleted") >= 0);
		ProjectDeletePageTest.check("the FAILED outcome is shown", html.indexOf(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.outcome.FAILED")) >= 0);
		ProjectDeletePageTest.check("the failed item's own message is shown", html.indexOf("connectors_mapping_delete refused") >= 0);
		ProjectDeletePageTest.check("the row counts table is relabelled \"deleted\"",
			html.indexOf(ProjectDeletePageTest.esc(Messages.get(ProjectDeletePage.class, Locale.ENGLISH, "delete.rows.title.done"))) >= 0);
		ProjectDeletePageTest.check("a link back to the explorer, opening the top window",
			html.indexOf("href=\"" + ProjectDeletePageTest.esc(model.explorerUrl) + "\" target=\"_top\"") >= 0);
		// leave-guard lot (2026-09-23): carries the class FactoryHtml#voluntaryLeaveScript arms in
		// ProjectDeleteUserService's own write() - a full-page link that must never trigger EBX's own
		// "Quitter le site Web ?" prompt on a genuinely unrelated form.
		ProjectDeletePageTest.check("the explorer link carries the voluntary-leave marker class",
			html.indexOf("<a class=\"mdmf-leave-link\" href=\"" + ProjectDeletePageTest.esc(model.explorerUrl) + "\"") >= 0);
	}

	private static void reportModeHasNoCheckboxesOrConfirmField()
	{
		final ProjectDeletePage.Model model = ProjectDeletePageTest.model();
		model.deleted = true;
		final String html = ProjectDeletePage.render(model);
		ProjectDeletePageTest.check("no option checkbox once the delete has actually run", html.indexOf("mdmfGenerated") < 0);
		ProjectDeletePageTest.check("no confirm field once the delete has actually run", html.indexOf("mdmfConfirm") < 0);
	}

	// ------------------------------------------------------------------------------------------------------- locales

	private static void bothLocalesRenderWithNoStrayEscapes()
	{
		final ProjectDeletePage.Model en = ProjectDeletePageTest.model();
		final String htmlEn = ProjectDeletePage.render(en);
		ProjectDeletePageTest.check("English wording is used", htmlEn.indexOf("Delete project") >= 0);
		ProjectDeletePageTest.check("no stray backslash-u escape leaked into the rendered page", htmlEn.indexOf("\\u") < 0);

		final ProjectDeletePage.Model fr = ProjectDeletePageTest.model();
		fr.locale = Locale.FRENCH;
		final String htmlFr = ProjectDeletePage.render(fr);
		ProjectDeletePageTest.check("French wording is used", htmlFr.indexOf("Supprimer le projet") >= 0);
		ProjectDeletePageTest.check("the French irreversible notice is correctly accented", htmlFr.indexOf("irréversible") >= 0);
		ProjectDeletePageTest.check("no stray backslash-u escape leaked into the rendered page", htmlFr.indexOf("\\u") < 0);
	}

	// ------------------------------------------------------------------------------------------------------- fixtures

	private static ProjectDeletePage.Model model()
	{
		final ProjectDeletePage.Model model = new ProjectDeletePage.Model();
		model.locale = Locale.ENGLISH;
		model.projectCode = "Companies";
		model.projectLabel = "Companies 360";
		return model;
	}

	private static ProjectDeletePage.Item eligibleItem(final String pKind, final String pOption, final String pName, final String pDetail)
	{
		final ProjectDeletePage.Item item = new ProjectDeletePage.Item();
		item.kind = pKind;
		item.option = pOption;
		item.name = pName;
		item.detail = pDetail;
		item.eligible = true;
		return item;
	}

	private static ProjectDeletePage.Item ineligibleItem(final String pKind, final String pOption, final String pName, final String pReason)
	{
		final ProjectDeletePage.Item item = new ProjectDeletePage.Item();
		item.kind = pKind;
		item.option = pOption;
		item.name = pName;
		item.eligible = false;
		item.reason = pReason;
		return item;
	}

	private static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	private static void check(final String pWhat, final boolean pCondition)
	{
		if (!pCondition)
		{
			throw new AssertionError(pWhat);
		}
	}
}
