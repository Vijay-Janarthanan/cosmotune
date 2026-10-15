# Working on the code

## Keeping in step with upstream

Cosmotune began as a fork of BitChord and still takes fixes from it by hand, on request, not on a schedule.

- `UPSTREAM.md` names the last upstream commit we have taken.
- `FORK.md` lists every edit we made to a file that came from upstream, with how to merge it. Read the row
  for a file before you change it or merge a change to it.
- A fresh clone has no upstream history. Add it once with `git remote add upstream <the original repository's address, named
  in `NOTICE.md`>` and `git fetch upstream`; the digest script tells you the same when the remote is missing.
- `tools/upstream-digest.sh` prints what changed upstream since that commit, grouped (playback fixes,
  security, features, files in the ledger that changed). It needs a git remote called `upstream` that points at
  the original repository.

New Cosmotune-only code goes under `com.cosmotune`; the Kotlin packages of upstream files keep their names so
that merges stay small. `ForkGuardTest` (run with `./gradlew :shared:jvmTest --tests "com.cosmotune.ForkGuard*"`)
checks these rules.

## Tests

`docs/BASELINE.md` lists the tests that already failed before our changes, so a new failure is easy to spot.
