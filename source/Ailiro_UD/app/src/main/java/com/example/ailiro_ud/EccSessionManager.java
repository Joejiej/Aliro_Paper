package com.example.ailiro_ud; // ← 请改成你的包名

import android.util.Base64;
import android.util.Log;

import java.security.*;
import java.security.spec.ECGenParameterSpec;

public class EccSessionManager {

    private static final String TAG = "EccSessionManager";

    // 当前会话的临时密钥对（仅存在内存中）
    private KeyPair keyPair;

    /**
     * 生成临时 ECC 密钥对（曲线 P-256）
     */
    public void generateTempKeyPair() throws Exception {
        Log.i(TAG, "正在生成临时 ECC 密钥对...");
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("EC");
        keyPairGenerator.initialize(new ECGenParameterSpec("secp256r1"), new SecureRandom());
        keyPair = keyPairGenerator.generateKeyPair();
        Log.i(TAG, "ECC 临时密钥对生成成功");
    }

    /**
     * 获取公钥的 DER 编码形式（可直接发送给 Reader）
     */
    public byte[] getPublicKeyEncoded() {
        if (keyPair == null) {
            throw new IllegalStateException("密钥对尚未生成！");
        }
        return keyPair.getPublic().getEncoded();
    }

    /**
     * 获取公钥的 Base64 表示（方便日志调试或 UI 显示）
     */
    public String getPublicKeyBase64() {
        return Base64.encodeToString(getPublicKeyEncoded(), Base64.NO_WRAP);
    }

    /**
     * 使用私钥对消息进行签名（SHA256withECDSA）
     */
    public byte[] signMessage(byte[] messageBytes) throws Exception {
        if (keyPair == null) {
            throw new IllegalStateException("密钥对尚未生成！");
        }

        Signature signature = Signature.getInstance("SHA256withECDSA");
        signature.initSign(keyPair.getPrivate());
        signature.update(messageBytes);
        byte[] sigBytes = signature.sign();

        Log.i(TAG, "签名成功，长度=" + sigBytes.length);
        return sigBytes;
    }

    /**
     * 清除临时密钥（安全销毁）
     */
    public void destroy() {
        Log.i(TAG, "销毁临时 ECC 密钥对");
        keyPair = null;
        try {
            // 尝试触发垃圾回收
            System.gc();
        } catch (Exception ignored) {}
    }
}
