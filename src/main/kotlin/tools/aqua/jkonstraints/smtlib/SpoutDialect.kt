/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints.smtlib

import tools.aqua.jkonstraints.smtlib.SExpr.Atom
import tools.aqua.jkonstraints.smtlib.SExpr.SList

/**
 * Rewrites the lenient SMT-LIB that concolic executors (in particular SPouT) emit into standard
 * SMT-LIB 2.7 that the strict konstraints parser accepts. Standard SMT-LIB passes through unchanged.
 *
 * Rewrites:
 * - `(!= a b)` becomes `(not (= a b))`
 * - `(RNE RoundingMode)` (and the other rounding modes) becomes `RNE`
 * - arithmetic and comparison operators on bit-vectors become signed bit-vector operators: `-` with
 *   one argument becomes `bvneg`, `+ - *` become `bvadd bvsub bvmul`, `< <= > >=` become `bvslt
 *   bvsle bvsgt bvsge`
 * - negative numerals `-5` become `(- 5)`
 * - legacy names: `bv2int` -> `ubv_to_int`, `(_ int2bv n)` -> `(_ int_to_bv n)`, `str.to.int` ->
 *   `str.to_int`, `int.to.str` -> `str.from_int`, `str.to.re` -> `str.to_re`, `str.in.re` ->
 *   `str.in_re`
 *
 * Recognizing bit-vector operands requires the sorts of declared symbols. They are tracked in [sorts]
 * (symbol to result sort), which is updated by every declaration that passes through [command].
 */
class SpoutDialect(val sorts: MutableMap<String, SExpr> = HashMap()) {

  /** Normalizes a complete script. */
  fun script(input: String): String =
      SExpr.parseAll(input).joinToString("\n") { SExpr.print(command(it)) }

  /** Normalizes a single command and records the sorts of symbols it declares. */
  fun command(command: SExpr): SExpr {
    if (command !is SList) return command
    val items = command.items
    return when (command.head?.atom) {
      "declare-fun" -> {
        sorts[items[1].atom!!] = items[3]
        command
      }
      "declare-const" -> {
        sorts[items[1].atom!!] = items[2]
        command
      }
      "define-fun" -> {
        val name = items[1].atom!!
        val parameters = (items[2] as SList).items.map { it as SList }
        val body = withBindings(parameters.map { it.items[0].atom!! to it.items[1] }) {
          term(items[4])
        }
        sorts[name] = items[3]
        SList(items.take(4) + body)
      }
      "assert" -> SList(items[0], term(items[1]))
      else -> command
    }
  }

  /** Normalizes a single term. */
  fun term(term: SExpr): SExpr = rewrite(term).expr

  private class Typed(val expr: SExpr, val sort: SExpr?)

  private inline fun <T> withBindings(bindings: List<Pair<String, SExpr?>>, block: () -> T): T {
    val saved = bindings.map { (name, _) -> name to sorts[name] }
    bindings.forEach { (name, sort) -> if (sort != null) sorts[name] = sort else sorts.remove(name) }
    try {
      return block()
    } finally {
      saved.forEach { (name, sort) -> if (sort != null) sorts[name] = sort else sorts.remove(name) }
    }
  }

  private val rewrite: DeepRecursiveFunction<SExpr, Typed> = DeepRecursiveFunction { expr ->
    when (expr) {
      is Atom -> rewriteAtom(expr)
      is SList -> {
        val head = expr.head
        when {
          head == null -> Typed(expr, null)
          head.atom == "_" -> indexed(expr)
          expr.items.size == 2 &&
              expr.items[1].atom == "RoundingMode" &&
              head.atom in ROUNDING_MODES -> Typed(head, ROUNDING_MODE)
          head.atom == "forall" || head.atom == "exists" -> {
            val variables = (expr.items[1] as SList).items.map { it as SList }
            val body =
                withBindings(variables.map { it.items[0].atom!! to it.items[1] }) {
                  callRecursive(expr.items[2]).expr
                }
            Typed(SList(expr.items[0], expr.items[1], body), BOOL)
          }
          head.atom == "let" -> {
            val bindings =
                (expr.items[1] as SList).items.map { binding ->
                  binding as SList
                  binding.items[0].atom!! to callRecursive(binding.items[1])
                }
            val body =
                withBindings(bindings.map { (name, typed) -> name to typed.sort }) {
                  callRecursive(expr.items[2])
                }
            Typed(
                SList(
                    expr.items[0],
                    SList(bindings.map { (name, typed) -> SList(Atom(name), typed.expr) }),
                    body.expr,
                ),
                body.sort,
            )
          }
          head is SList && head.head?.atom == "as" -> {
            // ((as const S) v)
            val args = expr.args.map { callRecursive(it).expr }
            Typed(SList(listOf(head) + args), head.items.getOrNull(2))
          }
          else -> {
            val typedHead = if (head is SList) callRecursive(head) else null
            val args = expr.args.map { callRecursive(it) }
            application(typedHead?.expr ?: head, args)
          }
        }
      }
    }
  }

