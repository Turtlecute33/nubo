# Filen client protocol: research for a clean-room Android (Kotlin) client

Date: 2026-11-19. Method: reading of public source code, docs and registry pages only.
No account was made. No request was sent to a Filen API host (gateway, ingest, egest).
One static file was fetched from cdn.filen.io (the 2021 whitepaper PDF).

The crypto descriptions in sections 3 to 6 were checked with an independent Python script
against the pinned known-answer vectors of the Rust SDK. All checks pass. See section 12.

## 0. Conventions and sources

- `F-nn` is a finding ID. Each finding gives its source as `S-x path`. IDs are unique but not
  contiguous (F-32, F-33 are not used).
- `VERIFY` marks a point where sources disagree, or where only one source exists and the
  behaviour is server-side.
- "Rust SDK" is the current engine of all official Filen apps (web, mobile, desktop). It is the
  primary reference. "TS SDK" is the previous engine. Where they disagree, follow the Rust SDK
  unless the finding says otherwise.
- JSON field names are exact (case sensitive). Endpoint paths are relative to the gateway host.

| ID | Source | Revision |
|---|---|---|
| S-RS | github.com/FilenCloudDienste/filen-rs (crates `filen-sdk-rs` 0.5.33, `filen-types` 0.2.0) | commit eb93d283609a9730f54fa591dce4774d4883d00e (2026-11-19) |
| S-TS | github.com/FilenCloudDienste/filen-sdk-ts (npm `@filen/sdk` 0.4.2) | commit 6f272ffac11802d5d1a64fb8796871b402db6a71 (2026-07-11) |
| S-APP | github.com/FilenCloudDienste/filen-ts (current web, mobile, desktop apps) | commit 09b67ce19a2582ceaec1f1dc4681cf3e6daa304c (2026-11-19) |
| S-OLDAPP | github.com/FilenCloudDienste/filen-mobile (archived, "READONLY FOR REFERENCE") | commit 7159f61fe9fadbf54b6bea6dbd33f27db727f45e (2026-07-15) |
| S-CLI | github.com/FilenCloudDienste/filen-cli (being sunset, Rust rewrite in filen-rs) | commit 27e5a1544fd153060a349ccbd5716a48b7995728 |
| S-GO | github.com/FilenCloudDienste/filen-sdk-go (official, used by rclone) | commit f8ce4fce921872a234c9a9536328054d45c1748b = tag v0.0.41 (2026-11-13) |
| S-RCLONE | github.com/rclone/rclone `backend/filen`, `docs/content/filen.md` | commit a6779f3d79e0dfb71d08149f0ba35ba5568daab6 (2026-11-18) |
| S-DOCS | github.com/FilenCloudDienste/filen-docs (source of docs.filen.io, includes OpenAPI files) | commit f922a9d8fe464792df65a0bced387f91ec8fac1d (2026-10-02) |
| S-WP | https://cdn.filen.io/whitepaper.pdf, "Security Whitepaper August 2021" | static PDF |
| S-KAT | S-RS `filen-sdk-rs/src/crypto/known_answer_tests.rs` (pinned test vectors) | same as S-RS |
| S-COMM | Community clients, listed in F-10 | commits in F-10 |

Local shallow clones of all of the above: `/tmp/filen-research/src/`.

---

## 1. Licences and reuse

| ID | Item | Licence (exact) | Source |
|---|---|---|---|
| F-01 | filen-sdk-ts | LICENSE = full GNU AGPL v3 text. package.json `"license": "AGPLv3"` (not a valid SPDX id). GitHub API: `AGPL-3.0`. | S-TS `LICENSE`, `package.json` |
| F-02 | filen-rs (all crates: filen-sdk-rs, filen-types, filen-mobile-native-cache, filen-macros, heif-decoder, microthumb, uniffi-bindgen) | LICENSE = GNU AGPL v3 text. Every `Cargo.toml` has `license = "AGPL-3.0"` (deprecated SPDX id, it does not say `-only` or `-or-later`). README: "AGPLv3". The crates are NOT on crates.io; the package names there are free or unrelated. | S-RS `LICENSE`, `*/Cargo.toml`, `README.md` |
| F-03 | filen-ts (current official web, Android/iOS, desktop apps) | LICENSE = GNU AGPL v3. Package `@filen/mobile` has no licence field; other packages say `"AGPLv3"`. | S-APP `LICENSE`, `packages/*/package.json` |
| F-04 | filen-mobile (old app, archived) | AGPL-3.0 | S-OLDAPP `LICENSE` |
| F-05 | filen-android-documents-provider | AGPL-3.0 (GitHub API licence field) | GitHub repo metadata |
| F-06 | filen-cli | AGPL-3.0 | S-CLI `LICENSE` |
| F-07 | filen-sdk-go | **MIT**, "Copyright (C) 2025 by Filen Cloud Dienste UG (haftungsbeschränkt)". README: "This SDK is incomplete and primarily intended for our fork of rclone." | S-GO `LICENSE`, `README.md` |
| F-08 | rclone (backend/filen) | MIT | S-RCLONE `COPYING` |
| F-09 | filen-docs, whitepaper | No LICENSE file. Treat as all rights reserved: read facts, do not copy text or code snippets. | S-DOCS, S-WP |

F-10 Community clients (read for cross-check only; source S-COMM):

