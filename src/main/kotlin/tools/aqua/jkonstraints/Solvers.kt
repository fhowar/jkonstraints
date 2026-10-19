/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import java.util.Properties
import java.util.ServiceLoader
import tools.aqua.konstraints.smt.BoolSort
import tools.aqua.konstraints.smt.Expression

/** Outcome of a satisfiability check. */
enum class Result {
  SAT,
  UNSAT,
  /** The solver gave up (incomplete theory, resource limit, ...). */
  DONT_KNOW,
  TIMEOUT,
  /** The solver failed, e.g. crashed or rejected the input. */
  ERROR,
}

/**
 * An incremental solving context: an assertion stack with push and pop.
 *
 * Declarations are global, i.e., they survive pop. Symbols are declared to the solver
 * automatically when they first occur in an added expression.
 */
interface SolverContext : AutoCloseable {
  fun push()

  fun pop() = pop(1)

  fun pop(n: Int)

  fun add(expressions: List<Expression<BoolSort>>)

  fun add(vararg expressions: Expression<BoolSort>) = add(expressions.asList())

  /**
   * Checks satisfiability of the current assertions. If the result is [Result.SAT] and [valuation]
   * is not null, it receives the values of all constants occurring in the current assertions.
   */
  fun solve(valuation: Valuation?): Result

  fun isSatisfiable(): Result = solve(null)

  /** Releases the solver. Redeclared so Java callers need not handle a checked exception. */
  override fun close()

  /** Releases the solver. Equivalent to [close]. */
  fun dispose() = close()
}

/** A decision procedure that creates [SolverContext]s. */
interface ConstraintSolver {
  fun createContext(): SolverContext

  /** Solves [expression] in a fresh context. */
  fun solve(expression: Expression<BoolSort>, valuation: Valuation?): Result =
      createContext().use { context ->
        context.add(expression)
        context.solve(valuation)
      }

  fun isSatisfiable(expression: Expression<BoolSort>): Result = solve(expression, null)
}

/** Creates solvers by name; registered with [ServiceLoader]. */
interface SolverProvider {
  val names: List<String>

  /** Creates the solver [name] (one of [names]) configured by [configuration]. */
  fun createSolver(name: String, configuration: Properties): ConstraintSolver
}

/**
 * Entry point for obtaining solvers. Built-in names are `z3` and `cvc5`, both run as external
 * processes and must be on the `PATH`. Configuration keys (with `<name>` the solver name):
 * - `<name>.timeout`: timeout per check in milliseconds
 * - `<name>.command`: command line that starts the solver in interactive mode
 */
object SolverFactory {
  @JvmStatic
  @JvmOverloads
  fun createSolver(name: String, configuration: Properties = Properties()): ConstraintSolver {
    val provider =
        providers().firstOrNull { name in it.names }
            ?: throw IllegalArgumentException(
                "Unknown solver $name, known: ${providers().flatMap { it.names }}"
            )
    return provider.createSolver(name, configuration)
  }

  @JvmStatic
  fun providers(): List<SolverProvider> =
      ServiceLoader.load(SolverProvider::class.java, SolverFactory::class.java.classLoader).toList()
}
