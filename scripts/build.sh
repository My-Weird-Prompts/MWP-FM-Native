#!/usr/bin/env bash
# Build the signed release APK, verify package/version/signer, stage dist/mwp-fm-player-<version>.apk.
# Usage: scripts/build.sh   (bump versionCode/versionName in app/build.gradle.kts first)
set -euo pipefail
cd "$(dirname "$0")/.."
EXPECTED_CERT="73bf2cd357da664b0e307c7aaae6629ca4973bbbee6f5a55b71800c3511827c6"
# Same JDK 17 bubblewrap uses (a stale JAVA_HOME in the shell profile is ignored).
export JAVA_HOME="$(python3 -c 'import json,os;print(json.load(open(os.path.expanduser("~/.bubblewrap/config.json")))["jdkPath"])')"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Development/Android/Sdk}"
[ -f local.properties ] || echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew --no-daemon -q assembleRelease
APK=app/build/outputs/apk/release/app-release.apk
BT="$ANDROID_HOME/build-tools/36.0.0"
"$BT/aapt2" dump badging "$APK" | sed -n 1p
CERT="$("$BT/apksigner" verify --print-certs "$APK" 2>/dev/null | sed -n 's/^Signer #1 certificate SHA-256 digest: //p')"
[ "$CERT" = "$EXPECTED_CERT" ] || { echo "ERROR: signer $CERT != $EXPECTED_CERT" >&2; exit 1; }
VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
mkdir -p dist && cp "$APK" "dist/mwp-fm-player-$VERSION.apk"
sha256sum "dist/mwp-fm-player-$VERSION.apk"
