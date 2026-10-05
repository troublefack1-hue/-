#!/usr/bin/env python3
"""
`phone` — the phone's files from the PC, through PC Remote.

    phone roots                      folders worth knowing (camera, screenshots, downloads…)
    phone ls [/sdcard/DCIM]          list a folder
    phone tree [/sdcard/DCIM] [-d 3] folder tree
    phone find /sdcard "*.jpg"       search by name (case-insensitive glob)
    phone stat /sdcard/x.jpg         size and date
    phone cat /sdcard/notes.txt      print a text file
    phone get /sdcard/x.jpg [local]  download one file
    phone put local.txt /sdcard/Download/   upload one file
    phone pull /sdcard/DCIM/Camera [local-dir]   mirror a folder onto the PC (default: %LOCALAPPDATA%\\pc-remote\\phone\\…)
    phone push local-dir /sdcard/Download/x      upload a folder
    phone rm /sdcard/x               delete a file or folder
    phone mkdir /sdcard/Download/new
    phone mv /sdcard/a /sdcard/b     rename or move

Talks to the PC Remote relay on this machine (http://127.0.0.1:8787) with the secret from
%LOCALAPPDATA%\\pc-remote\\config.json. The phone must be paired and have
"Файлы телефона доступны для ПК" switched on.
"""
import re
import json
import os
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

DATA = Path(os.environ.get("PC_REMOTE_DATA") or (Path(os.environ.get("LOCALAPPDATA", Path.home())) / "pc-remote"))
BASE = os.environ.get("PC_REMOTE_URL", "http://127.0.0.1:8787")
MIRROR = DATA / "phone"


def secret() -> str:
    try:
        return json.loads((DATA / "config.json").read_text("utf-8"))["secret"]
    except Exception:  # noqa: BLE001
        sys.exit(f"phone: нет {DATA / 'config.json'} — запущен ли PC Remote?")


def call(op: str, **kw) -> dict:
    req = urllib.request.Request(BASE + "/api/phone", data=json.dumps({"op": op, **kw}).encode(),
                                 headers={"Authorization": "Bearer " + secret(), "Content-Type": "application/json"}, method="POST")
    try:
        with urllib.request.urlopen(req, timeout=90) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        try:
            msg = json.load(e).get("error")
        except Exception:  # noqa: BLE001
            msg = e.reason
        sys.exit(f"phone: {msg}")
    except urllib.error.URLError as e:
        sys.exit(f"phone: PC Remote не отвечает ({e.reason})")


def fmt_size(n: int) -> str:
    for unit in ("Б", "КБ", "МБ", "ГБ"):
        if n < 1024 or unit == "ГБ":
            return f"{n:.0f} {unit}" if unit == "Б" else f"{n:.1f} {unit}"
        n /= 1024
    return str(n)


def fmt_time(t: int) -> str:
    return time.strftime("%Y-%m-%d %H:%M", time.localtime(t or 0))


def print_items(items):
    for it in items:
        if it.get("dir"):
            print(f"{'<папка>':>10}  {fmt_time(it.get('mtime', 0))}  {it['name']}/")
        else:
            print(f"{fmt_size(it.get('size', 0)):>10}  {fmt_time(it.get('mtime', 0))}  {it['name']}")


def get(remote: str, local: Path, quiet=False):
    url = BASE + "/api/phone?op=read&path=" + urllib.parse.quote(remote)
    req = urllib.request.Request(url, headers={"Authorization": "Bearer " + secret()})
    try:
        with urllib.request.urlopen(req, timeout=600) as r:
            local.parent.mkdir(parents=True, exist_ok=True)
            n = 0
            with open(local, "wb") as f:
                while chunk := r.read(1 << 17):
                    f.write(chunk)
                    n += len(chunk)
            item = json.loads(r.headers.get("X-Item") or "{}")
            if item.get("mtime"):
                os.utime(local, (item["mtime"], item["mtime"]))
    except urllib.error.HTTPError as e:
        try:
            msg = json.load(e).get("error")
        except Exception:  # noqa: BLE001
            msg = e.reason
        sys.exit(f"phone: {msg}")
    if not quiet:
        print(f"{remote} -> {local} ({fmt_size(n)})")
    return n


def put(local: Path, remote: str, quiet=False):
    if not local.is_file():
        sys.exit(f"phone: нет файла {local}")
    if remote.endswith("/"):
        remote += local.name
    url = BASE + "/api/phone?op=write&path=" + urllib.parse.quote(remote)
    with open(local, "rb") as f:
        req = urllib.request.Request(url, data=f, headers={"Authorization": "Bearer " + secret(), "Content-Length": str(local.stat().st_size),
                                                           "Content-Type": "application/octet-stream"}, method="POST")
        try:
            with urllib.request.urlopen(req, timeout=600) as r:
                json.load(r)
        except urllib.error.HTTPError as e:
            try:
                msg = json.load(e).get("error")
            except Exception:  # noqa: BLE001
                msg = e.reason
            sys.exit(f"phone: {msg}")
    if not quiet:
        print(f"{local} -> {remote} ({fmt_size(local.stat().st_size)})")


