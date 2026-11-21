# Device tests

Results of tests on a real phone with a Filen **test account** (free plan) and synthetic files only. All numbers
are estimates from a small number of runs.

Phone: Pixel 9 Pro XL, GrapheneOS (Android 17, security patch 2026-11-01).

## 2026-11-19, 0.1.0 (release build)

| Test | Result |
|---|---|
| Unit tests, lint | 36 tests pass; lint: no issues |
| Release APK | 3.06 MB |
| Cold start (`am start -W`, 4 runs) | 555–631 ms. Fonte 520–553 ms and Varco 543–571 ms on the same phone: the phone sets this time |
| Upload (picker), 8 files, 4.6 MB | **Failed** for all files (R-34). After the fix: about 1 s |
| Upload sizes | 0 B, exactly 1 MiB, 3 MiB + 1234 B: correct |
| Download of 3 MiB + 1234 B | Same SHA-256 as the source file |
| New folder, rename, trash, undo, restore, search, grid, keep offline | Correct |
| Viewers: image, text, PDF | Correct; text scroll 0 % janky frames, 99th percentile 6 ms |
| Viewers: audio, video | Play, but the controls showed 0:00 and "Play" during auto-play (R-35) |
| Old-form link with a bad escape | **Crash** (R-36) |
| Public link | Refused by Filen on the free plan (`subscription_needed`); the app showed the raw code (R-84) |
| Share 3 × 25 MB files from Files, then Back at once | 2 uploads done, the queued one failed with `SecurityException` (R-56) |
| Memory after the viewers | About 176 MB PSS |

