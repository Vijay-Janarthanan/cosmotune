# Upstream

Cosmotune starts from BitChord (https://github.com/kushagrasinghx/BitChord), GPLv3.

Last merged commit: fe95bb60aa9cd205cf4ab8bd25a5bec71a595b4f
Date: 2026-10-13

Update this file in the same commit that merges new upstream work (tools/upstream-digest.sh reads the commit above).

## Current integration audit

The [28 October delta review](docs/UPSTREAM_REVIEW_2026-10-28.md) inspected current upstream main `04a4a83889bb8fa5bd6ee6f6097f0c6608671d68`, 104 commits beyond the prior review pin. This is a review, not a merge. Upstream has removed its desktop app in favor of the web player; Cosmotune retains its native desktop implementation. New native correctness fixes and browser Automix/menu/lyrics changes are recorded separately from the historical selected integration below.

Reviewed upstream main `edc1e6f36febf71a118e649411d2fc21b70ead45` on 2026-10-23. This is a selected integration in progress, not a claim that the whole upstream branch was merged. The baseline above remains the last complete merge.

The native integration includes artist artwork/signatures, decoder format display, queue windows/history, continuation parsing, DSP/Automix, Cast and a Wear OS companion with phone bridge/install/output support. Source and release packaging are being validated. The initial omission of Wear OS was corrected following the user's review.

The commit-by-commit native audit of `fe95bb60..edc1e6f3` is complete: every commit is mapped to a tracker row or a recorded decision in [docs/NATIVE_UPSTREAM_AUDIT.md](docs/NATIVE_UPSTREAM_AUDIT.md) (rows BC-27 to BC-37). Mapping a change is not importing it; the open rows say what is still to do.

[FEATURE_TRACKER.md](FEATURE_TRACKER.md) inventories the native, companion, TV and browser features, including gaps and platform constraints. Preserve Cosmotune's identity, privacy settings, OpenCanvas boundary, tiny ranker, model compatibility fixes and target-native packaging when integrating further changes.
