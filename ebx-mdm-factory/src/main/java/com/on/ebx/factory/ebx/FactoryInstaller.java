package com.on.ebx.factory.ebx;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import com.on.ebx.factory.core.FactoryPaths;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationHome;
import com.onwbp.adaptation.AdaptationName;
import com.onwbp.adaptation.AdaptationReference;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.base.text.UserMessage;
import com.orchestranetworks.instance.HomeCreationSpec;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.schema.SchemaLocation;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.Profile;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * Creates the {@code MdmFactory} dataspace and dataset under the reference dataspace at module
 * startup when they are missing, and the DEFAULT settings row. Idempotent: existing rows are never
 * touched. The zones of a project (landing, staging) are not created here but by the project itself.
 * <p>
 * <b>{@code ReferenceBinding} seeding (normalisation/quality lot)</b>: {@link #seedReferenceBindings} adds
 * the three bindings docs/NORMALISATION.md sec 5.1 found live on the FastTrack instance this module was
 * built against (COUNTRY -&gt; Geographies/Country, CURRENCY -&gt; Currencies/Currency, LEGAL_FORM -&gt;
 * Companies/ReferenceData/LegalForm) - but ONLY when that exact dataspace/dataset/table can actually be
 * resolved on THIS instance at startup; a binding whose target is missing is simply skipped (logged, not an
 * error) rather than failing the whole installation - the same "degrade gracefully when a module/dataset is
 * missing" principle {@link #install} itself already follows for the factory dataset. An administrator can
 * always add a binding for a different instance's own reference tables afterwards; this only ever seeds the
 * three the original study measured, and only once each (an existing row of the same {@code kind} - however
 * an administrator may have since edited it - is never touched again).
 */
public final class FactoryInstaller
{
	private FactoryInstaller()
	{
	}

	/** Set once an installation reached the dataset: the checks below then belong to startup, not to every REST call. */
	static final AtomicBoolean INSTALLED = new AtomicBoolean(false);

	/** Lazy installation from any entry point that has a session; safe to invoke repeatedly. */
	public static void ensure(final Repository pRepository, final Session pSession)
	{
		if (FactoryInstaller.INSTALLED.get())
		{
			return;
		}
		final Repository repository = pRepository == null ? Repository.getDefault() : pRepository;
		FactoryInstaller.install(repository, pSession, FactoryPaths.MODULE_NAME, LoggingCategory.getKernel());
	}

	public static synchronized void install(final Repository pRepository, final Session pSession, final String pModuleName,
		final LoggingCategory pLog)
	{
		try
		{
			AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(FactoryPaths.DATASPACE));
			if (home == null)
			{
				final HomeCreationSpec spec = new HomeCreationSpec();
				spec.setKey(HomeKey.forBranchName(FactoryPaths.DATASPACE));
				spec.setParent(pRepository.getReferenceBranch());
				spec.setOwner(Profile.ADMINISTRATOR);
				spec.setLabel(UserMessage.createInfo("MDM Factory"));
				spec.setDescription(UserMessage.createInfo(
					"MDM Factory: projects, their source applications, the generated landing and staging zones, mappings, matching plans and record cross-references."));
				home = pRepository.createHome(spec, pSession);
				pLog.info("[mdm-factory] dataspace " + FactoryPaths.DATASPACE + " created");
			}
			Adaptation dataset = home.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DATASET));
			if (dataset != null && !FactoryInstaller.belongsTo(dataset, pModuleName))
			{
				// Never write into a dataset of the same name that is not ours.
				pLog.error("[mdm-factory] installation stopped: dataset " + FactoryPaths.DATASPACE + "/" + FactoryPaths.DATASET + " uses the data model "
					+ FactoryInstaller.schemaOf(dataset) + ", not " + FactoryPaths.MODEL_PATH + " of module " + pModuleName
					+ ". Nothing was written. Install the factory elsewhere with -Debx.factory.dataspace=<key> (and -Debx.factory.dataset=<name>).");
				return;
			}
			if (dataset == null)
			{
				final AdaptationHome target = home;
				final ProcedureResult result = ProgrammaticService.createForSession(pSession, home).execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setAllPrivileges(true);
						final Adaptation created = pContext.doCreateRoot(SchemaLocation.forPathInModule(FactoryPaths.MODEL_PATH, pModuleName),
							AdaptationReference.forPersistentName(FactoryPaths.DATASET), Profile.ADMINISTRATOR);
						pContext.setInstanceLabel(created, UserMessage.createInfo("MDM Factory"));
					}
				});
				if (result.hasFailed())
				{
					pLog.error("[mdm-factory] dataset creation failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
					return;
				}
				dataset = target.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DATASET));
				pLog.info("[mdm-factory] dataset " + FactoryPaths.DATASET + " created");
			}
			if (dataset != null)
			{
				FactoryInstaller.seedSettings(pSession, dataset, pLog);
				FactoryInstaller.stampInstanceId(pSession, dataset, pLog);
				FactoryInstaller.drawActivationKey(pSession, dataset, pLog);
				FactoryInstaller.seedReferenceBindings(pRepository, pSession, dataset, pLog);
				FactoryInstaller.INSTALLED.set(true);
			}
		}
		catch (final Exception ex)
		{
			pLog.error("[mdm-factory] installation failed: " + ex, ex);
		}
	}

	/** The DEFAULT settings row, with the defaults of the model; never rewritten once it exists. */
	private static void seedSettings(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final AdaptationTable settings = pDataset.getTable(FactoryPaths.SETTINGS);
		if (settings.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS)) != null)
		{
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContextForNewOccurrence(settings);
				value.setValue(FactoryPaths.DEFAULT_SETTINGS, FactoryPaths.Settings.CODE);
				pContext.doCreateOccurrence(value, settings);
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[mdm-factory] settings seed failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		else
		{
			pLog.info("[mdm-factory] DEFAULT settings created");
		}
	}

	/**
	 * Production review 2026-11-12 (F3-02): draws the secret key of the agents' activations once (32 random bytes), when the DEFAULT settings row has none. Never
	 * rewritten (that would cancel every activation), never logged: the log says only that a key was drawn.
	 */
	private static void drawActivationKey(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final Adaptation row = pDataset.getTable(FactoryPaths.SETTINGS).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (row == null || com.on.ebx.factory.agent.AgentActivation.keyOf(row.getString(FactoryPaths.Settings.AGENT_ACTIVATION_KEY)) != null)
		{
			return;
		}
		final String key = com.on.ebx.factory.agent.AgentActivation.newKey(new java.security.SecureRandom());
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
				value.setValue(key, FactoryPaths.Settings.AGENT_ACTIVATION_KEY);
				pContext.doModifyContent(row, value);
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[mdm-factory] agent activation key could not be drawn: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		else
		{
			pLog.info("[mdm-factory] agent activation key drawn (the agents are switched on again with POST /agents/{code}/enable)");
		}
	}

	/**
	 * Delivery lot 1 ({@code docs/DELIVERY.md} sec 5): the DEFAULT settings row gets a random {@code instanceId}
	 * once (a row written before the field existed is completed here), and every project whose
	 * {@code originRepository} is still empty is stamped with it - such a project was necessarily created on
	 * this instance, since a delivered one arrives with its source's origin. Idempotent: nothing is rewritten
	 * once set.
	 */
	private static void stampInstanceId(final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final AdaptationTable settings = pDataset.getTable(FactoryPaths.SETTINGS);
		final Adaptation row = settings.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(FactoryPaths.DEFAULT_SETTINGS));
		if (row == null)
		{
			return;
		}
		final String existing = row.getString(FactoryPaths.Settings.INSTANCE_ID);
		final String instanceId = existing == null || existing.trim().isEmpty() ? java.util.UUID.randomUUID().toString() : existing.trim();
		final AdaptationTable projects = pDataset.getTable(FactoryPaths.PROJECT);
		final List<Adaptation> unstamped = new ArrayList<Adaptation>();
		final com.onwbp.adaptation.RequestResult rows = projects.createRequestResult("osd:is-null(./originRepository)");
		try
		{
			for (Adaptation project = rows.nextAdaptation(); project != null; project = rows.nextAdaptation())
			{
				unstamped.add(project);
			}
		}
		finally
		{
			rows.close();
		}
		if (instanceId.equals(existing) && unstamped.isEmpty())
		{
			return;
		}
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				pContext.setAllPrivileges(true);
				if (!instanceId.equals(existing))
				{
					final ValueContextForUpdate value = pContext.getContext(row.getAdaptationName());
					value.setValue(instanceId, FactoryPaths.Settings.INSTANCE_ID);
					pContext.doModifyContent(row, value);
				}
				for (final Adaptation project : unstamped)
				{
					final ValueContextForUpdate value = pContext.getContext(project.getAdaptationName());
					value.setValue(instanceId, FactoryPaths.Project.ORIGIN_REPOSITORY);
					pContext.doModifyContent(project, value);
				}
			}
		});
		if (result.hasFailed())
		{
			pLog.error("[mdm-factory] instance identifier stamp failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
		}
		else
		{
			pLog.info("[mdm-factory] instance identifier " + instanceId + (instanceId.equals(existing) ? "" : " written") + ", "
				+ unstamped.size() + " project(s) stamped as created here");
		}
	}

	/** One binding {@link #seedReferenceBindings} may add - see its own Javadoc. */
	private static final class ReferenceBindingSeed
	{
		final String kind;
		final String dataspace;
		final String dataset;
		final String tablePath;
		final String codeField;
		final List<String> labelFields;
		final String scopeField;
		final String synonymsJson;

		ReferenceBindingSeed(final String pKind, final String pDataspace, final String pDataset, final String pTablePath, final String pCodeField,
			final List<String> pLabelFields, final String pScopeField, final String pSynonymsJson)
		{
			this.kind = pKind;
			this.dataspace = pDataspace;
			this.dataset = pDataset;
			this.tablePath = pTablePath;
			this.codeField = pCodeField;
			this.labelFields = pLabelFields;
			this.scopeField = pScopeField;
			this.synonymsJson = pSynonymsJson;
		}
	}

	private static List<ReferenceBindingSeed> referenceBindingSeeds()
	{
		final List<ReferenceBindingSeed> out = new ArrayList<ReferenceBindingSeed>();
		out.add(new ReferenceBindingSeed("COUNTRY", "Geographies", "Geographies", "/root/Country", "Alpha2Code",
			java.util.Arrays.asList("Name", "NameFR", "Alpha3Code"), null,
			"{\"UK\":\"GB\",\"USA\":\"US\",\"United States\":\"US\",\"Holland\":\"NL\",\"Great Britain\":\"GB\",\"Deutschland\":\"DE\",\"Italia\":\"IT\","
				+ "\"Schweiz\":\"CH\",\"Svizzera\":\"CH\"}"));
		out.add(new ReferenceBindingSeed("CURRENCY", "Currencies", "Currencies", "/root/Currency", "AlphaCode", java.util.Arrays.asList("Label"), null, null));
		out.add(new ReferenceBindingSeed("LEGAL_FORM", "Companies", "Companies", "/root/ReferenceData/LegalForm", "Code", java.util.Arrays.asList("Abbreviation"),
			"Country", null));
		return out;
	}

	/** See the class Javadoc's own "ReferenceBinding seeding" note. */
	private static void seedReferenceBindings(final Repository pRepository, final Session pSession, final Adaptation pDataset, final LoggingCategory pLog)
	{
		final AdaptationTable bindings = pDataset.getTable(FactoryPaths.REFERENCE_BINDING);
		for (final ReferenceBindingSeed seed : FactoryInstaller.referenceBindingSeeds())
		{
			if (bindings.lookupAdaptationByPrimaryKey(PrimaryKey.parseString(seed.kind)) != null)
			{
				continue; // already seeded, or an administrator's own row for this kind - never touched again
			}
			if (!FactoryInstaller.referenceTableExists(pRepository, seed))
			{
				pLog.info("[mdm-factory] ReferenceBinding " + seed.kind + " not seeded: " + seed.dataspace + "/" + seed.dataset + seed.tablePath
					+ " does not exist on this instance - an administrator can add the binding by hand once it does.");
				continue;
			}
			final ProcedureResult result = ProgrammaticService.createForSession(pSession, pDataset.getHome()).execute(new Procedure()
			{
				@Override
				public void execute(final ProcedureContext pContext) throws Exception
				{
					pContext.setAllPrivileges(true);
					final ValueContextForUpdate value = pContext.getContextForNewOccurrence(bindings);
					value.setValue(seed.kind, FactoryPaths.ReferenceBinding.KIND);
					value.setValue(seed.dataspace, FactoryPaths.ReferenceBinding.DATASPACE);
					value.setValue(seed.dataset, FactoryPaths.ReferenceBinding.DATASET);
					value.setValue(seed.tablePath, FactoryPaths.ReferenceBinding.TABLE_PATH);
					value.setValue(seed.codeField, FactoryPaths.ReferenceBinding.CODE_FIELD);
					value.setValue(new ArrayList<String>(seed.labelFields), FactoryPaths.ReferenceBinding.LABEL_FIELDS);
					if (seed.scopeField != null)
					{
						value.setValue(seed.scopeField, FactoryPaths.ReferenceBinding.SCOPE_FIELD);
					}
					if (seed.synonymsJson != null)
					{
						value.setValue(seed.synonymsJson, FactoryPaths.ReferenceBinding.SYNONYMS);
					}
					pContext.doCreateOccurrence(value, bindings);
				}
			});
			if (result.hasFailed())
			{
				pLog.error("[mdm-factory] ReferenceBinding " + seed.kind + " seed failed: " + result.getExceptionFullMessage(Locale.ENGLISH));
			}
			else
			{
				pLog.info("[mdm-factory] ReferenceBinding " + seed.kind + " created (" + seed.dataspace + "/" + seed.dataset + seed.tablePath + ")");
			}
		}
	}

	/** Whether {@code seed}'s dataspace/dataset/table can actually be resolved on this instance right now - checked BEFORE writing anything, so a missing reference module never fails the install (see the class Javadoc). */
	private static boolean referenceTableExists(final Repository pRepository, final ReferenceBindingSeed pSeed)
	{
		try
		{
			final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(pSeed.dataspace));
			if (home == null)
			{
				return false;
			}
			final Adaptation target = home.findAdaptationOrNull(AdaptationName.forName(pSeed.dataset));
			if (target == null)
			{
				return false;
			}
			return target.getTable(Path.parse(pSeed.tablePath)) != null;
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	private static String schemaOf(final Adaptation pDataset)
	{
		try
		{
			return String.valueOf(pDataset.getSchemaLocation().format());
		}
		catch (final RuntimeException ex)
		{
			return "?";
		}
	}

	/**
	 * True only when the dataset's model is {@code MdmFactory.xsd} packaged by this module. A dataset with
	 * the same name on a DMA publication or on another module's model is not the factory, whatever it is called.
	 */
	static boolean belongsTo(final Adaptation pDataset, final String pModuleName)
	{
		try
		{
			final SchemaLocation location = pDataset.getSchemaLocation();
			final String module = pModuleName == null ? FactoryPaths.MODULE_NAME : pModuleName;
			return location.isPackagedInModule() && module.equals(location.getModuleName())
				&& FactoryInstaller.samePath(FactoryPaths.MODEL_PATH, location.getPathInModule());
		}
		catch (final RuntimeException ex)
		{
			return false;
		}
	}

	static boolean samePath(final String pExpected, final String pActual)
	{
		if (pActual == null)
		{
			return false;
		}
		final String a = pActual.trim().startsWith("/") ? pActual.trim() : "/" + pActual.trim();
		return pExpected.equals(a);
	}

	/** The factory dataset, or null when it does not exist or is not ours ({@link #belongsTo}). */
	public static Adaptation dataset(final Repository pRepository)
	{
		final Adaptation dataset = FactoryInstaller.rawDataset(pRepository);
		return dataset != null && FactoryInstaller.belongsTo(dataset, FactoryPaths.MODULE_NAME) ? dataset : null;
	}

	/** {@code VERIFIED}, {@code ABSENT} or {@code FOREIGN} (a dataset of that name on another model). */
	public static String datasetState(final Repository pRepository)
	{
		final Adaptation dataset = FactoryInstaller.rawDataset(pRepository);
		return dataset == null ? "ABSENT" : FactoryInstaller.belongsTo(dataset, FactoryPaths.MODULE_NAME) ? "VERIFIED" : "FOREIGN";
	}

	private static Adaptation rawDataset(final Repository pRepository)
	{
		if (pRepository == null)
		{
			return null;
		}
		final AdaptationHome home = pRepository.lookupHome(HomeKey.forBranchName(FactoryPaths.DATASPACE));
		return home == null ? null : home.findAdaptationOrNull(AdaptationName.forName(FactoryPaths.DATASET));
	}
}
