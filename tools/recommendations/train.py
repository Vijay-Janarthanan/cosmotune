"""Train a bounded, source-aware recommendation pack from the validated local corpus.

Run from the repository root. Raw datasets stay in build/; only the model and public
aggregate report are distributed. Python, NumPy, SciPy and PyArrow are build tools.
The Kotlin reader never needs them. Source identities are never guessed to be URLs.
"""
from __future__ import annotations

import argparse
import bz2
import collections
import csv
import datetime as dt
import gzip
import hashlib
import heapq
import json
import math
import os
import re
from pathlib import Path
import shutil
import sqlite3
import struct
import sys
import time
import unicodedata

os.environ.setdefault("OPENBLAS_NUM_THREADS", "2")
os.environ.setdefault("OMP_NUM_THREADS", "2")
ROOT = Path(__file__).resolve().parents[2]
V3 = ROOT / "build/recommendation-datasets-v3"
V2 = ROOT / "build/recommendation-lab-v2"
LAB = ROOT / "build/recommendation-lab"
WORK = ROOT / "build/recommendation-release-1.0.3"
for vendor in (V2 / "vendor", V3 / "vendor"):
    sys.path.insert(0, str(vendor))
import numpy as np
from scipy import sparse
import pyarrow.parquet as pq

TRAIN = int(dt.datetime(2026, 10, 20, tzinfo=dt.timezone.utc).timestamp())
VALID = TRAIN + 86400
END = VALID + 86400
MAX_PROFILE = 64
NEIGHBOURS = 24
REUSE_TRAINED = False
SOURCES = {"listenbrainz": 1, "onion": 2, "fma": 4, "discogs": 8}
KINDS = {"listening": 1, "onion_listening": 2, "acoustic": 4, "context": 8}
VERSION_WORDS = ("remix", "karaoke", "instrumental", "sped up", "slowed", "live at", "live from", "cover version")


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def budget():
    free = shutil.disk_usage(ROOT).free
    if free < 15 * 1024**3:
        raise RuntimeError(f"Free-space reserve reached: {free / 1024**3:.2f} GiB")


def norm(text):
    # Keep Indic vowel signs/combining marks. Dropping category M merges distinct titles.
    text = unicodedata.normalize("NFKC", str(text)).lower()
    return " ".join("".join(c if unicodedata.category(c)[0] in "LNM" else " " for c in text).split())


def hash64(text):
    return struct.unpack(">q", hashlib.sha256(text.encode("utf-8")).digest()[:8])[0]


def title_key(text):
    return norm(re.sub(r"\s*[\(\[]\s*(?:from\b|official\b|album\b)[^\)\]]*[\)\]]", " ", text or "", flags=re.I))


def read_db(path):
    db = sqlite3.connect(f"file:{path.as_posix()}?mode=ro", uri=True)
    db.execute("PRAGMA cache_size=-16000")
    return db


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")


def listening_graph(matrix, shrinkage=5.0, top=NEIGHBOURS):
    matrix = matrix.astype(np.float32).tocsr()
    matrix.sum_duplicates()
    matrix.data[:] = 1.0
    columns = matrix.tocsc()
    degree = np.asarray(matrix.sum(axis=0)).ravel()
    graph = [[] for _ in range(matrix.shape[1])]
    for start in range(0, matrix.shape[1], 384):
        block = (columns[:, start:start + 384].T @ matrix).tocsr()
        for offset in range(block.shape[0]):
            i = start + offset
            lo, hi = block.indptr[offset:offset + 2]
            targets, support = block.indices[lo:hi], block.data[lo:hi]
            keep = (targets != i) & (support >= 2)
            targets, support = targets[keep], support[keep]
            scores = support / np.sqrt(np.maximum(1, degree[i] * degree[targets]))
            scores *= support / (support + shrinkage)
            best = np.lexsort((targets, -scores))[:top]
            graph[i] = [(int(targets[j]), float(scores[j]), int(support[j])) for j in best]
        if start % 15360 == 0:
            log(f"co-listening rows {start:,}/{matrix.shape[1]:,}")
            budget()
    return graph, degree


