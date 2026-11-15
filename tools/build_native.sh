#!/bin/bash
# 编译 JNI 桥接层，产出 app/src/main/jniLibs/arm64-v8a/libqrdp2.so
#
# 前置条件：
#   1. tools/freerdp-src/build-android 已由 CMake + Ninja 构建出 FreeRDP 静态库
#   2. tools/openssl-android/lib 下有 libssl.a / libcrypto.a
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
NDK=/d/ZTESOFT/AndroidDev/sdk/ndk/25.2.9519653
CLANG="$NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/aarch64-linux-android26-clang"
FR="$ROOT/tools/freerdp-src"
FRB="$FR/build-android"
SSL="$ROOT/tools/openssl-android"
OUT="$ROOT/app/src/main/jniLibs/arm64-v8a"

mkdir -p "$OUT"

# 收集全部 FreeRDP 静态库（核心 + 客户端 + winpr + 各通道）
LIBS=""
for f in "$FRB/libfreerdp/libfreerdp2.a" \
         "$FRB/client/common/libfreerdp-client2.a" \
         "$FRB/winpr/libwinpr/libwinpr2.a"; do
    if [ ! -f "$f" ]; then
        echo "缺少静态库: $f" >&2
        exit 1
    fi
    LIBS="$LIBS $f"
done
while IFS= read -r f; do
    LIBS="$LIBS $f"
done < <(find "$FRB/channels" -name "*.a" 2>/dev/null | sort)

echo "链接的静态库数量: $(echo $LIBS | wc -w)"

"$CLANG" -shared -fPIC -O2 \
    -o "$OUT/libqrdp2.so" \
    -I"$FR/FR/include" \
    -I"$FR/FR/winpr/include" \
    -I"$FRB/include" \
    -I"$FRB/winpr/include" \
    -I"$SSL/include" \
    "$ROOT/app/src/main/jni/qrdp.c" \
    -Wl,--start-group $LIBS \
    "$SSL/lib/libssl.a" \
    "$SSL/lib/libcrypto.a" \
    -Wl,--end-group \
    -lz -llog -ljnigraphics -lOpenSLES -ldl -lm

ls -lh "$OUT/libqrdp2.so"
