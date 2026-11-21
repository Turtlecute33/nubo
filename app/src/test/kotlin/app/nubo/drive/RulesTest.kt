package app.nubo.drive

import app.nubo.crypto.MasterKey
import app.nubo.data.FolderView
import app.nubo.data.Prefs
import app.nubo.data.Settings
import app.nubo.data.SortBy
import app.nubo.data.ThemeMode
import app.nubo.ui.drive.sortedFor
import org.json.JSONObject
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Name rules, item decoding, timestamps, sort order and the settings file. */
class RulesTest {
    @Test
    fun nameRules() {
        assertEquals("Café.txt", NameRules.clean("Café.txt")) // NFC
        for (bad in listOf("", ".", "..", "a/b", "a\\b", "a:b", "x?", "end.", "end ", " start", "CON", "lpt1.txt", "tab\tname", "a".repeat(256))) {
            assertFailsWith<IOException>(bad) { NameRules.clean(bad) }
        }
        assertEquals("ok name (1).pdf", NameRules.clean("ok name (1).pdf"))
    }

    @Test
    fun timestampsInSecondsOrMilliseconds() {
        val now = System.currentTimeMillis()
        assertEquals(now, Decode.ms(now))
        assertEquals(now / 1000 * 1000, Decode.ms(now / 1000))
        assertEquals(0, Decode.ms(0))
        // Too large for seconds: t * 1000 must not wrap to a time near now.
        assertEquals(Long.MAX_VALUE / 100, Decode.ms(Long.MAX_VALUE / 100))
    }

    @Test
    fun namesWithControlsThatReorderTextAreRefused() {
        // A right-to-left override shows "x<RLO>gnp.apk" as "xkpa.png"; the isolates do the same.
        for (bad in listOf("x\u202Egnp.apk", "a\u202Ab", "a\u2066b", "a\u2069b", "del\u007F", "c1\u0085")) {
            assertFalse(Decode.validName(bad), bad)
        }
        // Joiners in emoji, right-to-left letters and a real U+FFFD are fine.
        for (good in listOf("family 👨\u200D👩.jpg", "שלום.txt", "report \uFFFD.txt")) {
            assertTrue(Decode.validName(good), good)
        }
    }

    @Test
    fun sizesAndTypesOfOtherUsersAreChecked() {
        val account = MasterKey("0123456789abcdefghijklmnopqrstuv".encodeToByteArray())
        val noSize = JSONObject().put("name", "a.jpg").put("key", "abcdefghijklmnopqrstuvwxyzABCDEF")
        fun decode(meta: JSONObject, size: Long) = Decode.file(
            JSONObject().put("uuid", "0f8c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b").put("metadata", account.encrypt(meta.toString())).put("size", size),
            Source.Own,
            account::decrypt,
        )
        // With no size in the metadata the row's size counts: a negative one would pass the size limits.
        assertNull(decode(noSize, -2_147_483_649L))
        assertEquals(5, decode(noSize, 5)!!.size)
        // A MIME type goes into intents: megabytes of it would crash "Open with" (a too large Binder transaction).
        assertEquals("", decode(JSONObject(noSize.toString()).put("mime", "image/" + "x".repeat(100_000)), 5)!!.mime)
        assertEquals("image/jpeg", decode(JSONObject(noSize.toString()).put("mime", "image/jpeg"), 5)!!.mime)
        val docx = "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        assertEquals(docx, Decode.mime(docx))
        for (bad in listOf("", "image", "text/plain\n", "a/b/c", "text/plain; charset=utf-8")) assertEquals("", Decode.mime(bad), bad)
    }

    @Test
    fun decodesFilesAndRejectsBadEntries() {
        val account = MasterKey("0123456789abcdefghijklmnopqrstuv".encodeToByteArray())
        val meta = JSONObject().put("name", "photo.jpg").put("size", "2048").put("mime", "image/jpeg")
            .put("key", "abcdefghijklmnopqrstuvwxyzABCDEF").put("lastModified", 1_700_000_000_000L).put("blake3", "ab".repeat(32))
        val u1 = "0f8c1a2b-3c4d-4e5f-8a9b-0c1d2e3f4a5b"
        val row = JSONObject().put("uuid", u1).put("parent", "p").put("metadata", account.encrypt(meta.toString()))
            .put("chunks", 1).put("region", "de-1").put("bucket", "b").put("version", 2).put("timestamp", 1_700_000_000L).put("favorited", 1)
        val item = Decode.file(row, Source.Own, account::decrypt)!!
        assertEquals("photo.jpg", item.name)
        assertEquals(2048, item.size)
        assertTrue(item.favorite)
        assertEquals(1_700_000_000_000L, item.uploaded)
        assertEquals("Item(file $u1)", item.toString()) // no name, no key in logs
        // Review 60: the UUID goes into file paths.
        assertNull(Decode.file(JSONObject(row.toString()).put("uuid", ".."), Source.Own, account::decrypt))

        val traversal = JSONObject(meta.toString()).put("name", "../evil")
        assertNull(Decode.file(JSONObject(row.toString()).put("metadata", account.encrypt(traversal.toString())), Source.Own, account::decrypt))
        val badKey = JSONObject(meta.toString()).put("key", "short")
        assertNull(Decode.file(JSONObject(row.toString()).put("metadata", account.encrypt(badKey.toString())), Source.Own, account::decrypt))
        val sha512Only = JSONObject(meta.toString()).apply { remove("blake3") }.put("hash", "cd".repeat(64))
        assertEquals("", Decode.file(JSONObject(row.toString()).put("metadata", account.encrypt(sha512Only.toString())), Source.Own, account::decrypt)!!.hash)
    }

    @Test
    fun foldersFirstThenTheChosenOrder() {
        fun f(n: String, folder: Boolean, size: Long = 0) = Item(uuid = n, parent = "p", name = n, folder = folder, size = size)
        val items = listOf(f("b.txt", false, 5), f("A", true), f("a.txt", false, 9), f("c", true))
        assertEquals(listOf("A", "c", "a.txt", "b.txt"), items.sortedFor(FolderView()).map { it.name })
        assertEquals(listOf("c", "A", "a.txt", "b.txt"), items.sortedFor(FolderView(sort = SortBy.SIZE, descending = true)).map { it.name })
    }

    @Test
    fun offlineIndexKeepsTheSource() {
        val base = Item(uuid = "u", parent = "p", name = "a.txt", folder = false, size = 3, key = "k".repeat(32), chunks = 1)
        for (source in listOf(Source.Own, Source.SharedIn("a@example.org"), Source.SharedOut("b@example.org"), Source.Link("l1"))) {
            assertEquals(source, Item.fromJson(JSONObject(base.copy(source = source).toJson().toString())).source)
        }
        // An unknown creation time is left out, not written as 0 (1970 in the official apps).
        assertTrue(!JSONObject(base.metaJson()).has("creation"))
    }

    @Test
    fun settingsSurviveTheFile() {
        val s = Settings(theme = ThemeMode.DARK, pureBlack = true, appLock = true, folderViews = mapOf("x" to FolderView(grid = true, sort = SortBy.SIZE)), notesTab = true, chatsTab = true)
        assertEquals(s, Prefs.parse(JSONObject(Prefs.toJson(s).toString())))
        assertEquals(Settings(), Prefs.parse(JSONObject("{}")))
    }
}
