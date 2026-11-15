"""给指定函数的每个 `goto <label>;` 插入 logcat 埋点。

用法: python tools/patch_step.py <file> "<function header>" <tag> [label] [--revert]
label 默认 fail。埋点宏 QRDP_STEP 需在被改文件中已定义（用 --macro 自动插入）。
"""
import io
import os
import re
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

MACRO = '''
#if defined(__ANDROID__)
#include <android/log.h>
#define QRDP_STEP(s) __android_log_print(ANDROID_LOG_ERROR, "qrdp", "%s", s)
#endif
'''


def main():
    path = sys.argv[1]
    header = sys.argv[2]
    tag = sys.argv[3]
    args = [a for a in sys.argv[4:] if not a.startswith("--")]
    label = args[0] if args else "fail"
    revert = "--revert" in sys.argv

    full = os.path.join(ROOT, path)
    with io.open(full, "r", encoding="utf-8", newline="") as f:
        raw = f.read()
    crlf = "\r\n" in raw
    src = raw.replace("\r\n", "\n")

    start = src.index(header)
    end = src.index("\n}\n", start)
    body = src[start:end]

    if revert:
        pat = re.compile(r'\{\s*QRDP_STEP\("%s\d+"\);\s*goto %s;\s*\}' % (tag, label))
        body2 = pat.sub("goto %s;" % label, body)
        if body2 == body:
            print("没有找到 %s 埋点" % tag)
            return
        src = src[:start] + body2 + src[end:]
        print("已回滚", tag)
    else:
        if ('"%s1"' % tag) in body:
            print("已存在埋点，跳过")
            return
        n = [0]

        def repl(m):
            n[0] += 1
            return '{ QRDP_STEP("%s%d"); goto %s; }' % (tag, n[0], label)
        body2 = re.sub(r"goto %s;" % label, repl, body)
        src = src[:start] + body2 + src[end:]
        print("埋点数量:", n[0])
        if "QRDP_STEP(s)" not in src:
            anchor = "#include"
            src = src.replace(anchor, MACRO.strip() + "\n\n" + anchor, 1)
            print("已插入埋点宏")

    with io.open(full, "w", encoding="utf-8", newline="") as f:
        f.write(src.replace("\n", "\r\n") if crlf else src)


main()
