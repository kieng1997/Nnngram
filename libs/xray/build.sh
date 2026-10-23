#!/usr/bin/env bash
# Builds libs/xray/build/xraybridge.aar for Android arm64.
#
# Manual/CI entry point. Everything is pinned: the Xray module version lives in
# go.mod, the gomobile version below. Gradle's :TMessagesProj:buildXrayBridge
# task runs the same `go run` invocation directly, so no shell is needed there.
set -euo pipefail

GOMOBILE_VERSION="v0.0.0-20261011034528-197770444867"
ANDROID_API="${ANDROID_API:-27}"
ABI="${ABI:-arm64}"

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$HERE"

: "${ANDROID_HOME:?ANDROID_HOME must point at the Android SDK}"
if [ -z "${ANDROID_NDK_HOME:-}" ]; then
  echo "ANDROID_NDK_HOME must point at the NDK used by the app build" >&2
  exit 1
fi

mkdir -p build
# `go run` keeps the toolchain version pinned to the module graph without
# installing anything globally; gomobile locates the NDK from the environment.
go run "golang.org/x/mobile/cmd/gomobile@$GOMOBILE_VERSION" bind \
  -target "android/$ABI" \
  -androidapi "$ANDROID_API" \
  -trimpath \
  -o build/xraybridge.aar \
  -ldflags="-checklinkname=0 -s -w -buildid= -extldflags=-Wl,-z,max-page-size=16384" \
  .

echo "built $HERE/build/xraybridge.aar"
