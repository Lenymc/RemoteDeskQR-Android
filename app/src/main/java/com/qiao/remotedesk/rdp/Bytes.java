package com.qiao.remotedesk.rdp;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;

/** 字节读写工具：小端为主，含十六进制与 UTF-16LE 转换。 */
final class Bytes {

    static final Charset UTF16LE = Charset.forName("UTF-16LE");
    static final Charset ASCII = Charset.forName("US-ASCII");
    static final Charset UTF8 = Charset.forName("UTF-8");

    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private Bytes() {
    }

    static int u8(byte[] b, int o) {
        return b[o] & 0xFF;
    }

    static int u16(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8);
    }

    static int u16be(byte[] b, int o) {
        return ((b[o] & 0xFF) << 8) | (b[o + 1] & 0xFF);
    }

    static long u32(byte[] b, int o) {
        return ((long) (b[o] & 0xFF))
                | ((long) (b[o + 1] & 0xFF) << 8)
                | ((long) (b[o + 2] & 0xFF) << 16)
                | ((long) (b[o + 3] & 0xFF) << 24);
    }

    static int s32(byte[] b, int o) {
        return (int) u32(b, o);
    }

    static byte[] utf16(String s) {
        return s.getBytes(UTF16LE);
    }

    static byte[] utf16z(String s) {
        byte[] raw = s.getBytes(UTF16LE);
        byte[] r = new byte[raw.length + 2];
        System.arraycopy(raw, 0, r, 0, raw.length);
        return r;
    }

    static byte[] ascii(String s) {
        return s.getBytes(ASCII);
    }

    static String hex(byte[] b, int off, int len) {
        if (b == null) {
            return "(null)";
        }
        int end = Math.min(b.length, off + len);
        StringBuilder sb = new StringBuilder((end - off) * 2);
        for (int i = off; i < end; i++) {
            int v = b[i] & 0xFF;
            sb.append(HEX[v >>> 4]).append(HEX[v & 0x0F]);
        }
        return sb.toString();
    }

    static String hex(byte[] b) {
        return b == null ? "(null)" : hex(b, 0, b.length);
    }

    static String hex(byte[] b, int off, int len, int maxBytes) {
        if (b != null && b.length > off + maxBytes) {
            return hex(b, off, Math.min(len, maxBytes)) + "..." + len + "B";
        }
        return hex(b, off, len);
    }

    static byte[] concat(byte[]... parts) {
        int n = 0;
        for (byte[] p : parts) {
            n += p.length;
        }
        byte[] r = new byte[n];
        int o = 0;
        for (byte[] p : parts) {
            System.arraycopy(p, 0, r, o, p.length);
            o += p.length;
        }
        return r;
    }

    static byte[] slice(byte[] b, int off, int len) {
        byte[] r = new byte[len];
        System.arraycopy(b, off, r, 0, len);
        return r;
    }

    /** 从流中读满 n 字节，EOF 抛异常（不允许静默截断）。 */
    static byte[] readFully(InputStream in, int n) throws IOException {
        byte[] buf = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(buf, off, n - off);
            if (r < 0) {
                throw new EOFException("连接被对端关闭（已读 " + off + "/" + n + " 字节）");
            }
            off += r;
        }
        return buf;
    }

    static void writeFully(java.io.OutputStream out, byte[] b) throws IOException {
        out.write(b);
        out.flush();
    }
}
