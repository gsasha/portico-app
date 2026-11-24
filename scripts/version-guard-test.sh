#!/usr/bin/env bash
# CI-free guard: fails if porticoVersionName is not MAJOR.MINOR.PATCH-alpha, or its derived
# versionCode does not exceed the previous release's (the highest in scripts/release-history).
# Offline, no Gitea, no build. Run: scripts/version-guard-test.sh   (release.sh runs it too)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
# shellcheck source=lib-version.sh
. "$HERE/lib-version.sh"

fail=0
ok()  { echo "ok   $*"; }
bad() { echo "FAIL $*"; fail=1; }
expect_code() {
  local got; got="$(version_code "$1" || echo none)"
  if [[ "$got" == "$2" ]]; then ok "version_code $1 = $2"; else bad "version_code $1: got $got, want $2"; fi
}
expect_valid()   { if valid_release_name "$1"; then ok "valid   '$1'"; else bad "'$1' should be a valid release name"; fi; }
expect_invalid() { if valid_release_name "$1"; then bad "'$1' should be rejected"; else ok "invalid '$1'"; fi; }

# The derivation (docs/development/versioning-and-releases.md / app/build.gradle.kts examples and the known releases).
expect_code 0.1.0-alpha 10010; expect_code 0.1.0-alpha.2 10012; expect_code 0.1.0-rc 10050
expect_code 0.1.0 10090; expect_code 0.1.1-alpha 10110
expect_code 0.4.3-alpha 40310; expect_code 0.5.1-alpha 50110; expect_code 0.5.2-alpha 50210; expect_code 0.5.3-alpha 50310; expect_code 0.5.4-alpha 50410; expect_code 0.5.5-alpha 50510; expect_code 0.5.6-alpha 50610; expect_code 0.5.7-alpha 50710; expect_code 0.5.8-alpha 50810; expect_code 0.5.9-alpha 50910; expect_code 0.5.10-alpha 51010; expect_code 0.5.11-alpha 51110; expect_code 0.5.12-alpha 51210; expect_code 0.6.0-alpha 60010; expect_code 0.6.1-alpha 60110; expect_code 0.6.2-alpha 60210; expect_code 0.6.3-alpha 60310; expect_code 0.6.4-alpha 60410; expect_code 0.6.5-alpha 60510; expect_code 0.6.6-alpha 60610; expect_code 0.6.7-alpha 60710; expect_code 0.6.8-alpha 60810; expect_code 0.6.9-alpha 60910; expect_code 0.6.10-alpha 61010; expect_code 0.6.11-alpha 61110; expect_code 0.6.12-alpha 61210; expect_code 0.6.13-alpha 61310; expect_code 0.6.14-alpha 61410; expect_code 0.6.15-alpha 61510; expect_code 0.6.16-alpha 61610; expect_code 0.6.17-alpha 61710
# The accepted and refused shapes of a release name.
expect_valid 0.5.2-alpha; expect_valid 1.0.0-alpha; expect_valid 0.10.12-alpha; expect_valid 0.5.10-alpha; expect_valid 0.5.11-alpha; expect_valid 0.5.12-alpha; expect_valid 0.6.0-alpha; expect_valid 0.6.1-alpha; expect_valid 0.6.2-alpha; expect_valid 0.6.3-alpha; expect_valid 0.6.4-alpha; expect_valid 0.6.5-alpha; expect_valid 0.6.6-alpha; expect_valid 0.6.16-alpha; expect_valid 0.6.17-alpha
for n in 0.5.2 0.5-alpha 0.5.2-beta 0.5.2-alpha.1 v0.5.2-alpha 0.5.2-ALPHA "0.5.2-alpha " 01.2.3-alpha 0.100.0-alpha ""; do
  expect_invalid "$n"
done

# The repo itself.
name="$(portico_version_name "$HERE/../app/build.gradle.kts")"
if valid_release_name "$name"; then ok "porticoVersionName '$name' is MAJOR.MINOR.PATCH-alpha"
else bad "porticoVersionName '$name' is not MAJOR.MINOR.PATCH-alpha"; fi
code="$(version_code "$name" || echo 0)"
prev=0; prev_name=""
while IFS= read -r line; do
  line="${line%%#*}"; line="${line//[[:space:]]/}"
  [[ -z "$line" ]] && continue
  if c="$(version_code "$line")"; then
    if (( c > prev )); then prev=$c; prev_name=$line; fi
  else
    bad "release-history: '$line' is not a version"
  fi
done < "$HERE/release-history"
if (( code > prev )) || { (( code == prev )) && [[ "$name" == "$prev_name" ]]; }; then
  ok "versionCode $code (from $name) is above the previous release $prev_name ($prev), or is it"
else
  bad "versionCode $code (from $name) does not exceed the previous release $prev_name ($prev)"
fi
exit "$fail"
