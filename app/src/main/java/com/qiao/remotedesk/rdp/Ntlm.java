package com.qiao.remotedesk.rdp;

import java.security.MessageDigest;
import java.security.SecureRandom;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * NTLMSSP 客户端实现（NTLMv2 + Extended Session Security）。
 * 仅实现 CredSSP 需要的部分：NEGOTIATE / CHALLENGE 解析 / AUTHENTICATE / 会话密钥 / 密封。
 * 参考 MS-NLMP 与 FreeRDP winpr/libwinpr/sspi/NTLM。
 */
final class Ntlm {

    // 协商标志
    static final int FLAG_UNICODE = 0x00000001;
    static final int FLAG_OEM = 0x00000002;
    static final int FLAG_REQUEST_TARGET = 0x00000004;
    static final int FLAG_SIGN = 0x00000010;
    static final int FLAG_SEAL = 0x00000020;
    static final int FLAG_NTLM = 0x00000200;
    static final int FLAG_ALWAYS_SIGN = 0x00008000;
    static final int FLAG_EXTENDED_SESSIONSECURITY = 0x00080000;
    static final int FLAG_TARGET_INFO = 0x00800000;
    static final int FLAG_VERSION = 0x02000000;
    static final int FLAG_128 = 0x20000000;
    static final int FLAG_KEY_EXCH = 0x40000000;
    static final int FLAG_56 = 0x80000000;

    private static final byte[] SIGN_MAGIC =
            Bytes.ascii("session key to client-to-server signing key magic constant\0");
    private static final byte[] SEAL_MAGIC =
            Bytes.ascii("session key to client-to-server sealing key magic constant\0");
    private static final byte[] SERVER_SIGN_MAGIC =
            Bytes.ascii("session key to server-to-client signing key magic constant\0");
    private static final byte[] SERVER_SEAL_MAGIC =
            Bytes.ascii("session key to server-to-client sealing key magic constant\0");

    private final SecureRandom random = new SecureRandom();

    private int serverFlags;
    private byte[] serverChallenge;
    private byte[] targetInfo = new byte[0];
    private byte[] targetName = new byte[0];

    private byte[] exportedSessionKey;
    private byte[] clientSealingKey;
    private byte[] clientSigningKey;
    private byte[] serverSealingKey;
    private byte[] serverSigningKey;

    private Rc4 sendRc4;
    private Rc4 recvRc4;
    private int sendSeq;
    private int recvSeq;

    // ------------------------------------------------------------------
    // 报文构造
    // ------------------------------------------------------------------

    /** type 1：NEGOTIATE */
    byte[] createNegotiate(String domain, String workstation) {
        int flags = FLAG_UNICODE | FLAG_OEM | FLAG_REQUEST_TARGET | FLAG_SIGN | FLAG_SEAL
                | FLAG_NTLM | FLAG_ALWAYS_SIGN | FLAG_EXTENDED_SESSIONSECURITY
                | FLAG_TARGET_INFO | FLAG_VERSION | FLAG_128 | FLAG_KEY_EXCH | FLAG_56;

        byte[] dom = domain == null ? new byte[0] : Bytes.utf16(domain);
        byte[] ws = workstation == null ? new byte[0] : Bytes.utf16(workstation);

        Out o = new Out(64 + dom.length + ws.length);
        o.ascii("NTLMSSP").u8(0).u32(1);
        o.u32(flags);

        int payloadOffset = 40; // 12 固定 + 8 域名 + 8 主机名 + 8 版本 + 4 flags
        o.u16(dom.length).u16(dom.length).u32(payloadOffset);
        o.u16(ws.length).u16(ws.length).u32(payloadOffset + dom.length);
        version(o);
        o.bytes(dom).bytes(ws);
        return o.toArray();
    }

    private static void version(Out o) {
        o.u8(10).u8(0);          // Windows 10
        o.u16(19041);            // Build
        o.zeros(3);
        o.u8(15);                // NTLMSSP_REVISION_W2K3
    }

    /** 解析 type 2：CHALLENGE */
    void parseChallenge(byte[] msg) throws RdpException {
        if (msg.length < 32 || !isNtlm(msg, 2)) {
            throw new RdpException("NTLM CHALLENGE 报文无效（长度 " + msg.length + "）");
        }
        serverFlags = Bytes.s32(msg, 20);
        serverChallenge = Bytes.slice(msg, 24, 8);
        targetName = field(msg, 12);
        targetInfo = field(msg, 40);
        if ((serverFlags & FLAG_EXTENDED_SESSIONSECURITY) == 0) {
            throw new RdpException("服务器未启用 NTLM 扩展会话安全（Extended Session Security），暂不支持");
        }
        if (targetInfo == null) {
            targetInfo = new byte[0];
        }
    }

