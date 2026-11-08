#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
SDK_DIR="${ANDROID_SDK_TOOLS_DIR:-/tmp/android-sdk}"
BUILD_DIR="$ROOT_DIR/build/apk-build"
OUT_DIR="$ROOT_DIR/releases"
APK_NAME="WifiTalkie-v1.0.0.apk"

mkdir -p "$SDK_DIR" "$BUILD_DIR" "$OUT_DIR"

# If full Android SDK + Gradle are online and requested, run ./gradlew assembleDebug
if [ "${USE_GRADLE_DEBUG:-0}" = "1" ] && [ -x "$ROOT_DIR/gradlew" ]; then
  echo "==> Building standard aligned & debug-signed APK via ./gradlew assembleDebug..."
  "$ROOT_DIR/gradlew" assembleDebug --no-daemon
  GRADLE_APK="$ROOT_DIR/app/build/outputs/apk/debug/app-debug.apk"
  cp -f "$GRADLE_APK" "$OUT_DIR/$APK_NAME"
  cp -f "$GRADLE_APK" "$ROOT_DIR/WifiTalkie.apk"
  ls -lh "$OUT_DIR/$APK_NAME" "$ROOT_DIR/WifiTalkie.apk"
  exit 0
fi

echo "==> Checking Android SDK build tools in $SDK_DIR..."
if [ ! -x "$SDK_DIR/aapt2" ]; then
  curl -fsSL "https://github.com/kiliaosi/aaptjs3/raw/master/bin/x64/linux/aapt2" -o "$SDK_DIR/aapt2"
  chmod +x "$SDK_DIR/aapt2"
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

echo "==> [2/6] Linking resources and AndroidManifest.xml with aapt2 (-0 arsc uncompressed)..."
"$SDK_DIR/aapt2" link \
  -I "$SDK_DIR/android.jar" \
  --manifest "$ROOT_DIR/app/src/main/AndroidManifest.xml" \
  --java "$BUILD_DIR/gen" \
  --min-sdk-version 26 \
  --target-sdk-version 34 \
  --version-code 1 \
  --version-name "1.0.0" \
  -0 arsc \
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
  --debug \
  --min-api 26 \
  --lib "$SDK_DIR/android.jar" \
  --output "$BUILD_DIR/dex" \
  @"$BUILD_DIR/classes.txt"

echo "==> [5/6] Packaging classes.dex and 4-byte aligning uncompressed resources.arsc (Android 11+ R+ requirement)..."
python3 - "$BUILD_DIR/unaligned.apk" "$BUILD_DIR/dex/classes.dex" "$BUILD_DIR/aligned.apk" <<'PY'
import sys, zipfile

in_apk, dex_path, out_apk = sys.argv[1], sys.argv[2], sys.argv[3]

entries = []
with zipfile.ZipFile(in_apk, "r") as zin:
    for info in zin.infolist():
        data = zin.read(info.filename)
        entries.append((info.filename, data))

with open(dex_path, "rb") as fd:
    entries.append(("classes.dex", fd.read()))

with zipfile.ZipFile(out_apk, "w") as zout:
    for filename, data in entries:
        zi = zipfile.ZipInfo(filename, date_time=(2026, 1, 1, 0, 0, 0))
        zi.create_system = 3
        zi.external_attr = 0o644 << 16
        # Android 11+ (API 30+) requires resources.arsc to be stored uncompressed
        # and aligned on a 4-byte boundary.
        if filename == "resources.arsc" or filename.endswith(".so"):
            zi.compress_type = zipfile.ZIP_STORED
            current_offset = zout.fp.tell()
            name_len = len(filename.encode("utf-8"))
            # Local file header is 30 bytes + filename length + extra field length
            base_data_offset = current_offset + 30 + name_len
            pad_len = (4 - (base_data_offset % 4)) % 4
            zi.extra = b"\x00" * pad_len
        else:
            zi.compress_type = zipfile.ZIP_DEFLATED
            zi.extra = b""
        zout.writestr(zi, data)
PY

echo "==> [6/6] Signing aligned APK with debug keystore (v1 + v2 + v3 signatures)..."
KEYSTORE="$SDK_DIR/debug.keystore"
if [ ! -f "$KEYSTORE" ]; then
  keytool -genkeypair \
    -keystore "$KEYSTORE" \
    -alias androiddebugkey \
    -keyalg RSA \
    -keysize 2048 \
    -validity 10000 \
    -storepass android \
    -keypass android \
    -dname "CN=Android Debug,O=Android,C=US"
fi

java -jar "$SDK_DIR/apksigner.jar" sign \
  --v1-signing-enabled true \
  --v2-signing-enabled true \
  --v3-signing-enabled true \
  --ks "$KEYSTORE" \
  --ks-key-alias androiddebugkey \
  --ks-pass pass:android \
  --key-pass pass:android \
  --out "$OUT_DIR/$APK_NAME" \
  "$BUILD_DIR/aligned.apk"

rm -f "$OUT_DIR/$APK_NAME.idsig"
cp -f "$OUT_DIR/$APK_NAME" "$ROOT_DIR/WifiTalkie.apk"

echo "==> Verifying Android 11+ (API 30+) resources.arsc uncompressed & 4-byte alignment..."
python3 - "$OUT_DIR/$APK_NAME" <<'PY'
import sys, zipfile

apk_path = sys.argv[1]
with zipfile.ZipFile(apk_path, "r") as z, open(apk_path, "rb") as raw:
    found_arsc = False
    for info in z.infolist():
        raw.seek(info.header_offset + 26)
        name_len = int.from_bytes(raw.read(2), "little")
        extra_len = int.from_bytes(raw.read(2), "little")
        data_offset = info.header_offset + 30 + name_len + extra_len
        if info.filename == "resources.arsc":
            found_arsc = True
            print(f"resources.arsc: compress_type={info.compress_type} (0=STORED), data_offset={data_offset}, mod 4 = {data_offset % 4}")
            if info.compress_type != zipfile.ZIP_STORED:
                raise SystemExit("ERROR: resources.arsc is compressed!")
            if data_offset % 4 != 0:
                raise SystemExit(f"ERROR: resources.arsc is not 4-byte aligned (offset {data_offset})!")
    if not found_arsc:
        raise SystemExit("ERROR: resources.arsc missing from APK!")
print("OK: resources.arsc is uncompressed (ZIP_STORED) and aligned on a 4-byte boundary!")
PY

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
echo "==> SUCCESS: Built aligned & debug-signed APK at $OUT_DIR/$APK_NAME and $ROOT_DIR/WifiTalkie.apk"
