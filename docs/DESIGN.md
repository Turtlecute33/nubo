# Nubo design

This file is the single source of truth for Nubo. Code comments refer to its sections (`DESIGN §n`).
Protocol facts have IDs `F-nn` in [research/protocol.md](research/protocol.md).

## 1. Fixed decisions

- Nubo is an unofficial Android client for [Filen](https://filen.io) cloud storage.
- Name: Nubo (Esperanto for "cloud"), with a capital N in text (owner, 2026-11-19). The icon shows it in lower case.
  Identifiers stay `nubo`: package and app ID `app.nubo`, the Keystore alias, the user agent, the repository.
  Licence GPL-3.0.
- Release key: `~/.config/nubo/release.jks`, alias `nubo`, passwords in the Keychain items `nubo-android-keystore`
  and `nubo-android-key` (`scripts/make-release-key.sh`). The certificate stays the same for all releases.
- v1 scope (owner, 2026-11-19): login with two-factor code; browse in a list or a grid with a sort for
  each folder; search; upload; download; new folder; rename; move; trash and restore; favourites;
  recents; transfers; viewers for images, video, audio, text and PDF; app lock; settings.
  Extras: photo timeline and camera upload; public links and shares with Filen users; offline files;
  public Filen links open in the app.
- Notes (owner, 2026-11-20, after tester feedback): Filen notes (§12). They open from Home and Me; the
  four tabs stay.
- App lock: a screen lock, optional, off by default. It does not encrypt data (§7).
- Repository: public on GitHub, `Turtlecute33/nubo`. Releases there and on Zapstore.
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
- No two-way folder sync, no chat, no contacts screen, no file versions screen.
- Notes: no tags, no note history, no sharing of notes with other users, no edit of rich-text notes (§12).
- No server-side search index (F-68, F-91).
- No WebSocket events (F-18).
- No folder upload (single and multiple files only).
- No write access in folders that other users share with me (read and download only).
- No v3 file links: a v3 file key does not fit the link URL (F-77).

## 4. Architecture

- One `:app` module. One `ComponentActivity`. Compose only.
- The object graph is in `NuboApp` (`by lazy`), reached with `Context.app`. No DI framework.
- Packages: `crypto` (formats and key derivation), `api` (HTTP envelope, login), `net` (HttpEngine),
  `drive` (listings, writes, sharing, links, upload, download), `notes`,
  `offline`, `media` (thumbnails, chunk reader, open-with provider), `transfer`, `work` (camera job,
  notifications), `data` (vault, session, settings), `ui`.
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
| Session: API key, master keys or DEK, RSA private key, root folder, cache key | `noBackupFilesDir/session.bin` | AES-256-GCM with the Keystore key `nubo.vault.v1` (StrongBox first) |
| Settings | `filesDir/settings.json` | Not secret; no names |
| Offline files | `noBackupFilesDir/offline/<uuid>/<chunk>` | The chunks as the server sends them, still encrypted with the file key |
| Offline index (names, file keys) | `noBackupFilesDir/offline/index.bin` | `003` with the session cache key |
| Thumbnails | `cacheDir/thumbs/<hash>` | AES-256-GCM with the session cache key |
| Camera upload mark | `noBackupFilesDir/camera-mark.json` | Not secret: a MediaStore ID and a time |
| Logout mark | `noBackupFilesDir/wipe-pending` | Empty; the next start finishes a stopped wipe (review 74) |
| Notes and their keys | Memory only | Not on the disk; gone at logout and when the process ends |

- The cache key is 32 random bytes made at login. It lives only in the encrypted session.
- No plain file content goes to the disk. Viewers read chunks into memory. "Open with" and "Send a
  copy" give other apps a proxy file descriptor that decrypts on read (§7).
- Downloads go to `Download/Nubo` through MediaStore: no storage permission.
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
- Block screenshots (on by default): `FLAG_SECURE` and no recents screenshot. With the app lock on, recents
  shows no screenshot either (review 50).
- Copied links are sensitive clips. The app clears the clip when the user comes back to the app, or after
  1 minute in the app, if the clip is still its own (review 49).
- Tapjacking: the window drops touches that go through an overlay of another app.
- The keyboard does not learn from the password, code, search, link, name and note fields
  (`IME_FLAG_NO_PERSONALIZED_LEARNING`).
- Note HTML (rich text, checklists) comes from other users too. Nubo never renders it as HTML or loads a
  URL from it: it shows text only (N-93).
- Logs: no names, keys, links or server messages. `Item`, `Session`, `Links.Parsed` and the routes
  print only IDs. Release builds remove `Log.v/d/i`.
- Incoming shares: only content URIs of other apps (no `@` in the authority), at most 500, and only the
  text of a link. The app opens each shared file when the user confirms, while the read grant is alive
  (review 56).
- Server UUIDs must have the UUID form before they go into a file path (review 60).
- `android:memtagMode="sync"`, no WebView, no dynamic code loading, no native code.
- Integrity: GCM checks each chunk. A full download also checks the size and, when the uploader wrote
  one, the BLAKE3 hash of the whole file (F-55). A streamed read (video, PDF) cannot check the hash.
  The SHA-512 `hash` of the old TypeScript clients is not checked, like the Rust SDK: that SDK fed its
  hasher inside a parallel upload loop, so the value can be in another chunk order and a check could
  refuse good files.
- The drive-write lock of the official clients guards every write (F-57).

## 8. Network and privacy

- Hosts: `gateway.filen.io` (API), `ingest.filen.io` (upload), `egest.filen.io` (download). No
  fallback hosts, no CDN config, no WebSocket (F-12..F-18, F-91). Notes use the gateway only.
- HTTPS only, system CAs only, certificate transparency on Android 16 and later (Android 15 ignores the
  setting; review 73). No pinning: Filen publishes no pins (F-16).
- User agent `nubo`, the same on every device. No device ID, install ID or push token (F-92, F-93).
- Downloads carry no API key (F-30). Public links open with no account and no key.
- Referral (owner decision 2026-11-19): the login screen has a switch, on by default, "Use the referral of
  the Nubo developer". With it on, "Create one at filen.io" opens the developer's referral link
  (`filen.io/r/…`); with it off, `filen.io`. Filen gives 10 GB more to the new account and to the developer
  (at most three times for the developer), but not to accounts that it does not find eligible (for example,
  made through a VPN). The browser opens the link: the app does not connect to it. Login never sends a referral.
- Errors: the user sees short text from one place (`userText`); server text is never shown.
- Retries: only in `Api` (review 45). Network errors, 5xx and 429 get two more tries with a short wait; API
  errors, a too large answer and the login do not.
- What Filen still sees (F-90): the email, IP address and times; the folder tree shape; file sizes;
  unsalted name hashes on v1/v2 accounts; shares and links. The README tells this.

## 9. Camera upload

- A JobScheduler job with MediaStore content triggers for images and (optional) videos under
  `DCIM/`, on any network or only unmetered networks.
- It uploads media added after the time the user turned it on (or all, if the user chose that), in
  the order they were added, and stores a mark of the last one, so each item uploads once.
- No media location permission: Android removes the GPS position from the files.
- The job cannot be persisted (content triggers), so a boot receiver schedules it again after a reboot
  (review 37).
- Only a refusal of one item by the server counts as a try (3 tries, then the item gives way). A network
  error, a stop by the system (except the time limit) and an account error do not count. On an account or
  folder error (storage full, folder gone, session ended) the run stops and a notification tells the user
  (review 38, 40). A run stops after about 8 minutes and runs again at once (review 83).

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
  item decoding; sort order; settings file; link URLs and link passwords; note keys and formats
  against the vectors of the research (N-71); checklist HTML, previews and type conversions.
- Not tested by the developer: anything against the real Filen servers. Live tests are for the owner
  only ([LIVE-TESTS.md](LIVE-TESTS.md)).
- Not tested yet: v1 and v3 accounts with real data, camera upload on a real phone, the PDF and media
  viewers on a real phone. Notes against the real servers (the open VERIFY points of
  research/notes.md).

## 12. Notes

Facts: [research/notes.md](research/notes.md) (`N-nn`).

- Screens: a list (Notes, Favourites, Archive, Trash; pinned first, then the newest edit; a search over
  titles and previews on the phone) and an editor. Menu: pin, favourite, rename, change type, archive,
  trash, restore, delete for ever; "leave" for a note that another user shares.
- Types: Nubo writes text, Markdown and code as plain text, and checklists in the exact HTML of the
  official apps (N-52). Rich text (Quill HTML) shows as plain text, read only (N-51). An unknown type is
  read only (N-94). A type change converts the content: lines to rows and rows to lines (N-53).
- Keys: a new note gets a 32-character key (`002`, N-40, N-41). The owner copy uses the account key, the
  owner's participant copy RSA-OAEP (N-42, N-43); both go in under one "notes-write" lock (N-37, N-81).
  `textKey` picks the cipher from the key length: never `DataKey.parse` for a note key (N-82).
- Saves: 1.5 s after the last edit, when the app goes to the background, and when the editor closes; one
  at a time, in the app scope. Before each save Nubo reads the server content: if another device changed
  it, Nubo keeps both (the local text goes to a new note "… (conflict)") and shows the other text (N-69).
- Size: a content of 1 048 575 encrypted characters or more is refused before the write (N-55).

