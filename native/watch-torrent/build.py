"""Build Cosmotune's checked-in torrent helper for the current desktop or the Android release ABIs."""

import argparse
import os
from pathlib import Path
import platform
import subprocess
import zipfile


def build(go, target, arch, output, compiler=None):
    env = dict(os.environ, GOOS=target, GOARCH=arch, CGO_ENABLED="1" if compiler else "0")
    if arch == "arm":
        env["GOARM"] = "7"
    args = [go, "build", "-mod=readonly", "-tags=disable_libutp", "-trimpath", "-ldflags=-s -w", "-o", str(output)]
    if compiler:
        env["CC"] = str(compiler)
        args[2:2] = ["-buildmode=pie"]
        # The pinned anet dependency uses Android's interface table to refresh Go's IPv6 zone cache.
        args[args.index("-ldflags=-s -w")] = "-ldflags=-s -w -checklinkname=0 -extldflags=-Wl,-z,max-page-size=16384"
    output.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(args, env=env, cwd=Path(__file__).parent, check=True)
    return env


def notices(go, env, output):
    query = '{{with .Module}}{{.Path}}\t{{.Dir}}{{end}}'
    modules = subprocess.check_output(
        [go, "list", "-mod=readonly", "-tags=disable_libutp", "-deps", "-f", query, "."],
        env=env, cwd=Path(__file__).parent, text=True,
    )
    output.parent.mkdir(parents=True, exist_ok=True)
    with output.open("w", encoding="utf-8") as notice:
        for line in sorted(set(modules.splitlines())):
            name, _, directory = line.partition("\t")
            if not directory or name.startswith("github.com/Vijay-Janarthanan/cosmotune/"):
                continue
            for item in sorted(Path(directory).iterdir()):
                if item.is_file() and item.name.upper().startswith(("LICENSE", "COPYING", "NOTICE")):
                    notice.write(f"\n{name} — {item.name}\n\n")
                    notice.write(item.read_text(encoding="utf-8", errors="replace"))
                    notice.write("\n")


def verify_apks(directories):
    for directory in directories:
        apks = sorted(directory.glob("*.apk"))
        if not apks:
            raise SystemExit(f"No APKs in {directory}")
        found = set()
        for apk in apks:
            with zipfile.ZipFile(apk) as archive:
                entries = set(archive.namelist())
            abis = {name.split("/")[1] for name in entries if name.startswith("lib/") and name.count("/") == 2}
            if not abis or "assets/cosmotune-torrent/THIRD-PARTY.txt" not in entries:
                raise SystemExit(f"Missing engine metadata in {apk}")
            for abi in abis:
                if f"lib/{abi}/libcosmotorrent.so" not in entries:
                    raise SystemExit(f"Missing {abi} torrent engine in {apk}")
            found.update(abis)
        if not {"arm64-v8a", "armeabi-v7a", "x86_64"}.issubset(found):
            raise SystemExit(f"Missing release ABIs in {directory}")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("platform", choices=["desktop", "android", "verify-apks"])
    parser.add_argument("--go", default="go")
    parser.add_argument("--ndk", type=Path)
    parser.add_argument("--apk-dir", type=Path, action="append", default=[])
    args = parser.parse_args()
    if args.platform == "verify-apks":
        if not args.apk_dir:
            parser.error("--apk-dir is required when verifying APKs")
        verify_apks(args.apk_dir)
        return
    root = Path(__file__).resolve().parents[2] / "build" / "watch-torrent"
    if args.platform == "desktop":
        target = platform.system().lower()
        arch = {"amd64": "amd64", "x86_64": "amd64", "arm64": "arm64", "aarch64": "arm64"}[platform.machine().lower()]
        name = "cosmotorrent.exe" if target == "windows" else "cosmotorrent"
        bundle = root / "desktop" / "cosmotune-torrent"
        env = build(args.go, target, arch, bundle / name)
        notices(args.go, env, bundle / "THIRD-PARTY.txt")
        return
    if args.ndk is None:
        parser.error("--ndk is required for Android")
    host = {"windows": "windows-x86_64", "linux": "linux-x86_64", "darwin": "darwin-x86_64"}[platform.system().lower()]
    tools = args.ndk.resolve() / "toolchains" / "llvm" / "prebuilt" / host / "bin"
    for abi, arch, triplet in [
        ("arm64-v8a", "arm64", "aarch64-linux-android"),
        ("armeabi-v7a", "arm", "armv7a-linux-androideabi"),
        ("x86_64", "amd64", "x86_64-linux-android"),
    ]:
        # Calling clang directly also avoids the NDK's .cmd wrappers on Windows.
        clang = tools / ("clang.exe" if host.startswith("windows") else "clang")
        env = build(args.go, "android", arch, root / "android" / abi / "libcosmotorrent.so",
                    f'"{clang}" --target={triplet}26')
    notices(args.go, env, root / "android-assets" / "cosmotune-torrent" / "THIRD-PARTY.txt")


if __name__ == "__main__":
    main()
