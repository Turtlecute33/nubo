# Filen chat: research for a clean-room Kotlin client (Nubo)

Date: 2026-11-20. Method: reading of public source code only. No request was sent to a Filen
server. No code was copied: this file gives facts in new words. JSON field names, endpoint paths
and constants are facts and are exact.

Scope: chat and the part of contacts that chat needs. Notes are out of scope.

## 0. Conventions and sources

- `C-nn` is a finding ID of this file. `F-nn` is a finding ID of `protocol.md`.
- `VERIFY` marks a point that no source shows directly (usually server behaviour).
- Paths are relative to `https://gateway.filen.io` (F-12). Every answer has the envelope of F-82
  (`{status, message, code, data}`). "data" below is the `data` value.
- Types: `uuid` = UUID v4 text, lowercase with hyphens. `ms` = integer milliseconds since the epoch.
  `u64` = JSON number. `enc` = encrypted metadata text (`002`, `003` or `U2FsdGVk…`, F-34..F-36).
  `rsa` = base64 (standard, padded) of an RSA-OAEP ciphertext.
- Readers in the Rust SDK accept a timestamp in seconds or in ms (it picks the scale nearer to now,
  `filen-types/src/serde/time.rs:196-221`), numbers as integer or float, and booleans as `true`/`false`
  or `0`/`1` (`filen-types/src/serde/boolean.rs:1-53`). Do the same.

| ID | Source | Revision |
|---|---|---|
| S-RS | filen-rs (`filen-sdk-rs`, `filen-types`). PRIMARY. | commit e37de749808195677ec809434aaa63af83ead9e7 (2026-11-20). Newer than the S-RS commit of `protocol.md`. |
| S-TS | filen-sdk-ts (older engine) | commit 6f272ffac11802d5d1a64fb8796871b402db6a71 |
| S-APP | filen-ts (current official web and mobile apps) | commit 22f85698eef6d4d653fb7ceed9f99dae5b2177d7 (2026-11-20) |

Path prefixes in citations:

| Prefix | Means |
|---|---|
| `T/` | S-RS `filen-types/src/api/v3/` |
| `R/` | S-RS `filen-sdk-rs/src/` |
| `RT/` | S-RS `filen-sdk-rs/tests/` |
| `TS/` | S-TS `src/` |
| `M/` | S-APP `packages/filen-mobile/src/` |
| `W/` | S-APP `packages/filen-web/src/` |
| `SH/` | S-APP `packages/filen-shared/src/` |
| `N/` | Nubo `app/src/main/kotlin/app/nubo/` |

Licence: S-RS, S-TS and S-APP are AGPL-3.0 (F-01..F-03). Read for facts only.

---

## 1. Model in short

- A conversation has one owner and a list of participants. The owner is also a participant.
- Each conversation has one symmetric key (the "chat key"). The key never changes.
- The server keeps one copy of the chat key for each participant, encrypted with that
  participant's RSA public key (`participants[].metadata`). The owner has one more copy, encrypted
  with the account metadata key (`ownerMetadata`).
- Message text and conversation name are encrypted with the chat key. Sender, times, uuids,
  participant emails and the reply link are plain text for the server.
- Only contacts can be added to a conversation (by contact uuid).
- Live updates come only through the WebSocket. There is no push notification path in the official
  mobile app (no push code found in `M/`).

---

## 2. Conversations

C-01 List conversations. `GET /v3/chat/conversations`, no body. Data: array of Conversation.
Source: `T/chat/conversations/mod.rs:22-62`, `R/api/v3/chat/conversations/mod.rs:14-16`,
`TS/api/v3/chat/conversations.ts:3-25,62`.

| Field | Type | Note |
|---|---|---|
| `uuid` | uuid | |
| `lastMessageFull` | Message (C-10) or null or absent | Newest message. Rust reads this field. |
| `ownerId` | u64 | User ID of the owner. |
| `ownerMetadata` | enc or null | Owner copy of the key (C-33). |
| `name` | enc or null | Empty or null = no name (C-36). |
| `participants` | array of Participant | |
| `muted` | bool | Mute state of the current user. |
| `createdTimestamp` | ms | |
| `lastFocus` | ms or null or absent | Current user's focus time (C-16). `0` or null = never. |

The older TS type also names `lastMessageSender` (number), `lastMessage` (enc or null),
`lastMessageTimestamp` (ms) and `lastMessageUUID` (uuid or null). VERIFY that the server still
sends them. Rust does not read them. Use `lastMessageFull`.

Participant:

| Field | Type | Note |
|---|---|---|
| `userId` | u64 | |
| `email` | str | |
| `avatar` | str or null | A URL. Host: VERIFY. |
| `nickName` | str, may be absent | Empty = no nickname. |
| `metadata` | rsa | The chat key, wrapped for this participant (C-32). |
| `permissionsAdd` | `0`/`1` or bool | Rust test: an added participant reads as `true` (`RT/chat_tests.rs:184-214`). Meaning on the server: VERIFY. |
| `addedTimestamp` | ms | |
| `appearOffline` | bool | |
| `lastActive` | ms | Presence data of that user. |

- Rust sorts the list by `lastMessageFull.sentTimestamp` (or `createdTimestamp`), oldest first
  (`R/chats.rs:530-534`). The apps show newest first; a chat with no message goes last, ties break
  on the uuid (`SH/chatList.ts:12-23`).
