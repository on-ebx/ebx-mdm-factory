package com.on.ebx.factory.ebx.ui;

import java.util.Locale;
import java.util.Map;

/**
 * The faceted layout of the project explorer (PLAN.md screen 6): check-box facets in a left rail, advanced
 * filters folded under the search line, a line of removable active filters, and card groups that show two full
 * rows before a "show more" button - copied 2026-09-23 from ebx-connectors' own {@code FacetedPage} (kept as a
 * copy per module policy: the two modules ship separately and never share a classpath dependency), and adapted
 * to be genuinely pure. Every method here appends plain HTML to a caller-supplied {@link StringBuilder} instead
 * of writing to a live {@code UserServicePaneWriter}, so {@link ProjectExplorerPage} - the only caller - can
 * render the whole page from fixtures in a JUnit-free test, with no EBX type anywhere in the call chain. The
 * calling {@code UserService} links the stylesheets itself ({@code factory/factory.css} + {@code
 * factory/explorer.css}, the same way {@link CockpitPage}'s own caller does), since resolving a versioned
 * resource URL needs a live {@code UserServicePaneWriter}.
 * <p>
 * CSS classes carry the factory's own prefixes, {@code mdmx-} (rail/tools/facets) and {@code mdmc-}
 * (cards/grids), renamed here from ebx-connectors' {@code ftcx-}/{@code ftcc-} so the two modules' styles can
 * never collide if both happen to be loaded in the same browser tab (e.g. two EBX perspectives open in
 * different tabs of the same session share no state, but a copy-paste of one module's CSS onto the other would
 * have been a silent, hard-to-diagnose conflict otherwise).
 * <p>
 * Page order: search row ({@link #railToggle}, search box, {@link #sortControl}, {@link #advancedToggle}) →
 * {@link #openAdvanced} → {@link #pillRow}s → {@link #openRail} → {@link #facet}s → {@link #openMain} → groups ({@link #openGroup}, heading, {@link #closeGroupHeading}, cards,
 * {@link #closeGroup}) → {@link #closeMain} → {@link #script}.
 * <p>
 * Cards carry one {@code data-<filter>} attribute per filter; the attributes named in {@code pMultiValued} hold
 * space-separated values. Filters combine as OR inside a filter and AND between filters; each facet counts the cards that
 * pass every other filter, so a value that would give nothing is dimmed.
 * <p>
 * Two client sides: {@link #script} filters cards already written into the page; {@link #serverScript} (2026-10-11, the project explorer at volume)
 * writes no card and asks the server for each page and for the counts, with the same controls and the same rules.
 */
final class FacetedPage
{
	/** Full rows of cards shown per group before its "show more" button, and rows in list mode. */
	static final int GROUP_CARD_ROWS = 2;
	static final int GROUP_LIST_ROWS = 12;

	private final Locale locale;

	FacetedPage(final Locale pLocale)
	{
		this.locale = pLocale;
	}

	String message(final String pKey)
	{
		return Messages.get(FacetedPage.class, this.locale, pKey);
	}

	static String esc(final String pText)
	{
		return FactoryHtml.esc(pText);
	}

	static String js(final String pText)
	{
		return FactoryHtml.jsStringBody(pText);
	}

	/**
	 * Client-side search: every word typed must appear, in any order, ignoring case, accents, spaces, punctuation and a
	 * final plural "s". Declares ftTerms(input) and ftMatch(card, terms) in the enclosing script.
	 */
	static final String SEARCH_JS = "function ftFold(s){return (s||'').normalize('NFD').replace(/\\p{M}/gu,'').toLowerCase();}"
		+ "function ftTerms(s){return ftFold(s).split(/[^\\p{L}\\p{N}]+/u).filter(function(w){return w;}).map(function(w){return w.length>3&&w.charAt(w.length-1)==='s'?w.slice(0,-1):w;});}"
		+ "function ftMatch(c,terms){var t=c.ftSearch;if(t===undefined)t=c.ftSearch=ftFold(c.dataset.search).replace(/[^\\p{L}\\p{N}]+/gu,'');for(var i=0;i<terms.length;i++){if(t.indexOf(terms[i])<0)return false;}return true;}";

	/** The button that hides or shows the facet rail (remembered per browser), with a badge counting its active filters. */
	void railToggle(final StringBuilder pOut)
	{
		pOut.append("<button type=\"button\" id=\"mdmxRailToggle\" class=\"mdmx-rail-toggle\" aria-controls=\"mdmxRail\" aria-expanded=\"true\" data-show=\"").append(FacetedPage.esc(this.message("explorer.showFilters")))
			.append("\" data-hide=\"").append(FacetedPage.esc(this.message("explorer.hideFilters"))).append("\" title=\"").append(FacetedPage.esc(this.message("explorer.hideFilters")))
			.append("\"><i aria-hidden=\"true\"></i><span>").append(FacetedPage.esc(this.message("explorer.filters"))).append("</span><b hidden></b></button>");
	}

