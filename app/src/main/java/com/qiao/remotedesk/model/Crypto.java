package com.qiao.remotedesk.model;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 基于 Android Keystore 的 AES-GCM 加解密。密钥存在硬件/系统 Keystore 里，别名固定。
 * 返回 Base64 字符串（12 字节随机 IV 拼在密文前），空串原样返回。
 * 密钥不存在时自动创建；解密失败返回空串；所有异常一律吞掉并降级，绝不外抛。
 */
public final class Crypto {

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String ALIAS = "remotedesk_master";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";
    private static final int IV_LEN = 12;
    private static final int TAG_BITS = 128;

    private Crypto() {
    }

    /** 明文转 Base64 密文；失败返回空串，调用方据此选择「明文不落盘」。 */
    public static String encrypt(String plain) {
        if (plain == null || plain.isEmpty()) {
            return "";
        }
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) {
                return "";
            }
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, key);
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_LEN) {
                return "";
            }
            byte[] body = cipher.doFinal(plain.getBytes("UTF-8"));
            byte[] all = new byte[IV_LEN + body.length];
            System.arraycopy(iv, 0, all, 0, IV_LEN);
            System.arraycopy(body, 0, all, IV_LEN, body.length);
            return Base64.encodeToString(all, Base64.NO_WRAP);
        } catch (Throwable t) {
            return "";
        }
    }

    /** Base64 密文转明文；任何失败都返回空串而不是抛异常。 */
    public static String decrypt(String cipherText) {
        if (cipherText == null || cipherText.isEmpty()) {
            return "";
        }
        try {
            SecretKey key = getOrCreateKey();
            if (key == null) {
                return "";
            }
            byte[] all = Base64.decode(cipherText, Base64.NO_WRAP);
            if (all.length <= IV_LEN) {
                return "";
            }
            byte[] iv = new byte[IV_LEN];
            System.arraycopy(all, 0, iv, 0, IV_LEN);
            byte[] body = new byte[all.length - IV_LEN];
            System.arraycopy(all, IV_LEN, body, 0, body.length);
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), "UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }

    private static SecretKey getOrCreateKey() throws Exception {
        KeyStore ks = KeyStore.getInstance(KEYSTORE);
        ks.load(null);
        if (!ks.containsAlias(ALIAS)) {
            KeyGenerator kg = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build();
            kg.init(spec);
            kg.generateKey();
        }
        return (SecretKey) ks.getKey(ALIAS, null);
    }
}