- The apps list a conversation only when it has a last message or the user owns it
  (`SH/chatList.ts:25-29`). So an invited user sees a new chat only after the first message.
- Display name: the decrypted name; if empty, the names of the other participants
  (`M/lib/decryption.ts:24-37`).

C-02 Create conversation. `POST /v3/chat/conversations/create`.
Source: `T/chat/conversations/create.rs:9-25`, `R/chats.rs:581-678`, `TS/chats/index.ts:195-241`.

| Request field | Type | Value |
|---|---|---|
| `uuid` | uuid | New random v4, made by the client (`R/chats.rs:582`). |
| `metadata` | rsa | `{"key":<chat key>}` wrapped with the creator's own public key (C-32). |
| `ownerMetadata` | enc | `{"key":<chat key>}` encrypted with the account metadata key (C-33). |

Data: `{"timestamp": ms, "uuid": uuid}`.
- The creator becomes owner and first participant.
- Then the client adds each other user with C-03, one call per user.
- If one add fails, Rust deletes the new conversation (C-07) so that no half-made conversation
  stays (`R/chats.rs:648-659`).
- The server rate-limits this call with code `rate_limited` when many conversations exist
  (`RT/chat_tests.rs:8-13,95-153`). Numbers: VERIFY.

C-03 Add participant. `POST /v3/chat/conversations/participants/add`.
Source: `T/chat/conversations/participants/add.rs:6-25`, `R/chats.rs:545-579,856-874`,
`TS/chats/index.ts:497-526`.

| Request field | Type | Value |
|---|---|---|
| `uuid` | uuid | Conversation. |
| `contactUUID` | uuid | Exact spelling `contactUUID`. The `uuid` of the contact entry from C-20. Not the user ID, not a request uuid. |
| `metadata` | rsa | `{"key":<chat key>}` wrapped with the contact's public key. |

Data: `{"timestamp": ms, "lastActive": ms, "appearOffline": bool}`. `timestamp` = time of add.
- A repeat call for a user who is already a participant answers success
  (`R/api/v3/bulk/batched.rs:11-12`).
- Optional bulk form: `POST /v3/bulk/chat/conversations/participants/add`
  `{"uuid", "items": [{"contactUUID", "metadata"}]}` (`T/bulk/chat/conversations/participants/add.rs:9-27`).
  Not needed.
- Official UI offers "add" to the owner only (`M/features/chats/components/list/chat/menu.tsx:157-170`).

C-04 Remove participant. `POST /v3/chat/conversations/participants/remove`.
Request: `{"uuid": uuid, "userId": u64}`. No data. Source: `T/chat/conversations/participants/remove.rs:5-13`,
`R/chats.rs:876-894`. Official UI: owner only (`M/features/chats/screens/chatParticipants.tsx:42`).
The chat key does not change after a removal (C-34).

C-05 Rename. `POST /v3/chat/conversations/name/edit`. Request: `{"uuid": uuid, "name": enc}`
(C-36). No data. Source: `T/chat/conversations/name/edit.rs:5-11`, `R/chats.rs:680-705`.
Official UI: owner only (`M/features/chats/components/list/chat/menu.tsx:157-170`). Server rule: VERIFY.

C-06 Leave. `POST /v3/chat/conversations/leave`. Request: `{"uuid": uuid}`. No data.
The owner cannot leave. Rust refuses it before the call (`R/chats.rs:940-954`); TS says the same
(`TS/chats/index.ts:630-644`). Source: `T/chat/conversations/leave.rs:5-11`.

C-07 Delete conversation. `POST /v3/chat/conversations/delete`. Request: `{"uuid": uuid}`. No data.
Source: `T/chat/conversations/delete.rs:5-11`, `R/chats.rs:707-715`. Official UI: owner deletes, others
leave (`M/features/chats/chatSelectors.ts:25-30`). Server refusal for a non-owner: VERIFY.

C-08 Mute. `POST /v3/chat/mute`. Request: `{"uuid": uuid, "mute": bool}`. No data. The state is
per user and comes back as `muted` in C-01. Source: `T/chat/mute.rs:5-12`, `R/chats.rs:975-989`.
No socket event tells other devices; the mobile app re-reads the list after 30 s for this reason
(`M/features/chats/queries/useChats.query.ts:11-13`).

---

## 3. Messages

C-10 Message object (in C-01 `lastMessageFull` and in C-11 data).
Source: `T/chat/messages.rs:22-62`, `TS/api/v3/chat/messages.ts:3-23`.

| Field | Type | Note |
|---|---|---|
| `conversation` | uuid | |
| `uuid` | uuid | Message ID. |
| `senderId` | u64 | |
| `senderEmail` | str | |
| `senderAvatar` | str or null | |
| `senderNickName` | str, may be absent | Empty = none. Rust replaces avatar and nickname with the participant values (`R/chats.rs:479-500`). |
| `message` | enc | C-35. |
| `replyTo` | object, may be absent | See below. |
| `embedDisabled` | bool | C-15. |
| `edited` | bool | |
| `editedTimestamp` | ms | `0` when not edited. |
| `sentTimestamp` | ms | Set by the server. |

