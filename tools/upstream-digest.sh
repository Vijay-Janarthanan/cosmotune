#!/usr/bin/env bash
# Prints what changed in upstream (BitChord) since the commit recorded in UPSTREAM.md, grouped so that
# you can decide what to take over. It only reads: it fetches the `upstream` remote and prints a digest.
#
#   tools/upstream-digest.sh                          from UPSTREAM.md's last merged commit to upstream/main
#   UPSTREAM_BASE=<commit> tools/upstream-digest.sh   from another commit (for testing)
#
# Environment, all optional:
#   UPSTREAM_BASE   start commit (anything git can resolve); default: the commit named in UPSTREAM.md
#   UPSTREAM_FILE   the file with the "Last merged commit" line (default UPSTREAM.md)
#   FORK_FILE       the fork ledger table (default FORK.md)
#   A relative file name is read from the repository root.
#
# Hidden option: --print-ledger prints the repository paths read from the ledger, one per line, and exits.
#
# Runs in Git Bash on Windows and in bash on Linux and macOS. The files it reads may have LF or CRLF
# line endings. Errors go to standard error with a non-zero exit; the digest goes to standard output.
set -euo pipefail

die() { echo "upstream-digest: $*" >&2; exit 1; }
warn() { echo "upstream-digest: $*" >&2; }

cd "$(dirname "$0")/.."
upstream_file="${UPSTREAM_FILE:-UPSTREAM.md}"
fork_file="${FORK_FILE:-FORK.md}"

# --- The fork ledger -------------------------------------------------------------------------------
# Every backtick span in the first cell of a table row of the ledger that looks like a repository path.
# One path per line, each once, in the order of the ledger. The spans are not always plain paths:
#   `app/.../data/Foo.kt`  the `...` stands for folders that were left out
#   `.../desktop/Foo.kt`   a leading `.../` is dropped (the path is matched from its end anyway)
#   `values*/strings.xml`  and `desktop-{de,en}.xml`   a glob and a brace list
#   `.github/scripts/`     a folder (a trailing `/`): every file under it
#   `DesktopApp.kt`        a bare file name: any file of that name (a bare `.gitattributes` too)
# Text that is not a path (`DesktopSettingsFooter`, `cosmotune-backup-<date>.json`) is left out. The header
# and separator rows have no backticks, so they give nothing.
ledger_paths() {
  [ -f "$fork_file" ] || die "$fork_file not found (the fork ledger)"
  tr -d '\r' < "$fork_file" | awk '
    BEGIN { known = " kt kts java md xml yml yaml sh py go gradle properties json toml svg png jpg jpeg webp ico icns txt example cpp c h cmake " }
    /^\| / {
      rest = substr($0, 3)
      bar = index(rest, "|")
      cell = (bar > 0) ? substr(rest, 1, bar - 1) : rest
      n = split(cell, part, "`")
      for (i = 2; i < n; i += 2) {              # the even parts lie between two backticks
        s = part[i]
        gsub(/^[ \t]+|[ \t]+$/, "", s)
        while (sub(/^\.\.\.\//, "", s)) { }
        if (s == "" || s !~ /^[A-Za-z0-9._\/*{},+@-]+$/) continue
        ext = ""
        if (index(s, ".") > 0) { ext = s; sub(/.*\./, "", ext) }
        if (index(s, "/") == 0 && s !~ /^\.[A-Za-z0-9_-]+$/ && (ext == "" || index(known, " " ext " ") == 0)) continue
        if (!(s in seen)) { seen[s] = 1; print s }
      }
    }'
}

# Reads ledger paths, writes "<path><TAB><extended regular expression>" lines. The expression matches the
# whole path or its end after a "/" (never part of a file name: Foo.kt does not match BarFoo.kt).
ledger_patterns() {
  awk '
    {
      s = $0; dir = 0
      if (s ~ /\/$/) { dir = 1; sub(/\/+$/, "", s) }
      if (s == "") next
      out = ""; depth = 0; n = length(s); i = 1
      while (i <= n) {
        c = substr(s, i, 1)
        if (substr(s, i, 5) == "/.../") { out = out "/(.*/)?"; i += 5; continue }
        if (c == "*") out = out "[^/]*"
        else if (c == "{") { out = out "("; depth = 1 }
        else if (c == "}" && depth) { out = out ")"; depth = 0 }
        else if (c == "," && depth) out = out "|"
        else if (index(".+?()|[]\\^$", c) > 0) out = out "\\" c
        else out = out c
        i++
      }
      if (depth) next
      print $0 "\t(^|/)" out (dir ? "/" : "$")
    }'
}

case "${1:-}" in
  "") ;;
  --print-ledger) ledger_paths; exit 0 ;;
  *) die "unknown argument: $1 (usage: tools/upstream-digest.sh)" ;;
esac

# Read the ledger now, so that a missing or unreadable file stops the run before anything is printed.
patterns="$(ledger_paths | ledger_patterns)"

