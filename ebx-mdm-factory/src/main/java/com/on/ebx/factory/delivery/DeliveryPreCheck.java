package com.on.ebx.factory.delivery;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * What EBX Staging does not check for a delivered project, said before the archive is built: the source
 * connectors the landing flows name (they must exist on the target under the same ids, with the target's
 * own credentials - the connector rows never travel), the reference datasets the cleaning steps and
 * quality rules bind to, and the Match and Merge add-on when a matching configuration travels. Pure;
 * {@code ebx.DeliveryDomainReader} reads the facts. {@code docs/DELIVERY.md} sec 5.
 */
public final class DeliveryPreCheck
{
	private DeliveryPreCheck()
	{
	}

	public static final String ERROR = "ERROR";
	public static final String WARN = "WARN";
	public static final String INFO = "INFO";

	/** A connector a delivered landing flow reads from, as its row says on the source instance. */
	public static final class Connector
	{
		public final String id;
		/** Which source systems of the project use it. */
		public final List<String> usedBy;
		public final boolean exists;
		public final boolean active;
		public final boolean hasApiKey;
		public final boolean hasPassword;

		public Connector(final String pId, final List<String> pUsedBy, final boolean pExists, final boolean pActive, final boolean pHasApiKey,
			final boolean pHasPassword)
		{
			this.id = pId;
			this.usedBy = Collections.unmodifiableList(new ArrayList<String>(pUsedBy));
			this.exists = pExists;
			this.active = pActive;
			this.hasApiKey = pHasApiKey;
			this.hasPassword = pHasPassword;
		}
	}

	/** A {@code ReferenceBinding} row and whether its dataset exists here (it must exist on the target too). */
	public static final class Reference
	{
		public final String kind;
		public final String dataspace;
		public final String dataset;
		public final boolean exists;

		public Reference(final String pKind, final String pDataspace, final String pDataset, final boolean pExists)
		{
			this.kind = pKind;
			this.dataspace = pDataspace;
			this.dataset = pDataset;
			this.exists = pExists;
		}
	}

	/**
	 * One service parameter of a toolbar button of a delivered model ({@code ebx-dma/<model>/root/toolbarNode},
	 * {@code actionButton/service/serviceParameters}). Measured 2026-09-22: EBX Staging's dependency check
	 * ({@code ServiceSelectionStagingDependency.exists}) parses such a value as a {@code Path} and throws
	 * {@code IllegalArgumentException: Invalid step [Project[.]: there is no closing bracket} on an xpath with a
	 * predicate - the whole import answers HTTP 500 before any component is checked.
	 */
	public static final class ToolbarParameter
	{
		public final String model;
		public final String serviceKey;
		public final String name;
		public final String value;

		public ToolbarParameter(final String pModel, final String pServiceKey, final String pName, final String pValue)
		{
			this.model = pModel;
			this.serviceKey = pServiceKey;
			this.name = pName;
			this.value = pValue;
		}
	}

	public static final class Item
	{
		public final String level;
		public final String code;
		public final String message;

		Item(final String pLevel, final String pCode, final String pMessage)
		{
			this.level = pLevel;
			this.code = pCode;
			this.message = pMessage;
		}
	}

	public static final class Result
	{
		public final List<Item> items;
		/** False when at least one item is an ERROR. */
		public final boolean ok;

		Result(final List<Item> pItems)
		{
			this.items = Collections.unmodifiableList(pItems);
			boolean ok = true;
			for (final Item item : pItems)
			{
				ok &= !DeliveryPreCheck.ERROR.equals(item.level);
			}
			this.ok = ok;
		}
	}

	public static Result check(final List<Connector> pConnectors, final List<Reference> pReferences, final boolean pMatchingDelivered,
		final boolean pAddonPresent, final boolean pConnectorsInstalled, final boolean pLandingDelivered)
	{
		return DeliveryPreCheck.check(pConnectors, pReferences, pMatchingDelivered, pAddonPresent, pConnectorsInstalled, pLandingDelivered,
			Collections.<ToolbarParameter> emptyList());
	}

	public static Result check(final List<Connector> pConnectors, final List<Reference> pReferences, final boolean pMatchingDelivered,
		final boolean pAddonPresent, final boolean pConnectorsInstalled, final boolean pLandingDelivered, final List<ToolbarParameter> pToolbarParameters)
	{
		return DeliveryPreCheck.check(pConnectors, pReferences, pMatchingDelivered, pAddonPresent, pConnectorsInstalled, pLandingDelivered, pToolbarParameters,
			Collections.<String> emptyList(), Collections.<String> emptyList());
	}

