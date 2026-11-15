# 第三方组件与许可声明

本仓库的原创代码采用 **Apache License 2.0**（见根目录 `LICENSE`）。
下面列出随本仓库 / 发布 APK 一起分发的第三方组件、版权归属与许可证全文位置。

## 1. 随二进制分发的组件

`app/src/main/jniLibs/arm64-v8a/libqrdp2.so` 是本项目 JNI 层
（`app/src/main/jni/qrdp.c`）静态链接下列库后生成的产物：

| 组件 | 版本 | 用途 | 许可证 | 许可证全文 |
| --- | --- | --- | --- | --- |
| FreeRDP（libfreerdp / libfreerdp-client、WinPR、channels 含 disp） | 2.11.7 | RDP 协议栈与显示控制通道 | Apache License 2.0 | `licenses/FreeRDP-2.11.7-LICENSE.txt` |
| OpenSSL（libcrypto / libssl） | 3.0.13 | TLS / 证书 / 加密运算 | Apache License 2.0 | `licenses/OpenSSL-3.0.13-LICENSE.txt` |

两份许可证全文均取自对应版本的官方发布源码，未作改动；根目录 `LICENSE`
同时提供一份完整的 Apache License 2.0 文本。
FreeRDP 2.11.7 上游未提供 `NOTICE` 文件，因此没有可随附的 NOTICE 内容。

**版权归属（保留自上游源码）**

- FreeRDP / WinPR：FreeRDP 项目贡献者；其中 Interleaved RLE 位图编解码相关源文件
  （`libfreerdp/codec/interleaved.c`）头部载明：

  ```
  Copyright 2014 Marc-Andre Moreau <marcandre.moreau@gmail.com>
  Copyright 2015 Thincast Technologies GmbH
  Copyright 2015 DI (FH) Martin Haimberger <martin.haimberger@thincast.com>
  Copyright 2016 Armin Novak <armin.novak@thincast.com>
  Copyright 2016 Thincast Technologies GmbH
  ```

- OpenSSL：The OpenSSL Project 及其贡献者；产品包含由 OpenSSL Project
  为 OpenSSL Toolkit 开发的软件（<https://www.openssl.org/>）。

**未随 APK 分发**：`zlib`、`liblog`、`libjnigraphics`、`libOpenSLES`
为 Android 系统库，仅在运行时由系统提供、在链接期引用，不包含在本仓库或 APK 中。
本构建未链接 FFmpeg、libjpeg、RemoteFX / H.264 等其它第三方编解码组件。

## 2. 移植与改写说明

- `app/src/main/java/com/qiao/remotedesk/rdp/BitmapCodec.java`：Interleaved RLE
  解码分支移植并改写自 FreeRDP `libfreerdp/codec/interleaved.c`（C → Java），
  保留其订单码常量表与解码流程，属于 Apache-2.0 第 4(b) 条所述的修改文件；
  原版权声明与许可声明已写入该文件头部。
- `app/src/main/jni/qrdp.c` 及 `rdp/FreeRdpSession.java` 等：通过 FreeRDP 公开 API
  调用协议栈，未复制上游源码。
- `rdp/Gcc.java`、`rdp/Ntlm.java`、`rdp/Der.java`、`rdp/Md4.java`、`rdp/Rc4.java`
  等协议类：依据 Microsoft Open Specifications（MS-RDPBCGR、MS-CSSP 等）与
  FreeRDP 实现思路独立编写，用于隧道诊断与早期纯 Java 协议栈，未复制上游源码。
- `tools/selftest/android/util/Base64.java` 是仅用于 JVM 自测的自写替身
  （内部调用 `java.util.Base64`），并非 AOSP 源码，且不会打进 APK。

## 3. 商标与名称

`RemoteDeskQR`、包名 `com.qiao.remotedesk` 与应用图标为本项目自有标识，
不含 Microsoft、Windows、ThinkPad 等第三方商标；文档中的产品名仅作描述性引用。
RDP 协议格式依据公开的 Microsoft Open Specifications 实现，本项目不宣称获得
Microsoft 授权或背书。

## 4. 再分发注意事项

重新分发本项目的 APK 或 `libqrdp2.so` 时，请一并保留本文件、`LICENSE`
以及 `licenses/` 下的两份许可证全文。若更换 FreeRDP / OpenSSL 版本或启用
其它编解码组件，请同步更新本清单。
