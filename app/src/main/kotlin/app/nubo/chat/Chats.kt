package app.nubo.chat

import android.os.SystemClock
import androidx.compose.runtime.Immutable
import app.nubo.api.optText
import app.nubo.crypto.Bytes
import app.nubo.crypto.Keyring
import app.nubo.crypto.MetaKey
import app.nubo.crypto.textKey
import app.nubo.data.Account
import app.nubo.drive.Decode
import app.nubo.drive.Drive
import app.nubo.drive.Drive.Companion.objects
import app.nubo.drive.optBoolLoose
import app.nubo.drive.optLongLoose
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** A Filen user in a chat. Nubo never loads avatars: each load tells a host the IP address (C-82). */
@Immutable
data class Person(val id: Long, val email: String, val nick: String) {
    val name: String get() = nick.ifBlank { email }
}

/** One chat message. [text] is null when it does not decrypt. */
@Immutable
data class Message(
    val uuid: String,
    val sender: Person,
    val mine: Boolean,
    val text: String?,
    val sent: Long,
    val edited: Boolean,
    val reply: Reply?,
) {
    override fun toString() = "Message($uuid)"
}

/** The message that a message answers (C-37). */
@Immutable
data class Reply(val uuid: String, val sender: String, val text: String?)

@Immutable
data class Conversation(
    val uuid: String,
    val name: String,
    /** The participants other than this account. */
    val others: List<Person>,
    val owner: Boolean,
    val muted: Boolean,
    val last: Message?,
    val lastFocus: Long,
    val created: Long,
    val key: String?,
) {
    val title: String get() = name.ifBlank { others.joinToString(", ") { it.name }.ifEmpty { "Only you" } }

    /** The rule of the official apps (C-19): a new message from another user after the last focus. */
    val unread: Boolean get() = !muted && lastFocus > 0 && last != null && !last.mine && last.sent > lastFocus

    val time: Long get() = last?.sent ?: created

    override fun toString() = "Conversation($uuid)"
}

/** A Filen contact: a user who accepted a request (C-22). Only contacts can join a chat. */
@Immutable
data class Contact(val uuid: String, val person: Person, val publicKey: String) {
    override fun toString() = "Contact($uuid)"
}

/** A contact request, to this account ([incoming]) or from it. */
@Immutable
data class ContactRequest(val uuid: String, val person: Person, val incoming: Boolean) {
    override fun toString() = "ContactRequest($uuid)"
}

/**
 * Filen chat (research `chat.md`): conversations, messages, read state and the contacts that a chat needs. No
 * WebSocket: an open chat screen polls a small change counter (C-40). No typing or presence calls (C-84).
 * Chat keys stay in memory only. DESIGN §13.
 */
class Chats(private val drive: Drive, private val account: Account) {
    private val api get() = drive.api

    /** Chat key texts: one RSA-4096 decrypt for each conversation, then memory (C-34). */
    private val keys = ConcurrentHashMap<String, String>()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A change of this app to a chat or a contact: the screens read again. */
    val changed: SharedFlow<Unit> = _changed

    @Volatile
    var cached: List<Conversation>? = null
        private set

    /** The conversations, newest first. Like the official apps: an invited user sees a chat after its first message (C-01). */
    suspend fun conversations(): List<Conversation> = drive.guard {
        val rows = api.getArray("/v3/chat/conversations")
        val me = account.userId()
        val ring = drive.keys()
        withContext(Dispatchers.Default) {
            rows.objects().mapNotNull { runCatching { conversation(it, me, ring) }.getOrNull() }
                .filter { it.owner || it.last != null }
                .sortedWith(compareByDescending<Conversation> { it.time }.thenBy { it.uuid })
        }.also { cached = it }
    }

    fun find(uuid: String): Conversation? = cached?.firstOrNull { it.uuid == uuid }

    /**
     * One page of messages sent before [before] (ms), oldest first (C-11). The first page asks from one hour ahead,
     * so that clock skew does not hide new messages.
     */
    suspend fun messages(c: Conversation, before: Long = System.currentTimeMillis() + 3_600_000): List<Message> = drive.guard {
        val rows = api.postArray("/v3/chat/messages", JSONObject().put("conversation", c.uuid).put("timestamp", before))
        val me = account.userId()
        val key = cipher(c)
        withContext(Dispatchers.Default) {
            rows.objects().mapNotNull { runCatching { message(it, me, key) }.getOrNull() }.sortedWith(compareBy<Message> { it.sent }.thenBy { it.uuid })
        }
    }

    /** Sends [text]. No automatic retry: a second send after a lost answer can make a duplicate (C-76). */
    suspend fun send(c: Conversation, text: String, replyTo: Message?): Message = drive.guard {
        val uuid = UUID.randomUUID().toString()
        val sealed = seal(cipher(c), text)
        val o = api.post(
            "/v3/chat/send",
            JSONObject().put("conversation", c.uuid).put("uuid", uuid).put("message", sealed).put("replyTo", replyTo?.uuid.orEmpty()),
            retry = false,
        )
        val me = Person(account.userId(), account.session?.email.orEmpty(), "")
        val reply = replyTo?.let { Reply(it.uuid, it.sender.name, it.text) }
        Message(uuid, me, true, text, Decode.ms(o.optLongLoose("timestamp")).takeIf { it > 0 } ?: System.currentTimeMillis(), false, reply)
    }

