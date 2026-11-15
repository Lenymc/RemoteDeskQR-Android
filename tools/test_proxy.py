"""验证用的本地代理服务器（HTTP CONNECT / SOCKS5 / SOCKS4）。

仅用于开发期验证 app 的代理连接器实现，不是产品代码。
监听 0.0.0.0，分别提供：
    HTTP CONNECT : 18080
    SOCKS5       : 11080  (无认证 / 用户名密码均可)
    SOCKS4       : 11081
每个请求会把目标与转发字节数打印到 stdout，便于确认流量确实经过代理。
"""
import socket
import select
import os
import struct
import threading
import time
import sys

BIND = "0.0.0.0"
HTTP_PORT = 18080
SOCKS5_PORT = 11080
SOCKS4_PORT = 11081
LOG_LOCK = threading.Lock()

# 可选测试替身：把请求中的目标映射到另一台可达主机，用于开发期联调。
# 通过环境变量配置，避免把任何真实网络拓扑写进仓库：
#   QRDP_TEST_MAP="from_host:from_port=to_host:to_port"
# 例：QRDP_TEST_MAP="192.0.2.10:3389=127.0.0.1:3389"
def load_target_map():
    spec = os.environ.get("QRDP_TEST_MAP", "").strip()
    if not spec:
        return {}
    try:
        left, right = spec.split("=", 1)
        from_host, from_port = left.rsplit(":", 1)
        to_host, to_port = right.rsplit(":", 1)
        return {(from_host, int(from_port)): (to_host, int(to_port))}
    except ValueError:
        print("QRDP_TEST_MAP 格式应为 from_host:from_port=to_host:to_port", flush=True)
        return {}


TARGET_MAP = load_target_map()


def log(msg):
    with LOG_LOCK:
        print("[%s] %s" % (time.strftime("%H:%M:%S"), msg), flush=True)


def pump(a, b, tag):
    """全双工转发，返回 (上行, 下行) 字节数。"""
    total = [0, 0]
    try:
        while True:
            r, _, _ = select.select([a, b], [], [], 30)
            if not r:
                break
            for s in r:
                data = s.recv(65536)
                if not data:
                    return tuple(total)
                if s is a:
                    b.sendall(data)
                    total[0] += len(data)
                else:
                    a.sendall(data)
                    total[1] += len(data)
    except OSError:
        pass
    return tuple(total)


def connect_target(host, port):
    mapped = TARGET_MAP.get((host, port))
    if mapped:
        log("  [测试映射] %s:%s -> %s:%s" % (host, port, mapped[0], mapped[1]))
        host, port = mapped
    s = socket.create_connection((host, port), 5)
    s.settimeout(0)
    return s


def handle_http(conn, addr):
    try:
        buf = b""
        while b"\r\n\r\n" not in buf and len(buf) < 65536:
            chunk = conn.recv(4096)
            if not chunk:
                return
            buf += chunk
        head, rest = buf.split(b"\r\n\r\n", 1)
        lines = head.decode("iso-8859-1").split("\r\n")
        parts = lines[0].split()
        if len(parts) < 2 or parts[0].upper() != "CONNECT":
            conn.sendall(b"HTTP/1.1 405 Method Not Allowed\r\n\r\n")
            return
        authority = parts[1]
        host, port = authority.rsplit(":", 1)
        auth = next((l for l in lines[1:] if l.lower().startswith("proxy-authorization:")), None)
        log("HTTP CONNECT %s:%s auth=%s from=%s" % (host, port, "yes" if auth else "no", addr[0]))
        try:
            up = connect_target(host, int(port))
        except OSError as e:
            conn.sendall(b"HTTP/1.1 502 Bad Gateway\r\n\r\n")
            log("  -> 目标不可达: %s" % e)
            return
        conn.sendall(b"HTTP/1.1 200 Connection Established\r\n\r\n")
        if rest:
            up.sendall(rest)
        a, b = pump(conn, up, "http")
        log("  -> 转发完成 上行=%d 下行=%d" % (a, b))
        up.close()
    except Exception as e:
        log("HTTP 异常: %r" % e)
    finally:
        conn.close()


