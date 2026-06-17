package com.example.ailiro_ud;

import android.content.Context;

import java.security.PrivateKey;
import java.security.PublicKey;

import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumPrivateKeyParameters;
import org.bouncycastle.pqc.crypto.crystals.dilithium.DilithiumSigner;

public final class PQCSigner {

    private PQCSigner() {}

    // ---------- Dilithium：设备签名 ----------

    //这里用了keystore kek来取私钥，为缩短私钥暴露时间使用中间件，通过沙箱进程server做签名隔离，client通过Binder与隔离进程通讯，结束server自毁
    //价值在于通过Android进程边界弥补DilithiumPrivateParameters无法彻底擦除的缺陷
    public static byte[] MSDLSSign(byte[] message, Context context) throws Exception {

        PQCSignerClient client = new PQCSignerClient(context);
        try {
            return client.signSynchronous(message);
        } catch (Exception e){
                throw e;
            }
    }

    // ---------- Dilithium：验签（不需要设备私钥） ----------

    public static boolean MLDLSVerify(byte[] message,
                                 byte[] signature,
                                 PublicKey publicKey) throws Exception {

        return PQCUtil.dilithiumVerify(message, signature, publicKey);
    }

    private static void zeroBytes(byte[] b) {
        if (b != null) {
            for (int i = 0; i < b.length; i++) b[i] = 0;
        }
    }


}
