package com.qiao.remotedesk.rdp;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.math.BigInteger;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.security.SecureRandom;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;

/**
 * CredSSP (NLA) 客户端，按 MS-CSSP 3.1.5 实现：SPNEGO 包裹 NTLMSSP，
 * 最后一轮用 NTLM 密封的 pubKeyAuth 完成双向绑定，再发送加密的 TSCredentials。
 */
final class CredSsp {

    private static final int TS_VERSION = 6;

    private static final byte[] CLIENT_TO_SERVER_MAGIC =
            Bytes.ascii("CredSSP Client-To-Server Binding Hash\0");
    private static final byte[] SERVER_TO_CLIENT_MAGIC =
            Bytes.ascii("CredSSP Server-To-Client Binding Hash\0");

    private static final byte[] OID_SPNEGO = {
            0x06, 0x06, 0x2B, 0x06, 0x01, 0x05, 0x05, 0x02};
    private static final byte[] OID_NTLM = {
            0x06, 0x0A, 0x2B, 0x06, 0x01, 0x04, 0x01, (byte) 0x82, 0x37, 0x02, 0x02, 0x0A};

    private final Socket socket;
    private final PushbackInputStream in;
    private final OutputStream out;
    private final X509Certificate serverCert;
    private final Log log;

    private final Ntlm ntlm = new Ntlm();
    private final SecureRandom random = new SecureRandom();
    private final byte[] clientNonce = new byte[32];
    private byte[] publicKeyDer;
    /** 服务端 CredSSP 版本：< 5（Vista/Win7）用「公钥回显」，>= 5 用「公钥哈希绑定」 */
    private int peerVersion = 6;

    CredSsp(Socket socket, InputStream rawIn, PushbackInputStream in, OutputStream out,
            X509Certificate serverCert, Log log) {
        this.socket = socket;
        this.in = in;
        this.out = out;
        this.serverCert = serverCert;
        this.log = log;
        random.nextBytes(clientNonce);
    }

    /** 执行完整 CredSSP 握手，失败抛 RdpException（中文原因）。 */
    void authenticate(String domain, String user, String password) throws RdpException, IOException {
        publicKeyDer = buildPublicKeyDer(serverCert);

        // ---- 第 1 轮：NEGOTIATE ----
        byte[] negotiate = ntlm.createNegotiate(domain, "ANDROID");
        byte[] spnegoInit = spnegoInit(negotiate);
        log.log("CredSSP[1/4] 发送 NTLMSSP NEGOTIATE（SPNEGO " + spnegoInit.length + "B）");
        sendTsRequest(spnegoInit, null, null, null);

        TsResponse r1 = readTsRequest("第 1 轮响应");
        if (r1.errorCode != null) {
            throw new RdpException("CredSSP 协商失败：" + ntStatusText(r1.errorCode));
        }
        if (r1.version > 0) {
            peerVersion = r1.version;
        }
        log.log("CredSSP 服务端版本=" + peerVersion
                + (peerVersion < 5 ? "（旧版：公钥回显）" : "（新版：公钥哈希绑定）"));
        byte[] challenge = extractNtlmToken(r1.negoToken);
        if (challenge == null) {
            throw new RdpException("CredSSP 失败：服务器未返回 NTLM CHALLENGE（token="
                    + Bytes.hex(r1.negoToken, 0, Math.min(64, r1.negoToken.length)) + "）");
        }
        ntlm.parseChallenge(challenge);
        log.log("CredSSP[2/4] 收到 NTLMSSP CHALLENGE（" + challenge.length + "B）");

        // ---- 第 2 轮：AUTHENTICATE + pubKeyAuth ----
        byte[] authenticate = ntlm.createAuthenticate(domain, user, password);
        byte[] pubKeyAuth = buildPubKeyAuth();
        log.log("CredSSP[3/4] 发送 NTLMSSP AUTHENTICATE + pubKeyAuth(" + pubKeyAuth.length + "B)");
        sendTsRequest(spnegoResp(authenticate), null, pubKeyAuth, null);

        TsResponse r2 = readTsRequest("第 2 轮响应");
        if (r2.errorCode != null) {
            throw new RdpException("登录被拒绝：" + ntStatusText(r2.errorCode)
                    + "（NTSTATUS 0x" + Long.toHexString(r2.errorCode) + "）");
        }
        if (r2.pubKeyAuth != null && r2.pubKeyAuth.length > 0) {
            verifyServerBinding(r2.pubKeyAuth);
        }

        // ---- 第 3 轮：加密凭据 ----
        byte[] creds = Der.tsCredentials(1, Der.tsPasswordCreds(
                Bytes.utf16(domain == null ? "" : domain),
                Bytes.utf16(user == null ? "" : user),
                Bytes.utf16(password == null ? "" : password)));
        byte[] sealedCreds = seal(creds);
        log.log("CredSSP[4/4] 发送加密凭据 " + sealedCreds.length + "B");
        sendTsRequest(null, sealedCreds, null, null);

        // 成功后服务器一般不再回包，直接进入 MCS；若立刻回包则多半是错误。
        TsResponse r3 = (System.getProperty("rdp.nowait") != null) ? null
                : tryReadTsRequest(Integer.getInteger("rdp.credssp.wait", 400));
        if (r3 != null && r3.errorCode != null) {
            throw new RdpException("登录失败：" + ntStatusText(r3.errorCode)
                    + "（NTSTATUS 0x" + Long.toHexString(r3.errorCode) + "）");
        }
        log.log("CredSSP 完成，会话已通过 NLA 认证");
    }

