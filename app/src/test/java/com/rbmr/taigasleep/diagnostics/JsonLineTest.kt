package com.rbmr.taigasleep.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonLineTest {
  @Test
  fun headerIsFixedAndParsable() {
    val line = JsonLine.build(1700000000000, 5000, 42, "demo", mapOf("a" to 1))
    assertEquals("""{"t_ms":1700000000000,"elapsed_ms":5000,"pid":42,"type":"demo","a":1}""", line)
    assertEquals(LineMeta(1700000000000, 5000, 42, "demo"), LineMeta.parse(line))
  }

  @Test
  fun escapesQuotesBackslashesAndControlCharacters() {
    val line = JsonLine.build(1, 2, 3, "t", mapOf("s" to "a\"b\\c\nd\te\u0001f" + 0x2028.toChar()))
    assertTrue(line.contains("""a\"b\\c\nd\te\u0001f\u2028"""))
    assertFalse(line.contains('\n'))
  }

  @Test
  fun loneSurrogateIsEscapedButPairsSurvive() {
    val line = JsonLine.build(1, 2, 3, "t", mapOf("lone" to "x\uD800y", "pair" to "😀"))
    assertTrue(line.contains("""x\ud800y"""))
    assertTrue(line.contains("😀"))
  }

  @Test
  fun nestedValuesNullsAndNonFiniteNumbers() {
    val line =
        JsonLine.build(
            1,
            2,
            3,
            "t",
            mapOf(
                "map" to mapOf("k" to listOf(1, "x", null)),
                "nan" to Double.NaN,
                "none" to null,
                "flag" to true,
            ),
        )
    assertTrue(line.endsWith(""","map":{"k":[1,"x",null]},"nan":null,"none":null,"flag":true}"""))
  }

  @Test
  fun callerCannotShadowHeaderKeys() {
    val line = JsonLine.build(1, 2, 3, "t", mapOf("pid" to 99, "type" to "evil"))
    assertEquals(LineMeta(1, 2, 3, "t"), LineMeta.parse(line))
    assertTrue(line.contains(""""f_pid":99"""))
  }

  @Test
  fun longStringsAreTruncated() {
    val line = JsonLine.build(1, 2, 3, "t", mapOf("s" to "x".repeat(20000)))
    assertTrue(line.length < 9000)
    assertTrue(line.contains("[truncated 11808]"))
  }

  @Test
  fun truncatedOrForeignLinesDoNotParse() {
    assertNull(LineMeta.parse("""{"t_ms":1,"elapsed_ms":2"""))
    assertNull(LineMeta.parse("garbage"))
    assertNotNull(LineMeta.parse("""{"t_ms":1,"elapsed_ms":2,"pid":3,"type":"x","cut":"ab"""))
  }
}
