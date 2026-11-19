# Filenza design

This file is the single source of truth for Filenza. Code comments refer to its sections (`DESIGN §n`).
Protocol facts have IDs `F-nn` in [research/protocol.md](research/protocol.md).

## 1. Fixed decisions

- Filenza is an unofficial Android client for [Filen](https://filen.io) cloud storage.
- Name: Filenza. Package `app.filenza`. Licence GPL-3.0.
- v1 scope (owner, 2026-11-19): login with two-factor code; browse in a list or a grid with a sort for
  each folder; search; upload; download; new folder; rename; move; trash and restore; favourites;
  recents; transfers; viewers for images, video, audio, text and PDF; app lock; settings.
  Extras: photo timeline and camera upload; public links and shares with Filen users; offline files;
  public Filen links open in the app.
- App lock: a screen lock, optional, off by default. It does not encrypt data (§7).
- Repository: local git only for now.
- Protocol: a clean-room Kotlin version. The code reference is the MIT Go SDK (`filen-sdk-go`).
  The AGPL SDKs (`filen-rs`, `filen-sdk-ts`) are read for facts only. No code is copied from them.
  The unit tests use the known-answer values of `filen-rs` as data (F-11).

## 2. Goals in order

1. Security and privacy: the keys and the plain data stay on the phone and in memory where possible.
2. Correct data: the app never writes metadata that the official apps cannot read.
3. Minimal: few features that work well, a small APK, one third-party library.
4. Fast: lists show from memory at once, transfers run in parallel, no work on the main thread.
5. A clean Material 3 Expressive UI in the style of MegaGlass.

## 3. Non-goals

- No account creation, no password change, no 2FA setup, no plan or invoice screens.
- No two-way folder sync, no notes, no chat, no contacts screen, no file versions screen.
- No server-side search index (F-68, F-91).
- No WebSocket events (F-18).
- No folder upload (single and multiple files only).
- No write access in folders that other users share with me (read and download only).
- No v3 file links: a v3 file key does not fit the link URL (F-77).

## 4. Architecture

- One `:app` module. One `ComponentActivity`. Compose only.
- The object graph is in `FilenzaApp` (`by lazy`), reached with `Context.app`. No DI framework.
- Packages: `crypto` (formats and key derivation), `api` (HTTP envelope, login), `net` (HttpEngine),
  `drive` (listings, writes, sharing, links, upload, download), `offline`, `media` (thumbnails,
  chunk reader, open-with provider), `transfer`, `work` (camera job, notifications), `data` (vault,
  session, settings), `ui`.
- Navigation: four tabs (Home, Drive, Photos, Me), each with its own back stack in `Navigator`
  (a ViewModel). Each tab keeps its saved UI state while another tab shows. Routes keep personal
  data out of `toString()`, because it is the key of the saved state. After process death the app
  opens on Home.
- State: `StateFlow` and Compose state. Listings stay in a small memory cache in `Drive`; screens
  show the cached list first, then the server's list. Each write tells the screens which listings
  changed (`Drive.changed`).

## 5. Dependencies

| Library | Reason |
|---|---|
| Kotlin coroutines | Structured concurrency for network and crypto work. |
| Compose BOM, foundation, material3 | The UI. material3 is pinned to its newest version for Expressive components. |
| activity-compose, lifecycle-runtime-compose | Compose in the activity; lifecycle-aware state. |
| navigation3 runtime and ui | The back stacks and the predictive back animation. |
| profileinstaller | Faster first start on sideloaded installs. |
| Bouncy Castle `bcprov-jdk18on` | Argon2id (auth v3 login and v3 link passwords) and BLAKE3 (file hashes). The Android platform has neither. Only the light-weight API is used, no JCA provider; R8 keeps only these classes. |

Everything else is platform API: `HttpEngine` (Cronet in the system), `javax.crypto` (AES-GCM,
PBKDF2 by hand over HMAC-SHA512, RSA-OAEP, HMAC), `ImageDecoder`, `MediaPlayer`,
`MediaMetadataRetriever`, `PdfRenderer`, `StorageManager.openProxyFileDescriptor`, `JobScheduler`,
`BiometricPrompt`, `org.json`, `AtomicFile`.

## 6. Storage

| Data | Where | Protection |
|---|---|---|
| Session: API key, master keys or DEK, RSA private key, root folder, cache key | `noBackupFilesDir/session.bin` | AES-256-GCM with the Keystore key `filenza.vault.v1` (StrongBox first) |
| Settings | `filesDir/settings.json` | Not secret; no names |
| Offline files | `noBackupFilesDir/offline/<uuid>/<chunk>` | The chunks as the server sends them, still encrypted with the file key |
| Offline index (names, file keys) | `noBackupFilesDir/offline/index.bin` | `003` with the session cache key |
| Thumbnails | `cacheDir/thumbs/<hash>` | AES-256-GCM with the session cache key |
| Camera upload mark | `noBackupFilesDir/camera-mark.json` | Not secret: a MediaStore ID and a time |

- The cache key is 32 random bytes made at login. It lives only in the encrypted session.
- No plain file content goes to the disk. Viewers read chunks into memory. "Open with" and "Send a
  copy" give other apps a proxy file descriptor that decrypts on read (§7).
- Downloads go to `Download/Filenza` through MediaStore: no storage permission.
- Logout deletes the session, the offline files, the thumbnails, the cache, the camera mark and the
  queued transfers.
- Backups and device transfers exclude all data (`data_extraction_rules.xml`, `allowBackup=false`).

## 7. Security

- Login: the password goes through PBKDF2-SHA512 (v2), Argon2id (v3) or the old chained hashes (v1)
  on the phone. The server gets only the derived password (F-21, F-22). The password is not stored.
- The Keystore key has no user-authentication binding: camera upload and transfers need the session
  while the screen is locked.
- App lock (optional): `BiometricPrompt` with `BIOMETRIC_STRONG or DEVICE_CREDENTIAL`, at each start
  and each time the app goes to the background. It is a screen lock only.
- Block screenshots (on by default): `FLAG_SECURE` and no recents screenshot.
- Tapjacking: the window drops touches that go through an overlay of another app.
- The keyboard does not learn from the password, code, search and link fields
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- Logs: no names, keys, links or server messages. `Item`, `Session`, `Links.Parsed` and the routes
  print only IDs. Release builds remove `Log.v/d/i`.
- Incoming shares: only content URIs of other apps, at most 500, and only the text of a link.
- `android:memtagMode="sync"`, no WebView, no dynamic code loading, no native code.
- Integrity: GCM checks each chunk. A full download also checks the size and, when the uploader wrote
  one, the BLAKE3 hash of the whole file (F-55). A streamed read (video, PDF) cannot check the hash.
  The SHA-512 `hash` of the old TypeScript clients is not checked, like the Rust SDK: that SDK fed its
  hasher inside a parallel upload loop, so the value can be in another chunk order and a check could
  refuse good files.
- The drive-write lock of the official clients guards every write (F-57).

## 8. Network and privacy

- Hosts: `gateway.filen.io` (API), `ingest.filen.io` (upload), `egest.filen.io` (download). No
  fallback hosts, no CDN config, no WebSocket (F-12..F-18, F-91).
- HTTPS only, system CAs only, certificate transparency. No pinning: Filen publishes no pins (F-16).
- User agent `Filenza`, the same on every device. No device ID, install ID or push token (F-92, F-93).
- Downloads carry no API key (F-30). Public links open with no account and no key.
- Errors: the user sees short text from one place (`userText`); server text is never shown.
- Retries: network errors, 5xx and 429 get two more tries with a short wait; API errors do not.
- What Filen still sees (F-90): the email, IP address and times; the folder tree shape; file sizes;
  unsalted name hashes on v1/v2 accounts; shares and links. The README tells this.

## 9. Camera upload

- A JobScheduler job with MediaStore content triggers for images and (optional) videos under
  `DCIM/`, on any network or only unmetered networks.
- It uploads media added after the time the user turned it on (or all, if the user chose that), in
  the order they were added, and stores a mark of the last one, so each item uploads once.
- No media location permission: Android removes the GPS position from the files.

## 10. Protocol notes

- Write formats follow the account version (F-37): v1/v2 accounts write `002` metadata and 32-character
  file keys; v3 accounts write `003` and hex keys. Per-file name, size and MIME fields use the file key
  (F-43).
- Each create, move, restore and rename updates the shares and links of the folder (F-53).
- Same name on upload: the server replaces the old file and keeps it as a version when the account
  has versioning on (F-52).

## 11. Tests

- Unit tests (JVM, no Robolectric, no mocks): crypto against the `filen-rs` known-answer values;
  the v2 login with 2FA against a scripted server; download order, size and hash checks; name rules;
  item decoding; sort order; settings file; link URLs and link passwords.
- Not tested by the developer: anything against the real Filen servers. Live tests are for the owner
  only ([LIVE-TESTS.md](LIVE-TESTS.md)).
- Not tested yet: v1 and v3 accounts with real data, camera upload on a real phone, the PDF and media
  viewers on a real phone.
