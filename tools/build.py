# -*- coding: utf-8 -*-
"""零 Gradle / 零 Maven 依赖的 Android APK 构建脚本。

工具链来源：D:/ZTESOFT/AndroidDev（JDK 17 + Android SDK 35 + build-tools 35.0.0）。
流程：aapt2 compile → aapt2 link（生成 R.java）→ javac --release 8
      → jar → d8 → 合并 classes.dex → zipalign → apksigner 签名与校验。
用法：python tools/build.py
"""
import glob
import os
import shutil
import subprocess
import sys
import zipfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
DEV = r"D:\ZTESOFT\AndroidDev"
JDK = os.path.join(DEV, "jdk17")
SDK = os.path.join(DEV, "sdk")
BT = os.path.join(SDK, "build-tools", "35.0.0")
ANDROID_JAR = os.path.join(SDK, "platforms", "android-35", "android.jar")

SRC = os.path.join(ROOT, "app", "src", "main", "java")
RES = os.path.join(ROOT, "app", "src", "main", "res")
MANIFEST = os.path.join(ROOT, "app", "src", "main", "AndroidManifest.xml")
JNI_LIBS = os.path.join(ROOT, "app", "src", "main", "jniLibs")
BUILD = os.path.join(ROOT, "build")
DIST = os.path.join(ROOT, "dist")
APK_NAME = "RemoteDeskQR-1.0.apk"
STORE_PASS = "remotedesk123"

AAPT2 = os.path.join(BT, "aapt2.exe")
ZIPALIGN = os.path.join(BT, "zipalign.exe")
D8 = os.path.join(BT, "d8.bat")
APKSIGNER = os.path.join(BT, "apksigner.bat")
JAVAC = os.path.join(JDK, "bin", "javac.exe")
JAR = os.path.join(JDK, "bin", "jar.exe")
KEYTOOL = os.path.join(JDK, "bin", "keytool.exe")


def log(msg):
    print(msg, flush=True)


def run(cmd, label):
    log("  $ " + " ".join(cmd[:3]) + (" ..." if len(cmd) > 3 else ""))
    env = os.environ.copy()
    env["JAVA_HOME"] = JDK
    env["PATH"] = os.path.join(JDK, "bin") + os.pathsep + env.get("PATH", "")
    proc = subprocess.run(cmd, env=env, cwd=ROOT, stdout=subprocess.PIPE,
                          stderr=subprocess.STDOUT, encoding="utf-8",
                          errors="replace", timeout=900)
    out = (proc.stdout or "").strip()
    if out:
        log(out[-8000:])
    if proc.returncode != 0:
        raise RuntimeError("%s 失败，exit=%d" % (label, proc.returncode))
    log("  OK  " + label)


def cmd_bat(path, args):
    return ["cmd.exe", "/c", path] + args


