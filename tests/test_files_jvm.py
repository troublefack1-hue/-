"""«Проводник» core (android-files/src/.../Fs.java) on a desktop JVM: sorting, kinds, copy/move/trash/zip/search."""
import os, subprocess, sys, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
out = Path(tempfile.mkdtemp()) / "classes"; out.mkdir()
env = {**os.environ, "JAVA_TOOL_OPTIONS": ""}
r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out), str(ROOT / "android-files/src/ru/pcremote/files/Fs.java"), str(ROOT / "android-files/test/FsTest.java")], capture_output=True, text=True, env=env)
if r.returncode:
    print(r.stderr); sys.exit(1)
r = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", str(out), "FsTest"], capture_output=True, text=True, env=env)
print(r.stdout.strip()); print(r.stderr.strip()[-500:], file=sys.stderr)
sys.exit(r.returncode)
