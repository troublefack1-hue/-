/* pc-remote phone client */
(() => {
  const $ = (id) => document.getElementById(id);
  const login = $("login"), app = $("app"), canvas = $("screen"), ctx = canvas.getContext("2d");
  const view = $("view"), offline = $("offline"), dot = $("dot"), stateEl = $("state");
  const keys = $("keys"), kbInput = $("kbInput"), menu = $("menu"), toast = $("toast");

  let ws = null, secret = localStorage.getItem("pcr_secret") || "";
  let pcOnline = false, frameW = 0, frameH = 0, scale = 1, offX = 0, offY = 0;
  let reconnectTimer = null, toastTimer = null;

  // ------------------------------------------------------------ helpers
  function show(msg) {
    toast.textContent = msg; toast.hidden = false;
    clearTimeout(toastTimer); toastTimer = setTimeout(() => (toast.hidden = true), 2500);
  }
  function send(obj) { if (ws && ws.readyState === 1) ws.send(JSON.stringify(obj)); }
  function setState(text, on) { stateEl.textContent = text; dot.className = "dot " + (on ? "on" : "off"); }

  // ------------------------------------------------------------ connect
  function connect() {
    clearTimeout(reconnectTimer);
    const proto = location.protocol === "https:" ? "wss" : "ws";
    ws = new WebSocket(`${proto}://${location.host}/ws/phone?token=${encodeURIComponent(secret)}`);
    ws.binaryType = "blob";
    setState("подключение…", false);
    ws.onopen = () => { localStorage.setItem("pcr_secret", secret); login.hidden = true; app.hidden = false; };
    ws.onmessage = (e) => {
      if (e.data instanceof Blob) { drawFrame(e.data); return; }
      let m; try { m = JSON.parse(e.data); } catch { return; }
      if (m.t === "status") {
        pcOnline = m.pc_online;
        offline.hidden = pcOnline;
        setState(pcOnline ? "ПК в сети" : "ПК не в сети", pcOnline);
      } else if (m.t === "hello") {
        setState(`ПК в сети · ${m.host || ""} ${m.w}×${m.h}`, true);
      } else if (m.t === "cmd_result") {
        show(m.result === "ok" ? "Команда выполнена" : "Ошибка: " + m.result);
      }
    };
    ws.onclose = (e) => {
      if (e.code === 1008 || e.code === 4003) { // never used, kept for clarity
        failLogin(); return;
      }
      if (!app.hidden) { setState("нет связи с сервером", false); reconnectTimer = setTimeout(connect, 2000); }
      else failLogin();
    };
    ws.onerror = () => {};
  }
  function failLogin() {
    login.hidden = false; app.hidden = true;
    $("loginErr").textContent = "Не удалось подключиться. Проверьте секрет.";
  }

  $("loginBtn").onclick = () => { secret = $("secret").value.trim(); if (secret) connect(); };
  $("secret").addEventListener("keydown", (e) => { if (e.key === "Enter") $("loginBtn").click(); });
  $("logoutBtn").onclick = () => { localStorage.removeItem("pcr_secret"); location.reload(); };
  if (secret) connect();

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
        frameW = img.naturalWidth; frameH = img.naturalHeight; layout();
      }
      ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
      send({ t: "ack" });
    };
    img.src = url;
  }
  function layout() {
    if (!frameW) return;
    const vw = view.clientWidth, vh = view.clientHeight;
    scale = Math.min(vw / frameW, vh / frameH);
    canvas.width = Math.round(frameW * scale); canvas.height = Math.round(frameH * scale);
    offX = Math.round((vw - canvas.width) / 2); offY = Math.round((vh - canvas.height) / 2);
    canvas.style.left = offX + "px"; canvas.style.top = offY + "px";
    if (img.complete && img.naturalWidth) ctx.drawImage(img, 0, 0, canvas.width, canvas.height);
  }
  window.addEventListener("resize", layout);

  // -------------------------------------------------------------- touch
  // Direct mode: tap = click where you touch, long press = right click,
  // drag = mouse drag, two fingers = scroll.
  // Trackpad mode: finger moves the cursor relatively, tap = click.
  const trackpad = $("trackpad");
  trackpad.checked = localStorage.getItem("pcr_trackpad") === "1";
  trackpad.onchange = () => localStorage.setItem("pcr_trackpad", trackpad.checked ? "1" : "0");

  let touches = new Map(), t0 = null, longTimer = null, dragging = false, moved = false;
  let cur = { x: 0.5, y: 0.5 }, lastTap = 0, scrollAcc = 0;

  const norm = (t) => ({
    x: Math.min(1, Math.max(0, (t.clientX - view.getBoundingClientRect().left - offX) / canvas.width)),
    y: Math.min(1, Math.max(0, (t.clientY - view.getBoundingClientRect().top - offY) / canvas.height)),
  });

  view.addEventListener("touchstart", (e) => {
    if (!pcOnline) return;
    e.preventDefault();
    for (const t of e.changedTouches) touches.set(t.identifier, { x: t.clientX, y: t.clientY });
    if (e.touches.length === 1) {
      const t = e.touches[0];
      t0 = { x: t.clientX, y: t.clientY, time: Date.now() }; moved = false; dragging = false;
      if (!trackpad.checked) { cur = norm(t); send({ t: "move", ...cur }); }
      longTimer = setTimeout(() => {
        longTimer = null;
        send({ t: "click", b: "right", n: 1, ...cur }); navigator.vibrate?.(30);
        t0 = null;
      }, 550);
    } else {
      clearTimeout(longTimer); longTimer = null; t0 = null;
      if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }
      scrollAcc = 0;
    }
  }, { passive: false });

  view.addEventListener("touchmove", (e) => {
    if (!pcOnline) return;
    e.preventDefault();
    if (e.touches.length === 2) {
      const a = e.touches[0], prev = touches.get(a.identifier);
      if (prev) {
        scrollAcc += prev.y - a.clientY;
        if (Math.abs(scrollAcc) > 12) { send({ t: "wheel", dy: scrollAcc > 0 ? -120 : 120 }); scrollAcc = 0; }
      }
      for (const t of e.changedTouches) touches.set(t.identifier, { x: t.clientX, y: t.clientY });
      return;
    }
    if (e.touches.length !== 1 || !t0) return;
    const t = e.touches[0];
    const dx = t.clientX - t0.x, dy = t.clientY - t0.y;
    if (!moved && Math.hypot(dx, dy) > 8) { moved = true; clearTimeout(longTimer); longTimer = null; }
    if (!moved) return;
    if (trackpad.checked) {
      const prev = touches.get(t.identifier);
      cur.x = Math.min(1, Math.max(0, cur.x + (t.clientX - prev.x) * 1.5 / canvas.width));
      cur.y = Math.min(1, Math.max(0, cur.y + (t.clientY - prev.y) * 1.5 / canvas.height));
    } else {
      if (!dragging) { dragging = true; send({ t: "btn", b: "left", down: true }); }
      cur = norm(t);
    }
    send({ t: "move", ...cur });
    touches.set(t.identifier, { x: t.clientX, y: t.clientY });
  }, { passive: false });

  view.addEventListener("touchend", (e) => {
    if (!pcOnline) return;
    e.preventDefault();
    for (const t of e.changedTouches) touches.delete(t.identifier);
    if (e.touches.length > 0) return;
    if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }
    else if (t0 && !moved && longTimer) {
      clearTimeout(longTimer); longTimer = null;
      const now = Date.now(), dbl = now - lastTap < 350; lastTap = dbl ? 0 : now;
      send({ t: "click", b: "left", n: dbl ? 2 : 1, ...cur });
    }
    t0 = null;
  }, { passive: false });
  view.addEventListener("touchcancel", () => { clearTimeout(longTimer); longTimer = null; t0 = null; touches.clear(); });

  // ----------------------------------------------------------- keyboard
  $("kbBtn").onclick = () => {
    keys.hidden = !keys.hidden;
    if (!keys.hidden) kbInput.focus(); else kbInput.blur();
    layout();
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
    kbInput.focus();
  });

  // --------------------------------------------------------------- menu
  $("menuBtn").onclick = () => (menu.hidden = !menu.hidden);
  view.addEventListener("touchstart", () => (menu.hidden = true), { passive: true });
  menu.addEventListener("click", (e) => {
    const b = e.target.closest("button[data-cmd]"); if (!b) return;
    const names = { reboot: "Перезагрузить ПК?", shutdown: "Выключить ПК?", sleep: "Усыпить ПК?",
                    lock: "Заблокировать ПК?", cancel: "Отменить выключение?" };
    if (!confirm(names[b.dataset.cmd])) return;
    send({ t: "cmd", cmd: b.dataset.cmd }); menu.hidden = true;
  });
  $("fsBtn").onclick = () => { document.documentElement.requestFullscreen?.(); menu.hidden = true; };

  $("wakeBtn").onclick = async () => {
    $("wakeMsg").textContent = "Отправляю…";
    try {
      const r = await fetch(`/api/wake?token=${encodeURIComponent(secret)}`, { method: "POST" });
      const j = await r.json();
      $("wakeMsg").textContent = j.ok ? "Команда отправлена. ПК обычно появляется через 1–2 мин." : "Ошибка: " + j.error;
    } catch (e) { $("wakeMsg").textContent = "Ошибка: " + e; }
  };
})();