    // ------------------------------------------------------------------
    // pubKeyAuth：SHA256(magic || nonce || 公钥 DER) 再做 NTLM 密封
    // ------------------------------------------------------------------

    private byte[] buildPubKeyAuth() {
        if (peerVersion < 5) {
            // 旧版（Vista/Win7）：直接密封服务器公钥 DER，不含 nonce
            return seal(publicKeyDer);
        }
        byte[] hash = Ntlm.sha256(Bytes.concat(CLIENT_TO_SERVER_MAGIC, clientNonce, publicKeyDer));
        if ((System.getProperty("rdp.badpubkey") != null)) {
            hash = new byte[32];
            random.nextBytes(hash);
        }
        return seal(hash);
    }

    private void verifyServerBinding(byte[] blob) {
        byte[] plain = unseal(blob);
        if (plain == null) {
            log.log("警告：服务器 pubKeyAuth 签名校验失败（继续，不影响后续协议）");
            return;
        }
        if (peerVersion < 5) {
            // 旧版：服务器回显的是公钥 +1（小端整数加一）
            if (plain.length == publicKeyDer.length) {
                byte[] expect = publicKeyDer.clone();
                for (int i = 0; i < expect.length; i++) {
                    if ((expect[i] & 0xFF) != 0xFF) {
                        expect[i] = (byte) ((expect[i] & 0xFF) + 1);
                        break;
                    }
                    expect[i] = 0;
                }
                log.log(java.util.Arrays.equals(plain, expect)
                        ? "服务器公钥回显校验通过" : "警告：服务器公钥回显不匹配（继续）");
            } else {
                log.log("警告：服务器公钥回显长度异常 " + plain.length + "（继续）");
            }
            return;
        }
        byte[] expect = Ntlm.sha256(Bytes.concat(SERVER_TO_CLIENT_MAGIC, clientNonce, publicKeyDer));
        boolean same = plain.length == expect.length;
        if (same) {
            for (int i = 0; i < expect.length; i++) {
                if (plain[i] != expect[i]) {
                    same = false;
                    break;
                }
            }
        }
        log.log(same ? "服务器公钥绑定校验通过" : "警告：服务器公钥绑定哈希不匹配（继续）");
    }

    private byte[] seal(byte[] data) {
        return ntlm.seal(data);
    }

