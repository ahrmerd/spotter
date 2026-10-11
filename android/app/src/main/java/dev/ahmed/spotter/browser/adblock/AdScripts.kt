package dev.ahmed.spotter.browser.adblock

import org.json.JSONObject

object AdScripts {
    /**
     * Injected after the page starts loading and again when it finishes. Idempotent. The overlay rule removes an
     * element that covers most of the screen, sits above the page, shows nothing and is not a player: that is the
     * transparent layer streaming sites use to turn the first tap into an ad click.
     */
    fun build(css: String): String = """
(function(){
  try {
    var css = ${JSONObject.quote(css)};
    if (css && !document.getElementById('__spotter_ab_css')) {
      var st = document.createElement('style');
      st.id = '__spotter_ab_css';
      st.textContent = css;
      (document.head || document.documentElement).appendChild(st);
    }
    if (window.__spotterAb) { window.__spotterAb(); return; }
    function visibleText(el){ return (el.innerText || '').trim().length > 0; }
    function isPlayer(el){
      return !!(el.querySelector && el.querySelector('video,audio,iframe[src*="player"],iframe[src*="embed"],canvas')) || el.tagName === 'VIDEO';
    }
    function sweep(){
      var vw = window.innerWidth, vh = window.innerHeight;
      var all = document.body ? document.body.querySelectorAll('div,a,span,section,ins,iframe') : [];
      for (var i = 0; i < all.length; i++) {
        var el = all[i];
        if (el.__spotterSeen) continue;
        var cs = getComputedStyle(el);
        if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
        var r = el.getBoundingClientRect();
        if (r.width < vw * 0.85 || r.height < vh * 0.6) continue;
        var z = parseInt(cs.zIndex, 10);
        if (isNaN(z) || z < 100) continue;
        if (isPlayer(el) || visibleText(el)) { el.__spotterSeen = true; continue; }
        var bg = cs.backgroundColor;
        var clear = (bg === 'rgba(0, 0, 0, 0)' || bg === 'transparent' || parseFloat(cs.opacity) < 0.05);
        if (clear || el.tagName === 'IFRAME' || el.tagName === 'A') { el.remove(); }
        else { el.__spotterSeen = true; }
      }
    }
    window.__spotterAb = sweep;
    sweep();
    var t = null;
    new MutationObserver(function(){ if (t) return; t = setTimeout(function(){ t = null; sweep(); }, 400); })
      .observe(document.documentElement, {childList: true, subtree: true});
  } catch (e) {}
})();
""".trimIndent()
}
