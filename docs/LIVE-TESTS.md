# Live tests (owner only)

These tests use a real Filen account. Only the owner runs them, on the owner's phone. The developer
does not log in to real accounts and does not look at cloud content.

Use a test folder (for example `/zzztest`) with made-up files. Do not test in folders with personal
files.

## 1. Login

1. Log in with email and password. Expected: the Home tab shows, with your recent files.
2. With two-factor login on: the app asks for the code. A wrong code shows "The two-factor code is
   wrong".
3. Close the app from recents and open it again. Expected: no new login.

## 2. Browse

1. Drive tab: open folders, go back, switch list and grid, change the sort. Expected: each folder
   keeps its own view after a restart.
2. Pull to refresh. Search for a name. Expected: results from the whole drive.
3. Open Favourites, Shared with me, Shared by me, Public links, Trash from Home.

## 3. Changes (in the test folder)

1. New folder, rename it, move it, move it to the trash, undo, then restore from the Trash.
2. Check in the Filen web app that each change shows there, with the right names.
3. Upload 3 files (one larger than 10 MB). Check them in the web app and download one there.
4. Upload a file with the name of a file that is there. Expected: Filen keeps the old one as a version
   (if versioning is on).

## 4. Download and viewers

1. Download a file and a folder. Expected: they are in Download/Nubo, the folder with its
   structure.
2. Open an image (zoom, swipe), a video (seek), an audio file, a text file and a PDF.
3. "Open with" a document in another app; "Send a copy" to another app.

## 5. Offline

1. Keep a file offline, turn on flight mode, open it from Offline.
2. Remove the offline copy.

## 6. Sharing

1. Make a public link of a file and of a folder, open both in a browser (logged out of Filen).
2. Add a password and an expiry. Remove the link.
3. Open a public link in Nubo (share it to the app, or Home › Open link).
4. Share a folder with a second Filen account, open it there, stop sharing.

## 7. Photos and camera upload

1. Turn on camera upload ("Only new photos"). Take a photo. Expected: it uploads within a few minutes
   on Wi-Fi.
2. Photos tab: the new photo shows; open it.

## 8. Security

1. Settings: turn on the app lock; leave the app and come back. Expected: the lock shows.
2. Block screenshots on: a screenshot is refused, and recents shows no preview.
3. Log out. Expected: the login screen; Offline is empty after the next login.

Write the results with the date and the app version in `research/device-test.md`. Mark timings as
estimates.