`replyTo` object: `uuid`, `senderId`, `senderEmail`, `senderAvatar`, `senderNickName`, `message`
(enc, same chat key). The server fills it from the replied message. Rules for a reader:
- Rust reads `replyTo` as "no reply" when it is absent or does not parse (`T/chat/messages.rs:29-33`,
  `filen-types/src/serde/option.rs:21-31`). A uuid of `""` does not parse.
- TS decrypts `replyTo.message` only when `replyTo.uuid` and `replyTo.message` are not empty
  (`TS/chats/index.ts:446-452`). So the server can send an object with empty strings. VERIFY.
- Whether `replyTo.message` shows the edited text, or what it shows after a delete: VERIFY.

C-11 Messages of a conversation (one page). `POST /v3/chat/messages`.
Source: `T/chat/messages.rs:8-20`, `R/chats.rs:459-515`, `TS/chats/index.ts:415-484`.

| Request field | Type | Value |
|---|---|---|
| `conversation` | uuid | |
| `timestamp` | ms | Cursor: the server returns messages sent before this time. |

Data: array of Message (C-10).
- First page: the apps send now + 1 h (`M/features/chats/queries/useChatMessages.query.ts:39-43`,
  `W/features/chats/queries/chatMessages.ts:28,72-76`). Rust sends now + 1 day (`R/chats.rs:459-462`).
  TS sends now + 1 h (`TS/chats/index.ts:436`). Use a time in the future so that clock skew does
  not hide new messages.
- Older page: cursor = `sentTimestamp` of the oldest message that the client has
  (`M/features/chats/components/chat/messages.tsx:173-226`, `W/features/chats/components/thread/messageThread.tsx:695-725`).
- End: a page with no messages. Both apps stop there.
- Order from the server: newest first (TS doc, `TS/chats/index.ts:415-416`). Rust sorts oldest first,
  ties by uuid (`R/chats.rs:502-509`). Sort on the client; do not trust the server order.
- Page size: set by the server; no client sends a size. Value: VERIFY.
- Cursor strict (`<`) or not (`<=`): VERIFY. The mobile app appends older pages with no dedupe
  (`M/features/chats/store/useChats.store.ts:194-204`); the web app dedupes by uuid
  (`W/features/chats/queries/chatMessages.ts:238-249`). Dedupe by uuid. Two messages with the same
  `sentTimestamp` at a page border can be lost with a strict cursor (VERIFY).

C-12 Send. `POST /v3/chat/send`. Source: `T/chat/send.rs:6-26`, `R/chats.rs:717-763`,
`TS/api/v3/chat/send.ts:40-61`.

| Request field | Type | Value |
|---|---|---|
| `conversation` | uuid | |
| `uuid` | uuid | New random v4, made by the client. |
| `message` | enc | C-35. |
| `replyTo` | str | uuid of the replied message, or `""` for none. Rust writes `""`, not null (`filen-types/src/serde/uuid.rs:32-43`). |

Data: `{"timestamp": ms}` = the `sentTimestamp`.
- Size: TS refuses an encrypted `message` of 65 536 characters or more (`TS/constants.ts:50`,
  `TS/chats/index.ts:383-385`). The web app limits the text to 65 472 UTF-16 units
  (`W/features/chats/lib/composer.logic.ts:11-14`). Rust has no check. Server limit: VERIFY.
  A safe rule: keep the encrypted text below 65 536 characters (JSON plaintext at most about
  49 100 UTF-8 bytes, see C-74).
- The mobile app sends `text.trim()` and refuses blank text
  (`M/features/chats/components/chat/input/index.tsx:108,239`).
- Repeat of the same `uuid`: server behaviour VERIFY. The mobile app treats a send as done once the
  call returns, and never sends again after that (`M/features/chats/chats.ts:86-90`).

C-13 Edit. `POST /v3/chat/edit`. Request: `{"conversation": uuid, "uuid": uuid, "message": enc}`.
Data: `{"timestamp": ms}` = new `editedTimestamp`. Source: `T/chat/edit.rs:6-21`, `R/chats.rs:769-799`.
Official UI: own messages only (`M/features/chats/components/chat/message/menu.tsx:38,133-160`).

C-14 Delete message. `POST /v3/chat/delete`. Request: `{"uuid": uuid}` (message uuid only, no
conversation). No data. Source: `T/chat/delete.rs:5-11`, `R/chats.rs:801-826`. Official UI: own
messages only (same menu file). If the deleted message was the newest, Rust reads C-11 again to
find the new last message (`R/chats.rs:816-823`).

C-15 Disable embed. `POST /v3/chat/message/embed/disable`. Request: `{"uuid": uuid}`. No data.
Sets `embedDisabled` to `true`. Source: `T/chat/message/embed/disable.rs:5-11`. Official UI: sender
only (`W/features/chats/components/thread/messageMenu.logic.ts:21-24,88-93`). Not needed for Nubo.

---

## 4. Read state, unread counts, typing, presence

C-16 Focus time. `POST /v3/chat/lastFocus`. Request:
`{"conversations": [{"uuid": uuid, "lastFocus": ms}, …]}`. No data.
Source: `T/chat/last_focus_update.rs:6-20`, `R/chats.rs:956-973`. Rust sends now, rounded to ms.
The value comes back as `lastFocus` in C-01. It is per user and synced across the user's devices.
Other users do not see it. The older TS SDK also reads it with `GET /v3/chat/lastFocus`
(array of `{uuid, lastFocus}`, `TS/api/v3/chat/lastFocus.ts:39`); Rust does not use that call.

