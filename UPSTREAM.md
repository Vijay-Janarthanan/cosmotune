# Upstream

Cosmotune starts from BitChord (https://github.com/kushagrasinghx/BitChord), GPLv3.

Last merged commit: fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f
Date: 2026-10-13

Update this file in the same commit that merges new upstream work (tools/upstream-digest.sh reads the commit above).

## Repository history

On 5 November 2026 Cosmotune moved to a new repository that starts from one commit holding the whole tree (Cosmotune 1.0.15 and the work after it). The earlier history, issues and releases are in the archived `cosmotune-archive` repository. The licence, `NOTICE.md`, this file and the acknowledgements still credit BitChord and its contributors, as the GPLv3 requires.

What this changes about upstream work: the new history shares no ancestor with BitChord's, so `git merge upstream/main` no longer works. Everything else here still does, because it only reads BitChord's own history: `tools/upstream-digest.sh`, the commit hash above, `git log <hash>..upstream/main` and `git diff <hash>..upstream/main`. To bring a change in, port it by hand or with `git cherry-pick <upstream commit>` (a cherry-pick applies the patch and needs no shared ancestor), then update the hash above in the same commit.

## 2 November 2026 selected integration

The [current 20-repository review](docs/UPSTREAM_REVIEW_2026-11-02.md) compares exact previous/current heads and separates published releases from main-branch features. Selected native/browser catalogue matching, authenticated upload ownership hints, per-track provider modes and the compatible parser refresh passed the documented native/browser gates against Cosmotune 1.0.13. Complete-merge history below is retained; this is not a blanket merge. Browser adaptations are individually pinned in `web/UPSTREAM_DELTAS.json` and `FORK.md`.

## Current integration audit

The [9 November review](docs/UPSTREAM_REVIEW_2026-11-09.md) checks the same 20 branches against the 2 November snapshot, plus OpenCanvas separately.

The [28 October delta review](docs/UPSTREAM_REVIEW_2026-10-28.md) inspected current upstream main `04a4a83889bb8fa5bd6ee6f6097f0c6608671d68`, 104 commits beyond the prior review pin. This is a review, not a merge. Upstream has removed its desktop app in favor of the web player; Cosmotune retains its native desktop implementation. New native correctness fixes and browser Automix/menu/lyrics changes are recorded separately from the historical selected integration below.

Reviewed upstream main `edc1e6f36febf71a118e649411d2fc21b70ead45` on 2026-10-23. This is a selected integration in progress, not a claim that the whole upstream branch was merged. The baseline above remains the last complete merge.

The native integration includes artist artwork/signatures, decoder format display, queue windows/history, continuation parsing, DSP/Automix, Cast and a Wear OS companion with phone bridge/install/output support. Source and release packaging are being validated. The initial omission of Wear OS was corrected following the user's review.

The commit-by-commit native audit of `fe95bb60..edc1e6f3` is complete: every commit is mapped to a tracker row or a recorded decision in [docs/NATIVE_UPSTREAM_AUDIT.md](docs/NATIVE_UPSTREAM_AUDIT.md) (rows BC-27 to BC-37). Mapping a change is not importing it; the open rows say what is still to do.

[FEATURE_TRACKER.md](FEATURE_TRACKER.md) inventories the native, companion, TV and browser features, including gaps and platform constraints. Preserve Cosmotune's identity, privacy settings, OpenCanvas boundary, tiny ranker, model compatibility fixes and target-native packaging when integrating further changes.

## Mochy Music (selected ports)

Mochy Music (https://github.com/realidkroo/Mochy-Music, GPL-3.0) is not an upstream: Cosmotune does not follow its history. Selected
features were ported by hand from `main` at `8dd8f3b0` (6 November 2026), as listed in `NOTICE.md`; the comparison that chose them is
`docs/superpowers/research/2026-11-06-mochy-music-comparison.md` in the planning repository. To review Mochy again, compare its
`main` against `8dd8f3b0` and port by hand.