    suspend fun edit(c: Conversation, m: Message, text: String) = drive.guard {
        api.post("/v3/chat/edit", JSONObject().put("conversation", c.uuid).put("uuid", m.uuid).put("message", seal(cipher(c), text)))
        Unit
    }

    suspend fun delete(m: Message) = drive.guard {
        api.post("/v3/chat/delete", JSONObject().put("uuid", m.uuid))
        Unit
    }

    /** Read for this account on all its devices: the focus time of the official apps and the server marker (C-19, C-80). */
    suspend fun markRead(c: Conversation) = drive.guard {
        val now = System.currentTimeMillis()
        api.post("/v3/chat/lastFocus", JSONObject().put("conversations", JSONArray().put(JSONObject().put("uuid", c.uuid).put("lastFocus", now))))
        api.post("/v3/chat/conversations/read", JSONObject().put("uuid", c.uuid))
        Unit
    }

    /**
     * A new chat with [contacts] (C-02, C-03). The key goes to each participant wrapped with that user's public key.
     * If an add fails, the new chat goes away, so that no half-made chat stays.
     */
    suspend fun create(contacts: List<Contact>): String = drive.guard {
        val ring = drive.keys()
        val uuid = UUID.randomUUID().toString()
        val keyJson = JSONObject().put("key", Bytes.alnum(32)).toString()
        val (own, owner, theirs) = withContext(Dispatchers.Default) {
            Triple(Keyring.encryptFor(ring.publicKey, keyJson), ring.encryptMeta(keyJson), contacts.map { it to Keyring.encryptFor(it.publicKey, keyJson) })
        }
        api.post("/v3/chat/conversations/create", JSONObject().put("uuid", uuid).put("metadata", own).put("ownerMetadata", owner), retry = false)
        try {
            for ((contact, sealed) in theirs) {
                api.post("/v3/chat/conversations/participants/add", JSONObject().put("uuid", uuid).put("contactUUID", contact.uuid).put("metadata", sealed))
            }
        } catch (e: Exception) {
            withContext(NonCancellable) { runCatching { api.post("/v3/chat/conversations/delete", JSONObject().put("uuid", uuid)) } }
            throw e
        }
        changed()
        uuid
    }

    /** The owner names the chat; an empty name shows the participants (C-05, C-36). */
    suspend fun rename(c: Conversation, name: String) = drive.guard {
        val sealed = withContext(Dispatchers.Default) { cipher(c).encrypt(JSONObject().put("name", name).toString()) }
        api.post("/v3/chat/conversations/name/edit", JSONObject().put("uuid", c.uuid).put("name", sealed))
        changed()
    }

    suspend fun mute(c: Conversation, on: Boolean) = drive.guard {
        api.post("/v3/chat/mute", JSONObject().put("uuid", c.uuid).put("mute", on))
        changed()
    }

    /** The owner deletes the chat for all; another user leaves it (C-06, C-07). */
    suspend fun remove(c: Conversation) = drive.guard {
        api.post(if (c.owner) "/v3/chat/conversations/delete" else "/v3/chat/conversations/leave", JSONObject().put("uuid", c.uuid))
        keys.remove(c.uuid)
        changed()
    }

    // ---- contacts (C-22..C-26) ----

    suspend fun contacts(): List<Contact> = drive.guard {
        api.getArray("/v3/contacts").objects().mapNotNull { o ->
            val uuid = o.optText("uuid").takeIf(Decode::isUuid) ?: return@mapNotNull null
            val key = o.optText("publicKey").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            Contact(uuid, person(o, "userId", "email", "nickName"), key)
        }.sortedBy { it.person.name.lowercase() }
    }

    /** Requests to this account, then the ones that wait for an answer of another user. */
    suspend fun requests(): List<ContactRequest> = drive.guard {
        fun parse(rows: JSONArray, incoming: Boolean) = rows.objects().mapNotNull { o ->
            o.optText("uuid").takeIf(Decode::isUuid)?.let { ContactRequest(it, person(o, "userId", "email", "nickName"), incoming) }
        }
        parse(api.getArray("/v3/contacts/requests/in"), true) + parse(api.getArray("/v3/contacts/requests/out"), false)
    }

    /** A contact request to [email]. The server allows about 30 for each hour (C-24). */
    suspend fun request(email: String) = drive.guard {
        api.post("/v3/contacts/requests/send", JSONObject().put("email", email), retry = false)
        changed()
    }

    suspend fun answer(r: ContactRequest, accept: Boolean) = drive.guard {
        api.post(if (accept) "/v3/contacts/requests/accept" else "/v3/contacts/requests/deny", JSONObject().put("uuid", r.uuid))
        changed()
    }

