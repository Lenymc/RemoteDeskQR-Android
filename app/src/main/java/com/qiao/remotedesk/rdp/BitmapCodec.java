package com.qiao.remotedesk.rdp;

/*
 * FreeRDP: A Remote Desktop Protocol Implementation
 * Interleaved RLE Bitmap Codec
 *
 * Copyright 2014 Marc-Andre Moreau <marcandre.moreau@gmail.com>
 * Copyright 2015 Thincast Technologies GmbH
 * Copyright 2015 DI (FH) Martin Haimberger <martin.haimberger@thincast.com>
 * Copyright 2016 Armin Novak <armin.novak@thincast.com>
 * Copyright 2016 Thincast Technologies GmbH
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * 修改说明（Apache-2.0 第 4(b) 条）：本文件由 FreeRDP
 * libfreerdp/codec/interleaved.c 的 C 实现移植为 Java，改用本项目的字节缓冲
 * 与 ARGB 输出；RLE 订单码常量表与解码流程沿用原实现。完整许可证全文见
 * 仓库根目录 LICENSE 与 THIRD_PARTY_NOTICES.md。
 */

/**
 * 位图解码：未压缩原始位图 + Interleaved RLE（TS_INTERLEAVED）。
 * RLE 分支移植并修改自 FreeRDP libfreerdp/codec/interleaved.c。
 */
final class BitmapCodec {

    // RLE 订单码
    private static final int REGULAR_BG_RUN = 0x00;
    private static final int REGULAR_FG_RUN = 0x01;
    private static final int REGULAR_FGBG_IMAGE = 0x02;
    private static final int REGULAR_COLOR_RUN = 0x03;
    private static final int REGULAR_COLOR_IMAGE = 0x04;
    private static final int LITE_SET_FG_FG_RUN = 0x0C;
    private static final int LITE_SET_FG_FGBG_IMAGE = 0x0D;
    private static final int LITE_DITHERED_RUN = 0x0E;
    private static final int MEGA_MEGA_BG_RUN = 0xF0;
    private static final int MEGA_MEGA_FG_RUN = 0xF1;
    private static final int MEGA_MEGA_FGBG_IMAGE = 0xF2;
    private static final int MEGA_MEGA_COLOR_RUN = 0xF3;
    private static final int MEGA_MEGA_COLOR_IMAGE = 0xF4;
    private static final int MEGA_MEGA_SET_FG_RUN = 0xF6;
    private static final int MEGA_MEGA_SET_FGBG_IMAGE = 0xF7;
    private static final int MEGA_MEGA_DITHERED_RUN = 0xF8;
    private static final int SPECIAL_FGBG_1 = 0xF9;
    private static final int SPECIAL_FGBG_2 = 0xFA;
    private static final int SPECIAL_WHITE = 0xFD;
    private static final int SPECIAL_BLACK = 0xFE;

    private BitmapCodec() {
    }

