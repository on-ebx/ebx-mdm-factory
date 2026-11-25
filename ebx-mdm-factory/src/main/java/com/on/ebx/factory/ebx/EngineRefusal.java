package com.on.ebx.factory.ebx;

import java.util.Locale;

import com.on.ebx.factory.core.FactoryRight;
import com.on.ebx.factory.landing.ErrorText;
import com.on.ebx.factory.wizard.EngineMessageCodec;
import com.on.ebx.factory.wizard.WizardMessages;
import com.orchestranetworks.service.OperationException;

/**
 * Audit F-39: a refusal of the engine that a person meets often from a button, with a message code. {@link #getMessage()}
 * stays the English sentence the engine always gave (logs, REST answers, the {@code FactoryJob} rows and the tests read it
 * unchanged); {@link #getMessageForLocale} and {@link #sentence} give the short sentence of the bundle key {@link #code} in the
 * person's language ({@code com.on.ebx.factory.ebx.ui.Messages_xx.properties}, keys {@code engine.*} and {@code access.*}).
 * <p>
 * Only the refusals the wizard shows most are coded - see the factory methods below; every other {@code OperationException}
 * keeps its English text, which the wizard shows under a localized lead sentence ({@code ebx.ui.EngineNotice}).
 */
public final class EngineRefusal extends OperationException
{
	private static final long serialVersionUID = 1L;

	/** An argument rendered as the localized label of a job kind ({@code wizard.jobs.kind.<KIND>}), the kind itself when it has none. */
	public static final class JobKind
	{
		final String kind;

		JobKind(final String pKind)
		{
			this.kind = pKind == null ? "" : pKind;
		}

		String label(final Locale pLocale)
		{
			final String key = "wizard.jobs.kind." + this.kind;
			final String label = WizardMessages.get(pLocale, key);
			return key.equals(label) ? this.kind : label;
		}

		@Override
		public String toString()
		{
			return this.kind;
		}
	}

	private final String code;
	private final Object[] args;
	private final boolean rawInDetail;

	private EngineRefusal(final String pCode, final String pEnglish, final Throwable pCause, final Object[] pArgs, final boolean pRawInDetail)
	{
		super(pEnglish, pCause);
		this.code = pCode;
		this.args = pArgs == null ? new Object[0] : pArgs.clone();
		this.rawInDetail = pRawInDetail;
	}

	/** A coded refusal: {@code pCode} is the bundle key, {@code pEnglish} the engine's own sentence (what {@link #getMessage()} answers). */
	public static EngineRefusal of(final String pCode, final String pEnglish, final Object... pArgs)
	{
		return new EngineRefusal(pCode, pEnglish, null, pArgs, false);
	}

	/** The same, with the low-level cause (shown in the "details" fold, never in the sentence). */
	public static EngineRefusal withCause(final String pCode, final String pEnglish, final Throwable pCause, final Object... pArgs)
	{
		return new EngineRefusal(pCode, pEnglish, pCause, pArgs, false);
	}

	/**
	 * Wave 10 (audit F-39): an engine message with a code. {@code pCode} is {@code <kind>.<name>} (kind: {@code missing},
	 * {@code conflict}, {@code invalid}, {@code external}, {@code unexpected}), the bundle key {@code engine.<pCode>}; {@code pEnglish}
	 * stays the engine's own text word for word - {@link #getMessage()} (logs, REST, job rows) is unchanged and the screens show it
	 * under "Details" - and {@code pArgs} fill the placeholders of the bundle sentence.
	 */
	public static EngineRefusal coded(final String pCode, final String pEnglish, final Object... pArgs)
	{
		return new EngineRefusal("engine." + pCode, pEnglish, null, pArgs, true);
	}

	/** The same, with the cause that made the engine give up (kept for the stack trace and the "Details" fold). */
	public static EngineRefusal codedWithCause(final String pCode, final String pEnglish, final Throwable pCause, final Object... pArgs)
	{
		return new EngineRefusal("engine." + pCode, pEnglish, pCause, pArgs, true);
	}

	public String code()
	{
		return this.code;
	}

	/** True for a {@link #coded} message: the engine's English text carries what the sentence leaves out, so the screens fold it under "Details". */
	public boolean rawInDetail()
	{
		return this.rawInDetail;
	}

	/**
	 * What to store for this failure as text (a {@code FactoryJob} message): {@code pRaw} - the engine's English text, normally
	 * {@link #getMessage()} - followed by the marker that lets a later screen word it in its own language ({@link EngineMessageCodec}).
	 * {@code pMaxLength} bounds the whole text, the marker is never the part that is cut.
	 */
	public String stored(final String pRaw, final int pMaxLength)
	{
		final Object[] encodable = new Object[this.args.length];
		for (int i = 0; i < this.args.length; i++)
		{
			final Object arg = this.args[i];
			encodable[i] = arg instanceof JobKind ? EngineMessageCodec.jobKind(((JobKind) arg).kind)
				: arg instanceof JobKinds ? EngineMessageCodec.jobKinds(((JobKinds) arg).kindNames()) : arg;
		}
		return EngineMessageCodec.encode(pRaw, this.code, encodable, pMaxLength);
	}

	/** The text to store for any failure: its sentence for a log or a REST reader ({@link ErrorText}), plus the marker when it is a coded message. */
	public static String storedText(final Throwable pFailure, final int pMaxLength)
	{
		final String raw = ErrorText.of(pFailure);
		final EngineRefusal coded = EngineRefusal.find(pFailure);
		return coded == null ? raw : coded.stored(raw, pMaxLength);
	}

