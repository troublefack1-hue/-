# PC Remote / «Мой ПК» — заметки для Claude Code

Личная система удалённого доступа: Android-телефон ↔ домашний Windows-ПК, без сторонних
сервисов и регистраций, по белому IP (порт 8443 проброшен на роутере). Два приложения:
**PC Remote** (Windows, exe) и **Мой ПК** (Android, APK). Всё общение с пользователем — по-русски.

## Как устроено

| Папка | Что это |
|---|---|
| `relay/` | aiohttp-хаб на ПК: TLS 8443 наружу, HTTP 8787 только для localhost; авторизация, привязка, файлы, события, файлы телефона (`/api/phone`) |
| `agent/` | «руки» на ПК: захват экрана (mss/JPEG или ffmpeg H.264/VP8 — `video.py`), мышь/клавиатура (SendInput), звук (WASAPI), терминалы (pywinpty), окна, HD-зона, системная страница (`extras.py`), уведомления Windows (`notify_watch.py`) |
| `web/` | клиент телефона: `index.html`, `app.js`, `style.css`, `cc_ru.js` (перевод фраз Claude Code); грузится в WebView с ПК |
| `pcapp/` | окно PC Remote (tkinter), трей, плавающий значок, обновления (`updater.py`), докачка компонентов (`deps.py`), команда `phone` (`phone_cli/`) |
| `android/` | исходники APK без Gradle (`build.py`: aapt2/javac/d8/apksigner); фоновый сервис с WebSocket, звонок, трансляция, виджет, файлы телефона (`PhoneFs.java`), загрузчик |
| `android-net/` | второе приложение «Интернет через ПК» (`pcremote-net.apk`): VpnService + hev-socks5-tunnel (JNI `hev.htproxy.TProxyService`, .so собирает CI через ndk-build) → `Socks5Server` → `NetMux` → `/ws/net`; плитка `NetTile`; общие `Pinned/Pairing/WsClient/Updater` из `android/` через `--extra-src` |
| `relay/netproxy.py` | серверная часть «Интернет через ПК»: мультиплекс TCP/UDP, DNS с блок-листами, `/ws/net` |
| `tests/` | атаки на relay, сквозной тест файлов телефона, smoke-тест веб-клиента в браузере |
| `tools/phone.py` | та же команда `phone` на Python (для тестов и не-Windows) |

Поток данных: телефон ⇄ relay ⇄ агент; бинарные кадры: `0x01` JPEG, `0x02` звук ПК, `0x03/0x04`
трансляция с телефона, `0x05` HD-зона, `0x06/0x07` файлы телефона, `0x09` видео H.264/VP8, `0x0A` звук Opus.
Гость (`guest_secret`) видит только экран и питание — relay фильтрует и команды, и ответы.

## Запуск из исходников на Windows

```powershell
pip install -r agent/requirements.txt -r relay/requirements.txt cryptography pystray
python pcapp/main.py          # окно PC Remote; данные в %LOCALAPPDATA%\pc-remote
```
`deps.ensure()` сам доставит недостающие пакеты. Логи: `%LOCALAPPDATA%\pc-remote\pcapp.log`.
Сборка exe: `pcapp/build_exe.ps1`; APK: `python android/build.py --sdk <SDK>`; CI делает оба
(`.github/workflows/build.yml`) и публикует релиз при каждом пуше в `main`.

## Проверки (запускать перед коммитом)

```bash
python tests/test_relay_attacks.py      # 38 проверок relay, включая атаки
python tests/test_phone_cli.py          # файлы телефона: relay + fake_phone + tools/phone.py
python tests/test_web_smoke.py          # веб-клиент в настоящем браузере (нужен playwright + chromium)
python tests/test_netproxy.py           # «Интернет через ПК», серверная часть: 17 проверок
python tests/test_netmux_jvm.py         # телефонная часть (NetMux + SOCKS5) на обычной JVM против relay по TLS; нужны javac/java и curl
node -e "new Function(require('fs').readFileSync('web/app.js','utf8'))"
```
Коммит только когда всё зелёное: один красный коммит уже ломал страницу телефона.

## Что ещё НЕ проверялось на живом железе (проверить в первую очередь)

