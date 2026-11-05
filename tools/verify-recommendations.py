"""Verify the <5 MB bundled model, optionally in built JARs/APKs. Standard library only."""
import argparse
import gzip
import hashlib
import json
from pathlib import Path
import struct
from zipfile import ZipFile

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / "shared/src/jvmSharedMain/resources/recommendations"


def verify(manifest_bytes, packed, label):
    manifest = json.loads(manifest_bytes)
    assert manifest["schema"] == 4 and manifest["format"] == "CTTINY01", label
    assert len(packed) == manifest["compressed_bytes"] < 5_000_000, label
    # Bound decompression rather than inflating untrusted malformed resources.
    import io
    with gzip.GzipFile(fileobj=io.BytesIO(packed)) as stream:
        body = stream.read(5_000_001)
    assert len(body) == manifest["unpacked_bytes"] < 5_000_000, label
    assert hashlib.sha256(body).hexdigest() == manifest["sha256"], label
    assert body[:8] == b"CTTINY01", label
    version, dimensions, title, artist, album, items, record_size, strings = struct.unpack_from("<8i", body, 8)
    assert (version, dimensions, title, artist, album, record_size) == (1, 32, 65536, 32768, 16384, 24), label
    assert items == manifest["discovery_vocabulary"] <= 4096 and strings > 0, label
    assert 40 + (title + artist + album) * dimensions + items * (24 + dimensions) + strings == len(body), label
    assert manifest["training"]["onion"]["aggregate_rows"] > 50_000_000, label
    assert manifest["training"]["yambda"]["listens_read"] > 46_000_000, label
    print(f"{label}: {len(packed):,} bytes packed; {len(body):,} bytes mapped; model/source metadata verified")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--archive", type=Path, action="append", default=[])
    parser.add_argument("--apk-dir", type=Path)
    args = parser.parse_args()
    verify((ASSETS / "manifest.json").read_bytes(), (ASSETS / "recommendations.ctrec.gz").read_bytes(), "Source pack")
    archives = args.archive
    if args.apk_dir:
        apks = sorted(args.apk_dir.glob("*.apk"))
        assert apks, f"No APKs found in {args.apk_dir}"
        archives.extend(apks)
    for archive in archives:
        with ZipFile(archive) as z:
            verify(z.read("recommendations/manifest.json"), z.read("recommendations/recommendations.ctrec.gz"), archive.name)
            assert z.read("recommendations/ATTRIBUTION.md"), f"Missing dataset attribution: {archive.name}"


if __name__ == "__main__":
    main()
