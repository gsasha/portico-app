#!/usr/bin/env bash
# Build, verify and publish a Portico release. Run it from a clean checkout of main AFTER the change
# PR (which carries the one-line porticoVersionName bump) has been merged. See docs/development/release-build.md.
#
#   scripts/release.sh [--dry-run] [--notes TEXT] <versionName>      e.g. scripts/release.sh 0.5.3-alpha
#
# 1. refuse unless: tree clean, HEAD is a commit on origin/main, porticoVersionName == <versionName>,
#    the version guard passes, no Gitea release or tag v<ver> exists, and the versionCode is above
#    every existing release's;
# 2. ./gocore/bind.sh, then a signed :app:assembleRelease;
# 3. check the APK (aapt2 badging, signer SHA-256 == the v0.4.3 key, libgojni, size, sha256); ANY
#    mismatch aborts before anything is uploaded;
# 4. create the Gitea prerelease v<ver> at HEAD, upload portico-<ver>.apk, download it back, compare;
# 5. print a short summary.
# --dry-run does 1-3 only: nothing is created or uploaded anywhere, the APK is left in the scratch dir.
#
# Outside the repo, never printed: PORTICO_SIGNING_PROPS (default ~/secrets/portico/signing.properties)
# and the Gitea token (see scripts/gitea.py: PORTICO_GITEA_TOKEN / PORTICO_GITEA_ENV).
# Other knobs: PORTICO_RELEASE_OUT (scratch dir; default a fresh mktemp -d), PORTICO_GITEA_REPO.
set -euo pipefail

# The release key's certificate SHA-256 (the v0.4.3-alpha key; every later release must match, or it
# would not install over the earlier ones). A public fingerprint, not a secret.
EXPECTED_SIGNER=5f8cb715448565fd63d907a0fd4780726d8150f708b49178afbb86f0811b3451

HERE="$(cd "$(dirname "$0")" && pwd)"
ROOT="$(cd "$HERE/.." && pwd)"
# shellcheck source=lib-version.sh
. "$HERE/lib-version.sh"

die() { echo "release.sh: REFUSED/ABORTED: $*" >&2; exit 1; }
say() { echo "== $*"; }

DRY=0; NOTES=""; VER=""
while (( $# )); do
  case "$1" in
    --dry-run) DRY=1 ;;
    --notes) shift; NOTES="${1:-}" ;;
    -h|--help) sed -n '2,19p' "${BASH_SOURCE[0]}"; exit 0 ;;
    -*) die "unknown option $1" ;;
    *) [[ -z "$VER" ]] || die "one version only"; VER="$1" ;;
  esac
  shift
done
[[ -n "$VER" ]] || die "usage: scripts/release.sh [--dry-run] <versionName>"
valid_release_name "$VER" || die "'$VER' is not MAJOR.MINOR.PATCH-alpha"
TAG="v$VER"
CODE="$(version_code "$VER")"

# ---------------------------------------------------------------- 1. may this release be cut at all?
cd "$ROOT"
say "1/5 preconditions for $TAG (versionCode $CODE)$([[ $DRY == 1 ]] && echo ' [dry run]')"
[[ -z "$(git status --porcelain)" ]] || die "working tree is not clean (commit or move your changes; release from a clean checkout of main)"
git fetch -q origin main || die "cannot fetch origin/main"
git merge-base --is-ancestor HEAD origin/main || die "HEAD $(git rev-parse --short HEAD) is not a commit on origin/main (merge the change PR first, then 'git checkout main && git pull')"
HAVE="$(portico_version_name app/build.gradle.kts)"
[[ "$HAVE" == "$VER" ]] || die "app/build.gradle.kts has porticoVersionName '$HAVE', not '$VER' (is the bump merged, and is this checkout at it?)"
"$HERE/version-guard-test.sh" > /dev/null || { "$HERE/version-guard-test.sh" | grep '^FAIL' >&2; die "version guard failed"; }

set +e; EXISTS="$(python3 -I "$HERE/gitea.py" exists "$TAG")"; RC=$?; set -e
case $RC in
  0) die "Gitea already has a $EXISTS $TAG; a version is released once (bump again in a new change PR)" ;;
  1) ;;
  *) die "could not ask Gitea whether $TAG exists" ;;
