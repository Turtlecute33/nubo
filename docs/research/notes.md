# Filen notes: research for a clean-room Kotlin client

Date: 2026-11-20. Method: reading of public source code only. No request was sent to a Filen
server. Scope: notes. Chat is out of scope (a different document).

## 0. Conventions and sources

- `N-nn` is a finding ID of this document. `F-nn` is a finding in `protocol.md`.
- `VERIFY` marks a point that only one source shows, or that the server decides.
- JSON field names, endpoint paths and enum values are exact (case sensitive).
- All sources are AGPL (F-01..F-03). This document gives facts in its own words. It copies no code.
- Citation prefixes:

| Prefix | Path in `/tmp/filen-research/src/` | Revision |
|---|---|---|
| `R` | `filen-rs/filen-sdk-rs/src/` (crate 0.5.33) | commit e37de749808195677ec809434aaa63af83ead9e7 (2026-11-20) |
| `T` | `filen-rs/filen-types/src/api/v3/` (crate 0.2.0) | same |
| `RT` | `filen-rs/filen-sdk-rs/tests/note_tests.rs` (live tests) | same |
| `CLI` | `filen-rs/filen-cli/src/commands/notes_cmds/` | same |
| `TS` | `filen-sdk-ts/src/` (npm 0.4.2, previous engine) | commit 6f272ffac11802d5d1a64fb8796871b402db6a71 |
| `M`, `W`, `SH` | `filen-ts/packages/{filen-mobile,filen-web,filen-shared}/src/` (current apps) | commit 22f85698eef6d4d653fb7ceed9f99dae5b2177d7 (2026-11-20) |
| `N` | Nubo `app/src/main/kotlin/app/nubo/` | working tree |

The current official apps (mobile, web) call the Rust SDK (`@filen/sdk-rs`) for all note
requests and all note crypto. They add only the content format, the preview text and the order of
calls. So the Rust SDK is the reference for the wire format; the apps are the reference for content.

Value types in the tables:
`uuid` = lowercase UUID text; `enc` = encrypted text (`002` or `003`, F-34, F-35); `rsa` = base64 of
an RSA-OAEP ciphertext (F-27); `u64` = JSON number (readers also accept a numeric text);
`ts` = number in seconds or ms (F-45); `type` = note type text (N-04).

---

## 1. Data model

N-01 Note object (an item of `GET /v3/notes`). `T notes/mod.rs:72-98`.

| Field | Type | Meaning |
|---|---|---|
| `uuid` | uuid | Note ID. The client makes it (N-60). |
| `ownerId` | u64 | User ID of the owner. |
| `editorId` | u64 | User ID of the last editor. |
| `isOwner` | bool | True if the caller is the owner. |
| `favorite`, `pinned`, `trash`, `archive` | bool | State flags (N-65). |
| `tags` | array of tag (N-03) | Tags of the caller on this note. |
| `type` | type | N-04. |
| `metadata` | enc | Owner copy of the note key, account key (N-42). |
| `title` | enc | `{"title": …}` with the note key (N-45). |
| `preview` | enc or `""` | `{"preview": …}` with the note key. `""` before the first content write. |
| `createdTimestamp`, `editedTimestamp` | ts | |
| `participants` | array of participant (N-02) | Includes the owner. |

The listing has no content. Get content with `/v3/notes/content` (N-11).

N-02 Participant object. `T notes/mod.rs:56-70`.
`userId` u64, `isOwner` bool, `email` text, `avatar` text or null, `nickName` text or null,
`metadata` rsa (participant copy of the note key, N-43), `permissionsWrite` bool,
`addedTimestamp` ts. Test: null `avatar` and `nickName` are valid (`T notes/mod.rs:110-126`).

N-03 Tag object (in `Note.tags` and in `GET /v3/notes/tags`). `T notes/mod.rs:44-54`.
`uuid` uuid, `name` enc (`{"name": …}` with the account key, N-46), `favorite` bool,
`editedTimestamp` ts, `createdTimestamp` ts.

N-04 Note types: exactly `"text"`, `"md"`, `"code"`, `"rich"`, `"checklist"`.
`T notes/mod.rs:28-42`; `TS api/v3/notes.ts` (`NoteType`). The Rust enum has no fallback value, so an
unknown value fails the parse of the whole `/v3/notes` answer in Rust. A robust reader shows an
unknown type as read-only text. Do not write other values.

---

## 2. Endpoints

All calls go to the gateway (F-12) with `Authorization: Bearer` (F-84). Answer envelope: F-82.
"none" = the Rust SDK reads no `data` (unit type). Nubo `Api.post` already gives `{}` then.
Source of each request type: `T notes/<path>.rs`; Rust caller: `R notes.rs` at the given lines.

