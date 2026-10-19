/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import java.util.Properties
import tools.aqua.konstraints.smt.ALL
import tools.aqua.konstraints.smt.ArraySort
import tools.aqua.konstraints.smt.BoolSort
import tools.aqua.konstraints.smt.BooleanOptionValue
import tools.aqua.konstraints.smt.DeclareFun
import tools.aqua.konstraints.smt.DeclareSort
import tools.aqua.konstraints.smt.DefineFun
import tools.aqua.konstraints.smt.Expression
import tools.aqua.konstraints.smt.MutableSMTProgram
import tools.aqua.konstraints.smt.SatStatus
import tools.aqua.konstraints.smt.SetLogic
import tools.aqua.konstraints.smt.SetOption
import tools.aqua.konstraints.smt.Sort
import tools.aqua.konstraints.smt.Symbol
import tools.aqua.konstraints.smt.UserDeclaredExpression
import tools.aqua.konstraints.smt.UserDeclaredSMTFunction
import tools.aqua.konstraints.dsl.UserDeclaredSMTFunction0
import tools.aqua.konstraints.dsl.UserDeclaredSMTFunctionN
import tools.aqua.konstraints.smt.UserDeclaredSort
import tools.aqua.konstraints.smt.UserDefinedExpression
import tools.aqua.konstraints.smt.Variable as KVariable
import tools.aqua.konstraints.solvers.SolverDialect
import tools.aqua.konstraints.solvers.SolverException
import tools.aqua.konstraints.solvers.SolverSession

/** A solver that runs an SMT solver binary and talks to it through a konstraints [SolverSession]. */
class ProcessSolver
@JvmOverloads
constructor(
    val dialect: SolverDialect,
    val command: List<String> = dialect.command,
    val timeoutMillis: Long = 0,
) : ConstraintSolver {
  override fun createContext(): SolverContext = ProcessSolverContext(this)
}

private class ProcessSolverContext(solver: ProcessSolver) : SolverContext {
  private val session = SolverSession(solver.dialect, solver.command)

  /** Mirror of everything declared to the solver; used to parse terms in solver responses. */
  private val declarations = MutableSMTProgram(emptyList()).apply { setLogic(ALL) }
  private val declaredSorts = HashSet<Symbol>()
  private val declaredFunctions = HashSet<Symbol>()

  /** Constants occurring in the assertions of each assertion level, innermost last. */
  private val levels = ArrayDeque<LinkedHashMap<Symbol, Expression<*>>>()

  init {
    levels.addLast(LinkedHashMap())
    try {
      session.send(SetOption(":global-declarations", BooleanOptionValue(true)))
      session.send(SetOption(":produce-models", BooleanOptionValue(true)))
      session.send(SetLogic(ALL))
      if (solver.timeoutMillis > 0) session.setTimeout(solver.timeoutMillis)
    } catch (e: Exception) {
      session.close()
      throw e
    }
  }

  override fun push() {
    session.push()
    levels.addLast(LinkedHashMap())
  }

  override fun pop(n: Int) {
    require(n in 0 until levels.size) { "Cannot pop $n of ${levels.size - 1} levels" }
    session.pop(n)
    repeat(n) { levels.removeLast() }
  }

  override fun add(expressions: List<Expression<BoolSort>>) {
    expressions.forEach { expression ->
      declare(expression)
      session.assert(expression)
    }
  }

  override fun solve(valuation: Valuation?): Result {
    val status =
        try {
          session.checkSat()
        } catch (_: SolverException) {
          return Result.ERROR
        }

    return when (status) {
      SatStatus.SAT -> {
        if (valuation != null) readModel(valuation)
        Result.SAT
      }
      SatStatus.UNSAT -> Result.UNSAT
      SatStatus.UNKNOWN -> unknownResult()
      else -> Result.ERROR
    }
  }

  override fun close() = session.close()

  private fun unknownResult(): Result {
    val reason =
        try {
          session.getInfo(":reason-unknown")
        } catch (_: Exception) {
          ""
        }
    return if ("timeout" in reason || "canceled" in reason) Result.TIMEOUT else Result.DONT_KNOW
  }

  private fun readModel(valuation: Valuation) {
    val constants =
        levels
            .flatMap { it.values }
            .distinctBy { it.symbol }
            // array values are not representable as JVM values
            .filter { it.sort !is ArraySort<*, *> }
    if (constants.isEmpty()) return

    val values =
        try {
          session.getValue(constants, declarations).values.toList()
        } catch (_: Exception) {
          // fall back to one query per constant, so one unparsable value does not lose the model
          constants.map { constant ->
            try {
              session.getValue(listOf(constant), declarations).values.single()
            } catch (_: Exception) {
              null
            }
          }
        }

    (constants zip values).forEach { (constant, value) ->
      if (value != null) valuation.setLiteral(Variable(constant.symbol.toString(), constant.sort), value)
    }
  }

  /** Declares all symbols of [expression] that the solver does not know yet. */
  private fun declare(expression: Expression<*>) {
    ExpressionUtil.subterms(expression).forEach { term ->
      when (term) {
        is UserDeclaredExpression -> declareFunction(term.func as? UserDeclaredSMTFunction<*>, term)
        is KVariable -> declareFunction(null, term)
        is UserDefinedExpression -> {
          if (declaredFunctions.add(term.symbol)) {
            declare(term.definition.term)
            session.send(DefineFun(term.definition))
            declarations.defineFun(term.definition)
          }
        }
        else -> Unit
      }
      if (term.children.isEmpty() && (term is UserDeclaredExpression || term is KVariable)) {
        if (levels.none { term.symbol in it }) levels.last()[term.symbol] = term
      }
    }
  }

  private fun declareFunction(declared: UserDeclaredSMTFunction<*>?, term: Expression<*>) {
    val symbol = term.symbol as Symbol
    if (symbol in declaredFunctions) return

    val func =
        declared
            ?: if (term.children.isEmpty()) UserDeclaredSMTFunction0(symbol, term.sort)
            else UserDeclaredSMTFunctionN(symbol, term.sort, term.children.map { it.sort })

    (func.parameters + func.sort).forEach(::declareSort)
    session.send(DeclareFun(func))
    declarations.declareFun(func)
    declaredFunctions += symbol
  }

  private fun declareSort(sort: Sort) {
    if (sort is UserDeclaredSort && declaredSorts.add(sort.symbol)) {
      session.send(DeclareSort(sort.symbol, sort.parameters.size))
      declarations.declareSort(sort.symbol, sort.parameters.size)
    }
    sort.parameters.forEach(::declareSort)
  }
}

/** Provides `z3` and `cvc5` as [ProcessSolver]s. */
class ProcessSolverProvider : SolverProvider {
  override val names = listOf("z3", "cvc5")

  override fun createSolver(name: String, configuration: Properties): ConstraintSolver {
    val dialect = if (name == "cvc5") SolverDialect.CVC5 else SolverDialect.Z3
    val command =
        configuration.getProperty("$name.command")?.trim()?.split(Regex("\\s+")) ?: dialect.command
    val timeout = configuration.getProperty("$name.timeout")?.toLong() ?: 0
    return ProcessSolver(dialect, command, timeout)
  }
}
