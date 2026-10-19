/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import tools.aqua.konstraints.parser.SMTScriptParser
import tools.aqua.konstraints.parser.ScriptHandler
import tools.aqua.konstraints.smt.ALL
import tools.aqua.konstraints.smt.BoolSort
import tools.aqua.konstraints.smt.Expression
import tools.aqua.konstraints.smt.MutableSMTProgram
import tools.aqua.konstraints.smt.SMTBool
import tools.aqua.konstraints.smt.Sort
import tools.aqua.konstraints.smt.Symbol
import tools.aqua.konstraints.smt.cast
import tools.aqua.konstraints.smt.toSymbol

/**
 * Parses SMT-LIB text sent by client tools into konstraints expressions.
 *
 * The context is incremental: declarations from earlier calls stay visible, so a trace can be fed
 * line by line. Re-declaring a symbol with the same sort is ignored (client tools tend to resend
 * their declarations), re-declaring it with a different sort is an error.
 *
 * Input must be standard SMT-LIB; it is parsed by konstraints. `push` and `pop` are ignored (the
 * declarations of a trace are global); the context always uses logic `ALL`.
 */
class SmtContext {
  /** The konstraints program holding all declarations made so far. */
  val program = MutableSMTProgram(emptyList()).apply { setLogic(ALL) }

  /** Processes declarations in [script]. Assertions in [script] are rejected. */
  fun declare(script: String) {
    val assertions = load(script)
    require(assertions.isEmpty()) { "declare() does not accept assertions, use load()" }
  }

  /**
   * Processes all commands in [script] and returns the asserted formulas, in order. Declarations
   * are added to this context; the assertions are not stored.
   */
  fun load(script: String): List<Expression<BoolSort>> {
    val handler = Handler()
    SMTScriptParser.parseInto(program, script, handler)
    return handler.assertions
  }

  /** Parses a single Boolean term, e.g. `(bvslt __int_0 #x00000005)`. */
  fun parseFormula(term: String): Expression<BoolSort> {
    val expr = parseTerm(term)
    require(expr.sort == SMTBool) { "Expected a Bool term, got ${expr.sort}: $term" }
    return expr.cast()
  }

  /** Parses a single term of any sort. */
  fun parseTerm(term: String): Expression<*> = SMTScriptParser.parseTerm(term, program)

  /** Returns true if [symbol] is declared in this context. */
  fun isDeclared(symbol: String) = symbol.toSymbol() in program.context

  /** Returns the variable (a declared constant) named [name]. */
  fun variable(name: String): Variable {
    val func =
        requireNotNull(program.context.getFuncOrNull(name.toSymbol())) { "$name is not declared" }
    require(func.parameters.isEmpty()) { "$name is a function, not a constant" }
    return Variable(name, func.sort)
  }

  private class Handler : ScriptHandler {
    val assertions = mutableListOf<Expression<BoolSort>>()

    override fun assert(program: MutableSMTProgram, term: Expression<BoolSort>) {
      assertions += term
    }

    override fun declareConst(program: MutableSMTProgram, symbol: Symbol, sort: Sort) =
        declareFun(program, symbol, emptyList(), sort)

    override fun declareFun(
        program: MutableSMTProgram,
        symbol: Symbol,
        parameters: List<Sort>,
        sort: Sort,
    ) {
      val known = program.context.getFuncOrNull(symbol)
      if (known == null) {
        program.declareFun(symbol, parameters, sort)
      } else {
        require(known.parameters == parameters && known.sort == sort) {
          "$symbol is already declared as (${known.parameters.joinToString(" ")}) ${known.sort}, " +
              "not (${parameters.joinToString(" ")}) $sort"
        }
      }
    }

    override fun push(program: MutableSMTProgram, n: Int) = Unit

    override fun pop(program: MutableSMTProgram, n: Int) = Unit
  }
}
