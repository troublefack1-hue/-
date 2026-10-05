"""«Проводник» places on a desktop JVM: zip as a folder, Ops between places, search filters, batch rename."""
import os, subprocess, sys, tempfile
from pathlib import Path
ROOT = Path(__file__).resolve().parent.parent
out = Path(tempfile.mkdtemp()) / "classes"; out.mkdir()
env = {**os.environ, "JAVA_TOOL_OPTIONS": ""}
src = [str(p) for p in (ROOT / "android-files" / "src" / "ru" / "pcremote" / "files").glob("*.java") if p.name in ("Fs.java", "Loc.java", "LocalLoc.java", "ZipLoc.java", "Ops.java")]
src += [str(ROOT / "android-files" / "test" / "LocTest.java")]
r = subprocess.run(["javac", "-encoding", "UTF-8", "-nowarn", "-d", str(out), *src], capture_output=True, text=True, env=env)
if r.returncode: print(r.stderr[-1500:]); sys.exit(1)
r = subprocess.run(["java", "-Dfile.encoding=UTF-8", "-cp", str(out), "LocTest"], capture_output=True, text=True, env=env, timeout=120)
print(r.stdout.strip())
if r.returncode: print(r.stderr[-800:])
sys.exit(r.returncode)