	/**
	 * A compact sort: the criterion (short labels, value → label) and a button that flips the direction. Names sort A→Z
	 * and figures highest first until the button is used; choosing another criterion restores its natural direction.
	 */
	void sortControl(final StringBuilder pOut, final String pId, final Map<String, String> pOptions)
	{
		pOut.append("<span class=\"mdmx-sort\"><select id=\"").append(pId).append("\" aria-label=\"").append(FacetedPage.esc(this.message("explorer.sort.by"))).append("\" title=\"").append(FacetedPage.esc(this.message("explorer.sort.by"))).append("\">");
		for (final Map.Entry<String, String> e : pOptions.entrySet()) pOut.append("<option value=\"").append(FacetedPage.esc(e.getKey())).append("\">").append(FacetedPage.esc(e.getValue())).append("</option>");
		pOut.append("</select><button type=\"button\" id=\"").append(pId).append("Dir\" class=\"mdmx-dir\" data-asc=\"").append(FacetedPage.esc(this.message("explorer.sort.asc"))).append("\" data-desc=\"")
			.append(FacetedPage.esc(this.message("explorer.sort.desc"))).append("\"></button></span>");
	}

	/** The button that unfolds the advanced filters, with a badge counting the active ones. */
	void advancedToggle(final StringBuilder pOut)
	{
		pOut.append("<button type=\"button\" id=\"mdmxMoreToggle\" class=\"mdmx-more-toggle\" aria-expanded=\"false\" aria-controls=\"mdmxMore\" data-more=\"").append(FacetedPage.esc(this.message("explorer.moreFilters")))
			.append("\" data-less=\"").append(FacetedPage.esc(this.message("explorer.lessFilters"))).append("\"><span>").append(FacetedPage.esc(this.message("explorer.moreFilters"))).append("</span><b id=\"mdmxActive\" hidden></b></button>");
	}

	/** Opens the folded advanced panel, right after the search row (still inside {@code .mdmc-tools}). */
	void openAdvanced(final StringBuilder pOut)
	{
		pOut.append("<div class=\"mdmx-more\" id=\"mdmxMore\" hidden>");
	}

	/** An advanced filter: a row of toggle pills. */
	void pillRow(final StringBuilder pOut, final String pAttr, final String pLabel, final Map<String, String> pValues)
	{
		if (pValues.isEmpty()) return;
		pOut.append("<div class=\"mdmx-pills\" data-filter=\"").append(pAttr).append("\" data-label=\"").append(FacetedPage.esc(pLabel)).append("\"><span class=\"mdmc-filter-label\">").append(FacetedPage.esc(pLabel)).append("</span>")
			.append("<button type=\"button\" class=\"active\" data-value=\"\">").append(FacetedPage.esc(this.message("explorer.all"))).append("</button>");
		for (final Map.Entry<String, String> e : pValues.entrySet())
		{
			pOut.append("<button type=\"button\" data-value=\"").append(FacetedPage.esc(e.getKey())).append("\" data-label=\"").append(FacetedPage.esc(e.getValue())).append("\" aria-pressed=\"false\">")
				.append(FacetedPage.esc(e.getValue())).append(" <i></i></button>");
		}
		pOut.append("</div>");
	}

	/**
	 * Closes the advanced panel and the tools (active filters, then the page's links and the counter on the right), then
	 * opens the facet rail. {@code pLinksHtml} is ready-made HTML, possibly empty.
	 */
	void openRail(final StringBuilder pOut, final String pLinksHtml)
	{
		pOut.append("</div><div class=\"mdmx-activebar\"><span id=\"mdmxChips\" class=\"mdmx-chips\"></span><button type=\"button\" id=\"mdmxClear\" class=\"mdmx-clear\" hidden>")
			.append(FacetedPage.esc(this.message("explorer.clearAll"))).append("</button><span class=\"mdmx-links\">").append(pLinksHtml == null ? "" : pLinksHtml).append("</span><span id=\"mdmxCount\" class=\"mdmc-count\"></span></div></div>")
			.append("<div class=\"mdmx-body\"><aside class=\"mdmx-rail\" id=\"mdmxRail\" aria-label=\"").append(FacetedPage.esc(this.message("explorer.filters"))).append("\">");
	}

	/** A facet of the rail: check boxes, the most frequent values first, the rest behind a "show N more" link. */
	void facet(final StringBuilder pOut, final String pAttr, final String pLabel, final Map<String, String> pValues, final int pTop)
	{
		if (pValues.isEmpty()) return;
		pOut.append("<section class=\"mdmx-facet\" data-filter=\"").append(pAttr).append("\" data-label=\"").append(FacetedPage.esc(pLabel)).append("\" data-top=\"").append(String.valueOf(pTop))
			.append("\"><button type=\"button\" class=\"mdmx-facet-h\" aria-expanded=\"true\"><span>").append(FacetedPage.esc(pLabel)).append("</span><b class=\"mdmx-facet-n\"></b><i aria-hidden=\"true\"></i></button>");
		if (pValues.size() > 8) pOut.append("<input type=\"search\" class=\"mdmx-find\" placeholder=\"").append(FacetedPage.esc(this.message("explorer.facet.find"))).append("\"/>");
		pOut.append("<div class=\"mdmx-opts\">");
		int order = 0;
		for (final Map.Entry<String, String> e : pValues.entrySet())
		{
			pOut.append("<label class=\"mdmx-opt\" data-value=\"").append(FacetedPage.esc(e.getKey())).append("\" data-label=\"").append(FacetedPage.esc(e.getValue())).append("\" data-order=\"").append(String.valueOf(order++))
				.append("\"><input type=\"checkbox\"/><span>").append(FacetedPage.esc(e.getValue())).append("</span><i></i></label>");
		}
		pOut.append("</div><button type=\"button\" class=\"mdmx-moreopts\" data-tpl=\"").append(FacetedPage.esc(this.message("explorer.facet.more"))).append("\" data-less=\"")
			.append(FacetedPage.esc(this.message("explorer.facet.less"))).append("\" hidden></button></section>");
	}

