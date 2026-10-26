"use strict";
(() => {
  const $ = (id) => document.getElementById(id);
  const ui = { msg: $("msg"), live: $("live"), v: $("v"), start: $("start"), stop: $("stop"),
               count: $("count"), last: $("last"), mode: $("mode") };
  let csrf = null, epoch = 0, cfg = { width: 1280, quality: 0.62, interval_ms: 3000, mode: "normal" };
  let stream = null, seq = 0, inFlight = false, timer = null, wakeLock = null, polling = false, lastOk = 0;

  // The pairing code lives only in the URL fragment; remove it from the address bar immediately.
  const code = new URLSearchParams(location.hash.slice(1)).get("c");
  history.replaceState(null, "", location.pathname);

  const say = (t) => { ui.msg.textContent = t; };
  const setLive = (cls, text) => { ui.live.className = "badge " + cls; ui.live.textContent = text; };
  const applyCfg = (c) => { Object.assign(cfg, c); if (c.epoch !== undefined) epoch = Math.max(epoch, c.epoch); ui.mode.textContent = cfg.mode; };

  async function api(path, opts = {}) {
    const headers = Object.assign({}, opts.headers || {});
    if (csrf) headers["X-CSRF"] = csrf;
    return fetch(path, Object.assign({ credentials: "same-origin", cache: "no-store" }, opts, { headers }));
  }

  async function pair() {
    if (!code) { say("No pairing code. Scan the QR code shown on the computer again."); return; }
    const r = await fetch("/api/pair", { method: "POST", headers: { "Content-Type": "application/json" },
                                         body: JSON.stringify({ code }) });
    if (!r.ok) { say("Pairing failed or expired. Ask for a new QR code."); return; }
    const j = await r.json();
    csrf = j.csrf; applyCfg(j);
    say("Paired. Tap Start camera, then point the rear camera at the TV.");
    ui.start.disabled = false;
  }

  async function status(note) {
    if (!csrf) return;
    try {
      await api("/api/status", { method: "POST", keepalive: true, headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ visible: document.visibilityState === "visible", camera: !!stream, note }) });
    } catch (_) {}
  }

  async function start() {
    try {
      stream = await navigator.mediaDevices.getUserMedia({ audio: false,
        video: { facingMode: { ideal: "environment" }, width: { ideal: 1920 }, height: { ideal: 1080 } } });
    } catch (e) { say("Camera unavailable: " + e.name + ". Allow camera access and retry."); return; }
    ui.v.srcObject = stream;
    stream.getVideoTracks().forEach((t) => t.addEventListener("ended", () => { say("Camera interrupted."); stopLocal("camera ended"); }));
    ui.start.disabled = true; ui.stop.disabled = false;
    setLive("on", "LIVE");
    say("Streaming frames to your computer.");
    try { wakeLock = await navigator.wakeLock?.request("screen"); } catch (_) {}
    status("started");
    schedule(500);
    pollEpoch();
  }

  function schedule(ms) { clearTimeout(timer); if (stream) timer = setTimeout(capture, ms); }

  // Desktop browsers keep camera tracks running when the window is covered (the page reports "hidden"),
  // so capture regardless; if a browser does freeze the page, the server-side freshness check catches it.
  let again = false;
  async function grab() {
    const track = stream.getVideoTracks()[0];
    if (window.ImageCapture) {
      try { return await new ImageCapture(track).grabFrame(); } catch (_) {}
    }
    return ui.v.videoWidth ? ui.v : null;
  }

  async function capture() {
    if (!stream) return;
    if (inFlight) { again = true; return; }
    const track = stream.getVideoTracks()[0];
    if (!track || track.readyState !== "live") { schedule(1000); return; }
    inFlight = true;
    const seenEpoch = epoch;              // epoch known at the instant of capture
    const src = await grab();
    if (!src) { inFlight = false; schedule(1000); return; }
    const sw = src.videoWidth || src.width, sh = src.videoHeight || src.height;
    const w = Math.min(cfg.width, sw), h = Math.round(w * sh / sw);
    const c = document.createElement("canvas"); c.width = w; c.height = h;
    c.getContext("2d").drawImage(src, 0, 0, w, h);
    const blob = await new Promise((res) => c.toBlob(res, "image/jpeg", cfg.quality));
    const mySeq = ++seq;
    try {
      const r = await api("/api/frame", { method: "POST", body: blob, headers: {
        "Content-Type": "image/jpeg", "X-Frame-Seq": String(mySeq), "X-Seen-Epoch": String(seenEpoch),
        "X-Capture-Meta": JSON.stringify({ w, h, mode: cfg.mode }) } });
      if (r.status === 401) { say("Session ended. Scan a new QR code to resume."); stopLocal("unpaired"); return; }
      if (r.ok) { applyCfg(await r.json()); lastOk = Date.now(); ui.count.textContent = String(mySeq); }
    } catch (_) { say("Upload failed; retrying…"); }
    finally { inFlight = false; }
    if (again) { again = false; return capture(); }
    schedule(cfg.interval_ms);
  }

  async function pollEpoch() {
    if (polling) return; polling = true;
    while (stream && csrf) {
      try {
        const r = await api("/api/epoch?known=" + epoch + "&mode=" + encodeURIComponent(cfg.mode));
        if (r.status === 401) break;
        if (r.ok) {
          const j = await r.json(); const changed = j.epoch !== epoch || j.mode !== cfg.mode;
          applyCfg(j); if (changed) capture();   // capture straight from the network callback (not throttled like timers)
        } else { await new Promise((r) => setTimeout(r, 2000)); }
      } catch (_) { await new Promise((r) => setTimeout(r, 2000)); }
    }
    polling = false;
  }

  function stopLocal(note) {
    clearTimeout(timer);
    if (stream) stream.getTracks().forEach((t) => t.stop());
    stream = null; ui.v.srcObject = null;
    try { wakeLock?.release(); } catch (_) {}
    ui.stop.disabled = true; setLive("off", "OFF");
    status(note);
  }

  async function stopAll() {
    stopLocal("stopped");
    try { await api("/api/stop", { method: "POST" }); } catch (_) {}
    csrf = null; ui.start.disabled = true;
    say("Camera stopped and session revoked. Scan a new QR code to pair again.");
  }

  setInterval(() => {
    ui.last.textContent = lastOk ? Math.round((Date.now() - lastOk) / 1000) + " s ago" : "—";
    if (stream && lastOk && Date.now() - lastOk > 12000) setLive("warn", "STALE");
    else if (stream) setLive("on", "LIVE");
  }, 1000);

  document.addEventListener("visibilitychange", async () => {
    status(document.visibilityState);
    if (document.visibilityState === "visible" && stream) {
      try { wakeLock = await navigator.wakeLock?.request("screen"); } catch (_) {}
      schedule(200);
    }
  });

  ui.start.addEventListener("click", start);
  ui.stop.addEventListener("click", stopAll);
  pair();
})();