| ID | Use | Method, path | Request JSON | Response `data` | Rust caller |
|---|---|---|---|---|---|
| N-10 | List notes | `GET /v3/notes` | (no body) | array of note (N-01) | `R notes.rs:474-490`; `T notes/mod.rs:100-104`; `TS api/v3/notes.ts` (GET) |
| N-11 | Get content | `POST /v3/notes/content` | `{"uuid"}` | `{"preview": enc\|"", "content": enc\|"", "editedTimestamp": ts, "editorId": u64, "type": type}` | `R notes.rs:668-700`; `T notes/content/mod.rs:8-27` |
| N-12 | Create | `POST /v3/notes/create` | `{"uuid": uuid, "title": enc, "metadata": enc}` | `{"createdTimestamp": ts, "editedTimestamp": ts}` | `R notes.rs:607-666`; `T notes/create.rs:6-23` |
| N-13 | Edit content | `POST /v3/notes/content/edit` | `{"uuid", "preview": enc, "content": enc, "type": type}` | `{"timestamp": ts}` | `R notes.rs:759-795`; `T notes/content/edit.rs:6-23` |
| N-14 | Edit title | `POST /v3/notes/title/edit` | `{"uuid", "title": enc}` | none | `R notes.rs:797-819`; `T notes/title/edit.rs:5-12` |
| N-15 | Change type | `POST /v3/notes/type/change` | `{"uuid", "preview": enc, "content": enc, "type": type}` | `{"uuid", "type", "editorId": u64, "editedTimestamp": ts}` | `R notes.rs:702-757`; `T notes/type/change.rs:6-28` |
| N-16 | Trash | `POST /v3/notes/trash` | `{"uuid"}` | none | `R notes.rs:842-852` |
| N-17 | Restore (from trash or archive) | `POST /v3/notes/restore` | `{"uuid"}` | none | `R notes.rs:882-893` |
| N-18 | Delete for good | `POST /v3/notes/delete` | `{"uuid"}` | none | `R notes.rs:821-828` |
| N-19 | Archive | `POST /v3/notes/archive` | `{"uuid"}` | none | `R notes.rs:830-840` |
| N-20 | Pin | `POST /v3/notes/pinned` | `{"uuid", "pinned": bool}` | none | `R notes.rs:868-880` |
| N-21 | Favourite | `POST /v3/notes/favorite` | `{"uuid", "favorite": bool}` | none | `R notes.rs:854-866` |
| N-22 | List tags | `GET /v3/notes/tags` | (no body) | array of tag (N-03) | `R api/v3/notes/tags/mod.rs:10-12` (uses GET); `TS api/v3/notes/tags.ts:40-41` |
| N-23 | Create tag | `POST /v3/notes/tags/create` | `{"name": enc}` | `{"uuid": uuid, "timestamp": ts}` | `R notes.rs:1026-1061` |
| N-24 | Rename tag | `POST /v3/notes/tags/rename` | `{"uuid", "name": enc}` | `{"editedTimestamp": ts}` | `R notes.rs:1063-1082` |
| N-25 | Favourite tag | `POST /v3/notes/tags/favorite` | `{"uuid", "favorite": bool}` | `{"editedTimestamp": ts}` | `R notes.rs:1084-1101` |
| N-26 | Delete tag | `POST /v3/notes/tags/delete` | `{"uuid"}` | none | `R notes.rs:1103-1110` |
| N-27 | Tag a note | `POST /v3/notes/tag` | `{"uuid": note uuid, "tag": tag uuid}` | `{"editedTimestamp": ts}` (Rust puts it on the tag) | `R notes.rs:973-992` |
| N-28 | Untag a note | `POST /v3/notes/untag` | `{"uuid": note uuid, "tag": tag uuid}` | none | `R notes.rs:994-1007` |
| N-29 | History list | `POST /v3/notes/history` | `{"uuid"}` | array of `{"id": u64, "preview": enc\|"", "content": enc\|"", "editedTimestamp": ts, "editorId": u64, "type": type}` | `R notes.rs:922-948`; `T notes/history/mod.rs:8-33` |
| N-30 | History restore | `POST /v3/notes/history/restore` | `{"uuid", "id": u64}` (JSON number) | none | `R notes.rs:950-971` |
| N-31 | Add participant | `POST /v3/notes/participants/add` | `{"uuid", "contactUUID": "owner"\|contact uuid, "metadata": rsa, "permissionsWrite": bool}` | `{"timestamp": ts, "avatar": text\|null, "nickName": text\|null}` | `R notes.rs:493-537`; `T notes/participants/add.rs:8-58` |
| N-32 | Remove participant (also "leave") | `POST /v3/notes/participants/remove` | `{"uuid", "userId": u64}` | none | `R notes.rs:559-576`; `M features/notes/notesParticipants.ts:9-23` |
| N-33 | Participant write permission | `POST /v3/notes/participants/permissions` | `{"uuid", "userId": u64, "permissionsWrite": bool}` | none | `R notes.rs:578-598` |