	/**
	 * @param pLibraries the script libraries delivered as sources (closed over their imports) - an INFO telling the
	 *        operator to publish them on the target before the model publications import
	 * @param pMissingLibraries libraries a delivered constraint imports whose source was not found here - an ERROR:
	 *        the model publication would fail on the target exactly as it did on 2026-09-22
	 */
	public static Result check(final List<Connector> pConnectors, final List<Reference> pReferences, final boolean pMatchingDelivered,
		final boolean pAddonPresent, final boolean pConnectorsInstalled, final boolean pLandingDelivered, final List<ToolbarParameter> pToolbarParameters,
		final List<String> pLibraries, final List<String> pMissingLibraries)
	{
		final List<Item> items = new ArrayList<Item>();
		for (final String library : pMissingLibraries)
		{
			items.add(new Item(DeliveryPreCheck.ERROR, "LIBRARY_MISSING", "A delivered constraint imports the script library " + library
				+ ", whose source was not found here: the model publication would fail on the target (\"Library ... is not published or does not exist\")."));
		}
		if (!pLibraries.isEmpty())
		{
			items.add(new Item(DeliveryPreCheck.INFO, "LIBRARIES_TO_PUBLISH", "After importing the archive's script sources and before its model publications, publish on the target: "
				+ String.join(", ", pLibraries) + " (EBX Script IDE, or the scripting MCP server's publish_script). On a fresh instance the IDE hides the Library category until the"
				+ " sensitive permission scripting/scriptLibraryAllowed is granted (measured 2026-09-22: \"User cannot access this node\" even for an administrator)."));
		}
		for (final ToolbarParameter parameter : pToolbarParameters)
		{
			if (DeliveryPreCheck.blocksStaging(parameter.value))
			{
				items.add(new Item(DeliveryPreCheck.ERROR, "TOOLBAR_PREDICATE", "Model " + parameter.model + ": toolbar button " + parameter.serviceKey
					+ " passes " + parameter.name + "=" + parameter.value
					+ "; EBX Staging cannot import a model whose toolbar carries an xpath with a predicate (its dependency check parses it as a path and fails). Rewrite the button without the predicate."));
			}
		}
		if (pLandingDelivered && !pConnectorsInstalled)
		{
			items.add(new Item(DeliveryPreCheck.WARN, "CONNECTORS_ABSENT", "Connectors is not installed here: the landing flows cannot be delivered."));
		}
		for (final Connector connector : pConnectors)
		{
			final String usedBy = String.join(", ", connector.usedBy);
			if (!connector.exists)
			{
				items.add(new Item(DeliveryPreCheck.ERROR, "CONNECTOR_MISSING",
					"Connector " + connector.id + " (used by " + usedBy + ") does not exist here: the delivered flows would name a connector nobody can see."));
				continue;
			}
			if (!connector.active)
			{
				items.add(new Item(DeliveryPreCheck.WARN, "CONNECTOR_INACTIVE", "Connector " + connector.id + " (used by " + usedBy + ") is inactive."));
			}
			final List<String> secrets = new ArrayList<String>();
			if (connector.hasApiKey)
			{
				secrets.add("apiKey");
			}
			if (connector.hasPassword)
			{
				secrets.add("password");
			}
			items.add(new Item(DeliveryPreCheck.INFO, "CONNECTOR_TO_CREATE", "On the target, create connector " + connector.id + " (used by " + usedBy
				+ ") under the same id" + (secrets.isEmpty() ? "" : " and set its " + String.join(" and ", secrets)) + ": connector rows are never delivered."));
		}
		for (final Reference reference : pReferences)
		{
			if (!reference.exists)
			{
				items.add(new Item(DeliveryPreCheck.WARN, "REFERENCE_MISSING", "Reference binding " + reference.kind + " points at " + reference.dataspace + "/"
					+ reference.dataset + ", which does not exist here; it must exist on the target for the cleaning steps and quality rules that use it."));
			}
			else
			{
				items.add(new Item(DeliveryPreCheck.INFO, "REFERENCE_NEEDED",
					"Reference binding " + reference.kind + " needs dataset " + reference.dataspace + "/" + reference.dataset + " on the target."));
			}
		}
		if (pMatchingDelivered && !pAddonPresent)
		{
			items.add(new Item(DeliveryPreCheck.ERROR, "ADDON_MISSING", "A matching configuration is delivered but the Match and Merge add-on is not installed here."));
		}
		else if (pMatchingDelivered)
		{
			items.add(new Item(DeliveryPreCheck.INFO, "ADDON_NEEDED", "The Match and Merge add-on must be installed on the target."));
		}
		return new Result(items);
	}

	/** A service-parameter value EBX Staging's dependency check cannot parse: an xpath step with a predicate. */
	static boolean blocksStaging(final String pValue)
	{
		return pValue != null && pValue.indexOf('[') >= 0;
	}

	public static ObjectNode toJson(final Result pResult, final ObjectNode pInto)
	{
		pInto.put("ok", pResult.ok);
		final ArrayNode items = pInto.putArray("items");
		for (final Item item : pResult.items)
		{
			final ObjectNode node = items.addObject();
			node.put("level", item.level);
			node.put("code", item.code);
			node.put("message", item.message);
		}
		return pInto;
	}
}
