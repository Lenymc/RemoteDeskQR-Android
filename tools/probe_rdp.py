"""探测 RDP 服务器的 X.224 协商能力（只发 X.224 Connection Request，不进行认证）。

用法: python tools/probe_rdp.py <host> [port]
"""
import socket
import struct
import sys


def x224_neg_req(requested):
    # TPKT + X.224 CR + RDP Negotiation Request
    x224 = bytes([0x0E, 0xE0, 0x00, 0x00, 0x00, 0x00, 0x00])
    neg = struct.pack("<BBHI", 0x01, 0x00, 0x0008, requested)
    body = x224 + neg
    return struct.pack(">BBH", 0x03, 0x00, len(body) + 4) + body


PROTOS = {
    0x00000000: "标准 RDP 安全层（不发协商请求）",
    0x00000001: "SSL/TLS",
    0x00000002: "CredSSP/NLA (HYBRID)",
    0x00000003: "SSL | HYBRID",
    0x00000008: "HYBRID_EX",
    0x0000000B: "SSL | HYBRID | RDP",
}


def probe(host, port, requested):
    s = socket.create_connection((host, port), timeout=6)
    s.sendall(x224_neg_req(requested))
    data = s.recv(1024)
    s.close()
    return data


def parse(data):
    if len(data) < 11:
        return "响应过短: %s" % data.hex()
    # TPKT(4) + X.224 CC(7) ...
    off = 4
    li = data[off]
    total = off + 1 + li + 1  # +1 for TPKT header start
    if len(data) < total + 8:
        return "无协商应答（服务器按传统 RDP 安全层应答）: %s" % data.hex()
    tag = data[total]
    flags = data[total + 1]
    length = struct.unpack("<H", data[total + 2:total + 4])[0]
    if tag == 0x02:  # RDP_NEG_RSP
        sel = struct.unpack("<I", data[total + 4:total + 8])[0]
        return "RDP_NEG_RSP 选中协议 = 0x%08X (%s)" % (sel, PROTOS.get(sel, "未知"))
    if tag == 0x03:  # RDP_NEG_FAILURE
        code = struct.unpack("<I", data[total + 4:total + 8])[0]
        names = {
            1: "SSL_REQUIRED_BY_SERVER（服务器要求 SSL/NLA）",
            2: "SSL_NOT_ALLOWED_BY_SERVER（服务器只允许传统 RDP 安全层）",
            3: "SSL_CERT_NOT_ON_SERVER",
            4: "INCONSISTENT_FLAGS",
            5: "HYBRID_REQUIRED_BY_SERVER",
            6: "SSL_WITH_USER_AUTH_REQUIRED_BY_SERVER",
        }
        return "RDP_NEG_FAILURE 0x%08X (%s)" % (code, names.get(code, "未知"))
    return "未知应答 tag=0x%02X: %s" % (tag, data.hex())


def main():
    host = sys.argv[1]
    port = int(sys.argv[2]) if len(sys.argv) > 2 else 3389
    for req in (0x0B, 0x03, 0x01, 0x02):
        try:
            resp = probe(host, port, req)
            print("%-28s -> %s" % (PROTOS[req], parse(resp)))
        except Exception as e:  # noqa: BLE001
            print("%-28s -> 失败: %s" % (PROTOS[req], e))


main()
