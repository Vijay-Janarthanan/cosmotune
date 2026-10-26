# Amazon resolution investigation — 2026-10-26

The initial timeout was a Cosmotune integration failure. It did not establish that Amazon search or the upstream extension was broken.

## Controlled comparison

The same hash-reviewed Amazon `2.3.14` package from the Mornye registry was executed through:

1. Lokal's unmodified QuickJS worker, bootstrap, host and networking modules at `dc43d85db7711279f58d969107883084965b2005`, in an isolated workspace with an in-memory vault.
2. Cosmotune's GraalJS provider runtime and native metadata/network host, in a separate isolated profile.

No existing app account/profile was loaded. The full Mornye app UI was not run: the available official release inspected here supplied ARM Android/iOS artifacts, rather than a Windows portable build. The upstream proof is specifically Lokal's actual extension runtime executing the Mornye Amazon package.

Upstream `config.json` and `showSearch` both returned HTTP 200. Searching “Blinding Lights The Weeknd” returned eleven catalogue entries in roughly three seconds in that run.

## Why our request kept retrying

Our HTTP requests also received HTTP 200, but the search's valid JSON text was about 1.56 MB. The fetch bridge included both the text body and a Base64 representation for `arrayBuffer()`, producing an internal envelope of about 3.77 MB. `ProviderMetadataHost.bridge` incorrectly applied the raw metadata limit of 2 MiB to that expanded envelope. The extension received a host failure, refreshed its Amazon session and retried repeatedly. The original short probe budget expired during those retries.

The implementation now distinguishes:

- **Raw network metadata:** still at most 2 MiB, enforced during reads including unknown/chunked lengths.
- **Internal host envelope:** at most 8 MiB, accommodating text/Base64/JSON encoding overhead.
- **Guest arguments and final script results:** retain their separate 2 MiB bounds.

An isolated diagnostic that changed only the envelope boundary returned ten normalized search results. The production host fix was then rerun without that diagnostic override and again returned ten results. Counts can vary with the service response; equality of counts was not the success criterion.

The final `ProviderManager` probe also used the production search → `getTrack` enrichment → recording match → audio resolution path. It reached the stream phase and reported `ACCESS_REQUIRED` (`Provider sign-in or verification required`), rather than timing out in search or rejecting an untimed search row. This agrees with the clean upstream audio result below; it does not establish audio playback.

`ProviderFetchEnvelopeTest` covers a real 1.55-million-character JSON response through the actual guest/host bridge and asserts the raw network limit remains 2 MiB. The Android instrumentation fixture exercises the corresponding large response through QuickJS's 64 MiB guest limit.

## Additional protocol corrections

- Amazon declares `songs` as its custom-search filter. The host now reads the provider's declared song/track filter rather than always sending `tracks`.
- Amazon and YouTube Music packages can expose a null `getDownloadUrl` stub and implement file preparation in `download`. The host now falls back to that prepared-file route when allowed.
- A prepared-download regression covers the bootstrap's injected progress callback and the following options argument.
- The already implemented segmented-transfer API is now admitted when a package declares `downloadSegments@1`; the absent declaration had prevented Tidal and SoundCloud runtime initialization.
- Sparse search rows do not establish matching duration. Audio adapters can request the provider's `getTrack` metadata before applying close-duration substitution checks; they do not silently relax recording identity to force a match.

## Is Amazon Dolby audio working upstream?

The upstream runtime was also asked to download the selected Amazon recording at each advertised tier:

| Tier | Result in the clean upstream profile |
| --- | --- |
| FLAC best available | `verification_required` |
| Dolby Digital Plus / E-AC-3 | `verification_required` |
| Dolby Atmos / AC-4 | `verification_required` |

The package's operator verification step blocked all three. This was not a successful audio download/decoder test and is not proof of an Amazon decoder problem. No verification challenge was bypassed. A verified provider session is still required to establish whether those audio routes work for the user.

Even a successfully resolved AC-4/Atmos source would require separate decoder and device-output validation. The ordinary Cosmotune desktop stereo PCM path does not establish immersive rendering.

## Other real audio proof from this investigation

The JioSaavn package decoded both test songs as AAC at 44.1 kHz stereo. The separately supplied Qobuz HTTP add-on decoded:

- “Blinding Lights”: FLAC, 24-bit / 44.1 kHz.
- “Tippulu Tappulu”: FLAC, 16-bit / 44.1 kHz.

These were native decoder-input measurements, not simply provider quality labels. Only bounded audio was read to prove decoding; no audio was played through a physical output device. The private Qobuz access address and returned stream addresses are excluded from the report and source.

## Reproduction

The opt-in `AmazonRuntimeComparisonLiveTest`, `ProviderResolutionLiveProbeTest` and `ProvidedAddonLiveProbeTest` are skipped during normal test runs. They require explicit environment opt-ins and isolated workspace inputs. Redacted evidence is under ignored `build/amazon-comparison/` and `build/provider-live-probe-2026-10-26/`; no tokens, response bodies or request headers belong in published evidence.

See [provider capabilities](PROVIDER_CAPABILITIES.md), [provider runtime limits](PROVIDER_PACKAGES.md) and [remaining work](PENDING_WORK.md).

The follow-up [operator investigation and fixes](PROVIDER_OPERATORS.md) identifies the shared gateway and corrects the native verification entry, automatic grant exchange, retry and desktop browser return. The clean-profile audio results above predate a completed human verification and must not be read as a verified-session playback result.
