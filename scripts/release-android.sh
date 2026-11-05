#!/usr/bin/env bash
# Build the signed release APK and publish it into site/downloads/ (the site serves it; the app updates from it).
#
#   scripts/release-android.sh [-n "release notes"] [--skip-tests]
#
# Version comes from gradle.properties (liuying.versionName / liuying.versionCode): bump those first.
# Signing key: ./keystore.properties or LIUYING_KEYSTORE* env vars (see app/build.gradle.kts).
# Afterwards: review the diff and commit. Publishing is a separate, deliberate step.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

NOTES="${RELEASE_NOTES:-}"
TESTS=":app:testDebugUnitTest"
while [ $# -gt 0 ]; do
  case "$1" in
    -n|--notes) NOTES="$2"; shift 2 ;;
    --skip-tests) TESTS=""; shift ;;
    *) echo "unknown argument: $1" >&2; exit 2 ;;
  esac
done

prop() { grep -E "^$1=" gradle.properties | head -1 | cut -d= -f2- | tr -d '[:space:]'; }
VERSION_NAME="$(prop liuying.versionName)"
VERSION_CODE="$(prop liuying.versionCode)"
[ -n "$VERSION_NAME" ] && [ -n "$VERSION_CODE" ] || { echo "versionName/versionCode missing in gradle.properties" >&2; exit 1; }
BASE_URL="${LIUYING_SITE_URL:-https://www.liuyingapp.com}"

# Refuse to publish an APK that would not be upgradable: the real key must be present.
KEYPROPS="${LIUYING_KEYSTORE_PROPERTIES:-$ROOT/keystore.properties}"
if [ -z "${LIUYING_KEYSTORE:-}" ] && [ ! -f "$KEYPROPS" ]; then
  echo "No release keystore found ($KEYPROPS). Refusing to publish a debug-signed APK." >&2
  exit 1
fi

echo "Building Liuying $VERSION_NAME ($VERSION_CODE)..."
if command -v flock >/dev/null; then GRADLE=(flock /tmp/gradle.lock ./gradlew); else GRADLE=(./gradlew); fi
"${GRADLE[@]}" :app:assembleRelease $TESTS --no-daemon --console=plain

SRC="app/build/outputs/apk/release/app-release.apk"
[ -f "$SRC" ] || { echo "Build produced no $SRC" >&2; exit 1; }

# Verify the signature when apksigner is around.
APKSIGNER="$(command -v apksigner || true)"
if [ -z "$APKSIGNER" ]; then
  SDK="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
  [ -z "$SDK" ] && [ -f local.properties ] && SDK="$(grep -E '^sdk.dir=' local.properties | cut -d= -f2-)"
  [ -n "$SDK" ] && APKSIGNER="$(ls -d "$SDK"/build-tools/*/apksigner 2>/dev/null | sort -V | tail -1 || true)"
fi
if [ -n "$APKSIGNER" ]; then
  "$APKSIGNER" verify --print-certs "$SRC" | grep -E "Signer #1 certificate (DN|SHA-256)" || true
  "$APKSIGNER" verify "$SRC"
else
  echo "(apksigner not found; skipping signature check)"
fi

DEST_DIR="site/downloads"
APK_NAME="Liuying-${VERSION_NAME}.apk"
mkdir -p "$DEST_DIR"
find "$DEST_DIR" -maxdepth 1 -name '*.apk' ! -name "$APK_NAME" -delete
cp "$SRC" "$DEST_DIR/$APK_NAME"

SHA="$(sha256sum "$DEST_DIR/$APK_NAME" | cut -d' ' -f1)"
SIZE="$(stat -c %s "$DEST_DIR/$APK_NAME")"

VERSION_NAME="$VERSION_NAME" VERSION_CODE="$VERSION_CODE" APK_URL="$BASE_URL/downloads/$APK_NAME" \
SHA="$SHA" SIZE="$SIZE" NOTES="$NOTES" OUT="$DEST_DIR/latest.json" node -e '
const e = process.env;
const m = {
  versionName: e.VERSION_NAME, versionCode: Number(e.VERSION_CODE), apkUrl: e.APK_URL,
  sha256: e.SHA, sizeBytes: Number(e.SIZE), releasedAt: new Date().toISOString().replace(/\.\d+Z$/, "Z"),
  notes: e.NOTES,
};
require("fs").writeFileSync(e.OUT, JSON.stringify(m, null, 2) + "\n");
'

# Keep the no-JS download button on /download pointing at the current file.
sed -i -E "s#href=\"/downloads/Liuying-[^\"]*\\.apk\"#href=\"/downloads/$APK_NAME\"#; s#(<dd id=\"dl-version\">)[^<]*#\\1$VERSION_NAME#" site/download.html

echo
echo "Published $DEST_DIR/$APK_NAME ($SIZE bytes)"
echo "sha256 $SHA"
cat "$DEST_DIR/latest.json"
echo "Next: git add -A && git commit && git push"
