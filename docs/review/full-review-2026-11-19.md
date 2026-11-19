# Full review 2026-11-19 (second review)

A second review of 0.1.0: code (protocol and security, transfers and jobs, UI) and a live test on a Pixel 9 Pro XL
with a Filen test account (free plan) and synthetic files. The IDs continue from
[code-review-2026-11-19.md](code-review-2026-11-19.md).

Source of each finding: **phone** (seen on the phone), **code** (read in the code), **UNSURE** (needs a test).

Format: `ID [severity] [status]` — problem; fix.

## Device results (estimates)

- Unit tests: 36 pass. Lint: no issues. Release APK: 3.06 MB.
- Cold start: 555–631 ms (4 runs). Fonte 520–553 ms and Varco 543–571 ms on the same phone, so this is the
  baseline of the phone, not of the app.
- Text viewer scroll (55 KB text): 0 % janky frames, 99th percentile 6 ms.
- Upload of 8 files (4.6 MB): about 1 s. Download of a 3 MiB + 1234 B file: the same SHA-256 as the source.
- Memory after the viewers: about 176 MB PSS.
- Correct on the phone: login session after reinstall, new folder, rename (the base name is selected), trash,
  undo, restore, search, grid, keep offline, image, text, audio, video and PDF viewers, thumbnails.

## Fixed in this review (not committed)

- R-34 [critical] [fixed] Every upload failed with `IllegalArgumentException` (phone): the chunk upload sent a
  body with no `Content-Type`, and HttpEngine refuses that at start. This broke the picker upload, the share to
  the app and camera upload. `Api.uploadChunk` now sends `application/octet-stream`. Verified on the phone with a
  0 B file, a 1 MiB file, a 3 MiB + 1234 B file, and an image, video, audio, text and PDF file.
- R-35 [high] [fixed] The media auto-play called `MediaPlayer.start()` (the receiver of `apply`), not
  `Player.start()` (phone). The media played with no audio focus, the controls showed 0:00 and "Play", the first
  tap did not pause, and a call did not pause the media. `Player.kt` now calls `this@Player.start()`. Verified on
  the phone: "Pause" shows, the time moves, the app asks for audio focus.
- R-36 [medium] [fixed] The old link form was URL-decoded two times (phone). The link
  `https://app.filen.io/#/d/<uuid>%23%25zz` crashed the app; any web page or app can send it. `Links.parse` now
  decodes each part one time. A unit test covers it; verified on the phone.

## High

- R-37 [high] [open] Camera upload stops after a reboot (code). A job with content triggers cannot be persisted,
  and there is no `BOOT_COMPLETED` receiver. New photos wait until the user opens the app. Fix: a boot receiver
  (`RECEIVE_BOOT_COMPLETED`) that calls `CameraJob.ensure`.
- R-38 [high] [open] Camera upload skips photos for ever on an account error (code). Each `ApiException`
  (storage full, folder deleted) counts as a try of the item; after 3 runs the item is marked done with no upload,
  then the next item. The user is not told. Before R-34, every camera upload failed with a non-IO exception that
  also counted, so a phone that had camera upload on has photos marked done with no upload. Fix: count tries only
  for item errors; stop the run and tell the user on account or folder errors.

## Medium

- R-39 [medium] [open] Uploads and transfers fail with no message (phone). The 4 failed uploads showed only in
  Me › Transfers; the pill went away. `POST_NOTIFICATIONS` is declared but never asked for. Fix: a snackbar
  "n uploads failed · Show", and ask for the permission at the first transfer.
- R-40 [medium] [open] Camera upload counts a try before `freeName`, and does not give it back on a cancel
  (Wi-Fi lost with "only Wi-Fi", "Check now", a setting change) (code). A large video is skipped after 3 stops.
  Fix: count just before `upload`; give the try back unless `stopReason` is a timeout.
- R-41 [medium] [open] `TransferJob.onStopJob` returns false for all stops except USER (code). The transfers go
  on with no job, and the system can freeze or kill the process. Fix: return true while transfers are active.
- R-42 [medium] [open] BLAKE3 and the chunk copy run on the main thread (code). `Content.write` and
  `Uploader.send` run on the caller's dispatcher, which is Main for transfers and for the image viewer. A large
  image or a fast transfer drops frames. Fix: `withContext(Dispatchers.Default)` around both bodies.
- R-43 [medium] [open] A cancelled "Keep offline" leaves its chunks on the disk (code): the cleanup
  `withContext(Dispatchers.IO)` throws at once in a cancelled coroutine. Fix: add `NonCancellable`.
- R-44 [medium] [open] "Keep offline" on a file that is offline deletes the good copy first; two adds of one file
  delete each other's chunks (code). Fix: download into a temporary folder and rename under the mutex; skip
  files that are offline or in progress.
