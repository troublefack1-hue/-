#!/usr/bin/env python3
r"""
Builds pcremote.apk without Gradle, using only the Android SDK command-line
tools (build-tools + a platform) and a JDK.

  python build.py                      # SDK from ANDROID_SDK_ROOT / ANDROID_SDK / D:\Claude\android_sdk
  python build.py --sdk C:\android-sdk

Output: out/pcremote.apk, signed with out/pcremote.keystore (created on first
build; keep it, updates must be signed with the same key).
"""
import argparse
import glob
import os
import shutil
import subprocess
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
WIN = os.name == "nt"


def find_sdk(arg: str | None) -> Path:
    for c in [arg, os.environ.get("ANDROID_SDK_ROOT"), os.environ.get("ANDROID_SDK"),
              os.environ.get("ANDROID_HOME"), r"D:\Claude\android_sdk",
              os.path.expanduser("~/Android/Sdk"), os.path.expandvars(r"%LOCALAPPDATA%\Android\Sdk")]:
        if c and Path(c, "build-tools").is_dir():
            return Path(c)
    sys.exit("Android SDK not found: pass --sdk <path> (needs build-tools and platforms/android-NN)")


def newest(path: Path) -> Path:
    """Highest stable version directory (build-tools/34.0.0, platforms/android-34); betas last resort."""
    def key(p: Path):
        name = p.name.replace("android-", "")
        stable = "beta" not in name and "rc" not in name and "alpha" not in name
        nums = [int(x) for x in name.replace("-", ".").split(".") if x.isdigit()]
        return (stable, nums)
    items = sorted((p for p in path.iterdir() if p.is_dir()), key=key)
    if not items:
        sys.exit(f"nothing in {path}")
    return items[-1]


def run(cmd, **kw):
    print("+", " ".join(str(c) for c in cmd))
    subprocess.run([str(c) for c in cmd], check=True, **kw)


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--sdk")
    ap.add_argument("--version-code", type=int, default=1)
    ap.add_argument("--version-name", default="1.0")
    ap.add_argument("--keystore", help="signing keystore (default: out/pcremote.keystore, created if missing)")
    ap.add_argument("--ks-pass", default="pcremote")
    args = ap.parse_args()
    sdk = find_sdk(args.sdk)
    bt = newest(sdk / "build-tools")
    platform = newest(sdk / "platforms")
    android_jar = platform / "android.jar"
    ext = ".exe" if WIN else ""
    bat = ".bat" if WIN else ""
    aapt2, zipalign = bt / f"aapt2{ext}", bt / f"zipalign{ext}"
    d8, apksigner = bt / f"d8{bat}", bt / f"apksigner{bat}"
    print(f"SDK {sdk}\nbuild-tools {bt.name}, platform {platform.name}")

    build = HERE / "build"
    out = HERE / "out"
    shutil.rmtree(build, ignore_errors=True)
    for d in ("res", "gen", "classes", "dex"):
        (build / d).mkdir(parents=True)
    out.mkdir(exist_ok=True)

    # 1. resources + manifest -> base apk and R.java
    run([aapt2, "compile", "--dir", HERE / "res", "-o", build / "res.zip"])
    run([aapt2, "link", "-o", build / "base.apk", "-I", android_jar,
         "--manifest", HERE / "AndroidManifest.xml", "--java", build / "gen",
         "--min-sdk-version", "24", "--target-sdk-version", "33",
         "--version-code", str(args.version_code), "--version-name", args.version_name,
         build / "res.zip"])

    # 2. java -> classes
    sources = glob.glob(str(HERE / "src" / "**" / "*.java"), recursive=True) + \
        glob.glob(str(build / "gen" / "**" / "R.java"), recursive=True)
    run(["javac", "-source", "11", "-target", "11", "-Xlint:-options", "-encoding", "UTF-8", "-nowarn",
         "-classpath", android_jar, "-d", build / "classes", *sources])

    # 3. classes -> classes.dex
    classes = glob.glob(str(build / "classes" / "**" / "*.class"), recursive=True)
    run([d8, "--min-api", "24", "--output", build / "dex", "--lib", android_jar, *classes])

    # 4. dex into apk, align, sign
    unsigned = build / "unsigned.apk"
    shutil.copy(build / "base.apk", unsigned)
    with zipfile.ZipFile(unsigned, "a", zipfile.ZIP_DEFLATED) as z:
        z.write(build / "dex" / "classes.dex", "classes.dex")
    aligned = build / "aligned.apk"
    run([zipalign, "-f", "4", unsigned, aligned])

    ks = Path(args.keystore) if args.keystore else out / "pcremote.keystore"
    pw = args.ks_pass
    if not ks.exists():
        run(["keytool", "-genkeypair", "-v", "-keystore", ks, "-storepass", pw, "-keypass", pw,
             "-alias", "pcremote", "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-dname", "CN=pc-remote"])
    apk = out / "pcremote.apk"
    run([apksigner, "sign", "--ks", ks, "--ks-pass", f"pass:{pw}", "--key-pass", f"pass:{pw}",
         "--ks-key-alias", "pcremote", "--out", apk, aligned])
    run([apksigner, "verify", apk])
    print(f"\nOK: {apk} ({apk.stat().st_size // 1024} KB)")


if __name__ == "__main__":
    main()
