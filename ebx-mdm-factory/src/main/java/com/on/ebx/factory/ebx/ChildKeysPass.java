package com.on.ebx.factory.ebx;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.on.ebx.factory.core.FactoryPaths;
import com.on.ebx.factory.quality.ChildKeysDerivation;
import com.on.ebx.factory.staging.ChildKeysSweep;
import com.on.ebx.factory.staging.StagingModel;
import com.on.ebx.factory.staging.TechnicalKeyRule;
import com.onwbp.adaptation.Adaptation;
import com.onwbp.adaptation.AdaptationTable;
import com.onwbp.adaptation.PrimaryKey;
import com.onwbp.adaptation.RequestResult;
import com.orchestranetworks.schema.Path;
import com.orchestranetworks.service.LoggingCategory;
import com.orchestranetworks.service.OperationException;
import com.orchestranetworks.service.Procedure;
import com.orchestranetworks.service.ProcedureContext;
import com.orchestranetworks.service.ProcedureResult;
import com.orchestranetworks.service.ProgrammaticService;
import com.orchestranetworks.service.Session;
import com.orchestranetworks.service.ValueContextForUpdate;

/**
 * The EBX-bound half of the {@code CHILD_KEYS} post-pass of {@link StagingLoader} (lot M3, 2026-10-03; the
 * why is in {@link ChildKeysDerivation}, the algorithm and its memory bound in {@link ChildKeysSweep}): reads
 * the definitions off the {@code StagingAttribute} rows, decides whether a sweep is due, and plugs the real
 * child and parent staging tables into {@link ChildKeysSweep}.
 * <p>
 * <b>Two kinds of definition problem, two outcomes.</b> A definition that cannot be right whatever the model
 * looks like (not JSON, an unknown child entity, a child attribute the entity does not have, two foreign keys
 * to the parent with no {@code "reference"} to choose) makes the load FAIL before any row is read ({@link
 * #read} throws): nothing has been done yet, and a silently skipped matching key is exactly the failure that
 * costs a day. A definition that is right but not yet in the generated model (the parent or child entity not
 * built, the child's foreign-key field or one of its attributes not accepted/generated, the parent's own field
 * not on the published table) is SKIPPED with the reason in the step detail, the same convention as {@code
 * fieldsNotYetInModel}: regenerate the staging model, load again.
 * <p>
 * <b>When a sweep runs</b> ({@link #needed}): scope {@code all}; or a row was written in the parent entity (a
 * new parent has no value yet) or in the child entity this run; or the attribute never finished a computation
 * ({@code StagingAttribute.derivationLastRunAt} empty - first run after the attribute was added, or a previous
 * run stopped/failed halfway); or its definition (argument, length, effective encoding) changed since the last computation. A run
 * that changed nothing in either entity skips it. The marker is cleared BEFORE the sweep and set after it, so
 * a failed or cancelled sweep is retried by the next run, whatever that run reads.
 * <p>
 * Runs with the caller's session; the writes touch only the attribute's own field of the parent staging table
 * ({@code _sourceHash}, {@code _status}, {@code _loadId} are left as they are), so the parent row is not
 * "changed" for the incremental landing-to-staging transfer, and the master load - which re-reads every staging
 * row (docs/INCREMENTAL-TRANSFER.md &sect;5) - simply copies the value like any other field.
 */
final class ChildKeysPass
{
	private static final ObjectMapper MAPPER = new ObjectMapper();

	/**
	 * Child rows whose (parent hash, key) pairs are held in memory at once with the {@code hash} encoding - see {@link ChildKeysSweep}: 16 bytes each, 128 MB at the
	 * bound. Below it (every table up to 8 million child rows, Vol1m's 889,165 take 14 MB) the sweep is ONE scan of each table; above it, {@code K} partitions.
	 */
	static final int MAX_PAIRS_IN_MEMORY = 8000000;

	/**
	 * The same bound for the {@code plain} encoding, whose pairs also pin a readable key each in a dictionary (about 150 bytes a key): the bound of lot M3, 400,000
	 * (40-60 MB). That encoding is not meant for matching; it keeps the old, more partitioned shape.
	 */
	static final int MAX_PAIRS_IN_MEMORY_PLAIN = 400000;

