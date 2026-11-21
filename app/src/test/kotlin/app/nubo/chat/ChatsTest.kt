package app.nubo.chat

import app.nubo.crypto.textKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Chat crypto against research/chat.md (C-35, C-36, C-62) and the unread rule (C-19). */
class ChatsTest {
    private val v2 = textKey("abcdefghijklmnopqrstuvwxyzABCDEF")
    private val v3 = textKey("00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff")

    @Test
    fun vectors() {
        assertEquals("Hello!", Chats.open(v2, "002012345678901Q5joUtq8VTYcOhbqRqj8I5VlAn1SDJl8sQ98qyYa+dJ+4is+", "message"))
        assertEquals("Team", Chats.open(v2, "002abcdefABCDEF5fIEQRSOlGmnRTNX2/ZYosgajT2UJotRxxUkU79xwg==", "name"))
        assertEquals("café ☕", Chats.open(v2, "002ZZZZZZZZZZZZqh+pBD0DkQbIXSpHDUbdtBiclVQ1s5PuL18SLN+AHuOZCM3RoYfQ", "message"))
        assertEquals("Hello!", Chats.open(v3, "003000102030405060708090a0bbTrYUzM6rNGsGVXeebAIQwEUhQO7TwuoU4/zYFQifH816pOA", "message"))
        assertEquals("", Chats.open(v2, "", "message"))
    }

    @Test
    fun unreadFollowsTheOfficialRule() {
        val other = Person(2, "b@example.com", "")
        val m = Message("m", other, false, "hi", sent = 2_000, edited = false, reply = null)
        val c = Conversation("c", "", listOf(other), true, false, m, lastFocus = 1_000, created = 0, key = "k")
        assertTrue(c.unread)
        assertFalse(c.copy(lastFocus = 0).unread)
        assertFalse(c.copy(muted = true).unread)
        assertFalse(c.copy(last = m.copy(mine = true)).unread)
        assertFalse(c.copy(lastFocus = 3_000).unread)
        assertEquals("b@example.com", c.title)
        assertEquals("Team", c.copy(name = "Team").title)
    }
}
