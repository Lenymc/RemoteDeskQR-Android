package com.qiao.remotedesk.rdp;

/** GCC (T.124) 会议创建、客户端信息包与能力集构造。布局严格对照 FreeRDP gcc.c。 */
final class Gcc {

    static final int CS_CORE = 0xC001;
    static final int CS_SECURITY = 0xC002;
    static final int CS_NET = 0xC003;
    static final int CS_CLUSTER = 0xC004;

    static final int SC_CORE = 0x0C01;
    static final int SC_SECURITY = 0x0C02;
    static final int SC_NET = 0x0C03;

    private Gcc() {
    }

    // ------------------------------------------------------------------
    // 会议创建请求（Connect Initial 的 userData）
    // ------------------------------------------------------------------

    static byte[] conferenceCreateRequest(int width, int height, int colorDepth,
                                         int selectedProtocol, String clientName) {
        byte[] core = clientCoreData(width, height, colorDepth, selectedProtocol, clientName);
        byte[] cluster = clientClusterData();
        byte[] security = clientSecurityData();
        byte[] network = clientNetworkData(new int[0]);

        String v = System.getProperty("rdp.gccvar", "all");
        if ("core".equals(v)) {
            cluster = new byte[0];
            security = new byte[0];
            network = new byte[0];
        } else if ("nonet".equals(v)) {
            network = new byte[0];
        }
        int userDataLen = core.length + cluster.length + security.length + network.length;

        Out o = new Out(64 + userDataLen);
        // T.124 对象标识 + connectPDU
        o.u16be(5).u16be(0x14).u8(0x7C).u16be(1);
        o.u16be((userDataLen + 14) | 0x8000);
        // 用户数据头
        o.u16be(8).u16be(16).u8(0).u16(CS_CORE).u8(0);
        o.ascii("Duca");
        o.u16be(userDataLen | 0x8000);
        o.bytes(core).bytes(cluster).bytes(security).bytes(network);
        return o.toArray();
    }

    private static byte[] clientCoreData(int width, int height, int colorDepth,
                                         int selectedProtocol, String clientName) {
        String name = clientName == null ? "ANDROID" : clientName;
        if (name.length() > 15) {
            name = name.substring(0, 15);
        }

        boolean ext = (System.getProperty("rdp.core234") != null);
        int blockLen = ext ? 234 : 216;
        Out o = new Out(blockLen);
        o.u16(CS_CORE).u16(blockLen);         // type / length（含 4 字节头）
        o.u32(0x00080004);                    // version: RDP 5.0
        o.u16(width).u16(height);             // desktopWidth / desktopHeight
        o.u16(0x00CA);                        // colorDepth（忽略，由 postBeta2 决定）
        o.u16(0xAA03);                        // SASSequence = RNS_UD_SAS_DEL
        o.u32(0x00000409);                    // keyboardLayout: en-US
        o.u32(2600);                          // clientBuild
        byte[] nm = Bytes.utf16z(name);
        byte[] pad = new byte[32];
        System.arraycopy(nm, 0, pad, 0, Math.min(nm.length, 32));
        o.bytes(pad);                         // clientName（32 字节，UTF-16）
        o.u32(4);                             // keyboardType: IBM enhanced
        o.u32(0);                             // keyboardSubType
        o.u32(12);                            // keyboardFunctionKey
        o.zeros(64);                          // imeFileName
        o.u16(0x00CA);                        // postBeta2ColorDepth
        o.u16(1);                             // clientProductId
        o.u32(0);                             // serialNumber
        o.u16(colorDepth);                    // highColorDepth（24 / 32）
        o.u16(0x000F);                        // supportedColorDepths: 4/8/16/24
        o.u16(0x0001);                        // earlyCapabilityFlags: 仅 ERRINFO_SUPPORTED
        o.zeros(64);                          // clientDigProductId
        o.u8(0);                              // connectionType
        o.u8(0);                              // pad1octet
        o.u32(selectedProtocol);              // serverSelectedProtocol
        if (ext) {
            // 扩展字段（Windows 8+ 客户端会带）
            o.u32(0x00000000);                // desktopPhysicalWidth
            o.u32(0x00000000);                // desktopPhysicalHeight
            o.u16(0);                         // desktopOrientation
            o.u32(100);                       // desktopScaleFactor
            o.u32(100);                       // deviceScaleFactor
        }
        return o.toArray();
    }

