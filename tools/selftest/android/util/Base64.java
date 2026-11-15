package android.util;

/** 仅用于 JVM 协议自测：覆盖 android.jar 里的 Stub! 实现，让 HTTP Basic 认证能真跑。 */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static final int NO_WRAP = 2;

    private Base64() {
    }

    public static String encodeToString(byte[] input, int flags) {
        return java.util.Base64.getEncoder().encodeToString(input);
    }

    public static byte[] decode(String str, int flags) {
        return java.util.Base64.getDecoder().decode(str);
    }
}
