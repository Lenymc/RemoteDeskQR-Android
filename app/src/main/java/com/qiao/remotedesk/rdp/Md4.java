package com.qiao.remotedesk.rdp;

/**
 * MD4 实现（RFC 1320），NTLM 的 NTOWFv1/v2 需要它，JDK 未提供。
 */
final class Md4 {

    private int a, b, c, d;
    private final byte[] tail = new byte[64];
    private int tailLen;
    private long total;

    private Md4() {
        a = 0x67452301;
        b = 0xEFCDAB89;
        c = 0x98BADCFE;
        d = 0x10325476;
    }

    static byte[] digest(byte[] data) {
        Md4 m = new Md4();
        m.update(data, 0, data.length);
        return m.finish();
    }

    private void update(byte[] data, int off, int len) {
        total += len;
        if (tailLen > 0) {
            int need = 64 - tailLen;
            int n = Math.min(need, len);
            System.arraycopy(data, off, tail, tailLen, n);
            tailLen += n;
            off += n;
            len -= n;
            if (tailLen == 64) {
                block(tail, 0);
                tailLen = 0;
            }
        }
        while (len >= 64) {
            block(data, off);
            off += 64;
            len -= 64;
        }
        if (len > 0) {
            System.arraycopy(data, off, tail, tailLen, len);
            tailLen += len;
        }
    }

    private byte[] finish() {
        long bits = total << 3;
        byte[] pad = new byte[(tailLen < 56) ? (56 - tailLen) : (120 - tailLen)];
        pad[0] = (byte) 0x80;
        update(pad, 0, pad.length);
        byte[] lenBytes = new byte[8];
        for (int i = 0; i < 8; i++) {
            lenBytes[i] = (byte) (bits >>> (8 * i));
        }
        update(lenBytes, 0, 8);
        byte[] out = new byte[16];
        putLe(out, 0, a);
        putLe(out, 4, b);
        putLe(out, 8, c);
        putLe(out, 12, d);
        return out;
    }

    private static void putLe(byte[] o, int off, int v) {
        o[off] = (byte) v;
        o[off + 1] = (byte) (v >>> 8);
        o[off + 2] = (byte) (v >>> 16);
        o[off + 3] = (byte) (v >>> 24);
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xFF) | ((b[o + 1] & 0xFF) << 8)
                | ((b[o + 2] & 0xFF) << 16) | ((b[o + 3] & 0xFF) << 24);
    }

    private void block(byte[] p, int off) {
        int[] x = new int[16];
        for (int i = 0; i < 16; i++) {
            x[i] = le32(p, off + i * 4);
        }
        int aa = a, bb = b, cc = c, dd = d;

        // 第 1 轮
        aa = ff(aa, bb, cc, dd, x[0], 3);
        dd = ff(dd, aa, bb, cc, x[1], 7);
        cc = ff(cc, dd, aa, bb, x[2], 11);
        bb = ff(bb, cc, dd, aa, x[3], 19);
        aa = ff(aa, bb, cc, dd, x[4], 3);
        dd = ff(dd, aa, bb, cc, x[5], 7);
        cc = ff(cc, dd, aa, bb, x[6], 11);
        bb = ff(bb, cc, dd, aa, x[7], 19);
        aa = ff(aa, bb, cc, dd, x[8], 3);
        dd = ff(dd, aa, bb, cc, x[9], 7);
        cc = ff(cc, dd, aa, bb, x[10], 11);
        bb = ff(bb, cc, dd, aa, x[11], 19);
        aa = ff(aa, bb, cc, dd, x[12], 3);
        dd = ff(dd, aa, bb, cc, x[13], 7);
        cc = ff(cc, dd, aa, bb, x[14], 11);
        bb = ff(bb, cc, dd, aa, x[15], 19);

        // 第 2 轮
        aa = gg(aa, bb, cc, dd, x[0], 3);
        dd = gg(dd, aa, bb, cc, x[4], 5);
        cc = gg(cc, dd, aa, bb, x[8], 9);
        bb = gg(bb, cc, dd, aa, x[12], 13);
        aa = gg(aa, bb, cc, dd, x[1], 3);
        dd = gg(dd, aa, bb, cc, x[5], 5);
        cc = gg(cc, dd, aa, bb, x[9], 9);
        bb = gg(bb, cc, dd, aa, x[13], 13);
        aa = gg(aa, bb, cc, dd, x[2], 3);
        dd = gg(dd, aa, bb, cc, x[6], 5);
        cc = gg(cc, dd, aa, bb, x[10], 9);
        bb = gg(bb, cc, dd, aa, x[14], 13);
        aa = gg(aa, bb, cc, dd, x[3], 3);
        dd = gg(dd, aa, bb, cc, x[7], 5);
        cc = gg(cc, dd, aa, bb, x[11], 9);
        bb = gg(bb, cc, dd, aa, x[15], 13);

        // 第 3 轮
        aa = hh(aa, bb, cc, dd, x[0], 3);
        dd = hh(dd, aa, bb, cc, x[8], 9);
        cc = hh(cc, dd, aa, bb, x[4], 11);
        bb = hh(bb, cc, dd, aa, x[12], 15);
        aa = hh(aa, bb, cc, dd, x[2], 3);
        dd = hh(dd, aa, bb, cc, x[10], 9);
        cc = hh(cc, dd, aa, bb, x[6], 11);
        bb = hh(bb, cc, dd, aa, x[14], 15);
        aa = hh(aa, bb, cc, dd, x[1], 3);
        dd = hh(dd, aa, bb, cc, x[9], 9);
        cc = hh(cc, dd, aa, bb, x[5], 11);
        bb = hh(bb, cc, dd, aa, x[13], 15);
        aa = hh(aa, bb, cc, dd, x[3], 3);
        dd = hh(dd, aa, bb, cc, x[11], 9);
        cc = hh(cc, dd, aa, bb, x[7], 11);
        bb = hh(bb, cc, dd, aa, x[15], 15);

        a += aa;
        b += bb;
        c += cc;
        d += dd;
    }

    private static int ff(int a, int b, int c, int d, int x, int s) {
        int v = a + ((b & c) | (~b & d)) + x;
        return Integer.rotateLeft(v, s);
    }

    private static int gg(int a, int b, int c, int d, int x, int s) {
        int v = a + ((b & c) | (b & d) | (c & d)) + x + 0x5A827999;
        return Integer.rotateLeft(v, s);
    }

    private static int hh(int a, int b, int c, int d, int x, int s) {
        int v = a + (b ^ c ^ d) + x + 0x6ED9EBA1;
        return Integer.rotateLeft(v, s);
    }
}
