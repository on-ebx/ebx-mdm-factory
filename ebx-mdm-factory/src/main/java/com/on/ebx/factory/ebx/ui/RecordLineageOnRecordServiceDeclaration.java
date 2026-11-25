package com.on.ebx.factory.ebx.ui;

import com.orchestranetworks.service.ServiceKey;
import com.orchestranetworks.ui.selection.RecordEntitySelection;
import com.orchestranetworks.userservice.UserService;
import com.orchestranetworks.userservice.declaration.ActivationContextOnRecord;
import com.orchestranetworks.userservice.declaration.UserServiceDeclaration;
import com.orchestranetworks.userservice.declaration.UserServicePropertiesDefinitionContext;
import com.orchestranetworks.userservice.declaration.WebComponentDeclarationContext;

/**
 * A "Record lineage" record-level action directly on a MASTER record - the fifth way into the same screen
 * {@code RecordLineageServiceDeclaration} already offers (deep link, toolbar action on a {@code Project}
 * row, perspective entry - all four kept exactly as they are, this lot's own task brief). A steward looking
 * at a golden record in a project's master zone (e.g. dataspace {@code Companies_Master}, dataset
 * {@code Master}, table {@code /root/Company}) opens the SAME lineage screen from the record's own Actions
 * menu, no factory navigation needed.
 * <p>
 * <b>Registered at MODULE level, not as a schema extension</b> (coordinator review, 2026-09-22):
 * `docs/RECORD-LINEAGE.md`'s earlier note that this was impossible was only true for schema-extension
 * registration - the master model is DMA-generated, and only a {@code data-model}-owned XSD can declare
 * {@code <osd:extensions>}. {@link ServiceKey#forModuleServiceName(String, String)} is the module-level
 * counterpart of {@link ServiceKey#forName(String)} (CLAUDE.md &sect;9's own "ServiceKey format" note): a
 * module-level {@code UserServiceDeclaration} is offered on ANY table of ANY model, DMA-published ones
 * included - the same mechanism the FastTrack presales web project's own generic {@code
 * DataProfilingServiceDeclaration} (registered from {@code ModuleRegistration}, table view of every table
 * of every dataset) already uses, and {@code registerUserService} is called from {@code
 * FactoryModuleRegistration.handleServiceRegistration}, the module-level registration hook. <b>Registering
 * (or changing) a module-level service needs a full Tomcat restart to take effect</b> (CLAUDE.md &sect;9:
 * "a class already loaded by Tomcat ... never reloads without a full Tomcat restart") - this lot was never
 * deployed or seen running.
 * <p>
 * <b>Precise activation is the hard requirement</b> (the product owner, 2026-09-22): the action must be visible on a
 * project's own promoted master tables and nowhere else in the repository - landing tables, staging tables,
 * every factory table, and every other model's table must never show it. {@link #defineActivation} leaves
 * the declarative ACTIVATION scope at its documented maximum ({@code ActivationContextOnRecord}'s own
 * default is already "all records in all datasets in all opened non-technical branches" - the local Java
 * API doc, read 2026-09-22 - so {@code includeAllDatasets()}/{@code includeAllSchemaNodes()} below are
 * explicit, matching {@code DataProfilingServiceDeclaration}'s own precedent, rather than a behaviour
 * change) and hands the actual per-(dataspace, dataset, table) decision to {@link
 * RecordLineageOnRecordPermissionRule} via {@code setPermissionRule} - the mechanism the local Java API doc
 * itself documents for a rule that must be evaluated per context rather than declared as a static pattern.
 * See that class's own Javadoc for the cache/fail-closed design, and {@code MasterZoneIndex}'s for what it
 * reads and with which session.
 */
public final class RecordLineageOnRecordServiceDeclaration implements UserServiceDeclaration.OnRecord
{
	public static final String SERVICE_NAME = "RecordLineageOnRecord";
	/** Second permissions pass (2026-10-14): the key as registered (the module name is the one EBX gave), read by the service's own permission check. */
	static volatile ServiceKey registered;

	private final ServiceKey serviceKey;

	public RecordLineageOnRecordServiceDeclaration(final String pModuleName)
	{
		this.serviceKey = ServiceKey.forModuleServiceName(pModuleName, RecordLineageOnRecordServiceDeclaration.SERVICE_NAME);
		RecordLineageOnRecordServiceDeclaration.registered = this.serviceKey;
	}

	@Override
	public UserService<RecordEntitySelection> createUserService()
	{
		return new RecordLineageOnRecordUserService();
	}

	@Override
	public void declareWebComponent(final WebComponentDeclarationContext pContext)
	{
		// a record-level action only: the record form's own Actions menu, and the table view toolbar when
		// exactly one record is selected - no perspective entry (there is no single record to resolve without
		// one), the original OnTableView screen already covers that entry point.
		pContext.setAvailableAsToolbarAction(true);
	}

	@Override
	public void defineActivation(final ActivationContextOnRecord pContext)
	{
		pContext.includeAllDatasets();
		pContext.includeAllSchemaNodes();
		pContext.setPermissionRule(new RecordLineageOnRecordPermissionRule());
	}

	@Override
	public void defineProperties(final UserServicePropertiesDefinitionContext pContext)
	{
		pContext.setGroup(com.on.ebx.factory.ebx.FactoryModuleRegistration.SERVICE_GROUP);
		pContext.setLabel(Messages.getInfo(RecordLineageOnRecordServiceDeclaration.class, "lineage.onRecord.label"));
		pContext.setDescription(Messages.getInfo(RecordLineageOnRecordServiceDeclaration.class, "lineage.onRecord.description"));
	}

	@Override
	public ServiceKey getServiceKey()
	{
		return this.serviceKey;
	}
}