N-34 Field name traps: `contactUUID` is the only field that is not camelCase
(`T notes/participants/add.rs:14`). The type field is `"type"`, not `"noteType"`. `"pinned"`, not
`"pin"`.

N-35 No single-note GET exists. Rust and TS find one note in the full list
(`R notes.rs:600-605`; `TS notes/index.ts:151-160`). Notes in trash and archive are in the same list;
filter with the flags (`TS notes/index.ts:170-188`).

N-36 Bulk forms (optional). `T bulk/mod.rs:1-51`, `T bulk/notes/*.rs`.
`POST /v3/bulk/notes/{archive,delete,favorite,pinned,restore,tag,trash,untag}` with
`{"items": [<single request>, …]}`. Max 250 items for write endpoints. Answer `data` =
`{"results": [<envelope of the single call>, …]}` in item order; check `status`/`code` of each
result, not only the outer `status`. `bulk/notes/tag` answers `note_tagged` without data for a note
that has the tag already (`T bulk/notes/tag.rs:9-10`). `POST /v3/bulk/notes/participants/add`:
`{"uuid", "items": [{"contactUUID", "metadata", "permissionsWrite"}]}`
(`T bulk/notes/participants/add.rs:12-33`). An old server answers `invalid_endpoint`.

N-37 Lock `notes-write`. Same mechanism as F-57 with `"resource": "notes-write"`
(`R sync/mod.rs:154-156`; `TS index.ts:102-105`). Rust takes it for every write in N-12..N-33,
including favourite, pin and tag favourite. TS does not take it for favourite, pin and tag favourite
(`TS notes/index.ts:736-759, 1119-1124`). Rust takes it while it encrypts, and holds it for create +
owner participant (`R notes.rs:614-663`). Reads (N-10, N-11, N-22, N-29) take no lock. Nubo:
`N drive/WriteLock.kt:68` has `drive-write` fixed; give the resource name as a parameter and reuse
the class (one shared mechanism).

N-38 Live events (out of scope, for later): socket events `noteNew`, `noteArchived`, `noteDeleted`,
`noteRestored`, `noteContentEdited`, `noteTitleEdited`, `noteParticipantNew`,
`noteParticipantRemoved`, `noteParticipantPermissions` (`T socket.rs:293-301`).

---

## 3. Keys and encryption

N-40 Note key generation. One symmetric key for each note.
- Rust: always a "v2" key = 32 random characters from `A-Z a-z 0-9 - _`, for every account version
  (`R crypto/notes_and_chats.rs:122-126` → `R crypto/v2.rs:243-248` →
  `R crypto/shared.rs:77-91`).
- TS: `generateEncryptionKey("metadata")` = 32 characters (`byte % 64` over the same alphabet)
  while `METADATA_ENCRYPTION_VERSION` is 1 or 2 (default 2); 64 hex characters only if the build
  sets it to 3 (`TS crypto/utils.ts:28-42, 76-90`; `TS constants.ts:68-70`).
- Readers accept any 32-character text (`R crypto/v2.rs:320-327` derives the key from any text;
  TS only checks `length > 16`, `TS crypto/decrypt.ts:729-765`). `[a-zA-Z0-9]` keys (Nubo
  `Bytes.alnum`) are valid.
- Write: 32 characters. Do not make 64-hex note keys.

N-41 Key text → cipher (`R crypto/notes_and_chats.rs:34-43`):
- length 32 → format `002` (F-34): AES key = PBKDF2-HMAC-SHA512(key text, key text, 1, 32 bytes);
  nonce = 12 characters `[a-zA-Z0-9]` (`R crypto/v2.rs:27, 108-164`).
- length 64 → hex-decode → format `003` (F-35) with the 32 raw bytes.
- other length → error ("cannot decrypt").
- A 32-character key is NOT raw AES key bytes. This is different from a v2 file key (F-43).

N-42 Owner copy (`Note.metadata`, sent in N-12). Plaintext JSON `{"key":"<note key text>"}`,
encrypted as account metadata (F-37):
- auth v1/v2: `002` with a master key. Rust writes with the current master key; TS with
  `masterKeys.at(-1)` (`TS notes/index.ts:401-408`; `TS crypto/encrypt.ts:50-63`). Readers try all
  master keys (`TS crypto/decrypt.ts:729-765`).
- auth v3: Rust writes `003` with the DEK (`R auth/v3.rs:32-44`; `R notes.rs:617`). See N-48.
- Rust code: `R crypto/notes_and_chats.rs:222-278` (`NoteOrChatKeyStruct`, one field `key`).

