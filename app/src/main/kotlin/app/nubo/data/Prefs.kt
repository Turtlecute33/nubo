package app.nubo.data

import android.util.AtomicFile
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONObject
import java.io.File
import java.io.IOException

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class SortBy { NAME, MODIFIED, SIZE, TYPE }

/** How one folder shows its items. Folders always come first. */
@Immutable
data class FolderView(val grid: Boolean = false, val sort: SortBy = SortBy.NAME, val descending: Boolean = false)

@Immutable
data class CameraUpload(
    val enabled: Boolean = false,
    /** The cloud folder that gets the photos; null until the user picks one. Only the UUID: no name (review 63). */
    val folder: String? = null,
    val videos: Boolean = true,
    /** Upload only on a network that is not metered (Wi-Fi, Ethernet). */
    val unmeteredOnly: Boolean = true,
    /** Only media added after this time (ms) uploads. Set when the user turns camera upload on. */
    val since: Long = 0,
)

/** The user's settings. Not secret: the session is in [SessionStore]. DESIGN §6. */
@Immutable
data class Settings(
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val pureBlack: Boolean = false,
    val appLock: Boolean = false,
    val blockScreenshots: Boolean = true,
    val defaultView: FolderView = FolderView(),
    /** Per-folder view, by folder UUID. Set when the user changes the view of one folder. */
    val folderViews: Map<String, FolderView> = emptyMap(),
    val camera: CameraUpload = CameraUpload(),
    /** Notes and Chats as tabs in the bottom bar, when the user turns them on. */
    val notesTab: Boolean = false,
    val chatsTab: Boolean = false,
) {
    fun viewOf(folder: String): FolderView = folderViews[folder] ?: defaultView
}

/** One JSON file, written with [AtomicFile] off the main thread. */
class Prefs(dir: () -> File, private val scope: CoroutineScope) {
    // Lazy: the app folder is resolved off the main thread, at the first load.
    private val file by lazy { AtomicFile(File(dir(), "settings.json")) }
    private val writeLock = Mutex()
    private val _state = MutableStateFlow(Settings())
    val state: StateFlow<Settings> = _state.asStateFlow()
    val value: Settings get() = _state.value

    /** Reads the file. Call once, off the main thread, before the UI shows. */
    fun load() {
        val json = runCatching { JSONObject(file.readFully().decodeToString()) }.getOrNull()
        _state.value = json?.let { runCatching { parse(it) }.getOrNull() } ?: Settings()
        // A file of 0.1.0 has the camera folder name: write the file again without it (review 63).
        if (json?.optJSONObject("camera")?.has("folderName") == true) edit { it }
    }

    fun edit(change: (Settings) -> Settings) {
        _state.update(change)
        scope.launch(Dispatchers.IO) {
            writeLock.withLock {
                // A full disk must not crash the app: the settings stay in memory, the file keeps the last copy (review 82).
                try {
                    write(_state.value)
                } catch (_: IOException) {
                }
            }
        }
    }

    fun setView(folder: String, view: FolderView) = edit {
        // Only a size limit: old folder entries are not worth a cleanup pass.
        val views = (it.folderViews - folder).entries.toList().takeLast(MAX_VIEWS - 1).associate { e -> e.key to e.value }
        it.copy(folderViews = views + (folder to view))
    }

    private fun write(s: Settings) {
        val out = file.startWrite()
        try {
            out.write(toJson(s).toString().encodeToByteArray())
            file.finishWrite(out)
        } catch (e: Exception) {
            file.failWrite(out)
            throw e
        }
    }

    internal companion object {
        const val MAX_VIEWS = 500

        fun toJson(s: Settings): JSONObject = JSONObject()
            .put("theme", s.theme.name)
            .put("dynamicColor", s.dynamicColor)
            .put("pureBlack", s.pureBlack)
            .put("appLock", s.appLock)
            .put("blockScreenshots", s.blockScreenshots)
            .put("notesTab", s.notesTab)
            .put("chatsTab", s.chatsTab)
            .put("defaultView", view(s.defaultView))
            .put("folderViews", JSONObject().apply { s.folderViews.forEach { (k, v) -> put(k, view(v)) } })
            .put(
                "camera",
                JSONObject()
                    .put("enabled", s.camera.enabled)
                    .put("folder", s.camera.folder ?: JSONObject.NULL)
                    .put("videos", s.camera.videos)
                    .put("unmeteredOnly", s.camera.unmeteredOnly)
                    .put("since", s.camera.since),
            )

        fun parse(o: JSONObject): Settings {
            val d = Settings()
            val views = o.optJSONObject("folderViews")
            val cam = o.optJSONObject("camera") ?: JSONObject()
            return Settings(
                theme = enumOr(o.optString("theme"), d.theme),
                dynamicColor = o.optBoolean("dynamicColor", d.dynamicColor),
                pureBlack = o.optBoolean("pureBlack", d.pureBlack),
                appLock = o.optBoolean("appLock", d.appLock),
                blockScreenshots = o.optBoolean("blockScreenshots", d.blockScreenshots),
                notesTab = o.optBoolean("notesTab", d.notesTab),
                chatsTab = o.optBoolean("chatsTab", d.chatsTab),
                defaultView = o.optJSONObject("defaultView")?.let(::view) ?: d.defaultView,
                folderViews = views?.keys()?.asSequence()?.mapNotNull { k -> views.optJSONObject(k)?.let { k to view(it) } }?.toMap().orEmpty(),
                camera = CameraUpload(
                    enabled = cam.optBoolean("enabled", false),
                    folder = cam.optString("folder").takeIf { !cam.isNull("folder") && it.isNotEmpty() },
                    videos = cam.optBoolean("videos", true),
                    unmeteredOnly = cam.optBoolean("unmeteredOnly", true),
                    since = cam.optLong("since", 0),
                ),
            )
        }

        private fun view(v: FolderView) = JSONObject().put("grid", v.grid).put("sort", v.sort.name).put("descending", v.descending)

        private fun view(o: JSONObject) = FolderView(
            grid = o.optBoolean("grid", false),
            sort = enumOr(o.optString("sort"), SortBy.NAME),
            descending = o.optBoolean("descending", false),
        )

        private inline fun <reified E : Enum<E>> enumOr(name: String, fallback: E): E =
            enumValues<E>().firstOrNull { it.name == name } ?: fallback
    }
}
