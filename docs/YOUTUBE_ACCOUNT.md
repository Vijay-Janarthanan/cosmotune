# One YouTube account for Music and Video

Cosmotune signs in to Google once. YouTube Music (Music mode) and YouTube (Video mode) both use that one sign-in. Nothing in
either mode has its own credential.

## The session

`shared/src/jvmSharedMain/kotlin/com/cosmotune/youtube/account/` holds the session code:

| Piece | What it does |
|---|---|
| `YouTubeCookieJar` | Holds the cookies as a jar rather than a header string. It merges the music.youtube.com and www.youtube.com captures and applies `Set-Cookie` (it replaces, adds and deletes, and keeps only cookies for the jar's own domain). `sameLogin` matches two jars by their signing secret. |
| `SapisidAuth` | Builds the per-origin `Authorization` header: `SAPISIDHASH`, plus `SAPISID1PHASH` and `SAPISID3PHASH` when the jar has `__Secure-1PAPISID` and `__Secure-3PAPISID`. Each hash is `<ts>_<sha1(ts + " " + secret + " " + origin)>`. |
| `YouTubeSession` (`shared`) | Owns the jar, the one visitor id, the accounts.google.com jar, the `SignedOut / SignedIn(account, profile) / Expired(reason)` state, persistence through a platform `CredentialVault`, and the silent refresh. |
| `YouTubePageConfig` | Reads `LOGGED_IN`, `INNERTUBE_CLIENT_VERSION`, `VISITOR_DATA`, `DATASYNC_ID`, `SESSION_INDEX` and `DELEGATED_SESSION_ID` from a page. |
| `SessionTransport` / `KtorSessionTransport` | Makes the two calls the refresh needs (the rotation and a page check). Tests swap in a fake. |
| `SessionRecovery` | The platform's last way back before an expiry. |

`Innertube` (Music's WEB_REMIX client and Video's WEB client) delegates to the session. `Innertube.cookie` still works as before
(get and set), so existing callers did not change. Both clients:

- sign with the session's jar and per-origin header;
- pass every `Set-Cookie` from a signed response to the jar, guarded so it only applies to the same login. The vault writes a
  changed jar at most every 30 s;
- report what the server says. A 401 or 403 counts as a strike, and so does an answer that says it is logged out
  (`mainAppWebResponseContext.loggedOut`, or `logged_in=0` in the tracking parameters). A page that says `LOGGED_IN: false` starts
  a check.

The WEB client version is read live from the www.youtube.com page: at the first web call, after a sign-in, and every 6 h after
that. Until then, or when the page cannot be read, the pinned `2.20251001.01.00` is used. The account scope (`DATASYNC_ID`,
`SESSION_INDEX`, `DELEGATED_SESSION_ID`, the client version, the session-bound visitor id) is read again once a day, and after a
jar is recovered. The stream session (the PoToken binding and the resolver verdicts) is reset only when that re-read names a
different identity. A rotation does not reset it.

## Silent refresh: what is refreshed, when, and the fallback order

The goal is that nobody is asked to sign in again while Google still holds their session.

1. **Rotation.** The short-lived `__Secure-1PSIDTS` / `__Secure-3PSIDTS` cookies are rotated the way the site's own page does it.
   The session POSTs `[000,"-0000000000000000000"]` (JSON, `Origin: https://www.youtube.com`) to
   `https://accounts.youtube.com/RotateCookies` with the YouTube jar, and to `https://accounts.google.com/RotateCookies` with the
   accounts jar when one was captured. The `Set-Cookie` values in each answer go into their jar and then to the vault. This
   happens:
   - before the first signed request after app start, resume or sign-in (that request waits up to 8 s);
   - whenever the last rotation is more than 10 min old (in the background; the request does not wait);
   - every 10 min while the app is in the foreground (phone) or running (desktop);
   - every 6 h in the background on Android, through WorkManager, when there is a network and the battery is not low;
   - after a refused request (401/403). Refreshes triggered this way run at most once a minute, and the refused request is
     retried once if the jar changed.
2. **Check.** If a rotation was refused, or the server keeps refusing (3 strikes in a row, or a page that says it is signed
   out), the session fetches www.youtube.com with the jar and reads `LOGGED_IN`. `true` clears any expiry. A page that cannot be
   read proves nothing either way.
3. **Platform copy.** If the check says the session is signed out, the platform is asked for a fresher copy of the **same
   login**. Android reads the WebView `CookieManager` for www.youtube.com and music.youtube.com (merged) and for
   accounts.google.com. The desktop reads the browser the account was imported from, then the other browser profiles. A jar
   from a different login is never used.
4. **Expired.** Only when all of the above fails does the state become `Expired`. Music (phone and desktop) and Video
   (Library, History, Watch later, Liked, Saved playlists, Subscriptions) then show a "Sign in again" banner. Local data keeps
   showing.

Refreshes run one at a time: a caller that arrives while one is running waits for it and takes its result. A refresh that
finishes after a sign-out or an account switch is discarded. Cookie values are never logged.

## Migration (nobody is signed out)

- **Android.** The encrypted `AuthStore` keeps its keys (`cookie`, `google_account_sessions_v2`, the active ids, and the legacy
  `channel_*`). A v2 session gains an optional `googleCookie`; sessions saved before this change have none and keep working on
  their YouTube cookie alone. The legacy single cookie is still migrated into the registry on first read, as before. A rotated
  jar is written over the stored session of the same login (`AuthStore.rotateCookie`), and over the legacy cookie when it is the
  same login. `selectProfile` reads the stored copy first, so a stale in-memory list cannot write an old cookie back. The music
  service now restores the active session and channel (`AndroidYouTubeSession.publishActive`) instead of the legacy cookie, which
  a profile switch did not keep current.
- **Desktop.** The `google_accounts_v1` preferences and the keyring entries are unchanged. A rotated jar is written to the
  active account's keyring entry when it is the same login (`DesktopAccounts.rotateCookie`).
- When the platform installs its stored copy of a login whose jar has rotated since, the session keeps the rotated jar instead
  of going back.

## Signing in

- The phone's login (`YtMusicLoginScreen`) now captures the www.youtube.com jar merged with the music.youtube.com one (Music's
  values win where both set a name, so Music signs exactly as before), and keeps the accounts.google.com jar next to it.
