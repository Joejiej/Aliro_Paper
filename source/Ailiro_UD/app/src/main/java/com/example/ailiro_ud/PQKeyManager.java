package com.example.ailiro_ud;

import static com.example.ailiro_ud.KeyStoreManager.checkAESkey;
import static com.example.ailiro_ud.KeyStoreManager.saveToKeystoreasHmac;
import static com.example.ailiro_ud.PQCUtil.getRawPublicKeyForReader;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

import android.util.Base64;
import android.util.Log;

import org.bouncycastle.asn1.pkcs.PrivateKeyInfo;
import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters;
import org.bouncycastle.pqc.crypto.util.PrivateKeyFactory;
import org.bouncycastle.pqc.crypto.util.PublicKeyFactory;
import org.bouncycastle.pqc.jcajce.provider.dilithium.BCDilithiumPublicKey;
import org.bouncycastle.util.Arrays;
import org.bouncycastle.util.io.pem.PemObject;
import org.bouncycastle.util.io.pem.PemWriter;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PublicKey;
import java.security.spec.X509EncodedKeySpec;

import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

public final class PQKeyManager {

    private static final String TAG = "PQKeyManager";
    public static final String PREF_NAME = "pqc_keys";
    public static final String PREF_NAME_TEST = "pqc_keys_test";

    // ===== Dilithium 存储字段 =====
    public static final String KEY_DIL_PUB      = "dil_pub";
    public static final String KEY_DIL_PRIV_IV  = "dil_priv_iv";
    public static final String KEY_DIL_PRIV_CT  = "dil_priv_ct";

    // ===== Kyber 存储字段 =====
    public static final String KEY_KYB_PUB      = "kyb_pub";
    public static final String KEY_KYB_PRIV_IV  = "kyb_priv_iv";
    public static final String KEY_KYB_PRIV_CT  = "kyb_priv_ct";

    // ===== AES-GCM 参数 =====
    private static final int GCM_TAG_LENGTH_BIT = 128;

    // ===== Application-scoped state =====
    private static SharedPreferences sp;
    private static String aliasAesDil;
    private static String aliasAesKyb;
    private static boolean initialized = false;

    private PQKeyManager() {}

    // =====================================================
    // 初始化（只允许 Application 调用一次）
    // =====================================================
    public static synchronized void init(Context appContext,
                                         String aesDilAlias,
                                         String aesKybAlias) {

        if (initialized) {
            Log.i(TAG, "PQKeyManager already initialized");
            return;
        }

        sp = appContext.getSharedPreferences(PREF_NAME_TEST, Context.MODE_PRIVATE);
        aliasAesDil = aesDilAlias;
        aliasAesKyb = aesKybAlias;

        // 确保 Keystore AES Key 存在
        KeyStoreManager.ensureAESKeyExists(aliasAesDil);
        KeyStoreManager.ensureAESKeyExists(aliasAesKyb);

        ensureDilithiumKeys(appContext);
        ensureKyberKeys();

        initialized = true;
        Log.i(TAG, "PQKeyManager initialized");
    }

    private static void ensureInitialized() {
        if (!initialized || sp == null) {
            throw new IllegalStateException("PQKeyManager not initialized");
        }
    }

