package app.nubo.drive

import app.nubo.api.ApiException
import app.nubo.crypto.Bytes
import app.nubo.crypto.DataKey
import app.nubo.crypto.Kdf
import app.nubo.crypto.MasterKey
import app.nubo.crypto.MetaKey
import app.nubo.crypto.Names
import app.nubo.crypto.hex
import app.nubo.crypto.isHex
import app.nubo.crypto.unhex
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

    private val HOSTS = setOf("app.filen.io", "drive.filen.io", "filen.io", "www.filen.io")
    private val CANDIDATE = Regex("https://[^\\s\"'<>]+")

    /** Text after a link in a sentence or in brackets; no key ends with one of them. */
    private val TRAILING = charArrayOf('.', ',', ';', ':', '!', '?', ')', ']', '}')

    /** The key of link metadata: `003` for a 64-hex key, else `002` with the key text. */
    fun metaKey(key: String): MetaKey =
        if (key.length == 64 && key.isHex()) DataKey(key.unhex()) else MasterKey(key.encodeToByteArray())

    /** The link that the current Filen web app reads (research F-77): f = file, d = folder, hex key. */
    fun url(folder: Boolean, linkUuid: String, key: String): String =
        "https://app.filen.io/${if (folder) "d" else "f"}/$linkUuid#${key.encodeToByteArray().hex()}"

    /** The first Filen link in [text] (a share or a paste), or null. A full stop or a bracket after it is not part of it (review 66). */
    fun find(text: String): Parsed? = CANDIDATE.findAll(text).firstNotNullOfOrNull { parse(it.value.trimEnd(*TRAILING)) }

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
        // Decode each part once only. URI accepts only valid escapes in the raw parts, so this decode cannot throw;
        // a second decode of decoded text can ("%25zz" -> "%zz"), and a crash here is open to any web page.
        fun decode(raw: String) = URLDecoder.decode(raw, Charsets.UTF_8)
        val (kind, uuid, keyText) = when {
            path.size == 2 && path[0] in setOf("f", "d") -> {
                // Some apps send the key after an encoded '#' (%23) in the path, not in the fragment.
                val inPath = path[1].indexOf("%23", ignoreCase = true)
                // A key in both places is not a link that any client writes: the path key could have come in with
                // a link UUID from the server, and the real key is in the fragment. Refuse it.
                if (inPath >= 0 && fragment.isNotEmpty()) return null
                val id = if (inPath >= 0) path[1].substring(0, inPath) else path[1]
                Triple(if (path[0] == "d") "folder" else "file", id, decode(if (inPath >= 0) path[1].substring(inPath + 3) else fragment))
            }
            fragment.startsWith("/d/") || fragment.startsWith("/f/") -> {
                val rest = decode(fragment.substring(3))
                Triple(if (fragment[1] == 'f') "folder" else "file", rest.substringBefore('#'), rest.substringAfter('#', ""))
            }
            else -> return null
        }
        if (!Decode.isUuid(uuid)) return null
        val key = decodeKey(keyText) ?: return null
        return Parsed(kind == "folder", uuid.lowercase(), key)
    }

    /**
     * The key is the 32-character key text, or its hex form. A v3 folder key is 64 hex characters; the web app
     * writes its hex form, 128 characters, and so does [url] (review 57).
     */
    private fun decodeKey(text: String): String? = when {
        text.length == 64 && text.isHex() -> text.unhex().takeIf { b -> b.all { it in 0x20..0x7e } }?.decodeToString()
        text.length == 128 && text.isHex() -> text.unhex().decodeToString().takeIf { it.isHex() }
        text.length == 32 -> text
        else -> null
    }

    /**
     * The hashed link password (research F-76). No password: the text "empty". The salt tells the version:
     * 512 hex characters = Argon2id, 32 characters = PBKDF2, none = the old SHA-1/SHA-512 hash. The salt comes
     * from the server, and any other salt is refused: with the 256-hex salt of a v2 login, PBKDF2 here is the
     * login derivation, and the hash sent back would hold the master key of a user who types the account
     * password (filen-rs refuses these salts too).
     */
    fun hashPassword(password: String?, salt: String): String = when {
        password.isNullOrEmpty() -> NO_PASSWORD
        salt.length == 512 && salt.isHex() -> Kdf.argon2id(password.encodeToByteArray(), salt.unhex()).hex()
        salt.length == 32 -> Kdf.pbkdf2Sha512(password.encodeToByteArray(), salt.encodeToByteArray(), 200_000, 64).hex()
        salt.isEmpty() -> Names.v2Hash(password.encodeToByteArray())
        else -> throw ApiException("bad_answer", 0)
    }

    /** A new link salt: 256 random bytes as hex (the current form). */
    fun newSalt(): String = Bytes.random(256).hex()

    const val NO_PASSWORD = "empty"
}
