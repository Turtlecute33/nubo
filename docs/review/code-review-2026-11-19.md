# Code review 2026-11-19

An independent review of the first version (protocol, security, lifecycle, UI logic). 33 findings: 4 high,
11 medium, 18 low, none critical. The protocol request bodies, the key of each field, version handling,
upload/done, the link visitor flow, name hashes and timestamps were found correct.

Format: `ID [severity] [status]` — problem; fix.

- R-01 [high] [fixed] Leaving a media page while it buffers blocked the main thread (ANR). ChunkReader.close
  takes no lock; MediaPlayer.release runs on its own thread.
- R-02 [high] [fixed] Dialogs and sheets stayed usable over the app lock. The app is not composed while
  locked; its saved state comes back after the unlock.
- R-03 [high] [fixed] At a cold start by the camera job, the app rescheduled the job and stopped it.
  `CameraJob.ensure` schedules only when no job waits.
- R-04 [high] [fixed] The offline index lost the item source, so offline copies of shared or link files
  showed owner actions. The source is in the index.
- R-05 [medium] [fixed] Logout cleanup could stop halfway. It runs in the app scope.
- R-06 [medium] [fixed] Writes stopped when the user left the screen. They run in the app scope.
- R-07 [medium] [fixed] One failed or cancelled thumbnail never loaded again. Only decode errors count.
- R-08 [medium] [fixed] A very large video could block camera upload. After 3 tries (job time limit or
  server refusal; not network loss) the item gives way.
- R-09 [medium] [fixed] Camera upload could replace a cloud file with the same name. Same name and size:
  skipped; else a free name "name (n).ext".
- R-10 [medium] [fixed] The "Open a Filen link" field let the keyboard learn the key. PrivateTextInput.
- R-11 [medium] [fixed] Download was offered for shared and link folders. Only for own folders.
- R-12 [medium] [fixed] Rename from "Shared by me" failed (no parent). The parent is read first.
- R-13 [medium] [fixed] Large images used twice their size in memory. One array; thumbnail limit 25 MB,
  2 at a time.
- R-14 [medium] [fixed] Camera upload could skip media that was pending at query time. A 24 h window of done
  IDs replaces the single mark.
- R-15 [medium] [fixed] Overlays of other apps over dialogs. `setHideOverlayWindows(true)`.
- R-16 [low] [fixed] Rename wrote `"creation":0`. Left out when unknown.
- R-17 [low] [fixed] A failed share/link update after a good write showed as a failure. A warning only;
  the lists refresh first.
- R-18 [low] [fixed] A folder share could stop halfway on a long name. All sizes are checked first.
- R-19 [low] [fixed] A late lock release could free the next write's lock. The release is synchronous.
- R-20 [low] [fixed] The HTTP engine started on the main thread. It starts at the first request, and the
  app loads its state off the main thread.
- R-21 [low] [fixed] The app lock could open at a cold start. The UI waits for the settings.
- R-22 [low] [fixed] Old base64 v1 chunks were over the size cap. 2 MiB.
- R-23 [low] [fixed] A null link salt was read as the text "null" for file links.
- R-24 [low] [fixed] The clipboard clear could delete another app's clip. Only our own clip is cleared.
- R-25 [low] [fixed] The search text and login email went into the saved state. `remember` only.
- R-26 [low] [fixed] Slow uplinks timed out on each chunk. An idle timeout replaces the total timeout.
- R-27 [low] [fixed] Any HTTP 401 logged out. Only the server's session codes do.
- R-28 [low] [won't fix] The SHA-512 `hash` of old clients is not checked (DESIGN §7).
- R-29 [low] [fixed] Login was sent again on 429/5xx; a retried upload/done could report a false error.
  No login retry; a failed retry checks if the file is there.
- R-30 [low] [fixed] `/f/<uuid>%23<key>` links did not open.
- R-31 [low] [fixed] Tall PDF pages could need a huge bitmap. At most 8 million pixels per page.
- R-32 [low] [fixed] The offline index could load twice at start. Under the mutex.
- R-33 [low] [fixed] Items inside shared folders showed no owner. The owner goes down the folders.
