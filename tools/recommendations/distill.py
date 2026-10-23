"""Distil the full training graph into a <5 MB quantised local candidate ranker.

No new data is downloaded. This trades the full local name catalogue for hashed
32-dimensional graph embeddings and a bounded 4096-name discovery vocabulary.
The provider's live radio/search remains the broad, current catalogue.
"""
import argparse
import collections
import gzip
import hashlib
import json
from pathlib import Path
import re
import struct
import sys
import time

import train
import numpy as np
from scipy import sparse

DIM = 32
TITLE, ARTIST, ALBUM = 65536, 32768, 16384
CATALOGUE = 4096


def primary_artist(text):
    first = re.split(r"\s*(?:,|&|;| feat\.? | featuring | and )\s*", text, maxsplit=1, flags=re.I)[0]
    return train.norm(first).replace(" ", "")


def title_key(artist, title):
    return primary_artist(artist) + "\x1f" + train.title_key(title).replace(" ", "")


def bucket(key, size):
    return train.hash64(key) & (size - 1)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--teacher", type=Path, default=train.WORK / "teacher")
    parser.add_argument("--output", type=Path, default=train.ROOT / "shared/src/jvmSharedMain/resources/recommendations")
    args = parser.parse_args()
    train.budget()
    manifest = json.loads((args.teacher / "manifest.json").read_text(encoding="utf-8"))
    raw = gzip.decompress((args.teacher / "recommendations.ctrec.gz").read_bytes())
    assert hashlib.sha256(raw).hexdigest() == manifest["sha256"]
    count, edge_count, string_bytes = struct.unpack_from("<3i", raw, 12)
    records = np.frombuffer(raw, dtype=np.dtype([("key", "<i4"), ("title", "<i4"), ("artist", "<i4"), ("album", "<i4"),
                                               ("duration", "<i4"), ("popularity", "<f4"), ("source", "<i4"), ("flags", "<i4"),
                                               ("hash", "<i8"), ("artist_hash", "<i8"), ("album_hash", "<i8")]), count=count, offset=40)
    offsets_at = 40 + count * 56
    offsets = np.frombuffer(raw, dtype="<i4", count=count + 1, offset=offsets_at)
    edges_at = offsets_at + (count + 1) * 4
    edges = np.frombuffer(raw, dtype=np.dtype([("target", "<i4"), ("weight", "<f4"), ("support", "<u2"), ("kind", "u1"), ("flags", "u1")]), count=edge_count, offset=edges_at)
    strings_at = edges_at + edge_count * 12

    def text(offset):
        start = strings_at + int(offset)
        return raw[start:raw.index(0, start)].decode("utf-8")

    train.log(f"Distilling {count:,} source records / {edge_count:,} links into 32 dimensions")
    title_buckets = np.zeros(count, dtype=np.int32)
    artist_buckets = np.zeros(count, dtype=np.int32)
    album_buckets = np.zeros(count, dtype=np.int32)
    initial = np.zeros((count, DIM), dtype=np.float32)
    identities = []
    for i, row in enumerate(records):
        artist, title, album = text(row["artist"]), text(row["title"]), text(row["album"])
        a = primary_artist(artist)
        identity = title_key(artist, title)
        identities.append(identity)
        title_buckets[i] = bucket(identity, TITLE)
        artist_buckets[i] = bucket(a, ARTIST)
        album_buckets[i] = bucket(a + "\x1f" + train.norm(album), ALBUM)
        # Deterministic artist features are projected through the full listening,
        # acoustic and metadata graph, rather than directly equated with similarity.
        initial[i] = (np.frombuffer(hashlib.sha256(a.encode()).digest(), dtype=np.uint8).astype(np.float32) - 127.5) / 127.5
    graph = sparse.csr_matrix((edges["weight"].copy(), edges["target"].copy(), offsets.copy()), shape=(count, count))
    sums = np.asarray(graph.sum(axis=1)).ravel()
    graph = sparse.diags(1 / np.maximum(sums, .00001)) @ graph
    features = initial.copy()
    for step in range(3):
        features = .35 * initial + .65 * (graph @ features)
        features /= np.maximum(np.linalg.norm(features, axis=1, keepdims=True), .00001)
        train.log(f"Graph diffusion {step + 1}/3 complete")
    # Suppress duplicate release/alias dominance without discarding source evidence.
    duplicates = collections.Counter(identities)
    weights = np.array([1 / duplicates[key] for key in identities], dtype=np.float32)
    tables = []
    for indices, size in ((title_buckets, TITLE), (artist_buckets, ARTIST), (album_buckets, ALBUM)):
        table = np.zeros((size, DIM), dtype=np.float32)
        np.add.at(table, indices, features * weights[:, None])
        table /= np.maximum(np.linalg.norm(table, axis=1, keepdims=True), .00001)
        tables.append(np.rint(np.clip(table, -1, 1) * 127).astype(np.int8))

    def encoded(indices):
        result = .7 * tables[0][title_buckets[indices]].astype(np.float32) + .25 * tables[1][artist_buckets[indices]].astype(np.float32) + .05 * tables[2][album_buckets[indices]].astype(np.float32)
        return result / np.maximum(np.linalg.norm(result, axis=1, keepdims=True), .00001)

    # Hold-out metric here is compression fidelity to an already trained graph,
    # not a new listening prediction benchmark. Keep the two claims distinct.
    rng = np.random.default_rng(103)
    probes = rng.choice(count, min(10000, count), replace=False)
    positive, negative = [], []
    for i in probes:
        start, end = offsets[i:i + 2]
        if end <= start:
            continue
        positive.append((int(i), int(edges[start]["target"])))
        negative.append((int(i), int(rng.integers(count))))
    positives = np.asarray(positive, dtype=np.int32)
    negatives = np.asarray(negative, dtype=np.int32)
    positive_scores = np.sum(encoded(positives[:, 0]) * encoded(positives[:, 1]), axis=1)
    negative_scores = np.sum(encoded(negatives[:, 0]) * encoded(negatives[:, 1]), axis=1)
    fidelity = float(np.mean(positive_scores > negative_scores))

    # Balanced small name vocabulary; all source records still influence tables.
    regional_db = train.read_db(train.V3 / "data/discogs-catalogue.sqlite")
    regional_releases = {str(row[0]) for row in regional_db.execute("SELECT id FROM releases WHERE regional_context=1")}
    regional_db.close()
    selected, unique, artist_counts = [], set(), collections.Counter()

    def choose(indices, quota):
        added = 0
        for i in indices:
            artist = primary_artist(text(records[i]["artist"]))
            if identities[i] in unique or not artist or artist in ("variousartists", "unknownartist") or artist_counts[artist] >= 4 or records[i]["flags"] & 1:
                continue
            unique.add(identities[i])
            selected.append(int(i))
            artist_counts[artist] += 1
            added += 1
            if added >= quota or len(selected) >= CATALOGUE:
                break

    popularity = np.argsort(-records["popularity"], kind="stable")
    regional = [i for i, row in enumerate(records) if row["source"] == 8 and text(row["key"]).split(":")[1] in regional_releases]
    # Hash order avoids arbitrary dump order; this is regional release context,
    # not an inferred sung-language label or proof of singing credits.
    regional.sort(key=lambda i: train.hash64(identities[i]))
    choose(regional, 1024)
    choose((i for i in popularity if records[i]["source"] == 1), 1536)
    choose((i for i in popularity if records[i]["source"] == 2), 768)
    fma = np.flatnonzero(records["source"] == 4).tolist()
    fma.sort(key=lambda i: train.hash64(identities[i]))
    choose(fma, 384)
    choose(popularity, CATALOGUE - len(selected))
    strings, string_offsets = bytearray(b"\0"), {"": 0}

    def intern(value):
        if value not in string_offsets:
            string_offsets[value] = len(strings)
            strings.extend(value.encode("utf-8") + b"\0")
        return string_offsets[value]

    named = bytearray()
    for i in selected:
        row = records[i]
        named.extend(struct.pack("<6i", *(intern(text(row[name])) for name in ("key", "title", "artist", "album")), int(row["duration"]), int(row["flags"])))
    vocabulary_vectors = np.rint(encoded(np.array(selected)) * 127).astype(np.int8)
    header = b"CTTINY01" + struct.pack("<8i", 1, DIM, TITLE, ARTIST, ALBUM, len(selected), 24, len(strings))
    body = header + b"".join(table.tobytes() for table in tables) + named + vocabulary_vectors.tobytes() + strings
    output = args.output
    output.mkdir(parents=True, exist_ok=True)
    packed = output / "recommendations.ctrec.gz"
    with packed.open("wb") as stream:
        with gzip.GzipFile(filename="", fileobj=stream, mode="wb", mtime=0, compresslevel=9) as gz:
            gz.write(body)
    if len(body) >= 5_000_000 or packed.stat().st_size >= 5_000_000:
        raise RuntimeError("Tiny model exceeds the user's 5 MB limit")
    info = {"schema": 4, "format": "CTTINY01", "model_version": "2026-10-23", "trained_source_records": count,
            "trained_graph_links": edge_count, "discovery_vocabulary": len(selected), "dimensions": DIM,
            "unpacked_bytes": len(body), "compressed_bytes": packed.stat().st_size,
            "sha256": hashlib.sha256(body).hexdigest(), "teacher_sha256": manifest["sha256"],
            "teacher_source_records": manifest["source_records"], "training": manifest["training"],
            "compression_fidelity": {"pairs": len(positive), "positive_vs_random_accuracy": fidelity,
                                     "scope": "Agreement with trained graph neighbours versus random records; not next-song accuracy."},
            "algorithm": "Three-step graph diffusion, source-identity duplicate weighting, hashed 32D embeddings, int8 quantisation",
            "limitations": ["Hash collisions and quantisation trade precision for size.",
                            "The full named catalogue is not bundled: the small vocabulary adds limited discoveries, and live provider radio/search supplies current candidates.",
                            "No claim that all 583k source records are unique songs, or locally searchable names.",
                            "Streaming and resolving new playback IDs still need the provider; raw listening histories stay in training storage."]}
    train.save_json(output / "manifest.json", info)
    train.save_json(train.WORK / "tiny-model-report.json", info)
    train.log(f"TINY MODEL COMPLETE: {packed.stat().st_size:,} bytes packed; {len(body):,} bytes mapped; fidelity {fidelity:.3f}")


if __name__ == "__main__":
    main()
