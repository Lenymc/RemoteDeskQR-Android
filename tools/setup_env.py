# -*- coding: utf-8 -*-
"""安装 Android 构建环境到 D:/ZTESOFT/AndroidDev（一次性脚本）。

只依赖 Python 标准库。下载产物由上层脚本预先放到 downloads/。
产出：jdk17/、sdk/（cmdline-tools、platforms、build-tools、platform-tools）。
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

BASE = r"D:\ZTESOFT\AndroidDev"
DL = os.path.join(BASE, "downloads")
JDK = os.path.join(BASE, "jdk17")
SDK = os.path.join(BASE, "sdk")
LATEST = os.path.join(SDK, "cmdline-tools", "latest")


def log(msg):
    print(msg, flush=True)


def extract_jdk():
    javac = os.path.join(JDK, "bin", "javac.exe")
    if os.path.exists(javac):
        log("[1/4] JDK 已就绪，跳过")
        return
    tmp = os.path.join(BASE, "jdk_tmp")
    log("[1/4] 解压 JDK 17 ...")
    with zipfile.ZipFile(os.path.join(DL, "jdk17.zip")) as zf:
        zf.extractall(tmp)
    dirs = [d for d in glob.glob(os.path.join(tmp, "jdk-17*")) if os.path.isdir(d)]
    if not dirs:
        raise RuntimeError("JDK 压缩包里没找到 jdk-17* 目录")
    if os.path.exists(JDK):
        shutil.rmtree(JDK)
    shutil.move(dirs[0], JDK)
    shutil.rmtree(tmp, ignore_errors=True)
    log("     javac: " + javac)


def extract_cmdline_tools():
    manager = os.path.join(LATEST, "bin", "sdkmanager.bat")
    if os.path.exists(manager):
        log("[2/4] cmdline-tools 已就绪，跳过")
        return
    tmp = os.path.join(BASE, "cmd_tmp")
    log("[2/4] 解压 Android cmdline-tools ...")
    with zipfile.ZipFile(os.path.join(DL, "cmdline-tools.zip")) as zf:
        zf.extractall(tmp)
    src = os.path.join(tmp, "cmdline-tools")
    if not os.path.isdir(src):
        raise RuntimeError("cmdline-tools.zip 内没有 cmdline-tools/ 目录")
    os.makedirs(os.path.dirname(LATEST), exist_ok=True)
    if os.path.exists(LATEST):
        shutil.rmtree(LATEST)
    shutil.move(src, LATEST)
    shutil.rmtree(tmp, ignore_errors=True)
    log("     sdkmanager: " + manager)


def write_licenses():
    lic = os.path.join(SDK, "licenses")
    os.makedirs(lic, exist_ok=True)
    files = {
        "android-sdk-license": "\n8933bad161af4178b1185d1a37fbf41ea5269c55\n"
                               "d56f5187479451eabf01fb78af6dfcb131a6481e\n"
                               "24333f8a63b6825ea9c5514f83c2829b004d1fee\n",
        "android-sdk-preview-license": "\n84831b9409646a918e30573bab4c9c91346d58abd7\n",
    }
    for name, body in files.items():
        with open(os.path.join(lic, name), "w", encoding="utf-8", newline="\n") as fh:
            fh.write(body)


def install_sdk_packages():
    marker = os.path.join(SDK, "build-tools", "35.0.0", "aapt2.exe")
    if os.path.exists(marker):
        log("[3/4] platform/build-tools 已就绪，跳过")
        return
    write_licenses()
    log("[3/4] sdkmanager 安装 platforms;android-35 / build-tools;35.0.0 / platform-tools ...")
    env = os.environ.copy()
    env["JAVA_HOME"] = JDK
    cmd = [
        "cmd.exe", "/c", os.path.join(LATEST, "bin", "sdkmanager.bat"),
        "--sdk_root=" + SDK,
        "platforms;android-35", "build-tools;35.0.0", "platform-tools",
    ]
    proc = subprocess.run(
        cmd, env=env, input="y\ny\ny\ny\ny\ny\ny\ny\n",
        stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        encoding="utf-8", errors="replace", timeout=1800,
    )
    tail = (proc.stdout or "")[-6000:]
    log(tail)
    log("     sdkmanager exit=" + str(proc.returncode))


def verify():
    log("[4/4] 校验工具链 ...")
    required = [
        os.path.join(JDK, "bin", "javac.exe"),
        os.path.join(JDK, "bin", "keytool.exe"),
        os.path.join(SDK, "platforms", "android-35", "android.jar"),
        os.path.join(SDK, "build-tools", "35.0.0", "aapt2.exe"),
        os.path.join(SDK, "build-tools", "35.0.0", "d8.bat"),
        os.path.join(SDK, "build-tools", "35.0.0", "zipalign.exe"),
        os.path.join(SDK, "build-tools", "35.0.0", "apksigner.bat"),
    ]
    missing = [p for p in required if not os.path.exists(p)]
    for p in required:
        log(("  OK   " if os.path.exists(p) else "  MISS ") + p)
    if missing:
        raise RuntimeError("工具链不完整，缺 %d 项" % len(missing))
    log("SETUP_OK")


if __name__ == "__main__":
    try:
        extract_jdk()
        extract_cmdline_tools()
        install_sdk_packages()
        verify()
    except Exception as exc:
        log("SETUP_FAILED: %s: %s" % (type(exc).__name__, exc))
        sys.exit(1)