    // =====================================================
    // Dilithium
    // =====================================================
    private static void ensureDilithiumKeys(Context context) {
        // 1. 检查必要条件状态
        boolean hasSpKeys = sp.contains(KEY_DIL_PUB)
                && sp.contains(KEY_DIL_PRIV_IV)
                && sp.contains(KEY_DIL_PRIV_CT);

        boolean hasCertFile = false;
        // 动态检查 res/raw/ud_cert.pem 是否存在 (文件名必须小写: ud_cert)
        @SuppressLint("DiscouragedApi") int certResId = context.getResources().getIdentifier("ud_cert", "raw", context.getPackageName());
        if (certResId != 0) {
            hasCertFile = true;
        }

        boolean hasAesKey = false;
        try {
            hasAesKey = checkAESkey(aliasAesDil);
        } catch (Exception e) {
            Log.w(TAG, "Error checking KeyStore for AES key", e);
        }

        // 2. 核心判断逻辑
        // 场景 A: 完美状态 - 证书已预置，且私钥和AES保护密钥都存在
        if (hasCertFile && hasAesKey && hasSpKeys) {
            Log.i(TAG, "Aliro Environment Ready: CI Certificate found and Dilithium Private Key is secure. No generation needed.");
            return;
        }

        // 场景 B: 危险状态 - 证书已预置，但私钥丢失 (SP被清空 或 Keystore丢失)
        // 警告：如果此时重新生成，新私钥将无法匹配 APK 里打包的证书！
        if (hasCertFile && (!hasAesKey || !hasSpKeys)) {
            Log.e(TAG, "CRITICAL ERROR: Found bundled Certificate (ud_cert) but Private Key/AES Key is missing!");
            Log.e(TAG, "Regenerating keys now will CAUSE MISMATCH with the bundled certificate.");
            // 这里你可以选择抛出异常，或者强制重新生成（但在测试中这意味着你需要重新找CI签发）
            // 为了开发流畅，我们这里继续向下执行去重新生成，但留下了严重警告
        }

        // 场景 C: 普通状态 - 已经有私钥了（等待CI签发中），无需重复生成
        if (hasSpKeys && hasAesKey) {
            Log.i(TAG, "Dilithium keys already exist (Waiting for Certificate).");
            return;
        }

        // 3. 生成逻辑
        try {
            Log.i(TAG, "Generating Dilithium key pair...");

            SecretKey aesDil = KeyStoreManager.getAESKey(aliasAesDil);
            // 注意：getAESKey 内部应该处理 "如果不存在则生成" 的逻辑

            KeyPair kp = PQCUtil.generateDilithiumKeyPair(PQCConfig.DILITHIUM_LEVEL);

            byte[] encodedPri = kp.getPrivate().getEncoded();
            PrivateKeyInfo pki = PrivateKeyInfo.getInstance(encodedPri);
            DilithiumPrivateKeyParameters privParams = (DilithiumPrivateKeyParameters) PrivateKeyFactory.createKey(pki);
            byte[] priv = privParams.getEncoded();

            byte[] encodedPub = kp.getPublic().getEncoded();
            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(encodedPub);
            DilithiumPublicKeyParameters pubParams = (DilithiumPublicKeyParameters) PublicKeyFactory.createKey(spki);
            byte[] pub = pubParams.getEncoded();


            EncryptedBlob enc = encryptWithAESGCM(aesDil, priv);

            sp.edit()
                    .putString(KEY_DIL_PUB, Base64.encodeToString(pub, Base64.NO_WRAP))
                    .putString(KEY_DIL_PRIV_IV, Base64.encodeToString(enc.iv, Base64.NO_WRAP))
                    .putString(KEY_DIL_PRIV_CT, Base64.encodeToString(enc.ct, Base64.NO_WRAP))
                    .apply();

            // 安全擦除内存中的明文私钥
            java.util.Arrays.fill(priv, (byte) 0);
            Log.i(TAG, "Dilithium keys generated and secured.");

            // 导出公钥供 CI 签发使用
//            String pubkeypem = publicKeyToPem(kp.getPublic());
//            File out = new File(context.getFilesDir(), "dilithium_pub.pem");
//            try (FileOutputStream fos = new FileOutputStream(out)) {
//                fos.write(pubkeypem.getBytes(StandardCharsets.US_ASCII));
//            }

            Log.i(TAG, "--> Action Required: Please send 'dilithium_pub.pem' to CI to create 'ud_cert.pem'");

        } catch (Exception e) {
            Log.e(TAG, "Failed to generate Dilithium keys", e);
        }
    }

    //导出公钥PEM交给CI签发证书
    public static String publicKeyToPem(PublicKey publicKey) throws IOException {
        StringWriter sw = new StringWriter();
        PemWriter pw = new PemWriter(sw);

        pw.writeObject(new PemObject(
                "PUBLIC KEY",
                publicKey.getEncoded()
        ));
        pw.flush();
        pw.close();

        return sw.toString();
    }

    public static byte[] getDilithiumPublicKey() {
        ensureInitialized();
        String b64 = sp.getString(KEY_DIL_PUB, null);
        return b64 == null ? null : Base64.decode(b64, Base64.NO_WRAP);
    }

