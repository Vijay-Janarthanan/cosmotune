# Live websites and deployment sources

Reviewed 28 October 2026. The Shutter Island custom domains and their Firebase aliases all returned HTTP 200 over HTTPS. These are product/documentation sites; the browser music companion is distributed separately and is not deployed by updating a product page.

| Page | Public URL | Hosting alias / source |
| --- | --- | --- |
| Cosmotune product and downloads | https://cosmotune.shutterisland.in/ | https://cosmotune-vj-c4394.web.app/ · `D:/Vijay/Personal/shutterisland/sites/cosmotune/public/` |
| OpenCanvas product, docs and browser lab | https://opencanvas.shutterisland.in/ | https://opencanvas-vj-c4394.web.app/ · `D:/Vijay/Personal/shutterisland/sites/opencanvas/public/` |
| Shutter Island portfolio and project case files | https://www.shutterisland.in/ | https://portfolio-c4394.web.app/ · `D:/Vijay/Personal/shutterisland/mockups/` → `publish/` |
| OpenCanvas GitHub documentation and browser lab | https://vijay-janarthanan.github.io/OpenCanvas/ | OpenCanvas repository `gh-pages`; editable technical source in `main:docs/` |
| Native downloads and release notes | https://github.com/Vijay-Janarthanan/cosmotune/releases/latest | Cosmotune release workflow; source notes in `docs/releases/` |

The portfolio's `/cosmotune.html`, `/opencanvas.html` and `/docs.html` pages need updating with the standalone sites. PreppUp and unrelated websites are separate projects and are not part of this update.

## Firebase publication

The existing project is `portfolio-c4394`. `.firebaserc` maps `portfolio`, `opencanvas` and `cosmotune` to the three sites above. Deployment updates both custom domain and `web.app` alias; there is no second custom-domain build.

From the Shutter Island source directory, use the existing authenticated Firebase CLI:

```sh
npm run check
npm run deploy:firebase
```

This checks portfolio links, builds `publish/`, fingerprints Cosmotune's CSS/JavaScript and deploys the existing targets. For a single page family use `deploy:portfolio`, `deploy:opencanvas` or `deploy:cosmotune`. Preserve target IDs, unrelated projects, existing images/media and the OpenCanvas lab's sample/engine assets. Never commit Firebase account data or deploy logs containing credentials.

OpenCanvas's editorial introduction is separate from its technical documentation. Synchronize the technical sections, `demo/`, `engine/` and linked Markdown with the upstream `docs/` source while keeping that introduction and `site-v2.css`/`site-v2.js`. The framing comparison uses a 4:5 canvas and illustrated detections with the real compiled camera policy; it does not run native face/person inference in the browser.

## Current documentation baseline

Version 1.0.10 is published. Its release workflow completed successfully and all 49 uploaded assets are non-empty. Downloads use `/releases/latest`, which now resolves to 1.0.10. The product and portfolio pages describe it as available rather than a release preview. Publication does not replace the physical-device/account checks documented in the release notes; do not move an app tag for website-only changes.

Coverage includes Music/Video, Manga/Podio, radio globe and station pages, regional podcast charts, discovery choices, the 3.19 MB local ranker, source comparisons and decoder quality, 4:5 video, artist fallback/motion headers, library tools, companion apps and the new desktop controls. Retain stated limits: public RSS rather than paid-only podcast access, service/session-dependent providers, platform-dependent companions, outstanding physical-device checks and unverified immersive output.

The 1.0.10 MP4/forward-only/search repairs are currently in Cosmotune's vendored OpenCanvas code; porting them to standalone OpenCanvas remains tracked work. The published OpenCanvas 1.0.0 artifact and historical integration-demo installers must not be described as containing the newest source features.

## Publication verification

All three Firebase targets were deployed on 28 October 2026. The three custom-domain homepages, their three hosting aliases and the portfolio's three project/documentation pages returned HTTP 200 and matched the reviewed source. OpenCanvas [GitHub Pages source](https://github.com/Vijay-Janarthanan/OpenCanvas/commit/51d6717f367cebd08802bc96f9a91fc6d1e80caf) is also published; the served HTML matches its `gh-pages` source. Browser checks covered mode tabs, keyboard navigation, audio-quality copy, the 4:5 framing scene controls and phone/desktop overflow. The configuration table now scrolls inside its card, and changed demo assets have cache keys. No native app tag or installer was changed by this website update.
