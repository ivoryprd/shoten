package io.github.ivoryprd.shoten.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.StringReader

class JsonTest {
    @Test
    fun parsesScalarsAndNesting() {
        val v = Json.parse("""{"a": [1, 2.5, -3e2, "x\"y", true, false, null], "b": {"c": {}}, "d": []}""")
        val map = v.asJsonObject()!!
        assertEquals(listOf(1L, 2.5, -300.0, "x\"y", true, false, null), map["a"])
        assertEquals(mapOf("c" to emptyMap<String, Any?>()), map["b"])
        assertEquals(emptyList<Any?>(), map["d"])
    }

    @Test
    fun handlesEscapesAndUnicode() {
        val v = Json.parse(""""\u3042\n\t\\\/\ud83d\ude00日本"""")
        assertEquals("あ\n\t\\/" + String(Character.toChars(0x1F600)) + "日本", v)
    }

    @Test
    fun skipsBom() {
        assertEquals(listOf(1L), Json.parse(String(Character.toChars(0xFEFF)) + "[1]"))
    }

    @Test
    fun roundTrips() {
        val text = """[{"tag":"span","data":{"content":"x"},"content":["a",{"tag":"rt","content":"b"}]},1,2.5,null,true,"q\"\\\n"]"""
        assertEquals(text, Json.write(Json.parse(text)))
    }

    @Test
    fun writesControlCharacters() {
        assertEquals("\"\\u0001\\u2028\"", Json.write(String(charArrayOf(1.toChar(), 0x2028.toChar()))))
    }

    @Test
    fun streamsLargeArraysAcrossBufferBoundaries() {
        val sb = StringBuilder("[")
        for (i in 0 until 20000) {
            if (i > 0) sb.append(',')
            sb.append("[\"word$i\",\"よみ\",null,\"v1\",$i,[\"gloss ${"x".repeat(i % 50)}\"],$i,\"\"]")
        }
        sb.append(']')
        val reader = JsonReader(StringReader(sb.toString()))
        reader.beginArray()
        var count = 0
        while (reader.hasNext()) {
            reader.beginArray()
            assertEquals("word$count", reader.nextString())
            assertEquals("よみ", reader.nextString())
            assertNull(reader.nextStringOrNull())
            assertEquals("v1", reader.nextString())
            assertEquals(count.toLong(), reader.nextLong())
            val gloss = reader.readTree() as List<*>
            assertEquals("gloss " + "x".repeat(count % 50), gloss[0])
            reader.skipValue()
            reader.skipValue()
            assertFalse(reader.hasNext())
            reader.endArray()
            count++
        }
        reader.endArray()
        assertEquals(JsonToken.END_DOCUMENT, reader.peek())
        assertEquals(20000, count)
    }

    @Test
    fun rejectsMalformedInput() {
        for (bad in listOf("[1,]", "{\"a\" 1}", "[1 2]", "\"abc", "{\"a\":1,}", "[tru]", "[1]x", "{1:2}", "[-]")) {
            try {
                Json.parse(bad)
                fail("Expected failure for $bad")
            } catch (e: JsonException) {
                assertTrue(e.message!!.isNotEmpty())
            }
        }
    }
}
