package com.qiao.remotedesk.rdp;

import java.util.Arrays;

/** 可增长的字节缓冲，写入默认小端。 */
final class Out {

    private byte[] buf;
    private int pos;

    Out(int cap) {
        buf = new byte[Math.max(16, cap)];
    }

    private void ensure(int n) {
        if (pos + n <= buf.length) {
            return;
        }
        int cap = buf.length;
        while (cap < pos + n) {
            cap <<= 1;
        }
        buf = Arrays.copyOf(buf, cap);
    }

    int size() {
        return pos;
    }

    Out u8(int v) {
        ensure(1);
        buf[pos++] = (byte) v;
        return this;
    }

    Out u16(int v) {
        ensure(2);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        return this;
    }

    Out u16be(int v) {
        ensure(2);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    Out u24be(int v) {
        ensure(3);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    Out u32(long v) {
        ensure(4);
        buf[pos++] = (byte) v;
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 24);
        return this;
    }

    Out u32be(long v) {
        ensure(4);
        buf[pos++] = (byte) (v >>> 24);
        buf[pos++] = (byte) (v >>> 16);
        buf[pos++] = (byte) (v >>> 8);
        buf[pos++] = (byte) v;
        return this;
    }

    Out bytes(byte[] b) {
        ensure(b.length);
        System.arraycopy(b, 0, buf, pos, b.length);
        pos += b.length;
        return this;
    }

    Out bytes(byte[] b, int off, int len) {
        ensure(len);
        System.arraycopy(b, off, buf, pos, len);
        pos += len;
        return this;
    }

    Out ascii(String s) {
        return bytes(Bytes.ascii(s));
    }

    Out utf16(String s) {
        return bytes(Bytes.utf16(s));
    }

    Out zeros(int n) {
        ensure(n);
        Arrays.fill(buf, pos, pos + n, (byte) 0);
        pos += n;
        return this;
    }

    /** BER/PER 长度：<128 用单字节，否则 0x80|n + n 字节大端。 */
    Out perLength(int len) {
        if (len < 0x80) {
            return u8(len);
        }
        if (len < 0x4000) {
            u8(0x80 | (len >> 8));
            u8(len & 0xFF);
            return this;
        }
        u8(0x80 | (len >> 16));
        u8((len >> 8) & 0xFF);
        u8(len & 0xFF);
        return this;
    }

    void setU8(int at, int v) {
        buf[at] = (byte) v;
    }

    void setU16(int at, int v) {
        buf[at] = (byte) v;
        buf[at + 1] = (byte) (v >>> 8);
    }

    void setU16be(int at, int v) {
        buf[at] = (byte) (v >>> 8);
        buf[at + 1] = (byte) v;
    }

    void setU32(int at, long v) {
        buf[at] = (byte) v;
        buf[at + 1] = (byte) (v >>> 8);
        buf[at + 2] = (byte) (v >>> 16);
        buf[at + 3] = (byte) (v >>> 24);
    }

    byte[] toArray() {
        return Arrays.copyOf(buf, pos);
    }
}
