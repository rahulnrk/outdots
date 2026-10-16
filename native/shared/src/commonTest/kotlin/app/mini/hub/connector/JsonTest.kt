package app.mini.hub.connector

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsonTest {
    @Test
    fun parsesNestedObjectArraysAndEscapedStrings() {
        val raw = """
            {
              "id": "1234567890123456789",
              "ok": true,
              "count": 42,
              "nested": {
                "inner": "hello \"world\"\nnext line"
              },
              "items": [
                { "name": "a", "val": 1 },
                { "name": "b", "val": 2 }
              ],
              "empty": null
            }
        """.trimIndent()
        val json = JsonValue.parse(raw)
        assertEquals("1234567890123456789", json.str("id"))
        assertEquals(true, json["ok"]?.bool)
        assertEquals(42L, json["count"]?.long)
        assertEquals("hello \"world\"\nnext line", json.path("nested", "inner")?.string)
        assertEquals(2, json["items"]?.objects?.size)
        assertEquals("b", json["items"]?.objects?.get(1)?.str("name"))
        assertNull(json.path("items", "0", "missing"))
    }

    @Test
    fun writesEscapedJsonString() {
        val data = mapOf(
            "text" to "line1\nline2\t\"quoted\"",
            "ok" to true,
            "count" to 5,
            "recipients" to listOf("a@example.com", "b@example.com"),
        )
        val written = JsonWriter.write(data)
        assertTrue(written.contains("\"text\":"))
        assertTrue(written.contains("\"ok\":true"))
        val roundtrip = JsonValue.parse(written)
        assertEquals("line1\nline2\t\"quoted\"", roundtrip.str("text"))
        assertEquals(listOf("a@example.com", "b@example.com"), roundtrip["recipients"]?.array?.mapNotNull { it.string })
    }

    @Test
    fun rfc3339ParsesTimezoneOffsetsAndZulu() {
        assertEquals("100", JsonValue.parse("{\"update_id\":100}").str("update_id"))
        val encoded = ChatCursor(global = "101").encode()
        assertEquals("{\"c\":{},\"g\":\"101\"}", encoded)
        val decoded = ChatCursor.decode(encoded)
        assertEquals("101", decoded.global)
        assertEquals(1_700_000_000_000L, Rfc3339.parseMillis("2023-11-14T22:13:20Z"))
        assertEquals(1_700_000_000_500L, Rfc3339.parseMillis("2023-11-14T22:13:20.500Z"))
        assertEquals(1_700_000_000_000L, Rfc3339.parseMillis("2023-11-15T03:43:20+05:30"))
        assertNull(Rfc3339.parseMillis("not-a-date"))
    }

    @Test
    fun rejectsMalformedNumbersControlsAndDeepInput() {
        listOf("01", "1e", "--1", "{\"x\":\"bad\nline\"}").forEach {
            assertNull(JsonValue.parseOrNull(it), "malformed JSON must not be accepted: $it")
        }

        val deeplyNested = (1..80).fold("0") { value, _ -> "[$value]" }
        assertNull(JsonValue.parseOrNull(deeplyNested), "deep JSON must fail closed")
    }

    @Test
    fun preservesValidSurrogatePairEscapes() {
        val emoji = JsonValue.parse("""{"emoji":"\uD83D\uDE00"}""").str("emoji")
        assertEquals("\uD83D\uDE00", emoji)
    }
}
