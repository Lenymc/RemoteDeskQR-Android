package com.qiao.remotedesk.rdp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/** MCS（T.125）连接：Connect Initial / Erect Domain / Attach User / Channel Join / Send Data。 */
final class McS {

    private static final int MCS_GLOBAL_CHANNEL = 1003;
    /** PER INTEGER 的 userId 下界（MCS_BASE_CHANNEL_ID），线上值 = userId - 1001。 */
    private static final int MCS_BASE_CHANNEL_ID = 1001;

    // DomainMCSPDU（T.125）编号，线上首字节 = 值 << 2
    static final int DomainMCSPDU_ERECT_DOMAIN_REQUEST = 1;
    static final int DomainMCSPDU_ATTACH_USER_REQUEST = 10;
    static final int DomainMCSPDU_ATTACH_USER_CONFIRM = 11;
    static final int DomainMCSPDU_CHANNEL_JOIN_REQUEST = 14;
    static final int DomainMCSPDU_CHANNEL_JOIN_CONFIRM = 15;
    static final int DomainMCSPDU_SEND_DATA_REQUEST = 25;
    static final int DomainMCSPDU_SEND_DATA_INDICATION = 26;

    private final InputStream in;
    private final OutputStream out;
    private final Log log;

    int userId;
    int globalChannelId = MCS_GLOBAL_CHANNEL;
    int[] channelIds = new int[0];

    McS(InputStream in, OutputStream out, Log log) {
        this.in = in;
        this.out = out;
        this.log = log;
    }

    /** 发送 Connect Initial 并解析 Connect Response，返回 GCC 用户数据区。 */
    byte[] connect(int width, int height, int colorDepth, int selectedProtocol, String clientName)
            throws IOException, RdpException {
        byte[] userData = Gcc.conferenceCreateRequest(width, height, colorDepth,
                selectedProtocol, clientName);
        byte[] frame = buildConnectInitial(userData);
        if ((System.getProperty("rdp.garbage") != null)) {
            frame = new byte[]{0x03, 0x00, 0x00, 0x1a, 0x02, (byte) 0xF0, (byte) 0x80,
                    0x7f, 0x0f, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17};
            log.log("MCS: 发送伪造 PDU 测试服务器反应");
        }
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
        log.log("MCS: Connect Initial " + frame.length + "B（GCC " + userData.length + "B）hex="
                + Bytes.hex(frame, 0, Math.min(frame.length, 96)));
        log.log("MCS: GCC=" + Bytes.hex(userData));

        byte[] resp = readTpkt();
        if (resp.length < 12) {
            throw new RdpException("MCS Connect Response 过短（" + resp.length + "B）");
        }
        byte[] gcc = extractGccUserData(resp);
        if (gcc == null) {
            throw new RdpException("MCS Connect Response 解析失败（未找到 GCC 用户数据）");
        }
        return gcc;
    }

    private static byte[] buildConnectInitial(byte[] userData) {
        Out body = new Out(256 + userData.length);
        body.u8(0x04).u8(0x01).u8(0x01);   // callingDomainSelector（OCTET STRING）
        body.u8(0x04).u8(0x01).u8(0x01);   // calledDomainSelector（OCTET STRING）
        body.u8(0x01).u8(0x01).u8(0xFF);   // upwardFlag（BOOLEAN TRUE）
        // 三组 DomainParameters，与 mcs_new 的取值一致
        domainParameters(body, 34, 2, 0, 0xFFFF);
        domainParameters(body, 1, 1, 1, 0x420);
        domainParameters(body, 0xFFFF, 0xFC17, 0xFFFF, 0xFFFF);
        berOctetString(body, userData);    // userData OCTET STRING

        byte[] inner = body.toArray();
        Out o = new Out(16 + inner.length);
        o.u8(0x03).u8(0x00).u16be(0);                 // TPKT 头（长度稍后回填）
        o.u8(0x02).u8(0xF0).u8(0x80);                 // X.224 Data TPDU
        // BER：APPLICATION 101，标签号 > 30，必须用高位标签号形式 0x7F 0x65
        o.u8(0x7F).u8(0x65);
        berLength(o, inner.length);
        o.bytes(inner);
        o.setU16be(2, o.size());
        return o.toArray();
    }

    /** 写一组 DomainParameters：SEQUENCE + 8 个最简 BER INTEGER（同 ber_write_integer）。 */
    private static void domainParameters(Out o, int maxChannels, int maxUsers, int maxTokens,
                                         int maxPduSize) {
        Out b = new Out(40);
        intOf(b, maxChannels);
        intOf(b, maxUsers);
        intOf(b, maxTokens);
        intOf(b, 1);              // numPriorities
        intOf(b, 0);              // minThroughput
        intOf(b, 1);              // maxHeight
        intOf(b, maxPduSize);
        intOf(b, 2);              // protocolVersion
        byte[] inner = b.toArray();
        o.u8(0x30);
        berLength(o, inner.length);
        o.bytes(inner);
    }