C-17 Mark read. `POST /v3/chat/conversations/read`. Request: `{"uuid": uuid}`. No data.
Source: `T/chat/conversations/read.rs:5-11`, `R/chats.rs:896-902`. It moves the server read marker
that C-18 counts from. Bulk form: `POST /v3/bulk/chat/conversations/read` (not needed).

C-18 Unread counts.
- One conversation: `POST /v3/chat/conversations/unread` `{"uuid": uuid}` → `{"unread": u64}`
  (`T/chat/conversations/unread.rs:5-18`).
- All conversations: `GET /v3/chat/unread` → `{"unread": u64}` (`T/chat/unread.rs:3-9`).
- Rust live test: after one message from the peer and no C-17, the count is 1
  (`RT/chat_tests.rs:282-287`). Own messages do not count. Muted chats: VERIFY.

C-19 How the official apps do "read":
- "Mark as read" = C-16 and C-17 together (`M/features/chats/chats.ts:401-413`). The app does it when
  the user opens a chat and after each own send (`M/features/chats/chats.ts:137-140`).
- The unread badge is computed on the client, not from C-18. A message is unread when the chat is
  not muted, `lastFocus` is set, `sentTimestamp > lastFocus`, the sender is not the user, and the
  sender is not blocked (`SH/chatUnread.ts:29-75`). A chat with `lastFocus` never set shows no
  unread messages.
- So a client that does only C-17 leaves the chat unread on the official apps. Do both.

C-20 Typing. `POST /v3/chat/typing` `{"conversation": uuid, "type": "down" | "up"}`. No data.
`down` = the user types, `up` = the user stopped (`T/chat/typing.rs:5-26`). Other clients get it only
as the socket event `chatTyping`; they hide it after 10 s with no new event
(`M/features/chats/socketHandlers.ts:82-115`). Not needed. Sending it tells the server and the
other participants when the user types. Recommendation: do not send.

C-21 Presence. `POST /v3/chat/conversations/online` `{"conversation": uuid}` → array of
`{"userId": u64, "lastActive": ms, "appearOffline": bool}` (`T/chat/conversations/online.rs:6-26`,
`R/chats.rs:917-938`). Not needed. Which calls update the user's own `lastActive`: VERIFY.

There are no read receipts in Filen chat. No field or event tells a sender that another user read
a message.

---

## 5. Contacts (only what chat needs)

To chat with a user, that user must be a contact: send a request (C-23), the user accepts (C-25),
then the user is in C-22 with a `uuid` and a `publicKey`. The mobile app starts a chat by picking
contacts (`M/features/chats/chatsActions.ts:9-30`).

C-22 List contacts. `GET /v3/contacts`. Data: array of Contact.
Source: `T/contacts/mod.rs:13-34`, `R/connect/contacts.rs:14-16`.

| Field | Type | Note |
|---|---|---|
| `uuid` | uuid | Contact entry ID. Use it as `contactUUID` in C-03. |
| `userId` | u64 | |
| `email` | str | |
| `avatar` | str or null | |
| `nickName` | str, may be absent | Empty = none. |
| `lastActive` | ms | |
| `timestamp` | ms | |
| `publicKey` | str | base64 SPKI DER of the contact's RSA key. Rust requires it, so the server sends it. The older TS type does not list it (`TS/api/v3/contacts.ts:3-11`). |

C-23 Public key by email. `POST /v3/user/publicKey` `{"email": str}` → `{"publicKey": str}`
(base64 SPKI DER). Source: `T/user/public_key.rs:6-17`, `TS/api/v3/user/publicKey.ts:44`. TS uses
it before C-03 (`TS/chats/index.ts:502-506`); Rust uses the key from C-22. Same call as F-78.

C-24 Send request. `POST /v3/contacts/requests/send` `{"email": str}` → `{"uuid": uuid}` (request
ID). Source: `T/contacts/requests/send.rs:7-19`. The receiver accepts with the same uuid
(`test-utils/src/lib.rs:406-420`). Limit: 30 sends per hour per sender, and a send to a user who is
already a contact also counts (`test-utils/src/lib.rs:422-431`). The receiver gets the socket
event `contactRequestReceived` (C-50).

C-25 Answer a request.
- Incoming list: `GET /v3/contacts/requests/in` → array of `{uuid, userId, email, avatar (str or
  null), nickName (str)}`; TS also names `timestamp` (ms) (`T/contacts/requests/in/mod.rs:9-25`,
  `TS/api/v3/contacts/requests/in.ts:3-10`).
- Accept: `POST /v3/contacts/requests/accept` `{"uuid": uuid}` → `{"uuid": uuid}`
  (`T/contacts/requests/accept.rs:5-17`). Whether the answer uuid is the new contact `uuid` of C-22:
  VERIFY. The mobile app reads C-22 again after an accept (`M/features/contacts/contacts.ts:45-66`).
- Deny: `POST /v3/contacts/requests/deny` `{"uuid": uuid}`. No data (`T/contacts/requests/deny.rs:5-11`).
- Count: `GET /v3/contacts/requests/in/count` → data is a bare number
  (`T/contacts/requests/in/count.rs:3-6`). No SDK calls it. VERIFY.