    /**
     * 解码一块位图数据为 ARGB（0xFFRRGGBB）。
     *
     * @param src    数据起始
     * @param off    偏移
     * @param len    长度
     * @param width  位图宽度
     * @param height 位图高度
     * @param bpp    每像素位数（16/24/32）
     * @param flags  TS_BITMAP_DATA.flags
     */
    static int[] decode(byte[] src, int off, int len, int width, int height, int bpp, int flags)
            throws RdpException {
        if (width <= 0 || height <= 0) {
            throw new RdpException("位图尺寸非法：" + width + "×" + height);
        }
        boolean compressed = (flags & 0x0001) != 0;
        int pps = bpp / 8;
        if (pps < 2 || pps > 4) {
            throw new RdpException("不支持的色深：" + bpp + "bpp");
        }

        byte[] raw = new byte[width * height * pps];

        if (compressed) {
            int p = off;
            int l = len;
            int rleBpp = bpp;
            if ((flags & 0x0400) == 0) {
                // TS_CD_HEADER：cbCompFirstRowSize / cbCompMainBodySize / cbScanWidth /
                // cbUncompressedSize
                if (l < 8) {
                    throw new RdpException("压缩位图缺少 TS_CD_HEADER");
                }
                p += 8;
                l -= 8;
            }
            if (rleBpp == 32) {
                // 32bpp 会话的压缩位图实际按 24bpp RLE 传输
                rleBpp = 24;
                pps = 3;
                raw = new byte[width * height * pps];
            }
            if (!rleDecompress(src, p, l, raw, width * pps, width, height, pps)) {
                throw new RdpException("Interleaved RLE 解码失败（" + width + "×" + height
                        + " " + bpp + "bpp，" + len + "B）");
            }
        } else {
            int stride = ((width * bpp + 31) / 32) * 4;
            if (len < stride * height) {
                throw new RdpException("原始位图数据不足：需要 " + (stride * height)
                        + "B，实际 " + len + "B");
            }
            for (int y = 0; y < height; y++) {
                System.arraycopy(src, off + y * stride, raw, y * width * pps, width * pps);
            }
        }

        // RLE/原始数据均为自下而上，转换时翻转
        int[] argb = new int[width * height];
        int rowBytes = width * pps;
        for (int y = 0; y < height; y++) {
            int so = (height - 1 - y) * rowBytes;
            int dorow = y * width;
            for (int x = 0; x < width; x++) {
                int o = so + x * pps;
                int c;
                if (pps == 4 || pps == 3) {
                    c = 0xFF000000
                            | ((raw[o + 2] & 0xFF) << 16)
                            | ((raw[o + 1] & 0xFF) << 8)
                            | (raw[o] & 0xFF);
                } else {
                    int v = (raw[o] & 0xFF) | ((raw[o + 1] & 0xFF) << 8);
                    int r = (v >> 11) & 0x1F;
                    int g = (v >> 5) & 0x3F;
                    int b = v & 0x1F;
                    c = 0xFF000000 | (r << 19) | (g << 10) | (b << 3);
                }
                argb[dorow + x] = c;
            }
        }
        return argb;
    }

    // ------------------------------------------------------------------
    // Interleaved RLE
    // ------------------------------------------------------------------

