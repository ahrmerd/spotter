const $ = s => document.querySelector(s);
const post = (p, b) => fetch(p, {method: "POST", headers: {"Content-Type": "application/json"}, body: JSON.stringify(b || {})});
const fmt = n => { if (!n) return "0 B"; const u = ["B","KB","MB","GB"]; let i = 0; while (n >= 1024 && i < 3) { n /= 1024; i++; } return n.toFixed(i ? 1 : 0) + " " + u[i]; };
const esc = s => String(s ?? "").replace(/[&<>"]/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;",'"':"&quot;"}[c]));

$("#f").onsubmit = async e => { e.preventDefault(); await post("/api/add", {url: $("#u").value.trim()}); $("#u").value = ""; load(); };
$("#list").onclick = async e => {
  const b = e.target.closest("button[data-a]"); if (!b) return;
  await post(`/api/${b.dataset.id}/${b.dataset.a}`); load();
};

async function load() {
  const items = await (await fetch("/api/downloads")).json();
  $("#list").innerHTML = items.map(d => {
    const pct = d.size ? Math.min(100, d.downloaded / d.size * 100) : (d.status === "done" ? 100 : 0);
    const name = d.filename || d.url;
    const btn = (a, t) => `<button class="s" data-id="${d.id}" data-a="${a}">${t}</button>`;
    const acts = d.status === "downloading" ? btn("pause", "Pause")
      : d.status === "paused" || d.status === "error" ? btn("resume", "Resume") : "";
    return `<div class="item"><div class="top"><div class="name" title="${esc(d.url)}">${esc(name)}</div>
      <div class="act">${acts}${btn("remove", "Remove")}${d.status === "done" ? btn("delete", "Delete file") : ""}</div></div>
      <div class="bar"><i style="width:${pct}%"></i></div>
      <div class="meta">${d.status}${d.status === "downloading" ? " · " + fmt(d.speed) + "/s" : ""} · ${fmt(d.downloaded)}${d.size ? " / " + fmt(d.size) : ""}
      ${d.error ? `<span class="err"> · ${esc(d.error)}</span>` : ""}</div></div>`;
  }).join("") || '<p class="meta">No downloads yet.</p>';
}
load(); setInterval(load, 1000);
