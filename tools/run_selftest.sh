#!/usr/bin/env bash
# 纯 JVM 代理协议自测（不依赖 Android 设备）。用法：bash tools/run_selftest.sh
set -e
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
AJ="D:/ZTESOFT/AndroidDev/sdk/platforms/android-35/android.jar"
JAVAC="/d/ZTESOFT/AndroidDev/jdk17/bin/javac.exe"
JAVA="/d/ZTESOFT/AndroidDev/jdk17/bin/java.exe"
OUT="build/test-classes"
OUT_WIN=$(cygpath -w "$OUT")

mkdir -p "$OUT"
SRCS=$(find app/src/main/java/com/qiao/remotedesk/model \
            app/src/main/java/com/qiao/remotedesk/net -name '*.java' | tr '\n' ' ')

"$JAVAC" --release 8 -encoding UTF-8 -Xlint:-options -cp "$AJ" -d "$OUT" \
    $SRCS tools/selftest/android/util/Base64.java \
    tools/selftest/NetSelfTest.java

"$JAVA" -cp "$OUT_WIN;$AJ" NetSelfTest
