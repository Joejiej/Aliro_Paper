package com.example.ailiro_ud;

import android.app.Service;
import android.content.Intent;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;

import org.bouncycastle.asn1.x509.SubjectPublicKeyInfo;
import org.bouncycastle.crypto.util.PublicKeyFactory;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPublicKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner;
import org.bouncycastle.pqc.jcajce.interfaces.DilithiumPublicKey;

import java.security.PublicKey;
import java.util.Arrays;

// 移除 abstract，确保 Service 可被实例化
public class PQCSignatureService extends Service {

    private final IPQCSigner.Stub binder = new IPQCSigner.Stub() {
        @Override
        public byte[] sign(byte[] rawPrivKey, byte[] message) throws RemoteException {
            // 注意：考虑到安全性和 isolatedProcess 的权限限制，
            // 建议主进程解密 KEK 后将明文 privKey 通过 AIDL 传进来

            DilithiumPrivateKeyParameters privParams = null;
            try {
                // 1. 构造 PQC 参数对象
                // 使用 Dilithium3 参数集
                privParams = new DilithiumPrivateKeyParameters(
                        PQCConfig.getDilithiumParameters(), rawPrivKey, null);

                // 2. 执行签名
                DilithiumSigner signer = new DilithiumSigner();
                signer.init(true, privParams);
                return signer.generateSignature(message);

            } catch (Exception e) {
                // 将异常信息封装并抛出给调用方
                throw new RemoteException("PQC Signing Error: " + e.getMessage());
            } finally {
                // 3. 立即擦除传入的明文私钥数组
                if (rawPrivKey != null) {
                    Arrays.fill(rawPrivKey, (byte) 0);
                }
                // 4. 延迟 100ms 结束进程，确保 Binder 调用返回后再销毁内存
                // 这样可以强制操作系统回收堆内存中无法手动 destroy 的 privParams 对象
                new Handler(Looper.getMainLooper()).postDelayed(() -> {
                    android.os.Process.killProcess(android.os.Process.myPid());
                }, 100);
            }
        }

    };

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }
}