/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * Copyright 2026 The jKonstraints Authors
 */

plugins {
  `java-library`
  kotlin("jvm") version "2.4.0"
}

group = "tools.aqua"

version = "0.1.0-SNAPSHOT"

repositories {
  mavenLocal()
  mavenCentral()
}

kotlin { jvmToolchain(11) }

dependencies {
  api("tools.aqua:konstraints-core:${property("konstraints.version")}")

  testImplementation(platform("org.junit:junit-bom:5.13.4"))
  testImplementation("org.junit.jupiter:junit-jupiter")
  testImplementation("org.junit.jupiter:junit-jupiter-params")
  testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
  useJUnitPlatform()
  testLogging { events("passed", "skipped", "failed") }
}
