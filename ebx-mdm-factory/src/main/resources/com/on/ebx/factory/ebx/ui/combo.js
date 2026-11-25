/*
 * The factory's searchable combobox (source-combobox lot, 2026-09-23; shared since the llm-connector-combo lot,
 * 2026-09-24). Server-rendered markup: div.mdmf-combo[data-mdmf-nomatch] > input[role=combobox] +
 * ul.mdmf-combo-list > li.mdmf-combo-option[data-mdmf-id][data-mdmf-label][data-search] + li.mdmf-combo-empty.
 * A text input filters the pre-rendered listbox on its data-search text (lower-cased server-side); picking an
 * option writes the hidden field, shows the label in the input and, when a pick button id is given, clicks it so
 * the round trip goes through the page's own action path. Keyboard: ArrowDown/ArrowUp move, Enter picks (the
 * active option, or the only match), Escape closes and restores the picked label; a click uses mousedown so the
 * input's blur never closes the list before the click lands. Focusing the input shows the whole list.
 */
(function () {
	"use strict";
	if (window.mdmfInitCombo) return;
	function byId(id) { return document.getElementById(id); }
	function set(id, v) { var e = byId(id); if (e) e.value = v; }

	window.mdmfInitCombo = function (comboId, inputId, listId, hiddenFieldId, pickButtonId) {
		var combo = byId(comboId);
		if (!combo) return;
		var input = byId(inputId);
		var list = byId(listId);
		var empty = list.querySelector(".mdmf-combo-empty");
		var options = Array.prototype.slice.call(list.querySelectorAll(".mdmf-combo-option"));
		var active = -1;

		function visible() { return options.filter(function (o) { return !o.hidden; }); }
		function setActive(index) {
			var shown = visible();
			options.forEach(function (o) { o.classList.remove("active"); });
			if (!shown.length) { active = -1; input.removeAttribute("aria-activedescendant"); return; }
			if (index < 0) index = shown.length - 1;
			if (index >= shown.length) index = 0;
			active = index;
			var option = shown[index];
			option.classList.add("active");
			if (!option.id) option.id = comboId + "Option" + options.indexOf(option);
			input.setAttribute("aria-activedescendant", option.id);
			if (option.scrollIntoView) option.scrollIntoView({ block: "nearest" });
		}
		function filter(query) {
			var q = (query || "").trim().toLowerCase();
			var words = q ? q.split(/\s+/) : [];
			var shown = 0;
			options.forEach(function (o) {
				var text = o.getAttribute("data-search") || "";
				var match = words.every(function (w) { return text.indexOf(w) >= 0; });
				o.hidden = !match;
				if (match) shown++;
			});
			empty.textContent = combo.getAttribute("data-mdmf-nomatch") || "";
			empty.hidden = shown > 0;
			var pickedIndex = visible().findIndex(function (o) { return o.classList.contains("picked"); });
			setActive(pickedIndex >= 0 ? pickedIndex : 0);
		}
		function open(showAll) {
			filter(showAll ? "" : input.value);
			list.hidden = false;
			input.setAttribute("aria-expanded", "true");
		}
		function close(restoreLabel) {
			list.hidden = true;
			input.setAttribute("aria-expanded", "false");
			input.removeAttribute("aria-activedescendant");
			if (restoreLabel) input.value = input.getAttribute("data-mdmf-picked-label") || "";
		}
		function pick(option) {
			options.forEach(function (o) { o.classList.remove("picked"); o.setAttribute("aria-selected", "false"); });
			option.classList.add("picked");
			option.setAttribute("aria-selected", "true");
			var label = option.getAttribute("data-mdmf-label") || "";
			input.value = label;
			input.setAttribute("data-mdmf-picked-label", label);
			set(hiddenFieldId, option.getAttribute("data-mdmf-id") || "");
			close(false);
			var pickButton = pickButtonId ? byId(pickButtonId) : null;
			if (pickButton) pickButton.click();
		}

		// focus AND click: a click on the already-focused input (the picked label showing) must also select the
		// label and reopen the whole list, or the next keystroke lands inside the label ("sap-odata://…skafap…",
		// measured on the static preview, 2026-09-23)
		function showAll() { input.select(); open(true); }
		input.addEventListener("focus", showAll);
		input.addEventListener("click", function () { if (list.hidden) showAll(); });
		input.addEventListener("input", function () { open(false); });
		input.addEventListener("blur", function () { close(true); });
		input.addEventListener("keydown", function (e) {
			// key names first, keyCodes as the fallback (an older EBX-embedded browser, or a synthetic event)
			var key = e.key || "";
			if (key === "ArrowDown" || key === "Down" || e.keyCode === 40) { e.preventDefault(); if (list.hidden) open(true); else setActive(active + 1); }
			else if (key === "ArrowUp" || key === "Up" || e.keyCode === 38) { e.preventDefault(); if (list.hidden) open(true); else setActive(active - 1); }
			else if (key === "Enter" || e.keyCode === 13) {
				e.preventDefault();
				if (list.hidden) { open(true); return; }
				var shown = visible();
				var option = active >= 0 && active < shown.length ? shown[active] : (shown.length === 1 ? shown[0] : null);
				if (option) pick(option);
			}
			else if (key === "Escape" || key === "Esc" || e.keyCode === 27) { e.preventDefault(); close(true); }
		});
		options.forEach(function (o) {
			o.addEventListener("mousedown", function (e) { e.preventDefault(); pick(o); });
			o.addEventListener("mousemove", function () {
				var index = visible().indexOf(o);
				if (index >= 0 && index !== active) setActive(index);
			});
		});
	};
})();