- `YouTubeSignInOverlay` is the one sign-in screen. Music opens it from its profile button and Library. Video opens it on top of
  Video (`SignInRequest` in `PhoneModeHost`), and no longer switches the app to Music to do so.
- The account sheet is `sharedUi/.../ui/account/AccountSheet.kt`, built on the UI kit. It shows the avatar, the name and
  "Signed in to YouTube & YouTube Music" (Video says "Signed in for Video and Music", because Video's texts never name the site).
  It also has the channel and brand switcher, Add account, Sign out, and Sign in / Sign in again. Music's account selector uses it
  when the new look is on, and so does Video's `AccountSheet`. `AccountChip` and `AccountExpiredBanner` sit beside it.

## Video's account lists

`com.cosmotune.video.account.AccountLists` merges the account's lists with the local ones: account entries first, each video once
(by id).

- **Subscriptions** merges `FEsubscriptions` with the local feed engine, newest first. A video in both keeps the local copy and
  its exact upload time. An account-only video's time is estimated from text like "3 hours ago" (English only; other languages
  go to "Older"). With nothing followed on this device, the account's feed still shows.
- **History** shows `FEhistory` under "On your account", then the local day groups minus those videos.
- **Watch later / Liked** show `VLWL` / `VLLL` first, then the local-only videos. Removing a video that is on the account also
  removes it there (from the playlist, or by clearing the rating). Reordering applies to the local list.
- **Saved playlists** shows the account's playlists first, then the saved ones.

When signed out, or when the account cannot be read, every page shows its local list exactly as before.

## Signed video playback

`com.cosmotune.video.signed.SignedVideoSource` wraps the NewPipe adapter, and both graphs build Video on it. The adapter did not change:
`VideoDownloader` still has no cookie jar, so the anonymous path carries no account. Signed in (state `SignedIn`, not `Expired`), `details`
and `streams` go through `SignedPlayerResolver` first, so age-restricted and members-only videos and the account's private and unlisted
ones play. Signed out, every playback call is NewPipe's, as before.

The clients, in the order they are asked (`SignedPlayerClient`; the notes in that file say why):

| Client | Credential | Links | Note |
|---|---|---|---|
| `TVHTML5` as a Samsung TV | the jar | ciphered | first since the plain older TV app began answering "The page needs to be reloaded" (2026-10) |
| `WEB_EMBEDDED_PLAYER` | the jar | ciphered | says no for a video with embedding turned off |
| `TVHTML5`, the older app | the jar | ciphered | no PoToken |
| `WEB`, the live version | the jar + PoTokens | ciphered | its links stop after 1 MB without the streaming token |
| anonymous NewPipe | none | | when every client above failed |
| `IOS`, then `ANDROID_VR` | none | direct | only when NewPipe failed too. `ANDROID_VR` is last because googlevideo can refuse its links past the first megabyte |