C-26 Own requests. `GET /v3/contacts/requests/out` → array of `{uuid, email, avatar, nickName}`
(`T/contacts/requests/out/mod.rs:9-23`). Cancel: `POST /v3/contacts/requests/out/delete`
`{"uuid": uuid}` (`T/contacts/requests/out/delete.rs:5-11`).

C-27 Other contact calls (not needed for a first chat version):
`POST /v3/contacts/delete {"uuid"}`; `GET /v3/contacts/blocked` → array of
`{uuid, userId, email, avatar, nickName, timestamp}`; `POST /v3/contacts/blocked/add {"email"}` →
`{"uuid"}`; `POST /v3/contacts/blocked/delete {"uuid"}` (`T/contacts/`). The server does not stop
messages from a blocked user (`RT/chat_tests.rs:318-319`). The apps hide them on the client.

---

## 6. Keys and encryption

C-30 Chat key form. The chat key is a text, not raw bytes.
- Both SDKs make 32 random characters for every account version (v1, v2 and v3).
  Rust: alphabet `A-Z a-z 0-9 - _` (`R/crypto/shared.rs:77-91`, `R/crypto/v2.rs:243-248`), and a new
  chat key is always this form (`R/crypto/notes_and_chats.rs:122-126`, `R/chats.rs:583`).
  TS: `generateRandomString(32)` because the metadata version constant is 2
  (`TS/crypto/utils.ts:76-90`, `TS/constants.ts:68-70`, `TS/chats/index.ts:198`).
- A reader must accept two forms (`R/crypto/notes_and_chats.rs:33-43`):
  32 characters → "v2 key", text format `002`; 64 characters → "v3 key", 32 bytes from hex,
  text format `003`. Any other length is an error.
- Recommendation: write the 32-character form. Any alphanumeric alphabet is readable.

C-31 Key JSON. Both wrapped copies hold the UTF-8 JSON `{"key":"<chat key text>"}`
(`R/crypto/notes_and_chats.rs:222-279`, `TS/chats/index.ts:201-212`). Readers take field `key`; TS
fails if it is not a string (`TS/crypto/decrypt.ts:621-628`). Size: 42 bytes (32-char key) or
74 bytes (64-hex key).

C-32 Wrap for a participant (`metadata` in C-02, C-03 and in C-01 participants).
- RSA-OAEP. Hash SHA-512. MGF1 with SHA-512. Empty label. Output base64 standard, padded
  (`R/crypto/rsa.rs:93-112`; TS: `TS/crypto/encrypt.ts:171-213`). This is the share cipher of F-27/F-78.
- Public key: the participant's base64 SPKI DER (C-22 `publicKey` or C-23). For the creator: the
  own public key.
- Account keys are RSA-4096 (F-27). OAEP-SHA-512 then takes at most 382 bytes; C-31 fits.
- OAEP is random: the same key gives a new ciphertext each time. There is no fixed vector.

C-33 Owner copy (`ownerMetadata` in C-02). C-31 JSON, encrypted with the account metadata key, as
for all account metadata (F-37) (`R/chats.rs:585-601`, `R/auth/mod.rs:448-453`):
- v1/v2 account: `002` with the current master key (Rust uses index 0 of the master key list,
  which is the current key, `R/crypto/v2.rs:255-264,296-299`).
- v3 account: `003` with the DEK.
- Rust writes it but never reads it. TS reads it when the user is the owner
  (`TS/chats/index.ts:48-56`, `TS/crypto/decrypt.ts:646-690`). Write a valid one so that TS-based
  clients still work.

C-34 Get the chat key back.
1. In C-01, find the participant whose `userId` is the own user ID.
2. RSA-decrypt its `metadata` with the own private key (C-32 parameters).
3. Parse C-31 JSON. Take `key`. Read its form (C-30).
- Source: `R/chats.rs:304-317`, `R/crypto/notes_and_chats.rs:228-240`. Use this path also for the
  owner (the owner is a participant).
- If the user is not in `participants`, or the decrypt fails, Rust keeps the conversation with no
  key, name or last message (`R/chats.rs:258,285-300`).
- The key is the same for the life of the conversation. Cache it by conversation uuid: this saves
  one RSA-4096 private-key operation for each conversation on each list read.
- No re-key exists. A removed participant keeps the key (no forward secrecy). Do not invent a
  re-key: other clients would not follow it.

C-35 Message encryption.
- Plaintext: UTF-8 JSON `{"message":"<text>"}` (`R/chats.rs:450-455`, `TS/crypto/encrypt.ts:229-240`).
- Key: the chat key. 32-char key → `002` (F-34: AES-256-GCM, key = PBKDF2-HMAC-SHA512(key text,
  key text, 1 round, 32 bytes), 12-character nonce). 64-hex key → `003` (F-35).
- Decrypt: pick the format by prefix. A 32-char key also reads old `U2FsdGVk…` text
  (`R/crypto/v2.rs:134-161`). A 32-char key does not read `003` and a 64-hex key does not read `002`
  (`R/crypto/v2.rs:152-160`, `R/crypto/v3.rs:139-147`).
- An empty `message` value decrypts to empty text (`R/crypto/notes_and_chats.rs:147-149`).
- Missing or non-string field `message`: TS gives empty text (`TS/crypto/decrypt.ts:699-717`); Rust
  gives an error (no text shown).