    /**
     * Calls [onChange] while the caller runs: when the chat change counter moves (C-40), and at least each
     * [FALLBACK_MS], because the counter is not proven for every event. The caller runs this only while its screen
     * shows (C-41). Never returns.
     */
    suspend fun watch(onChange: suspend () -> Unit): Nothing {
        var last = -1L
        var at = SystemClock.elapsedRealtime()
        while (true) {
            delay(POLL_MS)
            val counter = try {
                api.get("/v3/messageIds").optLongLoose("chat")
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                -1L
            }
            val now = SystemClock.elapsedRealtime()
            if ((counter >= 0 && last >= 0 && counter != last) || now - at >= FALLBACK_MS) {
                at = now
                runCatching { onChange() }.exceptionOrNull()?.let { if (it is CancellationException) throw it }
            }
            if (counter >= 0) last = counter
        }
    }

    fun clear() {
        keys.clear()
        cached = null
    }

    private fun changed() {
        _changed.tryEmit(Unit)
    }

    private fun cipher(c: Conversation): MetaKey = textKey(c.key ?: throw GeneralSecurityException("No key"))

    /** The message JSON (C-35). The TypeScript SDK refuses 65 536 characters or more (C-74). */
    private suspend fun seal(key: MetaKey, text: String): String = withContext(Dispatchers.Default) {
        key.encrypt(JSONObject().put("message", text).toString()).also { if (it.length >= MAX_MESSAGE) throw IOException("The message is too long") }
    }

    private fun conversation(o: JSONObject, me: Long, ring: Keyring): Conversation? {
        val uuid = o.optText("uuid").takeIf(Decode::isUuid) ?: return null
        val participants = o.optJSONArray("participants").objects()
        val owner = o.optLongLoose("ownerId") == me
        val keyText = keyOf(uuid, o, owner, participants.firstOrNull { it.optLongLoose("userId") == me }, ring)
        val key = keyText?.let { runCatching { textKey(it) }.getOrNull() }
        return Conversation(
            uuid = uuid,
            name = key?.let { k -> o.optText("name").takeIf { it.isNotEmpty() }?.let { runCatching { open(k, it, "name") }.getOrNull() } }.orEmpty(),
            others = participants.filter { it.optLongLoose("userId") != me }.map { person(it, "userId", "email", "nickName") },
            owner = owner,
            muted = o.optBoolLoose("muted"),
            last = o.optJSONObject("lastMessageFull")?.let { m -> key?.let { runCatching { message(m, me, it) }.getOrNull() } },
            lastFocus = Decode.ms(o.optLongLoose("lastFocus")),
            created = Decode.ms(o.optLongLoose("createdTimestamp")),
            key = keyText?.takeIf { key != null },
        )
    }

    /** The owner reads the account copy (one AES call); others their RSA copy (C-33, C-34). */
    private fun keyOf(uuid: String, o: JSONObject, owner: Boolean, mine: JSONObject?, ring: Keyring): String? {
        keys[uuid]?.let { return it }
        val text = (if (owner) o.optText("ownerMetadata").takeIf { it.isNotEmpty() }?.let { runCatching { keyField(ring.decryptMeta(it)) }.getOrNull() } else null)
            ?: mine?.optText("metadata")?.takeIf { it.isNotEmpty() }?.let { runCatching { keyField(Drive.shared(ring, it)) }.getOrNull() }
        return text?.also { keys[uuid] = it }
    }

    private fun message(o: JSONObject, me: Long, key: MetaKey): Message? {
        val uuid = o.optText("uuid").takeIf(Decode::isUuid) ?: return null
        val sender = person(o, "senderId", "senderEmail", "senderNickName")
        val reply = o.optJSONObject("replyTo")?.let { r ->
            r.optText("uuid").takeIf(Decode::isUuid)?.let { Reply(it, person(r, "senderId", "senderEmail", "senderNickName").name, decrypt(key, r.optText("message"))) }
        }
        return Message(
            uuid = uuid,
            sender = sender,
            mine = sender.id == me,
            text = decrypt(key, o.optText("message")),
            sent = Decode.ms(o.optLongLoose("sentTimestamp")),
            edited = o.optBoolLoose("edited"),
            reply = reply,
        )
    }

    private fun decrypt(key: MetaKey, sealed: String): String? = runCatching { open(key, sealed, "message") }.getOrNull()

    private fun person(o: JSONObject, id: String, email: String, nick: String) = Person(o.optLongLoose(id), o.optText(email), o.optText(nick))

    companion object {
        const val POLL_MS = 5_000L
        const val FALLBACK_MS = 30_000L

        /** The TypeScript SDK refuses an encrypted message of this length or more (C-12). */
        const val MAX_MESSAGE = 65_536

        private fun keyField(json: String): String = JSONObject(json).getString("key")

        /** `""` is empty text (C-35); else the one field of the JSON. */
        internal fun open(key: MetaKey, sealed: String, field: String): String =
            if (sealed.isEmpty()) "" else JSONObject(key.decrypt(sealed)).getString(field)
    }
}