N-43 Participant copy (`Participant.metadata`, sent in N-31). Plaintext = the same JSON
`{"key":"<note key text>"}` as UTF-8. Cipher: RSA-OAEP, hash SHA-512, MGF1 SHA-512, empty label,
output base64 standard with padding (`R crypto/rsa.rs:93-112`; `TS crypto/encrypt.ts:171-216`, the
browser import uses hash SHA-512, `TS crypto/utils.ts:445-449`). Same as F-27. Plaintext is 41 bytes
for a 32-character key (max 382). Public key:
- the owner itself: its own account public key, `contactUUID: "owner"`, `permissionsWrite: true`
  (`R notes.rs:653-663`; `TS notes/index.ts:426-431`);
- another user: `publicKey` (base64 SPKI DER) of the contact from `GET /v3/contacts`
  (`T contacts/mod.rs:20-34`), `contactUUID` = the contact's `uuid` (not the user ID).

N-44 How a reader gets the key:
- Rust: the participant entry with the caller's `userId` → RSA decrypt. If none and
  `ownerId == userId` → decrypt `metadata` with the account key. Else: no key
  (`R notes.rs:380-401`; tests `R notes.rs:1552-1579`).
- TS: owner → `metadata` first (try all master keys); else participant RSA
  (`TS notes/index.ts:242-278`).
- Both are valid. Recommendation for Nubo: owner → `metadata` first (one AES call), then RSA. One
  RSA-4096 private operation costs about 5-15 ms on a phone (estimate), so RSA for each owned note
  makes a large list slow. Keep decrypted note keys only in the encrypted cache.
- A participant without an entry cannot read the note. Show it as "cannot decrypt"
  (`M features/notes/utils.ts:48-53`).

N-45 Note fields. All four use the note key (N-41). Plaintext = JSON with one field
(`R notes.rs:344-378`; `TS crypto/encrypt.ts:253-321`):

| Field | Plaintext JSON | Key |
|---|---|---|
| title | `{"title":"…"}` | note key |
| preview | `{"preview":"…"}` | note key |
| content | `{"content":"…"}` | note key |
| history `preview`, `content` | same as above | note key |

N-46 Tag names: `{"name":"…"}` with the ACCOUNT key, not a note key (`R notes.rs:61, 425-430,
1041`; `TS crypto/encrypt.ts:323-337`). Tags are private to an account (N-67). Same format rules as
N-42 (`002` master key for v1/v2, `003` DEK for v3).

N-47 Decrypt rules of the Rust reader (`R crypto/notes_and_chats.rs:143-188`):
- `""` (empty ciphertext) → `""`. Do not try to decrypt it.
- Plaintext that is not UTF-8 → read as Latin-1 (old clients wrote one byte per UTF-16 unit; test
  `R crypto/notes_and_chats.rs:343-364`; origin `R fs/meta_recovery.rs:166-175`).
- JSON with a lone surrogate escape (`\ud83d`) → that character becomes U+FFFD
  (`:170-177`, tests `:300-341`).
- Missing field or bad JSON → error. TS gives `""` for a missing field
  (`TS crypto/decrypt.ts:813-920`).
- Nubo `MetaKey.decrypt` uses `decodeToString()` (U+FFFD for bad UTF-8). This loses Latin-1 text of
  very old notes. VERIFY whether such notes exist for real users. Android `org.json` accepts lone
  surrogate escapes. VERIFY on device.

N-48 VERIFY: v3 accounts and the TS SDK. The TS SDK keeps the DEK (64 hex) as its only "master key"
(`TS index.ts:561-603`) and writes metadata with version 2 by default
(`TS crypto/encrypt.ts:50-63`; `TS constants.ts:68-70`). So a TS client with default settings
writes the owner copy and tag names of a v3 account as `002` with the DEK HEX TEXT as the key text.
The Rust v3 reader reads only `003` (`R auth/v3.rs:32-44`; `R crypto/v3.rs:125-163`). Unknown if
such data exists. Cheap fallback for Nubo: on a v3 account, if a `002` text fails, try
`MasterKey(dekHex.encodeToByteArray())`. Write `003` only.

N-49 Nubo primitive for each step.