# --- The range -------------------------------------------------------------------------------------
if ! git remote get-url upstream >/dev/null 2>&1; then
  url="$(awk 'match($0, /https:\/\/github\.com\/[A-Za-z0-9_.-]+\/[A-Za-z0-9_.-]+/) {
                u = substr($0, RSTART, RLENGTH); sub(/\.+$/, "", u); print u; exit }' "$upstream_file" 2>/dev/null || true)"
  echo "upstream-digest: there is no git remote named upstream. Add it (read-only use is enough):" >&2
  echo "  git remote add upstream ${url:-<the address of the original project, named in $upstream_file>}" >&2
  exit 1
fi

# The first "Last merged commit" line of UPSTREAM.md: with or without bold or code marks, LF or CRLF.
last_merged_commit() {
  tr -d '\r' < "$upstream_file" | sed -n -E '
    /[Ll]ast [Mm]erged [Cc]ommit/ {
      s/.*[Ll]ast [Mm]erged [Cc]ommit[^0-9a-fA-F]*//
      s/^([0-9a-fA-F]{7,40})([^0-9a-fA-F].*)?$/\1/p
    }' | awk 'NR == 1 { print }' | tr 'A-F' 'a-f'
}

if [ -n "${UPSTREAM_BASE:-}" ]; then
  base_ref="$UPSTREAM_BASE"
  base_from="UPSTREAM_BASE"
else
  [ -f "$upstream_file" ] || die "$upstream_file not found"
  base_ref="$(last_merged_commit)"
  base_from="$upstream_file"
  [ -n "$base_ref" ] || die "no base commit: $upstream_file has no 'Last merged commit: <hash>' line"
fi

if ! git fetch upstream --quiet; then
  warn "could not fetch upstream (offline?): using the copy of upstream/main from the last fetch"
fi
head="$(git rev-parse --verify --quiet 'upstream/main^{commit}')" || die "upstream/main is not known here: run: git fetch upstream"
base="$(git rev-parse --verify --quiet "$base_ref^{commit}")" ||
  die "the base commit $base_ref (from $base_from) is not in this repository: run: git fetch upstream (and check the hash)"
git merge-base --is-ancestor "$base" "$head" ||
  warn "${base:0:8} is not an ancestor of upstream/main: the lists below may hold commits that are not new"

range="$base..$head"
count="$(git rev-list --count "$range")"

echo "Upstream ${base:0:8} -> ${head:0:8}: $count commits"

# --- The digest ------------------------------------------------------------------------------------
section() { echo; echo "== $1 =="; }

# commits LIMIT <git log arguments>: at most LIMIT "hash subject" lines, and a note when there are more.
# The limit is git's own (--max-count), not "| head", so that nothing is closed early under pipefail.
commits() {
  local limit="$1" out total
  shift
  out="$(git log --no-merges --format='%h %s' --max-count="$limit" "$@")"
  if [ -z "$out" ]; then echo "  none"; return 0; fi
  printf '%s\n' "$out" | sed 's/^/  /'
  total="$(git rev-list --count --no-merges "$@")"
  [ "$total" -le "$limit" ] || echo "  ... and $((total - limit)) more"
}

section "YouTube, stream and playback fixes (most urgent)"
commits 40 "$range" -- shared app/src/main/java/com/music/bitchord/playback app/src/main/java/com/music/bitchord/data/innertube desktopApp/src/main/kotlin/com/music/bitchord/desktop/DesktopAudioEngine.kt

section "Security and account handling"
commits 20 -i -E --grep='security|vulnerab|cookie|token|leak|secret' "$range"

section "New features (feat) worth adopting"
commits 40 -i -E --grep='^feat' "$range"

section "Interface changes we will ignore (our own screens replace them)"
commits 20 "$range" -- sharedUi app/src/main/java/com/music/bitchord/ui

section "Upstream files in our ledger that changed (merge by hand)"
# --no-renames: a file that upstream moved shows up under its old name too, which is the name in the ledger.
# Upstream keeps its files in its own package folder; ours are in com/cosmotune/music since the rename (FORK.md),
# which is the path the ledger names, so upstream's paths are read as ours before they are matched.
changed="$(git diff --name-only --no-renames "$range" | sed 's#/com/music/bitchord/#/com/cosmotune/music/#')"
found=0
while IFS=$'\t' read -r entry re; do
  [ -n "$entry" ] || continue
  hits="$(printf '%s\n' "$changed" | grep -E -e "$re" || true)"
  [ -n "$hits" ] || continue
  found=1
  echo "  $entry"
  # A plain path shows once. A path with "..." or a glob or a folder shows the files it stands for.
  if [ "$hits" != "$entry" ]; then
    nhits="$(printf '%s\n' "$hits" | grep -c '')"
    printf '%s\n' "$hits" | awk 'NR <= 3 { print "      " $0 }'
    [ "$nhits" -le 3 ] || echo "      ... and $((nhits - 3)) more"
  fi
done <<< "$patterns"
[ "$found" = 1 ] || echo "  none"

section "Build files and workflows"
build="$(git diff --name-only --no-renames "$range" -- '*.gradle.kts' gradle .github)"
if [ -n "$build" ]; then printf '%s\n' "$build" | sed 's/^/  /'; else echo "  none"; fi

exit 0
