#!/usr/bin/env bash
# Build app/libs/gocore.aar (the tailnet client, DEV-557) from gocore/tailnet.
#
# Run it before any build that should carry the tailnet client, and always
# before the signed release build. app/libs/*.aar is git-ignored (derived,
# needs Go and the Android NDK), so a fresh checkout has none, and the app
# then builds WITHOUT the tailnet client: Setup says "not built into this
# APK" (docs/tailnet.md). Modelled on the intercom app's tsmobile/bind.sh.
#
# Toolchain (override with the variables): TOOLCHAIN=$HOME/toolchain holding
# go/, gopath/bin/{gomobile,gobind}, jdk/ and android-sdk/ with an NDK.
# Go modules come from the module cache; set GOPROXY to allow fetching.
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
TC="${TOOLCHAIN:-$HOME/toolchain}"
export GOPATH="${GOPATH:-$TC/gopath}"
export GOCACHE="${GOCACHE:-$TC/gocache}"
export GOFLAGS="${GOFLAGS:--mod=mod}"
export GOPROXY="${GOPROXY:-off}"
export GOTOOLCHAIN="${GOTOOLCHAIN:-local}"
export JAVA_HOME="${JAVA_HOME:-$TC/jdk}"
export ANDROID_HOME="${ANDROID_HOME:-$TC/android-sdk}"
export ANDROID_NDK_HOME="${ANDROID_NDK_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
export PATH="$TC/go/bin:$GOPATH/bin:$JAVA_HOME/bin:$PATH"

OUT="$HERE/../app/libs/gocore.aar"
cd "$HERE"
# gomobile does NOT create the output directory, and app/libs/ is git-ignored,
# so on a fresh checkout it fails with "open …/gocore.aar: no such file or
# directory", which reads like a missing input. (The intercom app hit this.)
mkdir -p "$(dirname "$OUT")"
# arm64 only: every phone this app targets is arm64, and each extra ABI adds
# a full Go runtime + netstack (~10+ MB). androidapi 24 is gomobile's floor
# for this NDK; the APK's own minSdk is 26.
# -javapkg puts the class at com.meda.portico.gomobile.tailnet.Tailnet.
gomobile bind -target=android/arm64 -androidapi 24 \
    -javapkg com.meda.portico.gomobile \
    -o "$OUT" ./tailnet
rm -f "$HERE/../app/libs/gocore-sources.jar"
ls -l "$OUT"
