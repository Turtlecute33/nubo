package app.nubo.notes

import app.nubo.crypto.DataKey
import app.nubo.crypto.MasterKey
import app.nubo.crypto.textKey
import java.security.GeneralSecurityException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Note crypto and content formats against research/notes.md (N-41, N-52, N-54, N-71). */
class NotesTest {
    private val v2 = "abcdefghijklmnopqrstuvwxyzABCDEF"
    private val v3 = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff"

    @Test
    fun keyTextPicksTheFormat() {
        assertIs<MasterKey>(textKey(v2))
        assertIs<DataKey>(textKey(v3))
        assertTrue(textKey(v2).encrypt("{}").startsWith("002"))
        assertTrue(textKey(v3).encrypt("{}").startsWith("003"))
        assertFailsWith<GeneralSecurityException> { textKey("short") }
        assertFailsWith<GeneralSecurityException> { textKey("z".repeat(64)) }
    }

    @Test
    fun vectors() {
        val k = textKey(v2)
        assertEquals("Shopping list", Notes.open(k, "002012345678901Q5jxXt2jUXNDOn+gYb3gJpQjAGzp7de272xPLNCFi7+IupaEQdUG534=", "title"))
        assertEquals(
            """{"key":"0123456789abcdefghijklmnopqrstuv"}""",
            k.decrypt("002012345678901Q5juUtDtDnNJKR77OvimeMJ9QWLj+sby9YKiSIV4tY0Dkb3W9CreTRpTWRwGX8oWjthRjnBHqBvwQQ=="),
        )
        assertEquals("Shopping list", Notes.open(textKey(v3), "003000102030405060708090a0bbTrBXzQlqJTzGTyUXqUURgBShxKSdw+9REF6guBFWUWpmMT80hJlGuw=", "title"))
        val checklist = Notes.open(
            k,
            "002012345678901Q5jmWMe7UT8NOhbqMrj8b54lVGGt/cvx8YGuRtNI+oUNjb/B23yVB1RHvquEXgbip37ZWDJsSklPzcwmuAjzFOMYNSDqz/S0XY6Bn7XSA18EAvkE+l2TSJ9BkdIT3A9bNSKgE3UMG+uGuYN8MgM7yLLvdy0o9J83oGsViMPU/RUq",
            "content",
        )
        assertEquals(listOf(Check("Milk", false), Check("Eggs & ham", true)), NoteText.parseChecklist(checklist))
    }

    @Test
    fun emptyAndDamaged() {
        val k = textKey(v2)
        assertEquals("", Notes.open(k, "", "content"))
        val sealed = k.encrypt("""{"title":"x"}""")
        assertFailsWith<GeneralSecurityException> { Notes.open(k, sealed, "content") }
        assertFailsWith<GeneralSecurityException> { Notes.open(k, sealed.dropLast(2) + "AA", "title") }
    }

    @Test
    fun checklistWriteIsTheOfficialShape() {
        val items = listOf(Check("Milk", false), Check("", false), Check(" Eggs & ham ", true))
        assertEquals(
            """<ul data-checked="false"><li>Milk</li><li><br></li></ul><ul data-checked="true"><li>Eggs &amp; ham</li></ul>""",
            NoteText.writeChecklist(items),
        )
        assertEquals("", NoteText.writeChecklist(emptyList()))
        assertEquals(listOf(Check("Milk", false), Check("", false), Check("Eggs & ham", true)), NoteText.parseChecklist(NoteText.writeChecklist(items)))
    }

    @Test
    fun checklistReadsOldForms() {
        assertEquals(listOf(Check("a", true), Check("b", false)), NoteText.parseChecklist("<ul data-checked='true'><li>a</li></ul><ul><li><b>b</b></li></ul>"))
        assertEquals(listOf(Check("parent", false), Check("child", false)), NoteText.parseChecklist("<ul><li>parent<ul><li>child</li></ul></li></ul>"))
        assertEquals(emptyList(), NoteText.parseChecklist("just text"))
        assertEquals(listOf(Check("<x>", false)), NoteText.parseChecklist("<ul><li>&lt;x&gt;</li></ul>"))
    }

    @Test
    fun previewRules() {
        assertEquals("first", NoteText.preview(NoteType.TEXT, "first\nsecond"))
        assertEquals("one", NoteText.preview(NoteType.RICH, "<p>one</p><p><br></p><p>two</p>"))
        assertEquals("ab", NoteText.preview(NoteType.RICH, "<p>a</p><p>b</p>"))
        assertEquals("first item", NoteText.preview(NoteType.CHECKLIST, "<li> </li><li><b>first</b> item</li>"))
        assertEquals("", NoteText.preview(NoteType.CHECKLIST, "<ul></ul>"))
        assertEquals("ä".repeat(128), NoteText.preview(NoteType.TEXT, "ä".repeat(129)))
        assertEquals("a".repeat(127), NoteText.cut("a".repeat(127) + "😀", 128))
    }

    @Test
    fun conversions() {
        assertEquals(
            """<ul data-checked="false"><li>one</li><li>two</li></ul>""",
            NoteText.convert("one\n\n two \n", NoteType.TEXT, NoteType.CHECKLIST),
        )
        assertEquals("one\ntwo", NoteText.convert("""<ul data-checked="true"><li>one</li><li>two</li></ul>""", NoteType.CHECKLIST, NoteType.MD))
        assertEquals("code", NoteText.convert("code", NoteType.CODE, NoteType.TEXT))
        assertEquals("""<ul data-checked="false"><li><br></li></ul>""", NoteText.convert("", NoteType.TEXT, NoteType.CHECKLIST))
    }

    @Test
    fun richTextAsPlainText() {
        assertEquals("Title\n\nA & B\n• one\n• two", NoteText.plain("<h1>Title</h1><p><br></p><p>A &amp; B</p><ul><li>one</li><li>two</li></ul>"))
        assertEquals("x", NoteText.plain("<script>x</script>"))
    }

    @Test
    fun unknownTypeIsReadOnly() {
        val n = Note("u", NoteType.of("drawing"), "t", "", false, false, false, false, 0, true, true, false, v2)
        assertEquals(null, n.type)
        assertEquals(false, n.editable)
        assertEquals(false, n.copy(type = NoteType.RICH).editable)
        assertEquals(true, n.copy(type = NoteType.CHECKLIST).editable)
        assertEquals(false, n.copy(type = NoteType.TEXT, trash = true).editable)
        assertEquals("Note(u)", n.toString())
    }
}
