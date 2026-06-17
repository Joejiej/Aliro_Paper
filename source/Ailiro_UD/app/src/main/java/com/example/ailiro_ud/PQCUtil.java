package com.example.ailiro_ud;

import static com.example.ailiro_ud.KeyStoreManager.saveToKeystoreasAES;

import android.util.Log;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.generators.HKDFBytesGenerator;
import org.bouncycastle.crypto.params.HKDFParameters;
import org.bouncycastle.jcajce.provider.digest.SHA3;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;
import org.bouncycastle.pqc.jcajce.spec.DilithiumParameterSpec;
import org.bouncycastle.pqc.jcajce.spec.KyberParameterSpec;
import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;
import org.bouncycastle.jcajce.spec.KEMGenerateSpec;
import org.bouncycastle.jcajce.spec.KEMExtractSpec;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;


public final class PQCUtil {

    private static final String PQC_PROVIDER = "BCPQC";

    static {
        Security.addProvider(new BouncyCastlePQCProvider());
    }

    private PQCUtil() {}

    // =====================================================
    // Dilithium（算法级）
    // =====================================================

    public static KeyPair generateDilithiumKeyPair(int level)
            throws GeneralSecurityException {

        DilithiumParameterSpec spec;
        switch (level) {
            case 2: spec = DilithiumParameterSpec.dilithium2; break;
            case 3: spec = DilithiumParameterSpec.dilithium3; break;
            case 5: spec = DilithiumParameterSpec.dilithium5; break;
            default:
                throw new IllegalArgumentException("Unsupported Dilithium level: " + level);
        }

        KeyPairGenerator kpg =
                KeyPairGenerator.getInstance("Dilithium", PQC_PROVIDER);
        kpg.initialize(spec, new SecureRandom());
        return kpg.generateKeyPair();
    }

    public static byte[] dilithiumSign(byte[] data, PrivateKey privateKey)
            throws GeneralSecurityException {

        Signature sig = Signature.getInstance("Dilithium", PQC_PROVIDER);
        sig.initSign(privateKey, new SecureRandom());
        sig.update(data);
        return sig.sign();
    }

    public static byte[] dilithiumSignWithBytes(byte[] data, byte[] rawPrivKey) {
        DilithiumPrivateKeyParameters privParams = null;
        try {
            privParams = new DilithiumPrivateKeyParameters(
                    PQCConfig.getDilithiumParameters(),
                    rawPrivKey,
                    null
            );

            DilithiumSigner signer = new DilithiumSigner();
            signer.init(true, privParams);

            return signer.generateSignature(data);

        } finally {
            if (rawPrivKey != null) {
                Arrays.fill(rawPrivKey, (byte) 0);
            }
        }
    }

    public static boolean dilithiumVerify(byte[] data,
                                          byte[] signature,
                                          PublicKey publicKey)
            throws GeneralSecurityException {

        Signature sig = Signature.getInstance("Dilithium", PQC_PROVIDER);
        sig.initVerify(publicKey);
        sig.update(data);
        return sig.verify(signature);
    }

    // =====================================================
    // Kyber（算法级）
    // =====================================================

    public static KeyPair generateKyberKeyPair(int level)
            throws GeneralSecurityException {

        KyberParameterSpec spec;
        switch (level) {
            case 512:  spec = KyberParameterSpec.kyber512; break;
            case 768:  spec = KyberParameterSpec.kyber768; break;
            case 1024: spec = KyberParameterSpec.kyber1024; break;
            default:
                throw new IllegalArgumentException("Unsupported Kyber level: " + level);
        }

        KeyPairGenerator kpg =
                KeyPairGenerator.getInstance("Kyber", PQC_PROVIDER);
        kpg.initialize(spec, new SecureRandom());
        return kpg.generateKeyPair();
    }

    // ---------- Kyber KEM（算法级） ----------

    public static SecretKeyWithEncapsulation kyberEncapsulateRaw(
            PublicKey publicKey,
            String secretKeyAlgorithm)
            throws GeneralSecurityException {

        KeyGenerator keyGen =
                KeyGenerator.getInstance("Kyber", PQC_PROVIDER);

        keyGen.init(
                new KEMGenerateSpec(publicKey, secretKeyAlgorithm),
                new SecureRandom()
        );

        return (SecretKeyWithEncapsulation) keyGen.generateKey();
    }

    public static SecretKey kyberDecapsulateRaw(
            PrivateKey privateKey,
            byte[] encapsulation,
            String secretKeyAlgorithm)
            throws GeneralSecurityException {

        KeyGenerator keyGen =
                KeyGenerator.getInstance("Kyber", PQC_PROVIDER);

        keyGen.init(
                new KEMExtractSpec(privateKey, encapsulation, secretKeyAlgorithm)
        );

        return (SecretKeyWithEncapsulation) keyGen.generateKey();
    }

    // =====================================================
    // decode
    // =====================================================
    public static PrivateKey decodeDilithiumPrivateKey(byte[] encoded)
            throws GeneralSecurityException {

        KeyFactory kf = KeyFactory.getInstance("Dilithium", "BCPQC");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        return kf.generatePrivate(spec);
    }

    public static PublicKey decodeDilithiumPublicKey(byte[] encoded)
            throws GeneralSecurityException {

        KeyFactory kf = KeyFactory.getInstance("Dilithium", "BCPQC");
        X509EncodedKeySpec spec = new X509EncodedKeySpec(encoded);
        return kf.generatePublic(spec);
    }