    private static byte[] clientClusterData() {
        Out o = new Out(12);
        o.u16(CS_CLUSTER).u16(12);
        o.u32(0x0D);                          // REDIRECTION_SUPPORTED | VERSION_4 | NO_REDIRECTION
        o.u32(0);
        return o.toArray();
    }

    private static byte[] clientSecurityData() {
        Out o = new Out(12);
        o.u16(CS_SECURITY).u16(12);
        int method = Integer.getInteger("rdp.encMethod", 0);
        int level = Integer.getInteger("rdp.encLevel", 0);
        o.u32(method);                        // encryptionMethod
        o.u32(level);                         // encryptionLevel
        return o.toArray();
    }

    private static byte[] clientNetworkData(int[] channelIds) {
        Out o = new Out(8 + channelIds.length * 12);
        o.u16(CS_NET).u16(8 + channelIds.length * 12);
        o.u32(channelIds.length);
        for (int id : channelIds) {
            o.zeros(8);                       // 通道名
            o.u32(0);                         // 选项
        }
        return o.toArray();
    }

    // ------------------------------------------------------------------
    // Client Info PDU（TS_INFO_PACKET，安全头之后直接是这个结构）
    // ------------------------------------------------------------------

    static byte[] clientInfo(String domain, String user, String password,
                             String clientAddress, int width, int height) {
        String dom = domain == null ? "" : domain;
        String usr = user == null ? "" : user;
        String pwd = password == null ? "" : password;
        String shell = "";
        String workDir = "";

        // 注意：TS_INFO_PACKET 的 cbXXX 不含结尾 null（null 单独追加 2 字节）；
        // 而 TS_EXTENDED_INFO 的 cbClientAddress/cbClientDir 是含 null 的。
        byte[] domB = Bytes.utf16(dom);
        byte[] usrB = Bytes.utf16(usr);
        byte[] pwdB = Bytes.utf16(pwd);
        byte[] shellB = Bytes.utf16(shell);
        byte[] wdB = Bytes.utf16(workDir);

        byte[] addrB = Bytes.utf16z(clientAddress);
        byte[] dirB = Bytes.utf16z("C:\\Windows\\System32\\mstscax.dll");

        Out o = new Out(512);
        o.u32(0);                             // codePage
        // INFO_MOUSE | INFO_DISABLECTRLALTDEL | INFO_UNICODE | INFO_MOUSE_HAS_WHEEL
        o.u32(0x00000001 | 0x00000002 | 0x00000010 | 0x00001000);
        o.u16(domB.length).u16(usrB.length).u16(pwdB.length)
                .u16(shellB.length).u16(wdB.length);
        o.bytes(domB).u16(0);
        o.bytes(usrB).u16(0);
        o.bytes(pwdB).u16(0);
        o.bytes(shellB).u16(0);
        o.bytes(wdB).u16(0);

        // 扩展信息
        o.u16(2);                             // clientAddressFamily = AF_INET
        o.u16(addrB.length);
        o.bytes(addrB);
        o.u16(dirB.length);
        o.bytes(dirB);
        o.zeros(172);                         // clientTimeZone（全 0 = UTC 结构）
        o.u32(0);                             // clientSessionId
        o.u32(0x00000000);                    // performanceFlags
        o.u16(0);                             // cbAutoReconnectLen
        return o.toArray();
    }

    // ------------------------------------------------------------------
    // Confirm Active
    // ------------------------------------------------------------------

    static byte[] confirmActive(int shareId, int originatorId, int width, int height) {
        return confirmActive(shareId, originatorId, width, height, null, null);
    }

