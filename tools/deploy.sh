#!/bin/bash
# 一键：编译原生库 -> 打包 APK -> 版本核对 -> 推送安装 -> 启动 -> 抓日志
# 用法: bash tools/deploy.sh [host] [等待秒数]
set -e

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
HOST="${1:-192.0.2.10}"   # 示例地址（RFC 5737 文档网段），实际使用时传入真实主机
WAIT="${2:-8}"

cd "$ROOT"

echo "== 1/5 链接原生库"
bash tools/build_native.sh 2>&1 | tail -1

echo "== 2/5 打包 APK"
python tools/build.py 2>&1 | tail -1

LOCAL_VER=$(cat dist/version.txt 2>/dev/null | tr -d '[:space:]')
echo "   本地版本: $LOCAL_VER"

# adb 之后的命令需要关掉 msys 路径转换
export MSYS_NO_PATHCONV=1

# 版本核对
DEV_VER=$(timeout 15 adb shell dumpsys package com.qiao.remotedesk 2>/dev/null | grep -m1 versionName | sed 's/.*versionName=//' | tr -d '[:space:]')
echo "   设备版本: ${DEV_VER:-未安装}"

if [ "$LOCAL_VER" = "$DEV_VER" ]; then
    echo "== 3/5 版本一致（$LOCAL_VER），跳过安装"
else
    echo "== 3/5 版本不同（$DEV_VER -> $LOCAL_VER），安装中..."
    timeout 30 adb push dist/RemoteDeskQR-1.0.apk /data/local/tmp/rd.apk 2>&1 | tail -1
    timeout 35 adb install -r -d dist/RemoteDeskQR-1.0.apk 2>&1 | tail -1
fi

echo "== 4/5 启动 -> $HOST"
timeout 30 adb shell am force-stop com.qiao.remotedesk
timeout 30 adb logcat -c
timeout 30 adb shell am start -n com.qiao.remotedesk/.MainActivity --es connect "$HOST" 2>&1 | tail -1

echo "== 5/5 日志（等待 ${WAIT}s）"
sleep "$WAIT"
timeout 30 adb logcat -d -t 800 2>/dev/null | grep -E "qrdp|freerdp" | tail -30
