const fmt = n => !n || n < 1e5 ? "" : n > 1e9 ? (n/1e9).toFixed(1)+" GB" : (n/1e6).toFixed(1)+" MB";
const nameOf = u => { try { return decodeURIComponent(new URL(u).pathname.split("/").pop()) || u; } catch { return u; } };
(async () => {
  const [t0] = await chrome.tabs.query({active: true, currentWindow: true});
  let host = ""; try { host = new URL(t0.url).hostname; } catch {}
  const st = await chrome.storage.local.get(["adblock", "adblockOff"]);
  const on = document.getElementById("abOn"), off = document.getElementById("abOff");
  const list = () => st.adblockOff || [];
  on.checked = st.adblock !== false;
  off.checked = !!host && list().includes(host);
  document.getElementById("abSite").textContent = host || "no site";
  off.disabled = !host || !on.checked;
  on.onchange = async () => { await chrome.storage.local.set({adblock: on.checked}); off.disabled = !host || !on.checked; };
  off.onchange = async () => {
    const next = new Set(list()); off.checked ? next.add(host) : next.delete(host);
    st.adblockOff = [...next]; await chrome.storage.local.set({adblockOff: st.adblockOff});
  };
})();
(async () => {
  const [tab] = await chrome.tabs.query({active: true, currentWindow: true});
  const items = await chrome.runtime.sendMessage({type: "list", tabId: tab.id});
  const l = document.getElementById("l");
  if (!items.length) { l.innerHTML = "<p>No videos detected yet. Play the video, then reopen this.</p>"; return; }
  items.sort((a, b) => (b.at || 0) - (a.at || 0));
  for (const it of items) {
    const row = document.createElement("div"); row.className = "row";
    const n = document.createElement("div"); n.className = "n";
    const stream = /m3u8|mpd|mpegurl|dash/i.test(it.type + it.url);
    // Editable name, prefilled with the page title (or the file's own name if there's no title).
    const b = document.createElement("input"); b.className = "name"; b.title = it.url;
    const fileName = nameOf(it.url), dot = fileName.lastIndexOf(".");
    const ext = !stream && dot > 0 && fileName.length - dot <= 5 ? fileName.slice(dot) : "";
    b.value = (it.title?.replace(/^\(\d+\)\s*/, "").replace(/\s+[-|–]\s+(YouTube|Vimeo|TikTok|X|Twitter)$/i, "") || fileName.slice(0, ext ? dot : undefined)).replace(/[\\/:*?"<>|]+/g, " ").replace(/\s+/g, " ").trim().slice(0, 150);
    b.spellcheck = false;
    const s = document.createElement("small"); s.textContent = [stream ? "HLS/DASH stream" : it.type.split(";")[0], fmt(it.size), it.at ? new Date(it.at).toLocaleTimeString() : ""].filter(Boolean).join(" · ");
    n.append(b, s);
    const btn = document.createElement("button"); btn.textContent = "Download";
    btn.onclick = async () => {
      btn.disabled = true;
      const r = await chrome.runtime.sendMessage({type: "download", item: {...it, filename: b.value.trim() ? b.value.trim() + ext : undefined}});
      btn.textContent = r?.ok ? (r.via === "browser" ? "Saving in browser ✓" : "Added ✓") : "Needs the desktop manager";
      if (!r?.ok) btn.disabled = false;
    };
    row.append(n, btn); l.append(row);
  }
})();
