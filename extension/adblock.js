// Runs in the page (world MAIN) at document_start, in every frame.
// Stops what filter lists cannot: the tap that opens an ad window or sends the tab to an ad network
// (seen on animepahe.pw: the first tap on the player), and invisible full-screen click catchers.
(() => {
  if (window.__spotterAdblock) return;
  window.__spotterAdblock = true;

  const site = (h) => h.split('.').slice(-2).join('.');
  let lastLinkTap = 0;
  const onTap = (e) => {
    if (!e.isTrusted) return;
    const a = e.target && e.target.closest && e.target.closest('a[href]');
    if (a) lastLinkTap = Date.now();
  };
  addEventListener('pointerdown', onTap, true);
  addEventListener('click', onTap, true);
  addEventListener('keydown', (e) => { if (e.isTrusted && e.key === 'Enter') lastLinkTap = Date.now(); }, true);
  const recentLinkTap = () => Date.now() - lastLinkTap < 1500;

  // window.open is allowed only right after a real tap on a link.
  const realOpen = window.open;
  window.open = function (url, ...rest) {
    if (recentLinkTap()) return realOpen.call(this, url, ...rest);
    console.debug('[spotter-adblock] blocked window.open', url);
    return null;
  };

  // Top-frame navigation to another site without a tap on a link is cancelled.
  if (window.top === window && window.navigation && window.navigation.addEventListener) {
    navigation.addEventListener('navigate', (e) => {
      try {
        if (!e.cancelable || e.navigationType === 'reload' || e.navigationType === 'traverse') return;
        const to = new URL(e.destination.url);
        if (to.protocol !== 'http:' && to.protocol !== 'https:') return;
        if (site(to.hostname) === site(location.hostname)) return;
        if (e.userInitiated && recentLinkTap()) return;
        if (recentLinkTap()) return;
        e.preventDefault();
        console.debug('[spotter-adblock] blocked navigation', to.href);
      } catch (_) { /* never break the page */ }
    });
  }

  // Invisible full-screen layer above the page that is not a player: remove it.
  const sweep = () => {
    const vw = innerWidth, vh = innerHeight;
    if (!document.body) return;
    for (const el of document.body.querySelectorAll('div,a,span,section,ins,iframe')) {
      if (el.__spotterSeen) continue;
      const cs = getComputedStyle(el);
      if (cs.position !== 'fixed' && cs.position !== 'absolute') continue;
      const r = el.getBoundingClientRect();
      if (r.width < vw * 0.85 || r.height < vh * 0.6) continue;
      const z = parseInt(cs.zIndex, 10);
      if (isNaN(z) || z < 100) continue;
      if ((el.innerText || '').trim() || (el.querySelector && el.querySelector('video,audio,canvas,iframe[src*="player"],iframe[src*="embed"]'))) {
        el.__spotterSeen = true; continue;
      }
      const clear = cs.backgroundColor === 'rgba(0, 0, 0, 0)' || cs.backgroundColor === 'transparent' || parseFloat(cs.opacity) < 0.05;
      if (clear || el.tagName === 'IFRAME' || el.tagName === 'A') el.remove(); else el.__spotterSeen = true;
    }
  };
  let timer = null;
  const start = () => {
    new MutationObserver(() => { if (!timer) timer = setTimeout(() => { timer = null; sweep(); }, 400); })
      .observe(document.documentElement, { childList: true, subtree: true });
    sweep();
  };
  if (document.readyState === 'loading') document.addEventListener('DOMContentLoaded', start); else start();
})();
