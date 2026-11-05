# Firebase for Cosmotune accounts, sync and rooms

Project: `portfolio-c4394` (it also serves the static sites in `shutterisland`; this folder never touches Hosting).
Design: `docs/superpowers/specs/2026-11-05-cosmotune-accounts-sync-rooms-design.md` in the planning repository.

| File | What |
| :-- | :-- |
| `rules/database.rules.src.mjs` | Source of the Realtime Database rules (room state, queue, chat, voice setup messages) |
| `database.rules.json` | Generated from the source: `npm run build:rules` |
| `firestore.rules` | Cloud sync: a user reaches only `users/{their uid}/**` |
| `tests/rules.test.mjs` | Emulator tests for every allowed action and its rejection |
| (code) | `shared/src/jvmSharedMain/.../cloud` and `.../room` use these rules; see docs/MAINTAINING.md |

## One-time setup in the Firebase console (cannot be done from the command line)

1. Cloud Firestore: <https://console.firebase.google.com/project/portfolio-c4394/firestore> then Create database, Standard edition, location `asia-south1` (Mumbai). Production mode is fine; the rules in this folder replace it.
2. Realtime Database: <https://console.firebase.google.com/project/portfolio-c4394/database> then Create database, location **Singapore (asia-southeast1)**, locked mode. The location cannot be changed later.
3. Authentication: <https://console.firebase.google.com/project/portfolio-c4394/authentication> then Get started, enable **Anonymous** and **Google** (set the support email).
4. Project settings, Your apps: add a **Web app** (public configuration, safe to commit) and an **Android app** with the package name from `app/build.gradle.kts` plus the SHA-1 of the signing key (release key from the signing keystore, debug key for local builds). Google sign-in on Android fails without the SHA-1.
5. Credentials (Google Cloud console, same project): create an OAuth client of type **Desktop** for the desktop sign-in.

## Deploy the rules (after the two databases exist)

```bash
cd firebase
npx firebase deploy --only database,firestore --project portfolio-c4394
```

## Tests

```bash
cd firebase
npm install
npm test
```

`npm test` regenerates the database rules and starts the Firebase emulators (the first run downloads them).

## Google sign-in: what is registered and what is still yours to do

Done from the command line (project `portfolio-c4394`):

| What | Value |
| :-- | :-- |
| Web app | `cosmotune`, id `1:805536285560:web:a4019ae88e69d1a549be8d` |
| Android app (release) | `com.cosmotune.app`, id `1:805536285560:android:f0c6153ec7e1a8d349be8d`, SHA-1 of the release key and of the debug key |
| Android app (dev flavour) | `com.cosmotune.dev`, id `1:805536285560:android:c9928af15f84d07f49be8d`, SHA-1 of the debug key |
| Web client id (Android sign-in uses it as the server client id) | `805536285560-uqg9elhnmh2o3c7v07nj7pvmgedtfrba.apps.googleusercontent.com` |

The release SHA-1 registered is the one `./gradlew :app:signingReport` prints for the local `keystore.properties`. If the signed APKs
built by the release workflow use a different keystore, add that key's SHA-1 too:
`firebase apps:android:sha:create 1:805536285560:android:f0c6153ec7e1a8d349be8d <SHA-1> --project portfolio-c4394`.

Still done in the Google Cloud console (it has no command line for this), <https://console.cloud.google.com/apis/credentials?project=portfolio-c4394>:

1. OAuth consent screen: user type External, app name Cosmotune, support email, and **Publish app** (only the basic `openid email profile`
   scopes are used, so Google needs no review; while the app stays in Testing only listed test users can sign in).
2. Create credentials, OAuth client ID, application type **Desktop app**, name `Cosmotune desktop`. Copy the client ID and client secret
   (Google gives installed apps a secret; it is not confidential) into `local.properties`:
   `GOOGLE_DESKTOP_CLIENT_ID=...` and `GOOGLE_DESKTOP_CLIENT_SECRET=...`, and into the GitHub repository variables of the same names so release builds have them.
3. If desktop sign-in answers `INVALID_IDP_RESPONSE`, open Firebase console, Authentication, Sign-in method, Google, and add the desktop client id
   under the list of allowed client ids.

## Room layout, in short

A member reads the whole room through **one stream** at `rooms/{code}` (each stream counts as one of the free plan's 100 connections);
someone who only has the code can read `meta` and `members`. Voice setup messages live outside the room at `signals/{code}/{to}/{from}/{id}`
so that reading a room does not hand every member everyone's inbox. A seat number (0 to 7) is the capacity limit, because the rules
cannot count children. Writes to `state` and `queue` must carry the next sequence number; a write that lost a race is refused and retried.
