package com.example.ailiro_ud;

import java.security.PrivateKey;
import java.security.PublicKey;

import javax.crypto.SecretKey;

import org.bouncycastle.jcajce.SecretKeyWithEncapsulation;

/**
 * Device-level Kyber KEM primitive.
 *
 * Responsibilities:
 *  - Encapsulation using a given public key (reader / peer side)
 *  - Decapsulation using the User Device Kyber private key
 *
 * Does NOT:
 *  - Generate key pairs
 *  - Perform signatures
 *  - Access UI or Context
 */
public final class PQCKEM {

    private PQCKEM() {}

    // =====================================================
    // Encapsulation (no device private key required)
    // =====================================================

    /**
     * Encapsulate a shared secret to the given Kyber public key.
     *
     * @param publicKey            Kyber public key
     * @param secretKeyAlgorithm   Algorithm name for derived SecretKey (e.g. "AES")
     * @return encapsulated shared secret and ciphertext
     */
    public static KyberEncapsulated encapsulate(PublicKey publicKey,
                                                String secretKeyAlgorithm)
            throws Exception {

        SecretKeyWithEncapsulation sek =
                PQCUtil.kyberEncapsulateRaw(publicKey, secretKeyAlgorithm);

        return new KyberEncapsulated(
                sek,
                sek.getEncapsulation()
        );
    }

    // =====================================================
    // Decapsulation (device private key)
    // =====================================================

    /**
     * Decapsulate a shared secret using the User Device Kyber private key.
     *
     * @param encapsulation        Kyber ciphertext
     * @param secretKeyAlgorithm   Algorithm name for derived SecretKey (e.g. "AES")
     * @return derived shared secret
     */
    public static SecretKey decapsulate(byte[] encapsulation,
                                        String secretKeyAlgorithm)
            throws Exception {

        byte[] privBytes = PQKeyManager.getKyberPrivateKey();
        if (privBytes == null) {
            throw new IllegalStateException("Kyber private key unavailable");
        }

        try {
            PrivateKey sk =
                    PQCUtil.decodeKyberPrivateKey(privBytes);

            return PQCUtil.kyberDecapsulateRaw(
                    sk,
                    encapsulation,
                    secretKeyAlgorithm
            );
        } finally {
            zeroBytes(privBytes);
        }
    }

    // =====================================================
    // Data holder
    // =====================================================

    public static final class KyberEncapsulated {

        public final SecretKey sharedSecret;
        public final byte[] encapsulation;

        public KyberEncapsulated(SecretKey sharedSecret,
                                 byte[] encapsulation) {
            this.sharedSecret = sharedSecret;
            this.encapsulation = encapsulation;
        }

        public byte[] getsharedSecret(){
            return this.sharedSecret.getEncoded();
        }

        public byte[] getencapsulation(){
            return this.encapsulation;
        }

    }

    // =====================================================
    // Utils
    // =====================================================

    private static void zeroBytes(byte[] data) {
        if (data == null) return;
        for (int i = 0; i < data.length; i++) {
            data[i] = 0;
        }
    }
}
