package app.mini.hub.android.intelligence

import kotlin.test.Test
import kotlin.test.assertEquals

class PlainTextDraftTest {
    @Test
    fun stripsMarkdownButKeepsText() {
        assertEquals("Hi Ravi, see you at 5.", PlainTextDraft.clean("**Hi Ravi**, see you at *5*."))
        assertEquals("Thanks!", PlainTextDraft.clean("\"Thanks!\""))
        assertEquals("Sure, done.", PlainTextDraft.clean("Reply: Sure, done."))
        assertEquals("Agenda\n- item one\n- item two", PlainTextDraft.clean("## Agenda\n* item one\n* item two"))
    }

    @Test
    fun leavesNormalTextAndMathAlone() {
        val s = "Price is 2 * 3 = 6, email a@b.com, 10% off"
        assertEquals(s, PlainTextDraft.clean(s))
    }
}
