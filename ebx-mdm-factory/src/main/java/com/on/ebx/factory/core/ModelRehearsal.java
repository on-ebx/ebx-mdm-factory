package com.on.ebx.factory.core;

/**
 * Whether a generated model is first rehearsed - {@code build_data_model(dry_run: true)}, a full throwaway
 * copy that is validated, compiled and deleted, then the real build (wave 2, lot V2-C). Off by default: the
 * real build is atomic and already validates and compiles ({@link ModelBuildAnswer}), so the rehearsal only
 * doubled the build time (~1 s per field on 6.3.0-rc2; about 10 ms per operation, one transaction, on 6.3.0-gp: docs/VOLUME.md, "Model build time, re-measured"). Kept for diagnosis behind the system property
 * {@value #PROPERTY}{@code =true} (JVM option, read at each build, no restart needed to flip it with a
 * debugger but a restart to set it).
 */
public final class ModelRehearsal
{
	public static final String PROPERTY = "ebx.mdmfactory.modelRehearsal";

	private ModelRehearsal()
	{
	}

	public static boolean enabled()
	{
		return ModelRehearsal.enabled(System.getProperty(ModelRehearsal.PROPERTY));
	}

	/** Only the literal {@code true} (any case, trimmed) turns it on. */
	public static boolean enabled(final String pPropertyValue)
	{
		return pPropertyValue != null && "true".equalsIgnoreCase(pPropertyValue.trim());
	}
}
