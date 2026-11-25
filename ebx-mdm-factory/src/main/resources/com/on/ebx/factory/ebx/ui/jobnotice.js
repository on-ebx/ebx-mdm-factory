/* Audit F-34 / end-of-task notice (wave 10): tells the person that a background task THEY launched has ended (DONE, DONE_WITH_FAILURES or FAILED).
 *
 * No polling of its own: the wizard's jobs banner (wizard.js) already reads GET /projects/{code}/jobs - {active, recent, me} - every 3 s while something runs and
 * every 30 s otherwise, and hands each answer to mdmfJobNotice.check(data, cfg). The proposal review, which has no banner, polls the same route itself, but only
 * while a task launched from that very page is still active (mdmfJobNotice.watch).
 *
 * A finished task is announced once per person and browser: its login, project and id are remembered in localStorage, under the person's own key
 * (mdmf:<login>:jobNoticesShown, userstore.js - wave 13: the keys of any other login are deleted when it is opened). Every access is wrapped in try/catch - private mode, blocked
 * storage, a full quota, a missing key or a missing userstore.js all read as "nothing remembered", which at worst shows a task again, never throws). Only tasks that ended within the
 * last two hours are announced, so a person who comes back to an old project is not greeted with a pile of history (that is what "Recent tasks" is for).
 *
 * The notice is a toast: a role="status" live region (announced politely by a screen reader), one block per task with the task's name, how it ended, the first
 * part of its own sentence, a link to "Recent tasks" when the screen has that list, and a Close button. It never closes by itself and takes no focus.
 * window.mdmfJobNoticeLabels is declared by the Java side (JobNotice.script): done, failed, nothing, partial, open, close, more, kinds.
 */
