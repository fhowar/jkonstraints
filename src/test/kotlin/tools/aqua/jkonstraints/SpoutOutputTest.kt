/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import java.io.File
import org.junit.jupiter.api.DynamicContainer.dynamicContainer
import org.junit.jupiter.api.DynamicNode
import org.junit.jupiter.api.DynamicTest
import org.junit.jupiter.api.DynamicTest.dynamicTest
import org.junit.jupiter.api.TestFactory

/** Parses SMT-LIB printed by SPouT strictly, i.e., checks that SPouT emits standard SMT-LIB. */
class SpoutOutputTest {
  private val resources = File(javaClass.getResource("/spout")!!.toURI())

  /**
   * `spout/operators.smt2` applies every operator of SPouT's `tools.aqua.smt.OperatorComparator`
   * (and every kind of constant) to arguments of the right sorts, as printed by SPouT itself.
   */
  @TestFactory
  fun `every SPouT operator is standard SMT-LIB`(): List<DynamicTest> {
    val lines = File(resources, "operators.smt2").readLines().filter { it.isNotBlank() }
    val (declarations, assertions) = lines.partition { it.startsWith("(declare") }
    val smt = SmtContext().apply { declare(declarations.joinToString("\n")) }
    return assertions.map { assertion -> dynamicTest(assertion) { smt.load(assertion) } }
  }

  /** `spout/ *.trace` are SPouT traces; their terms are read as DSE reads them. */
  @TestFactory
  fun `SPouT traces are standard SMT-LIB`(): List<DynamicNode> =
      resources
          .listFiles { file -> file.extension == "trace" }!!
          .sorted()
          .map { trace ->
            val smt = SmtContext()
            dynamicContainer(
                trace.name,
                trace.readLines().mapNotNull { line ->
                  val match = TRACE_LINE.matchEntire(line) ?: return@mapNotNull null
                  val (kind, command) = match.destructured
                  dynamicTest(line.take(120)) {
                    if (kind == "DECLARE" || kind == "AUXILIARY") smt.declare(command)
                    else smt.load(command)
                  }
                },
            )
          }

  private companion object {
    val TRACE_LINE = Regex("""\[(DECLARE|AUXILIARY|DECISION|ASSUMPTION)] (\(.*\))(?: // .*)?""")
  }
}