def csr_profiles(profiles, count):
    indptr = np.zeros(len(profiles) + 1, dtype=np.int32)
    for i, profile in enumerate(profiles):
        indptr[i + 1] = indptr[i] + len(profile)
    indices = np.fromiter((v for p in profiles for v in p), dtype=np.int32, count=int(indptr[-1]))
    return sparse.csr_matrix((np.ones(len(indices), dtype=np.float32), indices, indptr), shape=(len(profiles), count))


def recall(graph, profiles, targets, degree, limit=10):
    hits = covered = users = 0
    popular = np.argsort(-degree, kind="stable")[:500]
    for user in sorted(targets):
        history = profiles.get(user, [])
        wanted = set(targets[user]) - set(history)
        if len(history) < 3 or not wanted:
            continue
        users += 1
        scores = collections.defaultdict(float)
        for rank, seed in enumerate(reversed(history[-20:])):
            for item, weight, _ in graph[seed]:
                if item not in history:
                    scores[item] += weight * math.exp(-rank / 12)
        if scores:
            covered += 1
        ranked = heapq.nlargest(limit, scores, key=lambda item: (scores[item], -item))
        ranked.extend(int(i) for i in popular if i not in history and i not in ranked)
        hits += bool(wanted.intersection(ranked[:limit]))
        if users >= 500:
            break
    return {"users": users, "hit_rate_at_10": hits / max(1, users), "graph_coverage": covered / max(1, users)}


def calibrate_yambda():
    report_file = WORK / "yambda-calibration.json"
    if REUSE_TRAINED and report_file.exists():
        return json.loads(report_file.read_text())
    log("Yambda: fitting anonymous co-listening confidence; no named-song mapping")
    folder = V3 / "data/research-yambda"
    # Relative five-second bins, not Unix dates. Freeze boundaries before inspecting test.
    train_end, valid_end = 20_000_000, 23_000_000
    states = {}
    action_rows = 0
    for kind in ("likes", "unlikes", "dislikes", "undislikes"):
        for batch in pq.ParquetFile(folder / f"{kind}.parquet").iter_batches(batch_size=131072, columns=["uid", "timestamp", "item_id"]):
            a = batch.to_pydict()
            action_rows += batch.num_rows
            for user, stamp, item in zip(a["uid"], a["timestamp"], a["item_id"]):
                if stamp > train_end:
                    continue
                key = (user, item, kind in ("likes", "unlikes"))
                old = states.get(key)
                event = (stamp, kind in ("likes", "dislikes"))
                # Clearing an action at the same timestamp wins conservatively.
                if old is None or stamp > old[0] or (stamp == old[0] and not event[1]):
                    states[key] = event
    profiles, validation, test = {}, collections.defaultdict(list), collections.defaultdict(list)
    popularity = collections.Counter()
    user, counts = None, collections.Counter()
    recent = collections.deque(maxlen=MAX_PROFILE * 4)
    rows = organic_rows = replay_rows = 0

    def flush():
        if user is None:
            return
        selected = sorted(counts, key=lambda i: (-counts[i], i))[:MAX_PROFILE]
        profiles[user] = selected
        popularity.update(selected)

    for batch in pq.ParquetFile(folder / "listens.parquet").iter_batches(batch_size=262144):
        a = batch.to_pydict()
        for u, stamp, item, organic, ratio in zip(a["uid"], a["timestamp"], a["item_id"], a["is_organic"], a["played_ratio_pct"]):
            rows += 1
            organic_rows += bool(organic)
            replay_rows += ratio > 100
            if u != user:
                flush()
                user, counts = u, collections.Counter()
                recent.clear()
            if stamp > train_end:
                if ratio >= 50:
                    (validation if stamp <= valid_end else test)[u].append(item)
                continue
            if ratio < 50 or states.get((u, item, False), (0, False))[1]:
                continue
            # Record replay/organic signals without interpreting an undislike as a positive vote.
            weight = (1.0 if organic else .65) * min(ratio, 100) / 100
            if states.get((u, item, True), (0, False))[1]:
                weight *= 1.25
            counts[item] += weight
        if rows % 2_000_000 < batch.num_rows:
            log(f"Yambda listens {rows:,}")
    flush()
    keys = [i for i, n in popularity.most_common(60000) if n >= 2]
    index = {key: i for i, key in enumerate(keys)}
    fitted = {u: [index[i] for i in p if i in index] for u, p in profiles.items()}
    matrix = csr_profiles(list(fitted.values()), len(keys))
    validation = {u: [index[i] for i in v if i in index] for u, v in validation.items()}
    test = {u: [index[i] for i in v if i in index] for u, v in test.items()}
    # Fit on validation only. All candidates share one train-only graph, with a
    # monotonic correction to its shrinkage. Test is measured once after selection.
    graph, degree = listening_graph(matrix, shrinkage=2, top=48)
    trials = []
    for shrinkage in (2.0, 5.0, 10.0, 20.0):
        adjusted = [[(j, w * (s + 2) / (s + shrinkage), s) for j, w, s in row] for row in graph]
        trials.append({"shrinkage": shrinkage, **recall(adjusted, fitted, validation, degree)})
    chosen = max(trials, key=lambda x: (x["hit_rate_at_10"], x["shrinkage"]))["shrinkage"]
    adjusted = [[(j, w * (s + 2) / (s + chosen), s) for j, w, s in row] for row in graph]
    report = {"shrinkage": chosen, "validation": trials, "test": recall(adjusted, fitted, test, degree),
              "listens_read": rows, "actions_read": action_rows, "users": len(profiles), "items_fitted": len(keys),
              "organic_listens": organic_rows, "replay_listens": replay_rows,
              "relative_train_bin": train_end, "relative_validation_bin": valid_end,
              "scope": "Anonymous source-local confidence calibration; IDs/embeddings are not mapped to named songs or shipped."}
    save_json(report_file, report)
    log(f"Yambda calibration complete: shrinkage={chosen}; {report['test']}")
    return report


