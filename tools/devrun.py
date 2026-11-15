# -*- coding: utf-8 -*-
"""设备端快速迭代：把协议栈编成 dex，用 app_process 直接跑，不安装 APK。

用法：
    python tools/devrun.py <host> [port] [user] [password] [domain] [seconds]

流程：javac → d8 → jar(classes.dex) → adb push → app_process 运行 → 回显输出。
每轮只要几秒，改完协议代码立即能验证，最后再一次性打 APK 安装。
"""
import glob
import os
import shutil
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEV = r"D:\ZTESOFT\AndroidDev"
JDK = os.path.join(DEV, "jdk17")
BT = os.path.join(DEV, "sdk", "build-tools", "35.0.0")
ADB = os.path.join(DEV, "sdk", "platform-tools", "adb.exe")
D8 = os.path.join(BT, "d8.bat")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
JAR = os.path.join(JDK, "bin", "jar.exe")

SRC = os.path.join(ROOT, "app", "src", "main", "java")
CLI = os.path.join(ROOT, "tools", "rdp-test", "Cli.java")
BUILD = os.path.join(ROOT, "build")
CLI_OUT = os.path.join(BUILD, "cli")
DEX_OUT = os.path.join(BUILD, "dex")
JAR_PATH = os.path.join(BUILD, "rdcli.jar")
REMOTE = "/data/local/tmp/rdcli.jar"
MAIN = "com.qiao.remotedesk.rdp.Cli"


def run(cmd, **kw):
    print("  $ " + (cmd if isinstance(cmd, str) else " ".join(cmd)))
    return subprocess.run(cmd, shell=isinstance(cmd, str), **kw)


def main():
    os.environ["JAVA_HOME"] = JDK          # d8.bat 依赖 JAVA_HOME
    for d in (CLI_OUT, DEX_OUT):
        shutil.rmtree(d, ignore_errors=True)
        os.makedirs(d)

    sources = glob.glob(os.path.join(SRC, "com", "qiao", "remotedesk", "rdp", "*.java"))
    sources.append(CLI)
    print("[1/4] javac")
    r = run([JAVAC, "-encoding", "UTF-8", "-nowarn", "-d", CLI_OUT] + sources,
            capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        print("编译失败")
        return 1
    print("  OK")

    classes = glob.glob(os.path.join(CLI_OUT, "**", "*.class"), recursive=True)
    print("[2/4] d8（%d 个 class）" % len(classes))
    r = run(" ".join(["\"" + D8 + "\"", "--min-api", "26", "--output", "\"" + DEX_OUT + "\""]
                     + ["\"" + c + "\"" for c in classes]), capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        print("d8 失败")
        return 1
    print("  OK")

    print("[3/4] 打包 jar")
    dex = os.path.join(DEX_OUT, "classes.dex")
    if not os.path.exists(dex):
        print("  未生成 classes.dex")
        return 1
    r = run([JAR, "cf", JAR_PATH, "-C", DEX_OUT, "classes.dex"], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stderr)
        return 1
    print("  OK  %s (%d B)" % (JAR_PATH, os.path.getsize(JAR_PATH)))

    print("[4/4] 推送到设备并运行")
    r = run([ADB, "push", JAR_PATH, REMOTE], capture_output=True, text=True)
    if r.returncode != 0:
        print(r.stdout)
        print(r.stderr)
        return 1

    args = sys.argv[1:] or ["192.0.2.10"]  # 文档示例地址，实际测试请显式传入主机
    while len(args) < 6:
        args.append("" if len(args) == 4 else ("12" if len(args) == 5 else "3389"))
    host, port, user, password, domain, seconds = args[:6]
    if not user:
        user = ""
    if not password:
        # 口令不入库：用位置参数或环境变量 QRDP_TEST_PASSWORD 提供
        password = os.environ.get("QRDP_TEST_PASSWORD", "")
    if not password:
        print("  提示：未设置口令，可用位置参数或 QRDP_TEST_PASSWORD 环境变量提供")
    if not seconds:
        seconds = "12"

    cmd = ("CLASSPATH=%s app_process /system/bin %s %s %s %s %s '%s' %s %s"
           % (REMOTE, MAIN, host, port, user, password, domain, seconds,
              " ".join(args[6:])))
    print("  $ adb shell " + cmd)
    r = subprocess.run([ADB, "shell", cmd], capture_output=True, text=True, timeout=120)
    out = (r.stdout or "") + (r.stderr or "")
    sys.stdout.write(out)
    if "RESULT: OK" in out:
        print("\n=== 协议已跑通 ===")
        subprocess.run([ADB, "pull", "/data/local/tmp/rd-frame.raw",
                        os.path.join(BUILD, "rd-frame.raw")], capture_output=True)
        return 0
    return 2


if __name__ == "__main__":
    sys.exit(main())