A signed request carries the jar, `Authorization` over `https://www.youtube.com` (`SAPISIDHASH` + 1P + 3P), `X-Goog-AuthUser`,
`X-Goog-PageId`, `X-Goog-Visitor-Id` and `onBehalfOfUser`, read the way `Innertube.postWeb` reads them (`Innertube.signedScope`). It uses the
session like any other signed call: `Set-Cookie` goes to the jar, a 401/403 is a strike and starts the silent refresh, the client is asked
once more if the jar rotated, and `loggedOut` is reported. Stream sets carry the client's `User-Agent` (and `Origin`/`Referer` for a browser
client), never a cookie.

Ciphered links are solved with the player script, `YouTubeCipherService` and solvers Music's stream extraction uses
(`InnerTubeXResolver`). A format that cannot be solved is left out, and a client left with no formats counts as having refused. So do
formats with DRM or the OTF delivery.

**The PoTokens.** The WEB client sends a player token bound to the video and puts a streaming token on its links as `pot=`. The streaming
token is bound to the account's identity (`dataSyncId`, or the visitor id without one) and comes from the same BotGuard page as Music's
(`PoTokenGenerator.getSessionBoundTokens`; `PoTokenWebView` on Android, `DesktopPoTokenWebView` on the desktop). It is minted again only
when that identity changes. A cookie rotation changes no identity and restarts nothing. The one other re-mint is when the page's integrity
token runs out, because the old token would stop working. Where the platform has no browser, the WEB client is skipped.

**A refused set.** A set lasts for hours, so one asked for again within 10 minutes was refused by googlevideo. The client that minted it sits
out for 10 minutes and the next one answers; when they have all sat out, NewPipe answers. `SignedVideoSource` drops these verdicts and its
10-minute details cache when the account or the chosen channel changes.

The watch page's metadata comes from the same `player` answer. Related videos, chapters, the like count and the channel's picture come from
the signed `next` answer, and a page without it still plays.

## Comments

`InnerTubeComments` reads the `next` endpoint for everyone: the watch page's `comment-item-section` token, then one request per page. The
sort menu's second token is Newest, so Top and Newest are offered. A comment is a `commentViewModel` whose text, author and counts sit in
`frameworkUpdates` entities. A section with no token is "Comments are turned off". A watch page with no section, or threads none of which
can be read, falls back to NewPipe. Page tokens start with `it:`, so each page goes back to the source that gave it.

Signed in, the same answers also hold what the writes need, and the account sends them through `Innertube.postWeb`:

| Action | Endpoint | Body |
|---|---|---|
| Post | `comment/create_comment` | `commentText`, `createCommentParams` |
| Reply | `comment/create_comment_reply` | `commentText`, `createReplyParams` |
| Like, dislike, undo | `comment/perform_comment_action` | `actions: [<the comment's own value>]` |

The watch page's comments tile opens `CommentsSheet` on the phone, built on `KitSheet` with a `KitMediaRow` as the composer's prompt. The
desktop's side column and Shorts keep `CommentsPanel`; all three share one body. Comment rows stay `CommentItem`, because a comment needs
more than `KitMediaRow`'s one line each of title and subtitle (six lines, "Read more", the pinned and creator marks). `CommentInteractions`
holds the writes: a rating shows at once and goes back if refused. Signed out or expired, posting, liking and replying open the shared
account sheet, and signing in reads the first page again.

## Private playlists

Signed in, `SignedVideoSource.playlist` browses `VL<id>` with the session and reads the title, description and owner from the answer's
`metadata` and `header`, because a private playlist has no public page. If that fails, or when signed out, the anonymous page answers.

## Follow-ups (not in this pass)

- Trying all of the above against a live account and on devices. Signed playback, comment writes and private playlists are covered by unit
  tests over fake HTTP, with answers written from the documented shapes. The client versions come from the references and will age.
- Re-checking the rotation endpoints against live traffic. `accounts.youtube.com/RotateCookies` is the endpoint YouTube's own pages
  call for the `.youtube.com` cookies, but it could not be verified from here. A failed rotation only means the next refresh tries
  again.
- The desktop account selector still uses its own glass panel. It shows the shared banner, but not the shared sheet.
