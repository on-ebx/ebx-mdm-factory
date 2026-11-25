package com.on.ebx.factory.landing;

/**
 * Live-progress lot (2026-09-26): a long step says what it is doing now, in one plain-language line, so the
 * running screen never sits on a bare spinner ("Creating the landing model (24 fields)"). The landing run
 * forwards the line to its {@link LandingRunProgress}; every other caller passes {@link #NONE}.
 */
public interface ProgressNote
{
	ProgressNote NONE = pText -> {
		// nobody is watching
	};

	void note(String pText);
}
