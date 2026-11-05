# Paperback editions and the Paperback app

Reviewed 2 November 2026 for the Read feature.

## Physical and digital editions

Paperback and hardcover are physical edition bindings, not digital reader file formats. Open Library describes works separately from editions; editions can have different publishers, languages, publication dates, ISBNs, physical formats and illustrations. Cosmotune keeps that identity visible instead of promising that any digital file is the same edition. A colour-capable renderer preserves supplied colour illustrations; it does not create a colour edition of a text-only book.

[Open Library works and editions API](https://openlibrary.org/dev/docs/api/books), [publisher illustrated editions](https://www.bloomsbury.com/uk/harry-potter/illustrated-editions/).

## Paperback app review

The Paperback-iOS organization describes an iOS manga reader. Its public `app` repository hosts releases, issues and requests; it is not the full native reader implementation. Its open TypeScript toolchain provides extension contracts. The current 0.9 contract separates search results, chapter pages, discovery sections, managed collections, progress and settings capabilities. The runtime includes app-provided request scheduling, registered interceptors/selectors, redirects, cookies and state. This is a different host ABI from Cosmotune's Kotatsu parsers and Android Manga extension bridge.

Inspected: `packages/types/src/impl/Extension.ts` and `packages/runtime-polyfills/src/RequestManager.ts` in the official 0.9 toolchain. [Official organization](https://github.com/Paperback-iOS), [toolchain](https://github.com/Paperback-iOS/paperback-toolchain/tree/0.9), [extension interface](https://github.com/Paperback-iOS/paperback-toolchain/blob/0.9/packages/types/src/impl/Extension.ts).

Inkdex maintains a registry for Paperback 0.9. Its installation instructions use `https://inkdex.github.io/extensions/0.9/stable`; installing that URL into an unrelated parser host does not implement the Paperback runtime. Their content-service guide currently says it is forthcoming. [Installation](https://inkdex.github.io/installation), [registry](https://github.com/inkdex/extensions), [service guide](https://inkdex.github.io/guides/content-service-extensions).

## Adopted and unsupported

Read uses the useful product pattern: library first, discovery separate, source identity, saved progress, bookmarks, offline imports, collections and source settings. Manga retains its existing chapter/image readers and compatible extension systems. Books adds real EPUB/PDF document reading and OPDS acquisition.

Paperback 0.9 extension execution is **not implemented**. It needs a separately versioned JavaScript host, capability/selector/request/cookie/state bridge, update/signature policy, compatibility fixtures and independent verification on Android and desktop. No registry has been silently installed or advertised as executable. This review does not imply that every extension supplies licensed or universally available files.
