package com.qiao.remotedesk.rdp;

/** RC4 流密码（NTLM 密封与密钥交换用），状态可跨多次调用保持连续。 */
final class Rc4 {

    private final int[] s = new int[256];
    private int i;
    private int j;

    Rc4(byte[] key) {
        for (int k = 0; k < 256; k++) {
            s[k] = k;
        }
        int jj = 0;
        for (int k = 0; k < 256; k++) {
            jj = (jj + s[k] + (key[k % key.length] & 0xFF)) & 0xFF;
            int t = s[k];
            s[k] = s[jj];
            s[jj] = t;
        }
        i = 0;
        j = 0;
    }

    /** 原地加解密。 */
    void process(byte[] data, int off, int len, byte[] out, int outOff) {
        for (int k = 0; k < len; k++) {
            i = (i + 1) & 0xFF;
            j = (j + s[i]) & 0xFF;
            int t = s[i];
            s[i] = s[j];
            s[j] = t;
            int keyByte = s[(s[i] + s[j]) & 0xFF];
            out[outOff + k] = (byte) (data[off + k] ^ keyByte);
        }
    }

    byte[] process(byte[] data) {
        byte[] r = new byte[data.length];
        process(data, 0, data.length, r, 0);
        return r;
    }

    /** RC4K：一次性密钥流加密（密钥交换用）。 */
    static byte[] rc4k(byte[] key, byte[] data) {
        return new Rc4(key).process(data);
    }
}