| Step | Nubo primitive | Status |
|---|---|---|
| Make note key | `Bytes.alnum(32)` (`N crypto/Bytes.kt:15`) | exists |
| Note key 32 chars → cipher | `MasterKey(key.encodeToByteArray())` (`N crypto/Meta.kt:25-48`): `002`, PBKDF2 1 round, alnum nonce | exists, exact match |
| Note key 64 hex → cipher | `DataKey(key.unhex())` (`N crypto/Meta.kt:54-71`): `003` | exists |
| Choose between the two | new 5-line function `noteKey(text): MetaKey` by length (N-41) | NEW. Do not use `DataKey.parse`: for 32 characters it gives raw-byte `003`, which no other client reads |
| Owner copy write/read | `Keyring.encryptMeta` / `Keyring.decryptMeta` (`N crypto/Keyring.kt:42-56`) | exists (v3 `002` fallback missing, N-48) |
| Participant copy write/read | `Keyring.encryptFor(publicKey, json)` / `Keyring.decryptShared(text)` (`N crypto/Keyring.kt:64-102`), OAEP SHA-512/MGF1 SHA-512 | exists, exact match |
| Own public key | `Keyring.publicKey` (base64 SPKI) | exists |
| Tag names | `Keyring.encryptMeta` / `decryptMeta` | exists |
| JSON carriers | `org.json.JSONObject` | exists (N-84) |
| Caller user ID | `userId` from `/v3/user/info` (`N api/Auth.kt:51-55`) | exists; it is 0 when that call fails (N-89) |
| Note UUID | `java.util.UUID.randomUUID()` | exists |
| GET with array `data` (N-10, N-22) | `Api.get` returns only an object (`N api/Api.kt:68, 74-75`) | NEW: `getArray`, or a GET form of `postLarge` for a large list |
| POST with array `data` (N-29) | `Api.postArray` (`N api/Api.kt:71-72`) | exists |
| `notes-write` lock | `drive/WriteLock` | change: resource as a parameter (N-37) |
| HTML strip, entity decode, checklist read/write | none | NEW, small (N-51..N-54) |

---

## 4. Content formats

N-50 `text`, `md`, `code`: the content is plain UTF-8 text with `\n` line ends. `md` is Markdown
source. `code` is source text; the apps choose the syntax colours from the title's extension
("script.py" → `py`, 1-10 alphanumeric characters with a letter) (`M features/notes/utils.ts:242-264`).
Export names: text `.txt`, md `.md`, rich and checklist `.html` (`M features/notes/utils.ts:236-300`).

N-51 `rich`: HTML of the Quill 1.3.7 editor (`root.innerHTML`), stored as is. The current apps use
Quill 2 and convert its output back to the Quill 1 form before each save
(`SH dom/quillCompat.ts:1-26, 151-188`; `M components/textEditor/richText/dom.tsx:53-71`;
`W features/notes/components/editor/richTextEditor.logic.ts` `serializeRichEditor`).
- Blocks: `<p>…</p>`, `<h1>`..`<h6>`, `<blockquote>`, empty line = `<p><br></p>`. No `\n` between
  blocks.
- Lists, Quill 1 form (type on the container): `<ul><li>…</li></ul>` (bullet),
  `<ol><li>…</li></ol>` (numbered), `<ul data-checked="true|false"><li>…</li></ul>` (check list
  inside a rich note). Consecutive items of the same kind share one container. Empty item
  `<li><br></li>`. Indent: `class="ql-indent-N"` on `<li>` (`SH dom/quillCompat.ts:31-111`).
- Code block: `<pre class="ql-syntax" spellcheck="false">line1\nline2\n</pre>`, text HTML-escaped
  (`SH dom/quillCompat.ts:113-136`).
- Never write the Quill 2 forms `<li data-list="…">` or `ql-code-block-container`: Quill 1 clients
  (old web, desktop) misread them.
- Cross-client allow list (all clients sanitize with it): tags `p strong em u a h1-h6 code ol ul li
  blockquote pre br span div`; attributes `href target rel src alt class style`
  (`SH dom/richTextSanitizer.ts:5-32`). Quill formats: bold, italic, underline, link, header,
  blockquote, code-block, code, list, indent, align, color, background, size, font, direction
  (`:34-52`). VERIFY the exact Quill 1 markup of align/size/font (classes `ql-align-*`,
  `ql-size-*`, `ql-font-*`), color (inline `style`) and links (`target="_blank"`,
  `rel="noopener noreferrer"`): these are Quill 1 defaults, not visible in these sources.
- Minimal safe writer for Nubo: each line → `<p>` + escaped text (`&` `<` `>`) + `</p>`; empty line →
  `<p><br></p>`. Read: sanitize with the allow list, or show text only. Never run note HTML in a
  WebView with JavaScript.

N-52 `checklist`: HTML, exact shape of the apps' writer (`SH checklistParser.ts:805-832`; Rust CLI
writes the same, `CLI checklist.rs:1-3, 30-63`):
- Items in order. A run of items with the same state is one `<ul data-checked="false">` or
  `<ul data-checked="true">` block. A new block starts when the state changes.
- Item: `<li>` + text + `</li>`. Text is trimmed, then `&` → `&amp;`, `<` → `&lt;`, `>` → `&gt;`
  (quotes are not escaped) (`:409-411`). Empty text → `<li><br></li>`.
- No newline, no other tag, no item ID in the HTML (IDs are made on each read).
- Example: `<ul data-checked="false"><li>Milk</li><li><br></li></ul><ul data-checked="true"><li>Eggs &amp; ham</li></ul>`.
- Zero items → `""`. The editors always keep one row, so they write
  `<ul data-checked="false"><li><br></li></ul>` for an empty list
  (`SH checklistParser.ts:838-842`; `TS notes/index.ts:463-466, 499-505`).
