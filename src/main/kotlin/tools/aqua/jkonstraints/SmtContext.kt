/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import tools.aqua.jkonstraints.smtlib.SExpr
import tools.aqua.jkonstraints.smtlib.SpoutDialect
import tools.aqua.konstraints.parser.SMTScriptParser
import tools.aqua.konstraints.smt.ALL
import tools.aqua.konstraints.smt.BoolSort
import tools.aqua.konstraints.smt.Expression
import tools.aqua.konstraints.smt.MutableSMTProgram
import tools.aqua.konstraints.smt.SMTBool
import tools.aqua.konstraints.smt.cast
import tools.aqua.konstraints.smt.toSymbol

/**
 * Parses SMT-LIB text sent by client tools into konstraints expressions.
 *
 * The context is incremental: declarations from earlier calls stay visible, so a trace can be fed
 * line by line. Re-declaring a symbol with the same sort is ignored (client tools tend to resend
 * their declarations), re-declaring it with a different sort is an error.
 *
 * Input is first normalized by [SpoutDialect], so the lenient SMT-LIB emitted by SPouT is accepted.
 * Commands that do not declare or assert anything (`set-logic`, `check-sat`, `push`, ...) are
 * ignored; the context always uses logic `ALL`.
 */
class SmtContext {
  /** The konstraints program holding all declarations made so far. */
  val program = MutableSMTProgram(emptyList()).apply { setLogic(ALL) }

  private val dialect = SpoutDialect()

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
    val assertions = mutableListOf<Expression<BoolSort>>()

    for (command in SExpr.parseAll(script)) {
      require(command is SExpr.SList && command.head is SExpr.Atom) { "Not a command: $command" }

      when (val name = command.head!!.atom) {
        "declare-fun",
        "declare-const" -> {
          val symbol = command.items[1].atom!!
          val sort = command.items.last()
          val known = dialect.sorts[symbol]
          if (known != null && isDeclared(symbol)) {
            require(known == sort) { "$symbol is already declared with sort $known, not $sort" }
          } else {
            dialect.command(command)
            SMTScriptParser.parseInto(program, SExpr.print(command))
          }
        }
        "define-fun",
        "declare-sort",
        "define-sort" -> SMTScriptParser.parseInto(program, SExpr.print(dialect.command(command)))
        "assert" -> assertions += formula(dialect.term(command.items[1]))
        "set-logic",
        "set-option",
        "set-info",
        "check-sat",
        "get-model",
        "get-value",
        "push",
        "pop",
        "exit" -> Unit
        else -> throw IllegalArgumentException("Unsupported command $name")
      }
    }

    return assertions
  }

  /** Parses a single Boolean term, e.g. `(bvslt __int_0 #x00000005)`. */
  fun parseFormula(term: String): Expression<BoolSort> =
      formula(dialect.term(SExpr.parseAll(term).single()))

  /** Parses a single term of any sort. */
  fun parseTerm(term: String): Expression<*> =
      SMTScriptParser.parseTerm(SExpr.print(dialect.term(SExpr.parseAll(term).single())), program)

  /** Returns true if [symbol] is declared in this context. */
  fun isDeclared(symbol: String) = symbol.toSymbol() in program.context

  /** Returns the variable (a declared constant) named [name]. */
  fun variable(name: String): Variable {
    val func =
        requireNotNull(program.context.getFuncOrNull(name.toSymbol())) { "$name is not declared" }
    require(func.parameters.isEmpty()) { "$name is a function, not a constant" }
    return Variable(name, func.sort)
  }

  private fun formula(term: SExpr): Expression<BoolSort> {
    val expr = SMTScriptParser.parseTerm(SExpr.print(term), program)
    require(expr.sort == SMTBool) { "Expected a Bool term, got ${expr.sort}: $term" }
    return expr.cast()
  }
}