- R-45 [medium] [open] `Http.send` sends each request again once on `isImmediatelyRetryable` (timeout, reset),
  also login and writes (code). The R-29 "no login retry" is not complete; a second login with the same 2FA code
  can fail, and a second restore stops the loop. Fix: retry in `Api` only.
- R-46 [medium] [open] The R-07 fix is inverted (code). `ImageDecoder.DecodeException` is an `IOException`, so a
  bad image downloads again each time it shows; a video that fails on the network gets no thumbnail until a
  restart. Fix: catch `DecodeException` first and mark it failed; do not mark network failures.
- R-47 [medium] [open] The search text is lost after the user opens a result and goes back (phone). Fix: keep
  the query in a per-entry ViewModel (memory only, so R-25 stays).
- R-48 [medium] [open] With no network and no cache, Home shows a spinner and Me shows "Reading…" for ever
  (code). Fix: show the error state with "Try again".
- R-49 [medium] [open] The link clip is not cleared in the usual flow (code): copy, go to another app, paste. In
  the background `primaryClipDescription` is null, so `SecretClip.clear` does nothing. The KDoc says the opposite.
  Fix: clear when the app comes back if the clip is still ours, and change the message and the KDoc.
- R-50 [medium] [open] With the app lock on and "Block screenshots" off, recents shows the last screen (code).
  Fix: `setRecentsScreenshotEnabled(!(blockScreenshots || appLock))`.
- R-51 [medium] [open] A viewer page loses its data when it goes off the screen (code). A→B→A downloads the file
  again; a rotation loads all again; a video starts again at 0:00. Fix: a small memory LRU per viewer session,
  `beyondViewportPageCount = 1`, and keep the video position in saved state.
- R-52 [medium] [open] The screen models are in `remember` (code): each Back, tab change and rotation reloads
  the listing; Photos reads the full tree (`/v3/dir/download`) each time it shows. Fix: per-entry ViewModels; read
  again on `Drive.changed`, a pull, or after a time.
- R-53 [medium] [open] The text viewer cuts blocks by lines only (code). A minified 2 MB JSON file becomes one
  `Text` and freezes the app. Fix: also cut a block after about 4k characters.
- R-54 [medium] [open] The image decode size is 2× the long side of the screen on the long side of the image
  (code). A 50 MP landscape photo decodes to about 107 MB (estimate). Fix: 2× the fitted size.
- R-55 [medium] [open] "Remove from my shared items" has no confirm and no undo; only the owner can undo it
  (code). Fix: a confirm dialog.
- R-56 [medium] [open, UNSURE] A queued upload opens its URI only when it starts (code). The read grant can end
  when the activity closes, so a long share queue can fail with `SecurityException`. Test: share 20 files, then
  remove the app from recents. Fix: open the descriptors when the user confirms.
- R-57 [medium] [open, UNSURE] On v3 accounts a folder link key is 64 hex characters; `Links.url` writes 128 hex,
  and `decodeKey` refuses it (code). Not tested: the free test account cannot make links. Fix: accept 128 hex
  that decodes to 64 hex.
- R-58 [medium] [open] Disk-cached thumbnails wait in the same 2-permit gate as new downloads of up to 25 MB
  (code). Fix: read memory and disk outside the gate.
- R-59 [medium] [open] `Thumbs.peek` runs SHA-1 and 20 `String.format` calls per row in composition, also for
  folders (code). Fix: check `supports()` first; one hex call.

## Low

Protocol and security:

- R-60 [low] [open] The server's `uuid` goes into file paths unchecked (`offline/<uuid>`); `..` would delete
  `noBackupFilesDir` (code; a bad server only). Fix: check the UUID format in `Decode`.
- R-61 [low] [open] Android `optString` gives "null" for a JSON null (mime, error code, region, email). The JVM
  tests use another org.json and cannot show it. Fix: one helper that maps `JSONObject.NULL` to "".
- R-62 [low] [open] `foreignContent` accepts `content://0@app.filenza.files/…` (code). Fix: refuse an `@` in the
  authority.
- R-63 [low] [open] The camera folder name is plain text in `settings.json`; DESIGN §6 says no names (code). Fix:
  keep only the UUID.
- R-64 [low] [open] A link with a password shows "The link password is wrong" also for 429 and 5xx (code). Fix:
  map only a real refusal.
- R-65 [low] [open] "Answer too large" (32 MiB) counts as transient and downloads 2 more times; a very large tree
  fails (code). Fix: no retry; a larger limit for tree calls.
- R-66 [low] [open] `Links.find` keeps a full stop or a bracket at the end of a link (code). Fix: trim them.
- R-67 [low] [open] `FileProvider.clear` deletes the tokens but not the open readers; another app can go on
  reading after logout (code). Fix: close the open readers.
- R-68 [low] [open] Viewer sessions (names, file keys) stay in memory after logout (code). Fix: clear them in
  `wipe()`.