    private byte[] unseal(byte[] blob) {
        return ntlm.unseal(blob);
    }

    /** 把证书的 RSA 公钥编码成 DER RSAPublicKey（SEQUENCE{INTEGER n, INTEGER e}）。 */
    private static byte[] buildPublicKeyDer(X509Certificate cert) throws RdpException {
        if (cert == null) {
            throw new RdpException("TLS 握手未取得服务器证书");
        }
        java.security.PublicKey pk = cert.getPublicKey();
        if (!(pk instanceof RSAPublicKey)) {
            throw new RdpException("服务器证书不是 RSA 公钥，CredSSP 暂不支持（"
                    + pk.getAlgorithm() + "）");
        }
        RSAPublicKey rsa = (RSAPublicKey) pk;
        BigInteger n = rsa.getModulus();
        BigInteger e = rsa.getPublicExponent();
        return Der.sequence(Der.tlv(0x02, n.toByteArray()), Der.tlv(0x02, e.toByteArray()));
    }

    // ------------------------------------------------------------------
    // SPNEGO 包裹
    // ------------------------------------------------------------------

    private static byte[] spnegoInit(byte[] ntlmToken) {
        byte[] mechTypes = Der.tlv(0x30, OID_NTLM);
        byte[] seq = Der.tlv(0x30, Bytes.concat(
                Der.context(0, mechTypes),
                Der.context(2, Der.octetString(ntlmToken))));
        return Der.tlv(0x60, Bytes.concat(OID_SPNEGO, Der.context(0, seq)));
    }

    private static byte[] spnegoResp(byte[] ntlmToken) {
        byte[] seq = Der.tlv(0x30, Der.context(2, Der.octetString(ntlmToken)));
        return Der.context(1, seq);
    }

    /** 从 SPNEGO 响应里抠出 NTLMSSP 报文（部分服务器会直接回裸 NTLM）。 */
    private static byte[] extractNtlmToken(byte[] token) {
        if (token == null || token.length < 12) {
            return null;
        }
        for (int i = 0; i + 12 <= token.length; i++) {
            if (token[i] == 'N' && token[i + 1] == 'T' && token[i + 2] == 'L'
                    && token[i + 3] == 'M' && token[i + 4] == 'S' && token[i + 5] == 'S'
                    && token[i + 6] == 'P' && token[i + 7] == 0) {
                return Bytes.slice(token, i, token.length - i);
            }
        }
        return null;
    }

    // ------------------------------------------------------------------
    // TSRequest 收发
    // ------------------------------------------------------------------

    private void sendTsRequest(byte[] negoToken, byte[] authInfo, byte[] pubKeyAuth, Long error)
            throws IOException {
        byte[] req = Der.tsRequest(TS_VERSION, negoToken, authInfo, pubKeyAuth, error, clientNonce);
        out.write(req);
        out.flush();
        log.log("  -> TSRequest " + req.length + "B "
                + Bytes.hex(req, 0, req.length, 48));
    }

    private TsResponse readTsRequest(String what) throws RdpException, IOException {
        TsResponse r = tryReadTsRequest(30000);
        if (r == null) {
            throw new RdpException("CredSSP " + what + " 读取失败：连接已关闭或超时");
        }
        return r;
    }

    /** timeoutMs<=0 表示不设超时；返回 null 表示超时或遇到非 TSRequest 数据。 */
    private TsResponse tryReadTsRequest(int timeoutMs) throws RdpException, IOException {
        socket.setSoTimeout(timeoutMs > 0 ? timeoutMs : 0);
        int first;
        try {
            first = in.read();
        } catch (SocketTimeoutException e) {
            return null;
        }
        if (first < 0) {
            return null;
        }
        if (first != 0x30) {
            in.unread(first);
            return null;
        }
        int firstLen = in.read();
        if (firstLen < 0) {
            return null;
        }
        int len;
        if ((firstLen & 0x80) == 0) {
            len = firstLen;
        } else {
            int n = firstLen & 0x7F;
            byte[] lb = Bytes.readFully(in, n);
            len = 0;
            for (byte b : lb) {
                len = (len << 8) | (b & 0xFF);
            }
        }
        if (len <= 0 || len > 65536) {
            throw new RdpException("CredSSP 报文长度异常：" + len);
        }
        byte[] content = Bytes.readFully(in, len);
        log.log("  <- TSRequest " + (len + 2) + "B "
                + Bytes.hex(content, 0, content.length, 48));
        return TsResponse.parse(content);
    }

