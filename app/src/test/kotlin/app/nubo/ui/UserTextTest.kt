package app.nubo.ui

import app.nubo.net.Transport
import app.nubo.ui.components.userText
import java.io.FileNotFoundException
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals

/** Review 70: the user sees the app's own texts only, never a path, a URI or a wrapped system text. */
class UserTextTest {
    @Test
    fun ownTextsShow() {
        assertEquals("Connection lost", userText(IOException("Connection lost", RuntimeException("x"))))
        assertEquals("A name cannot contain / \\ : * ? \" < > |", userText(IOException("A name cannot contain / \\ : * ? \" < > |")))
        assertEquals("The answer from Filen is too large", userText(Transport.TooLarge()))
    }

    @Test
    fun systemTextsDoNotShow() {
        val generic = "The operation failed (IOException)"
        assertEquals(generic, userText(IOException("Failed to create directory for /data/user/0/app/files/settings.json.new")))
        assertEquals(generic, userText(IOException("No content provider: content://media/external/file/1")))
        assertEquals(generic, userText(IOException(IllegalStateException("closed"))))
        assertEquals("The operation failed (FileNotFoundException)", userText(FileNotFoundException("photo.jpg: open failed: ENOENT")))
    }
}