(function () {
	"use strict";
	if (window.mdmfJobNotice) return;

	var STORE = "jobNoticesShown";
	var RECENT_MS = 2 * 60 * 60 * 1000;
	var KEEP_MS = 24 * 60 * 60 * 1000;
	var KEEP_MAX = 300;
	var SHOW_MAX = 3;
	var MESSAGE_MAX = 160;

	function labels() { return window.mdmfJobNoticeLabels || {}; }
	function fmt(pattern, a, b) { return String(pattern || "").replace("{0}", String(a)).replace("{1}", String(b === undefined ? "" : b)); }

	/** The person's own localStorage (userstore.js): null without the library, then nothing is remembered. */
	function area(me) { return window.mdmfStore && me ? window.mdmfStore.forUser(me).local : null; }

	function readShown(me) {
		try {
			var raw = area(me).get(STORE);
			var parsed = raw ? JSON.parse(raw) : {};
			return parsed && typeof parsed === "object" ? parsed : {};
		} catch (ignored) { return {}; }
	}
	function writeShown(me, map) {
		try {
			var keys = Object.keys(map);
			if (keys.length > KEEP_MAX) {
				keys.sort(function (a, b) { return map[a] - map[b]; });
				keys.slice(0, keys.length - KEEP_MAX).forEach(function (k) { delete map[k]; });
			}
			area(me).set(STORE, JSON.stringify(map));
		} catch (ignored) { /* nothing remembered: a task may be announced again, never an error */ }
	}
	function key(data, job) { return String((data && data.me) || "") + ":" + String((data && data.projectId) || "") + ":" + String(job.jobId); }

	function injectStyle() {
		if (document.getElementById("mdmfJobNoticeStyle")) return;
		var st = document.createElement("style");
		st.id = "mdmfJobNoticeStyle";
		st.textContent = ".mdmf-toasts{position:fixed;right:20px;bottom:20px;z-index:70;display:flex;flex-direction:column;gap:8px;max-width:min(420px,calc(100vw - 32px))}"
			+ ".mdmf-toasts:empty{display:none}"
			+ ".mdmf-toast{display:flex;align-items:flex-start;gap:10px;padding:10px 12px;border-radius:10px;border:1px solid #d0d5dd;border-left-width:5px;"
			+ "background:#fff;color:var(--ink,#172033);box-shadow:0 8px 24px rgba(16,24,40,.18);font-size:13px;line-height:1.45}"
			+ ".mdmf-toast-nothing,.mdmf-toast-partial{border-left-color:var(--warn,#b54708);background:var(--warn-soft,#fffaeb)}"
			+ ".mdmf-toast-done{border-left-color:var(--good,#087443)}.mdmf-toast-failed{border-left-color:var(--bad,#b42318);background:var(--bad-soft,#fef2f2)}"
			+ ".mdmf-toast-body{flex:1 1 auto;min-width:0}.mdmf-toast-title{font-weight:700;display:block}"
			+ ".mdmf-toast-text{display:block;color:var(--muted,#667085);overflow-wrap:anywhere}"
			+ ".mdmf-toast-link{display:inline-block;margin-top:4px;color:var(--ebx-main-ink,#684bc0);font-weight:600;text-decoration:underline;cursor:pointer;background:none;border:0;padding:0;font:inherit}"
			+ ".mdmf-toast-close{flex:0 0 auto;border:0;background:none;cursor:pointer;font-size:18px;line-height:1;color:var(--muted,#667085);padding:2px 6px;border-radius:4px}"
			+ ".mdmf-toast-close:focus-visible,.mdmf-toast-link:focus-visible{outline:2px solid var(--ebx-focus,#684bc0);outline-offset:2px}"
			+ "@media(max-width:640px){.mdmf-toasts{left:16px;right:16px;bottom:16px;max-width:none}}";
		document.head.appendChild(st);
	}

	/** The live region every toast goes into: created once, empty, role=status so the announcements are polite and reliable. */
	function region(host) {
		var existing = document.getElementById("mdmfJobToasts");
		if (existing) return existing;
		injectStyle();
		var box = document.createElement("div");
		box.id = "mdmfJobToasts";
		box.className = "mdmf-toasts";
		box.setAttribute("role", "status");
		box.setAttribute("aria-live", "polite");
		(host || document.body).appendChild(box);
		return box;
	}

	function shorten(text) {
		var t = String(text || "").replace(/\s+/g, " ").trim();
		return t.length > MESSAGE_MAX ? t.slice(0, MESSAGE_MAX - 1) + "…" : t;
	}

	/** A DONE task whose message starts with "Nothing to do" changed nothing: amber, never the green "finished" (UX lot U3, same rule as the Recent tasks list). */
	function nothingToDo(job) {
		return !!job && job.status === "DONE" && /^\s*Nothing to do/.test(String(job.message || ""));
	}

	/**
	 * UX lot D: a task that ended with failures on some of its items is stored with the status DONE_WITH_FAILURES (amber "finished with failures", never the
	 * green "finished"; mirrors JobOutcome.display). The sentence is no longer parsed: the status says it (the rows written before the lot are only read by the
	 * server-rendered Recent tasks list, which keeps the old rule for history).
	 */
	function partial(job) {
		return !!job && job.status === "DONE_WITH_FAILURES";
	}

	function toast(box, job, kinds, plainTitle) {
		var L = labels();
		var failed = job.status === "FAILED";
		var nothing = nothingToDo(job);
		var part = partial(job);
		var el = document.createElement("div");
		el.className = "mdmf-toast " + (failed ? "mdmf-toast-failed" : part ? "mdmf-toast-partial" : nothing ? "mdmf-toast-nothing" : "mdmf-toast-done");
		el.setAttribute("data-mdmf-job-notice", String(job.jobId));
		var body = document.createElement("div");
		body.className = "mdmf-toast-body";
		var title = document.createElement("strong");
		title.className = "mdmf-toast-title";
		title.textContent = plainTitle || fmt(failed ? L.failed : part ? (L.partial || L.done) : nothing ? (L.nothing || L.done) : L.done, (kinds && kinds[job.kind]) || (L.kinds && L.kinds[job.kind]) || job.kind);
		body.appendChild(title);
		if (job.message && !plainTitle) {
			var text = document.createElement("span");
			text.className = "mdmf-toast-text";
			text.textContent = shorten(job.message);
			body.appendChild(text);
		}
		var list = document.getElementById("mdmfRecentTasks");
		if (list) {
			var link = document.createElement("button");
			link.type = "button";
			link.className = "mdmf-toast-link";
			link.textContent = L.open || "Recent tasks";
			link.addEventListener("click", function () {
				list.open = true;
				try { list.scrollIntoView({ block: "start" }); } catch (ignored) { list.scrollIntoView(); }
			});
			body.appendChild(link);
		}
		el.appendChild(body);
		var close = document.createElement("button");
		close.type = "button";
		close.className = "mdmf-toast-close";
		close.setAttribute("aria-label", L.close || "Close");
		close.textContent = "×";
		close.addEventListener("click", function () { if (el.parentNode) el.parentNode.removeChild(el); });
		el.appendChild(close);
		box.appendChild(el);
	}

	/**
	 * Pure part, exposed for the tests: the finished tasks of {data.recent} this person launched, ended recently and not announced yet, oldest first.
	 * {shown} maps "login:projectId:jobId" to the time it was announced (two people sharing a browser each get their own).
	 */
	function pending(data, shown, now) {
		if (!data || !data.me || !data.recent) return [];
		return data.recent.filter(function (job) {
			return (job.status === "DONE" || job.status === "DONE_WITH_FAILURES" || job.status === "FAILED") && job.requestedBy === data.me && job.endedAt
				&& now - job.endedAt < RECENT_MS && !shown[key(data, job)];
		}).sort(function (a, b) { return a.endedAt - b.endedAt; });
	}

	function check(data, cfg) {
		var me = data && data.me;
		var shown = readShown(me);
		var now = Date.now();
		var list = pending(data, shown, now);
		if (!list.length) return 0;
		var box = region(cfg && cfg.host);
		list.forEach(function (job) { shown[key(data, job)] = now; });
		Object.keys(shown).forEach(function (k) { if (now - shown[k] > KEEP_MS) delete shown[k]; });
		writeShown(me, shown);
		var kinds = cfg && cfg.kinds;
		list.slice(0, SHOW_MAX).forEach(function (job) { toast(box, job, kinds); });
		if (list.length > SHOW_MAX) {
			toast(box, { jobId: "more", kind: "", status: "DONE" }, kinds, fmt(labels().more, list.length - SHOW_MAX));
		}
		return list.length;
	}

	/**
	 * For a screen with no banner (the proposal review): reads {url} now, and again every 4 s for as long as a task of this person is queued or running there
	 * (at most 30 minutes), announcing what finished. One read at load is enough to catch a task that ended while the person was elsewhere.
	 */
	function watch(url, cfg) {
		var started = Date.now();
		function tick() {
			fetch(url, { credentials: "same-origin", cache: "no-store", headers: { "Accept": "application/json" } })
				.then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
				.then(function (data) {
					check(data, cfg);
					var mine = ((data && data.active) || []).some(function (job) { return job.requestedBy === data.me; });
					if (mine && Date.now() - started < 30 * 60 * 1000) window.setTimeout(tick, 4000);
				})
				.catch(function () { /* the notice is a convenience: a failed read is not worth a message */ });
		}
		tick();
	}

	window.mdmfJobNotice = { check: check, watch: watch, pending: pending, nothingToDo: nothingToDo, partial: partial };
})();
