#!/usr/bin/env bash
# Pulls main and rebuilds. The GitHub deploy key is pinned to this script
# (forced command), so a leaked key can do nothing but redeploy main.
set -euo pipefail
# deployed service name: renaming needs a new deployment
SRC=/opt/bitchord-src
REPOSITORY=https://github.com/Vijay-Janarthanan/cosmotune.git
if [ ! -d "$SRC/.git" ]; then
  git clone --depth 1 --branch main "$REPOSITORY" "$SRC"
fi
# An existing deployment may still hold the upstream project. Refuse it rather
# than silently rebuilding the wrong application or repointing its checkout.
case "$(git -C "$SRC" remote get-url origin)" in
  "$REPOSITORY"|https://github.com/Vijay-Janarthanan/cosmotune|git@github.com:Vijay-Janarthanan/cosmotune.git) ;;
  *) echo 'Refusing deployment: the source checkout origin is not Cosmotune. Prepare a separate Cosmotune checkout first.' >&2; exit 1 ;;
esac
git -C "$SRC" fetch --depth 1 origin main
git -C "$SRC" reset --hard FETCH_HEAD
echo "deploying $(git -C "$SRC" log -1 --format='%h %s')"
# deployed service name: renaming needs a new deployment
DOMAIN="$(cat /etc/bitchord-domain)" bash "$SRC/backend/deploy/setup.sh"