- Recovery that Rust does for bad writers (`R/crypto/notes_and_chats.rs:151-179`): plaintext that is
  not UTF-8 is read as Latin-1; JSON with an unpaired `\udXXX` escape is read with U+FFFD.

C-36 Conversation name. Plaintext JSON `{"name":"<text>"}`, same key and format as C-35
(`R/chats.rs:443-448`, `TS/crypto/encrypt.ts:349-359`, `TS/crypto/decrypt.ts:970-997`).

C-37 Reply. The sender sends only `replyTo` = the uuid text of the replied message (C-12). There is
no reply data in the encrypted payload. The server returns the replied message in `replyTo`
(C-10); decrypt its `message` with the same chat key.

C-38 Embeds and attachments. Not part of the encrypted payload.
- The text holds URLs. `embedDisabled` is a server flag (C-15).
- The apps resolve Filen public links in the text through the API and show a preview. They never
  fetch other URLs: the comment says that a fetch would give the sender the reader's IP address
  and the time of reading (`M/features/chats/queries/useChatMessageLinks.query.ts:160-178`).
- An attachment is a file that the app uploads to a chat-uploads folder in the user's drive, makes
  a public link for, and puts as a link in the message (`M/features/chats/chats.ts:497-540`).
- Text syntax that the apps render (display only): `@<email>` and `@everyone` mentions, ```` ``` ````
  code blocks, `:shortcode:` emoji, `http(s)://` links, `\n` line breaks
  (`SH/chatMessageSegments.ts:10-42`).

---

## 7. New messages without a socket

C-40 Change counter. `GET /v3/messageIds` → `{"general": u64, "chat": u64, "contact": u64,
"note": u64, "drive": u64, "lock": u64}` (`lock` may be absent) (`T/message_ids.rs:4-30`,
`R/socket/mod.rs:216-220`). Each socket chat event carries a `chatMessageId`
(`T/socket.rs:536-543`). The drive counter goes up with each drive event (`RT/socket_tests.rs:537-600`).
- Use: poll C-40. When `chat` changes, read C-01 and the newest page (C-11) of the open chat.
- Small answer, one call, no decrypt. This is the cheapest signal found.
- VERIFY: that `chat` goes up for every chat event of this user (new message, edit, delete, name,
  participant, typing). The live test only checks that it does not go down
  (`RT/socket_tests.rs:595-599`). The official web app reads only `drive` from it
  (`W/workers/sdk.worker.ts:1408-1410`).
- VERIFY: that `contact` goes up for a new contact request.

C-41 Cost of the other poll options.
- C-01 alone: one call returns all conversations with all participants. Each participant carries a
  684-character `metadata` (4096-bit RSA, base64). Size grows with conversations × participants.
  Each new conversation needs one RSA-4096 decrypt (cache, C-34).
- C-11 newest page: one call per open conversation; one AES-GCM decrypt per message.
- `GET /v3/chat/unread` (C-18): small, but it changes only for new messages from others before
  C-17. It does not show edits, deletes or name changes.
- Every poll tells the server that the app is open, and when. Poll only while a chat screen is in
  the foreground. Stop in the background.

C-42 What only the socket gives (`T/socket.rs:280-304`, events in C-50):
- Typing (`chatTyping`). Not stored; no endpoint returns it.
- Delivery at once, with the chat key in each event (`metadata`), so the socket client needs no list read.
- Changes outside the loaded page: an edit or a delete of an old message is visible to a poller only
  if it reads that page again.
- Read receipts: none exist, also not on the socket.
- Presence: the socket client does not need C-21 polls. A poller can call C-21 (not needed).

C-43 How the official apps load and order messages (to match their result):
- Open a chat: C-11 with now + 1 h. Keep the list sorted by `sentTimestamp`, oldest first
  (`W/features/chats/queries/chatMessages.ts:10-18,30-32`).
- Scroll up: C-11 with the oldest `sentTimestamp`; stop on an empty page (C-11).
- A new read of the newest page is merged by uuid: the page replaces only its own time window, and
  older loaded messages stay when the page reaches them (`W/features/chats/queries/chatMessages.ts:90-117`).
- After a reconnect or app focus: read C-01; read C-11 again for the open chat and for each chat
  whose `lastMessageFull` differs from the cached newest message (uuid, `editedTimestamp`, text,
  `embedDisabled`) (`M/features/chats/chats.ts:432-475`, `M/features/chats/chatSelectors.ts:95-124`).
- An own message: the app shows it at once, then puts the server copy in place when C-12 returns
  (`M/features/chats/chats.ts:71-146`).

---

## 8. Socket events (reference only)

C-50 Chat and contact socket events (`T/socket.rs:280-304`, structs at the lines below). Every chat
event also has `chatMessageId` (u64), every contact event `contactMessageId`. Nubo has no socket
(DESIGN §3, F-18); this list shows what a poller misses.

