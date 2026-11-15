package com.qiao.remotedesk.rdp;

import java.io.ByteArrayOutputStream;

/**
 * 极简 DER 编码器，仅为 CredSSP 的 TSRequest / TSCredentials 服务。
 * 全部使用「显式上下文标签」（与 FreeRDP WinPR 行为一致）：
 * [0] INTEGER → A0 len 02 len v；[3] OCTET STRING → A3 len 04 len data。
 */
final class Der {

    /** ASN.1 基本类型标签 */
    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_CONTEXT = 0xA0; // 构造型 + context class

    private Der() {
    }

    /** DER 长度域。 */
    static byte[] len(int n) {
        if (n < 0x80) {
            return new byte[]{(byte) n};
        }
        if (n <= 0xFF) {
            return new byte[]{(byte) 0x81, (byte) n};
        }
        if (n <= 0xFFFF) {
            return new byte[]{(byte) 0x82, (byte) (n >>> 8), (byte) n};
        }
        return new byte[]{(byte) 0x83, (byte) (n >>> 16), (byte) (n >>> 8), (byte) n};
    }

    static byte[] tlv(int tag, byte[] content) {
        return Bytes.concat(new byte[]{(byte) tag}, len(content.length), content);
    }

    static byte[] sequence(byte[]... items) {
        return tlv(TAG_SEQUENCE, Bytes.concat(items));
    }

    /** 最小的正数 INTEGER 编码（CredSSP 只用到 6/1/2/7 这类小值）。 */
    static byte[] integer(long v) {
        if (v >= 0 && v <= 0x7F) {
            return tlv(TAG_INTEGER, new byte[]{(byte) v});
        }
        if (v >= 0 && v <= 0x7FFF) {
            return tlv(TAG_INTEGER, new byte[]{(byte) (v >> 8), (byte) v});
        }
        return tlv(TAG_INTEGER, new byte[]{
                (byte) (v >> 24), (byte) (v >> 16), (byte) (v >> 8), (byte) v});
    }

    static byte[] octetString(byte[] data) {
        return tlv(TAG_OCTET_STRING, data);
    }

    /** 显式标签 [n]。 */
    static byte[] context(int n, byte[] inner) {
        return tlv(TAG_CONTEXT | (n & 0x1F), inner);
    }

    static byte[] contextInteger(int n, long v) {
        return context(n, integer(v));
    }

    static byte[] contextOctetString(int n, byte[] data) {
        return context(n, octetString(data));
    }

    // ------------------------------------------------------------------
    // TSRequest / TSCredentials 构造
    // ------------------------------------------------------------------

    /**
     * TSRequest ::= SEQUENCE {
     *   version [0] INTEGER, negoTokens [1] NegoData OPTIONAL,
     *   authInfo [2] OCTET STRING OPTIONAL, pubKeyAuth [3] OCTET STRING OPTIONAL,
     *   errorCode [4] INTEGER OPTIONAL, clientNonce [5] OCTET STRING OPTIONAL }
     */
    static byte[] tsRequest(int version, byte[] negoToken, byte[] authInfo,
                            byte[] pubKeyAuth, Long errorCode, byte[] clientNonce) {
        ByteArrayOutputStream body = new ByteArrayOutputStream(256);
        writeTo(body, contextInteger(0, version));
        if (negoToken != null && negoToken.length > 0) {
            // NegoData ::= SEQUENCE OF SEQUENCE { negoToken [0] OCTET STRING }
            byte[] item = sequence(contextOctetString(0, negoToken));
            byte[] list = sequence(item);
            writeTo(body, context(1, list));
        }
        if (authInfo != null && authInfo.length > 0) {
            writeTo(body, contextOctetString(2, authInfo));
        }
        if (pubKeyAuth != null && pubKeyAuth.length > 0) {
            writeTo(body, contextOctetString(3, pubKeyAuth));
        }
        if (errorCode != null) {
            writeTo(body, contextInteger(4, errorCode));
        }
        if (clientNonce != null && clientNonce.length > 0) {
            writeTo(body, contextOctetString(5, clientNonce));
        }
        return sequence(body.toByteArray());
    }

    /** TSCredentials ::= SEQUENCE { credType [0] INTEGER, credentials [1] OCTET STRING } */
    static byte[] tsCredentials(int credType, byte[] credentials) {
        return sequence(contextInteger(0, credType), contextOctetString(1, credentials));
    }

    /** TSPasswordCreds ::= SEQUENCE { domainName [0], userName [1], password [2] } */
    static byte[] tsPasswordCreds(byte[] domain, byte[] user, byte[] password) {
        return sequence(contextOctetString(0, domain),
                contextOctetString(1, user),
                contextOctetString(2, password));
    }

    private static void writeTo(ByteArrayOutputStream out, byte[] b) {
        out.write(b, 0, b.length);
    }

    // ------------------------------------------------------------------
    // 解析（只需取出 negoToken / errorCode / pubKeyAuth）
    // ------------------------------------------------------------------

    /** 极简 TLV 读取器。 */
    static final class Reader {
        private final byte[] b;
        private int pos;
        private final int end;

        Reader(byte[] b) {
            this(b, 0, b.length);
        }

        Reader(byte[] b, int off, int len) {
            this.b = b;
            this.pos = off;
            this.end = off + len;
        }

        boolean hasMore() {
            return pos < end;
        }

        int tag() {
            return b[pos] & 0xFF;
        }

        int peekTag() {
            return hasMore() ? (b[pos] & 0xFF) : -1;
        }

        /** 读 TLV，返回内容切片；长度非法时抛 IllegalStateException。 */
        byte[] read() {
            if (pos + 2 > end) {
                throw new IllegalStateException("DER 数据不足");
            }
            int t = b[pos++] & 0xFF;
            int l = b[pos++] & 0xFF;
            if ((l & 0x80) != 0) {
                int n = l & 0x7F;
                l = 0;
                for (int i = 0; i < n; i++) {
                    l = (l << 8) | (b[pos++] & 0xFF);
                }
            }
            if (pos + l > end) {
                throw new IllegalStateException("DER 长度越界 tag=0x" + Integer.toHexString(t));
            }
            byte[] content = Bytes.slice(b, pos, l);
            pos += l;
            return content;
        }

        byte[] readExpect(int expectTag) {
            if (tag() != expectTag) {
                throw new IllegalStateException("期望 tag 0x" + Integer.toHexString(expectTag)
                        + "，实际 0x" + Integer.toHexString(tag()));
            }
            return read();
        }

        static long toInteger(byte[] content) {
            long v = 0;
            for (byte c : content) {
                v = (v << 8) | (c & 0xFF);
            }
            return v;
        }

        void skip(int n) {
            pos += n;
        }

        int position() {
            return pos;
        }
    }
}
