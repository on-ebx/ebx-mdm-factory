package com.on.ebx.factory.ebx.ui;

import java.util.Locale;

import com.on.ebx.factory.ebx.Favorites;

/**
 * The favorite star of the project explorer: the button of a card, the client script that toggles it through
 * the page's AJAX endpoint ({@code #mdmxFavEndpoint}), and the localized error texts. Ported from
 * ebx-connectors' class of the same name (2026-09-23, at the product owner's request: "to be consistent" with the
 * connector explorer) - a faithful copy, with only the CSS/element vocabulary renamed to the factory's own
 * ({@code ftcx-fav} &rarr; {@code mdmx-fav}, {@code ftcc-card} &rarr; {@code mdmc-card}, {@code ftFacets}
 * &rarr; {@code mdmxFacets}) and this module's own {@link FactoryHtml#esc}/{@link Messages} used for escaping
 * and localization instead of ebx-connectors' equivalents.
 * <p>
 * Unlike the connector explorer's card (a single whole-card {@code <a>}, so the star has to be an absolutely
 * positioned overlay button sitting outside that link), a project card ({@link ProjectExplorerPage#card}) has
 * no whole-card link - only the Wizard/Cockpit {@code .mdmc-btn} buttons navigate - so here the star is a
 * plain sibling of the status chip and code in {@code .mdmc-meta}, laid out inline by flex, never absolutely
 * positioned (see {@code factory/explorer.css}). It still never navigates and never arms {@code
 * FactoryHtml#ORIGIN_KEY}: its class ({@code mdmx-fav}) matches neither selector ({@code .mdmc-btn},
 * {@code .mdmx-newproject}) that {@code FactoryHtml#originCaptureScript} arms.
 */
final class FavoriteStars
{
	private FavoriteStars()
	{
	}

	/** The star of one card. {@code pType} is a {@link Favorites} kind, {@code pKey} the primary key of the target. */
	static String button(final Locale pLocale, final String pType, final String pKey, final boolean pOn)
	{
		final String add = Messages.get(FavoriteStars.class, pLocale, "favorite.add");
		final String remove = Messages.get(FavoriteStars.class, pLocale, "favorite.remove");
		return "<button type=\"button\" class=\"mdmx-fav" + (pOn ? " on" : "") + "\" data-type=\"" + FactoryHtml.esc(pType) + "\" data-key=\"" + FactoryHtml.esc(pKey) + "\" aria-pressed=\"" + pOn
			+ "\" title=\"" + FactoryHtml.esc(pOn ? remove : add) + "\" data-add=\"" + FactoryHtml.esc(add) + "\" data-remove=\"" + FactoryHtml.esc(remove) + "\">" + (pOn ? "&#9733;" : "&#9734;") + "</button>";
	}

	/** Only the limit message takes an argument: MessageFormat would eat the apostrophes of the others. */
	static String errorText(final Locale pLocale, final String pCode)
	{
		final String key = "favorite.error." + pCode;
		return Favorites.ERR_LIMIT.equals(pCode) ? Messages.get(FavoriteStars.class, pLocale, key, Integer.valueOf(Favorites.MAX_PER_USER)) : Messages.get(FavoriteStars.class, pLocale, key);
	}

	/**
	 * Script fragment for a page whose script has {@code root} (the page element) and {@code F} (its {@code
	 * mdmxFacets}) in scope: a click on a star calls the endpoint with {@code type} and {@code key}, then flips
	 * the star, the card's {@code data-fav} and re-applies the filters.
	 */
	static final String JS = "root.querySelectorAll('.mdmx-fav').forEach(function(b){b.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();if(b.disabled)return;b.disabled=true;"
		+ "var card=b.closest('.mdmc-card'),ep=document.getElementById('mdmxFavEndpoint'),u=new URL(ep.dataset.url,window.top.location.href);u.searchParams.set('key',b.dataset.key);u.searchParams.set('type',b.dataset.type);"
		+ "var h=new EBX_AJAXResponseHandler();"
		+ "h.handleAjaxResponseSuccess=function(t){b.disabled=false;var d;try{d=JSON.parse(t);}catch(x){return;}"
		+ "if(d.error){window.alert(d.error);return;}"
		+ "b.classList.toggle('on',d.favorite);b.setAttribute('aria-pressed',d.favorite?'true':'false');b.textContent=d.favorite?'\\u2605':'\\u2606';b.title=d.favorite?b.dataset.remove:b.dataset.add;card.dataset.fav=d.favorite?'1':'0';F.changed();};"
		+ "h.handleAjaxResponseFailed=function(){b.disabled=false;};h.sendRequest(u.href);});});";

	/**
	 * Server-paged explorer (2026-10-11): the same toggle, listened to once on {@code root} instead of on each star, because the cards are replaced
	 * by every answer of the "query" request. After the toggle, {@code F.changed()} asks the server again (the "My favorites" counts move).
	 */
	static final String DELEGATED_JS = "root.addEventListener('click',function(e){var b=e.target.closest?e.target.closest('.mdmx-fav'):null;if(!b||!root.contains(b))return;e.preventDefault();e.stopPropagation();if(b.disabled)return;b.disabled=true;"
		+ "var card=b.closest('.mdmc-card'),ep=document.getElementById('mdmxFavEndpoint'),u=new URL(ep.dataset.url,window.top.location.href);u.searchParams.set('key',b.dataset.key);u.searchParams.set('type',b.dataset.type);"
		+ "var h=new EBX_AJAXResponseHandler();"
		+ "h.handleAjaxResponseSuccess=function(t){b.disabled=false;var d;try{d=JSON.parse(t);}catch(x){return;}"
		+ "if(d.error){window.alert(d.error);return;}"
		+ "b.classList.toggle('on',d.favorite);b.setAttribute('aria-pressed',d.favorite?'true':'false');b.textContent=d.favorite?'\\u2605':'\\u2606';b.title=d.favorite?b.dataset.remove:b.dataset.add;if(card)card.dataset.fav=d.favorite?'1':'0';F.changed();};"
		+ "h.handleAjaxResponseFailed=function(){b.disabled=false;};h.sendRequest(u.href);});";
}
