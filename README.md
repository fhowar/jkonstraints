# jKonstraints

jKonstraints is the successor of [jConstraints](https://github.com/tudo-aqua/jconstraints) for
tools that send SMT-LIB to a solver: concolic and symbolic executors like SPouT and the DSE driver.
It is built on [konstraints](https://github.com/tudo-aqua/konstraints), which provides the term
representation, the SMT-LIB parser and printer, and the solver connection.

jKonstraints adds what such clients need on top of konstraints:

- **`SmtContext`**: incremental parsing of SMT-LIB text into konstraints expressions. Declarations
  persist across calls; re-declarations with the same sort are ignored. Input must be standard
  SMT-LIB (SPouT emits standard SMT-LIB 2.7).
- **`SolverFactory`, `ConstraintSolver`, `SolverContext`**: the familiar jConstraints solving API
  (push, pop, add, solve, `Result`), implemented on a konstraints `SolverSession`, i.e., a solver
  process that is kept alive. Symbols are declared to the solver automatically.
- **`Valuation`, `Values`**: models as JVM values (bit-vectors as `byte`/`short`/`int`/`long`,
  floating point as `float`/`double` including NaN and signed zeros, strings with escapes decoded),
  read with `get-value`.
- **`ExpressionUtil`**: `and`, `or`, `not`, free variables, rewriting, and printing to SMT-LIB.

There are no Java-typed expressions and no custom text format: expressions are konstraints terms,
and SMT-LIB is the only exchange format.

## Example (Java)

```java
SmtContext smt = new SmtContext();
smt.declare("(declare-fun __int_0 () (_ BitVec 32))");
Expression<BoolSort> decision = smt.parseFormula("(not (= __int_0 #x00000000))");

try (SolverContext ctx = SolverFactory.createSolver("z3").createContext()) {
  ctx.add(decision);
  ctx.push();
  ctx.add(smt.parseFormula("(bvslt __int_0 #x0000000a)"));
  Valuation model = new Valuation();
  if (ctx.solve(model) == Result.SAT) {
    int seed = (Integer) model.getValue("__int_0");
  }
  ctx.pop();
}
```

## Solvers

`z3` and `cvc5` run as external processes and must be on the `PATH`. Configuration properties
(`<name>` is the solver name):

| Property         | Meaning                                       |
| ---------------- | --------------------------------------------- |
| `<name>.timeout` | timeout per check in milliseconds             |
| `<name>.command` | command line starting the solver interactively |

Further solvers can be registered through `java.util.ServiceLoader` by implementing
`SolverProvider`.

## Building

jKonstraints needs konstraints APIs that are not released yet (`SolverSession`,
`SMTScriptParser.parseInto`/`parseTerm`). By default the build includes a konstraints checkout at
`../../konstraints` as a composite build; set the Gradle property `konstraints.dir` to use a
different location, or to an empty value to use a published `konstraints-core`.

```sh
./gradlew test   # solver tests are skipped if z3 is not on the PATH
```

## Status and next steps

This is a minimal viable product covering what the DSE driver uses from jConstraints. Known gaps:

- constant arrays `((as const (Array ...)) v)` emitted by SPouT's heap model are not yet parsed by
  konstraints
- `str.lower`/`str.upper` are not SMT-LIB; jConstraints encoded them for Z3 with `seq.map`
- not yet ported: unsat cores, the sequential portfolio (cvc5 then Z3 with model validation),
  range-aware bit-vector lifting and Java string bounds as rewrite passes, query logging, bounded
  solving