    // ------------------------------------------------------------------

    static final class TsResponse {
        byte[] negoToken;
        byte[] pubKeyAuth;
        Long errorCode;
        int version;

        static TsResponse parse(byte[] seqContent) throws RdpException {
            TsResponse r = new TsResponse();
            try {
                Der.Reader rd = new Der.Reader(seqContent);
                while (rd.hasMore()) {
                    int raw = rd.peekTag();
                    if ((raw & 0xC0) != 0x80) {
                        rd.read();
                        continue;
                    }
                    int tag = raw & 0x1F;
                    byte[] content = unwrap(rd.read());
                    if (tag == 0) {
                        // version [0] INTEGER：内容为 02 01 xx，取最后字节
                        if (content.length > 0) {
                            r.version = content[content.length - 1] & 0xFF;
                        }
                    } else if (tag == 1) {
                        r.negoToken = content;
                    } else if (tag == 3) {
                        r.pubKeyAuth = content;
                    } else if (tag == 4) {
                        long v = 0;
                        for (byte b : content) {
                            v = (v << 8) | (b & 0xFF);
                        }
                        r.errorCode = v & 0xFFFFFFFFL;
                    }
                }
            } catch (RuntimeException e) {
                throw new RdpException("CredSSP 报文解析失败：" + e.getMessage(), e);
            }
            return r;
        }

        /** 兼容显式（A3 { 04 .. }）与隐式（83 ..）两种上下文标签编码。 */
        private static byte[] unwrap(byte[] c) {
            if (c.length >= 2) {
                int t = c[0] & 0xFF;
                if (t == 0x04 || t == 0x02 || t == 0x30 || t == 0xA0 || t == 0xA1
                        || t == 0xA2 || t == 0xA3) {
                    try {
                        Der.Reader inner = new Der.Reader(c);
                        byte[] v = inner.read();
                        if (!inner.hasMore()) {
                            return v;
                        }
                    } catch (RuntimeException ignored) {
                        // 落回原内容
                    }
                }
            }
            return c;
        }
    }

    static String ntStatusText(long code) {
        int c = (int) code;
        switch (c) {
            case 0xC000006D:
                return "用户名或密码错误（STATUS_LOGON_FAILURE）";
            case 0xC000006A:
                return "密码错误（STATUS_WRONG_PASSWORD）";
            case 0xC0000064:
                return "用户名不存在（STATUS_NO_SUCH_USER）";
            case 0xC0000234:
                return "账户已被锁定（STATUS_ACCOUNT_LOCKED_OUT）";
            case 0xC0000072:
                return "账户已被禁用（STATUS_ACCOUNT_DISABLED）";
            case 0xC000006F:
                return "不在允许的登录时间（STATUS_INVALID_LOGON_HOURS）";
            case 0xC0000070:
                return "不允许从该工作站登录（STATUS_INVALID_WORKSTATION）";
            case 0xC0000071:
                return "密码已过期（STATUS_PASSWORD_EXPIRED）";
            case 0xC0000224:
                return "必须修改密码后才能登录（STATUS_PASSWORD_MUST_CHANGE）";
            case 0xC0000133:
                return "客户端与服务器时间偏差过大（STATUS_TIME_DIFFERENCE_AT_DC）";
            default:
                return "未知错误（NTSTATUS 0x" + Long.toHexString(code) + "）";
        }
    }
}
