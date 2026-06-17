package com.example.ailiro_ud;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import org.bouncycastle.pqc.jcajce.provider.BouncyCastlePQCProvider;

import java.io.File;
import java.io.FileWriter;
import java.math.BigInteger;
import java.security.Key;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.Date;

/**
 * PQ Credential Issuer tool — generates Dilithium identity keys and a CI-signed
 * certificate chain for both Reader and Access Credential (User Device).
 *
 * <p><b>Reader side</b>: invoke via Maven ({@code mvn exec:java -Dexec.mainClass=...}).
 * <p><b>UD / Android side</b>: call {@link #generateAll(File)} from an Activity
 * (e.g., a dev-only button) or from a JVM unit-test on the host.
 *
 * Output files per {@link #generateAll(File)}:
 * <pre>
 *   CI_dilithium_priv.pem      CI_dilithium_cert.pem
 *   Reader_dilithium_priv.pem  Reader_dilithium_cert.pem
 *   Reader_kyber_priv.pem      Reader_kyber_pub.pem
 *   AccessCred_dilithium_priv.pem  AccessCred_dilithium_cert.pem
 * </pre>
 */
public class PQCertTool {

    private static final String PQC_PROVIDER = "BCPQC";

    static {
        if (Security.getProvider("BC") == null) {
            Security.addProvider(new BouncyCastleProvider());
        }
        if (Security.getProvider(PQC_PROVIDER) == null) {
            Security.addProvider(new BouncyCastlePQCProvider());
        }
    }

    // ------------------------------------------------------------------ //
    //  Public entry point                                                   //
    // ------------------------------------------------------------------ //

    /**
     * Generate the full PQ certificate chain under {@code outputDir}.
     * Can be called from an Android Activity, Instrumented Test, or JVM unit test.
     *
     * @param outputDir target directory (will be created if absent)
     */
    public static void generateAll(File outputDir) throws Exception {
        outputDir.mkdirs();

        log("=== PQ-Aliro Credential Issuer Tool ===");
        log(String.format("Mode=%s  Dilithium=%d  Kyber=%d",
                PQCConfig.MODE, PQCConfig.DILITHIUM_LEVEL, PQCConfig.KYBER_LEVEL));
        log("Output: " + outputDir.getAbsolutePath());

        // 1) CI self-signed Dilithium identity
        log("\n[1/3] CI Dilithium keypair + self-signed cert...");
        KeyPair ciKeys = PQCUtil.generateDilithiumKeyPair();
        X509Certificate ciCert = generateDilithiumSelfSignedCert(
                ciKeys, "CN=Aliro_CI, O=Aliro, C=CN");
        saveKey(ciKeys.getPrivate(), new File(outputDir, "CI_dilithium_priv.pem"),  false);
        saveCert(ciCert,             new File(outputDir, "CI_dilithium_cert.pem"));

        // 2) Reader Dilithium identity (signed by CI)
        log("\n[2/3] Reader Dilithium keypair + CI-signed cert...");
        KeyPair readerDilKeys = PQCUtil.generateDilithiumKeyPair();
        X509Certificate readerCert = generateDilithiumCertSignedByCI(
                readerDilKeys.getPublic(), "CN=Aliro_Reader, O=Aliro, C=CN",
                ciKeys.getPrivate(), ciCert);
        saveKey(readerDilKeys.getPrivate(), new File(outputDir, "Reader_dilithium_priv.pem"), false);
        saveCert(readerCert,                new File(outputDir, "Reader_dilithium_cert.pem"));

        // 2b) Reader Kyber keypair (template/long-term; each session uses ephemeral)
        log("\n[2b/3] Reader Kyber keypair...");
        KeyPair readerKybKeys = PQCUtil.generateKyberKeyPair();
        saveKey(readerKybKeys.getPrivate(), new File(outputDir, "Reader_kyber_priv.pem"), false);
        saveKey(readerKybKeys.getPublic(),  new File(outputDir, "Reader_kyber_pub.pem"),  true);

        // 3) Access Credential (UD identity) Dilithium keypair + CI-signed cert
        log("\n[3/3] Access Credential Dilithium keypair + CI-signed cert...");
        KeyPair acKeys = PQCUtil.generateDilithiumKeyPair();
        X509Certificate acCert = generateDilithiumCertSignedByCI(
                acKeys.getPublic(), "CN=Aliro_AccessCredential, O=Aliro, C=CN",
                ciKeys.getPrivate(), ciCert);
        saveKey(acKeys.getPrivate(), new File(outputDir, "AccessCred_dilithium_priv.pem"), false);
        saveCert(acCert,             new File(outputDir, "AccessCred_dilithium_cert.pem"));

        // Sanity verification
        log("\n=== Verification ===");
        log("Reader cert valid:       " + verifyCert(readerCert, ciCert.getPublicKey()));
        log("AccessCred cert valid:   " + verifyCert(acCert,     ciCert.getPublicKey()));
        log("CI self-sig valid:       " + verifyCert(ciCert,     ciCert.getPublicKey()));
        log("\nDone.");
    }