| Event | Payload | Line |
|---|---|---|
| `chatMessageNew` | `conversation`, Message fields of C-10 except `edited`/`editedTimestamp`, `replyTo`, `embedDisabled`, `sentTimestamp`, `metadata` (rsa: chat key for the receiver) | 990 |
| `chatTyping` | `conversation`, `senderAvatar`, `senderEmail`, `senderNickName`, `senderId`, `timestamp`, `type` | 1033 |
| `chatConversationsNew` | `uuid`, `metadata` (typed enc in Rust; not read), `addedTimestamp`, `ownerId`, `participants` | 1060 |
| `chatMessageDelete` | `uuid` | 1084 |
| `chatMessageEmbedDisabled` | `uuid` | 1227 |
| `chatConversationParticipantLeft` | `uuid` (conversation), `userId` | 1243 |
| `chatConversationDeleted` | `uuid` | 1261 |
| `chatMessageEdited` | `conversation`, `uuid`, `message`, `editedTimestamp`, `metadata` | 1267 |
| `chatConversationNameEdited` | `uuid`, `name`, `metadata` | 1280 |
| `chatConversationParticipantNew` | `conversation`, `userId`, `email`, `avatar`, `nickName`, `permissionsAdd`, `addedTimestamp`, `lastActive`, `appearOffline` | 1337 |
| `contactRequestReceived` | `uuid`, `senderId`, `senderEmail`, `senderAvatar`, `senderNickName`, `sentTimestamp` | 1290 |

---

## 9. Test data

C-60 Known-answer vectors for chat crypto in S-RS: none. `R/crypto/known_answer_tests.rs` has no
chat or note case. Its RSA vectors (OAEP-SHA-512, `RSA_FILEN`, key A) test the same cipher as C-32;
`protocol.md` §12 lists them.

C-61 Fixed test data in S-RS unit tests (`R/crypto/notes_and_chats.rs:281-405`):
- v2 chat/note key text `abcdefghijklmnopqrstuvwxyzABCDEF` (line 294).
- v3 key text `00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff` (lines 332-341).
- `{"message":"hi \udc00"}` (JSON with an unpaired escape) reads as `hi ` + U+FFFD (lines 320-328).
- Latin-1 plaintext `{"content":"caf\xe9"}` with nonce `012345678901` reads as `café` (lines 343-364).
- An empty encrypted string reads as empty text (lines 383-390). A changed last character fails (lines 392-404).
- The live tests (`RT/chat_tests.rs`, `RT/socket_tests.rs:805-1062`) need two real accounts. Not usable.

C-62 Vectors made by this research (not from S-RS). Tool: JDK 21 `javax.crypto`. The same code
first decrypted the `002` and `003` vectors of `protocol.md` §12 correctly. Use them as Kotlin unit
tests (decrypt, then compare; or encrypt with the fixed nonce, then compare).

| Key text | Nonce | Plaintext | Encrypted |
|---|---|---|---|
| `abcdefghijklmnopqrstuvwxyzABCDEF` | `012345678901` | `{"message":"Hello!"}` | `002012345678901Q5joUtq8VTYcOhbqRqj8I5VlAn1SDJl8sQ98qyYa+dJ+4is+` |
| `abcdefghijklmnopqrstuvwxyzABCDEF` | `abcdefABCDEF` | `{"name":"Team"}` | `002abcdefABCDEF5fIEQRSOlGmnRTNX2/ZYosgajT2UJotRxxUkU79xwg==` |
| `abcdefghijklmnopqrstuvwxyzABCDEF` | `ZZZZZZZZZZZZ` | `{"message":"café ☕"}` (UTF-8) | `002ZZZZZZZZZZZZqh+pBD0DkQbIXSpHDUbdtBiclVQ1s5PuL18SLN+AHuOZCM3RoYfQ` |
| `00112233…eeff` (64 hex, above) | hex `000102030405060708090a0b` | `{"message":"Hello!"}` | `003000102030405060708090a0bbTrYUzM6rNGsGVXeebAIQwEUhQO7TwuoU4/zYFQifH816pOA` |

Derived AES key for the 32-char key (PBKDF2-HMAC-SHA512, 1 round, 32 bytes):
`6c136c4710cc5c51d73bbce5ae25333ae26c9d6b4f9542a7cdeb2e637844417c`.
For C-32: test a round trip with a generated RSA-4096 key pair, and decrypt `RSA_FILEN` of
`protocol.md` §12 (proves the OAEP-SHA-512 parameters).

---

## 10. Pitfalls (so that the official apps can read what Nubo writes)

C-70 JSON shapes are exact: `{"message":…}`, `{"name":…}`, `{"key":…}`. Rust fails when the field
is missing; TS shows empty text. Do not add a wrapper or a version field.

C-71 Do not encrypt with the wrong format for the key. A 32-char key needs `002`. In Nubo,
`DataKey.parse(text)` accepts a 32-char text, and `DataKey.encrypt` then writes `003` with the text
bytes as raw key. Rust cannot read that (`R/crypto/v2.rs:152-160`): the message is lost for all
official apps. Use `MasterKey` (or `DataKey.asMasterKey()`) for a 32-char key.

C-72 Set the OAEP parameters explicitly (SHA-512, MGF1-SHA-512). The Android default MGF1 is SHA-1
(`protocol.md` §13). `Keyring` already does this (`N/crypto/Keyring.kt:81-82`).

C-73 Create sends both copies of the key: `metadata` (RSA, own public key) and `ownerMetadata`
(account metadata key). Without the RSA copy, Rust cannot find the key for the owner (C-34).

