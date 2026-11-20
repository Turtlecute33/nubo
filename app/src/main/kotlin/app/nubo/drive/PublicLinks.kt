package app.nubo.drive

import app.nubo.api.Api
import app.nubo.api.ApiException
import app.nubo.api.optText
import app.nubo.crypto.DataKey
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** What a public link opens: one file, or the root of a folder with its [link] for further listings. */
sealed interface Opened {
    data class File(val item: Item) : Opened
    data class Folder(val link: FolderLink, val root: Item) : Opened
}

/** The link needs a password: ask the user, then call [PublicLinks.open] again with it. */
class PasswordNeeded : Exception("Password needed")

/**
 * Opens public Filen links as a visitor (research F-73, F-75): no account and no API key are sent. The
 * server never sees the key, which comes from the URL fragment.
 */
class PublicLinks(private val api: Api) {
    suspend fun open(link: Links.Parsed, password: String?): Opened =
        if (link.folder) folder(link, password) else file(link, password)

    private suspend fun file(link: Links.Parsed, password: String?): Opened {
        val info = api.postAnon("/v3/file/link/password", JSONObject().put("uuid", link.uuid))
        if (info.optBoolean("hasPassword") && password.isNullOrEmpty()) throw PasswordNeeded()
        val hashed = withContext(Dispatchers.Default) { Links.hashPassword(password, info.optText("salt")) }
        val o = try {
            api.postAnon("/v3/file/link/info", JSONObject().put("uuid", link.uuid).put("password", hashed))
        } catch (e: ApiException) {
            // Only a refusal means a wrong password; a busy server keeps its own text (review 64).
            if (info.optBoolean("hasPassword") && !e.transient) throw ApiException("wrong_password", e.http) else throw e
        }
        val key = DataKey.parse(link.key)
        val version = o.optInt("version", 2)
        return withContext(Dispatchers.Default) {
            val fields = Ops.fieldKey(key, version)
            val name = fields.decrypt(o.getString("name"))
            val uuid = o.getString("uuid")
            // A size that does not parse would give an empty download that looks done; a negative one passes the
            // size limits of the viewers.
            val size = fields.decrypt(o.getString("size")).toLongOrNull()?.takeIf { it >= 0 }
            // The UUID is a part of file paths (review 60).
            if (!Decode.validName(name) || !Decode.isUuid(uuid) || size == null) throw ApiException("bad_answer", 0)
            Opened.File(
                Item(
                    uuid = uuid,
                    parent = "",
                    name = name,
                    folder = false,
                    size = size,
                    mime = Decode.mime(runCatching { fields.decrypt(o.getString("mime")) }.getOrDefault("")),
                    modified = Decode.ms(o.optLong("timestamp")),
                    uploaded = Decode.ms(o.optLong("timestamp")),
                    key = link.key,
                    region = o.getString("region"),
                    bucket = o.getString("bucket"),
                    chunks = o.optInt("chunks"),
                    version = version,
                    source = Source.Link(link.uuid),
                ),
            )
        }
    }

    private suspend fun folder(link: Links.Parsed, password: String?): Opened {
        val info = api.postAnon("/v3/dir/link/info", JSONObject().put("uuid", link.uuid))
        if (info.optBoolean("hasPassword") && password.isNullOrEmpty()) throw PasswordNeeded()
        val hashed = withContext(Dispatchers.Default) { Links.hashPassword(password, info.optText("salt")) }
        val key = Links.metaKey(link.key)
        val rootUuid = info.getString("parent")
        if (!Decode.isUuid(rootUuid)) throw ApiException("bad_answer", 0) // a part of file paths (review 60)
        val name = withContext(Dispatchers.Default) {
            val meta = key.decrypt(info.getString("metadata")) // a wrong key in the link fails here
            // The link creator wrote this JSON. Bad JSON gives the default name, also deep nesting, which overflows
            // the stack of the parser (an Error, which nothing else catches).
            runCatching { JSONObject(meta).optText("name") }.getOrNull()
        }?.takeIf(Decode::validName) ?: "Shared folder"
        val folderLink = FolderLink(link.uuid, link.key, hashed)
        // A wrong password shows at the first listing; check it now, so that the user can try again here.
        if (info.optBoolean("hasPassword")) {
            try {
                api.postAnon("/v3/dir/link/content", JSONObject().put("uuid", link.uuid).put("password", hashed).put("parent", rootUuid))
            } catch (e: ApiException) {
                throw if (e.transient) e else ApiException("wrong_password", e.http)
            }
        }
        val root = Item(uuid = rootUuid, parent = "", name = name, folder = true, created = Decode.ms(info.optLong("timestamp")), source = Source.Link(link.uuid))
        return Opened.Folder(folderLink, root)
    }
}
