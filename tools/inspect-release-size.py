"""Inspect a remote APK/ZIP's central directory using bounded HTTP ranges only."""
import argparse
import collections
import io
import json
from pathlib import Path
import re
import urllib.request
import zipfile


class RemoteZip(io.RawIOBase):
    def __init__(self, url):
        self.url, self.position, self.transferred = url, 0, 0
        request = urllib.request.Request(url, headers={"Range": "bytes=0-0", "User-Agent": "Cosmotune-size-audit/1.0"})
        with urllib.request.urlopen(request, timeout=40) as response:
            match = re.fullmatch(r"bytes 0-0/(\d+)", response.headers.get("Content-Range", ""))
            if response.status != 206 or not match:
                raise RuntimeError("Server did not honour HTTP ranges; refusing to download the full package")
            self.size = int(match[1])
            self.url = response.geturl()

    def seekable(self):
        return True

    def tell(self):
        return self.position

    def seek(self, offset, whence=0):
        self.position = offset if whence == 0 else self.position + offset if whence == 1 else self.size + offset
        if self.position < 0:
            raise ValueError("Negative seek")
        return self.position

    def read(self, size=-1):
        size = self.size - self.position if size < 0 else min(size, self.size - self.position)
        if size == 0:
            return b""
        if size > 2 * 1024**2:
            raise RuntimeError("Requested range exceeds audit limit")
        request = urllib.request.Request(self.url, headers={"Range": f"bytes={self.position}-{self.position + size - 1}", "User-Agent": "Cosmotune-size-audit/1.0"})
        with urllib.request.urlopen(request, timeout=40) as response:
            if response.status != 206:
                raise RuntimeError("Range request rejected")
            result = response.read(size + 1)
        if len(result) != size:
            raise RuntimeError("Incorrect range length")
        self.position += size
        self.transferred += size
        return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("url")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    source = RemoteZip(args.url)
    with zipfile.ZipFile(source) as archive:
        entries = [{"name": i.filename, "compressed": i.compress_size, "unpacked": i.file_size} for i in archive.infolist() if not i.is_dir()]
    groups = collections.Counter()
    for entry in entries:
        name = entry["name"]
        category = "/".join(name.split("/")[:2]) if name.startswith("lib/") else "dex" if name.endswith(".dex") else name.split("/")[0]
        groups[category] += entry["compressed"]
    report = {"archive_bytes": source.size, "network_bytes_read": source.transferred, "groups": dict(groups.most_common()), "largest_entries": sorted(entries, key=lambda i: -i["compressed"])[:35]}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"archive_MiB": round(source.size / 1024**2, 2), "network_KiB": round(source.transferred / 1024, 1),
                      "groups_MiB": {k: round(v / 1024**2, 2) for k, v in groups.most_common(12)}}, indent=2))


if __name__ == "__main__":
    main()
