package app.nubo.util

/** The kind of a file, from its name. Decides the icon, the colour and the viewer. */
enum class FileType {
    FOLDER, IMAGE, VIDEO, AUDIO, PDF, TEXT, DOCUMENT, SPREADSHEET, PRESENTATION, ARCHIVE, CODE, APK, OTHER;

    val isMedia: Boolean get() = this == IMAGE || this == VIDEO

    companion object {
        private val map: Map<String, FileType> = buildMap {
            listOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp").forEach { put(it, IMAGE) }
            listOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "avi").forEach { put(it, VIDEO) }
            listOf("mp3", "m4a", "aac", "flac", "ogg", "oga", "opus", "wav", "amr", "mid", "midi").forEach { put(it, AUDIO) }
            put("pdf", PDF)
            listOf("txt", "md", "log", "csv", "ini", "conf", "cfg", "srt", "vtt", "tex").forEach { put(it, TEXT) }
            listOf("doc", "docx", "odt", "rtf", "pages", "epub").forEach { put(it, DOCUMENT) }
            listOf("xls", "xlsx", "ods", "numbers", "tsv").forEach { put(it, SPREADSHEET) }
            listOf("ppt", "pptx", "odp", "key").forEach { put(it, PRESENTATION) }
            listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "zst").forEach { put(it, ARCHIVE) }
            listOf(
                "kt", "kts", "java", "js", "ts", "tsx", "jsx", "json", "xml", "html", "htm", "css", "py", "rs", "go", "c",
                "h", "cpp", "hpp", "swift", "sh", "zsh", "yml", "yaml", "toml", "sql", "rb", "php", "lua", "gradle",
            ).forEach { put(it, CODE) }
            put("apk", APK)
        }

        fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

        fun of(name: String): FileType = map[extension(name)] ?: OTHER

        /** Made one time, not at each call (review 101). */
        private val DECODABLE = setOf("jpg", "jpeg", "png", "gif", "webp", "heic", "heif", "avif", "bmp")

        /** Images that ImageDecoder can read, so the app can make a thumbnail. */
        fun decodable(name: String): Boolean = extension(name) in DECODABLE

        /** A MIME type for a share or an "open with", from the name. */
        fun mime(name: String): String = android.webkit.MimeTypeMap.getSingleton()
            .getMimeTypeFromExtension(extension(name)) ?: "application/octet-stream"
    }
}
