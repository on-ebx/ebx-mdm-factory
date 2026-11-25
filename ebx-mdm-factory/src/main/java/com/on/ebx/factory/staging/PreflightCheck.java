package com.on.ebx.factory.staging;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.on.ebx.factory.quality.CleaningSteps;
import com.on.ebx.factory.quality.Detectors;

/**
 * Staging preflight lot H1 (2026-10-17): replays ONE accepted link on the landing values the way the loader runs it, BEFORE any row is written, and says what the build would do
 * to those rows. The verifications of lots D and E ({@link TransformVerifier}, {@link StepChainCheck}) run when a proposal is made; a link a person accepted or edited afterwards,
 * or landing data that changed since, was never checked again (measured on TiersB2CRetestLanding: one accepted {@code isodate()} link on a {@code dd/MM/yyyy} column cost 140 refused rows).
 * <p>
 * <b>The replay</b> is the loader's: the ACCEPTED cleaning steps in position order ({@link CleaningSteps#applyPipeline}), then the mapping's chain through the injected {@link
 * TransformVerifier.Evaluator} (in production {@code Transformations.apply} of the Connectors engine, the code the load runs), then the checks EBX makes at the write: a typed
 * field must receive its xs lexical form, a string must fit its maximum length. Pure: the caller supplies the values (a bounded sample of the distinct values with their rows - no
 * connector call), so the decision is tested on the real B2C files.
 * <p>
 * <b>What it says</b> ({@link PreflightFinding} issues):
 * <ul>
 * <li>BLOCKING - {@link PreflightFinding#CONVERSION} (the chain fails on the value or leaves one the typed field cannot store), {@link PreflightFinding#LOST} (a typed field gets nothing
 * from a value), {@link PreflightFinding#TOO_LONG}, {@link PreflightFinding#MANDATORY_EMPTY} (the loader refuses a row that leaves a mandatory attribute empty; counted per link, so per
 * source when several feed the attribute; no fix: map a column or make the attribute optional);</li>
 * <li>WARNING, never blocking - {@link PreflightFinding#LOST_PLUS} (a phone loses its {@code +}), {@link PreflightFinding#DUPLICATES}
 * (the only business key of the entity repeats inside the source);</li>
 * <li>information - {@link PreflightFinding#NOT_CHECKED}: a chain that reads another column, a live lookup or the clock, or a link of a collection feed.</li>
 * </ul>
 * When a blocking or a plus issue has a fix, it is the one {@link TransformVerifier} derives from the measured profile and only when it converts EVERY sampled value (a day / month
 * order no value proves is kept as {@link PreflightFinding#ambiguousPipeline}, never offered). Counts, format names and pipelines only: no value leaves the check.
 */
public final class PreflightCheck
{
	private PreflightCheck()
	{
	}