    /**
     * 最简 BER INTEGER：≤0xFF 用 1 字节内容，≤0xFFFF 用 2 字节，
     * ≤0xFFFFFF 用 3 字节，否则 4 字节（与 FreeRDP ber_write_integer 一致）。
     */
    private static void intOf(Out o, int v) {
        if (v <= 0xFF) {
            o.u8(0x02).u8(0x01).u8(v);
        } else if (v <= 0xFFFF) {
            o.u8(0x02).u8(0x02).u8((v >> 8) & 0xFF).u8(v & 0xFF);
        } else if (v <= 0xFFFFFF) {
            o.u8(0x02).u8(0x03).u8((v >> 16) & 0xFF).u8((v >> 8) & 0xFF).u8(v & 0xFF);
        } else {
            o.u8(0x02).u8(0x04).u8((v >> 24) & 0xFF).u8((v >> 16) & 0xFF)
                    .u8((v >> 8) & 0xFF).u8(v & 0xFF);
        }
    }

    private static void berOctetString(Out o, byte[] data) {
        o.u8(0x04);
        berLength(o, data.length);
        o.bytes(data);
    }

    static void berLength(Out o, int len) {
        if (len < 0x80) {
            o.u8(len);
        } else if (len <= 0xFF) {
            o.u8(0x81).u8(len);
        } else if (len <= 0xFFFF) {
            o.u8(0x82).u8((len >> 8) & 0xFF).u8(len & 0xFF);
        } else {
            o.u8(0x83).u8((len >> 16) & 0xFF).u8((len >> 8) & 0xFF).u8(len & 0xFF);
        }
    }

    private static byte[] extractGccUserData(byte[] frame) {
        // 优先按 BER 结构解析：TPKT(4) X224(3) 0x7F len <内容>
        try {
            int p = 7;
            if ((frame[p] & 0xFF) == 0x7F) {
                p++;
                int len = readBerLength(frame, p);
                p += berLengthSize(frame, p);
                int end = Math.min(frame.length, p + len);
                while (p < end) {
                    int tag = frame[p] & 0xFF;
                    int l = readBerLength(frame, p + 1);
                    int lsz = berLengthSize(frame, p + 1);
                    int content = p + 1 + lsz;
                    if (tag == 0x04) {
                        return Bytes.slice(frame, content, Math.min(l, end - content));
                    }
                    p = content + l;
                }
            }
        } catch (RuntimeException ignored) {
            // 落回扫描
        }
        // 兜底：扫描 T.124 OID 之后的内容
        for (int i = 0; i + 7 <= frame.length; i++) {
            if ((frame[i] & 0xFF) == 0x00 && (frame[i + 1] & 0xFF) == 0x05
                    && (frame[i + 2] & 0xFF) == 0x00 && (frame[i + 3] & 0xFF) == 0x14
                    && (frame[i + 4] & 0xFF) == 0x7C) {
                return Bytes.slice(frame, i, frame.length - i);
            }
        }
        return null;
    }

    private static int readBerLength(byte[] b, int p) {
        int v = b[p] & 0xFF;
        if ((v & 0x80) == 0) {
            return v;
        }
        int n = v & 0x7F;
        int r = 0;
        for (int i = 0; i < n; i++) {
            r = (r << 8) | (b[p + 1 + i] & 0xFF);
        }
        return r;
    }

    private static int berLengthSize(byte[] b, int p) {
        int v = b[p] & 0xFF;
        return (v & 0x80) == 0 ? 1 : 1 + (v & 0x7F);
    }

    // ------------------------------------------------------------------

    void erectDomain() throws IOException {
        Out o = new Out(16);
        o.u8(0x03).u8(0x00).u16be(0);
        o.u8(0x02).u8(0xF0).u8(0x80);
        o.u8(DomainMCSPDU_ERECT_DOMAIN_REQUEST << 2);  // 1 << 2 = 0x04
        o.u16be(1);                 // subHeight
        o.u16be(1);                 // subInterval
        o.setU16be(2, o.size());
        send(o.toArray());
    }

    void attachUser() throws IOException, RdpException {
        Out o = new Out(16);
        o.u8(0x03).u8(0x00).u16be(0);
        o.u8(0x02).u8(0xF0).u8(0x80);
        o.u8(DomainMCSPDU_ATTACH_USER_REQUEST << 2);   // 10 << 2 = 0x28
        o.setU16be(2, o.size());
        send(o.toArray());

        byte[] resp = readTpkt();
        if (resp.length < 11 || (resp[7] & 0xFF) >> 2 != DomainMCSPDU_ATTACH_USER_CONFIRM) {
            throw new RdpException("MCS Attach User Confirm 无效（长度 " + resp.length
                    + "，首字节 0x" + (resp.length > 7 ? Integer.toHexString(resp[7] & 0xFF) : "?")
                    + "）");
        }
        int result = resp[8] & 0x03;
        if (result != 0) {
            throw new RdpException("MCS Attach User 被拒绝，result=" + result);
        }
        // initiator 用 PER INTEGER(1001..65536)，线上值是 userId - 1001
        userId = Bytes.u16be(resp, 9) + MCS_BASE_CHANNEL_ID;
        log.log("MCS: Attach User ok, userId=" + userId);
    }