C-74 Size: encrypted length = 15 + 4 × ceil((n + 16) / 3) characters for `002` (n = JSON bytes).
Below 65 536 characters (TS limit) means n ≤ 49 124 bytes. `{"message":""}` takes 14 of them; JSON
escapes (`\"`, `\\`, `\n`) take more. Check the encrypted length before C-12 and C-13.

C-75 uuids: new random v4 for each conversation and each message, lowercase text
(`java.util.UUID.randomUUID()`). Never reuse a message uuid for a new message.

C-76 No automatic retry of C-12 or C-02. `Api.post` retries on 5xx and 429 by default
(`N/api/Api.kt:65-66,132-143`). A retry after the server stored the message can make a duplicate
or an error (server rule for a repeated uuid: VERIFY). Use `retry = false` and show "not sent" with
a manual retry.

C-77 Timestamps: send ms. Read seconds or ms. `lastFocus` `0` or null = never focused (Rust reads 0
as "none", `filen-types/src/serde/time.rs:1-39`).

C-78 `replyTo`: send `""` when there is no reply (Rust form). Null or absent: VERIFY. Read: absent,
null, or an object with an empty `uuid` all mean "no reply".

C-79 Text encoding: UTF-8. Do not cut a string inside a surrogate pair (Kotlin `String` is UTF-16).
org.json writes valid JSON (it escapes `/` as `\/`); all readers parse JSON, so this is safe.

C-80 Mark read with both C-16 and C-17 (C-19). Use ms, now.

C-81 Rights: only the owner renames, adds, removes and deletes; others leave; only the sender edits,
deletes and disables an embed (official UI rules, C-04..C-07, C-13..C-15). The server check is
VERIFY. Follow the UI rules.

C-82 Do not load `avatar` URLs or URLs in messages by default. Each load gives a host the IP address
and the time. The official apps never fetch external URLs (C-38).

C-83 Blocked users: the server still delivers their messages (C-27). Hide them on the client if
Nubo shows a blocked list.

C-84 Do not send C-20 typing or poll C-21 presence: both expose activity and are not needed.

---

## 11. Nubo primitives for each step

| Step | Nubo primitive | Status |
|---|---|---|
| New chat key (32 chars) | `Bytes.alnum(32)` (`N/crypto/Bytes.kt:14-15`) | Exists. Alphabet `a-zA-Z0-9`; readable by all clients (C-30). |
| New conversation or message uuid | `java.util.UUID.randomUUID().toString()` | Platform. |
| Wrap `{"key":k}` for a user (C-32) | `Keyring.encryptFor(publicKey, json)` (`N/crypto/Keyring.kt:98-102`) | Exists. Takes base64 SPKI, OAEP SHA-512/MGF1 SHA-512. |
| Unwrap own copy (C-34) | `Keyring.decryptShared(metadata)` (`N/crypto/Keyring.kt:64-71`), then org.json `key` | Exists. |
| `ownerMetadata` (C-33) | `Keyring.encryptMeta(json)` (`N/crypto/Keyring.kt:42`) | Exists. `003` + DEK on v3, `002` + current master key on v1/v2. |
| Message or name, 32-char key (C-35, C-36) | `MasterKey(k.encodeToByteArray())` (`N/crypto/Meta.kt:25-48`) | Exists. Encrypts `002`; decrypts `002` and `U2FsdGVk`. |
| Message or name, 64-hex key | `DataKey(k.unhex())` (`N/crypto/Meta.kt:54-71`) | Exists. `003`. |
| Pick the key class from the key text (C-30, C-71) | none | New, small: 32 chars → `MasterKey`; 64 hex → `DataKey`; else error. `DataKey.parse` alone is wrong for 32 chars (it gives `003`). |
| GET with an array in `data` (C-01, C-22, C-25, C-26) | `Api.get` reads only an object (`N/api/Api.kt:68`) | New, small: a GET twin of `postArray` (`N/api/Api.kt:70-72`). |
| POST with an array in `data` (C-11, C-21) | `Api.postArray` | Exists. |
| POST with no retry (C-02, C-12) | `Api.post(path, body, retry = false)` (`N/api/Api.kt:65-66`) | Exists. |
| Bare number in `data` (requests count) | none | Not needed; skip that call. |

Hosts: chat uses only `gateway.filen.io`. No new host. No socket.

DESIGN §3 lists "no chat, no contacts screen" as non-goals. A chat feature changes that decision,
and needs at least C-22 to C-25 (list contacts, send and accept requests).

---

## 12. Open VERIFY list

1. Page size of C-11, and whether its cursor is strict (`<`).
2. Whether `chat` in C-40 goes up for every chat event of the user (and `contact` for requests).
3. Server answer to a repeated message uuid in C-12 (duplicate, error or no-op).
4. Server limit on message size (C-12).
5. `replyTo` shape when there is no reply (absent, null, or empty object) and after a delete or an
   edit of the replied message; whether the server accepts null in C-12.
6. Server rights checks for rename, add, remove, delete conversation, edit, delete message (C-81).
7. Whether C-01 still sends `lastMessage`, `lastMessageSender`, `lastMessageTimestamp`,
   `lastMessageUUID`.
8. Whether the accept answer (C-25) is the new contact uuid of C-22.
9. Whether muted chats count in C-18, and which calls update the own `lastActive` (C-21).
10. Avatar URL host (privacy note for the README if Nubo ever loads avatars).
