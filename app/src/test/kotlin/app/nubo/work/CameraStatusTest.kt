package app.nubo.work

import org.json.JSONObject
import kotlin.test.Test
import kotlin.test.assertEquals

/** The camera upload status file: the state survives it, and it holds only MediaStore URIs. */
class CameraStatusTest {
    @Test
    fun stateSurvivesTheFile() {
        val s = CameraState(last = 1_700_000_000_000, problem = "Your Filen storage is full", skipped = listOf("content://media/external/images/media/7"))
        assertEquals(s, CameraStatus.parse(JSONObject(CameraStatus.toJson(s).toString())))
        assertEquals(CameraState(), CameraStatus.parse(JSONObject("{}")))
        assertEquals(CameraState(), CameraStatus.parse(JSONObject(CameraStatus.toJson(CameraState()).toString())))
    }

    @Test
    fun onlyContentUrisAreRead() {
        val o = JSONObject("""{"skipped":["content://media/external/video/media/3","/sdcard/DCIM/a.jpg",""]}""")
        assertEquals(listOf("content://media/external/video/media/3"), CameraStatus.parse(o).skipped)
    }
}