- R-69 [low] [open] `NameDialog` (new folder, rename) has no `PrivateTextInput`; the text viewer copies plain text
  (code). Fix: wrap the field; copy through `SecretClip` or remove the selection.
- R-70 [low] [open] `userText` shows the raw message of any short `IOException`, which can hold a path (code).
  Fix: show only the app's own messages.
- R-71 [low] [open, UNSURE] `SESSION_CODES` has codes that F-83 does not list; a permission error could log out
  and delete offline files. Fix: keep only the known codes.
- R-72 [low] [open, UNSURE] The 60 s idle timeout also counts the wait for the answer headers; long server calls
  can time out. Test with a large account.
- R-73 [low] [open, UNSURE] It is not sure that HttpEngine applies `certificateTransparency` of the network
  security config. Check the platform CT path.

Transfers, jobs, storage:

- R-74 [low] [open] Logout clears the session before the wipe; a process death in a long wipe leaves offline
  files and camera settings (code). Fix: a "wipe pending" mark.
- R-75 [low] [open] A MediaStore row can stay pending after a process death, or after a cancel during `insert`
  (code). Fix: insert in `NonCancellable`; delete our pending rows at start.
- R-76 [low] [open] `Drive.changed` drops events after 16 (code). Fix: a larger buffer or one "all" event.
- R-77 [low] [open] `cancelAll` does not stop the loop that reads picked URIs; uploads can start after logout
  (code). Fix: track and cancel that job.
- R-78 [low] [open] `TransferJob.ensure` races with `jobFinished` and makes one binder call per file (code).
- R-79 [low] [open] The `/v3/file/exists` check in `Uploader` catches `CancellationException`; a cancel shows as
  a failure (code).
- R-80 [low] [open] The download loops trust the server's `chunks` count; the size check comes at the end
  (code). Fix: refuse `chunks != ceil(size / 1 MiB)`.
- R-81 [low] [open] A public-link visitor with no session gets no thumbnails (no cache key) (code). Fix: memory
  only.
- R-82 [low] [open] `Prefs.write` can throw in the app scope and crash the app when the storage is full (code).
- R-83 [low] [open, UNSURE] A camera run takes up to 200 items but has about 10 min; time-outs grow the back-off
  (code). Fix: stop after about 8 min and run again.

UI and UX:

- R-84 [low] [open] A public link on a free account shows "Filen refused the request (subscription_needed)"
  and a useless "Try again" (phone). Fix: map `subscription_needed` to "Public links need a paid Filen plan".
- R-85 [low] [open] The "Undo" snackbar has the Short duration (4 s); one undo on the phone expired before the
  tap. Snackbars also go under sheets and under the navigation bar in the viewer (phone, code). Fix:
  `SnackbarDuration.Long` with an action; show sheet feedback in the sheet.
- R-86 [low] [open] The FAB is about 35 dp above the tab bar, not 16 dp (phone). Fix: no bottom inset in the
  screen Scaffold's `contentWindowInsets`.
- R-87 [low] [open] The avatar button on Home has no label (phone). Fix: `contentDescription = "Account"`.
- R-88 [low] [open] Search results show no folder (phone). Fix: the parent path as the subtitle.
- R-89 [low] [open] A double tap opens two viewers or two choosers (code). Fix: ignore `open()` while a viewer
  opens.
- R-90 [low] [open] Back with the FAB menu open closes the folder (code). Fix: `BackHandler(fabOpen)`.
- R-91 [low] [open] Empty and error states cannot scroll, so pull to refresh does nothing there (code).
- R-92 [low] [open] Search has no IME padding; the last results are under the keyboard (code).
- R-93 [low] [open] After the 2FA field shows, there is no way back to another account (code).
- R-94 [low] [open] The viewer top padding is a fixed 72 dp; the quick tile labels are one line. Both cut text
  at a large font size (code).
- R-95 [low] [open] The selection bar counts UUIDs that a refresh removed; the selection is in saved state with
  no limit (code).
- R-96 [low] [open] A cancelled refresh clears `refreshing` while the new load runs (code).
- R-97 [low] [open] The folder list state in link folders and the folder picker is not keyed by folder; Up keeps
  the child's scroll offset (code).
- R-98 [low] [open] `PublicLinkScreen` keys its state by link UUID only; a fixed key keeps the old error (code).
- R-99 [low] [open] Camera upload `enable()` runs in the screen scope; Back stops it with no message (code).
- R-100 [low] [open] `AppRoot` `AnimatedContent` reads the outer state, so the login and logout fades show the
  wrong content (code).
- R-101 [low] [open] Sort and search run on the main thread; search compiles a Regex per name; `Format.date`
  makes a formatter per call (code).
- R-102 [low] [open] The tab `Crossfade` and the tile scale do not use `MaterialTheme.motionScheme` (code).
- R-103 [low] [open] The splash also waits for `offline.load()` (code).