    /**
     * 构造 Confirm Active。
     *
     * <p>客户端回的是「自己支持的一套能力集」，与 FreeRDP / rdesktop 的做法一致，
     * 不去镜像服务器下发的列表。能力集编号严格按 MS-RDPBCGR 定义
     * （GENERAL=1、BITMAP=2、ORDER=3、INPUT=0x0D、GLYPH_CACHE=0x10……），
     * 服务器按编号分发解析，镜像很容易把内容塞进错的编号里导致解析失败。
     *
     * <p>{@code serverTypes} / {@code serverLens} 仅为兼容旧调用保留，不再使用。
     */
    static byte[] confirmActive(int shareId, int originatorId, int width, int height,
                                int[] serverTypes, int[] serverLens) {
        final int[] order = {
                0x01,   // GENERAL
                0x02,   // BITMAP
                0x03,   // ORDER
                0x13,   // BITMAP_CACHE_V2
                0x08,   // POINTER
                0x0D,   // INPUT
                0x0F,   // BRUSH
                0x10,   // GLYPH_CACHE
                0x14,   // VIRTUAL_CHANNEL
                0x0C,   // SOUND
                0x09,   // SHARE
                0x0E,   // FONT
                0x05,   // CONTROL
                0x0A,   // COLOR_CACHE
                0x07,   // ACTIVATION
                0x1A,   // MULTIFRAGMENT_UPDATE
                0x1B,   // LARGE_POINTER
        };
        java.util.ArrayList<byte[]> caps = new java.util.ArrayList<byte[]>();
        int capsLen = 0;
        String filter = System.getProperty("rdp.caps");
        if (filter != null) {
            filter = "," + filter.toUpperCase() + ",";
        }
        for (int t : order) {
            if (filter != null && filter.indexOf("," + Integer.toHexString(t).toUpperCase() + ",") < 0) {
                continue;                      // 诊断：只发指定能力集
            }
            byte[] c = capByType(t, width, height);
            if (c != null) {
                caps.add(c);
                capsLen += c.length;
            }
        }

        Out o = new Out(32 + capsLen);
        o.u32(shareId);
        o.u16(originatorId);
        o.u16(4);                             // lengthSourceDescriptor（"RDP\0"）
        o.u16(4 + capsLen);                   // lengthCombinedCapabilities
        o.ascii("RDP").u8(0);                 // sourceDescriptor "RDP\0"
        o.u16(caps.size());                   // numberCapabilities
        o.u16(0);                             // pad2Octets
        for (byte[] c : caps) {
            o.bytes(c);
        }
        return o.toArray();
    }

    /** 按能力集类型号构造；类型号遵循 MS-RDPBCGR 的 CAPSET_TYPE_* 定义。 */
    private static byte[] capByType(int type, int width, int height) {
        switch (type) {
            case 0x01: return generalCap();
            case 0x02: return bitmapCap(width, height);
            case 0x03: return orderCap();
            case 0x13: return bitmapCacheV2Cap();
            case 0x08: return pointerCap();
            case 0x0D: return inputCap();
            case 0x0F: return brushCap();
            case 0x10: return glyphCacheCap();
            case 0x14: return virtualChannelCap();
            case 0x0C: return soundCap();
            case 0x09: return shareCap();
            case 0x0E: return fontCap();
            case 0x05: return controlCap();
            case 0x0A: return colorCacheCap();
            case 0x07: return activationCap();
            case 0x1A: return multifragmentCap();
            case 0x1B: return largePointerCap();
            default: return null;
        }
    }

    /** TS_GENERAL_CAPABILITYSET（24B） */
    private static byte[] generalCap() {
        Out c = new Out(24);
        c.u16(0x01).u16(24);
        c.u16(1);            // osMajorType = WINDOWS
        c.u16(3);            // osMinorType = WINDOWS_NT
        c.u16(0x0200);       // protocolVersion = TS_CAPS_PROTOCOLVERSION
        c.u16(0);            // pad2OctetsA
        c.u16(0);            // generalCompressionTypes
        // extraFlags: NO_BITMAP_COMPRESSION_HDR | AUTORECONNECT | LONG_CREDENTIALS | FASTPATH_OUTPUT
        c.u16(0x0400 | 0x0008 | 0x0004 | 0x0001);
        c.u16(0);            // updateCapabilityFlag
        c.u16(0);            // remoteUnshareFlag
        c.u16(0);            // generalCompressionLevel
        c.u8(0).u8(0);       // refreshRectSupport / suppressOutputSupport
        return c.toArray();
    }

