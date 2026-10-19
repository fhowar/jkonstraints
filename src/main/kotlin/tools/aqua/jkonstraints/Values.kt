/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import java.math.BigDecimal
import java.math.BigInteger
import java.math.MathContext
import tools.aqua.konstraints.smt.BitVecLiteral
import tools.aqua.konstraints.smt.Expression
import tools.aqua.konstraints.smt.FPInfinity
import tools.aqua.konstraints.smt.FPMinusInfinity
import tools.aqua.konstraints.smt.FPMinusZero
import tools.aqua.konstraints.smt.FPNaN
import tools.aqua.konstraints.smt.FPZero
import tools.aqua.konstraints.smt.False
import tools.aqua.konstraints.smt.FloatingPointLiteral
import tools.aqua.konstraints.smt.IntLiteral
import tools.aqua.konstraints.smt.IntNeg
import tools.aqua.konstraints.smt.QuotingRule
import tools.aqua.konstraints.smt.RealDiv
import tools.aqua.konstraints.smt.RealLiteral
import tools.aqua.konstraints.smt.RealNeg
import tools.aqua.konstraints.smt.StringLiteral
import tools.aqua.konstraints.smt.True

/**
 * Conversion of SMT value terms (as returned by get-value) into JVM values.
 *
 * | SMT sort                     | JVM value                                   |
 * |------------------------------|---------------------------------------------|
 * | `Bool`                       | [Boolean]                                   |
 * | `(_ BitVec 8/16/32/64)`      | [Byte]/[Short]/[Int]/[Long], two's complement |
 * | other bit-vectors            | [BigInteger], two's complement              |
 * | `(_ FloatingPoint 8 24)`     | [Float] (NaN and signed zeros preserved)    |
 * | `(_ FloatingPoint 11 53)`    | [Double]                                    |
 * | `Int`                        | [BigInteger]                                |
 * | `Real`                       | [BigDecimal]                                |
 * | `String`                     | [String] with SMT-LIB escapes decoded       |
 * | anything else                | the value term in SMT-LIB syntax            |
 */
object Values {
  @JvmStatic
  fun toJava(value: Expression<*>): Any =
      when (value) {
        is True -> true
        is False -> false
        is BitVecLiteral -> signed(value.value, value.numBits)
        is FloatingPointLiteral ->
            when {
              value.sort.exponentBits == 8 && value.sort.significantBits == 24 -> value.asFloat()
              value.sort.exponentBits == 11 && value.sort.significantBits == 53 -> value.asDouble()
              else -> smt(value)
            }
        is FPZero -> floatingPoint(value.eb, value.sb, 0.0) ?: smt(value)
        is FPMinusZero -> floatingPoint(value.eb, value.sb, -0.0) ?: smt(value)
        is FPInfinity -> floatingPoint(value.eb, value.sb, Double.POSITIVE_INFINITY) ?: smt(value)
        is FPMinusInfinity ->
            floatingPoint(value.eb, value.sb, Double.NEGATIVE_INFINITY) ?: smt(value)
        is FPNaN -> floatingPoint(value.eb, value.sb, Double.NaN) ?: smt(value)
        is IntLiteral -> value.value
        is IntNeg -> (toJava(value.inner) as? BigInteger)?.negate() ?: smt(value)
        is RealLiteral -> value.value
        is RealNeg -> (toJava(value.inner) as? BigDecimal)?.negate() ?: smt(value)
        is RealDiv -> {
          val terms = value.terms.map { toJava(it) }
          if (terms.all { it is BigDecimal })
              terms.drop(1).fold(terms[0] as BigDecimal) { acc, divisor ->
                acc.divide(divisor as BigDecimal, MathContext.DECIMAL128)
              }
          else smt(value)
        }
        is StringLiteral -> unescape(value.value)
        else -> smt(value)
      }

  /** Interprets the unsigned [value] of a [bits] wide bit-vector as a two's complement number. */
  @JvmStatic
  fun signed(value: BigInteger, bits: Int): Any {
    val signedValue = if (value.testBit(bits - 1)) value - BigInteger.ONE.shiftLeft(bits) else value
    return when (bits) {
      8 -> signedValue.toByte()
      16 -> signedValue.toShort()
      32 -> signedValue.toInt()
      64 -> signedValue.toLong()
      else -> signedValue
    }
  }

  /**
   * Decodes the contents of an SMT-LIB string literal (without the enclosing quotes): `""` is a
   * quote, `\u{d..d}` (1-5 hex digits) and `\udddd` (4 hex digits) are code points.
   */
  @JvmStatic
  fun unescape(literal: String): String {
    val builder = StringBuilder(literal.length)
    var i = 0
    while (i < literal.length) {
      val c = literal[i]
      if (c == '"' && literal.startsWith("\"\"", i)) {
        builder.append('"')
        i += 2
        continue
      }
      if (c == '\\' && literal.startsWith("\\u", i)) {
        val braced = BRACED_ESCAPE.matchAt(literal, i)
        val plain = PLAIN_ESCAPE.matchAt(literal, i)
        val match = braced ?: plain
        if (match != null) {
          builder.appendCodePoint(match.groupValues[1].toInt(16))
          i += match.value.length
          continue
        }
      }
      builder.append(c)
      i++
    }
    return builder.toString()
  }

  /** Encodes [value] as an SMT-LIB string literal, including the enclosing quotes. */
  @JvmStatic
  fun escape(value: String): String {
    val builder = StringBuilder("\"")
    value.codePoints().forEach { cp ->
      when {
        cp == '"'.code -> builder.append("\"\"")
        cp == '\\'.code || cp < 0x20 || cp > 0x7e -> builder.append("\\u{${cp.toString(16)}}")
        else -> builder.appendCodePoint(cp)
      }
    }
    return builder.append('"').toString()
  }

  private fun floatingPoint(eb: Int, sb: Int, value: Double): Any? =
      when {
        eb == 8 && sb == 24 -> value.toFloat()
        eb == 11 && sb == 53 -> value
        else -> null
      }

  private fun smt(value: Expression<*>) = value.toSMTString(QuotingRule.SAME_AS_INPUT, false)

  private val BRACED_ESCAPE = Regex("""\\u\{([0-9a-fA-F]{1,5})}""")
  private val PLAIN_ESCAPE = Regex("""\\u([0-9a-fA-F]{4})""")
}