- Reader rules (`SH checklistParser.ts:461-505, 767-787`; `CLI checklist.rs:100-220`): a `<ul>`
  without `data-checked` is unchecked; single quotes are valid; text inside `<li>` loses inner tags,
  then strict entities (`&…;`) are decoded, then it is trimmed; old mobile builds wrote unescaped
  text; nested lists exist in old data. Text that is not checklist HTML gives zero items.

N-53 Type change does not convert content. The current apps send the same content with the new
type (`M features/notes/components/note/menu.tsx:384-396`; `R notes.rs:702-757`). Rust also re-sends
the OLD preview (`R notes.rs:726-730`). The old TS SDK removed tags from rich/checklist content and
made a new preview (`TS notes/index.ts:545-562`). Effects: text → checklist shows one empty row in
the apps, and the next checklist edit overwrites the text; checklist/rich → text shows the HTML.
Nubo can convert on purpose (for example each non-empty line → one unchecked item, as
`CLI checklist.rs:30-63` does) or warn the user.

N-54 Preview: plain text, max 128 characters, made by the client from the content
(`SH notes.ts:5, 53-79`; `M features/notes/notesContent.ts:40-48`; same rules in
`CLI content.rs:80-112`):
- `rich`: the part before the first `<p><br></p>`; if there is none, the part before the first `\n`
  (Quill HTML has no `\n`, so this is all of it). Remove tags, decode entities, cut to 128.
  `<p>a</p><p>b</p>` gives `ab` (no separator).
- `checklist`: the text of the first item that is not empty after the reader rules of N-52; cut to
  128.
- `text`, `md`, `code`: the first line (before `\n`), tags removed (also for plain text: the apps
  use the same tag stripper), cut to 128. Entities are not decoded.
- Empty content → `""`. Encrypt the preview always, also `""` (N-45).
- JS cuts at 128 UTF-16 units; the Rust CLI at 128 code points. Both are valid. Do not end the cut
  inside a surrogate pair.
- Tag removal: the apps use the `striptags` library. A `<[^>]*>` removal is close enough for a
  preview. The preview is display text only.

N-55 Size limit. VERIFY (server value unknown; sources disagree):
- TS SDK: refuses when the ENCRYPTED content text has length ≥ 1 048 575
  (`TS constants.ts:49`; `TS notes/index.ts:608-610`).
- Web app: refuses when the PLAINTEXT content has more than 1 048 512 UTF-8 bytes
  (`W features/notes/hooks/useNoteEditor.logic.ts:6-33`), and says the server rejects more.
- Rust SDK: no check.
- Safe rule for Nubo: encrypt, then refuse if the encrypted text length ≥ 1 048 575. This allows a
  JSON plaintext of about 786 400 bytes (`002`: 15 + 4·⌈(n+16)/3⌉ characters).

---

## 5. Sequences

N-60 Create, protocol minimum (Rust `create_note`, `R notes.rs:607-666`; TS
`TS notes/index.ts:398-437`):
1. Make `uuid` (UUID v4) and the note key (N-40).
2. Title: the user's text. Rust default: UTC date `"%a %b %d %Y %X"` (example
   `Fri Nov 20 2026 14:03:05`). The mobile app asks for a title and refuses an empty one
   (`M features/notes/components/notesActions.ts:21-34`; `M features/notes/notesContent.ts:105-108`).
3. `metadata` = account-encrypt `{"key": k}` (N-42). `title` = note-key-encrypt `{"title": t}`.
4. Take the `notes-write` lock.
5. N-12 `POST /v3/notes/create`.
6. N-31 `POST /v3/notes/participants/add` with `contactUUID: "owner"`, own public key,
   `permissionsWrite: true`.
7. Release the lock.
The new note has type `text`, preview `""` and content `""` on the server. A first content write is
NOT required: right after create, N-11 gives `content: ""` and Rust reads it as empty text
(`R notes.rs:688-692`; `RT:74-77`).

N-61 Create, as the official mobile app does it (`M features/notes/notes.ts:121-150, 182-220`):
N-60 steps 1-7; then, only if the type is not `text`, N-15 with content `""` (as
`{"content":""}`) and preview `""`; then N-13 with the first content (`""` for a new empty note)
and its preview; then N-27 if the user created the note from a tag view. The web app does the same
type step (`W features/notes/lib/actions.ts:31-70`). If that type step fails for an imported note,
the web app deletes the note (`W features/notes/lib/import.ts:59-95`).

N-62 Edit content: preview from content (N-54); encrypt both (N-45); check size (N-55); lock;
N-13 with the CURRENT type of the note; unlock. Answer `timestamp` is the new `editedTimestamp`
(`R notes.rs:759-795`). VERIFY what the server does if `type` differs from the stored type.

