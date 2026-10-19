/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

package tools.aqua.jkonstraints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import tools.aqua.konstraints.smt.BoolSort;
import tools.aqua.konstraints.smt.Expression;

/** Uses the API from Java the way a DSE driver does: SMT-LIB in, push/pop, models out. */
class TraceSolvingTest {

  @BeforeAll
  static void requireZ3() {
    boolean available;
    try {
      available = new ProcessBuilder("z3", "-version").start().waitFor() == 0;
    } catch (IOException | InterruptedException e) {
      available = false;
    }
    assumeTrue(available, "z3 is not on the PATH");
  }

  private static ConstraintSolver z3() {
    Properties config = new Properties();
    config.setProperty("z3.timeout", "5000");
    return SolverFactory.createSolver("z3", config);
  }

  @Test
  void solvesStringDecisionAndReturnsSeedValues() {
    SmtContext smt = new SmtContext();
    smt.declare("(declare-fun __string_0 () String)");
    smt.declare("(declare-fun __int_0 () (_ BitVec 32))");
    // resending a declaration, as DSE does, is harmless
    smt.declare("(declare-fun __string_0 () String)");

    List<Expression<BoolSort>> decision =
        smt.load("(assert (= __string_0 \"whoopsy\"))");
    Expression<BoolSort> bound = smt.parseFormula("(bvslt __int_0 #xfffffffb)");

    try (SolverContext ctx = z3().createContext()) {
      ctx.add(decision);
      ctx.add(bound);
      Valuation model = new Valuation();
      assertEquals(Result.SAT, ctx.solve(model));
      assertEquals("whoopsy", model.getValue("__string_0"));
      assertTrue((Integer) model.getValue("__int_0") < -5);
    }
  }

  @Test
  void exploresBothBranchesIncrementally() {
    SmtContext smt = new SmtContext();
    smt.declare("(declare-fun __int_0 () (_ BitVec 32))");
    Expression<BoolSort> taken = smt.parseFormula("(bvslt __int_0 #x0000000a)");
    // the divisor check as SPouT prints it
    Expression<BoolSort> nonZero = smt.parseFormula("(not (= __int_0 #x00000000))");

    try (SolverContext ctx = z3().createContext()) {
      ctx.add(nonZero);
      List<Result> results = new ArrayList<>();
      for (Expression<BoolSort> branch : List.of(taken, ExpressionUtil.not(taken))) {
        ctx.push();
        ctx.add(branch);
        Valuation model = new Valuation();
        results.add(ctx.solve(model));
        int value = (Integer) model.getValue("__int_0");
        assertTrue(value != 0);
        assertEquals(branch == taken, value < 10);
        ctx.pop();
      }
      assertEquals(List.of(Result.SAT, Result.SAT), results);

      ctx.push();
      ctx.add(smt.parseFormula("(= __int_0 #x00000000)"));
      assertEquals(Result.UNSAT, ctx.isSatisfiable());
      ctx.pop();
    }
  }

  @Test
  void handlesHeapFunctionsAndFloatingPoint() {
    SmtContext smt = new SmtContext();
    smt.declare(
        "(declare-fun __object_0 () Int)"
            + "(declare-fun fld.BoxPair.a (Int) Int)"
            + "(declare-fun fld.Box.x (Int) (_ BitVec 32))"
            + "(declare-fun obj.cls (Int) String)"
            + "(declare-fun __double_0 () (_ FloatingPoint 11 53))");

    List<Expression<BoolSort>> path =
        smt.load(
            "(assert (not (bvsle (fld.Box.x (fld.BoxPair.a __object_0)) #x00000000)))"
                + "(assert (= (obj.cls (fld.BoxPair.a __object_0)) \"LBox;\"))"
                + "(assert (fp.lt (fp.add RNE __double_0 __double_0)"
                + " ((_ to_fp 11 53) RNE 0.5)))"
                + "(assert (fp.isNegative __double_0))");

    assertEquals(
        java.util.Set.of("__object_0", "__double_0"),
        ExpressionUtil.freeVariables(ExpressionUtil.and(path)).stream()
            .map(Variable::getName)
            .collect(java.util.stream.Collectors.toSet()));

    try (SolverContext ctx = z3().createContext()) {
      ctx.add(path);
      Valuation model = new Valuation();
      assertEquals(Result.SAT, ctx.solve(model));
      assertTrue(model.getValue("__object_0") instanceof java.math.BigInteger);
      double d = (Double) model.getValue("__double_0");
      assertTrue(d < 0 || Double.valueOf(d).equals(-0.0), "got " + d);
      assertFalse(model.containsValueFor("fld.Box.x"));
    }
  }

  @Test
  void reportsTimeouts() {
    Properties config = new Properties();
    config.setProperty("z3.timeout", "1");
    SmtContext smt = new SmtContext();
    smt.declare("(declare-fun x () Int)(declare-fun y () Int)(declare-fun z () Int)");
    // a cubic Diophantine equation that z3 cannot decide within a millisecond
    Expression<BoolSort> hard =
        smt.parseFormula("(= (+ (* x x x) (* y y y) (* z z z)) 33)");
    Result result = SolverFactory.createSolver("z3", config).isSatisfiable(hard);
    assertTrue(result == Result.TIMEOUT || result == Result.DONT_KNOW, result.toString());
  }
}
