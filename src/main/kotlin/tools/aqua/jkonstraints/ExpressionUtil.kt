/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import java.util.function.UnaryOperator
import tools.aqua.konstraints.smt.And
import tools.aqua.konstraints.smt.BoolSort
import tools.aqua.konstraints.smt.Expression
import tools.aqua.konstraints.smt.False
import tools.aqua.konstraints.smt.Not
import tools.aqua.konstraints.smt.Or
import tools.aqua.konstraints.smt.QuotingRule
import tools.aqua.konstraints.smt.True
import tools.aqua.konstraints.smt.UserDeclaredExpression
import tools.aqua.konstraints.smt.UserDefinedExpression
import tools.aqua.konstraints.smt.Variable as KVariable

/** Java-friendly helpers for building and inspecting konstraints expressions. */
object ExpressionUtil {
  @JvmField val TRUE: Expression<BoolSort> = True

  @JvmField val FALSE: Expression<BoolSort> = False

  @JvmStatic
  fun and(expressions: Collection<Expression<BoolSort>>): Expression<BoolSort> =
      when (expressions.size) {
        0 -> True
        1 -> expressions.single()
        else -> And(expressions.toList())
      }

  @JvmStatic
  fun and(vararg expressions: Expression<BoolSort>): Expression<BoolSort> =
      and(expressions.asList())

  @JvmStatic
  fun or(expressions: Collection<Expression<BoolSort>>): Expression<BoolSort> =
      when (expressions.size) {
        0 -> False
        1 -> expressions.single()
        else -> Or(expressions.toList())
      }

  @JvmStatic
  fun or(vararg expressions: Expression<BoolSort>): Expression<BoolSort> = or(expressions.asList())

  @JvmStatic fun not(expression: Expression<BoolSort>): Expression<BoolSort> = Not(expression)

  /** The declared constants (functions of arity 0) occurring in [expression]. */
  @JvmStatic
  fun freeVariables(expression: Expression<*>): Set<Variable> =
      subterms(expression)
          .filter { (it is UserDeclaredExpression || it is KVariable) && it.children.isEmpty() }
          .mapTo(LinkedHashSet()) { Variable(it.symbol.toString(), it.sort) }

  /** Names of all declared or defined functions and constants applied in [expression]. */
  @JvmStatic
  fun freeSymbols(expression: Expression<*>): Set<String> =
      subterms(expression)
          .filter {
            it is UserDeclaredExpression || it is KVariable || it is UserDefinedExpression
          }
          .mapTo(LinkedHashSet()) { it.symbol.toString() }

  /** Rewrites [expression] bottom-up with [transformation]; the sort must not change. */
  @JvmStatic
  fun <T : tools.aqua.konstraints.smt.Sort> transform(
      expression: Expression<T>,
      transformation: UnaryOperator<Expression<*>>,
  ): Expression<T> = expression.transform { transformation.apply(it) }

  /** [expression] in SMT-LIB syntax. */
  @JvmStatic
  fun toSmtLib(expression: Expression<*>): String =
      expression.toSMTString(QuotingRule.SAME_AS_INPUT, true)

  /** All subterms of [expression] in pre-order, without recursion. */
  @JvmStatic
  fun subterms(expression: Expression<*>): Sequence<Expression<*>> = sequence {
    val stack = ArrayDeque<Expression<*>>()
    stack.addLast(expression)
    while (stack.isNotEmpty()) {
      val current = stack.removeLast()
      yield(current)
      current.children.asReversed().forEach { stack.addLast(it) }
    }
  }
}