def build():
    if not os.path.exists(ANDROID_JAR):
        raise RuntimeError("缺少 android.jar，请先运行 tools/setup_env.py")
    # 只清中间构建目录；dist 是产物/截图/签名密钥目录，保留并覆盖写目标文件。
    # 整目录 rmtree 会触发运行时的 safe-delete（移回收站）拦截，且会误删历史截图。
    if os.path.exists(BUILD):
        shutil.rmtree(BUILD)
    os.makedirs(BUILD)
    os.makedirs(DIST, exist_ok=True)
    gen = os.path.join(BUILD, "gen")
    classes = os.path.join(BUILD, "classes")
    dexdir = os.path.join(BUILD, "dex")
    for d in (gen, classes, dexdir):
        os.makedirs(d)

    res_zip = os.path.join(BUILD, "res.zip")
    base_apk = os.path.join(BUILD, "base.apk")
    unsigned_apk = os.path.join(BUILD, "unsigned.apk")
    aligned_apk = os.path.join(BUILD, "aligned.apk")
    final_apk = os.path.join(DIST, APK_NAME)
    # 签名密钥必须持久保存：放在 build/ 之外，重建时复用，否则覆盖安装会签名冲突
    sign_dir = os.path.join(ROOT, "signing")
    if not os.path.exists(sign_dir):
        os.makedirs(sign_dir)
    keystore = os.path.join(sign_dir, "remotedesk.keystore")
    classes_jar = os.path.join(BUILD, "classes.jar")

    log("[1/9] 编译资源（含 sw600dp / sw720dp 平板限定符）")
    run([AAPT2, "compile", "--dir", RES, "-o", res_zip], "aapt2 compile")

    log("[2/9] 链接资源与清单，生成 R.java")
    flats_dir = os.path.join(BUILD, "flats")
    with zipfile.ZipFile(res_zip, "r") as zf:
        zf.extractall(flats_dir)
    flats = glob.glob(os.path.join(flats_dir, "**", "*.flat"), recursive=True)
    if not flats:
        raise RuntimeError("aapt2 compile 未产出 .flat 文件")
    # 注意：普通资源必须作为位置参数传入；用 -R 会被当作 overlay，导致同名资源冲突
    import datetime, re
    _now = datetime.datetime.now()
    _vc = (_now.year - 2020) * 10000 + _now.month * 100 + _now.day  # 如 61114
    _vn = "1.0." + _now.strftime("%Y%m%d.%H%M")

    # 生成临时 manifest 并替换版本号（aapt2 link 的 --version-* 不覆盖 manifest 内的值）
    _tmp_manifest = os.path.join(ROOT, "build", "AndroidManifest_versioned.xml")
    os.makedirs(os.path.dirname(_tmp_manifest), exist_ok=True)
    with open(MANIFEST, "r", encoding="utf-8") as _mf:
        _msrc = _mf.read()
    _msrc = re.sub(r'android:versionCode="[^"]*"', 'android:versionCode="%d"' % _vc, _msrc)
    _msrc = re.sub(r'android:versionName="[^"]*"', 'android:versionName="%s"' % _vn, _msrc)
    with open(_tmp_manifest, "w", encoding="utf-8") as _mf:
        _mf.write(_msrc)

    run([AAPT2, "link", "-o", base_apk, "-I", ANDROID_JAR,
         "--manifest", _tmp_manifest, "--java", gen,
         "--min-sdk-version", "26", "--target-sdk-version", "33"] + flats, "aapt2 link")
    _verfile = os.path.join(ROOT, "dist", "version.txt")
    with open(_verfile, "w") as _vf:
        _vf.write(_vn + "\n")

    sources = glob.glob(os.path.join(SRC, "**", "*.java"), recursive=True)
    rjava = os.path.join(gen, "com", "qiao", "remotedesk", "R.java")
    if not os.path.exists(rjava):
        raise RuntimeError("aapt2 未生成 R.java：" + rjava)
    sources.append(rjava)
    log("[3/9] javac 编译 %d 个 Java 文件" % len(sources))
    run([JAVAC, "--release", "8", "-encoding", "UTF-8", "-Xlint:-options",
         "-cp", ANDROID_JAR, "-d", classes] + sources, "javac")

    log("[4/9] 打包 class 到 jar")
    run([JAR, "cf", classes_jar, "-C", classes, "."], "jar")

    log("[5/9] d8 生成 classes.dex")
    run(cmd_bat(D8, ["--lib", ANDROID_JAR, "--min-api", "26",
                     "--output", dexdir, classes_jar]), "d8")
    dex = os.path.join(dexdir, "classes.dex")
    if not os.path.exists(dex):
        raise RuntimeError("d8 未生成 classes.dex")

    log("[6/9] 合并 dex 到 APK")
    with zipfile.ZipFile(base_apk, "r") as zin:
        with zipfile.ZipFile(unsigned_apk, "w") as zout:
            for item in zin.infolist():
                data = zin.read(item.filename)
                zi = zipfile.ZipInfo(item.filename, item.date_time)
                zi.compress_type = item.compress_type
                zi.external_attr = item.external_attr
                zi.internal_attr = item.internal_attr
                zi.create_system = item.create_system
                zout.writestr(zi, data)
            zout.write(dex, "classes.dex", compress_type=zipfile.ZIP_DEFLATED)

            # 原生库（FreeRDP + OpenSSL）打进 lib/<abi>/
            so_count = 0
            if os.path.isdir(JNI_LIBS):
                for abi in sorted(os.listdir(JNI_LIBS)):
                    abi_dir = os.path.join(JNI_LIBS, abi)
                    if not os.path.isdir(abi_dir):
                        continue
                    for fn in sorted(os.listdir(abi_dir)):
                        if not fn.endswith(".so"):
                            continue
                        zout.write(os.path.join(abi_dir, fn),
                                   "lib/%s/%s" % (abi, fn),
                                   compress_type=zipfile.ZIP_STORED if fn.endswith(".so") else zipfile.ZIP_DEFLATED)
                        so_count += 1
            if so_count == 0:
                log("  警告：未找到原生库，内嵌 RDP 将不可用")
            else:
                log("  OK  打入 %d 个原生库" % so_count)
    log("  OK  合并完成（%d 字节）" % os.path.getsize(unsigned_apk))

    log("[7/9] zipalign 4 字节对齐")
    run([ZIPALIGN, "-f", "-p", "4", unsigned_apk, aligned_apk], "zipalign")

    log("[8/9] 生成签名密钥并签名")
    if not os.path.exists(keystore):
        run([KEYTOOL, "-genkeypair", "-keystore", keystore, "-alias", "remotedesk",
             "-keyalg", "RSA", "-keysize", "2048", "-validity", "10000",
             "-storepass", STORE_PASS, "-keypass", STORE_PASS,
             "-dname", "CN=RemoteDeskQR, OU=Dev, O=Qiao, L=CN, ST=CN, C=CN",
             "-storetype", "PKCS12"], "keytool")
    run(cmd_bat(APKSIGNER, ["sign", "--ks", keystore, "--ks-key-alias", "remotedesk",
                            "--ks-pass", "pass:" + STORE_PASS,
                            "--key-pass", "pass:" + STORE_PASS,
                            "--v1-signing-enabled", "true",
                            "--v2-signing-enabled", "true",
                            "--out", final_apk, aligned_apk]), "apksigner sign")

    log("[9/9] 校验签名与包结构")
    run(cmd_bat(APKSIGNER, ["verify", "--verbose", final_apk]), "apksigner verify")
    with zipfile.ZipFile(final_apk, "r") as zf:
        names = zf.namelist()
        for needed in ("AndroidManifest.xml", "classes.dex", "resources.arsc"):
            if needed not in names:
                raise RuntimeError("APK 缺少 " + needed)
        log("  APK 条目 %d 项，含资源限定符 %d 个" %
            (len(names), len([n for n in names if n.startswith("res/")])))
    log("BUILD_OK " + final_apk + " (" + str(os.path.getsize(final_apk)) + " 字节) v" + _vn)


if __name__ == "__main__":
    try:
        build()
    except Exception as exc:
        log("BUILD_FAILED: %s: %s" % (type(exc).__name__, exc))
        sys.exit(1)
