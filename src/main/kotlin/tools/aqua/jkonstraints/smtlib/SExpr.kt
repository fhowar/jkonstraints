/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints.smtlib

/**
 * Minimal untyped S-expression tree used to pre-process SMT-LIB text before it is handed to the
 * konstraints parser. Atoms keep their exact source text (including quotes of string literals and
 * quoted symbols), so printing an unchanged tree reproduces the input up to whitespace.
 */
sealed class SExpr {
  /** A token that is not a parenthesis: symbol, keyword, numeral, string literal, ... */
  data class Atom(val text: String) : SExpr() {
    override fun toString() = text
  }

  /** A parenthesized list of S-expressions. */
  data class SList(val items: List<SExpr>) : SExpr() {
    constructor(vararg items: SExpr) : this(items.toList())

    val head: SExpr?
      get() = items.firstOrNull()

    val args: List<SExpr>
      get() = items.drop(1)

    override fun toString() = print(this)
  }

  /** The text of this node if it is an atom, null otherwise. */
  val atom: String?
    get() = (this as? Atom)?.text

  companion object {
    @JvmStatic fun atom(text: String) = Atom(text)

    @JvmStatic fun list(vararg items: SExpr) = SList(items.toList())

    /** Parses all top-level S-expressions in [input]. Comments (`;` to end of line) are skipped. */
    @JvmStatic
    fun parseAll(input: String): List<SExpr> {
      val result = mutableListOf<SExpr>()
      val stack = ArrayDeque<MutableList<SExpr>>()
      var i = 0

      fun emit(expr: SExpr) {
        if (stack.isEmpty()) result += expr else stack.last() += expr
      }

      while (i < input.length) {
        val c = input[i]
        when {
          c.isWhitespace() -> i++
          c == ';' -> {
            while (i < input.length && input[i] != '\n') i++
          }
          c == '(' -> {
            stack.addLast(mutableListOf())
            i++
          }
          c == ')' -> {
            require(stack.isNotEmpty()) { "Unbalanced ')' at offset $i" }
            emit(SList(stack.removeLast()))
            i++
          }
          c == '"' -> {
            val start = i++
            while (true) {
              require(i < input.length) { "Unterminated string literal at offset $start" }
              if (input[i] == '"') {
                if (i + 1 < input.length && input[i + 1] == '"') i += 2
                else {
                  i++
                  break
                }
              } else i++
            }
            emit(Atom(input.substring(start, i)))
          }
          c == '|' -> {
            val start = i++
            while (i < input.length && input[i] != '|') i++
            require(i < input.length) { "Unterminated quoted symbol at offset $start" }
            i++
            emit(Atom(input.substring(start, i)))
          }
          else -> {
            val start = i
            while (
                i < input.length &&
                    !input[i].isWhitespace() &&
                    input[i] != '(' &&
                    input[i] != ')' &&
                    input[i] != ';' &&
                    input[i] != '"') i++
            emit(Atom(input.substring(start, i)))
          }
        }
      }

      require(stack.isEmpty()) { "Unbalanced '(': ${stack.size} list(s) not closed" }
      return result
    }

    /** Prints [expr] without recursion, so arbitrarily deep terms can be printed. */
    @JvmStatic
    fun print(expr: SExpr): String {
      val builder = StringBuilder()
      val stack = ArrayDeque<Any>()
      stack.addLast(expr)
      while (stack.isNotEmpty()) {
        when (val top = stack.removeLast()) {
          is String -> builder.append(top)
          is Atom -> builder.append(top.text)
          is SList -> {
            builder.append('(')
            stack.addLast(")")
            top.items.indices.reversed().forEach { index ->
              stack.addLast(top.items[index])
              if (index > 0) stack.addLast(" ")
            }
          }
        }
      }
      return builder.toString()
    }
  }
}
