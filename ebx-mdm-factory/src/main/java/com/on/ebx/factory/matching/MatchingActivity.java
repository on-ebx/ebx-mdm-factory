package com.on.ebx.factory.matching;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Matching-live lot (2026-09-27, asked by the product owner on project ret): the last matching action of a project -
 * "Run matching" ({@code MATCHING_RUN}) or "Configure" ({@code MATCHING_CONFIGURE}) - as step 6's live panel
 * shows it, under the buttons, from the click to well after the end: queued, then one line per entity (running,
 * done, failed, with the entity step's own message). Read from what the run already writes - the parent {@code
 * PipelineStep} (scope empty) and one step per entity ({@code MATCHING_RUN}/{@code MATCHING_CONFIG}, scope =
 * the entity) - by {@code ebx.MatchingActivityReader}; nothing in the matching code writes for this panel.
 * <p>
 * Before this lot a run lasting a few seconds left only the green "started" line and the jobs banner at the
 * top of the page, while the page re-opened on the button: nothing to see where the person looks.
 * <p>
 * Pure: no EBX class, JSON round trip unit-tested.
 */
public final class MatchingActivity
{
	public static final String RUN = "RUN";
	public static final String CONFIGURE = "CONFIGURE";

	public static final String NONE = "NONE";
	public static final String QUEUED = "QUEUED";
	public static final String RUNNING = "RUNNING";
	public static final String DONE = "DONE";
	public static final String FAILED = "FAILED";
	public static final String STOPPED = "STOPPED";

	private static final ObjectMapper MAPPER = new ObjectMapper();

	/** One entity of the action: its own step. */
	public static final class Entity
	{
		public String name = "";
		public String status = MatchingActivity.RUNNING;
		public long startedAt;
		public long endedAt;
		public String message = "";
	}

	public String kind = MatchingActivity.RUN;
	public String status = MatchingActivity.NONE;
	public long startedAt;
	public long endedAt;
	public String message = "";
	/** Lot M6 (2026-10-18): the configure's notes (coded strings, English text + bundle marker) read from the job's detail - shown under the message, in the person's language. */
	public final List<String> notes = new ArrayList<String>();
	public final List<Entity> entities = new ArrayList<Entity>();

	/** Queued or running: the panel keeps polling. */
	public boolean isLive()
	{
		return MatchingActivity.QUEUED.equals(this.status) || MatchingActivity.RUNNING.equals(this.status);
	}

	public Entity entity(final String pName)
	{
		for (final Entity entity : this.entities)
		{
			if (entity.name.equals(pName))
			{
				return entity;
			}
		}
		return null;
	}

	/**
	 * A PipelineStep status as the panel says it: {@code DONE}/{@code FAILED}/{@code RUNNING} as is,
	 * {@code INTERRUPTED} (server restarted) as failed, a stopped run ({@code PipelineSteps.endStopped}) as
	 * stopped, anything unknown as running.
	 */
	public static String statusOf(final String pStepStatus)
	{
		if (pStepStatus == null)
		{
			return MatchingActivity.RUNNING;
		}
		switch (pStepStatus)
		{
			case "DONE":
				return MatchingActivity.DONE;
			case "FAILED":
			case "INTERRUPTED":
				return MatchingActivity.FAILED;
			case "STOPPED":
			case "CANCELLED":
				return MatchingActivity.STOPPED;
			default:
				return MatchingActivity.RUNNING;
		}
	}

	public ObjectNode toJson()
	{
		return this.toJson(null);
	}

	/** As {@link #toJson()}, the texts the run stored with a code worded in {@code pLocale}; without a locale, the engine's English with no marker. */
	public ObjectNode toJson(final java.util.Locale pLocale)
	{
		final ObjectNode root = MatchingActivity.MAPPER.createObjectNode();
		root.put("kind", this.kind);
		root.put("status", this.status);
		root.put("startedAt", this.startedAt);
		root.put("endedAt", this.endedAt);
		root.put("message", MatchingActivity.shown(this.message, pLocale));
		final ArrayNode noteList = root.putArray("notes");
		for (final String note : this.notes)
		{
			noteList.add(note);
		}
		final ArrayNode list = root.putArray("entities");
		for (final Entity entity : this.entities)
		{
			list.addObject().put("name", entity.name).put("status", entity.status).put("startedAt", entity.startedAt).put("endedAt", entity.endedAt)
				.put("message", MatchingActivity.shown(entity.message, pLocale));
		}
		return root;
	}

	private static String shown(final String pStored, final java.util.Locale pLocale)
	{
		return pLocale == null ? com.on.ebx.factory.wizard.EngineMessageCodec.decode(pStored).raw : com.on.ebx.factory.wizard.RunPanelText.display(pStored, pLocale);
	}

	/** Never throws: anything unreadable is "no action yet". */
	public static MatchingActivity parse(final String pJson)
	{
		final MatchingActivity out = new MatchingActivity();
		if (pJson == null || pJson.trim().isEmpty())
		{
			return out;
		}
		try
		{
			final JsonNode root = MatchingActivity.MAPPER.readTree(pJson);
			out.kind = root.path("kind").asText(MatchingActivity.RUN);
			out.status = root.path("status").asText(MatchingActivity.NONE);
			out.startedAt = root.path("startedAt").asLong(0L);
			out.endedAt = root.path("endedAt").asLong(0L);
			out.message = root.path("message").asText("");
			for (final JsonNode note : root.path("notes"))
			{
				if (note.isTextual() && !note.asText().isEmpty())
				{
					out.notes.add(note.asText());
				}
			}
			for (final JsonNode node : root.path("entities"))
			{
				final Entity entity = new Entity();
				entity.name = node.path("name").asText("");
				entity.status = node.path("status").asText(MatchingActivity.RUNNING);
				entity.startedAt = node.path("startedAt").asLong(0L);
				entity.endedAt = node.path("endedAt").asLong(0L);
				entity.message = node.path("message").asText("");
				out.entities.add(entity);
			}
		}
		catch (final Exception ex)
		{
			return new MatchingActivity();
		}
		return out;
	}
}