| Repo | Licence | Commit | Note |
|---|---|---|---|
| duplicati/duplicati `Duplicati/Library/Backend/Filen` | MIT | f3ad0f4c68058e1f52835c49d027dd56628015d2 | Has bugs (see F-41, F-35, F-23). Do not copy. |
| NaolShow/Filen.NET (C#) | MIT | 4b857e7b256c7bf741e4a5297bc4cfba7882cb2d | Old `down.filen.*` hosts. |
| lithium0003/ccViewer `FilenStorage.swift` | MIT | 311bb203716c5d9179ed95bccf2ddb6076c7e999 | Writes file version 3 on auth v2 accounts; writes `hash` (SHA-512). |
| lingfeishengtian/FilenSDK (Swift) | Apache-2.0 | 61e20b0b4561df9072f02cb83961a1efad641e22 | Sends `Checksum` on chunk upload. |
| juskalalie/ha-filen (Python) | MIT | 70113e8dee6b150021793e6179b7cb3a92621a0c | Auth v3 path agrees with Rust. |
| CrispStrobe/crisp-cloud-rs (`crisp-filen`) | MPL-2.0 | b7a619c7c5a45b443018b85ab3a9c93562f1fa21 | Serial transfers. |
| EnoughTea/rust-filen | MIT | 244d99ddc22c2b437a16e33d4e4e548af613bf30 | 2023, old hosts. |
| nchiker/Filen-Filemanager (Kotlin) | AGPL-3.0 | 52183e4ded414612c46fa56f4d3db883fb68e3e4 | Wraps filen-sdk-rs through UniFFI. Proof that the Rust SDK runs from Kotlin. |
| Gheovgos/Filen-Echo-Extension (Kotlin) | none (all rights reserved) | 8dd87753c7cb70f0cc8e3beae04a0d0e95cb1b22 | Do not read for code. |

F-11 Reuse analysis for a GPL-3.0 Android app (not legal advice):

1. The SPDX id to use for Filen's code is `AGPL-3.0-only` unless Filen states "or later". VERIFY
   with Filen; no source file header says "or later".
2. GPL-3.0 section 13 permits you to link or combine GPL-3.0 code with AGPL-3.0 code and to
   convey the combination. Your own files stay GPL-3.0. AGPL-3.0 section 13 (source offer to
   users who interact with the program over a network) applies to the combination. A local
   Android app has no remote users, so the practical cost is low, but the app distribution
   must give the full corresponding source of the AGPL parts.
3. Options:
   - Link filen-sdk-rs as a native library through UniFFI (feature `uniffi`, Kotlin bindings
     from the `uniffi-bindgen` crate; see `filen-mobile-native-cache`). This is legal under
     GPL-3.0 section 13. Cost: Rust nightly toolchain pinned by `rust-toolchain.toml`,
     cargo-ndk 4.x, big `.so` per ABI, and an internal API that "is likely to undergo multiple
     refactors" (S-RS README).
   - Copy AGPL code into your GPL-3.0 files: those files become AGPL-3.0. Avoid; it mixes
     licences file by file.
   - Clean-room implementation from this document: no licence obligation from Filen code.
     Protocol facts (endpoint names, constants, byte layouts) are not copyrightable
     expression. Keep the implementer away from the AGPL source, and use this document and
     the MIT Go SDK (F-07) as references.
   - MIT code (filen-sdk-go, rclone) may be copied into a GPL-3.0 app. Keep the MIT notice.
4. Test vectors (S-KAT) are data in an AGPL file. Using them as test inputs is low risk;
   generating your own vectors from your own implementation plus the MIT Go SDK is lower.

---

## 2. Hosts and transport

F-12 Gateway (JSON API) hosts. The same 8-host list is in S-TS `src/api/client.ts:57-66` and S-RS
`filen-sdk-rs/src/consts.rs:6-15`:
`https://gateway.filen.io`, `https://gateway.filen.net`, `https://gateway.filen-1.net` ...
`https://gateway.filen-6.net`.
- TS SDK picks a random host for every request (`client.ts:151`).
- Rust SDK uses the list only on wasm (browser). On native targets (Android, iOS, desktop) it
  always uses `https://gateway.filen.io` (`consts.rs:42-50`).
- Go SDK uses only `gateway.filen.io` (S-GO `filen/client/url.go`).
- The OpenAPI file names only `https://gateway.filen.io/v3` (S-DOCS
  `docs/api/specs/filen_openapi.yaml`).
- Recommendation: use the `.filen.io` host. Keep the other 7 as a manual fallback only.

F-13 Egest (download) hosts: `https://egest.filen.io`, `egest.filen.net`, `egest.filen-1.net` ...
`egest.filen-6.net` (S-TS `client.ts:67-76`, S-RS `consts.rs:17-26`). Native Rust uses only
`https://egest.filen.io` (`consts.rs:61-64`).

F-14 Ingest (upload) hosts: `https://ingest.filen.io`, `ingest.filen.net`, `ingest.filen-1.net` ...
`ingest.filen-6.net` (S-TS `client.ts:77-86`, S-RS `consts.rs:28-37`). Native Rust uses only
`https://ingest.filen.io` (`consts.rs:71-74`).

F-15 Old host names `api.filen.io`, `down.filen.*`, `up.filen.*` exist only in old community
clients (rust-filen 2023, Filen.NET 2024). Do not use them. S-TS `client.ts:363` still strips the
auth header for `down.` hosts, which shows they once existed.

F-16 TLS and pinning:
- Rust SDK: rustls with the platform verifier. On Android it calls the platform
  `X509TrustManager` through JNI (system and user CAs, no online revocation check). It fails
  closed until the JVM hands over a context (`FilenTls.nativeInit`). It refuses keys that
  rustls-webpki distrusts. There is no certificate pinning.
  Source: S-RS `filen-sdk-rs/src/platform_net/mod.rs:1-12,56-90`; S-APP
  `packages/filen-mobile/modules/filen-net/android/src/main/java/io/filen/net/FilenTls.kt`.
- Official Android app network security config: cleartext only for `127.0.0.1` (and LAN IPs in
  dev builds). No `<pin-set>`. S-APP `packages/filen-mobile/plugins/withAndroidNetworkSecurityConfig.ts`.
- TS SDK, Go SDK, old app, community clients: no pinning found.
- Rust uses HTTP/1.1 only (ALPN, reqwest without HTTP/2) (`platform_net/mod.rs:56-62`).
- Recommendation: use the Android default trust store. Pinning is your choice; Filen does not
  publish pins, and the 8 domains have separate certificates, so pinning has a breakage risk.

F-17 `https://cdn.filen.io/cfg.json` (maintenance and read-only flags) is read by one old
community client (go-filen). No official SDK reads it. Do not call it. VERIFY if you need a
maintenance signal.

F-18 WebSocket (`socket.filen.io`) is used by official apps for live events and lock
announcements. It is not needed for a v1 client.

---

## 3. Authentication

F-19 `POST /v3/auth/info`, no auth header. Request `{"email": "<email>"}`. Response data:
`{"email": str, "authVersion": 1|2|3, "salt": str, "id": number}`.
S-RS `filen-types/src/api/v3/auth/info.rs`; S-TS `src/index.ts:637-640`.
- For an unknown e-mail the server returns a random salt (anti-enumeration) (S-WP).
- v2 salt: 256 hex chars (128 random bytes, hex). It is used as a UTF-8 string, NOT hex-decoded
  (F-21). v3 salt: 512 hex chars (256 bytes), hex-decoded (F-22). Older accounts can have other
  salt strings; treat the salt as opaque text for v2.
  Source: S-RS `auth/unauth.rs:379-391` (register), S-TS `index.ts` `register()`.

F-20 Auth version 1 (deprecated, old accounts only). S-RS `crypto/v1.rs` `hash_password`,
`derive_password_and_mk`; S-TS `crypto/utils.ts:304-311, 335-347`.
- Login password = `hex(SHA512(hex(SHA384(hex(SHA256(hex(SHA1(pw)))))))) ||
  hex(SHA512(hex(MD5(hex(MD4(hex(MD2(pw))))))))`, 256 hex chars. Each `hex()` is lowercase
  hex text and is the input bytes of the next hash.
- Master key string = `hex(SHA1(hex(SHA512(pw))))` (40 chars).
- MD2 and MD4 are needed only for this path. You can refuse v1 accounts in v1 of your client
  (show "please log in once with the official app"); VERIFY if v1 accounts still exist.

F-21 Auth version 2 (PBKDF2). S-RS `crypto/v2.rs:397-418`, `auth/v2.rs:62-64`; S-TS
`crypto/utils.ts:348-372`; S-GO `crypto/crypto.go:229`. Verified (section 12).
1. `d = PBKDF2-HMAC-SHA512(password = UTF-8(pw), salt = UTF-8(saltString), iterations = 200000,
   dkLen = 64 bytes)`.
2. `h = lowercase hex(d)` (128 chars).
3. Master key string `mk = h[0..64]` (64 hex chars). It is used as TEXT, not decoded.
4. Login password = `lowercase hex(SHA512(UTF-8(h[64..128])))` (128 chars).
- S-DOCS `docs/api/guides/authentication.mdx` prose swaps the two halves; its code snippet,
  S-WP, S-TS, S-RS and S-GO agree with the steps above.

F-22 Auth version 3 (Argon2id). S-RS `crypto/v3.rs:27, 180-209`; S-TS `crypto/utils.ts:373-391`;
S-GO `crypto/crypto.go:347`. Verified (section 12).
1. `a = Argon2id(password = UTF-8(pw), salt = hexDecode(saltString) (256 bytes), t = 3,
   m = 65536 KiB, p = 4, version 0x13, outLen = 64)`.
2. `h = lowercase hex(a)` (128 chars).
3. KEK = `hexDecode(h[0..64])` (32 bytes, an AES-256 key).
4. Login password = `h[64..128]` (64 chars). No extra SHA-512 (different from v2).
- New accounts are still created as v2: S-RS `consts.rs:77` `NEW_ACCOUNT_AUTH_VERSION = V2`,
  S-TS `constants.ts` `REGISTER_AUTH_VERSION = 2`. So v3 accounts are rare. VERIFY.

F-23 `POST /v3/login`, no auth header. Request
`{"email": str, "password": <login password from F-20/21/22>, "twoFactorCode": str, "authVersion": n}`.
Response data: `{"apiKey": str, "masterKeys": str|null, "publicKey": str|null,
"privateKey": str|null, "dek": str|null}`.
S-RS `filen-types/src/api/v3/login.rs`; S-TS `src/api/v3/login.ts`.

F-24 Two-factor authentication. S-TS `api/v3/login.ts` (default), S-RS `error/mod.rs:31-41`,
S-RCLONE `backend/filen/filen.go` (`noTwoFactorCode`), S-DOCS authentication guide.
- When the user has no 2FA code, send `"twoFactorCode": "XXXXXX"`.
- If 2FA is on and the code is missing, the server answers `status:false, code:"enter_2fa"`.
  A wrong code gives `code:"wrong_2fa"`. Ask for the 6-digit TOTP code and send `/v3/login` again.
- The official mobile app asks for "your two-factor code or recovery key" in the same field
  (S-APP `packages/filen-mobile/src/locales/en/auth.ts:20`). So the recovery key is probably
  accepted in `twoFactorCode`. VERIFY.
- TOTP: 32-byte secret, Base32 (S-WP). RFC 6238 SHA-1, 6 digits, 30 s (S-RS `auth/mod.rs`
  test `two_factor_codes_match_the_rfc_6238_sha1_vectors`).
- Wrong password: `code:"email_or_password_wrong"`.
- Logins are rate limited (F-88). Do not retry a failed login in a loop.

F-25 Master keys (auth v1 and v2). S-RS `crypto/v2.rs` `MasterKeys`, `auth/v2.rs:62-113`; S-TS
`index.ts` `_updateKeys`.
- Encrypted form: one `"002"` string (F-34) whose plaintext is the master key strings joined
  with `|`, e.g. `"mkA|mkB|mkC"`. Each key is opaque text (32 random chars from new
  generation, 64 hex chars from F-21, 40 hex chars from F-20).
- The list holds the keys of all past passwords. Old metadata stays encrypted with old keys.
  To decrypt any `002` or `U2FsdGVk` string, try every key in the list until one works.
- Login path A (Rust): if `login.masterKeys` is not null, decrypt it with `mk` from F-21. Put
  `mk` first in the list and remove duplicates. If it is null, encrypt `mk` alone with `mk` and
  send `POST /v3/user/masterKeys {"masterKeys": "<002...>"}`.
- Login path B (TS, Go, and Rust login-with-API-key): `POST /v3/user/masterKeys` with
  `{"masterKeys": encrypt002("mk", key = mk)}`. Response data `{"keys": "<002...>"}`. Decrypt
  with `mk`, split at `|`. The server merges the sent keys into its list and returns the list.
  Rust comment: "`/v3/user/masterKeys` is a POST, but it reads" (`auth/v2.rs:87-95`).
- Which key to WRITE with: Rust uses `mk` (the key of the current password) (`MasterKeys` index
  0). TS uses the LAST key of its list (`masterKeys.at(-1)`). Both work for readers because
  readers try all keys. Use `mk`.

F-26 DEK (auth v3). S-RS `auth/v3.rs:46-113`; S-TS `index.ts:561-603`; S-GO `filen/filen.go`.
- `login.dek` is a `"003"` string (F-35) encrypted with the KEK. Its plaintext is the DEK as
  64 hex chars. Rust reads it from the login response. TS and Go read it with
  `GET /v3/user/dek` → data `{"dek": "<003...>" | null}`.
- If no DEK exists, TS makes 32 random bytes (hex), encrypts with the KEK and sends
  `POST /v3/user/dek`. Rust treats a missing DEK as an error. A client should not create a DEK;
  refuse and tell the user to log in once with an official app.
- All account metadata of a v3 account is `"003"` with the DEK.

F-27 RSA key pair. S-RS `crypto/rsa.rs`, `auth/unauth.rs:168-189`, `consts.rs:93`; S-TS
`crypto/utils.ts:639-728`, `index.ts` `__updateKeyPair`; S-RS `filen-types/src/serde/rsa.rs`.
- RSA 4096 bit, e = 65537.
- `publicKey` = base64 (standard, padded) of SubjectPublicKeyInfo DER.
- `privateKey` = account-metadata-encrypted (`002` with master key, or `003` with DEK) string
  whose plaintext is base64 of PKCS#8 DER.
- Source of the pair: the login response, or `GET /v3/user/keyPair/info` → data
  `{"publicKey": str|null, "privateKey": str|null}`.
- If the account has no pair (both null), the client makes one and sends
  `POST /v3/user/keyPair/set {"publicKey": ..., "privateKey": <encrypted>}`.
- Rust checks that the decrypted private key matches the public key.
- TS also sends `POST /v3/user/keyPair/update` on every login (re-encrypts the private key).
  Rust does not. Do not do it.
- RSA encryption for sharing: RSA-OAEP, hash SHA-512, MGF1 SHA-512, empty label. Output is
  base64. Max plaintext with 4096-bit keys = 512 - 2*64 - 2 = 382 bytes
  (S-RS `fs/meta_recovery.rs:157-159`).

F-28 HMAC key (needed only for auth v3 name hashes, F-42). VERIFY, sources disagree:
- Rust and Go: `HKDF-SHA256(IKM = RSA private exponent d as minimal big-endian bytes (no
  leading zero bytes), salt = none, info = "hmac-sha256-key", L = 32)`.
  S-RS `crypto/rsa.rs:26,42-53` (comment: "every account's HMAC key was derived from that
  encoding"); S-GO `filen/crypto.go`.
- TS: same HKDF but IKM = the whole PKCS#8 DER (base64-decoded `privateKey`).
  S-TS `crypto/utils.ts:140-158`.
- The two give different keys (checked, section 12). The Rust KAT pins the Rust variant. Follow
  Rust and Go. Risk: v3 entries written by TS clients may have other name hashes.

F-29 After login, both SDKs call:
- `GET /v3/user/baseFolder` → data `{"uuid": "<root folder uuid>"}`.
- `GET /v3/user/info` → data `{"id", "email", "isPremium" (0/1), "maxStorage", "storageUsed",
  "avatarURL", "baseFolderUUID"}`.
S-RS `auth/unauth.rs:259-283`; `filen-types/src/api/v3/user/info.rs`.

F-30 API key and session:
- Send `Authorization: Bearer <apiKey>` on all gateway calls except `/v3/auth/info`,
  `/v3/login`, public-link visitor calls and `/v3/health`. Also send it on ingest uploads.
  Do NOT send it to egest (S-TS `client.ts:363-366` deletes it; Rust uses its unauthenticated
  client for chunk downloads, `api/download.rs`).
- Invalid key: `status:false, code:"api_key_not_found"` → log the user out locally.
- No SDK has a logout or key-revoke endpoint. TS `logout()` only clears local state
  (S-TS `index.ts` `logout`). Session lifetime is not documented. VERIFY.
- rclone docs: after a password change, the saved API key must be removed and the user must
  log in again (S-RCLONE `docs/content/filen.md`). So a password change probably invalidates
  keys. VERIFY.
- Rust `login_with_api_key(email, password, apiKey)`: a stored API key plus the password
  rebuild all keys without 2FA (`/v3/auth/info`, then F-25 path B or `GET /v3/user/dek`, then
  `GET /v3/user/keyPair/info`). S-RS `auth/unauth.rs:204-254`.

F-31 What the official Android app stores and how (S-RS `auth/mod.rs:323-345`
`StringifiedClient`; S-APP `packages/filen-mobile/modules/filen-auth-key/.../FilenAuthKeyModule.kt`):
- Stored: email, userId, root uuid, auth info (master key list or DEK), private key, apiKey,
  authVersion. Not stored: the password.
- The file ("auth.json") is encrypted with a 32-byte AES-256-GCM key. That key is wrapped by a
  non-exportable AndroidKeyStore key (alias `filen_auth_dek_wrap`), stored as
  `iv(12) || wrapped`. Use the same pattern.

---

## 4. Metadata (string) encryption formats

All three formats are strings. Readers pick the format by prefix
(S-RS `auth/mod.rs:112-139`, `crypto/v2.rs` `blocking_decrypt_meta_into`; S-TS
`crypto/decrypt.ts:43-110`).

F-34 Format `"002"` (AES-256-GCM, key from text). S-RS `crypto/v2.rs:96-160, 322-330`; S-TS
`crypto/encrypt.ts:68-118`, `crypto/decrypt.ts:56-93`; S-GO `crypto/crypto.go:44-52`. Verified.
- Key: `K = PBKDF2-HMAC-SHA512(password = UTF-8(keyText), salt = UTF-8(keyText), iterations = 1,
  dkLen = 32)`. `keyText` is a master key string, a file key (v1/v2) or a link key.
- Nonce: 12 ASCII characters, used as their 12 bytes. Rust and Go use
  `[a-zA-Z0-9]`; TS uses `[A-Za-z0-9-_]`. Use alphanumeric ASCII only.
- Output: `"002" + nonce(12 chars) + base64std(ciphertext || tag(16))`. Standard base64 with
  padding. No associated data.
- Parse: `s[0..3] = "002"`, `nonce = bytes(s[3..15])`, `body = base64Decode(s[15..])`.

F-35 Format `"003"` (AES-256-GCM, raw key). S-RS `crypto/v3.rs:88-170`; S-TS
`crypto/encrypt.ts:118-150`, `decrypt.ts:94-110`. Verified.
- Key: 32 raw bytes (`hexDecode` of a 64-hex-char key: DEK, KEK, v3 file key, v3 link key).
- Nonce: 12 random bytes.
- Output: `"003" + lowercaseHex(nonce) (24 chars) + base64std(ciphertext || tag(16))`.
- Parse: `nonce = hexDecode(s[3..27])`, `body = base64Decode(s[27..])`.
- TS rule: if the requested version is 3 but the key is not 64 valid hex chars, it writes
  `"002"` instead (`encrypt.ts:63-66`). Rust does the same when it reads a key
  (`auth/mod.rs:532-549`). Duplicati parses `003` wrong (off by 12); ignore it.

F-36 Legacy format `"U2FsdGVk..."` (v1, read only). S-RS `crypto/v1.rs:23-112`; S-TS
`decrypt.ts:50-53` (CryptoJS). Verified.
- The string is base64 of `"Salted__" || salt(8) || AES-256-CBC(PKCS#7)`. Key and IV come from
  OpenSSL `EVP_BytesToKey(MD5, password = UTF-8(keyText), salt, 1 iteration)` → 32-byte key,
  16-byte IV. Detect by prefix `"U2FsdGVk"` (base64 of `"Salted"`).
- Never write it.

F-37 What to write:
- auth v1 or v2 account: account metadata = `"002"` with the current master key `mk` (F-25).
  File keys = v2 (32 chars). Per-file `name`/`size`/`mime` = `"002"` with the file key text.
  Link keys = 32-char text, `"002"`.
- auth v3 account: account metadata = `"003"` with the DEK. File keys = v3 (64 hex).
  Per-file fields = `"003"` with the file key bytes.
  Source: S-RS `auth/mod.rs:508-529` (`make_file_key`, `make_meta_key`), `auth/unauth.rs:273-278`.

F-38 Random key texts (master key, v2 file key, `rm`, `uploadKey`, v2 link key): 32 characters
from `ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_`, uniform
(S-RS `crypto/shared.rs:77-91`; S-TS `crypto/utils.ts:16,28-46` uses `byte % 64`, which is
uniform). Go uses alphanumeric only; both are accepted.

---

## 5. Metadata JSON, file keys and name hashing

F-39 File metadata (the `metadata` field of a file). JSON, encrypted with the account key
(F-37). S-RS `fs/file/meta.rs:33-55, 405-433`; S-TS `cloud/index.ts:4396-4409`; S-GO
`types/shared_types.go:166`.
```
{"name":"photo.jpg","size":12345,"mime":"image/jpeg","key":"<file key text>",
 "lastModified":1719000000000,"creation":1718999999000,"blake3":"<64 hex>"}
```
- `size`: plaintext bytes, number. Readers also accept a numeric string.
- `lastModified`, `creation`: Unix milliseconds. `creation` is optional. Readers accept seconds
  or ms (F-45).
- `key`: v2 = 32-char text; v3 = 64 hex chars (F-43). The listing `version` field tells which.
- Content hash: Rust and Go write `"blake3"` = hex BLAKE3 of the whole plaintext and check it on
  full downloads. TS writes `"hash"` = hex SHA-512 of the plaintext. Rust ignores `"hash"` and
  drops it when it rewrites metadata. Recommendation: write `"blake3"`. Writing `"hash"` too is
  allowed (unknown fields are ignored by all readers). VERIFY that no server check exists.
- Duplicati writes `"create"` instead of `"creation"`: a bug; tolerate unknown fields.

F-40 Directory metadata (the `name` field of a folder in listings). JSON, encrypted with the
account key: `{"name":"Folder","creation":1719000000000}`. TS writes `{"name":"Folder"}` only.
S-RS `fs/dir/meta.rs:176-187`; S-TS `cloud/index.ts:1553-1557`.

F-41 Name hash, auth v1 and v2 (`nameHashed`). S-RS `auth/v2.rs:139-141`, `crypto/v2.rs:388-395`;
S-TS `crypto/utils.ts:92-104, 281-292`. Verified.
- `nameHashed = lowercaseHex(SHA1(UTF-8(lowercaseHex(SHA512(UTF-8(lowercase(name)))))))`
  (40 chars).
- Test: `"Docs"` and `"docs"` → `455f3194ec552fbc452397fc4d6676427fe1bb96` (S-KAT).
- Lowercase: Rust `str::to_lowercase` (full Unicode). JS `toLowerCase` is the same for almost
  all text. In Kotlin use `lowercase(Locale.ROOT)`. Normalize names to NFC first (S-RS
  `fs/name/mod.rs` normalizes to NFC before validation).
- Duplicati hashes the raw SHA-512 bytes, and the OpenAPI text says "hex SHA-512": both wrong.
- The server uses `nameHashed` to find same-name items in a folder (F-52, F-60). It is a
  plain unsalted hash, so the server can test guesses of common names (privacy, F-91).

F-42 Name hash, auth v3: `nameHashed = lowercaseHex(HMAC-SHA256(key = HMAC key (F-28),
msg = UTF-8(lowercase(name))))` (64 chars). S-RS `auth/v3.rs:120-122`, `crypto/rsa.rs:56-66`.

F-43 File key formats (field `key` in F-39; the listing `version` field gives the version).
S-RS `crypto/file.rs:12-120`; S-TS `crypto/encrypt.ts:362-470`.

| version | key text | AES key bytes for chunks | key for per-file `name`/`size`/`mime` |
|---|---|---|---|
| 1 | 32 chars | (legacy CBC, F-56) | `U2FsdGVk` with key text |
| 2 | 32 chars `[A-Za-z0-9-_]` | the 32 ASCII bytes of the text, directly (NO PBKDF2) | `"002"` (PBKDF2 1 iteration of the text) |
| 3 | 64 lowercase hex | `hexDecode(text)` | `"003"` with the same 32 bytes |

F-44 Name rules (Rust; apply them before upload, create and rename). S-RS `fs/name/mod.rs:1-60`:
not empty; max 255 UTF-8 bytes; no control chars 0x00-0x1F or 0x7F; none of `/ \ : * ? " < > |`;
not `.` or `..`; no trailing dot or space; no leading space; no Windows reserved device names.
Normalize to NFC.

F-45 Timestamps. The server returns `timestamp` fields in seconds or ms. Rule used by TS, Rust
and the OpenAPI file: if `abs(nowMs - t) < abs(nowMs - t*1000)` then `t` is ms, else seconds.
S-TS `utils.ts:32-40`; S-RS `filen-types/src/serde/time.rs`. Write metadata times in ms
(Rust rounds to whole ms).

---

## 6. File content: encryption, upload, download

F-46 Chunks. S-RS `consts.rs:79-84`; S-TS `constants.ts` (`CHUNK_SIZE`, `UPLOAD_CHUNK_SIZE`).
- Plaintext chunk size = 1 048 576 bytes (1 MiB). The last chunk is shorter. Chunk index starts
  at 0. Number of chunks = `ceil(size / 1048576)`.
- Encrypted chunk (v2 and v3) = `nonce(12) || AES-256-GCM ciphertext || tag(16)`, so 28 bytes
  more than the plaintext. No associated data. The chunk index is NOT bound into the
  encryption (see F-55). S-RS `crypto/shared.rs:97-133`. Verified.
- Nonce: Rust uses 12 random bytes. TS v2 uses 12 random ASCII chars. Readers take the first
  12 bytes either way. Write 12 random bytes from a CSPRNG.

F-47 Upload flow (Rust `fs/file/write.rs`, TS `cloud/index.ts:4269-4460`, Go
`client/v3_upload.go`, `upload.go`):
1. Validate the name (F-44). Pick the MIME type from the extension.
2. Make `uuid` = random UUID v4 (lowercase, hyphens). Make the file key (F-43). Make
   `uploadKey` = 32 random chars (F-38). Keep `uploadKey` for all chunks of this file.
3. For each chunk `i`: encrypt (F-46), then
   `POST https://ingest.filen.io/v3/upload?uuid=<uuid>&index=<i>&parent=<parentUuid>&uploadKey=<uploadKey>&hash=<lowercaseHex(SHA512(encryptedChunk))>`
   with the encrypted bytes as the body and `Authorization: Bearer <apiKey>`. Response data
   `{"bucket": str, "region": str}`. Chunks may go in parallel.
   S-RS `api/v3/upload/mod.rs:11-41`; S-TS `api/client.ts:895-946`.
4. Hash the plaintext in order (BLAKE3 for `"blake3"`).
5. Take the drive-write lock (F-57).
6. If size > 0: `POST /v3/upload/done` (F-49). If size = 0: `POST /v3/upload/empty` (F-50).
7. If the parent folder is in a public link or is shared, add the new file there (F-53).
8. Release the lock.
- The query parameter `parent` must be a real folder uuid (use the base folder uuid for the
  root).
- The docs say `bucket` and `region` are the same for every chunk of a file (S-DOCS
  `docs/api/guides/files.mdx`). Keep the values of the last response.

F-48 Upload chunk request details:
- Content-Type: the Rust request builder sets `application/json` on every POST, which
  appears to include the raw chunk upload (S-RS `auth/http/mod.rs:1871-1898`); Go sends no
  Content-Type for chunks. Use
  `application/octet-stream` or none. VERIFY (any type seems accepted).
- No `Checksum` header is needed (F-85).

F-49 `POST /v3/upload/done`. S-RS `fs/file/write.rs:134-195`,
`filen-types/src/api/v3/upload/done.rs`, `empty.rs`; S-TS `api/v3/upload/done.ts`; S-GO.
Request:
```
{"uuid": "<file uuid>",
 "name": enc_fileKey(name),
 "nameHashed": "<F-41/F-42>",
 "size": enc_fileKey(decimal string of plaintext size),
 "parent": "<parent uuid>",
 "mime": enc_fileKey(mime),
 "metadata": enc_accountKey(JSON of F-39),
 "version": 2,              // file encryption version: 2 for auth v1/v2, 3 for auth v3
 "chunks": <number of chunks>,
 "rm": "<32 random chars>",
 "uploadKey": "<same as step 2>"}
```
`enc_fileKey` = `"002"` with the file key text (v2) or `"003"` with the file key bytes (v3).
TS does not send `parent` here; Rust and Go do. Send it.
Response data (Rust type): `{"uuid", "stableUUID", "chunks", "size", "timestamp"}`
(TS reads only `chunks`, `size`). `stableUUID` differs from `uuid` when the upload replaced an
existing same-name file (F-52).

F-50 Empty file: `POST /v3/upload/empty` with the same fields minus `chunks`, `rm`, `uploadKey`.
No chunk is uploaded. Clients then use `region = "de-1"`, `bucket = "filen-empty"` as local
placeholders (S-RS `fs/file/write.rs:42-49`). The docs claim empty files are not possible; that
is out of date (S-DOCS files guide).

F-51 `rm` is a 32-char random value. Its purpose is not documented. Send a fresh one per upload.

F-52 Same name in the same folder: the server matches by `nameHashed`. A new upload with the
same name replaces the old file. If account versioning is on, the old file becomes a version
(`GET /v3/file/versions`); `stableUUID` keeps the file identity. S-RS `fs/file/mod.rs:752-760`,
`fs/name/keep_both/mod.rs:1-15`, `filen-types/src/api/v3/user/versioning.rs`. To keep both,
pick a free name first (`POST /v3/file/exists`, F-64). VERIFY the exact server behaviour when
versioning is off.

F-53 Propagation to links and shares (needed for correctness, not for a first test). S-RS
`connect/mod.rs:659-800, 878-951`; S-TS `cloud/index.ts` `checkIfItemParentIsShared`.
After an item is created in, moved into, restored into or renamed inside a folder:
1. `POST /v3/item/linked {"uuid": <parent uuid>}` → data
   `{"link": bool, "links": [{"linkUUID", "linkKey"}]}`. For each link: decrypt `linkKey` with
   the account key, then `POST /v3/dir/link/add` (F-74) for the item (and for each item of its
   subtree if it is a folder).
2. `POST /v3/item/shared {"uuid": <parent uuid>}` → data
   `{"sharing": bool, "users": [{"id", "email", "publicKey"}]}`. For each user:
   `POST /v3/item/share` (F-78) for the item (and its subtree).
3. On rename of an item: `POST /v3/item/linked {"uuid": <item uuid>}` and
   `POST /v3/item/shared {"uuid": <item uuid>}`, then
   `POST /v3/item/linked/rename {"uuid", "linkUUID", "metadata": enc_linkKey(JSON)}` and
   `POST /v3/item/shared/rename {"uuid", "receiverId", "metadata": rsa(JSON)}`.
If you skip this, link visitors and share recipients do not see the new or renamed item.

F-54 Download. S-RS `api/download.rs:28-66`; S-TS `api/client.ts:650-790`; S-DOCS files guide.
- `GET https://egest.filen.io/<region>/<bucket>/<file uuid>/<chunk index>`. No
  Authorization header. `region`, `bucket`, `chunks`, `version` come from the listing.
- Decrypt each chunk with the file key (F-43) and append in index order.
- Size guard: an encrypted chunk is at most 1 MiB + 28 for v2/v3. Rust caps the body at 2 MiB
  (`MAX_ENCRYPTED_CHUNK_SIZE`).
- HTTP 404 on a chunk = chunk missing (`FileChunkNotFound`).
- Response header `X-Cl` gives the real content length (S-RS
  `auth/http/download_body/callback.rs:127-130`). Optional.

F-55 Integrity. GCM authenticates each chunk alone. It does not bind the chunk index, the file
uuid or the chunk count. A server could reorder, repeat or drop chunks without a tag error.
Checks to do:
- Download exactly `chunks` chunks; the total plaintext length must equal metadata `size`.
- If metadata has `"blake3"`, hash the full plaintext and compare (Rust does this:
  `fs/file/read/mod.rs:219-240`, error `ContentHashMismatch`). If it has only `"hash"`
  (SHA-512, TS uploads), compare that.
- For range reads (streaming), the hash check is not possible; accept that or verify later.

F-56 Legacy file data (version 1, read only). S-RS `crypto/v1.rs:94-125`. Three layouts, found by
sniffing the first 16 bytes:
- raw `"Salted__"` + salt(8) + AES-256-CBC, key/IV from `EVP_BytesToKey(MD5, keyText, salt)`;
- the same as base64 text (starts `"U2FsdGVk"`);
- otherwise plain AES-256-CBC (PKCS#7) with key = 32 ASCII bytes of the key text and
  IV = the first 16 bytes of that key.
Support this only if you must open very old files. Rust caps v1 chunks at 2 MiB, enough for
base64-expanded data.

F-57 Drive-write lock. All official SDKs and Go take it around drive writes.
S-TS `src/lock.ts`, `user/index.ts:770-822`; S-RS `sync/mod.rs:110-112`, `sync/lock/mod.rs:1087-1098`,
`filen-types/src/api/v3/user/lock.rs`; S-GO `filen/lock.go`.
- `POST /v3/user/lock {"uuid": "<random uuid v4, one per client session>", "type": "acquire",
  "resource": "drive-write"}` → data `{"acquired": bool, "released": bool, "refreshed": bool,
  "resource": str, "status": ...}`. If `acquired` is false, wait about 1 s and try again.
- Refresh with `"type": "refresh"` while held (TS every 5 s, Go every 20 s, Rust every 15 s).
  The server lease is 60 s.
- Release with `"type": "release"`.
- TS takes it for: upload done/empty, create folder, rename, move, trash, restore, delete,
  version restore, file/dir exists, metadata edits. Rust takes it for the same writes.
- The lock is cooperative. It stops races with the desktop sync engine of the same user.
  Server-side enforcement is unknown. VERIFY.

---

## 7. Drive operations

All are `POST` with a JSON body and `Authorization: Bearer`, unless marked `GET`.
Request and response types: S-RS `filen-types/src/api/v3/**`. HTTP method: S-RS
`filen-sdk-rs/src/api/v3/**` (`post_auth`, `get_auth`, `post_batched`).

F-58 List a folder: `POST /v3/dir/content {"uuid": "<folder uuid>"}` (TS also accepts
`"foldersOnly": true`). Response data:
```
{"uploads": [{"uuid", "stableUUID", "metadata" (F-39, encrypted), "rm", "timestamp",
              "chunks", "size", "bucket", "region", "parent", "version" (1|2|3),
              "favorited" (0|1)}],
 "folders": [{"uuid", "name" (F-40, encrypted), "parent", "color", "timestamp",
              "favorited" (0|1), "is_sync" (0|1), "is_default" (0|1)}]}
```
Decrypt `metadata` / `name` with the account keys (try each master key). If decryption fails,
show a placeholder; TS uses `CANNOT_DECRYPT_NAME_<uuid>`. `size` in the listing is the
server-side size; use metadata `size` for display (TS `realFileSize`, `utils.ts:296-298`).
S-RS `filen-types/src/api/v3/dir/content.rs`; S-TS `api/v3/dir/content.ts`, `cloud/index.ts:225-280`.
- Optional request header `msgpack: 1` makes the server answer in MessagePack for large
  listings (Rust `auth/http/mod.rs:1879-1884`). Not needed; JSON is the default.

F-59 Special values for `uuid` in `/v3/dir/content`: `"trash"`, `"recents"`, `"favorites"`,
`"links"` (S-RS `filen-types/src/fs.rs:105-108,173-176`; S-TS `cloud/index.ts:530,590,680,769`;
OpenAPI). Trashed items keep their original `parent` in the response (Rust models it as
`Trash(originalParent)`).

F-60 Create folder: `POST /v3/dir/create {"uuid": "<new uuid v4>", "name": enc_account(JSON F-40),
"nameHashed": "<F-41/42>", "parent": "<parent uuid>"}` → data `{"uuid", "timestamp"}`.
If a folder with the same `nameHashed` exists in the parent, the server does not create a new
one; it returns the uuid of the existing folder (Rust `CreateDirOutcome::Merged`,
`fs/dir/client_impl.rs:80-133`). Compare the returned uuid with yours. TS checks first with
`POST /v3/dir/exists` (F-64). Then propagate (F-53).

F-61 Rename:
- File: `POST /v3/file/metadata {"uuid", "name": enc_fileKey(newName), "nameHashed",
  "metadata": enc_account(full F-39 JSON with the new name)}`. Keep all other metadata fields
  (key, size, hash...). S-RS `fs/file/client_impl.rs:160-214`.
- Folder: `POST /v3/dir/metadata {"uuid", "nameHashed", "name": enc_account(JSON F-40)}`.
- Check name collisions first (F-64). Then F-53 step 3.

F-62 Move: `POST /v3/file/move {"uuid", "to": "<new parent uuid>"}`,
`POST /v3/dir/move {"uuid", "to": "<new parent uuid>"}`. Server error codes include
`cannot_move_folder_into_itself` (S-RS `filen-types/src/api/v3/bulk/tests.rs:565`). Then F-53.

F-63 Trash, restore, delete:
- `POST /v3/file/trash {"uuid"}`, `POST /v3/dir/trash {"uuid"}`.
- `POST /v3/file/restore {"uuid"}`, `POST /v3/dir/restore {"uuid"}`. The response has no new
  parent; Rust reads it with `POST /v3/file {"uuid"}` / `POST /v3/dir {"uuid"}`. Then F-53.
  Restoring an item that is not in the trash gives `file_not_in_trash` / `folder_not_in_trash`.
- `POST /v3/file/delete/permanent {"uuid"}`, `POST /v3/dir/delete/permanent {"uuid"}`.
- `POST /v3/trash/empty` (no body).
- Trash listing: F-59.

F-64 Exists checks: `POST /v3/file/exists {"nameHashed", "parent"}`,
`POST /v3/dir/exists {"nameHashed", "parent"}` → data `{"exists": bool, "uuid": str|null}`.

F-65 Item info and tree:
- `POST /v3/file {"uuid"}` → `{"uuid", "stableUUID", "region", "bucket", "nameEncrypted",
  "nameHashed", "sizeEncrypted", "mimeEncrypted", "metadata", "timestamp", "size", "parent",
  "versioned", "trash", "version", "favorited"}`.
- `POST /v3/dir {"uuid"}` → `{"uuid", "nameEncrypted" (F-40 JSON), "nameHashed", "parent",
  "trash", "favorited", "color", "timestamp"}`.
- Full tree: `POST /v3/dir/download {"uuid", "skipCache": bool}` → `{"files": [...],
  "folders": [...]}`. The queried folder is in `folders` with `parent: "base"`. Files carry
  `size` as an encrypted string and `chunksSize` as a number; `favorited` is a bool here.
- Folder size: `POST /v3/dir/size {"uuid", "sharerId": 0, "receiverId": 0, "trash": 0}` →
  `{"size", "files", "folders"}`.

F-66 Favourites: `POST /v3/item/favorite {"uuid", "type": "file"|"folder", "value": 0|1}`.
List with `/v3/dir/content {"uuid":"favorites"}`. Folder colour: `POST /v3/dir/color {"uuid",
"color": "default"|"blue"|"green"|"purple"|"red"|"gray"|<custom string>}`.

F-67 Versions: `POST /v3/file/versions {"uuid"}` → `{"versions": [{"uuid", "stableUUID",
"bucket", "region", "chunks", "size", "metadata", "timestamp", "version"}]}`. Restore:
`POST /v3/file/version/restore {"uuid": <old version uuid>, "current": <current uuid>}`.
Account setting: `POST /v3/user/versioning {"enabled": 0|1}`.

F-68 Search: TS used a server-side index (`POST /v3/search/add` with
`{"items": [{"type", "hash", "uuid"}]}`, hashes = HMAC-SHA256 of name parts, and
`POST /v3/search/find`). The Rust SDK does not call these; the current apps search a local
cache. Do not use the server index (it leaks name fragments as hashes). Search locally.

F-69 Storage usage: `GET /v3/user/info` (F-29: `storageUsed`, `maxStorage`, `isPremium`).
More detail: `GET /v3/user/account` (plans, subscriptions, personal data: avoid unless needed).

F-70 Bulk endpoints exist (`/v3/bulk/trash`, `/v3/bulk/move`, `/v3/bulk/dir/create`, ...,
max 50 write items per call). Rust uses them to batch concurrent single calls
(`api/v3/bulk/batched.rs`). The single endpoints above stay valid. Not needed for v1.

F-71 Health: `GET /v3/health` → body text `OK`.

---

## 8. Public links and sharing

F-72 File public link (owner side). S-RS `connect/mod.rs:1076-1190`,
`filen-types/src/api/v3/file/link/edit.rs`; S-TS `cloud/index.ts:2024-2043`.
- Create: make `linkUUID` = new UUID v4. Then
  `POST /v3/file/link/edit {"uuid": <linkUUID>, "fileUUID": <file uuid>, "expiration": "never",
  "password": "empty", "passwordHashed": <see below>, "salt": <salt>, "downloadBtn": true,
  "type": "enable"}`.
- `password` is `"empty"` (no password) or `"notempty"`.
- `expiration`: `"never" | "1h" | "6h" | "1d" | "3d" | "7d" | "14d" | "30d"`.
- Without a password: Rust sends `passwordHashed = "empty"` and a v3 salt (512 hex chars).
  TS sends `passwordHashed = hashFn("empty")` (F-41 hash of the text "empty") and a 512-hex
  salt. Both seem accepted. VERIFY.
- With a password: `passwordHashed` = F-76 hash with that salt.
- Disable: same call with `"type": "disable"`, `"password": "empty"`, `"passwordHashed":
  "empty"`, `"downloadBtn": false`.
- Status: `POST /v3/file/link/status {"uuid": <file uuid>}` → `{"enabled": bool, "uuid":
  <linkUUID>, "expiration", "expirationText", "downloadBtn" (0|1), "password"}`.
- The link key is the file key itself (no separate key).

F-73 File public link (visitor side, no auth header):
1. `POST /v3/file/link/password {"uuid": <linkUUID>}` → `{"hasPassword": bool, "salt": str}`.
2. Hash the password (F-76) or use the no-password value.
3. `POST /v3/file/link/info {"uuid": <linkUUID>, "password": <hash>}` → `{"uuid", "name",
   "mime", "size", "password", "chunks", "region", "bucket", "version", "timestamp",
   "downloadBtn"}`. `name`, `mime`, `size` are encrypted with the file key (F-43 last column).
4. Download chunks as in F-54 with the returned `uuid` (the file uuid), `region`, `bucket`.
S-RS `connect/mod.rs:1480-1520`; S-TS `cloud/index.ts:2238-2300`.

F-74 Folder public link (owner side). S-RS `connect/mod.rs:979-1074`; S-TS `cloud/index.ts:1942-2022`.
1. Take the drive-write lock.
2. Make `linkUUID` (UUID v4) and `linkKey` (32 random chars for auth v1/v2; 64 hex for v3).
   `linkKeyEncrypted` = enc_account(linkKey).
3. List the whole subtree (`/v3/dir/download`, F-65).
4. For the root folder: `POST /v3/dir/link/add {"uuid": <folder uuid>, "parent": "base",
   "linkUUID", "type": "folder", "metadata": enc_linkKey(F-40 JSON), "key": linkKeyEncrypted,
   "expiration": "never"}`.
5. For every sub-folder and file: the same call with `"parent": <its parent uuid>`,
   `"type": "folder"|"file"`, `"metadata"` = enc_linkKey(F-40 or F-39 JSON). The file JSON holds
   the file key, so visitors can decrypt the content.
6. Settings: `POST /v3/dir/link/edit {"uuid": <folder uuid>, "expiration", "password":
   "empty"|"notempty", "passwordHashed", "salt", "downloadBtn": bool}`.
- `enc_linkKey` = `"002"` with the link key text, or `"003"` if the key is 64 hex.
- Remove: `POST /v3/dir/link/remove {"uuid": <folder uuid>}`.
- Status: `POST /v3/dir/link/status {"uuid": <folder uuid>}` → `{"exists": bool, "uuid":
  <linkUUID>, "key": <linkKeyEncrypted>, "expiration", "expirationText", "downloadBtn",
  "password"}`.
- Large folders mean many calls (one per item). Rust sends them in parallel.

F-75 Folder public link (visitor side, no auth):
- `POST /v3/dir/link/info {"uuid": <linkUUID>}` → `{"parent": <root folder uuid>, "metadata",
  "hasPassword", "salt", "timestamp", "downloadBtn"}`.
- `POST /v3/dir/link/content {"uuid": <linkUUID>, "password": <hash>, "parent": <folder uuid>}`
  → `{"folders": [{"uuid", "parent", "metadata", "timestamp", "color"}], "files": [{"uuid",
  "parent", "metadata", "timestamp", "size", "chunks", "bucket", "region", "version"}]}`.
  Decrypt `metadata` with the link key.

F-76 Link password hash (`passwordHashed` and visitor `password`). S-RS `crypto/connect.rs`;
S-TS `crypto/utils.ts:821-882`, `cloud/index.ts:2253-2277`. Verified for v2.

| salt length | hash |
|---|---|
| 512 hex chars (v3, current Rust) | `lowercaseHex(Argon2id(UTF-8(pw), hexDecode(salt), t=3, m=65536, p=4, v=0x13, len=64))` |
| 32 chars (v2, TS `PUBLIC_LINK_VERSION = 2`) | `lowercaseHex(PBKDF2-HMAC-SHA512(UTF-8(pw), UTF-8(salt), 200000, 64))` |
| empty (v1) | F-41 hash of the password text (`hex(SHA1(hex(SHA512(pw))))`) |
| no password | Rust: the text `"empty"`; TS: F-41 hash of the text `"empty"`. VERIFY |

New links: use a v3 salt (256 random bytes as 512 hex chars).

F-77 Link URL format (key in the URL fragment, never sent to a server).
S-APP `packages/filen-web/src/features/publicLinks/lib/format.logic.ts`,
`packages/filen-shared/src/publicLink.ts`, `packages/filen-web/src/features/drive/components/linkDialog.logic.ts:110-136`.
- New format (current web app): `https://app.filen.io/f/<linkUUID>#<hex(UTF-8(fileKey))>` for a
  file; `https://app.filen.io/d/<linkUUID>#<hex(UTF-8(linkKey))>` for a folder. Here f = file,
  d = directory.
- Legacy format (old web; the mobile app still builds it): `https://app.filen.io/#/d/<linkUUID>%23<key>`
  for a file, `https://app.filen.io/#/f/<linkUUID>%23<key>` for a folder. Here the letters are
  swapped: d = download (file), f = folder. `<key>` is the raw key or its hex form.
- Both are parsed by the current apps. The decoded key must be 32 bytes. So a v3 file key
  (64 hex chars) cannot travel in this format. VERIFY how v3 accounts share file links.

F-78 Sharing with another Filen user. S-RS `connect/mod.rs:1293-1400`,
`filen-types/src/api/v3/item/share.rs`, `user/public_key.rs`; S-TS `cloud/index.ts` (`shareItemsToUser`).
- Get the recipient key: `POST /v3/user/publicKey {"email"}` → `{"publicKey": <SPKI base64>}`.
- Root item: `POST /v3/item/share {"uuid", "parent": "none", "email", "type": "file"|"folder",
  "metadata": base64(RSA-OAEP-SHA512(UTF-8(JSON F-39 or F-40)))}`.
- Each descendant of a shared folder: same call with `"parent": <its parent uuid>`.
- Plaintext must be at most 382 bytes (F-27). Long names with a long hash and key can pass the
  limit. Keep metadata compact (no spaces in JSON). VERIFY what official clients do on overflow.
- Rust's current API shares only with accounts in the user's contact list (contacts module).
  The endpoint itself takes an e-mail address.
- Remove: `/v3/item/shared/out/remove`, `/v3/item/shared/in/remove` (bodies not checked here).
- Old TS react-native sharer encoded the plaintext as Latin-1 by mistake; Rust has a fallback
  decoder (`fs/meta_recovery.rs`). Decrypt shared metadata as UTF-8, then try Latin-1.

F-79 Shared listings: `POST /v3/shared/in {"uuid": "shared-in"}` (root) or `{"uuid": <dir>}`;
`POST /v3/shared/out {"uuid": "shared-out", "receiverId": 0}`. Items carry RSA-encrypted
`metadata` (decrypt with your private key), `sharerEmail`, `sharerId`, `writeAccess`.
S-RS `filen-types/src/api/v3/shared/*.rs`.

F-80 The official apps show public links only to paying users ("Public links require a
subscription", S-APP `linkDialog.logic.ts:138`). Server-side enforcement is unknown. VERIFY.

---

## 9. Thumbnails

F-81 Filen stores no thumbnails on the server. There is no thumbnail endpoint in the API list
(S-RS `filen-types/src/api/v3`). The Rust SDK makes thumbnails on the device: it downloads the
first chunk or chunks, decodes (JPEG, PNG, WebP, HEIC/AVIF through libheif, embedded JPEG of
RAW files), scales and encodes to WebP, and caches them locally. File type is decided from the
file extension, not the stored MIME (`filen-sdk-rs/src/thumbnail/mod.rs:1-40`; crate
`microthumb` with a memory budget of 12 MB on iOS extensions). Do the same on Android: generate
from downloaded chunks, cache in app-private storage, never upload.

---

## 10. Headers, errors, retries, rate limits

F-82 Response envelope (gateway): HTTP 200 with
`{"status": true|false, "message": str, "code": str, "data": <payload>}`. On `status:false`
read `code`. S-RS `filen-types/src/api/response.rs`; S-TS `api/client.ts:575-582`.
Rust comment: most API errors come as HTTP 200 with `status:false`; transport problems come as
HTTP 5xx/408/429 (`auth/http/mod.rs:1045-1054`).

F-83 Known error codes (S-RS `error/mod.rs:31-41`, tests, S-TS): `api_key_not_found`,
`email_or_password_wrong`, `enter_2fa`, `wrong_2fa`, `wrong_password`, `max_storage_reached`,
`file_not_found`, `folder_not_found`, `invalid_folder`, `upload_not_found`, `item_not_found`,
`file_not_in_trash`, `folder_not_in_trash`, `cannot_move_folder_into_itself`, `rate_limited`,
`internal_error`, `invalid_params`, `invalid_endpoint`.

F-84 Request headers:
- `Authorization: Bearer <apiKey>` (F-30).
- `Content-Type: application/json` on JSON POSTs.
- `Accept: application/json, text/plain, */*` (TS only, optional).
- `User-Agent`: TS sends `filen-sdk` (Node only); Rust sends none; Go inherits rclone's
  `rclone/<version>`. No UA rule exists. Send a fixed generic value (F-92).
- No request signing exists.

F-85 `Checksum` header: TS sends `Checksum: lowercaseHex(SHA512(UTF-8(JSON.stringify(body))))`
on JSON POSTs, and on chunk uploads `Checksum = hex SHA-512 of JSON.stringify` of the URL query
parameters as an object in URL order (`{"uuid","index","parent","uploadKey","hash"}`, values as
strings). S-TS `api/client.ts:159-167, 916-942`. Rust and Go never send it, so the server does
not require it. Do not send it. If you send it, its bytes must match the exact body.

F-86 Timeouts and retries:
- TS: gateway 300 s, egest 60 s, ingest 180 s; retries every 1 s up to 3600 times, except on
  `status:false` (`api/client.ts:87-91, 545-612`). Too aggressive for mobile.
- Rust: connect 10 s, read (first byte and idle) 300 s; retry on any 5xx, 408, 429 and dead
  connections, max 10 retries per request plus a shared retry budget
  (`TpsBudget::new(10 s, 10, 0.2)`); connect failures retried 3 times at 200/400/800 ms;
  timeouts not retried (`auth/http/mod.rs:85-119, 1045-1107`, `auth/http/retry/mod.rs:20-45`,
  `tower_wasm_time/tps_budget.rs:189-193`).
- Go: no retry at all.
- Recommendation: retry 5xx/408/429 and connection resets with exponential backoff and
  jitter, max about 5 tries; retry `rate_limited` the same way; never retry other
  `status:false` codes. Chunk uploads are safe to retry (same uuid/index/uploadKey).

F-87 Rate limits: no numbers are published. Facts:
- "Logins are rate limited so save the API key" (S-RCLONE `backend/filen/filen.go`, docs);
  "too many login requests result in rate limiting" (S-DOCS `docs/cli/authenticating.mdx`).
- Code `rate_limited` exists (F-83); HTTP 429 is treated as transient by Rust.
- `Retry-After` is not read by any client. VERIFY whether the server sends it.
- Concurrency in official code: TS constants `MAX_UPLOAD_THREADS = 16`,
  `MAX_DOWNLOAD_THREADS = 32` (`src/constants.ts`); Rust is bounded by a memory budget and
  `MAX_SMALL_PARALLEL_REQUESTS = 64` (`consts.rs`). crisp-filen uses
  serial transfers as "gateway-safe". Start with 4 parallel chunk transfers on mobile.

F-88 Login once, then keep the API key (F-30, F-31). Do not log in again on every app start.

---

## 11. Privacy

F-89 Telemetry in official code: none found in filen-sdk-rs, filen-sdk-ts or filen-sdk-go (no
analytics or crash SDKs). The current official Android app has no Sentry or analytics package,
but it bundles Google Play services ML Kit document scanner and `com.google.android.datatransport`
(Google's telemetry transport) (S-APP
`packages/filen-mobile/src/features/settings/thirdPartyNotices.generated.ts`). The 2021
whitepaper mentions a "self hosted analytics service" for the website (S-WP).

F-90 What the server sees even with end-to-end encryption:
- E-mail, IP address, time of every request, API key use.
- Folder tree structure (uuids and parent links), item counts, timestamps (server
  `timestamp`), favourites, folder colours, trash state, versions.
- File sizes: chunk count and encrypted chunk length give the plaintext size to the byte.
- `nameHashed` (v1/v2): unsalted `SHA1(hex(SHA512(lowercase name)))`. The server can confirm
  guessed names (dictionary attack) and see equal names across folders.
- Share and link graph: who shares with whom, which items are public.
- Login events with IP and user agent (`/v3/user/events`, GDPR export has `user_agents`;
  S-RS `filen-sdk-rs/tests/user_tests.rs:1186`).

F-91 Things the official clients do that a privacy-first client should NOT do:
- `POST /v3/search/add` (TS): uploads HMAC hashes of every name prefix.
- `POST /v3/user/keyPair/update` on every login (TS).
- Random rotation over 8 domains per request (TS): more DNS lookups and TLS sessions.
- Sending `Authorization: Bearer anonymous` when not logged in (TS; ha-filen).
- Reading `cdn.filen.io/cfg.json`.
- Opening the WebSocket when not needed.
- Fetching `/v3/user/account` (personal data, invoices) when not needed.

F-92 User agent: login events record it. Send a fixed, generic value with no device model,
OS build or app install ID (for example `Filen-Android-Client/1`). OkHttp's default
`okhttp/x.y.z` is also acceptable. No source requires a specific value.

F-93 No device ID, install ID or push token is part of the protocol. Do not invent one.

---

## 12. Test vectors and independent check

Pinned vectors: S-KAT. Values used (all from S-KAT unless noted):

| Check | Input | Expected |
|---|---|---|
| `002` decrypt | key text `0123456789abcdefghijklmnopqrstuv`, ciphertext `002TmvWyoZdGWJvWFhhl4Y7PjyUaGQIgvVTxP1PtRhaPp0zQ19H6RnKEOa9fdYepQ==` | `{"name":"a","size":1}` |
| `003` decrypt | key = 32 bytes of 0x42, `0030a691da0e0ae2fe460d41753tPH5+bfJtbh01Rv6BoWxuOVuTX1Ks703pYf6Ifr9mXQ+mSEY4w==` | `{"name":"a","size":1}` |
| v1 meta | key text as above, `U2FsdGVkX183cOTZuIowDTPZ3cx+uOTl/DWwl81T7fTdSwIjY3H2Pik27LM+VABu` | same JSON |
| v2 chunk | key text as above (raw bytes), hex `7a5fcda118490d9ce16c1f95128e599b24d3967f007ac651e51f66b3fc` | `00` |
| v3 chunk | key = 32 bytes of 0x11, hex `4ffa6ce821250a375d06841d13b9991591279e27b2f4d3d51f7d6bc1de` | `00` |
| auth v2 | pw `password`, salt `filen-fixed-salt-0123456789abcdef` | mk `c387243a52f7a94cbb5850b90b89acd56dd0a4bbd4c0fd14f10c9b3e06a07dac`; login pw `ae2319242cf549bc15e965d9142cc66ce9981a0914971fb5726a27158ef4ffb6185589dd665824853c140d757eeff197d2c589df8a16787c40f876c5a08f7709` |
| auth v3 | pw `password`, salt `V3_SALT` in S-KAT | `V3_DERIVED` in S-KAT (first 64 hex = KEK, last 64 = login pw) |
| name hash | `Docs` | `455f3194ec552fbc452397fc4d6676427fe1bb96` |
| link pw v2 | pw `password`, salt `x__X9T9953af53zes9u-4pIhAqhxOjBx` | `ce273dada8b49ee004a002a9c46c44e789c644e8ad32446f031e73abadc83e257b571103d314088a87c21dbdc094c96e94f01bbb453110c6cec22b5644797376` (S-RS `crypto/connect.rs` tests) |
| private key | `ENCRYPTED_PRIVATE_KEY_A` with key text above | `KEY_A_PKCS8` |
| RSA | `RSA_FILEN` with `KEY_A_PKCS8`, OAEP SHA-512/MGF1 SHA-512 | `filen` |
| HMAC key | `KEY_A_PKCS8`, Rust rule (F-28), HMAC of `docs` | `e51a14ad887ba63d08dd47ae82bb2e6d0f8c400123aa3c788f53d508d1a6957e` |

Independent check: `/tmp/filen-research/scripts/verify.py` (written from this document, using
Python `cryptography` and `argon2-cffi`, not ported from SDK code) with the vectors extracted
to `/tmp/filen-research/scripts/vectors.json`. Result: all 16 checks PASS. The TS HMAC-key rule
(HKDF over the whole PKCS#8 DER) gives a different key for both test keys, which confirms the
F-28 disagreement.

---

## 13. Minimum for a v1 client

Target: auth v2 accounts (the only kind new registrations make). Detect v1 and v3 and either
support them (sections 3 to 5 have all details) or refuse with a clear message.

Crypto building blocks (Kotlin/JVM): PBKDF2WithHmacSHA512 (`javax.crypto`; note 1 iteration
and 200 000 iterations both needed), AES/GCM/NoPadding (tag 128), SHA-1, SHA-512, HMAC-SHA256,
RSA/ECB/OAEPWithSHA-512AndMGF1Padding (set `OAEPParameterSpec("SHA-512", "MGF1",
MGF1ParameterSpec.SHA512, PSource.PSpecified.DEFAULT)` explicitly, because the Android default
MGF1 hash is SHA-1), BLAKE3 (library needed, e.g. a pure-Java port), Argon2id (library needed,
only for v3 accounts and v3 link passwords), base64 standard with padding, UUID v4, NFC
normalizer.

| Feature | Calls (in order) | Crypto path |
|---|---|---|
| Login | `POST /v3/auth/info` → `POST /v3/login` (`twoFactorCode:"XXXXXX"`; on `enter_2fa` ask and resend) → `POST /v3/user/masterKeys` (or use `login.masterKeys`) → `GET /v3/user/keyPair/info` (or login fields) → `GET /v3/user/baseFolder` → `GET /v3/user/info` | F-21 (KDF), F-25 (key list), F-34 (`002`), F-27 (private key; needed for shares and to validate the pair). Store per F-31. |
| Browse | `POST /v3/dir/content {"uuid"}`; trash/recents/favorites via F-59 | Decrypt `metadata`/`name` with each master key (F-34, F-36 fallback). |
| Download | `GET egest/<region>/<bucket>/<uuid>/<i>` for i in 0..chunks-1, no auth | F-43 key, F-46 layout, F-55 checks (`blake3` or `hash`). |
| Upload | lock acquire (F-57) can come after chunks; `POST ingest/v3/upload?...` per chunk; `POST /v3/upload/done` or `/v3/upload/empty`; F-53 propagation; lock release | New v2 file key (32 chars), F-46 encrypt, SHA-512 of each encrypted chunk for `hash`, F-49 body: name/size/mime `002` with file key, metadata JSON F-39 `002` with `mk`, `nameHashed` F-41. |
| Create folder | lock; `POST /v3/dir/create`; compare returned uuid; F-53; release | F-40 JSON `002` with `mk`, F-41. |
| Rename | lock; `POST /v3/file/exists` or `/v3/dir/exists`; `POST /v3/file/metadata` or `/v3/dir/metadata`; F-53 step 3; release | File: `name` `002` with file key + full metadata `002` with `mk`; folder: F-40 JSON. |
| Move | lock; `POST /v3/file/move` or `/v3/dir/move {"uuid","to"}`; F-53; release | none |
| Trash / restore / delete | lock; `POST /v3/file|dir/trash`, `/restore` (then `POST /v3/file|dir {"uuid"}` for the new parent, F-53), `/delete/permanent`; `POST /v3/trash/empty`; release | none |
| Public link (file) | `POST /v3/file/link/edit` (`type:"enable"`, `password:"empty"`, `passwordHashed:"empty"`, v3 salt, `expiration:"never"`, `downloadBtn:true`); read back with `/v3/file/link/status` | URL F-77 with `hex(UTF-8(fileKey))`. |
| Public link (folder) | lock; `POST /v3/dir/download`; `POST /v3/dir/link/add` for root (`parent:"base"`) and every item; release | New 32-char link key; `key` = `002`(linkKey, mk); item metadata `002` with link key text; URL F-77. |
| Storage usage | `GET /v3/user/info` | none |
| Logout | delete local secrets; no server call exists | none |

---

## 14. Open risks

1. **Licence wording.** Filen code is AGPL v3 without "only" or "or later". The MIT Go SDK is
   the only permissive official reference. Confirm the SPDX id with Filen if you link their
   Rust SDK. (F-01 to F-11)
2. **No official protocol spec.** The OpenAPI file in filen-docs is partly out of date (auth
   v3, `003`, empty files, header names). The behaviour is defined by the Rust SDK, which
   changes often ("likely to undergo multiple refactors"). Pin the facts in this document to
   the listed commits and re-check before release.
3. **Auth v3 HMAC key disagreement (F-28).** TS uses other HKDF input than Rust and Go. Name
   hashes of v3 entries made by TS clients may differ. Matters only for v3 accounts.
4. **v3 accounts are not well covered.** New accounts are v2. v3 file links cannot use the
   current link URL format (F-77). A v1 client should refuse v3 (or treat it read-only) until
   you can test with a real v3 account.
5. **Server-side behaviours not visible in code** (VERIFY items): exact same-name replace and
   versioning rules (F-52), whether the drive-write lock is enforced (F-57), session and API
   key lifetime (F-30), `Retry-After` and real rate limits (F-87), recovery key in
   `twoFactorCode` (F-24), no-password link hash `"empty"` vs `hashFn("empty")` (F-72, F-76),
   subscription gate for links (F-80), RSA plaintext overflow handling (F-78).
6. **Propagation duty (F-53).** Every create, move, restore and rename inside a shared or
   linked folder needs extra calls. If the client skips them, recipients and link visitors see
   stale data. Large linked folders need one call per item.
7. **Integrity gap (F-55).** Per-chunk GCM does not bind the index. Files uploaded by TS-based
   clients carry a SHA-512 `hash`, newer ones a BLAKE3 `blake3`; very old ones none. Range
   reads cannot be verified until the full file is read.
8. **Weak legacy parts you must still read**: `002` uses 1-iteration PBKDF2 and a 12-char
   ASCII nonce (about 71 bits of nonce entropy); v1 uses MD5-based EVP_BytesToKey and CBC
   without integrity. They are needed for compatibility; never use them for new data beyond
   what the format requires.
9. **Name-hash leak (F-41, F-90).** v2 `nameHashed` is unsalted. Any client must send it; the
   server can test guessed file names. Tell users.
10. **Login rate limiting.** A bug that logs in repeatedly can lock the user out for a time.
    Store the API key securely and log in only on demand.
11. **Large dependencies for full coverage.** Argon2id (v3, v3 link passwords), BLAKE3, MD2/MD4
    (v1 only) are not in the Android platform. Choose small, audited libraries.
12. **Rust SDK as a dependency instead of clean-room**: big native build (nightly Rust, NDK,
    meson/ninja/nasm if HEIF is on), AGPL obligations, internal API churn. Fine for a
    prototype or for comparison tests, heavy for a small GPL app.
