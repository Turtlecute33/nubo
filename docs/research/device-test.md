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
