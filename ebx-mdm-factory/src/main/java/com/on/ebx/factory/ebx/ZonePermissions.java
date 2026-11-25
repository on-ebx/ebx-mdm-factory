package com.on.ebx.factory.ebx;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.onwbp.adaptation.AdaptationHome;
import com.orchestranetworks.instance.HomeKey;
import com.orchestranetworks.instance.Repository;

/**
 * 1.4.3 (X3-11, decision of 2026-11-12): where the permissions of a NEW generated zone (landing, staging, master, domain) come from.
 * <ul>
 * <li>{@code -Debx.factory.zonePermissionsFrom} unset or blank: the PARENT dataspace of the zone (the default since 1.4.3, as the ON EBX screen and Data Services do);</li>
 * <li>a dataspace key: that dataspace (as before 1.4.3, takes precedence over the default);</li>
 * <li>{@code none}: nothing is copied, the zone stays closed to everyone but its owner {@code administrator} (the behaviour before 1.4.3);</li>
 * <li>a key that names no dataspace: nothing is copied and one WARN line is written (the explicit setting is not silently replaced by the parent's permissions, which
 * could be wider than the template the administrator meant); the status says {@code NAMED_MISSING}.</li>
 * </ul>
 * The owner of the zone is {@code administrator} in every case. Existing zones are never touched. The decision is {@link #resolve}, pure.
 */
public final class ZonePermissions
{
	/** The value of the property that keeps the behaviour of 1.4.2 and before: no copy. */
	public static final String NONE = "none";

	/** Where the permissions come from. */
	public enum Mode
	{
		PARENT, NAMED, NAMED_MISSING, NONE
	}

	/** The decision: a mode and, for PARENT and NAMED, the key of the dataspace to copy from. */
	public static final class Decision
	{
		public final Mode mode;
		public final String from;

		Decision(final Mode pMode, final String pFrom)
		{
			this.mode = pMode;
			this.from = pFrom;
		}
	}

	private ZonePermissions()
	{
	}

	/**
	 * Pure. {@code pProperty} is the raw value of the JVM property (null / blank = unset), {@code pParentKey} the key of the dataspace the zone is created under,
	 * {@code pNamedExists} whether the dataspace named by the property exists (ignored when the property is unset or {@code none}).
	 */
	public static Decision resolve(final String pProperty, final String pParentKey, final boolean pNamedExists)
	{
		final String value = pProperty == null ? "" : pProperty.trim();
		if (value.isEmpty())
		{
			return new Decision(Mode.PARENT, pParentKey);
		}
		if (ZonePermissions.NONE.equalsIgnoreCase(value))
		{
			return new Decision(Mode.NONE, null);
		}
		return pNamedExists ? new Decision(Mode.NAMED, value) : new Decision(Mode.NAMED_MISSING, value);
	}

	private static String property()
	{
		return System.getProperty(FactoryAccess.PROPERTY_ZONE_PERMISSIONS);
	}

	private static boolean exists(final Repository pRepository, final String pKey)
	{
		return pRepository != null && pKey != null && pRepository.lookupHome(HomeKey.forBranchName(pKey)) != null;
	}

	/** The decision for a zone created under {@code pParent}, reading the JVM property now. */
	public static Decision decide(final Repository pRepository, final AdaptationHome pParent)
	{
		final String value = ZonePermissions.property();
		final String trimmed = value == null ? "" : value.trim();
		final boolean named = !trimmed.isEmpty() && !ZonePermissions.NONE.equalsIgnoreCase(trimmed);
		return ZonePermissions.resolve(value, pParent == null ? null : pParent.getKey().getName(), named && ZonePermissions.exists(pRepository, trimmed));
	}

	/** Language-neutral label for messages: {@code PARENT=Reference}, {@code NAMED=Key}, {@code NAMED_MISSING=Key}, {@code NONE}. */
	public static String label(final Decision pDecision)
	{
		return pDecision.from == null ? pDecision.mode.name() : pDecision.mode.name() + "=" + pDecision.from;
	}

	/** Sentence for the log (once at start) and for the status. */
	public static String describe(final Decision pDecision)
	{
		switch (pDecision.mode)
		{
		case PARENT:
			return "new zones copy the permissions of their parent dataspace (" + pDecision.from + ")";
		case NAMED:
			return "new zones copy the permissions of the dataspace " + pDecision.from + " (-D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + ")";
		case NONE:
			return "new zones copy no permissions (-D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + "=none): closed to everyone but the owner administrator";
		default:
			return "-D" + FactoryAccess.PROPERTY_ZONE_PERMISSIONS + "=" + pDecision.from + " names no dataspace: new zones copy no permissions";
		}
	}

	/** The status block: {@code {property, mode, from, description}}; the parent of every generated zone is the reference dataspace. */
	public static ObjectNode statusJson(final Repository pRepository)
	{
		final Decision decision = ZonePermissions.decide(pRepository, pRepository == null ? null : pRepository.getReferenceBranch());
		final ObjectNode out = new ObjectMapper().createObjectNode();
		out.put("property", FactoryAccess.PROPERTY_ZONE_PERMISSIONS);
		final String raw = ZonePermissions.property();
		out.put("value", raw == null || raw.trim().isEmpty() ? "" : raw.trim());
		out.put("mode", decision.mode.name());
		out.put("copiedFrom", decision.from == null ? "" : decision.from);
		out.put("description", ZonePermissions.describe(decision));
		return out;
	}
}