	/** The sentence in {@code pLocale}; the English sentence when the bundle has no such key. Pure (bundle only). */
	public String sentence(final Locale pLocale)
	{
		final Object[] rendered = new Object[this.args.length];
		for (int i = 0; i < this.args.length; i++)
		{
			final Object arg = this.args[i];
			rendered[i] = arg instanceof JobKind ? ((JobKind) arg).label(pLocale) : arg instanceof JobKinds ? ((JobKinds) arg).label(pLocale) : arg;
		}
		final String sentence = WizardMessages.get(pLocale, this.code, rendered);
		return this.code.equals(sentence) ? this.getMessage() : sentence;
	}

	@Override
	public String getMessageForLocale(final Locale pLocale)
	{
		return this.sentence(pLocale);
	}

	// ============================================================================================= the coded refusals

	/** A project that no longer exists (deleted by someone else while the page was open). */
	public static EngineRefusal projectNotFound(final String pProjectCode)
	{
		return EngineRefusal.of("engine.projectNotFound", "Project " + pProjectCode + " not found", pProjectCode);
	}

	/** The caller does not hold the right a job or an action asks ({@code FactoryAccess#refusal} is the English sentence). */
	public static EngineRefusal accessDenied(final String pWhat, final FactoryRight pNeeded)
	{
		final String english = FactoryAccess.refusal(pWhat, pNeeded);
		return pNeeded == FactoryRight.ADMINISTRATOR || pNeeded == null ? EngineRefusal.of("access.denied.admin", english)
			: EngineRefusal.of("access.denied", english, pNeeded.name(), FactoryAccess.roleFor(pNeeded));
	}

	/** Another run holds the resource ({@code FactoryJobs#busyMessage} is the English sentence). */
	public static EngineRefusal busy(final FactoryJobs.Handle pLive)
	{
		final long minutes = Math.max(0L, (System.currentTimeMillis() - pLive.createdAt) / 60000L);
		return EngineRefusal.of("engine.busy", FactoryJobs.busyMessage(pLive), new JobKind(pLive.kind), pLive.launcher == null ? "?" : pLive.launcher,
			Long.valueOf(minutes));
	}

	/** A claim lost the race with a run that has just started. */
	public static EngineRefusal justStarted()
	{
		return EngineRefusal.of("engine.justStarted", "A run of this project has just started. Retry in a moment.");
	}

	/** Destructive work refused while jobs of the project wait or run ({@code FactoryJobQueue#exclusively}); {@code pKinds} are their kinds. */
	public static EngineRefusal jobsPending(final String pEnglish, final java.util.List<String> pKinds)
	{
		return EngineRefusal.of("engine.jobsPending", pEnglish, new JobKinds(pKinds));
	}

	/** The job row could not be written; the cause goes to the "details" fold. */
	public static EngineRefusal queueFailed(final String pKind, final String pEnglish)
	{
		return EngineRefusal.of("engine.queueFailed", pEnglish, new JobKind(pKind));
	}

	/** No usable language model connection in the factory settings (no settings row, no connection chosen). */
	public static EngineRefusal llmNotConfigured(final String pEnglish)
	{
		return EngineRefusal.of("engine.llm.notConfigured", pEnglish);
	}

	/** The language model connection named in the settings cannot be used; {@code pWhy} is {@code missing}, {@code notLlm}, {@code inactive} or {@code noConnectors}. */
	public static EngineRefusal llmConnectionUnusable(final String pWhy, final String pConnectorId, final String pEnglish)
	{
		return EngineRefusal.of("engine.llm." + pWhy, pEnglish, pConnectorId);
	}

	/** Every row a load read was refused: nothing was written. {@code pEnglish} carries the first reasons (the "details" fold). */
	public static EngineRefusal allRowsRejected(final int pRead, final String pEnglish)
	{
		return EngineRefusal.of("engine.allRowsRejected", pEnglish, Integer.valueOf(pRead));
	}

	/** A plain refusal with no argument but the bundle key ({@code engine.<pCode>}). */
	public static EngineRefusal simple(final String pCode, final String pEnglish, final Object... pArgs)
	{
		return EngineRefusal.of("engine." + pCode, pEnglish, pArgs);
	}

	/**
	 * The coded refusal carried by {@code pFailure} or one of its causes, or null - an exception thrown inside a {@code Procedure}
	 * comes back wrapped ({@code ProcedureResult#getException}), and an engine that adds context wraps it again.
	 */
	public static EngineRefusal find(final Throwable pFailure)
	{
		Throwable current = pFailure;
		for (int depth = 0; current != null && depth < 10; depth++)
		{
			if (current instanceof EngineRefusal)
			{
				return (EngineRefusal) current;
			}
			current = current.getCause() == current ? null : current.getCause();
		}
		return null;
	}

	/** Several job kinds, rendered as a comma-separated list of their localized labels. */
	public static final class JobKinds
	{
		final java.util.List<JobKind> kinds = new java.util.ArrayList<JobKind>();

		JobKinds(final java.util.List<String> pKinds)
		{
			for (final String kind : pKinds)
			{
				this.kinds.add(new JobKind(kind));
			}
		}

		java.util.List<String> kindNames()
		{
			final java.util.List<String> names = new java.util.ArrayList<String>();
			for (final JobKind kind : this.kinds)
			{
				names.add(kind.kind);
			}
			return names;
		}

		String label(final Locale pLocale)
		{
			final java.util.LinkedHashSet<String> labels = new java.util.LinkedHashSet<String>();
			for (final JobKind kind : this.kinds)
			{
				labels.add(kind.label(pLocale));
			}
			return String.join(", ", labels);
		}

		@Override
		public String toString()
		{
			return this.kinds.toString();
		}
	}
}
