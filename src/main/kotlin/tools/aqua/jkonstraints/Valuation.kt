/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import tools.aqua.konstraints.smt.Expression

/** One assignment of a [Valuation]: the [variable], its JVM [value] and the SMT [literal]. */
class ValuationEntry(val variable: Variable, val value: Any?, val literal: Expression<*>?) {
  override fun toString() = "${variable.name}:=$value"
}

/**
 * An assignment of values to variables, typically a model returned by [SolverContext.solve].
 * Values are JVM objects as produced by [Values.toJava]; entries read from a model also keep the
 * SMT value term.
 */
class Valuation : Iterable<ValuationEntry> {
  private val values = LinkedHashMap<String, ValuationEntry>()

  /** Sets [variable] to the JVM [value]. Replaces any previous value of a variable of that name. */
  fun setValue(variable: Variable, value: Any?) {
    values[variable.name] = ValuationEntry(variable, value, null)
  }

  /** Sets [variable] to the SMT value term [literal], decoding it with [Values.toJava]. */
  fun setLiteral(variable: Variable, literal: Expression<*>) {
    values[variable.name] = ValuationEntry(variable, Values.toJava(literal), literal)
  }

  fun getValue(name: String): Any? = values[name]?.value

  fun getValue(variable: Variable): Any? = values[variable.name]?.takeIf { it.variable == variable }?.value

  /** The SMT value term of [name], if the value was read from a model. */
  fun getLiteral(name: String): Expression<*>? = values[name]?.literal

  fun containsValueFor(name: String) = name in values

  fun containsValueFor(variable: Variable) = values[variable.name]?.variable == variable

  fun entries(): Collection<ValuationEntry> = values.values

  fun variables(): Set<Variable> = values.values.mapTo(LinkedHashSet()) { it.variable }

  fun size() = values.size

  fun isEmpty() = values.isEmpty()

  fun clear() = values.clear()

  override fun iterator() = values.values.iterator()

  override fun toString() = values.values.joinToString(", ")
}
