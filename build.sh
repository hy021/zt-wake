#!/bin/sh
# 用法：./build.sh [versionCode] [versionName]
set -e
JAVA_HOME=${JAVA_HOME:?需要 JDK 17}
BT=${BUILD_TOOLS:?需要指向 build-tools 目录}
AJ=${ANDROID_JAR:?需要 android.jar}
VC=${1:-1}; VN=${2:-1.0}

mkdir -p build/classes build/gen
"$BT/aapt2" compile --dir res -o build/res.zip
"$BT/aapt2" link -I "$AJ" --manifest AndroidManifest.xml --auto-add-overlay \
    --min-sdk-version 21 --target-sdk-version 28 --java build/gen -o build/base.apk build/res.zip
javac -source 8 -target 8 -encoding UTF-8 -cp "$AJ" -d build/classes \
    build/gen/com/zt/wol/R.java src/com/zt/wol/MainActivity.java src/com/zt/wol/NetHelper.java
"$BT/d8" --lib "$AJ" --min-api 21 --output build/ build/classes/com/zt/wol/*.class
echo "base.apk 已生成，请自行塞入 classes.dex 与 lib/<abi>/libnbl.so 后用 apksigner 签名 (v$VN / code $VC)"
