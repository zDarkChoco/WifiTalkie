#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_TOOLS_DIR:-/tmp/android-sdk}"
BUILD_DIR="$ROOT_DIR/build/apk-build"
OUT_DIR="$ROOT_DIR/releases"
APK_NAME="WifiTalkie-v1.0.0.apk"

mkdir -p "$SDK_DIR" "$BUILD_DIR" "$OUT_DIR"

echo "==> Checking Android SDK build tools in $SDK_DIR..."
if [ ! -x "$SDK_DIR/aapt2" ]; then
  curl -fsSL "https://github.com/kiliaosi/aaptjs3/raw/master/bin/x64/linux/aapt2" -o "$SDK_DIR/aapt2"
  chmod +x "$SDK_DIR/aapt2"
fi
if [ ! -x "$SDK_DIR/zipalign" ]; then
  curl -fsSL "https://github.com/antipatico/balatromobile/raw/master/balatromobile/artifacts/zipalign-linux-amd64" -o "$SDK_DIR/zipalign"
  chmod +x "$SDK_DIR/zipalign"
fi
if [ ! -f "$SDK_DIR/d8.jar" ]; then
  curl -fsSL "https://github.com/HashShin/H2APK-TOOLS/releases/download/tools/d8.jar" -o "$SDK_DIR/d8.jar"
fi
if [ ! -f "$SDK_DIR/apksigner.jar" ]; then
  curl -fsSL "https://github.com/HashShin/H2APK-TOOLS/releases/download/tools/apksigner.jar" -o "$SDK_DIR/apksigner.jar"
fi
if [ ! -f "$SDK_DIR/android.jar" ]; then
  curl -fsSL "https://github.com/Sable/android-platforms/raw/master/android-34/android.jar" -o "$SDK_DIR/android.jar"
fi

rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/compiled_res" "$BUILD_DIR/gen" "$BUILD_DIR/classes" "$BUILD_DIR/dex"

echo "==> [1/6] Compiling Android resources with aapt2..."
"$SDK_DIR/aapt2" compile \
  --dir "$ROOT_DIR/app/src/main/res" \
  -o "$BUILD_DIR/compiled_res/resources.zip"

echo "==> [2/6] Linking resources and AndroidManifest.xml with aapt2..."
"$SDK_DIR/aapt2" link \
  -I "$SDK_DIR/android.jar" \
  --manifest "$ROOT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name "1.0.0" \
  -o "$BUILD_DIR/unaligned.apk" \
  "$BUILD_DIR/compiled_res/resources.zip"

echo "==> [3/6] Compiling Java sources (OpenJDK 17 -> bytecode)..."
find "$ROOT_DIR/app/src/main/java" "$BUILD_DIR/gen" -name "*.java" > "$BUILD_DIR/sources.txt"
javac \
  -encoding UTF-8 \
  --release 11 \
  -cp "$SDK_DIR/android.jar" \
  -d "$BUILD_DIR/classes" \
  @"$BUILD_DIR/sources.txt"

echo "==> [4/6] Dexing bytecode with D8..."
find "$BUILD_DIR/classes" -name "*.class" > "$BUILD_DIR/classes.txt"
java -cp "$SDK_DIR/d8.jar" com.android.tools.r8.D8 \
  --release \
  --min-api 26 \
  --lib "$SDK_DIR/android.jar" \
  --output "$BUILD_DIR/dex" \
  @"$BUILD_DIR/classes.txt"

echo "==> [5/6] Packaging classes.dex and aligning APK with zipalign..."
python3 - "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/dex/classes.dex" <<'PY'
import sys, zipfile
apk_path, dex_path = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(apk_path, "a", zipfile.ZIP_DEFLATED) as z:
    z.write(dex_path, "classes.dex")
PY

"$SDK_DIR/zipalign" -f -a 4 \
  -i "$BUILD_DIR/unaligned.apk" \
  -o "$BUILD_DIR/aligned.apk"

echo "==> [6/6] Signing APK with apksigner (v1 + v2 + v3 signatures)..."
KEYSTORE="$SDK_DIR/release-key.jks"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias wifitalkie \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass wifitalkie123 \
    -keypass wifitalkie123 \
    -dname "CN=WifiTalkie, OU=Mobile, O=zDarkChoco, L=Local, ST=Local, C=US"
fi

java -jar "$SDK_DIR/apksigner.jar" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$KEYSTORE" \
  --ks-key-alias wifitalkie \
  --ks-pass pass:wifitalkie123 \
  --key-pass pass:wifitalkie123 \
  --out "$OUT_DIR/$APK_NAME" \
  "$BUILD_DIR/aligned.apk"

cp -f "$OUT_DIR/$APK_NAME" "$ROOT_DIR/WifiTalkie.apk"

echo "==> Running automated protocol & floor-control verification tests..."
javac \
  -encoding UTF-8 \
  --release 11 \
  -cp "$SDK_DIR/android.jar:$BUILD_DIR/classes" \
  -d "$BUILD_DIR/classes" \
  "$ROOT_DIR/app/src/test/java/com/zdarkchoco/wifitalkie/WalkieProtocolTest.java"
java -cp "$BUILD_DIR/classes:$SDK_DIR/android.jar" com.zdarkchoco.wifitalkie.WalkieProtocolTest

java -jar "$SDK_DIR/apksigner.jar" verify --verbose "$OUT_DIR/$APK_NAME"
rm -rf "$ROOT_DIR/build"
ls -lh "$OUT_DIR/$APK_NAME" "$ROOT_DIR/WifiTalkie.apk"
echo "==> SUCCESS: Built signed APK at $OUT_DIR/$APK_NAME and $ROOT_DIR/WifiTalkie.apk"
