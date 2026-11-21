# Performance review 2026-11-21

A review of the speed of loading, search and Photos, after the owner asked for more speed with the same UI quality.
The start numbers are from the device test of 2026-11-20 (`research/device-test.md`): search on about 45,000 files
took 10–13 s from the open of search, and a folder with 5,000 files opened in up to 7 s. List scroll (0.09 % janky
frames) and cold start (the phone sets it) were not problems.

Format: `ID [severity] [status]` with PROBLEM, FIX and VERIFY.

- P-01 [high] [fixed]
  PROBLEM: One thread read the tree answer, parsed each entry and decrypted it, one after the other. Folder
  listings read the full answer, parsed it as one JSON object and then decrypted each entry on one core. Shares
  need one RSA decrypt for each entry.
  FIX: `Decode.stream` reads the answer one entry at a time and gives batches of 256 entries to workers on all
  cores (`util/Parallel`, at most two batches for each core wait or run). Listings use the same stream, so a
  listing has no 32 MiB answer limit any more, only the 200,000-item limit. Shares and links use `Decode.all`.
  The device test then showed that the workers never made the reader wait, but the network waited for the
  parser: the `HttpURLConnection` stream read only when the parser asked. `Http.stream` now uses the
  `UrlRequest` callbacks with a queue that reads up to 4 MiB ahead, so the download and the parse overlap.
  VERIFY: unit test `DecodeAllTest` (order, bad entries). Device: done (results below).
- P-02 [medium] [fixed]
  PROBLEM: Each name decrypt called `Cipher.getInstance`, which looks up the provider: up to 200,000 lookups for
  one tree. After a password change most names use an old master key, so each name first failed a tag check with
  the newest key (an exception for each name).
  FIX: one AES-GCM decrypt cipher for each thread, with `init` for each use. `002` text starts with the master key
  that opened the last `002` text. v1 text (CBC, no tag) keeps the order newest first.
  VERIFY: `CryptoTest` (key order, a failed tag check, then a good decrypt on the same thread).
- P-03 [high] [fixed]
  PROBLEM: Search and Photos read the whole tree each, also when the Photos folder is in the root (on the test
  account, about 37 MiB twice). The search tree was in memory only, so each process start and each write in the
  app read it again before the first result.
  FIX: `Trees` keeps the last root read and one other folder read, in memory and on the disk (encrypted with the
  session cache key; each record is bound to its file and place with GCM associated data; the header has the
  folder and the counts). A folder in the root read gets its part of it. A write marks the reads old instead of
  dropping them: the screens show the old read at once and read again behind it. A read with the same items as
  the last one does not write the file again. Each record is compressed and decompressed in one native call: the
  first device test showed 160–200 ms for each record of 2,048 items when the fields went one by one through the
  compressor stream, and 16–24 ms after the change. Owner decision 2026-11-21 (DESIGN §1, §6).
  VERIFY: `TreesTest` (read back, wrong key, damaged and short file, part of the root, newest read wins, no
  second write with no change, a folder loop). Device: done (results below).
- P-04 [low] [fixed]
  PROBLEM: The search index ran NFD and a regex on each name.
  FIX: ASCII names only go to lower case; the result is the same.
  VERIFY: `SearchIndexTest.asciiFoldIsTheSameAsTheFullFold`.
- P-05 [medium] [fixed]
  PROBLEM: The thumbnail of a photo downloaded the whole file (up to 25 MB), two files at a time.
  FIX: a JPEG of more than 1 MiB first reads chunk 0 and uses the picture in its EXIF data, turned like the photo,
  when its short side is 240 px or more. Four of these reads run at a time; a JPEG with no usable EXIF picture
  then waits for the full-download gate and uses chunk 0 again (no second download of it).
  VERIFY: open. The size of the EXIF picture in Pixel camera JPEGs, on a real photo folder; the synthetic test
  photos can have none. Turned photos (portrait) show upright.
- P-06 [low] [fixed]
  PROBLEM: Thumbnails from the disk or the network showed suddenly.
  FIX: they fade in with the fast effects motion of the theme; a thumbnail from memory shows at once, so a scroll
  back does not fade again. "Remove animations" makes the fade instant.
  VERIFY: open (device, Photos grid and a folder grid).
- P-07 [low] [fixed]
  PROBLEM: Search showed "Reading your drive…" at each open until the index was there, and it had no pull to
  refresh and no sign of a read behind the results.
  FIX: with no text yet, search shows "Search by name" (with "Reading your drive…" only on the first read with no
  copy). Pull to refresh reads the tree again; the loading indicator of the other lists shows while a read runs
  behind the results. A failed pull shows a snackbar; a failed read behind the results stays silent.
  VERIFY: device, done.

## Device results (estimates)

2026-11-21, Pixel 9 Pro XL, the test account (71,027 items in the drive, 2,006 media in the Photos folder), release
builds with temporary timing logs (times and counts only; not in the final build). Each run starts with a cold start
of the app. Few runs and a home network: the numbers are estimates.

| Test | 0.4.0 | 0.5.0 |
|---|---|---|
| Search: from the open of search to the index of 71,027 items | 16.9–18.0 s (3 runs) | 0.14–0.18 s from the disk copy (3 runs); the new read behind it ends after 11–18 s |
| Search with no disk copy (the first read after login) | 16.9–18.0 s | 11.2–13.2 s (3 runs). Limit: the network waits 6.7–8.2 s for 58 MiB; the first byte comes after about 1.6 s |
| Photos tab: from the tap to the grid of 2,006 media | 16.0–17.3 s (3 runs) | 0.12–0.16 s from the disk copy (3 runs); the new read behind it ends after 13–20 s |
| Folder with 5,000 files, whole answer against stream, in the same run | 0.75–1.12 s (the whole answer, with the decode on all cores) | 0.51–0.87 s (9 pairs; the stream was faster in each pair) |
| Disk copy of the tree | — | 8.6 MiB; read and decode 98–127 ms |
| Cold start (`am start -W`) | 569–590 ms | 573–611 ms (no change) |
| Release APK | 3.47 MB | 3.49 MB |

The open of the 5,000-file folder after a cold start varied from 0.8 s to 2.7 s in both builds: the network and the
server set it. The final build: search ("Search by name" at once, results while typing, pull to refresh), Photos and
the folder list work, with no crash. Not tested: P-05 (the test photos are small or have no EXIF picture) and the
fade of P-06 (FLAG_SECURE blocks the screenshots; the screen read cannot show a fade).
