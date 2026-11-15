"""给 FreeRDP 的 freerdp_context_new 各失败分支插入 logcat 埋点（一次性调试补丁）。

做法：把函数体内第 N 个 `goto fail;` 替换为 `{ QRDP_STEP("sN"); goto fail; }`。
用花括号包住，避免 braceless if 被插入语句后控制流被破坏。
"""
import io
import os
import re
import sys

P = os.path.join(os.path.dirname(__file__), "freerdp-src", "FR", "libfreerdp", "core", "freerdp.c")

with io.open(P, "r", encoding="utf-8", newline="") as f:
    raw = f.read()

crlf = "\r\n" in raw
src = raw.replace("\r\n", "\n")


def body_span(text):
    start = text.index("BOOL freerdp_context_new(freerdp* instance)")
    end = text.index("\n}\n", start)
    return start, end


# ---------- 回滚上一版错误补丁 ----------
if '"pubSub"' in src:
    src = re.sub(r'\n[ \t]*QRDP_STEP\("(?:pubSub|metrics_new|rdp_new|graphics_new|errorDescription|'
                 r'channelErrorEvent|abortEvent|channels_new)"\);', "", src)
    src = re.sub(r'\n[ \t]*QRDP_STEP\("ok"\);\n', "\n", src)
    # 去掉可能残留的空块
    src = src.replace("\tif (ret)\n\t{\n\t\treturn TRUE;\n\t}", "\tif (ret)\n\t\treturn TRUE;")
    src = re.sub(r"\n#if defined\(__ANDROID__\)\n#include <android/log\.h>\n#define QRDP_STEP.*?"
                 r"#endif\n", "\n", src, flags=re.S)
    print("已回滚上一版补丁")

if "QRDP_STEP" in src:
    print("仍有残留，请手工检查")
    sys.exit(1)

# ---------- 加入埋点宏 ----------
anchor = "#include <assert.h>"
src = src.replace(anchor, anchor + '''

#if defined(__ANDROID__)
#include <android/log.h>
#define QRDP_STEP(s) __android_log_print(ANDROID_LOG_ERROR, "qrdp", "ctx_new 失败于 %s", s)
#else
#define QRDP_STEP(s) fprintf(stderr, "ctx_new failed at %s\\n", s)
#endif
''', 1)

# ---------- 逐个 goto fail 包上埋点 ----------
start, end = body_span(src)
body = src[start:end]
n = [0]


def repl(_m):
    n[0] += 1
    return '{ QRDP_STEP("s%d"); goto fail; }' % n[0]


body = re.sub(r"goto fail;", repl, body)
print("埋点数量:", n[0])

src = src[:start] + body + src[end:]

with io.open(P, "w", encoding="utf-8", newline="") as f:
    f.write(src.replace("\n", "\r\n") if crlf else src)
print("补丁已写入")