def handle_socks5(conn, addr):
    try:
        def rd(n):
            out = b""
            while len(out) < n:
                c = conn.recv(n - len(out))
                if not c:
                    raise OSError("eof")
                out += c
            return out

        ver, nm = rd(2)
        methods = rd(nm)
        if ver != 5:
            return
        has_user = b"\x02" in methods
        if has_user:
            conn.sendall(b"\x05\x02")
        elif b"\x00" in methods:
            conn.sendall(b"\x05\x00")
        else:
            conn.sendall(b"\x05\xff")
            return
        if has_user:
            subver = rd(1)[0]
            ulen = rd(1)[0]
            user = rd(ulen)
            plen = rd(1)[0]
            pwd = rd(plen)
            log("SOCKS5 认证 user=%s from=%s" % (user.decode(errors="replace"), addr[0]))
            conn.sendall(b"\x01\x00")
        head = rd(4)
        ver, cmd, _, atyp = head
        if atyp == 1:
            host = socket.inet_ntoa(rd(4))
        elif atyp == 3:
            host = rd(rd(1)[0]).decode(errors="replace")
        elif atyp == 4:
            host = socket.inet_ntop(socket.AF_INET6, rd(16))
        else:
            conn.sendall(b"\x05\x01\x00\x01" + b"\x00" * 6)
            return
        port = struct.unpack(">H", rd(2))[0]
        log("SOCKS5 CONNECT %s:%s from=%s" % (host, port, addr[0]))
        try:
            up = connect_target(host, port)
        except OSError as e:
            conn.sendall(b"\x05\x01\x00\x01" + b"\x00" * 6)
            log("  -> 目标不可达: %s" % e)
            return
        lb = up.getsockname()
        conn.sendall(b"\x05\x00\x00\x01" + socket.inet_aton(lb[0]) + struct.pack(">H", lb[1]))
        a, b = pump(conn, up, "socks5")
        log("  -> 转发完成 上行=%d 下行=%d" % (a, b))
        up.close()
    except Exception as e:
        log("SOCKS5 异常: %r" % e)
    finally:
        conn.close()


def handle_socks4(conn, addr):
    try:
        def rd(n):
            out = b""
            while len(out) < n:
                c = conn.recv(n - len(out))
                if not c:
                    raise OSError("eof")
                out += c
            return out

        head = rd(8)
        ver, cmd = head[0], head[1]
        port = struct.unpack(">H", head[2:4])[0]
        ip = socket.inet_ntoa(head[4:8])
        user = b""
        while True:
            c = rd(1)
            if c == b"\x00":
                break
            user += c
        host = ip
        if ip.startswith("0.0.0.") and ip != "0.0.0.0":
            host = b""
            while True:
                c = rd(1)
                if c == b"\x00":
                    break
                host += c
            host = host.decode(errors="replace")
        log("SOCKS4 CONNECT %s:%s user=%s from=%s"
            % (host, port, user.decode(errors="replace"), addr[0]))
        try:
            up = connect_target(host, port)
        except OSError as e:
            conn.sendall(b"\x00\x5b" + b"\x00" * 6)
            log("  -> 目标不可达: %s" % e)
            return
        conn.sendall(b"\x00\x5a" + b"\x00" * 6)
        a, b = pump(conn, up, "socks4")
        log("  -> 转发完成 上行=%d 下行=%d" % (a, b))
        up.close()
    except Exception as e:
        log("SOCKS4 异常: %r" % e)
    finally:
        conn.close()


def serve(port, handler, name):
    s = socket.socket()
    s.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    s.bind((BIND, port))
    s.listen(20)
    log("%s 监听 %s:%d" % (name, BIND, port))
    while True:
        c, a = s.accept()
        threading.Thread(target=handler, args=(c, a), daemon=True).start()


def main():
    for p, h, n in ((HTTP_PORT, handle_http, "HTTP-CONNECT"),
                    (SOCKS5_PORT, handle_socks5, "SOCKS5"),
                    (SOCKS4_PORT, handle_socks4, "SOCKS4")):
        threading.Thread(target=serve, args=(p, h, n), daemon=True).start()
    log("代理服务已就绪，Ctrl+C 退出")
    try:
        while True:
            time.sleep(1)
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