	/** Closes the rail and opens the result column (empty-state first, then the groups). */
	void openMain(final StringBuilder pOut, final String pEmptyMessage)
	{
		this.openMain(pOut, pEmptyMessage, false);
	}

	/** {@code pHideEmpty}: the empty-state starts hidden (server-paged page: nothing is known before the first answer). */
	void openMain(final StringBuilder pOut, final String pEmptyMessage, final boolean pHideEmpty)
	{
		// the probe is an always-displayed empty grid: the script reads the column count on it, whatever group is hidden
		pOut.append("</aside><div class=\"mdmx-main\"><div class=\"mdmc-grid mdmx-probe\" aria-hidden=\"true\"><i></i></div><div id=\"mdmcEmpty\" class=\"mdmc-empty\"")
			.append(pHideEmpty ? " style=\"display:none\"" : "").append(">").append(FacetedPage.esc(pEmptyMessage)).append("</div>");
	}

	/**
	 * Opens a result group and its heading title; the caller writes the title. Groups keep {@code pOrder} (the display
	 * order of the connector categories), pinned groups first.
	 */
	void openGroup(final StringBuilder pOut, final String pGroup, final int pOrder, final boolean pPinned)
	{
		this.openGroup(pOut, pGroup, pOrder, pPinned, false);
	}

	/** {@code pHidden}: the group starts hidden (server-paged page: its cards come with the first answer). */
	void openGroup(final StringBuilder pOut, final String pGroup, final int pOrder, final boolean pPinned, final boolean pHidden)
	{
		pOut.append("<section class=\"mdmc-group\" data-group=\"").append(FacetedPage.esc(pGroup)).append("\" data-order=\"").append(String.valueOf(pOrder)).append("\" data-pin=\"").append(pPinned ? "1" : "0")
			.append(pHidden ? "\" hidden data-n=\"0" : "").append("\"><div class=\"mdmc-heading mdmx-heading\"><div><h2>");
	}

	void closeGroupHeading(final StringBuilder pOut, final String pWord, final String pDescription)
	{
		pOut.append(" <span class=\"mdmx-groupcount\"><span data-groupcount=\"1\"></span> ").append(FacetedPage.esc(pWord)).append("</span></h2>");
		if (pDescription != null && !pDescription.isEmpty()) pOut.append("<p>").append(FacetedPage.esc(pDescription)).append("</p>");
		pOut.append("</div></div><div class=\"mdmc-grid\">");
	}

	void closeGroup(final StringBuilder pOut)
	{
		pOut.append("</div><button type=\"button\" class=\"mdmx-showmore\" data-tpl=\"").append(FacetedPage.esc(this.message("explorer.showMore"))).append("\" hidden></button></section>");
	}

	/** Closes the result column and the body. */
	void closeMain(final StringBuilder pOut)
	{
		pOut.append("</div></div>");
	}