N-63 Edit title: encrypt `{"title": t}`; lock; N-14; unlock (`R notes.rs:797-819`). The apps do not
send an empty title (`M features/notes/notesContent.ts:105-108`).

N-64 Change type: N-15 with the content (N-53) and a preview, both encrypted. The apps skip the
call when the type is the same (`M features/notes/notesContent.ts:80-82`). Write access needed.

N-65 State. From live tests (`RT:97-157`):
- `trash` and `archive` exclude each other: trash of an archived note → trashed, not archived;
  archive of a trashed note → archived, not trashed. Restore clears both.
- Delete works on a note in any state (`RT:12-15, 119-121`).
- Apps allow trash and archive to the owner only; a participant gets "leave" = N-32 with its own
  user ID (`M features/notes/components/note/menu.tsx:232-239`;
  `W features/notes/lib/actions.ts:186-193`). The mobile app offers delete only in the trash view.
- VERIFY: automatic delete of old trashed notes; whether `favorite`/`pinned` are per user or per
  note on a shared note; whether the server lets a participant trash or delete.

N-66 History (`RT:299-354`):
- After create + 3 content edits the list has 4 entries: the first has `""` content and preview;
  the last equals the current content. So each content write adds one entry.
- Rust sorts oldest first; the apps show newest first, tie broken by the higher `id`
  (`SH notes.ts:81-91`). The TS SDK hides entries with empty content or preview
  (`TS notes/index.ts:864-867`).
- Restore (N-30) replaces the content on the server. Rust then copies preview, type and editor from
  the entry (`R notes.rs:965-968`). VERIFY: restore of the type; whether restore and type change
  add history entries.

N-67 Tags:
- Create: Rust and TS first read the tag list and return an existing tag with the same name
  (no duplicate names) (`R notes.rs:1026-1036`; `TS notes/index.ts:1049-1058`). TS rename refuses a
  name that exists (`TS notes/index.ts:1088-1093`).
- Names use the account key, so tags are private to each account. VERIFY that the server gives a
  participant only its own tags in `Note.tags`.
- VERIFY: delete of a tag also removes it from its notes.

N-68 Permissions: write = owner, or participant with `permissionsWrite: true`
(`SH notesSelectionFlags.ts:139-147`). The server enforces it: a read-only participant's N-13
fails (`RT:466-472, 512-517`). Apps hide type change, rename and history restore without write
access (`M features/notes/components/note/menu.tsx:371, 425, 561`).

N-69 Conflicts: N-13 has no revision field, so the last write wins. The apps keep the hash of the
content that an edit started from, and before a push they compare it with the current server
content to find a lost remote edit (`SH notesOutbox.ts:1-33`). Nubo: before a push, read N-11 and
compare `editedTimestamp`/`editorId` (or the content) with the base; on a change, keep both (for
example save the local text as a new note).

---

## 6. Test data

N-70 filen-rs has NO pinned known-answer vector for note crypto (no note entry in
`R crypto/known_answer_tests.rs`). Fixed test data that exists:
- 32-character key `abcdefghijklmnopqrstuvwxyzABCDEF` and 64-hex key
  `00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff`
  (`R crypto/notes_and_chats.rs:294, 332-334`).
- Fixed nonce `012345678901` and Latin-1 plaintext bytes `{"content":"caf` 0xE9 `"}`; expected
  decrypt result `café` (`:343-364`).
- Round-trip cases: lone surrogate escapes → U+FFFD, emoji pair intact, `""` → `""`, bad JSON →
  error, tampered text → error (`:300-404`).
- Owner fallback cases: owner with no participant entry reads `metadata`; a non-owner gets an error
  (`R notes.rs:1552-1579`, master key `"a"` × 32).
- The RSA test key is made at random for each run (`R fs/meta_recovery.rs:157-162`). No fixed RSA
  vector for notes. Use the `RSA_FILEN` vector of `protocol.md` §12 for the OAEP parameters.
- A note key is a 32-character key text, the same input as a master key. So the pinned `002`
  vector of `protocol.md` §12 also checks the note-key cipher.

N-71 Vectors made for this document (not from filen-rs). Tool: JDK 21 `javax.crypto`, written from
F-34/F-35. The same code decrypts the pinned `002` and `003` vectors of `protocol.md` §12 to
`{"name":"a","size":1}`, so the code is correct for those. Fixed nonces are for tests only; never
use a fixed nonce in the app.

