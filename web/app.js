/* pc-remote phone client */
(() => {
  const $ = (id) => document.getElementById(id);
  const login = $("login"), app = $("app"), canvas = $("screen"), ctx = canvas.getContext("2d");
  const view = $("view"), offline = $("offline"), connecting = $("connecting"), connMsg = $("connMsg");
  const dot = $("dot"), stateEl = $("state"), subEl = $("sub"), cursorEl = $("cursor"), zoomBadge = $("zoomBadge");
  const kbPanel = $("kbPanel"), kbInput = $("kbInput"), menu = $("menu"), toast = $("toast");
  const splash = $("splash"), ripples = $("ripples"), signal = $("signal");
  const splashShownAt = Date.now();
  function hideSplash(then) {
    // keep the intro on screen for at least 1.4 s so the animation completes
    const wait = Math.max(0, 1400 - (Date.now() - splashShownAt));
    setTimeout(() => { splash.classList.add("out"); setTimeout(() => (splash.hidden = true), 520); then && then(); }, wait);
  }
  function ripple(x, y, right) {
    const r = document.createElement("div"); r.className = "ripple" + (right ? " r" : "");
    const b = view.getBoundingClientRect(); r.style.left = (x - b.left) + "px"; r.style.top = (y - b.top) + "px";
    ripples.appendChild(r); setTimeout(() => r.remove(), 500);
  }

  // The Android app hands the secret over in the URL hash.
  let secret = localStorage.getItem("pcr_secret") || "";
  if (location.hash.length > 1) {
    secret = decodeURIComponent(location.hash.slice(1));
    history.replaceState(null, "", location.pathname);
  }
  let ws = null, pcOnline = false, pcHost = "", pcAudio = false, frameW = 0, frameH = 0;
  let base = 1, zoom = 1, panX = 0, panY = 0;
  let toastTimer = null, frames = 0, bytes = 0, lastFrameAt = 0, latency = 0, pendingWake = false;
  let profile = localStorage.getItem("pcr_profile") || "normal";
  let audioOn = false;

  // ------------------------------------------------------------ helpers
  function show(msg, ms = 2500) {
    toast.textContent = msg; toast.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => (toast.hidden = true), ms);
  }
  const send = (obj) => { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); };
  function setState(text, cls, sub = "") { stateEl.textContent = text; dot.className = "dot " + cls; subEl.textContent = sub; }
  // ---- preferences: accent colour, sounds, haptics
  const prefs = JSON.parse(localStorage.getItem("pcr_prefs") || "{}");
  const savePrefs = () => localStorage.setItem("pcr_prefs", JSON.stringify(prefs));
  const applyAccent = () => {
    const a = prefs.accent || "#4f8cff";
    document.documentElement.style.setProperty("--accent", a);
    document.documentElement.style.setProperty("--accent2", a + "cc");
    document.querySelectorAll("#accent button").forEach((b) => b.classList.toggle("on", b.dataset.accent === a));
  };
  applyAccent();
  const applyTheme = () => {
    const t = prefs.theme || "midnight";
    document.documentElement.dataset.theme = t;
    document.querySelector('meta[name="theme-color"]').content = getComputedStyle(document.documentElement).getPropertyValue("--bg").trim() || "#0f1117";
    document.querySelectorAll("#theme button").forEach((b) => b.classList.toggle("on", b.dataset.theme === t));
  };
  applyTheme();
  // background parallax from device tilt (or finger position as a fallback)
  function tilt(x, y) {
    document.querySelectorAll(".bg").forEach((bg) => { bg.style.setProperty("--tx", (x * 18) + "px"); bg.style.setProperty("--ty", (y * 18) + "px"); });
  }
  window.addEventListener("deviceorientation", (e) => {
    if (e.gamma == null) return;
    tilt(Math.max(-1, Math.min(1, e.gamma / 30)), Math.max(-1, Math.min(1, (e.beta - 45) / 30)));
  }, { passive: true });
  document.addEventListener("touchmove", (e) => {
    if (!e.target.closest(".screen, .center, #splash")) return;
    const t = e.touches[0]; tilt((t.clientX / innerWidth - .5) * 2, (t.clientY / innerHeight - .5) * 2);
  }, { passive: true });

  // event pill under the top bar
  let pillTimer = null;
  function pill(text, warn = false, ms = 2600) {
    $("pillText").textContent = text; $("pill").classList.toggle("warn", warn); $("pill").classList.add("show");
    clearTimeout(pillTimer); pillTimer = setTimeout(() => $("pill").classList.remove("show"), ms);
  }
  // confetti burst (PC came online)
  function confetti() {
    const c = $("confetti"), cx = c.getContext("2d");
    c.width = view.clientWidth; c.height = view.clientHeight;
    const cols = [getComputedStyle(document.documentElement).getPropertyValue("--accent").trim(), "#38d070", "#f5b84a", "#ff4f8b", "#fff"];
    const ps = Array.from({ length: 90 }, () => ({ x: c.width / 2, y: c.height * .45, vx: (Math.random() - .5) * 14, vy: -Math.random() * 12 - 4,
      s: 4 + Math.random() * 5, r: Math.random() * 6.28, vr: (Math.random() - .5) * .3, col: cols[(Math.random() * cols.length) | 0] }));
    let t0 = performance.now();
    (function frame(now) {
      const dt = Math.min(32, now - t0) / 16; t0 = now;
      cx.clearRect(0, 0, c.width, c.height);
      let alive = 0;
      for (const p of ps) {
        p.vy += .35 * dt; p.x += p.vx * dt; p.y += p.vy * dt; p.r += p.vr * dt; p.vx *= .99;
        if (p.y < c.height + 20) alive++;
        cx.save(); cx.translate(p.x, p.y); cx.rotate(p.r); cx.fillStyle = p.col; cx.fillRect(-p.s / 2, -p.s / 4, p.s, p.s / 2); cx.restore();
      }
      if (alive) requestAnimationFrame(frame); else cx.clearRect(0, 0, c.width, c.height);
    })(t0);
  }
  // latency sparkline in the menu
  const latHist = [];
  function drawSpark() {
    const c = $("spark"), cx = c.getContext("2d"); cx.clearRect(0, 0, c.width, c.height);
    if (latHist.length < 2) return;
    const max = Math.max(100, ...latHist), w = c.width, h = c.height;
    cx.beginPath();
    latHist.forEach((v, i) => { const x = (i / (latHist.length - 1)) * w, y = h - 4 - (v / max) * (h - 8); i ? cx.lineTo(x, y) : cx.moveTo(x, y); });
    cx.strokeStyle = getComputedStyle(document.documentElement).getPropertyValue("--accent").trim(); cx.lineWidth = 2; cx.stroke();
    cx.lineTo(w, h); cx.lineTo(0, h); cx.closePath(); cx.fillStyle = cx.strokeStyle; cx.globalAlpha = .15; cx.fill(); cx.globalAlpha = 1;
  }
  const buzz = (ms) => { if (prefs.haptic !== false) navigator.vibrate?.(ms); };
  // tiny synthesized UI sounds, no files needed
  let sfxCtx = null;
  function sfx(kind) {
    if (prefs.sfx !== true) return;
    try {
      sfxCtx = sfxCtx || new (window.AudioContext || window.webkitAudioContext)();
      const o = sfxCtx.createOscillator(), g = sfxCtx.createGain(), t = sfxCtx.currentTime;
      const f = { click: [900, 0.04], online: [520, 0.25], offline: [220, 0.3], ok: [700, 0.12] }[kind] || [600, 0.08];
      o.type = "sine"; o.frequency.setValueAtTime(f[0], t);
      if (kind === "online") o.frequency.exponentialRampToValueAtTime(f[0] * 1.5, t + f[1]);
      g.gain.setValueAtTime(0.0001, t); g.gain.exponentialRampToValueAtTime(0.2, t + 0.01); g.gain.exponentialRampToValueAtTime(0.0001, t + f[1]);
      o.connect(g).connect(sfxCtx.destination); o.start(t); o.stop(t + f[1] + 0.02);
    } catch {}
  }
  const ask = (text) => new Promise((resolve) => {
    $("confirmText").textContent = text; $("confirm").hidden = false;
    const done = (v) => { $("confirm").hidden = true; resolve(v); };
    $("confirmYes").onclick = () => done(true); $("confirmNo").onclick = () => done(false);
  });
  const authHeaders = () => ({ Authorization: "Bearer " + secret });

  // ------------------------------------------------- connection + watchdog
  // Auth goes in the first message, never in the URL. A ping every 5 s
  // measures latency; 15 s of silence means the link is dead -> reconnect
  // with backoff, without reloading the page.
  let lastMsgAt = 0, backoff = 1000, reconnectTimer = null, pingTimer = null, pingSentAt = 0, authed = false;

  function connect() {
    clearTimeout(reconnectTimer);
    if (ws) { try { ws.onclose = null; ws.close(); } catch {} }
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws/phone`);
    ws.binaryType = "arraybuffer";
    authed = false;
    setState("Подключение…", "wait");
    ws.onopen = () => { ws.send(JSON.stringify({ t: "auth", token: secret })); lastMsgAt = Date.now(); };
    ws.onmessage = (e) => {
      lastMsgAt = Date.now();
      if (e.data instanceof ArrayBuffer) { onBinary(e.data); return; }
      let m; try { m = JSON.parse(e.data); } catch { return; }
      if (!authed) { // first server message = we are in
        authed = true; backoff = 1000;
        localStorage.setItem("pcr_secret", secret);
        login.hidden = true; app.hidden = false; connecting.hidden = true;
        hideSplash();
        send({ t: "profile", name: document.hidden ? "idle" : profile });
        if (audioOn) send({ t: "audio", on: true });
      }
      if (m.t === "status" || m.t === "pong") {
        if (m.t === "pong") { latency = Date.now() - pingSentAt; latHist.push(latency); if (latHist.length > 40) latHist.shift(); drawSpark(); }
        const was = pcOnline; pcOnline = !!m.pc_online;
        offline.hidden = pcOnline;
        if (pcOnline) {
          setState("ПК в сети", "on", pcHost);
          if (!was) {
            sfx("online"); view.classList.remove("flash"); void view.offsetWidth; view.classList.add("flash");
            $("pcArt").classList.remove("booting"); $("pcArt").classList.add("on"); pill("ПК в сети");
            if (pendingWake) { pendingWake = false; buzz([40, 60, 40]); confetti(); stopWakeTimer(); }
          }
        } else if (m.t === "status") {
          setState("ПК не в сети", "off", m.pc_since ? "был в сети " + ago(m.pc_since) : "");
          if (was) { sfx("offline"); pill("ПК отключился", true); }
          if (!pendingWake) { $("wakeBtn").classList.remove("busy"); $("pcArt").classList.remove("on", "booting"); }
          clearCanvas();
        }
      } else if (m.t === "hello") {
        pcHost = `${m.host || "ПК"} · ${m.w}×${m.h}`; pcAudio = !!m.audio;
        pill(`${m.host || "ПК"} · ${m.w}×${m.h}`);
        $("audioBtn").hidden = !pcAudio;
        setState("ПК в сети", "on", pcHost);
      } else if (m.t === "cmd_result") {
        show(m.result === "ok" ? (m.cmd === "open_url" ? "Ссылка открыта на ПК" : "Команда отправлена на ПК") : "Ошибка: " + m.result);
        sfx(m.result === "ok" ? "ok" : "offline");
      } else if (m.t === "audio" && m.on === false) {
        audioOn = false; $("audioBtn").classList.remove("active"); if (m.error) show("Звук недоступен: " + m.error, 4000);
      }
    };
    ws.onclose = (e) => {
      if (!authed) {
        if (app.hidden || e.code === 4003) { failLogin(); return; }
      }
      setState("Нет связи с сервером", "off", `повтор через ${Math.round(backoff / 1000)} с`);
      reconnectTimer = setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, 15000);
    };
    ws.onerror = () => {};
  }
  function failLogin() {
    hideSplash(() => { login.hidden = false; app.hidden = true; connecting.hidden = true; });
    $("loginErr").textContent = secret ? "Не удалось подключиться. Проверьте секрет и адрес." : "";
  }
  pingTimer = setInterval(() => {
    if (!ws || ws.readyState !== 1) return;
    if (Date.now() - lastMsgAt > 15000) { show("Связь зависла, переподключаюсь…"); ws.close(); return; }
    pingSentAt = Date.now(); send({ t: "ping" });
  }, 5000);
  window.addEventListener("online", () => { backoff = 1000; if (!ws || ws.readyState !== 1) connect(); });
  document.addEventListener("visibilitychange", () => {
    // no video while the app is in the background: saves traffic and battery
    send({ t: "profile", name: document.hidden ? "idle" : profile });
    if (!document.hidden && ws && ws.readyState !== 1) { backoff = 1000; connect(); }
  });

  function ago(ts) {
    const s = Math.max(0, (Date.now() / 1000 - ts) | 0);
    if (s < 60) return "только что";
    if (s < 3600) return `${(s / 60) | 0} мин назад`;
    if (s < 86400) return `${(s / 3600) | 0} ч назад`;
    return `${(s / 86400) | 0} дн назад`;
  }

  $("loginBtn").onclick = () => { secret = $("secret").value.trim(); if (secret) connect(); };
  $("secret").addEventListener("keydown", (e) => { if (e.key === "Enter") $("loginBtn").click(); });
  $("logoutBtn").onclick = () => {
    localStorage.removeItem("pcr_secret");
    if (window.PcRemoteApp) window.PcRemoteApp.repair();  // inside the Android app: re-pair
    else location.reload();
  };
  if (secret) connect(); else hideSplash(() => { login.hidden = false; });

  // ------------------------------------------------------ binary frames
  function onBinary(buf) {
    bytes += buf.byteLength;
    const type = new Uint8Array(buf, 0, 1)[0];
    if (type === 1) drawFrame(new Blob([buf.slice(1)], { type: "image/jpeg" }));
    else if (type === 2) playAudio(buf);
  }
  const img = new Image();
  let pendingUrl = null;
  function drawFrame(blob) {
    const url = URL.createObjectURL(blob);
    if (pendingUrl) URL.revokeObjectURL(pendingUrl);
    pendingUrl = url;
    img.onload = () => {
      if (img.src !== url) return;
      if (img.naturalWidth !== frameW || img.naturalHeight !== frameH) {
        frameW = img.naturalWidth; frameH = img.naturalHeight;
        canvas.width = frameW; canvas.height = frameH; layout();
      }
      ctx.drawImage(img, 0, 0);
      canvas.classList.add("live");
      frames++; lastFrameAt = Date.now();
      send({ t: "ack" });
    };
    img.src = url;
  }
  function clearCanvas() { frameW = frameH = 0; ctx.clearRect(0, 0, canvas.width, canvas.height); canvas.classList.remove("live"); }
  setInterval(() => {
    if (pcOnline) {
      const parts = [pcHost];
      if (frames) parts.push(`${frames} к/с`);
      if (latency) parts.push(`${latency} мс`);
      if (bytes) parts.push(bytes > 1e6 ? `${(bytes / 1e6).toFixed(1)} МБ/с` : `${Math.round(bytes / 1024)} КБ/с`);
      subEl.textContent = parts.join(" · ");
      signal.className = "signal " + (latency ? (latency < 120 ? "s3" : latency < 350 ? "s2" : "s1") : "s3");
      $("stats").textContent = `Профиль: ${{ eco: "эконом", normal: "обычный", hq: "максимум" }[profile]}` +
        (latency ? ` · задержка ${latency} мс` : "") + ` · трафик ${Math.round(bytes / 1024)} КБ/с`;
    }
    frames = 0; bytes = 0;
  }, 1000);

  // ---------------------------------------------------------- audio out
  // PCM16 mono chunks are queued into a Web Audio graph, back to back.
  let actx = null, playAt = 0;
  function playAudio(buf) {
    if (!audioOn) return;
    if (!actx) { actx = new (window.AudioContext || window.webkitAudioContext)(); playAt = 0; }
    const rate = new DataView(buf).getUint16(1, true);
    const pcm = new Int16Array(buf.slice(3));
    const ab = actx.createBuffer(1, pcm.length, rate);
    const ch = ab.getChannelData(0);
    for (let i = 0; i < pcm.length; i++) ch[i] = pcm[i] / 32768;
    const src = actx.createBufferSource(); src.buffer = ab; src.connect(actx.destination);
    const now = actx.currentTime;
    if (playAt < now + 0.05) playAt = now + 0.08;        // (re)start with a small cushion
    if (playAt > now + 0.6) playAt = now + 0.1;          // fell behind: drop the backlog
    src.start(playAt); playAt += ab.duration;
  }
  $("audioBtn").onclick = () => {
    audioOn = !audioOn;
    $("audioBtn").classList.toggle("active", audioOn);
    if (audioOn && actx && actx.state === "suspended") actx.resume();
    send({ t: "audio", on: audioOn });
    show(audioOn ? "Звук с ПК включён" : "Звук выключен");
  };

  // ------------------------------------------------------------- layout
  function layout() {
    if (!frameW) return;
    base = Math.min(view.clientWidth / frameW, view.clientHeight / frameH);
    applyTransform();
  }
  function applyTransform() {
    const s = base * zoom;
    const vw = view.clientWidth, vh = view.clientHeight, w = frameW * s, h = frameH * s;
    panX = w <= vw ? (vw - w) / 2 : Math.min(0, Math.max(vw - w, panX));
    panY = h <= vh ? (vh - h) / 2 : Math.min(0, Math.max(vh - h, panY));
    canvas.style.transform = `translate(${panX}px, ${panY}px) scale(${s})`;
    zoomBadge.hidden = zoom === 1; zoomBadge.textContent = `${Math.round(zoom * 100)}%`;
    placeCursor();
  }
  window.addEventListener("resize", layout);
  function toPC(cx, cy) {
    const r = view.getBoundingClientRect();
    const m = new DOMMatrix(getComputedStyle(canvas).transform);
    const x = (cx - r.left - m.e) / m.a, y = (cy - r.top - m.f) / m.d;
    return { x: Math.min(1, Math.max(0, x / frameW)), y: Math.min(1, Math.max(0, y / frameH)) };
  }
  function placeCursor() {
    if (!trackpad.checked || !pcOnline || !frameW) { cursorEl.hidden = true; return; }
    const m = new DOMMatrix(getComputedStyle(canvas).transform);
    cursorEl.hidden = false;
    cursorEl.style.left = (m.e + cur.x * frameW * m.a) + "px";
    cursorEl.style.top = (m.f + cur.y * frameH * m.d) + "px";
  }

  // -------------------------------------------------------------- touch
  const trackpad = $("trackpad");
  trackpad.checked = localStorage.getItem("pcr_trackpad") === "1";
  trackpad.onchange = () => { localStorage.setItem("pcr_trackpad", trackpad.checked ? "1" : "0"); placeCursor(); };

  let pts = new Map(), t0 = null, longTimer = null, dragging = false, moved = false;
  let cur = { x: 0.5, y: 0.5 }, lastTap = 0, scrollAcc = 0, pinch = null;

  view.addEventListener("touchstart", (e) => {
    if (!pcOnline) return;
    e.preventDefault(); menu.hidden = true;
    for (const t of e.changedTouches) pts.set(t.identifier, { x: t.clientX, y: t.clientY });
    if (e.touches.length === 1) {
      const t = e.touches[0];
      t0 = { x: t.clientX, y: t.clientY, time: Date.now() }; moved = false; dragging = false;
      if (!trackpad.checked) { cur = toPC(t.clientX, t.clientY); send({ t: "move", ...cur }); }
      longTimer = setTimeout(() => { longTimer = null; buzz(30); send({ t: "click", b: "right", n: 1, ...cur }); ripple(t.clientX, t.clientY, true); t0 = null; }, 550);
    } else if (e.touches.length === 2) {
      clearTimeout(longTimer); longTimer = null; t0 = null;
      if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }
      const [a, b] = e.touches;
      pinch = { d0: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), zoom0: zoom,
                cx: (a.clientX + b.clientX) / 2, cy: (a.clientY + b.clientY) / 2, panX0: panX, panY0: panY, mode: null };
      scrollAcc = 0;
    }
  }, { passive: false });

  view.addEventListener("touchmove", (e) => {
    if (!pcOnline) return;
    e.preventDefault();
    if (e.touches.length === 2 && pinch) {
      const [a, b] = e.touches;
      const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      const cx = (a.clientX + b.clientX) / 2, cy = (a.clientY + b.clientY) / 2;
      if (!pinch.mode) {
        if (Math.abs(d - pinch.d0) > 25) pinch.mode = "zoom";
        else if (Math.hypot(cx - pinch.cx, cy - pinch.cy) > 12) pinch.mode = zoom > 1 ? "pan" : "scroll";
      }
      if (pinch.mode === "zoom") {
        zoom = Math.min(5, Math.max(1, pinch.zoom0 * d / pinch.d0));
        const k = zoom / pinch.zoom0, r = view.getBoundingClientRect();
        panX = (cx - r.left) - (pinch.cx - r.left - pinch.panX0) * k;
        panY = (cy - r.top) - (pinch.cy - r.top - pinch.panY0) * k;
        applyTransform();
      } else if (pinch.mode === "pan") {
        panX = pinch.panX0 + (cx - pinch.cx); panY = pinch.panY0 + (cy - pinch.cy); applyTransform();
      } else if (pinch.mode === "scroll") {
        const prev = pts.get(a.identifier);
        if (prev) {
          scrollAcc += prev.y - a.clientY;
          if (Math.abs(scrollAcc) > 12) { send({ t: "wheel", dy: scrollAcc > 0 ? -120 : 120 }); scrollAcc = 0; }
        }
      }
      for (const t of e.changedTouches) pts.set(t.identifier, { x: t.clientX, y: t.clientY });
      return;
    }
    if (e.touches.length !== 1 || !t0) return;
    const t = e.touches[0];
    const dx = t.clientX - t0.x, dy = t.clientY - t0.y;
    if (!moved && Math.hypot(dx, dy) > 8) { moved = true; clearTimeout(longTimer); longTimer = null; }
    if (!moved) return;
    if (trackpad.checked) {
      const prev = pts.get(t.identifier);
      const m = new DOMMatrix(getComputedStyle(canvas).transform);
      cur.x = Math.min(1, Math.max(0, cur.x + (t.clientX - prev.x) * 1.4 / (frameW * m.a)));
      cur.y = Math.min(1, Math.max(0, cur.y + (t.clientY - prev.y) * 1.4 / (frameH * m.d)));
      placeCursor();
    } else {
      if (!dragging) { dragging = true; send({ t: "btn", b: "left", down: true }); }
      cur = toPC(t.clientX, t.clientY);
      const d = document.createElement("div"); d.className = "trail"; const b = view.getBoundingClientRect();
      d.style.left = (t.clientX - b.left) + "px"; d.style.top = (t.clientY - b.top) + "px"; ripples.appendChild(d); setTimeout(() => d.remove(), 500);
    }
    send({ t: "move", ...cur });
    pts.set(t.identifier, { x: t.clientX, y: t.clientY });
  }, { passive: false });

  view.addEventListener("touchend", (e) => {
    if (!pcOnline) return;
    e.preventDefault();
    for (const t of e.changedTouches) pts.delete(t.identifier);
    if (e.touches.length > 0) return;
    pinch = null;
    if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }
    else if (t0 && !moved && longTimer) {
      clearTimeout(longTimer); longTimer = null;
      const now = Date.now(), dbl = now - lastTap < 350; lastTap = dbl ? 0 : now;
      send({ t: "click", b: "left", n: dbl ? 2 : 1, ...cur }); buzz(8); sfx("click");
      const t = e.changedTouches[0]; if (t) ripple(t.clientX, t.clientY, false);
    }
    t0 = null;
  }, { passive: false });
  view.addEventListener("touchcancel", () => { clearTimeout(longTimer); longTimer = null; t0 = null; pinch = null; pts.clear(); });
  $("zoomReset").onclick = () => { zoom = 1; panX = panY = 0; applyTransform(); menu.hidden = true; };

  // ----------------------------------------------------------- keyboard
  // Sticky modifiers: tap Ctrl, then "c" -> Ctrl+C. They release after use.
  const mods = new Set();
  const modBtns = [...document.querySelectorAll("#mods button[data-mod]")];
  function setMods(active) { mods.clear(); for (const m of active) mods.add(m); modBtns.forEach((b) => b.classList.toggle("on", mods.has(b.dataset.mod))); }
  function tapKey(k) {
    if (mods.size) { send({ t: "combo", keys: [...mods, k] }); setMods([]); }
    else { send({ t: "key", k, down: true }); send({ t: "key", k, down: false }); }
  }
  $("kbBtn").onclick = () => {
    kbPanel.hidden = !kbPanel.hidden; $("kbBtn").classList.toggle("active", !kbPanel.hidden);
    if (!kbPanel.hidden) kbInput.focus(); else kbInput.blur();
    setTimeout(layout, 50);
  };
  kbInput.addEventListener("input", () => {
    const s = kbInput.value; kbInput.value = "";
    if (!s) return;
    if (mods.size) { for (const ch of s) tapKey(ch.toLowerCase()); }
    else send({ t: "text", s });
  });
  kbInput.addEventListener("keydown", (e) => {
    if (["Enter", "Backspace", "Tab", "Escape", "Delete", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) {
      e.preventDefault(); tapKey(e.key);
    }
  });
  kbPanel.addEventListener("click", (e) => {
    const b = e.target.closest("button"); if (!b) return;
    if (b.dataset.mod) { const m = b.dataset.mod; mods.has(m) ? mods.delete(m) : mods.add(m); setMods([...mods]); }
    else if (b.dataset.key) tapKey(b.dataset.key);
    else if (b.dataset.combo) { send({ t: "combo", keys: b.dataset.combo.split(",") }); setMods([]); }
    buzz(8); kbInput.focus();
  });

  // --------------------------------------------------------------- menu
  $("menuBtn").onclick = () => (menu.hidden = !menu.hidden);
  menu.addEventListener("click", async (e) => {
    const p = e.target.closest("button[data-profile]");
    if (p) { setProfile(p.dataset.profile); return; }
    const b = e.target.closest("button[data-cmd]"); if (!b) return;
    const names = { reboot: "Перезагрузить ПК?", shutdown: "Выключить ПК?", sleep: "Перевести ПК в сон?",
                    lock: "Заблокировать ПК?", cancel: "Отменить выключение?" };
    menu.hidden = true;
    if (!pcOnline) { show("ПК не в сети"); return; }
    if (b.dataset.cmd !== "cancel" && !(await ask(names[b.dataset.cmd]))) return;
    send({ t: "cmd", cmd: b.dataset.cmd }); buzz(20);
  });
  function setProfile(name) {
    profile = name; localStorage.setItem("pcr_profile", name);
    document.querySelectorAll("#profile button").forEach((b) => b.classList.toggle("on", b.dataset.profile === name));
    $("ecoBadge").hidden = name !== "eco";
    send({ t: "profile", name });
  }
  setProfile(profile);
  $("fsBtn").onclick = () => { document.documentElement.requestFullscreen?.(); menu.hidden = true; };

  // ---- perks: accent, sounds, haptics, screenshot, paste, link, hints, clock
  $("theme").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-theme]"); if (!b) return;
    prefs.theme = b.dataset.theme; savePrefs(); applyTheme(); buzz(8);
  });
  $("accent").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-accent]"); if (!b) return;
    prefs.accent = b.dataset.accent; savePrefs(); applyAccent(); buzz(8);
  });
  $("sfx").checked = prefs.sfx === true; $("haptic").checked = prefs.haptic !== false;
  $("sfx").onchange = () => { prefs.sfx = $("sfx").checked; savePrefs(); sfx("ok"); };
  $("haptic").onchange = () => { prefs.haptic = $("haptic").checked; savePrefs(); buzz(20); };

  $("shotBtn").onclick = () => {
    menu.hidden = true;
    if (!frameW) { show("Нет кадра"); return; }
    const a = document.createElement("a");
    a.download = `pc-${new Date().toISOString().slice(0, 19).replace(/[T:]/g, "-")}.png`;
    a.href = canvas.toDataURL("image/png"); a.click(); show("Снимок сохранён"); sfx("ok");
  };
  function textDialog(title, placeholder, onOk) {
    $("textDlgTitle").textContent = title; $("textDlgInput").placeholder = placeholder; $("textDlgInput").value = "";
    $("textDlg").hidden = false; menu.hidden = true; setTimeout(() => $("textDlgInput").focus(), 50);
    $("textDlgNo").onclick = () => ($("textDlg").hidden = true);
    $("textDlgYes").onclick = () => { const v = $("textDlgInput").value; $("textDlg").hidden = true; if (v.trim()) onOk(v); };
  }
  $("pasteBtn").onclick = () => textDialog("Вставить текст на ПК", "Текст появится на ПК там, где курсор",
    (v) => { if (!pcOnline) return show("ПК не в сети"); send({ t: "clip", s: v }); show("Отправлено на ПК"); });
  $("linkBtn").onclick = () => textDialog("Открыть ссылку на ПК", "https://…",
    (v) => { if (!pcOnline) return show("ПК не в сети"); send({ t: "open_url", url: v.trim() }); });
  $("hintsBtn").onclick = () => { menu.hidden = true; $("hints").hidden = false; };
  $("hintsOk").onclick = () => { $("hints").hidden = true; prefs.hintsSeen = true; savePrefs(); };
  if (!prefs.hintsSeen) setTimeout(() => { if (!app.hidden) $("hints").hidden = false; }, 2600);

  setInterval(() => {
    if (offline.hidden) return;
    const d = new Date();
    $("clock").innerHTML = d.toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" }) +
      `<small>${d.toLocaleDateString("ru-RU", { weekday: "long", day: "numeric", month: "long" })}</small>`;
  }, 1000);

  // ---- phone screen -> PC (only inside the Android app, via the JS bridge)
  const bridge = window.PcRemoteApp;
  if (bridge && bridge.startCast) {
    $("castBox").hidden = false;
    const refreshCast = () => {
      const on = bridge.isCasting();
      $("castBtn").textContent = on ? "⏹ Остановить трансляцию" : "📱 Транслировать экран телефона на ПК";
      $("muteRow").hidden = !on;
      $("phoneMute").checked = bridge.isPhoneMuted();
    };
    $("castBtn").onclick = () => { bridge.isCasting() ? bridge.stopCast() : bridge.startCast(); menu.hidden = true; setTimeout(refreshCast, 800); };
    $("phoneMute").onchange = () => bridge.setPhoneMute($("phoneMute").checked);
    $("menuBtn").addEventListener("click", refreshCast);
    refreshCast();
  }

  let wakeT0 = 0, wakeTimer = null;
  function startWakeTimer() {
    wakeT0 = Date.now(); $("wakeTimer").hidden = false;
    clearInterval(wakeTimer);
    wakeTimer = setInterval(() => {
      const s = ((Date.now() - wakeT0) / 1000) | 0;
      $("wakeTimer").textContent = `ищу ПК… ${(s / 60) | 0}:${String(s % 60).padStart(2, "0")}`;
      if (s > 180) { stopWakeTimer(); pendingWake = false; $("wakeBtn").classList.remove("busy"); $("pcArt").classList.remove("booting");
        $("wakeMsg").textContent = "ПК не ответил за 3 минуты. Попробуйте ещё раз."; }
    }, 1000);
  }
  function stopWakeTimer() { clearInterval(wakeTimer); $("wakeTimer").hidden = true; }
  $("wakeBtn").onclick = async () => {
    const btn = $("wakeBtn"); btn.classList.add("busy"); buzz(20);
    $("wakeMsg").textContent = "Отправляю команду…";
    try {
      const r = await fetch("/api/wake", { method: "POST", headers: authHeaders() });
      const j = await r.json();
      if (j.ok) {
        pendingWake = true; $("wakeMsg").textContent = "Команда отправлена, ПК загружается…";
        $("pcArt").classList.add("booting"); startWakeTimer();
      }
      else { btn.classList.remove("busy"); $("wakeMsg").textContent = "Ошибка: " + j.error; }
    } catch (e) { btn.classList.remove("busy"); $("wakeMsg").textContent = "Ошибка: " + e; }
  };
})();
