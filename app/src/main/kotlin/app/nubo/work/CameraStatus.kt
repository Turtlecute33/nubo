package app.nubo.work

import android.util.AtomicFile
import androidx.compose.runtime.Immutable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * What camera upload did: the time of the last good upload, the problem that stopped it, and the media that did
 * not upload. Only MediaStore URIs: no file names on the disk.
 */
@Immutable
data class CameraState(val last: Long = 0, val problem: String? = null, val skipped: List<String> = emptyList())

/** [CameraState] in one small file in no-backup storage. Writes are small: call off the main thread. */
class CameraStatus(dir: File) {
    private val file = AtomicFile(File(dir, FILE))
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    /** Reads the file. Call once, off the main thread. */
    fun load() {
        _state.value = runCatching { parse(JSONObject(file.readFully().decodeToString())) }.getOrNull() ?: CameraState()
    }

    /** The item [uri] uploaded: the run works, so an old problem is gone, and the item is not in the list. */
    fun uploaded(uri: String) = change { it.copy(last = System.currentTimeMillis(), problem = null, skipped = it.skipped - uri) }

    /** A problem of the account or the folder stopped the run. [text] has no file names. */
    fun stopped(text: String) = change { it.copy(problem = text) }

    /** The user fixed the problem (another folder). */
    fun resolved() = change { it.copy(problem = null) }

    /** An item that did not upload and that the job does not try again. */
    fun skipped(uri: String) = change { s -> s.copy(skipped = (s.skipped - uri + uri).takeLast(MAX_SKIPPED)) }

    /** The user sent these items to the transfer queue, or they are not on the phone any more. */
    fun forget(uris: Collection<String>) = change { it.copy(skipped = it.skipped - uris.toSet()) }

    /** After a logout. */
    fun clear() = change { CameraState() }

    @Synchronized
    private fun change(f: (CameraState) -> CameraState): CameraState {
        val next = f(_state.value)
        _state.value = next
        // A full disk must not stop the upload: the state stays in memory.
        runCatching {
            val out = file.startWrite()
            try {
                out.write(toJson(next).toString().encodeToByteArray())
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
        }
        return next
    }

    internal companion object {
        const val FILE = "camera-status.json"
        const val MAX_SKIPPED = 100

        fun toJson(s: CameraState): JSONObject = JSONObject()
            .put("last", s.last)
            .put("problem", s.problem ?: JSONObject.NULL)
            .put("skipped", JSONArray(s.skipped))

        fun parse(o: JSONObject): CameraState {
            val list = o.optJSONArray("skipped")
            return CameraState(
                last = o.optLong("last", 0),
                problem = o.optString("problem").takeIf { !o.isNull("problem") && it.isNotEmpty() },
                skipped = (0 until (list?.length() ?: 0)).mapNotNull { list?.optString(it)?.takeIf { u -> u.startsWith("content://") } },
            )
        }
    }
}