def listenbrainz(shrinkage):
    cached = WORK / "listenbrainz.json.gz"
    if REUSE_TRAINED and cached.exists():
        with gzip.open(cached, "rt", encoding="utf-8") as f:
            result = json.load(f)
        if "training_events" in result["report"]:
            result["report"]["training_listener_item_pairs"] = result["report"].pop("training_events")
        return result
    log("ListenBrainz: collecting expanded, chronological training profiles")
    identities = read_db(V2 / "data/identities.sqlite")
    aliases = dict(identities.execute("SELECT raw_key,identity FROM aliases"))
    dbs = [read_db(LAB / "data/listens.sqlite"), read_db(V3 / "data/additional-listens.sqlite")]
    listeners = collections.Counter()
    user, seen = None, set()
    rows = 0
    for u, _, raw in heapq.merge(*(db.execute("SELECT user,ts,key FROM events WHERE ts<=? ORDER BY user,ts,key", (TRAIN,)) for db in dbs)):
        if u != user:
            listeners.update(seen)
            user, seen = u, set()
        seen.add(aliases.get(raw, raw))
        rows += 1
        if rows % 2_000_000 == 0:
            log(f"ListenBrainz training events {rows:,}")
    listeners.update(seen)
    selected = [k for k, n in listeners.most_common(150000) if n >= 2]
    index = {key: i for i, key in enumerate(selected)}
    tracks = [None] * len(selected)
    for key, i in index.items():
        row = identities.execute("SELECT metadata FROM items WHERE key=?", (key,)).fetchone()
        if row:
            tracks[i] = json.loads(row[0])
    missing = {key for key, i in index.items() if tracks[i] is None}
    for db in reversed(dbs):
        for row in db.execute("SELECT key,title,artist,album,artist_keys FROM tracks"):
            raw, title, artist, album, artists = row
            key = aliases.get(raw, raw)
            if key in missing:
                tracks[index[key]] = {"key": key, "title": title, "artist": artist, "album": album, "artist_keys": json.loads(artists)}
                missing.remove(key)
    assert not missing, f"Missing named metadata: {len(missing)}"
    profiles, validation, test = {}, collections.defaultdict(list), collections.defaultdict(list)
    user, counts = None, collections.Counter()
    recent = collections.deque(maxlen=256)

    def flush():
        if user is not None:
            # Recency cap bounds both training memory and domination by prolific listeners.
            profiles[user] = list(dict.fromkeys(reversed(recent)))[:MAX_PROFILE][::-1]

    rows = 0
    for u, stamp, raw in heapq.merge(*(db.execute("SELECT user,ts,key FROM events WHERE ts<? ORDER BY user,ts,key", (END,)) for db in dbs)):
        if u != user:
            flush()
            user = u
            recent.clear()
        i = index.get(aliases.get(raw, raw))
        if i is not None:
            if stamp <= TRAIN:
                recent.append(i)
            elif stamp <= VALID:
                validation[u].append(i)
            else:
                test[u].append(i)
        rows += 1
    flush()
    matrix = csr_profiles(list(profiles.values()), len(tracks))
    graph, degree = listening_graph(matrix, shrinkage)
    report = {"input_events_before_test_end": rows, "training_listener_item_pairs": sum(listeners.values()), "named_items": len(tracks),
              "users": len(profiles), "validation": recall(graph, profiles, validation, degree),
              "test": recall(graph, profiles, test, degree), "train_end_utc": dt.datetime.fromtimestamp(TRAIN, dt.timezone.utc).isoformat(),
              "validation_end_utc": dt.datetime.fromtimestamp(VALID, dt.timezone.utc).isoformat(),
              "test_end_utc": dt.datetime.fromtimestamp(END, dt.timezone.utc).isoformat(),
              "popularity_baseline_test": recall([[] for _ in tracks], profiles, test, degree),
              "note": "Train graph excludes all events after train cutoff; validation/test are later days, capped at first 500 eligible users. Metrics measure the source graph before metadata/acoustic fusion and provider resolution."}
    # Fresh temporal evaluation, not the v2 exploratory user cohort.
    result = {"tracks": tracks, "graph": graph, "popularity": degree.tolist(), "report": report}
    with gzip.open(cached, "wt", encoding="utf-8", compresslevel=3) as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
    for db in dbs + [identities]:
        db.close()
    log(f"ListenBrainz fitted: {report}")
    return result