	/**
	 * The smallest write transaction, in parents. {@code Settings.transferBatchSize} (500) is sized for the feeds, whose rows carry their own work; here a write is one
	 * field of a row already in hand, and a transaction costs a commit, an index refresh and a lock, so 576,692 parents took 1,154 of them. A larger batch lowers that
	 * overhead 10-fold; since 6.3.0 a Procedure of many changes runs in bounded memory (the flush threshold). The price: a failed batch rolls back 5,000 parents
	 * instead of 500 (the sweep is idempotent - the next run recomputes - and the marker stays empty), and the dataset is locked for writing a little longer per batch.
	 * A {@code transferBatchSize} above this value wins.
	 */
	static final int MIN_WRITE_BATCH = 5000;

	/** How often the child scan looks at the cancellation flag, in rows. */
	private static final int CANCEL_CHECK_EVERY = 20000;

	private ChildKeysPass()
	{
	}

	/** One {@code CHILD_KEYS} attribute, resolved against the entities of the project. */
	static final class Definition
	{
		final String entityName;
		final String attributeName;
		final String attributeId;
		final String argument;
		final int maxLength;
		final ChildKeysDerivation.Definition spec;
		/** The child attribute that refers to the parent, and its generated field - {@code null} when {@link #problem} is set. */
		final String referenceAttribute;
		final String referenceField;
		/** Why this definition cannot run yet (not in the generated model), or {@code null}. */
		final String problem;

		Definition(final String pEntityName, final String pAttributeName, final String pAttributeId, final String pArgument, final int pMaxLength,
			final ChildKeysDerivation.Definition pSpec, final String pReferenceAttribute, final String pReferenceField, final String pProblem)
		{
			this.entityName = pEntityName;
			this.attributeName = pAttributeName;
			this.attributeId = pAttributeId;
			this.argument = pArgument;
			this.maxLength = pMaxLength;
			this.spec = pSpec;
			this.referenceAttribute = pReferenceAttribute;
			this.referenceField = pReferenceField;
			this.problem = pProblem;
		}

		String label()
		{
			return this.entityName + "." + this.attributeName;
		}
	}

	/** The child entity of a {@code CHILD_KEYS} attribute row, for a screen's label ({@code ?} when the argument is missing or unreadable). Guarded: the field may not be in the published model. */
	static String childEntityLabel(final Adaptation pAttributeRow)
	{
		final String child = ChildKeysDerivation.childEntityOrNull(ChildKeysPass.guardedString(pAttributeRow, FactoryPaths.StagingAttribute.DERIVATION_ARGUMENT));
		return child == null ? "?" : child;
	}

	// ======================================================================================== definitions

