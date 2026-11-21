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
5. Tap +, then "Upload a folder": pick a folder with subfolders, an empty subfolder and 20 or
   more files. Expected: the same tree in the drive, and the empty subfolder too. Compare the tree
   in the web app.
6. Upload the same folder again. Expected: Nubo uses the folders that are there; files with the same
   name become new versions. No second copy of the folder.
7. Start a large upload, turn on flight mode, then turn it off. Expected: the transfer fails with a
   clear error and "Try again" works. The file in the web app is complete (download it there and
   compare the SHA-256).

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

## 7. Other Filen apps (account versions 1, 2 and 3 when you have them)

1. Make a file, a folder, a note and a share in Nubo. Open each in the official Filen app. Expected:
   the same names and content.
2. Edit a note in Nubo, then in the official app, then in Nubo again. Expected: no lost text.
3. A rich-text note from the official app: it shows as plain text in Nubo, and Nubo does not
   change it.
4. Chat between two accounts: one in Nubo, one in the official app. Send, edit and delete from both.
   Expected: both apps show the same messages.
5. Two files with the same name in two folders; a file that the official app replaces. Expected:
   Nubo shows the new version after a refresh.

## 8. Photos and camera upload

1. Turn on camera upload ("Only new photos"). Take a photo. Expected: it uploads within a few minutes
   on Wi-Fi.
2. Photos tab: the new photo shows; open it.
3. Camera upload screen: "Last upload" shows the time of the last photo.
4. Restart the phone, then take a photo. Expected: it uploads with no start of the app.
5. Wi-Fi off during an upload (with "Only on Wi-Fi"): the photo waits. Wi-Fi on: it uploads. It is
   never in the drive half-written.
6. Battery: set Nubo to "Restricted". Take photos. Expected: they upload late or wait, and none is
   marked as done before it uploads.
7. Full storage (an account with little free space): Expected: the notification "Camera upload
   stopped", and the same text on the Camera upload screen. Free space, "Try again": it uploads.
8. Delete the camera upload folder in the web app. Expected: "The camera upload folder is not there
   any more" on the screen and in a notification.
9. An item that fails 3 times: it shows under "Not uploaded" (with its name), and a notification
   shows the count with no name. "Try again" sends it to Transfers.
10. At each step: you can tell what uploaded, what waits and what needs you.

## 9. Security

1. Settings: turn on the app lock; leave the app and come back. Expected: the lock shows.
2. Block screenshots on: a screenshot is refused, and recents shows no preview.
3. Log out. Expected: the login screen; Offline is empty after the next login.
4. Me › Password and two-factor: the browser opens the security settings of the Filen web app.

## 10. Bottom bar

1. Settings › Bottom bar: turn on Notes. Expected: a Notes tab with no Back arrow; no Notes tile on
   Home and no Notes row in Me.
2. Turn on Chats too (six tabs). Expected: only the label of the selected tab shows. Check with the
   largest font size and display size.
3. Turn Notes off while the Notes tab shows. Expected: the app goes to Home.

## 11. Android file picker

1. In another app (for example a text editor), open a file and choose Nubo in the system picker.
   Expected: your drive, with thumbnails for photos. Open a file: the content is correct.
2. The picker shows no write, rename or delete for Nubo files.
3. Log out: Nubo is not in the picker. Log in again: it is back.
4. App lock on: leave Nubo. Expected: Nubo is not in the picker until you open and unlock the app.
5. Turn on flight mode and open a Nubo folder in the picker. Expected: an error text, no crash.

Write the results with the date and the app version in `research/device-test.md`. Mark timings as
estimates.