    private static boolean rleDecompress(byte[] src, int sp0, int srcLen, byte[] dst,
                                         int rowDelta, int width, int height, int pps) {
        int sp = sp0;
        int end = sp0 + srcLen;
        int dp = 0;
        int destEnd = rowDelta * height;
        boolean firstLine = true;
        boolean insertFgPel = false;
        int fgPel = pps == 2 ? 0xFFFF : 0xFFFFFF;
        int black = 0;
        int white = pps == 2 ? 0xFFFF : 0xFFFFFF;
        int[] adv = new int[1];

        while (sp < end) {
            if (firstLine && dp >= rowDelta) {
                firstLine = false;
                insertFgPel = false;
            }
            int code = extractCodeId(src[sp] & 0xFF);
            int runLength;

            if (code == REGULAR_BG_RUN || code == MEGA_MEGA_BG_RUN) {
                runLength = runLength(code, src, sp, end, adv);
                if (adv[0] == 0) {
                    return false;
                }
                sp += adv[0];
                if (firstLine) {
                    if (insertFgPel) {
                        if (dp + pps > destEnd) {
                            return false;
                        }
                        writePixel(dst, dp, fgPel, pps);
                        dp += pps;
                        runLength--;
                    }
                    if (dp + runLength * pps > destEnd) {
                        return false;
                    }
                    for (int i = 0; i < runLength; i++) {
                        writePixel(dst, dp, black, pps);
                        dp += pps;
                    }
                } else {
                    if (insertFgPel) {
                        int t = readPixel(dst, dp - rowDelta, pps);
                        writePixel(dst, dp, t ^ fgPel, pps);
                        dp += pps;
                        runLength--;
                    }
                    if (dp + runLength * pps > destEnd) {
                        return false;
                    }
                    for (int i = 0; i < runLength; i++) {
                        int t = readPixel(dst, dp - rowDelta, pps);
                        writePixel(dst, dp, t, pps);
                        dp += pps;
                    }
                }
                insertFgPel = true;
                continue;
            }
            insertFgPel = false;

            switch (code) {
                case REGULAR_FG_RUN:
                case MEGA_MEGA_FG_RUN:
                case LITE_SET_FG_FG_RUN:
                case MEGA_MEGA_SET_FG_RUN: {
                    runLength = runLength(code, src, sp, end, adv);
                    if (adv[0] == 0) {
                        return false;
                    }
                    sp += adv[0];
                    if (code == LITE_SET_FG_FG_RUN || code == MEGA_MEGA_SET_FG_RUN) {
                        if (sp + pps > end) {
                            return false;
                        }
                        fgPel = readPixel(src, sp, pps);
                        sp += pps;
                    }
                    if (dp + runLength * pps > destEnd) {
                        return false;
                    }
                    if (firstLine) {
                        for (int i = 0; i < runLength; i++) {
                            writePixel(dst, dp, fgPel, pps);
                            dp += pps;
                        }
                    } else {
                        for (int i = 0; i < runLength; i++) {
                            int t = readPixel(dst, dp - rowDelta, pps);
                            writePixel(dst, dp, t ^ fgPel, pps);
                            dp += pps;
                        }
                    }
                    break;
                }

                case LITE_DITHERED_RUN:
                case MEGA_MEGA_DITHERED_RUN: {
                    runLength = runLength(code, src, sp, end, adv);
                    if (adv[0] == 0) {
                        return false;
                    }
                    sp += adv[0];
                    if (sp + pps * 2 > end) {
                        return false;
                    }
                    int pixelA = readPixel(src, sp, pps);
                    sp += pps;
                    int pixelB = readPixel(src, sp, pps);
                    sp += pps;
                    if (dp + runLength * 2 * pps > destEnd) {
                        return false;
                    }
                    for (int i = 0; i < runLength; i++) {
                        writePixel(dst, dp, pixelA, pps);
                        dp += pps;
                        writePixel(dst, dp, pixelB, pps);
                        dp += pps;
                    }
                    break;
                }

                case REGULAR_COLOR_RUN:
                case MEGA_MEGA_COLOR_RUN: {
                    runLength = runLength(code, src, sp, end, adv);
                    if (adv[0] == 0) {
                        return false;
                    }
                    sp += adv[0];
                    if (sp + pps > end) {
                        return false;
                    }
                    int pixelA = readPixel(src, sp, pps);
                    sp += pps;
                    if (dp + runLength * pps > destEnd) {
                        return false;
                    }
                    for (int i = 0; i < runLength; i++) {
                        writePixel(dst, dp, pixelA, pps);
                        dp += pps;
                    }
                    break;
                }

                case REGULAR_FGBG_IMAGE:
                case MEGA_MEGA_FGBG_IMAGE:
                case LITE_SET_FG_FGBG_IMAGE:
                case MEGA_MEGA_SET_FGBG_IMAGE: {
                    runLength = runLength(code, src, sp, end, adv);
                    if (adv[0] == 0) {
                        return false;
                    }
                    sp += adv[0];
                    if (code == LITE_SET_FG_FGBG_IMAGE || code == MEGA_MEGA_SET_FGBG_IMAGE) {
                        if (sp + pps > end) {
                            return false;
                        }
                        fgPel = readPixel(src, sp, pps);
                        sp += pps;
                    }
                    int remaining = runLength;
                    while (remaining > 8) {
                        if (sp + 1 > end) {
                            return false;
                        }
                        int bitmask = src[sp++] & 0xFF;
                        dp = writeFgBg(dst, dp, destEnd, rowDelta, bitmask, fgPel, 8, firstLine, pps);
                        if (dp < 0) {
                            return false;
                        }
                        remaining -= 8;
                    }
                    if (remaining > 0) {
                        if (sp + 1 > end) {
                            return false;
                        }
                        int bitmask = src[sp++] & 0xFF;
                        dp = writeFgBg(dst, dp, destEnd, rowDelta, bitmask, fgPel, remaining,
                                firstLine, pps);
                        if (dp < 0) {
                            return false;
                        }
                    }
                    break;
                }

                case REGULAR_COLOR_IMAGE:
                case MEGA_MEGA_COLOR_IMAGE: {
                    runLength = runLength(code, src, sp, end, adv);
                    if (adv[0] == 0) {
                        return false;
                    }
                    sp += adv[0];
                    if (sp + runLength * pps > end || dp + runLength * pps > destEnd) {
                        return false;
                    }
                    for (int i = 0; i < runLength; i++) {
                        int t = readPixel(src, sp, pps);
                        sp += pps;
                        writePixel(dst, dp, t, pps);
                        dp += pps;
                    }
                    break;
                }

                case SPECIAL_FGBG_1:
                    sp++;
                    dp = writeFgBg(dst, dp, destEnd, rowDelta, 0x03, fgPel, 8, firstLine, pps);
                    if (dp < 0) {
                        return false;
                    }
                    break;

                case SPECIAL_FGBG_2:
                    sp++;
                    dp = writeFgBg(dst, dp, destEnd, rowDelta, 0x05, fgPel, 8, firstLine, pps);
                    if (dp < 0) {
                        return false;
                    }
                    break;

                case SPECIAL_WHITE:
                    sp++;
                    if (dp + pps > destEnd) {
                        return false;
                    }
                    writePixel(dst, dp, white, pps);
                    dp += pps;
                    break;

                case SPECIAL_BLACK:
                    sp++;
                    if (dp + pps > destEnd) {
                        return false;
                    }
                    writePixel(dst, dp, black, pps);
                    dp += pps;
                    break;

                default:
                    return false;
            }
        }
        return true;
    }