| ID | Key | Nonce | Plaintext | Ciphertext |
|---|---|---|---|---|
| V1 | `abcdefghijklmnopqrstuvwxyzABCDEF` | `012345678901` | `{"title":"Shopping list"}` | `002012345678901Q5jxXt2jUXNDOn+gYb3gJpQjAGzp7de272xPLNCFi7+IupaEQdUG534=` |
| V2 | same | same | bytes `{"content":"caf` 0xE9 `"}` (Rust test input; read result `café`) | `002012345678901Q5jmWMe7UT8NOhbqbaz2ptg5t6l5EaMOYoSvubYKwcU46A==` |
| V3 | same, as account master key | same | `{"key":"0123456789abcdefghijklmnopqrstuv"}` | `002012345678901Q5juUtDtDnNJKR77OvimeMJ9QWLj+sby9YKiSIV4tY0Dkb3W9CreTRpTWRwGX8oWjthRjnBHqBvwQQ==` |
| V4 | hex `00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff` | hex `000102030405060708090a0b` | `{"title":"Shopping list"}` | `003000102030405060708090a0bbTrBXzQlqJTzGTyUXqUURgBShxKSdw+9REF6guBFWUWpmMT80hJlGuw=` |
| V5 | `abcdefghijklmnopqrstuvwxyzABCDEF` | `012345678901` | `{"content":"<ul data-checked=\"false\"><li>Milk</li></ul><ul data-checked=\"true\"><li>Eggs &amp; ham</li></ul>"}` | `002012345678901Q5jmWMe7UT8NOhbqMrj8b54lVGGt/cvx8YGuRtNI+oUNjb/B23yVB1RHvquEXgbip37ZWDJsSklPzcwmuAjzFOMYNSDqz/S0XY6Bn7XSA18EAvkE+l2TSJ9BkdIT3A9bNSKgE3UMG+uGuYN8MgM7yLLvdy0o9J83oGsViMPU/RUq` |

Unit tests for Nubo: decrypt V1-V5; checklist write of N-52's example; preview cases of
`CLI content.rs:165-197` (facts: `"first\nsecond"` → `first`; `<p>one</p><p><br></p><p>two</p>` →
`one`; checklist `<li> </li><li><b>first</b> item</li>` → `first item`; `<ul></ul>` → `""`; 129 × `ä`
→ 128 × `ä`).

---

## 7. Pitfalls (what keeps notes readable for the official apps)

N-80 Never change the key of a note. No re-key endpoint exists, and each participant has a copy
wrapped with the old key.

N-81 Always add the owner participant (N-60 step 6) under the same lock as create. TS-SDK clients
reject a note in which the user is not a participant (`TS notes/index.ts:81-87`); Rust has a
fallback only for the owner. If step 6 fails, try it again; if it cannot succeed, delete the note.

N-82 Encrypt note fields with the note key in the format of its length (N-41). A 32-character key
used as raw AES bytes gives `003` text that no other client reads.

N-83 Use the exact JSON carriers: `{"key"}`, `{"title"}`, `{"preview"}`, `{"content"}`,
`{"name"}`. Rust fails on a missing field.

N-84 JSON escapes: serde_json and `JSON.stringify` do not escape `/`. Android `org.json` writes `\/`.
Readers accept both. It adds 1 byte for each `/` (for example each `</li>`) to the size of N-55.

N-85 Send `type` in N-13 and N-15 as one of the five values; in N-13 send the current type.

N-86 Checklist content must have the exact N-52 shape. Rich content must be Quill 1 HTML within
the allow list (N-51). Other HTML can show wrong, or the old web client can rewrite it on open.

N-87 The preview is the only text the note lists show. Always send it, plain text, ≤ 128 characters,
made by N-54. A wrong preview does not damage the content.

N-88 Treat `""` as empty for `preview`, `content` and history entries. Do not decrypt it, and do not
send `""` for `title`.

N-89 Use the real user ID for key lookup (N-44). Nubo stores 0 when `/v3/user/info` fails
(`N api/Auth.kt:51-55`); with 0 no participant matches and owner detection fails. Get the ID first.

N-90 Take the `notes-write` lock for writes (N-37). It stops races with other clients of the same
user. Server enforcement is unknown (as F-57). VERIFY.

N-91 Size: check N-55 before a write. A refused push can block the note in other apps' queues
(`W features/notes/hooks/useNoteEditor.logic.ts:6-9`).

N-92 Type change keeps the content (N-53). If Nubo converts content, it must write valid content
for the new type.

N-93 Sanitize rich and checklist HTML before display (N-51). Note content of a shared note comes
from another user and is untrusted.

N-94 Unknown note types and unknown fields: keep the note, show it read-only, and do not write it.

---

## 8. Open points (VERIFY)

1. Server size limit of `content` (N-55).
2. v3 accounts with `002` metadata written by TS clients (N-48).
3. Latin-1 and lone-surrogate data from old clients; `org.json` behaviour on device (N-47).
4. `type` in N-13 different from the stored type (N-62).
5. History: entries for type change and restore; restore of the type (N-66).
6. Trash auto-delete; per-user `favorite`/`pinned`; participant trash/delete (N-65).
7. Tag delete and notes; tags of participants (N-67).
8. Quill 1 markup of align, size, font, color and links (N-51).
9. Server enforcement of the `notes-write` lock (N-90).