esac
if [[ -n "$(git ls-remote --tags origin "refs/tags/$TAG")" ]]; then die "origin already has a git tag $TAG"; fi
MAXCODE=0; MAXTAG=none
while IFS= read -r t; do
  c="$(version_code "${t#v}" 2>/dev/null)" || continue
  if (( c > MAXCODE )); then MAXCODE=$c; MAXTAG=$t; fi
done < <(python3 -I "$HERE/gitea.py" tags)
(( CODE > MAXCODE )) || die "versionCode $CODE is not above the highest released one ($MAXTAG = $MAXCODE); Android refuses a downgrade"
echo "   ok: clean tree, HEAD $(git rev-parse --short HEAD) is on origin/main, $VER == build.gradle.kts, no $TAG yet, $CODE > $MAXCODE ($MAXTAG)"

# ---------------------------------------------------------------- 2. build
export JAVA_HOME="${JAVA_HOME:-$HOME/toolchain/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/toolchain/android-sdk}"
export ANDROID_SDK_ROOT="${ANDROID_SDK_ROOT:-$ANDROID_HOME}"
BT="$ANDROID_HOME/build-tools/34.0.0"
export PATH="$JAVA_HOME/bin:$BT:$PATH"
export PORTICO_SIGNING_PROPS="${PORTICO_SIGNING_PROPS:-$HOME/secrets/portico/signing.properties}"
[[ -r "$PORTICO_SIGNING_PROPS" ]] || die "signing properties not readable (set PORTICO_SIGNING_PROPS to the file outside the repo)"
for t in "$BT/aapt2" "$BT/apksigner" unzip sha256sum python3; do command -v "$t" > /dev/null || die "missing tool $t"; done

OUT="${PORTICO_RELEASE_OUT:-$(mktemp -d "${TMPDIR:-/tmp}/portico-release-XXXXXX")}"
mkdir -p "$OUT"
LOG="$OUT/build-$VER.log"
say "2/5 build (log: $LOG)"
rm -f app/build/outputs/apk/release/app-release.apk
{ ./gocore/bind.sh && ./gradlew --offline :app:assembleRelease; } > "$LOG" 2>&1 \
  || { tail -n 25 "$LOG" >&2; die "build failed (full log: $LOG)"; }
BUILT=app/build/outputs/apk/release/app-release.apk
[[ -f "$BUILT" ]] || die "build reported success but $BUILT is missing"
APK="$OUT/portico-$VER.apk"
cp "$BUILT" "$APK"

# ---------------------------------------------------------------- 3. checks (collect every failure, then decide)
say "3/5 checks on $APK"
BAD=0
fail() { echo "   FAIL: $*" >&2; BAD=1; }
pass() { echo "   ok:   $*"; }

BADGING="$("$BT/aapt2" dump badging "$APK")" || die "aapt2 dump badging failed"
PKG_LINE="$(sed -n 's/^package: //p' <<< "$BADGING" | head -n 1)"
[[ "$PKG_LINE" == *"name='com.meda.portico'"* ]] && pass "package com.meda.portico" || fail "package is not com.meda.portico ($PKG_LINE)"
[[ "$PKG_LINE" == *"versionName='$VER'"* ]] && pass "versionName $VER" || fail "versionName in the APK is not $VER ($PKG_LINE)"
[[ "$PKG_LINE" == *"versionCode='$CODE'"* ]] && pass "versionCode $CODE" || fail "versionCode in the APK is not the derived $CODE ($PKG_LINE)"
if grep -q '^application-debuggable' <<< "$BADGING"; then fail "APK is debuggable"; else pass "not debuggable"; fi
NATIVE="$(sed -n "s/^native-code: //p" <<< "$BADGING")"
[[ "$NATIVE" == "'arm64-v8a'" ]] && pass "native-code arm64-v8a only" || fail "native-code is '$NATIVE', want only 'arm64-v8a'"
grep -E '^(sdkVersion|targetSdkVersion):' <<< "$BADGING" | tr '\n' ' ' | sed 's/^/   info: /; s/$/\n/'

LISTING="$(unzip -l "$APK")" || die "cannot list the APK"   # in a variable: 'unzip | grep -q' dies of SIGPIPE under pipefail
grep -q 'lib/arm64-v8a/libgojni\.so$' <<< "$LISTING" && pass "libgojni.so present (tailnet client built in)" || fail "lib/arm64-v8a/libgojni.so missing: bind.sh did not feed this build"

CERTS="$("$BT/apksigner" verify --print-certs "$APK" 2>&1)" || { echo "$CERTS" | head -n 5 >&2; fail "apksigner verify failed"; CERTS=""; }
SIGNERS="$(grep -c 'Signer #[0-9]* certificate SHA-256 digest:' <<< "$CERTS" || true)"
SIGNER="$(sed -n 's/^Signer #1 certificate SHA-256 digest: *//p' <<< "$CERTS" | head -n 1 | tr 'A-F' 'a-f')"
if [[ "$SIGNERS" == 1 && "$SIGNER" == "$EXPECTED_SIGNER" ]]; then
  pass "signer SHA-256 == the v0.4.3 release key ($SIGNER)"
else
  fail "signer is '$SIGNER' ($SIGNERS signer(s)), expected $EXPECTED_SIGNER; an APK with another key cannot update installed phones"
fi

SIZE="$(stat -c %s "$APK")"
SHA="$(sha256sum "$APK" | cut -d' ' -f1)"
(( SIZE > 1000000 )) && pass "size $SIZE bytes, sha256 $SHA" || fail "size $SIZE bytes is implausibly small"

if (( BAD )); then die "checks failed; NOTHING was uploaded (APK kept for inspection: $APK)"; fi

if (( DRY )); then
  say "dry run done: $TAG would be released from $(git rev-parse --short HEAD)"
  echo "   APK:    $APK"
  echo "   size:   $SIZE bytes"
  echo "   sha256: $SHA"
  echo "   signer: $SIGNER (matches)"
  echo "   nothing was created or uploaded."
  exit 0
fi

# ---------------------------------------------------------------- 4. publish, download back, compare
COMMIT="$(git rev-parse HEAD)"
SUBJECT="$(git log -1 --format=%s)"
SIGNER_COLON="$(sed 's/../&:/g; s/:$//' <<< "$SIGNER" | tr 'a-f' 'A-F')"
BODY="$OUT/release-notes-$VER.md"
{
  [[ -n "$NOTES" ]] && printf '%s\n\n' "$NOTES"
  printf 'Portico %s (versionCode %s). Latest change: %s.\n' "$VER" "$CODE" "$SUBJECT"
  printf '%s bytes, arm64-v8a only, with the tailnet client (libgojni), sha256 %s.\n' "$SIZE" "$SHA"
  printf 'Signed with the same key as every earlier release (certificate SHA-256 %s).\n' "$SIGNER_COLON"
  printf 'Built by scripts/release.sh from main commit %s with gocore/bind.sh first.\n' "${COMMIT:0:7}"
} > "$BODY"

say "4/5 publish $TAG at ${COMMIT:0:7}"
RID="$(python3 -I "$HERE/gitea.py" create "$TAG" "$COMMIT" "Portico $VER" "$BODY")" || die "creating the release failed; nothing to clean up"
LEFT="release $TAG (id $RID) now exists WITHOUT a verified asset; fix or delete it by hand before retrying"
UP="$(python3 -I "$HERE/gitea.py" upload "$RID" "$APK" "portico-$VER.apk")" || die "upload failed; $LEFT"
[[ "$UP" == "portico-$VER.apk $SIZE" ]] || die "Gitea stored '$UP', expected 'portico-$VER.apk $SIZE'; $LEFT"
BACK="$OUT/back-portico-$VER.apk"
python3 -I "$HERE/gitea.py" download "$TAG" "portico-$VER.apk" "$BACK" || die "download-back failed; $LEFT"
BACK_SHA="$(sha256sum "$BACK" | cut -d' ' -f1)"; BACK_SIZE="$(stat -c %s "$BACK")"
[[ "$BACK_SHA" == "$SHA" && "$BACK_SIZE" == "$SIZE" ]] || die "downloaded copy differs (sha256 $BACK_SHA, $BACK_SIZE bytes); $LEFT"
echo "   ok: downloaded back, sha256 and size identical"

# ---------------------------------------------------------------- 5. summary
say "5/5 released $TAG"
echo "   release:  ${PORTICO_GITEA_PUBLIC_URL:-${PORTICO_GITEA_URL:-}}/${PORTICO_GITEA_REPO:-}/releases/tag/$TAG"
echo "   asset:    portico-$VER.apk, $SIZE bytes"
echo "   sha256:   $SHA"
echo "   signer:   $SIGNER (v0.4.3 key, verified)"
echo "   commit:   $COMMIT"
echo "   local:    $APK"
echo "   install:  adb install -r $APK   (updates in place; versionCode $CODE; not yet installed on any phone)"