	/**
	 * The client side. Runs on the element matching {@code pRoot} and exposes {@code root.mdmxFacets} ({@code select(filter,
	 * value)}, {@code changed()}, {@code apply()}) for the page's own script. Sort values other than {@code name} are
	 * numeric card attributes, highest first.
	 */
	String script(final String pRoot, final String pSearchId, final String pSortId, final String... pMultiValued)
	{
		final StringBuilder multi = new StringBuilder();
		for (final String m : pMultiValued) multi.append(multi.length() == 0 ? "" : ",").append(m).append(":1");
		return FactoryHtml.userStoreLib() + "(function(){var root=document.querySelector('" + pRoot + "');if(!root)return;var ST=window.mdmfStore?window.mdmfStore.forUser(root.dataset.user||'').local:null;var q=document.getElementById('" + pSearchId + "'),sort=document.getElementById('" + pSortId + "'),main=root.querySelector('.mdmx-main');"
			+ "var cards=Array.prototype.slice.call(root.querySelectorAll('.mdmc-card')),groups=Array.prototype.slice.call(root.querySelectorAll('.mdmc-group'));"
			+ "var filters=Array.prototype.slice.call(root.querySelectorAll('[data-filter]')),state={},limits={},expanded={},MULTI={" + multi + "};"
			+ "filters.forEach(function(f){state[f.dataset.filter]=[];});"
			+ FacetedPage.SEARCH_JS
			+ "function esc(s){return String(s).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}"
			+ "function vals(c,k){var v=c.dataset[k]||'';return MULTI[k]?v.split(' '):[v];}"
			+ "function has(a,v){return a.indexOf(v)>=0;}"
			+ "function passes(c,terms,skip){if(!ftMatch(c,terms))return false;for(var k in state){if(k===skip||!state[k].length)continue;var cv=vals(c,k),ok=false;for(var i=0;i<state[k].length;i++){if(has(cv,state[k][i])){ok=true;break;}}if(!ok)return false;}return true;}"
			+ "function opts(f){return Array.prototype.slice.call(f.querySelectorAll('[data-value]')).filter(function(o){return o.dataset.value!=='';});}"
			+ "var dirBtn=document.getElementById('" + pSortId + "Dir'),dir=0;"
			+ "function sdir(){return dir||(sort.value==='name'?1:-1);}"
			+ "function showDir(){if(!dirBtn)return;var up=sdir()>0,t=up?dirBtn.dataset.asc:dirBtn.dataset.desc;dirBtn.textContent=up?'\u2191':'\u2193';dirBtn.title=t;dirBtn.setAttribute('aria-label',t);}"
			+ "function per(){if(root.classList.contains('mdmx-list'))return " + FacetedPage.GROUP_LIST_ROWS + ";var p=root.querySelector('.mdmx-probe'),t=p?getComputedStyle(p).gridTemplateColumns:'',n=t&&t!=='none'&&t.indexOf('repeat')<0?t.split(' ').length:3;return " + FacetedPage.GROUP_CARD_ROWS + "*Math.max(1,n);}"
			+ "function apply(){var terms=ftTerms(q.value),shown=0;"
			+ "cards.forEach(function(c){var ok=passes(c,terms,null);c.hidden=!ok;if(ok)shown++;});"
			+ "filters.forEach(function(f){var k=f.dataset.filter,counts={},all=f.querySelector('[data-value=\"\"]'),os=opts(f);"
			+ "cards.forEach(function(c){if(passes(c,terms,k))vals(c,k).forEach(function(v){counts[v]=(counts[v]||0)+1;});});"
			+ "os.forEach(function(o){var v=o.dataset.value,n=counts[v]||0,on=has(state[k],v),i=o.querySelector('i'),cb=o.querySelector('input');o.dataset.n=n;if(i)i.textContent=n;o.classList.toggle('mdmx-zero',!n&&!on);o.classList.toggle('active',on);if(cb)cb.checked=on;else o.setAttribute('aria-pressed',on?'true':'false');});"
			+ "if(all)all.classList.toggle('active',!state[k].length);"
			+ "var hn=f.querySelector('.mdmx-facet-n');if(hn)hn.textContent=state[k].length?state[k].length:'';"
			+ "var list=f.querySelector('.mdmx-opts');if(!list)return;var find=f.querySelector('.mdmx-find'),ft=find?ftTerms(find.value):[],top=+f.dataset.top||6,i=0,extra=0;"
			+ "os.sort(function(a,b){return (has(state[k],b.dataset.value)-has(state[k],a.dataset.value))||((+b.dataset.n)-(+a.dataset.n))||((+a.dataset.order)-(+b.dataset.order));});"
			+ "os.forEach(function(o){list.appendChild(o);var l=ftFold(o.dataset.label),m=ft.every(function(t){return l.indexOf(t)>=0;});var vis=m&&(ft.length>0||expanded[k]||i<top||has(state[k],o.dataset.value));if(m){i++;if(!vis)extra++;}o.hidden=!vis;});"
			+ "var mb=f.querySelector('.mdmx-moreopts');mb.hidden=ft.length>0||(!extra&&!expanded[k]);mb.textContent=expanded[k]?mb.dataset.less:mb.dataset.tpl.replace('{0}',extra);});"
			+ "groups.forEach(function(g){g.hidden=!g.querySelector('.mdmc-card:not([hidden])');});"
			+ "var s=sort.value,d=sdir(),lim0=per();"
			+ "groups.forEach(function(g){var grid=g.querySelector('.mdmc-grid'),v=Array.prototype.slice.call(grid.querySelectorAll('.mdmc-card:not([hidden])'));"
			+ "v.sort(function(a,b){if(s==='name')return d*(a.dataset.name<b.dataset.name?-1:a.dataset.name>b.dataset.name?1:0);return d*((+(a.dataset[s]||0))-(+(b.dataset[s]||0)));});"
			+ "v.forEach(function(c){grid.appendChild(c);});var lim=limits[g.dataset.group]||lim0;v.forEach(function(c,i){c.classList.toggle('mdmx-cut',i>=lim);});"
			+ "var n=g.querySelector('[data-groupcount]');if(n)n.textContent=v.length;g.hidden=!v.length;g.dataset.n=v.length;"
			+ "var b=g.querySelector('.mdmx-showmore'),rest=v.length-lim;b.hidden=rest<=0;if(rest>0)b.textContent=b.dataset.tpl.replace('{0}',Math.min(3*lim0,rest)).replace('{1}',rest);});"
			+ "groups.slice().sort(function(a,b){return ((+b.dataset.pin)-(+a.dataset.pin))||((+a.dataset.order)-(+b.dataset.order));}).forEach(function(g){main.appendChild(g);});"
			+ "var html='',nf=0;filters.forEach(function(f){var k=f.dataset.filter;state[k].forEach(function(v){var o=opts(f).filter(function(x){return x.dataset.value===v;})[0];nf++;"
			+ "html+='<span class=\"mdmx-chip\">'+esc(f.dataset.label)+' : '+esc(o?o.dataset.label:v)+'<button type=\"button\" data-remove=\"'+esc(k)+'\" data-remove-value=\"'+esc(v)+'\" aria-label=\"" + FacetedPage.js(FacetedPage.esc(this.message("explorer.removeFilter"))) + "\">&#215;</button></span>';});});"
			+ "document.getElementById('mdmxChips').innerHTML=html;document.getElementById('mdmxClear').hidden=!(nf||q.value.trim());"
			+ "document.getElementById('mdmcEmpty').style.display=shown?'none':'block';document.getElementById('mdmxCount').textContent=shown+' / '+cards.length+' '+(cards.length>1?'" + FacetedPage.js(this.message("explorer.results")) + "':'" + FacetedPage.js(this.message("explorer.result")) + "');badgeCount();}"
			+ "function changed(){limits={};apply();}"
			+ "function toggle(k,v,on){var a=state[k],i=a.indexOf(v);if(on&&i<0)a.push(v);if(!on&&i>=0)a.splice(i,1);changed();}"
			+ "function select(k,v){if(state[k]===undefined)return;state[k]=[v];}"
			+ "root.addEventListener('change',function(e){var t=e.target,o=t.closest?t.closest('.mdmx-opt'):null;if(!o)return;toggle(o.closest('[data-filter]').dataset.filter,o.dataset.value,t.checked);});"
			+ "root.addEventListener('input',function(e){if(e.target.classList.contains('mdmx-find'))apply();});"
			+ "root.addEventListener('click',function(e){var b=e.target.closest?e.target.closest('button'):null;if(!b||!root.contains(b))return;var f=b.closest('[data-filter]');"
			+ "if(b.dataset.remove!==undefined){toggle(b.dataset.remove,b.dataset.removeValue,false);}"
			+ "else if(b.id==='mdmxClear'){for(var k in state)state[k]=[];q.value='';root.querySelectorAll('.mdmx-find').forEach(function(x){x.value='';});changed();}"
			+ "else if(b.classList.contains('mdmx-showmore')){var g=b.closest('.mdmc-group').dataset.group;limits[g]=(limits[g]||per())+3*per();apply();}"
			+ "else if(b.classList.contains('mdmx-moreopts')){expanded[f.dataset.filter]=!expanded[f.dataset.filter];apply();}"
			+ "else if(b.classList.contains('mdmx-facet-h')){var c=f.classList.toggle('mdmx-folded');b.setAttribute('aria-expanded',c?'false':'true');}"
			+ "else if(f&&f.classList.contains('mdmx-pills')&&b.dataset.value!==undefined){var k2=f.dataset.filter;if(b.dataset.value==='')state[k2]=[];else{toggle(k2,b.dataset.value,!has(state[k2],b.dataset.value));return;}changed();}});"
			+ "var more=document.getElementById('mdmxMore'),tog=document.getElementById('mdmxMoreToggle'),badge=document.getElementById('mdmxActive');"
			+ "function setMore(open){if(!more||!tog)return;more.hidden=!open;tog.setAttribute('aria-expanded',open?'true':'false');tog.querySelector('span').textContent=open?tog.dataset.less:tog.dataset.more;if(ST)ST.set('moreOpen',open?'1':'0');}"
			+ "function badgeCount(){var rb=railBtn?railBtn.querySelector('b'):null;if(rb&&rail){var m=0;rail.querySelectorAll('[data-filter]').forEach(function(r){if(state[r.dataset.filter].length)m++;});rb.textContent=m;rb.hidden=!m;}"
			+ "if(!badge||!more)return;var n=0;more.querySelectorAll('[data-filter]').forEach(function(r){if(state[r.dataset.filter].length)n++;});badge.textContent=n;badge.hidden=!n;}"
			+ "if(!more||!more.querySelector('[data-filter]')){if(tog)tog.hidden=true;more=null;}else if(tog){tog.addEventListener('click',function(){setMore(more.hidden);});var open=false;open=!!ST&&ST.get('moreOpen')==='1';setMore(open);}"
			+ "q.addEventListener('input',changed);sort.addEventListener('change',function(){dir=0;showDir();apply();});"
			+ "if(dirBtn)dirBtn.addEventListener('click',function(){dir=-sdir();showDir();apply();});showDir();"
			+ "var rail=document.getElementById('mdmxRail'),railBtn=document.getElementById('mdmxRailToggle');"
			+ "function setRail(show,save){root.classList.toggle('mdmx-norail',!show);if(railBtn){railBtn.setAttribute('aria-expanded',show?'true':'false');railBtn.title=show?railBtn.dataset.hide:railBtn.dataset.show;railBtn.classList.toggle('active',show);}if(save&&ST)ST.set('rail',show?'1':'0');}"
			+ "if(railBtn&&rail){railBtn.addEventListener('click',function(){setRail(root.classList.contains('mdmx-norail'),true);changed();});var shown=true;shown=!ST||ST.get('rail')!=='0';setRail(shown,false);}"
			// the workspace is resized whenever the EBX menu folds: the two full rows of a group follow the new column count
			+ "var rz=0;window.addEventListener('resize',function(){clearTimeout(rz);rz=setTimeout(apply,150);});"
			+ "root.mdmxFacets={select:function(k,v){select(k,v);if(more&&more.querySelector('[data-filter=\"'+k+'\"]'))setMore(true);},changed:changed,apply:apply};apply();})();";
	}

