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
  let pcInfo = { term: false, projects: [], monitors: 1, monitor: 1 }, pcClip = "";
  let audioOn = false;

  // ------------------------------------------------------------ helpers
  function show(msg, ms = 2500) {
    toast.textContent = msg; toast.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => (toast.hidden = true), ms);
  }
  let bytesIn = 0, bytesOut = 0;
  const send = (obj) => { if (ws && ws.readyState === 1) { const j = JSON.stringify(obj); bytesOut += j.length; ws.send(j); } };
  const fmtSpeed = (b) => b > 1e6 ? `${(b / 1e6).toFixed(1)} МБ/с` : b > 1024 ? `${Math.round(b / 1024)} КБ/с` : `${b} Б/с`;
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
      if (e.data instanceof ArrayBuffer) { bytesIn += e.data.byteLength; onBinary(e.data); return; }
      bytesIn += e.data.length;
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
        pcInfo = { term: !!m.term, shells: m.shells || ["shell"], projects: m.projects || [], monitors: m.monitors || 1, monitor: m.monitor || 1 };
        $("termBtn").hidden = false;
        renderMonitors();
        $("audioBtn").hidden = !pcAudio;
        setState("ПК в сети", "on", pcHost);
      } else if (m.t === "cmd_result") {
        show(m.result === "ok" ? (m.cmd === "open_url" ? "Ссылка открыта на ПК" : "Команда отправлена на ПК") : "Ошибка: " + m.result);
        sfx(m.result === "ok" ? "ok" : "offline");
      } else if (m.t === "term_out") { termOut(m.id, m.data);
      } else if (m.t === "term_exit") { termExit(m.id);
      } else if (m.t === "pc_clip") {
        pcClip = m.s; $("pcClipBtn").hidden = false; $("pcClipText").textContent = m.s.slice(0, 40).replace(/\s+/g, " ");
        show("Скопировано на ПК · нажмите, чтобы взять", 3500);
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
    $("spdDown").textContent = "↓ " + fmtSpeed(bytesIn); $("spdUp").textContent = "↑ " + fmtSpeed(bytesOut);
    $("spdDown").classList.toggle("hot", bytesIn > 2048); $("spdUp").classList.toggle("hot", bytesOut > 2048);
    bytesIn = 0; bytesOut = 0;
    if (pcOnline) {
      const parts = [pcHost];
      if (frames) parts.push(`${frames} к/с`);
      if (latency) parts.push(`${latency} мс`);
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
  function setProfile(name, auto = false) {
    profile = name; localStorage.setItem("pcr_profile", name);
    if (!auto) localStorage.setItem("pcr_profile_manual", name);
    document.querySelectorAll("#profile button").forEach((b) => b.classList.toggle("on", b.dataset.profile === name));
    $("ecoBadge").hidden = name !== "eco";
    send({ t: "profile", name });
  }
  setProfile(profile);
  // auto profile: cellular -> eco, otherwise the chosen one
  const conn = navigator.connection;
  function autoProfile() {
    if (!$("autoProfile").checked || !conn) return;
    const cellular = conn.type === "cellular" || /2g|3g/.test(conn.effectiveType || "");
    const want = cellular ? "eco" : (localStorage.getItem("pcr_profile_manual") || "normal");
    if (want !== profile) { setProfile(want, true); show(cellular ? "Мобильная сеть: эконом" : "Wi-Fi: обычное качество"); }
  }
  $("autoProfile").checked = prefs.autoProfile === true;
  $("autoProfile").onchange = () => { prefs.autoProfile = $("autoProfile").checked; savePrefs(); autoProfile(); };
  conn && conn.addEventListener && conn.addEventListener("change", autoProfile);
  setTimeout(autoProfile, 1500);
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
    canvas.toBlob((b) => openViewer(URL.createObjectURL(b), `pc-${new Date().toISOString().slice(0, 19).replace(/[T:]/g, "-")}.png`), "image/png");
    sfx("ok");
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

  // ================================================================
  // Monitors
  // ================================================================
  function renderMonitors() {
    const seg = $("monitors");
    seg.hidden = pcInfo.monitors < 2;
    [...seg.querySelectorAll("button")].forEach((b) => b.remove());
    for (let i = 1; i <= pcInfo.monitors; i++) {
      const b = document.createElement("button"); b.textContent = String(i); b.dataset.mon = i;
      b.classList.toggle("on", i === pcInfo.monitor); seg.appendChild(b);
    }
    const all = document.createElement("button"); all.textContent = "Все"; all.dataset.mon = 0; all.classList.toggle("on", pcInfo.monitor === 0); seg.appendChild(all);
  }
  $("monitors").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-mon]"); if (!b) return;
    pcInfo.monitor = +b.dataset.mon; send({ t: "monitor", n: pcInfo.monitor }); renderMonitors(); menu.hidden = true; zoom = 1; panX = panY = 0;
  });

  // ================================================================
  // PC clipboard -> phone
  // ================================================================
  $("pcClipBtn").onclick = async () => {
    try { await navigator.clipboard.writeText(pcClip); show("Скопировано в буфер телефона"); }
    catch { textDialog("Буфер ПК", "", () => {}); $("textDlgInput").value = pcClip; }
    menu.hidden = true;
  };

  // ================================================================
  // Terminal: Claude Code / PowerShell on the PC, streamed over the link
  // ================================================================
  const terms = new Map();   // id -> {term, fit, kind, cwd, exited}
  let activeTerm = null, termSeq = 0;
  const termPanel = $("termPanel"), termHost = $("termHost");
  function termTheme() {
    const cs = getComputedStyle(document.documentElement);
    return { background: "#0b0d12", foreground: "#e6e9f0", cursor: cs.getPropertyValue("--accent").trim(), selectionBackground: "#4f8cff55" };
  }
  function openTermPanel() {
    termPanel.hidden = false; menu.hidden = true;
    if (!terms.size) renderTermEmpty(); else showTerm(activeTerm);
  }
  function renderTermEmpty() {
    termHost.innerHTML = "";
    const d = document.createElement("div"); d.className = "term-empty";
    const projects = pcInfo.projects.length ? pcInfo.projects : [""];
    d.innerHTML = `<div>Запустить на ПК:</div>`;
    for (const cwd of projects) {
      const row = document.createElement("div");
      const name = cwd ? cwd.split(/[\\/]/).filter(Boolean).pop() : "домашняя папка";
      row.innerHTML = `<b>${name}</b><br>`;
      const shells = pcInfo.shells || ["shell"];
      const options = [["claude", "Claude Code"], ...(shells.includes("bash") ? [["bash", "Git Bash"]] : []), ["shell", "PowerShell"]];
      for (const [kind, label] of options) {
        const b = document.createElement("button"); b.textContent = label; b.onclick = () => newTerm(kind, cwd); row.appendChild(b);
      }
      d.appendChild(row);
    }
    if (!pcInfo.term) d.innerHTML += `<p class="err">На ПК нет pywinpty: в PC Remote это уже есть, для скриптов — pip install pywinpty</p>`;
    termHost.appendChild(d);
    renderTabs();
  }
  function newTerm(kind, cwd) {
    const id = "t" + (++termSeq);
    const term = new Terminal({ fontSize: 13, fontFamily: "ui-monospace, Consolas, monospace", cursorBlink: true, theme: termTheme(),
      scrollback: 3000, convertEol: false, allowProposedApi: true });
    const fit = new FitAddon.FitAddon(); term.loadAddon(fit);
    term.onData((data) => send({ t: "term_in", id, data }));
    terms.set(id, { term, fit, kind, cwd, exited: false });
    activeTerm = id; showTerm(id);
    const dims = fit.proposeDimensions() || { cols: 80, rows: 24 };
    send({ t: "term_open", id, kind, cwd: cwd || undefined, cols: dims.cols, rows: dims.rows });
    buzz(10);
  }
  function showTerm(id) {
    const t = terms.get(id); if (!t) { renderTermEmpty(); return; }
    activeTerm = id; termHost.innerHTML = "";
    const box = document.createElement("div"); box.style.height = "100%"; termHost.appendChild(box);
    t.term.open(box); setTimeout(() => { t.fit.fit(); send({ t: "term_resize", id, cols: t.term.cols, rows: t.term.rows }); }, 30);
    renderTabs();
  }
  function renderTabs() {
    const tabs = $("termTabs"); tabs.innerHTML = "";
    for (const [id, t] of terms) {
      const b = document.createElement("button"); b.classList.toggle("on", id === activeTerm);
      const name = { claude: "Claude", bash: "Git Bash", cmd: "cmd" }[t.kind] || "PowerShell";
      b.innerHTML = `${name}${t.exited ? " <i>·</i>" : ""} <i data-close="${id}">✕</i>`;
      b.onclick = (e) => { if (e.target.dataset.close) closeTerm(e.target.dataset.close); else showTerm(id); };
      tabs.appendChild(b);
    }
  }
  function termOut(id, data) { const t = terms.get(id); if (t) t.term.write(data); }
  function termExit(id) { const t = terms.get(id); if (!t) return; t.exited = true; t.term.write("\r\n\x1b[90m[сессия завершена]\x1b[0m\r\n"); renderTabs(); }
  function closeTerm(id) {
    const t = terms.get(id); if (!t) return;
    send({ t: "term_close", id }); t.term.dispose(); terms.delete(id);
    if (activeTerm === id) activeTerm = terms.keys().next().value || null;
    terms.size ? showTerm(activeTerm) : renderTermEmpty();
  }
  function termSend(data) { if (!activeTerm) return show("Сначала запустите сессию"); send({ t: "term_in", id: activeTerm, data }); }
  $("termBtn").onclick = openTermPanel;
  $("termMenuBtn").onclick = openTermPanel;
  $("termBack").onclick = () => (termPanel.hidden = true);
  $("termNew").onclick = () => renderTermEmpty();
  $("termQuick").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-send]"); if (!b) return;
    termSend(JSON.parse('"' + b.dataset.send + '"')); buzz(8);
  });
  $("termGit").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-cmd]"); if (!b) return;
    if (!activeTerm) { const p = pcInfo.projects[0]; newTerm((pcInfo.shells || []).includes("bash") ? "bash" : "shell", p); setTimeout(() => termSend(b.dataset.cmd + "\r"), 1500); }
    else termSend(b.dataset.cmd + "\r");
    buzz(8);
  });
  const sendLine = () => { const v = $("termLine").value; if (!v) return termSend("\r"); termSend(v + "\r"); $("termLine").value = ""; };
  $("termSend").onclick = sendLine;
  $("termLine").addEventListener("keydown", (e) => { if (e.key === "Enter") { e.preventDefault(); sendLine(); } });
  window.addEventListener("resize", () => { const t = terms.get(activeTerm); if (t && !termPanel.hidden) { t.fit.fit(); send({ t: "term_resize", id: activeTerm, cols: t.term.cols, rows: t.term.rows }); } });
  // custom quick buttons for the terminal (stored on the phone)
  function renderTermQuick() {
    const custom = prefs.termQuick || [];
    $("termQuick").querySelectorAll("button.custom").forEach((b) => b.remove());
    for (const c of custom) {
      const b = document.createElement("button"); b.className = "custom"; b.textContent = c; b.dataset.send = JSON.stringify(c + "\r").slice(1, -1);
      $("termQuick").insertBefore(b, $("termQuickEdit"));
    }
  }
  renderTermQuick();
  $("termQuickEdit").onclick = () => textDialog("Свои кнопки терминала (по одной в строке)", "/rc\n/status\ngit pull", (v) => {
    prefs.termQuick = v.split("\n").map((x) => x.trim()).filter(Boolean).slice(0, 20); savePrefs(); renderTermQuick();
  });
  setTimeout(() => { if (prefs.termQuick) $("textDlgInput").value = ""; }, 0);

  // ================================================================
  // Files: browse share folders, download to the phone, upload from the phone
  // ================================================================
  let filesPath = "";
  const isImage = (n) => /\.(jpe?g|png|gif|webp|bmp|avif)$/i.test(n);
  const thumbCache = new Map();
  async function loadFiles(path) {
    filesPath = path;
    const r = await fetch(`/api/files?path=${encodeURIComponent(path)}`, { headers: authHeaders() });
    if (!r.ok) { $("filesList").innerHTML = `<div class="empty">Нет доступа</div>`; return; }
    const j = await r.json(); const list = $("filesList"); list.innerHTML = "";
    $("filesTitle").textContent = j.path ? j.path.split(/[\\/]/).filter(Boolean).pop() : "Файлы на ПК";
    $("uploadLbl").textContent = j.path ? "⬆ Отправить сюда" : "⬆ Отправить на ПК";
    if (!j.items.length) list.innerHTML = `<div class="empty">Пусто</div>`;
    const images = j.items.filter((it) => !it.dir && isImage(it.name));
    const grid = images.length >= 2 && images.length >= j.items.length / 2;   // a photo folder -> thumbnails
    list.classList.toggle("grid", grid);
    for (const it of j.items) {
      const size = it.dir ? "" : it.size > 1e6 ? `${(it.size / 1e6).toFixed(1)} МБ` : `${Math.round(it.size / 1024)} КБ`;
      const d = document.createElement("div");
      if (grid) {
        d.className = "th" + (it.dir ? " dir" : "");
        if (it.dir) d.textContent = "📁"; else if (isImage(it.name)) { const im = document.createElement("img"); loadThumb(im, it.path); d.appendChild(im); } else d.textContent = "📄";
        const cap = document.createElement("span"); cap.textContent = it.name; d.appendChild(cap);
      } else {
        d.className = "it";
        d.innerHTML = `<i>${it.dir ? "📁" : isImage(it.name) ? "🖼" : "📄"}</i><span class="n">${it.name}</span><span class="s">${size}</span>`;
      }
      d.onclick = () => it.dir ? loadFiles(it.path) : isImage(it.name) ? viewImage(it) : downloadFile(it);
      list.appendChild(d);
    }
  }
  async function loadThumb(img, path) {
    if (thumbCache.has(path)) { img.src = thumbCache.get(path); return; }
    try {
      const r = await fetch(`/api/thumb?path=${encodeURIComponent(path)}`, { headers: authHeaders() });
      if (!r.ok) return; const b = await r.blob(); bytesIn += b.size;
      const u = URL.createObjectURL(b); thumbCache.set(path, u); img.src = u;
    } catch {}
  }
  async function downloadFile(it) {
    show(`Скачиваю ${it.name}…`, 6000);
    const r = await fetch(`/api/file?path=${encodeURIComponent(it.path)}`, { headers: authHeaders() });
    if (!r.ok) return show("Не удалось скачать");
    const blob = await r.blob(); bytesIn += blob.size; const a = document.createElement("a");
    a.href = URL.createObjectURL(blob); a.download = it.name; a.click(); setTimeout(() => URL.revokeObjectURL(a.href), 10000);
    show("Сохранено на телефон"); sfx("ok");
  }
  // ---- image viewer: photos from the PC and screenshots, pinch to zoom
  let imgBlobUrl = null, imgName = "", iz = 1, ix = 0, iy = 0, ipinch = null, ilastTap = 0;
  function openViewer(url, name) {
    imgBlobUrl = url; imgName = name; $("imgName").textContent = name; $("imgEl").src = url;
    iz = 1; ix = iy = 0; applyImg(); $("imgView").hidden = false; $("filesDlg").hidden = true;
  }
  async function viewImage(it) {
    show(`Открываю ${it.name}…`, 4000);
    const r = await fetch(`/api/file?path=${encodeURIComponent(it.path)}&inline=1`, { headers: authHeaders() });
    if (!r.ok) return show("Не удалось открыть");
    const blob = await r.blob(); bytesIn += blob.size; toast.hidden = true;
    openViewer(URL.createObjectURL(blob), it.name);
  }
  function applyImg() { $("imgEl").style.transform = `translate(${ix}px, ${iy}px) scale(${iz})`; }
  const stage = $("imgStage");
  stage.addEventListener("touchstart", (e) => {
    if (e.touches.length === 2) { const [a, b] = e.touches; ipinch = { d0: Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY), z0: iz, x0: ix, y0: iy, cx: (a.clientX + b.clientX) / 2, cy: (a.clientY + b.clientY) / 2 }; }
    else if (e.touches.length === 1) { ipinch = { pan: true, x0: ix, y0: iy, cx: e.touches[0].clientX, cy: e.touches[0].clientY }; }
  }, { passive: true });
  stage.addEventListener("touchmove", (e) => {
    if (!ipinch) return; e.preventDefault();
    if (e.touches.length === 2 && !ipinch.pan) {
      const [a, b] = e.touches; const d = Math.hypot(a.clientX - b.clientX, a.clientY - b.clientY);
      iz = Math.min(8, Math.max(1, ipinch.z0 * d / ipinch.d0));
      const cx = (a.clientX + b.clientX) / 2, cy = (a.clientY + b.clientY) / 2;
      ix = ipinch.x0 + (cx - ipinch.cx); iy = ipinch.y0 + (cy - ipinch.cy); applyImg();
    } else if (e.touches.length === 1 && ipinch.pan && iz > 1) {
      ix = ipinch.x0 + (e.touches[0].clientX - ipinch.cx); iy = ipinch.y0 + (e.touches[0].clientY - ipinch.cy); applyImg();
    }
  }, { passive: false });
  stage.addEventListener("touchend", (e) => {
    if (e.touches.length === 0) {
      const now = Date.now();
      if (ipinch && ipinch.pan && now - ilastTap < 300) { iz = iz > 1 ? 1 : 2.5; ix = iy = 0; applyImg(); }
      ilastTap = now; ipinch = null; if (iz === 1) { ix = iy = 0; applyImg(); }
    }
  }, { passive: true });
  $("imgBack").onclick = () => { $("imgView").hidden = true; };
  $("imgSave").onclick = () => { const a = document.createElement("a"); a.href = imgBlobUrl; a.download = imgName; a.click(); show("Сохранено на телефон"); sfx("ok"); };
  $("fileInput").onchange = async () => {
    const files = [...$("fileInput").files]; $("fileInput").value = "";
    for (const f of files) {
      $("filesProgress").textContent = `Отправляю ${f.name} (${Math.round(f.size / 1024)} КБ)…`;
      try {
        const r = await fetch("/api/upload", { method: "POST", headers: { ...authHeaders(), "X-Filename": f.name, ...(filesPath ? { "X-Dir": filesPath } : {}) }, body: f });
        const j = await r.json(); bytesOut += f.size;
        $("filesProgress").textContent = j.ok ? `✓ ${j.name} на ПК` : "Ошибка: " + j.error;
        if (j.ok && filesPath) loadFiles(filesPath);
      } catch (e) { $("filesProgress").textContent = "Ошибка: " + e; }
    }
    sfx("ok");
  };
  $("filesBtn").onclick = () => { menu.hidden = true; $("filesDlg").hidden = false; loadFiles(""); };
  $("filesClose").onclick = () => ($("filesDlg").hidden = true);
  $("filesUp").onclick = () => { const parts = filesPath.split(/[\\/]/); parts.pop(); loadFiles(parts.length > 1 ? parts.join("\\") : ""); };
  // ================================================================
  // Macros: named step lists, shown as buttons in the keyboard panel
  // ================================================================
  const defaultMacros = [{ name: "Блокнот", steps: ["keys: Meta,r", "wait: 400", "text: notepad", "key: Enter"] },
                         { name: "Диспетчер", steps: ["keys: Control,Shift,Escape"] }];
  let macros = JSON.parse(localStorage.getItem("pcr_macros") || "null") || defaultMacros;
  const saveMacros = () => localStorage.setItem("pcr_macros", JSON.stringify(macros));
  async function runMacro(m) {
    show(`▶ ${m.name}`); buzz(10);
    for (const raw of m.steps) {
      const [op, ...rest] = raw.split(":"); const arg = rest.join(":").trim();
      switch (op.trim()) {
        case "keys": send({ t: "combo", keys: arg.split(",").map((k) => k.trim()) }); break;
        case "key": send({ t: "key", k: arg, down: true }); send({ t: "key", k: arg, down: false }); break;
        case "text": send({ t: "text", s: arg }); break;
        case "clip": send({ t: "clip", s: arg }); break;
        case "wait": await new Promise((r) => setTimeout(r, Math.min(5000, +arg || 300))); break;
        case "url": send({ t: "open_url", url: arg }); break;
        case "cmd": send({ t: "cmd", cmd: arg }); break;
        case "shell": if (!activeTerm) { newTerm((pcInfo.shells || []).includes("bash") ? "bash" : "shell", pcInfo.projects[0]); await new Promise((r) => setTimeout(r, 1500)); } termSend(arg + "\r"); break;
      }
      await new Promise((r) => setTimeout(r, 120));
    }
  }
  function renderMacros() {
    const row = $("macroRow"); row.innerHTML = ""; row.hidden = !macros.length;
    macros.forEach((m) => { const b = document.createElement("button"); b.textContent = "⚡ " + m.name; b.onclick = () => runMacro(m); row.appendChild(b); });
    const list = $("macroList"); list.innerHTML = "";
    if (!macros.length) list.innerHTML = `<div class="empty">Пока нет макросов</div>`;
    macros.forEach((m, i) => {
      const d = document.createElement("div"); d.className = "it";
      d.innerHTML = `<i>⚡</i><span class="n">${m.name}</span><span class="s">${m.steps.length} шаг.</span><i data-del="${i}">🗑</i>`;
      d.onclick = (e) => { if (e.target.dataset.del) { macros.splice(i, 1); saveMacros(); renderMacros(); } else { $("macroEdit").value = [m.name, ...m.steps].join("\n"); } };
      list.appendChild(d);
    });
  }
  renderMacros();
  $("macrosBtn").onclick = () => { menu.hidden = true; $("macrosDlg").hidden = false; };
  $("macroClose").onclick = () => ($("macrosDlg").hidden = true);
  $("macroSave").onclick = () => {
    const lines = $("macroEdit").value.split("\n").map((x) => x.trim()).filter(Boolean);
    if (lines.length < 2) return show("Нужны название и хотя бы один шаг");
    const [name, ...steps] = lines; const i = macros.findIndex((m) => m.name === name);
    i >= 0 ? (macros[i].steps = steps) : macros.push({ name, steps });
    saveMacros(); renderMacros(); $("macroEdit").value = ""; show("Макрос сохранён");
  };

  // ================================================================
  // Event log
  // ================================================================
  $("logBtn").onclick = async () => {
    menu.hidden = true; $("logDlg").hidden = false; $("logList").innerHTML = `<div class="empty">Загружаю…</div>`;
    const r = await fetch("/api/events", { headers: authHeaders() }); const j = r.ok ? await r.json() : [];
    $("logList").innerHTML = j.length ? "" : `<div class="empty">Пока пусто</div>`;
    for (const e of j) { const d = document.createElement("div"); d.className = "it";
      d.innerHTML = `<span class="s">${new Date(e.ts * 1000).toLocaleString("ru-RU", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" })}</span><span class="n">${e.text}</span>`; $("logList").appendChild(d); }
  };
  $("logClose").onclick = () => ($("logDlg").hidden = true);
})();