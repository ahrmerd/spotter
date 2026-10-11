// Isolated world, document_start: tells adblock.js (page world) whether the blocker is on for this site.
(() => {
  const apply = (s) => {
    const host = location.hostname;
    const off = s.adblock === false || (s.adblockOff || []).some((h) => host === h || host.endsWith('.' + h));
    document.documentElement.dataset.spotterAb = off ? 'off' : 'on';
  };
  chrome.storage.local.get(['adblock', 'adblockOff']).then(apply).catch(() => {});
  chrome.storage.onChanged.addListener(() => chrome.storage.local.get(['adblock', 'adblockOff']).then(apply).catch(() => {}));
})();
