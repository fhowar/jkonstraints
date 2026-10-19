/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import tools.aqua.jkonstraints.smtlib.SExpr
import tools.aqua.jkonstraints.smtlib.SpoutDialect

class SpoutDialectTest {
  private val declarations =
      """
      (declare-fun __int_0 () (_ BitVec 32))
      (declare-fun __long_0 () (_ BitVec 64))
      (declare-fun __string_0 () String)
      (declare-fun __double_0 () (_ FloatingPoint 11 53))
      (declare-fun fld.Box.x (Int) (_ BitVec 32))
      (declare-fun arr.content.0 () (Array Int (_ BitVec 32)))
      """

  private fun normalize(term: String): String {
    val dialect = SpoutDialect()
    SExpr.parseAll(declarations).forEach { dialect.command(it) }
    return SExpr.print(dialect.term(SExpr.parseAll(term).single()))
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value =
          [
              // not equal is not SMT-LIB
              "(!= __int_0 #x00000000) | (not (= __int_0 #x00000000))",
              // integer comparisons on bit-vectors (charAt bounds checks)
              "(<= #x00000000 __int_0) | (bvsle #x00000000 __int_0)",
              "(< __int_0 ((_ int2bv 32) (str.len __string_0))) | " +
                  "(bvslt __int_0 ((_ int_to_bv 32) (str.len __string_0)))",
              "(> (fld.Box.x 3) #x00000000) | (bvsgt (fld.Box.x 3) #x00000000)",
              "(>= (select arr.content.0 1) __int_0) | (bvsge (select arr.content.0 1) __int_0)",
              // integer negation on bit-vectors
              "(= (- __int_0) #x00000001) | (= (bvneg __int_0) #x00000001)",
              "(= (- __long_0 ((_ sign_extend 32) __int_0)) __long_0) | " +
                  "(= (bvsub __long_0 ((_ sign_extend 32) __int_0)) __long_0)",
              // rounding modes
              "(fp.lt (fp.add (RNE RoundingMode) __double_0 __double_0) __double_0) | " +
                  "(fp.lt (fp.add RNE __double_0 __double_0) __double_0)",
              // legacy names and negative numerals
              "(= (bv2int __int_0) -1) | (= (ubv_to_int __int_0) (- 1))",
              "(= (str.to.int __string_0) 5) | (= (str.to_int __string_0) 5)",
              // integer arithmetic stays integer arithmetic
              "(< (+ (str.len __string_0) 1) 5) | (< (+ (str.len __string_0) 1) 5)",
              // ite over bit-vectors
              "(< (ite (= __int_0 #x00000000) __int_0 #x00000001) #x00000005) | " +
                  "(bvslt (ite (= __int_0 #x00000000) __int_0 #x00000001) #x00000005)",
              // standard SMT-LIB is unchanged
              "(= __string_0 \"a\"\"b\") | (= __string_0 \"a\"\"b\")",
          ],
  )
  fun `normalizes SPouT terms`(input: String, expected: String) {
    assertEquals(expected, normalize(input))
  }

  @ParameterizedTest
  @CsvSource(
      delimiter = '|',
      value =
          [
              "(forall ((x (_ BitVec 32))) (< x #x00000005)) | " +
                  "(forall ((x (_ BitVec 32))) (bvslt x #x00000005))",
              "(let ((y __int_0)) (< y #x00000005)) | (let ((y __int_0)) (bvslt y #x00000005))",
          ],
  )
  fun `tracks sorts of bound variables`(input: String, expected: String) {
    assertEquals(expected, normalize(input))
  }
}