- Захват экрана при масштабе Windows 125–150 % и на двух мониторах; HD-зона; смена разрешения. DXGI через dxcam (`agent/capture.py`): реально ли быстрее GDI на этой видеокарте, откат на GDI при полноэкранной игре, `--collect-all dxcam` в PyInstaller (numpy внутри).
- Путь до ПК (`android/src/ru/pcremote/Paths.java`): USB-модем и точка доступа телефона — что ПК попадает в `status.addrs` и телефон переключается на короткий путь сам (`RemoteService.watchPaths`).
- Звук WASAPI loopback и микрофон ПК (`agent.Audio`, PyAudioWPatch) на реальной карте.
- Терминалы pywinpty: Git Bash, `claude` через `claude-phone.sh`, кодировка вывода.
- ffmpeg: докачка (`deps.py`, ~100 МБ с gyan.dev), `h264_nvenc`/`libx264`, декодирование на телефоне (WebCodecs в WebView).
- Android: разрешение «Доступ ко всем файлам» для `phone`, трансляция экрана + звук (Android 10+), звонок «Найти телефон» на экране блокировки (полноэкранное уведомление; на Android 14 нужен грант), виджет, фон на MIUI/HyperOS (автозапуск, «без ограничений» по батарее).
- Уведомления Windows → телефон (`notify_watch.py` читает `wpndatabase.db`).
- Звук в Opus (`agent/opus.py`: ffmpeg libopus → Ogg → пакеты, кадр `0x0A`) и декодирование на телефоне через WebCodecs `AudioDecoder`; задержка и дропы на реальном канале.
- Переключение устройства вывода на время прослушивания (`agent/audio_out.py`, IPolicyConfig через comtypes): что на реальном ПК список устройств приходит в настройки, переключение и возврат срабатывают, loopback берётся с нового устройства.
- Профиль «10 КБ/с» (`tiny`): автопереход по `self.bw` в `video_step`, один кадр в полёте; проверить на ограниченном канале (например, `tc`/NetLimiter).
- «Интернет через ПК» на живом телефоне: сборка .so в CI (ndk-build, ANDROID_NDK_LATEST_HOME), запрос разрешения VPN, что трафик реально идёт через ПК (проверить внешний IP телефона = IP VPN ПК), QUIC/UDP, плитка в шторке, возврат на прямой интернет при выключенном ПК, батарея. Исключение самого приложения из туннеля (`addDisallowedApplication`) обязательно, иначе петля.
- QR-привязка: QR в окне ПК (`draw_qr`, пакет `qrcode`) → ссылка `pcremote://pair?host=…&code=…&fp=…&lan=…` → `MainActivity.handlePairLink`; телефон сверяет отпечаток сертификата с `fp` до отправки кода. Проверить со стандартной камерой и с MIUI-сканером.
- Обновление exe (`updater.apply` через batch-скрипт) и APK (подпись: нужны секреты `ANDROID_KEYSTORE_B64`/`ANDROID_KEYSTORE_PASS` в репозитории, иначе временный ключ).

## Сеть и порты

- Наружу нужен **только TCP 8443** (TLS, relay). `extra_ports` в config.json добавляет ещё TLS-слушатели (для замера по портам в «Диагностике»), их тоже пробрасывать. На роутере должен быть проброс
  8443 → локальный адрес ПК; адрес ПК лучше закрепить в DHCP роутера, иначе после его
  перезагрузки проброс перестанет работать. Правило брандмауэра Windows для 8443 PC Remote
  добавляет сам при первом запуске (`firewall_open`, один запрос UAC).
- 8787 — HTTP relay только на 127.0.0.1 (для команды `phone` и агента); наружу не открывать.
- ntfy (канал «Включить ПК» `ntfy_wake_url` и производный канал телефона `…-phone`) — только
  исходящие HTTPS, портов не требует.
- Проверка проброса: телефон на мобильном интернете (Wi-Fi выкл.) должен подключиться по
  адресу из окна PC Remote. Если внешний адрес в окне из диапазона `100.64.0.0/10` или не
  совпадает с WAN-адресом роутера — это CGNAT, проброс не поможет, нужен другой путь.
- При смене внешнего IP ПК сам публикует новый адрес телефону через ntfy (`watch_public_ip`).
- VPN на ПК: relay привязывает сокет к адаптеру с реальным маршрутом по умолчанию
  (`IP_UNICAST_IF`) и следит за сменой (`NetWatcher`); Kill Switch в VPN-клиенте держать
  выключенным.

## Правила

- Репозиторий **публичный**: никаких секретов, ключей, сертификатов, keystore в git
  (`.gitignore` их закрывает; `config.json` живёт в `%LOCALAPPDATA%\pc-remote`).
- Приложение только для владельца и его гостя: не добавлять ограничений «запрещённых файлов»,
  все диски видны намеренно.
- Любая строка с ПК/телефона в `innerHTML` — только через `esc()`.
- Не считать фоновый сервис телефона зрителем (`hello_phone{bg:true}`), не слать гостю приватное.
- Интерфейс: без загромождения — новые функции в «Ещё» (группы ПК / Обмен / Приложение) или
  в «Систему»/«Настройки», не в док.
- Сообщения коммитов по-русски, по делу; README обновлять вместе с функцией.