    public static PublicKey decodeKyberPublicKey(byte[] encoded)
            throws GeneralSecurityException {

        if (Security.getProvider("BCPQC") == null) {
            Security.addProvider(new BouncyCastlePQCProvider());
        }

        try {
            org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters pubParams =
                    new org.bouncycastle.pqc.crypto.crystals.kyber.KyberPublicKeyParameters(
                            PQCConfig.getKyberParameters(),
                            encoded
                    );

            return new org.bouncycastle.pqc.jcajce.provider.kyber.BCKyberPublicKey(pubParams);
        } catch (Exception e) {
            throw new GeneralSecurityException("Raw Kyber key decoding failed", e);
        }
    }

    public static PrivateKey decodeKyberPrivateKey(byte[] encoded)
            throws GeneralSecurityException {

        KeyFactory kf = KeyFactory.getInstance("Kyber", "BCPQC");
        PKCS8EncodedKeySpec spec = new PKCS8EncodedKeySpec(encoded);
        return kf.generatePrivate(spec);
    }

    //提取纯kyber公钥
    public static byte[] getRawPublicKeyForReader(byte[] x509EncodedPub) throws Exception {
        SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(x509EncodedPub);
        return spki.getPublicKeyData().getBytes();
    }

    // =====================================================
    // HASH
    // =====================================================
    //为了PQC协议UD需要生成一个随机数做秘密共享
    public static byte[] generateUDnoise(int size) throws NoSuchAlgorithmException, NoSuchProviderException {
        SecureRandom secureRandom = new SecureRandom();
        byte[] seed = new byte[size];
        secureRandom.nextBytes(seed);
        PRNG prng = new PRNG(seed);
        return prng.generateBytesWithSHAKE(size);
    }

    public static byte[] generateTransAH0(byte[] transBytes) throws NoSuchAlgorithmException, NoSuchProviderException {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }

        // 注意：BC 的 MessageDigest 注册名
        MessageDigest md = new SHA3.DigestShake256_512();
        return md.digest(transBytes);
    }

    //HKDF.Extract
    public static byte[] deriveHSInKeystore(String seAlias, byte[] transAH0) throws Exception {
        // 1. 加载 Keystore
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);

        // 2. 获取 Se 密钥引用（该密钥不可导出）
        SecretKey seKey = (SecretKey) keyStore.getKey(seAlias, null);
        if (seKey == null) throw new Exception("Se Key not found in Keystore");

        // 3. 执行硬件隔离的 HMAC 运算
        // 注意：Android KeyStore 里的 HmacSHA256 会在 TEE/SE 中执行
        Mac hmac = Mac.getInstance("HmacSHA256");
        hmac.init(seKey);

        // 得到 HS，此时 HS 处于内存中，可用于下一步 Expand
        return hmac.doFinal(transAH0);
    }



    //HKDF.Expand
    public static Boolean expandSKDevice(byte[] hs, byte[] transAH0, String SKDeviceAlias) throws Exception {
        // 构造 Info: "enc" || trans_AH0
        byte[] label = "enc".getBytes(StandardCharsets.US_ASCII);
        byte[] info = ByteBuffer.allocate(label.length + transAH0.length)
                .put(label)
                .put(transAH0)
                .array();

        HKDFBytesGenerator hkdf = new HKDFBytesGenerator(new SHA256Digest());
        HKDFParameters params = new HKDFParameters(hs, null, info);
        hkdf.init(params);

        boolean state = false;
        byte[] skDevice = new byte[32]; // 假设使用 AES-256
        try {
            hkdf.generateBytes(skDevice, 0, 32);
            saveToKeystoreasAES(skDevice,SKDeviceAlias);
            state = true;
        }catch (Exception e) {
            throw e;
        } finally {
            java.util.Arrays.fill(skDevice, (byte) 0);
        }

        return state;
    }

    // =====================================================
    // AEAD
    // =====================================================
    public static byte[] AEADencryptData(String AEADalias, byte[] plaintext, byte[] aad, int counter) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey secretKey = (SecretKey) keyStore.getKey(AEADalias, null);

        // 8+4填充IV
        long fixedPart = 1L;
        byte[] iv = ByteBuffer.allocate(12)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(fixedPart)
                .putInt(counter)
                .array();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, spec);

        // 注入 AAD
        if (aad != null) {
            cipher.updateAAD(aad);
        }

        byte[] ciphertext = cipher.doFinal(plaintext);

        // 这里不拼接IV，让对方同步IV实现解密
        ByteBuffer byteBuffer = ByteBuffer.allocate(ciphertext.length);
        byteBuffer.put(ciphertext);
        return byteBuffer.array();
    }


    public static byte[] AEADdecryptData(String AEADalias, byte[] ciphertextWithTag, byte[] aad, int counter) throws Exception {
        KeyStore keyStore = KeyStore.getInstance("AndroidKeyStore");
        keyStore.load(null);
        SecretKey secretKey = (SecretKey) keyStore.getKey(AEADalias, null);

        byte[] iv = ByteBuffer.allocate(12)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(1L)
                .putInt(counter)
                .array();

        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        GCMParameterSpec spec = new GCMParameterSpec(128, iv);

        cipher.init(Cipher.DECRYPT_MODE, secretKey, spec);

        if (aad != null) {
            cipher.updateAAD(aad);
        }
        return cipher.doFinal(ciphertextWithTag);
    }

}
