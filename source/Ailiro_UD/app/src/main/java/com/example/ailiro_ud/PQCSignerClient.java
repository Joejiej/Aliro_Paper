package com.example.ailiro_ud;

import static com.example.ailiro_ud.PQCUtil.dilithiumSign;
import static com.example.ailiro_ud.PQCUtil.dilithiumSignWithBytes;
import static com.example.ailiro_ud.PQCUtil.dilithiumVerify;
import static com.example.ailiro_ud.PQKeyManager.getDilithiumPrivateKey;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.util.Log;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.AsymmetricCipherKeyPair;
import org.bouncycastle.crypto.CryptoServicesRegistrar;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyGenerationParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumKeyPairGenerator;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner;
import org.bouncycastle.pqc.jcajce.provider.dilithium.BCDilithiumPublicKey;

import java.security.PublicKey;
import java.security.SecureRandom;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class PQCSignerClient {
    private static final String TAG = "PqcSignerClient";
    private final Context context;
    private IPQCSigner pqcSigner = null;
    private final Semaphore connectionSemaphore = new Semaphore(0);

    private final ServiceConnection serviceConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            pqcSigner = IPQCSigner.Stub.asInterface(service);
            connectionSemaphore.release();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            pqcSigner = null;
        }
    };

    public PQCSignerClient(Context context) {
        if (context == null) {
            throw new IllegalArgumentException("Context cannot be null");
        }
        this.context = context.getApplicationContext();
    }

    /**
     * 同步签名方法
     * @param /encryptedPrivKey KEK 加密后的私钥字节
     * @param message 待签名消息
     * @return 签名结果
     */
    public byte[] signSynchronous(byte[] message) throws Exception {
        // 1. 绑定 Service
        System.out.println(Thread.currentThread().getName());
        Intent intent = new Intent(context, PQCSignatureService.class);
        boolean bound = context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE);

        if (!bound) throw new Exception("无法绑定签名服务");
        byte[] rawPrivKey = null;
        try {
            // 2. 等待连接成功（设置 5 秒超时）
            if (!connectionSemaphore.tryAcquire(5, TimeUnit.SECONDS)) {
                throw new Exception("签名服务连接超时");
            }

            if (pqcSigner == null) throw new Exception("IPqcSigner 实例为空");

            // 3. 发起跨进程调用 (IPC)
            // 注意：由于 Service 逻辑中最后会 killProcess，这次调用后连接会断开
            //这里才解密kek尽量缩短暴露时间

            rawPrivKey = getDilithiumPrivateKey();

            return pqcSigner.sign(rawPrivKey, message);

        } finally {
            // 4. 解绑服务
            if (rawPrivKey != null) {
                java.util.Arrays.fill(rawPrivKey, (byte) 0);
            }
            context.unbindService(serviceConnection);
            pqcSigner = null;
        }
    }

    public boolean verify(PublicKey publicKey, byte[] message, byte[] signature) {
        try {
            byte[] encoded = publicKey.getEncoded();
            if (encoded == null) {
                return false;
            }

            SubjectPublicKeyInfo spki = SubjectPublicKeyInfo.getInstance(encoded);

            byte[] rawPublicKeyBytes = spki.getPublicKeyData().getOctets();

            DilithiumPublicKeyParameters pubParams = new DilithiumPublicKeyParameters(
                    PQCConfig.getDilithiumParameters(),
                    rawPublicKeyBytes
            );

            DilithiumSigner verifier = new DilithiumSigner();
            verifier.init(false, pubParams);
            return verifier.verifySignature(message, signature);

        } catch (Exception e) {
            android.util.Log.e("PQC_Verify", "验签解析失败: " + e.getMessage());
            return false;
        }
    }

    public boolean verifyWithRawPK(byte[] publicKey, byte[] message, byte[] signature) {
        try {
            DilithiumPublicKeyParameters pubParams = new DilithiumPublicKeyParameters(
                    PQCConfig.getDilithiumParameters(),
                    publicKey
            );

            DilithiumSigner verifier = new DilithiumSigner();
            verifier.init(false, pubParams);
            return verifier.verifySignature(message, signature);

        } catch (Exception e) {
            android.util.Log.e("PQC_Verify", "验签解析失败: " + e.getMessage());
            return false;
        }
    }
}
