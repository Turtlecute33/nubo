# Transfer speed review 2026-11-22

A review of upload and download speed, after the owner saw that MegaGlass (the MEGA SDK) moves files much faster.
The start number is from the device test of 2026-11-20 (`research/device-test.md`): a folder download of about
4.7 MB/s.

Format: `ID [severity] [status]` with PROBLEM, FIX and VERIFY.

## Limits that Filen sets

- Each chunk is 1 MiB and one HTTP request (F-46). There are no byte ranges and no larger requests. MEGA lets its
  SDK ask for up to 16 MB in one request, with 8 connections for each file.
- `ingest` and `egest` speak HTTP/2 only (nginx, no `Alt-Svc` for HTTP/3), checked 2026-11-22.
- `gateway`, `ingest` and `egest.filen.io` have the same 8 addresses and one `*.filen.io` certificate. The platform
  HttpEngine can then use one HTTP/2 connection for all of them. The other 7 domains of each host have their own
  certificates (checked 2026-11-22).
- Filen publishes no rate limits (F-87). The Filen TS SDK uses 16 upload and 32 download requests at a time.

## Findings

- T-01 [high] [fixed]
  PROBLEM: All chunks went to `ingest.filen.io` or `egest.filen.io`: probably one TCP connection for all
  transfers and the API calls. One connection has one congestion window, and one lost packet stops all streams.
  FIX: `Api` spreads the chunks over the 8 domains of each host (F-13, F-14), from the file UUID and the chunk
  index. A retry goes to the `filen.io` host, in case a network blocks a domain.
  VERIFY: `ContentTest` (3 chunks on 3 hosts; a failed chunk tries again on `egest.filen.io`). Device: an upload
  over the 8 hosts downloads again with the right BLAKE3 hash.
- T-02 [high] [fixed]
  PROBLEM: A download had 4 chunks in flight, an upload 3. With a wait of about 0.9 s for each 1 MiB request, this
  gives about 4 MB/s, whatever the link.
  FIX: 16 download and 8 upload requests at a time for each file. `Api` limits the whole app to 32 download and 16
  upload requests (the numbers of the Filen TS SDK).
  VERIFY: device, see below.
- T-03 [medium] [fixed]
  PROBLEM: `Content.write` started a new chunk only when the oldest chunk was done. One slow chunk stopped all
  other slots.
  FIX: `Content.inOrder`: a new request starts when any request ends. Up to 24 chunks wait in memory for a slow
  earlier one; the writes and the hash stay in chunk order.
  VERIFY: `ContentTest` (a first chunk that ends only when chunk 24 starts; the order stays; at most 16 requests).
- T-04 [medium] [fixed]
  PROBLEM: "Keep offline" got one chunk at a time.
  FIX: `Offline.add` uses `Content.inOrder`; the checks and the files stay in chunk order.
  VERIFY: device, see below.
- T-05 [low] [fixed]
  PROBLEM: The queue ran 2 files at a time. With many small files, the calls for each file set the speed.
  FIX: 4 files at a time.
  VERIFY: device, see below.

## Device results (estimates)

2026-11-22, Pixel 9 Pro XL, the test account, Wi-Fi with a VPN, a release build with a temporary switch between
the old and the new settings (times and sizes only; not in the final build). Each test ran 3 times in each mode,
old and new in turns in the same run. One network and few runs: the numbers are estimates. The value is the
median of 3 runs.

| Test | Old settings | New settings | Gain |
|---|---|---|---|
| Download, 100 MiB file | 37.2 s (2.8 MB/s) | 10.2 s (10.3 MB/s) | 3.6× |
| Upload, 64 MiB of made-up data | 34.7 s (1.9 MB/s) | 10.6 s (6.3 MB/s) | 3.3× |
| Upload, 40 files of 256 KiB | 13.4 s | 7.2 s | 1.8× |
| Download, 34 files (16.5 MB) | 9.2 s | 3.2 s | 2.8× |
| Keep offline, 100 MiB file (1 run) | 46.3 s | 9.2 s | 5.0× |

- The upload of the first new run downloaded again with the right size and BLAKE3 hash.
- The largest live Java heap after a GC was 70 MB of 256 MB.
- "Old" for "Keep offline" had 4 chunks at a time; 0.5.1 got 1 chunk at a time.
- The third old run of the small downloads took 4.0 s (9.2–12.1 s before): the server probably had these files in
  its cache by then.
- Not tested: mobile data, a network that blocks one of the domains, a phone with less memory.
