<div align="center">

<img src="docs/logo.svg" width="96" alt="Filenza logo">

# Filenza

**Open-source Filen cloud client for Android.**<br>
End-to-end encrypted, fast and private. No trackers, no Google Play Services, no native code.

[![Android 15+](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white)](#build)
[![GrapheneOS](https://img.shields.io/badge/GrapheneOS-ready-2F5BC9)](#security)
[![License: GPL-3.0](https://img.shields.io/badge/License-GPL--3.0-blue)](LICENSE)

</div>

## About

Filenza is an unofficial client for [Filen](https://filen.io) cloud storage on Android 15 and
later. It is written from the ground up in Kotlin: the Filen protocol and its encryption (from the
open Filen SDKs and their published test values), and Jetpack Compose with Material 3 Expressive
for the interface. It has no native code, no WebView and no dynamic code loading.

## Features

- **Drive**: browse in a list or a grid, sort each folder, search, upload, download, new folder,
  rename, move, trash and restore, favourites and recents.
- **Fast transfers**: parallel chunk uploads and downloads, a transfer queue that goes on in the
  background, and progress in one place.
- **Photos**: a timeline of your camera folder, and camera upload of new photos and videos.
- **Viewers**: images (zoom), video, audio, text and PDF, all in the app.
- **Offline**: keep files on the phone and open them with no network.
- **Sharing**: public links with an expiry and a password, shares with other Filen users, and
  public Filen links that open in the app without an account.
- **App lock**, **screenshot blocking**, and light, dark, pure black and wallpaper colours.

## Security

- **Your password stays on the phone.** The app derives the login secret on the phone (PBKDF2 or
  Argon2id, as Filen specifies). Filen gets only the derived value. The app does not store the
  password.
- **The session is encrypted** with a key in the Android Keystore (StrongBox when the phone has
  it), in storage that is not in backups.
- **No plain copy of your files on the disk.** Viewers decrypt into memory. "Open with" and "Send a
  copy" give the other app a file handle that decrypts as it reads; no decrypted file is written.
  Offline files stay encrypted as Filen stores them. Thumbnails are encrypted with a key of the
  session.
- **Downloads are checked.** Each 1 MiB chunk has its own authentication tag. A full download also
  checks the size and the BLAKE3 hash of the file, when the uploader wrote one.
- **Hardened app.** Hardware memory tagging (`memtagMode="sync"`) on GrapheneOS and Pixel, no
  cleartext traffic, system certificates only, certificate transparency, no backups, touches through
  overlays of other apps are dropped, and the keyboard does not learn from passwords.
- **App lock** (optional) uses your fingerprint or the screen lock. It hides the app; it does not
  encrypt data.

## Privacy

- No ads, no analytics, no crash reports, no Google Play Services.
- The app connects only to Filen: `gateway.filen.io` (API), `ingest.filen.io` (uploads) and
  `egest.filen.io` (downloads). Downloads and public links carry no account key.
- The user agent is `Filenza` on every phone. No device ID, install ID or push token.
- Search runs on the phone. Filenza does not use Filen's server-side search index, which would upload
  hashes of name parts.
- Camera upload does not ask for media location access, so Android removes the GPS position from
  the uploaded photos.
- Settings › Support has links to donate and to the developer on X and Nostr. They open in your
  browser. The app itself does not connect to these hosts.
- What Filen can still see, with any client: your email address, IP address and times of use, the
  shape of your folder tree, the file sizes, who you share with, and which items have links. On
  accounts of auth version 1 and 2, Filen also gets an unsalted hash of each lower-case name, so it
  can test guesses of common names.

## Download

Filenza is not published yet. Build it from the source (below).

## Build

Requirements: Android SDK with platform 37, JDK 21.

```sh
./gradlew :app:assembleDebug     # debug build, app id app.filenza.debug
./gradlew :app:testDebugUnitTest # unit tests (crypto against Filen's known-answer values)
```

For a signed release, run `scripts/make-release-key.sh` once, then `./gradlew :app:assembleRelease`.
Without the key, the release APK stays unsigned. The release APK is about 3 MB (estimate).

<details>
<summary><b>How it works</b></summary>

- **Login**: `/v3/auth/info` gives the salt and the auth version. The app derives the login secret
  and the key that opens the account keys, logs in with the secret, then reads the master keys (or the
  data key on v3 accounts) and the RSA key pair.
- **Metadata**: names and file keys are encrypted with AES-256-GCM in the Filen formats `002` and
  `003`; old `U2FsdGVk` data is read only.
- **Files**: each 1 MiB chunk is AES-256-GCM with a random nonce. Uploads hash each encrypted chunk
  (SHA-512) for the server, and the plain file (BLAKE3) for the metadata.
- **Shares and links**: a folder share or link holds a copy of the metadata of each item inside,
  encrypted for the receiver (RSA-OAEP-SHA512) or with the link key. Each new, moved or renamed item
  is added there too. The key of a public link is in the part of the URL after `#`, which no browser
  sends to a server.
- **Writes** take the same "drive-write" lock as the official apps, so they do not race with Filen's
  desktop sync.

The design notes are in [docs/DESIGN.md](docs/DESIGN.md), the protocol research in
[docs/research/protocol.md](docs/research/protocol.md).

</details>

## Licence

GPL-3.0. Bouncy Castle (MIT) gives Argon2id and BLAKE3. Material Symbols are Apache-2.0.

<sub>Filenza is not affiliated with, or endorsed by, Filen Cloud Dienste UG. "Filen" is their
trademark.</sub>