    /** TS_BITMAP_CAPABILITYSET（28B） */
    private static byte[] bitmapCap(int width, int height) {
        Out c = new Out(28);
        c.u16(0x02).u16(28);
        c.u16(24);           // preferredBitsPerPixel（与 GCC 里的 colorDepth 保持一致）
        c.u16(1);            // receive1BitPerPixel
        c.u16(1);            // receive4BitsPerPixel
        c.u16(1);            // receive8BitsPerPixel
        c.u16(width).u16(height);
        c.u16(0);            // pad2Octets
        c.u16(1);            // desktopResizeFlag
        c.u16(1);            // bitmapCompressionFlag（必须为 1）
        c.u8(0);             // highColorFlags
        c.u8(0);             // drawingFlags
        c.u16(1);            // multipleRectangleSupport（必须为 1）
        c.u16(0);            // pad2OctetsB
        return c.toArray();
    }

    /**
     * TS_ORDER_CAPABILITYSET（88B）。
     *
     * <p>orderFlags 必须带 NEGOTIATEORDERSUPPORT，orderSupport 也不能全零，
     * 否则服务器直接判定 ERRINFO_BAD_CAPABILITIES(0x10EA)。
     * 这里声明的是「基础订单」集合（目标绘制、图案填充、屏幕块拷贝等）。
     */
    private static byte[] orderCap() {
        byte[] support = new byte[32];
        support[0x00] = 1;   // DSTBLT
        support[0x01] = 1;   // PATBLT
        support[0x02] = 1;   // SCRBLT
        support[0x08] = 1;   // LINETO
        support[0x09] = 1;   // MULTI_DRAWNINEGRID
        support[0x13] = 1;   // POLYLINE
        support[0x1A] = 1;   // INDEX
        Out c = new Out(88);
        c.u16(0x03).u16(88);
        c.zeros(16);         // terminalDescriptor
        c.u32(0);            // pad4OctetsA
        c.u16(1);            // desktopSaveXGranularity
        c.u16(20);           // desktopSaveYGranularity
        c.u16(0);            // pad2OctetsA
        c.u16(1);            // maximumOrderLevel（必须为 1）
        c.u16(0);            // numberFonts
        c.u16(0x0002 | 0x0008 | 0x0020);   // NEGOTIATEORDERSUPPORT|ZEROBOUNDSDELTA|COLORINDEX
        c.bytes(support);    // orderSupport
        c.u16(0);            // textFlags
        c.u16(0);            // orderSupportExFlags
        c.u32(0);            // pad4OctetsB
        c.u32(230400);       // desktopSaveSize
        c.u16(0);            // pad2OctetsC
        c.u16(0);            // pad2OctetsD
        c.u16(1252);         // textANSICodePage
        c.u16(0);            // pad2OctetsE
        return c.toArray();
    }

    /** TS_BITMAPCACHE_CAPABILITYSET_REV2（40B） */
    private static byte[] bitmapCacheV2Cap() {
        Out c = new Out(40);
        c.u16(0x13).u16(40);
        c.u16(0);            // cacheFlags（不带 persistent 位）
        c.u8(0);             // pad2
        c.u8(3);             // numCellCaches
        c.u32(256);          // cache0：16x16
        c.u32(512);          // cache1：32x32
        c.u32(65536);        // cache2：64x64 及以上
        c.zeros(20);         // 其余缓存未使用
        return c.toArray();
    }

    /** TS_POINTER_CAPABILITYSET（10B） */
    private static byte[] pointerCap() {
        Out c = new Out(10);
        c.u16(0x08).u16(10);
        c.u16(1);            // colorPointerFlag
        c.u16(20);           // colorPointerCacheSize
        c.u16(20);           // pointerCacheSize
        return c.toArray();
    }

    /** TS_INPUT_CAPABILITYSET（88B，含 64 字节 imeFileName） */
    private static byte[] inputCap() {
        Out c = new Out(88);
        c.u16(0x0D).u16(88);
        c.u16(0x0001 | 0x0008 | 0x0010 | 0x0020);  // SCANCODES|FASTPATH|UNICODE|FASTPATH2
        c.u16(0);            // pad2OctetsA
        c.u32(0x00000409);   // keyboardLayout（en-US）
        c.u32(4);            // keyboardType
        c.u32(0);            // keyboardSubType
        c.u32(12);           // keyboardFunctionKey
        c.zeros(64);         // imeFileName
        return c.toArray();
    }

    /** TS_BRUSH_CAPABILITYSET（8B） */
    private static byte[] brushCap() {
        Out c = new Out(8);
        c.u16(0x0F).u16(8);
        c.u32(1);            // brushSupportLevel = BRUSH_COLOR_8x8
        return c.toArray();
    }

