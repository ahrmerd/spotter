const API = "http://127.0.0.1:6800";
let found = {}; // tabId -> {url: {url, type, size, referer, title}}
// MV3 service workers get killed when idle, so keep detections in session storage.
const ready = (chrome.storage.session?.get("found") ?? Promise.resolve({})).then(r => { found = Object.assign(r.found || {}, found); });
const persist = () => chrome.storage.session?.set({found}).catch(() => {});
const SITE = /^https?:\/\/([^/]*\.)?(youtube\.com\/(watch|shorts)|youtu\.be\/|vimeo\.com\/\d|twitter\.com\/.+\/status|x\.com\/.+\/status|tiktok\.com\/.+\/video|facebook\.com\/.+\/videos|instagram\.com\/(p|reel)|dailymotion\.com\/video|twitch\.tv\/(videos|[^/]+\/clip))/i;

const MEDIA_EXT = /\.(mp4|webm|mkv|mov|m4v|flv|mp3|m4a|ogg|m3u8|mpd)(\?|$)/i;
const MEDIA_CT = /^(video\/|audio\/|application\/(vnd\.apple\.mpegurl|x-mpegurl|dash\+xml))/i;

function addPage(tab) {
  if (!tab.url || !SITE.test(tab.url)) return;
  (found[tab.id] ||= {})[tab.url] = {url: tab.url, type: "page (via yt-dlp)", size: 0, referer: tab.url, title: tab.title};
  badge(tab.id);
}

function badge(tabId) {
  persist();
  const n = Object.keys(found[tabId] || {}).length;
  chrome.action.setBadgeText({tabId, text: n ? String(n) : ""});
  chrome.action.setBadgeBackgroundColor({tabId, color: "#2563eb"});
}

// Remember the headers the browser really sent for media requests (the Referer/Origin often
// come from an embedded player iframe, not the top-level page, and CDNs 403 without them).
const sent = {};
const KEEP = ["referer", "origin", "cookie", "user-agent", "authorization"];
chrome.webRequest.onSendHeaders.addListener(d => {
  if (!MEDIA_EXT.test(d.url)) return;
  const h = {};
  for (const x of d.requestHeaders) if (KEEP.includes(x.name.toLowerCase())) h[x.name] = x.value;
  sent[d.url] = h;
  const keys = Object.keys(sent);
  if (keys.length > 300) delete sent[keys[0]];
}, {urls: ["<all_urls>"]}, ["requestHeaders", "extraHeaders"]);

chrome.webRequest.onHeadersReceived.addListener(async d => {
  await ready;
  if (/googlevideo\.com\/videoplayback/.test(d.url)) return; // YouTube chunks: handled by page entry
  if (d.tabId < 0 || d.statusCode >= 300 || d.method !== "GET") return;
  const h = n => (d.responseHeaders.find(x => x.name.toLowerCase() === n) || {}).value || "";
  const ct = h("content-type"), len = parseInt(h("content-length") || "0", 10);
  const byType = MEDIA_CT.test(ct) && !/mp2t/.test(ct), byExt = MEDIA_EXT.test(d.url);
  if (!byType && !byExt) return;
  if (!/m3u8|mpd|mpegurl|dash/i.test(ct + d.url) && len && len < 100 * 1024) return; // skip tiny fragments
  if (/\.(ts|m4s)(\?|$)/i.test(d.url)) return; // HLS/DASH segments
  const t = (found[d.tabId] ||= {});
  if (t[d.url]) return;
  chrome.tabs.get(d.tabId).then(tab => {
    t[d.url] = {url: d.url, type: ct || "stream", size: len, at: Date.now(), headers: sent[d.url], referer: tab.url, title: tab.title};
    badge(d.tabId);
  }).catch(() => {});
}, {urls: ["<all_urls>"]}, ["responseHeaders"]);

chrome.tabs.onUpdated.addListener(async (id, info, tab) => {
  await ready;
  if (info.url || info.status === "loading") { delete found[id]; badge(id); addPage(tab); }
  else if (info.title) addPage(tab); // SPA sites set the title after navigating
});
chrome.tabs.onRemoved.addListener(id => { delete found[id]; persist(); });

async function send(item) {
  const headers = {...(item.headers || {})};
  if (!Object.keys(headers).length && item.referer) headers["Referer"] = item.referer;
  if (!headers.Cookie && !headers.cookie && !item.type?.startsWith("page")) try {
    const cookies = await chrome.cookies?.getAll({url: item.url});
    if (cookies?.length) headers["Cookie"] = cookies.map(c => `${c.name}=${c.value}`).join("; ");
  } catch {}
  try {
    const r = await fetch(API + "/api/add", {
      method: "POST", headers: {"Content-Type": "application/json"},
      body: JSON.stringify({url: item.url, headers, filename: item.filename}),
    });
    if (!r.ok) throw new Error("manager returned " + r.status);
    return "manager";
  } catch (e) {
    // Manager not running: plain files can still go through the browser's own downloader.
    const plain = /^https?:/i.test(item.url) && !item.type?.startsWith("page") && !/m3u8|mpd|mpegurl|dash/i.test((item.type || "") + item.url);
    if (!plain || !chrome.downloads) throw e;
    await chrome.downloads.download({url: item.url, filename: item.filename || undefined});
    return "browser";
  }
}

chrome.runtime.onMessage.addListener((msg, _s, reply) => {
  if (msg.type === "list") ready.then(() => reply(Object.values(found[msg.tabId] || {})));
  else if (msg.type === "download") send(msg.item).then(via => reply({ok: true, via}), e => reply({error: String(e)}));
  return true;
});

chrome.runtime.onInstalled.addListener(() => {
  chrome.contextMenus.create({id: "dl", title: "Download with Spotter", contexts: ["link", "video", "audio"]});
});
chrome.contextMenus.onClicked.addListener(info => {
  const url = info.linkUrl || info.srcUrl;
  if (url) send({url, referer: info.pageUrl}).catch(() => {});
});


// ---- Ad blocker switch (popup toggles chrome.storage.local: adblock = true/false, adblockOff = [hosts]) ----
async function applyAdblock() {
  const s = await chrome.storage.local.get(["adblock", "adblockOff"]);
  const on = s.adblock !== false, off = s.adblockOff || [];
  await chrome.declarativeNetRequest.updateEnabledRulesets(on ? {enableRulesetIds: ["ads"]} : {disableRulesetIds: ["ads"]});
  // Sites switched off: allow everything they request and everything requested from them.
  const rules = off.length ? [
    {id: 1, priority: 2, action: {type: "allow"}, condition: {initiatorDomains: off}},
    {id: 2, priority: 2, action: {type: "allow"}, condition: {requestDomains: off}},
  ] : [];
  await chrome.declarativeNetRequest.updateDynamicRules({removeRuleIds: [1, 2], addRules: rules});
}
chrome.storage.onChanged.addListener((c, area) => { if (area === "local" && (c.adblock || c.adblockOff)) applyAdblock(); });
chrome.runtime.onInstalled.addListener(applyAdblock);
chrome.runtime.onStartup.addListener(applyAdblock);
applyAdblock().catch(() => {});