def mirror_path(remote: str) -> Path:
    return MIRROR / remote.replace("/sdcard", "sdcard", 1).strip("/")


def pull(remote: str, local: Path | None, depth=0):
    res = call("list", path=remote)
    local = local or mirror_path(res.get("path", remote))
    local.mkdir(parents=True, exist_ok=True)
    n_files = 0
    for it in res["items"]:
        dest = local / it["name"]
        if it.get("dir"):
            n_files += pull(it["path"], dest, depth + 1)
            continue
        if dest.exists() and dest.stat().st_size == it.get("size") and abs(dest.stat().st_mtime - it.get("mtime", 0)) < 2:
            continue  # unchanged
        get(it["path"], dest, quiet=True)
        n_files += 1
        print(f"  {it['path']}")
    if depth == 0:
        print(f"готово: {local} (новых/изменённых файлов: {n_files})")
    return n_files


def push(local: Path, remote: str):
    if not local.is_dir():
        sys.exit(f"phone: нет папки {local}")
    n = 0
    for p in sorted(local.rglob("*")):
        if p.is_file():
            rel = p.relative_to(local).as_posix()
            put(p, remote.rstrip("/") + "/" + rel, quiet=True)
            n += 1
            print(f"  {rel}")
    print(f"готово: {n} файлов -> {remote}")


def tree(path: str, depth: int, prefix: str = "", level: int = 0):
    res = call("list", path=path)
    items = res["items"]
    for i, it in enumerate(items):
        last = i == len(items) - 1
        print(f"{prefix}{'└── ' if last else '├── '}{it['name']}{'/' if it.get('dir') else '  (' + fmt_size(it.get('size', 0)) + ')'}")
        if it.get("dir") and level + 1 < depth:
            tree(it["path"], depth, prefix + ("    " if last else "│   "), level + 1)


def main(argv):
    # a path Git Bash already rewrote (C:/Program Files/Git/sdcard/x): give the phone its own path back
    argv = [re.sub(r"^[A-Za-z]:[/\\](?:[^/\\]+[/\\])*?Git([/\\](?:sdcard|storage)(?:[/\\].*)?)$",
                   lambda m: m.group(1).replace("\\", "/"), a) for a in argv]
    if not argv or argv[0] in ("-h", "--help", "help"):
        print(__doc__.strip())
        return
    cmd, args = argv[0], argv[1:]
    if cmd == "roots":
        res = call("roots")
        for it in res["items"]:
            print(f"{it['name']:<16} {it['path']}")
        if res.get("total"):
            print(f"свободно {fmt_size(res['free'])} из {fmt_size(res['total'])}")
    elif cmd == "ls":
        res = call("list", path=args[0] if args else "/sdcard")
        print(res.get("path", ""))
        print_items(res["items"])
    elif cmd == "tree":
        depth = 2
        if "-d" in args:
            i = args.index("-d"); depth = int(args[i + 1]); del args[i:i + 2]
        path = args[0] if args else "/sdcard"
        print(path)
        tree(path, depth)
    elif cmd == "find":
        if len(args) < 2:
            sys.exit("phone find <папка> <маска>")
        res = call("find", path=args[0], q=args[1])
        for it in res["items"]:
            print(f"{fmt_size(it.get('size', 0)):>10}  {fmt_time(it.get('mtime', 0))}  {it['path']}")
        if not res["items"]:
            print("ничего не найдено")
    elif cmd == "stat":
        it = call("stat", path=args[0])["item"]
        print(json.dumps(it, ensure_ascii=False, indent=2))
    elif cmd == "cat":
        tmp = DATA / "phone" / ".cat.tmp"
        get(args[0], tmp, quiet=True)
        sys.stdout.write(tmp.read_text("utf-8", errors="replace"))
        tmp.unlink(missing_ok=True)
    elif cmd == "get":
        remote = args[0]
        local = Path(args[1]) if len(args) > 1 else Path(remote.rsplit("/", 1)[-1])
        if local.is_dir():
            local = local / remote.rsplit("/", 1)[-1]
        get(remote, local)
    elif cmd == "put":
        if len(args) < 2:
            sys.exit("phone put <локальный файл> <путь на телефоне>")
        put(Path(args[0]), args[1])
    elif cmd == "pull":
        pull(args[0] if args else "/sdcard/DCIM/Camera", Path(args[1]) if len(args) > 1 else None)
    elif cmd == "push":
        if len(args) < 2:
            sys.exit("phone push <локальная папка> <папка на телефоне>")
        push(Path(args[0]), args[1])
    elif cmd == "rm":
        print("удалено" if call("delete", path=args[0]).get("ok") else "не удалось")
    elif cmd == "mkdir":
        print("ok" if call("mkdir", path=args[0]).get("ok") else "не удалось")
    elif cmd == "mv":
        print("ok" if call("move", path=args[0], to=args[1]).get("ok") else "не удалось")
    else:
        sys.exit(f"phone: неизвестная команда {cmd} (phone --help)")


if __name__ == "__main__":
    main(sys.argv[1:])
