/* pc-remote phone client */
(() => {
  const $ = (id) => document.getElementById(id);
  const login = $("login"), app = $("app"), canvas = $("screen"), ctx = canvas.getContext("2d");
  const view = $("view"), offline = $("offline"), connecting = $("connecting"), connMsg = $("connMsg");
  const dot = $("dot"), stateEl = $("state"), subEl = $("sub"), cursorEl = $("cursor"), zoomBadge = $("zoomBadge");
  const kbPanel = $("kbPanel"), kbInput = $("kbInput"), menu = $("menu"), toast = $("toast");

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
  const buzz = (ms) => navigator.vibrate?.(ms);
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
        send({ t: "profile", name: document.hidden ? "idle" : profile });
        if (audioOn) send({ t: "audio", on: true });
      }
      if (m.t === "status" || m.t === "pong") {
        if (m.t === "pong") latency = Date.now() - pingSentAt;
        const was = pcOnline; pcOnline = !!m.pc_online;
        offline.hidden = pcOnline;
        if (pcOnline) {
          setState("ПК в сети", "on", pcHost);
          if (!was && pendingWake) { pendingWake = false; buzz([40, 60, 40]); show("ПК включился"); }
        } else if (m.t === "status") {
          setState("ПК не в сети", "off", m.pc_since ? "был в сети " + ago(m.pc_since) : "");
          $("wakeBtn").classList.remove("busy"); clearCanvas();
        }
      } else if (m.t === "hello") {
        pcHost = `${m.host || "ПК"} · ${m.w}×${m.h}`; pcAudio = !!m.audio;
        $("audioBtn").hidden = !pcAudio;
        setState("ПК в сети", "on", pcHost);
      } else if (m.t === "cmd_result") {
        show(m.result === "ok" ? "Команда отправлена на ПК" : "Ошибка: " + m.result);
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
    login.hidden = false; app.hidden = true; connecting.hidden = true;
    $("loginErr").textContent = "Не удалось подключиться. Проверьте секрет и адрес.";
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
  if (secret) connect(); else connecting.hidden = true;

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
      frames++; lastFrameAt = Date.now();
      send({ t: "ack" });
    };
    img.src = url;
  }
  function clearCanvas() { frameW = frameH = 0; ctx.clearRect(0, 0, canvas.width, canvas.height); }
  setInterval(() => {
    if (pcOnline) {
      const parts = [pcHost];
      if (frames) parts.push(`${frames} к/с`);
      if (latency) parts.push(`${latency} мс`);
      if (bytes) parts.push(bytes > 1e6 ? `${(bytes / 1e6).toFixed(1)} МБ/с` : `${Math.round(bytes / 1024)} КБ/с`);
      subEl.textContent = parts.join(" · ");
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
      longTimer = setTimeout(() => { longTimer = null; buzz(30); send({ t: "click", b: "right", n: 1, ...cur }); t0 = null; }, 550);
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
      send({ t: "click", b: "left", n: dbl ? 2 : 1, ...cur }); buzz(8);
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

  $("wakeBtn").onclick = async () => {
    const btn = $("wakeBtn"); btn.classList.add("busy"); buzz(20);
    $("wakeMsg").textContent = "Отправляю команду…";
    try {
      const r = await fetch("/api/wake", { method: "POST", headers: authHeaders() });
      const j = await r.json();
      if (j.ok) { pendingWake = true; $("wakeMsg").textContent = "Команда отправлена. ПК обычно появляется через 1–2 минуты."; }
      else { btn.classList.remove("busy"); $("wakeMsg").textContent = "Ошибка: " + j.error; }
    } catch (e) { btn.classList.remove("busy"); $("wakeMsg").textContent = "Ошибка: " + e; }
  };
})();
