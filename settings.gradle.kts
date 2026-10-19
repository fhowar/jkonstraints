/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

rootProject.name = "jkonstraints"

// Build against a konstraints checkout instead of a published release while the APIs that
// jkonstraints needs (SolverSession, SMTScriptParser.parseInto/parseTerm) are unreleased.
// Set konstraints.dir to an empty value to use the konstraints-core artifact from a repository.
val konstraintsDir = providers.gradleProperty("konstraints.dir").getOrElse("../../konstraints")

if (konstraintsDir.isNotBlank()) {
  require(file(konstraintsDir).isDirectory) { "konstraints.dir=$konstraintsDir is not a directory" }
  includeBuild(konstraintsDir)
}
