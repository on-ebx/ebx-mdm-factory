(function () {
	"use strict";
	// wave 11 (audit F-42): this file is served as a static, cached file (www/common/jscripts/factory/wizard.js, address stamped with the build). The page's
	// own loader checks this flag once the file has run and asks again, then says so, when it is missing.
	window.mdmfWizardReady = true;
	var root = document.querySelector(".mdmf-wizard");
	// F2-106: the group queue is paged on the server - a pager button is a plain action (MATCH_PAGE) whose data-mdmf-match-tab-value ("PENDING@n") is posted in mdmfMatchTab (see below)
	if (!root) return;

	// wave 13 (security): the tab's sessionStorage belongs to the tab, not to the EBX user (it survives a logout), so every key is mdmf:<login>:<name> and the keys of
	// any other login are deleted when the page opens (userstore.js, written before this file; the login is data-mdmf-user of the root). Without it nothing is stored.
	var ST = (window.mdmfStore && root.getAttribute("data-mdmf-user")) ? window.mdmfStore.forUser(root.getAttribute("data-mdmf-user")).session
		: { get: function () { return null; }, set: function () { return false; }, remove: function () { return false; }, take: function () { return null; } };

	function byId(id) { return document.getElementById(id); }
	function set(id, v) { var e = byId(id); if (e) e.value = v; }
	function val(id) { var e = byId(id); return e ? e.value : ""; }

	// ---- progress toast: same mdmfProgress/mdmfProgressLabel markup and .mdmf-progress/.mdmf-progress.on
	// classes (review.css, already linked on this page) as ProposalReviewUserService.pageJs's progress(msg) -
	// never a second implementation. mdmfWizardProgressLabels is a global declared by ProjectWizardUserService.
	// script(Locale) right before this file's own body (see that method's Javadoc); the typeof guard keeps this
	// file safe to load stand-alone (e.g. from a test) without that preamble.
	// long-passes-visible lot (2026-09-23): the whole request is SYNCHRONOUS (the browser blocks on the real
	// postback until the server answers), so this timeout is only ever a SAFETY NET for a request that never
	// comes back at all (a network error, a rejected postback) - it must never fire while a genuine, long
	// language-model pass (CAPABILITY_SUGGEST_LLM measured 4+ minutes live) is still in flight, or the buttons
	// re-enable and the toast disappears long before the page actually reloads with the real result, which is
	// exactly what read as "it proposed nothing" to the product owner. 15 minutes, not 45 seconds.
	var PROGRESS_TIMEOUT_MS = 900000;
	// audit F-49 (wave 10): an action that shows no toast (every one that only queues a background job, or writes a little) used to leave the buttons live
	// until the page came back, so a second click posted the same action twice. Every action that goes to the server now disables the buttons the moment it is
	// clicked. The page replaces this whole script when the answer arrives, which is what re-enables them; the timer below is only for a postback that never
	// comes back (EBX refused it on a field constraint, the network dropped), and is short for these quick actions - the toast ones keep their own long one.
	var LOCK_TIMEOUT_MS = 120000;
	var locked = false;
	function lockButtons(ms) {
		locked = true;
		root.querySelectorAll(".mdmf-btn").forEach(function (btn) { btn.disabled = true; });
		window.setTimeout(unlockButtons, ms);
	}
	function unlockButtons() {
		locked = false;
		root.querySelectorAll(".mdmf-btn").forEach(function (btn) { btn.disabled = false; });
	}
	// a page restored from the browser's back/forward cache comes back with its buttons as they were left
	window.addEventListener("pageshow", function (e) { if (e.persisted) unlockButtons(); });
	var progressLabels = (typeof mdmfWizardProgressLabels !== "undefined") ? mdmfWizardProgressLabels : {};
	function progress(msg) {
		var box = byId("mdmfProgress");
		var label = byId("mdmfProgressLabel");
		if (label) label.textContent = msg;
		if (box) { box.className = "mdmf-progress on"; box.style.display = "flex"; }
		root.querySelectorAll(".mdmf-btn").forEach(function (btn) { btn.disabled = true; });
		locked = true;
		window.setTimeout(function () {
			if (box) { box.className = "mdmf-progress"; box.style.display = "none"; }
			unlockButtons();
		}, PROGRESS_TIMEOUT_MS);
	}

	// ---- scroll-anchor lot (2026-09-23): the product owner, live on the deployed wizard - "Quand j'accepte un
	// element, l'ecran revient en haut. Je croyais que cela avait deja ete fixe." Cause: the wizard is a
	// UserServicePane written into an EBX workspace pane - the element that actually scrolls is an EBX
	// container div AROUND .mdmf-wizard, never window itself, so window.scrollY/scrollTo (the earlier
	// scroll-restore lot) were always a no-op. Finds the nearest ancestor of .mdmf-wizard that actually
	// scrolls (scrollHeight > clientHeight and a computed overflow-y of auto/scroll), walking up to
	// document.scrollingElement as the last resort - never assumed to be window.
	function scrollableAncestor() {
		var el = root.parentElement;
		while (el && el !== document.body && el !== document.documentElement) {
			var style = window.getComputedStyle(el);
			if (el.scrollHeight > el.clientHeight && (style.overflowY === "auto" || style.overflowY === "scroll")) return el;
			el = el.parentElement;
		}
		return document.scrollingElement || document.documentElement;
	}

	// ---- step switching: client side among the panels of the page. Wave 11 (audit F-42): the server writes only the step shown in full and a
	// placeholder (data-mdmf-deferred-step) for each other step - opening one goes through loadDeferredStep below, a STEP_VIEW round trip. A page
	// written with every step (-Debx.factory.wizard.allSteps=true) still switches without any round trip ----
	// lot C1-B: the steps of the project's style, in order, are the rail's own entries (data-step is a step id, not a position: the
	// CENTRALISATION path has ids 9-12) - every position, neighbour and "last step" below is read from this list
	var ORDER = [];
	root.querySelectorAll(".mdmf-wiz-steps li").forEach(function (li) { ORDER.push(parseInt(li.getAttribute("data-step"), 10)); });
	var current = parseInt(val("mdmfStep"), 10) || 1;
	function posOf(step) { return ORDER.indexOf(step) + 1; }
	function neighbour(step, delta) { var i = ORDER.indexOf(step); return i < 0 || i + delta < 0 || i + delta >= ORDER.length ? 0 : ORDER[i + delta]; }
	function isDistribution(step) { var li = stepLi(step); var b = li ? li.querySelector("button") : null; return !!(b && b.getAttribute("data-mdmf-deferred-label") === "distribution"); }

	function panel(step) { return root.querySelector('.mdmf-wiz-panel[data-step="' + step + '"]'); }
	function stepLi(step) { return root.querySelector('.mdmf-wiz-steps li[data-step="' + step + '"]'); }

	function showStep(step) {
		if (posOf(step) < 1) return;
		var target = panel(step);
		if (!target) return;
		var button = stepLi(step) ? stepLi(step).querySelector("button") : null;
		if (button && button.disabled) return; // blocked step: refuse the switch, same rule the server enforces
		root.querySelectorAll(".mdmf-wiz-panel").forEach(function (p) { p.hidden = true; });
		target.hidden = false;
		// UX lot U4 (2026-10-13): the one-line note of a refused rail click goes away once a step opens
		var railNoteLine = byId("mdmfRailNote");
		if (railNoteLine) railNoteLine.hidden = true;
		// UX lot U3 (2026-10-13): the green/yellow line of an earlier action belongs to the render that produced it - a client-side step change
		// (no round trip) must not carry it over to another step ("Planning the landing in the background" was still on top of step 6 an hour later)
		if (step !== current) {
			var oldNotice = byId("mdmfNotice");
			if (oldNotice && oldNotice.parentNode) oldNotice.parentNode.removeChild(oldNotice);
		}
		// completed-rail lot (2026-09-23): only "current" moves; "done" is the step's own completion, rendered
		// by WizardPage.renderSteps from WizardRules.StepState.completed and never recomputed here
		root.querySelectorAll(".mdmf-wiz-steps li").forEach(function (li) {
			var s = parseInt(li.getAttribute("data-step"), 10);
			li.classList.remove("current");
			if (s === step) li.classList.add("current");
		});
		current = step;
		set("mdmfStep", String(step));
		var back = byId("mdmfWizBack");
		var next = byId("mdmfWizNext");
		if (back) back.disabled = posOf(step) <= 1;
		// step-gating lot (2026-09-24): Next must stay disabled with the blocked step's own reason, not just
		// re-enable past the last panel - read from the rail's own <li> button (server-rendered from
		// WizardRules.stepStates, the SAME source showStep's own guard above already trusts), never
		// recomputed here.
		if (next) {
			var nextLi = stepLi(neighbour(step, 1));
			var nextButton = nextLi ? nextLi.querySelector("button") : null;
			var nextBlocked = !!(nextButton && nextButton.disabled);
			next.disabled = posOf(step) >= ORDER.length || nextBlocked;
			next.title = nextBlocked ? nextButton.title : "";
		}
		// wizard-progress lot (2026-09-22): named navProgress, not progress - that name is now the toast
		// function above, and shadowing it here (even harmlessly, function-scoped) reads as a bug
		var navProgress = root.querySelector(".mdmf-wiz-progress");
		if (navProgress) navProgress.textContent = navProgress.textContent.replace(/\d+/, String(posOf(step)));
		// fix/wizard-live-wave1 (2026-09-24, live bug item 3): the topbar's own "Step n of 7 - <name>" line
		// (WizardPage.renderTopbar, .mdmf-wiz-stepline) is server-rendered once and never touched by this
		// purely-client-side step switch - only the rail's own current/done classes moved. WizardPage now marks
		// the number and the step name as their own spans inside the (still fully localized, whole-sentence)
		// stepline text, so this only ever replaces two small pieces of text - never re-parses or rebuilds the
		// localized sentence itself, which would break for a locale whose word order differs from English.
		var steplineN = root.querySelector(".mdmf-wiz-stepline-n");
		if (steplineN) steplineN.textContent = String(posOf(step));
		// audit F-40: the Help link opens the user guide at the section of the step shown
		var help = root.querySelector(".mdmf-wiz-help[data-mdmf-help-base]");
		if (help) help.setAttribute("href", help.getAttribute("data-mdmf-help-base") + "#step-" + step);
		var steplineName = root.querySelector(".mdmf-wiz-stepline-name");
		if (steplineName && button) {
			var nameSpan = button.querySelector("span");
			steplineName.textContent = nameSpan ? nameSpan.textContent : "";
		}
	}

	// perf/wizard-distribution-lazy (2026-09-27): a panel the server deliberately left unbuilt (WizardPage.Model#
	// distributionDeferred - step 7 only today) carries [data-mdmf-deferred-step]; once shown client side, ask
	// the server for it with the same harmless STEP_VIEW round trip the rail's own step 7 button posts. Only
	// called from navigation that does NOT already submit (Back/Next, plain goto-step links) - never at load,
	// where the server always builds the step it renders, so this can never loop.
	function loadDeferredStep(step) {
		var target = panel(step);
		if (!target || target.hidden || !target.querySelector("[data-mdmf-deferred-step]")) return;
		var submit = byId("mdmfWizSubmit");
		if (!submit) return;
		// landing-walkthrough lot (2026-10-16): the placeholder only says it is loading once it really asks for the step
		var deferredNotice = target.querySelector("[data-mdmf-deferred-step]");
		if (deferredNotice && deferredNotice.getAttribute("data-mdmf-loading-text")) deferredNotice.textContent = deferredNotice.getAttribute("data-mdmf-loading-text");
		syncAlways();
		set("mdmfAction", "STEP_VIEW");
		set("mdmfStep", String(step));
		// wave 2 (2026-09-30): the placeholder now exists on steps 6 (pair cards) and 8 (summary) too - only step 7 says "distribution"
		progress(isDistribution(step) ? progressLabels.distributionLoad : progressLabels.stepLoad);
		submit.click();
	}

	// wave 11: what a person typed into a field of the step shown and did not save is lost when the page is replaced by another step (every step used to
	// stay in the page, so a field edited on step 1 survived a look at step 2). Say so before leaving, once per leave, only when the target step has to be
	// loaded and a real field of the step shown was changed; the search boxes (combobox, filters) are not edits.
	var dirty = false;
	var wizardLabels = (typeof mdmfWizardLabels !== "undefined") ? mdmfWizardLabels : {};
	function onFieldEdited(e) {
		var t = e.target;
		if (!t || !t.tagName || !/^(INPUT|TEXTAREA|SELECT)$/.test(t.tagName)) return;
		if (t.type === "hidden" || t.type === "search" || t.hasAttribute("data-mdmf-no-dirty") || t.closest(".mdmf-combo, [data-mdmf-no-dirty]")) return;
		// UX lot C4 (2026-10-14): a table tick of step 2 is saved by TABLES_KEEP on the way out (below), it is never "a change that would be lost"
		if (t.classList && t.classList.contains("mdmf-tick")) return;
		var p = t.closest(".mdmf-wiz-panel");
		if (p && !p.hidden) dirty = true;
	}
	root.addEventListener("input", onFieldEdited);
	root.addEventListener("change", onFieldEdited);
	function stepNeedsLoad(step) { var t = panel(step); return !!(t && t.querySelector("[data-mdmf-deferred-step]")); }
	function confirmLeave(step) {
		if (!dirty || step === current || !stepNeedsLoad(step) || !wizardLabels.leaveDirty) return true;
		return window.confirm(wizardLabels.leaveDirty);
	}

	root.querySelectorAll("[data-mdmf-goto-step]").forEach(function (b) {
		b.addEventListener("click", function (e) {
			e.preventDefault();
			var target = parseInt(b.getAttribute("data-mdmf-goto-step"), 10);
			// UX lot C4 (2026-10-14): leaving step 2 (the rail, "Continue to the landing") with ticks nobody kept yet is ONE submit, TABLES_KEEP landing on the
			// target step - no confirmation (the ticks are saved, not lost), no second STEP_VIEW submit, and the button's own action listener does not run
			// (the same path as Next). With no pending tick the step is shown client side as before.
			if (current === 2 && target !== current) {
				var pendingTicks = collectTicks("all");
				var keepSubmit = byId("mdmfWizSubmit");
				if (pendingTicks.length && keepSubmit) {
					e.stopImmediatePropagation();
					syncAlways();
					set("mdmfAction", "TABLES_KEEP");
					set("mdmfTableTicks", pendingTicks.join(","));
					set("mdmfStep", String(target));
					keepSubmit.click();
					return;
				}
			}
			// the rail's own buttons also carry a real action (STEP_VIEW) whose listener comes next: a refusal here must stop it too
			if (!confirmLeave(target)) { e.stopImmediatePropagation(); return; }
			showStep(target);
			// the rail's step 7/8 buttons also carry data-mdmf-action=STEP_VIEW: their own submit handler runs next
			var action = b.getAttribute("data-mdmf-action");
			if (action === null || action === "GOTO_STEP") loadDeferredStep(current);
		});
	});
	var backBtn = byId("mdmfWizBack");
	if (backBtn) backBtn.addEventListener("click", function (e) { e.preventDefault(); var prev = neighbour(current, -1); if (!prev || !confirmLeave(prev)) return; showStep(prev); loadDeferredStep(current); });
	var nextBtn = byId("mdmfWizNext");
	if (nextBtn) nextBtn.addEventListener("click", function (e) {
		e.preventDefault();
		var nxt = neighbour(current, 1);
		if (!nxt || !confirmLeave(nxt)) return;
		// UX lot U2 (2026-10-13): leaving step 2 with ticks nobody kept yet: save them (one TABLES_KEEP) on the way, then land on the next step
		if (current === 2) {
			var pendingTicks = collectTicks("all");
			var keepSubmit = byId("mdmfWizSubmit");
			if (pendingTicks.length && keepSubmit) {
				syncAlways();
				set("mdmfAction", "TABLES_KEEP");
				set("mdmfTableTicks", pendingTicks.join(","));
				set("mdmfStep", String(nxt));
				keepSubmit.click();
				return;
			}
		}
		showStep(nxt);
		loadDeferredStep(current);
	});

	// ---- pure client-side show/hide (no round trip): the landing sample preview panel
	// (WizardPage.renderPreviewRow, toggled via the hidden PROPERTY - its own CSS never sets display, so the
	// browser's default [hidden] styling is never overridden) and, since the inline-reviews lot (2026-09-23),
	// the two MATCH mini-panels (WizardPage.renderMatchInlinePanels, ported from ReviewPage's own review.css
	// .mdmf-match-inline{display:flex...} - an element whose CLASS sets display must be toggled via the
	// inline style.display instead, the exact technique ReviewPage's own pageJs already uses for this same
	// widget, or the class's display would keep winning over a mere hidden ATTRIBUTE). A panel rendered with
	// its own inline style="display:none" (the MATCH panels) is toggled that way; one with no inline style at
	// all (the preview panel) keeps using the hidden property, unchanged. Same "small, focused handler"
	// pattern as the [data-mdmf-goto-step] block above, deliberately kept out of the [data-mdmf-action] loop
	// below (a toggle button carries no data-mdmf-action at all, so it never triggers a submit).
	root.querySelectorAll("[data-mdmf-toggle]").forEach(function (b) {
		b.addEventListener("click", function (e) {
			e.preventDefault();
			var target = byId(b.getAttribute("data-mdmf-toggle"));
			if (!target) return;
			if (target.getAttribute("style")) {
				target.style.display = (target.style.display === "none" || !target.style.display) ? "flex" : "none";
			} else {
				target.hidden = !target.hidden;
			}
		});
	});

	// ---- wizard-llm-value-sharing lot (2026-09-28): step 1's "what may reach the language model" cards.
	// Pure client-side chrome, no round trip: the checked card takes the .selected look, the sample-size field
	// shows only for SAMPLE_VALUES (toggled via the hidden PROPERTY - .mdmf-field sets no display of its own),
	// and the consequence line swaps to the checked level's own text, rendered server-side into
	// data-mdmf-llm-<level> attributes (WizardPage.renderLlmSharingCards) with {0} standing for the live
	// sample size. A card above the instance cap is rendered disabled, so it can never be checked here.
	(function () {
		var radios = root.querySelectorAll("input[name=mdmfLlmSharingRadio]");
		if (!radios.length) return;
		var sizeField = byId("mdmfLlmSampleSizeField");
		var sizeInput = byId("mdmfLlmSampleSize");
		var line = byId("mdmfLlmConsequence");
		function refresh() {
			var level = "SHAPES_ONLY";
			radios.forEach(function (r) {
				var card = r.closest(".mdmf-choice-card");
				if (card) card.classList.toggle("selected", r.checked);
				if (r.checked) level = r.value;
			});
			if (sizeField) sizeField.hidden = level !== "SAMPLE_VALUES";
			if (line) {
				var text = line.getAttribute("data-mdmf-llm-" + level.toLowerCase()) || "";
				var size = sizeInput && sizeInput.value ? sizeInput.value : "20";
				line.textContent = text.replace("{0}", size);
			}
		}
		radios.forEach(function (r) { r.addEventListener("change", refresh); });
		if (sizeInput) sizeInput.addEventListener("input", refresh);
	})();

	// ---- 2026-10-14: step 1's style cards. The radio is visually hidden and the check mark (.selected) was only set by the server, so a click
	// on Centralisation changed the posted value but left the mark and the sentence beside the button on Consolidation: it looked ignored.
	// Same pattern as the language-model cards above; the sentence comes from WizardPage's data-mdmf-style-<code> attributes.
	(function () {
		var radios = root.querySelectorAll("input[name=mdmfStyleRadio]");
		if (!radios.length) return;
		var line = byId("mdmfStyleConsequence");
		function refresh() {
			radios.forEach(function (r) {
				var card = r.closest(".mdmf-choice-card");
				if (card) card.classList.toggle("selected", r.checked);
				if (r.checked && line) {
					var text = line.getAttribute("data-mdmf-style-" + r.value.toLowerCase());
					if (text) line.textContent = text;
				}
			});
		}
		radios.forEach(function (r) { r.addEventListener("change", refresh); });
	})();

	// ---- UX lot U4 (2026-10-13): step 1 - the project code proposed from the label as it is typed (mirror of WizardRules#projectCodeFromLabel: accents dropped, words
	// joined with their first letter upper-cased, leading digits dropped, 40 characters). Once the person edits the code it is theirs: the label no longer overwrites it.
	(function () {
		var label = byId("mdmfLabel");
		var code = byId("mdmfCode");
		if (!label || !code || code.readOnly || !label.hasAttribute("data-mdmf-code-source")) return;
		function proposal(text) {
			var plain = text && text.normalize ? text.normalize("NFD").replace(/[\u0300-\u036f]/g, "") : (text || "");
			var out = "";
			plain.split(/[^A-Za-z0-9]+/).forEach(function (w) { if (w) out += w.charAt(0).toUpperCase() + w.substring(1); });
			out = out.replace(/^[^A-Za-z]+/, "");
			return out.length > 40 ? out.substring(0, 40) : out;
		}
		var touched = code.value !== "" && code.value !== proposal(label.value);
		code.addEventListener("input", function () { touched = true; });
		label.addEventListener("input", function () { if (!touched) code.value = proposal(label.value); });
	})();

	// the "Advanced options" sentence of step 1 follows the language-model level that is checked
	(function () {
		var spot = byId("mdmfAdvLlm");
		var radios = root.querySelectorAll("input[name=mdmfLlmSharingRadio]");
		if (!spot || !radios.length) return;
		radios.forEach(function (r) {
			r.addEventListener("change", function () {
				var title = r.closest(".mdmf-choice-card") ? r.closest(".mdmf-choice-card").querySelector(".mdmf-choice-card-title") : null;
				if (r.checked && title) spot.textContent = title.textContent;
			});
		});
	})();

	// ---- UX lot U4: a click on a step of the rail that is not open yet says why, in one line under the rail (the hover title alone was the only explanation)
	(function () {
		var note = byId("mdmfRailNote");
		if (!note) return;
		root.querySelectorAll(".mdmf-wiz-steps li[data-mdmf-blocked-note]").forEach(function (li) {
			function show(e) { e.preventDefault(); note.textContent = li.getAttribute("data-mdmf-blocked-note"); note.hidden = false; }
			li.addEventListener("click", show);
			li.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") show(e); });
		});
	})();

	// ---- fields synced into their hidden, named counterpart on every submit (harmless when irrelevant to
	// the action actually clicked - same convention as the proposal review screen's filter fields) ----
	function syncAlways() {
		// the catalog source id is written by the combobox below the moment an option is picked (no select to read)
		var schemaChecks = root.querySelectorAll(".mdmf-schema-check:checked");
		var schemas = [];
		schemaChecks.forEach(function (c) { schemas.push(c.value); });
		set("mdmfSchemas", schemas.join(","));
		var bulkInput = byId("mdmfBulkThresholdInput");
		if (bulkInput) set("mdmfBulkThreshold", bulkInput.value);
		// staging-proposal-run lot (2026-09-24): the "Propose the staging" checkboxes (WizardPage
		// .renderProposalRunForm) are real, unnamed checkbox inputs - copy their checked state into the two
		// hidden fields right before every submit, same convention as mdmfSchemas above. Harmless when the
		// checkboxes are not on the page at all (the "done"/"running" states never render them).
		var qualityCheck = byId("mdmfProposalIncludeQualityCheckbox");
		if (qualityCheck) set("mdmfProposalIncludeQuality", qualityCheck.checked ? "true" : "false");
		var capabilityCheck = byId("mdmfProposalIncludeCapabilityCheckbox");
		if (capabilityCheck) set("mdmfProposalIncludeCapability", capabilityCheck.checked ? "true" : "false");
		// sources+landing redesign, lot LANDING (2026-09-24): "Prepare the landing"'s own "use the language
		// model" checkbox (WizardPage.renderLandingRunForm) - same convention as the two above.
		var landingLlmCheck = byId("mdmfLandingRunUseLlmCheckbox");
		if (landingLlmCheck) set("mdmfLandingRunUseLlm", landingLlmCheck.checked ? "true" : "false");
		// landing-walkthrough lot (2026-10-16): the language-model radios of the application page shown (blank when the page has none)
		var walkLlmRadio = root.querySelector("input[name=mdmfWalkLlmRadio]:checked");
		set("mdmfWalkLlm", walkLlmRadio ? walkLlmRadio.value : "");
		// volume wave 2, lot V2-E (2026-10-03): the re-run's "Full refresh" checkbox (WizardPage.renderLandingRunForm) - same convention.
		var landingFullRefresh = byId("mdmfLandingRunFullRefreshCheckbox");
		if (landingFullRefresh) set("mdmfLandingRunFullRefresh", landingFullRefresh.checked ? "true" : "false");
		// lot C1-B (CENTRALISATION, Pack step): the ticked pack cards and the options inside them, as two comma lists ("pack:option" for an option),
		// same convention as mdmfSchemas above. Harmless when the cards are not on the page.
		// the need box of the step shown (Pack or Specification; unnamed on purpose, one hidden field carries it)
		var needPanel = panel(current);
		var needBox = needPanel ? needPanel.querySelector(".mdmf-accel-need") : null;
		if (needBox) set("mdmfAccelNeed", needBox.value);
		if (root.querySelector(".mdmf-accel-pack")) {
			var packs = [], options = [];
			root.querySelectorAll(".mdmf-accel-pack:checked").forEach(function (c) { packs.push(c.value); });
			root.querySelectorAll(".mdmf-accel-option:checked").forEach(function (c) { options.push(c.value); });
			set("mdmfAccelPacks", packs.join(","));
			set("mdmfAccelOptions", options.join(","));
		}
	}

	// threshold-left-out lot (2026-09-23): the page-wide threshold input's value is echoed live on every
	// "Accept all ... (>= N %)" button label (WizardPage.thresholdSuffix's <span class="mdmf-bulk-threshold-
	// value">) so the number a person reads on a card button never lags what a bulk accept would actually use -
	// no round trip needed, this is purely cosmetic (the real value is re-read from mdmfBulkThreshold, synced
	// by syncAlways above, at submit time).
	(function () {
		var bulkInput = byId("mdmfBulkThresholdInput");
		if (!bulkInput) return;
		bulkInput.addEventListener("input", function () {
			var labels = root.querySelectorAll(".mdmf-bulk-threshold-value");
			labels.forEach(function (l) { l.textContent = bulkInput.value; });
		});
	})();

	// volume wave 2, lot V2-E (2026-10-03): picking another change column pre-selects the kind its type reads as (the option carries
	// data-kind: date -> TIMESTAMP, number -> MONOTONIC_ID, anything else -> ORDERED_STRING); the person can still change the kind.
	(function () {
		root.querySelectorAll(".mdmf-change-column").forEach(function (select) {
			select.addEventListener("change", function () {
				var option = select.options[select.selectedIndex];
				var kind = option ? option.getAttribute("data-kind") : "";
				var box = select.closest(".mdmf-change-col");
				var kindSelect = box ? box.querySelector(".mdmf-change-kind") : null;
				if (kindSelect && kind) kindSelect.value = kind;
			});
		});
	})();

	// ---- source-combobox lot (2026-09-23): the catalog-source picker of step 2 (WizardPage.renderCatalogSourceCombo).
	// A text input filters the pre-rendered listbox on its data-search text (name, platform, connector, id,
	// lower-cased server-side); picking an option writes a hidden field, shows the label in the input and clicks
	// a hidden pick button, so the round trip goes through the [data-mdmf-action] handler below exactly like
	// every other action (progress toast included) - never a second submit path. Keyboard: ArrowDown/ArrowUp
	// move, Enter picks (the active option, or the only match), Escape closes and restores the picked label; a
	// click uses mousedown so the input's blur never closes the list before the click lands. Focusing the input
	// shows the whole list (the picked label is not a filter).
	// source-onboarding lot (2026-09-23): factored into initCombo(...) so the onboarding connection picker below
	// reuses the exact same behaviour instead of a second, drifting copy - parameterized purely by element ids,
	// nothing else changed from the block this replaces.
	// llm-connector-combo lot (2026-09-24): the widget itself now lives in combo.js (window.mdmfInitCombo), prepended
	// to this file by ProjectWizardUserService.script and shared with the Connexion screen.
	function initCombo(comboId, inputId, listId, hiddenFieldId, pickButtonId) {
		if (window.mdmfInitCombo) window.mdmfInitCombo(comboId, inputId, listId, hiddenFieldId, pickButtonId);
	}
	// UX lot U2 (2026-10-13): the single "Add an application" list. A greyed row (aria-disabled) is shown with its reason but never picked: the guards below run
	// BEFORE combo.js's own handlers (a capturing listener on the list stops the click; a keydown listener registered first stops Enter on a greyed active row).
	(function () {
		var list = byId("mdmfAddAppList"), input = byId("mdmfAddAppSearch");
		if (!list || !input) return;
		list.addEventListener("mousedown", function (e) {
			var off = e.target && e.target.closest ? e.target.closest(".mdmf-combo-option[aria-disabled='true']") : null;
			if (off) { e.preventDefault(); e.stopPropagation(); }
		}, true);
		input.addEventListener("keydown", function (e) {
			if ((e.key === "Enter" || e.keyCode === 13) && !list.hidden) {
				var active = list.querySelector(".mdmf-combo-option.active");
				if (active && active.getAttribute("aria-disabled") === "true") { e.preventDefault(); e.stopImmediatePropagation(); }
			}
		});
	})();
	initCombo("mdmfAddAppCombo", "mdmfAddAppSearch", "mdmfAddAppList", "mdmfOnboardConnector", "mdmfAddAppPick");
	initCombo("mdmfCatalogSourceCombo", "mdmfCatalogSourceSearch", "mdmfCatalogSourceList", "mdmfCatalogSourceId", "mdmfCatalogSourcePick");
	// source-onboarding lot (2026-09-23): the "Create a catalog source" panel's own connection picker - same
	// widget, a distinct set of element ids (WizardPage.renderOnboardConnectionCombo). Coordinator review the
	// same day: the panel body (and this combo) is now rendered ONLY when the panel is open
	// (WizardPage.Model#onboardOpen) - genuinely absent from the DOM otherwise, not merely hidden, so
	// initCombo's own "not found" guard (the `if (!combo) return;` at its top) is what makes this call safe on
	// every page load, open or closed.
	initCombo("mdmfOnboardConnectorCombo", "mdmfOnboardConnectorSearch", "mdmfOnboardConnectorList", "mdmfOnboardConnector", "mdmfOnboardConnectorPick");
	// distribution-wizard lot, coordinator review (2026-09-24): the data product panel's "+ Add assets from the
	// catalogue" combo (WizardPage#renderDistributionProductAssetCombo) - same "absent from the DOM until the
	// panel is open" safety as the onboarding combo above.
	initCombo("mdmfProductAssetCombo", "mdmfProductAssetSearch", "mdmfProductAssetList", "mdmfProductAddAssetId", "mdmfProductAddAssetPick");

	// ---- sample-files lot (2026-09-23): "Sources from sample files" - drop/browse files, read each one
	// client-side with FileReader, list them in a table, submit the whole batch as one JSON array in the
	// hidden mdmfSampleFiles textarea (window.mdmfSyncSampleFiles, called from the [data-mdmf-action] handler
	// below on SOURCE_FILES_IMPORT, same "sync right before submit" idea as syncAlways()/mdmfIncludeBelowThreshold).
	// Deliberately simplified, PORTED copies of com.on.ebx.factory.wizard.SampleFileRules constants/rules (the
	// server re-validates everything - task brief): mimeByExt is the identical extension map, RAW_BYTES_CAP
	// must be kept equal to SampleFileRules.RAW_BYTES_CAP by hand (no shared source between Java and this file),
	// and the application-code proposal is intentionally NOT the same algorithm as
	// SampleFileRules#sourceSystemCode (task brief's own words: "keep it simple: upper-case, non-alphanumerics
	// -> '_', extension stripped, max 20 chars") - connectionId/host are never computed or shown client-side at
	// all, only the server ever derives them (SampleSourceImporter), so there is nothing to port for those two.
	(function () {
		var dropzone = byId("mdmfSampleDropzone");
		if (!dropzone) return; // panel not rendered at all (catalog not installed) - WizardPage.renderStep2's own gate

		var fileInput = byId("mdmfSampleFileInput");
		var browseBtn = byId("mdmfSampleBrowse");
		var table = byId("mdmfSampleTable");
		var tbody = byId("mdmfSampleTableBody");
		var submitBtn = byId("mdmfSampleSubmit");
		var errorsBox = byId("mdmfSampleErrors");
		var hiddenField = byId("mdmfSampleFiles");
		var removeLabel = table.getAttribute("data-mdmf-remove-label") || "Remove";

		var mimeByExt = {
			csv: "text/csv", txt: "text/csv", tsv: "text/tab-separated-values",
			json: "application/json", ndjson: "application/x-ndjson", jsonl: "application/x-ndjson"
		};
		// keep equal to com.on.ebx.factory.wizard.SampleFileRules.RAW_BYTES_CAP - see this block's own comment
		var RAW_BYTES_CAP = 1400000;
		var files = {}; // row id -> {name, size, mime, dataUrl}
		var nextId = 1;
		var totalRawBytes = 0;

		function extOf(name) { var i = name.lastIndexOf("."); return i < 0 ? "" : name.slice(i + 1).toLowerCase(); }
		function baseNameOf(name) { var i = name.lastIndexOf("."); return i <= 0 ? name : name.slice(0, i); }
		function proposeCode(name) {
			var code = baseNameOf(name).toUpperCase().replace(/[^A-Z0-9]+/g, "_").replace(/^_+|_+$/g, "");
			return (code.slice(0, 20)) || "SRC";
		}
		function kindLabel(mime) {
			if (mime === "text/csv") return "CSV";
			if (mime === "text/tab-separated-values") return "TSV";
			if (mime === "application/x-ndjson") return "NDJSON";
			if (mime === "application/json") return "JSON";
			return "";
		}
		function rawBytesOf(base64) {
			if (!base64) return 0;
			var len = base64.length, padding = 0;
			if (base64.slice(-2) === "==") padding = 2; else if (base64.slice(-1) === "=") padding = 1;
			return Math.floor(len / 4) * 3 - padding;
		}
		function showError(kind, name) {
			var msg = errorsBox.getAttribute("data-mdmf-msg-" + kind) || "";
			msg = msg.split("{0}").join(name || "");
			errorsBox.textContent = msg;
			errorsBox.hidden = false;
		}
		function clearError() { errorsBox.hidden = true; errorsBox.textContent = ""; }
		function escapeHtml(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : s; return d.innerHTML; }

		function checkDuplicateCodes() {
			var seen = {}, dup = null;
			tbody.querySelectorAll(".mdmf-sample-code").forEach(function (input) {
				var v = (input.value || "").trim().toUpperCase();
				if (!v) return;
				if (seen[v]) dup = v; else seen[v] = true;
			});
			if (dup) showError("duplicate", dup); else clearError();
		}

		function addRow(name, size, mime, dataUrl) {
			var id = "s" + (nextId++);
			files[id] = { name: name, size: size, mime: mime, dataUrl: dataUrl };
			// landing test fixes (2026-10-17): the smallest rank neither held by an application of the project (data-mdmf-taken-ranks) nor typed in another row -
			// mirrors SampleFileRules.nextFreeRank
			var taken = {};
			(dropzone.getAttribute("data-mdmf-taken-ranks") || "").split(",").forEach(function (r) { if (r) taken[parseInt(r, 10)] = true; });
			tbody.querySelectorAll(".mdmf-sample-trust").forEach(function (input) { var v = parseInt(input.value, 10); if (v > 0) taken[v] = true; });
			var position = 1;
			while (taken[position]) position++;
			var tr = document.createElement("tr");
			tr.setAttribute("data-mdmf-sample-row", id);
			tr.innerHTML = "<td>" + escapeHtml(name) + "</td>"
				+ "<td>" + Math.ceil(size / 1024) + " KB</td>"
				+ "<td>" + kindLabel(mime) + "</td>"
				+ "<td><input type=\"text\" class=\"mdmf-sample-code\" value=\"" + escapeHtml(proposeCode(name)) + "\"/></td>"
				+ "<td><input type=\"number\" class=\"mdmf-sample-trust\" min=\"1\" value=\"" + position + "\"/></td>"
				+ "<td><button type=\"button\" class=\"mdmf-btn mdmf-btn-sm mdmf-btn-danger\" data-mdmf-sample-remove=\"1\">" + escapeHtml(removeLabel) + "</button></td>";
			tbody.appendChild(tr);
			table.hidden = false;
			if (submitBtn) submitBtn.hidden = false;
			tr.querySelector("[data-mdmf-sample-remove]").addEventListener("click", function () {
				var payload = files[id] ? files[id].dataUrl : "";
				var comma = payload.indexOf(",");
				totalRawBytes -= rawBytesOf(comma >= 0 ? payload.slice(comma + 1) : "");
				delete files[id];
				tr.parentNode.removeChild(tr);
				if (!tbody.children.length) { table.hidden = true; if (submitBtn) submitBtn.hidden = true; }
				checkDuplicateCodes();
			});
			tr.querySelector(".mdmf-sample-code").addEventListener("input", checkDuplicateCodes);
			checkDuplicateCodes();
		}

		function handleFiles(fileList) {
			var list = Array.prototype.slice.call(fileList || []);
			list.forEach(function (file) {
				var mime = mimeByExt[extOf(file.name)];
				if (!mime) { showError("ext", file.name); return; }
				var reader = new FileReader();
				reader.onload = function () {
					var raw = String(reader.result || ""), comma = raw.indexOf(",");
					if (comma < 0) { showError("ext", file.name); return; }
					var base64 = raw.slice(comma + 1);
					var rawBytes = rawBytesOf(base64);
					if (rawBytes > RAW_BYTES_CAP) { showError("size", file.name); return; }
					if (totalRawBytes + rawBytes > RAW_BYTES_CAP) { showError("total", file.name); return; }
					totalRawBytes += rawBytes;
					clearError();
					var dataUrl = "data:" + mime + ";name=" + encodeURIComponent(file.name) + ";base64," + base64;
					addRow(file.name, file.size, mime, dataUrl);
				};
				reader.onerror = function () { showError("ext", file.name); };
				reader.readAsDataURL(file);
			});
		}

		dropzone.addEventListener("click", function (e) { if (e.target !== fileInput) fileInput.click(); });
		dropzone.addEventListener("keydown", function (e) { if (e.key === "Enter" || e.key === " ") { e.preventDefault(); fileInput.click(); } });
		if (browseBtn) browseBtn.addEventListener("click", function (e) { e.stopPropagation(); fileInput.click(); });
		fileInput.addEventListener("change", function () { handleFiles(fileInput.files); fileInput.value = ""; });
		["dragenter", "dragover"].forEach(function (n) { dropzone.addEventListener(n, function (e) { e.preventDefault(); dropzone.classList.add("is-dragging"); }); });
		["dragleave", "drop"].forEach(function (n) { dropzone.addEventListener(n, function (e) { e.preventDefault(); dropzone.classList.remove("is-dragging"); }); });
		dropzone.addEventListener("drop", function (e) { handleFiles(e.dataTransfer && e.dataTransfer.files); });

		// read by the [data-mdmf-action] handler below, right before the real submit, on SOURCE_FILES_IMPORT only
		window.mdmfSyncSampleFiles = function () {
			var out = [];
			tbody.querySelectorAll("tr[data-mdmf-sample-row]").forEach(function (tr) {
				var id = tr.getAttribute("data-mdmf-sample-row");
				var file = files[id];
				if (!file) return;
				var codeInput = tr.querySelector(".mdmf-sample-code");
				var trustInput = tr.querySelector(".mdmf-sample-trust");
				out.push({
					name: file.name, size: file.size,
					code: codeInput ? codeInput.value : "",
					trustRank: trustInput ? (parseInt(trustInput.value, 10) || 1) : 1,
					dataUrl: file.dataUrl
				});
			});
			hiddenField.value = JSON.stringify(out);
		};
	})();

	// ---- UX lot U2 (2026-10-13): the tables of step 2's cards. A tick posts NOTHING: the ticks changed since the page was drawn are sent in one
	// TABLES_KEEP, by "Keep these tables" (its card), by "Continue to the landing" (every card) or by Next. "+id" ticks, "-id" unticks. ----
	function collectTicks(scope) {
		var box = scope === "all" ? root : byId(scope), out = [];
		if (!box) return out;
		box.querySelectorAll(".mdmf-tick[data-mdmf-tick-id]").forEach(function (cb) {
			if (cb.disabled || cb.checked === cb.defaultChecked) return;
			out.push((cb.checked ? "+" : "-") + cb.getAttribute("data-mdmf-tick-id"));
		});
		return out;
	}
	function refreshTickCount(card) {
		var counter = card.querySelector(".mdmf-tick-count");
		if (!counter) return;
		var boxes = card.querySelectorAll(".mdmf-tick[data-mdmf-tick-id]"), on = 0;
		boxes.forEach(function (cb) { if (cb.checked) on++; });
		var template = counter.getAttribute("data-mdmf-count-template") || "{0} / {1}";
		counter.textContent = template.replace("{0}", String(on)).replace("{1}", String(boxes.length));
	}
	root.querySelectorAll(".mdmf-src2-app-card").forEach(function (card) {
		card.addEventListener("change", function (e) {
			if (e.target && e.target.classList && e.target.classList.contains("mdmf-tick")) refreshTickCount(card);
		});
	});
	root.querySelectorAll("[data-mdmf-tick-all]").forEach(function (link) {
		link.addEventListener("click", function (e) {
			e.preventDefault();
			var card = link.closest(".mdmf-src2-app-card");
			if (!card) return;
			var wanted = link.getAttribute("data-mdmf-tick-all") === "true";
			card.querySelectorAll(".mdmf-tick[data-mdmf-tick-id]").forEach(function (cb) { if (!cb.disabled) cb.checked = wanted; });
			refreshTickCount(card);
		});
	});

	// ---- landing-walkthrough lot (2026-10-16): the application pages of step 3. A tick posts nothing (the buttons of the page carry data-mdmf-ticks-scope); the page
	// only keeps "Validate" and its reason in step with the boxes, and the key picker's button in step with the columns ticked ----
	root.querySelectorAll(".mdmf-walk-page").forEach(function (page) {
		function refreshValidate() {
			var button = page.querySelector("[data-mdmf-walk-validate]");
			var reason = page.querySelector("[data-mdmf-walk-reason]");
			if (!button) return;
			var on = 0;
			page.querySelectorAll(".mdmf-tick[data-mdmf-tick-id]").forEach(function (cb) { if (cb.checked) on++; });
			button.disabled = on === 0;
			if (reason) reason.hidden = on !== 0;
		}
		page.addEventListener("change", function (e) {
			var t = e.target;
			if (!t || !t.classList) return;
			if (t.classList.contains("mdmf-tick")) refreshValidate();
			if (t.classList.contains("mdmf-key-check")) {
				var box = t.closest(".mdmf-walk-keybox");
				var use = box ? box.querySelector("[data-mdmf-walk-use]") : null;
				if (!use) return;
				var cols = [];
				box.querySelectorAll(".mdmf-key-check:checked").forEach(function (c) { cols.push(c.value); });
				use.disabled = cols.length === 0;
				var template = box.getAttribute("data-mdmf-use-template") || "{0}";
				use.textContent = cols.length ? template.replace("{0}", cols.join(" + ")) : use.getAttribute("data-mdmf-choose-text") || use.textContent;
			}
		});
	});

	// ---- every business action: set the hidden fields the button names, then click the one real submit ----
	root.querySelectorAll("[data-mdmf-action]").forEach(function (b) {
		b.addEventListener("click", function (e) {
			e.preventDefault();
			// audit F-02: an action the user's MDM Factory right does not cover is rendered disabled; never submit it (the server refuses it anyway)
			if (b.getAttribute("data-mdmf-denied") === "true") return;
			var action = b.getAttribute("data-mdmf-action");
			if (action === "GOTO_STEP") return; // handled above, no round trip
			// sources-landing live fix (2026-09-24): a generic confirmation, read the same way on ANY
			// [data-mdmf-action] button (first user: the Review screen's own Remove, task brief item 5 -
			// "with a confirmation") - the message is the button's own attribute (already localized server
			// side by WizardMessages), so no new hidden field or action-specific JS is needed.
			var confirmText = b.getAttribute("data-mdmf-confirm");
			if (confirmText !== null && !window.confirm(confirmText)) return;
			// UX lot U2 (2026-10-13): a button that keeps the ticks of the cards names the card whose boxes it reads ("all" = every card). "Continue to the
			// landing" has nothing to send when no box changed: the step was already shown by its own goto handler, so it stops here, no round trip.
			var ticksScope = b.getAttribute("data-mdmf-ticks-scope");
			if (ticksScope !== null) {
				var ticks = collectTicks(ticksScope);
				if (!ticks.length && b.getAttribute("data-mdmf-skip-if-clean") === "true") {
					// landing-walkthrough lot (2026-10-16): the goto handler of this button showed the step client side without asking the server (its own action is
					// not GOTO_STEP), and the step is a placeholder since wave 11 - it must be loaded here or "Continue to the landing" ends on "Loading this step..."
					loadDeferredStep(current);
					return;
				}
				set("mdmfTableTicks", ticks.join(","));
			}
			syncAlways();
			// sample-files lot (2026-09-23): fills mdmfSampleFiles right before this one submit - same "sync
			// right before the real submit" discipline as syncAlways() itself, kept as its own step since the
			// sync function only exists when the panel actually rendered (window.mdmfSyncSampleFiles, set by
			// the IIFE above only when #mdmfSampleDropzone was found).
			if (action === "SOURCE_FILES_IMPORT" && window.mdmfSyncSampleFiles) window.mdmfSyncSampleFiles();
			set("mdmfAction", action);
			set("mdmfStep", String(current));
			// wave 11 (audit F-42): step 4's filter box - the button names the input whose text the server filters on; "Clear" names none
			var filterInput = b.getAttribute("data-mdmf-filter-input");
			if (filterInput !== null) { var fi = byId(filterInput); set("mdmfStagingFilter", fi ? fi.value : ""); }
			else if (b.getAttribute("data-mdmf-filter-clear") !== null) set("mdmfStagingFilter", "");
			var nature = b.getAttribute("data-mdmf-nature");
			if (nature !== null) set("mdmfNature", nature);
			var id = b.getAttribute("data-mdmf-id");
			if (id !== null) set("mdmfId", id);
			// staging-still-to-decide lot (2026-09-27): a field's Accept on step 4's "decisions left" card also
			// accepts that field's PROPOSED quality rules (shown there as "decided with the field") - every other
			// button leaves the field blank, so no other Accept ever carries anything.
			set("mdmfCarryRules", b.getAttribute("data-mdmf-carry-rules") === "true" ? "true" : "");
			// lot C1-B: "Edit" on a difference of the Proposal step names the textarea holding the edited JSON (copied into mdmfDetail, accepted by the
			// server through the ordinary decide path); a pass button names its pass, "run all" its flag - both always rewritten, so none lingers
			if (b.getAttribute("data-mdmf-accel-blank") === "true") { set("mdmfAccelPacks", ""); set("mdmfAccelOptions", ""); } // "Skip the packs, start blank"
			var detailFrom = b.getAttribute("data-mdmf-detail-from");
			set("mdmfDetail", detailFrom !== null && byId(detailFrom) ? byId(detailFrom).value : "");
			var accelPass = b.getAttribute("data-mdmf-accel-pass");
			set("mdmfAccelPass", accelPass !== null ? accelPass : "");
			set("mdmfAccelRunAll", b.getAttribute("data-mdmf-accel-run-all") === "true" ? "true" : "false");
			var sourceId = b.getAttribute("data-mdmf-source-id");
			if (sourceId !== null) set("mdmfSourceId", sourceId);
			var tableId = b.getAttribute("data-mdmf-table-id");
			if (tableId !== null) set("mdmfTableId", tableId);
			// automatic-intake lot (2026-10-06): the application a card's "Read now" names (a source system code, NOT a ProjectSource id: its own field)
			var intakeSource = b.getAttribute("data-mdmf-intake-source");
			if (intakeSource !== null) set("mdmfIntakeSource", intakeSource);
			// pipeline-buttons lot (2026-09-23): the two MATCHING_RUN buttons (incremental/all) carry their
			// scope this way, same convention as every other data-mdmf-* attribute here
			var scope = b.getAttribute("data-mdmf-scope");
			if (scope !== null) set("mdmfMatchScope", scope);
			// inline-reviews lot (2026-09-23): ported from ProposalReviewUserService.pageJs - the MATCH mini-panels'
			// own Send/Confirm buttons carry data-mdmf-comment-from (the textarea's id), copied into mdmfComment
			// right before submit, and data-mdmf-requires-checkbox (the confirmation checkbox's id), which blocks
			// the submit entirely when unchecked - "No matching for this entity" must never fire without it.
			var commentFrom = b.getAttribute("data-mdmf-comment-from");
			if (commentFrom !== null) { var ta = byId(commentFrom); set("mdmfComment", ta ? ta.value : ""); }
			var requiresCheckbox = b.getAttribute("data-mdmf-requires-checkbox");
			if (requiresCheckbox !== null) {
				var confirmBox = byId(requiresCheckbox);
				if (!confirmBox || !confirmBox.checked) { if (confirmBox) confirmBox.focus(); return; }
			}
			// QualityRule.severity lot (2026-09-23): the Save button next to a QUALITY line's severity <select>
			// reads that select's CURRENT value (same "walk up to the closest cell, read the live control" idea
			// as the landing key picker above) into mdmfSeverity right before submit.
			if (b.getAttribute("data-mdmf-severity-select") !== null) {
				var sevCell = b.closest("td") || b.parentElement;
				var sevSelect = sevCell ? sevCell.querySelector(".mdmf-severity-select") : null;
				set("mdmfSeverity", sevSelect ? sevSelect.value : "");
			}
			// quality-script-rule lot (2026-09-23): same idea as the QualityRule.severity Save button above, for
			// a SCRIPT_CONSTRAINT line's transfer-policy <select>.
			if (b.getAttribute("data-mdmf-transfer-policy-select") !== null) {
				var tpCell = b.closest("td") || b.parentElement;
				var tpSelect = tpCell ? tpCell.querySelector(".mdmf-transfer-policy-select") : null;
				set("mdmfTransferPolicy", tpSelect ? tpSelect.value : "");
			}
			// trust-rank-edit lot (2026-09-23): the Save button of a source card's trust-rank input names that
			// input's id; its CURRENT value goes into mdmfTrustRankEdit right before submit.
			var trustInput = b.getAttribute("data-mdmf-trust-input");
			if (trustInput !== null) { var ti = byId(trustInput); set("mdmfTrustRankEdit", ti ? ti.value : ""); }
			// sources-landing lot SOURCES (2026-09-24): same "button names the input's id, read its CURRENT
			// value right before submit" idea, for the Review screen's editable application code, and its own
			// "Remove" on a still-stuck queued application (that button names the connector id directly, no
			// input to read).
			var codeInput = b.getAttribute("data-mdmf-code-input");
			if (codeInput !== null) { var coi = byId(codeInput); set("mdmfSourceCodeEdit", coi ? coi.value : ""); }
			// deduced-scan lot (2026-09-24): the Confirm button of a board-2e card names the table-name input's
			// id, same "read its CURRENT value right before submit" idea as data-mdmf-code-input above.
			var nameInput = b.getAttribute("data-mdmf-name-input");
			if (nameInput !== null) { var ni = byId(nameInput); set("mdmfAssetName", ni ? ni.value : ""); }
			var removeConnectorId = b.getAttribute("data-mdmf-remove-connector-id");
			if (removeConnectorId !== null) set("mdmfRemoveConnectorId", removeConnectorId);
			// target-mapping lot 3 (docs/TARGET-MAPPING.md sec 4.3): the feed tree's own pipeline/sourceIdPipeline
			// Save buttons, same "the button names the input's id, read its CURRENT value right before submit" idea.
			var pipelineInput = b.getAttribute("data-mdmf-pipeline-input");
			if (pipelineInput !== null) { var pi = byId(pipelineInput); set("mdmfPipeline", pi ? pi.value : ""); }
			// lot P1 (2026-10-04): a derivation's "Save parameters" button names the box holding its parameter inputs; only the inputs
			// whose value differs from the one rendered (data-initial) are posted, as one JSON object {parameter: value} in mdmfParams -
			// a to-be-provided input left blank is therefore never sent, a literal cleared to blank is (the server allows exactly that).
			var paramBox = b.getAttribute("data-mdmf-param-box");
			if (paramBox !== null) {
				var box = byId(paramBox), changedParams = {};
				if (box) box.querySelectorAll(".mdmf-param-input").forEach(function (inp) {
					if (inp.value !== inp.getAttribute("data-initial")) changedParams[inp.getAttribute("data-param")] = inp.value;
				});
				set("mdmfParams", JSON.stringify(changedParams));
			}
			var sourceIdPipelineInput = b.getAttribute("data-mdmf-source-id-pipeline-input");
			if (sourceIdPipelineInput !== null) { var si = byId(sourceIdPipelineInput); set("mdmfSourceIdPipeline", si ? si.value : ""); }
			// threshold-left-out lot (2026-09-23): a bulk button (entity-wide or quality-only) names the id of
			// its card's shared "include below threshold" checkbox - its CURRENT checked state goes into
			// mdmfIncludeBelowThreshold right before submit, same "walk up, read the live control" idea as
			// mdmfSeverity/mdmfTransferPolicy above. A button with no such attribute (the two page-wide bulk
			// buttons, which never offer the checkbox) leaves the field at its blank, always-false default.
			var includeBelowInput = b.getAttribute("data-mdmf-include-below-input");
			if (includeBelowInput !== null) {
				var includeBelowBox = byId(includeBelowInput);
				set("mdmfIncludeBelowThreshold", (includeBelowBox && includeBelowBox.checked) ? "true" : "false");
			}
			// step-reset lot (2026-09-23): STEP_RESET_PLAN/STEP_RESET_CONFIRM both name the stage they act on;
			// STEP_RESET_CONFIRM also names the confirm text input's id, same "read its CURRENT value right
			// before submit" idea as mdmfPipeline/mdmfTrustRankEdit above.
			// wizard-matching-results lot (2026-09-24): a suspect-pair decide button names the golden/suspect
			// master keys it acts on - same "the button carries the id(s), copied into a hidden field right
			// before submit" convention as every other data-mdmf-* attribute in this loop.
			var matchTabValue = b.getAttribute("data-mdmf-match-tab-value");
			if (matchTabValue !== null) set("mdmfMatchTab", matchTabValue);
			var matchGolden = b.getAttribute("data-mdmf-match-golden");
			if (matchGolden !== null) set("mdmfMatchGolden", matchGolden);
			var matchSuspect = b.getAttribute("data-mdmf-match-suspect");
			if (matchSuspect !== null) set("mdmfMatchSuspect", matchSuspect);
			// step 6 queue per group (2026-10-18): "Same person - merge" names the card it decides; the records ticked "not this one" in that card are posted with it
			// (mdmfMatchSuspect = their keys, comma separated), the group's own record keys are in mdmfMatchGolden above
			var matchExcludeIn = b.getAttribute("data-mdmf-match-exclude-in");
			if (matchExcludeIn !== null) {
				var matchCard = byId(matchExcludeIn), excluded = [];
				if (matchCard) matchCard.querySelectorAll("input.mdmf-match-excl").forEach(function (box) { if (box.checked) excluded.push(box.value); });
				set("mdmfMatchSuspect", excluded.join(","));
			}
			var resetStage = b.getAttribute("data-mdmf-reset-stage");
			if (resetStage !== null) set("mdmfResetStage", resetStage);
			var confirmInput = b.getAttribute("data-mdmf-confirm-input");
			if (confirmInput !== null) { var ci = byId(confirmInput); set("mdmfResetConfirm", ci ? ci.value : ""); }
			// distribution-wizard lot (2026-09-24): the two "Send now" buttons of a target's setup panel carry
			// their scope this way, same convention as data-mdmf-scope/mdmfMatchScope above.
			var distSendScope = b.getAttribute("data-mdmf-dist-send-scope");
			if (distSendScope !== null) set("mdmfDistSendScope", distSendScope);
			// live bug fix (2026-09-25, DistribTest, task brief item 4): an unconfigured connection's own "Set
			// up" button (7a) names the connector this way, so the freshly-opened NEW target's setup panel can
			// preselect it - same convention as data-mdmf-dist-send-scope above.
			var distNewConnector = b.getAttribute("data-mdmf-connector");
			if (distNewConnector !== null) set("mdmfDistNewConnector", distNewConnector);
			if (b.getAttribute("data-mdmf-change-input") !== null) {
				// volume wave 2, lot V2-E: a table's change-column selector (WizardPage.renderChangeColumn) - its two selects sit in the same <div>
				var changeBox = b.closest(".mdmf-change-col");
				var changeColumn = changeBox ? changeBox.querySelector(".mdmf-change-column") : null;
				var changeKind = changeBox ? changeBox.querySelector(".mdmf-change-kind") : null;
				set("mdmfChangeColumn", changeColumn ? changeColumn.value : "");
				set("mdmfChangeKind", changeKind ? changeKind.value : "");
			}
			if (b.getAttribute("data-mdmf-key-input") !== null) {
				// column-picker lot: a table with known columns (WizardSnapshot.TableInfo.columnNames) renders
				// checkboxes (.mdmf-key-check) instead of the old free-text field - collect the checked ones. A
				// table with no known columns still falls back to the original .mdmf-key-input text field
				// (WizardPage.renderLandingTableRow's own fallback), so both are handled here.
				var cell = b.closest("td");
				var checks = cell ? cell.querySelectorAll(".mdmf-key-check") : [];
				if (checks.length) {
					var cols = [];
					cell.querySelectorAll(".mdmf-key-check:checked").forEach(function (c) { cols.push(c.value); });
					set("mdmfKeyColumns", cols.join(","));
				} else {
					var input = cell ? cell.querySelector(".mdmf-key-input") : null;
					set("mdmfKeyColumns", input ? input.value : "");
				}
			}
			// wizard-progress lot (2026-09-22): the slow, real server actions (the two staging suggestions,
			// the LLM pass longest of all) show the progress toast immediately, before the real submit fires.
			// Fast actions (SAVE_PROJECT, SOURCE_ADD, TABLE_TOGGLE, ...) are deliberately left out - a
			// progress() call on every single click would be noise, not feedback. TABLE_PREVIEW (data-sample
			// lot) joins this list: a live connectors_call, not instant either.
			// job-engine lot C3 (2026-09-24): PLAN and GENERATE now queue their work and return at once (the
			// jobs banner takes over from here) - their own toast entries are gone, same removal as
			// SOURCE_ONBOARD_CATALOG/SCAN/ALL and SOURCE_FILES_IMPORT below.
			// source-onboarding lot (2026-09-23): OPEN/PICK/REFRESH are real EBX/MCP reads (OPEN and REFRESH both
			// call connectors_list; PICK/REFRESH also connectors_describe/connectors_catalog_source) - CATALOG and
			// SCAN are real MCP writes too, but job-engine lot C3 moved them onto the job queue, so they no longer
			// block the postback either. CLOSE is deliberately left out, like every other fast, EBX-only action
			// above: it only resets local state.
			if (action === "SOURCE_ONBOARD_OPEN") progress(progressLabels.onboardOpen);
			else if (action === "SOURCE_ONBOARD_PICK") progress(progressLabels.onboardPick);
			else if (action === "SOURCE_ONBOARD_REFRESH") progress(progressLabels.onboardPick);
			// audit F-34 (wave 10): STAGING_SUGGEST (the rule pass and its chained language-model pass), QUALITY_SUGGEST, MATCHING_SUGGEST, the distribution Configure /
			// Install triggers / Create the data product and the key study with the language model now queue a job and return at once: the banner follows them,
			// and every action locks the buttons until the page comes back (lockButtons) - none keeps a toast of its own.
			// job-engine lot C3 (2026-09-24): STAGING_SUGGEST_LLM/QUALITY_SUGGEST_LLM/CAPABILITY_SUGGEST_LLM/
			// MATCHING_SUGGEST_LLM now enqueue and return at once (FactoryJobQueue, resource DESIGN) - the
			// language-model pass itself runs in the background, followed by the generic jobs banner below, so
			// none of the four gets a progress() toast any more (the postback they DO make is fast).
			// staging-proposal-run lot (2026-09-24): starting the run itself is fast (it only writes the parent
			// PipelineStep and submits the background work - ebx.StagingProposalRun#start) even though the run it
			// kicks off is not; the toast still helps here since the click otherwise looks like nothing happened
			// for the split second before the page reloads into the "running" state.
			else if (action === "STAGING_PROPOSAL_RUN") progress(progressLabels.stagingSuggest);
			else if (action === "TABLE_PREVIEW") progress(progressLabels.preview);
			// landing key-study lot (2026-09-23): a live sample of 25 rows, then a language-model run for an
			// administrator - the slowest thing on step 3 besides GENERATE. TABLE_KEY_APPLY is an EBX-only write.
			else if (action === "TABLE_KEY_STUDY") progress(progressLabels.keyStudy);
			// pipeline-buttons lot (2026-09-23): every slow action past staging suggestion gets the same toast -
			// generate/load (a real MCP or transfer round trip), every *_LLM pass, and configure/run matching
			// lot C3 (2026-09-24): STAGING_GENERATE/STAGING_LOAD/MASTER_GENERATE/MASTER_LOAD/REAPPLY/MEASURE now
			// queue a background job and return at once (ebx.CapabilityApplyRun, ebx.StagingLoadRun/MasterBuildRun
			// with a "stages" param) - the old progress toast for THESE actions is gone; the jobs banner above
			// (data-mdmf-jobs-banner) follows the run instead. APPLY (still synchronous - see
			// ProjectWizardUserService#decide's own auto-apply-on-accept path, out of this lot's scope) and REMOVE
			// keep their own toast, unchanged.
			// lot C3 (2026-09-24, jobs-matching): MATCHING_CONFIGURE/MATCHING_RUN now queue a background job and
			// return at once (ebx.MatchingConfigureRun/MatchingRunRun) - the jobs banner follows them instead of
			// this per-action toast (removed here on purpose; progressLabels.matchingRun is still used below by
			// MATCH_PAIR_* actions, which stayed synchronous).
			// inline-reviews lot (2026-09-23): SCRIPT_CONSTRAINT apply/remove/measure/re-apply (ebx.CapabilityApplier,
			// can publish a model) and REPROPOSE (a language-model call) - same "arm before the real submit fires"
			// discipline as every other slow action above, ported from ProposalReviewUserService.pageJs.
			// automatic-intake wizard lot (2026-10-05): INTAKE_SWITCH_ON saves the settings, installs the triggers on the landing tables and republishes the landing
			// models - one synchronous request, minutes on a large landing; INTAKE_SWITCH_OFF removes the triggers. The confirmation (data-mdmf-confirm) came first.
			else if (action === "INTAKE_SWITCH_ON") progress(progressLabels.intakeSwitchOn);
			else if (action === "INTAKE_SWITCH_OFF") progress(progressLabels.intakeSwitchOff);
			else if (action === "ACCEL_SUGGEST_PACKS") progress(progressLabels.accelSuggest);
			else if (action === "APPLY") progress(progressLabels.apply);
			else if (action === "REMOVE") progress(progressLabels.remove);
			else if (action === "REPROPOSE") progress(progressLabels.repropose);
			// wizard-match-tree lot (2026-09-23): Simulate runs the add-on on real records (MatchPlanSimulationRunner) - not instant
			else if (action === "SIMULATE") progress(progressLabels.simulate);
			// wizard-matching-results lot (2026-09-24): a suspect decision runs the add-on's own accept/reject
			// (and, for MERGE, a real merge) - not instant, same toast treatment as every other real EBX write above.
			else if (action === "MATCH_PAIR_MERGE" || action === "MATCH_PAIR_REJECT" || action === "MATCH_PAIR_BULK_MERGE" || action === "MATCH_GROUP_MERGE" || action === "MATCH_GROUP_REJECT") progress(progressLabels.matchingRun);
			// perf/wizard-distribution-lazy (2026-09-27): the rail's step 7 button (and the placeholder's own
			// fallback button) builds the whole distribution step server side - Connectors round trips, not instant
			else if (action === "STEP_VIEW" && isDistribution(current)) progress(progressLabels.distributionLoad);
			else if (action === "STEP_VIEW") progress(progressLabels.stepLoad);
			// job-engine lot C3 (2026-09-24): SOURCE_ONBOARD_ALL and SOURCE_FILES_IMPORT both queue their work
			// (SourcesRun) and return at once now - the jobs banner takes over from here, so neither needs its
			// own toast any more (removed along with PLAN/GENERATE/SOURCE_ONBOARD_CATALOG/SCAN above).
			// scroll-anchor lot (2026-09-23): every action here is a real form submit, so the whole page
			// re-renders from scratch server-side and the pane drops back to the top - brutal on a long entity/
			// attribute list where DECIDE_ACCEPT/DECIDE_REJECT is clicked one row at a time (product feedback).
			// Primary: the id of the clicked button's nearest identifiable block (an entity card, attribute
			// row, inline proposal line, or landing table row - all rendered with a stable "mdmf-<NATURE>-<id>"/
			// "mdmf-table-<id>" id, WizardPage) - restored via scrollIntoView, which works whatever ancestor
			// actually scrolls. Fallback (no such block nearby, e.g. a step-level button): the nearest
			// scrollable ancestor's own scrollTop - stored every time as a second line of defence in case the
			// anchored element itself is gone after the reload (rejected/absorbed/promoted away).
			// wave 11: opening a step starts at the top of it - no block of the step left to land on, and the old scroll position means nothing on another page
			if (action !== "STEP_VIEW") try {
				// source-onboarding lot (2026-09-23): SOURCE_ONBOARD_USE is clicked from inside the onboarding
				// panel, but the place the person actually needs to land is the "add an application" form right
				// above it (task brief: "closes the onboarding panel and scrolls to the add form") - the generic
				// closest-ancestor anchor below would instead restore the onboarding panel itself, so this one
				// action overrides it with the add form's own stable id (WizardPage.renderAddSourceForm).
				var anchorEl = action === "SOURCE_ONBOARD_USE" ? byId("mdmf-add-source-form") : b.closest("[id^='mdmf-']");
				// automatic-intake wizard lot (2026-10-05): a button can name the id to land on after the reload (data-mdmf-anchor) - the intake
				// screens replace the summary's content, so the block the button sits in does not exist any more after the round trip - and how
				// (data-mdmf-anchor-block: "start" for a tall screen, centering one would land in its middle).
				var namedAnchor = b.getAttribute("data-mdmf-anchor");
				ST.set("scrollAnchor", namedAnchor ? namedAnchor : ((anchorEl && anchorEl !== root) ? anchorEl.id : ""));
				ST.set("scrollBlock", namedAnchor ? (b.getAttribute("data-mdmf-anchor-block") || "") : "");
				ST.set("scrollTop", String(scrollableAncestor().scrollTop));
			} catch (ignored) { /* private mode or disabled storage: just skip the restore */ }
			var submit = byId("mdmfWizSubmit");
			// audit F-49: no toast armed above = a quick action or one that only queues a job; lock the buttons all the same until the page comes back
			if (!locked) lockButtons(LOCK_TIMEOUT_MS);
			if (submit) submit.click();
		});
	});

	// wave 11: Enter in the step 4 filter box applies the filter, like its button
	(function () {
		var box = byId("mdmfStagingFilterInput"), apply = byId("mdmfStagingFilterApply");
		if (!box || !apply) return;
		box.addEventListener("keydown", function (e) { if (e.key === "Enter") { e.preventDefault(); apply.click(); } });
	})();

	// wave 11 (audit F-48): an application card whose facts are being read in the background (data-mdmf-sources-checking, WizardPage root) - the display never
	// calls Connectors itself. Ask again every 3 s, at most 20 times per tab and only while nothing is being typed; the card's own "Check again" button stays.
	(function () {
		var KEY = "checking", n = 0;
		if (root.getAttribute("data-mdmf-sources-checking") !== "true") {
			ST.remove(KEY);
			return;
		}
		n = parseInt(ST.get(KEY) || "0", 10) || 0;
		if (n >= 20) return;
		window.setTimeout(function () {
			var a = document.activeElement;
			if (a && root.contains(a) && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) && a.type !== "button" && a.type !== "submit") return;
			ST.set(KEY, String(n + 1)); // no count when storage is blocked: the cap is then the page's own re-render
			set("mdmfAction", "SOURCES_RUN_POLL");
			set("mdmfStep", String(current));
			var submit = byId("mdmfWizSubmit");
			if (submit) submit.click();
		}, 3000);
	})();

	var hiddenSubmit = byId("mdmfWizSubmit");
	if (hiddenSubmit) hiddenSubmit.style.display = "none";

	showStep(current);
	// landing-walkthrough lot (2026-10-16): whatever made the step shown a placeholder (see the skip-if-clean branch above), ask for it once at load. Once per
	// user, project and step in this tab (the user is already in the storage key), so a step the server cannot build can never loop.
	(function () {
		var target = panel(current);
		if (!target || target.hidden || !target.querySelector("[data-mdmf-deferred-step]")) return;
		var key = "deferredAsked:" + val("mdmfProjectId") + ":" + current;
		if (ST.get(key) === "1") return;
		ST.set(key, "1");
		loadDeferredStep(current);
	})();

	// deduced-scan lot (2026-09-24): DEDUCED_SCAN is now a job of the queue (lot C-deduced) - its own 5-second
	// self-poll is gone, the generic jobs banner below follows it like every other job kind. The server-side
	// DEDUCED_SCAN_POLL action stays a harmless no-op (nothing here posts it any more).

	// ---- landing live panel (live-progress lot, 2026-09-26, docs/mockups/landing-live/): WizardPage.renderLandingRunning
	// writes the panel once; the jobs poll below hands this block the running landing job every 3 s (its
	// progressJson is the LandingRunProgress grid, written by the run at every change), and a one-second tick
	// keeps the clocks moving in between. estimate() and cellHtml() mirror LandingRunProgress.estimate and
	// WizardPage.renderLandingCellBody: change them together. Server times are epoch ms; the offset measured at
	// render keeps the timers right when the browser's clock is not the server's.
	var landingLive = (function () {
		var box = byId("mdmfLandingLive");
		if (!box) return null;
		function json(attr) { try { return JSON.parse(box.getAttribute(attr) || "{}"); } catch (ignored) { return {}; } }
		var L = json("data-mdmf-lr-labels");
		var typical = json("data-mdmf-lr-typical");
		var offset = (parseInt(box.getAttribute("data-mdmf-lr-server-now"), 10) || Date.now()) - Date.now();
		var live = box.getAttribute("data-mdmf-lr-live") === "true";
		var startedAt = parseInt(box.getAttribute("data-mdmf-lr-started"), 10) || 0;
		var updatedAt = parseInt(box.getAttribute("data-mdmf-lr-updated"), 10) || 0;
		var run = null;
		var STAGES = ["KEY", "TABLE", "FLOW", "LOAD"];
		var STALE_MS = 180000;
		function now() { return Date.now() + offset; }
		function fmt(p, a, b) { return String(p || "").replace("{0}", String(a)).replace("{1}", String(b)); }
		function esc(s) { var d = document.createElement("div"); d.textContent = s == null ? "" : String(s); return d.innerHTML; }
		function dur(ms) {
			var s = Math.floor(Math.max(0, ms) / 1000);
			if (s < 60) return s + " s";
			var m = Math.floor(s / 60), r = s % 60;
			return m >= 10 || r === 0 ? m + " min" : m + " min " + (r < 10 ? "0" : "") + r;
		}
		function clock(ms) {
			var s = Math.floor(Math.max(0, ms) / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = s % 60;
			return (h ? h + ":" : "") + (h && m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
		}
		function leftText(ms) {
			if (ms == null) return L["live.leftUnknown"] || "";
			if (ms < 30000) return L["live.leftSoon"] || "";
			return fmt(L["live.left"], dur(ms < 60000 ? ms : Math.ceil(ms / 60000) * 60000));
		}
		function unitOf(t, st) { return st === "TABLE" || st === "LOAD" ? "S:" + t.sourceSystem : "T:" + t.id; }
		function cellOf(t, st) { return (t.cells && t.cells[st]) || { state: "TODO" }; }
		function estimate(p, at) {
			var state = {}, start = {}, order = [];
			var rank = { RUNNING: 3, TODO: 2, WAITING: 1, DONE: 0 };
			(p.tables || []).forEach(function (t) {
				STAGES.forEach(function (st) {
					var c = cellOf(t, st), u = st + "|" + unitOf(t, st);
					var s = c.state === "RUNNING" || c.state === "TODO" || c.state === "WAITING" ? c.state : "DONE";
					if (!(u in state)) order.push(u);
					if (!(u in state) || rank[s] > rank[state[u]]) state[u] = s;
					if (c.state === "RUNNING" && c.startedAt) start[u] = c.startedAt;
				});
			});
			if (!order.length) return { percent: 0, left: null };
			var done = 0, left = 0, known = true;
			order.forEach(function (u) {
				var st = u.substring(0, u.indexOf("|")), ty = typical[st], s = state[u];
				if (s === "DONE") done += 1;
				else if (s === "RUNNING") {
					var el = start[u] ? Math.max(0, at - start[u]) : 0;
					if (!ty) { known = false; done += 0.3; } else { done += Math.min(0.9, el / ty); left += Math.max(0, ty - el); }
				} else if (s === "TODO") { if (ty == null) known = false; else left += ty; }
			});
			return { percent: Math.max(0, Math.min(100, Math.round(done * 100 / order.length))), left: known ? left : null };
		}
		function circle(state, passive) {
			var glyph = state === "DONE" ? "✓" : passive ? "–" : state === "FAILED" || state === "WAITING" ? "!" : state === "RUNNING" ? "…" : state === "SKIPPED" ? "–" : "";
			return '<span class="mdmf-landing-circle mdmf-landing-circle-' + (passive ? "waiting-passive" : state.toLowerCase()) + '">' + glyph + "</span>";
		}
		function cellHtml(st, c, at) {
			var passive = c.state === "WAITING" && st !== "KEY";
			var label = c.state === "RUNNING" && c.activity ? c.activity
				: c.state === "TODO" ? L["live.after." + st] : L[passive ? "state.WAITING.stage" : "state." + c.state];
			var out = circle(c.state, passive) + '<span class="mdmf-lr-label">' + esc(label) + "</span>";
			if (c.state === "RUNNING") {
				return out + '<br/><span class="mdmf-hint mdmf-lr-timer" data-mdmf-lr-since="' + (c.startedAt || 0) + '">'
					+ esc(fmt(L["live.running"], dur(c.startedAt ? at - c.startedAt : 0)) + (typical[st] ? " · " + fmt(L["live.usually"], dur(typical[st])) : "")) + "</span>";
			}
			var took = c.startedAt && c.endedAt ? fmt(L["live.took"], dur(c.endedAt - c.startedAt)) : null;
			var line = c.summary ? (took ? c.summary + " · " + took : c.summary) : took;
			return line ? out + '<br/><span class="mdmf-hint">' + esc(line) + "</span>" : out;
		}
		function current(p) {
			var best = null, bestTable = null;
			(p.tables || []).forEach(function (t) {
				STAGES.forEach(function (st) { var c = cellOf(t, st); if (c.state === "RUNNING" && (!best || (c.startedAt || 0) > (best.startedAt || 0))) { best = c; bestTable = t; } });
			});
			return best ? bestTable.tableName + " · " + (best.activity || L["state.RUNNING"] || "") : L["live.nowIdle"];
		}
		function paint(p) {
			var at = now();
			var nowEl = byId("mdmfLrNow");
			if (nowEl && nowEl.lastElementChild) nowEl.lastElementChild.textContent = current(p);
			var total = 0, settled = 0;
			(p.tables || []).forEach(function (t) {
				STAGES.forEach(function (st) {
					var c = cellOf(t, st);
					total++;
					if (c.state === "DONE" || c.state === "FAILED" || c.state === "SKIPPED") settled++;
					var td = box.querySelector('[data-mdmf-lr-cell="' + (window.CSS && CSS.escape ? CSS.escape(t.id + "|" + st) : t.id + "|" + st) + '"]');
					if (!td) return;
					var before = td.getAttribute("data-mdmf-lr-state");
					// a settled cell the server rendered keeps its markup (the KEY column's key chips)
					if (before === c.state && c.state !== "RUNNING") return;
					var passive = c.state === "WAITING" && st !== "KEY";
					td.className = "mdmf-landing-cell mdmf-landing-cell-" + (passive ? "skipped" : c.state.toLowerCase());
					td.setAttribute("data-mdmf-lr-state", c.state);
					td.innerHTML = cellHtml(st, c, at);
				});
			});
			var stages = byId("mdmfLrStages");
			if (stages) stages.textContent = fmt(L.progressText, settled, total);
			var list = byId("mdmfLrJournal");
			if (list && p.events) {
				var html = "";
				p.events.slice(0, 6).forEach(function (e, i) {
					html += "<li" + (i === 0 ? ' class="mdmf-lr-journal-live"' : "") + "><time>" + esc(new Date(e.at - offset).toTimeString().slice(0, 8)) + "</time><span>" + esc(e.text) + "</span></li>";
				});
				list.innerHTML = html || '<li class="mdmf-lr-journal-empty">' + esc(L["live.journalEmpty"]) + "</li>";
			}
			tick();
		}
		function tick() {
			var at = now();
			var el = byId("mdmfLrElapsed");
			if (el && startedAt) el.textContent = clock(at - startedAt);
			box.querySelectorAll(".mdmf-lr-timer").forEach(function (span) {
				var since = parseInt(span.getAttribute("data-mdmf-lr-since"), 10) || 0;
				if (!since) return;
				var st = (span.closest("[data-mdmf-lr-cell]").getAttribute("data-mdmf-lr-cell") || "").split("|").pop();
				span.textContent = fmt(L["live.running"], dur(at - since)) + (typical[st] ? " · " + fmt(L["live.usually"], dur(typical[st])) : "");
			});
			if (run) {
				var e = estimate(run, at);
				var fill = byId("mdmfLrFill");
				if (fill) fill.style.width = e.percent + "%";
				var left = byId("mdmfLrLeft");
				if (left) left.textContent = leftText(e.left);
			}
			var beat = byId("mdmfLrBeat");
			if (beat && updatedAt) {
				var quiet = at - updatedAt;
				beat.className = "mdmf-lr-beat" + (quiet > STALE_MS ? " mdmf-lr-beat-stale" : "");
				beat.lastElementChild.textContent = fmt(quiet > STALE_MS ? L["live.beatStale"] : L["live.beat"], dur(quiet));
			}
		}
		function update(job) {
			if (!job || !job.progressJson) return;
			var p;
			try { p = JSON.parse(job.progressJson); } catch (ignored) { return; }
			if (!p || !p.tables) return;
			run = p;
			if (p.startedAt) startedAt = p.startedAt;
			if (p.updatedAt) updatedAt = p.updatedAt;
			paint(p);
		}
		if (live) window.setInterval(tick, 1000);
		return { update: update, isLanding: function (kind) { return kind === "LANDING_RUN" || kind === "LANDING_RUN_RESUME"; } };
	})();

	// ---- staging live panel (staging-live lot, 2026-09-27, docs/mockups/staging-live/): same pattern as the landing
	// live panel above, for step 4's "Fill the shared structure" run (job kind STAGING_LOAD_RUN). The progress JSON
	// is a RunProgress: stages (with their activity), units (business object x application, real rows after each
	// batch), events. cellHtml mirrors WizardPage.stagingCellBody, remainingMs mirrors RunProgress.remainingMs.
	// master-live lot (2026-09-27): one factory for steps 4 and 5 - the panel's data-mdmf-lr-prefix names its element
	// ids, data-mdmf-lr-stage-grid picks the grid (business object x application, or business object x the stages).
	function runPanel(boxId, kindName) {
		var box = byId(boxId);
		if (!box) return null;
		var P = box.getAttribute("data-mdmf-lr-prefix") || "";
		var stageGrid = box.getAttribute("data-mdmf-lr-stage-grid") === "true";
		var L = {};
		try { L = JSON.parse(box.getAttribute("data-mdmf-lr-labels") || "{}"); } catch (ignored) { /* raw keys */ }
		var offset = (parseInt(box.getAttribute("data-mdmf-lr-server-now"), 10) || Date.now()) - Date.now();
		var startedAt = parseInt(box.getAttribute("data-mdmf-lr-started"), 10) || 0;
		var updatedAt = parseInt(box.getAttribute("data-mdmf-lr-updated"), 10) || 0;
		var jobId = box.getAttribute("data-mdmf-job-id") || "";
		var run = null;
		var STALE_MS = 180000;
		function now() { return Date.now() + offset; }
		function fmt(p, a, b) { return String(p || "").replace("{0}", String(a)).replace("{1}", String(b)); }
		function esc(v) { var d = document.createElement("div"); d.textContent = v == null ? "" : String(v); return d.innerHTML; }
		function dur(ms) {
			var s = Math.floor(Math.max(0, ms) / 1000);
			if (s < 60) return s + " s";
			var m = Math.floor(s / 60), r = s % 60;
			return m >= 10 || r === 0 ? m + " min" : m + " min " + (r < 10 ? "0" : "") + r;
		}
		function clock(ms) {
			var s = Math.floor(Math.max(0, ms) / 1000), h = Math.floor(s / 3600), m = Math.floor(s / 60) % 60, sec = s % 60;
			return (h ? h + ":" : "") + (h && m < 10 ? "0" : "") + m + ":" + (sec < 10 ? "0" : "") + sec;
		}
		function rowsOf(p) {
			var read = 0, total = 0;
			(p.units || []).forEach(function (u) { if (u.total >= 0) { total += u.total; read += Math.min(u.read, u.total); } });
			return [read, total];
		}
		function remainingMs(p, at) {
			var read = 0, spent = 0;
			(p.units || []).forEach(function (u) { if (u.startedAt > 0 && u.total >= 0) { read += u.read; spent += Math.max(0, (u.endedAt > 0 ? u.endedAt : at) - u.startedAt); } });
			var of = rowsOf(p);
			if (read <= 0 || spent <= 0 || of[1] <= 0) return null;
			return (of[1] - of[0]) * (spent / read);
		}
		function leftText(ms) {
			if (ms == null) return L["live.leftUnknown"] || "";
			if (ms < 30000) return L["live.leftSoon"] || "";
			return fmt(L["live.left"], dur(ms < 60000 ? ms : Math.ceil(ms / 60000) * 60000));
		}
		function circle(state) {
			var glyph = state === "DONE" ? "✓" : state === "FAILED" ? "!" : state === "RUNNING" ? "…" : state === "SKIPPED" ? "–" : "";
			return '<span class="mdmf-landing-circle mdmf-landing-circle-' + String(state || "TODO").toLowerCase() + '">' + glyph + "</span>";
		}
		function cellHtml(u, at) {
			var title = u.state === "TODO" ? L["live.cell.next"]
				: u.state === "RUNNING" && u.total >= 0 ? fmt(L["live.cell.read"], u.read, u.total)
				: u.state === "FAILED" ? L["live.cell.failed"] : fmt(L["live.cell.rows"], u.total >= 0 ? u.read : u.written + (u.unchanged || 0));
			var out = circle(u.state) + '<span class="mdmf-lr-label">' + esc(title) + "</span>";
			if (u.state === "TODO") return out;
			var line = fmt(L["live.cell.written"], u.written);
			if (u.unchanged > 0) line += " · " + fmt(L["live.cell.unchanged"], u.unchanged);
			if (u.held > 0) line += " · " + fmt(L["live.cell.held"], u.held);
			if (u.startedAt > 0) line += " · " + fmt(u.state === "RUNNING" ? L["live.running"] : L["live.took"], dur((u.endedAt > 0 ? u.endedAt : at) - u.startedAt));
			out += '<br/><span class="mdmf-hint">' + esc(line) + "</span>";
			if (u.state === "RUNNING" && u.total > 0) out += '<span class="mdmf-sl-mini"><span style="width:' + Math.min(100, Math.floor(u.read * 100 / u.total)) + '%"></span></span>';
			return out;
		}
		function stageCellHtml(st, at) {
			var out = circle(st.state) + '<span class="mdmf-lr-label">' + esc(L["wizard.step3.run.state." + st.state] || st.state) + "</span>";
			if (st.startedAt) out += '<br/><span class="mdmf-hint">' + esc(fmt(st.state === "RUNNING" ? L["live.running"] : L["live.took"], dur((st.endedAt || at) - st.startedAt))) + "</span>";
			return out;
		}
		function paint(p) {
			var at = now();
			var nowText = L["live.nowIdle"] || "";
			(p.stages || []).forEach(function (st) { if (st.state === "RUNNING") nowText = st.activity || nowText; });
			var nowEl = byId(P + "Now");
			if (nowEl && nowEl.lastElementChild) nowEl.lastElementChild.textContent = nowText;
			var chips = byId(P + "Chips");
			if (chips) {
				var html = "";
				(p.stages || []).forEach(function (st, i) {
					var tone = st.state === "DONE" ? "mdmf-chip-real" : st.state === "RUNNING" ? "mdmf-sl-chip-run" : st.state === "FAILED" ? "mdmf-chip-warn" : "mdmf-chip-muted";
					var old = chips.children[i];
					var label = old ? old.getAttribute("data-label") || old.textContent.split(" · ")[1] : st.key;
					html += '<span class="mdmf-chip ' + tone + '" data-label="' + esc(label) + '">' + (i + 1) + " · " + esc(label)
						+ (st.startedAt && st.endedAt ? " · " + dur(st.endedAt - st.startedAt) : "") + "</span> ";
				});
				chips.innerHTML = html;
			}
			var entities = [], sources = [], byKey = {};
			(p.units || []).forEach(function (u) {
				if (entities.indexOf(u.row) < 0) entities.push(u.row);
				if (sources.indexOf(u.col) < 0) sources.push(u.col);
				byKey[u.row + "\u0000" + u.col] = u;
			});
			var wrap = byId(P + "GridWrap"), head = byId(P + "Head"), body = byId(P + "Body");
			if (stageGrid && wrap && body && (p.units || []).length) {
				// step 5: one row per business object (its unit), columns = the stages (mirrors WizardPage.renderRunStageGrid)
				wrap.style.display = "";
				body.innerHTML = (p.units || []).map(function (u) {
					return "<tr><td><b>" + esc(u.row) + "</b></td>" + (p.stages || []).map(function (st) {
						return u.col === st.key
							? '<td class="mdmf-landing-cell mdmf-landing-cell-' + String(u.state).toLowerCase() + '">' + cellHtml(u, at) + "</td>"
							: '<td class="mdmf-landing-cell mdmf-landing-cell-' + String(st.state).toLowerCase() + '">' + stageCellHtml(st, at) + "</td>";
					}).join("") + "</tr>";
				}).join("");
			}
			else if (!stageGrid && wrap && head && body && entities.length) {
				wrap.style.display = "";
				var first = head.firstElementChild ? head.firstElementChild.outerHTML : "<th></th>";
				var last = head.lastElementChild ? head.lastElementChild.outerHTML : "<th></th>";
				head.innerHTML = first + sources.map(function (c) { return "<th>" + esc(c) + "</th>"; }).join("") + last;
				body.innerHTML = entities.map(function (e) {
					var written = 0;
					var cells = sources.map(function (c) {
						var u = byKey[e + "\u0000" + c];
						if (!u) return "<td></td>";
						written += u.written;
						return '<td class="mdmf-landing-cell mdmf-landing-cell-' + String(u.state).toLowerCase() + '">' + cellHtml(u, at) + "</td>";
					}).join("");
					return "<tr><td><b>" + esc(e) + "</b></td>" + cells + "<td><b>" + written + "</b></td></tr>";
				}).join("");
			}
			var list = byId(P + "Journal");
			if (list && p.events) {
				list.innerHTML = p.events.slice(0, 6).map(function (ev, i) {
					return "<li" + (i === 0 ? ' class="mdmf-lr-journal-live"' : "") + "><time>" + esc(new Date(ev.at - offset).toTimeString().slice(0, 8)) + "</time><span>" + esc(ev.text) + "</span></li>";
				}).join("") || '<li class="mdmf-lr-journal-empty">' + esc(L["live.journalEmpty"]) + "</li>";
			}
			tick();
		}
		function tick() {
			var at = now();
			var el = byId(P + "Elapsed");
			if (el && startedAt) el.textContent = clock(at - startedAt);
			if (run) {
				var of = rowsOf(run);
				var fill = byId(P + "Fill");
				if (fill && of[1] > 0) fill.style.width = Math.min(100, Math.floor(of[0] * 100 / of[1])) + "%";
				var count = byId(P + "Count");
				if (count && of[1] > 0) count.textContent = fmt(L["live.rowsOf"], of[0], of[1]);
				var left = byId(P + "Left");
				if (left) left.textContent = leftText(remainingMs(run, at));
			}
			var beat = byId(P + "Beat");
			if (beat && updatedAt) {
				var quiet = at - updatedAt;
				beat.className = "mdmf-lr-beat" + (quiet > STALE_MS ? " mdmf-lr-beat-stale" : "");
				beat.lastElementChild.textContent = fmt(quiet > STALE_MS ? L["live.beatStale"] : L["live.beat"], dur(quiet));
			}
		}
		var stop = byId(P + "Stop");
		if (stop) {
			stop.addEventListener("click", function (e) {
				e.preventDefault();
				var jobs = byId("mdmfJobs");
				var base = jobs ? jobs.getAttribute("data-mdmf-jobs-cancel") : null;
				if (!base || !jobId) return;
				stop.disabled = true;
				fetch(base + encodeURIComponent(jobId) + "/cancel", { method: "POST", credentials: "same-origin", cache: "no-store",
					headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" } })
					.then(function () { stop.textContent = L["live.stopping"] || stop.textContent; }, function () { stop.disabled = false; });
			});
		}
		window.setInterval(tick, 1000);
		return {
			isKind: function (kind) { return kind === kindName; },
			update: function (job) {
				if (!job || !job.progressJson) return;
				var p;
				try { p = JSON.parse(job.progressJson); } catch (ignored) { return; }
				if (!p || !p.stages) return;
				run = p;
				if (job.jobId) jobId = job.jobId;
				if (p.startedAt) startedAt = p.startedAt;
				if (p.updatedAt) updatedAt = p.updatedAt;
				paint(p);
			}
		};
	}
	var stagingLive = runPanel("mdmfStagingLive", "STAGING_LOAD_RUN");
	var masterLive = runPanel("mdmfMasterLive", "MASTER_BUILD_RUN");

	// ---- proposal live list (UX lot U3, 2026-10-13): step 4's six-pass list while "Propose the staging" runs. Since lot C2 the page is only
	// re-rendered when the job ends, so the list used to stay on its first pass ("running for 0 min", the other five WAITING) for the whole run,
	// although the job's progress JSON (the ProposalProgress, jobs route -> progressJson) was current. Every poll of the jobs banner calls
	// update(): each pass's state, elapsed time and summary, the "Now: ..." line, "N of 6 passes done" and the bar are repainted in place. The words
	// are data-mdmf-pl-* attributes written by WizardPage.renderProposalRunning (mirror it: it renders the same list on the first paint). Elapsed
	// times use the server's clock (serverTime of the jobs answer) so a skewed browser clock does not show "-3 s". The banner re-renders the page
	// once at the job's end, which brings the "Proposal ready for review" block.
	var proposalLive = (function () {
		var box = byId("mdmfProposalLive");
		if (!box) return null;
		var run = null, offset = 0;
		function now() { return Date.now() + offset; }
		function dur(ms) {
			var s = Math.max(0, Math.floor(ms / 1000));
			if (s < 60) return s + " s";
			var m = Math.floor(s / 60);
			return m < 60 ? m + " min " + (s % 60) + " s" : Math.floor(m / 60) + " h " + (m % 60) + " min";
		}
		function word(name) { return box.getAttribute("data-mdmf-pl-" + name) || ""; }
		function paint() {
			if (!run || !run.stages) return;
			var at = now(), done = 0, running = null, total = run.stages.length;
			run.stages.forEach(function (st) {
				var li = box.querySelector('li[data-key="' + st.key + '"]');
				if (!li) return;
				var state = st.state || "TODO";
				if (state === "DONE" || state === "FAILED" || state === "SKIPPED") done++;
				var label = li.querySelector(".mdmf-proposal-stage-label");
				if (state === "RUNNING" && !running && label) running = label.textContent;
				li.className = "mdmf-proposal-stage mdmf-proposal-stage-" + state.toLowerCase();
				var stateEl = li.querySelector(".mdmf-proposal-stage-state");
				if (stateEl) stateEl.textContent = word("state-" + state.toLowerCase()) || state;
				var time = li.querySelector(".mdmf-proposal-stage-time");
				if (time) {
					time.textContent = st.startedAt && st.endedAt ? "(" + dur(st.endedAt - st.startedAt) + ")"
						: state === "RUNNING" && st.startedAt ? word("running-for").replace("{0}", dur(at - st.startedAt)) : "";
				}
				var sum = li.querySelector(".mdmf-proposal-stage-summary");
				if (st.summary) {
					if (!sum) { sum = document.createElement("div"); sum.className = "mdmf-hint mdmf-proposal-stage-summary"; li.appendChild(sum); }
					sum.textContent = st.summary;
				} else if (sum) { sum.parentNode.removeChild(sum); }
			});
			var nowEl = byId("mdmfProposalNow");
			if (nowEl) nowEl.textContent = running ? word("now").replace("{0}", running) : word("waiting");
			var count = byId("mdmfProposalCount");
			if (count) count.textContent = word("count").replace("{0}", String(done)).replace("{1}", String(total));
			var fill = box.querySelector(".mdmf-proposal-bar-fill");
			if (fill && total) fill.style.width = Math.floor(done * 100 / total) + "%";
		}
		window.setInterval(paint, 1000);
		return {
			isKind: function (kind) { return kind === "STAGING_PROPOSAL"; },
			update: function (job, serverTime) {
				if (!job || !job.progressJson) return;
				var p;
				try { p = JSON.parse(job.progressJson); } catch (ignored) { return; }
				if (!p || !p.stages) return;
				if (serverTime) offset = serverTime - Date.now();
				run = p;
				paint();
			}
		};
	})();

	// ---- matching live panel (matching-live lot, 2026-09-27): step 6's last matching action (Run or Configure), right
	// under the buttons. While the action is queued or running it polls GET .../matching/activity every 2 s and
	// repaints the title and one line per entity (statusHtml mirrors WizardPage.matchingEntityStatus); the page is
	// re-rendered once at the job's end by the jobs banner below (the render marks it active), which brings the
	// per-entity results.
	var matchingLive = (function () {
		var box = byId("mdmfMatchingLive");
		if (!box) return null;
		var L = {};
		try { L = JSON.parse(box.getAttribute("data-mdmf-lr-labels") || "{}"); } catch (ignored) { /* raw keys */ }
		var url = box.getAttribute("data-mdmf-ma-url");
		var offset = (parseInt(box.getAttribute("data-mdmf-lr-server-now"), 10) || Date.now()) - Date.now();
		var live = box.getAttribute("data-mdmf-ma-live") === "true";
		function now() { return Date.now() + offset; }
		function esc(v) { var d = document.createElement("div"); d.textContent = v == null ? "" : String(v); return d.innerHTML; }
		function dur(ms) {
			var s = Math.floor(Math.max(0, ms) / 1000);
			if (s < 60) return s + " s";
			var m = Math.floor(s / 60), r = s % 60;
			return m >= 10 || r === 0 ? m + " min" : m + " min " + (r < 10 ? "0" : "") + r;
		}
		function circle(state) {
			var st = state === "STOPPED" ? "SKIPPED" : state;
			var glyph = st === "DONE" ? "✓" : st === "FAILED" ? "!" : st === "RUNNING" ? "…" : st === "SKIPPED" ? "–" : "";
			return '<span class="mdmf-landing-circle mdmf-landing-circle-' + String(st || "TODO").toLowerCase() + '">' + glyph + "</span>";
		}
		function statusHtml(e, at) {
			if (!e) return circle("TODO") + '<span class="mdmf-lr-label">' + esc(L["live.inLine"]) + "</span>";
			var running = e.status === "RUNNING";
			var out = circle(e.status) + '<span class="mdmf-lr-label">' + esc(L["live.state." + e.status] || e.status) + "</span>";
			if (e.startedAt > 0) {
				out += ' <span class="mdmf-hint mdmf-ma-timer"' + (running ? ' data-mdmf-lr-since="' + e.startedAt + '"' : "") + ">"
					+ esc(String((running ? L["live.running"] : L["live.took"]) || "").replace("{0}", dur((e.endedAt > 0 ? e.endedAt : at) - e.startedAt))) + "</span>";
			}
			// UX lot U1 (mirrors WizardPage.matchingEntityStatus): a long step message - the engine's log text, the volume warning - sits under "Details", never in red in the cell
			if (e.message && !running) {
				if (e.message.length > 160 || e.status === "FAILED") {
					out += '<details class="mdmf-notice-details mdmf-ma-details"><summary>' + esc(L["live.details"] || "Details") + '</summary><div class="mdmf-hint">' + esc(e.message) + "</div></details>";
				} else {
					out += '<div class="mdmf-hint">' + esc(e.message) + "</div>";
				}
			}
			return out;
		}
		function paint(a) {
			var at = now();
			var title = byId("mdmfMaTitle");
			if (title && (a.status === "QUEUED" || a.status === "RUNNING")) title.textContent = L["live.title." + a.kind + (a.status === "QUEUED" ? ".QUEUED" : ".live")] || title.textContent;
			var body = byId("mdmfMaBody");
			if (!body) return;
			(a.entities || []).forEach(function (e) {
				var row = body.querySelector('tr[data-mdmf-ma-entity="' + (window.CSS && CSS.escape ? CSS.escape(e.name) : e.name) + '"]');
				if (!row) {
					row = document.createElement("tr");
					row.setAttribute("data-mdmf-ma-entity", e.name);
					row.innerHTML = "<td><b>" + esc(e.name) + '</b></td><td class="mdmf-ma-status"></td><td></td>';
					body.appendChild(row);
				}
				var cell = row.querySelector(".mdmf-ma-status");
				if (cell) cell.innerHTML = statusHtml(e, at);
			});
		}
		function tick() {
			var at = now();
			box.querySelectorAll(".mdmf-ma-timer[data-mdmf-lr-since]").forEach(function (span) {
				var since = parseInt(span.getAttribute("data-mdmf-lr-since"), 10) || 0;
				if (since) span.textContent = String(L["live.running"] || "").replace("{0}", dur(at - since));
			});
		}
		function poll() {
			if (!live || !url) return;
			fetch(url, { credentials: "same-origin", cache: "no-store", headers: { "Accept": "application/json" } })
				.then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
				.then(function (a) {
					paint(a);
					if (a.status === "QUEUED" || a.status === "RUNNING") window.setTimeout(poll, 2000);
					else live = false; // the jobs banner re-renders the page with the results
				})
				.catch(function () { window.setTimeout(poll, 5000); });
		}
		if (live) { window.setInterval(tick, 1000); poll(); }
		return { isKind: function (kind) { return kind === "MATCHING_RUN" || kind === "MATCHING_CONFIGURE"; } };
	})();

	// ---- jobs banner (lot C2, 2026-09-24): replaces the five 5-second full-page self-polls (STAGING_PROPOSAL_POLL,
	// SOURCES_RUN_POLL, LANDING_RUN_POLL, STAGING_LOAD_RUN_POLL, MASTER_BUILD_RUN_POLL) that each resubmitted the
	// whole form while a run was in progress. The banner (WizardPage.renderJobsBanner) reads the project's jobs from
	// the module's REST route every 3 s while something is active (30 s otherwise), shows each one with its place in
	// line or its current stage, and offers Stop (running) or Cancel (queued). The page is re-rendered ONCE, when a
	// job it saw active ends - and never while the person is typing or choosing a key: then the banner offers a
	// "Refresh" button instead. If the route cannot be read (a non-administrator, an expired session), a page that
	// was rendered with a run in progress falls back to the old full re-render every 10 s.
	(function () {
		var box = byId("mdmfJobs");
		if (!box) return;
		var L = (typeof mdmfJobLabels !== "undefined") ? mdmfJobLabels : {};
		var kinds = L.kinds || {};
		var url = box.getAttribute("data-mdmf-jobs-url");
		var cancelBase = box.getAttribute("data-mdmf-jobs-cancel");
		var renderedActive = box.getAttribute("data-mdmf-jobs-active") === "true" && box.getAttribute("data-mdmf-jobs-paused") !== "true";
		var seen = null; // job ids active at the previous poll
		var reloading = false;
		// audit F-43: the end of a job is no longer remembered in this tab's sessionStorage - the page re-renders once when a job it saw
		// active ends, and the server-rendered "Recent tasks" list (RecentTasks, read from FactoryJob) shows how every task of the project
		// ended, from any tab, after a restart, the next day.
		function fmt(pattern, arg) { return String(pattern || "").replace("{0}", String(arg)); }
		function editing() {
			if (root.querySelector(".mdmf-key-chooser")) return true;
			var a = document.activeElement;
			return !!(a && root.contains(a) && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName) && a.type !== "button" && a.type !== "submit");
		}
		function rerender() {
			if (reloading) return;
			reloading = true;
			try { ST.set("scrollTop", String(scrollableAncestor().scrollTop)); } catch (ignored) { /* no restore */ }
			set("mdmfAction", "JOBS_POLL");
			set("mdmfStep", String(current));
			var submit = byId("mdmfWizSubmit");
			if (submit) submit.click();
		}
		function button(label, danger, onClick) {
			var b = document.createElement("button");
			b.type = "button";
			b.className = "mdmf-btn mdmf-btn-sm" + (danger ? " mdmf-btn-danger" : "");
			b.textContent = label;
			b.addEventListener("click", function (e) { e.preventDefault(); e.stopPropagation(); b.disabled = true; onClick(); });
			return b;
		}
		function cancelJob(jobId) {
			fetch(cancelBase + encodeURIComponent(jobId) + "/cancel", {
				method: "POST", credentials: "same-origin", cache: "no-store",
				headers: { "Accept": "application/json", "X-Requested-With": "XMLHttpRequest" }
			}).then(function () { poll(); }, function () { poll(); });
		}
		function render(data, ended) {
			box.textContent = "";
			var active = (data && data.active) || [];
			var shownActive = active.filter(function (job) { return !(landingLive && landingLive.isLanding(job.kind)) && !(stagingLive && stagingLive.isKind(job.kind)) && !(masterLive && masterLive.isKind(job.kind)) && !(matchingLive && matchingLive.isKind(job.kind)); });
			if (!shownActive.length && !ended) { box.style.display = "none"; return; }
			box.style.display = "";
			active.forEach(function (job) {
				// live-progress lot: the landing panel is the landing job's own status line, with its own Stop
				if (landingLive && landingLive.isLanding(job.kind)) return;
				if (stagingLive && stagingLive.isKind(job.kind)) return;
				if (masterLive && masterLive.isKind(job.kind)) return;
				if (matchingLive && matchingLive.isKind(job.kind)) return;
				var row = document.createElement("div");
				row.className = "mdmf-jobs-row mdmf-jobs-" + String(job.status || "").toLowerCase();
				var title = document.createElement("strong");
				title.textContent = kinds[job.kind] || job.kind;
				row.appendChild(title);
				var state = document.createElement("span");
				state.className = "mdmf-jobs-state";
				if (job.status === "QUEUED") state.textContent = fmt(L.queued, job.position || 1);
				else if (job.stopRequested) state.textContent = L.stopRequested || "";
				else state.textContent = fmt(L.running, job.startedAt ? Math.max(0, Math.floor((Date.now() - job.startedAt) / 60000)) : 0);
				row.appendChild(state);
				if (job.status === "RUNNING" && job.stepMessage && !job.stopRequested) {
					var msg = document.createElement("span");
					msg.className = "mdmf-jobs-message";
					msg.textContent = job.stepMessage;
					row.appendChild(msg);
				}
				if (!job.stopRequested) {
					row.appendChild(button(job.status === "QUEUED" ? L.cancel : L.stop, job.status !== "QUEUED", function () { cancelJob(job.jobId); }));
				}
				box.appendChild(row);
			});
			if (ended) {
				var done = document.createElement("div");
				done.className = "mdmf-jobs-row";
				if (editing()) done.appendChild(button(L.refresh, false, rerender));
				else { done.textContent = L.ended || ""; }
				box.appendChild(done);
			}
		}
		var fallback = false;
		function schedule(ms) { window.setTimeout(poll, ms); }
		function poll() {
			if (reloading || fallback) return;
			fetch(url, { credentials: "same-origin", cache: "no-store", headers: { "Accept": "application/json" } })
				.then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
				.then(function (data) {
					var ids = ((data && data.active) || []).map(function (j) { return j.jobId; });
					// first poll: the page was rendered "in progress" and nothing is active any more -> it ended in between
					var ended = seen === null ? (renderedActive && ids.length === 0) : seen.some(function (id) { return ids.indexOf(id) < 0; });
					seen = ids;
					render(data, ended);
					if (landingLive) {
						((data && data.active) || []).forEach(function (job) { if (landingLive.isLanding(job.kind) && job.status === "RUNNING") landingLive.update(job); });
					}
					if (stagingLive) {
						((data && data.active) || []).forEach(function (job) { if (stagingLive.isKind(job.kind) && job.status === "RUNNING") stagingLive.update(job); });
					}
					if (masterLive) {
						((data && data.active) || []).forEach(function (job) { if (masterLive.isKind(job.kind) && job.status === "RUNNING") masterLive.update(job); });
					}
					if (proposalLive) {
						((data && data.active) || []).forEach(function (job) { if (proposalLive.isKind(job.kind) && job.status === "RUNNING") proposalLive.update(job, data.serverTime); });
					}
					if (ended && !editing()) { rerender(); return; } // the new page's first poll announces what ended
					// end-of-task notice (wave 10): a task THIS person launched that has ended is announced once, from the same answer - no extra request
					if (window.mdmfJobNotice) window.mdmfJobNotice.check(data, { kinds: kinds });
					schedule(ids.length ? 3000 : 30000);
				})
				.catch(function () {
					if (!renderedActive) return; // nothing to follow on this page
					fallback = true;
					window.setTimeout(function () { if (!editing()) rerender(); else { fallback = false; schedule(10000); } }, 10000);
				});
		}
		poll();
	})();

	// ---- automatic intake live panel (automatic-intake wizard lot, 2026-10-05, docs/AUTOMATIC-INTAKE.md "The screens"): IntakeScreens.renderLive writes the whole
	// screen once, complete without this script; this block only REPAINTS it. Every interval it reads GET /ebx-mdm-factory/rest/v1/projects/{code}/intake (session cookie,
	// same as the jobs banner), repaints each application's card body and the "Recent chains" rows in place, and between two reads a one-second tick moves the "N s ago"
	// and the quiet-period bar. Every "ago" is computed against the status's serverTime (offset = serverTime - browser clock at the last read), never the browser clock
	// alone; clocks are shown in the SERVER's zone (data-mdmf-in-tz), like the first paint. cardBody() and chainRow() mirror IntakeScreens.cardBodyHtml / chainRowHtml:
	// change them together. When the state (ON / PAUSED), the engine, the application list changes, or a chain this page saw running has ended, the step is reloaded
	// ONCE (STEP_VIEW) so the server-rendered parts are right; polling stops when the element is gone, and while its step is not showing it only looks again later.
	(function () {
		var box = byId("mdmfIntakeLive");
		if (!box) return;
		var L = {};
		try { L = JSON.parse(box.getAttribute("data-mdmf-in-labels") || "{}"); } catch (ignoredLabels) { L = {}; }
		var url = box.getAttribute("data-mdmf-in-url");
		var interval = (parseInt(box.getAttribute("data-mdmf-in-interval"), 10) || 5) * 1000;
		var tz = parseInt(box.getAttribute("data-mdmf-in-tz"), 10) || 0;
		var offset = (parseInt(box.getAttribute("data-mdmf-in-server-now"), 10) || Date.now()) - Date.now();
		var renderedState = box.getAttribute("data-mdmf-in-state");
		var renderedEngine = box.getAttribute("data-mdmf-in-engine") === "true";
		var renderedSources = box.getAttribute("data-mdmf-in-sources") || "";
		var running = (box.getAttribute("data-mdmf-in-running") || "").split(",").filter(function (id) { return id; });
		var reloading = false;
		function now() { return Date.now() + offset; }
		function esc(v) { var d = document.createElement("div"); d.textContent = v == null ? "" : String(v); return d.innerHTML; }
		function fmt(pattern, a, b) {
			return String(pattern || "").replace("{0}", function () { return String(a); }).replace("{1}", function () { return String(b); });
		}
		function pad2(n) { return (n < 10 ? "0" : "") + n; }
		function dur(sec) {
			var s = Math.max(0, Math.floor(sec));
			if (s < 60) return s + " s";
			var m = Math.floor(s / 60), r = s % 60;
			if (m < 60) return r === 0 ? m + " min" : m + " min " + r + " s";
			var h = Math.floor(m / 60), mm = m % 60;
			return mm === 0 ? h + " h" : h + " h " + pad2(mm) + " min";
		}
		function qdur(sec) { return sec < 120 ? sec + " s" : dur(sec); }
		function clock(ms) { var d = new Date(ms + tz); return pad2(d.getUTCHours()) + ":" + pad2(d.getUTCMinutes()); }
		function dateTime(ms) {
			if (ms == null || isNaN(ms)) return L.dash;
			var d = new Date(ms + tz);
			return d.getUTCDate() + " " + ((L.months || [])[d.getUTCMonth()] || "") + " " + clock(ms);
		}
		function ms(iso) { return iso ? Date.parse(iso) : -1; }
		function chipClass(state) {
			return state === "RECEIVING" ? "mdmf-chip-accent" : state === "DUE" ? "mdmf-chip-warn" : state === "RUNNING" ? "mdmf-chip-real" : "mdmf-chip-muted";
		}
		function pct(last, quiet) {
			if (last < 0 || quiet <= 0) return 0;
			return Math.floor(Math.min(100, Math.max(0, now() - last) * 100 / (quiet * 1000)));
		}
		function cardBody(src) {
			var state = src.state || "IDLE";
			var last = ms(src.lastRowAt), latest = ms(src.latestAt);
			var quiet = (src.settings && src.settings.quietSeconds) || 0;
			var waiting = state === "RECEIVING" || state === "DUE";
			var h = '<div class="mdmf-intake-body" data-in-body data-in-last="' + last + '" data-in-quiet="' + quiet + '" data-in-line-state="' + esc(state) + '">';
			h += '<div class="mdmf-intake-count">';
			if (src.rowsWaiting != null) h += '<b class="mdmf-intake-n">' + esc(src.rowsWaiting) + '</b> <span>' + esc(L.rowsWaiting) + '</span>';
			else if (state !== "IDLE") h += '<span class="mdmf-intake-arriving">' + esc(L.rowsArriving) + '</span>';
			else h += '<span class="mdmf-intake-arriving">' + esc(L.nothing) + '</span>';
			h += '</div><div class="mdmf-intake-line"><span class="mdmf-intake-lastline">';
			if (waiting && last >= 0) h += esc(fmt(L.lastLine, dur(Math.max(0, now() - last) / 1000), qdur(quiet)));
			else if (state === "RUNNING") h += esc(L.running);
			else if (state === "DUE") h += esc(L.due);
			h += '</span><span class="mdmf-intake-latest">';
			if (waiting && latest >= 0) h += esc(fmt(L.atLatest, clock(latest)));
			h += '</span></div>';
			if (waiting) h += '<div class="mdmf-intake-bar"><i style="width:' + (state === "DUE" ? 100 : pct(last, quiet)) + '%"></i></div>';
			if (src.reason && state !== "IDLE") h += '<p class="mdmf-hint mdmf-intake-reason">' + esc(fmt(L.why, (L.reason || {})[src.reason] || src.reason)) + '</p>';
			return h + '</div>';
		}
		function rowsText(c) {
			var parts = [];
			if (c.rowsWaiting != null) parts.push(fmt(L.rowsNew, c.rowsWaiting));
			if (c.written != null) {
				parts.push(fmt(L.rowsWritten, c.written));
				if (c.unchanged != null) parts.push(fmt(L.rowsUnchanged, c.unchanged));
			}
			return parts.length ? parts.join(" · ") : L.dash;
		}
		function resultText(c) {
			var st = c.status || "RUNNING";
			var res = L.result || {};
			if (st === "DONE_WITH_REJECTS") return fmt(res.DONE_WITH_REJECTS, c.rejected == null ? "" : c.rejected);
			var ended = st === "STOPPED" || st === "FAILED";
			if (ended && c.message) return fmt(res[st], c.message);
			return res[ended ? st + ".plain" : st] || st;
		}
		function resultClass(st) {
			return st === "DONE" ? "mdmf-chip-real" : st === "DONE_WITH_REJECTS" ? "mdmf-chip-warn" : (st === "STOPPED" || st === "FAILED") ? "mdmf-chip-bad" : "mdmf-chip-accent";
		}
		function chainRow(c) {
			var started = ms(c.startedAt), ended = ms(c.endedAt);
			var took = started >= 0 && ended >= started ? dur((ended - started) / 1000) : L.dash;
			return '<tr data-in-chain="' + esc(c.id) + '"><td>' + esc(started >= 0 ? dateTime(started) : L.dash) + '</td><td>' + esc((c.sources || []).join(", ") || L.dash)
				+ '</td><td>' + esc(rowsText(c)) + '</td><td class="num">' + esc(c.rejected == null ? L.dash : c.rejected) + '</td><td>' + esc(took)
				+ '</td><td><span class="mdmf-chip ' + resultClass(c.status) + '">' + esc(resultText(c)) + '</span></td></tr>';
		}
		function editing() {
			var settings = byId("mdmfIntakeSettings");
			var a = document.activeElement;
			return !!(settings && a && settings.contains(a) && /^(INPUT|TEXTAREA|SELECT)$/.test(a.tagName));
		}
		function hint(failed) {
			var e = box.querySelector("[data-in-hint]");
			if (e) e.hidden = !failed;
		}
		function reloadStep() {
			if (reloading) return;
			reloading = true;
			try { ST.set("scrollTop", String(scrollableAncestor().scrollTop)); } catch (ignoredScroll) { /* no restore */ }
			set("mdmfAction", "STEP_VIEW");
			set("mdmfStep", String(current));
			var submit = byId("mdmfWizSubmit");
			if (submit) submit.click();
		}
		function paint(data) {
			(data.sources || []).forEach(function (src) {
				var card = box.querySelector('[data-in-source="' + String(src.sourceSystem).replace(/"/g, '\\"') + '"]');
				if (!card) return;
				var chip = card.querySelector("[data-in-chip]");
				if (chip) {
					chip.className = "mdmf-chip " + chipClass(src.state);
					chip.textContent = (L.state || {})[src.state] || src.state;
				}
				var body = card.querySelector("[data-in-body]");
				if (body) {
					var holder = document.createElement("div");
					holder.innerHTML = cardBody(src);
					body.parentNode.replaceChild(holder.firstChild, body);
				}
			});
			var tbody = box.querySelector("[data-in-chains]");
			if (tbody) tbody.innerHTML = (data.chains || []).map(chainRow).join("");
			var empty = box.querySelector("[data-in-chains-empty]");
			if (empty) empty.hidden = (data.chains || []).length > 0;
		}
		function apply(data) {
			var codes = (data.sources || []).map(function (src) { return src.sourceSystem; }).join(",");
			var chains = data.chains || [];
			var ended = running.some(function (id) {
				return !chains.some(function (c) { return c.id === id && c.status === "RUNNING"; });
			});
			chains.forEach(function (c) { if (c.status === "RUNNING" && running.indexOf(c.id) < 0) running.push(c.id); });
			var changed = data.state !== renderedState || !!data.engineRunning !== renderedEngine || codes !== renderedSources || ended;
			if (changed && !editing()) { reloadStep(); return; }
			paint(data);
		}
		function schedule() { window.setTimeout(poll, interval); }
		function poll() {
			if (!document.body.contains(box)) return; // the screen is gone: stop
			if (reloading) return;
			if (box.offsetParent === null) { schedule(); return; } // its step is not showing: look again later
			fetch(url, { credentials: "same-origin", cache: "no-store", headers: { "Accept": "application/json" } })
				.then(function (r) { if (!r.ok) throw new Error("HTTP " + r.status); return r.json(); })
				.then(function (data) {
					var at = Date.parse(data.serverTime);
					if (!isNaN(at)) offset = at - Date.now();
					hint(false);
					apply(data);
					schedule();
				})
				.catch(function () { hint(true); schedule(); });
		}
		function tick() {
			box.querySelectorAll("[data-in-body]").forEach(function (body) {
				var state = body.getAttribute("data-in-line-state");
				var last = parseInt(body.getAttribute("data-in-last"), 10);
				var quiet = parseInt(body.getAttribute("data-in-quiet"), 10) || 0;
				if ((state !== "RECEIVING" && state !== "DUE") || isNaN(last) || last < 0) return;
				var line = body.querySelector(".mdmf-intake-lastline");
				if (line) line.textContent = fmt(L.lastLine, dur(Math.max(0, now() - last) / 1000), qdur(quiet));
				var bar = body.querySelector(".mdmf-intake-bar i");
				if (bar && state === "RECEIVING") bar.style.width = pct(last, quiet) + "%";
			});
		}
		var timer = window.setInterval(function () {
			if (!document.body.contains(box)) { window.clearInterval(timer); return; }
			tick();
		}, 1000);
		schedule();
	})();

	// scroll-anchor lot (2026-09-23): consume-once, so a normal fresh visit (bookmark, link, manual reload)
	// never gets pulled away from the top - only a reload WE just triggered above left these keys behind.
	// Applied twice, per the task brief: once right here (right after showStep made the right panel visible)
	// and again on a requestAnimationFrame tick (falling back to a zero-delay timeout), so the SECOND
	// application uses final, settled layout (images/fonts/the browser's own reflow) rather than whatever
	// showStep alone left mid-frame - a scrollIntoView computed too early can land short on a tall page.
	try {
		// wave 13: read then deleted at once (a hand-over from the page we just left), under this user's own keys
		var savedAnchorId = ST.take("scrollAnchor");
		var savedScrollTop = ST.take("scrollTop");
		var savedScrollBlock = ST.take("scrollBlock");
		if (savedAnchorId || savedScrollTop !== null) {
			// fix of 2026-10-16 (Remove on project Zefz): a refused action answers with a warning banner at the TOP of the page, and the
			// anchor below brought the person straight back to the card they clicked - the refusal was never seen ("nothing happens").
			// A warning of this render wins over the anchor.
			var warnNotice = byId("mdmfNotice");
			if (warnNotice && !warnNotice.classList.contains("mdmf-notice-warn")) warnNotice = null;
			var applyScroll = function () {
				if (warnNotice && warnNotice.scrollIntoView) {
					warnNotice.scrollIntoView({ block: "start" });
					return;
				}
				var target = savedAnchorId ? byId(savedAnchorId) : null;
				// the page is re-rendered with every <details> closed: an anchor inside one (a landing table row
				// under step 3's "Advanced") had no box, so scrollIntoView did nothing and the page stayed at the
				// top with the panel folded (2026-09-27). The click came from inside, so reopen its disclosures.
				for (var d = target ? target.parentElement : null; d; d = d.parentElement) {
					if (d.tagName === "DETAILS" && !d.open) d.open = true;
				}
				if (target && target.scrollIntoView) target.scrollIntoView({ block: savedScrollBlock === "start" ? "start" : "center" });
				else if (savedScrollTop !== null) scrollableAncestor().scrollTop = parseInt(savedScrollTop, 10) || 0;
			};
			applyScroll();
			if (window.requestAnimationFrame) window.requestAnimationFrame(applyScroll); else window.setTimeout(applyScroll, 0);
		}
	} catch (ignored) { /* private mode or disabled storage: page just stays at the top, same as before this lot */ }

	// sources+landing redesign, lot LANDING, coordinator follow-up (2026-09-24,
	// docs/mockups/sources-landing/LandingReview.dc.html): the key chooser's live, client-side composite-key
	// check - WizardPage.renderLandingKeyChooser embeds the sample as JSON (LandingKeyChooserJson.sample,
	// which is exactly landing.KeyEvidence.sampleJson - one object per row, keyed by column name) and this
	// block mirrors landing.KeyEvidence#combination's OWN counting rules (see that method's Javadoc): for the
	// columns currently checked, a row is "all empty" when EVERY one of them is empty, "partly empty" when
	// SOME but not all are, and the joined value of every row (in column order) is what "distinct together"
	// counts. This is an INDEPENDENT reimplementation, never compared back against a server-computed value -
	// the eventual write (LANDING_KEY_APPLY) re-measures nothing client-side, it just writes whatever columns
	// are checked when "Use ... and continue" is clicked.
	//
	// There is exactly ONE real checkbox per column (the "Several columns together" list, class
	// .mdmf-key-check) - the candidate cards above it ([data-mdmf-key-select]) are plain buttons that MOVE the
	// checked state onto that one set rather than carrying their own, so a click on a card and a checkbox
	// toggle both funnel through the same "change" handling below.
	(function () {
		var panel = root.querySelector(".mdmf-key-chooser");
		if (!panel) return;
		var sampleScript = panel.querySelector(".mdmf-key-sample-json");
		var sampleRows = [];
		try { sampleRows = sampleScript ? JSON.parse(sampleScript.textContent) : []; } catch (ignoredSample) { sampleRows = []; }

		function i18n(name) { return panel.getAttribute("data-mdmf-i18n-" + name) || ""; }
		function fill(template, a, b) {
			var out = template.replace("{0}", String(a));
			if (b !== undefined) out = out.replace("{1}", String(b));
			return out;
		}
		function checkedColumns() {
			var out = [];
			panel.querySelectorAll(".mdmf-key-check:checked").forEach(function (c) { out.push(c.value); });
			return out;
		}
		// mirrors landing.KeyEvidence#combination exactly (see this block's own header comment) - a column
		// the sample does not carry a value for on a given row is treated as empty for that row, same as the
		// Java side's own out-of-range/blank handling.
		//
		// coordinator follow-up round 3 (2026-09-24): rowsUniqueComplete is the EXACT replacement for the
		// round-2 approximation ("distinctTogether minus a guessed bucket count") - a second pass, over
		// COMPLETE (no-empty-part) rows only, counts how many times each one's combined value occurs among
		// them; rowsUniqueComplete is the count of complete rows whose combined value occurs exactly once.
		// Byte-for-byte the same two-pass algorithm as KeyEvidence#combination (Java), independently
		// implemented (never compared back against a server value - see this whole block's own header note).
		function combination(columns) {
			var rowsInSample = sampleRows.length, allEmpty = 0, partlyEmpty = 0;
			var distinct = {};
			var completeCombined = [];
			for (var r = 0; r < sampleRows.length; r++) {
				var row = sampleRows[r], emptyParts = 0, combined = "";
				for (var c = 0; c < columns.length; c++) {
					var raw = row[columns[c]];
					var value = (raw === undefined || raw === null) ? "" : String(raw).trim();
					if (value === "") emptyParts++;
					combined += value + "\u0001";
				}
				if (columns.length > 0 && emptyParts === columns.length) allEmpty++;
				else if (emptyParts > 0) partlyEmpty++;
				else completeCombined.push(combined);
				distinct[combined] = true;
			}
			var completeCounts = {};
			for (var i = 0; i < completeCombined.length; i++) {
				var key = completeCombined[i];
				completeCounts[key] = (completeCounts[key] || 0) + 1;
			}
			var rowsUniqueComplete = 0;
			for (var j = 0; j < completeCombined.length; j++) {
				if (completeCounts[completeCombined[j]] === 1) rowsUniqueComplete++;
			}
			return {
				rowsInSample: rowsInSample, distinctTogether: Object.keys(distinct).length, rowsAllEmpty: allEmpty, rowsPartlyEmpty: partlyEmpty,
				rowsUniqueComplete: rowsUniqueComplete
			};
		}
		function recompute() {
			var columns = checkedColumns();
			panel.querySelectorAll("[data-mdmf-key-col]").forEach(function (cell) {
				var on = columns.indexOf(cell.getAttribute("data-mdmf-key-col")) !== -1;
				cell.classList.toggle("mdmf-key-highlight", on);
			});
			var applyBtn = byId(panel.id + "Apply");
			if (applyBtn) {
				if (columns.length) {
					applyBtn.textContent = fill(i18n("use-and-continue"), columns.join("+"));
					applyBtn.disabled = false;
				} else {
					applyBtn.textContent = i18n("choose-a-column");
					applyBtn.disabled = true;
				}
			}
			var stats = byId(panel.id + "Stats");
			if (!stats) return;
			if (!columns.length) { stats.innerHTML = ""; return; }
			var combo = combination(columns);
			// coordinator follow-up round 3 (2026-09-24): "make the builder and the cards count the same way"
			// with an EXACT count, not an approximation - combo.rowsUniqueComplete IS "unique in N of M"'s N,
			// computed the same two-pass way KeyEvidence#combination (Java) computes it server-side.
			var emptyRows = combo.rowsAllEmpty + combo.rowsPartlyEmpty;
			var distinct = combo.rowsUniqueComplete;
			var unique = combo.rowsInSample > 0 && distinct === combo.rowsInSample;
			var uniqueTemplate = columns.length > 1 ? i18n("unique-together-of") : i18n("unique-of");
			var html = '<span class="mdmf-chip ' + (unique ? "mdmf-chip-real" : "mdmf-chip-warn") + '">'
				+ fill(uniqueTemplate, distinct, combo.rowsInSample) + (unique ? "" : " · " + i18n("not-a-key")) + "</span> ";
			if (emptyRows === 0) html += '<span class="mdmf-chip mdmf-chip-real">' + i18n("never-empty") + "</span>";
			else html += '<span class="mdmf-chip mdmf-chip-warn">' + fill(i18n(emptyRows === 1 ? "empty-on-row" : "empty-on-rows"), emptyRows) + "</span>";
			stats.innerHTML = html;
		}
		panel.addEventListener("change", function (e) {
			if (e.target && e.target.classList && e.target.classList.contains("mdmf-key-check")) recompute();
		});
		panel.querySelectorAll("[data-mdmf-key-select]").forEach(function (card) {
			card.addEventListener("click", function () {
				var column = card.getAttribute("data-mdmf-key-select");
				panel.querySelectorAll(".mdmf-key-check").forEach(function (c) { c.checked = (c.value === column); });
				recompute();
			});
		});
		recompute();
	})();

	// leave-guard lot (2026-09-23): Chrome's "Quitter le site Web ?" prompt on a Designer link click, seen live
	// by the product owner. Cause, read in EBX's own ebx_loader.js/_ebx_global.js (read-only, ebx-manager
	// webapp): EBX_Form.hasBeenModified compares form.ebx_initialSerialize.value (EBX's own baseline snapshot)
	// against the form's CURRENT serialization on every navigation attempt. The only hidden-field write this
	// whole file makes at LOAD time (not inside a click handler) is showStep(current) just above, via
	// showStep's own set("mdmfStep", ...) - every other set(...)/.value= in this file (mdmfSchemas in
	// syncAlways, the [data-mdmf-action] handler's own writes, the combobox's pick(), the sample-file table)
	// only runs from an event listener, never at init. That one write still lands AFTER EBX's baseline is
	// taken: EBX_Form.initFocusEventsAsTask (the task that sets ebx_initialSerialize.value the first time) is
	// registered via EBX_Loader.addDynamicallyTaskAfterTaskId(EBX_Loader_taskId_destroy_loading_layer, ...) -
	// i.e. spliced in right after "destroy loading layer" - while THIS script runs as the PAGE SCRIPT
	// EXTENSION task (UserServicePaneWriter.addJS_cr -> UIBuilder.addJSExtension -> EBX_Loader.
	// PageScriptExtensionId), which EBX_Loader.initTasks places LAST of the two, after "IFrames" and therefore
	// after the init-focus/snapshot task too. So by the time we get here, EBX already thinks the form is
	// pristine at some earlier point in time, and our own showStep(current) write above makes it look modified
	// from then on - established by reading the loader's task table, not assumed. Nothing scheduled after the
	// "page script extension" task in that same table touches the form, so a synchronous re-snapshot right
	// here (no need to also defer to window's own "load" event) is enough: it always runs after every
	// load-time write this file makes and before anything else could compare against it.
	try {
		var mdmfForm = document.getElementById(EBX_Form.WorkspaceFormId);
		if (mdmfForm && mdmfForm.ebx_initialSerialize) { mdmfForm.ebx_initialSerialize.value = EBX_Form.serialize(mdmfForm); }
	} catch (ignoredForm) { /* EBX_Form not present (e.g. a stand-alone/test load of this file): nothing to reconcile */ }
})();
