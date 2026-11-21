package app.nubo.notes

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
import app.nubo.drive.WriteLock
import app.nubo.drive.optBoolLoose
import app.nubo.drive.optLongLoose
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withContext
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.security.GeneralSecurityException
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** One note of the list. [key] is the note key text, null when this account has no readable copy (N-44). */
@Immutable
data class Note(
    val uuid: String,
    val type: NoteType?,
    val title: String,
    val preview: String,
    val pinned: Boolean,
    val favorite: Boolean,
    val trash: Boolean,
    val archive: Boolean,
    val edited: Long,
    val owner: Boolean,
    val canWrite: Boolean,
    val shared: Boolean,
    val key: String?,
) {
    /** Nubo writes the content only of its own types, in a note that is not in the trash (N-68, N-94). */
    val editable: Boolean get() = key != null && canWrite && !trash && type in NoteType.writable

    /** Only IDs: the title and the key stay out of logs and saved state. */
    override fun toString() = "Note($uuid)"
}

/** The content of a note as the server has it now. */
class NoteContent(val text: String, val type: NoteType?)

/** Another device changed the note after the text that the editor started from (N-69). */
class NoteConflict(val remote: NoteContent) : IOException("The note changed on another device")

/**
 * Filen notes (research `notes.md`): list, content, create, edit, and the state flags. Note keys and the list stay
 * in memory only. Writes take the "notes-write" lock of the other Filen apps (N-37). DESIGN §12.
 */
class Notes(private val drive: Drive, private val account: Account) {
    private val api get() = drive.api
    private val lock by lazy { WriteLock(drive.api, drive.scope, "notes-write") }

    /** Note key texts: a shared note needs one RSA-4096 decrypt, so each key is read once (N-44). */
    private val keys = ConcurrentHashMap<String, String>()

    private val _changed = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /** A write of this app changed the list. */
    val changed: SharedFlow<Unit> = _changed

    /** The last list that the server gave, for the first frame of a screen. */
    @Volatile
    var cached: List<Note>? = null
        private set

    suspend fun list(): List<Note> = drive.guard {
        val rows = api.getArray("/v3/notes")
        val me = account.userId()
        val ring = drive.keys()
        withContext(Dispatchers.Default) { rows.objects().mapNotNull { decode(it, me, ring) } }.also { cached = it }
    }

    fun find(uuid: String): Note? = cached?.firstOrNull { it.uuid == uuid }

    /** The content now on the server. A damaged content fails: the editor must never start from empty text. */
    suspend fun content(note: Note): NoteContent = drive.guard {
        val o = api.post("/v3/notes/content", JSONObject().put("uuid", note.uuid))
        val key = cipher(note)
        withContext(Dispatchers.Default) { NoteContent(open(key, o.optText("content"), "content"), NoteType.of(o.optText("type"))) }
    }

    /**
     * Writes [text] as the content. [base] is the text that the editor started from: when the server has other
     * text now, another device wrote in between, and the write stops with [NoteConflict] (N-69).
     */
    suspend fun save(note: Note, type: NoteType, text: String, base: String) = drive.guard {
        val key = cipher(note)
        val (content, preview) = withContext(Dispatchers.Default) { seal(key, type, text) }
        lock.write {
            val now = content(note)
            if (now.text != base) throw NoteConflict(now)
            api.post("/v3/notes/content/edit", body(note.uuid).put("preview", preview).put("content", content).put("type", type.wire))
        }
        // No [changed]: the open editor has the text, and the list reads again when it shows.
    }

    /**
     * A new note of [type] with [text], owned by this account (N-60, N-61). The owner copy of the key goes in under
     * the same lock: without it, the TypeScript clients refuse the note (N-81).
     */
    suspend fun create(title: String, type: NoteType, text: String = ""): Note = drive.guard {
        val ring = drive.keys()
        val uuid = UUID.randomUUID().toString()
        val keyText = Bytes.alnum(32)
        val key = textKey(keyText)
        val keyJson = JSONObject().put("key", keyText).toString()
        val sealed = withContext(Dispatchers.Default) {
            listOf(ring.encryptMeta(keyJson), Keyring.encryptFor(ring.publicKey, keyJson), key.encrypt(JSONObject().put("title", title).toString()))
        }
        val (content, preview) = withContext(Dispatchers.Default) { seal(key, type, text) }
        lock.write {
            api.post("/v3/notes/create", body(uuid).put("title", sealed[2]).put("metadata", sealed[0]), retry = false)
            try {
                api.post(
                    "/v3/notes/participants/add",
                    body(uuid).put("contactUUID", "owner").put("metadata", sealed[1]).put("permissionsWrite", true),
                )
                // A new note is "text" and empty on the server: the type and the first content follow (N-61).
                if (type != NoteType.TEXT) api.post("/v3/notes/type/change", body(uuid).put("preview", preview).put("content", content).put("type", type.wire))
                else if (text.isNotEmpty()) api.post("/v3/notes/content/edit", body(uuid).put("preview", preview).put("content", content).put("type", type.wire))
            } catch (e: Exception) {
                // No half-made note stays (N-81).
                withContext(NonCancellable) { runCatching { api.post("/v3/notes/delete", body(uuid)) } }
                throw e
            }
        }
        keys[uuid] = keyText
        val note = Note(uuid, type, title, NoteText.preview(type, text), false, false, false, false, System.currentTimeMillis(), true, true, false, keyText)
        // The editor opens at once and finds the note here, before the list is read again.
        cached = listOf(note) + cached.orEmpty()
        changed()
        note
    }

