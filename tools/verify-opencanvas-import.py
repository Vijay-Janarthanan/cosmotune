#!/usr/bin/env python3
"""Verify the recorded OpenCanvas import; text checks allow Git LF/CRLF conversion."""
import argparse
from zipfile import ZipFile
import hashlib
import json
from pathlib import Path

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--apk-dir", type=Path, help="Also verify models and licences in every APK in this directory")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
manifest = json.loads((root / "docs/OPENCANVAS_IMPORT.json").read_text(encoding="utf-8"))
failures = []
for entry in manifest["files"]:
    path = root / entry["target"]
    if not path.is_file():
        failures.append(entry["target"] + ": missing")
        continue
    content = path.read_bytes()
    if entry["normalizeLf"]:
        content = content.replace(b"\r\n", b"\n")
    if hashlib.sha256(content).hexdigest() != entry["sha256"]:
        failures.append(entry["target"] + ": changed since upstream import")
expected = {entry["target"] for entry in manifest["files"]}
engine = root / "shared/src/jvmSharedMain/kotlin/com/cosmotune/music/data/canvas/opencanvas"
for path in engine.glob("*.kt"):
    if path.relative_to(root).as_posix() not in expected:
        failures.append(str(path) + ": unrecorded engine source")
if args.apk_dir is not None:
    apks = sorted(args.apk_dir.glob("*.apk"))
    if not apks:
        failures.append(f"No APKs found in {args.apk_dir}")
    resources = [entry for entry in manifest["files"]
                 if entry["target"].startswith("shared/src/jvmSharedMain/resources/models/")]
    for apk in apks:
        with ZipFile(apk) as archive:
            for entry in resources:
                name = "models/" + Path(entry["target"]).name
                try:
                    content = archive.read(name)
                except KeyError:
                    failures.append(f"{apk.name}: missing {name}")
                    continue
                if entry["normalizeLf"]:
                    content = content.replace(b"\r\n", b"\n")
                if hashlib.sha256(content).hexdigest() != entry["sha256"]:
                    failures.append(f"{apk.name}: incorrect {name}")
        print(f"Inspected Android resources: {apk.name}")
if failures:
    raise SystemExit("\n".join(failures))
print(f"Verified {len(expected)} imported files from OpenCanvas {manifest['revision']}")
