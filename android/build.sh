#!/bin/sh
# Agy Switch Android 端手动工具链构建脚本（不用 Gradle）
# 用法：在 android/ 目录下执行 ./build.sh
# 产物：android/dist/agy-switch-android.apk（muse 测试签名）
set -e

cd "$(dirname "$0")"

# 工具链环境：ANDROID_HOME / JAVA_HOME / build-tools 35.0.0 进 PATH
. "$HOME/workspace/android-tools/env.sh"

ANDROID_JAR="$ANDROID_HOME/platforms/android-35/android.jar"
BUILD_TOOLS="$ANDROID_HOME/build-tools/35.0.0"
KEYSTORE="$HOME/workspace/android-tools/muse-test.keystore"
KEY_ALIAS="muse"
KEY_PASS="muse1234"

PKG_PATH="io/zealler/agyswitch"
SRC_DIR="app/src/main/java"
MANIFEST="app/src/main/AndroidManifest.xml"

BUILD_DIR="build"
DIST_DIR="dist"
rm -rf "$BUILD_DIR"
mkdir -p "$BUILD_DIR/classes" "$BUILD_DIR/dex" "$DIST_DIR"

echo "== 1/6 aapt2 link（纯代码 UI，无 res，只打包 Manifest）=="
# 无资源文件时不需要 compile 步骤，link 直接生成只含 AndroidManifest.xml 的基础 APK；
# 代码不引用 R 类，所以不需要生成 R.java。
"$BUILD_TOOLS/aapt2" link \
    -o "$BUILD_DIR/base.apk" \
    --manifest "$MANIFEST" \
    -I "$ANDROID_JAR" \
    --min-sdk-version 26 \
    --target-sdk-version 35 \
    --version-code 2 \
    --version-name "1.1"

echo "== 2/6 javac =="
find "$SRC_DIR" -name "*.java" > "$BUILD_DIR/sources.txt"
javac -encoding UTF-8 \
    -cp "$ANDROID_JAR" \
    -d "$BUILD_DIR/classes" \
    @"$BUILD_DIR/sources.txt"

echo "== 3/6 d8 =="
# 先打成 jar 再喂给 d8，避免逐个 class 文件传参
(cd "$BUILD_DIR/classes" && jar cf "../classes.jar" .)
"$BUILD_TOOLS/d8" --release \
    --lib "$ANDROID_JAR" \
    --output "$BUILD_DIR/dex" \
    "$BUILD_DIR/classes.jar"

echo "== 4/6 组装 APK（基础 APK + classes.dex）=="
cp "$BUILD_DIR/base.apk" "$BUILD_DIR/unsigned.apk"
(cd "$BUILD_DIR/dex" && zip -q -X "../unsigned.apk" classes.dex)

echo "== 5/6 zipalign =="
"$BUILD_TOOLS/zipalign" -f -p 4 "$BUILD_DIR/unsigned.apk" "$BUILD_DIR/aligned.apk"

echo "== 6/6 apksigner 签名 =="
"$BUILD_TOOLS/apksigner" sign \
    --ks "$KEYSTORE" \
    --ks-key-alias "$KEY_ALIAS" \
    --ks-pass "pass:$KEY_PASS" \
    --key-pass "pass:$KEY_PASS" \
    --out "$DIST_DIR/agy-switch-android.apk" \
    "$BUILD_DIR/aligned.apk"

"$BUILD_TOOLS/apksigner" verify --print-certs "$DIST_DIR/agy-switch-android.apk" | head -5
echo "构建完成：$DIST_DIR/agy-switch-android.apk"
