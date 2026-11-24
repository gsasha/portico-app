# Sourced by release.sh and version-guard-test.sh. Mirrors the derivation in app/build.gradle.kts
# (versionCodeOf); release.sh cross-checks it against the built APK, so a drift is caught there.

# portico_version_name <build.gradle.kts>: print the porticoVersionName literal.
portico_version_name() {
  sed -n 's/^val porticoVersionName = "\(.*\)"[[:space:]]*$/\1/p' "$1" | head -n 1
}

# valid_release_name <name>: the form a release may have (alpha only for now).
valid_release_name() {
  [[ "$1" =~ ^(0|[1-9][0-9]*)\.([0-9]|[1-9][0-9])\.([0-9]|[1-9][0-9])-alpha$ ]]
}

# version_code <name>: print versionCode for MAJOR.MINOR.PATCH[-alpha|beta|rc[.N]]; return 1 if not that form.
version_code() {
  local re='^([0-9]+)\.([0-9]{1,2})\.([0-9]{1,2})(-(alpha|beta|rc)(\.([0-9]))?)?$'
  [[ "$1" =~ $re ]] || return 1
  local rank=90
  case "${BASH_REMATCH[5]}" in alpha) rank=10 ;; beta) rank=30 ;; rc) rank=50 ;; esac
  echo $(( 10#${BASH_REMATCH[1]} * 1000000 + 10#${BASH_REMATCH[2]} * 10000 + 10#${BASH_REMATCH[3]} * 100 + rank + 10#${BASH_REMATCH[7]:-0} ))
}