    /** 写 bitmask 指定的像素，返回新的目标字节偏移；越界返回 -1。 */
    private static int writeFgBg(byte[] dst, int dp, int destEnd, int rowDelta, int bitmask,
                                 int fgPel, int cBits, boolean firstLine, int pps) {
        int mask = 0x01;
        if (dp + cBits * pps > destEnd) {
            return -1;
        }
        for (int i = 0; i < cBits; i++) {
            int data;
            if (firstLine) {
                data = (bitmask & mask) != 0 ? fgPel : 0;
            } else {
                int xor = readPixel(dst, dp - rowDelta, pps);
                data = (bitmask & mask) != 0 ? (xor ^ fgPel) : xor;
            }
            writePixel(dst, dp, data, pps);
            dp += pps;
            mask <<= 1;
        }
        return dp;
    }

    private static int extractCodeId(int hdr) {
        if ((hdr & 0xC0) != 0xC0) {
            return hdr >> 5;
        } else if ((hdr & 0xF0) == 0xF0) {
            return hdr;
        }
        return hdr >> 4;
    }

    /** 计算运行长度，adv[0] 为消耗的字节数（0 表示数据不足）。 */
    private static int runLength(int code, byte[] src, int sp, int end, int[] adv) {
        adv[0] = 0;
        if (sp >= end) {
            return 0;
        }
        int hdr = src[sp] & 0xFF;
        switch (code) {
            case REGULAR_FGBG_IMAGE:
                adv[0] = 1;
                if ((hdr & 0x1F) == 0) {
                    if (sp + 2 > end) {
                        adv[0] = 0;
                        return 0;
                    }
                    adv[0] = 2;
                    return (src[sp + 1] & 0xFF) + 1;
                }
                return (hdr & 0x1F) * 8;

            case LITE_SET_FG_FGBG_IMAGE:
                adv[0] = 1;
                if ((hdr & 0x0F) == 0) {
                    if (sp + 2 > end) {
                        adv[0] = 0;
                        return 0;
                    }
                    adv[0] = 2;
                    return (src[sp + 1] & 0xFF) + 1;
                }
                return (hdr & 0x0F) * 8;

            case REGULAR_BG_RUN:
            case REGULAR_FG_RUN:
            case REGULAR_COLOR_RUN:
            case REGULAR_COLOR_IMAGE:
                adv[0] = 1;
                if ((hdr & 0x1F) == 0) {
                    if (sp + 2 > end) {
                        adv[0] = 0;
                        return 0;
                    }
                    adv[0] = 2;
                    return (src[sp + 1] & 0xFF) + 32;
                }
                return hdr & 0x1F;

            case LITE_SET_FG_FG_RUN:
            case LITE_DITHERED_RUN:
                adv[0] = 1;
                if ((hdr & 0x0F) == 0) {
                    if (sp + 2 > end) {
                        adv[0] = 0;
                        return 0;
                    }
                    adv[0] = 2;
                    return (src[sp + 1] & 0xFF) + 16;
                }
                return hdr & 0x0F;

            default: // MEGA_MEGA_*
                if (sp + 3 > end) {
                    adv[0] = 0;
                    return 0;
                }
                adv[0] = 3;
                return (src[sp + 1] & 0xFF) | ((src[sp + 2] & 0xFF) << 8);
        }
    }

    private static int readPixel(byte[] b, int o, int pps) {
        if (pps == 2) {
            return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
        }
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8) | ((b[o + 2] & 0xFF) << 16);
    }

    private static void writePixel(byte[] b, int o, int v, int pps) {
        b[o] = (byte) v;
        b[o + 1] = (byte) (v >>> 8);
        if (pps == 3) {
            b[o + 2] = (byte) (v >>> 16);
        }
    }
}