  private fun rewriteAtom(atom: Atom): Typed {
    val text = atom.text
    return when {
      text.startsWith("#x") -> Typed(atom, bitVec((text.length - 2) * 4))
      text.startsWith("#b") -> Typed(atom, bitVec(text.length - 2))
      text.startsWith("\"") -> Typed(atom, STRING)
      text == "true" || text == "false" -> Typed(atom, BOOL)
      NUMERAL.matches(text) -> Typed(atom, INT)
      DECIMAL.matches(text) -> Typed(atom, REAL)
      NEGATIVE_NUMERAL.matches(text) ->
          Typed(SList(Atom("-"), Atom(text.substring(1))), if ('.' in text) REAL else INT)
      text in RENAMED -> Typed(Atom(RENAMED.getValue(text)), null)
      text in ROUNDING_MODES -> Typed(atom, ROUNDING_MODE)
      else -> Typed(atom, sorts[text])
    }
  }

  /** Indexed identifiers that are used as constants, e.g. `(_ bv5 32)` or `(_ NaN 8 24)`. */
  private fun indexed(expr: SList): Typed {
    val name = expr.items.getOrNull(1)?.atom
    val renamed =
        if (name != null && name in RENAMED_INDEXED)
            SList(listOf(expr.items[0], Atom(RENAMED_INDEXED.getValue(name))) + expr.items.drop(2))
        else expr
    val sort =
        when {
          name == null -> null
          name.startsWith("bv") && name.drop(2).all(Char::isDigit) ->
              expr.items.getOrNull(2)?.atom?.toIntOrNull()?.let(::bitVec)
          name in FP_CONSTANTS -> SList(Atom("_"), Atom("FloatingPoint"), expr.items[2], expr.items[3])
          else -> null
        }
    return Typed(renamed, sort)
  }

  private fun application(head: SExpr, args: List<Typed>): Typed {
    val exprs = args.map { it.expr }
    val anyBitVec = args.any { isBitVec(it.sort) }

    if (head is SList) return Typed(SList(listOf(head) + exprs), indexedResultSort(head, args))

    val name = RENAMED[head.atom!!] ?: head.atom!!
    fun apply(op: String = name) = SList(listOf(Atom(op)) + exprs)

    return when {
      name == "!=" ->
          Typed(
              if (exprs.size == 2) SList(Atom("not"), SList(Atom("="), exprs[0], exprs[1]))
              else apply("distinct"),
              BOOL,
          )
      anyBitVec && name in BITVEC_COMPARISONS -> Typed(apply(BITVEC_COMPARISONS.getValue(name)), BOOL)
      anyBitVec && name == "-" && exprs.size == 1 -> Typed(apply("bvneg"), args[0].sort)
      anyBitVec && name in BITVEC_ARITHMETIC -> {
        // the bit-vector operators are binary in SMT-LIB, so fold n-ary applications to the left
        val op = Atom(BITVEC_ARITHMETIC.getValue(name))
        Typed(exprs.drop(1).fold(exprs[0]) { acc, arg -> SList(op, acc, arg) }, args[0].sort)
      }
      name in BOOL_RESULT || name.startsWith("fp.is") -> Typed(apply(), BOOL)
      name in BITVEC_SAME_SORT -> Typed(apply(), args.firstOrNull()?.sort)
      name == "concat" -> {
        val widths = args.map { bitVecWidth(it.sort) }
        Typed(apply(), if (widths.all { it != null }) bitVec(widths.sumOf { it!! }) else null)
      }
      name == "ite" -> Typed(apply(), args.getOrNull(1)?.sort ?: args.getOrNull(2)?.sort)
      name == "select" -> Typed(apply(), (args[0].sort as? SList)?.items?.getOrNull(2))
      name == "store" -> Typed(apply(), args[0].sort)
      name in INT_RESULT -> Typed(apply(), INT)
      name in ARITHMETIC -> Typed(apply(), if (args.any { it.sort == REAL }) REAL else INT)
      name in STRING_RESULT -> Typed(apply(), STRING)
      name in FP_ARITHMETIC -> Typed(apply(), args.firstOrNull { isFloatingPoint(it.sort) }?.sort)
      name == "fp" -> {
        val eb = bitVecWidth(args[1].sort)
        val sb = bitVecWidth(args[2].sort)
        Typed(
            apply(),
            if (eb != null && sb != null)
                SList(Atom("_"), Atom("FloatingPoint"), Atom("$eb"), Atom("${sb + 1}"))
            else null,
        )
      }
      else -> Typed(apply(), sorts[name])
    }
  }

