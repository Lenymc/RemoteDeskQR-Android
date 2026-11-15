# RemoteDeskQR Android

一款支持HTTP/SOCKS代理的windows远程桌面客户端软件，重点适配平板 HD 分辨率。
成品：`dist/RemoteDeskQR-1.0.apk`（已签名，minSdk 26 / targetSdk 33）。

首次启动不预置任何主机、账号或口令：在「主页 → ＋ 新建连接」录入目标信息，
配置以 Android Keystore AES/GCM 加密后仅保存在本机。

## 功能

- 内嵌 FreeRDP：RDP 画面在应用内直接渲染（JNI + Android Bitmap 零拷贝），不依赖外部 RDP 客户端
- 首页九宫格主机卡片、侧栏会话列表、多标签页（配置 / 会话 / 全局设置）
- 触控模式（点哪打哪、长按放开 = 右键、按住拖动）与模拟鼠标模式（触摸板式虚拟光标）
- 双指缩放 / 平移仅作用于本地视图矩阵，不改远程分辨率、不重连；接近 100% 且居中时自动吸附回全屏
- 气泡菜单「缩放」选择远端缩放 100 / 125 / 150 / 175%（按缩放比例缩小请求桌面尺寸、本地等比铺满实现放大；保存配置后自动重连，所有服务端生效，放大时清晰度下降）
- 主机配置「显示」页提供分辨率预设与缩放比例；「高级 → 桌面体验」六项写入 RDP `PerformanceFlags`，保存后重新连接会话生效
- 真全屏沉浸式，按屏幕真实尺寸自适应远端分辨率
- 竖状图标气泡工具栏：一级图标按「显示 / 窗口 / 缩放 / 模式 / 气泡 / 键盘 / 会话」排列，展开二级功能列表，跟随气泡位置、点选项应用并收起、点外部收起
- 内置屏幕键盘：气泡菜单「键盘」单击开关，六行笔记本键盘布局，顶部细线把手可拖动调高度并持久化
- 外接真实鼠标：右键 / 滚轮 / 悬停移动转发，小型自绘鼠标指针
- 输入合并串行：MOVE 单槽降采样（直连 60Hz / 代理 30Hz），按钮 / 滚轮 / 按键严格保序，UI 线程不再直调 JNI
- 气泡菜单切换分辨率 / 色深会保存配置并自动重建会话；服务端不接受请求尺寸时如实显示实际桌面尺寸
- 悬浮气泡：贴右缘半圆、可拖动，单击 / 双击 / 长按动作在全局设置里可配（含「打开/关闭虚拟键盘」）
- 气泡透明度 0~90%（0% 完全不透明，数值越大越透明），全局设置滑杆与气泡菜单「气泡 → 透明度」共用同一配置；旧配置自动迁移且视觉观感不变
- 代理与隧道：直连 / HTTP CONNECT / SOCKS5 / SOCKS4a，本地 `127.0.0.x` 回环隧道、
  前台服务保活、字节统计、分段连通性诊断
- 全局设置标签页（全局代理 / 悬浮气泡），多选框置于文字之后，保存后自动关闭标签回主页
- 配置加密落盘（Android Keystore AES/GCM）、原子写 + 损坏备份

## 平板 HD 适配

`values` / `values-sw600dp` / `values-sw720dp` 三档尺寸资源，配合运行时按
`smallestScreenWidthDp` 与内容区宽度计算卡片列数（HD 最多 4 列）：
`sw720dp` 侧栏 264dp、卡片 136dp 高、字号 18sp；`sw600dp` 侧栏 220dp；
手机侧栏收起、卡片最多 2 列。

## 实现说明

- 原版用 `mstscax.dll`；Android 侧把 FreeRDP 2.11.7 + OpenSSL 交叉编译成
  `libqrdp2.so`，经 JNI 把远端帧缓冲直接写入 Android Bitmap。
- 第三方原生库源码体量大，不随仓库分发；仓库已含预编译
  `app/src/main/jniLibs/arm64-v8a/libqrdp2.so`，`tools/build.py` 可直接打包 APK。
  需重编译原生库时按 `tools/build_native.sh` 准备 FreeRDP / OpenSSL 源码目录。
- 经代理的会话启用 X.224 安全协商，直连会话保持 `/sec:rdp`。

## 第三方组件与许可

原创代码采用 Apache-2.0（见 `LICENSE`）。随仓库分发的 `libqrdp2.so` 静态链接
FreeRDP 2.11.7（含 WinPR、disp 通道）与 OpenSSL 3.0.13，两者同为 Apache-2.0，
允许闭源及二进制再分发。`rdp/BitmapCodec.java` 的 Interleaved RLE 解码移植并修改自
FreeRDP，文件头保留了原版权声明。组件清单、版权人、改写说明与许可证全文见
`THIRD_PARTY_NOTICES.md` 和 `licenses/` 目录；再分发 APK 时请一并保留这些文件。

## 构建

工具链需要 JDK 17 + Android SDK 35 + build-tools 35.0.0；路径在
`tools/build.py`、`tools/setup_env.py` 等脚本顶部集中配置，按本机安装位置调整即可。

```bash
python tools/setup_env.py     # 首次安装工具链
python tools/build.py         # 构建并签名 dist/RemoteDeskQR-1.0.apk
bash tools/deploy.sh [host]   # 编译原生库 + 打包 + 版本核对 + 安装 + 启动
tools/run_selftest.sh         # 代理协议自测
```

纯 Java + Android SDK，无 Gradle / AndroidX；构建链为
`aapt2 → javac --release 8 → d8 → zipalign → apksigner`。

## 验证结果

- 平板实机验证：直连与经 SOCKS5 / HTTP CONNECT 代理的会话均「已连接」，远程桌面正常渲染
- 开始菜单 / 双击图标 / 拖动窗口 / 长按右键菜单 / 双指缩放均通过；
  颜色、全屏、气泡工具栏、设置页对齐已核对
- APK v2/v3 签名校验通过；协议自测 8/8 通过

## 已知限制

- 代理不支持 NTLM / Negotiate，HTTP 代理仅 Basic；SOCKS4 不支持 IPv6
- 经代理时 RDP 的 UDP 传输不可用，自动回退 TCP
- 预编译原生库仅 arm64-v8a