	/**
	 * Every accepted {@code CHILD_KEYS} attribute of the project, resolved.
	 *
	 * @param pEntityIdByName entity name to {@code StagingEntity} id, the whole project
	 * @param pBuiltEntities the entities {@code StagingModel.build} builds a table for
	 * @param pForeignKeyFieldByAttributeByEntity entity name to (attribute name to the generated {@code <attribute>Ref} field)
	 * @param pEntities the entities with their attributes, as {@code StagingModel} sees them
	 * @throws OperationException the definition is wrong whatever the model looks like (see the class Javadoc)
	 */
	static List<Definition> read(final Adaptation pFactory, final Map<String, String> pEntityIdByName, final Set<String> pBuiltEntities,
		final Map<String, Map<String, String>> pForeignKeyFieldByAttributeByEntity, final List<StagingModel.EntityInfo> pEntities) throws OperationException
	{
		final List<Definition> out = new ArrayList<Definition>();
		for (final Map.Entry<String, String> entity : pEntityIdByName.entrySet())
		{
			final RequestResult rows = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE)
				.createRequestResult("./entity=" + CatalogReader.quote(entity.getValue()) + " and ./derivation=" + CatalogReader.quote(ChildKeysDerivation.DERIVATION_CHILD_KEYS));
			final List<Adaptation> attributeRows = new ArrayList<Adaptation>();
			try
			{
				for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
				{
					if (TechnicalKeyRule.isAcceptedLike(row.getString(FactoryPaths.StagingAttribute.STATUS)))
					{
						attributeRows.add(row);
					}
				}
			}
			finally
			{
				rows.close();
			}
			for (final Adaptation row : attributeRows)
			{
				out.add(ChildKeysPass.resolve(entity.getKey(), row, pEntityIdByName, pBuiltEntities, pForeignKeyFieldByAttributeByEntity, pEntities));
			}
		}
		return out;
	}

	private static Definition resolve(final String pEntityName, final Adaptation pRow, final Map<String, String> pEntityIdByName, final Set<String> pBuiltEntities,
		final Map<String, Map<String, String>> pForeignKeyFieldByAttributeByEntity, final List<StagingModel.EntityInfo> pEntities) throws OperationException
	{
		final String attributeName = pRow.getString(FactoryPaths.StagingAttribute.NAME);
		final String label = pEntityName + "." + attributeName;
		final String argument = ChildKeysPass.guardedString(pRow, FactoryPaths.StagingAttribute.DERIVATION_ARGUMENT);
		final Object rawMaxLength = pRow.get(FactoryPaths.StagingAttribute.MAX_LENGTH);
		final int maxLength = rawMaxLength instanceof Number && ((Number) rawMaxLength).intValue() > 0 ? ((Number) rawMaxLength).intValue()
			: ChildKeysDerivation.DEFAULT_MAX_LENGTH;
		final ChildKeysDerivation.Definition spec;
		try
		{
			spec = ChildKeysDerivation.parse(argument);
		}
		catch (final IllegalArgumentException ex)
		{
			throw EngineRefusal.coded("invalid.childKeys", "Staging attribute " + label + " (CHILD_KEYS): " + ex.getMessage(), label);
		}
		if (!pEntityIdByName.containsKey(spec.childEntity))
		{
			throw EngineRefusal.coded("invalid.childKeys", "Staging attribute " + label + " (CHILD_KEYS): the child entity '" + spec.childEntity + "' does not exist in this project", label);
		}
		StagingModel.EntityInfo child = null;
		for (final StagingModel.EntityInfo candidate : pEntities)
		{
			if (candidate.name.equals(spec.childEntity))
			{
				child = candidate;
			}
		}
		if (child == null)
		{
			throw EngineRefusal.coded("invalid.childKeys", "Staging attribute " + label + " (CHILD_KEYS): the child entity '" + spec.childEntity + "' could not be read", label);
		}
		final List<String> problems = new ArrayList<String>();
		for (final String field : spec.fields)
		{
			final StagingModel.AttributeInfo attribute = ChildKeysPass.attributeOf(child, field);
			if (attribute == null)
			{
				throw EngineRefusal.coded("invalid.childKeys", "Staging attribute " + label + " (CHILD_KEYS): '" + field + "' is not an attribute of " + spec.childEntity, label);
			}
			if (!TechnicalKeyRule.isAcceptedLike(attribute.status))
			{
				problems.add(spec.childEntity + "." + field + " is not accepted yet");
			}
		}
		final List<String> candidates = new ArrayList<String>();
		for (final StagingModel.AttributeInfo attribute : child.attributes)
		{
			if (pEntityName.equals(attribute.referenceEntity))
			{
				candidates.add(attribute.name);
			}
		}
		String referenceAttribute = null;
		if (spec.reference != null)
		{
			if (!candidates.contains(spec.reference))
			{
				throw EngineRefusal.coded("invalid.childKeys", "Staging attribute " + label + " (CHILD_KEYS): '" + spec.reference + "' is not an attribute of " + spec.childEntity
					+ " that refers to " + pEntityName + (candidates.isEmpty() ? " (it has none)" : " (it has: " + candidates + ")"), label);
			}
			referenceAttribute = spec.reference;
		}
		else if (candidates.size() == 1)
		{
			referenceAttribute = candidates.get(0);
		}
		else
		{
			throw EngineRefusal.coded("invalid.childKeys", "Staging attribute " + label + " (CHILD_KEYS): " + spec.childEntity + " has "
				+ (candidates.isEmpty() ? "no attribute that refers to " + pEntityName : candidates.size() + " attributes that refer to " + pEntityName + " " + candidates
					+ ": name one with \"reference\" in the derivation argument"), label);
		}
		String referenceField = null;
		final Map<String, String> childForeignKeys = pForeignKeyFieldByAttributeByEntity.get(spec.childEntity);
		if (childForeignKeys != null)
		{
			referenceField = childForeignKeys.get(referenceAttribute);
		}
		if (referenceField == null)
		{
			problems.add(spec.childEntity + "." + referenceAttribute + " has no foreign-key field in the staging model (accept the attribute and regenerate the staging)");
		}
		if (!pBuiltEntities.contains(pEntityName))
		{
			problems.add("the entity " + pEntityName + " has no staging table");
		}
		if (!pBuiltEntities.contains(spec.childEntity))
		{
			problems.add("the entity " + spec.childEntity + " has no staging table");
		}
		return new Definition(pEntityName, attributeName, pRow.getOccurrencePrimaryKey().format(), argument, maxLength, spec, referenceAttribute, referenceField,
			problems.isEmpty() ? null : ChildKeysPass.join(problems));
	}

	private static StagingModel.AttributeInfo attributeOf(final StagingModel.EntityInfo pEntity, final String pName)
	{
		for (final StagingModel.AttributeInfo attribute : pEntity.attributes)
		{
			if (attribute.name.equals(pName))
			{
				return attribute;
			}
		}
		return null;
	}

	// ============================================================================================ decision

	/** Pure: whether a sweep is due, see the class Javadoc. */
	static boolean needed(final boolean pAllRows, final long pParentWritten, final long pChildWritten, final boolean pLastRunPresent, final boolean pDefinitionUnchanged)
	{
		return pAllRows || pParentWritten > 0 || pChildWritten > 0 || !pLastRunPresent || !pDefinitionUnchanged;
	}

	/**
	 * The short tag of what the value depends on besides the data: the argument, the length and the EFFECTIVE encoding. Stored at the end of the last-run message
	 * as {@code (def <tag>)}. The tag starts with {@code h} (hash) or {@code p} (plain), which a hexadecimal tag of before lot M7 never did: a marker written by an
	 * earlier build is therefore always stale, whatever hash it holds, so the values computed in the readable form are recomputed once.
	 */
	static String definitionTag(final Definition pDefinition)
	{
		return ChildKeysPass.definitionTag(pDefinition.argument, pDefinition.maxLength, pDefinition.spec.encoding);
	}

	/** Pure form of {@link #definitionTag(Definition)}. */
	static String definitionTag(final String pArgument, final int pMaxLength, final String pEncoding)
	{
		final boolean plain = ChildKeysDerivation.ENCODING_PLAIN.equals(pEncoding);
		return (plain ? "p" : "h") + Integer.toHexString((pArgument + "|" + pMaxLength + "|" + (plain ? ChildKeysDerivation.ENCODING_PLAIN : ChildKeysDerivation.ENCODING_HASH)).hashCode());
	}

	/** Pure: whether the last-run message records a computation with exactly this tag. */
	static boolean definitionUnchanged(final String pLastMessage, final String pTag)
	{
		return pLastMessage != null && pLastMessage.endsWith("(def " + pTag + ")");
	}

	// ================================================================================================= run

	/**
	 * Computes one attribute if due.
	 *
	 * @return the detail node for the step: {@code entity}, {@code attribute}, {@code status} ({@code DONE}, {@code NOT_NEEDED}, {@code SKIPPED}, {@code STOPPED}),
	 *         a {@code reason} when not done, and the {@link ChildKeysSweep.Result} counters when done
	 * @throws OperationException the sweep failed (a read, or a write EBX refused); the attribute's marker is then empty, so the next run recomputes
	 */
	static ObjectNode run(final Session pSession, final Adaptation pFactory, final Adaptation pStagingDataset, final Definition pDefinition, final boolean pAllRows,
		final long pParentWritten, final long pChildWritten, final int pBatchSize, final FactoryJobs.Handle pHandle, final String pStepId) throws OperationException
	{
		final ObjectNode node = ChildKeysPass.MAPPER.createObjectNode();
		node.put("entity", pDefinition.entityName);
		node.put("attribute", pDefinition.attributeName);
		if (pDefinition.problem != null)
		{
			node.put("status", "SKIPPED");
			node.put("reason", pDefinition.problem);
			return node;
		}
		final AdaptationTable parentTable;
		final AdaptationTable childTable;
		try
		{
			parentTable = pStagingDataset.getTable(Path.parse(StagingModel.tablePath(pDefinition.entityName)));
			childTable = pStagingDataset.getTable(Path.parse(StagingModel.tablePath(pDefinition.spec.childEntity)));
		}
		catch (final RuntimeException ex)
		{
			node.put("status", "SKIPPED");
			node.put("reason", "a staging table is not in the published model yet: regenerate the staging model");
			return node;
		}
		final List<String> missing = new ArrayList<String>();
		final Path attributePath = Path.parse("./" + pDefinition.attributeName);
		if (parentTable.getTableOccurrenceRootNode().getNode(attributePath) == null)
		{
			missing.add(pDefinition.label());
		}
		final Path referencePath = Path.parse("./" + pDefinition.referenceField);
		if (childTable.getTableOccurrenceRootNode().getNode(referencePath) == null)
		{
			missing.add(pDefinition.spec.childEntity + "." + pDefinition.referenceField);
		}
		final List<Path> partPaths = new ArrayList<Path>();
		for (final String field : pDefinition.spec.fields)
		{
			final Path path = Path.parse("./" + field);
			partPaths.add(path);
			if (childTable.getTableOccurrenceRootNode().getNode(path) == null)
			{
				missing.add(pDefinition.spec.childEntity + "." + field);
			}
		}
		if (!missing.isEmpty())
		{
			node.put("status", "SKIPPED");
			node.put("reason", "field(s) not in the published staging model yet: " + missing + " - regenerate the staging model");
			return node;
		}

		final Adaptation attributeRow = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pDefinition.attributeId));
		final boolean lastRunFieldExists = attributeRow != null && ChildKeysPass.hasField(attributeRow, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_AT);
		final Object lastRunAt = lastRunFieldExists ? attributeRow.get(FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_AT) : null;
		final String lastMessage = lastRunFieldExists && ChildKeysPass.hasField(attributeRow, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_MESSAGE)
			? ChildKeysPass.guardedString(attributeRow, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_MESSAGE) : "";
		final String tag = ChildKeysPass.definitionTag(pDefinition);
		final boolean definitionUnchanged = ChildKeysPass.definitionUnchanged(lastMessage, tag);
		if (!ChildKeysPass.needed(pAllRows, pParentWritten, pChildWritten, lastRunAt != null, definitionUnchanged))
		{
			node.put("status", "NOT_NEEDED");
			node.put("reason", "no row written in " + pDefinition.entityName + " or " + pDefinition.spec.childEntity + " this run, computed before with the same definition");
			return node;
		}

		// the marker is emptied BEFORE the work: a failure or a stop leaves it empty, the next run recomputes
		ChildKeysPass.writeMarker(pSession, pFactory, attributeRow, lastRunFieldExists, null, null);

		final ChildKeysSweep.Children children = new ChildKeysSweep.Children()
		{
			@Override
			public void scan(final ChildKeysSweep.ChildVisitor pVisitor) throws Exception
			{
				final RequestResult rows = childTable.createRequestResult(null);
				try
				{
					long count = 0;
					final List<String> parts = new ArrayList<String>(partPaths.size());
					for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
					{
						parts.clear();
						for (final Path path : partPaths)
						{
							parts.add(ChildKeysPass.text(row.get(path)));
						}
						final String reference = row.getString(referencePath);
						pVisitor.child(reference == null || reference.trim().isEmpty() ? null : reference, parts);
						if (++count % ChildKeysPass.CANCEL_CHECK_EVERY == 0 && pHandle != null && pHandle.cancelled())
						{
							throw new ScanStopped();
						}
					}
				}
				finally
				{
					rows.close();
				}
			}
		};
		final int writeBatch = Math.max(pBatchSize, ChildKeysPass.MIN_WRITE_BATCH);
		final ChildKeysSweep.Parents parents = new ChildKeysSweep.Parents()
		{
			@Override
			public void scan(final ChildKeysSweep.ParentVisitor pVisitor) throws Exception
			{
				// ONE streaming read, in table order, never a predicate and never a lookup by key: the stored value comes with the row, and a row to change is written
				// through that very Adaptation. The result is frozen when it is created (EBX "Query snapshot isolation"), so the writes made from the visitor, each in
				// its own Procedure, neither disturb it nor are seen by it. The scan stays open meanwhile, which keeps the superseded index files of the table on disk
				// until it closes (docs/VOLUME.md, lot M10).
				final RequestResult rows = parentTable.createRequestResult(null);
				try
				{
					for (Adaptation row = rows.nextAdaptation(); row != null; row = rows.nextAdaptation())
					{
						if (!pVisitor.parent(row.getOccurrencePrimaryKey().format(), row.getString(attributePath), row))
						{
							return;
						}
					}
				}
				finally
				{
					rows.close();
				}
			}

			@Override
			public void write(final List<ChildKeysSweep.Update> pBatch)
			{
				final ProcedureResult result = ProgrammaticService.createForSession(pSession, pStagingDataset.getHome()).execute(new Procedure()
				{
					@Override
					public void execute(final ProcedureContext pContext) throws Exception
					{
						pContext.setExecutionInformation("mdm-factory:" + pStepId + ":child-keys:" + pDefinition.label());
						// only doModifyContent on the records in hand: no read of the table, so the index is not refreshed between two rows of the batch
						for (final ChildKeysSweep.Update update : pBatch)
						{
							final Adaptation parent = (Adaptation) update.handle;
							final ValueContextForUpdate value = pContext.getContext(parent.getAdaptationName());
							value.setValue(update.value, attributePath);
							pContext.doModifyContent(parent, value);
						}
					}
				});
				if (result.hasFailed())
				{
					throw new IllegalStateException(ChildKeysPass.shorten(result.getExceptionFullMessage(Locale.ENGLISH), 500));
				}
			}
		};
		final ChildKeysSweep.Cancellation cancellation = new ChildKeysSweep.Cancellation()
		{
			@Override
			public boolean cancelled()
			{
				return pHandle != null && pHandle.cancelled();
			}
		};

		final long childRows = ChildKeysPass.size(childTable);
		final int maxPairs = ChildKeysDerivation.ENCODING_PLAIN.equals(pDefinition.spec.encoding) ? ChildKeysPass.MAX_PAIRS_IN_MEMORY_PLAIN : ChildKeysPass.MAX_PAIRS_IN_MEMORY;
		final int partitions = ChildKeysSweep.partitionCount(childRows, maxPairs);
		final ChildKeysSweep.Result result;
		try
		{
			result = ChildKeysSweep.run(children, parents, pDefinition.spec, pDefinition.maxLength, partitions, childRows, writeBatch, cancellation);
		}
		catch (final ScanStopped ex)
		{
			node.put("status", "STOPPED");
			node.put("reason", "the run was stopped during the scan of " + pDefinition.spec.childEntity + "; the next run recomputes it");
			return node;
		}
		catch (final Exception ex)
		{
			throw EngineRefusal.coded("unexpected.childKeysCompute", "CHILD_KEYS " + pDefinition.label() + " could not be computed (" + ex.getMessage()
				+ "); rows already updated are kept, the next load recomputes it", pDefinition.label());
		}
		node.put("childRows", childRows);
		node.put("childrenRead", result.childrenRead);
		node.put("passes", result.partitions);
		node.put("mode", result.partitions == 1 ? "single-pass" : "partitioned");
		node.put("scans", result.scans);
		node.put("hashRestarts", result.hashRestarts);
		node.put("writeBatchSize", writeBatch);
		node.put("peakPairs", result.peakPairs);
		node.put("childrenWithoutParent", result.childrenWithoutParent);
		node.put("childrenWithoutKey", result.childrenWithoutKey);
		node.put("childKeysOfMissingParent", result.childrenOfMissingParent);
		node.put("parentsRead", result.parentsRead);
		node.put("parentsWithKeys", result.parentsWithKeys);
		node.put("parentsUpdated", result.parentsUpdated);
		node.put("parentsCleared", result.parentsCleared);
		node.put("parentsUnchanged", result.parentsUnchanged);
		node.put("writesSkippedUnchanged", result.writesSkippedUnchanged);
		node.put("writeBatches", result.writeBatches);
		node.put("partFolds", result.partFolds);
		node.put("partCacheHits", result.partCacheHits);
		node.put("childScanMs", result.childScanMs);
		node.put("sortMs", result.sortMs);
		node.put("parentPassMs", result.parentPassMs);
		node.put("writeMs", result.writeMs);
		node.put("tokensDropped", result.tokensDropped);
		node.put("parentsCapped", result.parentsCapped);
		if (result.cancelled)
		{
			node.put("status", "STOPPED");
			node.put("reason", "the run was stopped while updating the parents; the next run recomputes it");
			return node;
		}
		node.put("status", "DONE");
		node.put("summary", result.summary());
		ChildKeysPass.writeMarker(pSession, pFactory, attributeRow, lastRunFieldExists, new Date(), ChildKeysPass.shorten(result.summary(), 440) + " (def " + tag + ")");
		return node;
	}

	/**
	 * The load was stopped before the sweep: nothing is computed, but when a row of either entity was written this run the marker is emptied, so
	 * that the next run recomputes whatever it reads (it may read nothing: the rows already staged are not read again in scope new).
	 */
	static ObjectNode stopped(final Session pSession, final Adaptation pFactory, final Definition pDefinition, final long pParentWritten, final long pChildWritten)
	{
		final ObjectNode node = ChildKeysPass.MAPPER.createObjectNode();
		node.put("entity", pDefinition.entityName);
		node.put("attribute", pDefinition.attributeName);
		node.put("status", "STOPPED");
		node.put("reason", "the load was stopped before the computation; the next load recomputes it");
		if (pDefinition.problem == null && (pParentWritten > 0 || pChildWritten > 0))
		{
			final Adaptation attributeRow = pFactory.getTable(FactoryPaths.STAGING_ATTRIBUTE).lookupAdaptationByPrimaryKey(PrimaryKey.parseString(pDefinition.attributeId));
			if (attributeRow != null && ChildKeysPass.hasField(attributeRow, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_AT))
			{
				ChildKeysPass.writeMarker(pSession, pFactory, attributeRow, true, null, null);
			}
		}
		return node;
	}

	/** Thrown from inside the child scan to leave it when the run is cancelled. */
	private static final class ScanStopped extends RuntimeException
	{
		private static final long serialVersionUID = 1L;

		ScanStopped()
		{
			super("scan stopped", null, false, false);
		}
	}

	// ============================================================================================ helpers

	private static String shorten(final String pText, final int pMax)
	{
		if (pText == null)
		{
			return "";
		}
		return pText.length() <= pMax ? pText : pText.substring(0, pMax - 3) + "...";
	}

	private static long size(final AdaptationTable pTable)
	{
		final RequestResult rows = pTable.createRequestResult(null);
		try
		{
			return rows.getSize();
		}
		finally
		{
			rows.close();
		}
	}

	/** {@code Adaptation.get} throws on a path the table does not define (CLAUDE.md &sect;9): true when the record's table defines it. */
	private static boolean hasField(final Adaptation pRow, final Path pPath)
	{
		return pRow.getContainerTable().getTableOccurrenceRootNode().getNode(pPath) != null;
	}

	private static String guardedString(final Adaptation pRow, final Path pPath)
	{
		return ChildKeysPass.hasField(pRow, pPath) ? pRow.getString(pPath) : null;
	}

	/** A child value as text: a decimal without trailing zeros, anything else as EBX renders it. */
	static String text(final Object pValue)
	{
		if (pValue == null)
		{
			return null;
		}
		if (pValue instanceof BigDecimal)
		{
			return ((BigDecimal) pValue).stripTrailingZeros().toPlainString();
		}
		return String.valueOf(pValue);
	}

	private static String join(final List<String> pItems)
	{
		final StringBuilder out = new StringBuilder();
		for (final String item : pItems)
		{
			out.append(out.length() == 0 ? "" : "; ").append(item);
		}
		return out.toString();
	}

	/**
	 * Sets (or empties, when {@code pAt} is null) the last-run marker of the attribute. A failure is logged, never thrown: an empty marker
	 * only means "recompute at the next run", the safe side.
	 */
	private static void writeMarker(final Session pSession, final Adaptation pFactory, final Adaptation pAttributeRow, final boolean pFieldsExist, final Date pAt,
		final String pMessage)
	{
		if (pAttributeRow == null || !pFieldsExist)
		{
			return;
		}
		final boolean messageField = ChildKeysPass.hasField(pAttributeRow, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_MESSAGE);
		final ProcedureResult result = ProgrammaticService.createForSession(pSession, pFactory.getHome()).execute(new Procedure()
		{
			@Override
			public void execute(final ProcedureContext pContext) throws Exception
			{
				final ValueContextForUpdate value = pContext.getContext(pAttributeRow.getAdaptationName());
				value.setValue(pAt, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_AT);
				if (messageField && (pAt != null || pMessage != null))
				{
					value.setValue(pMessage, FactoryPaths.StagingAttribute.DERIVATION_LAST_RUN_MESSAGE);
				}
				pContext.doModifyContent(pAttributeRow, value);
			}
		});
		if (result.hasFailed())
		{
			LoggingCategory.getKernel().error("[mdm-factory] could not write the CHILD_KEYS marker of " + pAttributeRow.getOccurrencePrimaryKey().format() + ": "
				+ result.getExceptionFullMessage(Locale.ENGLISH));
		}
	}
}