  private fun indexedResultSort(head: SList, args: List<Typed>): SExpr? {
    if (head.head?.atom != "_") return null
    val index = head.items.drop(2).map { it.atom?.toIntOrNull() }
    val argWidth = bitVecWidth(args.firstOrNull()?.sort)
    return when (head.items[1].atom) {
      "extract" ->
          if (index.size == 2 && index.all { it != null }) bitVec(index[0]!! - index[1]!! + 1)
          else null
      "zero_extend",
      "sign_extend" -> if (argWidth != null && index[0] != null) bitVec(argWidth + index[0]!!) else null
      "repeat" -> if (argWidth != null && index[0] != null) bitVec(argWidth * index[0]!!) else null
      "rotate_left",
      "rotate_right" -> args.firstOrNull()?.sort
      "int_to_bv",
      "int2bv",
      "fp.to_sbv",
      "fp.to_ubv" -> index[0]?.let(::bitVec)
      "to_fp",
      "to_fp_unsigned" -> SList(Atom("_"), Atom("FloatingPoint"), head.items[2], head.items[3])
      else -> null
    }
  }

  private companion object {
    val BOOL = Atom("Bool")
    val INT = Atom("Int")
    val REAL = Atom("Real")
    val STRING = Atom("String")
    val ROUNDING_MODE = Atom("RoundingMode")

    fun bitVec(width: Int): SExpr = SList(Atom("_"), Atom("BitVec"), Atom("$width"))

    fun isBitVec(sort: SExpr?) =
        sort is SList && sort.items.size == 3 && sort.items[0].atom == "_" && sort.items[1].atom == "BitVec"

    fun bitVecWidth(sort: SExpr?) = if (isBitVec(sort)) (sort as SList).items[2].atom?.toIntOrNull() else null

    fun isFloatingPoint(sort: SExpr?) =
        (sort is SList && sort.items.getOrNull(1)?.atom == "FloatingPoint") ||
            sort?.atom in setOf("Float16", "Float32", "Float64", "Float128")

    val NUMERAL = Regex("0|[1-9][0-9]*")
    val DECIMAL = Regex("(0|[1-9][0-9]*)\\.[0-9]+")
    val NEGATIVE_NUMERAL = Regex("-(0|[1-9][0-9]*)(\\.[0-9]+)?")

    val ROUNDING_MODES =
        setOf(
            "RNE",
            "RNA",
            "RTP",
            "RTN",
            "RTZ",
            "roundNearestTiesToEven",
            "roundNearestTiesToAway",
            "roundTowardPositive",
            "roundTowardNegative",
            "roundTowardZero",
        )

    val RENAMED =
        mapOf(
            "bv2int" to "ubv_to_int",
            "str.to.int" to "str.to_int",
            "int.to.str" to "str.from_int",
            "str.to.re" to "str.to_re",
            "str.in.re" to "str.in_re",
        )

    val RENAMED_INDEXED = mapOf("int2bv" to "int_to_bv")

    val FP_CONSTANTS = setOf("+zero", "-zero", "+oo", "-oo", "NaN")

    val BITVEC_COMPARISONS = mapOf("<" to "bvslt", "<=" to "bvsle", ">" to "bvsgt", ">=" to "bvsge")

    val BITVEC_ARITHMETIC = mapOf("+" to "bvadd", "-" to "bvsub", "*" to "bvmul")

    val BITVEC_SAME_SORT =
        setOf(
            "bvneg",
            "bvnot",
            "bvadd",
            "bvsub",
            "bvmul",
            "bvudiv",
            "bvurem",
            "bvsdiv",
            "bvsrem",
            "bvsmod",
            "bvshl",
            "bvlshr",
            "bvashr",
            "bvand",
            "bvor",
            "bvxor",
            "bvnand",
            "bvnor",
            "bvxnor",
            "fp.neg",
            "fp.abs",
        )

    val BOOL_RESULT =
        setOf(
            "=",
            "distinct",
            "not",
            "and",
            "or",
            "xor",
            "=>",
            "<",
            "<=",
            ">",
            ">=",
            "bvult",
            "bvule",
            "bvugt",
            "bvuge",
            "bvslt",
            "bvsle",
            "bvsgt",
            "bvsge",
            "str.<",
            "str.<=",
            "str.prefixof",
            "str.suffixof",
            "str.contains",
            "str.in_re",
            "str.is_digit",
            "fp.eq",
            "fp.lt",
            "fp.leq",
            "fp.gt",
            "fp.geq",
        )

    val INT_RESULT =
        setOf(
            "str.len",
            "str.indexof",
            "str.to_code",
            "str.to_int",
            "ubv_to_int",
            "sbv_to_int",
            "div",
            "mod",
            "abs",
        )

    val ARITHMETIC = setOf("+", "-", "*")

    val STRING_RESULT =
        setOf(
            "str.++",
            "str.at",
            "str.substr",
            "str.replace",
            "str.replace_all",
            "str.from_code",
            "str.from_int",
            "str.lower",
            "str.upper",
        )

    val FP_ARITHMETIC =
        setOf(
            "fp.add",
            "fp.sub",
            "fp.mul",
            "fp.div",
            "fp.fma",
            "fp.sqrt",
            "fp.rem",
            "fp.roundToIntegral",
            "fp.min",
            "fp.max",
        )
  }
}
