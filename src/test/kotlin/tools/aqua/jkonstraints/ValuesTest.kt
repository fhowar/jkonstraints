/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import java.math.BigInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class ValuesTest {
  @Test
  fun `bit-vectors are two's complement`() {
    assertEquals((-1).toByte(), Values.signed(BigInteger.valueOf(0xff), 8))
    assertEquals((-32768).toShort(), Values.signed(BigInteger.valueOf(0x8000), 16))
    assertEquals(Int.MIN_VALUE, Values.signed(BigInteger.valueOf(0x80000000), 32))
    assertEquals(5L, Values.signed(BigInteger.valueOf(5), 64))
    assertEquals(BigInteger.valueOf(-1), Values.signed(BigInteger.valueOf(7), 3))
  }

  @Test
  fun `string escapes round-trip`() {
    val value = "a\"b\\c\u0000\uD83D\uDE00é"
    val literal = Values.escape(value)
    assertEquals(value, Values.unescape(literal.substring(1, literal.length - 1)))
    assertEquals("A\"", Values.unescape("\\u0041\"\""))
  }

  @Test
  fun `parsed literals decode to JVM values`() {
    val context = SmtContext()
    assertEquals(-1, Values.toJava(context.parseTerm("#xffffffff")))
    assertEquals(BigInteger.valueOf(-3), Values.toJava(context.parseTerm("(- 3)")))
    assertEquals(true, Values.toJava(context.parseTerm("true")))
    assertEquals(1.5f, Values.toJava(context.parseTerm("(fp #b0 #b01111111 #b10000000000000000000000)")))
    assertEquals(Double.NaN, Values.toJava(context.parseTerm("(_ NaN 11 53)")))
    assertEquals(-0.0, Values.toJava(context.parseTerm("(_ -zero 11 53)")))
    assertEquals("x\"y", Values.toJava(context.parseTerm("\"x\"\"y\"")))
  }
}