    // ------------------------------------------------------------------ //
    //  Certificate builders                                                 //
    // ------------------------------------------------------------------ //

    private static X509Certificate generateDilithiumSelfSignedCert(
            KeyPair kp, String dn) throws Exception {

        X500Name name = new X500Name(dn);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date from = new Date();
        Date to   = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                name, serial, from, to, name, kp.getPublic());

        ContentSigner signer = new JcaContentSignerBuilder(dilithiumSigAlg())
                .setProvider(PQC_PROVIDER)
                .build(kp.getPrivate());

        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static X509Certificate generateDilithiumCertSignedByCI(
            PublicKey subjectPub, String subjectDN,
            PrivateKey caPriv, X509Certificate caCert) throws Exception {

        X500Name issuer  = new X500Name(caCert.getSubjectX500Principal().getName());
        X500Name subject = new X500Name(subjectDN);
        BigInteger serial = BigInteger.valueOf(System.currentTimeMillis());
        Date from = new Date();
        Date to   = new Date(System.currentTimeMillis() + 365L * 24 * 60 * 60 * 1000);

        JcaX509v3CertificateBuilder builder = new JcaX509v3CertificateBuilder(
                issuer, serial, from, to, subject, subjectPub);

        ContentSigner signer = new JcaContentSignerBuilder(dilithiumSigAlg())
                .setProvider(PQC_PROVIDER)
                .build(caPriv);

        X509CertificateHolder holder = builder.build(signer);
        return new JcaX509CertificateConverter().setProvider("BC").getCertificate(holder);
    }

    private static String dilithiumSigAlg() {
        switch (PQCConfig.DILITHIUM_LEVEL) {
            case 2: return "Dilithium2";
            case 3: return "Dilithium3";
            case 5: return "Dilithium5";
            default:
                throw new IllegalStateException(
                        "Unsupported Dilithium level: " + PQCConfig.DILITHIUM_LEVEL);
        }
    }

    private static boolean verifyCert(X509Certificate cert, PublicKey caPub) {
        try {
            cert.verify(caPub, PQC_PROVIDER);
            return true;
        } catch (Exception e) {
            log("  verify failed: " + e.getMessage());
            return false;
        }
    }

    // ------------------------------------------------------------------ //
    //  PEM helpers                                                          //
    // ------------------------------------------------------------------ //

    private static void saveCert(X509Certificate cert, File file) throws Exception {
        writePem("CERTIFICATE", cert.getEncoded(), file);
    }

    private static void saveKey(Key key, File file, boolean isPublic) throws Exception {
        writePem(isPublic ? "PUBLIC KEY" : "PRIVATE KEY", key.getEncoded(), file);
    }

    private static void writePem(String type, byte[] der, File file) throws Exception {
        String b64 = Base64.getEncoder().encodeToString(der);
        StringBuilder sb = new StringBuilder();
        sb.append("-----BEGIN ").append(type).append("-----\n");
        for (int i = 0; i < b64.length(); i += 64) {
            sb.append(b64, i, Math.min(i + 64, b64.length())).append('\n');
        }
        sb.append("-----END ").append(type).append("-----\n");

        file.getParentFile().mkdirs();
        try (FileWriter fw = new FileWriter(file)) {
            fw.write(sb.toString());
        }
        log("  -> " + file.getName());
    }

    private static void log(String msg) {
        System.out.println(msg);
    }
}