    public static PublicKey byteToDilithiumPublicKey(byte[] keyBytes) {
        try {
            int expected = PQCConfig.dilithiumPublicKeySize();
            if (keyBytes == null || keyBytes.length != expected) {
                throw new IllegalArgumentException(
                        "Invalid raw Dilithium public key length: expected "
                                + expected + " bytes for Dilithium"
                                + PQCConfig.DILITHIUM_LEVEL
                                + ", got " + (keyBytes == null ? 0 : keyBytes.length));
            }

            DilithiumPublicKeyParameters pubParams = new DilithiumPublicKeyParameters(
                    PQCConfig.getDilithiumParameters(), keyBytes);

            return new BCDilithiumPublicKey(pubParams);

        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    public static byte[] getDilithiumPrivateKey() {
        ensureInitialized();
        try {
            byte[] iv = Base64.decode(sp.getString(KEY_DIL_PRIV_IV, null), Base64.NO_WRAP);
            byte[] ct = Base64.decode(sp.getString(KEY_DIL_PRIV_CT, null), Base64.NO_WRAP);
            SecretKey aes = KeyStoreManager.getAESKey(aliasAesDil);
            return decryptWithAESGCM(aes, iv, ct);
        } catch (Exception e) {
            Log.e(TAG, "Decrypt Dilithium private key failed", e);
            return null;
        }
    }

    // =====================================================
    // Kyber
    // =====================================================
    private static void  ensureKyberKeys() {

        try {
            Log.i(TAG, "Generating Kyber key pair");

            SecretKey aesKyb = KeyStoreManager.getAESKey(aliasAesKyb);
            KeyPair kp = PQCUtil.generateKyberKeyPair(PQCConfig.KYBER_LEVEL);

            byte[] pub = getRawPublicKeyForReader(kp.getPublic().getEncoded());
            byte[] priv = kp.getPrivate().getEncoded();

            EncryptedBlob enc = encryptWithAESGCM(aesKyb, priv);

            sp.edit()
                    .putString(KEY_KYB_PUB, Base64.encodeToString(pub, Base64.NO_WRAP))
                    .putString(KEY_KYB_PRIV_IV, Base64.encodeToString(enc.iv, Base64.NO_WRAP))
                    .putString(KEY_KYB_PRIV_CT, Base64.encodeToString(enc.ct, Base64.NO_WRAP))
                    .apply();

            java.util.Arrays.fill(priv, (byte) 0);
            Log.i(TAG, "Kyber keys generated");

        } catch (Exception e) {
            Log.e(TAG, "Failed to generate Kyber keys", e);
        }
    }

    public static byte[] getKyberPublicKey() {
        ensureInitialized();
        String b64 = sp.getString(KEY_KYB_PUB, null);
        return b64 == null ? null : Base64.decode(b64, Base64.NO_WRAP);
    }

    public static byte[] getKyberPrivateKey() {
        ensureInitialized();
        try {
            byte[] iv = Base64.decode(sp.getString(KEY_KYB_PRIV_IV, null), Base64.NO_WRAP);
            byte[] ct = Base64.decode(sp.getString(KEY_KYB_PRIV_CT, null), Base64.NO_WRAP);
            SecretKey aes = KeyStoreManager.getAESKey(aliasAesKyb);
            return decryptWithAESGCM(aes, iv, ct);
        } catch (Exception e) {
            Log.e(TAG, "Decrypt Kyber private key failed", e);
            return null;
        }
    }

    // =====================================================
    // AES-GCM helpers
    // =====================================================
    private static class EncryptedBlob {
        final byte[] iv;
        final byte[] ct;
        EncryptedBlob(byte[] iv, byte[] ct) {
            this.iv = iv;
            this.ct = ct;
        }
    }

    private static EncryptedBlob encryptWithAESGCM(SecretKey key, byte[] plain) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, key);
        return new EncryptedBlob(cipher.getIV(), cipher.doFinal(plain));
    }

    private static byte[] decryptWithAESGCM(SecretKey key, byte[] iv, byte[] ct) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(GCM_TAG_LENGTH_BIT, iv));
        return cipher.doFinal(ct);
    }

    //handle share secrete and massage
    public static byte[] generateAndSecureSession(PublicKey readerePubkey, String SSkeyAlias) throws Exception {

        byte[] rawSecretBytes = null;
        byte[] encapsulationToSend = null;
        try{
            PQCKEM.KyberEncapsulated result = PQCKEM.encapsulate(readerePubkey,"AES");
            rawSecretBytes = result.getsharedSecret();
            encapsulationToSend = result.getencapsulation();
            saveToKeystoreasHmac(rawSecretBytes,SSkeyAlias);
        } catch (Exception e){
            throw e;
        }
        finally {
            if (rawSecretBytes != null) {
                Arrays.fill(rawSecretBytes, (byte) 0x00);
            }
        }
        return encapsulationToSend;
    }



}
