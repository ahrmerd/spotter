package dev.ahmed.spotter.browser

/** Injected at page finish. Reports `<video>`/`<audio>` src to the one-method JS bridge. */
object SnifferJs {
    const val SOURCE = """
(function(){
  if (window.__spotterSniff) return;
  window.__spotterSniff = true;
  function report(el, play) {
    try {
      var src = el.currentSrc || el.getAttribute('src') || '';
      if (!src) {
        var s = el.querySelector('source');
        if (s) src = s.src || s.getAttribute('src') || '';
      }
      if (!src) return;
      if (window.SpotterSniffer && SpotterSniffer.media) {
        SpotterSniffer.media(String(src), play ? 'play' : '');
      }
    } catch (e) {}
  }
  function scan() {
    var list = document.querySelectorAll('video,audio');
    for (var i = 0; i < list.length; i++) {
      var el = list[i];
      report(el, false);
      if (!el.__spotterPlay) {
        el.__spotterPlay = true;
        el.addEventListener('play', function(){ report(this, true); });
      }
    }
  }
  scan();
  var root = document.documentElement || document.body;
  if (!root || !window.MutationObserver) return;
  var obs = new MutationObserver(scan);
  obs.observe(root, {subtree:true, childList:true, attributes:true, attributeFilter:['src']});
})();
"""
}
