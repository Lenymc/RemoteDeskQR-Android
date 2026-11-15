package com.qiao.remotedesk.rdp;

/** 内部日志回调（不暴露给 UI，RdpSession 会转发到 Listener.onLog）。 */
interface Log {
    void log(String message);
}
