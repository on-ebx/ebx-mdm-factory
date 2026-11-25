package com.on.ebx.factory.intake;

/** JUnit-free: {@link IntakeReadiness} and {@link IntakeStatus}'s JSON. */
public final class IntakeReadinessTest
{
	public static void main(final String[] pArgs)
	{
		IntakeReadinessTest.gate();
		IntakeReadinessTest.statusJsonHasEveryKey();
		System.out.println("IntakeReadinessTest OK");
	}

	private static void check(final boolean pCondition, final String pMessage)
	{
		if (!pCondition)
		{
			throw new AssertionError(pMessage);
		}
	}

	private static void gate()
	{
		IntakeReadiness.Result r = IntakeReadiness.evaluate(true, 2, 0, true, true, 0);
		check(r.ready && r.firstBlocker() == null && r.lines.size() == 4, "everything fine");
		r = IntakeReadiness.evaluate(true, 2, 0, true, true, 3);
		check(r.ready, "rows held back never block");
		check(!r.lines.get(3).ok && !r.lines.get(3).blocking && r.lines.get(3).count.longValue() == 3L && r.lines.get(3).text.contains("3 row"), "but the line says so");
		r = IntakeReadiness.evaluate(false, 2, 0, true, true, 0);
		check(!r.ready && IntakeReadiness.PIPELINE_MODEL.equals(r.firstBlocker().key) && r.firstBlocker().text.contains("predates automatic intake"), "old pipeline publication");
		r = IntakeReadiness.evaluate(true, 2, 1, true, true, 0);
		check(!r.ready && IntakeReadiness.LANDING_LOADED.equals(r.firstBlocker().key) && r.firstBlocker().count.longValue() == 1L, "a source not loaded");
		r = IntakeReadiness.evaluate(true, 0, 0, true, true, 0);
		check(!r.ready && r.firstBlocker().text.contains("no source"), "no source at all is not ready");
		r = IntakeReadiness.evaluate(true, 1, 0, false, true, 0);
		check(!r.ready && IntakeReadiness.STAGING_AND_MASTER_LOADED.equals(r.firstBlocker().key) && r.firstBlocker().text.contains("staging"), "staging not loaded");
		r = IntakeReadiness.evaluate(true, 1, 0, true, false, 0);
		check(!r.ready && r.firstBlocker().text.contains("master"), "master not loaded");
	}

	private static void statusJsonHasEveryKey()
	{
		final IntakeStatus status = new IntakeStatus();
		status.project = "P";
		status.state = "ON";
		status.serverTime = java.time.Instant.parse("2026-10-05T12:00:00Z");
		final IntakeStatus.Source source = new IntakeStatus.Source();
		source.sourceSystem = "CRM";
		source.kind = IntakeStatus.KIND_PUSHED;
		source.state = "IDLE";
		status.sources.add(source);
		final IntakeStatus.Chain chain = new IntakeStatus.Chain();
		chain.id = "abc";
		chain.sources.add("CRM");
		chain.status = "RUNNING";
		status.currentChain = chain;
		status.chains.add(chain);
		final com.fasterxml.jackson.databind.node.ObjectNode json = status.toJson();
		for (final String key : new String[] { "project", "state", "pauseReason", "enabledBy", "enabledAt", "reviewRejects", "stopRejectPercent", "ready", "readiness", "pipeline", "sources",
			"currentChain", "queuedAfterChain", "chains", "rejectReview", "engineRunning", "mayOperate", "serverTime" })
		{
			check(json.has(key), "missing key " + key);
		}
		check(json.get("serverTime").asText().equals("2026-10-05T12:00:00Z"), "ISO instant with seconds");
		check(json.get("pauseReason").isNull() && json.get("stopRejectPercent").isNull(), "absent values are null, never missing");
		final com.fasterxml.jackson.databind.JsonNode s = json.get("sources").get(0);
		for (final String key : new String[] { "sourceSystem", "label", "kind", "settings", "rowsWaiting", "lastRowAt", "pendingSince", "dueAt", "latestAt", "state", "reason", "batchCompletePath" })
		{
			check(s.has(key), "missing source key " + key);
		}
		check(s.get("settings").has("quietSeconds") && s.get("settings").has("maxWaitMinutes") && s.get("settings").has("minRows") && s.get("settings").has("batchSignal"), "settings");
		check(json.get("currentChain").get("sources").get(0).asText().equals("CRM") && json.get("currentChain").has("workflowKey"), "chain");
		check(new IntakeStatus().toJson().get("currentChain").isNull(), "no chain is null");
	}
}
