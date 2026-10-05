#!/usr/bin/env bash
# Quality gate: fails when tests were SKIPPED (or none ran) after `./mvnw verify`.
# A skipped integration test is a failed control: once, Docker became unreachable to Testcontainers and
# the database tests were silently skipped while the build stayed green.
#   Usage: scripts/check-test-reports.sh [minimum-total-tests]
set -euo pipefail
cd "$(dirname "$0")/.."
MIN="${1:-1}"
reports=$(find . -path '*/target/surefire-reports/TEST-*.xml' -not -path './node_modules/*')
[ -n "$reports" ] || { echo "FAIL: no Surefire reports found (did the tests run?)"; exit 1; }
total=0; skipped=0; failed=0
for r in $reports; do
  line=$(grep -m1 -o '<testsuite [^>]*>' "$r")
  t=$(echo "$line" | grep -o ' tests="[0-9]*"' | grep -o '[0-9]*'); total=$((total + t))
  s=$(echo "$line" | grep -o ' skipped="[0-9]*"' | grep -o '[0-9]*'); skipped=$((skipped + s))
  f=$(echo "$line" | grep -o ' failures="[0-9]*"' | grep -o '[0-9]*'); e=$(echo "$line" | grep -o ' errors="[0-9]*"' | grep -o '[0-9]*')
  failed=$((failed + f + e))
  [ "$s" -gt 0 ] && echo "skipped: $s in $(basename "$r" .xml | sed 's/^TEST-//')"
done
echo "tests: $total, failed: $failed, skipped: $skipped (minimum required: $MIN)"
[ "$failed" -eq 0 ]  || { echo "FAIL: test failures"; exit 1; }
[ "$skipped" -eq 0 ] || { echo "FAIL: skipped tests are not allowed (is Docker available for Testcontainers?)"; exit 1; }
[ "$total" -ge "$MIN" ] || { echo "FAIL: fewer tests ran than expected (test count dropped?)"; exit 1; }
echo "PASS: all tests ran"
