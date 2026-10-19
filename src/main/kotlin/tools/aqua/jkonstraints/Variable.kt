/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints

import tools.aqua.konstraints.smt.QuotingRule
import tools.aqua.konstraints.smt.Sort

/** A declared constant of an SMT sort, identified by its name and sort. */
class Variable(val name: String, val sort: Sort) {
  /** The sort in SMT-LIB syntax, e.g. `(_ BitVec 32)`. */
  val sortName: String = sort.toSMTString(QuotingRule.SAME_AS_INPUT, false)

  override fun equals(other: Any?) =
      other is Variable && name == other.name && sortName == other.sortName

  override fun hashCode() = 31 * name.hashCode() + sortName.hashCode()

  override fun toString() = name
}
