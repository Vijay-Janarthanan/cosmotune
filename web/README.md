# Cosmotune browser player (1.0.4 in development)

This companion is a separate browser app. It includes music browsing/search, album/artist/playlist pages, account libraries, queue/radio/autoplay, playback history, timed lyrics, video and motion artwork, Spotify library/Canvas integration and Listen Together. The helper extension handles permitted cross-origin requests and browser account sessions; cookies and signing tokens stay inside the extension. It is different from the native app's paired LAN controller.

The native Android/desktop interfaces keep their existing design. The browser port uses Cosmotune's existing mark and warm accent, system fonts, separate browser storage and its own extension identity. Discord stays disabled. It does not bundle Node, the web app or an extension into the native installers.

## Local development

Use Node 22.12 or newer and the `pnpm` version recorded in package.json:

```sh
pnpm --dir web install --frozen-lockfile
pnpm --dir web typecheck
pnpm --dir web test
pnpm --dir web build:site
pnpm --dir web/app dev
```

The exact development origin is `http://localhost:5173`. Chromium: load `web/extension/.output/chrome-mv3` as an unpacked extension. Firefox: load `web/extension/.output/firefox-mv3/manifest.json` as a temporary extension. Open the site's Extension page to check protocol/version/transport and download packages. Reload the extension after rebuilding it. Installing an extension and connecting an account remain explicit browser actions.

## Hosting and release packages

`build:site` builds Chromium and Firefox helpers, packages both into the site's downloads directory and builds `web/app/dist`. Serve that directory with SPA fallback to `index.html` for non-file routes. A static server alone does not remove the helper requirement. Chromium ZIPs are unpacked development builds; Firefox unsigned ZIPs require temporary loading or publisher signing.

Before building for an actual deployment, set `COSMOTUNE_SITE_ORIGINS` to a comma-separated list of exact HTTPS origins. The helper checks the full sender origin, including port, in addition to browser match patterns. Store builds (`COSMOTUNE_STORE_BUILD=1`) require that configuration and reject loopback HTTP. The default archive is configured only for local development; no public domain is assumed or deployed by this work. A store-assigned Chromium extension ID belongs in `VITE_EXTENSION_ID_CHROMIUM`. The committed public development key pins the unpacked helper ID; its private key was discarded.

Listen Together has no default public service. Set a server in Settings, or supply `VITE_LISTEN_TOGETHER_SERVER` at build time, using the deployment's existing party backend. Other optional `VITE_*` download links are documented in `app/.env.example`. Account sign-in happens on YouTube Music/Spotify; the helper's connect/disconnect controls select whether the site may use those browser sessions. The account and extension permissions are separate from native app accounts.

## Validation and provenance

Type checking and both production helper/site builds pass, with 131 tests: 122 app/player/parser/party/render checks, five bridge/stream-budget checks and four helper origin/sender checks. A real browser verified navigation, generated downloads, corrected contrast, the party route and missing-backend keyboard gating. Installed-helper/account/live playback checks remain distinct gates; parser fixtures do not prove signed-in playback.

Pinned source, original hashes and exclusions are in `UPSTREAM_IMPORT.json`; source-record IDs resolve to exact upstream links in root FORK.md. Original authors and licence are credited in root NOTICE.md. Extra font binaries, old icons/key, captured lyric/Spotify documents and upstream design notes were excluded. Anonymous catalogue fixtures remain test-only. `tools/verify-companion-imports.py` checks the index/ledger correspondence without weakening the existing source guards. The site is not published yet; 1.0.4 remains one full-tracker release.