	/**
	 * @param pLookups the reference lookups the cleaning steps resolve against (may be {@link CleaningSteps.Lookups#empty()})
	 * @return the finding of the link; its {@link PreflightFinding#issues} are empty when the link replays clean
	 */
	public static PreflightFinding check(final PreflightLink pLink, final TransformVerifier.Evaluator pEvaluator, final CleaningSteps.Lookups pLookups)
	{
		final String[] written = PreflightCheck.written(pLink.profile);
		final PreflightFinding finding = new PreflightFinding(pLink.mappingId, pLink.entityName, pLink.attributeName, pLink.sourceSystem, pLink.tableName, pLink.column,
			pLink.dataType, pLink.pipeline == null ? "" : pLink.pipeline, written[0], written[1]);
		finding.sampledValues = pLink.sampled;
		if (pLink.collectionFeed)
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.NOT_CHECKED, false, 0L, 0L, false,
				"the feed reads a sub-element of the landing row: the column's values are not the feed's values"));
			return finding;
		}
		if (pLink.pipeline == null)
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.NOT_CHECKED, false, 0L, 0L, false, "the transformation of this old mapping cannot be translated to a pipeline"));
			return finding;
		}
		if (TransformVerifier.readsOutsideItsColumn(pLink.pipeline))
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.NOT_CHECKED, false, 0L, 0L, false,
				"the chain reads another column, a live lookup or the clock: it cannot be replayed on this column alone"));
			return finding;
		}
		final boolean typed = TransformVerifier.isTyped(pLink.dataType);
		final boolean plusCheck = pLink.profile != null && pLink.profile.isPhoneLike() && pLink.profile.leadingPlusRows() > 0L;
		final boolean string = "STRING".equalsIgnoreCase(pLink.dataType);

		long seen = 0L;
		long emptiedBySteps = 0L;
		long conversion = 0L;
		long lost = 0L;
		long tooLong = 0L;
		long plusLost = 0L;
		long emptyOut = 0L;
		final Map<String, Long> cleanedInputs = new LinkedHashMap<String, Long>();
		final Map<String, Long> outputs = new LinkedHashMap<String, Long>();
		for (final Detectors.ValueCount input : pLink.values)
		{
			if (input.value == null || input.value.trim().isEmpty())
			{
				continue;
			}
			seen += input.count;
			final boolean startsWithPlus = input.value.trim().startsWith("+");
			String cleaned = input.value;
			if (!pLink.steps.isEmpty())
			{
				final CleaningSteps.Result stepped = CleaningSteps.applyPipeline(pLink.steps, input.value, null, pLookups);
				if (!stepped.unresolved)
				{
					cleaned = stepped.value; // an unresolved lookup step leaves the value raw, like the Mapping matrix's sample
				}
			}
			if (cleaned == null || cleaned.trim().isEmpty())
			{
				emptiedBySteps += input.count; // nulled by a cleaning step (EMPTY_TO_NULL): nothing reaches the chain
				if (plusCheck && startsWithPlus)
				{
					plusLost += input.count;
				}
				continue;
			}
			PreflightCheck.add(cleanedInputs, cleaned, input.count);
			final String out;
			try
			{
				out = pLink.pipeline.isEmpty() ? cleaned.trim() : pEvaluator.apply(pLink.pipeline, cleaned);
			}
			catch (final TransformVerifier.Unverifiable ex)
			{
				return PreflightCheck.notCheckedFinding(finding);
			}
			catch (final NullPointerException | IllegalStateException ex)
			{
				return PreflightCheck.notCheckedFinding(finding); // the chain needs a context this check does not have
			}
			catch (final Exception ex)
			{
				conversion += input.count; // the load refuses the row: the chain throws on this value
				continue;
			}
			if (out == null || out.trim().isEmpty())
			{
				emptyOut += input.count;
				if (typed)
				{
					lost += input.count;
				}
				if (plusCheck && startsWithPlus)
				{
					plusLost += input.count;
				}
				continue;
			}
			final String result = out.trim();
			if (typed && !TransformVerifier.isValidFor(pLink.dataType, result))
			{
				conversion += input.count;
				continue;
			}
			if (string && pLink.maxLength != null && result.length() > pLink.maxLength.intValue())
			{
				tooLong += input.count;
			}
			if (plusCheck && startsWithPlus && !result.startsWith("+"))
			{
				plusLost += input.count;
			}
			if (pLink.singleBusinessKey)
			{
				PreflightCheck.add(outputs, result, input.count);
			}
			// B2B test (2026-10-23): the identifier shape of what the attribute receives, compared across its links by shapeOutliers
			PreflightCheck.add(finding.outputShapes, RowFacts.identifierLike(result) ? com.on.ebx.factory.quality.Shapes.of(result) : PreflightFinding.OTHER_SHAPE,
				input.count);
		}

		final long of = pLink.filledRows >= 0L ? Math.max(pLink.filledRows, seen) : seen;
		final boolean atLeast = pLink.sampled;
		// the fix and the reason come from the verifier, on the inputs the chain really gets (after the steps)
		String reason = "";
		if ((conversion + lost + plusLost > 0L) && (typed || plusCheck))
		{
			final List<Detectors.ValueCount> verifierInputs = new ArrayList<Detectors.ValueCount>();
			for (final Map.Entry<String, Long> entry : cleanedInputs.entrySet())
			{
				verifierInputs.add(new Detectors.ValueCount(entry.getKey(), entry.getValue().longValue()));
			}
			final TransformVerifier.Result verdict = TransformVerifier.verify(pLink.dataType, pLink.pipeline, verifierInputs, pLink.profile, pEvaluator, pLink.sampled);
			reason = verdict.reason;
			if (TransformVerifier.FIXED.equals(verdict.verdict))
			{
				finding.fixPipeline = verdict.replacementPipeline;
				finding.fixRows = verdict.checkedRows;
			}
			else if (TransformVerifier.AMBIGUOUS.equals(verdict.verdict))
			{
				finding.ambiguousPipeline = verdict.replacementPipeline;
			}
		}
		if (conversion > 0L)
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.CONVERSION, true, conversion, of, atLeast,
				reason.isEmpty() ? PreflightCheck.conversionSentence(pLink, conversion, seen) : reason));
		}
		if (lost > 0L)
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.LOST, true, lost, of, atLeast,
				"'" + PreflightCheck.chain(pLink) + "' gives nothing for " + lost + " of " + seen + " rows that hold a value; the " + PreflightCheck.typeWords(pLink.dataType)
					+ " field stays empty."));
		}
		if (tooLong > 0L)
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.TOO_LONG, true, tooLong, of, atLeast,
				tooLong + " of " + seen + " values are longer than the " + pLink.maxLength + " characters the field allows."));
		}
		if (plusLost > 0L)
		{
			finding.issues.add(new PreflightFinding.Issue(PreflightFinding.LOST_PLUS, false, plusLost, pLink.profile.leadingPlusRows(), atLeast,
				"the leading + (the country of the number) is dropped from " + plusLost + " of " + pLink.profile.leadingPlusRows() + " rows."));
		}
		if (pLink.mandatory)
		{
			final long sourceEmpty = pLink.rows >= 0L && pLink.filledRows >= 0L ? Math.max(0L, pLink.rows - pLink.filledRows) : 0L;
			final long empty = sourceEmpty + emptiedBySteps + emptyOut;
			if (empty > 0L)
			{
				// the loader refuses a row that leaves a mandatory attribute empty (RejectReason.mandatory): blocking, counted per link, i.e. per source
				finding.issues.add(new PreflightFinding.Issue(PreflightFinding.MANDATORY_EMPTY, true, empty, pLink.rows >= 0L ? pLink.rows : of + empty, atLeast,
					empty + " rows leave this mandatory attribute empty and would be refused: map a column or make the attribute optional."));
			}
		}
		if (pLink.singleBusinessKey)
		{
			long duplicates = 0L;
			for (final Long count : outputs.values())
			{
				if (count.longValue() > 1L)
				{
					duplicates += count.longValue() - 1L;
				}
			}
			if (duplicates > 0L)
			{
				finding.issues.add(new PreflightFinding.Issue(PreflightFinding.DUPLICATES, false, duplicates, of, atLeast,
					duplicates + " rows repeat the value of the business key inside this source."));
			}
		}
		return finding;
	}

	/**
	 * Lot M7 (2026-10-18): the finding of an ACCEPTED entity that no accepted mapping feeds - a WARNING ({@link PreflightFinding#NO_FEED}), never blocking: the
	 * build goes on, its table stays empty, the load says so on the row. Measured on E2EMASTER20261017235630 ({@code ContactEmail}: accepted in bulk with four
	 * attributes and no mapping, the preflight reported nothing, the load wrote 0 rows without a word). Pure.
	 */
	public static PreflightFinding noFeed(final String pEntityName)
	{
		final PreflightFinding finding = new PreflightFinding("", pEntityName, "", "", "", "", "STRING", "", "", "");
		finding.issues.add(new PreflightFinding.Issue(PreflightFinding.NO_FEED, false, 0L, 0L, false,
			"no accepted mapping feeds " + pEntityName + ": nothing will be loaded into it. Map at least one attribute to a landing column and accept the mapping, or reject the entity."));
		return finding;
	}

	/**
	 * Lot M15 (2026-10-18): a filled landing column that NO accepted mapping reads, of a source that feeds an entity: where the check looks for a column the sources
	 * of that entity should have in common. Plain value object.
	 */
	public static final class UnmappedColumn
	{
		public final String entityName;
		public final String sourceSystem;
		public final String tableName;
		public final String column;
		/** The rows of the landing table, -1 when unknown. */
		public final long rows;
		/** The rows where the column is filled. */
		public final long filledRows;
		public final NormalisationProfile profile;
		/**
		 * B2B test (2026-10-23): most filled values of the column are keys of its own table - an identifier of the row repeated in a sub-record (SAP's
		 * {@code to_BusinessPartnerTax_results_0__BusinessPartner}, {@code 0001690977}, the row's own {@code BusinessPartner}); never a business value to look for.
		 */
		public final boolean identifier;

		public UnmappedColumn(final String pEntityName, final String pSourceSystem, final String pTableName, final String pColumn, final long pRows, final long pFilledRows,
			final NormalisationProfile pProfile)
		{
			this(pEntityName, pSourceSystem, pTableName, pColumn, pRows, pFilledRows, pProfile, false);
		}

		public UnmappedColumn(final String pEntityName, final String pSourceSystem, final String pTableName, final String pColumn, final long pRows, final long pFilledRows,
			final NormalisationProfile pProfile, final boolean pIdentifier)
		{
			this.identifier = pIdentifier;
			this.entityName = pEntityName;
			this.sourceSystem = pSourceSystem;
			this.tableName = pTableName;
			this.column = pColumn;
			this.rows = pRows;
			this.filledRows = pFilledRows;
			this.profile = pProfile;
		}
	}

	/**
	 * Lot M15: for each entity, the source systems that feed it but leave an attribute unfed that the OTHER sources feed with phone-like values only (every link of the
	 * attribute has a measured profile whose values are phone numbers) - where {@link #notFedBySource} has something to look for. Entity name to source systems, in order;
	 * empty when no attribute is both phone-like and fed by a source other than one that feeds the entity. Pure.
	 */
	public static Map<String, java.util.Set<String>> sourcesToLookAt(final List<PreflightLink> pLinks)
	{
		final Map<String, java.util.Set<String>> out = new LinkedHashMap<String, java.util.Set<String>>();
		final Map<String, java.util.Set<String>> sourcesByEntity = new LinkedHashMap<String, java.util.Set<String>>();
		for (final PreflightLink link : pLinks)
		{
			PreflightCheck.setOf(sourcesByEntity, link.entityName).add(link.sourceSystem);
		}
		for (final Map.Entry<String, Map<String, java.util.Set<String>>> entity : PreflightCheck.phoneAttributes(pLinks).entrySet())
		{
			for (final String source : sourcesByEntity.get(entity.getKey()))
			{
				for (final java.util.Set<String> fedBy : entity.getValue().values())
				{
					if (!fedBy.contains(source))
					{
						PreflightCheck.setOf(out, entity.getKey()).add(source);
					}
				}
			}
		}
		return out;
	}

	/**
	 * Lot M15 (2026-10-18): the warnings "{@code Client.mobile}: fed by ECOM and LOYALTY, not by STORES although {@code STORES.TELEPHONE} is filled (234 of 234)". Measured
	 * on E2EMASTER20261018090711: the language-model pass mapped the phone column of the STORES source only to a superseded per-source entity; {@code Client.mobile} stayed
	 * empty for all 234 STORES rows, no check said so. The rule is structural, no name is read: an attribute whose every link, in at least one source, reads phone-like
	 * values (the measured profile) is "fed by" those sources; a source that feeds the same entity without feeding that attribute and holds a FILLED, UNMAPPED column
	 * of phone-like values leaves it out. One warning per (attribute, column). Pure.
	 *
	 * @param pLinks every accepted link the check replayed
	 * @param pUnmapped the filled columns no accepted mapping reads, of the sources that feed each entity
	 */
	public static List<PreflightFinding> notFedBySource(final List<PreflightLink> pLinks, final List<UnmappedColumn> pUnmapped)
	{
		final List<PreflightFinding> out = new ArrayList<PreflightFinding>();
		final Map<String, Map<String, java.util.Set<String>>> phones = PreflightCheck.phoneAttributes(pLinks);
		for (final UnmappedColumn column : pUnmapped)
		{
			if (column.filledRows <= 0L || column.profile == null || !column.profile.isPhoneLike() || column.identifier)
			{
				continue; // B2B test (2026-10-23): a column that repeats the keys of its table is an identifier, whatever its values look like
			}
			final Map<String, java.util.Set<String>> attributes = phones.get(column.entityName);
			if (attributes == null)
			{
				continue;
			}
			for (final Map.Entry<String, java.util.Set<String>> attribute : attributes.entrySet())
			{
				if (attribute.getValue().contains(column.sourceSystem))
				{
					continue; // this source feeds the attribute (from this table or another)
				}
				final PreflightFinding finding = new PreflightFinding("", column.entityName, attribute.getKey(), column.sourceSystem, column.tableName, column.column, "STRING", "", "",
					"");
				finding.fedBy.addAll(attribute.getValue());
				finding.issues.add(new PreflightFinding.Issue(PreflightFinding.NOT_FED_BY_SOURCE, false, column.filledRows, column.rows >= 0L ? column.rows : column.filledRows,
					column.profile.sampled,
					column.entityName + "." + attribute.getKey() + ": fed by " + String.join(" and ", attribute.getValue()) + ", not by " + column.sourceSystem + " although "
						+ column.sourceSystem + "." + column.column + " is filled (" + column.filledRows + " of " + (column.rows >= 0L ? column.rows : column.filledRows)
						+ ") - map it or say why."));
				out.add(finding);
			}
		}
		return out;
	}

	/**
	 * B2B test (2026-10-23): for every attribute fed by at least two replayed links, the values a link gives it that do not have the identifier shape the OTHER links give it
	 * in at least {@link NormalisationProfile#DOMINANT_PERCENT} % of their values - a warning per link ({@link PreflightFinding#SHAPE_OUTLIERS}). Measured on the B2B test:
	 * SAP's tax number column gave {@code Organisation.siren} 25 VAT numbers among 150 values while Salesforce and the website gave nine digits only. Values that are not
	 * identifier-like (a name, a phone, a date) give no shape to compare: an attribute of such values is never reported. Shapes and counts only. Pure.
	 */
	public static List<PreflightFinding> shapeOutliers(final List<PreflightFinding> pFindings)
	{
		final Map<String, List<PreflightFinding>> byAttribute = new LinkedHashMap<String, List<PreflightFinding>>();
		for (final PreflightFinding finding : pFindings)
		{
			if (finding.outputShapes.isEmpty() || finding.attributeName.isEmpty())
			{
				continue;
			}
			final String key = finding.entityName + "." + finding.attributeName;
			List<PreflightFinding> links = byAttribute.get(key);
			if (links == null)
			{
				links = new ArrayList<PreflightFinding>();
				byAttribute.put(key, links);
			}
			links.add(finding);
		}
		final List<PreflightFinding> out = new ArrayList<PreflightFinding>();
		for (final List<PreflightFinding> links : byAttribute.values())
		{
			if (links.size() < 2)
			{
				continue;
			}
			for (final PreflightFinding link : links)
			{
				final Map<String, Long> others = new LinkedHashMap<String, Long>();
				final List<String> otherSources = new ArrayList<String>();
				for (final PreflightFinding other : links)
				{
					if (other == link)
					{
						continue;
					}
					for (final Map.Entry<String, Long> entry : other.outputShapes.entrySet())
					{
						PreflightCheck.add(others, entry.getKey(), entry.getValue().longValue());
					}
					if (!otherSources.contains(other.sourceSystem))
					{
						otherSources.add(other.sourceSystem);
					}
				}
				String dominant = null;
				long dominantRows = 0L;
				long otherTotal = 0L;
				for (final Map.Entry<String, Long> entry : others.entrySet())
				{
					otherTotal += entry.getValue().longValue();
					if (entry.getValue().longValue() > dominantRows)
					{
						dominant = entry.getKey();
						dominantRows = entry.getValue().longValue();
					}
				}
				if (dominant == null || PreflightFinding.OTHER_SHAPE.equals(dominant) || dominantRows * 100L < otherTotal * NormalisationProfile.DOMINANT_PERCENT)
				{
					continue;
				}
				long total = 0L;
				for (final Long rows : link.outputShapes.values())
				{
					total += rows.longValue();
				}
				final Long same = link.outputShapes.get(dominant);
				final long outliers = total - (same == null ? 0L : same.longValue());
				if (outliers <= 0L)
				{
					continue;
				}
				final PreflightFinding finding = new PreflightFinding(link.mappingId, link.entityName, link.attributeName, link.sourceSystem, link.tableName, link.column,
					link.dataType, link.pipeline, "", "");
				finding.expectedShape = dominant;
				finding.fedBy.addAll(otherSources);
				finding.issues.add(new PreflightFinding.Issue(PreflightFinding.SHAPE_OUTLIERS, false, outliers, total, link.sampledValues,
					outliers + " of " + total + " values " + link.sourceSystem + "." + link.column + " gives " + link.entityName + "." + link.attributeName
						+ " do not have the shape " + dominant + " that " + String.join(" and ", otherSources) + " give it in " + Math.round(100.0 * dominantRows / otherTotal)
						+ "% of their values - split the column with a filtering step, or say why."));
				out.add(finding);
			}
		}
		return out;
	}

	/**
	 * B2B test (2026-10-23): whether a column is an identifier of its table's rows - at least {@code RowFacts.SHARE_PERCENT} % of its filled rows hold a value that is a key
	 * of the same table ({@code pKeyValues}, the distinct key values read). Pure.
	 */
	public static boolean holdsTableKeys(final List<Detectors.ValueCount> pValues, final java.util.Set<String> pKeyValues)
	{
		if (pValues == null || pKeyValues == null || pKeyValues.isEmpty())
		{
			return false;
		}
		long filled = 0L;
		long keys = 0L;
		for (final Detectors.ValueCount value : pValues)
		{
			if (value.value == null || value.value.trim().isEmpty())
			{
				continue;
			}
			filled += value.count;
			if (pKeyValues.contains(value.value.trim()))
			{
				keys += value.count;
			}
		}
		return filled >= RowFacts.MIN_ROWS && keys * 100L >= filled * RowFacts.SHARE_PERCENT;
	}

	/**
	 * Entity name to attribute name to the source systems that feed it, for the attributes whose every link reads phone-like values (a measured profile on each); an attribute
	 * with a link without a profile is left out (nothing is claimed). Collection feeds are left out.
	 */
	private static Map<String, Map<String, java.util.Set<String>>> phoneAttributes(final List<PreflightLink> pLinks)
	{
		final Map<String, Map<String, java.util.Set<String>>> sources = new LinkedHashMap<String, Map<String, java.util.Set<String>>>();
		final Map<String, java.util.Set<String>> disqualified = new LinkedHashMap<String, java.util.Set<String>>();
		for (final PreflightLink link : pLinks)
		{
			if (link.collectionFeed)
			{
				continue;
			}
			Map<String, java.util.Set<String>> byAttribute = sources.get(link.entityName);
			if (byAttribute == null)
			{
				byAttribute = new LinkedHashMap<String, java.util.Set<String>>();
				sources.put(link.entityName, byAttribute);
			}
			PreflightCheck.setOf(byAttribute, link.attributeName).add(link.sourceSystem);
			if (link.profile == null || !link.profile.isPhoneLike())
			{
				PreflightCheck.setOf(disqualified, link.entityName).add(link.attributeName);
			}
		}
		final Map<String, Map<String, java.util.Set<String>>> out = new LinkedHashMap<String, Map<String, java.util.Set<String>>>();
		for (final Map.Entry<String, Map<String, java.util.Set<String>>> entity : sources.entrySet())
		{
			final Map<String, java.util.Set<String>> kept = new LinkedHashMap<String, java.util.Set<String>>();
			for (final Map.Entry<String, java.util.Set<String>> attribute : entity.getValue().entrySet())
			{
				final java.util.Set<String> bad = disqualified.get(entity.getKey());
				if (bad == null || !bad.contains(attribute.getKey()))
				{
					kept.put(attribute.getKey(), attribute.getValue());
				}
			}
			if (!kept.isEmpty())
			{
				out.put(entity.getKey(), kept);
			}
		}
		return out;
	}

	private static java.util.Set<String> setOf(final Map<String, java.util.Set<String>> pMap, final String pKey)
	{
		java.util.Set<String> set = pMap.get(pKey);
		if (set == null)
		{
			set = new java.util.LinkedHashSet<String>();
			pMap.put(pKey, set);
		}
		return set;
	}

	private static PreflightFinding notCheckedFinding(final PreflightFinding pFinding)
	{
		pFinding.issues.clear();
		pFinding.outputShapes.clear();
		pFinding.fixPipeline = null;
		pFinding.ambiguousPipeline = null;
		pFinding.issues.add(new PreflightFinding.Issue(PreflightFinding.NOT_CHECKED, false, 0L, 0L, false,
			"the chain needs a context (another column, a live lookup) that this check does not have"));
		return pFinding;
	}

	private static void add(final Map<String, Long> pMap, final String pValue, final long pRows)
	{
		final Long seen = pMap.get(pValue);
		pMap.put(pValue, Long.valueOf((seen == null ? 0L : seen.longValue()) + pRows));
	}

	private static String chain(final PreflightLink pLink)
	{
		return pLink.pipeline == null || pLink.pipeline.isEmpty() ? "the value as it is" : pLink.pipeline;
	}

	private static String conversionSentence(final PreflightLink pLink, final long pRows, final long pSeen)
	{
		return "'" + PreflightCheck.chain(pLink) + "' gives a value the " + PreflightCheck.typeWords(pLink.dataType) + " field cannot store for " + pRows + " of " + pSeen + " rows.";
	}

	private static String typeWords(final String pDataType)
	{
		final String type = pDataType == null ? "STRING" : pDataType.trim().toUpperCase(Locale.ROOT);
		if ("DATE".equals(type))
		{
			return "date (yyyy-MM-dd)";
		}
		if ("TIMESTAMP".equals(type))
		{
			return "date-time";
		}
		if ("DECIMAL".equals(type))
		{
			return "decimal number";
		}
		if ("INTEGER".equals(type))
		{
			return "integer";
		}
		if ("BOOLEAN".equals(type))
		{
			return "boolean";
		}
		return "text";
	}

	private static String numberWords(final String pShape)
	{
		switch (pShape)
		{
			case "INT":
				return "integer";
			case "DEC_DOT":
				return "decimal dot";
			case "DEC_COMMA":
				return "decimal comma";
			case "THOUSAND_DOT_COMMA":
				return "thousands dot, decimal comma";
			case "THOUSAND_COMMA_DOT":
				return "thousands comma, decimal dot";
			case "THOUSAND_SPACE":
				return "thousands space";
			default:
				return pShape.toLowerCase(Locale.ROOT).replace('_', ' ');
		}
	}

	/**
	 * How the column's dates or numbers are written, from its measured profile: {@code {"dd/MM/yyyy", "date"}}, {@code {"decimal comma", "number"}}, or two empty strings. A FORMAT
	 * name, never a value.
	 */
	public static String[] written(final NormalisationProfile pProfile)
	{
		if (pProfile == null || pProfile.filled <= 0L)
		{
			return new String[] { "", "" };
		}
		if (pProfile.isDateLike())
		{
			final List<Map.Entry<String, Long>> families = pProfile.datesByCount();
			if (!families.isEmpty())
			{
				return new String[] { NormalisationProfiler.describeFamily(pProfile, families.get(0).getKey()), "date" };
			}
		}
		if (pProfile.numberRows() * 100L >= pProfile.filled * NormalisationProfile.DOMINANT_PERCENT)
		{
			String best = null;
			long bestRows = -1L;
			for (final Map.Entry<String, Long> entry : pProfile.numberShapes.entrySet())
			{
				if (entry.getValue().longValue() > bestRows)
				{
					best = entry.getKey();
					bestRows = entry.getValue().longValue();
				}
			}
			if (best != null)
			{
				return new String[] { PreflightCheck.numberWords(best), "number" };
			}
		}
		return new String[] { "", "" };
	}
}
