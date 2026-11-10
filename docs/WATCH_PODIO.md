# Podio on Wear OS

## Listen on this watch

Open Podio and choose **Listen on this watch**. With an internet connection, the watch can browse or search Radio Browser and public podcast directories, open a show's publisher feed, and stream secure station and episode URLs without a phone or account. This mode uses its own media session for play, pause, stop, and episode seeking. The media notification opens its controls. Paired-phone favourites, follows, queue and playback stay in the paired mode below.

## Paired-phone mode

The watch now browses the paired phone's saved radio stations, popular Radio Browser stations, followed podcasts and their episodes. It can play/resume a selection or add it to Up Next. Existing Now Playing controls provide play/pause, previous/next, queue selection, volume and finite-episode seeking. The Podio entry is available in More, on the empty-player screen and as saved listings when the phone is disconnected.

Paired playback uses the phone's existing Media3 foreground playback service and queue. Output can remain on the phone or use the existing watch-speaker route: phone-owned resolution/data-source chain, Wearable streaming channel, and the watch's existing foreground player. Live radio is not treated as a finite seekable episode.

## Protocol and bounds

- `WatchPodioWire` is compiled into both apps. Versioned requests/replies carry catalogue identities and text; they do not authorize an arbitrary watch-supplied feed/media URL. Selection must resolve to a recently issued item in the phone's cache. Feed browsing is restricted to shows followed in the phone library.
- Pages contain up to 20 rows and 48 KB; requests/actions are limited to 2 KB. Views are capped at 300 rows. The phone keeps eight catalogue entries for ten minutes, strips episode descriptions from cached feed pages and fetches at most three additional publisher pages per browse request. A browse operation has a 20-second deadline.
- The watch keeps eight text-only pages in local app storage, plus the Data Layer's last reply. Saved pages remain readable offline; playback still requires the reachable phone and a current phone catalogue. Refresh goes back to the first page and explicitly requests new data. Failed refreshes preserve readable saved rows.
- Request identifiers reject late replies. Action acknowledgements distinguish failure from actual phone acceptance. Retried messages share a bounded phone action cache to avoid appending a queue item twice.

## Device requirements and verification

Phone and watch must run compatible builds with the same application id/flavour and signing key. Google Play services Wearable pairing and a reachable phone are required. Install both updated apps; an older phone does not understand the new catalogue commands. Watch output uses the existing foreground-service/notification permissions and the watch's supported audio codecs. Public RSS/YouTube catalogue availability and radio streams remain external-service limits.

`WatchPodioTest` covers wire bounds/round trips, radio paging/cache expiry, rejection of invented URL selections and unfollowed feed requests, phone-owned episode resume state and queue-action retry deduplication. Compilation, these unit checks, and the existing Wear controls/stream tests do not substitute for a physical paired-phone/watch walkthrough. No successful hardware playback test is claimed by this source update.