    /** TS_GLYPHCACHE_CAPABILITYSET（52B） */
    private static byte[] glyphCacheCap() {
        int[][] defs = {
                {254, 4}, {254, 4}, {254, 8}, {254, 8}, {254, 16},
                {254, 32}, {254, 64}, {254, 128}, {254, 256}, {64, 2048},
        };
        Out c = new Out(52);
        c.u16(0x10).u16(52);
        for (int[] d : defs) {
            c.u16(d[0]).u16(d[1]);       // cacheEntries / maxCellSize
        }
        c.u32(0x01000100);   // fragCache
        c.u16(2);            // glyphSupportLevel = GLYPH_SUPPORT_FULL
        c.u16(0);            // pad2Octets
        return c.toArray();
    }

    /** TS_VIRTUALCHANNEL_CAPABILITYSET（8B） */
    private static byte[] virtualChannelCap() {
        Out c = new Out(8);
        c.u16(0x14).u16(8);
        c.u32(0);            // flags：不请求任何虚拟通道
        return c.toArray();
    }

    /** TS_SOUND_CAPABILITYSET（8B） */
    private static byte[] soundCap() {
        Out c = new Out(8);
        c.u16(0x0C).u16(8);
        c.u16(1);            // soundFlags = SOUND_BEEPS_FLAG
        c.u16(0);            // pad2OctetsA
        return c.toArray();
    }

    /** TS_SHARE_CAPABILITYSET（8B） */
    private static byte[] shareCap() {
        Out c = new Out(8);
        c.u16(0x09).u16(8);
        c.u16(0);            // nodeId（客户端为 0）
        c.u16(0);            // pad2Octets
        return c.toArray();
    }

    /** TS_FONT_CAPABILITYSET（8B） */
    private static byte[] fontCap() {
        Out c = new Out(8);
        c.u16(0x0E).u16(8);
        c.u16(1);            // fontSupportFlags = FONTSUPPORT_FONTLIST
        c.u16(0);            // pad2Octets
        return c.toArray();
    }

    /** TS_CONTROL_CAPABILITYSET（12B） */
    private static byte[] controlCap() {
        Out c = new Out(12);
        c.u16(0x05).u16(12);
        c.u16(0);            // controlFlags
        c.u16(0);            // remoteDetachFlag
        c.u16(2);            // controlInterest
        c.u16(2);            // detachInterest
        return c.toArray();
    }

    /** TS_COLORCACHE_CAPABILITYSET（8B） */
    private static byte[] colorCacheCap() {
        Out c = new Out(8);
        c.u16(0x0A).u16(8);
        c.u16(6);            // colorTableCacheSize
        c.u16(0);            // pad2Octets
        return c.toArray();
    }

    /** TS_ACTIVATION_CAPABILITYSET（12B） */
    private static byte[] activationCap() {
        Out c = new Out(12);
        c.u16(0x07).u16(12);
        c.u16(0).u16(0).u16(0).u16(0);
        return c.toArray();
    }

    /** TS_MULTIFRAGMENTUPDATE_CAPABILITYSET（8B） */
    private static byte[] multifragmentCap() {
        Out c = new Out(8);
        c.u16(0x1A).u16(8);
        c.u32(608800);       // MaxRequestSize
        return c.toArray();
    }

    /** TS_LARGEPOINTER_CAPABILITYSET（6B） */
    private static byte[] largePointerCap() {
        Out c = new Out(6);
        c.u16(0x1B).u16(6);
        c.u16(0x0001);       // largePointerSupportFlags = 96x96
        return c.toArray();
    }

    // ------------------------------------------------------------------
    // 服务器数据块解析
    // ------------------------------------------------------------------

    /** 从 GCC 响应里找出指定类型的数据块（type 为 0x0C01/0x0C02/0x0C03）。 */
    static byte[] findServerBlock(byte[] data, int type) {
        for (int i = 0; i + 4 <= data.length; i++) {
            if (Bytes.u16(data, i) == type) {
                int len = Bytes.u16(data, i + 2);
                if (len >= 4 && i + len <= data.length) {
                    return Bytes.slice(data, i + 4, len - 4);
                }
            }
        }
        return null;
    }
}
