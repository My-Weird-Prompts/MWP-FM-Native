#!/usr/bin/env bash
# Release MWP FM Player (native Android) from the monorepo.
#   1. bump versionCode (+1) and versionName (v1.N, small tier) in app/build.gradle.kts and commit
#   2. scripts/release.sh "<one-line release notes>"
# Does: signed build + signer check (build.sh) → push android/ to the public mirror
# My-Weird-Prompts/MWP-FM-Native (git subtree) → GitHub release v<version> there with the APK
# → upload to R2 mwppublic (mwp-fm-player.apk + versioned copy) and verify the public bytes
# → trigger the F-Droid repo publish. Stops at the first failure.
set -euo pipefail
cd "$(dirname "$0")/.."
NOTES="${1:?usage: scripts/release.sh \"release notes\"}"
MIRROR=My-Weird-Prompts/MWP-FM-Native
ROOT="$(git rev-parse --show-toplevel)"

VERSION="$(sed -n 's/.*versionName = "\(.*\)".*/\1/p' app/build.gradle.kts)"
TAG="v$VERSION"
if gh release view "$TAG" -R "$MIRROR" >/dev/null 2>&1; then echo "ERROR: $TAG already released — bump the version" >&2; exit 1; fi
if [ -n "$(git -C "$ROOT" status --porcelain -- android)" ]; then echo "ERROR: commit android/ first" >&2; exit 1; fi

scripts/build.sh
APK="dist/mwp-fm-player-$VERSION.apk"

# Mirror: the public repo is a subtree split of android/ (history-preserving, fast-forward).
git -C "$ROOT" subtree push --prefix=android "https://github.com/$MIRROR.git" main

gh release create "$TAG" "$APK" -R "$MIRROR" --title "$TAG" --notes "$NOTES"

# R2 (bucket mwppublic, served as cdn.myweirdprompts.com). Credentials: the account-wide R2
# key in My-Weird-Prompts/.env.production (CF_R2_KEY_ID / CF_R2_ACCESS_KEY / CLOUDFLARE_ACCOUNT_ID).
eval "$(python3 - "$ROOT/.env.production" <<'PY'
import sys, shlex
want = {"CF_R2_KEY_ID": "AWS_ACCESS_KEY_ID", "CF_R2_ACCESS_KEY": "AWS_SECRET_ACCESS_KEY", "CLOUDFLARE_ACCOUNT_ID": "CF_ACCOUNT"}
for line in open(sys.argv[1]):
    k, _, v = line.strip().partition("=")
    if k in want: print(f"export {want[k]}={shlex.quote(v.strip().strip(chr(34)))}")
PY
)"
export AWS_DEFAULT_REGION=auto
SUM="$(sha256sum "$APK" | cut -d' ' -f1)"
for key in "mwp-fm-player-$VERSION.apk" mwp-fm-player.apk; do
  aws s3 cp "$APK" "s3://mwppublic/$key" --endpoint-url "https://$CF_ACCOUNT.r2.cloudflarestorage.com" \
    --content-type application/vnd.android.package-archive --only-show-errors
  GOT="$(curl -s "https://cdn.myweirdprompts.com/$key?v=$RANDOM" | sha256sum | cut -d' ' -f1)"
  [ "$GOT" = "$SUM" ] || { echo "ERROR: cdn $key sha256 $GOT != $SUM" >&2; exit 1; }
  echo "cdn.myweirdprompts.com/$key OK"
done

gh workflow run publish.yml -R My-Weird-Prompts/fdroid
echo "Released $TAG. F-Droid publish triggered (gh run list -R My-Weird-Prompts/fdroid -L 1)."
