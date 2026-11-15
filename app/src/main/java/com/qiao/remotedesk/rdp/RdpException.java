package com.qiao.remotedesk.rdp;

/**
 * 协议层致命异常（带中文消息）。
 * 所有异常消息面向最终用户，尽量给出可操作建议。
 */
public class RdpException extends Exception {
    private static final long serialVersionUID = 1L;

    public RdpException(String message) {
        super(message);
    }

    public RdpException(String message, Throwable cause) {
        super(message, cause);
    }
}
