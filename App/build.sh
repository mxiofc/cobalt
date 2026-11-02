#!/usr/bin/env bash
#
# Build Cobalt.apk from the existing Cobalt web app.
#
# The web app itself is not modified. This script only:
#   1. copies the production web build into the APK assets,
#   2. injects a small native bridge for downloads and dark mode,
#   3. compiles the native Android shell (MainActivity + splash resources),
#   4. packages, signs and verifies the APK.
#
# Requirements (already provided in this workspace):
#   - aapt2 (Linux x86_64)
#   - android.jar (API 34)
#   - d8.jar, apksigner.jar, ecj.jar
#   - a JDK (java + keytool)
#   - the production web build in ../web/build
#
# The tool paths can be overridden with environment variables.

set -euo pipefail

APP_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO_ROOT="$(cd "$APP_DIR/.." && pwd)"
WEB_BUILD="${WEB_BUILD:-$REPO_ROOT/web/build}"

TOOLS="${TOOLS:-/tmp/tinyapk-lab/tools/windows}"
AAPT2="${AAPT2:-/tmp/venv/lib/python3.11/site-packages/aapt2/bin/Linux/aapt2}"
JAVA="${JAVA:-/tmp/venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/java}"
KEYTOOL="${KEYTOOL:-/tmp/venv/lib/python3.11/site-packages/jdk4py/java-runtime/bin/keytool}"
ANDROID_JAR="${ANDROID_JAR:-$TOOLS/android.jar}"
D8_JAR="${D8_JAR:-$TOOLS/d8.jar}"
APKSIGNER_JAR="${APKSIGNER_JAR:-$TOOLS/apksigner.jar}"
ECJ_JAR="${ECJ_JAR:-$TOOLS/ecj-3.45.0.jar}"

BUILD="$APP_DIR/build"
ASSETS="$BUILD/assets"
WWW="$ASSETS/www"
CLASSES="$BUILD/classes"
DEX="$BUILD/dex"
GEN="$BUILD/gen"
RES_COMPILED="$BUILD/compiled-res.zip"
RESOURCES_APK="$BUILD/resources.apk"
UNSIGNED_APK="$BUILD/app-unsigned.apk"
KEYSTORE="$BUILD/cobalt.keystore"
FINAL_APK="$APP_DIR/Cobalt.apk"

echo "==> Cobalt APK build"
echo "    web build:  $WEB_BUILD"
echo "    tools:      $TOOLS"
echo

if [[ ! -d "$WEB_BUILD" ]]; then
    echo "Web build not found at $WEB_BUILD" >&2
    echo "Build it first (from the repository root):" >&2
    echo "  WEB_DEFAULT_API=https://api.cobalt.tools WEB_HOST=cobalt.tools pnpm --filter @imput/cobalt-web build" >&2
    exit 1
fi

for tool in "$AAPT2" "$JAVA" "$KEYTOOL" "$ANDROID_JAR" "$D8_JAR" "$APKSIGNER_JAR" "$ECJ_JAR"; do
    if [[ ! -e "$tool" ]]; then
        echo "Missing tool: $tool" >&2
        exit 1
    fi
done

chmod +x "$AAPT2" 2>/dev/null || true

echo "==> Preparing web assets"
rm -rf "$BUILD"
mkdir -p "$ASSETS" "$CLASSES" "$DEX" "$GEN"

cp -a "$WEB_BUILD" "$WWW"

# The local-processing wasm payload is ~77 MB and is disabled by default on
# Android. Removing it keeps the APK lightweight; the API does the processing.
rm -rf "$WWW/_libav"

cp "$APP_DIR/tools/cobalt-bridge.js" "$WWW/cobalt-bridge.js"
python3 "$APP_DIR/tools/inject_bridge.py" "$WWW"

echo "==> Compiling resources"
"$AAPT2" compile --dir "$APP_DIR/src/res" -o "$RES_COMPILED"
"$AAPT2" link \
    -o "$RESOURCES_APK" \
    -I "$ANDROID_JAR" \
    --manifest "$APP_DIR/src/AndroidManifest.xml" \
    -A "$ASSETS" \
    --java "$GEN" \
    --auto-add-overlay \
    "$RES_COMPILED"

echo "==> Compiling Java"
mapfile -t JAVA_FILES < <(find "$GEN" -name '*.java')
JAVA_FILES+=("$APP_DIR/src/java/com/cobalt/app/MainActivity.java")
"$JAVA" -jar "$ECJ_JAR" \
    -source 8 -target 8 -encoding UTF-8 \
    -bootclasspath "$ANDROID_JAR" \
    -classpath "$ANDROID_JAR" \
    -d "$CLASSES" \
    "${JAVA_FILES[@]}"

echo "==> Converting to DEX"
mapfile -t CLASS_FILES < <(find "$CLASSES" -name '*.class')
"$JAVA" -cp "$D8_JAR" com.android.tools.r8.R8 \
    --release \
    --dex \
    --min-api 24 \
    --no-tree-shaking \
    --lib "$ANDROID_JAR" \
    --output "$DEX" \
    "${CLASS_FILES[@]}"

echo "==> Packaging APK"
cp "$RESOURCES_APK" "$UNSIGNED_APK"
(cd "$DEX" && zip -q -u "$UNSIGNED_APK" classes.dex)

echo "==> Generating signing key"
if [[ ! -f "$KEYSTORE" ]]; then
    "$KEYTOOL" -genkeypair \
        -alias cobalt \
        -keyalg RSA \
        -keysize 2048 \
        -validity 10000 \
        -keystore "$KEYSTORE" \
        -storepass cobalt \
        -keypass cobalt \
        -dname "CN=Cobalt, OU=App, O=Cobalt, L=Jakarta, ST=West Java, C=ID"
fi

echo "==> Signing APK"
"$JAVA" -jar "$APKSIGNER_JAR" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias cobalt \
    --ks-pass pass:cobalt \
    --key-pass pass:cobalt \
    --v1-signing-enabled false \
    --v2-signing-enabled true \
    --v3-signing-enabled true \
    --min-sdk-version 24 \
    --out "$FINAL_APK" \
    "$UNSIGNED_APK"

echo "==> Verifying APK"
"$JAVA" -jar "$APKSIGNER_JAR" verify --verbose "$FINAL_APK"

echo
echo "==> Done"
ls -lh "$FINAL_APK"
echo "    package: com.cobalt.app"
echo "    version: 11.7"
echo

# Keep the workspace clean: the signed APK is the deliverable.
rm -rf "$BUILD"