    private static boolean isNtlm(byte[] b, int type) {
        return b.length >= 12 && b[0] == 'N' && b[1] == 'T' && b[2] == 'L' && b[3] == 'M'
                && Bytes.s32(b, 8) == type;
    }

    /** 读 8 字节安全缓冲字段（len/maxlen/offset）指向的数据。 */
    private static byte[] field(byte[] msg, int off) {
        if (off + 8 > msg.length) {
            return new byte[0];
        }
        int len = Bytes.u16(msg, off);
        int dataOff = (int) Bytes.u32(msg, off + 4);
        if (len <= 0 || dataOff < 0 || dataOff + len > msg.length) {
            return new byte[0];
        }
        return Bytes.slice(msg, dataOff, len);
    }

    /** type 3：AUTHENTICATE，返回报文并派生会话密钥。 */
    byte[] createAuthenticate(String domain, String user, String password) throws RdpException {
        if (serverChallenge == null) {
            throw new RdpException("NTLM 内部错误：尚未收到 CHALLENGE");
        }
        String dom = domain == null ? "" : domain;
        String usr = user == null ? "" : user;
        String pwd = password == null ? "" : password;

        byte[] ntowfv2 = hmacMd5(md4(Bytes.utf16(pwd)), Bytes.utf16(upcase(usr) + dom));

        byte[] clientChallenge = new byte[8];
        random.nextBytes(clientChallenge);

        byte[] timestamp = findTimestamp();
        if (timestamp == null) {
            timestamp = fileTime(System.currentTimeMillis());
        }

        // temp = 01010000 00000000 | timestamp | clientChallenge | 00000000 | targetInfo
        Out temp = new Out(32 + targetInfo.length);
        temp.u8(1).u8(1).u16(0).u32(0);
        temp.bytes(timestamp);
        temp.bytes(clientChallenge);
        temp.u32(0);
        temp.bytes(targetInfo);

        byte[] proof = hmacMd5(ntowfv2, Bytes.concat(serverChallenge, temp.toArray()));
        byte[] ntResponse = Bytes.concat(proof, temp.toArray());

        byte[] lmProof = hmacMd5(ntowfv2, Bytes.concat(serverChallenge, clientChallenge));
        byte[] lmResponse = Bytes.concat(lmProof, clientChallenge);

        byte[] sessionBaseKey = hmacMd5(ntowfv2, proof);
        boolean keyExch = (serverFlags & FLAG_KEY_EXCH) != 0;
        byte[] encryptedSessionKey;
        if (keyExch) {
            byte[] randomSessionKey = new byte[16];
            random.nextBytes(randomSessionKey);
            encryptedSessionKey = Rc4.rc4k(sessionBaseKey, randomSessionKey);
            exportedSessionKey = randomSessionKey;
        } else {
            // 未协商密钥交换时导出密钥即会话基密钥（MS-NLMP 3.4.5.1）
            encryptedSessionKey = new byte[0];
            exportedSessionKey = sessionBaseKey;
        }
        clientSigningKey = md5Concat(exportedSessionKey, SIGN_MAGIC);
        clientSealingKey = md5Concat(exportedSessionKey, SEAL_MAGIC);
        serverSigningKey = md5Concat(exportedSessionKey, SERVER_SIGN_MAGIC);
        serverSealingKey = md5Concat(exportedSessionKey, SERVER_SEAL_MAGIC);
        sendRc4 = new Rc4(clientSealingKey);
        recvRc4 = new Rc4(serverSealingKey);
        sendSeq = 0;
        recvSeq = 0;

        byte[] domBytes = Bytes.utf16(dom);
        byte[] userBytes = Bytes.utf16(usr);
        byte[] wsBytes = Bytes.utf16(hostName());

        boolean withVersion = (serverFlags & FLAG_VERSION) != 0;
        int headerLen = 64 + (withVersion ? 8 : 0);

        int off = headerLen;
        int lmOff = off;
        off += lmResponse.length;
        int ntOff = off;
        off += ntResponse.length;
        int domOff = off;
        off += domBytes.length;
        int userOff = off;
        off += userBytes.length;
        int wsOff = off;
        off += wsBytes.length;
        int keyOff = off;
        off += encryptedSessionKey.length;

        int flags = serverFlags | FLAG_UNICODE | FLAG_NTLM;
        flags &= ~FLAG_OEM;
        if (!keyExch) {
            flags &= ~FLAG_KEY_EXCH;
        }

        Out o = new Out(off);
        o.ascii("NTLMSSP").u8(0).u32(3);
        o.u16(lmResponse.length).u16(lmResponse.length).u32(lmOff);
        o.u16(ntResponse.length).u16(ntResponse.length).u32(ntOff);
        o.u16(domBytes.length).u16(domBytes.length).u32(domOff);
        o.u16(userBytes.length).u16(userBytes.length).u32(userOff);
        o.u16(wsBytes.length).u16(wsBytes.length).u32(wsOff);
        o.u16(encryptedSessionKey.length).u16(encryptedSessionKey.length).u32(keyOff);
        o.u32(flags);
        if (withVersion) {
            version(o);
        }
        o.bytes(lmResponse).bytes(ntResponse).bytes(domBytes).bytes(userBytes).bytes(wsBytes)
                .bytes(encryptedSessionKey);
        return o.toArray();
    }

