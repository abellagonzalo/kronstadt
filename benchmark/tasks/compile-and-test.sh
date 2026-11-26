#!/usr/bin/env bash
# Shared by the edit and refactor tasks' checks, run in the run worktree:
#   compile-and-test.sh <hidden test> --all-tests     compile and run every module's tests, the hidden one included
#   compile-and-test.sh <hidden test> [<test class>]   compile every module's main and test sources, and run the
#                                                      hidden test plus the test classes given
# The hidden test is copied into the kotlinpoet module only after the session, so the agent never sees it.
set -euo pipefail
hidden_test=$1
shift
cp "$hidden_test" kotlinpoet/src/jvmTest/kotlin/com/squareup/kotlinpoet/BenchmarkTaskTest.kt
gradle=(./gradlew --quiet --console=plain)
if [[ "${1:-}" == --all-tests ]]; then
  exec "${gradle[@]}" :kotlinpoet:jvmTest :interop:kotlin-metadata:test :interop:ksp:test :interop:ksp:test-processor:test
fi
filters=(--tests com.squareup.kotlinpoet.BenchmarkTaskTest)
for test_class in "$@"; do filters+=(--tests "$test_class"); done
exec "${gradle[@]}" \
  :interop:kotlin-metadata:compileTestKotlin \
  :interop:ksp:compileTestKotlin \
  :interop:ksp:test-processor:compileTestKotlin \
  :kotlinpoet:jvmTest "${filters[@]}"