	/**
	 * Server-paged explorer (2026-10-11): the same page, the same controls and the same {@code root.mdmxFacets} ({@code select}, {@code changed},
	 * {@code apply}), but the cards, their counts and the facet counts come from the server. The page element carries the URL of the "query" AJAX
	 * request in {@code data-query}; each change (a facet, a pill, the sort, its direction, a cleared filter; the search box after a 250 ms pause)
	 * asks for the first page of every status group - two full rows of the current column count, as {@link #script} showed - and "show more" asks
	 * the next page of that group only and appends it. Answers that arrive after a newer request are dropped. After inserting cards, the root
	 * element fires an {@code mdmxcards} event for the page's other scripts. Expected answer: {@code {all, total,
	 * facets: {filter: {value: count}}, groups: [{g, n, html}]}} ({@code html}: the cards of the page, {@code n}: the group's whole count). The
	 * text-search, facet and sort rules are {@link ProjectExplorerQuery}'s. {@code pFailedMessage} replaces the empty-state when an answer cannot
	 * be read.
	 */
	String serverScript(final String pRoot, final String pSearchId, final String pSortId, final String pFailedMessage)
	{
		return FactoryHtml.userStoreLib() + "(function(){var root=document.querySelector('" + pRoot + "');if(!root||!root.dataset.query)return;var ST=window.mdmfStore?window.mdmfStore.forUser(root.dataset.user||'').local:null;var q=document.getElementById('" + pSearchId + "'),sort=document.getElementById('" + pSortId + "'),EP=root.dataset.query;"
			+ "var groups=Array.prototype.slice.call(root.querySelectorAll('.mdmc-group')),filters=Array.prototype.slice.call(root.querySelectorAll('[data-filter]')),state={},expanded={},R=null,seq=0,lim0=0,tq=0;"
			+ "var emptyEl=document.getElementById('mdmcEmpty'),emptyText=emptyEl.textContent;"
			+ "filters.forEach(function(f){state[f.dataset.filter]=[];});"
			+ FacetedPage.SEARCH_JS
			+ "function esc(s){return String(s).replace(/[&<>\"]/g,function(c){return {'&':'&amp;','<':'&lt;','>':'&gt;','\"':'&quot;'}[c];});}"
			+ "function has(a,v){return a.indexOf(v)>=0;}"
			+ "function opts(f){return Array.prototype.slice.call(f.querySelectorAll('[data-value]')).filter(function(o){return o.dataset.value!=='';});}"
			+ "var dirBtn=document.getElementById('" + pSortId + "Dir'),dir=0;"
			+ "function sdir(){return dir||(sort.value==='name'?1:-1);}"
			+ "function showDir(){if(!dirBtn)return;var up=sdir()>0,t=up?dirBtn.dataset.asc:dirBtn.dataset.desc;dirBtn.textContent=up?'↑':'↓';dirBtn.title=t;dirBtn.setAttribute('aria-label',t);}"
			+ "function per(){if(root.classList.contains('mdmx-list'))return " + FacetedPage.GROUP_LIST_ROWS + ";var p=root.querySelector('.mdmx-probe'),t=p?getComputedStyle(p).gridTemplateColumns:'',n=t&&t!=='none'&&t.indexOf('repeat')<0?t.split(' ').length:3;return " + FacetedPage.GROUP_CARD_ROWS + "*Math.max(1,n);}"
			// the request: text, facets ("status=DRAFT|LIVE;owner=x"), sort, direction (0 = the criterion's natural one), page, and the group for a "show more"
			+ "function sel(){var p=[];for(var k in state){if(state[k].length)p.push(k+'='+state[k].join('|'));}return p.join(';');}"
			+ "function url(g,off,lim){var u=new URL(EP,window.location.href);u.searchParams.set('q',q.value);u.searchParams.set('f',sel());u.searchParams.set('sort',sort.value);u.searchParams.set('dir',String(dir));"
			+ "u.searchParams.set('off',String(off));u.searchParams.set('lim',String(lim));if(g)u.searchParams.set('group',g);return u.href;}"
			+ "function fail(){emptyEl.textContent='" + FacetedPage.js(pFailedMessage) + "';emptyEl.style.display='block';}"
			+ "function send(href,mine,done,failed){var h=new EBX_AJAXResponseHandler();h.handleAjaxResponseSuccess=function(t){if(mine!==seq)return;var d;try{d=JSON.parse(t);}catch(x){d=null;}if(!d||!d.groups){fail();if(failed)failed();return;}done(d);};"
			+ "h.handleAjaxResponseFailed=function(){if(mine!==seq)return;fail();if(failed)failed();};h.sendRequest(href);}"
			+ "function grid(g){return g.querySelector('.mdmc-grid');}"
			+ "function part(d,k){for(var i=0;i<d.groups.length;i++){if(d.groups[i].g===k)return d.groups[i];}return null;}"
			// a new question: first page of every group; a "show more": the next page of one group, dropped if a new question was asked meanwhile
			+ "function refresh(){clearTimeout(tq);lim0=per();var mine=++seq;send(url('',0,lim0),mine,function(d){R=d;groups.forEach(function(g){var a=part(d,g.dataset.group);grid(g).innerHTML=a?a.html:'';g.dataset.n=a?a.n:0;"
			+ "var b=g.querySelector('.mdmx-showmore');if(b)b.disabled=false;});paint();cardsIn();});}"
			+ "function next(g,b){b.disabled=true;var have=grid(g).querySelectorAll('.mdmc-card').length;send(url(g.dataset.group,have,3*(lim0||per())),seq,function(d){b.disabled=false;var a=part(d,g.dataset.group);"
			+ "if(a){grid(g).insertAdjacentHTML('beforeend',a.html);g.dataset.n=a.n;}R=d;paint();cardsIn();},function(){b.disabled=false;});}"
			// the page's other scripts (link rewrite, return-to-explorer capture) learn that new cards are in
			+ "function cardsIn(){try{root.dispatchEvent(new CustomEvent('mdmxcards'));}catch(x){}}"
			+ "function paint(){var counts=R?R.facets:{};"
			+ "filters.forEach(function(f){var k=f.dataset.filter,c=counts[k]||{},all=f.querySelector('[data-value=\"\"]'),os=opts(f);"
			+ "os.forEach(function(o){var v=o.dataset.value,n=c[v]||0,on=has(state[k],v),i=o.querySelector('i'),cb=o.querySelector('input');o.dataset.n=n;if(i)i.textContent=n;o.classList.toggle('mdmx-zero',!n&&!on);o.classList.toggle('active',on);if(cb)cb.checked=on;else o.setAttribute('aria-pressed',on?'true':'false');});"
			+ "if(all)all.classList.toggle('active',!state[k].length);"
			+ "var hn=f.querySelector('.mdmx-facet-n');if(hn)hn.textContent=state[k].length?state[k].length:'';"
			+ "var list=f.querySelector('.mdmx-opts');if(!list)return;var find=f.querySelector('.mdmx-find'),ft=find?ftTerms(find.value):[],top=+f.dataset.top||6,i=0,extra=0;"
			+ "os.sort(function(a,b){return (has(state[k],b.dataset.value)-has(state[k],a.dataset.value))||((+b.dataset.n)-(+a.dataset.n))||((+a.dataset.order)-(+b.dataset.order));});"
			+ "os.forEach(function(o){list.appendChild(o);var l=ftFold(o.dataset.label),m=ft.every(function(t){return l.indexOf(t)>=0;});var vis=m&&(ft.length>0||expanded[k]||i<top||has(state[k],o.dataset.value));if(m){i++;if(!vis)extra++;}o.hidden=!vis;});"
			+ "var mb=f.querySelector('.mdmx-moreopts');mb.hidden=ft.length>0||(!extra&&!expanded[k]);mb.textContent=expanded[k]?mb.dataset.less:mb.dataset.tpl.replace('{0}',extra);});"
			+ "var lim=lim0||per();"
			+ "groups.forEach(function(g){var n=+(g.dataset.n||0),have=grid(g).querySelectorAll('.mdmc-card').length,c=g.querySelector('[data-groupcount]');if(c)c.textContent=n;g.hidden=!n;"
			+ "var b=g.querySelector('.mdmx-showmore'),rest=n-have;b.hidden=rest<=0;if(rest>0)b.textContent=b.dataset.tpl.replace('{0}',Math.min(3*lim,rest)).replace('{1}',rest);});"
			+ "var html='',nf=0;filters.forEach(function(f){var k=f.dataset.filter;state[k].forEach(function(v){var o=opts(f).filter(function(x){return x.dataset.value===v;})[0];nf++;"
			+ "html+='<span class=\"mdmx-chip\">'+esc(f.dataset.label)+' : '+esc(o?o.dataset.label:v)+'<button type=\"button\" data-remove=\"'+esc(k)+'\" data-remove-value=\"'+esc(v)+'\" aria-label=\"" + FacetedPage.js(FacetedPage.esc(this.message("explorer.removeFilter"))) + "\">&#215;</button></span>';});});"
			+ "document.getElementById('mdmxChips').innerHTML=html;document.getElementById('mdmxClear').hidden=!(nf||q.value.trim());"
			+ "if(R){emptyEl.textContent=emptyText;emptyEl.style.display=R.total?'none':'block';document.getElementById('mdmxCount').textContent=R.total+' / '+R.all+' '+(R.all>1?'" + FacetedPage.js(this.message("explorer.results")) + "':'" + FacetedPage.js(this.message("explorer.result")) + "');}"
			+ "badgeCount();}"
			+ "function changed(){paint();refresh();}"
			+ "function relimit(){if(per()!==lim0)refresh();else paint();}"
			+ "function toggle(k,v,on){var a=state[k],i=a.indexOf(v);if(on&&i<0)a.push(v);if(!on&&i>=0)a.splice(i,1);changed();}"
			+ "function select(k,v){if(state[k]===undefined)return;state[k]=[v];}"
			+ "root.addEventListener('change',function(e){var t=e.target,o=t.closest?t.closest('.mdmx-opt'):null;if(!o)return;toggle(o.closest('[data-filter]').dataset.filter,o.dataset.value,t.checked);});"
			+ "root.addEventListener('input',function(e){if(e.target.classList.contains('mdmx-find'))paint();});"
			+ "root.addEventListener('click',function(e){var b=e.target.closest?e.target.closest('button'):null;if(!b||!root.contains(b))return;var f=b.closest('[data-filter]');"
			+ "if(b.dataset.remove!==undefined){toggle(b.dataset.remove,b.dataset.removeValue,false);}"
			+ "else if(b.id==='mdmxClear'){for(var k in state)state[k]=[];q.value='';root.querySelectorAll('.mdmx-find').forEach(function(x){x.value='';});changed();}"
			+ "else if(b.classList.contains('mdmx-showmore')){if(!b.disabled)next(b.closest('.mdmc-group'),b);}"
			+ "else if(b.classList.contains('mdmx-moreopts')){expanded[f.dataset.filter]=!expanded[f.dataset.filter];paint();}"
			+ "else if(b.classList.contains('mdmx-facet-h')){var c=f.classList.toggle('mdmx-folded');b.setAttribute('aria-expanded',c?'false':'true');}"
			+ "else if(f&&f.classList.contains('mdmx-pills')&&b.dataset.value!==undefined){var k2=f.dataset.filter;if(b.dataset.value==='')state[k2]=[];else{toggle(k2,b.dataset.value,!has(state[k2],b.dataset.value));return;}changed();}});"
			+ "var more=document.getElementById('mdmxMore'),tog=document.getElementById('mdmxMoreToggle'),badge=document.getElementById('mdmxActive');"
			+ "function setMore(open){if(!more||!tog)return;more.hidden=!open;tog.setAttribute('aria-expanded',open?'true':'false');tog.querySelector('span').textContent=open?tog.dataset.less:tog.dataset.more;if(ST)ST.set('moreOpen',open?'1':'0');}"
			+ "function badgeCount(){var rb=railBtn?railBtn.querySelector('b'):null;if(rb&&rail){var m=0;rail.querySelectorAll('[data-filter]').forEach(function(r){if(state[r.dataset.filter].length)m++;});rb.textContent=m;rb.hidden=!m;}"
			+ "if(!badge||!more)return;var n=0;more.querySelectorAll('[data-filter]').forEach(function(r){if(state[r.dataset.filter].length)n++;});badge.textContent=n;badge.hidden=!n;}"
			+ "if(!more||!more.querySelector('[data-filter]')){if(tog)tog.hidden=true;more=null;}else if(tog){tog.addEventListener('click',function(){setMore(more.hidden);});var open=false;open=!!ST&&ST.get('moreOpen')==='1';setMore(open);}"
			// the search box: one request once typing pauses, never one per key
			+ "q.addEventListener('input',function(){clearTimeout(tq);tq=setTimeout(function(){paint();refresh();},250);});sort.addEventListener('change',function(){dir=0;showDir();changed();});"
			+ "if(dirBtn)dirBtn.addEventListener('click',function(){dir=-sdir();showDir();changed();});showDir();"
			+ "var rail=document.getElementById('mdmxRail'),railBtn=document.getElementById('mdmxRailToggle');"
			+ "function setRail(show,save){root.classList.toggle('mdmx-norail',!show);if(railBtn){railBtn.setAttribute('aria-expanded',show?'true':'false');railBtn.title=show?railBtn.dataset.hide:railBtn.dataset.show;railBtn.classList.toggle('active',show);}if(save&&ST)ST.set('rail',show?'1':'0');}"
			+ "if(railBtn&&rail){railBtn.addEventListener('click',function(){setRail(root.classList.contains('mdmx-norail'),true);relimit();});var shown=true;shown=!ST||ST.get('rail')!=='0';setRail(shown,false);}"
			// the workspace is resized whenever the EBX menu folds: a new column count asks again for two full rows per group
			+ "var rz=0;window.addEventListener('resize',function(){clearTimeout(rz);rz=setTimeout(relimit,150);});"
			+ "root.mdmxFacets={select:function(k,v){select(k,v);if(more&&more.querySelector('[data-filter=\"'+k+'\"]'))setMore(true);},changed:changed,apply:paint};refresh();})();";
	}
}