def onion(shrinkage):
    cached = WORK / "onion.json.gz"
    if REUSE_TRAINED and cached.exists():
        with gzip.open(cached, "rt", encoding="utf-8") as f:
            return json.load(f)
    folder = V3 / "data/research-onion"
    db = read_db(folder / "research-names.sqlite")
    tracks = [{"key": "onion:" + key, "artist": artist, "title": title, "album": album} for key, artist, title, album in db.execute("SELECT id,artist,title,album FROM names ORDER BY id")]
    db.close()
    index = {t["key"][6:]: i for i, t in enumerate(tracks)}
    profiles, heldout_profiles, targets = [], {}, {}
    user, counts = None, collections.Counter()
    completed = set()
    rows = plays = 0

    def flush():
        if user is None:
            return
        if user in completed:
            raise RuntimeError("Onion input is not grouped by user")
        completed.add(user)
        chosen = sorted(counts, key=lambda i: (-counts[i], i))[:MAX_PROFILE]
        # Aggregates have no timestamps: this is a user-held-out protocol, not next-song prediction.
        if hash64(str(user)) % 10 == 0 and len(chosen) >= 6:
            ordered = sorted(chosen, key=lambda i: hash64(str(user) + ":" + str(i)))
            heldout_profiles[user] = ordered[:len(ordered) // 2]
            targets[user] = ordered[len(ordered) // 2:]
        else:
            profiles.append(chosen)

    with bz2.open(folder / "userid_trackid_count.tsv.bz2", "rt", encoding="utf-8") as stream:
        reader = csv.reader(stream, delimiter="\t")
        next(reader)
        for u, key, count in reader:
            if u != user:
                flush()
                user, counts = u, collections.Counter()
            n = int(count)
            counts[index[key]] += min(n, 100)
            rows += 1
            plays += n
            if rows % 5_000_000 == 0:
                log(f"Onion aggregate rows {rows:,}")
                budget()
    flush()
    graph, degree = listening_graph(csr_profiles(profiles, len(tracks)), shrinkage)
    report = {"aggregate_rows": rows, "plays": plays, "users": len(completed), "training_users": len(profiles),
              "named_items": len(tracks), "user_held_out": recall(graph, heldout_profiles, targets, degree),
              "popularity_baseline": recall([[] for _ in tracks], heldout_profiles, targets, degree),
              "note": "90% users train; 10% held out, deterministic profile/target partition; aggregate data has no temporal claim."}
    result = {"tracks": tracks, "graph": graph, "popularity": degree.tolist(), "report": report}
    with gzip.open(cached, "wt", encoding="utf-8", compresslevel=3) as f:
        json.dump(result, f, ensure_ascii=False, separators=(",", ":"))
    log(f"Onion fitted: {report}")
    return result


def acoustic_edges(features, seed=1701):
    """Deterministic two-table random-hyperplane ANN; compare only source-local vectors."""
    x = features.astype(np.float32)
    mean, scale = x.mean(axis=0), x.std(axis=0)
    x = np.clip((x - mean) / np.maximum(scale, .00001), -6, 6)
    x /= np.maximum(np.linalg.norm(x, axis=1, keepdims=True), .00001)
    rng = np.random.default_rng(seed)
    candidates = [dict() for _ in x]
    for _ in range(2):
        bits = (x @ rng.normal(size=(x.shape[1], 11)).astype(np.float32)) >= 0
        bucket = bits.astype(np.int32) @ (1 << np.arange(11, dtype=np.int32))
        for key in np.unique(bucket):
            indices = np.flatnonzero(bucket == key)
            # Bound degenerate buckets (silent / identical vectors) deterministically.
            for start in range(0, len(indices), 768):
                group = indices[start:start + 768]
                similarities = x[group] @ x[group].T
                np.fill_diagonal(similarities, -1)
                k = min(8, max(0, len(group) - 1))
                if k == 0:
                    continue
                for row, original in enumerate(group):
                    best = np.argpartition(-similarities[row], k - 1)[:k]
                    for p in best:
                        if similarities[row, p] > .65:
                            other = int(group[p])
                            candidates[original][other] = max(candidates[original].get(other, 0), float(similarities[row, p]))
    return [heapq.nlargest(8, row.items(), key=lambda pair: (pair[1], -pair[0])) for row in candidates]


def build_pack(lb, on, calibration, output):
    budget()
    tracks, graph = [], []
    groups = collections.defaultdict(list)
    report = {"listenbrainz": lb["report"], "onion": on["report"], "yambda": calibration}

    def add(track, source, popularity=0):
        i = len(tracks)
        t = dict(track)
        t["source"] = source
        t["popularity"] = float(popularity)
        t["duration_seconds"] = int(t.get("duration_seconds") or (t.get("duration_ms") or 0) / 1000)
        tracks.append(t)
        graph.append({})
        return i

    def edge(i, j, weight, support, kind):
        if i == j or not 0 <= j < len(tracks) or not math.isfinite(weight) or weight <= 0:
            return
        old = graph[i].get(j)
        value = (weight, min(support, 65535), kind)
        if old:
            value = (max(old[0], weight), max(old[1], value[1]), old[2] | kind)
        graph[i][j] = value

    for corpus, source, kind in ((lb, "listenbrainz", 1), (on, "onion", 2)):
        offset = len(tracks)
        for t, popularity in zip(corpus["tracks"], corpus["popularity"]):
            add(t, source, popularity)
        for i, neighbours in enumerate(corpus["graph"]):
            for j, weight, support in neighbours:
                edge(i + offset, j + offset, weight, support, kind)
    # Retain the earlier validated metadata coverage, including less frequent
    # regional recordings. Do not reuse its later listening graph/popularity.
    existing_keys = {t["key"] for t in tracks}
    with gzip.open(V2 / "pack/catalogue.json.gz", "rt", encoding="utf-8") as stream:
        legacy_metadata = json.load(stream)
    retained_metadata = 0
    for t in legacy_metadata:
        if t["key"] not in existing_keys:
            add(t, "listenbrainz", 0)
            existing_keys.add(t["key"])
            retained_metadata += 1
    report["retained_metadata"] = {"records": retained_metadata, "scope": "Prior CC0 metadata coverage only; no old graph, held-out events or popularity weights reused."}
    # Reuse factual MusicBrainz relationships already validated by exact source IDs.
    wikidata = json.loads((V3 / "data/wikidata-artist-genres.json").read_text(encoding="utf-8"))["artists"]
    for i, t in enumerate(tracks):
        if t["source"] != "listenbrainz":
            continue
        for a in t.get("artist_keys", []):
            for genre in wikidata.get(a, []):
                groups[("artist_genre", genre)].append(i)
        for name in ("composer_keys", "lyricist_keys"):
            for key in t.get(name, []):
                groups[(name, key)].append(i)
    report["wikidata"] = {"artists": len(wikidata), "scope": "Artist genre context through exact artist MBIDs; no inferred song language."}
    # FMA's MFCC and Onion's musicnn are fitted separately, never concatenated.
    for source, path, prefix in (("onion", V3 / "data/research-onion/musicnn-validated.npz", "onion:"),
                                 ("fma", V3 / "data/fma-acoustic.npz", "fma:")):
        if source == "fma":
            db = read_db(V3 / "data/fma.sqlite")
            for key, title, artist, album, genres, duration in db.execute("SELECT id,title,artist,album,genres,duration_seconds FROM tracks ORDER BY id"):
                i = add({"key": prefix + str(key), "title": title, "artist": artist, "album": album, "duration_seconds": duration}, source)
                for g in json.loads(genres):
                    groups[("fma_genre", g)].append(i)
            db.close()
        lookup = {t["key"]: i for i, t in enumerate(tracks) if t["source"] == source}
        data = np.load(path, allow_pickle=False)
        ids = [lookup[prefix + str(key)] for key in data["ids"]]
        log(f"{source}: fitting source-local acoustic neighbours for {len(ids):,} tracks")
        fitted = acoustic_edges(data["features"])
        for local, values in enumerate(fitted):
            for other, score in values:
                edge(ids[local], ids[other], score * .18, 0, 4)
        report[source + "_acoustic"] = {"items": len(ids), "dimensions": data["features"].shape[1], "algorithm": "standardised, L2 normalised, two deterministic hyperplane tables; bounded cosine neighbours"}
    genre = np.load(V3 / "data/research-onion/genre-tfidf-validated.npz", allow_pickle=False)
    genre_ids, genre_offsets = genre["ids"], genre["indptr"]
    genre_values, genre_columns = genre["data"], genre["indices"]
    onion_lookup = {t["key"]: i for i, t in enumerate(tracks) if t["source"] == "onion"}
    for row, key in enumerate(genre_ids):
        lo, hi = genre_offsets[row:row + 2]
        if hi <= lo:
            continue
        best = np.argsort(-genre_values[lo:hi], kind="stable")[:2]
        for at in best:
            groups[("onion_genre", int(genre_columns[lo + at]))].append(onion_lookup["onion:" + str(key)])
    log("Onion genre context fitted; selecting Discogs release context")
    # Discogs source tracklists remain distinct recordings. Include regional entries
    # plus releases containing known artist/title candidates, without asserting an ID join.
    known = {norm(t.get("artist", "")) + "\x1f" + norm(t.get("title", "")) for t in tracks}
    db = read_db(V3 / "data/discogs-catalogue.sqlite")
    regional = {row[0] for row in db.execute("SELECT id FROM releases WHERE regional_context=1")}
    retained = 0
    for key, release, title, artist, album, duration, signature, metadata in db.execute("SELECT * FROM tracks ORDER BY key"):
        if release not in regional and signature not in known:
            continue
        details = json.loads(metadata)
        if not details.get("position") and not duration:
            continue
        i = add({"key": key, "title": title, "artist": artist, "album": album, "duration_seconds": duration}, "discogs")
        groups[("discogs_release", release)].append(i)
        retained += 1
    db.close()
    report["discogs"] = {"retained_tracklist_entries": retained, "regional_releases": len(regional), "scope": "Release context only, no verified cross-source recording joins."}
    log(f"Discogs: retained {retained:,} source tracklist entries; linking composition context")
    # OpenOpus contributes composition context only where a full composer credit and
    # the full work title are both present. Works are never fabricated as playable songs.
    db = read_db(V3 / "data/openopus.sqlite")
    composers = {key: norm(json.loads(metadata)["complete_name"]) for key, metadata in db.execute("SELECT * FROM composers")}
    by_composer = collections.defaultdict(list)
    for i, t in enumerate(tracks):
        credited = norm(t.get("artist", ""))
        for key, name in composers.items():
            full = name
            if full and full in credited:
                by_composer[key].append(i)
    work_matches = 0
    normalized_titles = [norm(t.get("title", "")) for t in tracks]
    for key, composer, title in db.execute("SELECT key,composer_key,title FROM works"):
        wanted = norm(title)
        if len(wanted) < 8:
            continue
        for i in by_composer.get(composer, []):
            if wanted in normalized_titles[i]:
                groups[("openopus_work", key)].append(i)
                work_matches += 1
    db.close()
    report["openopus"] = {"works_examined": 24975, "composer_work_context_matches": work_matches, "scope": "Conservative composition context, never a playable recording ID."}
    # Same-credit/album context provides coverage for seeds without enough listens.
    for i, t in enumerate(tracks):
        artist, album = norm(t.get("artist", "")), norm(t.get("album", ""))
        if artist and artist not in ("various artists", "unknown", "unknown artist"):
            groups[("artist", artist)].append(i)
        if album and artist:
            groups[("album", artist + "\x1f" + album)].append(i)
    for (kind, _), indices in groups.items():
        if len(indices) < 2:
            continue
        # A broad genre is weak context, not proof two recordings are linked.
        weight = .10 if kind in ("album", "discogs_release", "openopus_work") else .025 if "genre" in kind else .06
        popular = sorted(indices, key=lambda i: (-tracks[i]["popularity"], tracks[i]["key"]))[:16]
        for i in indices:
            for j in popular[:8]:
                edge(i, j, weight, 0, 8)
    log(f"Writing binary pack: {len(tracks):,} source records")
    # Sorted signed 64-bit hashes allow direct binary search in the mmap reader.
    def signature(t):
        return title_key(t.get("title", ""))
    order = sorted(range(len(tracks)), key=lambda i: (hash64(signature(tracks[i])), tracks[i]["key"]))
    remap = np.empty(len(order), dtype=np.int32)
    remap[order] = np.arange(len(order), dtype=np.int32)
    strings, offsets = bytearray(b"\0"), {"": 0}

    def intern(value):
        value = str(value or "").replace("\0", " ")[:2048]
        if value not in offsets:
            offsets[value] = len(strings)
            strings.extend(value.encode("utf-8") + b"\0")
        return offsets[value]

    records, edges = bytearray(), bytearray()
    indptr = [0]
    for old in order:
        t = tracks[old]
        title = str(t.get("title") or "")
        flags = int(any(word in norm(title) for word in VERSION_WORDS))
        records.extend(struct.pack("<4iifii3q", intern(t["key"]), intern(title), intern(t.get("artist")), intern(t.get("album")),
                                   t["duration_seconds"], math.log1p(t["popularity"]), SOURCES[t["source"]], flags,
                                   hash64(signature(t)), hash64(norm(t.get("artist", ""))), hash64(norm(t.get("album", "")))))
        selected = sorted(graph[old].items(), key=lambda pair: (-pair[1][0], pair[0]))[:16]
        for other, (weight, support, kind) in selected:
            edges.extend(struct.pack("<ifHBB", int(remap[other]), weight, support, kind, 0))
        indptr.append(len(edges) // 12)
    header = b"CTREC003" + struct.pack("<8i", 3, len(tracks), len(edges) // 12, len(strings), 56, 12, 2, 0)
    body = header + records + struct.pack(f"<{len(indptr)}i", *indptr) + edges + strings
    if len(body) > 256 * 1024**2:
        raise RuntimeError("Unpacked model exceeds 256 MiB limit")
    output.mkdir(parents=True, exist_ok=True)
    target = output / "recommendations.ctrec.gz"
    with target.open("wb") as raw:
        with gzip.GzipFile(filename="", fileobj=raw, mode="wb", mtime=0, compresslevel=9) as f:
            f.write(body)
    if target.stat().st_size > 64 * 1024**2:
        raise RuntimeError("Compressed model exceeds 64 MiB limit")
    manifest = {"schema": 3, "model_version": "2026-10-23", "items": len(tracks), "edges": len(edges) // 12,
                "unpacked_bytes": len(body), "compressed_bytes": target.stat().st_size,
                "sha256": hashlib.sha256(body).hexdigest(), "minimum_behaviour_support": 2,
                "source_records": dict(collections.Counter(t["source"] for t in tracks)),
                "yambda_shrinkage": calibration["shrinkage"], "training": report,
                "limitations": ["An offline metadata model does not provide offline audio or streaming rights.",
                                "Provider search must resolve named candidates before playback.",
                                "Anonymous Yambda IDs calibrate graph confidence and are never mapped to named songs.",
                                "Acoustic neighbours are approximate and source local; broad genre/context is weaker than listening evidence.",
                                "No claim of universal recommendation improvement; see held-out aggregate metrics."]}
    save_json(output / "manifest.json", manifest)
    save_json(WORK / "training-report.json", manifest)
    log(f"MODEL COMPLETE: {len(tracks):,} items, {manifest['edges']:,} edges, {target.stat().st_size / 1024**2:.2f} MiB compressed")


def main():
    global REUSE_TRAINED
    parser = argparse.ArgumentParser()
    parser.add_argument("--output", type=Path, default=WORK / "teacher")
    parser.add_argument("--stage", choices=("all", "yambda", "listenbrainz", "onion"), default="all")
    parser.add_argument("--reuse-trained-graphs", action="store_true", help="Explicitly resume graph caches from this same frozen input snapshot; do not use after changing datasets or graph parameters")
    args = parser.parse_args()
    REUSE_TRAINED = args.reuse_trained_graphs
    WORK.mkdir(parents=True, exist_ok=True)
    budget()
    calibration = calibrate_yambda()
    if args.stage == "yambda":
        return
    lb = listenbrainz(calibration["shrinkage"])
    if args.stage == "listenbrainz":
        return
    on = onion(calibration["shrinkage"])
    if args.stage == "onion":
        return
    build_pack(lb, on, calibration, args.output)


if __name__ == "__main__":
    main()
