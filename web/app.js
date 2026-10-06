/* pc-remote phone client */
(() => {
  const $ = (id) => document.getElementById(id);
  const login = $("login"), app = $("app");
  // The picture is composed in `buf` (2D); the visible `canvas` shows it through the upscaler below at the phone's
  // own pixel density: detail recovered on the phone's GPU, not bought with bytes. No WebGL: `buf` is the canvas.
  let canvas = $("screen"), buf = canvas, ctx = null, gl = null;
  const view = $("view"), offline = $("offline"), connecting = $("connecting"), connMsg = $("connMsg");
  const dot = $("dot"), stateEl = $("state"), subEl = $("sub"), cursorEl = $("cursor"), zoomBadge = $("zoomBadge");
  const kbPanel = $("kbPanel"), kbInput = $("kbInput"), menu = $("menu"), toast = $("toast");
  const splash = $("splash"), ripples = $("ripples"), signal = $("signal");
  const splashShownAt = Date.now();
  function hideSplash(then) {
    // the intro leaves as soon as the page is ready: 1.4 s of animation was 1.4 s of waiting on every start
    const wait = 0;
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
  let bufK = 1;   // the composed picture is bufK times the full frame (a zoomed-in region brought more detail)
  const SHARP = 0.75;  // RCAS strength: 1 = FSR maximum; at 10 KB/s video has blocks a full sharpen outlines (bench: edges +25 % at 0.6, +56 % at 1)
  const VS = "attribute vec2 p; uniform float flip; varying vec2 uv;" +
    "void main(){ uv = vec2(p.x * 0.5 + 0.5, flip > 0.5 ? 0.5 - p.y * 0.5 : p.y * 0.5 + 0.5); gl_Position = vec4(p, 0.0, 1.0); }";
  // Lanczos-2 (4x4 taps) clamped to the 2x2 nearest texels: sharp edges without the rings plain Lanczos draws
  // around text (the deringing trick of AMD FSR's EASU)
  const FS_UP = "precision highp float; varying vec2 uv; uniform sampler2D src; uniform vec2 size;" +
    "float L(float x){ x = abs(x); if (x < 1e-4) return 1.0; if (x >= 2.0) return 0.0; float a = 3.14159265 * x; return 2.0 * sin(a) * sin(a * 0.5) / (a * a); }" +
    "void main(){ vec2 q = uv * size - 0.5; vec2 f = fract(q); vec2 b = floor(q) + 0.5; vec3 sum = vec3(0.0); float ws = 0.0;" +
    " vec3 mn = vec3(1.0); vec3 mx = vec3(0.0);" +
    " for (int j = -1; j <= 2; j++) { for (int i = -1; i <= 2; i++) {" +
    "  vec3 c = texture2D(src, (b + vec2(float(i), float(j))) / size).rgb; float w = L(float(i) - f.x) * L(float(j) - f.y);" +
    "  sum += c * w; ws += w; if (i >= 0 && i <= 1 && j >= 0 && j <= 1) { mn = min(mn, c); mx = max(mx, c); } } }" +
    " gl_FragColor = vec4(clamp(sum / ws, mn, mx), 1.0); }";
  // FSR 1.0 RCAS: sharpening limited by the local contrast, so it does not blow out edges or noise
  const FS_RCAS = "precision highp float; varying vec2 uv; uniform sampler2D src; uniform vec2 px; uniform float sharp;" +
    "void main(){ vec3 e = texture2D(src, uv).rgb;" +
    " vec3 b = texture2D(src, uv + vec2(0.0, px.y)).rgb; vec3 h = texture2D(src, uv - vec2(0.0, px.y)).rgb;" +
    " vec3 d = texture2D(src, uv - vec2(px.x, 0.0)).rgb; vec3 f = texture2D(src, uv + vec2(px.x, 0.0)).rgb;" +
    " vec3 mn4 = min(min(b, d), min(f, h)); vec3 mx4 = max(max(b, d), max(f, h));" +
    " vec3 hitMin = min(mn4, e) / (4.0 * mx4 + 1e-4); vec3 hitMax = (1.0 - max(mx4, e)) / (4.0 * min(mn4, e) - 4.0 - 1e-4);" +
    " vec3 l = max(-hitMin, hitMax); float lobe = max(-0.1875, min(max(l.r, max(l.g, l.b)), 0.0)) * sharp;" +
    " gl_FragColor = vec4((lobe * (b + d + f + h) + e) / (4.0 * lobe + 1.0), 1.0); }";
  function makeUpscaler(g) {
    const sh = (type, src) => { const x = g.createShader(type); g.shaderSource(x, src); g.compileShader(x); if (!g.getShaderParameter(x, g.COMPILE_STATUS)) throw new Error(g.getShaderInfoLog(x)); return x; };
    const prog = (fs) => { const p = g.createProgram(); g.attachShader(p, sh(g.VERTEX_SHADER, VS)); g.attachShader(p, sh(g.FRAGMENT_SHADER, fs)); g.linkProgram(p); if (!g.getProgramParameter(p, g.LINK_STATUS)) throw new Error(g.getProgramInfoLog(p)); return p; };
    const pUp = prog(FS_UP), pSharp = prog(FS_RCAS);
    const quad = g.createBuffer(); g.bindBuffer(g.ARRAY_BUFFER, quad); g.bufferData(g.ARRAY_BUFFER, new Float32Array([-1, -1, 1, -1, -1, 1, 1, 1]), g.STATIC_DRAW);
    const tex = () => { const t = g.createTexture(); g.bindTexture(g.TEXTURE_2D, t);
      for (const [k, v] of [[g.TEXTURE_MIN_FILTER, g.LINEAR], [g.TEXTURE_MAG_FILTER, g.LINEAR], [g.TEXTURE_WRAP_S, g.CLAMP_TO_EDGE], [g.TEXTURE_WRAP_T, g.CLAMP_TO_EDGE]]) g.texParameteri(g.TEXTURE_2D, k, v);
      return t; };
    const src = tex(), mid = tex(), fbo = g.createFramebuffer();
    let W = 0, H = 0;
    function resize(w, h) {
      W = w; H = h; g.bindTexture(g.TEXTURE_2D, mid);
      g.texImage2D(g.TEXTURE_2D, 0, g.RGBA, w, h, 0, g.RGBA, g.UNSIGNED_BYTE, null);
      g.bindFramebuffer(g.FRAMEBUFFER, fbo); g.framebufferTexture2D(g.FRAMEBUFFER, g.COLOR_ATTACHMENT0, g.TEXTURE_2D, mid, 0);
      g.bindFramebuffer(g.FRAMEBUFFER, null);
    }
    function pass(p, target, flip, uniforms) {
      g.useProgram(p); const a = g.getAttribLocation(p, "p");
      g.bindBuffer(g.ARRAY_BUFFER, quad); g.enableVertexAttribArray(a); g.vertexAttribPointer(a, 2, g.FLOAT, false, 0, 0);
      g.uniform1f(g.getUniformLocation(p, "flip"), flip); g.uniform1i(g.getUniformLocation(p, "src"), 0); uniforms(p);
      g.bindFramebuffer(g.FRAMEBUFFER, target); g.viewport(0, 0, W, H); g.drawArrays(g.TRIANGLE_STRIP, 0, 4);
    }
    function draw() {
      if (!W || !buf.width) return;
      g.activeTexture(g.TEXTURE0); g.bindTexture(g.TEXTURE_2D, src);
      g.texImage2D(g.TEXTURE_2D, 0, g.RGBA, g.RGBA, g.UNSIGNED_BYTE, buf);
      pass(pUp, fbo, 1, (p) => g.uniform2f(g.getUniformLocation(p, "size"), buf.width, buf.height));
      g.bindTexture(g.TEXTURE_2D, mid);
      pass(pSharp, null, 0, (p) => { g.uniform2f(g.getUniformLocation(p, "px"), 1 / W, 1 / H); g.uniform1f(g.getUniformLocation(p, "sharp"), SHARP); });
    }
    return { resize, draw };
  }
  let up = null;
  function fallback2D(why) {
    // no WebGL (or it was lost): a plain 2D canvas in the same place, the composed picture copied over
    console.warn("upscaler off:", why); up = null; gl = null;
    if (buf === canvas) return;
    const c2 = document.createElement("canvas"); c2.id = "screen"; c2.className = canvas.className;
    c2.style.cssText = canvas.style.cssText; c2.width = buf.width; c2.height = buf.height;
    const x2 = c2.getContext("2d"); x2.drawImage(buf, 0, 0);
    canvas.replaceWith(c2); canvas = c2; buf = c2; ctx = x2;
  }
  // off unless switched on (localStorage pcr_sharpen=1): on a 480-px picture at 10 KB/s the sharpening outlined the
  // compression blocks, and the owner judged the picture worse (06.10.2026)
  if (localStorage.getItem("pcr_sharpen") === "1") try {
    gl = canvas.getContext("webgl", { alpha: false, antialias: false, depth: false, stencil: false, premultipliedAlpha: false });
    if (gl) { buf = document.createElement("canvas"); up = makeUpscaler(gl); }
  } catch (e) { up = null; }
  if (gl && !up) fallback2D("shaders"); else if (!gl) buf = canvas;
  if (!ctx) ctx = buf.getContext("2d");
  canvas.addEventListener("webglcontextlost", (e) => { e.preventDefault(); fallback2D("context lost"); });
  function present() { if (up) up.draw(); }
  // the visible canvas keeps its CSS size = frame size (the zoom/pan maths work in those pixels); its backing store
  // is as many device pixels as the phone shows of it, at most 2560 px and 4x the composed picture
  let fitTimer = null;
  function fitDisplay(now) {
    if (!up || !frameW) return;
    clearTimeout(fitTimer);
    if (!now) { fitTimer = setTimeout(() => fitDisplay(true), 150); return; }   // during a pinch: once it settles
    const dpr = window.devicePixelRatio || 1, s = base * zoom;
    let w = frameW * s * dpr, h = frameH * s * dpr;
    const k = Math.min(1, 2560 / Math.max(w, h), 4 * buf.width / w);
    w = Math.max(1, Math.round(w * k)); h = Math.max(1, Math.round(h * k));
    if (canvas.width !== w || canvas.height !== h) { canvas.width = w; canvas.height = h; up.resize(w, h); }
    present();
  }
  function setFrameSize(w, h) {
    frameW = w; frameH = h; bufK = 1; buf.width = w; buf.height = h;
    canvas.style.width = w + "px"; canvas.style.height = h + "px";
    layout(); fitDisplay(true);
  }
  function growBuf(k) {
    // a region frame carries more detail than the full frame has pixels for: grow the composed picture (at most 4x
    // and 4096 px), the old content scaled into it
    k = Math.min(k, 4, 4096 / frameW);
    if (k <= bufK * 1.05) return;
    const old = document.createElement("canvas"); old.width = buf.width; old.height = buf.height; old.getContext("2d").drawImage(buf, 0, 0);
    bufK = k; buf.width = Math.round(frameW * k); buf.height = Math.round(frameH * k);
    ctx.drawImage(old, 0, 0, buf.width, buf.height);
    fitDisplay(true);
  }
  let base = 1, zoom = 1, panX = 0, panY = 0;
  let toastTimer = null, frames = 0, bytes = 0, lastFrameAt = 0, latency = 0, pendingWake = false;
  let profile = localStorage.getItem("pcr_profile") || "normal";
  let pcInfo = { term: false, projects: [], monitors: 1, monitor: 1 }, pcClip = "";
  let audioOn = false;

  // ------------------------------------------------------------ helpers
  // every string that came from the PC, a phone or a file name goes through esc() before innerHTML
  const esc = (v) => String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
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
  let lastMsgAt = 0, backoff = 1000, reconnectTimer = null, pingTimer = null, pingSentAt = 0, authed = false, phoneReconnects = 0, fpsShown = 0;

  // the Android activity tells us when it goes to the background (the WebView itself keeps running)
  let appHidden = false;
  const isHidden = () => document.hidden || appHidden;
  let audioWanted = false;
  window.pcrVisible = (v) => {
    appHidden = !v;
    send({ t: "profile", name: isHidden() ? "idle" : profile }); send({ t: "pq", level: pq });
    if (isHidden() && audioOn) { audioWanted = true; audioOn = false; send({ t: "audio", on: false }); }
    else if (!isHidden() && audioWanted) { audioWanted = false; audioOn = true; send({ t: "audio", on: true }); }
    if (!isHidden() && ws && ws.readyState !== 1) { backoff = 1000; connect(); }
  };
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
        send({ t: "profile", name: isHidden() ? "idle" : profile }); send({ t: "pq", level: pq });
        if (audioOn) send({ t: "audio", on: true });
      }
      if (m.t === "status" && Array.isArray(m.ports)) pcPorts = m.ports;
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
        pcHost = `${m.host || "ПК"} · ${m.w}×${m.h}`; pcAudio = !!m.audio; pcW = m.mw || m.w || pcW; pcH = m.mh || m.h || pcH;
        pill(`${m.host || "ПК"} · ${m.w}×${m.h}`);
        pcInfo = { term: !!m.term, shells: m.shells || ["shell"], projects: m.projects || [], monitors: m.monitors || 1, monitor: m.monitor || 1 };
        if (m.volume) applyVolume(m.volume);
        renderAudioDevices(m.audio_devices);
        $("termBtn").hidden = false;
        renderMonitors();
        $("audioBtn").hidden = !pcAudio;
        setState("ПК в сети", "on", pcHost);
        sendRules(); sendAudioSrc(); sendAdapt(); sendCapture(); if (m.video) announceCodecs();
        viewSent = ""; reportView();   // the agent may have restarted and forgotten how wide we show it
        if (!$("termPanel").hidden && !terms.size) renderTermEmpty();   // project folders may have changed on the PC
      } else if (m.t === "cmd_result") {
        const okText = { open_url: "Ссылка открыта на ПК", print: "Отправлено на печать", kill: "Процесс завершён", monitor_off: "Экран выключен", monitor_on: "Экран включён", powerplan: "Схема питания изменена" };
        show(m.result === "ok" ? (okText[m.cmd] || "Команда отправлена на ПК") : "Ошибка: " + m.result);
        if (m.result === "ok" && m.cmd === "kill") send({ t: "procs_get" });
        if (m.result === "ok" && m.cmd === "powerplan") send({ t: "powerplans_get" });
        sfx(m.result === "ok" ? "ok" : "offline");
      } else if (m.t === "volume") { applyVolume(m);
      } else if (m.t === "role") { setGuest(!!m.guest);
      } else if (m.t === "diag") { renderDiag(m);
      } else if (m.t === "windows") { renderWindows(m.items || []);
      } else if (m.t === "zone") {
        if (!m.rect) { zoneRect = zoneImg = null; $("zoneBtn").classList.remove("active"); $("bZone").hidden = true; show(m.error ? "HD-зона: " + m.error : (zoneWanted ? "Движение не найдено — выделите область вручную" : "HD-зона выключена")); }
        else { $("zoneBtn").classList.add("active"); $("bZone").hidden = false; show("HD-зона включена"); sfx("ok"); }
        zoneWanted = false;
      } else if (m.t === "sys") { renderSys(m);
      } else if (m.t === "procs") { renderProcs(m.items || []);
      } else if (m.t === "timers") { renderTimers(m.items || []); if (m.result && m.result !== "ok") show("Таймер: " + m.result);
      } else if (m.t === "powerplans") { renderPlans(m.items || []);
      } else if (m.t === "downloads") { renderDl(m.items || []); if (m.result && m.result !== "ok") show("Загрузка: " + m.result);
      } else if (m.t === "dl") { dlUpdate(m);
      } else if (m.t === "screen") {
        $("noScreen").hidden = !!m.ok; if (!m.ok) pill("Экран ПК недоступен — управление работает", true, 4000);
      } else if (m.t === "net") {
        // the PC just toggled its VPN: probe now, reconnect in 1.5 s if it went quiet
        pill(m.vpn ? "VPN на ПК включён" : "VPN на ПК выключен", m.vpn);
        pingSentAt = Date.now(); send({ t: "ping" });
        setTimeout(() => { if (Date.now() - lastMsgAt > 1400 && ws && ws.readyState === 1) { backoff = 300; ws.close(); } }, 1500);
      } else if (m.t === "traffic") { renderTraffic(m);
      } else if (m.t === "pair_request") {   // a new phone typed the permanent code: only a paired phone lets it in
        (async () => {
          const ok = await ask(`Новый телефон просит доступ к ПК: ${m.model} (${m.ip}). Он ввёл постоянный код. Разрешить? Если это не вы — «Отмена».`);
          send({ t: "pair_answer", id: m.id, ok: !!ok });
          show(ok ? "Разрешено" : "Отклонено", 2500);
        })();
      } else if (m.t === "cur") {   // the PC cursor moved: draw it now, the picture need not carry it
        pcCur = { x: m.x, y: m.y };
        // The PC's report comes back a link's delay late: right after the finger lifts it still says where the cursor
        // WAS, and taking it threw the cursor back on release (owner, 06.10.2026). The trackpad keeps its own position
        // and follows the PC only when the PC's cursor moved by itself: 2 s after the last touch, and clearly elsewhere.
        if (trackpad.checked && t0 === null && !dragging && Date.now() - padSentAt > 2000 &&
            (!cur || Math.abs(cur.x - pcCur.x) > 0.01 || Math.abs(cur.y - pcCur.y) > 0.01)) cur = pcCur;
        placeCursor();
      } else if (m.t === "term_out") { termOut(m.id, m.data);
      } else if (m.t === "pc_notify") {
        if (!isHidden()) { pill(`${m.app}: ${m.title || m.text}`.slice(0, 80), false, 4000); buzz(15); }
      } else if (m.t === "attention") {
        if ($("termPanel").hidden || isHidden()) { pill("Claude ждёт ответа — откройте терминал", true, 5000); buzz([30, 60, 30]); sfx("ok"); }
      } else if (m.t === "term_exit") { termExit(m.id);
      } else if (m.t === "pc_clip") {
        pcClip = m.s; $("pcClipBtn").hidden = false; $("pcClipText").textContent = m.s.slice(0, 40).replace(/\s+/g, " ");
        show("Скопировано на ПК · нажмите, чтобы взять", 3500);
      } else if (m.t === "audio" && m.on === false) {
        audioOn = false; $("audioBtn").classList.remove("active"); if (m.error) show("Звук недоступен: " + m.error, 4000);
      } else if (m.t === "audio" && m.on) {
        if (m.warn) { show(m.warn, 6000); pcrError(m.warn); }
        else if (prefs.audioOnly) show(`Звук только на телефоне · ПК играет в «${m.via || "?"}»`, 3000);
      }
    };
    ws.onclose = (e) => {
      if (authed) { phoneReconnects++; window.pcrError && window.pcrError(`WS закрыт: код ${e.code}${e.reason ? " " + e.reason : ""}`); }
      if (!authed) {
        if (app.hidden || e.code === 4003) { failLogin(); return; }
      }
      if (e.code === 4029) {   // too many wrong attempts from this network: hammering only prolongs the lockout
        setState("Адрес временно заблокирован", "off", "много неверных попыток, повтор через минуту");
        reconnectTimer = setTimeout(connect, 60000); return;
      }
      setState("Нет связи с сервером", "off", `повтор через ${Math.round(backoff / 1000)} с`);
      reconnectTimer = setTimeout(connect, backoff);
      backoff = Math.min(backoff * 2, 8000);
    };
    ws.onerror = () => {};
  }
  function failLogin() {
    hideSplash(() => { login.hidden = false; app.hidden = true; connecting.hidden = true; });
    $("loginErr").textContent = secret ? "Не удалось подключиться. Проверьте секрет и адрес." : "";
  }
  // 2 s pings while the app is on screen, 6 s of silence = reconnect (a VPN toggle on the PC costs seconds, not a minute)
  pingTimer = setInterval(() => {
    if (!ws || ws.readyState !== 1) return;
    const limit = isHidden() ? 40000 : 12000;
    if (Date.now() - lastMsgAt > limit) { show("Связь прервалась, переподключаюсь…"); backoff = 500; ws.close(); return; }
    if (Date.now() - pingSentAt > (isHidden() ? 20000 : 5000)) { pingSentAt = Date.now(); send({ t: "ping" }); }
  }, 1000);
  window.addEventListener("online", () => { backoff = 1000; if (!ws || ws.readyState !== 1) connect(); });
  document.addEventListener("visibilitychange", () => {
    // no video while the app is in the background: saves traffic and battery
    send({ t: "profile", name: isHidden() ? "idle" : profile }); send({ t: "pq", level: pq });
    if (!isHidden() && ws && ws.readyState !== 1) { backoff = 1000; connect(); }
  });

  function ago(ts) {
    const s = Math.max(0, (Date.now() / 1000 - ts) | 0);
    if (s < 60) return "только что";
    if (s < 3600) return `${(s / 60) | 0} мин назад`;
    if (s < 86400) return `${(s / 3600) | 0} ч назад`;
    return `${(s / 86400) | 0} дн назад`;
  }

  $("loginBtn").onclick = async () => {
    const v = $("secret").value.trim(); if (!v) return;
    const code = v.toUpperCase().replace(/[^0-9A-Z]/g, "");
    if (code.length === 6 || code.length === 8) {   // the connection code from the PC window: exchange it for the secret
      try {
        const r = await fetch(`/api/pair?code=${encodeURIComponent(code)}`);
        if (!r.ok) { $("loginErr").textContent = "Неверный код"; return; }
        secret = (await r.json()).secret;
      } catch { $("loginErr").textContent = "ПК не отвечает"; return; }
    } else secret = v;
    connect();
  };
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
    else if (type === 10) playOpus(buf);
    else if (type === 5) drawZone(buf);
    else if (type === 9) decodeFrame(buf);
  }
  const img = new Image();
  let pendingUrl = null;
  function drawFrame(blob) {
    const url = URL.createObjectURL(blob);
    if (pendingUrl) URL.revokeObjectURL(pendingUrl);
    pendingUrl = url;
    img.onload = () => {
      if (img.src !== url) return;
      if (img.naturalWidth !== frameW || img.naturalHeight !== frameH || bufK !== 1) setFrameSize(img.naturalWidth, img.naturalHeight);
      ctx.drawImage(img, 0, 0, buf.width, buf.height); paintZone(); present();
      if (!$("bVideo").hidden) $("bVideo").hidden = true;
      canvas.classList.add("live");
      frames++; lastFrameAt = Date.now();
      send({ t: "ack" });
    };
    img.src = url;
  }
  // ---- encoded video (H.264 / VP8 via WebCodecs): the PC asks what we can decode
  let codecList = null, vdec = null, vcodec = 0, waitKey = true;
  let decodeMaxW = 3840;   // lowered by the probe below and by a decoder failure at a big size
  async function probeCodecs() {
    if (codecList) return codecList;
    codecList = [];
    if (!("VideoDecoder" in window)) return codecList;
    // avc1h = H.264 High (the PC uses it on a thin link when we have it); hvc1/av01/vp09 are reported for the PC's
    // choice of codec and its log
    for (const [name, cfg] of [["avc1", { codec: "avc1.42E01E" }], ["avc1h", { codec: "avc1.64001F" }], ["vp8", { codec: "vp8" }],
                               ["hvc1", { codec: "hvc1.1.6.L93.B0" }], ["av01", { codec: "av01.0.04M.08" }], ["vp09", { codec: "vp09.00.10.08" }]]) {
      try { const r = await VideoDecoder.isConfigSupported({ ...cfg, codedWidth: 1280, codedHeight: 720 }); if (r.supported) codecList.push(name); } catch {}
    }
    // the widest H.264 picture this phone decodes: on Wi-Fi the PC sends its screen's own size up to this (4K if it can)
    for (const [w, h, c] of [[3840, 2160, "avc1.640033"], [2560, 1440, "avc1.640032"], [1920, 1080, "avc1.640028"]]) {
      try { const r = await VideoDecoder.isConfigSupported({ codec: c, codedWidth: w, codedHeight: h }); if (r.supported) { decodeMaxW = Math.min(decodeMaxW, w); break; } } catch {}
    }
    // sound: Opus at 24 kbit/s instead of raw PCM at 256 kbit/s (the PC falls back to PCM if we can't)
    try { if ("AudioDecoder" in window && (await AudioDecoder.isConfigSupported({ codec: "opus", sampleRate: 48000, numberOfChannels: 1 })).supported) codecList.push("opus"); } catch {}
    return codecList;
  }
  async function announceCodecs() {
    const all = await probeCodecs();
    const list = prefs.video === false ? all.filter((c) => c === "opus") : all;
    // the kind of network: the PC remembers its speed per kind, so mobile data never starts at the Wi-Fi picture size
    const link = (navigator.connection && navigator.connection.type) || "";
    send({ t: "video", codecs: list, link, maxw: decodeMaxW });
  }
  function ensureDecoder(codec) {
    if (vdec && vcodec === codec && vdec.state !== "closed") return true;
    try { vdec && vdec.close(); } catch {}
    try {
      vdec = new VideoDecoder({
        output: (f) => {
          const r = frameRects.get(f.timestamp); frameRects.delete(f.timestamp);
          const isKey = frameKeys.delete(f.timestamp);
          if (r && frameW) {   // a zoomed-in region: onto the last full picture, where it belongs, with its detail
            growBuf(f.displayWidth / (r.w * frameW));
            ctx.drawImage(f, r.x * buf.width, r.y * buf.height, r.w * buf.width, r.h * buf.height);
          } else if (!r) {
            // a new full stream (key frame) starts from its own size again: the region's extra pixels are gone anyway
            if (f.displayWidth !== frameW || f.displayHeight !== frameH || (isKey && bufK !== 1)) setFrameSize(f.displayWidth, f.displayHeight);
            ctx.drawImage(f, 0, 0, buf.width, buf.height);
          }
          paintZone(); f.close(); present();
          canvas.classList.add("live"); frames++; lastFrameAt = Date.now(); send({ t: "ack" });
          if ($("bVideo").hidden) $("bVideo").hidden = false;
        },
        error: (e) => {
          console.warn("video decoder", e); window.pcrError && window.pcrError("Декодер: " + (e.message || e));
          try { vdec.close(); } catch {} vdec = null; waitKey = true;
          if (frameW > 1920 && decodeMaxW > 1920) {   // too big for this phone: a smaller picture, not JPEG
            decodeMaxW = frameW > 2560 ? 2560 : 1920; announceCodecs(); return;
          }
          send({ t: "video", off: true }); show("Видео недоступно, перехожу на JPEG");
        },
      });
      vdec.configure({ codec: codec === 1 ? "avc1.42E01E" : codec === 3 ? "avc1.64001F" : "vp8", optimizeForLatency: true });
      vcodec = codec; waitKey = true; return true;
    } catch (e) { vdec = null; send({ t: "video", off: true }); return false; }
  }
  let keyAskedAt = 0;
  const frameRects = new Map();   // pts -> rect of a region frame, read back when the decoder outputs it
  const frameKeys = new Set();    // pts of key frames (a full key frame resets the composed picture's size)
  function requestKey() {
    if (Date.now() - keyAskedAt < 2000) return;
    keyAskedAt = Date.now(); send({ t: "keyreq" });
  }
  function decodeFrame(buf) {
    const u = new Uint8Array(buf); const key = !!(u[1] & 1), region = !!(u[1] & 2), codec = u[2];
    const v = new DataView(buf); const pts = Number(v.getBigUint64(3, true));
    let off = 11, rect = null;
    if (region) { rect = { x: v.getUint16(11, true) / 65535, y: v.getUint16(13, true) / 65535, w: v.getUint16(15, true) / 65535, h: v.getUint16(17, true) / 65535 }; off = 19; }
    if (!ensureDecoder(codec)) return;
    if (waitKey && !key) { requestKey(); return; }   // decoder (re)started: ask instead of waiting for the GOP
    waitKey = false;
    if (rect) frameRects.set(pts, rect); else frameRects.delete(pts);
    if (key) frameKeys.add(pts); else frameKeys.delete(pts);
    if (frameKeys.size > 64) frameKeys.delete(frameKeys.values().next().value);
    if (frameRects.size > 64) frameRects.delete(frameRects.keys().next().value);
    try { vdec.decode(new EncodedVideoChunk({ type: key ? "key" : "delta", timestamp: pts, data: buf.slice(off) })); }
    catch (e) { waitKey = true; }
  }
  document.querySelectorAll("#capture button").forEach((b) => {
    b.classList.toggle("on", b.dataset.capture === (prefs.capture || "auto"));
    b.onclick = () => { prefs.capture = b.dataset.capture; savePrefs(); document.querySelectorAll("#capture button").forEach((x) => x.classList.toggle("on", x === b)); send({ t: "capture", mode: prefs.capture }); buzz(8); };
  });
  const sendCapture = () => { if (prefs.capture && prefs.capture !== "auto") send({ t: "capture", mode: prefs.capture }); };
  document.querySelectorAll("#installApps button").forEach((b) => { b.onclick = () => { if (window.PcRemoteApp && PcRemoteApp.installFromPc) { PcRemoteApp.installFromPc(b.dataset.apk); buzz(8); } else show("Только в приложении «Мой ПК»"); }; });
  $("appUpdate").onclick = () => {
    if (window.PcRemoteApp && PcRemoteApp.checkUpdate) { PcRemoteApp.checkUpdate(); buzz(8); watchUpdate(); }
    else show("Обновления приложения — только в «Мой ПК» на Android; в браузере обновлять нечего");
  };
  // the in-app updater's progress (Мой ПК 1.172+): download with resume, then Android's own "Установить"
  let updTimer = null;
  function watchUpdate() {
    if (!(window.PcRemoteApp && PcRemoteApp.updState)) return;
    clearInterval(updTimer);
    const kb = (n) => Math.round(n / 1024) + " КБ";
    updTimer = setInterval(() => {
      let s; try { s = JSON.parse(PcRemoteApp.updState()); } catch { return; }
      const el = $("updState"); if (!el) return;
      const what = { check: "Проверяю версии на ПК…", download: `Качаю ${s.app} ${s.note}: ${kb(s.done)}${s.total > 0 ? " из " + kb(s.total) : ""}`,
                     install: `${s.app} ${s.note}`, done: s.note === "всё свежее" ? "Все приложения свежие" : "Готово",
                     error: "Не вышло: " + s.note + ". Повторное нажатие докачает с того же места." }[s.phase] || "";
      el.textContent = what; el.hidden = !what;
      if (!s.running && (s.phase === "done" || s.phase === "error" || s.phase === "idle")) { clearInterval(updTimer); updTimer = null; }
    }, 600);
  }
  setTimeout(watchUpdate, 2500);   // an update started at launch shows its progress too
  $("adaptOn").checked = prefs.adapt !== false;
  const sendAdapt = () => send({ t: "adapt", on: prefs.adapt !== false });
  $("adaptOn").onchange = () => { prefs.adapt = $("adaptOn").checked; savePrefs(); sendAdapt(); show(prefs.adapt ? "Качество подстраивается под канал" : "Качество фиксировано: как выбрано в профиле"); };
  $("videoOn").checked = prefs.video !== false;
  $("videoOn").onchange = () => { prefs.video = $("videoOn").checked; savePrefs(); announceCodecs(); };

  // HD zone: a native-resolution patch drawn over the base picture
  let zoneRect = null, zoneImg = null, zoneUrl = null;
  function drawZone(buf) {
    const v = new DataView(buf, 1, 8);
    zoneRect = { x: v.getUint16(0, true) / 1e4, y: v.getUint16(2, true) / 1e4, w: v.getUint16(4, true) / 1e4, h: v.getUint16(6, true) / 1e4 };
    const url = URL.createObjectURL(new Blob([buf.slice(9)], { type: "image/jpeg" }));
    const im = new Image();
    im.onload = () => { if (zoneUrl) URL.revokeObjectURL(zoneUrl); zoneUrl = url; zoneImg = im; paintZone(); present(); };
    im.src = url;
  }
  function paintZone() {
    if (!zoneImg || !zoneRect || !frameW) return;
    ctx.drawImage(zoneImg, zoneRect.x * buf.width, zoneRect.y * buf.height, zoneRect.w * buf.width, zoneRect.h * buf.height);
  }
  function clearCanvas() {
    frameW = frameH = 0; ctx.clearRect(0, 0, buf.width, buf.height); present(); canvas.classList.remove("live");
    $("bVideo").hidden = true; $("bZone").hidden = true; zoneRect = zoneImg = null;
    try { vdec && vdec.close(); } catch {} vdec = null; waitKey = true;
  }
  setInterval(() => {
    $("spdDown").textContent = "↓ " + fmtSpeed(bytesIn); $("spdUp").textContent = "↑ " + fmtSpeed(bytesOut);
    $("spdDown").classList.toggle("hot", bytesIn > 2048); $("spdUp").classList.toggle("hot", bytesOut > 2048);
    bytesIn = 0; bytesOut = 0;
    if (pcOnline) {
      const parts = [pcHost];
      fpsShown = frames;
      if (frames) parts.push(`${frames} к/с`);
      if (latency) parts.push(`${latency} мс`);
      subEl.textContent = parts.join(" · ");
      signal.className = "signal " + (latency ? (latency < 120 ? "s3" : latency < 350 ? "s2" : "s1") : "s3");
      $("stats").textContent = `Профиль: ${{ tiny: "10 КБ/с", eco: "эконом", normal: "обычный", hq: "максимум" }[profile]}` +
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
  // Opus packets (0x0A): WebCodecs decodes to 48 kHz float, then the same queue as PCM
  let adec = null, opusTs = 0;
  function queuePcmF32(f32, rate) {
    const ab = actx.createBuffer(1, f32.length, rate);
    ab.getChannelData(0).set(f32);
    const src = actx.createBufferSource(); src.buffer = ab; src.connect(actx.destination);
    const now = actx.currentTime;
    if (playAt < now + 0.05) playAt = now + 0.08;
    if (playAt > now + 0.6) playAt = now + 0.1;
    src.start(playAt); playAt += ab.duration;
  }
  function playOpus(buf) {
    if (!audioOn || !("AudioDecoder" in window)) return;
    if (!actx) { actx = new (window.AudioContext || window.webkitAudioContext)(); playAt = 0; }
    if (!adec || adec.state === "closed") {
      try {
        adec = new AudioDecoder({
          output: (ad) => { const f32 = new Float32Array(ad.numberOfFrames); ad.copyTo(f32, { planeIndex: 0, format: "f32-planar" }); queuePcmF32(f32, ad.sampleRate); ad.close(); },
          error: (e) => { pcrError("Opus: " + e.message); try { adec.close(); } catch {} adec = null; } });
        adec.configure({ codec: "opus", sampleRate: 48000, numberOfChannels: 1 });
        opusTs = 0;
      } catch (e) { pcrError("Opus: " + e.message); adec = null; return; }
    }
    try { adec.decode(new EncodedAudioChunk({ type: "key", timestamp: opusTs, data: buf.slice(1) })); opusTs += 20000; } catch (e) { try { adec.close(); } catch {} adec = null; }
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
  // how many device pixels of the PC's width the phone really shows: the PC sends no more than that
  let viewSent = 0, viewTimer = null;
  function reportView() {
    if (!frameW || !frameH) return;
    const dpr = window.devicePixelRatio || 1, s = base * zoom;
    const shown = Math.min(view.clientWidth, view.clientHeight * frameW / frameH) * dpr;   // device px of what we show
    const w = Math.ceil(shown / 160) * 160;   // steps of 160 px: the encoder restarts only on real changes
    // zoomed in: the part of the screen that is on the display, in 1/64 steps (fractions of the frame)
    let rect = null;
    if (zoom > 1.2) {
      const q = (v) => Math.max(0, Math.min(1, Math.round(v * 64) / 64));
      const x = q(-panX / (s * frameW)), y = q(-panY / (s * frameH));
      rect = { x, y, w: q(Math.min(1 - x, view.clientWidth / (s * frameW))), h: q(Math.min(1 - y, view.clientHeight / (s * frameH))) };
    }
    const key = w + "|" + JSON.stringify(rect);
    if (key === viewSent) return;
    clearTimeout(viewTimer);
    viewTimer = setTimeout(() => { viewSent = key; send({ t: "view", w, rect }); }, 400);
  }
  function applyTransform() {
    reportView();
    const s = base * zoom;
    const vw = view.clientWidth, vh = view.clientHeight, w = frameW * s, h = frameH * s;
    panX = w <= vw ? (vw - w) / 2 : Math.min(0, Math.max(vw - w, panX));
    panY = h <= vh ? (vh - h) / 2 : Math.min(0, Math.max(vh - h, panY));
    canvas.style.transform = `translate(${panX}px, ${panY}px) scale(${s})`;
    fitDisplay(false);
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
    const p = trackpad.checked ? cur : pcCur;   // the trackpad shows where it is sending the cursor, else the PC's own
    if (!p || !pcOnline || !frameW) { cursorEl.hidden = true; return; }
    const m = new DOMMatrix(getComputedStyle(canvas).transform);
    cursorEl.hidden = false;
    cursorEl.style.left = (m.e + p.x * frameW * m.a) + "px";
    cursorEl.style.top = (m.f + p.y * frameH * m.d) + "px";
  }

  // -------------------------------------------------------------- touch
  const trackpad = $("trackpad");
  let mouseMode = localStorage.getItem("pcr_mouse") || (localStorage.getItem("pcr_trackpad") === "1" ? "trackpad" : "direct");
  function setMouseMode(mode) {
    mouseMode = mode; localStorage.setItem("pcr_mouse", mode); trackpad.checked = mode !== "direct";
    document.querySelectorAll("#mouseMode button").forEach((b) => b.classList.toggle("on", b.dataset.mouse === mode));
    placeCursor(); gyro(mode === "gyro");
  }
  // gyroscope: tilt the phone to glide the cursor (relative, like an air mouse)
  let gyroOn = false, gyroLast = 0;
  function onMotion(e) {
    const r = e.rotationRate; if (!r || !pcOnline || !frameW || isHidden()) return;
    const now = performance.now(); if (now - gyroLast < 33) return; gyroLast = now;
    const dx = -(r.alpha || 0) * 0.0025, dy = -(r.beta || 0) * 0.0025;   // deg/s -> screen fraction per tick
    if (Math.abs(dx) < 0.0015 && Math.abs(dy) < 0.0015) return;
    cur.x = Math.min(1, Math.max(0, cur.x + dx)); cur.y = Math.min(1, Math.max(0, cur.y + dy));
    placeCursor(); send({ t: "move", ...cur });
  }
  async function gyro(on) {
    if (on && !gyroOn) {
      try { if (DeviceMotionEvent.requestPermission) await DeviceMotionEvent.requestPermission(); } catch { show("Нет доступа к гироскопу"); return; }
      window.addEventListener("devicemotion", onMotion); gyroOn = true; show("Гироскоп: наклоняйте телефон, касание — клик");
    } else if (!on && gyroOn) { window.removeEventListener("devicemotion", onMotion); gyroOn = false; }
  }
  document.querySelectorAll("#mouseMode button").forEach((b) => (b.onclick = () => { setMouseMode(b.dataset.mouse); buzz(8); }));
  setTimeout(() => setMouseMode(mouseMode), 0);

  let pcCur = null;   // where the PC says its cursor is (its own channel)
  let pts = new Map(), t0 = null, longTimer = null, dragging = false, moved = false;
  let cur = { x: 0.5, y: 0.5 }, lastTap = 0, scrollAcc = 0, pinch = null;
  // trackpad: tap-then-touch-and-move drags (button held), two-finger tap = right click, speed-dependent gain
  let dragArm = false, tapEndAt = 0, twoAt = 0, twoMoved = false, lastMoveAt = 0, pcW = 1920, pcH = 1080;

  view.addEventListener("touchstart", (e) => {
    if (!pcOnline) return;
    e.preventDefault(); menu.hidden = true;
    for (const t of e.changedTouches) pts.set(t.identifier, { x: t.clientX, y: t.clientY });
    if (e.touches.length === 1) {
      const t = e.touches[0];
      t0 = { x: t.clientX, y: t.clientY, time: Date.now() }; moved = false; dragging = false; lastMoveAt = 0;
      dragArm = trackpad.checked && Date.now() - tapEndAt < 300;   // a tap just before: this touch drags
      if (!trackpad.checked) { cur = toPC(t.clientX, t.clientY); send({ t: "move", ...cur }); }
      if (!dragArm) longTimer = setTimeout(() => { longTimer = null; buzz(30); send({ t: "click", b: "right", n: 1, ...cur }); ripple(t.clientX, t.clientY, true); t0 = null; }, 550);
    } else if (e.touches.length === 2) {
      clearTimeout(longTimer); longTimer = null; t0 = null;
      if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }
      twoAt = Date.now(); twoMoved = false;
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
        if (pinch.mode) twoMoved = true;
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
      // in PC pixels, like a laptop pad: slow finger = 1 PC px per screen point (precise), fast = up to 6
      const prev = pts.get(t.identifier), now = performance.now();
      const dt = lastMoveAt ? Math.max(1, now - lastMoveAt) : 16; lastMoveAt = now;
      const ddx = t.clientX - prev.x, ddy = t.clientY - prev.y, v = Math.hypot(ddx, ddy) / dt;   // points per ms
      const gain = 1 + Math.min(5, Math.max(0, (v - 0.15) * 4));
      cur.x = Math.min(1, Math.max(0, cur.x + ddx * gain / pcW));
      cur.y = Math.min(1, Math.max(0, cur.y + ddy * gain / pcH));
      if (dragArm && !dragging) { dragging = true; send({ t: "btn", b: "left", down: true }); buzz(15); }
      placeCursor();
    } else {
      if (!dragging) { dragging = true; send({ t: "btn", b: "left", down: true }); }
      cur = toPC(t.clientX, t.clientY);
      const d = document.createElement("div"); d.className = "trail"; const b = view.getBoundingClientRect();
      d.style.left = (t.clientX - b.left) + "px"; d.style.top = (t.clientY - b.top) + "px"; ripples.appendChild(d); setTimeout(() => d.remove(), 500);
    }
    sendMove(cur);
    pts.set(t.identifier, { x: t.clientX, y: t.clientY });
  }, { passive: false });
  let moveAt = 0, moveTimer = null, movePending = null, padSentAt = 0;
  function sendMove(p) {
    const now = Date.now();
    padSentAt = now;   // the PC's cursor reports are echoes of this for a while (see "cur")
    if (now - moveAt >= 40) { moveAt = now; movePending = null; send({ t: "move", x: p.x, y: p.y }); return; }
    movePending = { x: p.x, y: p.y };   // the latest position wins; it goes out when the 40 ms are up
    if (!moveTimer) moveTimer = setTimeout(() => { moveTimer = null; if (movePending) { moveAt = Date.now(); send({ t: "move", ...movePending }); movePending = null; } }, 40 - (now - moveAt));
  }

  view.addEventListener("touchend", (e) => {
    if (!pcOnline) return;
    e.preventDefault();
    for (const t of e.changedTouches) pts.delete(t.identifier);
    if (e.touches.length > 0) return;
    pinch = null;
    if (twoAt) {   // two fingers down and up without scrolling or zooming: right click
      const quick = !twoMoved && Date.now() - twoAt < 350; twoAt = 0;
      if (quick) { send({ t: "click", b: "right", n: 1, ...cur }); buzz(20); const t = e.changedTouches[0]; if (t) ripple(t.clientX, t.clientY, true); }
      t0 = null; dragArm = false; return;
    }
    if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }
    else if (t0 && !moved && (longTimer || dragArm)) {
      clearTimeout(longTimer); longTimer = null; tapEndAt = Date.now();
      const now = Date.now(), dbl = now - lastTap < 350; lastTap = dbl ? 0 : now;
      send({ t: "click", b: "left", n: dbl ? 2 : 1, ...cur }); buzz(8); sfx("click");
      const t = e.changedTouches[0]; if (t) ripple(t.clientX, t.clientY, false);
    }
    t0 = null; dragArm = false;
  }, { passive: false });
  view.addEventListener("touchcancel", () => {
    clearTimeout(longTimer); longTimer = null; t0 = null; pinch = null; pts.clear(); twoAt = 0; dragArm = false;
    if (dragging) { send({ t: "btn", b: "left", down: false }); dragging = false; }   // never leave the button held
  });
  $("zoomReset").onclick = () => { zoom = 1; panX = panY = 0; applyTransform(); menu.hidden = true; };

  // ---- volume slider (PC master volume via the agent)
  let volDragging = false, volTimer = null;
  function applyVolume(v) {
    if (v.level == null) return;
    $("volRow").hidden = false;
    if (!volDragging) { $("volRange").value = v.level; $("volVal").textContent = v.level; }
    $("volRow").classList.toggle("muted", !!v.mute);
  }
  $("volRange").addEventListener("input", () => { volDragging = true; $("volVal").textContent = $("volRange").value;
    clearTimeout(volTimer); volTimer = setTimeout(() => send({ t: "volume", level: +$("volRange").value }), 120); });
  $("volRange").addEventListener("change", () => { volDragging = false; send({ t: "volume", level: +$("volRange").value }); buzz(6); });
  $("muteBtn").onclick = () => { send({ t: "volume", mute: !$("volRow").classList.contains("muted") }); buzz(8); };
  $("menuBtn").addEventListener("click", () => { if (pcOnline) send({ t: "volume_get" }); });

  // ---- Android-style nav: ◁ back, ○ start menu, □ task view, ▽ minimize window; edge swipes
  const NAV = { back: () => tapKey("BrowserBack"), home: () => tapKey("Meta"), recent: () => openWindows(),
                min: () => send({ t: "combo", keys: ["Meta", "ArrowDown"] }) };
  $("navbar").addEventListener("click", (e) => { const b = e.target.closest("button[data-nav]"); if (!b || !pcOnline) return; NAV[b.dataset.nav](); buzz(10); });
  $("navbar").addEventListener("touchstart", (e) => e.stopPropagation(), { passive: true });
  $("navbarOn").checked = prefs.navbar !== false; $("edgeOn").checked = prefs.edges !== false;
  const applyNav = () => { $("navbar").hidden = !$("navbarOn").checked; };
  $("navbarOn").onchange = () => { prefs.navbar = $("navbarOn").checked; savePrefs(); applyNav(); };
  $("edgeOn").onchange = () => { prefs.edges = $("edgeOn").checked; savePrefs(); };
  applyNav();
  let edge = null;
  view.addEventListener("touchstart", (e) => {
    if (!$("edgeOn").checked || e.touches.length !== 1) { edge = null; return; }
    const t = e.touches[0], r = view.getBoundingClientRect();
    if (t.clientY > r.bottom - 22) edge = { kind: "bottom", y: t.clientY };
    else if (t.clientX < r.left + 18) edge = { kind: "left", x: t.clientX };
    else edge = null;
  }, { passive: true, capture: true });
  view.addEventListener("touchend", (e) => {
    if (!edge) return;
    const t = e.changedTouches[0];
    if (edge.kind === "bottom" && edge.y - t.clientY > 60) { NAV.recent(); buzz(15); const h = document.createElement("div"); h.className = "edge-hint"; view.appendChild(h); setTimeout(() => h.remove(), 600); }
    if (edge.kind === "left" && t.clientX - edge.x > 70) { NAV.back(); buzz(15); }
    edge = null;
  }, { passive: true, capture: true });

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
    kbPanel.hidden = !kbPanel.hidden; $("kbBtn").classList.toggle("on", !kbPanel.hidden);
    if (!kbPanel.hidden) kbInput.focus(); else kbInput.blur();
    setTimeout(layout, 50);
  };
  // Android keyboards (Gboard) report Backspace on an empty field as nothing at all: keep an
  // invisible sentinel in the field, and its disappearance means "Backspace".
  const ZW = "\u200b";
  const resetKb = () => { kbInput.value = ZW; try { kbInput.setSelectionRange(1, 1); } catch {} };
  resetKb();
  kbInput.addEventListener("focus", resetKb);
  kbInput.addEventListener("input", (e) => {
    const v = kbInput.value;
    if (!v.includes(ZW)) tapKey("Backspace");
    if (e.inputType === "deleteContentForward") tapKey("Delete");
    const s = v.replace(/\u200b/g, "");
    if (s) {
      if (mods.size) { for (const ch of s) tapKey(ch.toLowerCase()); }
      else send({ t: "text", s });
    }
    resetKb();
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
  // pages (settings, files, terminal) and sheets (menu, power) + dock state
  const pages = ["settingsPage", "filesPage", "termPanel", "sysPage", "trafficPage"];
  const hideSheets = () => { menu.hidden = true; for (const id of ["powerSheet", "winSheet", "zoneSheet"]) $(id).hidden = true; };
  function showPage(id) { for (const p of pages) $(p).hidden = p !== id; hideSheets(); dockState(id); }
  function closePages() { for (const p of pages) $(p).hidden = true; dockState("screen"); }
  function dockState(id) { for (const [bid, pid] of [["dockScreen", "screen"], ["filesBtn", "filesPage"], ["termBtn", "termPanel"]]) $(bid).classList.toggle("on", pid === id); }
  document.querySelectorAll("[data-close]").forEach((b) => (b.onclick = closePages));
  $("dockScreen").onclick = () => { closePages(); hideSheets(); };
  $("menuBtn").onclick = () => { const was = menu.hidden; hideSheets(); menu.hidden = !was; if (!menu.hidden) tidySections(); };
  $("powerBtn").onclick = () => { menu.hidden = true; $("powerSheet").hidden = false; };
  $("settingsBtn").onclick = () => showPage("settingsPage");
  document.addEventListener("click", async (e) => {
    const p = e.target.closest("#profile button[data-profile]");
    if (p) { setProfile(p.dataset.profile); return; }
    const q = e.target.closest("#pq button[data-pq]");
    if (q) { setPq(q.dataset.pq); return; }
    const b = e.target.closest("#powerSheet button[data-cmd]"); if (!b) return;
    const names = { reboot: "Перезагрузить ПК?", shutdown: "Выключить ПК?", sleep: "Перевести ПК в сон?",
                    lock: "Заблокировать ПК?", cancel: "Отменить выключение?" };
    $("powerSheet").hidden = true;
    if (!pcOnline) { show("ПК не в сети"); return; }
    if (b.dataset.cmd !== "cancel" && !(await ask(names[b.dataset.cmd]))) return;
    send({ t: "cmd", cmd: b.dataset.cmd }); buzz(20);
  });
  // the traffic ceiling: what each choice gives and costs, in the owner's words (06.10.2026: "напиши понятные скорости")
  const PROFILE_HINT = {
    tiny: "Не больше 15 КБ/с — мобильный интернет на пределе. Картинка 480 px, кадров сколько успеет сеть (обычно 2–6 в секунду), звук 16 кбит/с. Час работы — не больше 54 МБ, неподвижный экран почти ничего не стоит.",
    eco: "Не больше 100 КБ/с — нормальный мобильный интернет. Картинка до 960 px. Час работы — не больше 360 МБ; пока на экране ничего не меняется, трафика почти нет.",
    normal: "Не больше 1 МБ/с — домашний Wi-Fi или хороший 4G. Картинка во весь экран ПК, на Wi-Fi в его родном разрешении. Тратит много только при постоянном движении (фильм, игра).",
    hq: "Без потолка — сколько даст сеть. На Wi-Fi родное разрешение ПК вплоть до 4K, если телефон его декодирует, и до 20 кадров в секунду.",
  };
  const PQ_HINT = {
    smooth: "Кадр примерно на четверть легче: кадров в секунду больше, картинка мягче. Для фильмов и игр на слабой сети.",
    normal: "Баланс: текст читается, движение не рвётся.",
    sharp: "Каждый кадр чётче и примерно на четверть тяжелее — кадров в секунду меньше. Для текста, таблиц, кода.",
    max: "Самый чистый кадр, примерно в 1,6 раза тяжелее обычного: на слабом мобильном 1–2 кадра в секунду, зато каждый — как на ПК.",
  };
  function setProfile(name, auto = false) {
    profile = name; localStorage.setItem("pcr_profile", name);
    if (!auto) localStorage.setItem("pcr_profile_manual", name);
    document.querySelectorAll("#profile button").forEach((b) => b.classList.toggle("on", b.dataset.profile === name));
    $("profileHint").textContent = PROFILE_HINT[name] || "";
    $("ecoBadge").hidden = name !== "eco" && name !== "tiny";
    $("ecoBadge").textContent = name === "tiny" ? "≤15 КБ/с" : "≤100 КБ/с";
    send({ t: "profile", name });
  }
  setProfile(profile);
  // the picture quality, separately: how heavy (and so how sharp) each frame is
  let pq = localStorage.getItem("pcr_pq") || "normal";
  function setPq(level) {
    pq = PQ_HINT[level] ? level : "normal"; localStorage.setItem("pcr_pq", pq);
    document.querySelectorAll("#pq button").forEach((b) => b.classList.toggle("on", b.dataset.pq === pq));
    $("pqHint").textContent = PQ_HINT[pq];
    send({ t: "pq", level: pq });
  }
  setPq(pq);
  // auto profile: cellular -> eco, otherwise the chosen one
  const conn = navigator.connection;
  function autoProfile() {
    if (!$("autoProfile").checked || !conn) return;
    const cellular = conn.type === "cellular" || /2g|3g/.test(conn.effectiveType || "");
    const want = cellular ? "eco" : "hq";
    if (want !== profile) { setProfile(want, true); show(cellular ? "Мобильная сеть: до 100 КБ/с" : "Wi-Fi: без ограничений"); }
  }
  $("autoProfile").checked = prefs.autoProfile === true;
  $("autoProfile").onchange = () => { prefs.autoProfile = $("autoProfile").checked; savePrefs(); autoProfile(); };
  conn && conn.addEventListener && conn.addEventListener("change", autoProfile);
  setTimeout(autoProfile, 1500);
  let immersive = false;
  $("fsBtn").onclick = () => {
    menu.hidden = true;
    // inside the app the Fullscreen API is unavailable: ask the activity to hide the system bars
    if (window.PcRemoteApp && window.PcRemoteApp.fullscreen) { immersive = !immersive; window.PcRemoteApp.fullscreen(immersive); show(immersive ? "Весь экран: свайп от края вернёт панели" : "Обычный режим"); setTimeout(layout, 300); return; }
    if (document.fullscreenElement) document.exitFullscreen?.(); else document.documentElement.requestFullscreen?.();
  };
  $("wakeScreenBtn").onclick = () => {
    const b = $("wakeScreenBtn"); b.classList.add("busy"); b.textContent = "Бужу экран…"; buzz(15);
    send({ t: "device", op: "monitor_on" });
    setTimeout(() => { b.classList.remove("busy"); b.textContent = "Разбудить экран"; }, 3000);
  };

  // ---- perks: accent, sounds, haptics, screenshot, paste, link, hints, clock
  $("theme").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-theme]"); if (!b) return;
    prefs.theme = b.dataset.theme; savePrefs(); applyTheme(); buzz(8);
    if (typeof terms !== "undefined") for (const t of terms.values()) { try { t.term.options.theme = termTheme(); } catch {} }
  });
  $("accent").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-accent]"); if (!b) return;
    prefs.accent = b.dataset.accent; savePrefs(); applyAccent(); buzz(8);
  });
  // "only on the phone": a switch, not a device. The PC picks a silent output itself; the
  // dropdown under it is only for overriding that guess and shows only while the switch is on.
  const sendAudioSrc = () => send({ t: "audio_source", src: prefs.audioSrc || "speakers", only: !!prefs.audioOnly, device: prefs.audioDevice || "" });
  function renderAudioDevices(items) {
    const sel = $("audioDev"); sel.innerHTML = "";
    const o0 = document.createElement("option"); o0.value = ""; o0.textContent = "авто (HDMI монитора, S/PDIF, виртуальный кабель)"; sel.appendChild(o0);
    for (const d of items || []) { const o = document.createElement("option"); o.value = d.id; o.textContent = d.name + (d.default ? " (сейчас по умолчанию)" : ""); sel.appendChild(o); }
    if (prefs.audioDevice && ![...sel.options].some((o) => o.value === prefs.audioDevice)) { const o = document.createElement("option"); o.value = prefs.audioDevice; o.textContent = "(устройство не найдено)"; sel.appendChild(o); }
    sel.value = prefs.audioDevice || "";
    $("audioDevBox").hidden = !(items && items.length);
    $("audioDevRow").hidden = !prefs.audioOnly;
  }
  $("audioOnly").checked = !!prefs.audioOnly;
  $("audioOnly").onchange = () => { prefs.audioOnly = $("audioOnly").checked; savePrefs(); $("audioDevRow").hidden = !prefs.audioOnly; sendAudioSrc(); buzz(8);
    show(prefs.audioOnly ? "Пока телефон слушает, динамики ПК молчат" : "Звук слышно и на ПК, и на телефоне"); };
  $("audioDev").onchange = () => { prefs.audioDevice = $("audioDev").value; savePrefs(); sendAudioSrc(); buzz(8); };
  document.querySelectorAll("#audioSrc button").forEach((b) => {
    b.classList.toggle("on", b.dataset.src === (prefs.audioSrc || "speakers"));
    b.onclick = () => { prefs.audioSrc = b.dataset.src; savePrefs(); document.querySelectorAll("#audioSrc button").forEach((x) => x.classList.toggle("on", x === b)); sendAudioSrc(); buzz(8); };
  });
  const RULES = { ruleMonOn: "on_connect_monitor", ruleMonOff: "on_disconnect_monitor_off", ruleLock: "on_disconnect_lock", ruleNotify: "notify_phone" };
  const RULE_DEFAULT = { notify_phone: true };
  const ruleVal = (k) => { const v = (prefs.rules || {})[k]; return v === undefined ? !!RULE_DEFAULT[k] : !!v; };
  const sendRules = () => { const r = { t: "rules" }; for (const k in RULES) r[RULES[k]] = ruleVal(RULES[k]); send(r); };
  for (const id in RULES) {
    $(id).checked = ruleVal(RULES[id]);
    $(id).onchange = () => { prefs.rules = prefs.rules || {}; prefs.rules[RULES[id]] = $(id).checked; savePrefs(); sendRules(); };
  }
  $("sfx").checked = prefs.sfx === true; $("haptic").checked = prefs.haptic !== false;
  $("sfx").onchange = () => { prefs.sfx = $("sfx").checked; savePrefs(); sfx("ok"); };
  $("haptic").onchange = () => { prefs.haptic = $("haptic").checked; savePrefs(); buzz(20); };

  $("shotBtn").onclick = () => {
    menu.hidden = true;
    if (!frameW) { show("Нет кадра"); return; }
    buf.toBlob((b) => openViewer(URL.createObjectURL(b), `pc-${new Date().toISOString().slice(0, 19).replace(/[T:]/g, "-")}.png`), "image/png");
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
    $("castBox").hidden = false; $("castBtn").hidden = false;
    const refreshCast = () => {
      const on = bridge.isCasting();
      $("castBtn").innerHTML = on ? "<i>⏹</i>Стоп трансл." : "<i>📱</i>Трансляция";
      $("muteRow").hidden = !on;
      $("phoneMute").checked = bridge.isPhoneMuted();
    };
    $("castBtn").onclick = () => { bridge.isCasting() ? bridge.stopCast() : bridge.startCast(); menu.hidden = true; setTimeout(refreshCast, 800); };
    $("phoneMute").onchange = () => bridge.setPhoneMute($("phoneMute").checked);
    $("menuBtn").addEventListener("click", refreshCast);
    refreshCast();
  }
  // ---- phone files for the PC (Claude's `phone` command), Android only
  if (bridge && bridge.setFiles) {
    $("pfsBox").hidden = false;
    $("pfsOn").checked = bridge.filesEnabled();
    $("pfsOn").onchange = () => { bridge.setFiles($("pfsOn").checked); setTimeout(() => { $("pfsOn").checked = bridge.filesEnabled(); }, 1500); };
    $("settingsBtn").addEventListener("click", () => { $("pfsOn").checked = bridge.filesEnabled(); });
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
      const r = await fetch("/api/wake", { method: "POST", headers: authHeaders() }).catch(() => null);
      const viaPhone = !r && window.PcRemoteApp && window.PcRemoteApp.wakePc && window.PcRemoteApp.wakePc();
      if (!r && !viaPhone) throw new Error("нет связи с ПК");
      const j = viaPhone ? { ok: true } : await r.json();
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
    showPage("termPanel");
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
      row.innerHTML = `<b>${esc(name)}</b><br>`;
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
  // xterm (290 KB) only when a terminal is opened: not every session needs one, a thin link needs none
  let xtermLoading = null;
  function ensureXterm() {
    if (window.Terminal && window.FitAddon) return Promise.resolve();
    if (!xtermLoading) {
      show("Загружаю терминал…");
      const v = window.PCR_V ? "?v=" + window.PCR_V : "";
      const css = document.createElement("link"); css.rel = "stylesheet"; css.href = "/static/vendor/xterm.css" + v; document.head.appendChild(css);
      const load = (src) => new Promise((ok, bad) => { const s = document.createElement("script"); s.src = src; s.onload = ok; s.onerror = bad; document.head.appendChild(s); });
      xtermLoading = load("/static/vendor/xterm.js" + v).then(() => load("/static/vendor/addon-fit.js" + v)).catch((e) => { xtermLoading = null; show("Терминал не загрузился", 4000); throw e; });
    }
    return xtermLoading;
  }
  function newTerm(kind, cwd) {
    if (!window.Terminal || !window.FitAddon) { ensureXterm().then(() => newTerm(kind, cwd)); return; }
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
      b.innerHTML = `${esc(name)}${t.exited ? " <i>·</i>" : ""} <i data-close="${esc(id)}">✕</i>`;
      b.onclick = (e) => { if (e.target.dataset.close) closeTerm(e.target.dataset.close); else showTerm(id); };
      tabs.appendChild(b);
    }
  }
  // Claude Code phrases -> Russian. Chunks are held for 30 ms so a phrase split between two
  // packets still gets translated; the PC's console itself is untouched.
  const ccRe = (window.CC_RU || []).slice().sort((a, b) => b[0].length - a[0].length).map(([en, ru]) => [new RegExp(en.replace(/[.*+?^${}()|[\]\\]/g, "\\$&") + (/[\w)]$/.test(en) ? "(?![\\w-])" : ""), "g"), ru]);
  function ccTranslate(s) { if (prefs.termRu === false) return s; for (const [re, ru] of ccRe) s = s.replace(re, ru); return s; }
  function termOut(id, data) {
    const t = terms.get(id); if (!t) return;
    if (prefs.termRu === false) { t.term.write(data); return; }
    t.buf = (t.buf || "") + data; clearTimeout(t.flush);
    const go = () => { const b = t.buf; t.buf = ""; if (b) t.term.write(ccTranslate(b)); };
    if (/\n$/.test(data) || t.buf.length > 4000) go(); else t.flush = setTimeout(go, 30);
  }
  function termExit(id) { const t = terms.get(id); if (!t) return; t.exited = true; t.term.write("\r\n\x1b[90m[сессия завершена]\x1b[0m\r\n"); renderTabs(); }
  async function closeTerm(id) {
    const t = terms.get(id); if (!t) return;
    if (!t.exited && !(await ask("Закрыть эту сессию? Процесс на ПК (Claude, оболочка) будет остановлен."))) return;
    send({ t: "term_close", id }); t.term.dispose(); terms.delete(id);
    if (activeTerm === id) activeTerm = terms.keys().next().value || null;
    terms.size ? showTerm(activeTerm) : renderTermEmpty();
  }
  function termSend(data) { if (!activeTerm) return show("Сначала запустите сессию"); send({ t: "term_in", id: activeTerm, data }); }
  $("termBtn").onclick = openTermPanel;
  $("termMenuBtn").onclick = openTermPanel;
  $("termBack").onclick = closePages;
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
  $("termRuBtn").onclick = () => { $("ccDlg").hidden = false; const L = $("ccList"); L.innerHTML = "";
    for (const [en, ru] of window.CC_RU || []) { if (en.length < 4) continue; const d = document.createElement("div"); d.className = "it"; d.innerHTML = `<span class="n"><b>${esc(en)}</b><span class="m">${esc(ru)}</span></span>`; L.appendChild(d); } };
  $("ccClose").onclick = () => ($("ccDlg").hidden = true);
  $("termRu").checked = prefs.termRu !== false;
  $("termRu").onchange = () => { prefs.termRu = $("termRu").checked; savePrefs(); show(prefs.termRu ? "Подсказки Claude Code переводятся" : "Перевод выключен"); };
  $("termQuickEdit").onclick = () => textDialog("Свои кнопки терминала (по одной в строке)", "/rc\n/status\ngit pull", (v) => {
    prefs.termQuick = v.split("\n").map((x) => x.trim()).filter(Boolean).slice(0, 20); savePrefs(); renderTermQuick();
  });
  setTimeout(() => { if (prefs.termQuick) $("textDlgInput").value = ""; }, 0);

  // ================================================================
  // Files: browse share folders, download to the phone, upload from the phone
  // ================================================================
  let filesPath = "";

  // ================================================================
  // Explorer: full-page file manager over /api/files, /api/fs, /api/file
  // ================================================================
  const isImage = (n) => /\.(jpe?g|png|gif|webp|bmp|avif)$/i.test(n);
  const thumbCache = new Map();
  let fPath = "", fItems = [], fSelecting = false, fSel = new Set(), fClip = null, fGridPref = prefs.filesGrid || "auto";
  const fmtSize = (n) => n > 1e9 ? `${(n / 1e9).toFixed(1)} ГБ` : n > 1e6 ? `${(n / 1e6).toFixed(1)} МБ` : `${Math.max(1, Math.round(n / 1024))} КБ`;
  const fmtDate = (t) => new Date(t * 1000).toLocaleDateString("ru-RU", { day: "2-digit", month: "2-digit", year: "2-digit" });
  const sep = (p) => (p.includes("/") && !p.includes("\\")) ? "/" : "\\";
  function openFiles(path) { showPage("filesPage"); loadFiles(path ?? fPath); }
  async function loadFiles(path) {
    fPath = path; fSel.clear(); fSelecting = false; updateSelBar();
    const r = await fetch(`/api/files?path=${encodeURIComponent(path)}`, { headers: authHeaders() });
    if (!r.ok) { $("fList").innerHTML = `<div class="empty">Нет доступа</div>`; return; }
    const j = await r.json(); fItems = j.items; fPath = j.path || "";
    renderCrumbs(); renderFiles();
  }
  function renderCrumbs() {
    const c = $("crumbs"); c.innerHTML = "";
    const root = document.createElement("button"); root.textContent = "💻 ПК"; root.onclick = () => loadFiles(""); c.appendChild(root);
    if (!fPath) { root.classList.add("cur"); return; }
    const sp = sep(fPath), parts = fPath.split(/[\\/]/).filter(Boolean);
    parts.forEach((seg, i) => {
      const sepEl = document.createElement("i"); sepEl.textContent = "›"; c.appendChild(sepEl);
      const b = document.createElement("button"); b.textContent = seg;
      const target = (fPath.startsWith("/") ? "/" : "") + parts.slice(0, i + 1).join(sp) + (i === 0 && /^[A-Za-z]:$/.test(seg) ? sp : "");
      b.onclick = () => loadFiles(target); if (i === parts.length - 1) b.classList.add("cur"); c.appendChild(b);
    });
    c.scrollLeft = c.scrollWidth;
  }
  function useGrid() {
    if (fGridPref !== "auto") return fGridPref === "grid";
    const imgs = fItems.filter((it) => !it.dir && isImage(it.name)).length;
    return imgs >= 2 && imgs >= fItems.length / 2;
  }
  function renderFiles() {
    const list = $("fList"); list.innerHTML = ""; const grid = useGrid(); list.classList.toggle("grid", grid);
    $("fView").textContent = grid ? "☰" : "▦";
    if (!fItems.length) { list.innerHTML = `<div class="empty">${fPath ? "Пусто" : "Нет общих папок"}</div>`; return; }
    for (const it of fItems) {
      const d = document.createElement("div"); d.dataset.path = it.path;
      if (grid) {
        d.className = "th" + (it.dir ? " dir" : "");
        if (it.dir) d.textContent = "📁"; else if (isImage(it.name)) { const im = document.createElement("img"); loadThumb(im, it.path); d.appendChild(im); } else d.textContent = "📄";
        const cap = document.createElement("span"); cap.textContent = it.name; d.appendChild(cap);
        const chk = document.createElement("b"); chk.className = "chk"; chk.textContent = "✓"; chk.hidden = !fSelecting; d.appendChild(chk);
      } else {
        d.className = "it";
        const icon = it.dir ? "📁" : isImage(it.name) ? "🖼" : /\.(mp4|mkv|avi|mov)$/i.test(it.name) ? "🎬" : /\.(mp3|wav|flac)$/i.test(it.name) ? "🎵" : /\.(zip|rar|7z)$/i.test(it.name) ? "🗜" : /\.(exe|msi)$/i.test(it.name) ? "⚙" : "📄";
        d.innerHTML = `<i>${icon}</i><span class="n"><b>${esc(it.name)}</b><span class="m">${it.dir ? "папка" : fmtSize(it.size)}${it.mtime ? " · " + fmtDate(it.mtime) : ""}</span></span><span class="chk" ${fSelecting ? "" : "hidden"}>✓</span>`;
      }
      d.classList.toggle("sel", fSel.has(it.path));
      let lp = null;
      d.addEventListener("touchstart", () => { lp = setTimeout(() => { lp = null; fSelecting = true; toggleSel(it); buzz(20); }, 500); }, { passive: true });
      d.addEventListener("touchmove", () => { clearTimeout(lp); lp = null; }, { passive: true });
      d.addEventListener("touchend", () => { clearTimeout(lp); }, { passive: true });
      d.onclick = () => {
        if (fSelecting) { toggleSel(it); return; }
        if (it.dir) loadFiles(it.path);
        else if (isImage(it.name)) viewImage(it);
        else { fSel.clear(); fSel.add(it.path); fSelecting = true; renderFiles(); }
      };
      list.appendChild(d);
    }
    updateSelBar();
  }
  function toggleSel(it) { fSel.has(it.path) ? fSel.delete(it.path) : fSel.add(it.path); if (!fSel.size) fSelecting = false; renderFiles(); }
  function updateSelBar() {
    $("fSelBar").hidden = !fSel.size; $("fBar").hidden = !!fSel.size; $("fPaste").hidden = !fClip || !fPath;
    $("fRename").hidden = fSel.size !== 1;
  }
  const selItems = () => fItems.filter((it) => fSel.has(it.path));
  async function fsOp(body) {
    const r = await fetch("/api/fs", { method: "POST", headers: { ...authHeaders(), "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const j = await r.json().catch(() => ({ ok: false, error: r.status }));
    if (!j.ok) show("Ошибка: " + j.error, 4000); return j.ok;
  }
  $("filesBtn").onclick = () => openFiles(fPath);
  $("fView").onclick = () => { fGridPref = useGrid() ? "list" : "grid"; prefs.filesGrid = fGridPref; savePrefs(); renderFiles(); };
  $("fSelect").onclick = () => { fSelecting = !fSelecting; if (!fSelecting) fSel.clear(); renderFiles(); };
  $("fCancelSel").onclick = () => { fSel.clear(); fSelecting = false; renderFiles(); };
  $("fOpenPC").onclick = () => { for (const it of selItems()) send({ t: "open_path", path: it.path }); show("Открываю на ПК"); $("fCancelSel").click(); };
  $("fPrint").onclick = async () => {
    const files = selItems().filter((it) => !it.dir); if (!files.length) return show("Выберите файлы");
    if (!(await ask(`Напечатать на принтере ПК: ${files.length} файл(ов)?`))) return;
    for (const it of files) send({ t: "print", path: it.path }); $("fCancelSel").click();
  };
  $("fDownload").onclick = async () => { for (const it of selItems()) if (!it.dir) await downloadFile(it); $("fCancelSel").click(); };
  $("fRename").onclick = () => { const it = selItems()[0]; if (!it) return;
    textDialog("Новое имя", it.name, async (v) => { if (await fsOp({ op: "rename", path: it.path, name: v.trim() })) loadFiles(fPath); });
    $("textDlgInput").value = it.name; };
  $("fCut").onclick = () => { fClip = { op: "move", paths: [...fSel] }; show(`Вырезано: ${fSel.size}`); $("fCancelSel").click(); };
  $("fCopy").onclick = () => { fClip = { op: "copy", paths: [...fSel] }; show(`Скопировано: ${fSel.size}`); $("fCancelSel").click(); };
  $("fPaste").onclick = async () => { if (!fClip) return; let n = 0;
    for (const p of fClip.paths) if (await fsOp({ op: fClip.op, path: p, to: fPath })) n++;
    show(`${fClip.op === "move" ? "Перемещено" : "Скопировано"}: ${n}`); fClip = null; loadFiles(fPath); };
  $("fDelete").onclick = async () => { const items = selItems(); if (!items.length) return;
    if (!(await ask(`Удалить ${items.length === 1 ? "«" + items[0].name + "»" : items.length + " элементов"}? Уйдёт в корзину ПК.`))) return;
    let n = 0; for (const it of items) if (await fsOp({ op: "delete", path: it.path })) n++; show(`Удалено: ${n}`); loadFiles(fPath); };
  $("fNew").onclick = () => { if (!fPath) return show("Сначала откройте папку"); textDialog("Новая папка", "имя", async (v) => { if (await fsOp({ op: "mkdir", path: fPath, name: v.trim() })) loadFiles(fPath); }); };
  $("fTermHere").onclick = () => { if (!fPath) return show("Сначала откройте папку"); showPage("termPanel"); newTerm((pcInfo.shells || []).includes("bash") ? "bash" : "shell", fPath); };
  $("fReveal").onclick = () => { const p = fSel.size ? [...fSel][0] : fPath; if (!p) return; send({ t: "reveal", path: p }); show("Показываю в Проводнике ПК"); };
  async function loadThumb(img, path) {
    if (thumbCache.has(path)) { img.src = thumbCache.get(path); return; }
    try {
      const r = await fetch(`/api/thumb?path=${encodeURIComponent(path)}`, { headers: authHeaders() });
      if (!r.ok) return; const b = await r.blob(); bytesIn += b.size;
      const u = URL.createObjectURL(b); thumbCache.set(path, u); img.src = u;
      if (thumbCache.size > 400) {   // a long scroll through the camera roll must not eat the phone's memory
        const oldest = thumbCache.keys().next().value; URL.revokeObjectURL(thumbCache.get(oldest)); thumbCache.delete(oldest);
      }
    } catch {}
  }
  async function downloadFile(it) {
    // a one-time link: the download manager streams it to disk, however big the file is
    const r = await fetch("/api/ticket", { method: "POST", headers: { ...authHeaders(), "Content-Type": "application/json" }, body: JSON.stringify({ path: it.path }) });
    if (!r.ok) return show("Не удалось скачать");
    const j = await r.json(); const url = `${location.origin}/api/file?ticket=${encodeURIComponent(j.ticket)}`;
    if (window.PcRemoteApp && window.PcRemoteApp.download) { window.PcRemoteApp.download(url, j.name, j.size || 0); show(`Скачиваю ${it.name} в Загрузки…`, 4000); return; }
    const a = document.createElement("a"); a.href = url; a.download = j.name; document.body.appendChild(a); a.click(); a.remove();
    show(`Скачиваю ${it.name}…`, 4000); sfx("ok");
  }
  $("fileInput").onchange = async () => {
    const files = [...$("fileInput").files]; $("fileInput").value = ""; const pr = $("fProgress"); pr.hidden = false;
    for (const f of files) {
      pr.textContent = `Отправляю ${f.name} (${fmtSize(f.size)})…`;
      try {
        const r = await fetch("/api/upload", { method: "POST", headers: { ...authHeaders(), "X-Filename": encodeURIComponent(f.name), ...(fPath ? { "X-Dir": encodeURIComponent(fPath) } : {}) }, body: f });
        const j = await r.json(); bytesOut += f.size;
        pr.textContent = j.ok ? `✓ ${j.name}` : "Ошибка: " + j.error;
      } catch (e) { pr.textContent = "Ошибка: " + e; }
    }
    setTimeout(() => (pr.hidden = true), 4000); sfx("ok"); if (fPath) loadFiles(fPath);
  };
  // ---- image viewer: photos from the PC and screenshots, pinch to zoom
  let imgBlobUrl = null, imgName = "", iz = 1, ix = 0, iy = 0, ipinch = null, ilastTap = 0;
  function openViewer(url, name) {
    imgBlobUrl = url; imgName = name; $("imgName").textContent = name; $("imgEl").src = url;
    iz = 1; ix = iy = 0; applyImg(); $("imgView").hidden = false;
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
  $("imgSave").onclick = async () => {
    // inside the Android app a blob: link cannot be "downloaded" by the WebView: hand the bytes to the app
    if (window.PcRemoteApp && window.PcRemoteApp.saveImage) {
      try {
        const buf = new Uint8Array(await (await fetch(imgBlobUrl)).arrayBuffer());
        let bin = ""; for (let i = 0; i < buf.length; i += 0x8000) bin += String.fromCharCode.apply(null, buf.subarray(i, i + 0x8000));
        window.PcRemoteApp.saveImage(imgName, btoa(bin), imgName.toLowerCase().endsWith(".png") ? "image/png" : "image/jpeg");
        show("Сохранено в Pictures/PC Remote"); sfx("ok");
      } catch (e) { show("Не удалось сохранить: " + e); }
      return;
    }
    const a = document.createElement("a"); a.href = imgBlobUrl; a.download = imgName; a.click(); show("Сохранено на телефон"); sfx("ok");
  };
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
      d.innerHTML = `<i>⚡</i><span class="n">${esc(m.name)}</span><span class="s">${m.steps.length} шаг.</span><i data-del="${i}">🗑</i>`;
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
  // Windows switcher (□) and HD zone
  // ================================================================
  const AV = ["#4f8cff", "#38d070", "#a06bff", "#ff8a3d", "#ff4f8b", "#2bbac5", "#e0c341"];
  const hue = (s) => AV[[...s].reduce((a, c) => a + c.charCodeAt(0), 0) % AV.length];
  function openWindows() { menu.hidden = true; $("powerSheet").hidden = true; $("zoneSheet").hidden = true; $("winSheet").hidden = false; send({ t: "windows_get" }); }
  $("winBtn").onclick = openWindows;
  $("winRefresh").onclick = () => { send({ t: "windows_get" }); buzz(6); };
  $("winDesktop").onclick = () => { send({ t: "combo", keys: ["Meta", "d"] }); $("winSheet").hidden = true; buzz(10); };
  function renderWindows(items) {
    const L = $("winList"); L.innerHTML = items.length ? "" : `<div class="empty">Открытых окон нет</div>`;
    for (const w of items) {
      const d = document.createElement("div"); d.className = "w" + (w.active ? " active" : "");
      const label = (w.proc || w.title).slice(0, 1).toUpperCase();
      d.innerHTML = `<span class="av" style="background:${hue(w.proc || w.title)}">${esc(label)}</span><span class="n"><b>${esc(w.title)}</b><span>${esc(w.proc || "")}${w.min ? " · свёрнуто" : ""}${w.active ? " · активно" : ""}</span></span><button title="Закрыть">✕</button>`;
      d.querySelector("button").onclick = (e) => { e.stopPropagation(); closeWin(d, w); };
      d.onclick = () => { send({ t: "window", op: "focus", hwnd: w.hwnd }); $("winSheet").hidden = true; buzz(10); };
      // swipe left = close, like Android
      let sx = null;
      d.addEventListener("touchstart", (e) => { sx = e.touches[0].clientX; e.stopPropagation(); }, { passive: true });
      d.addEventListener("touchmove", (e) => { if (sx === null) return; const dx = e.touches[0].clientX - sx; if (dx < 0) d.style.transform = `translateX(${dx}px)`; }, { passive: true });
      d.addEventListener("touchend", (e) => { const dx = e.changedTouches[0].clientX - (sx ?? 0); d.style.transform = ""; if (sx !== null && dx < -90) closeWin(d, w); sx = null; });
      L.appendChild(d);
    }
  }
  function closeWin(d, w) { d.classList.add("gone"); send({ t: "window", op: "close", hwnd: w.hwnd }); buzz([10, 30, 10]); }

  let zoneWanted = false;
  $("zoneBtn").onclick = () => { menu.hidden = true; $("zoneSheet").hidden = false; };
  $("zoneAuto").onclick = () => { $("zoneSheet").hidden = true; if (!pcOnline) return show("ПК не в сети"); zoneWanted = true; send({ t: "zone", auto: true }); show("Ищу движение на экране…", 1800); };
  $("zoneOff").onclick = () => { $("zoneSheet").hidden = true; send({ t: "zone", off: true }); };
  $("zoneManual").onclick = () => { $("zoneSheet").hidden = true; $("zoneSel").hidden = false; };
  (() => {
    const sel = $("zoneSel"), box = sel.querySelector(".zbox"); let p0 = null;
    const rect = (a, b) => ({ l: Math.min(a.x, b.x), t: Math.min(a.y, b.y), w: Math.abs(a.x - b.x), h: Math.abs(a.y - b.y) });
    sel.addEventListener("touchstart", (e) => { e.preventDefault(); e.stopPropagation(); const t = e.touches[0]; p0 = { x: t.clientX, y: t.clientY }; box.style.display = "block"; }, { passive: false });
    sel.addEventListener("touchmove", (e) => { e.preventDefault(); if (!p0) return; const t = e.touches[0]; const r = rect(p0, { x: t.clientX, y: t.clientY });
      box.style.left = r.l + "px"; box.style.top = r.t + "px"; box.style.width = r.w + "px"; box.style.height = r.h + "px"; }, { passive: false });
    sel.addEventListener("touchend", (e) => {
      e.preventDefault(); sel.hidden = true; box.style.display = "none"; if (!p0) return;
      const t = e.changedTouches[0]; const a = toPC(p0.x, p0.y), b = toPC(t.clientX, t.clientY); p0 = null;
      const z = { x: Math.min(a.x, b.x), y: Math.min(a.y, b.y), w: Math.abs(a.x - b.x), h: Math.abs(a.y - b.y) };
      if (z.w < 0.03 || z.h < 0.03) return show("Слишком маленькая область");
      send({ t: "zone", ...z }); buzz(12);
    }, { passive: false });
  })();

  // ================================================================
  // System page: state / processes / timers / devices / downloads / log
  // ================================================================
  let sysTab = "state", sysTimer = null, guest = false;
  const TABS = { state: "sysState", procs: "sysProcs", timers: "sysTimers", devices: "sysDevices", dl: "sysDl", log: "sysLog", diag: "sysDiag" };
  function sysRequest() {
    if (sysTab === "state") send({ t: "sys_get", temps: true });
    else if (sysTab === "procs") send({ t: "procs_get" });
    else if (sysTab === "timers") send({ t: "timers_get" });
    else if (sysTab === "devices") send({ t: "powerplans_get" });
    else if (sysTab === "dl") send({ t: "downloads_get" });
    else if (sysTab === "log") loadLog();
    else if (sysTab === "diag") { send({ t: "diag_get" }); renderDiag(lastDiag); }
  }
  // ---- diagnostics: what the link, the codec and the PC are doing right now; recent errors
  let lastDiag = null; const diagErrors = [];
  window.pcrError = (text) => { diagErrors.push({ ts: Date.now(), text: String(text).slice(0, 200) }); if (diagErrors.length > 20) diagErrors.shift(); };
  window.addEventListener("error", (e) => window.pcrError("JS: " + (e.message || e.type)));
  window.addEventListener("unhandledrejection", (e) => window.pcrError("Promise: " + (e.reason && e.reason.message || e.reason)));
  function diagRows() {
    const d = lastDiag || {};
    const kind = lastDiag ? (d.codec ? `видео ${d.codec} (${d.encoder || "?"}) ${d.enc_fps || "?"} к/с` : "кадры JPEG") : "ПК не ответил";
    return [["Связь", pcOnline ? "ПК в сети" : "ПК не в сети"], ["Задержка", latency ? latency + " мс" : "—"], ["Кадров/с (факт)", String(fpsShown)],
      ["Картинка", kind], ["Размер кадра", d.size ? `${d.size[0]}×${d.size[1]}` : (frameW ? `${frameW}×${frameH}` : "—")],
      ["Профиль", d.profile || profile], ["RTT по ack на ПК", d.rtt_ms != null ? d.rtt_ms + " мс" : "—"], ["Канал по оценке ПК", d.bw_kbs ? d.bw_kbs + " КБ/с" : "—"],
      ["Захват экрана", d.capture ? `${d.capture === "dxgi" ? "DXGI" : "GDI"} · ${d.grab_ms} мс/кадр` + (d.capture_switches ? ` · переключений ${d.capture_switches}` : "") : "—"],
      ["Путь до ПК", (window.PcRemoteApp && PcRemoteApp.path && PcRemoteApp.path()) || "браузер"],
      ["Адаптация", d.adaptive === false ? "выкл" : (d.rung ? `ступень ${d.rung} из 7` + (d.bitrate ? ` · ${d.bitrate}бит/с` : "") : "полное качество профиля")],
      ["Звук", d.audio ? `${d.audio} · ${d.audio_codec || "?"}` : "выкл"], ["HD-зона", d.zone ? "вкл" : "выкл"],
      ["Зрителей", d.viewers ?? "—"], ["Терминалов", d.terms ?? "—"], ["Переподключений ПК↔relay", d.reconnects ?? "—"], ["Переподключений телефона", String(phoneReconnects)],
      ["Кодеки телефона", (codecList || []).join(", ") || "нет WebCodecs"], ["Без касаний, с", d.idle_s ?? "—"], ["Сеть телефона", (navigator.connection && (navigator.connection.effectiveType || navigator.connection.type)) || "—"],
      ["Приложение", window.PcRemoteApp ? "Android" : "браузер"], ["Экран телефона", `${innerWidth}×${innerHeight} @${devicePixelRatio}`]];
  }
  function renderDiag(m) {
    if (m) lastDiag = m;
    const L = $("diagList"); L.innerHTML = "";
    for (const [k, v] of diagRows()) { const r = document.createElement("div"); r.className = "it"; r.innerHTML = `<span class="s">${esc(k)}</span><span class="n">${esc(v)}</span>`; L.appendChild(r); }
    const E = $("diagErrors"); E.innerHTML = diagErrors.length ? "" : `<div class="empty">Ошибок не было</div>`;
    for (const e of diagErrors.slice().reverse()) { const r = document.createElement("div"); r.className = "it"; r.innerHTML = `<span class="s">${new Date(e.ts).toLocaleTimeString("ru-RU")}</span><span class="n">${esc(e.text)}</span>`; E.appendChild(r); }
  }
  // ---- which port to the PC is faster: measured natively by the app (the WebView cannot pin our certificate)
  let pcPorts = [];
  function renderPorts(items) {
    const L = $("portList"); L.innerHTML = "";
    if (!items || !items.length) { L.innerHTML = `<div class="empty">Нет данных</div>`; return; }
    const okItems = items.filter((p) => p.ok);
    const best = okItems.length ? okItems.reduce((a, b) => (b.kbs > a.kbs ? b : a)) : null;
    for (const p of items) {
      const r = document.createElement("div"); r.className = "it";
      const text = p.ok ? `TLS ${p.connect_ms} мс · ответ ${p.ttfb_ms} мс · ${p.kbs} КБ/с` : `недоступен${p.error ? ": " + p.error : ""}`;
      r.innerHTML = `<span class="s">${esc(String(p.port))}${p.current ? " · сейчас" : ""}${best && p.port === best.port ? " · быстрее всех" : ""}</span><span class="n">${esc(text)}</span>`;
      if (p.ok && !p.current && window.PcRemoteApp && PcRemoteApp.usePort) {
        const b = document.createElement("button"); b.className = "btn small"; b.textContent = "Использовать";
        b.onclick = () => { PcRemoteApp.usePort(p.port); show(`Переключаюсь на порт ${p.port}…`); };
        r.appendChild(b);
      }
      L.appendChild(r);
    }
  }
  window.pcrReconnect = () => { backoff = 300; if (ws) { try { ws.close(); } catch {} } };   // the app found a shorter road to the PC
  window.pcrPorts = (items) => { try { renderPorts(typeof items === "string" ? JSON.parse(items) : items); } catch (e) { pcrError("ports: " + e.message); } };
  $("portProbe").onclick = () => {
    if (!(window.PcRemoteApp && PcRemoteApp.probePorts)) { show("Замер по портам работает только в приложении «Мой ПК»"); return; }
    if (!pcPorts.length) { show("ПК ещё не сообщил свои порты"); return; }
    $("portList").innerHTML = `<div class="empty">Замеряю ${pcPorts.length} порт(а)…</div>`;
    PcRemoteApp.probePorts(JSON.stringify(pcPorts)); buzz(8);
  };
  $("diagReport").onclick = async () => {
    const body = { client: Object.fromEntries(diagRows()), errors: diagErrors, agent: lastDiag, ua: navigator.userAgent, prefs: { video: prefs.video, theme: prefs.theme } };
    const r = await fetch("/api/report", { method: "POST", headers: { ...authHeaders(), "Content-Type": "application/json" }, body: JSON.stringify(body) });
    const j = r.ok ? await r.json() : null; show(j && j.ok ? `Отчёт сохранён: ${j.path}` : "Не удалось сохранить отчёт", 5000); if (j && j.ok) sfx("ok");
  };
  function sysShowTab(tab) {
    sysTab = tab; for (const k in TABS) $(TABS[k]).hidden = k !== tab;
    document.querySelectorAll("#sysTabs button").forEach((b) => b.classList.toggle("on", b.dataset.tab === tab));
    clearInterval(sysTimer); sysRequest();
    if (tab === "state" || tab === "procs") sysTimer = setInterval(() => { if (!$("sysPage").hidden && !isHidden()) sysRequest(); }, tab === "state" ? 3000 : 5000);
  }
  // «Кто ест трафик»: asked once a second while the page is open, nothing otherwise
  let trafTimer = null;
  $("trafficBtn").onclick = () => {
    showPage("trafficPage"); if (!pcOnline) show("ПК не в сети");
    clearInterval(trafTimer); send({ t: "traffic_get" });
    trafTimer = setInterval(() => { if ($("trafficPage").hidden) { clearInterval(trafTimer); return; } send({ t: "traffic_get" }); }, 1000);
  };
  const fmtB = (n) => n >= 1048576 ? (n / 1048576).toFixed(1).replace(".", ",") + " МБ" : n >= 1024 ? Math.round(n / 1024) + " КБ" : n + " Б";
  const fmtR = (n) => n >= 1048576 ? (n / 1048576).toFixed(1).replace(".", ",") + " МБ/с" : (n / 1024).toFixed(n >= 10240 ? 0 : 1).replace(".", ",") + " КБ/с";
  function renderTraffic(m) {
    const since = Math.max(1, Math.round((Date.now() / 1000 - m.since) / 60));
    $("trafSum").innerHTML = `Канал телефона: <b>${m.bw ? "≈ " + fmtR(m.bw) : "ещё не измерен"}</b> · Claude сейчас: <b>${fmtR(m.claude || 0)}</b><br>` +
      `Остальным приложениям: <b>${m.others === null ? "без ограничений (Claude и экран не заняты)" : "не больше " + fmtR(m.others)}</b>` +
      `<br><span class="muted">Счёт за ${since < 120 ? since + " мин" : Math.round(since / 60) + " ч"} с запуска PC Remote</span>`;
    const top = Math.max(1, ...m.rows.map((r) => r.rate || 0));
    $("trafList").innerHTML = m.rows.map((r) => {
      const place = r.name === "Claude" ? "1-й в очереди" : r.name.startsWith("Мой ПК") ? "2-й в очереди" : "3-й, остаток";
      const waiting = (r.queued || 0) > 0;
      return `<div class="r${waiting ? " wait" : ""}"><div class="h"><span>${esc(r.name)}</span><span class="p">${place}</span></div>` +
        `<div class="l">получает <b>${fmtR(r.rate || 0)}</b>` + (r.wants !== undefined ? ` · хочет <b>${fmtR(r.wants || 0)}</b>` : "") +
        (waiting ? ` · <span class="q">ждёт у ПК ${fmtB(r.queued)}</span>` : "") + `</div>` +
        `<div class="l">всего съело: <b>${fmtB((r.down || 0) + (r.up || 0))}</b> (к телефону ${fmtB(r.down || 0)}, от телефона ${fmtB(r.up || 0)})</div>` +
        `<div class="bar" style="width:${Math.round(100 * (r.rate || 0) / top)}%"></div></div>`;
    }).join("") || `<p class="muted small">Пока ничего: включите на телефоне «Интернет через ПК».</p>`;
  }
  $("sysBtn").onclick = () => { showPage("sysPage"); if (!pcOnline) show("ПК не в сети"); sysShowTab(guest ? "state" : sysTab); };
  document.querySelectorAll("#sysTabs button").forEach((b) => (b.onclick = () => { sysShowTab(b.dataset.tab); buzz(6); }));
  $("sysRefresh").onclick = () => { sysRequest(); buzz(6); };
  const _closePages = closePages;
  document.querySelectorAll("#sysPage [data-close]").forEach((b) => (b.onclick = () => { clearInterval(sysTimer); _closePages(); }));

  const fmtUp = (s) => s >= 86400 ? `${(s / 86400) | 0} д ${((s % 86400) / 3600) | 0} ч` : s >= 3600 ? `${(s / 3600) | 0} ч ${((s % 3600) / 60) | 0} мин` : `${(s / 60) | 0} мин`;
  const fmtRate = (b) => b > 1e6 ? `${(b / 1e6).toFixed(1)} МБ/с` : `${Math.round(b / 1024)} КБ/с`;
  const gauge = (label, pct, value, sub) => `<div class="gauge"><div class="ring${pct >= 90 ? " hot" : ""}" style="--p:${Math.round(pct)}" data-v="${value}"></div><div class="t"><b>${esc(label)}</b><span>${esc(sub || "")}</span></div></div>`;
  function renderSys(m) {
    if (m.error) { $("gauges").innerHTML = `<div class="empty">${esc(m.error)}</div>`; return; }
    const ramP = m.ram_total ? m.ram_used / m.ram_total * 100 : 0;
    let g = gauge("Процессор", m.cpu || 0, `${Math.round(m.cpu || 0)}%`, `${m.cpu_cores || "?"} ядер${m.cpu_freq ? " · " + (m.cpu_freq / 1000).toFixed(1) + " ГГц" : ""}${m.cpu_temp ? " · " + m.cpu_temp + "°" : ""}`);
    g += gauge("Память", ramP, `${Math.round(ramP)}%`, `${(m.ram_used / 1e9).toFixed(1)} из ${(m.ram_total / 1e9).toFixed(0)} ГБ`);
    if (m.gpu) g += gauge("Видеокарта", m.gpu.load, `${m.gpu.load}%`, `${m.gpu.name.replace(/NVIDIA |GeForce /g, "")} · ${m.gpu.temp}° · ${(m.gpu.mem_used / 1024).toFixed(1)}/${(m.gpu.mem_total / 1024).toFixed(0)} ГБ`);
    if (m.battery) g += gauge("Батарея", m.battery.percent, `${Math.round(m.battery.percent)}%`, m.battery.plugged ? "от сети" : "от батареи");
    $("gauges").innerHTML = g;
    $("sysDisks").innerHTML = (m.disks || []).map((d) => { const p = d.total ? d.used / d.total * 100 : 0;
      return `<div class="bar${p >= 92 ? " hot" : ""}"><div class="h"><span>${esc(d.name)}</span><span>${(d.used / 1e9).toFixed(0)} / ${(d.total / 1e9).toFixed(0)} ГБ свободно ${((d.total - d.used) / 1e9).toFixed(0)}</span></div><div class="b"><i style="width:${p}%"></i></div></div>`; }).join("");
    $("sysMisc").textContent = `Работает ${fmtUp(m.uptime || 0)} · сеть ПК ↓${fmtRate(m.net_down || 0)} ↑${fmtRate(m.net_up || 0)}`;
  }
  function renderProcs(items) {
    const L = $("procList"); L.innerHTML = items.length ? "" : `<div class="empty">Пусто</div>`;
    for (const p of items) { const d = document.createElement("div"); d.className = "it";
      d.innerHTML = `<span class="n"><b>${esc(p.name)}</b><span class="m">${p.cpu.toFixed(0)}% · ${fmtSize(p.mem)}</span></span><button class="x" title="Завершить">✕</button>`;
      d.querySelector("button").onclick = async () => { if (await ask(`Завершить ${p.name} (${p.pid})?`)) send({ t: "proc_kill", pid: p.pid }); };
      L.appendChild(d); }
  }
  let timerAct = "shutdown", timerMin = 30;
  document.querySelectorAll("#timerAct button").forEach((b) => (b.onclick = () => { timerAct = b.dataset.act; document.querySelectorAll("#timerAct button").forEach((x) => x.classList.toggle("on", x === b)); }));
  document.querySelectorAll("#timerMin button").forEach((b) => (b.onclick = () => { timerMin = +b.dataset.min; $("timerCustom").value = ""; document.querySelectorAll("#timerMin button").forEach((x) => x.classList.toggle("on", x === b)); }));
  $("timerStart").onclick = () => {
    const min = +$("timerCustom").value || timerMin; if (!pcOnline) return show("ПК не в сети");
    send({ t: "timer_set", action: timerAct, seconds: min * 60 }); buzz(15); show(`Таймер: ${min} мин`);
  };
  $("timerCancel").onclick = () => { send({ t: "timer_cancel" }); buzz(10); };
  const NAMES = { shutdown: "Выключение", reboot: "Перезагрузка", sleep: "Сон", lock: "Блокировка" };
  function renderTimers(items) {
    const L = $("timerList"); L.innerHTML = items.length ? "" : `<div class="empty">Нет таймеров</div>`;
    for (const t of items) { const d = document.createElement("div"); d.className = "it";
      d.innerHTML = `<i>⏱</i><span class="n"><b>${esc(NAMES[t.action] || t.action)}</b><span class="m">через ${fmtUp(t.left)} · в ${new Date(t.at * 1000).toLocaleTimeString("ru-RU", { hour: "2-digit", minute: "2-digit" })}</span></span>`; L.appendChild(d); }
  }
  document.querySelectorAll("#sysDevices button[data-dev]").forEach((b) => (b.onclick = () => { send({ t: "device", op: b.dataset.dev }); buzz(10); }));
  function renderPlans(items) {
    const L = $("planList"); L.innerHTML = items.length ? "" : `<div class="empty">Схемы не найдены</div>`;
    for (const p of items) { const d = document.createElement("div"); d.className = "it" + (p.active ? " sel" : "");
      d.innerHTML = `<i>${p.active ? "✓" : "○"}</i><span class="n">${esc(p.name)}</span>`; d.onclick = () => send({ t: "device", op: "powerplan", value: p.guid }); L.appendChild(d); }
  }
  $("dlStart").onclick = () => { const u = $("dlUrl").value.trim(); if (!u) return; if (!pcOnline) return show("ПК не в сети"); send({ t: "download", url: u }); $("dlUrl").value = ""; buzz(10); };
  const dlItems = new Map();
  function renderDl(items) { dlItems.clear(); for (const it of items) dlItems.set(it.id, it); paintDl(); }
  function dlUpdate(m) { dlItems.set(m.id, m); paintDl(); if (m.status === "готово") { sfx("ok"); show(`Скачано: ${m.name}`); } }
  function paintDl() {
    const L = $("dlList"); const items = [...dlItems.values()].reverse(); L.innerHTML = items.length ? "" : `<div class="empty">Пока ничего</div>`;
    for (const it of items) { const d = document.createElement("div"); d.className = "it"; const p = it.total ? Math.min(100, it.done / it.total * 100) : 0;
      d.innerHTML = `<span class="n"><b>${esc(it.name)}</b><span class="m">${esc(it.status)} · ${fmtSize(it.done)}${it.total ? " из " + fmtSize(it.total) : ""}</span>${it.status === "идёт" ? `<div class="prog"><i style="width:${p}%"></i></div>` : ""}</span>${it.status === "идёт" ? '<button class="x">✕</button>' : ""}`;
      const x = d.querySelector("button"); if (x) x.onclick = () => send({ t: "download_cancel", id: it.id });
      L.appendChild(d); }
  }
  async function loadLog() {
    $("logList").innerHTML = `<div class="empty">Загружаю…</div>`;
    const r = await fetch("/api/events", { headers: authHeaders() }); const j = r.ok ? await r.json() : [];
    $("logList").innerHTML = j.length ? "" : `<div class="empty">Пока пусто</div>`;
    for (const e of j) { const d = document.createElement("div"); d.className = "it";
      d.innerHTML = `<span class="s">${new Date(e.ts * 1000).toLocaleString("ru-RU", { hour: "2-digit", minute: "2-digit", day: "2-digit", month: "2-digit" })}</span><span class="n">${esc(e.text)}</span>`; $("logList").appendChild(d); }
  }
  // say: the PC reads text aloud
  $("sayBtn").onclick = () => textDialog("Сказать вслух на ПК", "Текст, который ПК произнесёт", (v) => { send({ t: "say", text: v }); show("ПК говорит…"); });

  // guest: a relative with the guest code sees the screen and the power buttons, nothing else
  function setGuest(on) {
    guest = on; document.body.classList.toggle("guest", on);
    if (on) for (const id of ["filesBtn", "termBtn", "kbBtn", "macrosBtn", "shotBtn", "pasteBtn", "linkBtn", "sayBtn", "castBtn", "termMenuBtn", "volRow", "pcClipBtn", "audioBtn", "winBtn", "zoneBtn"]) $(id).hidden = true;
    document.querySelectorAll("#sysTabs button").forEach((b) => (b.hidden = on && b.dataset.tab !== "state"));
    tidySections();
    if (on) pill("Гостевой режим: только просмотр и питание", true, 4000);
  }
  // Android "Back": close the top-most thing (viewer, dialog, selector, sheet, keys, page).
  // Returns true when something was closed; the app then stays open instead of minimizing.
  window.pcrBack = () => {
    if (!$("imgView").hidden) { $("imgBack").click(); return true; }
    const modal = [...document.querySelectorAll(".modal")].find((m) => !m.hidden);
    if (modal) { const no = modal.querySelector("#confirmNo, #textDlgNo, #macroClose, #logClose, #ccClose, #hintsOk, .btn:not(.primary):not(.danger)"); no ? no.click() : (modal.hidden = true); return true; }
    if (!$("zoneSel").hidden) { $("zoneSel").hidden = true; return true; }
    if (!menu.hidden || !$("powerSheet").hidden || !$("winSheet").hidden || !$("zoneSheet").hidden) { hideSheets(); return true; }
    if (!$("kbPanel").hidden) { $("kbBtn").click(); return true; }
    if (!$("filesPage").hidden && fSelecting) { $("fCancelSel").click(); return true; }
    if (pages.some((p) => !$(p).hidden)) { closePages(); return true; }
    return false;
  };
  // hide a group heading in "Ещё" when every tile under it is hidden (e.g. guest mode)
  function tidySections() {
    document.querySelectorAll(".tools-wrap .sec").forEach((sec) => {
      const grid = sec.nextElementSibling;
      const anyVisible = grid && [...grid.children].some((b) => !b.hidden);
      sec.style.display = anyVisible ? "" : "none";
    });
  }
})();