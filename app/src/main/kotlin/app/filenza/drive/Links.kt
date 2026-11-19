package app.filenza.drive

import app.filenza.crypto.Bytes
import app.filenza.crypto.DataKey
import app.filenza.crypto.Kdf
import app.filenza.crypto.MasterKey
import app.filenza.crypto.MetaKey
import app.filenza.crypto.Names
import app.filenza.crypto.hex
import app.filenza.crypto.isHex
import app.filenza.crypto.unhex
import java.net.URI
import java.net.URLDecoder

/**
 * Public link URLs and link passwords (research F-72..F-77). The key is in the URL fragment, which no browser
 * sends to a server; the app keeps it in memory only.
 */
object Links {
    /** A parsed link: a file ([folder] false) or a folder, its link UUID and its key text. */
    class Parsed(val folder: Boolean, val uuid: String, val key: String) {
        override fun toString() = "Link(${if (folder) "folder" else "file"} $uuid)"
    }

    private val UUID = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
    private val HOSTS = setOf("app.filen.io", "drive.filen.io", "filen.io", "www.filen.io")
    private val CANDIDATE = Regex("https://[^\\s\"'<>]+")

    /** The key of link metadata: `003` for a 64-hex key, else `002` with the key text. */
    fun metaKey(key: String): MetaKey =
        if (key.length == 64 && key.isHex()) DataKey(key.unhex()) else MasterKey(key.encodeToByteArray())

    /** The link that the current Filen web app reads (research F-77): f = file, d = folder, hex key. */
    fun url(folder: Boolean, linkUuid: String, key: String): String =
        "https://app.filen.io/${if (folder) "d" else "f"}/$linkUuid#${key.encodeToByteArray().hex()}"

    /** The first Filen link in [text] (a share or a paste), or null. */
    fun find(text: String): Parsed? = CANDIDATE.findAll(text).firstNotNullOfOrNull { parse(it.value) }

    /**
     * Reads both URL forms: `https://app.filen.io/f/<uuid>#<hex key>` (f = file, d = folder) and the old
     * `https://app.filen.io/#/d/<uuid>%23<key>` (d = download = file, f = folder).
     */
    fun parse(url: String): Parsed? {
        // A browser can decode the %23 of the old form into a second '#', which URI refuses.
        val raw = url.trim().let { u ->
            val i = u.indexOf('#')
            if (i >= 0) u.substring(0, i + 1) + u.substring(i + 1).replace("#", "%23") else u
        }
        val uri = runCatching { URI(raw) }.getOrNull() ?: return null
        if (uri.scheme != "https" || uri.host?.lowercase() !in HOSTS) return null
        val path = uri.rawPath.orEmpty().trim('/').split('/')
        val fragment = uri.rawFragment.orEmpty()
        val (kind, uuid, rawKey) = when {
            path.size == 2 && path[0] in setOf("f", "d") -> Triple(if (path[0] == "d") "folder" else "file", path[1], fragment)
            fragment.startsWith("/d/") || fragment.startsWith("/f/") -> {
                val rest = URLDecoder.decode(fragment.substring(3), Charsets.UTF_8)
                Triple(if (fragment[1] == 'f') "folder" else "file", rest.substringBefore('#'), rest.substringAfter('#', ""))
            }
            else -> return null
        }
        if (!UUID.matches(uuid)) return null
        val key = decodeKey(URLDecoder.decode(rawKey, Charsets.UTF_8)) ?: return null
        return Parsed(kind == "folder", uuid.lowercase(), key)
    }

    /** The key is the 32-character key text, or its hex form. */
    private fun decodeKey(text: String): String? = when {
        text.length == 64 && text.isHex() -> text.unhex().takeIf { b -> b.all { it in 0x20..0x7e } }?.decodeToString()
        text.length == 32 -> text
        else -> null
    }

    /**
     * The hashed link password (research F-76). No password: the text "empty". The salt tells the version:
     * 512 hex characters = Argon2id, 32 characters = PBKDF2, none = the old SHA-1/SHA-512 hash.
     */
    fun hashPassword(password: String?, salt: String): String = when {
        password.isNullOrEmpty() -> NO_PASSWORD
        salt.length == 512 && salt.isHex() -> Kdf.argon2id(password.encodeToByteArray(), salt.unhex()).hex()
        salt.isNotEmpty() -> Kdf.pbkdf2Sha512(password.encodeToByteArray(), salt.encodeToByteArray(), 200_000, 64).hex()
        else -> Names.v2Hash(password.encodeToByteArray())
    }

    /** A new link salt: 256 random bytes as hex (the current form). */
    fun newSalt(): String = Bytes.random(256).hex()

    const val NO_PASSWORD = "empty"
}
