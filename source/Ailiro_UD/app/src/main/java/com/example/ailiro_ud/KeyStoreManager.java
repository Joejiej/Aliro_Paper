package com.example.ailiro_ud;


import android.security.keystore.KeyProtection;
import android.util.Log;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.io.IOException;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.security.spec.ECGenParameterSpec;
import java.util.Calendar;

import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import javax.security.auth.x500.X500Principal;

public class KeyStoreManager {

    private static final String TAG = "KeyStoreManager";
//    private static final String ALIASUDECC = "UserDeviceECCKey";
//    private static final String ALIASUDPQAES = "UserDevicekekAESKey";

    // 确保密钥存在
    public static void ensureECCKeyExists(String ALIASUDECC) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            if (keyStore.containsAlias(ALIASUDECC)) {
                Log.i(TAG, "KeyStore 已存在密钥: " + ALIASUDECC);
                return;
            }

            KeyPairGenerator kpg = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_EC, "AndroidKeyStore");
            Calendar start = Calendar.getInstance();
            Calendar end = Calendar.getInstance();
            end.add(Calendar.YEAR, 10);

            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIASUDECC,
                    KeyProperties.PURPOSE_SIGN | KeyProperties.PURPOSE_VERIFY
            )
                    .setAlgorithmParameterSpec(new ECGenParameterSpec("secp256r1"))
                    .setCertificateSubject(new X500Principal("CN=UserDeviceECC,O=Aliro,C=CN"))
                    .setCertificateSerialNumber(java.math.BigInteger.ONE)
                    .setCertificateNotBefore(start.getTime())
                    .setCertificateNotAfter(end.getTime())
                    .setDigests(KeyProperties.DIGEST_SHA256)
                    .build();

            kpg.initialize(spec);
            kpg.generateKeyPair();
            Log.i(TAG, "新ECC密钥对已生成。");
        } catch (Exception e) {
            Log.e(TAG, "生成密钥对失败", e);
        }
    }


    // 获取公钥
    public static PublicKey getPublicKey(String ALIASUDECC) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        X509Certificate cert = (X509Certificate) keyStore.getCertificate(ALIASUDECC);
        return cert.getPublicKey();
    }

    // 获取私钥
    public static PrivateKey getPrivateKey(String ALIASUDECC) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return (PrivateKey) keyStore.getKey(ALIASUDECC, null);
    }


    //用AES来做管理后量子私钥的kek
    public static void ensureAESKeyExists(String ALIASUDPQAES) {
        try {
            KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
            keyStore.load(null);

            if (keyStore.containsAlias(ALIASUDPQAES)) {
                Log.i(TAG, "KeyStore 已存在 AES 密钥: " + ALIASUDPQAES);
                return;
            }

            KeyGenerator keyGenerator = KeyGenerator.getInstance(
                    KeyProperties.KEY_ALGORITHM_AES,
                    "AndroidKeyStore"
            );

            KeyGenParameterSpec spec = new KeyGenParameterSpec.Builder(
                    ALIASUDPQAES,
                    KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT
            )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true)
                    // 如果希望和解锁/指纹绑定，可以打开下面这行
                    // .setUserAuthenticationRequired(true)
                    .build();

            keyGenerator.init(spec);
            keyGenerator.generateKey();

            Log.i(TAG, "新 AES 密钥已生成，别名: " + ALIASUDPQAES);
        } catch (Exception e) {
            Log.e(TAG, "生成 AES 密钥失败", e);
        }
    }


    //从 Keystore 取出 AES SecretKey（用作 KEK）

    public static SecretKey getAESKey(String ALIASUDPQAES) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        return (SecretKey) keyStore.getKey(ALIASUDPQAES, null);
    }

    public static boolean hasAESKey(String ALIASUDPQAES) {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            return ks.containsAlias(ALIASUDPQAES);
        } catch (Exception e) {
            return false;
        }
    }

    //检查keystore中是否有目标key
    public static boolean checkAESkey(String alias) throws KeyStoreException, CertificateException, IOException, NoSuchAlgorithmException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        return ks.containsAlias(alias);
    }


    //在生成Kyber秘钥时立刻存入keystore缩短攻击窗口
    public static void saveToKeystoreasHmac(byte[] keyBytes, String kyb_ss_alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        // 将原始字节包装为 HmacSHA256 密钥规范
        SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "HmacSHA256");

        // 构建保护参数
        KeyProtection.Builder builder = new KeyProtection.Builder(
                KeyProperties.PURPOSE_SIGN)
                // 如果你后续还想用这个 Se 做别的加密，可以保留 ENCRYPT/DECRYPT，
                // 但在 Aliro 流程中，Se 通常只用于派生 HS。
                .setDigests(KeyProperties.DIGEST_SHA256) // HMAC 必须指定摘要
//                .setIsStrongBoxBacked(false) // 先设为 false 保证兼容性，成功后再尝试 true
                .setUserAuthenticationRequired(false);

        // 导入 KeyStore
        // importKey 会将密钥通过 HAL 层送入 TEE/SE，之后应用层再也无法获取 raw bytes
        keyStore.setEntry(
                kyb_ss_alias,
                new KeyStore.SecretKeyEntry(secretKeySpec),
                builder.build()
        );
    }

    //在生成SKDevice时立刻存入keystore缩短攻击窗口
    public static void saveToKeystoreasAES(byte[] keyBytes, String alias) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        // 将原始字节包装为 AES 密钥规范
        SecretKeySpec secretKeySpec = new SecretKeySpec(keyBytes, "AES");

        // 构建保护参数
        KeyProtection.Builder builder = new KeyProtection.Builder(
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM) // Aliro 通常用 GCM
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setDigests(KeyProperties.DIGEST_NONE) // GCM 不需要设置 Digest
                .setRandomizedEncryptionRequired(false) //可控IV，需要手动注入
                // *** 关键设置 ***
                //.setIsStrongBoxBacked(true) // 尝试存入 StrongBox (独立安全芯片)，如果硬件支持，但是需要API大于31
                .setUserAuthenticationRequired(false); // 根据业务需求，是否需要指纹解锁才能用

        // 导入 KeyStore
        // importKey 会将密钥通过 HAL 层送入 TEE/SE，之后应用层再也无法获取 raw bytes
        keyStore.setEntry(
                alias,
                new KeyStore.SecretKeyEntry(secretKeySpec),
                builder.build()
        );
    }



}
