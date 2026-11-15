package com.qiao.remotedesk.rdp;

/** FastPath 输出解析 + 输入事件编码（MS-RDPBCGR 2.2.9）。 */
final class FastPath {

    // 输出更新类型
    static final int UPDATETYPE_ORDERS = 0x00;
    static final int UPDATETYPE_BITMAP = 0x01;
    static final int UPDATETYPE_PALETTE = 0x02;
    static final int UPDATETYPE_SYNCHRONIZE = 0x03;
    static final int UPDATETYPE_SURFCMDS = 0x04;
    static final int UPDATETYPE_PTR_NULL = 0x05;
    static final int UPDATETYPE_PTR_DEFAULT = 0x06;
    static final int UPDATETYPE_PTR_POSITION = 0x07;
    static final int UPDATETYPE_PTR_COLOR = 0x08;
    static final int UPDATETYPE_PTR_CACHED = 0x09;
    static final int UPDATETYPE_PTR_NEW = 0x0A;

    // 指针标志
    static final int PTRFLAGS_MOVE = 0x0800;
    static final int PTRFLAGS_DOWN = 0x8000;
    static final int PTRFLAGS_BUTTON1 = 0x1000;
    static final int PTRFLAGS_BUTTON2 = 0x2000;
    static final int PTRFLAGS_BUTTON3 = 0x4000;
    static final int PTRFLAGS_WHEEL = 0x0200;
    static final int PTRFLAGS_WHEEL_NEGATIVE = 0x0100;

    interface Host {
        void onBitmap(int left, int top, int width, int height, int[] argb);

        void onPointerShape(int width, int height, int hotX, int hotY, int[] argb);

        void onPointerPosition(int x, int y);

        void onPointerHide();

        void onSync();
    }

    private FastPath() {
    }

    /** 解析一块 FastPath 更新数据（已去掉 fpOutputHeader 与 size 字段）。 */
    static void process(int updateCode, byte[] data, int off, int len, Host host, Log log)
            throws RdpException {
        switch (updateCode) {
            case UPDATETYPE_BITMAP:
                handleBitmap(data, off, len, host);
                break;
            case UPDATETYPE_PTR_POSITION:
                if (len >= 4) {
                    host.onPointerPosition(Bytes.u16(data, off), Bytes.u16(data, off + 2));
                }
                break;
            case UPDATETYPE_PTR_NULL:
                host.onPointerHide();
                break;
            case UPDATETYPE_PTR_DEFAULT:
                host.onPointerHide();
                break;
            case UPDATETYPE_PTR_COLOR:
                handlePointer(data, off, len, host, false);
                break;
            case UPDATETYPE_PTR_NEW:
                handlePointer(data, off, len, host, true);
                break;
            case UPDATETYPE_SYNCHRONIZE:
                host.onSync();
                break;
            case UPDATETYPE_ORDERS:
            case UPDATETYPE_PALETTE:
            case UPDATETYPE_SURFCMDS:
            case UPDATETYPE_PTR_CACHED:
                // 未宣告对应能力，正常不会出现；出现则忽略
                break;
            default:
                log.log("FastPath: 未知更新类型 0x" + Integer.toHexString(updateCode) + "，已忽略");
                break;
        }
    }

    private static void handleBitmap(byte[] data, int off, int len, Host host) throws RdpException {
        int p = off;
        int end = off + len;
        if (p + 4 > end) {
            throw new RdpException("FastPath 位图更新过短");
        }
        int updateType = Bytes.u16(data, p);
        p += 2;
        int count = Bytes.u16(data, p);
        p += 2;
        if (updateType != 0x0001) {
            throw new RdpException("FastPath 位图更新类型异常：" + updateType);
        }
        for (int i = 0; i < count; i++) {
            if (p + 18 > end) {
                throw new RdpException("FastPath 位图矩形头被截断（" + i + "/" + count + "）");
            }
            int destLeft = Bytes.u16(data, p);
            int destTop = Bytes.u16(data, p + 2);
            int destRight = Bytes.u16(data, p + 4);
            int destBottom = Bytes.u16(data, p + 6);
            int width = Bytes.u16(data, p + 8);
            int height = Bytes.u16(data, p + 10);
            int bpp = Bytes.u16(data, p + 12);
            int flags = Bytes.u16(data, p + 14);
            int bitmapLength = Bytes.u16(data, p + 16);
            p += 18;
            if (p + bitmapLength > end) {
                throw new RdpException("FastPath 位图数据被截断（需要 " + bitmapLength
                        + "B，剩余 " + (end - p) + "B）");
            }
            if (width > 0 && height > 0 && destRight >= destLeft && destBottom >= destTop) {
                int[] argb = BitmapCodec.decode(data, p, bitmapLength, width, height, bpp, flags);
                host.onBitmap(destLeft, destTop, width, height, argb);
            }
            p += bitmapLength;
        }
    }

    private static void handlePointer(byte[] data, int off, int len, Host host, boolean cached)
            throws RdpException {
        int p = off;
        int end = off + len;
        if (cached) {
            p += 2;   // cacheIndex
        }
        if (p + 12 > end) {
            throw new RdpException("FastPath 指针更新过短");
        }
        int hotX = Bytes.u16(data, p);
        int hotY = Bytes.u16(data, p + 2);
        int width = Bytes.u16(data, p + 4);
        int height = Bytes.u16(data, p + 6);
        int andLen = Bytes.u16(data, p + 8);
        int xorLen = Bytes.u16(data, p + 10);
        p += 12;
        if (width <= 0 || height <= 0 || p + xorLen + andLen > end) {
            return;
        }
        int[] argb = new int[width * height];
        int andStride = ((width + 15) / 16) * 2;
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int o = p + (y * width + x) * 4;
                int b = data[o] & 0xFF;
                int g = data[o + 1] & 0xFF;
                int r = data[o + 2] & 0xFF;
                int a = data[o + 3] & 0xFF;
                if (a == 0) {
                    int andOff = p + xorLen + y * andStride + (x >> 3);
                    boolean andBit = andOff < end
                            && ((data[andOff] >> (7 - (x & 7))) & 1) != 0;
                    a = andBit ? 0x00 : 0xFF;
                }
                argb[y * width + x] = (a << 24) | (r << 16) | (g << 8) | b;
            }
        }
        host.onPointerShape(width, height, hotX, hotY, argb);
    }

    // ------------------------------------------------------------------
    // 输入事件
    // ------------------------------------------------------------------

    static byte[] mouse(int x, int y, int pointerFlags) {
        Out o = new Out(16);
        o.u8(0x04);                    // fpInputHeader: 1 个事件
        o.u8(0x01);                    // eventHeader: FASTPATH_INPUT_EVENT_MOUSE
        o.u16(pointerFlags);
        o.u16(x);
        o.u16(y);
        return o.toArray();
    }

    static byte[] key(int scancode, boolean extended, boolean down) {
        Out o = new Out(8);
        o.u8(0x04);
        int flags = (down ? 0 : 0x01) | (extended ? 0x02 : 0x00);
        o.u8((flags << 5) | 0x00);     // eventCode 0 = SCANCODE
        o.u8(scancode & 0xFF);
        return o.toArray();
    }

    static byte[] unicode(int codeUnit, boolean down) {
        Out o = new Out(8);
        o.u8(0x04);
        int flags = down ? 0 : 0x01;
        o.u8((flags << 5) | 0x03);     // eventCode 3 = UNICODE
        o.u16(codeUnit);
        return o.toArray();
    }

    static byte[] sync() {
        return new byte[]{0x04, 0x02};
    }
}