Not tested: links (they need a paid plan), shares with other users (they need a second account), app lock (it
needs the owner's fingerprint), "Open with", camera upload, v1 and v3 accounts.

## 2026-11-19, 0.2.0 (release build, installed over 0.1.0)

| Test | Result |
|---|---|
| Unit tests, lint | 61 tests pass; lint: no issues |
| Release APK | 3.04 MB |
| Update over 0.1.0 | The session stays; no new login |
| Share 3 × 25 MB files from Files, then Back at once | All 3 uploads done (R-56) |
| First transfer | The notification request shows one time (R-39); the transfer notification is hidden on the lock screen |
| Search: open a result, then Back | The text and the results stay; the keyboard does not open again (R-47) |
| Search with the keyboard open | The tab bar hides; the last result ends at the top of the keyboard (R-92) |
| Search results | Each result shows its folder ("Drive › zzztest", R-88) |
| Add button | 32 dp above the tab bar, the same as its side margin (R-86) |
| Avatar on Home | Read as "Account" (R-87) |
| Public link on the free plan | "Public links need a paid Filen plan", no "Try again" (R-84) |
| Video auto-play, swipe through 5 pages and back | Audio focus at auto-play; no crash. Memory after the viewer: about 236 MB PSS (3 pages live now, R-51) |
| Old-form link with a bad escape | No crash (R-36) |

Not tested on the phone in this round: reboot and camera upload (R-37, R-38, R-40, R-83), app lock with recents
(R-50), the clipboard clear (R-49), rotation in the viewer (R-51), a v3 folder link (R-57), logout (R-74).

## 2026-11-19, nubo 0.2.0 (after the rename)

| Test | Result |
|---|---|
| Install `app.nubo` (a new app ID) | The login screen shows "nubo"; no crash |
| Cold start (`am start -W`, 3 runs) | 545–632 ms, the same as before the rename |
| APK | 3.04 MB, no native code, the same signing certificate |

The README banner and screenshots come from this phone, with synthetic files on the test account, a
clean status bar painted over the real one, and no personal data. The test files were deleted after.

## 2026-11-20, 0.2.1 with the review fixes and the streamed tree (release build)

The test account got a large synthetic drive: 67,494 files and 3,536 folders (tiny text files, 2,000 camera
photos, real video, audio, PDF and image files, files of 100 MB, 500 MB and 2 GiB, odd names, a chain of 70
folders, and entries with bad metadata). The whole tree answer is 56.8 MiB (the old limit was 32 MiB).

| Test | Result |
|---|---|
| Unit tests, lint | 68 tests pass; lint: only notes about newer library versions |
| Release APK | 3.05 MB |
| Cold start (`am start -W`, 4 runs) | 570–627 ms, the same as before |
| Search, about 45,000 files (tree about 37 MiB) | Results in about 10–13 s from the open of search; about 17 MiB on the network (compressed). The old parse ran out of memory with a 42 MiB tree on the emulator (R-65) |
| Search after changes from another device | Before the fix: no results until a restart (R-104). After: a tree older than 60 s is read again; the new file shows after about 5 s |
| Search, 70 folders deep | The result shows "… › level-68 › level-69 › level-70" |
| Photos, the whole test folder (about 71,000 items) | 2,003 photos and 2 videos; scroll: 1,317 frames, 0 janky, 99th percentile 8 ms; Java heap about 80–88 MB |
| Thumbnails: 48 MP JPEG, 20000 × 200 PNG, 1 × 60000 PNG, 4K HEVC and 1080p video | Correct, no crash |
| Folder with 5,000 files | Scroll: 1,056 frames, 1 janky (0.09 %), 99th percentile 11 ms. Open: 7 s or less (upper limit: the screen read waits for the loading animation) |
| Bad entries from a sharer | Right-to-left override, BEL, C1 control and not-JSON metadata: hidden. Negative or not-a-number size: shown with the size from the server. MIME type with parameters: shown. A 20,000-character MIME type: Filen refuses it (`invalid_params`) |
| Odd names | NFC and NFD, Japanese, Chinese, Korean, Hebrew, Arabic, emoji with joiners, 240 characters, `#` and `%23`: correct |
| Viewers | 10 min 1080p video: plays, seek to 7:35 correct. 30 min AAC: plays. 200-page PDF, 48 MP JPEG: correct |
| Folder download: 0 B, 1 B, 1 MiB − 1, 1 MiB, 1 MiB + 1; 100 MB, 500 MB, 2 GiB (2^31 B) | Same SHA-256 as the source files; about 4.7 MB/s |
| Share 2 and 3 files from Files (grant in ClipData) | Upload correct. While another client holds the write lock: "Another Filen app is writing now. Try again in a minute." with "Try again" |
| Keep offline, 100 MB (check of each chunk, size and BLAKE3) | Correct; remove: correct |
| Me screen at rest | Before: 198 frames in 3 s (the wavy storage bar, R-105). After: 0 frames |

The test files on the phone were deleted after the test. The synthetic drive stays on the test account for the
next tests.

## 2026-11-21, notes and chat on main after 0.2.1 (release build)

Libraries of 2026-11 (Compose 1.13, material3 1.5.0), compileSdk 37.2. The release build went over the
installed 0.2.1; the test account stayed logged in.

| Test | Result |
|---|---|
| Unit tests, lint | 78 tests pass; lint: no issues |
| Release APK | 3.44 MB |
| Notes list, empty account | "No notes"; the server list and the own user ID read correctly |
| New text note, two lines | The note is on the server: the list shows the title and the first line as preview; a new open reads both lines |
| Change type text → checklist | Two rows, one for each line; a new open reads the checklist from the server |
| Checklist: tick a row, add a row | Saved; a new open shows three rows, the first ticked |
| Pin, favourite, rename | Correct after the list read; the pinned note stays first above a newer note |
| Markdown note, search "second" | Only the matching note shows |
| Archive, trash of an archived note, restore | Archive view, then Trash view; restore puts the note back in Notes (N-65) |
| Trashed note | Opens read only ("In the trash · read only") |
| Edit, then Home at once (before the 1.5 s delay) | Saved: the list preview after the return has the new text |
| Delete for ever (2 notes) | Trash empty, Notes empty |
| Chats list, New chat | Both read from the server with no error (no chats, no contacts, no requests on this account). The list stays correct after 40 s of polling |
| Screen reader labels | Material 3 1.5 hides the text of an extended button from screen readers: "New note", "New chat" and "Start chat" had no label. Fixed with a content description |

Not tested: a note or chat seen in the official Filen apps (interop), a save conflict between two devices,
rich-text notes from another app, and any chat with messages. A chat needs a second account and a
contact request to it; the test did not send contact requests.

## 2026-11-21, design feedback branch (release build, installed over 0.3.0)

The test account stayed logged in. Block screenshots was off during the test and on again after it.

| Test | Result |
|---|---|
| Unit tests, lint | 83 tests pass; lint: no issues |
| Release APK | 3.48 MB |
| Cold start (`am start -W`) | 598 ms |
| File menu | Rename has its icon, in line with the other icons |
| Notes and Chats tabs on | Six tabs; no Back arrow on the Notes and Chats roots; no Notes or Chats tile on Home or row in Me |
| Six tabs | Only the selected label shows. **Defect**: the other tabs had no name for TalkBack. After the fix, each icon has the name |
| Upload a folder (2 levels, an empty folder, 3 files) | The same tree in the drive, with the empty folder. The 100 KB file downloads with the same SHA-256. The folder grant is released after the upload |
| File picker, logged out (debug build) | No root |
| File picker, logged in | Root "Nubo / Filen drive", no email. Folders list, thumbnails show, a text file reads correctly |
| File picker, write | Refused ("Read only"); the Files app shows no delete, rename or move |
| File picker, unknown document | `FileNotFoundException` with no name |

Not tested: camera upload status (Immich and Google Photos on this phone back up DCIM, so a test photo could go to
other accounts), the picker with app lock on, logout (no new login on the test account), and the "Password and
two-factor" link (the browser can hold another Filen session).