    int joinChannel(int channelId) throws IOException, RdpException {
        Out o = new Out(16);
        o.u8(0x03).u8(0x00).u16be(0);
        o.u8(0x02).u8(0xF0).u8(0x80);
        o.u8(DomainMCSPDU_CHANNEL_JOIN_REQUEST << 2);  // 14 << 2 = 0x38
        o.u16be(userId - MCS_BASE_CHANNEL_ID);
        o.u16be(channelId);
        o.setU16be(2, o.size());
        send(o.toArray());

        byte[] resp = readTpkt();
        if (resp.length < 14
                || (resp[7] & 0xFF) >> 2 != DomainMCSPDU_CHANNEL_JOIN_CONFIRM) {
            throw new RdpException("MCS Channel Join Confirm 无效（channel=" + channelId
                    + "，长度 " + resp.length + "）");
        }
        int result = resp[8] & 0x03;
        if (result != 0) {
            throw new RdpException("MCS Channel Join 被拒绝，channel=" + channelId
                    + " result=" + result);
        }
        return Bytes.u16be(resp, 13);
    }

    /** 加入用户通道、全局通道 1003 与服务器分配的虚拟通道，返回全局通道 ID。 */
    int joinChannels(int[] vcIds) throws IOException, RdpException {
        // 用户通道 ID 就是 Attach User Confirm 返回的 userId（1004 起）
        int userCh = joinChannel(userId);
        log.log("MCS: 加入用户通道 " + userCh);
        int global = joinChannel(MCS_GLOBAL_CHANNEL);
        globalChannelId = global;
        log.log("MCS: 加入全局通道 " + global);
        int[] joined = new int[vcIds.length];
        for (int i = 0; i < vcIds.length; i++) {
            joined[i] = joinChannel(vcIds[i]);
            log.log("MCS: 加入虚拟通道 " + vcIds[i] + " -> " + joined[i]);
        }
        channelIds = joined;
        return global;
    }

    /** 发送一个 MCS Send Data Request（userId 通道）。 */
    void sendData(byte[] userData) throws IOException {
        Out o = new Out(userData.length + 20);
        o.u8(0x03).u8(0x00).u16be(0);
        o.u8(0x02).u8(0xF0).u8(0x80);
        o.u8(DomainMCSPDU_SEND_DATA_REQUEST << 2);  // 25 << 2 = 0x64
        // initiator 必须是客户端自己的 MCS 用户通道 ID（Attach User Confirm 返回的 userId）
        o.u16be(userId);
        o.u16be(globalChannelId);
        o.u8(0x70);                 // dataPriority=3 (bits 6-7)，segmentation=0
        // 与 rdesktop/FreeRDP 一致：固定 2 字节 PER 长度（0x8000 | len）
        o.u16be(0x8000 | (userData.length & 0x7FFF));
        o.bytes(userData);
        o.setU16be(2, o.size());
        send(o.toArray());
    }

    private void send(byte[] frame) throws IOException {
        if ((System.getProperty("rdp.dump") != null)) {
            log.log("MCS 发送 " + frame.length + "B hex=" + Bytes.hex(frame, 0, Math.min(48, frame.length)));
        }
        synchronized (out) {
            out.write(frame);
            out.flush();
        }
    }

    /** 读取一个完整 TPKT 帧（4 字节 TPKT 头 + 3 字节 X.224 + 负载）。 */
    byte[] readTpkt() throws IOException, RdpException {
        byte[] head = Bytes.readFully(in, 4);
        if ((head[0] & 0xFF) != 0x03) {
            throw new RdpException("期望 TPKT（0x03），实际 0x" + Integer.toHexString(head[0] & 0xFF)
                    + "（可能服务器要求标准 RDP 加密或协商失败）");
        }
        int len = Bytes.u16be(head, 2);
        if (len < 7 || len > 65535) {
            throw new RdpException("TPKT 长度非法：" + len + "（头部 "
                    + Bytes.hex(head) + "）");
        }
        byte[] rest = Bytes.readFully(in, len - 4);
        byte[] frame = new byte[len];
        System.arraycopy(head, 0, frame, 0, 4);
        System.arraycopy(rest, 0, frame, 4, rest.length);
        return frame;
    }
}
