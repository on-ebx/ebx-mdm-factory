package com.on.ebx.factory.ebx.ui;

import java.time.ZoneId;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.core.AboutInfo;
import com.on.ebx.factory.core.Assets;
import com.on.ebx.factory.core.ModuleVersion;
import com.on.ebx.factory.core.WriterShapes;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.ui.ResourceType;
import com.orchestranetworks.ui.selection.TableViewEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.UserServiceDisplayConfigurator;
import com.orchestranetworks.userservice.UserServiceEventOutcome;
import com.orchestranetworks.userservice.UserServiceObjectContextBuilder;
import com.orchestranetworks.userservice.UserServicePane;
import com.orchestranetworks.userservice.UserServicePaneContext;
import com.orchestranetworks.userservice.UserServicePaneWriter;
import com.orchestranetworks.userservice.UserServiceProcessEventOutcomeContext;
import com.orchestranetworks.userservice.UserServiceSetupDisplayContext;
import com.orchestranetworks.userservice.UserServiceSetupObjectContext;
import com.orchestranetworks.userservice.UserServiceValidateContext;

/** The "About" screen (see {@link AboutServiceDeclaration}): read only, nothing to submit, only a Close button. */
public final class AboutUserService implements UserService<TableViewEntitySelection>
{
	Locale locale = Locale.ENGLISH;

	@Override
	public void setupObjectContext(final UserServiceSetupObjectContext<TableViewEntitySelection> pContext, final UserServiceObjectContextBuilder pBuilder)
	{
		// nothing to prepare: the page reads the build's own version stamp
	}

	@Override
	public void setupDisplay(final UserServiceSetupDisplayContext<TableViewEntitySelection> pContext, final UserServiceDisplayConfigurator pConfigurator)
	{
		this.locale = pContext.getSession().getLocale();
		pConfigurator.setTitle(Messages.get(AboutUserService.class, this.locale, "about.title"));
		pConfigurator.setRightButtons(pConfigurator.newCloseButton());
		pConfigurator.setContent(ScreenFailure.guard("about", this.locale, new UserServicePane()
		{
			@Override
			public void writePane(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
			{
				AboutUserService.this.write(pPaneContext, pWriter);
			}
		}));
	}

	void write(final UserServicePaneContext pPaneContext, final UserServicePaneWriter pWriter)
	{
		final Map<String, String> theme = ThemeTokens.read(ThemeTokens.perspective(pPaneContext));
		pWriter.add(ThemeTokens.styleTag(theme));
		pWriter.add("<link rel=\"stylesheet\" href=\"").add(pWriter.getURLForResource(ResourceType.STYLESHEET, "factory/factory.css")).add("?v=").add(Assets.version()).add("\"/>");
		final AboutPage.Model model = new AboutPage.Model();
		model.locale = this.locale;
		model.version = ModuleVersion.version();
		model.commit = ModuleVersion.commit();
		model.buildTime = ModuleVersion.buildTime();
		model.buildTimeText = AboutInfo.buildTime(model.buildTime, this.locale, ZoneId.systemDefault());
		model.ebxVersion = WriterShapes.productVersion();
		// the hashes are for the person who checks what was deployed: administrators only (the permission rule only hides the action)
		model.administrator = pPaneContext.getSession().isUserInRole(Profile.ADMINISTRATOR);
		model.agentJarSha256 = ModuleVersion.agentJarSha256();
		model.mappingJarSha256 = ModuleVersion.mappingJarSha256();
		model.guideUrl = ThemeTokens.withQuery(pWriter.getURLForResource(ResourceType.HTML, AboutInfo.guide(this.locale)), theme);
		model.otherGuideUrl = ThemeTokens.withQuery(pWriter.getURLForResource(ResourceType.HTML, AboutInfo.otherGuide(this.locale)), theme);
		model.guideLanguage = AboutInfo.guide(this.locale).contains("-fr") ? "Français" : "English";
		model.otherGuideLanguage = AboutInfo.guide(this.locale).contains("-fr") ? "English" : "Français";
		pWriter.add(AboutPage.render(model));
		pWriter.addJS_cr(AboutPage.script(model));
	}

	@Override
	public void validate(final UserServiceValidateContext<TableViewEntitySelection> pContext)
	{
		// read only
	}

	@Override
	public UserServiceEventOutcome processEventOutcome(final UserServiceProcessEventOutcomeContext<TableViewEntitySelection> pContext, final UserServiceEventOutcome pOutcome)
	{
		return pOutcome;
	}
}
