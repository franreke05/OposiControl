#!/usr/bin/env bash
# Prueba de assert-junit-executed.sh (SEC-001) con informes JUnit XML con la forma que escribe Gradle: pasada -> exit 0;
# saltada por Assume, fallida, ausente, duplicada, informe ausente o sin nombres -> FAIL.
set -uo pipefail
cd "$(dirname "$0")/.."
tmp="$(mktemp -d)"; trap 'rm -rf "$tmp"' EXIT
x="$tmp/TEST-x.xml"
cat > "$x" <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<testsuite name="com.example.HardeningTest" tests="4" skipped="1" failures="1" errors="0">
  <properties/>
  <testcase name="posixPassed[jvm]" classname="com.example.HardeningTest" time="0.01"/>
  <testcase name="posixSkipped[jvm]" classname="com.example.HardeningTest" time="0.0">
    <skipped/>
  </testcase>
  <testcase name="posixFailed" classname="com.example.HardeningTest" time="0.0">
    <failure message="expected rw-------" type="java.lang.AssertionError">trace</failure>
  </testcase>
  <testcase name="posixPassedTwice" classname="com.example.HardeningTest" time="0.01"/>
  <testcase name="posixPassedTwice" classname="com.example.HardeningTest" time="0.01"/>
</testsuite>
XML
s=scripts/assert-junit-executed.sh
bash "$s" "$x" "posixPassed[jvm]" >/dev/null || { echo "TEST FAIL: pasada deberia pasar"; exit 1; }
ko() { bash "$s" "$@" >"$tmp/out" 2>&1 && { echo "TEST FAIL: $* paso"; exit 1; }; grep -q '^FAIL:' "$tmp/out" || { echo "TEST FAIL: $* sin FAIL"; exit 1; }; }
ko "$x" "posixSkipped[jvm]"
ko "$x" "posixSkippe[jvm]"
ko "$x" posixFailed
ko "$x" posixMissing
ko "$x" posixPassedTwice
ko "$x" "posixPassed[jvm]" "posixSkipped[jvm]"
ko "$x" posixPas
ko "$tmp/none.xml" "posixPassed[jvm]"
ko "$x" posixPassed
ko "$x"
echo "assert-junit-executed.test OK"
