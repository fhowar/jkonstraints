/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

class SmtContextTest {
  private fun context() =
      SmtContext().apply {
        declare(
            """
            (declare-fun __int_0 () (_ BitVec 32))
            (declare-fun __byte_0 () (_ BitVec 8))
            (declare-fun __string_0 () String)
            (declare-fun __float_0 () (_ FloatingPoint 8 24))
            (declare-fun __double_0 () (_ FloatingPoint 11 53))
            """
        )
      }

  /** Terms as SPouT emits them since it prints standard SMT-LIB 2.7. */
  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "(not (= __int_0 #x00000000))",
              "(bvsle #x00000000 __int_0)",
              "(bvslt __int_0 ((_ int_to_bv 32) (str.len __string_0)))",
              "(= (bvneg __int_0) #x00000001)",
              "(= __byte_0 #xff)",
              "(fp.lt (fp.add RNE __double_0 __double_0) ((_ to_fp 11 53) RNE __int_0))",
              "(= ((_ fp.to_sbv 32) RTZ __float_0) __int_0)",
              "(= (str.to_int __string_0) (- 1))",
              "(<= (ubv_to_int __int_0) (str.len __string_0))",
              // Java's floating-point remainder
              "(fp.isNegative (let ((__rem_x __double_0) (__rem_y __double_0))" +
                  " (let ((__rem_r (fp.rem __rem_x __rem_y)))" +
                  " (ite (and (not (fp.isZero __rem_r))" +
                  " (not (= (fp.isNegative __rem_r) (fp.isNegative __rem_x))))" +
                  " (ite (fp.isNegative __rem_x) (fp.sub RNE __rem_r (fp.abs __rem_y))" +
                  " (fp.add RNE __rem_r (fp.abs __rem_y))) __rem_r))))",
          ]
  )
  fun `parses standard SMT-LIB emitted by SPouT`(term: String) {
    context().parseFormula(term)
  }

  /** The lenient syntax that SPouT used to emit is not SMT-LIB and is rejected. */
  @ParameterizedTest
  @ValueSource(
      strings =
          [
              "(!= __int_0 #x00000000)",
              "(< __int_0 #x00000005)",
              "(fp.lt (fp.add (RNE RoundingMode) __double_0 __double_0) __double_0)",
              "(= (str.to.int __string_0) 5)",
          ]
  )
  fun `rejects non-standard SMT-LIB`(term: String) {
    assertThrows<Exception> { context().parseFormula(term) }
  }

  @Test
  fun `repeated declarations are ignored, conflicting ones rejected`() {
    val smt = context()
    smt.declare("(declare-fun __int_0 () (_ BitVec 32))")
    smt.declare("(declare-const __int_0 (_ BitVec 32))")
    assertThrows<IllegalArgumentException> { smt.declare("(declare-fun __int_0 () Int)") }
  }

  @Test
  fun `load returns assertions without storing them`() {
    val smt = context()
    val assertions =
        smt.load(
            """
            (push 1)
            (declare-fun __int_1 () (_ BitVec 32))
            (assert (bvslt __int_0 __int_1))
            (pop 1)
            (assert (= __int_1 #x00000000))
            """
        )
    assertEquals(2, assertions.size)
    assertTrue(smt.isDeclared("__int_1"))
    assertTrue(smt.program.commands.none { it is tools.aqua.konstraints.smt.Assert })
  }
}