    suspend fun rename(note: Note, title: String) = write {
        val sealed = withContext(Dispatchers.Default) { cipher(note).encrypt(JSONObject().put("title", title).toString()) }
        api.post("/v3/notes/title/edit", body(note.uuid).put("title", sealed))
    }

    /** Changes the type and converts the content for it (N-53, N-64). */
    suspend fun changeType(note: Note, type: NoteType) = drive.guard {
        val now = content(note)
        val text = NoteText.convert(now.text, now.type ?: note.type, type)
        val (content, preview) = withContext(Dispatchers.Default) { seal(cipher(note), type, text) }
        lock.write { api.post("/v3/notes/type/change", body(note.uuid).put("preview", preview).put("content", content).put("type", type.wire)) }
        changed()
    }

    suspend fun pin(note: Note, on: Boolean) = write { api.post("/v3/notes/pinned", body(note.uuid).put("pinned", on)) }

    suspend fun favorite(note: Note, on: Boolean) = write { api.post("/v3/notes/favorite", body(note.uuid).put("favorite", on)) }

    suspend fun archive(note: Note) = write { api.post("/v3/notes/archive", body(note.uuid)) }

    suspend fun trash(note: Note) = write { api.post("/v3/notes/trash", body(note.uuid)) }

    /** Out of the trash or the archive (N-17). */
    suspend fun restore(note: Note) = write { api.post("/v3/notes/restore", body(note.uuid)) }

    suspend fun delete(note: Note) = write {
        api.post("/v3/notes/delete", body(note.uuid))
        keys.remove(note.uuid)
    }

    /** A note that another user shares with this account: remove this account from it (N-32, N-65). */
    suspend fun leave(note: Note) = write {
        api.post("/v3/notes/participants/remove", body(note.uuid).put("userId", account.userId()))
        keys.remove(note.uuid)
    }

    fun clear() {
        keys.clear()
        cached = null
    }

    private suspend fun write(block: suspend () -> Unit) = drive.guard {
        lock.write { block() }
        changed()
    }

    private fun changed() {
        _changed.tryEmit(Unit)
    }

    private fun body(uuid: String) = JSONObject().put("uuid", uuid)

    private fun cipher(note: Note): MetaKey = textKey(note.key ?: throw GeneralSecurityException("No key"))

    /** The encrypted content and preview. A content too large for the server stops here (N-55, N-91). */
    private fun seal(key: MetaKey, type: NoteType, text: String): Pair<String, String> {
        val content = key.encrypt(JSONObject().put("content", text).toString())
        if (content.length >= MAX_CONTENT) throw IOException("The note is too large")
        return content to key.encrypt(JSONObject().put("preview", NoteText.preview(type, text)).toString())
    }

    private fun decode(o: JSONObject, me: Long, ring: Keyring): Note? {
        val uuid = o.optText("uuid").takeIf(Decode::isUuid) ?: return null
        val participants = o.optJSONArray("participants").objects()
        val mine = participants.firstOrNull { it.optLongLoose("userId") == me }
        val owner = o.optLongLoose("ownerId") == me || o.optBoolLoose("isOwner")
        val keyText = keyOf(uuid, o, owner, mine, ring)
        val key = keyText?.let { runCatching { textKey(it) }.getOrNull() }
        // A title or preview that does not decrypt shows empty; the content read fails loudly instead.
        fun field(name: String) = key?.let { runCatching { open(it, o.optText(name), name) }.getOrNull() }.orEmpty()
        return Note(
            uuid = uuid,
            type = NoteType.of(o.optText("type")),
            title = field("title"),
            preview = field("preview"),
            pinned = o.optBoolLoose("pinned"),
            favorite = o.optBoolLoose("favorite"),
            trash = o.optBoolLoose("trash"),
            archive = o.optBoolLoose("archive"),
            edited = Decode.ms(o.optLongLoose("editedTimestamp")),
            owner = owner,
            canWrite = owner || mine?.optBoolLoose("permissionsWrite") == true,
            shared = participants.size > 1,
            key = keyText?.takeIf { key != null },
        )
    }

    /** The owner reads the account copy (one AES call); others their RSA copy (N-44). */
    private fun keyOf(uuid: String, o: JSONObject, owner: Boolean, mine: JSONObject?, ring: Keyring): String? {
        keys[uuid]?.let { return it }
        val text = (if (owner) runCatching { keyField(ring.decryptMeta(o.optText("metadata"))) }.getOrNull() else null)
            ?: mine?.optText("metadata")?.takeIf { it.isNotEmpty() }?.let { runCatching { keyField(Drive.shared(ring, it)) }.getOrNull() }
        return text?.also { keys[uuid] = it }
    }

    companion object {
        /** The TypeScript SDK refuses encrypted content of this length or more (N-55). */
        const val MAX_CONTENT = 1_048_575

        private fun keyField(json: String): String = JSONObject(json).getString("key")

        /** `""` is empty (N-88); else the one field of the JSON (N-45). A missing field is damaged data. */
        internal fun open(key: MetaKey, sealed: String, field: String): String {
            if (sealed.isEmpty()) return ""
            return try {
                JSONObject(key.decrypt(sealed)).getString(field)
            } catch (e: JSONException) {
                throw GeneralSecurityException("Damaged data", e)
            }
        }
    }
}
