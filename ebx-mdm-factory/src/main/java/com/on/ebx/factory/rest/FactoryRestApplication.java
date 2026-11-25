package com.on.ebx.factory.rest;

import jakarta.ws.rs.ApplicationPath;

import com.orchestranetworks.rest.ApplicationConfigurator;
import com.orchestranetworks.rest.RESTApplicationAbstract;

/**
 * REST API of the MDM Factory module on the EBX REST Toolkit (EBX authentication: HTTP Basic, token,
 * SSO). Declared in web.xml through {@code RESTRegistrationServlet}; mounted under the module
 * context: {@code /ebx-mdm-factory/rest/v1/...}.
 */
@ApplicationPath("/rest")
public final class FactoryRestApplication extends RESTApplicationAbstract
{
	public FactoryRestApplication()
	{
		super(new java.util.function.Consumer<ApplicationConfigurator>()
		{
			@Override
			public void accept(final ApplicationConfigurator pConfigurator)
			{
				pConfigurator.register(FactoryResource.class);
				// security review (2026-09-24): cross-site request forgery guard on every state-changing route
				pConfigurator.register(CsrfGuard.class);
			}
		});
	}
}