    private static String hostName() {
        String h = System.getenv("COMPUTERNAME");
        if (h == null || h.isEmpty()) {
            h = "ANDROID";
        }
        return upcase(h.length() > 15 ? h.substring(0, 15) : h);
    }

    private static String upcase(String s) {
        // NTLM 只对 ASCII 做大小写折叠，避免土耳其语 I 等问题
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            sb.append(c >= 'a' && c <= 'z' ? (char) (c - 32) : c);
        }
        return sb.toString();
    }

    private byte[] findTimestamp() {
        int p = 0;
        while (p + 4 <= targetInfo.length) {
            int id = Bytes.u16(targetInfo, p);
            int len = Bytes.u16(targetInfo, p + 2);
            p += 4;
            if (id == 0) {
                break;
            }
            if (p + len > targetInfo.length) {
                break;
            }
            if (id == 7 && len == 8) { // MsvAvTimestamp
                return Bytes.slice(targetInfo, p, 8);
            }
            p += len;
        }
        return null;
    }

    private static byte[] fileTime(long millis) {
        long ft = (millis + 11644473600000L) * 10000L;
        byte[] b = new byte[8];
        for (int i = 0; i < 8; i++) {
            b[i] = (byte) (ft >>> (8 * i));
        }
        return b;
    }

    // ------------------------------------------------------------------
    // 完整性 / 机密性：NTLM 密封（CredSSP 的 pubKeyAuth 与 authInfo 都用它）
    // ------------------------------------------------------------------

    /** SEAL(seq, data) → signature(16) || RC4(data)。 */
    byte[] seal(byte[] data) {
        byte[] digest = hmacMd5(clientSigningKey, Bytes.concat(le32(sendSeq), data));
        byte[] cipher = new byte[data.length];
        sendRc4.process(data, 0, data.length, cipher, 0);
        byte[] sig8 = new byte[8];
        sendRc4.process(digest, 0, 8, sig8, 0);
        byte[] sig = new byte[16];
        System.arraycopy(le32(1), 0, sig, 0, 4);
        System.arraycopy(sig8, 0, sig, 4, 8);
        System.arraycopy(le32(sendSeq), 0, sig, 12, 4);
        sendSeq++;
        return Bytes.concat(sig, cipher);
    }

    /** UNSEAL(signature(16) || RC4(data)) → data；签名不匹配返回 null。 */
    byte[] unseal(byte[] blob) {
        if (blob.length < 16) {
            return null;
        }
        byte[] cipher = Bytes.slice(blob, 16, blob.length - 16);
        byte[] plain = new byte[cipher.length];
        recvRc4.process(cipher, 0, cipher.length, plain, 0);
        byte[] expected = hmacMd5(serverSigningKey, Bytes.concat(le32(recvSeq), plain));
        byte[] sig8 = Bytes.slice(blob, 4, 8);
        byte[] check = new byte[8];
        recvRc4.process(expected, 0, 8, check, 0);
        recvSeq++;
        boolean ok = true;
        for (int i = 0; i < 8; i++) {
            if (sig8[i] != check[i]) {
                ok = false;
            }
        }
        return ok ? plain : null;
    }
    private static byte[] le32(int v) {
        return new byte[]{(byte) v, (byte) (v >>> 8), (byte) (v >>> 16), (byte) (v >>> 24)};
    }

    private static byte[] md5Concat(byte[] key, byte[] magic) {
        try {
            MessageDigest md = MessageDigest.getInstance("MD5");
            md.update(key);
            md.update(magic);
            return md.digest();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] md4(byte[] data) {
        return Md4.digest(data);
    }

    static byte[] hmacMd5(byte[] key, byte[] data) {
        try {
            Mac mac = Mac.getInstance("HmacMD5");
            mac.init(new SecretKeySpec(key, "HmacMD5"));
            return mac.doFinal(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
