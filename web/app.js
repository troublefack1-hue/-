/* pc-remote phone client */
(() => {
  const $ = (id) => document.getElementById(id);
  const login = $("login"), app = $("app"), canvas = $("screen"), ctx = canvas.getContext("2d");
  const view = $("view"), offline = $("offline"), connecting = $("connecting");
  const dot = $("dot"), stateEl = $("state"), subEl = $("sub"), cursorEl = $("cursor"), zoomBadge = $("zoomBadge");
  const keys = $("keys"), kbInput = $("kbInput"), menu = $("menu"), toast = $("toast");

  // The launcher page can hand the secret over in the URL hash (local mode:
  // the tunnel address changes, so localStorage of the old origin is gone).
  let secret = localStorage.getItem("pcr_secret") || "";
  if (location.hash.length > 1) {
    secret = decodeURIComponent(location.hash.slice(1));
    history.replaceState(null, "", location.pathname);
  }
  let ws = null;
  let pcOnline = false, pcHost = "", frameW = 0, frameH = 0;
  let base = 1;                              // fit-to-view scale
  let zoom = 1, panX = 0, panY = 0;          // user zoom and canvas position
  let reconnectTimer = null, toastTimer = null, frames = 0, fpsTimer = null, lastFrameAt = 0;
  let pendingWake = false;

  // ------------------------------------------------------------ helpers
  function show(msg, ms = 2500) {
    toast.textContent = msg; toast.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => (toast.hidden = true), ms);
  }
  function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }
  function setState(text, cls, sub = "") { stateEl.textContent = text; dot.className = "dot " + cls; subEl.textContent = sub; }
  const buzz = (ms) => navigator.vibrate?.(ms);

  function ask(text) {
    return new Promise((resolve) => {
      $("confirmText").textContent = text; $("confirm").hidden = false;
      const done = (v) => { $("confirm").hidden = true; resolve(v); };
      $("confirmYes").onclick = () => done(true);
      $("confirmNo").onclick = () => done(false);
    });
  }

  // ------------------------------------------------------------ connect
  function connect() {
    clearTimeout(reconnectTimer);
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws/phone?token=${encodeURIComponent(secret)}`);
    ws.binaryType = "blob";
    setState("Подключение…", "wait");
    ws.onopen = () => {
      localStorage.setItem("pcr_secret", secret);
      login.hidden = true; app.hidden = false; connecting.hidden = true;
    };
    ws.onmessage = (e) => {
      if (e.data instanceof Blob) { drawFrame(e.data); return; }
      let m; try { m = JSON.parse(e.data); } catch { return; }
      if (m.t === "status") {
        pcOnline = m.pc_online;
        offline.hidden = pcOnline;
        if (pcOnline) {
          setState("ПК в сети", "on", pcHost);
          if (pendingWake) { pendingWake = false; buzz([40, 60, 40]); show("ПК включился"); }
        } else {
          setState("ПК не в сети", "off", m.pc_since ? "был в сети " + ago(m.pc_since) : "");
          $("wakeBtn").classList.remove("busy");
          clearCanvas();
        }
      } else if (m.t === "hello") {
        pcHost = `${m.host || "ПК"} · ${m.w}×${m.h}`;
        setState("ПК в сети", "on", pcHost);
      } else if (m.t === "cmd_result") {
        show(m.result === "ok" ? "Команда отправлена на ПК" : "Ошибка: " + m.result);
      }
    };
    ws.onclose = () => {
      if (app.hidden) { failLogin(); return; }
      setState("Нет связи с сервером", "off", "переподключение…");
      reconnectTimer = setTimeout(connect, 2000);
    };
    ws.onerror = () => {};
  }
  function failLogin() {
    login.hidden = false; app.hidden = true;
    $("loginErr").textContent = "Не удалось подключиться. Проверьте секрет и адрес.";
  }
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

  // ------------------------------------------------------------- frames
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
  fpsTimer = setInterval(() => {
    if (pcOnline) subEl.textContent = frames ? `${pcHost} · ${frames} к/с` : pcHost;
    frames = 0;
  }, 1000);

  function layout() {
    if (!frameW) return;
    const vw = view.clientWidth, vh = view.clientHeight;
    base = Math.min(vw / frameW, vh / frameH);
    applyTransform();
  }
  // panX/panY = the canvas translate in view pixels. When the image is smaller
  // than the view it is centered; when larger it is clamped to the edges.
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

  // screen-space (px in view) -> normalized PC coordinates
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
  let cur = { x: 0.5, y: 0.5 }, lastTap = 0, scrollAcc = 0;
  let pinch = null; // {d0, zoom0, cx, cy, panX0, panY0, mode}

  view.addEventListener("touchstart", (e) => {
    if (!pcOnline) return;
    e.preventDefault(); menu.hidden = true;
    for (const t of e.changedTouches) pts.set(t.identifier, { x: t.clientX, y: t.clientY });
    if (e.touches.length === 1) {
      const t = e.touches[0];
      t0 = { x: t.clientX, y: t.clientY, time: Date.now() }; moved = false; dragging = false;
      if (!trackpad.checked) { cur = toPC(t.clientX, t.clientY); send({ t: "move", ...cur }); }
      longTimer = setTimeout(() => {
        longTimer = null; buzz(30);
        send({ t: "click", b: "right", n: 1, ...cur }); t0 = null;
      }, 550);
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
        // zoom around the pinch centre and follow it as it moves
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
  $("kbBtn").onclick = () => {
    keys.hidden = !keys.hidden; $("kbBtn").classList.toggle("active", !keys.hidden);
    if (!keys.hidden) kbInput.focus(); else kbInput.blur();
    setTimeout(layout, 50);
  };
  kbInput.addEventListener("input", () => {
    if (kbInput.value) { send({ t: "text", s: kbInput.value }); kbInput.value = ""; }
  });
  kbInput.addEventListener("keydown", (e) => {
    if (["Enter", "Backspace", "Tab", "Escape", "Delete", "ArrowLeft", "ArrowRight", "ArrowUp", "ArrowDown"].includes(e.key)) {
      e.preventDefault(); send({ t: "key", k: e.key, down: true }); send({ t: "key", k: e.key, down: false });
    }
  });
  keys.addEventListener("click", (e) => {
    const b = e.target.closest("button"); if (!b) return;
    if (b.dataset.key) { send({ t: "key", k: b.dataset.key, down: true }); send({ t: "key", k: b.dataset.key, down: false }); }
    if (b.dataset.combo) send({ t: "combo", keys: b.dataset.combo.split(",") });
    buzz(8); kbInput.focus();
  });

  // --------------------------------------------------------------- menu
  $("menuBtn").onclick = () => (menu.hidden = !menu.hidden);
  menu.addEventListener("click", async (e) => {
    const b = e.target.closest("button[data-cmd]"); if (!b) return;
    const names = { reboot: "Перезагрузить ПК?", shutdown: "Выключить ПК?", sleep: "Перевести ПК в сон?",
                    lock: "Заблокировать ПК?", cancel: "Отменить выключение?" };
    menu.hidden = true;
    if (!pcOnline) { show("ПК не в сети"); return; }
    if (b.dataset.cmd !== "cancel" && !(await ask(names[b.dataset.cmd]))) return;
    send({ t: "cmd", cmd: b.dataset.cmd }); buzz(20);
  });
  $("fsBtn").onclick = () => { document.documentElement.requestFullscreen?.(); menu.hidden = true; };

  $("wakeBtn").onclick = async () => {
    const btn = $("wakeBtn"); btn.classList.add("busy"); buzz(20);
    $("wakeMsg").textContent = "Отправляю команду…";
    try {
      const r = await fetch(`/api/wake?token=${encodeURIComponent(secret)}`, { method: "POST" });
      const j = await r.json();
      if (j.ok) { pendingWake = true; $("wakeMsg").textContent = "Команда отправлена. ПК обычно появляется через 1–2 минуты."; }
      else { btn.classList.remove("busy"); $("wakeMsg").textContent = "Ошибка: " + j.error; }
    } catch (e) { btn.classList.remove("busy"); $("wakeMsg").textContent = "Ошибка: " + e; }
  };
})();
